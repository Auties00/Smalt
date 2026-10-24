package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Parses one AAC {@code coupling_channel_element} (ISO/IEC 14496-3 subclause 4.6.8.3) and holds
 * what applying it needs: its channel stream, its targets and its gain lists.
 *
 * <p>A dependently switched coupling channel is added, band by band and scaled by its gain
 * list, to the spectra of its targets, before or after their TNS filtering depending on its
 * domain. An independently switched one is synthesized on its own and added, scaled by a single
 * gain, to the time signal of its targets.
 *
 * <p>A gain element list applies to one target channel; a pair target whose left and right
 * flags are both clear shares one list between its channels. Gains are
 * {@code sign * scale^(-g)}, where {@code scale = 2^(2^(gain_element_scale - 3))} and {@code g}
 * is the common gain element or the running sum of the per-band differences; with
 * {@code gain_element_sign} set, the lowest bit of that value is the sign.
 *
 * @implNote This implementation's gain convention and target list semantics are validated
 * against the ISO/IEC 14496-26 conformance streams al07 (5.1 with a coupling channel), whose
 * reference waveforms it reproduces within the conformance tolerance.
 */
public final class CouplingChannel {
    /**
     * The largest number of target elements.
     */
    private static final int MAX_TARGETS = 8;

    /**
     * The largest number of gain lists: two per target.
     */
    private static final int MAX_LISTS = 2 * MAX_TARGETS;

    /**
     * The channel stream carrying the coupling spectrum.
     */
    private final ChannelStream stream;

    /**
     * The element instance tag.
     */
    private int tag;

    /**
     * Whether the coupling is independently switched (applied in the time domain).
     */
    private boolean independent;

    /**
     * Whether a dependent coupling applies after TNS rather than before.
     */
    private boolean afterTns;

    /**
     * The number of target elements.
     */
    private int targetCount;

    /**
     * Whether each target is a channel pair element.
     */
    private final boolean[] targetPairs = new boolean[MAX_TARGETS];

    /**
     * The instance tag of each target.
     */
    private final int[] targetTags = new int[MAX_TARGETS];

    /**
     * Which channels of each pair target are coupled: bit 1 left, bit 0 right, zero for both
     * with one shared gain list.
     */
    private final int[] targetSelects = new int[MAX_TARGETS];

    /**
     * The gain of every band of every gain list, indexed
     * {@code list * 8 * MAX_BANDS + group * MAX_BANDS + band}.
     */
    private final float[] gains = new float[MAX_LISTS * 8 * ChannelStream.MAX_BANDS];

    /**
     * The time-domain overlap memory of an independently switched coupling channel.
     */
    private final float[] overlap;

    /**
     * The window shape of the previous frame of an independently switched coupling channel.
     */
    private int previousShape;

    /**
     * The AAC Main predictor bank of the coupling channel, or {@code null} for other object types.
     */
    private final FrequencyPredictor predictor;

    /**
     * The long term predictor of an independently switched AAC LTP coupling channel, or
     * {@code null} for other object types.
     */
    private final LongTermPredictor longTermPredictor;

    /**
     * The SSR filterbank memory of an independently switched AAC SSR coupling channel, or
     * {@code null} for other object types.
     */
    private final SsrSynthesis.State ssrState;

    /**
     * The synthesized time signal of an independently switched coupling channel.
     */
    private final float[] timeSignal;

    /**
     * Allocates a coupling channel decoder.
     *
     * @param frameLength the frame length
     * @param tableIndex the sampling table index
     * @param objectType the core audio object type
     * @param kernel the DSP kernel
     */
    public CouplingChannel(int frameLength, int tableIndex, int objectType, AacKernel kernel) {
        this.stream = new ChannelStream(frameLength, tableIndex, objectType, kernel);
        this.overlap = new float[frameLength];
        this.timeSignal = new float[frameLength];
        this.predictor = objectType == AudioSpecificConfig.AOT_MAIN ? new FrequencyPredictor(frameLength, tableIndex) : null;
        this.longTermPredictor = objectType == AudioSpecificConfig.AOT_LTP ? new LongTermPredictor(frameLength, kernel) : null;
        this.ssrState = objectType == AudioSpecificConfig.AOT_SSR ? new SsrSynthesis.State() : null;
    }

    /**
     * Parses the element after its identifier and instance tag; the noise bands of the stream
     * are left for the caller to fill.
     *
     * @param reader the bit source
     * @param tag    the element instance tag, already read
     * @throws MediaException.Malformed if the element is invalid
     */
    public void parse(AacBitReader reader, int tag) {
        this.tag = tag;
        independent = reader.readFlag();
        targetCount = reader.read(3) + 1;
        var lists = 0;
        for (var target = 0; target < targetCount; target++) {
            lists++;
            targetPairs[target] = reader.readFlag();
            targetTags[target] = reader.read(4);
            targetSelects[target] = 0;
            if (targetPairs[target]) {
                var select = reader.read(2);
                targetSelects[target] = select;
                if (select == 3) {
                    lists++;
                }
            }
        }
        afterTns = reader.readFlag();
        var signed = reader.readFlag();
        var scale = StrictMath.pow(2, StrictMath.pow(2, reader.read(2) - 3));
        stream.parse(reader, false, false);
        var stride = 8 * ChannelStream.MAX_BANDS;
        Arrays.fill(gains, 0, stride, 1);
        for (var list = 1; list < lists; list++) {
            var base = list * stride;
            var common = independent || reader.readFlag();
            if (common) {
                var value = gain(Codebooks.decodeScalefactor(reader), signed, scale);
                Arrays.fill(gains, base, base + stride, value);
                continue;
            }
            var accumulated = 0;
            for (var group = 0; group < stream.groupCount(); group++) {
                for (var band = 0; band < stream.maxBands(); band++) {
                    var index = group * ChannelStream.MAX_BANDS + band;
                    if (stream.bandType(index) != Codebooks.ZERO_HCB) {
                        accumulated += Codebooks.decodeScalefactor(reader);
                        gains[base + index] = gain(accumulated, signed, scale);
                    }
                }
            }
        }
        reader.checkNotOverrun();
    }

