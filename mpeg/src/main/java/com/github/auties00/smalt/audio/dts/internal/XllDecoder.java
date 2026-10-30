package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;

/**
 * Decodes the XLL lossless extension (ETSI TS 102 114 clause 8): the residuals of every channel set, inverse fixed
 * and adaptive prediction, pairwise channel decorrelation, the MSB and LSB parts, the reversal of the embedded
 * downmixes, and the sum with the core where the residual is coded against it.
 *
 * <p>Instances are not thread-safe.
 */
final class XllDecoder {
    /**
     * The XLL sync word.
     */
    static final int SYNC = 0x41A29547;

    /**
     * The reflection coefficient table of the adaptive predictor (clause 8.5.4), Q16.
     */
    private static final int[] REFLECTION = {0, 3070, 5110, 7140, 9156, 11154, 13132, 15085, 17010, 18904, 20764,
            22588, 24373, 26117, 27818, 29474, 31085, 32648, 34164, 35631, 37049, 38418, 39738, 41008, 42230, 43404,
            44530, 45609, 46642, 47630, 48575, 49477, 50337, 51157, 51937, 52681, 53387, 54059, 54697, 55302, 55876,
            56421, 56937, 57426, 57888, 58326, 58741, 59132, 59502, 59852, 60182, 60494, 60789, 61066, 61328, 61576,
            61809, 62029, 62236, 62431, 62615, 62788, 62951, 63105, 63250, 63386, 63514, 63635, 63749, 63855, 63956,
            64051, 64140, 64224, 64302, 64376, 64446, 64512, 64573, 64631, 64686, 64737, 64785, 64830, 64873, 64913,
            64950, 64986, 65019, 65050, 65079, 65107, 65133, 65157, 65180, 65202, 65222, 65241, 65259, 65275, 65291,
            65306, 65320, 65333, 65345, 65357, 65368, 65378, 65387, 65396, 65405, 65413, 65420, 65427, 65434, 65440,
            65446, 65451, 65456, 65461, 65466, 65470, 65474, 65478, 65481, 65485, 65488, 65491};

    /**
     * The largest number of channel sets.
     */
    private static final int MAX_SETS = 16;

    /**
     * The largest number of samples per channel and frame.
     */
    private static final int MAX_FRAME = 1 << 13;

    /**
     * The reader of the extension.
     */
    private final MsbBitReader reader = new MsbBitReader(new byte[0]);

    /**
     * The channel set headers of the frame.
     */
    private final ChannelSetHeader[] sets = new ChannelSetHeader[MAX_SETS];

    /**
     * The size in bytes of each segment of each channel set, by frequency band.
     */
    private final int[][][] naviSizes = new int[4][][];

    /**
     * The decoded samples by channel.
     */
    private final int[][] samples = new int[64][MAX_FRAME];

    /**
     * The least significant bits of the scalable channels, by channel.
     */
    private final int[][] lsbs = new int[64][MAX_FRAME];

    /**
     * Scratch space of the prediction.
     */
    private final long[] work = new long[8];

    /**
     * The channel buffers of a set in their original order, while pairwise decorrelation is undone.
     */
    private final int[][] reordered = new int[16][];

    /**
     * The core samples of the channel being completed, with the embedded downmixes undone.
     */
    private final int[] coreSamples = new int[MAX_FRAME];

    /**
     * The undo coefficients of the channels of the channel set being reversed, Q15.
     */
    private final int[] undoCoefficients = new int[16];

    /**
     * The output samples by output channel.
     */
    private float[][] output = new float[0][];

    /**
     * The speaker bit by output channel.
     */
    private int[] outputMasks = new int[0];

    /**
     * The number of samples per channel of the last frame.
     */
    private int outputSamples;

    /**
     * The sample rate of the last frame.
     */
    private int outputRate;

    /**
     * The number of channel sets of the frame.
     */
    private int sets0;

    /**
     * The number of segments of the frame.
     */
    private int segments;

    /**
     * The number of samples per channel of each segment.
     */
    private int segmentSamples;

    /**
     * The width of the segment size fields.
     */
    private int bitsForSize;

    /**
     * The CRC presence of the frequency bands ({@code nBandDataCRCEn}).
     */
    private int bandCrc;

    /**
     * Whether the least significant bits are coded apart ({@code bScalableLSBs}).
     */
    private boolean scalableLsbs;

    /**
     * The width of the channel masks.
     */
    private int bitsForMask;

    /**
     * The fixed width of the least significant bits, 0 when signaled per channel.
     */
    private int fixedLsbWidth;

