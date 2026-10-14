/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** {@code rootActorId} is the root player's name at event time, so lookups can resolve names without live server state. */
public record EventEnvelope(String worldId, int x, int y, int z, String eventType,
        String actorType, String actorId, UUID actorUuid, UUID rootActorUuid, String rootActorId, String targetId) {
    public EventEnvelope {
        text(worldId, 128); text(eventType, 32); text(actorType, 32);
        text(actorId, 128); text(targetId, 128);
        if (rootActorId != null) {
            if (rootActorUuid == null) throw new IllegalArgumentException("Root actor name requires a root actor UUID");
            text(rootActorId, 128);
        }
    }
    public EventEnvelope(String worldId, int x, int y, int z, String eventType,
            String actorType, String actorId, UUID actorUuid, UUID rootActorUuid, String targetId) {
        this(worldId, x, y, z, eventType, actorType, actorId, actorUuid, rootActorUuid, null, targetId);
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
