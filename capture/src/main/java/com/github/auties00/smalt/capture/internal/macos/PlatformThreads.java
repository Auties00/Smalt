package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.Native;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs Objective-C work on a platform thread inside an autorelease pool.
 *
 * <p>An autorelease pool belongs to the operating system thread that pushed it. A virtual thread
 * may resume on another carrier after any blocking operation, so a pool pushed on a virtual
 * thread could be popped on the wrong thread; work arriving on a virtual thread is therefore
 * moved to a short-lived platform thread and the caller waits for it. Work arriving on a platform
 * thread runs in place. Either way the work runs between {@link ObjC#pushPool()} and
 * {@link ObjC#popPool(java.lang.foreign.MemorySegment)}, may block, and may throw.
 */
final class PlatformThreads {
    /**
     * The name of the threads work is moved to.
     */
    private static final String THREAD_NAME = "smalt-capture-macos";

    private PlatformThreads() {
        throw new AssertionError();
    }

    /**
     * Runs work and returns its result.
     *
     * <p>A caller interrupted while waiting for a moved task keeps waiting, since the task owns
     * native state, and has its interrupt status restored afterwards. The unchecked exception or
     * error the work throws is rethrown as is.
     *
     * @param <T> the type of the result
     * @return the result
     */
    static <T> T call(Supplier<T> work) {
        return call(work, PlatformThreads::inPool);
    }

    /**
     * Runs work through a runner on a platform thread and returns its result.
     *
     * <p>This is {@link #call(Supplier)} with the autorelease pool replaced by {@code runner},
     * which receives the work and runs it on the platform thread.
     *
     * @param runner runs the work on the platform thread
     * @param <T> the type of the result
     * @return the result
     */
    static <T> T call(Supplier<T> work, Function<Supplier<T>, T> runner) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(runner, "runner");
        if (!Thread.currentThread().isVirtual()) {
            return runner.apply(work);
        }
        return Native.callOnPlatformThread(THREAD_NAME, () -> runner.apply(work));
    }

    /**
     * Runs work that returns nothing, like {@link #call(Supplier)}.
     */
    static void run(Runnable work) {
        Objects.requireNonNull(work, "work");
        call(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs work on the calling platform thread inside an autorelease pool.
     */
    private static <T> T inPool(Supplier<T> work) {
        var pool = ObjC.pushPool();
        try {
            return work.get();
        } finally {
            ObjC.popPool(pool);
        }
    }
}
