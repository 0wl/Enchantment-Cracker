# Enchantment Cracker — Forge 1.16.5

An in-game port of [Earthcomputer and Hexicube's standalone Enchantment
Cracker](https://github.com/Earthcomputer/EnchantmentCracker). Instead of copying numbers
out of the game and into a separate window, the mod reads the enchanting table itself,
works out your player RNG, and tells you exactly what to do to get the enchantment you want.

Client side only. Works in singleplayer, when hosting or joining a LAN world, and on servers.

## Install

1. Install **Forge 1.16.5** (36.x).
2. Drop `enchcracker-1.2.4-forge-1.16.5.jar` into your `mods` folder.
3. Launch. Press **K** in game.

## What it does

| | |
|---|---|
| **Reads the seed automatically** | In your own world (singleplayer or LAN host) the mod reads the player's random number generator straight out of the running game. Always exact. |
| **Solves it from two XP seeds** | As a LAN guest or on a server, two XP seeds taken one enchantment apart pin down the full 48-bit state. |
| **Locks it from a thrown item** | On a server, after one enchantment, throwing a single item is enough: the launch velocity pins the remaining bits, so you never spend a second enchantment. |
| **Brute-forces it the original way** | The classic cracker is still there for vanilla tables. |
| **Predicts every slot** | Writes the real enchantments of all three slots under the enchanting table screen. |
| **Searches for one enchantment** | Pick any enchantment a table can give, modded ones included; it picks a fitting item and lists the steps. Filter items by name or mod id instead of stepping through them. |
| **Plans the manipulation** | Choose a wishlist and it works out how many items to drop, how many bookshelves to use, and which slot to click, with up to three options. |
| **Drops the items for you** | Pick a junk item once in your inventory; the table gets a *Drop N* button that throws exactly the number the plan needs. |
| **Reads your enchanting area** | Finds your table and every shelf around it, and outlines in the world which gaps to block to reach the count a plan needs. |
| **Plans the anvil** | Finds the cheapest order to combine enchanted books onto an item, prior-work penalty included. Can start from the enchanted, already-worked item in your hand. |
| **Works with mods** | Predictions call the game's own enchanting code, so modded enchantments and items work. Apotheosis's replacement table is supported. If an unknown mod changes the table, the overlay says so instead of showing wrong numbers. |

## Keys

Rebindable in **Options → Controls → Enchantment Cracker**, or from the Settings tab. A key
shared with another mod still works.

| Key | Action |
|---|---|
| `K` | Open (and close) the cracker window |
| `J` | Open the calculator, pre-filled from the table you have open |
| `N` | Read the table and report it in chat |
| `G` | In any inventory: make the hovered item the auto-drop junk |
| unbound | Auto-drop the items the plan needs |
| unbound | Open the enchantment search |

Beside the enchanting table: **Cracker**, **Predict** and **Drop N**. Above your inventory:
**Pick junk**.

## The window

* **Seed**: what the mod knows, the two-XP-seed solver, manual entry, RNG nudges, and the
  brute-force cracker.
* **Calc**: pick an item (including whatever you are holding), pick the enchantments you want
  (or ban), press Calculate.
* **Plan**: the steps, live predictions for the table in front of you, and progress.
* **Search**: every table enchantment; click one for the steps to get it.
* **Anvil**: the cheapest book-combining order for an item.
* **Settings**: switch each feature on or off. Saved to `config/enchcracker-client.properties`.
* **Guide**: the full instructions, in game.
* **About**: credits and diagnostics.

## Building from source

No Gradle needed. The script compiles straight against the SRG-named jars that any local
Forge 1.16.5 installation already has, which is the naming a production mod jar uses anyway.

```
build.bat
```

The jar lands in `..\output\`. If the script cannot find things automatically:

```
powershell -ExecutionPolicy Bypass -File build.ps1 -Jdk "C:\path\to\jdk" -ForgeLibraries "C:\path\to\libraries"
```

To build and verify in one step — build, run the pure-logic feature tests, and link-check the
jar against the real Forge runtime:

```
powershell -ExecutionPolicy Bypass -File tests\verify.ps1
```

See `tests\VERIFICATION.md` for what is checked automatically and what needs a running client.

You need a JDK (not just a JRE) and a Forge 1.16.5 install. See `../PROJECT-NOTES.md` for
how the build and the tests work, and what to change when porting.

## Credits and licence

MIT. The cracking and enchantment mathematics are ported from the standalone Enchantment
Cracker, © 2019 Joseph Burton (Earthcomputer), with speed and interface work by Hexicube.
See `LICENSE.txt`.

The technique, and the `/cenchant` command it inspired, are documented on the
[clientcommands wiki](https://github.com/Earthcomputer/clientcommands/wiki/cenchant).
