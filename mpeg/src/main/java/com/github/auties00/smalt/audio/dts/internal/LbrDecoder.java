package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes the LBR low bit rate coding of DTS Express (ETSI TS 102 114 clause 9): the decoder initialization header
 * (Table 9-1), the frame chunk and its tonal, residual and LFE chunks, and the hybrid synthesis filter bank.
 *
 * <p>A frame holds 32 synthesis blocks of {@code N} lines, {@code N} being 32, 64 or 128 by sample rate, or more
 * under a band limit. The chunk payloads are read least significant bit first. Where the specification is
 * incomplete or inconsistent, the decoder follows the behavior measured on ffmpeg's decoder run as a black box;
 * {@link LbrTables} describes those measurements.
 *
 * <p>Instances are not thread-safe.
 */
final class LbrDecoder {
    /**
     * The LBR sync word.
     */
    static final int SYNC = 0x0A801921;

    /**
     * The sample rates by {@code nLBRSampleRateCode} (Table 9-3), {@code 0} where reserved.
     */
    private static final int[] SAMPLE_RATES = {8000, 16000, 32000, 0, 0, 22050, 44100, 0, 0, 0, 12000, 24000, 48000,
            0, 0, 0};

    /**
     * The major LBR bitstream version this decoder reads.
     */
    private static final int VERSION = 8;

    /**
     * The number of synthesis blocks of a frame.
     */
    private static final int BLOCKS = 32;

    /**
     * The synthesis block of a frame where its first tonal position starts.
     */
    private static final int TONAL_DELAY = 23;

    /**
     * The number of synthesis blocks the spectrum ring holds, enough for the longest tonal component of a frame.
     */
    private static final int RING = 128;

    /**
     * The flag of 24-bit source samples.
     */
    private static final int FLAG_24_BIT = 0x01;

    /**
     * The flag of the LFE channel.
     */
    private static final int FLAG_LFE = 0x02;

    /**
     * The mask of the band limit flags.
     */
    private static final int FLAG_BAND_LIMIT = 0x1C;

    /**
     * The band limit flags of a full band stream.
     */
    private static final int BAND_LIMIT_NONE = 0x14;

    /**
     * The band limit flags of a stream limited to two thirds of its bandwidth.
     */
    private static final int BAND_LIMIT_TWO_THIRDS = 0x04;

    /**
     * The band limit flags of a stream limited to half its bandwidth.
     */
    private static final int BAND_LIMIT_HALF = 0x08;

    /**
     * The band limit flags of a stream limited to a third of its bandwidth.
     */
    private static final int BAND_LIMIT_THIRD = 0x0C;

    /**
     * The band limit flags of a stream limited to a quarter of its bandwidth.
     */
    private static final int BAND_LIMIT_QUARTER = 0x10;

    /**
     * The band limit flags of a stream limited to an eighth of its bandwidth.
     */
    private static final int BAND_LIMIT_EIGHTH = 0x18;

    /**
     * The flag of a stream whose first two channels are an embedded stereo downmix of all of them.
     */
    private static final int FLAG_STEREO_DOWNMIX = 0x20;

    /**
     * The flag of a stream whose first channels are an embedded multichannel downmix of all of them.
     */
    private static final int FLAG_MULTICHANNEL_DOWNMIX = 0x40;

    /**
     * The chunk identifier of an LBR frame with checksum.
     */
    private static final int LBR_CHECKSUM = 0x04;

    /**
     * The chunk identifier of an LBR frame without checksum.
     */
    private static final int LBR_PLAIN = 0x06;

    /**
     * The chunk identifier of the LFE samples.
     */
    private static final int LFE = 0x0A;

    /**
     * The ratio of the sample rate to the LFE rate from 28 to 48 kHz (Table 9-36), the one the measured LFE
     * interpolation filter of {@link LbrTables} runs at.
     */
    private static final int LFE_FACTOR = 64;

    /**
     * The limit of the predicted LFE value.
     */
    private static final float LFE_CLAMP = 3.0f;

    /**
     * The chunk identifier of the embedded channel sets.
     */
    private static final int EMBEDDED_LEVELS = 0x0B;

    /**
     * The first chunk identifier of the residual chunks (grid 1 of channel pair 0).
     */
    private static final int RESIDUAL_FIRST = 0x30;

    /**
     * The last chunk identifier of the residual chunks (time samples part 2 of channel pair 15).
     */
    private static final int RESIDUAL_LAST = 0x6F;

    /**
     * The chunk identifier of the tonal scale factors.
     */
    private static final int SCALE_FACTORS = 0x0E;

    /**
     * The chunk identifier of the combined tonal data without scale factors.
     */
    private static final int TONAL = 0x10;

    /**
     * The chunk identifier of the combined tonal data with scale factors.
     */
    private static final int TONAL_SCALED = 0x16;

    /**
     * The reader of the chunk being parsed.
     */
    private final Bits bits = new Bits();

    /**
     * The tonal scale factors of the frame by band.
     */
    private final int[] scaleFactors = new int[6];

    /**
     * The offset and size of the scale factor chunk of the frame, or a size of {@code -1}.
     */
    private int scaleOffset;

    /**
     * The size of the scale factor chunk of the frame, or {@code -1}.
     */
    private int scaleSize;

    /**
     * The payload offset of the LFE chunk of the frame.
     */
    private int lfeOffset;

    /**
     * The payload size of the LFE chunk of the frame, {@code -1} without one.
     */
    private int lfeSize;

    /**
     * The tonal chunks of the frame by slot: slot 0 holds the combined chunk ({@link #TONAL} or
     * {@link #TONAL_SCALED}), slot {@code 1 + g} the chunk of group {@code g}. As in ffmpeg's decoder, a later chunk
     * of a slot replaces an earlier one.
     */
    private final int[] tonalOffsets = new int[6];

    /**
     * The payload sizes of the tonal chunks by slot, {@code -1} for none.
     */
    private final int[] tonalSizes = new int[6];

    /**
     * The identifiers of the tonal chunks by slot.
     */
    private final int[] tonalIds = new int[6];

