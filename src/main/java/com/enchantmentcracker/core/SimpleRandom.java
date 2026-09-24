package com.enchantmentcracker.core;

/**
 * A stripped-down clone of {@link java.util.Random} specialised for seed cracking.
 *
 * <p>Ported from Earthcomputer/Hexicube's standalone EnchantmentCracker (MIT).
 * The only calls Minecraft makes while generating enchantment table levels are
 * {@code nextInt(bound)}, so everything else is omitted and {@code next()} is
 * hard-coded to {@code next(31)} and inlined.
 */
public final class SimpleRandom {

    public static final long MULTIPLIER = 0x5DEECE66DL;
    public static final long ADDEND = 0xBL;
    public static final long MASK = (1L << 48) - 1;

    private long seed;

    public void setSeed(long seed) {
        this.seed = (seed ^ MULTIPLIER) & MASK;
    }

    /** Always {@code next(31)}, inlined. */
    private int next() {
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        return (int) (seed >>> 17);
    }

    public int nextInt(int bound) {
        int r = next();
        int m = bound - 1;
        if ((bound & m) == 0) { // bound is a power of 2
            r = (int) ((bound * (long) r) >> 31);
        } else {
            int u = r;
            while (u - (r = u % bound) + m < 0) {
                u = next();
            }
        }
        return r;
    }
}
