package com.github.auties00.smalt.capture.internal.macos;

import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.util.Locale;

/**
 * Answers the architecture questions the Objective-C calling conventions depend on.
 *
 * <p>macOS runs on arm64 (Apple silicon) and x86_64 (Intel, or Rosetta 2 for an x86_64 JVM). The
 * two differ in how a message returning a large structure is sent and in the encoding of
 * {@code BOOL}.
 */
final class MacPlatform {
    /**
     * Whether the JVM runs on arm64.
     */
    static final boolean ARM64 = isArm64(System.getProperty("os.arch", ""));

    /**
     * The largest structure the x86_64 System V ABI returns in registers.
     */
    private static final long X86_64_REGISTER_RETURN = 16;

    /**
     * Prevents instantiation.
     */
    private MacPlatform() {
        throw new AssertionError();
    }

    /**
     * Returns whether an {@code os.arch} value names arm64.
     *
     * @param osArch the value of the {@code os.arch} system property
     * @return whether it is {@code aarch64} or {@code arm64}
     */
    static boolean isArm64(String osArch) {
        var arch = osArch.toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    /**
     * Returns whether a message returning a layout must be sent with
     * {@code objc_msgSend_stret} rather than {@code objc_msgSend}.
     *
     * <p>On x86_64 a structure larger than 16 bytes is returned through a hidden pointer passed
     * as the first argument, which {@code objc_msgSend_stret} expects; on arm64 the hidden pointer
     * travels in {@code x8} and {@code objc_msgSend} handles every return type (the
     * {@code _stret} variant does not exist there).
     *
     * @param arm64  whether the process runs on arm64
     * @param result the return layout, or {@code null} for {@code void}
     * @return whether the {@code _stret} entry point is required
     */
    static boolean needsStructReturn(boolean arm64, MemoryLayout result) {
        return !arm64 && result instanceof GroupLayout && result.byteSize() > X86_64_REGISTER_RETURN;
    }
}
