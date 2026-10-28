package dev.xantha.vss.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/** Optional native occupancy query. Unknown layouts keep native subdivision behavior. */
final class VoxyNodeChildren {
    private record Access(Method children, Field roots) { }
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        protected Access computeValue(Class<?> type) {
            try {
                Class<?> store = type.getDeclaredField("nodeData").getType();
                Method children = store.getMethod("getNodeChildExistence", int.class);
                Field roots = type.getDeclaredField("topLevelNodes"); roots.setAccessible(true);
                if (!Set.class.isAssignableFrom(roots.getType())) return new Access(null, null);
                return new Access(children, roots);
            } catch (NoSuchFieldException | NoSuchMethodException e) { return new Access(null, null); }
        }
    };
    static boolean emptyNonRoot(Object nodes, Object store, int nodeId, long position) throws ReflectiveOperationException {
        Access access = ACCESS.get(nodes.getClass());
        return access.children != null && ((Number) access.children.invoke(store, nodeId)).intValue() == 0
                && !((Set<?>) access.roots.get(nodes)).contains(position);
    }
}
