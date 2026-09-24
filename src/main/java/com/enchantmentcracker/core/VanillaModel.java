package com.enchantmentcracker.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.enchantmentcracker.core.CrackEnchantments.*;

/**
 * Minecraft 1.16.5's enchantment data, hard-coded.
 *
 * <p>Ported from Earthcomputer/Hexicube's standalone EnchantmentCracker (MIT), with the
 * multi-version branches resolved to 1.16 behaviour. The weights and enchantability ranges
 * come from the individual {@code Enchantment} subclasses.
 *
 * <p>This is what the maths runs on before the game has started and in the unit tests. In
 * game it is replaced by a model that reads the live registries, which also covers modded
 * enchantments and items. The two agree exactly on vanilla content; the test harness checks
 * that against the real {@code EnchantmentHelper}.
 */
public final class VanillaModel implements EnchantModel {

    public static final VanillaModel INSTANCE = new VanillaModel();

    /** Every 1.16.5 enchantment, in registry order. Order matters: see {@link #tableCandidates}. */
    private static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            PROTECTION, FIRE_PROTECTION, FEATHER_FALLING, BLAST_PROTECTION, PROJECTILE_PROTECTION,
            RESPIRATION, AQUA_AFFINITY, THORNS, DEPTH_STRIDER, FROST_WALKER, BINDING_CURSE,
            SOUL_SPEED, SHARPNESS, SMITE, BANE_OF_ARTHROPODS, KNOCKBACK, FIRE_ASPECT, LOOTING,
            SWEEPING, EFFICIENCY, SILK_TOUCH, UNBREAKING, FORTUNE, POWER, PUNCH, FLAME, INFINITY,
            LUCK_OF_THE_SEA, LURE, LOYALTY, IMPALING, RIPTIDE, CHANNELING, MULTISHOT, QUICK_CHARGE,
            PIERCING, MENDING, VANISHING_CURSE));

    /** Treasure enchantments (and soul speed, which is also undiscoverable) never come out of a table. */
    private static final Set<String> TREASURE = new HashSet<>(Arrays.asList(
            FROST_WALKER, BINDING_CURSE, SOUL_SPEED, MENDING, VANISHING_CURSE));

    private static final List<String> TABLE;

    static {
        List<String> table = new ArrayList<>();
        for (String enchantment : ALL) {
            if (!TREASURE.contains(enchantment)) {
                table.add(enchantment);
            }
        }
        TABLE = Collections.unmodifiableList(table);
    }

    private final Map<String, List<String>> candidates = new ConcurrentHashMap<>();

    private VanillaModel() {
    }

    // ------------------------------------------------------------------ lists

    @Override
    public List<String> tableCandidates(String item) {
        return candidates.computeIfAbsent(item, key -> {
            List<String> list = new ArrayList<>();
            for (String enchantment : TABLE) {
                if (canApplyAtTable(enchantment, key)) {
                    list.add(enchantment);
                }
            }
            return Collections.unmodifiableList(list);
        });
    }

    @Override
    public List<String> allTableEnchantments() {
        return TABLE;
    }

    @Override
    public List<String> allEnchantments() {
        return ALL;
    }

    @Override
    public boolean knowsEnchantment(String enchantment) {
        return ALL.contains(enchantment);
    }

    @Override
    public List<String> enchantableItems() {
        return CrackItems.vanillaEnchantableItems();
    }

    // ------------------------------------------------------------------ items

    @Override
    public int enchantability(String item) {
        return CrackItems.vanillaEnchantability(item);
    }

    @Override
    public boolean isBook(String item) {
        return CrackItems.BOOK.equals(item);
    }

    // ------------------------------------------------------------ applicability

    /** {@code Enchantment#canApplyAtEnchantingTable}, or {@code isAllowedOnBooks} for books. */
    private static boolean canApplyAtTable(String enchantment, String item) {
        return canApply(enchantment, item, true);
    }

    @Override
    public boolean canApplyAtAnvil(String enchantment, String item) {
        return canApply(enchantment, item, false);
    }

    /**
     * @param primary {@code true} for the enchanting table's stricter check
     *                ({@code Enchantment#canApplyAtEnchantingTable}), {@code false} for anvils.
     */
    static boolean canApply(String enchantment, String item, boolean primary) {
        if (CrackItems.BOOK.equals(item)) {
            return true;
        }

        switch (enchantment) {
            case PROTECTION:
            case FIRE_PROTECTION:
            case BLAST_PROTECTION:
            case PROJECTILE_PROTECTION:
                return CrackItems.isArmor(item);
            case THORNS:
                return primary ? CrackItems.isChestplate(item) : CrackItems.isArmor(item);
            case FEATHER_FALLING:
            case DEPTH_STRIDER:
            case FROST_WALKER:
            case SOUL_SPEED:
                return CrackItems.isBoots(item);
            case RESPIRATION:
            case AQUA_AFFINITY:
                return CrackItems.isHelmet(item);
            case BINDING_CURSE:
                return CrackItems.isArmor(item) || CrackItems.CARVED_PUMPKIN.equals(item)
                        || CrackItems.ELYTRA.equals(item) || CrackItems.PLAYER_HEAD.equals(item);
            case SHARPNESS:
            case SMITE:
            case BANE_OF_ARTHROPODS:
                return CrackItems.isSword(item) || (!primary && CrackItems.isAxe(item));
            case FIRE_ASPECT:
            case KNOCKBACK:
            case LOOTING:
            case SWEEPING:
                return CrackItems.isSword(item);
            case EFFICIENCY:
                return CrackItems.isTool(item) || (!primary && CrackItems.SHEARS.equals(item));
            case SILK_TOUCH:
            case FORTUNE:
                return CrackItems.isTool(item);
            case POWER:
            case PUNCH:
            case FLAME:
            case INFINITY:
                return CrackItems.BOW.equals(item);
            case LUCK_OF_THE_SEA:
            case LURE:
                return CrackItems.FISHING_ROD.equals(item);
            case UNBREAKING:
            case MENDING:
                return CrackItems.hasDurability(item);
            case VANISHING_CURSE:
                return CrackItems.hasDurability(item) || CrackItems.CARVED_PUMPKIN.equals(item)
                        || CrackItems.PLAYER_HEAD.equals(item);
            case LOYALTY:
            case IMPALING:
            case RIPTIDE:
            case CHANNELING:
                return CrackItems.TRIDENT.equals(item);
            case MULTISHOT:
            case QUICK_CHARGE:
            case PIERCING:
                return CrackItems.CROSSBOW.equals(item);
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ numbers

    @Override
    public int minLevel(String enchantment) {
        return 1;
    }

    @Override
    public int maxLevel(String enchantment) {
        switch (enchantment) {
            case SHARPNESS:
            case SMITE:
            case BANE_OF_ARTHROPODS:
            case EFFICIENCY:
            case POWER:
            case IMPALING:
                return 5;
            case PROTECTION:
            case FIRE_PROTECTION:
            case BLAST_PROTECTION:
            case PROJECTILE_PROTECTION:
            case FEATHER_FALLING:
            case PIERCING:
                return 4;
            case THORNS:
            case DEPTH_STRIDER:
            case RESPIRATION:
            case LOOTING:
            case SWEEPING:
            case FORTUNE:
            case LUCK_OF_THE_SEA:
            case LURE:
            case UNBREAKING:
            case LOYALTY:
            case RIPTIDE:
            case QUICK_CHARGE:
            case SOUL_SPEED:
                return 3;
            case FROST_WALKER:
            case KNOCKBACK:
            case FIRE_ASPECT:
            case PUNCH:
                return 2;
            case AQUA_AFFINITY:
            case BINDING_CURSE:
            case SILK_TOUCH:
            case FLAME:
            case INFINITY:
            case MENDING:
            case VANISHING_CURSE:
            case CHANNELING:
            case MULTISHOT:
                return 1;
            default:
                throw new IllegalArgumentException("Unknown enchantment: " + enchantment);
        }
    }

    @Override
    public int minEnchantability(String enchantment, int level) {
        switch (enchantment) {
            case PROTECTION:            return 1 + (level - 1) * 11;
            case FIRE_PROTECTION:       return 10 + (level - 1) * 8;
            case FEATHER_FALLING:       return 5 + (level - 1) * 6;
            case BLAST_PROTECTION:      return 5 + (level - 1) * 8;
            case PROJECTILE_PROTECTION: return 3 + (level - 1) * 6;
            case RESPIRATION:           return level * 10;
            case AQUA_AFFINITY:         return 1;
            case THORNS:                return 10 + (level - 1) * 20;
            case DEPTH_STRIDER:         return level * 10;
            case FROST_WALKER:          return level * 10;
            case BINDING_CURSE:         return 25;
            case SHARPNESS:             return 1 + (level - 1) * 11;
            case SMITE:                 return 5 + (level - 1) * 8;
            case BANE_OF_ARTHROPODS:    return 5 + (level - 1) * 8;
            case KNOCKBACK:             return 5 + (level - 1) * 20;
            case FIRE_ASPECT:           return 10 + (level - 1) * 20;
            case LOOTING:               return 15 + (level - 1) * 9;
            case SWEEPING:              return 5 + (level - 1) * 9;
            case EFFICIENCY:            return 1 + (level - 1) * 10;
            case SILK_TOUCH:            return 15;
            case UNBREAKING:            return 5 + (level - 1) * 8;
            case FORTUNE:               return 15 + (level - 1) * 9;
            case POWER:                 return 1 + (level - 1) * 10;
            case PUNCH:                 return 12 + (level - 1) * 20;
            case FLAME:                 return 20;
            case INFINITY:              return 20;
            case LUCK_OF_THE_SEA:       return 15 + (level - 1) * 9;
            case LURE:                  return 15 + (level - 1) * 9;
            case MENDING:               return level * 25;
            case VANISHING_CURSE:       return 25;
            case LOYALTY:               return 5 + level * 7;
            case IMPALING:              return 1 + (level - 1) * 8;
            case RIPTIDE:               return 10 + level * 7;
            case CHANNELING:            return 25;
            case MULTISHOT:             return 20;
            case QUICK_CHARGE:          return 12 + (level - 1) * 20;
            case PIERCING:              return 1 + (level - 1) * 10;
            case SOUL_SPEED:            return level * 10;
            default:
                throw new IllegalArgumentException("Unknown enchantment: " + enchantment);
        }
    }

    /**
     * Upper bounds as 1.16.5 has them. Many are {@code super.getMinEnchantability(level) + 50},
     * i.e. {@code 51 + 10 * level}; the standalone tool carried older numbers for those. None
     * of the differences fall inside the power range a vanilla table can roll (at most ~49),
     * which is why the two agreed on every roll, but these are the real values.
     */
    @Override
    public int maxEnchantability(String enchantment, int level) {
        switch (enchantment) {
            case PROTECTION:            return 1 + level * 11;
            case FIRE_PROTECTION:       return 10 + level * 8;
            case FEATHER_FALLING:       return 5 + level * 6;
            case BLAST_PROTECTION:      return 5 + level * 8;
            case PROJECTILE_PROTECTION: return 3 + level * 6;
            case RESPIRATION:           return 30 + level * 10;
            case AQUA_AFFINITY:         return 41;
            case THORNS:                return 51 + level * 10;
            case DEPTH_STRIDER:         return 15 + level * 10;
            case FROST_WALKER:          return 15 + level * 10;
            case BINDING_CURSE:         return 50;
            case SHARPNESS:             return 21 + (level - 1) * 11;
            case SMITE:                 return 25 + (level - 1) * 8;
            case BANE_OF_ARTHROPODS:    return 25 + (level - 1) * 8;
            case KNOCKBACK:             return 51 + level * 10;
            case FIRE_ASPECT:           return 51 + level * 10;
            case LOOTING:               return 51 + level * 10;
            case SWEEPING:              return 20 + (level - 1) * 9;
            case EFFICIENCY:            return 51 + level * 10;
            case SILK_TOUCH:            return 51 + level * 10;
            case UNBREAKING:            return 51 + level * 10;
            case FORTUNE:               return 51 + level * 10;
            case POWER:                 return 16 + (level - 1) * 10;
            case PUNCH:                 return 37 + (level - 1) * 20;
            case FLAME:                 return 50;
            case INFINITY:              return 50;
            case LUCK_OF_THE_SEA:       return 51 + level * 10;
            case LURE:                  return 51 + level * 10;
            case MENDING:               return level * 25 + 50;
            case VANISHING_CURSE:       return 50;
            case LOYALTY:               return 50;
            case IMPALING:              return 21 + (level - 1) * 8;
            case RIPTIDE:               return 50;
            case CHANNELING:            return 50;
            case MULTISHOT:             return 50;
            case QUICK_CHARGE:          return 50;
            case PIERCING:              return 50;
            case SOUL_SPEED:            return 15 + level * 10;
            default:
                throw new IllegalArgumentException("Unknown enchantment: " + enchantment);
        }
    }

    @Override
    public int weight(String enchantment) {
        switch (enchantment) {
            case PROTECTION:
            case SHARPNESS:
            case EFFICIENCY:
            case POWER:
            case PIERCING:
                return 10;
            case FIRE_PROTECTION:
            case FEATHER_FALLING:
            case PROJECTILE_PROTECTION:
            case SMITE:
            case BANE_OF_ARTHROPODS:
            case KNOCKBACK:
            case UNBREAKING:
            case LOYALTY:
            case QUICK_CHARGE:
                return 5;
            case BLAST_PROTECTION:
            case RESPIRATION:
            case AQUA_AFFINITY:
            case DEPTH_STRIDER:
            case FROST_WALKER:
            case FIRE_ASPECT:
            case LOOTING:
            case SWEEPING:
            case FORTUNE:
            case PUNCH:
            case FLAME:
            case LUCK_OF_THE_SEA:
            case LURE:
            case MENDING:
            case IMPALING:
            case RIPTIDE:
            case MULTISHOT:
                return 2;
            case THORNS:
            case BINDING_CURSE:
            case SILK_TOUCH:
            case INFINITY:
            case VANISHING_CURSE:
            case CHANNELING:
            case SOUL_SPEED:
                return 1;
            default:
                throw new IllegalArgumentException("Unknown enchantment: " + enchantment);
        }
    }

    // ------------------------------------------------------------ compatibility

    private static final Set<String> DAMAGE = new HashSet<>(Arrays.asList(SHARPNESS, SMITE, BANE_OF_ARTHROPODS));
    private static final Set<String> PROTECTIONS = new HashSet<>(
            Arrays.asList(PROTECTION, BLAST_PROTECTION, FIRE_PROTECTION, PROJECTILE_PROTECTION));

    @Override
    public boolean compatible(String a, String b) {
        return oneWay(a, b) && oneWay(b, a);
    }

    private static boolean oneWay(String a, String b) {
        if (a.equals(b)) {
            return false;
        }
        if (a.equals(INFINITY) && b.equals(MENDING)) {
            return false;
        }
        if (DAMAGE.contains(a) && DAMAGE.contains(b)) {
            return false;
        }
        if (PROTECTIONS.contains(a) && PROTECTIONS.contains(b)) {
            return false;
        }
        if (a.equals(DEPTH_STRIDER) && b.equals(FROST_WALKER)) {
            return false;
        }
        if (a.equals(SILK_TOUCH) && (b.equals(LOOTING) || b.equals(FORTUNE) || b.equals(LUCK_OF_THE_SEA))) {
            return false;
        }
        if (a.equals(RIPTIDE) && (b.equals(LOYALTY) || b.equals(CHANNELING))) {
            return false;
        }
        return !(a.equals(MULTISHOT) && b.equals(PIERCING));
    }
}
