package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.Crc16;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Encodes granules of input into MPEG audio Layer III frames.
 *
 * <p>The encoder runs the analysis filterbank one granule ahead of the transform so the attack
 * detector can see the next granule: a granule followed by an attack becomes a start block, a
 * granule with an attack becomes three short blocks, and the granule after the last short one a
 * stop block. In joint stereo both channels share their block types so that a frame can use
 * mid/side stereo, which it does when the perceptual entropy of the mid and side spectra under
 * the common masking threshold is lower than that of the left and right ones. Intensity stereo
 * is never used.
 *
 * <p>Rate control searches one noise offset for every granule and channel of a frame (see
 * {@link Mp3Quantizer#quantize}): in constant bitrate mode the smallest offset whose bits fit the
 * frame's budget, which draws on the bit reservoir when the frame needs more than its own slots
 * and refills it otherwise; in variable bitrate mode the offset of the quality setting, after
 * which the frame takes the lowest bitrate that holds it. Average bitrate mode is variable
 * bitrate mode whose offset follows the distance between the bitrate so far and the target.
 *
 * <p>Main data is laid out with the bit reservoir of ISO/IEC 11172-3 2.4.3.1: a frame's main data
 * starts up to {@code main_data_begin} bytes before its own slots, in the unused tail of earlier
 * frames. A frame is complete, and handed out, once no later frame can still write into it.
 *
 * <p>An instance is not thread-safe.
 */
final class Mp3FrameEncoder {
    /**
     * Receives the complete frames.
     */
    interface FrameSink {
        /**
         * Accepts one frame.
         *
         * @param frame the frame bytes, header included
         */
        void frame(byte[] frame);
    }

    /**
     * The noise offset of each variable bitrate quality, in decibels, chosen so that 44.1 kHz
     * stereo music lands near the customary bitrates of the MP3 quality scale (about 245 kbit/s
     * at quality 0, 190 at 2, 150 at 4, 105 at 6 and 65 at 9).
     */
    private static final float[] VBR_LAMBDA = {-12, -10, -8, -6.5f, -5, -3.5f, -2, 0, 2, 4.5f};

    /**
     * The noise offset range searched by constant bitrate rate control, in half decibels.
     */
    private static final int LAMBDA_MIN = -80;

    /**
     * The largest noise offset searched, in half decibels.
     */
    private static final int LAMBDA_MAX = 160;

    /**
     * The largest {@code part2_3_length} of one granule and channel.
     */
    private static final int MAX_GRANULE_BITS = 4095;

    /**
     * The fraction of the reservoir a frame of long blocks may draw.
     */
    private static final float RESERVOIR_DRAW_LONG = 0.6f;

    /**
     * The fraction of the reservoir a frame with short blocks may draw.
     */
    private static final float RESERVOIR_DRAW_SHORT = 1.0f;

    /**
     * The validated configuration.
     */
    private final Mp3EncoderOptions options;

    /**
     * The index of the sampling rate in {@link Mp3EncoderTables#SAMPLE_RATES}.
     */
    private final int rateIndex;

    /**
     * The MPEG version: 0 for MPEG-1, 1 for MPEG-2, 2 for MPEG-2.5.
     */
    private final int version;

    /**
     * Whether the stream is MPEG-1.
     */
    private final boolean mpeg1;

    /**
     * The number of granules per frame.
     */
    private final int granules;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The header channel mode.
     */
    private final int mode;

    /**
     * Whether the frame header carries a CRC.
     */
    private final boolean crc;

    /**
     * The largest {@code main_data_begin}, zero when the reservoir is disabled.
     */
    private final int maxReservoir;

    /**
     * The side information size in bytes.
     */
    private final int sideInfoBytes;

    /**
     * The long-block band layout.
     */
    private final Mp3BandLayout longLayout;

    /**
     * The short-block band layout.
     */
    private final Mp3BandLayout shortLayout;

    /**
     * The analysis filterbank of each channel.
     */
    private final Mp3AnalysisFilterbank[] filterbanks;

    /**
     * The attack detector of each channel.
     */
    private final Mp3AttackDetector[] detectors;

    /**
     * The psychoacoustic model of each channel.
     */
    private final Mp3Psychoacoustics[] models;

    /**
     * The hybrid transform.
     */
    private final Mp3HybridTransform transform;

    /**
     * The quantizer of each granule and channel of a frame, indexed {@code granule * 2 + channel}.
     */
    private final Mp3Quantizer[] quantizers;

    /**
     * The coding of each granule and channel of a frame.
     */
    private final Mp3CodedGranule[] coded;

    /**
     * The subband samples of the previous, current and next granule of each channel.
     */
    private final float[][][] subbands;

    /**
     * The attack masks of {@link Mp3AttackDetector#detect} of the previous, current and next
     * granule of each channel.
     */
    private final int[][] attacks;

    /**
     * The block type of the last transformed granule of each channel.
     */
    private final int[] lastBlockType;

    /**
     * The transform lines of each granule and channel of a frame.
     */
    private final float[][] lines;

    /**
     * The band energies of each granule and channel of a frame.
     */
    private final float[][] energies;

    /**
     * The masking thresholds of each granule and channel of a frame.
     */
    private final float[][] thresholds;

    /**
     * The lowpass gain of each long-block line.
     */
    private final float[] lowpassLong = new float[576];

    /**
     * The lowpass gain of each short-block line.
     */
    private final float[] lowpassShort = new float[192];

    /**
     * The number of granules pushed so far.
     */
    private long pushed;

    /**
     * The number of granules of the frame being assembled that are transformed.
     */
    private int frameGranules;

    /**
     * The fractional frame size accumulator of constant bitrate padding.
     */
    private int paddingRemainder;

    /**
     * The noise offset of average bitrate mode, in decibels.
     */
    private float averageLambda;

    /**
     * The noise offset of the last constant bitrate search, where the next search starts.
     */
    private float lastLambda;

    /**
     * The bits written so far, for average bitrate mode.
     */
    private long bitsWritten;

    /**
     * The frames written so far, for average bitrate mode.
     */
    private long framesWritten;

    /**
     * The frames assembled so far, handed out or pending.
     */
    private long assembled;

    /**
     * The frames whose slots may still receive main data, oldest first.
     */
    private final ArrayDeque<PendingFrame> pending = new ArrayDeque<>();

    /**
     * The position, in the concatenated slot space of all frames, where the next frame's slots
     * begin.
     */
    private long slotEnd;

    /**
     * The position, in the slot space, where the main data written so far ends.
     */
    private long dataEnd;

    /**
     * The main data of the frame being written.
     */
    private final MsbBitWriter mainData = new MsbBitWriter(4096);

    /**
     * The side information of the frame being written.
     */
    private final MsbBitWriter sideInfo = new MsbBitWriter(64);

    /**
     * Receives the complete frames.
     */
    private final FrameSink sink;

    /**
     * A frame whose slots may still receive main data of later frames.
     *
     * @param bytes the whole frame
     * @param slotStart the slot-space position of its first slot
     * @param slotCount the number of slots
     * @param offset the index in {@code bytes} of its first slot
     */
    private record PendingFrame(byte[] bytes, long slotStart, int slotCount, int offset) {
    }

    /**
     * Creates a frame encoder.
     *
     * @param options the configuration
     * @param kernel the DSP kernel
     * @param sink receives the complete frames
     */
    Mp3FrameEncoder(Mp3EncoderOptions options, Mp3EncoderKernel kernel, FrameSink sink) {
        this.options = options;
        this.sink = sink;
        this.rateIndex = Mp3EncoderTables.sampleRateIndex(options.sampleRate());
        this.version = rateIndex / 3;
        this.mpeg1 = version == 0;
        this.granules = mpeg1 ? 2 : 1;
        this.channels = options.layout().channels();
        this.mode = channels == 1 ? 3 : switch (options.stereoMode()) {
            case STEREO -> 0;
            case JOINT_STEREO -> 1;
            case DUAL_CHANNEL -> 2;
        };
        this.crc = options.crc();
        this.maxReservoir = options.bitReservoir() ? (mpeg1 ? 511 : 255) : 0;
        this.sideInfoBytes = mpeg1 ? (channels == 1 ? 17 : 32) : (channels == 1 ? 9 : 17);
        this.longLayout = new Mp3BandLayout(rateIndex, false);
        this.shortLayout = new Mp3BandLayout(rateIndex, true);
        this.filterbanks = new Mp3AnalysisFilterbank[channels];
        this.detectors = new Mp3AttackDetector[channels];
        this.models = new Mp3Psychoacoustics[channels];
        this.subbands = new float[channels][3][576];
        this.attacks = new int[channels][3];
        this.lastBlockType = new int[channels];
        for (var ch = 0; ch < channels; ch++) {
            filterbanks[ch] = new Mp3AnalysisFilterbank(kernel);
            detectors[ch] = new Mp3AttackDetector(options.sampleRate());
            models[ch] = new Mp3Psychoacoustics(rateIndex, longLayout, shortLayout, 0);
        }
        this.transform = new Mp3HybridTransform(kernel);
        this.quantizers = new Mp3Quantizer[4];
        this.coded = new Mp3CodedGranule[4];
        this.lines = new float[4][576];
        this.energies = new float[4][39];
        this.thresholds = new float[4][39];
        for (var i = 0; i < 4; i++) {
            quantizers[i] = new Mp3Quantizer(kernel, mpeg1);
            coded[i] = new Mp3CodedGranule();
        }
        var cutoff = options.effectiveBandwidth();
        var rate = options.sampleRate();
        fillLowpass(lowpassLong, rate / 1152.0, cutoff);
        fillLowpass(lowpassShort, rate / 384.0, cutoff);
        this.averageLambda = options.bitrateMode() == Mp3BitrateMode.ABR ? 0 : vbrLambda(options.vbrQuality());
    }

    /**
     * Fills the gains of a frequency-domain lowpass with a raised-cosine transition ending at the
     * cutoff.
     *
     * @param gains receives the gain of each line
     * @param lineWidth the width of one line in hertz
     * @param cutoff the cutoff in hertz
     */
    private static void fillLowpass(float[] gains, double lineWidth, double cutoff) {
        var transition = Math.max(cutoff * 0.05, 2 * lineWidth);
        for (var i = 0; i < gains.length; i++) {
            var f = (i + 0.5) * lineWidth;
            if (f <= cutoff - transition) {
                gains[i] = 1;
            } else if (f >= cutoff) {
                gains[i] = 0;
            } else {
                var x = (f - (cutoff - transition)) / transition;
                var c = StrictMath.cos(x * Math.PI / 2);
                gains[i] = (float) (c * c);
            }
        }
    }

    /**
     * Returns the noise offset of a variable bitrate quality.
     *
     * @param quality the quality, from 0 (best) to 9
     * @return the offset in decibels
     */
    static float vbrLambda(int quality) {
        return VBR_LAMBDA[quality];
    }

    /**
     * Returns the number of samples per channel of one frame.
     *
     * @return 1152 for MPEG-1, 576 otherwise
     */
    int frameSize() {
        return granules * 576;
    }

    /**
     * Encodes one granule of input, emitting a frame when one completes.
     *
     * @param planes the input planes, one per channel
     * @param offset the index of the granule's first sample in each plane
     */
    void push(float[][] planes, int offset) {
        for (var ch = 0; ch < channels; ch++) {
            var bands = subbands[ch];
            var oldest = bands[0];
            bands[0] = bands[1];
            bands[1] = bands[2];
            bands[2] = oldest;
            filterbanks[ch].process(planes[ch], offset, bands[2]);
            attacks[ch][0] = attacks[ch][1];
            attacks[ch][1] = attacks[ch][2];
            attacks[ch][2] = detectors[ch].detect(bands[2]);
        }
        pushed++;
        if (pushed < 2) {
            return;
        }
        transformGranule(frameGranules);
        frameGranules++;
        if (frameGranules == granules) {
            encodeFrame();
            frameGranules = 0;
        }
    }

    /**
     * Returns the number of frames assembled so far, handed out or still pending.
     *
     * @return the count
     */
    long writtenFrames() {
        return assembled;
    }

    /**
     * Returns whether the frame being assembled has no transformed granule.
     *
     * @return {@code true} at a frame boundary
     */
    boolean atFrameBoundary() {
        return frameGranules == 0;
    }

    /**
     * Decides the block type of the current granule of every channel, transforms it and runs the
     * psychoacoustic model.
     *
     * @param granule the index of the granule within its frame
     */
    private void transformGranule(int granule) {
        var shared = channels == 2 && mode == 1;
        var currentAttack = false;
        var nextAttack = false;
        for (var ch = 0; ch < channels; ch++) {
            currentAttack |= wantsShort(ch, 1);
            nextAttack |= wantsShort(ch, 2);
        }
        for (var ch = 0; ch < channels; ch++) {
            var current = shared ? currentAttack : wantsShort(ch, 1);
            var next = shared ? nextAttack : wantsShort(ch, 2);
            var previous = lastBlockType[ch];
            var afterShort = previous == Mp3CodedGranule.SHORT || previous == Mp3CodedGranule.START;
            int type;
            if (afterShort) {
                type = current || next ? Mp3CodedGranule.SHORT : Mp3CodedGranule.STOP;
            } else {
                type = next || current ? Mp3CodedGranule.START : Mp3CodedGranule.LONG;
            }
            lastBlockType[ch] = type;
            var index = granule * 2 + ch;
            var out = lines[index];
            transform.transform(subbands[ch][0], subbands[ch][1], type, out);
            coded[index].blockType = type;
            if (type == Mp3CodedGranule.SHORT) {
                for (var w = 0; w < 3; w++) {
                    for (var f = 0; f < 192; f++) {
                        out[192 * w + f] *= lowpassShort[f];
                    }
                }
                models[ch].analyzeShort(out, energies[index], thresholds[index]);
            } else {
                for (var i = 0; i < 576; i++) {
                    out[i] *= lowpassLong[i];
                }
                models[ch].analyzeLong(out, energies[index], thresholds[index]);
            }
        }
    }

    /**
     * Returns whether a granule of a channel calls for short blocks: it holds an attack, or the
     * granule before it holds one in its last third, where the window of the following granule
     * would still cover it.
     *
     * @param channel the channel index
     * @param granule {@code 1} for the current granule, {@code 2} for the next one
     * @return {@code true} for short blocks
     */
    private boolean wantsShort(int channel, int granule) {
        return attacks[channel][granule] != 0 || (attacks[channel][granule - 1] & 0b110000) != 0;
    }

    /**
     * Codes and writes the assembled frame.
     */
    private void encodeFrame() {
        var midSide = channels == 2 && mode == 1 && chooseMidSide();
        if (midSide) {
            toMidSide();
        }
        for (var gr = 0; gr < granules; gr++) {
            for (var ch = 0; ch < channels; ch++) {
                var index = gr * 2 + ch;
                var layout = coded[index].blockType == Mp3CodedGranule.SHORT ? shortLayout : longLayout;
                quantizers[index].prepare(lines[index], coded[index].blockType, layout, energies[index], thresholds[index]);
            }
        }
        var reservoirBytes = Math.min(maxReservoir, (int) Math.min(Integer.MAX_VALUE, slotEnd - dataEnd));
        var reservoirBits = 8 * reservoirBytes;
        int bitrateIndex;
        int padding;
        float lambda;
        if (options.bitrateMode() == Mp3BitrateMode.CBR) {
            bitrateIndex = cbrIndex();
            padding = nextPadding(bitrateIndex);
            var slots = frameBytes(bitrateIndex, padding) - 4 - (crc ? 2 : 0) - sideInfoBytes;
            var own = 8 * slots;
            var demand = codeAll(0, false);
            var hasShort = false;
            for (var i = 0; i < granules * 2; i++) {
                hasShort |= i % 2 < channels && coded[i].blockType == Mp3CodedGranule.SHORT;
            }
            var budget = demand <= own ? demand
                    : Math.min(demand, own + (int) ((hasShort ? RESERVOIR_DRAW_SHORT : RESERVOIR_DRAW_LONG) * reservoirBits));
            budget = Math.max(budget, own + reservoirBits - 8 * maxReservoir);
            budget = Math.min(budget, own + reservoirBits);
            lambda = search(budget);
        } else {
            lambda = options.bitrateMode() == Mp3BitrateMode.ABR ? averageLambda : vbrLambda(options.vbrQuality());
            var bits = codeAll(lambda, false);
            var maxIndex = 14;
            var maxBits = 8 * (frameBytes(maxIndex, 0) - 4 - (crc ? 2 : 0) - sideInfoBytes) + reservoirBits;
            if (bits > maxBits || bits == Integer.MAX_VALUE) {
                lambda = search(maxBits);
                bits = codeAll(lambda, false);
            }
            bitrateIndex = maxIndex;
            for (var index = 1; index <= maxIndex; index++) {
                var own = 8 * (frameBytes(index, 0) - 4 - (crc ? 2 : 0) - sideInfoBytes);
                if (own + reservoirBits >= bits) {
                    bitrateIndex = index;
                    break;
                }
            }
            padding = 0;
        }
        var total = codeAll(lambda, true);
        var slots = frameBytes(bitrateIndex, padding) - 4 - (crc ? 2 : 0) - sideInfoBytes;
        if (total > 8 * slots + reservoirBits) {
            throw new AssertionError("frame overflow: " + total + " > " + (8 * slots + reservoirBits));
        }
        writeFrame(bitrateIndex, padding, midSide, reservoirBytes, slots);
        if (options.bitrateMode() == Mp3BitrateMode.ABR) {
            updateAverageLambda(bitrateIndex, padding);
        }
    }

    /**
     * Returns the bitrate index of constant bitrate mode.
     *
     * @return the index
     */
    private int cbrIndex() {
        var table = Mp3EncoderTables.BITRATES[mpeg1 ? 0 : 1];
        for (var i = 1; i < table.length; i++) {
            if (table[i] == options.bitrate() / 1000) {
                return i;
            }
        }
        throw new AssertionError("invalid bitrate " + options.bitrate());
    }

    /**
     * Returns whether the next constant bitrate frame needs the padding slot that keeps the
     * average frame size exact.
     *
     * @param bitrateIndex the bitrate index
     * @return {@code 1} to pad, {@code 0} otherwise
     */
    private int nextPadding(int bitrateIndex) {
        var kbps = Mp3EncoderTables.BITRATES[mpeg1 ? 0 : 1][bitrateIndex];
        var numerator = (mpeg1 ? 144_000 : 72_000) * kbps;
        var rate = options.sampleRate();
        paddingRemainder += numerator % rate;
        if (paddingRemainder >= rate) {
            paddingRemainder -= rate;
            return 1;
        }
        return 0;
    }

    /**
     * Returns the size of a frame in bytes.
     *
     * @param bitrateIndex the bitrate index
     * @param padding the padding bit
     * @return the size, header included
     */
    private int frameBytes(int bitrateIndex, int padding) {
        var kbps = Mp3EncoderTables.BITRATES[mpeg1 ? 0 : 1][bitrateIndex];
        return (mpeg1 ? 144_000 : 72_000) * kbps / options.sampleRate() + padding;
    }

    /**
     * Returns the smallest noise offset whose frame fits a bit budget.
     *
     * @param budget the main data bits available
     * @return the offset in decibels
     */
    private float search(int budget) {
        var guess = Math.clamp(Math.round(lastLambda * 2), LAMBDA_MIN, LAMBDA_MAX);
        int low;
        int high;
        if (codeAll(guess * 0.5f, false) <= budget) {
            high = guess;
            low = guess;
            var step = 4;
            while (low > LAMBDA_MIN) {
                low = Math.max(LAMBDA_MIN, low - step);
                if (codeAll(low * 0.5f, false) > budget) {
                    break;
                }
                high = low;
                step *= 2;
            }
            if (high == LAMBDA_MIN) {
                lastLambda = high * 0.5f;
                return lastLambda;
            }
        } else {
            low = guess;
            high = guess;
            var step = 4;
            while (high < LAMBDA_MAX) {
                high = Math.min(LAMBDA_MAX, high + step);
                if (codeAll(high * 0.5f, false) <= budget) {
                    break;
                }
                low = high;
                step *= 2;
            }
        }
        while (high - low > 1) {
            var middle = (low + high) >> 1;
            if (codeAll(middle * 0.5f, false) <= budget) {
                high = middle;
            } else {
                low = middle;
            }
        }
        var lambda = high * 0.5f;
        while (codeAll(lambda, true) > budget && lambda < 200) {
            lambda += 0.5f;
        }
        lastLambda = lambda;
        return lambda;
    }

    /**
     * Codes every granule and channel of the frame at a noise offset, raising the offset of any
     * granule over the {@code part2_3_length} limit, and returns the main data bits.
     *
     * @param lambda the noise offset in decibels
     * @param search whether to search the Huffman region split exhaustively
     * @return the bits
     */
    private int codeAll(float lambda, boolean search) {
        var total = 0;
        for (var gr = 0; gr < granules; gr++) {
            for (var ch = 0; ch < channels; ch++) {
                var index = gr * 2 + ch;
                var previous = mpeg1 && gr == 1 ? coded[ch] : null;
                var local = lambda;
                var bits = quantizers[index].quantize(local, coded[index], previous, search);
                while (bits > MAX_GRANULE_BITS) {
                    local += 1;
                    bits = quantizers[index].quantize(local, coded[index], previous, search);
                }
                total += bits;
            }
        }
        return total;
    }

    /**
     * Decides whether the frame codes mid and side rather than left and right.
     *
     * @return {@code true} for mid/side stereo
     */
    private boolean chooseMidSide() {
        var leftRight = 0.0;
        var midSide = 0.0;
        for (var gr = 0; gr < granules; gr++) {
            var left = gr * 2;
            var right = left + 1;
            if (coded[left].blockType != coded[right].blockType) {
                return false;
            }
            var layout = coded[left].blockType == Mp3CodedGranule.SHORT ? shortLayout : longLayout;
            var l = lines[left];
            var r = lines[right];
            for (var b = 0; b < layout.count; b++) {
                var mid = 0.0f;
                var side = 0.0f;
                for (var i = layout.start[b]; i < layout.end[b]; i++) {
                    var m = l[i] + r[i];
                    var s = l[i] - r[i];
                    mid += m * m;
                    side += s * s;
                }
                mid *= 0.5f;
                side *= 0.5f;
                var width = layout.end[b] - layout.start[b];
                var common = Math.min(thresholds[left][b], thresholds[right][b]);
                leftRight += entropy(energies[left][b], thresholds[left][b], width)
                        + entropy(energies[right][b], thresholds[right][b], width);
                midSide += entropy(mid, common, width) + entropy(side, common, width);
            }
        }
        return midSide < leftRight;
    }

    /**
     * Returns the perceptual entropy of a band, the bits needed to code it at its threshold.
     *
     * @param energy the band energy
     * @param threshold the masking threshold
     * @param width the number of lines
     * @return the entropy estimate in bits
     */
    private static double entropy(float energy, float threshold, int width) {
        if (energy <= threshold) {
            return 0;
        }
        return 0.5 * width * StrictMath.log(energy / threshold) * 1.4426950408889634;
    }

    /**
     * Rotates the frame's left and right lines into mid and side, with their energies and the
     * common threshold.
     */
    private void toMidSide() {
        var scale = (float) Math.sqrt(0.5);
        for (var gr = 0; gr < granules; gr++) {
            var left = gr * 2;
            var right = left + 1;
            var l = lines[left];
            var r = lines[right];
            for (var i = 0; i < 576; i++) {
                var m = (l[i] + r[i]) * scale;
                var s = (l[i] - r[i]) * scale;
                l[i] = m;
                r[i] = s;
            }
            var layout = coded[left].blockType == Mp3CodedGranule.SHORT ? shortLayout : longLayout;
            for (var b = 0; b < layout.count; b++) {
                var common = Math.min(thresholds[left][b], thresholds[right][b]);
                thresholds[left][b] = common;
                thresholds[right][b] = common;
                var mid = 0.0f;
                var side = 0.0f;
                for (var i = layout.start[b]; i < layout.end[b]; i++) {
                    mid += l[i] * l[i];
                    side += r[i] * r[i];
                }
                energies[left][b] = mid;
                energies[right][b] = side;
            }
        }
    }

    /**
     * Moves the noise offset of average bitrate mode towards the target bitrate.
     *
     * @param bitrateIndex the bitrate index of the frame just written
     * @param padding its padding bit
     */
    private void updateAverageLambda(int bitrateIndex, int padding) {
        bitsWritten += 8L * frameBytes(bitrateIndex, padding);
        framesWritten++;
        var seconds = framesWritten * (double) frameSize() / options.sampleRate();
        var average = bitsWritten / seconds;
        var error = (average - options.bitrate()) / options.bitrate();
        averageLambda = (float) Math.clamp(averageLambda + 0.5 * error, -8, 20);
    }

    /**
     * Assembles the frame: header, CRC, side information and main data, the latter placed in the
     * reservoir.
     *
     * @param bitrateIndex the bitrate index
     * @param padding the padding bit
     * @param midSide whether mid/side stereo is on
     * @param reservoirBytes the {@code main_data_begin} value
     * @param slots the number of main data bytes the frame itself holds
     */
    private void writeFrame(int bitrateIndex, int padding, boolean midSide, int reservoirBytes, int slots) {
        var size = frameBytes(bitrateIndex, padding);
        var frame = new byte[size];
        assembled++;
        var header = 0xFFE00000 | (version == 0 ? 3 : version == 1 ? 2 : 0) << 19 | 1 << 17 | (crc ? 0 : 1) << 16
                | bitrateIndex << 12 | (rateIndex % 3) << 10 | padding << 9 | mode << 6 | (midSide ? 2 : 0) << 4 | 1 << 2;
        frame[0] = (byte) (header >>> 24);
        frame[1] = (byte) (header >>> 16);
        frame[2] = (byte) (header >>> 8);
        frame[3] = (byte) header;

        var side = sideInfo;
        side.reset();
        side.write(reservoirBytes, mpeg1 ? 9 : 8);
        side.write(0, mpeg1 ? (channels == 1 ? 5 : 3) : channels);
        if (mpeg1) {
            for (var ch = 0; ch < channels; ch++) {
                var granule = coded[2 + ch];
                for (var g = 0; g < 4; g++) {
                    side.write(granule.scfsi[g] ? 1 : 0, 1);
                }
            }
        }
        for (var gr = 0; gr < granules; gr++) {
            for (var ch = 0; ch < channels; ch++) {
                var granule = coded[gr * 2 + ch];
                side.write(granule.part23Length(), 12);
                side.write(granule.bigValues, 9);
                side.write(granule.globalGain, 8);
                side.write(granule.scalefacCompress, mpeg1 ? 4 : 9);
                if (granule.blockType != Mp3CodedGranule.LONG) {
                    side.write(1, 1);
                    side.write(granule.blockType, 2);
                    side.write(0, 1);
                    side.write(granule.tableSelect[0], 5);
                    side.write(granule.tableSelect[1], 5);
                    for (var w = 0; w < 3; w++) {
                        side.write(granule.subblockGain[w], 3);
                    }
                } else {
                    side.write(0, 1);
                    side.write(granule.tableSelect[0], 5);
                    side.write(granule.tableSelect[1], 5);
                    side.write(granule.tableSelect[2], 5);
                    side.write(granule.region0Count, 4);
                    side.write(granule.region1Count, 3);
                }
                if (mpeg1) {
                    side.write(granule.preflag ? 1 : 0, 1);
                }
                side.write(granule.scalefacScale, 1);
                side.write(granule.count1Table, 1);
            }
        }
        var sideOffset = 4 + (crc ? 2 : 0);
        System.arraycopy(side.array(), 0, frame, sideOffset, sideInfoBytes);
        if (crc) {
            var check = Crc16.crc16(Crc16.crc16(0xFFFF, frame, 2, 2), frame, sideOffset, sideInfoBytes);
            frame[4] = (byte) (check >>> 8);
            frame[5] = (byte) check;
        }

        var data = mainData;
        data.reset();
        for (var gr = 0; gr < granules; gr++) {
            for (var ch = 0; ch < channels; ch++) {
                var index = gr * 2 + ch;
                writeScalefactors(coded[index], gr, data);
                quantizers[index].writeSpectrum(coded[index], data);
            }
        }
        data.alignWithZeros();

        var slotStart = slotEnd;
        var dataStart = slotStart - reservoirBytes;
        var pendingFrame = new PendingFrame(frame, slotStart, slots, sideOffset + sideInfoBytes);
        pending.addLast(pendingFrame);
        slotEnd += slots;
        place(data.array(), data.size(), dataStart);
        dataEnd = dataStart + data.size();
        release(false);
    }

    /**
     * Copies main data bytes into the slots of the pending frames.
     *
     * @param bytes the main data
     * @param length the number of bytes
     * @param start the slot-space position of the first byte
     */
    private void place(byte[] bytes, int length, long start) {
        for (var frame : pending) {
            var from = Math.max(start, frame.slotStart());
            var to = Math.min(start + length, frame.slotStart() + frame.slotCount());
            if (from < to) {
                System.arraycopy(bytes, (int) (from - start), frame.bytes(), frame.offset() + (int) (from - frame.slotStart()),
                        (int) (to - from));
            }
        }
    }

    /**
     * Hands out the pending frames that no later frame can write into.
     *
     * @param all whether to hand out every pending frame, at the end of the stream
     */
    private void release(boolean all) {
        while (!pending.isEmpty()) {
            var frame = pending.peekFirst();
            var end = frame.slotStart() + frame.slotCount();
            if (!all && dataEnd < end && slotEnd - maxReservoir < end) {
                break;
            }
            pending.removeFirst();
            sink.frame(frame.bytes());
        }
    }

    /**
     * Hands out every pending frame; the encoder accepts no more input afterwards.
     */
    void finish() {
        release(true);
    }

    /**
     * Writes the scalefactors of one granule and channel.
     *
     * @param granule the coded granule whose scalefactors are written
     * @param gr the granule index within the frame
     * @param out the destination
     */
    private void writeScalefactors(Mp3CodedGranule granule, int gr, MsbBitWriter out) {
        var sf = granule.scalefactors;
        if (mpeg1) {
            var slen1 = Mp3EncoderTables.SLEN1[granule.scalefacCompress];
            var slen2 = Mp3EncoderTables.SLEN2[granule.scalefacCompress];
            if (granule.blockType == Mp3CodedGranule.SHORT) {
                for (var i = 0; i < 36; i++) {
                    out.write(sf[i], i < 18 ? slen1 : slen2);
                }
                return;
            }
            var groups = Mp3EncoderTables.SCFSI_BANDS;
            for (var g = 0; g < 4; g++) {
                if (gr == 1 && granule.scfsi[g]) {
                    continue;
                }
                for (var b = groups[g]; b < groups[g + 1]; b++) {
                    out.write(sf[b], b < 11 ? slen1 : slen2);
                }
            }
            return;
        }
        var compress = granule.scalefacCompress;
        var shortBlock = granule.blockType == Mp3CodedGranule.SHORT;
        int table;
        int[] slen;
        if (compress < 400) {
            table = 0;
            slen = new int[]{(compress >> 4) / 5, (compress >> 4) % 5, (compress & 15) >> 2, compress & 3};
        } else if (compress < 500) {
            table = 1;
            var c = compress - 400;
            slen = new int[]{(c >> 2) / 5, (c >> 2) % 5, c & 3, 0};
        } else {
            table = 2;
            var c = compress - 500;
            slen = new int[]{c / 3, c % 3, 0, 0};
        }
        var partitions = Mp3EncoderTables.LSF_PARTITIONS[table][shortBlock ? 1 : 0];
        var position = 0;
        for (var p = 0; p < 4; p++) {
            for (var k = 0; k < partitions[p]; k++, position++) {
                out.write(sf[position], slen[p]);
            }
        }
    }

    /**
     * Forgets every buffered sample and pending frame, as for a new stream.
     */
    void reset() {
        for (var ch = 0; ch < channels; ch++) {
            filterbanks[ch].reset();
            for (var b : subbands[ch]) {
                Arrays.fill(b, 0);
            }
            Arrays.fill(attacks[ch], 0);
            lastBlockType[ch] = Mp3CodedGranule.LONG;
        }
        pushed = 0;
        frameGranules = 0;
        pending.clear();
        slotEnd = 0;
        dataEnd = 0;
    }
}
