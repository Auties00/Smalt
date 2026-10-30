package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares {@link AlacDecoder} with ffmpeg's ALAC decoder, bit for bit, on files ffmpeg's ALAC
 * encoder writes for every sample size, channel layout, predictor order range and signal shape,
 * in MP4 and Matroska; checks the channel order, the configuration forms and hostile input.
 */
class AlacDecoderTest {
    /** One ffmpeg encode. */
    record Spec(String name, String container, int rate, String layout, int bits, List<String> options, String signal) {
        @Override
        public String toString() {
            return name;
        }

        int channels() {
            return switch (layout) {
                case "mono" -> 1;
                case "stereo" -> 2;
                case "3.0" -> 3;
                case "4.0" -> 4;
                case "5.0" -> 5;
                case "5.1" -> 6;
                case "6.1(back)" -> 7;
                default -> 8;
            };
        }

        Path fixture() {
            var output = new ArrayList<String>(List.of("-c:a", "alac"));
            if (bits == 16) {
                output.addAll(List.of("-sample_fmt", "s16p"));
            } else {
                output.addAll(List.of("-sample_fmt", "s32p", "-bits_per_raw_sample", Integer.toString(bits)));
            }
            output.addAll(options);
            var exprs = new ArrayList<String>();
            for (var c = 0; c < channels(); c++) {
                exprs.add(switch (signal) {
                    case "silence" -> "0";
                    case "noise" -> "random(" + c + ")*1.98-0.99";
                    case "square" -> "0.999*sgn(sin(2*PI*" + (100 + 37 * c) + "*t))";
                    default -> "0.6*sin(2*PI*" + (220 + 110 * c) + "*t)+0.05*(random(" + c + ")-0.5)";
                });
            }
            return Fixtures.ffmpeg(container, List.of("-f", "lavfi", "-i", "aevalsrc=exprs=" + String.join("|", exprs)
                    + ":s=" + rate + ":d=1.3:c=" + layout), output);
        }
    }

    static Stream<Spec> specs() {
        var specs = new ArrayList<Spec>();
        // ffmpeg's encoder writes 20-bit input as 24-bit ALAC
        for (var bits : new int[]{16, 20, 24}) {
            for (var signal : List.of("music", "noise", "silence", "square")) {
                specs.add(new Spec(bits + "-bit stereo " + signal, "m4a", 44100, "stereo", bits, List.of(), signal));
            }
        }
        for (var layout : List.of("mono", "3.0", "4.0", "5.0", "5.1", "6.1(back)", "7.1(wide)")) {
            specs.add(new Spec("16-bit " + layout, "m4a", 48000, layout, 16, List.of(), "music"));
            specs.add(new Spec("24-bit " + layout, "mkv", 48000, layout, 24, List.of(), "music"));
        }
        for (var order : new int[]{1, 4, 8, 12, 30}) {
            specs.add(new Spec("prediction order " + order, "m4a", 44100, "stereo", 16,
                    List.of("-min_prediction_order", Integer.toString(order), "-max_prediction_order", Integer.toString(order)), "music"));
        }
        specs.add(new Spec("Matroska 16-bit", "mkv", 44100, "stereo", 16, List.of(), "music"));
        specs.add(new Spec("96 kHz 24-bit", "m4a", 96000, "stereo", 24, List.of(), "music"));
        specs.add(new Spec("8 kHz mono", "m4a", 8000, "mono", 16, List.of(), "noise"));
        return specs.stream();
    }

    static Demuxer open(Path file) {
        var source = MediaSource.of(file);
        return file.toString().endsWith(".mkv") ? new MatroskaDemuxer(source)
                : new Mp4Demuxer(source);
    }

