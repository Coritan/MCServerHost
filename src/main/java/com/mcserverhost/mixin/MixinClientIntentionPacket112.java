package com.mcserverhost.mixin;

import com.mcserverhost.ForgePlugin;
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

/**
 * Injects into the 1.12.x handshake packet handler.
 *
 * In 1.12, the handshake packet class is net.minecraft.network.handshake.client.C00Handshake
 * and the handler is net.minecraft.network.handshake.server.ServerHandshakeNetHandler.
 * The method is processHandshake(IHandshakeNetHandler).
 * The hostname field SRG name is field_150747_b.
 */
@Pseudo
@Mixin(targets = "net.minecraft.network.handshake.server.ServerHandshakeNetHandler", remap = false)
public abstract class MixinClientIntentionPacket112 {

    @Inject(
        method = {"processHandshake", "func_147288_a"},
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void mcsh$onProcessHandshake(Object packet, CallbackInfo ci) {
        ForgePlugin plugin = ForgePlugin.getInstance();
        if (plugin == null) return;

        try {
            PacketContext packetCtx = new PacketContext112(packet);
            Object connection = ConnectionLocator112.extractConnection(this);
            PlayerContext player = new PlayerContext112(connection);
            plugin.getHandshakeHandler().handleHandshake(packetCtx, player);
        } catch (PluginException e) {
            plugin.getDebugger().exception(e);
        } catch (Exception e) {
            plugin.getDebugger().exception(e);
        }
    }


    private static final class ConnectionLocator112 {

        static Object extractConnection(Object listener) {
            if (listener == null) return null;

            Class<?> clazz = listener.getClass();
            while (clazz != null) {
                for (Field f : clazz.getDeclaredFields()) {
                    String typeName = f.getType().getName();
                    if (typeName.equals("net.minecraft.network.NetworkManager")
                            || typeName.equals("net.minecraft.network.Connection")) {
                        try {
                            f.setAccessible(true);
                            return f.get(listener);
                        } catch (Exception ignored) {
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
            return null;
        }

    }


    private static class PacketContext112 implements PacketContext {

        private final Object packet;

        PacketContext112(Object packet) {
            this.packet = packet;
        }

        @Override
        public String getPayloadString() {
            try {
                for (String name : new String[]{"field_150747_b", "hostName", "host"}) {
                    Field field = findField(packet.getClass(), name);
                    if (field != null && field.getType() == String.class) {
                        field.setAccessible(true);
                        Object val = field.get(packet);
                        if (val instanceof String) return (String) val;
                    }
                }
                for (String name : new String[]{"getHostName", "getHost", "func_149571_g", "func_149567_f"}) {
                    try {
                        Method m = packet.getClass().getMethod(name);
                        Object val = m.invoke(packet);
                        if (val instanceof String) return (String) val;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                for (Field f : packet.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        Object val = f.get(packet);
                        if (val instanceof String && !((String) val).isEmpty()) return (String) val;
                    }
                }
                throw new NoSuchFieldException("Could not locate hostName on " + packet.getClass().getName());
            } catch (Exception e) {
                throw new PluginException.ReflectionException(e);
            }
        }

        @Override
        public void setPacketHostname(String hostname) {
            try {
                Field field = null;
                for (String name : new String[]{"field_150747_b", "hostName", "host"}) {
                    field = findField(packet.getClass(), name);
                    if (field != null && field.getType() == String.class) break;
                }
                if (field == null) {
                    for (Field f : packet.getClass().getDeclaredFields()) {
                        if (f.getType() == String.class) { field = f; break; }
                    }
                }
                if (field == null) throw new NoSuchFieldException("hostName not found on " + packet.getClass().getName());
                field.setAccessible(true);
                try {
                    field.set(packet, hostname);
                } catch (IllegalAccessException e) {
                    sun.misc.Unsafe unsafe = getUnsafe();
                    long offset = unsafe.objectFieldOffset(field);
                    unsafe.putObject(packet, offset, hostname);
                }
            } catch (Exception e) {
                throw new PluginException.PacketManipulationException(e);
            }
        }

        private static Field findField(Class<?> startClass, String name) {
            Class<?> clazz = startClass;
            while (clazz != null) {
                try { return clazz.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
                clazz = clazz.getSuperclass();
            }
            return null;
        }

        private static sun.misc.Unsafe getUnsafe() throws Exception {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        }

    }


    static class PlayerContext112 implements PlayerContext {

        private final Object connection;
        private String ip;

        PlayerContext112(Object connection) {
            this.connection = connection;
            this.ip = connection != null ? resolveRemoteAddress(connection) : "unknown";
        }

        private static String resolveRemoteAddress(Object conn) {
            try {
                for (Method m : conn.getClass().getMethods()) {
                    String n = m.getName();
                    if ((n.equals("getRemoteAddress") || n.equals("func_150715_a") || n.equals("address"))
                            && m.getParameterCount() == 0) {
                        Object addr = m.invoke(conn);
                        if (addr instanceof InetSocketAddress) {
                            return ((InetSocketAddress) addr).getAddress().getHostAddress();
                        }
                    }
                }
                for (Field f : conn.getClass().getDeclaredFields()) {
                    if (java.net.SocketAddress.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object addr = f.get(conn);
                        if (addr instanceof InetSocketAddress) {
                            return ((InetSocketAddress) addr).getAddress().getHostAddress();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return "unknown";
        }

        @Override
        public String getUUID() { return "unknown"; }

        @Override
        public String getName() { return "unknown"; }

        @Override
        public String getIP() { return ip; }

        @Override
        public void setIP(InetSocketAddress newAddr) {
            if (connection == null) throw new PluginException.PlayerManipulationException(new NullPointerException("connection is null"));
            try {
                this.ip = newAddr.getAddress().getHostAddress();
                Field addressField = findConnectionAddressField(connection);
                if (addressField == null) throw new NoSuchFieldException("address field not found on " + connection.getClass().getName());
                addressField.setAccessible(true);
                try {
                    addressField.set(connection, newAddr);
                } catch (IllegalAccessException e) {
                    sun.misc.Unsafe unsafe = getUnsafe();
                    long offset = unsafe.objectFieldOffset(addressField);
                    unsafe.putObject(connection, offset, newAddr);
                }
            } catch (Exception e) {
                throw new PluginException.PlayerManipulationException(e);
            }
        }

        private static sun.misc.Unsafe getUnsafe() throws Exception {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        }

        private static Field findConnectionAddressField(Object connection) {
            for (String name : new String[]{"field_150744_e", "socketAddress", "address", "remoteAddress"}) {
                Field f = findField(connection.getClass(), name);
                if (f != null && java.net.SocketAddress.class.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
            Class<?> clazz = connection.getClass();
            while (clazz != null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (java.net.SocketAddress.class.isAssignableFrom(f.getType())) {
                        return f;
                    }
                }
                clazz = clazz.getSuperclass();
            }
            return null;
        }

        private static Field findField(Class<?> startClass, String name) {
            Class<?> clazz = startClass;
            while (clazz != null) {
                try { return clazz.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
                clazz = clazz.getSuperclass();
            }
            return null;
        }

    }

}
