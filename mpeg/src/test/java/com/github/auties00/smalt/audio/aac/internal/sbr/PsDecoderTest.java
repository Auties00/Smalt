package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the full parametric stereo decoder on synthetic subband matrices: the phase codebooks
 * against the codeword tables of ISO/IEC 14496-3, the statistics both mixing procedures promise,
 * perfect reconstruction through both hybrid filterbanks, the phase rotations of the inter-channel
 * and overall phase differences on exactly the bands that carry them, and robustness against
 * arbitrary data and band layout switches.
 */
class PsDecoderTest {
    private static final int SLOTS = 32;

    /** A bit string writer for hand-built {@code ps_data} elements. */
    static final class Bits {
        private final MsbBitWriter writer = new MsbBitWriter();
        private int length;

        Bits put(int value, int bits) {
            writer.write(value, bits);
            length += bits;
            return this;
        }

        Bits code(String codeword) {
            for (var i = 0; i < codeword.length(); i++) {
                put(codeword.charAt(i) - '0', 1);
            }
            return this;
        }

        int length() {
            return length;
        }

        AacBitReader reader() {
            writer.write(0, 32);
            return new AacBitReader(writer.toByteArray());
        }
    }

    /**
     * Writes a {@code ps_data} element with one envelope of zero intensity differences and full
     * coherence, optionally followed by phase parameters on every transmitted bin.
     *
     * @param iidMode the intensity difference mode (0 to 2, coarse)
     * @param iccMode the coherence mode (0 to 5)
     * @param ipd the inter-channel phase difference index of every phase bin, or -1 for none
     * @param opd the overall phase difference index of every phase bin
     */
    static Bits identity(int iidMode, int iccMode, int ipd, int opd) {
        var bins = new int[]{10, 20, 34}[iidMode];
        var iccBins = new int[]{10, 20, 34}[iccMode % 3];
        var bits = new Bits().put(1, 1).put(1, 1).put(iidMode, 3).put(1, 1).put(iccMode, 3).put(ipd >= 0 ? 1 : 0, 1);
        bits.put(0, 1).put(1, 2);
        bits.put(0, 1);
        for (var b = 0; b < bins; b++) {
            bits.code("0");
        }
        bits.put(0, 1);
        for (var b = 0; b < iccBins; b++) {
            bits.code("0");
        }
        if (ipd >= 0) {
            var phases = new int[]{5, 11, 17}[iidMode];
            var extension = new Bits().put(0, 2).put(1, 1).put(0, 1).code(IPD_FREQUENCY[ipd]);
            for (var b = 1; b < phases; b++) {
                extension.code(IPD_FREQUENCY[0]);
            }
            extension.put(0, 1).code(OPD_FREQUENCY[opd]);
            for (var b = 1; b < phases; b++) {
                extension.code(OPD_FREQUENCY[0]);
            }
            extension.put(0, 1);
            var bytes = (extension.length() + 7) / 8;
            bits.put(bytes, 4);
            var copy = extension.reader();
            for (var i = 0; i < extension.length(); i++) {
                bits.put(copy.readBit(), 1);
            }
            bits.put(0, 8 * bytes - extension.length());
        }
        return bits;
    }

    /** The codewords of {@code huff_ipd_df}, ISO/IEC 14496-3 Table 8.B.20. */
    static final String[] IPD_FREQUENCY = {"1", "000", "0110", "0100", "0010", "0011", "0101", "0111"};

    /** The codewords of {@code huff_ipd_dt}, Table 8.B.20. */
    static final String[] IPD_TIME = {"1", "010", "0010", "00011", "00010", "0000", "0011", "011"};

    /** The codewords of {@code huff_opd_df}, Table 8.B.21. */
    static final String[] OPD_FREQUENCY = {"1", "001", "0110", "0100", "01111", "01110", "0101", "000"};

    /** The codewords of {@code huff_opd_dt}, Table 8.B.21. */
    static final String[] OPD_TIME = {"1", "010", "0001", "00111", "00110", "0000", "0010", "011"};

