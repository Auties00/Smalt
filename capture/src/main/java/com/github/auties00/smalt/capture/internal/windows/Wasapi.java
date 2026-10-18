package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioDeviceFormat;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import com.github.auties00.smalt.capture.internal.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

/**
 * Calls the Windows Audio Session API (WASAPI) and the MMDevice API through the Foreign Function
 * and Memory API.
 *
 * <p>The interfaces are COM interfaces called through {@link Com}: {@code IMMDeviceEnumerator},
 * {@code IMMDeviceCollection}, {@code IMMDevice} and {@code IPropertyStore} to list endpoints,
 * {@code IAudioClient} (with its versions 2 and 3), {@code IAudioCaptureClient},
 * {@code IAudioRenderClient} and {@code IAudioClock} to stream. The vtable slots and constants are
 * those of the Windows SDK headers {@code mmdeviceapi.h}, {@code audioclient.h},
 * {@code audiosessiontypes.h}, {@code propsys.h}, {@code functiondiscoverykeys_devpkey.h},
 * {@code mmreg.h} and {@code ksmedia.h}. This class also binds the few {@code kernel32.dll},
 * {@code ole32.dll} and {@code avrt.dll} functions the streams need: events, the performance
 * counter, {@code CoCreateInstance} and the multimedia class scheduler.
 */
final class Wasapi {
    /**
     * {@code CLSID_MMDeviceEnumerator}.
     */
    static final MemorySegment CLSID_MM_DEVICE_ENUMERATOR = Com.guid("BCDE0395-E52F-467C-8E3D-C4579291692E");

    /**
     * {@code IID_IMMDeviceEnumerator}.
     */
    static final MemorySegment IID_IMM_DEVICE_ENUMERATOR = Com.guid("A95664D2-9614-4F35-A746-DE8DB63617E6");

    /**
     * {@code IID_IAudioClient}.
     */
    static final MemorySegment IID_AUDIO_CLIENT = Com.guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");

    /**
     * {@code IID_IAudioClient3}.
     */
    static final MemorySegment IID_AUDIO_CLIENT3 = Com.guid("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42");

    /**
     * {@code IID_IAudioCaptureClient}.
     */
    static final MemorySegment IID_AUDIO_CAPTURE_CLIENT = Com.guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");

    /**
     * {@code IID_IAudioRenderClient}.
     */
    static final MemorySegment IID_AUDIO_RENDER_CLIENT = Com.guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");

    /**
     * {@code IID_IAudioClock}.
     */
    static final MemorySegment IID_AUDIO_CLOCK = Com.guid("CD63314F-3FBA-4A1B-812C-EF96358728E7");

    /**
     * {@code IID_IUnknown}.
     */
    static final MemorySegment IID_UNKNOWN = Com.guid("00000000-0000-0000-C000-000000000046");

    /**
     * {@code IID_IMMNotificationClient}.
     */
    static final MemorySegment IID_MM_NOTIFICATION_CLIENT = Com.guid("7991EEC9-7E89-4D85-8390-6C703CEC60C0");

    /**
     * {@code KSDATAFORMAT_SUBTYPE_PCM}.
     */
    static final MemorySegment SUBTYPE_PCM = Com.guid("00000001-0000-0010-8000-00AA00389B71");

    /**
     * {@code KSDATAFORMAT_SUBTYPE_IEEE_FLOAT}.
     */
    static final MemorySegment SUBTYPE_IEEE_FLOAT = Com.guid("00000003-0000-0010-8000-00AA00389B71");

    /**
     * {@code PKEY_Device_FriendlyName}: the format id followed by the property id 14.
     */
    static final MemorySegment PKEY_DEVICE_FRIENDLY_NAME = propertyKey("A45C254E-DF1C-4EFD-8020-67D146A850E0", 14);

    /**
     * {@code IMMDeviceEnumerator::EnumAudioEndpoints}.
     */
    static final int ENUM_AUDIO_ENDPOINTS = 3;

    /**
     * {@code IMMDeviceEnumerator::GetDefaultAudioEndpoint}.
     */
    static final int GET_DEFAULT_AUDIO_ENDPOINT = 4;

    /**
     * {@code IMMDeviceEnumerator::GetDevice}.
     */
    static final int GET_DEVICE = 5;

    /**
     * {@code IMMDeviceEnumerator::RegisterEndpointNotificationCallback}.
     */
    static final int REGISTER_ENDPOINT_NOTIFICATION = 6;

