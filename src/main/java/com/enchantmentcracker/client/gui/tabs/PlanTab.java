package com.enchantmentcracker.client.gui.tabs;

import com.enchantmentcracker.client.ClientEvents;
import com.enchantmentcracker.client.ModSettings;
import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.CrackerTab;
import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackItems;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantArea;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.TableSetup;
import com.enchantmentcracker.game.AreaTracker;
import com.enchantmentcracker.game.AutoDropper;
import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The step-by-step helper: what to do next, and whether you have done it yet.
 *
 * <p>Each step ticks itself off from live game state. The bookshelf count comes from the
 * table you have open (or the one detected nearby) and the drop counter from items actually
 * leaving your inventory, so you can follow along without counting anything yourself.
 */
public final class PlanTab implements CrackerTab {

    private CrackerScreen screen;
    private int x;
    private int y;
    private int width;
    private int height;
    private String message = "";

    @Override
    public String title() {
        return "Plan";
    }

    @Override
    public void init(CrackerScreen screen, int x, int y, int width, int height) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        CrackerState state = CrackerState.get();
        int actionY = y + height - 16;
        int bx = x;
        bx += screen.addWidget(new Widgets.McButton(bx, actionY, 92, 15, "Done", () -> {
            if (CrackerState.get().getPlan() == null) {
                message = "No plan to finish.";
                return;
            }
            CrackerState.get().confirmPlan();
            message = "Plan finished. The seed kept up by itself; ready for the next one.";
        }).tooltip("Finish with this plan once you have enchanted.",
                "The seed follows your drops and enchantments",
                "by itself, so this only clears the steps.")).w() + 4;

        EnchantCalculator.Result plan = state.getPlan();
        if (ModSettings.autoDrop && plan != null && plan.needsDummy()) {
            bx += screen.addWidget(new Widgets.McButton(bx, actionY, 70, 15, "Auto drop", ClientEvents::startPlanDrops)
                    .labelFrom(() -> AutoDropper.isRunning() ? "Stop (" + AutoDropper.getRemaining() + ")"
                            : "Drop " + CrackerState.get().getDropsRemaining())
                    .selectedWhen(AutoDropper::isRunning)
                    .tooltip("Throw exactly the junk items still needed.",
                            "Pick the junk item first: open your",
                            "inventory and press 'Pick junk'.")).w() + 4;
        }

        List<EnchantCalculator.Result> options = state.getPlanOptions();
        if (options.size() > 1) {
            bx += screen.addWidget(new Widgets.McButton(bx, actionY, 58, 15, "Option", () -> {
                CrackerState s = CrackerState.get();
                int next = (s.getPlanOptionIndex() + 1) % s.getPlanOptions().size();
                s.choosePlanOption(next);
            }).labelFrom(() -> "Option " + (CrackerState.get().getPlanOptionIndex() + 1) + "/"
                    + CrackerState.get().getPlanOptions().size())
                    .tooltip("Other ways to the same result:", "fewer drops, other shelf counts.")).w() + 4;
        }

