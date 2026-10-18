package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

/**
 * Calls Component Object Model interfaces and Win32 functions through the Foreign Function and
 * Memory API.
 *
 * <p>A COM interface pointer points to an object whose first field is a pointer to its virtual
 * function table; method {@code n} of the interface (counting the three {@code IUnknown} methods
 * {@code QueryInterface}, {@code AddRef} and {@code Release} as {@code 0}, {@code 1} and {@code 2})
 * is the {@code n}-th pointer of that table and takes the interface pointer as its first
 * argument. The {@code call} overloads look the slot up and invoke it with one of a fixed set of
 * argument shapes, each backed by a single downcall handle that takes the target address as its
 * leading argument, so no handle is created per call. Every method returns the raw
 * {@code HRESULT}; {@link #check(int, String)} turns a failure into a {@link MediaException.Io}.
 *
 * <p>The class also owns COM initialization of the calling thread and the few helpers shared by
 * the Media Foundation, DXGI and GDI bindings: library lookup, GUID constants and UTF-16 string
 * decoding.
 */
final class Com {
    /**
     * The {@code E_NOINTERFACE} result of {@code QueryInterface} for an unsupported interface.
     */
    static final int E_NOINTERFACE = 0x80004002;

    /**
     * The {@code E_ACCESSDENIED} result.
     */
    static final int E_ACCESSDENIED = 0x80070005;

    /**
     * The {@code RPC_E_CHANGED_MODE} result of {@code CoInitializeEx} on a thread already
     * initialized in another apartment model.
     */
    static final int RPC_E_CHANGED_MODE = 0x80010106;

    /**
     * The {@code COINIT_MULTITHREADED} flag of {@code CoInitializeEx}.
     */
    private static final int COINIT_MULTITHREADED = 0x0;

    /**
     * The {@code COINIT_DISABLE_OLE1DDE} flag of {@code CoInitializeEx}.
     */
    private static final int COINIT_DISABLE_OLE1DDE = 0x4;

    /**
     * The vtable slot of {@code IUnknown::QueryInterface}.
     */
    private static final int QUERY_INTERFACE = 0;

    /**
     * The vtable slot of {@code IUnknown::AddRef}.
     */
    private static final int ADD_REF = 1;

    /**
     * The vtable slot of {@code IUnknown::Release}.
     */
    private static final int RELEASE = 2;

