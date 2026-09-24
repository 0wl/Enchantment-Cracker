import com.enchantmentcracker.core.AnvilPlanner;
import com.enchantmentcracker.core.CrackEnchantments;
import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.core.CrackItems;
import com.enchantmentcracker.core.EnchantArea;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.EnchantModel;
import com.enchantmentcracker.core.Models;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.SimpleRandom;
import com.enchantmentcracker.core.TableSetup;
import com.enchantmentcracker.core.VanillaModel;
import com.enchantmentcracker.core.VanillaTable;
import com.enchantmentcracker.game.GameTables;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.RegistryModel;
import com.mojang.authlib.GameProfile;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentData;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.container.RepairContainer;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.IntReferenceHolder;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Tests the mod against Minecraft 1.16.5 / Forge 36.2.42's own code, bootstrapped in a
 * plain JVM: the registry model, the ported table maths, the game-code table, the planner
 * and the anvil planner.
 */
public class GameTest {

    static int checks;
    static int failures;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            if (failures <= 25) {
                System.out.println("  FAIL " + what);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        net.minecraft.util.registry.Bootstrap.func_151354_b(); // Bootstrap.register()
        System.out.println("bootstrapped in " + (System.currentTimeMillis() - start) + " ms");

        RegistryModel live = RegistryModel.build();
        testModels(live);
        testRoundingEdge();
        testTables(live);
        testPlannerWithGameCode(live);
        testAnvil(live);
        testArea();

        System.out.println();
        System.out.println("checks run: " + checks);
        System.out.println(failures == 0 ? "ALL PASS" : "FAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ models

    static List<String> allVanillaItems() {
        List<String> items = new ArrayList<>(VanillaModel.INSTANCE.enchantableItems());
        for (String extra : new String[]{"shears", "flint_and_steel", "carrot_on_a_stick", "elytra", "shield",
                "carved_pumpkin", "player_head", "stick", "cobblestone", "enchanted_book", "warped_fungus_on_a_stick"}) {
            items.add(extra);
        }
        return items;
    }

    static void testModels(RegistryModel live) {
        System.out.println("-- registry model vs hard-coded vanilla model");
        VanillaModel ref = VanillaModel.INSTANCE;
        check(live.allTableEnchantments().equals(ref.allTableEnchantments()),
                "table enchantment list " + live.allTableEnchantments() + " vs " + ref.allTableEnchantments());
        check(live.allEnchantments().equals(ref.allEnchantments()),
                "all enchantment list " + live.allEnchantments() + " vs " + ref.allEnchantments());

        List<String> items = allVanillaItems();
        for (String item : items) {
            check(live.enchantability(item) == ref.enchantability(item),
                    "enchantability " + item + ": " + live.enchantability(item) + " vs " + ref.enchantability(item));
            if (ref.enchantability(item) > 0) {
                check(live.tableCandidates(item).equals(ref.tableCandidates(item)),
                        "table candidates " + item + ": " + live.tableCandidates(item) + " vs " + ref.tableCandidates(item));
            }
            for (String enchantment : ref.allTableEnchantments()) {
                check(live.maxTableLevel(enchantment, item) == ref.maxTableLevel(enchantment, item),
                        "max table level " + enchantment + "/" + item);
            }
        }
        for (String enchantment : ref.allEnchantments()) {
            check(live.maxLevel(enchantment) == ref.maxLevel(enchantment), "max level " + enchantment);
            check(live.minLevel(enchantment) == ref.minLevel(enchantment), "min level " + enchantment);
            check(live.weight(enchantment) == ref.weight(enchantment), "weight " + enchantment);
            check(live.anvilMultiplier(enchantment) == ref.anvilMultiplier(enchantment), "anvil multiplier " + enchantment);
            for (int level = 1; level <= ref.maxLevel(enchantment) + 1; level++) {
                check(live.minEnchantability(enchantment, level) == ref.minEnchantability(enchantment, level),
                        "min enchantability " + enchantment + " " + level + ": "
                                + live.minEnchantability(enchantment, level) + " vs " + ref.minEnchantability(enchantment, level));
                check(live.maxEnchantability(enchantment, level) == ref.maxEnchantability(enchantment, level),
                        "max enchantability " + enchantment + " " + level + ": "
                                + live.maxEnchantability(enchantment, level) + " vs " + ref.maxEnchantability(enchantment, level));
            }
            for (String other : ref.allEnchantments()) {
                check(live.compatible(enchantment, other) == ref.compatible(enchantment, other),
                        "compatible " + enchantment + "/" + other + ": " + live.compatible(enchantment, other));
            }
            for (String item : items) {
                if (item.equals("enchanted_book") || item.equals("stick") || item.equals("cobblestone")) {
                    continue;
                }
                check(live.canApplyAtAnvil(enchantment, item) == ref.canApplyAtAnvil(enchantment, item),
                        "anvil " + enchantment + " on " + item + ": " + live.canApplyAtAnvil(enchantment, item)
                                + " vs " + ref.canApplyAtAnvil(enchantment, item));
            }
        }
    }

    // ------------------------------------------------------------------ table maths

    /** Minecraft's own code: EnchantmentContainer#onCraftMatrixChanged + getEnchantmentList. */
    static String gameRoll(int xpSeed, int shelves, ItemStack stack, int[] levelsOut) {
        Random rand = new Random(xpSeed);
        for (int slot = 0; slot < 3; slot++) {
            int level = EnchantmentHelper.func_77514_a(rand, slot, shelves, stack);
            levelsOut[slot] = level < slot + 1 ? 0 : level;
        }
        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < 3; slot++) {
            if (levelsOut[slot] <= 0) {
                sb.append("|");
                continue;
            }
            rand.setSeed(xpSeed + slot);
            List<EnchantmentData> list = EnchantmentHelper.func_77513_b(rand, stack, levelsOut[slot], false);
            if (stack.func_77973_b() == Items.field_151122_aG && list.size() > 1) {
                list.remove(rand.nextInt(list.size()));
            }
            for (EnchantmentData data : list) {
                sb.append(Mc.idOf(data.field_76302_b.getRegistryName())).append(':').append(data.field_76303_c).append(',');
            }
            sb.append("|");
        }
        return sb.toString();
    }

    static String setupRoll(TableSetup setup, int xpSeed, String item, int[] levelsOut) {
        int[] levels = setup.levels(xpSeed, item);
        System.arraycopy(levels, 0, levelsOut, 0, 3);
        StringBuilder sb = new StringBuilder();
        for (int slot = 0; slot < 3; slot++) {
            if (levels[slot] > 0) {
                for (EnchantmentInstance e : setup.enchantments(xpSeed, item, slot, levels[slot])) {
                    sb.append(e.enchantment).append(':').append(e.level).append(',');
                }
            }
            sb.append("|");
        }
        return sb.toString();
    }

    static void testTables(RegistryModel live) {
        List<String> items = VanillaModel.INSTANCE.enchantableItems();
        Random seeder = new Random(424242);
        int trials = 60000;

        for (int pass = 0; pass < 2; pass++) {
            Models.set(pass == 0 ? VanillaModel.INSTANCE : live);
            System.out.println("-- ported maths (" + (pass == 0 ? "vanilla model" : "registry model")
                    + ") and game-code table vs EnchantmentHelper, " + trials + " rolls");
            for (int trial = 0; trial < trials; trial++) {
                String item = items.get(seeder.nextInt(items.size()));
                int xpSeed = seeder.nextInt();
                int shelves = seeder.nextInt(16);
                ItemStack stack = Mc.stackOf(item);
                int[] gameLevels = new int[3];
                String expected = gameRoll(xpSeed, shelves, stack, gameLevels);

                int[] portLevels = new int[3];
                String port = setupRoll(new VanillaTable(shelves), xpSeed, item, portLevels);
                check(Arrays.equals(portLevels, gameLevels) && port.equals(expected),
                        "port " + item + " seed " + xpSeed + " shelves " + shelves + ": " + port + " vs " + expected);

                int[] codeLevels = new int[3];
                String code = setupRoll(GameTables.FACTORY.vanilla(shelves), xpSeed, item, codeLevels);
                check(Arrays.equals(codeLevels, gameLevels) && code.equals(expected),
                        "game table " + item + ": " + code + " vs " + expected);
            }
        }
        Models.set(VanillaModel.INSTANCE);
    }

    /**
     * Vanilla rounds the fuzzed level as round(L + L*f). The original tool did L + round(L*f).
     * Find real seeds where the two disagree, and check the port now follows the game there.
     */
    static void testRoundingEdge() {
        System.out.println("-- level rounding: round(L + L*f) vs L + round(L*f)");
        Models.set(VanillaModel.INSTANCE);
        int found = 0;
        List<String> items = VanillaModel.INSTANCE.enchantableItems();
        Random seeder = new Random(5);
        for (long attempt = 0; attempt < 400_000_000L && found < 25; attempt++) {
            int seed = seeder.nextInt();
            String item = items.get((int) (attempt % items.size()));
            int enchantability = CrackItems.getEnchantability(item);
            int base = 1 + seeder.nextInt(40);
            Random rand = new Random(seed);
            int level = base + 1 + rand.nextInt(enchantability / 4 + 1) + rand.nextInt(enchantability / 4 + 1);
            float f = (rand.nextFloat() + rand.nextFloat() - 1) * 0.15f;
            int vanilla = Math.round((float) level + (float) level * f);
            int old = level + Math.round(level * f);
            if (vanilla == old) {
                continue;
            }
            found++;
            // The two formulas differ for this roll: compare the full enchantment list with the game.
            ItemStack stack = Mc.stackOf(item);
            List<EnchantmentData> game = EnchantmentHelper.func_77513_b(new Random(seed), stack, base, false);
            List<EnchantmentInstance> port = CrackEnchantments.addRandomEnchantments(new Random(seed), item, base);
            StringBuilder a = new StringBuilder();
            for (EnchantmentData data : game) {
                a.append(Mc.idOf(data.field_76302_b.getRegistryName())).append(':').append(data.field_76303_c).append(',');
            }
            StringBuilder b = new StringBuilder();
            for (EnchantmentInstance e : port) {
                b.append(e.enchantment).append(':').append(e.level).append(',');
            }
            check(a.toString().equals(b.toString()), "edge case seed " + seed + " " + item + " L=" + base
                    + ": port " + b + " vs game " + a);
        }
        System.out.println("   formula disagreements found and checked: " + found);
    }

    // ------------------------------------------------------------------ planner

    static void testPlannerWithGameCode(RegistryModel live) {
        System.out.println("-- planner with game-code tables, replayed through EnchantmentHelper");
        Models.set(live);
        Models.setTables(GameTables.FACTORY);
        Random seeder = new Random(77);
        String[][] goals = {
                {"diamond_sword", "looting", "3"}, {"diamond_pickaxe", "fortune", "3"},
                {"diamond_boots", "feather_falling", "4"}, {"book", "sharpness", "5"}, {"bow", "power", "4"},
                {"iron_chestplate", "protection", "4"}, {"trident", "loyalty", "3"}, {"fishing_rod", "luck_of_the_sea", "3"}
        };
        int found = 0;
        for (int trial = 0; trial < 40; trial++) {
            String[] goal = goals[trial % goals.length];
            EnchantCalculator.Request request = new EnchantCalculator.Request();
            request.playerSeed = seeder.nextLong() & SimpleRandom.MASK;
            request.currentXpSeed = seeder.nextInt();
            request.item = goal[0];
            request.setups = EnchantCalculator.vanillaSetups(15, 12);
            request.playerLevel = 30;
            request.wanted = java.util.Collections.singletonList(new EnchantmentInstance(goal[1], Integer.parseInt(goal[2])));
            request.maxThrows = 1024;
            request.maxOptions = 3;
            List<EnchantCalculator.Result> results = EnchantCalculator.calculateOptions(request);
            for (EnchantCalculator.Result result : results) {
                found++;
                int xpSeed = result.needsDummy()
                        ? PlayerSeed.xpSeedOf(PlayerSeed.next(PlayerSeed.advance(request.playerSeed,
                        result.itemsToThrow * PlayerSeed.STEPS_PER_ITEM_DROP)))
                        : request.currentXpSeed;
                int[] levels = new int[3];
                String roll = gameRoll(xpSeed, result.bookshelves, Mc.stackOf(goal[0]), levels);
                String slot = roll.split("\\|", -1)[result.slot];
                check(levels[result.slot] == result.levelRequirement, "replayed level for " + goal[1]);
                boolean has = false;
                for (String part : slot.split(",")) {
                    if (part.startsWith(goal[1] + ":") && Integer.parseInt(part.substring(part.indexOf(':') + 1)) >= Integer.parseInt(goal[2])) {
                        has = true;
                    }
                }
                check(has, "game roll lacks " + goal[1] + " " + goal[2] + ": " + slot);
            }
        }
        System.out.println("   plans replayed: " + found);
        check(found > 60, "too few plans found: " + found);
        Models.set(VanillaModel.INSTANCE);
        Models.setTables(null);
    }

    // ------------------------------------------------------------------ anvil

    /**
     * A player without running its constructor: the bare bootstrap lacks Forge's attribute
     * registry, which LivingEntity's constructor needs. The anvil only reads the player's
     * abilities (creative or not), so that is the one field filled in.
     */
    static PlayerEntity fakePlayer() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        PlayerEntity player = (PlayerEntity) unsafe.allocateInstance(FakePlayer.class);
        field(PlayerEntity.class, "field_71075_bZ").set(player, new net.minecraft.entity.player.PlayerAbilities()); // abilities
        return player;
    }

