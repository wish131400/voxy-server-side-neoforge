package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.client.VSSVoxyOptionsIntegration;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real dependency types and page builders; UI initialization stays outside this test. */
class SodiumEntryRecoveryTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void legacyEntrySelectsItsPageWithoutBuildingWidgetsBeforeInit() throws Exception {
        boolean factory = Boolean.getBoolean("vss.sodiumFactory");
        Class type = Class.forName((factory ? "net.caffeinemc" : "me.jellysquid")
                + ".mods.sodium.client.gui.SodiumOptionsGUI");
        Screen parent = mock(Screen.class);
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            if (factory) {
                Screen screen = (Screen) mock(type);
                pagesField(type).set(screen, new ArrayList<>());
                var create = type.getMethod("createScreen", Screen.class);
                try (var sodium = mockStatic(type)) {
                    sodium.when(() -> create.invoke(null, parent)).thenReturn(screen);
                    assertSame(screen, VSSVoxyOptionsIntegration.createSodiumConfigScreen(parent));
                    verifyPagesAndSelection(type, screen);
                    verifyNoInteractions(screen);
                }
            } else {
                try (var construction = mockConstruction(type, (screen, context) ->
                        pagesField(type).set(screen, new ArrayList<>()))) {
                    Screen result = VSSVoxyOptionsIntegration.createSodiumConfigScreen(parent);
                    assertEquals(1, construction.constructed().size());
                    assertSame(construction.constructed().get(0), result);
                    verifyPagesAndSelection(type, result);
                    verifyNoInteractions(result);
                }
            }
        }
    }

    private static void verifyPagesAndSelection(Class<?> type, Object screen) throws Exception {
        List<?> pages = (List<?>) pagesField(type).get(screen);
        assertEquals(2, pages.size(), "Both real option pages must be registered");
        int count = 0;
        for (Object page : pages) {
            List<?> options = (List<?>) page.getClass().getMethod("getOptions").invoke(page);
            assertFalse(options.isEmpty(), "Actual dependency controls must be constructed");
            count += options.size();
        }
        assertTrue(count >= 10);
        Field selected = type.getDeclaredField("currentPage");
        selected.setAccessible(true);
        assertSame(pages.get(0), selected.get(screen));
        VSSVoxyOptionsIntegration.addPage(screen);
        assertEquals(2, pages.size(), "Repeated entry hooks must not duplicate pages");
    }

    private static Field pagesField(Class<?> type) throws Exception {
        Field pages = type.getDeclaredField("pages");
        pages.setAccessible(true);
        return pages;
    }
}
