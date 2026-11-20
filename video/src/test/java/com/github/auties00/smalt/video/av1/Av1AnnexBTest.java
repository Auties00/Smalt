package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
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
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes temporal units in the length-delimited format of the AV1 specification's Annex B through the public
 * decoder: streams libaom's aomenc wrote in that format (IVF files and a raw stream, kept with the test in
 * {@code annexb/}, written by {@code tools/testdata/av1-annexb/make-vectors.py} with the SHA-256 of aomdec's output),
 * and libaom test vectors rewritten in it with sizes of every length and with and without {@code obu_size}, which must
 * decode to the vectors' reference MD5 and, through ffmpeg's Annex B demuxer and dav1d, to the same frames; and
 * malformed units, which must fail as {@link MediaException.Malformed}.
 */
@DisplayName("AV1 Annex B input")
class Av1AnnexBTest {
    /** A stream of {@code annexb/}: name, frame count and SHA-256 of the stream and of aomdec's raw output. */
    record Vector(String name, int frames, String streamSha256, String decodedSha256) {
        @Override
        public String toString() {
            return name;
        }

        byte[] stream() {
            try (var input = Av1AnnexBTest.class.getResourceAsStream("annexb/" + name)) {
                if (input == null) {
                    throw new IllegalStateException("missing vector " + name);
                }
                var data = input.readAllBytes();
                assertEquals(streamSha256, sha256(data), name + " stream");
                return data;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    static Stream<Vector> encoded() {
        try (var input = Av1AnnexBTest.class.getResourceAsStream("annexb/annexb.tsv")) {
            var vectors = new ArrayList<Vector>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                vectors.add(new Vector(columns[0], Integer.parseInt(columns[1]), columns[2], columns[3]));
            }
            return vectors.stream();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encoded")
    @DisplayName("aomenc's Annex B streams decode to aomdec's output, also with frames in flight")
    void aomenc(Vector vector) {
        var data = vector.stream();
        List<Av1Streams.Unit> units;
        VideoTrack track;
        if (vector.name().endsWith(".obu")) {
            units = new ArrayList<>();
            for (var unit : Av1Streams.splitAnnexB(data)) {
                units.add(new Av1Streams.Unit(unit, units.size()));
            }
            track = Av1Streams.bareTrack();
        } else {
            units = Av1Streams.units(data);
            track = Av1Streams.track(data);
        }
        for (var delay : new int[]{1, 3}) {
            var frames = new int[1];
            var options = Av1DecoderOptions.builder().annexB(true).highBitDepth(true).maxFrameDelay(delay).build();
            var decoded = decode(track, units, options, frames);
            assertEquals(vector.frames(), frames[0], vector + " frames");
            assertEquals(vector.decodedSha256(), sha256(decoded), vector + " decoded, frame delay " + delay);
        }
    }

    @Test
    @DisplayName("the IVF demuxer flags the Annex B unit that carries the sequence header as the only keyframe")
    void ivfKeyframes() {
        var vector = encoded().filter(v -> v.name().equals("annexb-8bit-tiles.ivf")).findFirst().orElseThrow();
        var flags = new ArrayList<Boolean>();
        try (var source = com.github.auties00.smalt.io.MediaSource.of(vector.stream());
             var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(source)) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                flags.add(packet.keyframe());
            }
        }
        assertEquals(vector.frames(), flags.size());
        assertTrue(flags.getFirst());
        assertEquals(1, flags.stream().filter(flag -> flag).count(), flags.toString());
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({"annexb-8bit-tiles.ivf, 8, YUV420", "annexb-10bit-444.ivf, 10, YUV444",
            "annexb-monochrome.ivf, 8, MONOCHROME"})
    @DisplayName("probing aomenc's Annex B IVF files reads the bit depth and chroma format of their sequence header")
    void probe(String name, int depth, com.github.auties00.smalt.image.ChromaFormat chroma) {
        var vector = encoded().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
        var info = com.github.auties00.smalt.Media.probe(com.github.auties00.smalt.io.MediaSource.of(vector.stream()));
        var video = info.videoStreams().getFirst();
        assertEquals(depth, video.bitDepth().orElseThrow(), name);
        assertEquals(java.util.Optional.of(chroma), video.chromaFormat(), name);
        assertTrue(video.profile().isPresent(), name);
    }

    static Stream<String> vectors() {
        return Stream.of("av1-1-b8-22-svc-L2T2-2.ivf", "av1-1-b8-23-film_grain-50.ivf", "av1-1-b10-23-film_grain-50.ivf",
                "av1-1-b8-06-mfmv.ivf", "av1-1-b10-24-monochrome.ivf", "av1-1-b8-04-cdfupdate.ivf");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("libaom vectors rewritten in Annex B decode to their reference MD5")
    void rewritten(String name) {
        var file = Av1Streams.vector("av1-" + name);
        var track = Av1Streams.track(file);
        var random = new SplittableRandom(name.hashCode());
        var units = new ArrayList<Av1Streams.Unit>();
        for (var unit : Av1Streams.units(file)) {
            units.add(new Av1Streams.Unit(Av1Streams.toAnnexB(unit.data(), random), unit.pts()));
        }
        var expected = Av1Streams.referenceMd5("av1-" + name);
        var options = Av1DecoderOptions.builder().annexB(true).highBitDepth(true).build();
        assertEquals(expected, md5s(track, units, options));
        assertEquals(expected, md5s(track, units, options.toBuilder().threads(1).build()), "calling thread");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"av1-1-b8-22-svc-L2T2-2.ivf", "av1-1-b8-23-film_grain-50.ivf"})
    @DisplayName("the rewritten streams decode through ffmpeg's Annex B demuxer and dav1d to the same frames")
    void againstDav1d(String name) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Av1Streams.vector("av1-" + name);
        var random = new SplittableRandom(~name.hashCode());
        var stream = new ByteArrayOutputStream();
        var units = new ArrayList<Av1Streams.Unit>();
        for (var unit : Av1Streams.units(file)) {
            var rewritten = Av1Streams.toAnnexB(unit.data(), random, null);
            stream.writeBytes(rewritten);
            units.add(new Av1Streams.Unit(rewritten, unit.pts()));
        }
        var path = Scratch.directory("av1-annexb").resolve(name.replace(".ivf", ".obu"));
        Files.write(path, stream.toByteArray());
        var reference = Oracle.decodeVideo(path, List.of("-f", "av1", "-c:v", "libdav1d"), "yuv420p", 0);
        var decoded = decode(Av1Streams.track(file), units, Av1DecoderOptions.builder().annexB(true).build(),
                new int[1]);
        assertArrayEquals(reference, decoded);
    }

    @Test
    @DisplayName("an Annex B decoder rejects a low-overhead unit, then decodes the same unit in Annex B")
    void formatsDoNotMix() {
        var file = Av1Streams.vector("av1-av1-1-b8-23-film_grain-50.ivf");
        var unit = Av1Streams.units(file).getFirst().data();
        var annexB = Av1Streams.toAnnexB(unit, new SplittableRandom(3));
        try (var decoder = new Av1Decoder(Av1Streams.bareTrack(), Av1DecoderOptions.builder().annexB(true).build())) {
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(unit), _ -> {
            }));
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(packet(annexB), frames::add);
            assertEquals(1, frames.size(), "the decoder recovers on the next unit");
        }
    }

