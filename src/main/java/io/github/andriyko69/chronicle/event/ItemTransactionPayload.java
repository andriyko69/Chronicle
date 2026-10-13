/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.event;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Component snapshots are opaque and must never be decoded on persistence or query workers. */
public record ItemTransactionPayload(int count, String containerBlockId, String customName,
        SortedMap<String, Integer> enchantments, Integer damage, ComponentSnapshotStatus componentSnapshotStatus,
        byte[] componentBytes) {
    public static final int VERSION = 1;
    public static final int MAX_COMPONENT_BYTES = 16 * 1024;
    public static final int MAX_NAME_BYTES = 1024;
    public static final int MAX_ENCHANTMENTS = 256;
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    public enum ComponentSnapshotStatus { NONE, PRESENT, OVERSIZED, FAILED }

    public ItemTransactionPayload {
        if (count <= 0) throw new IllegalArgumentException("Item count must be positive");
        resource(containerBlockId);
        if (customName != null && (!StandardCharsets.UTF_8.newEncoder().canEncode(customName)
                || customName.indexOf('\0') >= 0 || customName.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES))
            throw new IllegalArgumentException("Invalid custom name");
        Objects.requireNonNull(enchantments);
        if (enchantments.size() > MAX_ENCHANTMENTS) throw new IllegalArgumentException("Too many enchantments");
        var sorted = new TreeMap<String, Integer>();
        enchantments.forEach((id, level) -> {
            resource(id);
            if (level == null || level < 0 || level > 255) throw new IllegalArgumentException("Invalid enchantment level");
            sorted.put(id, level);
        });
        enchantments = Collections.unmodifiableSortedMap(sorted);
        if (damage != null && damage <= 0) throw new IllegalArgumentException("Damage must be positive");
        Objects.requireNonNull(componentSnapshotStatus);
        if ((componentSnapshotStatus == ComponentSnapshotStatus.PRESENT) != (componentBytes != null))
            throw new IllegalArgumentException("Component status/bytes mismatch");
        if (componentBytes != null) {
            if (componentBytes.length == 0 || componentBytes.length > MAX_COMPONENT_BYTES)
                throw new IllegalArgumentException("Component snapshot outside byte ceiling");
            componentBytes = componentBytes.clone();
        }
    }
    public static String resource(String value) {
        if (value == null || value.length() > 128 || !RESOURCE.matcher(value).matches())
            throw new IllegalArgumentException("Invalid resource location");
        return value;
    }
    public ItemTransactionPayload withCount(int value) {
        return new ItemTransactionPayload(value, containerBlockId, customName, enchantments, damage, componentSnapshotStatus, componentBytes);
    }
    @Override public byte[] componentBytes() { return componentBytes == null ? null : componentBytes.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof ItemTransactionPayload p && count == p.count && containerBlockId.equals(p.containerBlockId)
                && Objects.equals(customName, p.customName) && enchantments.equals(p.enchantments) && Objects.equals(damage, p.damage)
                && componentSnapshotStatus == p.componentSnapshotStatus && Arrays.equals(componentBytes, p.componentBytes);
    }
    @Override public int hashCode() {
        return 31 * Objects.hash(count, containerBlockId, customName, enchantments, damage, componentSnapshotStatus) + Arrays.hashCode(componentBytes);
    }
}