    /** Returns a random signal of {@code length} slots of 64 complex subbands, {re, im}. */
    static float[][] signal(int length, long seed) {
        var random = new Random(seed);
        var re = new float[length * 64];
        var im = new float[length * 64];
        for (var i = 0; i < re.length; i++) {
            re[i] = (float) random.nextGaussian();
            im[i] = (float) random.nextGaussian();
        }
        return new float[][]{re, im};
    }

    /**
     * Loads frame {@code frame} of a signal into a matrix as the spectral band replication
     * stage does: 32 slots of every subband, then six slots of look-ahead of the five lowest.
     */
    static void load(float[][] signal, int frame, float[] re, float[] im, int band) {
        java.util.Arrays.fill(re, 0);
        java.util.Arrays.fill(im, 0);
        for (var slot = 0; slot < SLOTS + 6; slot++) {
            var source = (frame * SLOTS + slot) * 64;
            var bands = slot < SLOTS ? 64 : 5;
            for (var b = 0; b < bands; b++) {
                if (band < 0 || b == band) {
                    re[slot * 64 + b] = signal[0][source + b];
                    im[slot * 64 + b] = signal[1][source + b];
                }
            }
        }
    }

    @Nested
    @DisplayName("phase codebooks")
    class Codebooks {
        @Test
        @DisplayName("decode every codeword of Tables 8.B.20 and 8.B.21 to its index")
        void tables() {
            var trees = new byte[][]{SbrHuffman.IPD_FREQUENCY, SbrHuffman.IPD_TIME, SbrHuffman.OPD_FREQUENCY,
                    SbrHuffman.OPD_TIME};
            var codes = new String[][]{IPD_FREQUENCY, IPD_TIME, OPD_FREQUENCY, OPD_TIME};
            for (var t = 0; t < trees.length; t++) {
                for (var index = 0; index < 8; index++) {
                    var reader = new Bits().code(codes[t][index]).reader();
                    assertEquals(index, SbrHuffman.decode(trees[t], reader), "table " + t + " index " + index);
                    assertEquals(codes[t][index].length(), reader.bitPosition());
                }
            }
        }
    }

    @Nested
    @DisplayName("mixing procedures")
    class Mixing {
        @ParameterizedTest(name = "procedure B {0}, fine {1}")
        @CsvSource({"false, false", "false, true", "true, false", "true, true"})
        @DisplayName("produce the coded level ratio and coherence with constant total power")
        void statistics(boolean procedureB, boolean fine) {
            var levels = fine ? new double[]{-50, -45, -40, -35, -30, -25, -22, -19, -16, -13, -10, -8, -6, -4, -2,
                    0, 2, 4, 6, 8, 10, 13, 16, 19, 22, 25, 30, 35, 40, 45, 50}
                    : new double[]{-25, -18, -14, -10, -7, -4, -2, 0, 2, 4, 7, 10, 14, 18, 25};
            var coherences = new double[]{1, 0.937, 0.84118, 0.60092, 0.36764, 0, -0.589, -1};
            var steps = fine ? 15 : 7;
            var h = new double[4];
            for (var iid = -steps; iid <= steps; iid++) {
                for (var icc = 0; icc < 8; icc++) {
                    PsDecoder.mixingMatrix(procedureB, fine, iid, icc, h);
                    var left = h[0] * h[0] + h[2] * h[2];
                    var right = h[1] * h[1] + h[3] * h[3];
                    var cross = h[0] * h[1] + h[2] * h[3];
                    var label = "iid " + iid + " icc " + icc;
                    assertEquals(2, left + right, 1e-9, label);
                    assertEquals(levels[steps + iid], 10 * Math.log10(left / right), 1e-9, label);
                    var rho = procedureB ? Math.max(coherences[icc], 0.05) : coherences[icc];
                    assertEquals(rho, cross / Math.sqrt(left * right), 1e-9, label);
                }
            }
        }

