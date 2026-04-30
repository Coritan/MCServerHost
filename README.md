# MCServerHost Plugin

**Version:** 1.2.0

A universal Minecraft plugin/mod that restores true player IP addresses on servers running behind [MCServerHost](https://www.mcserverhost.com) proxy infrastructure. When players connect through the proxy, their real IP and hostname are embedded in the handshake payload — this plugin intercepts that payload at the network level, validates it, and rewrites the connection so the backend server sees the player's actual IP instead of the proxy's.

It also provides a hub-transfer system, a graceful `/stop` override that sends players to a hub before shutdown, and full Fabric support via Mixin.

---

## Supported Platforms

| Platform | Notes |
|---|---|
| Bukkit / Spigot / Paper | Soft-depends on ProtocolLib and Floodgate |
| BungeeCord | Soft-depends on Floodgate |
| Velocity | API 1.1.9 (Java 8 compatible); soft-depends on Floodgate |
| Forge | 1.16.5 through 1.21.x (Java 8 – Java 25) |
| Fabric | 1.14+ dedicated-server, Fabric Loader 0.14+ |

A single JAR covers every platform.

---

## Installation

1. Download `MCServerHost-1.2.0.jar`.
2. Drop it into the `plugins/` folder of your Bukkit/Spigot/Paper, BungeeCord, or Velocity server — **or** into the `mods/` folder of your Forge or Fabric server.
3. Start the server. Default config files are generated on first run.

> Install on **every** backend server in your network, as well as any proxies (BungeeCord / Velocity) that sit in front of them.

---

## Configuration

### `hub.yml` (Bukkit/Paper/Forge/Fabric)

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

| Command | Platforms | Description |
|---|---|---|
| `/mcsh` (+ configured aliases) | Bukkit, Forge, Fabric | Transfer yourself to the hub server (requires client 1.20.5+) |
| `/transfer` | Fabric, BungeeCord | Transfer yourself (or a target) to the hub server |
| `/stop` | Fabric, Velocity (override) | Sends all online players to hub, then stops the server |

On Velocity, `/stop` is intercepted so the proxy will transfer connected players to the configured hub before shutting down. On Fabric, the vanilla `/stop` is replaced with the same behaviour when `send-to-hub-on-shutdown` is enabled.

---

## Soft Dependencies

| Dependency | Purpose |
|---|---|
| [ProtocolLib](https://github.com/dmulloy2/ProtocolLib) | Alternative packet interception method (Bukkit/Paper only) |
| [Floodgate](https://github.com/GeyserMC/Floodgate) | Bedrock player session authentication via Geyser |

Neither is required; the plugin operates without them.

---

## What's New in 1.2.0

- **Fabric support** — dedicated-server mod entrypoint, Mixin-based command hooks, and reflective Brigadier integration. Single JAR still covers every platform.
- **`/transfer` command** on Fabric and BungeeCord for sending players to the configured hub host/port using the 1.20.5+ transfer packet.
- **Graceful `/stop` override** on Fabric and Velocity — all online players are sent to the hub before shutdown when `send-to-hub-on-shutdown` is enabled.
- **Join-crash fix** — Brigadier `Command`/`Predicate` proxies now implement `hashCode`/`equals`/`toString` correctly. Previously, command-tree hashing on login threw a `NullPointerException` and kicked players with "Internal server error".
- **Cleaner logs** — debug instrumentation is silent by default on every platform. Only real warnings/errors reach the console.
- **Forge mod-event-bus fallback** on older loader versions that do not expose the mod event bus to `javafml`.

---

## Building from Source

See [BUILD.md](BUILD.md) for full build instructions.

**Quick summary:**
- Requires Java 8 (Amazon Corretto 8 recommended) and Gradle 7.6
- `libs/ProtocolLib.jar` must be present (see BUILD.md Step 3)
- Output: `build/libs/MCServerHost-1.2.0.jar`

```bash
JAVA_HOME=/tmp/jdk8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/tmp/jdk8/bin \
  bash /tmp/gradle/gradle-7.6/bin/gradle jar --no-daemon
```

---

## Multi-Version Forge & Fabric Support

The plugin supports Forge 1.16.5 through 1.21.x and Fabric 1.14+ from a single JAR by using multiple Mixin targets — one per mapping era — and letting the Mixin subsystem silently skip targets that do not exist at runtime. All NMS access beyond the injection points is done via reflection, so no version-specific code paths are needed at runtime. Brigadier command registration on Fabric is fully reflective for the same reason.

---

## License

&copy; [MCServerHost](https://www.mcserverhost.com). All rights reserved.
