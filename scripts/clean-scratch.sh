#!/usr/bin/env bash
# Cleans ephemeral scratch logs before starting a new milestone
rm -rf .agent/scratch/* run/smoke/*/console.log
echo "Ephemeral scratch logs pruned."