    /**
     * Converts a coded gain element to a linear gain.
     *
     * @param value  the accumulated gain element
     * @param signed whether the lowest bit carries the sign
     * @param scale  the gain step, {@code 2^(2^(gain_element_scale - 3))}
     * @return the linear gain
     */
    private static float gain(int value, boolean signed, double scale) {
        var sign = 1.0;
        if (signed) {
            if ((value & 1) != 0) {
                sign = -1;
            }
            value >>= 1;
        }
        return (float) (sign * StrictMath.pow(scale, -value));
    }

    /**
     * Returns the coupling channel stream.
     *
     * @return the stream
     */
    public ChannelStream stream() {
        return stream;
    }

    /**
     * Returns the element instance tag.
     *
     * @return the tag
     */
    public int tag() {
        return tag;
    }

    /**
     * Returns whether the coupling is independently switched.
     *
     * @return {@code true} when applied in the time domain
     */
    public boolean independent() {
        return independent;
    }

    /**
     * Returns whether a dependent coupling applies after the targets' TNS.
     *
     * @return {@code true} after TNS, {@code false} before
     */
    public boolean afterTns() {
        return afterTns;
    }

    /**
     * Returns the number of target elements.
     *
     * @return the count
     */
    public int targetCount() {
        return targetCount;
    }

    /**
     * Returns whether a target is a channel pair element.
     *
     * @param target the target index
     * @return {@code true} for a pair
     */
    public boolean targetIsPair(int target) {
        return targetPairs[target];
    }

    /**
     * Returns the instance tag of a target.
     *
     * @param target the target index
     * @return the tag
     */
    public int targetTag(int target) {
        return targetTags[target];
    }

    /**
     * Returns which channels of a pair target are coupled.
     *
     * @param target the target index
     * @return bit 1 for left, bit 0 for right, zero for both sharing one gain list
     */
    public int targetSelect(int target) {
        return targetSelects[target];
    }

    /**
     * Adds the coupling spectrum, scaled by a gain list, to a target spectrum.
     *
     * @param list   the gain list
     * @param target the target spectrum
     */
    public void addSpectrum(int list, float[] target) {
        var offsets = stream.bandOffsets();
        var windowLength = stream.windowLength();
        var source = stream.spectrum();
        var base = list * 8 * ChannelStream.MAX_BANDS;
        var firstWindow = 0;
        for (var group = 0; group < stream.groupCount(); group++) {
            var lastWindow = firstWindow + stream.groupLength(group);
            for (var band = 0; band < stream.maxBands(); band++) {
                var index = group * ChannelStream.MAX_BANDS + band;
                if (stream.bandType(index) == Codebooks.ZERO_HCB) {
                    continue;
                }
                var gain = gains[base + index];
                for (var window = firstWindow; window < lastWindow; window++) {
                    var end = window * windowLength + offsets[band + 1];
                    for (var k = window * windowLength + offsets[band]; k < end; k++) {
                        target[k] += gain * source[k];
                    }
                }
            }
            firstWindow = lastWindow;
        }
    }

    /**
     * Runs the AAC Main predictor bank over the coupling spectrum, once its noise bands are
     * filled; does nothing for other object types.
     */
    public void predict() {
        if (predictor != null) {
            predictor.apply(stream);
        }
    }

    /**
     * Adds the long term prediction of an independently switched AAC LTP coupling channel to its
     * spectrum, before TNS; does nothing for other object types.
     *
     * @param synthesis the filterbank whose windows the frame uses
     */
    public void predictLongTerm(Synthesis synthesis) {
        if (longTermPredictor != null) {
            longTermPredictor.predict(stream, overlap, previousShape, synthesis);
        }
    }

    /**
     * Synthesizes the time signal of an independently switched coupling channel.
     *
     * @param synthesis the filterbank
     * @param ssr the SSR filterbank, used for AAC SSR streams
     */
    public void synthesize(Synthesis synthesis, SsrSynthesis ssr) {
        if (ssrState != null) {
            ssr.synthesize(stream, ssrState, timeSignal, 0);
        } else {
            synthesis.synthesize(stream.spectrum(), stream.windowSequence(), previousShape, stream.windowShape(),
                    overlap, timeSignal, 0);
        }
        previousShape = stream.windowShape();
        if (longTermPredictor != null) {
            longTermPredictor.update(timeSignal, 0);
        }
    }

    /**
     * Adds the synthesized time signal, scaled by a gain list's gain, to target samples.
     *
     * @param list   the gain list
     * @param target the target samples
     * @param length the number of samples
     */
    public void addTimeSignal(int list, float[] target, int length) {
        var gain = gains[list * 8 * ChannelStream.MAX_BANDS];
        for (var n = 0; n < length; n++) {
            target[n] += gain * timeSignal[n];
        }
    }
}
