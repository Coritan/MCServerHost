package com.mcserverhost.mixin;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.handshake.IHandshakeNetHandler;
import com.mcserverhost.PluginException;

public final class ForgeHandshakeAccess {

    private ForgeHandshakeAccess() {}

    public static NetworkManager getConnection(IHandshakeNetHandler listener) {
        Class<?> clazz = listener.getClass();
        while (clazz != null) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (NetworkManager.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return (NetworkManager) field.get(listener);
                    } catch (Exception e) {
                        throw new PluginException.ReflectionException(e);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        throw new PluginException.ReflectionException("No NetworkManager field found in " + listener.getClass().getName());
    }

}
