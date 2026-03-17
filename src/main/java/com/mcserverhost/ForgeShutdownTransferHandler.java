package com.mcserverhost;

import java.lang.reflect.Method;
import java.util.logging.Logger;

public class ForgeShutdownTransferHandler {

    public static void transferAll(HubConfig hubConfig, Object serverInstance, Logger logger) {
        String host = hubConfig.getHost();
        int port = hubConfig.getPort();

        Object server = serverInstance != null ? serverInstance : getMinecraftServer(logger);
        if (server == null) {
            logger.warning("[MCServerHost] Could not get MinecraftServer to transfer players on shutdown.");
            return;
        }

        Iterable<?> players = getOnlinePlayers(server, logger);
        if (players == null) return;

        boolean anyTransferred = false;
        for (Object player : players) {
            try {
                sendTransferPacket(player, host, port, logger);
                anyTransferred = true;
            } catch (Throwable e) {
                logger.warning("[MCServerHost] Failed to transfer player on shutdown: " + e.getMessage());
            }
        }

        if (anyTransferred) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Object getMinecraftServer(Logger logger) {
        String[] serverClassNames = {
            "net.minecraft.server.MinecraftServer"
        };
        for (String className : serverClassNames) {
            try {
                Class<?> clazz = Class.forName(className);
                try {
                    Method getInstance = clazz.getMethod("getServer");
                    return getInstance.invoke(null);
                } catch (Throwable ignored) {
                }
                try {
                    Method getInstance = clazz.getMethod("getInstance");
                    return getInstance.invoke(null);
                } catch (Throwable ignored) {
                }
                for (Method m : clazz.getMethods()) {
                    if (m.getParameterCount() == 0 && clazz.isAssignableFrom(m.getReturnType())) {
                        try {
                            return m.invoke(null);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Iterable<?> getOnlinePlayers(Object server, Logger logger) {
        String[] methodNames = {"getPlayerList", "getPlayers", "getAllPlayers"};
        for (String methodName : methodNames) {
            try {
                Method m = server.getClass().getMethod(methodName);
                Object result = m.invoke(server);
                if (result instanceof Iterable) {
                    return (Iterable<?>) result;
                }
                if (result != null) {
                    try {
                        Method getPlayers = result.getClass().getMethod("getPlayers");
                        Object players = getPlayers.invoke(result);
                        if (players instanceof Iterable) {
                            return (Iterable<?>) players;
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Method getAllPlayers = result.getClass().getMethod("getAllPlayers");
                        Object players = getAllPlayers.invoke(result);
                        if (players instanceof Iterable) {
                            return (Iterable<?>) players;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        logger.warning("[MCServerHost] Could not get online players list for shutdown transfer.");
        return null;
    }

    private static void sendTransferPacket(Object serverPlayer, String host, int port, Logger logger) throws Throwable {
        Object packet = null;
        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.common.ClientboundTransferPacket");
            try {
                packet = packetClass.getDeclaredConstructor(String.class, int.class).newInstance(host, port);
            } catch (NoSuchMethodException ignored) {
                packet = packetClass.getDeclaredConstructors()[0].newInstance(host, port);
            }
        } catch (ClassNotFoundException ignored) {
            return;
        }

        Object connection = ForgeHubCommand.getConnectionPublic(serverPlayer);
        if (connection == null) return;
        ForgeHubCommand.sendOnConnectionPublic(connection, packet);
    }

}
