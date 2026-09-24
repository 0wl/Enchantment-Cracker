package com.enchantmentcracker.core;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;

import java.util.List;
import java.util.Random;

/**
 * A vanilla enchanting table with a given bookshelf count, rolled with the ported maths in
 * {@link CrackEnchantments}. This is the default before the game is running and what the
 * tests use; in game a setup that calls Minecraft's own code takes its place.
 */
public final class VanillaTable extends TableSetup {

    public static final Factory FACTORY = VanillaTable::new;

    public VanillaTable(int shelves) {
        super(Math.max(0, Math.min(EnchantArea.MAX_POWER, shelves)));
    }

    @Override
    public String describe() {
        return shelves + " bookshel" + (shelves == 1 ? "f" : "ves");
    }

    @Override
    public int[] levels(int xpSeed, String item) {
        return levelsFor(xpSeed, shelves, item);
    }

    @Override
    public List<EnchantmentInstance> enchantments(int xpSeed, String item, int slot, int level) {
        return CrackEnchantments.getEnchantmentsInTable(new Random(), xpSeed, item, slot, level);
    }

    /** Vanilla level requirements: rolled in slot order, and a slot below its number is empty. */
    public static int[] levelsFor(int xpSeed, int shelves, String item) {
        Random rand = new Random(xpSeed);
        int[] levels = new int[3];
        for (int slot = 0; slot < 3; slot++) {
            int level = CrackEnchantments.calcEnchantmentTableLevel(rand, slot, shelves, item);
            levels[slot] = level < slot + 1 ? 0 : level;
        }
        return levels;
    }
}
