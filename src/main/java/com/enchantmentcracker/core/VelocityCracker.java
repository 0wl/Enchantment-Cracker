package com.enchantmentcracker.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Recovers the player's 48-bit RNG state from the velocity of a thrown item, the way
 * clientcommands does, so a server seed can be locked without spending a second enchantment.
 *
 * <p>The technique the rest of the mod uses needs two enchantments: the first XP seed gives
 * the top 32 bits of the state, the second pins the low 16. That second enchantment costs a
 * level and a lapis every time. This does the same job with a thrown item instead: a dropped
 * stack spends four {@code nextFloat} calls, and those four floats are baked into the item's
 * launch velocity, which the server sends to the client. Given the 32 bits already known from
 * one enchantment, only 2^16 candidates remain, and the throw's velocity picks out the one.
 *
 * <p>Reproduces {@code PlayerEntity.dropItem(stack, false, true)} — the in-world "press Q"
 * drop — from Minecraft 1.16.5 exactly, {@code MathHelper}'s sine table included, so the
 * predicted velocity matches the server's to the bit before it is quantised for the network.
 */
public final class VelocityCracker {

    /** How the server clamps each velocity component before packing it into the packet. */
    private static final double MAX_PACKED = 3.9;
    private static final double PACK_SCALE = 8000.0;

    private VelocityCracker() {
    }

    // ------------------------------------------------------------- MathHelper's sine table

    /** A faithful copy of {@code MathHelper.SIN_TABLE}, so the look-direction terms match. */
    private static final float[] SIN_TABLE = new float[65536];

    static {
        for (int i = 0; i < 65536; i++) {
            SIN_TABLE[i] = (float) Math.sin(i * Math.PI * 2.0 / 65536.0);
        }
    }

    /** {@code MathHelper.sin} */
    static float sin(float value) {
        return SIN_TABLE[(int) (value * 10430.378F) & 65535];
    }

    /** {@code MathHelper.cos} */
    static float cos(float value) {
        return SIN_TABLE[(int) (value * 10430.378F + 16384.0F) & 65535];
    }

    // ------------------------------------------------------------------ forward model

    private static final float DEG_TO_RAD = (float) Math.PI / 180F;
    private static final float TWO_PI = (float) Math.PI * 2F;

    /** Item velocities are three doubles. */
    public static final class Velocity {
        public final double x;
        public final double y;
        public final double z;

        public Velocity(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * The exact velocity {@code PlayerEntity.dropItem(stack, false, true)} produces when the
     * player's RNG is at {@code preDropState} and they face {@code yaw}/{@code pitch}. The four
     * {@code nextFloat} calls are taken from the four states after {@code preDropState}.
     */
    public static Velocity velocityFor(long preDropState, float yaw, float pitch) {
        long s1 = PlayerSeed.next(preDropState);
        long s2 = PlayerSeed.next(s1);
        long s3 = PlayerSeed.next(s2);
        long s4 = PlayerSeed.next(s3);
        float r1 = nextFloat(s1);
        float r2 = nextFloat(s2);
        float r3 = nextFloat(s3);
        float r4 = nextFloat(s4);

        float f2 = 0.3F;
        float sinPitch = sin(pitch * DEG_TO_RAD);
        float cosPitch = cos(pitch * DEG_TO_RAD);
        float sinYaw = sin(yaw * DEG_TO_RAD);
        float cosYaw = cos(yaw * DEG_TO_RAD);
        float f7 = r1 * TWO_PI;
        float f8 = 0.02F * r2;

        double x = (double) (-sinYaw * cosPitch * f2) + Math.cos((double) f7) * (double) f8;
        double y = (double) (-sinPitch * f2 + 0.1F) + (r3 - r4) * 0.1F;
        double z = (double) (cosYaw * cosPitch * f2) + Math.sin((double) f7) * (double) f8;
        return new Velocity(x, y, z);
    }

    /** {@code nextFloat()} for the LCG state right after a step: the top 24 bits over 2^24. */
    private static float nextFloat(long state) {
        return ((int) (state >>> 24)) / (float) (1 << 24);
    }

    /** The server's packed short for one velocity component: clamp, scale, truncate. */
    public static int pack(double component) {
        double clamped = component < -MAX_PACKED ? -MAX_PACKED : Math.min(component, MAX_PACKED);
        return (int) (clamped * PACK_SCALE);
    }

    /** Recovers the packed short the server sent from the velocity the client reads back. */
    public static int unpack(double component) {
        return (int) Math.round(component * PACK_SCALE);
    }

    // ------------------------------------------------------------------ the solve

    /**
     * Every 48-bit state consistent with {@code xpSeed} and an observed throw velocity.
     *
     * @param xpSeed          the XP seed already captured — the top 32 bits of the state right
     *                        after the enchantment that produced it
     * @param stepsBeforeDrop RNG steps consumed between that state and the throw's first
     *                        {@code nextFloat} (four per item dropped in between, else 0)
     * @param observed        the item's launch velocity, as the client received it
     * @param yaw             the player's yaw when they threw
     * @param pitch           the player's pitch when they threw
     * @return the player-seed states immediately after the throw (its four floats) that fit; a
     *         single element in the normal case, empty when the throw does not match, more than
     *         one only when two low-16 values are genuinely indistinguishable (throw again)
     */
    public static List<Long> solveFromXpSeed(int xpSeed, int stepsBeforeDrop, Velocity observed,
                                             float yaw, float pitch) {
        int obsX = unpack(observed.x);
        int obsY = unpack(observed.y);
        int obsZ = unpack(observed.z);
        List<Long> matches = new ArrayList<>();
        long high = ((long) xpSeed & 0xFFFF_FFFFL) << 16;
        for (int low = 0; low < 65536; low++) {
            long stateAfterEnchant = high | low;
            long preDrop = PlayerSeed.advance(stateAfterEnchant, stepsBeforeDrop);
            Velocity predicted = velocityFor(preDrop, yaw, pitch);
            if (pack(predicted.x) == obsX && pack(predicted.y) == obsY && pack(predicted.z) == obsZ) {
                matches.add(PlayerSeed.advance(preDrop, PlayerSeed.STEPS_PER_ITEM_DROP));
            }
        }
        return matches;
    }

    /**
     * True when a throw velocity is consistent with a fully known state, for confirming a lock
     * or re-syncing. {@code preDropState} is the state right before the throw's first float.
     */
    public static boolean confirms(long preDropState, Velocity observed, float yaw, float pitch) {
        Velocity predicted = velocityFor(preDropState, yaw, pitch);
        return pack(predicted.x) == unpack(observed.x)
                && pack(predicted.y) == unpack(observed.y)
                && pack(predicted.z) == unpack(observed.z);
    }
}
