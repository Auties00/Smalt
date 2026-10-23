package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the ISO/IEC 14496-26 conformance streams of ER AAC LC ({@code er_al}), ER AAC LTP with the error
 * resilience tools ({@code er_ap14}, {@code er_ap27}), AAC-LD ({@code er_ad}) and AAC-ELD without SBR
 * ({@code er_eld}), at every sampling rate of the suite, and compares them with the reference waveforms under the
 * criterion of {@link AacConformanceTest}, on the output clipped to full scale as the references are. The streams
 * of the suites that use perceptual noise substitution, whose noise differs sample by sample, are compared by
 * their band energies instead. Streams stored with {@code epConfig} 1 (one track per error sensitivity category
 * instance) must decode bit for bit like their {@code epConfig} 0 twins, the streams ffmpeg's native decoder handles
 * must agree with it to single precision rounding, and the error protection tool
 * ({@code epConfig} 3) must be rejected as unsupported.
 */
class AacErrorResilientConformanceTest {
    private static final String[] ENHANCED_LOW_DELAY = {"1000np", "1001np", "1010np", "1020np", "1030np", "1040np",
            "1050np", "1060np", "1100np", "1101np", "1110np", "1120np", "1130np", "1140np", "1160np", "1170np", "2000np",
            "2070np", "2100np", "2150np"};

    private static final String[] ENHANCED_LOW_DELAY_RATES = {"22", "24", "32", "44", "48"};

