package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.capture.audio.AudioDevice;
import com.github.auties00.smalt.capture.audio.AudioPlaybackSession;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements {@link AudioPlaybackSession} over an {@link AudioRing} that a backend's
 * {@link DeviceEngine} drains.
 *
 * <p>The engine calls {@link #render(float[], int)} whenever the device wants audio and, after
 * handing it over, {@link #reportDeviceDelay(long)} with how long a frame handed over next would
 * wait before it is heard. {@link #delayMicros()} adds the session's queue to that report, aged
 * by the time elapsed since it was made.
 */
public final class RingPlaybackSession implements AudioPlaybackSession {
    /**
     * The device played on.
     */
    private final AudioDevice.Output device;

    /**
     * The stream geometry.
     */
    private final StreamPlan plan;

    /**
     * The speaker layout of the expected audio.
     */
    private final ChannelLayout layout;

    /**
     * The queue between the writer and the engine.
     */
    private final AudioRing ring;

    /**
     * Whether {@link #close()} was called.
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The interleaved scratch of the writes, grown on demand.
     */
    private float[] scratch;

    /**
     * The engine, once attached.
     */
    private volatile DeviceEngine engine;

    /**
     * The device buffer latency the operating system granted, in nanoseconds.
     */
    private volatile long latencyNanos;

    /**
     * Whether a write happened, after which silence counts as an underrun.
     */
    private volatile boolean started;

    /**
     * The frames of silence played after the first write; written by the engine only.
     */
    private volatile long underruns;

    /**
     * The engine's newest delay report, or {@code null} before the first.
     */
    private volatile DelayReport report;

    /**
     * Creates a session whose engine is attached later.
     *
     * @param device the device played on
     * @param plan the stream geometry
     */
    public RingPlaybackSession(AudioDevice.Output device, StreamPlan plan) {
        this.device = Objects.requireNonNull(device, "device");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.layout = ChannelLayout.defaultFor(plan.channels());
        this.ring = new AudioRing(plan.channels(), plan.bufferFrames());
        this.scratch = new float[plan.periodFrames() * plan.channels()];
        this.latencyNanos = plan.latencyNanos();
    }

    /**
     * Attaches the engine that {@link #close()} stops; if the session was already closed, stops
     * it at once.
     *
     * @param grantedNanos the device buffer latency the operating system granted, in nanoseconds
     */
    public void attach(DeviceEngine engine, long grantedNanos) {
        this.latencyNanos = grantedNanos;
        this.engine = engine;
        if (closed.get()) {
            engine.stop();
        }
    }

    /**
     * Returns the stream geometry.
     *
     * @return the plan
     */
    public StreamPlan plan() {
        return plan;
    }

    /**
     * Takes queued audio for the device; called by the engine only.
     *
     * <p>The frames the queue cannot supply are filled with silence.
     *
     * @param target receives {@code frames} interleaved frames in the session's format
     * @param frames the number of frames the device wants
     * @return the number of frames of queued audio, the rest being silence
     */
    public int render(float[] target, int frames) {
        var count = ring.poll(target, 0, frames);
        if (count < frames) {
            Arrays.fill(target, count * plan.channels(), frames * plan.channels(), 0.0f);
            if (started) {
                underruns += frames - count;
            }
        }
        return count;
    }

    /**
     * Records how long a frame handed to the device next would wait before it is heard; called
     * by the engine only.
     *
     * @param delayNanos the delay in nanoseconds, measured now
     */
    public void reportDeviceDelay(long delayNanos) {
        report = new DelayReport(Math.max(0, delayNanos), System.nanoTime());
    }

    /**
     * Reports that the device failed; the writer receives the failure once.
     *
     * @param exception the failure
     */
    public void fail(MediaException exception) {
        ring.fail(exception);
    }

    /**
     * Returns whether the engine should stop: the session was closed or the device failed.
     *
     * @return whether the engine should stop
     */
    public boolean stopped() {
        return ring.stopped();
    }

    @Override
    public AudioDevice.Output device() {
        return device;
    }

    @Override
    public int sampleRate() {
        return plan.sampleRate();
    }

    @Override
    public int channels() {
        return plan.channels();
    }

    @Override
    public ChannelLayout layout() {
        return layout;
    }

    @Override
    public int periodFrames() {
        return plan.periodFrames();
    }

    @Override
    public int bufferFrames() {
        return plan.bufferFrames();
    }

    @Override
    public long latencyMicros() {
        return latencyNanos / 1_000;
    }

    @Override
    public int write(AudioBuffer buffer) throws InterruptedException {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.sampleRate() != plan.sampleRate() || buffer.channels() != plan.channels()) {
            throw new IllegalArgumentException("expected " + plan.sampleRate() + " Hz " + plan.channels() + "-channel audio, got "
                    + buffer.sampleRate() + " Hz " + buffer.channels() + "-channel audio");
        }
        var frames = buffer.frames();
        var channels = plan.channels();
        var interleaved = scratch(frames);
        for (var c = 0; c < channels; c++) {
            var plane = buffer.plane(c);
            for (var i = 0; i < frames; i++) {
                interleaved[i * channels + c] = plane[i];
            }
        }
        started = true;
        return ring.write(interleaved, 0, frames);
    }

    @Override
    public int write(float[] samples, int offset, int frames) throws InterruptedException {
        checkRange(samples.length, offset, frames);
        started = true;
        return ring.write(samples, offset, frames);
    }

    @Override
    public int write(short[] samples, int offset, int frames) throws InterruptedException {
        checkRange(samples.length, offset, frames);
        var total = frames * plan.channels();
        var interleaved = scratch(frames);
        for (var i = 0; i < total; i++) {
            interleaved[i] = SampleCodec.toFloat(samples[offset + i]);
        }
        started = true;
        return ring.write(interleaved, 0, frames);
    }

    @Override
    public int queued() {
        return ring.size();
    }

    @Override
    public int available() {
        return plan.bufferFrames() - ring.size();
    }

    @Override
    public long delayMicros() {
        return (plan.nanos(ring.size()) + deviceDelayNanos()) / 1_000;
    }

    @Override
    public long underruns() {
        return underruns;
    }

    @Override
    public void drain() throws InterruptedException {
        ring.awaitEmpty();
        var remaining = deviceDelayNanos();
        var deadline = System.nanoTime() + remaining;
        while (!ring.stopped()) {
            var left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            Thread.sleep(Math.max(1, Math.min(left / 1_000_000, 10)));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ring.close();
        var current = engine;
        if (current != null) {
            current.stop();
        }
    }

    /**
     * Returns how long a frame handed to the device now would wait, from the newest report.
     *
     * @return the delay in nanoseconds
     */
    private long deviceDelayNanos() {
        var current = report;
        if (current == null) {
            return latencyNanos;
        }
        return Math.max(0, current.delayNanos() - (System.nanoTime() - current.atNanos()));
    }

    /**
     * Returns the scratch array, grown to hold a number of frames.
     *
     * @param frames the number of frames
     * @return the scratch array
     */
    private float[] scratch(int frames) {
        var needed = frames * plan.channels();
        if (scratch.length < needed) {
            scratch = new float[needed];
        }
        return scratch;
    }

    /**
     * Checks that a run of frames fits in an array.
     *
     * @param length the array's length
     * @param offset the index of the first sample
     * @param frames the number of frames
     * @throws IndexOutOfBoundsException if the frames do not fit or {@code frames} is negative
     */
    private void checkRange(int length, int offset, int frames) {
        if (frames < 0) {
            throw new IndexOutOfBoundsException("negative frame count " + frames);
        }
        Objects.checkFromIndexSize(offset, (long) frames * plan.channels() > Integer.MAX_VALUE ? -1 : frames * plan.channels(), length);
    }

    /**
     * Records a delay report of the engine.
     *
     * @param delayNanos the delay of a frame handed to the device at the time of the report
     * @param atNanos the {@link System#nanoTime()} of the report
     */
    private record DelayReport(long delayNanos, long atNanos) {
    }
}
