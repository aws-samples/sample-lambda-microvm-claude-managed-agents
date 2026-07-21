"""E2E test: deploy → upload data → trigger session → verify output.

This test requires:
- A deployed stack (run deploy.sh first)
- Valid .env credentials
- Sample data uploaded to S3

It creates a real Claude session and verifies the agent can read
files from S3 Files and write output back.

Usage:
    python tests/e2e/test_full_scenario.py
"""

import os
import time
from pathlib import Path

from dotenv import load_dotenv

# Load environment
ENV_PATH = Path(__file__).resolve().parents[2] / ".env"
load_dotenv(ENV_PATH)


def test_agent_reads_s3_files():
    """Create a session, ask agent to list files, verify it sees S3 data."""
    from anthropic import AnthropicAWS

    client = AnthropicAWS()

    agent_id = os.environ["AGENT_ID"]
    environment_id = os.environ["ANTHROPIC_ENVIRONMENT_ID"]

    print("Creating session...")
    session = client.beta.managed_agents.sessions.create(
        agent_id=agent_id,
        environment_id=environment_id,
    )
    print(f"  Session: {session.id}")

    # Ask the agent to list files — this proves S3 Files is mounted
    print("Sending request: list files in /mnt/s3files/data/")
    response = client.beta.managed_agents.sessions.messages.create(
        session_id=session.id,
        messages=[{
            "role": "user",
            "content": "List all files in /mnt/s3files/data/ and show me the first 3 lines of each CSV.",
        }],
        max_tokens=2048,
    )

    # Extract text from response
    response_text = ""
    for block in response.content:
        if hasattr(block, "text"):
            response_text += block.text

    print(f"  Response length: {len(response_text)} chars")

    # Verify the agent saw our sample data
    assert "sales-q1-2026" in response_text.lower() or "q1" in response_text.lower(), \
        f"Agent didn't find Q1 sales file. Response: {response_text[:500]}"

    assert "sales-q2-2026" in response_text.lower() or "q2" in response_text.lower(), \
        f"Agent didn't find Q2 sales file. Response: {response_text[:500]}"

    print("  ✓ Agent successfully read files from S3 Files mount")
    return session.id


def test_agent_writes_output():
    """Ask agent to write analysis output, verify it appears in S3."""
    import boto3
    from anthropic import AnthropicAWS

    client = AnthropicAWS()
    s3 = boto3.client("s3")

    agent_id = os.environ["AGENT_ID"]
    environment_id = os.environ["ANTHROPIC_ENVIRONMENT_ID"]
    bucket_name = os.environ["DATA_BUCKET_NAME"]

    print("\nCreating session for write test...")
    session = client.beta.managed_agents.sessions.create(
        agent_id=agent_id,
        environment_id=environment_id,
    )
    print(f"  Session: {session.id}")

    # Ask agent to generate and save a report
    print("Sending request: generate report")
    response = client.beta.managed_agents.sessions.messages.create(
        session_id=session.id,
        messages=[{
            "role": "user",
            "content": (
                "Read /mnt/s3files/data/sales-q1-2026.csv and write a one-paragraph "
                "summary to /mnt/s3files/outputs/reports/test-summary.md"
            ),
        }],
        max_tokens=2048,
    )

    # Wait for S3 Files sync (close-to-open consistency, typically <5s)
    print("  Waiting for S3 sync...")
    time.sleep(10)

    # Check if the output file appeared in S3
    try:
        result = s3.get_object(
            Bucket=bucket_name,
            Key="outputs/reports/test-summary.md",
        )
        content = result["Body"].read().decode("utf-8")
        print(f"  Output file found: {len(content)} chars")
        assert len(content) > 10, "Output file is too short"
        print("  ✓ Agent successfully wrote output to S3 via S3 Files")
    except s3.exceptions.NoSuchKey:
        print("  ✗ Output file not found in S3 (sync may be delayed)")
        raise


if __name__ == "__main__":
    print("=" * 60)
    print("  E2E Test: Data Analyst Agent with S3 Files")
    print("=" * 60)
    print()

    test_agent_reads_s3_files()
    test_agent_writes_output()

    print()
    print("=" * 60)
    print("  All E2E tests passed ✓")
    print("=" * 60)
