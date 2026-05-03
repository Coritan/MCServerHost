package com.mcserverhost;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

public class StopCommandInterceptor implements Listener {

    private final BukkitPlugin plugin;
    private final ShutdownTransferHandler transferHandler;
    private volatile boolean stopping = false;

    public StopCommandInterceptor(BukkitPlugin plugin, ShutdownTransferHandler transferHandler) {
        this.plugin = plugin;
        this.transferHandler = transferHandler;
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onServerCommand(ServerCommandEvent event) {
        if (!isStopCommand(event.getCommand())) return;
        if (!intercept(event.getSender())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg.startsWith("/")) msg = msg.substring(1);
        if (!isStopCommand(msg)) return;
        if (!event.getPlayer().hasPermission("minecraft.command.stop") && !event.getPlayer().isOp()) return;
        if (!intercept(event.getPlayer())) return;
        event.setCancelled(true);
    }

    private boolean isStopCommand(String command) {
        if (command == null) return false;
        String trimmed = command.trim().toLowerCase();
        if (trimmed.isEmpty()) return false;
        String head = trimmed.split("\\s+", 2)[0];
        return head.equals("stop") || head.equals("minecraft:stop");
    }

    private boolean intercept(CommandSender sender) {
        if (stopping) return false;
        stopping = true;

        plugin.getLogger().info("[MCServerHost] /stop intercepted — transferring players before shutdown.");
        try {
            transferHandler.transferAll();
        } catch (Throwable t) {
            plugin.getLogger().warning("[MCServerHost] Shutdown transfer failed: " + t.getMessage());
        }

        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 20L);
        return true;
    }
}