    /**
     * The groups whose parse stopped at a component past the coded lines after components of an earlier position,
     * with {@link #openPositions}: every component read afterwards in the frame is also synthesized as a component
     * of that group and position, as measured on ffmpeg's decoder. A frame opens at most {@link #MAX_OPEN} groups:
     * one in the combined chunk, whose parse ends there, and one per single group chunk.
     */
    private final int[] openGroups = new int[MAX_OPEN];

    /**
     * The positions at which the parse of {@link #openGroups} stopped.
     */
    private final int[] openPositions = new int[MAX_OPEN];

    /**
     * The largest number of {@link #openGroups} of a frame.
     */
    private static final int MAX_OPEN = 6;

    /**
     * The number of {@link #openGroups}.
     */
    private int openCount;

    /**
     * The number of tonal components read in the frame.
     */
    private int frameComponents;

    /**
     * A group whose components all parsed.
     */
    private static final int GROUP_DONE = 0;

    /**
     * A group cut short by the end of its chunk between two components.
     */
    private static final int GROUP_EXHAUSTED = 1;

    /**
     * A group whose parse stopped at an error, which ends the parse of its chunk.
     */
    private static final int GROUP_INVALID = 2;

    /**
     * The number of values of a queued tonal component.
     */
    private static final int PENDING_FIELDS = 9;

    /**
     * The tonal components of the frame: channel, group, position, line, offset, amplitude, phase, the group the
     * component was read in and the index of the component read, which a component and its copies in the open groups
     * share.
     */
    private int[] pending = new int[64 * PENDING_FIELDS];

    /**
     * The number of queued tonal components.
     */
    private int pendingCount;

    /**
     * The number of tonal components read in the frame and queued, with their copies, under one index.
     */
    private int componentIndex;

    /**
     * The quantized amplitude of each channel of the tonal component being read.
     */
    private final int[] componentAmplitudes = new int[16];

    /**
     * The amplitude of a channel a tonal component is absent from.
     */
    private static final int ABSENT = Integer.MIN_VALUE;

    /**
     * The quantized phase of each channel of the tonal component being read.
     */
    private final int[] componentPhases = new int[16];

    /**
     * Whether the decoder holds a valid initialization header.
     */
    private boolean initialized;

    /**
     * The sample rate of the initialization header.
     */
    private int sampleRate;

    /**
     * The speaker mask of the initialization header.
     */
    private int speakerMask;

    /**
     * The flags of the initialization header.
     */
    private int flags;

    /**
     * The original bit rate of the initialization header.
     */
    private int bitRate;

    /**
     * The scaled bit rate of the initialization header.
     */
    private int scaledBitRate;

    /**
     * The frequency range of the coded sample rate, from 0 to 2 (Table 9-4).
     */
    private int frequencyRange;

    /**
     * The number of lines of a synthesis block.
     */
    private int lines;

    /**
     * The scale of the tonal components for the lines of a block, {@code sqrt(lines / 128)}: the windows of other
     * numbers of lines carry the inverse gain, so tones keep the level they have at 128 lines.
     */
    private double tonalScale;

    /**
     * The number of samples per channel of a frame.
     */
    private int frameSamples;

    /**
     * The number of full band channels of the stream.
     */
    private int channels;

    /**
     * The number of full band channels decoded: all of them, or the two of the embedded stereo downmix.
     */
    private int decoded;

    /**
     * The speaker bit of each output channel, full band channels first.
     */
    private int[] masks = new int[0];

    /**
     * The synthesis window of {@code 2 * lines} taps.
     */
    private float[] window = new float[0];

    /**
     * The inverse MDCT basis, {@code lines} rows (one per line) of {@code 2 * lines} samples.
     */
    private float[] basis = new float[0];

    /**
     * The spectrum of the pending synthesis blocks by channel, {@link #RING} blocks of {@link #lines} lines.
     */
    private float[][] spectrum = new float[0][];

    /**
     * The second half of the last synthesized block by channel.
     */
    private float[][] overlap = new float[0][];

    /**
     * The output by channel.
     */
    private float[][] output = new float[0][];

    /**
     * The scratch block of {@code 2 * lines} samples.
     */
    private float[] block = new float[0];

    /**
     * The absolute index of the first synthesis block of the current frame.
     */
    private long frameBlock;

    /**
     * Whether the last frame produced output.
     */
    private boolean produced;

    /**
     * The number of lines of the coded spectrum: {@link #lines}, or fewer under a band limit.
     */
    private int codedLines;

    /**
     * The LFE samples of the current frame, then those of the previous frame, at the LFE rate.
     */
    private float[] lfeSamples = new float[0];

    /**
     * The ratio of the sample rate to the LFE rate (Table 9-36): 16 below 14 kHz, 32 below 28 kHz, else 64.
     */
    private int lfeFactor = LFE_FACTOR;

    /**
     * The head response of the LFE interpolation filter at {@link #lfeFactor}.
     */
    private float[] lfeHead = LbrTables.LFE_HEAD;

    /**
     * The poles of the LFE interpolation filter at {@link #lfeFactor}, as (real, imaginary) pairs.
     */
    private double[] lfePoles = LbrTables.LFE_POLES;

    /**
     * The part of the LFE head response that falls after the current frame.
     */
    private final float[] lfeSpill = new float[LbrTables.LFE_HEAD_LENGTH];

    /**
     * The state of the LFE interpolation filter's poles, as (real, imaginary) pairs.
     */
    private final double[] lfeModes = new double[LbrTables.LFE_POLES.length];

    /**
     * The residual decoder of the stream.
     */
    private LbrResidual residual = new LbrResidual(0, 1, 48000, 0, 0, 128);

