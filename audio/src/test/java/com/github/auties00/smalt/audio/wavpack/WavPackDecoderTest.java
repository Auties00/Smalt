package com.github.auties00.smalt.audio.wavpack;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.wavpack.WavPackDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the WavPack vectors of {@code src/test/resources/com/github/auties00/smalt/audio/wavpack} (made by
 * {@code tools/testdata/wavpack/make-vectors.py} with the WavPack 5.8.1 encoder) and compares every sample with the
 * MD5 of {@code wvunpack -r}'s decode recorded in {@code expected.tsv}: lossless integer, floating-point and DSD
 * streams, hybrid lossy streams and hybrid lossless reconstruction with the correction files, multichannel streams,
 * also after a round trip through Matroska's headerless {@code A_WAVPACK4} form.
 */
@DisplayName("WavPack decoder")
class WavPackDecoderTest {
    static byte[] resource(String name) {
        try (var in = WavPackDecoderTest.class.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Vector(String name, boolean correction, String md5) {
        @Override
        public String toString() {
            return name + (correction ? " with its correction file" : "");
        }
    }

    static Stream<Vector> vectors() {
        var lines = new String(resource("expected.tsv"), StandardCharsets.UTF_8).split("\n");
        var vectors = new ArrayList<Vector>();
        for (var line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t");
            var lossy = fields[0].endsWith("#lossy");
            var name = lossy ? fields[0].substring(0, fields[0].length() - 6) : fields[0];
            vectors.add(new Vector(name, !lossy && resource(name + ".wvc") != null, fields[2]));
        }
        return vectors.stream();
    }

    record Stream_(AudioTrack track, List<Packet> packets) {
    }

    static Stream_ demux(String name, boolean correction) {
        var wvc = correction ? resource(name + ".wvc") : null;
        try (var demuxer = new WavPackDemuxer(MediaSource.of(resource(name + ".wv")),
                wvc == null ? null : MediaSource.of(wvc), DemuxerOptions.defaults())) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
            return new Stream_(track, packets);
        }
    }

    /**
     * Decodes packets into wvunpack's raw output: interleaved little-endian samples of the stream's bytes per
     * sample, left justified, 8-bit samples unsigned, floats as their bits.
     */
    static byte[] raw(AudioDecoder decoder, List<Packet> packets, boolean floating) {
        var out = new ByteArrayOutputStream();
        for (var packet : packets) {
            if (floating) {
                decoder.decodeDoubles(packet, block -> {
                    for (var i = 0; i < block.frames(); i++) {
                        for (var c = 0; c < block.channels(); c++) {
                            var bits = Float.floatToRawIntBits((float) block.plane(c)[i]);
                            for (var k = 0; k < 4; k++) {
                                out.write(bits >> (8 * k));
                            }
                        }
                    }
                });
            } else {
                decoder.decodeIntegers(packet, block -> {
                    var bytes = (block.bitsPerSample() + 7) / 8;
                    var shift = bytes * 8 - block.bitsPerSample();
                    for (var i = 0; i < block.frames(); i++) {
                        for (var c = 0; c < block.channels(); c++) {
                            var value = block.plane(c)[i] << shift;
                            if (bytes == 1) {
                                value += 128;
                            }
                            for (var k = 0; k < bytes; k++) {
                                out.write(value >> (8 * k));
                            }
                        }
                    }
                });
            }
        }
        return out.toByteArray();
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static boolean floating(String name) {
        return name.startsWith("f32") || name.startsWith("hybrid-f32");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes every vector sample for sample as the reference decoder does")
    void reference(Vector vector) {
        var stream = demux(vector.name(), vector.correction());
        var decoder = AudioDecoders.create(stream.track());
        assertInstanceOf(WavPackDecoder.class, decoder);
        assertEquals(vector.md5(), md5(raw(decoder, stream.packets(), floating(vector.name()))), vector.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes the headerless blocks of a Matroska remux sample for sample")
    void matroska(Vector vector) {
        var stream = demux(vector.name(), vector.correction());
        var sink = MediaSink.memory();
        var muxer = new MatroskaMuxer(sink);
        muxer.addTrack(stream.track());
        stream.packets().forEach(muxer::writePacket);
        muxer.finish();
        try (var demuxer = new MatroskaDemuxer(MediaSource.of(sink.toByteArray()))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.WAVPACK, track.codec());
            assertEquals(2, track.codecConfig().orElseThrow().length);
            assertEquals(stream.track().sampleRate(), track.sampleRate());
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
                assertTrue(packet.data().length < 4 || packet.data()[0] != 'w' || packet.data()[1] != 'v',
                        "a Matroska WavPack block carries no block header");
            }
            assertEquals(stream.packets().size(), packets.size());
            var decoder = new WavPackDecoder(track);
            assertEquals(vector.md5(), md5(raw(decoder, packets, floating(vector.name()))), vector.toString());
        }
    }

    static Stream<Vector> withoutCorrection() {
        return vectors().filter(vector -> !vector.correction());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withoutCorrection")
    @DisplayName("writes the headerless blocks of a Matroska remux back as a .wv file that decodes sample for sample")
    void rawMuxer(Vector vector) throws IOException {
        var stream = demux(vector.name(), false);
        var mkv = MediaSink.memory();
        var muxer = new MatroskaMuxer(mkv);
        muxer.addTrack(stream.track());
        stream.packets().forEach(muxer::writePacket);
        muxer.finish();
        var wv = MediaSink.memory();
        try (var demuxer = new MatroskaDemuxer(MediaSource.of(mkv.toByteArray()));
             var raw = Containers.openMuxer(ContainerFormat.WAVPACK, wv)) {
            raw.addTrack(demuxer.tracks().getFirst());
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                raw.writePacket(packet);
            }
            raw.finish();
        }
        var data = wv.toByteArray();
        assertEquals(ContainerFormat.WAVPACK, Containers.detect(MediaSource.of(data)).orElseThrow());
        try (var demuxer = new WavPackDemuxer(MediaSource.of(data))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(stream.track().sampleRate(), track.sampleRate());
            assertEquals(stream.track().layout(), track.layout());
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            assertEquals(stream.packets().stream().map(Packet::pts).toList(), packets.stream().map(Packet::pts).toList());
            assertEquals(vector.md5(), md5(raw(new WavPackDecoder(track), packets, floating(vector.name()))),
                    vector.toString());
        }
        if (vector.name().startsWith("s16") && com.github.auties00.smalt.testkit.Oracle.find(
                com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG).isPresent()) {
            // ffmpeg's WavPack decoder reads the rebuilt headers too, and is lossless
            var file = com.github.auties00.smalt.testkit.Scratch.directory("wavpack").resolve(vector.name() + ".wv");
            java.nio.file.Files.write(file, data);
            var decoded = com.github.auties00.smalt.testkit.Oracle.run(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG,
                    java.time.Duration.ofMinutes(1), List.of("-v", "error", "-i", file.toAbsolutePath().toString(),
                            "-f", "s16le", "-"));
            assertEquals(vector.md5(), md5(decoded), vector + " decoded by ffmpeg");
        }
    }

    @Test
    @DisplayName("rejects a headerless packet without a stream version or with a block running past it, writing nothing")
    void rawMuxerMalformed() {
        var track = AudioTrack.builder().id(1).codec(Codec.WAVPACK).timeBase(Rational.timeBaseOf(44100))
                .sampleRate(44100).layout(ChannelLayout.STEREO).codecConfig(new byte[]{0x10, 0x04}).build();
        var sink = MediaSink.memory();
        try (var raw = Containers.openMuxer(ContainerFormat.WAVPACK, sink)) {
            raw.addTrack(track);
            // two blocks, initial but not final, the second claiming 4096 bytes
            var packet = new byte[]{16, 0, 0, 0, 0, 0x08, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 1, 2, 3, 4,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10, 0, 0};
            assertThrows(IllegalArgumentException.class, () -> raw.writePacket(new Packet(1, packet, 0, 0, 16, true)));
            assertThrows(IllegalArgumentException.class, () -> raw.writePacket(new Packet(1, new byte[8], 0, 0, 16,
                    true)));
        }
        assertEquals(0, sink.toByteArray().length);
    }

    @Nested
    @DisplayName("track description")
    class Description {
        @Test
        @DisplayName("recognizes the container and reads rate, layout, size and length")
        void stereo() {
            var data = resource("s16-stereo.wv");
            assertEquals(ContainerFormat.WAVPACK, Containers.detect(MediaSource.of(data)).orElseThrow());
            var stream = demux("s16-stereo", false);
            var track = stream.track();
            assertEquals(44100, track.sampleRate());
            assertEquals(ChannelLayout.STEREO, track.layout().orElseThrow());
            assertEquals(16, track.bitsPerSample());
            assertEquals(4410, track.duration());
            assertEquals(Rational.timeBaseOf(44100), track.timeBase());
            var total = stream.packets().stream().mapToLong(Packet::duration).sum();
            assertEquals(4410, total);
            assertEquals(0, stream.packets().getFirst().pts());
        }

        @Test
        @DisplayName("lays out the channels of the channel mask")
        void surround() {
            var track = demux("s16-5.1", false).track();
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT), track.layout().orElseThrow().speakers());
            var decoder = new WavPackDecoder(track);
            assertEquals(track.layout().orElseThrow(), decoder.layout());
        }

        @Test
        @DisplayName("reports the coded sample size of shifted, floating-point and DSD streams")
        void sizes() {
            assertEquals(12, demux("s12-mono", false).track().bitsPerSample());
            assertEquals(20, demux("s20-stereo", false).track().bitsPerSample());
            assertEquals(8, demux("u8-mono", false).track().bitsPerSample());
            assertEquals(32, demux("f32-stereo", false).track().bitsPerSample());
            var dsd = demux("dsd64-stereo", false).track();
            assertEquals(24, dsd.bitsPerSample());
            assertEquals(2822400 / 8, dsd.sampleRate());
        }

        @Test
        @DisplayName("reads the APEv2 tag at the end of the file")
        void tags() {
            try (var demuxer = new WavPackDemuxer(MediaSource.of(resource("s16-stereo-md5.wv")))) {
                var title = demuxer.metadata().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("title"))
                        .map(java.util.Map.Entry::getValue).findFirst();
                assertEquals("Smalt", title.orElseThrow());
            }
        }

        @Test
        @DisplayName("seeks to the frame holding the target sample")
        void seek() {
            var stream = demux("s16-small-blocks", false);
            try (var demuxer = new WavPackDemuxer(MediaSource.of(resource("s16-small-blocks.wv")))) {
                assertTrue(demuxer.isSeekable());
                var target = 2500;
                demuxer.seek(target * 1_000_000L / 44100);
                var packet = demuxer.read();
                assertTrue(packet.pts() <= target && packet.pts() + packet.duration() > target, "pts " + packet.pts());
                var expected = stream.packets().stream().filter(p -> p.pts() == packet.pts()).findFirst().orElseThrow();
                assertArrayEquals(expected.data(), packet.data());
                demuxer.seek(0);
                assertEquals(0, demuxer.read().pts());
            }
        }
    }

