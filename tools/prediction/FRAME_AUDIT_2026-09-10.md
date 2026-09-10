# Sustained low frame rate audit

Recording: build/frame-audit-20260910.jfr (35-second JFR profile, live process 40848).
Report: build/frame-audit-report.txt. Thread snapshot: build/frame-audit-threads.txt.
The user reported sustained low frame rate during this recording.

## Observations

- 1715 render-thread execution/native samples; 527 include PredictionLodSeams.update
  (30.7%), 239 include sameSurface (13.9%), 139 include Index.at (8.1%).
  Inclusive percentages overlap and are not measured frame durations.
- Render-thread allocation counter delta: 2864 MiB during the recording's
  allocation-statistics window. Weighted samples attribute about 604 MiB to
  WallIndex construction and 250 MiB to stitch neighbor arrays.
- 16 GC pause events total 241 ms; maximum 38.5 ms; nine exceeded 16.67 ms.
- Mean process CPU 38.6%, machine CPU 49.1%. Separate instantaneous GPU reading:
  RTX 4070 Ti SUPER at 39%, 4125/16376 MiB. These do not prove GPU frame time.
- Automatic real-terrain generation at 128 chunks was enabled in the game log.
  Prediction generation, decoration and coverage scans were active concurrently.

## Changes

- Canonicalize unchanged tile/coverage inputs once per update. Neighbor validation
  uses identity instead of comparing a 4096-element coverage array on every edge.
- Copy neighbor arrays only when a neighbor actually changes.
- Expand sorted LOD levels into arrays; neighbor lookup no longer allocates a
  TreeMap iterator on each edge.
- Use primitive long wall-plane keys to avoid boxing.
- Retain immutable wall indices across coverage updates with an LRU limit of
  64 tiles and 262144 source quads. Remove stale/replaced tiles and clear on reset.
- Preserve heights, materials, depth tests and seam ownership.

Targeted seam, cache invalidation and normal/Iris GPU tests passed, including
both height orders and 70/7-degree FOV. Output: build/seam-optimization-tests.log.
An actual before/after FPS comparison requires restarting with the new JAR;
the live JVM still runs the previous classes. No FPS improvement is claimed yet.