    /**
     * Decodes an LBR component: its header, then the LBR frame that follows it.
     *
     * @param data the bytes
     * @param offset the index of the component
     * @param length the component size
     * @param substream the extension substream holding the asset descriptor
     * @throws MediaException.Malformed if the component breaks the syntax or no initialization header was seen
     * @throws MediaException.Unsupported if the stream has another bitstream version or a multichannel downmix
     */
    void decode(byte[] data, int offset, int length, ExtensionSubstream substream) {
        produced = false;
        if (length < 5 || CoreDecoder.readInt(data, offset) != SYNC) {
            throw new MediaException.Malformed("missing DTS LBR sync word");
        }
        var type = data[offset + 4] & 0xFF;
        var position = offset + 5;
        if (type == 2) {
            if (length < 16) {
                throw new MediaException.Malformed("truncated DTS LBR decoder initialization header");
            }
            readHeader(data, offset + 5);
            position = offset + 16;
        } else if (type != 1) {
            throw new MediaException.Malformed("unknown DTS LBR header type " + type);
        }
        if (!initialized) {
            throw new MediaException.Malformed("DTS LBR frame before its decoder initialization header");
        }
        decodeFrame(data, position, offset + length - position);
        produced = true;
    }

    /**
     * Reads the decoder initialization header (Table 9-1) and configures the decoder when it changes.
     */
    private void readHeader(byte[] data, int offset) {
        var code = data[offset] & 0xFF;
        var rate = code < SAMPLE_RATES.length ? SAMPLE_RATES[code] : 0;
        if (rate == 0) {
            throw new MediaException.Malformed("invalid DTS LBR sample rate code " + code);
        }
        var mask = (data[offset + 1] & 0xFF) | (data[offset + 2] & 0xFF) << 8;
        var version = (data[offset + 3] & 0xFF) | (data[offset + 4] & 0xFF) << 8;
        var headerFlags = data[offset + 5] & 0xFF;
        var nibbles = data[offset + 6] & 0xFF;
        var original = ((data[offset + 7] & 0xFF) | (data[offset + 8] & 0xFF) << 8) | (nibbles & 0x0F) << 16;
        var scaled = ((data[offset + 9] & 0xFF) | (data[offset + 10] & 0xFF) << 8) | (nibbles & 0xF0) << 12;
        if (version >> 8 != VERSION) {
            throw new MediaException.Unsupported("DTS", "DTS Express (LBR) bitstream version " + (version >> 8));
        }
        if (bandLimit(headerFlags & FLAG_BAND_LIMIT) == 0) {
            throw new MediaException.Malformed("reserved DTS LBR band limit flags "
                    + Integer.toHexString(headerFlags & FLAG_BAND_LIMIT));
        }
        if ((headerFlags & FLAG_MULTICHANNEL_DOWNMIX) != 0) {
            // TODO: the embedded multichannel downmix (clause 9.6.6): the text gives the syntax of the scaling and
            // contribution coefficients of the embedded channel sets chunk but neither which coded channels form
            // the downmix nor how the coefficients restore the other channels, and ffmpeg's decoder rejects such
            // streams, so there is nothing to derive or verify them against.
            throw new MediaException.Unsupported("DTS", "DTS Express (LBR) streams with an embedded multichannel"
                    + " downmix");
        }
        var speakers = Speakers.expand(mask) & ~(Speakers.LFE1 | Speakers.LFE2);
        var fullBand = Integer.bitCount(speakers);
        if (fullBand == 0 || fullBand > BITS_LIMIT) {
            throw new MediaException.Malformed("invalid DTS LBR speaker mask " + Integer.toHexString(mask));
        }
        if ((headerFlags & FLAG_STEREO_DOWNMIX) != 0 && fullBand <= 2) {
            throw new MediaException.Malformed("DTS LBR stereo downmix of " + fullBand + " channels");
        }
        if (initialized && rate == sampleRate && mask == speakerMask && headerFlags == flags && original == bitRate
                && scaled == scaledBitRate) {
            return;
        }
        sampleRate = rate;
        speakerMask = mask;
        flags = headerFlags;
        bitRate = original;
        scaledBitRate = scaled;
        configure(speakers, fullBand);
        initialized = true;
    }

    /**
     * The largest number of full band channels the tonal channel indices can address.
     */
    private static final int BITS_LIMIT = 9;

    /**
     * Returns the band limit of the band limit flags (Table 9-7) as the denominator, times two, of the fraction of
     * the bandwidth that is coded: {@code 2} for the full band, {@code 3} for two thirds, {@code 4} for a half,
     * {@code 6} for a third, {@code 8} for a quarter and {@code 16} for an eighth, or {@code 0} for reserved flags.
     */
    private static int bandLimit(int limit) {
        return switch (limit) {
            case BAND_LIMIT_NONE -> 2;
            case BAND_LIMIT_TWO_THIRDS -> 3;
            case BAND_LIMIT_HALF -> 4;
            case BAND_LIMIT_THIRD -> 6;
            case BAND_LIMIT_QUARTER -> 8;
            case BAND_LIMIT_EIGHTH -> 16;
            default -> 0;
        };
    }

