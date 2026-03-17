package com.mcserverhost;

import com.mcserverhost.PluginException.PacketManipulationException;

/**
 * Abstracts read/write access to a platform-specific handshake packet
 */
public interface PacketContext {

	String getPayloadString();

	void setPacketHostname(String hostname) throws PacketManipulationException;

}
