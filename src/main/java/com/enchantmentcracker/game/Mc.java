package com.enchantmentcracker.game;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvents;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The one place this mod touches Minecraft's obfuscated API.
 *
 * <p>A production Forge 1.16.5 mod is compiled against SRG names, so every Minecraft
 * method here looks like {@code func_71410_x}. Each wrapper below is named for what it
 * actually does and carries the human-readable (MCP) name in a comment, so the rest of
 * the mod never has to see an SRG identifier.
 *
 * <p>If you ever port this to another Minecraft version, this file and the two beside it
 * are the only ones that need new names.
 */
public final class Mc {

    private Mc() {
    }

    // --------------------------------------------------------------- game accessors

    /** {@code Minecraft.getInstance()} */
    public static Minecraft mc() {
        return Minecraft.func_71410_x();
    }

    /** {@code minecraft.player} */
    public static net.minecraft.client.entity.player.ClientPlayerEntity player() {
        return mc().field_71439_g;
    }

    /** {@code minecraft.world} */
    public static net.minecraft.client.world.ClientWorld world() {
        return mc().field_71441_e;
    }

    /** {@code minecraft.fontRenderer} */
    public static FontRenderer font() {
        return mc().field_71466_p;
    }

    /** {@code minecraft.currentScreen} */
    public static Screen currentScreen() {
        return mc().field_71462_r;
    }

    /** {@code minecraft.displayGuiScreen(screen)} */
    public static void openScreen(Screen screen) {
        mc().func_147108_a(screen);
    }

    /** {@code minecraft.getItemRenderer()} */
    public static ItemRenderer itemRenderer() {
        return mc().func_175599_af();
    }

    /** {@code minecraft.isSingleplayer()} */
    public static boolean isSingleplayer() {
        return mc().func_71387_A();
    }

    /** {@code minecraft.getIntegratedServer()} — null on a multiplayer server. */
    public static net.minecraft.server.integrated.IntegratedServer integratedServer() {
        return mc().func_71401_C();
    }

    /** {@code minecraft.getTextureManager().bindTexture(texture)} */
    public static void bindTexture(ResourceLocation texture) {
        mc().func_110434_K().func_110577_a(texture);
    }

    /** Plays the standard UI click, so buttons feel like the rest of the game. */
    public static void playClick() {
        // minecraft.getSoundHandler().play(SimpleSound.master(SoundEvents.UI_BUTTON_CLICK, 1.0F))
        mc().func_147118_V().func_147682_a(SimpleSound.func_184371_a(SoundEvents.field_187909_gi, 1.0F));
    }

    // ------------------------------------------------------------------- text + i18n

    /** Prints a line into the player's own chat. Nothing is sent to the server. */
    public static void chat(String message) {
        if (mc().field_71456_v == null) { // minecraft.ingameGUI
            return;
        }
        // ingameGUI.getChatGUI().printChatMessage(new StringTextComponent(message))
        mc().field_71456_v.func_146158_b().func_146227_a(
                new net.minecraft.util.text.StringTextComponent(message));
    }

    /** {@code I18n.format(key, args)} */
    public static String translate(String key, Object... args) {
        return I18n.func_135052_a(key, args);
    }

    /** {@code I18n.hasKey(key)} */
    public static boolean hasTranslation(String key) {
        return I18n.func_188566_a(key);
    }

    /** Turns {@code "sharpness", 3} into the game's own "Sharpness III". Works for modded ids. */
    public static String enchantmentName(String id, int level) {
        net.minecraft.enchantment.Enchantment enchantment = enchantment(id);
        if (enchantment == null) {
            return id;
        }
        if (level <= 0) {
            return translate(enchantment.func_77320_a()); // getName() — the translation key
        }
        // enchantment.getDisplayName(level).getString(): drops the numeral for one-level enchantments
        return enchantment.func_200305_d(level).getString();
    }

    /** The game's own display name for an item id like {@code "diamond_pickaxe"}. */
    public static String itemName(String id) {
        ItemStack stack = stackOf(id);
        // itemStack.getDisplayName().getString()
        return stack.func_190926_b() ? id : stack.func_200301_q().getString();
    }

