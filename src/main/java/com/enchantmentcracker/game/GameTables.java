package com.enchantmentcracker.game;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.EnchantArea;
import com.enchantmentcracker.core.TableSetup;
import net.minecraft.enchantment.EnchantmentData;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Vanilla enchanting tables, rolled by calling Minecraft's own code.
 *
 * <p>The core has a faithful port of the table maths, but calling the real thing is better
 * still in game: it picks up every modded enchantment, every mod that adjusts enchantability
 * or which items take which enchantments, and every coremod patch to
 * {@code EnchantmentHelper}, with no extra work. The port stays as the reference the tests
 * compare this against.
 */
public final class GameTables {

    public static final TableSetup.Factory FACTORY = VanillaGameTable::new;

    /**
     * One shared stack per item. Rolling never modifies the stack, and building a new one
     * fires Forge's capability events, which should not happen thousands of times per search
     * or on a worker thread. Warm it with {@link #stack} on the client thread before a search.
     */
    private static final java.util.Map<String, ItemStack> STACKS = new java.util.concurrent.ConcurrentHashMap<>();

    private GameTables() {
    }

    public static ItemStack stack(String item) {
        if (item == null) {
            return ItemStack.field_190927_a; // EMPTY
        }
        return STACKS.computeIfAbsent(item, Mc::stackOf);
    }

    /** Registries changed (new world); ids may now name other things. */
    public static void clearCache() {
        STACKS.clear();
    }

    /** {@code EnchantmentContainer} as it would run for a table with this many shelves. */
    static final class VanillaGameTable extends TableSetup {

        VanillaGameTable(int shelves) {
            super(Math.max(0, Math.min(EnchantArea.MAX_POWER, shelves)));
        }

        @Override
        public String describe() {
            return shelves + " bookshel" + (shelves == 1 ? "f" : "ves");
        }

        @Override
        public int[] levels(int xpSeed, String item) {
            int[] levels = new int[3];
            ItemStack stack = stack(item);
            if (stack.func_190926_b() || !stack.func_77956_u()) { // isEmpty, isEnchantable
                return levels;
            }
            Random rand = new Random(xpSeed);
            for (int slot = 0; slot < 3; slot++) {
                // EnchantmentHelper.calcItemStackEnchantability(rand, slot, power, stack)
                int level = EnchantmentHelper.func_77514_a(rand, slot, shelves, stack);
                levels[slot] = level < slot + 1 ? 0 : level;
            }
            return levels;
        }

        @Override
        public List<EnchantmentInstance> enchantments(int xpSeed, String item, int slot, int level) {
            ItemStack stack = stack(item);
            if (stack.func_190926_b() || level <= 0) {
                return Collections.emptyList();
            }
            // EnchantmentContainer#getEnchantmentList
            Random rand = new Random();
            rand.setSeed(xpSeed + slot);
            // EnchantmentHelper.buildEnchantmentList(rand, stack, level, false)
            List<EnchantmentData> list = EnchantmentHelper.func_77513_b(rand, stack, level, false);
            if (stack.func_77973_b() == Items.field_151122_aG && list.size() > 1) { // Items.BOOK
                list.remove(rand.nextInt(list.size()));
            }
            return convert(list);
        }
    }

    /** Game enchantment data to the core's string form. */
    static List<EnchantmentInstance> convert(List<EnchantmentData> list) {
        List<EnchantmentInstance> out = new ArrayList<>(list.size());
        for (EnchantmentData data : list) {
            // enchantmentData.enchantment / .enchantmentLevel
            out.add(new EnchantmentInstance(Mc.idOf(data.field_76302_b.getRegistryName()), data.field_76303_c));
        }
        return out;
    }
}