    /**
     * {@code IMMDeviceEnumerator::UnregisterEndpointNotificationCallback}.
     */
    static final int UNREGISTER_ENDPOINT_NOTIFICATION = 7;

    /**
     * {@code IMMDeviceCollection::GetCount}.
     */
    static final int COLLECTION_GET_COUNT = 3;

    /**
     * {@code IMMDeviceCollection::Item}.
     */
    static final int COLLECTION_ITEM = 4;

    /**
     * {@code IMMDevice::Activate}.
     */
    static final int DEVICE_ACTIVATE = 3;

    /**
     * {@code IMMDevice::OpenPropertyStore}.
     */
    static final int DEVICE_OPEN_PROPERTY_STORE = 4;

    /**
     * {@code IMMDevice::GetId}.
     */
    static final int DEVICE_GET_ID = 5;

    /**
     * {@code IMMDevice::GetState}.
     */
    static final int DEVICE_GET_STATE = 6;

    /**
     * {@code IPropertyStore::GetValue}.
     */
    static final int STORE_GET_VALUE = 5;

    /**
     * {@code IAudioClient::Initialize}.
     */
    static final int CLIENT_INITIALIZE = 3;

    /**
     * {@code IAudioClient::GetBufferSize}.
     */
    static final int CLIENT_GET_BUFFER_SIZE = 4;

    /**
     * {@code IAudioClient::GetStreamLatency}.
     */
    static final int CLIENT_GET_STREAM_LATENCY = 5;

    /**
     * {@code IAudioClient::GetCurrentPadding}.
     */
    static final int CLIENT_GET_CURRENT_PADDING = 6;

    /**
     * {@code IAudioClient::IsFormatSupported}.
     */
    static final int CLIENT_IS_FORMAT_SUPPORTED = 7;

    /**
     * {@code IAudioClient::GetMixFormat}.
     */
    static final int CLIENT_GET_MIX_FORMAT = 8;

    /**
     * {@code IAudioClient::GetDevicePeriod}.
     */
    static final int CLIENT_GET_DEVICE_PERIOD = 9;

    /**
     * {@code IAudioClient::Start}.
     */
    static final int CLIENT_START = 10;

    /**
     * {@code IAudioClient::Stop}.
     */
    static final int CLIENT_STOP = 11;

    /**
     * {@code IAudioClient::SetEventHandle}.
     */
    static final int CLIENT_SET_EVENT_HANDLE = 13;

    /**
     * {@code IAudioClient::GetService}.
     */
    static final int CLIENT_GET_SERVICE = 14;

    /**
     * {@code IAudioClient3::GetSharedModeEnginePeriod}.
     */
    static final int CLIENT3_GET_SHARED_MODE_ENGINE_PERIOD = 18;

    /**
     * {@code IAudioClient3::InitializeSharedAudioStream}.
     */
    static final int CLIENT3_INITIALIZE_SHARED_AUDIO_STREAM = 20;

    /**
     * {@code IAudioCaptureClient::GetBuffer}.
     */
    static final int CAPTURE_GET_BUFFER = 3;

    /**
     * {@code IAudioCaptureClient::ReleaseBuffer}.
     */
    static final int CAPTURE_RELEASE_BUFFER = 4;

    /**
     * {@code IAudioCaptureClient::GetNextPacketSize}.
     */
    static final int CAPTURE_GET_NEXT_PACKET_SIZE = 5;

    /**
     * {@code IAudioRenderClient::GetBuffer}.
     */
    static final int RENDER_GET_BUFFER = 3;

    /**
     * {@code IAudioRenderClient::ReleaseBuffer}.
     */
    static final int RENDER_RELEASE_BUFFER = 4;

    /**
     * {@code IAudioClock::GetFrequency}.
     */
    static final int CLOCK_GET_FREQUENCY = 3;

    /**
     * {@code IAudioClock::GetPosition}.
     */
    static final int CLOCK_GET_POSITION = 4;

    /**
     * {@code eRender}: the data flow of output endpoints.
     */
    static final int E_RENDER = 0;

    /**
     * {@code eCapture}: the data flow of input endpoints.
     */
    static final int E_CAPTURE = 1;

    /**
     * {@code eConsole}: the role of the system's default device.
     */
    static final int E_CONSOLE = 0;

    /**
     * {@code DEVICE_STATE_ACTIVE}.
     */
    static final int DEVICE_STATE_ACTIVE = 0x1;

