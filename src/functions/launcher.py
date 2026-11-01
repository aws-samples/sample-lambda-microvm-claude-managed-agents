"""Launcher Lambda: launch one ephemeral MicroVM per started session.

On a ``session.status_run_started`` webhook event, verifies the Anthropic webhook
signature in-process, then launches one MicroVM via ``RunMicrovm`` with the
session dispatch delivered through ``runHookPayload``.

Security model:
- The launcher passes only a *reference* to the environment-key secret into the
  MicroVM. The environment key is fetched by the VM's own execution role.
- The organization API key never reaches AWS compute.

Behavior:
- Rejects deliveries that fail signature verification (401).
- Ignores non-``session.status_run_started`` events (200).
- Dedupes by webhook event id (DynamoDB-backed idempotency).
- Enforces the RunMicrovm 5 TPS rate limit.
- On RunMicrovm failure, returns non-2xx so Anthropic retries.
"""

from __future__ import annotations

import json
import os
from typing import Any, Callable, Optional

from aws_lambda_powertools import Logger
from aws_lambda_powertools.utilities import parameters
from aws_lambda_powertools.utilities.idempotency import (
    DynamoDBPersistenceLayer,
    IdempotencyConfig,
    idempotent_function,
)
from aws_lambda_powertools.utilities.idempotency.exceptions import (
    IdempotencyAlreadyInProgressError,
)
from aws_lambda_powertools.utilities.typing import LambdaContext

# Module-scope import so the heavy anthropic + pydantic + httpx cost is paid
# once during init, not on every invocation.
try:
    import anthropic
except ImportError:  # pragma: no cover
    anthropic = None  # type: ignore[assignment]

from shared.constants import (
    DEFAULT_IDLE_POLICY,
    DEFAULT_LAUNCH_TPS_LIMIT,
    DEFAULT_LOGGING_CONFIG,
    DEFAULT_MAX_LIFETIME_SECONDS,
    SESSION_RUN_STARTED,
    all_ingress_arn,
    internet_egress_arn,
)
from shared.microvm_client import Boto3MicroVmClient, LaunchMicroVmError, MicroVmClient
from shared.payload import build_run_hook_payload
from shared.rate_limiter import TokenBucket
from shared.types import LauncherConfig, WebhookEvent

logger = Logger(service="claude-microvm-sandbox-launcher")

_SECRET_CACHE_SECONDS = 300
_IDEMPOTENCY_TTL_SECONDS = DEFAULT_MAX_LIFETIME_SECONDS

_SESSION_ID_PREFIX = "sesn_"
_SESSION_ID_MAX_LEN = 128


def verify_signature(body: str, headers: dict[str, str], signing_secret: str) -> bool:
    """Verify a webhook delivery using the Anthropic SDK.

    Returns True if the signature is valid and the payload is fresh, else False.
    """
    try:
        client = anthropic.Anthropic(api_key="unused-for-webhook-verification")
        client.beta.webhooks.unwrap(body, headers=headers, key=signing_secret)
        return True
    except Exception as exc:  # noqa: BLE001
        logger.warning("webhook signature verification failed: %s", exc)
        return False


class Launcher:
    """Pure launch logic, testable without AWS."""

    def __init__(
        self,
        config: LauncherConfig,
        client: MicroVmClient,
        *,
        rate_limiter: Optional[TokenBucket] = None,
        executor: Optional[Callable[[WebhookEvent], dict[str, Any]]] = None,
    ) -> None:
        self._config = config
        self._client = client
        self._rate_limiter = rate_limiter or TokenBucket(config.launch_tps_limit)
        self._executor = executor or self._launch_and_dispatch

    def set_executor(self, executor: Callable[[WebhookEvent], dict[str, Any]]) -> None:
        """Install the idempotency-wrapped executor."""
        self._executor = executor

    def _launch_and_dispatch(self, event: WebhookEvent) -> dict[str, Any]:
        """Launch one MicroVM with the run hook payload. Raises on failure."""
        run_hook_payload = build_run_hook_payload(event, self._config)
        self._rate_limiter.acquire()
        launched = self._client.launch_microvm(
            image_identifier=self._config.image_identifier,
            run_hook_payload=run_hook_payload,
            max_lifetime_seconds=self._config.max_lifetime_seconds,
            execution_role_arn=self._config.execution_role_arn,
            idle_policy=DEFAULT_IDLE_POLICY,
            logging_config=DEFAULT_LOGGING_CONFIG,
            ingress_network_connectors=[all_ingress_arn(self._config.aws_region)],
            egress_network_connectors=[internet_egress_arn(self._config.aws_region)],
        )

        logger.info(
            "launched microvm_id=%s for session_id=%s",
            launched.microvm_id,
            event.session_id,
        )
        return {"microvm_id": launched.microvm_id}

    def handle(self, event: WebhookEvent) -> dict[str, Any]:
        """Handle one parsed, verified webhook event. Returns an API Gateway response."""
        if event.data_type != SESSION_RUN_STARTED:
            logger.info("ignoring non-start event type=%s", event.data_type)
            return {"statusCode": 200, "body": "ignored"}

        # Validate required fields. Return 200 (not 4xx) for malformed events
        # so Anthropic doesn't retry (a malformed event won't become valid).

        if not event.event_id:
            logger.warning("ignoring event: missing event_id (cannot dedupe)")
            return {"statusCode": 200, "body": "ignored"}

        if not event.session_id:
            logger.warning("ignoring event id=%s: missing session_id", event.event_id)
            return {"statusCode": 200, "body": "ignored"}

        # Loose shape sanity — log-only, never rejects.
        if not (
            event.session_id.startswith(_SESSION_ID_PREFIX)
            and len(event.session_id) <= _SESSION_ID_MAX_LEN
        ):
            logger.warning(
                "event id=%s has an implausible session_id shape (proceeding): %r",
                event.event_id,
                event.session_id,
            )

        try:
            result = self._executor(event)
        except IdempotencyAlreadyInProgressError:
            logger.info("event id=%s already in progress; not re-launching", event.event_id)
            return {"statusCode": 200, "body": "in progress"}
        except LaunchMicroVmError as exc:
            logger.error("RunMicrovm failed for session_id=%s: %s", event.session_id, exc)
            return {
                "statusCode": 502,
                "body": json.dumps(
                    {"error": "run_microvm_failed", "session_id": event.session_id}
                ),
            }

        return {
            "statusCode": 200,
            "body": json.dumps(
                {"microvm_id": result.get("microvm_id"), "session_id": event.session_id}
            ),
        }


