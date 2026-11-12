package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the VVenC test streams kept with the tests ({@code src/test/resources/.../video/vvc}, made by
 * {@code tools/testdata/vvc-vectors/make-vectors.py}, which records the MD5 of VVdeC's output for each in
 * {@code vvc.tsv}) through every packet layout the decoder accepts, and checks the frame properties, the 8-bit
 * rounding, the picture hash verification, the options and the lifecycle.
 */
@DisplayName("VVC decoder")
class VvcDecoderTest {
    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "vvc");

    record Vector(String name, int frames, String md5) {
        @Override
        public String toString() {
            return name;
        }

        byte[] stream() {
            return Fixtures.read(VECTORS.resolve(name + ".vvc"));
        }
    }

    static Stream<Vector> vectors() {
        var result = new ArrayList<Vector>();
        for (var line : new String(Fixtures.read(VECTORS.resolve("vvc.tsv"))).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var parts = line.strip().split("\t");
            result.add(new Vector(parts[0], Integer.parseInt(parts[1]), parts[4]));
        }
        return result.stream();
    }

    static Vector vector(String name) {
        return vectors().filter(vector -> vector.name().equals(name)).findFirst().orElseThrow();
    }

    private static final VvcDecoderOptions WIDE = VvcDecoderOptions.builder().highBitDepth(true)
            .hashVerification(true).build();

    private static List<VideoFrame> frames(byte[] stream, VvcDecoderOptions options) {
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new VvcDecoder(VvcTestStreams.track(), options)) {
            Consumer<VideoFrame> sink = frame -> frames.add(copy(frame));
            for (var nal : VvcTestStreams.splitAnnexB(stream)) {
                decoder.decode(VvcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    static VideoFrame copy(VideoFrame frame) {
        var picture = switch (frame.picture()) {
            case YuvPicture p -> {
                var out = YuvPicture.allocate(p.width(), p.height(), p.chromaFormat(), false, p.color());
                copyPlane(p.y(), p.yStride(), out.y(), out.yStride(), p.width(), p.height());
                if (p.u() != null) {
                    copyPlane(p.u(), p.uvStride(), out.u(), out.uvStride(), p.chromaWidth(), p.chromaHeight());
                    copyPlane(p.v(), p.uvStride(), out.v(), out.uvStride(), p.chromaWidth(), p.chromaHeight());
                }
                yield out;
            }
            case Yuv16Picture p -> {
                var out = Yuv16Picture.allocate(p.width(), p.height(), p.chromaFormat(), p.bitDepth(), false,
                        p.color());
                copyPlane(p.y(), p.yStride(), out.y(), out.yStride(), p.width(), p.height());
                if (p.u() != null) {
                    copyPlane(p.u(), p.uvStride(), out.u(), out.uvStride(), p.chromaWidth(), p.chromaHeight());
                    copyPlane(p.v(), p.uvStride(), out.v(), out.uvStride(), p.chromaWidth(), p.chromaHeight());
                }
                yield out;
            }
            default -> throw new IllegalStateException("unexpected picture " + frame.picture());
        };
        return new VideoFrame(picture, frame.pts(), frame.duration(), frame.keyframe(), frame.sideData());
    }

    private static void copyPlane(byte[] src, int srcStride, byte[] dst, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, y * srcStride, dst, y * dstStride, width);
        }
    }

    private static void copyPlane(short[] src, int srcStride, short[] dst, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, y * srcStride, dst, y * dstStride, width);
        }
    }

    private static String md5(List<VideoFrame> frames) {
        var digest = VvcTestStreams.md5();
        for (var frame : frames) {
            VvcTestStreams.hash(digest, frame.picture());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Nested
    @DisplayName("bit-exact output")
    class BitExact {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.vvc.VvcDecoderTest#vectors")
        @DisplayName("matches VVdeC with one NAL unit per packet")
        void nalUnits(Vector vector) {
            var result = VvcTestStreams.decode(vector.stream(), WIDE);
            assertEquals(vector.frames(), result.frames());
            assertEquals(vector.md5(), result.md5());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.vvc.VvcDecoderTest#vectors")
        @DisplayName("matches VVdeC with the whole byte stream in one packet")
        void wholeStream(Vector vector) {
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new VvcDecoder(VvcTestStreams.track(), WIDE)) {
                decoder.decode(VvcTestStreams.packet(vector.stream()), frame -> frames.add(copy(frame)));
                decoder.flush(frame -> frames.add(copy(frame)));
            }
            assertEquals(vector.frames(), frames.size());
            assertEquals(vector.md5(), md5(frames));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.vvc.VvcDecoderTest#vectors")
        @DisplayName("matches VVdeC with length-prefixed access units after a vvcC record")
        void accessUnits(Vector vector) {
            var result = VvcTestStreams.decodeAccessUnits(vector.stream(), WIDE);
            assertEquals(vector.frames(), result.frames());
            assertEquals(vector.md5(), result.md5());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {1, 2, 4})
        @DisplayName("reads every NAL unit length size of a vvcC record, with or without its full box header")
        void lengthSizes(int lengthSize) {
            var vector = vector("ra-420-10bit");
            var nals = VvcTestStreams.nalUnits(vector.stream());
            var sets = nals.stream().filter(VvcTestStreams::isParameterSet).toList();
            for (var fullBox : new boolean[]{false, true}) {
                var config = VvcTestStreams.vvcC(sets, fullBox);
                config[fullBox ? 4 : 0] = (byte) (0xF8 | ((lengthSize - 1) << 1));
                var frames = new ArrayList<VideoFrame>();
                try (var decoder = new VvcDecoder(VvcTestStreams.track(config), WIDE)) {
                    for (var unit : VvcTestStreams.accessUnits(nals)) {
                        var out = new ByteArrayOutputStream();
                        for (var nal : unit) {
                            if (lengthSize == 1 && nal.length > 255) {
                                return;
                            }
                            for (var shift = (lengthSize - 1) * 8; shift >= 0; shift -= 8) {
                                out.write(nal.length >>> shift);
                            }
                            out.writeBytes(nal);
                        }
                        decoder.decode(VvcTestStreams.packet(out.toByteArray()), frame -> frames.add(copy(frame)));
                    }
                    decoder.flush(frame -> frames.add(copy(frame)));
                }
                assertEquals(vector.md5(), md5(frames), "length size " + lengthSize + ", full box " + fullBox);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"tiles-420-10bit", "wpp-420-10bit", "ra-420-10bit"})
        @DisplayName("gives the same output on one thread and on several (concurrent tiles and wavefront rows)")
        void threads(String name) {
            var vector = vector(name);
            var one = VvcTestStreams.decode(vector.stream(), WIDE.toBuilder().threads(1).build());
            var four = VvcTestStreams.decode(vector.stream(), WIDE.toBuilder().threads(4).build());
            assertEquals(vector.md5(), one.md5());
            assertEquals(one, four);
        }
    }

    @Nested
    @DisplayName("frames")
    class Frames {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ra-420-10bit", "tiles-420-10bit", "intra-400-10bit"})
        @DisplayName("round 10-bit samples to 8 bits as min((v + 2) >> 2, 255)")
        void narrowing(String name) {
            var stream = vector(name).stream();
            var wide = frames(stream, WIDE);
            var narrow = frames(stream, VvcDecoderOptions.defaults());
            assertEquals(wide.size(), narrow.size());
            for (var i = 0; i < wide.size(); i++) {
                var deep = assertInstanceOf(Yuv16Picture.class, wide.get(i).picture());
                var shallow = assertInstanceOf(YuvPicture.class, narrow.get(i).picture());
                assertEquals(10, deep.bitDepth());
                assertEquals(deep.chromaFormat(), shallow.chromaFormat());
                assertPlane(deep.y(), deep.yStride(), shallow.y(), shallow.yStride(), deep.width(), deep.height());
                if (deep.u() != null) {
                    assertPlane(deep.u(), deep.uvStride(), shallow.u(), shallow.uvStride(), deep.chromaWidth(),
                            deep.chromaHeight());
                    assertPlane(deep.v(), deep.uvStride(), shallow.v(), shallow.uvStride(), deep.chromaWidth(),
                            deep.chromaHeight());
                }
            }
        }

        private static void assertPlane(short[] deep, int deepStride, byte[] shallow, int shallowStride, int width,
                                        int height) {
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var expected = Math.min((deep[y * deepStride + x] + 2) >> 2, 255);
                    assertEquals(expected, shallow[y * shallowStride + x] & 0xFF, "sample " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("hands out 8-bit streams as 8-bit pictures even when high bit depth is asked for")
        void eightBit() {
            var frames = frames(vector("ld-420-8bit").stream(), WIDE);
            assertEquals(10, frames.size());
            for (var frame : frames) {
                var picture = assertInstanceOf(YuvPicture.class, frame.picture());
                assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
                assertEquals(128, picture.width());
                assertEquals(96, picture.height());
            }
        }

        @Test
        @DisplayName("hands out 4:0:0 streams as monochrome pictures")
        void monochrome() {
            var frames = frames(vector("intra-400-10bit").stream(), VvcDecoderOptions.defaults());
            assertEquals(3, frames.size());
            for (var frame : frames) {
                var picture = assertInstanceOf(YuvPicture.class, frame.picture());
                assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat());
                assertNull(picture.u());
                assertTrue(frame.keyframe());
            }
        }

        @Test
        @DisplayName("describes the color from the VUI")
        void color() {
            var frames = frames(vector("hdr-420-10bit").stream(), VvcDecoderOptions.defaults());
            var color = ((YuvPicture) frames.getFirst().picture()).color();
            assertEquals(MatrixCoefficients.BT2020_NCL, color.matrix());
            assertEquals(ColorPrimaries.BT2020, color.primaries());
            assertEquals(TransferCharacteristics.PQ, color.transfer());
            assertEquals(ColorRange.FULL, color.range());
            assertEquals(ChromaSiting.TOP_LEFT, color.chromaSiting());
        }

        @Test
        @DisplayName("defaults the color of a stream without a VUI to BT.601 at standard definition")
        void defaultColor() {
            var color = ((YuvPicture) frames(vector("ld-420-8bit").stream(), VvcDecoderOptions.defaults())
                    .getFirst().picture()).color();
            assertEquals(MatrixCoefficients.BT601, color.matrix());
            assertEquals(ColorRange.LIMITED, color.range());
        }

        @Test
        @DisplayName("marks the IRAP pictures as key frames and the others not")
        void keyframes() {
            var frames = frames(vector("ra-420-10bit").stream(), VvcDecoderOptions.defaults());
            assertEquals(1, frames.stream().filter(VideoFrame::keyframe).count());
            var lowDelay = frames(vector("ld-420-8bit").stream(), VvcDecoderOptions.defaults());
            assertTrue(lowDelay.getFirst().keyframe());
            assertEquals(1, lowDelay.stream().filter(VideoFrame::keyframe).count());
        }

        @Test
        @DisplayName("stamps each frame with the timestamp of the packet of its picture")
        void timestamps() {
            var vector = vector("ra-420-10bit");
            var units = VvcTestStreams.accessUnits(VvcTestStreams.nalUnits(vector.stream()));
            var pts = new TreeSet<Long>();
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new VvcDecoder(VvcTestStreams.track(), VvcDecoderOptions.defaults())) {
                for (var i = 0; i < units.size(); i++) {
                    var stamp = 1000L + 3600L * i;
                    pts.add(stamp);
                    decoder.decode(new Packet(1, annexB(units.get(i)), stamp, stamp, 3600, i == 0),
                            frame -> frames.add(frame));
                }
                decoder.flush(frames::add);
            }
            assertEquals(units.size(), frames.size());
            var seen = new TreeSet<Long>();
            for (var frame : frames) {
                seen.add(frame.pts());
                assertEquals(3600, frame.duration());
            }
            assertEquals(pts, seen);
        }

        @Test
        @DisplayName("continues timestamps from the track frame rate when packets have none")
        void frameRate() {
            var track = VideoTrack.builder().id(1).codec(Codec.VVC).timeBase(new Rational(1, 90_000))
                    .frameRate(new Rational(25, 1)).build();
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new VvcDecoder(track)) {
                for (var nal : VvcTestStreams.splitAnnexB(vector("ld-420-8bit").stream())) {
                    decoder.decode(VvcTestStreams.packet(nal), frames::add);
                }
                decoder.flush(frames::add);
            }
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(3600, frames.get(i).duration());
                assertEquals(3600L * i, frames.get(i).pts());
            }
        }

        private static byte[] annexB(List<byte[]> nals) {
            var out = new ByteArrayOutputStream();
            for (var nal : nals) {
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.writeBytes(nal);
            }
            return out.toByteArray();
        }
    }

    @Nested
    @DisplayName("picture hashes")
    class Hashes {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ra-420-10bit", "ld-420-8bit", "intra-400-10bit"})
        @DisplayName("reject a picture whose MD5, CRC or checksum message does not match when verification is on")
        void mismatch(String name) {
            var damaged = damageHash(vector(name).stream());
            assertThrows(MediaException.Malformed.class, () -> VvcTestStreams.decode(damaged, WIDE));
            var result = VvcTestStreams.decode(damaged, VvcDecoderOptions.builder().highBitDepth(true).build());
            assertEquals(vector(name).md5(), result.md5());
        }

        /**
         * Changes one byte of the hash in the first decoded picture hash SEI message of a stream.
         */
        private static byte[] damageHash(byte[] stream) {
            var copy = stream.clone();
            for (var i = 0; i + 6 < copy.length; i++) {
                if (copy[i] == 0 && copy[i + 1] == 0 && copy[i + 2] == 1 && ((copy[i + 4] >> 3) & 0x1F) == 24
                        && (copy[i + 5] & 0xFF) == 132) {
                    // payload type, size, hash_type, then the first hash byte
                    var target = i + 3 + 2 + 1 + 1 + 1 + 1;
                    copy[target] ^= (byte) ((copy[target] & 0xFF) < 0x10 ? 0x40 : 0x01);
                    return copy;
                }
            }
            throw new AssertionError("no decoded picture hash message");
        }
    }

    @Nested
    @DisplayName("limits and lifecycle")
    class Lifecycle {
        @Test
        @DisplayName("rejects pictures larger than the limits before allocating them")
        void limits() {
            var options = VvcDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(64 * 64).build()).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> VvcTestStreams.decode(vector("ld-420-8bit").stream(), options));
        }

        @Test
        @DisplayName("refuses calls after close")
        void closed() {
            var decoder = new VvcDecoder(VvcTestStreams.track());
            decoder.close();
            assertThrows(IllegalStateException.class, () -> decoder.decode(VvcTestStreams.packet(new byte[0]),
                    frame -> {
                    }));
            assertThrows(IllegalStateException.class, () -> decoder.flush(frame -> {
            }));
        }

        @Test
        @DisplayName("rejects tracks of other codecs and corrupt configuration records")
        void construction() {
            var hevc = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000)).build();
            assertThrows(IllegalArgumentException.class, () -> new VvcDecoder(hevc));
            assertThrows(NullPointerException.class, () -> new VvcDecoder(null));
            assertThrows(MediaException.Malformed.class,
                    () -> new VvcDecoder(VvcTestStreams.track(new byte[]{(byte) 0xFF, 0, 1})));
            var sets = VvcTestStreams.nalUnits(vector("ld-420-8bit").stream()).stream()
                    .filter(VvcTestStreams::isParameterSet).toList();
            var record = VvcTestStreams.vvcC(sets, false);
            assertThrows(MediaException.Malformed.class,
                    () -> new VvcDecoder(VvcTestStreams.track(Arrays.copyOf(record, record.length - 3))));
            var badLength = record.clone();
            badLength[0] = (byte) (0xF8 | (2 << 1));
            assertThrows(MediaException.Malformed.class, () -> new VvcDecoder(VvcTestStreams.track(badLength)));
        }

        @Test
        @DisplayName("passes an exception of the frame consumer through unchanged")
        void consumerFailure() {
            var failure = new IllegalStateException("consumer");
            var thrown = assertThrows(IllegalStateException.class, () -> {
                try (var decoder = new VvcDecoder(VvcTestStreams.track())) {
                    for (var nal : VvcTestStreams.splitAnnexB(vector("ld-420-8bit").stream())) {
                        decoder.decode(VvcTestStreams.packet(nal), frame -> {
                            throw failure;
                        });
                    }
                    decoder.flush(frame -> {
                        throw failure;
                    });
                }
            });
            assertSame(failure, thrown);
        }

        @Test
        @DisplayName("ignores NAL units of the reserved types and of other layers")
        void ignoredUnits() {
            var stream = vector("ld-420-8bit").stream();
            var expected = VvcTestStreams.decode(stream, VvcDecoderOptions.defaults());
            var padded = new ByteArrayOutputStream();
            for (var nal : VvcTestStreams.nalUnits(stream)) {
                padded.writeBytes(new byte[]{0, 0, 0, 1});
                padded.writeBytes(nal);
                if (VvcTestStreams.type(nal) <= 11) {
                    for (var type : new int[]{4, 5, 6, 11, 26, 31}) {
                        padded.writeBytes(new byte[]{0, 0, 0, 1, 0, (byte) ((type << 3) | 1), (byte) 0xAF, 0x12,
                                (byte) 0x80});
                    }
                    var otherLayer = nal.clone();
                    otherLayer[0] = (byte) ((otherLayer[0] & 0xC0) | 5);
                    padded.writeBytes(new byte[]{0, 0, 0, 1});
                    padded.writeBytes(otherLayer);
                }
            }
            assertEquals(expected, VvcTestStreams.decode(padded.toByteArray(), VvcDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("ignores empty packets and stray bytes")
        void empty() {
            try (var decoder = new VvcDecoder(VvcTestStreams.track())) {
                decoder.decode(new Packet(1, new byte[0], 0, 0, 0, false), frame -> {
                });
                decoder.decode(new Packet(1, new byte[]{1, 2, 3}, 0, 0, 0, false), frame -> {
                });
                decoder.flush(frame -> {
                });
            }
        }

        @Test
        @DisplayName("decodes a stream again after a flush")
        void reuse() {
            var vector = vector("ld-420-8bit");
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new VvcDecoder(VvcTestStreams.track(), WIDE)) {
                for (var round = 0; round < 2; round++) {
                    for (var nal : VvcTestStreams.splitAnnexB(vector.stream())) {
                        decoder.decode(VvcTestStreams.packet(nal), frame -> frames.add(copy(frame)));
                    }
                    decoder.flush(frame -> frames.add(copy(frame)));
                }
            }
            assertEquals(2 * vector.frames(), frames.size());
            assertEquals(vector.md5(), md5(frames.subList(0, vector.frames())));
            assertEquals(vector.md5(), md5(frames.subList(vector.frames(), frames.size())));
        }
    }

    @Nested
    @DisplayName("options and registry")
    class Options {
        @Test
        @DisplayName("follow the options convention")
        void convention() {
            var defaults = VvcDecoderOptions.defaults();
            assertEquals(MediaLimits.DEFAULT, defaults.limits());
            assertEquals(0, defaults.threads());
            assertFalse(defaults.highBitDepth());
            assertFalse(defaults.hashVerification());
            var changed = defaults.toBuilder().threads(3).highBitDepth(true).hashVerification(true).build();
            assertEquals(3, changed.threads());
            assertTrue(changed.highBitDepth());
            assertTrue(changed.hashVerification());
            assertNotEquals(defaults, changed);
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed, VvcDecoderOptions.builder().threads(3).highBitDepth(true).hashVerification(true)
                    .build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().threads(-1).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().limits(null));
            assertSame(defaults, new VvcDecoder(VvcTestStreams.track()).options());
        }

        @Test
        @DisplayName("are found through the decoder registry")
        void registry() {
            assertTrue(VideoDecoders.isSupported(Codec.VVC));
            try (var decoder = VideoDecoders.create(VvcTestStreams.track())) {
                assertInstanceOf(VvcDecoder.class, decoder);
            }
        }

        @Test
        @DisplayName("decode the same frames through the registry")
        void registryOutput() {
            var vector = vector("ld-420-8bit");
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = VideoDecoders.create(VvcTestStreams.track())) {
                for (var nal : VvcTestStreams.splitAnnexB(vector.stream())) {
                    decoder.decode(VvcTestStreams.packet(nal), frame -> frames.add(copy(frame)));
                }
                decoder.flush(frame -> frames.add(copy(frame)));
            }
            assertEquals(vector.md5(), md5(frames));
        }
    }

    @Test
    @DisplayName("keeps the expected vectors in sync with the files")
    void manifest() {
        assertEquals(9, vectors().count());
        assertArrayEquals(new String[]{"ra-420-10bit", "ld-420-8bit", "ld-420-10bit", "intra-400-10bit",
                "tiles-420-10bit", "hdr-420-10bit", "wpp-420-10bit", "concat-420-8bit", "no-output-420-8bit"},
                vectors().map(Vector::name).toArray());
    }
}
