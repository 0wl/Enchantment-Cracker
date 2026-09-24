package com.enchantmentcracker.core;

/**
 * Maths for the player's 48-bit {@code java.util.Random} state — the thing the whole
 * technique is really after.
 *
 * <p>The server keeps one {@code Random} per player. Every time you enchant, it calls
 * {@code nextInt()} and stores the result as your XP seed, which is what the enchanting
 * table screen is driven by. Recover the 48-bit state and you can predict, and therefore
 * choose, every future enchantment.
 *
 * <p>An XP seed is the top 32 bits of the 48-bit state, so one XP seed leaves 16 unknown
 * bits. Two consecutive XP seeds pin it down exactly: brute-force the 16 missing bits and
 * keep the one that steps to the second seed.
 */
public final class PlayerSeed {

    /** Not a seed. Returned when the state is unknown. */
    public static final long UNKNOWN = -1L;

    /** RNG steps consumed by one dropped item stack (four {@code nextFloat} calls). */
    public static final int STEPS_PER_ITEM_DROP = 4;
    /** RNG steps consumed by one enchantment (one {@code nextInt} call). */
    public static final int STEPS_PER_ENCHANT = 1;

    private PlayerSeed() {
    }

    /** One step of Java's linear congruential generator. */
    public static long next(long seed) {
        return (seed * SimpleRandom.MULTIPLIER + SimpleRandom.ADDEND) & SimpleRandom.MASK;
    }

    /** Modular inverse of the LCG multiplier mod 2^48, so the generator can be run backwards. */
    private static final long INVERSE_MULTIPLIER = 0xDFE05BCB1365L;

    /** One step backwards. {@code previous(next(s)) == s}. */
    public static long previous(long seed) {
        return ((seed - SimpleRandom.ADDEND) * INVERSE_MULTIPLIER) & SimpleRandom.MASK;
    }

    public static long advance(long seed, int steps) {
        if (steps < 0) {
            for (int i = 0; i < -steps; i++) {
                seed = previous(seed);
            }
            return seed;
        }
        for (int i = 0; i < steps; i++) {
            seed = next(seed);
        }
        return seed;
    }

    /** The XP seed a given 48-bit state produces, i.e. what the table is currently using. */
    public static int xpSeedOf(long playerSeed) {
        return (int) (playerSeed >>> 16);
    }

    /**
     * Recovers the full 48-bit state from two XP seeds generated back to back, with
     * nothing else touching the player's RNG in between.
     *
     * @return the state immediately after {@code xpSeed2} was produced, or {@link #UNKNOWN}
     *         if no state maps one to the other (which means something else consumed the RNG).
     */
    public static long solve(int xpSeed1, int xpSeed2) {
        long seed1High = ((long) xpSeed1 << 16) & 0x0000_ffff_ffff_0000L;
        long seed2High = ((long) xpSeed2 << 16) & 0x0000_ffff_ffff_0000L;
        for (int low = 0; low < 65536; low++) {
            long stepped = next(seed1High | low);
            if ((stepped & 0x0000_ffff_ffff_0000L) == seed2High) {
                return stepped & SimpleRandom.MASK;
            }
        }
        return UNKNOWN;
    }

    /**
     * Finds how far the RNG has moved on since we last knew the state.
     *
     * <p>Handy when something unexpected consumed the player's RNG: the table still tells
     * us the true XP seed, so we walk forward looking for a state that produces it.
     *
     * @return the number of steps to advance, or -1 if no match within {@code maxSteps}.
     */
    public static int stepsUntilXpSeed(long playerSeed, int targetXpSeed, int maxSteps) {
        if (playerSeed == UNKNOWN) {
            return -1;
        }
        if (xpSeedOf(playerSeed) == targetXpSeed) {
            return 0;
        }
        long seed = playerSeed;
        for (int steps = 1; steps <= maxSteps; steps++) {
            seed = next(seed);
            if (xpSeedOf(seed) == targetXpSeed) {
                return steps;
            }
        }
        return -1;
    }

    public static String format(long playerSeed) {
        return playerSeed == UNKNOWN ? "-" : String.format("%012X", playerSeed);
    }

    public static String formatXpSeed(int xpSeed) {
        return String.format("%08X", xpSeed);
    }

    /** Parses a 12 hex digit player seed, or {@link #UNKNOWN} if the text is not one. */
    public static long parse(String text) {
        if (text == null) {
            return UNKNOWN;
        }
        text = text.trim();
        if (text.isEmpty() || text.length() > 12) {
            return UNKNOWN;
        }
        try {
            return Long.parseLong(text, 16) & SimpleRandom.MASK;
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** Parses an 8 hex digit XP seed. Returns null when the text is not one. */
    public static Integer parseXpSeed(String text) {
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.isEmpty() || text.length() > 8) {
            return null;
        }
        try {
            return (int) Long.parseLong(text, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