    /**
     * Constructs a decoder.
     */
    XllDecoder() {
        for (var i = 0; i < MAX_SETS; i++) {
            sets[i] = new ChannelSetHeader();
        }
    }

    /**
     * Decodes an XLL component.
     *
     * @param data the bytes
     * @param offset the index of the component
     * @param length the component size
     * @param substream the extension substream holding the asset descriptor
     * @param core the core decoder with its frame decoded and synthesized samples available, or {@code null}
     * @throws MediaException.Malformed if the component breaks the syntax
     * @throws MediaException.Unsupported if the component needs a feature this decoder does not implement
     */
    void decode(byte[] data, int offset, int length, ExtensionSubstream substream, CoreDecoder core) {
        if (length < 16 || CoreDecoder.readInt(data, offset) != SYNC) {
            throw new MediaException.Malformed("missing DTS XLL sync word");
        }
        reader.reset(data, offset, length);
        reader.skip(32);
        var version = reader.read(4) + 1;
        if (version > 1) {
            throw new MediaException.Unsupported("DTS", "XLL version " + version + " is not supported");
        }
        var headerSize = reader.read(8) + 1;
        var frameSizeBits = reader.read(5) + 1;
        var frameSize = reader.read(frameSizeBits) + 1;
        if (frameSize > length || headerSize > frameSize) {
            throw new MediaException.Malformed("DTS XLL frame of " + frameSize + " bytes in " + length);
        }
        if (!DtsCrc.check(data, offset + 4, headerSize - 4)) {
            throw new MediaException.Malformed("DTS XLL header CRC mismatch");
        }
        sets0 = reader.read(4) + 1;
        segments = 1 << reader.read(4);
        segmentSamples = 1 << reader.read(4);
        bitsForSize = reader.read(5) + 1;
        bandCrc = reader.read(2);
        scalableLsbs = reader.readFlag();
        bitsForMask = reader.read(5) + 1;
        fixedLsbWidth = scalableLsbs ? reader.read(4) : 0;
        if (segments * segmentSamples > MAX_FRAME) {
            throw new MediaException.Malformed("DTS XLL frame of " + segments * segmentSamples + " samples");
        }
        var position = offset + headerSize;
        var channelBase = 0;
        for (var s = 0; s < sets0; s++) {
            var set = sets[s];
            if (position + 2 > offset + frameSize) {
                throw new MediaException.Malformed("DTS XLL channel set header overruns the frame");
            }
            var size = ((data[position] & 0xFF) << 2 | (data[position + 1] & 0xFF) >> 6) + 1;
            if (position + size > offset + frameSize || !DtsCrc.check(data, position, size)) {
                throw new MediaException.Malformed("DTS XLL channel set header CRC mismatch");
            }
            reader.reset(data, position, size);
            set.read(reader, substream, s, channelBase, this);
            channelBase += set.channels;
            position += size;
        }
        if (channelBase > samples.length) {
            throw new MediaException.Malformed("DTS XLL frame codes " + channelBase + " channels");
        }
        var bands = 0;
        for (var s = 0; s < sets0; s++) {
            bands = Math.max(bands, sets[s].bands);
        }
        position = readNavigation(data, position, offset + frameSize, bands);
        if (bands > 1) {
            // TODO: frequency bands above 96 kHz need the band synthesis filter, which ETSI TS 102 114 does not
            // specify.
            throw new MediaException.Unsupported("DTS", "XLL frequency bands above 96 kHz are not supported");
        }
        if (core != null) {
            core.synthesize(false);
        }
        for (var band = 0; band < bands; band++) {
            for (var segment = 0; segment < segments; segment++) {
                for (var s = 0; s < sets0; s++) {
                    var set = sets[s];
                    if (set.bands <= band) {
                        continue;
                    }
                    var size = naviSizes[band][segment][s];
                    if (position + size > offset + frameSize) {
                        throw new MediaException.Malformed("DTS XLL band data overruns the frame");
                    }
                    readBandData(data, position, size, set, segment);
                    position += size;
                }
            }
        }
        var frameSamples = segments * segmentSamples;
        for (var s = 0; s < sets0; s++) {
            reconstruct(sets[s], frameSamples);
        }
        addCore(core, frameSamples);
        render(frameSamples, channelBase);
    }

