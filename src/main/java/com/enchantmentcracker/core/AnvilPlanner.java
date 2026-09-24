package com.enchantmentcracker.core;

import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finds the cheapest order to combine single-enchantment books onto an item at an anvil.
 *
 * <p>Costs follow {@code RepairContainer#updateRepairOutput} in 1.16.5 exactly:
 * <ul>
 *     <li>each enchantment carried by the sacrifice costs its level times a rarity multiplier
 *     (1, 2, 4, 8 for common to very rare), halved (minimum 1) when the sacrifice is a book;</li>
 *     <li>both inputs add their prior-work penalty, {@code 2^n - 1} after {@code n} anvil uses;</li>
 *     <li>the result's work count is one more than the larger of the two inputs';</li>
 *     <li>a step costing 40 levels or more is "Too Expensive!" in survival.</li>
 * </ul>
 *
 * <p>The search is exact: a dynamic programme over every subset of books and every work
 * count, so it considers every merge tree, not just the usual "pair them up" heuristic.
 * Twelve books is the practical ceiling, which is more than any real item takes.
 */
public final class AnvilPlanner {

    /** Survival refuses any anvil step costing this much or more. */
    public static final int TOO_EXPENSIVE = 40;
    public static final int MAX_BOOKS = 12;

    private static final int INF = Integer.MAX_VALUE / 4;

    private AnvilPlanner() {
    }

    /** One anvil operation. */
    public static final class Step {
        /** What goes in the left slot. */
        public final String target;
        /** What goes in the right slot, and gets used up. */
        public final String sacrifice;
        /** What comes out. */
        public final String result;
        public final int cost;
        /** True when the left slot is the item itself rather than a book. */
        public final boolean ontoItem;

        Step(String target, String sacrifice, String result, int cost, boolean ontoItem) {
            this.target = target;
            this.sacrifice = sacrifice;
            this.result = result;
            this.cost = cost;
            this.ontoItem = ontoItem;
        }
    }

    public static final class Plan {
        public final boolean possible;
        /** Why not, when {@link #possible} is false. */
        public final String problem;
        public final List<Step> steps;
        public final int totalLevels;
        /** Anvil uses recorded on the finished item. */
        public final int finalWork;
        /** The single most expensive step, which is the level you need to be at once. */
        public final int maxStepCost;

        Plan(boolean possible, String problem, List<Step> steps, int totalLevels, int finalWork, int maxStepCost) {
            this.possible = possible;
            this.problem = problem;
            this.steps = steps;
            this.totalLevels = totalLevels;
            this.finalWork = finalWork;
            this.maxStepCost = maxStepCost;
        }

        static Plan impossible(String why) {
            return new Plan(false, why, Collections.emptyList(), 0, 0, 0);
        }
    }

    /** Prior-work penalty after {@code work} anvil uses: 0, 1, 3, 7, 15, 31... */
    public static int penalty(int work) {
        return work >= 30 ? INF : (1 << work) - 1;
    }

    /** Levels one book of this enchantment adds to a step when used as the sacrifice. */
    public static int bookValue(EnchantModel model, EnchantmentInstance book) {
        return Math.max(1, model.anvilMultiplier(book.enchantment) / 2) * book.level;
    }

    /** Overload for a clean item straight out of the enchanting table: no prior work, no enchantments. */
    public static Plan plan(EnchantModel model, String item, String itemLabel, int itemWork,
                            List<EnchantmentInstance> books, java.util.function.Function<EnchantmentInstance, String> namer,
                            int cap) {
        return plan(model, item, itemLabel, itemWork, Collections.emptyList(), books, namer, cap);
    }

    /**
     * @param item       what the books end up on; its enchantability is irrelevant here
     * @param itemWork   anvil uses already on the item (0 for one fresh out of the table); adds
     *                   the item's own prior-work penalty to every step that touches it
     * @param existing   enchantments already on the item, which the books must not clash with and
     *                   which a book of the same enchantment may only raise, never repeat
     * @param books      one book per enchantment to add, all different and mutually compatible
     * @param namer      turns a book into a readable label
     * @param cap        a step costing this much or more is refused: {@link #TOO_EXPENSIVE} in
     *                   vanilla survival, {@code Integer.MAX_VALUE} where a mod lifts the cap
     */
    public static Plan plan(EnchantModel model, String item, String itemLabel, int itemWork,
                            List<EnchantmentInstance> existing, List<EnchantmentInstance> books,
                            java.util.function.Function<EnchantmentInstance, String> namer, int cap) {
        int n = books.size();
        if (n == 0) {
            return Plan.impossible("Pick at least one enchantment.");
        }
        if (n > MAX_BOOKS) {
            return Plan.impossible("At most " + MAX_BOOKS + " books.");
        }
        for (int i = 0; i < n; i++) {
            EnchantmentInstance a = books.get(i);
            if (!model.canApplyAtAnvil(a.enchantment, item)) {
                return Plan.impossible(namer.apply(a) + " cannot go on " + itemLabel + ".");
            }
            for (int j = i + 1; j < n; j++) {
                EnchantmentInstance b = books.get(j);
                if (a.enchantment.equals(b.enchantment) || !model.compatible(a.enchantment, b.enchantment)) {
                    return Plan.impossible(namer.apply(a) + " and " + namer.apply(b) + " cannot share an item.");
                }
            }
            for (EnchantmentInstance e : existing) {
                if (a.enchantment.equals(e.enchantment)) {
                    if (a.level <= e.level) {
                        return Plan.impossible(itemLabel + " already has " + namer.apply(e)
                                + "; pick a higher level to upgrade it.");
                    }
                } else if (!model.compatible(a.enchantment, e.enchantment)) {
                    return Plan.impossible(namer.apply(a) + " clashes with " + namer.apply(e)
                            + " already on " + itemLabel + ".");
                }
            }
        }

        int full = (1 << n) - 1;
        int maxWork = n + itemWork + 1;
        int[] value = new int[1 << n];
        for (int mask = 1; mask <= full; mask++) {
            int low = Integer.numberOfTrailingZeros(mask);
            value[mask] = value[mask & (mask - 1)] + bookValue(model, books.get(low));
        }

        // book[mask][w]: cheapest way to merge exactly these books into one book with work w.
        int[][] book = new int[1 << n][maxWork + 1];
        int[][] bookSplit = new int[1 << n][maxWork + 1];   // sacrifice submask
        int[][] bookWorks = new int[1 << n][maxWork + 1];   // wA * 64 + wB
        for (int[] row : book) {
            Arrays.fill(row, INF);
        }
        for (int i = 0; i < n; i++) {
            book[1 << i][0] = 0;
        }
        for (int mask = 1; mask <= full; mask++) {
            if (Integer.bitCount(mask) < 2) {
                continue;
            }
            for (int sac = (mask - 1) & mask; sac > 0; sac = (sac - 1) & mask) {
                int tgt = mask ^ sac;
                for (int wa = 0; wa <= maxWork; wa++) {
                    if (book[tgt][wa] >= INF) {
                        continue;
                    }
                    for (int wb = 0; wb <= maxWork; wb++) {
                        if (book[sac][wb] >= INF) {
                            continue;
                        }
                        int step = value[sac] + penalty(wa) + penalty(wb);
                        int w = Math.max(wa, wb) + 1;
                        if (step >= cap || w > maxWork) {
                            continue;
                        }
                        int total = book[tgt][wa] + book[sac][wb] + step;
                        if (total < book[mask][w]) {
                            book[mask][w] = total;
                            bookSplit[mask][w] = sac;
                            bookWorks[mask][w] = wa * 64 + wb;
                        }
                    }
                }
            }
        }

        // onItem[mask][w]: cheapest way to get these books onto the item, leaving it at work w.
        int[][] onItem = new int[1 << n][maxWork + 1];
        int[][] itemSplit = new int[1 << n][maxWork + 1];
        int[][] itemWorks = new int[1 << n][maxWork + 1];
        for (int[] row : onItem) {
            Arrays.fill(row, INF);
        }
        onItem[0][Math.min(itemWork, maxWork)] = 0;
        for (int mask = 1; mask <= full; mask++) {
            for (int sac = mask; sac > 0; sac = (sac - 1) & mask) {
                int before = mask ^ sac;
                for (int wa = 0; wa <= maxWork; wa++) {
                    if (onItem[before][wa] >= INF) {
                        continue;
                    }
                    for (int wb = 0; wb <= maxWork; wb++) {
                        if (book[sac][wb] >= INF) {
                            continue;
                        }
                        int step = value[sac] + penalty(wa) + penalty(wb);
                        int w = Math.max(wa, wb) + 1;
                        if (step >= cap || w > maxWork) {
                            continue;
                        }
                        int total = onItem[before][wa] + book[sac][wb] + step;
                        if (total < onItem[mask][w]) {
                            onItem[mask][w] = total;
                            itemSplit[mask][w] = sac;
                            itemWorks[mask][w] = wa * 64 + wb;
                        }
                    }
                }
            }
        }

        int bestW = -1;
        for (int w = 0; w <= maxWork; w++) {
            if (onItem[full][w] < INF && (bestW < 0 || onItem[full][w] < onItem[full][bestW])) {
                bestW = w;
            }
        }
        if (bestW < 0) {
            return Plan.impossible("Every order hits \"Too Expensive!\" (" + cap + "+ levels in one step). "
                    + "Try fewer or lower-level enchantments.");
        }

        Builder builder = new Builder(books, namer, book, bookSplit, bookWorks);
        builder.buildItem(full, bestW, itemLabel, onItem, itemSplit, itemWorks);
        int maxStep = 0;
        for (Step step : builder.steps) {
            maxStep = Math.max(maxStep, step.cost);
        }
        return new Plan(true, null, builder.steps, onItem[full][bestW], bestW, maxStep);
    }

    /** Walks the DP choices back into an ordered list of anvil steps. */
    private static final class Builder {
        final List<EnchantmentInstance> books;
        final java.util.function.Function<EnchantmentInstance, String> namer;
        final int[][] book;
        final int[][] bookSplit;
        final int[][] bookWorks;
        final List<Step> steps = new ArrayList<>();

        Builder(List<EnchantmentInstance> books, java.util.function.Function<EnchantmentInstance, String> namer,
                int[][] book, int[][] bookSplit, int[][] bookWorks) {
            this.books = books;
            this.namer = namer;
            this.book = book;
            this.bookSplit = bookSplit;
            this.bookWorks = bookWorks;
        }

        /** Emits the steps that make this book group, and returns its label. */
        String buildBook(int mask, int w) {
            if (Integer.bitCount(mask) == 1) {
                return "Book: " + namer.apply(books.get(Integer.numberOfTrailingZeros(mask)));
            }
            int sac = bookSplit[mask][w];
            int wa = bookWorks[mask][w] / 64;
            int wb = bookWorks[mask][w] % 64;
            String left = buildBook(mask ^ sac, wa);
            String right = buildBook(sac, wb);
            int cost = book[mask][w] - book[mask ^ sac][wa] - book[sac][wb];
            String label = "Book: " + describe(mask);
            steps.add(new Step(left, right, label, cost, false));
            return label;
        }

        void buildItem(int mask, int w, String itemLabel, int[][] onItem, int[][] itemSplit, int[][] itemWorks) {
            if (mask == 0) {
                return;
            }
            int sac = itemSplit[mask][w];
            int wa = itemWorks[mask][w] / 64;
            int wb = itemWorks[mask][w] % 64;
            buildItem(mask ^ sac, wa, itemLabel, onItem, itemSplit, itemWorks);
            String right = buildBook(sac, wb);
            int cost = onItem[mask][w] - onItem[mask ^ sac][wa] - book[sac][wb];
            steps.add(new Step(itemLabel, right, itemLabel, cost, true));
        }

        private String describe(int mask) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < books.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    if (sb.length() > 0) {
                        sb.append(" + ");
                    }
                    sb.append(namer.apply(books.get(i)));
                }
            }
            return sb.toString();
        }
    }
}
