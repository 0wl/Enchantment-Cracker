package com.enchantmentcracker.client;

import com.enchantmentcracker.game.Mc;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's key bindings.
 *
 * <p>Registering them here is what makes them show up in Options → Controls under their own
 * "Enchantment Cracker" heading, so they can be rebound like any vanilla key.
 *
 * <p>Presses are matched against the binding directly ({@link #matchesKey}) rather than
 * through {@code KeyBinding#isPressed()}. The game only credits a press to <em>one</em>
 * binding per key, so with the key shared (X, for instance, is vanilla's "Load Hotbar
 * Activator" and is popular with mods) {@code isPressed()} would never fire for ours. Matching
 * directly works however crowded the key is.
 */
public final class ModKeyBindings {

    public static final String CATEGORY = "key.categories.enchcracker";

    /** Opens the main tabbed GUI. Default K, which vanilla leaves free. */
    public static KeyBinding openGui;
    /** Snapshots the open enchanting table into the cracker without opening the GUI. */
    public static KeyBinding capture;
    /** Jumps straight to the calculator with the table's item and shelves filled in. */
    public static KeyBinding quickPlan;
    /** In any inventory: makes the hovered item the auto-drop junk. */
    public static KeyBinding pickJunk;
    /** Drops the junk the current plan still needs. Unbound by default. */
    public static KeyBinding autoDrop;
    /** Opens the enchantment search. Unbound by default. */
    public static KeyBinding search;

    private static final List<KeyBinding> ALL = new ArrayList<>();

    private ModKeyBindings() {
    }

    public static void register() {
        openGui = add(new KeyBinding("key.enchcracker.open", GLFW.GLFW_KEY_K, CATEGORY));
        capture = add(new KeyBinding("key.enchcracker.capture", GLFW.GLFW_KEY_N, CATEGORY));
        quickPlan = add(new KeyBinding("key.enchcracker.quickplan", GLFW.GLFW_KEY_J, CATEGORY));
        pickJunk = add(new KeyBinding("key.enchcracker.pickjunk", GLFW.GLFW_KEY_G, CATEGORY));
        autoDrop = add(new KeyBinding("key.enchcracker.autodrop", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        search = add(new KeyBinding("key.enchcracker.search", GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
    }

    private static KeyBinding add(KeyBinding binding) {
        ClientRegistry.registerKeyBinding(binding);
        ALL.add(binding);
        return binding;
    }

    public static List<KeyBinding> all() {
        return ALL;
    }

    /** True when this keyboard event is this binding (and its modifier, if it has one). */
    public static boolean matchesKey(KeyBinding binding, int keyCode, int scanCode) {
        if (binding == null || binding.func_197986_j()) { // isInvalid() — unbound
            return false;
        }
        // InputMappings.getInputByCode(keyCode, scanCode)
        return binding.isActiveAndMatches(InputMappings.func_197954_a(keyCode, scanCode));
    }

    /**
     * A key press in the world, with the sharing rules applied:
     * <ul>
     *     <li>a binding without a modifier does not fire while Ctrl or Alt is held, so another
     *     mod's Ctrl+X is left alone;</li>
     *     <li>the Open key always fires, even when the key is shared (that is the point: X is
     *     vanilla's Load Hotbar Activator and is popular with mods);</li>
     *     <li>the other keys fire on a shared key only when the game credited the press to
     *     them, so pressing J for a map mod does not also open the calculator.</li>
     * </ul>
     */
    public static boolean pressedInWorld(KeyBinding binding, boolean matches, int modifiers) {
        if (!matches) {
            return false;
        }
        boolean ctrlOrAlt = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) != 0;
        if (ctrlOrAlt && binding.getKeyModifier() == net.minecraftforge.client.settings.KeyModifier.NONE) {
            return false;
        }
        if (binding == openGui || conflictsOf(binding).isEmpty()) {
            return true;
        }
        return binding.func_151468_f(); // isPressed(): the game gave this press to us
    }

    /** True when this mouse button is this binding. */
    public static boolean matchesMouse(KeyBinding binding, int button) {
        if (binding == null || binding.func_197986_j()) {
            return false;
        }
        // InputMappings.Type.MOUSE.getOrMakeInput(button)
        return binding.isActiveAndMatches(InputMappings.Type.MOUSE.func_197944_a(button));
    }

    /**
     * Drains presses the game may have credited to our bindings, so a stale count never
     * fires later. Harmless when it credited them elsewhere.
     */
    public static void drain() {
        for (KeyBinding binding : ALL) {
            while (binding.func_151468_f()) { // isPressed()
                // discard
            }
        }
    }

    /** The key a binding is on, as the Controls screen shows it. */
    public static String keyName(KeyBinding binding) {
        if (binding == null) {
            return "?";
        }
        if (binding.func_197986_j()) {
            return "unbound";
        }
        // keyBinding.getTranslatedKeyMessage().getString()
        return binding.func_238171_j_().getString();
    }

    /** Names of other bindings sharing this one's key, for the conflict warning. */
    public static List<String> conflictsOf(KeyBinding binding) {
        List<String> out = new ArrayList<>();
        if (binding == null || binding.func_197986_j()) {
            return out;
        }
        for (KeyBinding other : Mc.settings().field_74324_K) { // gameSettings.keyBindings
            if (other == binding || other.func_197986_j()) {
                continue;
            }
            if (other.getKey().equals(binding.getKey()) && other.getKeyModifier() == binding.getKeyModifier()) {
                out.add(Mc.translate(other.func_151464_g())); // getKeyDescription()
            }
        }
        return out;
    }
}