    abstract static class FakePlayerBase extends PlayerEntity {
        FakePlayerBase() {
            super(null, BlockPos.field_177992_a, 0.0F, new GameProfile(UUID.randomUUID(), "test"));
        }
    }

    static final class FakePlayer extends FakePlayerBase {
        @Override
        public boolean func_175149_v() { // isSpectator
            return false;
        }

        @Override
        public boolean func_184812_l_() { // isCreative
            return false;
        }
    }

    static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Runs one real anvil operation; returns {cost, output} or cost -1 for no output. */
    static Object[] realAnvil(RepairContainer anvil, ItemStack left, ItemStack right) throws Exception {
        IInventory inputs = (IInventory) field(net.minecraft.inventory.container.AbstractRepairContainer.class, "field_234643_d_").get(anvil);
        IInventory output = (IInventory) field(net.minecraft.inventory.container.AbstractRepairContainer.class, "field_234642_c_").get(anvil);
        inputs.func_70299_a(0, left.func_77946_l());   // setInventorySlotContents, copy
        inputs.func_70299_a(1, right.func_77946_l());
        anvil.func_82848_d();                            // updateRepairOutput
        int cost = ((IntReferenceHolder) field(RepairContainer.class, "field_82854_e").get(anvil)).func_221495_b();
        ItemStack out = output.func_70301_a(0);
        return new Object[]{out.func_190926_b() ? -1 : cost, out.func_77946_l()};
    }

