package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceEvent;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.Native;
import com.github.auties00.smalt.capture.internal.audio.AudioBackend;
import com.github.auties00.smalt.capture.internal.audio.PollingDeviceWatch;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implements audio devices on macOS 11 and later with CoreAudio.
 *
 * <p>Devices are the audio objects of the hardware abstraction layer, listed by
 * {@link CoreAudioDevices} and identified by their UID; each session streams through its own HAL
 * output audio unit on CoreAudio's I/O thread ({@link CoreAudioStream}). Device changes are found
 * by listing the devices again whenever CoreAudio reports a change of the device list or of a
 * default device (property listeners on the system object), and every
 * {@value #WATCH_INTERVAL_SECONDS} seconds in case a notification is missed.
 *
 * <p>Not supported: the exclusive mode and loopback capture.
 *
 * @implNote This implementation has not been run on a Mac: it was written from the macOS SDK
 * headers and compiled on another platform.
 */
@SuppressWarnings("restricted")
public final class CoreAudioBackend implements AudioBackend {
    /**
     * The interval between two listings of the device watch without a notification, in seconds.
     */
    private static final int WATCH_INTERVAL_SECONDS = 5;

    /**
     * Constructs the backend; the frameworks are bound on first use.
     */
    public CoreAudioBackend() {
    }

    @Override
    public List<AudioDevice.Input> inputs() {
        return CoreAudioDevices.inputs();
    }

    @Override
    public List<AudioDevice.Output> outputs() {
        return CoreAudioDevices.outputs();
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the channels and formats of the returned device only.
     */
    @Override
    public Optional<AudioDevice.Input> defaultInput() {
        return CoreAudioDevices.input(null);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the channels and formats of the returned device only.
     */
    @Override
    public Optional<AudioDevice.Output> defaultOutput() {
        return CoreAudioDevices.output(null);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the channels and formats of the returned device only.
     */
    @Override
    public Optional<AudioDevice.Input> input(String id) {
        return CoreAudioDevices.input(id);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation reads the channels and formats of the returned device only.
     */
    @Override
    public Optional<AudioDevice.Output> output(String id) {
        return CoreAudioDevices.output(id);
    }

    @Override
    public AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        return CoreAudioStream.openCapture(device, options);
    }

    @Override
    public AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        return CoreAudioStream.openPlayback(device, options);
    }

    /**
     * Fails: CoreAudio has no loopback capture before process taps, which are not used.
     *
     * @throws MediaException.Unsupported always
     */
    @Override
    public AudioCaptureSession openLoopback(AudioDevice.Output device, AudioStreamOptions options) {
        // TODO: loopback through a CoreAudio process tap (macOS 14.2)
        throw new MediaException.Unsupported("loopback capture", "loopback capture is not supported on macOS");
    }

    @Override
    public DeviceWatch watch(Consumer<AudioDeviceEvent> sink) {
        var polling = new PollingDeviceWatch(this::inputs, this::outputs, sink, Duration.ofSeconds(WATCH_INTERVAL_SECONDS));
        return new ListenerWatch(polling);
    }

    /**
     * Pokes a polling watch from CoreAudio's change notifications.
     */
    private static final class ListenerWatch implements DeviceWatch {
        /**
         * The upcall stub of the listener.
         */
        private static final MemorySegment LISTENER = stub();

        /**
         * The registry resolving listener keys to watches.
         */
        private static final CallbackRegistry REGISTRY = new CallbackRegistry();

        /**
         * The properties listened to: the device list and both defaults.
         */
        private static final int[] SELECTORS = {CoreAudio.DEVICES, CoreAudio.DEFAULT_INPUT_DEVICE, CoreAudio.DEFAULT_OUTPUT_DEVICE};

        /**
         * The watch that lists and compares.
         */
        private final PollingDeviceWatch polling;

        /**
         * The memory of the property addresses.
         */
        private final Arena arena = Arena.ofShared();

        /**
         * The property addresses, parallel to {@link #SELECTORS}; {@code NULL} where registration
         * failed.
         */
        private final MemorySegment[] addresses = new MemorySegment[SELECTORS.length];

        /**
         * The listener key of this watch.
         */
        private final long key;

        /**
         * Registers the listeners.
         *
         * @param polling the watch to poke
         */
        ListenerWatch(PollingDeviceWatch polling) {
            this.polling = polling;
            this.key = REGISTRY.register(this);
            for (var i = 0; i < SELECTORS.length; i++) {
                var address = CoreAudio.address(arena, SELECTORS[i], CoreAudio.SCOPE_GLOBAL);
                addresses[i] = CoreAudio.addListener(CoreAudio.SYSTEM_OBJECT, address, LISTENER, MemorySegment.ofAddress(key)) == 0
                        ? address : MemorySegment.NULL;
            }
        }

        @Override
        public void close() {
            REGISTRY.unregister(key);
            for (var address : addresses) {
                if (!ObjC.isNull(address)) {
                    CoreAudio.removeListener(CoreAudio.SYSTEM_OBJECT, address, LISTENER, MemorySegment.ofAddress(key));
                }
            }
            polling.close();
            arena.close();
        }

        /**
         * Implements the property listener.
         *
         * @param object the system object
         * @param count the number of changed properties
         * @param changed the changed properties
         * @param client the watch's key
         * @return {@code noErr}
         */
        private static int onChanged(int object, int count, MemorySegment changed, MemorySegment client) {
            try {
                var watch = REGISTRY.lookup(client.address(), ListenerWatch.class);
                if (watch != null) {
                    watch.polling.poke();
                }
            } catch (Throwable ignored) {
                // an upcall must never throw into CoreAudio
            }
            return 0;
        }

        /**
         * Creates the upcall stub of the listener.
         *
         * @return the stub, in process-lifetime memory
         */
        private static MemorySegment stub() {
            try {
                var handle = MethodHandles.lookup().findStatic(ListenerWatch.class, "onChanged",
                        MethodType.methodType(int.class, int.class, int.class, MemorySegment.class, MemorySegment.class));
                return Native.LINKER.upcallStub(handle, CoreAudio.PROPERTY_LISTENER, Arena.global());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
