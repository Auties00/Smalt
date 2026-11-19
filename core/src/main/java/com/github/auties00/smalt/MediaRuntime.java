package com.github.auties00.smalt;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Reports how this JVM runs the library's kernels and threads.
 *
 * <p><b>Kernels.</b> Every hot kernel has a scalar implementation and a Vector API
 * implementation. The vector implementations are used only when the JVM was started with
 * {@code --add-modules jdk.incubator.vector}, the platform's preferred vectors are at least 128
 * bits wide, the processor and JVM support AVX2 on x86 (below it, C2 cannot compile every vector
 * operation the kernels use), and the {@code smalt.simd} system property is not {@code false};
 * otherwise every kernel runs its scalar code. On Windows the vector implementations are an opt-in: they are used
 * only when {@code smalt.simd} is {@code true} ({@code -Dsmalt.simd=true}) and the other conditions
 * hold. On affected Windows hosts (seen on Windows Server 2025), JIT-compiled code that keeps vector
 * state live across a hardware trap can lose it
 * (<a href="https://bugs.openjdk.org/browse/JDK-8365196">JDK-8365196</a>), which crashes the JVM or
 * corrupts a vector kernel's result; only {@code -XX:UseAVX=0} avoids it fully (it also disables
 * FMA, and runs every kernel scalar), so opting in there is not safe until the operating system or
 * the JDK is fixed. {@code -XX:UseAVX=2 -XX:MaxVectorSize=16} is a practical mitigation, which keeps
 * compiled vector code in the lower 128 bits of each register but is not proven safe for the JDK's
 * intrinsics; the kernels then run at 128 bits. The choice is made once per JVM and
 * never changes, so
 * {@link #isVectorized()} and {@link #vectorBits()} return constants. The kernels written for
 * 256-bit vectors, which include most of the video codecs' kernels, run their scalar code where
 * {@link #vectorBits()} is below {@code 256} (ARM NEON and 128-bit SVE, x86 without AVX2), since
 * the JIT compiler cannot compile their operations there. Vectorized video encoding costs several
 * times less than scalar encoding, so a caller that encodes in real time (a call, a live stream)
 * can use {@link #vectorBits()} to pick a picture size the host sustains.
 *
 * <p><b>Threads.</b> Codecs and picture operations split large work across one shared, bounded
 * pool of helper threads; {@link #parallelism()} reports how many threads, the caller included,
 * that pool runs at once (the {@code smalt.threads} system property, by default the number of
 * available processors). The global knobs are system properties only ({@code smalt.threads} and
 * {@code smalt.simd}), because the pool and the kernel choice are fixed when the library
 * initializes; per-operation thread counts go in the options of each operation, where {@code 0}
 * means the shared pool and {@code 1} the calling thread only.
 *
 * <p><b>Start-up.</b> In a fresh JVM the kernels and the codecs run in the interpreter and at the
 * first compilation tiers until the JIT compiler has compiled them, and Vector API code runs many
 * times slower than scalar code until then. The output never depends on it; only the first
 * seconds of work are slower. An application whose first seconds matter (a call answered at
 * once, the first picture sent) records a JDK ahead-of-time cache around Smalt's training
 * workloads, which the {@code smalt-aot} artifact provides.
 */
public final class MediaRuntime {
    /**
     * Prevents instantiation.
     */
    private MediaRuntime() {
        throw new AssertionError();
    }

    /**
     * Returns whether the library's kernels use their Vector API implementations in this JVM.
     *
     * @return {@code true} when the vector implementations are in use, {@code false} when every kernel runs its
     *         scalar code
     */
    public static boolean isVectorized() {
        return Simd.ENABLED;
    }

    /**
     * Returns the width of the vectors the kernels use in this JVM.
     *
     * @return the platform's preferred vector width in bits (for example {@code 256} on a processor with AVX2), or
     *         {@code 0} when {@link #isVectorized()} is {@code false}
     */
    public static int vectorBits() {
        return Simd.preferredBits();
    }

    /**
     * Returns how many threads, the calling thread included, run the library's shared parallel
     * work at once.
     *
     * <p>This is the {@code smalt.threads} system property when set, otherwise the number of
     * available processors at initialization; {@code 1} means everything runs on the calling
     * thread. However many threads call the library, it runs at most this many minus one helpers.
     *
     * @return the parallelism, at least {@code 1}
     */
    public static int parallelism() {
        return ParallelBands.THREADS;
    }
}