    /** Decodes a stream, clipping every sample to the range of the 24-bit reference waveforms. */
    static float[][] decode(String stream) {
        var planes = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(stream
                + ".mp4")))).planes();
        clip(planes);
        return planes;
    }

    private static void clip(float[][] planes) {
        for (var plane : planes) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = Math.clamp(plane[i], -1.0f, 8388607 / 8388608.0f);
            }
        }
    }

    static float[][] reference(String name) {
        return AacTestSupport.readWav(Fixtures.read(ErrorResilientVectors.fetch(name + ".wav")));
    }

    /** Returns the reference name of a stream: the stream name without its {@code _ep0} suffix. */
    private static String referenceOf(String stream) {
        return stream.replace("_ep0", "");
    }

    /** Asserts that every channel of a stream reproduces its reference under the conformance criterion. */
    static void assertStrict(String stream) {
        var decoded = decode(stream);
        var expected = reference(referenceOf(stream));
        assertEquals(expected.length, decoded.length, stream + " channels");
        for (var channel = 0; channel < expected.length; channel++) {
            AacConformanceTest.assertConformant(expected[channel], decoded[channel], 1024, stream + " channel " + channel);
        }
    }

    /**
     * Asserts that every channel of a stream with noise substitution matches its reference's band energies
     * within half a decibel on average, and that it differs sample by sample (so the stream does use noise).
     */
    static void assertNoise(String stream, int sampleRate) {
        var decoded = decode(stream);
        var expected = reference(referenceOf(stream));
        assertEquals(expected.length, decoded.length, stream + " channels");
        for (var channel = 0; channel < expected.length; channel++) {
            var deviation = PnsEnergy.meanBandDeviation(expected[channel], decoded[channel], sampleRate);
            assertTrue(deviation < 0.5, stream + " channel " + channel + ": mean band energy deviation " + deviation
                    + " dB");
            var error = AacConformanceTest.error(expected[channel], decoded[channel], 1024);
            assertTrue(error[0] > AacConformanceTest.RMS_LIMIT, stream + " channel " + channel
                    + " matches sample by sample, so it holds no noise substitution");
        }
    }

    private static int rate(String stream) {
        var code = stream.substring(stream.lastIndexOf('_', stream.indexOf("_ep") - 1) + 1, stream.indexOf("_ep"));
        return switch (code) {
            case "08" -> 8000;
            case "11" -> 11025;
            case "12" -> 12000;
            case "16" -> 16000;
            case "22" -> 22050;
            case "24" -> 24000;
            case "32" -> 32000;
            case "44" -> 44100;
            case "48" -> 48000;
            case "64" -> 64000;
            case "88" -> 88200;
            case "96" -> 96000;
            default -> throw new IllegalArgumentException(stream);
        };
    }

    @Nested
    @DisplayName("ER AAC LC against reference waveforms")
    class LowComplexity {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al10_08_ep0", "er_al10_11_ep0", "er_al10_12_ep0", "er_al10_16_ep0", "er_al10_22_ep0",
                "er_al10_24_ep0", "er_al10_32_ep0", "er_al10_44_ep0", "er_al10_48_ep0", "er_al10_64_ep0", "er_al10_88_ep0",
                "er_al10_96_ep0", "er_al15_08_ep0", "er_al15_22_ep0", "er_al15_32_ep0", "er_al15_48_ep0", "er_al21_08_ep0",
                "er_al21_22_ep0", "er_al21_32_ep0", "er_al21_48_ep0", "er_al26_08_ep0", "er_al26_12_ep0", "er_al26_22_ep0",
                "er_al26_32_ep0", "er_al26_48_ep0", "er_al26_88_ep0"})
        @DisplayName("matches the reference with and without virtual codebooks, RVLC and codeword reordering")
        void strict(String stream) {
            assertStrict(stream);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al12_48_ep0", "er_al18_48_ep0", "er_al23_48_ep0", "er_al15_96_ep0", "er_al21_96_ep0",
                "er_al26_11_ep0", "er_al26_16_ep0", "er_al26_24_ep0", "er_al26_44_ep0", "er_al26_64_ep0",
                "er_al26_96_ep0"})
        @DisplayName("reproduces the reference's band energies where noise substitution is used")
        void noise(String stream) {
            assertNoise(stream, rate(stream));
        }

        @Test
        @DisplayName("decodes the core of a stream that signals SBR around it, at the core rate")
        void sbrSignaledAroundCore() {
            // er_al10_48's configuration (898900) wrapped in object type 5 with a 96 kHz extension
            var stream = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch("er_al10_48_ep0.mp4")));
            var wrapped = new AacTestSupport.Stream(HexFormat.of().parseHex("29884480"), stream.frames(),
                    stream.sampleRate(), stream.channels());
            var expected = AacTestSupport.decodeAll(stream).planes();
            try (var decoder = new AacDecoder(wrapped.track())) {
                assertTrue(decoder.spectralBandReplicationSignaled());
                assertFalse(decoder.decodesSpectralBandReplication());
                assertEquals(48000, decoder.sampleRate());
                assertEquals(1024, decoder.frameLength());
                var actual = AacTestSupport.decodeAll(decoder, wrapped.frames()).planes();
                assertEquals(expected.length, actual.length);
                for (var channel = 0; channel < expected.length; channel++) {
                    assertArrayEquals(expected[channel], actual[channel], "channel " + channel);
                }
            }
        }
    }

    @Nested
    @DisplayName("ER AAC LTP with the error resilience tools against reference waveforms")
    class LongTermPrediction {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_ap14_11_ep0", "er_ap14_16_ep0", "er_ap14_24_ep0", "er_ap14_44_ep0", "er_ap14_64_ep0",
                "er_ap14_96_ep0", "er_ap27_08_ep0", "er_ap27_12_ep0", "er_ap27_22_ep0", "er_ap27_32_ep0", "er_ap27_48_ep0",
                "er_ap27_88_ep0"})
        @DisplayName("matches the reference at every sampling rate")
        void strict(String stream) {
            assertStrict(stream);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_ap14_08_ep0", "er_ap14_12_ep0", "er_ap14_22_ep0", "er_ap14_32_ep0", "er_ap14_48_ep0",
                "er_ap14_88_ep0", "er_ap27_11_ep0", "er_ap27_16_ep0", "er_ap27_24_ep0", "er_ap27_44_ep0", "er_ap27_64_ep0",
                "er_ap27_96_ep0"})
        @DisplayName("reproduces the reference's band energies where noise substitution is used")
        void noise(String stream) {
            assertNoise(stream, rate(stream));
        }
    }

    @Nested
    @DisplayName("AAC-LD against reference waveforms")
    class LowDelay {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_ad1000_48_ep0", "er_ad1000np_48_ep0", "er_ad1030_48_ep0", "er_ad1030np_48_ep0",
                "er_ad1100np_48_ep0", "er_ad1102_48_ep0", "er_ad1102np_48_ep0", "er_ad1103_48_ep0", "er_ad1103np_48_ep0",
                "er_ad1110np_48_ep0", "er_ad1120np_48_ep0", "er_ad1130np_48_ep0", "er_ad1140np_48_ep0",
                "er_ad1150np_48_ep0", "er_ad1160np_48_ep0", "er_ad1170np_48_ep0", "er_ad2050_48_ep0",
                "er_ad2050np_48_ep0", "er_ad2170_48_ep0", "er_ad2170np_48_ep0", "er_ad1000_22_ep0", "er_ad1000_24_ep0",
                "er_ad1000_32_ep0", "er_ad1000_44_ep0", "er_ad1102_22_ep0", "er_ad1102_24_ep0", "er_ad1102_32_ep0",
                "er_ad1102_44_ep0", "er_ad1170np_22_ep0", "er_ad1170np_24_ep0", "er_ad1170np_32_ep0",
                "er_ad1170np_44_ep0", "er_ad2050_22_ep0", "er_ad2050_32_ep0", "er_ad2170_22_ep0", "er_ad2170_32_ep0"})
        @DisplayName("matches the reference with 512- and 480-sample frames, long term prediction and the resilience tools")
        void strict(String stream) {
            assertStrict(stream);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_ad1009np_48_ep0", "er_ad1020_48_ep0", "er_ad1020np_48_ep0", "er_ad1109_48_ep0",
                "er_ad1109np_48_ep0", "er_ad1111_48_ep0", "er_ad1111np_48_ep0", "er_ad2040_48_ep0", "er_ad2040np_48_ep0",
                "er_ad2160_48_ep0", "er_ad2160np_48_ep0", "er_ad2050_24_ep0", "er_ad2050_44_ep0", "er_ad2170_24_ep0",
                "er_ad2170_44_ep0"})
        @DisplayName("reproduces the reference's band energies where noise substitution is used")
        void noise(String stream) {
            assertNoise(stream, rate(stream));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"er_ad1000_48_ep0, 512, 1", "er_ad1111_48_ep0, 480, 1", "er_ad2170_48_ep0, 480, 2"})
        @DisplayName("reports the low delay frame length and layout")
        void format(String stream, int frameLength, int channels) {
            var parsed = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(stream + ".mp4")));
            try (var decoder = new AacDecoder(parsed.track())) {
                assertEquals(frameLength, decoder.frameLength());
                assertEquals(48000, decoder.sampleRate());
                assertEquals(channels, decoder.layout().channels());
                assertFalse(decoder.separateCategoryInstances());
                assertFalse(decoder.decodesSpectralBandReplication());
                var blocks = new ArrayList<Integer>();
                decoder.decode(new Packet(1, parsed.frames().getFirst(), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0,
                        true), buffer -> blocks.add(buffer.frames()));
                assertEquals(List.of(frameLength), blocks);
            }
        }
    }

    static Stream<String> enhancedLowDelayStreams() {
        return Stream.of(ENHANCED_LOW_DELAY).flatMap(stream -> Stream.of(ENHANCED_LOW_DELAY_RATES)
                .map(rate -> "er_eld" + stream + "_" + rate + "_ep0"));
    }

    @Nested
    @DisplayName("AAC-ELD against reference waveforms")
    class EnhancedLowDelay {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacErrorResilientConformanceTest#enhancedLowDelayStreams")
        @DisplayName("matches the reference through the low delay filterbank at every sampling rate")
        void strict(String stream) {
            assertStrict(stream);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_eld1002np_48_ep0", "er_eld1102np_48_ep0"})
        @DisplayName("reproduces the reference's band energies where noise substitution is used")
        void noise(String stream) {
            assertNoise(stream, 48000);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"er_eld1000np_48_ep0, 512, 1", "er_eld1100np_22_ep0, 480, 1", "er_eld2150np_48_ep0, 480, 2"})
        @DisplayName("reports the low delay frame length, the layout and no spectral band replication")
        void format(String stream, int frameLength, int channels) {
            var parsed = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(stream + ".mp4")));
            try (var decoder = new AacDecoder(parsed.track())) {
                assertEquals(frameLength, decoder.frameLength());
                assertEquals(channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO, decoder.layout());
                assertFalse(decoder.spectralBandReplicationSignaled());
                assertFalse(decoder.decodesSpectralBandReplication());
            }
        }
    }

    @Nested
    @DisplayName("against ffmpeg's native decoder")
    class Ffmpeg {
        // ffmpeg decodes neither the resilience tools nor LTP-LD nor LD-SBR, and its 480-sample AAC-LD output is
        // off the reference waveforms (er_ad1100np, er_ad1102np, er_ad1103np), so only these streams are compared
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al10_08_ep0", "er_al10_48_ep0", "er_al21_08_ep0", "er_al21_48_ep0",
                "er_ad1000np_48_ep0", "er_eld1000np_22_ep0", "er_eld1000np_48_ep0", "er_eld1001np_44_ep0",
                "er_eld1100np_48_ep0", "er_eld1101np_32_ep0", "er_eld2000np_48_ep0", "er_eld2100np_22_ep0",
                "er_eld2100np_48_ep0"})
        @DisplayName("agree to single precision rounding on ER AAC LC, 512-sample AAC-LD and AAC-ELD")
        void agrees(String stream) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = ErrorResilientVectors.fetch(stream + ".mp4");
            var planes = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(file))).planes();
            var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), planes.length);
            for (var channel = 0; channel < planes.length; channel++) {
                assertEquals(reference[channel].length, planes[channel].length, stream + " sample count");
                double sum = 0;
                double peak = 0;
                for (var i = 0; i < planes[channel].length; i++) {
                    var difference = Math.abs(reference[channel][i] - planes[channel][i]);
                    sum += difference * difference;
                    peak = Math.max(peak, difference);
                }
                var rms = Math.sqrt(sum / planes[channel].length);
                assertTrue(rms < 2e-7 && peak < 4e-6, stream + " channel " + channel + ": rms " + rms + ", peak " + peak);
            }
        }
    }

    @Nested
    @DisplayName("error sensitivity categories in separate access units (epConfig 1)")
    class SeparateCategories {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al10_08", "er_al10_48", "er_al12_08", "er_al12_48", "er_al15_08", "er_al15_48",
                "er_al18_08", "er_al18_48", "er_al21_08", "er_al21_48", "er_al23_08", "er_al23_48", "er_al26_08",
                "er_al26_48", "er_ap14_48", "er_ap27_48", "er_ad1000_48", "er_ad1000np_48", "er_ad1020_48",
                "er_ad1020np_48", "er_ad1030_48", "er_ad1030np_48", "er_ad1102_48", "er_ad1102np_48", "er_ad1103_48",
                "er_ad1103np_48", "er_ad1109_48", "er_ad1111_48", "er_ad1111np_48", "er_ad2040_48", "er_ad2040np_48",
                "er_ad2050_48", "er_ad2050np_48", "er_ad2160_48", "er_ad2160np_48", "er_ad2170_48", "er_ad2170np_48"})
        @DisplayName("decode bit for bit like the same stream with epConfig 0")
        void matchesSingleAccessUnits(String stream) {
            var tracks = AacTestSupport.parseMp4Tracks(Fixtures.read(ErrorResilientVectors.fetch(stream + "_ep1.mp4")));
            assertTrue(tracks.size() >= 3, stream + " has " + tracks.size() + " tracks");
            var separate = AacTestSupport.decodeInstances(tracks).planes();
            var single = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(stream
                    + "_ep0.mp4")))).planes();
            assertEquals(single.length, separate.length);
            for (var channel = 0; channel < single.length; channel++) {
                assertArrayEquals(single[channel], separate[channel], stream + " channel " + channel);
            }
        }

        @Test
        @DisplayName("rejects a single access unit, and category instances for a stream that does not separate them")
        void wrongEntryPoint() {
            var tracks = AacTestSupport.parseMp4Tracks(Fixtures.read(ErrorResilientVectors.fetch("er_al21_48_ep1.mp4")));
            try (var decoder = new AacDecoder(tracks.getFirst().track())) {
                assertTrue(decoder.separateCategoryInstances());
                var packet = new Packet(1, tracks.getFirst().frames().getFirst(), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP,
                        0, true);
                var exception = assertThrows(MediaException.Unsupported.class, () -> decoder.decode(packet, buffer -> {
                }));
                assertEquals("aac er epconfig 1", exception.format());
            }
            var single = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch("er_al21_48_ep0.mp4")));
            try (var decoder = new AacDecoder(single.track())) {
                var packet = new Packet(1, single.frames().getFirst(), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true);
                assertThrows(IllegalStateException.class, () -> decoder.decode(List.of(packet), buffer -> {
                }));
            }
        }

        @Test
        @DisplayName("rejects a block that lacks a category instance as malformed")
        void missingInstance() {
            // er_al21 is a channel pair without RVLC: one common instance and three per channel
            var tracks = AacTestSupport.parseMp4Tracks(Fixtures.read(ErrorResilientVectors.fetch("er_al21_48_ep1.mp4")));
            assertEquals(7, tracks.size());
            try (var decoder = new AacDecoder(tracks.getFirst().track())) {
                for (var kept = 0; kept < tracks.size(); kept++) {
                    var instances = new ArrayList<Packet>();
                    for (var track = 0; track < kept; track++) {
                        instances.add(new Packet(1, tracks.get(track).frames().get(10), Packet.NO_TIMESTAMP,
                                Packet.NO_TIMESTAMP, 0, true));
                    }
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(instances, buffer -> {
                    }), kept + " instances");
                }
            }
        }
    }

    @Nested
    @DisplayName("seeking with an audio reader")
    class Seeking {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al26_48_ep0", "er_ad1000_48_ep0", "er_ad2170_44_ep0", "er_eld1000np_48_ep0",
                "er_eld2150np_32_ep0", "er_eld_sbr_s_1_48", "er_eld_sbr_e_2_44", "er_eld_sbr_twi_1_24"})
        @DisplayName("returns exactly the samples a linear read returns from the target on")
        void exact(String name) {
            var bytes = Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4"));
            float[][] linear;
            int rate;
            try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                rate = reader.sampleRate();
                linear = AacObjectTypesConformanceTest.Seeking.readAll(reader);
            }
            for (var micros : new long[]{500_000, 1_000_000, 2_000_000}) {
                var start = (int) (micros * rate / 1_000_000);
                if (start >= linear[0].length) {
                    continue;
                }
                try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                    reader.seek(micros);
                    var tail = AacObjectTypesConformanceTest.Seeking.readAll(reader);
                    for (var channel = 0; channel < linear.length; channel++) {
                        assertArrayEquals(Arrays.copyOfRange(linear[channel], start, linear[channel].length), tail[channel],
                                name + " channel " + channel + " after a seek to " + micros);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("the error protection tool (epConfig 3)")
    class ErrorProtection {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al10_08_ep3_crc01", "er_al15_48_ep3_srcpc01", "er_al18_11_ep3_crc02",
                "er_al21_32_ep3_crc03", "er_al23_44_ep3_crc04", "er_al26_88_ep3_srcpc03", "er_ad1111_22_ep3_srs01",
                "er_ad2050_48_ep3_srs03", "er_ad2170_32_ep3_srs04", "er_ap27_16_ep3_srcpc04"})
        @DisplayName("is rejected as unsupported, naming it")
        void unsupported(String stream) {
            var parsed = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(stream + ".mp4")));
            var exception = assertThrows(MediaException.Unsupported.class, () -> new AacDecoder(parsed.track()));
            assertEquals("aac er epconfig 3", exception.format());
        }
    }

    /**
     * The ER AAC LC, AAC-LD and AAC-ELD streams at the sampling rates the tests above do not cover whose every channel
     * matches its reference.
     */
    private static final String[] OTHER_RATES_STRICT = {
            "er_ad1000np_22_ep0", "er_ad1000np_24_ep0", "er_ad1000np_32_ep0", "er_ad1000np_44_ep0", "er_ad1020_24_ep0",
            "er_ad1020_44_ep0", "er_ad1020np_24_ep0", "er_ad1020np_44_ep0", "er_ad1030_22_ep0", "er_ad1030_32_ep0",
            "er_ad1030np_22_ep0", "er_ad1030np_32_ep0", "er_ad1100np_22_ep0", "er_ad1100np_24_ep0",
            "er_ad1100np_32_ep0", "er_ad1100np_44_ep0", "er_ad1102np_22_ep0", "er_ad1102np_24_ep0",
            "er_ad1102np_32_ep0", "er_ad1102np_44_ep0", "er_ad1103_22_ep0", "er_ad1103_32_ep0", "er_ad1103np_22_ep0",
            "er_ad1103np_32_ep0", "er_ad1110np_22_ep0", "er_ad1110np_24_ep0", "er_ad1110np_32_ep0",
            "er_ad1110np_44_ep0", "er_ad1111_24_ep0", "er_ad1111_44_ep0", "er_ad1111np_24_ep0", "er_ad1111np_44_ep0",
            "er_ad1120np_22_ep0", "er_ad1120np_24_ep0", "er_ad1120np_32_ep0", "er_ad1120np_44_ep0",
            "er_ad1130np_22_ep0", "er_ad1130np_24_ep0", "er_ad1130np_32_ep0", "er_ad1130np_44_ep0",
            "er_ad1140np_22_ep0", "er_ad1140np_24_ep0", "er_ad1140np_32_ep0", "er_ad1140np_44_ep0",
            "er_ad1150np_22_ep0", "er_ad1150np_24_ep0", "er_ad1150np_32_ep0", "er_ad1150np_44_ep0",
            "er_ad1160np_22_ep0", "er_ad1160np_24_ep0", "er_ad1160np_32_ep0", "er_ad1160np_44_ep0", "er_ad2040_24_ep0",
            "er_ad2040_44_ep0", "er_ad2040np_24_ep0", "er_ad2040np_44_ep0", "er_ad2050np_22_ep0", "er_ad2050np_32_ep0",
            "er_ad2160_24_ep0", "er_ad2160_44_ep0", "er_ad2160np_24_ep0", "er_ad2160np_44_ep0", "er_ad2170np_22_ep0",
            "er_ad2170np_32_ep0", "er_al12_11_ep0", "er_al12_16_ep0", "er_al12_24_ep0", "er_al12_44_ep0",
            "er_al12_64_ep0", "er_al12_96_ep0", "er_al15_12_ep0", "er_al15_88_ep0", "er_al18_11_ep0", "er_al18_16_ep0",
            "er_al18_24_ep0", "er_al18_44_ep0", "er_al18_64_ep0", "er_al18_96_ep0", "er_al21_12_ep0", "er_al21_88_ep0",
            "er_al23_11_ep0", "er_al23_16_ep0", "er_al23_24_ep0", "er_al23_44_ep0", "er_al23_64_ep0", "er_al23_96_ep0"};

    /** The streams at those sampling rates whose noise substitution makes them comparable by band energy only. */
    private static final String[] OTHER_RATES_NOISE = {
            "er_ad1009np_22_ep0", "er_ad1009np_24_ep0", "er_ad1009np_32_ep0", "er_ad1009np_44_ep0", "er_ad1020_22_ep0",
            "er_ad1020_32_ep0", "er_ad1020np_22_ep0", "er_ad1020np_32_ep0", "er_ad1030_24_ep0", "er_ad1030_44_ep0",
            "er_ad1030np_24_ep0", "er_ad1030np_44_ep0", "er_ad1103_24_ep0", "er_ad1103_44_ep0", "er_ad1103np_24_ep0",
            "er_ad1103np_44_ep0", "er_ad1109_22_ep0", "er_ad1109_24_ep0", "er_ad1109_32_ep0", "er_ad1109_44_ep0",
            "er_ad1109np_22_ep0", "er_ad1109np_24_ep0", "er_ad1109np_32_ep0", "er_ad1109np_44_ep0", "er_ad1111_22_ep0",
            "er_ad1111_32_ep0", "er_ad1111np_22_ep0", "er_ad1111np_32_ep0", "er_ad2040_22_ep0", "er_ad2040_32_ep0",
            "er_ad2040np_22_ep0", "er_ad2040np_32_ep0", "er_ad2050np_24_ep0", "er_ad2050np_44_ep0", "er_ad2160_22_ep0",
            "er_ad2160_32_ep0", "er_ad2160np_22_ep0", "er_ad2160np_32_ep0", "er_ad2170np_24_ep0", "er_ad2170np_44_ep0",
            "er_al12_12_ep0", "er_al12_22_ep0", "er_al12_32_ep0", "er_al12_88_ep0", "er_al15_11_ep0", "er_al15_16_ep0",
            "er_al15_24_ep0", "er_al15_44_ep0", "er_al15_64_ep0", "er_al18_12_ep0", "er_al18_22_ep0", "er_al18_32_ep0",
            "er_al18_88_ep0", "er_al21_11_ep0", "er_al21_16_ep0", "er_al21_24_ep0", "er_al21_44_ep0", "er_al21_64_ep0",
            "er_al23_12_ep0", "er_al23_22_ep0", "er_al23_32_ep0", "er_al23_88_ep0", "er_eld1002np_22_ep0",
            "er_eld1002np_24_ep0", "er_eld1002np_32_ep0", "er_eld1002np_44_ep0", "er_eld1102np_22_ep0",
            "er_eld1102np_24_ep0", "er_eld1102np_32_ep0", "er_eld1102np_44_ep0"};

    @Nested
    @DisplayName("ER AAC LC, AAC-LD and AAC-ELD at the other sampling rates")
    class OtherRates {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacErrorResilientConformanceTest#otherRatesStrict")
        @DisplayName("matches the reference")
        void strict(String stream) {
            assertStrict(stream);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacErrorResilientConformanceTest#otherRatesNoise")
        @DisplayName("reproduces the reference's band energies where noise substitution is used")
        void noise(String stream) {
            assertNoise(stream, rate(stream));
        }
    }

    static Stream<String> otherRatesStrict() {
        return Stream.of(OTHER_RATES_STRICT);
    }

    static Stream<String> otherRatesNoise() {
        return Stream.of(OTHER_RATES_NOISE);
    }
}
