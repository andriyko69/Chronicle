/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.event.ChronicleClock;
import io.github.andriyko69.chronicle.query.LookupParser;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurgeParserTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00.123456789Z");
    private final LookupParser parser = new LookupParser(ChronicleClock.fixed(NOW));

    @Test void ageAndFlagsInAnyOrder() {
        var plain = parser.parsePurge("t:30d");
        assertEquals(Duration.ofDays(30), plain.olderThan());
        assertEquals(NOW.minus(Duration.ofDays(30)), plain.cutoff());
        assertFalse(plain.preview()); assertFalse(plain.optimize());
        var flagged = parser.parsePurge("--optimize time:1h30m --preview");
        assertEquals(Duration.ofMinutes(90), flagged.olderThan());
        assertTrue(flagged.preview()); assertTrue(flagged.optimize());
        var bare = parser.parsePurge("preview t:2w optimize");
        assertTrue(bare.preview() && bare.optimize());
    }

    @Test void explicitAgeIsRequired() {
        for (String text : List.of("", "   ", "--preview", "--optimize --preview"))
            assertTrue(assertThrows(IllegalArgumentException.class, () -> parser.parsePurge(text)).getMessage().contains("explicit age"), text);
    }

    @Test void ageBelowOneMinuteIsRefused() {
        for (String text : List.of("t:1s", "t:59s", "t:0s", "t:0m"))
            assertThrows(IllegalArgumentException.class, () -> parser.parsePurge(text), text);
        assertEquals(LookupParser.MIN_PURGE_AGE, parser.parsePurge("t:60s").olderThan());
        assertEquals(LookupParser.MIN_PURGE_AGE, parser.parsePurge("t:1m").olderThan());
    }

    @Test void filtersDuplicatesAndMalformedAgesAreRefused() {
        for (String text : List.of("t:1d r:global", "t:1d u:Alice", "t:1d b:stone", "t:1d a:block", "t:1d at:0,0,0", "t:1d dim:overworld",
                "t:1d --force", "t:1d all", "t:1d t:2d", "t:1d time:2d", "t:1d --preview preview", "t:1d --optimize optimize",
                "t:", "t:abc", "t:1x", "t:-5m", "t:99999999999999999999w"))
            assertThrows(IllegalArgumentException.class, () -> parser.parsePurge(text), text);
        assertThrows(IllegalArgumentException.class, () -> parser.parsePurge(null));
        assertThrows(IllegalArgumentException.class, () -> parser.parsePurge("t:1d " + "x".repeat(300)));
    }
}
