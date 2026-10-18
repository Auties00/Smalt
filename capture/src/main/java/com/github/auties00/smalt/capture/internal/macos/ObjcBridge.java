package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.Native;

import java.io.InterruptedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Connects Objective-C callbacks to Java objects: a runtime-created delegate class and the
 * completion handler blocks.
 *
 * <p>The delegate class, a subclass of {@code NSObject} registered once per bridge, implements
 * {@code captureOutput:didOutputSampleBuffer:fromConnection:}
 * ({@code AVCaptureVideoDataOutputSampleBufferDelegate}), {@code stream:didOutputSampleBuffer:ofType:}
 * ({@code SCStreamOutput}) and {@code stream:didStopWithError:} ({@code SCStreamDelegate}) with
 * upcall stubs, and declares those protocols when the frameworks define them. Each instance holds
 * in an instance variable the {@link CallbackRegistry} key of the {@link DelegateSink} it serves.
 * Completion handlers are {@link BlockLayout} literals whose captured key names a
 * {@link BlockCompletion}; one invoke stub per handler signature serves them all.
 *
 * <p>Every upcall resolves its key and does nothing when the key is no longer registered, so a
 * callback arriving after a session was torn down is harmless, and catches every failure, because
 * an exception escaping an upcall terminates the JVM. The class, its stubs and its descriptors live
 * as long as the process.
 */
@SuppressWarnings("restricted")
final class ObjcBridge {
    /**
     * The name of the instance variable holding a delegate's key.
     */
    private static final String KEY_VARIABLE = "smaltKey";

    /**
     * The prefix of the delegate class name.
     */
    private static final String CLASS_PREFIX = "SmaltCaptureDelegate";

    /**
     * The protocols the delegate class declares when the frameworks defining them are loaded.
     */
    private static final List<String> PROTOCOLS = List.of("AVCaptureVideoDataOutputSampleBufferDelegate",
            "SCStreamOutput", "SCStreamDelegate");

