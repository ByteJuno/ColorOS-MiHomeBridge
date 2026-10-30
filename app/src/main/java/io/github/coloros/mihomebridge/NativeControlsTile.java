package io.github.coloros.mihomebridge;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/** Delegates changes to the live ColorOS pipeline; settings are its output. */
final class NativeControlsTile {
    private final Object host;
    private final Object spec;
    private final Method snapshot, getSpec, add, remove;

    NativeControlsTile(Object host, Class<?> specType, Object spec) throws ReflectiveOperationException {
        this.host = host;
        this.spec = spec;
        snapshot = accessible(host.getClass().getMethod("getCurrentTilesSpecs"));
        getSpec = accessible(specType.getMethod("getSpec"));
        add = accessible(host.getClass().getMethod("addTile", specType, int.class));
        remove = accessible(host.getClass().getMethod("removeTiles", Collection.class));
    }
    private static Method accessible(Method method) { method.setAccessible(true); return method; }
    private List<?> specs() throws ReflectiveOperationException { return (List<?>) snapshot.invoke(host); }
    boolean isPresent() throws ReflectiveOperationException {
        for (Object item : specs()) if ("controls".equals(getSpec.invoke(item))) return true;
        return false;
    }
    void add() throws ReflectiveOperationException {
        if (!isPresent()) add.invoke(host, spec, specs().size());
    }
    void remove() throws ReflectiveOperationException {
        if (isPresent()) remove.invoke(host, List.of(spec));
    }
}
