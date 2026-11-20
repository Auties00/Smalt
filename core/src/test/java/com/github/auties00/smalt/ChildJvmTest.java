package com.github.auties00.smalt;

import com.github.auties00.smalt.testkit.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks that the test JVMs and their child JVMs run with the CPU-feature options of the build's windows-x64 profile. */
@DisplayName("ChildJvm")
class ChildJvmTest {
    private static final boolean WINDOWS_X64 = System.getProperty("os.name", "").startsWith("Windows")
            && System.getProperty("os.arch", "").equals("amd64");

    @Test
    @DisplayName("passes -XX:UseAVX=2 -XX:MaxVectorSize=16 to the child JVMs on Windows x64 and nothing elsewhere")
    void cpuArgs() {
        assertEquals(WINDOWS_X64 ? List.of("-XX:UseAVX=2", "-XX:MaxVectorSize=16") : List.of(), ChildJvm.cpuArgs());
    }

    @Test
    @DisplayName("a child JVM started with the options runs with UseAVX 2 and 16-byte vectors on Windows x64")
    void childUseAvx() throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(ChildJvm.cpuArgs());
        command.addAll(List.of("-XX:+PrintFlagsFinal", "-version"));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child did not finish: " + output);
        assertEquals(0, process.exitValue(), output);
        var useAvx = flag(output, "UseAVX");
        var maxVectorSize = flag(output, "MaxVectorSize");
        // printed so that a CI log shows the cap in effect
        System.out.println("child JVM " + String.join(" ", ChildJvm.cpuArgs()) + ": " + useAvx + "; " + maxVectorSize);
        if (WINDOWS_X64) {
            assertTrue(useAvx.matches("int\\s+UseAVX\\s+=\\s+2\\s.*"), useAvx);
            assertTrue(maxVectorSize.matches("intx\\s+MaxVectorSize\\s+=\\s+16\\s.*"), maxVectorSize);
        }
    }

    private static String flag(String output, String name) {
        return output.lines().map(String::strip).filter(line -> line.matches("\\w+\\s+" + name + "\\s+=.*"))
                .findFirst().orElse("");
    }
}
