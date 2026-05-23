package com.heonezen.haproxy.util;

import java.lang.reflect.Field;
import java.net.SocketAddress;

public final class ReflectionUtil {
    private ReflectionUtil() {}
    public static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
    public static Field findFieldByType(Class<?> clazz, Class<?> type) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }
    public static Object getFieldValue(Object obj, String name) throws ReflectiveOperationException {
        Field f = findField(obj.getClass(), name);
        if (f == null)
            throw new NoSuchFieldException("Field '" + name + "' not found in " + obj.getClass().getName());
        return f.get(obj);
    }
    public static void setSocketAddress(Object obj, SocketAddress address) throws ReflectiveOperationException {
        Field f = findFieldByType(obj.getClass(), SocketAddress.class);
        if (f == null)
            throw new NoSuchFieldException("No SocketAddress field found in " + obj.getClass().getName());
        f.set(obj, address);
    }
}
