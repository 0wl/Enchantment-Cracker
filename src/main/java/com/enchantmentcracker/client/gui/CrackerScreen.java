package com.enchantmentcracker.client.gui;

import com.enchantmentcracker.client.ModKeyBindings;
import com.enchantmentcracker.client.ModSettings;
import com.enchantmentcracker.client.gui.tabs.AboutTab;
import com.enchantmentcracker.client.gui.tabs.AnvilTab;
import com.enchantmentcracker.client.gui.tabs.CalculatorTab;
import com.enchantmentcracker.client.gui.tabs.GuideTab;
import com.enchantmentcracker.client.gui.tabs.PlanTab;
import com.enchantmentcracker.client.gui.tabs.SearchTab;
import com.enchantmentcracker.client.gui.tabs.SeedTab;
import com.enchantmentcracker.client.gui.tabs.SettingsTab;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.TableWatcher;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's main window: a Minecraft-styled tabbed panel that holds the seed tools, the
 * enchantment calculator, the step-by-step helper, the enchantment search, the anvil
 * planner, the settings and the instructions.
 */
public final class CrackerScreen extends McScreen {

    public enum Tab {
        SEED("Seed", "ender_eye"),
        CALCULATOR("Calc", "enchanting_table"),
        PLAN("Plan", "compass"),
        SEARCH("Search", "enchanted_book"),
        ANVIL("Anvil", "anvil"),
        SETTINGS("Settings", "comparator"),
        GUIDE("Guide", "written_book"),
        ABOUT("About", "name_tag");

        public final String label;
        public final String iconItem;

        Tab(String label, String iconItem) {
            this.label = label;
            this.iconItem = iconItem;
        }
    }

    private static Tab lastTab = Tab.SEED;

    private final Map<Tab, CrackerTab> tabs = new EnumMap<>(Tab.class);
    private Tab current;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    private CrackerScreen(Tab tab) {
        super("Enchantment Cracker");
        this.current = tab;
        tabs.put(Tab.SEED, new SeedTab());
        tabs.put(Tab.CALCULATOR, new CalculatorTab());
        tabs.put(Tab.PLAN, new PlanTab());
        tabs.put(Tab.SEARCH, new SearchTab());
        tabs.put(Tab.ANVIL, new AnvilTab());
        tabs.put(Tab.SETTINGS, new SettingsTab());
        tabs.put(Tab.GUIDE, new GuideTab());
        tabs.put(Tab.ABOUT, new AboutTab());
    }

    // ------------------------------------------------------------------- entry points

    public static void open(Tab tab) {
        if (!isVisible(tab)) {
            tab = Tab.SEED;
        }
        lastTab = tab;
        Mc.openScreen(new CrackerScreen(tab));
    }

    /** Tabs whose feature is switched off in Settings are hidden. */
    public static boolean isVisible(Tab tab) {
        return tab != Tab.ANVIL || ModSettings.anvilPlanner;
    }

    public static List<Tab> visibleTabs() {
        List<Tab> visible = new ArrayList<>();
        for (Tab tab : Tab.values()) {
            if (isVisible(tab)) {
                visible.add(tab);
            }
        }
        return visible;
    }

    /** The tab object itself, so tabs can hand work to each other. */
    @SuppressWarnings("unchecked")
    public <T extends CrackerTab> T tab(Tab tab) {
        return (T) tabs.get(tab);
    }

    public static void open() {
        open(lastTab);
    }

    /** Opens the calculator with the open table's item and bookshelf count already filled in. */
    public static void openFromTable() {
        prefillFromTable();
        open(Tab.CALCULATOR);
    }

    /**
     * Reads the enchanting table without opening the GUI, and says what it found in chat.
     * Bound to a key so it can be used mid-workflow.
     */
    public static void captureFromTable() {
        EnchantmentContainer container = TableWatcher.openContainer();
        CrackerState state = CrackerState.get();
        if (container == null) {
            Mc.chat("§c[Cracker] Open an enchanting table first.");
            return;
        }
        prefillFromTable();
        int[] levels = state.getTableLevels();
        Mc.chat("§a[Cracker] §fXP seed §e"
                + com.enchantmentcracker.core.PlayerSeed.formatXpSeed(state.getTableXpSeed())
                + "§f, bookshelves §e" + state.getTableBookshelves()
                + "§f, levels §e" + levels[0] + "/" + levels[1] + "/" + levels[2]);
        Mc.chat("§7[Cracker] " + state.getStatusMessage());
    }

    private static void prefillFromTable() {
        CrackerState state = CrackerState.get();
        String item = state.getTableItem();
        if (item != null && com.enchantmentcracker.core.CrackItems.getEnchantability(item) > 0) {
            state.setSelectedItem(item);
        }
        int shelves = state.getTableBookshelves();
        if (shelves >= 0) {
            state.setMaxBookshelves(shelves);
        }
        if (Mc.player() != null) {
            state.setPlayerLevel(Mc.player().field_71068_ca); // experienceLevel
        }
    }

    // ------------------------------------------------------------------- layout

    public int contentX() {
        return panelX + 6;
    }

    public int contentY() {
        return panelY + 16;
    }

    public int contentWidth() {
        return panelWidth - 12;
    }

    public int contentHeight() {
        return panelHeight - 34;
    }

    public Tab currentTab() {
        return current;
    }

    public void switchTo(Tab tab) {
        if (!isVisible(tab)) {
            return;
        }
        if (tab != current) {
            current = tab;
            lastTab = tab;
            rebuild();
        }
    }