    @Nested
    @DisplayName("malformed units")
    class Malformed {
        private final byte[] unit = Av1Streams.toAnnexB(Av1Streams.units(
                Av1Streams.vector("av1-av1-1-b8-23-film_grain-50.ivf")).getFirst().data(), new SplittableRandom(9));

        @Test
        @DisplayName("every truncation fails as malformed, never with another exception")
        void truncated() {
            for (var length = 1; length < unit.length; length++) {
                assertRejected(Arrays.copyOf(unit, length));
            }
        }

        @Test
        @DisplayName("bytes after the temporal unit fail")
        void trailing() {
            var longer = Arrays.copyOf(unit, unit.length + 1);
            assertRejected(longer);
        }

        @Test
        @DisplayName("a size field of more than 8 bytes or above 2^32 - 1 fails")
        void oversizedFields() {
            assertRejected(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                    (byte) 0x80, (byte) 0x80, 0x00});
            assertRejected(new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10});
        }

        @Test
        @DisplayName("a frame unit or an OBU longer than its container fails")
        void overlongUnits() {
            // temporal unit of 3 bytes: a frame unit claiming 5 bytes
            assertRejected(new byte[]{3, 5, 1, 0x10});
            // a frame unit of 2 bytes holding an OBU claiming 4 bytes
            assertRejected(new byte[]{3, 2, 4, 0x10});
        }

        @Test
        @DisplayName("an obu_size that disagrees with its obu_length fails")
        void inconsistentSizes() {
            // a temporal delimiter with obu_size 0 inside an obu_length of 3
            assertRejected(new byte[]{5, 4, 3, 0x12, 0x00, 0x00});
        }

        @Test
        @DisplayName("an empty packet and an empty temporal unit decode to nothing")
        void empty() {
            try (var decoder = new Av1Decoder(Av1Streams.bareTrack(), Av1DecoderOptions.builder().annexB(true).build())) {
                var frames = new ArrayList<VideoFrame>();
                decoder.decode(packet(new byte[]{0}), frames::add);
                decoder.decode(packet(new byte[0]), frames::add);
                assertTrue(frames.isEmpty());
            }
        }

        private static void assertRejected(byte[] data) {
            try (var decoder = new Av1Decoder(Av1Streams.bareTrack(), Av1DecoderOptions.builder().annexB(true).build())) {
                assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(data), _ -> {
                }), () -> "length " + data.length);
            }
        }
    }

    private static Packet packet(byte[] data) {
        return new Packet(1, data, 0, 0, 1, true);
    }

    private static List<String> md5s(VideoTrack track, List<Av1Streams.Unit> units, Av1DecoderOptions options) {
        var md5 = new ArrayList<String>();
        Consumer<VideoFrame> sink = frame -> md5.add(Av1Streams.md5(frame.picture()));
        try (var decoder = new Av1Decoder(track, options)) {
            for (var unit : units) {
                decoder.decode(new Packet(1, unit.data(), unit.pts(), unit.pts(), 1, false), sink);
            }
            decoder.flush(sink);
        }
        return md5;
    }

    private static byte[] decode(VideoTrack track, List<Av1Streams.Unit> units, Av1DecoderOptions options,
                                 int[] frames) {
        var out = new ByteArrayOutputStream();
        Consumer<VideoFrame> sink = frame -> {
            frames[0]++;
            Picture picture = frame.picture();
            out.writeBytes(Av1Streams.raw(picture, false));
        };
        try (var decoder = new Av1Decoder(track, options)) {
            for (var unit : units) {
                decoder.decode(new Packet(1, unit.data(), unit.pts(), unit.pts(), 1, false), sink);
            }
            decoder.flush(sink);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
