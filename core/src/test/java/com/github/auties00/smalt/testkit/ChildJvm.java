package com.github.auties00.smalt.testkit;

import java.util.Arrays;
import java.util.List;

/**
 * The JVM options every child JVM a test starts takes from its parent, so that a child runs on the same CPU
 * features as the test JVM.
 *
 * <p>On Windows x64 the build's {@code windows-x64} profile runs the test JVMs with
 * {@code -XX:UseAVX=2 -XX:MaxVectorSize=16} because of JDK-8365196, which on affected Windows hosts loses the vector
 * state JIT-compiled code keeps live across a hardware trap, and hands the same options to the tests in the
 * {@code smalt.test.cpuArgs} system property, separated by commas; a child started without them would run into the
 * defect again. A test that launches {@code java} adds {@link #cpuArgs()} before its own options, so
 * that an option the test sets itself, such as a KernelGuard scenario's {@code -XX:UseAVX}, still wins.
 */
public final class ChildJvm {
    /** The CPU-feature options, resolved once. */
    private static final List<String> CPU_ARGS = resolve();

    private ChildJvm() {
        throw new AssertionError();
    }

    /**
     * Returns the CPU-feature options a child JVM must repeat: the {@code smalt.test.cpuArgs} system property,
     * split at commas and whitespace.
     *
     * @return the options, empty when the test JVMs run on the host's full CPU features
     */
    public static List<String> cpuArgs() {
        return CPU_ARGS;
    }

    private static List<String> resolve() {
        var property = System.getProperty("smalt.test.cpuArgs", "").strip();
        return property.isEmpty() ? List.of() : List.copyOf(Arrays.asList(property.split("[\\s,]+")));
    }
}
