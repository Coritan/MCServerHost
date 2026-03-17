package com.mcserverhost;

import com.destroystokyo.paper.event.player.PlayerHandshakeEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.mcserverhost.PluginException.PacketManipulationException;
import com.mcserverhost.PluginException.PlayerManipulationException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Bukkit backend using Paper's native PlayerHandshakeEvent
 */
public class PaperProvider extends BukkitPlugin.BukkitBackend {

	public PaperProvider(BukkitPlugin plugin) {
		super(plugin);
	}

	@Override
	public void load() {
		getPlugin().getServer().getPluginManager().registerEvents(new Handler(this), getPlugin());
	}


	private static class Handler implements Listener {

		private final PaperProvider provider;

		private Handler(PaperProvider provider) {
			this.provider = provider;
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onHandshake(PlayerHandshakeEvent e) {
			Packet packet = new Packet(e);
			Player player = new Player(e);

			provider.getPlugin().getHandshakeHandler().handleHandshake(packet, player);
		}

		@EventHandler(priority = EventPriority.LOWEST)
		public void onServerPing(PaperServerListPingEvent e) {
			if (e.getClient().isLegacy())
				e.setCancelled(true);
		}

	}


	private static class Packet implements PacketContext {

		private final PlayerHandshakeEvent handshakeEvent;

		private Packet(PlayerHandshakeEvent handshakeEvent) {
			this.handshakeEvent = handshakeEvent;
		}

		@Override
		public String getPayloadString() {
			return handshakeEvent.getOriginalHandshake();
		}

		@Override
		public void setPacketHostname(String hostname) throws PacketManipulationException {
			try {
				handshakeEvent.setCancelled(false);
				handshakeEvent.setServerHostname(hostname);
			} catch (Exception e) {
				throw new PacketManipulationException(e);
			}
		}

	}


	private static class Player implements PlayerContext {

		private final PlayerHandshakeEvent handshakeEvent;

		private Player(PlayerHandshakeEvent handshakeEvent) {
			this.handshakeEvent = handshakeEvent;
		}

		@Override
		public String getUUID() {
			UUID uuid = handshakeEvent.getUniqueId();
			if (uuid == null)
				return "unknown";
			return uuid.toString();
		}

		@Override
		public String getName() { return "unknown"; }

		@Override
		public String getIP() { return handshakeEvent.getSocketAddressHostname(); }

		@Override
		public void setIP(InetSocketAddress ip) throws PlayerManipulationException {
			try {
				handshakeEvent.setSocketAddressHostname(ip.getAddress().getHostAddress());
			} catch (Exception e) {
				throw new PlayerManipulationException(e);
			}
		}

	}

}
