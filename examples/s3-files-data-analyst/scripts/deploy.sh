#!/bin/bash
# deploy.sh — Deploy the Data Analyst Agent stack.
#
# This script:
#   1. Builds and deploys the SAM stack (launcher, API, secrets, IAM)
#   2. Creates the S3 Files filesystem via CLI (not yet in CloudFormation)
#   3. Creates mount targets in each subnet
#   4. Updates SSM parameters with the filesystem details
#
# Prerequisites:
#   - AWS CLI v2+ with s3files service model installed
#   - SAM CLI
#   - Configured AWS credentials with appropriate permissions
#
# Usage:
#   ./scripts/deploy.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$SCRIPT_DIR")"
INFRA_DIR="$EXAMPLE_DIR/infrastructure"

# Load config
if [ ! -f "$INFRA_DIR/samconfig.toml" ]; then
    echo "ERROR: $INFRA_DIR/samconfig.toml not found."
    echo "Copy samconfig.toml.example and fill in your values:"
    echo "  cp $INFRA_DIR/samconfig.toml.example $INFRA_DIR/samconfig.toml"
    exit 1
fi

echo "═══════════════════════════════════════════════════════════════"
echo "  Data Analyst Agent — Deploy"
echo "═══════════════════════════════════════════════════════════════"

# ── Step 1: SAM build + deploy ─────────────────────────────────────────────
echo ""
echo "Step 1/4: Building and deploying SAM stack..."
cd "$INFRA_DIR"
sam build
sam deploy --config-file samconfig.toml || {
    echo "  (Stack is already up to date or deploy had warnings)"
}

# Extract outputs
STACK_NAME=$(grep -oP 'stack_name\s*=\s*"\K[^"]+' samconfig.toml || echo "data-analyst-agent")
REGION=$(grep -oP 'region\s*=\s*"\K[^"]+' samconfig.toml || echo "${AWS_DEFAULT_REGION:-us-west-2}")
PROJECT_NAME=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$REGION" \
    --query "Stacks[0].Parameters[?ParameterKey=='ProjectName'].ParameterValue" \
    --output text 2>/dev/null || echo "data-analyst-agent")

echo "  Stack: $STACK_NAME"
echo "  Project: $PROJECT_NAME"

# ── Step 2: Create S3 Files filesystem ─────────────────────────────────────
echo ""
echo "Step 2/4: Creating S3 Files filesystem..."

DATA_BUCKET_ARN=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$REGION" \
    --query "Stacks[0].Parameters[?ParameterKey=='DataBucketArn'].ParameterValue" \
    --output text)
S3FILES_ROLE_ARN=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$REGION" \
    --query "Stacks[0].Outputs[?OutputKey=='S3FilesServiceRoleArn'].OutputValue" \
    --output text 2>/dev/null || echo "")

# Check if filesystem already exists
EXISTING_FS=$(aws s3files list-file-systems --region "$REGION" --query "fileSystems[?bucket=='${DATA_BUCKET_ARN}'].fileSystemId | [0]" --output text 2>/dev/null || echo "")

if [ -n "$EXISTING_FS" ] && [ "$EXISTING_FS" != "None" ]; then
    FS_ID="$EXISTING_FS"
    echo "  Using existing filesystem: $FS_ID"
else
    FS_ID=$(aws s3files create-file-system \
        --bucket "$DATA_BUCKET_ARN" \
        --role-arn "$S3FILES_ROLE_ARN" \
        --region "$REGION" \
        --query "fileSystemId" \
        --output text)
    echo "  Created filesystem: $FS_ID"

    # Wait for filesystem to become available
    echo "  Waiting for filesystem to become available..."
    for i in $(seq 1 30); do
        STATUS=$(aws s3files get-file-system --file-system-id "$FS_ID" --query "status" --output text 2>/dev/null || echo "creating")
        if [ "$STATUS" = "available" ]; then
            break
        fi
        sleep 10
    done
    echo "  Status: $STATUS"
fi

# ── Step 3: Create mount targets ──────────────────────────────────────────
echo ""
echo "Step 3/4: Creating mount targets..."

SUBNET_IDS=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$REGION" \
    --query "Stacks[0].Parameters[?ParameterKey=='SubnetIds'].ParameterValue" \
    --output text)
SG_ID=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$REGION" \
    --query "Stacks[0].Outputs[?OutputKey=='MountTargetSecurityGroup'].OutputValue" \
    --output text 2>/dev/null || echo "")

MOUNT_DNS=""
for SUBNET in $(echo "$SUBNET_IDS" | tr ',' ' '); do
    echo "  Creating mount target in subnet $SUBNET..."
    MT_ID=$(aws s3files create-mount-target \
        --file-system-id "$FS_ID" \
        --subnet-id "$SUBNET" \
        --security-groups "$SG_ID" \
        --query "mountTargetId" \
        --output text 2>/dev/null || echo "exists")

    if [ "$MT_ID" != "exists" ]; then
        echo "    Mount target: $MT_ID"
    fi
