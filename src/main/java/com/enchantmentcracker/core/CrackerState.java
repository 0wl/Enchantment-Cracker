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
    private boolean hasPendingXpSeed;
    private int pendingXpSeed;
    /** Items dropped when the pending XP seed was captured, so the velocity solver knows its offset. */
    private int pendingDropBaseline;

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
        if (hasTableXpSeed) {
            return tableXpSeed;
        }
        return isLocked() ? Integer.valueOf(PlayerSeed.xpSeedOf(playerSeed)) : null;
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
        this.hasPendingXpSeed = true;
        this.pendingXpSeed = xpSeed;
        this.pendingDropBaseline = itemsDropped;
        this.status = Status.AWAITING_SECOND;
        this.source = Source.CRACKED;
        this.statusMessage = "XP seed cracked. Enchant once more to get the full player seed.";
        this.staleXpSeed = false;
    }

    /**
     * Feeds the XP seed the enchanting table is currently reporting.
     *
     * <p>The XP seed only changes when the player enchants something, so a change means
     * exactly one {@code nextInt()} was consumed — plus whatever else touched the RNG,
     * which is what the drift search picks up.
     */
    public synchronized void observeXpSeed(int xpSeed) {
        if (hasTableXpSeed && xpSeed == tableXpSeed) {
            return; // nothing has happened
        }

        boolean firstEver = !hasTableXpSeed;
        hasTableXpSeed = true;
        int previous = tableXpSeed;
        tableXpSeed = xpSeed;

        if (source == Source.DIRECT) {
            return; // the world itself is the source of truth; nothing to infer
        }

        if (staleXpSeed) {
            if (firstEver) {
                statusMessage = "New session: this XP seed is from before you joined or respawned. "
                        + "Enchant twice to lock the new generator.";
                return;
            }
            // First enchantment on the new generator: this is XP seed 1.
            staleXpSeed = false;
            hasPendingXpSeed = true;
            pendingXpSeed = xpSeed;
            pendingDropBaseline = itemsDropped;
            status = Status.AWAITING_SECOND;
            statusMessage = "Captured XP seed " + PlayerSeed.formatXpSeed(xpSeed)
                    + ". Enchant again, or throw an item, to lock the seed.";
            return;
        }

        if (status == Status.LOCKED) {
            if (firstEver) {
                // The table reports the XP seed from the *last* enchantment, which is only
                // the current RNG state if nothing has been dropped since. Take it as a
                // baseline and wait for it to actually change before concluding anything.
                return;
            }
            // The XP seed changed, so an enchantment happened: at least one step. Find how many.
            int extra = PlayerSeed.stepsUntilXpSeed(PlayerSeed.next(playerSeed), xpSeed, RESYNC_WINDOW);
            if (extra >= 0) {
                int steps = extra + 1;
                playerSeed = PlayerSeed.advance(playerSeed, steps);
                driftSteps += extra;
                statusMessage = extra == 0
                        ? "In sync."
                        : "Re-synced: " + extra + " unexpected RNG step" + (extra == 1 ? "" : "s") + " caught up.";
                return;
            }
            // Too far gone to recover; start again from this seed.
            status = Status.AWAITING_SECOND;
            source = Source.NONE;
            playerSeed = PlayerSeed.UNKNOWN;
            hasPendingXpSeed = true;
            pendingXpSeed = xpSeed;
            pendingDropBaseline = itemsDropped;
            statusMessage = "Lost track of the RNG. Enchant once more, or throw an item, to re-lock.";
            return;
        }

        if (hasPendingXpSeed && !firstEver) {
            long solved = PlayerSeed.solve(pendingXpSeed, xpSeed);
            if (solved != PlayerSeed.UNKNOWN) {
                playerSeed = solved;
                status = Status.LOCKED;
                source = Source.TWO_SEEDS;
                driftSteps = 0;
                hasPendingXpSeed = false;
                statusMessage = "Player seed locked from two XP seeds.";
                return;
            }
            // The two seeds are not consecutive, so something else used the RNG in between.
            pendingXpSeed = xpSeed;
            pendingDropBaseline = itemsDropped;
            statusMessage = "Those two XP seeds were not consecutive. Enchant again, "
                    + "and avoid dropping items or taking damage in between.";
            return;
        }

        hasPendingXpSeed = true;
        pendingXpSeed = xpSeed;
        pendingDropBaseline = itemsDropped;
        status = Status.AWAITING_SECOND;
        statusMessage = previous == xpSeed
                ? statusMessage
                : "Captured XP seed " + PlayerSeed.formatXpSeed(xpSeed)
                        + ". Enchant again, or throw an item, to lock the seed.";
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
        if (source == Source.DIRECT || status == Status.LOCKED || !hasPendingXpSeed) {
            return false; // own world is already exact; a locked seed needs nothing from a throw
        }
        // The step offset is how many of the player's own RNG steps were spent between the enchant
        // and this throw's first nextFloat: four per item dropped since. That count comes from the
        // drop counter (fed by the toss/drop-key path), not from how many entities appeared near
        // us, so a stray item on the ground cannot corrupt it. This drop may or may not be counted
        // yet when its velocity is read, so both offsets are tried; the item picks the right one.
        int droppedSince = Math.max(0, itemsDropped - pendingDropBaseline);
        java.util.LinkedHashSet<Long> candidates = new java.util.LinkedHashSet<>();
        int[] offsets = {Math.max(0, droppedSince - 1) * PlayerSeed.STEPS_PER_ITEM_DROP,
                droppedSince * PlayerSeed.STEPS_PER_ITEM_DROP};
        for (int steps : offsets) {
            candidates.addAll(VelocityCracker.solveFromXpSeed(pendingXpSeed, steps, velocity, yaw, pitch));
        }
        if (candidates.size() == 1) {
            playerSeed = candidates.iterator().next();
            status = Status.LOCKED;
            source = Source.VELOCITY;
            hasPendingXpSeed = false;
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
        hasPendingXpSeed = false;
        pendingDropBaseline = itemsDropped;
        staleXpSeed = false;
        lastSeenXpSeed = null;
        itemsDropped = 0;
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
        hasPendingXpSeed = false;
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
        if (hasTableXpSeed) {
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
        if (xp != null && source != Source.DIRECT) {
            hasTableXpSeed = true;
            tableXpSeed = xp;
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
        if (now != null && now != plan.xpSeed) {
            return PlanStage.OFF_COURSE;
        }
        return PlanStage.FINAL;
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
