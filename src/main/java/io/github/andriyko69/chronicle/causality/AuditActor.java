/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import io.github.andriyko69.chronicle.listener.PlayerActor;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Detached attribution for one audit row: the direct actor plus, for environmental actors, the responsible player. */
public record AuditActor(String type, String id, UUID uuid, UUID rootUuid, String rootName) {
    public static final String ENVIRONMENT = "ENVIRONMENT";

    public static AuditActor player(Player player) {
        var actor = PlayerActor.capture(player);
        return new AuditActor(actor.type(), actor.name(), actor.uuid(), null, null);
    }

    public static AuditActor environment(String id, CausalActor root) {
        return new AuditActor(ENVIRONMENT, id, null, root == null ? null : root.uuid(), root == null ? null : root.name());
    }

    /** {@code #tnt}, {@code #creeper}; modded entity types keep their namespace. */
    public static String entityId(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return "#" + (key.getNamespace().equals("minecraft") ? key.getPath() : key.toString());
    }

    public CausalActor asRoot(java.time.Instant now) {
        if (uuid != null && (type.equals("PLAYER") || type.equals("FAKE_PLAYER"))) return new CausalActor(uuid, id, now);
        return rootUuid == null ? null : new CausalActor(rootUuid, rootName, now);
    }
}
