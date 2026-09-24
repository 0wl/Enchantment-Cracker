package com.enchantmentcracker.client;

import com.enchantmentcracker.EnchantmentCrackerMod;
import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.EnchantTablePrediction;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantCalculator;
import com.enchantmentcracker.core.Models;
import com.enchantmentcracker.game.AreaTracker;
import com.enchantmentcracker.game.AutoDropper;
import com.enchantmentcracker.game.GameTables;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.RegistryModel;
import com.enchantmentcracker.game.TableWatcher;
import com.enchantmentcracker.game.WorldProfiles;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.WinGameScreen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.gui.screen.inventory.InventoryScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.GuiContainerEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

/**
 * Wires the mod into the game: keeps the seed up to date, handles the key binds, decorates
 * the enchanting table and inventory screens, and keeps per-world memory.
 */
public final class ClientEvents {

    /** Where we are, fixed at login so it can still be saved after the connection is gone. */
    private static String worldKey;
    /** Set when the death or end-credits screen opens: the next respawn is a new player entity. */
    private static boolean newEntityPending;
    private static String newEntityReason;

    private static Field pressTimeField;
    private static boolean pressTimeBroken;

    /** The plan stage last announced in chat, so each step is announced once. */
    private static CrackerState.PlanStage announcedStage = CrackerState.PlanStage.NONE;
    private static EnchantCalculator.Result announcedPlan;

    /** Our buttons on the screen currently open, repositioned every frame. */
    private static Widgets.McButton pickButton;
    private static Screen pickButtonScreen;

    private ClientEvents() {
    }

