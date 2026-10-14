/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.gametest;

import io.github.andriyko69.chronicle.command.ChronicleFormatter;
import io.github.andriyko69.chronicle.diagnostics.*;
import io.github.andriyko69.chronicle.event.*;
import io.github.andriyko69.chronicle.query.*;
import java.time.Instant;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("chronicle")
@PrefixGameTestTemplate(false)
public final class LookupFormatterGameTests {
    @GameTest(template = "empty")
    public static void richLookupComponentsAndStatus(GameTestHelper helper) {
        var tests = new LookupFormatterGameTests();
        tests.blockRowsKeepForensicsInHoverAndCopyOrSuggestOnClick();
        tests.agesDimensionsNamesAndAllNbtStatuses();
        tests.headersAndNavigationRespectEndsAndPageCeiling();
        tests.itemStylesAndLongNamesStayCompactWithCompleteTooltip();
        tests.statusDashboardColorsAllHealthStatesAndBacklogWithoutHidingFailures();
        tests.itemStatusesAndSanitizedNames();
        tests.causalRowsShowRootActorAndCopyItsUuid();
        helper.succeed();
    }
    private static void assertTrue(boolean value) { assertTrue(value, "Expected true"); }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertFalse(boolean value) { assertTrue(!value, "Expected false"); }
    private static void assertNull(Object value) { assertEquals(null, value); }
    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static final Instant NOW = Instant.parse("2026-10-13T22:37:28.396515337Z");
    private static final ChronicleClock CLOCK = ChronicleClock.fixed(NOW);
    private static LookupRow block(String action, long age, String actorType, UUID actorUuid, String target,
            BlockDeltaPayload.SnapshotStatus nbt) {
        return new LookupRow(EventId.generate(), NOW.minusSeconds(age),
                new EventEnvelope("minecraft:overworld", -65, 63, -43, action, actorType, "Alice", actorUuid, null, target),
                new BlockStateSnapshot("minecraft:chest", Map.of("facing", "north")), BlockStateSnapshot.AIR, nbt);
    }
    private static Component row(LookupRow row, String dimension) {
        return ChronicleFormatter.page(new LookupPage(1, List.of(row), false), CLOCK, dimension).get(1);
    }
    private static List<Component> nodes(Component root) {
        var nodes = new ArrayList<Component>(); nodes.add(root);
        root.getSiblings().forEach(child -> nodes.addAll(nodes(child)));
        return nodes;
    }
    private static Component node(Component root, String text) {
        return nodes(root).stream().filter(c -> c.getString().equals(text)).findFirst().orElseThrow();
    }
    private static void color(Component component, ChatFormatting color) {
        assertEquals(TextColor.fromLegacyFormat(color), component.getStyle().getColor());
    }
    private static void click(Component component, ClickEvent.Action action, String value) {
        assertEquals(new ClickEvent(action, value), component.getStyle().getClickEvent());
    }
    private void blockRowsKeepForensicsInHoverAndCopyOrSuggestOnClick() {
        UUID actor = UUID.randomUUID();
        var data = block("BLOCK_BREAK", 18, "FAKE_PLAYER", actor, "minecraft:chest", BlockDeltaPayload.SnapshotStatus.PRESENT);
        var row = row(data, "minecraft:overworld");
        assertEquals("18s ago - Alice [Fake] broke chest [NBT] @ -65 63 -43", row.getString());
        var time = node(row, "18s ago"); color(time, ChatFormatting.GRAY);
        click(time, ClickEvent.Action.COPY_TO_CLIPBOARD, data.id().toString());
        var actorNode = node(row, "Alice"); color(actorNode, ChatFormatting.WHITE);
        click(actorNode, ClickEvent.Action.COPY_TO_CLIPBOARD, actor.toString());
        var symbol = node(row, "-"); color(symbol, ChatFormatting.RED); assertTrue(symbol.getStyle().isBold());
        color(node(row, " [Fake]"), ChatFormatting.GOLD);
        color(node(row, " [NBT]"), ChatFormatting.DARK_AQUA);
        var location = node(row, " @ -65 63 -43");
        click(location, ClickEvent.Action.SUGGEST_COMMAND, "/execute in minecraft:overworld run tp @s -65 63 -43");
        String evidence = ChatEvidence.text(row);
        for (String value : List.of(NOW.minusSeconds(18).toString(), data.id().toString(), actor.toString(), "FAKE_PLAYER",
                "Target: minecraft:chest", "Old state: minecraft:chest[facing=north]", "New state: minecraft:air",
                "NBT snapshot: PRESENT", "minecraft:overworld (-65, 63, -43)", "Click to teleport")) assertTrue(evidence.contains(value), value);
        assertFalse(row.getString().contains(data.id().toString()));
        assertFalse(row.getString().contains("minecraft:"));
    }
    private void causalRowsShowRootActorAndCopyItsUuid() {
        UUID root = UUID.fromString("33333333-3333-4333-8333-333333333333");
        var stone = new BlockStateSnapshot("minecraft:stone", Map.of());
        var data = new LookupRow(EventId.generate(), NOW.minusSeconds(12), new EventEnvelope("minecraft:overworld", 10, 64, 20, "BLOCK_BREAK",
                "ENVIRONMENT", "#tnt", null, root, "Andriyko69", "minecraft:stone"), stone, BlockStateSnapshot.AIR, BlockDeltaPayload.SnapshotStatus.NONE);
        var row = row(data, "minecraft:overworld");
        assertEquals("12s ago - #tnt (← Andriyko69) broke stone @ 10 64 20", row.getString());
        var actor = node(row, "#tnt (← Andriyko69)");
        color(actor, ChatFormatting.WHITE); color(node(row, "Andriyko69"), ChatFormatting.YELLOW);
        click(actor, ClickEvent.Action.COPY_TO_CLIPBOARD, root.toString());
        String evidence = ChatEvidence.text(row);
        for (String value : List.of("Actor type: ENVIRONMENT", "Actor UUID: none", "Caused by: Andriyko69", "Root UUID: " + root))
            assertTrue(evidence.contains(value), value);
        var unnamed = row(new LookupRow(EventId.generate(), NOW, new EventEnvelope("minecraft:overworld", 1, 2, 3, "BLOCK_BREAK",
                "ENVIRONMENT", "#fire", null, root, "minecraft:oak_planks"), stone, BlockStateSnapshot.AIR, BlockDeltaPayload.SnapshotStatus.NONE), null);
        assertTrue(unnamed.getString().contains("- #fire (← 33333333…) broke oak_planks"), unnamed.getString());
        assertTrue(ChatEvidence.text(unnamed).contains("Caused by: unknown name"));
        var direct = row(block("BLOCK_BREAK", 0, "PLAYER", null, "minecraft:chest", BlockDeltaPayload.SnapshotStatus.NONE), null);
        assertFalse(direct.getString().contains("←"));
    }
    private void agesDimensionsNamesAndAllNbtStatuses() {
        long[] ages = {-1, 0, 59, 60, 180, 3599, 3600, 86399, 86400, 259200};
        String[] expected = {"0s", "0s", "59s", "1m", "3m", "59m", "1h", "23h", "1d", "3d"};
        for (int i = 0; i < ages.length; i++) {
            var data = block("BLOCK_PLACE", ages[i], "PLAYER", null, "mekanism:bin", BlockDeltaPayload.SnapshotStatus.NONE);
            var component = row(data, "minecraft:the_nether");
            assertTrue(component.getString().startsWith(expected[i] + " ago + Alice placed mekanism:bin"));
            assertTrue(component.getString().endsWith("@ overworld -65 63 -43"));
            click(node(component, "Alice"), ClickEvent.Action.COPY_TO_CLIPBOARD, "Alice");
            color(node(component, "+"), ChatFormatting.GREEN);
        }
        for (var status : BlockDeltaPayload.SnapshotStatus.values()) {
            var component = row(block("BLOCK_BREAK", 0, "PLAYER", null, "minecraft:chest", status), null);
            assertTrue(ChatEvidence.text(component).contains("NBT snapshot: " + status));
            if (status == BlockDeltaPayload.SnapshotStatus.NONE) assertFalse(component.getString().contains("NBT"));
            if (status == BlockDeltaPayload.SnapshotStatus.OVERSIZED || status == BlockDeltaPayload.SnapshotStatus.FAILED)
                color(node(component, " [NBT:" + status.name().toLowerCase(Locale.ROOT) + "]"), ChatFormatting.RED);
        }
    }
    private void headersAndNavigationRespectEndsAndPageCeiling() {
        var first = ChronicleFormatter.page(new LookupPage(1, List.of(), true), CLOCK);
        assertEquals("── Chronicle Lookup ── Page 1 (0 results) ──", first.getFirst().getString());
        assertTrue(ChatEvidence.text(first.getFirst()).contains("Block states are hook observations."));
        color(node(first.getFirst(), "Chronicle Lookup"), ChatFormatting.GOLD);
        assertNull(node(first.getLast(), "[◀ Prev]").getStyle().getClickEvent());
        color(node(first.getLast(), "[◀ Prev]"), ChatFormatting.DARK_GRAY);
        click(node(first.getLast(), "[Next ▶]"), ClickEvent.Action.RUN_COMMAND, "/chronicle page 2");
        var middle = ChronicleFormatter.page(new LookupPage(2, List.of(), true), CLOCK).getLast();
        click(node(middle, "[◀ Prev]"), ClickEvent.Action.RUN_COMMAND, "/chronicle page 1");
        click(node(middle, "[Next ▶]"), ClickEvent.Action.RUN_COMMAND, "/chronicle page 3");
        for (var page : List.of(new LookupPage(2, List.of(), false), new LookupPage(LookupSpec.MAX_PAGE, List.of(), true))) {
            var next = node(ChronicleFormatter.page(page, CLOCK).getLast(), "[Next ▶]");
            assertNull(next.getStyle().getClickEvent()); color(next, ChatFormatting.DARK_GRAY);
        }
        assertEquals(1, ChronicleFormatter.page(new LookupPage(1, List.of(), false), CLOCK).size());
    }
    private void itemStylesAndLongNamesStayCompactWithCompleteTooltip() {
        String name = "Blade".repeat(30);
        for (String action : List.of("ITEM_PUT", "ITEM_TAKE")) {
            var envelope = new EventEnvelope("minecraft:overworld", -65, 63, -43, action, "PLAYER", "Alice", UUID.randomUUID(), null, "mod:sword");
            var payload = new ItemTransactionPayload(12, "mod:bin", name, new TreeMap<>(Map.of("mod:power", 5)), 2,
                    ItemTransactionPayload.ComponentSnapshotStatus.OVERSIZED, null);
            var component = row(LookupRow.decode(AuditEvent.item(EventId.generate(), NOW, envelope, payload)), "minecraft:overworld");
            assertTrue(component.getString().contains("×12 mod:sword"));
            assertTrue(component.getString().contains(action.equals("ITEM_PUT") ? " in mod:bin" : " from mod:bin"));
            var renamed = nodes(component).stream().filter(c -> c.getString().startsWith(" (\"")).findFirst().orElseThrow();
            color(renamed, ChatFormatting.YELLOW); assertTrue(renamed.getStyle().isItalic());
            assertTrue(renamed.getString().contains("…"));
            color(node(component, " ✦"), ChatFormatting.LIGHT_PURPLE);
            assertTrue(ChatEvidence.text(component).contains(name));
            assertTrue(ChatEvidence.text(component).contains("Components: OVERSIZED"));
        }
    }
    private void statusDashboardColorsAllHealthStatesAndBacklogWithoutHidingFailures() {
        var metrics = new ChronicleMetrics(); metrics.schemaVersion = 3;
        metrics.persistenceFailures.set(7); metrics.queueFullDrops.set(2);
        for (var health : ChronicleHealth.values()) {
            var snapshot = metrics.snapshot(health, 4, CLOCK);
            var lines = ChronicleFormatter.status(new ChronicleStatus(snapshot, new StorageTotals(42, 3)));
            String badge = health == ChronicleHealth.FATAL_STORAGE ? "FATAL" : health.name();
            var badgeNode = node(lines.getFirst(), badge);
            color(badgeNode, switch (health) {
                case HEALTHY -> ChatFormatting.GREEN;
                case STORAGE_UNAVAILABLE, JOURNAL_UNAVAILABLE, FATAL_STORAGE -> ChatFormatting.RED;
                default -> ChatFormatting.YELLOW;
            });
            String text = lines.stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
            for (String field : List.of("schema=3", "queue=4", "persistence failures=7", "queue-full drops=2", "Database totals: persisted events=42", "DLQ=3"))
                assertTrue(text.contains(field), field);
        }
        metrics.backlog = 1;
        color(node(ChronicleFormatter.runtimeStatus(metrics.snapshot(ChronicleHealth.HEALTHY, 0, CLOCK)).getFirst(), "STORAGE_BACKLOG"), ChatFormatting.YELLOW);
    }
    private void itemStatusesAndSanitizedNames() {
        for (var status : ItemTransactionPayload.ComponentSnapshotStatus.values()) {
            var envelope = new EventEnvelope("minecraft:overworld", 1, 2, 3, "ITEM_PUT", "PLAYER", "Alice", null, null, "minecraft:diamond");
            var payload = new ItemTransactionPayload(8, "minecraft:chest", "A\n\"B§c", new TreeMap<>(), null, status,
                    status == ItemTransactionPayload.ComponentSnapshotStatus.PRESENT ? new byte[] {1} : null);
            var component = row(LookupRow.decode(AuditEvent.item(EventId.generate(), NOW, envelope, payload)), "minecraft:overworld");
            assertFalse(component.getString().contains("\n"));
            assertFalse(component.getString().contains("§"));
            assertTrue(ChatEvidence.text(component).contains("Components: " + status));
        }
    }

}
