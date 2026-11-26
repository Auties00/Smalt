package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the AAC Low Complexity conformance streams of ISO/IEC 14496-26 (the {@code al*}
 * suite: every sampling rate, 1024- and 960-sample frames, mono to 48 channels, program
 * configurations, coupling channels, PNS; every family at every sampling rate the suite covers) and compares
 * them with the reference waveforms. The
 * HE-AAC streams of the suite are covered by {@link HeAacConformanceTest}.
 *
 * <p>The accuracy criterion is that of the standard for 16-bit output: an RMS error of at most
 * {@code 2^-15 / sqrt(12)} and a peak error of at most {@code 2^-14} of full scale. Reference
 * waveforms start either at the first decoded sample or after the priming frames, so every
 * comparison tries offsets of zero to two frames. The reference waveforms are integer PCM, so decoded
 * samples beyond full scale are clipped before the comparison, as a conforming PCM output clips them
 * (the {@code al14} streams at 64 to 96 kHz exceed full scale). The {@code al18} and {@code al19} streams
 * code their content with perceptual noise substitution, whose random noise no two decoders reproduce
 * sample by sample, so they are compared by short-term energy.
 */
class AacConformanceTest {
    static final double RMS_LIMIT = Math.pow(2, -15) / Math.sqrt(12);

    static final double PEAK_LIMIT = Math.pow(2, -14);

    /** The largest sample of the 24-bit reference waveforms. */
    static final double FULL_SCALE = 1 - Math.pow(2, -23);

    static Path file(String name) {
        return Mpeg4AudioVectors.file(name);
    }

