package com.enchantmentcracker.client.gui;

import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.StringTextComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The mod's widgets. They extend vanilla's so the screen's own input dispatch,
 * focus handling and click sounds all work, and only the drawing is replaced.
 */
public final class Widgets {

    private Widgets() {
    }

    /** A plain-text tooltip box in the vanilla style, kept on screen. */
    public static void drawTooltip(MatrixStack ms, List<String> lines, int mouseX, int mouseY,
                                   int screenWidth, int screenHeight) {
        if (lines.isEmpty()) {
            return;
        }
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, Mc.stringWidth(line));
        }
        int height = lines.size() * 10 + 2;
        int x = Math.max(4, Math.min(mouseX + 10, screenWidth - width - 8));
        int y = Math.max(6, Math.min(mouseY - height / 2, screenHeight - height - 6));
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        ms.func_227860_a_();                        // push
        ms.func_227861_a_(0.0, 0.0, 400.0);         // translate: above items
        Theme.darkPanel(ms, x - 3, y - 3, width + 6, height + 4);
        for (int i = 0; i < lines.size(); i++) {
            Mc.shadowText(ms, lines.get(i), x, y + i * 10, Theme.TEXT_LIGHT);
        }
        ms.func_227865_b_();                        // pop
    }

    /** A vanilla-looking button that can also be drawn as "selected". */
    public static class McButton extends Button {

        private String label;
        private List<String> tooltip = new ArrayList<>();
        private Supplier<Boolean> selected = () -> false;
        private Supplier<String> labelSupplier;
        private int labelColour = 0xFFFFFFFF;

        public McButton(int x, int y, int width, int height, String label, Runnable onPress) {
            super(x, y, width, height, new StringTextComponent(label), b -> onPress.run());
            this.label = label;
        }

        public McButton tooltip(String... lines) {
            this.tooltip.clear();
            for (String line : lines) {
                this.tooltip.add(line);
            }
            return this;
        }

        public McButton selectedWhen(Supplier<Boolean> condition) {
            this.selected = condition;
            return this;
        }

        public McButton labelColour(int colour) {
            this.labelColour = colour;
            return this;
        }

        /** A label worked out every frame, for buttons that show live state. */
        public McButton labelFrom(Supplier<String> supplier) {
            this.labelSupplier = supplier;
            return this;
        }

        public void setPosition(int x, int y) {
            field_230690_l_ = x; // Widget.x
            field_230691_m_ = y; // Widget.y
        }

        protected String currentLabel() {
            return labelSupplier != null ? labelSupplier.get() : label;
        }

        public void setLabel(String label) {
            this.label = label;
            func_238482_a_(new StringTextComponent(label)); // setMessage
        }

        public String getLabel() {
            return label;
        }

        public List<String> getTooltip() {
            return tooltip;
        }

        protected int labelColour() {
            return labelColour;
        }

        public int x() {
            return field_230690_l_;
        }

        public int y() {
            return field_230691_m_;
        }

        public int w() {
            return field_230688_j_;
        }

        public int h() {
            return field_230689_k_;
        }

        public boolean hovered() {
            return func_230449_g_(); // isHovered()
        }

        public boolean isEnabled() {
            return field_230693_o_; // active
        }

        public void setEnabled(boolean enabled) {
            field_230693_o_ = enabled;
        }

        @Override
        public void func_230431_b_(MatrixStack ms, int mouseX, int mouseY, float partialTicks) { // renderButton
            int state = !field_230693_o_ ? 0 : (func_230449_g_() || selected.get() ? 2 : 1);
            Theme.buttonBackground(ms, x(), y(), w(), h(), state);
            if (selected.get()) {
                Mc.outline(ms, x(), y(), w(), h(), 0xFFFFFF55);
            }
            int colour = field_230693_o_ ? labelColour() : 0xFFA0A0A0;
            String text = Mc.trim(currentLabel(), w() - 6);
            // Light labels get the vanilla drop shadow; dark ones drop it, or the shadow muddies them.
            Mc.centeredLabel(ms, text, x() + w() / 2, y() + (h() - 8) / 2, colour);
        }
    }

    /** An on/off switch: shows ON in green or OFF in grey, and flips on click. */
    public static class Toggle extends McButton {

        private final Supplier<Boolean> value;

        public Toggle(int x, int y, int width, int height, Supplier<Boolean> value, Runnable onFlip) {
            super(x, y, width, height, "", onFlip);
            this.value = value;
            labelFrom(() -> value.get() ? "ON" : "OFF");
        }

        @Override
        protected int labelColour() {
            return value.get() ? 0xFF7CFF6B : 0xFFBFBFBF;
        }
    }

    /** A tab across the top of the window. The selected one merges into the panel below it. */
    public static class TabButton extends Button {

        private final String label;
        private final Supplier<Boolean> selected;
        private final ItemStack icon;
        /** Easter egg: the compass icon's needle chases the mouse cursor. */
        private boolean compassNeedle;

        public TabButton(int x, int y, int width, int height, String label, ItemStack icon,
                         Supplier<Boolean> selected, Runnable onPress) {
            super(x, y, width, height, new StringTextComponent(label), b -> onPress.run());
            this.label = label;
            this.icon = icon;
            this.selected = selected;
        }

        public TabButton compassNeedle() {
            this.compassNeedle = true;
            return this;
        }

        public String getLabel() {
            return label;
        }

        public boolean hovered() {
            return func_230449_g_(); // isHovered()
        }

        @Override
        public void func_230431_b_(MatrixStack ms, int mouseX, int mouseY, float partialTicks) { // renderButton
            int x = field_230690_l_;
            int y = field_230691_m_;
            int w = field_230688_j_;
            int h = field_230689_k_;
            boolean on = selected.get();

            Mc.outline(ms, x - 1, y - 1, w + 2, h + 2, Theme.BORDER);
            Mc.fill(ms, x, y, x + w, y + h, on ? Theme.PANEL : 0xFF9A9A9A);
            Mc.fill(ms, x, y, x + w - 1, y + 1, on ? Theme.PANEL_LIGHT : 0xFFB4B4B4);
            Mc.fill(ms, x, y, x + 1, y + h, on ? Theme.PANEL_LIGHT : 0xFFB4B4B4);
            if (!on) {
                // Unselected tabs sit a shade lower and keep their bottom edge.
                Mc.fill(ms, x, y + h - 1, x + w, y + h, Theme.PANEL_SHADE);
            } else {
                // The selected tab loses its bottom edge so it runs into the panel.
                Mc.fill(ms, x, y + h - 1, x + w, y + h + 1, Theme.PANEL);
            }

            // Light label + drop shadow so it reads on both the selected (light) and unselected
            // (greyer) tab. Every tab keeps its icon; the name is trimmed only if it truly cannot fit.
            int colour = on ? Theme.TEXT_TITLE : Theme.TEXT_LIGHT;
            boolean hasIcon = icon != null && !icon.func_190926_b();
            if (hasIcon) {
                int iconX = x + 3;
                int iconY = y + (h - 16) / 2;
                // The compass tab's real needle points at the cursor while its icon is drawn.
                if (compassNeedle) {
                    com.enchantmentcracker.client.CompassNeedle.aimAt(iconX + 8, iconY + 8, mouseX, mouseY);
                }
                Mc.drawItem(icon, iconX, iconY);
                if (compassNeedle) {
                    com.enchantmentcracker.client.CompassNeedle.clear();
                }
                int textLeft = iconX + 16 + 2;
                int avail = Math.max(0, (x + w - 3) - textLeft);
                String text = Mc.trim(label, avail);
                Mc.text(ms, text, textLeft + (avail - Mc.stringWidth(text)) / 2, y + (h - 8) / 2, colour);
            } else {
                String text = Mc.trim(label, w - 6);
                Mc.text(ms, text, x + w / 2 - Mc.stringWidth(text) / 2, y + (h - 8) / 2, colour);
            }
        }
    }

    /** A slot-sized button that shows an item, used by the item and material pickers. */
    public static class IconButton extends Button {

        private final ItemStack icon;
        private final Supplier<Boolean> selected;
        private final List<String> tooltip = new ArrayList<>();

        public IconButton(int x, int y, int size, ItemStack icon, String tooltipLine,
                          Supplier<Boolean> selected, Runnable onPress) {
            super(x, y, size, size, new StringTextComponent(""), b -> onPress.run());
            this.icon = icon;
            this.selected = selected;
            if (tooltipLine != null) {
                this.tooltip.add(tooltipLine);
            }
        }

        public List<String> getTooltip() {
            return tooltip;
        }

        public boolean hovered() {
            return func_230449_g_(); // isHovered()
        }

        @Override
        public void func_230431_b_(MatrixStack ms, int mouseX, int mouseY, float partialTicks) { // renderButton
            int x = field_230690_l_;
            int y = field_230691_m_;
            int size = field_230688_j_;
            boolean on = selected.get();

            Theme.inset(ms, x, y, size, size);
            if (on) {
                Mc.fill(ms, x + 1, y + 1, x + size - 1, y + size - 1, 0x80FFFF55);
            } else if (func_230449_g_()) {
                Mc.fill(ms, x + 1, y + 1, x + size - 1, y + size - 1, 0x60FFFFFF);
            }
            if (icon != null && !icon.func_190926_b()) {
                Mc.drawItem(icon, x + (size - 16) / 2, y + (size - 16) / 2);
            }
            if (!field_230693_o_) {
                Mc.fill(ms, x + 1, y + 1, x + size - 1, y + size - 1, 0xA0202020);
            }
            if (on) {
                Mc.outline(ms, x, y, size, size, 0xFFFFFF55);
            }
        }
    }

    /** Text entry with the vanilla look plus input filtering. */
    public static class TextBox extends TextFieldWidget {

        public TextBox(FontRenderer font, int x, int y, int width, int height, String label) {
            super(font, x, y, width, height, new StringTextComponent(label));
        }

        public String text() {
            return func_146179_b(); // getText()
        }

        public void setText(String value) {
            func_146180_a(value); // setText()
        }

        public TextBox maxLength(int max) {
            func_146203_f(max); // setMaxStringLength
            return this;
        }

        /** Digits only. */
        public TextBox numeric() {
            func_200675_a(s -> s.chars().allMatch(Character::isDigit)); // setValidator
            return this;
        }

        /** Hex digits only, upper-cased as you type. */
        public TextBox hex() {
            func_200675_a(s -> s.chars().allMatch(c -> Character.digit(c, 16) >= 0));
            func_212954_a(s -> { // setResponder
                String upper = s.toUpperCase(java.util.Locale.ROOT);
                if (!upper.equals(s)) {
                    func_146180_a(upper);
                }
            });
            return this;
        }

        public int intValue(int fallback) {
            try {
                String t = text().trim();
                return t.isEmpty() ? fallback : Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /**
     * A wishlist row: click to cycle "don't care" → I → II → ... → max → "must not appear".
     * Right-click steps backwards.
     */
    public static class WishButton extends Button {

        private final String enchantment;
        private final int maxLevel;
        private final java.util.function.IntConsumer onChange;
        private final java.util.function.IntSupplier value;
        private boolean allowBan = true;

        /** For lists where "must not appear" makes no sense: cycles off, I, II... only. */
        public WishButton noBan() {
            this.allowBan = false;
            return this;
        }

        public WishButton(int x, int y, int width, int height, String enchantment, int maxLevel,
                          java.util.function.IntSupplier value, java.util.function.IntConsumer onChange) {
            super(x, y, width, height, new StringTextComponent(enchantment), b -> {
            });
            this.enchantment = enchantment;
            this.maxLevel = maxLevel;
            this.value = value;
            this.onChange = onChange;
        }

        @Override
        public void func_230930_b_() { // onPress()
            cycle(1);
        }

        @Override
        public boolean func_231044_a_(double mouseX, double mouseY, int button) { // mouseClicked
            if (field_230693_o_ && field_230694_p_ && func_231047_b_(mouseX, mouseY)) {
                if (button == 1) {
                    cycle(-1);
                    Mc.playClick();
                    return true;
                }
            }
            return super.func_231044_a_(mouseX, mouseY, button);
        }

        private void cycle(int direction) {
            // States run -1 (banned), 0 (don't care), 1..maxLevel (wanted at that level).
            int lowest = allowBan ? -1 : 0;
            int current = value.getAsInt();
            int next = current + direction;
            if (next > maxLevel) {
                next = lowest;
            } else if (next < lowest) {
                next = maxLevel;
            }
            onChange.accept(next);
        }

        @Override
        public void func_230431_b_(MatrixStack ms, int mouseX, int mouseY, float partialTicks) { // renderButton
            int x = field_230690_l_;
            int y = field_230691_m_;
            int w = field_230688_j_;
            int h = field_230689_k_;
            int state = value.getAsInt();

            int background = state > 0 ? 0xFF2B5B22 : state < 0 ? 0xFF5B2222 : 0xFF3A3A3A;
            Mc.fill(ms, x, y, x + w, y + h, background);
            Mc.outline(ms, x, y, w, h, func_230449_g_() ? 0xFFFFFFFF : 0xFF1A1A1A);

            String name = Mc.enchantmentName(enchantment, 0);
            int colour = state > 0 ? 0xFF8BE07A : state < 0 ? 0xFFE08B8B : 0xFFBFBFBF;
            Mc.text(ms, Mc.trim(name, w - 34), x + 4, y + (h - 8) / 2, colour);

            String suffix;
            if (state > 0) {
                suffix = com.enchantmentcracker.core.CrackEnchantments.romanNumeral(state) + "+";
            } else if (state < 0) {
                suffix = "NO";
            } else {
                suffix = "-";
            }
            Mc.text(ms, suffix, x + w - 4 - Mc.stringWidth(suffix), y + (h - 8) / 2, colour);
        }
    }
}