    /**
     * Sizes the state for the header in force and clears it.
     *
     * <p>A band limited stream is coded as a stream of the limited sample rate, the header's times the coded
     * fraction of the bandwidth: its frequency range, lines and subbands are those of that rate. The synthesis runs
     * at the header's rate on blocks of as many more lines, the lines above the coded ones left empty, so a frame
     * lasts as long as at the limited rate, as ffmpeg's decoder does for the 1/2 and 1/4 limits (measured).
     */
    private void configure(int speakers, int fullBand) {
        var limit = bandLimit(flags & FLAG_BAND_LIMIT);
        var limitedRate = (int) ((long) sampleRate * 2 / limit);
        frequencyRange = limitedRate < 16000 ? 0 : limitedRate < 32000 ? 1 : 2;
        codedLines = 32 << frequencyRange;
        lines = codedLines * limit / 2;
        tonalScale = StrictMath.sqrt(lines / 128.0);
        frameSamples = BLOCKS * lines;
        channels = fullBand;
        if ((flags & FLAG_STEREO_DOWNMIX) != 0) {
            // as the text allows a decoder of two channels to, the embedded stereo downmix is the output; the
            // other channels, the LFE and the embedded channel sets chunk are skipped
            // TODO: restoring the original channels from the downmix (clause 9.6.6.3): the replacement syntax of
            // the embedded channel sets chunk names variables the text never defines and no reference decoder
            // implements it, so there is nothing to derive it from
            decoded = 2;
            masks = new int[]{Speakers.L, Speakers.R};
        } else {
            decoded = fullBand;
            var lfe = (flags & FLAG_LFE) != 0;
            masks = new int[channels + (lfe ? 1 : 0)];
            var index = 0;
            for (var bit = 0; bit < 32; bit++) {
                if ((speakers & 1 << bit) != 0) {
                    masks[index++] = 1 << bit;
                }
            }
            if (lfe) {
                masks[index] = Speakers.LFE1;
            }
        }
        window = window(lines);
        basis = new float[2 * lines * lines];
        for (var n = 0; n < 2 * lines; n++) {
            for (var k = 0; k < lines; k++) {
                basis[k * 2 * lines + n] = (float) StrictMath.cos(Math.PI / lines * (n + 0.5 + lines * 0.5)
                        * (k + 0.5));
            }
        }
        spectrum = new float[decoded][RING * lines];
        overlap = new float[decoded][lines];
        output = new float[masks.length][frameSamples];
        block = new float[2 * lines];
        frameBlock = 0;
        residual = new LbrResidual(decoded, channels, limitedRate, bitRate, scaledBitRate, lines);
        lfeFactor = sampleRate < 14000 ? LFE_FACTOR / 4 : sampleRate < 28000 ? LFE_FACTOR / 2 : LFE_FACTOR;
        configureLfeFilter(LFE_FACTOR / lfeFactor);
        lfeSamples = new float[2 * (frameSamples / lfeFactor)];
        Arrays.fill(lfeSpill, 0);
        Arrays.fill(lfeModes, 0);
    }

    /**
     * Returns the synthesis window of {@code 2 * n} taps: the measured rising half followed by its mirror. The rising
     * half samples the 128-line one at every {@code 128 / n}th tap, scaled by the square root of {@code 128 / n}, as
     * measured on ffmpeg's decoder at 64 and 32 lines (to 1e-7). Where {@code 128 / n} is not an integer, which only
     * the 2/3, 1/3 and 1/8 band limits give, the taps between those of the 128-line window are interpolated with a
     * cubic through the four nearest ones.
     */
    private static float[] window(int n) {
        var taps = LbrTables.SYNTHESIS_WINDOW;
        var half = taps.length;
        var step = (double) half / n;
        var gain = StrictMath.sqrt(step);
        var w = new float[2 * n];
        for (var i = 0; i < n; i++) {
            var position = i * step;
            var index = (int) position;
            var fraction = position - index;
            double value;
            if (fraction == 0) {
                value = taps[index];
            } else {
                var p0 = windowTap(index - 1);
                var p1 = windowTap(index);
                var p2 = windowTap(index + 1);
                var p3 = windowTap(index + 2);
                value = p1 + 0.5 * fraction * (p2 - p0 + fraction * (2 * p0 - 5 * p1 + 4 * p2 - p3
                        + fraction * (3 * (p1 - p2) + p3 - p0)));
            }
            w[i] = (float) (gain * value);
            w[2 * n - 1 - i] = w[i];
        }
        return w;
    }

    /**
     * {@return a tap of the 256-tap window at 128 lines, the rising half mirrored past its end and odd about its
     * start}
     */
    private static double windowTap(int index) {
        var taps = LbrTables.SYNTHESIS_WINDOW;
        if (index < 0) {
            return -taps[-index];
        }
        return index < taps.length ? taps[index] : taps[2 * taps.length - 1 - index];
    }

    /**
     * Decodes the LBR frame chunk that starts at an offset and synthesizes the frame.
     */
    private void decodeFrame(byte[] data, int offset, int length) {
        if (length < 2) {
            throw new MediaException.Malformed("truncated DTS LBR frame");
        }
        var id = data[offset] & 0xFF;
        var header = (id & 0x80) != 0 ? 3 : 2;
        if (length < header) {
            throw new MediaException.Malformed("truncated DTS LBR frame chunk");
        }
        var size = (id & 0x80) != 0 ? (data[offset + 1] & 0xFF) << 8 | data[offset + 2] & 0xFF
                : data[offset + 1] & 0xFF;
        if (header + size > length) {
            throw new MediaException.Malformed("DTS LBR frame chunk overruns its component");
        }
        var start = offset + header;
        var end = start + size;
        switch (id & 0x7F) {
            case LBR_CHECKSUM -> {
                if (size < 2) {
                    throw new MediaException.Malformed("truncated DTS LBR frame checksum");
                }
                var stored = (data[start] & 0xFF) << 8 | data[start + 1] & 0xFF;
                var sum = 0;
                for (var i = offset; i < end; i++) {
                    if (i != start && i != start + 1) {
                        sum += data[i] & 0xFF;
                    }
                }
                if ((sum & 0xFFFF) != stored) {
                    throw new MediaException.Malformed("DTS LBR frame checksum mismatch");
                }
                start += 2;
            }
            case LBR_PLAIN -> {
            }
            default -> throw new MediaException.Malformed("unknown DTS LBR frame chunk " + Integer.toHexString(id));
        }
        collectChunks(data, start, end);
        readScaleFactors(data);
        decodeTonal(data);
        residual.decode(data, spectrum, RING, frameBlock);
        synthesize();
        if (masks.length > decoded) {
            decodeLfe(data);
        }
        frameBlock += BLOCKS;
    }

