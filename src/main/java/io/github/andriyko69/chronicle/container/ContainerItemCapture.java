/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import io.github.andriyko69.chronicle.event.ItemTransactionPayload;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class ContainerItemCapture {
    private ContainerItemCapture() {}
    public static ContainerNetDiff.Stack capture(ItemStack stack, String containerId, RegistryAccess registries) {
        var components = ComponentSnapshot.none();
        if (!stack.isComponentsPatchEmpty()) {
            try {
                var patch = stack.getComponentsPatch();
                // Persistent codecs omit transient components; do not treat those omitted identities as equal.
                if (patch.entrySet().stream().anyMatch(e -> e.getKey().isTransient())) components = ComponentSnapshot.failed();
                else {
                    var tag = DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), patch).getOrThrow();
                    components = ComponentSnapshot.capture(output -> {
                        output.writeByte(tag.getId()); output.writeUTF(""); writeCanonical(tag, output, 0);
                    });
                    if (components.key() == null) {
                        // NBT's modified-UTF limit must not turn unchanged oversized strings into fake take/put pairs.
                        String identity = ComponentSnapshot.fingerprint(output -> writeIdentity(tag, output, 0));
                        components = new ComponentSnapshot(components.status(), components.bytes(), "extended:" + identity);
                    }
                }
            } catch (IOException | RuntimeException failure) {
                throw new IllegalArgumentException("Unsupported item component identity; container comparison failed", failure);
            }
            if (components.key() == null)
                throw new IllegalArgumentException("Unsupported transient item components; container comparison failed");
        }
        var enchantments = new TreeMap<String, Integer>();
        addEnchantments(stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY), enchantments);
        addEnchantments(stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY), enchantments);
        var name = stack.get(DataComponents.CUSTOM_NAME);
        var payload = new ItemTransactionPayload(stack.getCount(), containerId, name == null ? null : boundedName(name.getString()),
                enchantments, stack.isDamaged() ? stack.getDamageValue() : null, components.status(), components.bytes());
        return new ContainerNetDiff.Stack(new ContainerNetDiff.Key(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), components.key()), payload);
    }
    private static void addEnchantments(ItemEnchantments value, TreeMap<String, Integer> summary) {
        for (var entry : value.entrySet()) {
            String id = entry.getKey().unwrapKey().orElseThrow(() -> new IllegalArgumentException("Unregistered enchantment")).location().toString();
            summary.merge(id, entry.getIntValue(), Math::max);
        }
    }
    private static String boundedName(String name) {
        if (name.indexOf('\0') < 0 && name.getBytes(StandardCharsets.UTF_8).length <= ItemTransactionPayload.MAX_NAME_BYTES) return name;
        var result = new StringBuilder();
        int bytes = 0;
        var points = name.codePoints().iterator();
        while (points.hasNext()) {
            int point = points.nextInt();
            if (point == 0) point = 0xFFFD;
            String character = new String(Character.toChars(point));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > ItemTransactionPayload.MAX_NAME_BYTES - 3) { result.append('…'); break; }
            bytes += size; result.append(character);
        }
        return result.toString();
    }
    private static void writeIdentity(Tag tag, DataOutput output, int depth) throws IOException {
        if (depth > Tag.MAX_DEPTH) throw new IOException("Component identity too deeply nested");
        output.writeByte(tag.getId());
        if (tag instanceof CompoundTag compound) {
            output.writeInt(compound.size());
            for (String key : new TreeSet<>(compound.getAllKeys())) {
                identityText(key, output); writeIdentity(Objects.requireNonNull(compound.get(key)), output, depth + 1);
            }
        } else if (tag instanceof ListTag list) {
            output.writeByte(list.getElementType()); output.writeInt(list.size());
            for (Tag value : list) writeIdentity(value, output, depth + 1);
        } else if (tag instanceof StringTag string) identityText(string.getAsString(), output);
        else tag.write(output);
    }
    private static void identityText(String text, DataOutput output) throws IOException {
        // Hash exact UTF-16 code units, including malformed modded strings, without NBT's 65535-byte limit.
        output.writeInt(text.length());
        for (int i = 0; i < text.length(); i++) output.writeChar(text.charAt(i));
    }
    private static void writeCanonical(Tag tag, DataOutput output, int depth) throws IOException {
        if (depth > Tag.MAX_DEPTH) throw new IOException("Component NBT too deeply nested");
        if (tag instanceof CompoundTag compound) {
            for (String key : new TreeSet<>(compound.getAllKeys())) {
                Tag value = Objects.requireNonNull(compound.get(key));
                output.writeByte(value.getId()); output.writeUTF(key); writeCanonical(value, output, depth + 1);
            }
            output.writeByte(Tag.TAG_END);
        } else if (tag instanceof ListTag list) {
            output.writeByte(list.getElementType()); output.writeInt(list.size());
            for (Tag value : list) writeCanonical(value, output, depth + 1);
        } else tag.write(output);
    }
}
