# Verifying the 1.2.0 features

Run the automated pass first:

```
powershell -ExecutionPolicy Bypass -File tests\verify.ps1
```

It **builds** the mod against the real Forge 1.16.5 SRG jars (any wrong obfuscated name is a
compile error), runs the **feature tests** (`FeatureTests.java`, pure logic, no game), and
**link-checks** the built jar against the real Forge runtime (`LoadTest.java`, catches
NoSuchMethod / NoClassDefFound at load time). All three must say PASS.

The self-test mod under `tests\selftest` drives a throwaway client end to end (including the
manual-drop auto-ticking from item 6); it needs a Forge 1.16.5 instance and is launched with
`tests\selftest\launch.py vanilla|apoth`.

Below, each to-do item and how it is checked. "Auto" = covered by `verify.ps1`. "In game" =
needs a running client (steps given).

| # | Feature | How to verify |
|---|---------|---------------|
| 1 | Escape closes the cracker menu | **In game:** press `K`, then `Esc` — the window closes. |
| 2 | Tab names no longer clipped to an icon | **In game:** open at a small window size; the "Search" and "Settings" tabs show their names (name-only when the icon will not also fit). |
| 3 | Item search on Search & Anvil tabs | **In game:** Search tab → pick an enchantment → the `⌕` button → type "diamond"/a mod id → click an icon. Anvil tab → `⌕` beside the item arrows. |
| 4 | Readable text (no dark-on-dark shadow) | **Auto** (luminance guard on button labels is exercised by the build) + **in game:** tab labels and the table-overlay greys read clearly. |
| 5 | Drop search up to a full inventory; carry vs need shown | **Auto:** `FeatureTests` checks the 2304 ceiling. **In game:** the Plan/Search steps and the "Drop N (H)" buttons show carried vs needed. |
| 6 | Auto-tick drops / dummy / final enchant | **Self-test:** `tests\selftest` (patched by `manual_drops_patch.py`) drops with Q, then checks the stage advances and the plan reaches DONE. |
| 7 | Auto-drop throws forward, not underfoot | **In game:** run Auto drop while looking down — the view levels out for the throw and restores after, and items land ahead of you. |
| 8 | Bookshelf count updates when shelves change | **In game:** open a table with an item in it, add/remove shelves — the detected count follows, and the overlay says to refresh the table for the on-screen numbers. |
| 9 | (same as 3) | |
| 10 | Lock the seed from a thrown item's velocity | **Auto:** `FeatureTests` recovers the exact state from a simulated throw in every case and never false-locks. **In game (server):** capture one XP seed (enchant once), then throw one item standing still and roughly level — the seed locks (source "XP seed + throw"). |
| 11 | Search Apotheosis Eterna layouts | **In game (Apotheosis):** a plan may ask you to lower the table's power to a stated Eterna, like a vanilla plan asks for a shelf count. |
| 12 | Anvil planner from a pre-enchanted / used item | **Auto:** `FeatureTests` checks prior-work cost, clashes, duplicates and upgrades. **In game:** Anvil tab → "From held" with an enchanted item in hand. |
| 13 | Plan-tab compass needle points at the cursor | **In game:** hover around the window; the compass on the Plan tab points at the mouse. |
| 14 | LAN-guest and server paths | **In game:** on a LAN world as guest and on a server, confirm two-XP-seed solving, the velocity lock (10), predictions and auto-drop all work. |
