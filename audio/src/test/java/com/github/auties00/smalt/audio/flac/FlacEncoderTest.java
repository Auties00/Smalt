package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.flac.FlacEncoding.Shape;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.internal.header.FlacFrameHeader;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
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
import java.util.stream.Stream;

import static com.github.auties00.smalt.audio.flac.FlacEncoding.decode;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.encode;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.md5;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.nativeFile;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.read;
import static com.github.auties00.smalt.audio.flac.FlacEncoding.signal;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that {@link FlacEncoder} is lossless for every compression setting, sample size, channel
 * count, block size and stream length, that its metadata (STREAMINFO, MD5, seek table, padding)
 * is complete, and that its streams round-trip through the native, MP4 and Matroska muxers and
 * decode in ffmpeg and the reference {@code flac} tool when they are installed.
 */
class FlacEncoderTest {
    private static ChannelLayout layout(int channels) {
        return ChannelLayout.defaultFor(channels);
    }

    private static void assertLossless(FlacEncoderOptions options, int[][] samples) throws Exception {
        var encoded = encode(options, samples);
        var decoded = decode(encoded, options.sampleRate(), samples.length);
        for (var c = 0; c < samples.length; c++) {
            assertArrayEquals(samples[c], decoded[c], "channel " + c);
        }
        var config = encoded.config();
        assertEquals(encoded.initialConfig().length, config.length, "final configuration keeps its length");
        assertEquals(samples[0].length, read(config, 21, 5) & 0xFFFFFFFFFL, "total samples");
        assertArrayEquals(md5(samples, options.bitsPerSample()), Arrays.copyOfRange(config, 26, 42), "MD5");
    }

