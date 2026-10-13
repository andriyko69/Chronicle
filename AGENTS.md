# Chronicle

Chronicle is a server-side forensic audit and rollback mod for NeoForge
1.21.1. Its mod ID is `chronicle`; its Java base package and Maven group
are `io.github.andriyko69.chronicle`.

1. Use Java 25 throughout development, compilation, build tools, and server
   runtime. Do not lower the target to Java 21 because upstream examples use
   it. Avoid preview features and unnecessary version-specific APIs.

2. Keep clients optional. Introduce client code, custom networking, or
   gameplay content only when a requested feature requires it.

3. Resolve version-sensitive APIs from repository source/tests, resolved
   NeoForge 1.21.1 sources, version-matched official documentation, mapped
   Minecraft sources, then upstream examples. Check later-version examples
   against 1.21.1 before using them.

4. Treat `references/` as optional, ignored, read-only research material.
   Never commit it or include it in builds or runtime dependencies.
   CoreProtect establishes its own behavior, not NeoForge implementation
   rules. Record provenance and assess licensing before reusing code.

5. Read or mutate live Minecraft state on its owning server thread.
   Background persistence workers receive detached snapshots, not live
   world objects. Routine database I/O must not block the tick thread.

6. Prefer explicit unsupported or failed results over silent data loss
   or false rollback success. Do not assume persisted modded state is safe
   to deserialize across Minecraft or mod version changes.

7. Test behavior whose failure could corrupt, lose, or duplicate world or
   player state. Use verification-before-completion when available and
   report actual commands and results. Build success does not establish
   server startup, client compatibility, or GameTest success.

8. Follow `.agent/PLANS.md` for complex or risky changes. Keep local fixes
   lightweight. Add project skills only for demonstrated recurring
   workflows; reuse existing global skills.

## Code clarity and comments

Prefer self-explanatory code. Do not add comments that narrate obvious code;
improve names, types, method boundaries, and structure instead.

Comments should primarily explain why: non-obvious invariants, concurrency,
durability, compatibility, surprising behavior, or external constraints.
Do not add boilerplate Javadocs to every class or method; document meaningful
API contracts. Remove stale or redundant comments when editing nearby code.

## Agent working files

Do not put plans, research, scratch files, handoffs, or reports in the repository
root. Use the ignored `.agent/plans/`, `.agent/research/`, `.agent/handoffs/`,
`.agent/scratch/`, and `.agent/reports/` directories.

Keep `AGENTS.md`, `.agent/PLANS.md`, and intentional reusable `.agents/skills/**`
as persistent, tracked infrastructure. Do not turn one-off task notes into
permanent project documentation.
