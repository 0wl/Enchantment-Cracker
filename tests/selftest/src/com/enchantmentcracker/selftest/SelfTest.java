package com.enchantmentcracker.selftest;

import com.enchantmentcracker.client.ModSettings;
import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.tabs.SearchTab;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantArea;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.Models;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.TableSetup;
import com.enchantmentcracker.game.AreaTracker;
import com.enchantmentcracker.game.AutoDropper;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.ServerRng;
import com.enchantmentcracker.game.TableWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.gui.screen.inventory.InventoryScreen;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.settings.DimensionGeneratorSettings;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Drives a real game session end to end and writes a report. Test harness only, never shipped.
 */
@Mod("enchcrackertest")
public final class SelfTest {

    static final String NAME = "SelfTest";
    static final boolean APOTH = "apoth".equals(System.getProperty("enchcracker.selftest.mode"));

    static final List<Runnable> steps = new ArrayList<>();
    static final List<Integer> waits = new ArrayList<>();
    static final List<BooleanSupplier> untils = new ArrayList<>();
    static int index;
    static int waited;
    static PrintWriter report;
    static int passes;
    static int fails;
    static String pendingShot;
    static BlockPos table;
    static long seedBeforeDrops;
    static int dropsBefore;
    static int cobbleBefore;
    static int dropCount;
    static EnchantCalculator.Result plan;
    static int totalTicks;
    static int manualDropsLeft;

    public SelfTest() {
        MinecraftForge.EVENT_BUS.addListener(SelfTest::onTick);
        MinecraftForge.EVENT_BUS.addListener(SelfTest::onRender);
        build();
    }

    static Minecraft mc() {
        return Mc.mc();
    }

    static void log(String line) {
        System.out.println("[SELFTEST] " + line);
        if (report != null) {
            report.println(line);
            report.flush();
        }
    }

    static void check(boolean ok, String what) {
        if (ok) {
            passes++;
            log("PASS " + what);
        } else {
            fails++;
            log("FAIL " + what);
        }
    }

    static void step(int waitAfter, Runnable action) {
        steps.add(action);
        waits.add(waitAfter);
        untils.add(null);
    }

    static void stepUntil(BooleanSupplier until, Runnable action) {
        steps.add(action);
        waits.add(0);
        untils.add(until);
    }

    static void shot(String name) {
        pendingShot = (APOTH ? "apoth_" : "vanilla_") + name;
    }

    static IntegratedServer server() {
        return mc().func_71401_C();
    }

    static void command(String cmd) {
        IntegratedServer server = server();
        server.execute(() -> server.func_195571_aL().func_197059_a(server.func_195573_aM(), cmd));
    }

    static ServerPlayerEntity serverPlayer() {
        return server().func_184103_al().func_177451_a(Mc.player().func_110124_au());
    }

    static String p(BlockPos pos, int dx, int dy, int dz) {
        return (pos.func_177958_n() + dx) + " " + (pos.func_177956_o() + dy) + " " + (pos.func_177952_p() + dz);
    }

    static int count(String item) {
        int total = 0;
        for (Slot slot : Mc.openContainer().field_75151_b) {
            if (slot.field_75224_c == Mc.player().field_71071_by && slot.func_75216_d()
                    && item.equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                total += slot.func_75211_c().func_190916_E();
            }
        }
        return total;
    }

    static Slot find(String item) {
        for (Slot slot : Mc.openContainer().field_75151_b) {
            if (slot.field_75224_c == Mc.player().field_71071_by && slot.func_75216_d()
                    && item.equals(Mc.idOf(slot.func_75211_c().func_77973_b()))
                    && !EnchantmentHelper.func_82781_a(slot.func_75211_c()).isEmpty() == false) {
                return slot;
            }
        }
        return null;
    }

    static void click(int slot, ClickType type) {
        Container c = Mc.openContainer();
        Mc.windowClick(c.field_75152_c, slot, 0, type);
    }

