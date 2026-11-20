package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;
import com.github.auties00.smalt.capture.audio.AudioSampleFormat;
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

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Streams a sink or a source of the PulseAudio-protocol sound server with the blocking streams of
 * {@code libpulse-simple}, on a dedicated platform thread.
 *
 * <p>A capture records a source, which may be the monitor source of a sink: that is the loopback
 * of the sink, the mix the server plays on it. Samples travel as 32-bit floats at the session's
 * rate and channel count, converted by the server. A recording delivers one period per read
 * ({@code fragsize}), a playback keeps about the session's latency queued ({@code tlength}); the
 * server's latency report ({@code pa_simple_get_latency}) times the captured frames and the
 * playback delay, as an echo canceller needs.
 */
final class PulseStream implements DeviceEngine, Runnable {
    /**
     * How long opening may take before it fails.
     */
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(10);

    /**
     * How long a stop waits for the thread.
     */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(3);

    /**
     * The rate of a device that reports none.
     */
    private static final int DEFAULT_RATE = 48_000;

    /**
     * The channel count of a device that reports none.
     */
    private static final int DEFAULT_CHANNELS = 2;

    /**
     * Whether the stream plays.
     */
    private final boolean playback;

    /**
     * The device the session reports.
     */
    private final AudioDevice device;

    /**
     * The server's name of the sink or source streamed, {@code null} for the default.
     */
    private final String target;

    /**
     * The device's sample rate.
     */
    private final int nativeRate;

    /**
     * The device's channel count.
     */
    private final int nativeChannels;

    /**
     * The stream options.
     */
    private final AudioStreamOptions options;

    /**
     * The streaming thread.
     */
    private final PumpThread pump;

    /**
     * Whether a stop was requested.
     */
    private volatile boolean stopping;

    /**
     * The capture session, or {@code null}.
     */
    private volatile RingCaptureSession captureSession;

    /**
     * The playback session, or {@code null}.
     */
    private volatile RingPlaybackSession playbackSession;

    /**
     * The stream geometry.
     */
    private StreamPlan plan;

    /**
     * The blocking stream; owned by the thread.
     */
    private MemorySegment stream = MemorySegment.NULL;

    /**
     * Describes a stream.
     */
    private PulseStream(boolean playback, AudioDevice device, String target, int nativeRate, int nativeChannels,
                        AudioStreamOptions options) {
        this.playback = playback;
        this.device = device;
        this.target = target;
        this.nativeRate = nativeRate > 0 ? nativeRate : DEFAULT_RATE;
        this.nativeChannels = nativeChannels > 0 ? nativeChannels : DEFAULT_CHANNELS;
        this.options = options;
        this.pump = new PumpThread(playback ? "smalt-pulse-playback" : "smalt-pulse-capture", Thread.MAX_PRIORITY);
    }

    /**
     * Records a source.
     *
     * @param device the device the session reports: the input, or the output whose monitor is recorded
     * @param source the server's name of the source, or {@code null} for the default source
     * @param rate the source's sample rate, or {@code 0}
     * @param channels the source's channel count, or {@code 0}
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the server refuses the stream
     */
    static AudioCaptureSession openCapture(AudioDevice device, String source, int rate, int channels, AudioStreamOptions options) {
        return start(new PulseStream(false, device, source, rate, channels, options)).captureSession;
    }

    /**
     * Plays to a sink.
     *
     * @param device the output
     * @param sink the server's name of the sink, or {@code null} for the default sink
     * @param rate the sink's sample rate, or {@code 0}
     * @param channels the sink's channel count, or {@code 0}
     * @param options the stream options
     * @return the session
     * @throws MediaException.Io if the server refuses the stream
     */
    static AudioPlaybackSession openPlayback(AudioDevice.Output device, String sink, int rate, int channels,
                                             AudioStreamOptions options) {
        return start(new PulseStream(true, device, sink, rate, channels, options)).playbackSession;
    }

    /**
     * Starts the thread and waits until the stream is open.
     */
    private static PulseStream start(PulseStream stream) {
        stream.pump.start(stream);
        stream.pump.awaitOpen(OPEN_TIMEOUT, "the audio device " + stream.device.name(), stream::stop);
        return stream;
    }

