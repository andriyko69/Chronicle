/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.codec.BlockDeltaPayloadCodec;
import io.github.andriyko69.chronicle.codec.EntityKillPayloadCodec;
import io.github.andriyko69.chronicle.codec.ItemTransactionPayloadCodec;
import java.time.Instant;

public record LookupRow(EventId id, Instant occurredAt, EventEnvelope envelope,
        BlockStateSnapshot before, BlockStateSnapshot after, BlockDeltaPayload.SnapshotStatus nbtStatus, ItemTransactionPayload item,
        boolean rolledBack, EntityKillPayload kill) {
    public LookupRow(EventId id, Instant occurredAt, EventEnvelope envelope, BlockStateSnapshot before, BlockStateSnapshot after,
            BlockDeltaPayload.SnapshotStatus nbtStatus, ItemTransactionPayload item, boolean rolledBack) {
        this(id, occurredAt, envelope, before, after, nbtStatus, item, rolledBack, null);
    }
    public LookupRow(EventId id, Instant occurredAt, EventEnvelope envelope,
            BlockStateSnapshot before, BlockStateSnapshot after, BlockDeltaPayload.SnapshotStatus nbtStatus, ItemTransactionPayload item) {
        this(id, occurredAt, envelope, before, after, nbtStatus, item, false);
    }
    public LookupRow(EventId id, Instant occurredAt, EventEnvelope envelope,
            BlockStateSnapshot before, BlockStateSnapshot after, BlockDeltaPayload.SnapshotStatus nbtStatus) {
        this(id, occurredAt, envelope, before, after, nbtStatus, null);
    }
    public static LookupRow decode(AuditEvent event) { return decode(event, false); }
    public static LookupRow decode(AuditEvent event, boolean rolledBack) {
        if (event.envelope().eventType().equals(AuditEvent.ENTITY_KILL)) {
            var kill = new EntityKillPayloadCodec().decode(event.payloadVersion(), event.payload());
            return new LookupRow(event.id(), event.occurredAt(), event.envelope(), null, null, BlockDeltaPayload.SnapshotStatus.NONE, null, rolledBack, kill);
        }
        if (event.envelope().eventType().equals("ITEM_TAKE") || event.envelope().eventType().equals("ITEM_PUT")) {
            var item = new ItemTransactionPayloadCodec().decode(event.payloadVersion(), event.payload());
            return new LookupRow(event.id(), event.occurredAt(), event.envelope(), null, null, BlockDeltaPayload.SnapshotStatus.NONE, item, rolledBack);
        }
        var block = new BlockDeltaPayloadCodec().decode(event.payloadVersion(), event.payload());
        return new LookupRow(event.id(), event.occurredAt(), event.envelope(), block.before(), block.after(), block.snapshotStatus(), null, rolledBack);
    }
}
