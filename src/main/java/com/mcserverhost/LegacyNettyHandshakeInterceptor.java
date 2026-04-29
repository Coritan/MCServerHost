package com.mcserverhost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Mapping-agnostic Netty-based handshake interceptor. Primary install path
 * on Fabric and fallback on legacy Forge <=1.12.
 */
public class LegacyNettyHandshakeInterceptor {

    private static final String ACCEPT_OBSERVER_NAME = "mcsh_accept_observer";
    private static final String HANDSHAKE_INTERCEPTOR_NAME = "mcsh_handshake_interceptor";

    public static void install(PluginBase plugin) {
        Logger log = plugin.getLogger();
        log.info("[MCServerHost] Starting Netty installer thread...");
        Thread installThread = new Thread(() -> {
            for (int attempt = 0; attempt < 120; attempt++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (tryInstall(plugin, attempt)) {
                        log.info("[MCServerHost] Netty handshake interceptor installed on attempt " + attempt + ".");
                        return;
                    }
                } catch (Throwable e) {
                    if (attempt % 20 == 0) {
                        log.warning("[MCServerHost] Install attempt " + attempt + " threw: " + e);
                        e.printStackTrace();
                    }
                }
            }
            log.warning("[MCServerHost] Could not install Netty handshake interceptor after retries.");
        }, "MCSH-NettyInstaller");
        installThread.setDaemon(true);
        installThread.start();
    }

    private static boolean tryInstall(PluginBase plugin, int attempt) throws Throwable {
        Logger log = plugin.getLogger();
        Object server = findMinecraftServer(plugin, attempt);
        if (server == null) {
            if (attempt % 20 == 0) log.info("[MCServerHost] [attempt " + attempt + "] server instance not yet available");
            return false;
        }
        if (attempt % 20 == 0 || attempt == 1) {
            log.info("[MCServerHost] [attempt " + attempt + "] server found: " + server.getClass().getName());
        }

        Object networkSystem = findNetworkSystemIn(server, plugin);
        if (networkSystem == null) {
            if (attempt % 20 == 0) log.info("[MCServerHost] [attempt " + attempt + "] network system not yet available on server");
            return false;
        }
        log.info("[MCServerHost] network system found: " + networkSystem.getClass().getName());

        List<?> endpoints = findChannelFutureList(networkSystem, plugin);
        if (endpoints == null || endpoints.isEmpty()) {
            if (attempt % 20 == 0) log.info("[MCServerHost] [attempt " + attempt + "] no channel futures yet");
            return false;
        }
        log.info("[MCServerHost] endpoints count: " + endpoints.size());

        Class<?> channelHandlerClass = Class.forName("io.netty.channel.ChannelHandler");

        boolean any = false;
        for (Object endpoint : endpoints) {
            try {
                Object channel = resolveChannel(endpoint);
                if (channel == null) continue;
                log.info("[MCServerHost] server channel: " + channel.getClass().getName() + " -> " + channel);
                if (installAcceptObserver(channel, channelHandlerClass, plugin)) any = true;
            } catch (Throwable e) {
                log.warning("[MCServerHost] Failed on endpoint: " + e);
                e.printStackTrace();
            }
        }
        return any;
    }

    // -------------------------------------------------------------------------
    // Server discovery
    // -------------------------------------------------------------------------

    private static Object findMinecraftServer(PluginBase plugin, int attempt) {
        Object captured = FabricServerHolder.getServer();
        if (captured != null) {
            if (attempt == 1) plugin.getLogger().info("[MCServerHost] Server obtained from FabricServerHolder (mixin): " + captured.getClass().getName());
            return captured;
        }

        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = fabricLoader.getMethod("getInstance").invoke(null);
            Object game = fabricLoader.getMethod("getGameInstance").invoke(loader);
            if (game != null) {
                if (attempt == 1) plugin.getLogger().info("[MCServerHost] Server obtained via FabricLoader.getGameInstance(): " + game.getClass().getName());
                FabricServerHolder.setServer(game);
                return game;
            }
            if (attempt % 20 == 0) plugin.getLogger().info("[MCServerHost] FabricLoader.getGameInstance() returned null");
        } catch (ClassNotFoundException nf) {
            if (attempt == 1) plugin.getLogger().info("[MCServerHost] FabricLoader not present (non-Fabric environment)");
        } catch (Throwable e) {
            if (attempt % 20 == 0) plugin.getLogger().warning("[MCServerHost] FabricLoader.getGameInstance() failed: " + e);
        }

        try {
            Class<?> serverClass = Class.forName("net.minecraft.server.MinecraftServer");
            for (Method m : serverClass.getMethods()) {
                if (m.getParameterCount() == 0 && serverClass.isAssignableFrom(m.getReturnType())
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    try {
                        Object v = m.invoke(null);
                        if (v != null) {
                            plugin.getLogger().info("[MCServerHost] Server obtained via static MinecraftServer." + m.getName() + "()");
                            return v;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static Object findNetworkSystemIn(Object obj, PluginBase plugin) {
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
                    if (findChannelFutureList(v, null) != null) {
                        plugin.getLogger().info("[MCServerHost] Network system found on " + c.getName() + "." + f.getName() + " (" + v.getClass().getName() + ")");
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static List<?> findChannelFutureList(Object obj, PluginBase plugin) {
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
                        if (plugin != null) plugin.getLogger().info("[MCServerHost] Channel list found on " + c.getName() + "." + f.getName() + " (element=" + tn + ")");
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
            return endpoint.getClass().getMethod("channel").invoke(endpoint);
        }
        return endpoint;
    }

    // -------------------------------------------------------------------------
    // Pipeline installation
    // -------------------------------------------------------------------------

    private static boolean installAcceptObserver(Object serverChannel, Class<?> channelHandlerClass, PluginBase plugin) throws Throwable {
        Logger log = plugin.getLogger();
        Object pipeline = serverChannel.getClass().getMethod("pipeline").invoke(serverChannel);
        Class<?> pipelineClass = pipeline.getClass();

        try {
            Object existing = pipelineClass.getMethod("get", String.class).invoke(pipeline, ACCEPT_OBSERVER_NAME);
            if (existing != null) {
                log.info("[MCServerHost] Accept observer already present");
                return true;
            }
        } catch (Throwable ignored) {}

        try {
            Object names = pipelineClass.getMethod("names").invoke(pipeline);
            log.info("[MCServerHost] Parent pipeline names: " + names);
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
                            log.info("[MCServerHost] New child channel accepted: " + child);
                            scheduleInterceptorInsertion(child, channelHandlerClass, plugin);
                        }
                    } catch (Throwable e) {
                        log.warning("[MCServerHost] accept observer error: " + e);
                    }
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
            log.info("[MCServerHost] Accept observer installed on " + serverChannel);
            return true;
        } catch (Throwable e) {
            log.warning("[MCServerHost] addFirst failed: " + e);
            return false;
        }
    }

    private static void scheduleInterceptorInsertion(Object childChannel, Class<?> channelHandlerClass, PluginBase plugin) {
        Logger log = plugin.getLogger();
        try {
            Object eventLoop = childChannel.getClass().getMethod("eventLoop").invoke(childChannel);
            Runnable task = () -> addHandshakeInterceptorWithRetry(childChannel, channelHandlerClass, plugin, 0);
            Method execute = eventLoop.getClass().getMethod("execute", Runnable.class);
            execute.invoke(eventLoop, task);
        } catch (Throwable e) {
            log.warning("[MCServerHost] Failed to schedule interceptor insertion: " + e);
            addHandshakeInterceptorWithRetry(childChannel, channelHandlerClass, plugin, 0);
        }
    }

    private static void addHandshakeInterceptorWithRetry(Object childChannel, Class<?> channelHandlerClass, PluginBase plugin, int attempt) {
        Logger log = plugin.getLogger();
        try {
            Object pipeline = childChannel.getClass().getMethod("pipeline").invoke(childChannel);
            Object existing = pipeline.getClass().getMethod("get", String.class).invoke(pipeline, HANDSHAKE_INTERCEPTOR_NAME);
            if (existing != null) return;

            Object packetHandler = pipeline.getClass().getMethod("get", String.class).invoke(pipeline, "packet_handler");
            if (packetHandler == null) {
                if (attempt < 10) {
                    try {
                        Object eventLoop = childChannel.getClass().getMethod("eventLoop").invoke(childChannel);
                        Method schedule = eventLoop.getClass().getMethod("schedule", Runnable.class, long.class, java.util.concurrent.TimeUnit.class);
                        schedule.invoke(eventLoop, (Runnable) () -> addHandshakeInterceptorWithRetry(childChannel, channelHandlerClass, plugin, attempt + 1), 10L, java.util.concurrent.TimeUnit.MILLISECONDS);
                        return;
                    } catch (Throwable ignored) {}
                }
                try {
                    Object names = pipeline.getClass().getMethod("names").invoke(pipeline);
                    log.warning("[MCServerHost] packet_handler never appeared. Pipeline names: " + names);
                } catch (Throwable ignored) {}
                return;
            }

            try {
                Object names = pipeline.getClass().getMethod("names").invoke(pipeline);
                log.info("[MCServerHost] Child pipeline names before insert: " + names);
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

            Method addBefore = pipeline.getClass().getMethod("addBefore", String.class, String.class, channelHandlerClass);
            addBefore.invoke(pipeline, "packet_handler", HANDSHAKE_INTERCEPTOR_NAME, interceptor);
            log.info("[MCServerHost] Handshake interceptor installed on child: " + childChannel);
        } catch (Throwable e) {
            log.warning("[MCServerHost] addHandshakeInterceptor failed: " + e);
            e.printStackTrace();
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
        Logger log = plugin.getLogger();
        String className = packet.getClass().getName();
        if (!isHandshakePacket(packet)) {
            return;
        }
        log.info("[MCServerHost] Handshake packet received: " + className);

        try {
            Object connection = extractConnectionFromCtx(ctx);
            Object channel = extractChannelFromCtx(ctx);
            log.info("[MCServerHost] connection=" + (connection == null ? "null" : connection.getClass().getName())
                    + " channel=" + (channel == null ? "null" : channel.getClass().getName()));
            PacketContext packetCtx = new LegacyPacketContext(packet);
            PlayerContext player = new LegacyPlayerContext(connection, channel);
            log.info("[MCServerHost] hostname=\"" + packetCtx.getPayloadString() + "\" realIp=" + player.getIP());
            plugin.getHandshakeHandler().handleHandshake(packetCtx, player);
            log.info("[MCServerHost] After rewrite: ip=" + player.getIP());
        } catch (PluginException e) {
            plugin.getDebugger().exception(e);
            log.warning("[MCServerHost] PluginException: " + e);
        } catch (Exception e) {
            plugin.getDebugger().exception(e);
            log.warning("[MCServerHost] Exception: " + e);
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
        for (Class<?> i : packet.getClass().getInterfaces()) {
            if (i.getName().contains("Handshake") || i.getName().contains("Intention")) return true;
        }
        return false;
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
