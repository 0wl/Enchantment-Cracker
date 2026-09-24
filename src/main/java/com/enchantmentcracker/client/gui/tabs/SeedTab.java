package com.enchantmentcracker.client.gui.tabs;

import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.CrackerTab;
import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.core.CrackerState;
import com.enchantmentcracker.core.PlayerSeed;
import com.enchantmentcracker.core.SeedCracker;
import com.enchantmentcracker.game.Mc;
import com.enchantmentcracker.game.ServerRng;
import com.mojang.blaze3d.matrix.MatrixStack;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Everything to do with getting hold of the player's RNG state: what the mod currently
 * knows, the two-XP-seed solver, manual entry, and the original brute-force cracker.
 */
public final class SeedTab implements CrackerTab {

    private static final NumberFormat NUMBERS = NumberFormat.getInstance(Locale.ROOT);

    private CrackerScreen screen;
    private int x;
    private int y;
    private int width;

    private Widgets.TextBox xpSeed1;
    private Widgets.TextBox xpSeed2;
    private Widgets.TextBox manualSeed;
    private Widgets.TextBox shelvesBox;
    private Widgets.TextBox level1;
    private Widgets.TextBox level2;
    private Widgets.TextBox level3;
    private Widgets.McButton crackButton;

    /** The brute-force pass runs here so it never stalls the render thread. */
    private static Thread crackThread;
    private static volatile String crackMessage = "";

    @Override
    public String title() {
        return "Seed";
    }

    @Override
    public void init(CrackerScreen screen, int x, int y, int width, int height) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.width = width;

        CrackerState state = CrackerState.get();

        screen.addWidget(new Widgets.McButton(x + width - 44, y - 1, 44, 13, "Reset",
                () -> {
                    CrackerState.get().resetSeed();
                    abortCrack();
                    CrackerState.get().getCracker().reset();
                    crackMessage = "";
                    screen.rebuild();
                }).tooltip("Forget the seed and start over.",
                "Use this if you changed world or the numbers stopped matching."));

