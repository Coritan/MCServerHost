package com.mcserverhost;

/**
 * Captures the Fabric ServerConnectionListener (intermediary class_3242)
 * instance at construction time. Gives us direct access without having to
 * walk MinecraftServer's field graph, which has been unreliable on modern
 * versions with Java 25 module restrictions.
 */
public final class FabricNetworkIoHolder {

    private static volatile Object instance;

    private FabricNetworkIoHolder() {}

    public static void set(Object v) {
        if (instance == null) instance = v;
    }

    public static Object get() {
        return instance;
    }
}
