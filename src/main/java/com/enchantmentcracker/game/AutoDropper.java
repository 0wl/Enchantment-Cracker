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
 * <p>Dropping first closes any open screen, so singleplayer is not paused and the view is ours,
 * then it looks level and throws the junk forward, and restores the view when finished. In your
 * own world (the seed is re-read from the server each tick, so nothing needs counting) it uses
 * the look-direction "press Q" drop, so the items fly ahead of you instead of scattering at your
 * feet. On a real server that programmatic drop is not reported back to us, so there it falls
 * back to the inventory throw, which is client-simulated and counted for the seed tracking.
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

    /** The view and hotbar slot saved before dropping, restored when it finishes or stops. */
    private static boolean stateSaved;
    private static float savedYaw;
    private static float savedPitch;
    private static int savedHotbar;

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
        // Close any open screen so singleplayer is not paused (the enchanting-table and inventory
        // screens pause it) and the view is under our control; then face level, drop forward, and
        // put the view back when done.
        Mc.openScreen(null);
        if (!stateSaved && Mc.player() != null) {
            savedYaw = Mc.playerYaw();
            savedPitch = Mc.playerPitch();
            savedHotbar = Mc.selectedHotbar();
            stateSaved = true;
        }
        message = "Dropping " + count + " " + Mc.itemName(junkItem) + "...";
    }

    public static void stop() {
        if (remaining > 0) {
            message = "Stopped after " + dropped + ".";
        }
        remaining = 0;
        restore();
    }

    /** Puts the view and selected hotbar slot back the way they were before dropping. */
    private static void restore() {
        if (stateSaved) {
            Mc.setPlayerLook(savedYaw, savedPitch);
            Mc.setSelectedHotbar(savedHotbar);
            stateSaved = false;
        }
    }

    private static void ranOut() {
        message = "Ran out of " + Mc.itemName(junkItem) + " with " + remaining + " still to drop.";
        Mc.chat("§c[Cracker] " + message);
        remaining = 0;
        restore();
    }

    /** Called every client tick. */
    public static void tick() {
        if (remaining <= 0 || Mc.player() == null) {
            return;
        }
        // Wait until the screen is closed (start() closes it) and the cursor is empty.
        if (Mc.currentScreen() != null || !Mc.cursorStack().func_190926_b()) {
            return;
        }
        Container container = Mc.openContainer();
        if (container == null) {
            return;
        }
        // Keep looking level and forward while dropping, so thrown items travel ahead of you.
        Mc.setPlayerLook(Mc.playerYaw(), 0.0F);

        // In your own world the seed is re-read from the server every tick, so we can drop in the
        // look direction (forward) with the "press Q" drop. On a real server that drop is not
        // counted for us, so there we keep the inventory throw, which is client-simulated and
        // counted (it scatters randomly, but the seed stays tracked).
        boolean ownWorld = ServerRng.isAvailable();
        for (int i = 0; i < perTick && remaining > 0; i++) {
            if (ownWorld) {
                if (!ensureJunkHeld(container)) {
                    ranOut();
                    return;
                }
                if (!isJunkStack(Mc.heldStack())) {
                    break; // a refill swap has not applied yet; try again next tick
                }
                Mc.dropSelected(false); // drop one, in the look direction
            } else {
                Slot slot = findJunkSlot(container);
                if (slot == null) {
                    ranOut();
                    return;
                }
                Mc.windowClick(container.field_75152_c, slot.field_75222_d, 0, ClickType.THROW);
            }
            remaining--;
            dropped++;
        }
        if (remaining == 0) {
            message = "Dropped " + dropped + " " + Mc.itemName(junkItem) + ".";
            Mc.chat("§a[Cracker] " + message);
            restore();
        }
    }

    /**
     * Makes sure the selected hotbar slot holds junk, so the look-direction drop throws junk:
     * selects a hotbar slot that already has it, or swaps a stack up from the main inventory.
     *
     * @return false only when there is no junk left anywhere
     */
    private static boolean ensureJunkHeld(Container container) {
        if (isJunkStack(Mc.heldStack())) {
            return true;
        }
        int hotbar = findHotbarJunk(container);
        if (hotbar >= 0) {
            Mc.setSelectedHotbar(hotbar);
            return true;
        }
        Slot fromInventory = findMainInventoryJunk(container);
        if (fromInventory == null) {
            return false;
        }
        // SWAP the main-inventory junk stack into the selected hotbar slot.
        Mc.windowClick(container.field_75152_c, fromInventory.field_75222_d, Mc.selectedHotbar(), ClickType.SWAP);
        return true;
    }

    private static boolean isJunkStack(ItemStack stack) {
        return stack != null && !stack.func_190926_b() && junkItem != null
                && junkItem.equals(Mc.idOf(stack.func_77973_b()));
    }

    /** Hotbar slot index (0-8) that holds junk, or -1. */
    private static int findHotbarJunk(Container container) {
        for (Slot slot : container.field_75151_b) {
            if (isJunk(slot) && slot.getSlotIndex() >= 0 && slot.getSlotIndex() < 9) {
                return slot.getSlotIndex();
            }
        }
        return -1;
    }

    /** A main-inventory (non-hotbar) slot holding junk, or null. */
    private static Slot findMainInventoryJunk(Container container) {
        for (Slot slot : container.field_75151_b) {
            if (isJunk(slot) && slot.getSlotIndex() >= 9 && slot.getSlotIndex() < 36) {
                return slot;
            }
        }
        return null;
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
