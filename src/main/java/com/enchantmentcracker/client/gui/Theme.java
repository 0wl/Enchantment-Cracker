package com.enchantmentcracker.client.gui;

import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft-native drawing: the same greys, bevels and button texture the vanilla
 * screens use, so the cracker does not look like a foreign window bolted onto the game.
 *
 * <p>The panels are drawn rather than blitted from a texture, using the exact colours
 * out of {@code gui/container/generic_54.png}, which keeps the GUI crisp at every scale
 * and lets it be any size.
 */
public final class Theme {

    /** Vanilla's widgets sheet — the button texture lives here at v=46/66/86. */
    public static final ResourceLocation WIDGETS =
            new ResourceLocation("minecraft", "textures/gui/widgets.png");

    // Container panel colours, straight out of the vanilla inventory texture.
    public static final int PANEL        = 0xFFC6C6C6;
    public static final int PANEL_LIGHT  = 0xFFFFFFFF;
    public static final int PANEL_SHADE  = 0xFF555555;
    public static final int BORDER       = 0xFF000000;

    // Inset / slot colours.
    public static final int SLOT         = 0xFF8B8B8B;
    public static final int SLOT_SHADE   = 0xFF373737;

    // Text.
    public static final int TEXT_DARK    = 0xFF404040;
    public static final int TEXT_MUTED   = 0xFF707070;
    public static final int TEXT_LIGHT   = 0xFFE0E0E0;
    public static final int TEXT_TITLE   = 0xFF3F3F3F;

    // Status colours, matching the game's chat formatting palette.
    public static final int GOOD         = 0xFF3C8527;
    public static final int WARN         = 0xFFB06A00;
    public static final int BAD          = 0xFFA02020;
    public static final int ACCENT       = 0xFF3B3B8F;

    // Dark tooltip-style panel, for blocks of text.
    public static final int DARK_BG      = 0xF0100010;
    public static final int DARK_EDGE_1  = 0xFF5000FF;
    public static final int DARK_EDGE_2  = 0xFF28007F;

    private Theme() {
    }

    /** The classic raised container panel: light grey, white top-left bevel, dark bottom-right. */
    public static void panel(MatrixStack ms, int x, int y, int w, int h) {
        Mc.outline(ms, x - 1, y - 1, w + 2, h + 2, BORDER);
        Mc.fill(ms, x, y, x + w, y + h, PANEL);
        Mc.fill(ms, x, y, x + w - 2, y + 2, PANEL_LIGHT);
        Mc.fill(ms, x, y, x + 2, y + h - 2, PANEL_LIGHT);
        Mc.fill(ms, x + 2, y + h - 2, x + w, y + h, PANEL_SHADE);
        Mc.fill(ms, x + w - 2, y + 2, x + w, y + h, PANEL_SHADE);
    }

    /** A sunken area, drawn like an inventory slot. */
    public static void inset(MatrixStack ms, int x, int y, int w, int h) {
        Mc.fill(ms, x, y, x + w, y + h, SLOT);
        Mc.fill(ms, x, y, x + w - 1, y + 1, SLOT_SHADE);
        Mc.fill(ms, x, y, x + 1, y + h - 1, SLOT_SHADE);
        Mc.fill(ms, x + 1, y + h - 1, x + w, y + h, PANEL_LIGHT);
        Mc.fill(ms, x + w - 1, y + 1, x + w, y + h, PANEL_LIGHT);
    }

    /** Tooltip-style dark panel with the purple gradient edge. */
    public static void darkPanel(MatrixStack ms, int x, int y, int w, int h) {
        Mc.fill(ms, x, y, x + w, y + h, DARK_BG);
        Mc.fill(ms, x, y, x + w, y + 1, DARK_EDGE_2);
        Mc.fill(ms, x, y + h - 1, x + w, y + h, DARK_EDGE_2);
        Mc.fill(ms, x, y, x + 1, y + h, DARK_EDGE_1);
        Mc.fill(ms, x + w - 1, y, x + w, y + h, DARK_EDGE_1);
    }

    /**
     * The vanilla button texture, sliced into four so it works at any size from 8 to 20
     * pixels tall rather than only the standard 20.
     *
     * @param state 0 = disabled, 1 = normal, 2 = hovered
     */
    public static void buttonBackground(MatrixStack ms, int x, int y, int w, int h, int state) {
        Mc.bindTexture(WIDGETS);
        Mc.resetColour();
        Mc.enableBlend();

        int v = 46 + state * 20;
        int leftW = Math.min(w / 2, 100);
        int rightW = w - leftW;
        int topH = Math.min(h / 2, 10);
        int bottomH = h - topH;
        // The sheet's button is 200x20 at (0, v); take the left/top from its start and the
        // right/bottom from its end so the rounded corners survive any width or height.
        Mc.blit256(ms, x, y, 0, v, leftW, topH);
        Mc.blit256(ms, x + leftW, y, 200 - rightW, v, rightW, topH);
        Mc.blit256(ms, x, y + topH, 0, v + 20 - bottomH, leftW, bottomH);
        Mc.blit256(ms, x + leftW, y + topH, 200 - rightW, v + 20 - bottomH, rightW, bottomH);

        Mc.disableBlend();
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
