package dev.xantha.vss.client;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

public final class VSSEmbeddiumOptionsEventBridge {
    private static final String OPTION_GUI_CONSTRUCTION_EVENT = "org.embeddedt.embeddium.api.OptionGUIConstructionEvent";

    private VSSEmbeddiumOptionsEventBridge() {
    }

    public static void register() {
        if (!classExists(OPTION_GUI_CONSTRUCTION_EVENT)) {
            return;
        }
        try {
            Class<?> eventClass = Class.forName(OPTION_GUI_CONSTRUCTION_EVENT);
            Field busField = eventClass.getField("BUS");
            Object bus = busField.get(null);
            Method addListener = findAddListener(bus.getClass());
            if (addListener == null) {
                VSSLogger.warn("Embeddium options event bus did not expose a compatible listener hook");
                return;
            }
            Consumer<Object> listener = VSSEmbeddiumOptionsEventBridge::onOptionsGuiConstruction;
            addListener.invoke(bus, listener);
            VSSLogger.info("Registered Embeddium options event bridge for VSS");
        } catch (Throwable e) {
            VSSLogger.warn("Failed to register Embeddium options event bridge", e);
        }
    }

    private static void onOptionsGuiConstruction(Object event) {
        try {
            Object pages = event.getClass().getMethod("getPages").invoke(event);
            if (pages instanceof List<?> list) {
                VSSVoxyOptionsIntegration.addPage(list);
            }
        } catch (Throwable e) {
            VSSLogger.warn("Failed to hook VSS options into Embeddium options", e);
        }
    }

    private static Method findAddListener(Class<?> type) {
        for (Method method : type.getMethods()) {
            if ("addListener".equals(method.getName())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(Consumer.class)) {
                return method;
            }
        }
        return null;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, VSSEmbeddiumOptionsEventBridge.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
