package com.github.auties00.smalt.image.raw.internal;

import java.io.ByteArrayOutputStream;
import java.util.SplittableRandom;

/**
 * Writes VC-5 bitstreams of Bayer images for the tests from given wavelet coefficients: the header the GPR files
 * carry, then for each of the four channels the lowpass band of the top wavelet level as 16-bit coefficients and the
 * nine highpass bands as the codebook's run-length and magnitude codes, each band in its own codeblock.
 */
public final class Vc5Writer {
    public int width = 48;
    public int height = 40;
    /** The prescale shifts of the three wavelet levels, packed as the bitstream's prescale tag. */
    public int prescale = 0x2800;
    /** The quantization of each highpass subband. */
    public int quantization = 12;
    /** Whether a band ends without its end marker. */
    public boolean dropBandEnd;
    /** The coefficients of each channel's ten subbands, row by row; null bands are zero. */
    public final short[][][] subbands = new short[4][10][];

    /** {@return the width of the bands of a wavelet: 0 for the largest, 2 for the top level} */
    public int bandWidth(int wavelet) {
        var size = width / 2;
        for (var index = 0; index <= wavelet; index++) {
            size = (size + 1) / 2;
        }
        return size;
    }

    /** {@return the height of the bands of a wavelet: 0 for the largest, 2 for the top level} */
    public int bandHeight(int wavelet) {
        var size = height / 2;
        for (var index = 0; index <= wavelet; index++) {
            size = (size + 1) / 2;
        }
        return size;
    }

    /**
     * Fills the bands with a scene: a smooth lowpass band and sparse highpass coefficients.
     *
     * @param seed the seed of the highpass coefficients
     * @return this writer
     */
    public Vc5Writer scene(long seed) {
        var random = new SplittableRandom(seed);
        for (var channel = 0; channel < 4; channel++) {
            for (var subband = 0; subband < 10; subband++) {
                var w = bandWidth(SUBBAND_WAVELET[subband]);
                var h = bandHeight(SUBBAND_WAVELET[subband]);
                var band = new short[w * h];
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        if (subband == 0) {
                            // the lowpass band holds the component's mean times the gain of the three levels
                            band[y * w + x] = (short) ((channel == 0 ? 600 + 20 * x + 10 * y : 2048) * 4);
                        } else if (random.nextInt(4) == 0) {
                            band[y * w + x] = (short) (random.nextInt(41) - 20);
                        }
                    }
                }
                subbands[channel][subband] = band;
            }
        }
        return this;
    }

    // the wavelet of each subband, 2 the top level
    private static final int[] SUBBAND_WAVELET = {2, 2, 2, 2, 1, 1, 1, 0, 0, 0};

    /**
     * Writes the bitstream.
     *
     * @return its bytes
     */
    public byte[] write() {
        var out = new Segments();
        out.word(0x56432D35);
        out.pair(12, 4);
        out.pair(20, width);
        out.pair(21, height);
        out.pair(84, 4);
        out.pair(106, 2);
        out.pair(107, 2);
        out.pair(108, 1);
        out.pair(102, 12);
        out.pair(109, prescale);
        for (var channel = 0; channel < 4; channel++) {
            for (var subband = 0; subband < 10; subband++) {
                out.pair(62, channel);
                out.pair(48, subband);
                out.pair(35, 16);
                out.pair(53, subband == 0 ? 0 : quantization);
                var w = bandWidth(SUBBAND_WAVELET[subband]);
                var h = bandHeight(SUBBAND_WAVELET[subband]);
                var band = subbands[channel][subband] != null ? subbands[channel][subband] : new short[w * h];
                var body = subband == 0 ? lowpass(band) : highpass(band);
                var size = body.length / 4;
                out.pair(-(0x6000 | (size >> 16)), size & 0xFFFF);
                out.bytes(body);
            }
        }
        return out.toByteArray();
    }

    private static byte[] lowpass(short[] band) {
        var bits = new BitWriter();
        for (var value : band) {
            bits.write(value & 0xFFFF, 16);
        }
        return bits.aligned();
    }

    private byte[] highpass(short[] band) {
        var bits = new BitWriter();
        var codebook = Vc5Decoder.CODEBOOK;
        var zeroRun = entry(1, 0);
        for (var value : band) {
            if (value == 0) {
                bits.write(codebook[4 * zeroRun + 1], codebook[4 * zeroRun]);
            } else {
                var magnitude = entry(1, Math.abs(value));
                bits.write(codebook[4 * magnitude + 1], codebook[4 * magnitude]);
                bits.write(value < 0 ? 1 : 0, 1);
            }
        }
        if (!dropBandEnd) {
            var end = entry(0, 1);
            bits.write(codebook[4 * end + 1], codebook[4 * end]);
        }
        return bits.aligned();
    }

    // the codebook entry of a run length and magnitude
    private static int entry(int count, int value) {
        var codebook = Vc5Decoder.CODEBOOK;
        for (var index = 0; index < codebook.length / 4; index++) {
            if (codebook[4 * index + 2] == count && codebook[4 * index + 3] == value) {
                return index;
            }
        }
        throw new IllegalArgumentException("no code for a run of " + count + " of " + value);
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        void write(int value, int bits) {
            for (var bit = bits - 1; bit >= 0; bit--) {
                buffer = (buffer << 1) | ((value >>> bit) & 1);
                if (++count == 8) {
                    out.write((int) buffer);
                    buffer = 0;
                    count = 0;
                }
            }
        }

        // the bits, padded with zeros to a multiple of 32
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

    private static final class Segments {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void word(int value) {
            out.write(value >>> 24);
            out.write(value >>> 16);
            out.write(value >>> 8);
            out.write(value);
        }

        void pair(int tag, int value) {
            word(((tag & 0xFFFF) << 16) | (value & 0xFFFF));
        }

        void bytes(byte[] data) {
            out.writeBytes(data);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
