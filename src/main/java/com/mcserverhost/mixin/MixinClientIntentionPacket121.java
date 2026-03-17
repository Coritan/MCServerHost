package com.mcserverhost.mixin;

import com.mcserverhost.ForgePlugin;
import com.mcserverhost.PacketContext;
import com.mcserverhost.PlayerContext;
import com.mcserverhost.PluginException;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;

/**
 * Injects into ServerHandshakePacketListenerImpl on Forge 1.17+ (Mojang-named classes).
 *
 * Forge 1.17+ uses Mojang class names at runtime but SRG method/field names.
 * - Target class:  net.minecraft.server.network.ServerHandshakePacketListenerImpl
 * - handleIntention SRG method name: m_7322_
 * - ClientIntentionPacket class:     net.minecraft.network.protocol.handshake.ClientIntentionPacket
 * - hostName field SRG name:         f_134721_
 *
 * The 1.16.5 mixin (MixinClientIntentionPacket) covers the older MCP-named class on that version.
 */
@Pseudo
@Mixin(targets = "net.minecraft.server.network.ServerHandshakePacketListenerImpl", remap = false)
public abstract class MixinClientIntentionPacket121 {

    /**
     * m_7322_ is the SRG name for handleIntention(ClientIntentionPacket) on Forge 1.17-1.20.x.
     * We also try "handleIntention" as a fallback for versions that may use Mojang method names.
     */
    @Inject(
        method = {"m_7322_", "handleIntention"},
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void mcsh$onHandleIntention(ClientIntentionPacket packet, CallbackInfo ci) {
        ForgePlugin plugin = ForgePlugin.getInstance();
        if (plugin == null) return;

        try {
            PacketContext packetCtx = new PacketContext121(packet);
            Object connection = ConnectionLocator.extractConnectionFromListener(this);
            PlayerContext player = new PlayerContext121(connection);
            plugin.getLogger().info("[MCServerHost] Handshake intercepted. Raw payload: \"" + packetCtx.getPayloadString() + "\", IP: " + player.getIP());
            plugin.getHandshakeHandler().handleHandshake(packetCtx, player);
            plugin.getLogger().info("[MCServerHost] Handshake handled. New IP: " + player.getIP());
        } catch (PluginException e) {
            plugin.getLogger().warning("[MCServerHost] Handshake PluginException: " + e.getMessage());
            plugin.getDebugger().exception(e);
        } catch (Exception e) {
            plugin.getLogger().warning("[MCServerHost] Handshake Exception: " + e.getClass().getName() + ": " + e.getMessage());
            plugin.getDebugger().exception(e);
        }
    }


    private static final class ConnectionLocator {

        static Object extractConnectionFromListener(Object listener) {
            if (listener == null) return null;

            Class<?> connClass = null;
            try { connClass = Class.forName("net.minecraft.network.Connection"); } catch (Exception ignored) {}
            if (connClass == null) {
                try { connClass = Class.forName("net.minecraft.network.NetworkManager"); } catch (Exception ignored) {}
            }

            Class<?> clazz = listener.getClass();
            while (clazz != null) {
                for (Field f : clazz.getDeclaredFields()) {
                    Class<?> type = f.getType();
                    boolean matches = connClass != null && connClass.isAssignableFrom(type);
                    if (!matches) {
                        String n = type.getName();
                        matches = n.equals("net.minecraft.network.Connection")
                                || n.equals("net.minecraft.network.NetworkManager");
                    }
                    if (matches) {
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


    private static class PacketContext121 implements PacketContext {

        private final ClientIntentionPacket packet;

        PacketContext121(ClientIntentionPacket packet) {
            this.packet = packet;
        }

        @Override
        public String getPayloadString() {
            try {
                for (String name : new String[]{"m_179802_", "getHostName"}) {
                    try {
                        Method m = packet.getClass().getMethod(name);
                        return (String) m.invoke(packet);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                for (String name : new String[]{"f_134721_", "hostName"}) {
                    Field field = findField(packet.getClass(), name);
                    if (field != null) {
                        field.setAccessible(true);
                        return (String) field.get(packet);
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
                for (String name : new String[]{"f_134721_", "hostName"}) {
                    field = findField(packet.getClass(), name);
                    if (field != null) break;
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


    static class PlayerContext121 implements PlayerContext {

        private final Object connection;
        private String ip;

        PlayerContext121(Object connection) {
            this.connection = connection;
            this.ip = connection != null ? resolveRemoteAddress(connection) : "unknown";
        }

        private static String resolveRemoteAddress(Object conn) {
            try {
                for (Method m : conn.getClass().getMethods()) {
                    String n = m.getName();
                    if ((n.equals("getRemoteAddress") || n.equals("address") || n.equals("m_129529_"))
                            && m.getParameterCount() == 0) {
                        Object addr = m.invoke(conn);
                        if (addr instanceof InetSocketAddress) {
                            return ((InetSocketAddress) addr).getAddress().getHostAddress();
                        }
                    }
                }
                Field f = findConnectionAddressField(conn);
                if (f != null) {
                    f.setAccessible(true);
                    Object addr = f.get(conn);
                    if (addr instanceof InetSocketAddress) {
                        return ((InetSocketAddress) addr).getAddress().getHostAddress();
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
            for (String name : new String[]{"f_129525_", "address", "o", "socketAddress", "remoteAddress"}) {
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
