#!/bin/bash
# mount-s3files — Mount an S3 Files filesystem via NFS.
#
# Usage: mount-s3files <mount-target-dns> <mount-path>
#
# This script is called by the worker on the /run hook before the agent
# session starts. It mounts the S3 Files filesystem (which is NFS-backed)
# at the specified path so the agent can read/write files transparently.
#
# Exit codes:
#   0 — mounted successfully (or already mounted)
#   1 — missing arguments
#   2 — mount failed

set -euo pipefail

MOUNT_DNS="${1:-}"
MOUNT_PATH="${2:-/mnt/s3files}"
MOUNT_TIMEOUT="${3:-30}"

if [ -z "$MOUNT_DNS" ]; then
    echo "ERROR: mount-s3files requires <mount-target-dns> as first argument" >&2
    exit 1
fi

# Already mounted? Skip.
if mountpoint -q "$MOUNT_PATH" 2>/dev/null; then
    echo "s3files: $MOUNT_PATH already mounted"
    exit 0
fi

# Create mount point if needed
mkdir -p "$MOUNT_PATH"

echo "s3files: mounting $MOUNT_DNS at $MOUNT_PATH (timeout ${MOUNT_TIMEOUT}s)..."

# NFS4 mount with performance-tuned options:
#   - nfsvers=4.1: required by S3 Files
#   - rsize/wsize=1048576: 1MB read/write buffers (optimal for large files)
#   - timeo=<timeout*10>: NFS timeout in deciseconds
#   - retrans=2: retry twice before failing
#   - noresvport: don't require privileged port (container-friendly)
if mount -t nfs4 \
    -o "nfsvers=4.1,rsize=1048576,wsize=1048576,timeo=$((MOUNT_TIMEOUT * 10)),retrans=2,noresvport" \
    "${MOUNT_DNS}:/" "$MOUNT_PATH"; then
    echo "s3files: mounted successfully at $MOUNT_PATH"

    # Create outputs directory if it doesn't exist
    mkdir -p "${MOUNT_PATH}/outputs/reports" "${MOUNT_PATH}/outputs/charts" 2>/dev/null || true

    exit 0
else
    echo "ERROR: s3files mount failed (dns=$MOUNT_DNS, path=$MOUNT_PATH)" >&2
    exit 2
fi
