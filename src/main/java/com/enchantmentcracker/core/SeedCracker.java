package com.enchantmentcracker.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Brute-forces the 32-bit XP seed from observed enchanting table level requirements.
 *
 * <p>Ported from Earthcomputer/Hexicube's standalone EnchantmentCracker (MIT).
 *
 * <p>The first observation sweeps all 2^32 seeds across every spare CPU core; later
 * observations just filter the survivors. In 1.16.5 the XP seed is actually synced to the
 * client, so {@link com.enchantmentcracker.game.TableWatcher} can normally read it for
 * free — this class exists for the manual workflow, for verification, and for anyone who
 * wants to see the original technique work.
 *
 * <p>The first pass can retain ~100 million survivors (~400 MB). Call {@link #reset()}
 * when you are done with it.
 */
public final class SeedCracker {

    /**
     * Cap on retained candidates, derived from the heap Minecraft was given.
     *
     * <p>Some readings are catastrophically unselective — zero bookshelves with all three
     * slots at level 1 matches roughly one seed in eight, which is half a billion of them.
     * Without a cap that is 2 GB of ints and a crash, so instead the pass stops and says so.
     */
    public static final int MAX_CANDIDATES = (int) Math.max(8_000_000L, Math.min(130_000_000L,
            Runtime.getRuntime().maxMemory() / 4 / 5));

    private final IntArray possibleSeeds = new IntArray(MAX_CANDIDATES);
    private volatile boolean tooManySeeds;
    private final AtomicLong seedsSearched = new AtomicLong();
    private final AtomicBoolean abortRequested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile boolean firstTime = true;
    private volatile int observations;

    public boolean isRunning() {
        return running.get();
    }

    public boolean isFirstTime() {
        return firstTime;
    }

    public int getObservationCount() {
        return observations;
    }

    public int getPossibleSeeds() {
        return possibleSeeds.size();
    }

    /** Only meaningful once {@link #getPossibleSeeds()} is exactly 1. */
    public int getSeed() {
        return possibleSeeds.get(0);
    }

    public long getSeedsSearched() {
        return seedsSearched.get();
    }

    /** 0..1 progress through the current pass, or -1 when idle. */
    public float getProgress() {
        if (!running.get()) {
            return -1;
        }
        long searched = seedsSearched.get();
        long total = firstTime ? 0x1_0000_0000L : Math.max(possibleSeeds.size(), 1);
        return Math.min(1f, (float) ((double) searched / (double) total));
    }

    public void requestAbort() {
        abortRequested.set(true);
    }

    public boolean isAbortRequested() {
        return abortRequested.get();
    }

    /** True when the last pass matched more seeds than we are willing to hold. */
    public boolean isTooManySeeds() {
        return tooManySeeds;
    }

    /** Throws away every candidate and frees the backing blocks. */
    public void reset() {
        abortRequested.set(true);
        firstTime = true;
        observations = 0;
        tooManySeeds = false;
        seedsSearched.set(0);
        possibleSeeds.trim();
    }

    /**
     * Feeds one observation into the cracker. Blocking and CPU-hungry; never call this
     * from the render thread.
     */
    public void addObservation(int bookshelves, int slot1, int slot2, int slot3) {
        running.set(true);
        tooManySeeds = false;
        try {
            if (firstTime) {
                firstInput(bookshelves, slot1, slot2, slot3);
                firstTime = false;
            } else {
                addInput(bookshelves, slot1, slot2, slot3);
            }
            if (tooManySeeds || possibleSeeds.hasOverflowed()) {
                tooManySeeds = true;
                possibleSeeds.trim();
                firstTime = true;
                observations = 0;
                return;
            }
            observations++;
        } finally {
            running.set(false);
        }
    }

    // ---------------------------------------------------------------- level generators

    private static int getGenericEnchantability(SimpleRandom rand, int bookshelves) {
        int first = rand.nextInt(8);
        int second = rand.nextInt(bookshelves + 1);
        return first + 1 + (bookshelves >> 1) + second;
    }

    private static int getLevelsSlot1(SimpleRandom rand, int bookshelves) {
        int enchantability = getGenericEnchantability(rand, bookshelves) / 3;
        return enchantability < 1 ? 1 : enchantability;
    }

    private static int getLevelsSlot2(SimpleRandom rand, int bookshelves) {
        return getGenericEnchantability(rand, bookshelves) * 2 / 3 + 1;
    }

    private static int getLevelsSlot3(SimpleRandom rand, int bookshelves) {
        int enchantability = getGenericEnchantability(rand, bookshelves);
        int twiceBookshelves = bookshelves * 2;
        return enchantability < twiceBookshelves ? twiceBookshelves : enchantability;
    }

    // ---------------------------------------------------------------- passes

    private void firstInput(int bookshelves, int slot1, int slot2, int slot3) {
        abortRequested.set(false);
        // Leave one core for the game (and the OS), but always use at least one.
        final int threadCount = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
        final int blockSize = Integer.MAX_VALUE / 20 / threadCount - 1;
        final AtomicInteger seed = new AtomicInteger(Integer.MIN_VALUE);
        List<Thread> threads = new ArrayList<>();

        final int twoShelves = bookshelves * 2;
        final int halfShelves = bookshelves / 2 + 1;
        final int shelvesPlusOne = bookshelves + 1;

        // Cheap early-outs derived from the target levels; they reject most seeds
        // after a single nextInt call.
        final int firstEarly = slot1 * 3 + 2;
        final int secondEarly = slot2 * 3 / 2;
        final int secondSubOne = slot2 - 1;

        seedsSearched.set(0);
        possibleSeeds.clear();

        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                final int[] local = new int[1_000_000];
                int pos = 0;
                final SimpleRandom rng = new SimpleRandom();

                while (true) {
                    if (abortRequested.get()) {
                        return;
                    }
                    int curSeed = seed.get();
                    final int last = curSeed + blockSize;
                    if (last < curSeed) {
                        break; // overflowed past Integer.MAX_VALUE
                    }
                    if (seed.compareAndSet(curSeed, curSeed + blockSize)) {
                        for (; curSeed < last; curSeed++) {
                            rng.setSeed(curSeed);

                            int ench1r1 = rng.nextInt(8) + halfShelves;
                            if (ench1r1 > firstEarly) continue;
                            int ench1 = (ench1r1 + rng.nextInt(shelvesPlusOne)) / 3;
                            if (ench1 < 1 && slot1 != 1) continue;
                            if (ench1 != slot1) continue;

                            int ench2r1 = rng.nextInt(8) + halfShelves;
                            if (ench2r1 > secondEarly) continue;
                            int ench2 = (ench2r1 + rng.nextInt(shelvesPlusOne)) * 2 / 3;
                            if (ench2 != secondSubOne) continue;

                            int ench3 = rng.nextInt(8) + halfShelves + rng.nextInt(shelvesPlusOne);
                            if (Math.max(ench3, twoShelves) != slot3) continue;

                            local[pos++] = curSeed;
                            if (pos == local.length) {
                                boolean full;
                                synchronized (possibleSeeds) {
                                    possibleSeeds.addAll(local, local.length);
                                    full = possibleSeeds.isFull();
                                }
                                pos = 0;
                                if (full) {
                                    // These readings match far too many seeds to be useful.
                                    abortRequested.set(true);
                                    return;
                                }
                            }
                        }
                    }
                }
                synchronized (possibleSeeds) {
                    possibleSeeds.addAll(local, pos);
                }
            }, "EnchCracker-worker-" + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            threads.add(thread);
            thread.start();
        }

        while (true) {
            if (abortRequested.get()) {
                joinAll(threads);
                abortRequested.set(false);
                if (possibleSeeds.hasOverflowed() || possibleSeeds.isFull()) {
                    tooManySeeds = true;
                }
                possibleSeeds.trim();
                return;
            }
            int cur = seed.get();
            if (cur + blockSize < cur) {
                break;
            }
            seedsSearched.set((long) cur - (long) Integer.MIN_VALUE);
            try {
                Thread.sleep(1);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        joinAll(threads);

        // The worker loop stops one block short of overflowing; finish the tail here.
        SimpleRandom rng = new SimpleRandom();
        int[] tail = new int[blockSize];
        int tailPos = 0;
        for (int s = seed.get(); s != Integer.MIN_VALUE; s++) {
            rng.setSeed(s);
            if (getLevelsSlot1(rng, bookshelves) == slot1
                    && getLevelsSlot2(rng, bookshelves) == slot2
                    && getLevelsSlot3(rng, bookshelves) == slot3) {
                tail[tailPos++] = s;
            }
        }
        possibleSeeds.addAll(tail, tailPos);
        seedsSearched.set(0x1_0000_0000L);
        abortRequested.set(false);
    }

    private void addInput(int bookshelves, int slot1, int slot2, int slot3) {
        SimpleRandom rand = new SimpleRandom();
        IntArray survivors = new IntArray();
        seedsSearched.set(0);

        for (int i = 0, e = possibleSeeds.size(); i < e; i++) {
            if (i % 250_000 == 0) {
                if (abortRequested.get()) {
                    abortRequested.set(false);
                    return;
                }
                seedsSearched.set(i);
            }

            int s = possibleSeeds.get(i);
            rand.setSeed(s);
            if (getLevelsSlot1(rand, bookshelves) == slot1
                    && getLevelsSlot2(rand, bookshelves) == slot2
                    && getLevelsSlot3(rand, bookshelves) == slot3) {
                survivors.add(s);
            }
        }
        possibleSeeds.clear();
        possibleSeeds.addAll(survivors);
        survivors.trim();
    }

    private static void joinAll(List<Thread> threads) {
        while (!threads.isEmpty()) {
            try {
                threads.remove(0).join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Checks a candidate XP seed against one observation without touching the
     * cracker's state. Used to verify a seed read straight off the table.
     */
    public static boolean matches(int xpSeed, int bookshelves, int slot1, int slot2, int slot3) {
        SimpleRandom rand = new SimpleRandom();
        rand.setSeed(xpSeed);
        return getLevelsSlot1(rand, bookshelves) == slot1
                && getLevelsSlot2(rand, bookshelves) == slot2
                && getLevelsSlot3(rand, bookshelves) == slot3;
    }

    /** Recovers the bookshelf count that explains an observation, or -1 if none does. */
    public static int deduceBookshelves(int xpSeed, int slot1, int slot2, int slot3) {
        for (int bookshelves = 0; bookshelves <= 15; bookshelves++) {
            if (matches(xpSeed, bookshelves, slot1, slot2, slot3)) {
                return bookshelves;
            }
        }
        return -1;
    }

    /** The level requirements a given XP seed produces, for cross-checking against the table. */
    public static int[] levelsFor(int xpSeed, int bookshelves, String item) {
        Random rand = new Random();
        rand.setSeed(xpSeed);
        int[] levels = new int[3];
        for (int slot = 0; slot < 3; slot++) {
            int level = CrackEnchantments.calcEnchantmentTableLevel(rand, slot, bookshelves, item);
            levels[slot] = level < slot + 1 ? 0 : level;
        }
        return levels;
    }
}
