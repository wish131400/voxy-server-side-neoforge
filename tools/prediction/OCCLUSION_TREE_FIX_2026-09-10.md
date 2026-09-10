# Prediction occlusion regression and fine vegetation

The previous screen-space ownership experiment discarded predicted solid fragments if a rendered Voxy point was within a horizontal radius and a 64-block vertical interval. A horizontal overlap is not proof of the same surface: it erases foreground ground in front of separate lower terrain. The existing GPU fixture failed at a 32-block separation after introducing that rule. It was incorrect to ship that failed test as unrelated.

Removed that added discard and its inverse-MVP uniform upload. Retained actual depth ordering, the original far-depth reconstruction, normal/Iris code, water depth tie handling, and the compiled real-terrain boundary work from other tasks. The same previously failing GPU fixture now passes. This removes the identified regression; it does not assert that all real/predicted height disagreements are resolved in game.

Fine vegetation (terrain spacing 1 or 2) previously adapted tree voxel size through 2/4/8 blocks once its estimated geometry exceeded 131072 vertices. This filled canopy gaps and thickened leaves even though the job had reached fine surface detail. Fine surface meshes now retain source BlockPos/BlockState data at one-block resolution. Only consecutive exposed vertical faces with identical state and orientation are merged before allocating triangles. Hidden faces, gaps, materials, cell ownership, terrain clipping, and texture repetition remain distinct. Coarser explicit LOD levels retain their previous reducer.

No terrain generation or native ABI change. Surface disk caches store original block placements, not these reduced cubes, so rebuilding meshes uses existing data without deleting caches. The hard mesh limit remains unchanged; pathological nonmergeable geometry is still subject to that existing limit.

Validation (18 tests, no failures/errors/skips):
- PredictionRenderTargetGpuTest: actual hidden OpenGL normal/Iris pipeline; foreground vs lower surfaces, co-planar ties, near/far depth, telescope FOV, water, frame reset/resize and existing boundary cases.
- PredictionVegetationTest: 14 placement/order/caching/ownership tests.
- PredictionVegetationRunsTest: 3 tests of exact face coverage, material boundaries, and dense canopy occupancy plus packed geometry.
- Dense fixture: 36864 leaf blocks. Unmerged vegetation alone would require 888192 vertices; new complete terrain+vegetation mesh uses 31104 vertices, preserving 1-block footprints. This is a synthetic correctness/size fixture, not an in-game FPS measurement.

Temporary build logs were removed during the requested build cleanup.
Test XML: build/reports/voxy-handoff/xml
Release JAR: build/libs/vss-0.3-neoforge-1.21.1.jar
SHA-256: 50fdf856bdd8148c2956ed089b2b94ed9a25b1931204bbe7193ef61231b4a631

The release artifact was checked to contain PredictionVegetationRuns and no ownershipDepth shader code. The duplicate delivery copy was removed after verifying identical SHA-256. It has not been installed into the user's mods folder. Screenshot-location verification remains a game-side check after replacement/restart.
