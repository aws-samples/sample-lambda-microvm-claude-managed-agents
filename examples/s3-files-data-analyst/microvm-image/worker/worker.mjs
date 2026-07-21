// In-MicroVM worker for the Data Analyst Agent.
//
// Extends the base worker with S3 Files mounting: before the agent session
// starts, this worker mounts the S3 Files filesystem at /mnt/s3files/ so the
// agent can read/write data using standard file operations.
//
// SNAPSHOT SAFETY: This worker uses crypto.randomUUID() (CSPRNG) for any
// per-VM unique state. Math.random() is NOT safe across snapshot resume.
// See: references/snapshots-and-uniqueness.md in the Lambda MicroVMs skill.
//
// Lifecycle:
//   /ready     → snapshot gate (image build)
//   /run       → mount S3 Files → handle session → exit
//   /terminate → cleanup

import http from "node:http";
import { execSync } from "node:child_process";
import { SecretsManagerClient, GetSecretValueCommand } from "@aws-sdk/client-secrets-manager";
import Anthropic from "@anthropic-ai/sdk";
import { WorkPoller, EnvironmentWorker } from "@anthropic-ai/sdk/helpers/beta/environments";

const HOOK_PORT = Number(process.env.HOOK_PORT || 9000);
const HOOK_HOST = "0.0.0.0";
const HOOK_PREFIX = "/aws/lambda-microvms/runtime/v1";

let sessionStarted = false;

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks).toString("utf-8");
}

/**
 * Build the Anthropic client for Claude Platform on AWS.
 *
 * On CPOA, workers authenticate via:
 *   1. SigV4 (IAM execution role) — default when AnthropicSelfHostedEnvironmentAccess
 *      policy is attached. No secrets needed.
 *   2. API key from Secrets Manager — fallback for API key auth mode.
 *
 * Environment keys (sk-ant-env01-...) are NOT used on CPOA.
 */
async function buildClient({ secretId, region, workspaceId, baseURL }) {
  const cpwBaseURL = baseURL || `https://aws-external-anthropic.${region}.api.aws`;

  // Try to load API key from Secrets Manager (optional fallback)
  let apiKey = null;
  if (secretId) {
    try {
      const smClient = new SecretsManagerClient({ region });
      const result = await smClient.send(new GetSecretValueCommand({ SecretId: secretId }));
      const val = result.SecretString || "";
      if (val.startsWith("aws-external-anthropic-api-key-")) {
        apiKey = val;
      }
    } catch (err) {
      console.warn(`worker: could not fetch secret ${secretId}: ${err.message}`);
    }
  }

  let client;
  const defaultHeaders = workspaceId ? { 'anthropic-workspace-id': workspaceId } : {};

  if (apiKey) {
    // API key mode: pass as authToken (WorkPoller uses bearer auth internally)
    // CPOA requires anthropic-workspace-id header on all requests
    client = new Anthropic({ authToken: apiKey, baseURL: cpwBaseURL, defaultHeaders });
    console.log(`worker: auth=api-key, region=${region}, workspace=${workspaceId}`);
  } else {
    // SigV4 mode: use IAM credentials from execution role
    client = new Anthropic({
      credentials: { type: "aws_iam", region },
      baseURL: cpwBaseURL,
      defaultHeaders,
    });
    console.log(`worker: auth=sigv4, region=${region}, workspace=${workspaceId}`);
  }

  return { client, apiKey };
}

/**
 * Mount S3 Files filesystem before the session starts.
 * Uses the mount-s3files helper script installed in the image.
 *
 * NOTE: The /run hook has a max timeout of 60s (configured at image creation).
 * NFS mounts typically complete in 2-10s but can take up to 30s on first access.
 * The mount is done AFTER acknowledging the /run hook (ackThenRun pattern) so
 * we are not constrained by the hook timeout.
 */
function mountS3Files(mountDns, mountPath = "/mnt/s3files") {
  if (!mountDns) {
    console.warn("worker: S3_FILES_MOUNT_DNS not provided, skipping mount");
    return false;
  }

  try {
    const output = execSync(`mount-s3files "${mountDns}" "${mountPath}"`, {
      timeout: 45_000, // 45s timeout for mount
      encoding: "utf-8",
      stdio: ["pipe", "pipe", "pipe"],
    });
    console.log(output.trim());
    return true;
  } catch (err) {
    console.error("worker: S3 Files mount failed:", err.message);
    // Don't crash the session — agent just won't have file access
    // This is graceful degradation: the agent can still run code,
    // just without the S3-backed filesystem.
    return false;
  }
}

