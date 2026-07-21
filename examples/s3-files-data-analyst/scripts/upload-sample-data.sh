#!/bin/bash
# upload-sample-data.sh — Upload sample data to the S3 bucket.
#
# Uploads the CSV files from sample-data/ to the data/ prefix in your bucket.
# After upload, these files will be visible at /mnt/s3files/data/ inside the MicroVM.
#
# Usage:
#   ./scripts/upload-sample-data.sh [bucket-name]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$SCRIPT_DIR")"
SAMPLE_DATA_DIR="$EXAMPLE_DIR/sample-data"

# Get bucket name from argument or stack output
BUCKET_NAME="${1:-}"

if [ -z "$BUCKET_NAME" ]; then
    STACK_NAME=$(grep -oP 'stack_name\s*=\s*"\K[^"]+' "$EXAMPLE_DIR/infrastructure/samconfig.toml" 2>/dev/null || echo "data-analyst-agent")
    DATA_BUCKET_ARN=$(aws cloudformation describe-stacks --stack-name "$STACK_NAME" \
        --query "Stacks[0].Parameters[?ParameterKey=='DataBucketArn'].ParameterValue" \
        --output text 2>/dev/null || echo "")
    BUCKET_NAME=$(echo "$DATA_BUCKET_ARN" | sed 's|arn:aws:s3:::||')
fi

if [ -z "$BUCKET_NAME" ]; then
    echo "ERROR: Could not determine bucket name."
    echo "Usage: $0 <bucket-name>"
    exit 1
fi

echo "Uploading sample data to s3://$BUCKET_NAME/data/..."

# Upload CSVs
for file in "$SAMPLE_DATA_DIR"/*.csv; do
    filename=$(basename "$file")
    echo "  → s3://$BUCKET_NAME/data/$filename"
    aws s3 cp "$file" "s3://$BUCKET_NAME/data/$filename"
done

echo ""
echo "✓ Sample data uploaded."
echo ""
echo "Files will be accessible in the MicroVM at:"
echo "  /mnt/s3files/data/sales-q1-2026.csv"
echo "  /mnt/s3files/data/sales-q2-2026.csv"
