package dev.xantha.vss.compat;

/** A worker retains one face buffer; nested calls borrow a separate temporary buffer. */
final class VoxyNotificationScratch implements AutoCloseable {
    private static final ThreadLocal<VoxyNotificationScratch> LOCAL=ThreadLocal.withInitial(VoxyNotificationScratch::new);
    final long[][] faces=new long[6][1024];
    private boolean borrowed;
    static VoxyNotificationScratch borrow() {
        var scratch=LOCAL.get();
        if(scratch.borrowed)scratch=new VoxyNotificationScratch();
        scratch.borrowed=true;return scratch;
    }
    @Override public void close(){borrowed=false;}
}