    /**
     * Reads the navigation index: the size of each channel set's data in each band and segment.
     *
     * @return the position of the first band data
     */
    private int readNavigation(byte[] data, int position, int end, int bands) {
        reader.reset(data, position, end - position);
        for (var band = 0; band < bands; band++) {
            if (naviSizes[band] == null || naviSizes[band].length < segments) {
                naviSizes[band] = new int[Math.max(segments, 16)][MAX_SETS];
            }
            for (var segment = 0; segment < segments; segment++) {
                for (var s = 0; s < sets0; s++) {
                    if (sets[s].bands > band) {
                        naviSizes[band][segment][s] = reader.read(bitsForSize) + 1;
                    }
                }
            }
        }
        reader.alignToByte();
        reader.skip(16);
        reader.checkNotOverrun();
        var size = (int) (reader.bitPosition() >>> 3);
        if (!DtsCrc.check(data, position, size)) {
            throw new MediaException.Malformed("DTS XLL navigation index CRC mismatch");
        }
        return position + size;
    }

    /**
     * Reads the band 0 data of one channel set in one segment (clauses 8.5.2.2 to 8.5.2.5).
     */
    private void readBandData(byte[] data, int position, int size, ChannelSetHeader set, int segment) {
        var lsbSize = scalableLsbs ? set.lsbSize : 0;
        var msbSize = size - lsbSize;
        if (msbSize < 0) {
            throw new MediaException.Malformed("DTS XLL LSB part larger than its band data");
        }
        reader.reset(data, position, msbSize);
        if (segment == 0 || !reader.readFlag()) {
            set.segmentType = reader.read(1);
            var parameterSets = set.segmentType == 0 ? set.channels : 1;
            for (var p = 0; p < parameterSets; p++) {
                set.rice[p] = reader.readFlag();
                set.auxBits[p] = 0;
                if (set.segmentType == 0 && set.rice[p] && reader.readFlag()) {
                    set.auxBits[p] = reader.read(set.bitsForAbit) + 1;
                }
            }
            for (var p = 0; p < parameterSets; p++) {
                if (segment == 0) {
                    set.abit0[p] = reader.read(set.bitsForAbit);
                    if (!set.rice[p] && set.abit0[p] > 0) {
                        set.abit0[p]++;
                    }
                }
                set.abit[p] = reader.read(set.bitsForAbit);
                if (!set.rice[p] && set.abit[p] > 0) {
                    set.abit[p]++;
                }
            }
        }
        var base = segment * segmentSamples;
        var lociBits = Integer.numberOfTrailingZeros(segmentSamples);
        for (var ch = 0; ch < set.channels; ch++) {
            var p = set.segmentType == 0 ? ch : 0;
            var part0 = segment == 0 ? (set.segmentType == 0 ? set.adaptiveOrder[0][ch] : set.highestOrder[0]) : 0;
            var buffer = samples[set.firstChannel + ch];
            var index = base;
            if (!set.rice[p]) {
                for (var n = 0; n < part0; n++) {
                    buffer[index++] = set.abit0[p] > 0 ? signed(reader.read(set.abit0[p])) : 0;
                }
                for (var n = part0; n < segmentSamples; n++) {
                    buffer[index++] = set.abit[p] > 0 ? signed(reader.read(set.abit[p])) : 0;
                }
            } else {
                for (var n = 0; n < part0; n++) {
                    buffer[index++] = signed(rice(set.abit0[p]));
                }
                var isolated = buffer;
                Arrays.fill(buffer, index, base + segmentSamples, 0);
                if (set.auxBits[p] > 0) {
                    var count = reader.read(lociBits);
                    for (var n = 0; n < count; n++) {
                        var location = reader.read(lociBits);
                        if (location < segmentSamples - part0) {
                            isolated[index + location] = 1;
                        }
                    }
                }
                for (var n = part0; n < segmentSamples; n++, index++) {
                    var value = isolated[index] == 0 ? rice(set.abit[p]) : reader.read(set.auxBits[p]);
                    buffer[index] = signed(value);
                }
            }
        }
        reader.checkNotOverrun();
        if (lsbSize > 0) {
            var lsbStart = position + size - lsbSize;
            reader.reset(data, lsbStart, lsbSize);
            for (var ch = 0; ch < set.channels; ch++) {
                var bits = set.scalableBits[0][ch];
                var buffer = lsbs[set.firstChannel + ch];
                for (var n = 0; n < segmentSamples; n++) {
                    buffer[base + n] = bits > 0 ? reader.read(bits) : 0;
                }
            }
            reader.checkNotOverrun();
        }
    }

