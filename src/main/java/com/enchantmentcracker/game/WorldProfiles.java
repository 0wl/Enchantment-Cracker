package com.enchantmentcracker.game;

import com.enchantmentcracker.EnchantmentCrackerMod;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * One small file per world or server under {@code config/enchcracker/worlds/}, holding what
 * is worth keeping between sessions.
 *
 * <p>The key is the save folder in singleplayer (and for the host of a LAN game), the LAN
 * world's name for a LAN guest (its port changes every time it is opened, so the address is
 * useless), and the address for a server.
 */
public final class WorldProfiles {

    private static Field saveField;

    private WorldProfiles() {
    }

    /** A stable, file-name-safe key for where the player is now, or null. */
    public static String currentKey() {
        IntegratedServer server = Mc.integratedServer();
        if (server != null) {
            return "sp_" + safe(saveName(server));
        }
        ServerData data = Mc.serverData();
        if (data == null) {
            return null;
        }
        if (data.func_181041_d()) { // isOnLAN
            return "lan_" + safe(data.field_78847_a); // serverName: "Player - World"
        }
        return "mp_" + safe(data.field_78845_b); // serverIP
    }

    /** A readable label for the Settings tab. */
    public static String describeCurrent() {
        IntegratedServer server = Mc.integratedServer();
        if (server != null) {
            return (ServerRng.isAvailable() && Mc.serverData() == null ? "your world " : "world ") + saveName(server);
        }
        ServerData data = Mc.serverData();
        if (data == null) {
            return "nowhere";
        }
        return data.func_181041_d() ? "LAN world " + data.field_78847_a : "server " + data.field_78845_b;
    }

    private static String saveName(IntegratedServer server) {
        try {
            if (saveField == null) {
                // MinecraftServer.anvilConverterForAnvilFile (the LevelSave) is protected.
                saveField = net.minecraft.server.MinecraftServer.class.getDeclaredField("field_71310_m");
                saveField.setAccessible(true);
            }
            Object save = saveField.get(server);
            // levelSave.getSaveName()
            return ((net.minecraft.world.storage.SaveFormat.LevelSave) save).func_237282_a_();
        } catch (Throwable t) {
            return "singleplayer";
        }
    }

    private static String safe(String text) {
        if (text == null || text.isEmpty()) {
            return "unknown";
        }
        String cleaned = text.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static Path file(String key) {
        return FMLPaths.CONFIGDIR.get().resolve("enchcracker").resolve("worlds").resolve(key + ".properties");
    }

    public static Map<String, String> load(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (key == null) {
            return out;
        }
        Path path = file(key);
        if (!Files.exists(path)) {
            return out;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            EnchantmentCrackerMod.LOGGER.warn("Could not read {}", path, e);
            return out;
        }
        for (String name : props.stringPropertyNames()) {
            out.put(name, props.getProperty(name));
        }
        return out;
    }

    public static void save(String key, Map<String, String> values) {
        if (key == null) {
            return;
        }
        Path path = file(key);
        Properties props = new Properties();
        props.putAll(values);
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Enchantment Cracker per-world memory");
            }
        } catch (IOException e) {
            EnchantmentCrackerMod.LOGGER.warn("Could not write {}", path, e);
        }
    }
}
