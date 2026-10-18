package com.github.auties00.smalt.capture.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.audio.AudioBackend;
import com.github.auties00.smalt.capture.internal.audio.DeviceEventHub;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lists and opens the microphones and speakers of the running computer.
 *
 * <p>Audio devices are reached through the operating system's own APIs, called with the Foreign
 * Function and Memory API, and no bundled native library: WASAPI on Windows, CoreAudio (the HAL
 * output audio unit) on macOS, ALSA on Linux, where the {@code default} device reaches PulseAudio
 * or PipeWire through their ALSA plugins. On a platform without a backend the lists are empty and
 * opening a device raises {@link MediaException.Unsupported}. This class is thread-safe.
 *
 * <p>An input is captured with {@link #openCapture(AudioDevice.Input, AudioStreamOptions)}, an
 * output played with {@link #openPlayback(AudioDevice.Output, AudioStreamOptions)}, and on
 * Windows the mix an output is playing can be captured with
 * {@link #openLoopback(AudioDevice.Output, AudioStreamOptions)}. Changes of the default devices
 * and devices plugged in or removed reach the {@link AudioDeviceListener}s added with
 * {@link #addListener(AudioDeviceListener)}.
 *
 * <p>Listing the devices probes the formats of every device, which on Windows and Linux opens each
 * one; {@link #defaultInput()}, {@link #defaultOutput()}, {@link #input(String)} and
 * {@link #output(String)} probe only the device they return.
 *
 * <p>Echo cancellation needs the delay between a far-end frame handed to the speaker and its echo
 * in the microphone signal. A playback session reports how long a frame written now waits before
 * it is heard ({@link AudioPlaybackSession#delayMicros()}), a capture session how long ago the
 * frames it just returned were recorded ({@link AudioCaptureSession#delayMicros()}); their sum is
 * the stream delay of a voice processor such as Smalt's {@code VoiceProcessor} (artifact
 * {@code smalt-audio}), which takes 10 ms frames at 16 kHz:
 * {@snippet :
 * var options = AudioStreamOptions.forVoice(); // 16 kHz mono, 10 ms periods
 * var speaker = AudioDevices.openPlayback(AudioDevices.defaultOutput().orElseThrow(), options);
 * var microphone = AudioDevices.openCapture(AudioDevices.defaultInput().orElseThrow(), options);
 *
 * // render thread, once per 10 ms frame of far-end audio
 * renderDelay = speaker.delayMicros();          // a volatile field shared with the capture thread
 * processor.processRender(farEnd, 0);
 * speaker.write(farEnd, 0, 160);
 *
 * // capture thread
 * while (microphone.read(nearEnd, 0, 160) == 160) {
 *     processor.setStreamDelayMs((int) ((renderDelay + microphone.delayMicros()) / 1000));
 *     processor.processCapture(nearEnd, 0, nearEnd, 0);
 *     encode(nearEnd);
 * }
 * }
 * Both delays include the device buffers and the latency the driver reports, so the sum is
 * within a few milliseconds of the acoustic path; the echo canceller refines it from the signals.
 */
public final class AudioDevices {
    private AudioDevices() {
        throw new AssertionError();
    }

    /**
     * Lists the input devices with their formats.
     *
     * @return the active inputs, the system's default one first; empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static List<AudioDevice.Input> inputs() {
        return AudioBackend.current().inputs();
    }

    /**
     * Lists the output devices with their formats.
     *
     * @return the active outputs, the system's default one first; empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static List<AudioDevice.Output> outputs() {
        return AudioBackend.current().outputs();
    }

    /**
     * Returns the system's default input device.
     *
     * @return the device, or empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static Optional<AudioDevice.Input> defaultInput() {
        return AudioBackend.current().defaultInput();
    }

    /**
     * Returns the system's default output device.
     *
     * @return the device, or empty when there is none
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static Optional<AudioDevice.Output> defaultOutput() {
        return AudioBackend.current().defaultOutput();
    }

    /**
     * Returns the active input device with an identifier.
     *
     * @param id the identifier, as returned by {@link AudioDevice#id()}
     * @return the device, or empty when no active input has that identifier
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static Optional<AudioDevice.Input> input(String id) {
        Objects.requireNonNull(id, "id");
        return AudioBackend.current().input(id);
    }

    /**
     * Returns the active output device with an identifier.
     *
     * @param id the identifier, as returned by {@link AudioDevice#id()}
     * @return the device, or empty when no active output has that identifier
     * @throws MediaException.Io if the platform's device enumeration fails
     */
    public static Optional<AudioDevice.Output> output(String id) {
        Objects.requireNonNull(id, "id");
        return AudioBackend.current().output(id);
    }

    /**
     * Opens an input device with the default options.
     *
     * @param device the input device, as listed by {@link #inputs()}
     * @return the session, which the caller closes
     * @throws MediaException.Io if the device cannot be opened, for example because it
     * was removed
     * @throws MediaException.Unsupported if audio capture is unavailable on this platform
     */
    public static AudioCaptureSession openCapture(AudioDevice.Input device) {
        return openCapture(device, AudioStreamOptions.defaults());
    }

    /**
     * Opens an input device.
     *
     * @param device the input device, as listed by {@link #inputs()}
     * @param options the stream options
     * @return the session, which the caller closes
     * @throws MediaException.Io if the device cannot be opened, for example because it
     * was removed or another application holds it exclusively
     * @throws MediaException.Unsupported if audio capture is unavailable on this platform, or the
     * device cannot run in the requested share mode and format
     */
    public static AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        return AudioBackend.current().openCapture(Objects.requireNonNull(device, "device"), Objects.requireNonNull(options, "options"));
    }

    /**
     * Opens an output device with the default options.
     *
     * @param device the output device, as listed by {@link #outputs()}
     * @return the session, which the caller closes
     * @throws MediaException.Io if the device cannot be opened, for example because it
     * was removed
     * @throws MediaException.Unsupported if audio playback is unavailable on this platform
     */
    public static AudioPlaybackSession openPlayback(AudioDevice.Output device) {
        return openPlayback(device, AudioStreamOptions.defaults());
    }

    /**
     * Opens an output device.
     *
     * @param device the output device, as listed by {@link #outputs()}
     * @param options the stream options
     * @return the session, which the caller closes
     * @throws MediaException.Io if the device cannot be opened, for example because it
     * was removed or another application holds it exclusively
     * @throws MediaException.Unsupported if audio playback is unavailable on this platform, or the
     * device cannot run in the requested share mode and format
     */
    public static AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        return AudioBackend.current().openPlayback(Objects.requireNonNull(device, "device"), Objects.requireNonNull(options, "options"));
    }

    /**
     * Opens the loopback of an output device with the default options.
     *
     * <p>The loopback delivers audio only while something plays on the device; while the device is silent the
     * session's reads wait.
     *
     * @param device the output device
     * @return the session, which the caller closes
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the platform has no loopback capture (macOS and Linux)
     * @see #openLoopback(AudioDevice.Output, AudioStreamOptions)
     */
    public static AudioCaptureSession openLoopback(AudioDevice.Output device) {
        return openLoopback(device, AudioStreamOptions.defaults());
    }

    /**
     * Opens the loopback of an output device: a capture of the mix the device is playing, from
     * every application.
     *
     * <p>The loopback delivers audio only while something plays on the device; while the device
     * is silent the session's reads wait. The share mode of {@code options} must be
     * {@link AudioShareMode#SHARED}.
     *
     * @apiNote A loopback is the exact far-end reference of an echo canceller when the far-end
     * audio is mixed with other sounds the user hears, at the cost of the mixer's delay.
     *
     * @param device the output device
     * @param options the stream options
     * @return the session, which the caller closes
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the platform has no loopback capture (macOS and Linux)
     * or {@code options} asks for the exclusive mode
     */
    public static AudioCaptureSession openLoopback(AudioDevice.Output device, AudioStreamOptions options) {
        return AudioBackend.current().openLoopback(Objects.requireNonNull(device, "device"), Objects.requireNonNull(options, "options"));
    }

    /**
     * Registers a listener of device changes.
     *
     * <p>The operating system's notifications start with the first listener and stop when the last
     * one is removed. Adding a listener twice delivers each event to it twice.
     *
     * @param listener the listener, called on the dispatch thread
     * @throws MediaException.Io if the platform's notifications cannot be started
     */
    public static void addListener(AudioDeviceListener listener) {
        DeviceEventHub.instance().add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unregisters a listener of device changes; an unknown listener is ignored.
     *
     * @param listener the listener to remove
     */
    public static void removeListener(AudioDeviceListener listener) {
        DeviceEventHub.instance().remove(Objects.requireNonNull(listener, "listener"));
    }
}
