package com.enchantmentcracker.client.gui;

import com.mojang.blaze3d.matrix.MatrixStack;

/**
 * One page of the cracker window. The screen owns the frame and the tab strip; a tab only
 * fills the content rectangle it is handed.
 */
public interface CrackerTab {

    /** Short name shown on the tab itself. */
    String title();

    /**
     * Called whenever the window is (re)built. Widgets should be created here and handed
     * to {@link CrackerScreen#addWidget}.
     */
    void init(CrackerScreen screen, int x, int y, int width, int height);

    /** Drawn under the widgets. */
    void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks);

    /** Drawn over the widgets — tooltips go here. */
    default void renderOverlay(MatrixStack ms, int mouseX, int mouseY) {
    }

    default void tick() {
    }

    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    default boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    /** A one-line hint shown in the window's status strip. */
    default String statusLine() {
        return null;
    }
}
