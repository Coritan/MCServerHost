package com.mcserverhost;

import org.geysermc.floodgate.api.InstanceHolder;

import java.util.Arrays;
import java.util.UUID;

/**
 * Handles Geyser/Floodgate Bedrock player connections by rewriting their handshake payload
 */
public class GeyserHandler {

	public static final String SESSION_SECRET = UUID.randomUUID().toString();
	public static boolean GEYSER_SUPPORT_ENABLED = false;

	public static void init(PluginBase plugin, Config config) {
		if (!(GEYSER_SUPPORT_ENABLED = isAvailable(config))) {
			return;
		}

		InstanceHolder.getHandshakeHandlers().addHandshakeHandler(data -> {
			if (data.getIp() == null) {
				plugin.getDebugger().warn("Connection with no bedrock data joined and ignored: username = %s, hostname = %s", data.getCorrectUsername(), data.getHostname());
				return;
			}

			plugin.getDebugger().warn("Bedrock connection joined and validated: username = %s, hostname = %s", data.getCorrectUsername(), data.getHostname());

			String oldPayload = data.getHostname();
			if (oldPayload.contains("///")) {
				oldPayload = oldPayload.split("///")[0];
			}

			String hostname = oldPayload;
			String realIp = data.getIp();
			String timestamp = "0";
			String signature = GeyserHandler.SESSION_SECRET;

			String newHostname = realIp + ":0///" + signature + "///" + timestamp + "///" + hostname;
			if (hostname.contains("\0")) {
				plugin.getDebugger().warn("Hostname contains null byte: " + Arrays.toString(hostname.toCharArray()));
			}

			plugin.getDebugger().warn("Setting hostname to %s - %s", newHostname, Arrays.toString(newHostname.toCharArray()));
			data.setHostname(newHostname);
		});
	}

	private static boolean isAvailable(Config config) {
		if (!config.isGeyser()) return false;

		try {
			Class.forName("org.geysermc.floodgate.api.InstanceHolder");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

}
