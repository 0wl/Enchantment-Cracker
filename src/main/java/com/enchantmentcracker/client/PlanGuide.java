package com.enchantmentcracker.client;

import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.game.AutoDropper;
import com.enchantmentcracker.game.AutoLocker;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.TableWatcher;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * The "Next:" line under the enchanting table: what to do right now, from locking the seed to
 * the real enchantment, with the thing to use highlighted (an inventory item, one of the three
 * enchant buttons, or the Lock seed / Auto drop button beside the table).
 */
public final class PlanGuide {

    /** Which of the buttons beside the table to point at. */
    public enum Button { NONE, LOCK, DROP }

    /** One instruction. */
    public static final class Step {
        public final String text;
        /** An item to highlight in the inventory, or null. */
        public final String item;
        /** An enchant button (0-2) to highlight, or -1. */
        public final int enchantSlot;
        public final Button button;
        /** Something went off plan: shown in orange. */
        public final boolean warn;

        Step(String text, String item, int enchantSlot, Button button, boolean warn) {
            this.text = text;
            this.item = item;
            this.enchantSlot = enchantSlot;
            this.button = button;
            this.warn = warn;
        }
    }

    private static Step say(String text) {
        return new Step(text, null, -1, Button.NONE, false);
    }

    private PlanGuide() {
    }

    /** What to do next at this table. */
    public static Step next(CrackerState state, EnchantmentContainer table) {
        String inTable = TableWatcher.itemIdIn(table);
        int[] levels = table.field_75167_g; // enchantLevels
        boolean offered = levels[0] != 0 || levels[1] != 0 || levels[2] != 0;
        if (!state.isLocked()) {
            if (AutoLocker.isRunning()) {
                return say("Locking the seed: enchanting books in slot 1...");
            }
            if (Mc.integratedServer() != null) {
                return say("Reading the seed from your world...");
            }
            return new Step("First lock the seed: click Lock seed (it enchants a couple of books in slot 1; "
                    + "needs books, lapis and a few levels).", "book", -1, Button.LOCK, false);
        }
        if (ClientEvents.isReplanning()) {
            return new Step("Planning again for your table as it stands...", null, -1, Button.NONE, true);
        }
        EnchantCalculator.Result plan = state.getPlan();
        CrackerState.PlanStage stage = state.getPlanStage();
        if (plan == null || plan.item == null || stage == CrackerState.PlanStage.NONE) {
            return say(inTable != null && offered
                    ? "Seed locked. Click Cracker to plan an enchantment for your " + Mc.itemName(inTable) + "."
                    : "Seed locked. Put the item you want to enchant in, then click Cracker to plan.");
        }
        String item = Mc.itemName(plan.item);
        switch (stage) {
            case DROPPING: {
                int left = state.getDropsRemaining();
                if (AutoDropper.isRunning()) {
                    return new Step("Dropping... " + AutoDropper.getRemaining() + " to go.", null, -1, Button.DROP, false);
                }
                String junk = AutoDropper.getJunkItem();
                String what = junk == null ? "item" + (left == 1 ? "" : "s") : Mc.itemName(junk);
                return new Step("Drop " + left + " more " + what + ": click Auto drop, or press Q with junk in hand."
                        + (junk == null && ModSettings.autoDrop ? " (Pick junk in your inventory first.)" : ""),
                        junk, -1, Button.DROP, false);
            }
            case OVERSHOT:
                return new Step("One drop too many: planning again...", null, -1, Button.NONE, true);
            case DUMMY:
                if (inTable == null) {
                    return new Step("Now the dummy: put a book (or anything cheap) in the table.", "book", -1,
                            Button.NONE, false);
                }
                if (inTable.equals(plan.item) && !"book".equals(plan.item)) {
                    return new Step("This step is the dummy: take your " + item + " out and put a book in.", "book", -1,
                            Button.NONE, true);
                }
                return offered
                        ? new Step("Click slot 1 to enchant the dummy.", null, 0, Button.NONE, false)
                        : new Step("Take that out and put a book in for the dummy.", "book", -1, Button.NONE, false);
            case CHECKING:
                return plan.item.equals(inTable)
                        ? say("Confirming the seed...")
                        : new Step("Put your " + item + " in so the cracker can confirm the seed.", plan.item, -1,
                        Button.NONE, false);
            case FINAL: {
                if (!plan.item.equals(inTable)) {
                    return new Step(inTable != null && offered
                            ? "Take the " + Mc.itemName(inTable) + " out and put your " + item + " in."
                            : "Put your " + item + " in the table.", plan.item, -1, Button.NONE, false);
                }
                String wrong = com.enchantmentcracker.client.gui.tabs.PlanTab.wrongItemWarning(state, plan);
                if (wrong != null) {
                    return new Step(wrong, null, -1, Button.NONE, true);
                }
                return new Step("Click slot " + (plan.slot + 1) + ": " + describe(plan.enchantments) + ".", null,
                        plan.slot, Button.NONE, false);
            }
            case OFF_COURSE:
                return new Step("The seed went off course: planning again...", null, -1, Button.NONE, true);
            case DONE:
                return say("Done! Take your " + item + " out. Plan the next one with Cracker.");
            default:
                return say("");
        }
    }

