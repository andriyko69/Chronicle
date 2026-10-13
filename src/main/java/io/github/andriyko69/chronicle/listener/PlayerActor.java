/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.listener;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

public record PlayerActor(String type, String name, UUID uuid) {
    public static PlayerActor capture(Player player) {
        return new PlayerActor(player instanceof FakePlayer ? "FAKE_PLAYER" : "PLAYER",
                player.getGameProfile().getName(), player.getUUID());
    }
}
