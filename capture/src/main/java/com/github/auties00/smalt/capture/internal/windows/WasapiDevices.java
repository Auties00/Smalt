package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceFormat;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Lists and resolves the audio endpoints of the MMDevice API.
 *
 * <p>Every method runs on a thread that belongs to the COM multithreaded apartment: the listing
 * methods are called through {@link Apartment}, the others by a stream's own thread. An endpoint
 * is identified by the string {@code IMMDevice::GetId} returns, which is stable across reboots,
 * and named by its {@code PKEY_Device_FriendlyName}.
 */
final class WasapiDevices {
    /**
     * The sampling rates probed for exclusive-mode support, in ascending order.
     */
    private static final int[] PROBED_RATES = {8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000};

    /**
     * Prevents instantiation.
     */
    private WasapiDevices() {
        throw new AssertionError();
    }

    /**
     * Lists the active input endpoints, probing the exclusive-mode formats of each.
     *
     * @return the inputs, the default one first
     * @throws MediaException.Io if the enumeration fails
     */
    static List<AudioDevice.Input> inputs() {
        return Apartment.call("smalt-audio-enumeration",
                () -> list(Wasapi.E_CAPTURE, (id, defaultDevice) -> true).stream().map(Endpoint::input).toList());
    }

    /**
     * Lists the active output endpoints, probing the exclusive-mode formats of each.
     *
     * @return the outputs, the default one first
     * @throws MediaException.Io if the enumeration fails
     */
    static List<AudioDevice.Output> outputs() {
        return Apartment.call("smalt-audio-enumeration",
                () -> list(Wasapi.E_RENDER, (id, defaultDevice) -> true).stream().map(Endpoint::output).toList());
    }

    /**
     * Finds an active input endpoint, probing the formats of that endpoint only.
     *
     * @param id the identifier, or {@code null} for the default endpoint
     * @return the input, or empty when none matches
     * @throws MediaException.Io if the enumeration fails
     */
    static Optional<AudioDevice.Input> input(String id) {
        return Apartment.call("smalt-audio-enumeration",
                () -> list(Wasapi.E_CAPTURE, matcher(id)).stream().findFirst().map(Endpoint::input));
    }

    /**
     * Finds an active output endpoint, probing the formats of that endpoint only.
     *
     * @param id the identifier, or {@code null} for the default endpoint
     * @return the output, or empty when none matches
     * @throws MediaException.Io if the enumeration fails
     */
    static Optional<AudioDevice.Output> output(String id) {
        return Apartment.call("smalt-audio-enumeration",
                () -> list(Wasapi.E_RENDER, matcher(id)).stream().findFirst().map(Endpoint::output));
    }

    /**
     * Returns the filter of {@link #list(int, BiPredicate)} that keeps the endpoint with an identifier,
     * or the default endpoint when {@code id} is {@code null}.
     */
    private static BiPredicate<String, Boolean> matcher(String id) {
        return id == null ? (endpointId, defaultDevice) -> defaultDevice : (endpointId, defaultDevice) -> endpointId.equals(id);
    }

    /**
     * Lists the active endpoints of a data flow that a filter keeps, naming and probing only those.
     *
     * @param flow {@link Wasapi#E_RENDER} or {@link Wasapi#E_CAPTURE}
     * @param keep receives an endpoint's identifier and whether it is the default one
     * @return the kept endpoints, the default one first
     */
    private static List<Endpoint> list(int flow, BiPredicate<String, Boolean> keep) {
        try (var arena = Arena.ofConfined()) {
            var enumerator = Wasapi.createEnumerator(arena);
            var collection = MemorySegment.NULL;
            try {
                var defaultId = defaultId(enumerator, flow, arena);
                var slot = arena.allocate(ADDRESS);
                Wasapi.check(Com.call(enumerator, Wasapi.ENUM_AUDIO_ENDPOINTS, flow, Wasapi.DEVICE_STATE_ACTIVE, slot),
                        "IMMDeviceEnumerator::EnumAudioEndpoints");
                collection = slot.get(ADDRESS, 0);
                var count = arena.allocate(JAVA_INT);
                Wasapi.check(Com.call(collection, Wasapi.COLLECTION_GET_COUNT, count), "IMMDeviceCollection::GetCount");
                var endpoints = new ArrayList<Endpoint>();
                for (var i = 0; i < count.get(JAVA_INT, 0); i++) {
                    Wasapi.check(Com.call(collection, Wasapi.COLLECTION_ITEM, i, slot), "IMMDeviceCollection::Item");
                    var device = slot.get(ADDRESS, 0);
                    try {
                        var id = id(device, arena);
                        var defaultDevice = id.equals(defaultId);
                        if (!keep.test(id, defaultDevice)) {
                            continue;
                        }
                        var endpoint = new Endpoint(id, friendlyName(device, arena, id), defaultDevice, formats(device, arena));
                        if (defaultDevice) {
                            endpoints.addFirst(endpoint);
                        } else {
                            endpoints.add(endpoint);
                        }
                    } finally {
                        Com.release(device);
                    }
                }
                return endpoints;
            } finally {
                Com.release(collection);
                Com.release(enumerator);
            }
        }
    }

