package com.mcserverhost.mixin.fabric;

import com.mcserverhost.FabricPlugin;
import com.mcserverhost.PacketContext;
import com.mcserverhost.PlayerContext;
import com.mcserverhost.PluginException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Fabric handshake interceptor. Follows the tcpshield-fabric pattern:
 * inject at HEAD of handleIntention on ServerHandshakePacketListenerImpl
 * (intermediary class_3248), read the hostname from the packet, rewrite
 * the Connection's remote socket address.
 *
 * Uses intermediary names (class_3248 / method_14414) with a Mojang name
 * fallback. @Pseudo avoids failing if the class is absent.
 */
@Pseudo
@Mixin(targets = {
    "net.minecraft.class_3248",
    "net.minecraft.server.network.ServerHandshakePacketListenerImpl"
}, remap = false)
public abstract class MixinFabricHandshake {

    @Inject(
        method = {"method_14414", "handleIntention"},
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void mcsh$onHandleIntention(Object packet, CallbackInfo ci) {
        FabricPlugin plugin = FabricPlugin.getInstance();
        if (plugin == null) return;

        try {
            Object connection = findConnection(this);
            if (connection == null) {
                plugin.getLogger().warning("[MCServerHost] handshake: connection field not found on " + this.getClass().getName());
                return;
            }

            PacketContext packetCtx = new FabricPacketContext(packet);
            PlayerContext player = new FabricPlayerContext(connection);
            plugin.getLogger().info("[MCServerHost] Handshake intercepted. Raw payload: \"" + packetCtx.getPayloadString() + "\", IP: " + player.getIP());
            plugin.getHandshakeHandler().handleHandshake(packetCtx, player);
            plugin.getLogger().info("[MCServerHost] Handshake handled. New IP: " + player.getIP());
        } catch (Throwable e) {
            plugin.getLogger().warning("[MCServerHost] Handshake error: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Object findConnection(Object listener) {
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

    private static sun.misc.Unsafe UNSAFE;
    static {
        try {
            Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) uf.get(null);
        } catch (Throwable ignored) {}
    }

    private static Object readField(Object target, Field f) {
        try { f.setAccessible(true); return f.get(target); } catch (Throwable ignored) {}
        if (UNSAFE == null) return null;
        try { return UNSAFE.getObject(target, UNSAFE.objectFieldOffset(f)); } catch (Throwable ignored) { return null; }
    }

    private static void writeField(Object target, Field f, Object v) throws Throwable {
        try { f.setAccessible(true); f.set(target, v); return; } catch (Throwable ignored) {}
        if (UNSAFE == null) throw new IllegalStateException("Unsafe unavailable");
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(f), v);
    }

    private static Field findField(Class<?> start, String name) {
        Class<?> c = start;
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }


    private static final class FabricPacketContext implements PacketContext {
        private final Object packet;
        FabricPacketContext(Object packet) { this.packet = packet; }

        @Override
        public String getPayloadString() {
            // 1.20.5+ makes ClientIntentionPacket a record; try record accessor then legacy names.
            for (String name : new String[]{"comp_2340", "comp_1267", "method_10920", "getHostName", "hostName"}) {
                try {
                    Method m = packet.getClass().getMethod(name);
                    Object v = m.invoke(packet);
                    if (v instanceof String) return (String) v;
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {}
            }
            // Field-based lookup: first String field.
            Class<?> c = packet.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        Object v = readField(packet, f);
                        if (v instanceof String) return (String) v;
                    }
                }
                c = c.getSuperclass();
            }
            throw new PluginException.ReflectionException(new NoSuchFieldException("hostName not found on " + packet.getClass().getName()));
        }

        @Override
        public void setPacketHostname(String hostname) {
            Class<?> c = packet.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        try { writeField(packet, f, hostname); return; } catch (Throwable ignored) {}
                    }
                }
                c = c.getSuperclass();
            }
        }
    }


    private static final class FabricPlayerContext implements PlayerContext {
        private final Object connection;
        private String ip;

        FabricPlayerContext(Object connection) {
            this.connection = connection;
            this.ip = resolveIp(connection);
        }

        private static String resolveIp(Object conn) {
            try {
                for (Method m : conn.getClass().getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    String n = m.getName();
                    if (n.equals("getAddress") || n.equals("getRemoteAddress") || n.equals("method_10755") || n.equals("method_52917")) {
                        try {
                            Object v = m.invoke(conn);
                            if (v instanceof InetSocketAddress) return ((InetSocketAddress) v).getAddress().getHostAddress();
                        } catch (Throwable ignored) {}
                    }
                }
                Field f = findSocketAddressField(conn);
                if (f != null) {
                    Object v = readField(conn, f);
                    if (v instanceof InetSocketAddress) return ((InetSocketAddress) v).getAddress().getHostAddress();
                }
            } catch (Throwable ignored) {}
            return "unknown";
        }

        private static Field findSocketAddressField(Object conn) {
            Class<?> c = conn.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (SocketAddress.class.isAssignableFrom(f.getType())) return f;
                }
                c = c.getSuperclass();
            }
            return null;
        }

        @Override public String getUUID() { return "unknown"; }
        @Override public String getName() { return "unknown"; }
        @Override public String getIP() { return ip; }

        @Override
        public void setIP(InetSocketAddress newAddr) {
            try {
                this.ip = newAddr.getAddress().getHostAddress();
                Field f = findSocketAddressField(connection);
                if (f == null) throw new NoSuchFieldException("SocketAddress field not found on " + connection.getClass().getName());
                writeField(connection, f, newAddr);
            } catch (Throwable e) {
                throw new PluginException.PlayerManipulationException(e);
            }
        }
    }
}
