/* Ports WavPack 5.8.1 (src/unpack.c, src/read_words.c, src/entropy_utils.c, src/decorr_utils.c,
   src/unpack_floats.c, src/open_utils.c). Copyright (c) 1998 - 2025 David Bryant; BSD 3-Clause License. Modified
   for Smalt. */
package com.github.auties00.smalt.audio.wavpack;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes the mono or stereo PCM blocks of one WavPack stream: the decorrelation passes, the adaptive Golomb words
 * of the lossless and hybrid modes, the correction bitstream of hybrid lossless reconstruction with its noise
 * shaping, the extended integer and floating-point data, and the block checksums.
 *
 * <p>Instances are not thread-safe.
 */
final class WavPackStream {
    static final int BYTES_STORED = 3;
    static final int MONO_FLAG = 4;
    static final int HYBRID_FLAG = 8;
    static final int JOINT_STEREO = 0x10;
    static final int CROSS_DECORR = 0x20;
    static final int HYBRID_SHAPE = 0x40;
    static final int FLOAT_DATA = 0x80;
    static final int INT32_DATA = 0x100;
    static final int HYBRID_BITRATE = 0x200;
    static final int HYBRID_BALANCE = 0x400;
    static final int INITIAL_BLOCK = 0x800;
    static final int FINAL_BLOCK = 0x1000;
    static final int SHIFT_LSB = 13;
    static final int SHIFT_MASK = 0x1f << SHIFT_LSB;
    static final int MAG_LSB = 18;
    static final int MAG_MASK = 0x1f << MAG_LSB;
    static final int SRATE_LSB = 23;
    static final int SRATE_MASK = 0xf << SRATE_LSB;
    static final int FALSE_STEREO = 0x40000000;
    static final int NEW_SHAPING = 0x20000000;
    static final int MONO_DATA = MONO_FLAG | FALSE_STEREO;
    static final int HAS_CHECKSUM = 0x10000000;
    static final int DSD_FLAG = 0x80000000;

    static final int ID_UNIQUE = 0x3f;
    static final int ID_OPTIONAL_DATA = 0x20;
    static final int ID_ODD_SIZE = 0x40;
    static final int ID_LARGE = 0x80;
    static final int ID_DUMMY = 0x0;
    static final int ID_DECORR_TERMS = 0x2;
    static final int ID_DECORR_WEIGHTS = 0x3;
    static final int ID_DECORR_SAMPLES = 0x4;
    static final int ID_ENTROPY_VARS = 0x5;
    static final int ID_HYBRID_PROFILE = 0x6;
    static final int ID_SHAPING_WEIGHTS = 0x7;
    static final int ID_FLOAT_INFO = 0x8;
    static final int ID_INT32_INFO = 0x9;
    static final int ID_WV_BITSTREAM = 0xa;
    static final int ID_WVC_BITSTREAM = 0xb;
    static final int ID_WVX_BITSTREAM = 0xc;
    static final int ID_CHANNEL_INFO = 0xd;
    static final int ID_DSD_BLOCK = 0xe;
    static final int ID_SAMPLE_RATE = ID_OPTIONAL_DATA | 0x7;
    static final int ID_MD5_CHECKSUM = ID_OPTIONAL_DATA | 0x6;
    static final int ID_NEW_CONFIG_BLOCK = ID_OPTIONAL_DATA | 0xa;
    static final int ID_WVX_NEW_BITSTREAM = ID_OPTIONAL_DATA | ID_WVX_BITSTREAM;
    static final int ID_BLOCK_CHECKSUM = ID_OPTIONAL_DATA | 0xf;
    static final int ID_CHANNEL_IDENTITIES = ID_OPTIONAL_DATA | 0xb;

    private static final int FLOAT_SHIFT_ONES = 1;
    private static final int FLOAT_SHIFT_SAME = 2;
    private static final int FLOAT_SHIFT_SENT = 4;
    private static final int FLOAT_ZEROS_SENT = 8;
    private static final int FLOAT_NEG_ZEROS = 0x10;

    private static final int MAX_NTERMS = 16;
    private static final int MAX_TERM = 8;
    private static final int LIMIT_ONES = 16;
    private static final int SLS = 8;
    private static final int SLO = 1 << (SLS - 1);

    /**
     * The value of {@code get_word} at the end of the bitstream or on an invalid code.
     */
    private static final int WORD_EOF = Integer.MIN_VALUE;

    int flags;

    int version;

    int blockSamples;

    int expectedCrc;

    private int crc;

    private int crcX;

    private int crcWvx;

    private boolean correction;

    private int numTerms;

    private final int[] term = new int[MAX_NTERMS];

    private final int[] delta = new int[MAX_NTERMS];

    private final int[] weightA = new int[MAX_NTERMS];

    private final int[] weightB = new int[MAX_NTERMS];

    private final int[][] samplesA = new int[MAX_NTERMS][MAX_TERM];

    private final int[][] samplesB = new int[MAX_NTERMS][MAX_TERM];

    private final int[] bitrateDelta = new int[2];

    private final int[] bitrateAcc = new int[2];

    private int holdingOne;

    private int zerosAcc;

    private int holdingZero;

    /**
     * The three medians of each channel, unsigned.
     */
    private final int[][] median = new int[2][3];

    private final int[] slowLevel = new int[2];

    private final int[] errorLimit = new int[2];

    private final int[] shapingAcc = new int[2];

    private final int[] shapingDelta = new int[2];

    private final int[] shapingError = new int[2];

    private int int32SentBits;

    private int int32Zeros;

    private int int32Ones;

    private int int32Dups;

    private int int32MaxWidth;

    private int floatFlags;

    private int floatShift;

    private int floatMaxExp;

    private int floatNormExp;

    private int floatMinShiftedZeros;

    private int floatMaxShiftedOnes;

    private final WavPackBitReader wv = new WavPackBitReader();

    private final WavPackBitReader wvc = new WavPackBitReader();

    private final WavPackBitReader wvx = new WavPackBitReader();

    private final int[] corrections = new int[2];

    final WavPackDsd dsd = new WavPackDsd();

    /**
     * The facts of the stream that the metadata of a block reports beyond the block itself.
     */
    static final class StreamInfo {
        int channels;

        int maxStreams;

        long channelMask;

        int sampleRate;

        byte[] md5;

        /**
         * The qualify mode of the configuration: bit 0 big-endian, bit 1 signed bytes, bit 2 unsigned words.
         */
        int qmode;
    }

    /**
     * {@return the exponent of a floating-point stream's full scale, 127 for a full scale of 1}
     */
    int floatNormExp() {
        return floatNormExp;
    }

