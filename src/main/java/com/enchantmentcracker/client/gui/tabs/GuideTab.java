package com.enchantmentcracker.client.gui.tabs;

import com.enchantmentcracker.client.gui.CrackerScreen;
import com.enchantmentcracker.client.gui.CrackerTab;
import com.enchantmentcracker.client.gui.Theme;
import com.enchantmentcracker.client.gui.Widgets;
import com.enchantmentcracker.game.Mc;
import com.mojang.blaze3d.matrix.MatrixStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The instructions, in the game, scrollable.
 *
 * <p>Covers both what the mod does for you and what is actually happening underneath,
 * because the technique is much easier to follow once you know the enchanting table is
 * driven by one 32-bit number that only changes when you enchant.
 */
public final class GuideTab implements CrackerTab {

    /**
     * Guide source. A line starting with {@code #} is a heading, {@code -} is a bullet,
     * {@code >} is an indented note. Everything else is a paragraph and gets word-wrapped.
     */
    private static final String[] SOURCE = {
            "# What this mod does",
            "Minecraft decides your enchantments from a number called the XP seed. If you know it, "
                    + "you know exactly what every slot of the enchanting table will give you, before you "
                    + "spend a single level. This mod works that number out and tells you how to steer it "
                    + "to the enchantment you actually want.",
            "",
            "# How enchanting really works",
            "- Every player has their own random number generator on the server, 48 bits wide.",
            "- Enchanting anything draws one number from it and stores it as your XP seed.",
            "- The enchanting table screen is generated purely from your XP seed, the bookshelf "
                    + "count, and which item is in the slot. No other randomness is involved.",
            "- So the same seed plus the same shelves plus the same item always gives the same "
                    + "three offers. That is what makes this possible.",
            "",
            "# Getting the seed",
            "In your own world the mod reads the generator straight out of the running game. "
                    + "Open the Seed tab and it will already say 'read from world'. Nothing else to do.",
            "",
            "Everywhere else it has to be worked out. The XP seed itself is sent to your client so "
                    + "the table can draw its hints, so the mod can see it the moment you open a table. "
                    + "One XP seed is only the top 32 bits of the generator though, so it takes two:",
            "- Open a table. The mod captures XP seed 1.",
            "- Enchant something cheap. That draws a new number, giving XP seed 2.",
            "- The mod brute-forces the 16 missing bits and locks the full seed.",
            "> Do not drop anything or take damage between the two, or they will not be consecutive.",
            "",
            "# The brute force cracker",
            "If you would rather do it the old way, the Seed tab still has the original cracker. "
                    + "Type in the bookshelf count and the three level requirements you can see, press "
                    + "Add reading, and it searches all 4.3 billion XP seeds for ones that match. One "
                    + "reading usually leaves millions of candidates; each further reading (enchant "
                    + "something, then read the table again) cuts it down until one is left.",
            "> The first pass uses every spare CPU core and can hold a few hundred MB. Give "
                    + "Minecraft plenty of memory before using it, and prefer readings taken with "
                    + "plenty of bookshelves, since low numbers match far too many seeds.",
            "",
            "# Choosing your enchantment",
            "Open the Calc tab:",
            "- Pick the item with the shape and material rows, or one of the items underneath. "
                    + "The last one is whatever you are holding, so modded items work too.",
            "- Set the highest bookshelf count you are willing to build, and your level. With area "
                    + "detection on, the shelf count is read from the blocks around your table.",
            "- Click an enchantment to cycle it: off, then I, II, III up to the highest that item "
                    + "can get, then NO to ban it entirely. Right-click steps back.",
            "- Press Calculate.",
            "",
            "# Following the plan",
            "The Plan tab shows what to do, and ticks each step off as you do it.",
            "- Set the bookshelf count it asks for. Fewer shelves means lower level offers, which "
                    + "is often how a specific enchantment becomes reachable.",
            "- Drop the number of junk items it asks for (or let Auto drop do it). Every item thrown "
                    + "moves your generator on by exactly four steps, which is the knob you are turning.",
            "- Enchant a junk item once. That is the 'dummy': it burns one draw so the real "
                    + "enchantment lands on the seed you want.",
            "- Enchant your real item in the slot it names.",
            "",
            "# What breaks the tracking",
            "Anything else on the server that uses your player's random numbers will push it out of "
                    + "step. The mod notices and re-syncs the next time you enchant, but while a plan is "
                    + "running you should avoid:",
            "- taking damage or being knocked back",
            "- having potion effects ticking",
            "- fishing, or anything that spawns entities from your player",
            "> In your own world none of this matters: the seed is re-read from the game every tick.",
            "",
            "# Searching for one enchantment",
            "The Search tab lists every enchantment a table can give, including ones added by other "
                    + "mods. Click one and the mod picks a fitting item (a sword for Looting, boots for "
                    + "Feather Falling...), which you can change with the arrows, along with the level. "
                    + "'Find a way' searches your seed and shows up to three ways, as numbered steps: "
                    + "the bookshelves to use, the junk to drop, the dummy enchantment, then the real one. "
                    + "'Use as plan' makes it the active plan.",
            "",
            "# Auto drop",
            "Counting dropped items by hand is where plans usually go wrong, so the mod can do it:",
            "- Open your inventory and press 'Pick junk' (above the inventory), then click the item "
                    + "to throw away, e.g. cobblestone. Any item works, modded ones too. The slot shimmers "
                    + "with the enchantment glint for a few seconds to confirm.",
            "- Or hover the item in any inventory and press the 'Pick auto-drop item' key (G).",
            "- At the enchanting table, press 'Drop N'. The mod throws exactly the number the plan "
                    + "still needs, one item at a time. Press it again to stop.",
            "> It throws items on the ground: use something you do not mind losing.",
            "",
            "# Enchanting area detection",
            "With this on (Settings), the mod finds the table you are near and reads every bookshelf "
                    + "around it, so the shelf count is never typed in. When a plan needs fewer shelves "
                    + "than you have, it works out the fewest changes and outlines them in the world: "
                    + "red means put any block (a torch, a carpet) in that gap between table and shelves, "
                    + "green means clear that gap, orange means take that shelf away.",
            "",
            "# The anvil planner",
            "The Anvil tab takes the enchantments you want on an item and finds the cheapest order to "
                    + "combine one book per enchantment, prior-work penalty included. Each book has a "
                    + "'Find' button that opens the Search tab for it on a book.",
            "",
            "# Modded tables and Apotheosis",
            "The predictions call the game's own enchanting code, so modded enchantments and items "
                    + "are handled exactly as the server handles them. Apotheosis replaces the table "
                    + "entirely (Eterna, Quanta, Arcana instead of a shelf count); the mod reads those "
                    + "stats off the open table and uses Apotheosis's own maths, so plans use your table "
                    + "as it stands. Put an item in the table once so its stats can be read.",
            "> If some other mod changes the table in a way the mod cannot follow, the table overlay "
                    + "says the numbers do not match instead of showing wrong predictions.",
            "",
            "# LAN and servers",
            "Hosting a LAN world works exactly like singleplayer: the seed is read from the game. As a "
                    + "LAN guest or on a server, the mod locks the seed from two enchantments and counts "
                    + "your drops itself: Q with nothing open, Q over a slot, dragging items out, and Auto "
                    + "drop are all counted. Other players never affect your seed.",
            "> Every time you join or respawn, Minecraft gives your character a new random generator, "
                    + "so the seed has to be locked again with two enchantments. 'Remember seed per "
                    + "world' keeps everything else.",
            "",
            "# Keys",
            "- K opens this window (and closes it again).",
            "- J opens the calculator with the table's item and shelves filled in.",
            "- N reads the table and reports it in chat without opening anything.",
            "- G, in an inventory, picks the hovered item as auto-drop junk.",
            "- 'Auto drop' and 'Open Enchantment Search' have no key until you give them one.",
            "All of them can be changed in Options, Controls, under 'Enchantment Cracker', or with "
                    + "'Key bindings...' on the Settings tab. A key shared with another mod still works.",
            "",
            "# On the table screen",
            "Buttons appear to the left of the enchanting table: 'Cracker' opens this window, "
                    + "'Predict' toggles an overlay that writes the exact enchantments of all three "
                    + "slots under the table, and 'Drop N' auto-drops the junk the plan needs.",
            "",
            "# Credits",
            "The cracking and enchantment maths are ported from the standalone Enchantment Cracker "
                    + "by Earthcomputer, with speed and UI work by Hexicube. The original technique and "
                    + "the /cenchant command it inspired are documented on the clientcommands wiki:",
            "> github.com/Earthcomputer/clientcommands/wiki/cenchant",
    };

