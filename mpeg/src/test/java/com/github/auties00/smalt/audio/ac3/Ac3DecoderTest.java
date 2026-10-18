package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.ac3.internal.DitherClearing;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes the pinned FATE AC-3 and E-AC-3 streams and compares them with ffmpeg used as a black
 * box. Dither makes zero-bit mantissas random in every decoder, so the oracle comparisons run on
 * copies of the streams whose dither flags {@link DitherClearing} cleared (CRCs recomputed); the
 * remaining differences are float rounding, the random noise of spectral extension (which ffmpeg
 * and Smalt generate differently), and short blocks of every channel after the first, where
 * ffmpeg 7.1 overlaps the previous channel's delay line (see the report). The SNR floors below sit
 * a few dB under the measured values listed with each case.
 */
class Ac3DecoderTest {
    @TempDir
    Path directory;

    /**
     * The sample, the channels it decodes to, the SNR floor against ffmpeg in full range, and the
     * largest absolute difference allowed. Measured: 44.9, 27.0 (near-silent content, errors
     * under 1e-5), 36.6, 32.1 (spectral extension and AHT noise), 35.7, 91.6, 19.0 (near silent),
     * 91.5 dB.
     */
    static Stream<Arguments> oracleCases() {
        var samples = Ac3TestSupport.SAMPLES;
        return Stream.of(
                Arguments.of(samples.get(0), 2, 40.0, 0.01),
                Arguments.of(samples.get(1), 6, 22.0, 1e-4),
                Arguments.of(samples.get(2), 4, 32.0, 0.15),
                Arguments.of(samples.get(3), 6, 28.0, 0.05),
                Arguments.of(samples.get(4), 2, 32.0, 0.01),
                Arguments.of(samples.get(5), 2, 85.0, 1e-4),
                Arguments.of(samples.get(6), 6, 14.0, 1e-4),
                Arguments.of(samples.get(7), 8, 85.0, 1e-4));
    }

    static List<Ac3TestSupport.Sample> samples() {
        return Ac3TestSupport.SAMPLES;
    }

