#!/bin/bash
# build-image.sh — Build the MicroVM image for the Data Analyst Agent.
#
# Packages the Dockerfile + worker into a zip, uploads to S3, and creates
# the MicroVM image with --additional-os-capabilities '["ALL"]' (required
# for NFS filesystem mounts inside the container).
#
# Prerequisites:
#   - deploy.sh has been run (stack exists, S3 bucket available)
#   - zip utility available
#   - AWS CLI with lambda-microvms service model installed
#
# Usage:
#   ./scripts/build-image.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$SCRIPT_DIR")"
IMAGE_DIR="$EXAMPLE_DIR/microvm-image"
INFRA_DIR="$EXAMPLE_DIR/infrastructure"

# Load config
STACK_NAME=$(grep -oP 'stack_name\s*=\s*"\K[^"]+' "$INFRA_DIR/samconfig.toml" 2>/dev/null || echo "data-analyst-agent")
REGION=$(aws configure get region || echo "us-east-2")
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
PROJECT_NAME=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Parameters[?ParameterKey=='ProjectName'].ParameterValue" \
    --output text 2>/dev/null || echo "data-analyst-agent")
IMAGE_NAME=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Parameters[?ParameterKey=='ImageNamePrefix'].ParameterValue" \
    --output text 2>/dev/null || echo "data-analyst-worker")

# S3 bucket for artifacts (from SAM deploy)
ARTIFACT_BUCKET="${PROJECT_NAME}-artifacts-${ACCOUNT_ID}-${REGION}"

echo "═══════════════════════════════════════════════════════════════"
echo "  Data Analyst Agent — Build MicroVM Image"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "  Image name: $IMAGE_NAME"
echo "  Region: $REGION"
echo "  Artifact bucket: $ARTIFACT_BUCKET"
echo ""

# ── Step 1: Package the image source ──────────────────────────────────────
echo "Step 1/3: Packaging image source..."
WORK_DIR=$(mktemp -d)
ARTIFACT_KEY="microvm-images/${IMAGE_NAME}/code-artifact.zip"

# Copy image source to temp dir, ensuring Dockerfile is at root
cp "$IMAGE_DIR/Dockerfile" "$WORK_DIR/"
cp -r "$IMAGE_DIR/worker" "$WORK_DIR/"

# Create zip with Dockerfile at root (required by Lambda MicroVMs)
cd "$WORK_DIR"
zip -r code-artifact.zip Dockerfile worker/
echo "  Packaged: $(du -h code-artifact.zip | cut -f1)"

# ── Step 2: Upload to S3 ──────────────────────────────────────────────────
echo ""
echo "Step 2/3: Uploading to S3..."

# Create bucket if it doesn't exist
aws s3 mb "s3://$ARTIFACT_BUCKET" --region "$REGION" 2>/dev/null || true
aws s3 cp code-artifact.zip "s3://$ARTIFACT_BUCKET/$ARTIFACT_KEY"
echo "  Uploaded: s3://$ARTIFACT_BUCKET/$ARTIFACT_KEY"

# ── Step 3: Create MicroVM image ──────────────────────────────────────────
echo ""
echo "Step 3/3: Creating MicroVM image..."
echo "  IMPORTANT: Using --additional-os-capabilities '[\"ALL\"]' for NFS mount support"

# Get build role ARN from stack
BUILD_ROLE_ARN=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
    --query "Stacks[0].Outputs[?OutputKey=='BuildRoleArn'].OutputValue" \
    --output text 2>/dev/null || echo "")

if [ -z "$BUILD_ROLE_ARN" ]; then
    BUILD_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${PROJECT_NAME}-microvm-build-role"
    echo "  WARNING: BuildRoleArn not in stack outputs, using default: $BUILD_ROLE_ARN"
fi

# Check if image already exists
EXISTING_IMAGE=$(aws lambda-microvms get-microvm-image \
    --image-identifier "arn:aws:lambda:${REGION}:${ACCOUNT_ID}:microvm-image:${IMAGE_NAME}" \
    --query "state" --output text 2>/dev/null || echo "")

if [ "$EXISTING_IMAGE" = "CREATED" ]; then
    echo "  Image already exists. Creating new version..."
    aws lambda-microvms update-microvm-image \
        --image-identifier "arn:aws:lambda:${REGION}:${ACCOUNT_ID}:microvm-image:${IMAGE_NAME}" \
        --base-image-arn "arn:aws:lambda:${REGION}:aws:microvm-image:al2023-1" \
        --build-role-arn "$BUILD_ROLE_ARN" \
        --code-artifact "{\"uri\":\"s3://${ARTIFACT_BUCKET}/${ARTIFACT_KEY}\"}"
else
    echo "  Creating new image..."
    aws lambda-microvms create-microvm-image \
        --name "$IMAGE_NAME" \
        --description "Data Analyst Agent - Claude MicroVM with S3 Files (NFS) access" \
        --base-image-arn "arn:aws:lambda:${REGION}:aws:microvm-image:al2023-1" \
        --build-role-arn "$BUILD_ROLE_ARN" \
        --code-artifact "{\"uri\":\"s3://${ARTIFACT_BUCKET}/${ARTIFACT_KEY}\"}" \
        --additional-os-capabilities '["ALL"]' \
        --hooks '{
          "port": 9000,
          "microvmImageHooks": {
            "ready": "ENABLED",
            "readyTimeoutInSeconds": 120
          },
          "microvmHooks": {
            "run": "ENABLED",
            "runTimeoutInSeconds": 5,
            "resume": "ENABLED",
            "resumeTimeoutInSeconds": 5,
            "suspend": "ENABLED",
            "suspendTimeoutInSeconds": 5,
            "terminate": "ENABLED",
            "terminateTimeoutInSeconds": 5
          }
        }'
fi

echo ""
echo "  Image ARN: arn:aws:lambda:${REGION}:${ACCOUNT_ID}:microvm-image:${IMAGE_NAME}"
echo ""
echo "  Build in progress. Monitor with:"
echo "    aws lambda-microvms list-microvm-image-builds \\"
echo "      --image-identifier arn:aws:lambda:${REGION}:${ACCOUNT_ID}:microvm-image:${IMAGE_NAME} \\"
echo "      --image-version 1"
echo ""
echo "  Build logs:"
echo "    /aws/lambda-microvms/${IMAGE_NAME} (CloudWatch Logs)"
echo ""

# Cleanup
rm -rf "$WORK_DIR"

echo "═══════════════════════════════════════════════════════════════"
echo "  ✓ Image build initiated."
echo "  Wait for state=SUCCESSFUL before running demo.sh"
echo "═══════════════════════════════════════════════════════════════"
