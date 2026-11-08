package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the JPEG-LS stream writer: byte for byte against CharLS's encodings of the synthetic images of
 * {@code tools/testdata/jpegls}, and round trips through the decoder over what CharLS does not write (subsampled
 * components, restart intervals, an explicit {@code MAXVAL}, oversize dimensions, the point transform).
 */
@DisplayName("JPEG-LS writer")
class JpegLsWriterTest {
    static JpegLsWriter.Stream stream(int width, int height, int precision, int[][] factors, int[][] planes,
                                      int interleave, int near, int[] presets, int transform, int restart,
                                      int spiff, int maxval) {
        var count = planes.length;
        var h = new int[count];
        var v = new int[count];
        for (var c = 0; c < count; c++) {
            h[c] = factors == null ? 1 : factors[c][0];
            v[c] = factors == null ? 1 : factors[c][1];
        }
        return new JpegLsWriter.Stream(width, height, precision, h, v, planes, interleave, near, presets, transform,
                restart, spiff, maxval);
    }

    @Nested
    @DisplayName("CharLS vectors")
    class CharLs {
        static List<JpegLsVectors.CharLsCase> cases() {
            return JpegLsVectors.charls().stream().filter(vector -> !vector.mapped()).toList();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        @DisplayName("are written byte for byte from the synthetic image")
        void bytes(JpegLsVectors.CharLsCase vector) {
            var planes = JpegLsVectors.synthetic(vector.width(), vector.height(), vector.components(), vector.bits(),
                    vector.seed(), null);
            var spiff = vector.spiff().equals("-") ? -1 : Integer.parseInt(vector.spiff());
            var written = JpegLsWriter.encode(stream(vector.width(), vector.height(), vector.bits(), null, planes,
                    vector.interleave(), vector.near(), new int[]{vector.t1(), vector.t2(), vector.t3(),
                            vector.reset()}, vector.transform(), 0, spiff, vector.maxval()));
            assertArrayEquals(JpegLsVectors.resource("charls/" + vector.name() + ".jls"), written, vector.name());
        }
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        static Stream<Arguments> layouts() {
            var cases = new ArrayList<Arguments>();
            int[][][] factors = {null, {{2, 2}, {1, 1}, {1, 1}}, {{1, 2}, {2, 1}, {1, 1}}, {{4, 1}, {1, 4}, {2, 2}},
                    {{3, 1}, {1, 1}, {1, 3}}};
            for (var f = 0; f < factors.length; f++) {
                for (var interleave = 0; interleave <= 2; interleave++) {
                    if (factors[f] != null && interleave == 2) {
                        continue;
                    }
                    for (var near : new int[]{0, 2}) {
                        for (var restart : new int[]{0, 1, 3}) {
                            cases.add(Arguments.of(f, factors[f], interleave, near, restart));
                        }
                    }
                }
            }
            return cases.stream();
        }

        @ParameterizedTest(name = "factors {0}, ILV {2}, NEAR {3}, restart {4}")
        @MethodSource("layouts")
        @DisplayName("decode subsampled and interleaved scans with restart intervals within NEAR of the samples")
        void layouts(int index, int[][] factors, int interleave, int near, int restart) {
            var planes = JpegLsVectors.synthetic(29, 23, 3, 8, 77 + index, factors);
            var jls = JpegLsWriter.encode(stream(29, 23, 8, factors, planes, interleave, near, new int[4], 0, restart,
                    -1, 0));
            var decoded = JpegLsVectors.decode(jls);
            for (var c = 0; c < 3; c++) {
                (near == 0 ? Tolerance.exact() : Tolerance.maxAbs(near)).assertWithin("component " + c, planes[c],
                        decoded[c]);
            }
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {2, 3, 4, 6, 7, 9, 11, 13, 14, 15, 16})
        @DisplayName("code every precision losslessly and near-losslessly")
        void precisions(int bits) {
            var planes = JpegLsVectors.synthetic(31, 17, 1, bits, bits, null);
            for (var near : new int[]{0, 1}) {
                var jls = JpegLsWriter.encode(stream(31, 17, bits, null, planes, 0, near, new int[4], 0, 0, -1, 0));
                (near == 0 ? Tolerance.exact() : Tolerance.maxAbs(near)).assertWithin(bits + " bits", planes[0],
                        JpegLsVectors.decode(jls)[0]);
            }
        }

        @ParameterizedTest(name = "MAXVAL {0}")
        @ValueSource(ints = {1, 2, 100, 200, 254})
        @DisplayName("code samples bounded by an explicit MAXVAL")
        void maxval(int maxval) {
            var random = new Random(maxval);
            var plane = new int[37 * 21];
            for (var i = 0; i < plane.length; i++) {
                plane[i] = i % 7 == 0 ? random.nextInt(maxval + 1) : (i / 37) * maxval / 21;
            }
            for (var near : new int[]{0, maxval / 4}) {
                var jls = JpegLsWriter.encode(stream(37, 21, 8, null, new int[][]{plane}, 0, near, new int[4], 0, 0,
                        -1, maxval));
                var decoded = JpegLsVectors.decode(jls)[0];
                (near == 0 ? Tolerance.exact() : Tolerance.maxAbs(near)).assertWithin("MAXVAL " + maxval, plane,
                        decoded);
                for (var value : decoded) {
                    assertTrue(value <= maxval, "sample " + value + " above MAXVAL");
                }
            }
        }

        @Test
        @DisplayName("define a width above 65535 in the oversize-dimension segment, and a long restart interval in 4 bytes")
        void oversize() {
            var plane = JpegLsVectors.synthetic(65541, 2, 1, 8, 5, null)[0];
            var jls = JpegLsWriter.encode(stream(65541, 2, 8, null, new int[][]{plane}, 0, 0, new int[4], 0, 0, -1, 0));
            var limits = MediaLimits.builder().maxDimension(100_000).build();
            assertArrayEquals(plane, JpegLsVectors.decode(jls, limits)[0]);
            var info = JpegDecoder.probe(MediaSource.of(jls));
            assertEquals(65541, info.width());
            assertEquals(2, info.height());
            var tall = JpegLsVectors.synthetic(2, 70000, 1, 8, 6, null)[0];
            var restarted = JpegLsWriter.encode(stream(2, 70000, 8, null, new int[][]{tall}, 0, 0, new int[4], 0,
                    66000, -1, 0));
            assertArrayEquals(tall, JpegLsVectors.decode(restarted, limits)[0]);
            assertEquals(70000, JpegDecoder.probe(MediaSource.of(restarted)).height());
        }

        @ParameterizedTest(name = "Pt {0}")
        @ValueSource(ints = {1, 3, 7})
        @DisplayName("shift the decoded samples left by the point transform of the scan header")
        void pointTransform(int pt) {
            var plane = JpegLsVectors.synthetic(19, 13, 1, 8 - pt, 9, null)[0];
            var jls = JpegLsWriter.encode(stream(19, 13, 8, null, new int[][]{plane}, 0, 0, new int[4], 0, 0, -1, 0));
            var sos = indexOf(jls, 0xDA);
            jls[sos + 2 + 2 + 1 + 2 + 2] = (byte) pt;
            var decoded = JpegLsVectors.decode(jls)[0];
            for (var i = 0; i < plane.length; i++) {
                assertEquals(plane[i] << pt, decoded[i]);
            }
        }

        @Test
        @DisplayName("reject a point transform combined with a mapping table, and one not below the precision")
        void pointTransformChecks() {
            var plane = JpegLsVectors.synthetic(7, 5, 1, 8, 9, null)[0];
            var jls = JpegLsWriter.encode(stream(7, 5, 8, null, new int[][]{plane}, 0, 0, new int[4], 0, 0, -1, 0));
            var sos = indexOf(jls, 0xDA);
            var bad = jls.clone();
            bad[sos + 2 + 2 + 1 + 2 + 2] = 8;
            org.junit.jupiter.api.Assertions.assertThrows(MediaException.Malformed.class,
                    () -> new JpegLsDecoder(bad, 0, bad.length, MediaLimits.DEFAULT));
        }
    }

    static int indexOf(byte[] data, int marker) {
        for (var i = 0; i + 1 < data.length; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == marker) {
                return i;
            }
        }
        throw new IllegalArgumentException("no marker " + Integer.toHexString(marker));
    }
}