    /**
     * Prepares the stream for a block ({@code unpack_init}): reads the header fields and every metadata
     * sub-block of the block and of its correction block.
     *
     * @param block the array holding the block, its 32-byte header first
     * @param offset the first byte of the block
     * @param correctionBlock the array holding the matching correction block, or {@code null}
     * @param correctionOffset the first byte of the correction block
     * @param info receives the stream facts the metadata reports
     * @throws MediaException.Malformed if the block or its metadata is invalid
     * @throws MediaException.Unsupported if the block uses a coding feature this decoder lacks
     */
    void init(byte[] block, int offset, byte[] correctionBlock, int correctionOffset, StreamInfo info) {
        version = u16(block, offset + 8);
        blockSamples = i32(block, offset + 20);
        flags = i32(block, offset + 24);
        expectedCrc = i32(block, offset + 28);
        if (version < 0x402 || version > 0x410) {
            throw new MediaException.Unsupported("wavpack", "WavPack stream version 0x" + Integer.toHexString(version));
        }
        numTerms = 0;
        crc = crcX = 0xffffffff;
        wv.close();
        wvc.close();
        wvx.close();
        for (var pass = 0; pass < MAX_NTERMS; pass++) {
            term[pass] = delta[pass] = weightA[pass] = weightB[pass] = 0;
            Arrays.fill(samplesA[pass], 0);
            Arrays.fill(samplesB[pass], 0);
        }
        Arrays.fill(shapingAcc, 0);
        Arrays.fill(shapingDelta, 0);
        Arrays.fill(shapingError, 0);
        Arrays.fill(bitrateDelta, 0);
        Arrays.fill(bitrateAcc, 0);
        holdingOne = zerosAcc = holdingZero = 0;
        for (var channel = 0; channel < 2; channel++) {
            Arrays.fill(median[channel], 0);
            slowLevel[channel] = errorLimit[channel] = 0;
        }
        int32SentBits = int32Zeros = int32Ones = int32Dups = int32MaxWidth = 0;
        floatFlags = floatShift = floatMaxExp = floatMinShiftedZeros = floatMaxShiftedOnes = 0;
        floatNormExp = 127;
        dsd.reset();
        if ((flags & MONO_DATA) == MONO_DATA) {
            throw new MediaException.Malformed("WavPack block flagged both mono and false stereo");
        }
        readMetadata(block, offset, info);
        correction = false;
        if (correctionBlock != null && blockSamples != 0) {
            expectedCrc = i32(correctionBlock, correctionOffset + 28);
            flags = i32(correctionBlock, correctionOffset + 24);
            readMetadata(correctionBlock, correctionOffset, info);
            correction = true;
        }
        if (blockSamples != 0) {
            if ((flags & DSD_FLAG) != 0 ? !dsd.ready() : !wv.isOpen()) {
                throw new MediaException.Malformed("WavPack block without its audio bitstream");
            }
        }
    }

    /**
     * {@return whether the block carries the extra bitstream of lossless floating-point or wide integer data, or
     * codes them losslessly without one}
     */
    boolean lossless() {
        if ((flags & HYBRID_FLAG) != 0 && !correction) {
            return false;
        }
        if (!wvx.isOpen()) {
            if ((flags & INT32_DATA) != 0 && int32SentBits != 0) {
                return false;
            }
            return (flags & FLOAT_DATA) == 0
                    || (floatFlags & (0x20 | FLOAT_ZEROS_SENT | FLOAT_SHIFT_SENT | FLOAT_SHIFT_SAME)) == 0;
        }
        return true;
    }

    private void readMetadata(byte[] block, int offset, StreamInfo info) {
        var end = offset + i32(block, offset + 4) + 8;
        var position = offset + 32;
        while (end - position >= 2) {
            var id = block[position++] & 0xFF;
            var length = (block[position++] & 0xFF) << 1;
            if ((id & ID_LARGE) != 0) {
                id &= ~ID_LARGE;
                if (end - position < 2) {
                    throw new MediaException.Malformed("WavPack metadata header truncated");
                }
                length += (block[position++] & 0xFF) << 9;
                length += (block[position++] & 0xFF) << 17;
            }
            if ((id & ID_ODD_SIZE) != 0) {
                if (length == 0) {
                    throw new MediaException.Malformed("WavPack metadata of odd size zero");
                }
                id &= ~ID_ODD_SIZE;
                length--;
            }
            if (end - position < length + (length & 1)) {
                throw new MediaException.Malformed("WavPack metadata runs past its block");
            }
            if (!process(id, block, position, length, info)) {
                throw new MediaException.Malformed("invalid WavPack metadata 0x" + Integer.toHexString(id));
            }
            position += length + (length & 1);
        }
    }