    /**
     * Locates the child chunks of the frame chunk.
     */
    private void collectChunks(byte[] data, int start, int end) {
        scaleSize = -1;
        lfeSize = -1;
        Arrays.fill(tonalSizes, -1);
        residual.clearChunks();
        var position = start;
        while (position < end) {
            var id = data[position] & 0xFF;
            var header = (id & 0x80) != 0 ? 3 : 2;
            if (position + header > end) {
                if ((id & 0x7F) == 0) {
                    break;
                }
                throw new MediaException.Malformed("truncated DTS LBR chunk header");
            }
            var size = (id & 0x80) != 0 ? (data[position + 1] & 0xFF) << 8 | data[position + 2] & 0xFF
                    : data[position + 1] & 0xFF;
            var payload = position + header;
            if (payload + size > end) {
                throw new MediaException.Malformed("DTS LBR chunk overruns its frame");
            }
            var kind = id & 0x7F;
            if (kind == EMBEDDED_LEVELS) {
                // the embedded channel sets chunk only serves to restore the channels of an embedded downmix, which
                // this decoder outputs as it is
                position = payload + size;
                continue;
            }
            if (kind >= RESIDUAL_FIRST && kind <= RESIDUAL_LAST) {
                residual.addChunk(kind, payload, size);
            } else if (kind == LFE) {
                lfeOffset = payload;
                lfeSize = size;
            } else if (kind == SCALE_FACTORS) {
                scaleOffset = payload;
                scaleSize = size;
            } else if (kind >= TONAL && kind <= 0x1B) {
                var slot = kind < TONAL_SCALED ? kind - TONAL : kind - TONAL_SCALED;
                tonalIds[slot] = kind;
                tonalOffsets[slot] = payload;
                tonalSizes[slot] = size;
            }
            position = payload + size;
        }
    }

    /**
     * Reads the tonal scale factors of the frame (Table 9-14), zero without a scale factor chunk.
     */
    private void readScaleFactors(byte[] data) {
        Arrays.fill(scaleFactors, 0);
        if (scaleSize < 0) {
            return;
        }
        bits.reset(data, scaleOffset, scaleSize);
        for (var band = 0; band < 6 && bits.left() >= 6; band++) {
            scaleFactors[band] = bits.read(6);
        }
    }

    /**
     * Decodes the tonal chunks of the frame in the order ffmpeg's decoder reads them, as measured: the combined chunk
     * first, all groups from the longest, then the chunks of single groups from the longest group, each of which
     * replaces the components the combined chunk gave its group. A combined chunk sets the scale factors every later
     * group uses: its own ({@link #TONAL_SCALED}) or zeros ({@link #TONAL}).
     */
    private void decodeTonal(byte[] data) {
        openCount = 0;
        pendingCount = 0;
        componentIndex = 0;
        frameComponents = 0;
        var factors = scaleFactors;
        if (tonalSizes[0] >= 0) {
            bits.reset(data, tonalOffsets[0], tonalSizes[0]);
            Arrays.fill(factors, 0);
            if (tonalIds[0] == TONAL_SCALED) {
                for (var band = 0; band < 6 && bits.left() >= 6; band++) {
                    factors[band] = bits.read(6);
                }
            }
            for (var group = 4; group >= 0; group--) {
                if (decodeGroup(group, factors) != GROUP_DONE) {
                    break;
                }
            }
        }
        for (var group = 4; group >= 0; group--) {
            if (tonalSizes[1 + group] >= 0) {
                if (tonalSizes[0] >= 0) {
                    discard(group);
                }
                bits.reset(data, tonalOffsets[1 + group], tonalSizes[1 + group]);
                decodeGroup(group, factors);
            }
        }
        for (var t = 0; t < pendingCount; ) {
            var base = t * PENDING_FIELDS;
            var end = t + 1;
            while (end < pendingCount && pending[end * PENDING_FIELDS + 8] == pending[base + 8]) {
                end++;
            }
            if (end - t == 1) {
                synthesizeTone(pending[base], pending[base + 1], pending[base + 2], pending[base + 3],
                        pending[base + 4], pending[base + 5], pending[base + 6]);
            } else {
                synthesizeShared(t, end);
            }
            t = end;
        }
    }

    /**
     * Drops the queued components read in a group and synthesized in it, which a later chunk of the group replaces.
     */
    private void discard(int group) {
        var kept = 0;
        for (var t = 0; t < pendingCount; t++) {
            var base = t * PENDING_FIELDS;
            if (pending[base + 1] == group && pending[base + 7] == group) {
                continue;
            }
            System.arraycopy(pending, base, pending, kept * PENDING_FIELDS, PENDING_FIELDS);
            kept++;
        }
        pendingCount = kept;
    }

    /**
     * Queues a tonal component of the frame, with a copy for every open group ({@link #openGroups}).
     */
    private void queueAll(int ch, int group, int position, int line, int offset, int amplitude, int phase) {
        queue(ch, group, position, line, offset, amplitude, phase, group);
        for (var i = 0; i < openCount; i++) {
            queue(ch, openGroups[i], openPositions[i], line, offset, amplitude, phase, group);
        }
        componentIndex++;
    }

    /**
     * Queues one tonal component for the synthesis of the frame.
     *
     * @param source the group the component was read in
     */
    private void queue(int ch, int group, int position, int line, int offset, int amplitude, int phase, int source) {
        if ((pendingCount + 1) * PENDING_FIELDS > pending.length) {
            pending = Arrays.copyOf(pending, Math.max(pending.length * 2, 64 * PENDING_FIELDS));
        }
        var base = pendingCount * PENDING_FIELDS;
        pending[base] = ch;
        pending[base + 1] = group;
        pending[base + 2] = position;
        pending[base + 3] = line;
        pending[base + 4] = offset;
        pending[base + 5] = amplitude;
        pending[base + 6] = phase;
        pending[base + 7] = source;
        pending[base + 8] = componentIndex;
        pendingCount++;
    }

