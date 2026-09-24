package com.enchantmentcracker.client.gui.tabs;

import com.enchantmentcracker.client.ModSettings;
import com.enchantmentcracker.client.Planner;
import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.CrackerTab;
import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.CrackEnchantments;
import com.enchantmentcracker.core.CrackItems;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.game.AreaTracker;
import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.widget.Widget;

import java.util.ArrayList;
import java.util.List;

/**
 * Pick an item, say which enchantments you want, and let the mod work out the plan.
 *
 * <p>The item picker is a shape row crossed with a material row, exactly like the
 * standalone tool, plus a row for the things that have no material variants.
 */
public final class CalculatorTab implements CrackerTab {

    private static final int CELL = 16;

    /** Item used as the icon for each material column. */
    private static final String[] MATERIAL_ICONS = {
            "netherite_ingot", "diamond", "gold_ingot", "iron_ingot",
            "chain", "cobblestone", "oak_planks", "leather"
    };

    private static final String[] MATERIAL_NAMES = {
            "Netherite", "Diamond", "Gold", "Iron", "Chainmail", "Stone", "Wood", "Leather"
    };

    private CrackerScreen screen;
    private int x;
    private int y;
    private int width;
    private int height;

    private final List<String> shapes = new ArrayList<>(CrackItems.ITEM_GRID.keySet());
    private int shapeIndex = 1;      // pickaxe
    private int materialIndex = 1;   // diamond

    private Widgets.TextBox shelvesBox;
    private Widgets.TextBox levelBox;

    private int wishScroll;
    private List<String> applicable = new ArrayList<>();
    private String message = "";
    private Planner.Job job;

    @Override
    public String title() {
        return "Calc";
    }

    @Override
    public void init(CrackerScreen screen, int x, int y, int width, int height) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        CrackerState state = CrackerState.get();
        syncPickerToSelection(state.getSelectedItem());
        applicable = applicableEnchantments(state.getSelectedItem());

        // --- shape row
        int rowY = y + 11;
        for (int i = 0; i < shapes.size(); i++) {
            int index = i;
            String[] row = CrackItems.ITEM_GRID.get(shapes.get(i));
            String sample = firstNonNull(row);
            screen.addWidget(new Widgets.IconButton(x + i * CELL, rowY, CELL,
                    Mc.stackOf(sample), capitalise(shapes.get(i)),
                    () -> shapeIndex == index && !isSingleSelected(),
                    () -> selectShape(index)));
        }

        // --- material row, to the right of the shapes
        int materialX = x + shapes.size() * CELL + 8;
        for (int i = 0; i < CrackItems.MATERIALS.length; i++) {
            int index = i;
            Widgets.IconButton button = new Widgets.IconButton(materialX + i * CELL, rowY, CELL,
                    Mc.stackOf(MATERIAL_ICONS[i]), MATERIAL_NAMES[i],
                    () -> materialIndex == index && !isSingleSelected(),
                    () -> selectMaterial(index));
            screen.addWidget(button);
        }

        // --- items with no material variants
        int singlesY = y + 30;
        for (int i = 0; i < CrackItems.SINGLE_ITEMS.size(); i++) {
            String item = CrackItems.SINGLE_ITEMS.get(i);
            screen.addWidget(new Widgets.IconButton(x + i * CELL, singlesY, CELL,
                    Mc.stackOf(item), Mc.itemName(item),
                    () -> item.equals(CrackerState.get().getSelectedItem()),
                    () -> selectItem(item)));
        }

        // --- whatever is in your hand, modded items included
        int heldX = x + CrackItems.SINGLE_ITEMS.size() * CELL + 8;
        String held = heldItem();
        if (held != null) {
            screen.addWidget(new Widgets.IconButton(heldX, singlesY, CELL,
                    Mc.stackOf(held), "In your hand: " + Mc.itemName(held),
                    () -> held.equals(CrackerState.get().getSelectedItem()),
                    () -> selectItem(held)));
        }

