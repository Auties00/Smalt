package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.alac.AlacSignals.Shape;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Writes {@link AlacEncoder} streams into M4A and Matroska files with Smalt's muxers and decodes them with independent
 * decoders: ffmpeg's ALAC decoder (every sample size and channel count, bit for bit, after the container validators)
 * and, on macOS, Core Audio through {@code afconvert}. The files carry the final configuration, so ffprobe also
 * reports the stream's real sample size, channel count and rate.
 */
@DisplayName("ALAC encoder output decoded by other decoders")
class AlacEncoderInteropTest {
    /** Encodes samples in the decoder's speaker order and writes them to a file with a muxer. */
    static Path write(String name, int[][] samples, int bits, int rate, Function<MediaSink, Muxer> muxer) {
        var channels = samples.length;
        var layout = new AlacDecoder(AudioTrack.builder().codec(Codec.ALAC).timeBase(Rational.timeBaseOf(rate))
                .sampleRate(rate).codecConfig(new AlacEncoder(AlacEncoderOptions.builder()
                        .layout(AlacSignals.bitstreamLayout(channels)).build()).codecConfig().orElseThrow())
                .build()).layout();
        var encoder = new AlacEncoder(AlacEncoderOptions.builder().format(rate, layout).bitsPerSample(bits).build());
        var packets = new ArrayList<Packet>();
        encoder.encode(samples, 0, samples[0].length, packets::add);
        encoder.flush(packets::add);
        var file = Scratch.directory("alac-interop").resolve(name);
        var track = AudioTrack.builder().id(1).codec(Codec.ALAC).timeBase(Rational.timeBaseOf(rate))
                .codecConfig(encoder.codecConfig().orElseThrow()).sampleRate(rate).layout(layout).build();
        try (var sink = MediaSink.of(file); var output = muxer.apply(sink)) {
            output.addTrack(track);
            for (var packet : packets) {
                output.writePacket(packet.withTrackId(1));
            }
            output.updateCodecConfig(1, encoder.codecConfig().orElseThrow());
            output.finish();
        }
        return file;
    }

    /** Returns samples left-justified to 32 bits and interleaved, the form of ffmpeg's {@code s32le} output. */
    static int[] justified(int[][] samples, int bits) {
        var interleaved = AlacSignals.interleave(samples);
        for (var i = 0; i < interleaved.length; i++) {
            interleaved[i] <<= 32 - bits;
        }
        return interleaved;
    }

