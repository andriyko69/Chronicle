/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.command;

import io.github.andriyko69.chronicle.diagnostics.*;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.query.*;
import io.github.andriyko69.chronicle.rollback.RollbackEngine;
import io.github.andriyko69.chronicle.rollback.RollbackPlan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import static net.minecraft.ChatFormatting.*;

public final class ChronicleFormatter {
    private ChronicleFormatter() {}

    public static List<Component> page(LookupPage page, ChronicleClock clock) {
        return page(page, clock, null);
    }

    /** A null viewer dimension represents console output. */
    public static List<Component> page(LookupPage page, ChronicleClock clock, String viewerDimension) {
        var lines = new ArrayList<Component>();
        lines.add(text("── ", DARK_GRAY)
                .append(hover(text("Chronicle Lookup", GOLD), "Pages use current persisted data. Block states are hook observations."))
                .append(text(" ── ", DARK_GRAY))
                .append(text("Page " + page.number() + " (" + page.rows().size() + " results)", GRAY))
                .append(text(" ──", DARK_GRAY)));
        for (var row : page.rows()) lines.add(row(row, clock, viewerDimension));
        if (page.number() > 1 || page.hasMore()) {
            lines.add(Component.empty().append(button("[◀ Prev]", page.number() - 1, page.number() > 1))
                    .append(text(" ", GRAY))
                    .append(button("[Next ▶]", page.number() + 1, page.hasMore() && page.number() < LookupSpec.MAX_PAGE)));
        }
        return List.copyOf(lines);
    }

    public static List<Component> inspection(LookupPage page, ChronicleClock clock, String dimension, int x, int y, int z) {
        String coordinates = x + " " + y + " " + z;
        if (page.rows().isEmpty())
            return List.of(text("Chronicle: no recorded history at ", YELLOW).append(text(coordinates, WHITE)).append(text(".", YELLOW)));
        var lines = new ArrayList<Component>();
        lines.add(text("Inspecting ", GRAY).append(hover(text(coordinates, WHITE), dimension + " (" + x + ", " + y + ", " + z + ")")));
        lines.addAll(page(page, clock, dimension));
        return List.copyOf(lines);
    }

    public static Component inspector(boolean enabled) {
        if (!enabled) return text("Chronicle inspector disabled.", YELLOW);
        return text("Chronicle inspector ", GOLD).append(text("enabled", GREEN).withStyle(BOLD))
                .append(text(" — left-click a block to inspect it, right-click a container or adjacent space.", GRAY));
    }

    private static MutableComponent button(String label, int page, boolean enabled) {
        var button = text(label, enabled ? AQUA : DARK_GRAY);
        if (enabled) button.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chronicle page " + page)));
        return button;
    }

