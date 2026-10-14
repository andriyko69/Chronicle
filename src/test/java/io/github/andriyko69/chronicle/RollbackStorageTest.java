/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.diagnostics.ChronicleHealth;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.rollback.RollbackPlan;
import io.github.andriyko69.chronicle.runtime.*;
import io.github.andriyko69.chronicle.storage.migration.MigrationManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RollbackStorageTest {
    @TempDir Path root;
    private static final Instant NOW = Instant.now();
    private static final ChronicleClock CLOCK = ChronicleClock.fixed(NOW);
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    /** V001–V005 must stay byte-for-byte identical: deployed databases verify these exact checksums. */
    private static final Map<String, String> HISTORICAL = Map.of(
            "V001__infrastructure.sql", "a88252e0257a3d23a986d1a5b97e8399605b3a60f0203959e67c9ad1807d8178",
            "V002__audit_events.sql", "e19fea3d79044f6901e92921d47fe7162e529f9f42fbcc59cf2817eb44d5881b",
            "V003__lookup_indexes.sql", "4245267ef98bb745df44e96feadee712754377f3370e6092780075db38820c79",
            "V004__position_index.sql", "d1188bc4ed99017fc6dffb336988a81a898e7125c8d7122486e63fecfa9aa996",
            "V005__root_actor_index.sql", "c952d8cf139b1687036bcfac884fbaf0d5110577052c7c7b96fdb15be5608c12");

    private static EventEnvelope envelope(int x, String type, String actorId, UUID actor, String target) {
        return new EventEnvelope("minecraft:overworld", x, 64, 0, type, "PLAYER", actorId, actor, null, target);
    }
    static AuditEvent block(int x, String type, String actorId, UUID actor, Instant at, String blockId, BlockDeltaPayload.SnapshotStatus nbt) {
        var state = new BlockStateSnapshot(blockId, Map.of());
        boolean breaking = type.equals("BLOCK_BREAK");
        return AuditEvent.block(EventId.generate(), at, envelope(x, type, actorId, actor, blockId),
                new BlockDeltaPayload(breaking ? state : BlockStateSnapshot.AIR, breaking ? BlockStateSnapshot.AIR : state,
                        BlockDeltaPayload.Observation.HOOK_OBSERVATION, nbt, nbt == BlockDeltaPayload.SnapshotStatus.PRESENT ? new byte[]{10, 0, 0, 0} : null));
    }
    static AuditEvent item(int x, String type, String actorId, UUID actor, Instant at, String itemId, String container) {
        return AuditEvent.item(EventId.generate(), at, envelope(x, type, actorId, actor, itemId),
                new ItemTransactionPayload(10, container, null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
    }
    private static ChronicleRuntime start(InfrastructureConfig config) throws Exception {
        var runtime = new ChronicleRuntime(config, ChronicleClock.system(), "test");
        runtime.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return runtime;
    }
    private static void persist(ChronicleRuntime runtime, List<AuditEvent> events) throws Exception {
        long before = runtime.diagnostics().persisted();
        for (var event : events) PersistenceTest.submit(runtime, event);
        PersistenceTest.await(() -> runtime.diagnostics().persisted() == before + events.size());
    }
    private static RollbackPlan plan(ChronicleRuntime runtime, String query, boolean restore) throws Exception {
        var spec = new LookupParser(CLOCK).parseRollback(query, LookupParser.Context.console()).spec();
        return runtime.planRollback(spec, restore).toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
    private static List<EventId> ids(RollbackPlan plan) { return plan.steps().stream().map(step -> step.event().id()).toList(); }
    private static void mark(ChronicleRuntime runtime, boolean rolledBack, AuditEvent... events) throws Exception {
        runtime.setRolledBack(Arrays.stream(events).map(AuditEvent::id).toList(), rolledBack).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test void v005UpgradesToV006WithDefaultStateAndStableHistoricalChecksums() throws Exception {
        Path database = root.resolve("v005.db");
        var names = List.of("V001__infrastructure.sql", "V002__audit_events.sql", "V003__lookup_indexes.sql",
                "V004__position_index.sql", "V005__root_actor_index.sql");
        try (var connection = PersistenceTest.connection(database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,name TEXT NOT NULL,checksum TEXT NOT NULL,applied_at TEXT NOT NULL) STRICT");
            for (int i = 0; i < names.size(); i++) {
                byte[] bytes;
                try (var input = MigrationManager.class.getResourceAsStream(names.get(i))) { bytes = input.readAllBytes(); }
                String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                assertEquals(HISTORICAL.get(names.get(i)), checksum, names.get(i) + " changed");
                for (String sql : new String(bytes, StandardCharsets.UTF_8).split(";")) if (!sql.isBlank()) statement.execute(sql);
                try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES (?,?,?,'baseline')")) {
                    insert.setInt(1, i + 1); insert.setString(2, names.get(i)); insert.setString(3, checksum); insert.executeUpdate();
                }
            }
            statement.execute("PRAGMA application_id=1128813135"); statement.execute("PRAGMA user_version=5");
            try (var insert = connection.prepareStatement("INSERT INTO audit_events VALUES (X'00000000000070008000000000000001',1,2,'minecraft:overworld',1,2,3,'BLOCK_BREAK','PLAYER','before',NULL,NULL,'minecraft:stone',1,?,NULL)")) {
                insert.setBytes(1, BlockDeltaCodecTest.event(1).payload()); insert.executeUpdate();
            }
        }
        try (var connection = PersistenceTest.connection(database)) { MigrationManager.migrate(connection, Instant.now()); }
        assertEquals(6, PersistenceTest.scalar(database, "PRAGMA user_version"));
        assertEquals(5, PersistenceTest.scalar(database, "SELECT count(*) FROM schema_migrations WHERE applied_at='baseline'"));
        assertEquals(1, PersistenceTest.scalar(database, "SELECT count(*) FROM audit_events WHERE actor_id='before' AND rolled_back=0"));
        try (var connection = PersistenceTest.connection(database); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("UPDATE audit_events SET rolled_back=2"));
            assertThrows(SQLException.class, () -> statement.execute("UPDATE audit_events SET rolled_back=NULL"));
            for (var entry : HISTORICAL.entrySet()) {
                try (var rows = statement.executeQuery("SELECT checksum FROM schema_migrations WHERE name='" + entry.getKey() + "'")) {
                    assertTrue(rows.next()); assertEquals(entry.getValue(), rows.getString(1));
                }
            }
        }
        try (var connection = PersistenceTest.connection(database)) { MigrationManager.migrate(connection, Instant.now()); }
        assertEquals(6, PersistenceTest.scalar(database, "SELECT count(*) FROM schema_migrations"));
    }

    @Test void candidatesFilterByStateAndApplyNewestFirstForRollbackOldestFirstForRestore() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var a = block(1, "BLOCK_PLACE", "Alice", ALICE, NOW.minusSeconds(30), "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE);
        var b = block(2, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(20), "minecraft:dirt", BlockDeltaPayload.SnapshotStatus.NONE);
        var c = item(3, "ITEM_PUT", "Alice", ALICE, NOW.minusSeconds(10), "minecraft:diamond", "minecraft:chest");
        var bob = block(4, "BLOCK_BREAK", "Bob", BOB, NOW.minusSeconds(5), "minecraft:dirt", BlockDeltaPayload.SnapshotStatus.NONE);
        try (var runtime = start(config)) {
            persist(runtime, List.of(a, b, c, bob));
            assertEquals(List.of(c.id(), b.id(), a.id()), ids(plan(runtime, "r:global u:Alice", false)));
            assertTrue(plan(runtime, "r:global u:Alice", true).steps().isEmpty(), "Nothing is rolled back yet");
            mark(runtime, true, b);
            assertEquals(List.of(c.id(), a.id()), ids(plan(runtime, "r:global u:Alice", false)));
            assertEquals(List.of(b.id()), ids(plan(runtime, "r:global u:Alice", true)));
            mark(runtime, true, a, c);
            assertTrue(plan(runtime, "r:global u:Alice", false).steps().isEmpty(), "A second rollback is a no-op");
            assertEquals(List.of(a.id(), b.id(), c.id()), ids(plan(runtime, "r:global u:Alice", true)));
            var rows = runtime.lookup(new LookupParser(CLOCK).parse("r:global t:1h", LookupParser.Context.console()), 1).toCompletableFuture().get(5, TimeUnit.SECONDS).rows();
            assertEquals(Map.of(a.id(), true, b.id(), true, c.id(), true, bob.id(), false),
                    rows.stream().collect(java.util.stream.Collectors.toMap(LookupRow::id, LookupRow::rolledBack)));
            mark(runtime, false, b);
            assertEquals(List.of(b.id()), ids(plan(runtime, "r:global u:Alice", false)));
            var plan = plan(runtime, "r:global u:Alice a:block", true);
            assertEquals(List.of(a.id()), ids(plan));
            assertEquals(1, plan.blockChanges()); assertEquals(0, plan.itemChanges());
        }
        assertEquals(2, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events WHERE rolled_back=1"));
    }

    @Test void spillRowsTravelWithTheirSnapshotBreakRegardlessOfFilters() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        var present = BlockDeltaPayload.SnapshotStatus.PRESENT;
        var firstBreak = block(1, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(100), "minecraft:chest", present);
        var firstSpill = item(1, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(100).plusMillis(50), "minecraft:diamond", "minecraft:chest");
        var viewerDeposit = item(1, "ITEM_PUT", "Bob", BOB, NOW.minusSeconds(100), "minecraft:gold_ingot", "minecraft:chest");
        var otherContainer = item(1, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(99), "minecraft:emerald", "minecraft:barrel");
        var secondBreak = block(1, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(90), "minecraft:chest", present);
        var secondSpill = item(1, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(90).plusMillis(50), "minecraft:diamond", "minecraft:chest");
        var lateTake = item(1, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(50), "minecraft:diamond", "minecraft:chest");
        var plainBreak = block(2, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(80), "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE);
        try (var runtime = start(config)) {
            persist(runtime, List.of(firstBreak, firstSpill, viewerDeposit, otherContainer, secondBreak, secondSpill, lateTake, plainBreak));
            var all = plan(runtime, "r:global t:1h", false);
            assertEquals(List.of(lateTake.id(), plainBreak.id(), secondBreak.id(), otherContainer.id(), viewerDeposit.id(), firstBreak.id()), ids(all));
            var companions = new HashMap<EventId, List<EventId>>();
            all.steps().forEach(step -> companions.put(step.event().id(), step.companions().stream().map(AuditEvent::id).toList()));
            assertEquals(List.of(firstSpill.id()), companions.get(firstBreak.id()));
            assertEquals(List.of(secondSpill.id()), companions.get(secondBreak.id()), "A later break owns the spill after it");
            assertEquals(List.of(), companions.get(plainBreak.id()));
            assertEquals(List.of(), companions.get(lateTake.id()));
            assertEquals(3, all.blockChanges(), "Three breaks");
            assertEquals(5, all.itemChanges(), "Three standalone item rows plus two spills");
            // An a:block rollback still carries the spills; an a:take rollback alone sees them as ordinary rows.
            var blocks = plan(runtime, "r:global t:1h a:block", false);
            assertEquals(List.of(secondSpill.id(), firstSpill.id()), blocks.steps().stream().flatMap(step -> step.companions().stream()).map(AuditEvent::id).toList());
            assertTrue(ids(plan(runtime, "r:global t:1h a:take", false)).containsAll(List.of(firstSpill.id(), secondSpill.id())));
            // A spill already in the other state is not the break's companion.
            mark(runtime, true, firstSpill);
            assertEquals(List.of(), plan(runtime, "r:global t:1h a:block", false).steps().stream()
                    .filter(step -> step.event().id().equals(firstBreak.id())).findFirst().orElseThrow().companions());
            mark(runtime, true, firstBreak, secondBreak, secondSpill);
            var restore = plan(runtime, "r:global t:1h a:block", true);
            assertEquals(List.of(firstBreak.id(), secondBreak.id()), ids(restore));
            assertEquals(List.of(firstSpill.id()), restore.steps().getFirst().companions().stream().map(AuditEvent::id).toList());
            assertEquals(List.of(secondSpill.id()), restore.steps().getLast().companions().stream().map(AuditEvent::id).toList());
        }
    }

    @Test void takesFromAReplacementContainerAreNeverPairedWithTheEarlierBreak() throws Exception {
        var present = BlockDeltaPayload.SnapshotStatus.PRESENT;
        var emptyBreak = block(1, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(20), "minecraft:chest", present);
        var replacement = block(1, "BLOCK_PLACE", "Alice", ALICE, NOW.minusSeconds(19), "minecraft:chest", BlockDeltaPayload.SnapshotStatus.NONE);
        var ordinaryTake = item(1, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(18), "minecraft:emerald", "minecraft:chest");
        var bucket = block(2, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(20), "minecraft:barrel", present);
        var waterlogged = block(2, "BLOCK_PLACE", "Bob", BOB, NOW.minusSeconds(19), "minecraft:water", BlockDeltaPayload.SnapshotStatus.NONE);
        var lateSpill = item(2, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(18), "minecraft:emerald", "minecraft:barrel");
        try (var runtime = start(InfrastructureConfig.defaults(root))) {
            persist(runtime, List.of(emptyBreak, replacement, ordinaryTake, bucket, waterlogged, lateSpill));
            var breaks = plan(runtime, "r:global t:1h u:Alice a:break", false);
            assertEquals(Set.of(emptyBreak.id(), bucket.id()), Set.copyOf(ids(breaks)));
            assertTrue(breaks.steps().stream().allMatch(step -> step.companions().isEmpty()), "Any later block change at the position ends the spill window");
            assertEquals(List.of(ordinaryTake.id()), ids(plan(runtime, "t:1h u:Alice a:take r:0 at:1,64,0 dim:overworld", false)));
        }
    }

    @Test void aSpillSettledLongAfterItsBreakStillPairsAndLaterTakesDoNot() throws Exception {
        var stalledBreak = block(1, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(300), "minecraft:chest", BlockDeltaPayload.SnapshotStatus.PRESENT);
        var delayedSpill = item(1, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(240), "minecraft:diamond", "minecraft:chest");
        var sameBatch = AuditEvent.item(EventId.generate(), delayedSpill.occurredAt(), delayedSpill.envelope(),
                new ItemTransactionPayload(3, "minecraft:chest", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null));
        var laterTake = item(1, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(200), "minecraft:emerald", "minecraft:chest");
        try (var runtime = start(InfrastructureConfig.defaults(root))) {
            persist(runtime, List.of(stalledBreak, delayedSpill, sameBatch, laterTake));
            var plan = plan(runtime, "r:global t:1h u:Alice a:break", false);
            assertEquals(List.of(stalledBreak.id()), ids(plan));
            assertEquals(Set.of(delayedSpill.id(), sameBatch.id()), Set.copyOf(plan.steps().getFirst().companions().stream().map(AuditEvent::id).toList()),
                    "A stall separates break and spill by minutes; only the first settled batch is the spill");
        }
    }

    @Test void aSpillBatchLargerThanOneScanPageIsPairedCompletely() throws Exception {
        var largeBreak = block(1, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(60), "mod:vault", BlockDeltaPayload.SnapshotStatus.PRESENT);
        var settled = NOW.minusSeconds(59);
        var spills = new ArrayList<AuditEvent>();
        for (int i = 0; i < 600; i++) spills.add(AuditEvent.item(EventId.generate(), settled,
                new EventEnvelope("minecraft:overworld", 1, 64, 0, "ITEM_TAKE", "PLAYER", "Alice", ALICE, null, "mod:gem_" + i),
                new ItemTransactionPayload(1, "mod:vault", null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null)));
        try (var runtime = start(InfrastructureConfig.defaults(root))) {
            var events = new ArrayList<AuditEvent>(spills); events.addFirst(largeBreak);
            persist(runtime, events);
            var plan = plan(runtime, "r:global t:1h u:Alice", false);
            assertEquals(List.of(largeBreak.id()), ids(plan));
            assertEquals(600, plan.steps().getFirst().companions().size(), "Every row of the batch travels with its break");
        }
    }

    @Test void quarantinedRowsAndBreaksHoldingThemAreNeverPlanned() throws Exception {
        var present = BlockDeltaPayload.SnapshotStatus.PRESENT;
        var held = block(1, "BLOCK_BREAK", "Alice", ALICE, NOW.minusSeconds(30), "minecraft:chest", present);
        var heldSpill = item(1, "ITEM_TAKE", "Alice", ALICE, NOW.minusSeconds(29), "minecraft:diamond", "minecraft:chest");
        var direct = block(2, "BLOCK_PLACE", "Alice", ALICE, NOW.minusSeconds(20), "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE);
        var free = block(3, "BLOCK_PLACE", "Alice", ALICE, NOW.minusSeconds(10), "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE);
        try (var runtime = start(InfrastructureConfig.defaults(root))) {
            persist(runtime, List.of(held, heldSpill, direct, free));
            runtime.quarantine(List.of(heldSpill.id(), direct.id()), true);
            var plan = plan(runtime, "r:global t:1h u:Alice", false);
            assertEquals(List.of(free.id()), ids(plan), "Only rows that cannot have been applied already are planned");
            assertEquals(2, plan.quarantined());
            assertEquals(List.of(), ids(plan(runtime, "r:global t:1h u:Alice", true)));
            java.nio.file.Files.delete(runtime.quarantineFile());
            assertEquals(3, plan(runtime, "r:global t:1h u:Alice", false).steps().size(), "Reviewed and released by removing the file");
            java.nio.file.Files.writeString(runtime.quarantineFile(), "not-an-id\n");
            var failure = assertThrows(ExecutionException.class, () -> runtime.planRollback(
                    new LookupParser(CLOCK).parseRollback("r:global t:1h u:Alice", LookupParser.Context.console()).spec(), false).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("rollback-quarantine.txt"), "An unreadable quarantine blocks planning");
        }
    }

    @Test void planIncludesEventsAdmittedImmediatelyBeforeTheRequest() throws Exception {
        try (var runtime = start(InfrastructureConfig.defaults(root))) {
            var events = new ArrayList<AuditEvent>();
            for (int i = 0; i < 300; i++) events.add(block(i, "BLOCK_PLACE", "Alice", ALICE, NOW.minusMillis(i), "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE));
            for (var event : events) PersistenceTest.submit(runtime, event);
            assertEquals(300, plan(runtime, "r:global u:Alice", false).steps().size(), "No admitted event is missed by planning");
        }
    }

    @Test void rollbackStateSharesThePersistenceWriterUnderLoad() throws Exception {
        var config = InfrastructureConfig.defaults(root);
        try (var runtime = start(config)) {
            var marked = new ArrayList<AuditEvent>();
            for (int round = 0; round < 20; round++) {
                var batch = new ArrayList<AuditEvent>();
                for (int i = 0; i < 50; i++) batch.add(block(round * 50 + i, "BLOCK_PLACE", "Alice", ALICE, NOW.minusMillis(round * 50L + i), "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE));
                var update = marked.isEmpty() ? null : runtime.setRolledBack(marked.stream().map(AuditEvent::id).toList(), true).toCompletableFuture();
                long expected = (round + 1) * 50L;
                for (var event : batch) PersistenceTest.submit(runtime, event);
                if (update != null) update.get(10, TimeUnit.SECONDS);
                PersistenceTest.await(() -> runtime.diagnostics().persisted() == expected);
                marked.addAll(batch.subList(0, 10));
            }
            var missing = runtime.setRolledBack(List.of(EventId.generate()), true).toCompletableFuture();
            var failure = assertThrows(ExecutionException.class, () -> missing.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof StorageFailure storage && storage.kind() == StorageFailure.Kind.PERMANENT_EVENT);
            persist(runtime, List.of(block(5000, "BLOCK_PLACE", "Alice", ALICE, NOW, "minecraft:stone", BlockDeltaPayload.SnapshotStatus.NONE)));
            assertEquals(ChronicleHealth.HEALTHY, runtime.health());
            assertEquals(0, runtime.diagnostics().persistenceFailures(), "No lock contention or retries");
        }
        assertEquals(190, PersistenceTest.scalar(config.database(), "SELECT count(*) FROM audit_events WHERE rolled_back=1"));
    }
}
