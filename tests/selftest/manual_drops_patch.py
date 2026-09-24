import pathlib

p = pathlib.Path(__file__).resolve().parent / 'src/com/enchantmentcracker/selftest/SelfTest.java'
s = open(p, encoding='utf-8').read()


def rep(a, b):
    global s
    if s.count(a) != 1:
        raise SystemExit('%d x %s' % (s.count(a), a[:80]))
    s = s.replace(a, b)


rep('''        // End to end: plan, auto-drop, dummy, enchant.''',
    '''        // End to end: plan, manual Q drops (detected), dummy (detected), enchant (detected).''')
rep('''                dropCount = Math.max(0, plan.itemsToThrow);
                if (plan.needsDummy() && plan.itemsToThrow > 0) {
                    AutoDropper.start(plan.itemsToThrow);
                }
            }
        });
        stepUntil(() -> !AutoDropper.isRunning(), () -> {
        });
        step(30, () -> {
            if (plan == null) {
                return;
            }
            long now = ServerRng.readPlayerSeed();
            check(now == PlayerSeed.advance(seedBeforeDrops, dropCount * 4), "plan drops landed the RNG where planned");
            if (plan.needsDummy()) {
                place("book", 0);
            }
        });
        step(15, () -> {
            if (plan != null && plan.needsDummy()) {
                mc().field_71442_b.func_78756_a(tableContainer().field_75152_c, 0);
            }
        });
        step(15, () -> {
            if (plan != null && plan.needsDummy()) {
                click(0, ClickType.QUICK_MOVE);
            }
        });''', '''                dropCount = plan.needsDummy() ? Math.max(0, plan.itemsToThrow) : 0;
                manualDropsLeft = dropCount;
                check(state.getPlanStage() == (dropCount > 0 ? CrackerState.PlanStage.DROPPING
                        : plan.needsDummy() ? CrackerState.PlanStage.DUMMY : CrackerState.PlanStage.FINAL),
                        "plan starts at the right stage: " + state.getPlanStage());
            }
        });
        // Close the table and put cobblestone in hotbar slot 0, the slot Q drops from.
        step(15, () -> Mc.openScreen(null));
        step(15, () -> {
            Slot cobble = null;
            for (Slot slot : Mc.player().field_71069_bz.field_75151_b) {
                if (slot.func_75216_d() && "cobblestone".equals(Mc.idOf(slot.func_75211_c().func_77973_b()))) {
                    cobble = slot;
                    break;
                }
            }
            Mc.windowClick(0, cobble.field_75222_d, Mc.player().field_71071_by.field_70461_c, ClickType.SWAP);
        });
        // Press Q, one item per tick, like a player tapping it.
        stepUntil(() -> {
            if (manualDropsLeft <= 0) {
                return true;
            }
            Mc.player().func_225609_n_(false); // ClientPlayerEntity.drop(false): what the Q key does
            manualDropsLeft--;
            return false;
        }, () -> {
        });
        step(30, () -> {
            if (plan == null) {
                return;
            }
            CrackerState state = CrackerState.get();
            check(state.getDropsSincePlan() == dropCount, "manual Q drops detected: " + state.getDropsSincePlan()
                    + " of " + dropCount);
            long now = ServerRng.readPlayerSeed();
            check(now == PlayerSeed.advance(seedBeforeDrops, dropCount * 4), "plan drops landed the RNG where planned");
            if (plan.needsDummy()) {
                check(state.getPlanStage() == CrackerState.PlanStage.DUMMY, "stage after drops is DUMMY: "
                        + state.getPlanStage());
            }
        });
        step(20, TableTestSteps::open);
        step(10, () -> {
            Slot lapis = find("lapis_lazuli");
            click(lapis.field_75222_d, ClickType.PICKUP);
            click(1, ClickType.PICKUP);
        });
        step(15, () -> {
            if (plan != null && plan.needsDummy()) {
                place("book", 0);
            }
        });
        step(15, () -> {
            if (plan != null && plan.needsDummy()) {
                mc().field_71442_b.func_78756_a(tableContainer().field_75152_c, 0);
            }
        });
        step(15, () -> {
            if (plan != null && plan.needsDummy()) {
                check(CrackerState.get().getEnchantsSincePlan() == 1, "dummy enchant detected");
                check(CrackerState.get().getPlanStage() == CrackerState.PlanStage.FINAL,
                        "after the dummy the table is on the planned seed (stage " + CrackerState.get().getPlanStage() + ")");
                click(0, ClickType.QUICK_MOVE);
            }
        });''')
rep('''            check(got.equals(asSet(plan.enchantments)), "planned enchantment delivered: planned "
                    + asSet(plan.enchantments) + ", got " + got);''', '''            check(got.equals(asSet(plan.enchantments)), "planned enchantment delivered: planned "
                    + asSet(plan.enchantments) + ", got " + got);
            check(CrackerState.get().getPlanStage() == CrackerState.PlanStage.DONE, "final enchant detected, plan DONE: "
                    + CrackerState.get().getPlanStage());''')
rep('''    static int totalTicks;''', '''    static int totalTicks;
    static int manualDropsLeft;''')
open(p, 'w', encoding='utf-8').write(s)
print('ok')