    private static Component row(LookupRow row, ChronicleClock clock, String viewerDimension) {
        var e = row.envelope();
        var time = hover(text(relativeTime(Math.max(0, Duration.between(row.occurredAt(), clock.now()).getSeconds())), GRAY),
                "Timestamp: " + row.occurredAt() + "\nEvent ID: " + row.id());
        time.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, row.id().toString())));
        boolean added = e.eventType().equals("BLOCK_PLACE") || e.eventType().equals("ITEM_PUT");
        var root = e.rootActorUuid();
        String rootName = root == null ? null : e.rootActorId() != null ? safeName(e.rootActorId()) : root.toString().substring(0, 8) + "…";
        var actor = text(safeName(e.actorId()), WHITE);
        if (root != null) actor.append(text(" (← ", DARK_GRAY)).append(text(rootName, YELLOW)).append(text(")", DARK_GRAY));
        hover(actor, "Actor type: " + e.actorType() + "\nActor UUID: " + (e.actorUuid() == null ? "none" : e.actorUuid())
                + (root == null ? "" : "\nCaused by: " + (e.rootActorId() == null ? "unknown name" : safeName(e.rootActorId()))
                        + "\nRoot UUID: " + root + "\nClick to copy root UUID"));
        // The responsible player is what an investigator pivots on, so the copy target follows the root actor.
        String copied = root != null ? root.toString() : e.actorUuid() == null ? e.actorId() : e.actorUuid().toString();
        actor.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copied)));
        var line = Component.empty().append(time).append(text(" ", GRAY))
                .append(text(added ? "+" : "-", added ? GREEN : RED).withStyle(BOLD))
                .append(text(" ", GRAY)).append(actor);
        if (e.actorType().equals("FAKE_PLAYER")) line.append(text(" [Fake]", GOLD));
        String status = row.rolledBack() ? "\nStatus: Rolled back" : "";
        if (row.item() == null) {
            line.append(struck(text(added ? " placed " : " broke ", GRAY), row));
            var target = struck(text(shortId(e.targetId()), AQUA), row);
            if (row.nbtStatus() != BlockDeltaPayload.SnapshotStatus.NONE) {
                target.append(text(row.nbtStatus() == BlockDeltaPayload.SnapshotStatus.PRESENT ? " [NBT]"
                        : " [NBT:" + row.nbtStatus().name().toLowerCase(Locale.ROOT) + "]",
                        row.nbtStatus() == BlockDeltaPayload.SnapshotStatus.PRESENT ? DARK_AQUA : RED));
            }
            line.append(hover(target, "Target: " + e.targetId() + "\nOld state: " + state(row.before())
                    + "\nNew state: " + state(row.after()) + "\nNBT snapshot: " + row.nbtStatus() + status));
        } else {
            var item = row.item();
            line.append(struck(text(added ? " put " : " took ", GRAY), row));
            var target = struck(text("×" + item.count() + " " + shortId(e.targetId()), AQUA), row);
            if (item.customName() != null) target.append(text(" (\"" + compactName(item.customName()) + "\")", YELLOW).withStyle(ITALIC));
            if (!item.enchantments().isEmpty() || item.damage() != null) target.append(text(" ✦", LIGHT_PURPLE));
            line.append(hover(target, "Item: " + e.targetId() + "\nCount: " + item.count() + "\nContainer: " + item.containerBlockId()
                    + "\nCustom name: " + (item.customName() == null ? "none" : safeName(item.customName()))
                    + "\nEnchantments: " + item.enchantments() + "\nDamage: " + (item.damage() == null ? "none" : item.damage())
                    + "\nComponents: " + item.componentSnapshotStatus() + (item.slot() == null ? "" : "\nSlot: " + item.slot()) + status));
            line.append(text(added ? " in " : " from ", GRAY)).append(text(shortId(item.containerBlockId()), WHITE));
        }
        if (row.rolledBack()) line.append(hover(text(" [RB]", DARK_GRAY), "Status: Rolled back\nRestore with /chronicle restore"));
        String coordinates = e.x() + " " + e.y() + " " + e.z();
        var location = hover(text(" @ ", DARK_GRAY).append(text(
                (e.worldId().equals(viewerDimension) ? "" : shortId(e.worldId()) + " ") + coordinates, GRAY)),
                e.worldId() + " (" + e.x() + ", " + e.y() + ", " + e.z() + ")\nClick to teleport");
        location.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                "/execute in " + e.worldId() + " run tp @s " + coordinates)));
        return line.append(location);
    }

    private static MutableComponent struck(MutableComponent text, LookupRow row) {
        return row.rolledBack() ? text.withStyle(STRIKETHROUGH) : text;
    }

    public static List<Component> rollbackPreview(RollbackPlan plan) {
        String verb = plan.restore() ? "restore" : "roll back";
        if (plan.steps().isEmpty()) return withQuarantine(plan, nothingTo(plan.restore()));
        return withQuarantine(plan, text("── Chronicle " + (plan.restore() ? "Restore" : "Rollback") + " Preview ──", GOLD),
                text("Would " + verb + " ", GRAY).append(text(plan.blockChanges() + " block changes", AQUA)).append(text(" and ", GRAY))
                        .append(text(plan.itemChanges() + " item changes", AQUA)).append(text(".", GRAY)),
                hover(text(plan.spillRows() + " container spill rows travel with their breaks.", GRAY),
                        "A rolled-back break restores its saved contents once;\nspill rows are only re-inserted when no snapshot was restored."),
                text("Nothing was changed. Run again without --preview to apply.", YELLOW));
    }

    /** Matching rows an interrupted rollback may already have applied are never planned until an operator releases them. */
    public static List<Component> withQuarantine(RollbackPlan plan, Component... lines) {
        var result = new ArrayList<>(List.of(lines));
        if (plan.quarantined() > 0) result.add(hover(text(plan.quarantined() + " matching changes are quarantined and were left out.", RED),
                "An earlier rollback applied them but could not record it.\nReview them, then delete rollback-quarantine.txt\nnext to Chronicle's database to release them."));
        return List.copyOf(result);
    }

    public static Component nothingTo(boolean restore) {
        return text("Chronicle: no matching changes to " + (restore ? "restore" : "roll back") + ".", YELLOW);
    }

    public static Component rollbackStarted(RollbackPlan plan) {
        return text((plan.restore() ? "Restoring " : "Rolling back ") + plan.operations() + " changes", GOLD)
                .append(text(" (" + plan.blockChanges() + " blocks, " + plan.itemChanges() + " items) at up to "
                        + RollbackEngine.OPERATIONS_PER_TICK + " per tick…", GRAY));
    }

    public static List<Component> rollbackReport(RollbackEngine.Report report) {
        String kind = report.restore() ? "Restore" : "Rollback";
        var lines = new ArrayList<Component>();
        lines.add(report.failure() == null ? text("── Chronicle " + kind + " complete ──", GOLD)
                : text("── Chronicle " + kind + " stopped: " + report.failure() + " ──", RED));
        lines.add(metrics("Applied", "blocks", report.blocks(), "items", report.items(), "spill rows covered by snapshots", report.covered()));
        if (report.emptied() > 0)
            lines.add(hover(text("Emptied " + report.emptied() + " replaced containers of items this request did not cover", YELLOW),
                    "Their remaining contents were cleared, not dropped; see the server log for positions."));
        if (report.skippedTotal() > 0) {
            String reasons = report.skipped().entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining("\n"));
            lines.add(hover(text("Skipped " + report.skippedTotal() + " changes (left as they are; hover for reasons)", YELLOW), reasons));
        }
        return List.copyOf(lines);
    }

    private static String relativeTime(long seconds) {
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return seconds / 60 + "m ago";
        if (seconds < 86400) return seconds / 3600 + "h ago";
        return seconds / 86400 + "d ago";
    }
    private static String shortId(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }
    private static String compactName(String name) {
        String safe = safeName(name);
        int length = safe.codePointCount(0, safe.length());
        return length <= 24 ? safe : safe.substring(0, safe.offsetByCodePoints(0, 24)) + "…";
    }
    private static String safeName(String name) {
        var result = new StringBuilder();
        name.codePoints().forEach(point -> {
            if (Character.isISOControl(point) || point == 0x00A7 || point == 0x2028 || point == 0x2029) result.append(' ');
            else {
                if (point == '"' || point == '\\') result.append('\\');
                result.appendCodePoint(point);
            }
        });
        return result.toString();
    }
    private static String state(BlockStateSnapshot state) {
        return state.blockId() + (state.properties().isEmpty() ? "" : state.properties().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(",", "[", "]")));
    }
    private static MutableComponent text(String text, ChatFormatting color) { return Component.literal(text).withStyle(color); }
    private static MutableComponent hover(MutableComponent text, String tooltip) {
        return text.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(tooltip))));
    }
    private static Component metrics(String section, Object... pairs) {
        var line = text(section + ": ", GOLD);
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) line.append(text(" · ", DARK_GRAY));
            line.append(text(pairs[i] + "=", WHITE)).append(text(String.valueOf(pairs[i + 1]), AQUA));
        }
        return line;
    }
    public static List<Component> status(ChronicleStatus status) {
        var lines = new ArrayList<>(runtimeStatus(status.runtime()));
        lines.add(metrics("Database totals", "persisted events", status.storage().persistedEvents(), "DLQ", status.storage().deadLetters()));
        return List.copyOf(lines);
    }
    public static List<Component> runtimeStatus(ChronicleMetrics.Snapshot s) {
        String health = s.health() == ChronicleHealth.FATAL_STORAGE ? "FATAL" : s.health().name();
        if (s.health() == ChronicleHealth.HEALTHY && s.backlog() > 0) health = "STORAGE_BACKLOG";
        ChatFormatting color = switch (s.health()) {
            case HEALTHY -> s.backlog() > 0 ? YELLOW : GREEN;
            case STORAGE_UNAVAILABLE, JOURNAL_UNAVAILABLE, FATAL_STORAGE -> RED;
            default -> YELLOW;
        };
        return List.of(text("── Chronicle Status ── ", GOLD).append(text(health, color).withStyle(BOLD))
                        .append(text(" · schema=", WHITE)).append(text(Integer.toString(s.schemaVersion()), AQUA)),
                metrics("Ingress", "queue", s.ingressDepth(), "journal backlog", s.backlog(), "allocated bytes", s.journalDiskBytes()),
                metrics("Runtime", "persisted events", s.persisted(), "DLQ", s.deadLetters(), "retries", s.retries()),
                metrics("Failures", "persistence failures", s.persistenceFailures(), "dropped", s.droppedEvents(), "queue-full drops", s.queueFullDrops()),
                metrics("Durability", "Last flush", s.lastFlush(), "last commit", s.lastCommit()));
    }
}
