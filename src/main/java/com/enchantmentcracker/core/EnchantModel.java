package com.enchantmentcracker.core;

import java.util.List;

/**
 * Everything the enchanting maths needs to know about enchantments and items.
 *
 * <p>Two implementations exist. {@link VanillaModel} is the hard-coded 1.16.5 data ported
 * from the standalone tool, and needs no game at all, which is what the unit tests run
 * against. The game layer supplies a registry-backed model that reads every value straight
 * off the live {@code Enchantment} and {@code Item} objects, so enchantments and items added
 * by other mods take part exactly as they do on the server.
 *
 * <p>Identifiers are registry names with the {@code minecraft:} namespace dropped, so vanilla
 * content reads {@code "sharpness"} / {@code "diamond_sword"} and modded content keeps its
 * namespace, e.g. {@code "mymod:frost_blade"}.
 *
 * <p>Implementations must be safe to read from worker threads.
 */
public interface EnchantModel {

    /**
     * Enchantments the table can roll for {@code item}, in registry order: not treasure,
     * discoverable, and either applicable to the item at a table or, for a book, allowed on
     * books. Order matters, because the weighted pick walks this list front to back.
     */
    List<String> tableCandidates(String item);

    /** Every enchantment a table can roll for at least one item, in registry order. */
    List<String> allTableEnchantments();

    /** Every enchantment there is, treasure included, in registry order. The anvil uses these. */
    List<String> allEnchantments();

    /**
     * {@code ItemStack#getItemEnchantability()}, or 0 when the table refuses the item
     * outright ({@code !isEnchantable()}).
     */
    int enchantability(String item);

    /** Vanilla removes one enchantment from a multi-enchantment roll for a plain book. */
    boolean isBook(String item);

    int minLevel(String enchantment);

    int maxLevel(String enchantment);

    int minEnchantability(String enchantment, int level);

    int maxEnchantability(String enchantment, int level);

    /** {@code Enchantment.Rarity#getWeight()}: 10, 5, 2 or 1 in vanilla. */
    int weight(String enchantment);

    /** {@code Enchantment#isCompatibleWith}, which already checks both directions. */
    boolean compatible(String a, String b);

    /** {@code Enchantment#canApply} — the anvil's looser check, not the table's. */
    boolean canApplyAtAnvil(String enchantment, String item);

    /** Anvil cost per level: 1, 2, 4 or 8 by rarity. */
    default int anvilMultiplier(String enchantment) {
        switch (weight(enchantment)) {
            case 10:
                return 1;
            case 5:
                return 2;
            case 2:
                return 4;
            default:
                return 8;
        }
    }

    /**
     * The highest level of {@code enchantment} a table could ever put on {@code item}. The
     * default is vanilla's ceiling: 30 levels, both enchantability rolls at their maximum,
     * then +15%.
     */
    default int maxTableLevel(String enchantment, String item) {
        int enchantability = enchantability(item);
        if (enchantability <= 0 || !tableCandidates(item).contains(enchantment)) {
            return 0;
        }
        int level = 30 + 1 + enchantability / 4 + enchantability / 4;
        level = Math.round(level + level * 0.15f);
        for (int candidate = maxLevel(enchantment); candidate >= minLevel(enchantment); candidate--) {
            if (level >= minEnchantability(enchantment, candidate)) {
                return candidate;
            }
        }
        return 0;
    }

    /** Items the table accepts, in the order a picker should offer them. */
    List<String> enchantableItems();

    /** True when an identifier names something this model knows about. */
    boolean knowsEnchantment(String enchantment);
}
