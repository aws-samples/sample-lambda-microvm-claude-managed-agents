"""Configure a Claude Managed Agent for the Data Analyst example.

Creates the agent and self-hosted environment on Claude Platform on AWS (CPOA).

Prerequisites:
    pip install "anthropic[aws]" python-dotenv
    cp .env.example .env  # Fill in your CPOA API key and workspace ID

Usage:
    python setup-agent.py
"""

import os
from pathlib import Path

from dotenv import load_dotenv
from anthropic import Anthropic

load_dotenv()

# ── Configuration ──────────────────────────────────────────────────────────

AGENT_NAME = "Data Analyst (S3 Files)"
AGENT_DESCRIPTION = (
    "Analyzes files stored in S3 using pandas, matplotlib. "
    "Data mounted via S3 Files at /mnt/s3files/."
)

SYSTEM_PROMPT_PATH = Path(__file__).parent / "system-prompt.md"

REGION = os.environ.get("AWS_REGION", "us-west-2")
WORKSPACE_ID = os.environ.get("ANTHROPIC_AWS_WORKSPACE_ID")
API_KEY = os.environ.get("ANTHROPIC_AWS_API_KEY")

if not WORKSPACE_ID:
    print("ERROR: Set ANTHROPIC_AWS_WORKSPACE_ID in .env")
    raise SystemExit(1)
if not API_KEY:
    print("ERROR: Set ANTHROPIC_AWS_API_KEY in .env")
    raise SystemExit(1)

# ── Client setup (CPOA API key auth) ──────────────────────────────────────

client = Anthropic(
    auth_token=API_KEY,
    base_url=f"https://aws-external-anthropic.{REGION}.api.aws",
    default_headers={"anthropic-workspace-id": WORKSPACE_ID},
)

# ── Read system prompt ─────────────────────────────────────────────────────

system_prompt = SYSTEM_PROMPT_PATH.read_text()

# ── Create agent and environment ──────────────────────────────────────────

print("Setting up Managed Agent...")
print(f"  Name: {AGENT_NAME}")
print(f"  Region: {REGION}")
print(f"  Workspace: {WORKSPACE_ID}")
print()

# NOTE: Programmatic agent creation requires the managed-agents beta API.
# If the SDK version supports it:
#
#   agent = client.beta.agents.create(
#       name=AGENT_NAME,
#       description=AGENT_DESCRIPTION,
#       model="claude-sonnet-4-6",
#       system=system_prompt,
#       tools=[{"type": "bash_20250124"}, {"type": "text_editor_20250124"}],
#   )
#
# Otherwise, create via the Claude Console:

print("─" * 60)
print("SETUP STEPS:")
print("─" * 60)
print()
print("1. Open Claude Console → Agents (https://console.anthropic.com)")
print(f"2. Create agent named: {AGENT_NAME}")
print(f"3. Paste system prompt from: {SYSTEM_PROMPT_PATH.name}")
print("4. Enable tools: bash, text_editor")
print("5. Create a 'self_hosted' environment")
print("6. Save these to your .env:")
print("     ANTHROPIC_AGENT_ID=agent_...")
print("     ANTHROPIC_ENVIRONMENT_ID=env_...")
print()
print("─" * 60)
print("VALIDATION:")
print("─" * 60)
print()

# Validate connectivity
try:
    models = client.models.list()
    print(f"  ✓ API connection works ({len(models.data)} models available)")
except Exception as e:
    print(f"  ✗ API connection failed: {e}")
    raise SystemExit(1)

# Validate environment if configured
env_id = os.environ.get("ANTHROPIC_ENVIRONMENT_ID")
if env_id:
    try:
        work = client.beta.environments.work.poll(
            environment_id=env_id, timeout=1
        )
        print(f"  ✓ Environment {env_id} accessible (work polling OK)")
    except Exception as e:
        if "timeout" in str(e).lower() or "408" in str(e):
            print(f"  ✓ Environment {env_id} accessible (no pending work)")
        else:
            print(f"  ⚠ Environment poll error: {e}")

# Validate agent if configured
agent_id = os.environ.get("ANTHROPIC_AGENT_ID")
if agent_id and env_id:
    try:
        session = client.beta.sessions.create(
            agent=agent_id,
            environment_id=env_id,
        )
        print(f"  ✓ Session creation works: {session.id}")
    except Exception as e:
        print(f"  ⚠ Session creation failed: {e}")

print()
print("Done. Update .env with agent/environment IDs, then run scripts/demo.sh")
