/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle;

import io.github.andriyko69.chronicle.container.*;
import io.github.andriyko69.chronicle.event.ItemTransactionPayload;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContainerNetDiffTest {
    private static ContainerNetDiff.Stack stack(String id, int count, String key) {
        var payload = new ItemTransactionPayload(count, "minecraft:chest", null, new TreeMap<>(), null,
                ItemTransactionPayload.ComponentSnapshotStatus.NONE, null);
        return new ContainerNetDiff.Stack(new ContainerNetDiff.Key("minecraft:" + id, key), payload);
    }
    private static List<ContainerNetDiff.Delta> diff(List<ContainerNetDiff.Stack> before, List<ContainerNetDiff.Stack> after) {
        return ContainerNetDiff.between(ContainerNetDiff.group(before), ContainerNetDiff.group(after));
    }
    @Test void depositWithdrawalSwapAndNetZero() {
        var diamonds = stack("diamond", 32, "plain");
        var deposit = diff(List.of(), List.of(diamonds));
        assertEquals("ITEM_PUT", deposit.getFirst().eventType()); assertEquals(32, deposit.getFirst().payload().count());
        var withdrawal = diff(List.of(diamonds), List.of(stack("diamond", 16, "plain")));
        assertEquals("ITEM_TAKE", withdrawal.getFirst().eventType()); assertEquals(16, withdrawal.getFirst().payload().count());
        var swap = diff(List.of(diamonds), List.of(stack("diamond", 16, "plain"), stack("iron_ingot", 8, "plain")));
        assertEquals(List.of("ITEM_TAKE", "ITEM_PUT"), swap.stream().map(ContainerNetDiff.Delta::eventType).toList());
        assertEquals(List.of(16, 8), swap.stream().map(d -> d.payload().count()).toList());
        assertTrue(diff(List.of(diamonds), List.of(diamonds)).isEmpty(), "Taking and returning items has no net effect");
        assertTrue(diff(List.of(diamonds), List.of(stack("diamond", 8, "plain"), stack("diamond", 24, "plain"))).isEmpty());
        assertTrue(diff(List.of(stack("diamond", 10, "plain"), stack("iron_ingot", 8, "plain")),
                List.of(stack("iron_ingot", 8, "plain"), stack("diamond", 10, "plain"))).isEmpty());
    }
    @Test void sameItemWithDifferentNamesEnchantmentsOrComponentsCannotCancel() {
        for (String key : List.of("name:Blade", "enchantment:sharpness", "custom_data:unique")) {
            var result = diff(List.of(stack("diamond_sword", 1, "plain")), List.of(stack("diamond_sword", 1, key)));
            assertEquals(List.of("ITEM_TAKE", "ITEM_PUT"), result.stream().map(ContainerNetDiff.Delta::eventType).toList());
            assertTrue(result.stream().allMatch(d -> d.itemId().equals("minecraft:diamond_sword") && d.payload().count() == 1));
        }
    }
    @Test void snapshotsAreDetachedAndCountOverflowFailsExplicitly() {
        var input = new ArrayList<>(List.of(stack("diamond", 1, "plain")));
        var grouped = ContainerNetDiff.group(input); input.clear();
        assertEquals(1, grouped.size()); assertThrows(UnsupportedOperationException.class, grouped::clear);
        assertThrows(ArithmeticException.class, () -> ContainerNetDiff.group(List.of(stack("diamond", Integer.MAX_VALUE, "plain"), stack("diamond", 1, "plain"))));
    }
    @Test void ceilingPreservesFullIdentityAndUnknownComparisonsFailExplicitly() {
        byte[] exact = new byte[16 * 1024];
        var present = ComponentSnapshot.capture(out -> out.write(exact));
        assertEquals(ItemTransactionPayload.ComponentSnapshotStatus.PRESENT, present.status());
        exact[0] = 4; assertEquals(0, present.bytes()[0]);
        byte[] returned = present.bytes(); returned[0] = 5; assertEquals(0, present.bytes()[0]);
        var large = ComponentSnapshot.capture(out -> { out.write(new byte[20_000]); out.writeInt(1); });
        var different = ComponentSnapshot.capture(out -> { out.write(new byte[20_000]); out.writeInt(2); });
        var identical = ComponentSnapshot.capture(out -> { out.write(new byte[20_000]); out.writeInt(1); });
        assertEquals(ItemTransactionPayload.ComponentSnapshotStatus.OVERSIZED, large.status()); assertNull(large.bytes());
        assertNotEquals(large.key(), different.key()); assertEquals(large.key(), identical.key());
        assertTrue(diff(List.of(stack("stone", 1, large.key())), List.of(stack("stone", 1, identical.key()))).isEmpty());
        var failed = ComponentSnapshot.capture(out -> { throw new IOException("bad codec"); });
        assertEquals(ItemTransactionPayload.ComponentSnapshotStatus.FAILED, failed.status()); assertNull(failed.bytes());
        assertNull(failed.key());
        assertThrows(NullPointerException.class, () -> new ContainerNetDiff.Key("minecraft:paper", failed.key()));
        assertEquals(ItemTransactionPayload.ComponentSnapshotStatus.OVERSIZED,
                ComponentSnapshot.capture(out -> out.writeUTF("x".repeat(70_000))).status());
    }
}
