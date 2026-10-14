# Chronicle

Chronicle is a server-side NeoForge 1.21.1 mod being developed into a forensic
logging, inspection, querying, and rollback system for modded Minecraft.
Correctness and forensic reliability take priority over feature velocity.

**Early development:** player block break, placement, world-container net item
transactions, container break spills and menu-less storage interactions are
captured on the logical server thread, journaled, and persisted to SQLite. Operator lookup, pagination and status commands are available.
Inspection and rollback are not implemented yet.

Chronicle is intended to run on a dedicated server without requiring players
to install it on their clients. It currently registers no gameplay content
or custom networking. Client compatibility must be verified as features arrive.

## Development

Use a 64-bit **JDK 25** for the IDE, Gradle, compilation, build tooling, and
server runtime. Chronicle deliberately uses Java 25 despite Minecraft 1.21.1's
upstream Java 21 baseline. Point `JAVA_HOME` and the IDE's Gradle JVM to JDK 25.

The project pins Gradle 9.2.1, ModDevGradle 2.0.148, NeoForge 21.1.259, and
Parchment 2024.11.17 for Minecraft 1.21.1. Use the checked-in wrapper:

```bash
java -version
./gradlew --version
./gradlew clean build
```

On Windows use `gradlew.bat`. First-time builds download Minecraft and prepare
its development sources. The mod JAR is written to
`build/libs/chronicle-0.1.0-SNAPSHOT.jar`.

Mod metadata is generated from `src/main/templates/META-INF/neoforge.mods.toml`.
Edit that template and `gradle.properties`; do not edit generated build output.
The Java package and Maven group are `io.github.andriyko69.chronicle`.

## Dedicated development server

```bash
./gradlew runServer
```

