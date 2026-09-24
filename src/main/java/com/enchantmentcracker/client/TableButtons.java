package com.enchantmentcracker.client;

import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.EnchantTablePrediction;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.game.AutoDropper;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraftforge.client.event.GuiScreenEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The column of buttons beside the enchanting table screen: open the cracker, toggle the
 * prediction overlay, and auto-drop the items the plan needs.
 *
 * <p>They sit to the left of the table window, where item-list mods like JEI leave space,
 * and move to the right only if the screen is too narrow.
 */
final class TableButtons {

    private static final int WIDTH = 66;
    private static final int HEIGHT = 16;
    private static final int GAP = 2;

    private static final List<Widgets.McButton> buttons = new ArrayList<>();

    private TableButtons() {
    }

    static void add(GuiScreenEvent.InitGuiEvent.Post event, ContainerScreen<?> table) {
        buttons.clear();
        buttons.add(new Widgets.McButton(0, 0, WIDTH, HEIGHT, "Cracker", CrackerScreen::openFromTable)
                .tooltip("Open the Enchantment Cracker", "with this table's item filled in."));
        buttons.add(new Widgets.McButton(0, 0, WIDTH, HEIGHT, "Predict", EnchantTablePrediction::toggle)
                .labelFrom(() -> ModSettings.tablePrediction ? "Predict: on" : "Predict: off")
                .tooltip("Show the real enchantments", "of all three slots below the table."));
        if (ModSettings.autoDrop) {
            buttons.add(new Widgets.McButton(0, 0, WIDTH, HEIGHT, "Auto drop", ClientEvents::startPlanDrops)
                    .labelFrom(TableButtons::dropLabel)
                    .selectedWhen(AutoDropper::isRunning)
                    .tooltip("Drop exactly the junk items the plan",
                            "needs, one by one, so the count is exact.",
                            "Pick the junk item first (Pick junk).",
                            "Click again to stop."));
        }
        for (Widgets.McButton button : buttons) {
            event.addWidget(button);
        }
        reposition(table);
    }

    private static String dropLabel() {
        if (AutoDropper.isRunning()) {
            return "Stop (" + AutoDropper.getRemaining() + ")";
        }
        EnchantCalculator.Result plan = CrackerState.get().getPlan();
        if (plan == null || !plan.needsDummy()) {
            return "Auto drop";
        }
        int left = CrackerState.get().getDropsRemaining();
        return left == 0 ? "Dropped" : "Drop " + left;
    }

    static List<Widgets.McButton> buttons() {
        return buttons;
    }

    static void reposition(ContainerScreen<?> table) {
        int left = table.getGuiLeft();
        int x = left - WIDTH - 4;
        if (x < 2) {
            x = left + table.getXSize() + 4;
        }
        int y = table.getGuiTop();
        for (Widgets.McButton button : buttons) {
            button.setPosition(x, y);
            y += HEIGHT + GAP;
        }
    }
}