        // --- numbers
        int numbersY = y + 50;
        shelvesBox = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 52, numbersY, 24, 14, "Shelves")
                .maxLength(2).numeric());
        shelvesBox.setText(String.valueOf(autoShelves() >= 0 ? autoShelves() : state.getMaxBookshelves()));
        shelvesBox.func_146184_c(autoShelves() < 0); // setEnabled: typed only when not auto-detected
        levelBox = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 112, numbersY, 28, 14, "Level")
                .maxLength(3).numeric());
        levelBox.setText(String.valueOf(state.getPlayerLevel()));

        screen.addWidget(new Widgets.McButton(x + 146, numbersY, 62, 14, "From table", () -> {
            CrackerState s = CrackerState.get();
            if (s.getTableBookshelves() >= 0) {
                s.setMaxBookshelves(s.getTableBookshelves());
            }
            if (s.getTableItem() != null && CrackItems.getEnchantability(s.getTableItem()) > 0) {
                s.setSelectedItem(s.getTableItem());
            }
            if (Mc.player() != null) {
                s.setPlayerLevel(Mc.player().field_71068_ca);
            }
            screen.rebuild();
        }).tooltip("Copy the item (modded ones too), bookshelf", "count and level from the last table."));

        screen.addWidget(new Widgets.McButton(x + 212, numbersY, 46, 14, "Clear", () -> {
            CrackerState.get().clearWishlist();
            message = "";
        }).tooltip("Forget every wanted and unwanted enchantment."));

        // --- wishlist rows
        int listY = y + 79;
        int listHeight = height - 79 - 20;
        int rows = Math.max(1, listHeight / 13);
        wishScroll = Math.max(0, Math.min(wishScroll, Math.max(0, applicable.size() - rows)));
        for (int i = 0; i < rows && i + wishScroll < applicable.size(); i++) {
            String enchantment = applicable.get(i + wishScroll);
            int maxLevel = CrackEnchantments.getMaxLevelInTable(enchantment, state.getSelectedItem());
            screen.addWidget(new Widgets.WishButton(x, listY + i * 13, width - 8, 12,
                    enchantment, maxLevel,
                    () -> CrackerState.get().getWish(enchantment),
                    value -> CrackerState.get().setWish(enchantment, value)));
        }

        // --- calculate
        int actionY = y + height - 16;
        screen.addWidget(new Widgets.McButton(x, actionY, 84, 15, "Calculate", this::calculate)
                .tooltip("Search for a way to get exactly these enchantments.",
                        "Needs the player seed (see the Seed tab)."));
        screen.addWidget(new Widgets.McButton(x + 88, actionY, 74, 15, "Best now", this::showBestNow)
                .tooltip("Ignore the wishlist and just show what",
                        "the table is offering right now."));
    }

    /** The auto-detected shelf maximum, or -1 when that is off or no table is near. */
    private static int autoShelves() {
        return ModSettings.autoDetectArea && AreaTracker.hasTable() ? AreaTracker.potentialShelves() : -1;
    }

    private static String heldItem() {
        if (Mc.player() == null) {
            return null;
        }
        String id = Mc.idOf(Mc.heldStack().func_77973_b()); // getItem()
        return id != null && CrackItems.getEnchantability(id) > 0 ? id : null;
    }

    // ------------------------------------------------------------------ selection

    private boolean isSingleSelected() {
        return CrackItems.SINGLE_ITEMS.contains(CrackerState.get().getSelectedItem());
    }

    private void selectShape(int index) {
        shapeIndex = index;
        applySelection();
    }

    private void selectMaterial(int index) {
        materialIndex = index;
        applySelection();
    }

    private void selectItem(String item) {
        CrackerState.get().setSelectedItem(item);
        wishScroll = 0;
        message = "";
        screen.rebuild();
    }

    /** Picks the shape/material combination, sliding to a valid material if needed. */
    private void applySelection() {
        String[] row = CrackItems.ITEM_GRID.get(shapes.get(shapeIndex));
        if (row[materialIndex] == null) {
            for (int offset = 1; offset < row.length; offset++) {
                int candidate = (materialIndex + offset) % row.length;
                if (row[candidate] != null) {
                    materialIndex = candidate;
                    break;
                }
            }
        }
        selectItem(row[materialIndex]);
    }

    private void syncPickerToSelection(String item) {
        for (int s = 0; s < shapes.size(); s++) {
            String[] row = CrackItems.ITEM_GRID.get(shapes.get(s));
            for (int m = 0; m < row.length; m++) {
                if (item != null && item.equals(row[m])) {
                    shapeIndex = s;
                    materialIndex = m;
                    return;
                }
            }
        }
    }

    private static List<String> applicableEnchantments(String item) {
        List<String> list = new ArrayList<>();
        for (String enchantment : CrackEnchantments.tableEnchantments()) {
            if (CrackEnchantments.getMaxLevelInTable(enchantment, item) > 0) {
                list.add(enchantment);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ actions

    private void readBoxes() {
        CrackerState state = CrackerState.get();
        if (autoShelves() < 0) {
            state.setMaxBookshelves(shelvesBox.intValue(state.getMaxBookshelves()));
        }
        state.setPlayerLevel(levelBox.intValue(state.getPlayerLevel()));
    }

    private void calculate() {
        readBoxes();
        CrackerState state = CrackerState.get();
        if (!state.hasWishes()) {
            message = "Pick at least one enchantment to aim for.";
            return;
        }
        EnchantCalculator.Request request = new EnchantCalculator.Request();
        String problem = Planner.prepare(request, state.getSelectedItem(), state.getWanted(), state.getUnwanted(), 3);
        if (problem != null) {
            message = problem;
            return;
        }
        message = "Searching...";
        job = Planner.start("calc", request);
    }

    /** Builds a zero-setup "plan" describing what the table would give right now. */
    private void showBestNow() {
        readBoxes();
        CrackerState state = CrackerState.get();
        EnchantCalculator.Request request = new EnchantCalculator.Request();
        String problem = Planner.prepare(request, state.getSelectedItem(),
                java.util.Collections.emptyList(), java.util.Collections.emptyList(), 3);
        if (problem != null) {
            message = problem;
            return;
        }
        request.maxThrows = -1; // only "enchant right now": no dropping, no dummy
        message = "Reading the table...";
        job = Planner.start("calc", request);
    }

    @Override
    public void tick() {
        Planner.Job running = job;
        if (running == null || !running.done) {
            return;
        }
        job = null;
        if (running.cancelled) {
            message = "Search cancelled.";
            return;
        }
        if (running.error != null) {
            message = "Search failed: " + running.error;
            return;
        }
        CrackerState.get().setPlanOptions(running.results);
        if (running.results.isEmpty()) {
            message = "No way to get that. Try fewer enchantments, a lower level, or more bookshelves.";
        } else {
            message = "Plan ready" + (running.results.size() > 1 ? " (" + running.results.size() + " options)." : ".");
            screen.switchTo(CrackerScreen.Tab.PLAN);
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        CrackerState state = CrackerState.get();
        String item = state.getSelectedItem();

        Mc.text(ms, "Item", x, y, Theme.TEXT_TITLE);
        String name = Mc.itemName(item) + "  (enchantability "
                + CrackItems.getEnchantability(item) + ")";
        Mc.text(ms, Mc.trim(name, width - 40), x + 30, y, Theme.TEXT_DARK);

        Mc.text(ms, autoShelves() >= 0 ? "Shelves*" : "Shelves", x, y + 53, Theme.TEXT_DARK);
        Planner.Job running = job;
        if (running != null && !running.done) {
            int barY = y + height - 16;
            Theme.inset(ms, x + 166, barY + 2, width - 170, 11);
            Mc.fill(ms, x + 167, barY + 3, x + 167 + (int) ((width - 172) * running.fraction()), barY + 12, Theme.ACCENT);
        }
        Mc.text(ms, "Level", x + 80, y + 53, Theme.TEXT_DARK);

        Mc.text(ms, "Enchantments", x, y + 68, Theme.TEXT_TITLE);
        Mc.text(ms, "click to cycle, right-click back", x + 70, y + 68, Theme.TEXT_MUTED);

        int listY = y + 79;
        int listHeight = height - 79 - 20;
        int rows = Math.max(1, listHeight / 13);
        Theme.scrollbar(ms, x + width - 6, listY, rows * 13, applicable.size() * 13, wishScroll * 13);

        if (applicable.isEmpty()) {
            Mc.text(ms, "This item cannot be enchanted at a table.", x, listY + 4, Theme.BAD);
        }
    }

    @Override
    public void renderOverlay(MatrixStack ms, int mouseX, int mouseY) {
        for (Widget widget : screen.widgets()) {
            if (widget instanceof Widgets.IconButton) {
                Widgets.IconButton icon = (Widgets.IconButton) widget;
                if (icon.hovered() && !icon.getTooltip().isEmpty()) {
                    screen.drawTooltip(ms, icon.getTooltip(), mouseX, mouseY);
                    return;
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int listY = y + 79;
        int listHeight = height - 79 - 20;
        if (mouseY < listY || mouseY > listY + listHeight) {
            return false;
        }
        int rows = Math.max(1, listHeight / 13);
        int max = Math.max(0, applicable.size() - rows);
        int next = Math.max(0, Math.min(max, wishScroll - (int) Math.signum(amount)));
        if (next != wishScroll) {
            wishScroll = next;
            screen.rebuild();
        }
        return true;
    }

    @Override
    public String statusLine() {
        if (message.isEmpty() && autoShelves() >= 0) {
            int shelves = autoShelves();
            return shelves >= 15
                    ? "* 15 is vanilla's cap: bookshelves past 15 around the table add nothing."
                    : "* Shelves detected around your table: " + shelves + " at most.";
        }
        return message.isEmpty() ? null : message;
    }

    // ------------------------------------------------------------------ helpers

    private static String firstNonNull(String[] row) {
        for (String value : row) {
            if (value != null) {
                return value;
            }
        }
        return CrackItems.BOOK;
    }

    private static String capitalise(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
