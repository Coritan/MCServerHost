package com.mcserverhost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Mapping-agnostic Netty-based handshake interceptor. Primary install path
 * on Fabric (Yarn/Intermediary makes named reflection impractical) and
 * fallback on legacy Forge 1.12 and older (no Mixin).
 *
 * Discovery:
 *   - The MinecraftServer instance is captured by a mixin into its
 *     constructor (see FabricServerHolder + mixin.fabric.MixinMinecraftServer).
 *     We also try a class-getter fallback so legacy Forge (<=1.12) still
 *     works without the mixin.
 *   - On the server instance, locate a field whose value transitively
 *     exposes a List of io.netty.channel.ChannelFuture (the network system).
 *
 * Injection:
 *   - For each listening server channel, install an "accept observer" that
 *     fires on channelRead. The msg IS the new child Channel.
 *   - For each child, defer interceptor insertion onto the child's
 *     EventLoop. That guarantees it runs AFTER Minecraft's ChannelInitializer
 *     has populated the pipeline with "packet_handler". We then insert our
 *     handler with addBefore("packet_handler", ...) so it receives fully
 *     decoded handshake packet objects.
 */
public class LegacyNettyHandshakeInterceptor {

    private static final String ACCEPT_OBSERVER_NAME = "mcsh_accept_observer";
    private static final String HANDSHAKE_INTERCEPTOR_NAME = "mcsh_handshake_interceptor";

