package com.github.auties00.smalt.internal.simd;

/**
 * Decides once per JVM whether DSP kernels use their Vector API implementation.
 *
 * <p>Every hot kernel in this module exists twice: a scalar implementation that is always
 * available and serves as the reference in tests, and a Vector API implementation. The vector
 * implementation is selected only when the incubating {@code jdk.incubator.vector} module is
 * present in the boot layer (the JVM was started with
 * {@code --add-modules jdk.incubator.vector}), the preferred vector shape is at least 128 bits
 * wide (so the API is backed by real SIMD registers rather than its slow Java fallback), on x86 the
 * JVM's AVX level is at least AVX2 ({@code UseAVX} at least 2), and the {@code smalt.simd} system
 * property is not {@code false}; on Windows the property must also be {@code true}.
 *
 * <p>An x86 JVM below AVX2 (an SSE-only or AVX1 processor, or {@code -XX:UseAVX=0} or {@code 1})
 * still reports 128-bit preferred vectors, but C2 does not compile every operation the kernels use
 * there, and those left on the allocating Java fallback run hundreds of times slower than the scalar
 * twins. The AVX level is the {@code UseAVX} flag, read through the optional {@code jdk.management}
 * module (resolved by default on the class path); without that module, 256-bit preferred integer
 * vectors, which only AVX2 and later have, stand in for it, so a 128-bit x86 JVM runs scalar.
 *
 * <p>On Windows the vector kernels are therefore an opt-in, because of
 * <a href="https://bugs.openjdk.org/browse/JDK-8365196">JDK-8365196</a>: on affected Windows
 * hosts (seen on Windows Server 2025), JIT-compiled code that keeps vector state live across a
 * hardware trap can lose it, AVX2 and AVX-512 state alike, which crashes the JVM or corrupts a
 * vector kernel's result. Only {@code -XX:UseAVX=0} avoids it fully, so opting in on such a host is
 * not safe until the operating system or the JDK is fixed.
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
 *   <li>no kernel method exceeds about 2000 bytes of bytecode ({@code kernel-size});</li>
 *   <li>an instance kernel does not read its receiver after a vector loop, for example to reach a
 *       scalar tail through an instance field ({@code loop-receiver}): under JDK-8365196, C2 can
 *       keep a reference live across a loop's safepoint poll in a vector register and find it null
 *       after the trap, so a scalar tail lives in a {@code static final} field, or a stateful kernel
 *       runs it before the loop. The rule narrows the kernels' exposure to the defect without
 *       removing it.</li>
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
     * The lowest {@code UseAVX} level at which an x86 JVM selects the vector kernels.
     */
    private static final int MIN_X86_AVX = 2;

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
        var property = System.getProperty("smalt.simd");
        if ("false".equalsIgnoreCase(property)) {
            return false;
        }
        // TODO: select the vector kernels by default on Windows again once JDK-8365196
        //  (https://bugs.openjdk.org/browse/JDK-8365196, vector state lost across hardware traps on affected
        //  Windows hosts) is fixed and the fixed JDK is the minimum Smalt supports
        if (System.getProperty("os.name", "").startsWith("Windows") && !"true".equalsIgnoreCase(property)) {
            return false;
        }
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
            return false;
        }
        int preferredBits;
        try {
            preferredBits = VectorProbe.preferredBits();
        } catch (LinkageError error) {
            return false;
        }
        return preferredBits >= 128 && (!isX86() || x86VectorsCompile(preferredBits));
    }

    /**
     * {@return whether this JVM runs on x86 or x86-64}
     */
    private static boolean isX86() {
        return switch (System.getProperty("os.arch", "")) {
            case "amd64", "x86_64", "x86", "i386", "i486", "i586", "i686" -> true;
            default -> false;
        };
    }

    /**
     * Returns whether C2 compiles the vector kernels on this x86 JVM: its AVX level is at least
     * {@link #MIN_X86_AVX}.
     *
     * <p>The level is the {@code UseAVX} flag, read through {@code jdk.management} when that module is
     * present. Without it, the preferred vector width stands in: 256-bit integer vectors exist only from AVX2
     * on, while 128 bits may be an SSE-only JVM or a {@code MaxVectorSize} limit, so a 128-bit JVM is then
     * taken to lack AVX2.
     *
     * @param preferredBits the width of the preferred integer vectors
     * @return whether the vector kernels compile
     */
    private static boolean x86VectorsCompile(int preferredBits) {
        if (ModuleLayer.boot().findModule("jdk.management").isPresent()) {
            try {
                var useAvx = HotSpotFlags.intFlag("UseAVX");
                if (useAvx >= 0) {
                    return useAvx >= MIN_X86_AVX;
                }
            } catch (LinkageError | RuntimeException exception) {
                // not a HotSpot JVM, or its management beans are unavailable: fall back on the vector width
            }
        }
        return preferredBits >= 256;
    }
}
