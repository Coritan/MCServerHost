# MCServerHost Plugin

**Version:** 1.1.0

A universal Minecraft plugin/mod that restores true player IP addresses on servers running behind [MCServerHost](https://www.mcserverhost.com) proxy infrastructure. When players connect through the proxy, their real IP and hostname are embedded in the handshake payload — this plugin intercepts that payload at the network level, validates it, and rewrites the connection so the backend server sees the player's actual IP instead of the proxy's.

---

## Supported Platforms

| Platform | Notes |
|---|---|
| Bukkit / Spigot / Paper | Soft-depends on ProtocolLib and Floodgate |
| BungeeCord | Soft-depends on Floodgate |
| Velocity | API 1.1.9 (Java 8 compatible); soft-depends on Floodgate |
| Forge | 1.16.5 through 1.21.x (Java 8 – Java 25) |

A single JAR covers all platforms.

---

## Installation

1. Download `MCServerHost-1.1.0.jar`.
2. Drop it into the `plugins/` folder of your Bukkit/Spigot/Paper, BungeeCord, or Velocity server — **or** into the `mods/` folder of your Forge server.
3. Start the server. Default config files are generated on first run.

> Install on **every** backend server in your network, as well as any proxies (BungeeCord / Velocity) that sit in front of them.

---

## Configuration

### `hub.yml` (Bukkit/Paper/Forge)

Controls where players are sent when the server shuts down or when the hub command is used.

```yaml
# Hostname or IP of the hub server
host: mcsh.io

# Port of the hub server
port: 25577

# Additional aliases for /mcsh
aliases:
  - mh
  - mk

# Enable or disable the /mcsh command
hub-command-enabled: true

# Send players to hub on shutdown/crash instead of disconnecting them
send-to-hub-on-shutdown: true
```

### Config options (advanced)

| Option | Default | Description |
|---|---|---|
| `debug` | `false` | Verbose logging for troubleshooting |
| `prefer-protocollib` | `false` | Prefer ProtocolLib for packet interception when available |
| `timestamp-validation-mode` | `system` | Clock source used for replay-attack validation |
| `geyser` | `true` | Enable special handling for Bedrock players via Geyser |
| `handle-pre-login-event` | `true` | Whether to process the pre-login event |

---

## Commands

| Command | Permission | Description |
|---|---|---|
| `/mcsh` | — | Transfer yourself to the hub server |
| `/stop` | `minecraft.command.stop` | Send all online players to hub, then stop the server |

---

## Soft Dependencies

| Dependency | Purpose |
|---|---|
| [ProtocolLib](https://github.com/dmulloy2/ProtocolLib) | Alternative packet interception method (Bukkit/Paper only) |
| [Floodgate](https://github.com/GeyserMC/Floodgate) | Bedrock player session authentication via Geyser |

Neither is required; the plugin operates without them.

---

## Building from Source

See [BUILD.md](BUILD.md) for full build instructions.

**Quick summary:**
- Requires Java 8 (Amazon Corretto 8 recommended) and Gradle 7.6
- `libs/ProtocolLib.jar` must be present (see BUILD.md Step 3)
- Output: `build/libs/MCServerHost-1.1.0.jar`

```bash
JAVA_HOME=/tmp/jdk8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/tmp/jdk8/bin \
  bash /tmp/gradle/gradle-7.6/bin/gradle jar --no-daemon
```

---

## Multi-Version Forge Support

The plugin supports Forge 1.16.5 through 1.21.x from a single JAR by using two Mixin classes — one targeting the 1.16.5 MCP-named handshake packet class, and one targeting the 1.17+ Mojang-named listener class. The Mixin subsystem silently skips whichever target does not exist at runtime. All NMS access beyond the injection point is done via reflection, so no version-specific code paths are needed at runtime.

---

## License

&copy; [MCServerHost](https://www.mcserverhost.com). All rights reserved.
