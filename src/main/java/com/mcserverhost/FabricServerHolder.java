package com.mcserverhost;

/**
 * Static holder populated by the Fabric-only MinecraftServer constructor mixin.
 * Kept out of any Minecraft-typed signature so it can be loaded on every
 * platform (Forge, Paper, Bungee, Velocity) without class-resolution issues.
 */
public final class FabricServerHolder {

    private static volatile Object server;

    private FabricServerHolder() {}

    public static void setServer(Object instance) {
        if (server == null) server = instance;
    }

    public static Object getServer() {
        return server;
    }
}
