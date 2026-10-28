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
        major = 65 if platform == "neoforge" else 61

        def check_class(name, *symbols):
            data = archive.read("dev/xantha/vss/" + name + ".class")
            require(int.from_bytes(data[6:8], "big") == major, "Wrong Java version: " + name)
            for symbol in symbols:
                require(symbol.encode() in data, "Missing reference: " + name + ": " + symbol)
            return data

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
        check_class("networking/client/VSSClientNetworking", "onClientLevelUnload")
        check_class("compat/VoxyCompat$LocalSectionIndex", "settledRegions")
        check_class("compat/VoxyIngestTracker", "realColumnSettled")
        check_class("compat/VoxyPredictionBridge$Context", "hasSkyLight")
        check_class("client/prediction/PredictionVoxySource", "lightingBuilds")
        check_class("client/prediction/PredictionVoxyLighting", "propagate")
        check_class("client/prediction/PredictionVoxelCache", ":floating-columns-r1")
        check_class("client/prediction/PredictionExteriorColumns", "enrichFloating")
        check_class("compat/VoxySubdivisionWaiters", "needsSamples")
        check_class("client/prediction/PredictionInputSnapshots$TrackedSet", "changed", "shards", "ShardSet")
        check_class("client/prediction/PredictionInputSnapshots$TrackedMap", "changed", "shards", "ShardMap")
        shards = check_class("client/prediction/PredictionInputSnapshots", "shard")
        require(bytes.fromhex("0385ebca6b") in shards and bytes.fromhex("03c2b2ae35") in shards,
                "Missing independent shard hash mixing constants")
        check_class("client/prediction/PredictionVoxyFeatures", "intersect", "visit", "quickSort")
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
        check_class("mixin/voxy/PredictionVoxyNeighborSourceMixin", "copyNeighborFaces", "VoxyFluidSurface", "vss$packFluidHeight", "vss$liquidTransitions")

        check_class("compat/VoxyFluidSurface", "top", "pack", "transitions", "meshTransitions", "LiquidBlock")
        check_class("compat/VoxySurfaceHeights", "// VSS liquid surface remainder", "vssLiquidDrop")
        check_class("client/prediction/PredictionVoxySource", "VoxyFluidSurface")
        check_class("mixin/voxy/StrictVoxyVisibilityMixin", "observeUnifiedView")
        check_class("client/prediction/PredictionRenderer", "observeUnifiedView", "width", "height")
        check_class("client/prediction/PredictionViewObservation", "height", "selectionScale")
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
    return {"jar": jar.name, "platform": platform, "buildId": build_id,
            "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "bytes": jar.stat().st_size, "nativeSha256": natives,
            "integratedFixes": ["auxiliary-viewport-observer-guard", "native-detail-demand", "authoritative-viewport", "spatial-bootstrap-only", "liquid-surface-remainders", "native-liquid-transition-walls", "native-lod-topology", "world-space-model-inset", "nether-lighting",
                                "end-floating-columns", "empty-node-sample-readmission",
                                "dimension-transition", "real-air-authority",
                                "incremental-snapshots", "scheduled-range-pruning",
                                "frontier-blocker-probe", "empty-child-wakeup", "native-neighbor-faces",
                                "reusable-coarse-column-runs", "direct-section-destination", "bounded-face-cache",
                                "content-aware-publication", "local-terrain-invalidation", "independent-snapshot-shards", "feature-bounds-index", "projected-tile-ownership", "mixed-column-footprints", "batched-gc-observation", "incremental-plan-candidates"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--platform", choices=["neoforge", "forge"], required=True)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--source", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.jar, args.platform, args.build_id, args.source), indent=2))
