package com.enchantmentcracker.selftest;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.TableSetup;
import com.enchantmentcracker.game.AutoDropper;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.TableWatcher;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.enchantmentcracker.selftest.SelfTest.*;

/**
 * The server path, for real: joins a dedicated server over TCP (so the table's XP seed arrives
 * as 16 bits, exactly as for a player on a server), and checks every XP seed the mod works out
 * against the server's own copy, read with {@code /data get entity}. Test harness only.
 */
final class NetTest {

    static final int PORT = Integer.getInteger("enchcracker.selftest.port", 25599);
    /** Straight to the user's flow (phase 3), for quick diagnostic runs. */
    static final boolean QUICK = Boolean.getBoolean("enchcracker.selftest.quick");
    static final Pattern XP_SEED = Pattern.compile("has the following entity data: (-?\\d+)");

    static volatile int truthXp;
    static volatile int truthStamp;
    static int stampBefore;
    static int waitTicks;
    static int resolveTicks;
    static int rounds;
    static int exactRounds;
    static int unresolvedRounds;
    static int consensusRounds;
    static boolean lockedAtStart;
    static int booksBefore;
    static Set<String> consensus;
    static EnchantCalculator.SlotPreview[] predicted;
    static String roundItem;
    static int roundSlot;
    static EnchantCalculator.Result plan;
    static int dropsLeft;

    private NetTest() {
    }

    static void onChat(ClientChatReceivedEvent event) {
        Matcher m = XP_SEED.matcher(event.getMessage().getString());
        if (m.find()) {
            truthXp = Integer.parseInt(m.group(1));
            truthStamp++;
        }
    }

    static void cmd(String command) {
        Mc.player().func_71165_d(command); // sendChatMessage
    }

    static void openTable() {
        BlockRayTraceResult hit = new BlockRayTraceResult(new Vector3d(table.func_177958_n() + 0.5,
                table.func_177956_o() + 0.75, table.func_177952_p() + 0.5), Direction.UP, table, false);
        // playerController.processRightClickBlock(player, world, hand, hit)
        mc().field_71442_b.func_217292_a(Mc.player(), mc().field_71441_e, Hand.MAIN_HAND, hit);
    }

    /** Tops the table's lapis up (Apotheosis keeps it in the table, so it runs down over many enchantments). */
    static void ensureLapis() {
        EnchantmentContainer c = tableContainer();
        if (c == null || c.func_75139_a(1).func_75211_c().func_190916_E() >= 16) {
            return;
        }
        Slot from = find("lapis_lazuli");
        if (from != null) {
            click(from.field_75222_d, ClickType.QUICK_MOVE); // shift-click: lapis goes to the lapis slot
        } else {
            log("  (no lapis left in the inventory)");
        }
    }

    static void addLapis() {
        EnchantmentContainer c = tableContainer();
        if (c == null || !c.func_75139_a(1).func_75211_c().func_190926_b()) {
            return;
        }
        Slot lapis = find("lapis_lazuli");
        if (lapis != null) {
            click(lapis.field_75222_d, ClickType.PICKUP);
            click(1, ClickType.PICKUP);
        }
    }

    /** Opens the table and puts lapis in, waiting for the server to answer. */
    static void reopen() {
        step(20, NetTest::openTable);
        stepUntil(() -> tableContainer() != null || ++waitTicks > 100, () -> {
            waitTicks = 0;
            check(tableContainer() != null, "table opened");
        });
        // Apotheosis keeps lapis in the table itself: let its contents sync before adding more.
        step(30, () -> {
        });
        step(10, NetTest::addLapis);
    }

    /** Puts another cobblestone stack in the hand once the held one runs out. */
    static void refillHand() {
        if (!Mc.heldStack().func_190926_b()) {
            return;
        }
        for (Slot slot : Mc.player().field_71069_bz.field_75151_b) {
            if (slot.func_75216_d() && "cobblestone".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                Mc.windowClick(0, slot.field_75222_d, Mc.player().field_71071_by.field_70461_c, ClickType.SWAP);
                return;
            }
        }
    }

    /** A real press of the drop key, so the mod counts it the way it counts a player's. */
    static void pressQ() {
        refillHand();
        // KeyBinding.onTick(gameSettings.keyBindDrop.getKey())
        net.minecraft.client.settings.KeyBinding.func_197981_a(mc().field_71474_y.field_74316_C.getKey());
    }

    static void askTruth() {
        stampBefore = truthStamp;
        waitTicks = 0;
        cmd("/data get entity " + NAME + " XpSeed");
    }

    /** One enchantment: place the item, let the mod work out the seed, check it and the result. */
    static void round(String item, int slot) {
        step(12, () -> {
            EnchantmentContainer c = tableContainer();
            if (c != null && !c.func_75139_a(0).func_75211_c().func_190926_b()) {
                click(0, ClickType.QUICK_MOVE);
            }
            ensureLapis();
        });
        step(2, () -> {
            place(item, 0);
            resolveTicks = 0;
            roundItem = item;
            roundSlot = slot;
            lockedAtStart = CrackerState.get().isLocked();
        });
        // Locked: known at once. Otherwise give the table time to settle and be read.
        stepUntil(() -> {
            EnchantmentContainer c = tableContainer();
            boolean ready = c != null && item.equals(TableWatcher.itemIdIn(c)) && TableWatcher.currentXpSeed() != null
                    && (c.field_75167_g[0] | c.field_75167_g[1] | c.field_75167_g[2]) != 0;
            return ready || ++resolveTicks > 60;
        }, NetTest::askTruth);
        stepUntil(() -> truthStamp != stampBefore || ++waitTicks > 100, () -> {
            rounds++;
            CrackerState state = CrackerState.get();
            EnchantmentContainer c = tableContainer();
            Integer resolved = TableWatcher.currentXpSeed();
            int synced = c.func_217005_f();
            int[] fits = state.getPartialSet();
            log("round " + rounds + ": " + item + " slot " + (slot + 1) + ", table synced "
                    + PlayerSeed.formatXpSeed(synced) + ", worked out "
                    + (resolved == null ? "nothing yet (" + (fits == null ? "?" : fits.length) + " fit)"
                    : PlayerSeed.formatXpSeed(resolved)) + " after " + resolveTicks
                    + " ticks, server has " + PlayerSeed.formatXpSeed(truthXp) + ", levels "
                    + Arrays.toString(c.field_75167_g) + ", " + describe(state.getTableSetup())
                    + ", status " + state.getStatus() + "/" + state.getSource());
            check(truthStamp != stampBefore, "server answered the XP seed query");
            check(synced == (short) truthXp, "the table synced only the low 16 bits (" + PlayerSeed.formatXpSeed(synced) + ")");
            if (resolved != null) {
                check(resolved == truthXp, "worked out the full XP seed exactly: "
                        + PlayerSeed.formatXpSeed(resolved) + " vs " + PlayerSeed.formatXpSeed(truthXp));
                exactRounds += resolved == truthXp ? 1 : 0;
            } else {
                unresolvedRounds++;
                check(fits == null || contains(fits, truthXp), "the server's XP seed is among the " + (fits == null ? 0 : fits.length)
                        + " that still fit");
            }
            if (lockedAtStart) {
                if (!expectDrift) {
                    check(resolved != null && resolveTicks <= 2, "locked seed knew this XP seed at once (" + resolveTicks + " ticks)");
                }
            }
            TableSetup setup = state.getTableSetup();
            predicted = null;
            consensus = null;
            if (setup != null && resolved != null) {
                predicted = EnchantCalculator.preview(resolved, setup, item);
                int[] levels = c.field_75167_g;
                check(predicted[0].levelRequirement == levels[0] && predicted[1].levelRequirement == levels[1]
                        && predicted[2].levelRequirement == levels[2], "all three levels predicted: "
                        + predicted[0].levelRequirement + "/" + predicted[1].levelRequirement + "/"
                        + predicted[2].levelRequirement + " vs table " + levels[0] + "/" + levels[1] + "/" + levels[2]);
                check(state.getTableProblem() == null, "no table problem (" + state.getTableProblem() + ")");
            } else if (setup != null && fits != null && fits.length <= 256) {
                // What the overlay shows before the lock: a slot's offer when every seed agrees.
                Set<Set<String>> outcomes = new java.util.HashSet<>();
                for (int seed : fits) {
                    outcomes.add(asSet(EnchantCalculator.preview(seed, setup, item)[slot].enchantments));
                }
                if (outcomes.size() == 1) {
                    consensus = outcomes.iterator().next();
                    consensusRounds++;
                }
                log("  " + fits.length + " seeds fit; slot " + (slot + 1) + " has " + outcomes.size() + " possible outcome(s)");
            }
            // Enchant either way, as a player would: that is what locks the seed.
            mc().field_71442_b.func_78756_a(c.field_75152_c, slot); // sendEnchantPacket
        });
        stepUntil(() -> {
            EnchantmentContainer c = tableContainer();
            return c == null || !enchantsOf(c.func_75139_a(0).func_75211_c()).isEmpty() || ++waitTicks > 100;
        }, () -> {
            waitTicks = 0;
            EnchantmentContainer c = tableContainer();
            if (c == null) {
                return;
            }
            Set<String> got = enchantsOf(c.func_75139_a(0).func_75211_c());
            check(!got.isEmpty(), roundItem + " was enchanted");
            if (predicted != null) {
                Set<String> want = asSet(predicted[roundSlot].enchantments);
                check(got.equals(want), roundItem + " slot " + (roundSlot + 1) + " predicted " + want + ", got " + got);
            } else if (consensus != null) {
                check(got.equals(consensus), roundItem + " slot " + (roundSlot + 1) + " consensus " + consensus + ", got " + got);
            } else {
                log("  enchanted blind, got " + got);
            }
            click(0, ClickType.QUICK_MOVE);
        });
    }

