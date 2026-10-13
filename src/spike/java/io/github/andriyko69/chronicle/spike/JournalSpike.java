/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.spike;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.core.onoes.Slf4jExceptionHandler;
import net.openhft.posix.PosixAPI;
import net.openhft.posix.internal.PosixAPIHolder;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.RollCycles;

public final class JournalSpike {
    public static void main(String[] args) throws Exception {
        io.github.andriyko69.chronicle.journal.openhft.ModuleAccess.initialize();
        System.out.println("SPIKE OpenHFT module=" + net.openhft.chronicle.core.Jvm.class.getModule());
        if (args.length > 0 && args[0].equals("crash")) {
            try (var queue = builder(Path.of(args[1])).build()) {
                var appender = queue.createAppender();
                for (int i = 0; i < 1000; i++) write(appender, i);
                appender.sync();
                // Deliberately leave a half-written document and do not run close hooks.
                appender.writingDocument().wire().bytes().writeLong(42);
                Runtime.getRuntime().halt(0);
            }
            return;
        }
        boolean suite = args.length > 0 && args[0].equals("suite");
        Path path = Files.createTempDirectory("chronicle-queue-spike-");
        System.out.println("SPIKE directory=" + path + " java=" + Runtime.version());
        try {
        try (ChronicleQueue queue = builder(path).build()) {
            var appender = queue.createAppender();
            try (var document = appender.writingDocument()) {
                document.wire().bytes().write(new byte[]{1, 2, 3, 4});
            }
            appender.sync();
            try (var tailer = queue.createTailer(); var document = tailer.readingDocument()) {
                if (!document.isPresent() || document.wire().bytes().readInt() != 0x04030201) {
                    throw new AssertionError("Opaque bytes failed to round trip");
                }
            }
            System.out.println("SPIKE opaque write/read/sync succeeded");
        }
        try (ChronicleQueue queue = builder(path).build(); var tailer = queue.createTailer();
                var document = tailer.readingDocument()) {
            if (!document.isPresent() || document.wire().bytes().readInt() != 0x04030201)
                throw new AssertionError("Restart lost payload");
        }
        System.out.println("SPIKE restart succeeded");
        if (suite) {
            rolling(path.resolve("rolling"));
            syncFailure(path.resolve("sync-failure"));
            crash(path.resolve("crash"));
            volume(path.resolve("volume"));
            System.out.println("SPIKE suite succeeded");
        }
        } finally {
            try (var paths = Files.walk(path)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static SingleChronicleQueueBuilder builder(Path path) {
        return SingleChronicleQueueBuilder.binary(path).rollCycle(RollCycles.FAST_HOURLY)
                .blockSize(4 * 1024 * 1024).syncMode(SyncMode.SYNC).timeoutMS(1000);
    }

    private static void write(net.openhft.chronicle.queue.ExcerptAppender appender, int value) {
        try (var document = appender.writingDocument()) {
            document.wire().bytes().writeInt(value);
        }
    }

    private static void rolling(Path path) throws Exception {
        var clock = new SetTimeProvider(java.time.Instant.parse("2026-10-13T00:00:00Z"));
        long committed;
        try (var queue = builder(path).timeProvider(clock).build()) {
            var appender = queue.createAppender();
            for (int i = 0; i < 3; i++) {
                write(appender, i);
                appender.sync();
                clock.advanceMillis(3_600_000);
            }
            committed = appender.lastIndexAppended();
            try (var tailer = queue.createTailer()) {
                for (int i = 0; i < 3; i++) {
                    try (var document = tailer.readingDocument()) {
                        if (!document.isPresent() || document.wire().bytes().readInt() != i)
                            throw new AssertionError("Hourly replay lost " + i);
                    }
                }
            }
        }
        // Startup-only cleanup with ALL appender/tailer mappings closed. Keep committed cycle.
        try (var files = Files.list(path)) {
            var cycles = files.filter(f -> f.toString().endsWith(".cq4")).sorted().toList();
            if (cycles.size() != 3) throw new AssertionError("Expected 3 hourly cycles: " + cycles);
            Files.delete(cycles.get(0));
        }
        try (var queue = builder(path).timeProvider(clock).build(); var tailer = queue.createTailer()) {
            if (!tailer.moveToIndex(committed)) throw new AssertionError("Retained checkpoint absent");
            try (var document = tailer.readingDocument()) {
                if (!document.isPresent() || document.wire().bytes().readInt() != 2)
                    throw new AssertionError("Retention damaged checkpoint");
            }
        }
        System.out.println("SPIKE hourly rolling/replay/closed-file retention succeeded");
    }

    private static void syncFailure(Path path) {
        try (var queue = builder(path).build()) {
            var appender = queue.createAppender();
            write(appender, 17);
            PosixAPI original = PosixAPI.posix();
            PosixAPIHolder.POSIX_API = (PosixAPI) Proxy.newProxyInstance(PosixAPI.class.getClassLoader(),
                    new Class<?>[]{PosixAPI.class}, (proxy, method, values) -> {
                        if (method.getName().equals("msync")) return -1;
                        return method.invoke(original, values);
                    });
            var recorded = new java.util.concurrent.atomic.AtomicReference<String>();
            Jvm.setThreadLocalExceptionHandlers((logger, message, failure) -> {
                recorded.compareAndSet(null, message);
                Slf4jExceptionHandler.ERROR.on(logger, message, failure);
            }, Slf4jExceptionHandler.WARN, Slf4jExceptionHandler.DEBUG);
            try {
                appender.sync();
                if (recorded.get() == null || !recorded.get().contains("msync failed"))
                    throw new AssertionError("Native sync failure was hidden from recording handler");
            } finally {
                PosixAPIHolder.POSIX_API = original;
                Jvm.setThreadLocalExceptionHandlers(null, null, null);
            }
        }
        System.out.println("SPIKE explicit native sync failure detection succeeded (test-only POSIX injection)");
    }

    private static void crash(Path path) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.addAll(Arrays.asList("-cp", System.getProperty("java.class.path"),
                JournalSpike.class.getName(), "crash", path.toString()));
        var process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(path.resolveSibling("crash-output.txt").toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            throw new AssertionError("Crash child timed out");
        }
        if (process.exitValue() != 0) throw new AssertionError("Crash child failed");
        try (var queue = builder(path).build()) {
            var appender = queue.createAppender();
            write(appender, 1000);
            appender.sync();
            try (var tailer = queue.createTailer()) {
                for (int i = 0; i <= 1000; i++) {
                    try (var document = tailer.readingDocument()) {
                        if (!document.isPresent() || document.wire().bytes().readInt() != i)
                            throw new AssertionError("Crash recovery lost/corrupted " + i);
                    }
                }
            }
        }
        System.out.println("SPIKE abrupt-process/incomplete-document recovery succeeded: 1000 + 1 records");
    }

    private static void volume(Path path) {
        long start = System.nanoTime();
        try (var queue = builder(path).build()) {
            var appender = queue.createAppender();
            for (int i = 0; i < 100_000; i++) {
                write(appender, i);
                if (i % 1000 == 0) appender.sync();
            }
            appender.sync();
            try (var tailer = queue.createTailer()) {
                for (int i = 0; i < 100_000; i++) {
                    try (var document = tailer.readingDocument()) {
                        if (!document.isPresent() || document.wire().bytes().readInt() != i)
                            throw new AssertionError("Volume mismatch " + i);
                    }
                }
            }
        }
        System.out.printf("SPIKE 100000 append/sync/replay seconds=%.3f%n", (System.nanoTime() - start) / 1e9);
    }
}
