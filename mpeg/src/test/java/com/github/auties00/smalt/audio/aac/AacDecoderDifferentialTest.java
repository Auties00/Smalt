package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the decoder with ffmpeg's native AAC decoder on streams produced by ffmpeg's native
 * encoder across bitrates, sampling rates, channel layouts, coders and coding tools: the output
 * must have exactly ffmpeg's sample count and an SNR of at least 80 dB against it (70 dB for AAC
 * Main, whose predictor ffmpeg evaluates in single precision), except for perceptual noise
 * substitution, whose pseudo-random noise is compared by band energy. An ffmpeg-made ADTS AAC Main
 * stream also checks that seeking restarts the predictors exactly when the object type comes from
 * the ADTS profile.
 */
class AacDecoderDifferentialTest {
    private static final String TONES = "0.3*sin(2*PI*440*t)+0.2*sin(2*PI*1234*t)+0.1*sin(2*PI*5000*t)+0.02*sin(2*PI*11000*t)";

    private static final String CLICKS = "0.7*exp(-mod(t\\,0.25)*200)*sin(2*PI*3000*t)+0.1*sin(2*PI*300*t)";

    private static final String CHIRP = "0.3*sin(2*PI*(100+3000*t)*t)";

    /** Returns an aevalsrc expression with one distinct signal per channel. */
    static String signal(String kind, int channels, int rate, double seconds) {
        var expressions = new ArrayList<String>();
        for (var c = 0; c < channels; c++) {
            var base = switch (kind) {
                case "tones" -> TONES;
                case "clicks" -> CLICKS;
                case "chirp" -> CHIRP;
                case "noise" -> "0.4*(random(" + c + ")-0.5)";
                default -> throw new IllegalArgumentException(kind);
            };
            expressions.add(base.replace("*t)", "*t+" + c + ")") + "*(1-0.05*" + c + ")");
        }
        return "aevalsrc=" + String.join("|", expressions) + ":s=" + rate + ":d=" + seconds;
    }

    static Path fixture(String kind, int channels, int rate, double seconds, String extension, String... options) {
        var output = new ArrayList<>(List.of("-c:a", "aac"));
        output.addAll(List.of(options));
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", signal(kind, channels, rate, seconds)), output);
    }

    static AacTestSupport.Stream parse(Path file) {
        var bytes = Fixtures.read(file);
        return file.toString().endsWith(".aac") ? AacTestSupport.parseAdts(bytes) : AacTestSupport.parseMp4(bytes);
    }

    /**
     * Decodes with both decoders and asserts equal sample counts (after applying an MP4 edit
     * list, which ffmpeg applies) and at least 80 dB SNR per channel.
     */
    static void assertMatchesFfmpeg(Path file) {
        assertMatchesFfmpeg(file, 80);
    }

