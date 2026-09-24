package com.enchantmentcracker.core;

/**
 * Holds the {@link EnchantModel} and the vanilla {@link TableSetup.Factory} the maths
 * currently runs on.
 *
 * <p>Both start on the ported, hard-coded vanilla implementations so the core works with no
 * game present. Once the game is up, the game layer swaps in versions backed by the live
 * registries and Minecraft's own enchanting code, and refreshes them whenever the registries
 * are re-synced by joining a server.
 */
public final class Models {

    private static volatile EnchantModel current = VanillaModel.INSTANCE;
    private static volatile TableSetup.Factory tables = VanillaTable.FACTORY;

    private Models() {
    }

    public static EnchantModel get() {
        return current;
    }

    public static void set(EnchantModel model) {
        current = model == null ? VanillaModel.INSTANCE : model;
    }

    public static TableSetup vanillaTable(int shelves) {
        return tables.vanilla(shelves);
    }

    public static void setTables(TableSetup.Factory factory) {
        tables = factory == null ? VanillaTable.FACTORY : factory;
    }
}