        @Test
        @DisplayName("differ between the procedures once the coherence drops")
        void procedures() {
            var a = new double[4];
            var b = new double[4];
            PsDecoder.mixingMatrix(false, false, 0, 0, a);
            PsDecoder.mixingMatrix(true, false, 0, 0, b);
            for (var i = 0; i < 4; i++) {
                assertEquals(a[i], b[i], 1e-12, "full coherence leaves the identity mix");
            }
            PsDecoder.mixingMatrix(false, false, 3, 4, a);
            PsDecoder.mixingMatrix(true, false, 3, 4, b);
            var difference = 0.0;
            for (var i = 0; i < 4; i++) {
                difference += Math.abs(a[i] - b[i]);
            }
            assertTrue(difference > 0.1, "procedure B rotates the principal axis: " + difference);
        }
    }

    @Nested
    @DisplayName("hybrid filterbanks")
    class Hybrid {
        @ParameterizedTest(name = "mode {0}")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("reconstruct the mono signal in both channels under identity parameters")
        void reconstruct(int mode) {
            var frames = 4;
            var signal = signal(frames * SLOTS + 6, mode);
            var decoder = new PsDecoder();
            var re = new float[(SLOTS + 6) * 64];
            var im = new float[(SLOTS + 6) * 64];
            var rightRe = new float[SLOTS * 64];
            var rightIm = new float[SLOTS * 64];
            for (var frame = 0; frame < frames; frame++) {
                var bits = identity(mode, mode, -1, 0);
                var reader = bits.reader();
                assertEquals(bits.length(), decoder.read(reader, bits.length()));
                assertTrue(decoder.seen());
                load(signal, frame, re, im, -1);
                decoder.process(re, im, rightRe, rightIm, SLOTS, 64);
                if (frame == 0) {
                    continue;
                }
                for (var slot = 0; slot < SLOTS; slot++) {
                    for (var band = 0; band < 64; band++) {
                        var source = (frame * SLOTS + slot) * 64 + band;
                        var index = slot * 64 + band;
                        var label = "frame " + frame + " slot " + slot + " band " + band;
                        assertEquals(signal[0][source], re[index], 2e-5, label);
                        assertEquals(signal[1][source], im[index], 2e-5, label);
                        assertEquals(signal[0][source], rightRe[index], 2e-5, label);
                        assertEquals(signal[1][source], rightIm[index], 2e-5, label);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("phase parameters")
    class Phases {
        @ParameterizedTest(name = "mode {0}, band {1}, rotated {2}")
        @CsvSource({
                "0, 4, true", "0, 5, false", "1, 4, true", "1, 5, true", "1, 6, false", "2, 5, true", "2, 6, false",
                "2, 9, false"
        })
        @DisplayName("rotate the left channel by the overall and the right by the overall minus the inter-channel phase")
        void rotation(int mode, int band, boolean rotated) {
            var frames = 5;
            var signal = signal(frames * SLOTS + 6, band);
            var decoder = new PsDecoder();
            var re = new float[(SLOTS + 6) * 64];
            var im = new float[(SLOTS + 6) * 64];
            var rightRe = new float[SLOTS * 64];
            var rightIm = new float[SLOTS * 64];
            // inter-channel phase pi / 2, overall phase pi / 4
            var left = rotated ? Math.PI / 4 : 0;
            var right = rotated ? -Math.PI / 4 : 0;
            for (var frame = 0; frame < frames; frame++) {
                var bits = identity(mode, mode, 2, 1);
                assertEquals(bits.length(), decoder.read(bits.reader(), bits.length()));
                load(signal, frame, re, im, band);
                decoder.process(re, im, rightRe, rightIm, SLOTS, 64);
                if (frame < 3) {
                    // the phases are smoothed over the two previous parameter positions
                    continue;
                }
                for (var slot = 0; slot < SLOTS; slot++) {
                    var source = (frame * SLOTS + slot) * 64 + band;
                    var index = slot * 64 + band;
                    double sRe = signal[0][source];
                    double sIm = signal[1][source];
                    var label = "frame " + frame + " slot " + slot;
                    assertEquals(sRe * Math.cos(left) - sIm * Math.sin(left), re[index], 2e-5, label);
                    assertEquals(sIm * Math.cos(left) + sRe * Math.sin(left), im[index], 2e-5, label);
                    assertEquals(sRe * Math.cos(right) - sIm * Math.sin(right), rightRe[index], 2e-5, label);
                    assertEquals(sIm * Math.cos(right) + sRe * Math.sin(right), rightIm[index], 2e-5, label);
                }
            }
        }

        @Test
        @DisplayName("conjugate the rotation on the negative-frequency hybrid sub-subbands")
        void conjugated() {
            // a real-valued tone in QMF subband 0 has its positive and negative frequency halves
            // in sub-subbands of the same bin, rotated in opposite directions: an overall phase of
            // pi / 2 turns cos into -sin, still real, where rotating both halves alike would make
            // the left channel imaginary
            var frames = 6;
            var length = frames * SLOTS + 6;
            var re0 = new float[length * 64];
            var im0 = new float[length * 64];
            for (var t = 0; t < length; t++) {
                re0[t * 64] = (float) Math.cos(0.3 * Math.PI * t);
            }
            var signal = new float[][]{re0, im0};
            var decoder = new PsDecoder();
            var re = new float[(SLOTS + 6) * 64];
            var im = new float[(SLOTS + 6) * 64];
            var rightRe = new float[SLOTS * 64];
            var rightIm = new float[SLOTS * 64];
            double monoEnergy = 0;
            double leftEnergy = 0;
            double imaginaryEnergy = 0;
            for (var frame = 0; frame < frames; frame++) {
                var bits = identity(1, 1, 0, 2);
                decoder.read(bits.reader(), bits.length());
                load(signal, frame, re, im, 0);
                decoder.process(re, im, rightRe, rightIm, SLOTS, 64);
                if (frame < 3) {
                    continue;
                }
                for (var slot = 0; slot < SLOTS; slot++) {
                    var mono = re0[(frame * SLOTS + slot) * 64];
                    monoEnergy += mono * mono;
                    leftEnergy += re[slot * 64] * re[slot * 64];
                    imaginaryEnergy += im[slot * 64] * im[slot * 64];
                }
            }
            assertEquals(monoEnergy, leftEnergy, 0.02 * monoEnergy);
            assertTrue(imaginaryEnergy < 0.01 * monoEnergy, "imaginary share " + imaginaryEnergy / monoEnergy);
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("keeps every output finite on arbitrary data and band layout switches")
        void arbitrary() {
            var random = new Random(7);
            var signal = signal(40 * SLOTS + 6, 3);
            var decoder = new PsDecoder();
            var re = new float[(SLOTS + 6) * 64];
            var im = new float[(SLOTS + 6) * 64];
            var rightRe = new float[SLOTS * 64];
            var rightIm = new float[SLOTS * 64];
            for (var frame = 0; frame < 40; frame++) {
                if (frame % 3 == 0) {
                    var bits = identity(frame % 2 == 0 ? 2 : 1, 3 + frame % 3, frame % 4 == 0 ? 3 : -1, 5);
                    decoder.read(bits.reader(), bits.length());
                } else {
                    var bytes = new byte[random.nextInt(40) + 1];
                    random.nextBytes(bytes);
                    bytes[0] |= (byte) 0x80;
                    var reader = new AacBitReader(bytes);
                    decoder.read(reader, 8L * bytes.length);
                }
                load(signal, frame, re, im, -1);
                decoder.process(re, im, rightRe, rightIm, SLOTS, 20 + random.nextInt(45));
                for (var i = 0; i < SLOTS * 64; i++) {
                    assertTrue(Float.isFinite(re[i]) && Float.isFinite(im[i]) && Float.isFinite(rightRe[i])
                            && Float.isFinite(rightIm[i]), "frame " + frame);
                    assertTrue(Math.abs(re[i]) < 1e3 && Math.abs(rightRe[i]) < 1e3, "frame " + frame);
                }
            }
        }
    }
}
