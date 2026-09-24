package com.enchantmentcracker.client.gui;

import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A self-contained dark theme, drawn with plain fills so it stays crisp at every scale and any
 * size. Everything else in the GUI takes its colours from here, so the palette lives in one place.
 *
 * <p>The panel is dark, the text is light and the status colours are chosen to stand out on the
 * dark background, which is what keeps every label — greens, ambers, greys — readable.
 */
public final class Theme {

    // Container panel: a raised dark card with a lighter top-left bevel and darker bottom-right.
    public static final int PANEL        = 0xFF23262E;
    public static final int PANEL_LIGHT  = 0xFF3A3E48;
    public static final int PANEL_SHADE  = 0xFF14161B;
    public static final int BORDER       = 0xFF000000;

    // Sunken inset (lists, previews, item wells): darker than the panel.
    public static final int SLOT         = 0xFF14161D;
    public static final int SLOT_SHADE   = 0xFF0A0B0F;

    // Text, light on the dark panel (still drawn with a subtle shadow via Mc.text).
    public static final int TEXT_DARK    = 0xFFD6D9E0;
    public static final int TEXT_MUTED   = 0xFF9BA2AE;
    public static final int TEXT_LIGHT   = 0xFFECEEF2;
    public static final int TEXT_TITLE   = 0xFFFFFFFF;

    // Status colours, tuned to read on the dark panel.
    public static final int GOOD         = 0xFF5FD98A;
    public static final int WARN         = 0xFFF0B24A;
    public static final int BAD          = 0xFFF0655C;
    public static final int ACCENT       = 0xFF7E9CFF;

    // Button fills, by state (0 disabled, 1 normal, 2 hovered): background, top-left, bottom-right.
    private static final int[] BTN_BG    = {0xFF2A2C31, 0xFF33373F, 0xFF3C5A93};
    private static final int[] BTN_LIGHT = {0xFF34363C, 0xFF474C57, 0xFF5B7EBE};
    private static final int[] BTN_SHADE = {0xFF1D1E22, 0xFF1F212A, 0xFF243A66};

    // Dark tooltip / overlay panel with a blue gradient edge, matching the accent.
    public static final int DARK_BG      = 0xF00E1016;
    public static final int DARK_EDGE_1  = 0xFF3A5AC8;
    public static final int DARK_EDGE_2  = 0xFF1E2E66;

    private Theme() {
    }

    /** The raised container panel: dark card, lighter top-left bevel, darker bottom-right. */
    public static void panel(MatrixStack ms, int x, int y, int w, int h) {
        Mc.outline(ms, x - 1, y - 1, w + 2, h + 2, BORDER);
        Mc.fill(ms, x, y, x + w, y + h, PANEL);
        Mc.fill(ms, x, y, x + w - 2, y + 2, PANEL_LIGHT);
        Mc.fill(ms, x, y, x + 2, y + h - 2, PANEL_LIGHT);
        Mc.fill(ms, x + 2, y + h - 2, x + w, y + h, PANEL_SHADE);
        Mc.fill(ms, x + w - 2, y + 2, x + w, y + h, PANEL_SHADE);
    }

    /** A sunken area, darker than the panel with an inset bevel. */
    public static void inset(MatrixStack ms, int x, int y, int w, int h) {
        Mc.fill(ms, x, y, x + w, y + h, SLOT);
        Mc.fill(ms, x, y, x + w - 1, y + 1, SLOT_SHADE);
        Mc.fill(ms, x, y, x + 1, y + h - 1, SLOT_SHADE);
        Mc.fill(ms, x + 1, y + h - 1, x + w, y + h, PANEL_LIGHT);
        Mc.fill(ms, x + w - 1, y + 1, x + w, y + h, PANEL_LIGHT);
    }

    /** Tooltip-style panel: near-black with a blue gradient edge. */
    public static void darkPanel(MatrixStack ms, int x, int y, int w, int h) {
        Mc.fill(ms, x, y, x + w, y + h, DARK_BG);
        Mc.fill(ms, x, y, x + w, y + 1, DARK_EDGE_2);
        Mc.fill(ms, x, y + h - 1, x + w, y + h, DARK_EDGE_2);
        Mc.fill(ms, x, y, x + 1, y + h, DARK_EDGE_1);
        Mc.fill(ms, x + w - 1, y, x + w, y + h, DARK_EDGE_1);
    }

    /**
     * A dark button, drawn (not textured) so it matches the theme and works at any size.
     *
     * @param state 0 = disabled, 1 = normal, 2 = hovered
     */
    public static void buttonBackground(MatrixStack ms, int x, int y, int w, int h, int state) {
        int s = state < 0 ? 0 : Math.min(state, 2);
        Mc.fill(ms, x, y, x + w, y + h, BTN_BG[s]);
        Mc.fill(ms, x, y, x + w - 1, y + 1, BTN_LIGHT[s]);
        Mc.fill(ms, x, y, x + 1, y + h - 1, BTN_LIGHT[s]);
        Mc.fill(ms, x + 1, y + h - 1, x + w, y + h, BTN_SHADE[s]);
        Mc.fill(ms, x + w - 1, y + 1, x + w, y + h, BTN_SHADE[s]);
        Mc.outline(ms, x, y, w, h, BORDER);
    }

    /** Greedy word wrap. Long words are hard-broken so nothing ever runs off the panel. */
    public static List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() == 0) {
                    line.append(word);
                } else if (Mc.stringWidth(line + " " + word) <= maxWidth) {
                    line.append(' ').append(word);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
                while (Mc.stringWidth(line.toString()) > maxWidth && line.length() > 1) {
                    // A single word wider than the panel: break it at the last fitting character.
                    int cut = line.length() - 1;
                    while (cut > 1 && Mc.stringWidth(line.substring(0, cut)) > maxWidth) {
                        cut--;
                    }
                    lines.add(line.substring(0, cut));
                    line.delete(0, cut);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** A simple vertical scrollbar in the vanilla style. */
    public static void scrollbar(MatrixStack ms, int x, int y, int height, int contentHeight, int scroll) {
        if (contentHeight <= height) {
            return;
        }
        Mc.fill(ms, x, y, x + 6, y + height, 0xFF1A1A1A);
        int knobHeight = Math.max(16, height * height / contentHeight);
        int travel = height - knobHeight;
        int maxScroll = contentHeight - height;
        int knobY = y + (maxScroll <= 0 ? 0 : travel * scroll / maxScroll);
        Mc.fill(ms, x, knobY, x + 6, knobY + knobHeight, 0xFF8B8B8B);
        Mc.fill(ms, x, knobY, x + 5, knobY + knobHeight - 1, 0xFFC6C6C6);
    }
}
