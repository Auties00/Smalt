package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Binds the DXGI and Direct3D 11 functions, interface identifiers, vtable slots, constants and
 * structure offsets the screen backend uses.
 *
 * <p>The vtable slots are the method indices declared in the Windows SDK headers {@code dxgi.h},
 * {@code dxgi1_2.h}, {@code dxgi1_5.h} and {@code d3d11.h}, counting the three {@code IUnknown}
 * methods; the structure offsets are those of the 64-bit layouts of the same headers.
 */
final class Dxgi {

    /**
     * The {@code DXGI_ERROR_NOT_FOUND} result of an enumeration past its end.
     */
    static final int ERROR_NOT_FOUND = 0x887A0002;

    /**
     * The {@code DXGI_ERROR_UNSUPPORTED} result of a duplication the desktop mode does not allow.
     */
    static final int ERROR_UNSUPPORTED = 0x887A0004;

    /**
     * The {@code DXGI_ERROR_NOT_CURRENTLY_AVAILABLE} result of a duplication beyond the system's
     * limit of concurrent duplications.
     */
    static final int ERROR_NOT_CURRENTLY_AVAILABLE = 0x887A0022;

    /**
     * The {@code DXGI_ERROR_ACCESS_LOST} result of a duplication invalidated by a desktop switch
     * or mode change.
     */
    static final int ERROR_ACCESS_LOST = 0x887A0026;

    /**
     * The {@code DXGI_ERROR_WAIT_TIMEOUT} result of {@code AcquireNextFrame} when the desktop did
     * not change.
     */
    static final int ERROR_WAIT_TIMEOUT = 0x887A0027;

    /**
     * The {@code DXGI_FORMAT_B8G8R8A8_UNORM} format.
     */
    static final int FORMAT_B8G8R8A8_UNORM = 87;

    /**
     * The {@code DXGI_FORMAT_B8G8R8A8_UNORM_SRGB} format.
     */
    static final int FORMAT_B8G8R8A8_UNORM_SRGB = 91;

    /**
     * The {@code DXGI_MODE_ROTATION_ROTATE90} rotation.
     */
    static final int ROTATE_90 = 2;

    /**
     * The {@code DXGI_MODE_ROTATION_ROTATE180} rotation.
     */
    static final int ROTATE_180 = 3;

    /**
     * The {@code DXGI_MODE_ROTATION_ROTATE270} rotation.
     */
    static final int ROTATE_270 = 4;

    /**
     * The {@code D3D_DRIVER_TYPE_UNKNOWN} driver type, required with an explicit adapter.
     */
    static final int DRIVER_TYPE_UNKNOWN = 0;

    /**
     * The {@code D3D_DRIVER_TYPE_HARDWARE} driver type.
     */
    static final int DRIVER_TYPE_HARDWARE = 1;

    /**
     * The {@code D3D_DRIVER_TYPE_WARP} driver type, the software rasterizer.
     */
    static final int DRIVER_TYPE_WARP = 5;

    /**
     * The {@code D3D11_CREATE_DEVICE_BGRA_SUPPORT} creation flag.
     */
    static final int CREATE_DEVICE_BGRA_SUPPORT = 0x20;

    /**
     * The {@code D3D11_SDK_VERSION}.
     */
    static final int SDK_VERSION = 7;

    /**
     * The {@code D3D11_USAGE_STAGING} usage.
     */
    static final int USAGE_STAGING = 3;

    /**
     * The {@code D3D11_CPU_ACCESS_READ} flag.
     */
    static final int CPU_ACCESS_READ = 0x20000;

    /**
     * The {@code D3D11_MAP_READ} map type.
     */
    static final int MAP_READ = 1;

    /**
     * The {@code DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MONOCHROME} pointer type.
     */
    static final int POINTER_MONOCHROME = 1;

    /**
     * The {@code DXGI_OUTDUPL_POINTER_SHAPE_TYPE_COLOR} pointer type.
     */
    static final int POINTER_COLOR = 2;

    /**
     * The {@code DXGI_OUTDUPL_POINTER_SHAPE_TYPE_MASKED_COLOR} pointer type.
     */
    static final int POINTER_MASKED_COLOR = 4;

    /**
     * The identifier of the {@code IDXGIFactory1} interface.
     */
    static final MemorySegment IID_FACTORY1 = Com.guid("770aae78-f26f-4dba-a829-253c83d1b387");

    /**
     * The identifier of the {@code IDXGIOutput1} interface.
     */
    static final MemorySegment IID_OUTPUT1 = Com.guid("00cddea8-939b-4b83-a340-a685226666cc");

    /**
     * The identifier of the {@code IDXGIOutput5} interface.
     */
    static final MemorySegment IID_OUTPUT5 = Com.guid("80a07424-ab52-42eb-833c-0c42fd282d98");

    /**
     * The identifier of the {@code ID3D11Texture2D} interface.
     */
    static final MemorySegment IID_TEXTURE2D = Com.guid("6f15aaf2-d208-4e89-9ab4-489535d34f9c");

