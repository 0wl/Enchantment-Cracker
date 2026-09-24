import com.enchantmentcracker.core.AnvilPlanner;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.EnchantModel;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.VanillaModel;
import com.enchantmentcracker.core.VelocityCracker;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-logic checks for the 1.2.0 features, run in a plain JVM with only the mod's core
 * classes on the classpath — no Minecraft. These cover the parts that can be verified
 * deterministically: the velocity cracker's maths, the anvil planner's new "start from a
 * real item" path, and the widened drop-search ceiling. The GUI and in-game wiring are
 * covered by the self-test mod and the manual checklist instead.
 */
public class FeatureTests {

    static int checks;
    static int failures;

    static void check(boolean ok, String what) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

    public static void main(String[] args) {
        System.out.println("== PlayerSeed ==");
        playerSeed();
        System.out.println("== VelocityCracker (T10: lock from a thrown item) ==");
        velocityCracker();
        System.out.println("== AnvilPlanner (T12: start from a pre-enchanted / used item) ==");
        anvilFromHeldItem();
        System.out.println("== EnchantCalculator (T5: drop-search ceiling) ==");
        dropCeiling();

        System.out.println();
        System.out.println(failures == 0 ? "ALL " + checks + " CHECKS PASSED" : failures + " / " + checks + " CHECKS FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ PlayerSeed

    private static void playerSeed() {
        long seed = 0x1234_5678_9ABCL;
        check(PlayerSeed.previous(PlayerSeed.next(seed)) == seed, "next then previous is identity");
        check(PlayerSeed.advance(seed, 7) == step(seed, 7), "advance(7) == seven next() calls");
        check(PlayerSeed.advance(PlayerSeed.advance(seed, 5), -5) == seed, "advance(+5) then advance(-5) is identity");

        // Two XP seeds one enchantment apart solve back to the state that produced the second.
        long s0 = 0x0000_DEAD_BEEFL;
        long s1 = PlayerSeed.next(s0);
        long s2 = PlayerSeed.next(s1);
        int xp1 = PlayerSeed.xpSeedOf(s1);
        int xp2 = PlayerSeed.xpSeedOf(s2);
        check(PlayerSeed.solve(xp1, xp2) == s2, "solve() recovers the state from two consecutive XP seeds");
    }

    private static long step(long seed, int n) {
        for (int i = 0; i < n; i++) {
            seed = PlayerSeed.next(seed);
        }
        return seed;
    }

    // ------------------------------------------------------------------ VelocityCracker

    private static void velocityCracker() {
        long[] states = {0x0000_0000_0001L, 0x1357_9BDF_0246L, 0x7FFF_FFFF_FFFFL, 0xABCD_1234_5678L};
        float[][] looks = {{0f, 0f}, {45f, 0f}, {-123.4f, 30f}, {177.7f, -42.5f}};
        int recovered = 0;
        int unique = 0;
        for (long stateAfterEnchant : states) {
            for (float[] look : looks) {
                float yaw = look[0];
                float pitch = look[1];
                int xpSeed = PlayerSeed.xpSeedOf(stateAfterEnchant);

                // Simulate the server: throw one item straight after the enchant (offset 0), then
                // send its velocity to the client, quantised through the network packet.
                VelocityCracker.Velocity exact = VelocityCracker.velocityFor(stateAfterEnchant, yaw, pitch);
                VelocityCracker.Velocity asClientSees = new VelocityCracker.Velocity(
                        VelocityCracker.pack(exact.x) / 8000.0,
                        VelocityCracker.pack(exact.y) / 8000.0,
                        VelocityCracker.pack(exact.z) / 8000.0);

                List<Long> got = VelocityCracker.solveFromXpSeed(xpSeed, 0, asClientSees, yaw, pitch);
                long expectedPostDrop = PlayerSeed.advance(stateAfterEnchant, PlayerSeed.STEPS_PER_ITEM_DROP);
                if (got.contains(expectedPostDrop)) {
                    recovered++;
                }
                if (got.size() == 1) {
                    unique++;
                }
            }
        }
        int total = states.length * looks.length;
        check(recovered == total, "throw velocity recovers the true post-drop state in all " + total + " cases");
        check(unique >= total - 2, "the solve is unique in nearly every case (" + unique + "/" + total + ")");

        // With items dropped in between, the step offset must be honoured.
        long s = 0x2468_ACE0_1357L;
        int xp = PlayerSeed.xpSeedOf(s);
        long preDrop = PlayerSeed.advance(s, 2 * PlayerSeed.STEPS_PER_ITEM_DROP); // two junk drops first
        VelocityCracker.Velocity v = VelocityCracker.velocityFor(preDrop, 12f, -5f);
        VelocityCracker.Velocity seen = new VelocityCracker.Velocity(
                VelocityCracker.pack(v.x) / 8000.0, VelocityCracker.pack(v.y) / 8000.0, VelocityCracker.pack(v.z) / 8000.0);
        List<Long> withOffset = VelocityCracker.solveFromXpSeed(xp, 2 * PlayerSeed.STEPS_PER_ITEM_DROP, seen, 12f, -5f);
        check(withOffset.contains(PlayerSeed.advance(preDrop, PlayerSeed.STEPS_PER_ITEM_DROP)),
                "the step offset for earlier drops is applied");

        // A garbage velocity must not produce a false lock.
        List<Long> none = VelocityCracker.solveFromXpSeed(xp, 0, new VelocityCracker.Velocity(2.5, 2.5, 2.5), 0f, 0f);
        check(none.isEmpty(), "an impossible velocity yields no candidate (never a false lock)");
    }

    // ------------------------------------------------------------------ AnvilPlanner

    private static void anvilFromHeldItem() {
        EnchantModel model = VanillaModel.INSTANCE;
        String sword = "diamond_sword";
        java.util.function.Function<EnchantmentInstance, String> namer =
                e -> e.enchantment + " " + e.level;

        // A clean item still plans (baseline).
        AnvilPlanner.Plan clean = AnvilPlanner.plan(model, sword, "Diamond Sword", 0,
                Arrays.asList(new EnchantmentInstance("sharpness", 5), new EnchantmentInstance("unbreaking", 3)),
                namer, AnvilPlanner.TOO_EXPENSIVE);
        check(clean.possible, "clean item: Sharpness V + Unbreaking III plans");

        // Prior anvil work on the base item makes it cost more.
        AnvilPlanner.Plan used = AnvilPlanner.plan(model, sword, "Diamond Sword", 3, Collections.emptyList(),
                Arrays.asList(new EnchantmentInstance("sharpness", 5), new EnchantmentInstance("unbreaking", 3)),
                namer, Integer.MAX_VALUE);
        check(used.possible && used.totalLevels > clean.totalLevels,
                "prior anvil work raises the cost (" + clean.totalLevels + " -> " + used.totalLevels + ")");

        // A book that clashes with an enchantment already on the item is refused.
        AnvilPlanner.Plan clash = AnvilPlanner.plan(model, sword, "Diamond Sword", 0,
                Collections.singletonList(new EnchantmentInstance("sharpness", 3)),
                Collections.singletonList(new EnchantmentInstance("smite", 3)), namer, AnvilPlanner.TOO_EXPENSIVE);
        check(!clash.possible, "a book clashing with an existing enchantment is refused");

        // A book of an enchantment the item already has, at the same or lower level, is refused.
        AnvilPlanner.Plan dup = AnvilPlanner.plan(model, sword, "Diamond Sword", 0,
                Collections.singletonList(new EnchantmentInstance("unbreaking", 3)),
                Collections.singletonList(new EnchantmentInstance("unbreaking", 3)), namer, AnvilPlanner.TOO_EXPENSIVE);
        check(!dup.possible, "a duplicate enchantment at the same level is refused");

        // But a higher level of an existing enchantment is a valid upgrade.
        AnvilPlanner.Plan upgrade = AnvilPlanner.plan(model, sword, "Diamond Sword", 0,
                Collections.singletonList(new EnchantmentInstance("sharpness", 2)),
                Collections.singletonList(new EnchantmentInstance("sharpness", 5)), namer, AnvilPlanner.TOO_EXPENSIVE);
        check(upgrade.possible, "a higher level of an existing enchantment upgrades it");
    }

    // ------------------------------------------------------------------ drop ceiling

    private static void dropCeiling() {
        check(EnchantCalculator.DEFAULT_MAX_THROWS == 36 * 64,
                "drop search reaches a full inventory (36 x 64 = " + (36 * 64) + ")");
    }
}
