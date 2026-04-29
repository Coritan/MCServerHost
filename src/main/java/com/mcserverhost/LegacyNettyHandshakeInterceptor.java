package com.mcserverhost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Mapping-agnostic Netty-based handshake interceptor. Used as the sole
 * install path on Fabric (where Yarn / Intermediary names make named
 * reflection impossible) and as a fallback on legacy Forge (1.12 and older,
 * where SpongePowered Mixin is not viable).
 *
 * Strategy, purely by reflection on Netty / java.lang types — no Minecraft
 * method or field names are referenced:
 *
 *   1. Locate the running MinecraftServer. Try the classname-based path
 *      first, then fall back to scanning the server thread's Runnable graph
 *      for any object whose field graph contains a List of
 *      io.netty.channel.ChannelFuture.
 *   2. From that network-system-like object, extract the ChannelFuture list
 *      and resolve each entry to its listening server Channel.
 *   3. Add an observer to each server channel's pipeline that watches
 *      channelRead — its msg IS the new child Channel. For every child,
 *      inject a handshake interceptor into its pipeline positioned before
 *      the literal "packet_handler" handler (Minecraft uses this literal
 *      across all mappings) so we see decoded packets.
 */
public class LegacyNettyHandshakeInterceptor {

    private static final String ACCEPT_OBSERVER_NAME = "mcsh_accept_observer";
    private static final String HANDSHAKE_INTERCEPTOR_NAME = "mcsh_handshake_interceptor";

