package com.enchantmentcracker.core;

import java.util.BitSet;
import java.util.List;

/**
 * What a table can actually hand out, as opposed to what any table ever could.
 *
 * <p>An enchantment's level comes from the "power" the table rolls with: the slot's level
 * requirement, fuzzed (vanilla: by the item's enchantability and +/-15%; Apotheosis: by Quanta
 * and Rectification). The table then gives each enchantment the highest level whose power
 * window contains that power. So the powers a table can roll decide which levels are
 * possible: a table whose top slot is 30 cannot give Sharpness VII, however long you drop items.
 *
 * <p>Each {@link TableSetup} reports its powers for an item; the planner may search several
 * setups (every shelf count up to what you have), so their powers are pooled.
 */
public final class TableReach {

    private TableReach() {
    }

    /** Every power the given setups can roll for {@code item}, or null when some setup cannot say. */
    public static BitSet powers(List<TableSetup> setups, String item) {
        if (setups == null || setups.isEmpty()) {
            return null;
        }
        BitSet all = new BitSet();
        for (TableSetup setup : setups) {
            BitSet one = setup.powers(item);
            if (one == null) {
                return null;
            }
            all.or(one);
        }
        return all;
    }

    /**
     * The highest level of {@code enchantment} a table rolling these powers can put on
     * {@code item}, 0 when none. With {@code powers == null} (table not known yet) this is the
     * most any table could give.
     */
    public static int maxLevel(String enchantment, String item, BitSet powers) {
        int ceiling = CrackEnchantments.getMaxLevelInTable(enchantment, item);
        if (ceiling <= 0 || powers == null) {
            return ceiling;
        }
        EnchantModel model = CrackEnchantments.model();
        int best = 0;
        for (int power = powers.nextSetBit(0); power >= 0; power = powers.nextSetBit(power + 1)) {
            int level = levelAt(model, enchantment, power);
            if (level > best) {
                best = level;
                if (best >= ceiling) {
                    break;
                }
            }
        }
        return best;
    }

    /** The highest power in the set, 0 when empty. */
    public static int topPower(BitSet powers) {
        return powers == null || powers.isEmpty() ? 0 : powers.length() - 1;
    }

    /** The level the table gives at this power: the highest whose window contains it, else 0. */
    static int levelAt(EnchantModel model, String enchantment, int power) {
        for (int level = model.maxLevel(enchantment); level >= model.minLevel(enchantment); level--) {
            if (power >= model.minEnchantability(enchantment, level)
                    && power <= model.maxEnchantability(enchantment, level)) {
                return level;
            }
        }
        return 0;
    }

    /**
     * Powers a vanilla table with this many shelves can roll for an item of this
     * enchantability: every level requirement the three slots can show
     * ({@link CrackEnchantments#calcEnchantmentTableLevel}), each plus 1 and two rolls of
     * 0..enchantability/4, then +/-15% ({@link CrackEnchantments#addRandomEnchantments}).
     */
    public static BitSet vanillaPowers(int shelves, int enchantability) {
        BitSet powers = new BitSet();
        if (enchantability <= 0) {
            return powers;
        }
        shelves = Math.max(0, Math.min(15, shelves));
        BitSet levels = new BitSet();
        for (int r8 = 0; r8 < 8; r8++) {
            for (int rs = 0; rs <= shelves; rs++) {
                int base = r8 + 1 + (shelves >> 1) + rs;
                int[] slots = {Math.max(base / 3, 1), base * 2 / 3 + 1, Math.max(base, shelves * 2)};
                for (int slot = 0; slot < 3; slot++) {
                    if (slots[slot] >= slot + 1) {
                        levels.set(slots[slot]);
                    }
                }
            }
        }
        int bonus = enchantability / 4;
        for (int level = levels.nextSetBit(1); level >= 0; level = levels.nextSetBit(level + 1)) {
            for (int x = level + 1; x <= level + 1 + 2 * bonus; x++) {
                int low = Math.max(1, Math.round((float) x - (float) x * 0.15F));
                int high = Math.max(1, Math.round((float) x + (float) x * 0.15F));
                powers.set(low, high + 1);
            }
        }
        return powers;
    }
}
