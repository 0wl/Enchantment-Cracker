package com.enchantmentcracker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The blocks around an enchanting table, and how to reach a given bookshelf count.
 *
 * <p>Vanilla looks in eight directions from the table. For each one, the two blocks directly
 * beside the table (at table height and one above) are the "gap"; if either is not air the
 * whole direction is ignored. Otherwise the shelf positions two blocks out count, plus the
 * in-between positions for the four diagonals. So a direction can be switched off wholesale
 * by putting anything (a torch, a carpet) in its gap, and that is the cheap, reversible way
 * to lower the count.
 *
 * <p>The power sum is truncated to an int after <em>every</em> shelf, in vanilla's exact
 * visiting order ({@code (int)((float) power + bonus)}), which only matters for modded shelves
 * with fractional power but is copied faithfully anyway.
 *
 * <p>Pure data; the game layer fills it in from the world.
 */
public final class EnchantArea {

    /** Vanilla caps the power at 15 before using it. */
    public static final int MAX_POWER = 15;

    public static final class Shelf {
        /** Offset from the table. */
        public final int dx;
        public final int dy;
        public final int dz;
        /** Enchanting power this block contributes when its direction is open. */
        public final float power;

        public Shelf(int dx, int dy, int dz, float power) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.power = power;
        }
    }

    public static final class Gap {
        public final int dx;
        public final int dz;
        /** Something is standing in the gap, so this direction contributes nothing right now. */
        public final boolean blocked;
        /** Shelf positions in vanilla's visiting order. Only blocks with power are listed. */
        public final List<Shelf> shelves;

        public Gap(int dx, int dz, boolean blocked, List<Shelf> shelves) {
            this.dx = dx;
            this.dz = dz;
            this.blocked = blocked;
            this.shelves = shelves;
        }

        public boolean isCorner() {
            return dx != 0 && dz != 0;
        }
    }

    /** What to change to get the target count. */
    public static final class Adjustment {
        public final boolean possible;
        public final int target;
        /** Power after the changes (before the 15 cap). */
        public final int resultingPower;
        /** Open directions to block. */
        public final List<Gap> block;
        /** Blocked directions to clear. */
        public final List<Gap> unblock;
        /** Individual shelves to take away, when blocking whole directions cannot hit the number. */
        public final List<Shelf> remove;

        Adjustment(boolean possible, int target, int resultingPower, List<Gap> block, List<Gap> unblock, List<Shelf> remove) {
            this.possible = possible;
            this.target = target;
            this.resultingPower = resultingPower;
            this.block = block;
            this.unblock = unblock;
            this.remove = remove;
        }

        public boolean nothingToDo() {
            return possible && block.isEmpty() && unblock.isEmpty() && remove.isEmpty();
        }

        public int changeCount() {
            return block.size() + unblock.size() + remove.size();
        }
    }

    private final List<Gap> gaps;

    /** @param gaps all eight directions, in vanilla order: dz outer -1..1, dx inner -1..1. */
    public EnchantArea(List<Gap> gaps) {
        this.gaps = Collections.unmodifiableList(new ArrayList<>(gaps));
    }

    public List<Gap> gaps() {
        return gaps;
    }

    /** The count the table sees right now, capped at 15. */
    public int currentPower() {
        boolean[] open = new boolean[gaps.size()];
        for (int i = 0; i < open.length; i++) {
            open[i] = !gaps.get(i).blocked;
        }
        return Math.min(MAX_POWER, rawPower(open, Collections.emptyList()));
    }

    /** The most this setup could give with every gap cleared, capped at 15. */
    public int potentialPower() {
        boolean[] open = new boolean[gaps.size()];
        java.util.Arrays.fill(open, true);
        return Math.min(MAX_POWER, rawPower(open, Collections.emptyList()));
    }

    /** Vanilla's sum, for the given open directions and with some shelves taken away. */
    public int rawPower(boolean[] open, List<Shelf> removed) {
        int power = 0;
        for (int i = 0; i < gaps.size(); i++) {
            if (!open[i]) {
                continue;
            }
            for (Shelf shelf : gaps.get(i).shelves) {
                if (!removed.contains(shelf)) {
                    power = (int) ((float) power + shelf.power);
                }
            }
        }
        return power;
    }

    /**
     * Works out the fewest changes that make the table read {@code target}.
     *
     * <p>Blocking or clearing a gap counts as one change; removing a shelf counts as two,
     * because it is more work and harder to undo, so it is only used when no combination of
     * gaps lands on the number exactly.
     */
    public Adjustment planFor(int target) {
        int n = gaps.size();
        Adjustment best = null;
        int bestCost = Integer.MAX_VALUE;

        for (int mask = 0; mask < (1 << n); mask++) {
            boolean[] open = new boolean[n];
            int cost = 0;
            List<Gap> block = new ArrayList<>();
            List<Gap> unblock = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                open[i] = (mask & (1 << i)) != 0;
                Gap gap = gaps.get(i);
                if (open[i] && gap.blocked) {
                    unblock.add(gap);
                    cost++;
                } else if (!open[i] && !gap.blocked) {
                    // Blocking a direction with no shelves in it changes nothing; skip the work.
                    if (!gap.shelves.isEmpty()) {
                        block.add(gap);
                        cost++;
                    }
                }
            }
            if (cost >= bestCost) {
                continue;
            }

            List<Shelf> removed = new ArrayList<>();
            int power = rawPower(open, removed);
            if (capped(power) < target) {
                continue;
            }
            // Too high: take shelves away, largest that still fits first, until it matches.
            while (capped(power) > target) {
                Shelf pick = null;
                for (int i = 0; i < n && pick == null; i++) {
                    if (!open[i]) {
                        continue;
                    }
                    for (Shelf shelf : gaps.get(i).shelves) {
                        if (removed.contains(shelf)) {
                            continue;
                        }
                        removed.add(shelf);
                        int without = rawPower(open, removed);
                        removed.remove(removed.size() - 1);
                        if (capped(without) >= target) {
                            pick = shelf;
                            break;
                        }
                    }
                }
                if (pick == null) {
                    break;
                }
                removed.add(pick);
                power = rawPower(open, removed);
                cost += 2;
                if (cost >= bestCost) {
                    break;
                }
            }
            if (capped(power) != target || cost >= bestCost) {
                continue;
            }
            bestCost = cost;
            best = new Adjustment(true, target, power, block, unblock, removed);
        }

        if (best == null) {
            return new Adjustment(false, target, potentialPower(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList());
        }
        return best;
    }

    private static int capped(int power) {
        return Math.min(MAX_POWER, power);
    }

    /**
     * Builds the empty skeleton of the eight directions in vanilla order, with the shelf
     * offsets each one looks at. The game layer fills in powers and gap states.
     */
    public static int[][] shelfOffsets(int dx, int dz) {
        List<int[]> offsets = new ArrayList<>();
        offsets.add(new int[]{dx * 2, 0, dz * 2});
        offsets.add(new int[]{dx * 2, 1, dz * 2});
        if (dx != 0 && dz != 0) {
            offsets.add(new int[]{dx * 2, 0, dz});
            offsets.add(new int[]{dx * 2, 1, dz});
            offsets.add(new int[]{dx, 0, dz * 2});
            offsets.add(new int[]{dx, 1, dz * 2});
        }
        return offsets.toArray(new int[0][]);
    }
}
