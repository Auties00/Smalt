package com.github.auties00.smalt.capture.internal;

import java.util.Locale;
import java.util.Set;

/**
 * The operating systems the capture backends support, resolved once from the {@code os.name} and
 * {@code os.arch} system properties.
 */
public enum Platform {
    /**
     * Windows, on any architecture the JDK runs on.
     */
    WINDOWS,

    /**
     * macOS, on any architecture the JDK runs on.
     */
    MACOS,

    /**
     * Linux on a 64-bit architecture whose ABI is LP64, which the Video4Linux2, X11 and ALSA
     * bindings assume: x86-64, AArch64 or RISC-V 64.
     */
    LINUX,

    /**
     * Any other operating system or architecture, where no device is found and none opens.
     */
    UNSUPPORTED;

    /**
     * The values of {@code os.arch} of the {@link #LINUX} architectures.
     */
    private static final Set<String> LINUX_ARCHITECTURES = Set.of("amd64", "x86_64", "aarch64", "riscv64");

    private static final Platform CURRENT = detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));

    /**
     * {@return the platform of the running JVM}
     */
    public static Platform current() {
        return CURRENT;
    }

    /**
     * {@return the name of the running operating system, as reported in failures}
     */
    public static String osName() {
        return System.getProperty("os.name", "unknown");
    }

    /**
     * Resolves the platform of an operating system and architecture.
     *
     * @param osName the value of {@code os.name}
     * @param osArch the value of {@code os.arch}
     * @return the platform
     */
    static Platform detect(String osName, String osArch) {
        var os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return WINDOWS;
        }
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return MACOS;
        }
        if (os.startsWith("linux") && LINUX_ARCHITECTURES.contains(osArch.toLowerCase(Locale.ROOT))) {
            return LINUX;
        }
        return UNSUPPORTED;
    }
}