    /** Decodes with both decoders and asserts equal sample counts and a minimum SNR per channel. */
    static void assertMatchesFfmpeg(Path file, double minimumSnr) {
        var stream = parse(file);
        var planes = trimmed(stream, AacTestSupport.decodeAll(stream).planes());
        var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), planes.length);
        assertEquals(reference[0].length, planes[0].length, "sample count");
        for (var channel = 0; channel < reference.length; channel++) {
            var snr = AacTestSupport.snr(new float[][]{reference[channel]}, new float[][]{planes[channel]});
            assertTrue(snr >= minimumSnr, "channel " + channel + " SNR " + snr + " dB");
        }
    }

    /**
     * Drops the leading samples an MP4 edit list skips, as a container would through the track's
     * skip samples; ffmpeg's raw output drops them too but keeps the trailing padding.
     */
    static float[][] trimmed(AacTestSupport.Stream stream, float[][] planes) {
        if (stream.presented() < 0) {
            return planes;
        }
        assertTrue(planes[0].length >= stream.skip() + stream.presented(), "the decode covers the presented samples");
        var out = new float[planes.length][];
        for (var c = 0; c < planes.length; c++) {
            out[c] = java.util.Arrays.copyOfRange(planes[c], (int) stream.skip(), planes[c].length);
        }
        return out;
    }

    /**
     * Decodes with both decoders and asserts equal sample counts and that every decoded channel
     * matches a distinct ffmpeg channel at 80 dB or more, for layouts whose channel order the
     * two decoders define differently.
     */
    static void assertMatchesFfmpegUnordered(Path file) {
        var stream = parse(file);
        var planes = trimmed(stream, AacTestSupport.decodeAll(stream).planes());
        var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), planes.length);
        assertEquals(reference[0].length, planes[0].length, "sample count");
        var used = new boolean[planes.length];
        for (var plane : planes) {
            var matched = false;
            for (var channel = 0; channel < reference.length && !matched; channel++) {
                if (!used[channel] && AacTestSupport.snr(new float[][]{reference[channel]}, new float[][]{plane}) >= 80) {
                    used[channel] = true;
                    matched = true;
                }
            }
            assertTrue(matched, "a decoded channel matches no ffmpeg channel");
        }
    }

    @Nested
    @DisplayName("bitrates")
    class Bitrates {
        @ParameterizedTest(name = "{0} bps, {1} channels")
        @CsvSource({"16000, 1", "24000, 1", "32000, 2", "48000, 2", "64000, 2", "96000, 2", "128000, 2",
                "192000, 2", "256000, 2", "320000, 2"})
        @DisplayName("match ffmpeg on tones")
        void tones(int bitrate, int channels) {
            assertMatchesFfmpeg(fixture("tones", channels, 48000, 3, "aac", "-b:a", Integer.toString(bitrate)));
        }

        @ParameterizedTest(name = "{0} bps")
        @ValueSource(ints = {32000, 64000, 128000, 256000})
        @DisplayName("match ffmpeg on transients coded with short windows")
        void transients(int bitrate) {
            assertMatchesFfmpeg(fixture("clicks", 2, 44100, 3, "aac", "-b:a", Integer.toString(bitrate)));
        }
    }

    @Nested
    @DisplayName("sampling rates")
    class SamplingRates {
        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {7350, 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000})
        @DisplayName("match ffmpeg at every standard rate")
        void rate(int rate) {
            assertMatchesFfmpeg(fixture("chirp", 2, rate, 2, "aac", "-b:a", Integer.toString(Math.min(160000, rate * 3))));
        }
    }

    @Nested
    @DisplayName("channel layouts")
    class Layouts {
        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6})
        @DisplayName("match ffmpeg channel by channel in WAVE order")
        void configurations(int channels) {
            var file = fixture("tones", channels, 48000, 2, "aac", "-b:a", Integer.toString(64000 * channels));
            assertMatchesFfmpeg(file);
            assertEquals(ChannelLayout.defaultFor(channels).channels(), AacTestSupport.decodeAll(parse(file)).planes().length);
        }

        @Test
        @DisplayName("decode the 5.1 layout in WAVE order")
        void surround() {
            var decoded = AacTestSupport.decodeAll(parse(fixture("tones", 6, 48000, 1, "aac", "-b:a", "384000")));
            assertEquals(ChannelLayout.SURROUND_5_1, decoded.layout());
        }

        @Test
        @DisplayName("decode 7.1 (channel configuration 7) with every channel matching one of ffmpeg's")
        void sevenOne() {
            var file = fixture("tones", 8, 48000, 2, "aac", "-b:a", "512000");
            assertEquals(8, AacTestSupport.decodeAll(parse(file)).planes().length);
            assertMatchesFfmpegUnordered(file);
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {2, 6, 8})
        @DisplayName("decode program configurations carried in band (ADTS)")
        void inBandProgramConfiguration(int channels) {
            var file = fixture("tones", channels, 48000, 2, "aac", "-b:a", Integer.toString(64000 * channels), "-aac_pce", "1");
            var stream = parse(file);
            assertEquals(0, (stream.config()[1] >> 3) & 15, "the fixture signals its layout through a PCE");
            assertMatchesFfmpegUnordered(file);
        }

        @Test
        @DisplayName("decode plain frames under a hand-built program configuration")
        void handBuiltProgramConfiguration() {
            var file = fixture("tones", 6, 48000, 2, "aac", "-b:a", "384000");
            var stream = parse(file);
            var writer = new com.github.auties00.smalt.internal.bits.MsbBitWriter();
            writer.write(2, 5);
            writer.write(3, 4);
            writer.write(0, 4);
            writer.write(0, 3);
            writer.write(0, 4);
            writer.write(1, 2);
            writer.write(3, 4);
            writer.write(2, 4);
            writer.write(0, 4);
            writer.write(1, 4);
            writer.write(1, 2);
            writer.write(0, 3);
            writer.write(0, 4);
            writer.write(0, 3);
            writer.write(0, 1);
            writer.write(0, 4);
            writer.write(1, 1);
            writer.write(0, 4);
            writer.write(1, 1);
            writer.write(1, 4);
            writer.write(0, 4);
            writer.alignWithZeros();
            writer.write(0, 8);
            var pce = new AacTestSupport.Stream(writer.toByteArray(), stream.frames(), 48000, 6);
            var mine = AacTestSupport.decodeAll(pce);
            assertEquals(ChannelLayout.SURROUND_5_1, mine.layout());
            var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), 6);
            assertEquals(reference[0].length, mine.frames());
            for (var channel = 0; channel < 6; channel++) {
                var snr = AacTestSupport.snr(new float[][]{reference[channel]}, new float[][]{mine.planes()[channel]});
                assertTrue(snr >= 80, "channel " + channel + " SNR " + snr);
            }
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {6, 8})
        @DisplayName("decode program configurations carried in the AudioSpecificConfig (MP4)")
        void configProgramConfiguration(int channels) {
            assertMatchesFfmpegUnordered(fixture("tones", channels, 48000, 2, "m4a", "-b:a", Integer.toString(64000 * channels), "-aac_pce", "1"));
        }
    }

    @Nested
    @DisplayName("coders and tools")
    class Tools {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "twoloop default, -aac_coder, twoloop, -b:a, 128000",
                "fast coder, -aac_coder, fast, -b:a, 128000",
                "forced M/S, -aac_ms, 1, -b:a, 96000",
                "intensity stereo, -aac_is, 1, -b:a, 40000",
                "TNS, -aac_tns, 1, -b:a, 64000",
                "no TNS, -aac_tns, 0, -b:a, 64000",
                "M4A container, -aac_pns, 0, -b:a, 128000"
        })
        @DisplayName("match ffmpeg with each coder and coding tool")
        void tools(String name, String option, String value, String bitrateOption, String bitrate) {
            var extension = name.startsWith("M4A") ? "m4a" : "aac";
            for (var kind : new String[]{"tones", "clicks"}) {
                assertMatchesFfmpeg(fixture(kind, 2, 48000, 3, extension, option, value, bitrateOption, bitrate, "-aac_pns", "0"));
            }
        }

        @ParameterizedTest(name = "{0} bps")
        @ValueSource(ints = {24000, 48000, 96000})
        @DisplayName("match ffmpeg on noise with PNS disabled")
        void noise(int bitrate) {
            assertMatchesFfmpeg(fixture("noise", 2, 48000, 3, "aac", "-b:a", Integer.toString(bitrate), "-aac_pns", "0"));
        }

        @ParameterizedTest(name = "{0} bps")
        @ValueSource(ints = {24000, 48000})
        @DisplayName("substitute noise with the band energies ffmpeg produces")
        void noiseSubstitution(int bitrate) {
            var file = fixture("noise", 2, 48000, 3, "aac", "-b:a", Integer.toString(bitrate), "-aac_pns", "1", "-aac_is", "0");
            var mine = AacTestSupport.decodeAll(parse(file));
            var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), 2);
            assertEquals(reference[0].length, mine.frames());
            for (var channel = 0; channel < 2; channel++) {
                var deviation = PnsEnergy.meanBandDeviation(reference[channel], mine.planes()[channel], 48000);
                assertTrue(deviation < 1.0, "mean band energy deviation " + deviation + " dB");
            }
        }
    }

    @Nested
    @DisplayName("AAC Main and LTP")
    class Prediction {
        @ParameterizedTest(name = "{0}, {2} channels, {3}")
        @CsvSource({"aac_main, -aac_pred, 1, tones", "aac_main, -aac_pred, 2, tones", "aac_main, -aac_pred, 2, chirp",
                "aac_ltp, -aac_ltp, 1, tones", "aac_ltp, -aac_ltp, 2, tones", "aac_ltp, -aac_ltp, 2, clicks"})
        @DisplayName("match ffmpeg on streams its encoder predicts")
        void profiles(String profile, String option, int channels, String kind) {
            // ffmpeg evaluates the AAC Main predictor in single precision and this decoder in double, as the
            // ISO/IEC 14496-26 reference waveforms require, so the two drift apart below 80 dB; ffmpeg's LTP
            // encoder emits no LTP data, which leaves the LTP syntax and predictor state to this comparison
            // and the prediction itself to the conformance streams
            var minimum = profile.equals("aac_main") ? 70 : 80;
            assertMatchesFfmpeg(fixture(kind, channels, 48000, 2, "aac", "-strict", "-2", "-profile:a", profile, option, "1",
                    "-aac_is", "0", "-b:a", Integer.toString(48000 * channels)), minimum);
        }

        @Test
        @DisplayName("seek in ADTS AAC Main exactly, though the profile comes from the ADTS header")
        void adtsSeek() {
            // ffmpeg's encoder sets prediction_used in nearly every frame of this stream
            var bytes = Fixtures.read(fixture("tones", 1, 44100, 3, "aac", "-strict", "-2", "-profile:a", "aac_main",
                    "-aac_pred", "1", "-b:a", "24000"));
            float[][] linear;
            int rate;
            try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                rate = reader.sampleRate();
                linear = AacObjectTypesConformanceTest.Seeking.readAll(reader);
            }
            for (var micros : new long[]{1_000_000, 2_345_678}) {
                try (var reader = AudioReader.open(MediaSource.of(bytes))) {
                    reader.seek(micros);
                    var tail = AacObjectTypesConformanceTest.Seeking.readAll(reader);
                    var start = (int) (micros * rate / 1_000_000);
                    for (var channel = 0; channel < linear.length; channel++) {
                        assertArrayEquals(Arrays.copyOfRange(linear[channel], start, linear[channel].length),
                                tail[channel], "channel " + channel + " after a seek to " + micros);
                    }
                }
            }
        }
    }
}