    static void assertSamples(int[] expected, int[] actual, int channels, String what) {
        assertEquals(expected.length, actual.length, what + ": sample count");
        for (var i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(what + ": sample " + i / channels + " channel " + i % channels + ": expected " + expected[i]
                        + " but was " + actual[i]);
            }
        }
    }

    static Stream<Arguments> ffmpegCases() {
        var arguments = new ArrayList<Arguments>();
        for (var bits : new int[]{16, 20, 24, 32}) {
            for (var channels = 1; channels <= 8; channels++) {
                arguments.add(Arguments.of(bits, channels, channels % 2 == 0 ? "m4a" : "mkv"));
            }
            arguments.add(Arguments.of(bits, 2, "mkv"));
            arguments.add(Arguments.of(bits, 1, "m4a"));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}-bit {1} ch {2}")
    @MethodSource("ffmpegCases")
    @DisplayName("ffmpeg decodes the file to every input sample, and the file validates")
    void ffmpeg(int bits, int channels, String container) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var samples = AlacSignals.samples(Shape.MUSIC, channels, 30_000, bits, bits * 10L + channels);
        // a noise burst, which escapes, inside the music; ffmpeg 7.1 rejects an escaped pair of 32-bit samples (it
        // checks the 33-bit size of a compressed pair before reading the escape flag), so 32-bit pairs stay compressed
        if (bits < 32 || channels == 1) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(AlacSignals.samples(Shape.NOISE, 1, 4096, bits, c)[0], 0, samples[c], 8192, 4096);
            }
        }
        Function<MediaSink, Muxer> muxer = container.equals("m4a")
                ? sink -> new Mp4Muxer(sink, Mp4MuxerOptions.m4a()) : MatroskaMuxer::new;
        var file = write(bits + "-" + channels + "." + container, samples, bits, 48000, muxer);
        Oracle.assertDecodesCleanly(file);
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-c:a", "alac", "-i",
                file.toString(), "-f", "s32le", "-"));
        var decoded = new int[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(decoded);
        assertSamples(justified(samples, bits), decoded, channels, bits + "-bit " + channels + " ch " + container);
        var probe = Oracle.probe(file);
        assertTrue(probe.contains("\"codec_name\": \"alac\""), probe);
        assertTrue(probe.contains("\"channels\": " + channels), probe);
        assertTrue(probe.contains("\"sample_rate\": \"48000\""), probe);
        assertTrue(probe.contains("\"bits_per_raw_sample\": \"" + bits + "\""), probe);
    }

    @Test
    @DisplayName("Core Audio decodes M4A files to every input sample")
    void afconvert() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Skip.unless(os.startsWith("mac"), "platform:macos", "afconvert is part of macOS");
        var afconvert = Oracle.find("afconvert").orElseThrow(() -> new AssertionError("macOS without afconvert"));
        for (var bits : new int[]{16, 24, 32}) {
            for (var channels = 1; channels <= 2; channels++) {
                var samples = AlacSignals.samples(Shape.MUSIC, channels, 20_000, bits, bits + channels);
                var file = write("af-" + bits + "-" + channels + ".m4a", samples, bits, 44100,
                        sink -> new Mp4Muxer(sink, Mp4MuxerOptions.m4a()));
                var wav = file.resolveSibling("af-" + bits + "-" + channels + ".wav");
                var result = Oracle.execute(afconvert, Duration.ofMinutes(1), null, List.of("-f", "WAVE", "-d",
                        "LEI" + bits, file.toString(), wav.toString()));
                assertEquals(0, result.exitCode(), result.stderr());
                assertSamples(AlacSignals.interleave(samples), wavSamples(wav, bits), channels,
                        "afconvert " + bits + "-bit " + channels + " ch");
            }
        }
    }

    /** Reads the integer samples of a WAVE file's data chunk. */
    static int[] wavSamples(Path wav, int bits) {
        byte[] data;
        try {
            data = Files.readAllBytes(wav);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var position = 12;
        while (position + 8 <= data.length) {
            var id = new String(data, position, 4, java.nio.charset.StandardCharsets.US_ASCII);
            var size = buffer.getInt(position + 4);
            if (id.equals("data")) {
                var bytes = bits / 8;
                var samples = new int[size / bytes];
                for (var i = 0; i < samples.length; i++) {
                    var offset = position + 8 + i * bytes;
                    var value = 0;
                    for (var b = 0; b < bytes; b++) {
                        value |= (data[offset + b] & 0xFF) << (8 * b);
                    }
                    samples[i] = value << (32 - bits) >> (32 - bits);
                }
                return samples;
            }
            position += 8 + size + (size & 1);
        }
        throw new AssertionError("no data chunk in " + wav);
    }

    @Test
    @DisplayName("the muxed configuration is the final one, with the largest packet size")
    void finalConfig() {
        var samples = AlacSignals.samples(Shape.MUSIC, 2, 50_000, 16, 1);
        for (var container : List.of("m4a", "mkv")) {
            Function<MediaSink, Muxer> muxer = container.equals("m4a")
                    ? sink -> new Mp4Muxer(sink, Mp4MuxerOptions.m4a()) : MatroskaMuxer::new;
            var file = write("final." + container, samples, 16, 44100, muxer);
            var source = com.github.auties00.smalt.io.MediaSource.of(file);
            try (var demuxer = container.equals("m4a") ? new com.github.auties00.smalt.container.mp4.Mp4Demuxer(source)
                    : new com.github.auties00.smalt.container.matroska.MatroskaDemuxer(source)) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var config = ByteBuffer.wrap(com.github.auties00.smalt.internal.codec.AudioConfigs.alacConfig(
                        track.codecConfig().orElseThrow()));
                var largest = 0;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    largest = Math.max(largest, packet.size());
                }
                assertEquals(largest, config.getInt(12), container + " maxFrameBytes");
                assertTrue(config.getInt(16) > 0, container + " avgBitRate");
                if (container.equals("m4a")) {
                    assertEquals(16, track.bitsPerSample(), "sample size of the sample entry");
                }
            }
        }
    }
}
