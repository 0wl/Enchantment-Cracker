package com.enchantmentcracker.core;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the mod knows about the player's RNG, plus the calculator's selections.
 *
 * <p>One instance lives for the whole client session so the GUI can be closed and
 * reopened without losing a cracked seed. Mutated from the client thread and, for
 * {@link #onItemDropped()}, from the integrated server thread, hence the synchronisation.
 */
public final class CrackerState {

    private static final CrackerState INSTANCE = new CrackerState();

    /** How far forward we will search when the RNG has drifted. 200k steps is 50k dropped items. */
    private static final int RESYNC_WINDOW = 200_000;
    /**
     * Largest (earlier candidates x later candidates) worth pairing. A wrong pair fits by chance
     * with odds of about this over 65,536, and only matters when the true pair is missing (something
     * else used the RNG); a wrong lock is then caught at the next enchantment.
     */
    private static final int PAIR_LIMIT = 1024;
    /** Largest candidate set a locked seed is re-synced against over the whole drift window. */
    private static final int RESYNC_SET_LIMIT = 64;
    /** How far back a re-sync looks for drops counted that never happened: 512 drops. */
    private static final int RESYNC_BACK_WINDOW = 2048;

    public static CrackerState get() {
        return INSTANCE;
    }

    public enum Status {
        /** Nothing known yet. */
        UNKNOWN,
        /** One XP seed captured; enchant once more to pin down the other 16 bits. */
        AWAITING_SECOND,
        /** Full 48-bit state known. */
        LOCKED
    }

    public enum Source {
        NONE("-"),
        /** Read straight out of the integrated server. Exact, singleplayer only. */
        DIRECT("read from world"),
        /** Solved from two consecutive XP seeds. */
        TWO_SEEDS("two XP seeds"),
        /** Typed in by hand. */
        MANUAL("entered by hand"),
        /** Recovered by the brute-force cracker. */
        CRACKED("brute-forced"),
        /** One XP seed plus a thrown item's velocity, so no second enchantment was spent. */
        VELOCITY("XP seed + throw");

        public final String label;

        Source(String label) {
            this.label = label;
        }
    }

    // ------------------------------------------------------------------ seed state

    private long playerSeed = PlayerSeed.UNKNOWN;
    private Status status = Status.UNKNOWN;
    private Source source = Source.NONE;
    private String statusMessage = "No seed yet.";

    private boolean hasTableXpSeed;
    private int tableXpSeed;
    /**
     * On a server the table only syncs the low 16 bits of the XP seed ({@link PartialXpSeed}).
     * True while the table is on an XP seed whose top half is not worked out yet; the old
     * {@link #tableXpSeed} is then out of date and must not be used.
     */
    private boolean tableXpSeedPartial;
    private int partialLow;
    private int partialCount = -1;
    /** The full XP seeds that still fit the partial one, once narrowed; null while unknown. */
    private int[] partialSet;
    /** Some table XP seed has been seen since the last reset. */
    private boolean seenTableSeed;
    /** The table's XP seed is left over from an old generator (see {@link #staleXpSeed}). */
    private boolean currentStale;
    /** The table's XP seed is the first one seen, not one we watched an enchantment make. */
    private boolean currentFirst;
    /**
     * Items dropped when the table's XP seed appeared, i.e. at the enchantment that made it. On a
     * server a seed may only be worked out later, and drops in between must not be mistaken for
     * drops before it.
     */
    private int seedChangeDrops;

    /**
     * What is known about the XP seed before the table's current one (exact: one value; on a
     * server possibly a few), to pair with the current one. Null when there is nothing to pair.
     */
    private int[] pendingSet;
    /** Items dropped when the pending XP seed appeared, so the steps between the two are known. */
    private int pendingDropBaseline;
    /** The pending XP seed came from the brute-force cracker and is the table's current one. */
    private boolean pendingIsCurrent;

    private int itemsDropped;
    private int driftSteps;
    /**
     * Set after joining a world or respawning. The server gives every new player entity a
     * freshly seeded generator, so the XP seed the table shows next is left over from the old
     * one: it is only a baseline to watch for the next change, not the first of two seeds.
     */
    private boolean staleXpSeed;

    // ------------------------------------------------------------ live table state

    private boolean tableOpen;
    private int tableBookshelves = -1;
    private final int[] tableLevels = new int[3];
    private String tableItem;
    /** How the open table turns seeds into offers; null until one has been opened. */
    private TableSetup tableSetup;
    /** Why predictions for the open table cannot be trusted, or null when they match. */
    private String tableProblem;

    // ----------------------------------------------------------- calculator state

    private String selectedItem = CrackItems.DIAMOND_PICKAXE;
    private int maxBookshelves = 15;
    private int playerLevel = 30;
    /** enchantment id -> wanted level, or 0 for "must not appear". */
    private final Map<String, Integer> wishlist = new LinkedHashMap<>();
    private EnchantCalculator.Result plan;
    private List<EnchantCalculator.Result> planOptions = new ArrayList<>();
    private int planStartDrops;
    /** Enchantments seen since the plan was set: the dummy, then the real one. */
    private int enchantsSincePlan;
    /** The last XP seed seen by {@link #noteXpSeed}, to spot enchantments; null until one is. */
    private Integer lastSeenXpSeed;

    private final SeedCracker cracker = new SeedCracker();

    private CrackerState() {
    }

    /**
     * Where to write what the server path decides (seeds appearing, candidates, lock attempts),
     * so a failure on someone's server can be worked through from their log. Set by the game layer.
     */
    private static java.util.function.Consumer<String> diagnostics = line -> { };

    /** Told the offset every time a locked seed is confirmed at an enchantment (0 = in sync). */
    private static java.util.function.IntConsumer resyncListener = offset -> { };

    public static void setResyncListener(java.util.function.IntConsumer listener) {
        resyncListener = listener == null ? offset -> { } : listener;
    }

    public static void setDiagnostics(java.util.function.Consumer<String> sink) {
        diagnostics = sink == null ? line -> { } : sink;
    }

    private static void diag(String line) {
        diagnostics.accept(line);
    }

    private static String list(int[] seeds) {
        if (seeds == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder().append(seeds.length).append(" [");
        for (int i = 0; i < seeds.length && i < 40; i++) {
            sb.append(i == 0 ? "" : " ").append(PlayerSeed.formatXpSeed(seeds[i]));
        }
        return sb.append(seeds.length > 40 ? " ...]" : "]").toString();
    }

    // ------------------------------------------------------------------ accessors

    public synchronized long getPlayerSeed() {
        return playerSeed;
    }

    public synchronized Status getStatus() {
        return status;
    }

    public synchronized Source getSource() {
        return source;
    }

    public synchronized String getStatusMessage() {
        return statusMessage;
    }

    public synchronized boolean isLocked() {
        return status == Status.LOCKED && playerSeed != PlayerSeed.UNKNOWN;
    }

    public synchronized int getCurrentXpSeed() {
        return isLocked() ? PlayerSeed.xpSeedOf(playerSeed) : 0;
    }

    public synchronized boolean hasTableXpSeed() {
        return hasTableXpSeed;
    }

    public synchronized int getTableXpSeed() {
        return tableXpSeed;
    }

    /**
     * The XP seed an enchantment done right now would use.
     *
     * <p>Not the same as {@code xpSeedOf(playerSeed)}: the player's XP seed is a snapshot
     * taken at the last enchantment, while the RNG state keeps moving every time an item
     * is dropped. Enchanting now uses the snapshot.
     */
    public synchronized Integer getEffectiveXpSeed() {
        if (tableXpSeedPartial) {
            return null;
        }
        if (hasTableXpSeed) {
            return tableXpSeed;
        }
        return isLocked() ? Integer.valueOf(PlayerSeed.xpSeedOf(playerSeed)) : null;
    }

    /** The full XP seeds that still fit the table's half-known one, once narrowed; else null. */
    public synchronized int[] getPartialSet() {
        return tableXpSeedPartial && partialSet != null ? partialSet.clone() : null;
    }

    public synchronized boolean isTableXpSeedPartial() {
        return tableXpSeedPartial;
    }

    /** The table's XP seed for display: in full, "????FB78" while only half is known, or "-". */
    public synchronized String getTableXpSeedText() {
        if (tableXpSeedPartial) {
            return PartialXpSeed.format(partialLow);
        }
        return hasTableXpSeed ? PlayerSeed.formatXpSeed(tableXpSeed) : "-";
    }

    /**
     * The full XP seed behind the low half a server's table synced, when it can be told without
     * rolling the table: it is the one already known, or (seed locked) the one the enchantment
     * that made it was bound to produce. Null otherwise.
     */
    public synchronized Integer knownFullXpSeed(int low) {
        if (hasTableXpSeed && !tableXpSeedPartial && PartialXpSeed.lowBits(tableXpSeed) == low) {
            return tableXpSeed;
        }
        if (isLocked() && source != Source.DIRECT) {
            // Drops made since this seed appeared came after the enchantment; step back over them.
            int late = tableXpSeedPartial && partialLow == low ? itemsDropped - seedChangeDrops : 0;
            long before = PlayerSeed.advance(playerSeed, -late * PlayerSeed.STEPS_PER_ITEM_DROP);
            int next = PlayerSeed.xpSeedOf(PlayerSeed.next(before));
            if (PartialXpSeed.lowBits(next) == low) {
                return next;
            }
        }
        return null;
    }

    /**
     * The table is on an XP seed of which only the low half is known, and {@code candidates}
     * full values still fit what it shows ({@code set} lists them once narrowed, else null).
     */
    public synchronized void notePartialXpSeed(int low, int candidates, int[] set) {
        if (source == Source.DIRECT) {
            return;
        }
        boolean newSeed = !(tableXpSeedPartial && partialLow == low);
        if (!newSeed && partialCount == candidates) {
            return;
        }
        if (newSeed) {
            seedAppeared();
        }
        tableXpSeedPartial = true;
        partialLow = low;
        partialCount = candidates;
        partialSet = set != null && candidates > 0 && candidates < PartialXpSeed.ALL ? set.clone() : null;
        String seed = PartialXpSeed.format(low);
        if (candidates == 0) {
            statusMessage = "No XP seed ending " + seed.substring(4) + " fits what this table shows. "
                    + "Another mod may change enchanting here.";
        } else if (candidates >= PartialXpSeed.ALL) {
            statusMessage = "On a server the table only shares half the XP seed (" + seed
                    + "). Put an enchantable item in the table to work out the rest.";
        } else {
            statusMessage = candidates + " XP seeds fit this table. Enchant something to lock the seed, "
                    + "or put a different item in to narrow it down.";
        }
        if (partialSet != null) {
            seedKnown();
        }
    }

    /**
     * The table shows something the XP seed we took for it cannot produce: it was remembered
     * wrongly or predicted from a wrong lock. Forget it, keeping only its low half (which the
     * table does sync), so it is worked out again from the table. Not a new XP seed: nothing
     * was enchanted.
     */
    public synchronized void rejectTableXpSeed() {
        if (source == Source.DIRECT || !hasTableXpSeed || tableXpSeedPartial) {
            return;
        }
        tableXpSeedPartial = true;
        partialLow = PartialXpSeed.lowBits(tableXpSeed);
        partialCount = -1;
        partialSet = null;
        if (status == Status.LOCKED) {
            status = Status.UNKNOWN;
            source = Source.NONE;
            playerSeed = PlayerSeed.UNKNOWN;
            pendingSet = null;
            statusMessage = "The table does not match the locked seed. Enchant twice to lock it again.";
        } else {
            statusMessage = "The remembered XP seed does not fit this table; working it out again.";
        }
    }

    /** The exact stored XP seed, read out of the integrated server. */
    public synchronized void setDirectXpSeed(int xpSeed) {
        this.hasTableXpSeed = true;
        this.tableXpSeed = xpSeed;
    }

    public synchronized int getItemsDropped() {
        return itemsDropped;
    }

    public synchronized int getDriftSteps() {
        return driftSteps;
    }

    public SeedCracker getCracker() {
        return cracker;
    }

    public synchronized boolean isTableOpen() {
        return tableOpen;
    }

    public synchronized int getTableBookshelves() {
        return tableBookshelves;
    }

    public synchronized int[] getTableLevels() {
        return new int[]{tableLevels[0], tableLevels[1], tableLevels[2]};
    }

    public synchronized String getTableItem() {
        return tableItem;
    }

    public synchronized TableSetup getTableSetup() {
        return tableSetup;
    }

    public synchronized String getTableProblem() {
        return tableProblem;
    }

    public synchronized void setTableSetup(TableSetup setup, String problem) {
        this.tableSetup = setup;
        this.tableProblem = problem;
    }

    // ------------------------------------------------------------ seed transitions

    /**
     * The exact state, read out of the integrated server. Always trusted over anything
     * we inferred.
     */
    public synchronized void setFromWorld(long seed) {
        this.playerSeed = seed;
        this.status = Status.LOCKED;
        this.source = Source.DIRECT;
        this.driftSteps = 0;
        this.statusMessage = "Seed read directly from your world.";
        this.staleXpSeed = false;
    }

    public synchronized void setManually(long seed) {
        if (seed == PlayerSeed.UNKNOWN) {
            return;
        }
        this.playerSeed = seed;
        this.status = Status.LOCKED;
        this.source = Source.MANUAL;
        this.driftSteps = 0;
        this.statusMessage = "Seed set by hand.";
        this.staleXpSeed = false;
    }

    /** Called when the brute-force cracker narrows down to a single XP seed. */
    public synchronized void setCrackedXpSeed(int xpSeed) {
        this.pendingSet = new int[]{xpSeed};
        this.pendingDropBaseline = itemsDropped;
        this.pendingIsCurrent = true;
        this.status = Status.AWAITING_SECOND;
        this.source = Source.CRACKED;
        this.statusMessage = "XP seed cracked. Enchant once more to get the full player seed.";
        this.staleXpSeed = false;
    }

    /**
     * Feeds the XP seed the enchanting table is currently reporting, in full.
     *
     * <p>The XP seed only changes when the player enchants something, so a change means
     * exactly one {@code nextInt()} was consumed — plus whatever else touched the RNG,
     * which is what the drift search picks up. On a server the full value may only be worked
     * out some time after the table first showed its low half ({@link #notePartialXpSeed}).
     */
    public synchronized void observeXpSeed(int xpSeed) {
        boolean wasPartial = tableXpSeedPartial && partialLow == PartialXpSeed.lowBits(xpSeed);
        if (!wasPartial && hasTableXpSeed && !tableXpSeedPartial && xpSeed == tableXpSeed) {
            return; // nothing has happened
        }
        if (!wasPartial) {
            seedAppeared();
        }
        hasTableXpSeed = true;
        tableXpSeed = xpSeed;
        tableXpSeedPartial = false;
        partialSet = null;
        partialCount = -1;
        seedKnown();
    }

    /** What is known of the table's current XP seed: one value, a few, or null. */
    private int[] currentKnowledge() {
        if (tableXpSeedPartial) {
            return partialSet;
        }
        return hasTableXpSeed ? new int[]{tableXpSeed} : null;
    }

    /**
     * A new XP seed is on the table: the player just enchanted (or it is the first one seen).
     * The one it replaces becomes the pending seed to pair it with. Call before recording it.
     */
    private void seedAppeared() {
        boolean first = !seenTableSeed;
        int[] previous = currentStale ? null : currentKnowledge();
        int previousDrops = seedChangeDrops;
        seenTableSeed = true;
        seedChangeDrops = itemsDropped;
        if (source == Source.DIRECT) {
            return; // the world itself is the source of truth; nothing to infer
        }
        diag("XP seed changed (first " + first + ", stale " + staleXpSeed + ", drops so far " + itemsDropped
                + ", status " + status + "); previous: " + list(previous));
        if (first) {
            currentStale = staleXpSeed;
            currentFirst = true;
            return;
        }
        if (status != Status.LOCKED) {
            if (pendingIsCurrent) {
                pendingIsCurrent = false; // the cracked seed was the one just replaced: keep it
            } else {
                pendingSet = previous;
                pendingDropBaseline = previousDrops;
            }
        }
        // First enchantment on a new generator: the seed it replaced was only a baseline.
        staleXpSeed = false;
        currentStale = false;
        currentFirst = false;
    }

    /** Something more is known about the table's current XP seed: act on it. */
    private void seedKnown() {
        if (source == Source.DIRECT) {
            return;
        }
        int[] current = currentKnowledge();
        if (current == null) {
            return;
        }
        diag("XP seed known: " + list(current) + " (stale " + currentStale + ", first " + currentFirst
                + ", status " + status + ", drops since it appeared " + (itemsDropped - seedChangeDrops) + ")");
        if (currentStale) {
            statusMessage = "New session: this XP seed is from before you joined or respawned. "
                    + "Enchant twice to lock the new generator.";
            return;
        }
        if (status == Status.LOCKED) {
            if (!currentFirst) {
                resync(current);
            }
            // Otherwise the table reports the XP seed from the *last* enchantment, which is only
            // the current RNG state if nothing has been dropped since. Take it as a baseline and
            // wait for it to actually change before concluding anything.
            return;
        }
        status = Status.AWAITING_SECOND;
        if (pendingSet == null) {
            statusMessage = current.length == 1
                    ? "Captured XP seed " + PlayerSeed.formatXpSeed(current[0])
                    + ". Enchant again, or throw an item, to lock the seed."
                    : current.length + " XP seeds fit this table. Enchant something to lock the seed.";
            return;
        }
        if ((long) pendingSet.length * current.length > PAIR_LIMIT) {
            statusMessage = pendingSet.length + " x " + current.length + " XP seeds still fit. Put a different "
                    + "item in the table to narrow it down.";
            return;
        }
        // One step for the enchantment, four for every item dropped between the two.
        int dropsBetween = Math.max(0, seedChangeDrops - pendingDropBaseline);
        long[] solved = PlayerSeed.solveSets(pendingSet, current,
                PlayerSeed.STEPS_PER_ENCHANT + dropsBetween * PlayerSeed.STEPS_PER_ITEM_DROP);
        diag("Pairing " + list(pendingSet) + " -> " + list(current) + " with " + dropsBetween
                + " drops between: " + solved.length + " solution(s)");
        if (solved.length == 1) {
            lock(solved[0]);
            return;
        }
        if (solved.length > 1) {
            statusMessage = "More than one seed fits so far. Put a different item in the table to narrow it down.";
        } else if (pendingSet.length == 1 && current.length == 1) {
            // The two seeds are not consecutive, so something else used the RNG in between.
            statusMessage = "Those two XP seeds were not consecutive. Enchant again, "
                    + "and avoid taking damage in between.";
        } else {
            statusMessage = "No seed links the last two enchantments; something else used the RNG. Enchant again.";
        }
    }

    /** Locks onto {@code state}, the RNG right after the table's current XP seed was drawn. */
    private void lock(long state) {
        int late = itemsDropped - seedChangeDrops;
        playerSeed = PlayerSeed.advance(state, late * PlayerSeed.STEPS_PER_ITEM_DROP);
        status = Status.LOCKED;
        source = Source.TWO_SEEDS;
        driftSteps = 0;
        pendingSet = null;
        pendingIsCurrent = false;
        hasTableXpSeed = true;
        tableXpSeed = PlayerSeed.xpSeedOf(state);
        tableXpSeedPartial = false;
        partialSet = null;
        partialCount = -1;
        statusMessage = "Player seed locked from two XP seeds.";
        diag("Locked: player seed " + PlayerSeed.format(playerSeed) + ", table XP seed " + PlayerSeed.formatXpSeed(tableXpSeed));
    }

    /** The seed is locked and the table moved on to a new XP seed: find how many steps it took. */
    private void resync(int[] current) {
        if (current.length > RESYNC_SET_LIMIT) {
            return; // too many candidates to search safely; wait for a narrower view
        }
        int late = itemsDropped - seedChangeDrops;
        // The enchantment came before the drops made since the seed appeared.
        long before = PlayerSeed.advance(playerSeed, -late * PlayerSeed.STEPS_PER_ITEM_DROP);
        int[] sorted = current.clone();
        java.util.Arrays.sort(sorted);
        // Where the enchantment's step landed: normally right after the tracked state; later if
        // something else used the RNG; earlier if drops were counted that the server never made.
        long expected = PlayerSeed.next(before);
        Long made = null;
        int offset = 0;
        long seed = expected;
        for (int steps = 0; steps <= RESYNC_WINDOW; steps++, seed = PlayerSeed.next(seed)) {
            if (java.util.Arrays.binarySearch(sorted, PlayerSeed.xpSeedOf(seed)) >= 0) {
                made = seed;
                offset = steps;
                break;
            }
        }
        if (made == null) {
            seed = expected;
            for (int back = 1; back <= RESYNC_BACK_WINDOW; back++) {
                seed = PlayerSeed.previous(seed);
                if (java.util.Arrays.binarySearch(sorted, PlayerSeed.xpSeedOf(seed)) >= 0) {
                    made = seed;
                    offset = -back;
                    break;
                }
            }
        }
        diag("Re-sync " + list(current) + ": " + (made == null ? "not found" : offset + " step(s) off"));
        if (made != null) {
            resyncListener.accept(offset);
        }
        if (made != null) {
            playerSeed = PlayerSeed.advance(made, late * PlayerSeed.STEPS_PER_ITEM_DROP);
            driftSteps += Math.abs(offset);
            hasTableXpSeed = true;
            tableXpSeed = PlayerSeed.xpSeedOf(made);
            tableXpSeedPartial = false;
            partialSet = null;
            partialCount = -1;
            statusMessage = offset == 0 ? "In sync."
                    : offset > 0 ? "Re-synced: " + offset + " unexpected RNG step" + (offset == 1 ? "" : "s") + " caught up."
                    : "Re-synced: " + (-offset) + " RNG step" + (offset == -1 ? "" : "s") + " counted that never happened.";
            return;
        }
        if (current.length > 1) {
            return; // not found among these; an exact view will decide
        }
        // Too far gone to recover; start again from this seed.
        status = Status.AWAITING_SECOND;
        source = Source.NONE;
        playerSeed = PlayerSeed.UNKNOWN;
        pendingSet = null;
        statusMessage = "Lost track of the RNG. Enchant once more, or throw an item, to re-lock.";
    }

    /**
     * Tries to lock the seed from a thrown item's velocity, using the one XP seed already
     * captured. This is the whole point of the velocity technique: the top 32 bits come from a
     * single enchantment, and the throw's four {@code nextFloat} calls pick the low 16 out of
     * {@code 2^16} candidates, so no second enchantment is spent.
     *
     * @return true when the seed became locked.
     */
    public synchronized boolean observeThrowVelocity(VelocityCracker.Velocity velocity, float yaw, float pitch) {
        if (source == Source.DIRECT || status == Status.LOCKED) {
            return false; // own world is already exact; a locked seed needs nothing from a throw
        }
        // The XP seed the throw follows: the table's current one (or a cracked one).
        int[] xpSeeds = pendingIsCurrent ? pendingSet : currentStale ? null : currentKnowledge();
        int baseline = pendingIsCurrent ? pendingDropBaseline : seedChangeDrops;
        if (xpSeeds == null || xpSeeds.length > 16) {
            return false;
        }
        // The step offset is how many of the player's own RNG steps were spent between the enchant
        // and this throw's first nextFloat: four per item dropped since. That count comes from the
        // drop counter (fed by the toss/drop-key path), not from how many entities appeared near
        // us, so a stray item on the ground cannot corrupt it. This drop may or may not be counted
        // yet when its velocity is read, so both offsets are tried; the item picks the right one.
        int droppedSince = Math.max(0, itemsDropped - baseline);
        java.util.LinkedHashSet<Long> candidates = new java.util.LinkedHashSet<>();
        int[] offsets = {Math.max(0, droppedSince - 1) * PlayerSeed.STEPS_PER_ITEM_DROP,
                droppedSince * PlayerSeed.STEPS_PER_ITEM_DROP};
        for (int xpSeed : xpSeeds) {
            for (int steps : offsets) {
                candidates.addAll(VelocityCracker.solveFromXpSeed(xpSeed, steps, velocity, yaw, pitch));
            }
        }
        if (candidates.size() == 1) {
            playerSeed = candidates.iterator().next();
            status = Status.LOCKED;
            source = Source.VELOCITY;
            pendingSet = null;
            pendingIsCurrent = false;
            driftSteps = 0;
            statusMessage = "Locked from a thrown item's velocity — no second enchantment needed.";
            return true;
        }
        if (candidates.isEmpty()) {
            statusMessage = "That throw did not match. Stand still, look roughly level, and throw one item again.";
        } else {
            statusMessage = candidates.size() + " states fit that throw; throw one more item to settle it.";
        }
        return false;
    }

    /**
     * One dropped item stack = four {@code nextFloat()} calls on the player's RNG.
     *
     * <p>Skipped when the seed is being read straight out of the world, since that is
     * re-read every tick and already reflects the drop.
     */
    public synchronized void onItemDropped() {
        itemsDropped++;
        if (status == Status.LOCKED && source != Source.DIRECT) {
            playerSeed = PlayerSeed.advance(playerSeed, PlayerSeed.STEPS_PER_ITEM_DROP);
        }
    }

    /**
     * Manual nudge for the rare case the automatic drop counter missed something, or
     * counted something twice. Negative steps run the LCG backwards.
     */
    public synchronized void nudge(int steps) {
        if (status != Status.LOCKED || steps == 0) {
            return;
        }
        playerSeed = PlayerSeed.advance(playerSeed, steps);
        statusMessage = (steps > 0 ? "Advanced " : "Rewound ") + Math.abs(steps)
                + " RNG step" + (Math.abs(steps) == 1 ? "" : "s") + ".";
    }

    public synchronized void resetSeed() {
        playerSeed = PlayerSeed.UNKNOWN;
        status = Status.UNKNOWN;
        source = Source.NONE;
        hasTableXpSeed = false;
        tableXpSeedPartial = false;
        partialCount = -1;
        partialSet = null;
        seenTableSeed = false;
        currentStale = false;
        currentFirst = false;
        pendingSet = null;
        pendingIsCurrent = false;
        staleXpSeed = false;
        lastSeenXpSeed = null;
        itemsDropped = 0;
        seedChangeDrops = 0;
        pendingDropBaseline = 0;
        driftSteps = 0;
        plan = null;
        planOptions = new ArrayList<>();
        statusMessage = "Reset. Open an enchanting table to start again.";
    }

    /**
     * The player entity was replaced (joined a world, respawned, left the End), which gives
     * it a brand new generator. Whatever we knew about the old one is now worthless, and the
     * XP seed carried over from before is only a baseline.
     */
    public synchronized void onNewPlayerEntity(String why) {
        if (source == Source.DIRECT) {
            return; // re-read from the world next tick anyway
        }
        boolean hadSeed = status != Status.UNKNOWN;
        playerSeed = PlayerSeed.UNKNOWN;
        status = Status.UNKNOWN;
        source = Source.NONE;
        hasTableXpSeed = false;
        tableXpSeedPartial = false;
        partialCount = -1;
        partialSet = null;
        seenTableSeed = false;
        currentStale = false;
        currentFirst = false;
        pendingSet = null;
        pendingIsCurrent = false;
        pendingDropBaseline = itemsDropped;
        staleXpSeed = true;
        lastSeenXpSeed = null;
        driftSteps = 0;
        plan = null;
        planOptions = new ArrayList<>();
        statusMessage = hadSeed
                ? why + ": Minecraft rolled a new random generator for you. Enchant twice to lock it again."
                : why + ". Enchant twice to lock your seed.";
    }

    // ------------------------------------------------------------ per-world memory

    /** Everything worth keeping for one world or server, as plain strings. */
    public synchronized Map<String, String> exportProfile() {
        Map<String, String> out = new LinkedHashMap<>();
        if (hasTableXpSeed && !tableXpSeedPartial) {
            out.put("xpSeed", PlayerSeed.formatXpSeed(tableXpSeed));
        }
        out.put("item", selectedItem);
        out.put("maxShelves", String.valueOf(maxBookshelves));
        StringBuilder wishes = new StringBuilder();
        for (Map.Entry<String, Integer> entry : wishlist.entrySet()) {
            if (wishes.length() > 0) {
                wishes.append(',');
            }
            wishes.append(entry.getKey()).append('=').append(entry.getValue());
        }
        out.put("wishlist", wishes.toString());
        return out;
    }

    /**
     * Restores what {@link #exportProfile} saved. The 48-bit seed is deliberately not part of
     * it: it cannot survive a relog, because the server hands the new player entity a new
     * generator. The XP seed does survive (it is saved with the player), so it comes back as
     * the baseline to watch.
     */
    public synchronized void importProfile(Map<String, String> in) {
        String item = in.get("item");
        if (item != null && !item.isEmpty()) {
            selectedItem = item;
        }
        try {
            if (in.containsKey("maxShelves")) {
                maxBookshelves = Math.max(0, Math.min(15, Integer.parseInt(in.get("maxShelves"))));
            }
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        wishlist.clear();
        String wishes = in.get("wishlist");
        if (wishes != null && !wishes.isEmpty()) {
            for (String part : wishes.split(",")) {
                int eq = part.lastIndexOf('=');
                if (eq <= 0) {
                    continue;
                }
                try {
                    wishlist.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
                } catch (NumberFormatException ignored) {
                    // skip a damaged entry
                }
            }
        }
        Integer xp = PlayerSeed.parseXpSeed(in.get("xpSeed"));
        // 1.2.4 and older saved what a server's table synced: only the low 16 bits, sign-extended.
        if (xp != null && xp == (short) (int) xp) {
            xp = null;
        }
        if (xp != null && source != Source.DIRECT) {
            hasTableXpSeed = true;
            tableXpSeedPartial = false;
            tableXpSeed = xp;
            // Saved with the player, so it survives the relog, but the generator did not: a baseline.
            seenTableSeed = true;
            currentStale = staleXpSeed;
            currentFirst = true;
        }
        plan = null;
        planOptions = new ArrayList<>();
    }

    // ------------------------------------------------------------------ table info

    public synchronized void setTableState(boolean open, int bookshelves, int[] levels, String item) {
        this.tableOpen = open;
        this.tableBookshelves = bookshelves;
        if (levels != null) {
            System.arraycopy(levels, 0, tableLevels, 0, 3);
        }
        this.tableItem = item;
    }

    public synchronized void setTableClosed() {
        this.tableOpen = false;
    }

    // ------------------------------------------------------------ calculator state

    public synchronized String getSelectedItem() {
        return selectedItem;
    }

    public synchronized void setSelectedItem(String item) {
        if (!java.util.Objects.equals(this.selectedItem, item)) {
            this.selectedItem = item;
            pruneWishlist();
            this.plan = null;
        }
    }

    public synchronized int getMaxBookshelves() {
        return maxBookshelves;
    }

    public synchronized void setMaxBookshelves(int value) {
        this.maxBookshelves = Math.max(0, Math.min(15, value));
    }

    public synchronized int getPlayerLevel() {
        return playerLevel;
    }

    public synchronized void setPlayerLevel(int value) {
        this.playerLevel = Math.max(0, value);
    }

    /** 0 = don't care, -1 = must not appear, >0 = must appear at this level or better. */
    public synchronized int getWish(String enchantment) {
        Integer value = wishlist.get(enchantment);
        return value == null ? 0 : value;
    }

    public synchronized void setWish(String enchantment, int value) {
        if (value == 0) {
            wishlist.remove(enchantment);
        } else {
            wishlist.put(enchantment, value);
        }
        plan = null;
    }

    public synchronized void clearWishlist() {
        wishlist.clear();
        plan = null;
    }

    public synchronized boolean hasWishes() {
        return !wishlist.isEmpty();
    }

    public synchronized List<EnchantmentInstance> getWanted() {
        List<EnchantmentInstance> wanted = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wishlist.entrySet()) {
            if (entry.getValue() > 0) {
                wanted.add(new EnchantmentInstance(entry.getKey(), entry.getValue()));
            }
        }
        return wanted;
    }

    public synchronized List<String> getUnwanted() {
        List<String> unwanted = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wishlist.entrySet()) {
            if (entry.getValue() < 0) {
                unwanted.add(entry.getKey());
            }
        }
        return unwanted;
    }

    /** Drops wishes that the newly selected item can never receive. */
    private void pruneWishlist() {
        wishlist.keySet().removeIf(ench -> CrackEnchantments.getMaxLevelInTable(ench, selectedItem) == 0);
    }

    public synchronized EnchantCalculator.Result getPlan() {
        return plan;
    }

    public synchronized void setPlan(EnchantCalculator.Result plan) {
        this.plan = plan;
        this.planOptions = new ArrayList<>();
        if (plan != null) {
            this.planOptions.add(plan);
        }
        this.planStartDrops = itemsDropped;
        this.enchantsSincePlan = 0;
    }

    /** Several ways to the same goal; the first becomes the active plan. */
    public synchronized void setPlanOptions(List<EnchantCalculator.Result> options) {
        this.planOptions = new ArrayList<>(options);
        this.plan = options.isEmpty() ? EnchantCalculator.IMPOSSIBLE : options.get(0);
        this.planStartDrops = itemsDropped;
        this.enchantsSincePlan = 0;
    }

    /** The wishes the active plan was made for, so it can be made again if it stops fitting. */
    private List<EnchantmentInstance> planWanted = new ArrayList<>();
    private List<String> planUnwanted = new ArrayList<>();

    public synchronized void setPlanGoal(List<EnchantmentInstance> wanted, List<String> unwanted) {
        this.planWanted = wanted == null ? new ArrayList<>() : new ArrayList<>(wanted);
        this.planUnwanted = unwanted == null ? new ArrayList<>() : new ArrayList<>(unwanted);
    }

    public synchronized List<EnchantmentInstance> getPlanWanted() {
        return new ArrayList<>(planWanted);
    }

    public synchronized List<String> getPlanUnwanted() {
        return new ArrayList<>(planUnwanted);
    }

    public synchronized List<EnchantCalculator.Result> getPlanOptions() {
        return new ArrayList<>(planOptions);
    }

    /** Switches between the options without resetting the drop counter. */
    public synchronized void choosePlanOption(int index) {
        if (index >= 0 && index < planOptions.size()) {
            this.plan = planOptions.get(index);
        }
    }

    public synchronized int getPlanOptionIndex() {
        return plan == null ? -1 : planOptions.indexOf(plan);
    }

    /** Items still to drop for the active plan, or 0. */
    public synchronized int getDropsRemaining() {
        if (plan == null || !plan.needsDummy()) {
            return 0;
        }
        return Math.max(0, plan.itemsToThrow - getDropsSincePlan());
    }

    /**
     * Watches the XP seed for enchantments, which is how the dummy and the final enchantment
     * are ticked off without the player saying so. Every enchantment draws a new XP seed, so
     * a change is exactly one enchantment.
     *
     * <p>One source only, or a lagging copy would look like a second change: in your own world
     * the value read straight from the server, elsewhere the table screen's synced copy.
     */
    public synchronized void noteXpSeed(int xpSeed, boolean fromWorld) {
        if (fromWorld != (source == Source.DIRECT)) {
            return;
        }
        if (lastSeenXpSeed != null && lastSeenXpSeed != xpSeed) {
            enchantsSincePlan++;
        }
        lastSeenXpSeed = xpSeed;
    }

    public synchronized int getEnchantsSincePlan() {
        return enchantsSincePlan;
    }

    /** How far through the active plan the player is. */
    public enum PlanStage {
        NONE,
        /** Still dropping junk. */
        DROPPING,
        /** Dropped more than the plan asked for: it no longer applies. */
        OVERSHOT,
        /** Drops done; waiting for the dummy enchantment. */
        DUMMY,
        /** The dummy is done, but the table's new XP seed is not confirmed yet. */
        CHECKING,
        /** Ready for the real enchantment, and the table is on the planned XP seed. */
        FINAL,
        /** The table is not on the planned XP seed: something went off course. */
        OFF_COURSE,
        /** The real enchantment happened. */
        DONE
    }

    public synchronized PlanStage getPlanStage() {
        if (plan == null || plan.outcome == EnchantCalculator.Outcome.IMPOSSIBLE) {
            return PlanStage.NONE;
        }
        int enchantsNeeded = plan.needsDummy() ? 2 : 1;
        if (enchantsSincePlan >= enchantsNeeded) {
            return PlanStage.DONE;
        }
        if (plan.needsDummy()) {
            int dropped = getDropsSincePlan();
            if (enchantsSincePlan == 0) {
                if (dropped > plan.itemsToThrow) {
                    return PlanStage.OVERSHOT;
                }
                return dropped < plan.itemsToThrow ? PlanStage.DROPPING : PlanStage.DUMMY;
            }
        }
        // Next is the real enchantment: check the table is where the plan expects.
        Integer now = getEffectiveXpSeed();
        if (now == null) {
            return PlanStage.CHECKING; // never say "on the planned seed" without knowing it
        }
        return now == plan.xpSeed ? PlanStage.FINAL : PlanStage.OFF_COURSE;
    }

    /** How many items have been dropped since the current plan was worked out. */
    public synchronized int getDropsSincePlan() {
        return Math.max(0, itemsDropped - planStartDrops);
    }

    /**
     * Marks the plan as carried out.
     *
     * <p>The seed is deliberately not advanced here: every drop was already counted as it
     * happened ({@link #onItemDropped}), and both enchantments were seen by the table watcher
     * ({@link #observeXpSeed}), which moved the seed on. Advancing again would put the tracked
     * seed ahead of the real one.
     */
    public synchronized void confirmPlan() {
        if (plan == null) {
            return;
        }
        statusMessage = "Plan done. The seed was tracked through every step.";
        plan = null;
        planOptions = new ArrayList<>();
    }
}
