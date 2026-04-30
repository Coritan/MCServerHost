package com.mcserverhost;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

public class FabricStopCommand {

    public static void register(Object dispatcher, HubConfig hubConfig, java.util.function.Supplier<Object> serverSupplier, Logger logger) {
        if (hubConfig == null || !hubConfig.isSendToHubOnShutdown()) return;
        try {
            Class<?> literalClass = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder");
            Class<?> requiresInterface = Class.forName("java.util.function.Predicate");
            Method literalMethod = literalClass.getMethod("literal", String.class);
            Object builder = literalMethod.invoke(null, "stop");

            Object requiresProxy = Proxy.newProxyInstance(
                FabricStopCommand.class.getClassLoader(),
                new Class[]{requiresInterface},
                (proxy, method, args) -> {
                    if (method.getName().equals("test")) {
                        return hasStopPermission(args[0]);
                    }
                    return null;
                });
            Method requires = builder.getClass().getMethod("requires", requiresInterface);
            requires.invoke(builder, requiresProxy);

            Class<?> commandInterface = Class.forName("com.mojang.brigadier.Command");
            Object command = Proxy.newProxyInstance(
                FabricStopCommand.class.getClassLoader(),
                new Class[]{commandInterface},
                (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        handle(args[0], hubConfig, serverSupplier, logger);
                        return 1;
                    }
                    return null;
                });
            Method executes = builder.getClass().getMethod("executes", commandInterface);
            executes.invoke(builder, command);

            Method register = dispatcher.getClass().getMethod("register", literalClass);
            register.invoke(dispatcher, builder);
            logger.info("[MCServerHost] /stop override registered.");
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Failed to register /stop override: " + e.getMessage());
        }
    }

    private static boolean hasStopPermission(Object source) {
        try {
            Method hasPermission = source.getClass().getMethod("hasPermission", int.class);
            return (Boolean) hasPermission.invoke(source, 4);
        } catch (Throwable ignored) {
        }
        try {
            Method method_9259 = source.getClass().getMethod("method_9259", int.class);
            return (Boolean) method_9259.invoke(source, 4);
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static void handle(Object context, HubConfig hubConfig, java.util.function.Supplier<Object> serverSupplier, Logger logger) {
        Object server = null;
        try {
            Method getSource = context.getClass().getMethod("getSource");
            Object source = getSource.invoke(context);
            for (String methodName : new String[]{"getServer", "method_9211"}) {
                try {
                    server = source.getClass().getMethod(methodName).invoke(source);
                    if (server != null) break;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (server == null && serverSupplier != null) server = serverSupplier.get();

        try {
            ForgeShutdownTransferHandler.transferAll(hubConfig, server, logger);
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Shutdown transfer failed: " + e.getMessage());
        }

        final Object finalServer = server;
        Thread stopper = new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            stopServer(finalServer, logger);
        }, "MCServerHost-Stopper");
        stopper.setDaemon(false);
        stopper.start();
    }

    private static void stopServer(Object server, Logger logger) {
        if (server == null) return;
        for (String methodName : new String[]{"halt", "stopServer", "method_3747", "method_3782"}) {
            try {
                Method m;
                try {
                    m = server.getClass().getMethod(methodName, boolean.class);
                    m.invoke(server, false);
                    return;
                } catch (NoSuchMethodException ignored) {
                }
                m = server.getClass().getMethod(methodName);
                m.invoke(server);
                return;
            } catch (Throwable ignored) {
            }
        }
        logger.warning("[MCServerHost] Could not invoke server stop via reflection.");
    }
}
