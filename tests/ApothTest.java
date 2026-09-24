import com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance;
import com.enchantmentcracker.game.Apotheosis;
import com.enchantmentcracker.game.Mc;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;

/** Loads the real Apotheosis jar and checks every member the bridge reflects on, then rolls a table. */
public class ApothTest {
  public static void main(String[] a) throws Exception {
    net.minecraft.util.registry.Bootstrap.func_151354_b();
    Method resolve = Apotheosis.class.getDeclaredMethod("resolve");
    resolve.setAccessible(true);
    resolve.invoke(null);
    Field available = Apotheosis.class.getDeclaredField("available");
    available.setAccessible(true);
    System.out.println("bridge resolved: " + available.get(null) + "  failure: " + Apotheosis.getFailure());
    if (!(Boolean) available.get(null)) System.exit(1);

    // Every member the bridge uses was found, with the expected types. Apotheosis's own
    // class initialiser needs a running mod loader, so only the level maths (which does not
    // touch it) can be exercised here; the enchantment pick is checked in game.
    for (String name : new String[]{"eternaField", "quantaField", "arcanaField", "rectificationField", "holderGet",
        "enchantmentCost", "selectEnchantment", "getEnchInfo", "infoMaxLevel", "infoMinLevel", "infoMinPower",
        "infoMaxPower", "absoluteMaxEterna", "enableEnch"}) {
      Field f = Apotheosis.class.getDeclaredField(name);
      f.setAccessible(true);
      System.out.println("  " + name + " -> " + f.get(null));
    }

    Constructor<Apotheosis.Stats> ctor = Apotheosis.Stats.class.getDeclaredConstructor(float.class, float.class, float.class, float.class, boolean.class);
    ctor.setAccessible(true);
    Apotheosis.Stats stats = ctor.newInstance(22.5F, 15F, 10F, 0F, true);
    Apotheosis.Table table = new Apotheosis.Table(stats);

    Class<?> helper = Class.forName("shadows.apotheosis.ench.table.RealEnchantmentHelper");
    Method cost = helper.getMethod("getEnchantmentCost", Random.class, int.class, float.class, net.minecraft.item.ItemStack.class);
    int checks = 0, fails = 0;
    Random seeder = new Random(3);
    for (int t = 0; t < 2000; t++) {
      int xp = seeder.nextInt();
      String item = new String[]{"diamond_sword", "diamond_pickaxe", "book", "bow", "iron_boots"}[t % 5];
      int[] mine = table.levels(xp, item);
      Random r = new Random(xp);
      net.minecraft.item.ItemStack stack = Mc.stackOf(item);
      for (int slot = 0; slot < 3; slot++) {
        int lv = (Integer) cost.invoke(null, r, slot, 22.5F, stack);
        if (lv < slot + 1) lv++;
        checks++;
        if (lv != mine[slot]) { fails++; if (fails < 5) System.out.println("level mismatch " + item + " " + slot); }
      }
      if (t < 5) System.out.println("  " + item + " levels at Eterna 22.5: " + java.util.Arrays.toString(mine));
    }
    System.out.println("checks " + checks + ", failures " + fails);
    System.exit(fails == 0 ? 0 : 1);
  }
}
