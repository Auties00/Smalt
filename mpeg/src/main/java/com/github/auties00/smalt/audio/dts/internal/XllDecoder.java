package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;

/**
 * Decodes the XLL lossless extension (ETSI TS 102 114 clause 8): the residuals of every channel set, inverse fixed
 * and adaptive prediction, pairwise channel decorrelation, the MSB and LSB parts, the reversal of the embedded
 * downmixes, the sum with the core where the residual is coded against it, and the joining of the two frequency bands
 * of the rates above 96 kHz ({@link XllBandSynthesis}).
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
     * The largest number of frequency bands this decoder joins.
     */
    private static final int BANDS = 2;

    /**
     * The highest sample rate this decoder decodes.
     */
    private static final int MAX_RATE = 192000;

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
     * The decoded samples by frequency band and channel; band 1 is allocated by the first frame that has it.
     */
    private final int[][][] samples = new int[BANDS][][];

    /**
     * The least significant bits of the scalable channels, by frequency band and channel.
     */
    private final int[][][] lsbs = new int[BANDS][][];

    /**
     * The decimator history by channel.
     */
    private final int[][] history = new int[64][XllBandSynthesis.HISTORY];

    /**
     * The samples of a channel with its frequency bands joined.
     */
    private int[] joined;

    /**
     * Scratch space of the prediction.
     */
    private final long[] work = new long[8];

    /**
     * The channel buffers of a set in their original order, while pairwise decorrelation is undone.
     */
    private final int[][] reordered = new int[16][];

    /**
     * The width of the fixed-point core samples.
     */
    private static final int CORE_BITS = 24;

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
     * The index after the last component read.
     */
    private int frameEnd;

    /**
     * The number of channels the channel sets of the last component code.
     */
    private int channelBase;

    /**
     * Constructs a decoder.
     */
    XllDecoder() {
        for (var i = 0; i < MAX_SETS; i++) {
            sets[i] = new ChannelSetHeader();
        }
        samples[0] = new int[64][MAX_FRAME];
        lsbs[0] = new int[64][MAX_FRAME];
    }

    /**
     * Decodes an XLL component.
     *
     * @param data the bytes
     * @param offset the index of the component
     * @param length the component size
     * @param substream the extension substream holding the asset descriptor
     * @param core the core decoder with its frame decoded, or {@code null}
     * @throws MediaException.Malformed if the component breaks the syntax
     * @throws MediaException.Unsupported if the component needs a feature this decoder does not implement
     */
    void decode(byte[] data, int offset, int length, ExtensionSubstream substream, CoreDecoder core) {
        var position = readHeaders(data, offset, length, substream);
        var bands = 0;
        for (var s = 0; s < sets0; s++) {
            bands = Math.max(bands, sets[s].bands);
        }
        checkBands(bands);
        position = readNavigation(data, position, frameEnd, bands);
        if (core != null) {
            if (codesAgainstCore() && !core.losslessReference()) {
                // TODO: a core with a 128 times interpolated LFE channel has no oracle: ETSI TS 102 114 does not give
                // the fixed-point reconstruction and ffmpeg refuses it in that mode.
                throw new MediaException.Unsupported("DTS", "XLL over a core with a 128 times interpolated LFE channel");
            }
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
                    if (position + size > frameEnd) {
                        throw new MediaException.Malformed("DTS XLL band data overruns the frame");
                    }
                    readBandData(data, position, size, set, band, segment);
                    position += size;
                }
            }
        }
        var frameSamples = segments * segmentSamples;
        for (var s = 0; s < sets0; s++) {
            for (var band = 0; band < bands; band++) {
                reconstruct(sets[s], band, frameSamples);
            }
        }
        addCore(core, frameSamples);
        render(frameSamples, channelBase);
    }

    /**
     * {@return the ratio of the rate of band 0 of an XLL component to the rate of a core, when the component has
     * frequency bands this decoder joins and every channel set has them at one rate, the rate of the core or twice it;
     * else {@code 0}}
     *
     * <p>A component with no channel coded against the core is included: the frames after it may need the history its
     * core frame leaves in the fixed-point reconstruction.
     *
     * @param data the bytes
     * @param offset the index of the component
     * @param length the component size
     * @param substream the extension substream holding the asset descriptor
     * @param coreRate the sample rate of the core
     * @throws MediaException.Malformed if the component breaks the syntax
     * @throws MediaException.Unsupported if the component needs a feature this decoder does not implement
     */
    int coreRatio(byte[] data, int offset, int length, ExtensionSubstream substream, int coreRate) {
        readHeaders(data, offset, length, substream);
        var bands = sets[0].bands;
        if (bands > BANDS || sets[0].sampleRate > MAX_RATE) {
            return 0;
        }
        var ratio = sets[0].sampleRate / bands == 2 * coreRate ? 2 : 1;
        for (var s = 0; s < sets0; s++) {
            if (sets[s].bands != bands || sets[s].sampleRate != ratio * coreRate * bands) {
                return 0;
            }
        }
        return ratio;
    }

    /**
     * Rejects the frequency bands this decoder does not join.
     *
     * @param bands the largest number of frequency bands of a channel set
     * @throws MediaException.Unsupported if a channel set has four frequency bands or a rate above 192 kHz, or the
     *         channel sets do not all have the same number of frequency bands
     */
    private void checkBands(int bands) {
        if (bands > BANDS) {
            // TODO: four frequency bands (bXtraFreqBands set) need a filter ETSI TS 102 114 does not give, and ffmpeg
            // refuses them, so nothing shows what it is.
            throw new MediaException.Unsupported("DTS", "XLL with four frequency bands");
        }
        for (var s = 0; s < sets0; s++) {
            if (sets[s].sampleRate > MAX_RATE) {
                // TODO: ffmpeg refuses 352.8 and 384 kHz, so nothing shows how their two frequency bands are joined.
                throw new MediaException.Unsupported("DTS", "XLL at " + sets[s].sampleRate + " Hz");
            }
            if (sets[s].bands != bands) {
                // TODO: channel sets at rates with different numbers of frequency bands would output channels at
                // different rates, and ffmpeg refuses them.
                throw new MediaException.Unsupported("DTS", "XLL channel sets with different frequency bands");
            }
        }
        if (bands > 1 && samples[1] == null) {
            samples[1] = new int[64][MAX_FRAME];
            lsbs[1] = new int[64][MAX_FRAME];
            joined = new int[2 * MAX_FRAME];
        }
    }

    /**
     * {@return whether a channel of the last component read is coded against the core}
     */
    private boolean codesAgainstCore() {
        for (var s = 0; s < sets0; s++) {
            if ((sets[s].residualType & ((1 << sets[s].channels) - 1)) != (1 << sets[s].channels) - 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the common header and the channel set headers of a component.
     *
     * @return the index of the navigation index, after the channel set headers
     */
    private int readHeaders(byte[] data, int offset, int length, ExtensionSubstream substream) {
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
        channelBase = 0;
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
        if (channelBase > samples[0].length) {
            throw new MediaException.Malformed("DTS XLL frame codes " + channelBase + " channels");
        }
        frameEnd = offset + frameSize;
        return position;
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
     * Reads the data of one frequency band of one channel set in one segment (clauses 8.5.2.2 to 8.5.2.5).
     */
    private void readBandData(byte[] data, int position, int size, ChannelSetHeader set, int band, int segment) {
        var lsbSize = band > 0 || scalableLsbs ? set.lsbSize[band] : 0;
        var covered = band == 0 || bandCrc == 3;
        var lsbPart = lsbSize + (lsbSize > 0 && bandCrc >= 2 && covered ? 2 : 0);
        var msbSize = size - lsbPart - (bandCrc >= 1 && covered ? 2 : 0);
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
            var part0 = segment == 0 ? (set.segmentType == 0 ? set.adaptiveOrder[band][ch] : set.highestOrder[band]) : 0;
            var buffer = samples[band][set.firstChannel + ch];
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
        if (segment == 0 && band == 1) {
            readHistory(set);
        }
        reader.checkNotOverrun();
        if (lsbSize > 0) {
            var lsbStart = position + size - lsbPart;
            reader.reset(data, lsbStart, lsbSize);
            for (var ch = 0; ch < set.channels; ch++) {
                var bits = set.scalableBits[band][ch];
                var buffer = lsbs[band][set.firstChannel + ch];
                for (var n = 0; n < segmentSamples; n++) {
                    buffer[base + n] = bits > 0 ? reader.read(bits) : 0;
                }
            }
            reader.checkNotOverrun();
        } else if (band > 0 || scalableLsbs) {
            // a band without an LSB part still shifts by its width adjustment or the fixed LSB width, over zeros
            for (var ch = 0; ch < set.channels; ch++) {
                Arrays.fill(lsbs[band][set.firstChannel + ch], base, base + segmentSamples, 0);
            }
        }
    }

    /**
     * Reads the decimator history of a channel set (clause 8.5.2.4), signed values of a coded width.
     */
    private void readHistory(ChannelSetHeader set) {
        var bits = reader.read(5) + 1;
        for (var ch = 0; ch < set.channels; ch++) {
            var target = history[set.firstChannel + ch];
            for (var n = 0; n < target.length; n++) {
                target[n] = reader.read(bits) << 32 - bits >> 32 - bits;
            }
        }
    }

    /**
     * Reads a Rice code with a binary part of {@code k} bits.
     */
    private int rice(int k) {
        var unary = 0;
        while (reader.read(1) == 0) {
            if (++unary < 0 || reader.overrun()) {
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
     * MSB/LSB assembly over one frequency band of one channel set.
     */
    private void reconstruct(ChannelSetHeader set, int band, int frameSamples) {
        var bandSamples = samples[band];
        for (var ch = 0; ch < set.channels; ch++) {
            var buffer = bandSamples[set.firstChannel + ch];
            var fixed = set.fixedOrder[band][ch];
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
            var order = set.adaptiveOrder[band][ch];
            if (order > 0) {
                var coefficients = set.predictor(band, ch);
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
        if (set.pairwise[band]) {
            for (var pair = 0; pair < set.channels >> 1; pair++) {
                var coefficient = set.pairCoefficients[band][pair];
                if (coefficient != 0) {
                    var basis = bandSamples[set.firstChannel + 2 * pair];
                    var target = bandSamples[set.firstChannel + 2 * pair + 1];
                    for (var n = 0; n < frameSamples; n++) {
                        target[n] += (int) (((long) coefficient * basis[n] + 4) >> 3);
                    }
                }
            }
            var ordered = reordered;
            for (var ch = 0; ch < set.channels; ch++) {
                ordered[set.originalOrder[band][ch]] = bandSamples[set.firstChannel + ch];
            }
            for (var ch = 0; ch < set.channels; ch++) {
                bandSamples[set.firstChannel + ch] = ordered[ch];
            }
        }
        for (var ch = 0; ch < set.channels; ch++) {
            var shift = lsbShift(set, band, ch);
            if (shift > 0) {
                var adjust = set.widthAdjust[band][ch];
                var buffer = bandSamples[set.firstChannel + ch];
                var low = lsbs[band][set.firstChannel + ch];
                for (var n = 0; n < frameSamples; n++) {
                    buffer[n] = (buffer[n] << shift) + (low[n] << adjust);
                }
            }
        }
    }

    /**
     * {@return the number of low bits of a channel that the MSB part of its band data does not code}
     *
     * @param set the channel set
     * @param band the frequency band
     * @param ch the channel within the set
     */
    private int lsbShift(ChannelSetHeader set, int band, int ch) {
        if (!scalableLsbs && band == 0) {
            return 0;
        }
        if (fixedLsbWidth != 0) {
            return fixedLsbWidth;
        }
        var bits = set.scalableBits[band][ch];
        var adjust = set.widthAdjust[band][ch];
        return (adjust > 0 && bits > 0 ? bits - 1 : bits) + adjust;
    }

    /**
     * Adds the core to the channels coded against it, then undoes the downmixes the later channel sets embedded into
     * the earlier ones: in band 0 and, when flagged ({@code bEmbDMixInExtBand}), in band 1 and its decimator history.
     *
     * <p>A channel coded against the core adds the core sample, multiplied by the inverse downmix scales of the later
     * channel sets and rounded to the precision of the channel's MSB part, then clipped to 24 bits. A channel coded
     * alone is not scaled. Each channel then subtracts the channels of the later sets, times their undo coefficients
     * and at its own resolution, as those channels stand before their own downmixes are undone (clause 8.6.2.4).
     *
     * @param core the core decoder, or {@code null} without core
     * @param frameSamples the number of samples per channel of the frame
     * @throws MediaException.Malformed if a channel is coded against a missing core
     * @implNote This implementation follows ffmpeg's lossless output, measured as a black box, for the arithmetic of
     * the scales and coefficients, which clause C.9 leaves open for more than one later set and for negative scales.
     */
    private void addCore(CoreDecoder core, int frameSamples) {
        for (var s = 0; s < sets0; s++) {
            var set = sets[s];
            var masks = set.channelMasks();
            for (var ch = 0; ch < set.channels; ch++) {
                if ((set.residualType >> ch & 1) == 0) {
                    if (core == null) {
                        throw new MediaException.Malformed("DTS XLL residual coded against a missing core");
                    }
                    if (core.fixedOutputSamples() != frameSamples) {
                        throw new MediaException.Malformed("DTS XLL frame of " + frameSamples
                                + " samples over a core frame of " + core.fixedOutputSamples());
                    }
                    addCoreChannel(samples[0][set.firstChannel + ch], core.fixedChannelOutput(masks[ch]),
                            coreGain(s, ch), lsbShift(set, 0, ch), CORE_BITS - set.bitResolution, frameSamples);
                }
            }
        }
        for (var s = 0; s < sets0; s++) {
            var set = sets[s];
            for (var ch = 0; ch < set.channels; ch++) {
                for (var t = s + 1; t < sets0; t++) {
                    undoDownmix(t, s, samples[0], ch, frameSamples);
                    if (set.bands > 1 && sets[t].downmixInBand1) {
                        undoDownmix(t, s, samples[1], ch, frameSamples);
                        undoDownmix(t, s, history, ch, XllBandSynthesis.HISTORY);
                    }
                }
            }
        }
    }

    /**
     * Adds the scaled core to one channel.
     *
     * @param buffer the samples of the channel, updated
     * @param lossy the 24-bit core samples
     * @param gain the gain of the core, Q16
     * @param lsb the number of low bits of the channel the MSB part does not code
     * @param reduce the number of bits the core has more than the sample resolution of the channel
     * @param frameSamples the number of samples per channel of the frame
     */
    private static void addCoreChannel(int[] buffer, int[] lossy, int gain, int lsb, int reduce, int frameSamples) {
        var drop = reduce + lsb;
        var half = drop > 0 ? 1L << (drop - 1) : 0;
        for (var n = 0; n < frameSamples; n++) {
            var scaled = ((long) lossy[n] * gain + (1 << 15)) >> 16;
            var msb = (int) Math.clamp((scaled + half) >> drop, -(1L << 23), (1L << 23) - 1);
            buffer[n] += msb << lsb;
        }
    }

    /**
     * {@return the gain of the core in a channel: the product of the inverse downmix scales of the later channel
     * sets, Q16}
     *
     * @param target the index of the channel set of the channel
     * @param channel the channel within its set
     */
    private int coreGain(int target, int channel) {
        var gain = 1 << 16;
        for (var t = target + 1; t < sets0; t++) {
            if (mixesInto(t, target)) {
                gain = multiply16(gain, sets[t].inverseScale(column(t, target, channel)));
            }
        }
        return gain;
    }

    /**
     * Undoes on one channel the downmix a later channel set embedded into it.
     *
     * @param source the index of the later channel set
     * @param target the index of the channel set of the channel
     * @param buffers the samples by channel, of a frequency band or of the decimator history
     * @param channel the channel within its set
     * @param frameSamples the number of samples of each channel to process
     */
    private void undoDownmix(int source, int target, int[][] buffers, int channel, int frameSamples) {
        if (!mixesInto(source, target)) {
            return;
        }
        var set = sets[source];
        var column = column(source, target, channel);
        var inverse = Math.abs(set.inverseScale(column));
        var buffer = buffers[sets[target].firstChannel + channel];
        for (var k = 0; k < set.channels; k++) {
            var coefficient = set.undoCoefficient(k, column, inverse);
            if (coefficient == 0) {
                continue;
            }
            for (var w = source + 1; w < sets0; w++) {
                if (mixesInto(w, target) && mixesInto(w, source)) {
                    coefficient = multiply16(coefficient, sets[w].inverseScale(column(w, target, channel)));
                    coefficient = (int) (((long) coefficient * sets[w].scale(column(w, source, k)) + (1 << 14)) >> 15);
                }
            }
            var later = buffers[set.firstChannel + k];
            var drop = 15 + set.bitResolution - sets[target].bitResolution;
            if (drop > 0) {
                for (var n = 0; n < frameSamples; n++) {
                    buffer[n] -= (int) (((long) coefficient * later[n] + (1L << (drop - 1))) >> drop);
                }
            } else {
                for (var n = 0; n < frameSamples; n++) {
                    buffer[n] -= (int) ((long) coefficient * later[n] << -drop);
                }
            }
        }
    }

    /**
     * {@return whether a channel set embeds a downmix into an earlier one}
     *
     * @param source the index of the later channel set
     * @param target the index of the earlier channel set
     */
    private boolean mixesInto(int source, int target) {
        var set = sets[source];
        return !set.primary && set.downmixCoefficientsPresent && set.downmixEmbedded && set.replacementSet == 0
                && (set.hierarchical || sets[target].primary);
    }

    /**
     * {@return the column of a channel in the downmix matrix of a later channel set}
     *
     * @param source the index of the later channel set
     * @param target the index of the channel set of the channel
     * @param channel the channel within its set
     * @throws MediaException.Malformed if the matrix has no such column
     */
    private int column(int source, int target, int channel) {
        var set = sets[source];
        var column = channel;
        for (var u = 0; u < target; u++) {
            if (set.hierarchical || sets[u].primary) {
                column += sets[u].channels;
            }
        }
        if (column >= set.downmixColumns) {
            throw new MediaException.Malformed("DTS XLL downmix matrix has no column " + column);
        }
        return column;
    }

    /**
     * {@return the product of two Q16 numbers, rounded}
     *
     * @param a the first number
     * @param b the second number
     */
    private static int multiply16(int a, int b) {
        return (int) (((long) a * b + (1 << 15)) >> 16);
    }

    /**
     * Joins the frequency bands of every channel and converts it to float, at the storage width of its set.
     *
     * @param frameSamples the number of samples per channel and frequency band of the frame
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
                var buffer = samples[0][set.firstChannel + ch];
                if (set.bands > 1) {
                    XllBandSynthesis.synthesize(buffer, samples[1][set.firstChannel + ch],
                            history[set.firstChannel + ch], joined, frameSamples);
                    buffer = joined;
                }
                var total = frameSamples * set.bands;
                var full = 1 << (set.bitWidth - 1);
                var widen = set.bitWidth - set.bitResolution;
                if (output[out] == null || output[out].length < total) {
                    output[out] = new float[Math.max(total, MAX_FRAME)];
                }
                var target = output[out];
                for (var n = 0; n < total; n++) {
                    target[n] = Math.clamp((long) buffer[n] << widen, -full, full - 1) / (float) full;
                }
                outputMasks[out] = masks[ch];
                out++;
            }
        }
        outputSamples = frameSamples * sets[0].bands;
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
         * Whether the embedded downmix of the set is also in frequency band 1 ({@code bEmbDMixInExtBand}).
         */
        boolean downmixInBand1;

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
         * The size in bytes of the least significant bits section by frequency band.
         */
        final int[] lsbSize = new int[4];

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
            // the syntax of clause 8.3.2: its semantics make a clear flag one band, but no stream or decoder does
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
                    lsbSize[0] = xll.scalableLsbs() ? reader.read(xll.bitsForSize()) : 0;
                    // the second frequency band widens the field as a second channel set does, which the 16-bit
                    // FATE streams at 192 kHz show and clause 8.3.2 does not say
                    bitsForAbit = bitWidth > 16 ? 5 : bitWidth > 8 ? 4 : 3;
                    if ((xll.setCount() > 1 || bands > 1) && bitsForAbit < 5) {
                        bitsForAbit++;
                    }
                    for (var ch = 0; ch < channels; ch++) {
                        scalableBits[0][ch] = xll.scalableLsbs() ? reader.read(4) : 0;
                    }
                    for (var ch = 0; ch < channels; ch++) {
                        widthAdjust[0][ch] = xll.scalableLsbs() ? reader.read(4) : 0;
                    }
                } else {
                    downmixInBand1 = downmixEmbedded && reader.readFlag();
                    var split = reader.readFlag();
                    lsbSize[band] = split ? reader.read(xll.bitsForSize()) : 0;
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
         * {@return the downmix scale of a target channel, Q15}
         *
         * @param column the target channel, counted over the channel sets the set mixes into
         * @throws MediaException.Malformed if the scale index is outside -40 dB to 0 dB
         */
        int scale(int column) {
            var code = downmixCoefficients[column * (channels + 1)];
            var index = (code & 0xFF) - 1;
            if (index < 40 || index > 240) {
                throw new MediaException.Malformed("invalid DTS XLL downmix scale index " + index);
            }
            return (code & 0x100) != 0 ? DtsCoreTables.DMIX[index] : -DtsCoreTables.DMIX[index];
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