    /**
     * Reads a Rice code with a binary part of {@code k} bits.
     */
    private int rice(int k) {
        var unary = 0;
        while (reader.read(1) == 0) {
            if (++unary > 32 || reader.overrun()) {
                throw new MediaException.Malformed("invalid DTS XLL Rice code");
            }
        }
        return k > 0 ? unary << k | reader.read(k) : unary;
    }

    /**
     * Maps an entropy coded unsigned value back to a signed one (C.8).
     *
     * @param value the unsigned value
     * @return the signed value
     */
    private static int signed(int value) {
        return (value & 1) != 0 ? -(value >>> 1) - 1 : value >>> 1;
    }

    /**
     * Runs the inverse fixed and adaptive prediction, the pairwise decorrelation, the channel reordering and the
     * MSB/LSB assembly over band 0 of one channel set.
     */
    private void reconstruct(ChannelSetHeader set, int frameSamples) {
        for (var ch = 0; ch < set.channels; ch++) {
            var buffer = samples[set.firstChannel + ch];
            var fixed = set.fixedOrder[0][ch];
            if (fixed > 0) {
                Arrays.fill(work, 0);
                for (var n = 0; n < frameSamples; n++) {
                    work[0] = buffer[n];
                    for (var order = 0; order < fixed; order++) {
                        work[2 * order + 2] = work[2 * order] + work[2 * order + 1];
                        work[2 * order + 1] = work[2 * order + 2];
                    }
                    buffer[n] = (int) work[2 * fixed];
                }
            }
            var order = set.adaptiveOrder[0][ch];
            if (order > 0) {
                var coefficients = set.predictor(0, ch);
                for (var n = order; n < frameSamples; n++) {
                    long sum = 0;
                    for (var k = 0; k < order; k++) {
                        sum += (long) buffer[n - 1 - k] * coefficients[k];
                    }
                    var prediction = (int) ((sum + (1 << 15)) >> 16);
                    prediction = Math.clamp(prediction, -(1 << 23), (1 << 23) - 1);
                    buffer[n] -= prediction;
                }
            }
        }
        if (set.pairwise[0]) {
            for (var pair = 0; pair < set.channels >> 1; pair++) {
                var coefficient = set.pairCoefficients[0][pair];
                if (coefficient != 0) {
                    var basis = samples[set.firstChannel + 2 * pair];
                    var target = samples[set.firstChannel + 2 * pair + 1];
                    for (var n = 0; n < frameSamples; n++) {
                        target[n] += (int) (((long) coefficient * basis[n] + 4) >> 3);
                    }
                }
            }
            var ordered = reordered;
            for (var ch = 0; ch < set.channels; ch++) {
                ordered[set.originalOrder[0][ch]] = samples[set.firstChannel + ch];
            }
            for (var ch = 0; ch < set.channels; ch++) {
                samples[set.firstChannel + ch] = ordered[ch];
            }
        }
        for (var ch = 0; ch < set.channels; ch++) {
            var adjust = set.widthAdjust[0][ch];
            int shift;
            if (fixedLsbWidth == 0) {
                var bits = set.scalableBits[0][ch];
                shift = (adjust > 0 && bits > 0 ? bits - 1 : bits) + adjust;
            } else {
                shift = fixedLsbWidth;
            }
            if (scalableLsbs && shift > 0) {
                var buffer = samples[set.firstChannel + ch];
                var low = lsbs[set.firstChannel + ch];
                for (var n = 0; n < frameSamples; n++) {
                    buffer[n] = (buffer[n] << shift) + (low[n] << adjust);
                }
            }
        }
    }

    /**
     * Scales every channel to its storage width and adds the core to the channels coded against it, from the last
     * channel set to the first. The core carries the downmix the encoder embedded into it, so before it is added to
     * a channel the downmixes of the later channel sets into that channel are undone on it (C.9): the core sample
     * is multiplied by the inverse downmix scale and the channels of the later set, times their undo coefficients,
     * are subtracted. A channel coded alone already holds its own signal.
     *
     * @param core the core decoder, or {@code null} without core
     * @param frameSamples the number of samples per channel of the frame
     * @throws MediaException.Malformed if a channel is coded against a missing core
     */
    private void addCore(CoreDecoder core, int frameSamples) {
        for (var s = sets0 - 1; s >= 0; s--) {
            var set = sets[s];
            var masks = set.channelMasks();
            var full = 1 << (set.bitWidth - 1);
            var shift = set.bitWidth - set.bitResolution;
            for (var ch = 0; ch < set.channels; ch++) {
                var buffer = samples[set.firstChannel + ch];
                if ((set.residualType >> ch & 1) != 0) {
                    if (shift > 0) {
                        for (var n = 0; n < frameSamples; n++) {
                            buffer[n] <<= shift;
                        }
                    }
                    continue;
                }
                if (core == null) {
                    throw new MediaException.Malformed("DTS XLL residual coded against a missing core");
                }
                var lossy = core.channelOutput(masks[ch]);
                var base = coreSamples;
                for (var n = 0; n < frameSamples; n++) {
                    base[n] = Math.clamp(Math.round(lossy[n] * full), -full, full - 1);
                }
                for (var t = s + 1; t < sets0; t++) {
                    undoDownmix(sets[t], t, s, ch, base, frameSamples);
                }
                for (var n = 0; n < frameSamples; n++) {
                    buffer[n] = (buffer[n] << shift) + base[n];
                }
            }
        }
    }

