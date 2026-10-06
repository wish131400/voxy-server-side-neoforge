package dev.xantha.vss.compat;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Observes an existing BO session; never creates sessions or changes its transport. */
public final class BandwidthOptimizerCompat {
    static final AttributeKey<Object> SESSION =
            AttributeKey.valueOf("bandwidthoptimizer:channel_transport_session");
    private static final ClassValue<MethodHandle> WAITING = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                return MethodHandles.publicLookup().findVirtual(type, "isOutboundStreamingEpochClosed",
                        MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class));
            } catch (ReflectiveOperationException e) {
                return MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, Object.class);
            }
        }
    };

    private BandwidthOptimizerCompat() {}

    public static boolean isWaitingForClient(Channel channel) {
        if (!channel.hasAttr(SESSION)) return false;
        Object session = channel.attr(SESSION).get();
        if (session == null) return false;
        try {
            return (boolean) WAITING.get(session.getClass()).invokeExact(session);
        } catch (Throwable e) {
            if (e instanceof Error error) throw error;
            return false;
        }
    }
}
