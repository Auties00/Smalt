package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.AudioBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Finds the silences of a stream of samples: runs of sample frames in which every channel stays
 * below a threshold for at least a minimum duration.
 *
 * <p>A sample frame is silent when the magnitude of every channel is strictly below
 * {@link SilenceDetectorOptions#thresholdDb()}; a run of silent frames at least
 * {@link SilenceDetectorOptions#minDurationMicros()} long is a {@link SilenceRange} from its first
 * silent frame to the first frame with sound. A silence still running when the stream ends is
 * reported by {@link #finish()}, ending at the end of the stream. Memory is bounded by the number
 * of silences found.
 *
 * <p>An instance is not thread-safe.
 */
public final class SilenceDetector {
    /**
     * The sampling rate in hertz.
     */
    private final int sampleRate;

    /**
     * The options.
     */
    private final SilenceDetectorOptions options;

    /**
     * The threshold as a magnitude.
     */
    private final float threshold;

    /**
     * The shortest silence in frames.
     */
    private final long minFrames;

    /**
     * The silences found.
     */
    private final List<SilenceRange> ranges;

    /**
     * The index of the first frame of the current silent run, or {@code -1}.
     */
    private long runStart = -1;

    /**
     * The number of frames processed.
     */
    private long position;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * Constructs a detector with the default options.
     *
     * @param sampleRate the sampling rate in hertz, positive
     * @throws IllegalArgumentException if the rate is not positive
     */
    public SilenceDetector(int sampleRate) {
        this(sampleRate, SilenceDetectorOptions.defaults());
    }

    /**
     * Constructs a detector.
     *
     * @param sampleRate the sampling rate in hertz, positive
     * @param options the threshold and the minimum duration
     * @throws NullPointerException if {@code options} is {@code null}
     * @throws IllegalArgumentException if the rate is not positive
     */
    public SilenceDetector(int sampleRate, SilenceDetectorOptions options) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("invalid sample rate " + sampleRate);
        }
        this.sampleRate = sampleRate;
        this.options = Objects.requireNonNull(options, "options");
        this.threshold = (float) options.thresholdAmplitude();
        this.minFrames = Math.max(1, (long) Math.ceil(options.minDurationMicros() * (double) sampleRate / 1_000_000));
        this.ranges = new ArrayList<>();
    }

    /**
     * {@return the threshold and the minimum duration this detector applies}
     */
    public SilenceDetectorOptions options() {
        return options;
    }

    /**
     * Processes the valid samples of a buffer.
     *
     * @param buffer the samples, at the detector's rate
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if the buffer's rate differs
     * @throws IllegalStateException    if the detector is finished
     */
    public void accept(AudioBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.sampleRate() != sampleRate) {
            throw new IllegalArgumentException("buffer at " + buffer.sampleRate() + " Hz for a detector at " + sampleRate + " Hz");
        }
        accept(buffer.planes(), 0, buffer.frames());
    }

    /**
     * Processes a range of planar samples.
     *
     * @param planes one array per channel, at least one
     * @param offset the index of the first sample in each plane
     * @param count  the number of samples per channel
     * @throws NullPointerException      if {@code planes} or a plane is {@code null}
     * @throws IllegalArgumentException  if there is no plane
     * @throws IndexOutOfBoundsException if the range exceeds a plane
     * @throws IllegalStateException     if the detector is finished
     */
    public void accept(float[][] planes, int offset, int count) {
        Objects.requireNonNull(planes, "planes");
        if (finished) {
            throw new IllegalStateException("the detector is finished");
        }
        if (planes.length == 0) {
            throw new IllegalArgumentException("no channel");
        }
        for (var plane : planes) {
            Objects.checkFromIndexSize(offset, count, Objects.requireNonNull(plane, "plane").length);
        }
        for (var index = 0; index < count; index++) {
            var silent = true;
            for (var plane : planes) {
                if (!(Math.abs(plane[offset + index]) < threshold)) {
                    silent = false;
                    break;
                }
            }
            var frame = position + index;
            if (silent) {
                if (runStart < 0) {
                    runStart = frame;
                }
            } else if (runStart >= 0) {
                close(frame);
            }
        }
        position += count;
    }

    /**
     * Records the silent run that ends at a frame when it is long enough.
     *
     * @param end the index after the run
     */
    private void close(long end) {
        if (end - runStart >= minFrames) {
            ranges.add(new SilenceRange(runStart, end, sampleRate));
        }
        runStart = -1;
    }

    /**
     * Returns whether the last frame processed was silent.
     *
     * @return {@code true} inside a silent run, long enough or not
     */
    public boolean isSilent() {
        return runStart >= 0;
    }

    /**
     * Returns the silences that ended so far.
     *
     * @return the silences in order, unmodifiable
     */
    public List<SilenceRange> ranges() {
        return Collections.unmodifiableList(new ArrayList<>(ranges));
    }

    /**
     * Ends the stream: a silent run still going is closed at the end of the stream.
     *
     * @return every silence found, in order, unmodifiable
     */
    public List<SilenceRange> finish() {
        if (!finished) {
            finished = true;
            if (runStart >= 0) {
                close(position);
            }
        }
        return ranges();
    }
}
