package com.mcserverhost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

public class ForgeHubCommand {

    public static void register(Object dispatcher, HubConfig hubConfig, Logger logger) {
        try {
            registerLiteral(dispatcher, "mcsh", hubConfig, logger);
            for (String alias : hubConfig.getAliases()) {
                registerLiteral(dispatcher, alias, hubConfig, logger);
            }
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Failed to register hub commands: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerLiteral(Object dispatcher, String name, HubConfig hubConfig, Logger logger) throws Throwable {
        Class<?> literalClass = Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder");
        Method literalMethod = literalClass.getMethod("literal", String.class);
        Object builder = literalMethod.invoke(null, name);

        Class<?> commandInterface = Class.forName("com.mojang.brigadier.Command");
        Object command = java.lang.reflect.Proxy.newProxyInstance(
            ForgeHubCommand.class.getClassLoader(),
            new Class[]{commandInterface},
            (proxy, method, args) -> {
                if (method.getName().equals("run")) {
                    Object context = args[0];
                    handleHubCommand(context, hubConfig, logger);
                    return 1;
                }
                return null;
            }
        );

        Method executes = builder.getClass().getMethod("executes", commandInterface);
        executes.invoke(builder, command);

        Method register = dispatcher.getClass().getMethod("register", literalClass);
        register.invoke(dispatcher, builder);
    }

    private static void handleHubCommand(Object context, HubConfig hubConfig, Logger logger) {
        String host = hubConfig.getHost();
        int port = hubConfig.getPort();

        Object serverPlayer = null;
        try {
            Method getSource = context.getClass().getMethod("getSource");
            Object source = getSource.invoke(context);

            try {
                Method getPlayerOrException = source.getClass().getMethod("getPlayerOrException");
                serverPlayer = getPlayerOrException.invoke(source);
            } catch (Throwable ignored) {
            }

            if (serverPlayer == null) {
                try {
                    Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
                    if (serverPlayerClass.isInstance(source)) {
                        serverPlayer = source;
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Hub command: could not get player: " + e.getMessage());
            return;
        }

        if (serverPlayer == null) {
            logger.warning("[MCServerHost] Hub command can only be used by players.");
            return;
        }

        sendTransferPacket(serverPlayer, host, port, logger);
    }

    private static void sendTransferPacket(Object serverPlayer, String host, int port, Logger logger) {
        Object packet = null;
        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.common.ClientboundTransferPacket");
            try {
                packet = packetClass.getDeclaredConstructor(String.class, int.class).newInstance(host, port);
            } catch (NoSuchMethodException ignored) {
                packet = packetClass.getDeclaredConstructors()[0].newInstance(host, port);
            }
        } catch (ClassNotFoundException ignored) {
            logger.warning("[MCServerHost] Transfer packet not available on this Forge version (requires 1.20.5+).");
            return;
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Could not create transfer packet: " + e.getMessage());
            return;
        }

        try {
            Object connection = getConnection(serverPlayer);
            if (connection == null) {
                logger.warning("[MCServerHost] Could not find player connection to send transfer packet.");
                return;
            }
            sendOnConnection(connection, packet);
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Could not send transfer packet: " + e.getMessage());
        }
    }

    static Object getConnectionPublic(Object serverPlayer) {
        return getConnection(serverPlayer);
    }

    static void sendOnConnectionPublic(Object connection, Object packet) throws Throwable {
        sendOnConnection(connection, packet);
    }

    private static Object getConnection(Object serverPlayer) {
        String[] connectionFieldNames = {"connection", "f", "b"};
        for (String fieldName : connectionFieldNames) {
            try {
                Field field = Reflect.getDeclaredField(serverPlayer.getClass(), fieldName);
                field.setAccessible(true);
                Object val = field.get(serverPlayer);
                if (val != null && val.getClass().getName().toLowerCase().contains("connection")) {
                    return val;
                }
            } catch (Throwable ignored) {
            }
        }
        for (Field field : serverPlayer.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object val = field.get(serverPlayer);
                if (val != null && val.getClass().getName().toLowerCase().contains("connection")) {
                    return val;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void sendOnConnection(Object connection, Object packet) throws Throwable {
        Class<?> packetSuperType = null;
        for (Class<?> iface : packet.getClass().getInterfaces()) {
            if (iface.getSimpleName().equalsIgnoreCase("Packet")) {
                packetSuperType = iface;
                break;
            }
        }

        String[] sendMethodNames = {"send", "sendPacket", "a"};
        for (String methodName : sendMethodNames) {
            if (packetSuperType != null) {
                try {
                    Method m = connection.getClass().getMethod(methodName, packetSuperType);
                    m.invoke(connection, packet);
                    return;
                } catch (Throwable ignored) {
                }
            }
            for (Method m : connection.getClass().getMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                    try {
                        m.invoke(connection, packet);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        throw new IllegalStateException("Could not find send method on connection " + connection.getClass().getName());
    }

}
