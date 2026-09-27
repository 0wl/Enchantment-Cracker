package com.enchantmentcracker.core;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;

import java.util.Arrays;
import java.util.List;

/**
 * Recovers the full XP seed on a server, where the table only sends its low 16 bits.
 *
 * <p>The enchanting table syncs its XP seed to the client as container data, and
 * {@code SWindowPropertyPacket} writes container data as a {@code short}. In your own world
 * the packet never leaves memory, so the whole int arrives; over a real connection only the
 * low 16 bits do, sign-extended. The top 16 bits are recovered here by trying all 65,536 and
 * keeping those that reproduce what the table actually shows: the three level numbers and the
 * three enchantment hints. Every item put in the table rolls again from the same XP seed, so
 * another item narrows the list further until one seed is left.
 */
public final class PartialXpSeed {

    /** The bits that survive the trip to the client. */
    public static final int SYNCED_MASK = 0xFFFF;
    public static final int ALL = 1 << 16;

    /** Stands for "the table shows no hint for this slot". */
    public static final EnchantmentInstance NO_CLUE = new EnchantmentInstance("", 0);

    /** What the table shows for one item. */
    public static final class Observation {
        public final TableSetup setup;
        public final String item;
        public final int[] levels;
        /** One per slot: the hint shown, {@link #NO_CLUE}, or null to not check that slot. */
        public final EnchantmentInstance[] clues;
        /**
         * Tables that reveal several hints (Apotheosis): per slot, every hint in the order drawn,
         * or null to check only {@link #clues}. With {@link #allClues}: the hints are the whole list.
         */
        public final List<EnchantmentInstance>[] clueLists;
        public final boolean[] allClues;

        public Observation(TableSetup setup, String item, int[] levels, EnchantmentInstance[] clues) {
            this(setup, item, levels, clues, null, null);
        }

        public Observation(TableSetup setup, String item, int[] levels, EnchantmentInstance[] clues,
                           List<EnchantmentInstance>[] clueLists, boolean[] allClues) {
            this.setup = setup;
            this.item = item;
            this.levels = levels.clone();
            this.clues = clues.clone();
            this.clueLists = clueLists == null ? null : clueLists.clone();
            this.allClues = allClues == null ? null : allClues.clone();
        }

        /** True when a table holding this full XP seed would show exactly this. */
        public boolean matches(int xpSeed) {
            if (!Arrays.equals(setup.levels(xpSeed, item), levels)) {
                return false;
            }
            for (int slot = 0; slot < 3; slot++) {
                if (levels[slot] <= 0) {
                    continue;
                }
                List<EnchantmentInstance> shownList = clueLists == null ? null : clueLists[slot];
                if (shownList != null && !shownList.isEmpty()) {
                    TableSetup.ClueRoll roll = setup.rollClues(xpSeed, item, slot, levels[slot], shownList.size());
                    if (roll != null) {
                        if (!roll.picks.equals(shownList) || roll.exhausted != allClues[slot]) {
                            return false;
                        }
                        continue;
                    }
                }
                EnchantmentInstance shown = clues[slot];
                if (shown == null) {
                    continue;
                }
                EnchantmentInstance predicted = setup.clue(xpSeed, item, slot, levels[slot]);
                if (shown == NO_CLUE ? predicted != null : !shown.equals(predicted)) {
                    return false;
                }
            }
            return true;
        }
    }

    private int low = -1;
    /** Full XP seeds still possible, or null while nothing has been checked (all 65,536). */
    private int[] candidates;

    /** The 16 bits the client does get, as an unsigned value. */
    public static int lowBits(int synced) {
        return synced & SYNCED_MASK;
    }

    public static int withHigh(int high, int low) {
        return (high << 16) | (low & SYNCED_MASK);
    }

    public static String format(int low) {
        return String.format("????%04X", low & SYNCED_MASK);
    }

    /**
     * Feeds the value the table synced. A new low half means a new XP seed (an enchantment
     * happened), so everything learnt about the old one is dropped.
     *
     * @return true when it changed
     */
    public boolean setSynced(int synced) {
        int newLow = lowBits(synced);
        if (newLow == low) {
            return false;
        }
        low = newLow;
        candidates = null;
        return true;
    }

    public void reset() {
        low = -1;
        candidates = null;
    }

    public int getLow() {
        return low;
    }

    /**
     * Narrows the candidates with what the table shows. When nothing left fits (the player
     * swapped items mid-sync, say), it starts again from this observation alone.
     */
    public void observe(Observation observation) {
        if (low < 0) {
            return;
        }
        int[] narrowed = filter(candidates, observation);
        if (narrowed.length == 0 && candidates != null) {
            narrowed = filter(null, observation);
        }
        candidates = narrowed;
    }

    private int[] filter(int[] from, Observation observation) {
        int[] out = new int[from == null ? 16 : from.length];
        int n = 0;
        int count = from == null ? ALL : from.length;
        for (int i = 0; i < count; i++) {
            int seed = from == null ? withHigh(i, low) : from[i];
            if (observation.matches(seed)) {
                if (n == out.length) {
                    out = Arrays.copyOf(out, n * 2);
                }
                out[n++] = seed;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** How many full XP seeds still fit: 65,536 before anything was checked, 0 if none does. */
    public int count() {
        return low < 0 ? 0 : candidates == null ? ALL : candidates.length;
    }

    /** The full XP seeds that still fit, or null before anything was checked. */
    public int[] candidates() {
        return candidates == null ? null : candidates.clone();
    }

    /** The full XP seed, once only one fits; otherwise null. */
    public Integer resolved() {
        return candidates != null && candidates.length == 1 ? Integer.valueOf(candidates[0]) : null;
    }
}
