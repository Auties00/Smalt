package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Calls the Objective-C runtime ({@code libobjc.A.dylib}).
 *
 * <p>Messages are sent through {@code objc_msgSend}, a trampoline that must be called with the
 * exact C signature of the method it dispatches to ({@code id self, SEL _cmd, ...} followed by the
 * method's arguments, never as a variadic call). Each method shape this backend sends has its own
 * downcall handle, named after its signature; the helpers below wrap them. A structure larger than
 * 16 bytes is returned through {@code objc_msgSend_stret} on x86_64 (see
 * {@link MacPlatform#needsStructReturn(boolean, MemoryLayout)}). {@code BOOL} travels as a byte
 * on both architectures. Messages to {@code nil} return zero, so every getter tolerates a
 * {@link MemorySegment#NULL} receiver.
 *
 * <p>Objects returned by methods other than {@code alloc}, {@code new}, {@code copy} and
 * {@code mutableCopy} are autoreleased: they stay valid until the enclosing autorelease pool
 * ({@link #pushPool()}, {@link #popPool(MemorySegment)}) is drained, and must be
 * {@link #retain(MemorySegment) retained} to outlive it.
 *
 * <p>This class loads the runtime when it is initialized, which only happens on macOS.
 */
@SuppressWarnings("restricted")
final class ObjC {
    /**
     * The runtime library.
     */
    private static final SymbolLookup LIBRARY = Frameworks.load(Frameworks.OBJC);

    /**
     * The address of {@code objc_msgSend}.
     */
    private static final MemorySegment MSG_SEND = Frameworks.symbol(LIBRARY, "objc_msgSend");

    /**
     * {@code Class objc_getClass(const char *name)}.
     */
    private static final MethodHandle GET_CLASS = Native.function(LIBRARY, "objc_getClass",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code Protocol *objc_getProtocol(const char *name)}.
     */
    private static final MethodHandle GET_PROTOCOL = Native.function(LIBRARY, "objc_getProtocol",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code SEL sel_registerName(const char *str)}.
     */
    private static final MethodHandle REGISTER_NAME = Native.function(LIBRARY, "sel_registerName",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code Class objc_allocateClassPair(Class superclass, const char *name, size_t extraBytes)}.
     */
    private static final MethodHandle ALLOCATE_CLASS_PAIR = Native.function(LIBRARY, "objc_allocateClassPair",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

    /**
     * {@code BOOL class_addIvar(Class cls, const char *name, size_t size, uint8_t alignment,
     * const char *types)}.
     */
    private static final MethodHandle ADD_IVAR = Native.function(LIBRARY, "class_addIvar",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_BYTE, ADDRESS));

    /**
     * {@code BOOL class_addMethod(Class cls, SEL name, IMP imp, const char *types)}.
     */
    private static final MethodHandle ADD_METHOD = Native.function(LIBRARY, "class_addMethod",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code BOOL class_addProtocol(Class cls, Protocol *protocol)}.
     */
    private static final MethodHandle ADD_PROTOCOL = Native.function(LIBRARY, "class_addProtocol",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));

    /**
     * {@code void objc_registerClassPair(Class cls)}.
     */
    private static final MethodHandle REGISTER_CLASS_PAIR = Native.function(LIBRARY, "objc_registerClassPair",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code Ivar class_getInstanceVariable(Class cls, const char *name)}.
     */
    private static final MethodHandle GET_INSTANCE_VARIABLE = Native.function(LIBRARY, "class_getInstanceVariable",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code ptrdiff_t ivar_getOffset(Ivar v)}.
     */
    private static final MethodHandle IVAR_OFFSET = Native.function(LIBRARY, "ivar_getOffset",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code void *objc_autoreleasePoolPush(void)}, the entry point Clang emits for
     * {@code @autoreleasepool}.
     */
    private static final MethodHandle POOL_PUSH = Native.function(LIBRARY, "objc_autoreleasePoolPush",
            FunctionDescriptor.of(ADDRESS));

    /**
     * {@code void objc_autoreleasePoolPop(void *context)}.
     */
    private static final MethodHandle POOL_POP = Native.function(LIBRARY, "objc_autoreleasePoolPop",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * Sends {@code id (id, SEL)}.
     */
    private static final MethodHandle SEND_ID = send(ADDRESS);

    /**
     * Sends {@code id (id, SEL, id)}.
     */
    private static final MethodHandle SEND_ID_ID = send(ADDRESS, ADDRESS);

    /**
     * Sends {@code id (id, SEL, id, id)}.
     */
    private static final MethodHandle SEND_ID_ID_ID = send(ADDRESS, ADDRESS, ADDRESS);

    /**
     * Sends {@code id (id, SEL, id, id, id)}.
     */
    private static final MethodHandle SEND_ID_ID_ID_ID = send(ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    /**
     * Sends {@code id (id, SEL, NSUInteger)}.
     */
    private static final MethodHandle SEND_ID_LONG = send(ADDRESS, JAVA_LONG);

    /**
     * Sends {@code id (id, SEL, unsigned int)}.
     */
    private static final MethodHandle SEND_ID_INT = send(ADDRESS, JAVA_INT);

    /**
     * Sends {@code id (id, SEL, const id *, NSUInteger)}.
     */
    private static final MethodHandle SEND_ID_ID_LONG = send(ADDRESS, ADDRESS, JAVA_LONG);

    /**
     * Sends {@code id (id, SEL, id, id, NSInteger)}.
     */
    private static final MethodHandle SEND_ID_ID_ID_LONG = send(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);

    /**
     * Sends {@code NSInteger (id, SEL)}.
     */
    private static final MethodHandle SEND_LONG = send(JAVA_LONG);

    /**
     * Sends {@code NSInteger (id, SEL, id)}.
     */
    private static final MethodHandle SEND_LONG_ID = send(JAVA_LONG, ADDRESS);

    /**
     * Sends {@code unsigned int (id, SEL)}.
     */
    private static final MethodHandle SEND_INT = send(JAVA_INT);

    /**
     * Sends {@code BOOL (id, SEL)}.
     */
    private static final MethodHandle SEND_BOOL = send(JAVA_BYTE);

    /**
     * Sends {@code BOOL (id, SEL, id)}.
     */
    private static final MethodHandle SEND_BOOL_ID = send(JAVA_BYTE, ADDRESS);

    /**
     * Sends {@code BOOL (id, SEL, id, NSInteger, id)}.
     */
    private static final MethodHandle SEND_BOOL_ID_LONG_ID = send(JAVA_BYTE, ADDRESS, JAVA_LONG, ADDRESS);

    /**
     * Sends {@code BOOL (id, SEL, id, NSInteger, id, id)}.
     */
    private static final MethodHandle SEND_BOOL_ID_LONG_ID_ID = send(JAVA_BYTE, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS);

    /**
     * Sends {@code double (id, SEL)}.
     */
    private static final MethodHandle SEND_DOUBLE = send(JAVA_DOUBLE);

    /**
     * Sends {@code CMTime (id, SEL)}.
     */
    private static final MethodHandle SEND_TIME = send(CmTime.LAYOUT);

    /**
     * Sends {@code void (id, SEL)}.
     */
    private static final MethodHandle SEND_VOID = sendVoid();

    /**
     * Sends {@code void (id, SEL, id)}.
     */
    private static final MethodHandle SEND_VOID_ID = sendVoid(ADDRESS);

    /**
     * Sends {@code void (id, SEL, id, id)}.
     */
    private static final MethodHandle SEND_VOID_ID_ID = sendVoid(ADDRESS, ADDRESS);

    /**
     * Sends {@code void (id, SEL, BOOL)}.
     */
    private static final MethodHandle SEND_VOID_BOOL = sendVoid(JAVA_BYTE);

    /**
     * Sends {@code void (id, SEL, NSInteger)} and {@code void (id, SEL, size_t)}.
     */
    private static final MethodHandle SEND_VOID_LONG = sendVoid(JAVA_LONG);

    /**
     * Sends {@code void (id, SEL, OSType)}.
     */
    private static final MethodHandle SEND_VOID_INT = sendVoid(JAVA_INT);

    /**
     * Sends {@code void (id, SEL, CMTime)}.
     */
    private static final MethodHandle SEND_VOID_TIME = sendVoid(CmTime.LAYOUT);

    /**
     * The {@code alloc} selector.
     */
    static final MemorySegment ALLOC = selector("alloc");

    /**
     * The {@code init} selector.
     */
    static final MemorySegment INIT = selector("init");

    /**
     * The {@code retain} selector.
     */
    private static final MemorySegment RETAIN = selector("retain");

    /**
     * The {@code release} selector.
     */
    private static final MemorySegment RELEASE = selector("release");

    /**
     * The {@code count} selector of {@code NSArray}.
     */
    private static final MemorySegment COUNT = selector("count");

    /**
     * The {@code objectAtIndex:} selector of {@code NSArray}.
     */
    private static final MemorySegment OBJECT_AT_INDEX = selector("objectAtIndex:");

    /**
     * Prevents instantiation.
     */
    private ObjC() {
        throw new AssertionError();
    }

    /**
     * Creates the handle of a message shape with a return value.
     *
     * @param result the return layout
     * @param arguments the layouts of the explicit arguments
     * @return the handle, taking the receiver and the selector first
     */
    private static MethodHandle send(MemoryLayout result, MemoryLayout... arguments) {
        var descriptor = FunctionDescriptor.of(result, ADDRESS, ADDRESS).appendArgumentLayouts(arguments);
        var target = MacPlatform.needsStructReturn(MacPlatform.ARM64, result)
                ? Frameworks.symbol(LIBRARY, "objc_msgSend_stret") : MSG_SEND;
        return Native.LINKER.downcallHandle(target, descriptor);
    }

    /**
     * Creates the handle of a message shape without a return value.
     *
     * @param arguments the layouts of the explicit arguments
     * @return the handle, taking the receiver and the selector first
     */
    private static MethodHandle sendVoid(MemoryLayout... arguments) {
        var descriptor = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS).appendArgumentLayouts(arguments);
        return Native.LINKER.downcallHandle(MSG_SEND, descriptor);
    }

    /**
     * Returns whether a pointer is {@code NULL} ({@code nil}).
     *
     * @return whether its address is zero
     */
    static boolean isNull(MemorySegment pointer) {
        return pointer == null || pointer.address() == 0;
    }

    /**
     * Looks up a class.
     *
     * @param name the class name
     * @return the class, or {@code NULL} when no loaded image defines it
     */
    static MemorySegment objcClass(String name) {
        try (var arena = Arena.ofConfined()) {
            return (MemorySegment) GET_CLASS.invokeExact(arena.allocateFrom(name));
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Looks up a protocol.
     *
     * @param name the protocol name
     * @return the protocol, or {@code NULL} when no loaded image defines it
     */
    static MemorySegment protocol(String name) {
        try (var arena = Arena.ofConfined()) {
            return (MemorySegment) GET_PROTOCOL.invokeExact(arena.allocateFrom(name));
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Registers (or finds) a selector.
     *
     * @param name the selector name, for example {@code "objectAtIndex:"}
     * @return the selector
     */
    static MemorySegment selector(String name) {
        try (var arena = Arena.ofConfined()) {
            return (MemorySegment) REGISTER_NAME.invokeExact(arena.allocateFrom(name));
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Allocates a class pair to be filled and registered.
     *
     * @param name the class name, unused so far in the process
     * @return the class, or {@code NULL} when the name is taken
     */
    static MemorySegment allocateClassPair(MemorySegment superclass, String name) {
        try (var arena = Arena.ofConfined()) {
            return (MemorySegment) ALLOCATE_CLASS_PAIR.invokeExact(superclass, arena.allocateFrom(name), 0L);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Adds a 64-bit integer instance variable ({@code long long}, encoded {@code q}) to a class
     * being built.
     *
     * @param cls the class, allocated and not yet registered
     * @param name the variable name
     * @return whether the variable was added
     */
    static boolean addLongIvar(MemorySegment cls, String name) {
        try (var arena = Arena.ofConfined()) {
            // class_addIvar takes log2 of the alignment: 3 for an 8-byte variable
            return (byte) ADD_IVAR.invokeExact(cls, arena.allocateFrom(name), 8L, (byte) 3, arena.allocateFrom("q")) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Adds a method to a class.
     *
     * @param cls the class
     * @param selector the method's selector
     * @param implementation the function implementing it
     * @param types the method's type encoding
     * @return whether the method was added
     */
    static boolean addMethod(MemorySegment cls, MemorySegment selector, MemorySegment implementation, String types) {
        try {
            // the runtime may keep the pointer, so the encoding lives as long as the class
            var encoding = Arena.global().allocateFrom(types);
            return (byte) ADD_METHOD.invokeExact(cls, selector, implementation, encoding) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Declares that a class conforms to a protocol.
     *
     * @param cls the class
     * @return whether the protocol was added
     */
    static boolean addProtocol(MemorySegment cls, MemorySegment protocol) {
        try {
            return (byte) ADD_PROTOCOL.invokeExact(cls, protocol) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Registers a class built with {@link #allocateClassPair(MemorySegment, String)}.
     *
     * @param cls the class
     */
    static void registerClassPair(MemorySegment cls) {
        try {
            REGISTER_CLASS_PAIR.invokeExact(cls);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the offset of an instance variable within an instance.
     *
     * @param cls the class
     * @param name the variable name
     * @return the offset in bytes
     * @throws IllegalStateException if the class has no such variable
     */
    static long ivarOffset(MemorySegment cls, String name) {
        try (var arena = Arena.ofConfined()) {
            var ivar = (MemorySegment) GET_INSTANCE_VARIABLE.invokeExact(cls, arena.allocateFrom(name));
            if (isNull(ivar)) {
                throw new IllegalStateException("missing instance variable " + name);
            }
            return (long) IVAR_OFFSET.invokeExact(ivar);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Pushes an autorelease pool on the calling thread.
     *
     * @return the token to pass to {@link #popPool(MemorySegment)} on the same thread
     */
    static MemorySegment pushPool() {
        try {
            return (MemorySegment) POOL_PUSH.invokeExact();
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Drains the autorelease pool pushed on the calling thread.
     *
     * @param token the token {@link #pushPool()} returned
     */
    static void popPool(MemorySegment token) {
        try {
            POOL_POP.invokeExact(token);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message returning an object.
     *
     * @return the result
     */
    static MemorySegment id(MemorySegment receiver, MemorySegment selector) {
        try {
            return (MemorySegment) SEND_ID.invokeExact(receiver, selector);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an object argument, returning an object.
     *
     * @param first the argument
     * @return the result
     */
    static MemorySegment id(MemorySegment receiver, MemorySegment selector, MemorySegment first) {
        try {
            return (MemorySegment) SEND_ID_ID.invokeExact(receiver, selector, first);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with two object arguments, returning an object.
     *
     * @param first the first argument
     * @param second the second argument
     * @return the result
     */
    static MemorySegment id(MemorySegment receiver, MemorySegment selector, MemorySegment first, MemorySegment second) {
        try {
            return (MemorySegment) SEND_ID_ID_ID.invokeExact(receiver, selector, first, second);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with three object arguments, returning an object.
     *
     * @param first the first argument
     * @param second the second argument
     * @param third the third argument
     * @return the result
     */
    static MemorySegment id(MemorySegment receiver, MemorySegment selector, MemorySegment first, MemorySegment second,
                            MemorySegment third) {
        try {
            return (MemorySegment) SEND_ID_ID_ID_ID.invokeExact(receiver, selector, first, second, third);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an integer argument ({@code NSUInteger} or {@code NSInteger}),
     * returning an object.
     *
     * @param value the argument
     * @return the result
     */
    static MemorySegment idWithLong(MemorySegment receiver, MemorySegment selector, long value) {
        try {
            return (MemorySegment) SEND_ID_LONG.invokeExact(receiver, selector, value);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an {@code unsigned int} argument, returning an object.
     *
     * @param value the argument
     * @return the result
     */
    static MemorySegment idWithInt(MemorySegment receiver, MemorySegment selector, int value) {
        try {
            return (MemorySegment) SEND_ID_INT.invokeExact(receiver, selector, value);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with a C array of objects and its length, returning an object.
     *
     * @param objects the array
     * @param count the number of objects
     * @return the result
     */
    static MemorySegment idWithArray(MemorySegment receiver, MemorySegment selector, MemorySegment objects, long count) {
        try {
            return (MemorySegment) SEND_ID_ID_LONG.invokeExact(receiver, selector, objects, count);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with two object arguments and an integer, returning an object.
     *
     * @param first the first argument
     * @param second the second argument
     * @param value the integer argument
     * @return the result
     */
    static MemorySegment idWithLong(MemorySegment receiver, MemorySegment selector, MemorySegment first,
                                    MemorySegment second, long value) {
        try {
            return (MemorySegment) SEND_ID_ID_ID_LONG.invokeExact(receiver, selector, first, second, value);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message returning an integer ({@code NSInteger} or {@code NSUInteger}).
     *
     * @return the result
     */
    static long integer(MemorySegment receiver, MemorySegment selector) {
        try {
            return (long) SEND_LONG.invokeExact(receiver, selector);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an object argument, returning an integer.
     *
     * @param first the argument
     * @return the result
     */
    static long integer(MemorySegment receiver, MemorySegment selector, MemorySegment first) {
        try {
            return (long) SEND_LONG_ID.invokeExact(receiver, selector, first);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message returning an {@code unsigned int} or {@code uint32_t}.
     *
     * @return the result's 32 bits
     */
    static int unsignedInt(MemorySegment receiver, MemorySegment selector) {
        try {
            return (int) SEND_INT.invokeExact(receiver, selector);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message returning a {@code BOOL}.
     *
     * @return the result
     */
    static boolean bool(MemorySegment receiver, MemorySegment selector) {
        try {
            return (byte) SEND_BOOL.invokeExact(receiver, selector) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an object argument, returning a {@code BOOL}.
     *
     * @param first the argument
     * @return the result
     */
    static boolean bool(MemorySegment receiver, MemorySegment selector, MemorySegment first) {
        try {
            return (byte) SEND_BOOL_ID.invokeExact(receiver, selector, first) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an object, an integer and an object, returning a {@code BOOL}.
     *
     * @param first the first argument
     * @param value the integer argument
     * @param third the third argument
     * @return the result
     */
    static boolean bool(MemorySegment receiver, MemorySegment selector, MemorySegment first, long value,
                        MemorySegment third) {
        try {
            return (byte) SEND_BOOL_ID_LONG_ID.invokeExact(receiver, selector, first, value, third) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an object, an integer and two objects, returning a {@code BOOL}.
     *
     * @param first the first argument
     * @param value the integer argument
     * @param third the third argument
     * @param fourth the fourth argument
     * @return the result
     */
    static boolean bool(MemorySegment receiver, MemorySegment selector, MemorySegment first, long value,
                        MemorySegment third, MemorySegment fourth) {
        try {
            return (byte) SEND_BOOL_ID_LONG_ID_ID.invokeExact(receiver, selector, first, value, third, fourth) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message returning a {@code double}.
     *
     * @return the result
     */
    static double real(MemorySegment receiver, MemorySegment selector) {
        try {
            return (double) SEND_DOUBLE.invokeExact(receiver, selector);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message returning a {@code CMTime}.
     *
     * @param receiver the receiver, not {@code nil}: a structure-returning message to {@code nil}
     * leaves the result undefined on x86_64
     * @return the result
     */
    static CmTime time(MemorySegment receiver, MemorySegment selector) {
        try (var arena = Arena.ofConfined()) {
            var result = (MemorySegment) SEND_TIME.invokeExact((SegmentAllocator) arena, receiver, selector);
            return CmTime.read(result);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message without arguments or result.
     */
    static void call(MemorySegment receiver, MemorySegment selector) {
        try {
            SEND_VOID.invokeExact(receiver, selector);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an object argument and no result.
     *
     * @param first the argument
     */
    static void call(MemorySegment receiver, MemorySegment selector, MemorySegment first) {
        try {
            SEND_VOID_ID.invokeExact(receiver, selector, first);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with two object arguments and no result.
     *
     * @param first the first argument
     * @param second the second argument
     */
    static void call(MemorySegment receiver, MemorySegment selector, MemorySegment first, MemorySegment second) {
        try {
            SEND_VOID_ID_ID.invokeExact(receiver, selector, first, second);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with a {@code BOOL} argument and no result.
     *
     * @param value the argument
     */
    static void callWithBool(MemorySegment receiver, MemorySegment selector, boolean value) {
        try {
            SEND_VOID_BOOL.invokeExact(receiver, selector, value ? (byte) 1 : (byte) 0);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with an {@code NSInteger} or {@code size_t} argument and no result.
     *
     * @param value the argument
     */
    static void callWithLong(MemorySegment receiver, MemorySegment selector, long value) {
        try {
            SEND_VOID_LONG.invokeExact(receiver, selector, value);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with a 32-bit integer argument ({@code OSType}) and no result.
     *
     * @param value the argument
     */
    static void callWithInt(MemorySegment receiver, MemorySegment selector, int value) {
        try {
            SEND_VOID_INT.invokeExact(receiver, selector, value);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Sends a message with a {@code CMTime} argument and no result.
     *
     * @param value the argument
     */
    static void callWithTime(MemorySegment receiver, MemorySegment selector, CmTime value) {
        try (var arena = Arena.ofConfined()) {
            SEND_VOID_TIME.invokeExact(receiver, selector, value.allocate(arena));
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Creates an instance with {@code [[cls alloc] init]}.
     *
     * @param cls the class
     * @return the instance, owned by the caller, or {@code NULL} when initialization failed
     */
    static MemorySegment newInstance(MemorySegment cls) {
        return id(id(cls, ALLOC), INIT);
    }

    /**
     * Retains an object.
     *
     * @param object the object, or {@code NULL}
     * @return {@code object}
     */
    static MemorySegment retain(MemorySegment object) {
        return isNull(object) ? MemorySegment.NULL : id(object, RETAIN);
    }

    /**
     * Releases an object; {@code NULL} is ignored.
     */
    static void release(MemorySegment object) {
        if (!isNull(object)) {
            call(object, RELEASE);
        }
    }

    /**
     * Returns the number of elements of an {@code NSArray}.
     *
     * @param array the array, or {@code NULL}
     * @return the count, {@code 0} for {@code NULL}
     */
    static int count(MemorySegment array) {
        return Math.clamp(integer(array, COUNT), 0, Integer.MAX_VALUE);
    }

    /**
     * Returns an element of an {@code NSArray}.
     *
     * @param index the index, below {@link #count(MemorySegment)}: an index out of range raises an
     * Objective-C exception, which aborts the process
     * @return the element
     */
    static MemorySegment element(MemorySegment array, int index) {
        return idWithLong(array, OBJECT_AT_INDEX, index);
    }
}
