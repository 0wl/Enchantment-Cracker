package com.enchantmentcracker.selftest;

import com.enchantmentcracker.client.ClientEvents;
import com.enchantmentcracker.client.Planner;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.game.AutoDropper;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.ServerRng;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.settings.DimensionGeneratorSettings;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.enchantmentcracker.selftest.SelfTest.*;

/**
 * The prediction model against the user's whole modpack: all its mods, configs and scripts, and
 * a table of Mahogany Bookshelves like theirs. In your own world the seed is read directly, so
 * every prediction can be compared 1:1 with what the pack's enchanting really gives. Test only.
 */
final class PackTest {

    static final String[] ITEMS = {"diamond_boots", "diamond_leggings", "diamond_chestplate", "diamond_helmet",
            "diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "bow", "crossbow", "trident",
            "fishing_rod", "book", "iron_chestplate", "golden_helmet"};

    static int predictions;
    static int plansDone;
    static EnchantCalculator.Result plan;
    static int waitTicks;

    private PackTest() {
    }

    /** Every server tick: the spy on the player's generator, so any mod drawing from it shows up. */
    static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.START || server() == null || Mc.player() == null) {
            return;
        }
        RngSpy.tick++;
        try {
            net.minecraft.entity.player.ServerPlayerEntity me = serverPlayer();
            if (me == null) {
                return;
            }
            if (RngSpy.out == null) {
                RngSpy.out = new PrintWriter(new FileWriter(new File(mc().field_71412_D, "rng-calls.txt")));
            }
            if (RngSpy.install(me)) {
                RngSpy.out.println("tick " + RngSpy.tick + " spy installed");
            }
            if (RngSpy.tick % 20 == 0) {
                RngSpy.out.flush();
            }
        } catch (Exception e) {
            log("spy failed: " + e);
        }
    }

    static void build() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(PackTest::onServerTick);
        stepUntil(() -> {
            Object screen = mc().field_71462_r;
            if (screen != null && screen.getClass().getSimpleName().contains("LoadingErrorScreen")) {
                Mc.openScreen(new MainMenuScreen());
                return false;
            }
            return screen instanceof MainMenuScreen;
        }, () -> {
            try {
                report = new PrintWriter(new FileWriter(new File(mc().field_71412_D, "selftest-report-pack.txt")));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log("Enchantment Cracker: the whole DDSS2 pack, singleplayer (seed read directly)");
            DynamicRegistries.Impl registries = DynamicRegistries.func_239770_b_();
            WorldSettings settings = new WorldSettings("pack", GameType.SURVIVAL, false, Difficulty.PEACEFUL,
                    true, new GameRules(), net.minecraft.util.datafix.codec.DatapackCodec.field_234880_a_);
            DimensionGeneratorSettings gen = DimensionGeneratorSettings.func_242751_a(
                    registries.func_243612_b(Registry.field_239698_ad_),
                    registries.func_243612_b(Registry.field_239720_u_),
                    registries.func_243612_b(Registry.field_243549_ar));
            mc().func_238192_a_("pack", settings, registries, gen);
        });
        stepUntil(() -> Mc.player() != null && mc().field_71462_r == null || ++waitTicks > 2400,
                () -> {
                    waitTicks = 0;
                    log("joined the world");
                });
        // The pack may open screens on a first join (quest book, tips): close whatever is open.
        for (int i = 0; i < 4; i++) {
            step(100, () -> {
                if (Mc.player() != null && mc().field_71462_r != null) {
                    log("  closing " + mc().field_71462_r.getClass().getSimpleName());
                    Mc.openScreen(null);
                }
            });
        }
        step(60, () -> {
            BlockPos at = Mc.player().func_233580_cy_();
            table = at.func_177982_a(0, 0, 4);
            command("/gamerule doDaylightCycle false");
            command("/gamerule doMobSpawning false");
            command("/time set noon");
            command("/fill " + p(table, -5, -1, -5) + " " + p(table, 5, 6, 6) + " air");
            command("/fill " + p(table, -4, -1, -4) + " " + p(table, 4, -1, 6) + " stone");
            command("/fill " + p(table, -2, 0, -2) + " " + p(table, 2, 1, 2) + " byg:mahogany_bookshelf");
            command("/fill " + p(table, -1, 0, -1) + " " + p(table, 1, 1, 1) + " air");
            command("/setblock " + p(table, 0, 0, 0) + " enchanting_table");
            command("/tp " + NAME + " " + (table.func_177958_n() + 0.5) + " " + table.func_177956_o() + " "
                    + (table.func_177952_p() + 3.5) + " facing " + p(table, 0, 1, 0));
            command("/clear " + NAME);
            command("/give " + NAME + " lapis_lazuli 256");
            command("/give " + NAME + " book 64");
            command("/give " + NAME + " cobblestone 1200");
            command("/xp add " + NAME + " 5000 levels");
            log("built a Mahogany Bookshelf table at " + table);
        });
        step(40, () -> {
        });
        step(20, SelfTest::openTable);
        step(20, () -> {
            EnchantmentContainer c = tableContainer();
            check(c != null, "table open");
            click(lapisSlot(), ClickType.PICKUP);
            click(1, ClickType.PICKUP);
        });
        step(10, () -> place("book", 0));
        step(25, () -> {
            check(CrackerState.get().getSource() == CrackerState.Source.DIRECT, "seed read directly");
            log("table reads " + (CrackerState.get().getTableSetup() == null ? "nothing"
                    : CrackerState.get().getTableSetup().describe()));
            check(CrackerState.get().getTableSetup() != null
                    && CrackerState.get().getTableSetup().describe().startsWith("Apotheosis E15.0 Q15"),
                    "the table reads like the user's (E15 Q15)");
            click(0, ClickType.QUICK_MOVE);
        });

        step(1200, () -> log("a quiet minute at the table (spy watching)"));
        // Every item type in every slot, twice over: prediction vs the pack's real enchanting.
        for (int pass = 0; pass < 2; pass++) {
            for (String item : ITEMS) {
                step(5, () -> command("/give " + NAME + " " + item + " 3"));
                for (int slot = 0; slot < 3; slot++) {
                    step(5, PackTest::supplies);
                    SelfTest.predictAndEnchant(item, slot);
                    step(1, () -> predictions++);
                }
                step(5, () -> command("/clear " + NAME + " " + item));
                step(5, () -> command("/clear " + NAME + " enchanted_book"));
            }
        }

        // The user's plans end to end: planner, auto drop, a book as the dummy, the real item.
        userPlan("diamond_boots", new EnchantmentInstance("protection", 4), new EnchantmentInstance("unbreaking", 3),
                new EnchantmentInstance("feather_falling", 4));
        userPlan("diamond_leggings", new EnchantmentInstance("protection", 4), new EnchantmentInstance("unbreaking", 3));
        userPlan("diamond_chestplate", new EnchantmentInstance("protection", 4), new EnchantmentInstance("unbreaking", 3));
        userPlan("diamond_boots", new EnchantmentInstance("protection", 4), new EnchantmentInstance("unbreaking", 3),
                new EnchantmentInstance("feather_falling", 4));

        step(5, () -> {
            log("");
            log("predicted enchantments checked: " + predictions + ", plans delivered exactly: " + plansDone);
            log("passed " + passes + ", failed " + fails);
            log(fails == 0 ? "SELFTEST OK" : "SELFTEST FAILED");
            report.close();
        });
        step(40, () -> mc().func_71400_g());
    }

    /** Tops up the table's lapis and the book supply (Apotheosis keeps lapis in the table). */
    static void supplies() {
        EnchantmentContainer c = tableContainer();
        if (c != null && c.func_75139_a(1).func_75211_c().func_190916_E() < 16) {
            net.minecraft.inventory.container.Slot from = find("lapis_lazuli");
            if (from != null) {
                click(from.field_75222_d, ClickType.QUICK_MOVE); // shift-click into the lapis slot
            }
        }
        if (count("book") < 8) {
            command("/give " + NAME + " book 32");
        }
        if (count("lapis_lazuli") < 64) {
            command("/give " + NAME + " lapis_lazuli 128");
        }
    }

    static int lapisSlot() {
        net.minecraft.inventory.container.Slot s = find("lapis_lazuli");
        return s == null ? 1 : s.field_75222_d;
    }

    static void userPlan(String item, EnchantmentInstance... wishes) {
        step(10, PackTest::supplies);
        step(10, () -> {
            plan = null;
            command("/give " + NAME + " " + item + " 1");
            command("/clear " + NAME + " enchanted_book");
            if (count("cobblestone") < 1200) {
                command("/give " + NAME + " cobblestone " + (1200 - count("cobblestone")));
            }
            if (tableContainer() != null && tableContainer().func_75139_a(1).func_75211_c().func_190926_b()) {
                click(lapisSlot(), ClickType.PICKUP);
                click(1, ClickType.PICKUP);
            }
        });
        step(20, () -> {
            EnchantCalculator.Request request = new EnchantCalculator.Request();
            String why = Planner.prepare(request, item, Arrays.asList(wishes), Collections.emptyList(), 3);
            check(why == null, "planner ready (" + why + ")");
            if (why != null) {
                return;
            }
            request.maxThrows = 1100;
            // Only the table as it stands: nothing in this test rebuilds it.
            request.setups = Collections.singletonList(CrackerState.get().getTableSetup());
            List<EnchantCalculator.Result> results = EnchantCalculator.calculateOptions(request);
            if (results.isEmpty()) {
                log("  " + item + " " + Arrays.toString(wishes) + ": no plan within 1100 drops, skipped");
                return;
            }
            plan = results.get(0);
            CrackerState.get().setPlanOptions(results);
            log("  plan: " + item + " " + Arrays.toString(wishes) + " -> drop " + plan.itemsToThrow + ", dummy "
                    + plan.needsDummy() + ", slot " + (plan.slot + 1) + ", gives " + plan.enchantments);
            if (plan.needsDummy() && plan.itemsToThrow > 0) {
                AutoDropper.setJunkItem("cobblestone");
                ClientEvents.startPlanDrops();
            }
        });
        stepUntil(() -> plan == null || !AutoDropper.isRunning() || ++waitTicks > 4000, () -> waitTicks = 0);
        step(60, () -> {
        });
        step(10, () -> {
            if (plan == null) {
                return;
            }
            long now = ServerRng.readPlayerSeed();
            check(CrackerState.get().getDropsSincePlan() == Math.max(0, plan.itemsToThrow),
                    "drops " + CrackerState.get().getDropsSincePlan() + " == planned " + plan.itemsToThrow);
            check(CrackerState.get().getPlayerSeed() == now, "tracked seed == the world's " + PlayerSeed.format(now));
            if (tableContainer() == null) {
                SelfTest.openTable();
            }
        });
        step(20, () -> {
            if (plan != null && plan.needsDummy()) {
                place("book", 0);
            }
        });
        step(20, () -> {
            if (plan != null && plan.needsDummy()) {
                Mc.enchant(tableContainer().field_75152_c, 0);
            }
        });
        step(20, () -> {
            if (plan == null) {
                return;
            }
            check(CrackerState.get().getPlanStage() == CrackerState.PlanStage.FINAL, "stage FINAL: "
                    + CrackerState.get().getPlanStage());
            if (plan.needsDummy()) {
                click(0, ClickType.QUICK_MOVE);
            }
        });
        step(15, () -> {
            if (plan != null) {
                place(item, 0);
            }
        });
        step(25, () -> {
            if (plan == null) {
                return;
            }
            check(com.enchantmentcracker.client.gui.tabs.PlanTab.wrongItemWarning(CrackerState.get(), plan) == null,
                    "no final-step warning");
            Mc.enchant(tableContainer().field_75152_c, plan.slot);
        });
        step(20, () -> {
            if (plan == null) {
                return;
            }
            Set<String> got = enchantsOf(tableContainer().func_75139_a(0).func_75211_c());
            boolean same = got.equals(asSet(plan.enchantments));
            check(same, "1:1 " + item + ": planned " + asSet(plan.enchantments) + ", got " + got);
            plansDone += same ? 1 : 0;
            click(0, ClickType.QUICK_MOVE);
            CrackerState.get().confirmPlan();
        });
        step(5, () -> command("/clear " + NAME + " " + item));
    }
}