    @Nested
    @DisplayName("outputs")
    class Outputs {
        @Test
        @DisplayName("scales integers to floats by 2^-(b - 1)")
        void floats() {
            var stream = demux("s20-stereo", false);
            var integers = new ArrayList<int[]>();
            var decoder = new WavPackDecoder(stream.track());
            for (var packet : stream.packets()) {
                decoder.decodeIntegers(packet, block -> integers.add(block.plane(1).clone()));
            }
            var floats = new ArrayList<float[]>();
            var other = new WavPackDecoder(stream.track());
            for (var packet : stream.packets()) {
                other.decode(packet, block -> floats.add(java.util.Arrays.copyOf(block.plane(1), block.frames())));
            }
            assertEquals(integers.size(), floats.size());
            for (var b = 0; b < floats.size(); b++) {
                for (var i = 0; i < floats.get(b).length; i++) {
                    assertEquals(integers.get(b)[i] * 0x1p-19f, floats.get(b)[i]);
                }
            }
        }

        @Test
        @DisplayName("has no integer output for floating-point samples")
        void floatingIntegers() {
            var stream = demux("f32-stereo", false);
            var decoder = new WavPackDecoder(stream.track());
            assertThrows(MediaException.Unsupported.class,
                    () -> decoder.decodeIntegers(stream.packets().getFirst(), block -> { }));
        }