    public static void install(PluginBase plugin) {
        Thread installThread = new Thread(() -> {
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (tryInstall(plugin)) {
                        plugin.getLogger().info("[MCServerHost] Netty handshake interceptor installed.");
                        return;
                    }
                } catch (Throwable e) {
                    if (attempt == 0 || attempt == 30) {
                        plugin.getLogger().warning("[MCServerHost] Install attempt " + attempt + " failed: " + e.getMessage());
                    }
                }
            }
            plugin.getLogger().warning("[MCServerHost] Could not install Netty handshake interceptor after retries.");
        }, "MCSH-NettyInstaller");
        installThread.setDaemon(true);
        installThread.start();
    }

    private static boolean tryInstall(PluginBase plugin) throws Throwable {
        List<Object> serverChannels = findServerChannels();
        if (serverChannels.isEmpty()) return false;

        Class<?> channelHandlerClass = Class.forName("io.netty.channel.ChannelHandler");

        boolean any = false;
        for (Object serverChannel : serverChannels) {
            try {
                if (installAcceptObserver(serverChannel, channelHandlerClass, plugin)) any = true;
            } catch (Throwable ignored) {}
        }
        return any;
    }

    // -------------------------------------------------------------------------
    // Server channel discovery — fully mapping-agnostic
    // -------------------------------------------------------------------------

    private static List<Object> findServerChannels() {
        List<Object> out = new ArrayList<>();
        Object server = findMinecraftServer();
        if (server == null) return out;

        Object networkSystem = findNetworkSystemIn(server);
        if (networkSystem == null) return out;

        List<?> endpoints = findChannelFutureList(networkSystem);
        if (endpoints == null) return out;

        for (Object endpoint : endpoints) {
            try {
                Object channel = resolveChannel(endpoint);
                if (channel != null) out.add(channel);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static Object findMinecraftServer() {
        try {
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            for (Method m : serverClass.getMethods()) {
                if (m.getParameterCount() == 0 && serverClass.isAssignableFrom(m.getReturnType())) {
                    try {
                        Object v = m.invoke(null);
                        if (v != null) return v;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            Field targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t == null) continue;
                String name = t.getName();
                if (name == null || !name.contains("Server thread")) continue;
                try {
                    Object target = targetField.get(t);
                    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                    Object server = searchForServer(target, 4, seen);
                    if (server != null) return server;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static Object searchForServer(Object obj, int depth, Set<Object> seen) {
        if (obj == null || depth <= 0) return null;
        if (!seen.add(obj)) return null;
        if (findNetworkSystemIn(obj) != null) return obj;

        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray() || ft == String.class || ft == Class.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    Object r = searchForServer(v, depth - 1, seen);
                    if (r != null) return r;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object findNetworkSystemIn(Object obj) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray() || ft == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) continue;
                    if (findChannelFutureList(v) != null) return v;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static List<?> findChannelFutureList(Object obj) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (!(v instanceof List)) continue;
                    List<?> list = (List<?>) v;
                    if (list.isEmpty()) continue;
                    Object first = list.get(0);
                    if (first == null) continue;
                    String tn = first.getClass().getName();
                    if (tn.contains("ChannelFuture") || tn.startsWith("io.netty.channel.")) {
                        return list;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
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

    // -------------------------------------------------------------------------
    // Pipeline installation
    // -------------------------------------------------------------------------

    private static boolean installAcceptObserver(Object serverChannel, Class<?> channelHandlerClass, PluginBase plugin) throws Throwable {
        Object pipeline = serverChannel.getClass().getMethod("pipeline").invoke(serverChannel);
        Class<?> pipelineClass = pipeline.getClass();

        try {
            Object existing = pipelineClass.getMethod("get", String.class).invoke(pipeline, ACCEPT_OBSERVER_NAME);
            if (existing != null) return true;
        } catch (Throwable ignored) {}

        Object observer = java.lang.reflect.Proxy.newProxyInstance(
            LegacyNettyHandshakeInterceptor.class.getClassLoader(),
            new Class[]{channelHandlerClass},
            (proxy, method, args) -> {
                String methodName = method.getName();
                if (methodName.equals("channelRead") && args != null && args.length == 2) {
                    Object ctx = args[0];
                    Object child = args[1];
                    try {
                        if (child != null && isChannel(child)) {
                            Object childPipeline = child.getClass().getMethod("pipeline").invoke(child);
                            addHandshakeInterceptor(childPipeline, channelHandlerClass, plugin);
                        }
                    } catch (Throwable ignored) {}
                    try {
                        Method fire = ctx.getClass().getMethod("fireChannelRead", Object.class);
                        fire.invoke(ctx, child);
                    } catch (Throwable ignored) {}
                    return null;
                }
                return defaultInvoke(proxy, method, args);
            }
        );

        try {
            Method addFirst = pipelineClass.getMethod("addFirst", String.class, channelHandlerClass);
            addFirst.invoke(pipeline, ACCEPT_OBSERVER_NAME, observer);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static void addHandshakeInterceptor(Object pipeline, Class<?> channelHandlerClass, PluginBase plugin) {
        try {
            Object existing = pipeline.getClass().getMethod("get", String.class).invoke(pipeline, HANDSHAKE_INTERCEPTOR_NAME);
            if (existing != null) return;
        } catch (Throwable ignored) {}

        Object interceptor = java.lang.reflect.Proxy.newProxyInstance(
            LegacyNettyHandshakeInterceptor.class.getClassLoader(),
            new Class[]{channelHandlerClass},
            (proxy, method, args) -> {
                String methodName = method.getName();
                if (methodName.equals("channelRead") && args != null && args.length == 2) {
                    Object ctx = args[0];
                    Object packet = args[1];
                    tryInterceptPacket(ctx, packet, plugin);
                    try {
                        Method fire = ctx.getClass().getMethod("fireChannelRead", Object.class);
                        fire.invoke(ctx, packet);
                    } catch (Throwable ignored) {}
                    return null;
                }
                return defaultInvoke(proxy, method, args);
            }
        );

        try {
            Method addBefore = pipeline.getClass().getMethod("addBefore", String.class, String.class, channelHandlerClass);
            addBefore.invoke(pipeline, "packet_handler", HANDSHAKE_INTERCEPTOR_NAME, interceptor);
            return;
        } catch (Throwable ignored) {}

        try {
            Method addLast = pipeline.getClass().getMethod("addLast", String.class, channelHandlerClass);
            addLast.invoke(pipeline, HANDSHAKE_INTERCEPTOR_NAME, interceptor);
        } catch (Throwable ignored) {}
    }

    private static boolean isChannel(Object obj) {
        try {
            Class<?> channelClass = Class.forName("io.netty.channel.Channel");
            return channelClass.isInstance(obj);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object defaultInvoke(Object proxy, Method method, Object[] args) {
        String methodName = method.getName();
        if (methodName.equals("equals")) return args != null && proxy == args[0];
        if (methodName.equals("hashCode")) return System.identityHashCode(proxy);
        if (methodName.equals("toString")) return "MCServerHostProxy";
        if (methodName.equals("isSharable")) return true;
        if (args != null && args.length >= 1 && args[0] != null) {
            try {
                Class<?> ctxClass = Class.forName("io.netty.channel.ChannelHandlerContext");
                if (ctxClass.isInstance(args[0])) {
                    Object ctx = args[0];
                    try {
                        if (methodName.startsWith("channel") || methodName.startsWith("user") || methodName.equals("exceptionCaught")) {
                            String fireName = "fire" + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
                            if (args.length == 1) {
                                Method fire = ctx.getClass().getMethod(fireName);
                                fire.invoke(ctx);
                            } else if (args.length == 2) {
                                Method fire = ctx.getClass().getMethod(fireName, Object.class);
                                fire.invoke(ctx, args[1]);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        if (method.getReturnType() == boolean.class) return false;
        if (method.getReturnType() == int.class) return 0;
        if (method.getReturnType() == long.class) return 0L;
        return null;
    }

    // -------------------------------------------------------------------------
    // Handshake packet handling
    // -------------------------------------------------------------------------

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
            Object pipeline = ctx.getClass().getMethod("pipeline").invoke(ctx);
            Method get = pipeline.getClass().getMethod("get", String.class);
            Object handler = get.invoke(pipeline, "packet_handler");
            if (handler != null) return handler;
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
                Class<?> c = conn.getClass();
                while (c != null) {
                    for (Field f : c.getDeclaredFields()) {
                        if (java.net.SocketAddress.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object addr = f.get(conn);
                            if (addr instanceof InetSocketAddress) {
                                return ((InetSocketAddress) addr).getAddress().getHostAddress();
                            }
                        }
                    }
                    c = c.getSuperclass();
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
