package dev.xantha.vss.compat;

import java.lang.reflect.Method;
import java.util.Optional;

/** Cache the native ABI once per backend. Ownership-only changes use the native save queue. */
final class VoxyRealIngestAccess {
    private record Access(java.lang.reflect.Field storage,Method acquire,Method release,Method mark,Method save) { }
    private static final ClassValue<Optional<Access>> ACCESS=new ClassValue<>() {
        @Override protected Optional<Access> computeValue(Class<?> engine) {
            try {
                Method acquire=engine.getMethod("acquire",long.class);
                Class<?> section=acquire.getReturnType();
                return Optional.of(new Access(engine.getField("storage"),acquire,section.getMethod("release"),
                        section.getMethod("markDirty"),engine.getMethod("saveSection",section,boolean.class,boolean.class)));
            } catch(ReflectiveOperationException missing) { return Optional.empty(); }
        }
    };
    private VoxyRealIngestAccess() { }

    static boolean bind(Object engine) {
        Optional<Access> access=ACCESS.get(engine.getClass());
        if(access.isEmpty())return false;
        try { return VoxyRealCoveragePersistence.bind(engine,access.get().storage.get(engine)); }
        catch(IllegalAccessException failure) { return false; }
    }

    static int committed(Object engine,int x,int y,int z) {
        return bind(engine)?VoxyRealCoveragePersistence.committed(engine,x,y,z):0;
    }

    static void save(Object engine,int x,int y,int z,int changed) {
        if(changed==0)return;
        Access access=ACCESS.get(engine.getClass()).orElseThrow();
        try {
            for(int lod=0;lod<=4;lod++)if((changed&1<<lod)!=0) {
                Object section=access.acquire.invoke(engine,VoxySectionPosition.key(lod,x>>(lod+1),y>>(lod+1),z>>(lod+1)));
                try {
                    access.mark.invoke(section);
                    // Nonblocking: persistence must not steal save work onto the ingest thread.
                    access.save.invoke(engine,section,true,false);
                } finally { access.release.invoke(section); }
            }
        } catch(ReflectiveOperationException failure) {
            dev.xantha.vss.common.VSSLogger.warn("Could not queue Voxy real coverage save",failure);
        }
    }
}
