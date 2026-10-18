/* Ports vo-aacenc 0.1.3 (aacenc/src/qc_main.c, ms_stereo.c). Copyright 2003-2010 VisualOn, Inc.; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.Arrays;

/**
 * Encodes planar float samples into AAC Low Complexity raw data blocks.
 *
 * <p>Samples are appended with {@link #push}; once {@link #frameReady()} holds, each
 * {@link #encodeFrame()} codes the next 1024 samples of every channel. The first frame codes the
 * encoder delay of 1024 silent samples, so a decoder reproduces input sample {@code i} at output
 * sample {@code i + 1024}. Each frame needs 512 samples of look-ahead for block switching;
 * {@link #pad(int)} appends silence at the end of the input.
 *
 * <p>Mono is coded as one single channel element, stereo as one channel pair element and 5.1
 * (input in WAVE order) as the elements of channel configuration 6. In constant bitrate mode
 * the frame budget follows a bit reservoir of {@code 6144 * channels} bits minus the mean frame
 * size, filled with fill elements whenever it would overflow, so the long-term bitrate is exact.
 * In variable bitrate mode every frame is coded at the same rate control parameter, a
 * level-independent quality derived from the nominal bitrate, so frame sizes follow the signal
 * and no fill elements are written.
 *
 * @implNote This implementation's block switching, M/S decision and bit reservoir control
 * follow 3GPP TS 26.403. Its rate control differs: it bisects one threshold parameter shared by all channels on
 * the bit counts of actual quantizations, so frames use their budget closely.
 */
public final class EncoderEngine {
    /**
     * The frame length.
     */
    public static final int FRAME = 1024;

    /**
     * The look-ahead of block switching beyond the frame's window.
     */
    private static final int LOOKAHEAD = 512;

    /**
     * The capacity of each channel's input buffer.
     */
    private static final int BUFFER = 3 * FRAME;

    /**
     * The largest number of bits one channel may use in a frame.
     */
    private static final int CHANNEL_BITS = 6144;

    /**
     * The first parameter of the rate control's unprotected regime.
     */
    private static final float UNPROTECTED = 1.0001f;

    /**
     * The width below which the rate control stops bisecting its parameter.
     */
    private static final float SEARCH_TOLERANCE = 0.008f;

    /**
     * The sampling rate.
     */
    private final int sampleRate;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The bitrate in bits per second.
     */
    private final int bitrate;

    /**
     * Whether the encoder codes at constant quality.
     */
    private final boolean vbr;

    /**
     * The channel encoders, in coded order.
     */
    private final EncoderChannel[] coded;

    /**
     * The input channel feeding each coded channel.
     */
    private final int[] inputChannel;

    /**
     * The element types, in coded order.
     */
    private final int[] elementTypes;

    /**
     * The input buffer of each coded channel; index 0 holds the first sample of the next
     * frame's window.
     */
    private final float[][] buffers;

    /**
     * The number of valid samples in each buffer.
     */
    private int filled;

    /**
     * The number of frames encoded.
     */
    private long frames;

    /**
     * Whether each coded channel's current frame holds an attack.
     */
    private final boolean[] attacks;

    /**
     * The attack segment of each coded channel's current frame.
     */
    private final int[] attackIndexes;

    /**
     * The largest segment energy of each coded channel's current frame.
     */
    private final float[] attackEnergies;

    /**
     * Whether each coded channel's next frame holds an attack.
     */
    private final boolean[] nextAttacks;

    /**
     * The attack segment of each coded channel's next frame.
     */
    private final int[] nextIndexes;

    /**
     * The largest segment energy of each coded channel's next frame.
     */
    private final float[] nextEnergies;

    /**
     * The next instance tag of each element type while writing a frame.
     */
    private final int[] tags = new int[4];

    /**
     * The window sequence of the previous frame of each element.
     */
    private final int[] previousSequences;

    /**
     * The M/S flag of every band of each channel pair, indexed by element.
     */
    private final boolean[][] msUsed;

    /**
     * The M/S mask mode of each channel pair: 0 none, 1 some, 2 all.
     */
    private final int[] msModes;

    /**
     * The bit reservoir fullness in bits.
     */
    private int reservoir;

    /**
     * The bit reservoir capacity in bits.
     */
    private final int reservoirSize;

    /**
     * The smallest perceptual entropy of the bit reservoir control's interpolation range.
     */
    private float peMin;

    /**
     * The largest perceptual entropy of the bit reservoir control's interpolation range.
     */
    private float peMax;

