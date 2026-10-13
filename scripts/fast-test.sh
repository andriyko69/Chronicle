#!/usr/bin/env bash
# scripts/fast-test.sh <filter>
# e.g.: ./scripts/fast-test.sh "*Container*"
set -euo pipefail

FILTER="${1:-*}"
GRADLE_USER_HOME=/tmp/chronicle-gradle ./gradlew test \
  --tests "$FILTER" \
  --console=plain \
  -q \
  --no-daemon \
  --warning-mode=none
