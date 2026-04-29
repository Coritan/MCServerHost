package net.fabricmc.api;

/**
 * Stub of Fabric Loader's DedicatedServerModInitializer interface. The real
 * interface is provided at runtime by Fabric Loader when this JAR is loaded
 * as a Fabric mod. This stub only exists so the project compiles without a
 * Loom / Fabric Loader dependency.
 */
public interface DedicatedServerModInitializer {
    void onInitializeServer();
}
