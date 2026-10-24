package com.github.auties00.smalt;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.internal.task.ParallelBands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MediaRuntime")
class MediaRuntimeTest {
    @Test
    @DisplayName("reports the kernel selection the module made")
    void vectorized() {
        assertEquals(Simd.ENABLED, MediaRuntime.isVectorized());
    }

    @Test
    @DisplayName("reports a vector width of at least 128 bits when vectorized and 0 otherwise")
    void vectorBits() {
        if (MediaRuntime.isVectorized()) {
            assertTrue(MediaRuntime.vectorBits() >= 128, "vector bits " + MediaRuntime.vectorBits());
        } else {
            assertEquals(0, MediaRuntime.vectorBits());
        }
    }

    @Test
    @DisplayName("selects the kernels written for 256-bit vectors exactly where the vectors are that wide")
    void wide() {
        assertEquals(Simd.ENABLED && Simd.preferredBits() >= 256, Simd.WIDE);
    }

    @Test
    @DisplayName("a JVM limited to 128-bit vectors keeps the Vector API kernels but not the 256-bit ones")
    void narrowJvm() throws Exception {
        var classpath = System.getProperty("surefire.test.class.path", Path.of("target", "test-classes").toAbsolutePath()
                + File.pathSeparator + Path.of("target", "classes").toAbsolutePath());
        // MaxVectorSize=16 gives an x86 JVM the 128-bit vectors of ARM NEON
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-modules", "jdk.incubator.vector", "-XX:MaxVectorSize=16", "-Xmx64m", "-cp", classpath,
                SelectionProbe.class.getName());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child did not finish: " + output);
        assertEquals(0, process.exitValue(), output);
        var result = output.lines().filter(line -> line.startsWith("VECTORIZED=")).findFirst().orElse(output);
        assertEquals("VECTORIZED=true BITS=128 WIDE=false", result);
    }

    /** Prints the kernel selection of its JVM. */
    static final class SelectionProbe {
        public static void main(String[] args) {
            System.out.println("VECTORIZED=" + MediaRuntime.isVectorized() + " BITS=" + MediaRuntime.vectorBits()
                    + " WIDE=" + Simd.WIDE);
        }
    }

    @Test
    @DisplayName("reports the parallelism of the shared pool, at least the calling thread")
    void parallelism() {
        assertEquals(ParallelBands.THREADS, MediaRuntime.parallelism());
        assertTrue(MediaRuntime.parallelism() >= 1);
    }
}
