package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Calls Windows Graphics Capture ({@code Windows.Graphics.Capture}, Windows 10 1903 and later) through its
 * WinRT application binary interface.
 *
 * <p>Runtime classes are reached through their activation factories ({@code RoGetActivationFactory} of
 * {@code combase.dll}, with class names as {@code HSTRING}s) and their interfaces through their virtual
 * function tables, as COM interfaces are: an {@code IInspectable} interface has the three
 * {@code IUnknown} methods and three {@code IInspectable} methods ({@code GetIids},
 * {@code GetRuntimeClassName}, {@code GetTrustLevel}) before its own, so its first method is slot
 * {@value #FIRST_METHOD}. The interface identifiers and slots are those of the Windows SDK headers
 * {@code windows.graphics.capture.h}, {@code windows.graphics.capture.interop.h} and
 * {@code windows.graphics.directx.direct3d11.interop.h}.
 */
final class GraphicsCapture {
    /**
     * The slot of the first method of an {@code IInspectable} interface.
     */
    static final int FIRST_METHOD = 6;

    /**
     * {@code IGraphicsCaptureItemInterop}, an {@code IUnknown} interface of the item's factory.
     */
    static final MemorySegment IID_ITEM_INTEROP = Com.guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356");

    /**
     * {@code IGraphicsCaptureItem}.
     */
    static final MemorySegment IID_ITEM = Com.guid("79C3F95B-31F7-4EC2-A464-632EF5D30760");

    /**
     * {@code IGraphicsCaptureSessionStatics}.
     */
    static final MemorySegment IID_SESSION_STATICS = Com.guid("2224A540-5974-49AA-B232-0882536F4CB5");

    /**
     * {@code IGraphicsCaptureSession2}, which switches the pointer.
     */
    static final MemorySegment IID_SESSION2 = Com.guid("2C39AE40-7D2E-5044-804E-8B6799D4CF9E");

    /**
     * {@code IGraphicsCaptureSession3}, which switches the yellow capture border.
     */
    static final MemorySegment IID_SESSION3 = Com.guid("F2CDD966-22AE-5EA1-9596-3A289344C3BE");

    /**
     * {@code IDirect3D11CaptureFramePoolStatics2}, whose pool needs no dispatcher queue.
     */
    static final MemorySegment IID_POOL_STATICS2 = Com.guid("589B103F-6BBC-5DF5-A991-02E28B3B66D5");

    /**
     * {@code IDirect3DDevice}.
     */
    static final MemorySegment IID_DIRECT3D_DEVICE = Com.guid("A37624AB-8D5F-4650-9D3E-9EAE3D9BC670");

    /**
     * {@code IDirect3DDxgiInterfaceAccess}, an {@code IUnknown} interface of a surface.
     */
    static final MemorySegment IID_DXGI_ACCESS = Com.guid("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1");

    /**
     * {@code IDXGIDevice}.
     */
    static final MemorySegment IID_DXGI_DEVICE = Com.guid("54EC77FA-1377-44E6-8C32-88FD5F44C84C");

    /**
     * {@code IClosable}.
     */
    static final MemorySegment IID_CLOSABLE = Com.guid("30D5A829-7FA4-4026-83BB-D75BAE4EA99E");

    /**
     * {@code IGraphicsCaptureItemInterop::CreateForWindow}.
     */
    static final int INTEROP_CREATE_FOR_WINDOW = 3;

    /**
     * {@code IGraphicsCaptureItem::get_Size}.
     */
    static final int ITEM_GET_SIZE = FIRST_METHOD + 1;

    /**
     * {@code IGraphicsCaptureSessionStatics::IsSupported}.
     */
    static final int STATICS_IS_SUPPORTED = FIRST_METHOD;

    /**
     * {@code IGraphicsCaptureSession::StartCapture}.
     */
    static final int SESSION_START_CAPTURE = FIRST_METHOD;

    /**
     * {@code IGraphicsCaptureSession2::put_IsCursorCaptureEnabled}.
     */
    static final int SESSION2_PUT_CURSOR = FIRST_METHOD + 1;

    /**
     * {@code IGraphicsCaptureSession3::put_IsBorderRequired}.
     */
    static final int SESSION3_PUT_BORDER = FIRST_METHOD + 1;

    /**
     * {@code IDirect3D11CaptureFramePoolStatics2::CreateFreeThreaded}.
     */
    static final int POOL_STATICS2_CREATE_FREE_THREADED = FIRST_METHOD;

    /**
     * {@code IDirect3D11CaptureFramePool::Recreate}.
     */
    static final int POOL_RECREATE = FIRST_METHOD;

    /**
     * {@code IDirect3D11CaptureFramePool::TryGetNextFrame}.
     */
    static final int POOL_TRY_GET_NEXT_FRAME = FIRST_METHOD + 1;

    /**
     * {@code IDirect3D11CaptureFramePool::CreateCaptureSession}.
     */
    static final int POOL_CREATE_CAPTURE_SESSION = FIRST_METHOD + 4;

    /**
     * {@code IDirect3D11CaptureFrame::get_Surface}.
     */
    static final int FRAME_GET_SURFACE = FIRST_METHOD;

    /**
     * {@code IDirect3D11CaptureFrame::get_ContentSize}.
     */
    static final int FRAME_GET_CONTENT_SIZE = FIRST_METHOD + 2;

    /**
     * {@code IDirect3DDxgiInterfaceAccess::GetInterface}.
     */
    static final int ACCESS_GET_INTERFACE = 3;

    /**
     * {@code IClosable::Close}.
     */
    static final int CLOSABLE_CLOSE = FIRST_METHOD;

    /**
     * {@code DirectXPixelFormat.B8G8R8A8UIntNormalized}.
     */
    static final int PIXEL_FORMAT_BGRA8 = 87;

    /**
     * The layout of {@code Windows.Graphics.SizeInt32}.
     */
    static final StructLayout SIZE = MemoryLayout.structLayout(JAVA_INT.withName("Width"), JAVA_INT.withName("Height"))
            .withName("SizeInt32");

    /**
     * The class name of {@code GraphicsCaptureItem}.
     */
    static final String ITEM_CLASS = "Windows.Graphics.Capture.GraphicsCaptureItem";

    /**
     * The class name of {@code GraphicsCaptureSession}.
     */
    static final String SESSION_CLASS = "Windows.Graphics.Capture.GraphicsCaptureSession";

    /**
     * The class name of {@code Direct3D11CaptureFramePool}.
     */
    static final String POOL_CLASS = "Windows.Graphics.Capture.Direct3D11CaptureFramePool";

    /**
     * Invokes {@code HRESULT f(this, IDirect3DDevice*, DirectXPixelFormat, INT32, SizeInt32, void**)}, the
     * shape of {@code CreateFreeThreaded} and {@code Recreate} (without the last argument).
     */
    private static final MethodHandle CREATE_POOL = Native.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT,
            ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, SIZE, ADDRESS));

    /**
     * Invokes {@code HRESULT f(this, IDirect3DDevice*, DirectXPixelFormat, INT32, SizeInt32)}.
     */
    private static final MethodHandle RECREATE_POOL = Native.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT,
            ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, SIZE));

    /**
     * Invokes {@code HRESULT f(this, boolean)}.
     */
    private static final MethodHandle PUT_BOOLEAN = Native.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT,
            ADDRESS, JAVA_BYTE));

    private static final Functions FUNCTIONS = Functions.load();

    private GraphicsCapture() {
        throw new AssertionError();
    }

    /**
     * The linked functions of {@code combase.dll} and {@code d3d11.dll}.
     */
    private record Functions(MethodHandle createString, MethodHandle deleteString, MethodHandle activationFactory,
                             MethodHandle createDevice) {
        static Functions load() {
            var combase = Native.library("combase").orElse(null);
            var d3d11 = Native.library("d3d11").orElse(null);
            if (combase == null || d3d11 == null) {
                return null;
            }
            try {
                return new Functions(
                        Native.function(combase, "WindowsCreateString", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)),
                        Native.function(combase, "WindowsDeleteString", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(combase, "RoGetActivationFactory", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(d3d11, "CreateDirect3D11DeviceFromDXGIDevice", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }
    }

    /**
     * {@return whether the WinRT functions are present}
     */
    static boolean linked() {
        return FUNCTIONS != null;
    }

    /**
     * Returns an activation factory of a runtime class.
     *
     * @param className the runtime class
     * @param iid the factory interface
     * @param slot a pointer-sized slot receiving the factory
     * @return the {@code HRESULT}; the factory is in {@code slot} when it succeeds
     */
    static int factory(String className, MemorySegment iid, MemorySegment slot) {
        try (var arena = Arena.ofConfined()) {
            var characters = arena.allocate(JAVA_CHAR, className.length() + 1L);
            for (var index = 0; index < className.length(); index++) {
                characters.setAtIndex(JAVA_CHAR, index, className.charAt(index));
            }
            var string = arena.allocate(ADDRESS);
            var hr = (int) FUNCTIONS.createString().invokeExact(characters, className.length(), string);
            if (hr < 0) {
                return hr;
            }
            try {
                slot.set(ADDRESS, 0, MemorySegment.NULL);
                return (int) FUNCTIONS.activationFactory().invokeExact(string.get(ADDRESS, 0), iid, slot);
            } finally {
                var ignored = (int) FUNCTIONS.deleteString().invokeExact(string.get(ADDRESS, 0));
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether the system supports Windows Graphics Capture.
     *
     * <p>Runs on a thread in the multithreaded apartment.
     *
     * @return whether {@code GraphicsCaptureSession.IsSupported()} is true
     */
    static boolean supported() {
        if (FUNCTIONS == null) {
            return false;
        }
        try (var arena = Arena.ofConfined()) {
            var slot = arena.allocate(ADDRESS);
            if (factory(SESSION_CLASS, IID_SESSION_STATICS, slot) < 0) {
                return false;
            }
            var statics = slot.get(ADDRESS, 0);
            try {
                var result = arena.allocate(JAVA_BYTE);
                return Com.call(statics, STATICS_IS_SUPPORTED, result) >= 0 && result.get(JAVA_BYTE, 0) != 0;
            } finally {
                Com.release(statics);
            }
        }
    }

    /**
     * Wraps a DXGI device as a WinRT {@code IDirect3DDevice}.
     *
     * @param dxgiDevice the {@code IDXGIDevice}
     * @param slot a pointer-sized slot receiving the {@code IInspectable}
     * @return the {@code HRESULT}
     */
    static int wrapDevice(MemorySegment dxgiDevice, MemorySegment slot) {
        try {
            return (int) FUNCTIONS.createDevice().invokeExact(dxgiDevice, slot);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code IDirect3D11CaptureFramePoolStatics2::CreateFreeThreaded}.
     *
     * @param statics the pool's statics
     * @param device the {@code IDirect3DDevice}
     * @param buffers the number of frame buffers
     * @param width the frame width
     * @param height the frame height
     * @param slot a pointer-sized slot receiving the pool
     * @return the {@code HRESULT}
     */
    static int createPool(MemorySegment statics, MemorySegment device, int buffers, int width, int height,
                          MemorySegment slot) {
        try (var arena = Arena.ofConfined()) {
            var size = size(arena, width, height);
            return (int) CREATE_POOL.invokeExact(Com.method(statics, POOL_STATICS2_CREATE_FREE_THREADED), statics, device,
                    PIXEL_FORMAT_BGRA8, buffers, size, slot);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls {@code IDirect3D11CaptureFramePool::Recreate} with a new frame size.
     *
     * @param pool the frame pool to resize
     * @param device the {@code IDirect3DDevice}
     * @param buffers the number of frame buffers
     * @param width the frame width
     * @param height the frame height
     * @return the {@code HRESULT}
     */
    static int recreatePool(MemorySegment pool, MemorySegment device, int buffers, int width, int height) {
        try (var arena = Arena.ofConfined()) {
            var size = size(arena, width, height);
            return (int) RECREATE_POOL.invokeExact(Com.method(pool, POOL_RECREATE), pool, device, PIXEL_FORMAT_BGRA8,
                    buffers, size);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Calls a {@code put_} method taking a WinRT {@code boolean}.
     *
     * @param self the interface
     * @param slot the vtable slot
     * @param value the new value of the property
     * @return the {@code HRESULT}
     */
    static int putBoolean(MemorySegment self, int slot, boolean value) {
        try {
            return (int) PUT_BOOLEAN.invokeExact(Com.method(self, slot), self, value ? (byte) 1 : (byte) 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Closes an object through its {@code IClosable} interface, if it has one.
     *
     * @param object the object, or {@link MemorySegment#NULL}
     */
    static void close(MemorySegment object) {
        if (object == null || object.equals(MemorySegment.NULL)) {
            return;
        }
        try (var arena = Arena.ofConfined()) {
            var closable = Com.query(object, IID_CLOSABLE, arena.allocate(ADDRESS));
            if (!closable.equals(MemorySegment.NULL)) {
                Com.call(closable, CLOSABLE_CLOSE);
                Com.release(closable);
            }
        }
    }

    private static MemorySegment size(Arena arena, int width, int height) {
        var size = arena.allocate(SIZE);
        size.set(JAVA_INT, 0, width);
        size.set(JAVA_INT, 4, height);
        return size;
    }
}
