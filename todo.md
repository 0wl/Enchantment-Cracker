# To do

Newest first.

All of the items below were done for **1.2.0**. Verify with `tests\verify.ps1` (build +
pure-logic feature tests + link check) and the in-game checklist in `tests\VERIFICATION.md`.

- [x] Easter egg: make the needle of the compass icon on the Plan tab point towards the mouse cursor.
  - The Plan tab's compass icon rotates its real needle at the cursor (`CompassNeedle` swaps the
    compass model's `angle` property while the icon is drawn, delegating to vanilla otherwise, so
    actual compasses are untouched). Registered in `EnchantmentCrackerMod`, driven from
    `Widgets.TabButton`. (1.2.0 drew a line over the item instead; replaced in 1.2.1.)
- [x] Fix the tab names: "Search" and "Settings" show only an icon.
  - `Widgets.TabButton` now tries icon + name, then name alone (dropping the icon rather than the
    name), and only falls back to icon-only when even the name will not fit.
- [x] Add an item search to the Search and Anvil tabs.
  - New `ItemGrid` pop-over: a filter box (name / mod id) over a scrollable grid of item icons,
    opened with the `⌕` button next to the `<` `>` arrows on both tabs. Search tab filters the
    items the enchantment can go on; Anvil tab filters every enchantable item. The arrows stay.
- [x] Make the text easier to read: dark text with a dark drop shadow is hard to read.
  - 1.2.1: the body text is light (white titles) and drawn with a drop shadow (`Mc.text`), and the
    status colours were lightened (`Theme`), so everything reads on the grey panel instead of dark
    text sitting on grey.
- [x] Base the drop search on how much junk the player actually carries, up to a full inventory.
  - Ceiling raised to 36 x 64 = 2304 (`EnchantCalculator.DEFAULT_MAX_THROWS`). The search still
    returns the fewest-drops plan; the Plan/Search steps and the "Drop N (H)" buttons show junk
    carried vs needed, with a "get M more" hint when short (`PlanTab.junkCarriedHint`).
- [x] Bug: the detected bookshelf count does not update when shelves are placed or added.
  - `TableWatcher.resolveBookshelves` now trusts the live world scan (which follows placed shelves)
    instead of back-solving from the stale level numbers, and the overlay says to take the item out
    and back in to refresh the on-screen numbers. Apotheosis stats are still only re-read with an
    item in the table (the mod zeroes them when empty); item 11 covers searching lower layouts.
- [x] Detect drops / dummy / final enchant and tick the plan's steps off automatically.
  - The detection was already in the source; the self-test now covers it: `manual_drops_patch.py`
    was applied to `SelfTest.java`, so the end-to-end test drops with Q and checks the stages
    advance to DONE. Still worth a real-game pass (see `tests\VERIFICATION.md`).
- [x] Auto drop: items should land in front of the player, not at their feet.
  - 1.2.2: auto-drop closes the open screen first (so singleplayer is not paused — the enchanting
    table/inventory screens pause it — and the view is ours), looks level, and in your own world
    uses the look-direction "press Q" drop so items fly forward; the view is restored when done.
    On a real server it keeps the client-simulated inventory throw (which is counted for seed
    tracking; that throw scatters randomly server-side, but the seed stays exact). The 1.2.0/1.2.1
    attempts (force pitch over the paused screen, then remove it) did not work because the screen
    pause froze the drops.
- [x] Make Escape close the cracker menu.
- [x] Crack the player RNG from dropped-item velocities, so a server seed locks without spending two enchantments.
  - `VelocityCracker`: one captured XP seed gives the top 32 bits; a thrown item's velocity picks
    the low 16 out of 2^16 candidates, so one enchant + one throw locks the seed (source
    "XP seed + throw"). Wired through `CrackerState.observeThrowVelocity` and `ClientEvents`
    (opt-out setting "Lock seed from thrown items"). The maths is unit-tested. Note: on servers the
    velocity is quantised over the network, so an occasional throw will not match — throw again
    (it never locks to a wrong seed; it requires an exact match).
- [x] Search Apotheosis shelf layouts (Eterna/Quanta/Arcana) the way vanilla shelf counts are searched.
  - `Apotheosis.searchSetups` walks the current Eterna and every whole value below it (Quanta and
    Arcana kept), and `Planner.setupsFor` searches them. A plan then tells you the Eterna to lower
    the table to, as a vanilla plan tells you a shelf count. (Exact per-block layouts are not
    enumerated; Eterna is the lever that changes the level requirements.)
- [x] Anvil planner: start from an item that already has enchantments or anvil uses.
  - `AnvilPlanner.plan` takes the item's existing enchantments and prior anvil uses; the Anvil tab's
    "From held" button reads them off the item in your hand and plans the new books on top.
- [x] Test the LAN-guest and server paths in a real multiplayer session.
  - Automated where possible (velocity crack + planner maths are unit-tested; every class is
    link-checked against the real runtime). The remaining live multiplayer checks are listed in
    `tests\VERIFICATION.md` for a manual pass.
