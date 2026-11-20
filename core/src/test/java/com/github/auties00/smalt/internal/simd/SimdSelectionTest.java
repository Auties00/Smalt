package com.github.auties00.smalt.internal.simd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the rules that gate the vector kernels before the hardware checks: the {@code smalt.simd}
 * property, the Windows opt-in and the x86 architecture names.
 */
class SimdSelectionTest {
    @Test
    @DisplayName("smalt.simd=false turns the vector kernels off on every platform")
    void propertyOff() {
        assertFalse(Simd.permitted("false", "Linux"));
        assertFalse(Simd.permitted("FALSE", "Mac OS X"));
        assertFalse(Simd.permitted("false", "Windows 11"));
    }

    @Test
    @DisplayName("an unset or other property allows the vector kernels outside Windows")
    void defaultOutsideWindows() {
        assertTrue(Simd.permitted(null, "Linux"));
        assertTrue(Simd.permitted("true", "Mac OS X"));
        assertTrue(Simd.permitted("yes", "FreeBSD"));
    }

    @Test
    @DisplayName("Windows runs the scalar kernels unless smalt.simd is true")
    void windowsOptIn() {
        assertFalse(Simd.permitted(null, "Windows Server 2025"));
        assertFalse(Simd.permitted("yes", "Windows 11"));
        assertTrue(Simd.permitted("true", "Windows Server 2025"));
        assertTrue(Simd.permitted("TRUE", "Windows 10"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amd64", "x86_64", "x86", "i386", "i486", "i586", "i686"})
    @DisplayName("recognizes the x86 architecture names")
    void x86(String arch) {
        assertTrue(Simd.isX86(arch));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aarch64", "arm", "riscv64", "ppc64le", "s390x", ""})
    @DisplayName("does not take other architectures for x86")
    void notX86(String arch) {
        assertFalse(Simd.isX86(arch));
    }
}
