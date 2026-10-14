/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.codec.*;
import io.github.andriyko69.chronicle.container.ContainerNetDiff;
import io.github.andriyko69.chronicle.event.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageSpillPayloadTest {
    private static ContainerNetDiff.Stack stack(String storage, String item, int count, String key) {
        var payload = new ItemTransactionPayload(count, storage, null, new TreeMap<>(), null, ItemTransactionPayload.ComponentSnapshotStatus.NONE, null);
        return new ContainerNetDiff.Stack(new ContainerNetDiff.Key(item, key), payload);
    }
    @Test void spillTakesEveryIdentityOnceWithSummedCounts() {
        var before = ContainerNetDiff.group(List.of(stack("minecraft:chest", "minecraft:diamond", 64, "none"),
                stack("minecraft:chest", "minecraft:diamond", 64, "none"), stack("minecraft:chest", "minecraft:diamond", 3, "none"),
                stack("minecraft:chest", "minecraft:diamond_sword", 1, "named"), stack("minecraft:chest", "minecraft:diamond_sword", 1, "none")));
        var spill = ContainerNetDiff.between(before, Map.of());
        assertEquals(3, spill.size());
        assertTrue(spill.stream().allMatch(d -> d.eventType().equals("ITEM_TAKE") && d.payload().containerBlockId().equals("minecraft:chest")));
        assertEquals(131, spill.getFirst().payload().count());
        assertEquals(List.of("minecraft:diamond", "minecraft:diamond_sword", "minecraft:diamond_sword"),
                spill.stream().map(ContainerNetDiff.Delta::itemId).toList());
        assertTrue(ContainerNetDiff.between(Map.of(), Map.of()).isEmpty(), "Empty storage spills nothing");
    }
    @Test void menulessInteractionsDiffSingleSlotStorage() {
        var empty = Map.<ContainerNetDiff.Key, ItemTransactionPayload>of();
        var disc = ContainerNetDiff.group(List.of(stack("minecraft:jukebox", "minecraft:music_disc_cat", 1, "none")));
        assertEquals("ITEM_PUT", ContainerNetDiff.between(empty, disc).getFirst().eventType());
        assertEquals("ITEM_TAKE", ContainerNetDiff.between(disc, empty).getFirst().eventType());
        var framed = ContainerNetDiff.group(List.of(stack("minecraft:item_frame", "minecraft:map", 1, "none")));
        assertTrue(ContainerNetDiff.between(framed, framed).isEmpty(), "Rotating a framed item is not a transaction");
    }
    @Test void storageIdsAndEnvironmentActorsRoundTripThroughJournal() throws Exception {
        var codec = new ItemTransactionPayloadCodec(); var journal = new ChronicleEventCodec();
        var root = UUID.fromString("00000000-0000-0000-0000-000000000006");
        for (String storage : List.of("minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:chiseled_bookshelf",
                "minecraft:decorated_pot", "minecraft:jukebox", "minecraft:lectern", "minecraft:campfire", "minecraft:shulker_box")) {
            var payload = stack(storage, "minecraft:book", 2, "none").payload();
            assertEquals(payload, codec.decode(ItemTransactionPayload.VERSION, codec.encode(payload)));
            var envelope = new EventEnvelope("minecraft:overworld", -4, 70, 9, "ITEM_TAKE", "ENVIRONMENT", "#tnt", null, root, "minecraft:book");
            var event = AuditEvent.item(EventId.generate(), Instant.now(), envelope, payload);
            var decoded = journal.decode(journal.encode(event));
            assertEquals(event, decoded);
            assertNull(decoded.envelope().actorUuid());
            assertEquals(root, decoded.envelope().rootActorUuid());
        }
    }
}
