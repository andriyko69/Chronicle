/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope(String worldId, int x, int y, int z, String eventType,
        String actorType, String actorId, UUID actorUuid, UUID rootActorUuid, String targetId) {
    public EventEnvelope {
        text(worldId, 128); text(eventType, 32); text(actorType, 32);
        text(actorId, 128); text(targetId, 128);
    }
    public static String text(String value, int maximum) {
        Objects.requireNonNull(value);
        int count = value.codePointCount(0, value.length());
        if (count < 1 || count > maximum || value.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new IllegalArgumentException("Invalid bounded Unicode text");
        return value;
    }
}
