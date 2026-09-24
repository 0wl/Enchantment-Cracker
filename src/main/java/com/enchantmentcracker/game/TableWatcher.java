package com.enchantmentcracker.game;

import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.Models;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.TableSetup;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Watches the open enchanting table and keeps {@link CrackerState} fed.
 *
 * <p>In 1.16.5 the server sends the XP seed to the client so the screen can render the
 * enchantment hints, which means the client already knows the number the whole technique
 * revolves around. Reading it here is what makes the automatic workflow possible.
 *
 * <p>Any screen whose container is an {@code EnchantmentContainer} counts, not just the
 * vanilla screen class: mods that replace the table (Apotheosis does) usually keep the
 * container type but bring their own screen.
 */
public final class TableWatcher {

    private static BlockPos lastTablePos;
    private static int lastBookshelves = -1;
    /** Apotheosis zeroes its stats when the table is empty, so remember the last real ones per table. */
    private static final Map<BlockPos, Apotheosis.Stats> apothStats = new HashMap<>();

    private TableWatcher() {
    }

    /** Remembers which table the player actually opened, so the shelf scan is exact. */
    public static void rememberTable(World world, BlockPos pos) {
        if (BookshelfCounter.isEnchantingTable(world, pos)) {
            lastTablePos = pos;
        }
    }

    public static BlockPos getLastTablePos() {
        return lastTablePos;
    }

    public static void setLastTablePos(BlockPos pos) {
        lastTablePos = pos;
    }

    public static int getLastBookshelves() {
        return lastBookshelves;
    }

    /** Forget everything about the previous world. */
    public static void reset() {
        lastTablePos = null;
        lastBookshelves = -1;
        apothStats.clear();
    }

    /** Called every client tick. */
    public static void tick() {
        CrackerState state = CrackerState.get();

        EnchantmentContainer container = openContainer();
        if (container == null) {
            state.setTableClosed();
            return;
        }

        int xpSeed = container.func_217005_f();            // getXPSeed()
        int[] levels = container.field_75167_g.clone();    // enchantLevels
        String item = itemIdIn(container);
        boolean hasLevels = levels[0] != 0 || levels[1] != 0 || levels[2] != 0;

        TableSetup setup;
        String problem = null;
        int bookshelves;
        if (Apotheosis.isApothContainer(container)) {
            setup = apotheosisSetup(container, item, hasLevels);
            bookshelves = -1;
            if (setup == null) {
                problem = "Put an item in the table so its stats can be read.";
            }
        } else {
            bookshelves = resolveBookshelves(xpSeed, levels, item);
            setup = bookshelves < 0 ? null : Models.vanillaTable(bookshelves);
            if (bookshelves < 0) {
                problem = "Cannot see the bookshelves around this table.";
            }
        }
        lastBookshelves = bookshelves;

        // The safety net for every mod we do not know about: if what we would predict does not
        // match what the server actually put on the table, say so instead of quietly lying.
        if (setup != null && item != null && hasLevels) {
            int[] predicted = setup.levels(xpSeed, item);
            if (!Arrays.equals(predicted, levels)) {
                problem = "This table's numbers differ from the prediction ("
                        + levels[0] + "/" + levels[1] + "/" + levels[2] + " vs "
                        + predicted[0] + "/" + predicted[1] + "/" + predicted[2]
                        + "). Another mod may change enchanting here.";
            }
        }

        state.setTableState(true, bookshelves, levels, item);
        state.setTableSetup(setup, problem);
        // The window opens before the server's data packets arrive; an XP seed of exactly 0 on
        // an empty table is almost certainly "not synced yet", not a real value.
        if (xpSeed != 0 || hasLevels) {
            state.observeXpSeed(xpSeed);
            state.noteXpSeed(xpSeed, false);
        }
    }

    private static TableSetup apotheosisSetup(EnchantmentContainer container, String item, boolean hasLevels) {
        BlockPos key = lastTablePos;
        if (item != null && hasLevels) {
            ItemStack stack = container.func_75139_a(0).func_75211_c(); // getSlot(0).getStack()
            int enchantability = stack.getItemEnchantability();
            // In your own world, read the server's copy: same numbers, full precision.
            Apotheosis.Stats stats = null;
            Container serverSide = ServerRng.serverOpenContainer();
            if (serverSide != null && Apotheosis.isApothContainer(serverSide)) {
                stats = Apotheosis.readStats(serverSide, enchantability, true);
            }
            if (stats == null) {
                stats = Apotheosis.readStats(container, enchantability, false);
            }
            if (stats != null) {
                apothStats.put(key, stats);
            }
        }
        Apotheosis.Stats stats = apothStats.get(key);
        return stats == null ? null : new Apotheosis.Table(stats);
    }

