package com.mcserverhost;

import java.util.logging.Logger;

/**
 * Conditional debug logger — active or silent based on plugin config
 */
public abstract class Debugger {

	public static Debugger createDebugger(PluginBase plugin) {
		if (plugin.getMcshConfig().doDebug())
			return new Debugger(plugin.getLogger()) {
				@Override public void info(String format, Object... formats) { this.logger.info("Debug : " + String.format(format, formats)); }
				@Override public void warn(String format, Object... formats) { this.logger.warning("Debug : " + String.format(format, formats)); }
				@Override public void error(String format, Object... formats) { this.logger.severe("Debug : " + String.format(format, formats)); }
				@Override public void exception(Exception exception) { exception.printStackTrace(); }
			};
		else
			return new Debugger(null) {
				@Override public void info(String format, Object... formats) {}
				@Override public void warn(String format, Object... formats) {}
				@Override public void error(String format, Object... formats) {}
				@Override public void exception(Exception exception) {}
			};
	}


	protected final Logger logger;

	private Debugger(Logger logger) {
		this.logger = logger;
	}

	public abstract void info(String format, Object... formats);

	public abstract void warn(String format, Object... formats);

	public abstract void error(String format, Object... formats);

	public abstract void exception(Exception exception);

}