    private CrackerScreen screen;
    private int x;
    private int y;
    private int width;
    private int height;

    private final List<Line> lines = new ArrayList<>();
    private int scroll;

    private static final class Line {
        final String text;
        final int colour;
        final int indent;
        final boolean heading;

        Line(String text, int colour, int indent, boolean heading) {
            this.text = text;
            this.colour = colour;
            this.indent = indent;
            this.heading = heading;
        }
    }

    @Override
    public String title() {
        return "Guide";
    }

    @Override
    public void init(CrackerScreen screen, int x, int y, int width, int height) {
        this.screen = screen;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        buildLines(width - 10);

        screen.addWidget(new Widgets.McButton(x + width - 48, y + height - 14, 20, 13, "Up",
                () -> scrollBy(-3)).tooltip("Scroll up"));
        screen.addWidget(new Widgets.McButton(x + width - 26, y + height - 14, 24, 13, "Down",
                () -> scrollBy(3)).tooltip("Scroll down"));
    }

    private void buildLines(int wrapWidth) {
        lines.clear();
        for (String raw : SOURCE) {
            if (raw.isEmpty()) {
                lines.add(new Line("", Theme.TEXT_DARK, 0, false));
            } else if (raw.startsWith("# ")) {
                lines.add(new Line(raw.substring(2), Theme.TEXT_TITLE, 0, true));
            } else if (raw.startsWith("- ")) {
                List<String> wrapped = Theme.wrap(raw.substring(2), wrapWidth - 10);
                for (int i = 0; i < wrapped.size(); i++) {
                    lines.add(new Line((i == 0 ? "- " : "  ") + wrapped.get(i),
                            Theme.TEXT_DARK, 4, false));
                }
            } else if (raw.startsWith("> ")) {
                for (String wrapped : Theme.wrap(raw.substring(2), wrapWidth - 10)) {
                    lines.add(new Line(wrapped, Theme.WARN, 8, false));
                }
            } else {
                for (String wrapped : Theme.wrap(raw, wrapWidth)) {
                    lines.add(new Line(wrapped, Theme.TEXT_DARK, 0, false));
                }
            }
        }
    }

    private void scrollBy(int amount) {
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - visible), scroll + amount));
    }

    private int visibleRows() {
        return Math.max(1, (height - 16) / 10);
    }

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partialTicks) {
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - visible), scroll));

        for (int i = 0; i < visible && i + scroll < lines.size(); i++) {
            Line line = lines.get(i + scroll);
            if (line.text.isEmpty()) {
                continue;
            }
            int lineY = y + i * 10;
            if (line.heading) {
                Mc.fill(ms, x, lineY + 9, x + Mc.stringWidth(line.text) + 2, lineY + 10, 0xFF9E9E9E);
            }
            Mc.text(ms, line.text, x + line.indent, lineY, line.colour);
        }

        Theme.scrollbar(ms, x + width - 6, y, visible * 10, lines.size() * 10, scroll * 10);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollBy(amount > 0 ? -3 : 3);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP:
                scrollBy(-1);
                return true;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN:
                scrollBy(1);
                return true;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP:
                scrollBy(-visibleRows());
                return true;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN:
                scrollBy(visibleRows());
                return true;
            default:
                return false;
        }
    }

    @Override
    public String statusLine() {
        return "Scroll with the wheel, the arrow keys or page up / page down.";
    }
}
