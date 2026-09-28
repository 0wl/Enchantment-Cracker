package com.enchantmentcracker.core;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;

import java.util.List;

/**
 * One enchanting table configuration: how it turns an XP seed into the three offers.
 *
 * <p>For a vanilla table the only knob is the bookshelf count, so the planner tries every
 * count from 0 up to what you have. Tables replaced by other mods (Apotheosis, for one) are
 * driven by other stats; those are described by the game layer and the planner simply uses
 * the table as it stands.
 *
 * <p>Implementations must be deterministic and safe to call from a worker thread.
 */
public abstract class TableSetup {

    /** Bookshelf count for a vanilla table, or -1 when this setup is not shelf-based. */
    public final int shelves;

    protected TableSetup(int shelves) {
        this.shelves = shelves;
    }

    /** "11 bookshelves", "this table (Eterna 22.5, ...)" and so on. */
    public abstract String describe();

    /**
     * The three level requirements for this XP seed, 0 for an empty slot. The table rolls all
     * three from one {@code Random} seeded with the XP seed, in slot order.
     */
    public abstract int[] levels(int xpSeed, String item);

    /** The exact enchantments slot {@code slot} hands out at level requirement {@code level}. */
    public abstract List<EnchantmentInstance> enchantments(int xpSeed, String item, int slot, int level);

    /**
     * The hint the table shows for slot {@code slot} ("Sharpness III...?"), or null when the
     * slot hands out nothing. Tables pick it with the same {@code Random} that just built the
     * list: {@code list.get(rand.nextInt(list.size()))}.
     */
    public abstract EnchantmentInstance clue(int xpSeed, String item, int slot, int level);

    /**
     * For tables that reveal more than one hint per slot (Apotheosis): the first {@code count}
     * hints in the order they are drawn, each removed from the list as it is picked, and whether
     * that emptied the list. Null when this table shows a single hint only.
     */
    public ClueRoll rollClues(int xpSeed, String item, int slot, int level, int count) {
        return null;
    }

    /** See {@link #rollClues}. */
    public static final class ClueRoll {
        public final List<EnchantmentInstance> picks;
        public final boolean exhausted;

        public ClueRoll(List<EnchantmentInstance> picks, boolean exhausted) {
            this.picks = picks;
            this.exhausted = exhausted;
        }
    }

    /**
     * Every enchanting power this table can roll for {@code item} over all XP seeds, which
     * decides the highest enchantment levels it can give (see {@link TableReach}). Null when
     * this table cannot say.
     */
    public java.util.BitSet powers(String item) {
        return null;
    }

    public boolean isShelfBased() {
        return shelves >= 0;
    }

    /** Builds the setup for a plain vanilla table with this many shelves. */
    public interface Factory {
        TableSetup vanilla(int shelves);
    }
}
