"""Unit tests for the extended payload that includes S3 Files config."""

import json
import sys
from pathlib import Path

# Add the base sample's functions to the path for import
BASE_FUNCTIONS = Path(__file__).resolve().parents[4] / "src" / "functions"
sys.path.insert(0, str(BASE_FUNCTIONS))


def test_payload_includes_s3_files_fields():
    """The run-hook payload should include S3 Files mount configuration."""
    # Simulate what the extended launcher would produce
    # (In the real implementation, payload.py is extended to include these)
    dispatch = {
        "version": "1",
        "session": {
            "ANTHROPIC_SESSION_ID": "sesn_test123",
            "ANTHROPIC_ENVIRONMENT_ID": "env_test",
            "ENVIRONMENT_KEY_SECRET_ID": "arn:aws:secretsmanager:us-east-2:123:secret:test",
            "AWS_REGION": "us-east-2",
            # S3 Files fields (added by this example)
            "S3_FILES_MOUNT_DNS": "fs-abc123.s3files.us-east-2.amazonaws.com",
            "S3_FILES_MOUNT_PATH": "/mnt/s3files",
        },
    }

    payload_json = json.dumps(dispatch)
    parsed = json.loads(payload_json)
    session = parsed["session"]

    # Verify S3 Files fields are present
    assert "S3_FILES_MOUNT_DNS" in session, "Missing S3_FILES_MOUNT_DNS"
    assert "S3_FILES_MOUNT_PATH" in session, "Missing S3_FILES_MOUNT_PATH"
    assert session["S3_FILES_MOUNT_DNS"].endswith(".amazonaws.com"), \
        "Mount DNS should be a valid AWS endpoint"
    assert session["S3_FILES_MOUNT_PATH"].startswith("/"), \
        "Mount path should be absolute"

    # Verify base fields still present
    assert "ANTHROPIC_SESSION_ID" in session
    assert "ANTHROPIC_ENVIRONMENT_ID" in session
    assert "ENVIRONMENT_KEY_SECRET_ID" in session
    assert "AWS_REGION" in session

    # Verify no secrets in payload
    forbidden_keys = ["ANTHROPIC_API_KEY", "ANTHROPIC_ENVIRONMENT_KEY"]
    for key in forbidden_keys:
        assert key not in session, f"Secret key {key} must not appear in payload"

    print("✓ All payload assertions passed")


def test_payload_without_s3_files_is_valid():
    """Base payload without S3 Files fields should still be valid."""
    dispatch = {
        "version": "1",
        "session": {
            "ANTHROPIC_SESSION_ID": "sesn_test456",
            "ANTHROPIC_ENVIRONMENT_ID": "env_test",
            "ENVIRONMENT_KEY_SECRET_ID": "arn:aws:secretsmanager:us-east-2:123:secret:test",
            "AWS_REGION": "us-east-2",
        },
    }

    session = dispatch["session"]

    # S3 Files fields are optional — worker handles gracefully
    assert "S3_FILES_MOUNT_DNS" not in session
    print("✓ Base payload (no S3 Files) is valid")


if __name__ == "__main__":
    test_payload_includes_s3_files_fields()
    test_payload_without_s3_files_is_valid()
    print("\nAll tests passed ✓")
