/**
 * <p><b>职责：</b>The Beyond's StructureStart.placeInChunk handler: prediction levels with a known dimension take its End or not-End branch instead of calling level.getLevel().
 * <p><b>依赖：</b>Optional; applied only when The Beyond is installed, its merged handler is found by Mixin metadata and exact instruction shape, otherwise left untouched with one error.
 * <p><b>线程：</b>The guard reads the level it is given; no shared state.
 * <p><b>生命周期：</b>Decided once when StructureStart is transformed; no runtime state.
 */
package dev.xantha.vss.mixin.thebeyond;
