package com.enchantmentcracker.client;

import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.game.Mc;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.UseAction;
import net.minecraft.potion.EffectInstance;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Says in chat when you do something that uses your hidden random numbers on the server, which
 * the cracker cannot see and so cannot count: taking damage (the hurt sound's pitch), sprinting
 * (running particles, every tick), eating or drinking (particles and sound), splashing into
 * water, a potion effect with particles (every tick), and a /give to you (the pickup sound).
 * Mods can add more. When the next enchantment re-syncs the seed, it names these as the likely
 * cause of the steps it found missing. On a server only: in your own world the seed is read
 * straight from the game, so nothing is missed. Switched with Settings > "Warn when something
 * moves the seed".
 */
public final class RngWatch {

    /** One warning per cause per this many ticks (10 s), so sprinting does not flood the chat. */
    private static final int REPEAT_TICKS = 200;

    /** What happened since the tracked seed was last confirmed, in order, with how often. */
    private static final Map<String, Integer> since = new LinkedHashMap<>();
    private static final Map<String, Long> lastSaid = new LinkedHashMap<>();
    private static long ticks;

    private static int lastHurtTime;
    private static boolean wasSprinting;
    private static boolean wasUsing;
    private static boolean wasInWater = true; // no splash reported on joining in water
    private static final Set<String> particleEffects = new HashSet<>();

    private RngWatch() {
    }

    /** Called every client tick. */
    public static void tick() {
        ticks++;
        ClientPlayerEntity me = Mc.player();
        if (me == null || Mc.integratedServer() != null) {
            return; // own world: the seed is read directly, nothing to warn about
        }
        int hurt = me.field_70737_aN; // hurtTime
        if (hurt > lastHurtTime && hurt > 0) {
            note("took damage", "You took damage");
        }
        lastHurtTime = hurt;

        boolean sprinting = me.func_70051_ag() && me.func_233570_aj_() && !me.func_70090_H(); // isSprinting, isOnGround, isInWater
        if (sprinting && !wasSprinting) {
            note("sprinting", "You are sprinting (every tick of it)");
        }
        wasSprinting = sprinting;

        boolean using = false;
        if (me.func_184587_cr()) { // isHandActive
            ItemStack active = me.func_184607_cu(); // getActiveItemStack
            UseAction action = active.func_77975_n(); // getUseAction
            using = action == UseAction.EAT || action == UseAction.DRINK;
        }
        if (using && !wasUsing) {
            note("eating or drinking", "You are eating or drinking");
        }
        wasUsing = using;

        boolean inWater = me.func_70090_H();
        if (inWater && !wasInWater) {
            note("water", "You went into water (splashing and swimming)");
        }
        wasInWater = inWater;

        Set<String> now = new HashSet<>();
        for (EffectInstance effect : me.func_70651_bq()) { // getActivePotionEffects
            if (effect.func_188418_e()) { // doesShowParticles
                String name = effect.func_188419_a().func_199286_c().getString(); // getPotion().getDisplayName()
                now.add(name);
                if (!particleEffects.contains(name)) {
                    note("effect " + name, "The effect " + name + " shows particles (every tick it lasts)");
                }
            }
        }
        particleEffects.clear();
        particleEffects.addAll(now);
    }

    /** A chat line from the server: a /give to us moves the seed (the pickup sound's pitch). */
    public static void onChat(ITextComponent message) {
        if (Mc.player() == null || Mc.integratedServer() != null || !(message instanceof TranslationTextComponent)) {
            return;
        }
        String key = ((TranslationTextComponent) message).func_150268_i(); // getKey
        if (key.startsWith("commands.give.success")) {
            note("/give", "A /give");
        }
    }

    private static void note(String cause, String sentence) {
        since.merge(cause, 1, Integer::sum);
        CrackerState state = CrackerState.get();
        if (!ModSettings.rngWarnings || state.getStatus() == CrackerState.Status.UNKNOWN) {
            return; // nothing tracked yet, so nothing to lose
        }
        Long said = lastSaid.get(cause);
        if (said != null && ticks - said < REPEAT_TICKS) {
            return;
        }
        lastSaid.put(cause, ticks);
        boolean planned = state.getPlan() != null && state.getPlanStage() != CrackerState.PlanStage.NONE
                && state.getPlanStage() != CrackerState.PlanStage.DONE;
        Mc.chat("§e[Cracker] §f" + sentence + ": that uses your hidden random numbers on the server, so the seed "
                + "drifts until the next enchantment re-syncs it." + (planned
                ? " Your plan will be checked after the dummy and made again if needed." : ""));
    }

    /**
     * The seed was just confirmed at an enchantment, {@code offset} steps off the tracked one. When
     * steps were missed, names what happened since the last time as the likely cause.
     */
    public static void onResync(int offset) {
        if (offset > 0 && ModSettings.rngWarnings && Mc.integratedServer() == null) {
            StringBuilder why = new StringBuilder();
            for (Map.Entry<String, Integer> e : since.entrySet()) {
                why.append(why.length() == 0 ? "" : ", ").append(e.getKey())
                        .append(e.getValue() > 1 ? " (x" + e.getValue() + ")" : "");
            }
            Mc.chat("§e[Cracker] §fRe-synced over " + offset + " RNG step" + (offset == 1 ? "" : "s")
                    + " the cracker could not see. " + (why.length() > 0 ? "Likely cause: " + why + "."
                    : "Nothing it watches happened: a mod may use your random numbers."));
        }
        since.clear();
    }

    /** New world or new player entity: forget what happened before. */
    public static void reset() {
        since.clear();
        lastSaid.clear();
        particleEffects.clear();
        lastHurtTime = 0;
        wasSprinting = false;
        wasUsing = false;
        wasInWater = true;
    }
}
