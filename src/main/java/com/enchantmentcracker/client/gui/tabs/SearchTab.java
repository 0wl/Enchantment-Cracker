package com.enchantmentcracker.client.gui.tabs;

import com.enchantmentcracker.client.Planner;
import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.CrackerTab;
import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.CrackEnchantments;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackItems;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.EnchantModel;
import com.enchantmentcracker.core.Models;
import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "How do I get this enchantment?" Pick any enchantment a table can give (modded ones
 * included), and the tab picks a fitting item (a sword for a sword enchantment, boots for
 * boots...), searches the RNG, and lays out the steps: shelves, junk to drop, the dummy
 * enchantment, then the real one.
 */
public final class SearchTab implements CrackerTab {

    private static final int LIST_ROW = 11;

    /** Preferred item shapes and materials when choosing an item to show for an enchantment. */
    private static final String[] SHAPE_ORDER = {
            "sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots"
    };
    private static final String[] MATERIAL_ORDER = {
            "diamond", "netherite", "iron", "golden", "stone", "wooden", "chainmail", "leather"
    };

    private CrackerScreen screen;
    private int x;
    private int y;
    private int width;
    private int height;
    private int listWidth;

    private Widgets.TextBox searchBox;
    private static String query = "";
    private int listScroll;

    /** Every enchantment worth listing, in registry order, for the model it was built from. */
    private static List<String> listable = Collections.emptyList();
    /** itemsFor() per enchantment, for the hover tooltip; rebuilt with the list. */
    private static final java.util.Map<String, List<String>> HOVER_ITEMS = new java.util.HashMap<>();
    private static EnchantModel listableFor;

    private static String selected;
    private static int level = 1;
    private static List<String> items = Collections.emptyList();
    private static int itemIndex;

    private static List<EnchantCalculator.Result> results = Collections.emptyList();
    private static int option;
    private static String message = "";
    private static Planner.Job job;

    @Override
    public String title() {
        return "Search";
    }

    @Override
    public void init(CrackerScreen screen, int x, int y, int width, int height) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.listWidth = Math.min(150, width * 2 / 5);
        buildListable();

        searchBox = screen.addWidget(new Widgets.TextBox(Mc.font(), x, y, listWidth - 2, 13, "Search").maxLength(40));
        searchBox.setText(query);
        searchBox.func_212954_a(text -> { // setResponder
            query = text;
            listScroll = 0;
        });

        if (selected == null) {
            return;
        }
        int rx = x + listWidth + 8;
        int rw = width - listWidth - 8;

        // Level: < V >
        int maxLevel = maxLevelFor(selected);
        screen.addWidget(new Widgets.McButton(rx + rw - 62, y, 16, 13, "<", () -> changeLevel(-1))
                .tooltip("Lower level"));
        screen.addWidget(new Widgets.McButton(rx + rw - 16, y, 16, 13, ">", () -> changeLevel(1))
                .tooltip("Higher level (up to " + CrackEnchantments.romanNumeral(maxLevel) + " from a table)"));

        // Item: < icon >
        int itemY = y + 16;
        screen.addWidget(new Widgets.McButton(rx + rw - 62, itemY + 2, 16, 13, "<", () -> changeItem(-1))
                .tooltip("Previous item that can get this"));
        screen.addWidget(new Widgets.McButton(rx + rw - 16, itemY + 2, 16, 13, ">", () -> changeItem(1))
                .tooltip("Next item that can get this"));

