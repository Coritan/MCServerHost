package com.mcserverhost;

import com.mcserverhost.PluginException.PacketManipulationException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class FabricPacketContext implements PacketContext {

    private final Object packet;

    public FabricPacketContext(Object packet) {
        this.packet = packet;
    }

    @Override
    public String getPayloadString() {
        for (String name : new String[]{"comp_2340", "comp_1267", "method_10920", "getHostName", "hostName"}) {
            try {
                Method m = packet.getClass().getMethod(name);
                Object v = m.invoke(packet);
                if (v instanceof String) return (String) v;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {}
        }
        Class<?> c = packet.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    Object v = FabricHandshakeSupport.readField(packet, f);
                    if (v instanceof String) return (String) v;
                }
            }
            c = c.getSuperclass();
        }
        throw new PluginException.ReflectionException(new NoSuchFieldException("hostName not found on " + packet.getClass().getName()));
    }

    @Override
    public void setPacketHostname(String hostname) throws PacketManipulationException {
        Class<?> c = packet.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    try { FabricHandshakeSupport.writeField(packet, f, hostname); return; } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }
    }
}
