@AGENTS.md

## Claude Code Specific Workflow Overrides

1. **Cross-Model Code Review (`code-review` step):**
   - When reaching the `code-review` step of the workflow, do NOT spawn Claude sub-agents for `.agents/skills/code-review`.
   - Before invoking the review, ensure untracked files are staged as intent-to-add with `git add -N .` so the diff covers all newly created resources.
   - Then invoke the Codex plugin skill `codex:adversarial-review` with `--base HEAD` (scoped to changes since the baseline commit) so Codex performs the review using the ChatGPT Plus quota.
   - Address any actionable P1/P2 findings returned by Codex before running the final `verification-before-completion` suite.
2. **Skills Location:**
   - Other repository skills reside in `.agents/skills/`.
