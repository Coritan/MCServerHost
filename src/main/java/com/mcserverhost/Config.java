package com.mcserverhost;

import java.io.File;

/**
 * Holds plugin configuration defaults. Platform-specific subclasses supply the data folder.
 */
public abstract class Config {

	protected File dataFolder;

	public boolean isGeyser() {
		return true;
	}

	public boolean handlePreLoginEvent() {
		return true;
	}

	public String getTimestampValidationMode() {
		return "system";
	}

	public boolean doDebug() {
		return false;
	}

	public boolean preferProtocolLib() {
		return false;
	}

	public File getDataFolder() {
		return dataFolder;
	}

	protected final long maxTimestampDifference = 3;

	public long getMaxTimestampDifference() {
		return maxTimestampDifference;
	}

}
