/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.query.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LookupParserTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00.123456789Z");
    private final LookupParser parser = new LookupParser(ChronicleClock.fixed(NOW));
    private static final LookupParser.Context PLAYER = new LookupParser.Context("minecraft:overworld", new LookupSpec.Center(1, 64, -1));
    @Test void defaultsAndCompoundDurationsAreExact() {
        var defaults = parser.parse("", PLAYER);
        assertEquals(NOW.minus(Duration.ofHours(24)), defaults.since());
        assertEquals(List.of("BLOCK_BREAK", "BLOCK_PLACE", "ITEM_TAKE", "ITEM_PUT"), defaults.actions());
        assertEquals(new LookupSpec.Bounds(-4, 6, 59, 69, -6, 4), defaults.bounds());
        for (var entry : Map.of("1s", 1L, "2m", 120L, "3h", 10800L, "4d", 345600L,
                "2w", 1209600L, "1h30m", 5400L, "1w2d3h4m5s", 788645L).entrySet())
            assertEquals(NOW.minusSeconds(entry.getValue()), parser.parse("t:" + entry.getKey(), PLAYER).since());
    }
    @Test void aliasesListsAndResourceNamespaceDefaults() {
        UUID id = UUID.randomUUID();
        var spec = parser.parse("time:1h30m radius:3 user:Alice," + id + ",#fakeplayer block:diamond_ore,mod:ore action:+block world:overworld at:-1,5,10", PLAYER);
        assertEquals(List.of("minecraft:diamond_ore", "mod:ore"), spec.targets());
        assertEquals(List.of("BLOCK_PLACE"), spec.actions());
        assertEquals("Alice", spec.actors().get(0).name()); assertEquals(id, spec.actors().get(1).uuid()); assertTrue(spec.actors().get(2).fakePlayer());
        assertEquals(new LookupSpec.Bounds(-4, 2, 2, 8, 7, 13), spec.bounds());
        assertEquals("minecraft:overworld", spec.dimension());
        for (String action : List.of("-block", "break")) assertEquals(List.of("BLOCK_BREAK"), parser.parse("a:" + action, PLAYER).actions());
        for (String action : List.of("+block", "place")) assertEquals(List.of("BLOCK_PLACE"), parser.parse("a:" + action, PLAYER).actions());
        assertEquals(2, parser.parse("a:block", PLAYER).actions().size());
        assertEquals("Бот", parser.parse("u:Бот", PLAYER).actors().getFirst().name());
        assertEquals(List.of("minecraft:chest"), parser.parse("b::chest", PLAYER).targets());
    }
    @Test void consoleNeedsExplicitSpatialContextOrSelectiveGlobal() {
        var console = LookupParser.Context.console();
        for (String text : List.of("", "t:1h", "at:1,2,3", "dim:overworld", "r:global", "r:global a:block", "r:global dim:overworld"))
            assertThrows(IllegalArgumentException.class, () -> parser.parse(text, console), text);
        var local = parser.parse("dim:the_nether at:1,2,3", console);
        assertEquals("minecraft:the_nether", local.dimension()); assertNotNull(local.bounds());
        for (String text : List.of("r:global t:10m", "r:global u:Alice", "r:global b:chest")) {
            var global = parser.parse(text, console); assertNull(global.bounds()); assertNull(global.dimension());
        }
        assertNotNull(parser.parse("r:100", PLAYER).bounds());
        assertEquals(new LookupSpec.Bounds(1, 1, 64, 64, -1, -1), parser.parse("r:0", PLAYER).bounds());
    }
    @Test void malformedUnknownDuplicateOverflowAndUnboundedFiltersFailClearly() {
        for (String text : List.of("hello", "q:1", "t:", "t:0s", "t:-1h", "t:1.5h", "t:1hX30m", "t:1", "t:9999999999999999999999999999w",
                "r:-1", "r:101", "r:1.0", "r:2147483648", "r:+1", "at:1,2", "at:1,,3", "at:1,2,2147483648", "at:NaN,2,3",
                "b:Chest", "b:a:b:c", "b::", "b:chest,", "u:,Alice", "u:#unknown", "a:explode", "dim:Bad", "t:1m time:2m", "r:1 radius:2",
                "r:global", "r:global t:1h at:1,2,3", "b:" + String.join(",", Collections.nCopies(17, "stone")))) {
            var error = assertThrows(IllegalArgumentException.class, () -> parser.parse(text, PLAYER), text);
            assertFalse(error.getMessage().isBlank());
        }
        assertThrows(IllegalArgumentException.class, () -> parser.parse("x".repeat(2049), PLAYER));
    }
    @Test void spatialBoundsDoNotWrapAtIntegerEdges() {
        var spec = parser.parse("at:2147483647,-2147483648,0 r:100", PLAYER);
        assertEquals(Integer.MAX_VALUE, spec.bounds().maxX()); assertEquals(Integer.MIN_VALUE, spec.bounds().minY());
        assertEquals(2147483547, spec.bounds().minX());
    }
}
