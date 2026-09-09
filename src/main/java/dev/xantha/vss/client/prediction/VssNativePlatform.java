package dev.xantha.vss.client.prediction;

import java.util.Locale;

/** Explicit packaged targets; unsupported systems use the Java sampler. */
record VssNativePlatform(String id, String fileName) {
    static VssNativePlatform current() {
        return resolve(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static VssNativePlatform resolve(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = switch (architecture.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
        if (arch == null) return null;
        // Darwin contains "win", so match macOS before Windows.
        if (os.contains("mac") || os.contains("darwin"))
            return new VssNativePlatform("macos-" + arch, "libvss_native_core.dylib");
        if (os.startsWith("windows") && arch.equals("x86_64"))
            return new VssNativePlatform("windows-" + arch, "vss_native_core.dll");
        if (os.contains("linux"))
            return new VssNativePlatform("linux-" + arch, "libvss_native_core.so");
        return null;
    }

    String resource() { return "META-INF/vss-natives/" + id + "/" + fileName; }
}
