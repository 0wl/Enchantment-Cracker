package com.enchantmentcracker.core;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Works out how to steer the player's RNG to a chosen set of enchantments.
 *
 * <p>Ported from the manipulation logic in Earthcomputer/Hexicube's standalone
 * EnchantmentCracker (MIT).
 *
 * <p>Two knobs move the RNG forward: dropping an item (4 steps) and enchanting
 * something (1 step). So the search walks "throw N items, then burn one dummy
 * enchantment" for increasing N, and at each stop asks whether any slot, at any
 * allowed table setup, gives what you asked for.
 *
 * <p>A "table setup" is a vanilla table at some bookshelf count, or a table another mod
 * has replaced, as it stands. See {@link TableSetup}.
 */
public final class EnchantCalculator {

    /** 32 stacks of throwaway items is as far as the original searched. */
    public static final int DEFAULT_MAX_THROWS = 64 * 32;

    private EnchantCalculator() {
    }

    public enum Outcome {
        /** Found, and reachable without burning a dummy enchantment — enchant right now. */
        READY,
        /** Found: drop {@link Result#itemsToThrow} items, dummy-enchant, then enchant. */
        NEEDS_SETUP,
        /** No combination of throws, bookshelves and slots produces this. */
        IMPOSSIBLE
    }

    public static final class Result {
        public final Outcome outcome;
        /** Items to drop before the dummy enchantment; -1 when no dummy is needed. */
        public final int itemsToThrow;
        /** The table setup the final enchantment uses. */
        public final TableSetup setup;
        /** Its bookshelf count, or -1 for a setup that is not shelf-based. */
        public final int bookshelves;
        /** 0, 1 or 2 — the GUI shows this 1-based. */
        public final int slot;
        /** Level requirement shown next to that slot. */
        public final int levelRequirement;
        /** Levels actually spent on the final enchantment ({@code slot + 1}). */
        public final int levelCost;
        public final List<EnchantmentInstance> enchantments;
        /** The item the plan enchants, or null for the IMPOSSIBLE marker. */
        public final String item;
        /**
         * The XP seed the final enchantment rolls with. After the drops and the dummy, the
         * table should show exactly this; if it does not, the plan went off course.
         */
        public final int xpSeed;

        Result(Outcome outcome, int itemsToThrow, TableSetup setup, int slot, int levelRequirement,
               List<EnchantmentInstance> enchantments) {
            this(outcome, itemsToThrow, setup, slot, levelRequirement, enchantments, null, 0);
        }

        Result(Outcome outcome, int itemsToThrow, TableSetup setup, int slot, int levelRequirement,
               List<EnchantmentInstance> enchantments, String item, int xpSeed) {
            this.item = item;
            this.xpSeed = xpSeed;
            this.outcome = outcome;
            this.itemsToThrow = itemsToThrow;
            this.setup = setup;
            this.bookshelves = setup == null ? -1 : setup.shelves;
            this.slot = slot;
            this.levelRequirement = levelRequirement;
            this.levelCost = slot + 1;
            this.enchantments = enchantments;
        }

        public boolean needsDummy() {
            return outcome == Outcome.NEEDS_SETUP;
        }

        /** Total levels this plan costs, including the dummy enchantment. */
        public int totalLevelCost() {
            return levelCost + (needsDummy() ? 1 : 0);
        }

        /** "3 stacks + 7" style description of the drop count. */
        public String describeThrows() {
            if (!needsDummy()) {
                return "none";
            }
            if (itemsToThrow == 0) {
                return "0";
            }
            if (itemsToThrow > 63) {
                return (itemsToThrow / 64) + " stack" + (itemsToThrow / 64 == 1 ? "" : "s")
                        + (itemsToThrow % 64 == 0 ? "" : " + " + (itemsToThrow % 64));
            }
            return String.valueOf(itemsToThrow);
        }
    }

    public static final Result IMPOSSIBLE =
            new Result(Outcome.IMPOSSIBLE, -2, null, 0, 0, Collections.emptyList());

    /** Progress callback so the GUI can show a bar without blocking. */
    public interface Progress {
        /** @return false to abort the search. */
        boolean update(int throwsTried, int maxThrows);
    }