def _load_config() -> LauncherConfig:
    region = os.environ.get("AWS_REGION", "us-west-2")
    return LauncherConfig(
        environment_id=os.environ["ANTHROPIC_ENVIRONMENT_ID"],
        image_identifier=os.environ["MICROVM_IMAGE_IDENTIFIER"],
        environment_key_param_name=os.environ["ENVIRONMENT_KEY_PARAM_NAME"],
        execution_role_arn=os.environ["MICROVM_EXECUTION_ROLE_ARN"],
        aws_region=region,
        signing_param_name=os.environ.get("SIGNING_PARAM_NAME"),
        base_url=os.environ.get("ANTHROPIC_BASE_URL") or None,
        max_lifetime_seconds=int(
            os.environ.get("MAX_LIFETIME_SECONDS", DEFAULT_MAX_LIFETIME_SECONDS)
        ),
        launch_tps_limit=int(os.environ.get("LAUNCH_TPS_LIMIT", DEFAULT_LAUNCH_TPS_LIMIT)),
    )


_idempotency_config: Optional[IdempotencyConfig] = None
_idempotent_run: Optional[Callable[..., dict[str, Any]]] = None


def _build_idempotency(table_name: str) -> None:
    """Construct the DynamoDB persistence layer and idempotent wrapper once."""
    global _idempotency_config, _idempotent_run
    if _idempotent_run is not None:
        return

    persistence = DynamoDBPersistenceLayer(table_name=table_name, expiry_attr="expiration")
    _idempotency_config = IdempotencyConfig(
        event_key_jmespath="event_id",
        expires_after_seconds=_IDEMPOTENCY_TTL_SECONDS,
    )

    @idempotent_function(
        data_keyword_argument="event_record",
        persistence_store=persistence,
        config=_idempotency_config,
    )
    def _run(event_record: dict[str, Any], *, launch: Callable[[], dict[str, Any]]) -> dict[str, Any]:
        return launch()

    _idempotent_run = _run


def _idempotent_executor(
    launcher: Launcher, context: Optional[LambdaContext]
) -> Callable[[WebhookEvent], dict[str, Any]]:
    """Wrap the launch side effect in DynamoDB idempotency."""

    def executor(event: WebhookEvent) -> dict[str, Any]:
        if _idempotency_config is not None and context is not None:
            _idempotency_config.register_lambda_context(context)
        assert _idempotent_run is not None
        return _idempotent_run(
            event_record={"event_id": event.event_id},
            launch=lambda: launcher._launch_and_dispatch(event),
        )

    return executor


@logger.inject_lambda_context
def handler(
    event: dict[str, Any],
    context: Optional[LambdaContext] = None,
    *,
    verifier: Callable[[str, dict[str, str], str], bool] = verify_signature,
) -> dict[str, Any]:
    """Lambda entry point (API Gateway proxy integration)."""
    config = _load_config()

    body = event.get("body")
    raw_body = body if isinstance(body, str) else json.dumps(body or {})
    headers = event.get("headers") or {}

    if config.signing_param_name:
        signing_secret = parameters.get_parameter(
            config.signing_param_name, max_age=_SECRET_CACHE_SECONDS, decrypt=True
        )
        if not verifier(raw_body, headers, signing_secret):
            logger.info("denying webhook: signature verification failed")
            return {"statusCode": 401, "body": "signature verification failed"}
    else:
        # No signing secret configured: verification cannot run, so deny
        # instead of accepting an unverified webhook.
        logger.info("denying webhook: signing secret not configured")
        return {"statusCode": 401, "body": "signature verification not configured"}

    client = Boto3MicroVmClient(region_name=config.aws_region)
    launcher = Launcher(config, client)

    table_name = os.environ.get("IDEMPOTENCY_TABLE")
    if table_name:
        _build_idempotency(table_name)
        launcher.set_executor(_idempotent_executor(launcher, context))

    payload = json.loads(raw_body) if raw_body else {}
    return launcher.handle(WebhookEvent.from_payload(payload))
