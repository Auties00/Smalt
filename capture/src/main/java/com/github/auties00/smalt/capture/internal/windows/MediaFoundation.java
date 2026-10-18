package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Binds the Media Foundation functions, attribute keys, interface identifiers and vtable slots
 * the camera backend uses.
 *
 * <p>The functions live in {@code mfplat.dll}, {@code mf.dll} and {@code mfreadwrite.dll}, which
 * are absent from the N and KN editions of Windows without the Media Feature Pack; there
 * {@link #available()} is {@code false} and the camera backend lists no camera. The vtable slots
 * are the method indices of the interfaces as declared in the Windows SDK headers
 * {@code mfobjects.h}, {@code mfidl.h} and {@code mfreadwrite.h}, counting the three
 * {@code IUnknown} methods.
 */
final class MediaFoundation {
    /**
     * The {@code MF_VERSION} passed to {@code MFStartup}: {@code MF_SDK_VERSION << 16 | MF_API_VERSION}.
     */
    private static final int MF_VERSION = 0x0002 << 16 | 0x0070;

    /**
     * The {@code MFSTARTUP_LITE} flag: no socket library initialization.
     */
    private static final int MFSTARTUP_LITE = 0x1;

    /**
     * The {@code MF_E_NO_MORE_TYPES} result of {@code GetNativeMediaType} past the last type.
     */
    static final int MF_E_NO_MORE_TYPES = 0xC00D36B9;

    /**
     * The {@code MF_SOURCE_READER_FIRST_VIDEO_STREAM} stream selector.
     */
    static final int FIRST_VIDEO_STREAM = 0xFFFFFFFC;

    /**
     * The {@code MF_SOURCE_READER_ALL_STREAMS} stream selector.
     */
    static final int ALL_STREAMS = 0xFFFFFFFE;

    /**
     * The {@code MF_SOURCE_READERF_ERROR} flag of {@code ReadSample}.
     */
    static final int READER_ERROR = 0x1;

    /**
     * The {@code MF_SOURCE_READERF_ENDOFSTREAM} flag of {@code ReadSample}.
     */
    static final int READER_END_OF_STREAM = 0x2;

    /**
     * The {@code MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED} flag of {@code ReadSample}.
     */
    static final int READER_CURRENT_TYPE_CHANGED = 0x20;

    /**
     * The {@code MF2DBuffer_LockFlags_Read} flag of {@code IMF2DBuffer2::Lock2DSize}.
     */
    static final int LOCK_READ = 0x1;

    /**
     * The {@code MFVideoTransferMatrix_BT709} value of {@link #MT_YUV_MATRIX}.
     */
    static final int MATRIX_BT709 = 1;

    /**
     * The {@code MFNominalRange_0_255} value of {@link #MT_VIDEO_NOMINAL_RANGE}.
     */
    static final int RANGE_FULL = 1;

    /**
     * The {@code MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE} attribute key.
     */
    static final MemorySegment DEVSOURCE_SOURCE_TYPE = Com.guid("c60ac5fe-252a-478f-a0ef-bc8fa5f7cad3");

    /**
     * The {@code MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID} value: video capture devices.
     */
    static final MemorySegment DEVSOURCE_VIDCAP = Com.guid("8ac3587a-4ae7-42d8-99e0-0a6013eef90f");

    /**
     * The {@code MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME} attribute key.
     */
    static final MemorySegment DEVSOURCE_FRIENDLY_NAME = Com.guid("60d0e559-52f8-4fa2-bbce-acdb34a8ec01");

    /**
     * The {@code MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK} attribute key.
     */
    static final MemorySegment DEVSOURCE_SYMBOLIC_LINK = Com.guid("58f0aad8-22bf-4f8a-bb3d-d2c4978c6e2f");

    /**
     * The {@code MF_MT_MAJOR_TYPE} attribute key.
     */
    static final MemorySegment MT_MAJOR_TYPE = Com.guid("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");

    /**
     * The {@code MF_MT_SUBTYPE} attribute key.
     */
    static final MemorySegment MT_SUBTYPE = Com.guid("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");

    /**
     * The {@code MF_MT_FRAME_SIZE} attribute key: width in the high and height in the low 32 bits.
     */
    static final MemorySegment MT_FRAME_SIZE = Com.guid("1652c33d-d6b2-4012-b834-72030849a37d");

    /**
     * The {@code MF_MT_FRAME_RATE} attribute key: numerator in the high and denominator in the low
     * 32 bits.
     */
    static final MemorySegment MT_FRAME_RATE = Com.guid("c459a2e8-3d2c-4e44-b132-fee5156c7bb0");

    /**
     * The {@code MF_MT_DEFAULT_STRIDE} attribute key: the signed row stride of a contiguous
     * buffer, negative for a bottom-up image.
     */
    static final MemorySegment MT_DEFAULT_STRIDE = Com.guid("644b4e48-1e02-4516-b0eb-c01ca9d49ac6");

    /**
     * The {@code MF_MT_YUV_MATRIX} attribute key.
     */
    static final MemorySegment MT_YUV_MATRIX = Com.guid("3e23d450-2c75-4d25-a00e-b91670d12327");

    /**
     * The {@code MF_MT_VIDEO_NOMINAL_RANGE} attribute key.
     */
    static final MemorySegment MT_VIDEO_NOMINAL_RANGE = Com.guid("c21b8ee5-b956-4071-8daf-325edf5cab11");

    /**
     * The {@code MFMediaType_Video} major type.
     */
    static final MemorySegment MEDIA_TYPE_VIDEO = Com.guid("73646976-0000-0010-8000-00aa00389b71");

    /**
     * The identifier of the {@code IMFMediaSource} interface.
     */
    static final MemorySegment IID_MEDIA_SOURCE = Com.guid("279a808d-aec7-40c8-9c6b-a6b492c78a66");

    /**
     * The identifier of the {@code IMF2DBuffer} interface.
     */
    static final MemorySegment IID_2D_BUFFER = Com.guid("7dc9d5f9-9ed9-44ec-9bbf-0600bb589fbb");

    /**
     * The identifier of the {@code IMF2DBuffer2} interface.
     */
    static final MemorySegment IID_2D_BUFFER2 = Com.guid("33ae5ea6-4316-436f-8ddd-d73d22f829ec");

    /**
     * The {@code IMFAttributes::GetUINT32} slot.
     */
    static final int ATTRIBUTES_GET_UINT32 = 7;

    /**
     * The {@code IMFAttributes::GetUINT64} slot.
     */
    static final int ATTRIBUTES_GET_UINT64 = 8;

    /**
     * The {@code IMFAttributes::GetGUID} slot.
     */
    static final int ATTRIBUTES_GET_GUID = 10;

    /**
     * The {@code IMFAttributes::GetAllocatedString} slot.
     */
    static final int ATTRIBUTES_GET_ALLOCATED_STRING = 13;

    /**
     * The {@code IMFAttributes::SetGUID} slot.
     */
    static final int ATTRIBUTES_SET_GUID = 24;

    /**
     * The {@code IMFAttributes::SetString} slot.
     */
    static final int ATTRIBUTES_SET_STRING = 25;

    /**
     * The {@code IMFActivate::ActivateObject} slot.
     */
    static final int ACTIVATE_ACTIVATE_OBJECT = 33;

    /**
     * The {@code IMFActivate::ShutdownObject} slot.
     */
    static final int ACTIVATE_SHUTDOWN_OBJECT = 34;

    /**
     * The {@code IMFMediaSource::Shutdown} slot.
     */
    static final int SOURCE_SHUTDOWN = 12;

    /**
     * The {@code IMFSourceReader::SetStreamSelection} slot.
     */
    static final int READER_SET_STREAM_SELECTION = 4;

    /**
     * The {@code IMFSourceReader::GetNativeMediaType} slot.
     */
    static final int READER_GET_NATIVE_MEDIA_TYPE = 5;

    /**
     * The {@code IMFSourceReader::GetCurrentMediaType} slot.
     */
    static final int READER_GET_CURRENT_MEDIA_TYPE = 6;

    /**
     * The {@code IMFSourceReader::SetCurrentMediaType} slot.
     */
    static final int READER_SET_CURRENT_MEDIA_TYPE = 7;

    /**
     * The {@code IMFSourceReader::ReadSample} slot.
     */
    static final int READER_READ_SAMPLE = 9;

    /**
     * The {@code IMFSourceReader::Flush} slot.
     */
    static final int READER_FLUSH = 10;

    /**
     * The {@code IMFSample::GetBufferCount} slot.
     */
    static final int SAMPLE_GET_BUFFER_COUNT = 39;

    /**
     * The {@code IMFSample::GetBufferByIndex} slot.
     */
    static final int SAMPLE_GET_BUFFER_BY_INDEX = 40;

    /**
     * The {@code IMFSample::ConvertToContiguousBuffer} slot.
     */
    static final int SAMPLE_CONVERT_TO_CONTIGUOUS_BUFFER = 41;

    /**
     * The {@code IMFMediaBuffer::Lock} slot.
     */
    static final int BUFFER_LOCK = 3;

    /**
     * The {@code IMFMediaBuffer::Unlock} slot.
     */
    static final int BUFFER_UNLOCK = 4;

    /**
     * The {@code IMF2DBuffer::Lock2D} slot.
     */
    static final int BUFFER_2D_LOCK = 3;

    /**
     * The {@code IMF2DBuffer::Unlock2D} slot.
     */
    static final int BUFFER_2D_UNLOCK = 4;

    /**
     * The {@code IMF2DBuffer2::Lock2DSize} slot.
     */
    static final int BUFFER_2D_LOCK_SIZE = 10;

    /**
     * The bound functions, or {@code null} when Media Foundation is not installed.
     */
    private static final Functions FUNCTIONS = Functions.load();

    /**
     * Prevents instantiation.
     */
    private MediaFoundation() {
        throw new AssertionError();
    }

    /**
     * Binds the exported functions.
     *
     * @param startup {@code HRESULT MFStartup(ULONG, DWORD)}
     * @param shutdown {@code HRESULT MFShutdown()}
     * @param createAttributes {@code HRESULT MFCreateAttributes(IMFAttributes**, UINT32)}
     * @param enumDeviceSources {@code HRESULT MFEnumDeviceSources(IMFAttributes*, IMFActivate***, UINT32*)}
     * @param createDeviceSource {@code HRESULT MFCreateDeviceSource(IMFAttributes*, IMFMediaSource**)}
     * @param createSourceReader {@code HRESULT MFCreateSourceReaderFromMediaSource(IMFMediaSource*,
     * IMFAttributes*, IMFSourceReader**)}
     */
    private record Functions(MethodHandle startup, MethodHandle shutdown, MethodHandle createAttributes,
                             MethodHandle enumDeviceSources, MethodHandle createDeviceSource,
                             MethodHandle createSourceReader) {
        /**
         * Loads the libraries and binds the functions.
         *
         * @return the bindings, or {@code null} when a library or a function is missing
         */
        static Functions load() {
            if (!Com.available()) {
                return null;
            }
            var platform = Native.library("mfplat").orElse(null);
            var core = Native.library("mf").orElse(null);
            var readWrite = Native.library("mfreadwrite").orElse(null);
            if (platform == null || core == null || readWrite == null) {
                return null;
            }
            try {
                return new Functions(
                        Native.function(platform, "MFStartup", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)),
                        Native.function(platform, "MFShutdown", FunctionDescriptor.of(JAVA_INT)),
                        Native.function(platform, "MFCreateAttributes", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(core, "MFEnumDeviceSources", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(core, "MFCreateDeviceSource", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(readWrite, "MFCreateSourceReaderFromMediaSource", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }
    }

    /**
     * Returns whether Media Foundation is installed.
     *
     * @return whether every function was bound
     */
    static boolean available() {
        return FUNCTIONS != null;
    }

    /**
     * Returns the bindings, failing when Media Foundation is not installed.
     *
     * @return the bindings
     * @throws MediaException.Unsupported if Media Foundation is not installed
     */
    private static Functions functions() {
        if (FUNCTIONS == null) {
            throw new MediaException.Unsupported("media foundation", "Media Foundation is not installed on this system");
        }
        return FUNCTIONS;
    }

    /**
     * Starts Media Foundation for the caller; every successful call is balanced by
     * {@link #shutdown()}.
     *
     * @throws MediaException.Io if Media Foundation cannot start
     * @throws MediaException.Unsupported if Media Foundation is not installed
     */
    static void startup() {
        int result;
        try {
            result = (int) functions().startup().invokeExact(MF_VERSION, MFSTARTUP_LITE);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        Com.check(result, "MFStartup");
    }

    /**
     * Releases the caller's reference to Media Foundation.
     */
    static void shutdown() {
        try {
            var ignored = (int) functions().shutdown().invokeExact();
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Creates an empty attribute store.
     *
     * @param capacity the initial number of entries
     * @param result a pointer-sized slot receiving the store
     * @return the {@code IMFAttributes} pointer
     * @throws MediaException.Io if the store cannot be created
     */
    static MemorySegment createAttributes(int capacity, MemorySegment result) {
        int hr;
        try {
            hr = (int) functions().createAttributes().invokeExact(result, capacity);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        Com.check(hr, "MFCreateAttributes");
        return result.get(ADDRESS, 0);
    }

    /**
     * Enumerates the device sources matching an attribute store.
     *
     * @param attributes the filter
     * @param array a pointer-sized slot receiving the {@code CoTaskMemAlloc} array of
     * {@code IMFActivate} pointers
     * @param count a 32-bit slot receiving the number of devices
     * @throws MediaException.Io if the enumeration fails
     */
    static void enumDeviceSources(MemorySegment attributes, MemorySegment array, MemorySegment count) {
        int hr;
        try {
            hr = (int) functions().enumDeviceSources().invokeExact(attributes, array, count);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        Com.check(hr, "MFEnumDeviceSources");
    }

    /**
     * Creates the media source of a device.
     *
     * @param attributes the device's source type and symbolic link
     * @param result a pointer-sized slot receiving the {@code IMFMediaSource}
     * @return the {@code HRESULT}
     */
    static int createDeviceSource(MemorySegment attributes, MemorySegment result) {
        try {
            return (int) functions().createDeviceSource().invokeExact(attributes, result);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Creates a source reader over a media source.
     *
     * @param source the media source
     * @param attributes the reader's configuration, or {@link MemorySegment#NULL}
     * @param result a pointer-sized slot receiving the {@code IMFSourceReader}
     * @return the reader
     * @throws MediaException.Io if the reader cannot be created
     */
    static MemorySegment createSourceReader(MemorySegment source, MemorySegment attributes, MemorySegment result) {
        int hr;
        try {
            hr = (int) functions().createSourceReader().invokeExact(source, attributes, result);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        Com.check(hr, "MFCreateSourceReaderFromMediaSource");
        return result.get(ADDRESS, 0);
    }
}
