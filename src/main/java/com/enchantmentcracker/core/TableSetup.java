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

    public boolean isShelfBased() {
        return shelves >= 0;
    }

    /** Builds the setup for a plain vanilla table with this many shelves. */
    public interface Factory {
        TableSetup vanilla(int shelves);
    }
}