    /**
     * Decodes the tonal components of one group (Table 9-17) and queues them for the synthesis.
     *
     * <p>Only malformed streams carry a component within 5 lines of the top of the coded lines. ffmpeg's decoder
     * stops reading the group there; if the frame had components before the position it stops at, it keeps every
     * component read and opens the group at that position ({@link #openGroups}), otherwise it drops the group's
     * components, as this decoder does. An invalid code or a component that overruns the chunk drops the components
     * of the group.
     *
     * @param group the group, from 0 (components of 4 blocks) to 4 (64 blocks)
     * @param factors the scale factors by band
     * @return {@link #GROUP_DONE}, {@link #GROUP_EXHAUSTED} when the chunk ran out of bits between components, or
     *         {@link #GROUP_INVALID}
     */
    private int decodeGroup(int group, int[] factors) {
        var positions = 16 >> group;
        var book = switch (group) {
            case 0 -> LbrTables.PRS_DIST_4;
            case 1 -> LbrTables.PRS_DIST_3;
            case 2 -> LbrTables.PRS_DIST_2;
            case 3 -> LbrTables.PRS_DIST_1;
            default -> LbrTables.PRS_DIST_0;
        };
        var position = 0;
        var frequency = 1;
        var channelBits = channels > 1 ? LbrTables.BITS_FOR_CHANNELS[channels] - 1 : 0;
        var start = pendingCount;
        var positionStart = frameComponents;
        while (true) {
            if (bits.left() <= 0) {
                return GROUP_EXHAUSTED;
            }
            var code = bits.variable(book);
            if (code < 0 || code >= LbrTables.FST_AMP.length) {
                pendingCount = start;
                return GROUP_INVALID;
            }
            var extra = code >> 2;
            var distance = LbrTables.FST_AMP[code] + (extra > 0 ? bits.read(extra) : 0);
            if (distance <= 1) {
                position += distance == 0 ? 1 : 8;
                frequency = 1;
                positionStart = frameComponents;
                if (position >= positions) {
                    return GROUP_DONE;
                }
                continue;
            }
            frequency += distance - 2;
            var main = channelBits > 0 ? bits.read(channelBits) : 0;
            var amplitude = bits.variable(LbrTables.TONAL_SCF);
            var phase = bits.read(3);
            for (var ch = 0; ch < channels; ch++) {
                componentAmplitudes[ch] = ABSENT;
            }
            if (main < channels) {
                componentAmplitudes[main] = amplitude;
                componentPhases[main] = phase;
            }
            for (var ch = 0; ch < channels; ch++) {
                if (ch == main) {
                    continue;
                }
                if (bits.read(1) != 0) {
                    var amplitudeDifference = bits.variable(LbrTables.D_AMP);
                    var phaseDifference = bits.variable(LbrTables.D_PH);
                    componentAmplitudes[ch] = amplitude - amplitudeDifference;
                    componentPhases[ch] = phase - phaseDifference;
                }
            }
            if (bits.left() < 0) {
                pendingCount = start;
                return GROUP_INVALID;
            }
            var line = frequency >> (group + 1);
            if (line >= codedLines - 5) {
                if (positionStart > 0) {
                    openGroups[openCount] = group;
                    openPositions[openCount] = position;
                    openCount++;
                } else {
                    pendingCount = start;
                }
                return GROUP_INVALID;
            }
            frameComponents++;
            var offset = (frequency & ((1 << (group + 1)) - 1)) << (4 - group);
            // the band of a line is the one of the same line at 128 lines, at every rate, as measured
            var band = factors[LbrTables.FREQ_TO_SF[Math.min(31, line >> 2)]];
            for (var ch = 0; ch < decoded; ch++) {
                // a secondary amplitude below 0 is kept: the difference applies to the amplitude with its scale
                // factor, as measured
                if (componentAmplitudes[ch] != ABSENT) {
                    queueAll(ch, group, position, line, offset, componentAmplitudes[ch] + band, componentPhases[ch]);
                }
            }
            frequency++;
        }
    }

    /**
     * Adds one tonal component to the spectrum of a channel. Lines below 0 fold back onto the lowest lines, except
     * for a component in line 0, whose lines below 0 are dropped, and lines past the top fold back with the opposite
     * sign, as measured on ffmpeg's decoder.
     *
     * @param ch the channel
     * @param group the group, from 0 to 4
     * @param position the position in the frame, in steps of half a component
     * @param line the line below the component frequency
     * @param offset the frequency above that line, in 1/32 lines
     * @param amplitude the quantized amplitude with its scale factor
     * @param phase the quantized phase, modulo 8
     */
    private void synthesizeTone(int ch, int group, int position, int line, int offset, int amplitude, int phase) {
        amplitude &= 0x7F;
        if (amplitude > 55) {
            return;
        }
        var gain = LbrTables.amplitude(amplitude) * 0x1p-25 * tonalScale;
        if (gain == 0) {
            return;
        }
        var length = 4 << group;
        var first = frameBlock + TONAL_DELAY + (long) position * (2 << group);
        var angle = line * 16 - 24 - (phase & 7) * 8 - offset;
        var rotation = offset + ((line & 1) << 5);
        for (var i = 0; i < length; i++) {
            var envelope = LbrTables.ENVELOPE[(i + 1) * (64 / length) - 1];
            if (envelope != 0) {
                addBlock(spectrum[ch], first + i, line, offset, gain * envelope, angle - i * rotation);
            }
        }
    }

    /**
     * Adds a tonal component synthesized in several groups, itself and its copies in the open groups, to the
     * spectrum of its channel. ffmpeg's decoder keeps one phase per component read and turns it after each block it
     * synthesizes, block after block and in a block from the longest group, so where the spans overlap the component
     * turns once per group and the copies start from the phase the component reached, as measured; the components of
     * even lines at offset 0 do not turn, so only the others show it.
     *
     * @param from the index of the first queued entry of the component
     * @param to the index past its last entry
     */
    private void synthesizeShared(int from, int to) {
        for (var t = from + 1; t < to; t++) {
            for (var u = t; u > from && pending[u * PENDING_FIELDS + 1] > pending[(u - 1) * PENDING_FIELDS + 1]; u--) {
                for (var f = 0; f < PENDING_FIELDS; f++) {
                    var swap = pending[u * PENDING_FIELDS + f];
                    pending[u * PENDING_FIELDS + f] = pending[(u - 1) * PENDING_FIELDS + f];
                    pending[(u - 1) * PENDING_FIELDS + f] = swap;
                }
            }
        }
        var base = from * PENDING_FIELDS;
        var amplitude = pending[base + 5] & 0x7F;
        if (amplitude > 55) {
            return;
        }
        var gain = LbrTables.amplitude(amplitude) * 0x1p-25 * tonalScale;
        if (gain == 0) {
            return;
        }
        var ch = pending[base];
        var line = pending[base + 3];
        var offset = pending[base + 4];
        var turn = line * 16 - 24 - (pending[base + 6] & 7) * 8 - offset;
        var rotation = offset + ((line & 1) << 5);
        var start = Long.MAX_VALUE;
        var end = Long.MIN_VALUE;
        for (var t = from; t < to; t++) {
            var group = pending[t * PENDING_FIELDS + 1];
            var first = frameBlock + TONAL_DELAY + (long) pending[t * PENDING_FIELDS + 2] * (2 << group);
            start = Math.min(start, first);
            end = Math.max(end, first + (4 << group));
        }
        for (var block = start; block < end; block++) {
            for (var t = from; t < to; t++) {
                var group = pending[t * PENDING_FIELDS + 1];
                var length = 4 << group;
                var i = block - (frameBlock + TONAL_DELAY + (long) pending[t * PENDING_FIELDS + 2] * (2 << group));
                if (i < 0 || i >= length) {
                    continue;
                }
                var envelope = LbrTables.ENVELOPE[((int) i + 1) * (64 / length) - 1];
                if (envelope != 0) {
                    addBlock(spectrum[ch], block, line, offset, gain * envelope, turn);
                    turn -= rotation;
                }
            }
        }
    }

