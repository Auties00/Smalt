package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.Native;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Calls CoreAudio's hardware abstraction layer and AudioToolbox's audio units through the
 * Foreign Function and Memory API.
 *
 * <p>Audio objects (the system object, devices) are read and written with the
 * {@code AudioObject*} property functions of {@code CoreAudio.framework}, addressed by a
 * {@code AudioObjectPropertyAddress} of selector, scope and element; audio units are created
 * with {@code AudioComponentFindNext} and {@code AudioComponentInstanceNew} and driven with the
 * {@code AudioUnit*} functions of {@code AudioToolbox.framework}. The constants are those of the
 * macOS SDK headers {@code AudioHardware.h}, {@code AudioHardwareBase.h},
 * {@code AudioUnitProperties.h}, {@code AUComponent.h}, {@code AudioComponent.h} and
 * {@code CoreAudioBaseTypes.h}; four-character codes are written as text and converted with
 * {@link FourCc#of(String)}. Both frameworks are loaded when this class is initialized, which
 * only happens on macOS.
 */
@SuppressWarnings("restricted")
final class CoreAudio {
    /**
     * CoreAudio.
     */
    private static final String CORE_AUDIO_PATH = "/System/Library/Frameworks/CoreAudio.framework/Versions/A/CoreAudio";

    /**
     * AudioToolbox.
     */
    private static final String AUDIO_TOOLBOX_PATH = "/System/Library/Frameworks/AudioToolbox.framework/Versions/A/AudioToolbox";

    /**
     * {@code kAudioObjectSystemObject}.
     */
    static final int SYSTEM_OBJECT = 1;

    /**
     * {@code kAudioHardwarePropertyDevices}.
     */
    static final int DEVICES = FourCc.of("dev#");

    /**
     * {@code kAudioHardwarePropertyDefaultInputDevice}.
     */
    static final int DEFAULT_INPUT_DEVICE = FourCc.of("dIn ");

    /**
     * {@code kAudioHardwarePropertyDefaultOutputDevice}.
     */
    static final int DEFAULT_OUTPUT_DEVICE = FourCc.of("dOut");

    /**
     * {@code kAudioDevicePropertyDeviceUID}.
     */
    static final int DEVICE_UID = FourCc.of("uid ");

    /**
     * {@code kAudioObjectPropertyName}.
     */
    static final int NAME = FourCc.of("lnam");

    /**
     * {@code kAudioDevicePropertyStreamConfiguration}.
     */
    static final int STREAM_CONFIGURATION = FourCc.of("slay");

    /**
     * {@code kAudioDevicePropertyNominalSampleRate}.
     */
    static final int NOMINAL_SAMPLE_RATE = FourCc.of("nsrt");

    /**
     * {@code kAudioDevicePropertyAvailableNominalSampleRates}.
     */
    static final int AVAILABLE_NOMINAL_SAMPLE_RATES = FourCc.of("nsr#");

    /**
     * {@code kAudioDevicePropertyBufferFrameSize}.
     */
    static final int BUFFER_FRAME_SIZE = FourCc.of("fsiz");

    /**
     * {@code kAudioDevicePropertyLatency}.
     */
    static final int LATENCY = FourCc.of("ltnc");

    /**
     * {@code kAudioDevicePropertySafetyOffset}.
     */
    static final int SAFETY_OFFSET = FourCc.of("saft");

    /**
     * {@code kAudioDevicePropertyDeviceIsAlive}.
     */
    static final int DEVICE_IS_ALIVE = FourCc.of("livn");

    /**
     * {@code kAudioObjectPropertyScopeGlobal}.
     */
    static final int SCOPE_GLOBAL = FourCc.of("glob");

    /**
     * {@code kAudioObjectPropertyScopeInput}.
     */
    static final int SCOPE_INPUT = FourCc.of("inpt");

    /**
     * {@code kAudioObjectPropertyScopeOutput}.
     */
    static final int SCOPE_OUTPUT = FourCc.of("outp");

    /**
     * {@code kAudioObjectPropertyElementMain}.
     */
    static final int ELEMENT_MAIN = 0;

    /**
     * {@code kAudioUnitType_Output}.
     */
    static final int UNIT_TYPE_OUTPUT = FourCc.of("auou");

    /**
     * {@code kAudioUnitSubType_HALOutput}.
     */
    static final int UNIT_SUBTYPE_HAL_OUTPUT = FourCc.of("ahal");

    /**
     * {@code kAudioUnitManufacturer_Apple}.
     */
    static final int MANUFACTURER_APPLE = FourCc.of("appl");

    /**
     * {@code kAudioUnitProperty_StreamFormat}.
     */
    static final int UNIT_STREAM_FORMAT = 8;

    /**
     * {@code kAudioUnitProperty_MaximumFramesPerSlice}.
     */
    static final int UNIT_MAXIMUM_FRAMES_PER_SLICE = 14;

    /**
     * {@code kAudioUnitProperty_SetRenderCallback}.
     */
    static final int UNIT_SET_RENDER_CALLBACK = 23;

    /**
     * {@code kAudioOutputUnitProperty_CurrentDevice}.
     */
    static final int UNIT_CURRENT_DEVICE = 2000;

    /**
     * {@code kAudioOutputUnitProperty_EnableIO}.
     */
    static final int UNIT_ENABLE_IO = 2003;

    /**
     * {@code kAudioOutputUnitProperty_SetInputCallback}.
     */
    static final int UNIT_SET_INPUT_CALLBACK = 2005;

    /**
     * {@code kAudioUnitScope_Global}.
     */
    static final int UNIT_SCOPE_GLOBAL = 0;

    /**
     * {@code kAudioUnitScope_Input}.
     */
    static final int UNIT_SCOPE_INPUT = 1;

    /**
     * {@code kAudioUnitScope_Output}.
     */
    static final int UNIT_SCOPE_OUTPUT = 2;

    /**
     * The element of the HAL output unit facing the output device.
     */
    static final int OUTPUT_ELEMENT = 0;

    /**
     * The element of the HAL output unit facing the input device.
     */
    static final int INPUT_ELEMENT = 1;

    /**
     * {@code kAudioFormatLinearPCM}.
     */
    static final int FORMAT_LINEAR_PCM = FourCc.of("lpcm");

    /**
     * {@code kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked}: packed interleaved floats.
     */
    static final int FLAGS_PACKED_FLOAT = 0x1 | 0x8;

    /**
     * The size of {@code AudioStreamBasicDescription}.
     */
    static final long ASBD_SIZE = 40;

    /**
     * The size of an {@code AudioBufferList} holding one {@code AudioBuffer}.
     */
    static final long BUFFER_LIST_SIZE = 24;

    /**
     * The offset of {@code mHostTime} in {@code AudioTimeStamp}.
     */
    static final long TIMESTAMP_HOST_TIME = 8;

    /**
     * The size of {@code AudioTimeStamp}.
     */
    static final long TIMESTAMP_SIZE = 64;

    /**
     * The {@code AURenderCallback} signature: {@code OSStatus (void *inRefCon,
     * AudioUnitRenderActionFlags *ioActionFlags, const AudioTimeStamp *inTimeStamp,
     * UInt32 inBusNumber, UInt32 inNumberFrames, AudioBufferList *ioData)}.
     */
    static final FunctionDescriptor RENDER_CALLBACK = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    /**
     * The {@code AudioObjectPropertyListenerProc} signature: {@code OSStatus (AudioObjectID,
     * UInt32 inNumberAddresses, const AudioObjectPropertyAddress *inAddresses, void *inClientData)}.
     */
    static final FunctionDescriptor PROPERTY_LISTENER = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);

    /**
     * {@code OSStatus AudioObjectGetPropertyDataSize(AudioObjectID, const
     * AudioObjectPropertyAddress*, UInt32, const void*, UInt32*)}.
     */
    private static final MethodHandle GET_PROPERTY_DATA_SIZE;

    /**
     * {@code OSStatus AudioObjectGetPropertyData(AudioObjectID, const AudioObjectPropertyAddress*,
     * UInt32, const void*, UInt32*, void*)}.
     */
    private static final MethodHandle GET_PROPERTY_DATA;

    /**
     * {@code OSStatus AudioObjectSetPropertyData(AudioObjectID, const AudioObjectPropertyAddress*,
     * UInt32, const void*, UInt32, const void*)}.
     */
    private static final MethodHandle SET_PROPERTY_DATA;

    /**
     * {@code OSStatus AudioObjectAddPropertyListener(AudioObjectID, const
     * AudioObjectPropertyAddress*, AudioObjectPropertyListenerProc, void*)}.
     */
    private static final MethodHandle ADD_PROPERTY_LISTENER;

    /**
     * {@code OSStatus AudioObjectRemovePropertyListener(AudioObjectID, const
     * AudioObjectPropertyAddress*, AudioObjectPropertyListenerProc, void*)}.
     */
    private static final MethodHandle REMOVE_PROPERTY_LISTENER;

    /**
     * {@code AudioComponent AudioComponentFindNext(AudioComponent, const
     * AudioComponentDescription*)}.
     */
    private static final MethodHandle COMPONENT_FIND_NEXT;

    /**
     * {@code OSStatus AudioComponentInstanceNew(AudioComponent, AudioComponentInstance*)}.
     */
    private static final MethodHandle COMPONENT_INSTANCE_NEW;

    /**
     * {@code OSStatus AudioComponentInstanceDispose(AudioComponentInstance)}.
     */
    private static final MethodHandle COMPONENT_INSTANCE_DISPOSE;

    /**
     * {@code OSStatus AudioUnitSetProperty(AudioUnit, AudioUnitPropertyID, AudioUnitScope,
     * AudioUnitElement, const void*, UInt32)}.
     */
    private static final MethodHandle UNIT_SET_PROPERTY;

    /**
     * {@code OSStatus AudioUnitGetProperty(AudioUnit, AudioUnitPropertyID, AudioUnitScope,
     * AudioUnitElement, void*, UInt32*)}.
     */
    private static final MethodHandle UNIT_GET_PROPERTY;

    /**
     * {@code OSStatus AudioUnitInitialize(AudioUnit)}.
     */
    private static final MethodHandle UNIT_INITIALIZE;

    /**
     * {@code OSStatus AudioUnitUninitialize(AudioUnit)}.
     */
    private static final MethodHandle UNIT_UNINITIALIZE;

    /**
     * {@code OSStatus AudioOutputUnitStart(AudioUnit)}.
     */
    private static final MethodHandle OUTPUT_UNIT_START;

    /**
     * {@code OSStatus AudioOutputUnitStop(AudioUnit)}.
     */
    private static final MethodHandle OUTPUT_UNIT_STOP;

    /**
     * {@code OSStatus AudioUnitRender(AudioUnit, AudioUnitRenderActionFlags*, const
     * AudioTimeStamp*, UInt32, UInt32, AudioBufferList*)}.
     */
    private static final MethodHandle UNIT_RENDER;

    /**
     * {@code uint64_t mach_absolute_time(void)}.
     */
    private static final MethodHandle MACH_ABSOLUTE_TIME;

    /**
     * The numerator of the host clock's conversion to nanoseconds.
     */
    private static final long TIMEBASE_NUMERATOR;

    /**
     * The denominator of the host clock's conversion to nanoseconds.
     */
    private static final long TIMEBASE_DENOMINATOR;

    static {
        var coreAudio = Frameworks.load(CORE_AUDIO_PATH);
        var toolbox = Frameworks.load(AUDIO_TOOLBOX_PATH);
        SymbolLookup system = Frameworks.load(Frameworks.SYSTEM);
        GET_PROPERTY_DATA_SIZE = Native.function(coreAudio, "AudioObjectGetPropertyDataSize",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        GET_PROPERTY_DATA = Native.function(coreAudio, "AudioObjectGetPropertyData",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        SET_PROPERTY_DATA = Native.function(coreAudio, "AudioObjectSetPropertyData",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        ADD_PROPERTY_LISTENER = Native.function(coreAudio, "AudioObjectAddPropertyListener",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        REMOVE_PROPERTY_LISTENER = Native.function(coreAudio, "AudioObjectRemovePropertyListener",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        COMPONENT_FIND_NEXT = Native.function(toolbox, "AudioComponentFindNext", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        COMPONENT_INSTANCE_NEW = Native.function(toolbox, "AudioComponentInstanceNew", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        COMPONENT_INSTANCE_DISPOSE = Native.function(toolbox, "AudioComponentInstanceDispose", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        UNIT_SET_PROPERTY = Native.function(toolbox, "AudioUnitSetProperty",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        UNIT_GET_PROPERTY = Native.function(toolbox, "AudioUnitGetProperty",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        UNIT_INITIALIZE = Native.function(toolbox, "AudioUnitInitialize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        UNIT_UNINITIALIZE = Native.function(toolbox, "AudioUnitUninitialize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        OUTPUT_UNIT_START = Native.function(toolbox, "AudioOutputUnitStart", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        OUTPUT_UNIT_STOP = Native.function(toolbox, "AudioOutputUnitStop", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        UNIT_RENDER = Native.function(toolbox, "AudioUnitRender",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        MACH_ABSOLUTE_TIME = Native.function(system, "mach_absolute_time", FunctionDescriptor.of(JAVA_LONG));
        var timebase = Native.function(system, "mach_timebase_info", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        var info = Arena.global().allocate(JAVA_INT, 2);
        try {
            var ignored = (int) timebase.invokeExact(info);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
        var numerator = Integer.toUnsignedLong(info.getAtIndex(JAVA_INT, 0));
        var denominator = Integer.toUnsignedLong(info.getAtIndex(JAVA_INT, 1));
        TIMEBASE_NUMERATOR = numerator == 0 ? 1 : numerator;
        TIMEBASE_DENOMINATOR = denominator == 0 ? 1 : denominator;
    }

    /**
     * Prevents instantiation.
     */
    private CoreAudio() {
        throw new AssertionError();
    }

    /**
     * Allocates an {@code AudioObjectPropertyAddress}.
     *
     * @param arena the arena to allocate in
     * @param selector the property selector
     * @return the 12-byte structure, element main
     */
    static MemorySegment address(Arena arena, int selector, int scope) {
        var address = arena.allocate(JAVA_INT, 3);
        address.setAtIndex(JAVA_INT, 0, selector);
        address.setAtIndex(JAVA_INT, 1, scope);
        address.setAtIndex(JAVA_INT, 2, ELEMENT_MAIN);
        return address;
    }

    /**
     * Returns the size of a property's value.
     *
     * @param object the audio object
     * @param address the property address
     * @param arena the arena to allocate in
     * @return the size in bytes, or {@code -1} when the object has no such property
     */
    static int propertySize(int object, MemorySegment address, Arena arena) {
        var size = arena.allocate(JAVA_INT);
        try {
            var status = (int) GET_PROPERTY_DATA_SIZE.invokeExact(object, address, 0, MemorySegment.NULL, size);
            return status != 0 ? -1 : size.get(JAVA_INT, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads a property's value.
     *
     * @param object the audio object
     * @param address the property address
     * @param value receives the value; its size is the size offered
     * @param arena the arena to allocate in
     * @return the size written, or {@code -1} on failure
     */
    static int property(int object, MemorySegment address, MemorySegment value, Arena arena) {
        var size = arena.allocate(JAVA_INT);
        size.set(JAVA_INT, 0, (int) value.byteSize());
        try {
            var status = (int) GET_PROPERTY_DATA.invokeExact(object, address, 0, MemorySegment.NULL, size, value);
            return status != 0 ? -1 : size.get(JAVA_INT, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads a 32-bit property.
     *
     * @param object the audio object
     * @param selector the property selector
     * @param fallback the value returned when the property cannot be read
     * @return the value
     */
    static int intProperty(int object, int selector, int scope, int fallback) {
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(JAVA_INT);
            return property(object, address(arena, selector, scope), value, arena) == 4 ? value.get(JAVA_INT, 0) : fallback;
        }
    }

    /**
     * Reads a 64-bit floating-point property.
     *
     * @param object the audio object
     * @param selector the property selector
     * @param fallback the value returned when the property cannot be read
     * @return the value
     */
    static double doubleProperty(int object, int selector, int scope, double fallback) {
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(java.lang.foreign.ValueLayout.JAVA_DOUBLE);
            return property(object, address(arena, selector, scope), value, arena) == 8
                    ? value.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0) : fallback;
        }
    }

    /**
     * Reads a {@code CFStringRef} property and releases it.
     *
     * @param object the audio object
     * @param selector the property selector
     * @return the string, or {@code null} when the property cannot be read
     */
    static String stringProperty(int object, int selector) {
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(ADDRESS);
            if (property(object, address(arena, selector, SCOPE_GLOBAL), value, arena) != ADDRESS.byteSize()) {
                return null;
            }
            var string = value.get(ADDRESS, 0);
            try {
                return ObjC.isNull(string) ? null : CoreFoundation.string(string);
            } finally {
                CoreFoundation.release(string);
            }
        }
    }

    /**
     * Writes a 32-bit property.
     *
     * @param object the audio object
     * @param selector the property selector
     * @return the status, {@code 0} on success
     */
    static int setIntProperty(int object, int selector, int scope, int value) {
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocate(JAVA_INT);
            data.set(JAVA_INT, 0, value);
            return (int) SET_PROPERTY_DATA.invokeExact(object, address(arena, selector, scope), 0, MemorySegment.NULL, 4, data);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Registers a property listener.
     *
     * @param object the audio object
     * @param address the property address, in memory that outlives the registration
     * @param listener the listener's upcall stub
     * @param clientData the value passed back to the listener
     * @return the status, {@code 0} on success
     */
    static int addListener(int object, MemorySegment address, MemorySegment listener, MemorySegment clientData) {
        try {
            return (int) ADD_PROPERTY_LISTENER.invokeExact(object, address, listener, clientData);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Unregisters a property listener.
     *
     * @param object the audio object
     * @param address the property address it was registered with
     * @param listener the listener's upcall stub
     * @param clientData the value it was registered with
     */
    static void removeListener(int object, MemorySegment address, MemorySegment listener, MemorySegment clientData) {
        try {
            var ignored = (int) REMOVE_PROPERTY_LISTENER.invokeExact(object, address, listener, clientData);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Creates an instance of the HAL output audio unit.
     *
     * @param arena the arena to allocate in
     * @return the unit, which {@link #dispose(MemorySegment)} releases
     * @throws MediaException.Io if the unit cannot be created
     */
    static MemorySegment newHalOutputUnit(Arena arena) {
        var description = arena.allocate(JAVA_INT, 5);
        description.setAtIndex(JAVA_INT, 0, UNIT_TYPE_OUTPUT);
        description.setAtIndex(JAVA_INT, 1, UNIT_SUBTYPE_HAL_OUTPUT);
        description.setAtIndex(JAVA_INT, 2, MANUFACTURER_APPLE);
        try {
            var component = (MemorySegment) COMPONENT_FIND_NEXT.invokeExact(MemorySegment.NULL, description);
            if (ObjC.isNull(component)) {
                throw failure("the HAL output audio unit is missing", -1);
            }
            var slot = arena.allocate(ADDRESS);
            check((int) COMPONENT_INSTANCE_NEW.invokeExact(component, slot), "AudioComponentInstanceNew");
            return slot.get(ADDRESS, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Disposes of an audio unit; {@code NULL} is ignored.
     */
    static void dispose(MemorySegment unit) {
        if (ObjC.isNull(unit)) {
            return;
        }
        try {
            var ignored = (int) COMPONENT_INSTANCE_DISPOSE.invokeExact(unit);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Sets a property of an audio unit.
     *
     * @param property the property id
     * @return the status, {@code 0} on success
     */
    static int setUnitProperty(MemorySegment unit, int property, int scope, int element, MemorySegment value) {
        try {
            return (int) UNIT_SET_PROPERTY.invokeExact(unit, property, scope, element, value, (int) value.byteSize());
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads a property of an audio unit.
     *
     * @param property the property id
     * @param value receives the value; its size is the size offered
     * @param arena the arena to allocate in
     * @return the status, {@code 0} on success
     */
    static int unitProperty(MemorySegment unit, int property, int scope, int element, MemorySegment value, Arena arena) {
        var size = arena.allocate(JAVA_INT);
        size.set(JAVA_INT, 0, (int) value.byteSize());
        try {
            return (int) UNIT_GET_PROPERTY.invokeExact(unit, property, scope, element, value, size);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Sets a 32-bit property of an audio unit.
     *
     * @param property the property id
     * @param arena the arena to allocate in
     * @return the status, {@code 0} on success
     */
    static int setUnitInt(MemorySegment unit, int property, int scope, int element, int value, Arena arena) {
        var data = arena.allocate(JAVA_INT);
        data.set(JAVA_INT, 0, value);
        return setUnitProperty(unit, property, scope, element, data);
    }

    /**
     * Initializes an audio unit.
     *
     * @return the status, {@code 0} on success
     */
    static int initialize(MemorySegment unit) {
        try {
            return (int) UNIT_INITIALIZE.invokeExact(unit);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Uninitializes an audio unit.
     */
    static void uninitialize(MemorySegment unit) {
        try {
            var ignored = (int) UNIT_UNINITIALIZE.invokeExact(unit);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Starts an output audio unit.
     *
     * @return the status, {@code 0} on success
     */
    static int start(MemorySegment unit) {
        try {
            return (int) OUTPUT_UNIT_START.invokeExact(unit);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Stops an output audio unit; returns once its render callbacks have finished.
     */
    static void stop(MemorySegment unit) {
        try {
            var ignored = (int) OUTPUT_UNIT_STOP.invokeExact(unit);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Pulls rendered audio from an audio unit: on the HAL output unit's input element, the
     * captured audio.
     *
     * @param flags the action flags of the callback
     * @param timestamp the timestamp of the callback
     * @param bus the element to render
     * @param frames the number of frames
     * @param buffers the {@code AudioBufferList} receiving the audio
     * @return the status, {@code 0} on success
     */
    static int render(MemorySegment unit, MemorySegment flags, MemorySegment timestamp, int bus, int frames, MemorySegment buffers) {
        try {
            return (int) UNIT_RENDER.invokeExact(unit, flags, timestamp, bus, frames, buffers);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the host clock in nanoseconds.
     *
     * @return {@code mach_absolute_time()} converted to nanoseconds
     */
    static long hostNanos() {
        try {
            return hostToNanos((long) MACH_ABSOLUTE_TIME.invokeExact());
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Converts host clock ticks to nanoseconds.
     *
     * @return the nanoseconds
     */
    static long hostToNanos(long ticks) {
        if (TIMEBASE_NUMERATOR == TIMEBASE_DENOMINATOR) {
            return ticks;
        }
        return ticks / TIMEBASE_DENOMINATOR * TIMEBASE_NUMERATOR + ticks % TIMEBASE_DENOMINATOR * TIMEBASE_NUMERATOR / TIMEBASE_DENOMINATOR;
    }

    /**
     * Fills an {@code AudioStreamBasicDescription} of packed interleaved 32-bit floats.
     *
     * @param arena the arena to allocate in
     * @param rate the sampling rate
     * @param channels the channel count
     * @return the 40-byte structure
     */
    static MemorySegment floatFormat(Arena arena, double rate, int channels) {
        var format = arena.allocate(ASBD_SIZE, 8);
        format.set(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0, rate);
        format.set(JAVA_INT, 8, FORMAT_LINEAR_PCM);
        format.set(JAVA_INT, 12, FLAGS_PACKED_FLOAT);
        format.set(JAVA_INT, 16, 4 * channels);
        format.set(JAVA_INT, 20, 1);
        format.set(JAVA_INT, 24, 4 * channels);
        format.set(JAVA_INT, 28, channels);
        format.set(JAVA_INT, 32, 32);
        format.set(JAVA_INT, 36, 0);
        return format;
    }

    /**
     * Throws when a status reports a failure.
     *
     * @param status the {@code OSStatus}
     * @param operation the name of the failed call
     * @throws MediaException.Io if {@code status} is not zero
     */
    static void check(int status, String operation) {
        if (status != 0) {
            throw failure(operation + " failed", status);
        }
    }

    /**
     * Builds the failure raised for a failed call.
     *
     * @param message the description of the failure
     * @param status the {@code OSStatus}
     * @return the exception
     */
    static MediaException.Io failure(String message, int status) {
        var detail = message + String.format(Locale.ROOT, " (OSStatus %d)", status);
        return new MediaException.Io(detail, new IOException(detail));
    }
}
