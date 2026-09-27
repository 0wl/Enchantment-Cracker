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
 * item works, modded ones included. Every stack of that item then shimmers for a few seconds
 * to confirm the choice.
 *
 * <p>Each drop is an ordinary "throw one item" inventory click ({@code ClickType.THROW}) aimed
 * at a junk slot, so it always throws the junk and never whatever you are holding. Before
 * dropping it closes any screen that pauses singleplayer (the enchanting table and inventory
 * screens do), so the thrown items actually fly and scatter away instead of piling at your feet
 * on a frozen server.
 *
 * <p>It aims at drops the server <em>made</em>, as counted by {@link CrackerState} (on a server:
 * from the items it spawns), not at clicks sent: a server can ignore a click, and a drop that did
 * not happen must not be counted. So it keeps a limited number of clicks in flight, and resends
 * only if no drop has arrived for a while.
 */
public final class AutoDropper {

    /** How long the picked item's stacks shimmer. */
    public static final long HIGHLIGHT_MILLIS = 5000;

    private static String junkItem;
    private static boolean pickArmed;

    private static long highlightUntil;

    /** Clicks allowed ahead of the drops the server has confirmed. */
    private static final int MAX_IN_FLIGHT = 12;
    /** Ticks without a confirmed drop after which unconfirmed clicks count as lost. */
    private static final int STALL_TICKS = 60;

    private static boolean running;
    private static int target;
    private static int sent;
    private static int baseDrops;
    private static int lastConfirmed;
    private static int stallTicks;
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
     * Makes whatever is in this slot the junk item, and shimmers every stack of it.
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
        return running;
    }

    /** Drops the server has made since this run started. */
    private static int confirmed() {
        return com.enchantmentcracker.core.CrackerState.get().getItemsDropped() - baseDrops;
    }

    public static int getRemaining() {
        return running ? Math.max(0, target - confirmed()) : 0;
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
        // A paused singleplayer world would freeze the drops so the items never fly; close the
        // screen that pauses it (the table / inventory screen) first. A non-pausing screen (our
        // own window) is left open so the plan stays in view.
        if (Mc.currentScreenPauses()) {
            Mc.openScreen(null);
        }
        running = true;
        target = count;
        sent = 0;
        baseDrops = com.enchantmentcracker.core.CrackerState.get().getItemsDropped();
        lastConfirmed = 0;
        stallTicks = 0;
        message = "Dropping " + count + " " + Mc.itemName(junkItem) + "...";
    }

    public static void stop() {
        if (running) {
            message = "Stopped after " + confirmed() + ".";
        }
        running = false;
    }

    /** Called every client tick. */
    public static void tick() {
        if (!running || Mc.player() == null) {
            return;
        }
        int done = confirmed();
        if (done >= target) {
            running = false;
            message = "Dropped " + done + " " + Mc.itemName(junkItem) + ".";
            Mc.chat("§a[Cracker] " + message);
            return;
        }
        if (done != lastConfirmed) {
            lastConfirmed = done;
            stallTicks = 0;
        } else if (sent > done && ++stallTicks > STALL_TICKS) {
            // Clicks the server ignored (it can, while it resyncs a window): send those again.
            sent = done;
            stallTicks = 0;
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
        for (int i = 0; i < perTick && sent < target && sent - done < MAX_IN_FLIGHT; i++) {
            Slot slot = findJunkSlot(container);
            if (slot == null) {
                message = "Ran out of " + Mc.itemName(junkItem) + " with " + (target - done) + " still to drop.";
                Mc.chat("§c[Cracker] " + message);
                running = false;
                return;
            }
            // playerController.windowClick(windowId, slotNumber, 0, THROW, player): throw one junk item
            Mc.windowClick(container.field_75152_c, slot.field_75222_d, 0, ClickType.THROW);
            sent++;
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
     * Shimmers every stack of the picked junk item in the open container for a few seconds, so
     * you can see exactly what will be thrown. Called from the container's foreground pass, where
     * coordinates are already relative to the container's corner.
     */
    public static void renderHighlight(Container container, MatrixStack ms) {
        if (junkItem == null || container == null || System.currentTimeMillis() >= highlightUntil) {
            return;
        }
        float pulse = 0.5F + 0.5F * (float) Math.sin(System.currentTimeMillis() / 150.0);
        int alpha = (int) (0x40 + 0x50 * pulse);
        RenderSystem.disableDepthTest();
        for (Slot slot : container.field_75151_b) {
            ItemStack stack = slot.func_75211_c();
            if (stack.func_190926_b() || !junkItem.equals(Mc.idOf(stack.func_77973_b()))) {
                continue;
            }
            int x = slot.field_75223_e; // xPos
            int y = slot.field_75221_f; // yPos
            Mc.fill(ms, x, y, x + 16, y + 16, (alpha << 24) | 0x8040FF);
            ItemRenderer renderer = Mc.itemRenderer();
            RenderSystem.enableDepthTest();
            renderer.field_77023_b = 250.0F; // zLevel, above the slot's own item
            renderer.func_180450_b(Mc.glinting(stack), x, y); // renderItemAndEffectIntoGUI
            renderer.field_77023_b = 0.0F;
            RenderSystem.disableDepthTest();
            Mc.outline(ms, x - 1, y - 1, 18, 18, 0xFFB080FF);
        }
        RenderSystem.enableDepthTest();
    }

    public static boolean isHighlighting() {
        return junkItem != null && System.currentTimeMillis() < highlightUntil;
    }
}
