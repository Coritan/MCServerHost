package com.mcserverhost;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

import java.net.InetSocketAddress;
import java.util.List;

public class BungeeTransferCommand extends Command {

    private final HubConfig hubConfig;

    public BungeeTransferCommand(HubConfig hubConfig) {
        super("mcsh", null, hubConfig.getAliases().toArray(new String[0]));
        this.hubConfig = hubConfig;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            sender.sendMessage("This command can only be used by players.");
            return;
        }

        ProxiedPlayer player = (ProxiedPlayer) sender;

        ServerInfo target = ProxyServer.getInstance().constructServerInfo(
            "mcsh-transfer",
            new InetSocketAddress(hubConfig.getHost(), hubConfig.getPort()),
            "",
            false
        );

        player.connect(target);
    }

}
