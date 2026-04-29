package com.mcserverhost;

import net.fabricmc.api.DedicatedServerModInitializer;

import java.io.File;
import java.util.logging.Logger;

/**
 * Fabric mod entry point, discovered via fabric.mod.json. Supports Minecraft
 * 1.14 through 26.1 in a single JAR by reusing the mappings-agnostic
 * LegacyNettyHandshakeInterceptor, which operates purely on Netty pipelines
 * and reflection and does not depend on Yarn / Intermediary / Mojmap.
 */
public class FabricPlugin implements DedicatedServerModInitializer, PluginBase {

    private static FabricPlugin instance;

    private final Config config;
    private final Debugger debugger;
    private final HandshakeHandler handshakeHandler;
    private final Logger logger;

    public FabricPlugin() {
        instance = this;
        this.logger = Logger.getLogger("MCServerHost");
        this.config = new FabricConfig();
        this.debugger = Debugger.createDebugger(this);
        this.handshakeHandler = new HandshakeHandler(this);
    }

    @Override
    public void onInitializeServer() {
        try {
            GeyserHandler.init(this, config);
            initialization();
            LegacyNettyHandshakeInterceptor.install(this);
            logger.info("[MCServerHost] Fabric mod initialized.");
        } catch (Exception e) {
            logger.severe("[MCServerHost] Failed to initialize Fabric mod: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static FabricPlugin getInstance() { return instance; }

    @Override public Config getMcshConfig() { return config; }
    @Override public Logger getLogger() { return logger; }
    @Override public HandshakeHandler getHandshakeHandler() { return handshakeHandler; }
    @Override public Debugger getDebugger() { return debugger; }


    private static class FabricConfig extends Config {
        private FabricConfig() {
            this.dataFolder = new File("config/mcserverhost");
        }

        @Override
        public boolean doDebug() {
            return true;
        }
    }

}
