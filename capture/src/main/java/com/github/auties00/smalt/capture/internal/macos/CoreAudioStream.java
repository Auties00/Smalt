package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import com.github.auties00.smalt.capture.audio.AudioShareMode;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.Native;
import com.github.auties00.smalt.capture.internal.audio.DeviceEngine;
import com.github.auties00.smalt.capture.internal.audio.RingCaptureSession;
import com.github.auties00.smalt.capture.internal.audio.RingPlaybackSession;
import com.github.auties00.smalt.capture.internal.audio.SampleCodec;
import com.github.auties00.smalt.capture.internal.audio.RateConverter;
import com.github.auties00.smalt.capture.internal.audio.StreamPlan;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Streams one CoreAudio device through a HAL output audio unit ({@code kAudioUnitSubType_HALOutput}).
 *
 * <p>A capture stream enables the unit's input element, binds it to the device and receives
 * {@code kAudioOutputUnitProperty_SetInputCallback} notifications on CoreAudio's real-time I/O
 * thread, where it pulls the captured audio with {@code AudioUnitRender} as packed 32-bit floats
 * at the caller's channel count. The unit converts the channels but not the rate on its input
 * side, so a stream whose rate differs from the device's nominal rate converts it with a
 * {@link RateConverter}. A playback stream sets the caller's format on the output element's
 * input scope, which the unit converts entirely, and fills each {@code AURenderCallback} from the
 * session. The device's I/O buffer is set to the period. Callbacks reach the stream through a
 * key in a {@link CallbackRegistry} passed as the callback's reference constant, so a late
 * callback after {@link #stop()} finds nothing.
 *
 * <p>Captured audio is stamped with the host time CoreAudio gives the callback, less the device's
 * latency and safety offset and, when the rate is converted, the converter's look-ahead; a playback
 * stream reports the host time at which its buffer will be output, plus the same device latency.
 * A {@code kAudioDevicePropertyDeviceIsAlive} listener fails the session when the device
 * disappears.
 *
 * @implNote This implementation has not been run on a Mac: it was written from the macOS SDK
 * headers and compiled on another platform.
 */
@SuppressWarnings("restricted")
final class CoreAudioStream implements DeviceEngine {
    /**
     * The registry resolving callback keys to streams.
     */
    private static final CallbackRegistry REGISTRY = new CallbackRegistry();

    /**
     * The upcall stub of the input callback.
     */
    private static final MemorySegment INPUT_CALLBACK = stub("onInput", MethodType.methodType(int.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class, int.class, int.class, MemorySegment.class), true);

    /**
     * The upcall stub of the render callback.
     */
    private static final MemorySegment RENDER_CALLBACK = stub("onRender", MethodType.methodType(int.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class, int.class, int.class, MemorySegment.class), true);

    /**
     * The upcall stub of the device-alive listener.
     */
    private static final MemorySegment ALIVE_LISTENER = stub("onAliveChanged", MethodType.methodType(int.class,
            int.class, int.class, MemorySegment.class, MemorySegment.class), false);

    /**
     * The smallest number of frames the native buffer of a capture stream holds.
     */
    private static final int MIN_CAPACITY = 4_096;

    /**
     * Whether the stream plays rather than captures.
     */
    private final boolean playback;

    /**
     * The device.
     */
    private final AudioDevice device;

    /**
     * The device's audio object.
     */
    private final int object;

    /**
     * The memory shared with the I/O thread.
     */
    private final Arena arena = Arena.ofShared();

    /**
     * Whether {@link #stop()} was called.
     */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /**
     * The callback key of this stream.
     */
    private final long key;

    /**
     * The audio unit.
     */
    private MemorySegment unit = MemorySegment.NULL;

    /**
     * The address of the device-alive property, while the listener is registered.
     */
    private MemorySegment aliveAddress = MemorySegment.NULL;

    /**
     * The stream geometry.
     */
    private StreamPlan plan;

    /**
     * The capture session.
     */
    private RingCaptureSession captureSession;

    /**
     * The playback session.
     */
    private RingPlaybackSession playbackSession;

    /**
     * The rate converter of a capture stream.
     */
    private RateConverter converter;

    /**
     * The native buffer of a capture stream.
     */
    private MemorySegment nativeBuffer;

    /**
     * The {@code AudioBufferList} of a capture stream.
     */
    private MemorySegment bufferList;

    /**
     * The frames {@link #nativeBuffer} holds.
     */
    private int capacity;

    /**
     * The interleaved scratch of the I/O thread.
     */
    private float[] samples = new float[0];

    /**
     * The device's latency plus its safety offset, in nanoseconds.
     */
    private long deviceLatencyNanos;

    /**
     * Creates a stream.
     *
     * @param playback whether the stream plays
     * @param object the device's audio object
     */
    private CoreAudioStream(boolean playback, AudioDevice device, int object) {
        this.playback = playback;
        this.device = device;
        this.object = object;
        this.key = REGISTRY.register(this);
    }

    /**
     * Opens an input device.
     *
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the options ask for the exclusive mode
     */
    static AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        var stream = new CoreAudioStream(false, device, resolve(device, options));
        try {
            stream.openInput(options);
        } catch (RuntimeException | Error failure) {
            stream.stop();
            throw failure;
        }
        return stream.captureSession;
    }

    /**
     * Opens an output device.
     *
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the device cannot be opened
     * @throws MediaException.Unsupported if the options ask for the exclusive mode
     */
    static AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        var stream = new CoreAudioStream(true, device, resolve(device, options));
        try {
            stream.openOutput(options);
        } catch (RuntimeException | Error failure) {
            stream.stop();
            throw failure;
        }
        return stream.playbackSession;
    }

    /**
     * Resolves the device's audio object and checks the options.
     *
     * @param options the stream options
     * @return the {@code AudioObjectID}
     * @throws MediaException.Io if no device has the UID
     * @throws MediaException.Unsupported if the options ask for the exclusive mode
     */
    private static int resolve(AudioDevice device, AudioStreamOptions options) {
        if (options.shareMode() == AudioShareMode.EXCLUSIVE) {
            // TODO: exclusive streams through kAudioDevicePropertyHogMode
            throw new MediaException.Unsupported("exclusive mode", "exclusive audio streams are not supported on macOS");
        }
        var object = CoreAudioDevices.resolve(device.id());
        if (object == 0) {
            var message = "cannot open the audio device " + device.name() + ": no such device";
            throw new MediaException.Io(message, new IOException(message));
        }
        return object;
    }

    /**
     * Configures and starts a capture unit.
     *
     * @param options the stream options
     */
    private void openInput(AudioStreamOptions options) {
        var deviceRate = (int) Math.round(CoreAudio.doubleProperty(object, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, 48_000));
        var deviceChannels = Math.max(1, CoreAudioDevices.channels(object, CoreAudio.SCOPE_INPUT));
        plan = StreamPlan.resolve(options, deviceRate, deviceChannels);
        unit = CoreAudio.newHalOutputUnit(arena);
        CoreAudio.check(CoreAudio.setUnitInt(unit, CoreAudio.UNIT_ENABLE_IO, CoreAudio.UNIT_SCOPE_INPUT, CoreAudio.INPUT_ELEMENT, 1, arena),
                "AudioUnitSetProperty(EnableIO input)");
        CoreAudio.check(CoreAudio.setUnitInt(unit, CoreAudio.UNIT_ENABLE_IO, CoreAudio.UNIT_SCOPE_OUTPUT, CoreAudio.OUTPUT_ELEMENT, 0, arena),
                "AudioUnitSetProperty(EnableIO output)");
        bind(deviceRate);
        CoreAudio.check(CoreAudio.setUnitProperty(unit, CoreAudio.UNIT_STREAM_FORMAT, CoreAudio.UNIT_SCOPE_OUTPUT, CoreAudio.INPUT_ELEMENT,
                CoreAudio.floatFormat(arena, deviceRate, plan.channels())), "AudioUnitSetProperty(StreamFormat)");
        var maximum = arena.allocate(JAVA_INT);
        capacity = MIN_CAPACITY;
        if (CoreAudio.unitProperty(unit, CoreAudio.UNIT_MAXIMUM_FRAMES_PER_SLICE, CoreAudio.UNIT_SCOPE_GLOBAL, 0, maximum, arena) == 0) {
            capacity = Math.max(capacity, maximum.get(JAVA_INT, 0));
        }
        nativeBuffer = arena.allocate((long) capacity * 4 * plan.channels(), 16);
        bufferList = arena.allocate(CoreAudio.BUFFER_LIST_SIZE, 8);
        CoreAudio.check(CoreAudio.setUnitProperty(unit, CoreAudio.UNIT_SET_INPUT_CALLBACK, CoreAudio.UNIT_SCOPE_GLOBAL, 0, callback(INPUT_CALLBACK)),
                "AudioUnitSetProperty(SetInputCallback)");
        CoreAudio.check(CoreAudio.initialize(unit), "AudioUnitInitialize");
        converter = new RateConverter(deviceRate, plan.sampleRate(), plan.channels());
        deviceLatencyNanos = deviceLatency(CoreAudio.SCOPE_INPUT, deviceRate);
        var session = new RingCaptureSession(device, plan);
        session.attach(this, granted(deviceRate));
        captureSession = session;
        watchAlive();
        CoreAudio.check(CoreAudio.start(unit), "AudioOutputUnitStart");
    }

    /**
     * Configures and starts a playback unit.
     *
     * @param options the stream options
     */
    private void openOutput(AudioStreamOptions options) {
        var deviceRate = (int) Math.round(CoreAudio.doubleProperty(object, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, 48_000));
        var deviceChannels = Math.max(1, CoreAudioDevices.channels(object, CoreAudio.SCOPE_OUTPUT));
        plan = StreamPlan.resolve(options, deviceRate, deviceChannels);
        unit = CoreAudio.newHalOutputUnit(arena);
        bind(deviceRate);
        CoreAudio.check(CoreAudio.setUnitProperty(unit, CoreAudio.UNIT_STREAM_FORMAT, CoreAudio.UNIT_SCOPE_INPUT, CoreAudio.OUTPUT_ELEMENT,
                CoreAudio.floatFormat(arena, plan.sampleRate(), plan.channels())), "AudioUnitSetProperty(StreamFormat)");
        CoreAudio.check(CoreAudio.setUnitProperty(unit, CoreAudio.UNIT_SET_RENDER_CALLBACK, CoreAudio.UNIT_SCOPE_INPUT, CoreAudio.OUTPUT_ELEMENT,
                callback(RENDER_CALLBACK)), "AudioUnitSetProperty(SetRenderCallback)");
        CoreAudio.check(CoreAudio.initialize(unit), "AudioUnitInitialize");
        deviceLatencyNanos = deviceLatency(CoreAudio.SCOPE_OUTPUT, deviceRate);
        var session = new RingPlaybackSession((AudioDevice.Output) device, plan);
        session.attach(this, granted(deviceRate));
        playbackSession = session;
        watchAlive();
        CoreAudio.check(CoreAudio.start(unit), "AudioOutputUnitStart");
    }

    /**
     * Binds the unit to the device and asks for an I/O buffer of one period.
     *
     * @param deviceRate the device's nominal rate
     */
    private void bind(int deviceRate) {
        var id = arena.allocate(JAVA_INT);
        id.set(JAVA_INT, 0, object);
        CoreAudio.check(CoreAudio.setUnitProperty(unit, CoreAudio.UNIT_CURRENT_DEVICE, CoreAudio.UNIT_SCOPE_GLOBAL, 0, id),
                "AudioUnitSetProperty(CurrentDevice)");
        var periodFrames = (int) Math.max(1, (long) plan.periodFrames() * deviceRate / plan.sampleRate());
        CoreAudio.setIntProperty(object, CoreAudio.BUFFER_FRAME_SIZE, CoreAudio.SCOPE_GLOBAL, periodFrames);
    }

    /**
     * Builds an {@code AURenderCallbackStruct} pointing at a stub with this stream's key.
     *
     * @param stub the callback's upcall stub
     * @return the 16-byte structure
     */
    private MemorySegment callback(MemorySegment stub) {
        var callback = arena.allocate(ADDRESS, 2);
        callback.setAtIndex(ADDRESS, 0, stub);
        callback.setAtIndex(ADDRESS, 1, MemorySegment.ofAddress(key));
        return callback;
    }

    /**
     * Returns the device's latency plus its safety offset in one direction.
     *
     * @param scope the direction's scope
     * @param deviceRate the device's nominal rate
     * @return the latency in nanoseconds
     */
    private long deviceLatency(int scope, int deviceRate) {
        var frames = Math.max(0, CoreAudio.intProperty(object, CoreAudio.LATENCY, scope, 0))
                     + Math.max(0, CoreAudio.intProperty(object, CoreAudio.SAFETY_OFFSET, scope, 0));
        return frames * 1_000_000_000L / Math.max(1, deviceRate);
    }

    /**
     * Returns the latency of the device's I/O buffer as granted.
     *
     * @param deviceRate the device's nominal rate
     * @return the latency in nanoseconds
     */
    private long granted(int deviceRate) {
        var frames = CoreAudio.intProperty(object, CoreAudio.BUFFER_FRAME_SIZE, CoreAudio.SCOPE_GLOBAL, plan.periodFrames());
        return frames * 1_000_000_000L / Math.max(1, deviceRate) + deviceLatencyNanos;
    }

    /**
     * Registers the listener that fails the session when the device disappears.
     */
    private void watchAlive() {
        var address = CoreAudio.address(arena, CoreAudio.DEVICE_IS_ALIVE, CoreAudio.SCOPE_GLOBAL);
        if (CoreAudio.addListener(object, address, ALIVE_LISTENER, MemorySegment.ofAddress(key)) == 0) {
            aliveAddress = address;
        }
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        REGISTRY.unregister(key);
        if (!ObjC.isNull(aliveAddress)) {
            CoreAudio.removeListener(object, aliveAddress, ALIVE_LISTENER, MemorySegment.ofAddress(key));
            aliveAddress = MemorySegment.NULL;
        }
        if (!ObjC.isNull(unit)) {
            CoreAudio.stop(unit);
            CoreAudio.uninitialize(unit);
            CoreAudio.dispose(unit);
            unit = MemorySegment.NULL;
        }
        if (captureSession != null) {
            captureSession.end();
        }
        arena.close();
    }

    /**
     * Pulls and delivers one captured buffer; called on the I/O thread.
     *
     * @param flags the action flags of the callback
     * @param timestamp the timestamp of the callback
     * @param frames the number of frames captured
     */
    private void input(MemorySegment flags, MemorySegment timestamp, int frames) {
        var session = captureSession;
        if (session == null || frames <= 0 || frames > capacity) {
            return;
        }
        var channels = plan.channels();
        bufferList.set(JAVA_INT, 0, 1);
        bufferList.set(JAVA_INT, 8, channels);
        bufferList.set(JAVA_INT, 12, frames * 4 * channels);
        bufferList.set(ADDRESS, 16, nativeBuffer);
        if (CoreAudio.render(unit, flags, timestamp, CoreAudio.INPUT_ELEMENT, frames, bufferList) != 0) {
            return;
        }
        var count = frames * channels;
        if (samples.length < count) {
            samples = new float[count];
        }
        MemorySegment.copy(nativeBuffer, JAVA_FLOAT, 0, samples, 0, count);
        var host = timestamp.reinterpret(CoreAudio.TIMESTAMP_SIZE).get(JAVA_LONG, CoreAudio.TIMESTAMP_HOST_TIME);
        var age = Math.max(0, CoreAudio.hostNanos() - CoreAudio.hostToNanos(host));
        var captured = System.nanoTime() - age - deviceLatencyNanos;
        if (converter.identity()) {
            session.deliver(samples, frames, captured);
        } else {
            var produced = converter.convert(samples, frames);
            session.deliver(converter.output(), produced, converter.outputNanos(captured));
        }
    }

    /**
     * Fills one output buffer from the session; called on the I/O thread.
     *
     * @param timestamp the timestamp of the callback
     * @param frames the number of frames wanted
     * @param data the {@code AudioBufferList} to fill
     */
    private void output(MemorySegment timestamp, int frames, MemorySegment data) {
        var session = playbackSession;
        if (session == null || frames <= 0) {
            return;
        }
        var channels = plan.channels();
        var count = frames * channels;
        if (samples.length < count) {
            samples = new float[count];
        }
        session.render(samples, frames);
        var list = data.reinterpret(CoreAudio.BUFFER_LIST_SIZE);
        if (list.get(JAVA_INT, 0) < 1) {
            return;
        }
        var bytes = Math.min(list.get(JAVA_INT, 12), count * 4);
        var target = list.get(ADDRESS, 16).reinterpret(bytes);
        SampleCodec.encode(AudioSampleFormat.FLOAT32, samples, 0, target, bytes / 4);
        var host = timestamp.reinterpret(CoreAudio.TIMESTAMP_SIZE).get(JAVA_LONG, CoreAudio.TIMESTAMP_HOST_TIME);
        var untilOutput = CoreAudio.hostToNanos(host) - CoreAudio.hostNanos();
        session.reportDeviceDelay(untilOutput + plan.nanos(frames) + deviceLatencyNanos);
    }

    /**
     * Fails the session after the device disappeared.
     */
    private void lost() {
        var message = "the audio device " + device.name() + " was removed";
        var failure = new MediaException.Io(message, new IOException(message));
        if (captureSession != null) {
            captureSession.fail(failure);
        }
        if (playbackSession != null) {
            playbackSession.fail(failure);
        }
    }

    /**
     * Implements the input callback.
     *
     * @param reference the stream's key
     * @param flags the action flags
     * @param bus the element
     * @param frames the frames captured
     * @param data {@code NULL} for an input callback
     * @return {@code noErr}
     */
    private static int onInput(MemorySegment reference, MemorySegment flags, MemorySegment timestamp, int bus, int frames, MemorySegment data) {
        try {
            var stream = REGISTRY.lookup(reference.address(), CoreAudioStream.class);
            if (stream != null) {
                stream.input(flags, timestamp, frames);
            }
        } catch (Throwable ignored) {
            // an upcall must never throw into CoreAudio's I/O thread
        }
        return 0;
    }

    /**
     * Implements the render callback.
     *
     * @param reference the stream's key
     * @param flags the action flags
     * @param bus the element
     * @param frames the frames wanted
     * @param data the buffers to fill
     * @return {@code noErr}
     */
    private static int onRender(MemorySegment reference, MemorySegment flags, MemorySegment timestamp, int bus, int frames, MemorySegment data) {
        try {
            var stream = REGISTRY.lookup(reference.address(), CoreAudioStream.class);
            if (stream != null) {
                stream.output(timestamp, frames, data);
            }
        } catch (Throwable ignored) {
            // an upcall must never throw into CoreAudio's I/O thread
        }
        return 0;
    }

    /**
     * Implements the device-alive listener.
     *
     * @param object the device
     * @param count the number of changed properties
     * @param addresses the changed properties
     * @param client the stream's key
     * @return {@code noErr}
     */
    private static int onAliveChanged(int object, int count, MemorySegment addresses, MemorySegment client) {
        try {
            var stream = REGISTRY.lookup(client.address(), CoreAudioStream.class);
            if (stream != null && CoreAudio.intProperty(object, CoreAudio.DEVICE_IS_ALIVE, CoreAudio.SCOPE_GLOBAL, 1) == 0) {
                stream.lost();
            }
        } catch (Throwable ignored) {
            // an upcall must never throw into CoreAudio
        }
        return 0;
    }

    /**
     * Creates the upcall stub of a static method of this class.
     *
     * @param name the method name
     * @param type the method type
     * @param render whether the stub has the {@code AURenderCallback} signature rather than the
     * property listener one
     * @return the stub, in process-lifetime memory
     */
    private static MemorySegment stub(String name, MethodType type, boolean render) {
        try {
            var handle = MethodHandles.lookup().findStatic(CoreAudioStream.class, name, type);
            return Native.LINKER.upcallStub(handle, render ? CoreAudio.RENDER_CALLBACK : CoreAudio.PROPERTY_LISTENER, Arena.global());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