    @Override
    public void stop() {
        stopping = true;
        pump.join(STOP_TIMEOUT);
    }

    @Override
    public void run() {
        try (var arena = Arena.ofConfined()) {
            try {
                open();
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
                if (playback) {
                    play(arena);
                } else {
                    capture(arena);
                }
            } catch (MediaException exception) {
                fail(exception);
            } catch (RuntimeException exception) {
                var message = "the audio device " + device.name() + " failed";
                fail(new MediaException.Io(message, new IOException(message, exception)));
            }
        } finally {
            if (!stream.equals(MemorySegment.NULL)) {
                Pulse.simpleFree(stream);
                stream = MemorySegment.NULL;
            }
            if (captureSession != null) {
                captureSession.end();
            }
        }
    }

    /**
     * Opens the blocking stream and creates the session.
     */
    private void open() {
        plan = StreamPlan.resolve(options, nativeRate, nativeChannels);
        var frameBytes = plan.channels() * AudioSampleFormat.FLOAT32.bytes();
        var bufferBytes = (int) Math.min(Integer.MAX_VALUE / 2, (playback
                ? StreamPlan.frames(Duration.ofNanos(plan.latencyNanos()), plan.sampleRate())
                : plan.periodFrames()) * (long) frameBytes);
        stream = Pulse.simpleNew(playback ? Pulse.STREAM_PLAYBACK : Pulse.STREAM_RECORD, target,
                playback ? "playback" : "capture", plan.sampleRate(), plan.channels(), Math.max(frameBytes, bufferBytes));
        var granted = playback ? plan.latencyNanos() : plan.nanos(plan.periodFrames());
        if (playback) {
            var session = new RingPlaybackSession((AudioDevice.Output) device, plan);
            session.attach(this, granted);
            playbackSession = session;
        } else {
            var session = new RingCaptureSession(device, plan);
            session.attach(this, granted);
            captureSession = session;
        }
    }

    /**
     * Records periods until the session stops.
     */
    private void capture(Arena arena) {
        var session = captureSession;
        var channels = plan.channels();
        var chunk = plan.periodFrames();
        var bytes = (long) chunk * channels * AudioSampleFormat.FLOAT32.bytes();
        var buffer = arena.allocate(bytes, 16);
        var samples = new float[chunk * channels];
        var error = arena.allocate(JAVA_INT);
        while (!stopping && !session.stopped()) {
            if (Pulse.read(stream, buffer, bytes, error) < 0) {
                throw Pulse.failure("the audio device " + device.name() + " failed", error.get(JAVA_INT, 0));
            }
            var now = System.nanoTime();
            var latency = Math.max(0, Pulse.latency(stream, error));
            SampleCodec.decode(AudioSampleFormat.FLOAT32, buffer, samples, 0, chunk * channels);
            session.deliver(samples, chunk, now - latency * 1_000 - plan.nanos(chunk));
        }
    }

    /**
     * Plays periods until the session stops.
     */
    private void play(Arena arena) {
        var session = playbackSession;
        var channels = plan.channels();
        var chunk = plan.periodFrames();
        var bytes = (long) chunk * channels * AudioSampleFormat.FLOAT32.bytes();
        var buffer = arena.allocate(bytes, 16);
        var samples = new float[chunk * channels];
        var error = arena.allocate(JAVA_INT);
        while (!stopping && !session.stopped()) {
            session.render(samples, chunk);
            SampleCodec.encode(AudioSampleFormat.FLOAT32, samples, 0, buffer, chunk * channels);
            if (Pulse.write(stream, buffer, bytes, error) < 0) {
                throw Pulse.failure("the audio device " + device.name() + " failed", error.get(JAVA_INT, 0));
            }
            var latency = Pulse.latency(stream, error);
            if (latency >= 0) {
                session.reportDeviceDelay(latency * 1_000);
            }
        }
    }

    /**
     * Fails the session.
     */
    private void fail(MediaException exception) {
        if (captureSession != null) {
            captureSession.fail(exception);
        }
        if (playbackSession != null) {
            playbackSession.fail(exception);
        }
    }
}
