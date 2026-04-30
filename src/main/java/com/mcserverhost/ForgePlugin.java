package com.mcserverhost;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Forge mod entry point. Recognized by FMLLoader via META-INF/mods.toml.
 *
 * The same JAR is also a valid Bukkit plugin (plugin.yml), BungeeCord plugin (bungee.yml),
 * and Velocity plugin (velocity-plugin.json). Each platform's class loader finds only what
 * it needs and ignores the rest.
 */
@Mod("mcserverhost")
public class ForgePlugin implements PluginBase {

    private static ForgePlugin instance;

    private final Config config;
    private final Debugger debugger;
    private final HandshakeHandler handshakeHandler;
    private final Logger logger;
    private volatile boolean initialized = false;

    private HubConfig hubConfig;

    public ForgePlugin() {
        instance = this;

        this.logger = Logger.getLogger("MCServerHost");
        this.config = new ForgeConfig();
        this.debugger = Debugger.createDebugger(this);
        this.handshakeHandler = new HandshakeHandler(this);

        registerServerSetupListener();

        if (isLegacyForge()) {
            initialized = true;
            doInit();
        }
    }

    private static boolean isLegacyForge() {
        try {
            Class.forName("net.minecraftforge.fml.common.event.FMLServerStartingEvent");
            try {
                Class.forName("net.minecraftforge.fml.event.server.FMLServerStartingEvent");
                return false;
            } catch (ClassNotFoundException ignored) {
                return true;
            }
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void registerServerSetupListener() {
        boolean registered = false;

        try {
            Class<?> contextClass = Class.forName("net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext");
            Method getCtx = contextClass.getMethod("get");
            Object ctx = getCtx.invoke(null);
            Method getModEventBus = ctx.getClass().getMethod("getModEventBus");
            Object modEventBus = getModEventBus.invoke(ctx);
            Method addListener = modEventBus.getClass().getMethod("addListener", Consumer.class);
            addListener.invoke(modEventBus, (Consumer<FMLDedicatedServerSetupEvent>) this::onServerSetup);
            registered = true;
        } catch (Throwable ignored) {
        }

        if (!registered) {
            try {
                Class<?> contextClass = Class.forName("net.minecraftforge.fml.ModLoadingContext");
                Method getActive = contextClass.getMethod("getActiveContainer");
                Object container = getActive.invoke(null);
                if (container != null) {
                    Method getEventBus = container.getClass().getMethod("getEventBus");
                    Object eventBus = getEventBus.invoke(container);
                    Method addListener = eventBus.getClass().getMethod("addListener", Consumer.class);
                    addListener.invoke(eventBus, (Consumer<FMLDedicatedServerSetupEvent>) this::onServerSetup);
                    registered = true;
                }
            } catch (Throwable ignored) {
            }
        }

        if (!registered) {
            logger.info("[MCServerHost] Mod event bus unavailable, registering via game event bus (ServerStartingEvent).");
            registerOnGameEventBus();
        }
    }

    @SuppressWarnings("unchecked")
    private void registerOnGameEventBus() {
        Object eventBus = null;

        try {
            Field eventBusField = MinecraftForge.class.getField("EVENT_BUS");
            eventBus = eventBusField.get(null);
        } catch (Throwable ignored) {
        }

        if (eventBus == null) {
            try {
                Class<?> neoForgeClass = Class.forName("net.neoforged.neoforge.common.NeoForge");
                Field eventBusField = neoForgeClass.getField("EVENT_BUS");
                eventBus = eventBusField.get(null);
            } catch (Throwable ignored) {
            }
        }

        if (eventBus == null) {
            logger.severe("[MCServerHost] Failed to register on any event bus. Server start event will not fire.");
            return;
        }

        try {
            Method register = eventBus.getClass().getMethod("register", Object.class);
            register.invoke(eventBus, this);
        } catch (Throwable ignored) {
        }

        registerDynamicListener(eventBus, "net.minecraftforge.event.server.ServerStartingEvent", event -> {
            if (initialized) return;
            initialized = true;
            doInit();
        });

        registerDynamicListener(eventBus, "net.minecraftforge.fml.common.event.FMLServerStartingEvent", event -> {
            if (initialized) return;
            initialized = true;
            doInit();
            tryRegisterCommandsFromEvent(event);
        });

        registerDynamicListener(eventBus, "net.minecraftforge.event.server.ServerStoppingEvent", event -> onServerStopping());

        registerDynamicListener(eventBus, "net.minecraftforge.fml.event.server.FMLServerStoppingEvent", event -> onServerStopping());

        registerDynamicListener(eventBus, "net.minecraftforge.fml.common.event.FMLServerStoppingEvent", event -> onServerStopping());

        registerCommandListener(eventBus);
    }

    @SuppressWarnings("unchecked")
    private void registerDynamicListener(Object eventBus, String eventClassName, Consumer<Object> handler) {
        try {
            Class<?> eventClass = Class.forName(eventClassName);
            Method addListener = findAddListenerMethod(eventBus, eventClass);
            if (addListener == null) return;

            Class<?> priorityClass = null;
            try { priorityClass = Class.forName("net.minecraftforge.eventbus.api.EventPriority"); } catch (Throwable ignored) {}

            if (addListener.getParameterCount() == 3) {
                addListener.invoke(eventBus, false, eventClass, handler);
            } else if (priorityClass != null) {
                Object normalPriority = priorityClass.getMethod("valueOf", String.class).invoke(null, "NORMAL");
                addListener.invoke(eventBus, normalPriority, false, eventClass, handler);
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Could not register listener for " + eventClassName + ": " + e.getMessage());
        }
    }

    private Method findAddListenerMethod(Object eventBus, Class<?> eventClass) {
        for (Method m : eventBus.getClass().getMethods()) {
            if (m.getName().equals("addListener") && m.getParameterCount() == 3) {
                Class<?>[] params = m.getParameterTypes();
                if (params[1] == Class.class && Consumer.class.isAssignableFrom(params[2])) {
                    return m;
                }
            }
        }
        for (Method m : eventBus.getClass().getMethods()) {
            if (m.getName().equals("addListener") && m.getParameterCount() == 4) {
                Class<?>[] params = m.getParameterTypes();
                if (params[2] == Class.class && Consumer.class.isAssignableFrom(params[3])) {
                    return m;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void registerCommandListener(Object eventBus) {
        try {
            Class<?> eventClass = Class.forName("net.minecraftforge.event.RegisterCommandsEvent");
            Method addListener = findAddListenerMethod(eventBus, eventClass);
            if (addListener == null) return;

            Class<?> priorityClass = null;
            try { priorityClass = Class.forName("net.minecraftforge.eventbus.api.EventPriority"); } catch (Throwable ignored) {}

            Consumer<Object> listener = event -> registerForgeCommands(event);

            if (addListener.getParameterCount() == 3) {
                addListener.invoke(eventBus, false, eventClass, listener);
            } else if (priorityClass != null) {
                Object normalPriority = priorityClass.getMethod("valueOf", String.class).invoke(null, "NORMAL");
                addListener.invoke(eventBus, normalPriority, false, eventClass, listener);
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Could not register command listener: " + e.getMessage());
        }
    }

    private void registerForgeCommands(Object registerCommandsEvent) {
        if (hubConfig == null) return;
        try {
            Method getDispatcher = registerCommandsEvent.getClass().getMethod("getDispatcher");
            Object dispatcher = getDispatcher.invoke(registerCommandsEvent);
            if (hubConfig.isHubCommandEnabled()) {
                ForgeHubCommand.register(dispatcher, hubConfig, logger);
            }
            FabricStopCommand.register(dispatcher, hubConfig, ForgePlugin::findServerInstance, logger);
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Could not register commands: " + e.getMessage());
        }
    }

    private static Object findServerInstance() {
        try {
            Class<?> c = Class.forName("net.minecraft.server.MinecraftServer");
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() == 0 && c.isAssignableFrom(m.getReturnType())
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    try { return m.invoke(null); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void doInit() {
        try {
            hubConfig = new HubConfig(config.getDataFolder(), logger);
            GeyserHandler.init(this, config);
            initialization();
            if (isLegacyForge()) {
                LegacyNettyHandshakeInterceptor.install(this);
            }
        } catch (Exception e) {
            logger.severe("[MCServerHost] Failed to initialize Forge mod: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void onServerStopping() {
        onServerStopping(null);
    }

    private void onServerStopping(Object serverInstance) {
        if (hubConfig == null || !hubConfig.isSendToHubOnShutdown()) return;
        ForgeShutdownTransferHandler.transferAll(hubConfig, serverInstance, logger);
    }

    private void onServerSetup(FMLDedicatedServerSetupEvent event) {
        if (initialized) return;
        initialized = true;
        doInit();
    }

    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        if (initialized) return;
        initialized = true;
        doInit();
        registerForgeCommandsFromStartingEvent(event);
    }

    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        Object server = null;
        try {
            server = event.getServer();
        } catch (Throwable ignored) {
        }
        onServerStopping(server);
    }

    private void registerForgeCommandsFromStartingEvent(FMLServerStartingEvent event) {
        if (hubConfig == null) return;
        try {
            Method getCommandDispatcher = event.getClass().getMethod("getCommandDispatcher");
            Object dispatcher = getCommandDispatcher.invoke(event);
            if (hubConfig.isHubCommandEnabled()) {
                ForgeHubCommand.register(dispatcher, hubConfig, logger);
            }
            FabricStopCommand.register(dispatcher, hubConfig, ForgePlugin::findServerInstance, logger);
        } catch (Throwable e) {
            logger.warning("[MCServerHost] Could not register commands from ServerStartingEvent: " + e.getMessage());
        }
    }

    private void tryRegisterCommandsFromEvent(Object event) {
        if (hubConfig == null) return;
        try {
            Method getCommandDispatcher = event.getClass().getMethod("getCommandDispatcher");
            Object dispatcher = getCommandDispatcher.invoke(event);
            if (hubConfig.isHubCommandEnabled()) {
                ForgeHubCommand.register(dispatcher, hubConfig, logger);
            }
            FabricStopCommand.register(dispatcher, hubConfig, ForgePlugin::findServerInstance, logger);
        } catch (Throwable ignored) {
        }
    }

    public static ForgePlugin getInstance() {
        return instance;
    }

    @Override
    public Config getMcshConfig() { return config; }

    @Override
    public Logger getLogger() { return logger; }

    @Override
    public HandshakeHandler getHandshakeHandler() { return handshakeHandler; }

    @Override
    public Debugger getDebugger() { return debugger; }


    private static class ForgeConfig extends Config {
        private ForgeConfig() {
            this.dataFolder = new File("config/mcserverhost");
        }

        @Override
        public boolean doDebug() {
            return true;
        }
    }

}
