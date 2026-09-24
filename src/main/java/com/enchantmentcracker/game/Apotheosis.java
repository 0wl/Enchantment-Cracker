package com.enchantmentcracker.game;

import com.enchantmentcracker.EnchantmentCrackerMod;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.TableSetup;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentData;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Support for Apotheosis's enchanting table (tested against 4.8.9 for 1.16.5).
 *
 * <p>With its enchantment module on, Apotheosis replaces the vanilla table outright: the
 * level requirements come from "Eterna" rather than a bookshelf count, and the enchantments
 * are chosen with its own weights ("Arcana") and level spread ("Quanta"). It still draws
 * everything from the player's XP seed, and enchanting still costs one step of the player's
 * generator, so the whole cracking technique carries over: only the table maths differs.
 *
 * <p>Rather than re-implement that maths, this calls Apotheosis's own code
 * ({@code RealEnchantmentHelper}) reflectively, so its configs, its per-enchantment level
 * rules and any other mods' enchantments are all honoured exactly. The table's stats are
 * synced to the client for its own screen; in your own world they are read from the server
 * side instead, which keeps full float precision.
 */
public final class Apotheosis {

    private static boolean resolved;
    private static boolean available;
    private static String failure;

    private static Class<?> containerClass;
    private static Field eternaField;
    private static Field quantaField;
    private static Field arcanaField;
    private static Field rectificationField;
    private static Method holderGet;
    private static Method enchantmentCost;
    private static Method selectEnchantment;
    private static Method getEnchInfo;
    private static Method infoMaxLevel;
    private static Method infoMinLevel;
    private static Method infoMinPower;
    private static Method infoMaxPower;
    private static Method absoluteMaxEterna;
    private static Field enableEnch;

    private Apotheosis() {
    }

