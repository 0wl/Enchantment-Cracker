package com.enchantmentcracker;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Enchantment Cracker for Forge 1.16.5.
 *
 * <p>An in-game port of Earthcomputer and Hexicube's standalone Enchantment Cracker (MIT),
 * with the manual transcription taken out: the mod reads the enchanting table itself, works
 * out the player's RNG, and tells you exactly what to do to get the enchantment you want.
 *
 * <p>Entirely client side. Nothing is registered on a dedicated server.
 */
@Mod(EnchantmentCrackerMod.MOD_ID)
public final class EnchantmentCrackerMod {

    public static final String MOD_ID = "enchcracker";
    public static final String MOD_NAME = "Enchantment Cracker";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public EnchantmentCrackerMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onClientSetup);

        // Nothing here exists on the server, so servers must not demand it of joining
        // clients and servers without it must not be refused.
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.fml.ExtensionPoint.DISPLAYTEST,
                () -> org.apache.commons.lang3.tuple.Pair.of(
                        () -> net.minecraftforge.fml.network.FMLNetworkConstants.IGNORESERVERONLY,
                        (remoteVersion, isFromServer) -> true));
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::run);
    }

    /**
     * Split out so the class loader never touches a client-only class on a server.
     * Referenced through {@link DistExecutor}, which is why it is its own type.
     */
    private static final class ClientBootstrap {
        static void run() {
            com.enchantmentcracker.client.ModSettings.load();
            com.enchantmentcracker.game.AutoDropper.setJunkItem(com.enchantmentcracker.client.ModSettings.junkItem);
            com.enchantmentcracker.client.ModKeyBindings.register();
            net.minecraftforge.common.MinecraftForge.EVENT_BUS
                    .register(com.enchantmentcracker.client.ClientEvents.class);
            LOGGER.info("Enchantment Cracker ready. Press the 'Open Cracker GUI' key "
                    + "(default K) or click the button beside the enchanting table.");
        }
    }
}
