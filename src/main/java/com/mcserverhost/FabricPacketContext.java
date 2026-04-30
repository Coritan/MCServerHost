package com.mcserverhost;

import java.lang.reflect.Field;

public class FabricPacketContext implements PacketContext {

    private final Object packet;
    private final String hostname;

    public FabricPacketContext(Object packet, String hostname) {
        this.packet = packet;
        this.hostname = hostname;
    }

    @Override
    public String getPayloadString() {
        return hostname;
    }

    @Override
    public void setPacketHostname(String newHostname) {
        Class<?> c = packet.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    Object val = FabricHandshakeSupport.readField(packet, f);
                    if (hostname.equals(val)) {
                        FabricHandshakeSupport.writeField(packet, f, newHostname);
                        return;
                    }
                }
            }
            c = c.getSuperclass();
        }
        c = packet.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    if (FabricHandshakeSupport.writeField(packet, f, newHostname)) return;
                }
            }
            c = c.getSuperclass();
        }
    }
}
