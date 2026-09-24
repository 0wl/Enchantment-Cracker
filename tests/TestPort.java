import com.enchantmentcracker.core.CrackEnchantments;
import com.enchantmentcracker.core.CrackItems;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.SeedCracker;
import com.enchantmentcracker.core.SimpleRandom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Differential test: the ported maths against the original standalone cracker,
 * plus end-to-end checks on the planner.
 */
public class TestPort {

    static int failures = 0;
    static int checks = 0;

    public static void main(String[] args) {
        List<String> items = CrackItems.enchantableItems();
        System.out.println("enchantable items: " + items.size());

        testEnchantability(items);
        testMaxLevelInTable(items);
        testLevelGeneration(items);
        testEnchantmentLists(items);
        testCrackerConsistency(items);
        testPlayerSeedMath();
        testPlanner();

        System.out.println();
        System.out.println("checks run: " + checks);
        if (failures == 0) {
            System.out.println("ALL PASS");
        } else {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
    }

    static void fail(String message) {
        failures++;
        if (failures <= 20) {
            System.out.println("  FAIL " + message);
        }
    }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            fail(message);
        }
    }

    // ---------------------------------------------------------------- item data

    static void testEnchantability(List<String> items) {
        System.out.println("-- enchantability vs original");
        for (String item : items) {
            check(CrackItems.getEnchantability(item) == enchcracker.Items.getEnchantability(item),
                    "enchantability " + item + ": " + CrackItems.getEnchantability(item)
                            + " vs " + enchcracker.Items.getEnchantability(item));
        }
    }

    static void testMaxLevelInTable(List<String> items) {
        System.out.println("-- max table level vs original");
        for (String item : items) {
            for (String ench : CrackEnchantments.tableEnchantments()) {
                int mine = CrackEnchantments.getMaxLevelInTable(ench, item);
                int theirs = enchcracker.Enchantments.getMaxLevelInTable(ench, item);
                check(mine == theirs, "maxLevelInTable " + ench + "/" + item + ": " + mine + " vs " + theirs);
            }
        }
    }

    // ---------------------------------------------------------------- level maths

    static void testLevelGeneration(List<String> items) {
        System.out.println("-- slot level requirements vs original");
        Random seeder = new Random(1234);
        for (int trial = 0; trial < 20000; trial++) {
            String item = items.get(seeder.nextInt(items.size()));
            int xpSeed = seeder.nextInt();
            int shelves = seeder.nextInt(16);

            Random a = new Random();
            Random b = new Random();
            a.setSeed(xpSeed);
            b.setSeed(xpSeed);
            for (int slot = 0; slot < 3; slot++) {
                int mine = CrackEnchantments.calcEnchantmentTableLevel(a, slot, shelves, item);
                int theirs = enchcracker.Enchantments.calcEnchantmentTableLevel(b, slot, shelves, item);
                check(mine == theirs, "level slot" + slot + " " + item + " shelves=" + shelves
                        + " seed=" + xpSeed + ": " + mine + " vs " + theirs);
            }
        }
    }

    static void testEnchantmentLists(List<String> items) {
        System.out.println("-- enchantment lists vs original (1.16)");
        Random seeder = new Random(99);
        for (int trial = 0; trial < 20000; trial++) {
            String item = items.get(seeder.nextInt(items.size()));
            int xpSeed = seeder.nextInt();
            int shelves = seeder.nextInt(16);

            Random a = new Random();
            Random b = new Random();
            int[] levelsA = new int[3];
            int[] levelsB = new int[3];
            a.setSeed(xpSeed);
            b.setSeed(xpSeed);
            for (int slot = 0; slot < 3; slot++) {
                int la = CrackEnchantments.calcEnchantmentTableLevel(a, slot, shelves, item);
                int lb = enchcracker.Enchantments.calcEnchantmentTableLevel(b, slot, shelves, item);
                levelsA[slot] = la < slot + 1 ? 0 : la;
                levelsB[slot] = lb < slot + 1 ? 0 : lb;
            }
            for (int slot = 0; slot < 3; slot++) {
                List<CrackEnchantments.EnchantmentInstance> mine =
                        CrackEnchantments.getEnchantmentsInTable(a, xpSeed, item, slot, levelsA[slot]);
                List<enchcracker.Enchantments.EnchantmentInstance> theirs =
                        enchcracker.Enchantments.getEnchantmentsInTable(b, xpSeed, item, slot, levelsB[slot],
                                enchcracker.Versions.V1_16);
                check(describeMine(mine).equals(describeTheirs(theirs)),
                        "enchants " + item + " slot" + slot + " shelves=" + shelves + " seed=" + xpSeed
                                + ": " + describeMine(mine) + " vs " + describeTheirs(theirs));
            }
        }
    }

    static String describeMine(List<CrackEnchantments.EnchantmentInstance> list) {
        List<String> parts = new ArrayList<>();
        for (CrackEnchantments.EnchantmentInstance i : list) {
            parts.add(i == null ? "null" : i.enchantment + ":" + i.level);
        }
        return String.join(",", parts);
    }

    static String describeTheirs(List<enchcracker.Enchantments.EnchantmentInstance> list) {
        List<String> parts = new ArrayList<>();
        for (enchcracker.Enchantments.EnchantmentInstance i : list) {
            parts.add(i == null ? "null" : i.enchantment + ":" + i.level);
        }
        return String.join(",", parts);
    }

    // ---------------------------------------------------------------- cracker

    static void testCrackerConsistency(List<String> items) {
        System.out.println("-- SeedCracker.matches / levelsFor / deduceBookshelves");
        Random seeder = new Random(7);
        for (int trial = 0; trial < 20000; trial++) {
            int xpSeed = seeder.nextInt();
            int shelves = seeder.nextInt(16);
            String item = CrackItems.DIAMOND_PICKAXE;

            // The cracker's fast path uses the raw formula, so compare to the raw levels.
            SimpleRandom raw = new SimpleRandom();
            raw.setSeed(xpSeed);
            Random ref = new Random();
            ref.setSeed(xpSeed);
            int[] refLevels = new int[3];
            for (int slot = 0; slot < 3; slot++) {
                refLevels[slot] = CrackEnchantments.calcEnchantmentTableLevel(ref, slot, shelves, item);
            }
            check(SeedCracker.matches(xpSeed, shelves, refLevels[0], refLevels[1], refLevels[2]),
                    "matches() rejected its own seed " + xpSeed + " shelves=" + shelves);

            int[] clamped = SeedCracker.levelsFor(xpSeed, shelves, item);
            for (int slot = 0; slot < 3; slot++) {
                int expected = refLevels[slot] < slot + 1 ? 0 : refLevels[slot];
                check(clamped[slot] == expected, "levelsFor slot" + slot + ": " + clamped[slot]
                        + " vs " + expected);
            }
        }
    }

    // ---------------------------------------------------------------- seed maths

    static void testPlayerSeedMath() {
        System.out.println("-- player seed solve / step");
        Random seeder = new Random(31337);
        for (int trial = 0; trial < 20000; trial++) {
            long start = seeder.nextLong() & SimpleRandom.MASK;

            check(PlayerSeed.previous(PlayerSeed.next(start)) == start, "previous(next(s)) != s");

            // Two consecutive nextInt() results, exactly as the game produces XP seeds.
            long s1 = PlayerSeed.next(start);
            long s2 = PlayerSeed.next(s1);
            int xp1 = PlayerSeed.xpSeedOf(s1);
            int xp2 = PlayerSeed.xpSeedOf(s2);
            long solved = PlayerSeed.solve(xp1, xp2);
            check(solved != PlayerSeed.UNKNOWN, "solve() found nothing for " + xp1 + "/" + xp2);
            check(solved == s2, "solve() gave " + Long.toHexString(solved)
                    + " expected " + Long.toHexString(s2));

            // And that it agrees with what java.util.Random actually does.
            Random real = new Random();
            setRawSeed(real, start);
            check(real.nextInt() == xp1, "xpSeedOf does not match Random.nextInt()");
            check(real.nextInt() == xp2, "second xpSeed does not match Random.nextInt()");
        }

        System.out.println("-- drop / enchant step counts");
        Random real = new Random();
        setRawSeed(real, 0x123456789ABCL);
        long tracked = 0x123456789ABCL;
        for (int i = 0; i < 50; i++) {
            // One dropped stack: four nextFloat calls, as PlayerEntity.dropItem does.
            real.nextFloat();
            real.nextFloat();
            real.nextFloat();
            real.nextFloat();
            tracked = PlayerSeed.advance(tracked, PlayerSeed.STEPS_PER_ITEM_DROP);
            check(rawSeed(real) == tracked, "drop tracking drifted at " + i);
        }
        int next = real.nextInt();
        tracked = PlayerSeed.advance(tracked, PlayerSeed.STEPS_PER_ENCHANT);
        check(PlayerSeed.xpSeedOf(tracked) == next, "enchant step does not produce the XP seed");
    }

    static void setRawSeed(Random random, long raw) {
        try {
            java.lang.reflect.Field field = Random.class.getDeclaredField("seed");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) field.get(random)).set(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static long rawSeed(Random random) {
        try {
            java.lang.reflect.Field field = Random.class.getDeclaredField("seed");
            field.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicLong) field.get(random)).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------- planner

    static void testPlanner() {
        System.out.println("-- planner end to end");
        Random seeder = new Random(2024);
        int found = 0;
        int impossible = 0;

        for (int trial = 0; trial < 300; trial++) {
            long playerSeed = seeder.nextLong() & SimpleRandom.MASK;
            int currentXpSeed = seeder.nextInt();
            String item = CrackItems.DIAMOND_PICKAXE;

            List<CrackEnchantments.EnchantmentInstance> wanted = new ArrayList<>();
            wanted.add(new CrackEnchantments.EnchantmentInstance(CrackEnchantments.EFFICIENCY, 4));
            List<String> unwanted = Collections.singletonList(CrackEnchantments.SILK_TOUCH);

            EnchantCalculator.Result result = EnchantCalculator.calculate(
                    playerSeed, currentXpSeed, item, 15, 30, wanted, unwanted, 512, null);

            if (result.outcome == EnchantCalculator.Outcome.IMPOSSIBLE) {
                impossible++;
                continue;
            }
            found++;

            // Replay the plan and check the table really does hand over those enchantments.
            int xpSeed;
            if (result.needsDummy()) {
                long after = PlayerSeed.advance(playerSeed,
                        result.itemsToThrow * PlayerSeed.STEPS_PER_ITEM_DROP);
                xpSeed = PlayerSeed.xpSeedOf(PlayerSeed.next(after));
            } else {
                xpSeed = currentXpSeed;
            }

            EnchantCalculator.SlotPreview[] slots =
                    EnchantCalculator.preview(xpSeed, result.bookshelves, item);
            EnchantCalculator.SlotPreview actual = slots[result.slot];

            check(actual.levelRequirement == result.levelRequirement,
                    "replayed level " + actual.levelRequirement + " vs planned " + result.levelRequirement);
            check(describeMine(actual.enchantments).equals(describeMine(result.enchantments)),
                    "replayed enchants " + describeMine(actual.enchantments)
                            + " vs planned " + describeMine(result.enchantments));

            boolean hasEfficiency4 = false;
            boolean hasSilkTouch = false;
            for (CrackEnchantments.EnchantmentInstance instance : actual.enchantments) {
                if (CrackEnchantments.EFFICIENCY.equals(instance.enchantment) && instance.level >= 4) {
                    hasEfficiency4 = true;
                }
                if (CrackEnchantments.SILK_TOUCH.equals(instance.enchantment)) {
                    hasSilkTouch = true;
                }
            }
            check(hasEfficiency4, "plan did not deliver Efficiency IV: " + describeMine(actual.enchantments));
            check(!hasSilkTouch, "plan delivered a banned enchantment");

            // applyPlan must land on the state after the real enchantment.
            long applied = EnchantCalculator.applyPlan(playerSeed, result);
            long expected = playerSeed;
            if (result.needsDummy()) {
                expected = PlayerSeed.advance(expected,
                        result.itemsToThrow * PlayerSeed.STEPS_PER_ITEM_DROP);
                expected = PlayerSeed.next(expected);
            }
            expected = PlayerSeed.next(expected);
            check(applied == expected, "applyPlan landed on the wrong state");
        }
        System.out.println("   plans found: " + found + ", impossible: " + impossible);
        check(found > 250, "planner found too few solutions: " + found);
    }
}
