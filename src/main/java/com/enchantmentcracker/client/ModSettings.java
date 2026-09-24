package com.enchantmentcracker.client;

import com.enchantmentcracker.EnchantmentCrackerMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The switches on the Settings tab, saved to {@code config/enchcracker-client.properties}.
 *
 * <p>Key bindings are not here: they live in Minecraft's own Options → Controls, under
 * "Enchantment Cracker", and are saved with the rest of your controls.
 */
public final class ModSettings {

    /** Keep a small profile per world/server: XP seed, calculator setup, table. */
    public static boolean rememberPerWorld = true;
    /** Show the Anvil tab. */
    public static boolean anvilPlanner = true;
    /** Auto-drop buttons on the inventory and the enchanting table. */
    public static boolean autoDrop = true;
    /** Find the table and its shelves by itself, and fill the shelf count in everywhere. */
    public static boolean autoDetectArea = true;
    /** Outline what to block or clear around the table when a plan needs a different count. */
    public static boolean areaOutlines = true;
    /** Write the real enchantments over the enchanting table screen. */
    public static boolean tablePrediction = true;
    /** Items thrown per game tick while auto-dropping. Slower is kinder to busy servers. */
    public static int dropsPerTick = 2;
    /** The picked junk item, by id. */
    public static String junkItem = "";
    /** Lock the seed from a thrown item's velocity, so a server needs one enchant, not two. */
    public static boolean velocityCrack = true;

    private ModSettings() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("enchcracker-client.properties");
    }

    public static void load() {
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            EnchantmentCrackerMod.LOGGER.warn("Could not read {}", path, e);
            return;
        }
        rememberPerWorld = bool(props, "rememberPerWorld", rememberPerWorld);
        anvilPlanner = bool(props, "anvilPlanner", anvilPlanner);
        autoDrop = bool(props, "autoDrop", autoDrop);
        autoDetectArea = bool(props, "autoDetectArea", autoDetectArea);
        areaOutlines = bool(props, "areaOutlines", areaOutlines);
        tablePrediction = bool(props, "tablePrediction", tablePrediction);
        velocityCrack = bool(props, "velocityCrack", velocityCrack);
        try {
            dropsPerTick = Math.max(1, Math.min(8, Integer.parseInt(props.getProperty("dropsPerTick", "2").trim())));
        } catch (NumberFormatException ignored) {
            dropsPerTick = 2;
        }
        junkItem = props.getProperty("junkItem", "").trim();
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("rememberPerWorld", String.valueOf(rememberPerWorld));
        props.setProperty("anvilPlanner", String.valueOf(anvilPlanner));
        props.setProperty("autoDrop", String.valueOf(autoDrop));
        props.setProperty("autoDetectArea", String.valueOf(autoDetectArea));
        props.setProperty("areaOutlines", String.valueOf(areaOutlines));
        props.setProperty("tablePrediction", String.valueOf(tablePrediction));
        props.setProperty("velocityCrack", String.valueOf(velocityCrack));
        props.setProperty("dropsPerTick", String.valueOf(dropsPerTick));
        props.setProperty("junkItem", junkItem == null ? "" : junkItem);
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Enchantment Cracker client settings. Key bindings are in Options > Controls.");
            }
        } catch (IOException e) {
            EnchantmentCrackerMod.LOGGER.warn("Could not write {}", path, e);
        }
    }

    private static boolean bool(Properties props, String key, boolean fallback) {
        String value = props.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }
}