    @Nested
    @DisplayName("lossless round trip")
    class RoundTrip {
        @ParameterizedTest(name = "level {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("every compression level codes 16-bit stereo music exactly")
        void levels(int level) throws Exception {
            assertLossless(FlacEncoderOptions.ofCompressionLevel(level), signal(Shape.MUSIC, 2, 16, 44_100, level));
        }

        static Stream<Arguments> depths() {
            var arguments = new ArrayList<Arguments>();
            for (var bits : new int[]{4, 8, 12, 16, 17, 20, 24, 28, 31, 32}) {
                for (var shape : Shape.values()) {
                    arguments.add(Arguments.of(bits, shape));
                }
            }
            return arguments.stream();
        }

        @ParameterizedTest(name = "{0} bits, {1}")
        @MethodSource("depths")
        @DisplayName("every sample size and signal shape codes stereo exactly, including the 33-bit side channel")
        void depths(int bits, Shape shape) throws Exception {
            var options = FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits)
                    .format(48_000, ChannelLayout.STEREO).build();
            assertLossless(options, signal(shape, 2, bits, 12_000, bits * 31L + shape.ordinal()));
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 16, 24, 32})
        @DisplayName("the exhaustive searches and escape coding code every shape exactly")
        void exhaustive(int bits) throws Exception {
            var options = FlacEncoderOptions.forSize().toBuilder().bitsPerSample(bits).exhaustiveModelSearch(true)
                    .precisionSearch(true).escapeCoding(true).partitionOrders(0, 8).maxLpcOrder(32)
                    .apodizations(FlacApodization.parse(
                            "tukey(0.5);partial_tukey(2);punchout_tukey(3);subdivide_tukey(4)"))
                    .build();
            for (var shape : Shape.values()) {
                assertLossless(options, signal(shape, 2, bits, 9_000, shape.ordinal()));
            }
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("every channel count codes exactly")
        void channels(int channels) throws Exception {
            var options = FlacEncoderOptions.of(44_100, layout(channels), 16);
            assertLossless(options, signal(Shape.MUSIC, channels, 16, 10_000, channels));
        }

        @ParameterizedTest(name = "block size {0}")
        @ValueSource(ints = {16, 17, 192, 576, 1000, 1152, 4096, 4608, 16384, 32768, 65535})
        @DisplayName("every block size codes exactly")
        void blockSizes(int blockSize) throws Exception {
            var options = FlacEncoderOptions.defaults().toBuilder().blockSize(blockSize).build();
            assertLossless(options, signal(Shape.MUSIC, 2, 16, Math.max(3 * blockSize + 5, 7_000), blockSize));
        }

        @ParameterizedTest(name = "{0} samples")
        @ValueSource(ints = {0, 1, 2, 4, 5, 33, 4095, 4096, 4097, 8192})
        @DisplayName("streams of any length code exactly, the last frame unpadded")
        void lengths(int length) throws Exception {
            var options = FlacEncoderOptions.defaults();
            var samples = signal(Shape.MUSIC, 2, 16, length, length);
            assertLossless(options, samples);
            var encoded = encode(options, samples);
            assertEquals(length, encoded.packets().stream().mapToLong(Packet::duration).sum());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"bartlett", "bartlett_hann", "blackman", "blackman_harris_4term_92db", "connes", "flattop",
                "gauss(0.2)", "hamming", "hann", "kaiser_bessel", "nuttall", "rectangle", "triangle", "tukey(0)",
                "tukey(1)", "partial_tukey(1)", "partial_tukey(3/0.5/0.3)", "punchout_tukey(4)", "subdivide_tukey(5/0.1)", "welch"})
        @DisplayName("every window function codes exactly")
        void windows(String specification) throws Exception {
            var options = FlacEncoderOptions.defaults().toBuilder().apodizations(FlacApodization.parse(specification))
                    .build();
            assertLossless(options, signal(Shape.MUSIC, 2, 16, 20_000, specification.length()));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(FlacStereoMode.class)
        @DisplayName("every stereo mode codes exactly")
        void stereoModes(FlacStereoMode mode) throws Exception {
            var options = FlacEncoderOptions.defaults().toBuilder().stereoMode(mode).build();
            assertLossless(options, signal(Shape.MUSIC, 2, 16, 100_000, mode.ordinal()));
        }

        @Test
        @DisplayName("a fixed coefficient precision and fixed predictors only code exactly")
        void predictorSettings() throws Exception {
            var samples = signal(Shape.MUSIC, 2, 24, 30_000, 5);
            for (var precision = 5; precision <= 15; precision += 5) {
                assertLossless(FlacEncoderOptions.defaults().toBuilder().bitsPerSample(24).qlpPrecision(precision)
                        .build(), samples);
            }
            assertLossless(FlacEncoderOptions.defaults().toBuilder().bitsPerSample(24).maxLpcOrder(0).build(), samples);
            assertLossless(FlacEncoderOptions.defaults().toBuilder().bitsPerSample(24).partitionOrders(15, 15)
                    .build(), samples);
        }

        @Test
        @DisplayName("sample rates without a frame header code are coded from STREAMINFO")
        void sampleRates() throws Exception {
            for (var rate : new int[]{1, 8_000, 11_025, 44_100, 48_000, 96_000, 192_000, 352_800, 655_350, 700_001, 1_048_575}) {
                var options = FlacEncoderOptions.defaults().toBuilder().format(rate, ChannelLayout.MONO).build();
                assertLossless(options, signal(Shape.MUSIC, 1, 16, 5_000, rate));
            }
        }
    }

