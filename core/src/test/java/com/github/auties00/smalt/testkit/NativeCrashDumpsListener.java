package com.github.auties00.smalt.testkit;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Installs {@link NativeCrashDumps} in a Windows x64 test JVM whose environment names a dump directory, when JUnit
 * opens the launcher session.
 *
 * <p>JUnit finds the listener through {@code META-INF/services} of core's test classes and of the test jar, which the
 * other modules' tests load. After installing, the listener writes {@code listener-<pid>.txt} into the directory, so
 * that CI can check that the test JVMs installed it.
 */
public final class NativeCrashDumpsListener implements LauncherSessionListener {
    /**
     * Constructs the listener; JUnit's service loader calls it.
     */
    public NativeCrashDumpsListener() {
    }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        var directory = NativeCrashDumps.configured();
        if (directory == null || !NativeCrashDumps.supported()) {
            return;
        }
        try {
            NativeCrashDumps.install(directory);
            Files.writeString(directory.resolve("listener-" + ProcessHandle.current().pid() + ".txt"),
                    "native crash dumps installed" + System.lineSeparator());
        } catch (RuntimeException | IOException exception) {
            System.err.println("native crash dumps not installed: " + exception);
        }
    }
}