    // ------------------------------------------------------------------- ids

    /**
     * The mod's id convention: vanilla content by bare path ({@code "sharpness"}), modded
     * content by full name ({@code "mymod:frost_blade"}).
     */
    public static String idOf(ResourceLocation name) {
        if (name == null) {
            return null;
        }
        // getNamespace() / getPath()
        return "minecraft".equals(name.func_110624_b()) ? name.func_110623_a() : name.toString();
    }

    public static ResourceLocation resourceOf(String id) {
        return id.indexOf(':') >= 0 ? new ResourceLocation(id) : new ResourceLocation("minecraft", id);
    }

    public static String idOf(Item item) {
        return item == null ? null : idOf(item.getRegistryName());
    }

    public static Item item(String id) {
        if (id == null) {
            return null;
        }
        try {
            return ForgeRegistries.ITEMS.getValue(resourceOf(id));
        } catch (RuntimeException badId) {
            return null;
        }
    }

    public static net.minecraft.enchantment.Enchantment enchantment(String id) {
        if (id == null) {
            return null;
        }
        try {
            return ForgeRegistries.ENCHANTMENTS.getValue(resourceOf(id));
        } catch (RuntimeException badId) {
            return null;
        }
    }

    /** Which mod an id belongs to, for labels: "minecraft" for bare ids. */
    public static String namespaceOf(String id) {
        return id == null || id.indexOf(':') < 0 ? "minecraft" : id.substring(0, id.indexOf(':'));
    }

    /** {@code font.getStringWidth(text)} */
    public static int stringWidth(String text) {
        return font().func_78256_a(text);
    }

    /** {@code font.trimStringToWidth(text, width)} */
    public static String trim(String text, int width) {
        return font().func_238412_a_(text, width);
    }

    // ------------------------------------------------------------------- items

    /** Builds a stack from an item id, vanilla ({@code "diamond_pickaxe"}) or modded ({@code "mod:thing"}). */
    public static ItemStack stackOf(String id) {
        Item item = item(id);
        // ForgeRegistries hands back air for unknown names
        return item == null || item == net.minecraft.item.Items.field_190931_a ? ItemStack.field_190927_a /* ItemStack.EMPTY */
                : new ItemStack(item);
    }

    /** The same stack with an enchantment glint forced on, for highlighting. */
    public static ItemStack glinting(ItemStack stack) {
        ItemStack copy = stack.func_77946_l(); // copy()
        if (!copy.func_77962_s()) {             // hasEffect()
            copy.func_77966_a(net.minecraft.enchantment.Enchantments.field_185307_s, 1); // addEnchantment(UNBREAKING, 1)
        }
        return copy;
    }

    /**
     * Draws an item exactly as an inventory slot would, 16x16 at (x, y).
     *
     * <p>The surrounding state handling mirrors {@code ContainerScreen#drawSlot}: item
     * rendering is 3D and leaves depth testing on, which would make the flat fills and
     * text drawn afterwards disappear.
     */
    public static void drawItem(ItemStack stack, int x, int y) {
        if (stack.func_190926_b()) {
            return;
        }
        ItemRenderer renderer = itemRenderer();
        RenderSystem.enableDepthTest();
        RenderHelper.func_227780_a_();   // RenderHelper.enableStandardItemLighting()
        renderer.field_77023_b = 100.0F; // itemRenderer.zLevel
        renderer.func_175042_a(stack, x, y); // renderItemAndEffectIntoGUI(stack, x, y)
        renderer.field_77023_b = 0.0F;
        RenderHelper.func_74518_a();     // RenderHelper.disableStandardItemLighting()
        RenderSystem.disableDepthTest();
        resetColour();
    }

    // ------------------------------------------------------------------- drawing

    /** {@code AbstractGui.fill(ms, x1, y1, x2, y2, argb)} */
    public static void fill(MatrixStack ms, int x1, int y1, int x2, int y2, int argb) {
        AbstractGui.func_238467_a_(ms, x1, y1, x2, y2, argb);
    }

