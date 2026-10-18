package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Calls CoreFoundation: reference counting, equality, strings and arrays.
 *
 * <p>{@code CFStringRef}, {@code CFArrayRef}, {@code CFDictionaryRef} and {@code CFNumberRef} are
 * toll-free bridged with {@code NSString}, {@code NSArray}, {@code NSDictionary} and
 * {@code NSNumber}, so the functions here also accept the Objective-C objects. This class loads
 * the framework (and Foundation, which defines {@code NSNumber} and {@code NSBundle}) when it is
 * initialized, which only happens on macOS.
 */
@SuppressWarnings("restricted")
final class CoreFoundation {
    /**
     * The framework.
     */
    private static final SymbolLookup LIBRARY = Frameworks.load(Frameworks.CORE_FOUNDATION);

    /**
     * Foundation, loaded so that its classes are registered with the runtime.
     */
    @SuppressWarnings("unused")
    private static final SymbolLookup FOUNDATION = Frameworks.load(Frameworks.FOUNDATION);

    /**
     * {@code void CFRelease(CFTypeRef cf)}.
     */
    private static final MethodHandle RELEASE = Native.function(LIBRARY, "CFRelease",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code Boolean CFEqual(CFTypeRef cf1, CFTypeRef cf2)}.
     */
    private static final MethodHandle EQUAL = Native.function(LIBRARY, "CFEqual",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));

    /**
     * {@code CFStringRef CFStringCreateWithCharacters(CFAllocatorRef alloc, const UniChar *chars,
     * CFIndex numChars)}.
     */
    private static final MethodHandle CREATE_STRING = Native.function(LIBRARY, "CFStringCreateWithCharacters",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

    /**
     * {@code CFIndex CFStringGetLength(CFStringRef theString)}.
     */
    private static final MethodHandle STRING_LENGTH = Native.function(LIBRARY, "CFStringGetLength",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code void CFStringGetCharacters(CFStringRef theString, CFRange range, UniChar *buffer)}.
     */
    private static final MethodHandle STRING_CHARACTERS = Native.function(LIBRARY, "CFStringGetCharacters",
            FunctionDescriptor.ofVoid(ADDRESS, NativeLayouts.CF_RANGE, ADDRESS));

    /**
     * {@code CFIndex CFArrayGetCount(CFArrayRef theArray)}.
     */
    private static final MethodHandle ARRAY_COUNT = Native.function(LIBRARY, "CFArrayGetCount",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code const void *CFArrayGetValueAtIndex(CFArrayRef theArray, CFIndex idx)}.
     */
    private static final MethodHandle ARRAY_VALUE = Native.function(LIBRARY, "CFArrayGetValueAtIndex",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));

    /**
     * {@code const void *CFDictionaryGetValue(CFDictionaryRef theDict, const void *key)}.
     */
    private static final MethodHandle DICTIONARY_VALUE = Native.function(LIBRARY, "CFDictionaryGetValue",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    /**
     * The longest string converted to Java, a guard against a corrupt length.
     */
    private static final long MAX_STRING = 1 << 20;

    /**
     * Prevents instantiation.
     */
    private CoreFoundation() {
        throw new AssertionError();
    }

    /**
     * Releases a CoreFoundation object; {@code NULL} is ignored.
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

    /**
     * Compares two CoreFoundation objects.
     *
     * @param first an object, or {@code NULL}
     * @param second another object, or {@code NULL}
     * @return whether both are non-{@code NULL} and equal
     */
    static boolean equal(MemorySegment first, MemorySegment second) {
        if (ObjC.isNull(first) || ObjC.isNull(second)) {
            return false;
        }
        try {
            return (byte) EQUAL.invokeExact(first, second) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Creates a string holding a Java string's UTF-16 code units.
     *
     * @param value the text
     * @return the string, owned by the caller, who releases it with {@link #release(MemorySegment)}
     */
    static MemorySegment createString(String value) {
        try (var arena = Arena.ofConfined()) {
            var units = value.toCharArray();
            var buffer = arena.allocate(JAVA_CHAR, Math.max(1, units.length));
            MemorySegment.copy(units, 0, buffer, JAVA_CHAR, 0, units.length);
            return (MemorySegment) CREATE_STRING.invokeExact(MemorySegment.NULL, buffer, (long) units.length);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Converts a string to Java.
     *
     * @param string the {@code CFStringRef} or {@code NSString}, or {@code NULL}
     * @return the text, or {@code null} for {@code NULL}
     */
    static String string(MemorySegment string) {
        if (ObjC.isNull(string)) {
            return null;
        }
        try (var arena = Arena.ofConfined()) {
            var length = (long) STRING_LENGTH.invokeExact(string);
            if (length <= 0) {
                return "";
            }
            length = Math.min(length, MAX_STRING);
            var range = arena.allocate(NativeLayouts.CF_RANGE);
            range.set(JAVA_LONG, 0, 0L);
            range.set(JAVA_LONG, 8, length);
            var buffer = arena.allocate(JAVA_CHAR, length);
            STRING_CHARACTERS.invokeExact(string, range, buffer);
            return new String(buffer.toArray(JAVA_CHAR));
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the number of elements of an array.
     *
     * @param array the array, or {@code NULL}
     * @return the count, {@code 0} for {@code NULL}
     */
    static long arrayCount(MemorySegment array) {
        if (ObjC.isNull(array)) {
            return 0;
        }
        try {
            return (long) ARRAY_COUNT.invokeExact(array);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns an element of an array.
     *
     * @param index the index, below {@link #arrayCount(MemorySegment)}
     * @return the element, not retained
     */
    static MemorySegment arrayValue(MemorySegment array, long index) {
        try {
            return (MemorySegment) ARRAY_VALUE.invokeExact(array, index);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the value of a dictionary key.
     *
     * @param dictionary the dictionary, or {@code NULL}
     * @return the value, not retained, or {@code NULL} when absent
     */
    static MemorySegment dictionaryValue(MemorySegment dictionary, MemorySegment key) {
        if (ObjC.isNull(dictionary) || ObjC.isNull(key)) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) DICTIONARY_VALUE.invokeExact(dictionary, key);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }
}
