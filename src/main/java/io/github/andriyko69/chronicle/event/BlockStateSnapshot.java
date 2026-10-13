/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record BlockStateSnapshot(String blockId, Map<String, String> properties) {
    public static final BlockStateSnapshot AIR = new BlockStateSnapshot("minecraft:air", Map.of());
    public BlockStateSnapshot {
        EventEnvelope.text(blockId, 128);
        if (properties.size() > 32) throw new IllegalArgumentException("Too many state properties");
        var sorted = new TreeMap<String, String>();
        properties.forEach((name, value) -> sorted.put(EventEnvelope.text(name, 64), EventEnvelope.text(value, 128)));
        properties = Collections.unmodifiableMap(sorted);
    }
}
