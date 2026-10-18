package com.github.auties00.smalt.internal.simd;

/**
 * Decides once per JVM whether DSP kernels use their Vector API implementation.
 *
 * <p>Every hot kernel in this module exists twice: a scalar implementation that is always
 * available and serves as the reference in tests, and a Vector API implementation. The vector
 * implementation is selected only when the incubating {@code jdk.incubator.vector} module is
 * present in the boot layer (the JVM was started with
 * {@code --add-modules jdk.incubator.vector}), the preferred vector shape is at least 128 bits
 * wide (so the API is backed by real SIMD registers rather than its slow Java fallback), and the
 * {@code smalt.simd} system property is not {@code false}.
 *
 * <p>Kernels select their implementation in a {@code static final} field, which the JIT treats as
 * a constant: the choice costs nothing after class initialization. The field calls the vector class
 * directly, {@code Simd.ENABLED ? VectorX.create() : new ScalarX()}, with no holder class in between:
 * when the module is absent the vector class is at most loaded by the verifier, never linked or
 * initialized, so none of the module's types is loaded. Only the vector class itself may mention
 * {@code jdk.incubator.vector} types, because linking a class that mentions them fails when the
 * module is absent.
 *
 * <p>A JIT compiler that cannot compile a Vector API operation leaves it on its allocating Java
 * fallback, which then runs on every call for the life of the JVM; mixing many kernels in one JVM
 * makes that likelier. Vector kernels therefore follow these rules, which {@code JitRobustnessTest}
 * in the {@code it} module checks:
 * <ul>
 *   <li>operations use the per-shape {@code lanewise} form, never a convenience method such as
 *       {@code add} or {@code fma};</li>
 *   <li>every {@code intoArray} and every {@code lanewise} with a scalar operand has a receiver of
 *       exact type, cast through the shape's vector class ({@code INTS.cast(v)});</li>
 *   <li>no vector crosses a loop back edge or a branch merge, a vector carried into or around a
 *       loop is cast again before each call on it, and no method takes or returns a vector;</li>
 *   <li>no kernel method exceeds about 2000 bytecodes.</li>
 * </ul>
 *
 * @implNote This implementation's selection rule was checked with {@code -Xlog:class+load} on a
 * JVM started without {@code --add-modules jdk.incubator.vector}: neither {@code VectorX.create()}
 * nor {@code new VectorX()} in the selecting expression loads a {@code jdk.incubator.vector} class,
 * and the scalar kernels are selected.
 */
public final class Simd {
    /**
     * Whether kernels use their Vector API implementation.
     */
    public static final boolean ENABLED = detect();

    /**
     * The width of the vectors the kernels use, in bits, or {@code 0} when {@link #ENABLED} is
     * {@code false}.
     */
    private static final int PREFERRED_BITS = ENABLED ? VectorProbe.preferredBits() : 0;

    /**
     * Prevents instantiation.
     */
    private Simd() {
        throw new AssertionError();
    }

    /**
     * Returns the width of the vectors the kernels use.
     *
     * @return the platform's preferred vector width in bits, or {@code 0} when {@link #ENABLED} is
     *         {@code false}
     */
    public static int preferredBits() {
        return PREFERRED_BITS;
    }

    /**
     * Evaluates the selection rule described on the class.
     *
     * @return whether vector kernels should be used
     */
    private static boolean detect() {
        if ("false".equalsIgnoreCase(System.getProperty("smalt.simd"))) {
            return false;
        }
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
            return false;
        }
        try {
            return VectorProbe.preferredBits() >= 128;
        } catch (LinkageError error) {
            return false;
        }
    }
}
