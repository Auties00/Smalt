package com.github.auties00.smalt.capture.internal.macos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TapProbeExperiment {
    private static void script(String decision) throws IOException, InterruptedException {
        var process = new ProcessBuilder("bash", System.getenv("SMALT_TEST_TAP_PERMISSION_SCRIPT"), decision).inheritIO().start();
        assertEquals(0, process.waitFor(), "the permission script failed");
    }

    private static void probe(String decision) throws IOException, InterruptedException {
        var classPath = String.join(File.pathSeparator, Path.of("target/test-classes").toAbsolutePath().toString(),
                Path.of("target/classes").toAbsolutePath().toString(), System.getProperty("surefire.test.class.path", ""),
                System.getProperty("jdk.module.path", ""), System.getProperty("java.class.path", ""));
        var launcher = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        script(decision);
        var child = new ProcessBuilder(launcher, "--enable-native-access=ALL-UNNAMED", "-cp", classPath, TapProbe.class.getName(), "BlackHole")
                .redirectErrorStream(true).start();
        var drain = Thread.ofPlatform().daemon().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                    System.out.println("EXPERIMENT " + decision + ": " + line);
                }
            } catch (IOException exception) {
                System.out.println("EXPERIMENT " + decision + ": " + exception);
            }
        });
        var ended = child.waitFor(200, TimeUnit.SECONDS);
        System.out.println("EXPERIMENT probe with decision " + decision + " ended: " + ended + (ended ? ", exit " + child.exitValue() : ""));
        child.destroyForcibly();
        drain.join(2_000);
    }

    @Test
    @Timeout(300)
    @DisplayName("a new process's tap while the permission is refused")
    void probeRefused() throws IOException, InterruptedException {
        probe("0");
    }

    @Test
    @Timeout(300)
    @DisplayName("a new process's tap while the permission was never asked")
    void probeUnasked() throws IOException, InterruptedException {
        probe("none");
    }
}
