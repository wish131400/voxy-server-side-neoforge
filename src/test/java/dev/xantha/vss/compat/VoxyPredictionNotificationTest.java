package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.config.VSSClientConfig;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

/** Exercise notification -> event hook -> router, including the real snapshot/version maps. */
class VoxyPredictionNotificationTest {
    private Object context;
    private Engine engine;
    private final AtomicInteger reads = new AtomicInteger();
    private boolean enabled;
    private boolean faceLayout;
    private final long pos = VoxyPredictionBridge.position(2,0,0,0);

    @BeforeAll static void bootstrap() throws Exception {
        var method=Class.forName("dev.xantha.vss.client.prediction.ClientTerrainSamplerTest")
                .getDeclaredMethod("bootstrapMinecraft");
        method.setAccessible(true); method.invoke(null);
    }
    @BeforeEach void setup() throws Exception {
        faceLayout=VoxyFaceData.nativeLayoutVerified;VoxyFaceData.nativeLayoutVerified=true;
        VoxyPredictionBridge.patchSubdivisionRequests("voxy:lod/hierarchical/traversal_dev.comp","void addRequest(inout UnpackedNode node) {}");
        enabled=VSSClientConfig.CONFIG.enablePrediction; VSSClientConfig.CONFIG.enablePrediction=true;
        engine=new Engine();
        Class<?> type=Class.forName(VoxyPredictionBridge.class.getName()+"$Context");
        context=mock(type,CALLS_REAL_METHODS);
        set("engine",engine); set("nodes",engine); set("supported",true);
        set("dimension",net.minecraft.world.level.Level.OVERWORLD);
        set("requested",new VoxySectionVersions());
        set("pruner",new VoxySectionPruner());
        set("cachedSections",new LinkedHashMap<>());
        set("delivered",new ConcurrentHashMap<>());
        set("predictionOnly",ConcurrentHashMap.<Long>newKeySet());
        set("subdivisionWaiters",new VoxySubdivisionWaiters());
        set("dirty",ConcurrentHashMap.<Long>newKeySet()); set("scheduled",new AtomicBoolean());
        set("predictionEnabled",true); set("withdrawals",new ConcurrentLinkedQueue<Long>());
        set("source",(VoxyPredictionBridge.Source)(position,mapper)->{reads.incrementAndGet();return new long[32768];});
        set("acquireReal",Engine.class.getMethod("acquireIfExists",long.class));
        set("voxels",new VoxyRenderAccess.Sections(Section.class));
        set("children",Section.class.getMethod("getNonEmptyChildren"));
        set("create",Section.class.getMethod("create",int.class,int.class,int.class,int.class));
        set("acquire",Section.class.getMethod("acquire")); set("release",Section.class.getMethod("release"));
        set("setChildren",Section.class.getMethod("_unsafeSetNonEmptyChildren",byte.class));
        set("worldEvent",Engine.class.getMethod("worldEvent",Section.class,int.class,int.class));
        worlds().put(engine,context);
    }
    @AfterEach void cleanup() throws Exception {
        VoxyFaceData.nativeLayoutVerified=faceLayout;
        var clear=context.getClass().getDeclaredMethod("clearCached");clear.setAccessible(true);clear.invoke(context);
        worlds().remove(engine); VSSClientConfig.CONFIG.enablePrediction=enabled;
    }
    @SuppressWarnings("unchecked") private Map<Object,Object> worlds() throws Exception {
        Field f=VoxyPredictionBridge.class.getDeclaredField("WORLDS"); f.setAccessible(true); return (Map<Object,Object>)f.get(null);
    }
    private void set(String name,Object value) throws Exception {
        Field f=context.getClass().getDeclaredField(name); f.setAccessible(true); f.set(context,value);
    }
    @SuppressWarnings("unchecked") private Map<Long,AtomicLong> versions() throws Exception {
        Field f=context.getClass().getDeclaredField("requested");f.setAccessible(true);return (Map<Long,AtomicLong>)f.get(context);
    }
    @Test void rendererCloseReleasesMappingsAndANewRendererAttachesTheSameSource() throws Exception {
        var releases=new AtomicInteger();var leases=new AtomicInteger();
        var source=new VoxyPredictionBridge.Source() {
            public long[] section(long p,VoxyPredictionBridge.Mapper mapper){reads.incrementAndGet();return new long[32768];}
            public VoxyPredictionBridge.Retention retainSection(long bytes){leases.incrementAndGet();return leases::decrementAndGet;}
            public void releaseMapper(VoxyPredictionBridge.Mapper mapper){assertFalse(mapper.isOpen());releases.incrementAndGet();}
        };
        set("source",source);
        var snapshot=(Section)VoxyPredictionBridge.compose(engine,pos,null);snapshot.release();assertEquals(1,leases.get());
        var current=VoxyPredictionBridge.class.getDeclaredField("current");current.setAccessible(true);
        Object previous=current.get(null);var hookValues=new HashMap<Field,Boolean>();
        try {
            current.set(null,context);Object stopped=context;
            VoxyPredictionBridge.renderClosed(new Renderer(engine));
            assertNull(current.get(null));assertFalse(worlds().containsKey(engine));
            assertEquals(1,releases.get());assertEquals(0,leases.get());assertEquals(0,snapshot.references);
            assertFalse(((VoxyPredictionBridge.Mapper)stopped).isOpen());
            boolean originalEnabled=enabled;setup();enabled=originalEnabled;
            set("source",null);
            var level=mock(net.minecraft.client.multiplayer.ClientLevel.class);
            when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);set("level",level);
            current.set(null,context);
            for(String name:List.of("meshHook","neighborHook","eventHook","detailHook")) {
                var hook=VoxyPredictionBridge.class.getDeclaredField(name);hook.setAccessible(true);
                hookValues.put(hook,hook.getBoolean(null));hook.setBoolean(null,true);
            }
            assertTrue(VoxyPredictionBridge.attach(net.minecraft.world.level.Level.OVERWORLD,source));
            var fresh=(Section)VoxyPredictionBridge.compose(engine,pos,null);fresh.release();
            assertNotSame(snapshot,fresh);assertEquals(2,reads.get());assertEquals(1,leases.get());
            VoxyPredictionBridge.renderClosed(new Renderer(engine));
            assertEquals(2,releases.get());assertEquals(0,leases.get());assertEquals(0,fresh.references);
        } finally {
            current.set(null,previous);
            for(var entry:hookValues.entrySet())entry.getKey().setBoolean(null,entry.getValue());
        }
    }
    public static final class Renderer {
        public final Engine worldIn;
        Renderer(Engine engine){worldIn=engine;}
    }
    @Test void meshAcquisitionRecordsDemandEvenWithACachedSnapshotAndNoSubdivisionRequest() throws Exception {
        var requests=new ArrayList<Long>();
        set("source",new VoxyPredictionBridge.Source() {
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper) {return new long[32768];}
            public void requestMeshDetail(long position,VoxyPredictionBridge.RealColumn real) {requests.add(position);}
            public VoxyPredictionBridge.Retention retainSection(long bytes) {return ()->{};}
        });
        long bottom=VoxyPredictionBridge.position(0,64,1,0);
        var service=new MeshService(engine);
        ((Section)VoxyPredictionBridge.meshSection(service,bottom,null)).release();
        ((Section)VoxyPredictionBridge.meshSection(service,bottom,null)).release();
        assertEquals(List.of(bottom,bottom),requests,"Cached native leaves must renew evicted demand too");
        ((Section)VoxyPredictionBridge.compose(engine,bottom,null)).release();
        assertEquals(2,requests.size(),"Notification and border reads must not create mesh demand");
        VSSClientConfig.CONFIG.enablePrediction=false;
        assertNull(VoxyPredictionBridge.meshSection(service,bottom,null));
        assertEquals(2,requests.size());
        VoxyPredictionBridge.meshAttemptFinished();
    }
    public static final class MeshService {
        final Engine world;
        MeshService(Engine world) {this.world=world;}
    }
    @Test void publicationDuringDemandObservationCannotBeMistakenForAnAlreadyCheckedVersion() throws Exception {
        set("source",new VoxyPredictionBridge.Source() {
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper) {return new long[32768];}
            public void requestMeshDetail(long position,VoxyPredictionBridge.RealColumn real) {
                try {
                    assertNotNull(versions().get(position),"Register the change token before checking missing samples");
                    versions().get(position).incrementAndGet(); // Publication races the observation.
                } catch(Exception e) {throw new AssertionError(e);}
            }
        });
        try {
            ((Section)VoxyPredictionBridge.meshSection(new MeshService(engine),pos,null)).release();
            var field=VoxyPredictionBridge.class.getDeclaredField("ACQUIRED");field.setAccessible(true);
            Object stamp=((ThreadLocal<?>)field.get(null)).get();
            var version=stamp.getClass().getDeclaredMethod("version");version.setAccessible(true);
            assertNotEquals(versions().get(pos).get(),version.invoke(stamp),
                    "The actual build must recheck demand when publication changed after the first observation");
        } finally {VoxyPredictionBridge.meshAttemptFinished();}
    }
    @Test void syntheticSnapshotIsForwardedOnceWithoutChangingItsVersion() throws Exception {
        Router router=new Router(3);
        Section snapshot=(Section)VoxyPredictionBridge.compose(engine,pos,null);
        long version=versions().get(pos).get();
        VoxyPredictionBridge.forwardEvent(engine,router,snapshot,3);
        assertSame(snapshot,router.received,"An already-composed notification must keep its snapshot");
        assertEquals(1,reads.get()); assertEquals(version,versions().get(pos).get());
        assertEquals(0,snapshot.releases); snapshot.release();
    }
    @Test void waitingParentSuppressesGpuRetriesWithoutInventingANativeChildRequestAndPublicationWakesIt() throws Exception {
        var nodes=new Nodes(pos); set("refinementNodes",nodes);
        var ready=new AtomicBoolean(); var requests=new AtomicInteger();
        set("source",detailSource(ready,requests));
        var snapshot=(Section)VoxyPredictionBridge.compose(engine,pos,null);
        VoxyPredictionBridge.requestDetail(nodes,pos);
        assertFalse(VoxyPredictionBridge.allowSubdivision(nodes,3));
        assertFalse(nodes.nodeData.inFlight); assertEquals(1,nodes.invalidations);
        assertEquals(2,requests.get()); assertEquals(0,snapshot.releases);
        long address=org.lwjgl.system.MemoryUtil.nmemAlloc(16);
        try {
            writeNode(address,pos,0x10123456,0x34ffffff);
            VoxyPredictionBridge.writeSubdivisionState(nodes,address);
            assertEquals(0x12123456,org.lwjgl.system.MemoryUtil.memGetInt(address+8),
                    "Waiting uses its own bit and leaves the native request bit clear for cleaning");
            assertEquals(0x34ffffff,org.lwjgl.system.MemoryUtil.memGetInt(address+12));
            assertFalse(VoxyPredictionBridge.requestDetail(nodes,pos),"Readback duplicates must not spin a new request");
            assertFalse(nodes.nodeData.inFlight);
            ready.set(true);
            VoxyPredictionBridge.resumeSubdivision(nodes,pos);
            assertEquals(1,nodes.subdivisions);assertTrue(nodes.nodeData.inFlight);
            assertEquals(2,nodes.invalidations);
            writeNode(address,pos,0x10123456,0x34ffffff);
            VoxyPredictionBridge.writeSubdivisionState(nodes,address);
            assertEquals(0x10123456,org.lwjgl.system.MemoryUtil.memGetInt(address+8));
            VoxyPredictionBridge.resumeSubdivision(nodes,pos);
            assertEquals(1,nodes.subdivisions,"Repeated publication must not restart a completed wait");
        } finally { org.lwjgl.system.MemoryUtil.nmemFree(address); }
        snapshot.release();
    }
    @Test void emptyNonRootWaitsWithoutRepeatingSourceDemandAndChildPublicationResumesIt() throws Exception {
        var nodes=new Nodes(pos);set("refinementNodes",nodes);nodes.nodeData.children=0;
        var requests=new AtomicInteger();set("source",detailSource(new AtomicBoolean(true),requests));
        nodes.processRequest(pos);
        assertEquals(0,nodes.subdivisions);assertFalse(nodes.nodeData.inFlight);
        int observed=requests.get();
        for(int retry=0;retry<1000;retry++) assertFalse(VoxyPredictionBridge.requestDetail(nodes,pos));
        assertEquals(observed,requests.get(),"A known empty node must not repeatedly resubmit identical demand");
        VoxyPredictionBridge.resumeSubdivision(nodes,pos);assertEquals(0,nodes.subdivisions);
        nodes.nodeData.children=(byte)128;
        VoxyPredictionBridge.resumeSubdivision(nodes,pos);
        assertEquals(1,nodes.subdivisions);assertTrue(nodes.nodeData.inFlight);
        assertEquals(0,((VoxySubdivisionWaiters)get("subdivisionWaiters")).size());
    }
    @Test void emptyOccupancyCannotSuppressReadmissionOfMissingOrEvictedSamples() throws Exception {
        var nodes=new Nodes(pos);set("refinementNodes",nodes);nodes.nodeData.children=0;
        var ready=new AtomicBoolean();var requests=new AtomicInteger();set("source",detailSource(ready,requests));
        nodes.processRequest(pos);assertEquals(0,nodes.subdivisions);
        var waits=(VoxySubdivisionWaiters)get("subdivisionWaiters");assertTrue(waits.needsSamples(pos));
        int before=requests.get();
        assertFalse(VoxyPredictionBridge.requestDetail(nodes,pos));
        assertTrue(requests.get()>before,"Missing sample demand must be readmitted while native occupancy is empty");
        ready.set(true);assertFalse(VoxyPredictionBridge.requestDetail(nodes,pos));assertFalse(waits.needsSamples(pos));
        before=requests.get();
        for(int n=0;n<1000;n++)assertFalse(VoxyPredictionBridge.requestDetail(nodes,pos));
        assertEquals(before,requests.get());
        nodes.nodeData.children=1;VoxyPredictionBridge.resumeSubdivision(nodes,pos);
        assertEquals(1,nodes.subdivisions);assertEquals(0,waits.size());
    }
    @Test void emptyTopLevelExpansionIsAllowedAndDisabledPredictionReleasesEmptyWaits() throws Exception {
        var nodes=new Nodes(pos);set("refinementNodes",nodes);nodes.nodeData.children=0;
        nodes.topLevelNodes.add(pos);nodes.processRequest(pos);assertEquals(1,nodes.subdivisions);
        nodes.topLevelNodes.clear();nodes.processRequest(pos);assertEquals(1,nodes.subdivisions);
        VSSClientConfig.CONFIG.enablePrediction=false;
        assertTrue(VoxyPredictionBridge.requestDetail(nodes,pos));
        assertEquals(0,((VoxySubdivisionWaiters)get("subdivisionWaiters")).size());
        VSSClientConfig.CONFIG.enablePrediction=true;
        nodes.processRequest(pos);VoxyPredictionBridge.forgetSubdivision(nodes,pos);
        assertEquals(0,((VoxySubdivisionWaiters)get("subdivisionWaiters")).size());
    }
    private static void writeNode(long address,long pos,int mesh,int children) {
        org.lwjgl.system.MemoryUtil.memPutInt(address,(int)(pos>>>32));
        org.lwjgl.system.MemoryUtil.memPutInt(address+4,(int)pos);
        org.lwjgl.system.MemoryUtil.memPutInt(address+8,mesh);
        org.lwjgl.system.MemoryUtil.memPutInt(address+12,children);
    }
    @Test void fiftyUnavailableCoarseParentsCannotConsumeTheNextFramesRequestSlotsAgain() throws Exception {
        var nodes=new Nodes(pos);set("refinementNodes",nodes);
        var requests=new AtomicInteger();set("source",detailSource(new AtomicBoolean(),requests));
        @SuppressWarnings("unchecked") var predicted=(Set<Long>)get("predictionOnly");
        long[] addresses=new long[51];
        try {
            for(int i=0;i<addresses.length;i++) {
                long p=VoxyPredictionBridge.position(i<50?4:1,i,0,0);
                predicted.add(p);nodes.nodeData.position=p;nodes.nodeData.inFlight=true;
                addresses[i]=org.lwjgl.system.MemoryUtil.nmemAlloc(16);
                if(i<50)assertFalse(VoxyPredictionBridge.allowSubdivision(nodes,3));
                writeNode(addresses[i],p,123,0xffffff);
                VoxyPredictionBridge.writeSubdivisionState(nodes,addresses[i]);
            }
            int slots=50,nearRequests=0;
            for(int i=0;i<addresses.length&&slots>0;i++)
                if((org.lwjgl.system.MemoryUtil.memGetInt(addresses[i]+8)&3<<24)==0) {
                    slots--;if(i==50)nearRequests++;
                }
            assertEquals(1,nearRequests,"The following LOD1 request must get a slot after the first coarse batch waits");
            assertEquals(49,slots);
        } finally {for(long address:addresses)if(address!=0)org.lwjgl.system.MemoryUtil.nmemFree(address);}
    }
    @Test void unrelatedPublicationDoesNotWakeAWaitAndDisabledPredictionOrNativeDataReleasesIt() throws Exception {
        var nodes=new Nodes(pos);set("refinementNodes",nodes);
        set("source",detailSource(new AtomicBoolean(),new AtomicInteger()));
        var snapshot=(Section)VoxyPredictionBridge.compose(engine,pos,null);
        assertFalse(VoxyPredictionBridge.allowSubdivision(nodes,3));
        VoxyPredictionBridge.resumeSubdivision(nodes,VoxyPredictionBridge.position(2,100,0,0));
        assertEquals(0,nodes.subdivisions);
        VSSClientConfig.CONFIG.enablePrediction=false;
        VoxyPredictionBridge.resumeSubdivision(nodes,pos);
        assertEquals(1,nodes.subdivisions);
        VSSClientConfig.CONFIG.enablePrediction=true;nodes.nodeData.inFlight=true;
        assertFalse(VoxyPredictionBridge.allowSubdivision(nodes,3));
        @SuppressWarnings("unchecked") var predictionOnly=(Set<Long>)get("predictionOnly");predictionOnly.remove(pos);
        VoxyPredictionBridge.resumeSubdivision(nodes,pos);
        assertEquals(2,nodes.subdivisions,"Native takeover must not wait for synthetic detail");
        snapshot.release();
    }
    @Test void recycledNodeIdsAndWorldCloseCannotKeepAnOldGpuWaitFlag() throws Exception {
        var nodes=new Nodes(pos);set("refinementNodes",nodes);
        set("source",detailSource(new AtomicBoolean(),new AtomicInteger()));
        var snapshot=(Section)VoxyPredictionBridge.compose(engine,pos,null);
        assertFalse(VoxyPredictionBridge.allowSubdivision(nodes,3));
        long address=org.lwjgl.system.MemoryUtil.nmemAlloc(16);
        try {
            long other=VoxyPredictionBridge.position(1,-12,-1,7);
            writeNode(address,other,123,0xffffff);VoxyPredictionBridge.writeSubdivisionState(nodes,address);
            assertEquals(123,org.lwjgl.system.MemoryUtil.memGetInt(address+8));
            VoxyPredictionBridge.forgetSubdivision(nodes,pos);
            writeNode(address,pos,123,0xffffff);VoxyPredictionBridge.writeSubdivisionState(nodes,address);
            assertEquals(123,org.lwjgl.system.MemoryUtil.memGetInt(address+8));
            nodes.nodeData.inFlight=true;assertFalse(VoxyPredictionBridge.allowSubdivision(nodes,3));
            var close=context.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(context);
            VoxyPredictionBridge.writeSubdivisionState(nodes,address);
            assertEquals(123,org.lwjgl.system.MemoryUtil.memGetInt(address+8));
        } finally {org.lwjgl.system.MemoryUtil.nmemFree(address);snapshot.release();}
    }
    @Test void unknownTraversalLayoutKeepsOrdinaryRetriesWithoutInstallingAWaitFlag() throws Exception {
        var shader=VoxyPredictionBridge.class.getDeclaredField("subdivisionShaderReady");shader.setAccessible(true);
        boolean before=shader.getBoolean(null);shader.setBoolean(null,false);
        long address=org.lwjgl.system.MemoryUtil.nmemAlloc(16);
        Section snapshot=null;
        try {
            String unknown="void submitRequest(inout OtherNode node) {}";
            assertEquals(unknown,VoxyPredictionBridge.patchSubdivisionRequests("voxy:lod/hierarchical/traversal_dev.comp",unknown));
            assertFalse(shader.getBoolean(null));
            var nodes=new Nodes(pos);set("refinementNodes",nodes);
            set("source",detailSource(new AtomicBoolean(),new AtomicInteger()));
            snapshot=(Section)VoxyPredictionBridge.compose(engine,pos,null);
            assertFalse(VoxyPredictionBridge.allowSubdivision(nodes,3));
            assertFalse(nodes.nodeData.inFlight);assertEquals(1,nodes.invalidations);
            writeNode(address,pos,123,0xffffff);VoxyPredictionBridge.writeSubdivisionState(nodes,address);
            assertEquals(123,org.lwjgl.system.MemoryUtil.memGetInt(address+8));
            assertTrue(VoxyPredictionBridge.requestDetail(nodes,pos),"Unpatched traversal must retain native retries");
        } finally {
            shader.setBoolean(null,before);org.lwjgl.system.MemoryUtil.nmemFree(address);
            if(snapshot!=null)snapshot.release();
        }
    }
    @Test void mixedNativeGeometryAndDisabledPredictionNeverWaitForSyntheticDetail() throws Exception {
        var nodes=new Nodes(pos); set("refinementNodes",nodes);
        var requests=new AtomicInteger();set("source",detailSource(new AtomicBoolean(),requests));
        var real=new Section(pos);real.data[0]=2L<<27;
        var snapshot=(Section)VoxyPredictionBridge.compose(engine,pos,real);
        assertTrue(VoxyPredictionBridge.allowSubdivision(nodes,3));
        assertTrue(nodes.nodeData.inFlight); assertEquals(0,nodes.invalidations);
        VSSClientConfig.CONFIG.enablePrediction=false;
        int before=requests.get();
        assertTrue(VoxyPredictionBridge.allowSubdivision(nodes,3));
        VoxyPredictionBridge.requestDetail(nodes,pos);
        assertEquals(before,requests.get()); snapshot.release();
    }
    private static VoxyPredictionBridge.Source detailSource(AtomicBoolean ready,AtomicInteger requests) {
        return new VoxyPredictionBridge.Source() {
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper) {
                long[] data=new long[32768];Arrays.fill(data,1L<<27);return data;
            }
            public boolean requestDetail(long position,VoxyPredictionBridge.RealColumn real) {
                requests.incrementAndGet();return ready.get();
            }
        };
    }
    public static final class Nodes {
        final Store nodeData;
        final Set<Long> topLevelNodes = new HashSet<>();
        final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap activeSectionMap=new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        int invalidations,subdivisions;
        Nodes(long position){nodeData=new Store(position);activeSectionMap.defaultReturnValue(-1);activeSectionMap.put(position,3);}
        public void processRequest(long position) {
            if(!VoxyPredictionBridge.requestDetail(this,position))return;
            if(activeSectionMap.get(position)==-1)return;
            nodeData.inFlight=true;
            if(VoxyPredictionBridge.allowSubdivision(this,3))subdivisions++;
        }
        private void invalidateNode(int node){assertEquals(3,node);invalidations++;}
    }
    public static final class Store {
        long position;boolean inFlight=true;byte children=(byte)255;
        public byte getNodeChildExistence(int node) { return children; }
        Store(long position){this.position=position;}
        public long nodePosition(int node){assertEquals(3,node);return position;}
        public void unmarkRequestInFlight(int node){assertEquals(3,node);inFlight=false;}
    }
    @Test void unobservedRealEventsDoNotCreatePredictionSnapshotsOrRetainSectionVersions() throws Exception {
        Router router=new Router(0); Section real=new Section(pos);
        VoxyPredictionBridge.forwardEvent(engine,router,real,3);
        assertSame(real,router.received); assertEquals(0,reads.get()); assertTrue(versions().isEmpty());
        assertEquals(0,engine.acquires); assertEquals(0,real.releases);
    }
    @Test void observedRealEventsComposeAndAdvanceTheVersion() throws Exception {
        Router router=new Router(3); Section real=new Section(pos);
        versions().put(pos,new AtomicLong(-1));
        VoxyPredictionBridge.forwardEvent(engine,router,real,3);
        assertNotSame(real,router.received); assertEquals(1,reads.get());
        assertTrue(versions().get(pos).get()>-1); assertEquals(1,router.received.releases);
        assertEquals(0,real.releases,"The real event section is borrowed");
    }
    @Test void regionRefreshDoesNotBroadcastTheSameUpdateToSixNeighborsAgain() throws Exception {
        versions().put(pos,new AtomicLong(1));
        VoxyPredictionBridge.changed((VoxyPredictionBridge.Source)get("source"),-16,-16,48,48);
        var flush=context.getClass().getDeclaredMethod("flushUpdates");flush.setAccessible(true);flush.invoke(context);
        assertTrue(engine.event.await(5,TimeUnit.SECONDS));
        // worldEvent has synchronously run the event hook before completing the latch.
        assertEquals(0,engine.neighborMask); assertEquals(1,reads.get());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        AtomicBoolean scheduled=(AtomicBoolean)get("scheduled");
        while(scheduled.get()&&System.nanoTime()<deadline)Thread.sleep(1);
        assertFalse(scheduled.get());
    }
    @Test void finalComposedContentSkipsUnchangedUpdatesButKeepsFacesOccupancyDeletionAndRealTakeover() throws Exception {
        var data=new long[32768];data[0]=1L<<27;
        var faceValue=new AtomicLong();
        set("source",new VoxyPredictionBridge.Source() {
            public long[] section(long p,VoxyPredictionBridge.Mapper m){return data.clone();}
            public boolean compareNotificationFaces(){return true;}
            public boolean copyFace(long p,VoxyPredictionBridge.Mapper m,int face,long[] target) {
                Arrays.fill(target,faceValue.get());return true;
            }
        });
        versions().put(pos,new AtomicLong(1));
        publishNotification();assertEquals(1,engine.events.get());assertEquals(3,engine.flags);
        for(int i=0;i<5;i++)publishNotification();
        assertEquals(1,engine.events.get(),"Duplicate composed blocks, occupancy and neighbor faces must settle");
        assertEquals(0,((Map<?,?>)get("cachedSections")).size(),"One-shot notifications must not evict the mesh snapshot working set");
        data[0]=2L<<27;publishNotification();assertEquals(2,engine.events.get());assertEquals(1,engine.flags);
        faceValue.set(3L<<27);publishNotification();assertEquals(3,engine.events.get());assertEquals(1,engine.flags,"A neighbor-only border change must remesh the center");
        data[16]=1L<<27;publishNotification();assertEquals(4,engine.events.get());assertEquals(3,engine.flags,"A new octant must deliver child occupancy too");
        Arrays.fill(data,0);publishNotification();assertEquals(5,engine.events.get());assertEquals(3,engine.flags,"Removing all blocks must withdraw child occupancy");
        var real=new Section(pos);real.data[0]=7L<<27;engine.real=real;
        publishNotification();assertEquals(6,engine.events.get());assertEquals(7L<<27,engine.lastEvent.data[0]);
        publishNotification();assertEquals(6,engine.events.get());
        VoxyPredictionBridge.forwardEvent(engine,new Router(3),real,3);
        publishNotification();assertEquals(7,engine.events.get(),"An external real event revokes comparison evidence");
    }
    private void publishNotification() throws Exception {
        VoxyPredictionBridge.changed((VoxyPredictionBridge.Source)get("source"),-16,-16,48,48);
        flush();drainUpdates();
    }
    @Test void maskedPublicationsKeepCoveredVersionsAndStillInvalidateContributingSections() throws Exception {
        long adjacent=VoxyPredictionBridge.position(0,1,0,0);
        versions().put(pos,new AtomicLong(1));versions().put(adjacent,new AtomicLong(1));
        VoxyPredictionBridge.changed((VoxyPredictionBridge.Source)get("source"),-16,-16,-16,80,80,80,p->p==adjacent);
        assertEquals(1,versions().get(pos).get());assertTrue(versions().get(adjacent).get()>1);
        @SuppressWarnings("unchecked") var dirty=(Set<Long>)get("dirty");
        assertFalse(dirty.contains(pos));assertTrue(dirty.contains(adjacent));assertEquals(0,reads.get());
    }
    @Test void publicationsBeforeAFrameInvalidateImmediatelyAndDeliverOnlyTheLatestNotification() throws Exception {
        versions().put(pos,new AtomicLong(1));
        for(int update=0;update<40;update++)
            VoxyPredictionBridge.changed((VoxyPredictionBridge.Source)get("source"),-16,-16,48,48);
        assertEquals(0,reads.get(),"Publications must not eagerly reconstruct the same section repeatedly");
        assertTrue(versions().get(pos).get()>1);
        assertTrue(VoxyPredictionBridge.notificationsPending(net.minecraft.world.level.Level.OVERWORLD,0,0,1,1));
        var flush=context.getClass().getDeclaredMethod("flushUpdates");flush.setAccessible(true);flush.invoke(context);
        assertTrue(engine.event.await(5,TimeUnit.SECONDS));assertEquals(1,reads.get());
    }
    @Test void pendingSourceNotificationsRevokeOnlyIntersectingWorldAndFootprint() throws Exception {
        set("dimension",net.minecraft.world.level.Level.OVERWORLD);
        @SuppressWarnings("unchecked") var dirty=(Set<Long>)get("dirty");
        dirty.add(pos);
        assertTrue(VoxyPredictionBridge.notificationsPending(net.minecraft.world.level.Level.OVERWORLD,0,0,1,1));
        assertFalse(VoxyPredictionBridge.notificationsPending(net.minecraft.world.level.Level.OVERWORLD,100,100,101,101));
        assertFalse(VoxyPredictionBridge.notificationsPending(net.minecraft.world.level.Level.NETHER,0,0,1,1));
        set("notifying",pos); dirty.clear();
        assertTrue(VoxyPredictionBridge.notificationsPending(net.minecraft.world.level.Level.OVERWORLD,0,0,1,1),
                "taking the notification off the queue must not expose stale uploaded readiness");
        set("notifying",null);
        assertFalse(VoxyPredictionBridge.notificationsPending(net.minecraft.world.level.Level.OVERWORLD,0,0,1,1));
    }
    @Test void queueToWorkerHandoffCannotExposeAnEmptyNotificationWindow() throws Exception {
        set("dimension",net.minecraft.world.level.Level.OVERWORLD);
        Field inFlight=context.getClass().getDeclaredField("notifying"); inFlight.setAccessible(true);
        set("dirty",new AbstractSet<Long>() {
            public int size() { return 0; }
            public Iterator<Long> iterator() {
                try { inFlight.set(context,pos); }
                catch(IllegalAccessException failure) { throw new AssertionError(failure); }
                return Collections.emptyIterator();
            }
        });
        assertTrue(VoxyPredictionBridge.notificationsPending(net.minecraft.world.level.Level.OVERWORLD,0,0,1,1),
                "a worker taking the last queued notification must remain observable");
    }
    @Test void cachedNotificationAndMeshReadsShareOneImmutableSectionAndReleaseAllReferencesOnClose() throws Exception {
        var leases=new AtomicInteger();
        set("source",new VoxyPredictionBridge.Source(){
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper){reads.incrementAndGet();var data=new long[32768];Arrays.fill(data,1L<<27);return data;}
            public VoxyPredictionBridge.Retention retainSection(long bytes){assertEquals(262160L,bytes);leases.incrementAndGet();return leases::decrementAndGet;}
        });
        var first=(Section)VoxyPredictionBridge.compose(engine,pos,null);
        var real=new Section(pos);
        var second=(Section)VoxyPredictionBridge.compose(engine,pos,real);
        assertSame(first,second);assertEquals(1,reads.get());assertEquals(1,real.releases);assertEquals(1,leases.get());
        first.release();second.release();
        var close=context.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(context);
        assertEquals(0,leases.get());assertEquals(3,first.releases,"Two consumer references and one cache reference are released");
    }
    @Test void heightBoundedChangeKeepsGroundSnapshotAndRevokesOnlyIntersectingSections() throws Exception {
        var leases=new AtomicInteger();
        set("source",new VoxyPredictionBridge.Source(){
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper){reads.incrementAndGet();return new long[32768];}
            public VoxyPredictionBridge.Retention retainSection(long bytes){leases.incrementAndGet();return leases::decrementAndGet;}
        });
        long low=VoxyPredictionBridge.position(0,0,0,0), high=VoxyPredictionBridge.position(0,0,2,0);
        var ground=(Section)VoxyPredictionBridge.compose(engine,low,null);
        var plants=(Section)VoxyPredictionBridge.compose(engine,high,null);
        ((AtomicBoolean)get("scheduled")).set(true); // Keep notification queued so its exact dirty set is observable.
        VoxyPredictionBridge.changed((VoxyPredictionBridge.Source)get("source"),-16,60,-16,48,100,48);
        @SuppressWarnings("unchecked") var dirty=(Set<Long>)get("dirty");
        assertEquals(Set.of(high),dirty);assertEquals(1,leases.get());
        assertSame(ground,VoxyPredictionBridge.compose(engine,low,null));ground.release();
        var replacement=(Section)VoxyPredictionBridge.compose(engine,high,null);
        assertNotSame(plants,replacement);replacement.release();
        ground.release();plants.release();
    }
    @Test void realEventRevokesACachedNeighborEvenWhenTheNodeRouterDoesNotWatchIt() throws Exception {
        set("source",new VoxyPredictionBridge.Source(){
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper){reads.incrementAndGet();return new long[32768];}
            public VoxyPredictionBridge.Retention retainSection(long bytes){return ()->{};}
        });
        var old=(Section)VoxyPredictionBridge.compose(engine,pos,null);long version=versions().get(pos).get();
        var real=new Section(pos);var router=new Router(0);
        VoxyPredictionBridge.forwardEvent(engine,router,real,3);
        assertSame(real,router.received);assertEquals(0,real.releases);
        assertTrue(versions().get(pos).get()>version);
        var fresh=(Section)VoxyPredictionBridge.compose(engine,pos,null);
        assertNotSame(old,fresh);assertEquals(2,reads.get());old.release();fresh.release();
    }
    @Test void concurrentNativeReadsShareOneBuildAndEvictionKeepsBorrowedSnapshotsAlive() throws Exception {
        var leases=new AtomicInteger();var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        set("source",new VoxyPredictionBridge.Source(){
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper){
                if(reads.incrementAndGet()==1){entered.countDown();try{assertTrue(resume.await(5,TimeUnit.SECONDS));}
                    catch(InterruptedException e){throw new AssertionError(e);}}
                return new long[32768];
            }
            public VoxyPredictionBridge.Retention retainSection(long bytes){leases.incrementAndGet();return leases::decrementAndGet;}
        });
        var workers=Executors.newFixedThreadPool(2);
        try {
            var one=workers.submit(()->(Section)VoxyPredictionBridge.compose(engine,pos,null));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var two=workers.submit(()->(Section)VoxyPredictionBridge.compose(engine,pos,null));resume.countDown();
            var first=one.get(5,TimeUnit.SECONDS);var second=two.get(5,TimeUnit.SECONDS);
            assertSame(first,second);assertEquals(1,reads.get());assertEquals(3,first.references);
            var invalidate=context.getClass().getDeclaredMethod("invalidateCached",long.class);invalidate.setAccessible(true);invalidate.invoke(context,pos);
            assertEquals(2,first.references);assertEquals(0,leases.get());
            first.release();second.release();assertEquals(0,first.references);
        }finally{resume.countDown();workers.shutdownNow();}
    }
    @Test void snapshotCacheDoesNotWaitForTheWorldNotificationMonitor() throws Exception {
        var entered=new CountDownLatch(1);var workers=Executors.newSingleThreadExecutor();
        set("source",new VoxyPredictionBridge.Source(){
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper){entered.countDown();return new long[32768];}
            public VoxyPredictionBridge.Retention retainSection(long bytes){return ()->{};}
        });
        Future<Section> pending=null;
        try {
            synchronized(context) {
                pending=workers.submit(()->(Section)VoxyPredictionBridge.compose(engine,pos,null));
                assertTrue(entered.await(2,TimeUnit.SECONDS),"A section reader must not wait for the lifecycle monitor held during notification delivery");
                pending.get(5,TimeUnit.SECONDS).release();pending=null;
            }
        } finally {
            try {if(pending!=null)pending.get(5,TimeUnit.SECONDS).release();}
            finally {workers.shutdownNow();}
        }
    }
    @Test void levelUnloadCancelsAQueuedBatchWithoutRebuildingTheOldScene() throws Exception {
        var level=mock(net.minecraft.client.multiplayer.ClientLevel.class);
        set("level",level);holdWorldReference();
        var source=(VoxyPredictionBridge.Source)get("source");
        for(int x=0;x<150;x++)versions().put(VoxyPredictionBridge.position(0,x,0,0),new AtomicLong(1));
        var worker=updates();var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        worker.execute(()->{entered.countDown();awaitLatch(resume);});
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            VoxyPredictionBridge.changed(source,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
            flush();
            assertTrue(((AtomicBoolean)get("scheduled")).get());
            VoxyPredictionBridge.levelUnloaded(level);
            VoxyPredictionBridge.detach(source);
            assertTrue(((Set<?>)get("dirty")).isEmpty());
            assertTrue(versions().isEmpty());assertFalse(worlds().containsKey(engine));
            assertFalse(VoxyPredictionBridge.isLevelActive(level));
            assertTrue(VoxyPredictionBridge.isLevelActive(mock(net.minecraft.client.multiplayer.ClientLevel.class)));
            assertEquals(0,engine.worldReleases,"The queued worker still owns the world reference");
        } finally {resume.countDown();drainUpdates();}
        assertEquals(0,engine.events.get());assertEquals(0,reads.get());
        assertEquals(1,engine.worldReleases);assertFalse((boolean)get("worldHeld"));
        VoxyPredictionBridge.renderClosed(new Renderer(engine));
        assertEquals(1,engine.worldReleases,"Later renderer shutdown cannot release the reference twice");
    }

    @Test void levelUnloadDuringCompositionStopsDeliveryAndReleasesTheBorrowedSection() throws Exception {
        var level=mock(net.minecraft.client.multiplayer.ClientLevel.class);set("level",level);holdWorldReference();
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var real=new Section(pos);engine.real=real;
        var source=new VoxyPredictionBridge.Source() {
            public long[] section(long position,VoxyPredictionBridge.Mapper mapper) {
                entered.countDown();awaitLatch(resume);return new long[32768];
            }
        };
        set("source",source);versions().put(pos,new AtomicLong(1));
        VoxyPredictionBridge.changed(source,-16,-16,48,48);flush();
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            VoxyPredictionBridge.levelUnloaded(level);
            VoxyPredictionBridge.detach(source);
            assertEquals(0,engine.worldReleases);
        } finally {resume.countDown();drainUpdates();}
        assertEquals(0,engine.events.get());assertEquals(1,real.references,"engine retains its own reference");
        assertEquals(1,engine.worldReleases);assertFalse(((VoxyPredictionBridge.Mapper)context).isOpen());
    }

    @Test void sourceRemovalRestoresNativeDataInFrameBatchesWithoutAnAutonomousDrainLoop() throws Exception {
        for(int x=0;x<150;x++)versions().put(VoxyPredictionBridge.position(0,x,0,0),new AtomicLong(1));
        VoxyPredictionBridge.detach((VoxyPredictionBridge.Source)get("source"));
        drainUpdates();assertEquals(0,engine.events.get(),"Detach only queues restoration until a frame can consume it");
        flush();drainUpdates();assertEquals(64,engine.events.get());
        assertEquals(86,((Set<?>)get("dirty")).size());assertFalse(((AtomicBoolean)get("scheduled")).get());
        drainUpdates();assertEquals(64,engine.events.get(),"A completed batch must wait for the next frame");
        flush();drainUpdates();assertEquals(128,engine.events.get());
        flush();drainUpdates();assertEquals(150,engine.events.get());
        assertEquals(0,reads.get());assertTrue(((Set<?>)get("dirty")).isEmpty());
    }

    @Test void sourceRemovalPublishesTheAuthoritativeSectionWithoutOverlay() throws Exception {
        versions().put(pos,new AtomicLong(1));var real=new Section(pos);real.data[0]=17L<<27;engine.real=real;
        VoxyPredictionBridge.detach((VoxyPredictionBridge.Source)get("source"));flush();drainUpdates();
        assertSame(real,engine.lastEvent);assertEquals(17L<<27,engine.lastEvent.data[0]);
        assertEquals(0,reads.get());assertEquals(1,real.references,"engine retains its own reference");
    }

    @Test void activePredictionPublicationsAlsoUseBoundedBatchesAndEventuallyDeliverEverySection() throws Exception {
        for(int x=0;x<150;x++)versions().put(VoxyPredictionBridge.position(0,x,0,0),new AtomicLong(1));
        VoxyPredictionBridge.changed((VoxyPredictionBridge.Source)get("source"),Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
        flush();drainUpdates();assertEquals(64,engine.events.get());
        assertEquals(86,((Set<?>)get("dirty")).size());
        flush();drainUpdates();assertEquals(128,engine.events.get());
        flush();drainUpdates();assertEquals(150,engine.events.get());
        assertTrue(((Set<?>)get("dirty")).isEmpty());
    }

    @Test void continuousPublicationsCannotStarveSectionsBehindTheFirstBatch() throws Exception {
        set("dirty",new ConcurrentSkipListSet<Long>());
        for(int x=0;x<150;x++)versions().put(VoxyPredictionBridge.position(0,x,0,0),new AtomicLong(1));
        for(int frame=0;frame<3;frame++) {
            VoxyPredictionBridge.changed((VoxyPredictionBridge.Source)get("source"),Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
            flush();drainUpdates();
        }
        assertEquals(150,engine.delivered.size(),"New publications cannot repeatedly crowd out the tail of the queue");
    }


    @Test void disablingPredictionWithdrawsNativeEmptyMeshesWithoutBakingAndReenableReusesSource() throws Exception {
        var releases=new AtomicInteger();nonemptySource(releases);
        var original=(VoxyPredictionBridge.Source)get("source");
        var snapshot=(Section)VoxyPredictionBridge.compose(engine,pos,null);snapshot.release();
        long before=versions().get(pos).get();
        VSSClientConfig.CONFIG.enablePrediction=false;
        VoxyPredictionBridge.updatePredictionState();drainUpdates();
        assertEquals(List.of(pos),engine.withdrawn);
        assertSame(original,get("source"),"resident samples and reduced columns survive display off");
        assertTrue(versions().get(pos).get()>before);
        assertEquals(1,reads.get(),"withdrawal must not ask prediction to remesh");
        assertTrue(((VoxyPredictionBridge.Mapper)context).isOpen());assertEquals(0,releases.get());
        assertTrue((boolean)get("supported"));assertTrue(((Set<?>)get("dirty")).isEmpty());
        VoxyPredictionBridge.updatePredictionState();drainUpdates();
        assertEquals(1,engine.withdrawn.size(),"steady disabled ticks must not clear the scene repeatedly");
        VSSClientConfig.CONFIG.enablePrediction=true;
        VoxyPredictionBridge.updatePredictionState();drainUpdates();
        assertSame(original,get("source"));assertTrue(reads.get()>1);
        assertEquals(1,engine.withdrawn.size());
    }

    @Test void newlyIngestedNativeBlocksCannotBeWithdrawnUsingAnOldPredictionOnlyFlag() throws Exception {
        nonemptySource(new AtomicInteger());
        ((Section)VoxyPredictionBridge.compose(engine,pos,null)).release();
        var real=new Section(pos);real.data[0]=3L<<27;engine.real=real;
        VSSClientConfig.CONFIG.enablePrediction=false;
        VoxyPredictionBridge.updatePredictionState();drainUpdates();
        assertTrue(engine.withdrawn.isEmpty());
        assertSame(real,engine.lastEvent,"native data continues through the ordinary update path");
        assertEquals(1,real.references);assertTrue((boolean)get("supported"));
    }

    @Test void aMeshBuiltBeforeDisableCannotReachTheNativeUploadConsumer() throws Exception {
        var type=Class.forName(VoxyPredictionBridge.class.getName()+"$BuildStamp");
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        versions().put(pos,new AtomicLong(7));
        var stamp=constructor.newInstance(context,pos,7L);
        var field=VoxyPredictionBridge.class.getDeclaredField("BUILDS");field.setAccessible(true);
        var mesh=new Mesh();((Map<Object,Object>)field.get(null)).put(mesh,stamp);
        VSSClientConfig.CONFIG.enablePrediction=false;
        var service=new Service();var delivered=new AtomicInteger();
        VoxyPredictionBridge.meshConsumer(service,m->delivered.incrementAndGet()).accept(mesh);
        assertEquals(0,delivered.get());assertEquals(1,mesh.frees);assertEquals(1,service.requests);
    }
    public static final class Mesh { int frees; public void free(){frees++;} }
    public static final class Service { int requests; public void enqueueTask(long position){requests++;} }
    public static final class EmptyMesh {
        final long position; EmptyMesh(long p){position=p;}
        public static EmptyMesh empty(long position){return new EmptyMesh(position);}
    }

    private void nonemptySource(AtomicInteger releases) throws Exception {
        set("source",new VoxyPredictionBridge.Source() {
            public long[] section(long p,VoxyPredictionBridge.Mapper mapper) {
                reads.incrementAndGet();var data=new long[32768];Arrays.fill(data,1L<<27);return data;
            }
            public void releaseMapper(VoxyPredictionBridge.Mapper mapper){releases.incrementAndGet();}
        });
    }

    @Test void nativeChildrenWithoutBlocksAlsoPreventPredictionWithdrawal() throws Exception {
        nonemptySource(new AtomicInteger());
        ((Section)VoxyPredictionBridge.compose(engine,pos,null)).release();
        var real=new Section(pos);real.children=1;engine.real=real;
        VSSClientConfig.CONFIG.enablePrediction=false;VoxyPredictionBridge.updatePredictionState();drainUpdates();
        assertTrue(engine.withdrawn.isEmpty());assertSame(real,engine.lastEvent);
        assertEquals(1,real.references);assertTrue((boolean)get("supported"));
    }

    @Test void displayWithdrawalDoesNotWaitBehindTheSixtyFourRemeshBudget() throws Exception {
        nonemptySource(new AtomicInteger());
        for(int x=0;x<150;x++)((Section)VoxyPredictionBridge.compose(engine,VoxyPredictionBridge.position(2,x,0,0),null)).release();
        VSSClientConfig.CONFIG.enablePrediction=false;VoxyPredictionBridge.updatePredictionState();drainUpdates();
        assertEquals(150,engine.withdrawn.size());assertEquals(150,engine.events.get());
        assertEquals(150,reads.get(),"removal never recomputes prediction samples");
        assertTrue(((Set<?>)get("dirty")).isEmpty());assertTrue((boolean)get("supported"));
    }

    @Test void anOlderResidentMeshIsWithdrawnEvenAfterItsLastCompositionBecameEmpty() throws Exception {
        nonemptySource(new AtomicInteger());
        ((Section)VoxyPredictionBridge.compose(engine,pos,null)).release();
        set("source",(VoxyPredictionBridge.Source)(p,m)->null);
        assertNull(VoxyPredictionBridge.compose(engine,pos,null));
        assertTrue(((Set<?>)get("predictionOnly")).isEmpty());
        VSSClientConfig.CONFIG.enablePrediction=false;VoxyPredictionBridge.updatePredictionState();drainUpdates();
        assertEquals(List.of(pos),engine.withdrawn);assertTrue((boolean)get("supported"));
    }

    @Test void detachedSourceCannotLeaveAnUndrainableWithdrawalQueueOnReenable() throws Exception {
        var queue=(Queue<Long>)get("withdrawals");queue.add(pos);
        set("predictionEnabled",false);set("source",null);
        VoxyPredictionBridge.updatePredictionState();
        assertTrue(queue.isEmpty());assertFalse(((AtomicBoolean)get("scheduled")).get());
    }

    @Test void quickReenableCancelsQueuedWithdrawalAndPublishesWarmSamples() throws Exception {
        nonemptySource(new AtomicInteger());
        ((Section)VoxyPredictionBridge.compose(engine,pos,null)).release();
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        updates().execute(()->{entered.countDown();awaitLatch(resume);});
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            VSSClientConfig.CONFIG.enablePrediction=false;VoxyPredictionBridge.updatePredictionState();
            VSSClientConfig.CONFIG.enablePrediction=true;VoxyPredictionBridge.updatePredictionState();
        } finally {resume.countDown();drainUpdates();}
        assertTrue(engine.withdrawn.isEmpty());assertEquals(2,reads.get());
        assertTrue((boolean)get("supported"));
    }

    private void holdWorldReference() throws Exception {
        set("worldHeld",true);set("refRelease",Engine.class.getMethod("releaseRef"));
    }
    private static ExecutorService updates() throws Exception {
        var field=VoxyPredictionBridge.class.getDeclaredField("UPDATES");field.setAccessible(true);
        return (ExecutorService)field.get(null);
    }
    private static void drainUpdates() throws Exception {updates().submit(()->{}).get(5,TimeUnit.SECONDS);}
    private void flush() throws Exception {
        var method=context.getClass().getDeclaredMethod("flushUpdates");method.setAccessible(true);method.invoke(context);
    }
    private static void awaitLatch(CountDownLatch latch) {
        try {assertTrue(latch.await(5,TimeUnit.SECONDS));}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
    }

    private Object get(String name) throws Exception {
        Field f=context.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(context);
    }
    public static final class FaceFactory {
        final Engine world;final long[] neighboringFaces=new long[6144];
        FaceFactory(Engine world) {this.world=world;Arrays.fill(neighboringFaces,-1);}
    }
    @Test void coldNeighborFacesSkipSectionCompositionAndOnlyTouchRequestedPlanes() throws Exception {
        var calls=new AtomicInteger();
        set("source",new VoxyPredictionBridge.Source() {
            public long[] section(long p,VoxyPredictionBridge.Mapper m) {fail("Face read must not compose a full block");return null;}
            public long[] face(long p,VoxyPredictionBridge.Mapper m,int face) {
                calls.incrementAndGet();long[] data=new long[1024];for(int i=0;i<1024;i++)data[i]=(p^face^i)<<27;return data;
            }
        });
        var factory=new FaceFactory(engine);var center=new Section(pos);
        assertTrue(VoxyPredictionBridge.copyNeighborFaces(factory,center,1|8|32));assertEquals(3,calls.get());
        assertEquals(3,engine.acquires);assertEquals(0,reads.get());assertEquals(3,versions().size());
        for(int direction=0;direction<6;direction++) {
            long neighbor=VoxyPredictionBridge.position(2,direction==0?-1:direction==1?1:0,
                    direction==2?-1:direction==3?1:0,direction==4?-1:direction==5?1:0);
            for(int i=0;i<1024;i++)assertEquals((direction==0||direction==3||direction==5)?(neighbor^(direction^1)^i)<<27:-1,
                    factory.neighboringFaces[direction*1024+i]);
        }
    }
    @Test void cachedNeighborSnapshotAvoidsSourceAndNativeAcquisitionAndUnknownLayoutsUseFallback() throws Exception {
        set("source",new VoxyPredictionBridge.Source() {
            public long[] section(long p,VoxyPredictionBridge.Mapper m) {reads.incrementAndGet();var data=new long[32768];Arrays.fill(data,5L<<27);return data;}
            public VoxyPredictionBridge.Retention retainSection(long bytes) {return ()->{};}
        });
        long neighbor=VoxyPredictionBridge.position(2,-1,0,0);
        ((Section)VoxyPredictionBridge.compose(engine,neighbor,null)).release();
        var factory=new FaceFactory(engine);assertTrue(VoxyPredictionBridge.copyNeighborFaces(factory,new Section(pos),1));
        assertEquals(1,reads.get());assertEquals(0,engine.acquires);
        for(int i=0;i<1024;i++)assertEquals(5L<<27,factory.neighboringFaces[i]);
        VoxyFaceData.nativeLayoutVerified=false;
        assertFalse(VoxyPredictionBridge.copyNeighborFaces(factory,new Section(pos),1));
    }
    public static final class Engine {
        final List<Long> withdrawn=new CopyOnWriteArrayList<>();
        private void submitGeometryResult(EmptyMesh mesh) { withdrawn.add(mesh.position); }
        int acquires; volatile int neighborMask=-1,flags; volatile int worldReleases;
        final AtomicInteger events=new AtomicInteger(); final Set<Long> delivered=ConcurrentHashMap.newKeySet(); volatile Section real,lastEvent;
        public void releaseRef() { worldReleases++; }
        final CountDownLatch event=new CountDownLatch(1);
        public Section acquireIfExists(long position) { acquires++;var section=real;if(section!=null)section.acquire();return section; }
        public void worldEvent(Section section,int flags,int neighbors) {
            this.flags=flags;
            events.incrementAndGet();delivered.add(section.key);lastEvent=section;neighborMask=neighbors;
            try { VoxyPredictionBridge.forwardEvent(this,new Router(3),section,flags); }
            finally { event.countDown(); }
        }
    }
    public static final class Router {
        final int watched; Section received;
        Router(int watched) { this.watched=watched; }
        public int get(long position) { return watched; }
        public void forwardEvent(Section section,int flags) { received=section; }
    }
    public static final class Section {
        public final long key; final long[] data=new long[32768]; int releases, references=1;
        Section(long key) { this.key=key; }
        public static Section create(int lod,int x,int y,int z) { var section=new Section(VoxyPredictionBridge.position(lod,x,y,z));section.references=0;return section; }
        public long[] _unsafeGetRawDataArray() { return data; }
        byte children;
        public byte getNonEmptyChildren() { return children; }
        public void _unsafeSetNonEmptyChildren(byte children) { this.children=children; }
        public synchronized void acquire() { references++; }
        public synchronized void release() { releases++;assertTrue(--references>=0,"Section reference released twice"); }
    }
}
