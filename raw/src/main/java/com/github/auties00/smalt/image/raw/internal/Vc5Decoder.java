/* Ports gpr 1.0.0 (source/lib/vc5_decoder: decoder.c, inverse.c, wavelet.c, dequantize.c, vlc.c, raw.c;
   source/lib/vc5_common: bitstream.c, companding.c, logcurve.c, codec.c, table17.inc). Copyright 2018 GoPro, Inc.;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Decodes a VC-5 bitstream of a Bayer image (SMPTE ST 2073, the coding of DNG compression 9 that GoPro's GPR
 * files use) into its photosites.
 *
 * <p>The bitstream is a sequence of 32-bit segments, a 16-bit tag and a 16-bit value each, most significant
 * byte first, after the start marker {@code VC-5}. The header gives the image size, four channels and the
 * prescale shifts of the three wavelet levels. Each channel holds one component of the Bayer quads (the green
 * sum, the red and blue differences from it and the green difference), coded as a three-level spatial wavelet:
 * the lowpass band of the top level as fixed-width coefficients, then each level's three highpass bands as
 * run-length and magnitude codes of the codebook, dequantized through the companding curve and the band's
 * quantization. The inverse transform rebuilds each component array, the inverse component transform the
 * 12-bit photosites, and the inverse log curve expands them to 16 bits before they are reduced to the output
 * depth. Optional chunks, metadata sections and the unique image identifier are skipped.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class Vc5Decoder {
    /**
     * The start marker, {@code "VC-5"}.
     */
    private static final int START_MARKER = 0x56432D35;

    /**
     * The number of wavelet levels of a channel.
     */
    private static final int WAVELETS = 3;

    /**
     * The subbands of a channel: the lowpass band and three highpass bands per level.
     */
    private static final int SUBBANDS = 10;

    /**
     * The most channels a bitstream may declare.
     */
    private static final int MAX_CHANNELS = 4;

    /**
     * The image format of a Bayer image (VC-5 Part 3).
     */
    private static final int IMAGE_FORMAT_RAW = 4;

    /**
     * The wavelet of each subband.
     */
    private static final int[] SUBBAND_WAVELET = {2, 2, 2, 2, 1, 1, 1, 0, 0, 0};

    /**
     * The band of each subband in its wavelet.
     */
    private static final int[] SUBBAND_BAND = {0, 1, 2, 3, 1, 2, 3, 1, 2, 3};

    /**
     * The codebook: per entry the code length, the code, the run length (zero for the band end marker) and the
     * magnitude, in order of increasing code length.
     */
    static final int[] CODEBOOK = {
            1, 0x0, 1, 0, 2, 0x2, 1, 1, 3, 0x7, 1, 2, 5, 0x19, 1, 3, 6, 0x30, 1, 4, 6, 0x36, 1, 5, 7, 0x6F, 1, 8,
            7, 0x63, 1, 6, 7, 0x69, 12, 0, 7, 0x6B, 1, 7, 8, 0xD1, 20, 0, 8, 0xD4, 1, 9, 8, 0xDC, 1, 10,
            9, 0x189, 1, 11, 9, 0x18A, 32, 0, 9, 0x1A0, 1, 12, 9, 0x1AB, 1, 13, 10, 0x377, 1, 18, 10, 0x310, 1, 14,
            10, 0x316, 1, 15, 10, 0x343, 60, 0, 10, 0x354, 1, 16, 10, 0x375, 1, 17, 11, 0x623, 1, 19,
            11, 0x684, 1, 20, 11, 0x685, 100, 0, 11, 0x6AB, 1, 21, 11, 0x6EC, 1, 22, 12, 0xDDB, 1, 29,
            12, 0xC5C, 1, 24, 12, 0xC5E, 1, 25, 12, 0xC44, 1, 23, 12, 0xD55, 1, 26, 12, 0xDD1, 1, 27,
            12, 0xDD3, 1, 28, 13, 0x1BB5, 1, 35, 13, 0x188B, 1, 30, 13, 0x18BB, 1, 31, 13, 0x18BF, 180, 0,
            13, 0x1AA8, 1, 32, 13, 0x1BA0, 1, 33, 13, 0x1BA5, 320, 0, 13, 0x1BA4, 1, 34, 14, 0x3115, 1, 36,
            14, 0x3175, 1, 37, 14, 0x317D, 1, 38, 14, 0x3553, 1, 39, 14, 0x3768, 1, 40, 15, 0x6E87, 1, 46,
            15, 0x6ED3, 1, 47, 15, 0x62E8, 1, 42, 15, 0x62F8, 1, 43, 15, 0x6228, 1, 41, 15, 0x6AA4, 1, 44,
            15, 0x6E85, 1, 45, 16, 0xC453, 1, 48, 16, 0xC5D3, 1, 49, 16, 0xC5F3, 1, 50, 16, 0xDDA4, 1, 53,
            16, 0xDD08, 1, 51, 16, 0xDD0C, 1, 52, 17, 0x1BB4B, 1, 61, 17, 0x1BB4A, 1, 60, 17, 0x18BA5, 1, 55,
            17, 0x18BE5, 1, 56, 17, 0x1AA95, 1, 57, 17, 0x1AA97, 1, 58, 17, 0x188A4, 1, 54, 17, 0x1BA13, 1, 59,
            18, 0x31748, 1, 62, 18, 0x317C8, 1, 63, 18, 0x35528, 1, 64, 18, 0x3552C, 1, 65, 18, 0x37424, 1, 66,
            18, 0x37434, 1, 67, 18, 0x37436, 1, 68, 19, 0x62294, 1, 69, 19, 0x62E92, 1, 70, 19, 0x62F92, 1, 71,
            19, 0x6AA52, 1, 72, 19, 0x6AA5A, 1, 73, 19, 0x6E86A, 1, 75, 19, 0x6E86E, 1, 76, 19, 0x6E84A, 1, 74,
            20, 0xC452A, 1, 77, 20, 0xC5D27, 1, 78, 20, 0xC5F26, 1, 79, 20, 0xD54A6, 1, 80, 20, 0xD54B6, 1, 81,
            20, 0xDD096, 1, 82, 20, 0xDD0D6, 1, 83, 20, 0xDD0DE, 1, 84, 21, 0x188A56, 1, 85, 21, 0x18BA4D, 1, 86,
            21, 0x18BE4E, 1, 87, 21, 0x18BE4F, 1, 88, 21, 0x1AA96E, 1, 89, 21, 0x1BA12E, 1, 90, 21, 0x1BA12F, 1, 91,
            21, 0x1BA1AF, 1, 92, 21, 0x1BA1BF, 1, 93, 22, 0x37435D, 1, 99, 22, 0x37437D, 1, 100, 22, 0x317498, 1, 94,
            22, 0x35529C, 1, 95, 22, 0x35529D, 1, 96, 22, 0x3552DE, 1, 97, 22, 0x3552DF, 1, 98, 23, 0x62E933, 1, 102,
            23, 0x62295D, 1, 101, 23, 0x6AA53D, 1, 103, 23, 0x6AA53F, 1, 105, 23, 0x6AA53E, 1, 104,
            23, 0x6E86B9, 1, 106, 23, 0x6E86F8, 1, 107, 24, 0xD54A79, 1, 111, 24, 0xC5D265, 1, 109,
            24, 0xC452B8, 1, 108, 24, 0xDD0D71, 1, 113, 24, 0xD54A78, 1, 110, 24, 0xDD0D70, 1, 112,
            24, 0xDD0DF2, 1, 114, 24, 0xDD0DF3, 1, 115, 25, 0x188A5F6, 1, 225, 25, 0x188A5F5, 1, 189,
            25, 0x188A5F4, 1, 188, 25, 0x188A5F3, 1, 203, 25, 0x188A5F2, 1, 202, 25, 0x188A5F1, 1, 197,
            25, 0x188A5F0, 1, 207, 25, 0x188A5EF, 1, 169, 25, 0x188A5EE, 1, 223, 25, 0x188A5ED, 1, 159,
            25, 0x188A5AA, 1, 235, 25, 0x188A5E3, 1, 152, 25, 0x188A5DF, 1, 192, 25, 0x188A589, 1, 179,
            25, 0x188A5DD, 1, 201, 25, 0x188A578, 1, 172, 25, 0x188A5E0, 1, 149, 25, 0x188A588, 1, 178,
            25, 0x188A5D6, 1, 120, 25, 0x188A5DB, 1, 219, 25, 0x188A5E1, 1, 150, 25, 0x188A587, 1, 127,
            25, 0x188A59A, 1, 211, 25, 0x188A5C4, 1, 125, 25, 0x188A5EC, 1, 158, 25, 0x188A586, 1, 247,
            25, 0x188A573, 1, 238, 25, 0x188A59C, 1, 163, 25, 0x188A5C8, 1, 228, 25, 0x188A5FB, 1, 183,
            25, 0x188A5A1, 1, 217, 25, 0x188A5EB, 1, 168, 25, 0x188A5A8, 1, 122, 25, 0x188A584, 1, 128,
            25, 0x188A5D2, 1, 249, 25, 0x188A599, 1, 187, 25, 0x188A598, 1, 186, 25, 0x188A583, 1, 136,
            25, 0x18BA4C9, 1, 181, 25, 0x188A5D0, 1, 255, 25, 0x188A594, 1, 230, 25, 0x188A582, 1, 135,
            25, 0x188A5CB, 1, 233, 25, 0x188A5D8, 1, 222, 25, 0x188A5E7, 1, 145, 25, 0x188A581, 1, 134,
            25, 0x188A5EA, 1, 167, 25, 0x188A5A9, 1, 248, 25, 0x188A5A6, 1, 209, 25, 0x188A580, 1, 243,
            25, 0x188A5A0, 1, 216, 25, 0x188A59D, 1, 164, 25, 0x188A5C3, 1, 140, 25, 0x188A57F, 1, 157,
            25, 0x188A5C0, 1, 239, 25, 0x188A5DE, 1, 191, 25, 0x188A5D4, 1, 251, 25, 0x188A57E, 1, 156,
            25, 0x188A5C2, 1, 139, 25, 0x188A592, 1, 242, 25, 0x188A5CD, 1, 133, 25, 0x188A57D, 1, 162,
            25, 0x188A5A3, 1, 213, 25, 0x188A5E8, 1, 165, 25, 0x188A5A2, 1, 212, 25, 0x188A57C, 1, 227,
            25, 0x188A58E, 1, 198, 25, 0x188A5B3, 1, 236, 25, 0x188A5B2, 1, 234, 25, 0x188A5B1, 1, 117,
            25, 0x188A5B0, 1, 215, 25, 0x188A5AF, 1, 124, 25, 0x188A5AE, 1, 123, 25, 0x188A5AD, 1, 254,
            25, 0x188A5AC, 1, 253, 25, 0x188A5AB, 1, 148, 25, 0x188A5DA, 1, 218, 25, 0x188A5E4, 1, 146,
            25, 0x188A5E5, 1, 147, 25, 0x188A5D9, 1, 224, 25, 0x188A5B5, 1, 143, 25, 0x188A5BC, 1, 184,
            25, 0x188A5BD, 1, 185, 25, 0x188A5E9, 1, 166, 25, 0x188A5CC, 1, 132, 25, 0x188A585, 1, 129,
            25, 0x188A5D3, 1, 250, 25, 0x188A5E2, 1, 151, 25, 0x188A595, 1, 119, 25, 0x188A596, 1, 193,
            25, 0x188A5B8, 1, 176, 25, 0x188A590, 1, 245, 25, 0x188A5C9, 1, 229, 25, 0x188A5A4, 1, 206,
            25, 0x188A5E6, 1, 144, 25, 0x188A5A5, 1, 208, 25, 0x188A5CE, 1, 137, 25, 0x188A5BF, 1, 241,
            25, 0x188A572, 1, 237, 25, 0x188A59B, 1, 190, 25, 0x188A5BE, 1, 240, 25, 0x188A5C7, 1, 131,
            25, 0x188A5CA, 1, 232, 25, 0x188A5D5, 1, 252, 25, 0x188A57B, 1, 171, 25, 0x188A58D, 1, 205,
            25, 0x188A58C, 1, 204, 25, 0x188A58B, 1, 118, 25, 0x188A58A, 1, 214, 25, 0x18BA4C8, 1, 180,
            25, 0x188A5C5, 1, 126, 25, 0x188A5FA, 1, 182, 25, 0x188A5BB, 1, 175, 25, 0x188A5C1, 1, 141,
            25, 0x188A5CF, 1, 138, 25, 0x188A5B9, 1, 177, 25, 0x188A5B6, 1, 153, 25, 0x188A597, 1, 194,
            25, 0x188A5FE, 1, 160, 25, 0x188A5D7, 1, 121, 25, 0x188A5BA, 1, 174, 25, 0x188A591, 1, 246,
            25, 0x188A5C6, 1, 130, 25, 0x188A5DC, 1, 200, 25, 0x188A57A, 1, 170, 25, 0x188A59F, 1, 221,
            25, 0x188A5F9, 1, 196, 25, 0x188A5B4, 1, 142, 25, 0x188A5A7, 1, 210, 25, 0x188A58F, 1, 199,
            25, 0x188A5FD, 1, 155, 25, 0x188A5B7, 1, 154, 25, 0x188A593, 1, 244, 25, 0x188A59E, 1, 220,
            25, 0x188A5F8, 1, 195, 25, 0x188A5FF, 1, 161, 25, 0x188A5FC, 1, 231, 25, 0x188A579, 1, 173,
            25, 0x188A5F7, 1, 226, 26, 0x3114BA2, 1, 116, 26, 0x3114BA3, 0, 1
    };

    /**
     * The bits of the fast codebook lookup.
     */
    private static final int FAST_BITS = 12;

    /**
     * The entry of each code of up to {@link #FAST_BITS} bits, indexed by the next {@link #FAST_BITS} bits, plus
     * one; zero when the code is longer.
     */
    private static final short[] FAST = new short[1 << FAST_BITS];

    /**
     * The first codebook entry longer than {@link #FAST_BITS} bits.
     */
    private static final int FIRST_LONG;

    /**
     * The inverse log curve, from 12-bit values to 16-bit linear ones.
     */
    private static final int[] LOG_CURVE = new int[1 << 12];

    static {
        var entries = CODEBOOK.length / 4;
        var firstLong = entries;
        for (var entry = 0; entry < entries; entry++) {
            var size = CODEBOOK[4 * entry];
            if (size > FAST_BITS) {
                firstLong = Math.min(firstLong, entry);
                continue;
            }
            var code = CODEBOOK[4 * entry + 1] << (FAST_BITS - size);
            for (var fill = 0; fill < 1 << (FAST_BITS - size); fill++) {
                FAST[code + fill] = (short) (entry + 1);
            }
        }
        FIRST_LONG = firstLong;
        for (var index = 0; index < LOG_CURVE.length; index++) {
            float input = index;
            var output = (float) (65535 * (StrictMath.pow(113.0, input / 4095.0) - 1.0) / 112.0);
            LOG_CURVE[index] = Math.min((int) output, 65535);
        }
    }

    /**
     * Prevents instantiation.
     */
    private Vc5Decoder() {
        throw new AssertionError();
    }

    /**
     * Reads the bits of a bitstream, most significant bit first; past its end it reads zeros.
     */
    private static final class Bits {
        private final byte[] data;
        private final long origin;
        private final long end;
        private long position;

        Bits(byte[] data, int offset, int length) {
            this.data = data;
            this.origin = (long) offset * 8;
            this.position = origin;
            this.end = (long) (offset + length) * 8;
        }

        int read(int count) {
            var value = peek(count);
            position += count;
            return value;
        }

        // the next count bits, from 0 to 31, without consuming them
        int peek(int count) {
            if (count == 0) {
                return 0;
            }
            var value = 0L;
            var at = position;
            var remaining = count;
            while (remaining > 0) {
                var index = (int) (at >>> 3);
                var bit = (int) (at & 7);
                var take = Math.min(8 - bit, remaining);
                var current = at < end ? data[index] & 0xFF : 0;
                value = (value << take) | ((current >>> (8 - bit - take)) & ((1 << take) - 1));
                at += take;
                remaining -= take;
            }
            return (int) value;
        }

        void skip(long count) {
            position += count;
        }

        // advances to the next multiple of 32 bits from the start of the bitstream
        void align() {
            position = origin + ((position - origin + 31) & ~31L);
        }

        boolean exhausted() {
            return position > end;
        }
    }

    /**
     * One level of a channel's wavelet: four bands of the same size.
     */
    private static final class Wavelet {
        final int width;
        final int height;
        final short[][] bands = new short[4][];
        final int[] quantization = new int[4];
        int valid;

        Wavelet(int width, int height, MediaLimits limits) {
            this.width = width;
            this.height = height;
            LimitChecks.checkAllocation(limits, 8L * width * height);
            for (var band = 0; band < 4; band++) {
                bands[band] = new short[width * height];
            }
        }

        boolean complete() {
            return valid == 0xF;
        }
    }

    /**
     * Decodes a bitstream into photosites.
     *
     * @param data the bytes holding the bitstream
     * @param offset the index of its first byte
     * @param length its length in bytes
     * @param outputBits the bits of the photosites written, 12 or 14
     * @param greenFirst whether the Bayer quads are green, blue, red, green rather than red, green, green, blue
     * @param out the photosites, row by row
     * @param outWidth the width of {@code out}
     * @param outHeight the height of {@code out}
     * @param x the column of {@code out} the image's first photosite goes to
     * @param y the row of {@code out} the image's first photosite goes to
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the bitstream is corrupt or ends before the image is decoded
     * @throws MediaException.Unsupported if the bitstream uses a part of VC-5 that is not decoded
     */
    static void decode(byte[] data, int offset, int length, int outputBits, boolean greenFirst, short[] out,
                       int outWidth, int outHeight, int x, int y, MediaLimits limits) {
        var bits = new Bits(data, offset, length);
        if (bits.read(16) << 16 != (START_MARKER & 0xFFFF0000) || bits.read(16) != (START_MARKER & 0xFFFF)) {
            throw new MediaException.Malformed("VC-5 bitstream without its start marker");
        }
        var channelCount = 0;
        var imageWidth = 0;
        var imageHeight = 0;
        var imageFormat = 0;
        var patternWidth = 0;
        var patternHeight = 0;
        var prescale = new int[WAVELETS];
        var lowpassPrecision = 16;
        var quantization = 0;
        var channel = 0;
        var subband = 0;
        var subbandCount = SUBBANDS;
        var headerFinished = false;
        Wavelet[][] wavelets = null;
        int channelWidth = 0;
        int channelHeight = 0;
        while (true) {
            if (bits.exhausted()) {
                throw new MediaException.Malformed("VC-5 bitstream ends before its image is decoded");
            }
            var tag = (short) bits.read(16);
            var value = bits.read(16);
            var required = tag < 0 ? -tag : tag;
            var header = required == 12 || required == 20 || required == 21 || required == 84 || required == 106
                    || required == 107 || required == 108 || required == 102;
            if (header && headerFinished) {
                throw new MediaException.Malformed("VC-5 header parameter " + required + " after the header");
            }
            if (!header && !headerFinished) {
                headerFinished = true;
                if (imageFormat != IMAGE_FORMAT_RAW || patternWidth != 2 || patternHeight != 2 || channelCount != 4) {
                    throw new MediaException.Unsupported("raw", "VC-5 image format " + imageFormat + " of "
                            + channelCount + " channels is not supported");
                }
                if (imageWidth < 12 || imageHeight < 12 || imageWidth % 2 != 0 || imageHeight % 2 != 0) {
                    throw new MediaException.Malformed("VC-5 image of " + imageWidth + "x" + imageHeight);
                }
                LimitChecks.checkPicture(limits, imageWidth, imageHeight);
                channelWidth = imageWidth / 2;
                channelHeight = imageHeight / 2;
                wavelets = new Wavelet[channelCount][WAVELETS];
                for (var index = 0; index < channelCount; index++) {
                    var width = channelWidth;
                    var height = channelHeight;
                    for (var level = 0; level < WAVELETS; level++) {
                        width = (width + 1) / 2;
                        height = (height + 1) / 2;
                        wavelets[index][level] = new Wavelet(width, height, limits);
                    }
                }
            }
            switch (required) {
                case 12 -> {
                    if (value <= 0 || value > MAX_CHANNELS) {
                        throw new MediaException.Malformed("VC-5 bitstream of " + value + " channels");
                    }
                    channelCount = value;
                }
                case 20 -> imageWidth = value;
                case 21 -> imageHeight = value;
                case 84 -> imageFormat = value;
                case 106 -> patternWidth = value;
                case 107 -> patternHeight = value;
                case 108, 102 -> {
                }
                case 14 -> {
                    if (value < 1 || value > SUBBANDS) {
                        throw new MediaException.Malformed("VC-5 bitstream of " + value + " subbands");
                    }
                    subbandCount = value;
                }
                case 48 -> subband = value;
                case 53 -> quantization = value;
                case 35 -> {
                    if (value < 1 || value > 16) {
                        throw new MediaException.Malformed("VC-5 lowpass precision " + value);
                    }
                    lowpassPrecision = value;
                }
                case 62 -> channel = value;
                case 101, 104, 105 -> {
                }
                case 109 -> {
                    for (var index = 0; index < WAVELETS; index++) {
                        prescale[index] = (value >> (14 - index * 2)) & 3;
                    }
                }
                default -> {
                    if ((required & 0x6000) == 0x6000) {
                        var size = (value & 0xFFFF) + ((required & 0xFF) << 16);
                        if (wavelets == null) {
                            throw new MediaException.Malformed("VC-5 codeblock before the bitstream header");
                        }
                        if (channel < 0 || channel >= channelCount || subband < 0 || subband >= SUBBANDS) {
                            throw new MediaException.Malformed("VC-5 codeblock of channel " + channel + ", subband "
                                    + subband);
                        }
                        var start = bits.position;
                        decodeSubband(bits, wavelets[channel], subband, lowpassPrecision, quantization, prescale,
                                limits);
                        if (bits.position > start + 32L * size) {
                            throw new MediaException.Malformed("VC-5 subband overruns its codeblock");
                        }
                        subband++;
                        if (subband == subbandCount) {
                            channel++;
                            subband = 0;
                        }
                    } else if ((required & 0x4000) != 0) {
                        if (required == 0x4001 || required == 0x4002 || required == 0x4003) {
                            throw new MediaException.Unsupported("raw", "VC-5 inverse component transforms are not"
                                    + " supported");
                        }
                        bits.skip(32L * (value & 0xFFFF));
                    }
                    // the sections of VC-5 Part 6 (tags with the large chunk bit) wrap the elements they hold
                }
            }
            if (wavelets != null && !header && complete(wavelets)) {
                break;
            }
        }
        LimitChecks.checkAllocation(limits, 8L * channelWidth * channelHeight);
        var components = new short[4][];
        for (var index = 0; index < 4; index++) {
            components[index] = new short[channelWidth * channelHeight];
            invert(wavelets[index][0], components[index], channelWidth, channelHeight, prescale[0]);
        }
        pack(components, channelWidth, channelHeight, outputBits, greenFirst, out, outWidth, outHeight, x, y);
    }

    /**
     * {@return whether every channel's lowest wavelet has all its bands}
     *
     * @param wavelets the wavelets of each channel
     */
    private static boolean complete(Wavelet[][] wavelets) {
        for (var channel : wavelets) {
            if (!channel[0].complete()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes one subband into its wavelet band, and rebuilds the next lower level's lowpass band once a level's
     * four bands are decoded.
     *
     * @param bits the bitstream, at the subband's first bit
     * @param wavelets the channel's wavelets
     * @param subband the subband number
     * @param lowpassPrecision the bits of each lowpass coefficient
     * @param quantization the band's quantization
     * @param prescale the prescale shift of each level
     * @param limits the resource bounds
     */
    private static void decodeSubband(Bits bits, Wavelet[] wavelets, int subband, int lowpassPrecision,
                                      int quantization, int[] prescale, MediaLimits limits) {
        var index = SUBBAND_WAVELET[subband];
        var band = SUBBAND_BAND[subband];
        var wavelet = wavelets[index];
        var data = wavelet.bands[band];
        if (subband == 0) {
            for (var position = 0; position < data.length; position++) {
                data[position] = (short) bits.read(lowpassPrecision);
            }
            bits.align();
        } else {
            bits.align();
            runs(bits, data);
            bits.align();
            wavelet.quantization[band] = quantization;
        }
        wavelet.valid |= 1 << band;
        if (wavelet.complete() && index > 0) {
            var lower = wavelets[index - 1];
            invert(wavelet, lower.bands[0], lower.width, lower.height, prescale[index]);
            lower.valid |= 1;
        }
    }

    /**
     * Decodes the run-length and magnitude codes of a highpass band, then its band end marker.
     *
     * @param bits the bitstream
     * @param data the band's coefficients, row by row
     * @throws MediaException.Malformed if a run overruns the band or the band end marker is missing
     */
    private static void runs(Bits bits, short[] data) {
        var index = 0;
        while (index < data.length) {
            var entry = code(bits);
            var count = CODEBOOK[4 * entry + 2];
            var value = CODEBOOK[4 * entry + 3];
            if (count == 0) {
                throw new MediaException.Malformed("VC-5 band ends before its last coefficient");
            }
            if (value != 0 && bits.read(1) == 1) {
                value = -value;
            }
            if (count > data.length - index) {
                throw new MediaException.Malformed("VC-5 run overruns its band");
            }
            for (var run = 0; run < count; run++) {
                data[index++] = (short) value;
            }
        }
        var end = code(bits);
        if (CODEBOOK[4 * end + 2] != 0 && CODEBOOK[4 * end + 3] != 1) {
            throw new MediaException.Malformed("VC-5 band without its end marker");
        }
    }

    /**
     * Reads one code of the codebook.
     *
     * @param bits the bitstream
     * @return the codebook entry
     * @throws MediaException.Malformed if the bits match no code
     */
    private static int code(Bits bits) {
        var fast = FAST[bits.peek(FAST_BITS)];
        if (fast != 0) {
            var entry = fast - 1;
            bits.skip(CODEBOOK[4 * entry]);
            return entry;
        }
        var peeked = bits.peek(26);
        for (var entry = FIRST_LONG; entry < CODEBOOK.length / 4; entry++) {
            var size = CODEBOOK[4 * entry];
            if (peeked >>> (26 - size) == CODEBOOK[4 * entry + 1]) {
                bits.skip(size);
                return entry;
            }
        }
        throw new MediaException.Malformed("VC-5 bitstream holds a code its codebook lacks");
    }

    /**
     * {@return a dequantized coefficient: uncompanded, then multiplied by the band's quantization, clamped to 16
     * bits}
     *
     * @param value the coded coefficient
     * @param quantization the band's quantization
     */
    private static int dequantize(int value, int quantization) {
        var magnitude = Math.abs(value);
        double cubic = magnitude;
        cubic *= magnitude;
        cubic *= magnitude;
        cubic *= 768;
        cubic /= 255 * 255 * 255;
        magnitude += (int) cubic;
        var result = (long) quantization * magnitude;
        result = value < 0 ? -result : result;
        return (int) Math.clamp(result, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Dequantizes a row of a highpass band.
     *
     * @param band the band's coefficients, row by row
     * @param row the index of the row to dequantize
     * @param width the band's width
     * @param quantization the band's quantization
     * @param line receives the dequantized row
     */
    private static void dequantizeRow(short[] band, int row, int width, int quantization, int[] line) {
        var start = row * width;
        for (var column = 0; column < width; column++) {
            line[column] = dequantize(band[start + column], quantization);
        }
    }

    /**
     * Applies the inverse spatial wavelet transform to a wavelet's bands, giving the lowpass band of the next
     * lower level, or a component array, of twice the size.
     *
     * @param wavelet the wavelet whose four bands are inverted
     * @param output receives the inverted samples, row by row
     * @param outputWidth the output's width, at most twice the wavelet's
     * @param outputHeight the output's height, at most twice the wavelet's
     * @param prescale the prescale shift the encoder applied to this level
     */
    private static void invert(Wavelet wavelet, short[] output, int outputWidth, int outputHeight, int prescale) {
        var width = wavelet.width;
        var height = wavelet.height;
        var lowlow = wavelet.bands[0];
        var lowhigh = wavelet.bands[1];
        var highlow = wavelet.bands[2];
        var highhigh = wavelet.bands[3];
        var lowhighQuantization = wavelet.quantization[1];
        var highlowQuantization = wavelet.quantization[2];
        var highhighQuantization = wavelet.quantization[3];
        var evenLowpass = new int[width];
        var evenHighpass = new int[width];
        var oddLowpass = new int[width];
        var oddHighpass = new int[width];
        var lines = new int[][]{new int[width], new int[width], new int[width]};
        var highlowLine = new int[width];
        var highhighLine = new int[width];
        var descale = prescale > 1;
        var shift = prescale == 2 ? 1 : 0;
        var last = height - 1;
        dequantizeRow(lowhigh, 0, width, lowhighQuantization, lines[0]);
        dequantizeRow(lowhigh, 1, width, lowhighQuantization, lines[1]);
        dequantizeRow(lowhigh, 2, width, lowhighQuantization, lines[2]);
        dequantizeRow(highlow, 0, width, highlowQuantization, highlowLine);
        dequantizeRow(highhigh, 0, width, highhighQuantization, highhighLine);
        for (var column = 0; column < width; column++) {
            var a = lowlow[column];
            var b = lowlow[width + column];
            var c = lowlow[2 * width + column];
            evenLowpass[column] = pixel(((11 * a - 4 * b + c + 4) >> 3) + highlowLine[column] >> 1);
            oddLowpass[column] = pixel(((5 * a + 4 * b - c + 4) >> 3) - highlowLine[column] >> 1);
            evenHighpass[column] = pixel(((11 * lines[0][column] - 4 * lines[1][column] + lines[2][column] + 4) >> 3)
                    + highhighLine[column] >> 1);
            oddHighpass[column] = pixel(((5 * lines[0][column] + 4 * lines[1][column] - lines[2][column] + 4) >> 3)
                    - highhighLine[column] >> 1);
        }
        horizontal(evenLowpass, evenHighpass, output, 0, width, outputWidth, descale, shift);
        horizontal(oddLowpass, oddHighpass, output, outputWidth, width, outputWidth, descale, shift);
        var row = 1;
        for (; row < last; row++) {
            dequantizeRow(highlow, row, width, highlowQuantization, highlowLine);
            dequantizeRow(highhigh, row, width, highhighQuantization, highhighLine);
            var above = (row - 1) * width;
            for (var column = 0; column < width; column++) {
                var a = lowlow[above + column];
                var b = lowlow[above + width + column];
                var c = lowlow[above + 2 * width + column];
                evenLowpass[column] = pixel((((a - c + 4) >> 3) + b + highlowLine[column]) >> 1);
                oddLowpass[column] = pixel((((c - a + 4) >> 3) + b - highlowLine[column]) >> 1);
                var d = lines[0][column];
                var e = lines[1][column];
                var f = lines[2][column];
                evenHighpass[column] = pixel((((d - f + 4) >> 3) + e + highhighLine[column]) >> 1);
                oddHighpass[column] = pixel((((f - d + 4) >> 3) + e - highhighLine[column]) >> 1);
            }
            horizontal(evenLowpass, evenHighpass, output, 2 * row * outputWidth, width, outputWidth, descale, shift);
            horizontal(oddLowpass, oddHighpass, output, (2 * row + 1) * outputWidth, width, outputWidth, descale,
                    shift);
            if (row < last - 1) {
                var rotated = lines[0];
                lines[0] = lines[1];
                lines[1] = lines[2];
                lines[2] = rotated;
                dequantizeRow(lowhigh, row + 2, width, lowhighQuantization, lines[2]);
            }
        }
        dequantizeRow(highlow, row, width, highlowQuantization, highlowLine);
        dequantizeRow(highhigh, row, width, highhighQuantization, highhighLine);
        var base = row * width;
        for (var column = 0; column < width; column++) {
            var a = lowlow[base + column];
            var b = lowlow[base - width + column];
            var c = lowlow[base - 2 * width + column];
            evenLowpass[column] = pixel(((5 * a + 4 * b - c + 4) >> 3) + highlowLine[column] >> 1);
            oddLowpass[column] = pixel(((11 * a - 4 * b + c + 4) >> 3) - highlowLine[column] >> 1);
            evenHighpass[column] = pixel(((5 * lines[2][column] + 4 * lines[1][column] - lines[0][column] + 4) >> 3)
                    + highhighLine[column] >> 1);
            oddHighpass[column] = pixel(((11 * lines[2][column] - 4 * lines[1][column] + lines[0][column] + 4) >> 3)
                    - highhighLine[column] >> 1);
        }
        horizontal(evenLowpass, evenHighpass, output, 2 * row * outputWidth, width, outputWidth, descale, shift);
        if (2 * row + 1 < outputHeight) {
            horizontal(oddLowpass, oddHighpass, output, (2 * row + 1) * outputWidth, width, outputWidth, descale,
                    shift);
        }
    }

    /**
     * {@return a value clamped to the signed 16-bit range}
     *
     * @param value the value to clamp
     */
    private static int pixel(int value) {
        return Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Applies the inverse horizontal wavelet filter to a row of lowpass and highpass coefficients, writing a row
     * twice as wide: clamped to 14 bits, or, when descaling, the prescale shift applied instead of the last
     * halving and the result clamped to 16 bits.
     *
     * @param lowpass the lowpass coefficients
     * @param highpass the highpass coefficients
     * @param output receives the output row
     * @param start the index of the output row's first value
     * @param width the coefficients of a row, at least 3
     * @param outputWidth the output row's width, at most twice {@code width}
     * @param descale whether the encoder prescaled the level by more than one bit
     * @param shift the left shift of a descaled value
     */
    private static void horizontal(int[] lowpass, int[] highpass, short[] output, int start, int width, int outputWidth,
                                   boolean descale, int shift) {
        var last = width - 1;
        var even = ((11 * lowpass[0] - 4 * lowpass[1] + lowpass[2] + 4) >> 3) + highpass[0];
        var odd = ((5 * lowpass[0] + 4 * lowpass[1] - lowpass[2] + 4) >> 3) - highpass[0];
        output[start] = finish(even, descale, shift);
        output[start + 1] = finish(odd, descale, shift);
        var column = 1;
        for (; column < last; column++) {
            var left = lowpass[column - 1];
            var right = lowpass[column + 1];
            even = ((left - right + 4) >> 3) + lowpass[column] + highpass[column];
            odd = ((right - left + 4) >> 3) + lowpass[column] - highpass[column];
            output[start + 2 * column] = finish(even, descale, shift);
            output[start + 2 * column + 1] = finish(odd, descale, shift);
        }
        even = ((5 * lowpass[column] + 4 * lowpass[column - 1] - lowpass[column - 2] + 4) >> 3) + highpass[column];
        output[start + 2 * column] = finish(even, descale, shift);
        if (2 * column + 1 < outputWidth) {
            odd = ((11 * lowpass[column] - 4 * lowpass[column - 1] + lowpass[column - 2] + 4) >> 3) - highpass[column];
            output[start + 2 * column + 1] = finish(odd, descale, shift);
        }
    }

    /**
     * {@return the last step of the horizontal filter: halved and clamped to 14 bits, or shifted and clamped to
     * 16 bits when descaling}
     *
     * @param value the filtered value before its last step
     * @param descale whether the level is descaled
     * @param shift the left shift of a descaled value
     */
    private static short finish(int value, boolean descale, int shift) {
        if (descale) {
            return (short) pixel(value << shift);
        }
        return (short) Math.clamp(value >> 1, 0, (1 << 14) - 1);
    }

    /**
     * Applies the inverse component transform to the four component arrays and the inverse log curve to the
     * photosites, and writes them reduced to the output depth.
     *
     * @param components the green sum, red difference, blue difference and green difference arrays
     * @param width the arrays' width
     * @param height the arrays' height
     * @param outputBits the bits of the photosites written
     * @param greenFirst whether the quads are green, blue, red, green
     * @param out the photosites
     * @param outWidth the width of {@code out}
     * @param outHeight the height of {@code out}
     * @param x the column of {@code out} the first photosite goes to
     * @param y the row of {@code out} the first photosite goes to
     */
    private static void pack(short[][] components, int width, int height, int outputBits, boolean greenFirst,
                             short[] out, int outWidth, int outHeight, int x, int y) {
        var shift = 16 - outputBits;
        var rows = Math.min(height, (outHeight - y + 1) / 2);
        var columns = Math.min(width, (outWidth - x + 1) / 2);
        for (var row = 0; row < rows; row++) {
            var first = (y + 2 * row) * outWidth + x;
            var second = y + 2 * row + 1 < outHeight ? first + outWidth : -1;
            for (var column = 0; column < columns; column++) {
                var index = row * width + column;
                var gs = components[0][index] & 0xFFFF;
                var rg = (components[1][index] & 0xFFFF) - 2048;
                var bg = (components[2][index] & 0xFFFF) - 2048;
                var gd = (components[3][index] & 0xFFFF) - 2048;
                var red = LOG_CURVE[Math.clamp((rg << 1) + gs, 0, 4095)] >> shift;
                var blue = LOG_CURVE[Math.clamp((bg << 1) + gs, 0, 4095)] >> shift;
                var green1 = LOG_CURVE[Math.clamp(gs + gd, 0, 4095)] >> shift;
                var green2 = LOG_CURVE[Math.clamp(gs - gd, 0, 4095)] >> shift;
                var at = first + 2 * column;
                var paired = x + 2 * column + 1 < outWidth;
                if (greenFirst) {
                    out[at] = (short) green1;
                    if (paired) {
                        out[at + 1] = (short) blue;
                    }
                    if (second >= 0) {
                        out[second + 2 * column] = (short) red;
                        if (paired) {
                            out[second + 2 * column + 1] = (short) green2;
                        }
                    }
                } else {
                    out[at] = (short) red;
                    if (paired) {
                        out[at + 1] = (short) green1;
                    }
                    if (second >= 0) {
                        out[second + 2 * column] = (short) green2;
                        if (paired) {
                            out[second + 2 * column + 1] = (short) blue;
                        }
                    }
                }
            }
        }
    }
}