    @Nested
    @DisplayName("float input")
    class FloatInput {
        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 16, 24})
        @DisplayName("samples a FLAC decoder produced encode back to the same integers")
        void inverse(int bits) {
            var samples = signal(Shape.MUSIC, 2, bits, 10_000, bits);
            var integer = encode(FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits).build(), samples);
            var decoder = new FlacDecoder(FlacEncoding.track(integer.config(), 44_100));
            var encoder = new FlacEncoder(FlacEncoderOptions.defaults().toBuilder().bitsPerSample(bits).build());
            var packets = new ArrayList<Packet>();
            for (var packet : integer.packets()) {
                decoder.decode(packet, buffer -> encoder.encode(buffer, packets::add));
            }
            encoder.flush(packets::add);
            var decoded = decode(new FlacEncoding.Encoded(packets, null, encoder.codecConfig().orElseThrow()), 44_100, 2);
            assertArrayEquals(samples[0], decoded[0]);
            assertArrayEquals(samples[1], decoded[1]);
        }

        @Test
        @DisplayName("out-of-range samples clip, NaN codes zero and halves round up")
        void clipping() {
            var buffer = new AudioBuffer(ChannelLayout.MONO, 44_100, new float[][]{{2f, -2f, Float.NaN, 0.5f / 32768,
                    -0.5f / 32768, 1f, -1f, Float.POSITIVE_INFINITY}}, 8);
            var encoder = new FlacEncoder(FlacEncoderOptions.defaults().toBuilder().format(44_100, ChannelLayout.MONO)
                    .build());
            var packets = new ArrayList<Packet>();
            encoder.encode(buffer, packets::add);
            encoder.flush(packets::add);
            var decoded = decode(new FlacEncoding.Encoded(packets, null, encoder.codecConfig().orElseThrow()), 44_100, 1);
            assertArrayEquals(new int[]{32767, -32768, 0, 1, 0, 32767, -32768, 32767}, decoded[0]);
        }
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {
        @Test
        @DisplayName("STREAMINFO declares the format before the end and the totals after it")
        void streamInfo() throws Exception {
            var options = FlacEncoderOptions.defaults().toBuilder().format(48_000, ChannelLayout.SURROUND_5_1)
                    .bitsPerSample(24).build();
            var samples = signal(Shape.MUSIC, 6, 24, 50_000, 1);
            var encoded = encode(options, samples);
            for (var config : List.of(encoded.initialConfig(), encoded.config())) {
                assertEquals("fLaC", new String(config, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
                assertEquals(0, config[4] & 0x7F);
                assertEquals(34, read(config, 5, 3));
                assertEquals(4096, read(config, 8, 2));
                assertEquals(4096, read(config, 10, 2));
                var packed = read(config, 18, 8);
                assertEquals(48_000, packed >>> 44);
                assertEquals(5, (packed >>> 41) & 7);
                assertEquals(23, (packed >>> 36) & 31);
            }
            assertEquals(0, read(encoded.initialConfig(), 21, 5) & 0xFFFFFFFFFL);
            assertEquals(0, read(encoded.initialConfig(), 12, 6));
            var sizes = encoded.packets().stream().mapToInt(Packet::size).summaryStatistics();
            assertEquals(sizes.getMin(), read(encoded.config(), 12, 3));
            assertEquals(sizes.getMax(), read(encoded.config(), 15, 3));
            assertArrayEquals(md5(samples, 24), Arrays.copyOfRange(encoded.config(), 26, 42));
        }

        @Test
        @DisplayName("a known length reserves the seek table up front; the final one points at frame starts")
        void seekTableReserved() throws Exception {
            var samples = signal(Shape.MUSIC, 2, 16, 44_100 * 25, 2);
            var options = FlacEncoderOptions.defaults().toBuilder().expectedTotalSamples(samples[0].length).build();
            var encoded = encode(options, samples);
            assertEquals(read(encoded.initialConfig(), 21, 5) & 0xFFFFFFFFFL, samples[0].length);
            var file = nativeFile(encoded, 44_100);
            checkSeekTable(file, encoded, 3, 8192);
        }

        @Test
        @DisplayName("an unknown length carves the seek table out of the padding at the end")
        void seekTableCarved() throws Exception {
            var samples = signal(Shape.MUSIC, 2, 16, 44_100 * 25, 3);
            var encoded = encode(FlacEncoderOptions.defaults(), samples);
            assertEquals(42 + 4 + 8192, encoded.initialConfig().length, "STREAMINFO and padding only");
            var file = nativeFile(encoded, 44_100);
            checkSeekTable(file, encoded, 3, 8192 - 3 * 18 - 4);
        }

        @Test
        @DisplayName("a seek table that does not fit the padding is thinned out, or replaces it exactly")
        void seekTableThinned() throws Exception {
            var samples = signal(Shape.MUSIC, 1, 16, 44_100 * 31, 4);
            var options = FlacEncoderOptions.defaults().toBuilder().format(44_100, ChannelLayout.MONO)
                    .seekPointInterval(Duration.ofSeconds(1)).padding(100).build();
            var encoded = encode(options, samples);
            var config = encoded.config();
            assertEquals(42 + 4 + 100, config.length);
            assertEquals(3, config[42] & 0x7F);
            assertEquals(5 * 18, read(config, 43, 3));
            assertEquals(0x81, config[42 + 4 + 90] & 0xFF, "the last block is a shorter padding block");
            assertEquals(6, read(config, 42 + 4 + 90 + 1, 3));
            for (var i = 0; i < 5; i++) {
                assertEquals(i * 7L * 44_100 / 4096 * 4096, read(config, 46 + 18 * i, 8), "every seventh second");
            }
            var exact = encode(options.toBuilder().padding(31 * 18).build(), samples).config();
            assertEquals(42 + 4 + 31 * 18, exact.length);
            assertEquals(0x83, exact[42] & 0xFF, "the seek table replaces the padding and is the last block");
            assertEquals(31 * 18, read(exact, 43, 3));
        }

        @Test
        @DisplayName("no seek table and no padding leave STREAMINFO as the only and last block")
        void minimal() {
            var encoded = encode(FlacEncoderOptions.defaults().toBuilder().noSeekTable().padding(0)
                    .build(), signal(Shape.MUSIC, 2, 16, 9_000, 6));
            assertEquals(42, encoded.config().length);
            assertEquals(0x80, encoded.config()[4] & 0xFF);
        }

        private void checkSeekTable(byte[] file, FlacEncoding.Encoded encoded, int points, int paddingAfter) {
            var header = encoded.config();
            assertArrayEquals(header, Arrays.copyOf(file, header.length), "the file starts with the final header");
            assertEquals(3, header[42] & 0x7F, "SEEKTABLE follows STREAMINFO");
            assertEquals(points * 18, read(header, 43, 3));
            var audio = header.length;
            var frameHeader = new FlacFrameHeader();
            for (var i = 0; i < points; i++) {
                var base = 46 + 18 * i;
                var sample = read(header, base, 8);
                var offset = read(header, base + 8, 8);
                assertEquals(i * 10L * 44_100 / 4096 * 4096, sample, "point " + i + " is the frame holding second " + 10 * i);
                assertTrue(frameHeader.parse(file, (int) (audio + offset), file.length), "point " + i + " targets a frame header");
                assertEquals(sample / 4096, frameHeader.number());
                assertEquals(4096, read(header, base + 16, 2));
            }
            var padding = 46 + 18 * points;
            assertEquals(0x81, header[padding] & 0xFF);
            assertEquals(paddingAfter, read(header, padding + 1, 3));
        }
    }

    @Nested
    @DisplayName("containers")
    class Containers {
        @Test
        @DisplayName("a native file reads back through FlacDemuxer with its final STREAMINFO")
        void nativeFlac() throws Exception {
            var samples = signal(Shape.MUSIC, 2, 16, 100_000, 7);
            var encoded = encode(FlacEncoderOptions.defaults(), samples);
            var file = nativeFile(encoded, 44_100);
            try (var demuxer = new FlacDemuxer(MediaSource.of(file))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.FLAC, track.codec());
                var config = track.codecConfig().orElseThrow();
                assertArrayEquals(Arrays.copyOfRange(encoded.config(), 8, 42), Arrays.copyOfRange(config, 8, 42));
                var decoder = new FlacDecoder(track);
                var position = 0;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    decoder.decode(packet, buffer -> {
                    });
                    for (var c = 0; c < 2; c++) {
                        assertArrayEquals(Arrays.copyOfRange(samples[c], position, position + decoder.lastBlockSize()),
                                Arrays.copyOf(decoder.lastSamples()[c], decoder.lastBlockSize()));
                    }
                    position += decoder.lastBlockSize();
                }
                assertEquals(100_000, position);
            }
        }

        @Test
        @DisplayName("a non-seekable native stream keeps the initial header, which is valid on its own")
        void streaming() throws Exception {
            var samples = signal(Shape.MUSIC, 2, 16, 20_000, 8);
            var encoded = encode(FlacEncoderOptions.defaults(), samples);
            var bytes = new java.io.ByteArrayOutputStream();
            try (var muxer = new com.github.auties00.smalt.container.raw.RawMuxer(MediaSink.of(bytes))) {
                muxer.addTrack(FlacEncoding.track(encoded.initialConfig(), 44_100));
                for (var packet : encoded.packets()) {
                    muxer.writePacket(packet);
                }
                muxer.updateCodecConfig(0, encoded.config());
                muxer.finish();
            }
            var file = bytes.toByteArray();
            assertArrayEquals(encoded.initialConfig(), Arrays.copyOf(file, encoded.initialConfig().length));
            try (var demuxer = new FlacDemuxer(MediaSource.of(file))) {
                var count = 0;
                while (demuxer.read() != null) {
                    count++;
                }
                assertEquals(encoded.packets().size(), count);
            }
        }

        @Test
        @DisplayName("MP4 carries the final STREAMINFO in dfLa without the seek table and padding")
        void mp4() throws Exception {
            var samples = signal(Shape.MUSIC, 2, 24, 60_000, 9);
            var options = FlacEncoderOptions.defaults().toBuilder().bitsPerSample(24)
                    .format(48_000, ChannelLayout.STEREO).build();
            var encoded = encode(options, samples);
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink)) {
                muxer.addTrack(FlacEncoding.track(encoded.initialConfig(), 48_000));
                for (var packet : encoded.packets()) {
                    muxer.writePacket(packet);
                }
                muxer.updateCodecConfig(0, encoded.config());
                muxer.finish();
            }
            try (var demuxer = new Mp4Demuxer(MediaSource.of(sink.toByteArray()))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var config = track.codecConfig().orElseThrow();
                assertEquals(42, config.length, "STREAMINFO only");
                assertArrayEquals(Arrays.copyOfRange(encoded.config(), 8, 42), Arrays.copyOfRange(config, 8, 42));
                var decoded = decodeAll(demuxer, track, 2);
                assertArrayEquals(samples[0], decoded[0]);
                assertArrayEquals(samples[1], decoded[1]);
            }
        }

        @Test
        @DisplayName("Matroska carries the STREAMINFO without the seek table and padding")
        void matroska() throws Exception {
            var samples = signal(Shape.MUSIC, 2, 16, 30_000, 10);
            var encoded = encode(FlacEncoderOptions.defaults(), samples);
            var sink = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(sink)) {
                muxer.addTrack(FlacEncoding.track(encoded.config(), 44_100));
                for (var packet : encoded.packets()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            try (var demuxer = new MatroskaDemuxer(MediaSource.of(sink.toByteArray()))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(42, track.codecConfig().orElseThrow().length);
                var decoded = decodeAll(demuxer, track, 2);
                assertArrayEquals(samples[0], decoded[0]);
                assertArrayEquals(samples[1], decoded[1]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"matroska", "ogg"})
        @DisplayName("Matroska and Ogg store the final STREAMINFO on a seekable sink and keep the initial one on a stream")
        void finalStreamInfo(String container, @TempDir Path directory) throws Exception {
            var samples = signal(Shape.MUSIC, 2, 24, 70_001, 12);
            var options = FlacEncoderOptions.defaults().toBuilder().bitsPerSample(24)
                    .format(48_000, ChannelLayout.STEREO).build();
            var encoded = encode(options, samples);
            var seekable = MediaSink.memory();
            muxFinal(container, seekable, encoded);
            var stream = new java.io.ByteArrayOutputStream();
            try (var sink = MediaSink.of(stream)) {
                muxFinal(container, sink, encoded);
            }
            var files = List.of(seekable.toByteArray(), stream.toByteArray());
            for (var index = 0; index < files.size(); index++) {
                var finalForm = index == 0;
                var source = MediaSource.of(files.get(index));
                try (var demuxer = container.equals("ogg") ? new com.github.auties00.smalt.container.ogg.OggDemuxer(source)
                        : new MatroskaDemuxer(source)) {
                    var track = (AudioTrack) demuxer.tracks().getFirst();
                    var config = track.codecConfig().orElseThrow();
                    var expected = finalForm ? encoded.config() : encoded.initialConfig();
                    assertArrayEquals(Arrays.copyOfRange(expected, 8, 42), Arrays.copyOfRange(config, 8, 42),
                            finalForm ? "final STREAMINFO" : "initial STREAMINFO");
                    var decoded = decodeAll(demuxer, track, 2);
                    assertArrayEquals(samples[0], decoded[0]);
                    assertArrayEquals(samples[1], decoded[1]);
                    if (finalForm) {
                        assertEquals(samples[0].length, read(config, 21, 5) & 0xFFFFFFFFFL, "total samples");
                        assertArrayEquals(md5(samples, 24), Arrays.copyOfRange(config, 26, 42), "MD5");
                    } else {
                        assertEquals(0, read(config, 26, 8) | read(config, 34, 8), "no MD5 before the end");
                    }
                }
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = directory.resolve("final." + (container.equals("ogg") ? "oga" : "mka"));
                Files.write(path, seekable.toByteArray());
                Oracle.assertDecodesCleanly(path);
                var native24 = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i",
                        path.toString(), "-map", "0:a:0", "-c:a", "copy", "-f", "flac", "-"));
                var extracted = directory.resolve("extracted.flac");
                Files.write(extracted, native24);
                assertArrayEquals(md5(samples, 24), Arrays.copyOfRange(native24, 26, 42), "MD5 ffmpeg copies out");
                assertEquals(samples[0].length, read(native24, 21, 5) & 0xFFFFFFFFFL, "total samples ffmpeg copies out");
                var pcm = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i",
                        extracted.toString(), "-f", "s24le", "-"));
                assertArrayEquals(md5(samples, 24), java.security.MessageDigest.getInstance("MD5").digest(pcm),
                        "ffmpeg's decode of the stored stream matches the samples");
                if (Oracle.find(Oracle.Tool.FLAC).isPresent()) {
                    Oracle.run(Oracle.Tool.FLAC, Duration.ofMinutes(1), List.of("-t", "-s", "-w", extracted.toString()));
                }
            }
        }

        private static void muxFinal(String container, MediaSink sink, FlacEncoding.Encoded encoded) {
            try (var muxer = container.equals("ogg") ? new com.github.auties00.smalt.container.ogg.OggMuxer(sink)
                    : new MatroskaMuxer(sink)) {
                muxer.addTrack(FlacEncoding.track(encoded.initialConfig(), 48_000));
                for (var packet : encoded.packets()) {
                    muxer.writePacket(packet);
                }
                muxer.updateCodecConfig(0, encoded.config());
                muxer.finish();
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"MATROSKA", "OGG"})
        @DisplayName("a transcode to Matroska or Ogg FLAC stores the final STREAMINFO")
        void transcodeToContainer(String name, @TempDir Path directory) throws Exception {
            var wav = com.github.auties00.smalt.testkit.Fixtures.ffmpeg("wav", List.of("-f", "lavfi", "-i",
                    "aevalsrc=exprs=0.5*sin(2*PI*440*t)|0.4*sin(2*PI*660*t):s=44100:d=7"), List.of("-c:a", "pcm_s16le"));
            var container = com.github.auties00.smalt.transcode.OutputContainer.valueOf(name);
            assertTrue(container.supports(Codec.FLAC));
            var sink = MediaSink.memory();
            com.github.auties00.smalt.transcode.Transcoder.transcode(MediaSource.of(wav), sink,
                    com.github.auties00.smalt.transcode.TranscodeOptions.of(container).toBuilder()
                            .audio(com.github.auties00.smalt.transcode.AudioTranscodeOptions.encode(Codec.FLAC))
                            .build());
            var source = MediaSource.of(sink.toByteArray());
            try (var demuxer = name.equals("OGG") ? new com.github.auties00.smalt.container.ogg.OggDemuxer(source)
                    : new MatroskaDemuxer(source)) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.FLAC, track.codec());
                var config = track.codecConfig().orElseThrow();
                assertEquals(7L * 44_100, read(config, 21, 5) & 0xFFFFFFFFFL, "total samples");
                var decoded = decodeAll(demuxer, track, 2);
                assertArrayEquals(md5(decoded, 16), Arrays.copyOfRange(config, 26, 42), "MD5 of the decoded samples");
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = directory.resolve("transcoded." + (name.equals("OGG") ? "oga" : "mka"));
                Files.write(path, sink.toByteArray());
                Oracle.assertDecodesCleanly(path);
            }
        }

        @Test
        @DisplayName("a transcode to native FLAC stores the final STREAMINFO and seek table")
        void transcode(@TempDir Path directory) throws Exception {
            var wav = com.github.auties00.smalt.testkit.Fixtures.ffmpeg("wav", List.of("-f", "lavfi", "-i",
                    "aevalsrc=exprs=0.5*sin(2*PI*440*t)|0.4*sin(2*PI*660*t):s=44100:d=25"), List.of("-c:a", "pcm_s16le"));
            var sink = MediaSink.memory();
            com.github.auties00.smalt.transcode.Transcoder.transcode(MediaSource.of(wav), sink,
                    com.github.auties00.smalt.transcode.TranscodeOptions.of(com.github.auties00.smalt.transcode.OutputContainer.FLAC)
                            .toBuilder()
                            .audio(com.github.auties00.smalt.transcode.AudioTranscodeOptions.encode(Codec.FLAC))
                            .build());
            var file = sink.toByteArray();
            assertEquals(25L * 44_100, read(file, 21, 5) & 0xFFFFFFFFFL, "total samples");
            assertNotEquals(0, read(file, 26, 8) | read(file, 34, 8), "MD5 signature");
            assertEquals(3, file[42] & 0x7F, "seek table carved from the padding");
            var path = directory.resolve("transcoded.flac");
            Files.write(path, file);
            if (Oracle.find(Oracle.Tool.FLAC).isPresent()) {
                Oracle.run(Oracle.Tool.FLAC, Duration.ofMinutes(1), List.of("-t", "-s", "-w", path.toString()));
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(path);
            }
        }

        private int[][] decodeAll(com.github.auties00.smalt.container.Demuxer demuxer, AudioTrack track, int channels) {
            var decoder = new FlacDecoder(track);
            var chunks = new ArrayList<int[][]>();
            var total = 0;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                decoder.decode(packet, buffer -> {
                });
                var size = decoder.lastBlockSize();
                var chunk = new int[channels][];
                for (var c = 0; c < channels; c++) {
                    chunk[c] = Arrays.copyOf(decoder.lastSamples()[c], size);
                }
                chunks.add(chunk);
                total += size;
            }
            var out = new int[channels][total];
            var position = 0;
            for (var chunk : chunks) {
                for (var c = 0; c < channels; c++) {
                    System.arraycopy(chunk[c], 0, out[c], position, chunk[c].length);
                }
                position += chunk[0].length;
            }
            return out;
        }
    }

    @Nested
    @DisplayName("reference decoders")
    class References {
        static Stream<Arguments> files() {
            return Stream.of(
                    Arguments.of("16-bit stereo level 5", FlacEncoderOptions.defaults(), Shape.MUSIC, 2, 16),
                    Arguments.of("8-bit mono level 0", FlacEncoderOptions.forSpeed().toBuilder()
                            .format(22_050, ChannelLayout.MONO).bitsPerSample(8).build(), Shape.MUSIC, 1, 8),
                    Arguments.of("24-bit 5.1 level 8", FlacEncoderOptions.forSize().toBuilder()
                            .format(96_000, ChannelLayout.SURROUND_5_1).bitsPerSample(24).build(), Shape.MUSIC, 6, 24),
                    Arguments.of("32-bit stereo", FlacEncoderOptions.defaults().toBuilder().bitsPerSample(32).build(), Shape.NOISE, 2, 32),
                    Arguments.of("20-bit escape coding", FlacEncoderOptions.defaults().toBuilder().bitsPerSample(20).escapeCoding(true).build(), Shape.IMPULSES, 2, 20),
                    Arguments.of("odd block size", FlacEncoderOptions.defaults().toBuilder().blockSize(1000).build(), Shape.SQUARE, 2, 16));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("files")
        @DisplayName("ffmpeg decodes native files without error to the input samples")
        void ffmpeg(String name, FlacEncoderOptions options, Shape shape, int channels, int bits, @TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var samples = signal(shape, channels, bits, 70_001, name.hashCode());
            var file = directory.resolve("encoded.flac");
            Files.write(file, nativeFile(encode(options, samples), options.sampleRate()));
            Oracle.assertDecodesCleanly(file);
            var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(), "-f", "s32le", "-"));
            var decoded = new int[raw.length / 4];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(decoded);
            assertEquals(70_001 * channels, decoded.length);
            for (var i = 0; i < 70_001; i++) {
                for (var c = 0; c < channels; c++) {
                    var expected = bits <= 16 ? samples[c][i] << (16 - bits) << 16 : samples[c][i] << (32 - bits);
                    assertEquals(expected, decoded[i * channels + c], "sample " + i + " channel " + c);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("files")
        @DisplayName("flac -t verifies native files, MD5 included")
        void reference(String name, FlacEncoderOptions options, Shape shape, int channels, int bits, @TempDir Path directory) throws Exception {
            Oracle.assume(Oracle.Tool.FLAC);
            var samples = signal(shape, channels, bits, 70_001, name.hashCode());
            var file = directory.resolve("encoded.flac");
            Files.write(file, nativeFile(encode(options, samples), options.sampleRate()));
            Oracle.run(Oracle.Tool.FLAC, Duration.ofMinutes(2), List.of("-t", "-s", "-w", file.toString()));
        }
    }

    @Nested
    @DisplayName("API")
    class Api {
        @Test
        @DisplayName("the registry creates a FLAC encoder from the codec-independent options")
        void registry() {
            assertTrue(AudioEncoders.isSupported(Codec.FLAC));
            try (var encoder = AudioEncoders.create(Codec.FLAC, AudioEncoderOptions.of(48_000, ChannelLayout.MONO))) {
                assertInstanceOf(FlacEncoder.class, encoder);
                assertEquals(48_000, encoder.sampleRate());
                assertEquals(ChannelLayout.MONO, encoder.layout());
                assertEquals(4096, encoder.frameSize());
                assertEquals(0, encoder.delay());
            }
            try (var encoder = AudioEncoders.create(Codec.FLAC, AudioEncoderOptions.forRealtime(48_000, ChannelLayout.STEREO))) {
                assertEquals(1152, encoder.frameSize());
            }
        }

        @Test
        @DisplayName("invalid input is rejected")
        void rejected() {
            var encoder = new FlacEncoder(FlacEncoderOptions.defaults());
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new int[1][4], 0, 4, packet -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new int[][]{{40000}, {0}}, 0, 1, packet -> {
            }));
            assertThrows(IndexOutOfBoundsException.class, () -> encoder.encode(new int[2][4], 2, 4, packet -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 44_100, 16),
                    packet -> {
                    }));
            encoder.flush(packet -> {
            });
            assertThrows(IllegalStateException.class, () -> encoder.encodeInterleaved(new int[2], 0, 1, packet -> {
            }));
        }

        @Test
        @DisplayName("interleaved and planar input give identical streams")
        void interleaved() {
            var samples = signal(Shape.MUSIC, 3, 16, 10_000, 11);
            var interleaved = new int[30_000];
            for (var i = 0; i < 10_000; i++) {
                for (var c = 0; c < 3; c++) {
                    interleaved[i * 3 + c] = samples[c][i];
                }
            }
            var options = FlacEncoderOptions.of(44_100, layout(3), 16);
            var planar = encode(options, samples);
            var encoder = new FlacEncoder(options);
            var packets = new ArrayList<Packet>();
            encoder.encodeInterleaved(interleaved, 0, 10_000, packets::add);
            encoder.flush(packets::add);
            assertEquals(planar.packets().size(), packets.size());
            for (var i = 0; i < packets.size(); i++) {
                assertArrayEquals(planar.packets().get(i).data(), packets.get(i).data());
            }
        }
    }
}