    /** Apotheosis is installed and its enchantment module is switched on. */
    public static boolean isActive() {
        if (!isInstalled()) {
            return false;
        }
        resolve();
        if (!available) {
            return false;
        }
        try {
            return enableEnch.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Why the integration is not working, for the About tab. Null when it is fine or absent. */
    public static String getFailure() {
        return failure;
    }

    /** The mod is loaded at all (its enchantment module may still be off). */
    public static boolean isInstalled() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded("apotheosis");
    }

    public static boolean isApothContainer(Container container) {
        if (!isInstalled()) {
            return false;
        }
        resolve();
        return available && containerClass.isInstance(container);
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            ClassLoader loader = Apotheosis.class.getClassLoader();
            Class<?> apoth = Class.forName("shadows.apotheosis.Apotheosis", false, loader);
            enableEnch = apoth.getField("enableEnch");

            containerClass = Class.forName("shadows.apotheosis.ench.table.ApothEnchantContainer", false, loader);
            eternaField = field(containerClass, "eterna");
            quantaField = field(containerClass, "quanta");
            arcanaField = field(containerClass, "arcana");
            rectificationField = field(containerClass, "rectification");
            holderGet = Class.forName("shadows.apotheosis.util.FloatReferenceHolder", false, loader).getMethod("get");

            Class<?> helper = Class.forName("shadows.apotheosis.ench.table.RealEnchantmentHelper", false, loader);
            enchantmentCost = helper.getMethod("getEnchantmentCost", Random.class, int.class, float.class, ItemStack.class);
            selectEnchantment = helper.getMethod("selectEnchantment", Random.class, ItemStack.class, int.class,
                    float.class, float.class, float.class, boolean.class);

            Class<?> module = Class.forName("shadows.apotheosis.ench.EnchModule", false, loader);
            getEnchInfo = module.getMethod("getEnchInfo", Enchantment.class);
            Class<?> info = Class.forName("shadows.apotheosis.ench.EnchantmentInfo", false, loader);
            infoMaxLevel = info.getMethod("getMaxLevel");
            infoMinLevel = info.getMethod("getMinLevel");
            infoMinPower = info.getMethod("getMinPower", int.class);
            infoMaxPower = info.getMethod("getMaxPower", int.class);

            Class<?> stats = Class.forName("shadows.apotheosis.ench.table.EnchantingStatManager", false, loader);
            absoluteMaxEterna = stats.getMethod("getAbsoluteMaxEterna");
            available = true;
        } catch (Throwable t) {
            available = false;
            failure = "Apotheosis found, but this version is not supported: " + t;
            EnchantmentCrackerMod.LOGGER.warn("Apotheosis integration unavailable", t);
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ------------------------------------------------------------------ stats

    /** The four stats of an Apotheosis table, with the item's share of Arcana taken out. */
    public static final class Stats {
        public final float eterna;
        public final float quanta;
        /** Arcana from the blocks alone; the item adds half its enchantability on top. */
        public final float baseArcana;
        public final float rectification;
        /** True when read from the server side at full precision. */
        public final boolean exact;

        Stats(float eterna, float quanta, float baseArcana, float rectification, boolean exact) {
            this.eterna = eterna;
            this.quanta = quanta;
            this.baseArcana = baseArcana;
            this.rectification = rectification;
            this.exact = exact;
        }

        public String describe() {
            return String.format(Locale.ROOT, "E%.1f Q%.0f%% A%.0f%%+",
                    eterna, quanta, baseArcana);
        }

        /** The same stats at a different Eterna, for searching lower-power layouts. */
        public Stats withEterna(float newEterna) {
            return new Stats(newEterna, quanta, baseArcana, rectification, exact);
        }
    }

    /**
     * Table setups to search for an Apotheosis world, the way vanilla searches bookshelf counts
     * 0..max. Eterna is the lever that a table's blocks change (like a shelf count), so this
     * walks the current Eterna and every whole value below it down to the 2 floor; Quanta and
     * Arcana are left as they are. The current layout comes first so a plan that needs no
     * rebuilding wins a tie.
     */
    public static java.util.List<TableSetup> searchSetups(Stats current) {
        java.util.List<TableSetup> out = new java.util.ArrayList<>();
        if (current == null) {
            return out;
        }
        out.add(new Table(current));
        for (int eterna = (int) Math.floor(current.eterna); eterna >= 2; eterna--) {
            if (eterna < current.eterna) {
                out.add(new Table(current.withEterna(eterna)));
            }
        }
        return out;
    }

    /**
     * Reads the stats off an open table container, or null if they are not there. Apotheosis
     * zeroes them whenever the item slot is empty, so they only mean something with an item in.
     *
     * @param itemEnchantability of the item currently in the table
     */
    public static Stats readStats(Container container, int itemEnchantability, boolean exact) {
        if (!isApothContainer(container)) {
            return null;
        }
        try {
            float eterna = (Float) holderGet.invoke(eternaField.get(container));
            float quanta = (Float) holderGet.invoke(quantaField.get(container));
            float arcana = (Float) holderGet.invoke(arcanaField.get(container));
            float rect = (Float) holderGet.invoke(rectificationField.get(container));
            return new Stats(eterna, quanta, arcana - itemEnchantability / 2.0F, rect, exact);
        } catch (Throwable t) {
            failure = "Could not read the table's stats: " + t;
            return null;
        }
    }

    // ------------------------------------------------------------------ the table

    /** An Apotheosis table with fixed stats, rolled with Apotheosis's own code. */
    public static final class Table extends TableSetup {
        private final Stats stats;

        public Table(Stats stats) {
            super(-1);
            this.stats = stats;
        }

        public Stats stats() {
            return stats;
        }

        @Override
        public String describe() {
            return "Apotheosis " + stats.describe();
        }

        @Override
        public int[] levels(int xpSeed, String item) {
            int[] levels = new int[3];
            ItemStack stack = GameTables.stack(item);
            if (stack.func_190926_b() || !stack.func_77973_b().func_77616_k(stack)) { // isEmpty, item.isEnchantable(stack)
                return levels;
            }
            float eterna = Math.max(1.5F, stats.eterna);
            Random rand = new Random(xpSeed);
            try {
                for (int slot = 0; slot < 3; slot++) {
                    int level = (Integer) enchantmentCost.invoke(null, rand, slot, eterna, stack);
                    // Apotheosis bumps a too-low slot up by one where vanilla empties it.
                    levels[slot] = level < slot + 1 ? level + 1 : level;
                }
            } catch (Throwable t) {
                failure = "Apotheosis level calculation failed: " + t;
                return new int[3];
            }
            return levels;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<EnchantmentInstance> enchantments(int xpSeed, String item, int slot, int level) {
            ItemStack stack = GameTables.stack(item);
            if (stack.func_190926_b()) {
                return Collections.emptyList();
            }
            float arcana = stats.baseArcana + stack.getItemEnchantability() / 2.0F;
            Random rand = new Random();
            rand.setSeed(xpSeed + slot);
            try {
                List<EnchantmentData> list = (List<EnchantmentData>) selectEnchantment.invoke(null, rand, stack, level,
                        stats.quanta, arcana, stats.rectification, false);
                return GameTables.convert(list);
            } catch (Throwable t) {
                failure = "Apotheosis enchantment selection failed: " + t;
                return Collections.emptyList();
            }
        }
    }

    // ------------------------------------------------------------ enchantment info

    /** Apotheosis's per-enchantment level and power rules; null when unavailable. */
    public static int[] levelRange(Enchantment enchantment) {
        try {
            Object info = getEnchInfo.invoke(null, enchantment);
            return new int[]{(Integer) infoMinLevel.invoke(info), (Integer) infoMaxLevel.invoke(info)};
        } catch (Throwable t) {
            return null;
        }
    }

    public static int minPower(Enchantment enchantment, int level) {
        try {
            return (Integer) infoMinPower.invoke(getEnchInfo.invoke(null, enchantment), level);
        } catch (Throwable t) {
            return enchantment.func_77321_a(level);
        }
    }

    public static int maxPower(Enchantment enchantment, int level) {
        try {
            return (Integer) infoMaxPower.invoke(getEnchInfo.invoke(null, enchantment), level);
        } catch (Throwable t) {
            return enchantment.func_223551_b(level);
        }
    }

    /** The highest power an Apotheosis table can ever roll with: 4x the Eterna ceiling. */
    public static int maxPower() {
        try {
            return (int) ((Float) absoluteMaxEterna.invoke(null) * 4.0F);
        } catch (Throwable t) {
            return 200;
        }
    }
}
