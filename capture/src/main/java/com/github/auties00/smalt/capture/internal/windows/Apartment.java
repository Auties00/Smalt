package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.Native;

import java.util.function.Supplier;

/**
 * Runs native work on a dedicated platform thread that belongs to the COM multithreaded
 * apartment and uses physical pixel coordinates.
 *
 * <p>COM initialization and DPI awareness are properties of an operating system thread, and a
 * virtual thread may move between carrier threads between two native calls, so enumeration never
 * runs on the caller's thread: it runs on a short-lived platform thread that initializes COM,
 * performs the work and uninitializes COM.
 */
final class Apartment {
    private Apartment() {
        throw new AssertionError();
    }

    /**
     * Runs a task on a new apartment thread and waits for its result.
     *
     * <p>Unchecked exceptions and errors thrown by the task are rethrown on the calling thread.
     * An interrupt of the calling thread does not abandon the task: the call still waits for it
     * and then restores the interrupt status.
     *
     * @param name the name of the thread
     * @param task the work
     * @param <T> the type of the result
     * @return the task's result
     */
    static <T> T call(String name, Supplier<T> task) {
        return Native.callOnPlatformThread(name, () -> {
            Gdi.usePhysicalPixels();
            var initialized = Com.initialize();
            try {
                return task.get();
            } finally {
                if (initialized) {
                    Com.uninitialize();
                }
            }
        });
    }
}
