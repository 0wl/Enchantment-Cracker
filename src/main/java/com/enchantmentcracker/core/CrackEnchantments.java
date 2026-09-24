package com.enchantmentcracker.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * A faithful re-implementation of Minecraft 1.16.5's enchanting table maths.
 *
 * <p>Ported from Earthcomputer/Hexicube's standalone EnchantmentCracker (MIT), with the
 * multi-version branches resolved to 1.16 behaviour. Every method here mirrors a piece of
 * vanilla: {@link #calcEnchantmentTableLevel} is {@code EnchantmentContainer#onCraftMatrixChanged},
 * {@link #addRandomEnchantments} is {@code EnchantmentHelper#buildEnchantmentList}.
 *
 * <p>The per-enchantment data (weights, enchantability ranges, which items take what) comes
 * from the active {@link EnchantModel}: hard-coded vanilla data before the game starts, the
 * live registries after, so modded enchantments are rolled exactly as the server rolls them.
 *
 * <p>Order matters. {@link EnchantModel#tableCandidates} is in registry order because
 * {@link #weightedRandom} walks the candidate list front to back, so reordering it silently
 * changes which enchantment a given seed produces.
 */
public final class CrackEnchantments {

    private CrackEnchantments() {
    }

    // @formatter:off
    public static final String
            PROTECTION            = "protection",
            FIRE_PROTECTION       = "fire_protection",
            FEATHER_FALLING       = "feather_falling",
            BLAST_PROTECTION      = "blast_protection",
            PROJECTILE_PROTECTION = "projectile_protection",
            RESPIRATION           = "respiration",
            AQUA_AFFINITY         = "aqua_affinity",
            THORNS                = "thorns",
            DEPTH_STRIDER         = "depth_strider",
            FROST_WALKER          = "frost_walker",
            BINDING_CURSE         = "binding_curse",
            SOUL_SPEED            = "soul_speed",
            SHARPNESS             = "sharpness",
            SMITE                 = "smite",
            BANE_OF_ARTHROPODS    = "bane_of_arthropods",
            KNOCKBACK             = "knockback",
            FIRE_ASPECT           = "fire_aspect",
            LOOTING               = "looting",
            SWEEPING              = "sweeping",
            EFFICIENCY            = "efficiency",
            SILK_TOUCH            = "silk_touch",
            UNBREAKING            = "unbreaking",
            FORTUNE               = "fortune",
            POWER                 = "power",
            PUNCH                 = "punch",
            FLAME                 = "flame",
            INFINITY              = "infinity",
            LUCK_OF_THE_SEA       = "luck_of_the_sea",
            LURE                  = "lure",
            LOYALTY               = "loyalty",
            IMPALING              = "impaling",
            RIPTIDE               = "riptide",
            CHANNELING            = "channeling",
            MULTISHOT             = "multishot",
            QUICK_CHARGE          = "quick_charge",
            PIERCING              = "piercing",
            MENDING               = "mending",
            VANISHING_CURSE       = "vanishing_curse";
    // @formatter:on

    /** Total experience points contained in {@code numLevels} levels counting down from {@code startLevel}. */
    public static int levelsToXP(int startLevel, int numLevels) {
        int amount = 0;
        int endLevel = startLevel - numLevels;
        for (int level = startLevel; level > endLevel; level--) {
            if (level > 30) {
                amount += (9 * (level - 1)) - 158;
            } else if (level > 15) {
                amount += (5 * (level - 1)) - 38;
            } else {
                amount += (2 * (level - 1)) + 7;
            }
        }
        return amount;
    }

    // ------------------------------------------------------------ model facade
    //
    // The numbers below come from whichever EnchantModel is active: the hard-coded vanilla
    // tables before the game is up, the live registries (modded content included) after.

    public static EnchantModel model() {
        return Models.get();
    }

    /** Every enchantment a table can roll for at least one item, in registry order. */
    public static List<String> tableEnchantments() {
        return model().allTableEnchantments();
    }

    /**
     * @param primary {@code true} for the enchanting table's check, {@code false} for the
     *                anvil's looser one.
     */
    public static boolean canApply(String enchantment, String item, boolean primary) {
        return primary ? model().tableCandidates(item).contains(enchantment)
                : model().canApplyAtAnvil(enchantment, item);
    }

    public static int getMaxLevel(String enchantment) {
        return model().maxLevel(enchantment);
    }

    public static int getMinEnchantability(String enchantment, int level) {
        return model().minEnchantability(enchantment, level);
    }

    public static int getMaxEnchantability(String enchantment, int level) {
        return model().maxEnchantability(enchantment, level);
    }

    public static int getWeight(String enchantment) {
        return model().weight(enchantment);
    }

    /** Highest level of {@code enchantment} a table could ever put on {@code item}. */
    public static int getMaxLevelInTable(String enchantment, String item) {
        return model().maxTableLevel(enchantment, item);
    }

    public static boolean areCompatible(String enchA, String enchB) {
        return model().compatible(enchA, enchB);
    }

    /**
     * Vanilla's per-slot level requirement. Must be called for slots 0, 1 and 2 in order
     * on the same {@link Random}, because the RNG is not reset between slots.
     */
    public static int calcEnchantmentTableLevel(Random rand, int slot, int bookshelves, String item) {
        if (CrackItems.getEnchantability(item) <= 0) {
            return 0;
        }
        bookshelves = Math.min(bookshelves, 15);

        int level = rand.nextInt(8) + 1 + (bookshelves >> 1) + rand.nextInt(bookshelves + 1);

        switch (slot) {
            case 0:
                return Math.max(level / 3, 1);
            case 1:
                return level * 2 / 3 + 1;
            case 2:
                return Math.max(level, bookshelves * 2);
            default:
                throw new IllegalArgumentException("Bad slot " + slot);
        }
    }

    /** The exact enchantments slot {@code slot} would hand out for this XP seed. */
    public static List<EnchantmentInstance> getEnchantmentsInTable(Random rand, int xpSeed, String item, int slot,
                                                                  int levels) {
        rand.setSeed(xpSeed + slot);

        List<EnchantmentInstance> list = addRandomEnchantments(rand, item, levels);
        if (model().isBook(item) && list.size() > 1) {
            list.remove(rand.nextInt(list.size()));
        }
        return list;
    }

    /** Vanilla {@code EnchantmentHelper#getEnchantmentDatas} with treasure excluded. */
    public static List<EnchantmentInstance> getHighestAllowedEnchantments(int level, String item) {
        EnchantModel model = model();
        List<EnchantmentInstance> allowed = new ArrayList<>();
        for (String enchantment : model.tableCandidates(item)) {
            for (int enchLevel = model.maxLevel(enchantment); enchLevel > model.minLevel(enchantment) - 1; enchLevel--) {
                if (level >= model.minEnchantability(enchantment, enchLevel)
                        && level <= model.maxEnchantability(enchantment, enchLevel)) {
                    allowed.add(new EnchantmentInstance(enchantment, enchLevel));
                    break;
                }
            }
        }
        return allowed;
    }

    /** Vanilla {@code EnchantmentHelper#buildEnchantmentList}. */
    public static List<EnchantmentInstance> addRandomEnchantments(Random rand, String item, int level) {
        int enchantability = CrackItems.getEnchantability(item);
        List<EnchantmentInstance> enchantments = new ArrayList<>();
        if (enchantability <= 0) {
            return enchantments;
        }

        // Fuzz the level by the item's enchantability, then by +/-15%.
        level = level + 1 + rand.nextInt(enchantability / 4 + 1) + rand.nextInt(enchantability / 4 + 1);
        float percentChange = (rand.nextFloat() + rand.nextFloat() - 1) * 0.15f;
        // Exactly vanilla's float expression: round(L + L*f), not L + round(L*f). The two
        // differ when L*f lands a hair either side of .5, which does happen.
        level = Math.max(1, Math.round((float) level + (float) level * percentChange));

        List<EnchantmentInstance> allowed = getHighestAllowedEnchantments(level, item);
        if (allowed.isEmpty()) {
            return enchantments;
        }

        enchantments.add(weightedRandom(rand, allowed));

        // Each extra enchantment gets progressively less likely.
        while (rand.nextInt(50) <= level) {
            // Vanilla only filters against the most recent pick; earlier picks were already
            // filtered against when they were chosen.
            String last = enchantments.get(enchantments.size() - 1).enchantment;
            allowed.removeIf(it -> !model().compatible(last, it.enchantment));
            if (allowed.isEmpty()) {
                break;
            }
            enchantments.add(weightedRandom(rand, allowed));
            level /= 2;
        }

        return enchantments;
    }

    private static EnchantmentInstance weightedRandom(Random rand, List<EnchantmentInstance> list) {
        EnchantModel model = model();
        ToIntFunction<EnchantmentInstance> weightOf = it -> model.weight(it.enchantment);
        int weight = list.stream().mapToInt(weightOf).sum();
        if (weight <= 0) {
            return null;
        }
        weight = rand.nextInt(weight);
        for (EnchantmentInstance instance : list) {
            weight -= weightOf.applyAsInt(instance);
            if (weight < 0) {
                return instance;
            }
        }
        return null;
    }

    public static String romanNumeral(int level) {
        switch (level) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            default: return String.valueOf(level);
        }
    }

    public static final class EnchantmentInstance {
        public final String enchantment;
        public final int level;

        public EnchantmentInstance(String enchantment, int level) {
            this.enchantment = enchantment;
            this.level = level;
        }

        @Override
        public int hashCode() {
            return enchantment.hashCode() + 31 * level;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EnchantmentInstance
                    && ((EnchantmentInstance) other).level == level
                    && ((EnchantmentInstance) other).enchantment.equals(enchantment);
        }

        @Override
        public String toString() {
            if (level == 1 && model().knowsEnchantment(enchantment) && getMaxLevel(enchantment) == 1) {
                return enchantment;
            }
            return enchantment + " " + romanNumeral(level);
        }
    }
}