    /** A one-pixel outline, drawn as four fills. */
    public static void outline(MatrixStack ms, int x, int y, int width, int height, int argb) {
        fill(ms, x, y, x + width, y + 1, argb);
        fill(ms, x, y + height - 1, x + width, y + height, argb);
        fill(ms, x, y + 1, x + 1, y + height - 1, argb);
        fill(ms, x + width - 1, y + 1, x + width, y + height - 1, argb);
    }

    /**
     * The mod's body text. Drawn with a drop shadow: the GUI's light text needs the shadow to
     * read on the grey container panel (and it does no harm on the darker overlays), which is
     * what keeps every label legible instead of dark-on-grey.
     */
    public static void text(MatrixStack ms, String s, int x, int y, int color) {
        font().func_238421_b_(ms, s, x, y, color); // drawStringWithShadow
    }

    /** {@code font.drawString(ms, text, x, y, color)} — genuinely no shadow, for rare flat text. */
    public static void flatText(MatrixStack ms, String s, int x, int y, int color) {
        font().func_238405_a_(ms, s, x, y, color);
    }

    /** {@code font.drawStringWithShadow(ms, text, x, y, color)} */
    public static void shadowText(MatrixStack ms, String s, int x, int y, int color) {
        font().func_238421_b_(ms, s, x, y, color);
    }

    public static void centeredText(MatrixStack ms, String s, int centerX, int y, int color) {
        text(ms, s, centerX - stringWidth(s) / 2, y, color);
    }

    /**
     * True when a colour is dark enough that a black drop shadow behind it would muddy it
     * rather than lift it off the background. Used to drop the shadow on dark text.
     */
    public static boolean isDarkColour(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        // Rec. 601 luma; below ~40% brightness a dark shadow stops helping.
        return (299 * r + 587 * g + 114 * b) / 1000 < 100;
    }

    /** Draws text with a shadow only when the colour is light enough to benefit from one. */
    public static void label(MatrixStack ms, String s, int x, int y, int color) {
        if (isDarkColour(color)) {
            text(ms, s, x, y, color);
        } else {
            shadowText(ms, s, x, y, color);
        }
    }

    public static void centeredLabel(MatrixStack ms, String s, int centerX, int y, int color) {
        label(ms, s, centerX - stringWidth(s) / 2, y, color);
    }

    public static void centeredShadowText(MatrixStack ms, String s, int centerX, int y, int color) {
        shadowText(ms, s, centerX - stringWidth(s) / 2, y, color);
    }

    /**
     * {@code AbstractGui.blit(ms, x, y, width, height, u, v, uWidth, vHeight, texWidth, texHeight)}
     */
    public static void blit(MatrixStack ms, int x, int y, int width, int height,
                            float u, float v, int uWidth, int vHeight, int texWidth, int texHeight) {
        AbstractGui.func_238466_a_(ms, x, y, width, height, u, v, uWidth, vHeight, texWidth, texHeight);
    }

    /** Standard 256x256 GUI sheet blit: {@code AbstractGui.blit(ms, x, y, u, v, w, h, 256, 256)} */
    public static void blit256(MatrixStack ms, int x, int y, float u, float v, int width, int height) {
        AbstractGui.func_238463_a_(ms, x, y, u, v, width, height, 256, 256);
    }

    public static void colour(float r, float g, float b, float a) {
        RenderSystem.color4f(r, g, b, a);
    }