    /** The enchanting table container, if that is what's open. */
    public static EnchantmentContainer openContainer() {
        return enchantingContainerOf(Mc.currentScreen());
    }

    public static EnchantmentContainer enchantingContainerOf(Screen screen) {
        if (!(screen instanceof ContainerScreen)) {
            return null;
        }
        Container container = ((ContainerScreen<?>) screen).func_212873_a_(); // getContainer()
        return container instanceof EnchantmentContainer ? (EnchantmentContainer) container : null;
    }

    public static boolean isEnchantingScreen(Screen screen) {
        return enchantingContainerOf(screen) != null;
    }

    /** Item id of whatever is sitting in the enchant slot, or null. Modded items included. */
    public static String itemIdIn(EnchantmentContainer container) {
        ItemStack stack = container.func_75139_a(0).func_75211_c(); // getSlot(0).getStack()
        if (stack.func_190926_b()) {
            return null;
        }
        return Mc.idOf(stack.func_77973_b()); // getItem()
    }

    /**
     * Works out the bookshelf count the server used.
     *
     * <p>Preferred route is the level requirements themselves: given the XP seed and the
     * item, only one shelf count reproduces all three numbers, and that is exactly what
     * the server computed. With an empty table there is nothing to match, so we fall back
     * to scanning the blocks around the table the way vanilla does.
     */
    private static int resolveBookshelves(int xpSeed, int[] levels, String item) {
        if (item != null && (levels[0] != 0 || levels[1] != 0 || levels[2] != 0)) {
            int scanned = scanWorldBookshelves();
            // Try what the blocks say first; several counts can share the same numbers.
            if (scanned >= 0 && Arrays.equals(Models.vanillaTable(scanned).levels(xpSeed, item), levels)) {
                return scanned;
            }
            for (int shelves = 0; shelves <= BookshelfCounter.MAX_POWER; shelves++) {
                if (Arrays.equals(Models.vanillaTable(shelves).levels(xpSeed, item), levels)) {
                    return shelves;
                }
            }
            return scanned;
        }
        return scanWorldBookshelves();
    }

    /** Counts shelves around the table the player opened, or the nearest one. */
    public static int scanWorldBookshelves() {
        World world = Mc.world();
        if (world == null) {
            return -1;
        }
        BlockPos pos = lastTablePos;
        if (!BookshelfCounter.isEnchantingTable(world, pos) && Mc.player() != null) {
            pos = BookshelfCounter.findNearbyTable(world, Mc.player().func_233580_cy_()); // getPosition()
            if (pos != null) {
                lastTablePos = pos;
            }
        }
        return pos == null ? -1 : BookshelfCounter.count(world, pos);
    }

    /**
     * Pulls the exact RNG state out of the integrated server when that is possible.
     * Called every tick; cheap, and it keeps the seed permanently correct in singleplayer
     * and for the host of a LAN world.
     */
    public static void syncFromWorld() {
        if (!ServerRng.isAvailable()) {
            // Left the singleplayer world, or joined a server. A seed read from the old
            // world means nothing here, so drop it rather than quietly lie.
            CrackerState state = CrackerState.get();
            if (state.getSource() == CrackerState.Source.DIRECT) {
                state.resetSeed();
            }
            return;
        }
        long seed = ServerRng.readPlayerSeed();
        if (seed == PlayerSeed.UNKNOWN) {
            return;
        }
        CrackerState state = CrackerState.get();
        if (state.getPlayerSeed() != seed || state.getSource() != CrackerState.Source.DIRECT) {
            state.setFromWorld(seed);
        }
        Integer xpSeed = ServerRng.readXpSeed();
        if (xpSeed != null) {
            state.setDirectXpSeed(xpSeed);
            state.noteXpSeed(xpSeed, true);
        }
    }

    /** Level the player is at, for the calculator. */
    public static int playerLevel() {
        PlayerEntity player = Mc.player();
        return player == null ? 0 : player.field_71068_ca; // experienceLevel
    }
}
