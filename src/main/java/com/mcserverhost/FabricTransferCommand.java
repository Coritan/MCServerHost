package com.mcserverhost;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

public class FabricTransferCommand {

    public static void register(Object dispatcher, HubConfig hubConfig, Logger logger) {
        try {
            Class<?> literalClass = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder");
            Method literalMethod = literalClass.getMethod("literal", String.class);
            Object builder = literalMethod.invoke(null, "transfer");

            Class<?> commandInterface = Class.forName("com.mojang.brigadier.Command");
            Object command = Proxy.newProxyInstance(
                FabricTransferCommand.class.getClassLoader(),
                new Class[]{commandInterface},
                (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        handle(args[0], hubConfig, logger);
                        return 1;
                    }
                    return null;
                });

            Method executes = builder.getClass().getMethod("executes", commandInterface);
            executes.invoke(builder, command);
            Method register = dispatcher.getClass().getMethod("register", literalClass);
            register.invoke(dispatcher, builder);
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Failed to register /transfer: " + e.getMessage());
        }
    }

    private static void handle(Object context, HubConfig hubConfig, Logger logger) {
        try {
            Method getSource = context.getClass().getMethod("getSource");
            Object source = getSource.invoke(context);
            Object player = null;
            try {
                Method getPlayerOrException = source.getClass().getMethod("getPlayerOrException");
                player = getPlayerOrException.invoke(source);
            } catch (Throwable ignored) {
            }
            if (player == null) {
                try {
                    Method method_9207 = source.getClass().getMethod("method_9207");
                    player = method_9207.invoke(source);
                } catch (Throwable ignored) {
                }
            }
            if (player == null) {
                logger.warning("[MCServerHost] /transfer: must be run by a player.");
                return;
            }
            sendTransferPacket(player, hubConfig.getHost(), hubConfig.getPort(), logger);
        } catch (Throwable e) {
            logger.warning("[MCServerHost] /transfer failed: " + e.getMessage());
        }
    }

    static void sendTransferPacket(Object player, String host, int port, Logger logger) {
        Object packet;
        try {
            Class<?> packetClass;
            try {
                packetClass = Class.forName("net.minecraft.network.protocol.common.ClientboundTransferPacket");
            } catch (ClassNotFoundException e) {
                packetClass = Class.forName("net.minecraft.class_8788");
            }
            try {
                packet = packetClass.getDeclaredConstructor(String.class, int.class).newInstance(host, port);
            } catch (NoSuchMethodException ignored) {
                packet = packetClass.getDeclaredConstructors()[0].newInstance(host, port);
            }
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Transfer packet not available (requires 1.20.5+).");
            return;
        }

        try {
            Object connection = ForgeHubCommand.getConnectionPublic(player);
            if (connection == null) {
                logger.warning("[MCServerHost] No connection found for /transfer.");
                return;
            }
            ForgeHubCommand.sendOnConnectionPublic(connection, packet);
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Failed to send transfer packet: " + e.getMessage());
        }
    }
}