    /**
     * Returns the identifier of the default endpoint of a data flow.
     *
     * @param enumerator the {@code IMMDeviceEnumerator}
     * @param flow the data flow
     * @param arena the arena of the calling thread
     * @return the identifier, or {@code null} when the flow has no default endpoint
     */
    static String defaultId(MemorySegment enumerator, int flow, Arena arena) {
        var slot = arena.allocate(ADDRESS);
        var result = Com.call(enumerator, Wasapi.GET_DEFAULT_AUDIO_ENDPOINT, flow, Wasapi.E_CONSOLE, slot);
        if (result < 0) {
            return null;
        }
        var device = slot.get(ADDRESS, 0);
        try {
            return id(device, arena);
        } finally {
            Com.release(device);
        }
    }

    /**
     * Opens the active endpoint with an identifier.
     *
     * @param id the identifier
     * @param name the endpoint's label, reported in failures
     * @param arena the arena of the calling thread
     * @return the {@code IMMDevice}, which the caller releases
     * @throws MediaException.Io if no active endpoint has the identifier
     */
    static MemorySegment open(String id, String name, Arena arena) {
        var enumerator = Wasapi.createEnumerator(arena);
        try {
            var slot = arena.allocate(ADDRESS);
            var result = Com.call(enumerator, Wasapi.GET_DEVICE, Wasapi.wide(arena, id), slot);
            if (result < 0) {
                throw Wasapi.failure("cannot open the audio device " + name + Wasapi.describe(result), result);
            }
            var device = slot.get(ADDRESS, 0);
            var state = arena.allocate(JAVA_INT);
            var stateResult = Com.call(device, Wasapi.DEVICE_GET_STATE, state);
            if (stateResult < 0 || (state.get(JAVA_INT, 0) & Wasapi.DEVICE_STATE_ACTIVE) == 0) {
                Com.release(device);
                var message = "the audio device " + name + " is not active: it was unplugged or disabled";
                throw new MediaException.Io(message, new IOException(message));
            }
            return device;
        } finally {
            Com.release(enumerator);
        }
    }

    /**
     * Returns the identifier of an endpoint.
     *
     * @param device the {@code IMMDevice}
     * @param arena the arena of the calling thread
     * @return the identifier
     */
    static String id(MemorySegment device, Arena arena) {
        var slot = arena.allocate(ADDRESS);
        Wasapi.check(Com.call(device, Wasapi.DEVICE_GET_ID, slot), "IMMDevice::GetId");
        var pointer = slot.get(ADDRESS, 0);
        try {
            return Wasapi.readWide(pointer);
        } finally {
            Com.free(pointer);
        }
    }

    /**
     * Returns the friendly name of an endpoint.
     *
     * @param device the {@code IMMDevice}
     * @param arena the arena of the calling thread
     * @param fallback the name to return when the endpoint has none
     * @return the name
     */
    private static String friendlyName(MemorySegment device, Arena arena, String fallback) {
        var slot = arena.allocate(ADDRESS);
        if (Com.call(device, Wasapi.DEVICE_OPEN_PROPERTY_STORE, Wasapi.STGM_READ, slot) < 0) {
            return fallback;
        }
        var store = slot.get(ADDRESS, 0);
        var variant = arena.allocate(Wasapi.PROPVARIANT_SIZE, 8);
        try {
            if (Com.call(store, Wasapi.STORE_GET_VALUE, Wasapi.PKEY_DEVICE_FRIENDLY_NAME, variant) < 0) {
                return fallback;
            }
            try {
                if (variant.get(JAVA_SHORT, 0) != Wasapi.VT_LPWSTR) {
                    return fallback;
                }
                var name = Wasapi.readWide(variant.get(ADDRESS, 8));
                return name.isBlank() ? fallback : name;
            } finally {
                Wasapi.clear(variant);
            }
        } finally {
            Com.release(store);
        }
    }

