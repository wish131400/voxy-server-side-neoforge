package dev.xantha.vss.client.prediction;

import java.nio.*;
import java.util.ArrayList;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL43C.*;

/** Ordered page batches: never regroup transparent draws across other pages or fallback tiles. */
final class PredictionIndirectBatch implements AutoCloseable {
    // Voxy keeps one large GPU command stream. Prediction still has to split
    // when a terrain arena page changes, but the old 512-tile/2560-command
    // ceiling caused avoidable flushes inside each page on large horizons.
    // Keep the buffers bounded while matching the size of a normal arena page.
    private static final int LIMIT=2048, COMMAND_LIMIT=LIMIT*8;
    private int tiles,commands;
    private final ByteBuffer records=MemoryUtil.memAlloc(LIMIT*64);
    private final ByteBuffer draws=MemoryUtil.memAlloc(COMMAND_LIMIT*20);
    private final ByteBuffer bounds=MemoryUtil.memAlloc(COMMAND_LIMIT*32);
    private final PredictionOcclusionCuller occlusion = new PredictionOcclusionCuller();
    private boolean culling;
    /** Dynamic batch uniforms are bound once for the camera/time of this batch. */
    private boolean cameraBound;
    private final ArrayList<BatchSlot> opaqueSlots = new ArrayList<>();
    private final ArrayList<BatchSlot> culledSlots = new ArrayList<>();
    private final ArrayList<BatchSlot> waterSlots = new ArrayList<>();
    private final ArrayList<PendingBatch> pending = new ArrayList<>();
    private int pendingCount, barriers, lastBarriers;
    private final PredictionOcclusionPolicy policy = new PredictionOcclusionPolicy();
    private final GpuSample[] gpuSamples = {new GpuSample(), new GpuSample(), new GpuSample(), new GpuSample()};
    private GpuSample activeGpuSample;
    private ArrayList<BatchSlot> slots = opaqueSlots;
    private int slotIndex;
    private PredictionTerrainArena.Page page;
    private PredictionTerrainProgram program;
    private int oldStorage,oldIndirect;
    private int oldIndexed;
    private long oldStart, oldSize;
    private boolean opaque;
    private long calls;
    private long submittedTiles;
    private long reusedBatches, uploadedBatches, uploadedBytes;
    private long flushes, lastOpaqueFlushes;
    private final long[] queuedQuads = new long[5], lastOpaqueQueued = new long[5];
    private long lastOpaqueReused, lastOpaqueUploaded, lastOpaqueBytes;
    /**
     * Render-thread-only static submission plans.  The plan contains the exact
     * page/order/range sequence used by the previous frame; records, indirect
     * commands and meshlet bounds can therefore stay resident while camera,
     * time and the current-frame occlusion result continue to change.
     */
    private final PlanCache opaquePlan = new PlanCache();
    private final PlanCache culledPlan = new PlanCache();
    private final PlanCache waterPlan = new PlanCache();
    private PlanCache plan;
    private boolean replaying;
    private boolean rebuilding;
    private boolean fallbackInFrame;
    private int replayBatchIndex, replayItemIndex;
    private PlanBatch currentPlanBatch;
    private long frameResidencyRevision, frameOwnershipRevision;
    private long planHits, planMisses, planReplayBatches;

