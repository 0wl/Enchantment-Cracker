package com.enchantmentcracker.client.gui;

import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A pop-over item picker: a filter box over a scrollable grid of item icons. Stepping through
 * a big modpack's items one at a time with the {@code <} {@code >} arrows takes ages, so the
 * Search and Anvil tabs open one of these to jump straight to an item by name or mod id.
 *
 * <p>It lives inside a tab and fills the rectangle it is handed. The filter box is a real
 * widget (built in {@link #build}) so it can take typing, but the grid is drawn and clicked by
 * hand rather than as widgets: that way a keystroke only refilters on the next frame, with no
 * rebuild, so the box keeps its focus while you type.
 */
public final class ItemGrid {

    private static final int CELL = 18;

    /** Whether the picker is open. The owning tab checks this to decide what to build and draw. */
    public boolean active;

    private String query = "";
    private int scroll;

    private int x;
    private int y;
    private int width;
    private int height;
    private int cols = 1;
    private int rows = 1;
    private int gridTop;

    private List<String> all = Collections.emptyList();
    private Consumer<String> onPick = item -> { };

    public void open() {
        active = true;
        scroll = 0;
    }

    public void close() {
        active = false;
    }

    /**
     * Builds the filter box and the close button. The grid itself is drawn in {@link #render}
     * and clicked through {@link #mouseClicked}.
     *
     * @param all    every item that may be picked
     * @param onPick called with the chosen id; the picker closes itself first
     */
    public void build(CrackerScreen screen, int x, int y, int width, int height,
                      List<String> all, Consumer<String> onPick) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.all = all;
        this.onPick = onPick;
        this.cols = Math.max(1, (width - 8) / CELL);
        this.gridTop = y + 16;
        this.rows = Math.max(1, (height - 18 - 12) / CELL);

        Widgets.TextBox box = screen.addWidget(new Widgets.TextBox(Mc.font(), x, y, width - 34, 13, "Filter items")
                .maxLength(40));
        box.setText(query);
        box.func_212954_a(text -> { // setResponder: refilter live, no rebuild, so focus is kept
            query = text;
            scroll = 0;
        });
        screen.addWidget(new Widgets.McButton(x + width - 32, y, 32, 13, "Close", () -> {
            close();
            screen.rebuild();
        }).tooltip("Back to the arrows."));
    }

    public void render(MatrixStack ms, int mouseX, int mouseY) {
        Theme.panel(ms, x - 2, y - 2, width + 4, height + 4);
        Theme.inset(ms, x, gridTop, width, rows * CELL);
        List<String> shown = filtered();
        int max = Math.max(0, divCeil(shown.size(), cols) - rows);
        scroll = Math.max(0, Math.min(scroll, max));
        int start = scroll * cols;
        for (int i = 0; i < cols * rows && start + i < shown.size(); i++) {
            int cx = x + (i % cols) * CELL;
            int cy = gridTop + (i / cols) * CELL;
            boolean hover = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
            if (hover) {
                Mc.fill(ms, cx, cy, cx + CELL, cy + CELL, 0x60FFFFFF);
            }
            Mc.drawItem(Mc.stackOf(shown.get(start + i)), cx + 1, cy + 1);
        }
        Theme.scrollbar(ms, x + width - 6, gridTop, rows * CELL, divCeil(shown.size(), cols) * CELL, scroll * CELL);
        String count = shown.size() + " item" + (shown.size() == 1 ? "" : "s");
        Mc.text(ms, count, x, y + height - 10, Theme.TEXT_MUTED);
        if (shown.isEmpty()) {
            Mc.text(ms, "no match", x + 4, gridTop + 4, Theme.TEXT_DARK);
        }
    }

    /** Draws the hovered item's name as a tooltip. Call from the tab's overlay pass. */
    public void renderTooltip(CrackerScreen screen, MatrixStack ms, int mouseX, int mouseY) {
        String item = itemAt(mouseX, mouseY);
        if (item != null) {
            screen.drawTooltip(ms, java.util.Arrays.asList(Mc.itemName(item), Mc.namespaceOf(item)), mouseX, mouseY);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        String item = itemAt((int) mouseX, (int) mouseY);
        if (item == null) {
            return false;
        }
        Mc.playClick();
        close();
        onPick.accept(item);
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
            return false;
        }
        int max = Math.max(0, divCeil(filtered().size(), cols) - rows);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        return true;
    }

    /** The item under the cursor in the grid, or null. */
    private String itemAt(int mouseX, int mouseY) {
        if (mouseX < x || mouseX >= x + cols * CELL || mouseY < gridTop || mouseY >= gridTop + rows * CELL) {
            return null;
        }
        int col = (mouseX - x) / CELL;
        int row = (mouseY - gridTop) / CELL;
        List<String> shown = filtered();
        int index = scroll * cols + row * cols + col;
        return index >= 0 && index < shown.size() ? shown.get(index) : null;
    }

    private List<String> filtered() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return all;
        }
        List<String> out = new ArrayList<>();
        for (String item : all) {
            if (matches(item, q)) {
                out.add(item);
            }
        }
        return out;
    }

    /** Matches an item id against a lower-cased query by display name, id, or mod id. */
    public static boolean matches(String id, String lowerQuery) {
        if (id == null) {
            return false;
        }
        return Mc.itemName(id).toLowerCase(Locale.ROOT).contains(lowerQuery)
                || id.toLowerCase(Locale.ROOT).contains(lowerQuery)
                || Mc.namespaceOf(id).toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static int divCeil(int a, int b) {
        return b <= 0 ? 0 : (a + b - 1) / b;
    }
}
