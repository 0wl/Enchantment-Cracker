package com.enchantmentcracker.client.gui.tabs;

import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.CrackerTab;
import com.enchantmentcracker.client.gui.ItemGrid;
import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.AnvilPlanner;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackItems;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantModel;
import com.enchantmentcracker.core.Models;
import com.enchantmentcracker.game.Apotheosis;
import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans the anvil side: which books to make, and the cheapest order to combine them onto the
 * item, with every step's level cost and the prior-work penalty accounted for.
 *
 * <p>Each book is meant to come out of the enchanting table with the Search tab, so each
 * needed book has a Find button that jumps there with it filled in.
 */
public final class AnvilTab implements CrackerTab {

    private static final int ROW = 12;
    private static final int MAX_BOOKS = 10;
    /** Item row, then the "pick" hint, then the list. */
    private static final int LIST_TOP = 32;

    private CrackerScreen screen;
    private int x;
    private int y;
    private int width;
    private int height;
    private int listWidth;
    private int scroll;
    private final ItemGrid itemPicker = new ItemGrid();

    private static String item;
    /** Enchantments already on the base item, and its prior anvil uses, when started from a real item. */
    private static final List<EnchantmentInstance> existing = new ArrayList<>();
    private static int itemWork;
    private static final Map<String, Integer> wanted = new LinkedHashMap<>();
    private static AnvilPlanner.Plan plan;
    private List<String> applicable = new ArrayList<>();

    @Override
    public String title() {
        return "Anvil";
    }

    @Override
    public void init(CrackerScreen screen, int x, int y, int width, int height) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.listWidth = Math.min(150, width * 2 / 5);

        if (item == null || Mc.stackOf(item).func_190926_b()) {
            item = CrackerState.get().getSelectedItem();
        }
        EnchantModel model = Models.get();
        applicable = new ArrayList<>();
        for (String enchantment : model.allEnchantments()) {
            if (model.canApplyAtAnvil(enchantment, item)) {
                applicable.add(enchantment);
            }
        }
        wanted.keySet().removeIf(e -> !applicable.contains(e));

        // Item switcher: [find] < >
        screen.addWidget(new Widgets.McButton(x + listWidth - 54, y + 2, 16, 13, "⌕", () -> {
            itemPicker.open();
            screen.rebuild();
        }).tooltip("Search items by name or mod,", "instead of stepping with < >."));
        screen.addWidget(new Widgets.McButton(x + listWidth - 36, y + 2, 16, 13, "<", () -> changeItem(-1))
                .tooltip("Previous item"));
        screen.addWidget(new Widgets.McButton(x + listWidth - 18, y + 2, 16, 13, ">", () -> changeItem(1))
                .tooltip("Next item"));

        // The item picker takes over the right pane while open.
        if (itemPicker.active) {
            int rx = x + listWidth + 8;
            int rw = width - listWidth - 8;
            itemPicker.build(screen, rx, y, rw, height, pickableItems(), this::pickItem);
            replan();
            return;
        }

