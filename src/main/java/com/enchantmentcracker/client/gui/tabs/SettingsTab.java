package com.enchantmentcracker.client.gui.tabs;

import com.enchantmentcracker.client.ModKeyBindings;
import com.enchantmentcracker.client.ModSettings;
import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.CrackerTab;
import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.game.AutoDropper;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.WorldProfiles;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.ControlsScreen;
import net.minecraft.client.settings.KeyBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Switches for every optional feature, plus a way into Minecraft's own Controls screen,
 * where the mod's key bindings live under "Enchantment Cracker".
 */
public final class SettingsTab implements CrackerTab {

    private static final int ROW = 17;

    private CrackerScreen screen;
    private int x;
    private int y;
    private int width;
    private int height;
    private int scroll;

    /** One line of the list: a label, what it does, and either a toggle or custom controls. */
    private static final class Row {
        final String label;
        final String[] help;
        final Supplier<Boolean> value;
        final Consumer<Boolean> set;
        final boolean indent;

        Row(String label, Supplier<Boolean> value, Consumer<Boolean> set, boolean indent, String... help) {
            this.label = label;
            this.value = value;
            this.set = set;
            this.indent = indent;
            this.help = help;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<int[]> helpAreas = new ArrayList<>();
    private final List<String[]> helpTexts = new ArrayList<>();

    public SettingsTab() {
        rows.add(new Row("Remember seed per world", () -> ModSettings.rememberPerWorld,
                v -> ModSettings.rememberPerWorld = v, false,
                "Keeps a small file per world / server: the XP seed,",
                "the calculator's item, shelves and wishlist.",
                "The full 48-bit seed itself cannot be kept: Minecraft",
                "gives your character a new generator every time you",
                "join or respawn, so it is re-locked with two enchants."));
        rows.add(new Row("Anvil combination planner", () -> ModSettings.anvilPlanner,
                v -> ModSettings.anvilPlanner = v, false,
                "Shows the Anvil tab: the cheapest order to combine",
                "enchanted books onto an item."));
        rows.add(new Row("Auto drop items", () -> ModSettings.autoDrop,
                v -> ModSettings.autoDrop = v, false,
                "Adds 'Pick junk' to your inventory and 'Drop N' to the",
                "enchanting table. The mod throws exactly the number of",
                "junk items the plan needs, so the count is never off."));
        rows.add(new Row("Auto-detect enchanting area", () -> ModSettings.autoDetectArea,
                v -> ModSettings.autoDetectArea = v, false,
                "Finds the table you are near and every bookshelf around",
                "it, so shelf counts are filled in by themselves, and",
                "plans say exactly which gaps to block."));
        rows.add(new Row("Outline changes in the world", () -> ModSettings.areaOutlines,
                v -> ModSettings.areaOutlines = v, true,
                "Red: put a block (a torch, a carpet) in this gap.",
                "Green: clear this gap. Orange: take this shelf away.",
                "Blue: the table that was detected."));
        rows.add(new Row("Predictions on the table screen", () -> ModSettings.tablePrediction,
                v -> ModSettings.tablePrediction = v, false,
                "Writes the real enchantments of all three slots",
                "under the enchanting table window."));
        rows.add(new Row("Lock seed from thrown items", () -> ModSettings.velocityCrack,
                v -> ModSettings.velocityCrack = v, false,
                "On a server, once one XP seed is captured, throw an",
                "item: its launch velocity pins the seed, so no second",
                "enchantment is spent. Stand still, look roughly level."));
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void init(CrackerScreen screen, int x, int y, int width, int height) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        helpAreas.clear();
        helpTexts.clear();

        int listHeight = height - 40;
        int total = rows.size() + 2; // + drop speed + junk item
        int visible = Math.max(1, listHeight / ROW);
        scroll = Math.max(0, Math.min(scroll, total - visible));

        int rowY = y + 12;
        int index = 0;
        for (Row row : rows) {
            if (index >= scroll && index < scroll + visible) {
                screen.addWidget(new Widgets.Toggle(x + width - 44, rowY, 36, 14, row.value, () -> {
                    row.set.accept(!row.value.get());
                    ModSettings.save();
                    screen.rebuild(); // the Anvil tab may have to appear or go
                }).tooltip(row.help));
                helpAreas.add(new int[]{x + (row.indent ? 12 : 0), rowY, width - 50, 14});
                helpTexts.add(row.help);
                rowY += ROW;
            }
            index++;
        }

        // Auto-drop speed.
        if (index >= scroll && index < scroll + visible) {
            screen.addWidget(new Widgets.McButton(x + width - 66, rowY, 18, 14, "-", () -> {
                ModSettings.dropsPerTick = Math.max(1, ModSettings.dropsPerTick - 1);
                ModSettings.save();
            }).tooltip("Drop more slowly."));
            screen.addWidget(new Widgets.McButton(x + width - 26, rowY, 18, 14, "+", () -> {
                ModSettings.dropsPerTick = Math.min(8, ModSettings.dropsPerTick + 1);
                ModSettings.save();
            }).tooltip("Drop faster. Busy servers", "may prefer 1 or 2."));
            rowY += ROW;
        }
        index++;

        // The junk item.
        if (index >= scroll && index < scroll + visible) {
            screen.addWidget(new Widgets.McButton(x + width - 66, rowY, 58, 14, "Forget", () -> {
                AutoDropper.setJunkItem(null);
                ModSettings.junkItem = "";
                ModSettings.save();
            }).tooltip("Clear the picked junk item."));
        }

        // Bottom: key bindings.
        int keysY = y + height - 16;
        screen.addWidget(new Widgets.McButton(x, keysY, 100, 15, "Key bindings...", () ->
                // Minecraft's own Controls screen; our keys are under "Enchantment Cracker".
                Mc.openScreen(new ControlsScreen(screen, Mc.settings())))
                .tooltip("Opens Options > Controls. The mod's keys", "are under 'Enchantment Cracker'."));
    }

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        Mc.text(ms, "Features", x, y, Theme.TEXT_TITLE);
        String where = WorldProfiles.describeCurrent();
        Mc.text(ms, Mc.trim(where, width - 60), x + width - Mc.stringWidth(Mc.trim(where, width - 60)), y, Theme.TEXT_MUTED);

        int listHeight = height - 40;
        int visible = Math.max(1, listHeight / ROW);
        int rowY = y + 12;
        int index = 0;
        for (Row row : rows) {
            if (index >= scroll && index < scroll + visible) {
                int colour = row.indent && !parentOn(index) ? Theme.TEXT_MUTED : Theme.TEXT_DARK;
                Mc.text(ms, (row.indent ? "  - " : "") + row.label, x, rowY + 3, colour);
                rowY += ROW;
            }
            index++;
        }
        if (index >= scroll && index < scroll + visible) {
            Mc.text(ms, "  - Drops per tick", x, rowY + 3, ModSettings.autoDrop ? Theme.TEXT_DARK : Theme.TEXT_MUTED);
            Mc.centeredText(ms, String.valueOf(ModSettings.dropsPerTick), x + width - 37, rowY + 3, Theme.TEXT_TITLE);
            rowY += ROW;
        }
        index++;
        if (index >= scroll && index < scroll + visible) {
            String junk = AutoDropper.getJunkItem();
            String text = "  - Junk item: " + (junk == null ? "none (use Pick junk)" : Mc.itemName(junk));
            Mc.text(ms, Mc.trim(text, width - 72), x, rowY + 3, ModSettings.autoDrop ? Theme.TEXT_DARK : Theme.TEXT_MUTED);
            if (junk != null) {
                Mc.drawItem(Mc.stackOf(junk), x + width - 86, rowY - 1);
            }
        }
        Theme.scrollbar(ms, x + width - 6, y + 12, visible * ROW, (rows.size() + 2) * ROW, scroll * ROW);

        // Key summary with a warning when a key is shared.
        int keysY = y + height - 16;
        String keys = "Open: " + ModKeyBindings.keyName(ModKeyBindings.openGui)
                + "   Pick junk: " + ModKeyBindings.keyName(ModKeyBindings.pickJunk)
                + "   Auto drop: " + ModKeyBindings.keyName(ModKeyBindings.autoDrop);
        Mc.text(ms, Mc.trim(keys, width - 106), x + 106, keysY + 4, Theme.TEXT_MUTED);
        List<String> clashes = ModKeyBindings.conflictsOf(ModKeyBindings.openGui);
        if (!clashes.isEmpty()) {
            Mc.text(ms, Mc.trim("Open key is shared with " + String.join(", ", clashes)
                    + " - still works.", width), x, keysY - 11, Theme.WARN);
        }
    }

