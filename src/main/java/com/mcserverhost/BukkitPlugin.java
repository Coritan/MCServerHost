package com.mcserverhost;

import com.mcserverhost.PluginException.InitializationException;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.logging.Logger;

/**
 * Plugin entry point for Bukkit/Spigot/Paper servers
 */
public class BukkitPlugin extends JavaPlugin implements PluginBase {

	private Config config;
	private HandshakeHandler handshakeHandler;
	private Debugger debugger;
	private BukkitBackend backend;
	private ShutdownTransferHandler shutdownTransferHandler;

	@Override
	public void onEnable() {
		try {
			config = new BukkitConfig(this);
			debugger = Debugger.createDebugger(this);
			handshakeHandler = new HandshakeHandler(this);

			if (config.preferProtocolLib() && getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
				try {
					String[] plibVersion = getServer().getPluginManager().getPlugin("ProtocolLib").getDescription().getVersion().split("-")[0].split("\\.");
					int major = Integer.parseInt(plibVersion[0]);
					int minor = Integer.parseInt(plibVersion[1]);
					int patch = Integer.parseInt(plibVersion[2]);

					String paperVersion = Bukkit.getServer().getMinecraftVersion();
					if (major <= 5 && minor <= 2 && patch <= 1 && (paperVersion.equals("1.20.5") || paperVersion.equals("1.20.6"))) {
						getLogger().severe("MCSH is incompatible with ProtocolLib <= 5.2.1 on Paper 1.20.5/1.20.6 due to lack of support from ProtocolLib. Reverting to default Paper handler to prevent issues. This error can be avoided by disabling 'prefer-protocollib' in the config.");
						backend = new PaperProvider(this);
					} else {
						backend = new ProtocolLibProvider(this);
					}
				} catch (Exception t) {
					getLogger().warning("Failed to check Paper or ProtocolLib version. This is not a critical error unless you are running Paper 1.20.5/1.20.6 with ProtocolLib version 5.2.1 or below.");
					getDebugger().exception(t);
					backend = new ProtocolLibProvider(this);
				}
			} else if (hasPaperHandshakeEvent()) {
				backend = new PaperProvider(this);
			} else if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
				backend = new ProtocolLibProvider(this);
			} else {
				getLogger().severe("MCSH not loading because ProtocolLib is not installed. Either use Paper to enable native compatibility or install ProtocolLib.");
				return;
			}

			backend.load();

			GeyserHandler.init(this, config);

			getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
			HubConfig hubConfig = new HubConfig(getDataFolder(), getLogger());
			if (hubConfig.isHubCommandEnabled()) {
				TransferCommand transferCommand = new TransferCommand(this, hubConfig);
				getCommand("mcsh").setExecutor(transferCommand);
				registerDynamicAliases(transferCommand, hubConfig);
			}
			if (hubConfig.isSendToHubOnShutdown()) {
				shutdownTransferHandler = new ShutdownTransferHandler(this, hubConfig);
				new StopCommandInterceptor(this, shutdownTransferHandler).register();
			}

			initialization();
		} catch (Exception e) {
			throw new InitializationException(e);
		}
	}


	@Override
	public void onDisable() {
		if (shutdownTransferHandler != null) {
			shutdownTransferHandler.transferAll();
		}
	}

	private void registerDynamicAliases(TransferCommand executor, HubConfig hubConfig) {
		try {
			Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
			commandMapField.setAccessible(true);
			SimpleCommandMap commandMap = (SimpleCommandMap) commandMapField.get(Bukkit.getServer());
			for (String alias : hubConfig.getAliases()) {
				PluginCommand cmd = getCommand(alias);
				if (cmd != null) {
					cmd.setExecutor(executor);
				} else {
					org.bukkit.command.defaults.BukkitCommand dynamic = new org.bukkit.command.defaults.BukkitCommand(alias) {
						@Override
						public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
							return executor.onCommand(sender, this, commandLabel, args);
						}
					};
					commandMap.register(getName(), dynamic);
				}
			}
		} catch (Exception e) {
			getLogger().warning("Failed to register hub command aliases: " + e.getMessage());
		}
	}

	public static boolean hasPaperHandshakeEvent() {
		try {
			Class.forName("com.destroystokyo.paper.event.player.PlayerHandshakeEvent");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}


	@Override public HandshakeHandler getHandshakeHandler() { return handshakeHandler; }
	@Override public Debugger getDebugger() { return debugger; }
	@Override public Config getMcshConfig() { return config; }
	@Override public Logger getLogger() { return super.getLogger(); }


	/**
	 * Abstract base for Bukkit backend implementations (Paper or ProtocolLib)
	 */
	public static abstract class BukkitBackend {

		private final BukkitPlugin plugin;

		public BukkitBackend(BukkitPlugin plugin) {
			this.plugin = plugin;
		}

		public BukkitPlugin getPlugin() { return plugin; }

		public abstract void load();

	}


	private static class BukkitConfig extends Config {
		private BukkitConfig(JavaPlugin plugin) {
			this.dataFolder = plugin.getDataFolder();
		}
	}

}
