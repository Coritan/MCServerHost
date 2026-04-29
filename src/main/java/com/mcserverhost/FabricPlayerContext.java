package com.mcserverhost;

import com.mcserverhost.PluginException.PlayerManipulationException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class FabricPlayerContext implements PlayerContext {

    private final Object connection;
    private String ip;

    public FabricPlayerContext(Object connection) {
        this.connection = connection;
        this.ip = resolveIp(connection);
    }

    private static String resolveIp(Object conn) {
        try {
            for (Method m : conn.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (n.equals("getAddress") || n.equals("getRemoteAddress") || n.equals("method_10755") || n.equals("method_52917")) {
                    try {
                        Object v = m.invoke(conn);
                        if (v instanceof InetSocketAddress) return ((InetSocketAddress) v).getAddress().getHostAddress();
                    } catch (Throwable ignored) {}
                }
            }
            Field f = findSocketAddressField(conn);
            if (f != null) {
                Object v = FabricHandshakeSupport.readField(conn, f);
                if (v instanceof InetSocketAddress) return ((InetSocketAddress) v).getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    private static Field findSocketAddressField(Object conn) {
        Class<?> c = conn.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (SocketAddress.class.isAssignableFrom(f.getType())) return f;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    @Override public String getUUID() { return "unknown"; }
    @Override public String getName() { return "unknown"; }
    @Override public String getIP() { return ip; }

    @Override
    public void setIP(InetSocketAddress newAddr) throws PlayerManipulationException {
        try {
            this.ip = newAddr.getAddress().getHostAddress();
            Field f = findSocketAddressField(connection);
            if (f == null) throw new NoSuchFieldException("SocketAddress field not found on " + connection.getClass().getName());
            FabricHandshakeSupport.writeField(connection, f, newAddr);
        } catch (Throwable e) {
            throw new PluginException.PlayerManipulationException(e);
        }
    }
}
