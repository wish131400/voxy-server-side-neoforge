"""Check the final release JAR for integrated fixes, not just its source tree.

This is an artifact presence/reference check, not a substitute for regression
tests or a live game run. Run after reobfJar for Forge.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import zipfile


def verify(jar, platform, build_id, source):
    def require(condition, message):
        if not condition:
            raise ValueError(message)

    with zipfile.ZipFile(jar) as archive:
        require(archive.testzip() is None, "Corrupt JAR")
        names = set(archive.namelist())
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        require("VSS-Build-Id: " + build_id in manifest, "Wrong build ID")
        version = re.search(r"(?m)^mod_version=(.+)$", (source / "gradle.properties").read_text()).group(1).strip()
        require(jar.name == "vss-" + version + ".jar", "Wrong standard JAR name")
        require("Implementation-Version: " + version in manifest, "Wrong implementation version")
        metadata = "META-INF/neoforge.mods.toml" if platform == "neoforge" else "META-INF/mods.toml"
        require(re.search(r'(?m)^version\s*=\s*"' + re.escape(version) + r'"', archive.read(metadata).decode()) is not None,
                "Wrong mod metadata version")
        for locale in ("en_us", "zh_cn"):
            language = "assets/vss/lang/" + locale + ".json"
            require(archive.read(language) == (source / "src/main/resources" / language).read_bytes(),
                    "Packaged language differs from source: " + language)
        major = 65 if platform == "neoforge" else 61

        def check_class(name, *symbols):
            data = archive.read("dev/xantha/vss/" + name + ".class")
            require(int.from_bytes(data[6:8], "big") == major, "Wrong Java version: " + name)
            for symbol in symbols:
                require(symbol.encode() in data, "Missing reference: " + name + ": " + symbol)
            return data

        check_class("client/prediction/PredictionClimateSampler", "FabricBiomeCompat", "copyClimateContext")
        check_class("client/prediction/FabricBiomeCompat", "net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks", "fabric_getSeed", "fabric_setSeed")
        check_class("client/prediction/PredictionBiomeCache", "LookupFailure", "sample")
        check_class("client/prediction/PredictionBiomeCache$Points", "xs", "ys", "zs", "values")
        check_class("client/prediction/PredictionBiomeCache$Local", "PredictionBiomeCache$Points")
        check_class("client/prediction/PredictionBiomeCache$Stripe", "referenced", "next", "get", "put")
        check_class("compat/VoxySurfaceHeights", "NATIVE_INSET_ALIAS", "LEGACY_FLUID_INSET")
        check_class("compat/VoxyPendingSections", "poll", "promote", "priority", "view")
        check_class("compat/VoxyPredictionBridge", "newSingleThreadScheduledExecutor", "updateBatches", "emptyWithdrawals")
        check_class("compat/VoxyPredictionBridge$Context", "nextNotification", "meshBacklog", "mayHavePrediction")
        check_class("compat/VoxyRenderAccess", "meshBacklog")
        check_class("client/prediction/PredictionRetirementQueue", "submit", "drain", "Command")
        check_class("client/prediction/PredictionVoxySource", "removeLater", "mayHavePrediction", "pendingRetirements")
        check_class("client/prediction/PredictionTileManager", "removeLater", "reclaimAdmissionTarget")
        check_class("config/VSSServerConfig", "singleplayerGenerationConcurrencyLimit", "effectiveGenerationConcurrencyLimitPerPlayer")
        check_class("networking/server/generation/ChunkGenerationService", "effectiveGenerationConcurrencyLimitPerPlayer")
        check_class("networking/server/session/PlayerSessionManager", "effectiveGenerationConcurrencyLimitPerPlayer")
        check_class("compat/ModCompat", "replayRealXaeroColumn")
        check_class("compat/XaeroMapCompat", "replayRealColumn", "tryOfferReplay")
        check_class("networking/client/IntegratedPregenImporter", "replayRealXaeroColumn")
        check_class("networking/server/generation/ChunkyGenerationService", "retryImport")
        check_class("networking/server/session/WorldgenCodecSnapshot", "registry_tags", "preferred_biomes", "vss_terrablender_end")
        check_class("client/prediction/ClientWorldgenRegistries", "bindSnapshotTags", "snapshotRegistries")
        check_class("client/prediction/PredictionLodSampler", ":capture-palette-r2:")
        check_class("client/prediction/ClientWorldgenProfileDecoder", "Required TerraBlender region routing unavailable", "vss_terrablender_end")
        check_class("client/prediction/RustTerrainSampler", "Native library lacks required TerraBlender routing", "terrablenderRouting")
        check_class("compat/VoxySectionData", "contentIdentity", "faceIdentity")
        check_class("compat/VoxySectionSignature", "compact")
        check_class("compat/VoxySectionSignature", "face", "EMPTY_FACE")
        check_class("compat/VoxyPredictionBridge$Delivered", "realFaces")
        check_class("compat/VoxyPredictionBridge$Context", "realFaces", "face")
        check_class("client/prediction/PredictionVoxySource", "protectedBytes", "trimProtected", "retainedBytes")
        check_class("client/prediction/PredictionVegetation", "restoreForRendering")
        check_class("client/prediction/PredictionVegetation$CachedTile", "complete", "tile")
        check_class("client/prediction/PredictionTileManager", "surfaceCacheAttempts", "restoreForRendering")
        bridge_context=check_class("compat/VoxyPredictionBridge$Context", "notificationIdentity", "earlyUnchangedNotifications", "revokeInput", "revokeNeighborInputs")
        require(b"realRevision" not in bridge_context, "Global native revision still invalidates unrelated reuse")
        check_class("client/prediction/PredictionVoxySource", "notificationIdentity", "cachedNotificationView", "NOT_CACHED", "readFace")
        check_class("client/prediction/PredictionTerrainChange", "compare", "ordered", "LiquidBlock")
        check_class("client/prediction/PredictionVoxySource", "PredictionTerrainChange", "sameFeatureBiomes", "terrainRangeReductions")
        check_class("client/prediction/PredictionVegetation", "END_CLIMATE_SETTINGS_VERSION", "LookupFailure")
        check_class("client/prediction/PredictionSurfaceStructures", "LookupFailure")
        inset = check_class("compat/VoxySurfaceHeights", "patchShader", "// VSS world-space model inset")
        for retired in (b"vssSurfaceDrop", b"HEIGHT_BITS", b"org/lwjgl/system/MemoryUtil"):
            require(retired not in inset, "Retired post-cull geometry path: " + retired.decode())
        for method in (b"correct", b"encode"):
            # Class-file UTF8 constants carry a two-byte length; exclude longer local names.
            require(len(method).to_bytes(2, "big") + method not in inset,
                    "Retired mesh method: " + method.decode())
        require(not any(n.startswith("dev/xantha/vss/compat/VoxySurfaceHeights$") for n in names),
                "Stale native geometry accessor packaged")
        for cls in ("compat/VoxySectionData", "compat/VoxyPredictionBridge", "client/prediction/PredictionVoxySource"):
            require(b"surfaceHeights" not in check_class(cls), "Retired height metadata: " + cls)
        bridge = check_class("compat/VoxyPredictionBridge", "levelUnloaded", "confirmedPredictionColumns")
        require(b"VoxySurfaceHeights" not in bridge, "Native mesh is still post-processed")
        check_class("mixin/voxy/PredictionVoxyTraversalShaderMixin", "VoxySurfaceHeights", "patchShader")
        check_class("client/prediction/ClientPredictionState", "LEVEL_GENERATION", "levelUnloaded")
        check_class("client/prediction/ClientPredictionState", "retireUnusedSampler", "VSS prediction cache ready:", "VSS prediction generation ready:")
        check_class("client/prediction/PredictionCacheSampler", "Cache context cannot generate terrain", "cacheBiomes", "templates", "floating")
        check_class("client/prediction/PredictionCacheStorage", "openExisting")
        check_class("client/prediction/ClientWorldgenProfileDecoder$Session", "PredictionCacheSampler", "cacheContext")
        check_class("client/prediction/PredictionTileManager", "generationReady", "acceptsGeneration", "enableGeneration")
        check_class("client/prediction/PredictionResources", "retireUnusedSampler")
        check_class("client/prediction/PredictionVoxelCache", "java/security/DigestInputStream", "voxel record has trailing data")
        check_class("networking/client/VSSClientNetworking", "onClientLevelUnload")
        check_class("compat/VoxyCompat$LocalSectionIndex", "settledRegions")
        check_class("compat/VoxyIngestTracker", "realColumnSettled")
        check_class("compat/VoxyPredictionBridge$Context", "hasSkyLight")
        check_class("compat/VoxyCapacityPlan", "initialCapacity", "capacity")
        check_class("compat/VoxyTraversalCapacity", "getCurrentMaxNodeId", "beforeAdd", "beforeTraversal", "ensureRoots", "ensureQueues")
        check_class("compat/VoxyTraversalCapacity$1", "glCopyNamedBufferSubData", "free")
        traversal_shader = check_class("compat/VoxyTraversalShader", "// VSS bounded traversal queues", "nodeQueueSource.length()", "enqueueSelfForRender")
        require(b"atomicCompSwap" not in traversal_shader, "Serial GPU reservation retry loop packaged")
        check_class("mixin/voxy/VoxyTraversalCapacityMixin", "vss$initialRootArray", "vss$initialQueues", "beforeTraversal")
        check_class("mixin/voxy/VoxyTraversalQueueShaderMixin", "VoxyTraversalShader")
        capacity_mixins = json.loads(archive.read("vss.compat.mixins.json"))["client"]
        require(all(name in capacity_mixins for name in ("voxy.VoxyTraversalCapacityMixin", "voxy.VoxyTraversalQueueShaderMixin")),
                "Capacity mixins absent from runtime configuration")
        check_class("client/prediction/PredictionVoxySource", "lightingBuilds")
        check_class("client/prediction/PredictionTileManager", "invalidatedSurfaces")
        check_class("client/prediction/PredictionCaptureHandoff", "await", "committed", "blocked", "reindex", "retired")
        check_class("client/prediction/PredictionTileManager", "awaitExactColumns", "requiresExactCapture", "retryCapture", "captureHandoff", "retireExactColumns")
        check_class("networking/client/VSSClientNetworking", "dirtyColumnTimestamp", "expectsAuthoritativeColumns")
        check_class("networking/client/LodRequestManager", "onDirtyColumnsRetired")
        check_class("client/prediction/PredictionVoxelData", "terrainRevision")
        check_class("client/prediction/PredictionVoxelData", "cacheContentIdentity")
        check_class("client/prediction/PredictionVoxySource$Tile", "reuseContent")
        check_class("client/prediction/PredictionVoxySource", "vegetationVisible")
        check_class("client/prediction/PredictionTileManager", "surfaceFallbackReady")
        check_class("client/prediction/PredictionVoxyBoxes", "changedBounds")
        check_class("client/prediction/PredictionDiskCache", "voxelWrites", "unchangedVoxelWrites", "contentIdentity")
        check_class("client/prediction/PredictionVoxelCache", "FEATURE_PAGE_SIZE", "contentIdentity", "voxel feature page invalid")
        check_class("compat/VoxySectionSignature", "SHA-256", "faces")
        check_class("compat/VoxyPredictionBridge$Delivered", "changes")
        check_class("compat/VoxyPredictionBridge$Context", "notificationContent", "delivered", "unchangedNotifications")
        check_class("client/prediction/PredictionParentLod$Proof", "terrainRevision")
        check_class("client/prediction/PredictionParentLod$Input", "uses")
        check_class("client/prediction/PredictionTileManager", "terrainCellAxis")
        check_class("client/prediction/PredictionTileManager", "PredictionTileStates", "tileStates")
        check_class("client/prediction/PredictionWorkOrder", "coveragePriority")
        check_class("client/prediction/PredictionSchedulePreparation$Facts", "coveragePriority")
        check_class("client/prediction/PredictionPlanPreparation", "nativeCoverage")
        check_class("client/prediction/PredictionVoxyWorkPreparation", "coveragePriority")
        check_class("client/prediction/PredictionDiskCache", "resetTerrainProbes", "maximumWaitNanos")
        check_class("client/prediction/PredictionTileManager", "coveragePriority", "resetTerrainProbes")
        check_class("client/prediction/PredictionTileStates", "AtomicLongArray", "membership", "generation")
        check_class("client/prediction/PredictionInputSnapshots", "SetSource", "MapSource")
        check_class("client/prediction/PredictionSchedulePreparation$Facts", "projected", "distance", "priority")
        check_class("client/prediction/PredictionLoadingPreparation$Calculation", "Facts", "reads")
        check_class("client/prediction/PredictionVegetationBlocks", "positions", "palette", "table")
        check_class("client/prediction/PredictionVegetation$Tile", "forEachVoxel", "order", "cells")
        check_class("client/prediction/PredictionVegetationDisplayCache", "PredictionVegetationBlocks$Builder", "bytes")
        check_class("client/prediction/PredictionVoxySource", "forEachVoxel")
        check_class("client/prediction/PredictionVoxySource$VolumeScratch", "mappedRuns", "mappedRunCount", "materials", "clear")
        check_class("client/prediction/PredictionVoxyMaterials", "reset", "clear", "table", "lastValue")
        require("dev/xantha/vss/client/prediction/PredictionVoxyMaterials$Key.class" not in names,
                "Stale object material pair key packaged")
        check_class("compat/VoxySectionData", "BOUNDARIES", "MAX_BOUNDARIES")
        check_class("compat/VoxyNotificationScratch", "ThreadLocal", "borrow", "close")
        check_class("compat/VoxyPredictionBridge$Context", "VoxyNotificationScratch")
        check_class("client/prediction/PredictionVoxyBoxes", "retainVegetation", "geometryHash", "sameGeometry")
        check_class("client/prediction/PredictionVoxyLighting", "propagate")
        check_class("client/prediction/PredictionVoxelCache", ":floating-columns-r1")
        check_class("client/prediction/PredictionExteriorColumns", "enrichFloating")
        check_class("compat/VoxySubdivisionWaiters", "needsSamples")
        check_class("client/prediction/PredictionInputSnapshots$TrackedSet", "changed", "shards", "ShardSet")
        check_class("client/prediction/PredictionInputSnapshots$TrackedMap", "changed", "shards", "ShardMap")
        shards = check_class("client/prediction/PredictionInputSnapshots", "shard")
        require(bytes.fromhex("0385ebca6b") in shards and bytes.fromhex("03c2b2ae35") in shards,
                "Missing independent shard hash mixing constants")
        check_class("client/prediction/PredictionVoxyFeatures", "intersect", "visit", "quickSort", "bounds")
        check_class("client/prediction/PredictionVoxyBoxes", "coordinate", "WeakReference", "pageStorage")
        check_class("client/prediction/PredictionVoxyBoxes", "CONTENTS", "shareContents", "index")
        check_class("client/prediction/PredictionVoxyFeatures", "partition", "compare")
        check_class("client/prediction/PredictionDiskCache$Shared", "surfaceInvalidations")
        check_class("client/prediction/PredictionDiskCache", "invalidated", "deleteSurfaces")
        check_class("client/prediction/PredictionRegionStorage", "deleteColdRegion", "deleteSurfaces")
        check_class("client/prediction/PredictionVoxySource", "columnClock", "FACE_BUFFERS", "compactFaceCopy")
        check_class("client/prediction/PredictionVoxyBoxes$Data", "index", "limits")
        check_class("client/prediction/PredictionVoxyBoxes", "append", "indexBytes")
        check_class("client/prediction/PredictionDetailTrace", "dirtyAgeSeconds", "demandAgeSeconds", "lastEvent")
        check_class("client/prediction/PredictionVoxySource", "copyFace", "COLUMN_SCRATCH", "VOLUME_SCRATCH", "COLUMN_PROBE")
        check_class("compat/VoxyPredictionBridge", "NEIGHBOR_BUFFER", "copyFace")
        check_class("client/prediction/PredictionCapturedTerrain", "decoding", "coalescedReads", "generation")
        check_class("client/prediction/PredictionCapturedTerrain$Summary", "columns")
        check_class("client/prediction/PredictionDecorationLevel", "LocalPlacementFailure")
        check_class("client/prediction/PredictionVegetation", "localFailures", "LocalPlacementFailure")
        check_class("client/prediction/PredictionTileManager", "detailPipelineSnapshot", "oldestIncomplete", "visibleRemaining")
        require("dev/xantha/vss/client/prediction/PredictionVoxyFeatures$Node.class" not in names, "Object feature tree packaged")
        check_class("client/prediction/PredictionVoxyDetailQueue", "maintainResidency", "dormant")
        check_class("client/prediction/PredictionVoxySource", "selectOwners", "featureIndex")
        check_class("client/prediction/PredictionVoxySource$ColumnKey", "PredictionKeyHash")
        check_class("client/prediction/PredictionMemoryBudget", "unobservedReleaseBytes", "refreshCollections")
        check_class("common/StrictLodFrontier", "blockedStage")
        check_class("compat/VoxySectionPruner", "completedAdditions", "prune")
        check_class("compat/VoxySectionVersions", "additions")
        check_class("compat/VoxyNodeChildren", "emptyNonRoot")
        check_class("compat/VoxyPredictionBridge$Context", "VoxySectionPruner")
        check_class("compat/VoxyPredictionBridge", "VoxyNodeChildren")
        check_class("compat/VoxyPredictionBridge", "copyNeighborFaces", "mergeRealFace", "SnapshotTarget")
        check_class("compat/VoxySectionData", "copyFace", "VoxyFaceData")
        check_class("client/prediction/PredictionVoxySource", "reducedColumn", "copySection", "mappedFaces",
                    "unchangedPublications", "retainedColumnPublications", "changedTerrain")
        check_class("client/prediction/PredictionVoxyColumnRuns$Builder", "arraycopy", "binarySearch")
        check_class("client/prediction/PredictionVoxyColumnRuns$Coverage", "covers", "put")
        check_class("client/prediction/PredictionVoxySource$CachedColumn", "referenced")
        check_class("client/prediction/PredictionVoxySource", "columnSecondChances")
        check_class("client/prediction/PredictionVoxySource", "columnPageBuilds", "featureContributors", "publicationChange")
        check_class("client/prediction/PredictionVoxySource$VolumeScratch", "columnPages", "featureContributors")
        check_class("client/prediction/PredictionVoxyColumnRuns$Page", "offsets", "write")
        check_class("client/prediction/PredictionVoxyColumnRuns$PageBuilder", "append")
        check_class("client/prediction/PredictionVoxyChange", "precedes", "bitCount")
        check_class("client/prediction/PredictionVoxyChange", "computeIfAbsent", "horizontal", "peerVisits")
        check_class("client/prediction/PredictionSectionIndex", "references", "levels", "intersect", "collect")
        check_class("client/prediction/PredictionVoxySource", "cachedPositions", "changeInput", "changeCurrent", "prepareInvalidation", "refreshDetails")
        check_class("client/prediction/PredictionTileManager", "voxyDemand", "voxyDemandRefill", "PredictionVoxySource$DetailInput")
        check_class("client/prediction/PredictionVoxyViewDemand", "collect", "projectedSize", "hasProjection")
        check_class("client/prediction/PredictionVoxyHorizon", "changes", "intersects", "affected", "clips")
        check_class("client/prediction/PredictionVoxySource", "PredictionVoxyViewDemand", "observedView", "viewDemandBuilds",
                    "PredictionVoxyHorizon", "windowUpdates", "updateWindow")
        check_class("client/prediction/PredictionVoxyDetailQueue", "observe")
        check_class("client/prediction/PredictionWorkView", "hasProjection", "atan")
        check_class("client/prediction/PredictionRenderer", "projection", "scoping", "ageSeconds")
        check_class("compat/VoxySectionSignature", "asLongBuffer", "java/nio/LongBuffer", "SHA-256")
        check_class("client/prediction/PredictionInputSnapshots$Union", "buckets", "snapshot")
        check_class("client/prediction/PredictionTileManager", "PredictionInputSnapshots$Union", "keys")
        check_class("client/prediction/RustWorldgenDocument", "vss_dimension_bounds")
        for native in [n for n in names if n.startswith("META-INF/vss-natives/") and n.endswith((".dll", ".so", ".dylib"))]:
            require(b"vss_dimension_bounds" in archive.read(native), "Native missing dimension bounds: " + native)

        check_class("mixin/voxy/PredictionVoxyNeighborSourceMixin", "copyNeighborFaces", "VoxyFluidSurface", "vss$packFluidHeight", "vss$liquidTransitions")

        check_class("compat/VoxyPredictionBridge", "updatePredictionState")
        check_class("compat/VoxyPredictionBridge$Context", "withdrawals", "withdraw")
        require(any(n.startswith("dev/xantha/vss/compat/VoxyRenderAccess$") and n.endswith(".class")
                    and b"submitGeometryResult" in archive.read(n) and b"empty" in archive.read(n) for n in names),
                "Missing native empty geometry adapter")
        check_class("client/prediction/ClientPredictionState", "updatePredictionState")
        cpu_budget = check_class("client/prediction/PredictionCpuBudget", "generationWaiting", "generationWaitAt", "mode=configured", "detailLimit")
        require(not any(marker in cpu_budget for marker in (b"getCpuLoad", b"getProcessCpuLoad", b"METRIC_SAMPLER", b"DETAIL_TRICKLE_NANOS", b"RECOVERY_NANOS", b"pressure")),
                "Machine-wide CPU throttling still packaged")
        require("dev/xantha/vss/client/prediction/PredictionCpuBudget$ThrottleLevel.class" not in names,
                "Stale CPU throttle class packaged")
        require("dev/xantha/vss/client/prediction/PredictionCpuBudget$MetricSample.class" not in names,
                "Stale CPU metric sample class packaged")
        priority = check_class("networking/client/PredictionGenerationPriority", "shared-", "detail-ready")
        require(b"exclusive" not in priority and b"STALL" not in priority, "Exclusive prediction generation gate packaged")
        requests = check_class("networking/client/LodRequestManager", "reserveLocalGeneration")
        require(b"localGenerationLimit" not in requests, "Non-atomic generation admission still packaged")

        check_class("compat/VoxyFluidSurface", "top", "pack", "transitions", "meshTransitions", "LiquidBlock")
        check_class("compat/VoxySurfaceHeights", "// VSS liquid surface remainder", "vssLiquidDrop")
        check_class("client/prediction/PredictionVoxySource", "VoxyFluidSurface")
        check_class("mixin/voxy/StrictVoxyVisibilityMixin", "observeUnifiedView")
        check_class("client/prediction/PredictionRenderer", "observeUnifiedView", "width", "height")
        check_class("client/prediction/PredictionViewObservation", "height", "selectionScale")
        check_class("client/prediction/PredictionVoxyDetailQueue", "deferredEntries", "deferredOrder", "rebalance", "membershipRevision")
        check_class("client/prediction/PredictionVoxySource", "detailSnapshotRevision", "detailWaiting", "revision")
        state = check_class("client/prediction/ClientPredictionState", "setNativeDetailLoading")
        require(b"updateViewFocus" not in state, "Independent scope raycast still scheduled")
        check_class("client/prediction/PredictionPlanPreparation$Input", "nativeDemand")
        check_class("client/prediction/PredictionPlanPreparation", "bootstrap")
        require("dev/xantha/vss/mixin/voxy/IrisVoxyPredictionMixin.class" not in names, "Retired Iris observer packaged")
        enabled = set()
        for name in names:
            if name.endswith(".mixins.json"):
                config = json.loads(archive.read(name))
                for cls in config.get("mixins", []) + config.get("client", []) + config.get("server", []):
                    path = config["package"].replace(".", "/") + "/" + cls.replace(".", "/") + ".class"
                    require(path in names, "Missing configured mixin: " + path)
                    enabled.add(path)
        require("dev/xantha/vss/mixin/voxy/PredictionVoxyTraversalShaderMixin.class" in enabled,
                "Model inset shader mixin is not configured")
        require(not any(n.endswith("Test.class") or n.startswith("xaero/") for n in names),
                "Test or foreign fixture classes packaged")
        if platform == "forge":
            check_class("client/prediction/PredictionRenderer", "m_91087_")
            require("META-INF/jarjar/lz4-java-1.8.0.jar" in names, "Missing Forge LZ4 dependency")
        else:
            for name in names:
                if name.startswith("dev/xantha/vss/") and name.endswith(".class"):
                    require(archive.read(name) == (source / "build/classes/java/main" / name).read_bytes(),
                            "Packaged class differs from compiled source: " + name)
        natives = {}
        for name in sorted(names):
            if name.startswith("META-INF/vss-natives/") and name.endswith((".dll", ".so", ".dylib")):
                require(archive.read(name) == (source / "src/main/resources" / name).read_bytes(),
                        "Native library differs from source: " + name)
                natives[name] = hashlib.sha256(archive.read(name)).hexdigest()
        require(len(natives) == 5, "Expected all five native libraries")
        native_build = json.loads(archive.read("META-INF/vss-native-build.json"))
        digest = hashlib.sha256()
        core = source / "tools/rust/vss-native-core"
        inputs = [p for p in (core / "src").rglob("*.rs")]
        inputs += [core / name for name in ("Cargo.toml", "Cargo.lock", "build.rs") if (core / name).is_file()]
        for path in sorted(inputs, key=lambda p: p.relative_to(source).as_posix()):
            digest.update(path.relative_to(source).as_posix().encode() + b"\0"
                          + path.read_bytes().replace(b"\r\n", b"\n") + b"\0")
        require(native_build["sourceSha256"] == digest.hexdigest(), "Native build differs from production Rust source")
        for name, value in natives.items():
            require(native_build["libraries"][name.removeprefix("META-INF/vss-natives/")] == value,
                    "Native build metadata checksum mismatch: " + name)
            require(b"vss_terrablender_end" in archive.read(name), "Native End routing absent: " + name)
    return {"jar": jar.name, "platform": platform, "buildId": build_id,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "bytes": jar.stat().st_size, "nativeSha256": natives,
            "integratedFixes": ["view-priority-background-notifications", "asynchronous-budgeted-retirement", "off-lock-snapshot-disposal", "native-empty-fast-withdrawal", "steady-state-lock-bypass", "configured-shared-cpu-admission", "two-stage-warm-cache-startup", "in-place-native-backend-promotion", "streamed-voxel-content-digest", "stale-prepared-backend-retirement", "native-border-consumer-evidence", "byte-bounded-section-admission", "independent-partial-surface-cache-restore", "early-identical-input-notification", "answer-budget-biome-cache", "terrain-interval-publication", "shared-quart-neighborhoods", "persistent-fair-generation-turn", "horizon-planned-root-capacity", "resident-node-traversal-capacity", "non-spinning-gpu-queue-bounds", "two-phase-dirty-capture-handoff", "bounded-capture-retry", "dirty-retention-buffer-fetch", "retired-refresh-barrier", "seeded-fabric-end-biomes", "failed-biome-surface-rejection", "end-decoration-cache-repair", "compact-column-pages", "contributing-feature-query", "native-owner-publication",
                                "layout-plan-domain-reset", "partial-vegetation-retention", "terrain-only-parent-proof",
                                "retained-deferred-detail-demand", "view-priority-readmission", "completion-driven-detail-refill", "dimension-write-bounds", "reverse-coarse-column-coverage", "second-chance-column-cache", "incremental-candidate-union", "exact-display-biome-reuse", "prediction-display-withdrawal", "warm-toggle-reactivation", "concurrent-real-generation", "atomic-generation-admission", "auxiliary-viewport-observer-guard", "native-detail-demand", "authoritative-viewport", "spatial-bootstrap-only", "liquid-surface-remainders", "native-liquid-transition-walls", "native-lod-topology", "world-space-model-inset", "nether-lighting",
                                "end-floating-columns", "empty-node-sample-readmission",
                                "dimension-transition", "real-air-authority",
                                "incremental-snapshots", "scheduled-range-pruning",
                                "frontier-blocker-probe", "empty-child-wakeup", "native-neighbor-faces",
                                "reusable-coarse-column-runs", "direct-section-destination", "bounded-face-cache",
                                "content-aware-publication", "local-terrain-invalidation", "independent-snapshot-shards", "feature-bounds-index", "projected-tile-ownership", "mixed-column-footprints", "batched-gc-observation", "incremental-plan-candidates",
                                "coordinate-surface-invalidation", "slot-local-cold-deletion", "allocation-free-column-clock",
                                "compact-neighbor-planes", "lazy-shared-page-index", "per-revision-detail-trace", "display-parent-reduction", "stable-terrain-publication",
                                "precise-final-voxel-notification", "paged-complete-vegetation-cache",
                                "deduplicated-fast-voxel-save", "reclaim-preserves-parent-inputs",
                                "shared-tile-state-pages", "reused-scheduling-facts", "packed-vegetation-display",
                                "reusable-material-workspace", "bounded-section-boundaries", "reused-notification-faces",
                                "partial-vegetation-lod-union", "complete-surface-eviction-fallback",
                                "active-projected-sample-demand", "zoom-sensitive-view-refresh", "prediction-horizon-mask",
                                "indexed-horizon-invalidation",
                                "off-lock-publication-evidence", "shared-horizontal-change-evidence", "spatial-cache-invalidation",
                                "asynchronous-native-demand-handoff", "bulk-exact-section-signatures", "bounded-middle-cover", "first-terrain-priority", "bounded-cache-probe-wait", "offscreen-retirement-without-generation"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--platform", choices=["neoforge", "forge"], required=True)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--source", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.jar, args.platform, args.build_id, args.source), indent=2))
