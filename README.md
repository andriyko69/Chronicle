# Chronicle

Chronicle is a server-side NeoForge 1.21.1 mod being developed into a forensic
logging, inspection, querying, and rollback system for modded Minecraft.
Correctness and forensic reliability take priority over feature velocity.

**Early development:** immutable synthetic events can be journaled, persisted
to SQLite and recovered after process failure. Minecraft audit logging,
commands, inspection and rollback are not implemented yet.

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
infrastructure readiness and clean closure, and world saving. It does not run
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
```

The stress task submits 100,000 synthetic events; it is not a production
benchmark. `runGameTestServer` is retained for future gameplay tests, but an
empty suite fails at startup and is not part of CI.
Use the [version-matched GameTest documentation](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)
when adding real tests; ordinary `src/test` classes are not automatically game mods.

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
