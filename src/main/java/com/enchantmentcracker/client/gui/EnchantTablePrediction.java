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
        int xpSeed = container.func_217005_f(); // getXPSeed()
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
        String header = "XP seed " + PlayerSeed.formatXpSeed(xpSeed) + "   " + where;
        Mc.shadowText(ms, Mc.trim(header, width - 10), left + 5, top + 4, 0xFFFFFF55);
        if (hypothetical && setup != null) {
            String note = "preview: " + Mc.itemName(item);
            Mc.shadowText(ms, Mc.trim(note, width - 10), left + 5, top + 46, 0xFFAAAAAA);
        }

        if (setup == null || problem != null) {
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
