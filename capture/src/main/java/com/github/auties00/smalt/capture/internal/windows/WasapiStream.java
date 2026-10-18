package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioDeviceFormat;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
import com.github.auties00.smalt.capture.audio.AudioShareMode;
import com.github.auties00.smalt.capture.audio.AudioStreamOptions;
import com.github.auties00.smalt.capture.internal.PumpThread;
import com.github.auties00.smalt.capture.internal.audio.DeviceEngine;
import com.github.auties00.smalt.capture.internal.audio.RingCaptureSession;
import com.github.auties00.smalt.capture.internal.audio.RingPlaybackSession;
import com.github.auties00.smalt.capture.internal.audio.SampleCodec;
import com.github.auties00.smalt.capture.internal.audio.StreamPlan;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Streams one WASAPI endpoint: a capture, a playback or the loopback of a render endpoint.
 *
 * <p>The stream owns a platform thread at the highest Java priority that joins the COM
 * multithreaded apartment and the multimedia class scheduler's {@code Pro Audio} task, opens the
 * endpoint, initializes an event-driven {@code IAudioClient} and then moves audio between the
 * endpoint and the session every time WASAPI signals the event, or at least every period when it
 * does not (a loopback of a silent endpoint). Every COM object is created, used and released on
 * that thread.
 *
 * <p>In the shared mode the stream asks WASAPI for 32-bit float samples at the caller's rate and
 * channel count with {@code AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM}, so the audio engine converts and
 * the samples are copied as they are. When the caller's format is the engine's mix format and
 * the period is shorter than the engine's default, the stream asks
 * {@code IAudioClient3::InitializeSharedAudioStream} for a smaller engine period and falls back
 * to the default one when the driver refuses. In the exclusive mode the stream opens the device
 * at the caller's rate and channel count in the first encoding the device accepts (float, 32-bit,
 * 24-in-32-bit, packed 24-bit, then 16-bit), with a buffer of one period realigned as WASAPI
 * requires.
 *
 * <p>Capture packets are stamped with the performance-counter time WASAPI reports for their
 * first frame. A playback stream keeps the device buffer full and, after each refill, reports to
 * the session how long the next frame will wait: the frames written minus the position
 * {@code IAudioClock} reports as playing, or the buffer padding plus the stream latency when the
 * clock is unavailable.
 */
final class WasapiStream implements DeviceEngine, Runnable {
    /**
     * The longest wait for the stream thread to open the endpoint.
     */
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The longest wait for the stream thread to stop.
     */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(3);

    /**
     * The 100-nanosecond units of a second.
     */
    private static final long HNS_PER_SECOND = 10_000_000L;

    /**
     * What the stream does.
     */
    private enum Kind {
        /**
         * Captures an input endpoint.
         */
        CAPTURE,

        /**
         * Plays on an output endpoint.
         */
        PLAYBACK,

        /**
         * Captures the mix of an output endpoint.
         */
        LOOPBACK
    }

    /**
     * What the stream does.
     */
    private final Kind kind;

    /**
     * The endpoint.
     */
    private final AudioDevice device;

    /**
     * The stream options.
     */
    private final AudioStreamOptions options;

    /**
     * Guards {@link #event} between the stream thread and {@link #stop()}.
     */
    private final Object eventLock = new Object();

    /**
     * The stream thread.
     */
    private final PumpThread pump;

    /**
     * Whether {@link #stop()} was called or the opener gave up.
     */
    private volatile boolean stopping;

    /**
     * The capture session, set before the pump is told the endpoint is open.
     */
    private volatile RingCaptureSession captureSession;

    /**
     * The playback session, set before the pump is told the endpoint is open.
     */
    private volatile RingPlaybackSession playbackSession;

    /**
     * The event WASAPI signals, or {@link MemorySegment#NULL} once closed.
     */
    private MemorySegment event = MemorySegment.NULL;

    /**
     * The device, owned by the stream thread.
     */
    private MemorySegment endpoint = MemorySegment.NULL;

    /**
     * The audio client, owned by the stream thread.
     */
    private MemorySegment client = MemorySegment.NULL;

    /**
     * The capture or render service, owned by the stream thread.
     */
    private MemorySegment service = MemorySegment.NULL;

