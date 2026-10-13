# Plans

Write a plan before implementing a change with substantial architectural,
data-integrity, concurrency, or compatibility risk.

Typical triggers:

- persistence or schema design;
- rollback, restore, or undo behavior;
- causality and actor attribution;
- large migrations;
- concurrency-sensitive changes;
- invasive Mixins or instrumentation;
- broad mod integration boundaries.

Small localized fixes, documentation edits, and routine configuration
changes do not need a formal plan.

Store substantive plans at `.agent/plans/<short-name>.md` and commit them
with the work they explain. Link the relevant issue when one exists.
Temporary investigation notes and raw logs may remain outside Git.

Use this outline, omitting sections that do not apply:

```markdown
# <Title>
Status: proposed | active | complete | superseded

## Goal and scope
Describe the intended behavior, non-goals, and acceptance criteria.

## Evidence and design
Identify relevant source versions, constraints, the chosen approach,
and alternatives that materially affect the decision.

## Correctness and failure handling
Describe invariants, thread ownership, unsupported states, compatibility,
and recovery or migration concerns.

## Execution and verification
List small implementation steps and the checks that establish success.
Distinguish unit tests, GameTests, development-server runs, and packaged
server validation where relevant.

## Open decisions and outcome
Record unresolved decisions and, when complete, the actual outcome and
verification results.
```

Keep plans current when evidence changes the design. Preserve durable
decisions and unresolved limitations; remove redundant progress narration.
Link superseding plans instead of leaving contradictory guidance active.
