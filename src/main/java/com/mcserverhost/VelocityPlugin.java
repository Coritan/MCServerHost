package com.mcserverhost;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.ProxyServer;
import com.mcserverhost.PluginException.InitializationException;
import com.mcserverhost.PluginException.PacketManipulationException;
import com.mcserverhost.PluginException.PlayerManipulationException;
import com.mcserverhost.PluginException.ReflectionException;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Plugin entry point for Velocity proxy servers
 */
@Plugin(
	id = "mcserverhost",
	name = "MCServerHost",
	description = "MCServerHost IP parsing capabilities for Velocity"
)
public class VelocityPlugin implements PluginBase {

	private final ProxyServer server;
	private final Logger logger;
	private final Path dataFolder;

	private Config config;
	private HandshakeHandler handshakeHandler;
	private Debugger debugger;
	private HubConfig hubConfig;

	@Inject
	public VelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataFolder) {
		this.server = server;
		this.logger = logger;
		this.dataFolder = dataFolder;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent e) {
		try {
			config = new VelocityConfig(dataFolder.toFile());
			debugger = Debugger.createDebugger(this);
			handshakeHandler = new HandshakeHandler(this);

			server.getEventManager().register(this, new HandshakeListener(this));

			hubConfig = new HubConfig(dataFolder.toFile(), logger);
			if (hubConfig.isHubCommandEnabled()) {
				VelocityTransferCommand transferCommand = new VelocityTransferCommand(server, hubConfig);
				CommandMeta meta = server.getCommandManager().metaBuilder("mcsh")
					.aliases(hubConfig.getAliases().toArray(new String[0]))
					.build();
				server.getCommandManager().register(meta, transferCommand);
			}

			VelocityShutdownCommand shutdownCommand = new VelocityShutdownCommand(server, hubConfig);
			CommandMeta shutdownMeta = server.getCommandManager().metaBuilder("shutdown").build();
			server.getCommandManager().register(shutdownMeta, shutdownCommand);

			GeyserHandler.init(this, config);

			initialization();
		} catch (Exception exception) {
			throw new InitializationException(exception);
		}
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent e) {
		if (hubConfig == null || !hubConfig.isSendToHubOnShutdown()) {
			return;
		}
		ServerInfo info = new ServerInfo("mcsh-shutdown", new InetSocketAddress(hubConfig.getHost(), hubConfig.getPort()));
		RegisteredServer target = server.registerServer(info);
		for (Player player : server.getAllPlayers()) {
			try {
				player.createConnectionRequest(target).fireAndForget();
			} catch (Exception ex) {
				logger.warning("Failed to transfer " + player.getUsername() + " on shutdown: " + ex.getMessage());
			}
		}
	}

	@Override public Config getMcshConfig() { return config; }
	@Override public Logger getLogger() { return logger; }
	@Override public HandshakeHandler getHandshakeHandler() { return handshakeHandler; }
	@Override public Debugger getDebugger() { return debugger; }


	private static class VelocityConfig extends Config {
		private VelocityConfig(File dataFolder) {
			this.dataFolder = dataFolder;
		}
	}


	private static class HandshakeListener {

		private final PluginBase plugin;

		private static Class<?> CONNECTED_PLAYER_CLASS;

		static {
			try {
				CONNECTED_PLAYER_CLASS = Class.forName("com.velocitypowered.proxy.connection.client.ConnectedPlayer");
			} catch (Exception e) {
				// ignore for old velocity versions
			}
		}

		private HandshakeListener(PluginBase plugin) {
			this.plugin = plugin;
		}

		@Subscribe(order = PostOrder.FIRST)
		public void onPreLogin(PreLoginEvent e) {
			if (!plugin.getMcshConfig().handlePreLoginEvent()) {
				return;
			}
			handleEvent(e.getConnection(), "onPreLogin");
		}

		@Subscribe(order = PostOrder.FIRST)
		public void onHandshake(ConnectionHandshakeEvent e) {
			handleEvent(e.getConnection(), "onHandshake");
		}

		@Subscribe(order = PostOrder.FIRST)
		public void onProxyPing(ProxyPingEvent e) {
			InboundConnection connection = e.getConnection();
			if (connection.getClass() == CONNECTED_PLAYER_CLASS) {
				return;
			}
			handleEvent(connection, "onProxyPing");
		}

		private void handleEvent(InboundConnection connection, String debugSource) {
			PlayerWrapper player = new PlayerWrapper(connection);
			if (player.getConnectionType() == PlayerWrapper.ConnectionType.LEGACY) {
				return;
			}

			Packet packet = new Packet(connection);

			plugin.getDebugger().warn("Velocity: " + debugSource + " Raw player hostname: " + packet.getPayloadString());

			plugin.getHandshakeHandler().handleHandshake(packet, player);
		}

	}


	private static class Packet implements PacketContext {

		private static final Field HANDSHAKE_FIELD;
		private static final Field HOSTNAME_FIELD;
		private static final Field CLEANED_ADDRESS_FIELD;

		private static Class<?> LOGIN_INBOUND_CLASS;
		private static Field LOGIN_INBOUND_DELEGATE_FIELD;

		static {
			try {
				Class<?> inboundConnection = Class.forName("com.velocitypowered.proxy.connection.client.InitialInboundConnection");
				HANDSHAKE_FIELD = Reflect.getPrivateField(inboundConnection, "handshake");

				Field hostnameField;
				try {
					hostnameField = Reflect.getPrivateField(Class.forName("com.velocitypowered.proxy.protocol.packet.HandshakePacket"), "serverAddress");
				} catch (Exception e) {
					hostnameField = Reflect.getPrivateField(Class.forName("com.velocitypowered.proxy.protocol.packet.Handshake"), "serverAddress");
				}
				HOSTNAME_FIELD = hostnameField;

				CLEANED_ADDRESS_FIELD = Reflect.getPrivateField(inboundConnection, "cleanedAddress");
			} catch (Exception e) {
				throw new InitializationException(new ReflectionException(e));
			}

			try {
				LOGIN_INBOUND_CLASS = Class.forName("com.velocitypowered.proxy.connection.client.LoginInboundConnection");
				LOGIN_INBOUND_DELEGATE_FIELD = Reflect.getPrivateField(LOGIN_INBOUND_CLASS, "delegate");
			} catch (Exception e) {
				// ignore for old versions of velocity
			}
		}

		private final InboundConnection inboundConnection;
		private final String rawPayload;

		private Packet(InboundConnection inboundConnection) {
			if (inboundConnection.getClass() == LOGIN_INBOUND_CLASS) {
				try {
					inboundConnection = (InboundConnection) LOGIN_INBOUND_DELEGATE_FIELD.get(inboundConnection);
				} catch (IllegalAccessException e) {
					throw new PacketManipulationException(e);
				}
			}

			this.inboundConnection = inboundConnection;
			try {
				this.rawPayload = (String) HOSTNAME_FIELD.get(HANDSHAKE_FIELD.get(inboundConnection));
			} catch (IllegalAccessException e) {
				throw new PacketManipulationException(e);
			}
		}

		@Override
		public String getPayloadString() { return rawPayload; }

		@Override
		public void setPacketHostname(String hostname) throws PacketManipulationException {
			try {
				Reflect.setFinalField(inboundConnection, CLEANED_ADDRESS_FIELD, cleanAddress(hostname));
				Object handshake = HANDSHAKE_FIELD.get(inboundConnection);
				HOSTNAME_FIELD.set(handshake, hostname);
			} catch (Exception e) {
				throw new PacketManipulationException(e);
			}
		}

		// Adapted from https://github.com/VelocityPowered/Velocity/blob/17e6944daea8130e03903ccdfbf63f111c573849/proxy/src/main/java/com/velocitypowered/proxy/connection/client/HandshakeSessionHandler.java
		private String cleanAddress(String hostname) {
			String cleaned = hostname;
			int zeroIdx = cleaned.indexOf('\0');
			if (zeroIdx > -1) {
				cleaned = hostname.substring(0, zeroIdx);
			}
			if (!cleaned.isEmpty() && cleaned.charAt(cleaned.length() - 1) == '.') {
				cleaned = cleaned.substring(0, cleaned.length() - 1);
			}
			return cleaned;
		}

	}


	private static class PlayerWrapper implements PlayerContext {

		private static final Class<?> INITIAL_INBOUND_CLASS;
		private static final Field MINECRAFT_CONNECTION_FIELD;
		private static final Field REMOTE_ADDRESS_FIELD;

		private static Class<?> LOGIN_INBOUND_CLASS;
		private static Field LOGIN_INBOUND_DELEGATE_FIELD;

		static {
			try {
				INITIAL_INBOUND_CLASS = Class.forName("com.velocitypowered.proxy.connection.client.InitialInboundConnection");
				MINECRAFT_CONNECTION_FIELD = Reflect.getPrivateField(INITIAL_INBOUND_CLASS, "connection");

				Class<?> minecraftConnection = Class.forName("com.velocitypowered.proxy.connection.MinecraftConnection");
				REMOTE_ADDRESS_FIELD = Reflect.getPrivateField(minecraftConnection, "remoteAddress");
			} catch (Exception e) {
				throw new InitializationException(new ReflectionException(e));
			}

			try {
				LOGIN_INBOUND_CLASS = Class.forName("com.velocitypowered.proxy.connection.client.LoginInboundConnection");
				LOGIN_INBOUND_DELEGATE_FIELD = Reflect.getPrivateField(LOGIN_INBOUND_CLASS, "delegate");
			} catch (Exception e) {
				// ignore for old versions of velocity
			}
		}

		private final InboundConnection inboundConnection;
		private final ConnectionType connectionType;
		private String ip;

		private PlayerWrapper(InboundConnection inboundConnection) {
			this.inboundConnection = inboundConnection;
			this.ip = inboundConnection.getRemoteAddress().getAddress().getHostAddress();

			if (this.inboundConnection.getClass() == INITIAL_INBOUND_CLASS) {
				this.connectionType = ConnectionType.INITIAL_INBOUND;
			} else if (this.inboundConnection.getClass() == LOGIN_INBOUND_CLASS) {
				this.connectionType = ConnectionType.LOGIN_INBOUND;
			} else {
				this.connectionType = ConnectionType.LEGACY;
			}
		}

		@Override public String getUUID() { return "unknown"; }
		@Override public String getName() { return "unknown"; }
		@Override public String getIP() { return ip; }

		@Override
		public void setIP(InetSocketAddress ip) throws PlayerManipulationException {
			try {
				this.ip = ip.getAddress().getHostAddress();
				REMOTE_ADDRESS_FIELD.set(getMinecraftConnection(), ip);
			} catch (Exception e) {
				throw new PlayerManipulationException(e);
			}
		}

		private Object getMinecraftConnection() {
			try {
				switch (this.connectionType) {
					case INITIAL_INBOUND:
						return MINECRAFT_CONNECTION_FIELD.get(inboundConnection);
					case LOGIN_INBOUND: {
						Object initial = LOGIN_INBOUND_DELEGATE_FIELD.get(this.inboundConnection);
						return MINECRAFT_CONNECTION_FIELD.get(initial);
					}
				}
			} catch (IllegalAccessException e) {
				throw new PlayerManipulationException(e);
			}
			return null;
		}

		public ConnectionType getConnectionType() { return connectionType; }

		enum ConnectionType {
			LOGIN_INBOUND, INITIAL_INBOUND, LEGACY
		}

	}

}
