package com.enchantmentcracker.client.gui;

import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.Widget;
import net.minecraft.util.text.StringTextComponent;

/**
 * A {@link Screen} with readable method names.
 *
 * <p>A production 1.16.5 mod is compiled against SRG names, so every {@code Screen}
 * override looks like {@code func_231160_c_}. This class takes that hit once and gives
 * the rest of the GUI ordinary names to implement.
 */
public abstract class McScreen extends Screen {

    protected McScreen(String title) {
        super(new StringTextComponent(title));
    }

    // ------------------------------------------------------- readable hooks to override

    protected void onInit() {
    }

    protected void onRender(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
    }

    protected void onTick() {
    }

    protected boolean onMouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    protected boolean onMouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    protected boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        return false;
    }

    protected boolean onMouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    /** Return true to swallow the key. Escape closing is handled for you. */
    protected boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    protected boolean onCharTyped(char typed, int modifiers) {
        return false;
    }

    protected void onClosed() {
    }

    /** Whether this screen should pause a singleplayer world. */
    protected boolean pausesGame() {
        return false;
    }

    // ------------------------------------------------------------------ helpers

    public int screenWidth() {
        return field_230708_k_; // Screen.width
    }

    public int screenHeight() {
        return field_230709_l_; // Screen.height
    }

    public void close() {
        Mc.openScreen(null);
    }

    /** {@code Screen.addButton(widget)} — registers it for both rendering and input. */
    public <T extends Widget> T addWidget(T widget) {
        func_230480_a_(widget);
        return widget;
    }

    /** {@code Screen.buttons} — every widget currently registered. */
    public java.util.List<Widget> widgets() {
        return field_230710_m_;
    }

    private boolean rebuildQueued;

    /**
     * Rebuilds every widget at the start of the next frame.
     *
     * <p>Always use this from inside a button handler. Rebuilding immediately would clear
     * the widget list that the screen's own event dispatch is still iterating over, which
     * throws {@link java.util.ConcurrentModificationException}.
     */
    public void rebuild() {
        rebuildQueued = true;
    }

    /** Rebuilds right now. Only safe outside event dispatch. */
    private void rebuildNow() {
        rebuildQueued = false;
        func_231158_b_(Mc.mc(), screenWidth(), screenHeight()); // Screen.init(mc, width, height)
    }

    /** The standard dimmed-world backdrop. */
    public void drawBackdrop(MatrixStack ms) {
        func_230446_a_(ms); // Screen.renderBackground(ms)
    }

    // ------------------------------------------------------------ SRG plumbing

    @Override
    protected void func_231160_c_() { // init()
        onInit();
    }

    @Override
    public void func_230430_a_(MatrixStack ms, int mouseX, int mouseY, float partialTicks) { // render(...)
        if (rebuildQueued) {
            rebuildNow();
        }
        onRender(ms, mouseX, mouseY, partialTicks);
        super.func_230430_a_(ms, mouseX, mouseY, partialTicks); // draws the registered widgets
        afterWidgets(ms, mouseX, mouseY, partialTicks);
    }

    /** Drawn on top of the widgets — tooltips and overlays belong here. */
    protected void afterWidgets(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
    }

    @Override
    public void func_231023_e_() { // tick()
        super.func_231023_e_();
        onTick();
    }

    @Override
    public boolean func_231044_a_(double mouseX, double mouseY, int button) { // mouseClicked
        return onMouseClicked(mouseX, mouseY, button) || super.func_231044_a_(mouseX, mouseY, button);
    }

    @Override
    public boolean func_231048_c_(double mouseX, double mouseY, int button) { // mouseReleased
        return onMouseReleased(mouseX, mouseY, button) || super.func_231048_c_(mouseX, mouseY, button);
    }

    @Override
    public boolean func_231045_a_(double mouseX, double mouseY, int button, double dx, double dy) { // mouseDragged
        return onMouseDragged(mouseX, mouseY, button, dx, dy)
                || super.func_231045_a_(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean func_231043_a_(double mouseX, double mouseY, double amount) { // mouseScrolled
        return onMouseScrolled(mouseX, mouseY, amount) || super.func_231043_a_(mouseX, mouseY, amount);
    }

    @Override
    public boolean func_231046_a_(int keyCode, int scanCode, int modifiers) { // keyPressed
        if (onKeyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.func_231046_a_(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean func_231042_a_(char typed, int modifiers) { // charTyped
        return onCharTyped(typed, modifiers) || super.func_231042_a_(typed, modifiers);
    }

    @Override
    public void func_231164_f_() { // removed()
        super.func_231164_f_();
        onClosed();
    }

    @Override
    public boolean func_231178_ax__() { // isPauseScreen()
        return pausesGame();
    }
}
