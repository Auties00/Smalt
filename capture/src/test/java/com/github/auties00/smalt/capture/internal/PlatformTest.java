package com.github.auties00.smalt.capture.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("platform detection")
class PlatformTest {
    @ParameterizedTest(name = "{0} on {1} is {2}")
    @CsvSource({
            "Windows 11, amd64, WINDOWS",
            "Windows Server 2022, aarch64, WINDOWS",
            "Mac OS X, aarch64, MACOS",
            "Darwin, x86_64, MACOS",
            "Linux, amd64, LINUX",
            "Linux, aarch64, LINUX",
            "Linux, riscv64, LINUX",
            "Linux, x86, UNSUPPORTED",
            "Linux, arm, UNSUPPORTED",
            "FreeBSD, amd64, UNSUPPORTED",
            "'', '', UNSUPPORTED"
    })
    @DisplayName("maps os.name and os.arch to the backend's platform")
    void detect(String osName, String osArch, Platform expected) {
        assertEquals(expected, Platform.detect(osName, osArch));
    }
}