        int actionY = y + 38;
        screen.addWidget(new Widgets.McButton(rx, actionY, 70, 14, "Find a way", this::search)
                .tooltip("Search your RNG for the steps", "that put this on the item."));
        if (results.size() > 1) {
            screen.addWidget(new Widgets.McButton(rx + 74, actionY, 16, 14, "<", () -> changeOption(-1))
                    .tooltip("Previous option"));
            screen.addWidget(new Widgets.McButton(rx + 134, actionY, 16, 14, ">", () -> changeOption(1))
                    .tooltip("Next option"));
        }
        if (!results.isEmpty()) {
            screen.addWidget(new Widgets.McButton(rx + rw - 76, actionY, 76, 14, "Use as plan", this::usePlan)
                    .tooltip("Make this the active plan: the Plan tab,",
                            "the table overlay and Auto drop follow it."));
        }
    }

    // ------------------------------------------------------------------ data

    private static void buildListable() {
        EnchantModel model = Models.get();
        if (model == listableFor) {
            return;
        }
        List<String> list = new ArrayList<>();
        for (String enchantment : model.allTableEnchantments()) {
            if (!itemsFor(enchantment).isEmpty()) {
                list.add(enchantment);
            }
        }
        listable = list;
        listableFor = model;
        HOVER_ITEMS.clear();
        if (selected != null && !list.contains(selected)) {
            selected = null;
        }
    }

    private List<String> filtered() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return listable;
        }
        List<String> out = new ArrayList<>();
        for (String enchantment : listable) {
            if (Mc.enchantmentName(enchantment, 0).toLowerCase(Locale.ROOT).contains(q)
                    || enchantment.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(enchantment);
            }
        }
        return out;
    }

    /**
     * Items a table can put this enchantment on, best example first: the item in the table
     * or already chosen in the calculator, then vanilla gear by shape (diamond first), the
     * other vanilla items, modded items, and a book last.
     */
    static List<String> itemsFor(String enchantment) {
        EnchantModel model = Models.get();
        Set<String> ordered = new LinkedHashSet<>();
        CrackerState state = CrackerState.get();
        ordered.add(state.getTableItem());
        ordered.add(state.getSelectedItem());
        for (String shape : SHAPE_ORDER) {
            for (String material : MATERIAL_ORDER) {
                ordered.add(material + "_" + shape);
            }
        }
        ordered.add(CrackItems.TURTLE_HELMET);
        for (String single : CrackItems.SINGLE_ITEMS) {
            if (!CrackItems.BOOK.equals(single)) {
                ordered.add(single);
            }
        }
        ordered.addAll(model.enchantableItems());
        ordered.remove(CrackItems.BOOK);
        ordered.add(CrackItems.BOOK);
        ordered.remove(null);

        List<String> out = new ArrayList<>();
        for (String item : ordered) {
            if (model.maxTableLevel(enchantment, item) > 0) {
                out.add(item);
            }
        }
        return out;
    }

    private static int maxLevelFor(String enchantment) {
        String item = currentItem();
        int max = item == null ? 0 : Models.get().maxTableLevel(enchantment, item);
        return Math.max(1, max);
    }

    private static String currentItem() {
        return items.isEmpty() ? null : items.get(Math.max(0, Math.min(itemIndex, items.size() - 1)));
    }

    /** Opens the search on this enchantment, for this item, at this level. */
    public static void preselect(String enchantment, String item, int wantedLevel) {
        selected = enchantment;
        List<String> list = new ArrayList<>(itemsFor(enchantment));
        if (list.remove(item)) {
            list.add(0, item);
        }
        items = list;
        itemIndex = 0;
        level = Math.max(1, Math.min(wantedLevel, maxLevelFor(enchantment)));
        query = "";
        clearResults();
    }

    private void select(String enchantment) {
        selected = enchantment;
        items = itemsFor(enchantment);
        itemIndex = 0;
        level = maxLevelFor(enchantment);
        clearResults();
        screen.rebuild();
    }

    private void changeLevel(int delta) {
        int max = maxLevelFor(selected);
        level = Math.max(Models.get().minLevel(selected), Math.min(max, level + delta));
        clearResults();
    }

    private void changeItem(int delta) {
        if (items.isEmpty()) {
            return;
        }
        itemIndex = (itemIndex + delta + items.size()) % items.size();
        level = Math.min(level, maxLevelFor(selected));
        clearResults();
        screen.rebuild();
    }

    private void changeOption(int delta) {
        if (!results.isEmpty()) {
            option = (option + delta + results.size()) % results.size();
        }
    }

    private static void clearResults() {
        results = Collections.emptyList();
        option = 0;
        message = "";
        if (job != null) {
            job.cancelled = true; // only our own search; the Calc tab's keeps running
        }
        job = null;
    }

    // ------------------------------------------------------------------ actions

    private void search() {
        String item = currentItem();
        if (selected == null || item == null) {
            return;
        }
        EnchantCalculator.Request request = new EnchantCalculator.Request();
        String problem = Planner.prepare(request, item,
                Collections.singletonList(new EnchantmentInstance(selected, level)),
                Collections.emptyList(), 3);
        if (problem != null) {
            message = problem;
            return;
        }
        results = Collections.emptyList();
        option = 0;
        message = "Searching...";
        job = Planner.start("search", request);
    }

    private void usePlan() {
        if (results.isEmpty()) {
            return;
        }
        CrackerState state = CrackerState.get();
        state.setSelectedItem(currentItem());
        List<EnchantCalculator.Result> ordered = new ArrayList<>(results);
        // The option on screen becomes the active one.
        EnchantCalculator.Result chosen = ordered.remove(option);
        ordered.add(0, chosen);
        state.setPlanOptions(ordered);
        screen.switchTo(CrackerScreen.Tab.PLAN);
    }

    @Override
    public void tick() {
        Planner.Job running = job;
        if (running != null && running.done) {
            job = null;
            if (running.cancelled) {
                return;
            }
            if (running.error != null) {
                message = "Search failed: " + running.error;
            } else {
                results = running.results;
                option = 0;
                message = results.isEmpty()
                        ? "No way within " + EnchantCalculator.DEFAULT_MAX_THROWS + " dropped items. Try a lower level or another item."
                        : results.size() + " way" + (results.size() == 1 ? "" : "s") + " found.";
            }
            screen.rebuild();
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        renderList(ms, mouseX, mouseY);

        int rx = x + listWidth + 8;
        int rw = width - listWidth - 8;
        Mc.fill(ms, rx - 5, y, rx - 4, y + height, 0xFF9E9E9E);

        if (selected == null) {
            int lineY = y + 4;
            for (String line : Theme.wrap("Pick an enchantment on the left. Every enchantment a table can "
                    + "give is listed, including ones from other mods. The mod picks a fitting item, finds "
                    + "where your RNG leads to it, and lists the steps.", rw)) {
                Mc.text(ms, line, rx, lineY, Theme.TEXT_DARK);
                lineY += 10;
            }
            return;
        }

        // Title and level.
        String name = Mc.enchantmentName(selected, level);
        Mc.text(ms, Mc.trim(name, rw - 66), rx, y + 3, Theme.TEXT_TITLE);
        Mc.centeredText(ms, CrackEnchantments.romanNumeral(level), rx + rw - 31, y + 3, Theme.TEXT_DARK);

        // Item with its picture.
        String item = currentItem();
        int itemY = y + 16;
        Theme.inset(ms, rx, itemY, 18, 18);
        if (item != null) {
            Mc.drawItem(Mc.stackOf(item), rx + 1, itemY + 1);
            Mc.text(ms, Mc.trim(Mc.itemName(item), rw - 88), rx + 22, itemY + 2, Theme.TEXT_DARK);
            String count = (itemIndex + 1) + "/" + items.size();
            Mc.text(ms, count, rx + 22, itemY + 11, Theme.TEXT_MUTED);
        }
        Mc.centeredText(ms, "item", rx + rw - 31, itemY + 5, Theme.TEXT_MUTED);

        // Option counter.
        if (results.size() > 1) {
            Mc.centeredText(ms, (option + 1) + "/" + results.size(), rx + 112, y + 41, Theme.TEXT_DARK);
        }

        int stepsY = y + 58;
        Planner.Job running = job;
        if (running != null && !running.done) {
            Theme.inset(ms, rx, stepsY, rw, 10);
            Mc.fill(ms, rx + 1, stepsY + 1, rx + 1 + (int) ((rw - 2) * running.fraction()), stepsY + 9, Theme.ACCENT);
            Mc.centeredText(ms, "searching...", rx + rw / 2, stepsY + 1, 0xFFFFFFFF);
            return;
        }
        if (results.isEmpty()) {
            int lineY = stepsY;
            for (String line : Theme.wrap(message.isEmpty() ? describePossible(item) : message, rw)) {
                Mc.text(ms, line, rx, lineY, message.isEmpty() ? Theme.TEXT_DARK : Theme.WARN);
                lineY += 10;
            }
            return;
        }
        renderSteps(ms, results.get(option), item, rx, stepsY, rw);
    }

    private String describePossible(String item) {
        if (item == null) {
            return "";
        }
        int max = Models.get().maxTableLevel(selected, item);
        return "A table can put up to " + Mc.enchantmentName(selected, max) + " on " + Mc.itemName(item)
                + ". Press Find a way to see how, using your seed.";
    }

    private void renderSteps(MatrixStack ms, EnchantCalculator.Result plan, String item, int rx, int stepsY, int rw) {
        List<String> steps = PlanTab.describeSteps(plan, item, CrackerState.get());
        int lineY = stepsY;
        int number = 1;
        for (String step : steps) {
            List<String> wrapped = Theme.wrap(step, rw - 12);
            for (int i = 0; i < wrapped.size(); i++) {
                if (lineY > y + height - 10) {
                    return;
                }
                if (i == 0) {
                    Mc.text(ms, number + ".", rx, lineY, Theme.ACCENT);
                }
                Mc.text(ms, wrapped.get(i), rx + 12, lineY, Theme.TEXT_DARK);
                lineY += 10;
            }
            number++;
        }
        if (lineY <= y + height - 10) {
            String gets = "You get: " + PlanTab.describeEnchantments(plan.enchantments);
            for (String line : Theme.wrap(gets, rw)) {
                if (lineY > y + height - 10) {
                    return;
                }
                Mc.text(ms, line, rx, lineY + 2, Theme.GOOD);
                lineY += 10;
            }
        }
    }

    private void renderList(MatrixStack ms, int mouseX, int mouseY) {
        List<String> list = filtered();
        int top = y + 16;
        int rows = Math.max(1, (height - 16) / LIST_ROW);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, list.size() - rows)));
        Theme.inset(ms, x, top, listWidth - 2, rows * LIST_ROW + 2);
        for (int i = 0; i < rows && i + listScroll < list.size(); i++) {
            String enchantment = list.get(i + listScroll);
            int rowY = top + 1 + i * LIST_ROW;
            boolean hover = mouseX >= x && mouseX < x + listWidth - 8 && mouseY >= rowY && mouseY < rowY + LIST_ROW;
            if (enchantment.equals(selected)) {
                Mc.fill(ms, x + 1, rowY, x + listWidth - 9, rowY + LIST_ROW, 0xFF3B3B8F);
            } else if (hover) {
                Mc.fill(ms, x + 1, rowY, x + listWidth - 9, rowY + LIST_ROW, 0xFF6B6B6B);
            }
            boolean modded = enchantment.indexOf(':') >= 0;
            String label = Mc.enchantmentName(enchantment, 0);
            Mc.text(ms, Mc.trim(label, listWidth - 14), x + 3, rowY + 2, modded ? 0xFFB0E0FF : 0xFFFFFFFF);
        }
        Theme.scrollbar(ms, x + listWidth - 8, top, rows * LIST_ROW + 2, list.size() * LIST_ROW, listScroll * LIST_ROW);
        if (list.isEmpty()) {
            Mc.text(ms, "no match", x + 4, top + 3, 0xFFD0D0D0);
        }
    }

    @Override
    public void renderOverlay(MatrixStack ms, int mouseX, int mouseY) {
        List<String> list = filtered();
        int top = y + 16;
        int rows = Math.max(1, (height - 16) / LIST_ROW);
        if (mouseX < x || mouseX >= x + listWidth - 8 || mouseY < top) {
            return;
        }
        int index = (mouseY - top - 1) / LIST_ROW + listScroll;
        if ((mouseY - top - 1) / LIST_ROW >= rows || index < 0 || index >= list.size()) {
            return;
        }
        String enchantment = list.get(index);
        List<String> lines = new ArrayList<>();
        lines.add(Mc.enchantmentName(enchantment, 0));
        lines.add("From: " + Mc.namespaceOf(enchantment));
        List<String> fits = HOVER_ITEMS.computeIfAbsent(enchantment, SearchTab::itemsFor);
        if (!fits.isEmpty()) {
            lines.add("e.g. " + Mc.itemName(fits.get(0)) + (fits.size() > 1 ? " (+" + (fits.size() - 1) + " more)" : ""));
        }
        screen.drawTooltip(ms, lines, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<String> list = filtered();
        int top = y + 16;
        int rows = Math.max(1, (height - 16) / LIST_ROW);
        if (button != 0 || mouseX < x || mouseX >= x + listWidth - 8 || mouseY < top + 1) {
            return false;
        }
        int row = (int) (mouseY - top - 1) / LIST_ROW;
        int index = row + listScroll;
        if (row >= rows || index >= list.size()) {
            return false;
        }
        Mc.playClick();
        select(list.get(index));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX < x || mouseX >= x + listWidth) {
            return false;
        }
        listScroll = Math.max(0, listScroll - (int) Math.signum(amount) * 3);
        return true;
    }

    @Override
    public String statusLine() {
        if (!message.isEmpty()) {
            return message;
        }
        return CrackerState.get().isLocked() ? "Click an enchantment, then Find a way."
                : "Needs your player seed (Seed tab) to find the steps.";
    }
}
