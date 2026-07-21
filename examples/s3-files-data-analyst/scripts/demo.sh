#!/bin/bash
# demo.sh — Create a Claude session and demonstrate the agent analyzing data.
#
# This script creates a session, sends a data analysis request, and polls for
# the agent's response via the work queue (handled by the MicroVM worker).
#
# Prerequisites:
#   - The stack is deployed (./scripts/deploy.sh)
#   - MicroVM image is built (./scripts/build-image.sh)
#   - Sample data is uploaded (./scripts/upload-sample-data.sh)
#   - Agent is configured in the Claude Console
#   - .env has ANTHROPIC_AWS_API_KEY, ANTHROPIC_AWS_WORKSPACE_ID,
#     ANTHROPIC_AGENT_ID, ANTHROPIC_ENVIRONMENT_ID
#
# Usage:
#   ./scripts/demo.sh [prompt]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$SCRIPT_DIR")"

# Default prompt
PROMPT="${1:-Analyze the sales data in /mnt/s3files/data/. Compare Q1 and Q2 performance by region. Which regions grew? Which declined? Generate a summary report.}"

echo "═══════════════════════════════════════════════════════════════"
echo "  Data Analyst Agent — Demo"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "Prompt: $PROMPT"
echo ""
echo "─────────────────────────────────────────────────────────────"

export EXAMPLE_DIR PROMPT

# Run the demo via Python
python3 - <<'PYTHON'
import os
import sys
import time
from pathlib import Path
from dotenv import load_dotenv

# Load .env from the example directory
load_dotenv(Path(os.environ.get("EXAMPLE_DIR", ".")) / ".env")

from anthropic import Anthropic

REGION = os.environ.get("AWS_REGION", "us-west-2")
WORKSPACE_ID = os.environ["ANTHROPIC_AWS_WORKSPACE_ID"]
API_KEY = os.environ["ANTHROPIC_AWS_API_KEY"]
AGENT_ID = os.environ["ANTHROPIC_AGENT_ID"]
ENVIRONMENT_ID = os.environ["ANTHROPIC_ENVIRONMENT_ID"]

client = Anthropic(
    auth_token=API_KEY,
    base_url=f"https://aws-external-anthropic.{REGION}.api.aws",
    default_headers={"anthropic-workspace-id": WORKSPACE_ID},
)

prompt = os.environ.get("PROMPT", "List the files in /mnt/s3files/data/")

# 1. Create session
print("Creating session...")
session = client.beta.sessions.create(
    agent=AGENT_ID,
    environment_id=ENVIRONMENT_ID,
)
print(f"  Session: {session.id}")

# 2. Send user message
print("Sending message...")
client.beta.sessions.events.send(
    session_id=session.id,
    events=[{
        "type": "user.message",
        "content": [{"type": "text", "text": prompt}],
    }],
)
print("  ✓ Message sent, session is running")
print()

# 3. Poll for session completion
print("Waiting for agent response (MicroVM will pick up the work)...")
print("─" * 60)

for i in range(60):  # 10 min max
    time.sleep(10)
    session = client.beta.sessions.retrieve(session.id)
    status = session.status

    if status == "idle":
        # Session completed — fetch events
        events = client.beta.sessions.events.list(session_id=session.id)
        for ev in events.data:
            if hasattr(ev, "content") and ev.type == "agent.message":
                for block in ev.content:
                    if hasattr(block, "text"):
                        print(block.text)
        break
    elif status == "error":
        print(f"  ✗ Session errored")
        break
    else:
        print(f"  [{(i+1)*10}s] status={status}...")

print()
print("─" * 60)
print(f"✓ Session: {session.id} (status={session.status})")
PYTHON
