package com.mcserverhost;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;

public class VelocityShutdownCommand implements SimpleCommand {

    private final ProxyServer server;
    private final HubConfig hubConfig;

    public VelocityShutdownCommand(ProxyServer server, HubConfig hubConfig) {
        this.server = server;
        this.hubConfig = hubConfig;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        if (!source.hasPermission("velocity.command.shutdown")) {
            source.sendMessage(Component.text("You do not have permission to shut down the proxy."));
            return;
        }

        if (hubConfig.isSendToHubOnShutdown()) {
            ServerInfo info = new ServerInfo("mcsh-shutdown", new InetSocketAddress(hubConfig.getHost(), hubConfig.getPort()));
            RegisteredServer target = server.registerServer(info);
            for (Player player : server.getAllPlayers()) {
                try {
                    player.createConnectionRequest(target).fireAndForget();
                } catch (Exception ignored) {
                }
            }
        }

        server.shutdown();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocity.command.shutdown");
    }

}
