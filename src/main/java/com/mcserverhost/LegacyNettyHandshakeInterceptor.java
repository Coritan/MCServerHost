package com.mcserverhost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.logging.Logger;

/**
 * Netty-based handshake interceptor for Forge 1.12 and older versions where
 * SpongePowered Mixin is not available.
 *
 * Injects a ChannelHandler into the server's network pipeline by wrapping the
 * existing ChannelInitializer on each listening server channel, so that every
 * new incoming client connection gets our handler added to its pipeline.
 */
public class LegacyNettyHandshakeInterceptor {

    private static final String HANDLER_NAME = "mcsh_handshake_interceptor";
    private static final String INIT_HANDLER_NAME = "mcsh_init_wrapper";

    public static void install(PluginBase plugin) {
        Thread installThread = new Thread(() -> {
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (tryInstall(plugin)) {
                        plugin.getLogger().info("[MCServerHost] Legacy Netty handshake interceptor installed.");
                        return;
                    }
                } catch (Throwable e) {
                    plugin.getLogger().warning("[MCServerHost] Install attempt " + attempt + " failed: " + e.getMessage());
                }
            }
            plugin.getLogger().warning("[MCServerHost] Could not install legacy handshake interceptor after retries.");
        }, "MCSH-LegacyInterceptorInstaller");
        installThread.setDaemon(true);
        installThread.start();
    }

    private static boolean tryInstall(PluginBase plugin) throws Throwable {
        Object networkSystem = getNetworkSystem();
        if (networkSystem == null) return false;

        List<?> endpoints = getEndpoints(networkSystem);
        if (endpoints == null || endpoints.isEmpty()) return false;

        boolean injected = false;
        for (Object endpoint : endpoints) {
            try {
                Object channel = resolveChannel(endpoint);
                if (channel == null) continue;
                if (wrapChannelInitializer(channel, plugin)) {
                    injected = true;
                }
            } catch (Throwable e) {
                plugin.getLogger().warning("[MCServerHost] Failed to wrap channel initializer: " + e.getMessage());
            }
        }
        return injected;
    }

    private static Object getNetworkSystem() {
        try {
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            Object server = null;
            for (Method m : serverClass.getMethods()) {
                if (m.getParameterCount() == 0 && serverClass.isAssignableFrom(m.getReturnType())) {
                    try { server = m.invoke(null); break; } catch (Throwable ignored) {}
                }
            }
            if (server == null) return null;

            for (String methodName : new String[]{
                    "getNetworkSystem", "func_147137_ag",
                    "getServerConnectionListener", "getConnection"}) {
                try {
                    Method m = server.getClass().getMethod(methodName);
                    Object result = m.invoke(server);
                    if (result != null) return result;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<?> getEndpoints(Object networkSystem) throws Throwable {
        for (Field f : networkSystem.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object val = f.get(networkSystem);
            if (!(val instanceof List)) continue;
            List<?> list = (List<?>) val;
            if (list.isEmpty()) continue;
            Object first = list.get(0);
            if (first == null) continue;
            String typeName = first.getClass().getName();
            if (typeName.contains("ChannelFuture") || typeName.contains("Channel")) {
                return list;
            }
        }
        return null;
    }

    private static Object resolveChannel(Object endpoint) throws Throwable {
        String typeName = endpoint.getClass().getName();
        if (typeName.contains("ChannelFuture")) {
            Method channel = endpoint.getClass().getMethod("channel");
            return channel.invoke(endpoint);
        }
        return endpoint;
    }

    private static boolean wrapChannelInitializer(Object serverChannel, PluginBase plugin) throws Throwable {
        Method pipelineMethod = serverChannel.getClass().getMethod("pipeline");
        Object pipeline = pipelineMethod.invoke(serverChannel);

        Class<?> channelHandlerClass = Class.forName("io.netty.channel.ChannelHandler");
        Class<?> channelPipelineClass = pipeline.getClass();

        Method getMethod = null;
        try { getMethod = channelPipelineClass.getMethod("get", String.class); } catch (Throwable ignored) {}
        if (getMethod == null) return false;

        Object existingInitializer = null;
        try { existingInitializer = getMethod.invoke(pipeline, "packet_handler"); } catch (Throwable ignored) {}
        if (existingInitializer == null) {
            try { existingInitializer = getMethod.invoke(pipeline, "initializer"); } catch (Throwable ignored) {}
        }
        if (existingInitializer == null) {
            try {
                Method names = channelPipelineClass.getMethod("names");
                Object namesList = names.invoke(pipeline);
                if (namesList instanceof List) {
                    for (Object name : (List<?>) namesList) {
                        try {
                            existingInitializer = getMethod.invoke(pipeline, name);
                            if (existingInitializer != null) break;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (existingInitializer == null) return false;

        final Object wrapped = existingInitializer;
        Object wrapper = java.lang.reflect.Proxy.newProxyInstance(
            LegacyNettyHandshakeInterceptor.class.getClassLoader(),
            new Class[]{channelHandlerClass},
            (proxy, method, args) -> {
                String methodName = method.getName();
                if ((methodName.equals("channelRead") || methodName.equals("channelRegistered"))
                        && args != null && args.length >= 1) {
                    try {
                        Object ctx = args[0];
                        Object childChannel = ctx.getClass().getMethod("channel").invoke(ctx);
                        Object childPipeline = childChannel.getClass().getMethod("pipeline").invoke(childChannel);
                        addPacketInterceptorToPipeline(childPipeline, channelHandlerClass, plugin);
                    } catch (Throwable ignored) {}
                }
                if (methodName.equals("equals")) return proxy == args[0];
                if (methodName.equals("hashCode")) return System.identityHashCode(proxy);
                if (methodName.equals("toString")) return "MCServerHostWrapper";
                try {
                    return method.invoke(wrapped, args);
                } catch (Throwable ignored) {}
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class || method.getReturnType() == long.class) return 0;
                return null;
            }
        );

        try {
            Method replace = channelPipelineClass.getMethod("replace", channelHandlerClass, String.class, channelHandlerClass);
            replace.invoke(pipeline, existingInitializer, INIT_HANDLER_NAME, wrapper);
            return true;
        } catch (Throwable ignored) {}

        try {
            Method addFirst = channelPipelineClass.getMethod("addFirst", String.class, channelHandlerClass);
            addFirst.invoke(pipeline, INIT_HANDLER_NAME, wrapper);
            return true;
        } catch (Throwable ignored) {}

        return false;
    }

    private static void addPacketInterceptorToPipeline(Object pipeline, Class<?> channelHandlerClass, PluginBase plugin) throws Throwable {
        Object interceptor = java.lang.reflect.Proxy.newProxyInstance(
            LegacyNettyHandshakeInterceptor.class.getClassLoader(),
            new Class[]{channelHandlerClass},
            (proxy, method, args) -> {
                String methodName = method.getName();
                if (methodName.equals("channelRead") && args != null && args.length == 2) {
                    tryInterceptPacket(args[0], args[1], plugin);
                }
                if (methodName.equals("equals")) return proxy == args[0];
                if (methodName.equals("hashCode")) return System.identityHashCode(proxy);
                if (methodName.equals("toString")) return "MCServerHostInterceptor";
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class || method.getReturnType() == long.class) return 0;
                return null;
            }
        );

        try {
            Method addFirst = pipeline.getClass().getMethod("addFirst", String.class, channelHandlerClass);
            addFirst.invoke(pipeline, HANDLER_NAME, interceptor);
        } catch (Throwable ignored) {}
    }

    private static void tryInterceptPacket(Object ctx, Object packet, PluginBase plugin) {
        if (packet == null) return;
        String className = packet.getClass().getName();
        if (!className.contains("C00Handshake") && !className.contains("CHandshakePacket")
                && !className.contains("ClientIntentionPacket") && !className.contains("Handshake")) {
            return;
        }

        try {
            Object connection = extractConnectionFromCtx(ctx);
            PacketContext packetCtx = new LegacyPacketContext(packet);
            PlayerContext player = new LegacyPlayerContext(connection);
            plugin.getHandshakeHandler().handleHandshake(packetCtx, player);
        } catch (PluginException e) {
            plugin.getDebugger().exception(e);
        } catch (Exception e) {
            plugin.getDebugger().exception(e);
        }
    }

    private static Object extractConnectionFromCtx(Object ctx) {
        if (ctx == null) return null;
        try {
            for (Field f : ctx.getClass().getDeclaredFields()) {
                String typeName = f.getType().getName();
                if (typeName.contains("NetworkManager") || typeName.contains("Connection")) {
                    f.setAccessible(true);
                    return f.get(ctx);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }


    private static class LegacyPacketContext implements PacketContext {

        private final Object packet;

        LegacyPacketContext(Object packet) {
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
                for (String name : new String[]{"getHostName", "getHost", "func_149571_g"}) {
                    try {
                        Method m = packet.getClass().getMethod(name);
                        Object val = m.invoke(packet);
                        if (val instanceof String) return (String) val;
                    } catch (NoSuchMethodException ignored) {}
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


    private static class LegacyPlayerContext implements PlayerContext {

        private final Object connection;
        private String ip;

        LegacyPlayerContext(Object connection) {
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
            } catch (Exception ignored) {}
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
            this.ip = newAddr.getAddress().getHostAddress();
            if (connection == null) return;
            try {
                Field addressField = findConnectionAddressField(connection);
                if (addressField == null) return;
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
