# Chronicle

Chronicle is a server-side forensic audit and rollback mod for NeoForge 1.21.1
(mod ID `chronicle`). It records who changed what, where and when, lets
operators query and inspect that history, and can roll back or restore it.
Correctness and forensic reliability take priority over feature velocity.

Chronicle runs on a dedicated server and needs nothing on the client. It
registers no gameplay content and no custom networking.

## Core guarantees

- **No server-thread stalls.** Live game state is read only on the owning
  server thread. Persistence workers receive detached snapshots, and routine
  database I/O never blocks the tick.
- **Durable journaling.** Admitted events are written to an off-heap OpenHFT
  Chronicle Queue journal and replayed after a crash.
- **Single-writer SQLite.** One writer persists journal batches into SQLite
  (WAL, FULL synchronization, `schema=6`). Transient failures retry; permanent
  payload failures go to a transactional dead-letter queue; corruption halts
  progress explicitly.
- **Fast lookups.** Queries use read-only connections and time-ordered
  indexes, with a two-second execution deadline.
- **Explicit failure over silent loss.** Unsupported, oversized or failed
  captures are marked as such. Rollback never reports false success, and
  changes it cannot prove recorded are quarantined.

## What Chronicle audits

### Blocks and fluids
- Breaks and placements by players, `FakePlayer`s and the environment, with
  canonical block states and the pre-change BlockEntity NBT snapshot.
- Bucket pours and pickups (water, lava, powder snow) and waterlogging
  (`b:water`).

### Containers and storage
- Net-diff container sessions (`Container`): changes over the whole open
  interval, including both double-chest halves.
- Container-break spills, including shulker box contents.
- Menu-less storage: lecterns, chiseled bookshelves (exact slot indexing),
  jukeboxes, decorated pots and campfires.
- Item frames and glow item frames, and armor stand equipment and destruction
  spills.

### Environmental causality (`← RootPlayer`)
Environmental rows carry actor type `ENVIRONMENT` and the responsible player as
root actor when it can be traced:
- TNT, including chain-primed TNT, creepers (aggro and flint-and-steel
  ignition), end crystals, and bed and respawn anchor blasts.
- Fire spread from ignition through every burnout, and fire from poured lava.
- Dispenser bucket triggers and loaders.
- Wall-mounted drops (item frames) after their support is removed.

### Entity death forensics (`ENTITY_KILL`)
Deaths of entities worth auditing are recorded when a player caused them:
domesticated and farm animals (marked `chronicle:domesticated` through
breeding, feeding or interaction), villagers and traders, tamed pets and
mounts, golems, allays, bosses, and named, leashed or equipped mobs.
Attribution covers direct kills and indirect ones (`#creeper`, `#tnt`, `#lava`,
`#fire`, `#campfire`, `#arrow`, `#wolf`) with the root player.

## Commands and permissions

All commands require permission level 2 and are available as `/chronicle` or
`/ch`.

| Command | Alias | Purpose |
| --- | --- | --- |
| `/chronicle status` | `/ch status` | Health badge, queue and writer metrics, schema version, config summary |
| `/chronicle lookup <filters...>` | `/ch l` | Search recorded events |
| `/chronicle page <n>` | `/ch page <n>`, `/ch p <n>` | Next page of the last lookup |
| `/chronicle inspect [on\|off]` | `/ch i` | Click-to-inspect mode |
| `/chronicle rollback <filters...> [--preview]` | `/ch rb` | Undo matching changes |
| `/chronicle restore <filters...> [--preview]` | `/ch rs` | Redo changes a rollback undid |
| `/chronicle purge t:<age> [--preview] [--optimize]` | `/ch purge` | Delete old history |

### Lookup filters

| Filter | Meaning |
| --- | --- |
| `t:`/`time:` | Duration in `s`, `m`, `h`, `d`, `w`, compounds allowed (`1h30m`). Default 24h |
| `r:`/`radius:` | Inclusive box radius, max 100; `r:global` removes spatial bounds and needs an explicit time, actor or block filter. Players default to radius 5 |
| `u:`/`user:` | Comma-separated exact names, UUIDs or `#fakeplayer`; also matches environmental events the user caused |
| `b:`/`block:` | Comma-separated block or item IDs (bare names mean `minecraft:`) |
| `a:`/`action:` | `break`, `place`, `take`, `put`, `kill`; also `block`, `container`/`item` and signed forms (`-block`, `+container`) |
| `dim:`/`world:` | Dimension |
| `at:x,y,z` | Center of the spatial filter (cannot combine with `r:global`) |

Console spatial lookups need both `at:` and `dim:`. Unknown, duplicate and
malformed filters fail explicitly. Pages hold at most eight events, newest
first; the saved lookup lasts ten minutes (pages 1-10,000). Rows have hover
tooltips with exact details and click actions to copy values or teleport.

```text
/ch l t:1h30m r:10 u:Alice b:chest,diamond_ore a:break
/ch l r:global t:10m u:#fakeplayer
/ch l t:1d a:kill b:cow
/ch page 2
```

### Inspector
`/ch inspect` toggles an interactive mode: click a block, container, item frame
or armor stand to see its recent history without typing a lookup. Lookups are
rate-limited by `inspectorCooldownMillis`.

### Rollback and restore
`rollback` undoes and `restore` redoes the changes matching the filters. Use
`--preview` first; it reports what would change and modifies nothing.
- Work is applied on the server thread in budgeted slices per tick
  (`rollbackMaxOpsPerTick`, `rollbackMaxChunkLoadsPerTick`).
- One rollback, restore or purge runs at a time; a second is refused.
- A restored BlockEntity NBT snapshot and its recorded spills are deduplicated
  so items are never duplicated.
