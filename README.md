# Chronicle

Chronicle is a server-side NeoForge 1.21.1 mod being developed into a forensic
logging, inspection, querying, and rollback system for modded Minecraft.
Correctness and forensic reliability take priority over feature velocity.

**Early development:** this repository currently provides a minimal mod entry
point and development infrastructure. Audit logging, inspection, persistence,
and rollback are not implemented yet.

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
startup or shutdown timeouts. It checks that Chronicle loaded and the world
was saved; it does not run gameplay tests or connect a client.

Server readiness and clean shutdown are separate checks from compilation.
Joining with a client without Chronicle and loading the packaged JAR on an
installed NeoForge server are additional integration checks.

## Tests and agent workflow

There are no behavior tests yet. `runGameTestServer` is retained for future
tests, but an empty suite fails at startup and is not part of CI.
Use the [version-matched GameTest documentation](https://docs.neoforged.net/docs/1.21.1/misc/gametest/)
when adding real tests; ordinary `src/test` classes are not automatically game mods.

[AGENTS.md](AGENTS.md) records development invariants.
[.agent/PLANS.md](.agent/PLANS.md) describes when a substantive plan is useful;
plans are committed under `.agent/plans/`. Project-local skills are reserved
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
