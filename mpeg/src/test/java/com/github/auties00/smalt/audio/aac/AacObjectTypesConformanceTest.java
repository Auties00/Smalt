package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the ISO/IEC 14496-26 conformance streams of AAC Main (the {@code am} suite: backward-adaptive
 * prediction with reset groups, intensity stereo and multichannel programs), AAC LTP ({@code ap}), AAC
 * SSR ({@code as}: the PQF filterbank, gain control and every window sequence transition), the Main and SSR
 * streams at every sampling rate of the suite, and ER AAC LTP
 * ({@code er_ap10}), and compares them with the reference waveforms under the criterion of
 * {@link AacConformanceTest}, on the output clipped to full scale as the references are. The ER LTP stream
 * stored with {@code epConfig} 1 must decode like its {@code epConfig} 0 twin, and seeking through an audio
 * reader is checked to be exact. The ER LTP streams with the error resilience tools are covered by
 * {@link AacErrorResilientConformanceTest}.
 */
class AacObjectTypesConformanceTest {
    static Path file(String name) {
        return Mpeg4AudioVectors.file(name);
    }

    /** Decodes a stream, clipping every sample to the range of the 24-bit reference waveforms. */
    static float[][] decode(String stream) {
        var planes = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(file(stream + ".mp4")))).planes();
        for (var plane : planes) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = Math.clamp(plane[i], -1.0f, 8388607 / 8388608.0f);
            }
        }
        return planes;
    }

    static float[][] reference(String name) {
        return AacTestSupport.readWav(Fixtures.read(file(name + ".wav")));
    }

    /** Asserts that every channel of a mono or stereo stream reproduces its reference. */
    static void assertStream(String stream, String reference) {
        var decoded = decode(stream);
        var expected = reference(reference);
        assertEquals(expected.length, decoded.length, stream + " channels");
        for (var channel = 0; channel < expected.length; channel++) {
            AacConformanceTest.assertConformant(expected[channel], decoded[channel], 1024,
                    stream + " channel " + channel);
        }
    }

    /**
     * Asserts that a multichannel stream has one decoded channel per reference waveform and that each
     * reference is reproduced by a channel no other reference claimed.
     */
    static void assertMultichannel(String stream, String channels) {
        var decoded = decode(stream);
        var names = channels.split(" ");
        assertEquals(names.length, decoded.length, stream + " channels");
        var claimed = new ArrayList<Integer>();
        for (var name : names) {
            var expected = reference(stream + "_" + name)[0];
            var match = -1;
            for (var channel = 0; channel < decoded.length && match < 0; channel++) {
                if (claimed.contains(channel)) {
                    continue;
                }
                var error = AacConformanceTest.error(expected, decoded[channel], 1024);
                if (error[0] <= AacConformanceTest.RMS_LIMIT && error[1] <= AacConformanceTest.PEAK_LIMIT) {
                    match = channel;
                }
            }
            assertTrue(match >= 0, stream + ": no channel reproduces " + name);
            claimed.add(match);
        }
    }

    /** The sampling rates of the suite other than 48 kHz, which the families' 48 kHz tests cover. */
    private static final List<String> OTHER_RATES = List.of("08", "11", "12", "16", "22", "24", "32", "44", "64", "88",
            "96");

    /** The reference waveforms of the multichannel streams, by stream family; the others have one waveform. */
    private static final Map<String, String> CHANNELS = Map.of(
            "am05", "f00 f01 f02 b00 b01 l00", "am06", "f00 f01 f02", "am07", "f00 f01 f02 b00 b01 l00",
            "as13", "f00 f01 f02 b00 b01 l00", "as14", "f00 f01 f02 b00 b01 l00",
            "as15", "f00 f01 f02 f03 f04 b00 b01 l00", "as16", "f00 f01 f02 f03 f04 b00 b01 l00");

    static Stream<String> mainRates() {
        return Stream.of("am01", "am02", "am04", "am05", "am06", "am07")
                .flatMap(family -> OTHER_RATES.stream().map(rate -> family + "_" + rate));
    }

    static Stream<String> ssrRates() {
        return IntStream.rangeClosed(1, 17).mapToObj(index -> String.format("as%02d", index))
                .flatMap(family -> OTHER_RATES.stream().map(rate -> family + "_" + rate));
    }

    /** Asserts a stream against its one reference waveform, or a multichannel one against those of its family. */
    static void assertAnyStream(String stream) {
        var channels = CHANNELS.get(stream.substring(0, stream.indexOf('_')));
        if (channels == null) {
            assertStream(stream, stream);
        } else {
            assertMultichannel(stream, channels);
        }
    }

    @Nested
    @DisplayName("AAC Main against reference waveforms")
    class Main {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"08", "11", "12", "16", "22", "24", "32", "44", "48", "64", "88", "96"})
        @DisplayName("prediction at every sampling rate")
        void rates(String rate) {
            assertStream("am00_" + rate, "am00_" + rate);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"am01_48", "am02_48", "am04_48"})
        @DisplayName("mono and stereo streams, including intensity stereo with prediction")
        void streams(String stream) {
            assertStream(stream, stream);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"am05_48, f00 f01 f02 b00 b01 l00", "am06_48, f00 f01 f02", "am07_48, f00 f01 f02 b00 b01 l00"})
        @DisplayName("multichannel programs")
        void multichannel(String stream, String channels) {
            assertMultichannel(stream, channels);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacObjectTypesConformanceTest#mainRates")
        @DisplayName("every stream at the other sampling rates")
        void otherRates(String stream) {
            assertAnyStream(stream);
        }
    }

    @Nested
    @DisplayName("AAC LTP against reference waveforms")
    class LongTermPrediction {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ap01_48", "ap02_48", "ap03_48", "ap04_48", "ap05_48"})
        @DisplayName("mono and stereo streams")
        void streams(String stream) {
            assertStream(stream, stream);
        }
    }

    @Nested
    @DisplayName("AAC SSR against reference waveforms")
    class ScalableSampleRate {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"08", "11", "12", "16", "22", "24", "32", "44", "48", "64", "88", "96"})
        @DisplayName("the PQF filterbank at every sampling rate")
        void rates(String rate) {
            assertStream("as00_" + rate, "as00_" + rate);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"as01_48", "as02_48", "as03_48", "as04_48", "as05_48", "as06_48", "as07_48", "as08_48",
                "as09_48", "as10_48", "as11_48", "as12_48", "as17_48"})
        @DisplayName("gain control, window shapes and every window sequence transition")
        void streams(String stream) {
            assertStream(stream, stream);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"as13_48, f00 f01 f02 b00 b01 l00", "as14_48, f00 f01 f02 b00 b01 l00",
                "as15_48, f00 f01 f02 f03 f04 b00 b01 l00", "as16_48, f00 f01 f02 f03 f04 b00 b01 l00"})
        @DisplayName("multichannel programs")
        void multichannel(String stream, String channels) {
            assertMultichannel(stream, channels);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacObjectTypesConformanceTest#ssrRates")
        @DisplayName("every stream at the other sampling rates")
        void otherRates(String stream) {
            assertAnyStream(stream);
        }
    }

    @Nested
    @DisplayName("ER AAC LTP")
    class ErrorResilient {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"08", "11", "12", "16", "22", "24", "32", "44", "48", "64", "88", "96"})
        @DisplayName("matches the reference waveform at every sampling rate")
        void rates(String rate) {
            assertStream("er_ap10_" + rate + "_ep0", "er_ap10_" + rate);
        }

        @Test
        @DisplayName("reads a channel pair in error sensitivity order")
        void channelPair() {
            // er_al21 is ER AAC LC (object type 17), a stereo stream with common windows, M/S and TNS and no
            // resilience tools; without prediction data its payload is valid ER AAC LTP syntax, so relabeling
            // its configuration as object type 19 exercises the channel pair order of the ER payload.
            var stream = AacTestSupport.parseMp4(Fixtures.read(file("er_al21_48_ep0.mp4")));
            var config = stream.config().clone();
            config[0] = (byte) (config[0] & 0x07 | 19 << 3);
            var planes = AacTestSupport.decodeAll(new AacTestSupport.Stream(config, stream.frames(), stream.sampleRate(),
                    stream.channels())).planes();
            var expected = reference("er_al21_48");
            assertEquals(2, planes.length);
            for (var channel = 0; channel < 2; channel++) {
                AacConformanceTest.assertConformant(expected[channel], planes[channel], 1024, "er_al21 channel " + channel);
            }
        }

        @Test
        @DisplayName("decodes the error sensitivity categories of separate access units like a single access unit")
        void separateCategories() {
            var tracks = AacTestSupport.parseMp4Tracks(Fixtures.read(file("er_ap10_48_ep1.mp4")));
            var separate = AacTestSupport.decodeInstances(tracks).planes();
            var single = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(file("er_ap10_48_ep0.mp4"))))
                    .planes();
            assertEquals(single.length, separate.length);
            for (var channel = 0; channel < single.length; channel++) {
                assertArrayEquals(single[channel], separate[channel], "channel " + channel);
            }
            var exception = assertThrows(MediaException.Unsupported.class,
                    () -> AacTestSupport.decodeAll(tracks.getFirst()));
            assertEquals("aac er epconfig 1", exception.format());
        }

        @Test
        @DisplayName("decodes no spectral band replication")
        void noExtensions() {
            var decoder = new AacDecoder(AacTestSupport.parseMp4(Fixtures.read(file("er_ap10_48_ep0.mp4"))).track());
            assertFalse(decoder.decodesSpectralBandReplication());
            assertEquals(48000, decoder.sampleRate());
        }
    }

    @Nested
    @DisplayName("seeking with an audio reader")
    class Seeking {
        static float[][] readAll(AudioReader reader) {
            var planes = new float[reader.layout().channels()][1 << 16];
            var count = 0;
            for (var block = reader.read(); block != null; block = reader.read()) {
                if (count + block.frames() > planes[0].length) {
                    for (var channel = 0; channel < planes.length; channel++) {
                        planes[channel] = Arrays.copyOf(planes[channel], Math.max(2 * planes[channel].length,
                                count + block.frames()));
                    }
                }
                for (var channel = 0; channel < planes.length; channel++) {
                    System.arraycopy(block.plane(channel), 0, planes[channel], count, block.frames());
                }
                count += block.frames();
            }
            for (var channel = 0; channel < planes.length; channel++) {
                planes[channel] = Arrays.copyOf(planes[channel], count);
            }
            return planes;
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"am01_48", "ap01_48", "as17_48", "er_ap10_48_ep0"})
        @DisplayName("returns exactly the samples a linear read returns from the target on")
        void exact(String name) {
            // the prediction state of AAC Main and LTP reaches back to the start of the stream, so a seek
            // that pre-rolled only a few frames would differ from the linear read (by up to 0.13 of full
            // scale on am01)
            var bytes = Fixtures.read(file(name + ".mp4"));
            float[][] linear;
            int rate;
            try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                rate = reader.sampleRate();
                linear = readAll(reader);
            }
            for (var micros : new long[]{1_000_000, 2_718_281}) {
                try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                    reader.seek(micros);
                    var tail = readAll(reader);
                    var start = (int) (micros * rate / 1_000_000);
                    for (var channel = 0; channel < linear.length; channel++) {
                        assertArrayEquals(Arrays.copyOfRange(linear[channel], start, linear[channel].length), tail[channel],
                                name + " channel " + channel + " after a seek to " + micros);
                    }
                }
            }
        }
    }
}
