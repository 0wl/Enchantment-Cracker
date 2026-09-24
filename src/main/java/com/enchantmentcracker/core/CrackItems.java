package com.enchantmentcracker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item identity + enchantability data, restricted to what exists in Minecraft 1.16.5.
 *
 * <p>Ported from Earthcomputer/Hexicube's standalone EnchantmentCracker (MIT). The
 * original tracks every version from 1.8 to 1.21; this mod only ever runs on 1.16.5,
 * so the version branches are resolved and later items (mace, copper gear, spears)
 * are dropped.
 *
 * <p>Names are vanilla registry paths, so {@code "minecraft:" + id} always resolves
 * to the real item.
 */
public final class CrackItems {

    private CrackItems() {
    }

    // @formatter:off
    public static final String
            LEATHER_HELMET      = "leather_helmet",
            LEATHER_CHESTPLATE  = "leather_chestplate",
            LEATHER_LEGGINGS    = "leather_leggings",
            LEATHER_BOOTS       = "leather_boots",
            CHAINMAIL_HELMET    = "chainmail_helmet",
            CHAINMAIL_CHESTPLATE= "chainmail_chestplate",
            CHAINMAIL_LEGGINGS  = "chainmail_leggings",
            CHAINMAIL_BOOTS     = "chainmail_boots",
            IRON_HELMET         = "iron_helmet",
            IRON_CHESTPLATE     = "iron_chestplate",
            IRON_LEGGINGS       = "iron_leggings",
            IRON_BOOTS          = "iron_boots",
            GOLDEN_HELMET       = "golden_helmet",
            GOLDEN_CHESTPLATE   = "golden_chestplate",
            GOLDEN_LEGGINGS     = "golden_leggings",
            GOLDEN_BOOTS        = "golden_boots",
            DIAMOND_HELMET      = "diamond_helmet",
            DIAMOND_CHESTPLATE  = "diamond_chestplate",
            DIAMOND_LEGGINGS    = "diamond_leggings",
            DIAMOND_BOOTS       = "diamond_boots",
            NETHERITE_HELMET    = "netherite_helmet",
            NETHERITE_CHESTPLATE= "netherite_chestplate",
            NETHERITE_LEGGINGS  = "netherite_leggings",
            NETHERITE_BOOTS     = "netherite_boots",
            TURTLE_HELMET       = "turtle_helmet",

            WOODEN_SWORD        = "wooden_sword",
            STONE_SWORD         = "stone_sword",
            IRON_SWORD          = "iron_sword",
            GOLDEN_SWORD        = "golden_sword",
            DIAMOND_SWORD       = "diamond_sword",
            NETHERITE_SWORD     = "netherite_sword",

            WOODEN_PICKAXE      = "wooden_pickaxe",
            STONE_PICKAXE       = "stone_pickaxe",
            IRON_PICKAXE        = "iron_pickaxe",
            GOLDEN_PICKAXE      = "golden_pickaxe",
            DIAMOND_PICKAXE     = "diamond_pickaxe",
            NETHERITE_PICKAXE   = "netherite_pickaxe",

            WOODEN_AXE          = "wooden_axe",
            STONE_AXE           = "stone_axe",
            IRON_AXE            = "iron_axe",
            GOLDEN_AXE          = "golden_axe",
            DIAMOND_AXE         = "diamond_axe",
            NETHERITE_AXE       = "netherite_axe",

            WOODEN_SHOVEL       = "wooden_shovel",
            STONE_SHOVEL        = "stone_shovel",
            IRON_SHOVEL         = "iron_shovel",
            GOLDEN_SHOVEL       = "golden_shovel",
            DIAMOND_SHOVEL      = "diamond_shovel",
            NETHERITE_SHOVEL    = "netherite_shovel",

            WOODEN_HOE          = "wooden_hoe",
            STONE_HOE           = "stone_hoe",
            IRON_HOE            = "iron_hoe",
            GOLDEN_HOE          = "golden_hoe",
            DIAMOND_HOE         = "diamond_hoe",
            NETHERITE_HOE       = "netherite_hoe",

