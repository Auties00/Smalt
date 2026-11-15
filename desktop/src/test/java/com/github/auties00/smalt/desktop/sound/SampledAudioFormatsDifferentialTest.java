package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the G.711, odd-sized integer and half-precision formats of {@link SampledAudio} against the JDK's
 * own Java Sound converters (in process) and FFmpeg's G.711 coder, used as black-box oracles.
 */
@DisplayName("SampledAudio G.711, odd sizes and half floats")
class SampledAudioFormatsDifferentialTest {
    private static final Tolerance EXACT = Tolerance.exact();

    private static AudioFormat law(AudioFormat.Encoding encoding) {
        return new AudioFormat(encoding, 8000, 8, 1, 1, 8000, false);
    }

    private static final AudioFormat PCM16 = new AudioFormat(8000, 16, 1, true, false);

    private static byte[] allCodes() {
        var codes = new byte[256];
        for (var code = 0; code < 256; code++) {
            codes[code] = (byte) code;
        }
        return codes;
    }

    private static byte[] allSamples() {
        var bytes = new byte[65536 * 2];
        for (var value = -32768; value < 32768; value++) {
            var at = (value + 32768) * 2;
            bytes[at] = (byte) value;
            bytes[at + 1] = (byte) (value >> 8);
        }
        return bytes;
    }

    private static int[] shorts(byte[] bytes) {
        var result = new int[bytes.length / 2];
        for (var i = 0; i < result.length; i++) {
            result[i] = (short) ((bytes[2 * i] & 0xFF) | bytes[2 * i + 1] << 8);
        }
        return result;
    }

    private static int[] decoded(AudioFormat.Encoding encoding) {
        var format = law(encoding);
        var planes = new float[1][256];
        SampledAudio.read(allCodes(), 0, 256, format, planes, 0);
        var result = new int[256];
        for (var i = 0; i < 256; i++) {
            result[i] = Math.round(planes[0][i] * 32768);
        }
        return result;
    }

    private static int[] encoded(AudioFormat.Encoding encoding) {
        var samples = allSamples();
        var planes = new float[1][65536];
        SampledAudio.read(samples, 0, 65536, PCM16, planes, 0);
        var codes = new byte[65536];
        SampledAudio.write(planes, 0, 65536, law(encoding), codes, 0);
        var result = new int[65536];
        for (var i = 0; i < codes.length; i++) {
            result[i] = codes[i] & 0xFF;
        }
        return result;
    }

    private static String ffmpegFormat(AudioFormat.Encoding encoding) {
        return AudioFormat.Encoding.ULAW.equals(encoding) ? "mulaw" : "alaw";
    }

    @Nested
    @DisplayName("G.711")
    class G711 {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ULAW", "ALAW"})
        @DisplayName("every code decodes to the 16-bit value of the JDK's G.711 converter")
        void decodeLikeJdk(String name) throws IOException {
            var encoding = new AudioFormat.Encoding(name);
            var stream = new AudioInputStream(new ByteArrayInputStream(allCodes()), law(encoding), 256);
            var jdk = shorts(AudioSystem.getAudioInputStream(PCM16, stream).readAllBytes());
            EXACT.assertWithin(name + " decoding", jdk, decoded(encoding));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ULAW", "ALAW"})
        @DisplayName("every code decodes to the 16-bit value of FFmpeg's G.711 decoder")
        void decodeLikeFfmpeg(String name) {
            var encoding = new AudioFormat.Encoding(name);
            Oracle.assume(Oracle.Tool.FFMPEG);
            var output = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), allCodes(), List.of("-v", "error",
                    "-f", ffmpegFormat(encoding), "-ar", "8000", "-ac", "1", "-i", "-", "-f", "s16le", "-"));
            EXACT.assertWithin(name + " decoding", shorts(output), decoded(encoding));
        }