    private boolean parentOn(int index) {
        return index == 4 ? ModSettings.autoDetectArea : true;
    }

    @Override
    public void renderOverlay(MatrixStack ms, int mouseX, int mouseY) {
        for (int i = 0; i < helpAreas.size(); i++) {
            int[] area = helpAreas.get(i);
            if (mouseX >= area[0] && mouseX < area[0] + area[2] && mouseY >= area[1] && mouseY < area[1] + area[3]) {
                List<String> lines = new ArrayList<>();
                for (String line : helpTexts.get(i)) {
                    lines.add(line);
                }
                screen.drawTooltip(ms, lines, mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int visible = Math.max(1, (height - 40) / ROW);
        int max = Math.max(0, rows.size() + 2 - visible);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        if (next != scroll) {
            scroll = next;
            screen.rebuild();
        }
        return true;
    }

    @Override
    public String statusLine() {
        List<String> clashes = new ArrayList<>();
        for (KeyBinding binding : ModKeyBindings.all()) {
            if (!ModKeyBindings.conflictsOf(binding).isEmpty()) {
                clashes.add(ModKeyBindings.keyName(binding));
            }
        }
        if (!clashes.isEmpty()) {
            return "Shared keys: " + String.join(", ", clashes) + ". Open always works; the others only when "
                    + "the game gives them the press. Rebind in Key bindings.";
        }
        return "Saved to config/enchcracker-client.properties. Hover a setting for details.";
    }
}
