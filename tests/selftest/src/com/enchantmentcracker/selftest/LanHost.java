package com.enchantmentcracker.selftest;

import com.enchantmentcracker.game.Mc;
import net.minecraft.client.gui.screen.MainMenuScreen;
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

import static com.enchantmentcracker.selftest.SelfTest.*;

/**
 * The host side of the LAN test: a singleplayer world opened to LAN, exactly as a friend hosting
 * a LAN game does, so the guest (NetTest in another client) plays on an integrated server over a
 * real connection. Offline mode, since the test accounts are not real ones. Test harness only.
 */
final class LanHost {

    static final int PORT = Integer.getInteger("enchcracker.selftest.port", 25601);

    private LanHost() {
    }

    /** Every server tick: keep the spy on the guest player's generator. */
    static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.START || server() == null) {
            return;
        }
        RngSpy.tick++;
        net.minecraft.entity.player.ServerPlayerEntity guest = null;
        for (net.minecraft.entity.player.ServerPlayerEntity p : server().func_184103_al().func_181057_v()) { // getPlayerList().getPlayers()
            if ("SelfTest".equals(p.func_146103_bH().getName())) { // getGameProfile()
                guest = p;
            }
        }
        if (guest == null) {
            return;
        }
        if (RngSpy.out == null) {
            try {
                RngSpy.out = new PrintWriter(new FileWriter(new File(mc().field_71412_D, "rng-calls.txt")));
            } catch (IOException e) {
                log("no rng log: " + e);
                return;
            }
        }
        try {
            if (RngSpy.install(guest)) {
                RngSpy.out.println("tick " + RngSpy.tick + " spy installed on the guest's generator");
                RngSpy.out.flush();
            }
        } catch (Exception e) {
            log("spy failed: " + e);
        }
        if (RngSpy.tick % 20 == 0) {
            RngSpy.out.flush();
        }
    }

    static void build() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(LanHost::onServerTick);
        stepUntil(() -> {
            Object screen = mc().field_71462_r;
            if (screen != null && screen.getClass().getSimpleName().contains("LoadingErrorScreen")) {
                Mc.openScreen(new MainMenuScreen());
                return false;
            }
            return screen instanceof MainMenuScreen;
        }, () -> {
            try {
                report = new PrintWriter(new FileWriter(new File(mc().field_71412_D, "selftest-report-lanhost.txt")));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log("LAN host, port " + PORT);
            DynamicRegistries.Impl registries = DynamicRegistries.func_239770_b_();
            WorldSettings settings = new WorldSettings("lan", GameType.SURVIVAL, false, Difficulty.PEACEFUL,
                    true, new GameRules(), net.minecraft.util.datafix.codec.DatapackCodec.field_234880_a_);
            DimensionGeneratorSettings gen = DimensionGeneratorSettings.func_242751_a(
                    registries.func_243612_b(Registry.field_239698_ad_),
                    registries.func_243612_b(Registry.field_239720_u_),
                    registries.func_243612_b(Registry.field_243549_ar));
            mc().func_238192_a_("lan", settings, registries, gen);
        });
        stepUntil(() -> Mc.player() != null && mc().field_71462_r == null, () -> log("host joined its world"));
        step(60, () -> {
            command("/gamerule doDaylightCycle false");
            command("/time set noon");
            command("/gamerule doMobSpawning false");
            // Out of the guest's way: the guest builds its table next to the shared spawn.
            command("/tp " + Mc.player().func_200200_C_().getString() + " ~40 ~ ~40");
        });
        step(20, () -> {
            server().func_71229_d(false); // setOnlineMode(false): the test guest has no real account
            boolean ok = server().func_195565_a(GameType.SURVIVAL, true, PORT); // shareToLAN(mode, cheats, port)
            check(ok, "opened to LAN on port " + PORT);
            try {
                new File(mc().field_71412_D, "lan-ready").createNewFile();
            } catch (IOException e) {
                log("could not write lan-ready: " + e);
            }
        });
        // Host until the launcher says the guest is done.
        stepUntil(() -> new File(mc().field_71412_D, "lan-stop").exists(), () -> {
            log("stop requested; passed " + passes + ", failed " + fails);
            log(fails == 0 ? "SELFTEST OK" : "SELFTEST FAILED");
            report.close();
        });
        step(20, () -> mc().func_71400_g()); // shutdown
    }
}
