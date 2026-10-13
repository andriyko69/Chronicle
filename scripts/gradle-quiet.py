#!/usr/bin/env python3
# scripts/gradle-quiet.py <gradle-command...>
import subprocess
import sys

cmd = sys.argv[1:]
proc = subprocess.run(cmd, check=False, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

if proc.returncode == 0:
    # Print only the summary lines (BUILD SUCCESSFUL, test counts)
    lines = [l for l in proc.stdout.splitlines() if "BUILD SUCCESSFUL" in l or "passed" in l or "tests" in l]
    print("\n".join(lines) if lines else "Build and tests passed cleanly (exit 0).")
else:
    # On error, print only the relevant failure block / last 40 lines
    lines = proc.stdout.splitlines()
    print("\n".join(lines[-40:]))
    sys.exit(proc.returncode)
