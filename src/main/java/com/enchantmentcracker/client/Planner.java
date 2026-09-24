package com.enchantmentcracker.client;

import com.enchantmentcracker.EnchantmentCrackerMod;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.TableSetup;
import com.enchantmentcracker.game.Apotheosis;
import com.enchantmentcracker.game.AreaTracker;
import com.enchantmentcracker.game.GameTables;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs plan searches off the render thread.
 *
 * <p>A search can roll the table a hundred thousand times; with a big modpack's enchantment
 * list that is a second or two, which would freeze the game if done in a button handler.
 * One search runs at a time; starting another cancels the first.
 */
public final class Planner {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "EnchCracker-planner");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Job current;

    private Planner() {
    }

    /** One running or finished search. Poll it from a tab's {@code tick()}. */
    public static final class Job {
        /** Who asked, so a tab only picks up its own results. */
        public final String owner;
        public volatile int progress;
        public volatile int max = 1;
        public volatile boolean done;
        public volatile boolean cancelled;
        public volatile List<EnchantCalculator.Result> results = Collections.emptyList();
        public volatile String error;

        Job(String owner) {
            this.owner = owner;
        }

        public float fraction() {
            return max <= 0 ? 0 : Math.min(1.0F, progress / (float) max);
        }
    }

    public static Job current() {
        return current;
    }

    public static boolean isRunning() {
        Job job = current;
        return job != null && !job.done;
    }

    public static void cancel() {
        Job job = current;
        if (job != null) {
            job.cancelled = true;
        }
    }

    /** Starts a search, replacing any earlier one. Call on the client thread. */
    public static Job start(String owner, EnchantCalculator.Request request) {
        cancel();
        Job job = new Job(owner);
        // Build the shared item stack here, on the client thread, before the worker uses it.
        GameTables.stack(request.item);
        request.progress = (tried, max) -> {
            job.progress = tried;
            job.max = max;
            return !job.cancelled;
        };
        current = job;
        EXECUTOR.submit(() -> {
            try {
                job.results = EnchantCalculator.calculateOptions(request);
            } catch (Throwable t) {
                job.error = t.toString();
                EnchantmentCrackerMod.LOGGER.error("Plan search failed", t);
            } finally {
                job.done = true;
            }
        });
        return job;
    }

    // ------------------------------------------------------------ building requests

    /**
     * Why a search cannot run right now, or null when it can. The request is filled in
     * either way as far as possible.
     */
    public static String prepare(EnchantCalculator.Request request, String item,
                                 List<EnchantmentInstance> wanted, List<String> unwanted, int maxOptions) {
        CrackerState state = CrackerState.get();
        request.playerSeed = state.getPlayerSeed();
        request.currentXpSeed = state.getEffectiveXpSeed();
        request.item = item;
        request.playerLevel = state.getPlayerLevel();
        request.wanted = wanted;
        request.unwanted = unwanted;
        request.maxOptions = maxOptions;
        request.setups = setupsFor(state);

        if (!state.isLocked()) {
            return "No player seed yet. See the Seed tab.";
        }
        if (request.setups.isEmpty()) {
            return Apotheosis.isActive()
                    ? "Open your enchanting table with an item in it once, so its Eterna/Quanta/Arcana can be read."
                    : "No table setup known.";
        }
        return null;
    }

    /**
     * The tables a plan may use. A table another mod replaced is used as it stands; a vanilla
     * table may be set to any shelf count up to the most available (auto-detected when that
     * is on), trying the count it has now first.
     */
    public static List<TableSetup> setupsFor(CrackerState state) {
        TableSetup table = state.getTableSetup();
        if (table instanceof Apotheosis.Table) {
            // Search lower-power layouts too, the way a vanilla table searches shelf counts.
            return Apotheosis.searchSetups(((Apotheosis.Table) table).stats());
        }
        if (table != null && !table.isShelfBased()) {
            return Collections.singletonList(table);
        }
        if (Apotheosis.isActive()) {
            // An Apotheosis world, but its table has not been read yet.
            return Collections.emptyList();
        }
        int max = state.getMaxBookshelves();
        int preferred = state.getTableBookshelves();
        if (ModSettings.autoDetectArea && AreaTracker.hasTable()) {
            max = AreaTracker.potentialShelves();
            preferred = AreaTracker.currentShelves();
        }
        return EnchantCalculator.vanillaSetups(max, preferred);
    }
}
