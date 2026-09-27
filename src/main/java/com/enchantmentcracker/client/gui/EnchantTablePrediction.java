package com.enchantmentcracker.client.gui;

import com.enchantmentcracker.client.ModSettings;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackItems;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.TableSetup;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.TableWatcher;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.inventory.container.EnchantmentContainer;

import java.util.List;

/**
 * Writes the real enchantments of all three slots straight onto the enchanting table
 * screen, instead of the one scrambled hint the game gives you.
 *
 * <p>This needs no cracking at all: the XP seed arrives with the screen, and the item and
 * table setup are both known, which is everything the generator uses. If the table's own
 * numbers disagree with the prediction (a mod we do not know changes enchanting), the
 * overlay says so rather than showing guesses.
 */
public final class EnchantTablePrediction {

    private EnchantTablePrediction() {
    }

    public static void toggle() {
        ModSettings.tablePrediction = !ModSettings.tablePrediction;
        ModSettings.save();
    }

    public static boolean isEnabled() {
        return ModSettings.tablePrediction;
    }

    public static void render(ContainerScreen<?> screen, EnchantmentContainer container,
                              MatrixStack ms, int mouseX, int mouseY) {
        if (!ModSettings.tablePrediction) {
            return;
        }

        CrackerState state = CrackerState.get();
        // Whole in your own world; on a server worked out from what the table shows, else null.
        Integer xpSeed = TableWatcher.currentXpSeed();
        TableSetup setup = state.getTableSetup();
        String problem = state.getTableProblem();

        String tableItem = TableWatcher.itemIdIn(container);
        boolean hypothetical = tableItem == null || CrackItems.getEnchantability(tableItem) <= 0;
        String item = hypothetical ? state.getSelectedItem() : tableItem;

        int width = Math.max(screen.getXSize() + 90, 280);
        int height = 58;
        int left = screen.getGuiLeft() + (screen.getXSize() - width) / 2;
        int screenHeight = screen.field_230709_l_; // Screen.height
        // Prefer sitting under the table GUI, but on a short screen slide up and overlap it
        // rather than drawing off the bottom edge.
        int top = Math.min(screen.getGuiTop() + screen.getYSize() + 4, screenHeight - height - 2);
        top = Math.max(2, top);
        left = Math.max(2, left);

        Theme.darkPanel(ms, left, top, width, height);

        String where = setup == null ? "table ?" : setup.describe();
        String header = "XP seed " + (xpSeed != null ? PlayerSeed.formatXpSeed(xpSeed) : state.getTableXpSeedText())
                + "   " + where;
        Mc.shadowText(ms, Mc.trim(header, width - 10), left + 5, top + 4, 0xFFFFFF55);
        if (hypothetical && setup != null) {
            String note = "preview: " + Mc.itemName(item);
            Mc.shadowText(ms, Mc.trim(note, width - 10), left + 5, top + 46, 0xFFAAAAAA);
        }

        // On a server, before the seed is pinned down: a few seeds may still fit, and for many
        // slots they all agree on what the slot gives. Show those.
        int[] fits = xpSeed == null && !hypothetical ? state.getPartialSet() : null;
        if (setup != null && fits != null && fits.length <= CONSENSUS_LIMIT) {
            renderConsensus(ms, fits, setup, item, left, top, width);
            return;
        }

        if (setup == null || problem != null || xpSeed == null) {
            int lineY = top + 17;
            for (String line : Theme.wrap(problem == null ? "Cannot read this table yet." : problem, width - 10)) {
                if (lineY > top + height - 10) {
                    break;
                }
                Mc.shadowText(ms, line, left + 5, lineY, 0xFFFF7070);
                lineY += 10;
            }
            return;
        }

        EnchantCalculator.SlotPreview[] slots = EnchantCalculator.preview(xpSeed, setup, item);
        for (int slot = 0; slot < 3; slot++) {
            int rowY = top + 16 + slot * 10;
            EnchantCalculator.SlotPreview preview = slots[slot];
            Mc.shadowText(ms, (slot + 1) + ")", left + 5, rowY, 0xFFB8B8B8);

            if (preview.isEmpty()) {
                Mc.shadowText(ms, "not available", left + 20, rowY, 0xFFB0B0B0);
                continue;
            }
            String level = preview.levelRequirement + "lv";
            Mc.shadowText(ms, level, left + 20, rowY, 0xFF80FF80);
            Mc.shadowText(ms, Mc.trim(describe(preview.enchantments), width - 62),
                    left + 52, rowY, 0xFFFFFFFF);
        }
    }