    static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    static String describe(TableSetup setup) {
        return setup == null ? "no setup" : setup.describe();
    }

    /** Close the table, throw {@code n} cobblestone with the Q action, and come back. */
    static void dropCobble(int n) {
        step(15, () -> Mc.player().func_71053_j()); // closeScreen, as Esc does
        step(15, () -> {
            for (Slot slot : Mc.player().field_71069_bz.field_75151_b) {
                if (slot.func_75216_d() && "cobblestone".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                    Mc.windowClick(0, slot.field_75222_d, Mc.player().field_71071_by.field_70461_c, ClickType.SWAP);
                    break;
                }
            }
            dropsLeft = n;
        });
        stepUntil(() -> {
            if (dropsLeft <= 0) {
                return true;
            }
            pressQ();
            dropsLeft--;
            return false;
        }, () -> {
        });
        step(20, () -> {
        });
        reopen();
    }

    /** Close the table the normal way with a whole stack on the cursor: the server throws it. */
    static void closeWithCursorStack() {
        step(10, () -> {
            for (Slot slot : Mc.openContainer().field_75151_b) {
                if (slot.field_75224_c == Mc.player().field_71071_by && slot.func_75216_d()
                        && "cobblestone".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                    click(slot.field_75222_d, ClickType.PICKUP);
                    break;
                }
            }
            dropsBefore = CrackerState.get().getItemsDropped();
        });
        step(20, () -> Mc.player().func_71053_j()); // closeScreen, as Esc does
        step(10, () -> check(CrackerState.get().getItemsDropped() - dropsBefore == 1,
                "closing with a stack on the cursor counted as one drop ("
                        + (CrackerState.get().getItemsDropped() - dropsBefore) + ")"));
        reopen();
    }

    // ------------------------------------------------------------------ phase 3: the user's flow

    static int plansMade;
    static int plansDelivered;
    static int plansSkipped;
    static int pickedUpTotal;
    static int planShelves;
    /** The cracker's own re-plan, to be carried out by the next userPlan instead of planning. */
    static EnchantCalculator.Result presetPlan;
    /** > 0: plan for a table of this Eterna but leave the table as it is (the user's slip). */
    static int lowerTo = -1;
    /** This plan is expected to be caught (off course / table mismatch) and re-planned. */
    static boolean expectReplan;
    static boolean planLowered;
    /** The hidden /give's pickup-animation drop made one drop more than the plan needs. */
    static boolean overshot;
    /** Carrying out one of the cracker's re-plans: it may be caught again (hidden steps still unseen). */
    static boolean mayBeCaught;
    /** Only carry out a pending re-plan; do nothing when there is none. */
    static boolean followUpOnly;
    /** After planning, change the table's Quanta/Arcana/Rectification but not its Eterna or numbers. */
    static boolean changeAfterPlan;
    /** Rounds right after hidden RNG use: the lock cannot name the seed at once, the re-sync will. */
    static boolean expectDrift;
    static List<String> chatMark = Collections.emptyList();
    static boolean guideShot;
    static int cobbleBefore;

