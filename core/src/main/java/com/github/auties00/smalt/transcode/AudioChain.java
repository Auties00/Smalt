package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.process.Biquad;
import com.github.auties00.smalt.audio.process.BiquadFilter;
import com.github.auties00.smalt.audio.process.TimeStretcher;

import java.util.List;

/**
 * Runs the sample steps of an encoded audio stream that change its timing or its content before the gain: the
 * removal of pauses, the tempo and pitch change, the filters and the fades.
 *
 * <p>The input is the kept samples, the first one at the start of the kept range; the output goes to a
 * {@link Receiver} in blocks, each valid during the call. Cuts are in input frames from the first kept sample, fade
 * times on the output timeline. An instance is not thread-safe.
 */
final class AudioChain {
    /**
     * Receives the output of the chain.
     */
    interface Receiver {
        /**
         * Receives a block.
         *
         * @param planes the samples, one plane per channel, from index zero, valid during the call
         * @param frames the number of frames
         */
        void accept(float[][] planes, int frames);
    }

    private final int channels;
    private final int sampleRate;

    /**
     * The removed input ranges, {@code start, end} pairs in input frames, sorted and disjoint.
     */
    private final long[] cuts;

    /**
     * The time-scaling of the tempo and pitch change, or {@code null}.
     */
    private final TimeStretcher stretcher;

    /**
     * The filters, or {@code null}.
     */
    private final BiquadFilter filter;

    private final List<Fade> fades;

    /**
     * The output duration in microseconds that places a fade-out at the end, or {@code -1} when unknown.
     */
    private final long outputMicros;

    /**
     * The planes the time-scaled samples are read into, or {@code null} without time-scaling.
     */
    private final float[][] stretched;

    /**
     * The number of input frames received.
     */
    private long inputPosition;

    /**
     * The index of the first cut not yet passed.
     */
    private int cutIndex;

    /**
     * The number of output frames delivered.
     */
    private long outputPosition;

