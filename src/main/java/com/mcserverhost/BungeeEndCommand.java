package com.mcserverhost;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.net.InetSocketAddress;

public class BungeeEndCommand extends Command {

    private final HubConfig hubConfig;

    public BungeeEndCommand(HubConfig hubConfig) {
        super("end", "bungeecord.command.end");
        this.hubConfig = hubConfig;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bungeecord.command.end")) {
            sender.sendMessage("You do not have permission to shut down the proxy.");
            return;
        }

        if (hubConfig.isSendToHubOnShutdown()) {
            for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
                try {
                    ServerInfo target = ProxyServer.getInstance().constructServerInfo(
                        "mcsh-shutdown",
                        new InetSocketAddress(hubConfig.getHost(), hubConfig.getPort()),
                        "",
                        false
                    );
                    player.connect(target);
                } catch (Exception ignored) {
                }
            }
        }

        ProxyServer.getInstance().stop();
    }

}
