package com.mcserverhost;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;

public class TransferCommand implements CommandExecutor {

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

    public TransferCommand(BukkitPlugin plugin, HubConfig hubConfig) {
        this.plugin = plugin;
        this.hubConfig = hubConfig;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        String host = hubConfig.getHost();
        int port = hubConfig.getPort();

        if (TRANSFER_METHOD != null) {
            try {
                TRANSFER_METHOD.invoke(player, host, port);
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("Transfer packet failed, falling back to plugin message: " + e.getMessage());
            }
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("ConnectOther");
            out.writeUTF(player.getName());
            out.writeUTF(host + ":" + port);
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to transfer player " + player.getName() + ": " + e.getMessage());
        }

        return true;
    }

}
