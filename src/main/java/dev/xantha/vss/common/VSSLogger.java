package dev.xantha.vss.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VSSLogger {
    private static final Logger LOG = LoggerFactory.getLogger("VSS");

    private VSSLogger() {
    }

    public static void debug(String msg) {
        LOG.debug(msg);
    }

    public static void info(String msg) {
        LOG.info(msg);
    }

    public static void warn(String msg) {
        LOG.warn(msg);
    }

    public static void warn(String msg, Throwable t) {
        LOG.warn(msg, t);
    }

    public static void error(String msg, Throwable t) {
        LOG.error(msg, t);
    }
}
