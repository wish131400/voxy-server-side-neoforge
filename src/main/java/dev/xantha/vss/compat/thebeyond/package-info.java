/**
 * <p><b>职责：</b>The mixin config plugin of vss.thebeyond.mixins.json and the ClassNode patch it applies to The Beyond's merged StructureStart.placeInChunk handler.
 * <p><b>依赖：</b>JDK, ASM, Mixin and FML only; never a game class. Kept outside every mixin package so Mixin may load it after the packages are registered.
 * <p><b>线程：</b>Runs on the class transformation thread; the patch works on the one ClassNode it is given.
 * <p><b>生命周期：</b>Decided once when StructureStart is transformed; no runtime state.
 */
package dev.xantha.vss.compat.thebeyond;