    @Override
    protected void onInit() {
        if (!isVisible(current)) {
            current = Tab.SEED;
        }
        // Wide enough that all eight tabs can show their icon and name together.
        panelWidth = clamp(screenWidth() - 20, 300, 512);
        panelHeight = clamp(screenHeight() - 44, 176, 250);
        panelX = (screenWidth() - panelWidth) / 2;
        panelY = (screenHeight() - panelHeight) / 2 + 8;

        // Tab strip sits just above the panel and overlaps its top edge by a pixel.
        List<Tab> visible = visibleTabs();
        int tabHeight = 18;
        int tabY = panelY - tabHeight;
        int gap = 2;
        int tabWidth = (panelWidth - gap * (visible.size() - 1)) / visible.size();
        int x = panelX;
        for (Tab tab : visible) {
            Tab target = tab;
            ItemStack icon = Mc.stackOf(tab.iconItem);
            Widgets.TabButton button = new Widgets.TabButton(x, tabY, tabWidth, tabHeight, tab.label, icon,
                    () -> current == target, () -> switchTo(target));
            if (tab == Tab.PLAN) {
                button.compassNeedle();
            }
            addWidget(button);
            x += tabWidth + gap;
        }

        addWidget(new Widgets.McButton(panelX + panelWidth - 46, panelY + panelHeight - 16,
                42, 14, "Close", this::close));

        tabs.get(current).init(this, contentX(), contentY(), contentWidth(), contentHeight());
    }

    @Override
    protected void onRender(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        drawBackdrop(ms);
        Theme.panel(ms, panelX, panelY, panelWidth, panelHeight);

        // Title strip.
        Mc.text(ms, "Enchantment Cracker", panelX + 7, panelY + 5, Theme.TEXT_TITLE);
        String seedText = seedSummary();
        Mc.text(ms, seedText, panelX + panelWidth - 7 - Mc.stringWidth(seedText), panelY + 5,
                CrackerState.get().isLocked() ? Theme.GOOD : Theme.TEXT_MUTED);
        Mc.fill(ms, panelX + 5, panelY + 14, panelX + panelWidth - 5, panelY + 15, 0xFF8B8B8B);

        tabs.get(current).render(ms, mouseX, mouseY, partialTicks);

        // Status strip along the bottom.
        int statusY = panelY + panelHeight - 15;
        Mc.fill(ms, panelX + 5, statusY - 3, panelX + panelWidth - 5, statusY - 2, 0xFF8B8B8B);
        String status = tabs.get(current).statusLine();
        if (status == null) {
            status = CrackerState.get().getStatusMessage();
        }
        Mc.text(ms, Mc.trim(status, panelWidth - 60), panelX + 7, statusY + 1, Theme.TEXT_MUTED);
    }

    @Override
    protected void afterWidgets(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        tabs.get(current).renderOverlay(ms, mouseX, mouseY);
        renderWidgetTooltip(ms, mouseX, mouseY);
    }

    /** Shows the tooltip of whichever {@link Widgets.McButton} is under the cursor. */
    private void renderWidgetTooltip(MatrixStack ms, int mouseX, int mouseY) {
        for (Widget widget : widgets()) {
            if (widget instanceof Widgets.TabButton && ((Widgets.TabButton) widget).hovered()) {
                drawTooltip(ms, Collections.singletonList(((Widgets.TabButton) widget).getLabel()), mouseX, mouseY);
                return;
            }
            if (!(widget instanceof Widgets.McButton)) {
                continue;
            }
            Widgets.McButton button = (Widgets.McButton) widget;
            if (!button.hovered() || button.getTooltip().isEmpty()) {
                continue;
            }
            drawTooltip(ms, button.getTooltip(), mouseX, mouseY);
            return;
        }
    }

    /** A plain-text tooltip box, drawn in the vanilla style. */
    public void drawTooltip(MatrixStack ms, List<String> lines, int mouseX, int mouseY) {
        Widgets.drawTooltip(ms, lines, mouseX, mouseY, screenWidth(), screenHeight());
    }

    private String seedSummary() {
        CrackerState state = CrackerState.get();
        switch (state.getStatus()) {
            case LOCKED:
                return "seed " + com.enchantmentcracker.core.PlayerSeed.format(state.getPlayerSeed());
            case AWAITING_SECOND:
                return "half-known";
            default:
                return "no seed";
        }
    }

    // ------------------------------------------------------------------- input

    @Override
    protected void onTick() {
        tabs.get(current).tick();
    }

    @Override
    protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
        return tabs.get(current).mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected boolean onMouseScrolled(double mouseX, double mouseY, double amount) {
        return tabs.get(current).mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (tabs.get(current).keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // Escape first drops focus from a text box you were typing in (a filter, a seed field);
        // pressed again with nothing focused, it closes the window.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (isTyping()) {
                for (Widget widget : widgets()) {
                    if (widget instanceof Widgets.TextBox) {
                        ((Widgets.TextBox) widget).func_146195_b(false); // setFocused(false)
                    }
                }
                return true;
            }
            close();
            return true;
        }
        // Tab / Shift-Tab flips between pages, like a real tabbed dialog.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
            List<Tab> all = visibleTabs();
            int index = all.indexOf(current) + (hasShift() ? all.size() - 1 : 1);
            switchTo(all.get(index % all.size()));
            return true;
        }
        // The open key closes the window again, the way the inventory key does.
        if (ModKeyBindings.matchesKey(ModKeyBindings.openGui, keyCode, scanCode) && !isTyping()) {
            close();
            return true;
        }
        return false;
    }

    /** True while a text box has focus, so letters typed there are not taken as key binds. */
    private boolean isTyping() {
        for (Widget widget : widgets()) {
            if (widget instanceof Widgets.TextBox && ((Widgets.TextBox) widget).func_230999_j_()) { // isFocused
                return true;
            }
        }
        return false;
    }

    private static boolean hasShift() {
        return func_231173_s_(); // Screen.hasShiftDown()
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