    /**
     * The {@code IDXGIFactory1::EnumAdapters1} slot.
     */
    static final int FACTORY_ENUM_ADAPTERS1 = 12;

    /**
     * The {@code IDXGIAdapter::EnumOutputs} slot.
     */
    static final int ADAPTER_ENUM_OUTPUTS = 7;

    /**
     * The {@code IDXGIOutput::GetDesc} slot.
     */
    static final int OUTPUT_GET_DESC = 7;

    /**
     * The {@code IDXGIOutput1::DuplicateOutput} slot.
     */
    static final int OUTPUT1_DUPLICATE_OUTPUT = 22;

    /**
     * The {@code IDXGIOutput5::DuplicateOutput1} slot.
     */
    static final int OUTPUT5_DUPLICATE_OUTPUT1 = 26;

    /**
     * The {@code IDXGIOutputDuplication::GetDesc} slot.
     */
    static final int DUPLICATION_GET_DESC = 7;

    /**
     * The {@code IDXGIOutputDuplication::AcquireNextFrame} slot.
     */
    static final int DUPLICATION_ACQUIRE_NEXT_FRAME = 8;

    /**
     * The {@code IDXGIOutputDuplication::GetFramePointerShape} slot.
     */
    static final int DUPLICATION_GET_FRAME_POINTER_SHAPE = 11;

    /**
     * The {@code IDXGIOutputDuplication::ReleaseFrame} slot.
     */
    static final int DUPLICATION_RELEASE_FRAME = 14;

    /**
     * The {@code ID3D11Device::CreateTexture2D} slot.
     */
    static final int DEVICE_CREATE_TEXTURE2D = 5;

    /**
     * The {@code ID3D11Texture2D::GetDesc} slot.
     */
    static final int TEXTURE2D_GET_DESC = 10;

    /**
     * The {@code ID3D11DeviceContext::Map} slot.
     */
    static final int CONTEXT_MAP = 14;

    /**
     * The {@code ID3D11DeviceContext::Unmap} slot.
     */
    static final int CONTEXT_UNMAP = 15;

    /**
     * The {@code ID3D11DeviceContext::CopyResource} slot.
     */
    static final int CONTEXT_COPY_RESOURCE = 47;

    /**
     * The size of {@code DXGI_OUTPUT_DESC}.
     */
    static final long OUTPUT_DESC_SIZE = 96;

    /**
     * The offset of {@code DXGI_OUTPUT_DESC.DesktopCoordinates} (a {@code RECT} of four
     * {@code LONG}: left, top, right, bottom).
     */
    static final long OUTPUT_DESC_COORDINATES = 64;

    /**
     * The offset of {@code DXGI_OUTPUT_DESC.AttachedToDesktop}.
     */
    static final long OUTPUT_DESC_ATTACHED = 80;

    /**
     * The offset of {@code DXGI_OUTPUT_DESC.Rotation}.
     */
    static final long OUTPUT_DESC_ROTATION = 84;

    /**
     * The size of {@code DXGI_OUTDUPL_DESC}.
     */
    static final long DUPLICATION_DESC_SIZE = 36;

    /**
     * The offset of {@code DXGI_OUTDUPL_DESC.Rotation}.
     */
    static final long DUPLICATION_DESC_ROTATION = 28;

    /**
     * The size of {@code DXGI_OUTDUPL_FRAME_INFO}.
     */
    static final long FRAME_INFO_SIZE = 48;

    /**
     * The offset of {@code DXGI_OUTDUPL_FRAME_INFO.LastPresentTime}.
     */
    static final long FRAME_INFO_LAST_PRESENT = 0;

    /**
     * The offset of {@code DXGI_OUTDUPL_FRAME_INFO.LastMouseUpdateTime}.
     */
    static final long FRAME_INFO_LAST_MOUSE_UPDATE = 8;

    /**
     * The offset of {@code DXGI_OUTDUPL_FRAME_INFO.PointerPosition.Position.x}.
     */
    static final long FRAME_INFO_POINTER_X = 28;

    /**
     * The offset of {@code DXGI_OUTDUPL_FRAME_INFO.PointerPosition.Position.y}.
     */
    static final long FRAME_INFO_POINTER_Y = 32;

    /**
     * The offset of {@code DXGI_OUTDUPL_FRAME_INFO.PointerPosition.Visible}.
     */
    static final long FRAME_INFO_POINTER_VISIBLE = 36;

    /**
     * The offset of {@code DXGI_OUTDUPL_FRAME_INFO.PointerShapeBufferSize}.
     */
    static final long FRAME_INFO_POINTER_SHAPE_SIZE = 44;

    /**
     * The size of {@code DXGI_OUTDUPL_POINTER_SHAPE_INFO}: type, width, height, pitch and the hot
     * spot.
     */
    static final long POINTER_SHAPE_INFO_SIZE = 24;

