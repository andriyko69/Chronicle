/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.andriyko69.chronicle.causality;

import com.mojang.authlib.GameProfile;
import io.github.andriyko69.chronicle.event.EntityKillPayload;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;

/** Which deaths are worth a row, and their detached description; server thread only. */
public final class EntityKillCapture {
    /** Survives saves in NeoForge's persistent data, so a farm animal stays audited after its love or leash ends. */
    public static final String DOMESTICATED = "chronicle:domesticated";
    public enum Scope { NEVER, ALWAYS, IF_PLAYER_CAUSED }

    private EntityKillCapture() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityKillCapture::bred);
    }

    /** Breeding parents and the child they produced are farm animals from then on. */
    public static void bred(BabyEntitySpawnEvent event) {
        if (event.isCanceled() || !(event.getParentA().level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        for (Entity entity : new Entity[]{event.getParentA(), event.getParentB(), event.getChild()})
            if (entity != null) stamp(entity);
    }

    /**
     * A player's successful interaction (feeding, leashing, shearing, milking, saddling, naming) marks a non-hostile
     * mob as domesticated. Called after vanilla decided the result: NeoForge's interact event fires before it.
     */
    public static void interacted(Player player, Entity entity, InteractionResult result) {
        if (!result.consumesAction() || !(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !(entity instanceof Mob) || entity instanceof Enemy && !(entity instanceof Animal)) return;
        stamp(entity);
    }

    static void stamp(Entity entity) { entity.getPersistentData().putBoolean(DOMESTICATED, true); }

    /**
     * Farm animals, pets, villagers and other player-invested entities only, so neither mob grinders nor wildlife
     * dying in the wilderness flood the log. Naturally spawned iron golems (village and iron farm golems) are recorded
     * only when a player caused their death.
     */
    public static Scope scope(LivingEntity entity) {
        if (entity instanceof Player || entity instanceof ArmorStand) return Scope.NEVER;
        if (entity instanceof AbstractVillager || entity instanceof Allay || entity instanceof SnowGolem
                || entity instanceof WitherBoss || entity instanceof EnderDragon || entity instanceof Warden || entity instanceof ElderGuardian
                || domesticated(entity) || equippedByPlayer(entity))
            return Scope.ALWAYS;
        if (entity instanceof IronGolem golem) return golem.isPlayerCreated() ? Scope.ALWAYS : Scope.IF_PLAYER_CAUSED;
        return Scope.NEVER;
    }

    /** Marked by Chronicle, or showing a player's involvement vanilla still records (including from before the mark existed). */
    static boolean domesticated(LivingEntity entity) {
        return entity.getPersistentData().getBoolean(DOMESTICATED) || entity.hasCustomName()
                || entity instanceof Leashable leashable && leashable.getLeashHolder() != null
                || entity instanceof Bucketable bucketable && bucketable.fromBucket()
                || entity instanceof OwnableEntity owned && owned.getOwnerUUID() != null
                || entity instanceof TamableAnimal pet && pet.isTame()
                || entity instanceof AbstractHorse horse && horse.isTamed()
                || entity instanceof Saddleable saddleable && saddleable.isSaddled()
                || entity instanceof Animal animal && (animal.isInLove() || animal.getLoveCause() != null);
    }

    /** Picking up an item makes a mob persistent; naturally spawned gear does not. */
    private static boolean equippedByPlayer(LivingEntity entity) {
        if (!(entity instanceof Mob mob) || !mob.isPersistenceRequired()) return false;
        for (var slot : EquipmentSlot.values()) if (!mob.getItemBySlot(slot).isEmpty()) return true;
        return false;
    }

    public static EntityKillPayload capture(ServerLevel level, LivingEntity entity) {
        var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        var name = entity.hasCustomName() ? entity.getCustomName().getString() : null;
        UUID owner = entity instanceof OwnableEntity owned ? owned.getOwnerUUID() : null;
        return EntityKillPayload.clipped(type, name, owner, owner == null ? null : playerName(level, owner), detail(entity));
    }

    /** Online players first, then the server's in-memory profile cache; null when neither knows the UUID. */
    static String playerName(ServerLevel level, UUID uuid) {
        var server = level.getServer();
        var online = server.getPlayerList().getPlayer(uuid);
        if (online != null) return online.getGameProfile().getName();
        var cache = server.getProfileCache();
        return cache == null ? null : cache.get(uuid).map(GameProfile::getName).orElse(null);
    }

    private static String detail(LivingEntity entity) {
        var parts = new ArrayList<String>();
        if (entity instanceof VillagerDataHolder villager) {
            var data = villager.getVillagerData();
            if (data.getProfession() != VillagerProfession.NONE)
                parts.add(BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()).getPath() + " (Lv." + data.getLevel() + ")");
        }
        if (entity instanceof TamableAnimal pet && pet.isTame()) {
            if (pet instanceof Wolf wolf) parts.add("collar: " + wolf.getCollarColor().getName());
            else if (pet instanceof Cat cat) parts.add("collar: " + cat.getCollarColor().getName());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }
}