    /**
     * Undoes on the core samples of one channel the downmix a later channel set embedded into it.
     *
     * @param source the later channel set
     * @param sourceIndex the index of the later channel set
     * @param targetIndex the index of the channel set of the channel
     * @param channel the channel within its set
     * @param base the core samples of the channel, updated
     * @param frameSamples the number of samples per channel of the frame
     */
    private void undoDownmix(ChannelSetHeader source, int sourceIndex, int targetIndex, int channel, int[] base,
                             int frameSamples) {
        if (!source.downmixEmbedded || !source.downmixCoefficientsPresent || source.replacementSet != 0) {
            return;
        }
        var column = 0;
        for (var u = 0; u < targetIndex; u++) {
            if (source.hierarchical || sets[u].primary) {
                column += sets[u].channels;
            }
        }
        if (!(source.hierarchical || sets[targetIndex].primary)) {
            return;
        }
        column += channel;
        if (column >= source.downmixColumns) {
            return;
        }
        var inverse = source.inverseScale(column);
        var undo = undoCoefficients;
        for (var k = 0; k < source.channels; k++) {
            undo[k] = source.undoCoefficient(k, column, inverse);
        }
        for (var n = 0; n < frameSamples; n++) {
            long value = ((long) base[n] * inverse + (1 << 15)) >> 16;
            for (var k = 0; k < source.channels; k++) {
                value -= ((long) undo[k] * samples[source.firstChannel + k][n] + (1 << 14)) >> 15;
            }
            base[n] = (int) value;
        }
    }

    /**
     * Converts every channel to float.
     *
     * @param frameSamples the number of samples per channel of the frame
     * @param channels the number of channels of the frame
     */
    private void render(int frameSamples, int channels) {
        if (output.length < channels) {
            output = new float[channels][];
            outputMasks = new int[channels];
        }
        if (outputMasks.length != countOutput()) {
            outputMasks = new int[countOutput()];
        }
        var out = 0;
        outputRate = 0;
        for (var s = 0; s < sets0; s++) {
            var set = sets[s];
            if (set.skip) {
                continue;
            }
            outputRate = Math.max(outputRate, set.sampleRate);
            var masks = set.channelMasks();
            for (var ch = 0; ch < set.channels; ch++) {
                var buffer = samples[set.firstChannel + ch];
                var full = 1 << (set.bitWidth - 1);
                if (output[out] == null || output[out].length < frameSamples) {
                    output[out] = new float[Math.max(frameSamples, MAX_FRAME)];
                }
                var target = output[out];
                for (var n = 0; n < frameSamples; n++) {
                    target[n] = buffer[n] / (float) full;
                }
                outputMasks[out] = masks[ch];
                out++;
            }
        }
        outputSamples = frameSamples;
    }

    /**
     * {@return the number of output channels of the frame}
     */
    private int countOutput() {
        var count = 0;
        for (var s = 0; s < sets0; s++) {
            if (!sets[s].skip) {
                count += sets[s].channels;
            }
        }
        return count;
    }

    /**
     * {@return the number of samples per channel of the last frame}
     */
    int outputSamples() {
        return outputSamples;
    }

    /**
     * {@return the sample rate of the last frame}
     */
    int outputRate() {
        return outputRate;
    }

    /**
     * {@return the speaker bit of each output channel}
     */
    int[] masks() {
        return outputMasks;
    }

    /**
     * {@return the samples of an output channel}
     *
     * @param ch the channel
     */
    float[] output(int ch) {
        return output[ch];
    }

    /**
     * Clears the prediction history, as at the start of a stream.
     */
    void reset() {
    }

