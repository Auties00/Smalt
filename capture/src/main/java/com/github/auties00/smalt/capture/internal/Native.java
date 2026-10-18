package com.github.auties00.smalt.capture.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Binds operating system libraries and runs native work on platform threads, for every capture
 * backend.
 *
 * <p>A library that cannot be loaded is reported as absent, and a missing function as an
 * {@link UnsatisfiedLinkError}, so a backend binds its required functions in one block that catches
 * that error and reports itself unavailable.
 */
public final class Native {
    /**
     * The linker of the running platform.
     */
    public static final Linker LINKER = Linker.nativeLinker();

    private Native() {
        throw new AssertionError();
    }

    /**
     * Loads a library for the lifetime of the process.
     *
     * @param name the library name or path, as the operating system's loader accepts it
     * @return the lookup of the library's exports, or empty when it cannot be loaded or native access
     * is denied to this module
     */
    public static Optional<SymbolLookup> library(String name) {
        try {
            return Optional.of(SymbolLookup.libraryLookup(name, Arena.global()));
        } catch (IllegalArgumentException | IllegalCallerException exception) {
            return Optional.empty();
        }
    }

    /**
     * Creates a downcall handle for a function that must exist.
     *
     * @param library the library exporting the function
     * @param name the export name
     * @param descriptor the function's signature
     * @param options the linker options, such as a captured call state
     * @return the handle
     * @throws UnsatisfiedLinkError if the library does not export the function
     */
    public static MethodHandle function(SymbolLookup library, String name, FunctionDescriptor descriptor,
                                        Linker.Option... options) {
        var address = library.find(name).orElseThrow(() -> new UnsatisfiedLinkError("missing native function " + name));
        return LINKER.downcallHandle(address, descriptor, options);
    }

    /**
     * Creates a downcall handle for a function that may be absent.
     *
     * @param library the library exporting the function
     * @param name the export name
     * @param descriptor the function's signature
     * @return the handle, or empty when the library does not export the function
     */
    public static Optional<MethodHandle> optionalFunction(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        return library.find(name).map(address -> LINKER.downcallHandle(address, descriptor));
    }

    /**
     * Rethrows the failure of a method handle invocation, which declares {@link Throwable} although a
     * downcall only throws unchecked exceptions and errors.
     *
     * <p>Callers write {@code throw Native.rethrow(failure)} so the compiler sees the branch end.
     *
     * @return never returns normally
     * @throws IllegalStateException wrapping {@code failure} when it is a checked exception; an
     * unchecked exception or error is thrown as is
     */
    public static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }

    /**
     * Runs work on a new platform daemon thread and waits for its result.
     *
     * <p>Native state that belongs to an operating system thread (a COM apartment, an autorelease
     * pool) cannot be kept on a virtual thread, which may move between carriers between two calls. An
     * interrupt of the caller does not abandon the work, which owns that state: the call keeps waiting
     * and restores the interrupt status afterwards.
     *
     * @param name the name of the thread
     * @param work the work, including any per-thread setup and teardown
     * @param <T> the type of the result
     * @return the work's result
     * @throws IllegalStateException wrapping a checked exception of the work; an unchecked exception
     * or error of the work is thrown as is
     */
    public static <T> T callOnPlatformThread(String name, Supplier<T> work) {
        var outcome = new Outcome<T>(work);
        var thread = Thread.ofPlatform().name(name).daemon().start(outcome);
        var interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (outcome.failure != null) {
            throw rethrow(outcome.failure);
        }
        return outcome.value;
    }

    /**
     * Carries the result or failure of work from its thread to the caller, which reads it after joining.
     */
    private static final class Outcome<T> implements Runnable {
        private final Supplier<T> work;
        private T value;
        private Throwable failure;

        private Outcome(Supplier<T> work) {
            this.work = work;
        }

        @Override
        public void run() {
            try {
                value = work.get();
            } catch (Throwable thrown) {
                failure = thrown;
            }
        }
    }
}