    public static void resetColour() {
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void enableBlend() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    public static void disableBlend() {
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------- inventory

    /** {@code minecraft.playerController.windowClick(windowId, slot, button, type, player)} */
    public static void windowClick(int windowId, int slotId, int button, net.minecraft.inventory.container.ClickType type) {
        mc().field_71442_b.func_187098_a(windowId, slotId, button, type, player());
    }

    /** {@code player.openContainer} — the player's own inventory container when nothing else is open. */
    public static net.minecraft.inventory.container.Container openContainer() {
        return player() == null ? null : player().field_71070_bA;
    }

    /** {@code player.inventory.getItemStack()} — whatever is held on the mouse cursor. */
    public static ItemStack cursorStack() {
        return player().field_71071_by.func_70445_o();
    }

    /** {@code player.inventory.getCurrentItem()} */
    public static ItemStack heldStack() {
        return player().field_71071_by.func_70448_g();
    }

    /** {@code playerController.isInCreativeMode()} */
    public static boolean isCreative() {
        return mc().field_71442_b != null && mc().field_71442_b.func_78758_h();
    }

    /** {@code player.isSpectator()} */
    public static boolean isSpectator() {
        return player() != null && player().func_175149_v();
    }

    /** {@code player.rotationPitch} */
    public static float playerPitch() {
        return player() == null ? 0 : player().field_70125_A;
    }

    /** {@code player.rotationYaw} */
    public static float playerYaw() {
        return player() == null ? 0 : player().field_70177_z;
    }

    /**
     * Points the player's view, prev values included so the change does not interpolate into a
     * spin. The client sends the new rotation to the server on the next tick, GUI open or not.
     */
    public static void setPlayerLook(float yaw, float pitch) {
        net.minecraft.client.entity.player.ClientPlayerEntity player = player();
        if (player == null) {
            return;
        }
        player.field_70177_z = yaw;       // rotationYaw
        player.field_70126_B = yaw;       // prevRotationYaw
        player.field_70125_A = pitch;     // rotationPitch
        player.field_70127_C = pitch;     // prevRotationPitch
    }

    /** {@code player.inventory.currentItem} — the selected hotbar slot (0-8). */
    public static int selectedHotbar() {
        return player() == null ? 0 : player().field_71071_by.field_70461_c;
    }

    public static void setSelectedHotbar(int slot) {
        if (player() != null && slot >= 0 && slot < 9) {
            player().field_71071_by.field_70461_c = slot;
        }
    }

    /** {@code clientPlayer.drop(dropEntireStack)} — drops the selected item in the look direction. */
    public static void dropSelected(boolean entireStack) {
        if (player() != null) {
            player().func_225609_n_(entireStack);
        }
    }

    /** The enchantments on a stack, in this mod's id convention. */
    public static java.util.List<com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance> enchantmentsOf(ItemStack stack) {
        java.util.List<com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance> out = new java.util.ArrayList<>();
        if (stack == null || stack.func_190926_b()) {
            return out;
        }
        // EnchantmentHelper.getEnchantments(stack)
        for (java.util.Map.Entry<net.minecraft.enchantment.Enchantment, Integer> entry
                : net.minecraft.enchantment.EnchantmentHelper.func_82781_a(stack).entrySet()) {
            String id = idOf(entry.getKey().getRegistryName());
            if (id != null) {
                out.add(new com.enchantmentcracker.core.CrackEnchantments.EnchantmentInstance(id, entry.getValue()));
            }
        }
        return out;
    }

    /** {@code stack.getRepairCost()} — the prior-work penalty exponent already on an item. */
    public static int repairCost(ItemStack stack) {
        return stack == null || stack.func_190926_b() ? 0 : stack.func_82838_A(); // getRepairCost()
    }

    // ------------------------------------------------------------------- session

    /** {@code minecraft.getCurrentServerData()} — null in singleplayer. */
    public static net.minecraft.client.multiplayer.ServerData serverData() {
        return mc().func_147104_D();
    }

    /** {@code minecraft.gameSettings} */
    public static net.minecraft.client.GameSettings settings() {
        return mc().field_71474_y;
    }

    // ------------------------------------------------------------------- world render

    /** {@code gameRenderer.getActiveRenderInfo().getProjectedView()} — the camera position. */
    public static net.minecraft.util.math.vector.Vector3d cameraPos() {
        return mc().field_71460_t.func_215316_n().func_216785_c();
    }

    /** {@code minecraft.getRenderTypeBuffers().getBufferSource()} */
    public static net.minecraft.client.renderer.IRenderTypeBuffer.Impl bufferSource() {
        return mc().func_228019_au_().func_228487_b_();
    }
}
