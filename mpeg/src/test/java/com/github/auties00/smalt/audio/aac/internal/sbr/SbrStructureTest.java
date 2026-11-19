package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the static structure of the spectral band replication decoder: the Huffman codebooks
 * are complete prefix codes over the value ranges of ISO/IEC 14496-3, the time grid parser
 * resolves and validates every frame class, the frequency tables keep their invariants for every
 * header at every rate, and the Vector API kernels agree with their scalar twins bit for bit.
 */
class SbrStructureTest {
    static AacBitReader reader(int... fields) {
        var writer = new MsbBitWriter();
        for (var i = 0; i < fields.length; i += 2) {
            writer.write(fields[i], fields[i + 1]);
        }
        writer.write(0, 32);
        return new AacBitReader(writer.toByteArray());
    }

    @Nested
    @DisplayName("Huffman codebooks")
    class Codebooks {
        static Stream<Arguments> codebooks() {
            return Stream.of(
                    Arguments.of("envelope 1.5 dB time", SbrHuffman.ENVELOPE_15_TIME, 60),
                    Arguments.of("envelope 1.5 dB frequency", SbrHuffman.ENVELOPE_15_FREQUENCY, 60),
                    Arguments.of("balance 1.5 dB time", SbrHuffman.BALANCE_15_TIME, 24),
                    Arguments.of("balance 1.5 dB frequency", SbrHuffman.BALANCE_15_FREQUENCY, 24),
                    Arguments.of("envelope 3 dB time", SbrHuffman.ENVELOPE_30_TIME, 31),
                    Arguments.of("envelope 3 dB frequency", SbrHuffman.ENVELOPE_30_FREQUENCY, 31),
                    Arguments.of("balance 3 dB time", SbrHuffman.BALANCE_30_TIME, 12),
                    Arguments.of("balance 3 dB frequency", SbrHuffman.BALANCE_30_FREQUENCY, 12),
                    Arguments.of("noise time", SbrHuffman.NOISE_TIME, 31),
                    Arguments.of("noise balance time", SbrHuffman.NOISE_BALANCE_TIME, 12),
                    Arguments.of("IID time", SbrHuffman.IID_TIME, 14),
                    Arguments.of("IID frequency", SbrHuffman.IID_FREQUENCY, 14),
                    Arguments.of("fine IID time", SbrHuffman.IID_FINE_TIME, 30),
                    Arguments.of("fine IID frequency", SbrHuffman.IID_FINE_FREQUENCY, 30),
                    Arguments.of("ICC time", SbrHuffman.ICC_TIME, 7),
                    Arguments.of("ICC frequency", SbrHuffman.ICC_FREQUENCY, 7));
        }