    private Path write(String name, byte[] data) {
        try {
            var path = directory.resolve(name);
            Files.write(path, data);
            return path;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static boolean enhanced(byte[] stream) {
        return ((stream[5] & 0xFF) >> 3) > 10;
    }

    private Path cleared(Ac3TestSupport.Sample sample, byte[] stream) {
        return write("cleared" + (enhanced(stream) ? ".eac3" : ".ac3"), stream);
    }

    @Nested
    @DisplayName("pinned FATE streams")
    class Streams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.ac3.Ac3DecoderTest#samples")
        @DisplayName("decode every access unit to finite samples at 48 kHz")
        void decodes(Ac3TestSupport.Sample sample) {
            var stream = Ac3TestSupport.read(sample.fetch());
            var units = Ac3TestSupport.units(stream);
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.defaults());
            assertEquals(48000, decoded.sampleRate());
            assertEquals(units.stream().mapToInt(Ac3TestSupport::samples).sum(), decoded.frames());
            for (var plane : decoded.planes()) {
                for (var value : plane) {
                    assertTrue(Float.isFinite(value) && Math.abs(value) < 4, "sample " + value);
                }
            }
        }

        @Test
        @DisplayName("lays out the 7.1 stream from its AC-3 core and its E-AC-3 dependent substream")
        void sevenOne() {
            var stream = Ac3TestSupport.read(Ac3TestSupport.SAMPLES.get(7).fetch());
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.defaults());
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT), decoded.layout().speakers());
            var core = Ac3TestSupport.decode(stream, Ac3DecoderOptions.defaults().toBuilder().channelExtensions(false).build());
            assertEquals(6, core.layout().channels());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.ac3.Ac3DecoderTest#samples")
        @DisplayName("report the dialogue level, and object audio only for the Atmos stream")
        void metadata(Ac3TestSupport.Sample sample) {
            var stream = Ac3TestSupport.read(sample.fetch());
            var unit = Ac3TestSupport.units(stream).getFirst();
            try (var decoder = new Ac3Decoder(Ac3TestSupport.track(enhanced(stream) ? Codec.EAC3 : Codec.AC3))) {
                assertTrue(decoder.dialogueLevel().isEmpty());
                decoder.decode(new Packet(0, unit, 0, 0, 1536, true), buffer -> {
                });
                var level = decoder.dialogueLevel().orElseThrow();
                assertTrue(level >= -31 && level <= -1, "dialogue level " + level);
                assertEquals(sample == Ac3TestSupport.SAMPLES.get(7), decoder.objectAudio());
            }
        }
    }

    @Nested
    @DisplayName("against ffmpeg")
    class Oracles {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.ac3.Ac3DecoderTest#oracleCases")
        @DisplayName("match in full range on dither-free copies")
        void fullRange(Ac3TestSupport.Sample sample, int channels, double floor, double maxError) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var stream = DitherClearing.clear(Ac3TestSupport.read(sample.fetch()));
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange());
            var expected = Ac3TestSupport.ffmpeg(cleared(sample, stream), "-drc_scale", "0");
            var actual = decoded.interleaved();
            assertEquals(channels, decoded.layout().channels());
            assertEquals(expected.length, actual.length);
            var result = Ac3TestSupport.compare(expected, actual);
            System.out.printf("%s: SNR %.2f dB, max error %.3g%n", sample, result[0], result[1]);
            assertTrue(result[0] >= floor, sample + " SNR " + result[0]);
            assertTrue(result[1] <= maxError, sample + " max error " + result[1]);
        }

        @ParameterizedTest(name = "sample {0}")
        @ValueSource(ints = {0, 5, 7})
        @DisplayName("match the default dynamic range compression")
        void compression(int index) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var sample = Ac3TestSupport.SAMPLES.get(index);
            var stream = DitherClearing.clear(Ac3TestSupport.read(sample.fetch()));
            var expected = Ac3TestSupport.ffmpeg(cleared(sample, stream));
            var actual = Ac3TestSupport.decode(stream, Ac3DecoderOptions.defaults()).interleaved();
            var full = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()).interleaved();
            var snr = Ac3TestSupport.compare(expected, actual)[0];
            System.out.printf("%s compressed: SNR %.2f dB%n", sample, snr);
            assertTrue(snr >= (index == 0 ? 40 : 80), "SNR " + snr);
            assertFalse(Arrays.equals(actual, full), "compression changes the output");
        }

        @ParameterizedTest(name = "sample {0}")
        @ValueSource(ints = {2, 7})
        @DisplayName("match the Lo/Ro stereo downmix")
        void stereoDownmix(int index) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var sample = Ac3TestSupport.SAMPLES.get(index);
            var stream = DitherClearing.clear(Ac3TestSupport.read(sample.fetch()));
            var expected = Ac3TestSupport.ffmpeg(cleared(sample, stream), "-drc_scale", "0", "-downmix", "stereo");
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange().toBuilder().downmix(Ac3Downmix.STEREO).build());
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT), decoded.layout().speakers());
            var snr = Ac3TestSupport.compare(expected, decoded.interleaved())[0];
            System.out.printf("%s stereo downmix: SNR %.2f dB%n", sample, snr);
            assertTrue(snr >= (index == 7 ? 85 : 33), "SNR " + snr);
        }

        @Test
        @DisplayName("match the mono downmix up to its overflow-safe gain")
        void monoDownmix() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var sample = Ac3TestSupport.SAMPLES.get(7);
            var stream = DitherClearing.clear(Ac3TestSupport.read(sample.fetch()));
            var expected = Ac3TestSupport.ffmpeg(cleared(sample, stream), "-drc_scale", "0", "-downmix", "mono");
            var decoded = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange().toBuilder().downmix(Ac3Downmix.MONO).build());
            assertEquals(List.of(Speaker.FRONT_CENTER), decoded.layout().speakers());
            var actual = decoded.interleaved();
            var dot = 0.0;
            var energy = 0.0;
            for (var i = 0; i < actual.length; i++) {
                dot += (double) expected[i] * actual[i];
                energy += (double) actual[i] * actual[i];
            }
            var gain = dot / energy;
            var scaled = new float[actual.length];
            for (var i = 0; i < actual.length; i++) {
                scaled[i] = (float) (actual[i] * gain);
            }
            var snr = Ac3TestSupport.compare(expected, scaled)[0];
            System.out.printf("%s mono downmix: SNR %.2f dB at gain %.4f%n", sample, snr, gain);
            assertEquals(Math.sqrt(2), gain, 1e-3);
            assertTrue(snr >= 85, "SNR " + snr);
        }
    }

    @Nested
    @DisplayName("damaged frames")
    class Damage {
        @Test
        @DisplayName("conceal a frame failing its CRC without disturbing the frames around it")
        void concealment() {
            var stream = DitherClearing.clear(Ac3TestSupport.read(Ac3TestSupport.SAMPLES.get(0).fetch()));
            var clean = Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange());
            var units = Ac3TestSupport.units(stream);
            var target = 60;
            var damaged = stream.clone();
            var offset = 0;
            for (var i = 0; i < target; i++) {
                offset += units.get(i).length;
            }
            damaged[offset + units.get(target).length / 2] ^= 0x5A;
            var decoded = Ac3TestSupport.decode(damaged, Ac3DecoderOptions.fullRange());
            assertEquals(clean.frames(), decoded.frames());
            var start = target * 1536;
            var end = start + 1536;
            for (var ch = 0; ch < 2; ch++) {
                var expected = clean.planes().get(ch);
                var actual = decoded.planes().get(ch);
                assertTrue(Arrays.equals(expected, 0, start, actual, 0, start), "frames before the damage");
                assertFalse(Arrays.equals(expected, start, end, actual, start, end), "concealed frame");
                assertTrue(Arrays.equals(expected, end + 1536, expected.length, actual, end + 1536, actual.length),
                        "frames after the damage once the overlap is flushed");
            }
        }

        @ParameterizedTest(name = "sample {0}")
        @ValueSource(ints = {0, 2, 3, 7})
        @DisplayName("raise only MediaException on mutated and truncated packets")
        void mutations(int index) {
            var stream = Ac3TestSupport.read(Ac3TestSupport.SAMPLES.get(index).fetch());
            var units = Ac3TestSupport.units(stream);
            var codec = enhanced(stream) ? Codec.EAC3 : Codec.AC3;
            var random = new Random(index * 7919L + 1);
            assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
                for (var round = 0; round < 6; round++) {
                    try (var decoder = new Ac3Decoder(Ac3TestSupport.track(codec), Ac3DecoderOptions.defaults()
                            .toBuilder().crcCheck(round % 2 == 0).build())) {
                        for (var u = 0; u < Math.min(units.size(), 60); u++) {
                            var unit = units.get(u).clone();
                            switch (random.nextInt(4)) {
                                case 0 -> unit[random.nextInt(unit.length)] ^= (byte) (1 << random.nextInt(8));
                                case 1 -> {
                                    for (var k = 0; k < 16; k++) {
                                        unit[random.nextInt(unit.length)] = (byte) random.nextInt();
                                    }
                                }
                                case 2 -> unit = Arrays.copyOf(unit, random.nextInt(unit.length));
                                default -> {
                                    for (var k = 8; k < unit.length; k++) {
                                        unit[k] = (byte) random.nextInt();
                                    }
                                }
                            }
                            decodeTolerantly(decoder, unit);
                        }
                    }
                }
            });
        }

        @Test
        @DisplayName("raise only MediaException on random bytes")
        void noise() {
            var random = new Random(42);
            try (var decoder = new Ac3Decoder(Ac3TestSupport.track(Codec.EAC3), Ac3DecoderOptions.defaults().toBuilder().crcCheck(false).build())) {
                for (var i = 0; i < 2000; i++) {
                    var data = new byte[random.nextInt(4096)];
                    random.nextBytes(data);
                    if (data.length >= 2) {
                        data[0] = 0x0B;
                        data[1] = 0x77;
                    }
                    decodeTolerantly(decoder, data);
                }
            }
        }

        private static void decodeTolerantly(Ac3Decoder decoder, byte[] data) {
            try {
                decoder.decode(new Packet(0, data, 0, 0, 1536, true), buffer -> {
                    for (var ch = 0; ch < buffer.channels(); ch++) {
                        for (var i = 0; i < buffer.frames(); i++) {
                            if (!Float.isFinite(buffer.plane(ch)[i])) {
                                fail("non-finite output");
                            }
                        }
                    }
                });
            } catch (MediaException expected) {
                if (expected.getCause() != null) {
                    throw new AssertionError("the safety net caught " + expected.getCause(), expected.getCause());
                }
            } catch (RuntimeException unexpected) {
                throw new AssertionError("decoder leaked " + unexpected, unexpected);
            }
        }
    }

    @Nested
    @DisplayName("consumer failures")
    class ConsumerFailures {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"AC-3", "E-AC-3"})
        @DisplayName("reach the caller unchanged, not as a corrupt packet")
        void passThrough(String format) {
            var input = Ac3EncoderTest.signal(ChannelLayout.STEREO, 48000, 0.1);
            var enhanced = format.equals("E-AC-3");
            var packets = enhanced ? Eac3EncoderTest.encode(Eac3EncoderOptions.defaults(), input)
                    : Ac3EncoderTest.encode(Ac3EncoderOptions.defaults(), input);
            var failure = new IllegalStateException("consumer");
            try (var decoder = new Ac3Decoder(Ac3TestSupport.track(enhanced ? Codec.EAC3 : Codec.AC3))) {
                var thrown = assertThrows(IllegalStateException.class,
                        () -> decoder.decode(packets.getFirst(), buffer -> {
                            throw failure;
                        }));
                assertSame(failure, thrown);
            }
        }
    }
}
