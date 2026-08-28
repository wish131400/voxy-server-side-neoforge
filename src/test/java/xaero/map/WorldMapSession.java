package xaero.map;

/** Tier-1 stub of Xaero WM 1.45.0's session root (XaeroMapCompatTest — the
 *  real-package-name stub pattern, see MoonriseReadCompatTest). Members mirror
 *  exactly the surface XaeroMapCompat.Handles resolves. */
public class WorldMapSession {
    public static WorldMapSession current;
    public boolean usable = true;
    public MapProcessor processor;

    public static WorldMapSession getCurrentSession() { return current; }

    public boolean isUsable() { return this.usable; }

    public MapProcessor getMapProcessor() { return this.processor; }
}