        /** Collects {value, codeword, length} for every leaf reachable from a node. */
        static void leaves(byte[] tree, int node, int code, int length, List<int[]> out) {
            for (var bit = 0; bit < 2; bit++) {
                var child = tree[2 * node + bit];
                if (child < 0) {
                    out.add(new int[]{child + 64, (code << 1) | bit, length + 1});
                } else {
                    leaves(tree, child, (code << 1) | bit, length + 1, out);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("codebooks")
        @DisplayName("are complete prefix codes over -limit..limit that decode every codeword")
        void complete(String name, byte[] tree, int limit) {
            var leaves = new ArrayList<int[]>();
            leaves(tree, 0, 0, 0, leaves);
            assertEquals(2 * limit + 1, leaves.size(), "one leaf per value");
            assertEquals(tree.length / 2 + 1, leaves.size(), "a full binary tree reaching every node");
            var values = new TreeSet<Integer>();
            var kraft = 0.0;
            for (var leaf : leaves) {
                values.add(leaf[0]);
                kraft += Math.pow(2, -leaf[2]);
                var reader = reader(leaf[1], leaf[2]);
                assertEquals(leaf[0], SbrHuffman.decode(tree, reader));
                assertEquals(leaf[2], reader.bitPosition(), "the codeword is consumed exactly");
            }
            assertEquals(-limit, values.first());
            assertEquals(limit, values.last());
            assertEquals(2 * limit + 1, values.size());
            assertEquals(1.0, kraft, 1e-12);
        }
    }

    @Nested
    @DisplayName("time grid")
    class Grid {
        @Test
        @DisplayName("splits a FIXFIX frame into equal envelopes with two noise floors")
        void fixFix() {
            var data = new SbrFrameData();
            data.readGrid(reader(0, 2, 2, 2, 1, 1), 16);
            assertEquals(4, data.envelopes);
            assertArrayEquals(new int[]{0, 4, 8, 12, 16}, Arrays.copyOf(data.borders, 5));
            assertArrayEquals(new int[]{1, 1, 1, 1}, Arrays.copyOf(data.resolution, 4));
            assertEquals(-1, data.transientEnvelope);
            assertEquals(2, data.noiseEnvelopes);
            assertArrayEquals(new int[]{0, 8, 16}, Arrays.copyOf(data.noiseBorders, 3));
        }

        @Test
        @DisplayName("resolves the leading and trailing borders and the transient of a VARVAR frame")
        void varVar() {
            // bs_var_bord_0 3, bs_var_bord_1 0, two leading and one trailing relative border,
            // pointer 2 of 3 bits, resolutions 0 0 1 0
            var data = new SbrFrameData();
            data.readGrid(reader(3, 2, 3, 2, 0, 2, 2, 2, 1, 2, 0, 2, 1, 2, 0, 2, 2, 3, 0, 1, 0, 1, 1, 1, 0, 1), 16);
            assertEquals(SbrFrameData.VARVAR, data.frameClass);
            assertEquals(4, data.envelopes);
            assertArrayEquals(new int[]{3, 5, 9, 14, 16}, Arrays.copyOf(data.borders, 5));
            assertArrayEquals(new int[]{0, 0, 1, 0}, Arrays.copyOf(data.resolution, 4));
            assertEquals(3, data.transientEnvelope);
            assertArrayEquals(new int[]{3, 14, 16}, Arrays.copyOf(data.noiseBorders, 3));
        }

        @Test
        @DisplayName("rejects eight FIXFIX envelopes, crossing borders and a transient beyond the frame")
        void invalid() {
            assertThrows(MediaException.Malformed.class, () -> new SbrFrameData().readGrid(reader(0, 2, 3, 2, 0, 1), 16));
            // VARVAR from slot 3 with three leading steps of 8 crosses the trailing border 16
            assertThrows(MediaException.Malformed.class, () -> new SbrFrameData().readGrid(
                    reader(3, 2, 3, 2, 0, 2, 3, 2, 0, 2, 3, 2, 3, 2, 3, 2, 0, 3, 0, 4), 16));
            // FIXVAR with four envelopes and transient pointer 7, beyond envelopes + 1
            assertThrows(MediaException.Malformed.class, () -> new SbrFrameData().readGrid(
                    reader(1, 2, 0, 2, 3, 2, 0, 2, 0, 2, 0, 2, 7, 3, 0, 4), 16));
        }
    }

    @Nested
    @DisplayName("frequency tables")
    class Frequencies {
        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000})
        @DisplayName("keep their invariants for every header the bitstream can carry")
        void exhaustive(int rate) {
            var tables = new SbrFrequencies();
            var header = new SbrHeader();
            var valid = 0;
            for (var start = 0; start < 16; start++) {
                for (var stop = 0; stop < 16; stop++) {
                    for (var scale = 0; scale < 4; scale++) {
                        for (var alter = 0; alter < 2; alter++) {
                            for (var noise = 0; noise < 4; noise++) {
                                for (var crossover = 0; crossover < 8; crossover++) {
                                    header.startFrequency = start;
                                    header.stopFrequency = stop;
                                    header.frequencyScale = scale;
                                    header.alterScale = alter;
                                    header.noiseBands = noise;
                                    header.crossoverBand = crossover;
                                    if (tables.update(header, rate)) {
                                        valid++;
                                        check(tables);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            assertTrue(valid > 0, "some headers are valid at " + rate + " Hz");
        }

        @Test
        @DisplayName("builds the six patches a linear table from subband 8 to 43 at 44.1 kHz needs, beyond the five an encoder may use")
        void sixPatches() {
            // the header of er_eld_sbr_gh_*_44 frames 199, 491 and 783: start 0, stop 8, linear scale
            var header = new SbrHeader();
            header.startFrequency = 0;
            header.stopFrequency = 8;
            header.frequencyScale = 0;
            header.alterScale = 0;
            header.noiseBands = 0;
            header.crossoverBand = 0;
            var tables = new SbrFrequencies();
            assertTrue(tables.update(header, 44100));
            assertEquals(8, tables.kx);
            assertEquals(35, tables.m);
            assertEquals(6, tables.patches);
            assertArrayEquals(new int[]{6, 6, 6, 6, 6, 5}, Arrays.copyOf(tables.patchSubbands, 6));
            assertArrayEquals(new int[]{2, 2, 2, 2, 2, 2}, Arrays.copyOf(tables.patchStart, 6));
            check(tables);
        }

        static void check(SbrFrequencies tables) {
            var kx = tables.kx;
            var end = kx + tables.m;
            assertTrue(kx >= 1 && kx <= 32 && end <= 64, "kx " + kx + " end " + end);
            increasing(tables.master, tables.masterBands);
            increasing(tables.high, tables.highBands);
            increasing(tables.low, tables.lowBands);
            increasing(tables.noise, tables.noiseBands);
            assertEquals(kx, tables.high[0]);
            assertEquals(end, tables.high[tables.highBands]);
            assertEquals(kx, tables.low[0]);
            assertEquals(end, tables.low[tables.lowBands]);
            assertEquals(kx, tables.noise[0]);
            assertEquals(end, tables.noise[tables.noiseBands]);
            assertTrue(tables.noiseBands >= 1 && tables.noiseBands <= SbrFrequencies.MAX_NOISE_BANDS);
            assertTrue(tables.patches >= 1, "at least one patch");
            var covered = 0;
            for (var p = 0; p < tables.patches; p++) {
                var from = tables.patchStart[p];
                assertTrue(from >= 1 && from + tables.patchSubbands[p] <= kx, "patch " + p + " copies from below kx");
                covered += tables.patchSubbands[p];
            }
            // a last patch narrower than three subbands is dropped, leaving the top of the high
            // band empty (ISO/IEC 14496-3 patch construction)
            assertTrue(covered <= tables.m && covered >= tables.m - 2, "the patches cover the high band: " + covered);
            for (var mode = 0; mode < 4; mode++) {
                tables.updateLimiter(mode);
                // narrow high bands can merge into no limiter band at all, which leaves the
                // gains unlimited
                if (tables.limiterBands > 0) {
                    increasing(tables.limiter, tables.limiterBands);
                }
                assertEquals(kx, tables.limiter[0]);
                // the merging may drop the top border when it is no patch border, as the
                // standard's limiter construction does
                var top = tables.limiter[tables.limiterBands];
                assertTrue(mode == 0 ? top == end : top <= end, "limiter top " + top);
            }
        }

        static void increasing(int[] table, int bands) {
            assertTrue(bands >= 1, "at least one band");
            for (var i = 0; i < bands; i++) {
                assertTrue(table[i] < table[i + 1], "band " + i + " of " + Arrays.toString(Arrays.copyOf(table, bands + 1)));
            }
        }
    }

    @Nested
    @DisplayName("kernels")
    class Kernels {
        private static final SbrKernel SCALAR = SbrKernels.scalar();

        private static final SbrKernel VECTOR = SbrKernels.vector();

        static float[] gaussian(Random random, int length) {
            var values = new float[length];
            for (var i = 0; i < length; i++) {
                values[i] = (float) random.nextGaussian();
            }
            return values;
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("transform the analysis bank bit for bit alike over whole and short frames")
        void analysis(int seed) {
            var random = new Random(seed);
            for (var slots : new int[]{32, 30}) {
                var a = new QmfAnalysis(SCALAR);
                var b = new QmfAnalysis(VECTOR);
                for (var frame = 0; frame < 4; frame++) {
                    var samples = gaussian(random, 32 * slots);
                    var aRe = new float[32 * slots + 64];
                    var aIm = new float[32 * slots + 64];
                    var bRe = new float[32 * slots + 64];
                    var bIm = new float[32 * slots + 64];
                    a.analyze(samples, slots, aRe, aIm, 64);
                    b.analyze(samples, slots, bRe, bIm, 64);
                    assertArrayEquals(aRe, bRe);
                    assertArrayEquals(aIm, bIm);
                }
            }
        }

        @ParameterizedTest(name = "{0} subbands")
        @ValueSource(ints = {64, 32})
        @DisplayName("transform the synthesis bank bit for bit alike over whole and short frames")
        void synthesis(int bands) {
            var random = new Random(bands);
            for (var slots : new int[]{32, 30}) {
                var a = new QmfSynthesis(bands, SCALAR);
                var b = new QmfSynthesis(bands, VECTOR);
                for (var frame = 0; frame < 4; frame++) {
                    var re = gaussian(random, 64 * slots);
                    var im = gaussian(random, 64 * slots);
                    var aOut = new float[bands * slots + 8];
                    var bOut = new float[bands * slots + 8];
                    a.synthesize(re, im, slots, aOut, 8);
                    b.synthesize(re, im, slots, bOut, 8);
                    assertArrayEquals(aOut, bOut);
                }
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("sum the covariance of the low band bit for bit alike over ragged subband counts")
        void covariance(int seed) {
            var random = new Random(seed);
            for (var bands = 2; bands <= 32; bands++) {
                var re = gaussian(random, 40 * 32);
                var im = gaussian(random, 40 * 32);
                var a = new double[256];
                var b = new double[256];
                SCALAR.covariance(re, im, bands, 38, a);
                VECTOR.covariance(re, im, bands, 38, b);
                assertArrayEquals(a, b, "bands " + bands);
            }
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("apply gains and noise bit for bit alike over ragged subband counts")
        void gains(int seed) {
            var random = new Random(seed);
            var noiseRe = gaussian(random, 576);
            var noiseIm = gaussian(random, 576);
            for (var count = 1; count <= 49; count++) {
                var re = gaussian(random, 128);
                var im = gaussian(random, 128);
                var gain = gaussian(random, 64);
                var noise = gaussian(random, 64);
                var offset = random.nextInt(64);
                var noiseOffset = random.nextInt(512);
                var a = re.clone();
                var b = im.clone();
                SCALAR.applyGains(a, b, offset, gain, noise, noiseRe, noiseIm, noiseOffset, count);
                VECTOR.applyGains(re, im, offset, gain, noise, noiseRe, noiseIm, noiseOffset, count);
                assertArrayEquals(a, re);
                assertArrayEquals(b, im);
            }
        }
    }
}