    /**
     * Returns the formats an endpoint runs at without conversion: its shared-mode mix format,
     * then the formats it accepts in exclusive mode at its channel count.
     *
     * @param device the {@code IMMDevice}
     * @param arena the arena of the calling thread
     * @return the formats; empty when the endpoint cannot be activated
     */
    private static List<AudioDeviceFormat> formats(MemorySegment device, Arena arena) {
        MemorySegment client;
        try {
            client = Wasapi.activate(device, Wasapi.IID_AUDIO_CLIENT, arena);
        } catch (MediaException.Io exception) {
            return List.of();
        }
        try {
            var mix = mixFormat(client, arena);
            if (mix == null) {
                return List.of();
            }
            var formats = new LinkedHashSet<AudioDeviceFormat>();
            formats.add(mix);
            probe:
            for (var rate : PROBED_RATES) {
                for (var candidate : ExclusiveFormat.CANDIDATES) {
                    var format = candidate.waveFormat(arena, rate, mix.channels());
                    var result = Com.call(client, Wasapi.CLIENT_IS_FORMAT_SUPPORTED, Wasapi.SHAREMODE_EXCLUSIVE, format, MemorySegment.NULL);
                    if (result == Wasapi.E_EXCLUSIVE_MODE_NOT_ALLOWED || result == Wasapi.E_DEVICE_INVALIDATED) {
                        break probe;
                    }
                    if (result == 0) {
                        formats.add(new AudioDeviceFormat(rate, mix.channels(), candidate.sampleFormat()));
                    }
                }
            }
            return List.copyOf(formats);
        } finally {
            Com.release(client);
        }
    }

    /**
     * Returns the shared-mode mix format of an audio client.
     *
     * @param client the {@code IAudioClient}
     * @param arena the arena of the calling thread
     * @return the format, or {@code null} when it is not linear PCM or float
     * @throws MediaException.Io if the format cannot be read
     */
    static AudioDeviceFormat mixFormat(MemorySegment client, Arena arena) {
        var slot = arena.allocate(ADDRESS);
        Wasapi.check(Com.call(client, Wasapi.CLIENT_GET_MIX_FORMAT, slot), "IAudioClient::GetMixFormat");
        var pointer = slot.get(ADDRESS, 0);
        try {
            return Wasapi.parseWaveFormat(pointer);
        } finally {
            Com.free(pointer);
        }
    }

    /**
     * Describes one sample encoding tried in exclusive mode.
     *
     * @param sampleFormat the encoding
     * @param validBits the significant bits of each sample
     */
    record ExclusiveFormat(AudioSampleFormat sampleFormat, int validBits) {
        /**
         * The encodings tried, in order of preference.
         */
        static final List<ExclusiveFormat> CANDIDATES = List.of(
                new ExclusiveFormat(AudioSampleFormat.FLOAT32, 32),
                new ExclusiveFormat(AudioSampleFormat.PCM_S32, 32),
                new ExclusiveFormat(AudioSampleFormat.PCM_S32, 24),
                new ExclusiveFormat(AudioSampleFormat.PCM_S24, 24),
                new ExclusiveFormat(AudioSampleFormat.PCM_S16, 16));

        /**
         * Builds the {@code WAVEFORMATEXTENSIBLE} of this encoding.
         *
         * @param arena the arena to allocate in
         * @param rate the sampling rate
         * @param channels the channel count
         * @return the structure
         */
        MemorySegment waveFormat(Arena arena, int rate, int channels) {
            return Wasapi.waveFormat(arena, rate, channels, sampleFormat, validBits);
        }
    }

    /**
     * Describes an endpoint while it is listed.
     *
     * @param id the identifier
     * @param name the friendly name
     * @param defaultDevice whether it is the default of its flow
     * @param formats the formats it runs at without conversion
     */
    private record Endpoint(String id, String name, boolean defaultDevice, List<AudioDeviceFormat> formats) {
        AudioDevice.Input input() {
            return new AudioDevice.Input(id, name, defaultDevice, formats);
        }

        AudioDevice.Output output() {
            return new AudioDevice.Output(id, name, defaultDevice, formats);
        }
    }
}
