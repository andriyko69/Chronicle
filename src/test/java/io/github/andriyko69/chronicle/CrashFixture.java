/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.journal.openhft.ChronicleQueueJournal;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.runtime.*;
import io.github.andriyko69.chronicle.storage.*;
import io.github.andriyko69.chronicle.storage.sqlite.SQLiteStorageBackend;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Abrupt child-process fixture: real SQLite initialized, commits deliberately unavailable. */
public final class CrashFixture {
    public static void main(String[] args) throws Exception {
        var config = InfrastructureConfig.defaults(Path.of(args[0]));
        var clock = ChronicleClock.system();
        var base = new SQLiteStorageBackend(config, clock, "crash-test");
        var backend = new StorageBackend() {
            public StorageWriter openWriter(UUID identity) throws StorageFailure {
                var writer = base.openWriter(identity);
                return new StorageWriter() {
                    public Checkpoint checkpoint() throws StorageFailure { return writer.checkpoint(); }
                    public CommitResult commit(Checkpoint expected, List<TerminalRecord> records) throws StorageFailure {
                        throw new StorageFailure(StorageFailure.Kind.TRANSIENT, "Test database offline after initialization", "TEST_OFFLINE", null);
                    }
                    public void close() throws StorageFailure { writer.close(); }
                };
            }
            public StorageReader openReader() throws StorageFailure { return base.openReader(); }
        };
        var runtime = new ChronicleRuntime(config, clock, backend, () -> new ChronicleQueueJournal(config.journal(), clock));
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        io.github.andriyko69.chronicle.ingest.Submission last = null;
        for (int i = 0; i < 1000; i++) last = PersistenceTest.submit(runtime, PersistenceTest.event(i));
        last.journaled().toCompletableFuture().get(10, TimeUnit.SECONDS);
        Runtime.getRuntime().halt(7);
    }
}