        // --- solve from two XP seeds
        int solveY = y + 47;
        xpSeed1 = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 26, solveY, 58, 14, "XP seed 1")
                .maxLength(8).hex());
        xpSeed2 = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 112, solveY, 58, 14, "XP seed 2")
                .maxLength(8).hex());
        if (state.hasTableXpSeed()) {
            xpSeed1.setText(PlayerSeed.formatXpSeed(state.getTableXpSeed()));
        }
        screen.addWidget(new Widgets.McButton(x + 176, solveY, 44, 14, "Solve", this::solveFromXpSeeds)
                .tooltip("Turn two XP seeds taken one enchantment apart",
                        "into the full 48-bit player seed."));
        screen.addWidget(new Widgets.McButton(x + 224, solveY, 40, 14, "Grab", () -> {
            CrackerState s = CrackerState.get();
            if (!s.hasTableXpSeed()) {
                crackMessage = "Open an enchanting table first.";
                return;
            }
            String value = PlayerSeed.formatXpSeed(s.getTableXpSeed());
            if (xpSeed1.text().isEmpty() || !xpSeed2.text().isEmpty()) {
                xpSeed1.setText(value);
                xpSeed2.setText("");
            } else {
                xpSeed2.setText(value);
            }
        }).tooltip("Copy the XP seed the table is showing", "into the next empty box."));

        // --- manual entry
        int manualY = y + 65;
        manualSeed = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 40, manualY, 86, 14, "Player seed")
                .maxLength(12).hex());
        if (state.isLocked()) {
            manualSeed.setText(PlayerSeed.format(state.getPlayerSeed()));
        }
        screen.addWidget(new Widgets.McButton(x + 130, manualY, 36, 14, "Set", () -> {
            long seed = PlayerSeed.parse(manualSeed.text());
            if (seed == PlayerSeed.UNKNOWN) {
                crackMessage = "That is not a 12 digit hex seed.";
            } else {
                CrackerState.get().setManually(seed);
                crackMessage = "";
            }
        }).tooltip("Use a seed you already had,", "e.g. from the standalone tool."));

        // Nudge controls, for when something outside the mod moved the RNG.
        int nudgeX = x + 176;
        int[] steps = {-4, -1, 1, 4};
        for (int i = 0; i < steps.length; i++) {
            int step = steps[i];
            screen.addWidget(new Widgets.McButton(nudgeX + i * 23, manualY, 21, 14,
                    (step > 0 ? "+" : "") + step, () -> CrackerState.get().nudge(step))
                    .tooltip("Move the tracked RNG by " + step + " step" + (Math.abs(step) == 1 ? "" : "s") + ".",
                            "One dropped item is 4 steps,",
                            "one enchantment is 1 step."));
        }

        // --- brute-force cracker
        int crackY = y + 91;
        shelvesBox = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 46, crackY, 22, 14, "Shelves")
                .maxLength(2).numeric());
        level1 = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 84, crackY, 22, 14, "Slot 1")
                .maxLength(2).numeric());
        level2 = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 118, crackY, 22, 14, "Slot 2")
                .maxLength(2).numeric());
        level3 = screen.addWidget(new Widgets.TextBox(Mc.font(), x + 152, crackY, 22, 14, "Slot 3")
                .maxLength(2).numeric());
        screen.addWidget(new Widgets.McButton(x + 180, crackY, 40, 14, "Fill", this::fillFromTable)
                .tooltip("Copy the numbers from the table", "you last had open."));
        fillFromTable();

        int buttonY = y + 109;
        crackButton = screen.addWidget(new Widgets.McButton(x, buttonY, 92, 14, "Add reading",
                this::startCrack)
                .tooltip("Narrow the possible XP seeds using",
                        "these three level requirements.",
                        "The first reading searches all 4 billion seeds."));
        screen.addWidget(new Widgets.McButton(x + 96, buttonY, 56, 14, "Clear", () -> {
            abortCrack();
            CrackerState.get().getCracker().reset();
            crackMessage = "Cracker cleared.";
        }).tooltip("Throw away the candidate list."));
        screen.addWidget(new Widgets.McButton(x + 156, buttonY, 50, 14, "Stop",
                SeedTab::abortCrack).tooltip("Abort the search in progress."));
    }

    // ------------------------------------------------------------------ actions

    private void solveFromXpSeeds() {
        Integer first = PlayerSeed.parseXpSeed(xpSeed1.text());
        Integer second = PlayerSeed.parseXpSeed(xpSeed2.text());
        if (first == null || second == null) {
            crackMessage = "Both boxes need an 8 digit hex XP seed.";
            return;
        }
        long solved = PlayerSeed.solve(first, second);
        if (solved == PlayerSeed.UNKNOWN) {
            crackMessage = "No seed links those two. They must be one enchantment apart.";
            return;
        }
        CrackerState.get().setManually(solved);
        manualSeed.setText(PlayerSeed.format(solved));
        crackMessage = "Solved. Player seed locked.";
    }

    private void fillFromTable() {
        CrackerState state = CrackerState.get();
        int shelves = state.getTableBookshelves();
        int[] levels = state.getTableLevels();
        if (shelves >= 0) {
            shelvesBox.setText(String.valueOf(shelves));
        }
        if (levels[0] > 0 || levels[1] > 0 || levels[2] > 0) {
            level1.setText(String.valueOf(levels[0]));
            level2.setText(String.valueOf(levels[1]));
            level3.setText(String.valueOf(levels[2]));
        }
    }

    private void startCrack() {
        SeedCracker cracker = CrackerState.get().getCracker();
        if (cracker.isRunning()) {
            crackMessage = "Already searching.";
            return;
        }
        if (com.enchantmentcracker.game.Apotheosis.isActive()) {
            // Its level numbers come from Eterna, not a shelf count, so vanilla readings mean nothing.
            crackMessage = "The brute-force cracker only understands vanilla tables. With Apotheosis, "
                    + "just enchant twice: the seed locks from the two XP seeds automatically.";
            return;
        }
        int shelves = shelvesBox.intValue(-1);
        int s1 = level1.intValue(0);
        int s2 = level2.intValue(0);
        int s3 = level3.intValue(0);
        if (shelves < 0 || shelves > 15 || s1 <= 0 || s2 <= 0 || s3 <= 0) {
            crackMessage = "Need bookshelves (0-15) and all three level numbers.";
            return;
        }

        crackMessage = cracker.isFirstTime()
                ? "Searching all 4.3 billion seeds. This can take a minute..."
                : "Filtering the remaining candidates...";

        crackThread = new Thread(() -> {
            try {
                cracker.addObservation(shelves, s1, s2, s3);
                int remaining = cracker.getPossibleSeeds();
                if (cracker.isTooManySeeds()) {
                    crackMessage = "Those readings match too many seeds to hold in memory. "
                            + "Use more bookshelves, or read a table with higher level numbers.";
                } else if (remaining == 0) {
                    crackMessage = "No seed matches. Double-check the numbers you typed.";
                } else if (remaining == 1) {
                    CrackerState.get().setCrackedXpSeed(cracker.getSeed());
                    crackMessage = "XP seed found: " + PlayerSeed.formatXpSeed(cracker.getSeed())
                            + ". Enchant once, then Solve.";
                } else {
                    crackMessage = NUMBERS.format(remaining)
                            + " seeds left. Enchant, re-read the table, and add another reading.";
                }
            } catch (Throwable t) {
                crackMessage = "Search failed: " + t;
            }
        }, "EnchCracker-search");
        crackThread.setDaemon(true);
        crackThread.start();
    }

    private static void abortCrack() {
        SeedCracker cracker = CrackerState.get().getCracker();
        if (cracker.isRunning()) {
            cracker.requestAbort();
            crackMessage = "Search aborted.";
        }
        crackThread = null;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        CrackerState state = CrackerState.get();

        Mc.text(ms, "Status", x, y, Theme.TEXT_TITLE);
        String source = state.isLocked() ? state.getSource().label
                : state.getStatus() == CrackerState.Status.AWAITING_SECOND ? "one XP seed captured" : "nothing yet";
        int sourceColour = state.isLocked() ? Theme.GOOD
                : state.getStatus() == CrackerState.Status.AWAITING_SECOND ? Theme.WARN : Theme.BAD;
        Mc.text(ms, source, x + 44, y, sourceColour);

        Theme.inset(ms, x + 66, y + 11, 120, 12);
        Mc.text(ms, "Player seed", x, y + 13, Theme.TEXT_DARK);
        Mc.text(ms, state.isLocked() ? PlayerSeed.format(state.getPlayerSeed()) : "unknown",
                x + 70, y + 13, state.isLocked() ? 0xFF303030 : Theme.TEXT_MUTED);

        String xpText = state.hasTableXpSeed()
                ? PlayerSeed.formatXpSeed(state.getTableXpSeed()) : "-";
        Mc.text(ms, "XP seed " + xpText, x, y + 27, Theme.TEXT_DARK);
        Mc.text(ms, "drops " + state.getItemsDropped(), x + 104, y + 27, Theme.TEXT_MUTED);
        Mc.text(ms, "drift " + state.getDriftSteps(), x + 162, y + 27,
                state.getDriftSteps() == 0 ? Theme.TEXT_MUTED : Theme.WARN);

        if (ServerRng.isAvailable()) {
            Mc.text(ms, "Reading your world directly - always exact.", x, y + 38, Theme.GOOD);
        } else {
            Mc.text(ms, "Get the seed", x, y + 37, Theme.TEXT_TITLE);
        }

        Mc.text(ms, "XP 1", x, y + 50, Theme.TEXT_DARK);
        Mc.text(ms, "XP 2", x + 88, y + 50, Theme.TEXT_DARK);
        Mc.text(ms, "By hand", x, y + 68, Theme.TEXT_DARK);

        separator(ms, y + 84);
        Mc.text(ms, "Brute force from table readings", x, y + 79, Theme.TEXT_TITLE);
        Mc.text(ms, "Shelves", x, y + 94, Theme.TEXT_DARK);
        Mc.text(ms, "1", x + 76, y + 94, Theme.TEXT_DARK);
        Mc.text(ms, "2", x + 110, y + 94, Theme.TEXT_DARK);
        Mc.text(ms, "3", x + 144, y + 94, Theme.TEXT_DARK);

        SeedCracker cracker = state.getCracker();
        int barY = y + 128;
        Theme.inset(ms, x, barY, width, 10);
        float progress = cracker.getProgress();
        if (progress >= 0) {
            int filled = (int) ((width - 2) * progress);
            Mc.fill(ms, x + 1, barY + 1, x + 1 + filled, barY + 9, Theme.ACCENT);
        }
        String barText = cracker.isRunning()
                ? String.format(Locale.ROOT, "%.1f%%", Math.max(0, progress) * 100)
                : cracker.getObservationCount() == 0
                        ? "idle"
                        : NUMBERS.format(cracker.getPossibleSeeds()) + " candidate"
                                + (cracker.getPossibleSeeds() == 1 ? "" : "s");
        Mc.centeredText(ms, barText, x + width / 2, barY + 1, 0xFFFFFFFF);

        if (!crackMessage.isEmpty()) {
            for (String line : Theme.wrap(crackMessage, width)) {
                Mc.text(ms, line, x, y + 142, Theme.TEXT_DARK);
                break; // one line; the rest is in the status strip
            }
        }
    }

    private void separator(MatrixStack ms, int lineY) {
        Mc.fill(ms, x, lineY, x + width, lineY + 1, 0xFF9E9E9E);
    }

    @Override
    public String statusLine() {
        return crackMessage.isEmpty() ? CrackerState.get().getStatusMessage() : crackMessage;
    }
}