    /**
     * {@code CLSCTX_ALL}.
     */
    static final int CLSCTX_ALL = 0x17;

    /**
     * {@code STGM_READ}.
     */
    static final int STGM_READ = 0;

    /**
     * {@code VT_LPWSTR}: a {@code PROPVARIANT} holding a wide string.
     */
    static final short VT_LPWSTR = 31;

    /**
     * The size of a {@code PROPVARIANT} on 64-bit Windows.
     */
    static final long PROPVARIANT_SIZE = 24;

    /**
     * {@code AUDCLNT_SHAREMODE_SHARED}.
     */
    static final int SHAREMODE_SHARED = 0;

    /**
     * {@code AUDCLNT_SHAREMODE_EXCLUSIVE}.
     */
    static final int SHAREMODE_EXCLUSIVE = 1;

    /**
     * {@code AUDCLNT_STREAMFLAGS_LOOPBACK}.
     */
    static final int STREAMFLAGS_LOOPBACK = 0x00020000;

    /**
     * {@code AUDCLNT_STREAMFLAGS_EVENTCALLBACK}.
     */
    static final int STREAMFLAGS_EVENTCALLBACK = 0x00040000;

    /**
     * {@code AUDCLNT_STREAMFLAGS_NOPERSIST}.
     */
    static final int STREAMFLAGS_NOPERSIST = 0x00080000;

    /**
     * {@code AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY}.
     */
    static final int STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000;

    /**
     * {@code AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM}.
     */
    static final int STREAMFLAGS_AUTOCONVERTPCM = 0x80000000;

    /**
     * {@code AUDCLNT_BUFFERFLAGS_SILENT}.
     */
    static final int BUFFERFLAGS_SILENT = 0x2;

    /**
     * {@code AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR}.
     */
    static final int BUFFERFLAGS_TIMESTAMP_ERROR = 0x4;

    /**
     * {@code AUDCLNT_S_BUFFER_EMPTY}: no capture packet is ready.
     */
    static final int S_BUFFER_EMPTY = 0x08890001;

    /**
     * {@code AUDCLNT_E_DEVICE_INVALIDATED}: the endpoint was removed or disabled.
     */
    static final int E_DEVICE_INVALIDATED = 0x88890004;

    /**
     * {@code AUDCLNT_E_UNSUPPORTED_FORMAT}.
     */
    static final int E_UNSUPPORTED_FORMAT = 0x88890008;

    /**
     * {@code AUDCLNT_E_DEVICE_IN_USE}: another stream holds the endpoint exclusively.
     */
    static final int E_DEVICE_IN_USE = 0x8889000A;

    /**
     * {@code AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED}.
     */
    static final int E_EXCLUSIVE_MODE_NOT_ALLOWED = 0x8889000E;

    /**
     * {@code AUDCLNT_E_SERVICE_NOT_RUNNING}.
     */
    static final int E_SERVICE_NOT_RUNNING = 0x88890010;

    /**
     * {@code AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED}.
     */
    static final int E_BUFFER_SIZE_NOT_ALIGNED = 0x88890019;

    /**
     * {@code AUDCLNT_E_RESOURCES_INVALIDATED}.
     */
    static final int E_RESOURCES_INVALIDATED = 0x88890026;

    /**
     * {@code E_NOTFOUND}, {@code HRESULT_FROM_WIN32(ERROR_NOT_FOUND)}: no endpoint has the id.
     */
    static final int E_NOTFOUND = 0x80070490;

    /**
     * {@code WAIT_OBJECT_0}.
     */
    static final int WAIT_OBJECT_0 = 0;

    /**
     * {@code WAVE_FORMAT_PCM}.
     */
    static final int WAVE_FORMAT_PCM = 1;

    /**
     * {@code WAVE_FORMAT_IEEE_FLOAT}.
     */
    static final int WAVE_FORMAT_IEEE_FLOAT = 3;

    /**
     * {@code WAVE_FORMAT_EXTENSIBLE}.
     */
    static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

    /**
     * The size of {@code WAVEFORMATEXTENSIBLE}.
     */
    static final long WAVEFORMATEXTENSIBLE_SIZE = 40;

