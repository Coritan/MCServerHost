package com.mcserverhost;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;

import java.lang.reflect.Field;

public class StopCommandInterceptor implements CommandExecutor {

    private final BukkitPlugin plugin;
    private final ShutdownTransferHandler transferHandler;

    public StopCommandInterceptor(BukkitPlugin plugin, ShutdownTransferHandler transferHandler) {
        this.plugin = plugin;
        this.transferHandler = transferHandler;
    }

    public void register() {
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            SimpleCommandMap commandMap = (SimpleCommandMap) commandMapField.get(Bukkit.getServer());

            Command existing = commandMap.getCommand("stop");
            if (existing != null) {
                Field executorField = existing.getClass().getDeclaredField("executor");
                executorField.setAccessible(true);
                executorField.set(existing, this);
                return;
            }

            org.bukkit.command.defaults.BukkitCommand intercepted = new org.bukkit.command.defaults.BukkitCommand("stop") {
                @Override
                public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                    return onCommand(sender, this, commandLabel, args);
                }
            };
            commandMap.register("minecraft", intercepted);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not intercept stop command via reflection, registering override: " + e.getMessage());
            registerViaPluginYml();
        }
    }

    private void registerViaPluginYml() {
        org.bukkit.command.PluginCommand cmd = plugin.getCommand("stop");
        if (cmd != null) {
            cmd.setExecutor(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("minecraft.command.stop") && !sender.isOp()) {
            sender.sendMessage("You do not have permission to stop the server.");
            return true;
        }

        transferHandler.transferAll();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:stop");
        }, 20L);

        return true;
    }

}