            BOW                 = "bow",
            CROSSBOW            = "crossbow",
            TRIDENT             = "trident",
            FISHING_ROD         = "fishing_rod",
            BOOK                = "book",
            SHEARS              = "shears",
            FLINT_AND_STEEL     = "flint_and_steel",
            CARROT_ON_A_STICK   = "carrot_on_a_stick",
            WARPED_FUNGUS_ON_A_STICK = "warped_fungus_on_a_stick",
            ELYTRA              = "elytra",
            SHIELD              = "shield",
            CARVED_PUMPKIN      = "carved_pumpkin",
            PLAYER_HEAD         = "player_head";
    // @formatter:on

    /** Material tiers offered by the GUI's material picker, in the order they cycle. */
    public static final String[] MATERIALS = {
            "netherite", "diamond", "golden", "iron", "chainmail", "stone", "wooden", "leather"
    };

    /**
     * Grid used by the item picker: one row per item shape, one entry per material.
     * A {@code null} means that shape does not exist in that material.
     */
    public static final Map<String, String[]> ITEM_GRID = new LinkedHashMap<>();

    static {
        // order of columns matches MATERIALS
        ITEM_GRID.put("sword", new String[]{
                NETHERITE_SWORD, DIAMOND_SWORD, GOLDEN_SWORD, IRON_SWORD, null, STONE_SWORD, WOODEN_SWORD, null});
        ITEM_GRID.put("pickaxe", new String[]{
                NETHERITE_PICKAXE, DIAMOND_PICKAXE, GOLDEN_PICKAXE, IRON_PICKAXE, null, STONE_PICKAXE, WOODEN_PICKAXE, null});
        ITEM_GRID.put("axe", new String[]{
                NETHERITE_AXE, DIAMOND_AXE, GOLDEN_AXE, IRON_AXE, null, STONE_AXE, WOODEN_AXE, null});
        ITEM_GRID.put("shovel", new String[]{
                NETHERITE_SHOVEL, DIAMOND_SHOVEL, GOLDEN_SHOVEL, IRON_SHOVEL, null, STONE_SHOVEL, WOODEN_SHOVEL, null});
        ITEM_GRID.put("hoe", new String[]{
                NETHERITE_HOE, DIAMOND_HOE, GOLDEN_HOE, IRON_HOE, null, STONE_HOE, WOODEN_HOE, null});
        ITEM_GRID.put("helmet", new String[]{
                NETHERITE_HELMET, DIAMOND_HELMET, GOLDEN_HELMET, IRON_HELMET, CHAINMAIL_HELMET, null, null, LEATHER_HELMET});
        ITEM_GRID.put("chestplate", new String[]{
                NETHERITE_CHESTPLATE, DIAMOND_CHESTPLATE, GOLDEN_CHESTPLATE, IRON_CHESTPLATE, CHAINMAIL_CHESTPLATE, null, null, LEATHER_CHESTPLATE});
        ITEM_GRID.put("leggings", new String[]{
                NETHERITE_LEGGINGS, DIAMOND_LEGGINGS, GOLDEN_LEGGINGS, IRON_LEGGINGS, CHAINMAIL_LEGGINGS, null, null, LEATHER_LEGGINGS});
        ITEM_GRID.put("boots", new String[]{
                NETHERITE_BOOTS, DIAMOND_BOOTS, GOLDEN_BOOTS, IRON_BOOTS, CHAINMAIL_BOOTS, null, null, LEATHER_BOOTS});
    }

