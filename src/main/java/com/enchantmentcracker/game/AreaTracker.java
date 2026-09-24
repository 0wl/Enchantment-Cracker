package com.enchantmentcracker.game;

import com.enchantmentcracker.core.EnchantArea;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

/**
 * Finds the enchanting table you are standing at and reads its surroundings, so bookshelf
 * counts never have to be typed in.
 *
 * <p>Every half second it looks for the nearest table (preferring the one you last opened),
 * scans all eight directions, and knows both the count the table sees now and the most it
 * could see with every gap cleared. When a plan needs a different count, it works out the
 * fewest changes and outlines them in the world: red where something has to go in a gap,
 * green where a gap has to be cleared, orange for a shelf to take away.
 */
public final class AreaTracker {

    private static final int SCAN_INTERVAL = 10;
    private static final int SEARCH_RADIUS = 6;
    private static final int SEARCH_HEIGHT = 3;

    private static int ticks;
    private static BlockPos tablePos;
    private static EnchantArea area;

    /** Cached adjustment for the last target asked about. */
    private static int adjustmentTarget = -1;
    private static EnchantArea adjustmentArea;
    private static EnchantArea.Adjustment adjustment;

    private AreaTracker() {
    }

    public static void reset() {
        tablePos = null;
        area = null;
        adjustment = null;
        adjustmentArea = null;
        adjustmentTarget = -1;
    }

    /** Called every client tick while the feature is on. */
    public static void tick() {
        if (++ticks % SCAN_INTERVAL != 0 || Mc.player() == null) {
            return;
        }
        World world = Mc.world();
        BlockPos near = Mc.player().func_233580_cy_(); // getPosition()
        BlockPos pos = TableWatcher.getLastTablePos();
        if (pos == null || !BookshelfCounter.isEnchantingTable(world, pos) || distanceSq(pos, near) > 64) {
            pos = BookshelfCounter.findNearbyTable(world, near, SEARCH_RADIUS, SEARCH_HEIGHT);
        }
        tablePos = pos;
        area = pos == null ? null : BookshelfCounter.scan(world, pos);
        if (pos != null && TableWatcher.getLastTablePos() == null) {
            TableWatcher.setLastTablePos(pos);
        }
    }

    private static double distanceSq(BlockPos a, BlockPos b) {
        double dx = a.func_177958_n() - b.func_177958_n(); // getX
        double dy = a.func_177956_o() - b.func_177956_o(); // getY
        double dz = a.func_177952_p() - b.func_177952_p(); // getZ
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean hasTable() {
        return area != null;
    }

    public static BlockPos tablePos() {
        return tablePos;
    }

    public static EnchantArea area() {
        return area;
    }

    /** What the table sees now, or -1 with no table nearby. */
    public static int currentShelves() {
        return area == null ? -1 : area.currentPower();
    }

    /** The most this setup can give with every gap cleared, or -1. */
    public static int potentialShelves() {
        return area == null ? -1 : area.potentialPower();
    }

    /** The changes that make the table read {@code target}; null with no table nearby. */
    public static EnchantArea.Adjustment adjustmentFor(int target) {
        if (area == null || target < 0) {
            return null;
        }
        if (target != adjustmentTarget || area != adjustmentArea) {
            adjustment = area.planFor(target);
            adjustmentTarget = target;
            adjustmentArea = area;
        }
        return adjustment;
    }

    /** One line saying what to do, for the plan and the table overlay. */
    public static String describe(EnchantArea.Adjustment adj) {
        if (adj == null) {
            return "no table nearby";
        }
        if (!adj.possible) {
            return "only " + adj.resultingPower + " possible here";
        }
        if (adj.nothingToDo()) {
            return "already set";
        }
        StringBuilder sb = new StringBuilder();
        if (!adj.block.isEmpty()) {
            sb.append("block ").append(adj.block.size()).append(" gap").append(adj.block.size() == 1 ? "" : "s");
        }
        if (!adj.unblock.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("clear ").append(adj.unblock.size()).append(" gap").append(adj.unblock.size() == 1 ? "" : "s");
        }
        if (!adj.remove.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("remove ").append(adj.remove.size()).append(" shel").append(adj.remove.size() == 1 ? "f" : "ves");
        }
        return sb + " (outlined)";
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Outlines the changes needed to reach {@code target}, plus a faint box round the table
     * so you can see which one was detected.
     */
    public static void render(MatrixStack ms, int target) {
        if (area == null || tablePos == null) {
            return;
        }
        Vector3d camera = Mc.cameraPos();
        IRenderTypeBuffer.Impl buffers = Mc.bufferSource();
        IVertexBuilder lines = buffers.getBuffer(RenderType.func_228659_m_()); // RenderType.getLines()

        ms.func_227860_a_(); // push
        ms.func_227861_a_(-camera.field_72450_a, -camera.field_72448_b, -camera.field_72449_c); // translate(-x, -y, -z)

        int tx = tablePos.func_177958_n();
        int ty = tablePos.func_177956_o();
        int tz = tablePos.func_177952_p();
        box(ms, lines, tx, ty, tz, 0.0F, 0.8F, 1.0F, 0.6F);

        EnchantArea.Adjustment adj = target >= 0 ? adjustmentFor(target) : null;
        if (adj != null && adj.possible) {
            for (EnchantArea.Gap gap : adj.block) {
                // Anything in the lower gap block closes the whole direction.
                box(ms, lines, tx + gap.dx, ty, tz + gap.dz, 1.0F, 0.2F, 0.2F, 1.0F);
            }
            World world = Mc.world();
            for (EnchantArea.Gap gap : adj.unblock) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = tablePos.func_177982_a(gap.dx, dy, gap.dz);
                    if (!world.func_175623_d(pos)) { // isAirBlock
                        box(ms, lines, tx + gap.dx, ty + dy, tz + gap.dz, 0.2F, 1.0F, 0.2F, 1.0F);
                    }
                }
            }
            for (EnchantArea.Shelf shelf : adj.remove) {
                box(ms, lines, tx + shelf.dx, ty + shelf.dy, tz + shelf.dz, 1.0F, 0.6F, 0.0F, 1.0F);
            }
        }

        ms.func_227865_b_(); // pop
        buffers.func_228462_a_(RenderType.func_228659_m_()); // finish(RenderType.getLines())
    }

    private static void box(MatrixStack ms, IVertexBuilder lines, int x, int y, int z,
                            float r, float g, float b, float a) {
        double grow = 0.004;
        // WorldRenderer.drawBoundingBox(ms, builder, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a)
        WorldRenderer.func_228427_a_(ms, lines, x - grow, y - grow, z - grow,
                x + 1 + grow, y + 1 + grow, z + 1 + grow, r, g, b, a);
    }
}