    private static String describe(List<EnchantmentInstance> list) {
        StringBuilder sb = new StringBuilder();
        for (EnchantmentInstance e : list) {
            sb.append(sb.length() == 0 ? "" : ", ").append(Mc.enchantmentName(e.enchantment, e.level));
        }
        return sb.toString();
    }

    /** Draws the line under the table (under the prediction panel when that is on) and the highlights. */
    public static void render(ContainerScreen<?> screen, EnchantmentContainer table, MatrixStack ms) {
        if (!ModSettings.planGuide) {
            return;
        }
        Step step = next(CrackerState.get(), table);
        if (step.text.isEmpty()) {
            return;
        }
        int width = Math.max(screen.getXSize() + 90, 280);
        int left = Math.max(2, screen.getGuiLeft() + (screen.getXSize() - width) / 2);
        int screenHeight = screen.field_230709_l_; // Screen.height
        int predictionTop = Math.max(2, Math.min(screen.getGuiTop() + screen.getYSize() + 4, screenHeight - 58 - 2));
        List<String> lines = Theme.wrap("Next: " + step.text, width - 10);
        if (lines.size() > 3) {
            lines = lines.subList(0, 3);
        }
        int height = 5 + lines.size() * 10;
        int top = ModSettings.tablePrediction ? predictionTop + 60 : predictionTop;
        if (top + height > screenHeight - 2) {
            top = Math.max(2, screen.getGuiTop() - height - 16); // no room below: above the table
            if (top + height > screen.getGuiTop() - 14) {
                // Not even room above (a short window): stay clear of the Pick junk button that
                // sits on the table's top right edge, wrapping into up to four narrower lines.
                width = screen.getGuiLeft() + screen.getXSize() - 64 - left;
                lines = Theme.wrap("Next: " + step.text, width - 10);
                if (lines.size() > 4) {
                    lines = lines.subList(0, 4);
                }
                height = 5 + lines.size() * 10;
                top = Math.max(2, screen.getGuiTop() - height - 16);
            }
        }
        Theme.darkPanel(ms, left, top, width, height);
        int y = top + 3;
        for (String line : lines) {
            Mc.shadowText(ms, line, left + 5, y, step.warn ? 0xFFFFB070 : 0xFF80FFE0);
            y += 10;
        }

        // The highlights, pulsing.
        float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 150.0);
        int colour = ((int) (0x90 + 0x6F * pulse) << 24) | 0x40FFD0;
        int gx = screen.getGuiLeft();
        int gy = screen.getGuiTop();
        if (step.enchantSlot >= 0) {
            Mc.outline(ms, gx + 59, gy + 13 + 19 * step.enchantSlot, 110, 21, colour);
        }
        if (step.item != null) {
            for (Slot slot : table.field_75151_b) { // inventorySlots
                if (slot.field_75224_c != Mc.player().field_71071_by || !slot.func_75216_d()) { // inventory, getHasStack
                    continue;
                }
                ItemStack stack = slot.func_75211_c();
                if (step.item.equals(Mc.idOf(stack.func_77973_b())) && !stack.func_77948_v()) { // getItem, isEnchanted
                    Mc.outline(ms, gx + slot.field_75223_e - 1, gy + slot.field_75221_f - 1, 18, 18, colour); // xPos, yPos
                    break;
                }
            }
        }
        Widgets.McButton button = step.button == Button.LOCK ? TableButtons.lockButton
                : step.button == Button.DROP ? TableButtons.dropButton : null;
        if (button != null) {
            Mc.outline(ms, button.field_230690_l_ - 1, button.field_230691_m_ - 1, // x, y
                    button.func_230998_h_() + 2, button.func_238483_d_() + 2, colour); // getWidth, getHeight
        }
    }
}