- Residual fluid recession and exact chiseled bookshelf slot restoration are
  handled.
- If a rollback applied changes but could not record that, those changes are
  quarantined in `rollback-quarantine.txt` next to the database and are left
  out of later plans. Review them, then delete the file to release them.
- `ENTITY_KILL` rows are forensic only and are not resurrected.

### Purge
`purge t:<age>` deletes events older than the age (minimum `60s`; no other
filters). The single writer works in bounded batches (`purgeBatchSize`) between
live journal batches, prunes `actor_profiles` that no remaining row names, and
reports counts and elapsed time. `--preview` only counts. `--optimize` also runs
`PRAGMA optimize` and truncates the WAL. Dead letters are not purged.

## Server configuration

`config/chronicle-server.toml` is generated on first start. A copy in
`<world>/serverconfig/chronicle-server.toml` overrides it for that world.
Values are clamped to their ranges and reload without a restart.

| Key | Section | Default | Range | Purpose |
| --- | --- | --- | --- | --- |
| `inspectorCooldownMillis` | `inspector` | `200` | 0-10000 | Minimum ms between one player's inspector lookups |
| `rollbackMaxOpsPerTick` | `rollback` | `250` | 10-5000 | World changes a rollback or restore applies per tick |
| `rollbackMaxChunkLoadsPerTick` | `rollback` | `8` | 1-64 | Synchronous chunk loads per tick during rollback or restore |
| `purgeBatchSize` | `retention` | `5000` | 100-50000 | Rows one purge step deletes before live persistence resumes |
| `autoPurgeRetentionDays` | `retention` | `0` (off) | 0-3650 | If above 0, purge older events once after each start |
| `entityKillIgnoredTypes` | `audit` | `[]` | entity IDs | Entity types whose deaths are never recorded, e.g. `["minecraft:chicken"]` |

Storage settings (journal and database paths, queue and batch sizes) are
separate, in the optional `<world>/chronicle/chronicle.properties` described
under [Persistence runtime](#persistence-runtime).

## Installation and JVM arguments

Build with JDK 25 (`./gradlew build`) and put `build/libs/chronicle-*.jar` in
the server's `mods/` directory. The JAR bundles OpenHFT Chronicle Queue and
SQLite JDBC. Add the options from [chronicle-jvm.args](chronicle-jvm.args) to
`user_jvm_args.txt`: they open the JDK internals OpenHFT needs
(`--add-opens`, `--enable-native-access`) and disable its background releaser,
disk monitor and analytics. Chronicle targets Java 25; the OpenHFT grants are
also required on Java 21. Development runs and tests apply them automatically.

## Known limitations

- Block entity NBT snapshots are capped at 64 KiB and container item component
  snapshots at 16 KiB. Larger ones keep block states or item identity with an
  explicit status and no NBT; truncated NBT is never stored.
- Causal memory for fire, poured lava and dispenser owners is in memory only,
  bounded, expires after 15 minutes and is cleared at server stop. Lightning
  fires and fires lit by flowing lava are unattributed.
- Redstone-triggered dispensers are attributed to a player whose nearby
  activation (within 32 blocks and about 5 seconds) caused the trigger; it is
  a proximity heuristic, not proof.
- `ENTITY_KILL` is forensic only; `/ch rb` does not resurrect entities.
- Container sessions show the session player, not proven exclusive causality
  (automation and other viewers can contribute). Menu-less storage changes in
  the same tick can be attributed to the wrong nearby player.
- Persisted NBT is opaque and not assumed safe to restore across Minecraft or
  mod version changes.
- Verified on Linux/WSL2 with local storage; Windows and network filesystems
  are unverified.

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
`build/libs/chronicle-1.0.0.jar`.

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
Campfire cooking output and removals by other mods or entities are not observed.

## Environmental causality

Blocks destroyed by explosions, fire burnout and projectiles are recorded as
`BLOCK_BREAK` (with the block entity NBT snapshot taken before removal) together
with any storage spill. Environmental rows use actor type `ENVIRONMENT`, an actor
such as `#tnt`, `#creeper`, `#end_crystal`, `#fire` or `#explosion` (bed and
respawn anchor blasts), and the responsible player as root actor when one can be
traced: the TNT owner or projectile shooter, a player who ignited a creeper, the
player a hostile mob was targeting, or the player who clicked an exploding bed or
anchor. TNT primed by fire or by another explosion inherits that root.
Explosion and projectile removals are confirmed at the next tick boundary; blocks
that survive record nothing.

Fire attribution follows the chain from ignition (flint and steel, fire charge,
an attributed explosion, or lava a player poured with a bucket) through every
spread step to each block it burns. Vanilla posts no events for fire spread or
burnout, so Chronicle observes them with small, read-only Mixins on `FireBlock`,
`BucketItem` (poured lava) and `TntBlock` (which explosion primed a TNT block). Causal memory is bounded (4,096 fire
positions per dimension and 4,096 entities, 15-minute expiry) and cleared at
server stop. Lightning fires and fires lit by flowing lava are unattributed.

Item frames that drop after their supporting block is broken by a player,
explosion or fire are recorded as `ITEM_TAKE` by whoever removed the support (the
frame drops on its own support check, up to 101 of its ticks later; the watch
counts those ticks, not wall-clock time, so server lag does not lose the drop). Decorated pots
shattered by a projectile and items knocked out of frames by one are attributed
to the shooting player, or to `#<projectile>` with a root actor otherwise.

`u:<name>` and `u:<uuid>` match both a player's own actions and the
environmental events they caused; such rows show as `#tnt (← Name) broke stone`,
with the root UUID in the hover text and copied on click.

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
