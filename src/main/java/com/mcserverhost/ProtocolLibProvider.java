package com.mcserverhost;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.netty.Injector;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import com.mcserverhost.PluginException.InitializationException;
import com.mcserverhost.PluginException.PacketManipulationException;
import com.mcserverhost.PluginException.PlayerManipulationException;
import com.mcserverhost.PluginException.ReflectionException;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Bukkit backend using ProtocolLib packet interception
 */
public class ProtocolLibProvider extends BukkitPlugin.BukkitBackend {

	public ProtocolLibProvider(BukkitPlugin plugin) {
		super(plugin);
	}

	@Override
	public void load() {
		com.comphenix.protocol.ProtocolLibrary.getProtocolManager().addPacketListener(new Handler(this));
	}


	private static class Handler extends PacketAdapter {

		private final ProtocolLibProvider provider;

		private Handler(ProtocolLibProvider provider) {
			super(provider.getPlugin(), ListenerPriority.LOWEST, PacketType.Handshake.Client.SET_PROTOCOL);
			this.provider = provider;
		}

		@Override
		public void onPacketReceiving(PacketEvent e) {
			Packet packet = new Packet(e.getPacket());
			PlayerWrapper player = new PlayerWrapper(e.getPlayer());

			provider.getPlugin().getHandshakeHandler().handleHandshake(packet, player);
		}

	}


	private static class Packet implements PacketContext {

		private final PacketContainer packetContainer;
		private final String rawPayload;

		private Packet(PacketContainer packetContainer) {
			this.packetContainer = packetContainer;
			this.rawPayload = packetContainer.getStrings().read(0);
		}

		@Override
		public String getPayloadString() { return rawPayload; }

		@Override
		public void setPacketHostname(String hostname) throws PacketManipulationException {
			try {
				packetContainer.getStrings().write(0, hostname);
			} catch (Exception e) {
				throw new PacketManipulationException(e);
			}
		}

	}


	private static class PlayerWrapper implements PlayerContext {

		private static Class<?> abstractChannelClass;

		static {
			try {
				abstractChannelClass = Class.forName("io.netty.channel.AbstractChannel");
			} catch (ClassNotFoundException e) {
				try {
					abstractChannelClass = Class.forName("net.minecraft.util.io.netty.channel.AbstractChannel");
				} catch (ClassNotFoundException e2) {
					throw new InitializationException(new ReflectionException(e2));
				}
			}
		}

		private final Player player;
		private String ip;

		private PlayerWrapper(Player player) {
			this.player = player;
			this.ip = player.getAddress().getAddress().getHostAddress();
		}

		@Override public String getUUID() { return "unknown"; }
		@Override public String getName() { return "unknown"; }
		@Override public String getIP() { return ip; }

		@Override
		public void setIP(InetSocketAddress ip) throws PlayerManipulationException {
			try {
				this.ip = ip.getAddress().getHostAddress();

				Injector injector = TemporaryPlayerFactory.getInjectorFromPlayer(player);
				Object networkManager = Reflect.getObjectInPrivateField(injector, "networkManager");

				Reflect.setFinalField(networkManager, Reflect.searchFieldByClass(networkManager.getClass(), SocketAddress.class), ip);

				Object channel = Reflect.getObjectInPrivateField(injector, "channel");
				Reflect.setFinalField(channel, Reflect.getDeclaredField(abstractChannelClass, "remoteAddress"), ip);
			} catch (Exception e) {
				throw new PlayerManipulationException(e);
			}
		}

	}

}