    /**
     * The layout of {@code PROPERTYKEY}, passed by value to
     * {@code IMMNotificationClient::OnPropertyValueChanged}: a GUID and a 32-bit property id.
     */
    static final MemoryLayout PROPERTYKEY = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(16, java.lang.foreign.ValueLayout.JAVA_BYTE).withName("fmtid"),
            JAVA_INT.withName("pid"));

    /**
     * Invokes {@code HRESULT f(this, UINT, UINT, INT64, INT64, void*, void*)}:
     * {@code IAudioClient::Initialize}.
     */
    private static final MethodHandle P_IILLPP = Native.LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));

    /**
     * Invokes {@code HRESULT f(this, void*, void*, void*, void*, void*)}.
     */
    private static final MethodHandle P_PPPPP = Native.LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /**
     * Invokes {@code HRESULT f(this, void*, UINT, void*, void*)}: {@code IMMDevice::Activate}.
     */
    private static final MethodHandle P_PIPP = Native.LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    /**
     * Invokes {@code HRESULT f(this, UINT, UINT, void*, void*)}:
     * {@code IAudioClient3::InitializeSharedAudioStream}.
     */
    private static final MethodHandle P_IIPP = Native.LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    /**
     * The functions of the system libraries, or {@code null} when one is missing.
     */
    private static final Functions FUNCTIONS = Functions.load();

    /**
     * Prevents instantiation.
     */
    private Wasapi() {
        throw new AssertionError();
    }

    /**
     * Binds the system functions the audio backend needs.
     *
     * @param coCreateInstance {@code HRESULT CoCreateInstance(REFCLSID, LPUNKNOWN, DWORD, REFIID, LPVOID*)}
     * @param propVariantClear {@code HRESULT PropVariantClear(PROPVARIANT*)}
     * @param createEvent {@code HANDLE CreateEventW(LPSECURITY_ATTRIBUTES, BOOL, BOOL, LPCWSTR)}
     * @param setEvent {@code BOOL SetEvent(HANDLE)}
     * @param closeHandle {@code BOOL CloseHandle(HANDLE)}
     * @param waitForSingleObject {@code DWORD WaitForSingleObject(HANDLE, DWORD)}
     * @param queryCounter {@code BOOL QueryPerformanceCounter(LARGE_INTEGER*)}
     * @param queryFrequency {@code BOOL QueryPerformanceFrequency(LARGE_INTEGER*)}
     * @param setThreadCharacteristics {@code HANDLE AvSetMmThreadCharacteristicsW(LPCWSTR, LPDWORD)},
     * or {@code null} without {@code avrt.dll}
     * @param revertThreadCharacteristics {@code BOOL AvRevertMmThreadCharacteristics(HANDLE)}, or
     * {@code null} without {@code avrt.dll}
     */
    private record Functions(MethodHandle coCreateInstance, MethodHandle propVariantClear, MethodHandle createEvent,
                             MethodHandle setEvent, MethodHandle closeHandle, MethodHandle waitForSingleObject,
                             MethodHandle queryCounter, MethodHandle queryFrequency,
                             MethodHandle setThreadCharacteristics, MethodHandle revertThreadCharacteristics) {
        /**
         * Loads the libraries and binds the functions.
         *
         * @return the bindings, or {@code null} when a required library or function is missing
         */
        static Functions load() {
            var ole32 = Native.library("ole32").orElse(null);
            var kernel32 = Native.library("kernel32").orElse(null);
            if (ole32 == null || kernel32 == null) {
                return null;
            }
            try {
                var avrt = Native.library("avrt").orElse(null);
                return new Functions(
                        Native.function(ole32, "CoCreateInstance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(ole32, "PropVariantClear", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(kernel32, "CreateEventW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)),
                        Native.function(kernel32, "SetEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(kernel32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(kernel32, "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(kernel32, "QueryPerformanceCounter", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(kernel32, "QueryPerformanceFrequency", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        avrt == null ? null : Native.optionalFunction(avrt, "AvSetMmThreadCharacteristicsW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)).orElse(null),
                        avrt == null ? null : Native.optionalFunction(avrt, "AvRevertMmThreadCharacteristics", FunctionDescriptor.of(JAVA_INT, ADDRESS)).orElse(null));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }

        }

    /**
     * Returns whether the audio functions are available.
     *
     * @return whether COM and the system libraries were bound
     */
    static boolean available() {
        return FUNCTIONS != null && Com.available();
    }

    /**
     * Allocates a {@code PROPERTYKEY} in process-lifetime memory.
     *
     * @param fmtid the format id in canonical GUID form
     * @param pid the property id
     * @return the 20-byte structure
     */
    private static MemorySegment propertyKey(String fmtid, int pid) {
        var key = Arena.global().allocate(20, 4);
        MemorySegment.copy(Com.guid(fmtid), 0, key, 0, 16);
        key.set(JAVA_INT_UNALIGNED, 16, pid);
        return key;
    }

    /**
     * Creates the device enumerator.
     *
     * @param arena the arena of the calling thread
     * @return the {@code IMMDeviceEnumerator}, which the caller releases
     * @throws MediaException.Io if the enumerator cannot be created
     */
    static MemorySegment createEnumerator(Arena arena) {
        var slot = arena.allocate(ADDRESS);
        int result;
        try {
            result = (int) FUNCTIONS.coCreateInstance().invokeExact(CLSID_MM_DEVICE_ENUMERATOR, MemorySegment.NULL, CLSCTX_ALL,
                    IID_IMM_DEVICE_ENUMERATOR, slot);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        check(result, "CoCreateInstance(MMDeviceEnumerator)");
        return slot.get(ADDRESS, 0);
    }

    /**
     * Invokes {@code IAudioClient::Initialize}.
     *
     * @param client the audio client
     * @param shareMode the share mode
     * @param flags the stream flags
     * @param buffer the buffer duration in 100-nanosecond units
     * @param periodicity the periodicity in 100-nanosecond units
     * @param format the {@code WAVEFORMATEX}
     * @return the {@code HRESULT}
     */
    static int initialize(MemorySegment client, int shareMode, int flags, long buffer, long periodicity, MemorySegment format) {
        try {
            return (int) P_IILLPP.invokeExact(Com.method(client, CLIENT_INITIALIZE), client, shareMode, flags, buffer, periodicity,
                    format, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes {@code IAudioClient3::InitializeSharedAudioStream}.
     *
     * @param client the {@code IAudioClient3}
     * @param flags the stream flags
     * @param periodFrames the period in frames
     * @param format the {@code WAVEFORMATEX}
     * @return the {@code HRESULT}
     */
    static int initializeSharedAudioStream(MemorySegment client, int flags, int periodFrames, MemorySegment format) {
        try {
            return (int) P_IIPP.invokeExact(Com.method(client, CLIENT3_INITIALIZE_SHARED_AUDIO_STREAM), client, flags, periodFrames,
                    format, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes a method taking five pointers: {@code IAudioCaptureClient::GetBuffer} or
     * {@code IAudioClient3::GetSharedModeEnginePeriod}.
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
    static int call5(MemorySegment self, int slot, MemorySegment a, MemorySegment b, MemorySegment c, MemorySegment d, MemorySegment e) {
        try {
            return (int) P_PPPPP.invokeExact(Com.method(self, slot), self, a, b, c, d, e);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Invokes {@code IMMDevice::Activate} for an interface, with no activation parameters.
     *
     * @param device the {@code IMMDevice}
     * @param iid the interface to activate
     * @param arena the arena of the calling thread
     * @return the interface, which the caller releases
     * @throws MediaException.Io if the activation fails
     */
    static MemorySegment activate(MemorySegment device, MemorySegment iid, Arena arena) {
        var slot = arena.allocate(ADDRESS);
        int result;
        try {
            result = (int) P_PIPP.invokeExact(Com.method(device, DEVICE_ACTIVATE), device, iid, CLSCTX_ALL, MemorySegment.NULL, slot);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        check(result, "IMMDevice::Activate");
        return slot.get(ADDRESS, 0);
    }

    /**
     * Creates an auto-reset event, initially not signaled.
     *
     * @return the event handle, which the caller closes
     * @throws MediaException.Io if the event cannot be created
     */
    static MemorySegment createEvent() {
        MemorySegment handle;
        try {
            handle = (MemorySegment) FUNCTIONS.createEvent().invokeExact(MemorySegment.NULL, 0, 0, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        if (handle.equals(MemorySegment.NULL)) {
            throw failure("CreateEventW failed", 0x80004005);
        }
        return handle;
    }

    /**
     * Signals an event.
     *
     * @param event the event handle
     */
    static void setEvent(MemorySegment event) {
        try {
            var ignored = (int) FUNCTIONS.setEvent().invokeExact(event);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Closes a kernel handle; {@link MemorySegment#NULL} is ignored.
     */
    static void closeHandle(MemorySegment handle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            var ignored = (int) FUNCTIONS.closeHandle().invokeExact(handle);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Waits for an event.
     *
     * @param event the event handle
     * @param timeoutMillis the longest wait in milliseconds
     * @return whether the event was signaled before the timeout
     */
    static boolean await(MemorySegment event, int timeoutMillis) {
        try {
            return (int) FUNCTIONS.waitForSingleObject().invokeExact(event, timeoutMillis) == WAIT_OBJECT_0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the performance counter in 100-nanosecond units, the unit of the WASAPI timestamps.
     *
     * @param scratch an 8-byte scratch segment
     * @return the counter in 100-nanosecond units
     */
    static long qpc100ns(MemorySegment scratch) {
        try {
            var ignored = (int) FUNCTIONS.queryCounter().invokeExact(scratch);
            var counter = scratch.get(JAVA_LONG_UNALIGNED, 0);
            ignored = (int) FUNCTIONS.queryFrequency().invokeExact(scratch);
            var frequency = scratch.get(JAVA_LONG_UNALIGNED, 0);
            if (frequency <= 0) {
                return 0;
            }
            return counter / frequency * 10_000_000L + counter % frequency * 10_000_000L / frequency;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Raises the calling thread to the multimedia class scheduler's {@code Pro Audio} task.
     *
     * @param arena the arena of the calling thread
     * @return the task handle to revert, or {@link MemorySegment#NULL} when unavailable
     */
    static MemorySegment enterProAudio(Arena arena) {
        if (FUNCTIONS.setThreadCharacteristics() == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) FUNCTIONS.setThreadCharacteristics().invokeExact(wide(arena, "Pro Audio"), arena.allocate(JAVA_INT));
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reverts {@link #enterProAudio(Arena)}.
     *
     * @param task the task handle; {@link MemorySegment#NULL} is ignored
     */
    static void leaveProAudio(MemorySegment task) {
        if (task.equals(MemorySegment.NULL) || FUNCTIONS.revertThreadCharacteristics() == null) {
            return;
        }
        try {
            var ignored = (int) FUNCTIONS.revertThreadCharacteristics().invokeExact(task);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Clears a {@code PROPVARIANT}, freeing what it owns.
     *
     * @param variant the {@code PROPVARIANT}
     */
    static void clear(MemorySegment variant) {
        try {
            var ignored = (int) FUNCTIONS.propVariantClear().invokeExact(variant);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Encodes a string as a null-terminated UTF-16 string.
     *
     * @param arena the arena to allocate in
     * @param text the string
     * @return the string's memory
     */
    static MemorySegment wide(Arena arena, String text) {
        var bytes = (text + "\0").getBytes(StandardCharsets.UTF_16LE);
        var segment = arena.allocate(bytes.length, 2);
        MemorySegment.copy(bytes, 0, segment, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
        return segment;
    }

    /**
     * Decodes a null-terminated UTF-16 string at an address.
     *
     * @param pointer the string; {@link MemorySegment#NULL} gives the empty string
     * @return the string
     */
    static String readWide(MemorySegment pointer) {
        if (pointer.equals(MemorySegment.NULL)) {
            return "";
        }
        var view = pointer.reinterpret(Long.MAX_VALUE);
        var length = 0L;
        while (length < 65_536 && view.get(JAVA_SHORT_UNALIGNED, length) != 0) {
            length += 2;
        }
        var bytes = new byte[(int) length];
        MemorySegment.copy(view, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
        return new String(bytes, StandardCharsets.UTF_16LE);
    }

    /**
     * Builds a {@code WAVEFORMATEXTENSIBLE}.
     *
     * @param arena the arena to allocate in
     * @param rate the sampling rate
     * @param channels the channel count
     * @param format the sample encoding
     * @param validBits the significant bits of each sample, at most the container's
     * @return the 40-byte structure
     */
    static MemorySegment waveFormat(Arena arena, int rate, int channels, AudioSampleFormat format, int validBits) {
        var bytes = format.bytes();
        var segment = arena.allocate(WAVEFORMATEXTENSIBLE_SIZE, 8);
        segment.set(JAVA_SHORT, 0, (short) WAVE_FORMAT_EXTENSIBLE);
        segment.set(JAVA_SHORT, 2, (short) channels);
        segment.set(JAVA_INT, 4, rate);
        segment.set(JAVA_INT, 8, rate * channels * bytes);
        segment.set(JAVA_SHORT, 12, (short) (channels * bytes));
        segment.set(JAVA_SHORT, 14, (short) (bytes * 8));
        segment.set(JAVA_SHORT, 16, (short) 22);
        segment.set(JAVA_SHORT, 18, (short) validBits);
        segment.set(JAVA_INT, 20, channelMask(channels));
        MemorySegment.copy(format == AudioSampleFormat.FLOAT32 ? SUBTYPE_IEEE_FLOAT : SUBTYPE_PCM, 0, segment, 24, 16);
        return segment;
    }

    /**
     * Returns the speaker mask of the conventional layout of a channel count.
     *
     * @param channels the channel count
     * @return the {@code SPEAKER_*} mask, or {@code 0} (unspecified) for unusual counts
     */
    static int channelMask(int channels) {
        return switch (channels) {
            case 1 -> 0x4;
            case 2 -> 0x3;
            case 3 -> 0x7;
            case 4 -> 0x33;
            case 5 -> 0x37;
            case 6 -> 0x3F;
            case 7 -> 0x70F;
            case 8 -> 0x63F;
            default -> 0;
        };
    }

    /**
     * Parses a {@code WAVEFORMATEX} or {@code WAVEFORMATEXTENSIBLE}.
     *
     * @param pointer the structure
     * @return the format, or {@code null} when it is not linear PCM or float
     */
    static AudioDeviceFormat parseWaveFormat(MemorySegment pointer) {
        var header = pointer.reinterpret(18);
        var tag = header.get(JAVA_SHORT_UNALIGNED, 0) & 0xFFFF;
        var channels = header.get(JAVA_SHORT_UNALIGNED, 2) & 0xFFFF;
        var rate = header.get(JAVA_INT_UNALIGNED, 4);
        var bits = header.get(JAVA_SHORT_UNALIGNED, 14) & 0xFFFF;
        var isFloat = tag == WAVE_FORMAT_IEEE_FLOAT;
        var isPcm = tag == WAVE_FORMAT_PCM;
        if (tag == WAVE_FORMAT_EXTENSIBLE) {
            var extensible = pointer.reinterpret(WAVEFORMATEXTENSIBLE_SIZE);
            var subtype = extensible.asSlice(24, 16);
            isFloat = Com.sameGuid(subtype, SUBTYPE_IEEE_FLOAT);
            isPcm = Com.sameGuid(subtype, SUBTYPE_PCM);
        }
        if (channels <= 0 || rate <= 0) {
            return null;
        }
        AudioSampleFormat format;
        if (isFloat && bits == 32) {
            format = AudioSampleFormat.FLOAT32;
        } else if (isPcm && bits == 16) {
            format = AudioSampleFormat.PCM_S16;
        } else if (isPcm && bits == 24) {
            format = AudioSampleFormat.PCM_S24;
        } else if (isPcm && bits == 32) {
            format = AudioSampleFormat.PCM_S32;
        } else {
            return null;
        }
        return new AudioDeviceFormat(rate, channels, format);
    }

    /**
     * Throws when an {@code HRESULT} reports a failure, naming device-level failures in the
     * message.
     *
     * @param operation the name of the failed call
     * @throws MediaException.Io if {@code result} is negative
     */
    static void check(int result, String operation) {
        if (result < 0) {
            throw failure(operation + " failed" + describe(result), result);
        }
    }

    /**
     * Builds the failure raised for a failed call.
     *
     * @param message the description of the failure
     * @param result the {@code HRESULT}
     * @return the exception
     */
    static MediaException.Io failure(String message, int result) {
        var detail = message + String.format(Locale.ROOT, " (0x%08X)", result);
        return new MediaException.Io(detail, new IOException(detail));
    }

    /**
     * Describes the audio-specific {@code HRESULT}s.
     *
     * @return a clause starting with a colon, or the empty string
     */
    static String describe(int result) {
        return switch (result) {
            case E_DEVICE_INVALIDATED -> ": the device was removed or disabled";
            case E_DEVICE_IN_USE -> ": another application uses the device exclusively";
            case E_EXCLUSIVE_MODE_NOT_ALLOWED -> ": the device does not allow exclusive mode";
            case E_UNSUPPORTED_FORMAT -> ": the device does not support the format";
            case E_SERVICE_NOT_RUNNING -> ": the Windows Audio service is not running";
            case E_RESOURCES_INVALIDATED -> ": the audio resources were invalidated";
            case E_NOTFOUND -> ": no such device";
            default -> "";
        };
    }
}