        @Test
        @DisplayName("verifies the stored MD5 signature on flush")
        void md5() {
            var stream = demux("s16-stereo-md5", false);
            var decoder = new WavPackDecoder(stream.track(), AudioDecoderOptions.builder().verifyIntegrity(true).build());
            for (var packet : stream.packets()) {
                decoder.decodeIntegers(packet, block -> { });
            }
            assertDoesNotThrow(() -> decoder.flushIntegers(block -> { }));
            var tampered = WavPackBlocks.tamperMd5(resource("s16-stereo-md5.wv"));
            List<Packet> packets;
            AudioTrack track;
            try (var demuxer = new WavPackDemuxer(MediaSource.of(tampered))) {
                track = (AudioTrack) demuxer.tracks().getFirst();
                packets = new ArrayList<>();
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    packets.add(packet);
                }
            }
            var checking = new WavPackDecoder(track, AudioDecoderOptions.builder().verifyIntegrity(true).build());
            for (var packet : packets) {
                checking.decodeIntegers(packet, block -> { });
            }
            assertThrows(MediaException.Malformed.class, () -> checking.flushIntegers(block -> { }));
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"s16-stereo", "hybrid-s16-stereo", "f32-stereo", "dsd64-stereo", "s16-5.1", "s32-stereo"})
        @DisplayName("raises MediaException.Malformed for every flipped byte of a block")
        void flips(String name) {
            var stream = demux(name, false);
            var random = new Random(name.hashCode());
            var packet = stream.packets().getFirst();
            for (var trial = 0; trial < 200; trial++) {
                var data = packet.data().clone();
                var at = 32 + random.nextInt(data.length - 32);
                data[at] ^= (byte) (1 + random.nextInt(255));
                var decoder = new WavPackDecoder(stream.track());
                var corrupted = packet.withData(data);
                var thrown = assertThrows(MediaException.class, () -> decoder.decode(corrupted, block -> { }),
                        "byte " + at);
                assertInstanceOf(MediaException.Malformed.class, thrown, "byte " + at);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"s16-stereo", "hybrid-s16-stereo-lossless", "dsd64-mono", "s16-5.1"})
        @DisplayName("raises MediaException.Malformed for a truncated packet")
        void truncated(String name) {
            var stream = demux(name, false);
            var packet = stream.packets().getFirst();
            for (var length : new int[]{0, 1, 31, 32, packet.data().length / 2, packet.data().length - 1}) {
                var decoder = new WavPackDecoder(stream.track());
                var cut = packet.withData(java.util.Arrays.copyOf(packet.data(), length));
                if (length == 0) {
                    assertDoesNotThrow(() -> decoder.decode(cut, block -> { }));
                } else {
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(cut, block -> { }),
                            "length " + length);
                }
            }
        }

        @Test
        @DisplayName("rejects correction data whose bitstream was altered, even under a valid block checksum")
        void alteredCorrection() {
            var main = demux("hybrid-s16-stereo-lossless", true);
            var packet = main.packets().getFirst();
            var correction = packet.sideData().extra(WavPackDecoder.CORRECTION_KEY).orElseThrow();
            for (var at : new int[]{correction.length / 2, correction.length / 3}) {
                var altered = WavPackBlocks.alterBitstream(correction, at);
                var decoder = new WavPackDecoder(main.track());
                var corrupted = packet.withSideData(packet.sideData().toBuilder()
                        .extra(WavPackDecoder.CORRECTION_KEY, altered).build());
                assertThrows(MediaException.Malformed.class, () -> decoder.decode(corrupted, block -> { }), "at " + at);
            }
        }
    }
}