Runtime files live in the ignored `run/server/` directory. On first launch,
read [Minecraft's EULA](https://aka.ms/MinecraftEULA). If you accept it, set
`eula=true` in `run/server/eula.txt`, then rerun the command. Type `stop` in
the console for a clean shutdown. Use only disposable development worlds.

The run directory can be changed with
`-Pchronicle.runDirectory=/absolute/path/to/disposable-directory`.

On POSIX systems, automate startup and clean shutdown in a fresh local world:

```bash
python3 scripts/smoke-server.py --accept-eula
```

Pass `--accept-eula` only if you accept Minecraft's EULA. The helper binds to
loopback on an ephemeral port, retains files under `run/smoke/`, and fails on
startup or shutdown timeouts. It checks Minecraft readiness, Chronicle's
infrastructure readiness, status/lookup/page command responses, clean closure,
and world saving. It does not run
gameplay tests or connect a client.

Server readiness and clean shutdown are separate checks from compilation.
Joining with a client without Chronicle and loading the packaged JAR on an
installed NeoForge server are additional integration checks.

## Persistence runtime

The built mod bundles OpenHFT Chronicle Queue 2026.7, SQLite JDBC 3.53.4.0
and their required libraries. For an installed NeoForge 21.1.259 server, place
the mod JAR in `mods/` and add the options from [chronicle-jvm.args](chronicle-jvm.args)
to `user_jvm_args.txt`. Development runs and tests apply these options automatically.
Use local storage with working directory synchronization; the current runtime
has been exercised on Linux/WSL2. Windows and network filesystems are unverified.

Each world keeps its database and journal in `<world>/chronicle/`. Optional
`chronicle.properties` in that directory accepts these defaults:

```properties
storage.backend=sqlite
sqlite.path=audit.sqlite
sqlite.busyMillis=100
journal.path=journal
journal.syncMillis=50
journal.retainedCycles=2
ingress.capacity=8192
batch.size=100
batch.maxAgeMillis=25
retry.minMillis=50
retry.maxMillis=5000
shutdown.graceMillis=10000
```

Submission is nonblocking and explicitly rejects a full queue. `ACCEPTED`
means admission to memory; its `journaled` future completes after synchronous
journal and frontier flushing. A crash can lose admitted events whose futures
have not completed. The default 50 ms flush interval is a scheduling target,
not a proven 100 ms power-loss bound. SQLite uses WAL and FULL synchronization.
Transient failures retry while backlog stays in the journal; permanent payload
failures enter the transactional DLQ. Corruption or conflicting event identity
halts progress explicitly. Keep the database and its matching journal together.

Player and FakePlayer hooks capture dimension, coordinates, attribution, canonical
block states and optional pre-change block entity NBT. Multi-block placements
produce one delta per position. Records explicitly carry `HOOK_OBSERVATION`:
NeoForge's uncanceled LOWEST-priority callbacks precede final engine success and
can precede later cancellation. Break records use air as the intended result,
including for waterlogged blocks; they do not establish the final fluid state.

Raw NBT serialization is bounded to 64 KiB. Oversized or failed snapshots retain
block states with an explicit status and no NBT; truncated NBT is never stored.
Mod block entity save methods can allocate their own tag data before serialization.
Persisted NBT remains opaque and is not assumed safe to restore across versions.
V002 preserves synthetic V001 rows in `legacy_audit_events` and supports replaying
pending version-1 journal records. New envelopes use the STRICT `audit_events` table.
V003 adds time-ordered lookup indexes without changing V001/V002 migrations.

## Container transactions

World storage menus are snapshotted at open and compared at close, logout, death,
dimension change, replacement menu or server stop. Identical items are grouped
across slots; rearranging stacks or taking and returning items emits no event.
Only persistent BlockEntity-backed slots count, including both double chest
halves and crafter inputs. Double chest coordinates use the lexicographically
smallest `(x, y, z)` half, independent of which half was opened. Virtual menus,
player/ender inventories and transient result previews are excluded.

Component patches use registry-aware Minecraft 1.21.1 codecs on the server thread.
Plain items store no component snapshot. Modified items retain at most 16 KiB of
canonical NBT plus a bounded readable summary; oversized or failed snapshots are
explicitly marked. Custom name summaries are limited to 1024 UTF-8 bytes with an
ellipsis on truncation. Complete serializable patches are hashed even when their
bytes exceed the retention limit, preserving net comparisons. Unknown component
identities (failed serialization or transient components) fail the container
comparison explicitly and log the failure without fabricating transactions.
NBT string overflow uses a stable extended fingerprint for comparison while
marking the retained snapshot oversized. Query and persistence workers never decode NBT.
These opaque snapshots are not a promise of cross-version restoration.

The net difference observes changes over the entire open interval. Automation,
furnace processing and simultaneous viewers can contribute to that difference;
these records identify the session player and do not prove exclusive causality.
No world or player state is mutated by auditing.

## Spills and menu-less storage

When a player break or an explosion removes a storage block (any BlockEntity
container, lectern or campfire), its contents are recorded as `ITEM_TAKE` rows at
the block's own position. Shulker box contents are included: they leave the
position inside the dropped item. Contents are captured at the break or detonate
hook and recorded only after the block entity is confirmed removed at the next
tick boundary, so refused breaks and non-destructive explosions record nothing.
Explosions are attributed to `ENVIRONMENT` with actor `#<source entity>` (or
`#explosion`) and the igniting player, when known, as the root actor. Players
viewing that container are credited with their changes up to the break and are
not blamed for the spill.

Direct clicks on chiseled bookshelves, decorated pots, jukeboxes, lecterns
(including taking the book through the lectern screen), campfires, item frames
and glow item frames are net-diffed between the interaction hook and the next tick
boundary. Rotating a framed item is not a transaction. Attacks that pop an item
and explosions that destroy a frame record `ITEM_TAKE`. Unrolled loot containers
are skipped rather than generating loot. All observers of one storage share a
baseline, so each change is recorded once: to the remover, else the latest click,
else the earliest player whose lectern screen is still open. Changes from automation
in the same tick, or a lectern screen action in the same tick as another player's
click, can therefore be attributed to the wrong one of those players.
Frames dropped after losing support, pots broken by projectiles, campfire cooking
output and removals by other mods or entities are not observed.

## Lookup and status commands

Operators with permission level 2 or higher can use `/chronicle` or `/ch`:

```text
/ch lookup t:1h30m r:10 u:Alice b:chest,diamond_ore a:-block
/ch l r:global t:10m u:#fakeplayer
/ch l t:1h a:take b:diamond
/ch l t:1h a:container
/ch page 2
/ch p 1
/ch status
```

Lookup accepts `t:`/`time:` durations in seconds (`s`), minutes (`m`), hours (`h`),
days (`d`) or weeks (`w`), including compound durations. Without `t:`, the window
is 24 hours. Player lookups default to radius 5 in the command source's dimension
and position; radius is an inclusive bounding box, with a maximum of 100 blocks.
`at:x,y,z` changes its center, and `dim:`/`world:` selects a dimension.
Bare block, item and dimension names default to `minecraft:`.

Console spatial lookups require both `at:` and `dim:`. `r:global` removes the
spatial bounds and requires an explicit time, actor or block filter; it retains
the player's default dimension unless an explicit dimension replaces it. Console
global lookups span dimensions unless `dim:` is given. `at:` and `r:global` cannot
be combined.

`u:`/`user:` accepts comma-separated exact actor names, full UUIDs or `#fakeplayer`.
`b:`/`block:` accepts comma-separated block or item IDs (the event target).
`a:`/`action:` accepts `block` (both block actions), `-block`/`break`,
`+block`/`place`, `container`/`item` (both item actions), `-container`/`-item`/`take`,
or `+container`/`+item`/`put`. With no action filter, both block and item events
are returned. Unknown, duplicate and malformed filters fail explicitly.

Each page contains at most eight events in descending timestamp/nanosecond/event-ID
order, with exact actor, world and coordinates. Block rows show old/new block
states and NBT status. Item rows show ITEM_TAKE/ITEM_PUT, item ID, net count,
container block ID, custom name/enchantments/damage and component snapshot status.
Block states describe hook observations, not confirmed final mutations. Synthetic
V001 records are excluded from lookup. Pagination saves the last successful query
for ten minutes, for up to 128 callers, and supports pages 1–10,000. Each page reads
current persisted data with the original time cutoff; new or late commits can
shift page boundaries. Console and other non-player sources share one session.

Queries run on a bounded worker queue with read-only SQLite access, a two-second
SQL execution deadline and at most two seconds of lock waiting. Overload rejects
immediately. Narrow the filters if a query times out. Stored payload corruption
halts admission and reports fatal storage health. Status shows database-wide
event/DLQ totals (including legacy events), separate runtime counters, ingress,
journal backlog/allocated bytes, health, schema and flush/commit times. If reads
are unavailable, runtime diagnostics remain available while database totals are
reported unavailable.

For an existing disposable, loopback-bound NeoForge installation with the mod
installed and the EULA already accepted:

```bash
python3 scripts/smoke-server.py --installed-directory /path/to/test-server
```

## Tests and agent workflow

Pure unit/integration tests use temporary SQLite databases and journals:

```bash
./gradlew test
./gradlew stressTest
./gradlew journalSpike
./gradlew runGameTestServer
```

The stress task submits 100,000 synthetic events; it is not a production
benchmark. Live GameTests run from the isolated `src/gameTest` source set and
exercise player attribution, engine break/place hooks, cancellation, multi-place
coalescing, NBT capture, production command output, pagination and operator
permissions through journal and SQLite. Accept the EULA in
`run/gametest/eula.txt` before running them. Test mod classes/resources are excluded
from ordinary server runs and the shipped JAR; ordinary `src/test` classes are
not automatically game mods.

[AGENTS.md](AGENTS.md) records development invariants.
[.agent/PLANS.md](.agent/PLANS.md) describes when a substantive plan is useful;
plans and other working notes stay in the ignored `.agent/` directories.
Project-local skills are reserved
for demonstrated recurring workflows. Existing global research, debugging,
review, and verification skills should be reused.

## Behavioral reference

An optional local copy of CoreProtect 24.1 may exist under
`references/CoreProtect-24.1/`. It is ignored, read-only research material,
excluded from builds and runtime dependencies. Record the source revision or
file hashes when using it to investigate behavior. Chronicle uses Minecraft
and NeoForge concepts directly; CoreProtect is not a NeoForge API reference.

## License

Chronicle is licensed under the **GNU General Public License, version 3 or
any later version** (`GPL-3.0-or-later`). See [LICENSE](LICENSE).
Chronicle is distributed without warranty; see the license for details.

Inherited MDK template files retain their MIT notice in
[TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt). Minecraft mappings retain their
own terms; consult the mapping artifacts and
[NeoForm's mapping notice](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md).
Bundled dependency notices are preserved in [licenses/third-party](licenses/third-party)
and included in the mod JAR.
