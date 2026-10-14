#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check NeoForge and Chronicle readiness, world saving, and clean shutdown (POSIX)."""

import argparse
import json
import os
from pathlib import Path
import queue
import re
import signal
import subprocess
import tempfile
import threading
import time


def positive_seconds(value):
    seconds = int(value)
    if seconds <= 0:
        raise argparse.ArgumentTypeError("must be a positive number of seconds")
    return seconds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--accept-eula", action="store_true",
        help="confirm that you accept https://aka.ms/MinecraftEULA for this test",
    )
    parser.add_argument("--startup-timeout", type=positive_seconds, default=600)
    parser.add_argument("--shutdown-timeout", type=positive_seconds, default=60)
    parser.add_argument("--installed-directory", type=Path,
                        help="test an existing disposable NeoForge installation with the built mod and accepted EULA")
    args = parser.parse_args()
    if not args.accept_eula and args.installed_directory is None:
        parser.error("read the Minecraft EULA and pass --accept-eula if you accept it")
    if os.name != "posix":
        parser.error("this helper requires POSIX; use gradlew.bat runServer on Windows")

    root = Path(__file__).resolve().parents[1]
    if args.installed_directory is not None:
        directory = args.installed_directory.resolve()
        if not (directory / "eula.txt").is_file() or not re.search(
            r"(?m)^eula=true\s*$", (directory / "eula.txt").read_text(encoding="utf-8")
        ):
            parser.error("installed directory must already have eula=true")
        if not re.search(r"(?m)^server-ip=127\.0\.0\.1\s*$",
                         (directory / "server.properties").read_text(encoding="utf-8")):
            parser.error("installed smoke server must bind to 127.0.0.1")
        version = re.search(r"(?m)^neo_version=(.+)$", (root / "gradle.properties").read_text()).group(1)
        launch_args = directory / "libraries/net/neoforged/neoforge" / version / "unix_args.txt"
        if not launch_args.is_file():
            parser.error(f"missing installed NeoForge arguments: {launch_args}")
        command = ["java", f"@{root / 'chronicle-jvm.args'}", "@user_jvm_args.txt", f"@{launch_args}", "--nogui"]
        working_directory = directory
    else:
        parent = root / "run" / "smoke"
        parent.mkdir(parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix="server-", dir=parent))
        (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        settings = json.dumps({
            "layers": [
                {"block": "minecraft:bedrock", "height": 1},
                {"block": "minecraft:dirt", "height": 2},
                {"block": "minecraft:grass_block", "height": 1},
            ],
            "biome": "minecraft:plains",
        })
        (directory / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nonline-mode=true\n"
            "level-type=minecraft:flat\n" + f"generator-settings={settings}\n"
            "generate-structures=false\nview-distance=2\nsimulation-distance=2\n"
            "max-players=1\nmotd=Chronicle smoke test\n", encoding="utf-8",
        )
        command = [str(root / "gradlew"), "--no-daemon", "--console=plain", "runServer",
                   f"-Pchronicle.runDirectory={directory}"]
        working_directory = root
    log_path = directory / "console.log"
    print(f"Smoke-test files and logs: {directory}", flush=True)
    lines = queue.Queue()
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command, cwd=working_directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
            bufsize=1, start_new_session=True,
        )

        def read_output():
            try:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    lines.put(line)
            finally:
                lines.put(None)

        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        ready = loaded = saved = infrastructure_ready = infrastructure_closed = False
        build_success = args.installed_directory is not None
        stop_sent = False
        commands_sent = page_sent = False
        status_health = status_totals = lookup_seen = page_seen = False
        deadline = time.monotonic() + args.startup_timeout
        failure = None
        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError("shutdown timed out" if ready else "startup timed out")
                try:
                    line = lines.get(timeout=min(remaining, 0.5))
                except queue.Empty:
                    continue
                if line is None:
                    break
                if any(message in line for message in (
                    "Chronicle infrastructure startup failed", "Chronicle infrastructure configuration/startup failed",
                    "Chronicle journal unavailable", "Chronicle journal replay unavailable",
                    "Chronicle fatal persistence failure", "Chronicle shutdown was not clean",
                    "Chronicle fatal query/storage failure",
                )):
                    raise RuntimeError("Chronicle infrastructure reported a fatal lifecycle failure")
                loaded |= bool(re.search(r"Chronicle\s+\S+\s+\(chronicle\)", line))
                saved |= "All dimensions are saved" in line
                build_success |= "BUILD SUCCESSFUL" in line
                infrastructure_ready |= "Chronicle ready: schema=" in line
                infrastructure_closed |= "Chronicle closed:" in line
                if not ready and re.search(r'Done \([^)]+\)! For help, type "help"', line):
                    ready = True
                status_health |= "Chronicle Status ── HEALTHY · schema=5" in line
                status_totals |= "Database totals: persisted events=" in line
                if "Chronicle Lookup ── Page 1" in line:
                    if page_sent:
                        page_seen = True
                    else:
                        lookup_seen = True
                page_seen |= page_sent and "Page 1 has no results." in line
                if ready and infrastructure_ready and not commands_sent:
                    commands_sent = True
                    print("Server ready; checking Chronicle status and lookup commands.", flush=True)
                    process.stdin.write("chronicle status\nchronicle lookup r:global t:10m u:ChronicleSmokeNoActor\n")
                    process.stdin.flush()
                if status_health and status_totals and lookup_seen and not page_sent:
                    page_sent = True
                    process.stdin.write("ch p 1\n")
                    process.stdin.flush()
                if page_seen and not stop_sent:
                    stop_sent = True
                    print("Server ready; requesting clean shutdown.", flush=True)
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    deadline = time.monotonic() + args.shutdown_timeout
            exit_code = process.wait(timeout=max(1, deadline - time.monotonic()))
            if exit_code or not all((ready, loaded, saved, build_success, infrastructure_ready, infrastructure_closed,
                                     status_health, status_totals, lookup_seen, page_seen)):
                raise RuntimeError(
                    f"incomplete smoke test: exit={exit_code}, ready={ready}, "
                    f"chronicle_loaded={loaded}, worlds_saved={saved}, build_success={build_success}, "
                    f"infrastructure_ready={infrastructure_ready}, infrastructure_closed={infrastructure_closed}, "
                    f"status_health={status_health}, status_totals={status_totals}, lookup={lookup_seen}, page={page_seen}"
                )
        except (RuntimeError, OSError, subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            failure = str(error) or "interrupted"
        finally:
            if process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=args.shutdown_timeout)
                except (OSError, subprocess.TimeoutExpired):
                    try:
                        os.killpg(process.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=5)
            # A failed wrapper can exit while descendants still hold the output pipe.
            if failure:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            try:
                process.stdin.close()
            except BrokenPipeError:
                pass
            reader.join(timeout=5)
            if reader.is_alive():
                failure = failure or "output reader did not finish"
            else:
                process.stdout.close()
        if failure:
            print(f"Smoke test failed: {failure}. Inspect {log_path}", flush=True)
            return 1
    print("Smoke test passed: Chronicle loaded, status/lookup/page commands responded, worlds saved, clean exit.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
