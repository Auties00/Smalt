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
 * <p>Some kernels are written for 256-bit vectors: they process, for example, sixteen 16-bit samples
 * per vector. On hardware whose vectors are narrower (ARM NEON and SVE at 128 bits, x86 without
 * AVX2) the JIT compilers cannot intrinsify their operations, which then run on the allocating Java
 * fallback, so such a kernel is selected only when {@link #WIDE} is {@code true}: either the whole
 * kernel ({@code Simd.WIDE ? VectorX.create() : new ScalarX()}), or each of its methods that uses
 * 256-bit vectors, which then starts by delegating to the scalar kernel when {@link #WIDE} is
 * {@code false}. Gathers and scatters (loads and stores through an index map) follow the same rule:
 * they compile only on hardware with gather instructions, which every platform with 256-bit vectors
 * has (AVX2, SVE) and ARM NEON lacks. The kernel lint (rule {@code wide-species}) checks that every
 * method using a 256-bit or wider species, a gather or a scatter is guarded one way or the other.
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
 * makes that likelier. Vector kernels therefore follow these rules, which the build checks in
 * {@code verify} (the {@code smalt-kernel-lint} step in the {@code it} module, whose rule ids are
 * given here, and the forbidden-apis signatures under {@code tools/lint}):
 * <ul>
 *   <li>operations use the per-shape {@code lanewise} form, never a convenience method such as
 *       {@code add} or {@code fma}, and never {@code reinterpretAs*} (forbidden-apis);</li>
 *   <li>every call on a vector, mask or shuffle has a receiver of exact type, cast through the
 *       shape's vector class ({@code INTS.cast(v)}) ({@code exact-receiver}); a value carried into
 *       or around a loop, or merged from branches, is cast again before each call on it
 *       ({@code carried-receiver});</li>
 *   <li>no method takes or returns a vector, a mask or a shuffle ({@code vector-helper});</li>
 *   <li>operators are {@code VectorOperators} constants at the call ({@code vector-operator}), a
 *       scalar operand other than a shift count is broadcast to a vector first
 *       ({@code vector-scalar-operand}), and no shift takes a vector of counts
 *       ({@code vector-shift-count});</li>
 *   <li>no kernel method exceeds about 2000 bytes of bytecode ({@code kernel-size}).</li>
 * </ul>
 * {@link KernelLintSuppress} on a method accepts a finding with a reason. The kernel JIT guard of
 * each module's tests then checks, in child JVMs, that every kernel runs compiled by C2 without
 * allocating and with its scalar twin's output.
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

    // TODO: 128-bit paths for the kernels gated on WIDE, which run scalar on NEON, SVE-128 and SSE-only x86
    /**
     * Whether kernels written for 256-bit vectors use their Vector API implementation: {@link #ENABLED}
     * is {@code true} and the preferred vectors are at least 256 bits wide.
     */
    public static final boolean WIDE = PREFERRED_BITS >= 256;

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
