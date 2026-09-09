package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VssNativePlatformTest {
    @Test void targetNamesHandleAliasesWithoutLoadingAnotherOperatingSystemsBinary() {
        assertEquals("windows-x86_64", VssNativePlatform.resolve("Windows 11", "amd64").id());
        assertEquals("linux-x86_64", VssNativePlatform.resolve("Linux", "x86_64").id());
        assertEquals("linux-aarch64", VssNativePlatform.resolve("Linux", "aarch64").id());
        assertEquals("macos-aarch64", VssNativePlatform.resolve("Darwin", "arm64").id());
        assertEquals("macos-x86_64", VssNativePlatform.resolve("Mac OS X", "x64").id());
        assertNull(VssNativePlatform.resolve("Windows 11", "arm64"));
        assertNull(VssNativePlatform.resolve("FreeBSD", "amd64"));
        assertNull(VssNativePlatform.resolve("Linux", "riscv64"));
    }

    @Test void allPackagedTargetsHaveTheirOwnNativeContainerAndArchitecture() throws Exception {
        for (String os : new String[]{"Windows 11", "Linux", "Mac OS X"}) {
            for (String arch : new String[]{"amd64", "aarch64"}) {
                var platform = VssNativePlatform.resolve(os, arch);
                if (platform == null) continue;
                try (var input = getClass().getClassLoader().getResourceAsStream(platform.resource())) {
                    assertTrue(platform.fileName().contains("vss_native_core"));
                    assertNotNull(input, platform.resource());
                    byte[] data = input.readAllBytes();
                    var bytes = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    String hex = java.util.HexFormat.of().formatHex(data, 0, 4);
                    if (os.startsWith("Windows")) assertTrue(hex.startsWith("4d5a"));
                    else if (os.equals("Linux")) assertEquals("7f454c46", hex);
                    else assertEquals("cffaedfe", hex);
                    boolean arm = arch.equals("aarch64");
                    if (os.startsWith("Windows")) assertEquals(0x8664, Short.toUnsignedInt(bytes.getShort(bytes.getInt(0x3c) + 4)));
                    else if (os.equals("Linux")) {
                        assertEquals(2, data[4]);
                        assertEquals(1, data[5]);
                        assertEquals(arm ? 183 : 62, bytes.getShort(18));
                    } else assertEquals(arm ? 0x100000c : 0x1000007, bytes.getInt(4));
                    assertTrue(data.length > 500_000);
                }
            }
        }
    }
}