    private boolean process(int id, byte[] data, int at, int length, StreamInfo info) {
        return switch (id) {
            case ID_DUMMY -> true;
            case ID_DECORR_TERMS -> readDecorrTerms(data, at, length);
            case ID_DECORR_WEIGHTS -> readDecorrWeights(data, at, length);
            case ID_DECORR_SAMPLES -> readDecorrSamples(data, at, length);
            case ID_ENTROPY_VARS -> readEntropyVars(data, at, length);
            case ID_HYBRID_PROFILE -> readHybridProfile(data, at, length);
            case ID_SHAPING_WEIGHTS -> readShapingInfo(data, at, length);
            case ID_FLOAT_INFO -> {
                if (length != 4) {
                    yield false;
                }
                floatFlags = data[at] & 0xFF;
                floatShift = data[at + 1] & 0xFF;
                floatMaxExp = data[at + 2] & 0xFF;
                floatNormExp = data[at + 3] & 0xFF;
                yield true;
            }
            case ID_INT32_INFO -> {
                if (length != 4) {
                    yield false;
                }
                int32SentBits = data[at] & 0xFF;
                int32Zeros = data[at + 1] & 0xFF;
                int32Ones = data[at + 2] & 0xFF;
                int32Dups = data[at + 3] & 0xFF;
                yield true;
            }
            case ID_CHANNEL_INFO -> readChannelInfo(data, at, length, info);
            case ID_CHANNEL_IDENTITIES -> {
                for (var i = 0; i < length; i++) {
                    if (data[at + i] == 0) {
                        yield false;
                    }
                }
                yield length > 0;
            }
            case ID_SAMPLE_RATE -> {
                if (length == 3 || length == 4) {
                    var rate = (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16;
                    if (length == 4) {
                        rate |= (data[at + 3] & 0x7F) << 24;
                    }
                    if (info.sampleRate == 0) {
                        info.sampleRate = rate;
                    }
                }
                yield true;
            }
            case ID_NEW_CONFIG_BLOCK -> {
                if (length >= 2) {
                    info.qmode = data[at + 1] & 0xFF;
                }
                yield true;
            }
            case ID_MD5_CHECKSUM -> {
                if (length == 16 && info.md5 == null) {
                    info.md5 = Arrays.copyOfRange(data, at, at + 16);
                }
                yield true;
            }
            case ID_WV_BITSTREAM -> {
                if (length == 0 || (length & 1) != 0) {
                    yield false;
                }
                wv.open(data, at, at + length);
                yield true;
            }
            case ID_WVC_BITSTREAM -> {
                if (length == 0 || (length & 1) != 0) {
                    yield false;
                }
                wvc.open(data, at, at + length);
                yield true;
            }
            case ID_WVX_BITSTREAM, ID_WVX_NEW_BITSTREAM -> {
                if (length <= 4 || (length & 1) != 0) {
                    yield false;
                }
                crcWvx = i32(data, at);
                wvx.open(data, at + 4, at + length);
                if (id == ID_WVX_NEW_BITSTREAM) {
                    if ((flags & FLOAT_DATA) != 0) {
                        floatMinShiftedZeros = wvx.bits(5) & 0x1f;
                        floatMaxShiftedOnes = wvx.bits(5) & 0x1f;
                    } else {
                        int32MaxWidth = wvx.bits(5) & 0x1f;
                    }
                }
                yield true;
            }
            case ID_DSD_BLOCK -> dsd.init(data, at, length, flags, blockSamples);
            default -> (id & ID_OPTIONAL_DATA) != 0;
        };
    }

    private boolean readDecorrTerms(byte[] data, int at, int length) {
        if (length > MAX_NTERMS) {
            return false;
        }
        numTerms = length;
        for (var pass = length - 1; pass >= 0; pass--) {
            var value = data[at++] & 0xFF;
            var t = (value & 0x1f) - 5;
            term[pass] = t;
            delta[pass] = (value >> 5) & 7;
            if (t == 0 || t < -3 || (t > MAX_TERM && t < 17) || t > 18 || ((flags & MONO_DATA) != 0 && t < 0)) {
                return false;
            }
        }
        return true;
    }

    private boolean readDecorrWeights(byte[] data, int at, int length) {
        var count = (flags & MONO_DATA) == 0 ? length / 2 : length;
        if (count > numTerms) {
            return false;
        }
        for (var pass = 0; pass < numTerms; pass++) {
            weightA[pass] = weightB[pass] = 0;
        }
        for (var pass = numTerms - 1; pass >= 0 && count-- > 0; pass--) {
            weightA[pass] = WavPackTables.restoreWeight(data[at++]);
            if ((flags & MONO_DATA) == 0) {
                weightB[pass] = WavPackTables.restoreWeight(data[at++]);
            }
        }
        return true;
    }

    private static int s16(byte[] data, int at) {
        return (short) ((data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8);
    }

    private boolean readDecorrSamples(byte[] data, int at, int length) {
        var end = at + length;
        var mono = (flags & MONO_DATA) != 0;
        for (var pass = 0; pass < numTerms; pass++) {
            Arrays.fill(samplesA[pass], 0);
            Arrays.fill(samplesB[pass], 0);
        }
        if (version == 0x402 && (flags & HYBRID_FLAG) != 0) {
            if (at + (mono ? 2 : 4) > end) {
                return false;
            }
            shapingError[0] = WavPackTables.exp2s(s16(data, at));
            at += 2;
            if (!mono) {
                shapingError[1] = WavPackTables.exp2s(s16(data, at));
                at += 2;
            }
        }
        for (var pass = numTerms - 1; pass >= 0 && at < end; pass--) {
            var t = term[pass];
            if (t > MAX_TERM) {
                if (at + (mono ? 4 : 8) > end) {
                    return false;
                }
                samplesA[pass][0] = WavPackTables.exp2s(s16(data, at));
                samplesA[pass][1] = WavPackTables.exp2s(s16(data, at + 2));
                at += 4;
                if (!mono) {
                    samplesB[pass][0] = WavPackTables.exp2s(s16(data, at));
                    samplesB[pass][1] = WavPackTables.exp2s(s16(data, at + 2));
                    at += 4;
                }
            } else if (t < 0) {
                if (at + 4 > end) {
                    return false;
                }
                samplesA[pass][0] = WavPackTables.exp2s(s16(data, at));
                samplesB[pass][0] = WavPackTables.exp2s(s16(data, at + 2));
                at += 4;
            } else {
                for (var m = 0; m < t; m++) {
                    if (at + (mono ? 2 : 4) > end) {
                        return false;
                    }
                    samplesA[pass][m] = WavPackTables.exp2s(s16(data, at));
                    at += 2;
                    if (!mono) {
                        samplesB[pass][m] = WavPackTables.exp2s(s16(data, at));
                        at += 2;
                    }
                }
            }
        }
        return at == end;
    }

    private boolean readShapingInfo(byte[] data, int at, int length) {
        var mono = (flags & MONO_DATA) != 0;
        if (length == 2) {
            shapingAcc[0] = WavPackTables.restoreWeight(data[at]) << 16;
            shapingAcc[1] = WavPackTables.restoreWeight(data[at + 1]) << 16;
            return true;
        }
        if (length >= (mono ? 4 : 8)) {
            shapingError[0] = WavPackTables.exp2s(s16(data, at));
            shapingAcc[0] = WavPackTables.exp2s(s16(data, at + 2));
            at += 4;
            if (!mono) {
                shapingError[1] = WavPackTables.exp2s(s16(data, at));
                shapingAcc[1] = WavPackTables.exp2s(s16(data, at + 2));
                at += 4;
            }
            if (length == (mono ? 6 : 12)) {
                shapingDelta[0] = WavPackTables.exp2s(s16(data, at));
                if (!mono) {
                    shapingDelta[1] = WavPackTables.exp2s(s16(data, at + 2));
                }
            }
            return true;
        }
        return false;
    }

    private boolean readEntropyVars(byte[] data, int at, int length) {
        var mono = (flags & MONO_DATA) != 0;
        if (length != (mono ? 6 : 12)) {
            return false;
        }
        for (var i = 0; i < 3; i++) {
            median[0][i] = WavPackTables.exp2s(u16(data, at + 2 * i));
        }
        if (!mono) {
            for (var i = 0; i < 3; i++) {
                median[1][i] = WavPackTables.exp2s(u16(data, at + 6 + 2 * i));
            }
        }
        return true;
    }

    private boolean readHybridProfile(byte[] data, int at, int length) {
        var end = at + length;
        var mono = (flags & MONO_DATA) != 0;
        var step = mono ? 2 : 4;
        if ((flags & HYBRID_BITRATE) != 0) {
            if (at + step > end) {
                return false;
            }
            slowLevel[0] = WavPackTables.exp2s(u16(data, at));
            at += 2;
            if (!mono) {
                slowLevel[1] = WavPackTables.exp2s(u16(data, at));
                at += 2;
            }
        }
        if (at + step > end) {
            return false;
        }
        bitrateAcc[0] = u16(data, at) << 16;
        at += 2;
        if (!mono) {
            bitrateAcc[1] = u16(data, at) << 16;
            at += 2;
        }
        if (at < end) {
            if (at + step > end) {
                return false;
            }
            bitrateDelta[0] = WavPackTables.exp2s(s16(data, at));
            at += 2;
            if (!mono) {
                bitrateDelta[1] = WavPackTables.exp2s(s16(data, at));
                at += 2;
            }
            return at >= end;
        }
        bitrateDelta[0] = bitrateDelta[1] = 0;
        return true;
    }

    private static boolean readChannelInfo(byte[] data, int at, int length, StreamInfo info) {
        if (length == 0 || length > 7) {
            return false;
        }
        if (info.channels != 0) {
            return true;
        }
        long mask = 0;
        int channels;
        var maxStreams = 8;
        if (length >= 6) {
            channels = ((data[at] & 0xFF) | (data[at + 2] & 0xF) << 8) + 1;
            maxStreams = ((data[at + 1] & 0xFF) | (data[at + 2] & 0xF0) << 4) + 1;
            if (channels < maxStreams) {
                return false;
            }
            mask = (data[at + 3] & 0xFF) | (data[at + 4] & 0xFF) << 8 | (data[at + 5] & 0xFF) << 16;
            if (length == 7) {
                mask |= (long) (data[at + 6] & 0xFF) << 24;
            }
        } else {
            channels = data[at] & 0xFF;
            for (var i = 1; i < length; i++) {
                mask |= (long) (data[at + i] & 0xFF) << (8 * (i - 1));
            }
            if (channels > 8) {
                maxStreams = channels;
            }
        }
        if (channels == 0 || channels > maxStreams * 2 || Long.bitCount(mask) > channels) {
            return false;
        }
        info.channels = channels;
        info.maxStreams = maxStreams;
        info.channelMask = mask;
        return true;
    }

    /**
     * Decodes the samples of the PCM block into {@code buffer}, interleaved for a stereo block (a false stereo
     * block's mono samples repeated in both channels), as 32-bit integers or the bits of 32-bit floats
     * ({@code unpack_samples}).
     *
     * @param buffer receives {@code blockSamples} samples per channel of the block
     * @throws MediaException.Malformed if the bitstream is corrupt or the block fails its checksums
     */
    void unpack(int[] buffer) {
        var sampleCount = blockSamples;
        if ((flags & DSD_FLAG) != 0) {
            crc = dsd.unpack(buffer, sampleCount, flags, crc);
            if ((flags & FALSE_STEREO) != 0) {
                for (var i = sampleCount - 1; i >= 0; i--) {
                    buffer[2 * i + 1] = buffer[2 * i] = buffer[i];
                }
            }
            if (crc != expectedCrc) {
                throw new MediaException.Malformed("WavPack block fails its checksum");
            }
            return;
        }
        var muteLimit = (int) ((1L << ((flags & MAG_MASK) >>> MAG_LSB)) + 2);
        var m = 0;
        if ((flags & HYBRID_FLAG) != 0 && !correction) {
            muteLimit = muteLimit * 2 + 128;
        }
        var count = 0;
        var mono = (flags & MONO_DATA) != 0;
        if (!correction && mono) {
            if ((flags & HYBRID_FLAG) != 0) {
                for (count = 0; count < sampleCount; count++) {
                    var word = getWord(0, false);
                    if (word == WORD_EOF) {
                        break;
                    }
                    buffer[count] = word;
                }
            } else {
                count = getWordsLossless(buffer, sampleCount);
            }
            if (count == sampleCount) {
                for (var pass = 0; pass < numTerms; pass++) {
                    decorrMonoPass(pass, buffer, sampleCount);
                }
                for (var i = 0; i < sampleCount; i++) {
                    if (Math.abs((long) buffer[i]) > muteLimit) {
                        count = i;
                        break;
                    }
                    crc = crc * 3 + buffer[i];
                }
            }
        } else if (!correction) {
            if ((flags & HYBRID_FLAG) != 0) {
                for (count = 0; count < sampleCount; count++) {
                    var left = getWord(0, false);
                    if (left == WORD_EOF) {
                        break;
                    }
                    buffer[2 * count] = left;
                    var right = getWord(1, false);
                    if (right == WORD_EOF) {
                        break;
                    }
                    buffer[2 * count + 1] = right;
                }
            } else {
                count = getWordsLossless(buffer, sampleCount);
            }
            if (count == sampleCount) {
                for (var pass = 0; pass < numTerms; pass++) {
                    decorrStereoPass(pass, buffer, sampleCount);
                }
                m = sampleCount & (MAX_TERM - 1);
                var joint = (flags & JOINT_STEREO) != 0;
                for (var i = 0; i < 2 * sampleCount; i += 2) {
                    if (joint) {
                        buffer[i + 1] -= buffer[i] >> 1;
                        buffer[i] += buffer[i + 1];
                    }
                    crc += (crc << 3) + (buffer[i] << 1) + buffer[i] + buffer[i + 1];
                }
                for (var i = 0; i < 2 * sampleCount; i += 16) {
                    if (Math.abs((long) buffer[i]) > muteLimit || Math.abs((long) buffer[i + 1]) > muteLimit) {
                        count = i / 2;
                        break;
                    }
                }
            }
        } else if (mono) {
            count = unpackCorrectedMono(buffer, sampleCount, muteLimit);
        } else {
            count = unpackCorrectedStereo(buffer, sampleCount, muteLimit);
            m = correctedM;
        }
        if (count != sampleCount) {
            throw new MediaException.Malformed("corrupt WavPack block");
        }
        if (m != 0) {
            for (var pass = 0; pass < numTerms; pass++) {
                if (term[pass] > 0 && term[pass] <= MAX_TERM) {
                    rotate(samplesA[pass], m);
                    rotate(samplesB[pass], m);
                }
            }
        }
        fixup(buffer, mono ? sampleCount : sampleCount * 2);
        if ((flags & FALSE_STEREO) != 0) {
            for (var i = sampleCount - 1; i >= 0; i--) {
                buffer[2 * i + 1] = buffer[2 * i] = buffer[i];
            }
        }
        if (crc != expectedCrc || (wvx.isOpen() && crcX != crcWvx)) {
            throw new MediaException.Malformed("WavPack block fails its checksum");
        }
    }

    private final int[] rotation = new int[MAX_TERM];

    private void rotate(int[] samples, int m) {
        System.arraycopy(samples, 0, rotation, 0, MAX_TERM);
        for (var k = 0; k < MAX_TERM; k++) {
            samples[k] = rotation[m];
            m = (m + 1) & (MAX_TERM - 1);
        }
    }

    /**
     * The history index the corrected stereo path ends at.
     */
    private int correctedM;

    private int unpackCorrectedMono(int[] buffer, int sampleCount, int muteLimit) {
        var m = 0;
        int i;
        for (i = 0; i < sampleCount; i++) {
            var word = getWord(0, true);
            if (word == WORD_EOF) {
                break;
            }
            for (var pass = 0; pass < numTerms; pass++) {
                var history = samplesA[pass];
                int sam;
                int k;
                if (term[pass] > MAX_TERM) {
                    sam = (term[pass] & 1) != 0 ? 2 * history[0] - history[1] : (3 * history[0] - history[1]) >> 1;
                    history[1] = history[0];
                    k = 0;
                } else {
                    sam = history[m];
                    k = (m + term[pass]) & (MAX_TERM - 1);
                }
                var temp = applyWeight(weightA[pass], sam) + word;
                weightA[pass] = updateWeight(weightA[pass], delta[pass], sam, word);
                history[k] = word = temp;
            }
            m = (m + 1) & (MAX_TERM - 1);
            if ((flags & HYBRID_SHAPE) != 0) {
                var shapingWeight = (shapingAcc[0] += shapingDelta[0]) >> 16;
                var temp = -applyWeight(shapingWeight, shapingError[0]);
                if ((flags & NEW_SHAPING) != 0 && shapingWeight < 0 && temp != 0) {
                    if (temp == shapingError[0]) {
                        temp = temp < 0 ? temp + 1 : temp - 1;
                    }
                    shapingError[0] = temp - corrections[0];
                } else {
                    shapingError[0] = -corrections[0];
                }
                word += corrections[0] - temp;
            } else {
                word += corrections[0];
            }
            crc += (crc << 1) + word;
            if (Math.abs((long) word) > muteLimit) {
                break;
            }
            buffer[i] = word;
        }
        if (m != 0) {
            for (var pass = 0; pass < numTerms; pass++) {
                if (term[pass] > 0 && term[pass] <= MAX_TERM) {
                    rotate(samplesA[pass], m);
                }
            }
        }
        return i;
    }

    private int unpackCorrectedStereo(int[] buffer, int sampleCount, int muteLimit) {
        var m = 0;
        int i;
        var cross = (flags & CROSS_DECORR) != 0;
        var joint = (flags & JOINT_STEREO) != 0;
        for (i = 0; i < sampleCount; i++) {
            var left = getWord(0, true);
            if (left == WORD_EOF) {
                break;
            }
            var correctionLeft = corrections[0];
            var right = getWord(1, true);
            if (right == WORD_EOF) {
                break;
            }
            var correctionRight = corrections[1];
            var leftC = 0;
            var rightC = 0;
            if (cross) {
                leftC = left + correctionLeft;
                rightC = right + correctionRight;
                for (var pass = 0; pass < numTerms; pass++) {
                    var t = term[pass];
                    if (t > 0) {
                        int samA;
                        int samB;
                        var a = samplesA[pass];
                        var b = samplesB[pass];
                        if (t > MAX_TERM) {
                            if ((t & 1) != 0) {
                                samA = 2 * a[0] - a[1];
                                samB = 2 * b[0] - b[1];
                            } else {
                                samA = (3 * a[0] - a[1]) >> 1;
                                samB = (3 * b[0] - b[1]) >> 1;
                            }
                        } else {
                            samA = a[m];
                            samB = b[m];
                        }
                        leftC += applyWeight(weightA[pass], samA);
                        rightC += applyWeight(weightB[pass], samB);
                    } else if (t == -1) {
                        leftC += applyWeight(weightA[pass], samplesA[pass][0]);
                        rightC += applyWeight(weightB[pass], leftC);
                    } else {
                        rightC += applyWeight(weightB[pass], samplesB[pass][0]);
                        if (t == -3) {
                            leftC += applyWeight(weightA[pass], samplesA[pass][0]);
                        } else {
                            leftC += applyWeight(weightA[pass], rightC);
                        }
                    }
                }
                if (joint) {
                    rightC -= leftC >> 1;
                    leftC += rightC;
                }
            }
            for (var pass = 0; pass < numTerms; pass++) {
                var t = term[pass];
                var a = samplesA[pass];
                var b = samplesB[pass];
                if (t > 0) {
                    int samA;
                    int samB;
                    int k;
                    if (t > MAX_TERM) {
                        if ((t & 1) != 0) {
                            samA = 2 * a[0] - a[1];
                            samB = 2 * b[0] - b[1];
                        } else {
                            samA = (3 * a[0] - a[1]) >> 1;
                            samB = (3 * b[0] - b[1]) >> 1;
                        }
                        a[1] = a[0];
                        b[1] = b[0];
                        k = 0;
                    } else {
                        samA = a[m];
                        samB = b[m];
                        k = (m + t) & (MAX_TERM - 1);
                    }
                    var left2 = applyWeight(weightA[pass], samA) + left;
                    var right2 = applyWeight(weightB[pass], samB) + right;
                    weightA[pass] = updateWeight(weightA[pass], delta[pass], samA, left);
                    weightB[pass] = updateWeight(weightB[pass], delta[pass], samB, right);
                    a[k] = left = left2;
                    b[k] = right = right2;
                } else if (t == -1) {
                    var left2 = left + applyWeight(weightA[pass], a[0]);
                    weightA[pass] = updateWeightClip(weightA[pass], delta[pass], a[0], left);
                    left = left2;
                    var right2 = right + applyWeight(weightB[pass], left2);
                    weightB[pass] = updateWeightClip(weightB[pass], delta[pass], left2, right);
                    a[0] = right = right2;
                } else {
                    var right2 = right + applyWeight(weightB[pass], b[0]);
                    weightB[pass] = updateWeightClip(weightB[pass], delta[pass], b[0], right);
                    right = right2;
                    if (t == -3) {
                        right2 = a[0];
                        a[0] = right;
                    }
                    var left2 = left + applyWeight(weightA[pass], right2);
                    weightA[pass] = updateWeightClip(weightA[pass], delta[pass], right2, left);
                    b[0] = left = left2;
                }
            }
            m = (m + 1) & (MAX_TERM - 1);
            if (!cross) {
                leftC = left + correctionLeft;
                rightC = right + correctionRight;
                if (joint) {
                    rightC -= leftC >> 1;
                    leftC += rightC;
                }
            }
            if (joint) {
                right -= left >> 1;
                left += right;
            }
            if ((flags & HYBRID_SHAPE) != 0) {
                left = shape(0, leftC, leftC - left);
                right = shape(1, rightC, rightC - right);
            } else {
                left = leftC;
                right = rightC;
            }
            if (Math.abs((long) left) > muteLimit || Math.abs((long) right) > muteLimit) {
                break;
            }
            crc += (crc << 3) + (left << 1) + left + right;
            buffer[2 * i] = left;
            buffer[2 * i + 1] = right;
        }
        correctedM = m;
        return i;
    }

    /**
     * Applies the noise shaping of a hybrid lossless channel and returns its output sample.
     */
    private int shape(int channel, int corrected, int correctionValue) {
        var shapingWeight = (shapingAcc[channel] += shapingDelta[channel]) >> 16;
        var temp = -applyWeight(shapingWeight, shapingError[channel]);
        if ((flags & NEW_SHAPING) != 0 && shapingWeight < 0 && temp != 0) {
            if (temp == shapingError[channel]) {
                temp = temp < 0 ? temp + 1 : temp - 1;
            }
            shapingError[channel] = temp - correctionValue;
        } else {
            shapingError[channel] = -correctionValue;
        }
        return corrected - temp;
    }

    /**
     * Applies a decorrelation weight ({@code apply_weight}).
     */
    private static int applyWeight(int weight, int sample) {
        if (sample != (short) sample) {
            return ((((sample & 0xffff) * weight) >> 9) + (((sample & ~0xffff) >> 9) * weight) + 1) >> 1;
        }
        return (weight * sample + 512) >> 10;
    }

    /**
     * Returns an adapted decorrelation weight ({@code update_weight}).
     */
    private static int updateWeight(int weight, int delta, int source, int result) {
        if (source != 0 && result != 0) {
            var s = (source ^ result) >> 31;
            return (delta ^ s) + (weight - s);
        }
        return weight;
    }

    /**
     * Returns an adapted decorrelation weight clipped to 1024 in magnitude ({@code update_weight_clip}).
     */
    private static int updateWeightClip(int weight, int delta, int source, int result) {
        if (source != 0 && result != 0) {
            var s = (source ^ result) >> 31;
            weight = (weight ^ s) + (delta - s);
            if (weight > 1024) {
                weight = 1024;
            }
            return (weight ^ s) - s;
        }
        return weight;
    }

    /**
     * Runs a mono decorrelation pass and leaves its history normalized ({@code decorr_mono_pass}).
     */
    private void decorrMonoPass(int pass, int[] buffer, int sampleCount) {
        var d = delta[pass];
        var weight = weightA[pass];
        var a = samplesA[pass];
        switch (term[pass]) {
            case 17 -> {
                for (var i = 0; i < sampleCount; i++) {
                    var sam = 2 * a[0] - a[1];
                    a[1] = a[0];
                    a[0] = applyWeight(weight, sam) + buffer[i];
                    weight = updateWeight(weight, d, sam, buffer[i]);
                    buffer[i] = a[0];
                }
            }
            case 18 -> {
                for (var i = 0; i < sampleCount; i++) {
                    var sam = (3 * a[0] - a[1]) >> 1;
                    a[1] = a[0];
                    a[0] = applyWeight(weight, sam) + buffer[i];
                    weight = updateWeight(weight, d, sam, buffer[i]);
                    buffer[i] = a[0];
                }
            }
            default -> {
                var m = 0;
                var k = term[pass] & (MAX_TERM - 1);
                for (var i = 0; i < sampleCount; i++) {
                    var sam = a[m];
                    a[k] = applyWeight(weight, sam) + buffer[i];
                    weight = updateWeight(weight, d, sam, buffer[i]);
                    buffer[i] = a[k];
                    m = (m + 1) & (MAX_TERM - 1);
                    k = (k + 1) & (MAX_TERM - 1);
                }
                if (m != 0) {
                    rotate(a, m);
                }
            }
        }
        weightA[pass] = weight;
    }

    /**
     * Runs a stereo decorrelation pass; the history of terms 1 to 8 is left unrotated ({@code decorr_stereo_pass}).
     */
    private void decorrStereoPass(int pass, int[] buffer, int sampleCount) {
        var end = sampleCount * 2;
        var a = samplesA[pass];
        var b = samplesB[pass];
        var d = delta[pass];
        var wa = weightA[pass];
        var wb = weightB[pass];
        switch (term[pass]) {
            case 17 -> {
                for (var i = 0; i < end; i += 2) {
                    var sam = 2 * a[0] - a[1];
                    a[1] = a[0];
                    var tmp = buffer[i];
                    buffer[i] = a[0] = applyWeight(wa, sam) + tmp;
                    wa = updateWeight(wa, d, sam, tmp);
                    sam = 2 * b[0] - b[1];
                    b[1] = b[0];
                    tmp = buffer[i + 1];
                    buffer[i + 1] = b[0] = applyWeight(wb, sam) + tmp;
                    wb = updateWeight(wb, d, sam, tmp);
                }
            }
            case 18 -> {
                for (var i = 0; i < end; i += 2) {
                    var sam = a[0] + ((a[0] - a[1]) >> 1);
                    a[1] = a[0];
                    var tmp = buffer[i];
                    buffer[i] = a[0] = applyWeight(wa, sam) + tmp;
                    wa = updateWeight(wa, d, sam, tmp);
                    sam = b[0] + ((b[0] - b[1]) >> 1);
                    b[1] = b[0];
                    tmp = buffer[i + 1];
                    buffer[i + 1] = b[0] = applyWeight(wb, sam) + tmp;
                    wb = updateWeight(wb, d, sam, tmp);
                }
            }
            case -1 -> {
                for (var i = 0; i < end; i += 2) {
                    var sam = buffer[i] + applyWeight(wa, a[0]);
                    wa = updateWeightClip(wa, d, a[0], buffer[i]);
                    buffer[i] = sam;
                    a[0] = buffer[i + 1] + applyWeight(wb, sam);
                    wb = updateWeightClip(wb, d, sam, buffer[i + 1]);
                    buffer[i + 1] = a[0];
                }
            }
            case -2 -> {
                for (var i = 0; i < end; i += 2) {
                    var sam = buffer[i + 1] + applyWeight(wb, b[0]);
                    wb = updateWeightClip(wb, d, b[0], buffer[i + 1]);
                    buffer[i + 1] = sam;
                    b[0] = buffer[i] + applyWeight(wa, sam);
                    wa = updateWeightClip(wa, d, sam, buffer[i]);
                    buffer[i] = b[0];
                }
            }
            case -3 -> {
                for (var i = 0; i < end; i += 2) {
                    var samA = buffer[i] + applyWeight(wa, a[0]);
                    wa = updateWeightClip(wa, d, a[0], buffer[i]);
                    var samB = buffer[i + 1] + applyWeight(wb, b[0]);
                    wb = updateWeightClip(wb, d, b[0], buffer[i + 1]);
                    buffer[i] = b[0] = samA;
                    buffer[i + 1] = a[0] = samB;
                }
            }
            default -> {
                var m = 0;
                var k = term[pass] & (MAX_TERM - 1);
                for (var i = 0; i < end; i += 2) {
                    var sam = a[m];
                    a[k] = applyWeight(wa, sam) + buffer[i];
                    wa = updateWeight(wa, d, sam, buffer[i]);
                    buffer[i] = a[k];
                    sam = b[m];
                    b[k] = applyWeight(wb, sam) + buffer[i + 1];
                    wb = updateWeight(wb, d, sam, buffer[i + 1]);
                    buffer[i + 1] = b[k];
                    m = (m + 1) & (MAX_TERM - 1);
                    k = (k + 1) & (MAX_TERM - 1);
                }
            }
        }
        weightA[pass] = wa;
        weightB[pass] = wb;
    }

    /**
     * Reads the ones count of a word ({@code get_word}), or {@link #WORD_EOF}.
     */
    private int onesCount() {
        var ones = 0;
        while (ones < LIMIT_ONES + 1 && wv.bit() != 0) {
            ones++;
        }
        if (ones >= LIMIT_ONES) {
            if (ones == LIMIT_ONES + 1) {
                return WORD_EOF;
            }
            var escape = escapeCount();
            if (escape < 0) {
                return WORD_EOF;
            }
            ones = escape + LIMIT_ONES;
        }
        return ones;
    }

    /**
     * Reads an Elias gamma style count: the run of zeros or the escaped ones count; -1 when invalid.
     */
    private int escapeCount() {
        var bits = 0;
        while (bits < 33 && wv.bit() != 0) {
            bits++;
        }
        if (bits == 33) {
            return -1;
        }
        if (bits < 2) {
            return bits;
        }
        var value = 0;
        var mask = 1;
        while (--bits > 0) {
            if (wv.bit() != 0) {
                value |= mask;
            }
            mask <<= 1;
        }
        return value | mask;
    }

    private static int med(int value) {
        return (value >>> 4) + 1;
    }

    private static int incMed(int value, int div) {
        return value + Integer.divideUnsigned(value + div, div) * 5;
    }

    private static int decMed(int value, int div) {
        return value - Integer.divideUnsigned(value + (div - 2), div) * 2;
    }

    /**
     * Reads one word of the hybrid or lossless bitstream ({@code get_word}), storing its correction in
     * {@link #corrections} when {@code withCorrection}.
     */
    private int getWord(int channel, boolean withCorrection) {
        if (!wv.isOpen()) {
            return WORD_EOF;
        }
        if (withCorrection) {
            corrections[channel] = 0;
        }
        var c = median[channel];
        if ((median[0][0] & ~1) == 0 && holdingZero == 0 && holdingOne == 0 && (median[1][0] & ~1) == 0) {
            if (zerosAcc != 0) {
                if (--zerosAcc != 0) {
                    slowLevel[channel] -= (slowLevel[channel] + SLO) >>> SLS;
                    return 0;
                }
            } else {
                var run = escapeCount();
                if (run < 0) {
                    return WORD_EOF;
                }
                zerosAcc = run;
                if (zerosAcc != 0) {
                    slowLevel[channel] -= (slowLevel[channel] + SLO) >>> SLS;
                    Arrays.fill(median[0], 0);
                    Arrays.fill(median[1], 0);
                    return 0;
                }
            }
        }
        int ones;
        if (holdingZero != 0) {
            ones = holdingZero = 0;
        } else {
            ones = onesCount();
            if (ones == WORD_EOF) {
                return WORD_EOF;
            }
            if (holdingOne != 0) {
                holdingOne = ones & 1;
                ones = (ones >>> 1) + 1;
            } else {
                holdingOne = ones & 1;
                ones >>>= 1;
            }
            holdingZero = ~holdingOne & 1;
        }
        if ((flags & HYBRID_FLAG) != 0 && channel == 0) {
            updateErrorLimit();
        }
        int low;
        int high;
        if (ones == 0) {
            low = 0;
            high = med(c[0]) - 1;
            c[0] = decMed(c[0], 128);
        } else {
            low = med(c[0]);
            c[0] = incMed(c[0], 128);
            if (ones == 1) {
                high = low + med(c[1]) - 1;
                c[1] = decMed(c[1], 64);
            } else {
                low += med(c[1]);
                c[1] = incMed(c[1], 64);
                if (ones == 2) {
                    high = low + med(c[2]) - 1;
                    c[2] = decMed(c[2], 32);
                } else {
                    low += (ones - 2) * med(c[2]);
                    high = low + med(c[2]) - 1;
                    c[2] = incMed(c[2], 32);
                }
            }
        }
        low &= 0x7fffffff;
        high &= 0x7fffffff;
        if (low > high) {
            high = low;
        }
        var mid = (high + low + 1) >>> 1;
        if (errorLimit[channel] == 0) {
            mid = wv.code(high - low) + low;
        } else {
            while (Integer.compareUnsigned(high - low, errorLimit[channel]) > 0) {
                if (wv.bit() != 0) {
                    low = mid;
                    mid = (high + low + 1) >>> 1;
                } else {
                    high = mid - 1;
                    mid = (high + low + 1) >>> 1;
                }
            }
        }
        var sign = wv.bit();
        if (wvc.isOpen() && errorLimit[channel] != 0) {
            var value = codeOf(wvc, high - low) + low;
            if (withCorrection) {
                corrections[channel] = sign != 0 ? mid - value : value - mid;
            }
        }
        if ((flags & HYBRID_BITRATE) != 0) {
            slowLevel[channel] -= (slowLevel[channel] + SLO) >>> SLS;
            slowLevel[channel] += WavPackTables.log2(mid);
        }
        return sign != 0 ? ~mid : mid;
    }

    private static int codeOf(WavPackBitReader reader, int maxCode) {
        return reader.code(maxCode);
    }

    /**
     * Reads the words of a lossless block ({@code get_words_lossless}).
     *
     * @return the number of samples per channel read
     */
    private int getWordsLossless(int[] buffer, int count) {
        var mono = (flags & MONO_DATA) != 0;
        var total = mono ? count : count * 2;
        if (count != 0 && !wv.isOpen()) {
            Arrays.fill(buffer, 0, total, 0);
            return count;
        }
        int samples;
        var c = median[0];
        for (samples = 0; samples < total; samples++) {
            if (!mono) {
                c = median[samples & 1];
            }
            if (holdingZero != 0) {
                holdingZero = 0;
                var low = wv.code(med(c[0]) - 1);
                c[0] = decMed(c[0], 128);
                buffer[samples] = wv.bit() != 0 ? ~low : low;
                if (++samples == total) {
                    break;
                }
                if (!mono) {
                    c = median[samples & 1];
                }
            }
            if (Integer.compareUnsigned(median[0][0], 2) < 0 && holdingOne == 0
                    && Integer.compareUnsigned(median[1][0], 2) < 0) {
                if (zerosAcc != 0) {
                    if (--zerosAcc != 0) {
                        buffer[samples] = 0;
                        continue;
                    }
                } else {
                    var run = escapeCount();
                    if (run < 0) {
                        break;
                    }
                    zerosAcc = run;
                    if (zerosAcc != 0) {
                        Arrays.fill(median[0], 0);
                        Arrays.fill(median[1], 0);
                        buffer[samples] = 0;
                        continue;
                    }
                }
            }
            var ones = onesCount();
            if (ones == WORD_EOF) {
                break;
            }
            var held = holdingOne;
            holdingOne = ones & 1;
            holdingZero = ~ones & 1;
            ones = (ones >>> 1) + held;
            int low;
            int high;
            if (ones == 0) {
                low = 0;
                high = med(c[0]) - 1;
                c[0] = decMed(c[0], 128);
            } else {
                low = med(c[0]);
                c[0] = incMed(c[0], 128);
                if (ones == 1) {
                    high = low + med(c[1]) - 1;
                    c[1] = decMed(c[1], 64);
                } else {
                    low += med(c[1]);
                    c[1] = incMed(c[1], 64);
                    if (ones == 2) {
                        high = low + med(c[2]) - 1;
                        c[2] = decMed(c[2], 32);
                    } else {
                        low += (ones - 2) * med(c[2]);
                        high = low + med(c[2]) - 1;
                        c[2] = incMed(c[2], 32);
                    }
                }
            }
            low += wv.code(high - low);
            buffer[samples] = wv.bit() != 0 ? ~low : low;
        }
        return mono ? samples : samples / 2;
    }

    /**
     * Updates the error limits of the hybrid mode from the bitrate accumulators ({@code update_error_limit}).
     */
    private void updateErrorLimit() {
        var bitrate0 = (bitrateAcc[0] += bitrateDelta[0]) >>> 16;
        if ((flags & MONO_DATA) != 0) {
            if ((flags & HYBRID_BITRATE) != 0) {
                var slowLog0 = (slowLevel[0] + SLO) >>> SLS;
                errorLimit[0] = slowLog0 - bitrate0 > -0x100 ? WavPackTables.exp2s(slowLog0 - bitrate0 + 0x100) : 0;
            } else {
                errorLimit[0] = WavPackTables.exp2s(bitrate0);
            }
            return;
        }
        var bitrate1 = (bitrateAcc[1] += bitrateDelta[1]) >>> 16;
        if ((flags & HYBRID_BITRATE) != 0) {
            var slowLog0 = (slowLevel[0] + SLO) >>> SLS;
            var slowLog1 = (slowLevel[1] + SLO) >>> SLS;
            if ((flags & HYBRID_BALANCE) != 0) {
                var balance = (slowLog1 - slowLog0 + bitrate1 + 1) >> 1;
                if (balance > bitrate0) {
                    bitrate1 = bitrate0 * 2;
                    bitrate0 = 0;
                } else if (-balance > bitrate0) {
                    bitrate0 = bitrate0 * 2;
                    bitrate1 = 0;
                } else {
                    bitrate1 = bitrate0 + balance;
                    bitrate0 = bitrate0 - balance;
                }
            }
            errorLimit[0] = slowLog0 - bitrate0 > -0x100 ? WavPackTables.exp2s(slowLog0 - bitrate0 + 0x100) : 0;
            errorLimit[1] = slowLog1 - bitrate1 > -0x100 ? WavPackTables.exp2s(slowLog1 - bitrate1 + 0x100) : 0;
        } else {
            errorLimit[0] = WavPackTables.exp2s(bitrate0);
            errorLimit[1] = WavPackTables.exp2s(bitrate1);
        }
    }

    /**
     * Restores the final samples: the floating-point values, the extended integer bits and the shift and
     * clipping of the block ({@code fixup_samples}).
     */
    private void fixup(int[] buffer, int count) {
        var lossy = (flags & HYBRID_FLAG) != 0 && !correction;
        var shift = (flags & SHIFT_MASK) >>> SHIFT_LSB;
        if ((flags & FLOAT_DATA) != 0) {
            floatValues(buffer, count);
            return;
        }
        if ((flags & INT32_DATA) != 0) {
            var sentBits = int32SentBits & 0x1f;
            var zeros = int32Zeros & 0x1f;
            var ones = int32Ones & 0x1f;
            var dups = int32Dups & 0x1f;
            var mask = (int) ((1L << sentBits) - 1);
            if (wvx.isOpen()) {
                var maxWidth = int32MaxWidth;
                var crc = crcX;
                for (var i = 0; i < count; i++) {
                    var value = buffer[i];
                    if (sentBits != 0) {
                        if (maxWidth != 0) {
                            var positive = value < 0 ? ~value : value;
                            var width = WavPackTables.countBits(positive) + sentBits;
                            var bitsToRead = sentBits;
                            if (width <= maxWidth || (bitsToRead -= width - maxWidth) > 0) {
                                var data = wvx.bits(bitsToRead) & (int) ((1L << bitsToRead) - 1);
                                value = ((value << bitsToRead) | data) << (sentBits - bitsToRead);
                            } else {
                                value <<= sentBits;
                            }
                        } else {
                            var data = wvx.bits(sentBits);
                            value = (value << sentBits) | (data & mask);
                        }
                    }
                    if (zeros != 0) {
                        value <<= zeros;
                    } else if (ones != 0) {
                        value = ((value + 1) << ones) - 1;
                    } else if (dups != 0) {
                        value = ((value + (value & 1)) << dups) - (value & 1);
                    }
                    crc = crc * 9 + (value & 0xffff) * 3 + ((value >> 16) & 0xffff);
                    buffer[i] = value;
                }
                crcX = crc;
            } else if (sentBits == 0 && zeros + ones + dups != 0) {
                while (lossy && (flags & BYTES_STORED) == 3 && shift < 8) {
                    if (zeros != 0) {
                        zeros--;
                    } else if (ones != 0) {
                        ones--;
                    } else if (dups != 0) {
                        dups--;
                    } else {
                        break;
                    }
                    shift++;
                }
                for (var i = 0; i < count; i++) {
                    var value = buffer[i];
                    if (zeros != 0) {
                        value <<= zeros;
                    } else if (ones != 0) {
                        value = ((value + 1) << ones) - 1;
                    } else if (dups != 0) {
                        value = ((value + (value & 1)) << dups) - (value & 1);
                    }
                    buffer[i] = value;
                }
            } else {
                shift += zeros + sentBits + ones + dups;
            }
        }
        shift &= 0x1f;
        if (lossy) {
            int minValue;
            int maxValue;
            switch (flags & BYTES_STORED) {
                case 0 -> {
                    minValue = -128 >> shift;
                    maxValue = 127 >> shift;
                }
                case 1 -> {
                    minValue = -32768 >> shift;
                    maxValue = 32767 >> shift;
                }
                case 2 -> {
                    minValue = -8388608 >> shift;
                    maxValue = 8388607 >> shift;
                }
                default -> {
                    minValue = Integer.MIN_VALUE >> shift;
                    maxValue = Integer.MAX_VALUE >> shift;
                }
            }
            var minShifted = minValue << shift;
            var maxShifted = maxValue << shift;
            for (var i = 0; i < count; i++) {
                var value = buffer[i];
                buffer[i] = value < minValue ? minShifted : value > maxValue ? maxShifted : value << shift;
            }
        } else if (shift != 0) {
            for (var i = 0; i < count; i++) {
                buffer[i] <<= shift;
            }
        }
    }

    /**
     * Restores 32-bit floating-point values, as their bits ({@code float_values}).
     */
    private void floatValues(int[] values, int count) {
        if (!wvx.isOpen()) {
            floatValuesWithoutExtra(values, count);
            return;
        }
        var minShiftedZeros = floatMinShiftedZeros;
        var maxShiftedOnes = floatMaxShiftedOnes;
        var crc = crcX;
        for (var i = 0; i < count; i++) {
            var shiftCount = 0;
            var exp = floatMaxExp;
            var out = 0;
            var value = values[i];
            if (value == 0) {
                if ((floatFlags & FLOAT_ZEROS_SENT) != 0) {
                    if (wvx.bit() != 0) {
                        out = setMantissa(out, wvx.bits(23));
                        if (exp >= 25) {
                            out = setExponent(out, wvx.bits(8) & 0xff);
                        }
                        out = setSign(out, wvx.bit());
                    } else if ((floatFlags & FLOAT_NEG_ZEROS) != 0) {
                        out = setSign(out, wvx.bit());
                    }
                }
            } else {
                value <<= floatShift & 0x1f;
                if (value < 0) {
                    value = -value;
                    out = setSign(out, 1);
                }
                if (value == 0x1000000) {
                    if (wvx.bit() != 0) {
                        out = setMantissa(out, wvx.bits(23));
                    }
                    out = setExponent(out, 255);
                } else {
                    if (exp != 0) {
                        while ((value & 0x800000) == 0 && --exp != 0) {
                            shiftCount++;
                            value <<= 1;
                        }
                    }
                    shiftCount &= 0x1f;
                    if (shiftCount != 0) {
                        if ((floatFlags & FLOAT_SHIFT_ONES) != 0
                                || ((floatFlags & FLOAT_SHIFT_SAME) != 0 && wvx.bit() != 0)) {
                            value |= (int) ((1L << shiftCount) - 1);
                        } else if ((floatFlags & FLOAT_SHIFT_SENT) != 0) {
                            var mask = (int) ((1L << shiftCount) - 1);
                            var numZeros = 0;
                            if (maxShiftedOnes != 0 && shiftCount > maxShiftedOnes) {
                                numZeros = shiftCount - maxShiftedOnes;
                            }
                            if (minShiftedZeros > numZeros) {
                                numZeros = Math.min(minShiftedZeros, shiftCount);
                            }
                            if ((shiftCount -= numZeros) > 0) {
                                var temp = wvx.bits(shiftCount);
                                value |= (temp << numZeros) & mask;
                            }
                        }
                    }
                    out = setMantissa(out, value);
                    out = setExponent(out, exp);
                }
            }
            crc = crc * 27 + (out & 0x7fffff) * 9 + ((out >> 23) & 0xff) * 3 + ((out >>> 31) & 1);
            values[i] = out;
        }
        crcX = crc;
    }

    private void floatValuesWithoutExtra(int[] values, int count) {
        for (var i = 0; i < count; i++) {
            var shiftCount = 0;
            var exp = floatMaxExp;
            var out = 0;
            var value = values[i];
            if (value != 0) {
                value <<= floatShift & 0x1f;
                if (value < 0) {
                    value = -value;
                    out = setSign(out, 1);
                }
                if (value >= 0x1000000) {
                    while ((value & 0xf000000) != 0) {
                        value >>= 1;
                        ++exp;
                    }
                } else if (exp != 0) {
                    while ((value & 0x800000) == 0 && --exp != 0) {
                        shiftCount++;
                        value <<= 1;
                    }
                    shiftCount &= 0x1f;
                    if (shiftCount != 0 && (floatFlags & FLOAT_SHIFT_ONES) != 0) {
                        value |= (int) ((1L << shiftCount) - 1);
                    }
                }
                out = setMantissa(out, value);
                out = setExponent(out, exp);
            }
            values[i] = out;
        }
    }

    private static int setMantissa(int f, int value) {
        return f ^ ((f ^ value) & 0x7fffff);
    }

    private static int setExponent(int f, int value) {
        return f ^ ((f ^ (value << 23)) & 0x7f800000);
    }

    private static int setSign(int f, int value) {
        return f ^ ((f ^ (value << 31)) & 0x80000000);
    }

    /**
     * Checks the structure of a complete block and, when it carries one, its block checksum
     * ({@code WavpackVerifySingleBlock}).
     *
     * @param data the array holding the block
     * @param offset the first byte of the block
     * @throws MediaException.Malformed if the block fails the check
     */
    static void verifyBlock(byte[] data, int offset) {
        var remaining = (i32(data, offset + 4) & 0xFFFFFFFFL) - 24;
        var position = offset + 32;
        var passed = 0;
        while (remaining >= 2) {
            var id = data[position++] & 0xFF;
            long length = (data[position++] & 0xFF) << 1;
            remaining -= 2;
            if ((id & ID_LARGE) != 0) {
                if (remaining < 2) {
                    throw new MediaException.Malformed("WavPack metadata header truncated");
                }
                length += (long) (data[position++] & 0xFF) << 9;
                length += (long) (data[position++] & 0xFF) << 17;
                remaining -= 2;
            }
            if (remaining < length) {
                throw new MediaException.Malformed("WavPack metadata runs past its block");
            }
            if ((id & ID_UNIQUE) == ID_BLOCK_CHECKSUM) {
                if ((id & ID_ODD_SIZE) != 0 || length < 2 || length > 4) {
                    throw new MediaException.Malformed("invalid WavPack block checksum");
                }
                var words = (position - 2 - offset) >> 1;
                var sum = 0xffffffff;
                for (var i = 0; i < words; i++) {
                    sum = sum * 3 + u16(data, offset + 2 * i);
                }
                if (length == 4) {
                    if (i32(data, position) != sum) {
                        throw new MediaException.Malformed("WavPack block fails its block checksum");
                    }
                } else {
                    sum ^= sum >>> 16;
                    if (u16(data, position) != (sum & 0xffff)) {
                        throw new MediaException.Malformed("WavPack block fails its block checksum");
                    }
                }
                passed++;
            }
            remaining -= length;
            position += (int) length;
        }
        if (remaining != 0) {
            throw new MediaException.Malformed("WavPack metadata does not fill its block");
        }
        if ((i32(data, offset + 24) & HAS_CHECKSUM) != 0 && passed == 0) {
            throw new MediaException.Malformed("WavPack block without its block checksum");
        }
    }

    static int u16(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
    }

    static int i32(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16 | data[at + 3] << 24;
    }
}