    static AacTestSupport.Decoded decode(String stream) {
        return AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(file(stream + ".mp4"))));
    }

    /** Returns {rms, peak} of the best alignment of {@code actual} against {@code reference}. */
    static double[] error(float[] reference, float[] actual, int frameLength) {
        double[] best = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (var offset = 0; offset <= 2 * frameLength; offset += frameLength) {
            if (actual.length - offset < reference.length) {
                continue;
            }
            double squared = 0;
            double peak = 0;
            for (var i = 0; i < reference.length; i++) {
                var clipped = Math.max(-1.0, Math.min(FULL_SCALE, actual[i + offset]));
                var difference = Math.abs((double) reference[i] - clipped);
                squared += difference * difference;
                peak = Math.max(peak, difference);
            }
            var rms = Math.sqrt(squared / reference.length);
            if (rms < best[0]) {
                best = new double[]{rms, peak};
            }
        }
        return best;
    }

    static void assertConformant(float[] reference, float[] actual, int frameLength, String what) {
        var error = error(reference, actual, frameLength);
        assertTrue(error[0] <= RMS_LIMIT, what + ": RMS error " + error[0] + " exceeds " + RMS_LIMIT);
        assertTrue(error[1] <= PEAK_LIMIT, what + ": peak error " + error[1] + " exceeds " + PEAK_LIMIT);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("decodes the AAC-LC 5.1 CMAF of a production encoder within the criterion of ffmpeg's decode")
    void productionSurround() {
        com.github.auties00.smalt.testkit.Oracle.assume(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG);
        HeAacContainersTest.assertLikeFfmpeg("cta-aac-lc-5.1.mp4",
                StreamingVectors.cmaf("cta-wave-camc-aac_lc-at11-2023-04-27.zip"), 48000, 6);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @CsvSource({"audio/ChID_voices_2ch_64kbps_aac.aac, 2", "audio/ChID_voices_6ch_640kbps_aac.aac, 6"})
    @DisplayName("decodes the Dolby-encoded AAC of the online delivery kit within the criterion of ffmpeg's decode")
    void dolbyEncoded(String member, int channels) {
        com.github.auties00.smalt.testkit.Oracle.assume(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG);
        var data = Fixtures.read(com.github.auties00.smalt.testkit.Conformance.archive("dolby-ddp-odk-1.4.1-audio")
                .member(member));
        HeAacContainersTest.assertLikeFfmpeg(member.substring(member.lastIndexOf('/') + 1), data, 48000, channels);
    }

    @Nested
    @DisplayName("Low Complexity against reference waveforms")
    class LowComplexity {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"al00_08", "al00_11", "al00_12", "al00_16", "al00_22", "al00_24", "al00_32", "al00_44",
                "al00_48", "al00_64", "al00_88", "al00_96", "al01_48", "al02_48", "al03_48", "al04_48", "al05_08",
                "al05_22", "al05_48", "al05_96", "al14_48", "al17_48"})
        @DisplayName("mono and stereo streams at every sampling rate")
        void single(String stream) {
            var decoded = decode(stream);
            var reference = AacTestSupport.readWav(Fixtures.read(file(stream + ".wav")));
            assertEquals(reference.length, decoded.planes().length);
            for (var channel = 0; channel < reference.length; channel++) {
                assertConformant(reference[channel], decoded.planes()[channel], 1024, stream + " channel " + channel);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"al00sf_48", "al01sf_48", "al05sf_48", "al17sf_48"})
        @DisplayName("960-sample frames")
        void shortFrames(String stream) {
            var decoded = decode(stream);
            assertEquals(0, decoded.frames() % 960);
            var reference = AacTestSupport.readWav(Fixtures.read(file(stream + ".wav")));
            for (var channel = 0; channel < reference.length; channel++) {
                assertConformant(reference[channel], decoded.planes()[channel], 960, stream + " channel " + channel);
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "al06_48, 1024, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT",
                "al06sf_48, 960, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT",
                "al07_48, 1024, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT b00:BACK_LEFT b01:BACK_RIGHT l00:LOW_FREQUENCY",
                "al07sf_48, 960, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT b00:BACK_LEFT b01:BACK_RIGHT l00:LOW_FREQUENCY",
                "al16_48, 1024, f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT"
        })
        @DisplayName("multichannel streams (3.0, 5.1 with a coupling channel) in WAVE order")
        void multichannel(String stream, int frameLength, String channels) {
            assertChannels(stream, frameLength, channels);
        }

        @Test
        @DisplayName("a 48-channel program configuration")
        void programConfiguration() {
            assertProgramConfiguration("al08_48");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacConformanceTest#noiseStreams")
        @DisplayName("perceptual noise substitution reproduces the reference's short-term energy")
        void noiseSubstitution(String stream) {
            var frameLength = stream.contains("sf_") ? 960 : 1024;
            var decoded = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(
                    Mpeg4AudioVectors.file(stream + ".mp4")))).planes()[0];
            var reference = AacTestSupport.readWav(Fixtures.read(Mpeg4AudioVectors.file(stream + ".wav")))[0];
            var bestDeviation = Double.POSITIVE_INFINITY;
            for (var offset = 0; offset <= 2 * frameLength; offset += frameLength) {
                double deviation = 0;
                var blocks = 0;
                for (var start = 0; start + 2048 <= reference.length && start + offset + 2048 <= decoded.length; start += 2048) {
                    double a = 0;
                    double b = 0;
                    for (var i = start; i < start + 2048; i++) {
                        a += (double) reference[i] * reference[i];
                        b += (double) decoded[i + offset] * decoded[i + offset];
                    }
                    if (a < 1e-6) {
                        continue;
                    }
                    deviation += Math.abs(10 * Math.log10((b + 1e-12) / a));
                    blocks++;
                }
                bestDeviation = Math.min(bestDeviation, deviation / blocks);
            }
            assertTrue(bestDeviation < 1.0, stream + ": mean short-term energy deviation " + bestDeviation + " dB");
        }
    }

    /** The other sampling rates of the mono and stereo streams, by stream family. */
    private static final Map<String, String> RATES = Map.ofEntries(
            Map.entry("al00sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al01", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al01sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al02", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al02sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al03", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al03sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al04", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al04sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al05", "11 12 16 24 32 44 64 88"),
            Map.entry("al05sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al14", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al14sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al17", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al17sf", "08 11 12 16 22 24 32 44 64 88 96"));

    /** The perceptual noise substitution streams at every sampling rate, by stream family. */
    private static final Map<String, String> NOISE_RATES = Map.ofEntries(
            Map.entry("al18", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al18sf", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al19", "08 11 12 16 22 24 32 44 48 64 88 96"),
            Map.entry("al19sf", "08 11 12 16 22 24 32 44 48 64 88 96"));

    /** The multichannel streams at the sampling rates the 48 kHz tests do not cover, by stream family. */
    private static final Map<String, String> MULTICHANNEL_RATES = Map.ofEntries(
            Map.entry("al06", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al06sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al07", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al07sf", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al16", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al16sf", "08 11 12 16 22 24 32 44 48 64 88 96"));

    /** The reference waveforms of each multichannel family, with the speaker each one holds. */
    private static final Map<String, String> MULTICHANNEL_CHANNELS = Map.of(
            "al06", "f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT",
            "al07", "f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT b00:BACK_LEFT b01:BACK_RIGHT l00:LOW_FREQUENCY",
            "al16", "f00:FRONT_CENTER f01:FRONT_LEFT f02:FRONT_RIGHT");

    /** The rates of the 48-channel program configuration streams that the 48 kHz test does not cover. */
    private static final Map<String, String> PROGRAM_RATES = Map.ofEntries(
            Map.entry("al08", "08 11 12 16 22 24 32 44 64 88 96"),
            Map.entry("al08sf", "08 11 12 16 22 24 32 44 48 64 88 96"));

    static java.util.stream.Stream<String> otherRates() {
        return streams(RATES);
    }

    static java.util.stream.Stream<String> multichannelRates() {
        return streams(MULTICHANNEL_RATES);
    }

    static java.util.stream.Stream<String> programRates() {
        return streams(PROGRAM_RATES);
    }

    static java.util.stream.Stream<String> noiseStreams() {
        return streams(NOISE_RATES);
    }

    private static java.util.stream.Stream<String> streams(Map<String, String> rates) {
        return rates.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> java.util.Arrays.stream(entry.getValue().split(" "))
                        .map(rate -> entry.getKey() + "_" + rate));
    }

    /** Asserts that each named reference waveform of a multichannel stream is reproduced by its speaker. */
    static void assertChannels(String stream, int frameLength, String channels) {
        var decoded = decode(stream);
        assertEquals(0, decoded.frames() % frameLength, stream + " frames");
        for (var mapping : channels.split(" ")) {
            var parts = mapping.split(":");
            var index = decoded.layout().indexOf(Speaker.valueOf(parts[1]));
            assertTrue(index >= 0, stream + " lacks " + parts[1]);
            var reference = AacTestSupport.readWav(Fixtures.read(file(stream + "_" + parts[0] + ".wav")))[0];
            assertConformant(reference, decoded.planes()[index], frameLength, stream + " " + parts[1]);
        }
    }

    /**
     * Asserts that a stream of the 48-channel program configuration decodes 48 channels, the front center third, and
     * that a front, a back and a side reference waveform are each reproduced by a channel.
     */
    static void assertProgramConfiguration(String stream) {
        var frameLength = stream.contains("sf_") ? 960 : 1024;
        var decoded = decode(stream);
        assertEquals(48, decoded.planes().length);
        assertEquals(Speaker.FRONT_CENTER, decoded.layout().speakers().get(2));
        for (var name : new String[]{"f00", "b00", "s17"}) {
            var reference = AacTestSupport.readWav(Fixtures.read(file(stream + "_" + name + ".wav")))[0];
            var matched = false;
            for (var plane : decoded.planes()) {
                var error = error(reference, plane, frameLength);
                matched |= error[0] <= RMS_LIMIT && error[1] <= PEAK_LIMIT;
            }
            assertTrue(matched, stream + ": no channel reproduces " + name);
        }
        var front = AacTestSupport.readWav(Fixtures.read(file(stream + "_f00.wav")))[0];
        assertConformant(front, decoded.planes()[decoded.layout().indexOf(Speaker.FRONT_CENTER)], frameLength,
                stream + " center");
    }

    @Nested
    @DisplayName("Low Complexity at the other sampling rates against reference waveforms")
    class OtherRates {
        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.MethodSource("com.github.auties00.smalt.audio.aac.AacConformanceTest#otherRates")
        @DisplayName("mono and stereo streams, 1024- and 960-sample frames, at 8 to 96 kHz")
        void stream(String stream) {
            var frameLength = stream.contains("sf_") ? 960 : 1024;
            var decoded = AacTestSupport.decodeAll(AacTestSupport.parseMp4(Fixtures.read(
                    Mpeg4AudioVectors.file(stream + ".mp4"))));
            assertEquals(0, decoded.frames() % frameLength);
            var reference = AacTestSupport.readWav(Fixtures.read(Mpeg4AudioVectors.file(stream + ".wav")));
            assertEquals(reference.length, decoded.planes().length);
            for (var channel = 0; channel < reference.length; channel++) {
                assertConformant(reference[channel], decoded.planes()[channel], frameLength, stream + " channel " + channel);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacConformanceTest#multichannelRates")
        @DisplayName("multichannel streams (3.0, 5.1 with a coupling channel), 1024- and 960-sample frames, at 8 to 96 kHz")
        void multichannel(String stream) {
            var family = stream.substring(0, stream.indexOf('_')).replace("sf", "");
            assertChannels(stream, stream.contains("sf_") ? 960 : 1024, MULTICHANNEL_CHANNELS.get(family));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacConformanceTest#programRates")
        @DisplayName("the 48-channel program configuration, 1024- and 960-sample frames, at 8 to 96 kHz")
        void programConfiguration(String stream) {
            assertProgramConfiguration(stream);
        }
    }
}
