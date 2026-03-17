package com.mcserverhost;

import java.net.InetSocketAddress;


/**
 * Parses and validates MCSH handshake payloads, then rewrites the player IP and hostname
 */
public class HandshakeHandler {

	private static final String PAYLOAD_DELIMITER = "///";

	private final PluginBase plugin;

	private TimestampValidator timestampValidator;

	public HandshakeHandler(PluginBase plugin) {
		this.plugin = plugin;
		timestampValidator = TimestampValidator.createDefault(plugin);
	}

	public void handleHandshake(PacketContext packet, PlayerContext player) {
		String[] payload = packet.getPayloadString().split(PAYLOAD_DELIMITER);

		if (payload.length != 3 && payload.length != 4) {
			plugin.getDebugger().info("No proxy payload detected for %s[%s/%s], skipping IP rewrite.", player.getName(), player.getUUID(), player.getIP());
			return;
		}

		try {
			String extraData = null;
			int nullIndex;
			int lastIndex = payload.length - 1;
			if ((nullIndex = payload[lastIndex].indexOf('\0')) != -1) {
				String originalData = payload[lastIndex];
				payload[lastIndex] = originalData.substring(0, nullIndex);
				extraData = originalData.substring(nullIndex);
			}

			String hostname = payload[0];
			String ipData = payload[1];
			int timestamp = Integer.parseInt(payload[2]);
			String signature = payload.length == 4 ? payload[3] : null;

			String[] ipParts;
			String host;
			int port;

			if (timestamp == 0 && GeyserHandler.GEYSER_SUPPORT_ENABLED && payload.length == 4) {
				ipData = payload[0];
				signature = payload[1];
				hostname = payload[3];

				ipParts = ipData.split(":");
				host = ipParts[0];
				port = Integer.parseInt(ipParts[1]);

				if (!signature.equals(GeyserHandler.SESSION_SECRET)) {
					plugin.getDebugger().warn("%s[%s/%s] provided an invalid session secret when authenticating a geyser connection.", player.getName(), player.getUUID(), player.getIP());
					return;
				}
			} else {
				ipParts = ipData.split(":");
				host = ipParts[0];
				port = Integer.parseInt(ipParts[1]);

				if (!timestampValidator.validate(timestamp)) {
					plugin.getDebugger().warn(
							"%s[%s/%s] provided valid handshake information, but timestamp was not valid. " +
									"Provided timestamp: %d vs. system timestamp: %d. Please check your machine time. Timestamp validation mode: %s",
							player.getName(), player.getUUID(), player.getIP(), (long) timestamp, timestampValidator.getUnixTime(), plugin.getMcshConfig().getTimestampValidationMode());
					return;
				}
			}

			InetSocketAddress newIP = new InetSocketAddress(host, port);
			player.setIP(newIP);

			if (extraData != null) hostname = hostname + extraData;

			packet.setPacketHostname(hostname);
		} catch (Exception e) {
			plugin.getDebugger().warn("Failed to parse proxy payload for %s[%s/%s]: %s", player.getName(), player.getUUID(), player.getIP(), e.getMessage());
		}
	}

}
