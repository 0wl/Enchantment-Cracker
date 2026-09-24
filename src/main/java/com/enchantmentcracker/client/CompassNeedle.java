package com.enchantmentcracker.client;

import com.enchantmentcracker.EnchantmentCrackerMod;
import com.enchantmentcracker.game.Mc;
import net.minecraft.item.IItemPropertyGetter;
import net.minecraft.item.Item;
import net.minecraft.item.ItemModelsProperties;
import net.minecraft.util.ResourceLocation;

import java.lang.reflect.Method;

/**
 * Easter egg: while the Plan tab's compass icon is drawn, its real needle points at the mouse
 * cursor. Rather than draw a line over the item, this swaps the compass model's {@code angle}
 * property for one that returns the cursor angle while we render the icon and the vanilla value
 * the rest of the time — so actual compasses in the world are untouched.
 *
 * <p>All of it is guarded reflection: if a mapping ever changes, the easter egg simply switches
 * itself off instead of breaking compass rendering.
 */
public final class CompassNeedle {

    private static volatile boolean active;
    private static volatile float angle;

    private CompassNeedle() {
    }

    /** Aim the needle from the icon centre {@code (cx, cy)} at the cursor, for the next item draw. */
    public static void aimAt(int cx, int cy, int mouseX, int mouseY) {
        double a = Math.atan2(mouseY - cy, mouseX - cx) / (Math.PI * 2.0) + 0.25; // 0 = up, clockwise
        angle = (float) (a - Math.floor(a));
        active = true;
    }

    public static void clear() {
        active = false;
    }

    /**
     * Installs the override. Call once during client setup, on the client only.
     */
    public static void register() {
        try {
            Item compass = Mc.item("compass");
            if (compass == null) {
                return;
            }
            ResourceLocation angleId = new ResourceLocation("angle");
            // getProperty(compass, "angle") — public: the vanilla getter we delegate to when idle.
            IItemPropertyGetter original = ItemModelsProperties.func_239417_a_(compass, angleId);
            IItemPropertyGetter wrapper = (stack, world, entity) ->
                    active ? angle : (original == null ? 0.0F : original.call(stack, world, entity));
            // registerProperty(item, name, getter) is private; reach it reflectively.
            Method register = ItemModelsProperties.class.getDeclaredMethod(
                    "func_239418_a_", Item.class, ResourceLocation.class, IItemPropertyGetter.class);
            register.setAccessible(true);
            register.invoke(null, compass, angleId, wrapper);
        } catch (Throwable t) {
            EnchantmentCrackerMod.LOGGER.warn("Compass needle easter egg unavailable", t);
        }
    }
}
