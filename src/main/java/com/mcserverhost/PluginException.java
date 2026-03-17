package com.mcserverhost;

/**
 * Base exception for the plugin, contains all exception subtypes as nested classes
 */
public abstract class PluginException extends RuntimeException {

	public PluginException(Throwable throwable) { super(throwable); }
	public PluginException(String message) { super(message); }
	public PluginException(String message, Throwable throwable) { super(message, throwable); }
	public PluginException() { super(); }


	public static class HandshakeException extends PluginException {
		public HandshakeException(Throwable throwable) { super("An exception occured during the handshake process", throwable); }
		public HandshakeException(String message) { super(message); }
		public HandshakeException(String message, Throwable throwable) { super(message, throwable); }
		public HandshakeException() { super(); }
	}

	public static class InitializationException extends PluginException {
		public InitializationException(Throwable throwable) { super("An exception occured during the initalization process", throwable); }
		public InitializationException(String message) { super(message); }
		public InitializationException(String message, Throwable throwable) { super(message, throwable); }
		public InitializationException() { super(); }
	}

	public static class ReflectionException extends PluginException {
		public ReflectionException(Throwable throwable) { super("An exception occured during the reflection process", throwable); }
		public ReflectionException(String message) { super(message); }
		public ReflectionException(String message, Throwable throwable) { super(message, throwable); }
		public ReflectionException() { super(); }
	}

	public static class CIDRException extends PluginException {
		public CIDRException(Throwable throwable) { super("An exception occured during the CIDR process", throwable); }
		public CIDRException(String message) { super(message); }
		public CIDRException(String message, Throwable throwable) { super(message, throwable); }
		public CIDRException() { super(); }
	}

	public static class InvalidSecretException extends PluginException {
		public InvalidSecretException(Throwable throwable) { super("An invalid secret was provided during the geyser handshake process", throwable); }
		public InvalidSecretException(String message) { super(message); }
		public InvalidSecretException(String message, Throwable throwable) { super(message, throwable); }
		public InvalidSecretException() { super(); }
	}

	public static class PacketManipulationException extends HandshakeException {
		public PacketManipulationException(Throwable throwable) { super(throwable); }
		public PacketManipulationException(String message) { super(message); }
		public PacketManipulationException(String message, Throwable throwable) { super(message, throwable); }
		public PacketManipulationException() { super(); }
	}

	public static class PlayerManipulationException extends HandshakeException {
		public PlayerManipulationException(Throwable throwable) { super(throwable); }
		public PlayerManipulationException(String message) { super(message); }
		public PlayerManipulationException(String message, Throwable throwable) { super(message, throwable); }
		public PlayerManipulationException() { super(); }
	}

	public static class InvalidPayloadException extends HandshakeException {
		public InvalidPayloadException(Throwable throwable) { super(throwable); }
		public InvalidPayloadException(String message) { super(message); }
		public InvalidPayloadException(String message, Throwable throwable) { super(message, throwable); }
		public InvalidPayloadException() { super(); }
	}

	public static class TimestampValidationException extends HandshakeException {
		private final TimestampValidator timestampValidator;
		private final long timestamp;

		public TimestampValidationException(TimestampValidator timestampValidator, long timestamp) {
			super();
			this.timestampValidator = timestampValidator;
			this.timestamp = timestamp;
		}

		public TimestampValidator getTimestampValidator() { return timestampValidator; }
		public long getTimestamp() { return timestamp; }
	}

}
