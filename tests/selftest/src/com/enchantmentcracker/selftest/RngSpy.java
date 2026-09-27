package com.enchantmentcracker.selftest;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test harness only: a stand-in for a player's {@code Entity.rand} that behaves identically (same
 * internal state, same sequence) but records who draws from it, so every step of the player's
 * generator can be attributed to the code that took it.
 */
final class RngSpy extends Random {

    static PrintWriter out;
    static volatile long tick;
    static long steps;

    private RngSpy() {
        super(0L);
    }

    @Override
    protected int next(int bits) {
        steps++;
        if (out != null) {
            StackTraceElement[] trace = new Throwable().getStackTrace();
            StringBuilder who = new StringBuilder();
            int shown = 0;
            for (StackTraceElement e : trace) {
                String c = e.getClassName();
                if (c.equals("java.util.Random") || c.equals(RngSpy.class.getName())) {
                    continue;
                }
                who.append(shown == 0 ? "" : " < ").append(c.substring(c.lastIndexOf('.') + 1)).append('.')
                        .append(e.getMethodName()).append(':').append(e.getLineNumber());
                if (++shown == 4) {
                    break;
                }
            }
            out.println("tick " + tick + " step " + steps + " " + who);
        }
        return super.next(bits);
    }

    /** Swaps the entity's generator for a spy carrying the same state. True if it was swapped now. */
    static boolean install(Object entity) throws Exception {
        Field rand = net.minecraft.entity.Entity.class.getDeclaredField("field_70146_Z"); // Entity.rand
        rand.setAccessible(true);
        Object current = rand.get(entity);
        if (current instanceof RngSpy) {
            return false;
        }
        Field seed = Random.class.getDeclaredField("seed");
        seed.setAccessible(true);
        long state = ((AtomicLong) seed.get(current)).get();
        RngSpy spy = new RngSpy();
        ((AtomicLong) seed.get(spy)).set(state);
        Field modifiers = Field.class.getDeclaredField("modifiers");
        modifiers.setAccessible(true);
        modifiers.setInt(rand, rand.getModifiers() & ~Modifier.FINAL);
        rand.set(entity, spy);
        return true;
    }
}