    public static void install(PluginBase plugin) {
        Thread installThread = new Thread(() -> {
            for (int attempt = 0; attempt < 120; attempt++) {
                try {
                    Thread.sleep(500);
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
                    if (attempt == 0 || attempt == 60) {
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
            } catch (Throwable e) {
                plugin.getLogger().warning("[MCServerHost] Failed to install on server channel: " + e.getMessage());
            }
        }
        return any;
    }

    // -------------------------------------------------------------------------
    // Server channel discovery
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
        Object captured = FabricServerHolder.getServer();
        if (captured != null) return captured;

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
                            scheduleInterceptorInsertion(child, channelHandlerClass, plugin);
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

    private static void scheduleInterceptorInsertion(Object childChannel, Class<?> channelHandlerClass, PluginBase plugin) {
        try {
            Object eventLoop = childChannel.getClass().getMethod("eventLoop").invoke(childChannel);
            Runnable task = () -> {
                try {
                    Object pipeline = childChannel.getClass().getMethod("pipeline").invoke(childChannel);
                    addHandshakeInterceptor(pipeline, channelHandlerClass, plugin);
                } catch (Throwable ignored) {}
            };
            Method execute = eventLoop.getClass().getMethod("execute", Runnable.class);
            execute.invoke(eventLoop, task);
        } catch (Throwable ignored) {
            try {
                Object pipeline = childChannel.getClass().getMethod("pipeline").invoke(childChannel);
                addHandshakeInterceptor(pipeline, channelHandlerClass, plugin);
            } catch (Throwable ignored2) {}
        }
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

        boolean inserted = false;
        try {
            Method addBefore = pipeline.getClass().getMethod("addBefore", String.class, String.class, channelHandlerClass);
            addBefore.invoke(pipeline, "packet_handler", HANDSHAKE_INTERCEPTOR_NAME, interceptor);
            inserted = true;
        } catch (Throwable ignored) {}

        if (!inserted) {
            try {
                Method names = pipeline.getClass().getMethod("names");
                Object namesList = names.invoke(pipeline);
                if (namesList instanceof List) {
                    List<?> list = (List<?>) namesList;
                    for (int i = list.size() - 1; i >= 0; i--) {
                        Object n = list.get(i);
                        if (!(n instanceof String)) continue;
                        String sn = (String) n;
                        if (sn.equals(ACCEPT_OBSERVER_NAME) || sn.equals(HANDSHAKE_INTERCEPTOR_NAME)) continue;
                        try {
                            Method addBefore = pipeline.getClass().getMethod("addBefore", String.class, String.class, channelHandlerClass);
                            addBefore.invoke(pipeline, sn, HANDSHAKE_INTERCEPTOR_NAME, interceptor);
                            inserted = true;
                            break;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (!inserted) {
            try {
                Method addLast = pipeline.getClass().getMethod("addLast", String.class, channelHandlerClass);
                addLast.invoke(pipeline, HANDSHAKE_INTERCEPTOR_NAME, interceptor);
            } catch (Throwable ignored) {}
        }
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
        if (!isHandshakePacket(packet)) return;

        plugin.getDebugger().info("Fabric/Legacy handshake packet received: %s", className);

        try {
            Object connection = extractConnectionFromCtx(ctx);
            Object channel = extractChannelFromCtx(ctx);
            PacketContext packetCtx = new LegacyPacketContext(packet);
            PlayerContext player = new LegacyPlayerContext(connection, channel);
            plugin.getHandshakeHandler().handleHandshake(packetCtx, player);
        } catch (PluginException e) {
            plugin.getDebugger().exception(e);
        } catch (Exception e) {
            plugin.getDebugger().exception(e);
        }
    }

    private static boolean isHandshakePacket(Object packet) {
        Class<?> c = packet.getClass();
        while (c != null && c != Object.class) {
            String n = c.getName();
            String s = c.getSimpleName();
            if (n.contains("C00Handshake") || n.contains("CHandshakePacket")
                    || n.contains("ClientIntentionPacket") || n.contains("HandshakeC2SPacket")
                    || s.contains("Handshake") || s.contains("Intention")) {
                return true;
            }
            c = c.getSuperclass();
        }
        Class<?>[] ifaces = packet.getClass().getInterfaces();
        for (Class<?> i : ifaces) {
            if (i.getName().contains("Handshake") || i.getName().contains("Intention")) return true;
        }
        boolean hasIntField = false, hasStringField = false, hasPortField = false;
        for (Field f : packet.getClass().getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == int.class) hasIntField = true;
            if (t == short.class) hasPortField = true;
            if (t == String.class) hasStringField = true;
        }
        return hasIntField && hasStringField && hasPortField;
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

    private static Object extractChannelFromCtx(Object ctx) {
        if (ctx == null) return null;
        try {
            return ctx.getClass().getMethod("channel").invoke(ctx);
        } catch (Throwable ignored) {
            return null;
        }
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
        private final Object channel;
        private String ip;

        LegacyPlayerContext(Object connection, Object channel) {
            this.connection = connection;
            this.channel = channel;
            this.ip = resolveRemoteAddress();
        }

        private String resolveRemoteAddress() {
            if (channel != null) {
                try {
                    Object addr = channel.getClass().getMethod("remoteAddress").invoke(channel);
                    if (addr instanceof InetSocketAddress) {
                        return ((InetSocketAddress) addr).getAddress().getHostAddress();
                    }
                } catch (Throwable ignored) {}
            }
            if (connection != null) {
                try {
                    for (Method m : connection.getClass().getMethods()) {
                        String n = m.getName();
                        if ((n.equals("getRemoteAddress") || n.equals("getAddress") || n.equals("address"))
                                && m.getParameterCount() == 0) {
                            Object addr = m.invoke(connection);
                            if (addr instanceof InetSocketAddress) {
                                return ((InetSocketAddress) addr).getAddress().getHostAddress();
                            }
                        }
                    }
                    Class<?> c = connection.getClass();
                    while (c != null) {
                        for (Field f : c.getDeclaredFields()) {
                            if (java.net.SocketAddress.class.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                Object addr = f.get(connection);
                                if (addr instanceof InetSocketAddress) {
                                    return ((InetSocketAddress) addr).getAddress().getHostAddress();
                                }
                            }
                        }
                        c = c.getSuperclass();
                    }
                } catch (Exception ignored) {}
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
            this.ip = newAddr.getAddress().getHostAddress();
            if (connection == null) return;
            try {
                Field addressField = findSocketAddressField(connection.getClass());
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

        private static Field findSocketAddressField(Class<?> startClass) {
            Class<?> clazz = startClass;
            while (clazz != null && clazz != Object.class) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (java.net.SocketAddress.class.isAssignableFrom(f.getType())) {
                        return f;
                    }
                }
                clazz = clazz.getSuperclass();
            }
            return null;
        }

    }

}