        screen.addWidget(new Widgets.McButton(bx, actionY, 44, 15, "Clear",
                () -> {
                    CrackerState.get().setPlan(null);
                    AutoDropper.stop();
                    message = "";
                    screen.rebuild();
                }).tooltip("Throw the current plan away."));
    }

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        CrackerState state = CrackerState.get();
        EnchantCalculator.Result plan = state.getPlan();

        renderLiveTable(ms, state);

        int planY = y + 58;
        if (plan == null) {
            Mc.text(ms, "No plan yet", x, planY, Theme.TEXT_TITLE);
            int lineY = planY + 12;
            for (String line : Theme.wrap(
                    "Use the Search tab to pick one enchantment, or the Calc tab for a full wishlist. "
                            + "The steps will appear here.", width)) {
                Mc.text(ms, line, x, lineY, Theme.TEXT_DARK);
                lineY += 10;
            }
            return;
        }

        if (plan.outcome == EnchantCalculator.Outcome.IMPOSSIBLE) {
            Mc.text(ms, "Not reachable", x, planY, Theme.BAD);
            int lineY = planY + 12;
            for (String line : Theme.wrap(
                    "Nothing within " + EnchantCalculator.DEFAULT_MAX_THROWS + " dropped items produces that "
                            + "combination. Ask for fewer enchantments, allow a lower level, or raise the "
                            + "bookshelf limit.", width)) {
                Mc.text(ms, line, x, lineY, Theme.TEXT_DARK);
                lineY += 10;
            }
            return;
        }

        renderPlan(ms, state, plan, planY);
    }

    /** Top block: what the table in front of you is currently offering. */
    private void renderLiveTable(MatrixStack ms, CrackerState state) {
        Mc.text(ms, "At the table", x, y, Theme.TEXT_TITLE);

        TableSetup setup = state.getTableSetup();
        String where = setup != null ? setup.describe()
                : ModSettings.autoDetectArea && AreaTracker.hasTable() ? AreaTracker.currentShelves() + " bookshelves (detected)"
                : "no table read yet";
        Mc.text(ms, Mc.trim(where, width - 110), x + 70, y, Theme.TEXT_DARK);
        Mc.text(ms, state.isTableOpen() ? "open" : "closed", x + width - 34, y,
                state.isTableOpen() ? Theme.GOOD : Theme.TEXT_MUTED);

        Theme.inset(ms, x, y + 11, width, 42);

        Integer xpSeed = state.getEffectiveXpSeed();
        String item = state.getTableItem() != null ? state.getTableItem() : state.getSelectedItem();
        if (state.getTableProblem() != null && state.isTableOpen()) {
            int lineY = y + 14;
            for (String line : Theme.wrap(state.getTableProblem(), width - 8)) {
                Mc.text(ms, line, x + 4, lineY, 0xFFFFB0B0);
                lineY += 10;
            }
            return;
        }
        if (xpSeed == null || setup == null) {
            Mc.text(ms, "Open an enchanting table to see live predictions.",
                    x + 4, y + 27, Theme.TEXT_LIGHT);
            return;
        }

        EnchantCalculator.SlotPreview[] slots = EnchantCalculator.preview(xpSeed, setup, item);
        for (int slot = 0; slot < 3; slot++) {
            int rowY = y + 14 + slot * 13;
            EnchantCalculator.SlotPreview preview = slots[slot];
            Mc.text(ms, (slot + 1) + ")", x + 4, rowY, 0xFFA0A0A0);

            if (preview.isEmpty()) {
                Mc.text(ms, "unavailable", x + 18, rowY, 0xFF808080);
                continue;
            }
            Mc.text(ms, preview.levelRequirement + " lvl", x + 18, rowY, 0xFF7FE07F);
            Mc.text(ms, Mc.trim(describeEnchantments(preview.enchantments), width - 66),
                    x + 60, rowY, 0xFFE8E8E8);
        }
    }

    /** Bottom block: the ordered steps, each ticked off from live state. */
    private void renderPlan(MatrixStack ms, CrackerState state, EnchantCalculator.Result plan, int planY) {
        Mc.text(ms, "Your plan", x, planY, Theme.TEXT_TITLE);
        String cost = plan.totalLevelCost() + " levels total";
        Mc.text(ms, cost, x + width - Mc.stringWidth(cost), planY, Theme.TEXT_MUTED);

        int lineY = planY + 11;
        Mc.text(ms, "You get:", x, lineY, Theme.TEXT_DARK);
        Mc.text(ms, Mc.trim(describeEnchantments(plan.enchantments), width - 46), x + 44, lineY, Theme.GOOD);

        List<String> steps = describeSteps(plan, plan.item != null ? plan.item : state.getSelectedItem(), state);
        List<Boolean> done = stepsDone(plan, state);
        int stepY = planY + 24;
        for (int i = 0; i < steps.size(); i++) {
            if (y + height - 20 - stepY < 10) {
                break;
            }
            boolean ok = i < done.size() && done.get(i);
            int colour = ok ? Theme.GOOD : Theme.TEXT_DARK;
            List<String> wrapped = Theme.wrap(steps.get(i), width - 22);
            Mc.text(ms, ok ? "[x]" : "[ ]", x, stepY, colour);
            for (String line : wrapped) {
                if (y + height - 20 - stepY < 10) {
                    break;
                }
                Mc.text(ms, line, x + 20, stepY, colour);
                stepY += 10;
            }
            stepY += 1;
        }

        // Where things stand, worked out from what the mod has seen you do.
        String note = stageNote(state, plan);
        if (note != null && y + height - 20 - stepY >= 10) {
            CrackerState.PlanStage stage = state.getPlanStage();
            int colour = stage == CrackerState.PlanStage.OVERSHOT || stage == CrackerState.PlanStage.OFF_COURSE
                    ? Theme.BAD : Theme.ACCENT;
            for (String line : Theme.wrap(note, width)) {
                if (y + height - 20 - stepY < 10) {
                    break;
                }
                Mc.text(ms, line, x, stepY + 2, colour);
                stepY += 10;
            }
        }
    }

    /**
     * One line about the current stage: what to do next, or what went wrong. Also used for
     * the chat messages sent as each step completes.
     */
    public static String stageNote(CrackerState state, EnchantCalculator.Result plan) {
        String item = Mc.itemName(plan.item != null ? plan.item : state.getSelectedItem());
        switch (state.getPlanStage()) {
            case DROPPING:
                return "Dropped " + state.getDropsSincePlan() + " of " + plan.itemsToThrow + ".";
            case OVERSHOT:
                return "Dropped " + (state.getDropsSincePlan() - plan.itemsToThrow)
                        + " too many, so this plan no longer fits. Recalculate (the seed is still tracked).";
            case DUMMY:
                return "Drops done. Now enchant the dummy item in slot 1.";
            case FINAL:
                return plan.needsDummy()
                        ? "Dummy done and the table is on the planned seed. Enchant your " + item
                        + " in slot " + (plan.slot + 1) + "."
                        : "Enchant your " + item + " in slot " + (plan.slot + 1) + ".";
            case OFF_COURSE:
                return "The table is not on the planned seed (a drop was missed, or something else used "
                        + "your random numbers). Recalculate before enchanting.";
            case DONE:
                return "Done! Press Done or Clear for the next plan.";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ shared wording

    /**
     * The steps of a plan in words, used by this tab and the Search tab:
     * table setup, junk to drop, the dummy enchantment, then the real one.
     */
    public static List<String> describeSteps(EnchantCalculator.Result plan, String item, CrackerState state) {
        List<String> steps = new ArrayList<>();
        TableSetup setup = plan.setup;

        if (setup != null && !setup.isShelfBased()) {
            steps.add("Use your enchanting table as it is: " + setup.describe() + ".");
        } else {
            int target = plan.bookshelves;
            StringBuilder shelves = new StringBuilder("Set the table to " + target + " bookshel" + (target == 1 ? "f" : "ves"));
            if (ModSettings.autoDetectArea && AreaTracker.hasTable()) {
                int potential = AreaTracker.potentialShelves();
                if (target < potential) {
                    shelves.append(" (block off ").append(potential - target).append(" of your ").append(potential).append(")");
                }
                EnchantArea.Adjustment adjustment = AreaTracker.adjustmentFor(target);
                shelves.append(": ").append(AreaTracker.describe(adjustment));
            } else if (state.getTableBookshelves() >= 0 && state.getTableBookshelves() != target) {
                shelves.append(" (now ").append(state.getTableBookshelves()).append(")");
            }
            steps.add(shelves + ".");
        }

        if (plan.needsDummy()) {
            if (plan.itemsToThrow > 0) {
                String junk = AutoDropper.getJunkItem();
                steps.add("Drop " + plan.itemsToThrow + " junk item" + (plan.itemsToThrow == 1 ? "" : "s")
                        + (plan.itemsToThrow > 63 ? " (" + plan.describeThrows() + ")" : "")
                        + (ModSettings.autoDrop ? ", or press Auto drop"
                        + (junk == null ? " (pick a junk item first)" : " (" + Mc.itemName(junk) + ")") : "")
                        + (plan == state.getPlan()
                        ? ". Dropped so far: " + Math.min(state.getDropsSincePlan(), plan.itemsToThrow) : "") + ".");
            }
            steps.add("Enchant a dummy item (a book works) in slot 1" + dummyRequirement(plan, state)
                    + ". It costs 1 level and just burns one roll.");
        }

        steps.add("Enchant your " + Mc.itemName(item) + " in slot " + (plan.slot + 1)
                + ": needs level " + plan.levelRequirement + ", costs " + plan.levelCost + ".");
        return steps;
    }

    /** "needs level N" for the dummy, worked out for a book on the plan's table and today's XP seed. */
    private static String dummyRequirement(EnchantCalculator.Result plan, CrackerState state) {
        Integer xpSeed = state.getEffectiveXpSeed();
        if (xpSeed == null || plan.setup == null) {
            return "";
        }
        int[] levels = plan.setup.levels(xpSeed, CrackItems.BOOK);
        return levels[0] > 0 ? " (needs level " + levels[0] + ")" : "";
    }

    private static List<Boolean> stepsDone(EnchantCalculator.Result plan, CrackerState state) {
        List<Boolean> done = new ArrayList<>();
        boolean shelvesReady = plan.setup != null && !plan.setup.isShelfBased()
                || state.getTableBookshelves() == plan.bookshelves
                || (ModSettings.autoDetectArea && AreaTracker.currentShelves() == plan.bookshelves);
        done.add(shelvesReady);
        int enchants = state.getEnchantsSincePlan();
        if (plan.needsDummy()) {
            if (plan.itemsToThrow > 0) {
                done.add(state.getDropsSincePlan() >= plan.itemsToThrow || enchants >= 1);
            }
            done.add(enchants >= 1); // the dummy: seen as the first enchantment
        }
        done.add(state.getPlanStage() == CrackerState.PlanStage.DONE);
        return done;
    }

    public static String describeEnchantments(List<EnchantmentInstance> enchantments) {
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

    @Override
    public String statusLine() {
        if (!message.isEmpty()) {
            return message;
        }
        String drop = AutoDropper.getMessage();
        return drop.isEmpty() ? null : drop;
    }
}
