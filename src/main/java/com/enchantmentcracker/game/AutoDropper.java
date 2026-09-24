package com.enchantmentcracker.game;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;

/**
 * Drops junk items for you, exactly as many as the plan needs.
 *
 * <p>You pick the junk once: open your inventory, arm the picker (the button above the
 * inventory, or the "Pick auto-drop item" key while hovering a slot) and click the item. Any
 * item works, modded ones included. The slot then shimmers with the enchantment glint for a
 * few seconds to confirm the choice.
 *
 * <p>Each drop is an ordinary "throw one item" inventory click ({@code ClickType.THROW}),
 * the same thing pressing Q over a slot does, sent through the normal container, so it works
 * in singleplayer, on LAN and on servers alike. The server spends four steps of your
 * generator per item thrown, which is what the plan counts on. The drop counter itself is
 * fed by the game's own toss events, so these drops are counted exactly like manual ones.
 */
public final class AutoDropper {

    /** How long the picked slot shimmers. */
    public static final long HIGHLIGHT_MILLIS = 5000;

    private static String junkItem;
    private static boolean pickArmed;

    private static Slot highlightSlot;
    private static Container highlightContainer;
    private static long highlightUntil;

    private static int remaining;
    private static int dropped;
    private static int perTick = 2;
    private static String message = "";

    private AutoDropper() {
    }

    // ------------------------------------------------------------------ the junk item

    public static String getJunkItem() {
        return junkItem;
    }

    public static void setJunkItem(String item) {
        junkItem = item == null || item.isEmpty() ? null : item;
    }

    public static boolean isPickArmed() {
        return pickArmed;
    }

    public static void setPickArmed(boolean armed) {
        pickArmed = armed;
    }

    /**
     * Makes whatever is in this slot the junk item, and makes the slot shimmer.
     *
     * @return false if the slot is empty
     */
    public static boolean pick(Container container, Slot slot) {
        pickArmed = false;
        if (slot == null || !slot.func_75216_d()) { // getHasStack
            return false;
        }
        ItemStack stack = slot.func_75211_c(); // getStack
        junkItem = Mc.idOf(stack.func_77973_b());
        highlightSlot = slot;
        highlightContainer = container;
        highlightUntil = System.currentTimeMillis() + HIGHLIGHT_MILLIS;
        Mc.playClick();
        message = "Junk item: " + Mc.itemName(junkItem);
        return true;
    }

    /** How many of the junk item the player is carrying. */
    public static int junkCount() {
        Container container = Mc.openContainer();
        if (container == null || junkItem == null) {
            return 0;
        }
        int total = 0;
        for (Slot slot : container.field_75151_b) { // inventorySlots
            if (isJunk(slot)) {
                total += slot.func_75211_c().func_190916_E(); // getStack().getCount()
            }
        }
        return total;
    }

    private static boolean isJunk(Slot slot) {
        if (junkItem == null || slot.field_75224_c != Mc.player().field_71071_by) { // slot.inventory != player.inventory
            return false;
        }
        if (slot.getSlotIndex() >= 36) {
            return false; // armour and offhand are never junk, whatever is in them
        }
        ItemStack stack = slot.func_75211_c();
        return !stack.func_190926_b() && junkItem.equals(Mc.idOf(stack.func_77973_b()));
    }

    // ------------------------------------------------------------------ dropping

    public static boolean isRunning() {
        return remaining > 0;
    }

    public static int getRemaining() {
        return remaining;
    }

    public static String getMessage() {
        return message;
    }

    public static void setPerTick(int value) {
        perTick = Math.max(1, Math.min(8, value));
    }

    /** Starts dropping {@code count} single items of the junk type. */
    public static void start(int count) {
        if (junkItem == null) {
            message = "Pick a junk item first: open your inventory and use Pick junk.";
            Mc.chat("§c[Cracker] " + message);
            return;
        }
        if (count <= 0) {
            message = "Nothing to drop.";
            return;
        }
        int have = junkCount();
        if (have < count) {
            message = "Need " + count + " " + Mc.itemName(junkItem) + ", only carrying " + have + ".";
            Mc.chat("§c[Cracker] " + message);
            return;
        }
        remaining = count;
        dropped = 0;
        message = "Dropping " + count + " " + Mc.itemName(junkItem) + "...";
    }

    public static void stop() {
        if (remaining > 0) {
            message = "Stopped after " + dropped + ".";
        }
        remaining = 0;
    }

    /** Called every client tick. */
    public static void tick() {
        if (remaining <= 0 || Mc.player() == null) {
            return;
        }
        Container container = Mc.openContainer();
        if (container == null) {
            return;
        }
        // THROW only works with nothing on the cursor; wait for the player to put it down.
        if (!Mc.cursorStack().func_190926_b()) {
            message = "Put down the item on your cursor to keep dropping.";
            return;
        }
        for (int i = 0; i < perTick && remaining > 0; i++) {
            Slot slot = findJunkSlot(container);
            if (slot == null) {
                message = "Ran out of " + Mc.itemName(junkItem) + " with " + remaining + " still to drop.";
                Mc.chat("§c[Cracker] " + message);
                remaining = 0;
                return;
            }
            // playerController.windowClick(windowId, slotNumber, 0, THROW, player): drop one item
            Mc.windowClick(container.field_75152_c, slot.field_75222_d, 0, ClickType.THROW);
            remaining--;
            dropped++;
        }
        if (remaining == 0) {
            message = "Dropped " + dropped + " " + Mc.itemName(junkItem) + ".";
            Mc.chat("§a[Cracker] " + message);
        }
    }

    private static Slot findJunkSlot(Container container) {
        for (Slot slot : container.field_75151_b) {
            if (isJunk(slot)) {
                return slot;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ highlight

    /**
     * Draws the picked slot with the enchantment glint over it for a few seconds. Called
     * from the container's foreground pass, where coordinates are already relative to the
     * container's corner.
     */
    public static void renderHighlight(Container container, MatrixStack ms) {
        if (highlightSlot == null || container != highlightContainer) {
            return;
        }
        long left = highlightUntil - System.currentTimeMillis();
        if (left <= 0) {
            highlightSlot = null;
            highlightContainer = null;
            return;
        }
        int x = highlightSlot.field_75223_e; // xPos
        int y = highlightSlot.field_75221_f; // yPos
        ItemStack stack = highlightSlot.func_75211_c();

        // A purple pulse behind, the item redrawn with a forced glint on top of the original.
        float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 150.0);
        int alpha = (int) (0x40 + 0x50 * pulse);
        RenderSystem.disableDepthTest();
        Mc.fill(ms, x, y, x + 16, y + 16, (alpha << 24) | 0x8040FF);
        if (!stack.func_190926_b()) {
            ItemRenderer renderer = Mc.itemRenderer();
            RenderSystem.enableDepthTest();
            renderer.field_77023_b = 250.0F; // zLevel, above the slot's own item
            renderer.func_180450_b(Mc.glinting(stack), x, y); // renderItemAndEffectIntoGUI
            renderer.field_77023_b = 0.0F;
            RenderSystem.disableDepthTest();
        }
        Mc.outline(ms, x - 1, y - 1, 18, 18, 0xFFB080FF);
        RenderSystem.enableDepthTest();
    }

    public static boolean isHighlighting() {
        return highlightSlot != null && highlightUntil > System.currentTimeMillis();
    }
}
