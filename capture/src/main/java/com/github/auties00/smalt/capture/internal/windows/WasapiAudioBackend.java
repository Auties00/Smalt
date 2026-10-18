package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.audio.AudioBackend;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implements audio devices on Windows 10 and later with WASAPI.
 *
 * <p>Devices are the active endpoints of the MMDevice API ({@code mmdevapi.dll} through
 * {@code ole32.dll}), identified by their endpoint id and listed by {@link WasapiDevices} on a
 * short-lived platform thread in the COM multithreaded apartment. Each session streams on its own
 * platform thread ({@link WasapiStream}), event-driven, in the shared mode through the audio
 * engine (with its PCM converter, and with {@code IAudioClient3}'s smaller engine periods where
 * the driver offers them) or in the exclusive mode; a loopback session captures a render
 * endpoint's mix. Device changes arrive through an {@code IMMNotificationClient}
 * ({@link WasapiNotifications}).
 */
public final class WasapiAudioBackend implements AudioBackend {
    /**
     * Creates the backend.
     */
    public WasapiAudioBackend() {
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation returns an empty list when the COM runtime or the system
     * libraries cannot be loaded.
     */
    @Override
    public List<AudioDevice.Input> inputs() {
        return Wasapi.available() ? WasapiDevices.inputs() : List.of();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation returns an empty list when the COM runtime or the system
     * libraries cannot be loaded.
     */
    @Override
    public List<AudioDevice.Output> outputs() {
        return Wasapi.available() ? WasapiDevices.outputs() : List.of();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the default endpoint only.
     */
    @Override
    public Optional<AudioDevice.Input> defaultInput() {
        return Wasapi.available() ? WasapiDevices.input(null) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the default endpoint only.
     */
    @Override
    public Optional<AudioDevice.Output> defaultOutput() {
        return Wasapi.available() ? WasapiDevices.output(null) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the matching endpoint only.
     */
    @Override
    public Optional<AudioDevice.Input> input(String id) {
        return Wasapi.available() ? WasapiDevices.input(id) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation probes the formats of the matching endpoint only.
     */
    @Override
    public Optional<AudioDevice.Output> output(String id) {
        return Wasapi.available() ? WasapiDevices.output(id) : Optional.empty();
    }

    @Override
    public AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        checkAvailable();
        return WasapiStream.openCapture(device, options);
    }

    @Override
    public AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        checkAvailable();
        return WasapiStream.openPlayback(device, options);
    }

    @Override
    public AudioCaptureSession openLoopback(AudioDevice.Output device, AudioStreamOptions options) {
        checkAvailable();
        return WasapiStream.openLoopback(device, options);
    }

    @Override
    public DeviceWatch watch(Consumer<AudioDeviceEvent> sink) {
        if (!Wasapi.available()) {
            return () -> {
            };
        }
        return WasapiNotifications.start(sink);
    }

    /**
     * Throws when WASAPI cannot be reached.
     *
     * @throws MediaException.Unsupported if the COM runtime or the system libraries cannot be
     * loaded
     */
    private static void checkAvailable() {
        if (!Wasapi.available()) {
            throw new MediaException.Unsupported("wasapi", "WASAPI is not available on this system");
        }
    }
}
