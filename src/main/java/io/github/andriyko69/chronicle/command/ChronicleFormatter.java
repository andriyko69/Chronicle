/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.command;

import io.github.andriyko69.chronicle.diagnostics.*;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.query.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ChronicleFormatter {
    private ChronicleFormatter() {}
    public static List<String> page(LookupPage page, ChronicleClock clock) {
        var lines = new ArrayList<String>();
        lines.add("Chronicle lookup — page " + page.number() + " (" + page.rows().size() + " results"
                + (page.hasMore() ? page.number() == LookupSpec.MAX_PAGE ? "; page limit reached" : "; more: /ch page " + (page.number() + 1) : "; end")
                + "). Pages use current persisted data. Block states are hook observations.");
        for (var row : page.rows()) {
            var e = row.envelope();
            long age = Math.max(0, Duration.between(row.occurredAt(), clock.now()).getSeconds());
            String nbt = row.nbtStatus() == BlockDeltaPayload.SnapshotStatus.NONE ? ""
                    : " [NBT: " + row.nbtStatus().name().toLowerCase(Locale.ROOT) + "]";
            lines.add(row.id() + " | " + row.occurredAt() + " (" + age + "s ago) | "
                    + e.worldId() + " (" + e.x() + ", " + e.y() + ", " + e.z() + ") | " + e.eventType()
                    + " | " + e.actorId() + " [" + e.actorType() + ", " + (e.actorUuid() == null ? "no UUID" : e.actorUuid()) + "]"
                    + " | " + e.targetId() + " | " + state(row.before()) + " → " + state(row.after()) + nbt);
        }
        return List.copyOf(lines);
    }
    private static String state(BlockStateSnapshot state) {
        return state.blockId() + (state.properties().isEmpty() ? "" : state.properties().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(",", "[", "]")));
    }
    public static List<String> status(ChronicleStatus status) {
        var lines = new ArrayList<>(runtimeStatus(status.runtime()));
        lines.add("Database totals: persisted events=" + status.storage().persistedEvents() + ", DLQ=" + status.storage().deadLetters());
        return List.copyOf(lines);
    }
    public static List<String> runtimeStatus(ChronicleMetrics.Snapshot s) {
        return List.of("Chronicle status: health=" + s.health() + ", schema=" + s.schemaVersion(),
                "Ingress queue=" + s.ingressDepth() + ", journal backlog=" + s.backlog() + ", allocated bytes=" + s.journalDiskBytes(),
                "Runtime: persisted events=" + s.persisted() + ", DLQ=" + s.deadLetters() + ", retries=" + s.retries()
                        + ", persistence failures=" + s.persistenceFailures() + ", dropped=" + s.droppedEvents() + ", queue-full drops=" + s.queueFullDrops(),
                "Last flush=" + s.lastFlush() + ", last commit=" + s.lastCommit());
    }
}
