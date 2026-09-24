package com.enchantmentcracker.core;

/**
 * A growable int list built out of fixed 1M-entry blocks.
 *
 * <p>Ported from Earthcomputer/Hexicube's standalone EnchantmentCracker (MIT).
 * A single backing array would need double its own size every time it grew,
 * which matters a lot here: the first cracking pass can retain ~100 million
 * candidate seeds.
 */
public final class IntArray {

    private static final int BLOCK_SIZE = 1_000_000;

    private final int[][] blocks;
    private final int capacity;
    private int size;
    private volatile boolean overflowed;

    /**
     * @param capacity the most entries this list will ever hold. Anything past it is
     *                 dropped and {@link #hasOverflowed()} flips, rather than the list
     *                 quietly eating every byte of heap Minecraft has.
     */
    public IntArray(int capacity) {
        this.capacity = capacity;
        this.blocks = new int[capacity / BLOCK_SIZE + 2][];
        blocks[0] = new int[BLOCK_SIZE];
    }

    public IntArray() {
        this(120_000_000);
    }

    public boolean hasOverflowed() {
        return overflowed;
    }

    public boolean isFull() {
        return size >= capacity;
    }

    public void clear() {
        size = 0;
        overflowed = false;
    }

    public void add(int value) {
        if (size >= capacity) {
            overflowed = true;
            return;
        }
        blocks[size / BLOCK_SIZE][size % BLOCK_SIZE] = value;
        size++;
        if (size % BLOCK_SIZE == 0 && blocks[size / BLOCK_SIZE] == null) {
            blocks[size / BLOCK_SIZE] = new int[BLOCK_SIZE];
        }
    }

    public void addAll(int[] values, int count) {
        if (size + count > capacity) {
            overflowed = true;
            count = Math.max(0, capacity - size);
        }
        int pos = 0;
        while (pos < count) {
            appendRange(values, pos, Math.min(BLOCK_SIZE, count - pos));
            pos += BLOCK_SIZE;
        }
    }

    public void addAll(IntArray other) {
        int remaining = Math.min(other.size, capacity - size);
        if (remaining < other.size) {
            overflowed = true;
        }
        int block = 0;
        while (remaining > 0) {
            appendRange(other.blocks[block], 0, Math.min(BLOCK_SIZE, remaining));
            remaining -= BLOCK_SIZE;
            block++;
        }
    }

    public int size() {
        return size;
    }

    public int get(int index) {
        return blocks[index / BLOCK_SIZE][index % BLOCK_SIZE];
    }

    /** Frees every block but the first, so an aborted crack does not keep hundreds of MB alive. */
    public void trim() {
        size = 0;
        overflowed = false;
        for (int i = 1; i < blocks.length; i++) {
            blocks[i] = null;
        }
    }

    private void appendRange(int[] source, int start, int length) {
        int currentBlock = size / BLOCK_SIZE;
        int available = BLOCK_SIZE - (size % BLOCK_SIZE);
        int pos = start;
        int end = start + length;
        while (pos < end) {
            int remaining = end - pos;
            if (remaining <= available) {
                System.arraycopy(source, pos, blocks[currentBlock], size % BLOCK_SIZE, remaining);
                size += remaining;
                if (size % BLOCK_SIZE == 0 && blocks[currentBlock + 1] == null) {
                    blocks[currentBlock + 1] = new int[BLOCK_SIZE];
                }
                pos += remaining;
            } else {
                System.arraycopy(source, pos, blocks[currentBlock], size % BLOCK_SIZE, available);
                currentBlock++;
                if (blocks[currentBlock] == null) {
                    blocks[currentBlock] = new int[BLOCK_SIZE];
                }
                pos += available;
                size += available;
                available = BLOCK_SIZE;
            }
        }
    }
}