    /**
     * The C signature of {@code -captureOutput:didOutputSampleBuffer:fromConnection:}:
     * {@code self}, {@code _cmd}, output, sample buffer, connection.
     */
    static final FunctionDescriptor CAPTURE_OUTPUT = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    /**
     * The C signature of {@code -stream:didOutputSampleBuffer:ofType:}: {@code self}, {@code _cmd},
     * stream, sample buffer, {@code NSInteger} type.
     */
    static final FunctionDescriptor STREAM_OUTPUT = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);

    /**
     * The C signature of {@code -stream:didStopWithError:}: {@code self}, {@code _cmd}, stream,
     * error.
     */
    static final FunctionDescriptor STREAM_STOPPED = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    /**
     * The C signature of the invoke function of {@code void (^)(BOOL)}: the block, the
     * {@code BOOL}.
     */
    static final FunctionDescriptor FLAG_INVOKE = FunctionDescriptor.ofVoid(ADDRESS, JAVA_BYTE);

    /**
     * The C signature of the invoke function of {@code void (^)(id, NSError *)}.
     */
    static final FunctionDescriptor OBJECT_ERROR_INVOKE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

    /**
     * The C signature of the invoke function of {@code void (^)(NSError *)}.
     */
    static final FunctionDescriptor ERROR_INVOKE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    /**
     * The C signature of a {@code dispatch_function_t}.
     */
    static final FunctionDescriptor DISPATCH_FUNCTION = FunctionDescriptor.ofVoid(ADDRESS);

    /**
     * The keys of the delegates and completions.
     */
    private final CallbackRegistry registry;

    /**
     * The delegate class.
     */
    private final MemorySegment delegateClass;

    /**
     * The offset of {@link #KEY_VARIABLE} within a delegate instance.
     */
    private final long keyOffset;

    /**
     * The descriptor of {@code void (^)(BOOL)} literals.
     */
    private final MemorySegment flagDescriptor;

    /**
     * The descriptor of {@code void (^)(id, NSError *)} literals.
     */
    private final MemorySegment objectErrorDescriptor;

    /**
     * The descriptor of {@code void (^)(NSError *)} literals.
     */
    private final MemorySegment errorDescriptor;

    /**
     * The invoke function of {@code void (^)(BOOL)} literals.
     */
    private final MemorySegment flagInvoke;

    /**
     * The invoke function of {@code void (^)(id, NSError *)} literals.
     */
    private final MemorySegment objectErrorInvoke;

    /**
     * The invoke function of {@code void (^)(NSError *)} literals.
     */
    private final MemorySegment errorInvoke;

    /**
     * A {@code dispatch_function_t} doing nothing, run synchronously to drain a queue.
     */
    private final MemorySegment drainFunction;

    /**
     * Enumerates the completion handler signatures.
     */
    enum Handler {
        /**
         * {@code void (^)(BOOL)}.
         */
        FLAG,

        /**
         * {@code void (^)(id, NSError *)}.
         */
        OBJECT_ERROR,

        /**
         * {@code void (^)(NSError *)}.
         */
        ERROR
    }

    /**
     * Creates the bridge, registering the delegate class and the block stubs.
     *
     * @throws IllegalStateException if the Objective-C runtime refuses the delegate class
     */
    ObjcBridge() {
        this.registry = new CallbackRegistry();
        var arena = Arena.global();
        var captureOutput = upcall("captureOutput", CAPTURE_OUTPUT, arena);
        var streamOutput = upcall("streamOutput", STREAM_OUTPUT, arena);
        var streamStopped = upcall("streamStopped", STREAM_STOPPED, arena);
        this.flagInvoke = upcall("flagInvoked", FLAG_INVOKE, arena);
        this.objectErrorInvoke = upcall("objectErrorInvoked", OBJECT_ERROR_INVOKE, arena);
        this.errorInvoke = upcall("errorInvoked", ERROR_INVOKE, arena);
        this.drainFunction = upcall("drained", DISPATCH_FUNCTION, arena);
        this.flagDescriptor = BlockLayout.descriptor(arena, TypeEncoding.boolBlock(MacPlatform.ARM64));
        this.objectErrorDescriptor = BlockLayout.descriptor(arena, TypeEncoding.OBJECT_ERROR_BLOCK);
        this.errorDescriptor = BlockLayout.descriptor(arena, TypeEncoding.ERROR_BLOCK);
        this.delegateClass = createDelegateClass(captureOutput, streamOutput, streamStopped);
        this.keyOffset = ObjC.ivarOffset(delegateClass, KEY_VARIABLE);
    }

    /**
     * Creates an upcall stub for an instance method of this bridge.
     *
     * @param name the method name
     * @param descriptor the C signature
     * @param arena the arena owning the stub
     * @return the stub
     */
    private MemorySegment upcall(String name, FunctionDescriptor descriptor, Arena arena) {
        return Native.LINKER.upcallStub(target(name, descriptor).bindTo(this), descriptor, arena);
    }

    /**
     * Resolves the instance method an upcall stub calls.
     *
     * @param name the method name
     * @param descriptor the C signature, whose Java form the method must have
     * @return the unbound handle, taking the bridge first
     * @throws IllegalStateException if no such method exists
     */
    static MethodHandle target(String name, FunctionDescriptor descriptor) {
        try {
            return MethodHandles.lookup().findVirtual(ObjcBridge.class, name, descriptor.toMethodType());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("missing upcall target " + name, failure);
        }
    }

    /**
     * Builds and registers the delegate class.
     *
     * @param captureOutput the implementation of the video data output callback
     * @param streamOutput the implementation of the stream output callback
     * @param streamStopped the implementation of the stream delegate callback
     * @return the class
     * @throws IllegalStateException if the runtime refuses the class, its variable or a method
     */
    private MemorySegment createDelegateClass(MemorySegment captureOutput, MemorySegment streamOutput,
                                              MemorySegment streamStopped) {
        loadProtocolFrameworks();
        var superclass = ObjC.objcClass("NSObject");
        var name = CLASS_PREFIX + "_" + Integer.toHexString(System.identityHashCode(this));
        var cls = ObjC.allocateClassPair(superclass, name);
        for (var attempt = 1; ObjC.isNull(cls) && attempt < 16; attempt++) {
            cls = ObjC.allocateClassPair(superclass, name + "_" + attempt);
        }
        if (ObjC.isNull(cls)) {
            throw new IllegalStateException("cannot allocate the Objective-C delegate class " + name);
        }
        if (!ObjC.addLongIvar(cls, KEY_VARIABLE)
                || !ObjC.addMethod(cls, ObjC.selector("captureOutput:didOutputSampleBuffer:fromConnection:"), captureOutput, TypeEncoding.CAPTURE_OUTPUT)
                || !ObjC.addMethod(cls, ObjC.selector("stream:didOutputSampleBuffer:ofType:"), streamOutput, TypeEncoding.STREAM_OUTPUT)
                || !ObjC.addMethod(cls, ObjC.selector("stream:didStopWithError:"), streamStopped, TypeEncoding.STREAM_STOPPED)) {
            throw new IllegalStateException("cannot build the Objective-C delegate class " + name);
        }
        for (var protocolName : PROTOCOLS) {
            var protocol = ObjC.protocol(protocolName);
            if (!ObjC.isNull(protocol)) {
                ObjC.addProtocol(cls, protocol);
            }
        }
        ObjC.registerClassPair(cls);
        return cls;
    }

    /**
     * Loads AVFoundation and ScreenCaptureKit so their protocols are registered; a framework that
     * fails to load is skipped.
     */
    private static void loadProtocolFrameworks() {
        try {
            var ignored = AvFoundation.CAPTURE_DEVICE;
        } catch (LinkageError absent) {
            // camera capture will report the failure when it is used
        }
        try {
            var ignored = ScreenCaptureKit.AVAILABLE;
        } catch (LinkageError absent) {
            // screen capture falls back to CoreGraphics
        }
    }

    /**
     * Registers the target of delegate callbacks or of a completion handler.
     *
     * @param target the {@link DelegateSink} or {@link BlockCompletion}
     * @return the key
     */
    long register(Object target) {
        return registry.register(target);
    }

    /**
     * Unregisters a key, so that later callbacks for it do nothing.
     */
    void unregister(long key) {
        registry.unregister(key);
    }

    /**
     * Creates a delegate instance serving a registered sink.
     *
     * @param key the sink's key
     * @return the instance, owned by the caller
     * @throws IllegalStateException if the instance cannot be created
     */
    MemorySegment newDelegate(long key) {
        var instance = ObjC.newInstance(delegateClass);
        if (ObjC.isNull(instance)) {
            throw new IllegalStateException("cannot create a delegate instance");
        }
        instance.reinterpret(keyOffset + JAVA_LONG.byteSize()).set(JAVA_LONG, keyOffset, key);
        return instance;
    }

    /**
     * Detaches a delegate instance from its sink, so that later callbacks on it do nothing.
     *
     * <p>Must be called while the caller still owns a reference to the instance.
     *
     * @param delegate the instance
     */
    void detachDelegate(MemorySegment delegate) {
        if (!ObjC.isNull(delegate)) {
            delegate.reinterpret(keyOffset + JAVA_LONG.byteSize()).set(JAVA_LONG, keyOffset, 0L);
        }
    }

    /**
     * Waits until every work item already submitted to a serial queue has run.
     */
    void drain(MemorySegment queue) {
        Dispatch.sync(queue, MemorySegment.NULL, drainFunction);
    }

    /**
     * Calls an Objective-C method taking a completion handler and waits for the handler.
     *
     * <p>The literal passed to {@code call} is valid only during the call, as a stack block would
     * be; the callee copies it if it keeps it.
     *
     * @param handler the handler's signature
     * @param timeoutMillis the longest wait in milliseconds
     * @param action what is being done, in words, for failure messages
     * @param call sends the message, given the block literal
     * @return the resolved completion
     * @throws MediaException.Io if the handler is not called in time or the wait is interrupted
     */
    BlockCompletion await(Handler handler, long timeoutMillis, String action, Consumer<MemorySegment> call) {
        var completion = new BlockCompletion();
        var key = registry.register(completion);
        try {
            try (var arena = Arena.ofConfined()) {
                call.accept(literal(arena, handler, key));
            }
            if (!completion.await(timeoutMillis)) {
                throw new MediaException.Io(action + " timed out after " + timeoutMillis + " ms",
                        new InterruptedIOException(action + " timed out"));
            }
            return completion;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MediaException.Io(action + " was interrupted", new InterruptedIOException(action + " was interrupted"));
        } finally {
            registry.unregister(key);
        }
    }

    /**
     * Builds a completion handler literal.
     *
     * @param allocator the allocator of the literal
     * @param handler the handler's signature
     * @param key the completion's key
     * @return the literal
     */
    private MemorySegment literal(SegmentAllocator allocator, Handler handler, long key) {
        return switch (handler) {
            case FLAG -> BlockLayout.literal(allocator, Dispatch.STACK_BLOCK, flagInvoke, flagDescriptor, key);
            case OBJECT_ERROR -> BlockLayout.literal(allocator, Dispatch.STACK_BLOCK, objectErrorInvoke, objectErrorDescriptor, key);
            case ERROR -> BlockLayout.literal(allocator, Dispatch.STACK_BLOCK, errorInvoke, errorDescriptor, key);
        };
    }

    /**
     * Reads the key of a delegate instance.
     *
     * @param self the instance
     * @return the key, {@code 0} once detached
     */
    private long delegateKey(MemorySegment self) {
        return self.reinterpret(keyOffset + JAVA_LONG.byteSize()).get(JAVA_LONG, keyOffset);
    }

    /**
     * Reads the key of a block literal.
     *
     * @param block the literal, or the runtime's copy of it
     * @return the key
     */
    private static long blockKey(MemorySegment block) {
        return BlockLayout.key(block.reinterpret(BlockLayout.LITERAL.byteSize()));
    }

    /**
     * Implements {@code -captureOutput:didOutputSampleBuffer:fromConnection:}.
     *
     * @param self the delegate
     * @param command the selector
     * @param output the video data output
     * @param sampleBuffer the captured sample buffer
     * @param connection the connection it arrived on
     */
    private void captureOutput(MemorySegment self, MemorySegment command, MemorySegment output,
                               MemorySegment sampleBuffer, MemorySegment connection) {
        deliver(self, sampleBuffer);
    }

    /**
     * Implements {@code -stream:didOutputSampleBuffer:ofType:}.
     *
     * @param self the delegate
     * @param command the selector
     * @param sampleBuffer the captured sample buffer
     * @param type the {@code SCStreamOutputType}; only screen buffers are delivered
     */
    private void streamOutput(MemorySegment self, MemorySegment command, MemorySegment stream,
                              MemorySegment sampleBuffer, long type) {
        if (type == ScreenCaptureKit.OUTPUT_SCREEN) {
            deliver(self, sampleBuffer);
        }
    }

    /**
     * Implements {@code -stream:didStopWithError:}.
     *
     * @param self the delegate
     * @param command the selector
     * @param error the error it stopped with
     */
    private void streamStopped(MemorySegment self, MemorySegment command, MemorySegment stream, MemorySegment error) {
        DelegateSink sink = null;
        try {
            sink = registry.lookup(delegateKey(self), DelegateSink.class);
            if (sink == null) {
                return;
            }
            var pool = ObjC.pushPool();
            try {
                sink.stopped(error);
            } finally {
                ObjC.popPool(pool);
            }
        } catch (Throwable failure) {
            report(sink, failure);
        }
    }

    /**
     * Forwards a sample buffer to the sink of a delegate.
     *
     * @param self the delegate
     * @param sampleBuffer the sample buffer
     */
    private void deliver(MemorySegment self, MemorySegment sampleBuffer) {
        DelegateSink sink = null;
        try {
            sink = registry.lookup(delegateKey(self), DelegateSink.class);
            if (sink == null) {
                return;
            }
            var pool = ObjC.pushPool();
            try {
                sink.sampleBuffer(sampleBuffer);
            } finally {
                ObjC.popPool(pool);
            }
        } catch (Throwable failure) {
            report(sink, failure);
        }
    }

    /**
     * Reports a failure caught in an upcall to its sink, if any.
     *
     * @param sink the sink, or {@code null}
     */
    private static void report(DelegateSink sink, Throwable failure) {
        if (sink == null) {
            return;
        }
        try {
            sink.failed(failure);
        } catch (Throwable secondary) {
            // an exception leaving an upcall would terminate the JVM
        }
    }

    /**
     * Implements the invoke function of {@code void (^)(BOOL)} literals.
     *
     * @param block the literal
     * @param value the {@code BOOL} argument
     */
    private void flagInvoked(MemorySegment block, byte value) {
        try {
            var completion = registry.lookup(blockKey(block), BlockCompletion.class);
            if (completion != null) {
                completion.completeFlag(value != 0);
            }
        } catch (Throwable failure) {
            // an exception leaving an upcall would terminate the JVM; the waiter times out
        }
    }

    /**
     * Implements the invoke function of {@code void (^)(id, NSError *)} literals.
     *
     * @param block the literal
     * @param object the object argument, retained here for the waiter
     * @param error the error argument
     */
    private void objectErrorInvoked(MemorySegment block, MemorySegment object, MemorySegment error) {
        complete(block, object, error);
    }

    /**
     * Implements the invoke function of {@code void (^)(NSError *)} literals.
     *
     * @param block the literal
     * @param error the error argument
     */
    private void errorInvoked(MemorySegment block, MemorySegment error) {
        complete(block, MemorySegment.NULL, error);
    }

    /**
     * Resolves the completion of an object or error handler.
     *
     * @param block the literal
     * @param object the object argument, or {@code NULL}
     * @param error the error argument, or {@code NULL}
     */
    private void complete(MemorySegment block, MemorySegment object, MemorySegment error) {
        BlockCompletion completion = null;
        try {
            completion = registry.lookup(blockKey(block), BlockCompletion.class);
            if (completion == null) {
                return;
            }
            var pool = ObjC.pushPool();
            try {
                var retained = ObjC.retain(object);
                if (!completion.complete(retained, NsError.read(error))) {
                    ObjC.release(retained);
                }
            } finally {
                ObjC.popPool(pool);
            }
        } catch (Throwable failure) {
            if (completion != null) {
                completion.complete(MemorySegment.NULL, new NsError("java", -1, String.valueOf(failure)));
            }
        }
    }

    /**
     * Implements the {@code dispatch_function_t} that drains a queue: it does nothing.
     *
     * @param context the unused context
     */
    private void drained(MemorySegment context) {
    }
}
