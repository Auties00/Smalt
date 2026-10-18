package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.audio.AudioBackend;
import com.github.auties00.smalt.capture.internal.audio.PollingDeviceWatch;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implements audio devices on 64-bit Linux with ALSA.
 *
 * <p>ALSA ({@code libasound.so.2}) is present on virtually every desktop, and its {@code default}
 * device reaches the running sound server through the PulseAudio or PipeWire ALSA plugin, so one
 * backend serves PulseAudio, PipeWire and bare ALSA systems alike, with the timing
 * ({@code snd_pcm_delay}) an echo canceller needs. The PulseAudio simple API was not chosen: it
 * cannot list devices, and PipeWire serves it only through its PulseAudio compatibility server.
 * Devices are listed by {@link AlsaDevices}, each session streams on its own platform thread
 * ({@link AlsaStream}), and device changes are found by listing the devices every
 * {@value #WATCH_INTERVAL_SECONDS} seconds ({@link PollingDeviceWatch}).
 *
 * <p>Not supported: loopback capture (a sound server exposes it as the {@code .monitor} source of
 * a sink, which a user can select as an input where the server lists it), and notifications of a
 * new default device, since the {@code default} device follows the sound server's default by
 * itself.
 */
public final class AlsaAudioBackend implements AudioBackend {
    /**
     * The interval between two listings of the device watch, in seconds.
     */
    private static final int WATCH_INTERVAL_SECONDS = 2;

    /**
     * Constructs the backend; the library is bound on first use.
     */
    public AlsaAudioBackend() {
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation returns an empty list when {@code libasound.so.2} is missing.
     */
    @Override
    public List<AudioDevice.Input> inputs() {
        return Alsa.available() ? AlsaDevices.inputs() : List.of();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation returns an empty list when {@code libasound.so.2} is missing.
     */
    @Override
    public List<AudioDevice.Output> outputs() {
        return Alsa.available() ? AlsaDevices.outputs() : List.of();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the {@code default} device only.
     */
    @Override
    public Optional<AudioDevice.Input> defaultInput() {
        return Alsa.available() ? AlsaDevices.input(null) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the {@code default} device only.
     */
    @Override
    public Optional<AudioDevice.Output> defaultOutput() {
        return Alsa.available() ? AlsaDevices.output(null) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the matching device only.
     */
    @Override
    public Optional<AudioDevice.Input> input(String id) {
        return Alsa.available() ? AlsaDevices.input(id) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the matching device only.
     */
    @Override
    public Optional<AudioDevice.Output> output(String id) {
        return Alsa.available() ? AlsaDevices.output(id) : Optional.empty();
    }

    @Override
    public AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        checkAvailable();
        return AlsaStream.openCapture(device, options);
    }

    @Override
    public AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        checkAvailable();
        return AlsaStream.openPlayback(device, options);
    }

    /**
     * Fails: ALSA has no loopback capture.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public AudioCaptureSession openLoopback(AudioDevice.Output device, AudioStreamOptions options) {
        throw new MediaException.Unsupported("loopback capture", "ALSA has no loopback capture; capture the sound server's"
                + " monitor source as an input instead");
    }

    @Override
    public DeviceWatch watch(Consumer<AudioDeviceEvent> sink) {
        if (!Alsa.available()) {
            return () -> {
            };
        }
        return new PollingDeviceWatch(this::inputs, this::outputs, sink, Duration.ofSeconds(WATCH_INTERVAL_SECONDS));
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
}
