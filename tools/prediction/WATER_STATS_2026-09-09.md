# Water handoff and client statistics

The installed game's `latest.log` records an `ArrayIndexOutOfBoundsException` in
`ClientPredictionState.diagnostics`: the aggregation visited 20 legacy LOD levels
while a manager returned nine counts. Statistics now grow the aggregate to each
manager's actual count length and read only that manager's valid indices. The
regression calls the complete diagnostics method with no managers, different
dimension layouts, and after removing the larger layout.

The user reports that underwater flashing disappears when prediction is disabled.
That establishes an association with prediction rendering; a screenshot alone does
not establish that every visible artifact has the same cause.

The production OpenGL shader regression reproduced coplanar fluid ownership errors
after extending the existing water test to low altitude, grazing views, and actual
main-target depth inside the vanilla far plane. The old fixed view-depth bias
failed on slightly changed high-altitude views. Correcting depth quantization alone
still left 1,104 of 2,304 interior pixels incorrectly drawing duplicate water in
a 7-degree FOV grazing case at camera height 128.

Fluid comparisons now convert the existing 0.02-block tie tolerance from the face
normal into view depth. The original Voxy depth recovery additionally uses the near
end of a two-depth-bin interval to cover stored-depth rounding and shader arithmetic.
Solid-terrain coverage policy and water geometry/alpha are unchanged. The normal
distance correction also applies to the Iris fragment path.

The expanded water regression covers heights 128, 181.07, 4700, 4701.37, and 8191.3;
FOV 70 and 7; overhead and low-altitude oblique views; coplanar water, a bed four
blocks below, and a surface 4,000 blocks below. All 42 cases pass: zero duplicate
interior pixels for coplanar water, and all 2,304 checked interior pixels retain
foreground water for both distinct background depths. The final fixture uses a
131,072-block Voxy far plane so the deep background remains inside its projection
even at grazing angles.

GPU tests execute on the local NVIDIA RTX 4070 Ti SUPER using the production shader
and framebuffer classes. They do not constitute an in-game visual verification of
the user's scene, or hardware testing on AMD/Intel/macOS/Linux.

Validation logs: `water-stats-targeted.log`, `water-stats-full.log`.

Final full run: 567 tests, zero failures/errors/skips. The package audit confirms
all five native targets and their 30 JNI exports. No Rust binary rebuild was needed.
Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 4,138,352 bytes,
SHA-256 `e679d739f2bbcd1fc4f3a3241c03cc0e17021146908fe4f4b2aadf4267ae8dbe`.
