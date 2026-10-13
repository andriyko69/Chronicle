/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.query;

import io.github.andriyko69.chronicle.event.*;
import java.time.Instant;

public record LookupRow(EventId id, Instant occurredAt, EventEnvelope envelope,
        BlockStateSnapshot before, BlockStateSnapshot after, BlockDeltaPayload.SnapshotStatus nbtStatus) {}
