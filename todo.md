# To do

Newest first.

- [x] **1.2.11: next-step guide, seed-move warnings, and only the levels your table can give.**
  - `client/PlanGuide.java`: the "Next:" panel under the enchanting table (below the prediction
    panel, or above the table when there is no room; on a very short window it narrows to stay
    clear of the Pick junk button) saying what to do now at every plan stage, with pulsing
    highlights on the inventory item, the enchant button or the Lock seed / Auto drop button.
    Setting `planGuide`.
  - `client/RngWatch.java`: on a server, a chat line when the player takes damage, sprints, eats or
    drinks, goes into water, has a potion effect with particles, or gets a /give; throttled to
    once per cause per 10 s; only once a seed is tracked. When the next enchantment re-syncs with
    missed steps, `CrackerState.setResyncListener` -> `RngWatch.onResync` names the likely causes
    (that happens once an item is back in the table: with steps missed, the new XP seed is only
    worked out from the table's numbers and hints). Setting `rngWarnings`.
  - **Table reach** (user: "check if enchants are even possible ... fix the levels so it auto
    accounts if the table doesn't start with 8/10/30"): `TableSetup.powers(item)` gives every
    enchanting power the table can roll (vanilla: the slot levels for that shelf count, +1 +
    2x0..ench/4, +/-15%; Apotheosis: round(2E) and its 0.2-0.4 / 0.6-0.8 slots, times
    1 + g*Quanta with g from -1+Rectification to 1, capped at 4x the Eterna ceiling).
    `core/TableReach` turns that into the top level of each enchantment (the highest level whose
    power window holds a reachable power). The Calc list shows only enchantments the table can
    give, each capped at that level, and follows the table while open; Calculate refuses wishes
    above it with the reason; the Search tab's level cap and text use it too. Unknown table
    (never opened): falls back to what any table could give, and says so.
  - Tests: FeatureTests `tableReach()` rolls vanilla tables 40,000 times per item/shelf count
    (792 cases): nothing beats the prediction, common enchantments reach it; diamond sword
    Sharpness IV at 15 shelves (vanilla really cannot give V there; gold can). NetTest
    `reachChecks()` does the same on the user's Apotheosis table (E15 Q15 A0: powers 5-35,
    Sharpness tops at IV where any table allows IX; 360 cases, none beaten), screenshot
    `calc_reach`. Guide checks at every stage of each plain plan (+ `guide_final`),
    `rngWatchChecks()` (real instant damage + real sprint reported, re-sync names them, silent
    with the setting off).
  - Also: the About tab said "1.1.0"; it now shows the real version.

- [x] **1.2.10:** A plan now also notices when the table's Quanta, Arcana or Rectification changed
  (Apotheosis): those change which enchantments come out but not the level numbers, so comparing the
  numbers alone missed them. `Apotheosis.Table.sameStats` is part of `PlanTab.tableMismatch`, so the
  final step is held back and the plan is made again for the table as it now is. Rectification shows
  in the table description ("R5%"). LAN test: a rectifier swapped in after planning (still E15, same
  numbers) is caught, re-planned for "E15 Q15% A0% R5%" and delivered 1:1.

- [x] **1.2.9:** "The table shows 10/23/30 but the plan expects 8/19/24 (Apotheosis E12)": the planner
  had picked a plan for a lower-Eterna table, which means rebuilding the table, and that step was
  missed. Now:
  - plans use the table as it stands unless Settings > "Plans may lower the table's power" is on
    (`ModSettings.lowerTablePower`, off by default; `Planner.setupsFor`);
  - auto fix: when the table does not match the plan at the final step (`PlanTab.tableMismatch`)
    or the seed is off course or overshot (one drop too many), `ClientEvents.autoReplan` plans again for the same item and wishes
    (`CrackerState.setPlanGoal`, recorded when Calc/Search adopt a plan) on the table as it stands,
    adopts it and says the steps in chat. Once per plan, never in a loop.
  - LAN test: a plan for E12 left on an E15 table is caught, held back, re-planned for E15 and
    delivered 1:1; a plan knocked off course by a hidden /give is re-planned and delivered 1:1.

- [x] **1.2.8:** Calc tab: the enchantment list is sorted A to Z by name, and a search box above it
  filters by name or mod id as you type (the rows are rebuilt per key; the box keeps focus).

- [x] **Bug (servers / LAN guests): the table's XP seed reaches the client as 16 bits only.** Fixed in 1.2.5.
  - `SWindowPropertyPacket` writes container data with `writeShort`, so over a real network
    `EnchantmentContainer.getXPSeed()` (`func_217005_f`) is `(short) realXpSeed`, sign-extended
    (e.g. `FFFFFB78`). Singleplayer / LAN host pass packets in memory without serialising, so
    the full int arrives, which is why every test passed.
  - Seen 2026-09-27 as a guest on DDSS2 (Apotheosis E15 Q15 A0, diamond boots): table 10/24/30,
    prediction 8/21/30, "Another mod may change enchanting here". No other mod touches the
    table; Apotheosis's own levels (L = round(E*2): slot 1 = L*0.2-0.4, slot 2 = L*0.6-0.8,
    slot 3 = L) are what the table shows.
  - Breaks in `TableWatcher.tick` (prediction + mismatch guard), `CrackerState.observeXpSeed`
    and `PlayerSeed.solve` (two-XP-seed lock), which all assume 32 bits.
  - Fix idea: when not in your own world, treat the synced value as the low 16 bits only and
    recover the top 16 by trying all 65,536 and keeping the one that reproduces the shown
    levels and the three enchantment clues (vanilla and Apotheosis). Then lock as before.
  - **Done (1.2.5):** `core/PartialXpSeed` narrows the top half from the levels, the hint
    fields and (Apotheosis) the ordered hint list + "all hints" flag from its `ClueMessage`.
    Apotheosis tables often leave a few candidates, so `CrackerState` now pairs candidate
    *sets*: only the true pair is one enchantment (plus 4 steps per drop between) apart
    (`PlayerSeed.solveSets`). Drops are timed from when a seed *appeared*, not when it was
    worked out, so drops before the lock are allowed for. Once locked, each new XP seed is
    named from its low half at once. The overlay shows offers all remaining candidates agree on.
  - Also fixed: closing an inventory with Esc/E while holding a stack on the cursor makes the
    server throw it (4 RNG steps) with no client-side toss event; now counted.
  - 1.2.6, from playing on the DDSS2 server with 1.2.5:
    - A half-seed saved by 1.2.4 in the per-world memory (`xpSeed=FFFFFB78`) was taken as a full
      seed: confident but wrong predictions. Such values are ignored now, and any remembered or
      predicted XP seed is checked against the table's numbers and hints before it is used
      (`CrackerState.rejectTableXpSeed`).
    - An XP seed whose low half is 0 (1 in 65,536) was read as "table not synced yet", so the
      enchantment that made it was missed. 0 now only means that in the first second after opening.
    - Plans are item-specific. The calculator kept a remembered item (a chestplate) while leggings
      went in the table, so "Prot IV + Unbreaking III" came out as other enchantments with the seed
      tracked perfectly. Opening the cracker in front of a table now takes the table's item (never
      mid-plan), and the final step warns when the table holds another item than the plan's.
    - *Lock seed* button beside the table (servers only): enchants books in slot 1 until locked.
    - The server path writes `[seed]` lines to the game log (seeds appearing, candidates, pairings,
      locks, re-syncs), so a failure on someone's server can be worked through from their log.
  - 1.2.7, from a plan that missed on the DDSS2 LAN world:
    - The count said 577 drops, the server made 575. On a server, drops were counted from the
      client's own simulation of each click, and the server can ignore a click the client already
      simulated. Now counted from the items the server spawns (`ClientEvents.isOwnDrop`: made at
      the thrower's x, eye height - 0.3, z), which covers every drop, whatever caused it; the
      auto dropper aims at that confirmed count and resends only after 3 s without one.
    - "Dummy done and the table is on the planned seed" was said while the seed was unknown. New
      CHECKING stage; FINAL now needs the seed confirmed.
    - Re-sync also searches backwards (up to 512 drops), so a miscount heals instead of losing the lock.
    - The table screen now holds back an enchant click that would spoil the plan (Shift overrides).
      Things the client cannot see can still use the RNG: an RNG spy on a LAN host showed `/give`
      takes two steps (the pickup sound's pitch); being hit, eating and mods can too. The dummy
      reveals any of that before the real enchantment, and the click is held back.
    - `Mc.currentScreenPauses` and `McScreen` used `func_231178_ax__` (shouldCloseOnEsc) as
      isPauseScreen (`func_231177_au__`): auto drop closed the table screen on servers, and the
      cracker window paused singleplayer. Every SRG name with a readable comment was audited.
    - The thrown-item lock's model summed the vertical velocity's last term in double where
      `PlayerEntity#dropItem` stays in float, so real throws often "did not match". Now bit-exact
      as the server sends it (FeatureTests: 100,000 throws against a literal transcription).
      A real throw on the LAN test still did not match, so the thrown-item lock is switched off
      for 1.2.7 (new settings key `velocityLock`, not offered in Settings). Still to find: what
      differs between the model and a real throw (the yaw/pitch the server used? the velocity
      packet? the drop offset?). Until then two enchantments lock the seed.
    - Tested on a real LAN world (host client + guest client, `lanlaunch.py`), with every thrown
      item picked back up while dropping, and with the whole DDSS2 pack (`packlaunch.py`: 90
      predictions and the user's wishes delivered 1:1, Soulbound/Magnet/etc. included; an RNG spy
      found nothing in the pack using the player's generator besides drops, /give and enchanting).
  - Verified against a real dedicated server over TCP (`tests/selftest/netlaunch.py`), with
    `/data get entity <you> XpSeed` as ground truth: Apotheosis 124/124, vanilla 123/123.

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
  - Root cause: the enchanting-table / inventory screen pauses singleplayer, so the drops ran
    against a frozen server and the items never flew. 1.2.3: auto-drop closes any *pausing* screen
    first (`Mc.currentScreenPauses`), then throws the specific junk slot with the inventory throw
    (`ClickType.THROW`) — which always throws the junk, never the held item, and is counted on
    servers. With the world unpaused the items get their velocity and scatter away instead of
    piling underfoot. (The 1.2.2 attempt to hold+drop the junk in the look direction was reverted:
    the hotbar swap was unreliable and dropped the held item.)
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