    /** Everything a search needs. */
    public static final class Request {
        /** The player's 48-bit RNG state right now. */
        public long playerSeed = PlayerSeed.UNKNOWN;
        /**
         * The XP seed the player currently has stored, which is what an enchantment done
         * <em>right now</em> would use. This is a snapshot from the last enchantment, so after
         * dropping items it is no longer {@code xpSeedOf(playerSeed)}. Null falls back to the state.
         */
        public Integer currentXpSeed;
        /** Item id to enchant. */
        public String item;
        /** Table setups the player is willing to use, tried in this order at every step. */
        public List<TableSetup> setups = new ArrayList<>();
        public int playerLevel = 30;
        /** Enchantments that must appear, at least at the given level. */
        public List<EnchantmentInstance> wanted = Collections.emptyList();
        /** Enchantments that must not appear at any level. */
        public List<String> unwanted = Collections.emptyList();
        public int maxThrows = DEFAULT_MAX_THROWS;
        public int maxOptions = 1;
        public Progress progress;
    }

    /**
     * Vanilla setups for shelf counts 0..max, the preferred count first so a plan that needs
     * no building wins a tie.
     */
    public static List<TableSetup> vanillaSetups(int maxBookshelves, int preferredShelves) {
        List<TableSetup> setups = new ArrayList<>();
        for (int shelves : shelfOrder(Math.max(0, Math.min(15, maxBookshelves)), preferredShelves)) {
            setups.add(Models.vanillaTable(shelves));
        }
        return setups;
    }

    /** The original tool's search: a vanilla table at any shelf count up to the limit. */
    public static Result calculate(long playerSeed, Integer currentXpSeed, String item,
                                   int maxBookshelves, int playerLevel,
                                   List<EnchantmentInstance> wanted, List<String> unwanted,
                                   int maxThrows, Progress progress) {
        Request request = new Request();
        request.playerSeed = playerSeed;
        request.currentXpSeed = currentXpSeed;
        request.item = item;
        request.setups = vanillaSetups(maxBookshelves, -1);
        request.playerLevel = playerLevel;
        request.wanted = wanted;
        request.unwanted = unwanted;
        request.maxThrows = maxThrows;
        request.progress = progress;
        List<Result> options = calculateOptions(request);
        return options.isEmpty() ? IMPOSSIBLE : options.get(0);
    }

    /**
     * Searches for up to {@code maxOptions} different ways, cheapest first (fewest dropped
     * items, then setup order).
     *
     * @return the options found; empty when there is no way.
     */
    public static List<Result> calculateOptions(Request request) {
        List<Result> results = new ArrayList<>();
        if (request.playerSeed == PlayerSeed.UNKNOWN || request.item == null
                || CrackItems.getEnchantability(request.item) <= 0 || request.setups.isEmpty()) {
            return results;
        }
        String item = request.item;
        long seed = request.playerSeed;

        for (int throwCount = -1; throwCount <= request.maxThrows; throwCount++) {
            if (request.progress != null && (throwCount & 63) == 0
                    && !request.progress.update(Math.max(throwCount, 0), request.maxThrows)) {
                return results;
            }

            // throwCount == -1 means "enchant straight away", so the table is still on the
            // XP seed the player already has stored. Otherwise a dummy enchantment burns one
            // step first and the table moves on to the value that produces.
            int xpSeed;
            if (throwCount == -1) {
                xpSeed = request.currentXpSeed != null ? request.currentXpSeed : PlayerSeed.xpSeedOf(seed);
            } else {
                xpSeed = PlayerSeed.xpSeedOf(PlayerSeed.next(seed));
            }

            for (TableSetup setup : request.setups) {
                int[] levels = setup.levels(xpSeed, item);
                for (int slot = 0; slot < 3; slot++) {
                    if (levels[slot] <= 0) {
                        continue;
                    }
                    // A dummy enchantment costs one level, so we need one more in hand.
                    int levelsNeeded = levels[slot] + (throwCount == -1 ? 0 : 1);
                    if (request.playerLevel < levelsNeeded) {
                        continue;
                    }
                    List<EnchantmentInstance> enchantments = setup.enchantments(xpSeed, item, slot, levels[slot]);
                    if (enchantments.isEmpty() || !matchesWishlist(enchantments, request.wanted, request.unwanted)) {
                        continue;
                    }

                    Outcome outcome = throwCount == -1 ? Outcome.READY : Outcome.NEEDS_SETUP;
                    results.add(new Result(outcome, throwCount, setup, slot, levels[slot],
                            new ArrayList<>(enchantments), item, xpSeed));
                    if (results.size() >= request.maxOptions) {
                        return results;
                    }
                    break; // one option per setup is plenty; try the next one
                }
            }

            // Simulate throwing one more item before the next attempt.
            if (throwCount != -1) {
                seed = PlayerSeed.advance(seed, PlayerSeed.STEPS_PER_ITEM_DROP);
            }
        }

        return results;
    }

