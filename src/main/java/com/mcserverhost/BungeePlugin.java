package com.mcserverhost;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerHandshakeEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import net.md_5.bungee.protocol.packet.Handshake;
import com.mcserverhost.PluginException.InitializationException;
import com.mcserverhost.PluginException.PacketManipulationException;
import com.mcserverhost.PluginException.PlayerManipulationException;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

/**
 * Plugin entry point for BungeeCord proxy servers
 */
public class BungeePlugin extends Plugin implements PluginBase, Listener {

	private Config config;
	private HandshakeHandler handshakeHandler;
	private Debugger debugger;
	private HubConfig hubConfig;

	@Override
	public void onEnable() {
		try {
			config = new BungeeConfig(this);
			debugger = Debugger.createDebugger(this);
			handshakeHandler = new HandshakeHandler(this);

			PluginManager pluginManager = this.getProxy().getPluginManager();
			pluginManager.registerListener(this, new HandshakeListener(this));
			hubConfig = new HubConfig(getDataFolder(), getLogger());
			if (hubConfig.isHubCommandEnabled()) {
				pluginManager.registerCommand(this, new BungeeTransferCommand(hubConfig));
			}
			pluginManager.registerCommand(this, new BungeeEndCommand(hubConfig));

			GeyserHandler.init(this, config);

			initialization();
		} catch (Exception e) {
			throw new InitializationException(e);
		}
	}

	@Override
	public void onDisable() {
		if (hubConfig != null && hubConfig.isSendToHubOnShutdown()) {
			String host = hubConfig.getHost();
			int port = hubConfig.getPort();
			for (ProxiedPlayer player : getProxy().getPlayers()) {
				try {
					ServerInfo target = ProxyServer.getInstance().constructServerInfo(
						"mcsh-shutdown",
						new java.net.InetSocketAddress(host, port),
						"",
						false
					);
					player.connect(target);
				} catch (Exception e) {
					getLogger().warning("Failed to transfer " + player.getName() + " on shutdown: " + e.getMessage());
				}
			}
		}
	}

	@Override public Config getMcshConfig() { return config; }
	@Override public HandshakeHandler getHandshakeHandler() { return handshakeHandler; }
	@Override public Debugger getDebugger() { return debugger; }


	private static class BungeeConfig extends Config {
		private BungeeConfig(Plugin plugin) {
			this.dataFolder = plugin.getDataFolder();
		}
	}


	private static class HandshakeListener implements Listener {

		private final PluginBase plugin;

		private HandshakeListener(PluginBase plugin) {
			this.plugin = plugin;
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onPlayerHandshake(PlayerHandshakeEvent e) {
			Packet packet = new Packet(e.getHandshake(), e.getConnection());
			PlayerWrapper player = new PlayerWrapper(e.getConnection());

			plugin.getDebugger().warn("BungeeCord: Raw player hostname: " + packet.getPayloadString());

			plugin.getHandshakeHandler().handleHandshake(packet, player);
		}

	}


	private static class Packet implements PacketContext {

		private final Handshake handshake;
		private final PendingConnection pendingConnection;

		private Packet(Handshake handshake, PendingConnection pendingConnection) {
			this.handshake = handshake;
			this.pendingConnection = pendingConnection;
		}

		@Override
		public String getPayloadString() { return handshake.getHost(); }

		@Override
		public void setPacketHostname(String hostname) throws PacketManipulationException {
			try {
				InetSocketAddress virtualHost = InetSocketAddress.createUnresolved(hostname, handshake.getPort());
				try {
					Reflect.setFinalField(pendingConnection, "virtualHost", virtualHost);
				} catch (Exception ex) {
					Reflect.setFinalField(pendingConnection, "vHost", virtualHost);
				}
				Reflect.setField(handshake, "host", hostname);
			} catch (Exception e) {
				throw new PacketManipulationException(e);
			}
		}

	}


	private static class PlayerWrapper implements PlayerContext {

		private final PendingConnection pendingConnection;
		private String ip;

		private PlayerWrapper(PendingConnection pendingConnection) {
			this.pendingConnection = pendingConnection;
			this.ip = pendingConnection.getAddress().getAddress().getHostAddress();
		}

		@Override
		public String getUUID() {
			UUID uuid = pendingConnection.getUniqueId();
			if (uuid == null)
				return "unknown";
			return uuid.toString();
		}

		@Override
		public String getName() { return pendingConnection.getName(); }

		@Override
		public String getIP() { return ip; }

		@Override
		public void setIP(InetSocketAddress ip) throws PlayerManipulationException {
			try {
				this.ip = ip.getAddress().getHostAddress();

				Object channelWrapper = Reflect.getObjectInPrivateField(pendingConnection, "ch");
				Object channel = Reflect.getObjectInPrivateField(channelWrapper, "ch");

				try {
					Field socketAddressField = Reflect.searchFieldByClass(channelWrapper.getClass(), SocketAddress.class);
					Reflect.setFinalField(channelWrapper, socketAddressField, ip);
				} catch (IllegalArgumentException ignored) {
					// Some BungeeCord versions, notably those on 1.7 (e.g. zBungeeCord) don't have an SocketAddress field in the ChannelWrapper class
				}

				Reflect.setFinalField(channel, Reflect.getPrivateField(channel.getClass(), "remoteAddress"), ip);
				try {
					Reflect.setFinalField(channel, Reflect.getPrivateField(channel.getClass(), "localAddress"), ip);
				} catch (Throwable t) {
					// ChannelWrapper doesn't have a localAddress
				}
			} catch (Exception e) {
				throw new PlayerManipulationException(e);
			}
		}

	}

}