async function handleSession(dispatch) {
  const sessionId = dispatch.ANTHROPIC_SESSION_ID;
  const environmentId = dispatch.ANTHROPIC_ENVIRONMENT_ID;
  const secretId = dispatch.ENVIRONMENT_KEY_SECRET_ID;
  const region = dispatch.AWS_REGION;
  const baseURL = dispatch.ANTHROPIC_BASE_URL || undefined;

  // ── Mount S3 Files before starting the session ──────────────────────────
  const mountDns = dispatch.S3_FILES_MOUNT_DNS;
  const mountPath = dispatch.S3_FILES_MOUNT_PATH || "/mnt/s3files";

  const mounted = mountS3Files(mountDns, mountPath);
  console.log(`worker: S3 Files mounted=${mounted} at ${mountPath}`);

  // ── Start the agent session ─────────────────────────────────────────────
  const workspaceId = dispatch.ANTHROPIC_AWS_WORKSPACE_ID || process.env.ANTHROPIC_AWS_WORKSPACE_ID;
  const { client, apiKey } = await buildClient({ secretId, region, workspaceId, baseURL });

  // CPOA auth: API key mode uses WorkPoller; SigV4 mode polls directly
  const environmentKey = apiKey || undefined;

  if (environmentKey) {
    // API key mode: WorkPoller uses the key as bearer auth for polling
    const worker = new EnvironmentWorker({ client, environmentId, environmentKey, workdir: "/workspace" });
    console.log(`worker: polling for session ${sessionId} (api-key mode)`);
    const poller = new WorkPoller({
      client,
      environmentId,
      environmentKey,
      reclaimOlderThanMs: 2000,
      drain: true,
      autoStop: false,
    });

    for await (const work of poller) {
      if (work.data.type !== "session" || work.data.id !== sessionId) continue;
      console.log(`worker: handling session ${sessionId} (work ${work.id})`);
      await worker.handleItem({ workId: work.id, environmentId, sessionId, environmentKey });
      console.log(`worker: session ${sessionId} complete`);
      return;
    }
    console.warn(`worker: no work item found for session ${sessionId}`);
  } else {
    // SigV4 mode: poll work queue directly using the IAM-authenticated client
    // WorkPoller cannot be used here because it requires an environmentKey
    // for its internal sub-client (authToken). With SigV4, the main client's
    // credential chain handles auth for all requests.
    console.log(`worker: polling for session ${sessionId} (sigv4 mode)`);

    for (let attempt = 0; attempt < 12; attempt++) {
      try {
        const work = await client.beta.environments.work.poll(environmentId, {
          timeout: 5,
        });
        if (!work || !work.id) continue;
        if (work.data?.type !== "session" || work.data?.id !== sessionId) continue;

        console.log(`worker: found work ${work.id} for session ${sessionId}`);
        await client.beta.environments.work.ack(work.id, { environment_id: environmentId });
        console.log(`worker: acked work ${work.id}`);

        // Use EnvironmentWorker for tool execution (no environmentKey needed)
        const worker = new EnvironmentWorker({ client, environmentId, workdir: "/workspace" });
        await worker.handleItem({ workId: work.id, environmentId, sessionId });
        console.log(`worker: session ${sessionId} complete`);
        return;
      } catch (err) {
        if (err?.status === 408 || err?.message?.includes("timeout")) continue;
        throw err;
      }
    }
    console.warn(`worker: no work item found for session ${sessionId} after polling`);
  }
}

function ackThenRun(res, dispatch) {
  res.writeHead(200, { "content-type": "application/json" });
  res.end(JSON.stringify({ status: "accepted" }));
  if (sessionStarted) return;
  sessionStarted = true;
  handleSession(dispatch).then(
    () => process.exit(0),
    (err) => {
      console.error("worker: session failed", err);
      process.exit(1);
    },
  );
}

const server = http.createServer(async (req, res) => {
  const ok = (body = { status: "ok" }) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify(body));
  };

  if (req.method !== "POST" || !req.url.startsWith(HOOK_PREFIX)) {
    res.writeHead(404);
    res.end();
    return;
  }
  const hook = req.url.slice(HOOK_PREFIX.length + 1);

  switch (hook) {
    case "ready":
    case "validate":
    case "resume":
    case "suspend":
    case "terminate":
      ok();
      return;
    case "run": {
      try {
        const raw = await readBody(req);
        const envelope = raw ? JSON.parse(raw) : {};
        const inner = envelope.runHookPayload
          ? JSON.parse(envelope.runHookPayload)
          : envelope;
        const dispatch = inner.session || inner;
        if (!dispatch.ANTHROPIC_SESSION_ID) {
          console.error("worker: /run hook missing ANTHROPIC_SESSION_ID:", raw);
          res.writeHead(400, { "content-type": "application/json" });
          res.end(JSON.stringify({ error: "missing ANTHROPIC_SESSION_ID" }));
          return;
        }
        ackThenRun(res, dispatch);
      } catch (err) {
        console.error("worker: /run hook error", err);
        res.writeHead(400, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: "invalid run payload" }));
      }
      return;
    }
    default:
      res.writeHead(404);
      res.end();
  }
});

server.listen(HOOK_PORT, HOOK_HOST, () => {
  console.log(`worker: hook server listening on ${HOOK_HOST}:${HOOK_PORT}`);
});
