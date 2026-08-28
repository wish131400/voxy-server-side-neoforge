package xaero.map.region;

/** Tier-1 stub — intern is identity, but the call is recorded. */
public class OverlayManager {
    public int internCalls;

    public Overlay getOriginal(Overlay overlay) {
        this.internCalls++;
        return overlay;
    }
}


