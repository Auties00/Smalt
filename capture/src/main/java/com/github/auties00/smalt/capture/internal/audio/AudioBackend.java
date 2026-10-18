package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.Platform;
import com.github.auties00.smalt.capture.internal.linux.AlsaAudioBackend;
import com.github.auties00.smalt.capture.internal.macos.CoreAudioBackend;
import com.github.auties00.smalt.capture.internal.windows.WasapiAudioBackend;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implements audio device access for one operating system through its native APIs, called with
 * the Foreign Function and Memory API.
 *
 * <p>Implementations must not load any library that is not part of the operating system, must
 * return empty lists rather than fail when the platform has no device of a direction, must list
 * the default device of each direction first, and must raise {@link MediaException.Io} when a
 * device cannot be opened or fails and {@link MediaException.Unsupported} when a requested share
 * mode or format cannot be honored. The arguments are checked by the public API before they reach
 * a backend.
 */
public interface AudioBackend {
    /**
     * Lists the active input devices with their formats.
     *
     * @return the devices, the default one first
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    List<AudioDevice.Input> inputs();

    /**
     * Lists the active output devices with their formats.
     *
     * @return the devices, the default one first
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    List<AudioDevice.Output> outputs();

    /**
     * Returns the default input device.
     *
     * @implSpec The default implementation searches {@link #inputs()}.
     *
     * @return the device, or empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    default Optional<AudioDevice.Input> defaultInput() {
        return inputs().stream().filter(AudioDevice::defaultDevice).findFirst();
    }

    /**
     * Returns the default output device.
     *
     * @implSpec The default implementation searches {@link #outputs()}.
     *
     * @return the device, or empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    default Optional<AudioDevice.Output> defaultOutput() {
        return outputs().stream().filter(AudioDevice::defaultDevice).findFirst();
    }

    /**
     * Returns the active input device with an identifier.
     *
     * @implSpec The default implementation searches {@link #inputs()}.
     *
     * @param id the identifier, as {@link AudioDevice#id()} returns it
     * @return the device, or empty when no active input has that identifier
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    default Optional<AudioDevice.Input> input(String id) {
        return inputs().stream().filter(device -> device.id().equals(id)).findFirst();
    }

    /**
     * Returns the active output device with an identifier.
     *
     * @implSpec The default implementation searches {@link #outputs()}.
     *
     * @param id the identifier, as {@link AudioDevice#id()} returns it
     * @return the device, or empty when no active output has that identifier
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    default Optional<AudioDevice.Output> output(String id) {
        return outputs().stream().filter(device -> device.id().equals(id)).findFirst();
    }

    /**
     * Opens an input device.
     *
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the share mode or format cannot be honored
     */
    AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options);

    /**
     * Opens an output device.
     *
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the share mode or format cannot be honored
     */
    AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options);

    /**
     * Opens the loopback of an output device.
     *
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the platform has no loopback capture
     */
    AudioCaptureSession openLoopback(AudioDevice.Output device, AudioStreamOptions options);

    /**
     * Starts reporting device changes.
     *
     * @param sink receives the events, on a thread of the backend's choosing; it must not block
     * @return the watch, whose {@link DeviceWatch#close()} stops the reports
     * @throws MediaException.Io if the platform's notifications cannot be started
     */
    DeviceWatch watch(Consumer<AudioDeviceEvent> sink);

    /**
     * Returns the backend of the running operating system, created on first use.
     *
     * @return the backend; one that finds no device and opens none on an unsupported platform
     */
    static AudioBackend current() {
        return Holder.INSTANCE;
    }

    /**
     * Stops the device change reports of {@link #watch(Consumer)}.
     */
    interface DeviceWatch extends AutoCloseable {
        /**
         * Stops the reports; no event reaches the sink once this returns. Closing twice does
         * nothing.
         */
        @Override
        void close();
    }

    /**
     * Holds the backend of the running operating system.
     */
    final class Holder {
        private static final AudioBackend INSTANCE = switch (Platform.current()) {
            case WINDOWS -> new WasapiAudioBackend();
            case MACOS -> new CoreAudioBackend();
            case LINUX -> new AlsaAudioBackend();
            case UNSUPPORTED -> new UnsupportedAudioBackend(Platform.osName());
        };

        private Holder() {
            throw new AssertionError();
        }
    }
}
