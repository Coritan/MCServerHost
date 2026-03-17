package com.mcserverhost;

import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.util.Collection;

public class ShutdownTransferHandler {

    private static final Method TRANSFER_METHOD;

    static {
        Method m = null;
        try {
            m = Player.class.getMethod("transfer", String.class, int.class);
        } catch (NoSuchMethodException ignored) {
        }
        TRANSFER_METHOD = m;
    }

    private final BukkitPlugin plugin;
    private final HubConfig hubConfig;

    public ShutdownTransferHandler(BukkitPlugin plugin, HubConfig hubConfig) {
        this.plugin = plugin;
        this.hubConfig = hubConfig;
    }

    public void transferAll() {
        String host = hubConfig.getHost();
        int port = hubConfig.getPort();

        Collection<? extends Player> players = plugin.getServer().getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }

        for (Player player : players) {
            try {
                if (TRANSFER_METHOD != null) {
                    TRANSFER_METHOD.invoke(player, host, port);
                } else {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bytes);
                    out.writeUTF("Connect");
                    out.writeUTF(host + ":" + port);
                    player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to transfer " + player.getName() + " on shutdown: " + e.getMessage());
            }
        }

        if (TRANSFER_METHOD == null) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
