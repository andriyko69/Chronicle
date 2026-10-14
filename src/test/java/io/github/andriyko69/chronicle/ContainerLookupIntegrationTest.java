/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.diagnostics.ChronicleHealth;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.runtime.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContainerLookupIntegrationTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final ChronicleClock CLOCK = ChronicleClock.fixed(NOW);
    private static LookupPage lookup(ChronicleRuntime runtime, String filter) throws Exception {
        return runtime.lookup(new LookupParser(CLOCK).parse("r:global t:1h " + filter, LookupParser.Context.console()), 1)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
    @Test void mixedJournalRowsFilterFormatAndSurviveRestartWithIdempotentDuplicates() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var take = ContainerPayloadCodecTest.event("ITEM_TAKE", "minecraft:diamond_sword", 1, NOW.minusSeconds(1));
        var put = ContainerPayloadCodecTest.event("ITEM_PUT", "minecraft:iron_ingot", 8, NOW.minusSeconds(2));
        var blockSource = BlockDeltaCodecTest.event(7);
        var block = new AuditEvent(blockSource.id(), NOW.minusSeconds(3), blockSource.envelope(), blockSource.payloadVersion(), blockSource.payload());
        try (var runtime = new ChronicleRuntime(config, CLOCK, "test")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            for (var event : List.of(block, put, take, take, put)) PersistenceTest.submit(runtime, event);
            PersistenceTest.await(() -> runtime.diagnostics().backlog() == 0 && runtime.diagnostics().persisted() == 3);
            assertEquals(List.of(take.id(), put.id(), block.id()), lookup(runtime, "").rows().stream().map(LookupRow::id).toList());
            for (String alias : List.of("item", "container")) assertEquals(2, lookup(runtime, "a:" + alias).rows().size());
            for (String alias : List.of("take", "-item", "-container")) assertEquals(List.of(take.id()), lookup(runtime, "a:" + alias).rows().stream().map(LookupRow::id).toList());
            for (String alias : List.of("put", "+item", "+container")) assertEquals(List.of(put.id()), lookup(runtime, "a:" + alias).rows().stream().map(LookupRow::id).toList());
            assertEquals(List.of(block.id()), lookup(runtime, "a:block").rows().stream().map(LookupRow::id).toList());
            assertEquals(List.of(take.id()), lookup(runtime, "b:diamond_sword").rows().stream().map(LookupRow::id).toList());
            assertEquals(List.of(put.id()), lookup(runtime, "a:item b:iron_ingot").rows().stream().map(LookupRow::id).toList());
            var rows = lookup(runtime, "a:take"); var row = rows.rows().getFirst();
            assertEquals(ContainerPayloadCodecTest.payload(1, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT), row.item());
            assertEquals("minecraft:diamond_sword", row.envelope().targetId());
            assertEquals(take.id(), row.id());
            assertEquals(take.occurredAt(), row.occurredAt());
            assertEquals(take.envelope(), row.envelope());
            assertEquals(ChronicleHealth.HEALTHY, runtime.health());
        }
        assertEquals(6, PersistenceTest.scalar(config.database(), "PRAGMA user_version"));
        assertEquals(5, PersistenceTest.scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
        try (var runtime = new ChronicleRuntime(config, CLOCK, "restart")) {
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(List.of(take, put, block), runtime.recent(10).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(2, lookup(runtime, "a:item").rows().size());
        }
    }
    @Test void itemDuplicateIdConflictHaltsWithoutAdvancingCheckpoint() throws Exception {
        for (String action : List.of("ITEM_PUT", "ITEM_TAKE")) {
            var config = InfrastructureConfig.defaults(root.resolve(action));
            var first = ContainerPayloadCodecTest.event(action, "minecraft:diamond", 2, NOW);
            var runtime = new ChronicleRuntime(config, CLOCK, "test");
            runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            try {
                PersistenceTest.submit(runtime, first); PersistenceTest.await(() -> runtime.diagnostics().persisted() == 1);
                var conflict = AuditEvent.item(first.id(), NOW, first.envelope(), ContainerPayloadCodecTest.payload(3, ItemTransactionPayload.ComponentSnapshotStatus.PRESENT));
                PersistenceTest.submit(runtime, conflict); PersistenceTest.await(() -> runtime.health() == ChronicleHealth.FATAL_STORAGE);
            } finally { assertThrows(IllegalStateException.class, runtime::close); }
            assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT terminal_records FROM journal_checkpoint"));
            assertEquals(1, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events"));
        }
    }
}
