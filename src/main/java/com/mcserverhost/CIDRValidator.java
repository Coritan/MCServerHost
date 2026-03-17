package com.mcserverhost;

import com.mcserverhost.PluginException.CIDRException;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

/**
 * Validates incoming IPs against a CIDR whitelist loaded from disk
 */
public class CIDRValidator {

	private final PluginBase plugin;
	private final File ipWhitelistFolder;
	private final List<CIDRMatcher> cidrMatchers;
	private final Set<String> cache = new HashSet<>();

	public CIDRValidator(PluginBase plugin) throws CIDRException {
		this.plugin = plugin;

		ipWhitelistFolder = new File(plugin.getMcshConfig().getDataFolder(), "ip-whitelist");
		if (!ipWhitelistFolder.exists())
			ipWhitelistFolder.mkdir();

		try {
			List<String> whitelists = loadWhitelists();
			cidrMatchers = loadCIDRMatchers(whitelists);
		} catch (Exception e) {
			throw new CIDRException(e);
		}
	}


	private List<CIDRMatcher> loadCIDRMatchers(List<String> whitelists) {
		List<CIDRMatcher> matchers = new ArrayList<>();

		for (String whitelist : whitelists)
			try {
				matchers.add(new CIDRMatcher(whitelist));
			} catch (Exception e) {
				plugin.getDebugger().warn("Exception occured while creating CIDRMatcher for \"%s\". Ignoring it.", whitelist);
				plugin.getDebugger().exception(e);
			}

		return matchers;
	}

	private List<String> loadWhitelists() throws FileNotFoundException {
		List<String> whitelists = new ArrayList<>();

		File[] files = ipWhitelistFolder.listFiles();
		if (files == null || files.length == 0) {
			return whitelists;
		}

		for (File file : files) {
			if (file.isDirectory())
				continue;

			try (Scanner scanner = new Scanner(file)) {
				while (scanner.hasNextLine()) {
					String cidrEntry = scanner.nextLine();
					whitelists.add(cidrEntry);
				}
			}
		}

		return whitelists;
	}

	public boolean validate(InetAddress inetAddress) {
		String ip = inetAddress.getHostAddress();

		if (cache.contains(ip))
			return true;

		for (CIDRMatcher cidrMatcher : cidrMatchers)
			if (cidrMatcher.match(inetAddress)) {
				cache.add(ip);
				return true;
			}

		return false;
	}


	private static class CIDRMatcher {

		private final int maskBits;
		private final int maskBytes;
		private final boolean simpleCIDR;
		private final InetAddress cidrAddress;

		private CIDRMatcher(String cidrMatchString) {
			String[] split = cidrMatchString.split("/");

			String parsedIPAddress;
			if (split.length != 0) {
				parsedIPAddress = split[0];
				this.maskBits = Integer.parseInt(split[1]);
				this.simpleCIDR = maskBits == 32;
			} else {
				parsedIPAddress = cidrMatchString;
				this.maskBits = -1;
				this.simpleCIDR = true;
			}

			this.maskBytes = simpleCIDR ? -1 : maskBits / 8;

			try {
				cidrAddress = InetAddress.getByName(parsedIPAddress);
			} catch (UnknownHostException e) {
				throw new CIDRException(e);
			}
		}

		private boolean match(InetAddress inetAddress) {
			if (!cidrAddress.getClass().equals(inetAddress.getClass())) return false;
			if (simpleCIDR) return inetAddress.equals(cidrAddress);

			byte[] inetAddressBytes = inetAddress.getAddress();
			byte[] requiredAddressBytes = cidrAddress.getAddress();
			byte finalByte = (byte) (0xFF00 >> (maskBits & 0x07));

			for (int i = 0; i < maskBytes; i++) {
				if (inetAddressBytes[i] != requiredAddressBytes[i]) return false;
			}

			if (finalByte != 0)
				return (inetAddressBytes[maskBytes] & finalByte) == (requiredAddressBytes[maskBytes] & finalByte);

			return true;
		}

	}

}
