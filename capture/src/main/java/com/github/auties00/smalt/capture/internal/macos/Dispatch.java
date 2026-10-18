package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Calls libdispatch and exposes the block runtime's stack block class, both re-exported by
 * {@code libSystem.B.dylib}.
 *
 * <p>This class loads the library when it is initialized, which only happens on macOS.
 */
@SuppressWarnings("restricted")
final class Dispatch {
    /**
     * The system umbrella library.
     */
    private static final SymbolLookup LIBRARY = Frameworks.load(Frameworks.SYSTEM);

    /**
     * The address of {@code _NSConcreteStackBlock}, the {@code isa} of stack block literals.
     *
     * <p>{@code Block.h} declares it as {@code void *_NSConcreteStackBlock[32]}: the address of
     * the array itself, not its content, is the class pointer.
     */
    static final MemorySegment STACK_BLOCK = Frameworks.symbol(LIBRARY, "_NSConcreteStackBlock");

    /**
     * {@code dispatch_queue_t dispatch_queue_create(const char *label, dispatch_queue_attr_t attr)}.
     */
    private static final MethodHandle QUEUE_CREATE = Native.function(LIBRARY, "dispatch_queue_create",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code void dispatch_sync_f(dispatch_queue_t queue, void *context, dispatch_function_t work)}.
     */
    private static final MethodHandle SYNC_F = Native.function(LIBRARY, "dispatch_sync_f",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code void dispatch_release(dispatch_object_t object)}.
     */
    private static final MethodHandle RELEASE = Native.function(LIBRARY, "dispatch_release",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * The label of the queues this backend creates, allocated once because libdispatch may keep
     * the pointer.
     */
    private static final MemorySegment LABEL = Arena.global().allocateFrom("com.github.auties00.smalt.capture");

    /**
     * Prevents instantiation.
     */
    private Dispatch() {
        throw new AssertionError();
    }

    /**
     * Creates a serial queue.
     *
     * @return the queue, owned by the caller, who releases it with {@link #release(MemorySegment)}
     */
    static MemorySegment createSerialQueue() {
        try {
            // DISPATCH_QUEUE_SERIAL is NULL (dispatch/queue.h)
            return (MemorySegment) QUEUE_CREATE.invokeExact(LABEL, MemorySegment.NULL);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Runs a function on a queue and waits for it; on a serial queue this also waits for every
     * work item submitted before.
     *
     * @param context the function's argument
     * @param function the function, taking {@code void *}
     */
    static void sync(MemorySegment queue, MemorySegment context, MemorySegment function) {
        try {
            SYNC_F.invokeExact(queue, context, function);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Releases a dispatch object; {@code NULL} is ignored.
     */
    static void release(MemorySegment object) {
        if (ObjC.isNull(object)) {
            return;
        }
        try {
            RELEASE.invokeExact(object);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }
}