    /**
     * {@return the width of the channel masks}
     */
    int bitsForMask() {
        return bitsForMask;
    }

    /**
     * {@return whether the least significant bits are coded apart}
     */
    boolean scalableLsbs() {
        return scalableLsbs;
    }

    /**
     * {@return the width of the segment size fields}
     */
    int bitsForSize() {
        return bitsForSize;
    }

    /**
     * {@return the number of channel sets of the frame}
     */
    int setCount() {
        return sets0;
    }

    /**
     * {@return a channel set header}
     *
     * @param index the channel set
     */
    ChannelSetHeader set(int index) {
        return sets[index];
    }

    /**
     * The sub-header of one XLL channel set (Table 8-4).
     */
    static final class ChannelSetHeader {
        /**
         * The number of channels of the set.
         */
        int channels;

        /**
         * The index of the first channel of the set among the channels of the frame.
         */
        int firstChannel;

        /**
         * The residual type by channel: a set bit codes the channel alone, a clear bit against the core.
         */
        int residualType;

        /**
         * The resolution of the original samples, in bits.
         */
        int bitResolution;

        /**
         * The storage width of the samples, in bits.
         */
        int bitWidth;

        /**
         * The sample rate of the set.
         */
        int sampleRate;

        /**
         * The sample rate interpolation factor code.
         */
        int interpolation;

        /**
         * The replacement set index, 0 for none.
         */
        int replacementSet;

        /**
         * Whether the set is a replacement set left out of the output.
         */
        boolean skip;

        /**
         * Whether the set is the primary channel set.
         */
        boolean primary;

        /**
         * Whether the set carries downmix coefficients.
         */
        boolean downmixCoefficientsPresent;

        /**
         * Whether the encoder embedded the downmix of the set.
         */
        boolean downmixEmbedded;

        /**
         * The downmix type of the primary set.
         */
        int downmixType;

        /**
         * Whether the set is part of the hierarchy.
         */
        boolean hierarchical;

        /**
         * The 9-bit downmix codes, per target channel the scale then the coefficient of each channel.
         */
        int[] downmixCoefficients;

        /**
         * The number of target channels of the downmix.
         */
        int downmixColumns;

        /**
         * The channel mask of the set, single speakers of Table 6-22.
         */
        int mask;

        /**
         * The number of frequency bands of the set.
         */
        int bands;

        /**
         * The size in bytes of the least significant bits section.
         */
        int lsbSize;

        /**
         * The width of the bit allocation fields.
         */
        int bitsForAbit;

        /**
         * Whether pairwise decorrelation is on, by band.
         */
        final boolean[] pairwise = new boolean[4];

        /**
         * The original channel order by band and coded channel.
         */
        final int[][] originalOrder = new int[4][16];

        /**
         * The pairwise decorrelation coefficients by band and channel pair.
         */
        final int[][] pairCoefficients = new int[4][8];

        /**
         * The adaptive predictor order by band and channel.
         */
        final int[][] adaptiveOrder = new int[4][16];

        /**
         * The highest adaptive predictor order of the band.
         */
        final int[] highestOrder = new int[4];

        /**
         * The fixed predictor order by band and channel.
         */
        final int[][] fixedOrder = new int[4][16];

        /**
         * The quantized reflection coefficients by band, channel and order.
         */
        final int[][][] reflection = new int[4][16][16];

        /**
         * The number of least significant bits coded apart, by band and channel.
         */
        final int[][] scalableBits = new int[4][16];

        /**
         * The width adjustment of the least significant bits, by band and channel.
         */
        final int[][] widthAdjust = new int[4][16];

        /**
         * The segment type of the current segment: 1 when its channels share one code parameter set.
         */
        int segmentType;

        /**
         * Whether each channel uses Rice codes rather than binary codes.
         */
        final boolean[] rice = new boolean[16];

        /**
         * The width of the binary codes of the isolated samples of hybrid Rice coding by channel, 0 without.
         */
        final int[] auxBits = new int[16];

        /**
         * The code parameter of the samples before the predictor order of the first segment, by channel.
         */
        final int[] abit0 = new int[16];

        /**
         * The code parameter of the rest of the segment by channel.
         */
        final int[] abit = new int[16];

        /**
         * The direct form predictor coefficients the reflection coefficients convert to.
         */
        private final int[] coefficients = new int[16];

        /**
         * The speaker bit by channel, which {@link #channelMasks()} fills.
         */
        private final int[] channelMasks = new int[16];

