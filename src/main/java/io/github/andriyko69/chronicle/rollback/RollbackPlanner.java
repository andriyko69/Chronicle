/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.rollback;

import io.github.andriyko69.chronicle.codec.BlockDeltaPayloadCodec;
import io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.persistence.StorageFailure;
import io.github.andriyko69.chronicle.query.LookupSpec;
import io.github.andriyko69.chronicle.storage.StorageReader;
import java.time.Instant;
import java.util.*;

/** Runs on the query worker against detached rows only. */
public final class RollbackPlanner {
    public static final int MAX_CHANGES = 100_000;
    /** Page size of the forward scan; the scan itself always runs until the spill batch provably ends. */
    static final int SPILL_PAGE = 256;

    public static final class Rejected extends RuntimeException {
        public Rejected(String message) { super(message); }
    }

    private RollbackPlanner() {}

    /**
     * Rollback selects changes not yet rolled back, newest first; restore selects rolled-back changes, oldest first.
     * {@code quarantined} rows, and breaks whose spills are quarantined, are never planned: their world change may already
     * have been applied without its state being recorded. Entity kills are forensic only and never selected.
     */
    public static RollbackPlan plan(StorageReader reader, LookupSpec spec, boolean restore, Set<EventId> quarantined) throws StorageFailure {
        var reversible = spec.actions().stream().filter(RollbackPlanner::reversible).toList();
        if (reversible.isEmpty()) return new RollbackPlan(restore, List.of(), 0);
        var found = reader.candidates(new LookupSpec(spec.since(), spec.dimension(), spec.bounds(), spec.actors(), spec.targets(), reversible),
                restore, MAX_CHANGES + 1).stream().filter(candidate -> reversible(candidate.event().envelope().eventType())).toList();
        if (found.size() > MAX_CHANGES) throw new Rejected("More than " + MAX_CHANGES + " matching changes; narrow the filters");
        var claimed = new HashSet<EventId>();
        var held = new HashSet<EventId>();
        var companions = new HashMap<EventId, List<AuditEvent>>();
        for (var candidate : found) {
            var event = candidate.event();
            if (!event.envelope().eventType().equals("BLOCK_BREAK") || claimed.contains(event.id())) continue;
            var spills = spills(reader, event, restore).stream().filter(spill -> !claimed.contains(spill.id())).toList();
            if (spills.isEmpty()) continue;
            companions.put(event.id(), spills);
            spills.forEach(spill -> claimed.add(spill.id()));
            if (spills.stream().anyMatch(spill -> quarantined.contains(spill.id()))) held.add(event.id());
        }
        var steps = new ArrayList<RollbackPlan.Step>();
        int excluded = 0;
        for (var candidate : found) {
            var event = candidate.event();
            // A spill travels with its break even when the user's filters selected it on its own.
            if (claimed.contains(event.id())) continue;
            if (quarantined.contains(event.id()) || held.contains(event.id())) { excluded++; continue; }
            steps.add(new RollbackPlan.Step(event, companions.getOrDefault(event.id(), List.of())));
        }
        return new RollbackPlan(restore, steps, excluded);
    }

    /**
     * The ITEM_TAKE rows recording contents released by {@code breakEvent}: the first matching rows after it at its
     * position (same actor, the broken container, the same rollback state), all stamped with the one instant at which the
     * removal settled. Storage audit settles a removal before any later block row there, so the scan stops at the next
     * block change: a replacement's ordinary takes are never this break's spill, however long the server stalled.
     * Execution additionally verifies covered spills against the restored contents.
     */
    static List<AuditEvent> spills(StorageReader reader, AuditEvent breakEvent, boolean rolledBack) throws StorageFailure {
        var removed = new BlockDeltaPayloadCodec().decode(breakEvent.payloadVersion(), breakEvent.payload());
        // Only blocks with a block entity can spill, and those always carry a snapshot status.
        if (removed.snapshotStatus() == BlockDeltaPayload.SnapshotStatus.NONE) return List.of();
        var envelope = breakEvent.envelope();
        var start = breakEvent.occurredAt();
        var spills = new ArrayList<AuditEvent>();
        var items = new ItemTransactionPayloadCodec();
        Instant settled = null;
        for (int offset = 0; ; offset += SPILL_PAGE) {
            var page = reader.position(envelope.worldId(), envelope.x(), envelope.y(), envelope.z(), start, SPILL_PAGE, offset);
            for (var stored : page) {
                var event = stored.event();
                var other = event.envelope();
                if (event.id().equals(breakEvent.id())) continue;
                if (block(event) && event.occurredAt().isAfter(start)) return List.copyOf(spills);
                if (!other.eventType().equals("ITEM_TAKE") || !sameActor(envelope, other)
                        || !items.decode(event.payloadVersion(), event.payload()).containerBlockId().equals(removed.before().blockId())) continue;
                if (settled == null) settled = event.occurredAt();
                else if (!settled.equals(event.occurredAt())) return List.copyOf(spills);
                if (stored.rolledBack() == rolledBack) spills.add(event);
            }
            if (page.size() < SPILL_PAGE) return List.copyOf(spills);
        }
    }

    private static boolean sameActor(EventEnvelope a, EventEnvelope b) {
        return a.actorType().equals(b.actorType()) && a.actorId().equals(b.actorId()) && Objects.equals(a.actorUuid(), b.actorUuid())
                && Objects.equals(a.rootActorUuid(), b.rootActorUuid());
    }

    static boolean reversible(String eventType) {
        return switch (eventType) {
            case "BLOCK_BREAK", "BLOCK_PLACE", "ITEM_TAKE", "ITEM_PUT" -> true;
            default -> false;
        };
    }

    static boolean block(AuditEvent event) {
        var type = event.envelope().eventType();
        return type.equals("BLOCK_BREAK") || type.equals("BLOCK_PLACE");
    }
}
