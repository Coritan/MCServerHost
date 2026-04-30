package com.mcserverhost;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.logging.Logger;

public final class FabricHandshakeSupport {

    private static Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Throwable ignored) {
        }
    }

    private FabricHandshakeSupport() {}

    public static String readHostname(Object packet) {
        try {
            for (Method m : packet.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class
                        && (m.getName().equals("comp_2340") || m.getName().equals("getHostName")
                        || m.getName().equals("method_10862") || m.getName().equals("hostName"))) {
                    Object val = m.invoke(packet);
                    if (val != null) return (String) val;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Field f : packet.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    Object val = readField(packet, f);
                    if (val != null) return (String) val;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Object findConnection(Object listener) {
        for (Field f : listener.getClass().getDeclaredFields()) {
            Object val = readField(listener, f);
            if (val == null) continue;
            String name = val.getClass().getName();
            if (name.endsWith(".class_2535") || name.endsWith(".Connection")
                    || name.endsWith(".NetworkManager") || name.endsWith(".ClientConnection")) {
                return val;
            }
        }
        Class<?> c = listener.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                Object val = readField(listener, f);
                if (val == null) continue;
                String name = val.getClass().getName();
                if (name.contains("Connection") || name.contains("class_2535")
                        || name.contains("NetworkManager") || name.contains("ClientConnection")) {
                    return val;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static SocketAddress readRemoteAddress(Object connection) {
        Class<?> c = connection.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (SocketAddress.class.isAssignableFrom(f.getType())) {
                    Object val = readField(connection, f);
                    if (val instanceof SocketAddress) return (SocketAddress) val;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static boolean writeRemoteAddress(Object connection, SocketAddress newAddr, Logger logger) {
        Class<?> c = connection.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (SocketAddress.class.isAssignableFrom(f.getType())) {
                    if (writeField(connection, f, newAddr)) {
                        return true;
                    }
                }
            }
            c = c.getSuperclass();
        }
        if (logger != null) logger.warning("[MCServerHost] Could not rewrite remote address on connection.");
        return false;
    }

    public static void handleHandshake(Object packet, Object listener, PluginBase plugin) {
        try {
            String hostname = readHostname(packet);
            if (hostname == null) return;

            Object connection = findConnection(listener);
            if (connection == null) {
                plugin.getLogger().warning("[MCServerHost] Handshake intercepted but no connection found.");
                return;
            }

            SocketAddress remote = readRemoteAddress(connection);
            if (!(remote instanceof InetSocketAddress)) return;

            FabricPacketContext pkt = new FabricPacketContext(packet, hostname);
            FabricPlayerContext ply = new FabricPlayerContext(connection, (InetSocketAddress) remote);

            plugin.getHandshakeHandler().handleHandshake(pkt, ply);

            if (ply.hasNewAddress()) {
                writeRemoteAddress(connection, ply.getNewAddress(), plugin.getLogger());
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("[MCServerHost] Fabric handshake handling failed: " + e.getMessage());
        }
    }

    static Object readField(Object holder, Field f) {
        try {
            f.setAccessible(true);
            return f.get(holder);
        } catch (Throwable ignored) {
        }
        try {
            if (UNSAFE != null && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                long offset = UNSAFE.objectFieldOffset(f);
                return UNSAFE.getObject(holder, offset);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean writeField(Object holder, Field f, Object value) {
        try {
            f.setAccessible(true);
            f.set(holder, value);
            return true;
        } catch (Throwable ignored) {
        }
        try {
            if (UNSAFE != null && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                long offset = UNSAFE.objectFieldOffset(f);
                UNSAFE.putObject(holder, offset, value);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