    /**
     * The clock of a playback stream, or {@link MemorySegment#NULL}.
     */
    private MemorySegment clock = MemorySegment.NULL;

    /**
     * The encoding of the endpoint's buffers.
     */
    private AudioSampleFormat sampleFormat;

    /**
     * Whether the endpoint was opened exclusively.
     */
    private boolean exclusive;

    /**
     * The endpoint's buffer size in frames.
     */
    private int bufferFrames;

    /**
     * The stream geometry.
     */
    private StreamPlan plan;

    /**
     * The stream latency WASAPI reports, in 100-nanosecond units.
     */
    private long streamLatency;

    /**
     * The longest wait for the event, in milliseconds.
     */
    private int waitMillis;

    /**
     * Creates the state of a stream.
     *
     * @param kind what the stream does
     * @param device the endpoint
     * @param options the stream options
     */
    private WasapiStream(Kind kind, AudioDevice device, AudioStreamOptions options) {
        this.kind = kind;
        this.device = device;
        this.options = options;
        this.pump = new PumpThread(switch (kind) {
            case CAPTURE -> "smalt-audio-capture";
            case PLAYBACK -> "smalt-audio-playback";
            case LOOPBACK -> "smalt-audio-loopback";
        }, Thread.MAX_PRIORITY);
    }

    /**
     * Opens an input endpoint.
     *
     * @param device the endpoint
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the endpoint cannot be opened
     * @throws MediaException.Unsupported if the share mode or format cannot be honored
     */
    static AudioCaptureSession openCapture(AudioDevice.Input device, AudioStreamOptions options) {
        return start(new WasapiStream(Kind.CAPTURE, device, options)).captureSession;
    }

    /**
     * Opens an output endpoint.
     *
     * @param device the endpoint
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the endpoint cannot be opened
     * @throws MediaException.Unsupported if the share mode or format cannot be honored
     */
    static AudioPlaybackSession openPlayback(AudioDevice.Output device, AudioStreamOptions options) {
        return start(new WasapiStream(Kind.PLAYBACK, device, options)).playbackSession;
    }

    /**
     * Opens the loopback of an output endpoint.
     *
     * @param device the endpoint
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the endpoint cannot be opened
     * @throws MediaException.Unsupported if the options ask for the exclusive mode
     */
    static AudioCaptureSession openLoopback(AudioDevice.Output device, AudioStreamOptions options) {
        if (options.shareMode() != AudioShareMode.SHARED) {
            throw new MediaException.Unsupported("exclusive loopback", "a loopback capture shares its device; use AudioShareMode.SHARED");
        }
        return start(new WasapiStream(Kind.LOOPBACK, device, options)).captureSession;
    }

    /**
     * Starts the stream thread and waits until it opened the endpoint.
     *
     * @return the stream
     * @throws MediaException if opening fails or times out
     */
    private static WasapiStream start(WasapiStream stream) {
        stream.pump.start(stream);
        stream.pump.awaitOpen(OPEN_TIMEOUT, "the audio device " + stream.device.name(), stream::stop);
        return stream;
    }

    @Override
    public void stop() {
        stopping = true;
        synchronized (eventLock) {
            if (!event.equals(MemorySegment.NULL)) {
                Wasapi.setEvent(event);
            }
        }
        pump.join(STOP_TIMEOUT);
    }

    @Override
    public void run() {
        var initialized = false;
        var task = MemorySegment.NULL;
        try (var arena = Arena.ofConfined()) {
            try {
                initialized = Com.initialize();
                task = Wasapi.enterProAudio(arena);
                openEndpoint(arena);
            } catch (MediaException exception) {
                pump.failOpen(exception);
                return;
            } catch (RuntimeException exception) {
                var message = "cannot open the audio device " + device.name();
                pump.failOpen(new MediaException.Io(message, new IOException(message, exception)));
                return;
            } finally {
                pump.markOpen();
            }
            if (stopping) {
                return;
            }
            try {
                Wasapi.check(Com.call(client, Wasapi.CLIENT_START), "IAudioClient::Start");
                if (kind == Kind.PLAYBACK) {
                    play(arena);
                } else {
                    capture(arena);
                }
            } catch (MediaException exception) {
                fail(exception);
            } catch (RuntimeException exception) {
                var message = "the audio device " + device.name() + " failed";
                fail(new MediaException.Io(message, new IOException(message, exception)));
            } finally {
                if (!client.equals(MemorySegment.NULL)) {
                    Com.call(client, Wasapi.CLIENT_STOP);
                }
            }
        } finally {
            releaseEndpoint();
            Wasapi.leaveProAudio(task);
            if (initialized) {
                Com.uninitialize();
            }
        }
    }

