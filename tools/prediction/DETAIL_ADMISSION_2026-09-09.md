# Fine terrain admission and preview progress

The user confirmed quick first coverage but very slow subsequent refinement. A read-only thread snapshot from the running JDK 25 game found all eight prediction workers executing `RustWorldgenBackend.surfacePoints`; each had consumed roughly 365 CPU seconds over 370 elapsed seconds. This is active native computation, not Java lock waiting. The snapshot alone does not imply one JNI call lasted 370 seconds.

A later read-only snapshot of the existing prediction profile and manager diagnostics found:

- 194 full terrain tiles, 243 tiles with fewer than 64 cells, 97 completed surface/vegetation tiles; some sub-64 tiles may already meet their configured medium/outer-band target.
- 504 queued tasks and 1236 completed executor tasks.
- 4783622 accumulated worker milliseconds sampling, 566067 decoration, 1668 meshing, 353 packing.
- About 8272 MiB free heap, 1024 MiB active build reservations, zero immediately available builder slots.
- 401748 submitted sparse points and 2763 full native chunk loads.

This establishes progress with CPU saturation and a large queue, rather than a complete refinement deadlock. The source only limited simultaneous vegetation tasks; final terrain grids could still occupy every worker and prevent nearby intermediate grids from advancing.

`PredictionTileManager` now tracks final-terrain and vegetation work under one detail counter. When any planned tile still needs coverage/intermediate detail up to its 32-cell target, expensive refinement is admitted to at most half the executor workers (at least one). Jobs denied this slot return through normal cleanup, release memory/pending ownership, and can retry on the next plan update. They do not block a worker on a semaphore and do not enter failure backoff. First coverage and disk-restored terrain do not consume this lane. When preview work finishes, all workers can contribute to final detail. Sampling output, native libraries, final detail targets and transition gates are unchanged.

The two new fields in explicit stats are `detailActive` and `previewWorkPending`; no periodic logging was added. The source/test change is in the Codex worktree, not the Desktop checkout. The diagnostic Java attach helper in `tools/prediction/PredictionSnapshotAgent.java` only reads existing VSS profile/diagnostic objects and exports them to an explicitly supplied workspace directory; it does not transform classes or modify game fields. It is not included in the mod JAR.

Regression: block a final grid in a two-worker manager, queue another final grid and a preview, assert the preview publishes while the first final grid remains blocked, then release and verify deferred final detail reaches 64 cells without retry backoff or leaked detail slots. Existing first coverage, vegetation, near-first and telescope tests also pass in the targeted suite. Full-suite evidence is recorded in `build/detail-admission-full.log`; runtime snapshots remain in `build/live-prediction-snapshot` and `build/dev-comparison/current-threads.txt`.

This is a scheduling responsiveness fix, not a claim that total native terrain computation is faster or that game performance has already been verified with the new JAR.

Final validation: 571 tests, zero failures/errors/skips, including released-mod compatibility and GPU regressions. Packaged native audit passed for all five binaries. JAR size 4138825 bytes, SHA-256 `B7E4BB33D557633A0E695663006E85C659CB06DF4194AE7F51E368A4386A9021`. No Desktop checkout or installed game mod was overwritten.
