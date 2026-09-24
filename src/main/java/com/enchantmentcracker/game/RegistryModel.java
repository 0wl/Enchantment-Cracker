package com.enchantmentcracker.game;

import com.enchantmentcracker.core.CrackItems;
import com.enchantmentcracker.core.EnchantModel;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.registry.Registry;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EnchantModel} read off the live game registries, so modded enchantments and items
 * take part exactly as the server sees them.
 *
 * <p>Built as a snapshot on the client thread: the enchantment list in registry id order,
 * which is the order the table walks it in (and which a server re-syncs on join, hence the
 * rebuild then), plus each enchantment's levels and weights. Per-item facts are worked out
 * on first use and cached; those only call side-effect-free {@code Enchantment} and
 * {@code Item} methods, so worker threads may trigger them.
 *
 * <p>When Apotheosis's enchantment module is on, its per-enchantment level and power rules
 * replace the vanilla ones, since that is what its table and anvil use.
 */
public final class RegistryModel implements EnchantModel {

    private final List<String> all = new ArrayList<>();
    private final List<String> table = new ArrayList<>();
    private final Map<String, Enchantment> byId = new HashMap<>();
    private final Map<String, Integer> minLevel = new HashMap<>();
    private final Map<String, Integer> maxLevel = new HashMap<>();
    private final Map<String, Integer> weight = new HashMap<>();
    private final boolean apotheosis;
    private final int apotheosisMaxPower;

    private final Map<String, List<String>> candidates = new ConcurrentHashMap<>();
    private final Map<String, Integer> enchantability = new ConcurrentHashMap<>();
    private final Map<String, Boolean> compatibility = new ConcurrentHashMap<>();
    private final Map<String, Boolean> anvil = new ConcurrentHashMap<>();
    private volatile List<String> items;

    private RegistryModel() {
        apotheosis = Apotheosis.isActive();
        apotheosisMaxPower = apotheosis ? Apotheosis.maxPower() : 0;
        // Registry.ENCHANTMENT iterates in id order, the order EnchantmentHelper uses.
        for (Enchantment enchantment : Registry.field_212628_q) {
            String id = Mc.idOf(enchantment.getRegistryName());
            if (id == null) {
                continue;
            }
            all.add(id);
            byId.put(id, enchantment);
            int[] range = apotheosis ? Apotheosis.levelRange(enchantment) : null;
            minLevel.put(id, range != null ? range[0] : enchantment.func_77319_d());   // getMinLevel
            maxLevel.put(id, range != null ? range[1] : enchantment.func_77325_b());   // getMaxLevel
            weight.put(id, enchantment.func_77324_c().func_185270_a());                // getRarity().getWeight()
            if (isTableEnchantment(enchantment)) {
                table.add(id);
            }
        }
    }

    /** Builds a fresh snapshot. Call on the client thread. */
    public static RegistryModel build() {
        return new RegistryModel();
    }

    /** Not treasure, and (in vanilla) discoverable: the same filter the table applies. */
    private boolean isTableEnchantment(Enchantment enchantment) {
        if (enchantment.func_185261_e()) { // isTreasureEnchantment
            return false;
        }
        // canGenerateInLoot (soul speed says no). Apotheosis's replacement list ignores it.
        return apotheosis || enchantment.func_230310_i_();
    }

    // ------------------------------------------------------------------ lists

    @Override
    public List<String> tableCandidates(String item) {
        return candidates.computeIfAbsent(item, key -> {
            ItemStack stack = GameTables.stack(key);
            if (stack.func_190926_b()) {
                return Collections.emptyList();
            }
            boolean book = stack.func_77973_b() == Items.field_151122_aG;
            List<String> list = new ArrayList<>();
            for (String id : table) {
                Enchantment enchantment = byId.get(id);
                boolean applies = enchantment.canApplyAtEnchantingTable(stack)
                        || (book && enchantment.isAllowedOnBooks())
                        // Apotheosis also lets items opt in themselves (its typed tomes)
                        || (apotheosis && stack.func_77973_b().canApplyAtEnchantingTable(stack, enchantment));
                if (applies) {
                    list.add(id);
                }
            }
            return Collections.unmodifiableList(list);
        });
    }

    @Override
    public List<String> allTableEnchantments() {
        return Collections.unmodifiableList(table);
    }

    @Override
    public List<String> allEnchantments() {
        return Collections.unmodifiableList(all);
    }