    /**
     * Adds one block of a tonal component to a spectrum ring.
     *
     * @param target the spectrum ring of the channel
     * @param block the absolute index of the block
     * @param line the line below the component frequency
     * @param offset the frequency above that line, in 1/32 lines
     * @param scale the amplitude of the block
     * @param turn the phase of the block, in 1/64 of a turn
     */
    private void addBlock(float[] target, long block, int line, int offset, double scale, int turn) {
        var correction = LbrTables.CORRECTION[offset];
        var slot = (int) Math.floorMod(block, RING) * lines;
        for (var j = -5; j <= 5; j++) {
            var value = (float) (scale * correction[j + 5] * LbrTables.COSINE[(turn + 16 * j + 48) & 63]);
            var k = line + j;
            if (k < 0) {
                if (line > 0) {
                    target[slot - k - 1] += value;
                }
            } else if (k < lines) {
                target[slot + k] += value;
            } else {
                target[slot + 2 * lines - 1 - k] -= value;
            }
        }
    }

    /**
     * Synthesizes the 32 blocks of the frame into the output and clears their spectrum.
     */
    private void synthesize() {
        for (var ch = 0; ch < decoded; ch++) {
            var out = output[ch];
            var tail = overlap[ch];
            var source = spectrum[ch];
            for (var b = 1; b <= BLOCKS; b++) {
                var slot = (int) Math.floorMod(frameBlock + b, RING) * lines;
                inverse(source, slot, block);
                Arrays.fill(source, slot, slot + lines, 0);
                var base = (b - 1) * lines;
                for (var n = 0; n < lines; n++) {
                    out[base + n] = tail[n] + block[n];
                    tail[n] = block[lines + n];
                }
            }
        }
    }

    /**
     * Sets the LFE interpolation filter of {@link #lfeFactor}: the measured filter of {@link #LFE_FACTOR} sampled at
     * every {@code step}th output sample, which keeps its response in time and frequency, so the LFE channel sounds
     * the same at every sample rate. ffmpeg's decoder does not decode the LFE channel below 32 kHz, so nothing
     * shows the filter of the lower factors.
     *
     * @param step {@code LFE_FACTOR / lfeFactor}: 1, 2 or 4
     */
    private void configureLfeFilter(int step) {
        if (step == 1) {
            lfeHead = LbrTables.LFE_HEAD;
            lfePoles = LbrTables.LFE_POLES;
            return;
        }
        lfeHead = new float[LbrTables.LFE_HEAD_LENGTH / step];
        for (var n = 0; n < lfeHead.length; n++) {
            lfeHead[n] = LbrTables.LFE_HEAD[n * step];
        }
        var measured = LbrTables.LFE_POLES;
        lfePoles = new double[measured.length];
        for (var m = 0; m < measured.length; m += 2) {
            double re = 1;
            double im = 0;
            for (var i = 0; i < step; i++) {
                var r = re * measured[m] - im * measured[m + 1];
                im = re * measured[m + 1] + im * measured[m];
                re = r;
            }
            lfePoles[m] = re;
            lfePoles[m + 1] = im;
        }
    }

    /**
     * Decodes the LFE chunk of the frame (Table 9-36) into the LFE channel: ADPCM at {@code 1 / lfeFactor} of the
     * sample rate, so 64 codes per frame at every rate without a band limit, then the interpolation filter.
     *
     * <p>A frame without an LFE chunk, or whose LFE chunk is too short or has a step index past its table, has
     * silent LFE input, as in ffmpeg's decoder. The predicted value is clamped to {@code [-3, 3]}, the limit
     * ffmpeg's decoder applies, not the 1.3 of the text.
     */
    private void decodeLfe(byte[] data) {
        var count = frameSamples / lfeFactor;
        System.arraycopy(lfeSamples, 0, lfeSamples, count, count);
        Arrays.fill(lfeSamples, 0, count, 0);
        var wide = (flags & FLAG_24_BIT) != 0;
        var needed = wide ? 4 + (count * 6 + 7) / 8 : 3 + (count * 4 + 7) / 8;
        if (lfeSize >= needed) {
            bits.reset(data, lfeOffset, lfeSize);
            var magnitude = wide ? bits.read(8) | bits.read(16) << 8 : bits.read(16);
            var limit = wide ? 0x7FFFFF : 0x7FFF;
            var negative = (magnitude & (limit + 1)) != 0;
            var value = (float) (magnitude & limit) / (float) (negative ? -limit : limit);
            var index = bits.read(8);
            var steps = wide ? LbrTables.LFE_STEPS_24 : LbrTables.LFE_STEPS_16;
            var deltas = wide ? LbrTables.LFE_DELTA_24 : LbrTables.LFE_DELTA_16;
            if (index < steps.length) {
                var codeBits = wide ? 6 : 4;
                var sign = 1 << (codeBits - 1);
                for (var i = 0; i < count; i++) {
                    var code = bits.read(codeBits);
                    var step = (float) steps[index];
                    var delta = step / sign;
                    for (int bit = sign >> 1, divisor = 1; bit != 0; bit >>= 1, divisor <<= 1) {
                        if ((code & bit) != 0) {
                            delta += step / divisor;
                        }
                    }
                    value = (code & sign) != 0 ? Math.max(value - delta, -LFE_CLAMP)
                            : Math.min(value + delta, LFE_CLAMP);
                    index = Math.clamp(index + deltas[code & (sign - 1)], 0, steps.length - 1);
                    lfeSamples[i] = value;
                }
            }
        }
        interpolateLfe(count);
    }

