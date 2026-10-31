package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceFormat;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns the sinks and sources of the sound server into audio devices and finds the monitor
 * source that is the loopback of an output.
 *
 * <p>A sound-server device has the identifier {@value #PREFIX} followed by the server's name of
 * the sink or source, so it never collides with an ALSA device name. While a server answers, its
 * default sink and source are the default devices, so a change of the server's default is a change
 * of the default device; the ALSA {@code default} device, which follows the server's default by
 * itself, stays listed but is not flagged. Sources include the monitor source of every sink.
 *
 * <p>The loopback of an output is the monitor source of the sink it plays to: of the named sink
 * for a sound-server device, of the server's default sink ({@value Pulse#DEFAULT_MONITOR}) for the
 * ALSA {@code default}, {@code pulse} and {@code pipewire} devices, and for an ALSA device naming a
 * card ({@code hw:CARD=X,DEV=N}, {@code plughw:...}, {@code sysdefault:CARD=X}, {@code hdmi:...})
 * of the sink the server drives that card with, its {@code alsa.card} (or PipeWire's
 * {@code api.alsa.card}) property matching the card's number and, when the name gives one,
 * {@code alsa.device} the device number.
 */
final class PulseDevices {
    /**
     * The prefix of the identifiers of sound-server devices.
     */
    static final String PREFIX = "pulse:";

    /**
     * The ALSA devices that reach the sound server's defaults.
     */
    private static final List<String> SERVER_ALIASES = List.of(AlsaDevices.DEFAULT, "pulse", "pipewire");

    /**
     * The {@code CARD=} argument of an ALSA device name.
     */
    private static final Pattern CARD = Pattern.compile("[:,]CARD=([^,]+)");

    /**
     * The {@code DEV=} argument of an ALSA device name.
     */
    private static final Pattern DEV = Pattern.compile("[:,]DEV=(\\d+)");

    /**
     * The target of a {@code /proc/asound} card link, {@code cardN}.
     */
    private static final Pattern CARD_LINK = Pattern.compile("card(\\d+)");

    private PulseDevices() {
        throw new AssertionError();
    }

    /**
     * Returns whether an identifier names a sound-server device.
     *
     * @param id the identifier
     * @return whether it starts with {@value #PREFIX}
     */
    static boolean owns(String id) {
        return id.startsWith(PREFIX);
    }

    /**
     * Returns the server's name of a sound-server device.
     *
     * @param id the identifier, which {@link #owns(String)}
     * @return the sink or source name
     */
    static String serverName(String id) {
        return id.substring(PREFIX.length());
    }

    /**
     * Lists the sources as inputs, the default source flagged.
     *
     * @param snapshot the server's devices
     * @return the inputs
     */
    static List<AudioDevice.Input> inputs(PulseServer.Snapshot snapshot) {
        return snapshot.sources().stream().map(source -> new AudioDevice.Input(PREFIX + source.name(), source.description(),
                source.name().equals(snapshot.defaultSource()), formats(source))).toList();
    }

    /**
     * Lists the sinks as outputs, the default sink flagged.
     *
     * @param snapshot the server's devices
     * @return the outputs
     */
    static List<AudioDevice.Output> outputs(PulseServer.Snapshot snapshot) {
        return snapshot.sinks().stream().map(sink -> new AudioDevice.Output(PREFIX + sink.name(), sink.description(),
                sink.name().equals(snapshot.defaultSink()), formats(sink))).toList();
    }

    /**
     * Returns the native format of a sink or source.
     *
     * @param device the sink or source
     * @return its sample rate and channel count in 32-bit floats, or nothing when the server reported none
     */
    private static List<AudioDeviceFormat> formats(PulseServer.Device device) {
        if (device.sampleRate() <= 0 || device.channels() <= 0) {
            return List.of();
        }
        return List.of(new AudioDeviceFormat(device.sampleRate(), device.channels(), AudioSampleFormat.FLOAT32));
    }

    /**
     * Merges the ALSA devices with the server's, clearing the default flag of the ALSA devices when
     * the server has a default.
     *
     * @param alsa the ALSA devices
     * @param server the server's devices
     * @param unflag rebuilds an ALSA device without its default flag
     * @param <D> the device type
     * @return the ALSA devices followed by the server's
     */
    static <D extends AudioDevice> List<D> merge(List<D> alsa, List<D> server, Function<D, D> unflag) {
        var merged = new ArrayList<D>(alsa.size() + server.size());
        var serverDefault = server.stream().anyMatch(AudioDevice::defaultDevice);
        for (var device : alsa) {
            merged.add(serverDefault && device.defaultDevice() ? unflag.apply(device) : device);
        }
        merged.addAll(server);
        return List.copyOf(merged);
    }

    /**
     * Finds the source that is the loopback of an output.
     *
     * @param id the output's identifier
     * @param snapshot the server's devices
     * @param cards resolves an ALSA card identifier to its number
     * @return the source name, or empty when no sink of the server plays to the output
     */
    static Optional<String> monitorOf(String id, PulseServer.Snapshot snapshot, Function<String, Optional<Integer>> cards) {
        if (owns(id)) {
            var name = serverName(id);
            return snapshot.sinks().stream().filter(sink -> sink.name().equals(name)).findFirst()
                    .map(sink -> sink.monitor() != null ? sink.monitor() : sink.name() + ".monitor");
        }
        if (SERVER_ALIASES.contains(id)) {
            return Optional.of(Pulse.DEFAULT_MONITOR);
        }
        var card = CARD.matcher(id);
        if (!card.find()) {
            return Optional.empty();
        }
        var number = card.group(1).chars().allMatch(Character::isDigit)
                ? Optional.of(Integer.parseInt(card.group(1))) : cards.apply(card.group(1));
        if (number.isEmpty()) {
            return Optional.empty();
        }
        var dev = DEV.matcher(id);
        var device = dev.find() && !id.startsWith("hdmi:") ? dev.group(1) : null;
        var cardNumber = Integer.toString(number.get());
        return snapshot.sinks().stream()
                .filter(sink -> cardNumber.equals(sink.alsaCard()) && (device == null || device.equals(sink.alsaDevice())))
                .findFirst()
                .map(sink -> sink.monitor() != null ? sink.monitor() : sink.name() + ".monitor");
    }

    /**
     * Resolves an ALSA card identifier to its number through {@code /proc/asound}.
     *
     * @param id the card identifier, such as {@code PCH}
     * @return the card number, or empty when no such card exists
     */
    static Optional<Integer> cardNumber(String id) {
        try {
            var link = Files.readSymbolicLink(Path.of("/proc/asound").resolve(id)).getFileName().toString();
            var matcher = CARD_LINK.matcher(link);
            return matcher.matches() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }
}
