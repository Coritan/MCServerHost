package com.mcserverhost.mixin;

import com.mcserverhost.PluginException;

import java.lang.reflect.Field;

public final class ForgeHandshakeAccess121 {

    private ForgeHandshakeAccess121() {}

    public static Object getConnection(Object listener) {
        Class<?> connectionClass = null;
        try {
            connectionClass = Class.forName("net.minecraft.network.Connection");
        } catch (ClassNotFoundException ignored) {
        }
        if (connectionClass == null) {
            try {
                connectionClass = Class.forName("net.minecraft.network.NetworkManager");
            } catch (ClassNotFoundException ignored) {
            }
        }

        Class<?> clazz = listener.getClass();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                Class<?> type = field.getType();
                if (connectionClass != null && connectionClass.isAssignableFrom(type)) {
                    try {
                        field.setAccessible(true);
                        return field.get(listener);
                    } catch (Exception e) {
                        throw new PluginException.ReflectionException(e);
                    }
                }
                String typeName = type.getName();
                if (typeName.equals("net.minecraft.network.Connection")
                        || typeName.equals("net.minecraft.network.NetworkManager")) {
                    try {
                        field.setAccessible(true);
                        return field.get(listener);
                    } catch (Exception e) {
                        throw new PluginException.ReflectionException(e);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        throw new PluginException.ReflectionException("No Connection field found in " + listener.getClass().getName());
    }

}
