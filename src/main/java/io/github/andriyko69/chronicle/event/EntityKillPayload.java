/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A killed entity, detached on the server thread. Forensic only: rollback never resurrects it. {@code ownerName} is
 * unknown (null) when the owner is offline and absent from the server's profile cache.
 */
public record EntityKillPayload(String entityTypeId, String customName, UUID ownerUuid, String ownerName, String detail) {
    public static final int VERSION = 1;
    public static final int MAX_NAME_BYTES = 1024;
    public static final int MAX_OWNER_BYTES = 128;
    public static final int MAX_DETAIL_BYTES = 256;

    public EntityKillPayload {
        ItemTransactionPayload.resource(entityTypeId);
        text(customName, MAX_NAME_BYTES);
        text(ownerName, MAX_OWNER_BYTES);
        text(detail, MAX_DETAIL_BYTES);
        if (ownerName != null && ownerUuid == null) throw new IllegalArgumentException("Owner name requires an owner UUID");
    }

    /** Clips live, player-controlled text (custom names) to the payload's bounds instead of rejecting the kill. */
    public static EntityKillPayload clipped(String entityTypeId, String customName, UUID ownerUuid, String ownerName, String detail) {
        return new EntityKillPayload(entityTypeId, clip(customName, MAX_NAME_BYTES), ownerUuid, clip(ownerName, MAX_OWNER_BYTES),
                clip(detail, MAX_DETAIL_BYTES));
    }

    static String clip(String value, int maximumBytes) {
        if (value == null) return null;
        var result = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length(); ) {
            int point = value.codePointAt(i);
            i += Character.charCount(point);
            if (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE) point = 0xFFFD;
            if (point == 0) continue;
            int size = point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
            if (bytes + size > maximumBytes) break;
            result.appendCodePoint(point); bytes += size;
        }
        return result.isEmpty() ? null : result.toString();
    }

    private static void text(String value, int maximumBytes) {
        if (value == null) return;
        if (value.isEmpty() || value.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
            throw new IllegalArgumentException("Invalid entity kill text");
    }
}