    /**
     * Constructs a chain.
     *
     * @param channels the number of channels
     * @param sampleRate the sampling rate in hertz
     * @param blockFrames the largest block the chain receives and delivers
     * @param cuts the removed input ranges, {@code start, end} pairs in input frames, sorted and disjoint
     * @param tempo the tempo factor, {@code 1} for none
     * @param pitch the pitch factor, {@code 1} for none
     * @param filters the filters, in order
     * @param fades the fades, on the output timeline
     * @param outputMicros the output duration in microseconds, or {@code -1} when unknown
     * @throws IllegalArgumentException if a filter's frequency is not below half the sampling rate, or a fade-out
     * at the end has no known output duration
     */
    AudioChain(int channels, int sampleRate, int blockFrames, long[] cuts, double tempo, double pitch,
               List<Biquad> filters, List<Fade> fades, long outputMicros) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.cuts = cuts.clone();
        this.stretcher = tempo == 1 && pitch == 1 ? null : new TimeStretcher(channels, sampleRate, tempo, pitch);
        this.filter = filters.isEmpty() ? null : new BiquadFilter(filters, channels, sampleRate);
        this.fades = List.copyOf(fades);
        this.outputMicros = outputMicros;
        if (outputMicros < 0 && this.fades.stream().anyMatch(Fade::endsWithOutput)) {
            throw new IllegalArgumentException("a fade-out at the end of the output needs a known duration: the input "
                    + "declares none, so give the fade a start or the transcode a range");
        }
        this.stretched = stretcher == null ? null : new float[channels][blockFrames];
    }

    /**
     * {@return whether the chain changes anything}
     */
    boolean active() {
        return cuts.length > 0 || stretcher != null || filter != null || !fades.isEmpty();
    }

    /**
     * Returns the number of output frames a number of kept input frames gives.
     *
     * @param inputFrames the kept input frames
     * @return the output frames
     */
    long outputFrames(long inputFrames) {
        var kept = inputFrames;
        for (var index = 0; index < cuts.length; index += 2) {
            var start = Math.min(cuts[index], inputFrames);
            var end = Math.min(cuts[index + 1], inputFrames);
            kept -= end - start;
        }
        return stretcher == null ? kept : stretcher.outputFrames(kept);
    }

    /**
     * Processes a block of kept input.
     *
     * @param planes the samples, from index zero; modified
     * @param frames the number of frames
     * @param receiver receives the output
     */
    void process(float[][] planes, int frames, Receiver receiver) {
        frames = removeCuts(planes, frames);
        if (frames == 0) {
            return;
        }
        if (stretcher == null) {
            deliver(planes, frames, receiver);
            return;
        }
        stretcher.write(planes, 0, frames);
        drain(receiver);
    }

    /**
     * Releases the output still held.
     *
     * @param receiver receives the output
     */
    void finish(Receiver receiver) {
        if (stretcher != null) {
            stretcher.finish();
            drain(receiver);
        }
    }

    /**
     * Delivers the time-scaled frames available.
     *
     * @param receiver receives the output
     */
    private void drain(Receiver receiver) {
        int count;
        while ((count = stretcher.read(stretched, 0, stretched[0].length)) > 0) {
            deliver(stretched, count, receiver);
        }
    }

    /**
     * Drops the frames of a block that fall in a cut, moving the others to the front.
     *
     * @param planes the samples
     * @param frames the number of frames
     * @return the number of frames left
     */
    private int removeCuts(float[][] planes, int frames) {
        var blockStart = inputPosition;
        inputPosition += frames;
        if (cutIndex >= cuts.length || cuts[cutIndex] >= inputPosition) {
            return frames;
        }
        var kept = 0;
        var position = blockStart;
        while (position < inputPosition) {
            while (cutIndex < cuts.length && cuts[cutIndex + 1] <= position) {
                cutIndex += 2;
            }
            long next;
            boolean inCut;
            if (cutIndex < cuts.length && cuts[cutIndex] <= position) {
                inCut = true;
                next = Math.min(inputPosition, cuts[cutIndex + 1]);
            } else {
                inCut = false;
                next = cutIndex < cuts.length ? Math.min(inputPosition, cuts[cutIndex]) : inputPosition;
            }
            if (!inCut) {
                var from = (int) (position - blockStart);
                var count = (int) (next - position);
                if (from != kept) {
                    for (var plane : planes) {
                        System.arraycopy(plane, from, plane, kept, count);
                    }
                }
                kept += count;
            }
            position = next;
        }
        return kept;
    }

    /**
     * Filters and fades a block and hands it to the receiver.
     *
     * @param planes the samples, from index zero; modified
     * @param frames the number of frames
     * @param receiver receives the output
     */
    private void deliver(float[][] planes, int frames, Receiver receiver) {
        if (filter != null) {
            filter.process(planes, 0, frames);
        }
        if (!fades.isEmpty()) {
            applyFades(planes, frames);
        }
        outputPosition += frames;
        receiver.accept(planes, frames);
    }

    /**
     * Multiplies a block by the gain of the fades at each frame's output time.
     *
     * @param planes the samples
     * @param frames the number of frames
     */
    private void applyFades(float[][] planes, int frames) {
        var first = outputPosition * 1_000_000.0 / sampleRate;
        var last = (outputPosition + frames - 1) * 1_000_000.0 / sampleRate;
        if (constantOver(first, last)) {
            var gain = (float) Fade.gain(fades, first, outputMicros);
            if (gain != 1f) {
                for (var plane : planes) {
                    for (var index = 0; index < frames; index++) {
                        plane[index] *= gain;
                    }
                }
            }
            return;
        }
        for (var index = 0; index < frames; index++) {
            var gain = (float) Fade.gain(fades, (outputPosition + index) * 1_000_000.0 / sampleRate, outputMicros);
            for (var channel = 0; channel < channels; channel++) {
                planes[channel][index] *= gain;
            }
        }
    }

    /**
     * {@return whether no fade changes its gain between two output times}
     *
     * @param first the first time in microseconds
     * @param last the last time in microseconds
     */
    private boolean constantOver(double first, double last) {
        for (var fade : fades) {
            var start = fade.endsWithOutput() ? outputMicros - fade.durationMicros() : fade.startMicros().getAsLong();
            var end = start + fade.durationMicros();
            if (last >= start && first <= end) {
                return false;
            }
        }
        return true;
    }
}