    /**
     * The constant threshold parameter of variable bitrate mode.
     */
    private final float vbrParameter;

    /**
     * The threshold parameter the rate control chose for the previous frame.
     */
    private float lastParameter;

    /**
     * The bitstream writer, reused frame after frame.
     */
    private final MsbBitWriter writer = new MsbBitWriter(8192);

    /**
     * Creates an engine.
     *
     * @param sampleRate a standard AAC sampling rate
     * @param channels 1, 2 or 6
     * @param bitrate the bitrate in bits per second
     * @param vbr whether to code at constant quality instead of constant bitrate
     * @param bandwidth the audio bandwidth in hertz
     * @param kernel the DSP kernel
     */
    public EncoderEngine(int sampleRate, int channels, int bitrate, boolean vbr, int bandwidth, AacKernel kernel) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitrate = bitrate;
        this.vbr = vbr;
        switch (channels) {
            case 1 -> {
                elementTypes = new int[]{ElementId.SCE};
                inputChannel = new int[]{0};
            }
            case 2 -> {
                elementTypes = new int[]{ElementId.CPE};
                inputChannel = new int[]{0, 1};
            }
            case 6 -> {
                elementTypes = new int[]{ElementId.SCE, ElementId.CPE, ElementId.CPE,
                        ElementId.LFE};
                inputChannel = new int[]{2, 0, 1, 4, 5, 3};
            }
            default -> throw new IllegalArgumentException("unsupported channel count " + channels);
        }
        var fullBitrate = channels == 6 ? bitrate * 5 / 5.2 : bitrate;
        var perChannel = (int) (fullBitrate / (channels == 6 ? 5 : channels));
        coded = new EncoderChannel[channels];
        for (var i = 0; i < channels; i++) {
            var lfe = channels == 6 && i == 5;
            coded[i] = new EncoderChannel(sampleRate, lfe ? Math.min(bandwidth, 240) : bandwidth,
                    lfe ? Math.max(perChannel / 5, 1000) : perChannel, !lfe, kernel);
        }
        buffers = new float[channels][BUFFER];
        filled = FRAME;
        attacks = new boolean[channels];
        attackIndexes = new int[channels];
        attackEnergies = new float[channels];
        nextAttacks = new boolean[channels];
        nextIndexes = new int[channels];
        nextEnergies = new float[channels];
        previousSequences = new int[elementTypes.length];
        msUsed = new boolean[elementTypes.length][8 * EncoderChannel.MAX_BANDS];
        msModes = new int[elementTypes.length];
        var mean = meanFrameBits();
        reservoirSize = Math.max(0, (CHANNEL_BITS * channels - mean) & ~7);
        reservoir = reservoirSize;
        var meanPe = 10.0f * FRAME * Math.min(bandwidth, sampleRate / 2) / (sampleRate / 2.0f) * channels;
        peMin = 0.8f * meanPe;
        peMax = 1.2f * meanPe;
        vbrParameter = vbrParameter(bitrate / (channels == 6 ? 5.2 : channels));
    }

    /**
     * Returns the number of channels.
     *
     * @return the channel count
     */
    public int channels() {
        return channels;
    }

    /**
     * Returns the number of frames encoded so far.
     *
     * @return the frame count
     */
    public long frames() {
        return frames;
    }

    /**
     * Returns the number of input samples the engine can accept before the next frame must be
     * encoded.
     *
     * @return the free capacity in samples per channel
     */
    public int capacity() {
        return BUFFER - filled;
    }

    /**
     * Appends input samples, clipped to {@code [-1, 1]}.
     *
     * @param planes the input planes in input channel order
     * @param offset the index of the first sample
     * @param count the number of samples, at most {@link #capacity()}
     */
    public void push(float[][] planes, int offset, int count) {
        for (var channel = 0; channel < channels; channel++) {
            var source = planes[inputChannel[channel]];
            var target = buffers[channel];
            for (var i = 0; i < count; i++) {
                var value = source[offset + i];
                target[filled + i] = value > 1 ? 1 : value < -1 ? -1 : value == value ? value : 0;
            }
        }
        filled += count;
    }

    /**
     * Appends silence.
     *
     * @param count the number of samples, at most {@link #capacity()}
     */
    public void pad(int count) {
        for (var channel = 0; channel < channels; channel++) {
            Arrays.fill(buffers[channel], filled, filled + count, 0);
        }
        filled += count;
    }

    /**
     * Returns whether enough samples are buffered to encode the next frame.
     *
     * @return {@code true} when the window and the look-ahead are complete
     */
    public boolean frameReady() {
        return filled >= 2 * FRAME + LOOKAHEAD;
    }

    /**
     * Encodes the next frame.
     *
     * @return the raw data block
     */
    public byte[] encodeFrame() {
        if (frames == 0) {
            for (var channel = 0; channel < channels; channel++) {
                detect(channel, FRAME - LOOKAHEAD);
            }
        }
        for (var channel = 0; channel < channels; channel++) {
            var detector = coded[channel].detector();
            detector.analyze(buffers[channel], 2 * FRAME - LOOKAHEAD);
            nextAttacks[channel] = detector.attack();
            nextIndexes[channel] = detector.attackIndex();
            nextEnergies[channel] = detector.peakEnergy();
        }
        var channel = 0;
        for (var element = 0; element < elementTypes.length; element++) {
            var width = elementTypes[element] == ElementId.CPE ? 2 : 1;
            var sequence = ChannelStream.ONLY_LONG_SEQUENCE;
            var attackIndex = -1;
            if (elementTypes[element] != ElementId.LFE) {
                var current = false;
                var next = false;
                var strongest = -1.0f;
                for (var i = channel; i < channel + width; i++) {
                    current |= attacks[i];
                    next |= nextAttacks[i];
                    if (attacks[i] && attackEnergies[i] > strongest) {
                        strongest = attackEnergies[i];
                        attackIndex = attackIndexes[i];
                    }
                }
                sequence = nextSequence(previousSequences[element], current, next);
                if (sequence != ChannelStream.EIGHT_SHORT_SEQUENCE || !current) {
                    attackIndex = -1;
                }
            }
            previousSequences[element] = sequence;
            for (var i = channel; i < channel + width; i++) {
                coded[i].analyze(buffers[i], 0, sequence, attackIndex);
            }
            if (width == 2) {
                decideMidSide(element, coded[channel], coded[channel + 1]);
            }
            channel += width;
        }
        for (var i = 0; i < channels; i++) {
            attacks[i] = nextAttacks[i];
            attackIndexes[i] = nextIndexes[i];
            attackEnergies[i] = nextEnergies[i];
            coded[i].prepare();
        }
        var block = encodeBlock();
        for (var i = 0; i < channels; i++) {
            System.arraycopy(buffers[i], FRAME, buffers[i], 0, filled - FRAME);
        }
        filled -= FRAME;
        frames++;
        return block;
    }

    /**
     * Runs attack detection for the block centered on a buffer position.
     *
     * @param channel the coded channel
     * @param offset the index of the block's first sample
     */
    private void detect(int channel, int offset) {
        var detector = coded[channel].detector();
        detector.analyze(buffers[channel], offset);
        attacks[channel] = detector.attack();
        attackIndexes[channel] = detector.attackIndex();
        attackEnergies[channel] = detector.peakEnergy();
    }

    /**
     * Chooses the window sequence of a frame from its neighborhood.
     *
     * @param previous the previous frame's sequence
     * @param current whether the frame holds an attack
     * @param next whether the next frame holds an attack
     * @return the sequence
     */
    private int nextSequence(int previous, boolean current, boolean next) {
        return switch (previous) {
            case ChannelStream.LONG_START_SEQUENCE -> ChannelStream.EIGHT_SHORT_SEQUENCE;
            case ChannelStream.EIGHT_SHORT_SEQUENCE -> current || next
                    ? ChannelStream.EIGHT_SHORT_SEQUENCE : ChannelStream.LONG_STOP_SEQUENCE;
            default -> {
                if (current && frames == 0) {
                    yield ChannelStream.EIGHT_SHORT_SEQUENCE;
                }
                yield next ? ChannelStream.LONG_START_SEQUENCE : ChannelStream.ONLY_LONG_SEQUENCE;
            }
        };
    }

    /**
     * Decides M/S stereo band by band for a channel pair and converts the chosen bands.
     *
     * <p>A band is coded as mid and side when the product of its threshold-to-energy ratios in
     * M/S (with the smaller threshold for both) exceeds that in L/R.
     *
     * @param element the element index
     * @param left the left channel
     * @param right the right channel
     */
    private void decideMidSide(int element, EncoderChannel left, EncoderChannel right) {
        var used = msUsed[element];
        var l = left.grouped();
        var r = right.grouped();
        var energyL = left.energies();
        var energyR = right.energies();
        var thresholdL = left.thresholds();
        var thresholdR = right.thresholds();
        var some = false;
        var all = true;
        for (var group = 0; group < left.groupCount(); group++) {
            for (var band = 0; band < left.bandCount(); band++) {
                var index = group * EncoderChannel.MAX_BANDS + band;
                used[index] = false;
                if (band >= left.activeBands()) {
                    continue;
                }
                var start = left.bandStart(index);
                var end = left.bandEnd(index);
                var mid = 0.0f;
                var side = 0.0f;
                for (var k = start; k < end; k++) {
                    var m = (l[k] + r[k]) * 0.5f;
                    var s = (l[k] - r[k]) * 0.5f;
                    mid += m * m;
                    side += s * s;
                }
                var tL = thresholdL[index];
                var tR = thresholdR[index];
                var minimum = Math.min(tL, tR);
                var lr = (tL / Math.max(energyL[index], tL)) * (tR / Math.max(energyR[index], tR));
                var ms = (minimum / Math.max(mid, minimum)) * (minimum / Math.max(side, minimum));
                if (ms > lr) {
                    used[index] = true;
                    some = true;
                    for (var k = start; k < end; k++) {
                        var a = l[k];
                        var b = r[k];
                        l[k] = (a + b) * 0.5f;
                        r[k] = (a - b) * 0.5f;
                    }
                    energyL[index] = mid;
                    energyR[index] = side;
                    thresholdL[index] = minimum;
                    thresholdR[index] = minimum;
                } else {
                    all = false;
                }
            }
        }
        msModes[element] = !some ? 0 : all ? 2 : 1;
    }

    /**
     * Returns the mean number of bits of the next frame at the configured bitrate.
     *
     * @return the bits, the exact long-term average rounded frame by frame
     */
    private int meanFrameBits() {
        var before = frames * FRAME * (long) bitrate / sampleRate;
        var after = (frames + 1) * FRAME * (long) bitrate / sampleRate;
        return (int) (after - before);
    }

    /**
     * Runs the rate control, then writes the raw data block of the analyzed frame.
     *
     * @return the block
     */
    private byte[] encodeBlock() {
        var mean = meanFrameBits();
        var hardLimit = vbr ? CHANNEL_BITS * channels : Math.min(CHANNEL_BITS * channels, mean + reservoir);
        var reach = 0.0f;
        var pe = 0.0f;
        for (var channel : coded) {
            reach = Math.max(reach, channel.loudnessReach());
            pe += channel.perceptualEntropy();
        }
        float parameter;
        if (vbr && evaluate(vbrParameter, reach, false) <= hardLimit - 8) {
            parameter = vbrParameter;
        } else {
            parameter = search(Math.min(hardLimit - 8, vbr ? hardLimit : desiredBits(mean, pe)), reach);
        }
        var bits = evaluate(parameter, reach, true);
        while (bits > hardLimit - 8 && parameter < 2) {
            parameter = Math.min(2, parameter + 0.05f);
            bits = evaluate(parameter, reach, true);
        }
        writer.reset();
        writeElements();
        var used = (int) writer.bitCount() + 3;
        var target = used;
        if (!vbr) {
            var after = reservoir + mean - ((used + 7) & ~7);
            if (after > reservoirSize) {
                target = used + (after - reservoirSize);
            }
        }
        writeFill(target - used);
        writer.write(ElementId.END, 3);
        writer.alignWithZeros();
        var block = writer.toByteArray();
        if (!vbr) {
            reservoir = Math.min(reservoirSize, reservoir + mean - block.length * 8);
            if (reservoir < 0) {
                reservoir = 0;
            }
        }
        return block;
    }

    /**
     * Finds, to within a small tolerance, the smallest threshold parameter whose frame fits a
     * bit budget.
     *
     * <p>The hole-avoiding regime (parameters from -1 to 1) is searched unless even its end
     * exceeds the budget, in which case the unprotected regime (1 to 2) is. Within the regime,
     * the search brackets the answer by doubling steps away from the previous frame's parameter,
     * which is usually close, and then bisects the bracket.
     *
     * @param budget the bit budget
     * @param reach the loudness reach of the frame
     * @return the parameter, from -1 to 2
     */
    private float search(int budget, float reach) {
        float result;
        if (lastParameter <= 1) {
            result = searchRange(budget, reach, -1, 1, lastParameter);
            if (Float.isNaN(result)) {
                result = searchRange(budget, reach, UNPROTECTED, 2, UNPROTECTED);
            }
        } else if (evaluate(1, reach, false) <= budget) {
            result = searchRange(budget, reach, -1, 1, 1);
        } else {
            result = searchRange(budget, reach, UNPROTECTED, 2, lastParameter);
        }
        lastParameter = result;
        return result;
    }

    /**
     * Searches one regime of the threshold parameter.
     *
     * @param budget the bit budget
     * @param reach the loudness reach of the frame
     * @param floor the lowest parameter of the regime
     * @param ceiling the highest parameter of the regime
     * @param start the first parameter to try
     * @return the smallest fitting parameter to within {@link #SEARCH_TOLERANCE}, or NaN when
     * even the ceiling does not fit
     */
    private float searchRange(int budget, float reach, float floor, float ceiling, float start) {
        start = Math.clamp(start, floor, ceiling);
        float low;
        float high;
        var step = 0.0625f;
        if (evaluate(start, reach, false) <= budget) {
            high = start;
            low = Math.max(floor, high - step);
            while (high > floor && evaluate(low, reach, false) <= budget) {
                high = low;
                step *= 2;
                low = Math.max(floor, high - step);
            }
            if (high == floor) {
                return high;
            }
        } else {
            low = start;
            high = Math.min(ceiling, low + step);
            while (low < ceiling && evaluate(high, reach, false) > budget) {
                low = high;
                step *= 2;
                high = Math.min(ceiling, low + step);
            }
            if (low == ceiling) {
                return ceiling == 2 ? 2 : Float.NaN;
            }
        }
        while (high - low > SEARCH_TOLERANCE) {
            var middle = (low + high) / 2;
            if (evaluate(middle, reach, false) <= budget) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return high;
    }

    /**
     * Quantizes every channel at a threshold parameter and returns the frame's bits.
     *
     * @param parameter the threshold parameter
     * @param reach the loudness reach of the frame
     * @param exhaustive whether to plan the sections exactly
     * @return the bits of every element and the end marker, before fill and alignment
     */
    private int evaluate(float parameter, float reach, boolean exhaustive) {
        var bits = 3;
        var channel = 0;
        for (var element = 0; element < elementTypes.length; element++) {
            if (elementTypes[element] == ElementId.CPE) {
                var left = coded[channel];
                var right = coded[channel + 1];
                var required = Math.max(left.requiredBands(), right.requiredBands());
                left.quantize(parameter, reach, required);
                right.quantize(parameter, reach, required);
                var shared = Math.max(left.maxBands(), right.maxBands());
                left.setMaxBands(shared);
                right.setMaxBands(shared);
                bits += 3 + 4 + 1 + left.infoBits() + 2;
                if (msModes[element] == 1) {
                    bits += left.groupCount() * shared;
                }
                bits += left.sideBits(true) + left.cost(exhaustive);
                bits += right.sideBits(true) + right.cost(exhaustive);
                channel += 2;
            } else {
                var single = coded[channel];
                single.quantize(parameter, reach, single.requiredBands());
                bits += 3 + 4 + single.sideBits(false) + single.cost(exhaustive);
                channel++;
            }
        }
        return bits;
    }

    /**
     * Returns the bits the bit reservoir control grants the frame.
     *
     * @param mean the mean frame bits
     * @param pe the frame's perceptual entropy
     * @return the desired bits
     */
    private int desiredBits(int mean, float pe) {
        var shortFrame = false;
        for (var channel : coded) {
            shortFrame |= channel.sequence() == ChannelStream.EIGHT_SHORT_SEQUENCE;
        }
        var fill = reservoirSize == 0 ? 0 : 100f * reservoir / reservoirSize;
        float save;
        float spend;
        if (shortFrame) {
            save = interpolate(fill, 20, 75, 20, 0);
            spend = interpolate(fill, 20, 75, -5, 50);
        } else {
            save = interpolate(fill, 20, 95, 30, -5);
            spend = interpolate(fill, 20, 95, -10, 40);
        }
        var clamped = Math.clamp(pe, peMin, peMax);
        var factor = peMax > peMin ? (100 - save) + (spend + save) * (clamped - peMin) / (peMax - peMin) : 100;
        factor = Math.min(factor, 100 - 30 + 100f * reservoir / Math.max(mean, 1));
        adjustPeRange(pe);
        return (int) (mean * factor / 100);
    }

    /**
     * Interpolates linearly between two values over a clipped range.
     *
     * @param x the abscissa
     * @param low the start of the range
     * @param high the end of the range
     * @param atLow the value at the start
     * @param atHigh the value at the end
     * @return the interpolated value
     */
    private static float interpolate(float x, float low, float high, float atLow, float atHigh) {
        var clamped = Math.clamp(x, low, high);
        return atLow + (atHigh - atLow) * (clamped - low) / (high - low);
    }

    /**
     * Tracks the perceptual entropy range the reservoir control interpolates over.
     *
     * @param pe the frame's perceptual entropy
     */
    private void adjustPeRange(float pe) {
        var minDifference = pe / 6;
        if (pe > peMax) {
            var difference = pe - peMax;
            peMin += difference * 0.3f;
            peMax += difference;
        } else if (pe < peMin) {
            var difference = peMin - pe;
            peMin -= difference * 0.14f;
            peMax -= difference * 0.07f;
        } else {
            peMin += (pe - peMin) * 0.3f;
            peMax -= (peMax - pe) * 0.07f;
        }
        if (peMax - peMin < minDifference) {
            var below = Math.max(0, pe - peMin);
            var above = Math.max(0, peMax - pe);
            var sum = below + above;
            if (sum > 0) {
                peMax = pe + above * minDifference / sum;
                peMin = Math.max(0, pe - below * minDifference / sum);
            }
        }
    }

    /**
     * Writes every channel element of the frame.
     */
    private void writeElements() {
        var channel = 0;
        Arrays.fill(tags, 0);
        for (var element = 0; element < elementTypes.length; element++) {
            var type = elementTypes[element];
            writer.write(type, 3);
            writer.write(tags[type]++, 4);
            if (type == ElementId.CPE) {
                var left = coded[channel];
                var right = coded[channel + 1];
                writer.write(1, 1);
                left.writeInfo(writer);
                writer.write(msModes[element], 2);
                if (msModes[element] == 1) {
                    for (var group = 0; group < left.groupCount(); group++) {
                        for (var band = 0; band < left.maxBands(); band++) {
                            writer.write(msUsed[element][group * EncoderChannel.MAX_BANDS + band] ? 1 : 0, 1);
                        }
                    }
                }
                left.write(writer, true);
                right.write(writer, true);
                channel += 2;
            } else {
                coded[channel].write(writer, false);
                channel++;
            }
        }
    }

    /**
     * Writes fill elements carrying at least {@code bits} bits.
     *
     * <p>Each fill element holds an {@code EXT_FILL} extension payload; the elements may exceed
     * the request by less than the escape overhead of one element.
     *
     * @param bits the bits to fill
     */
    private void writeFill(int bits) {
        while (bits > 0) {
            int count;
            int size;
            if (bits <= 7 + 8 * 14) {
                count = Math.max(0, (bits - 7 + 7) / 8);
                size = 7 + 8 * count;
            } else {
                count = Math.clamp((bits - 15 + 7) / 8, 15, 269);
                size = 15 + 8 * count;
            }
            writer.write(ElementId.FIL, 3);
            if (count >= 15) {
                writer.write(15, 4);
                writer.write(count - 14, 8);
            } else {
                writer.write(count, 4);
            }
            if (count > 0) {
                writer.write(0, 8);
                for (var i = 1; i < count; i++) {
                    writer.write(0xA5, 8);
                }
            }
            bits -= size;
        }
    }

    /**
     * Returns the rate control parameter that codes at about the nominal bitrate.
     *
     * @implNote This implementation interpolates a table calibrated so that the mean bitrate of
     * synthetic speech and music at 48 kHz, coded with the automatic bandwidth, meets the nominal
     * bitrate per channel; the parameter scales every frame's thresholds with its own loudness,
     * so the quality does not depend on the level and the bitrate follows the signal's
     * complexity. The bitrate saturates above about 200 kbps per channel.
     *
     * @param channelBitrate the nominal bitrate per full-band channel
     * @return the parameter, from -1 to 2
     */
    private static float vbrParameter(double channelBitrate) {
        double[] rates = {8000, 12000, 16000, 24000, 32000, 48000, 64000, 80000, 96000, 128000, 160000, 200000};
        double[] parameters = {1.23, 1.14, 1.11, 0.187, 0.123, 0.0715, 0.040, 0.0217, 0.0137, 0.0012, -0.28, -1};
        if (channelBitrate <= rates[0]) {
            return (float) parameters[0];
        }
        for (var i = 1; i < rates.length; i++) {
            if (channelBitrate <= rates[i]) {
                var t = (channelBitrate - rates[i - 1]) / (rates[i] - rates[i - 1]);
                return (float) (parameters[i - 1] + t * (parameters[i] - parameters[i - 1]));
            }
        }
        return -1;
    }
}