    /**
     * Reports a failure of the running stream to its session.
     *
     * @param exception the failure
     */
    private void fail(MediaException exception) {
        if (captureSession != null) {
            captureSession.fail(exception);
        }
        if (playbackSession != null) {
            playbackSession.fail(exception);
        }
    }

    /**
     * Opens the endpoint, initializes the audio client and creates the session.
     *
     * @param arena the arena of the stream thread
     * @throws MediaException.Io if a WASAPI call fails
     * @throws MediaException.Unsupported if the share mode or format cannot be honored
     */
    private void openEndpoint(Arena arena) {
        endpoint = WasapiDevices.open(device.id(), device.name(), arena);
        client = Wasapi.activate(endpoint, Wasapi.IID_AUDIO_CLIENT, arena);
        var mixSlot = arena.allocate(ADDRESS);
        Wasapi.check(Com.call(client, Wasapi.CLIENT_GET_MIX_FORMAT, mixSlot), "IAudioClient::GetMixFormat");
        var mixPointer = mixSlot.get(ADDRESS, 0);
        try {
            var mix = Wasapi.parseWaveFormat(mixPointer);
            if (mix == null) {
                throw new MediaException.Unsupported("audio device format", "the audio device " + device.name()
                        + " mixes in a format that is neither linear PCM nor float");
            }
            plan = StreamPlan.resolve(options, mix.sampleRate(), mix.channels());
            var periods = arena.allocate(JAVA_LONG, 2);
            Wasapi.check(Com.call(client, Wasapi.CLIENT_GET_DEVICE_PERIOD, periods, periods.asSlice(8)), "IAudioClient::GetDevicePeriod");
            var defaultPeriod = periods.get(JAVA_LONG, 0);
            var minimumPeriod = periods.get(JAVA_LONG, 8);
            if (options.shareMode() == AudioShareMode.EXCLUSIVE) {
                initializeExclusive(arena, minimumPeriod);
            } else {
                initializeShared(arena, mix, mixPointer, defaultPeriod);
            }
        } finally {
            Com.free(mixPointer);
        }
        synchronized (eventLock) {
            event = Wasapi.createEvent();
        }
        Wasapi.check(Com.call(client, Wasapi.CLIENT_SET_EVENT_HANDLE, event), "IAudioClient::SetEventHandle");
        var size = arena.allocate(JAVA_INT);
        Wasapi.check(Com.call(client, Wasapi.CLIENT_GET_BUFFER_SIZE, size), "IAudioClient::GetBufferSize");
        bufferFrames = size.get(JAVA_INT, 0);
        var latency = arena.allocate(JAVA_LONG);
        if (Com.call(client, Wasapi.CLIENT_GET_STREAM_LATENCY, latency) >= 0) {
            streamLatency = latency.get(JAVA_LONG, 0);
        }
        var grantedNanos = plan.nanos(bufferFrames);
        var periodMillis = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(plan.nanos(plan.periodFrames())));
        waitMillis = kind == Kind.LOOPBACK ? periodMillis : Math.max(20, 4 * periodMillis);
        var slot = arena.allocate(ADDRESS);
        if (kind == Kind.PLAYBACK) {
            Wasapi.check(Com.call(client, Wasapi.CLIENT_GET_SERVICE, Wasapi.IID_AUDIO_RENDER_CLIENT, slot), "IAudioClient::GetService(IAudioRenderClient)");
            service = slot.get(ADDRESS, 0);
            if (Com.call(client, Wasapi.CLIENT_GET_SERVICE, Wasapi.IID_AUDIO_CLOCK, slot) >= 0) {
                clock = slot.get(ADDRESS, 0);
            }
            var session = new RingPlaybackSession((AudioDevice.Output) device, plan);
            session.attach(this, grantedNanos);
            playbackSession = session;
        } else {
            Wasapi.check(Com.call(client, Wasapi.CLIENT_GET_SERVICE, Wasapi.IID_AUDIO_CAPTURE_CLIENT, slot), "IAudioClient::GetService(IAudioCaptureClient)");
            service = slot.get(ADDRESS, 0);
            var session = new RingCaptureSession(device, plan);
            session.attach(this, grantedNanos);
            captureSession = session;
        }
    }

    /**
     * Initializes the audio client in the shared mode.
     *
     * @param arena the arena of the stream thread
     * @param mix the engine's mix format
     * @param mixPointer the engine's mix format structure
     * @param defaultPeriod the engine's default period in 100-nanosecond units
     */
    private void initializeShared(Arena arena, AudioDeviceFormat mix, MemorySegment mixPointer, long defaultPeriod) {
        sampleFormat = AudioSampleFormat.FLOAT32;
        var nativeFormat = plan.sampleRate() == mix.sampleRate() && plan.channels() == mix.channels();
        var period = HNS_PER_SECOND * plan.periodFrames() / plan.sampleRate();
        if (kind != Kind.LOOPBACK && nativeFormat && mix.sampleFormat() == AudioSampleFormat.FLOAT32 && period < defaultPeriod
            && initializeLowLatency(arena, mixPointer)) {
            return;
        }
        var flags = Wasapi.STREAMFLAGS_EVENTCALLBACK | Wasapi.STREAMFLAGS_NOPERSIST;
        if (!nativeFormat) {
            flags |= Wasapi.STREAMFLAGS_AUTOCONVERTPCM | Wasapi.STREAMFLAGS_SRC_DEFAULT_QUALITY;
        }
        if (kind == Kind.LOOPBACK) {
            flags |= Wasapi.STREAMFLAGS_LOOPBACK;
        }
        var buffer = Math.max(plan.latencyNanos() / 100, period);
        var format = Wasapi.waveFormat(arena, plan.sampleRate(), plan.channels(), AudioSampleFormat.FLOAT32, 32);
        Wasapi.check(Wasapi.initialize(client, Wasapi.SHAREMODE_SHARED, flags, buffer, 0, format), "IAudioClient::Initialize");
    }

    /**
     * Initializes the audio client with a shared-mode engine period shorter than the default.
     *
     * <p>When the driver refuses, the client is replaced by a fresh one so the caller can
     * initialize it the usual way.
     *
     * @param arena the arena of the stream thread
     * @param mixPointer the engine's mix format structure
     * @return whether the client was initialized
     */
    private boolean initializeLowLatency(Arena arena, MemorySegment mixPointer) {
        var client3 = Com.query(client, Wasapi.IID_AUDIO_CLIENT3, arena.allocate(ADDRESS));
        if (client3.equals(MemorySegment.NULL)) {
            return false;
        }
        try {
            var periods = arena.allocate(JAVA_INT, 4);
            var result = Wasapi.call5(client3, Wasapi.CLIENT3_GET_SHARED_MODE_ENGINE_PERIOD, mixPointer, periods,
                    periods.asSlice(4), periods.asSlice(8), periods.asSlice(12));
            if (result < 0) {
                return false;
            }
            var defaultFrames = periods.get(JAVA_INT, 0);
            var fundamental = Math.max(1, periods.get(JAVA_INT, 4));
            var minimum = periods.get(JAVA_INT, 8);
            var maximum = periods.get(JAVA_INT, 12);
            var requested = (plan.periodFrames() + fundamental - 1) / fundamental * fundamental;
            var frames = Math.clamp(requested, minimum, Math.max(minimum, maximum));
            if (frames >= defaultFrames) {
                return false;
            }
            result = Wasapi.initializeSharedAudioStream(client3, Wasapi.STREAMFLAGS_EVENTCALLBACK, frames, mixPointer);
            if (result >= 0) {
                return true;
            }
        } finally {
            Com.release(client3);
        }
        Com.release(client);
        client = MemorySegment.NULL;
        client = Wasapi.activate(endpoint, Wasapi.IID_AUDIO_CLIENT, arena);
        return false;
    }

    /**
     * Initializes the audio client in the exclusive mode.
     *
     * @param arena the arena of the stream thread
     * @param minimumPeriod the device's minimum period in 100-nanosecond units
     * @throws MediaException.Unsupported if the device refuses the exclusive mode or every
     * encoding at the caller's format
     */
    private void initializeExclusive(Arena arena, long minimumPeriod) {
        if (kind == Kind.LOOPBACK) {
            throw new MediaException.Unsupported("exclusive loopback", "a loopback capture shares its device");
        }
        MemorySegment format = null;
        for (var candidate : WasapiDevices.ExclusiveFormat.CANDIDATES) {
            var tried = candidate.waveFormat(arena, plan.sampleRate(), plan.channels());
            var result = Com.call(client, Wasapi.CLIENT_IS_FORMAT_SUPPORTED, Wasapi.SHAREMODE_EXCLUSIVE, tried, MemorySegment.NULL);
            if (result == Wasapi.E_EXCLUSIVE_MODE_NOT_ALLOWED) {
                throw new MediaException.Unsupported("exclusive mode", "the audio device " + device.name() + " does not allow exclusive mode");
            }
            if (result == 0) {
                format = tried;
                sampleFormat = candidate.sampleFormat();
                break;
            }
        }
        if (format == null) {
            throw new MediaException.Unsupported("exclusive mode", "the audio device " + device.name() + " cannot run exclusively at "
                    + plan.sampleRate() + " Hz with " + plan.channels() + " channels");
        }
        exclusive = true;
        var period = Math.max(minimumPeriod, HNS_PER_SECOND * plan.periodFrames() / plan.sampleRate());
        var flags = Wasapi.STREAMFLAGS_EVENTCALLBACK | Wasapi.STREAMFLAGS_NOPERSIST;
        var result = Wasapi.initialize(client, Wasapi.SHAREMODE_EXCLUSIVE, flags, period, period, format);
        if (result == Wasapi.E_BUFFER_SIZE_NOT_ALIGNED) {
            var size = arena.allocate(JAVA_INT);
            Wasapi.check(Com.call(client, Wasapi.CLIENT_GET_BUFFER_SIZE, size), "IAudioClient::GetBufferSize");
            Com.release(client);
            client = MemorySegment.NULL;
            client = Wasapi.activate(endpoint, Wasapi.IID_AUDIO_CLIENT, arena);
            period = (HNS_PER_SECOND * size.get(JAVA_INT, 0) + plan.sampleRate() / 2) / plan.sampleRate();
            result = Wasapi.initialize(client, Wasapi.SHAREMODE_EXCLUSIVE, flags, period, period, format);
        }
        if (result == Wasapi.E_UNSUPPORTED_FORMAT || result == Wasapi.E_EXCLUSIVE_MODE_NOT_ALLOWED) {
            throw new MediaException.Unsupported("exclusive mode", "the audio device " + device.name() + Wasapi.describe(result));
        }
        Wasapi.check(result, "IAudioClient::Initialize");
    }

    /**
     * Delivers captured packets to the session until it stops.
     *
     * @param arena the arena of the stream thread
     */
    private void capture(Arena arena) {
        var session = captureSession;
        var channels = plan.channels();
        var frameBytes = (long) channels * sampleFormat.bytes();
        var packetSize = arena.allocate(JAVA_INT);
        var data = arena.allocate(ADDRESS);
        var frames = arena.allocate(JAVA_INT);
        var flags = arena.allocate(JAVA_INT);
        var position = arena.allocate(JAVA_LONG);
        var timestamp = arena.allocate(JAVA_LONG);
        var counter = arena.allocate(JAVA_LONG);
        var samples = new float[Math.max(bufferFrames, plan.periodFrames()) * channels];
        while (!stopping && !session.stopped()) {
            Wasapi.await(event, waitMillis);
            while (!stopping) {
                Wasapi.check(Com.call(service, Wasapi.CAPTURE_GET_NEXT_PACKET_SIZE, packetSize), "IAudioCaptureClient::GetNextPacketSize");
                if (packetSize.get(JAVA_INT, 0) == 0) {
                    break;
                }
                var result = Wasapi.call5(service, Wasapi.CAPTURE_GET_BUFFER, data, frames, flags, position, timestamp);
                if (result == Wasapi.S_BUFFER_EMPTY) {
                    break;
                }
                Wasapi.check(result, "IAudioCaptureClient::GetBuffer");
                var count = frames.get(JAVA_INT, 0);
                var packetFlags = flags.get(JAVA_INT, 0);
                if (samples.length < count * channels) {
                    samples = new float[count * channels];
                }
                if ((packetFlags & Wasapi.BUFFERFLAGS_SILENT) != 0) {
                    java.util.Arrays.fill(samples, 0, count * channels, 0.0f);
                } else {
                    var buffer = data.get(ADDRESS, 0).reinterpret(count * frameBytes);
                    SampleCodec.decode(sampleFormat, buffer, samples, 0, count * channels);
                }
                Wasapi.check(Com.call(service, Wasapi.CAPTURE_RELEASE_BUFFER, count), "IAudioCaptureClient::ReleaseBuffer");
                var now = System.nanoTime();
                long captured;
                if ((packetFlags & Wasapi.BUFFERFLAGS_TIMESTAMP_ERROR) != 0) {
                    captured = now - plan.nanos(count);
                } else {
                    captured = now - Math.max(0, Wasapi.qpc100ns(counter) - timestamp.get(JAVA_LONG, 0)) * 100;
                }
                session.deliver(samples, count, captured);
            }
        }
        session.end();
    }

    /**
     * Keeps the endpoint's buffer filled from the session until it stops.
     *
     * @param arena the arena of the stream thread
     */
    private void play(Arena arena) {
        var session = playbackSession;
        var channels = plan.channels();
        var frameBytes = (long) channels * sampleFormat.bytes();
        var padding = arena.allocate(JAVA_INT);
        var data = arena.allocate(ADDRESS);
        var clockPosition = arena.allocate(JAVA_LONG);
        var clockTime = arena.allocate(JAVA_LONG);
        var counter = arena.allocate(JAVA_LONG);
        var samples = new float[bufferFrames * channels];
        var frequency = 0L;
        if (!clock.equals(MemorySegment.NULL) && Com.call(clock, Wasapi.CLOCK_GET_FREQUENCY, clockPosition) >= 0) {
            frequency = clockPosition.get(JAVA_LONG, 0);
        }
        var written = 0L;
        while (!stopping && !session.stopped()) {
            var queued = 0;
            if (!exclusive) {
                Wasapi.check(Com.call(client, Wasapi.CLIENT_GET_CURRENT_PADDING, padding), "IAudioClient::GetCurrentPadding");
                queued = padding.get(JAVA_INT, 0);
            }
            var free = bufferFrames - queued;
            if (free > 0) {
                Wasapi.check(Com.call(service, Wasapi.RENDER_GET_BUFFER, free, data), "IAudioRenderClient::GetBuffer");
                session.render(samples, free);
                SampleCodec.encode(sampleFormat, samples, 0, data.get(ADDRESS, 0).reinterpret(free * frameBytes), free * channels);
                Wasapi.check(Com.call(service, Wasapi.RENDER_RELEASE_BUFFER, free, 0), "IAudioRenderClient::ReleaseBuffer");
                written += free;
                queued += free;
            }
            var delay = -1L;
            if (frequency > 0 && Com.call(clock, Wasapi.CLOCK_GET_POSITION, clockPosition, clockTime) >= 0) {
                var played = clockPosition.get(JAVA_LONG, 0);
                var playedNanos = played / frequency * 1_000_000_000L + played % frequency * 1_000_000_000L / frequency;
                playedNanos += Math.max(0, Wasapi.qpc100ns(counter) - clockTime.get(JAVA_LONG, 0)) * 100;
                delay = plan.nanos(written) - playedNanos;
            }
            if (delay < 0 || delay > 2_000_000_000L) {
                delay = plan.nanos(queued) + streamLatency * 100;
            }
            session.reportDeviceDelay(delay);
            Wasapi.await(event, waitMillis);
        }
    }

    /**
     * Releases the COM objects and the event; called by the stream thread when it ends.
     */
    private void releaseEndpoint() {
        Com.release(clock);
        clock = MemorySegment.NULL;
        Com.release(service);
        service = MemorySegment.NULL;
        Com.release(client);
        client = MemorySegment.NULL;
        Com.release(endpoint);
        endpoint = MemorySegment.NULL;
        synchronized (eventLock) {
            Wasapi.closeHandle(event);
            event = MemorySegment.NULL;
        }
        if (captureSession != null) {
            captureSession.end();
        }
    }
}
