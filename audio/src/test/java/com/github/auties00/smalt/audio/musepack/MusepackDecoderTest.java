package com.github.auties00.smalt.audio.musepack;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.musepack.MusepackDemuxer;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the Musepack vectors of {@code src/test/resources/com/github/auties00/smalt/audio/musepack} (stream version
 * 8, made by {@code tools/testdata/musepack/make-vectors.py} with mpcenc 1.30.1) and the version 7 and 8 files of the
 * FFmpeg FATE suite and samples archive, and compares every float with the decode of libmpcdec r475 (its
 * floating-point build, through {@code tools/testdata/musepack-oracle}); checks the timestamps, seeking, the tags and
 * the rejection of damaged frames.
 */
@DisplayName("Musepack decoder")
class MusepackDecoderTest {
    static byte[] resource(String name) {
        try (var in = MusepackDecoderTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Vector(String name, String md5, long samples, int channels) {
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
            var f = line.split("\t");
            vectors.add(new Vector(f[0], f[1], Long.parseLong(f[2]), Integer.parseInt(f[3])));
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
     * Decodes packets into interleaved little-endian float bits, as the reference driver writes them.
     */
    static byte[] decode(AudioDecoder decoder, List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        for (var packet : packets) {
            decoder.decode(packet, block -> {
                for (var i = 0; i < block.frames(); i++) {
                    for (var c = 0; c < block.channels(); c++) {
                        var bits = Float.floatToRawIntBits(block.plane(c)[i]);
                        for (var k = 0; k < 4; k++) {
                            out.write(bits >> (8 * k));
                        }
                    }
                }
            });
        }
        decoder.flush(block -> {
        });
        return out.toByteArray();
    }

    static void check(byte[] file, String md5, long samples, int channels) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertEquals(ContainerFormat.MUSEPACK, demuxer.format());
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(samples, track.duration());
            assertEquals(channels, track.layout().orElseThrow().channels());
            try (var decoder = AudioDecoders.create(track)) {
                var decoded = decode(decoder, packets(demuxer));
                assertEquals(samples, decoded.length / 4 / channels);
                assertEquals(md5, md5(decoded));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("decodes every stream version 8 vector bit for bit as libmpcdec does")
    void vector(Vector vector) {
        check(resource(vector.name() + ".mpc"), vector.md5(), vector.samples(), vector.channels());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "fate-inside-mp7.mpc, e223101a29356a9bcf90d4bd81bdfce2, 524277",
            "fate-inside-mp8.mpc, 8cd0482bc927d423fcb925ed6933549b, 524277",
            "samples-musepack-right-here-right-now.mpc, a28afec7d1f349ea87a1bc049692d8bc, 17111976",
            "samples-musepack-kalifornia.mpc, f1717894b95e634bc3c2e843001725b7, 15586704",
            "samples-musepack-sv8-cpe.mpc, b83220ba4c096ac2b6ef847c6a0a9829, 12001088",
            "samples-musepack-sv8-choral.mpc, c5535cdb27bff0de1d971d7ae67dfe52, 3747456",
            "samples-musepack-sv8-tags-and-chaps.mpc, 029466546aa5074609c15e79de566532, 2245572",
    })
    @DisplayName("decodes the version 7 and 8 sample files bit for bit as libmpcdec does")
    void samples(String name, String md5, long samples) throws IOException {
        check(Files.readAllBytes(Conformance.fetchListed(name)), md5, samples, 2);
    }

    @Nested
    @DisplayName("the demuxer")
    class Demuxing {
        @Test
        @DisplayName("timestamps packets in output samples, the decoder's delay first")
        void timestamps() {
            try (var demuxer = new MusepackDemuxer(MediaSource.of(resource("stereo-standard.mpc")))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.MUSEPACK8, track.codec());
                var packets = packets(demuxer);
                assertEquals(-481, packets.getFirst().pts());
                assertTrue(packets.stream().allMatch(Packet::keyframe));
                for (var i = 1; i < packets.size(); i++) {
                    assertEquals(packets.get(i - 1).pts() + packets.get(i - 1).duration(), packets.get(i).pts());
                }
            }
        }

        @Test
        @DisplayName("seeks to the audio packet holding the target, whose decode is exact after one frame")
        void seek() {
            var data = resource("stereo-standard.mpc");
            float[] linear;
            AudioTrack track;
            List<Packet> all;
            try (var demuxer = new MusepackDemuxer(MediaSource.of(data))) {
                track = (AudioTrack) demuxer.tracks().getFirst();
                all = packets(demuxer);
                assertTrue(all.size() >= 2);
            }
            try (var decoder = new MusepackDecoder(track)) {
                linear = floats(decode(decoder, all));
            }
            try (var demuxer = new MusepackDemuxer(MediaSource.of(data))) {
                var target = all.get(1).pts() + 100;
                demuxer.seek(track.timeBase().toMicros(target));
                var rest = packets(demuxer);
                assertEquals(all.get(1).pts(), rest.getFirst().pts());
                try (var decoder = new MusepackDecoder(track)) {
                    var restarted = floats(decode(decoder, rest));
                    // the restarted decoder drops its own delay again; past one frame the samples equal the linear decode
                    var start = (int) (rest.getFirst().pts() + 481 + 1152) * 2;
                    var from = 1152 * 2;
                    var compare = Math.min(linear.length - start, restarted.length - from);
                    assertTrue(compare > 0);
                    assertArrayEquals(java.util.Arrays.copyOfRange(linear, start, start + compare),
                            java.util.Arrays.copyOfRange(restarted, from, from + compare));
                }
            }
        }

        @Test
        @DisplayName("reads the APEv2 tag the encoder appended")
        void tags() {
            try (var demuxer = new MusepackDemuxer(MediaSource.of(resource("stereo-tagged.mpc")))) {
                var builder = MediaTags.builder();
                demuxer.metadata().forEach(builder::add);
                var tags = builder.build();
                assertEquals("Smalt Vectors", tags.get(StandardTag.ARTIST).orElseThrow());
                assertEquals("Tagged Musepack", tags.get(StandardTag.TITLE).orElseThrow());
            }
        }
    }

    static float[] floats(byte[] raw) {
        var out = new float[raw.length / 4];
        for (var i = 0; i < out.length; i++) {
            out[i] = Float.intBitsToFloat((raw[4 * i] & 0xFF) | (raw[4 * i + 1] & 0xFF) << 8
                    | (raw[4 * i + 2] & 0xFF) << 16 | raw[4 * i + 3] << 24);
        }
        return out;
    }

    @Nested
    @DisplayName("corrupt input")
    class Corrupt {
        @Test
        @DisplayName("an audio packet cut short raises Malformed")
        void truncated() {
            try (var demuxer = new MusepackDemuxer(MediaSource.of(resource("stereo-braindead.mpc")))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                var packet = demuxer.read();
                assertNotNull(packet);
                var data = java.util.Arrays.copyOf(packet.data(), packet.data().length / 2);
                try (var decoder = new MusepackDecoder(track)) {
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(
                            new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(), true),
                            block -> {
                            }));
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.musepack.MusepackDecoderTest#vectors")
        @DisplayName("random damage raises only MediaException")
        void damage(Vector vector) {
            var original = resource(vector.name() + ".mpc");
            var random = new Random(vector.name().hashCode());
            for (var round = 0; round < 8; round++) {
                var data = original.clone();
                for (var i = 0; i < 6; i++) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt();
                }
                try (var demuxer = new MusepackDemuxer(MediaSource.of(data))) {
                    var track = (AudioTrack) demuxer.tracks().getFirst();
                    try (var decoder = AudioDecoders.create(track)) {
                        Packet packet;
                        while ((packet = demuxer.read()) != null) {
                            decoder.decode(packet, block -> {
                            });
                        }
                    }
                } catch (MediaException expected) {
                    assertNotNull(expected.getMessage());
                }
            }
        }
    }
}