    static ItemStack book(Map<String, Integer> enchants) {
        ItemStack stack = new ItemStack(Items.field_151134_bR); // ENCHANTED_BOOK
        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            EnchantedBookItem.func_92115_a(stack, new EnchantmentData(Mc.enchantment(e.getKey()), e.getValue()));
        }
        return stack;
    }

    static void testAnvil(RegistryModel live) throws Exception {
        System.out.println("-- anvil planner replayed on a real RepairContainer");
        PlayerEntity player;
        RepairContainer anvil;
        try {
            player = fakePlayer();
            anvil = new RepairContainer(0, new PlayerInventory(player));
        } catch (Throwable t) {
            System.out.println("   SKIPPED: could not build an anvil here: " + t);
            return;
        }
        String[][] sets = {
                {"diamond_sword", "sharpness:5", "looting:3", "unbreaking:3", "mending:1", "fire_aspect:2", "sweeping:3", "knockback:2"},
                {"diamond_pickaxe", "efficiency:5", "fortune:3", "unbreaking:3", "mending:1"},
                {"diamond_chestplate", "protection:4", "unbreaking:3", "mending:1", "thorns:3"},
                {"bow", "power:5", "punch:2", "flame:1", "infinity:1", "unbreaking:3"},
                {"diamond_boots", "protection:4", "feather_falling:4", "depth_strider:3", "unbreaking:3", "mending:1", "soul_speed:3"},
                {"netherite_axe", "efficiency:5", "unbreaking:3", "sharpness:5", "silk_touch:1", "mending:1"},
        };
        int steps = 0;
        for (String[] set : sets) {
            String item = set[0];
            List<EnchantmentInstance> books = new ArrayList<>();
            for (int i = 1; i < set.length; i++) {
                String[] parts = set[i].split(":");
                books.add(new EnchantmentInstance(parts[0], Integer.parseInt(parts[1])));
            }
            AnvilPlanner.Plan plan = AnvilPlanner.plan(live, item, "ITEM", 0, books, b -> b.enchantment + " " + b.level,
                    AnvilPlanner.TOO_EXPENSIVE);
            check(plan.possible, "anvil plan impossible for " + item + ": " + plan.problem);
            if (!plan.possible) {
                continue;
            }
            // Replay: every label is a stack; single books start as fresh enchanted books.
            Map<String, ItemStack> stacks = new HashMap<>();
            stacks.put("ITEM", Mc.stackOf(item));
            for (EnchantmentInstance b : books) {
                Map<String, Integer> one = new HashMap<>();
                one.put(b.enchantment, b.level);
                stacks.put("Book: " + b.enchantment + " " + b.level, book(one));
            }
            int total = 0;
            for (AnvilPlanner.Step step : plan.steps) {
                ItemStack left = stacks.get(step.target);
                ItemStack right = stacks.get(step.sacrifice);
                check(left != null && right != null, "replay missing input " + step.target + " / " + step.sacrifice);
                if (left == null || right == null) {
                    break;
                }
                Object[] result = realAnvil(anvil, left, right);
                int cost = (Integer) result[0];
                check(cost == step.cost, item + ": step " + step.target + " + " + step.sacrifice
                        + " planned " + step.cost + ", anvil says " + cost);
                stacks.put(step.result, (ItemStack) result[1]);
                total += Math.max(cost, 0);
                steps++;
            }
            check(total == plan.totalLevels, item + ": total " + total + " vs planned " + plan.totalLevels);
            ItemStack finished = stacks.get("ITEM");
            Map<Enchantment, Integer> on = EnchantmentHelper.func_82781_a(finished); // getEnchantments
            for (EnchantmentInstance b : books) {
                Integer level = on.get(Mc.enchantment(b.enchantment));
                check(level != null && level == b.level, item + " ended without " + b.enchantment + " " + b.level);
            }
            check(finished.func_82838_A() == AnvilPlanner.penalty(plan.finalWork),
                    item + " repair cost " + finished.func_82838_A() + " vs work " + plan.finalWork);

            // And the plan must not lose to the naive "one book at a time" order.
            int naive = 0;
            ItemStack current = Mc.stackOf(item);
            for (EnchantmentInstance b : books) {
                Map<String, Integer> one = new HashMap<>();
                one.put(b.enchantment, b.level);
                Object[] result = realAnvil(anvil, current, book(one));
                int cost = (Integer) result[0];
                if (cost < 0) {
                    naive = Integer.MAX_VALUE;
                    break;
                }
                naive += cost;
                current = (ItemStack) result[1];
            }
            check(plan.totalLevels <= naive, item + ": planned " + plan.totalLevels + " worse than naive " + naive);
            System.out.println("   " + item + ": " + plan.totalLevels + " levels in " + plan.steps.size()
                    + " steps (one at a time: " + (naive == Integer.MAX_VALUE ? "too expensive" : String.valueOf(naive)) + ")");
        }
        System.out.println("   anvil steps replayed: " + steps);
    }

    // ------------------------------------------------------------------ area

    static void testArea() {
        System.out.println("-- enchanting area planner vs brute force");
        Random rand = new Random(11);
        for (int trial = 0; trial < 3000; trial++) {
            List<EnchantArea.Gap> gaps = new ArrayList<>();
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    List<EnchantArea.Shelf> shelves = new ArrayList<>();
                    for (int[] o : EnchantArea.shelfOffsets(dx, dz)) {
                        if (rand.nextInt(3) != 0) {
                            float power = rand.nextInt(10) == 0 ? 0.5F : 1.0F;
                            shelves.add(new EnchantArea.Shelf(o[0], o[1], o[2], power));
                        }
                    }
                    gaps.add(new EnchantArea.Gap(dx, dz, rand.nextInt(5) == 0, shelves));
                }
            }
            EnchantArea area = new EnchantArea(gaps);
            int target = rand.nextInt(16);
            EnchantArea.Adjustment adj = area.planFor(target);
            // Apply the adjustment and recount.
            boolean[] open = new boolean[gaps.size()];
            for (int i = 0; i < gaps.size(); i++) {
                EnchantArea.Gap gap = gaps.get(i);
                open[i] = (!gap.blocked || adj.unblock.contains(gap)) && !adj.block.contains(gap);
            }
            int power = Math.min(15, area.rawPower(open, adj.remove));
            if (adj.possible) {
                check(power == target, "area plan gives " + power + " not " + target);
            } else {
                // Impossible must really mean impossible: no subset of gaps and removals reaches it.
                check(Math.min(15, area.potentialPower()) < target || !reachable(area, target),
                        "area said impossible for " + target + " but potential " + area.potentialPower());
            }
        }
    }

    /** Can any combination of open gaps (all shelves kept, or some removed) make exactly target? */
    static boolean reachable(EnchantArea area, int target) {
        int n = area.gaps().size();
        for (int mask = 0; mask < (1 << n); mask++) {
            boolean[] open = new boolean[n];
            for (int i = 0; i < n; i++) {
                open[i] = (mask & (1 << i)) != 0;
            }
            if (Math.min(15, area.rawPower(open, java.util.Collections.emptyList())) >= target) {
                return true; // removing shelves one at a time walks down through every value
            }
        }
        return false;
    }
}
