package com.enchantmentcracker.game;

import com.enchantmentcracker.core.EnchantArea;
import net.minecraft.block.BlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the enchanting power around a table, client side.
 *
 * <p>Vanilla does this on the server in {@code EnchantmentContainer#onCraftMatrixChanged},
 * and the result never reaches the client. The client does have the blocks loaded though,
 * so we can run the identical scan ourselves: for each of the eight horizontal neighbours,
 * if both blocks beside the table are air, add up the enchanting power of the shelf
 * positions two blocks out (plus the in-between slots for the diagonals).
 */
public final class BookshelfCounter {

    private static final ResourceLocation ENCHANTING_TABLE =
            new ResourceLocation("minecraft", "enchanting_table");

    /** Vanilla clamps power to 15 before using it. */
    public static final int MAX_POWER = EnchantArea.MAX_POWER;

    private BookshelfCounter() {
    }

    /**
     * True for the vanilla table and for anything that replaces it under the same name
     * (Apotheosis does), plus modded tables whose name says what they are.
     */
    public static boolean isEnchantingTable(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.func_180495_p(pos); // world.getBlockState(pos)
        ResourceLocation name = state.func_177230_c().getRegistryName(); // state.getBlock()
        return name != null && (ENCHANTING_TABLE.equals(name) || name.func_110623_a().endsWith("enchanting_table"));
    }

    /**
     * @return the bookshelf count the server would compute for this table, clamped to 0..15,
     *         or -1 if the position is not a loaded enchanting table.
     */
    public static int count(World world, BlockPos pos) {
        EnchantArea area = scan(world, pos);
        return area == null ? -1 : area.currentPower();
    }

    /**
     * Reads every direction around the table: whether its gap is clear, and the power of each
     * shelf position behind it, in vanilla's visiting order. Null if there is no table here.
     */
    public static EnchantArea scan(World world, BlockPos pos) {
        if (!isEnchantingTable(world, pos)) {
            return null;
        }
        List<EnchantArea.Gap> gaps = new ArrayList<>();
        // Vanilla's loop: dz (k) outer, dx (l) inner.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                boolean blocked = !isAir(world, pos.func_177982_a(dx, 0, dz)) // pos.add
                        || !isAir(world, pos.func_177982_a(dx, 1, dz));
                List<EnchantArea.Shelf> shelves = new ArrayList<>();
                for (int[] offset : EnchantArea.shelfOffsets(dx, dz)) {
                    float power = enchantPower(world, pos.func_177982_a(offset[0], offset[1], offset[2]));
                    if (power != 0.0F) {
                        shelves.add(new EnchantArea.Shelf(offset[0], offset[1], offset[2], power));
                    }
                }
                gaps.add(new EnchantArea.Gap(dx, dz, blocked, shelves));
            }
        }
        return new EnchantArea(gaps);
    }

    /** Finds the enchanting table the player is most likely standing at. */
    public static BlockPos findNearbyTable(World world, BlockPos around) {
        return findNearbyTable(world, around, 5, 3);
    }

    public static BlockPos findNearbyTable(World world, BlockPos around, int radius, int height) {
        if (world == null || around == null) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -height; dy <= height; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    BlockPos pos = around.func_177982_a(dx, dy, dz);
                    if (!isEnchantingTable(world, pos)) {
                        continue;
                    }
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isAir(World world, BlockPos pos) {
        return world.func_175623_d(pos); // world.isAirBlock(pos)
    }

    private static float enchantPower(World world, BlockPos pos) {
        // Forge's hook, so modded shelves count exactly as they do on the server.
        return world.func_180495_p(pos).getEnchantPowerBonus(world, pos);
    }
}
