package dev.xantha.vss.compat;

import java.util.ArrayList;
import java.util.List;

/** Shared ordered event sink for the xaero.map.* Tier-1 stubs — pins the cross-object
 *  commit-sequence ORDER (worldInterpretationVersion before setTile, setBeingWritten
 *  before requestLoad, flag-then-consume buffers), which per-object recorders cannot. */
public final class XaeroStubEvents {
    private static final List<String> EVENTS = new ArrayList<>();

    private XaeroStubEvents() {}

    public static synchronized void record(String event) { EVENTS.add(event); }

    public static synchronized List<String> snapshot() { return new ArrayList<>(EVENTS); }

    public static synchronized void clear() { EVENTS.clear(); }
}


