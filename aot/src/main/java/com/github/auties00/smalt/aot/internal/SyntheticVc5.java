/* Ports gpr 1.0.0 (source/lib/vc5_common: table17.inc, the codes of the run lengths and magnitudes it writes).
   Copyright 2018 GoPro, Inc.; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.RgbaPicture;

import java.io.ByteArrayOutputStream;
import java.util.SplittableRandom;

/**
 * Writes the VC-5 bitstreams of synthetic GoPro GPR files: the header the GPR files carry, then for
 * each of the four channels of the Bayer quads (the green sum and the red, blue and green
 * differences) the lowpass band of the top wavelet level as 16-bit coefficients and the nine
 * highpass bands as run-length and magnitude codes, each band in its own codeblock.
 *
 * <p>The green sum's lowpass band follows the picture's green, the difference channels' are
 * neutral, and the highpass bands hold sparse coefficients of up to 20 in magnitude, so the decoder
 * runs its run-length decoding, dequantization, three inverse wavelet levels and inverse component
 * transform over every band.
 */
final class SyntheticVc5 {
    /**
     * The bitstream's start code, {@code "VC-5"}.
     */
    private static final int START = 0x56432D35;

    /**
     * The prescale shifts of the three wavelet levels, packed as the bitstream's prescale tag.
     */
    private static final int PRESCALE = 0x2800;

    /**
     * The quantization of each highpass band.
     */
    private static final int QUANTIZATION = 12;

    /**
     * The value of a difference channel's lowpass band that stands for no difference, on the 12-bit
     * scale.
     */
    private static final int NEUTRAL = 2048;

    /**
     * The gain of the three wavelet levels on the lowpass band.
     */
    private static final int GAIN = 4;

    /**
     * The largest magnitude of a highpass coefficient.
     */
    private static final int MAGNITUDE = 20;

    /**
     * The wavelet level of each band, 2 being the top level.
     */
    private static final int[] BAND_WAVELET = {2, 2, 2, 2, 1, 1, 1, 0, 0, 0};

    /**
     * The code lengths of a run of one coefficient of each magnitude from 0 to {@link #MAGNITUDE}.
     */
    private static final int[] MAGNITUDE_SIZES = {1, 2, 3, 5, 6, 6, 7, 7, 7, 8, 8, 9, 9, 9, 10, 10, 10, 10, 10, 11, 11};

    /**
     * The codes of a run of one coefficient of each magnitude from 0 to {@link #MAGNITUDE}.
     */
    private static final int[] MAGNITUDE_CODES = {0x0, 0x2, 0x7, 0x19, 0x30, 0x36, 0x63, 0x6B, 0x6F, 0xD4, 0xDC, 0x189,
            0x1A0, 0x1AB, 0x310, 0x316, 0x354, 0x375, 0x377, 0x623, 0x684};

    /**
     * The code length of the band end marker.
     */
    private static final int END_SIZE = 26;

    /**
     * The code of the band end marker.
     */
    private static final int END_CODE = 0x3114BA3;

    /**
     * Prevents instantiation.
     */
    private SyntheticVc5() {
        throw new AssertionError();
    }

    /**
     * Writes the bitstream of a picture's RGGB mosaic.
     *
     * @param picture the picture, of even width and height
     * @param seed the seed of the highpass coefficients
     * @return the bitstream
     */
    static byte[] bitstream(RgbaPicture picture, long seed) {
        var width = picture.width();
        var height = picture.height();
        var random = new SplittableRandom(seed);
        var out = new ByteArrayOutputStream();
        word(out, START);
        pair(out, 12, 4);
        pair(out, 20, width);
        pair(out, 21, height);
        pair(out, 84, 4);
        pair(out, 106, 2);
        pair(out, 107, 2);
        pair(out, 108, 1);
        pair(out, 102, 12);
        pair(out, 109, PRESCALE);
        for (var channel = 0; channel < 4; channel++) {
            for (var band = 0; band < 10; band++) {
                pair(out, 62, channel);
                pair(out, 48, band);
                pair(out, 35, 16);
                pair(out, 53, band == 0 ? 0 : QUANTIZATION);
                var bandWidth = bandSize(width, BAND_WAVELET[band]);
                var bandHeight = bandSize(height, BAND_WAVELET[band]);
                var body = band == 0 ? lowpass(picture, channel, bandWidth, bandHeight)
                        : highpass(bandWidth * bandHeight, random);
                var size = body.length / 4;
                pair(out, -(0x6000 | (size >> 16)), size & 0xFFFF);
                out.writeBytes(body);
            }
        }
        return out.toByteArray();
    }