    /**
     * The size of {@code D3D11_TEXTURE2D_DESC}.
     */
    static final long TEXTURE2D_DESC_SIZE = 44;

    /**
     * The size of {@code D3D11_MAPPED_SUBRESOURCE}.
     */
    static final long MAPPED_SUBRESOURCE_SIZE = 16;

    /**
     * The offset of {@code D3D11_MAPPED_SUBRESOURCE.RowPitch}.
     */
    static final long MAPPED_SUBRESOURCE_ROW_PITCH = 8;

    /**
     * The bound functions, or {@code null} when DXGI or Direct3D 11 is missing.
     */
    private static final Functions FUNCTIONS = Functions.load();

    /**
     * Prevents instantiation.
     */
    private Dxgi() {
        throw new AssertionError();
    }

    /**
     * Binds the exported functions.
     *
     * @param createFactory {@code HRESULT CreateDXGIFactory1(REFIID, void**)}
     * @param createDevice {@code HRESULT D3D11CreateDevice(IDXGIAdapter*, D3D_DRIVER_TYPE, HMODULE, UINT,
     * const D3D_FEATURE_LEVEL*, UINT, UINT, ID3D11Device**, D3D_FEATURE_LEVEL*,
     * ID3D11DeviceContext**)}
     */
    private record Functions(MethodHandle createFactory, MethodHandle createDevice) {
        /**
         * Loads the libraries and binds the functions.
         *
         * @return the bindings, or {@code null} when a library or a function is missing
         */
        static Functions load() {
            var dxgi = Native.library("dxgi").orElse(null);
            var d3d11 = Native.library("d3d11").orElse(null);
            if (dxgi == null || d3d11 == null) {
                return null;
            }
            try {
                return new Functions(
                        Native.function(dxgi, "CreateDXGIFactory1", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(d3d11, "D3D11CreateDevice", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT,
                                ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS)));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }
    }

    /**
     * Returns whether DXGI and Direct3D 11 are available.
     *
     * @return whether every function was bound
     */
    static boolean available() {
        return FUNCTIONS != null;
    }

    /**
     * Creates a DXGI 1.1 factory.
     *
     * @param result a pointer-sized slot receiving the factory
     * @return the {@code IDXGIFactory1} pointer
     * @throws MediaException.Io if DXGI is unavailable or the factory cannot be created
     */
    static MemorySegment createFactory(MemorySegment result) {
        if (FUNCTIONS == null) {
            throw Com.failure("DXGI is not available", 0x80004005);
        }
        int hr;
        try {
            hr = (int) FUNCTIONS.createFactory().invokeExact(IID_FACTORY1, result);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        Com.check(hr, "CreateDXGIFactory1");
        return result.get(ADDRESS, 0);
    }

    /**
     * Creates a Direct3D 11 device on the default hardware adapter, or on the WARP software rasterizer
     * when no hardware device can be created, with the BGRA support Windows Graphics Capture needs.
     *
     * @param device a pointer-sized slot receiving the {@code ID3D11Device}
     * @param featureLevel an integer slot receiving the feature level
     * @param context a pointer-sized slot receiving the {@code ID3D11DeviceContext}
     * @return the {@code HRESULT} of the last attempt
     */
    static int createDefaultDevice(MemorySegment device, MemorySegment featureLevel, MemorySegment context) {
        if (FUNCTIONS == null) {
            throw Com.failure("Direct3D 11 is not available", 0x80004005);
        }
        var hr = 0;
        for (var driver : new int[]{DRIVER_TYPE_HARDWARE, DRIVER_TYPE_WARP}) {
            try {
                hr = (int) FUNCTIONS.createDevice().invokeExact(MemorySegment.NULL, driver, MemorySegment.NULL,
                        CREATE_DEVICE_BGRA_SUPPORT, MemorySegment.NULL, 0, SDK_VERSION, device, featureLevel, context);
            } catch (Throwable throwable) {
                throw Native.rethrow(throwable);
            }
            if (hr >= 0) {
                return hr;
            }
        }
        return hr;
    }

    /**
     * Creates a Direct3D 11 device on an adapter.
     *
     * @param device a pointer-sized slot receiving the {@code ID3D11Device}
     * @param featureLevel a 32-bit slot receiving the feature level
     * @param context a pointer-sized slot receiving the immediate {@code ID3D11DeviceContext}
     * @return the {@code HRESULT}
     */
    static int createDevice(MemorySegment adapter, MemorySegment device, MemorySegment featureLevel, MemorySegment context) {
        if (FUNCTIONS == null) {
            throw Com.failure("Direct3D 11 is not available", 0x80004005);
        }
        try {
            return (int) FUNCTIONS.createDevice().invokeExact(adapter, DRIVER_TYPE_UNKNOWN, MemorySegment.NULL, 0,
                    MemorySegment.NULL, 0, SDK_VERSION, device, featureLevel, context);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }
}