    /** Most seeds worth rolling for a "they all agree" preview. */
    private static final int CONSENSUS_LIMIT = 256;
    private static String consensusKey;
    private static String[] consensusRows;

    /** Each slot's offer if every seed still possible gives the same one; else how many differ. */
    private static void renderConsensus(MatrixStack ms, int[] fits, TableSetup setup, String item,
                                        int left, int top, int width) {
        String key = java.util.Arrays.toString(fits) + item + setup.describe();
        if (!key.equals(consensusKey)) {
            consensusKey = key;
            consensusRows = new String[3];
            java.util.List<java.util.Set<java.util.List<String>>> outcomes = new java.util.ArrayList<>();
            for (int slot = 0; slot < 3; slot++) {
                outcomes.add(new java.util.LinkedHashSet<>());
            }
            int[] levels = null;
            for (int seed : fits) {
                EnchantCalculator.SlotPreview[] slots = EnchantCalculator.preview(seed, setup, item);
                levels = new int[]{slots[0].levelRequirement, slots[1].levelRequirement, slots[2].levelRequirement};
                for (int slot = 0; slot < 3; slot++) {
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (EnchantmentInstance e : slots[slot].enchantments) {
                        names.add(e.enchantment + " " + e.level);
                    }
                    java.util.Collections.sort(names);
                    outcomes.get(slot).add(names);
                }
                if (outcomes.get(0).size() > 1 && outcomes.get(1).size() > 1 && outcomes.get(2).size() > 1) {
                    break; // nothing left to agree on
                }
            }
            for (int slot = 0; slot < 3; slot++) {
                java.util.Set<java.util.List<String>> set = outcomes.get(slot);
                if (levels == null || levels[slot] == 0) {
                    consensusRows[slot] = null;
                } else if (set.size() == 1) {
                    EnchantCalculator.SlotPreview one = EnchantCalculator.preview(fits[0], setup, item)[slot];
                    consensusRows[slot] = levels[slot] + "lv|" + describe(one.enchantments);
                } else {
                    consensusRows[slot] = levels[slot] + "lv|? not settled yet (several seeds fit)";
                }
            }
        }
        for (int slot = 0; slot < 3; slot++) {
            int rowY = top + 16 + slot * 10;
            Mc.shadowText(ms, (slot + 1) + ")", left + 5, rowY, 0xFFB8B8B8);
            String row = consensusRows[slot];
            if (row == null) {
                Mc.shadowText(ms, "not available", left + 20, rowY, 0xFFB0B0B0);
                continue;
            }
            int bar = row.indexOf('|');
            Mc.shadowText(ms, row.substring(0, bar), left + 20, rowY, 0xFF80FF80);
            boolean settled = !row.startsWith("?", bar + 1);
            Mc.shadowText(ms, Mc.trim(row.substring(bar + 1), width - 62), left + 52, rowY,
                    settled ? 0xFFFFFFFF : 0xFFFFB070);
        }
        Mc.shadowText(ms, Mc.trim(fits.length + " XP seeds still fit; enchant once to lock the seed.", width - 10),
                left + 5, top + 46, 0xFFAAAAAA);
    }

    static String describe(List<EnchantmentInstance> enchantments) {
        if (enchantments == null || enchantments.isEmpty()) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        for (EnchantmentInstance instance : enchantments) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(Mc.enchantmentName(instance.enchantment, instance.level));
        }
        return sb.toString();
    }
}