    @Override
    public boolean knowsEnchantment(String enchantment) {
        return byId.containsKey(enchantment);
    }

    /**
     * Everything the table will take: the familiar vanilla items first in picker order, then
     * everything else (modded tools, armour, books...) grouped by mod.
     */
    @Override
    public List<String> enchantableItems() {
        List<String> cached = items;
        if (cached != null) {
            return cached;
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String id : CrackItems.vanillaEnchantableItems()) {
            if (enchantability(id) > 0) {
                ordered.add(id);
            }
        }
        List<String> modded = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            String id = Mc.idOf(item);
            if (id == null || ordered.contains(id)) {
                continue;
            }
            try {
                if (enchantability(id) > 0) {
                    modded.add(id);
                }
            } catch (Throwable ignored) {
                // a mod's item that throws when asked is not one we can plan for
            }
        }
        modded.sort((a, b) -> {
            int byMod = Mc.namespaceOf(a).compareTo(Mc.namespaceOf(b));
            return byMod != 0 ? byMod : a.compareTo(b);
        });
        ordered.addAll(modded);
        cached = Collections.unmodifiableList(new ArrayList<>(ordered));
        items = cached;
        return cached;
    }

    // ------------------------------------------------------------------ items

    @Override
    public int enchantability(String item) {
        return enchantability.computeIfAbsent(item, key -> {
            ItemStack stack = GameTables.stack(key);
            if (stack.func_190926_b()) {
                return 0;
            }
            // The table only takes what isEnchantable() allows, then reads Forge's enchantability.
            boolean takes = apotheosis ? stack.func_77973_b().func_77616_k(stack) : stack.func_77956_u();
            return takes ? Math.max(0, stack.getItemEnchantability()) : 0;
        });
    }

    @Override
    public boolean isBook(String item) {
        return CrackItems.BOOK.equals(item);
    }

    // ------------------------------------------------------------------ numbers

    @Override
    public int minLevel(String enchantment) {
        Integer value = minLevel.get(enchantment);
        return value == null ? 1 : value;
    }

    @Override
    public int maxLevel(String enchantment) {
        Integer value = maxLevel.get(enchantment);
        return value == null ? 1 : value;
    }

    @Override
    public int minEnchantability(String enchantment, int level) {
        Enchantment e = byId.get(enchantment);
        if (e == null) {
            return Integer.MAX_VALUE;
        }
        return apotheosis ? Apotheosis.minPower(e, level) : e.func_77321_a(level); // getMinEnchantability
    }

    @Override
    public int maxEnchantability(String enchantment, int level) {
        Enchantment e = byId.get(enchantment);
        if (e == null) {
            return Integer.MIN_VALUE;
        }
        return apotheosis ? Apotheosis.maxPower(e, level) : e.func_223551_b(level); // getMaxEnchantability
    }

    @Override
    public int weight(String enchantment) {
        Integer value = weight.get(enchantment);
        return value == null ? 0 : value;
    }

    @Override
    public boolean compatible(String a, String b) {
        return compatibility.computeIfAbsent(a + '|' + b, key -> {
            Enchantment ea = byId.get(a);
            Enchantment eb = byId.get(b);
            return ea != null && eb != null && ea.func_191560_c(eb); // isCompatibleWith, checks both ways
        });
    }

    @Override
    public boolean canApplyAtAnvil(String enchantment, String item) {
        return anvil.computeIfAbsent(enchantment + '|' + item, key -> {
            Enchantment e = byId.get(enchantment);
            ItemStack stack = GameTables.stack(item);
            if (e == null || stack.func_190926_b()) {
                return false;
            }
            // Anything goes onto a book; otherwise Enchantment#canApply, the anvil's check.
            return stack.func_77973_b() == Items.field_151122_aG || e.func_92089_a(stack);
        });
    }

    @Override
    public int maxTableLevel(String enchantment, String item) {
        if (!apotheosis) {
            return EnchantModel.super.maxTableLevel(enchantment, item);
        }
        if (enchantability(item) <= 0 || !tableCandidates(item).contains(enchantment)) {
            return 0;
        }
        // An Apotheosis table's power tops out at 4x the Eterna ceiling; the level is the
        // highest whose power window starts below that.
        for (int level = maxLevel(enchantment); level >= minLevel(enchantment); level--) {
            if (minEnchantability(enchantment, level) <= apotheosisMaxPower) {
                return level;
            }
        }
        return 0;
    }

    public boolean isApotheosis() {
        return apotheosis;
    }
}
