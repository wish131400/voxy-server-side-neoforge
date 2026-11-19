package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionAcceptanceSettingsTest {
    @Test void backgroundCaptureRequiresExplicitProcessAndStillRejectsMenusAndPause() {
        var keys = java.util.List.of("vss.acceptance.enabled","vss.acceptance.runId","vss.acceptance.background");
        var before = new java.util.HashMap<String,String>();
        keys.forEach(key -> before.put(key,System.getProperty(key)));
        try {
            keys.forEach(System::clearProperty);
            assertTrue(PredictionAcceptanceSettings.gameplay(true,false,false,true));
            assertFalse(PredictionAcceptanceSettings.gameplay(true,false,false,false));
            System.setProperty("vss.acceptance.background","true");
            assertFalse(PredictionAcceptanceSettings.gameplay(true,false,false,false));
            System.setProperty("vss.acceptance.enabled","true");
            System.setProperty("vss.acceptance.runId","../world");
            assertFalse(PredictionAcceptanceSettings.enabled());
            System.setProperty("vss.acceptance.runId","nf-121-check");
            assertTrue(PredictionAcceptanceSettings.gameplay(true,false,false,false));
            assertFalse(PredictionAcceptanceSettings.gameplay(true,true,false,false));
            assertFalse(PredictionAcceptanceSettings.gameplay(true,false,true,false));
            assertFalse(PredictionAcceptanceSettings.gameplay(false,false,false,false));
            assertThrows(IllegalArgumentException.class,()->PredictionAcceptanceSettings.validateProbe("../bad",2,1));
            assertThrows(IllegalArgumentException.class,()->PredictionAcceptanceSettings.validateProbe("good",9,1));
        } finally {
            before.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
        }
    }
}