        // FFmpeg's G.711 encoders pick the nearest reconstruction level, which differs from the decision values of
        // G.711 where two segments meet, so the encoders are checked against those decision values instead: every
        // code's interval is centered on its reconstruction value and is one step of its segment wide.
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ULAW", "ALAW"})
        @DisplayName("non-negative samples encode to the code whose G.711 interval holds them, negative ones as their ones' complement")
        void encodeByDecisionValues(String name) {
            var encoding = new AudioFormat.Encoding(name);
            var mine = encoded(encoding);
            var levels = decoded(encoding);
            var mulaw = AudioFormat.Encoding.ULAW.equals(encoding);
            var top = mulaw ? 0x80 : 0xAA;
            for (var value = 0; value < 32768; value++) {
                var code = mine[value + 32768];
                var level = levels[code];
                // the segment of the code sets the step: mu-law segment s steps 8 << s, A-law segments 0 and 1 step
                // 16 and segment s above them 8 << s
                var segment = mulaw ? (~code & 0x70) >> 4 : ((code ^ 0xD5) & 0x70) >> 4;
                var step = mulaw ? 8 << segment : segment < 2 ? 16 : 8 << segment;
                assertTrue(level >= 0, name + " sample " + value + " coded as a negative level");
                if (code == top) {
                    assertTrue(value >= level - step / 2, name + " sample " + value + " below the top interval");
                } else if (mulaw && level == 0) {
                    assertTrue(value < step / 2, name + " sample " + value + " coded as zero");
                } else {
                    assertTrue(value >= level - step / 2 && value < level + step / 2,
                            name + " sample " + value + " coded as " + code + " of level " + level + " and step " + step);
                }
            }
            for (var value = -32768; value < 0; value++) {
                var complement = mine[~value + 32768];
                var expected = mulaw ? complement & 0x7F : complement ^ 0x80;
                assertEquals(expected, mine[value + 32768], "sample " + value);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ULAW", "ALAW"})
        @DisplayName("decoding a code and encoding it again gives the code, but the mu-law negative zero")
        void roundTrip(String name) {
            var encoding = new AudioFormat.Encoding(name);
            var planes = new float[1][256];
            SampledAudio.read(allCodes(), 0, 256, law(encoding), planes, 0);
            var codes = new byte[256];
            SampledAudio.write(planes, 0, 256, law(encoding), codes, 0);
            for (var code = 0; code < 256; code++) {
                var expected = AudioFormat.Encoding.ULAW.equals(encoding) && code == 0x7F ? 0xFF : code;
                assertEquals(expected, codes[code] & 0xFF, "code " + code);
            }
        }

        @Test
        @DisplayName("the encoders clip at full scale and code silence as the zero codes")
        void boundaries() {
            var planes = new float[][]{{0f, 1f, -1f, 2f, -2f, Float.NaN}};
            var mu = new byte[6];
            SampledAudio.write(planes, 0, 6, law(AudioFormat.Encoding.ULAW), mu, 0);
            assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0x80, 0x00, (byte) 0x80, 0x00, (byte) 0xFF}, mu);
            var a = new byte[6];
            SampledAudio.write(planes, 0, 6, law(AudioFormat.Encoding.ALAW), a, 0);
            assertArrayEquals(new byte[]{(byte) 0xD5, (byte) 0xAA, 0x2A, (byte) 0xAA, 0x2A, (byte) 0xD5}, a);
        }
    }

    @Nested
    @DisplayName("odd sample sizes")
    class OddSizes {
        @ParameterizedTest(name = "{0} bits, big-endian {1}")
        @CsvSource({"12, false", "12, true", "20, false", "20, true", "10, false", "28, true"})
        @DisplayName("samples fill the most significant bits and read back as the JDK converts them to 16 or 32 bits")
        void likeJdk(int bits, boolean bigEndian) throws IOException {
            // the JDK converts samples of more than 16 bits through floats, which rounds them by a unit of the
            // 24-bit mantissa; a misjustified sample would be off by a factor of 2 or more
            var tolerance = bits <= 16 ? EXACT : Tolerance.maxAbs(0x1p-21);
            var container = (bits + 7) / 8;
            var format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 8000, bits, 2, 2 * container, 8000,
                    bigEndian);
            var random = new Random(bits);
            var frames = 500;
            var planes = new float[2][frames];
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < frames; i++) {
                    planes[c][i] = (random.nextInt(1 << bits) - (1 << (bits - 1))) * (float) Math.scalb(1.0, 1 - bits);
                }
            }
            var bytes = new byte[frames * 2 * container];
            SampledAudio.write(planes, 0, frames, format, bytes, 0);
            var padding = container * 8 - bits;
            for (var i = 0; i < frames * 2; i++) {
                var low = bytes[i * container + (bigEndian ? container - 1 : 0)] & 0xFF;
                assertEquals(0, low & ((1 << padding) - 1), "padding bits of sample " + i);
            }
            var back = new float[2][frames];
            SampledAudio.read(bytes, 0, frames, format, back, 0);
            for (var c = 0; c < 2; c++) {
                assertArrayEquals(planes[c], back[c], "channel " + c);
            }
            var wide = container <= 2 ? 16 : 32;
            var target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 8000, wide, 2, 2 * wide / 8, 8000, false);
            var stream = new AudioInputStream(new ByteArrayInputStream(bytes), format, frames);
            var jdk = AudioSystem.getAudioInputStream(target, stream).readAllBytes();
            var expected = new float[2][frames];
            SampledAudio.read(jdk, 0, frames, target, expected, 0);
            for (var c = 0; c < 2; c++) {
                tolerance.assertPcm("channel " + c, expected[c], back[c]);
            }
        }

        @Test
        @DisplayName("integer readers give the signed samples of every supported size")
        void integers() {
            for (var bits = 8; bits <= 32; bits++) {
                for (var encoding : List.of(AudioFormat.Encoding.PCM_SIGNED, AudioFormat.Encoding.PCM_UNSIGNED)) {
                    var format = SampledAudio.format(encoding, 8000, bits, ChannelLayout.MONO, bits % 2 == 0);
                    var limit = 1L << (bits - 1);
                    var values = new long[]{-limit, -1, 0, 1, limit - 1};
                    var planes = new float[1][values.length];
                    for (var i = 0; i < values.length; i++) {
                        planes[0][i] = (float) (values[i] / (double) limit);
                    }
                    var bytes = new byte[values.length * SampledAudio.frameSize(format)];
                    SampledAudio.write(planes, 0, values.length, format, bytes, 0);
                    var integers = new int[1][values.length];
                    SampledAudio.readIntegers(bytes, 0, values.length, format, integers, 0);
                    for (var i = 0; i < values.length; i++) {
                        // floats keep 24 significant bits, so the largest positive sample of more bits rounds up
                        // and clamps to the same value
                        assertEquals(values[i], integers[0][i], bits + "-bit " + encoding + " value " + i);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("half-precision floats")
    class Half {
        @Test
        @DisplayName("samples are IEEE 754 binary16, rounded to nearest even, overflowing to infinity")
        void values() {
            var format = new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 8000, 16, 1, 2, 8000, true);
            var planes = new float[][]{{1f, -2f, 0.5f, 65504f, 1e6f, 1.0009765625f, 1.00048828125f, Float.NaN}};
            var bytes = new byte[16];
            SampledAudio.write(planes, 0, 8, format, bytes, 0);
            int[] expected = {0x3C00, 0xC000, 0x3800, 0x7BFF, 0x7C00, 0x3C01, 0x3C00};
            for (var i = 0; i < expected.length; i++) {
                assertEquals(expected[i], (bytes[2 * i] & 0xFF) << 8 | bytes[2 * i + 1] & 0xFF, "sample " + i);
            }
            var back = new float[1][8];
            SampledAudio.read(bytes, 0, 8, format, back, 0);
            assertEquals(1f, back[0][0]);
            assertEquals(65504f, back[0][3]);
            assertEquals(Float.POSITIVE_INFINITY, back[0][4]);
            assertTrue(Float.isNaN(back[0][7]));
        }

        @Test
        @DisplayName("every half-precision value reads back as itself")
        void allValues() {
            var format = new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 8000, 16, 1, 2, 8000, false);
            var bytes = new byte[65536 * 2];
            for (var bits = 0; bits < 65536; bits++) {
                bytes[2 * bits] = (byte) bits;
                bytes[2 * bits + 1] = (byte) (bits >> 8);
            }
            var planes = new float[1][65536];
            SampledAudio.read(bytes, 0, 65536, format, planes, 0);
            var again = new byte[bytes.length];
            SampledAudio.write(planes, 0, 65536, format, again, 0);
            for (var bits = 0; bits < 65536; bits++) {
                var value = Float.float16ToFloat((short) bits);
                if (!Float.isNaN(value)) {
                    assertEquals(bits, (again[2 * bits] & 0xFF) | (again[2 * bits + 1] & 0xFF) << 8, "half " + bits);
                }
            }
        }
    }
}