        /**
         * Reads the sub-header.
         */
        void read(MsbBitReader reader, ExtensionSubstream substream, int index, int channelBase, XllDecoder xll) {
            reader.skip(10);
            channels = reader.read(4) + 1;
            firstChannel = channelBase;
            residualType = reader.read(channels);
            bitResolution = reader.read(5) + 1;
            bitWidth = reader.read(5) + 1;
            if (bitWidth > 24 || bitResolution > bitWidth) {
                throw new MediaException.Unsupported("DTS", "XLL storage width " + bitWidth + " is not supported");
            }
            sampleRate = ExtensionSubstream.SAMPLE_RATES[reader.read(4)];
            interpolation = reader.read(2);
            replacementSet = reader.read(2);
            skip = false;
            if (replacementSet > 0) {
                skip = !reader.readFlag();
            }
            var oneToOne = substream.oneToOne;
            primary = true;
            downmixEmbedded = false;
            hierarchical = true;
            downmixCoefficientsPresent = false;
            mask = 0;
            if (oneToOne) {
                primary = reader.readFlag();
                downmixCoefficientsPresent = reader.readFlag();
                if (downmixCoefficientsPresent) {
                    downmixEmbedded = reader.readFlag();
                }
                if (downmixCoefficientsPresent && primary) {
                    downmixType = reader.read(3);
                }
                hierarchical = reader.readFlag();
                if (downmixCoefficientsPresent) {
                    int count;
                    if (primary) {
                        downmixColumns = switch (downmixType) {
                            case 0 -> 1;
                            case 1, 2 -> 2;
                            case 3, 4 -> 3;
                            case 5, 6 -> 4;
                            default -> 0;
                        };
                        count = channels * downmixColumns;
                    } else {
                        var columns = 0;
                        for (var t = 0; t < index; t++) {
                            var other = xll.set(t);
                            if (hierarchical || other.primary) {
                                columns += other.channels;
                            }
                        }
                        downmixColumns = columns;
                        count = (channels + 1) * columns;
                    }
                    if (downmixCoefficients == null || downmixCoefficients.length < count) {
                        downmixCoefficients = new int[count];
                    }
                    for (var i = 0; i < count; i++) {
                        downmixCoefficients[i] = reader.read(9);
                    }
                }
                if (reader.readFlag()) {
                    mask = reader.read(xll.bitsForMask());
                } else {
                    reader.skip(25L * channels);
                }
            } else {
                if (reader.readFlag()) {
                    var bits = 6 + 2 * reader.read(3);
                    var configurations = reader.read(2) + 1;
                    for (var c = 0; c < configurations; c++) {
                        var active = reader.read(channels);
                        var speakers = reader.read(6) + 1;
                        var maskEnabled = reader.readFlag();
                        if (maskEnabled) {
                            reader.skip(xll.bitsForMask());
                        }
                        for (var s = 0; s < speakers; s++) {
                            if (!maskEnabled) {
                                reader.skip(25);
                            }
                            reader.skip((long) bits * Integer.bitCount(active));
                        }
                    }
                }
            }
            bands = sampleRate > 96000 ? (reader.readFlag() ? 4 : 2) : 1;
            var orderBits = 0;
            while (1 << orderBits < channels) {
                orderBits++;
            }
            for (var band = 0; band < bands; band++) {
                pairwise[band] = reader.readFlag();
                if (pairwise[band]) {
                    for (var ch = 0; ch < channels; ch++) {
                        originalOrder[band][ch] = reader.read(orderBits);
                        if (originalOrder[band][ch] >= channels) {
                            throw new MediaException.Malformed("invalid DTS XLL channel order");
                        }
                    }
                    for (var pair = 0; pair < channels >> 1; pair++) {
                        pairCoefficients[band][pair] = reader.readFlag() ? signed(reader.read(7)) : 0;
                    }
                }
                highestOrder[band] = 0;
                for (var ch = 0; ch < channels; ch++) {
                    adaptiveOrder[band][ch] = reader.read(4);
                    highestOrder[band] = Math.max(highestOrder[band], adaptiveOrder[band][ch]);
                }
                for (var ch = 0; ch < channels; ch++) {
                    fixedOrder[band][ch] = adaptiveOrder[band][ch] == 0 ? reader.read(2) : 0;
                }
                for (var ch = 0; ch < channels; ch++) {
                    for (var n = 0; n < adaptiveOrder[band][ch]; n++) {
                        var value = signed(reader.read(8));
                        if (Math.abs(value) >= REFLECTION.length) {
                            throw new MediaException.Malformed("invalid DTS XLL reflection coefficient");
                        }
                        reflection[band][ch][n] = value;
                    }
                }
                if (band == 0) {
                    lsbSize = xll.scalableLsbs() ? reader.read(xll.bitsForSize()) : 0;
                    bitsForAbit = bitWidth > 16 ? 5 : bitWidth > 8 ? 4 : 3;
                    if (xll.setCount() > 1 && bitsForAbit < 5) {
                        bitsForAbit++;
                    }
                    for (var ch = 0; ch < channels; ch++) {
                        scalableBits[0][ch] = xll.scalableLsbs() ? reader.read(4) : 0;
                    }
                    for (var ch = 0; ch < channels; ch++) {
                        widthAdjust[0][ch] = xll.scalableLsbs() ? reader.read(4) : 0;
                    }
                } else {
                    if (downmixEmbedded) {
                        reader.skip(1);
                    }
                    var split = reader.readFlag();
                    if (split) {
                        reader.skip(xll.bitsForSize());
                    }
                    for (var ch = 0; ch < channels; ch++) {
                        scalableBits[band][ch] = split ? reader.read(4) : 0;
                    }
                    var scalable = reader.readFlag();
                    for (var ch = 0; ch < channels; ch++) {
                        widthAdjust[band][ch] = scalable ? reader.read(4) : 0;
                    }
                }
            }
            reader.checkNotOverrun();
        }

