package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XaeroPredictionStoreTest {
    @TempDir Path root;
    private final XaeroPredictionBridge.Quality quality=new XaeroPredictionBridge.Quality(4,8,true);
    private static void settle() throws Exception {
        XaeroPredictionStore.awaitIoForTest(); XaeroPredictionStore.awaitIoForTest();
    }
    @Test void roundTripAndPendingRealRemovalSurviveReopenWithNegativeCoordinates() throws Exception {
        var store=new XaeroPredictionStore(); store.ready(root,-1,-33); settle();
        var stamp=new XaeroPredictionStore.Stamp(12345L,quality);
        store.put(root,-1,-33,stamp); settle();
        var reloaded=new XaeroPredictionStore(); reloaded.ready(root,-1,-33); settle();
        assertEquals(stamp,reloaded.get(root,-1,-33));
        var racing=new XaeroPredictionStore(); racing.ready(root,-1,-33);
        racing.put(root,-1,-33,null); settle();
        var finalStore=new XaeroPredictionStore(); finalStore.ready(root,-1,-33); settle();
        assertNull(finalStore.get(root,-1,-33));
    }
    @Test void corruptOrTruncatedSidecarCannotGrantPredictionOwnership() throws Exception {
        Path file=root.resolve("vss-prediction/r.0.0.dat"); Files.createDirectories(file.getParent());
        Files.write(file,new byte[]{0x56,0x53,0x50,0x31,0,0,0,1});
        var store=new XaeroPredictionStore(); store.ready(root,0,0); settle();
        assertTrue(store.ready(root,0,0)); assertNull(store.get(root,0,0));
        store.put(root,0,0,new XaeroPredictionStore.Stamp(1,quality)); settle();
        assertNull(store.get(root,0,0));
    }
    @Test void differentXaeroMapFoldersNeverShareProvenanceAndCacheStaysBounded() throws Exception {
        var store=new XaeroPredictionStore(); Path other=root.resolve("another-world");
        store.ready(root,0,0); store.ready(other,0,0); settle();
        store.put(root,0,0,new XaeroPredictionStore.Stamp(1,quality)); settle();
        assertNull(store.get(other,0,0));
        for(int i=0;i<256;i++) store.ready(root,i*32,0);
        assertTrue(store.cachedRegionsForTest()<=32); settle();
        assertTrue(store.cachedRegionsForTest()<=32);
    }
}
