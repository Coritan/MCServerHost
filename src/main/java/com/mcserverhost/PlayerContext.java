package com.mcserverhost;

import com.mcserverhost.PluginException.PlayerManipulationException;

import java.net.InetSocketAddress;

/**
 * Abstracts read/write access to a platform-specific connecting player
 */
public interface PlayerContext {

	String getUUID();

	String getName();

	String getIP();

	void setIP(InetSocketAddress ip) throws PlayerManipulationException;

}
