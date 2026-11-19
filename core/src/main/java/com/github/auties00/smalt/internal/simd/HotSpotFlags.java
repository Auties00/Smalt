package com.github.auties00.smalt.internal.simd;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;

/**
 * Reads the value of a HotSpot integer flag through the platform's {@link HotSpotDiagnosticMXBean}.
 *
 * <p>This class references {@code jdk.management} types, so {@link Simd} calls it only after verifying that the
 * module is present in the boot layer; the module is an optional dependency.
 */
final class HotSpotFlags {
    /**
     * Prevents instantiation.
     */
    private HotSpotFlags() {
        throw new AssertionError();
    }

    /**
     * Returns the value of a HotSpot integer flag.
     *
     * @param name the flag's name, for example {@code UseAVX}
     * @return the flag's value, or {@code -1} when this JVM has no such flag or reports a value that is not an
     *         integer
     */
    static int intFlag(String name) {
        try {
            var value = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption(name).getValue();
            return Integer.parseInt(value);
        } catch (IllegalArgumentException exception) {
            return -1;
        }
    }
}
