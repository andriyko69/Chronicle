#!/usr/bin/env bash
set -euo pipefail

# Stage untracked files as intent-to-add so the diff against HEAD includes new files
git add -N .

COMPANION="$(find "$HOME/.claude/plugins" -name "codex-companion.mjs" | sort -V | tail -n 1)"
if [[ -z "$COMPANION" ]]; then
  echo "Error: codex-companion.mjs not found under ~/.claude/plugins" >&2
  exit 1
fi

exec node "$COMPANION" adversarial-review --wait --base HEAD "$@"
