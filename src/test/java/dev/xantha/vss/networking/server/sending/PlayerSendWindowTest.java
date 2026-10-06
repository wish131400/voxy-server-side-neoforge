package dev.xantha.vss.networking.server.sending;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlayerSendWindowTest {
    @Test
    void delayedEncoderCannotDrainTheWholeVssQueue() {
        PlayerSendWindow window = new PlayerSendWindow();
        Runnable first = window.acquire(256 * 1024);
        Runnable second = window.acquire(256 * 1024);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(window.acquire(1));
        first.run();
        first.run();
        assertEquals(256 * 1024, window.pendingBytes());
        assertNotNull(window.acquire(1));
    }

    @Test
    void tinyPacketsAreBoundedAndAnIndivisibleLargePartCanMakeProgress() {
        PlayerSendWindow window = new PlayerSendWindow();
        Runnable[] completed = new Runnable[8];
        for (int i = 0; i < 8; i++) completed[i] = window.acquire(1);
        assertNull(window.acquire(1));
        for (Runnable release : completed) release.run();
        Runnable oversized = window.acquire(1024 * 1024);
        assertNotNull(oversized);
        assertNull(window.acquire(1));
        oversized.run();
        assertEquals(0, window.pendingBytes());
    }
}
