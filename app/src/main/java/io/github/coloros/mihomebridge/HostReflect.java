package io.github.coloros.mihomebridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reflection stays inside the verified host; no broad method-name guessing. */
final class HostReflect {
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }
    static Object get(Object target, String name) throws Exception {
        return call(target, name, new Class<?>[0]);
    }
    static Object enumValue(Class<?> type, String name) throws Exception {
        return type.getMethod("valueOf", String.class).invoke(null, name);
    }
    private HostReflect() {}
}
