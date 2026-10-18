package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips the encoder through ffmpeg (an independent decoder) and compares it with ffmpeg's
 * libopus encoder at equal settings: bitrate, segmental SNR and log-spectral distance against
 * the original, clean decoding of every configuration, and how many packets are bit-identical.
 *
 * <p>Speech comes from a public-domain LibriVox recording (pinned by SHA-256); music is the
 * decoded RFC 8251 {@code testvector07}.
 */
class OpusEncoderQualityTest {
    private static final String SPEECH = "librivox-art_of_war_03-04_64kb.mp3";
    private static final String SPEECH_URL = "https://archive.org/download/art_of_war_librivox/art_of_war_03-04_sun_tzu_64kb.mp3";
    private static final String SPEECH_SHA256 = "57e879fe7822578fd22010f1280185799a119a3482e025d9a71f49878ff45ba7";

    /** Result of one encoding: packets, the Ogg file and ffmpeg's decode of it. */
    record Encoded(List<byte[]> packets, Path ogg, float[] decoded, double bitrate) {
    }

    /** Returns interleaved float PCM of the LibriVox speech at the given rate and channel count. */
    static float[] speechInput(int sampleRate, int channels, int seconds) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var mp3 = Conformance.fetch(SPEECH, SPEECH_URL, SPEECH_SHA256);
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-ss", "20", "-t",
                Integer.toString(seconds), "-i", mp3.toString(), "-ac", Integer.toString(channels), "-ar",
                Integer.toString(sampleRate), "-f", "f32le", "-"));
        return floats(raw);
    }

    /** Returns interleaved float PCM of the music vector at 48 kHz stereo. */
    static float[] musicInput(int seconds) {
        var pcm = OpusVectors.readPcm16(OpusVectors.directory().resolve("testvector07.dec"));
        var length = Math.min(pcm.length, seconds * 48000 * 2);
        var out = new float[length];
        for (var i = 0; i < length; i++) {
            out[i] = pcm[i] * (1.0f / 32768);
        }
        return out;
    }

    static float[] floats(byte[] raw) {
        var out = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    static byte[] bytes(float[] samples) {
        var buffer = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(samples);
        return buffer.array();
    }

    static Path temp(String suffix, byte[] content) {
        try {
            var file = Files.createTempFile("opus-test-", suffix);
            file.toFile().deleteOnExit();
            Files.write(file, content);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Encodes with the encoder under test and decodes the Ogg file with ffmpeg. */
    static Encoded encodeOurs(OpusEncoderOptions config, float[] input) {
        var encoder = new OpusEncoder(config);
        var channels = config.channels();
        var frames = input.length / channels;
        var packets = new ArrayList<Packet>();
        var chunk = 4410;
        for (var position = 0; position < frames; position += chunk) {
            var count = Math.min(chunk, frames - position);
            var planes = new float[channels][count];
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < count; i++) {
                    planes[c][i] = input[(position + i) * channels + c];
                }
            }
            encoder.encode(new AudioBuffer(encoder.layout(), config.sampleRate(), planes, count), packets::add);
        }
        encoder.flush(packets::add);
        var data = new ArrayList<byte[]>();
        var durations = new int[packets.size()];
        long bytes = 0;
        for (var i = 0; i < packets.size(); i++) {
            data.add(packets.get(i).data());
            durations[i] = (int) (packets.get(i).duration() * 48000 / config.sampleRate());
            bytes += packets.get(i).size();
        }
        var head = encoder.codecConfig().orElseThrow();
        var preSkip = OpusHead.parse(head, MediaLimits.DEFAULT).preSkip();
        var ogg = temp(".opus", OggOpusIO.write(head, data, durations, preSkip, (long) frames * 48000 / config.sampleRate()));
        Oracle.assertDecodesCleanly(ogg);
        var decoded = decodeWithFfmpeg(ogg, config.sampleRate(), channels);
        return new Encoded(data, ogg, decoded, bytes * 8.0 * config.sampleRate() / ((double) data.size() * config.frameSize()));
    }

    /** Encodes with ffmpeg's libopus at the same settings and decodes the result. */
    static Encoded encodeLibopus(OpusEncoderOptions config, float[] input) {
        var pcm = temp(".f32", bytes(input));
        var ogg = pcm.resolveSibling(pcm.getFileName() + ".opus");
        ogg.toFile().deleteOnExit();
        var application = switch (config.application()) {
            case VOIP -> "voip";
            case AUDIO -> "audio";
            case RESTRICTED_LOWDELAY -> "lowdelay";
        };
        var vbr = switch (config.bitrateMode()) {
            case VBR -> "on";
            case CONSTRAINED_VBR -> "constrained";
            case CBR -> "off";
        };
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-f", "f32le", "-ar",
                Integer.toString(config.sampleRate()), "-ac", Integer.toString(config.channels()), "-i", pcm.toString(),
                "-c:a", "libopus", "-sample_fmt", "flt", "-application", application, "-b:a", Integer.toString(config.bitrate().getAsInt()),
                "-vbr", vbr, "-compression_level", Integer.toString(config.complexity()), "-frame_duration",
                Double.toString(config.frameDurationMicros() / 1000.0), ogg.toString()));
        var stream = OggOpusIO.read(readAll(ogg));
        long bytes = 0;
        for (var packet : stream.packets()) {
            bytes += packet.length;
        }
        var decoded = decodeWithFfmpeg(ogg, config.sampleRate(), config.channels());
        return new Encoded(stream.packets(), ogg, decoded,
                bytes * 8.0 * config.sampleRate() / ((double) stream.packets().size() * config.frameSize()));
    }

    static byte[] readAll(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static float[] decodeWithFfmpeg(Path ogg, int sampleRate, int channels) {
        return floats(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", ogg.toString(),
                "-ar", Integer.toString(sampleRate), "-ac", Integer.toString(channels), "-f", "f32le", "-")));
    }

    static float[] channel(float[] interleaved, int channels, int channel) {
        var out = new float[interleaved.length / channels];
        for (var i = 0; i < out.length; i++) {
            out[i] = interleaved[i * channels + channel];
        }
        return out;
    }

    /** Returns the number of leading packets that are byte-identical in both streams. */
    static int identicalPrefix(List<byte[]> a, List<byte[]> b) {
        var n = Math.min(a.size(), b.size());
        for (var i = 0; i < n; i++) {
            if (!java.util.Arrays.equals(a.get(i), b.get(i))) {
                return i;
            }
        }
        return n;
    }

    static int identicalCount(List<byte[]> a, List<byte[]> b) {
        var n = Math.min(a.size(), b.size());
        var count = 0;
        for (var i = 0; i < n; i++) {
            if (java.util.Arrays.equals(a.get(i), b.get(i))) {
                count++;
            }
        }
        return count;
    }

    /** Compares both encoders on one input; returns a one-line summary. */
    static String compare(OpusEncoderOptions config, float[] input, double maxHz, double snrMargin, double lsdMargin) {
        var ours = encodeOurs(config, input);
        var reference = encodeLibopus(config, input);
        assertEquals(input.length, ours.decoded().length, "decoded length after end trimming");
        var channels = config.channels();
        var summary = new StringBuilder();
        for (var c = 0; c < channels; c++) {
            var original = channel(input, channels, c);
            var ourSnr = AudioMetrics.segmentalSnr(original, channel(ours.decoded(), channels, c), config.sampleRate());
            var refSnr = AudioMetrics.segmentalSnr(original, channel(reference.decoded(), channels, c), config.sampleRate());
            var ourLsd = AudioMetrics.logSpectralDistance(original, channel(ours.decoded(), channels, c), config.sampleRate(), maxHz);
            var refLsd = AudioMetrics.logSpectralDistance(original, channel(reference.decoded(), channels, c), config.sampleRate(), maxHz);
            summary.append(String.format("ch%d segSNR %.2f/%.2f dB LSD %.3f/%.3f dB; ", c, ourSnr, refSnr, ourLsd, refLsd));
            assertTrue(ourSnr >= refSnr - snrMargin, "segmental SNR " + ourSnr + " vs libopus " + refSnr);
            assertTrue(ourLsd <= refLsd + lsdMargin, "log-spectral distance " + ourLsd + " vs libopus " + refLsd);
        }
        var ratio = ours.bitrate() / reference.bitrate();
        summary.append(String.format("bitrate %.0f/%.0f bps (%.3f); identical packets %d/%d, prefix %d",
                ours.bitrate(), reference.bitrate(), ratio, identicalCount(ours.packets(), reference.packets()),
                reference.packets().size(), identicalPrefix(ours.packets(), reference.packets())));
        assertTrue(Math.abs(ratio - 1) <= 0.10, "bitrate ratio " + ratio);
        System.err.println("[opus-quality] " + config + ": " + summary);
        return summary.toString();
    }

    @Nested
    @DisplayName("against ffmpeg's libopus")
    class AgainstLibopus {
        @Test
        @DisplayName("forSpeech(16 kb/s) on speech: same bitrate within 10 %, same quality within a small margin")
        void speech() {
            compare(OpusEncoderOptions.forSpeech(16000), speechInput(16000, 1, 30), 8000, 0.5, 0.3);
        }

        @ParameterizedTest(name = "{0} bps")
        @CsvSource({"96000", "128000"})
        @DisplayName("48 kHz stereo music in the AUDIO application")
        void music(int bitrate) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var config = OpusEncoderOptions.defaults().toBuilder().bitrate(bitrate).build();
            compare(config, musicInput(10), 20000, 0.5, 0.3);
        }

        @Test
        @DisplayName("RESTRICTED_LOWDELAY, 20 ms, 32 kb/s")
        void lowDelay() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var config = OpusEncoderOptions.builder().format(48000, 1).application(OpusApplication.RESTRICTED_LOWDELAY).bitrate(32000)
                    .bitrateMode(OpusBitrateMode.VBR).complexity(10).frameDurationMicros(20000).build();
            compare(config, speechInput(48000, 1, 10), 20000, 0.5, 0.3);
        }
    }

    @Nested
    @DisplayName("every setting decodes cleanly")
    class Settings {
        @ParameterizedTest(name = "complexity {0}")
        @CsvSource({"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"})
        @DisplayName("every complexity, on speech and music")
        void complexity(int complexity) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var speech = OpusEncoderOptions.builder().format(16000, 1).application(OpusApplication.VOIP).bitrate(16000)
                    .bitrateMode(OpusBitrateMode.VBR).complexity(complexity).frameDurationMicros(20000).build();
            checkReasonable(speech, speechInput(16000, 1, 2), 8000);
            var music = OpusEncoderOptions.builder().format(48000, 2).application(OpusApplication.AUDIO).bitrate(96000)
                    .bitrateMode(OpusBitrateMode.VBR).complexity(complexity).frameDurationMicros(20000).build();
            checkReasonable(music, musicInput(2), 20000);
        }

        @ParameterizedTest(name = "{0} us, {1}")
        @CsvSource({"2500,AUDIO", "5000,AUDIO", "10000,VOIP", "20000,VOIP", "40000,VOIP", "60000,VOIP",
                "40000,AUDIO", "60000,AUDIO", "80000,VOIP", "100000,AUDIO", "120000,VOIP", "2500,RESTRICTED_LOWDELAY",
                "60000,RESTRICTED_LOWDELAY"})
        @DisplayName("every frame duration")
        void frameSize(int micros, OpusApplication application) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var config = OpusEncoderOptions.builder().format(48000, 2).application(application).bitrate(64000)
                    .bitrateMode(OpusBitrateMode.VBR).complexity(10).frameDurationMicros(micros).build();
            checkReasonable(config, speechInput(48000, 2, 2), 20000);
        }

        @ParameterizedTest(name = "{0} Hz {1} ch {2}")
        @CsvSource({"8000,1,CBR", "12000,1,VBR", "16000,2,CONSTRAINED_VBR", "24000,1,VBR", "24000,2,CBR", "48000,1,CONSTRAINED_VBR"})
        @DisplayName("every input rate and rate control")
        void rates(int rate, int channels, OpusBitrateMode mode) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var config = OpusEncoderOptions.builder().format(rate, channels).application(OpusApplication.AUDIO).bitrate(24000 * channels)
                    .bitrateMode(mode).complexity(9).frameDurationMicros(20000).build();
            checkReasonable(config, speechInput(rate, channels, 2), rate / 2.0);
        }

        void checkReasonable(OpusEncoderOptions config, float[] input, double maxHz) {
            var ours = encodeOurs(config, input);
            assertEquals(input.length, ours.decoded().length, "decoded length");
            for (var c = 0; c < config.channels(); c++) {
                var lsd = AudioMetrics.logSpectralDistance(channel(input, config.channels(), c),
                        channel(ours.decoded(), config.channels(), c), config.sampleRate(), maxHz);
                assertTrue(lsd < 12, "log-spectral distance " + lsd);
            }
        }
    }
}