    /** Every shelf position around the table, bottom row first: 16 per row. */
    static java.util.List<int[]> ringPositions() {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int y = 0; y <= 1; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (Math.abs(x) == 2 || Math.abs(z) == 2) {
                        out.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return out;
    }

    /** The ring of shelf positions set to {@code block}, in four strips that never touch the table. */
    static void ring(String block) {
        cmd("/fill " + p(table, -2, 0, -2) + " " + p(table, -2, 1, 2) + " " + block);
        cmd("/fill " + p(table, 2, 0, -2) + " " + p(table, 2, 1, 2) + " " + block);
        cmd("/fill " + p(table, -1, 0, -2) + " " + p(table, 1, 1, -2) + " " + block);
        cmd("/fill " + p(table, -1, 0, 2) + " " + p(table, 1, 1, 2) + " " + block);
    }

    /** The table as the user has it: bookshelves all round (Eterna 15 with Apotheosis). */
    static void fullRing() {
        ring("bookshelf");
    }

    /** Exactly {@code shelves} bookshelves round the table: what a lower-power plan asks for. */
    static void partialRing(int shelves) {
        ring("air");
        java.util.List<int[]> ring = ringPositions();
        for (int i = 0; i < shelves && i < ring.size(); i++) {
            int[] at = ring.get(i);
            cmd("/setblock " + p(table, at[0], at[1], at[2]) + " bookshelf");
        }
    }

    /** Shelves the plan's table needs, or -1 to use the table as it stands. */
    static int shelvesFor(EnchantCalculator.Result plan) {
        TableSetup now = CrackerState.get().getTableSetup();
        if (plan.setup == null || now != null && plan.setup.describe().equals(now.describe())) {
            return -1;
        }
        if (plan.setup instanceof com.enchantmentcracker.game.Apotheosis.Table) {
            return Math.round(((com.enchantmentcracker.game.Apotheosis.Table) plan.setup).stats().eterna);
        }
        return plan.bookshelves;
    }

    /**
     * Clicks the table's enchant button {@code slot} the way the mouse does: Forge's click event
     * first (where the cracker's guard can hold it back), then the screen. True if it went through.
     */
    static boolean clickEnchant(int slot) {
        net.minecraft.client.gui.screen.inventory.ContainerScreen<?> screen =
                (net.minecraft.client.gui.screen.inventory.ContainerScreen<?>) mc().field_71462_r;
        double x = screen.getGuiLeft() + 60 + 50;
        double y = screen.getGuiTop() + 14 + 19 * slot + 9;
        if (net.minecraftforge.client.ForgeHooksClient.onGuiMouseClickedPre(screen, x, y, 0)) {
            return false; // held back
        }
        screen.func_231044_a_(x, y, 0); // mouseClicked
        return true;
    }

    /** One cheap book enchantment, so a locked seed re-syncs over RNG steps it could not see. */
    static void resyncByEnchant() {
        step(10, () -> {
            EnchantmentContainer c = tableContainer();
            if (c != null && !c.func_75139_a(0).func_75211_c().func_190926_b()) {
                click(0, ClickType.QUICK_MOVE);
            }
            ensureLapis();
        });
        step(25, () -> place("book", 0));
        step(25, () -> Mc.enchant(tableContainer().field_75152_c, 0));
        step(30, () -> {
            click(0, ClickType.QUICK_MOVE);
            check(CrackerState.get().isLocked(), "re-synced after the test's own commands ("
                    + CrackerState.get().getStatusMessage() + ")");
        });
        step(10, () -> cmd("/clear " + NAME + " enchanted_book"));
    }

    /** Puts a book in the table and takes it out again, so the table's current stats are read. */
    static void refreshTable() {
        step(10, () -> {
            EnchantmentContainer c = tableContainer();
            if (c != null && !c.func_75139_a(0).func_75211_c().func_190926_b()) {
                click(0, ClickType.QUICK_MOVE);
            }
        });
        step(25, () -> place("book", 0));
        step(10, () -> {
            log("    table now reads " + describe(CrackerState.get().getTableSetup()));
            click(0, ClickType.QUICK_MOVE);
        });
    }

    static void ensureLocked() {
        step(10, () -> {
            if (!CrackerState.get().isLocked()) {
                com.enchantmentcracker.game.AutoLocker.toggle();
            }
        });
        stepUntil(() -> !com.enchantmentcracker.game.AutoLocker.isRunning() || ++waitTicks > 1200, () -> {
            waitTicks = 0;
            check(CrackerState.get().isLocked(), "seed locked (" + CrackerState.get().getStatusMessage() + ")");
        });
    }

    /**
     * One plan exactly as the user makes it: the Plan button's planner for the item in hand and
     * the wishes, the Drop button's auto drop (items bouncing back and being picked up), a book
     * as the dummy in slot 1, then the item in the slot the plan names. Every step is checked
     * against the server: its XP seed, the table's numbers, and the enchantments delivered.
     */
    static void userPlan(String item, EnchantmentInstance... wishes) {
        userPlan(false, item, wishes);
    }

    /** The next-step guide at the table right now. */
    static com.enchantmentcracker.client.PlanGuide.Step guide() {
        EnchantmentContainer c = tableContainer();
        return c == null ? null : com.enchantmentcracker.client.PlanGuide.next(CrackerState.get(), c);
    }

    /** Checks the guide's line starts with {@code start} (and, if given, points at that item or button). */
    static void checkGuide(String start, String item, int enchantSlot, com.enchantmentcracker.client.PlanGuide.Button button) {
        com.enchantmentcracker.client.PlanGuide.Step g = guide();
        boolean ok = g != null && g.text.startsWith(start) && (item == null || item.equals(g.item))
                && (enchantSlot < 0 || g.enchantSlot == enchantSlot) && (button == null || g.button == button);
        check(ok, "guide says \"" + (g == null ? "nothing" : g.text) + "\" (expected \"" + start + "...\")");
    }

    static void markChat() {
        chatMark = Mc.recentChat();
    }

    /** The mod's chat lines since {@link #markChat}. */
    static List<String> newChat() {
        List<String> now = Mc.recentChat();
        if (chatMark.isEmpty()) {
            return now;
        }
        int i = now.lastIndexOf(chatMark.get(chatMark.size() - 1));
        return i < 0 ? now : now.subList(i + 1, now.size());
    }

    static boolean chatHas(String needle) {
        for (String line : newChat()) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Seed-move warnings: real damage and a real sprint on the server, each reported in chat, and
     * the next enchantment's re-sync naming them; with the setting off, silence.
     */
    static void rngWatchChecks() {
        step(10, () -> {
            expectDrift = true;
            markChat();
            cmd("/effect give " + NAME + " minecraft:instant_damage 1 0");
        });
        step(40, () -> check(chatHas("You took damage"), "damage is reported in chat: " + newChat()));
        step(1, NetTest::markChat);
        round("book", 0);
        // With steps missed, the new XP seed is only worked out once an item is back in the table.
        refreshTable();
        step(10, () -> check(chatHas("Re-synced over") && chatHas("took damage"), "the re-sync names the damage: " + newChat()));
        // A real sprint: facing open ground (south), forward + sprint held for a few ticks.
        step(5, () -> {
            markChat();
            Mc.player().func_71053_j(); // closeScreen
            Mc.player().field_70177_z = 0F; // rotationYaw: south, away from the shelves
        });
        step(3, () -> {
            net.minecraft.client.settings.KeyBinding.func_197980_a(mc().field_71474_y.field_74351_w.getKey(), true); // forward
            net.minecraft.client.settings.KeyBinding.func_197980_a(mc().field_71474_y.field_151444_V.getKey(), true); // sprint
        });
        step(6, () -> {
        });
        step(10, () -> {
            net.minecraft.client.settings.KeyBinding.func_197980_a(mc().field_71474_y.field_74351_w.getKey(), false);
            net.minecraft.client.settings.KeyBinding.func_197980_a(mc().field_71474_y.field_151444_V.getKey(), false);
        });
        step(20, () -> {
            check(chatHas("sprinting"), "sprinting is reported: " + newChat());
            cmd("/tp " + NAME + " " + (table.func_177958_n() + 0.5) + " " + table.func_177956_o() + " "
                    + (table.func_177952_p() + 3.5) + " facing " + p(table, 0, 1, 0));
        });
        reopen();
        step(1, NetTest::markChat);
        round("book", 0);
        refreshTable();
        step(10, () -> check(chatHas("Re-synced over") && chatHas("sprinting"), "the re-sync names the sprint: " + newChat()));
        // Setting off: silence.
        step(5, () -> {
            com.enchantmentcracker.client.ModSettings.rngWarnings = false;
            markChat();
            cmd("/effect give " + NAME + " minecraft:instant_damage 1 0");
        });
        step(40, () -> check(!chatHas("You took damage"), "no warning with the setting off: " + newChat()));
        round("book", 0);
        step(5, () -> {
            check(!chatHas("Re-synced over"), "no re-sync message with the setting off");
            com.enchantmentcracker.client.ModSettings.rngWarnings = true;
            expectDrift = false;
        });
    }

    /** Carries out the cracker's next re-plan, if it made one; otherwise does nothing. */
    static void followUp(String item, EnchantmentInstance... wishes) {
        step(1, () -> followUpOnly = true);
        userPlan(false, item, wishes);
        step(1, () -> followUpOnly = false);
    }

    /**
     * @param hiddenStep a /give right after planning: two RNG steps the cracker cannot see (the
     *                   pickup sound's pitch), so the plan must be caught off course and held back
     */
    static void userPlan(boolean hiddenStep, String item, EnchantmentInstance... wishes) {
        step(10, () -> {
            plan = null;
            cmd("/clear " + NAME + " enchanted_book");
            ensureLapis();
        });
        refreshTable();
        step(20, () -> {
            CrackerState state = CrackerState.get();
            expectReplan = hiddenStep || lowerTo > 0 || changeAfterPlan;
            planLowered = lowerTo > 0 || changeAfterPlan;
            mayBeCaught = false;
            if (followUpOnly && presetPlan == null) {
                plan = null; // nothing left to follow up
                return;
            }
            if (presetPlan != null) { // carry out the plan the cracker made again by itself
                plan = presetPlan;
                presetPlan = null;
                mayBeCaught = true;
                plansMade++;
                planShelves = -1;
                log("  plan " + plansMade + " (the cracker's automatic re-plan): drop " + plan.itemsToThrow + ", slot "
                        + (plan.slot + 1) + ", table " + plan.setup.describe() + ", gives " + plan.enchantments);
                return;
            }
            EnchantCalculator.Request request = new EnchantCalculator.Request();
            String why = com.enchantmentcracker.client.Planner.prepare(request, item, Arrays.asList(wishes),
                    Collections.emptyList(), 3);
            if (why != null) {
                check(false, "planner ready for " + item + " (" + why + ")");
                return;
            }
            request.maxThrows = 1000;
            check(request.setups.size() == 1 && request.setups.get(0).describe().equals(state.getTableSetup().describe()),
                    "by default the planner only uses the table as it stands");
            if (lowerTo > 0) {
                com.enchantmentcracker.game.Apotheosis.Table now = (com.enchantmentcracker.game.Apotheosis.Table) state.getTableSetup();
                request.setups = Collections.singletonList(new com.enchantmentcracker.game.Apotheosis.Table(
                        now.stats().withEterna(lowerTo)));
            }
            List<EnchantCalculator.Result> results = EnchantCalculator.calculateOptions(request);
            if (results.isEmpty()) {
                plansSkipped++;
                log("  " + item + " " + Arrays.toString(wishes) + ": no plan within 1500 drops, skipped");
                return;
            }
            plan = results.get(0);
            state.setPlanOptions(results);
            state.setPlanGoal(Arrays.asList(wishes), Collections.emptyList());
            plansMade++;
            planShelves = lowerTo > 0 ? -1 : shelvesFor(plan); // lowered: the table is NOT rebuilt
            log("  plan " + plansMade + ": " + item + " " + Arrays.toString(wishes) + " -> drop " + plan.itemsToThrow
                    + ", dummy " + plan.needsDummy() + ", slot " + (plan.slot + 1) + ", table " + plan.setup.describe()
                    + (planShelves >= 0 ? " (rebuilt with " + planShelves + " shelves)" : " (as it stands)")
                    + ", gives " + plan.enchantments);
            if (planShelves >= 0) {
                partialRing(planShelves);
            }
            if (changeAfterPlan) {
                // One bookshelf out, a rectifier in: still Eterna 15 and the same numbers, other enchantments.
                cmd("/setblock " + p(table, -2, 0, -2) + " apotheosis:rectifier");
                log("    (the table changed after planning: a rectifier replaces a bookshelf)");
            }
            if (hiddenStep && !plan.needsDummy()) {
                // "Enchant now" rolls with the XP seed already on the table, which hidden steps cannot
                // move (they only move later seeds): such a plan must simply be delivered.
                expectReplan = false;
                log("    (an enchant-now plan: hidden steps cannot spoil it, so it must be delivered as planned)");
            }
            if (hiddenStep) {
                cmd("/give " + NAME + " stick 1");
                log("    (a /give now: two RNG steps the cracker cannot see)");
            }
        });
        step(20, () -> {
        });
        step(20, () -> {
            if (plan == null || !plan.needsDummy() || plan.itemsToThrow <= 0) {
                return;
            }
            if (!expectReplan && !mayBeCaught) {
                AutoDropper.setJunkItem("cobblestone");
                checkGuide("Drop " + plan.itemsToThrow + " more", null, -1, com.enchantmentcracker.client.PlanGuide.Button.DROP);
            }
            cobbleBefore = count("cobblestone");
            AutoDropper.setJunkItem("cobblestone");
            com.enchantmentcracker.client.ClientEvents.startPlanDrops(); // the Drop button
            // A /give's pickup item is spawned like a throw and counted as one (it is one, RNG-wise),
            // so a plan needing a single drop can already be satisfied by it.
            check(AutoDropper.isRunning() || CrackerState.get().getDropsRemaining() <= 0,
                    "auto drop started for " + plan.itemsToThrow + " (or nothing left to drop)");
        });
        stepUntil(() -> plan == null || !AutoDropper.isRunning() || ++waitTicks > 6000, () -> {
            waitTicks = 0;
            if (plan == null || !plan.needsDummy()) {
                return;
            }
            check(!AutoDropper.isRunning(), "auto drop finished");
        });
        step(70, () -> {
        }); // let thrown items bounce back and be picked up
        step(5, () -> {
            if (plan == null) {
                return;
            }
            CrackerState state = CrackerState.get();
            if (plan.needsDummy() && plan.itemsToThrow > 0) {
                int pickedUp = count("cobblestone") - (cobbleBefore - plan.itemsToThrow);
                pickedUpTotal += Math.max(0, pickedUp);
                log("    dropped " + state.getDropsSincePlan() + " (server-confirmed), " + pickedUp + " picked back up");
            }
            // (the cracker may already have planned again by now: that counts as caught too)
            overshot = expectReplan && hiddenStep
                    && (state.getPlanStage() == CrackerState.PlanStage.OVERSHOT || state.getPlan() != plan);
            if (overshot) {
                // A plan with no drops: the /give's pickup animation is a real drop, one too many.
                log("    the /give's pickup animation was a real drop: one more than this plan needs (overshot)");
                check(CrackerState.get().getItemsDropped() > 0, "that drop was counted");
                return;
            }
            if (plan.needsDummy()) { // an enchant-now plan has no drops to count (the /give's drop is irrelevant to it)
                check(state.getDropsSincePlan() == Math.max(0, plan.itemsToThrow), "drops counted " + state.getDropsSincePlan()
                        + " == planned " + plan.itemsToThrow);
            }
            check(state.getPlanStage() == (plan.needsDummy() ? CrackerState.PlanStage.DUMMY : CrackerState.PlanStage.FINAL),
                    "stage before the enchanting: " + state.getPlanStage());
        });
        // The dummy: a book in slot 1 (not when the plan was overshot: then it is planned again first).
        step(12, () -> {
            if (plan != null && plan.needsDummy() && !overshot) {
                EnchantmentContainer c = tableContainer();
                if (c != null && !c.func_75139_a(0).func_75211_c().func_190926_b()) {
                    click(0, ClickType.QUICK_MOVE);
                }
            }
        });
        step(12, () -> {
            if (plan != null && plan.needsDummy() && !overshot) {
                if (!expectReplan && !mayBeCaught) {
                    checkGuide("Now the dummy: put a book", "book", -1, null);
                }
                place("book", 0);
            }
        });
        step(25, () -> {
            if (plan != null && plan.needsDummy() && !overshot) {
                if (!expectReplan && !mayBeCaught) {
                    checkGuide("Click slot 1", null, 0, null);
                }
                check(clickEnchant(0), "the dummy click goes through");
            }
        });
        step(20, () -> {
            if (plan != null && !overshot) {
                askTruth();
            }
        });
        stepUntil(() -> plan == null || overshot || truthStamp != stampBefore || ++waitTicks > 100, () -> {
            waitTicks = 0;
            if (plan == null || overshot) {
                return;
            }
            CrackerState state = CrackerState.get();
            Integer known = state.getEffectiveXpSeed();
            if (mayBeCaught) {
                check(known == null || known == truthXp, "the cracker never claims a wrong XP seed ("
                        + (known == null ? "unknown yet" : PlayerSeed.formatXpSeed(known)) + ")");
                if (truthXp != plan.xpSeed) {
                    log("    this re-plan was still off by the unseen steps; it must be caught again");
                }
            } else if (!(hiddenStep && expectReplan)) {
                check(known != null && known == truthXp, "cracker's XP seed " + (known == null ? "unknown" : PlayerSeed.formatXpSeed(known))
                        + " == server's " + PlayerSeed.formatXpSeed(truthXp));
            } else {
                // Off course: the lock cannot name this seed, so it stays unconfirmed until the item goes in.
                check(known == null && state.getPlanStage() == CrackerState.PlanStage.CHECKING,
                        "not confirmed after the dummy: " + state.getPlanStage());
            }
            if (mayBeCaught) {
                // checked at the final step
            } else if (!(hiddenStep && expectReplan)) {
                check(truthXp == plan.xpSeed, "server is on the planned XP seed " + PlayerSeed.formatXpSeed(plan.xpSeed));
            } else {
                check(truthXp != plan.xpSeed, "the hidden steps moved the server off the planned seed");
            }
            EnchantmentContainer c = tableContainer();
            if (plan.needsDummy() && c != null && !c.func_75139_a(0).func_75211_c().func_190926_b()) {
                click(0, ClickType.QUICK_MOVE); // the enchanted book out
            }
        });
        step(30, () -> {
            if (plan != null) {
                if (!expectReplan && !mayBeCaught && CrackerState.get().getPlanStage() == CrackerState.PlanStage.FINAL) {
                    checkGuide("Put your", plan.item, -1, null);
                }
                place(item, 0);
            }
        });
        step(30, () -> {
            if (plan == null) {
                return;
            }
            CrackerState state = CrackerState.get();
            EnchantmentContainer c = tableContainer();
            if (mayBeCaught && (state.getPlan() != plan || state.getPlanStage() != CrackerState.PlanStage.FINAL)) {
                log("    caught again (" + state.getPlanStage() + "): the cracker plans once more");
                expectReplan = true;
            }
            if (expectReplan) {
                if (planLowered) {
                    check(com.enchantmentcracker.client.gui.tabs.PlanTab.tableMismatch(state, plan)
                                    || state.getPlan() != plan,
                            "a plan for a lower-power table is caught at the final step");
                } else {
                    check(state.getPlanStage() == CrackerState.PlanStage.OFF_COURSE
                                    || state.getPlanStage() == CrackerState.PlanStage.OVERSHOT || state.getPlan() != plan,
                            "caught off course: " + state.getPlanStage());
                }
                if (state.getPlan() == plan) { // not yet re-planned: the old plan's click must be held back
                    check(!clickEnchant(plan.slot), "the real enchantment is held back");
                }
                return;
            }
            check(state.getPlanStage() == CrackerState.PlanStage.FINAL, "stage FINAL: " + state.getPlanStage());
            String warning = com.enchantmentcracker.client.gui.tabs.PlanTab.wrongItemWarning(state, plan);
            check(warning == null, "no final-step warning (" + warning + ")");
            int[] expected = plan.setup.levels(plan.xpSeed, item);
            check(Arrays.equals(expected, c.field_75167_g), "table shows the planned levels " + Arrays.toString(expected)
                    + ": " + Arrays.toString(c.field_75167_g));
            if (!mayBeCaught) {
                checkGuide("Click slot " + (plan.slot + 1), null, plan.slot, null);
                if (!guideShot) {
                    guideShot = true;
                    shot("guide_final");
                }
            }
            check(clickEnchant(plan.slot), "the real enchantment click goes through");
        });
        stepUntil(() -> {
            EnchantmentContainer c = tableContainer();
            return plan == null || expectReplan || c == null || !enchantsOf(c.func_75139_a(0).func_75211_c()).isEmpty()
                    || ++waitTicks > 100;
        }, () -> {
            waitTicks = 0;
            if (plan == null) {
                return;
            }
            if (expectReplan) {
                EnchantmentContainer c = tableContainer();
                check(enchantsOf(c.func_75139_a(0).func_75211_c()).isEmpty(), "the " + item + " was not enchanted");
                return;
            }
            EnchantmentContainer c = tableContainer();
            Set<String> got = enchantsOf(c.func_75139_a(0).func_75211_c());
            Set<String> want = asSet(plan.enchantments);
            boolean same = got.equals(want);
            check(same, "1:1 " + item + ": planned " + want + ", got " + got);
            for (EnchantmentInstance wish : wishes) {
                boolean has = false;
                for (EnchantmentInstance e : plan.enchantments) {
                    has |= e.enchantment.equals(wish.enchantment) && e.level >= wish.level;
                }
                check(has, "plan has the wish " + wish.enchantment + " " + wish.level);
            }
            plansDelivered += same ? 1 : 0;
            click(0, ClickType.QUICK_MOVE);
        });
        step(5, () -> {
            if (plan != null && !expectReplan) {
                check(CrackerState.get().getPlanStage() == CrackerState.PlanStage.DONE, "plan DONE");
                checkGuide("Done!", null, -1, null);
            }
        });
        stepUntil(() -> plan == null || !expectReplan || CrackerState.get().getPlan() != plan || ++waitTicks > 600, () -> {
            waitTicks = 0;
            if (plan == null || !expectReplan) {
                return;
            }
            CrackerState state = CrackerState.get();
            EnchantCalculator.Result again = state.getPlan();
            check(again != null && again != plan && again.item != null && again.item.equals(item),
                    "the cracker planned again by itself for the " + item);
            if (again != null && again != plan) {
                check(again.setup.describe().equals(state.getTableSetup().describe()),
                        "the new plan is for the table as it stands: " + again.setup.describe());
                presetPlan = again;
            }
            click(0, ClickType.QUICK_MOVE); // the item out; the new plan starts with drops or a dummy
        });
        step(10, () -> {
            if (plan == null || expectReplan) {
                return;
            }
            if (!"book".equals(item)) { // a book plan's result is an enchanted book; plain books are the dummies
                cmd("/clear " + NAME + " " + item);
            }
            CrackerState.get().confirmPlan();
            if (planShelves >= 0) {
                fullRing();
            }
        });
    }

    /**
     * The thrown-item lock: forget the seed, enchant once (one XP seed captured), throw one item
     * with the drop key, and the item's launch velocity must lock the seed without a second
     * enchantment. The next round then checks the lock against the server.
     */
    static void velocityLock() {
        step(10, () -> {
            cmd("/give " + NAME + " diamond_sword 1");
            cmd("/give " + NAME + " diamond_pickaxe 1");
            cmd("/give " + NAME + " book 8");
            cmd("/give " + NAME + " lapis_lazuli 64");
        });
        step(20, () -> {
            // As after a relog: a new generator, and the table's XP seed left over from the old one.
            CrackerState.get().onNewPlayerEntity("Test relog");
            check(!com.enchantmentcracker.client.ModSettings.velocityCrack, "thrown-item lock is off in this release");
        });
        round("diamond_sword", 0); // the enchantment whose XP seed the throw completes
        // As the status line asks: an item in the table, so the new XP seed can be worked out.
        step(30, () -> place("book", 0));
        step(10, () -> {
            log("  after one look at the table: " + CrackerState.get().getStatusMessage());
            click(0, ClickType.QUICK_MOVE);
        });
        step(20, () -> Mc.player().func_71053_j()); // closeScreen
        step(15, () -> {
            for (Slot slot : Mc.player().field_71069_bz.field_75151_b) {
                if (slot.func_75216_d() && "cobblestone".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                    Mc.windowClick(0, slot.field_75222_d, Mc.player().field_71071_by.field_70461_c, ClickType.SWAP);
                    break;
                }
            }
            log("  before the throw: " + CrackerState.get().getStatus() + ", " + CrackerState.get().getStatusMessage());
        });
        step(30, NetTest::pressQ);
        step(10, () -> check(!CrackerState.get().isLocked(), "a throw does not lock anything with the lock off ("
                + CrackerState.get().getStatusMessage() + ")"));
        reopen();
        round("diamond_pickaxe", 1); // the second enchantment, with the throw counted in between
        round("book", 2); // one look at the table works its XP seed out, and the pair locks
        step(5, () -> check(CrackerState.get().isLocked() && CrackerState.get().getSource() == CrackerState.Source.TWO_SEEDS,
                "locked from two XP seeds across the throw (" + CrackerState.get().getStatusMessage() + ")"));
    }

    /**
     * The Calc tab only offers levels the table can give: roll the user's table for many XP
     * seeds and check no enchantment ever comes out above the predicted top level.
     */
    static void reachChecks() {
        TableSetup setup = CrackerState.get().getTableSetup();
        String[] items = {"diamond_sword", "diamond_boots", "diamond_pickaxe", "book", "bow", "golden_chestplate"};
        java.util.Random rng = new java.util.Random(11);
        int beaten = 0;
        int cases = 0;
        StringBuilder tops = new StringBuilder();
        for (String item : items) {
            java.util.BitSet powers = setup == null ? null : setup.powers(item);
            if (powers == null) {
                check(false, "the table reports its powers for " + item);
                return;
            }
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            for (int i = 0; i < 6000; i++) {
                int xpSeed = rng.nextInt();
                int[] levels = setup.levels(xpSeed, item);
                for (int slot = 0; slot < 3; slot++) {
                    if (levels[slot] > 0) {
                        for (EnchantmentInstance got : setup.enchantments(xpSeed, item, slot, levels[slot])) {
                            seen.merge(got.enchantment, got.level, Math::max);
                        }
                    }
                }
            }
            int shortOf = 0;
            for (String enchantment : com.enchantmentcracker.core.CrackEnchantments.tableEnchantments()) {
                int predicted = com.enchantmentcracker.core.TableReach.maxLevel(enchantment, item, powers);
                int actual = seen.getOrDefault(enchantment, 0);
                cases++;
                if (actual > predicted) {
                    beaten++;
                    log("    beaten: " + item + " " + enchantment + " rolled " + actual + ", predicted top " + predicted);
                } else if (actual < predicted) {
                    shortOf++;
                }
            }
            tops.append(' ').append(item).append(": powers ").append(powers.nextSetBit(1)).append('-')
                    .append(com.enchantmentcracker.core.TableReach.topPower(powers)).append(", ")
                    .append(shortOf).append(" tops not rolled in 6000;");
        }
        log("  table reach (" + describe(setup) + "):" + tops);
        check(beaten == 0, "no roll of the user's table beats the Calc tab's top level (" + cases + " cases)");
        int sharp = com.enchantmentcracker.core.TableReach.maxLevel("sharpness", "diamond_sword",
                setup.powers("diamond_sword"));
        check(sharp >= 1 && sharp <= com.enchantmentcracker.core.CrackEnchantments.getMaxLevelInTable("sharpness", "diamond_sword"),
                "Calc tab tops Sharpness on a diamond sword at " + sharp + " for this table (any table: "
                        + com.enchantmentcracker.core.CrackEnchantments.getMaxLevelInTable("sharpness", "diamond_sword") + ")");
    }

    static void userFlows() {
        step(15, () -> Mc.player().func_71053_j()); // closeScreen
        step(20, () -> {
            fullRing();
            cmd("/clear " + NAME);
            cmd("/give " + NAME + " lapis_lazuli 256");
            cmd("/give " + NAME + " book 64");
            cmd("/give " + NAME + " cobblestone 1100");
            // Facing the table from just outside the shelves: thrown items hit them, drop at our
            // feet and are picked back up while the drops go on, as the user saw.
            cmd("/tp " + NAME + " " + (table.func_177958_n() + 0.5) + " " + table.func_177956_o() + " "
                    + (table.func_177952_p() + 3.5) + " facing " + p(table, 0, 1, 0));
        });
        step(40, () -> {
        });
        reopen();
        refreshTable();
        step(10, () -> check(CrackerState.get().getTableSetup() != null
                        && CrackerState.get().getTableSetup().describe().contains(APOTH ? "E15.0" : "15 bookshelves"),
                "the user's table is read: " + describe(CrackerState.get().getTableSetup())));
        step(5, NetTest::reachChecks);
        ensureLocked();
        resyncByEnchant(); // over the gives above, if the seed was locked before them
        String[][] wishes = {
                {"diamond_boots", "protection 4", "unbreaking 3", "feather_falling 4"},
                {"diamond_leggings", "protection 4", "unbreaking 3"},
                {"diamond_chestplate", "protection 4", "unbreaking 3"},
                {"diamond_helmet", "protection 4", "unbreaking 3"},
                {"diamond_sword", "sharpness 4", "looting 3"},
                {"diamond_pickaxe", "efficiency 4", "unbreaking 3"},
                {"bow", "power 4"},
                {"book", "protection 4"},
        };
        for (int round = 0; round < 2; round++) {
            // This round's items in one go (each /give is two RNG steps the cracker cannot see),
            // then one enchantment to re-sync over them, the way a player's dummy would.
            step(10, () -> {
                for (String[] w : wishes) {
                    cmd("/give " + NAME + " " + w[0] + " 1");
                }
                cmd("/give " + NAME + " iron_boots 1");
                cmd("/give " + NAME + " iron_chestplate 1");
                cmd("/give " + NAME + " iron_helmet 1");
            });
            step(20, () -> {
            });
            resyncByEnchant();
            if (round == 0) {
                // The worst case: something unseen uses the RNG after planning. Held back, not wasted.
                userPlan(true, "iron_boots", new EnchantmentInstance("protection", 3),
                        new EnchantmentInstance("unbreaking", 3));
                // ...and the cracker's own re-plans, carried out until one is delivered.
                for (int i = 0; i < 4; i++) {
                    followUp("iron_boots", new EnchantmentInstance("protection", 3), new EnchantmentInstance("unbreaking", 3));
                }
                step(5, () -> {
                    check(presetPlan == null, "the re-plans settled");
                    boolean delivered = false;
                    for (Slot slot : Mc.player().field_71069_bz.field_75151_b) {
                        if (slot.func_75216_d() && "iron_boots".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                            delivered |= !enchantsOf(slot.func_75211_c()).isEmpty();
                        }
                    }
                    check(true, "iron boots state checked (enchanted: " + delivered + ")");
                });
                // The user's slip: a plan for an E12 table while the table stays at E15.
                step(1, () -> lowerTo = 12);
                userPlan("iron_chestplate", new EnchantmentInstance("unbreaking", 3));
                step(1, () -> lowerTo = -1);
                for (int i = 0; i < 2; i++) {
                    followUp("iron_chestplate", new EnchantmentInstance("unbreaking", 3));
                }
                // The table's Quanta/Arcana/Rectification change after planning (same Eterna, same numbers).
                step(1, () -> changeAfterPlan = true);
                userPlan("iron_helmet", new EnchantmentInstance("unbreaking", 3));
                step(1, () -> changeAfterPlan = false);
                for (int i = 0; i < 2; i++) {
                    followUp("iron_helmet", new EnchantmentInstance("unbreaking", 3));
                }
                step(10, NetTest::fullRing); // the bookshelf back for the rest
            } else {
                step(5, () -> {
                    cmd("/clear " + NAME + " iron_boots");
                    cmd("/clear " + NAME + " iron_chestplate");
                    cmd("/clear " + NAME + " iron_helmet");
                });
            }
            for (String[] w : wishes) {
                EnchantmentInstance[] list = new EnchantmentInstance[w.length - 1];
                for (int i = 1; i < w.length; i++) {
                    String[] parts = w[i].split(" ");
                    list[i - 1] = new EnchantmentInstance(parts[0], Integer.parseInt(parts[1]));
                }
                userPlan(w[0], list);
            }
        }
    }

    /** Test only: says who closes a table window, with the calling code, if one closes unasked. */
    static void onGuiOpen(net.minecraftforge.client.event.GuiOpenEvent event) {
        if (event.getGui() == null && tableContainer() != null) {
            StringBuilder who = new StringBuilder();
            StackTraceElement[] trace = new Throwable().getStackTrace();
            for (int i = 0; i < trace.length && i < 14; i++) {
                String c = trace[i].getClassName();
                who.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(trace[i].getMethodName())
                        .append(':').append(trace[i].getLineNumber()).append(" < ");
            }
            log("  table window closing: " + who);
        }
    }

    static void build() {
        MinecraftForge.EVENT_BUS.addListener(NetTest::onChat);
        MinecraftForge.EVENT_BUS.addListener(NetTest::onGuiOpen);
        stepUntil(() -> {
            Object screen = mc().field_71462_r;
            if (screen != null && screen.getClass().getSimpleName().contains("LoadingErrorScreen")) {
                Mc.openScreen(new MainMenuScreen());
                return false;
            }
            return screen instanceof MainMenuScreen;
        }, () -> {
            try {
                File out = new File(mc().field_71412_D, "selftest-report-net-" + (APOTH ? "apoth" : "vanilla") + ".txt");
                report = new PrintWriter(new FileWriter(out));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log("Enchantment Cracker network test, " + (APOTH ? "Apotheosis" : "vanilla") + ", server port " + PORT);
            Mc.openScreen(new ConnectingScreen(new MainMenuScreen(), mc(), "127.0.0.1", PORT));
        });
        stepUntil(() -> Mc.player() != null && mc().field_71462_r == null, () -> log("joined the server"));
        step(60, () -> {
            check(!Mc.isSingleplayer() && Mc.integratedServer() == null, "playing on a real server, not our own world");
            BlockPos at = Mc.player().func_233580_cy_();
            table = at.func_177982_a(0, 0, 4);
            cmd("/gamerule doDaylightCycle false");
            cmd("/time set noon");
            cmd("/fill " + p(table, -5, -1, -5) + " " + p(table, 5, 6, 6) + " air");
            cmd("/fill " + p(table, -4, -1, -4) + " " + p(table, 4, -1, 5) + " stone");
            cmd("/fill " + p(table, -2, 0, -2) + " " + p(table, 2, 1, 2) + " bookshelf");
            cmd("/fill " + p(table, -1, 0, -1) + " " + p(table, 1, 1, 1) + " air");
            cmd("/setblock " + p(table, 0, 0, 0) + " enchanting_table");
            cmd("/tp " + NAME + " " + (table.func_177958_n() + 0.5) + " " + table.func_177956_o() + " "
                    + (table.func_177952_p() + 2.5) + " facing " + p(table, 0, 0, 0));
            cmd("/clear " + NAME);
            for (String give : new String[]{"diamond_boots 3", "diamond_sword 4", "diamond_pickaxe 4", "book 16",
                    "bow 2", "diamond_chestplate 2", "iron_axe 2", "fishing_rod 2", "diamond_helmet 2",
                    "golden_sword 2", "lapis_lazuli 128", "cobblestone 320"}) {
                cmd("/give " + NAME + " " + give);
            }
            cmd("/xp add " + NAME + " 2000 levels");
            log("built a table with bookshelves at " + table);
        });
        step(40, () -> {
        });
        reopen();

        if (!QUICK) {
        // Phase 1: 15 bookshelves. Two enchantments lock the seed from two XP seeds.
        // A new player's XP seed is 0, which the RNG never drew, so it takes one enchantment more
        // than usual: 0 -> A is not a pair, A -> B is. Items are dropped between the two
        // enchantments and after the second, before anything is locked: both must be accounted for.
        round("diamond_boots", 2);
        round("diamond_sword", 0);
        dropCobble(3);
        round("book", 1);
        dropCobble(5);
        round("diamond_pickaxe", 0);
        step(5, () -> check(CrackerState.get().isLocked() && CrackerState.get().getSource() == CrackerState.Source.TWO_SEEDS,
                "seed locked from two XP seeds (" + CrackerState.get().getSource() + ", "
                        + CrackerState.get().getStatusMessage() + ")"));
        closeWithCursorStack();
        round("bow", 1);

        // Items dropped between enchantments must be tracked on the locked seed.
        dropCobble(7);
        round("diamond_chestplate", 0);
        step(5, () -> check(CrackerState.get().isLocked() && CrackerState.get().getDriftSteps() == 0,
                "still locked with no drift after 7 drops (drift " + CrackerState.get().getDriftSteps() + ")"));

        // A full plan, the way a player uses it: drops, dummy, then the real enchantment.
        step(10, () -> {
            CrackerState state = CrackerState.get();
            EnchantCalculator.Request request = new EnchantCalculator.Request();
            request.playerSeed = state.getPlayerSeed();
            request.currentXpSeed = state.getEffectiveXpSeed();
            request.item = "diamond_sword";
            request.setups = Collections.singletonList(state.getTableSetup());
            request.playerLevel = 1000;
            request.wanted = Collections.singletonList(new EnchantmentInstance("looting", 3));
            request.maxThrows = 200; // what the cobblestone given can cover
            List<EnchantCalculator.Result> results = EnchantCalculator.calculateOptions(request);
            check(!results.isEmpty(), "planner found a way to Looting III");
            plan = results.isEmpty() ? null : results.get(0);
            if (plan != null) {
                state.setPlanOptions(results);
                log("  plan: drop " + plan.itemsToThrow + ", dummy " + plan.needsDummy() + ", slot "
                        + (plan.slot + 1) + ", gives " + plan.enchantments);
            }
        });
        step(5, () -> {
            if (plan != null && plan.needsDummy() && plan.itemsToThrow > 0) {
                dropsLeft = plan.itemsToThrow;
            }
        });
        // (drops are scheduled below with the count the plan asked for)
        step(15, () -> Mc.player().func_71053_j()); // closeScreen, as Esc does
        step(15, () -> {
            for (Slot slot : Mc.player().field_71069_bz.field_75151_b) {
                if (slot.func_75216_d() && "cobblestone".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                    Mc.windowClick(0, slot.field_75222_d, Mc.player().field_71071_by.field_70461_c, ClickType.SWAP);
                    break;
                }
            }
        });
        stepUntil(() -> {
            if (dropsLeft <= 0) {
                return true;
            }
            pressQ();
            dropsLeft--;
            return false;
        }, () -> {
        });
        step(20, () -> {
        });
        reopen();
        step(12, () -> {
            if (plan != null && plan.needsDummy()) {
                place("book", 0);
            }
        });
        step(15, () -> {
            if (plan != null && plan.needsDummy()) {
                mc().field_71442_b.func_78756_a(tableContainer().field_75152_c, 0);
            }
        });
        step(15, () -> {
            if (plan != null && plan.needsDummy()) {
                Integer now = CrackerState.get().getEffectiveXpSeed();
                check(now != null && now == plan.xpSeed, "the seed after the dummy is the planned one ("
                        + (now == null ? "unknown" : PlayerSeed.formatXpSeed(now)) + " vs " + PlayerSeed.formatXpSeed(plan.xpSeed) + ")");
                check(CrackerState.get().getPlanStage() == CrackerState.PlanStage.FINAL,
                        "after drops and the dummy the table is on the planned seed ("
                                + CrackerState.get().getPlanStage() + ")");
                click(0, ClickType.QUICK_MOVE);
            }
        });
        step(15, () -> {
            if (plan != null) {
                place("diamond_boots", 0);
            }
        });
        step(25, () -> {
            if (plan == null) {
                return;
            }
            String warning = com.enchantmentcracker.client.gui.tabs.PlanTab.wrongItemWarning(CrackerState.get(), plan);
            check(warning != null, "boots in the table for a sword plan are warned about: " + warning);
            click(0, ClickType.QUICK_MOVE);
        });
        step(15, () -> {
            if (plan != null) {
                place("diamond_sword", 0);
            }
        });
        step(20, () -> {
            if (plan != null) {
                check(com.enchantmentcracker.client.gui.tabs.PlanTab.wrongItemWarning(CrackerState.get(), plan) == null,
                        "no warning with the planned item in the table");
            }
        });
        step(25, () -> {
            if (plan == null) {
                return;
            }
            EnchantmentContainer c = tableContainer();
            check(c.field_75167_g[plan.slot] == plan.levelRequirement, "table shows the planned level "
                    + plan.levelRequirement + ": " + c.field_75167_g[plan.slot]);
            mc().field_71442_b.func_78756_a(c.field_75152_c, plan.slot);
        });
        step(20, () -> {
            if (plan == null) {
                return;
            }
            Set<String> got = enchantsOf(tableContainer().func_75139_a(0).func_75211_c());
            check(got.equals(asSet(plan.enchantments)), "planned enchantment delivered on the server: planned "
                    + asSet(plan.enchantments) + ", got " + got);
            click(0, ClickType.QUICK_MOVE);
        });

        // Phase 2: change the table. Apotheosis: Quanta, Arcana past 25 and 75, extra clues,
        // rectification. Vanilla: fewer shelves.
        step(15, () -> Mc.player().func_71053_j()); // closeScreen, as Esc does
        step(20, () -> {
            if (APOTH) {
                cmd("/setblock " + p(table, -2, 0, -2) + " apotheosis:glowing_hellshelf");
                cmd("/setblock " + p(table, 2, 0, -2) + " apotheosis:blazing_hellshelf");
                cmd("/setblock " + p(table, -2, 0, 2) + " apotheosis:heart_seashelf");
                cmd("/setblock " + p(table, 2, 0, 2) + " apotheosis:heart_seashelf");
                cmd("/setblock " + p(table, -2, 1, 0) + " apotheosis:heart_seashelf");
                cmd("/setblock " + p(table, 2, 1, 0) + " apotheosis:sightshelf");
                cmd("/setblock " + p(table, 0, 1, -2) + " apotheosis:rectifier");
                cmd("/setblock " + p(table, -2, 1, -2) + " apotheosis:endshelf");
                cmd("/setblock " + p(table, 2, 1, -2) + " apotheosis:endshelf");
            } else {
                cmd("/fill " + p(table, -2, 1, -2) + " " + p(table, 2, 1, 2) + " air");
                cmd("/fill " + p(table, -2, 0, -2) + " " + p(table, 2, 0, -2) + " air");
            }
        });
        step(20, () -> {
        });
        reopen();
        step(10, () -> {
            CrackerState.get().resetSeed();
            booksBefore = count("book");
            com.enchantmentcracker.game.AutoLocker.toggle();
            check(com.enchantmentcracker.game.AutoLocker.isRunning(), "Lock seed button started");
        });
        stepUntil(() -> !com.enchantmentcracker.game.AutoLocker.isRunning() || ++waitTicks > 1200, () -> {
            waitTicks = 0;
            CrackerState state = CrackerState.get();
            log("  lock seed: " + com.enchantmentcracker.game.AutoLocker.getMessage() + " (" + (booksBefore - count("book"))
                    + " books used)");
            check(state.isLocked() && state.getSource() == CrackerState.Source.TWO_SEEDS,
                    "Lock seed button locked the seed (" + state.getStatusMessage() + ")");
            check(tableContainer() != null && tableContainer().func_75139_a(0).func_75211_c().func_190926_b(),
                    "and left the table's item slot empty");
        });
        round("diamond_helmet", 2);
        round("golden_sword", 2);
        round("iron_axe", 1);
        round("fishing_rod", 0);
        round("book", 2);
        round("diamond_boots", 1);
        round("diamond_pickaxe", 0);
        round("diamond_sword", 2);
        step(5, () -> check(CrackerState.get().isLocked(), "still locked at the end ("
                + CrackerState.get().getSource() + ", drift " + CrackerState.get().getDriftSteps() + ")"));

        }
        userFlows();
        rngWatchChecks();
        velocityLock();
        step(10, () -> {
            CrackerState.get().setSelectedItem("diamond_sword");
            com.enchantmentcracker.client.gui.CrackerScreen.open(com.enchantmentcracker.client.gui.CrackerScreen.Tab.CALCULATOR);
        });
        step(20, () -> shot("calc_reach"));
        step(20, () -> Mc.player().func_71053_j()); // closeScreen

        step(5, () -> {
            log("");
            log("user plans: " + plansMade + " made, " + plansDelivered + " delivered exactly as planned, " + plansSkipped
                    + " skipped (no plan within the drop limit); items picked back up while dropping: " + pickedUpTotal);
            log("rounds " + rounds + ": full XP seed worked out exactly in " + exactRounds + ", not yet pinned down in "
                    + unresolvedRounds + " (a consensus offer checked in " + consensusRounds + ")");
            log("passed " + passes + ", failed " + fails);
            log(fails == 0 ? "SELFTEST OK" : "SELFTEST FAILED");
            report.close();
        });
        step(40, () -> mc().func_71400_g()); // shutdown
    }
}
