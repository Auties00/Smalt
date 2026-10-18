package com.github.auties00.smalt.capture.internal.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Keeps X protocol errors on the displays this backend opens from terminating the process.
 *
 * <p>Xlib reports a protocol error (for example {@code BadMatch} from a grab outside the root
 * window after a resolution change, or {@code BadAccess} from attaching shared memory to a remote
 * server) to one process-wide error handler whose default prints the error and exits. The trap
 * installs, once, a handler that records the error code of a registered display and returns, and
 * forwards errors of every other display to the handler it replaced, so other Xlib users in the
 * process keep their behavior. A lost connection is reported to a separate I/O error handler that
 * must not return; libX11 1.7 and later accept a per-display exit handler instead, which the trap
 * registers to mark the display broken. With an older libX11, losing the X server still ends the
 * process, as it does for every Xlib client.
 *
 * @implNote This implementation keeps process-wide state because Xlib's error handler is
 * process-wide: the handler is installed on the first registration and never removed,
 * since another library may have installed its own handler on top of it since.
 */
final class X11ErrorTrap {
    /**
     * The state of each registered display, by address.
     */
    private static final ConcurrentHashMap<Long, State> DISPLAYS = new ConcurrentHashMap<>();

    /**
     * Calls a previously installed error handler through its function pointer.
     */
    private static final MethodHandle CHAIN = Linker.nativeLinker().downcallHandle(X11.ERROR_HANDLER);

    /**
     * Prevents instantiation.
     */
    private X11ErrorTrap() {
        throw new AssertionError();
    }

    /**
     * Registers a display, installing the trap on first use.
     *
     * @param x11 the bindings
     */
    static void register(X11 x11, MemorySegment display) {
        var exitHandler = Installed.IO_ERROR_EXIT_HANDLER;
        DISPLAYS.put(display.address(), new State());
        x11.setIoErrorExitHandler(display, exitHandler);
    }

    /**
     * Unregisters a display after it was closed.
     */
    static void unregister(MemorySegment display) {
        DISPLAYS.remove(display.address());
    }

    /**
     * Returns and clears the last error recorded for a display.
     *
     * @return the X error code, or {@code 0} when no error occurred since the last call
     */
    static int takeError(MemorySegment display) {
        var state = DISPLAYS.get(display.address());
        if (state == null) {
            return 0;
        }
        var error = state.error;
        state.error = 0;
        return error;
    }

    /**
     * Returns whether a display lost its connection.
     *
     * @return whether the I/O error exit handler ran for it
     */
    static boolean broken(MemorySegment display) {
        var state = DISPLAYS.get(display.address());
        return state != null && state.broken;
    }

    /**
     * Handles an X protocol error; called by Xlib.
     *
     * @param display the display the error occurred on
     * @param event the {@code XErrorEvent}
     * @return {@code 0}, or the result of the previous handler for a foreign display
     */
    private static int onError(MemorySegment display, MemorySegment event) {
        try {
            var state = DISPLAYS.get(display.address());
            if (state != null) {
                state.error = event.reinterpret(X11.ERROR_EVENT.byteSize()).get(JAVA_BYTE, X11.ERROR_CODE) & 0xFF;
                return 0;
            }
            var handler = Installed.PREVIOUS;
            if (handler == null || handler.equals(MemorySegment.NULL)) {
                return 0;
            }
            return (int) CHAIN.invokeExact(handler, display, event);
        } catch (Throwable throwable) {
            return 0;
        }
    }

    /**
     * Marks a display broken after its connection was lost; called by Xlib 1.7 and later instead
     * of exiting.
     *
     * @param data the user data, unused
     */
    private static void onIoError(MemorySegment display, MemorySegment data) {
        try {
            var state = DISPLAYS.get(display.address());
            if (state != null) {
                state.broken = true;
            }
        } catch (Throwable ignored) {
            // An exception must not unwind into Xlib.
        }
    }

    /**
     * Installs the trap's handlers when first used, which is when the first display registers.
     */
    private static final class Installed {
        /**
         * The trap's error handler.
         */
        static final MemorySegment ERROR_HANDLER = upcall("onError",
                MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class), X11.ERROR_HANDLER);

        /**
         * The trap's I/O error exit handler.
         */
        static final MemorySegment IO_ERROR_EXIT_HANDLER = upcall("onIoError",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class), X11.IO_ERROR_EXIT_HANDLER);

        /**
         * The handler that was installed before the trap, called for foreign displays.
         */
        static final MemorySegment PREVIOUS = X11.instance().setErrorHandler(ERROR_HANDLER);

        /**
         * Prevents instantiation.
         */
        private Installed() {
            throw new AssertionError();
        }

        /**
         * Creates a process-lifetime upcall stub of a static method of {@link X11ErrorTrap}.
         *
         * @param name the method name
         * @param type the method type
         * @param descriptor the native signature
         * @return the function pointer
         */
        private static MemorySegment upcall(String name, MethodType type, FunctionDescriptor descriptor) {
            try {
                var target = MethodHandles.lookup().findStatic(X11ErrorTrap.class, name, type);
                return Linker.nativeLinker().upcallStub(target, descriptor, Arena.global());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    /**
     * Holds what the trap recorded for one display.
     */
    private static final class State {
        /**
         * The last X error code, or {@code 0}.
         */
        volatile int error;

        /**
         * Whether the connection was lost.
         */
        volatile boolean broken;
    }
}