    // ------------------------------------------------------------------ per-tick work

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (Mc.player() == null) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            countDropKeyPresses();
            return;
        }

        ModKeyBindings.drain();
        TableWatcher.syncFromWorld();
        TableWatcher.tick();
        if (ModSettings.autoDetectArea) {
            AreaTracker.tick();
        }
        if (ModSettings.autoDrop) {
            AutoDropper.setPerTick(ModSettings.dropsPerTick);
            AutoDropper.tick();
        } else if (AutoDropper.isRunning()) {
            AutoDropper.stop();
        }

        announcePlanProgress();

        // Keep the calculator's level in step with the player, unless they are editing it.
        if (!(Mc.currentScreen() instanceof CrackerScreen)) {
            CrackerState.get().setPlayerLevel(TableWatcher.playerLevel());
        }
    }

    /**
     * Says in chat when a plan step completes, so the window does not have to be open to
     * follow along: drops counted, dummy enchanted (and whether the table landed on the
     * planned seed), final enchantment done.
     */
    private static void announcePlanProgress() {
        CrackerState state = CrackerState.get();
        EnchantCalculator.Result plan = state.getPlan();
        CrackerState.PlanStage stage = state.getPlanStage();
        if (plan != announcedPlan) {
            // A new plan: remember where it starts, announce nothing yet.
            announcedPlan = plan;
            announcedStage = stage;
            return;
        }
        if (stage == announcedStage || plan == null) {
            return;
        }
        announcedStage = stage;
        String note = com.enchantmentcracker.client.gui.tabs.PlanTab.stageNote(state, plan);
        if (note == null || stage == CrackerState.PlanStage.DROPPING) {
            return;
        }
        boolean bad = stage == CrackerState.PlanStage.OVERSHOT || stage == CrackerState.PlanStage.OFF_COURSE;
        Mc.chat((bad ? "§c" : "§a") + "[Cracker] §f" + note);
    }

    /**
     * As a LAN guest or on a server the drop event only happens on the server, so the one
     * kind of drop the client never hears about is pressing Q with no screen open. Read the
     * drop key's pending presses just before the game processes them, and count what they
     * will throw: one item per press, or the whole stack once with Ctrl.
     */
    private static void countDropKeyPresses() {
        if (Mc.integratedServer() != null || Mc.currentScreen() != null || Mc.isSpectator() || pressTimeBroken) {
            return;
        }
        KeyBinding drop = Mc.settings().field_74316_C; // gameSettings.keyBindDrop
        int presses;
        try {
            if (pressTimeField == null) {
                pressTimeField = KeyBinding.class.getDeclaredField("field_151474_i"); // pressTime
                pressTimeField.setAccessible(true);
            }
            presses = pressTimeField.getInt(drop);
        } catch (Throwable t) {
            pressTimeBroken = true;
            EnchantmentCrackerMod.LOGGER.warn("Cannot watch the drop key; server-side drops will not be counted", t);
            return;
        }
        if (presses <= 0) {
            return;
        }
        ItemStack held = Mc.heldStack();
        if (held.func_190926_b()) {
            return;
        }
        int drops = Screen.func_231172_r_() ? 1 : Math.min(presses, held.func_190916_E()); // hasControlDown, getCount
        for (int i = 0; i < drops; i++) {
            CrackerState.get().onItemDropped();
        }
    }

    /**
     * Every dropped stack spends four {@code nextFloat()} calls on the player's RNG.
     *
     * <p>In your own world (singleplayer or hosting LAN) the authoritative event fires on the
     * integrated server, in this same process, and that is the one counted. Elsewhere the
     * server's event never reaches us, but inventory drops (Q over a slot, dragging out of
     * the window, and the auto-dropper) are simulated on the client first and fire the same
     * event there, so those are counted instead.
     */
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        PlayerEntity thrower = event.getPlayer();
        PlayerEntity me = Mc.player();
        if (thrower == null || me == null) {
            return;
        }
        boolean clientSide = thrower.field_70170_p.field_72995_K; // world.isRemote
        boolean ownWorld = Mc.integratedServer() != null;
        if (ownWorld) {
            if (clientSide || !thrower.func_110124_au().equals(me.func_110124_au())) { // getUniqueID
                return;
            }
        } else if (!clientSide || thrower != me) {
            return;
        }
        CrackerState.get().onItemDropped();
    }

    /** Remembers which enchanting table was opened, so the bookshelf scan looks at the right one. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getWorld() != null && event.getWorld().field_72995_K) {
            TableWatcher.rememberTable(event.getWorld(), event.getPos());
        }
    }

    // ------------------------------------------------------------------ sessions

    @SubscribeEvent
    public static void onLoggedIn(ClientPlayerNetworkEvent.LoggedInEvent event) {
        // The registries (and their id order) are final for this session now.
        GameTables.clearCache();
        try {
            Models.set(RegistryModel.build());
            Models.setTables(GameTables.FACTORY);
        } catch (Throwable t) {
            EnchantmentCrackerMod.LOGGER.error("Could not read the enchantment registry; using vanilla data", t);
            Models.set(null);
            Models.setTables(null);
        }
        TableWatcher.reset();
        AreaTracker.reset();
        AutoDropper.stop();

        CrackerState state = CrackerState.get();
        state.resetSeed();
        state.onNewPlayerEntity("Joined " + WorldProfiles.describeCurrent());
        worldKey = WorldProfiles.currentKey();
        if (ModSettings.rememberPerWorld && worldKey != null) {
            // After the reset: brings back the setup, and the saved XP seed as the baseline
            // to watch, so the first enchantment is recognised as the first on the new generator.
            state.importProfile(WorldProfiles.load(worldKey));
        }
        newEntityPending = false;
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        saveProfile();
        worldKey = null;
        AutoDropper.stop();
        TableWatcher.reset();
        AreaTracker.reset();
    }

    /** Saves this world's memory, if that is switched on. */
    public static void saveProfile() {
        if (ModSettings.rememberPerWorld && worldKey != null) {
            WorldProfiles.save(worldKey, CrackerState.get().exportProfile());
        }
    }

    public static String getWorldKey() {
        return worldKey;
    }

    @SubscribeEvent
    public static void onRespawn(ClientPlayerNetworkEvent.RespawnEvent event) {
        // The client re-creates its player on every dimension change too, but the server only
        // makes a new one (with a new generator) after death or the end credits.
        if (newEntityPending) {
            newEntityPending = false;
            CrackerState.get().onNewPlayerEntity(newEntityReason);
        }
    }

    @SubscribeEvent
    public static void onGuiOpen(GuiOpenEvent event) {
        Screen next = event.getGui();
        if (next instanceof DeathScreen) {
            newEntityPending = true;
            newEntityReason = "You respawned";
        } else if (next instanceof WinGameScreen) {
            newEntityPending = true;
            newEntityReason = "You left the End";
        }
        if (Mc.currentScreen() instanceof CrackerScreen && !(next instanceof CrackerScreen)) {
            saveProfile();
        }
        AutoDropper.setPickArmed(false);
    }

    // ------------------------------------------------------------------ key bindings

    /** Keys pressed with no screen open. */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS || Mc.player() == null || Mc.currentScreen() != null) {
            return;
        }
        handleWorldKey(binding -> ModKeyBindings.pressedInWorld(binding,
                ModKeyBindings.matchesKey(binding, event.getKey(), event.getScanCode()), event.getModifiers()));
    }

    /** The same, for bindings someone put on a mouse button. */
    @SubscribeEvent
    public static void onMouseInput(InputEvent.MouseInputEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS || Mc.player() == null || Mc.currentScreen() != null) {
            return;
        }
        handleWorldKey(binding -> ModKeyBindings.pressedInWorld(binding,
                ModKeyBindings.matchesMouse(binding, event.getButton()), event.getMods()));
    }

    private static void handleWorldKey(java.util.function.Predicate<KeyBinding> pressed) {
        if (pressed.test(ModKeyBindings.openGui)) {
            CrackerScreen.open();
        } else if (pressed.test(ModKeyBindings.quickPlan)) {
            CrackerScreen.open(CrackerScreen.Tab.CALCULATOR);
        } else if (pressed.test(ModKeyBindings.search)) {
            CrackerScreen.open(CrackerScreen.Tab.SEARCH);
        } else if (pressed.test(ModKeyBindings.capture)) {
            CrackerScreen.captureFromTable();
        } else if (pressed.test(ModKeyBindings.autoDrop)) {
            startPlanDrops();
        }
    }

    /** Keys pressed inside inventories and the enchanting table. */
    @SubscribeEvent
    public static void onScreenKey(GuiScreenEvent.KeyboardKeyPressedEvent.Pre event) {
        Screen screen = event.getGui();
        int key = event.getKeyCode();
        int scan = event.getScanCode();

        if (screen instanceof ContainerScreen && ModSettings.autoDrop
                && ModKeyBindings.matchesKey(ModKeyBindings.pickJunk, key, scan)) {
            ContainerScreen<?> container = (ContainerScreen<?>) screen;
            if (pickSlot(container, container.getSlotUnderMouse())) {
                event.setCanceled(true);
                return;
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && AutoDropper.isPickArmed()) {
            AutoDropper.setPickArmed(false);
            event.setCanceled(true);
            return;
        }

        if (!TableWatcher.isEnchantingScreen(screen)) {
            return;
        }
        if (ModKeyBindings.matchesKey(ModKeyBindings.openGui, key, scan)) {
            CrackerScreen.open();
            event.setCanceled(true);
        } else if (ModKeyBindings.matchesKey(ModKeyBindings.quickPlan, key, scan)) {
            CrackerScreen.openFromTable();
            event.setCanceled(true);
        } else if (ModKeyBindings.matchesKey(ModKeyBindings.search, key, scan)) {
            CrackerScreen.open(CrackerScreen.Tab.SEARCH);
            event.setCanceled(true);
        } else if (ModKeyBindings.matchesKey(ModKeyBindings.capture, key, scan)) {
            CrackerScreen.captureFromTable();
            event.setCanceled(true);
        } else if (ModKeyBindings.matchesKey(ModKeyBindings.autoDrop, key, scan)) {
            startPlanDrops();
            event.setCanceled(true);
        }
    }

    // --------------------------------------------------------- junk picking & dropping

    private static boolean pickSlot(ContainerScreen<?> screen, Slot slot) {
        if (slot == null || !slot.func_75216_d()) { // getHasStack
            return false;
        }
        AutoDropper.pick(screen.func_212873_a_(), slot); // getContainer()
        ModSettings.junkItem = AutoDropper.getJunkItem();
        ModSettings.save();
        return true;
    }

    /** Drops what the active plan still needs. */
    public static void startPlanDrops() {
        if (!ModSettings.autoDrop) {
            Mc.chat("§7[Cracker] Auto-drop is switched off in Settings.");
            return;
        }
        if (AutoDropper.isRunning()) {
            AutoDropper.stop();
            return;
        }
        CrackerState state = CrackerState.get();
        EnchantCalculator.Result plan = state.getPlan();
        if (plan == null || !plan.needsDummy()) {
            Mc.chat("§7[Cracker] The current plan has nothing to drop.");
            return;
        }
        int remaining = state.getDropsRemaining();
        if (remaining <= 0) {
            Mc.chat("§a[Cracker] Already dropped enough. Now enchant your junk item once.");
            return;
        }
        AutoDropper.start(remaining);
    }

    @SubscribeEvent
    public static void onMouseClicked(GuiScreenEvent.MouseClickedEvent.Pre event) {
        if (!AutoDropper.isPickArmed() || !(event.getGui() instanceof ContainerScreen)) {
            return;
        }
        ContainerScreen<?> screen = (ContainerScreen<?>) event.getGui();
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            AutoDropper.setPickArmed(false);
            event.setCanceled(true);
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && slot != null) {
            // Choosing, not moving: swallow the click so the item stays where it is.
            if (!pickSlot(screen, slot)) {
                AutoDropper.setPickArmed(false);
            }
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------- screen decoration

    @SubscribeEvent
    public static void onScreenInit(GuiScreenEvent.InitGuiEvent.Post event) {
        Screen screen = event.getGui();
        pickButton = null;
        pickButtonScreen = null;

        if (TableWatcher.isEnchantingScreen(screen)) {
            ContainerScreen<?> table = (ContainerScreen<?>) screen;
            TableButtons.add(event, table);
        }
        if (ModSettings.autoDrop && (screen instanceof InventoryScreen || TableWatcher.isEnchantingScreen(screen))) {
            pickButton = new Widgets.McButton(0, 0, 62, 12, "Pick junk", () -> {
                AutoDropper.setPickArmed(!AutoDropper.isPickArmed());
            }).labelFrom(() -> AutoDropper.isPickArmed() ? "Click item..." : "Pick junk")
                    .selectedWhen(AutoDropper::isPickArmed)
                    .tooltip("Choose the junk item to auto-drop:",
                            "press this, then click any item",
                            "(or hover it and press the",
                            "'Pick auto-drop item' key).");
            pickButtonScreen = screen;
            positionPickButton((ContainerScreen<?>) screen);
            event.addWidget(pickButton);
        }
    }

    /** Sits on the top edge of the window, right-aligned, and follows the recipe book around. */
    private static void positionPickButton(ContainerScreen<?> screen) {
        if (pickButton == null) {
            return;
        }
        pickButton.setPosition(screen.getGuiLeft() + screen.getXSize() - pickButton.w(), screen.getGuiTop() - 13);
    }

    @SubscribeEvent
    public static void onScreenDrawPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (pickButton != null && event.getGui() == pickButtonScreen && event.getGui() instanceof ContainerScreen) {
            positionPickButton((ContainerScreen<?>) event.getGui());
        }
        if (TableWatcher.isEnchantingScreen(event.getGui())) {
            TableButtons.reposition((ContainerScreen<?>) event.getGui());
        }
    }

    @SubscribeEvent
    public static void onScreenDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        Screen screen = event.getGui();
        if (AutoDropper.isPickArmed() && screen instanceof ContainerScreen) {
            ContainerScreen<?> container = (ContainerScreen<?>) screen;
            String hint = "Click the item to use as junk (right-click to cancel)";
            Mc.centeredShadowText(event.getMatrixStack(), hint,
                    container.getGuiLeft() + container.getXSize() / 2, container.getGuiTop() - 24, 0xFFE0B0FF);
        }
        EnchantmentContainer table = TableWatcher.enchantingContainerOf(screen);
        if (table != null) {
            EnchantTablePrediction.render((ContainerScreen<?>) screen, table,
                    event.getMatrixStack(), event.getMouseX(), event.getMouseY());
        }

        // Vanilla screens do not draw widget tooltips, so draw ours.
        java.util.List<Widgets.McButton> ours = new java.util.ArrayList<>();
        if (table != null) {
            ours.addAll(TableButtons.buttons());
        }
        if (pickButton != null && screen == pickButtonScreen) {
            ours.add(pickButton);
        }
        for (Widgets.McButton button : ours) {
            if (button.hovered() && !button.getTooltip().isEmpty()) {
                Widgets.drawTooltip(event.getMatrixStack(), button.getTooltip(), event.getMouseX(), event.getMouseY(),
                        screen.field_230708_k_, screen.field_230709_l_); // Screen.width / height
                break;
            }
        }
    }

    /** Inside the container's own coordinate space, after the slots: the glint highlight. */
    @SubscribeEvent
    public static void onContainerForeground(GuiContainerEvent.DrawForeground event) {
        AutoDropper.renderHighlight(event.getGuiContainer().func_212873_a_(), event.getMatrixStack());
    }

    // ------------------------------------------------------------------ world

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        if (!ModSettings.autoDetectArea || !ModSettings.areaOutlines || !AreaTracker.hasTable()) {
            return;
        }
        EnchantCalculator.Result plan = CrackerState.get().getPlan();
        int target = plan != null && plan.outcome != EnchantCalculator.Outcome.IMPOSSIBLE ? plan.bookshelves : -1;
        AreaTracker.render(event.getMatrixStack(), target);
    }
}
