package dev.xantha.vss.common;

import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.config.VSSServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VSSLogger {
    private static final Logger LOG = LoggerFactory.getLogger("VSS");

    private VSSLogger() {
    }

    public static void debug(String msg) {
        if (!isDebugEnabled()) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug(msg);
        } else {
            LOG.info("[debug] " + msg);
        }
    }

    public static boolean isDebugEnabled() {
        try {
            return VSSClientConfig.CONFIG.debugLogging || VSSServerConfig.CONFIG.debugLogging || LOG.isDebugEnabled();
        } catch (Throwable ignored) {
            return LOG.isDebugEnabled();
        }
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