    /**
     * Enchantable items that have no material variants; shown as a flat row in the picker.
     *
     * <p>Shears, flint and steel, elytra and the rest are deliberately absent: their
     * enchantability is zero, so an enchanting table will not take them at all.
     */
    public static final List<String> SINGLE_ITEMS = Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(
            BOOK, BOW, CROSSBOW, TRIDENT, FISHING_ROD, TURTLE_HELMET
    )));

    public static boolean isArmor(String item) {
        return isHelmet(item) || isChestplate(item) || isLeggings(item) || isBoots(item);
    }

    public static boolean isHelmet(String item) {
        return item.endsWith("_helmet") && hasArmorMaterial(item);
    }

    public static boolean isChestplate(String item) {
        return item.endsWith("_chestplate") && hasArmorMaterial(item);
    }

    public static boolean isLeggings(String item) {
        return item.endsWith("_leggings") && hasArmorMaterial(item);
    }

    public static boolean isBoots(String item) {
        return item.endsWith("_boots") && hasArmorMaterial(item);
    }

    private static boolean hasArmorMaterial(String item) {
        return item.startsWith("leather_") || item.startsWith("chainmail_") || item.startsWith("iron_")
                || item.startsWith("golden_") || item.startsWith("diamond_") || item.startsWith("netherite_")
                || item.startsWith("turtle_");
    }

    public static boolean isSword(String item) {
        return item.endsWith("_sword") && hasToolMaterial(item);
    }

    public static boolean isAxe(String item) {
        return item.endsWith("_axe") && hasToolMaterial(item);
    }

    /** Matches vanilla's {@code DIGGER} enchantment category: pickaxe, shovel, hoe and axe. */
    public static boolean isTool(String item) {
        if (isAxe(item)) {
            return true;
        }
        return (item.endsWith("_pickaxe") || item.endsWith("_shovel") || item.endsWith("_hoe"))
                && hasToolMaterial(item);
    }

    private static boolean hasToolMaterial(String item) {
        return item.startsWith("wooden_") || item.startsWith("stone_") || item.startsWith("iron_")
                || item.startsWith("golden_") || item.startsWith("diamond_") || item.startsWith("netherite_");
    }

    public static boolean hasDurability(String item) {
        return isArmor(item) || isTool(item) || isSword(item)
                || BOW.equals(item) || CROSSBOW.equals(item) || TRIDENT.equals(item)
                || FISHING_ROD.equals(item) || SHEARS.equals(item) || FLINT_AND_STEEL.equals(item)
                || CARROT_ON_A_STICK.equals(item) || WARPED_FUNGUS_ON_A_STICK.equals(item)
                || ELYTRA.equals(item) || SHIELD.equals(item);
    }

    /**
     * {@code ItemStack#getItemEnchantability()} as the active model reports it, which in game
     * includes modded items. Zero means the enchanting table refuses the item entirely.
     */
    public static int getEnchantability(String item) {
        return item == null ? 0 : Models.get().enchantability(item);
    }

    /** Vanilla 1.16.5 {@code Item.getItemEnchantability()}, hard-coded. */
    public static int vanillaEnchantability(String item) {
        if (isArmor(item)) {
            if (item.startsWith("leather_")) return 15;
            if (item.startsWith("chainmail_")) return 12;
            if (item.startsWith("iron_")) return 9;
            if (item.startsWith("golden_")) return 25;
            if (item.startsWith("diamond_")) return 10;
            if (item.startsWith("netherite_")) return 15;
            if (item.startsWith("turtle_")) return 9;
        }
        if (isSword(item) || isTool(item)) {
            if (item.startsWith("wooden_")) return 15;
            if (item.startsWith("stone_")) return 5;
            if (item.startsWith("iron_")) return 14;
            if (item.startsWith("golden_")) return 22;
            if (item.startsWith("diamond_")) return 10;
            if (item.startsWith("netherite_")) return 15;
        }
        if (BOW.equals(item) || CROSSBOW.equals(item) || TRIDENT.equals(item)
                || FISHING_ROD.equals(item) || BOOK.equals(item)) {
            return 1;
        }
        return 0;
    }

    /** Every item the enchanting table will accept, in picker order, modded ones last. */
    public static List<String> enchantableItems() {
        return Models.get().enchantableItems();
    }

    /** The vanilla items the table accepts, in picker order. */
    public static List<String> vanillaEnchantableItems() {
        List<String> all = new ArrayList<>();
        for (String[] row : ITEM_GRID.values()) {
            for (String item : row) {
                if (item != null && vanillaEnchantability(item) > 0) {
                    all.add(item);
                }
            }
        }
        for (String item : SINGLE_ITEMS) {
            if (vanillaEnchantability(item) > 0) {
                all.add(item);
            }
        }
        return all;
    }

    /**
     * A readable category for an item, used to pick a representative item for an
     * enchantment: "sword", "pickaxe", "helmet", ..., or null for anything else.
     */
    public static String shapeOf(String item) {
        if (item == null) {
            return null;
        }
        String path = item.indexOf(':') >= 0 ? item.substring(item.indexOf(':') + 1) : item;
        for (String shape : ITEM_GRID.keySet()) {
            if (path.endsWith("_" + shape)) {
                return shape;
            }
        }
        return null;
    }
}