    /**
     * Runs the LFE interpolation filter over the frame: its head response of {@code lfeHead.length} samples, then
     * the decaying response of its ten poles.
     *
     * @param count the number of LFE samples of the frame
     */
    private void interpolateLfe(int count) {
        var out = output[decoded];
        var head = lfeHead;
        var length = head.length;
        System.arraycopy(lfeSpill, 0, out, 0, length);
        Arrays.fill(out, length, frameSamples, 0);
        Arrays.fill(lfeSpill, 0);
        for (var k = 0; k < count; k++) {
            var value = lfeSamples[k];
            if (value == 0) {
                continue;
            }
            var start = k * lfeFactor;
            var inside = Math.min(length, frameSamples - start);
            for (var n = 0; n < inside; n++) {
                out[start + n] += value * head[n];
            }
            for (var n = inside; n < length; n++) {
                lfeSpill[start + n - frameSamples] += value * head[n];
            }
        }
        var poles = lfePoles;
        var residues = LbrTables.LFE_RESIDUES;
        var delay = length / lfeFactor;
        for (var m = 0; m < poles.length; m += 2) {
            var pr = poles[m];
            var pi = poles[m + 1];
            var cr = residues[m];
            var ci = residues[m + 1];
            var sr = lfeModes[m];
            var si = lfeModes[m + 1];
            for (var t = 0; t < frameSamples; t++) {
                var r = pr * sr - pi * si;
                si = pr * si + pi * sr;
                sr = r;
                if (t % lfeFactor == 0) {
                    var k = t / lfeFactor - delay;
                    var value = k >= 0 ? lfeSamples[k] : lfeSamples[2 * count + k];
                    sr += cr * value;
                    si += ci * value;
                }
                out[t] += (float) sr;
            }
            lfeModes[m] = sr;
            lfeModes[m + 1] = si;
        }
    }

    /**
     * Computes the windowed inverse MDCT of one block of the spectrum.
     *
     * @param source the spectrum
     * @param slot the index of the block's first line
     * @param target the {@code 2 * lines} samples
     */
    private void inverse(float[] source, int slot, float[] target) {
        Arrays.fill(target, 0);
        for (var k = 0; k < lines; k++) {
            var x = source[slot + k];
            if (x == 0) {
                continue;
            }
            var row = k * 2 * lines;
            for (var n = 0; n < 2 * lines; n++) {
                target[n] += x * basis[row + n];
            }
        }
        for (var n = 0; n < 2 * lines; n++) {
            target[n] *= window[n];
        }
    }

    /**
     * {@return the number of samples per channel of the last frame}
     */
    int outputSamples() {
        return produced ? frameSamples : 0;
    }

    /**
     * {@return the sample rate of the last frame}
     */
    int outputRate() {
        return sampleRate;
    }

    /**
     * {@return the speaker bit of each output channel}
     */
    int[] masks() {
        return masks;
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
     * {@return the speaker mask of the last decoder initialization header (Table 7-10), or {@code 0}}
     */
    int speakerMask() {
        return speakerMask;
    }

    /**
     * Clears the state, as at the start of a stream.
     */
    void reset() {
        initialized = false;
        produced = false;
        sampleRate = 0;
        speakerMask = 0;
        flags = 0;
        bitRate = 0;
        scaledBitRate = 0;
        for (var ch = 0; ch < spectrum.length; ch++) {
            Arrays.fill(spectrum[ch], 0);
            Arrays.fill(overlap[ch], 0);
        }
        frameBlock = 0;
        residual.reset();
        Arrays.fill(lfeSamples, 0);
        Arrays.fill(lfeSpill, 0);
        Arrays.fill(lfeModes, 0);
    }

    /**
     * A least significant bit first reader of one chunk.
     */
    static final class Bits {
        /**
         * The bytes.
         */
        private byte[] data = new byte[0];

        /**
         * The index of the next bit, from the start of the array.
         */
        private long position;

        /**
         * The index of the bit after the chunk.
         */
        private long end;

        /**
         * Starts reading a chunk.
         */
        void reset(byte[] data, int offset, int length) {
            this.data = data;
            position = (long) offset << 3;
            end = (long) (offset + length) << 3;
        }

        /**
         * {@return the number of bits left in the chunk, negative after an overrun}
         */
        long left() {
            return end - position;
        }

        /**
         * Reads bits, least significant first; bits past the chunk read as zero.
         *
         * @param count the number of bits, at most 31
         * @return the value
         */
        int read(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                if (position < end) {
                    value |= (data[(int) (position >>> 3)] >> (int) (position & 7) & 1) << i;
                }
                position++;
            }
            return value;
        }

        /**
         * Returns the next bits, least significant first, without consuming them; bits past the chunk read as zero.
         *
         * @param count the number of bits, at most 31
         * @return the value
         */
        int peek(int count) {
            var saved = position;
            var value = read(count);
            position = saved;
            return value;
        }

        /**
         * Consumes bits.
         *
         * @param count the number of bits
         */
        void skip(int count) {
            position += count;
        }

        /**
         * Reads a value with an index hopping code book ({@code getVariableParam}, Table 9-18): the value of the
         * leaf, or after an escape leaf a 3-bit length minus one and that many bits.
         *
         * @param book the (A, B) pairs
         * @return the value
         */
        int variable(int[] book) {
            var row = 1;
            while (book[2 * row] != 0xFF) {
                row += read(1) != 0 ? book[2 * row + 1] : book[2 * row];
                if (row >= book.length / 2) {
                    return -1;
                }
            }
            var leaf = book[2 * row + 1];
            if (leaf == 0) {
                return read(read(3) + 1);
            }
            return leaf - 1;
        }
    }
}
