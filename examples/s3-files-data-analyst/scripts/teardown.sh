#!/bin/bash
# teardown.sh — Remove all resources created by this example.
#
# Removes:
#   - S3 Files mount targets and filesystem
#   - SAM CloudFormation stack
#   - Sample data from S3 (but NOT the bucket itself)
#
# Usage:
#   ./scripts/teardown.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$SCRIPT_DIR")"
INFRA_DIR="$EXAMPLE_DIR/infrastructure"

STACK_NAME=$(grep -oP 'stack_name\s*=\s*"\K[^"]+' "$INFRA_DIR/samconfig.toml" 2>/dev/null || echo "data-analyst-agent")
REGION=$(aws configure get region || echo "us-east-2")
PROJECT_NAME=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Parameters[?ParameterKey=='ProjectName'].ParameterValue" \
    --output text 2>/dev/null || echo "data-analyst-agent")

echo "═══════════════════════════════════════════════════════════════"
echo "  Data Analyst Agent — Teardown"
echo "  Stack: $STACK_NAME"
echo "═══════════════════════════════════════════════════════════════"
echo ""

# ── Step 1: Delete S3 Files resources ─────────────────────────────────────
echo "Step 1/3: Removing S3 Files resources..."

FS_ID=$(aws ssm get-parameter --name "/${PROJECT_NAME}/s3-files-filesystem-id" \
    --query "Parameter.Value" --output text 2>/dev/null || echo "")

if [ -n "$FS_ID" ] && [ "$FS_ID" != "PLACEHOLDER-SET-BY-DEPLOY-SCRIPT" ]; then
    # Delete mount targets first
    MOUNT_TARGETS=$(aws s3files list-mount-targets --file-system-id "$FS_ID" \
        --query "mountTargets[].mountTargetId" --output text 2>/dev/null || echo "")

    for MT in $MOUNT_TARGETS; do
        echo "  Deleting mount target: $MT"
        aws s3files delete-mount-target --mount-target-id "$MT" 2>/dev/null || true
    done

    # Wait for mount targets to be deleted
    sleep 10

    # Delete filesystem
    echo "  Deleting filesystem: $FS_ID"
    aws s3files delete-file-system --file-system-id "$FS_ID" 2>/dev/null || true
else
    echo "  No filesystem found, skipping."
fi

# ── Step 2: Remove sample data ────────────────────────────────────────────
echo ""
echo "Step 2/3: Removing sample data from S3..."

DATA_BUCKET_ARN=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Parameters[?ParameterKey=='DataBucketArn'].ParameterValue" \
    --output text 2>/dev/null || echo "")
BUCKET_NAME=$(echo "$DATA_BUCKET_ARN" | sed 's|arn:aws:s3:::||')

if [ -n "$BUCKET_NAME" ]; then
    echo "  Removing data/ prefix from s3://$BUCKET_NAME..."
    aws s3 rm "s3://$BUCKET_NAME/data/" --recursive 2>/dev/null || true
    echo "  Removing outputs/ prefix..."
    aws s3 rm "s3://$BUCKET_NAME/outputs/" --recursive 2>/dev/null || true
    echo "  (Bucket itself preserved)"
else
    echo "  Could not determine bucket, skipping."
fi

# ── Step 3: Delete CloudFormation stack ────────────────────────────────────
echo ""
echo "Step 3/3: Deleting CloudFormation stack..."
aws cloudformation delete-stack --stack-name "$STACK_NAME"
echo "  Waiting for stack deletion..."
aws cloudformation wait stack-delete-complete --stack-name "$STACK_NAME" 2>/dev/null || true

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  ✓ Teardown complete."
echo "═══════════════════════════════════════════════════════════════"
