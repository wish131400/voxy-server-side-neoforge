package dev.xantha.vss.client;

import dev.xantha.vss.common.VSSLogger;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;

public final class VSSEmbeddiumOptionsEventBridge {
    private VSSEmbeddiumOptionsEventBridge() {
    }

    public static void register() {
        if (!classExists("org.embeddedt.embeddium.api.OptionGUIConstructionEvent")) {
            return;
        }
        try {
            OptionGUIConstructionEvent.BUS.addListener(VSSEmbeddiumOptionsEventBridge::onOptionsGuiConstruction);
            VSSLogger.info("Registered Embeddium options event bridge for VSS");
        } catch (Throwable e) {
            VSSLogger.warn("Failed to register Embeddium options event bridge", e);
        }
    }

    private static void onOptionsGuiConstruction(OptionGUIConstructionEvent event) {
        VSSVoxyOptionsIntegration.addPage(event.getPages());
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, VSSEmbeddiumOptionsEventBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
