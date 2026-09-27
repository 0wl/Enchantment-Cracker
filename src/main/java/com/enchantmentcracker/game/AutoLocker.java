package com.enchantmentcracker.game;

import com.enchantmentcracker.core.CrackerState;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;

/**
 * "Lock seed": enchants plain books in the cheapest slot, one after another, until the
 * player seed is locked. On a server that takes two enchantments after joining (sometimes a
 * third), which is tedious by hand. Uses only the clicks a player would make: book in, lapis
 * in, click slot 1, take the book out.
 *
 * <p>Between enchantments it waits for the table to show the new book long enough for
 * {@link TableWatcher} to read the XP seed, since that reading is what the lock is made from.
 */
public final class AutoLocker {

    /** Slot 1 costs one level and one lapis. */
    private static final int SLOT = 0;
    /** Most enchantments to spend before giving up (a mod using the RNG in between, say). */
    private static final int MAX_ENCHANTS = 4;
    /** Ticks the table must show a fresh book before it is enchanted: longer than TableWatcher's settle time. */
    private static final int READ_TICKS = 16;
    private static final int TIMEOUT_TICKS = 200;

    private enum Step { CLEAR, LAPIS, BOOK, READ, ENCHANT, WAIT }

    private static boolean running;
    private static Step step;
    private static int ticks;
    private static int enchants;
    private static int syncedBefore;
    private static String message = "";

    private AutoLocker() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static String getMessage() {
        return message;
    }

    /** Starts, or stops if already running. */
    public static void toggle() {
        if (running) {
            stop("Stopped.");
            return;
        }
        if (CrackerState.get().isLocked()) {
            say("§a[Cracker] §fThe seed is already locked.");
            return;
        }
        if (TableWatcher.openContainer() == null) {
            say("§c[Cracker] §fOpen an enchanting table first.");
            return;
        }
        running = true;
        enchants = 0;
        begin(Step.CLEAR);
        say("§a[Cracker] §fLocking the seed: enchanting books in slot 1 (1 level + 1 lapis each)...");
    }

    private static void begin(Step next) {
        step = next;
        ticks = 0;
    }

    private static void stop(String why) {
        running = false;
        message = why;
        say((CrackerState.get().isLocked() ? "§a" : "§e") + "[Cracker] §f" + why);
    }

    private static void say(String line) {
        Mc.chat(line);
    }

    /** Called every client tick. */
    public static void tick() {
        if (!running) {
            return;
        }
        EnchantmentContainer table = TableWatcher.openContainer();
        if (table == null) {
            stop("Stopped: the table was closed.");
            return;
        }
        if (CrackerState.get().isLocked()) {
            if (!table.func_75139_a(0).func_75211_c().func_190926_b()) {
                // Hand the reading book back before finishing.
                Mc.windowClick(table.field_75152_c, 0, 0, ClickType.QUICK_MOVE);
            }
            stop("Seed locked after " + enchants + " enchantment" + (enchants == 1 ? "" : "s") + ". You can plan now.");
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            stop("Stopped: the table did not respond.");
            return;
        }
        switch (step) {
            case CLEAR:
                if (!clearTableSlot(table)) {
                    return; // wait for the slot to empty
                }
                begin(Step.LAPIS);
                return;
            case LAPIS: {
                ItemStack lapis = table.func_75139_a(1).func_75211_c(); // getSlot(1).getStack()
                if (!lapis.func_190926_b()) { // isEmpty
                    begin(Step.BOOK);
                    return;
                }
                Slot from = findInInventory(table, "lapis_lazuli");
                if (from == null) {
                    stop("Stopped: no lapis lazuli in your inventory.");
                    return;
                }
                Mc.windowClick(table.field_75152_c, from.field_75222_d, 0, ClickType.QUICK_MOVE);
                begin(Step.BOOK);
                return;
            }
            case BOOK: {
                if (ticks < 3) {
                    return; // let the lapis land first
                }
                if (TableWatcher.playerLevel() < 1 && !Mc.isCreative()) {
                    stop("Stopped: you need at least one level.");
                    return;
                }
                Slot from = findInInventory(table, "book");
                if (from == null) {
                    stop("Stopped: no plain books left.");
                    return;
                }
                // Pick the stack up, drop one into the table, put the rest back.
                Mc.windowClick(table.field_75152_c, from.field_75222_d, 0, ClickType.PICKUP);
                Mc.windowClick(table.field_75152_c, 0, 1, ClickType.PICKUP);
                Mc.windowClick(table.field_75152_c, from.field_75222_d, 0, ClickType.PICKUP);
                begin(Step.READ);
                return;
            }
            case READ:
                // The server's levels for the new book, held still long enough to be read.
                if (table.field_75167_g[SLOT] <= 0 || !"book".equals(TableWatcher.itemIdIn(table))) {
                    return;
                }
                if (ticks < READ_TICKS) {
                    return;
                }
                begin(Step.ENCHANT);
                return;
            case ENCHANT:
                syncedBefore = table.func_217005_f(); // getXPSeed()
                Mc.enchant(table.field_75152_c, SLOT);
                enchants++;
                begin(Step.WAIT);
                return;
            case WAIT:
                if (table.func_217005_f() == syncedBefore && ticks < 40) {
                    return; // the new XP seed is on its way
                }
                if (enchants >= MAX_ENCHANTS) {
                    stop("Stopped after " + enchants + " enchantments without a lock. Something else may be using "
                            + "your random numbers (effects, sprinting, some modded gear).");
                    return;
                }
                begin(Step.CLEAR);
                return;
            default:
        }
    }

    /** Moves whatever sits in the item slot back to the inventory. True once it is empty. */
    private static boolean clearTableSlot(EnchantmentContainer table) {
        if (table.func_75139_a(0).func_75211_c().func_190926_b()) {
            return true;
        }
        if (ticks % 5 == 1) {
            Mc.windowClick(table.field_75152_c, 0, 0, ClickType.QUICK_MOVE);
        }
        return false;
    }

    /** A slot of the player's own inventory holding this item, unenchanted. */
    private static Slot findInInventory(EnchantmentContainer table, String item) {
        for (Slot slot : table.field_75151_b) { // inventorySlots
            if (slot.field_75224_c != Mc.player().field_71071_by || !slot.func_75216_d()) { // inventory, getHasStack
                continue;
            }
            ItemStack stack = slot.func_75211_c();
            if (item.equals(Mc.idOf(stack.func_77973_b())) && !stack.func_77948_v()) { // getItem, isItemEnchanted
                return slot;
            }
        }
        return null;
    }
}
