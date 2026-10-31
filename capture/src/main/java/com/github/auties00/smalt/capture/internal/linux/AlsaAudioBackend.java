package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioShareMode;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.audio.AudioBackend;
import com.github.auties00.smalt.capture.internal.audio.PollingDeviceWatch;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Implements audio devices on 64-bit Linux with ALSA and the PulseAudio-protocol sound server.
 *
 * <p>ALSA ({@code libasound.so.2}) is present on virtually every desktop, and its {@code default}
 * device reaches the running sound server through the PulseAudio or PipeWire ALSA plugin, so the
 * ALSA devices ({@link AlsaDevices}, streamed by {@link AlsaStream}) serve PulseAudio, PipeWire and
 * bare ALSA systems alike, with the timing ({@code snd_pcm_delay}) an echo canceller needs. When a
 * PulseAudio or PipeWire ({@code pipewire-pulse}) server answers, its sinks and sources are listed
 * besides, as {@link PulseDevices} describes, and streamed by {@link PulseStream}: they name the
 * server's devices one by one, carry its defaults, and include the monitor sources.
 *
 * <p>Loopback capture records the monitor source of the sink an output plays to
 * ({@link PulseDevices#monitorOf}), so it needs the sound server; bare ALSA has none.
 *
 * <p>Device changes are reported as they happen: the server's subscription reports new, removed
 * and changed sinks, sources and cards and a changed default, and the {@code /dev/snd} directory
 * ({@link DevWatch}) reports ALSA cards; each makes the {@link PollingDeviceWatch} list the devices
 * again and report the differences. The watch also lists the devices every
 * {@value #WATCH_INTERVAL_SECONDS} seconds, or every {@value #FALLBACK_INTERVAL_SECONDS} seconds
 * when neither source of events is available, and reconnects to a server that restarted.
 */
public final class AlsaAudioBackend implements AudioBackend {
    /**
     * The interval between two listings of the device watch while events arrive, in seconds.
     */
    private static final int WATCH_INTERVAL_SECONDS = 10;

    /**
     * The interval between two listings of the device watch without events, in seconds.
     */
    private static final int FALLBACK_INTERVAL_SECONDS = 2;

    /**
     * The ALSA nodes of {@code /dev/snd} whose appearance means a card was added or removed.
     */
    private static final Pattern SOUND_NODE = Pattern.compile("pcmC\\d+D\\d+[pc]|controlC\\d+");

    /**
     * Constructs the backend; the libraries are bound on first use.
     */
    public AlsaAudioBackend() {
    }

    /**
     * Lists the sound server's devices.
     *
     * @return the snapshot, or {@code null} without a server
     */
    private static PulseServer.Snapshot server() {
        try (var server = PulseServer.connect()) {
            return server == null ? null : server.snapshot();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation lists the ALSA capture devices when {@code libasound.so.2} is
     * present, then the sound server's sources when one answers.
     */
    @Override
    public List<AudioDevice.Input> inputs() {
        var alsa = Alsa.available() ? AlsaDevices.inputs() : List.<AudioDevice.Input>of();
        var server = server();
        return server == null ? alsa : PulseDevices.merge(alsa, PulseDevices.inputs(server),
                device -> new AudioDevice.Input(device.id(), device.name(), false, device.formats()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation lists the ALSA playback devices when {@code libasound.so.2} is
     * present, then the sound server's sinks when one answers.
     */
    @Override
    public List<AudioDevice.Output> outputs() {
        var alsa = Alsa.available() ? AlsaDevices.outputs() : List.<AudioDevice.Output>of();
        var server = server();
        return server == null ? alsa : PulseDevices.merge(alsa, PulseDevices.outputs(server),
                device -> new AudioDevice.Output(device.id(), device.name(), false, device.formats()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation returns the sound server's default source when a server
     * answers, else the ALSA {@code default} device, probing the formats of that device only.
     */
    @Override
    public Optional<AudioDevice.Input> defaultInput() {
        var server = server();
        if (server != null) {
            var found = PulseDevices.inputs(server).stream().filter(AudioDevice::defaultDevice).findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Alsa.available() ? AlsaDevices.input(null) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation returns the sound server's default sink when a server answers,
     * else the ALSA {@code default} device, probing the formats of that device only.
     */
    @Override
    public Optional<AudioDevice.Output> defaultOutput() {
        var server = server();
        if (server != null) {
            var found = PulseDevices.outputs(server).stream().filter(AudioDevice::defaultDevice).findFirst();
            if (found.isPresent()) {
                return found;
            }
        }
        return Alsa.available() ? AlsaDevices.output(null) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the matching device only.
     */
    @Override
    public Optional<AudioDevice.Input> input(String id) {
        if (PulseDevices.owns(id)) {
            var server = server();
            return server == null ? Optional.empty()
                    : PulseDevices.inputs(server).stream().filter(device -> device.id().equals(id)).findFirst();
        }
        return Alsa.available() ? AlsaDevices.input(id).map(device -> unflagged(device)) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the matching device only.
     */
    @Override
    public Optional<AudioDevice.Output> output(String id) {
        if (PulseDevices.owns(id)) {
            var server = server();
            return server == null ? Optional.empty()
                    : PulseDevices.outputs(server).stream().filter(device -> device.id().equals(id)).findFirst();
        }
        return Alsa.available() ? AlsaDevices.output(id).map(device -> unflagged(device)) : Optional.empty();
    }

    /**
     * Clears the default flag of an ALSA input while a sound server has a default.
     */
    private static AudioDevice.Input unflagged(AudioDevice.Input device) {
        if (!device.defaultDevice()) {
            return device;
        }
        var server = server();
        var serverDefault = server != null && server.defaultSource() != null;
        return serverDefault ? new AudioDevice.Input(device.id(), device.name(), false, device.formats()) : device;
    }

    /**
     * Clears the default flag of an ALSA output while a sound server has a default.
     */
    private static AudioDevice.Output unflagged(AudioDevice.Output device) {
        if (!device.defaultDevice()) {
            return device;
        }
        var server = server();
        var serverDefault = server != null && server.defaultSink() != null;
        return serverDefault ? new AudioDevice.Output(device.id(), device.name(), false, device.formats()) : device;
    }

    @Override
    public AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        if (PulseDevices.owns(device.id())) {
            checkShared(device, options);
            var format = device.formats().isEmpty() ? null : device.formats().getFirst();
            return PulseStream.openCapture(device, PulseDevices.serverName(device.id()),
                    format == null ? 0 : format.sampleRate(), format == null ? 0 : format.channels(), options);
        }
        checkAvailable();
        return AlsaStream.openCapture(device, options);
    }

    @Override
    public AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        if (PulseDevices.owns(device.id())) {
            checkShared(device, options);
            var format = device.formats().isEmpty() ? null : device.formats().getFirst();
            return PulseStream.openPlayback(device, PulseDevices.serverName(device.id()),
                    format == null ? 0 : format.sampleRate(), format == null ? 0 : format.channels(), options);
        }
        checkAvailable();
        return AlsaStream.openPlayback(device, options);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation records the monitor source of the sink the output plays to, at
     * the output's native format unless the options ask for another.
     * @throws MediaException.Unsupported if no sound server answers, none of its sinks plays to the
     * output, or the options ask for the exclusive mode
     */
    @Override
    public AudioCaptureSession openLoopback(AudioDevice.Output device, AudioStreamOptions options) {
        checkShared(device, options);
        var server = server();
        if (server == null) {
            throw new MediaException.Unsupported("loopback capture", "loopback capture needs a PulseAudio or PipeWire sound"
                    + " server, and none answers; bare ALSA has no loopback");
        }
        var monitor = PulseDevices.monitorOf(device.id(), server, PulseDevices::cardNumber).orElseThrow(
                () -> new MediaException.Unsupported("loopback capture", "no sink of the sound server " + server.server()
                        + " plays to the audio device " + device.name() + " (" + device.id() + ")"));
        var format = device.formats().isEmpty() ? null : device.formats().getFirst();
        return PulseStream.openCapture(device, monitor, format == null ? 0 : format.sampleRate(),
                format == null ? 0 : format.channels(), options);
    }

    @Override
    public DeviceWatch watch(Consumer<AudioDeviceEvent> sink) {
        if (!Alsa.available() && !Pulse.available()) {
            return () -> {
            };
        }
        var watch = new AtomicReference<PollingDeviceWatch>();
        Runnable poke = () -> {
            var current = watch.get();
            if (current != null) {
                current.poke();
            }
        };
        var subscription = new Subscription(poke);
        var dev = DevWatch.start(Path.of("/dev/snd"), SOUND_NODE, poke);
        var evented = dev != null || subscription.active();
        var interval = Duration.ofSeconds(evented ? WATCH_INTERVAL_SECONDS : FALLBACK_INTERVAL_SECONDS);
        watch.set(new PollingDeviceWatch(() -> {
            subscription.ensure();
            return inputs();
        }, this::outputs, sink, interval));
        return () -> {
            watch.get().close();
            if (dev != null) {
                dev.close();
            }
            subscription.close();
        };
    }

    /**
     * Throws when the options ask for the exclusive mode, which the sound server's streams and the
     * loopback do not have.
     *
     * @throws MediaException.Unsupported if the share mode is exclusive
     */
    private static void checkShared(AudioDevice device, AudioStreamOptions options) {
        if (options.shareMode() == AudioShareMode.EXCLUSIVE) {
            throw new MediaException.Unsupported("audio share mode", "the sound server's streams of " + device.name()
                    + " have no exclusive mode");
        }
    }

    /**
     * Throws when ALSA cannot be reached.
     *
     * @throws MediaException.Unsupported if {@code libasound.so.2} is missing
     */
    private static void checkAvailable() {
        if (!Alsa.available()) {
            throw new MediaException.Unsupported("alsa", "libasound.so.2 is not installed");
        }
    }

    /**
     * Keeps a subscription to the sound server's changes, reconnecting when the server restarted.
     */
    private static final class Subscription implements AutoCloseable {
        /**
         * Called after every change.
         */
        private final Runnable listener;

        /**
         * Guards {@link #server} and {@link #closed}.
         */
        private final Object lock = new Object();

        /**
         * The subscribed connection, or {@code null}; guarded by {@link #lock}.
         */
        private PulseServer server;

        /**
         * Whether the subscription is closed; guarded by {@link #lock}.
         */
        private boolean closed;

        /**
         * Subscribes, if a server answers.
         *
         * @param listener called on the server connection's thread after every change
         */
        Subscription(Runnable listener) {
            this.listener = listener;
            ensure();
        }

        /**
         * Returns whether the server's changes are being received.
         *
         * @return whether the subscription is connected
         */
        boolean active() {
            synchronized (lock) {
                return server != null;
            }
        }

        /**
         * Reconnects when the connection was lost or never made; called by the watch's thread.
         */
        void ensure() {
            PulseServer stale = null;
            synchronized (lock) {
                if (closed || (server != null && server.ready())) {
                    return;
                }
                stale = server;
                server = null;
            }
            if (stale != null) {
                stale.close();
            }
            var fresh = PulseServer.connect();
            if (fresh != null && !fresh.subscribe(listener)) {
                fresh.close();
                fresh = null;
            }
            synchronized (lock) {
                if (closed) {
                    if (fresh != null) {
                        fresh.close();
                    }
                    return;
                }
                server = fresh;
            }
        }

        @Override
        public void close() {
            PulseServer current;
            synchronized (lock) {
                closed = true;
                current = server;
                server = null;
            }
            if (current != null) {
                current.close();
            }
        }
    }
}