done

# Get mount target IP
MOUNT_IP=$(aws s3files list-mount-targets \
    --file-system-id "$FS_ID" \
    --query "mountTargets[0].ipv4Address" \
    --output text 2>/dev/null || echo "")

# Construct DNS name (standard S3 Files pattern: {fs-id}.s3files.{region}.amazonaws.com)
FS_DNS="${FS_ID}.s3files.${REGION}.amazonaws.com"
echo "  Filesystem DNS: $FS_DNS"
if [ -n "$MOUNT_IP" ] && [ "$MOUNT_IP" != "None" ]; then
    echo "  Mount target IP: $MOUNT_IP"
fi

# ── Step 4: Update SSM parameters ─────────────────────────────────────────
echo ""
echo "Step 4/5: Updating SSM parameters..."

aws ssm put-parameter \
    --name "/${PROJECT_NAME}/s3-files-filesystem-id" \
    --value "$FS_ID" \
    --type String \
    --overwrite

aws ssm put-parameter \
    --name "/${PROJECT_NAME}/s3-files-mount-target-dns" \
    --value "$FS_DNS" \
    --type String \
    --overwrite

echo "  Updated /${PROJECT_NAME}/s3-files-filesystem-id = $FS_ID"
echo "  Updated /${PROJECT_NAME}/s3-files-mount-target-dns = $FS_DNS"

# ── Step 5: Create VPC egress connector ────────────────────────────────────
echo ""
echo "Step 5/5: Creating VPC egress network connector..."
echo "  (Required for MicroVM to reach S3 Files mount targets via NFS)"

# Check if connector already exists
EXISTING_CONNECTOR=$(aws ssm get-parameter --name "/${PROJECT_NAME}/vpc-egress-connector-arn" \
    --query "Parameter.Value" --output text 2>/dev/null || echo "PLACEHOLDER-SET-BY-DEPLOY-SCRIPT")

if [ "$EXISTING_CONNECTOR" != "PLACEHOLDER-SET-BY-DEPLOY-SCRIPT" ] && [ -n "$EXISTING_CONNECTOR" ]; then
    echo "  Using existing connector: $EXISTING_CONNECTOR"
else
    # Create network connector operator role (if not exists)
    # The connector needs subnets and SGs to provision ENIs
    CONNECTOR_ARN=$(aws lambda-core create-network-connector \
        --name "${PROJECT_NAME}-vpc-egress" \
        --configuration "{\"VpcEgressConfiguration\":{\"SubnetIds\":$(echo $SUBNET_IDS | tr ',' '\n' | jq -R . | jq -s .),\"SecurityGroupIds\":[\"$SG_ID\"],\"NetworkProtocol\":\"IPv4\",\"AssociatedComputeResourceTypes\":[\"MicroVm\"]}}" \
        --query "Arn" --output text 2>/dev/null || echo "")

    if [ -n "$CONNECTOR_ARN" ]; then
        echo "  Created connector: $CONNECTOR_ARN"
        echo "  Waiting for connector to become ACTIVE (may take up to 10 min)..."
        for i in $(seq 1 60); do
            CONN_STATE=$(aws lambda-core get-network-connector \
                --name "${PROJECT_NAME}-vpc-egress" \
                --query "State" --output text 2>/dev/null || echo "PENDING")
            if [ "$CONN_STATE" = "ACTIVE" ]; then
                break
            fi
            sleep 10
        done
        echo "  Connector state: $CONN_STATE"

        aws ssm put-parameter \
            --name "/${PROJECT_NAME}/vpc-egress-connector-arn" \
            --value "$CONNECTOR_ARN" \
            --type String \
            --overwrite
    else
        echo "  WARNING: Could not create VPC egress connector."
        echo "  You may need to create it manually. See README for details."
    fi
fi

# ── Done ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  ✓ Infrastructure deployed!"
echo ""
echo "  Webhook URL:"
WEBHOOK_URL=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" --region "$REGION" \
    --query "Stacks[0].Outputs[?OutputKey=='WebhookUrl'].OutputValue" \
    --output text)
echo "    $WEBHOOK_URL"
echo ""
echo "  IMPORTANT: Build the MicroVM image with:"
echo "    --additional-os-capabilities '[\"ALL\"]'  (enables NFS mounts)"
echo "    --base-image-arn arn:aws:lambda:${REGION}:aws:microvm-image:al2023-1"
echo ""
echo "  Next steps:"
echo "    1. Build MicroVM image:   ./scripts/build-image.sh"
echo "    2. Upload sample data:    ./scripts/upload-sample-data.sh"
echo "    3. Configure agent:       python agent/setup-agent.py"
echo "    4. Run demo:              ./scripts/demo.sh"
echo "═══════════════════════════════════════════════════════════════"
