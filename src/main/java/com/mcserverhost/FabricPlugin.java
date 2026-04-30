package com.mcserverhost;

import net.fabricmc.api.DedicatedServerModInitializer;

import java.io.File;
import java.util.logging.Logger;

public class FabricPlugin implements PluginBase, DedicatedServerModInitializer {

    private static FabricPlugin instance;

    private final Logger logger;
    private final Config config;
    private final Debugger debugger;
    private final HandshakeHandler handshakeHandler;
    private volatile HubConfig hubConfig;
    private volatile Object minecraftServer;

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
            hubConfig = new HubConfig(config.getDataFolder(), logger);
            GeyserHandler.init(this, config);
            initialization();
            logger.info("[MCServerHost] Fabric mod initialized.");
        } catch (Throwable e) {
            logger.severe("[MCServerHost] Failed to initialize Fabric mod: " + e.getMessage());
        }
    }

    public void onServerStarted(Object server) {
        this.minecraftServer = server;
    }

    public void onServerStopping(Object server) {
        if (hubConfig == null || !hubConfig.isSendToHubOnShutdown()) return;
        FabricShutdownTransferHandler.transferAll(hubConfig, server, logger);
    }

    public void onRegisterCommands(Object dispatcher) {
        if (hubConfig == null) return;
        if (hubConfig.isHubCommandEnabled()) {
            ForgeHubCommand.register(dispatcher, hubConfig, logger);
        }
        FabricTransferCommand.register(dispatcher, hubConfig, logger);
        FabricStopCommand.register(dispatcher, hubConfig, this::getMinecraftServer, logger);
    }

    public HubConfig getHubConfig() { return hubConfig; }

    public Object getMinecraftServer() { return minecraftServer; }

    public static FabricPlugin getInstance() { return instance; }

    @Override
    public Config getMcshConfig() { return config; }

    @Override
    public Logger getLogger() { return logger; }

    @Override
    public HandshakeHandler getHandshakeHandler() { return handshakeHandler; }

    @Override
    public Debugger getDebugger() { return debugger; }

    private static class FabricConfig extends Config {
        private FabricConfig() {
            this.dataFolder = new File("config/mcserverhost");
        }

        @Override
        public boolean doDebug() { return true; }
    }
}
