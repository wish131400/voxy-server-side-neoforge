package dev.xantha.vss.compat;

/** Covers Voxy's gap between decrementing its work count and publishing GPU updates. */
public interface StrictVoxyWorkState {
    boolean vss$isPublishing();
}