    /**
     * Invokes {@code HRESULT f(this)}.
     */
    private static final MethodHandle P = shape(JAVA_INT, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, void*)}.
     */
    private static final MethodHandle P_P = shape(JAVA_INT, ADDRESS, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, void*, void*)}.
     */
    private static final MethodHandle P_PP = shape(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, void*, void*, void*)}.
     */
    private static final MethodHandle P_PPP = shape(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, void*, UINT)}.
     */
    private static final MethodHandle P_PI = shape(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);

    /**
     * Invokes {@code HRESULT f(this, UINT)}.
     */
    private static final MethodHandle P_I = shape(JAVA_INT, ADDRESS, JAVA_INT);

    /**
     * Invokes {@code HRESULT f(this, UINT, UINT)}.
     */
    private static final MethodHandle P_II = shape(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);

    /**
     * Invokes {@code HRESULT f(this, UINT, void*)}.
     */
    private static final MethodHandle P_IP = shape(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, UINT, UINT, void*)}.
     */
    private static final MethodHandle P_IIP = shape(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, UINT, void*, void*)}.
     */
    private static final MethodHandle P_IPP = shape(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, UINT, void*, void*, void*)}.
     */
    private static final MethodHandle P_IPPP = shape(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, UINT, void*, void*, void*, void*)}.
     */
    private static final MethodHandle P_IPPPP = shape(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, UINT, UINT, void*, void*, void*, void*)}.
     */
    private static final MethodHandle P_IIPPPP = shape(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, void*, UINT, UINT, UINT, void*)}.
     */
    private static final MethodHandle P_PIIIP = shape(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);

    /**
     * Invokes {@code HRESULT f(this, void*, UINT, UINT, void*, void*)}.
     */
    private static final MethodHandle P_PIIPP = shape(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);

    /**
     * Invokes {@code void f(this, void*)}.
     */
    private static final MethodHandle V_P = shape(null, ADDRESS, ADDRESS);

    /**
     * Invokes {@code void f(this, void*, void*)}.
     */
    private static final MethodHandle V_PP = shape(null, ADDRESS, ADDRESS, ADDRESS);

    /**
     * Invokes {@code void f(this, void*, UINT)}.
     */
    private static final MethodHandle V_PI = shape(null, ADDRESS, ADDRESS, JAVA_INT);

    /**
     * The COM functions of {@code ole32.dll}, or {@code null} when the library cannot be loaded.
     */
    private static final Ole32 OLE32 = Ole32.load();

    /**
     * Prevents instantiation.
     */
    private Com() {
        throw new AssertionError();
    }

    /**
     * Binds the COM functions of {@code ole32.dll}.
     *
     * @param initialize {@code HRESULT CoInitializeEx(void*, DWORD)}
     * @param uninitialize {@code void CoUninitialize()}
     * @param free {@code void CoTaskMemFree(void*)}
     */
    private record Ole32(MethodHandle initialize, MethodHandle uninitialize, MethodHandle free) {
        /**
         * Loads the library and binds the functions.
         *
         * @return the bindings, or {@code null} when the library or a function is missing
         */
        static Ole32 load() {
            var library = Native.library("ole32").orElse(null);
            if (library == null) {
                return null;
            }
            try {
                return new Ole32(
                        Native.function(library, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(library, "CoUninitialize", FunctionDescriptor.ofVoid()),
                        Native.function(library, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS)));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }
    }

    /**
     * Creates a downcall handle for a function pointer supplied at invocation time.
     *
     * @param result the return layout, or {@code null} for {@code void}
     * @param arguments the argument layouts
     * @return the handle, whose first parameter is the function address
     */
    private static MethodHandle shape(MemoryLayout result, MemoryLayout... arguments) {
        var descriptor = result == null ? FunctionDescriptor.ofVoid(arguments) : FunctionDescriptor.of(result, arguments);
        return Native.LINKER.downcallHandle(descriptor);
    }

    /**
     * Returns whether the COM runtime is available.
     *
     * @return whether {@code ole32.dll} was bound
     */
    static boolean available() {
        return OLE32 != null;
    }

    /**
     * Joins the calling thread to the multithreaded apartment.
     *
     * @return whether the call must be balanced by {@link #uninitialize()}: {@code false} when the
     * thread already belongs to a single-threaded apartment, which serves the free-threaded
     * objects this package uses equally well
     * @throws MediaException.Io if COM cannot be initialized
     */
    static boolean initialize() {
        if (OLE32 == null) {
            throw failure("COM is not available", 0x80004005);
        }
        int result;
        try {
            result = (int) OLE32.initialize().invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED | COINIT_DISABLE_OLE1DDE);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        if (result == RPC_E_CHANGED_MODE) {
            return false;
        }
        check(result, "CoInitializeEx");
        return true;
    }

    /**
     * Leaves the apartment joined by a successful {@link #initialize()}.
     */
    static void uninitialize() {
        try {
            OLE32.uninitialize().invokeExact();
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Frees memory allocated by the COM task allocator.
     *
     * @param pointer the memory, or {@link MemorySegment#NULL}
     */
    static void free(MemorySegment pointer) {
        if (OLE32 == null || pointer.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            OLE32.free().invokeExact(pointer);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the address of a method of a COM object.
     *
     * @param self the interface pointer
     * @param slot the method's index in the virtual function table
     * @return the method's address
     */
    static MemorySegment method(MemorySegment self, int slot) {
        var table = self.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return table.reinterpret(ADDRESS.byteSize() * (slot + 1L)).getAtIndex(ADDRESS, slot);
    }

    /**
     * Adds a reference to a COM object.
     *
     * @param self the interface pointer
     */
    static void addRef(MemorySegment self) {
        call(self, ADD_REF);
    }

    /**
     * Releases a reference to a COM object.
     *
     * @param self the interface pointer; {@code null} and {@link MemorySegment#NULL} are ignored
     */
    static void release(MemorySegment self) {
        if (self != null && !self.equals(MemorySegment.NULL)) {
            call(self, RELEASE);
        }
    }

    /**
     * Asks a COM object for another of its interfaces.
     *
     * @param self the interface pointer
     * @param iid the identifier of the requested interface
     * @param result a pointer-sized slot receiving the interface pointer
     * @return the interface pointer, or {@link MemorySegment#NULL} when the object does not
     * implement it
     */
    static MemorySegment query(MemorySegment self, MemorySegment iid, MemorySegment result) {
        var hr = call(self, QUERY_INTERFACE, iid, result);
        return hr < 0 ? MemorySegment.NULL : result.get(ADDRESS, 0);
    }

    /**
     * Throws when an {@code HRESULT} reports a failure.
     *
     * @param operation the name of the failed call, reported in the message
     * @throws MediaException.Io if {@code result} is negative
     */
    static void check(int result, String operation) {
        if (result < 0) {
            throw failure(operation + " failed", result);
        }
    }

    /**
     * Builds the failure raised for a failed call.
     *
     * @param message the description of the failure
     * @param result the {@code HRESULT} or Win32 error code
     * @return the exception
     */
    static MediaException.Io failure(String message, int result) {
        var detail = message + String.format(Locale.ROOT, " (0x%08X)", result);
        return new MediaException.Io(detail, new IOException(detail));
    }

    /**
     * Allocates a GUID in process-lifetime memory.
     *
     * @param text the GUID in its canonical {@code 8-4-4-4-12} hexadecimal form
     * @return the 16-byte {@code GUID} structure
     */
    static MemorySegment guid(String text) {
        var hex = HexFormat.of();
        var digits = text.replace("-", "");
        var segment = Arena.global().allocate(16, 4);
        segment.set(JAVA_INT_UNALIGNED, 0, (int) Long.parseLong(digits, 0, 8, 16));
        segment.set(JAVA_SHORT_UNALIGNED, 4, (short) Integer.parseInt(digits, 8, 12, 16));
        segment.set(JAVA_SHORT_UNALIGNED, 6, (short) Integer.parseInt(digits, 12, 16, 16));
        var tail = hex.parseHex(digits, 16, 32);
        for (var i = 0; i < 8; i++) {
            segment.set(JAVA_BYTE, 8 + i, tail[i]);
        }
        return segment;
    }

    /**
     * Returns the {@code Data1} field of a GUID, which holds the FourCC of the video subtypes.
     *
     * @param guid the 16-byte {@code GUID} structure
     * @return the first 32-bit field
     */
    static int data1(MemorySegment guid) {
        return guid.get(JAVA_INT_UNALIGNED, 0);
    }

    /**
     * Returns whether two GUIDs are equal.
     *
     * @param first the first 16-byte structure
     * @param second the second 16-byte structure
     * @return whether all bytes are equal
     */
    static boolean sameGuid(MemorySegment first, MemorySegment second) {
        return first.asSlice(0, 16).mismatch(second.asSlice(0, 16)) == -1;
    }

    /**
     * Decodes a null-terminated UTF-16 string stored in a fixed-size field.
     *
     * @return the string up to the first null character
     */
    static String wideString(MemorySegment field) {
        var length = 0L;
        while (length + 1 < field.byteSize() && field.get(JAVA_SHORT_UNALIGNED, length) != 0) {
            length += 2;
        }
        return new String(field.asSlice(0, length).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
    }

    /**
     * Invokes a method taking no argument.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @return the {@code HRESULT}, or the {@code ULONG} of {@code AddRef} and {@code Release}
     */
    static int call(MemorySegment self, int slot) {
        try {
            return (int) P.invokeExact(method(self, slot), self);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a pointer.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, MemorySegment a) {
        try {
            return (int) P_P.invokeExact(method(self, slot), self, a);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking two pointers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, MemorySegment a, MemorySegment b) {
        try {
            return (int) P_PP.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking three pointers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @param c the third argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, MemorySegment a, MemorySegment b, MemorySegment c) {
        try {
            return (int) P_PPP.invokeExact(method(self, slot), self, a, b, c);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a pointer and a 32-bit integer.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, MemorySegment a, int b) {
        try {
            return (int) P_PI.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a 32-bit integer.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, int a) {
        try {
            return (int) P_I.invokeExact(method(self, slot), self, a);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking two 32-bit integers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, int a, int b) {
        try {
            return (int) P_II.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a 32-bit integer and a pointer.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, int a, MemorySegment b) {
        try {
            return (int) P_IP.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking two 32-bit integers and a pointer.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @param c the third argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, int a, int b, MemorySegment c) {
        try {
            return (int) P_IIP.invokeExact(method(self, slot), self, a, b, c);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a 32-bit integer and two pointers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @param c the third argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, int a, MemorySegment b, MemorySegment c) {
        try {
            return (int) P_IPP.invokeExact(method(self, slot), self, a, b, c);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a 32-bit integer and three pointers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @param c the third argument
     * @param d the fourth argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, int a, MemorySegment b, MemorySegment c, MemorySegment d) {
        try {
            return (int) P_IPPP.invokeExact(method(self, slot), self, a, b, c, d);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a 32-bit integer and four pointers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @param c the third argument
     * @param d the fourth argument
     * @param e the fifth argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, int a, MemorySegment b, MemorySegment c, MemorySegment d, MemorySegment e) {
        try {
            return (int) P_IPPPP.invokeExact(method(self, slot), self, a, b, c, d, e);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking two 32-bit integers and four pointers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @param c the third argument
     * @param d the fourth argument
     * @param e the fifth argument
     * @param f the sixth argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, int a, int b, MemorySegment c, MemorySegment d, MemorySegment e, MemorySegment f) {
        try {
            return (int) P_IIPPPP.invokeExact(method(self, slot), self, a, b, c, d, e, f);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a pointer, three 32-bit integers and a pointer.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @param c the third argument
     * @param d the fourth argument
     * @param e the fifth argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, MemorySegment a, int b, int c, int d, MemorySegment e) {
        try {
            return (int) P_PIIIP.invokeExact(method(self, slot), self, a, b, c, d, e);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking a pointer, two 32-bit integers and two pointers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     * @param c the third argument
     * @param d the fourth argument
     * @param e the fifth argument
     * @return the {@code HRESULT}
     */
    static int call(MemorySegment self, int slot, MemorySegment a, int b, int c, MemorySegment d, MemorySegment e) {
        try {
            return (int) P_PIIPP.invokeExact(method(self, slot), self, a, b, c, d, e);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a {@code void} method taking a pointer.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the argument
     */
    static void callVoid(MemorySegment self, int slot, MemorySegment a) {
        try {
            V_P.invokeExact(method(self, slot), self, a);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a {@code void} method taking two pointers.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     */
    static void callVoid(MemorySegment self, int slot, MemorySegment a, MemorySegment b) {
        try {
            V_PP.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a {@code void} method taking a pointer and a 32-bit integer.
     *
     * @param self the interface pointer
     * @param slot the vtable slot
     * @param a the first argument
     * @param b the second argument
     */
    static void callVoid(MemorySegment self, int slot, MemorySegment a, int b) {
        try {
            V_PI.invokeExact(method(self, slot), self, a, b);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }
}
