package dev.xantha.vss.compat;

/** Section face order: -X,+X,-Y,+Y,-Z,+Z; each plane contains 1024 cells. */
public final class VoxyFaceData {
    public static volatile boolean nativeLayoutVerified;
    private VoxyFaceData() { }
    public static int index(int face,int i) {
        return switch(face>>1) {
            case 0 -> (i<<5) | (face&1)*31;
            case 1 -> i | (face&1)*31<<10;
            case 2 -> (i&31) | (i>>5)<<10 | (face&1)*31<<5;
            default -> throw new IllegalArgumentException("face");
        };
    }
}
