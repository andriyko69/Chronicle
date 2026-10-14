@AGENTS.md

## Claude Code Specific Workflow Overrides

1. **Unattended Cross-Model Code Review & Auto-Fix (`code-review` step):**
   - When reaching the `code-review` step of the workflow, do NOT spawn Claude sub-agents and do NOT stop to ask the user to run the review.
   - Execute `./scripts/codex-review.sh` directly via the `Bash` tool with `timeout: 600000` (10 minutes) and wait for it to finish. Do not detach and end your turn while the review is running.
   - When `./scripts/codex-review.sh` returns:
     - If Verdict is `needs-attention` or any `[critical]` / `[high]` / `[medium]` (P1/P2) findings are reported, **immediately fix all of them and add the recommended regression tests automatically without asking the user for confirmation.**
     - Only after all review findings are fixed, proceed to the final `verification-before-completion` suite (`clean build stressTest runGameTestServer` and both `smoke-server.py` modes) before writing the completion report.
2. **Skills Location:**
   - Other repository skills reside in `.agents/skills/`.
