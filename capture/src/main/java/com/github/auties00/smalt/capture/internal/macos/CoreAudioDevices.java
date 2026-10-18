package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceFormat;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Lists the audio devices of the CoreAudio hardware abstraction layer.
 *
 * <p>Every device of {@code kAudioHardwarePropertyDevices} with input channels is an input, and
 * every one with output channels an output, so a headset appears in both lists. A device is
 * identified by its {@code kAudioDevicePropertyDeviceUID}, which is stable across reboots, and
 * resolved back to its current {@code AudioObjectID} by listing again. Its formats are 32-bit
 * float (the HAL's canonical format) at its channel count and nominal rate, followed by its other
 * available nominal rates. {@link #input(String)} and {@link #output(String)} read the channels
 * and formats of the device they return only, and agree with {@link #inputs()} and
 * {@link #outputs()}.
 */
final class CoreAudioDevices {
    /**
     * The common rates reported for a device that offers a continuous range.
     */
    private static final int[] COMMON_RATES = {8_000, 16_000, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000};

    /**
     * Prevents instantiation.
     */
    private CoreAudioDevices() {
        throw new AssertionError();
    }

    /**
     * Lists the input devices.
     *
     * @return the inputs, the default one first
     */
    static List<AudioDevice.Input> inputs() {
        var defaultId = CoreAudio.intProperty(CoreAudio.SYSTEM_OBJECT, CoreAudio.DEFAULT_INPUT_DEVICE, CoreAudio.SCOPE_GLOBAL, 0);
        var inputs = new ArrayList<AudioDevice.Input>();
        for (var object : objects()) {
            var channels = channels(object, CoreAudio.SCOPE_INPUT);
            var uid = CoreAudio.stringProperty(object, CoreAudio.DEVICE_UID);
            if (channels == 0 || uid == null) {
                continue;
            }
            var device = new AudioDevice.Input(uid, name(object, uid), object == defaultId, formats(object, channels));
            if (device.defaultDevice()) {
                inputs.addFirst(device);
            } else {
                inputs.add(device);
            }
        }
        return List.copyOf(inputs);
    }

    /**
     * Lists the output devices.
     *
     * @return the outputs, the default one first
     */
    static List<AudioDevice.Output> outputs() {
        var defaultId = CoreAudio.intProperty(CoreAudio.SYSTEM_OBJECT, CoreAudio.DEFAULT_OUTPUT_DEVICE, CoreAudio.SCOPE_GLOBAL, 0);
        var outputs = new ArrayList<AudioDevice.Output>();
        for (var object : objects()) {
            var channels = channels(object, CoreAudio.SCOPE_OUTPUT);
            var uid = CoreAudio.stringProperty(object, CoreAudio.DEVICE_UID);
            if (channels == 0 || uid == null) {
                continue;
            }
            var device = new AudioDevice.Output(uid, name(object, uid), object == defaultId, formats(object, channels));
            if (device.defaultDevice()) {
                outputs.addFirst(device);
            } else {
                outputs.add(device);
            }
        }
        return List.copyOf(outputs);
    }

    /**
     * Returns the input device {@link #inputs()} reports with a UID, or the default input.
     *
     * @param uid the UID, or {@code null} for the default input
     * @return the device, or empty when no input has the UID or there is no default input
     */
    static Optional<AudioDevice.Input> input(String uid) {
        var defaultId = CoreAudio.intProperty(CoreAudio.SYSTEM_OBJECT, CoreAudio.DEFAULT_INPUT_DEVICE, CoreAudio.SCOPE_GLOBAL, 0);
        var object = uid == null ? listed(defaultId) : resolve(uid);
        var channels = object == 0 ? 0 : channels(object, CoreAudio.SCOPE_INPUT);
        var id = channels == 0 ? null : CoreAudio.stringProperty(object, CoreAudio.DEVICE_UID);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.of(new AudioDevice.Input(id, name(object, id), object == defaultId, formats(object, channels)));
    }

    /**
     * Returns the output device {@link #outputs()} reports with a UID, or the default output.
     *
     * @param uid the UID, or {@code null} for the default output
     * @return the device, or empty when no output has the UID or there is no default output
     */
    static Optional<AudioDevice.Output> output(String uid) {
        var defaultId = CoreAudio.intProperty(CoreAudio.SYSTEM_OBJECT, CoreAudio.DEFAULT_OUTPUT_DEVICE, CoreAudio.SCOPE_GLOBAL, 0);
        var object = uid == null ? listed(defaultId) : resolve(uid);
        var channels = object == 0 ? 0 : channels(object, CoreAudio.SCOPE_OUTPUT);
        var id = channels == 0 ? null : CoreAudio.stringProperty(object, CoreAudio.DEVICE_UID);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.of(new AudioDevice.Output(id, name(object, id), object == defaultId, formats(object, channels)));
    }

    /**
     * Returns an audio object if it is one of the listed devices.
     *
     * @param object the {@code AudioObjectID}
     * @return {@code object}, or {@code 0} when it is not listed
     */
    private static int listed(int object) {
        if (object != 0) {
            for (var listed : objects()) {
                if (listed == object) {
                    return object;
                }
            }
        }
        return 0;
    }

    /**
     * Resolves a device UID to its current audio object.
     *
     * @param uid the UID
     * @return the {@code AudioObjectID}, or {@code 0} when no device has the UID
     */
    static int resolve(String uid) {
        for (var object : objects()) {
            if (uid.equals(CoreAudio.stringProperty(object, CoreAudio.DEVICE_UID))) {
                return object;
            }
        }
        return 0;
    }

    /**
     * Lists the audio objects of every device.
     *
     * @return the {@code AudioObjectID}s
     */
    static int[] objects() {
        try (var arena = Arena.ofConfined()) {
            var address = CoreAudio.address(arena, CoreAudio.DEVICES, CoreAudio.SCOPE_GLOBAL);
            var size = CoreAudio.propertySize(CoreAudio.SYSTEM_OBJECT, address, arena);
            if (size <= 0) {
                return new int[0];
            }
            var value = arena.allocate(size, 4);
            var written = CoreAudio.property(CoreAudio.SYSTEM_OBJECT, address, value, arena);
            if (written <= 0) {
                return new int[0];
            }
            var objects = new int[written / 4];
            for (var i = 0; i < objects.length; i++) {
                objects[i] = value.getAtIndex(JAVA_INT, i);
            }
            return objects;
        }
    }

    /**
     * Returns the channel count of a device in one direction.
     *
     * @param object the device
     * @param scope  {@link CoreAudio#SCOPE_INPUT} or {@link CoreAudio#SCOPE_OUTPUT}
     * @return the channels of every stream of that direction, summed; {@code 0} when it has none
     */
    static int channels(int object, int scope) {
        try (var arena = Arena.ofConfined()) {
            var address = CoreAudio.address(arena, CoreAudio.STREAM_CONFIGURATION, scope);
            var size = CoreAudio.propertySize(object, address, arena);
            if (size < 8) {
                return 0;
            }
            var list = arena.allocate(size, 8);
            if (CoreAudio.property(object, address, list, arena) < 8) {
                return 0;
            }
            var buffers = list.get(JAVA_INT, 0);
            var channels = 0;
            for (var i = 0; i < buffers && 8 + 16L * (i + 1) <= size; i++) {
                channels += list.get(JAVA_INT, 8 + 16L * i);
            }
            return channels;
        }
    }

    /**
     * Returns the name of a device.
     *
     * @param object   the device
     * @param fallback the name returned when the device has none
     * @return the name
     */
    private static String name(int object, String fallback) {
        var name = CoreAudio.stringProperty(object, CoreAudio.NAME);
        return name == null || name.isBlank() ? fallback : name;
    }

    /**
     * Returns the formats of a device: its nominal rate first, then its other available rates.
     *
     * @param object   the device
     * @param channels its channel count in the listed direction
     * @return the formats
     */
    private static List<AudioDeviceFormat> formats(int object, int channels) {
        var formats = new LinkedHashSet<AudioDeviceFormat>();
        var nominal = (int) Math.round(CoreAudio.doubleProperty(object, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, 0));
        if (nominal > 0) {
            formats.add(new AudioDeviceFormat(nominal, channels, AudioSampleFormat.FLOAT32));
        }
        try (var arena = Arena.ofConfined()) {
            var address = CoreAudio.address(arena, CoreAudio.AVAILABLE_NOMINAL_SAMPLE_RATES, CoreAudio.SCOPE_GLOBAL);
            var size = CoreAudio.propertySize(object, address, arena);
            if (size >= 16) {
                var ranges = arena.allocate(size, 8);
                var written = CoreAudio.property(object, address, ranges, arena);
                for (var i = 0; i + 1 < written / 8; i += 2) {
                    var minimum = ranges.getAtIndex(JAVA_DOUBLE, i);
                    var maximum = ranges.getAtIndex(JAVA_DOUBLE, i + 1);
                    if (minimum == maximum) {
                        if (minimum >= 1) {
                            formats.add(new AudioDeviceFormat((int) Math.round(minimum), channels, AudioSampleFormat.FLOAT32));
                        }
                        continue;
                    }
                    for (var rate : COMMON_RATES) {
                        if (rate >= minimum && rate <= maximum) {
                            formats.add(new AudioDeviceFormat(rate, channels, AudioSampleFormat.FLOAT32));
                        }
                    }
                }
            }
        }
        return List.copyOf(formats);
    }
}
