package com.enchantmentcracker.game;

import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.SimpleRandom;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.management.PlayerList;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads the player's RNG straight out of your own world.
 *
 * <p>In singleplayer the "server" is running inside the same process, so the
 * {@code java.util.Random} that decides your enchantments is one object reference away.
 * That makes the seed exact and self-correcting — no cracking, no drift, no counting
 * dropped items.
 *
 * <p>This only works on your own world. On a real server the RNG lives on another
 * machine, and the mod falls back to the XP-seed technique
 * ({@link com.enchantmentcracker.core.PlayerSeed#solve}) or the brute-force cracker.
 */
public final class ServerRng {

    private static Field entityRandom;
    private static Field playerXpSeed;
    private static Field randomSeed;
    private static boolean initialised;
    private static boolean fieldsResolved;
    private static String failureReason;

    private ServerRng() {
    }

    /** True when we can read the world's RNG, i.e. singleplayer and reflection is allowed. */
    public static boolean isAvailable() {
        return resolveFields() && Mc.isSingleplayer() && serverPlayer() != null;
    }

    public static String getFailureReason() {
        return failureReason;
    }

    /**
     * @return the player's live 48-bit RNG state, using the same convention as the rest of
     *         the mod (the state right after the last value was generated), or
     *         {@link PlayerSeed#UNKNOWN}.
     */
    public static long readPlayerSeed() {
        PlayerEntity player = serverPlayer();
        if (player == null || !resolveFields()) {
            return PlayerSeed.UNKNOWN;
        }
        try {
            Random random = (Random) entityRandom.get(player);
            AtomicLong seed = (AtomicLong) randomSeed.get(random);
            return seed.get() & SimpleRandom.MASK;
        } catch (Throwable t) {
            failureReason = t.toString();
            return PlayerSeed.UNKNOWN;
        }
    }

    /**
     * @return the XP seed the player currently has stored (the snapshot the enchanting
     *         table screen is driven by), or null.
     */
    public static Integer readXpSeed() {
        PlayerEntity player = serverPlayer();
        if (player == null || !resolveFields()) {
            return null;
        }
        try {
            return playerXpSeed.getInt(player);
        } catch (Throwable t) {
            failureReason = t.toString();
            return null;
        }
    }

    /**
     * The server side's copy of whatever container the player has open, in your own world
     * (singleplayer, or hosting a LAN game). Null anywhere else.
     */
    public static net.minecraft.inventory.container.Container serverOpenContainer() {
        PlayerEntity player = serverPlayer();
        return player == null ? null : player.field_71070_bA; // player.openContainer
    }

    private static PlayerEntity serverPlayer() {
        IntegratedServer server = Mc.integratedServer();
        ClientPlayerEntity clientPlayer = Mc.player();
        if (server == null || clientPlayer == null) {
            return null;
        }
        PlayerList players = server.func_184103_al(); // server.getPlayerList()
        if (players == null) {
            return null;
        }
        // playerList.getPlayerByUUID(player.getUniqueID())
        return players.func_177451_a(clientPlayer.func_110124_au());
    }

    private static boolean resolveFields() {
        if (initialised) {
            return fieldsResolved;
        }
        initialised = true;
        try {
            entityRandom = Entity.class.getDeclaredField("field_70146_Z"); // Entity.rand
            entityRandom.setAccessible(true);
            playerXpSeed = PlayerEntity.class.getDeclaredField("field_175152_f"); // PlayerEntity.xpSeed
            playerXpSeed.setAccessible(true);
            randomSeed = Random.class.getDeclaredField("seed");
            randomSeed.setAccessible(true);
            fieldsResolved = true;
            return true;
        } catch (Throwable t) {
            // Minecraft 1.16.5 runs on Java 8, where this is allowed. On a newer JVM the
            // module system may refuse to open java.util.Random; the mod then falls back
            // to solving the seed from XP seeds, which needs no reflection at all.
            failureReason = t.toString();
            entityRandom = null;
            playerXpSeed = null;
            randomSeed = null;
            fieldsResolved = false;
            return false;
        }
    }
}
