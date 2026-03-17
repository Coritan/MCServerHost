package com.mcserverhost;

import java.net.InetSocketAddress;

public interface SpoofableAddress {
    void mcsh$setSpoofedAddress(InetSocketAddress address);
    InetSocketAddress mcsh$getSpoofedAddress();
}