    /** 0..max, with the preferred count moved to the front. */
    static int[] shelfOrder(int maxBookshelves, int preferred) {
        int[] order = new int[maxBookshelves + 1];
        int index = 0;
        if (preferred >= 0 && preferred <= maxBookshelves) {
            order[index++] = preferred;
        }
        for (int shelves = 0; shelves <= maxBookshelves; shelves++) {
            if (shelves != preferred) {
                order[index++] = shelves;
            }
        }
        return order;
    }

    private static boolean matchesWishlist(List<EnchantmentInstance> enchantments,
                                           List<EnchantmentInstance> wanted, List<String> unwanted) {
        for (EnchantmentInstance want : wanted) {
            boolean found = false;
            for (EnchantmentInstance got : enchantments) {
                if (!want.enchantment.equals(got.enchantment)) {
                    continue;
                }
                if (want.level > got.level) {
                    return false; // present, but not high enough
                }
                found = true;
                break;
            }
            if (!found) {
                return false;
            }
        }
        for (String avoid : unwanted) {
            for (EnchantmentInstance got : enchantments) {
                if (avoid.equals(got.enchantment)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Applies a plan to the player's RNG state, returning where it lands afterwards.
     * Mirrors the "Done" button in the original tool.
     */
    public static long applyPlan(long playerSeed, Result result) {
        if (playerSeed == PlayerSeed.UNKNOWN || result.outcome == Outcome.IMPOSSIBLE) {
            return playerSeed;
        }
        long seed = playerSeed;
        if (result.needsDummy()) {
            seed = PlayerSeed.advance(seed, result.itemsToThrow * PlayerSeed.STEPS_PER_ITEM_DROP);
            seed = PlayerSeed.advance(seed, PlayerSeed.STEPS_PER_ENCHANT); // dummy enchantment
        }
        seed = PlayerSeed.advance(seed, PlayerSeed.STEPS_PER_ENCHANT); // the real enchantment
        return seed;
    }

    /** What the three slots would offer for a given XP seed, vanilla shelf count and item. */
    public static SlotPreview[] preview(int xpSeed, int bookshelves, String item) {
        return preview(xpSeed, Models.vanillaTable(bookshelves), item);
    }

    /** What the three slots of this table would offer for a given XP seed and item. */
    public static SlotPreview[] preview(int xpSeed, TableSetup setup, String item) {
        SlotPreview[] slots = new SlotPreview[3];
        if (item == null || setup == null || CrackItems.getEnchantability(item) <= 0) {
            for (int i = 0; i < 3; i++) {
                slots[i] = new SlotPreview(0, Collections.emptyList());
            }
            return slots;
        }
        int[] levels = setup.levels(xpSeed, item);
        for (int slot = 0; slot < 3; slot++) {
            slots[slot] = new SlotPreview(levels[slot], levels[slot] <= 0
                    ? Collections.emptyList() : new ArrayList<>(setup.enchantments(xpSeed, item, slot, levels[slot])));
        }
        return slots;
    }

    public static final class SlotPreview {
        public final int levelRequirement;
        public final List<EnchantmentInstance> enchantments;

        SlotPreview(int levelRequirement, List<EnchantmentInstance> enchantments) {
            this.levelRequirement = levelRequirement;
            this.enchantments = enchantments;
        }

        public boolean isEmpty() {
            return levelRequirement == 0;
        }
    }
}
