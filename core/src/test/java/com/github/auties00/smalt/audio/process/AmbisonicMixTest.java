package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers the spherical harmonics behind the ambisonic rules of {@link ChannelMixer} (against closed forms, their
 * orthonormality and the Furse-Malham maxima) and the matrices those rules give, through {@link
 * ChannelMixer#matrix()} and {@link ChannelMixer#process(float[][], float[][], int)}.
 */
@DisplayName("ChannelMixer with ambisonic layouts")
class AmbisonicMixTest {
    private static final double EPSILON = 1e-6;

    private static final ChannelLayout HEAD_LOCKED = ChannelLayout.ofAmbisonics(Ambisonics.of(1),
            List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));

    private static float[][] matrix(ChannelLayout input, ChannelLayout output) {
        return new ChannelMixer(input, output).matrix();
    }

    private static void assertRow(double[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        for (var i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-6, message + " coefficient " + i);
        }
    }

    /**
     * Returns the closed-form SN3D harmonics up to order 3 (the AmbiX table).
     */
    private static double[] closedForm(double a, double e) {
        double s = Math.sin(e), c = Math.cos(e);
        double r3 = Math.sqrt(3) / 2;
        return new double[]{
                1,
                Math.sin(a) * c, s, Math.cos(a) * c,
                r3 * Math.sin(2 * a) * c * c, r3 * Math.sin(a) * Math.sin(2 * e), (3 * s * s - 1) / 2,
                r3 * Math.cos(a) * Math.sin(2 * e), r3 * Math.cos(2 * a) * c * c,
                Math.sqrt(5.0 / 8) * Math.sin(3 * a) * c * c * c, Math.sqrt(15) / 2 * Math.sin(2 * a) * s * c * c,
                Math.sqrt(3.0 / 8) * Math.sin(a) * c * (5 * s * s - 1), s * (5 * s * s - 3) / 2,
                Math.sqrt(3.0 / 8) * Math.cos(a) * c * (5 * s * s - 1), Math.sqrt(15) / 2 * Math.cos(2 * a) * s * c * c,
                Math.sqrt(5.0 / 8) * Math.cos(3 * a) * c * c * c
        };
    }

    @Nested
    @DisplayName("spherical harmonics")
    class Harmonics {
        @Test
        @DisplayName("match the closed-form SN3D harmonics up to order 3")
        void closedForms() {
            var random = new Random(8486);
            for (var trial = 0; trial < 500; trial++) {
                var azimuth = (random.nextDouble() * 2 - 1) * Math.PI;
                var elevation = (random.nextDouble() - 0.5) * Math.PI;
                var expected = closedForm(azimuth, elevation);
                var actual = SphericalHarmonics.sn3d(3, azimuth, elevation);
                for (var acn = 0; acn < 16; acn++) {
                    assertEquals(expected[acn], actual[acn], 1e-12, "ACN " + acn + " at " + azimuth + ", " + elevation);
                }
            }
        }

        @Test
        @DisplayName("are orthonormal over the sphere in N3D up to order 6")
        void orthonormal() {
            var order = 6;
            var count = (order + 1) * (order + 1);
            var gram = new double[count][count];
            var azimuths = 64;
            var heights = 4000;
            for (var h = 0; h < heights; h++) {
                // midpoint rule in sin(E), whose measure is uniform on the sphere
                var x = -1 + (h + 0.5) * 2.0 / heights;
                var elevation = Math.asin(x);
                for (var k = 0; k < azimuths; k++) {
                    var values = SphericalHarmonics.sn3d(order, 2 * Math.PI * k / azimuths, elevation);
                    for (var i = 0; i < count; i++) {
                        var vi = values[i] / SphericalHarmonics.toSn3d(Ambisonics.Normalization.N3D, i);
                        for (var j = 0; j <= i; j++) {
                            gram[i][j] += vi * values[j] / SphericalHarmonics.toSn3d(Ambisonics.Normalization.N3D, j);
                        }
                    }
                }
            }
            for (var i = 0; i < count; i++) {
                for (var j = 0; j <= i; j++) {
                    assertEquals(i == j ? 1 : 0, gram[i][j] / (heights * azimuths), 1e-4, "<" + i + ", " + j + ">");
                }
            }
        }

        @Test
        @DisplayName("scale Furse-Malham components by the maximum of each SN3D harmonic, W by sqrt(2)")
        void furseMalham() {
            var maxima = new double[16];
            for (var e = 0; e <= 720; e++) {
                for (var a = 0; a < 1440; a++) {
                    var values = SphericalHarmonics.sn3d(3, Math.toRadians(a / 4.0), Math.toRadians(e / 4.0 - 90));
                    for (var acn = 0; acn < 16; acn++) {
                        maxima[acn] = Math.max(maxima[acn], Math.abs(values[acn]));
                    }
                }
            }
            assertEquals(Math.sqrt(2), SphericalHarmonics.toSn3d(Ambisonics.Normalization.FUMA, 0), EPSILON);
            for (var acn = 1; acn < 16; acn++) {
                assertEquals(maxima[acn], SphericalHarmonics.toSn3d(Ambisonics.Normalization.FUMA, acn), 1e-4,
                        "ACN " + acn);
            }
        }

        @ParameterizedTest
        @EnumSource(Speaker.class)
        @DisplayName("give every positioned speaker a direction")
        void directions(Speaker speaker) {
            var direction = SphericalHarmonics.direction(speaker);
            var none = speaker == Speaker.LOW_FREQUENCY || speaker == Speaker.LOW_FREQUENCY_2
                    || speaker == Speaker.UNKNOWN;
            assertEquals(none, direction == null, speaker.toString());
        }
    }

    @Nested
    @DisplayName("ambisonics to loudspeakers")
    class Decoding {
        @ParameterizedTest
        @ValueSource(ints = {0, 30, 45, 90, 135, 180, -60, -90, -150})
        @DisplayName("decodes a horizontal plane wave to stereo as cardioids aimed left and right")
        void planeWave(int degrees) {
            var azimuth = Math.toRadians(degrees);
            var frames = 480;
            var signal = new float[frames];
            for (var t = 0; t < frames; t++) {
                signal[t] = (float) Math.sin(2 * Math.PI * 1000 * t / 48000.0);
            }
            for (var normalization : Ambisonics.Normalization.values()) {
                var layout = ChannelLayout.ofAmbisonics(Ambisonics.of(1, normalization));
                var harmonics = SphericalHarmonics.sn3d(1, azimuth, 0);
                var planes = new float[4][frames];
                for (var acn = 0; acn < 4; acn++) {
                    var gain = harmonics[acn] / SphericalHarmonics.toSn3d(normalization, acn);
                    for (var t = 0; t < frames; t++) {
                        planes[acn][t] = (float) (gain * signal[t]);
                    }
                }
                var out = new float[2][frames];
                new ChannelMixer(layout, ChannelLayout.STEREO).process(planes, out, frames);
                var left = (1 + Math.sin(azimuth)) / 2;
                var right = (1 - Math.sin(azimuth)) / 2;
                for (var t = 0; t < frames; t++) {
                    assertEquals(left * signal[t], out[0][t], 1e-5, normalization + " left at " + t);
                    assertEquals(right * signal[t], out[1][t], 1e-5, normalization + " right at " + t);
                }
            }
        }

        @Test
        @DisplayName("follows RFC 8486 section 4 for the head-locked pair, and mono is W")
        void rfc8486() {
            var stereo = matrix(HEAD_LOCKED, ChannelLayout.STEREO);
            assertRow(new double[]{0.25, 0.25, 0, 0, 0.5, 0}, stereo[0], "left");
            assertRow(new double[]{0.25, -0.25, 0, 0, 0, 0.5}, stereo[1], "right");
            var mono = matrix(HEAD_LOCKED, ChannelLayout.MONO);
            assertRow(new double[]{0.25, 0, 0, 0, 0.25, 0.25}, mono[0], "mono with head-locked pair");
            assertRow(new double[]{1, 0, 0, 0}, matrix(ChannelLayout.ofAmbisonics(1), ChannelLayout.MONO)[0], "mono");
            assertRow(new double[]{Math.sqrt(2), 0, 0, 0},
                    matrix(ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)), ChannelLayout.MONO)[0], "FuMa mono");
            var third = matrix(ChannelLayout.ofAmbisonics(3), ChannelLayout.STEREO);
            for (var acn = 4; acn < 16; acn++) {
                assertEquals(0, third[0][acn], "higher orders are not used");
            }
            assertRow(new double[]{0.5, 0.5, 0, 0}, matrix(ChannelLayout.ofAmbisonics(1), ChannelLayout.STEREO)[0],
                    "first order left");
        }

        @Test
        @DisplayName("aims a first-order cardioid at each speaker of a surround layout")
        void surround() {
            var decode = matrix(ChannelLayout.ofAmbisonics(1), ChannelLayout.SURROUND_5_1);
            for (var o = 0; o < 6; o++) {
                var speaker = ChannelLayout.SURROUND_5_1.speakers().get(o);
                var direction = SphericalHarmonics.direction(speaker);
                if (direction == null) {
                    assertRow(new double[4], decode[o], "silent " + speaker);
                    continue;
                }
                var a = Math.toRadians(direction[0]);
                var e = Math.toRadians(direction[1]);
                assertRow(new double[]{0.5, 0.5 * Math.sin(a) * Math.cos(e), 0.5 * Math.sin(e),
                        0.5 * Math.cos(a) * Math.cos(e)}, decode[o], speaker.toString());
            }
            // a plane wave from the front reaches the center at full gain
            var front = SphericalHarmonics.sn3d(1, 0, 0);
            var center = 0.0;
            for (var acn = 0; acn < 4; acn++) {
                center += decode[2][acn] * front[acn];
            }
            assertEquals(1, center, EPSILON);
            var height = matrix(ChannelLayout.ofAmbisonics(1), new ChannelLayout(List.of(Speaker.TOP_CENTER,
                    Speaker.BOTTOM_FRONT_CENTER)));
            assertRow(new double[]{0.5, 0, 0.5, 0}, height[0], "top center");
            assertEquals(-0.25, height[1][2], EPSILON, "bottom front center Z");
        }

        @Test
        @DisplayName("maps by index into channels without positions and ignores the Lt/Rt downmix")
        void special() {
            var unknown = new ChannelLayout(Collections.nCopies(4, Speaker.UNKNOWN));
            var identity = matrix(ChannelLayout.ofAmbisonics(1), unknown);
            for (var o = 0; o < 4; o++) {
                for (var i = 0; i < 4; i++) {
                    assertEquals(o == i ? 1 : 0, identity[o][i]);
                }
            }
            var ltrt = new ChannelMixer(HEAD_LOCKED, ChannelLayout.STEREO, ChannelMixerOptions.builder()
                    .stereoDownmix(ChannelMixerOptions.StereoDownmix.LT_RT).build()).matrix();
            assertArrayEquals(matrix(HEAD_LOCKED, ChannelLayout.STEREO), ltrt);
            var normalized = new ChannelMixer(HEAD_LOCKED, ChannelLayout.STEREO, true).matrix();
            assertRow(new double[]{0.25, 0.25, 0, 0, 0.5, 0}, normalized[0], "rows summing to one stay");
        }
    }

    @Nested
    @DisplayName("into ambisonics")
    class Encoding {
        @Test
        @DisplayName("converts between orders, normalizations and channel orders exactly")
        void conversions() {
            var acn3 = ChannelLayout.ofAmbisonics(3);
            var fuma = ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(16));
            var there = matrix(acn3, fuma);
            var back = matrix(fuma, acn3);
            for (var i = 0; i < 16; i++) {
                for (var j = 0; j < 16; j++) {
                    var product = 0.0;
                    for (var k = 0; k < 16; k++) {
                        product += back[i][k] * there[k][j];
                    }
                    assertEquals(i == j ? 1 : 0, product, 1e-6, "round trip " + i + ", " + j);
                }
            }
            // FuMa W is SN3D W / sqrt(2) and FuMa channel 1 is X, ACN 3
            assertEquals(1 / Math.sqrt(2), there[0][0], 1e-6);
            assertEquals(1, there[1][3], 1e-6);
            var n3d = matrix(ChannelLayout.ofAmbisonics(2), ChannelLayout.ofAmbisonics(Ambisonics.of(2,
                    Ambisonics.Normalization.N3D)));
            for (var acn = 0; acn < 9; acn++) {
                assertEquals(Math.sqrt(2 * Ambisonics.degree(acn) + 1), n3d[acn][acn], 1e-6, "N3D " + acn);
            }
            var truncated = matrix(acn3, ChannelLayout.ofAmbisonics(1));
            for (var o = 0; o < 4; o++) {
                for (var i = 0; i < 16; i++) {
                    assertEquals(o == i ? 1 : 0, truncated[o][i]);
                }
            }
            var extended = matrix(ChannelLayout.ofAmbisonics(1), ChannelLayout.ofAmbisonics(2));
            for (var o = 4; o < 9; o++) {
                assertRow(new double[4], extended[o], "silent component " + o);
            }
        }

        @Test
        @DisplayName("encodes speakers as plane waves from their nominal direction")
        void speakers() {
            var encode = matrix(ChannelLayout.SURROUND_5_1, ChannelLayout.ofAmbisonics(3));
            for (var i = 0; i < 6; i++) {
                var speaker = ChannelLayout.SURROUND_5_1.speakers().get(i);
                var direction = SphericalHarmonics.direction(speaker);
                if (direction == null) {
                    for (var o = 0; o < 16; o++) {
                        assertEquals(0, encode[o][i], "low-frequency channel dropped");
                    }
                    continue;
                }
                var expected = closedForm(Math.toRadians(direction[0]), Math.toRadians(direction[1]));
                for (var o = 0; o < 16; o++) {
                    assertEquals(expected[o], encode[o][i], 1e-6, speaker + " ACN " + o);
                }
            }
            var withLfe = new ChannelMixer(ChannelLayout.SURROUND_5_1, ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)),
                    ChannelMixerOptions.builder().lfeMixLevel(1).build()).matrix();
            assertEquals(1 / Math.sqrt(2), withLfe[0][3], 1e-6, "low-frequency channel into W");
            var side = matrix(new ChannelLayout(List.of(Speaker.SIDE_LEFT)), ChannelLayout.ofAmbisonics(1));
            var stereo = matrix(ChannelLayout.ofAmbisonics(1), ChannelLayout.STEREO);
            assertEquals(1, stereo[0][0] * side[0][0] + stereo[0][1] * side[1][0], 1e-6, "left side to left");
            assertEquals(0, stereo[1][0] * side[0][0] + stereo[1][1] * side[1][0], 1e-6, "left side not right");
        }

        @Test
        @DisplayName("keeps head-locked speakers the output has and maps unknown channels by index")
        void headLocked() {
            var into = matrix(ChannelLayout.STEREO, HEAD_LOCKED);
            assertEquals(1, into[4][0]);
            assertEquals(1, into[5][1]);
            assertEquals(0, into[0][0]);
            var copy = matrix(HEAD_LOCKED, ChannelLayout.ofAmbisonics(Ambisonics.of(1),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT)));
            for (var o = 0; o < 6; o++) {
                assertEquals(1, copy[o][o]);
            }
            var dropped = matrix(HEAD_LOCKED, ChannelLayout.ofAmbisonics(1));
            var left = SphericalHarmonics.sn3d(1, Math.toRadians(30), 0);
            for (var acn = 0; acn < 4; acn++) {
                assertEquals(left[acn], dropped[acn][4], 1e-6, "front left encoded, ACN " + acn);
            }
            var unknown = matrix(new ChannelLayout(Collections.nCopies(4, Speaker.UNKNOWN)), ChannelLayout.ofAmbisonics(1));
            for (var o = 0; o < 4; o++) {
                assertEquals(1, unknown[o][o]);
            }
            var mixed = new ChannelLayout(List.of(Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN,
                    Speaker.FRONT_CENTER), Optional.of(Ambisonics.of(1)));
            assertNotNull(new ChannelMixer(mixed, ChannelLayout.SURROUND_7_1).matrix());
        }
    }
}
