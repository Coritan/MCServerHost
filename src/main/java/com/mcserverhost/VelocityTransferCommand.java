package com.mcserverhost;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import java.net.InetSocketAddress;

public class VelocityTransferCommand implements SimpleCommand {

    private final ProxyServer server;
    private final HubConfig hubConfig;

    public VelocityTransferCommand(ProxyServer server, HubConfig hubConfig) {
        this.server = server;
        this.hubConfig = hubConfig;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            source.sendMessage(net.kyori.adventure.text.Component.text("This command can only be used by players."));
            return;
        }

        Player player = (Player) source;

        ServerInfo info = new ServerInfo("mcsh-transfer", new InetSocketAddress(hubConfig.getHost(), hubConfig.getPort()));
        RegisteredServer target = server.registerServer(info);

        player.createConnectionRequest(target).fireAndForget();
    }

}
