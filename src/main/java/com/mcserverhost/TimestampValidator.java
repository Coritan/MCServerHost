package com.mcserverhost;

import com.mcserverhost.PluginException.InitializationException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * Validates handshake timestamps to prevent replay attacks.
 * Includes an HTP-synced implementation as a nested type.
 */
public abstract class TimestampValidator {

	public static TimestampValidator createEmpty(PluginBase plugin) {
		return new TimestampValidator(plugin) {
			@Override
			public boolean validate(long timestamp) {
				return true;
			}
		};
	}

	public static TimestampValidator createDefault(PluginBase plugin) {
		return new TimestampValidator(plugin) {};
	}

	public static TimestampValidator createHTPDate(PluginBase plugin) {
		return new HTPDateSynced(plugin);
	}


	protected final PluginBase plugin;

	public TimestampValidator(PluginBase plugin) {
		this.plugin = plugin;
	}

	public boolean validate(long timestamp) {
		return Math.abs(getUnixTime() - timestamp) <= plugin.getMcshConfig().getMaxTimestampDifference();
	}

	public long getUnixTime() {
		return System.currentTimeMillis() / 1000;
	}


	private static class HTPDateSynced extends TimestampValidator {

		private volatile long htpDateOffset = 0;

		private HTPDateSynced(PluginBase plugin) {
			super(plugin);

			ForkJoinPool.commonPool().execute(() -> {
				try {
					updateOffset();
				} catch (Exception e) {
					throw new InitializationException(e);
				}
			});
		}

		private void updateOffset() throws IOException {
			Socket socket = new Socket("google.com", 80);

			String payload = "HEAD http://google.com/ HTTP/1.1\r\nHost: google.com\r\nUser-Agent: mcsh/1.0\r\nPragma: no-cache\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
			socket.getOutputStream().write(payload.getBytes());

			long readTime = System.currentTimeMillis();

			List<String> response = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
					.lines()
					.collect(Collectors.toList());

			Date serverDate = parseDate(response);
			htpDateOffset = Math.round((serverDate.getTime() - readTime) / 1000.0) * 1000;
		}

		private Date parseDate(List<String> response) {
			for (String line : response) {
				if (!line.startsWith("Date: "))
					continue;

				SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
				try {
					return sdf.parse(line.substring("Date: ".length()));
				} catch (ParseException e) {
					throw new IllegalStateException(e);
				}
			}

			throw new IllegalArgumentException("no date line found - response: " + response);
		}

		@Override
		public long getUnixTime() {
			return (System.currentTimeMillis() + htpDateOffset) / 1000;
		}

	}

}