    /** Moves one stack of an unenchanted item into a table slot. */
    static void place(String item, int tableSlot) {
        Slot from = find(item);
        if (from == null) {
            log("  (no " + item + " left to place)");
            return;
        }
        click(from.field_75222_d, ClickType.PICKUP);
        click(tableSlot, ClickType.PICKUP);
        if (!Mc.cursorStack().func_190926_b()) {
            click(from.field_75222_d, ClickType.PICKUP); // put back what did not fit
        }
    }

    static void openTable() {
        server().execute(() -> {
            ServerPlayerEntity sp = serverPlayer();
            ServerWorld world = sp.func_71121_q();
            sp.func_213829_a(world.func_180495_p(table).func_215699_b(world, table));
        });
    }

    static EnchantmentContainer tableContainer() {
        return TableWatcher.openContainer();
    }

    static Set<String> enchantsOf(ItemStack stack) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<Enchantment, Integer> e : EnchantmentHelper.func_82781_a(stack).entrySet()) {
            out.add(Mc.idOf(e.getKey().getRegistryName()) + ":" + e.getValue());
        }
        return out;
    }

    static Set<String> asSet(List<EnchantmentInstance> list) {
        Set<String> out = new HashSet<>();
        for (EnchantmentInstance e : list) {
            out.add(e.enchantment + ":" + e.level);
        }
        return out;
    }

    // ------------------------------------------------------------------ the script

    static EnchantCalculator.SlotPreview[] predicted;
    static String predictedItem;
    static int enchantSlot;

    static void predictAndEnchant(String item, int slot) {
        step(12, () -> {
            EnchantmentContainer c = tableContainer();
            if (c == null) {
                log("  table not open");
                return;
            }
            if (!c.func_75139_a(0).func_75211_c().func_190926_b()) {
                click(0, ClickType.QUICK_MOVE); // clear the item slot
            }
        });
        step(12, () -> place(item, 0));
        step(12, () -> {
            CrackerState state = CrackerState.get();
            TableSetup setup = state.getTableSetup();
            EnchantmentContainer c = tableContainer();
            int xp = c.func_217005_f();
            predicted = EnchantCalculator.preview(xp, setup, item);
            predictedItem = item;
            enchantSlot = slot;
            int[] levels = c.field_75167_g;
            check(state.getTableProblem() == null, item + ": table reads cleanly (problem: " + state.getTableProblem() + ")");
            check(predicted[slot].levelRequirement == levels[slot], item + " slot " + (slot + 1) + " level "
                    + predicted[slot].levelRequirement + " vs table " + levels[slot] + " (" + setup.describe() + ")");
            mc().field_71442_b.func_78756_a(c.field_75152_c, slot); // sendEnchantPacket
        });
        step(15, () -> {
            EnchantmentContainer c = tableContainer();
            ItemStack out = c.func_75139_a(0).func_75211_c();
            Set<String> got = enchantsOf(out);
            Set<String> want = asSet(predicted[enchantSlot].enchantments);
            check(!got.isEmpty() && got.equals(want), predictedItem + " slot " + (enchantSlot + 1)
                    + " predicted " + want + ", got " + got);
            click(0, ClickType.QUICK_MOVE);
        });
    }

    static void build() {
        step(0, () -> {
        });
        stepUntil(() -> {
            Object screen = mc().field_71462_r;
            // Forge's "warnings while loading" screen sits in front of the menu; step past it.
            if (screen != null && screen.getClass().getSimpleName().contains("LoadingErrorScreen")) {
                Mc.openScreen(new MainMenuScreen());
                return false;
            }
            return screen instanceof MainMenuScreen;
        }, () -> {
            try {
                File out = new File(mc().field_71412_D, "selftest-report-" + (APOTH ? "apoth" : "vanilla") + ".txt");
                report = new PrintWriter(new FileWriter(out));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log("Enchantment Cracker self test, mode " + (APOTH ? "Apotheosis" : "vanilla"));
            DynamicRegistries.Impl registries = DynamicRegistries.func_239770_b_();
            WorldSettings settings = new WorldSettings("selftest", GameType.SURVIVAL, false, Difficulty.PEACEFUL,
                    true, new GameRules(), net.minecraft.util.datafix.codec.DatapackCodec.field_234880_a_);
            DimensionGeneratorSettings gen = DimensionGeneratorSettings.func_242751_a(
                    registries.func_243612_b(Registry.field_239698_ad_),
                    registries.func_243612_b(Registry.field_239720_u_),
                    registries.func_243612_b(Registry.field_243549_ar));
            mc().func_238192_a_("selftest", settings, registries, gen);
        });
        stepUntil(() -> Mc.player() != null && mc().field_71462_r == null, () -> log("joined the world"));
        step(80, () -> {
        });
        step(40, () -> {
            BlockPos at = Mc.player().func_233580_cy_();
            table = at.func_177982_a(0, 0, 4);
            command("/gamerule doDaylightCycle false");
            command("/time set noon");
            command("/fill " + p(table, -5, -1, -5) + " " + p(table, 5, 9, 6) + " air");
            command("/fill " + p(table, -4, -1, -4) + " " + p(table, 4, -1, 5) + " stone");
            command("/fill " + p(table, -2, 0, -2) + " " + p(table, 2, 1, 2) + " bookshelf");
            command("/fill " + p(table, -1, 0, -1) + " " + p(table, 1, 1, 1) + " air");
            command("/setblock " + p(table, 0, 0, 0) + " enchanting_table");
            command("/tp " + NAME + " " + (table.func_177958_n() + 0.5) + " " + table.func_177956_o() + " "
                    + (table.func_177952_p() + 3.5) + " facing " + p(table, 0, 0, 0));
            command("/give " + NAME + " diamond_sword 4");
            command("/give " + NAME + " diamond_pickaxe 2");
            command("/give " + NAME + " book 8");
            command("/give " + NAME + " lapis_lazuli 128");
            command("/give " + NAME + " cobblestone 640");
            command("/xp add " + NAME + " 300 levels");
            log("built a table with shelves at " + table);
        });
        step(40, () -> {
            check(Models.get() instanceof com.enchantmentcracker.game.RegistryModel, "live registry model installed");
            check(CrackerState.get().getSource() == CrackerState.Source.DIRECT, "seed read directly from the world: "
                    + CrackerState.get().getSource());
            if (!APOTH) {
                check(AreaTracker.hasTable() && AreaTracker.currentShelves() == 15,
                        "area detection sees the table with 15 shelves: " + AreaTracker.currentShelves());
            }
        });

        // Every tab, rendered.
        for (CrackerScreen.Tab tab : CrackerScreen.Tab.values()) {
            step(10, () -> CrackerScreen.open(tab));
            step(5, () -> shot("tab_" + tab.name().toLowerCase()));
        }
        step(10, () -> {
            SearchTab.preselect("looting", "diamond_sword", 3);
            CrackerScreen.open(CrackerScreen.Tab.SEARCH);
        });
        step(10, () -> {
            try {
                CrackerScreen screen = (CrackerScreen) mc().field_71462_r;
                Object tab = screen.tab(CrackerScreen.Tab.SEARCH);
                java.lang.reflect.Method search = tab.getClass().getDeclaredMethod("search");
                search.setAccessible(true);
                search.invoke(tab);
            } catch (Exception e) {
                log("search invoke failed " + e);
            }
        });
        stepUntil(() -> !com.enchantmentcracker.client.Planner.isRunning(), () -> {
        });
        step(10, () -> shot("search_looting"));
        step(10, () -> {
            try {
                Class<?> anvil = Class.forName("com.enchantmentcracker.client.gui.tabs.AnvilTab");
                java.lang.reflect.Field wanted = anvil.getDeclaredField("wanted");
                wanted.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Integer> map = (Map<String, Integer>) wanted.get(null);
                map.clear();
                map.put("sharpness", 5);
                map.put("looting", 3);
                map.put("unbreaking", 3);
                map.put("mending", 1);
                java.lang.reflect.Field item = anvil.getDeclaredField("item");
                item.setAccessible(true);
                item.set(null, "diamond_sword");
            } catch (Exception e) {
                log("anvil setup failed " + e);
            }
            CrackerScreen.open(CrackerScreen.Tab.ANVIL);
        });
        step(10, () -> shot("anvil_sword"));
        step(10, () -> Mc.openScreen(null));

        // Picking the junk item in the inventory, with the glint.
        step(10, () -> Mc.openScreen(new InventoryScreen(Mc.player())));
        step(3, () -> {
            Slot cobble = null;
            for (Slot slot : Mc.player().field_71069_bz.field_75151_b) {
                if (slot.func_75216_d() && "cobblestone".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                    cobble = slot;
                    break;
                }
            }
            check(cobble != null && AutoDropper.pick(Mc.player().field_71069_bz, cobble), "picked cobblestone as junk");
            ModSettings.junkItem = AutoDropper.getJunkItem();
        });
        step(20, () -> {
            check(AutoDropper.isHighlighting(), "slot is highlighted after picking");
            shot("inventory_glint");
        });
        step(110, () -> {
        });
        step(5, () -> check(!AutoDropper.isHighlighting(), "highlight switched off after 5 seconds"));

        // Auto drop: exactly N items, 4 RNG steps each.
        step(5, () -> {
            seedBeforeDrops = ServerRng.readPlayerSeed();
            dropsBefore = CrackerState.get().getItemsDropped();
            cobbleBefore = count("cobblestone");
            AutoDropper.start(25);
        });
        stepUntil(() -> !AutoDropper.isRunning(), () -> {
        });
        step(30, () -> {
            check(cobbleBefore - count("cobblestone") == 25, "auto drop threw exactly 25 (" + (cobbleBefore - count("cobblestone")) + ")");
            check(CrackerState.get().getItemsDropped() - dropsBefore == 25, "drop counter saw 25 ("
                    + (CrackerState.get().getItemsDropped() - dropsBefore) + ")");
            long now = ServerRng.readPlayerSeed();
            long expected = PlayerSeed.advance(seedBeforeDrops, 25 * PlayerSeed.STEPS_PER_ITEM_DROP);
            int steps = PlayerSeed.stepsUntilXpSeed(seedBeforeDrops, PlayerSeed.xpSeedOf(now), 1000);
            check(now == expected, "25 drops moved the player RNG exactly 100 steps (moved " + steps + ")");
        });
        step(10, () -> Mc.openScreen(null));

        // The table: overlay, buttons, predictions checked against real enchanting.
        step(20, TableTestSteps::open);
        step(20, () -> place("diamond_sword", 0));
        step(10, () -> {
            Slot lapis = find("lapis_lazuli");
            click(lapis.field_75222_d, ClickType.PICKUP);
            click(1, ClickType.PICKUP);
        });
        step(20, () -> shot("table_overlay"));
        step(5, () -> click(0, ClickType.QUICK_MOVE));
        predictAndEnchant("diamond_sword", 2);
        predictAndEnchant("diamond_sword", 0);
        predictAndEnchant("book", 2);
        predictAndEnchant("book", 1);
        predictAndEnchant("diamond_pickaxe", 1);
        predictAndEnchant("diamond_pickaxe", 2);

        // End to end: plan, manual Q drops (detected), dummy (detected), enchant (detected).
        step(10, () -> {
            CrackerState state = CrackerState.get();
            EnchantCalculator.Request request = new EnchantCalculator.Request();
            request.playerSeed = state.getPlayerSeed();
            request.currentXpSeed = state.getEffectiveXpSeed();
            request.item = "diamond_sword";
            request.setups = Collections.singletonList(state.getTableSetup());
            request.playerLevel = 200;
            request.wanted = Collections.singletonList(new EnchantmentInstance("looting", 3));
            request.maxThrows = 500;
            List<EnchantCalculator.Result> results = EnchantCalculator.calculateOptions(request);
            check(!results.isEmpty(), "planner found a way to Looting III with this table");
            if (!results.isEmpty()) {
                plan = results.get(0);
                state.setPlanOptions(results);
                log("  plan: drop " + plan.itemsToThrow + ", dummy " + plan.needsDummy() + ", slot " + (plan.slot + 1)
                        + ", gives " + plan.enchantments);
                seedBeforeDrops = ServerRng.readPlayerSeed();
                dropCount = plan.needsDummy() ? Math.max(0, plan.itemsToThrow) : 0;
                manualDropsLeft = dropCount;
                check(state.getPlanStage() == (dropCount > 0 ? CrackerState.PlanStage.DROPPING
                        : plan.needsDummy() ? CrackerState.PlanStage.DUMMY : CrackerState.PlanStage.FINAL),
                        "plan starts at the right stage: " + state.getPlanStage());
            }
        });
        // Close the table and put cobblestone in hotbar slot 0, the slot Q drops from.
        step(15, () -> Mc.openScreen(null));
        step(15, () -> {
            Slot cobble = null;
            for (Slot slot : Mc.player().field_71069_bz.field_75151_b) {
                if (slot.func_75216_d() && "cobblestone".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                    cobble = slot;
                    break;
                }
            }
            Mc.windowClick(0, cobble.field_75222_d, Mc.player().field_71071_by.field_70461_c, ClickType.SWAP);
        });
        // Press Q, one item per tick, like a player tapping it.
        stepUntil(() -> {
            if (manualDropsLeft <= 0) {
                return true;
            }
            Mc.player().func_225609_n_(false); // ClientPlayerEntity.drop(false): what the Q key does
            manualDropsLeft--;
            return false;
        }, () -> {
        });
        step(30, () -> {
            if (plan == null) {
                return;
            }
            CrackerState state = CrackerState.get();
            check(state.getDropsSincePlan() == dropCount, "manual Q drops detected: " + state.getDropsSincePlan()
                    + " of " + dropCount);
            long now = ServerRng.readPlayerSeed();
            check(now == PlayerSeed.advance(seedBeforeDrops, dropCount * 4), "plan drops landed the RNG where planned");
            if (plan.needsDummy()) {
                check(state.getPlanStage() == CrackerState.PlanStage.DUMMY, "stage after drops is DUMMY: "
                        + state.getPlanStage());
            }
        });
        step(20, TableTestSteps::open);
        step(10, () -> {
            Slot lapis = find("lapis_lazuli");
            click(lapis.field_75222_d, ClickType.PICKUP);
            click(1, ClickType.PICKUP);
        });
        step(15, () -> {
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
                check(CrackerState.get().getEnchantsSincePlan() == 1, "dummy enchant detected");
                check(CrackerState.get().getPlanStage() == CrackerState.PlanStage.FINAL,
                        "after the dummy the table is on the planned seed (stage " + CrackerState.get().getPlanStage() + ")");
                click(0, ClickType.QUICK_MOVE);
            }
        });
        step(15, () -> {
            if (plan != null) {
                place("diamond_sword", 0);
            }
        });
        step(15, () -> {
            if (plan == null) {
                return;
            }
            EnchantmentContainer c = tableContainer();
            check(c.field_75167_g[plan.slot] == plan.levelRequirement, "table shows the planned level "
                    + plan.levelRequirement + " in slot " + (plan.slot + 1) + ": " + c.field_75167_g[plan.slot]);
            mc().field_71442_b.func_78756_a(c.field_75152_c, plan.slot);
        });
        step(15, () -> {
            if (plan == null) {
                return;
            }
            Set<String> got = enchantsOf(tableContainer().func_75139_a(0).func_75211_c());
            check(got.equals(asSet(plan.enchantments)), "planned enchantment delivered: planned "
                    + asSet(plan.enchantments) + ", got " + got);
            check(CrackerState.get().getPlanStage() == CrackerState.PlanStage.DONE, "final enchant detected, plan DONE: "
                    + CrackerState.get().getPlanStage());
            click(0, ClickType.QUICK_MOVE);
        });
        step(10, () -> CrackerScreen.open(CrackerScreen.Tab.PLAN));
        step(5, () -> shot("plan_after"));
        step(10, () -> Mc.openScreen(null));

        if (!APOTH) {
            // Area: ask for 8 shelves, outline, apply the changes, and let the real table confirm.
            step(10, () -> {
                CrackerState state = CrackerState.get();
                EnchantCalculator.Request request = new EnchantCalculator.Request();
                request.playerSeed = state.getPlayerSeed();
                request.currentXpSeed = state.getEffectiveXpSeed();
                request.item = "diamond_sword";
                request.setups = Collections.singletonList(Models.vanillaTable(8));
                request.playerLevel = 200;
                request.maxThrows = 5;
                List<EnchantCalculator.Result> results = EnchantCalculator.calculateOptions(request);
                state.setPlanOptions(results);
                check(!results.isEmpty() && results.get(0).bookshelves == 8, "made a plan needing 8 shelves");
            });
            step(20, () -> command("/tp " + NAME + " " + (table.func_177958_n() + 0.5) + " " + (table.func_177956_o() + 4) + " "
                    + (table.func_177952_p() + 2.5) + " facing " + p(table, 0, 0, 0)));
            step(20, () -> shot("area_outlines"));
            step(10, () -> command("/tp " + NAME + " " + (table.func_177958_n() + 0.5) + " " + table.func_177956_o() + " "
                    + (table.func_177952_p() + 3.5) + " facing " + p(table, 0, 0, 0)));
            step(10, () -> {
                EnchantArea.Adjustment adj = AreaTracker.adjustmentFor(8);
                check(adj != null && adj.possible, "area planner can reach 8 shelves");
                log("  adjustment: " + AreaTracker.describe(adj));
                for (EnchantArea.Gap gap : adj.block) {
                    command("/setblock " + p(table, gap.dx, 0, gap.dz) + " torch");
                }
                for (EnchantArea.Gap gap : adj.unblock) {
                    command("/setblock " + p(table, gap.dx, 0, gap.dz) + " air");
                    command("/setblock " + p(table, gap.dx, 1, gap.dz) + " air");
                }
                for (EnchantArea.Shelf shelf : adj.remove) {
                    command("/setblock " + p(table, shelf.dx, shelf.dy, shelf.dz) + " air");
                }
            });
            step(30, () -> check(AreaTracker.currentShelves() == 8, "after the changes the area reads 8: "
                    + AreaTracker.currentShelves()));
            step(20, TableTestSteps::open);
            step(15, () -> place("diamond_sword", 0));
            step(10, () -> {
                Slot lapis = find("lapis_lazuli");
                click(lapis.field_75222_d, ClickType.PICKUP);
                click(1, ClickType.PICKUP);
            });
            step(15, () -> {
                check(CrackerState.get().getTableBookshelves() == 8, "the real table agrees: 8 shelves ("
                        + CrackerState.get().getTableBookshelves() + ")");
                check(CrackerState.get().getTableProblem() == null, "no prediction mismatch at 8 shelves");
            });
            predictAndEnchant("diamond_sword", 1);
            step(10, () -> Mc.openScreen(null));
        }

        step(5, () -> {
            log("");
            log("passed " + passes + ", failed " + fails);
            log(fails == 0 ? "SELFTEST OK" : "SELFTEST FAILED");
            report.close();
        });
        step(40, () -> mc().func_71400_g()); // shutdown
    }

    static final class TableTestSteps {
        static void open() {
            openTable();
        }
    }

    static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        totalTicks++;
        if (index >= steps.size()) {
            return;
        }
        BooleanSupplier until = untils.get(index);
        if (until != null) {
            if (!until.getAsBoolean()) {
                return;
            }
        } else if (waited < (index == 0 ? 0 : waits.get(index - 1))) {
            waited++;
            return;
        }
        waited = 0;
        try {
            steps.get(index).run();
        } catch (Throwable t) {
            fails++;
            log("FAIL step " + index + " threw " + t);
            t.printStackTrace();
        }
        index++;
    }

    static void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingShot == null) {
            return;
        }
        String name = pendingShot + ".png";
        pendingShot = null;
        net.minecraft.util.ScreenShotHelper.func_148259_a(mc().field_71412_D, name,
                mc().func_228018_at_().func_198109_k(), mc().func_228018_at_().func_198091_l(),
                mc().func_147110_a(), message -> log("  screenshot " + name));
    }
}