    /**
     * {@return the size of the bands of a wavelet level along one dimension}
     *
     * @param size the picture's size along the dimension
     * @param wavelet the wavelet level, 0 for the largest
     */
    private static int bandSize(int size, int wavelet) {
        var band = size / 2;
        for (var level = 0; level <= wavelet; level++) {
            band = (band + 1) / 2;
        }
        return band;
    }

    /**
     * Writes the lowpass band of one channel: for the green sum, the 12-bit mean of the picture's
     * green over the block each coefficient covers, and for the three difference channels the
     * neutral middle value, each times the gain of the three wavelet levels.
     *
     * @param picture the picture whose green the green sum follows
     * @param channel the channel: 0 the green sum, 1 to 3 the red, blue and green differences
     * @param bandWidth the band's width
     * @param bandHeight the band's height
     * @return the band's codeblock body
     */
    private static byte[] lowpass(RgbaPicture picture, int channel, int bandWidth, int bandHeight) {
        var bits = new Bits();
        var pixels = picture.pixels();
        var stride = picture.stride();
        var quadWidth = picture.width() / 2;
        var quadHeight = picture.height() / 2;
        for (var y = 0; y < bandHeight; y++) {
            for (var x = 0; x < bandWidth; x++) {
                var value = NEUTRAL;
                if (channel == 0) {
                    var sum = 0L;
                    var count = 0;
                    for (var dy = 0; dy < 8 && y * 8 + dy < quadHeight; dy++) {
                        for (var dx = 0; dx < 8 && x * 8 + dx < quadWidth; dx++) {
                            sum += pixels[2 * (y * 8 + dy) * stride + 2 * (x * 8 + dx) * 4 + 1] & 0xFF;
                            count++;
                        }
                    }
                    value = count == 0 ? 0 : (int) (sum * 16 / count);
                }
                bits.write(value * GAIN & 0xFFFF, 16);
            }
        }
        return bits.aligned();
    }

    /**
     * Writes a highpass band of sparse coefficients, then its band end marker.
     *
     * @param count the band's coefficients
     * @param random the source of the coefficients
     * @return the band's codeblock body
     */
    private static byte[] highpass(int count, SplittableRandom random) {
        var bits = new Bits();
        for (var index = 0; index < count; index++) {
            var value = random.nextInt(4) == 0 ? random.nextInt(2 * MAGNITUDE + 1) - MAGNITUDE : 0;
            var magnitude = Math.abs(value);
            bits.write(MAGNITUDE_CODES[magnitude], MAGNITUDE_SIZES[magnitude]);
            if (value != 0) {
                bits.write(value < 0 ? 1 : 0, 1);
            }
        }
        bits.write(END_CODE, END_SIZE);
        return bits.aligned();
    }

    /**
     * Writes a 32-bit big-endian word.
     *
     * @param out the stream
     * @param value the word
     */
    private static void word(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /**
     * Writes a tag and value pair: two big-endian 16-bit words.
     *
     * @param out the stream
     * @param tag the tag, negative for an optional or chunk tag
     * @param value the tag's 16-bit value
     */
    private static void pair(ByteArrayOutputStream out, int tag, int value) {
        word(out, ((tag & 0xFFFF) << 16) | (value & 0xFFFF));
    }

    /**
     * Writes bits most significant first.
     */
    private static final class Bits {
        /**
         * The bytes written.
         */
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /**
         * The bits of the byte being filled.
         */
        private int buffer;

        /**
         * The bits in {@link #buffer}.
         */
        private int count;

        /**
         * Writes the low bits of a value, most significant first.
         *
         * @param value the bits, in its low {@code size} bits
         * @param size the number of bits
         */
        void write(int value, int size) {
            for (var bit = size - 1; bit >= 0; bit--) {
                buffer = (buffer << 1) | ((value >>> bit) & 1);
                if (++count == 8) {
                    out.write(buffer);
                    buffer = 0;
                    count = 0;
                }
            }
        }

        /**
         * {@return the bits, padded with zeros to a multiple of 32}
         */
        byte[] aligned() {
            while (count != 0) {
                write(0, 1);
            }
            while (out.size() % 4 != 0) {
                out.write(0);
            }
            return out.toByteArray();
        }
    }
}
