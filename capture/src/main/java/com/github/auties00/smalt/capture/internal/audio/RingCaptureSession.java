package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.capture.audio.AudioCaptureSession;
import com.github.auties00.smalt.capture.audio.AudioDevice;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements {@link AudioCaptureSession} over an {@link AudioRing} that a backend's
 * {@link DeviceEngine} fills.
 *
 * <p>The engine calls {@link #deliver(float[], int, long)} with each packet of interleaved float
 * frames in the session's format and the time the device captured its first frame, on the
 * {@link System#nanoTime()} clock. The session keeps the newest such anchor and derives the
 * capture time of any later frame from it at the session's rate, so the timestamps of the reads
 * follow the device's clock rather than the reader's scheduling.
 */
public final class RingCaptureSession implements AudioCaptureSession {
    /**
     * The captured device.
     */
    private final AudioDevice device;

    /**
     * The stream geometry.
     */
    private final StreamPlan plan;

    /**
     * The speaker layout of the delivered audio.
     */
    private final ChannelLayout layout;

    /**
     * The queue between the engine and the reader.
     */
    private final AudioRing ring;

    /**
     * The {@link System#nanoTime()} at which the session opened.
     */
    private final long openNanos;

    /**
     * Whether {@link #close()} was called.
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The buffer {@link #read()} returns.
     */
    private final AudioBuffer buffer;

    /**
     * The interleaved scratch of the reads, grown on demand.
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
     * The newest capture anchor, or {@code null} before the first packet.
     */
    private volatile Anchor anchor;

    /**
     * The capture time of the first frame of the last read, or {@link Long#MIN_VALUE}.
     */
    private volatile long lastCaptureNanos = Long.MIN_VALUE;

    /**
     * The frames dropped because the reader fell behind; written by the engine only.
     */
    private volatile long overruns;

    /**
     * Creates a session whose engine is attached later.
     *
     * @param device the captured device
     * @param plan the stream geometry
     */
    public RingCaptureSession(AudioDevice device, StreamPlan plan) {
        this.device = Objects.requireNonNull(device, "device");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.layout = ChannelLayout.defaultFor(plan.channels());
        this.ring = new AudioRing(plan.channels(), plan.bufferFrames());
        this.buffer = new AudioBuffer(layout, plan.sampleRate(), plan.periodFrames());
        this.scratch = new float[plan.periodFrames() * plan.channels()];
        this.openNanos = System.nanoTime();
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
     * Appends a packet captured by the device; called by the engine only.
     *
     * @param samples the interleaved samples, in the session's format
     * @param frames the number of frames
     * @param captureNanos the {@link System#nanoTime()} at which the device captured the first
     * frame
     */
    public void deliver(float[] samples, int frames, long captureNanos) {
        if (frames <= 0) {
            return;
        }
        anchor = new Anchor(ring.head(), captureNanos);
        var dropped = ring.append(samples, 0, frames);
        if (dropped != 0) {
            overruns += dropped;
        }
    }

    /**
     * Reports that the device failed; the reader receives the queued audio, then the failure.
     *
     * @param exception the failure
     */
    public void fail(MediaException exception) {
        ring.fail(exception);
    }

    /**
     * Reports that the device stopped delivering audio.
     */
    public void end() {
        ring.end();
    }

    /**
     * Returns whether the engine should stop: the session was closed or the device ended.
     *
     * @return whether the engine should stop
     */
    public boolean stopped() {
        return ring.stopped();
    }

    @Override
    public AudioDevice device() {
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
    public AudioBuffer read() throws InterruptedException {
        var count = readFrames(plan.periodFrames());
        if (count < 0) {
            return null;
        }
        var channels = plan.channels();
        var interleaved = scratch;
        for (var c = 0; c < channels; c++) {
            var plane = buffer.plane(c);
            for (var i = 0; i < count; i++) {
                plane[i] = interleaved[i * channels + c];
            }
        }
        buffer.setFrames(count);
        return buffer;
    }

    @Override
    public int read(float[] samples, int offset, int frames) throws InterruptedException {
        checkRange(samples.length, offset, frames);
        var count = readFrames(frames);
        if (count > 0) {
            System.arraycopy(scratch, 0, samples, offset, count * plan.channels());
        }
        return count;
    }

    @Override
    public int read(short[] samples, int offset, int frames) throws InterruptedException {
        checkRange(samples.length, offset, frames);
        var count = readFrames(frames);
        var interleaved = scratch;
        var total = Math.max(0, count) * plan.channels();
        for (var i = 0; i < total; i++) {
            samples[offset + i] = SampleCodec.toShort(interleaved[i]);
        }
        return count;
    }

    @Override
    public int available() {
        return ring.size();
    }

    @Override
    public long timestampMicros() {
        var captured = lastCaptureNanos;
        return captured == Long.MIN_VALUE ? 0 : Math.max(0, (captured - openNanos) / 1_000);
    }

    @Override
    public long delayMicros() {
        var captured = lastCaptureNanos;
        return captured == Long.MIN_VALUE ? 0 : Math.max(0, (System.nanoTime() - captured) / 1_000);
    }

    @Override
    public long overruns() {
        return overruns;
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
     * Reads frames into the scratch array and updates the capture time.
     *
     * @param frames the number of frames
     * @return the frames read, or {@code -1} at the end
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private int readFrames(int frames) throws InterruptedException {
        var needed = frames * plan.channels();
        if (scratch.length < needed) {
            scratch = new float[needed];
        }
        var count = ring.read(scratch, 0, frames);
        if (count > 0) {
            var current = anchor;
            if (current != null) {
                lastCaptureNanos = current.nanos() + plan.nanos(ring.readStart() - current.position());
            }
        }
        return count;
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
     * Pairs a stream position with the time the device captured that frame.
     *
     * @param position the frame position in the stream
     * @param nanos the capture time on the {@link System#nanoTime()} clock
     */
    private record Anchor(long position, long nanos) {
    }
}
