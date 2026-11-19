package com.github.auties00.smalt;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.testkit.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MediaRuntime")
class MediaRuntimeTest {
    private static final boolean X86 = switch (System.getProperty("os.arch", "")) {
        case "amd64", "x86_64", "x86", "i386", "i486", "i586", "i686" -> true;
        default -> false;
    };

    /** Whether the build caps the child JVMs below AVX2. */
    private static final boolean BELOW_AVX2 = ChildJvm.cpuArgs().contains("-XX:UseAVX=0")
            || ChildJvm.cpuArgs().contains("-XX:UseAVX=1");

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
        // MaxVectorSize=16 gives an x86 JVM the 128-bit vectors of ARM NEON; a child below AVX2 (Windows CI runs
        // its JVMs with -XX:UseAVX=0 for JDK-8365196) runs scalar instead
        assertEquals(BELOW_AVX2 ? "VECTORIZED=false BITS=0 WIDE=false" : "VECTORIZED=true BITS=128 WIDE=false",
                probe("-Dsmalt.simd=true", "-XX:MaxVectorSize=16"));
    }

    @ParameterizedTest(name = "UseAVX={0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("an x86 JVM below AVX2 runs the scalar kernels, since C2 cannot compile all the vector ones there")
    void belowAvx2(int level) throws Exception {
        if (X86) {
            assertEquals("VECTORIZED=false BITS=0 WIDE=false", probe("-Dsmalt.simd=true", "-XX:UseAVX=" + level));
        }
    }

    @Test
    @DisplayName("without jdk.management, an x86 JVM takes 256-bit preferred vectors as its sign of AVX2")
    void withoutManagement() throws Exception {
        var limited = probe("-Dsmalt.simd=true", "--limit-modules", "java.base,jdk.incubator.vector");
        var managed = probe("-Dsmalt.simd=true");
        // without the flag reader, the selection equals the one made with it whenever the preferred width is 256 or
        // more, and a 128-bit x86 JVM runs scalar
        if (!X86 || managed.contains("BITS=256") || managed.contains("BITS=512")) {
            assertEquals(managed, limited);
        } else {
            assertEquals("VECTORIZED=false BITS=0 WIDE=false", limited);
        }
        if (X86) {
            assertEquals("VECTORIZED=false BITS=0 WIDE=false",
                    probe("-Dsmalt.simd=true", "--limit-modules", "java.base,jdk.incubator.vector", "-XX:UseAVX=0"));
        }
    }

    @Test
    @DisplayName("without smalt.simd, selects the scalar kernels on Windows and the Vector API ones elsewhere")
    void defaultSelection() throws Exception {
        var result = probe();
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            assertEquals("VECTORIZED=false BITS=0 WIDE=false", result);
        } else {
            assertTrue(result.startsWith("VECTORIZED=true "), result);
        }
    }

    @Test
    @DisplayName("smalt.simd=false selects the scalar kernels on every platform")
    void disabled() throws Exception {
        assertEquals("VECTORIZED=false BITS=0 WIDE=false", probe("-Dsmalt.simd=false"));
    }

    /** Runs {@link SelectionProbe} in a child JVM that resolves the incubator module, with the given options. */
    private static String probe(String... options) throws Exception {
        var classpath = System.getProperty("surefire.test.class.path", Path.of("target", "test-classes").toAbsolutePath()
                + File.pathSeparator + Path.of("target", "classes").toAbsolutePath());
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-modules", "jdk.incubator.vector", "-Xmx64m"));
        command.addAll(ChildJvm.cpuArgs());
        command.addAll(List.of(options));
        command.addAll(List.of("-cp", classpath, SelectionProbe.class.getName()));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child did not finish: " + output);
        assertEquals(0, process.exitValue(), output);
        return output.lines().filter(line -> line.startsWith("VECTORIZED=")).findFirst().orElse(output);
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
