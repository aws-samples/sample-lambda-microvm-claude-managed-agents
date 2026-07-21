#!/bin/bash
# test_mount_config.sh — Unit tests for mount-s3files.sh
#
# Tests the mount script's argument validation and error handling
# WITHOUT requiring actual NFS or AWS access.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOUNT_SCRIPT="$SCRIPT_DIR/../../microvm-image/worker/mount-s3files.sh"

PASSED=0
FAILED=0

assert_exit_code() {
    local expected="$1"
    local actual="$2"
    local desc="$3"

    if [ "$actual" -eq "$expected" ]; then
        echo "  ✓ $desc"
        PASSED=$((PASSED + 1))
    else
        echo "  ✗ $desc (expected exit $expected, got $actual)"
        FAILED=$((FAILED + 1))
    fi
}

echo "mount-s3files.sh — unit tests"
echo "──────────────────────────────"

# Test 1: Missing DNS argument → exit 1
echo ""
echo "Test: missing DNS argument"
EXIT_CODE=0
bash "$MOUNT_SCRIPT" 2>/dev/null || EXIT_CODE=$?
assert_exit_code 1 "$EXIT_CODE" "exits with code 1 when no DNS provided"

# Test 2: Script is executable
echo ""
echo "Test: script is executable"
if [ -x "$MOUNT_SCRIPT" ]; then
    echo "  ✓ script has execute permission"
    PASSED=$((PASSED + 1))
else
    echo "  ✗ script missing execute permission"
    FAILED=$((FAILED + 1))
fi

# Test 3: Script uses bash strict mode
echo ""
echo "Test: bash strict mode"
if grep -q "set -euo pipefail" "$MOUNT_SCRIPT"; then
    echo "  ✓ uses 'set -euo pipefail'"
    PASSED=$((PASSED + 1))
else
    echo "  ✗ missing strict mode"
    FAILED=$((FAILED + 1))
fi

# Test 4: NFS version is 4.1
echo ""
echo "Test: NFS version 4.1"
if grep -q "nfsvers=4.1" "$MOUNT_SCRIPT"; then
    echo "  ✓ uses NFS v4.1 (required by S3 Files)"
    PASSED=$((PASSED + 1))
else
    echo "  ✗ wrong NFS version"
    FAILED=$((FAILED + 1))
fi

# Test 5: Creates output directories
echo ""
echo "Test: creates output directories"
if grep -q "outputs/reports" "$MOUNT_SCRIPT" && grep -q "outputs/charts" "$MOUNT_SCRIPT"; then
    echo "  ✓ creates outputs/reports and outputs/charts"
    PASSED=$((PASSED + 1))
else
    echo "  ✗ missing output directory creation"
    FAILED=$((FAILED + 1))
fi

# ── Summary ────────────────────────────────────────────────────────────────
echo ""
echo "──────────────────────────────"
echo "Results: $PASSED passed, $FAILED failed"

if [ "$FAILED" -gt 0 ]; then
    exit 1
fi
