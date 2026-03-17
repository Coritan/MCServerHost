package com.mcserverhost.mixin;

import com.mcserverhost.ForgePlugin;
import com.mcserverhost.PacketContext;
import com.mcserverhost.PlayerContext;
import com.mcserverhost.PluginException;
import com.mcserverhost.SpoofableAddress;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.handshake.client.CHandshakePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;

/**
 * Injects into ServerHandshakeNetHandler on Forge 1.16.5.
 *
 * At runtime the SRG jar has class net.minecraft.network.handshake.ServerHandshakeNetHandler
 * with method func_147383_a(CHandshakePacket) and field field_147386_b (NetworkManager).
 * remap = false because we use SRG method names directly.
 */
@Mixin(targets = "net.minecraft.network.handshake.ServerHandshakeNetHandler", remap = false)
public abstract class MixinClientIntentionPacket {

    @Inject(
        method = "func_147383_a",
        at = @At("HEAD"),
        remap = false,
        require = 0
    )
    private void mcsh$onHandleIntention(CHandshakePacket packet, CallbackInfo ci) {
        ForgePlugin plugin = ForgePlugin.getInstance();
        if (plugin == null) return;

        try {
            NetworkManager connection = extractNetworkManager(this);
            if (connection == null) return;

            PacketContext packetCtx = new ForgePacketContext(packet);
            PlayerContext player = new ForgePlayerContext(connection);

            plugin.getDebugger().info("Forge 1.16.5 handshake: hostname=\"%s\" ip=%s", packetCtx.getPayloadString(), player.getIP());

            plugin.getHandshakeHandler().handleHandshake(packetCtx, player);
        } catch (PluginException e) {
            plugin.getDebugger().exception(e);
        } catch (Throwable e) {
            plugin.getDebugger().warn("Forge 1.16.5 mixin error: %s: %s", e.getClass().getName(), e.getMessage());
        }
    }

    private static NetworkManager extractNetworkManager(Object listener) {
        Class<?> clazz = listener.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (NetworkManager.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(listener);
                        if (val instanceof NetworkManager) return (NetworkManager) val;
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }


    private static class ForgePacketContext implements PacketContext {

        private final CHandshakePacket packet;

        ForgePacketContext(CHandshakePacket packet) {
            this.packet = packet;
        }

        @Override
        public String getPayloadString() {
            try {
                Field field = findHostNameField();
                field.setAccessible(true);
                return (String) field.get(packet);
            } catch (Exception e) {
                throw new PluginException.ReflectionException(e);
            }
        }

        @Override
        public void setPacketHostname(String hostname) {
            try {
                Field field = findHostNameField();
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

        private Field findHostNameField() throws NoSuchFieldException {
            for (String name : new String[]{"hostName", "field_149573_b", "host"}) {
                try {
                    return CHandshakePacket.class.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {}
            }
            for (Field f : CHandshakePacket.class.getDeclaredFields()) {
                if (f.getType() == String.class) return f;
            }
            throw new NoSuchFieldException("hostName not found on CHandshakePacket");
        }

        private static sun.misc.Unsafe getUnsafe() throws Exception {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        }

    }


    private static class ForgePlayerContext implements PlayerContext {

        private final NetworkManager connection;
        private String ip;

        ForgePlayerContext(NetworkManager connection) {
            this.connection = connection;
            this.ip = resolveRemoteIP(connection);
        }

        private static String resolveRemoteIP(NetworkManager conn) {
            try {
                java.net.SocketAddress addr = invokeGetRemoteAddress(conn);
                if (addr instanceof InetSocketAddress) {
                    return ((InetSocketAddress) addr).getAddress().getHostAddress();
                }
            } catch (Exception ignored) {}
            return "unknown";
        }

        private static java.net.SocketAddress invokeGetRemoteAddress(NetworkManager conn) throws Exception {
            for (String name : new String[]{"func_74430_c", "getRemoteAddress"}) {
                try {
                    java.lang.reflect.Method m = NetworkManager.class.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return (java.net.SocketAddress) m.invoke(conn);
                } catch (NoSuchMethodException ignored) {}
            }
            for (Field f : NetworkManager.class.getDeclaredFields()) {
                if (java.net.SocketAddress.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (java.net.SocketAddress) f.get(conn);
                }
            }
            return null;
        }

        @Override
        public String getUUID() { return "unknown"; }

        @Override
        public String getName() { return "unknown"; }

        @Override
        public String getIP() { return ip; }

        @Override
        public void setIP(InetSocketAddress ip) {
            try {
                this.ip = ip.getAddress().getHostAddress();
                ((SpoofableAddress) connection).mcsh$setSpoofedAddress(ip);
            } catch (Exception e) {
                throw new PluginException.PlayerManipulationException(e);
            }
        }

    }

}