        // Enchantment rows.
        int listY = y + LIST_TOP;
        int rows = Math.max(1, (height - LIST_TOP - 18) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, applicable.size() - rows)));
        for (int i = 0; i < rows && i + scroll < applicable.size(); i++) {
            String enchantment = applicable.get(i + scroll);
            int max = model.maxLevel(enchantment);
            screen.addWidget(new Widgets.WishButton(x, listY + i * ROW, listWidth - 8, ROW - 1, enchantment, max,
                    () -> wanted.getOrDefault(enchantment, 0),
                    value -> setWanted(enchantment, value)).noBan());
        }

        screen.addWidget(new Widgets.McButton(x, y + height - 16, 50, 15, "Clear", () -> {
            wanted.clear();
            replan();
        }).tooltip("Deselect everything."));
        screen.addWidget(new Widgets.McButton(x + 54, y + height - 16, 84, 15, "From wishlist", () -> {
            wanted.clear();
            for (EnchantmentInstance wish : CrackerState.get().getWanted()) {
                if (applicable.contains(wish.enchantment)) {
                    wanted.put(wish.enchantment, wish.level);
                }
            }
            replan();
        }).tooltip("Copy the Calc tab's wanted enchantments."));
        screen.addWidget(new Widgets.McButton(x + 142, y + height - 16, 78, 15, "From held", this::fromHeld)
                .tooltip("Start from the item in your hand:",
                        "keep its enchantments and prior anvil uses,",
                        "and plan the new books on top."));

        // Find buttons next to the books in the result.
        if (plan != null && plan.possible) {
            int rx = x + listWidth + 8;
            int rw = width - listWidth - 8;
            int bookY = y + 22;
            int index = 0;
            for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
                if (bookY + index * ROW > y + height / 2) {
                    break;
                }
                String enchantment = entry.getKey();
                int level = entry.getValue();
                if (!Models.get().allTableEnchantments().contains(enchantment)) {
                    index++; // treasure (mending...) never comes from a table
                    continue;
                }
                screen.addWidget(new Widgets.McButton(rx + rw - 30, bookY + index * ROW, 30, ROW - 1, "Find",
                        () -> {
                            SearchTab.preselect(enchantment, CrackItems.BOOK, level);
                            screen.switchTo(CrackerScreen.Tab.SEARCH);
                        }).tooltip("How to get this book", "from the enchanting table."));
                index++;
            }
        }
        replan();
    }

    private void setWanted(String enchantment, int level) {
        if (level <= 0) {
            wanted.remove(enchantment);
        } else if (wanted.containsKey(enchantment) || wanted.size() < MAX_BOOKS) {
            wanted.put(enchantment, level);
        }
        replan();
        screen.rebuild();
    }

    private void changeItem(int delta) {
        List<String> items = pickableItems();
        if (items.isEmpty()) {
            return;
        }
        int index = items.indexOf(item);
        index = (index + delta + items.size()) % items.size();
        setBaseItem(items.get(Math.max(0, index)));
        screen.rebuild();
    }

    /** Every item the anvil can start from (a book is never the base item). */
    private static List<String> pickableItems() {
        List<String> items = new ArrayList<>(Models.get().enchantableItems());
        items.remove(CrackItems.BOOK);
        return items;
    }

    private void pickItem(String picked) {
        setBaseItem(picked);
        screen.rebuild();
    }

    /** Switches the base item and forgets any pre-existing enchantments/prior work. */
    private static void setBaseItem(String picked) {
        item = picked;
        existing.clear();
        itemWork = 0;
    }

    /** Starts from the item in the player's hand, keeping its enchantments and prior anvil uses. */
    private void fromHeld() {
        if (Mc.player() == null) {
            return;
        }
        net.minecraft.item.ItemStack held = Mc.heldStack();
        String id = Mc.idOf(held.func_77973_b()); // getItem()
        if (held.func_190926_b() || id == null) {
            return;
        }
        item = id;
        itemWork = Mc.repairCost(held);
        existing.clear();
        existing.addAll(Mc.enchantmentsOf(held));
        // Drop any wanted books that the held item already has at the same or higher level.
        for (EnchantmentInstance e : existing) {
            Integer want = wanted.get(e.enchantment);
            if (want != null && want <= e.level) {
                wanted.remove(e.enchantment);
            }
        }
        replan();
        screen.rebuild();
    }

    private static int cap() {
        // Apotheosis's coremod removes the "Too Expensive!" limit whenever it is installed.
        return Apotheosis.isInstalled() ? Integer.MAX_VALUE : AnvilPlanner.TOO_EXPENSIVE;
    }

    private static void replan() {
        if (wanted.isEmpty()) {
            plan = null;
            return;
        }
        List<EnchantmentInstance> books = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            books.add(new EnchantmentInstance(entry.getKey(), entry.getValue()));
        }
        plan = AnvilPlanner.plan(Models.get(), item, Mc.itemName(item), itemWork, existing, books,
                book -> Mc.enchantmentName(book.enchantment, book.level), cap());
    }

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        Theme.inset(ms, x, y, 18, 18);
        Mc.drawItem(Mc.stackOf(item), x + 1, y + 1);
        Mc.text(ms, Mc.trim(Mc.itemName(item), listWidth - 78), x + 21, y + 5, Theme.TEXT_TITLE);
        if (!existing.isEmpty() || itemWork > 0) {
            // A blue mark: this base item is a real one carrying enchantments / prior anvil work.
            Mc.text(ms, "✦", x + listWidth - 66, y + 5, 0xFF3B6BC6);
        }

        if (itemPicker.active) {
            int rx = x + listWidth + 8;
            itemPicker.render(ms, mouseX, mouseY);
            Mc.fill(ms, rx - 5, y, rx - 4, y + height, 0xFF9E9E9E);
            return;
        }

        Mc.text(ms, "Pick enchantments & levels:", x, y + 21, Theme.TEXT_MUTED);

        int listY = y + LIST_TOP;
        int rows = Math.max(1, (height - LIST_TOP - 18) / ROW);
        Theme.scrollbar(ms, x + listWidth - 6, listY, rows * ROW, applicable.size() * ROW, scroll * ROW);

        int rx = x + listWidth + 8;
        int rw = width - listWidth - 8;
        Mc.fill(ms, rx - 5, y, rx - 4, y + height, 0xFF9E9E9E);

        if (plan == null) {
            int lineY = y;
            for (String line : Theme.wrap("Choose the enchantments you want on the item. The planner "
                    + "works out the cheapest order to combine one book per enchantment on an anvil, "
                    + "including the prior-work penalty that makes the naive order expensive."
                    + (cap() == AnvilPlanner.TOO_EXPENSIVE ? " Steps of 40+ levels are refused, as in survival."
                    : " Apotheosis is on, so there is no 40-level cap."), rw)) {
                Mc.text(ms, line, rx, lineY, Theme.TEXT_DARK);
                lineY += 10;
            }
            return;
        }
        if (!plan.possible) {
            int lineY = y;
            for (String line : Theme.wrap(plan.problem, rw)) {
                Mc.text(ms, line, rx, lineY, Theme.BAD);
                lineY += 10;
            }
            return;
        }

        Mc.text(ms, "Books needed", rx, y, Theme.TEXT_TITLE);
        String total = plan.totalLevels + " levels";
        Mc.text(ms, total, rx + rw - Mc.stringWidth(total), y, Theme.GOOD);
        int lineY = y + 12;
        int bookY = y + 22;
        int index = 0;
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            if (bookY + index * ROW > y + height / 2) {
                Mc.text(ms, "...", rx, bookY + index * ROW, Theme.TEXT_MUTED);
                break;
            }
            Mc.text(ms, Mc.trim(Mc.enchantmentName(entry.getKey(), entry.getValue()), rw - 34),
                    rx, bookY + index * ROW + 2, Theme.TEXT_DARK);
            index++;
        }
        lineY = Math.max(lineY, bookY + index * ROW) + 4;

        Mc.text(ms, "Order (" + plan.steps.size() + " anvil uses, most " + plan.maxStepCost + " at once)",
                rx, lineY, Theme.TEXT_TITLE);
        lineY += 11;
        int step = 1;
        for (AnvilPlanner.Step s : plan.steps) {
            String text = s.target + "  +  " + s.sacrifice + "  (" + s.cost + " lv)";
            for (String line : Theme.wrap(text, rw - 12)) {
                if (lineY > y + height - 10) {
                    return;
                }
                if (line.equals(Theme.wrap(text, rw - 12).get(0))) {
                    Mc.text(ms, step + ".", rx, lineY, Theme.ACCENT);
                }
                Mc.text(ms, line, rx + 12, lineY, s.ontoItem ? Theme.TEXT_TITLE : Theme.TEXT_DARK);
                lineY += 10;
            }
            step++;
        }
    }

    @Override
    public void renderOverlay(MatrixStack ms, int mouseX, int mouseY) {
        if (itemPicker.active) {
            itemPicker.renderTooltip(screen, ms, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return itemPicker.active && itemPicker.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (itemPicker.active) {
            return itemPicker.mouseScrolled(mouseX, mouseY, amount);
        }
        if (mouseX >= x + listWidth) {
            return false;
        }
        int rows = Math.max(1, (height - LIST_TOP - 18) / ROW);
        int max = Math.max(0, applicable.size() - rows);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        if (next != scroll) {
            scroll = next;
            screen.rebuild();
        }
        return true;
    }

    @Override
    public String statusLine() {
        if (!existing.isEmpty() || itemWork > 0) {
            StringBuilder sb = new StringBuilder("Base item already has ");
            if (existing.isEmpty()) {
                sb.append("no enchantments");
            } else {
                for (int i = 0; i < existing.size(); i++) {
                    EnchantmentInstance e = existing.get(i);
                    sb.append(i == 0 ? "" : ", ").append(Mc.enchantmentName(e.enchantment, e.level));
                }
            }
            if (itemWork > 0) {
                sb.append(" (+").append(itemWork).append(" prior anvil use").append(itemWork == 1 ? "" : "s").append(")");
            }
            return sb.append(".").toString();
        }
        if (plan != null && plan.possible) {
            return "Left slot first, right slot second. The item ends with " + plan.finalWork
                    + " anvil use" + (plan.finalWork == 1 ? "" : "s") + " on it.";
        }
        return "Left-click adds a level, right-click removes one. Up to " + MAX_BOOKS + " books.";
    }
}
