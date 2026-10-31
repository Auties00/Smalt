package com.github.auties00.smalt.audio.ape;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.ape.ApeDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the Monkey's Audio vectors of {@code src/test/resources/com/github/auties00/smalt/audio/ape} (made by
 * {@code tools/testdata/ape/make-vectors.py} with the Monkey's Audio 13.45 encoder at every compression level, for
 * 8 to 32-bit integer and 32-bit floating-point samples in one to eight channels, silence and identical channels)
 * and compares every sample with the MD5 of the reference library's decode; decodes the FFmpeg FATE files that
 * Monkey's Audio 3.80 to 3.99 wrote against the reference library's frame-by-frame decode; and checks seeking, the
 * tags and the rejection of corrupt frames.
 */
@DisplayName("Monkey's Audio decoder")
class ApeDecoderTest {
    static byte[] resource(String name) {
        try (var in = ApeDecoderTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Vector(String name, String md5, int bits, boolean floating) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> vectors() {
        var vectors = new ArrayList<Vector>();
        for (var line : new String(resource("expected.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t");
            vectors.add(new Vector(fields[0], fields[1], Integer.parseInt(fields[2]), fields[3].equals("float")));
        }
        return vectors.stream();
    }

    static List<Packet> packets(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            packets.add(packet);
        }
        return packets;
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Writes a decoded block as the reference decoder's WAVE data does: interleaved little-endian samples, 8-bit
     * samples unsigned, floats as their bits.
     */
    static void raw(ByteArrayOutputStream out, int[][] planes, int frames, int bits) {
        var bytes = bits / 8;
        for (var i = 0; i < frames; i++) {
            for (var plane : planes) {
                var value = plane[i] + (bytes == 1 ? 128 : 0);
                for (var k = 0; k < bytes; k++) {
                    out.write(value >> (8 * k));
                }
            }
        }
    }

    static byte[] decode(AudioDecoder decoder, List<Packet> packets, boolean floating, int bits) {
        var out = new ByteArrayOutputStream();
        for (var packet : packets) {
            decodeOne(decoder, packet, floating, bits, out);
        }
        return out.toByteArray();
    }

    static void decodeOne(AudioDecoder decoder, Packet packet, boolean floating, int bits,
                          ByteArrayOutputStream out) {
        if (floating) {
            decoder.decodeDoubles(packet, block -> {
                var planes = new int[block.channels()][block.frames()];
                for (var c = 0; c < block.channels(); c++) {
                    for (var i = 0; i < block.frames(); i++) {
                        planes[c][i] = Float.floatToRawIntBits((float) block.plane(c)[i]);
                    }
                }
                raw(out, planes, block.frames(), 32);
            });
        } else {
            decoder.decodeIntegers(packet, block -> {
                assertEquals(bits, block.bitsPerSample());
                var planes = new int[block.channels()][];
                for (var c = 0; c < block.channels(); c++) {
                    planes[c] = block.plane(c);
                }
                raw(out, planes, block.frames(), bits);
            });
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes every vector bit for bit as Monkey's Audio 13.45 does")
    void vector(Vector vector) {
        var source = MediaSource.of(resource(vector.name() + ".ape"));
        try (var demuxer = Containers.openDemuxer(source)) {
            assertEquals(ContainerFormat.APE, demuxer.format());
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.APE, track.codec());
            assertEquals(vector.bits(), track.bitsPerSample());
            var packets = packets(demuxer);
            try (var decoder = AudioDecoders.create(track)) {
                assertEquals(!vector.floating(), decoder.supportsIntegerOutput());
                var decoded = decode(decoder, packets, vector.floating(), vector.bits());
                assertEquals(vector.md5(), md5(decoded));
                var channels = track.layout().orElseThrow().channels();
                assertEquals(track.duration(), decoded.length / (vector.bits() / 8) / channels);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("outputs floats equal to the integers scaled by 2^-(bits - 1)")
    void floats(Vector vector) {
        try (var demuxer = new ApeDemuxer(MediaSource.of(resource(vector.name() + ".ape")))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var packets = packets(demuxer);
            try (var floats = new ApeDecoder(track); var exact = new ApeDecoder(track)) {
                for (var packet : packets.subList(0, Math.min(2, packets.size()))) {
                    var expected = new ArrayList<float[]>();
                    exact.decodeDoubles(packet, block -> {
                        for (var c = 0; c < block.channels(); c++) {
                            var plane = new float[block.frames()];
                            for (var i = 0; i < plane.length; i++) {
                                plane[i] = (float) block.plane(c)[i];
                            }
                            expected.add(plane);
                        }
                    });
                    floats.decode(packet, block -> {
                        for (var c = 0; c < block.channels(); c++) {
                            var plane = new float[block.frames()];
                            System.arraycopy(block.plane(c), 0, plane, 0, plane.length);
                            assertArrayEquals(expected.get(c), plane);
                        }
                    });
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "luckynight-mac380-c2000.ape, 138240, 7f00b9f07684d3aa561f0217fc398012",
            "luckynight-mac380-c4000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-mac388-c2000.ape, 138240, 7f00b9f07684d3aa561f0217fc398012",
            "luckynight-mac388-c4000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-mac389b1-c2000.ape, 138240, 7f00b9f07684d3aa561f0217fc398012",
            "luckynight-mac389b1-c4000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-mac391b1-c2000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-mac391b1-c4000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-mac392b2-c2000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-mac392b2-c4000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-mac394b1-c2000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-mac394b1-c4000.ape, 147456, 9bf12f930eea5fcea8281957c3df9edb",
            "luckynight-partial.ape, 368640, e6498196d728f92dc5c9b51eca9a41e5",
    })
    @DisplayName("decodes the files of Monkey's Audio 3.80 to 3.99 as the reference library does, up to the cut")
    void legacy(String name, long samples, String md5) throws IOException {
        var file = Conformance.fetchListed("fate-" + name);
        try (var demuxer = new ApeDemuxer(MediaSource.of(Files.readAllBytes(file)))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            try (var decoder = AudioDecoders.create(track)) {
                var out = new ByteArrayOutputStream();
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    try {
                        decodeOne(decoder, packet, false, track.bitsPerSample(), out);
                    } catch (MediaException.Malformed exception) {
                        break;
                    }
                }
                var decoded = out.toByteArray();
                assertEquals(samples, decoded.length / (track.bitsPerSample() / 8) / 2);
                assertEquals(md5, md5(decoded));
            }
        }
    }

    @Nested
    @DisplayName("the demuxer")
    class Demuxing {
        @Test
        @DisplayName("seeks to the frame holding the target, from which the decode equals the linear decode")
        void seek() {
            var data = resource("s16-mono-long-c2000.ape");
            byte[] linear;
            List<Packet> all;
            AudioTrack track;
            try (var demuxer = new ApeDemuxer(MediaSource.of(data))) {
                track = (AudioTrack) demuxer.tracks().getFirst();
                all = packets(demuxer);
                assertTrue(all.size() >= 2);
            }
            try (var decoder = new ApeDecoder(track)) {
                linear = decode(decoder, all.subList(1, all.size()), false, 16);
            }
            try (var demuxer = new ApeDemuxer(MediaSource.of(data))) {
                assertTrue(demuxer.isSeekable());
                var second = all.get(1).pts();
                demuxer.seek(track.timeBase().toMicros(second + 10));
                var rest = packets(demuxer);
                assertEquals(second, rest.getFirst().pts());
                try (var decoder = new ApeDecoder(track)) {
                    assertArrayEquals(linear, decode(decoder, rest, false, 16));
                }
                demuxer.seek(demuxer.durationMicros() + 1);
                assertEquals(null, demuxer.read());
            }
        }

        @Test
        @DisplayName("reads the APEv2 tag the encoder appended")
        void tags() {
            try (var demuxer = new ApeDemuxer(MediaSource.of(resource("s16-stereo-tagged-c2000.ape")))) {
                var builder = MediaTags.builder();
                demuxer.metadata().forEach(builder::add);
                var tags = builder.build();
                assertEquals("Smalt Vectors", tags.get(StandardTag.ARTIST).orElseThrow());
                assertEquals("Tagged Tone", tags.get(StandardTag.TITLE).orElseThrow());
                assertEquals("Monkey Test", tags.get(StandardTag.ALBUM).orElseThrow());
            }
        }

        @Test
        @DisplayName("finds the header after a leading ID3v2 tag")
        void leadingId3() {
            var ape = resource("s16-stereo-c2000.ape");
            var id3 = new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 20, 'T', 'I', 'T', '2', 0, 0, 0, 6, 0, 0, 3, 'L',
                    'e', 'a', 'd', 0, 0, 0, 0, 0};
            var joined = new byte[id3.length + ape.length];
            System.arraycopy(id3, 0, joined, 0, id3.length);
            System.arraycopy(ape, 0, joined, id3.length, ape.length);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(joined))) {
                assertEquals(ContainerFormat.APE, demuxer.format());
                var track = (AudioTrack) demuxer.tracks().getFirst();
                try (var decoder = AudioDecoders.create(track)) {
                    var expected = vectors().filter(v -> v.name().equals("s16-stereo-c2000")).findFirst()
                            .orElseThrow().md5();
                    assertEquals(expected, md5(decode(decoder, packets(demuxer), false, 16)));
                }
            }
        }
    }

    @Nested
    @DisplayName("corrupt input")
    class Corrupt {
        @Test
        @DisplayName("a flipped bit of a frame fails its CRC")
        void flippedBit() {
            try (var demuxer = new ApeDemuxer(MediaSource.of(resource("s16-stereo-c3000.ape")))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var packet = demuxer.read();
                var data = packet.data().clone();
                data[data.length / 2] ^= 0x10;
                try (var decoder = new ApeDecoder(track)) {
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(
                            new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(), true),
                            block -> {
                            }));
                }
            }
        }

        @Test
        @DisplayName("a truncated frame raises Malformed")
        void truncated() {
            try (var demuxer = new ApeDemuxer(MediaSource.of(resource("s24-stereo-c4000.ape")))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var packet = demuxer.read();
                var data = java.util.Arrays.copyOf(packet.data(), packet.data().length / 3);
                try (var decoder = new ApeDecoder(track)) {
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(
                            new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(), true),
                            block -> {
                            }));
                }
            }
        }

        @Test
        @DisplayName("a frame declaring more samples than the header's frame size raises Malformed before allocating")
        void oversizedFrame() {
            try (var demuxer = new ApeDemuxer(MediaSource.of(resource("s16-stereo-c3000.ape")))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var packet = demuxer.read();
                var data = packet.data().clone();
                // 9,999,999 samples per channel, within the old fixed bound but above every header's frame size
                data[0] = (byte) 0x7F;
                data[1] = (byte) 0x96;
                data[2] = (byte) 0x98;
                data[3] = 0;
                try (var decoder = new ApeDecoder(track)) {
                    var error = assertThrows(MediaException.Malformed.class, () -> decoder.decode(
                            new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(), true),
                            block -> {
                            }));
                    assertTrue(error.getMessage().contains("9999999"), error.getMessage());
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.ape.ApeDecoderTest#vectors")
        @DisplayName("random damage raises only MediaException")
        void damage(Vector vector) {
            var original = resource(vector.name() + ".ape");
            var random = new Random(vector.name().hashCode());
            for (var round = 0; round < 8; round++) {
                var data = original.clone();
                for (var i = 0; i < 6; i++) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt();
                }
                try (var demuxer = new ApeDemuxer(MediaSource.of(data))) {
                    var track = (AudioTrack) demuxer.tracks().getFirst();
                    try (var decoder = AudioDecoders.create(track)) {
                        Packet packet;
                        while ((packet = demuxer.read()) != null) {
                            decoder.decode(packet, block -> {
                            });
                        }
                    }
                } catch (MediaException expected) {
                    assertFalse(expected.getMessage() == null);
                }
            }
        }
    }
}
