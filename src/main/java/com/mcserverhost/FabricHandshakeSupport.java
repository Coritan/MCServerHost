package com.mcserverhost;

import java.lang.reflect.Field;

/**
 * Shared helpers for the Fabric handshake mixin. Lives outside the mixin
 * package so Mixin's inner-class generator does not try to process it.
 */
public final class FabricHandshakeSupport {

    private static final sun.misc.Unsafe UNSAFE;
    static {
        sun.misc.Unsafe u = null;
        try {
            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            u = (sun.misc.Unsafe) uf.get(null);
        } catch (Throwable ignored) {}
        UNSAFE = u;
    }

    private FabricHandshakeSupport() {}

    public static Object findConnection(Object listener) {
        Class<?> c = listener.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                String tn = f.getType().getName();
                if (tn.equals("net.minecraft.class_2535")
                        || tn.equals("net.minecraft.network.Connection")
                        || tn.equals("net.minecraft.network.NetworkManager")
                        || tn.endsWith(".ClientConnection")) {
                    Object v = readField(listener, f);
                    if (v != null) return v;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static Object readField(Object target, Field f) {
        try { f.setAccessible(true); return f.get(target); } catch (Throwable ignored) {}
        if (UNSAFE == null) return null;
        try { return UNSAFE.getObject(target, UNSAFE.objectFieldOffset(f)); } catch (Throwable ignored) { return null; }
    }

    public static void writeField(Object target, Field f, Object v) throws Throwable {
        try { f.setAccessible(true); f.set(target, v); return; } catch (Throwable ignored) {}
        if (UNSAFE == null) throw new IllegalStateException("Unsafe unavailable");
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(f), v);
    }
}
