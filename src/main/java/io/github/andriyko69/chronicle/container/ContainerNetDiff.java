/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.container;

import io.github.andriyko69.chronicle.event.ItemTransactionPayload;
import java.util.*;

public final class ContainerNetDiff {
    private ContainerNetDiff() {}
    public record Key(String itemId, String componentKey) {
        public Key { ItemTransactionPayload.resource(itemId); Objects.requireNonNull(componentKey); }
    }
    public record Stack(Key key, ItemTransactionPayload payload) {
        public Stack { Objects.requireNonNull(key); Objects.requireNonNull(payload); }
    }
    public record Delta(String eventType, String itemId, ItemTransactionPayload payload) {}

    public static Map<Key, ItemTransactionPayload> group(List<Stack> stacks) {
        var grouped = new LinkedHashMap<Key, ItemTransactionPayload>();
        for (var stack : stacks) grouped.merge(stack.key(), stack.payload(),
                (first, next) -> first.withCount(Math.addExact(first.count(), next.count())));
        return Collections.unmodifiableMap(grouped);
    }
    public static List<Delta> between(Map<Key, ItemTransactionPayload> before, Map<Key, ItemTransactionPayload> after) {
        var keys = new LinkedHashSet<>(before.keySet()); keys.addAll(after.keySet());
        var deltas = new ArrayList<Delta>();
        for (var key : keys) {
            var old = before.get(key); var current = after.get(key);
            int difference = (current == null ? 0 : current.count()) - (old == null ? 0 : old.count());
            if (difference != 0) deltas.add(new Delta(difference > 0 ? "ITEM_PUT" : "ITEM_TAKE", key.itemId(),
                    (difference > 0 ? current : old).withCount(Math.abs(difference))));
        }
        return List.copyOf(deltas);
    }
}
