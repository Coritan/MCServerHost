package com.mcserverhost;

import java.net.InetSocketAddress;

public class FabricPlayerContext implements PlayerContext {

    private final Object connection;
    private final InetSocketAddress currentAddress;
    private InetSocketAddress newAddress;

    public FabricPlayerContext(Object connection, InetSocketAddress currentAddress) {
        this.connection = connection;
        this.currentAddress = currentAddress;
    }

    @Override
    public String getUUID() { return "unknown"; }

    @Override
    public String getName() { return "unknown"; }

    @Override
    public String getIP() {
        return currentAddress == null ? "unknown" : currentAddress.getAddress().getHostAddress();
    }

    @Override
    public void setIP(InetSocketAddress ip) {
        this.newAddress = ip;
    }

    public boolean hasNewAddress() { return newAddress != null; }

    public InetSocketAddress getNewAddress() { return newAddress; }

    public Object getConnection() { return connection; }
}
