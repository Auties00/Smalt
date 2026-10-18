package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;

import java.util.List;
import java.util.function.Consumer;

/**
 * Serves platforms without an audio backend: it lists no device, opens none and reports no
 * change.
 *
 * @param platform the name of the running operating system, reported in failures
 */
public record UnsupportedAudioBackend(String platform) implements AudioBackend {
    /**
     * Returns no input.
     *
     * @return an empty list
     */
    @Override
    public List<AudioDevice.Input> inputs() {
        return List.of();
    }

    /**
     * Returns no output.
     *
     * @return an empty list
     */
    @Override
    public List<AudioDevice.Output> outputs() {
        return List.of();
    }

    /**
     * Fails: audio devices are not available.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        throw unsupported();
    }

    /**
     * Fails: audio devices are not available.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        throw unsupported();
    }

    /**
     * Fails: audio devices are not available.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public AudioCaptureSession openLoopback(AudioDevice.Output device, AudioStreamOptions options) {
        throw unsupported();
    }

    /**
     * Returns a watch that reports nothing.
     *
     * @param sink ignored
     * @return a watch whose close does nothing
     */
    @Override
    public DeviceWatch watch(Consumer<AudioDeviceEvent> sink) {
        return () -> {
        };
    }

    /**
     * Returns the failure raised by every open.
     *
     * @return the failure
     */
    private MediaException.Unsupported unsupported() {
        return new MediaException.Unsupported("audio devices", "audio devices are not available on " + platform);
    }
}
