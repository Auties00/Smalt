package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.audio.flac.FlacEncoding.Shape;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.github.auties00.smalt.audio.flac.FlacEncoding.encode;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.md5;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.nativeFile;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.signal;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.track;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the integer and double outputs of {@link FlacDecoder} and the integer input of {@link FlacEncoder}: random
 * samples of every size up to 32 bits round-trip exactly, the doubles scale them exactly while the floats round them,
 * the {@code STREAMINFO} MD5 check passes on intact streams and fails on altered ones, the reference {@code flac}
 * tool agrees in both directions, and transcodes between lossless formats keep every bit.
 */
@DisplayName("FLAC integer output")
class FlacIntegerOutputTest {
    /** Uniform random samples over the whole range of a sample size. */
    static int[][] uniform(int channels, int bits, int length, long seed) {
        var random = new SplittableRandom(seed);
        var out = new int[channels][length];
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < length; i++) {
                out[c][i] = (int) (random.nextLong(1L << bits) - (1L << (bits - 1)));
            }
        }
        return out;
    }

    /** The blocks of every packet, copied, through one output form. */
    static <T> List<T> collect(List<Packet> packets, java.util.function.BiConsumer<Packet, Consumer<T>> decode) {
        var blocks = new ArrayList<T>();
        for (var packet : packets) {
            decode.accept(packet, blocks::add);
        }
        return blocks;
    }

    static int[][] concat(List<IntegerAudioBuffer> blocks, int channels) {
        var total = blocks.stream().mapToInt(IntegerAudioBuffer::frames).sum();
        var out = new int[channels][total];
        var position = 0;
        for (var block : blocks) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(block.plane(c), 0, out[c], position, block.frames());
            }
            position += block.frames();
        }
        return out;
    }

    static int[][] decodeAll(FlacDecoder decoder, List<Packet> packets, int channels) {
        var blocks = new ArrayList<IntegerAudioBuffer>();
        for (var packet : packets) {
            decoder.decodeIntegers(packet, block -> blocks.add(block.copy()));
        }
        decoder.flushIntegers(block -> blocks.add(block.copy()));
        return concat(blocks, channels);
    }

    static Stream<Arguments> formats() {
        var arguments = new ArrayList<Arguments>();
        for (var bits : new int[]{4, 8, 16, 20, 24, 25, 28, 31, 32}) {
            for (var channels : new int[]{1, 2, 6, 8}) {
                arguments.add(Arguments.of(bits, channels));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} bits, {1} channels")
    @MethodSource("formats")
    @DisplayName("uniform random samples decode to the same integers, exact doubles and rounded floats")
    void exact(int bits, int channels) {
        var samples = uniform(channels, bits, 10_000, bits * 17L + channels);
        var options = FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits)
                .format(48_000, ChannelLayout.defaultFor(channels)).build();
        var encoded = encode(options, samples);
        var track = track(encoded.config(), 48_000);
        try (var integers = new FlacDecoder(track); var doubles = new FlacDecoder(track); var floats = new FlacDecoder(track)) {
            assertTrue(integers.supportsIntegerOutput());
            assertTrue(integers.supportsDoubleOutput());
            assertEquals(bits, integers.bitsPerSample());
            var integerBlocks = collect(encoded.packets(), (Packet packet, Consumer<IntegerAudioBuffer> sink) ->
                    integers.decodeIntegers(packet, block -> sink.accept(block.copy())));
            var doubleBlocks = collect(encoded.packets(), (Packet packet, Consumer<DoubleAudioBuffer> sink) ->
                    doubles.decodeDoubles(packet, block -> sink.accept(block.copy())));
            var floatBlocks = collect(encoded.packets(), (Packet packet, Consumer<AudioBuffer> sink) ->
                    floats.decode(packet, block -> sink.accept(block.copy())));
            var decoded = concat(integerBlocks, channels);
            for (var c = 0; c < channels; c++) {
                assertArrayEquals(samples[c], decoded[c], "channel " + c);
            }
            assertEquals(integerBlocks.size(), doubleBlocks.size());
            assertEquals(integerBlocks.size(), floatBlocks.size());
            var scale = Math.scalb(1.0, 1 - bits);
            var rounded = false;
            for (var b = 0; b < integerBlocks.size(); b++) {
                var block = integerBlocks.get(b);
                assertEquals(bits, block.bitsPerSample());
                assertEquals(ChannelLayout.defaultFor(channels), block.layout());
                assertEquals(block.frames(), doubleBlocks.get(b).frames());
                for (var c = 0; c < channels; c++) {
                    for (var i = 0; i < block.frames(); i++) {
                        var exact = block.plane(c)[i] * scale;
                        assertEquals(exact, doubleBlocks.get(b).plane(c)[i]);
                        assertEquals((float) exact, floatBlocks.get(b).plane(c)[i]);
                        rounded |= (double) floatBlocks.get(b).plane(c)[i] != exact;
                    }
                }
            }
            // a float holds every integer of up to 24 bits of magnitude, so every 25-bit sample too
            assertEquals(bits > 25, rounded, "floats round exactly the samples wider than 25 bits");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FlacStereoMode.class)
    @DisplayName("32-bit stereo decodes exactly through every stereo mode, the 33-bit side channel included")
    void stereo(FlacStereoMode mode) {
        var samples = uniform(2, 32, 12_000, mode.ordinal());
        // a correlated pair makes the side channel the cheaper choice
        for (var i = 0; i < samples[0].length; i += 3) {
            samples[1][i] = samples[0][i] ^ 1;
        }
        var options = FlacEncoderOptions.defaults().toBuilder().bitsPerSample(32).stereoMode(mode)
                .format(44_100, ChannelLayout.STEREO).build();
        var encoded = encode(options, samples);
        try (var decoder = new FlacDecoder(track(encoded.config(), 44_100))) {
            var decoded = decodeAll(decoder, encoded.packets(), 2);
            assertArrayEquals(samples[0], decoded[0]);
            assertArrayEquals(samples[1], decoded[1]);
        }
    }

    @Nested
    @DisplayName("format changes in the integer stream output")
    class FormatChanges {
        /** Two encodes joined into one track: the second part's frames code their own rate or sample size. */
        private static List<Packet> joined(FlacEncoding.Encoded first, FlacEncoding.Encoded second) {
            var packets = new ArrayList<Packet>();
            var pts = 0L;
            for (var part : List.of(first, second)) {
                for (var packet : part.packets()) {
                    packets.add(new Packet(0, packet.data(), pts, pts, packet.duration(), true));
                    pts += packet.duration();
                }
            }
            return packets;
        }

        private static FlacEncoding.Encoded part(int rate, int bits, int[][] samples) {
            return encode(FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits)
                    .format(rate, ChannelLayout.STEREO).build(), samples);
        }

        private static int[][] integers(AudioTrack track, List<Packet> packets) {
            var options = com.github.auties00.smalt.audio.AudioStreamDecoderOptions.builder()
                    .sampleFormat(SampleFormat.INTEGER).build();
            try (var decoder = com.github.auties00.smalt.audio.AudioStreamDecoder.create(track, options)) {
                var out = new int[2][40_000];
                var total = 0;
                for (var packet : packets) {
                    decoder.decode(packet);
                    total += decoder.readIntegers(out, total, 40_000 - total);
                }
                decoder.finish();
                total += decoder.readIntegers(out, total, 40_000 - total);
                assertEquals(24, decoder.bitsPerSample());
                return new int[][]{Arrays.copyOf(out[0], total), Arrays.copyOf(out[1], total)};
            }
        }

        private static float[][] floats(AudioTrack track, List<Packet> packets) {
            try (var decoder = com.github.auties00.smalt.audio.AudioStreamDecoder.create(track)) {
                var out = new float[2][40_000];
                var total = 0;
                for (var packet : packets) {
                    decoder.decode(packet);
                    total += decoder.read(out, total, 40_000 - total);
                }
                decoder.finish();
                total += decoder.read(out, total, 40_000 - total);
                return new float[][]{Arrays.copyOf(out[0], total), Arrays.copyOf(out[1], total)};
            }
        }

        @Test
        @DisplayName("a narrower part is shifted to the first part's sample size, every value kept")
        void sampleSize() {
            var wide = uniform(2, 24, 5000, 1);
            var narrow = uniform(2, 16, 5000, 2);
            var first = part(48_000, 24, wide);
            var packets = joined(first, part(48_000, 16, narrow));
            var track = track(first.config(), 48_000);
            var decoded = integers(track, packets);
            var reference = floats(track, packets);
            assertEquals(10_000, decoded[0].length);
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < 10_000; i++) {
                    var expected = i < 5000 ? wide[c][i] : narrow[c][i - 5000] << 8;
                    assertEquals(expected, decoded[c][i], "sample " + i);
                    assertEquals(expected * 0x1p-23f, reference[c][i], "float sample " + i);
                }
            }
        }

        @Test
        @DisplayName("a part at another rate is resampled as in float output and quantized, the first part kept exactly")
        void sampleRate() {
            var samples = uniform(2, 24, 5000, 3);
            var first = part(48_000, 24, samples);
            var packets = joined(first, part(44_100, 24, uniform(2, 24, 5000, 4)));
            var track = track(first.config(), 48_000);
            var decoded = integers(track, packets);
            var reference = floats(track, packets);
            assertEquals(reference[0].length, decoded[0].length, "same length as the float output");
            assertTrue(decoded[0].length > 9000, "the resampled part is output");
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < decoded[c].length; i++) {
                    if (i < 5000) {
                        assertEquals(samples[c][i], decoded[c][i], "sample " + i);
                    }
                    var quantized = (int) Math.clamp(Math.round(reference[c][i] * 0x1p23), -(1 << 23), (1 << 23) - 1);
                    assertEquals(quantized, decoded[c][i], "sample " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("integer encoder input")
    class EncoderInput {
        private static FlacEncoderOptions options(int bits) {
            return FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits).format(48_000, ChannelLayout.STEREO)
                    .build();
        }

        private static List<Packet> encodeBlocks(FlacEncoder encoder, int[][] samples, int bits) {
            var packets = new ArrayList<Packet>();
            var position = 0;
            var chunk = 7;
            while (position < samples[0].length) {
                var count = Math.min(chunk, samples[0].length - position);
                var planes = new int[2][];
                for (var c = 0; c < 2; c++) {
                    planes[c] = Arrays.copyOfRange(samples[c], position, position + count);
                }
                encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 48_000, bits, planes, count), packets::add);
                position += count;
                chunk = chunk * 2 + 1;
            }
            encoder.flush(packets::add);
            return packets;
        }

        @Test
        @DisplayName("blocks of the encoder's sample size code exactly as the planar integer method does")
        void sameSize() {
            var samples = uniform(2, 32, 9_000, 3);
            var encoder = new FlacEncoder(options(32));
            assertTrue(encoder.supportsIntegerInput());
            var packets = encodeBlocks(encoder, samples, 32);
            var reference = encode(options(32), samples);
            assertEquals(reference.packets().size(), packets.size());
            for (var i = 0; i < packets.size(); i++) {
                assertArrayEquals(reference.packets().get(i).data(), packets.get(i).data(), "packet " + i);
            }
            assertArrayEquals(reference.config(), encoder.codecConfig().orElseThrow());
        }

        @Test
        @DisplayName("narrower blocks are shifted left and wider ones rounded to nearest, ties up, and clamped")
        void otherSizes() {
            var narrow = uniform(2, 16, 5_000, 4);
            var widened = new FlacEncoder(options(24));
            var packets = encodeBlocks(widened, narrow, 16);
            try (var decoder = new FlacDecoder(track(widened.codecConfig().orElseThrow(), 48_000))) {
                var decoded = decodeAll(decoder, packets, 2);
                for (var c = 0; c < 2; c++) {
                    for (var i = 0; i < narrow[c].length; i++) {
                        assertEquals(narrow[c][i] << 8, decoded[c][i]);
                    }
                }
            }
            var wide = uniform(2, 32, 5_000, 5);
            wide[0][0] = Integer.MAX_VALUE;
            wide[0][1] = 0x7F_FF_FF_7F;
            wide[0][2] = 0x80;
            wide[0][3] = -0x80;
            wide[0][4] = Integer.MIN_VALUE;
            var narrowed = new FlacEncoder(options(24));
            packets = encodeBlocks(narrowed, wide, 32);
            try (var decoder = new FlacDecoder(track(narrowed.codecConfig().orElseThrow(), 48_000))) {
                var decoded = decodeAll(decoder, packets, 2);
                for (var c = 0; c < 2; c++) {
                    for (var i = 0; i < wide[c].length; i++) {
                        var expected = (int) Math.min(((long) wide[c][i] + 128) >> 8, (1 << 23) - 1);
                        assertEquals(expected, decoded[c][i], "sample " + i);
                    }
                }
                assertEquals((1 << 23) - 1, decoded[0][0]);
                assertEquals((1 << 23) - 1, decoded[0][1]);
                assertEquals(1, decoded[0][2]);
                assertEquals(0, decoded[0][3]);
                assertEquals(-(1 << 23), decoded[0][4]);
            }
        }

        @Test
        @DisplayName("out-of-range samples and a mismatched format are rejected")
        void rejects() {
            var encoder = new FlacEncoder(options(16));
            var bad = new int[][]{{40_000}, {0}};
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 48_000, 16, bad, 1), packet -> {
                    }));
            var shifted = new int[][]{{1 << 12}, {0}};
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 48_000, 12, shifted, 1), packet -> {
                    }));
            // 2^24 is outside 12 bits, and shifted to 16 bits it would wrap to zero
            var wrapping = new int[][]{{1 << 24}, {0}};
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 48_000, 12, wrapping, 1), packet -> {
                    }));
            // 2^27 is outside 28 bits, and rounded to 16 bits it would clamp
            var wide = new int[][]{{1 << 27}, {0}};
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 48_000, 28, wide, 1), packet -> {
                    }));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 44_100, 16, 4), packet -> {
                    }));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new IntegerAudioBuffer(ChannelLayout.MONO, 48_000, 16, 4), packet -> {
                    }));
        }
        @Test
        @DisplayName("a block with an out-of-range sample is rejected whole, leaving the encoder unchanged")
        void rejectsWhole() {
            var samples = uniform(2, 20, 10_000, 6);
            var encoder = new FlacEncoder(options(24));
            var packets = new ArrayList<Packet>();
            var bad = new int[][]{samples[0].clone(), samples[1].clone()};
            bad[1][9_999] = 1 << 20;
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 48_000, 20, bad, 10_000), packets::add));
            assertTrue(packets.isEmpty(), "no frame is coded from a rejected block");
            assertEquals(0, encoder.samplesEncoded());
            encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, 48_000, 20, samples, 10_000), packets::add);
            encoder.flush(packets::add);
            try (var decoder = new FlacDecoder(track(encoder.codecConfig().orElseThrow(), 48_000))) {
                var decoded = decodeAll(decoder, packets, 2);
                for (var c = 0; c < 2; c++) {
                    assertEquals(10_000, decoded[c].length);
                    for (var i = 0; i < 10_000; i++) {
                        assertEquals(samples[c][i] << 4, decoded[c][i], "sample " + i);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("STREAMINFO MD5 check")
    class Md5 {
        private static final AudioDecoderOptions VERIFY = AudioDecoderOptions.builder().verifyIntegrity(true).build();

        private FlacEncoding.Encoded encoded(int bits) {
            var options = FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits).blockSize(1152)
                    .format(44_100, ChannelLayout.STEREO).build();
            return encode(options, signal(Shape.MUSIC, 2, bits, 20_000, bits));
        }

        private static byte[] alteredMd5(byte[] config) {
            var copy = config.clone();
            copy[30] ^= 0x10;
            return copy;
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 16, 24, 32})
        @DisplayName("an intact stream passes the check through every output form")
        void intact(int bits) {
            var encoded = encoded(bits);
            for (var form = 0; form < 3; form++) {
                try (var decoder = new FlacDecoder(track(encoded.config(), 44_100), VERIFY)) {
                    for (var packet : encoded.packets()) {
                        switch (form) {
                            case 0 -> decoder.decode(packet, block -> {
                            });
                            case 1 -> decoder.decodeIntegers(packet, block -> {
                            });
                            default -> decoder.decodeDoubles(packet, block -> {
                            });
                        }
                        assertFalse(decoder.md5Verified(), "checked only by the flush");
                    }
                    switch (form) {
                        case 0 -> decoder.flush(block -> {
                        });
                        case 1 -> decoder.flushIntegers(block -> {
                        });
                        default -> decoder.flushDoubles(block -> {
                        });
                    }
                    assertTrue(decoder.md5Verified(), "form " + form);
                }
            }
        }

        @Test
        @DisplayName("an altered signature fails the flush, and is ignored without the option")
        void altered() {
            var encoded = encoded(24);
            var config = alteredMd5(encoded.config());
            try (var decoder = new FlacDecoder(track(config, 44_100), VERIFY)) {
                var failure = assertThrows(MediaException.Malformed.class, () -> decodeAll(decoder, encoded.packets(), 2));
                assertTrue(failure.getMessage().contains("MD5"), failure.getMessage());
                assertFalse(decoder.md5Verified());
            }
            try (var decoder = new FlacDecoder(track(config, 44_100))) {
                decodeAll(decoder, encoded.packets(), 2);
                assertFalse(decoder.md5Verified());
            }
        }

        @Test
        @DisplayName("a missing, a repeated or a truncated frame fails the flush")
        void damaged() {
            var encoded = encoded(16);
            var packets = encoded.packets();
            var missing = new ArrayList<>(packets);
            missing.remove(5);
            var repeated = new ArrayList<>(packets);
            repeated.add(6, packets.get(5));
            var truncated = packets.subList(0, packets.size() - 1);
            for (var damaged : List.of(missing, repeated, truncated)) {
                try (var decoder = new FlacDecoder(track(encoded.config(), 44_100), VERIFY)) {
                    assertThrows(MediaException.Malformed.class, () -> decodeAll(decoder, damaged, 2));
                }
            }
        }

        @Test
        @DisplayName("a decode that starts after the first frame, or a stream without a signature, is not checked")
        void unchecked() {
            var encoded = encoded(16);
            try (var decoder = new FlacDecoder(track(encoded.config(), 44_100), VERIFY)) {
                decodeAll(decoder, encoded.packets().subList(1, encoded.packets().size()), 2);
                assertFalse(decoder.md5Verified());
            }
            var unsigned = encoded.config().clone();
            Arrays.fill(unsigned, 26, 42, (byte) 0);
            try (var decoder = new FlacDecoder(track(unsigned, 44_100), VERIFY)) {
                decodeAll(decoder, encoded.packets(), 2);
                assertFalse(decoder.md5Verified());
            }
        }

        @Test
        @DisplayName("the reader checks the signature when asked to, failing at the end of an altered file")
        void reader() {
            var encoded = encoded(32);
            var intact = nativeFile(encoded, 44_100);
            var altered = intact.clone();
            altered[30] ^= 0x10;
            var options = AudioReaderOptions.builder().verifyIntegrity(true).build();
            try (var reader = AudioReader.open(MediaSource.of(intact), options)) {
                while (reader.read() != null) {
                    // drained to the end, where the signature is checked
                }
            }
            try (var reader = AudioReader.open(MediaSource.of(altered), options)) {
                assertThrows(MediaException.Malformed.class, () -> {
                    while (reader.read() != null) {
                        // the last read flushes the codec
                    }
                });
            }
            try (var reader = AudioReader.open(MediaSource.of(altered))) {
                while (reader.read() != null) {
                    // not checked by default
                }
            }
        }
    }

    @Nested
    @DisplayName("lossless transcodes")
    class Transcodes {
        private static byte[] source(int bits, int length) {
            var options = FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits)
                    .format(48_000, ChannelLayout.STEREO).build();
            return nativeFile(encode(options, uniform(2, bits, length, bits)), 48_000);
        }

        private static int[][] decodeFile(byte[] flac) {
            try (var demuxer = new FlacDemuxer(MediaSource.of(flac));
                 var decoder = new FlacDecoder((AudioTrack) demuxer.tracks().getFirst(),
                         AudioDecoderOptions.builder().verifyIntegrity(true).build())) {
                var packets = new ArrayList<Packet>();
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    packets.add(packet);
                }
                var decoded = decodeAll(decoder, packets, 2);
                assertTrue(decoder.md5Verified(), "the output's STREAMINFO signature matches its samples");
                return decoded;
            }
        }

        private static byte[] transcode(byte[] input, AudioTranscodeOptions audio, OutputContainer container) {
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(input), sink, TranscodeOptions.of(container).toBuilder().audio(audio).build());
            return sink.toByteArray();
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {16, 24, 25, 32})
        @DisplayName("FLAC to FLAC keeps every sample and the source's MD5")
        void flacToFlac(int bits) {
            var input = source(bits, 30_000);
            var output = transcode(input, AudioTranscodeOptions.encode(Codec.FLAC).toBuilder().verifyIntegrity(true)
                    .build(), OutputContainer.FLAC);
            assertArrayEquals(Arrays.copyOfRange(input, 26, 42), Arrays.copyOfRange(output, 26, 42), "MD5");
            assertEquals(bits, ((output[20] & 0x01) << 4 | (output[21] & 0xF0) >> 4) + 1, "sample size");
            var expected = decodeFile(input);
            var decoded = decodeFile(output);
            assertArrayEquals(expected[0], decoded[0]);
            assertArrayEquals(expected[1], decoded[1]);
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {24, 32})
        @DisplayName("FLAC in Matroska, whose track declares no sample size, re-encodes at the STREAMINFO sample size")
        void matroskaToFlac(int bits) {
            var input = source(bits, 20_000);
            var mkv = transcode(input, AudioTranscodeOptions.copy(), OutputContainer.MATROSKA);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(mkv))) {
                assertEquals(0, ((AudioTrack) demuxer.tracks().getFirst()).bitsPerSample(),
                        "the Matroska track leaves the sample size to the codec configuration");
            }
            var output = transcode(mkv, AudioTranscodeOptions.encode(Codec.FLAC).toBuilder().verifyIntegrity(true)
                    .build(), OutputContainer.FLAC);
            assertEquals(bits, ((output[20] & 0x01) << 4 | (output[21] & 0xF0) >> 4) + 1, "sample size");
            assertArrayEquals(Arrays.copyOfRange(input, 26, 42), Arrays.copyOfRange(output, 26, 42), "MD5");
            var expected = decodeFile(input);
            var decoded = decodeFile(output);
            assertArrayEquals(expected[0], decoded[0]);
            assertArrayEquals(expected[1], decoded[1]);
        }

        @ParameterizedTest(name = "seeking {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("a trimmed FLAC to FLAC transcode keeps the kept samples exactly, seeking or decoding from the start")
        void trimmed(boolean seeking) {
            var input = source(32, 48_000);
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(input), sink, TranscodeOptions.of(OutputContainer.FLAC).toBuilder()
                    .audio(AudioTranscodeOptions.encode(Codec.FLAC).toBuilder().verifyIntegrity(true).build())
                    .range(250_000, 750_000).seeking(seeking).build());
            var expected = decodeFile(input);
            var decoded = decodeFile(sink.toByteArray());
            assertEquals(24_000, decoded[0].length);
            for (var c = 0; c < 2; c++) {
                assertArrayEquals(Arrays.copyOfRange(expected[c], 12_000, 36_000), decoded[c]);
            }
        }

        @Test
        @DisplayName("a source whose signature does not match fails a verifying transcode")
        void verified() {
            var input = source(24, 20_000);
            input[30] ^= 0x10;
            transcode(input, AudioTranscodeOptions.encode(Codec.FLAC), OutputContainer.FLAC);
            assertThrows(MediaException.Malformed.class, () -> transcode(input,
                    AudioTranscodeOptions.encode(Codec.FLAC).toBuilder().verifyIntegrity(true).build(), OutputContainer.FLAC));
        }

        @Test
        @DisplayName("32-bit FLAC to 32-bit WAV writes the source's integers")
        void flacToWav() {
            var input = source(32, 20_000);
            var output = transcode(input, AudioTranscodeOptions.encode(Codec.PCM_S32LE), OutputContainer.WAV);
            var expected = decodeFile(input);
            try (var demuxer = new WavDemuxer(MediaSource.of(output))) {
                var data = new java.io.ByteArrayOutputStream();
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    data.writeBytes(packet.data());
                }
                var samples = ByteBuffer.wrap(data.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                assertEquals(2 * expected[0].length, samples.remaining());
                for (var i = 0; i < expected[0].length; i++) {
                    assertEquals(expected[0][i], samples.get(2 * i), "left " + i);
                    assertEquals(expected[1][i], samples.get(2 * i + 1), "right " + i);
                }
            }
        }

        @Test
        @DisplayName("a gain keeps the float path, so the encode still runs")
        void gain() {
            var input = source(16, 10_000);
            var output = transcode(input, AudioTranscodeOptions.encode(Codec.FLAC).toBuilder().gainDb(-6).build(),
                    OutputContainer.FLAC);
            var expected = decodeFile(input);
            var decoded = decodeFile(output);
            assertNotEquals(expected[0][100], decoded[0][100]);
        }
    }

    @Nested
    @DisplayName("reference flac tool")
    class Reference {
        private static byte[] interleaved(int[][] samples, int bits) {
            var width = (bits + 7) / 8;
            var out = new byte[samples.length * samples[0].length * width];
            var at = 0;
            for (var i = 0; i < samples[0].length; i++) {
                for (var channel : samples) {
                    for (var b = 0; b < width; b++) {
                        out[at++] = (byte) (channel[i] >> (8 * b));
                    }
                }
            }
            return out;
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {24, 28, 31, 32})
        @DisplayName("flac verifies and decodes Smalt's wide encodes to the source samples")
        void smaltToFlac(int bits, @TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FLAC);
            var samples = uniform(2, bits, 20_000, bits);
            var options = FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits)
                    .format(48_000, ChannelLayout.STEREO).build();
            var file = directory.resolve("smalt.flac");
            Files.write(file, nativeFile(encode(options, samples), 48_000));
            var flac = Files.readAllBytes(file);
            assertArrayEquals(md5(samples, bits), Arrays.copyOfRange(flac, 26, 42), "STREAMINFO MD5 of the source");
            // flac -t decodes the stream and checks the samples against that signature
            Oracle.run(Oracle.Tool.FLAC, Duration.ofMinutes(1), List.of("-t", "-s", "-w", file.toString()));
            if (bits % 8 == 0) {
                // flac writes raw samples of whole bytes only
                var raw = directory.resolve("decoded.raw");
                Oracle.run(Oracle.Tool.FLAC, Duration.ofMinutes(1), List.of("-d", "-s", "-f", "--force-raw-format",
                        "--endian=little", "--sign=signed", "-o", raw.toString(), file.toString()));
                assertArrayEquals(interleaved(samples, bits), Files.readAllBytes(raw));
            }
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {24, 32})
        @DisplayName("Smalt decodes flac's 32-bit encodes to the source samples and passes their MD5 check")
        void flacToSmalt(int bits, @TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FLAC);
            var samples = uniform(2, bits, 30_000, bits + 100L);
            for (var i = 0; i < samples[0].length; i += 2) {
                samples[1][i] = samples[0][i] / 2;
            }
            var raw = directory.resolve("source.raw");
            Files.write(raw, interleaved(samples, bits));
            var file = directory.resolve("reference.flac");
            Oracle.run(Oracle.Tool.FLAC, Duration.ofMinutes(1), List.of("-8", "-s", "-f", "--force-raw-format",
                    "--endian=little", "--sign=signed", "--channels=2", "--bps=" + bits, "--sample-rate=48000",
                    "-o", file.toString(), raw.toString()));
            var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).verifyIntegrity(true).build();
            try (var reader = AudioReader.open(MediaSource.of(file), options)) {
                assertEquals(bits, reader.bitsPerSample());
                var blocks = new ArrayList<IntegerAudioBuffer>();
                IntegerAudioBuffer block;
                while ((block = reader.readIntegers()) != null) {
                    blocks.add(block.copy());
                }
                var decoded = concat(blocks, 2);
                assertArrayEquals(samples[0], decoded[0]);
                assertArrayEquals(samples[1], decoded[1]);
            }
            assertArrayEquals(md5(samples, bits), Arrays.copyOfRange(Files.readAllBytes(file), 26, 42),
                    "flac wrote the signature the check verified");
        }
    }
}