    /** Decodes a file with Smalt's integer output into interleaved left-justified 32-bit samples. */
    static int[] smalt(Path file) {
        var out = new ArrayList<int[]>();
        var total = 0;
        try (var demuxer = open(file)) {
            var track = (AudioTrack) demuxer.tracks().stream().filter(AudioTrack.class::isInstance).findFirst().orElseThrow();
            assertEquals(Codec.ALAC, track.codec());
            try (var decoder = AudioDecoders.create(track, AudioDecoderOptions.defaults())) {
                var alac = assertInstanceOf(AlacDecoder.class, decoder);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() != track.id()) {
                        continue;
                    }
                    var chunks = new ArrayList<int[]>();
                    alac.decodeIntegers(packet, block -> {
                        var frames = block.frames();
                        var channels = block.channels();
                        var chunk = new int[frames * channels];
                        for (var i = 0; i < frames; i++) {
                            for (var c = 0; c < channels; c++) {
                                chunk[i * channels + c] = block.plane(c)[i] << (32 - block.bitsPerSample());
                            }
                        }
                        chunks.add(chunk);
                    });
                    for (var chunk : chunks) {
                        out.add(chunk);
                        total += chunk.length;
                    }
                }
            }
        }
        var result = new int[total];
        var position = 0;
        for (var chunk : out) {
            System.arraycopy(chunk, 0, result, position, chunk.length);
            position += chunk.length;
        }
        return result;
    }

    static int[] ffmpeg(Path file) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-f", "s32le", "-"));
        var samples = new int[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(samples);
        return samples;
    }

    @Nested
    @DisplayName("differential")
    class Differential {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.alac.AlacDecoderTest#specs")
        @DisplayName("decodes ffmpeg's ALAC files bit-exactly as ffmpeg's decoder does")
        void bitExact(Spec spec) {
            var file = spec.fixture();
            var expected = ffmpeg(file);
            var actual = smalt(file);
            assertEquals(expected.length, actual.length, "sample count");
            for (var i = 0; i < expected.length; i++) {
                if (expected[i] != actual[i]) {
                    fail("sample " + i / spec.channels() + " channel " + i % spec.channels() + ": expected " + expected[i]
                            + " but was " + actual[i]);
                }
            }
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> music() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("16-bit 48 kHz music (CELLAR subset 53)", "53",
                        List.of("-c:a", "alac", "-sample_fmt", "s16p")),
                org.junit.jupiter.params.provider.Arguments.of("24-bit 96 kHz music (CELLAR subset 30)", "30",
                        List.of("-c:a", "alac", "-sample_fmt", "s32p", "-bits_per_raw_sample", "24")),
                org.junit.jupiter.params.provider.Arguments.of("16-bit 44.1 kHz music (CELLAR subset 25)", "25",
                        List.of("-c:a", "alac", "-sample_fmt", "s16p")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("music")
    @DisplayName("decodes ffmpeg's ALAC encodes of real music bit-exactly")
    void realMusic(String name, String vector, List<String> options) {
        var source = com.github.auties00.smalt.audio.flac.FlacConformanceCorpus.vector("subset", vector);
        var file = Fixtures.ffmpeg("m4a", List.of("-i", source.toString()), options);
        var expected = ffmpeg(file);
        var actual = smalt(file);
        assertEquals(expected.length, actual.length, "sample count");
        for (var i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail("sample " + i / 2 + " channel " + i % 2 + ": expected " + expected[i] + " but was " + actual[i]);
            }
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        static byte[] config(int frameLength, int bits, int channels, int rate) {
            var config = ByteBuffer.allocate(24);
            config.putInt(frameLength).put((byte) 0).put((byte) bits).put((byte) 40).put((byte) 10).put((byte) 14)
                    .put((byte) channels).putShort((short) 255).putInt(0).putInt(0).putInt(rate);
            return config.array();
        }

        static AudioTrack track(byte[] config) {
            return AudioTrack.builder().codec(Codec.ALAC).timeBase(Rational.timeBaseOf(44100)).codecConfig(config)
                    .sampleRate(44100)
                    .build();
        }

        @Test
        @DisplayName("bare, versioned and atom-wrapped magic cookies give the same decoder")
        void cookies() {
            var bare = config(4096, 16, 2, 44100);
            var versioned = ByteBuffer.allocate(28).putInt(0).put(bare).array();
            var atom = ByteBuffer.allocate(36).putInt(36).put("alac".getBytes()).putInt(0).put(bare).array();
            var framed = ByteBuffer.allocate(48).putInt(12).put("frma".getBytes()).put("alac".getBytes()).put(atom).array();
            for (var cookie : List.of(bare, versioned, atom, framed)) {
                var decoder = new AlacDecoder(track(cookie));
                assertEquals(44100, decoder.sampleRate());
                assertEquals(ChannelLayout.STEREO, decoder.layout());
            }
        }

        @Test
        @DisplayName("multichannel streams are reordered into WAVE speaker order")
        void layouts() {
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT), new AlacDecoder(track(config(4096, 16, 6, 48000))).layout().speakers());
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER),
                    new AlacDecoder(track(config(4096, 16, 8, 48000))).layout().speakers());
            assertEquals(ChannelLayout.MONO, new AlacDecoder(track(config(4096, 16, 1, 48000))).layout());
        }

        @Test
        @DisplayName("invalid and unsupported configurations are rejected")
        void rejected() {
            assertThrows(MediaException.Malformed.class, () -> new AlacDecoder(track(new byte[10])));
            assertThrows(MediaException.Unsupported.class, () -> new AlacDecoder(track(config(4096, 33, 2, 44100))));
            assertThrows(MediaException.Unsupported.class, () -> new AlacDecoder(track(config(4096, 16, 9, 44100))));
            assertThrows(MediaException.Malformed.class, () -> new AlacDecoder(track(config(0, 16, 2, 44100))));
            assertThrows(MediaException.LimitExceeded.class, () -> new AlacDecoder(track(config(Integer.MAX_VALUE, 16, 2, 44100))));
            var noConfig = AudioTrack.builder().codec(Codec.ALAC).timeBase(Rational.timeBaseOf(44100))
                    .sampleRate(44100).build();
            assertThrows(MediaException.Malformed.class, () -> new AlacDecoder(noConfig));
            var opus = AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> new AlacDecoder(opus));
        }

        @Test
        @DisplayName("an uncompressed frame, a partial frame and fill elements decode as written")
        void handBuilt() {
            var decoder = new AlacDecoder(track(config(4096, 16, 1, 44100)));
            decoder.decode(handBuiltPacket(), buffer -> assertEquals(3, buffer.frames()));
            assertArrayEquals(new int[]{-5, 7, -32768}, java.util.Arrays.copyOf(decoder.lastSamples()[0], 3));
        }

        @Test
        @DisplayName("an exception of the output consumer reaches the caller unchanged")
        void consumerFailure() {
            var decoder = new AlacDecoder(track(config(4096, 16, 1, 44100)));
            var failure = new IllegalStateException("consumer");
            var thrown = assertThrows(IllegalStateException.class, () -> decoder.decode(handBuiltPacket(), buffer -> {
                throw failure;
            }));
            assertSame(failure, thrown);
        }

        private static Packet handBuiltPacket() {
            // a fill element of two bytes, then a single channel element with the partial-frame and
            // escape flags: three 16-bit samples stored verbatim
            var writer = new com.github.auties00.smalt.internal.bits.MsbBitWriter();
            writer.write(6, 3);
            writer.write(2, 4);
            writer.write(0xABCD, 16);
            writer.write(0, 3);
            writer.write(0, 4);
            writer.write(0, 12);
            writer.write(0b1001, 4);
            writer.write(0, 16);
            writer.write(3, 16);
            writer.write(-5 & 0xFFFF, 16);
            writer.write(7, 16);
            writer.write(-32768 & 0xFFFF, 16);
            writer.write(7, 3);
            writer.alignWithZeros();
            return new Packet(0, writer.toByteArray(), 0, 0, 3, true);
        }
    }

    @Nested
    @DisplayName("predictor")
    class Predictor {
        @Test
        @DisplayName("order 0 copies, order 31 integrates with wrap-around at the prediction size")
        void fixedModes() {
            int[] residual = {5, 3, -2, 100, -200};
            var copy = new int[5];
            AlacPredictor.restore(residual, copy, 5, null, 0, 16, 9);
            assertArrayEquals(residual, copy);
            var integrated = residual.clone();
            AlacPredictor.restore(integrated, integrated, 5, null, 31, 8, 0);
            assertArrayEquals(new int[]{5, 8, 6, 106, -94}, integrated);
        }

        @Test
        @DisplayName("the unrolled fourth order equals the general loop")
        void unrolled() {
            var random = new java.util.SplittableRandom(9);
            for (var round = 0; round < 50; round++) {
                var residual = new int[300];
                for (var i = 0; i < residual.length; i++) {
                    residual[i] = random.nextInt(-3000, 3000);
                }
                short[] coefficients = {(short) random.nextInt(-2000, 2000), (short) random.nextInt(-2000, 2000),
                        (short) random.nextInt(-2000, 2000), (short) random.nextInt(-2000, 2000)};
                var unrolled = new int[300];
                AlacPredictor.restore(residual, unrolled, 300, coefficients.clone(), 4, 17, 9);
                // a direct evaluation of the general recursion of dp_dec.c's unpc_block
                var expected = new int[300];
                var a = coefficients.clone();
                expected[0] = residual[0];
                for (var j = 1; j <= 4; j++) {
                    expected[j] = (residual[j] + expected[j - 1]) << 15 >> 15;
                }
                for (var j = 5; j < 300; j++) {
                    var top = expected[j - 5];
                    var sum = 0;
                    for (var k = 0; k < 4; k++) {
                        sum += a[k] * (expected[j - 1 - k] - top);
                    }
                    var del = residual[j];
                    var del0 = del;
                    expected[j] = (del + top + ((sum + 256) >> 9)) << 15 >> 15;
                    for (var k = 3; k >= 0 && del != 0; k--) {
                        var dd = top - expected[j - 1 - k];
                        var sgn = Integer.signum(dd) * Integer.signum(del);
                        a[k] = (short) (a[k] - sgn);
                        del0 -= (4 - k) * ((sgn * dd) >> 9);
                        if (del > 0 ? del0 <= 0 : del0 >= 0) {
                            break;
                        }
                    }
                }
                assertArrayEquals(expected, unrolled, "round " + round);
            }
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("mutated packets decode or raise MediaException within the time bound")
        void mutations() {
            var spec = new Spec("stereo", "m4a", 44100, "stereo", 24, List.of(), "music");
            var file = spec.fixture();
            var packets = new ArrayList<byte[]>();
            AudioTrack track;
            try (var demuxer = open(file)) {
                track = (AudioTrack) demuxer.tracks().getFirst();
                for (var packet = demuxer.read(); packet != null && packets.size() < 6; packet = demuxer.read()) {
                    packets.add(packet.data());
                }
            }
            var finalTrack = track;
            var outcomes = AudioMutations.assertRobust(packets, 300, 41, Duration.ofSeconds(5), data -> {
                var decoder = new AlacDecoder(finalTrack);
                decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> {
                });
            });
            assertTrue(outcomes.getOrDefault("Malformed", 0) > 0, outcomes.toString());
        }

        @Test
        @DisplayName("empty and truncated packets raise Malformed")
        void truncated() {
            var decoder = new AlacDecoder(Configuration.track(Configuration.config(4096, 16, 2, 44100)));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(new Packet(0, new byte[0], 0, 0, 0, true), buffer -> {
            }));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(new Packet(0, new byte[]{0x20, 0, 0}, 0, 0, 0, true), buffer -> {
            }));
        }
    }

    @Test
    @DisplayName("the registry decodes ALAC tracks")
    void registry() throws Exception {
        assertTrue(AudioDecoders.isSupported(Codec.ALAC));
        var file = new Spec("registry", "m4a", 44100, "stereo", 16, List.of(), "music").fixture();
        assertTrue(Files.size(file) > 0);
    }

    @Nested
    @DisplayName("codec-independent options")
    class IndependentOptions {
        @Test
        @DisplayName("apply their limits and ignore the preferred sample rate")
        void limits() {
            var stereo = Configuration.track(Configuration.config(4096, 16, 2, 44100));
            var decoder = new AlacDecoder(stereo, AudioDecoderOptions.builder().preferredSampleRate(8000).build());
            assertEquals(ChannelLayout.STEREO, decoder.layout());
            assertEquals(44100, decoder.sampleRate());
            assertThrows(MediaException.LimitExceeded.class, () -> new AlacDecoder(stereo,
                    AudioDecoderOptions.builder().limits(MediaLimits.builder().maxChannels(1).build()).build()));
            assertThrows(NullPointerException.class, () -> new AlacDecoder(stereo, (AudioDecoderOptions) null));
        }
    }
}
