package com.enchantmentcracker.selftest;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.TableSetup;
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

    /** A real press of the drop key, so the mod counts it the way it counts a player's. */
    static void pressQ() {
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
                check(resolved != null && resolveTicks <= 2, "locked seed knew this XP seed at once (" + resolveTicks + " ticks)");
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

    static void build() {
        MinecraftForge.EVENT_BUS.addListener(NetTest::onChat);
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
            request.maxThrows = 400;
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
                place("diamond_sword", 0);
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

        step(5, () -> {
            log("");
            log("rounds " + rounds + ": full XP seed worked out exactly in " + exactRounds + ", not yet pinned down in "
                    + unresolvedRounds + " (a consensus offer checked in " + consensusRounds + ")");
            log("passed " + passes + ", failed " + fails);
            log(fails == 0 ? "SELFTEST OK" : "SELFTEST FAILED");
            report.close();
        });
        step(40, () -> mc().func_71400_g()); // shutdown
    }
}
