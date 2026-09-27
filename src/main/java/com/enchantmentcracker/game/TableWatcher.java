package com.enchantmentcracker.game;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.Models;
import com.enchantmentcracker.core.PartialXpSeed;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.TableSetup;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
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
 * <p>In your own world it arrives whole. Over a real connection only its low 16 bits do
 * (container data travels as a {@code short}), and the rest is worked out from what the table
 * shows, see {@link PartialXpSeed}.
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

    /**
     * Ticks the table's numbers and hints must hold still before they are trusted to narrow a
     * half-synced XP seed. Right after a click the client already shows the new item while the
     * server's numbers for it are still on their way.
     */
    private static final int STABLE_TICKS = 10;
    private static final PartialXpSeed partial = new PartialXpSeed();
    private static String observationKey;
    private static int stableTicks;
    /** The open table's full XP seed, or null while it is not known (yet). */
    private static Integer currentXpSeed;

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
        partial.reset();
        observationKey = null;
        stableTicks = 0;
        currentXpSeed = null;
    }

    /**
     * The open table's full XP seed, or null when it is not known: no table open, or on a
     * server before enough of the table has been seen to work out its top half.
     */
    public static Integer currentXpSeed() {
        return currentXpSeed;
    }

    /** Called every client tick. */
    public static void tick() {
        CrackerState state = CrackerState.get();

        EnchantmentContainer container = openContainer();
        if (container == null) {
            state.setTableClosed();
            currentXpSeed = null;
            return;
        }

        int synced = container.func_217005_f();            // getXPSeed()
        int[] levels = container.field_75167_g.clone();    // enchantLevels
        String item = itemIdIn(container);
        boolean hasLevels = levels[0] != 0 || levels[1] != 0 || levels[2] != 0;
        // The window opens before the server's data packets arrive; an XP seed of exactly 0 on
        // an empty table is almost certainly "not synced yet", not a real value.
        boolean seedSynced = synced != 0 || hasLevels;

        // Own world (singleplayer, LAN host): the packet never left memory, so it is whole.
        boolean whole = Mc.isSingleplayer();
        if (!whole && partial.setSynced(synced)) {
            observationKey = null;
        }
        Integer xpSeed = whole ? Integer.valueOf(synced)
                : seedSynced ? state.knownFullXpSeed(PartialXpSeed.lowBits(synced)) : null;

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

        if (xpSeed == null && seedSynced) {
            xpSeed = resolvePartial(container, setup, item, levels, hasLevels);
            if (xpSeed == null && problem == null) {
                problem = partialProblem(item, hasLevels);
            }
        }
        currentXpSeed = xpSeed;

        // The safety net for every mod we do not know about: if what we would predict does not
        // match what the server actually put on the table, say so instead of quietly lying.
        if (xpSeed != null && setup != null && item != null && hasLevels) {
            int[] predicted = setup.levels(xpSeed, item);
            if (!Arrays.equals(predicted, levels)) {
                // The common, harmless case: the player added or removed shelves while the table
                // was open. Vanilla only recomputes the numbers when the item changes, so the count
                // now scanned is ahead of the numbers shown. Tell them to refresh, not that a mod
                // is interfering.
                int shownFor = Apotheosis.isApothContainer(container) ? -1 : matchingVanillaShelves(xpSeed, levels, item);
                if (shownFor >= 0 && shownFor != bookshelves) {
                    problem = "Bookshelves now read " + bookshelves + ", but the table still shows the numbers for "
                            + shownFor + ". Take the item out and back in to refresh it.";
                } else {
                    problem = "This table's numbers differ from the prediction ("
                            + levels[0] + "/" + levels[1] + "/" + levels[2] + " vs "
                            + predicted[0] + "/" + predicted[1] + "/" + predicted[2]
                            + "). Another mod may change enchanting here.";
                }
            }
        }

        state.setTableState(true, bookshelves, levels, item);
        state.setTableSetup(setup, problem);
        if (seedSynced) {
            if (xpSeed != null) {
                state.observeXpSeed(xpSeed);
            }
            // Only watches for changes, so the synced value will do, whole or not.
            state.noteXpSeed(synced, false);
        }
    }

    /**
     * Works out the top half of a server's XP seed from what the table shows, once it has held
     * still for a moment. Null until exactly one full XP seed fits.
     */
    private static Integer resolvePartial(EnchantmentContainer container, TableSetup setup, String item,
                                          int[] levels, boolean hasLevels) {
        if (setup != null && item != null && hasLevels) {
            EnchantmentInstance[] clues = cluesOf(container);
            @SuppressWarnings("unchecked")
            java.util.List<EnchantmentInstance>[] clueLists = new java.util.List[3];
            boolean[] allClues = new boolean[3];
            StringBuilder extra = new StringBuilder();
            for (int slot = 0; slot < 3; slot++) {
                Object[] shown = Apotheosis.screenClues(Mc.currentScreen(), slot);
                // Only when it agrees with the table's own hint: the screen keeps old ones around.
                if (shown != null && clues[slot] != null && clues[slot] != PartialXpSeed.NO_CLUE) {
                    @SuppressWarnings("unchecked")
                    java.util.List<EnchantmentInstance> list = (java.util.List<EnchantmentInstance>) shown[0];
                    if (list.get(0).equals(clues[slot])) {
                        clueLists[slot] = list;
                        allClues[slot] = (Boolean) shown[1];
                        extra.append(slot).append(list).append(shown[1]);
                    }
                }
            }
            String key = partial.getLow() + "|" + item + "|" + Arrays.toString(levels) + "|"
                    + describe(clues) + "|" + extra + "|" + setup.describe();
            if (key.equals(observationKey)) {
                stableTicks++;
            } else {
                observationKey = key;
                stableTicks = 0;
            }
            if (stableTicks == STABLE_TICKS) {
                partial.observe(new PartialXpSeed.Observation(setup, item, levels, clues, clueLists, allClues));
            }
        } else {
            observationKey = null;
        }
        Integer resolved = partial.resolved();
        if (resolved == null) {
            CrackerState.get().notePartialXpSeed(partial.getLow(), partial.count(), partial.candidates());
        }
        return resolved;
    }

    private static String partialProblem(String item, boolean hasLevels) {
        int count = partial.count();
        String seed = PartialXpSeed.format(partial.getLow());
        if (count == 0) {
            return "No XP seed ending " + seed.substring(4) + " fits this table's numbers and hints. "
                    + "Another mod may change enchanting here.";
        }
        if (count < PartialXpSeed.ALL) {
            return count + " XP seeds fit this table (" + seed + "). Put a different enchantable item in to narrow it down.";
        }
        if (item != null && hasLevels) {
            return "Working out the XP seed from the table (" + seed + ")...";
        }
        return "On a server the table shares only half the XP seed (" + seed
                + "). Put an enchantable item in to work out the rest.";
    }

    /** The three hints the table shows, as {@link PartialXpSeed.Observation} wants them. */
    private static EnchantmentInstance[] cluesOf(EnchantmentContainer container) {
        EnchantmentInstance[] clues = new EnchantmentInstance[3];
        for (int slot = 0; slot < 3; slot++) {
            int id = container.field_185001_h[slot];      // enchantClue
            int level = container.field_185002_i[slot];   // worldClue
            if (id < 0) {
                clues[slot] = PartialXpSeed.NO_CLUE;
                continue;
            }
            Enchantment enchantment = Registry.field_212628_q.func_148745_a(id); // ENCHANTMENT.getByValue(id)
            String name = enchantment == null ? null : Mc.idOf(enchantment.getRegistryName());
            // An Apotheosis infusion recipe replaces slot 3's list; the item's own roll is not shown.
            clues[slot] = name == null || name.equals("apotheosis:infusion") ? null
                    : new EnchantmentInstance(name, level);
        }
        return clues;
    }

    private static String describe(EnchantmentInstance[] clues) {
        StringBuilder sb = new StringBuilder();
        for (EnchantmentInstance clue : clues) {
            sb.append(clue == null ? "?" : clue.enchantment + clue.level).append(',');
        }
        return sb.toString();
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
    private static int resolveBookshelves(Integer xpSeed, int[] levels, String item) {
        // The shelves actually placed around the table are the truth, and scanning them is the
        // one reading that follows the world live — so a shelf added or taken away is picked up at
        // once. (Back-solving from the level numbers cannot: vanilla leaves those stale until the
        // item in the table changes, which is exactly what made the count look stuck before.)
        int scanned = scanWorldBookshelves();
        if (scanned >= 0) {
            return scanned;
        }
        // Only when the blocks cannot be seen (view blocked, chunk unloaded) do we recover the
        // count from the level numbers instead.
        return item == null || xpSeed == null ? -1 : matchingVanillaShelves(xpSeed, levels, item);
    }

    /** The vanilla shelf count that reproduces these three level numbers, or -1 if none does. */
    private static int matchingVanillaShelves(int xpSeed, int[] levels, String item) {
        if (item == null || (levels[0] == 0 && levels[1] == 0 && levels[2] == 0)) {
            return -1;
        }
        for (int shelves = 0; shelves <= BookshelfCounter.MAX_POWER; shelves++) {
            if (Arrays.equals(Models.vanillaTable(shelves).levels(xpSeed, item), levels)) {
                return shelves;
            }
        }
        return -1;
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
