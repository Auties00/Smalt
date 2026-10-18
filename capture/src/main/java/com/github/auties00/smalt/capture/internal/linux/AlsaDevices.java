package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceFormat;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Lists the ALSA PCM devices an application would offer a user.
 *
 * <p>Devices come from {@code snd_device_name_hint}. Of the many PCMs a configuration defines,
 * the list keeps those that name an endpoint: {@code default} (the sound server's default sink or
 * source when PulseAudio or PipeWire is running, otherwise the first card), {@code pulse} and
 * {@code pipewire} (the sound server itself), {@code sysdefault:} (a card's default),
 * {@code hdmi:} (HDMI and DisplayPort outputs), and the raw {@code hw:} and {@code plughw:}
 * devices. A hint without an {@code IOID} serves both directions. The {@code default} device is
 * the default of both directions and is listed first; when the hints omit it, it is added.
 *
 * <p>Formats are reported only for the raw {@code hw:} devices, which run at a fixed set of
 * formats: they are opened without blocking and their hardware parameter space is tested at
 * common rates. The other devices convert any format and report none. A raw device that another
 * application holds reports none either.
 */
final class AlsaDevices {
    /**
     * The name of the default device.
     */
    static final String DEFAULT = "default";

    /**
     * The name prefixes of the devices kept from the hints.
     */
    private static final List<String> KEPT = List.of(DEFAULT, "pulse", "pipewire", "sysdefault:", "hdmi:", "hw:", "plughw:");

    /**
     * The sampling rates probed on raw devices, in ascending order.
     */
    private static final int[] PROBED_RATES = {8_000, 16_000, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000, 192_000};

    /**
     * The encodings probed on raw devices, in order of preference.
     */
    private static final List<AudioSampleFormat> PROBED_FORMATS = List.of(AudioSampleFormat.FLOAT32, AudioSampleFormat.PCM_S32,
            AudioSampleFormat.PCM_S24, AudioSampleFormat.PCM_S16);

    /**
     * Prevents instantiation.
     */
    private AlsaDevices() {
        throw new AssertionError();
    }

    /**
     * Lists the input devices, probing the formats of each raw one.
     *
     * @return the inputs, {@code default} first
     */
    static List<AudioDevice.Input> inputs() {
        return list("Output").stream().map(AlsaDevices::input).toList();
    }

    /**
     * Lists the output devices, probing the formats of each raw one.
     *
     * @return the outputs, {@code default} first
     */
    static List<AudioDevice.Output> outputs() {
        return list("Input").stream().map(AlsaDevices::output).toList();
    }

    /**
     * Finds an input device, probing the formats of that device only.
     *
     * @param id the device name, or {@code null} for the {@code default} device
     * @return the input, or empty when no input has that name
     */
    static Optional<AudioDevice.Input> input(String id) {
        var name = id == null ? DEFAULT : id;
        return list("Output").stream().filter(hint -> hint.name().equals(name)).findFirst().map(AlsaDevices::input);
    }

    /**
     * Finds an output device, probing the formats of that device only.
     *
     * @param id the device name, or {@code null} for the {@code default} device
     * @return the output, or empty when no output has that name
     */
    static Optional<AudioDevice.Output> output(String id) {
        var name = id == null ? DEFAULT : id;
        return list("Input").stream().filter(hint -> hint.name().equals(name)).findFirst().map(AlsaDevices::output);
    }

    private static AudioDevice.Input input(Hint hint) {
        return new AudioDevice.Input(hint.name(), hint.description(), hint.name().equals(DEFAULT),
                formats(hint.name(), Alsa.STREAM_CAPTURE));
    }

    private static AudioDevice.Output output(Hint hint) {
        return new AudioDevice.Output(hint.name(), hint.description(), hint.name().equals(DEFAULT),
                formats(hint.name(), Alsa.STREAM_PLAYBACK));
    }

    /**
     * Lists the kept hints of one direction.
     *
     * @param excluded the {@code IOID} of the other direction
     * @return the hints, {@code default} first
     */
    private static List<Hint> list(String excluded) {
        var hints = new ArrayList<Hint>();
        try (var arena = Arena.ofConfined()) {
            var array = Alsa.hints(arena);
            if (!array.equals(MemorySegment.NULL)) {
                try {
                    var view = array.reinterpret(Long.MAX_VALUE);
                    for (var i = 0L; ; i++) {
                        var hint = view.getAtIndex(ADDRESS, i);
                        if (hint.equals(MemorySegment.NULL)) {
                            break;
                        }
                        var name = Alsa.hint(hint, "NAME", arena);
                        if (name == null || !kept(name)) {
                            continue;
                        }
                        var io = Alsa.hint(hint, "IOID", arena);
                        if (excluded.equals(io)) {
                            continue;
                        }
                        var description = Alsa.hint(hint, "DESC", arena);
                        var label = description == null || description.isBlank() ? name : description.replace('\n', ',').replace(",", ", ").replaceAll(" +", " ");
                        if (hints.stream().noneMatch(existing -> existing.name().equals(name))) {
                            hints.add(new Hint(name, label));
                        }
                    }
                } finally {
                    Alsa.freeHints(array);
                }
            }
        }
        var defaults = hints.stream().filter(hint -> hint.name().equals(DEFAULT)).toList();
        hints.removeAll(defaults);
        hints.addFirst(defaults.isEmpty() ? new Hint(DEFAULT, "Default ALSA device") : defaults.getFirst());
        return hints;
    }

    /**
     * Returns whether a device name is one the list keeps.
     *
     * @return whether it is kept
     */
    private static boolean kept(String name) {
        for (var prefix : KEPT) {
            if (prefix.endsWith(":") ? name.startsWith(prefix) : name.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether a device is a raw hardware device, which neither converts nor mixes.
     *
     * @param name the device name
     * @return whether it starts with {@code hw:}
     */
    static boolean raw(String name) {
        return name.startsWith("hw:");
    }

    /**
     * Probes the formats of a raw device.
     *
     * @param name the device name
     * @param stream the direction
     * @return the formats, or an empty list for a converting device or one that cannot be opened
     */
    private static List<AudioDeviceFormat> formats(String name, int stream) {
        if (!raw(name)) {
            return List.of();
        }
        try (var arena = Arena.ofConfined()) {
            var slot = arena.allocate(ADDRESS);
            if (Alsa.open(name, stream, Alsa.NONBLOCK, arena, slot) < 0) {
                return List.of();
            }
            var pcm = slot.get(ADDRESS, 0);
            try {
                var params = Alsa.hwParams(pcm, arena);
                if (params.equals(MemorySegment.NULL)) {
                    return List.of();
                }
                try {
                    var channels = Alsa.supportsChannels(pcm, params, 2) ? 2 : Alsa.supportsChannels(pcm, params, 1) ? 1 : 0;
                    for (var candidate = 3; channels == 0 && candidate <= 32; candidate++) {
                        if (Alsa.supportsChannels(pcm, params, candidate)) {
                            channels = candidate;
                        }
                    }
                    if (channels == 0) {
                        return List.of();
                    }
                    var formats = new ArrayList<AudioDeviceFormat>();
                    for (var rate : PROBED_RATES) {
                        if (!Alsa.supportsRate(pcm, params, rate)) {
                            continue;
                        }
                        for (var format : PROBED_FORMATS) {
                            if (Alsa.supportsFormat(pcm, params, Alsa.format(format))) {
                                formats.add(new AudioDeviceFormat(rate, channels, format));
                            }
                        }
                    }
                    return List.copyOf(formats);
                } finally {
                    Alsa.freeHwParams(params);
                }
            } finally {
                Alsa.close(pcm);
            }
        }
    }

    /**
     * Describes a kept hint.
     *
     * @param name the device name
     * @param description the human-readable label
     */
    private record Hint(String name, String description) {
    }
}