        /**
         * {@return the direct form predictor coefficients of a channel, Q16, newest sample first}
         */
        int[] predictor(int band, int ch) {
            var order = adaptiveOrder[band][ch];
            Arrays.fill(coefficients, 0);
            for (var o = 1; o <= order; o++) {
                var index = reflection[band][ch][o - 1];
                var rc = index >= 0 ? REFLECTION[index] : -REFLECTION[-index];
                for (var n = 1; n <= o >> 1; n++) {
                    var forward = coefficients[n - 1];
                    var backward = coefficients[o - 1 - n];
                    coefficients[n - 1] = forward + (int) (((long) rc * backward + (1 << 15)) >> 16);
                    coefficients[o - 1 - n] = backward + (int) (((long) rc * forward + (1 << 15)) >> 16);
                }
                coefficients[o - 1] = rc;
            }
            return coefficients;
        }

        /**
         * {@return the DTS speaker bit of each channel of the set, from its channel mask ({@code nChMask}), whose bits
         * are the single speakers of Table 6-22 in channel order}
         */
        int[] channelMasks() {
            var ch = 0;
            for (var bit = 0; bit < 32 && ch < channels; bit++) {
                if ((mask >> bit & 1) != 0) {
                    channelMasks[ch++] = 1 << bit;
                }
            }
            return channelMasks;
        }

        /**
         * {@return the inverse downmix scale of a target channel, Q16 (C.9, {@code InvDmixScale})}
         *
         * <p>The coefficients of a channel set that is not primary are coded target channel by target channel:
         * the scale, then the coefficient of each channel of the set.
         *
         * @param column the target channel, counted over the channel sets the set mixes into
         * @throws MediaException.Malformed if the scale index is outside -40 dB to 0 dB
         */
        int inverseScale(int column) {
            var code = downmixCoefficients[column * (channels + 1)];
            var index = (code & 0xFF) - 1;
            if (index < 40 || index > 240) {
                throw new MediaException.Malformed("invalid DTS XLL downmix scale index " + index);
            }
            var sign = (code & 0x100) != 0 ? 1 : -1;
            return sign * DtsCoreTables.INV_DMIX[index - 40];
        }

        /**
         * {@return the product of the inverse scale and the downmix coefficient of one channel into a target
         * channel, Q15 (C.9, {@code UndoDmixScale})}
         *
         * @param channel the channel of the set
         * @param column the target channel
         * @param inverse the inverse scale of the target channel, Q16
         * @throws MediaException.Malformed if the coefficient index is outside the table
         */
        int undoCoefficient(int channel, int column, int inverse) {
            var code = downmixCoefficients[column * (channels + 1) + 1 + channel];
            if ((code & 0xFF) == 0) {
                return 0;
            }
            var index = (code & 0xFF) - 1;
            if (index > 240) {
                throw new MediaException.Malformed("invalid DTS XLL downmix coefficient index " + index);
            }
            var coefficient = ((code & 0x100) != 0 ? 1 : -1) * DtsCoreTables.DMIX[index];
            return (int) (((long) inverse * coefficient + (1 << 15)) >> 16);
        }
    }
}
