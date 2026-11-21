package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioShareMode;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.Native;
import com.github.auties00.smalt.capture.internal.audio.DeviceEngine;
import com.github.auties00.smalt.capture.internal.audio.RateConverter;
import com.github.auties00.smalt.capture.internal.audio.RingCaptureSession;
import com.github.auties00.smalt.capture.internal.audio.StreamPlan;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Captures what an output device plays through a CoreAudio process tap, present from macOS 14.2.
 *
 * <p>The stream creates a private tap of every process's audio on the device's first output stream
 * and a private aggregate device holding only that tap, whose input is the tapped audio, and reads
 * the aggregate with an {@code AudioDeviceIOProc} on CoreAudio's real-time I/O thread. Both objects
 * exist only inside this process and for the life of the session; the operating system destroys them
 * if the process dies. The tap leaves the audio audible. The tap's clock is the device's, so the
 * callback runs only while some client keeps the device running, and delivers silence while that
 * client plays nothing.
 *
 * <p>The tap's rate and channel count are the device's. A session asking for others converts them
 * itself, the channels with {@link TapFrames} and the rate with a {@link RateConverter}. The session
 * keeps the rate it opened at when the device's changes, by converting from then on. Captured
 * audio is stamped with the host time of the input buffer, less the aggregate's latency and safety
 * offset and the converter's look-ahead.
 *
 * <p>The user grants tapping under System Audio Recording (the {@code kTCCServiceAudioCapture}
 * service), which the operating system asks for when the aggregate first starts; an application
 * bundle declares {@code NSAudioCaptureUsageDescription}. No callback runs until the user answers,
 * and until then the operating system also holds the creation and the teardown of these objects for a
 * time it bounds, so opening waits and {@link #stop()} waits only a bounded time of its own for a
 * stream that has not had a callback.
 */
@SuppressWarnings("restricted")
final class CoreAudioTap implements DeviceEngine {
    /**
     * The registry resolving callback keys to streams.
     */
    private static final CallbackRegistry REGISTRY = new CallbackRegistry();

    /**
     * The upcall stub of the I/O callback.
     */
    private static final MemorySegment IO_PROC = stub("onIo", MethodType.methodType(int.class, int.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
            CoreAudio.IO_PROC);

    /**
     * The upcall stub of the device-alive listener.
     */
    private static final MemorySegment ALIVE_LISTENER = stub("onAliveChanged", MethodType.methodType(int.class,
            int.class, int.class, MemorySegment.class, MemorySegment.class), CoreAudio.PROPERTY_LISTENER);

    /**
     * The upcall stub of the rate listener.
     */
    private static final MemorySegment RATE_LISTENER = stub("onRateChanged", MethodType.methodType(int.class,
            int.class, int.class, MemorySegment.class, MemorySegment.class), CoreAudio.PROPERTY_LISTENER);

    /**
     * The prefix of the names and identifiers of the objects a stream creates.
     */
    static final String NAME_PREFIX = "com.github.auties00.smalt.capture.tap.";

    /**
     * The offset of {@code mFlags} in {@code AudioTimeStamp}.
     */
    private static final long TIMESTAMP_FLAGS = 56;

    /**
     * {@code kAudioTimeStampHostTimeValid}.
     */
    private static final int HOST_TIME_VALID = 2;

    /**
     * The offset of {@code mFormatFlags} in {@code AudioStreamBasicDescription}.
     */
    private static final long ASBD_FORMAT_FLAGS = 12;

    /**
     * The offset of {@code mFormatID} in {@code AudioStreamBasicDescription}.
     */
    private static final long ASBD_FORMAT_ID = 8;

    /**
     * The offset of {@code mChannelsPerFrame} in {@code AudioStreamBasicDescription}.
     */
    private static final long ASBD_CHANNELS = 28;

    /**
     * The offset of {@code mBitsPerChannel} in {@code AudioStreamBasicDescription}.
     */
    private static final long ASBD_BITS = 32;

    /**
     * {@code kAudioFormatFlagIsFloat}.
     */
    private static final int FLAG_FLOAT = 0x1;

    /**
     * The longest wait of {@link #stop()} for the teardown of a stream that has not had a callback, in
     * milliseconds. A running or idle device tears down in far less; a stream waiting for the user's
     * answer takes until the operating system gives up the request.
     */
    private static final int TEARDOWN_WAIT_MILLIS = 2_000;

    /**
     * The tapped device.
     */
    private final AudioDevice device;

    /**
     * The tapped device's audio object.
     */
    private final int object;

    /**
     * The memory of the property address and the I/O callback's identifier.
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
     * Whether the I/O callback has run.
     */
    private volatile boolean called;

    /**
     * The tap's audio object, or {@code 0}.
     */
    private int tap;

    /**
     * The aggregate device's audio object, or {@code 0}.
     */
    private int aggregate;

    /**
     * The I/O callback's identifier.
     */
    private MemorySegment ioProc = MemorySegment.NULL;

    /**
     * The address of the device-alive property, while the listener is registered.
     */
    private MemorySegment aliveAddress = MemorySegment.NULL;

    /**
     * The session; set before the I/O callback starts.
     */
    private RingCaptureSession session;

    /**
     * The channel conversion of the I/O thread.
     */
    private TapFrames frames;

    /**
     * The address of the nominal rate property, while its listeners are registered.
     */
    private MemorySegment rateAddress = MemorySegment.NULL;

    /**
     * The geometry of the session.
     */
    private StreamPlan plan;

    /**
     * What the I/O thread needs of the aggregate's current rate; replaced as a whole by the rate
     * listener, so the I/O thread allocates nothing when the rate changes.
     */
    private volatile Clock clock;

    /**
     * What a block of tapped audio depends on at one rate of the aggregate.
     *
     * @param rate the aggregate's rate in hertz
     * @param converter the conversion to the session's rate, which only the I/O thread runs
     * @param latencyNanos the aggregate's latency plus its safety offset, in nanoseconds
     */
    private record Clock(int rate, RateConverter converter, long latencyNanos) {
    }

    /**
     * Creates a stream.
     *
     * @param device the tapped device
     * @param object the device's audio object
     */
    private CoreAudioTap(AudioDevice device, int object) {
        this.device = device;
        this.object = object;
        this.key = REGISTRY.register(this);
    }

    /**
     * Opens the loopback of an output device.
     *
     * @param device the output device
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if no device has the UID or the tap cannot be created
     * @throws MediaException.Unsupported if the options ask for the exclusive mode
     */
    static AudioCaptureSession open(AudioDevice.Output device, AudioStreamOptions options) {
        if (options.shareMode() != AudioShareMode.SHARED) {
            throw new MediaException.Unsupported("exclusive loopback", "a loopback capture shares its device; use AudioShareMode.SHARED");
        }
        var object = CoreAudioDevices.resolve(device.id());
        if (object == 0) {
            var message = "cannot open the audio device " + device.name() + ": no such device";
            throw new MediaException.Io(message, new IOException(message));
        }
        var stream = new CoreAudioTap(device, object);
        try {
            stream.start(options);
        } catch (RuntimeException | Error failure) {
            stream.stop();
            throw failure;
        }
        return stream.session;
    }

    /**
     * Creates the tap and the aggregate and starts the I/O callback.
     *
     * @param options the stream options
     */
    private void start(AudioStreamOptions options) {
        var name = NAME_PREFIX + UUID.randomUUID();
        var pool = ObjC.pushPool();
        try {
            createTap(name);
            createAggregate(name);
        } finally {
            ObjC.popPool(pool);
        }
        var format = arena.allocate(CoreAudio.ASBD_SIZE, 8);
        var read = CoreAudio.property(tap, CoreAudio.address(arena, CoreAudio.TAP_FORMAT, CoreAudio.SCOPE_GLOBAL), format, arena);
        if (read != CoreAudio.ASBD_SIZE) {
            throw CoreAudio.failure("cannot read the format of the tap of the audio device " + device.name(), read);
        }
        if (format.get(JAVA_INT, ASBD_FORMAT_ID) != CoreAudio.FORMAT_LINEAR_PCM
            || (format.get(JAVA_INT, ASBD_FORMAT_FLAGS) & FLAG_FLOAT) == 0
            || format.get(JAVA_INT, ASBD_BITS) != 32) {
            throw new MediaException.Unsupported("loopback capture", "the tap of the audio device " + device.name() + " does not deliver 32-bit floats");
        }
        var tapRate = (int) Math.round(format.get(JAVA_DOUBLE, 0));
        var rate = (int) Math.round(CoreAudio.doubleProperty(aggregate, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, tapRate));
        var tapChannels = Math.max(1, format.get(JAVA_INT, ASBD_CHANNELS));
        plan = StreamPlan.resolve(options, rate, tapChannels);
        var periodFrames = (int) Math.max(1, (long) plan.periodFrames() * rate / plan.sampleRate());
        CoreAudio.setIntProperty(aggregate, CoreAudio.BUFFER_FRAME_SIZE, CoreAudio.SCOPE_GLOBAL, periodFrames);
        frames = new TapFrames(tapChannels, plan.channels());
        var current = clockAt(rate);
        clock = current;
        var granted = CoreAudio.intProperty(aggregate, CoreAudio.BUFFER_FRAME_SIZE, CoreAudio.SCOPE_GLOBAL, periodFrames);
        var captureSession = new RingCaptureSession(device, plan);
        captureSession.attach(this, granted * 1_000_000_000L / Math.max(1, rate) + current.latencyNanos());
        session = captureSession;
        var address = CoreAudio.address(arena, CoreAudio.DEVICE_IS_ALIVE, CoreAudio.SCOPE_GLOBAL);
        if (CoreAudio.addListener(object, address, ALIVE_LISTENER, MemorySegment.ofAddress(key)) == 0) {
            aliveAddress = address;
        }
        var nominal = CoreAudio.address(arena, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL);
        if (CoreAudio.addListener(aggregate, nominal, RATE_LISTENER, MemorySegment.ofAddress(key)) == 0) {
            rateAddress = nominal;
        }
        var slot = arena.allocate(ADDRESS);
        CoreAudio.check(CoreAudio.createIoProc(aggregate, IO_PROC, MemorySegment.ofAddress(key), slot), "AudioDeviceCreateIOProcID");
        ioProc = slot.get(ADDRESS, 0);
        CoreAudio.check(CoreAudio.startDevice(aggregate, ioProc), "AudioDeviceStart");
    }

    /**
     * Builds the state of the I/O thread at a rate of the aggregate.
     *
     * @param rate the aggregate's rate in hertz
     * @return the state
     */
    private Clock clockAt(int rate) {
        var latency = Math.max(0, CoreAudio.intProperty(aggregate, CoreAudio.LATENCY, CoreAudio.SCOPE_INPUT, 0))
                      + Math.max(0, CoreAudio.intProperty(aggregate, CoreAudio.SAFETY_OFFSET, CoreAudio.SCOPE_INPUT, 0));
        return new Clock(rate, new RateConverter(rate, plan.sampleRate(), plan.channels()), latency * 1_000_000_000L / Math.max(1, rate));
    }

    /**
     * Follows a change of the aggregate's rate, which follows the tapped device's.
     */
    private void rateChanged() {
        var rate = (int) Math.round(CoreAudio.doubleProperty(aggregate, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, 0));
        if (rate > 0 && rate != clock.rate()) {
            clock = clockAt(rate);
        }
    }

    /**
     * Creates the private tap of every process's audio on the device's first output stream.
     *
     * @param name the tap's name
     */
    private void createTap(String name) {
        var uid = CoreFoundation.createString(device.id());
        var label = CoreFoundation.createString(name);
        var description = MemorySegment.NULL;
        try {
            // TODO: tap every output stream of a device that has several and mix them; the runners' devices have one
            var none = ObjC.id(ObjC.objcClass("NSArray"), ObjC.selector("array"));
            description = ObjC.idWithLong(ObjC.id(ObjC.objcClass("CATapDescription"), ObjC.ALLOC),
                    ObjC.selector("initExcludingProcesses:andDeviceUID:withStream:"), none, uid, 0L);
            if (ObjC.isNull(description)) {
                throw CoreAudio.failure("cannot describe a tap of the audio device " + device.name(), -1);
            }
            ObjC.call(description, ObjC.selector("setName:"), label);
            var setPrivate = ObjC.selector("setPrivate:");
            if (ObjC.bool(description, ObjC.selector("respondsToSelector:"), setPrivate)) {
                ObjC.callWithBool(description, setPrivate, true);
            }
            var slot = arena.allocate(JAVA_INT);
            CoreAudio.check(CoreAudio.createProcessTap(description, slot), "AudioHardwareCreateProcessTap");
            tap = slot.get(JAVA_INT, 0);
        } finally {
            ObjC.release(description);
            CoreFoundation.release(label);
            CoreFoundation.release(uid);
        }
    }

    /**
     * Creates the private aggregate device holding the tap.
     *
     * @param name the device's name and identifier
     */
    private void createAggregate(String name) {
        var tapUid = CoreAudio.stringProperty(tap, CoreAudio.TAP_UID);
        if (tapUid == null) {
            throw CoreAudio.failure("cannot read the identifier of the tap of the audio device " + device.name(), -1);
        }
        var yes = ObjC.idWithInt(ObjC.objcClass("NSNumber"), ObjC.selector("numberWithBool:"), 1);
        var subTap = dictionary();
        put(subTap, "uid", tapUid);
        put(subTap, "drift", yes);
        var taps = ObjC.id(ObjC.objcClass("NSArray"), ObjC.selector("arrayWithObject:"), subTap);
        var description = dictionary();
        put(description, "name", name);
        put(description, "uid", name);
        put(description, "private", yes);
        put(description, "tapautostart", yes);
        put(description, "taps", taps);
        var slot = arena.allocate(JAVA_INT);
        CoreAudio.check(CoreAudio.createAggregateDevice(description, slot), "AudioHardwareCreateAggregateDevice");
        aggregate = slot.get(JAVA_INT, 0);
    }

    /**
     * Creates an empty mutable dictionary in the current autorelease pool.
     *
     * @return the {@code NSMutableDictionary}
     */
    private static MemorySegment dictionary() {
        return ObjC.id(ObjC.objcClass("NSMutableDictionary"), ObjC.selector("dictionary"));
    }

    /**
     * Sets a string value of a dictionary.
     *
     * @param dictionary the {@code NSMutableDictionary}
     * @param key the name of the entry
     * @param value the entry
     */
    private static void put(MemorySegment dictionary, String key, String value) {
        var string = CoreFoundation.createString(value);
        try {
            put(dictionary, key, string);
        } finally {
            CoreFoundation.release(string);
        }
    }

    /**
     * Sets an object value of a dictionary, which retains it.
     *
     * @param dictionary the {@code NSMutableDictionary}
     * @param key the name of the entry
     * @param value the entry
     */
    private static void put(MemorySegment dictionary, String key, MemorySegment value) {
        var string = CoreFoundation.createString(key);
        try {
            ObjC.call(dictionary, ObjC.selector("setObject:forKey:"), value, string);
        } finally {
            CoreFoundation.release(string);
        }
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        REGISTRY.unregister(key);
        if (called || ObjC.isNull(ioProc)) {
            release();
            return;
        }
        var teardown = Thread.ofPlatform().daemon().name("smalt-tap-teardown").start(this::release);
        try {
            teardown.join(TEARDOWN_WAIT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Destroys what the stream created and ends the session; the callbacks no longer find the stream.
     */
    private void release() {
        if (!ObjC.isNull(aliveAddress)) {
            CoreAudio.removeListener(object, aliveAddress, ALIVE_LISTENER, MemorySegment.ofAddress(key));
            aliveAddress = MemorySegment.NULL;
        }
        if (!ObjC.isNull(rateAddress)) {
            CoreAudio.removeListener(aggregate, rateAddress, RATE_LISTENER, MemorySegment.ofAddress(key));
            rateAddress = MemorySegment.NULL;
        }
        if (!ObjC.isNull(ioProc)) {
            CoreAudio.stopDevice(aggregate, ioProc);
            CoreAudio.destroyIoProc(aggregate, ioProc);
            ioProc = MemorySegment.NULL;
        }
        if (aggregate != 0) {
            CoreAudio.destroyAggregateDevice(aggregate);
            aggregate = 0;
        }
        if (tap != 0) {
            CoreAudio.destroyProcessTap(tap);
            tap = 0;
        }
        if (session != null) {
            session.end();
        }
        arena.close();
    }

    /**
     * Delivers one block of tapped audio; called on the I/O thread.
     *
     * @param data the {@code AudioBufferList} of the input
     * @param time the {@code AudioTimeStamp} of the input
     */
    private void input(MemorySegment data, MemorySegment time) {
        if (!called) {
            called = true;
        }
        var captureSession = session;
        if (captureSession == null || stopped.get()) {
            return;
        }
        var count = frames.convert(data);
        if (count == 0) {
            return;
        }
        var age = 0L;
        if (time.address() != 0) {
            var stamp = time.reinterpret(CoreAudio.TIMESTAMP_SIZE);
            if ((stamp.get(JAVA_INT, TIMESTAMP_FLAGS) & HOST_TIME_VALID) != 0) {
                age = Math.max(0, CoreAudio.hostNanos() - CoreAudio.hostToNanos(stamp.get(JAVA_LONG, CoreAudio.TIMESTAMP_HOST_TIME)));
            }
        }
        var current = clock;
        var captured = System.nanoTime() - age - current.latencyNanos();
        var converter = current.converter();
        if (converter.identity()) {
            captureSession.deliver(frames.output(), count, captured);
        } else {
            var produced = converter.convert(frames.output(), count);
            captureSession.deliver(converter.output(), produced, converter.outputNanos(captured));
        }
    }

    /**
     * Fails the session after the device disappeared.
     */
    private void lost() {
        var message = "the audio device " + device.name() + " was removed";
        session.fail(new MediaException.Io(message, new IOException(message)));
    }

    /**
     * Implements the I/O callback.
     *
     * @param device the aggregate
     * @param now the current time
     * @param input the captured buffers
     * @param inputTime the time of the captured buffers
     * @param output the output buffers, which stay empty
     * @param outputTime the time of the output buffers
     * @param client the stream's key
     * @return {@code noErr}
     */
    private static int onIo(int device, MemorySegment now, MemorySegment input, MemorySegment inputTime, MemorySegment output,
                            MemorySegment outputTime, MemorySegment client) {
        try {
            var stream = REGISTRY.lookup(client.address(), CoreAudioTap.class);
            if (stream != null) {
                stream.input(input, inputTime);
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
            var stream = REGISTRY.lookup(client.address(), CoreAudioTap.class);
            if (stream != null && CoreAudio.intProperty(object, CoreAudio.DEVICE_IS_ALIVE, CoreAudio.SCOPE_GLOBAL, 1) == 0) {
                stream.lost();
            }
        } catch (Throwable ignored) {
            // an upcall must never throw into CoreAudio
        }
        return 0;
    }

    /**
     * Implements the rate listener.
     *
     * @param object the aggregate
     * @param count the number of changed properties
     * @param addresses the changed properties
     * @param client the stream's key
     * @return {@code noErr}
     */
    private static int onRateChanged(int object, int count, MemorySegment addresses, MemorySegment client) {
        try {
            var stream = REGISTRY.lookup(client.address(), CoreAudioTap.class);
            if (stream != null) {
                stream.rateChanged();
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
     * @param descriptor the native signature
     * @return the stub, in process-lifetime memory
     */
    private static MemorySegment stub(String name, MethodType type, FunctionDescriptor descriptor) {
        try {
            var handle = MethodHandles.lookup().findStatic(CoreAudioTap.class, name, type);
            return Native.LINKER.upcallStub(handle, descriptor, Arena.global());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
