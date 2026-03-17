package com.mcserverhost;

import java.util.logging.Logger;

/**
 * Common interface implemented by all platform entry points
 */
public interface PluginBase {

	Config getMcshConfig();

	Logger getLogger();

	HandshakeHandler getHandshakeHandler();

	Debugger getDebugger();

	default void initialization() {
		String jvmVersion = System.getProperty("java.version");
		try {
			String[] versionParts = jvmVersion.split("\\.");
			int baseVersion = Integer.parseInt(versionParts[0]);
			if (baseVersion < 11)
				this.getDebugger().warn("The Java version you are running is outdated for MCSH and may cause issues. Update to atleast Java 11. Your version: Java %s", jvmVersion);
		} catch (Throwable t) {
			this.getDebugger().error("Failed to check java version for string: " + jvmVersion);
		}
	}

}
