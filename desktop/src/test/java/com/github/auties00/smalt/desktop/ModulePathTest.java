package com.github.auties00.smalt.desktop;

import com.github.auties00.smalt.desktop.probe.DesktopProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The suite itself runs on the class path; this runs DesktopProbe in a fresh JVM with every Smalt
// artifact of the test class path either on the module path or on the class path, so the
// provider registrations (module-info provides and META-INF/services) are checked independently.
@DisplayName("Provider registration in a fresh JVM")
class ModulePathTest {
    static List<String> smaltEntries() {
        var result = new ArrayList<String>();
        for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            try {
                var smalt = ModuleFinder.of(Path.of(entry)).findAll().stream()
                        .anyMatch(module -> module.descriptor().name().startsWith("com.github.auties00.smalt"));
                if (smalt) {
                    result.add(entry);
                }
            } catch (FindException exception) {
                // not a module: the test classes, the test kit and the test libraries
            }
        }
        return result;
    }

    static String probeClasses() throws URISyntaxException {
        return Path.of(DesktopProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"module path", "class path"})
    @DisplayName("ImageIO and Java Sound find the providers")
    void probe(String mode) throws Exception {
        var entries = smaltEntries();
        assertTrue(entries.size() >= 5, "Smalt modules on the test class path: " + entries);
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-Djava.awt.headless=true"));
        if (mode.equals("module path")) {
            command.addAll(List.of("--module-path", String.join(File.pathSeparator, entries),
                    "--add-modules", "ALL-MODULE-PATH", "-cp", probeClasses()));
        } else {
            var classPath = new ArrayList<>(entries);
            classPath.add(probeClasses());
            command.addAll(List.of("-cp", String.join(File.pathSeparator, classPath)));
        }
        command.add(DesktopProbe.class.getName());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the probe did not finish");
        assertEquals(0, process.exitValue(), output);
        var lines = output.lines().toList();
        assertTrue(lines.contains("reader=com.github.auties00.smalt.desktop.imageio.SmaltImageReader"), output);
        assertTrue(lines.contains(mode.equals("module path")
                ? "reader-module=com.github.auties00.smalt.desktop"
                : "reader-module=null"), output);
        assertTrue(lines.contains("webp-write=true"), output);
        assertTrue(lines.contains("webp-read=16x12"), output);
        assertTrue(lines.contains("sound-encoding=OPUS"), output);
        assertTrue(lines.contains("sound-frames=48000"), output);
    }
}