    void begin(PredictionTerrainProgram program) {
        begin(program, true, 0L, 0L);
    }
    void begin(PredictionTerrainProgram program, boolean opaque) {
        begin(program, opaque, 0L, 0L);
    }
    void begin(PredictionTerrainProgram program, boolean opaque,
               long residencyRevision, long ownershipRevision) {
        beginFrame(program, opaque, residencyRevision, ownershipRevision);
        beginPlan(program, opaque, residencyRevision, ownershipRevision);
    }
    private void beginFrame(PredictionTerrainProgram program, boolean opaque,
                            long residencyRevision, long ownershipRevision) {
        this.program=program;calls=0;submittedTiles=0;tiles=commands=0;page=null;records.clear();draws.clear();bounds.clear();
        culling=false;
        pendingCount = barriers = 0;
        cameraBound=false;
        reusedBatches=uploadedBatches=uploadedBytes=flushes=0;
        Arrays.fill(queuedQuads, 0L);
        this.opaque=opaque;
        slots = opaque ? opaqueSlots : waterSlots;
        slotIndex = 0;
        plan = opaque ? opaquePlan : waterPlan;
        frameResidencyRevision = residencyRevision;
        frameOwnershipRevision = ownershipRevision;
        replayBatchIndex = replayItemIndex = 0;
        currentPlanBatch = null;
        fallbackInFrame = false;
        replaying = false;
        rebuilding = false;
        oldStorage=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);oldIndirect=glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        oldIndexed = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 7);
        oldStart = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 7);
        oldSize = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 7);
        program.batch(false);
    }
    void begin(PredictionTerrainProgram program, int depth, int width, int height, org.joml.Matrix4f transform) {
        begin(program, depth, width, height, transform, null, 0L, 0L);
    }
    void begin(PredictionTerrainProgram program, int depth, int width, int height,
               org.joml.Matrix4f transform, net.minecraft.world.phys.Vec3 camera) {
        begin(program, depth, width, height, transform, camera, 0L, 0L);
    }
    void begin(PredictionTerrainProgram program, int depth, int width, int height,
               org.joml.Matrix4f transform, net.minecraft.world.phys.Vec3 camera,
               long residencyRevision, long ownershipRevision) {
        beginFrame(program, true, residencyRevision, ownershipRevision);
        boolean adaptive = camera != null && !Boolean.getBoolean("vss.forcePredictionOcclusion")
                && !Boolean.getBoolean("vss.disablePredictionOcclusion") && depth > 0
                && org.lwjgl.opengl.GL.getCapabilities().OpenGL45;
        pollGpuSamples();
        boolean selectCulling = !adaptive || policy.choose(transform, camera.x, camera.y, camera.z,
                width, height, residencyRevision, ownershipRevision);
        culling=selectCulling && occlusion.begin(depth,width,height,transform);
        if (!selectCulling) occlusion.skippedFrame();
        if (culling) { slots = culledSlots; plan = culledPlan; }
        if (adaptive && policy.sample()) for (GpuSample sample : gpuSamples) if (!sample.pending) {
            if (sample.start == 0) { sample.start = glGenQueries(); sample.end = glGenQueries(); }
            sample.epoch = policy.epoch(); sample.culling = culling;
            glQueryCounter(sample.start, GL_TIMESTAMP);
            activeGpuSample = sample;
            break;
        }
        if (culling && camera != null) occlusion.setCameraOffset((float)-camera.x, (float)-camera.y, (float)-camera.z);
        // Meshlet segmentation depends on the depth probe, so select the
        // replay token after culling has been initialized.
        beginPlan(program, true, residencyRevision, ownershipRevision);
    }
    private void beginPlan(PredictionTerrainProgram nextProgram, boolean nextOpaque,
                           long residencyRevision, long ownershipRevision) {
        long materialRevision = VssLodSpriteTable.materialRevision();
        if (nextOpaque) {
            PlanCache inactive = plan == culledPlan ? opaquePlan : culledPlan;
            ArrayList<BatchSlot> inactiveSlots = plan == culledPlan ? opaqueSlots : culledSlots;
            if (inactive.valid && !inactive.matches(nextProgram, true, !culling, residencyRevision,
                    ownershipRevision, materialRevision)) {
                inactive.clear();
                for (BatchSlot slot : inactiveSlots) slot.close();
                inactiveSlots.clear();
            }
        }
        boolean hit = plan.matches(nextProgram, nextOpaque, culling, residencyRevision,
                ownershipRevision, materialRevision);
        if (hit) {
            replaying = true;
            planHits++;
        } else {
            replaying = false;
            if (plan.valid) planMisses++;
            plan.clear();
            plan.setToken(nextProgram, nextOpaque, culling, residencyRevision,
                    ownershipRevision, materialRevision);
        }
        plan.valid = false;
        rebuilding = !replaying;
    }
    /** Complete the nearer bucket before deriving proof for farther terrain from this frame. */
    void nextOcclusionBucket() {
        if (!culling) return;
        flush();
        occlusion.rebuild();
    }
    boolean add(PredictionRenderer.Draw draw,PredictionDrawRanges ranges,net.minecraft.world.phys.Vec3 camera,
                boolean water,boolean average,long now) {
        var slice=draw.gpu().arenaSlice(water);
        if(slice==null){
            flush();
            invalidateForFallback();
            return false;
        }
        if (!cameraBound) {
            // Records and bounds are deliberately world-stable. Bind the
            // frame-local values once so direct batch callers and the
            // production renderer use the same coordinate convention.
            program.setBatchCameraOffset((float)-camera.x, (float)-camera.y, (float)-camera.z);
            program.setBatchTimeSeconds(PredictionMorph.seconds(now));
            if (culling) occlusion.setCameraOffset((float)-camera.x, (float)-camera.y, (float)-camera.z);
            cameraBound = true;
        }
        boolean segmented=culling&&!water&&!Boolean.getBoolean("vss.disablePredictionMeshlets");
        int needed=segmented?PredictionMeshletBounds.count(ranges):ranges.first.length;
        // An unusually large single tile falls back to conservative whole-tile commands.
        if(needed>COMMAND_LIMIT) { segmented=false; needed=ranges.first.length; }
        if(needed>COMMAND_LIMIT) {
            flush();
            invalidateForFallback();
            return false;
        }
        if(page!=slice.page || tiles==LIMIT || commands+needed>COMMAND_LIMIT)stage();
        page=slice.page;
        PlanBatch expectedBatch = replaying && replayBatchIndex < plan.batches.size()
                ? plan.batches.get(replayBatchIndex) : null;
        PlanItem expected = expectedBatch != null && replayItemIndex < expectedBatch.items.size()
                ? expectedBatch.items.get(replayItemIndex) : null;
        if (replaying && (expected == null || expectedBatch.page != page
                || !expected.matches(draw, ranges, slice, water, average, segmented, needed))) {
            switchToRebuildCurrent();
        }
        if (replaying) {
            // The current frame owns the same static records.  Only counters,
            // distance telemetry and the dynamic filter are updated here.
            replayItemIndex++;
            commands += needed;
            tiles++;
            submittedTiles++;
        } else {
            encodeItem(draw, ranges, slice, water, average, segmented, tiles);
            recordPlanItem(draw, ranges, slice, water, average, segmented, needed);
            commands += needed;
            tiles++;
            submittedTiles++;
        }
        int bucket = distanceBucket(draw, camera);
        queuedQuads[bucket] += ranges.quads;
        return true;
    }
    private void encodeItem(PredictionRenderer.Draw draw, PredictionDrawRanges ranges,
                            PredictionTerrainArena.Slice slice, boolean water,
                            boolean average, boolean segmented, int recordIndex) {
        var packed=draw.gpu().packed();
        // Keep the record in world space.  The batch shader applies the one
        // frame-wide camera offset, so moving the camera does not dirty every
        // metadata slot or every HZB bound.
        records.putFloat((float)draw.tile().baseBlockX()).putFloat(0.0F)
                .putFloat((float)draw.tile().baseBlockZ()).putFloat(draw.tile().spacingBlocks());
        records.putInt(packed.cellAxis()).putInt(average?1:0).putInt(slice.offset/16).putInt(slice.maskOffset/4);
        float morphScale = !water && !draw.seam() && packed.morph() != null ? draw.morph() : 0.0F;
        records.putFloat(packed.morphBaseTexel()).putFloat(morphScale)
                .putFloat(packed.morphMinY()).putFloat(packed.morphMaxY());
        records.putInt(!water&&!draw.seam()?1:0).putInt(water ? packed.waterPaletteBaseTexel() : draw.gpu().paletteBaseTexel())
                // flags.z/w are intentionally unused by the fragment stage.
                // Store the morph clock there so the static record can be
                // evaluated against BatchTimeSeconds in the vertex shader.
                .putInt(Float.floatToRawIntBits(!water && !draw.seam() && packed.morph() != null
                        ? draw.gpu().morphStartSeconds() : 0.0F))
                .putInt(Float.floatToRawIntBits(morphScale));
        for(int i=0;i<ranges.first.length;i++) {
            int start = ranges.first[i];
            int end=start+ranges.count[i];
            for(int first=start;first<end;) {
                int next=segmented?PredictionMeshletBounds.end(first,end):end;
                int gpuFirst = packed.gpuFirst(first, next - first, water);
                draws.putInt((next-first)*6).putInt(1).putInt(gpuFirst*6).putInt(0).putInt(recordIndex);
                if(culling) putBounds(draw,packed,morphScale,segmented?first/PredictionMeshletBounds.QUADS:-1);
                first=next;
            }
        }
    }
    private void recordPlanItem(PredictionRenderer.Draw draw, PredictionDrawRanges ranges,
                                PredictionTerrainArena.Slice slice, boolean water,
                                boolean average, boolean segmented, int needed) {
        if (currentPlanBatch == null) {
            currentPlanBatch = new PlanBatch();
            currentPlanBatch.page = slice.page;
            plan.batches.add(currentPlanBatch);
        }
        currentPlanBatch.items.add(new PlanItem(draw, ranges, slice, water, average, segmented, needed));
        currentPlanBatch.tiles++;
        currentPlanBatch.commands += needed;
    }
    private void putBounds(PredictionRenderer.Draw draw, PredictionPackedMesh packed,
                           float morph, int meshlet) {
        double x=draw.bounds().minX,z=draw.bounds().minZ,xx=draw.bounds().maxX,zz=draw.bounds().maxZ;
        double y=draw.bounds().minY,yy=draw.bounds().maxY;
        if(meshlet>=0) {
            float[] b=packed.occlusionBounds.values;int i=meshlet*6;
            x=draw.tile().baseBlockX()+(double)b[i]-2; xx=draw.tile().baseBlockX()+(double)b[i+3]+2;
            z=draw.tile().baseBlockZ()+(double)b[i+2]-2; zz=draw.tile().baseBlockZ()+(double)b[i+5]+2;
            y=b[i+1]-2; yy=b[i+4]+2;
        }
        // Bilinear morph samples remain within the field's delta extrema. Expanding by
        // those deltas covers the entire animation without stretching every box to tile height.
        if(morph>0) { y+=Math.min(0,packed.morphDeltaMin()*morph); yy+=Math.max(0,packed.morphDeltaMax()*morph); }
        // Bounds use the same stable world space as the records.  The
        // occlusion filter adds CameraOffset before projection.
        bounds.putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat(0);
        bounds.putFloat((float)xx).putFloat((float)yy).putFloat((float)zz).putFloat(0);
    }
    void fallback(PredictionRenderer.Draw draw, PredictionDrawRanges ranges,
                  net.minecraft.world.phys.Vec3 camera) {
        int bucket = distanceBucket(draw, camera);
        queuedQuads[bucket] += ranges.quads;
    }
    private static int distanceBucket(PredictionRenderer.Draw draw, net.minecraft.world.phys.Vec3 camera) {
        double dx = draw.tile().baseBlockX() + draw.tile().spanBlocks() * 0.5 - camera.x;
        double dz = draw.tile().baseBlockZ() + draw.tile().spanBlocks() * 0.5 - camera.z;
        double distance2 = dx * dx + dz * dz;
        return distance2 < 65536.0 ? 0 : distance2 < 1048576.0 ? 1
                : distance2 < 16777216.0 ? 2 : distance2 < 268435456.0 ? 3 : 4;
    }
    void flush() {
        stage();
        drain();
    }

    private void stage() {
        if(commands==0)return;
        if (replaying) {
            PlanBatch expected = replayBatchIndex < plan.batches.size()
                    ? plan.batches.get(replayBatchIndex) : null;
            if (expected == null || expected.page != page || expected.tiles != tiles
                    || expected.commands != commands || replayItemIndex != expected.items.size()
                    || slotIndex >= slots.size()) {
                switchToRebuildCurrent();
            } else {
                BatchSlot slot = slots.get(slotIndex++);
                submitBatch(slot, expected.page, expected.commands);
                planReplayBatches++;
                replayBatchIndex++;
                replayItemIndex = 0;
                records.clear();draws.clear();bounds.clear();tiles=commands=0;page=null;
                currentPlanBatch = null;
                return;
            }
        }
        records.flip();draws.flip();bounds.flip();
        if (slotIndex == slots.size()) slots.add(new BatchSlot());
        BatchSlot slot = slots.get(slotIndex++);
        long uploadStart = PredictionRenderTimings.start();
        boolean uploaded = slot.upload(records, draws, bounds, culling);
        PredictionRenderTimings.end(PredictionRenderTimings.Stage.INDIRECT_UPLOAD, uploadStart);
        if (uploaded) {
            uploadedBatches++;
            uploadedBytes += records.remaining() + draws.remaining() + (culling ? bounds.remaining() : 0);
        } else reusedBatches++;
        submitBatch(slot, page, commands);
        records.clear();draws.clear();bounds.clear();tiles=commands=0;page=null;
        currentPlanBatch = null;
    }
    private void submitBatch(BatchSlot slot, PredictionTerrainArena.Page batchPage, int commandCount) {
        if (culling) {
            if (pendingCount == pending.size()) pending.add(new PendingBatch());
            PendingBatch entry = pending.get(pendingCount++);
            entry.slot = slot; entry.page = batchPage; entry.commands = commandCount;
        } else drawBatch(slot, batchPage, commandCount, slot.indirect);
    }

    /** Filter every page before the bucket's first draw, sharing one visibility barrier. */
    private void drain() {
        if (pendingCount == 0) return;
        boolean filtering = occlusion.beginFiltering();
        try {
            for (int i = 0; i < pendingCount; i++) {
                PendingBatch entry = pending.get(i);
                entry.buffer = occlusion.dispatch(entry.slot.bounds, entry.slot.indirect,
                        entry.slot.filtered, entry.commands, entry.commands >= 128 ? entry.slot.parents : 0);
            }
        } finally { occlusion.endFiltering(); }
        if (filtering) barriers++;
        for (int i = 0; i < pendingCount; i++) {
            PendingBatch entry = pending.get(i);
            drawBatch(entry.slot, entry.page, entry.commands, entry.buffer);
            entry.slot = null; entry.page = null;
        }
        pendingCount = 0;
    }

    private void drawBatch(BatchSlot slot, PredictionTerrainArena.Page batchPage, int commandCount, int commandBuffer) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,slot.metadata);
        PredictionGlState.activeTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,batchPage.texture);
        program.use();
        program.batch(true);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        int submitQuery = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.INDIRECT_SUBMIT);
        glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,0L,commandCount,20);
        PredictionRenderTimings.gpuEnd(submitQuery);
        program.batch(false);
        calls++;flushes++;
    }
    private void switchToRebuildCurrent() {
        if (!replaying) return;
        PlanBatch expected = replayBatchIndex < plan.batches.size()
                ? plan.batches.get(replayBatchIndex) : null;
        int matched = expected == null ? 0 : Math.min(replayItemIndex, expected.items.size());
        if (expected != null) {
            // Re-encode only the prefix which was already accepted by replay.
            // This is rare (coverage/order/page changes); stable movement never
            // enters this path and therefore performs no metadata work.
            for (int i = 0; i < matched; i++) {
                PlanItem item = expected.items.get(i);
                encodeItem(item.draw, item.ranges, item.slice, item.water,
                        item.average, item.segmented, i);
            }
            expected.items.subList(matched, expected.items.size()).clear();
            expected.page = page;
            expected.tiles = tiles;
            expected.commands = commands;
            currentPlanBatch = expected;
            if (replayBatchIndex + 1 < plan.batches.size())
                plan.batches.subList(replayBatchIndex + 1, plan.batches.size()).clear();
        }
        replaying = false;
        rebuilding = true;
        plan.valid = false;
        planMisses++;
    }
    private void invalidateForFallback() {
        replaying = false;
        rebuilding = false;
        fallbackInFrame = true;
        plan.clear();
    }
    long end() {
        try {
            if (replaying && commands != 0) {
                PlanBatch expected = replayBatchIndex < plan.batches.size()
                        ? plan.batches.get(replayBatchIndex) : null;
                if (expected == null || replayItemIndex != expected.items.size())
                    switchToRebuildCurrent();
            }
            flush();
            if (replaying && replayBatchIndex < plan.batches.size())
                plan.batches.subList(replayBatchIndex, plan.batches.size()).clear();
            if (!fallbackInFrame) plan.valid = true;
            else plan.valid = false;
            if (opaque) {
                System.arraycopy(queuedQuads, 0, lastOpaqueQueued, 0, queuedQuads.length);
                lastOpaqueReused = reusedBatches;
                lastOpaqueUploaded = uploadedBatches;
                lastOpaqueBytes = uploadedBytes;
                lastOpaqueFlushes = flushes;
                lastBarriers = barriers;
            }
            while (slots.size() > slotIndex) slots.remove(slots.size() - 1).close();
            return calls;
        }
        finally {
            if (activeGpuSample != null) {
                glQueryCounter(activeGpuSample.end, GL_TIMESTAMP);
                activeGpuSample.pending = true; activeGpuSample = null;
            }
            if(culling) occlusion.end();
            culling=false;
            program.batch(false);
            if (oldIndexed != 0 && oldSize > 0)
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 7, oldIndexed, oldStart, oldSize);
            else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 7, oldIndexed);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,oldStorage);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,oldIndirect);
        }
    }
    long submittedTiles() { return submittedTiles; }
    long reusedBatches() { return reusedBatches; }
    long uploadedBatches() { return uploadedBatches; }
    long uploadedBytes() { return uploadedBytes; }
    String diagnostics() {
        return "queuedQuads=" + Arrays.toString(lastOpaqueQueued)
                + ",buckets=0-256|256-1024|1024-4096|4096-16384|16384+"
                + ",reusedBatches=" + lastOpaqueReused
                + ",uploadedBatches=" + lastOpaqueUploaded
                + ",uploadedBytes=" + lastOpaqueBytes
                + ",flushes=" + lastOpaqueFlushes
                + ",planHits=" + planHits
                + ",planMisses=" + planMisses
                + ",planReplayBatches=" + planReplayBatches + ",filterBarriers=" + lastBarriers
                + policy.diagnostics() + occlusion.diagnostics();
    }
    @Override public void close(){
        for (BatchSlot slot : opaqueSlots) slot.close();
        for (BatchSlot slot : culledSlots) slot.close();
        for (BatchSlot slot : waterSlots) slot.close();
        for (GpuSample sample : gpuSamples) if (sample.start != 0) {
            glDeleteQueries(sample.start); glDeleteQueries(sample.end);
        }
        opaqueSlots.clear();culledSlots.clear();waterSlots.clear();pending.clear();
        MemoryUtil.memFree(records);MemoryUtil.memFree(draws);MemoryUtil.memFree(bounds);
        occlusion.close();
        opaquePlan.clear();
        culledPlan.clear();
        waterPlan.clear();
    }

    private void pollGpuSamples() {
        for (GpuSample sample : gpuSamples) if (sample.pending
                && glGetQueryObjecti(sample.end, GL_QUERY_RESULT_AVAILABLE) != 0) {
            long nanos = glGetQueryObjectui64(sample.end, GL_QUERY_RESULT)
                    - glGetQueryObjectui64(sample.start, GL_QUERY_RESULT);
            policy.accept(sample.epoch, sample.culling, nanos);
            sample.pending = false;
        }
    }

    private static final class GpuSample { int start, end; long epoch; boolean pending, culling; }
    private static final class PendingBatch { BatchSlot slot; PredictionTerrainArena.Page page; int commands, buffer; }

    private static final class PlanCache {
        final ArrayList<PlanBatch> batches = new ArrayList<>();
        PredictionTerrainProgram program;
        long residencyRevision, ownershipRevision, materialRevision;
        boolean opaque, culling, valid;

        boolean matches(PredictionTerrainProgram nextProgram, boolean nextOpaque, boolean nextCulling,
                        long nextResidency, long nextOwnership, long nextMaterial) {
            return valid && program == nextProgram && opaque == nextOpaque && culling == nextCulling
                    && residencyRevision == nextResidency && ownershipRevision == nextOwnership
                    && materialRevision == nextMaterial;
        }

        void setToken(PredictionTerrainProgram nextProgram, boolean nextOpaque, boolean nextCulling,
                      long nextResidency, long nextOwnership, long nextMaterial) {
            program = nextProgram;
            opaque = nextOpaque;
            culling = nextCulling;
            residencyRevision = nextResidency;
            ownershipRevision = nextOwnership;
            materialRevision = nextMaterial;
        }

        void clear() {
            valid = false;
            batches.clear();
        }
    }

    private static final class PlanBatch {
        final ArrayList<PlanItem> items = new ArrayList<>();
        PredictionTerrainArena.Page page;
        int tiles, commands;
    }

    private static final class PlanItem {
        final PredictionRenderer.Draw draw;
        final PredictionDrawRanges ranges;
        final PredictionTerrainArena.Slice slice;
        final boolean water, average, segmented;
        final int commands;
        final long key;

        PlanItem(PredictionRenderer.Draw draw, PredictionDrawRanges ranges,
                 PredictionTerrainArena.Slice slice, boolean water, boolean average,
                 boolean segmented, int commands) {
            this.draw = draw;
            this.ranges = ranges;
            this.slice = slice;
            this.water = water;
            this.average = average;
            this.segmented = segmented;
            this.commands = commands;
            this.key = key(draw, ranges, slice, water, average, segmented, commands);
        }

        boolean matches(PredictionRenderer.Draw nextDraw, PredictionDrawRanges nextRanges,
                        PredictionTerrainArena.Slice nextSlice, boolean nextWater,
                        boolean nextAverage, boolean nextSegmented, int nextCommands) {
            if (key != key(nextDraw, nextRanges, nextSlice, nextWater, nextAverage,
                    nextSegmented, nextCommands)) return false;
            if (draw.tile() != nextDraw.tile() || draw.gpu() != nextDraw.gpu()
                    || draw.allowed() != nextDraw.allowed() || draw.seam() != nextDraw.seam()
                    || draw.faces() != nextDraw.faces()
                    || Float.floatToIntBits(draw.morph()) != Float.floatToIntBits(nextDraw.morph())
                    || !sameBounds(draw.bounds(), nextDraw.bounds())) return false;
            if (ranges.quads != nextRanges.quads || !Arrays.equals(ranges.first, nextRanges.first)
                    || !Arrays.equals(ranges.count, nextRanges.count)) return false;
            if (slice != nextSlice || slice.page != nextSlice.page || slice.offset != nextSlice.offset
                    || slice.length != nextSlice.length || slice.maskOffset != nextSlice.maskOffset)
                return false;
            return water == nextWater && average == nextAverage && segmented == nextSegmented
                    && commands == nextCommands;
        }

        private static boolean sameBounds(net.minecraft.world.phys.AABB a,
                                          net.minecraft.world.phys.AABB b) {
            return a.minX == b.minX && a.minY == b.minY && a.minZ == b.minZ
                    && a.maxX == b.maxX && a.maxY == b.maxY && a.maxZ == b.maxZ;
        }

        private static long key(PredictionRenderer.Draw draw, PredictionDrawRanges ranges,
                                PredictionTerrainArena.Slice slice, boolean water,
                                boolean average, boolean segmented, int commands) {
            var tile = draw.tile();
            var packed = draw.gpu().packed();
            long h = 0x9E3779B97F4A7C15L;
            h = mix(h, System.identityHashCode(tile));
            h = mix(h, tile.revision());
            h = mix(h, tile.baseBlockX());
            h = mix(h, tile.baseBlockZ());
            h = mix(h, tile.spanBlocks());
            h = mix(h, System.identityHashCode(draw.gpu()));
            h = mix(h, System.identityHashCode(packed));
            h = mix(h, draw.faces());
            h = mix(h, draw.seam() ? 1 : 0);
            h = mix(h, Float.floatToIntBits(draw.morph()));
            var bounds = draw.bounds();
            h = mix(h, Double.doubleToLongBits(bounds.minX));
            h = mix(h, Double.doubleToLongBits(bounds.minY));
            h = mix(h, Double.doubleToLongBits(bounds.minZ));
            h = mix(h, Double.doubleToLongBits(bounds.maxX));
            h = mix(h, Double.doubleToLongBits(bounds.maxY));
            h = mix(h, Double.doubleToLongBits(bounds.maxZ));
            if (packed != null) {
                h = mix(h, packed.quadCount());
                h = mix(h, packed.cellAxis());
                h = mix(h, packed.morphBaseTexel());
                h = mix(h, packed.paletteBaseTexel());
                h = mix(h, System.identityHashCode(packed.morph()));
                h = mix(h, packed.morphMinY());
                h = mix(h, packed.morphMaxY());
                h = mix(h, Float.floatToIntBits(packed.morphDeltaMin()));
                h = mix(h, Float.floatToIntBits(packed.morphDeltaMax()));
                h = mix(h, packed.aquaticLod.tier());
                if (segmented) h = mix(h, Arrays.hashCode(packed.occlusionBounds.values));
            }
            h = mix(h, draw.gpu().paletteBaseTexel());
            h = mix(h, Float.floatToIntBits(draw.gpu().morphStartSeconds()));
            h = mix(h, Arrays.hashCode(ranges.first));
            h = mix(h, Arrays.hashCode(ranges.count));
            h = mix(h, ranges.quads);
            h = mix(h, slice == null ? 0 : System.identityHashCode(slice.page));
            if (slice != null) {
                h = mix(h, slice.offset);
                h = mix(h, slice.length);
                h = mix(h, slice.maskOffset);
            }
            h = mix(h, water ? 1 : 0);
            h = mix(h, average ? 1 : 0);
            h = mix(h, segmented ? 1 : 0);
            h = mix(h, commands);
            return h;
        }

        private static long mix(long h, long value) {
            h ^= value + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
            return h;
        }
    }

    private static final class BatchSlot implements AutoCloseable {
        private final int metadata = glGenBuffers();
        private final int indirect = glGenBuffers();
        private int bounds, parents, filtered, filteredBytes;
        private byte[] recordBytes, drawBytes, boundBytes;
        private int recordLength, drawLength, boundLength;

        private boolean upload(ByteBuffer records, ByteBuffer draws, ByteBuffer boxes, boolean culling) {
            boolean sameRecords = same(records, recordBytes, recordLength);
            boolean sameDraws = same(draws, drawBytes, drawLength);
            if (!sameRecords) {
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, metadata);
                glBufferData(GL_SHADER_STORAGE_BUFFER, records, GL_STREAM_DRAW);
                recordLength = records.remaining();
                recordBytes = ensureCapacity(recordBytes, recordLength);
                records.duplicate().get(recordBytes, 0, recordLength);
            }
            if (!sameDraws) {
                glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect);
                glBufferData(GL_DRAW_INDIRECT_BUFFER, draws, GL_STREAM_DRAW);
                drawLength = draws.remaining();
                drawBytes = ensureCapacity(drawBytes, drawLength);
                draws.duplicate().get(drawBytes, 0, drawLength);
            }
            boolean sameBounds=true;
            if(culling) {
                if(bounds==0) { bounds=glGenBuffers(); parents=glGenBuffers(); filtered=glGenBuffers(); }
                sameBounds = same(boxes, boundBytes, boundLength);
                if(!sameBounds) {
                    glBindBuffer(GL_SHADER_STORAGE_BUFFER,bounds); glBufferData(GL_SHADER_STORAGE_BUFFER,boxes,GL_STREAM_DRAW);
                    boundLength = boxes.remaining();
                    boundBytes = ensureCapacity(boundBytes, boundLength);
                    boxes.duplicate().get(boundBytes, 0, boundLength);
                    float[] coarse = new float[((boundLength / 32 + 63) / 64) * 8];
                    for (int b = 0; b < coarse.length; b += 8) {
                        for (int axis = 0; axis < 3; axis++) { coarse[b + axis] = Float.POSITIVE_INFINITY; coarse[b + 4 + axis] = Float.NEGATIVE_INFINITY; }
                    }
                    for (int q = 0; q < boundLength / 32; q++) {
                        int b = q / 64 * 8, p = boxes.position() + q * 32;
                        for (int axis = 0; axis < 3; axis++) {
                            coarse[b + axis] = Math.min(coarse[b + axis], boxes.getFloat(p + axis * 4));
                            coarse[b + 4 + axis] = Math.max(coarse[b + 4 + axis], boxes.getFloat(p + 16 + axis * 4));
                        }
                    }
                    glBindBuffer(GL_SHADER_STORAGE_BUFFER,parents); glBufferData(GL_SHADER_STORAGE_BUFFER,coarse,GL_STATIC_DRAW);
                }
                if(filteredBytes<draws.remaining()) {
                    filteredBytes=draws.remaining(); glBindBuffer(GL_DRAW_INDIRECT_BUFFER,filtered);
                    glBufferData(GL_DRAW_INDIRECT_BUFFER,filteredBytes,GL_STREAM_DRAW);
                }
            }
            return !sameRecords || !sameDraws || !sameBounds;
        }

        private static boolean same(ByteBuffer source, byte[] target, int targetLength) {
            int length = source.remaining();
            if (target == null || targetLength != length)
                return false;
            int position = source.position();
            for (int i = 0; i < length; i++) {
                if (source.get(position + i) != target[i])
                    return false;
            }
            return true;
        }

        private static byte[] ensureCapacity(byte[] current, int required) {
            if (current != null && current.length >= required)
                return current;
            return new byte[required];
        }

        @Override public void close() {
            glDeleteBuffers(metadata);
            glDeleteBuffers(indirect);
            if(bounds!=0)glDeleteBuffers(bounds);
            if(parents!=0)glDeleteBuffers(parents);
            if(filtered!=0)glDeleteBuffers(filtered);
        }
    }
}
