package com.enchantmentcracker.client.gui.tabs;

import com.enchantmentcracker.client.ModKeyBindings;
import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.CrackerTab;
import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.EnchantModel;
import com.enchantmentcracker.core.Models;
import com.enchantmentcracker.game.Apotheosis;
import com.enchantmentcracker.game.RegistryModel;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.ServerRng;
import com.mojang.blaze3d.matrix.MatrixStack;

/**
 * Credits, licence, and a diagnostics block for when something is not behaving.
 */
public final class AboutTab implements CrackerTab {

    public static final String VERSION = "1.1.0";

    private int x;
    private int y;
    private int width;

    @Override
    public String title() {
        return "About";
    }

    @Override
    public void init(CrackerScreen screen, int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;

        screen.addWidget(new Widgets.McButton(x, y + height - 16, 110, 15, "Copy seed to chat",
                () -> {
                    CrackerState state = CrackerState.get();
                    if (state.isLocked()) {
                        Mc.chat("§a[Cracker] §fPlayer seed: §e"
                                + com.enchantmentcracker.core.PlayerSeed.format(state.getPlayerSeed()));
                    } else {
                        Mc.chat("§c[Cracker] No seed known yet.");
                    }
                }).tooltip("Prints the seed in your own chat", "so you can copy it out of the log."));
    }

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        int lineY = y;

        Mc.text(ms, "Enchantment Cracker " + VERSION, x, lineY, Theme.TEXT_TITLE);
        lineY += 12;
        Mc.text(ms, "Forge 1.16.5 · client side only", x, lineY, Theme.TEXT_MUTED);
        lineY += 14;

        Mc.text(ms, "Cracking and enchantment maths ported from the", x, lineY, Theme.TEXT_DARK);
        lineY += 10;
        Mc.text(ms, "standalone Enchantment Cracker (MIT):", x, lineY, Theme.TEXT_DARK);
        lineY += 10;
        Mc.text(ms, "Earthcomputer — original tool and technique", x + 6, lineY, Theme.ACCENT);
        lineY += 10;
        Mc.text(ms, "Hexicube — speed and interface work", x + 6, lineY, Theme.ACCENT);
        lineY += 14;

        Mc.text(ms, "Technique: github.com/Earthcomputer/clientcommands/wiki/cenchant", x, lineY, Theme.ACCENT);
        lineY += 14;

        Mc.fill(ms, x, lineY, x + width, lineY + 1, 0xFF9E9E9E);
        lineY += 5;
        Mc.text(ms, "Diagnostics", x, lineY, Theme.TEXT_TITLE);
        lineY += 11;

        boolean direct = ServerRng.isAvailable();
        Mc.text(ms, "World RNG access: " + (direct ? "yes" : "no"), x, lineY,
                direct ? Theme.GOOD : Theme.WARN);
        lineY += 10;
        if (!direct) {
            String reason = Mc.isSingleplayer()
                    ? (ServerRng.getFailureReason() == null ? "server not ready" : ServerRng.getFailureReason())
                    : "not your own world";
            Mc.text(ms, Mc.trim("Reason: " + reason, width), x + 6, lineY, Theme.TEXT_MUTED);
            lineY += 10;
        }

        String role = Mc.integratedServer() != null
                ? "your own world (singleplayer or LAN host): seed read directly"
                : Mc.serverData() != null && Mc.serverData().func_181041_d() ? "LAN guest" : "server";
        Mc.text(ms, "Playing on: " + role, x, lineY, Theme.TEXT_DARK);
        lineY += 10;

        EnchantModel model = Models.get();
        String data = model instanceof RegistryModel
                ? model.allTableEnchantments().size() + " table enchantments, " + model.enchantableItems().size()
                + " enchantable items (live registry)"
                : "built-in vanilla data (not in a world yet)";
        Mc.text(ms, Mc.trim("Data: " + data, width), x, lineY, Theme.TEXT_DARK);
        lineY += 10;

        if (Apotheosis.isActive()) {
            Mc.text(ms, "Apotheosis enchanting: supported", x, lineY, Theme.GOOD);
        } else if (Apotheosis.getFailure() != null) {
            Mc.text(ms, Mc.trim(Apotheosis.getFailure(), width), x, lineY, Theme.BAD);
        } else {
            Mc.text(ms, "Table: vanilla rules", x, lineY, Theme.TEXT_MUTED);
        }
        lineY += 10;

        Mc.text(ms, "Keys: open " + ModKeyBindings.keyName(ModKeyBindings.openGui)
                        + ", calc " + ModKeyBindings.keyName(ModKeyBindings.quickPlan)
                        + ", read " + ModKeyBindings.keyName(ModKeyBindings.capture)
                        + ", pick " + ModKeyBindings.keyName(ModKeyBindings.pickJunk),
                x, lineY, Theme.TEXT_DARK);
    }

    @Override
    public String statusLine() {
        return "Offline / singleplayer tool. MIT licensed.";
    }
}
