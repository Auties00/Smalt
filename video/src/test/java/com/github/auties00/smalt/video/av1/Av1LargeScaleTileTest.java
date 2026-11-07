package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the large scale tile decoding process through the public decoder on light fields coded with libaom's light
 * field examples ({@code lightfield/}, written by {@code tools/testdata/av1-lightfield/make-vectors.py}): the anchor
 * frames are decoded as ordinary frames and set with {@link Av1Decoder#setAnchorFrames}, then the camera frame header
 * and the tile lists are decoded, and the tiles of every tile list, laid out as libaom's
 * {@code lightfield_tile_list_decoder} writes them, must have the SHA-256 of that program's output. The test also
 * covers the samples no tile covers, the return to general decoding, the thread count and the rejection of unusable
 * anchor frames and corrupt tile lists.
 */
@DisplayName("AV1 large scale tile decoding")
class Av1LargeScaleTileTest {
    /** A light field: stream name, anchor frame count, tile list count and the two SHA-256 sums. */
    record Vector(String name, int anchors, int tileLists, String streamSha256, String tilesSha256) {
        @Override
        public String toString() {
            return name;
        }

        byte[] stream() {
            try (var input = Av1LargeScaleTileTest.class.getResourceAsStream("lightfield/" + name)) {
                if (input == null) {
                    throw new IllegalStateException("missing vector " + name);
                }
                var data = input.readAllBytes();
                assertEquals(streamSha256, Av1AnnexBTest.sha256(data), name + " stream");
                return data;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    static Stream<Vector> vectors() {
        try (var input = Av1LargeScaleTileTest.class.getResourceAsStream("lightfield/lightfield.tsv")) {
            var vectors = new ArrayList<Vector>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                vectors.add(new Vector(columns[0], Integer.parseInt(columns[1]), Integer.parseInt(columns[2]),
                        columns[3], columns[4]));
            }
            return vectors.stream();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The layout of a tile list OBU's output frame: its size in tiles and the number of tiles it lists. */
    record TileList(int columns, int rows, int count) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("the tiles of every tile list match lightfield_tile_list_decoder's output")
    void tiles(Vector vector) {
        for (var threads : new int[]{1, 0}) {
            var options = Av1DecoderOptions.builder().threads(threads).build();
            assertEquals(vector.tilesSha256(), Av1AnnexBTest.sha256(decodeTiles(vector, options)), vector + ", "
                    + threads + " threads");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("with frames in flight, every tile list yields its frame from its own packet, with the same tiles")
    void framesInFlight(Vector vector) {
        var data = vector.stream();
        var units = Av1Streams.units(data);
        var out = new ByteArrayOutputStream();
        try (var decoder = new Av1Decoder(Av1Streams.track(data), Av1DecoderOptions.builder().maxFrameDelay(4)
                .build())) {
            var anchors = new ArrayList<Picture>();
            for (var i = 0; i < vector.anchors(); i++) {
                decoder.decode(packet(units.get(i).data(), i), frame -> anchors.add(copy(frame).picture()));
            }
            decoder.flush(frame -> anchors.add(copy(frame).picture()));
            assertEquals(vector.anchors(), anchors.size());
            decoder.setAnchorFrames(anchors);
            decoder.decode(packet(units.get(vector.anchors()).data(), 0), frame -> {
                throw new AssertionError("the camera frame header shows a frame");
            });
            for (var i = vector.anchors() + 1; i < units.size(); i++) {
                var unit = units.get(i).data();
                var frames = new ArrayList<VideoFrame>();
                decoder.decode(packet(unit, i), frames::add);
                assertEquals(1, frames.size(), "tile list " + i);
                assertEquals(i, frames.getFirst().pts());
                writeTiles(out, (YuvPicture) frames.getFirst().picture(), layout(unit));
            }
            var rest = new ArrayList<VideoFrame>();
            decoder.flush(rest::add);
            assertTrue(rest.isEmpty());
        }
        assertEquals(vector.tilesSha256(), Av1AnnexBTest.sha256(out.toByteArray()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("anchor frames handed over as 16-bit pictures of the stream's depth decode the same tiles")
    void wideAnchors(Vector vector) {
        assertEquals(vector.tilesSha256(), Av1AnnexBTest.sha256(decodeTiles(vector, Av1DecoderOptions.defaults(),
                true)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("the samples no listed tile covers are 0 and every frame carries its packet's timestamps")
    void uncovered(Vector vector) {
        var units = Av1Streams.units(vector.stream());
        try (var decoder = new Av1Decoder(Av1Streams.track(vector.stream()))) {
            setAnchors(decoder, units, vector.anchors());
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(packet(units.get(vector.anchors()).data(), 77), frames::add);
            assertTrue(frames.isEmpty(), "the camera frame header shows no frame");
            for (var i = vector.anchors() + 1; i < units.size(); i++) {
                var data = units.get(i).data();
                var layout = layout(data);
                frames.clear();
                decoder.decode(packet(data, 1000 + i), frame -> frames.add(copy(frame)));
                assertEquals(1, frames.size(), "one frame per tile list");
                var frame = frames.getFirst();
                assertEquals(1000 + i, frame.pts());
                assertFalse(frame.keyframe());
                var picture = (YuvPicture) frame.picture();
                assertEquals(0, picture.width() % layout.columns());
                var tileWidth = picture.width() / layout.columns();
                var tileHeight = picture.height() / layout.rows();
                for (var y = 0; y < picture.height(); y++) {
                    for (var x = 0; x < picture.width(); x++) {
                        var tile = (y / tileHeight) * layout.columns() + x / tileWidth;
                        if (tile >= layout.count()) {
                            assertEquals(0, picture.y()[y * picture.yStride() + x], "luma at " + x + "," + y);
                        }
                    }
                }
                var shiftX = picture.chromaFormat().shiftX();
                var shiftY = picture.chromaFormat().shiftY();
                var uncovered = 0;
                for (var y = 0; y < picture.chromaHeight(); y++) {
                    for (var x = 0; x < picture.chromaWidth(); x++) {
                        var tile = ((y << shiftY) / tileHeight) * layout.columns() + (x << shiftX) / tileWidth;
                        if (tile >= layout.count()) {
                            uncovered++;
                            assertEquals(0, picture.u()[y * picture.uvStride() + x], "Cb at " + x + "," + y);
                            assertEquals(0, picture.v()[y * picture.uvStride() + x], "Cr at " + x + "," + y);
                        }
                    }
                }
                assertEquals((layout.columns() * layout.rows() - layout.count()) * (tileWidth >> shiftX)
                        * (tileHeight >> shiftY), uncovered, "uncovered chroma samples");
            }
        }
    }

    @Test
    @DisplayName("an empty anchor list returns to general decoding, where tile lists are ignored")
    void backToGeneral() {
        var vector = vectors().findFirst().orElseThrow();
        var units = Av1Streams.units(vector.stream());
        try (var decoder = new Av1Decoder(Av1Streams.track(vector.stream()))) {
            setAnchors(decoder, units, vector.anchors());
            assertEquals(vector.anchors(), decoder.anchorFrameCount());
            decoder.decode(packet(units.get(vector.anchors()).data(), 0), _ -> {
            });
            decoder.setAnchorFrames(List.of());
            assertEquals(0, decoder.anchorFrameCount());
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(packet(units.getLast().data(), 0), frames::add);
            assertTrue(frames.isEmpty());
        }
    }

    @Test
    @DisplayName("a tile list before a camera frame header fails, and anchor frames replaced later still decode it")
    void cameraHeaderFirst() {
        var vector = vectors().findFirst().orElseThrow();
        var units = Av1Streams.units(vector.stream());
        try (var decoder = new Av1Decoder(Av1Streams.track(vector.stream()))) {
            var anchors = anchors(decoder, units, vector.anchors());
            decoder.setAnchorFrames(anchors);
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(units.getLast().data(), 0),
                    _ -> {
                    }));
            decoder.decode(packet(units.get(vector.anchors()).data(), 0), _ -> {
            });
            decoder.setAnchorFrames(anchors);
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(packet(units.getLast().data(), 0), frames::add);
            assertEquals(1, frames.size(), "the camera frame header outlives a change of anchor frames");
        }
    }

    @Nested
    @DisplayName("unusable anchor frames")
    class Anchors {
        @Test
        @DisplayName("more than 128 frames, non-planar or 4:4:0 frames are rejected when set")
        void rejected() {
            try (var decoder = new Av1Decoder(Av1Streams.bareTrack())) {
                var gray = YuvPicture.allocate(64, 64, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
                assertThrows(IllegalArgumentException.class, () -> decoder.setAnchorFrames(
                        Collections.nCopies(129, gray)));
                assertThrows(IllegalArgumentException.class, () -> decoder.setAnchorFrames(List.of(
                        RgbaPicture.allocate(64, 64, false))));
                assertThrows(IllegalArgumentException.class, () -> decoder.setAnchorFrames(List.of(
                        YuvPicture.allocate(64, 64, ChromaFormat.YUV440, false, ColorInfo.BT709_LIMITED))));
                assertThrows(NullPointerException.class, () -> decoder.setAnchorFrames(Arrays.asList(gray, null)));
                decoder.setAnchorFrames(Collections.nCopies(128, gray));
                assertEquals(128, decoder.anchorFrameCount());
            }
        }

        @Test
        @DisplayName("frames of another size, chroma format or bit depth fail the tile lists that use them")
        void mismatched() {
            var vector = vectors().findFirst().orElseThrow();
            var units = Av1Streams.units(vector.stream());
            try (var decoder = new Av1Decoder(Av1Streams.track(vector.stream()))) {
                var anchors = anchors(decoder, units, vector.anchors());
                var small = YuvPicture.allocate(anchors.getFirst().width() / 2, anchors.getFirst().height(),
                        ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
                var deep = Yuv16Picture.allocate(anchors.getFirst().width(), anchors.getFirst().height(),
                        ChromaFormat.YUV420, 10, false, ColorInfo.BT709_LIMITED);
                var full = YuvPicture.allocate(anchors.getFirst().width(), anchors.getFirst().height(),
                        ChromaFormat.YUV444, false, ColorInfo.BT709_LIMITED);
                decoder.setAnchorFrames(anchors);
                decoder.decode(packet(units.get(vector.anchors()).data(), 0), _ -> {
                });
                for (Picture wrong : List.<Picture>of(small, deep, full)) {
                    var replaced = new ArrayList<Picture>(anchors);
                    Collections.fill(replaced, wrong);
                    decoder.setAnchorFrames(replaced);
                    var failure = assertThrows(MediaException.Malformed.class,
                            () -> decoder.decode(packet(units.get(vector.anchors() + 1).data(), 0), _ -> {
                            }));
                    assertTrue(failure.getMessage().contains("anchor frame"), failure.getMessage());
                }
            }
        }

        @Test
        @DisplayName("a closed decoder rejects anchor frames")
        void closed() {
            var decoder = new Av1Decoder(Av1Streams.bareTrack());
            decoder.close();
            assertThrows(IllegalStateException.class, () -> decoder.setAnchorFrames(List.of()));
        }
    }

    @Nested
    @DisplayName("corrupt tile lists")
    class Corrupt {
        @Test
        @DisplayName("every truncation of a tile list fails as malformed, and the decoder decodes the next one")
        void truncated() {
            var vector = vectors().findFirst().orElseThrow();
            var units = Av1Streams.units(vector.stream());
            var list = units.get(vector.anchors() + 1).data();
            try (var decoder = new Av1Decoder(Av1Streams.track(vector.stream()))) {
                setAnchors(decoder, units, vector.anchors());
                decoder.decode(packet(units.get(vector.anchors()).data(), 0), _ -> {
                });
                var payload = (int) Av1Streams.leb128(list, 1)[1];
                for (var length = payload; length < list.length; length += Math.max(1, list.length / 97)) {
                    // keep the OBU size consistent with the cut, so that the tile list itself is short
                    var size = length - payload;
                    var rewritten = new ByteArrayOutputStream();
                    rewritten.write(list[0]);
                    Av1Streams.writeLeb128(rewritten, size, null);
                    rewritten.write(list, payload, size);
                    var cut = rewritten.toByteArray();
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(cut, 0), _ -> {
                    }), () -> "cut at " + size);
                }
                var frames = new ArrayList<VideoFrame>();
                decoder.decode(packet(list, 0), frames::add);
                assertEquals(1, frames.size());
            }
        }

        @Test
        @DisplayName("an anchor index, tile row or tile column out of range fails")
        void outOfRange() {
            var vector = vectors().findFirst().orElseThrow();
            var units = Av1Streams.units(vector.stream());
            var list = units.get(vector.anchors() + 1).data();
            var payload = (int) Av1Streams.leb128(list, 1)[1];
            try (var decoder = new Av1Decoder(Av1Streams.track(vector.stream()))) {
                setAnchors(decoder, units, vector.anchors());
                decoder.decode(packet(units.get(vector.anchors()).data(), 0), _ -> {
                });
                for (var field = 0; field < 3; field++) {
                    var broken = list.clone();
                    broken[payload + 4 + field] = (byte) 200;
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(broken, 0), _ -> {
                    }), "field " + field);
                }
                var crowded = list.clone();
                crowded[payload] = 0;
                crowded[payload + 1] = 0;
                if (layout(list).count() > 1) {
                    assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(crowded, 0), _ -> {
                    }), "more tiles than the output frame holds");
                }
            }
        }

    }

    /** Decodes a light field and lays out the tiles of every tile list as libaom's tile list decoder writes them. */
    static byte[] decodeTiles(Vector vector, Av1DecoderOptions options) {
        return decodeTiles(vector, options, false);
    }

    /**
     * Decodes a light field as {@link #decodeTiles(Vector, Av1DecoderOptions)} does, the anchor frames handed over as
     * 8-bit {@link Yuv16Picture}s when {@code wide} is set.
     */
    static byte[] decodeTiles(Vector vector, Av1DecoderOptions options, boolean wide) {
        var data = vector.stream();
        var units = Av1Streams.units(data);
        var out = new ByteArrayOutputStream();
        try (var decoder = new Av1Decoder(Av1Streams.track(data), options)) {
            var anchors = anchors(decoder, units, vector.anchors());
            decoder.setAnchorFrames(wide ? anchors.stream().map(anchor -> (Picture) widen((YuvPicture) anchor))
                    .toList() : anchors);
            decoder.decode(packet(units.get(vector.anchors()).data(), 0), frame -> {
                throw new AssertionError("the camera frame header shows a frame");
            });
            var lists = 0;
            for (var i = vector.anchors() + 1; i < units.size(); i++) {
                var unit = units.get(i).data();
                var layout = layout(unit);
                var frames = new ArrayList<VideoFrame>();
                decoder.decode(packet(unit, i), frames::add);
                assertEquals(1, frames.size());
                writeTiles(out, (YuvPicture) frames.getFirst().picture(), layout);
                lists++;
            }
            assertEquals(vector.tileLists(), lists);
        }
        return out.toByteArray();
    }

    /** Writes the tiles of an output frame in order: each tile's luma rows, then its Cb rows and its Cr rows. */
    static void writeTiles(ByteArrayOutputStream out, YuvPicture picture, TileList layout) {
        var tileWidth = picture.width() / layout.columns();
        var tileHeight = picture.height() / layout.rows();
        var shiftX = picture.chromaFormat().shiftX();
        var shiftY = picture.chromaFormat().shiftY();
        for (var tile = 0; tile < layout.count(); tile++) {
            var x0 = (tile % layout.columns()) * tileWidth;
            var y0 = (tile / layout.columns()) * tileHeight;
            for (var y = 0; y < tileHeight; y++) {
                out.write(picture.y(), (y0 + y) * picture.yStride() + x0, tileWidth);
            }
            for (var plane : new byte[][]{picture.u(), picture.v()}) {
                for (var y = 0; y < tileHeight >> shiftY; y++) {
                    out.write(plane, ((y0 >> shiftY) + y) * picture.uvStride() + (x0 >> shiftX), tileWidth >> shiftX);
                }
            }
        }
    }

    /** Reads the layout of the tile list OBU of a temporal unit. */
    static TileList layout(byte[] unit) {
        var position = 0;
        while (position < unit.length) {
            var header = unit[position] & 0xFF;
            var type = (header >> 3) & 0xF;
            var size = Av1Streams.leb128(unit, position + ((header & 0x4) != 0 ? 2 : 1));
            var payload = (int) size[1];
            if (type == 8) {
                return new TileList((unit[payload] & 0xFF) + 1, (unit[payload + 1] & 0xFF) + 1,
                        ((unit[payload + 2] & 0xFF) << 8 | (unit[payload + 3] & 0xFF)) + 1);
            }
            position = Math.toIntExact(payload + size[0]);
        }
        throw new IllegalArgumentException("no tile list OBU");
    }

    /** Decodes the anchor frames of a light field and copies the frame each of their temporal units shows. */
    static List<Picture> anchors(Av1Decoder decoder, List<Av1Streams.Unit> units, int count) {
        var anchors = new ArrayList<Picture>();
        for (var i = 0; i < count; i++) {
            var shown = new ArrayList<Picture>();
            decoder.decode(packet(units.get(i).data(), i), frame -> shown.add(copy(frame).picture()));
            assertEquals(1, shown.size(), "anchor temporal unit " + i);
            anchors.add(shown.getFirst());
        }
        return anchors;
    }

    /** Returns an 8-bit {@link Yuv16Picture} holding the samples of a {@link YuvPicture}. */
    static Yuv16Picture widen(YuvPicture picture) {
        var wide = Yuv16Picture.allocate(picture.width(), picture.height(), picture.chromaFormat(), 8, false,
                picture.color());
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                wide.y()[y * wide.yStride() + x] = (short) (picture.y()[y * picture.yStride() + x] & 0xFF);
            }
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            for (var x = 0; x < picture.chromaWidth(); x++) {
                wide.u()[y * wide.uvStride() + x] = (short) (picture.u()[y * picture.uvStride() + x] & 0xFF);
                wide.v()[y * wide.uvStride() + x] = (short) (picture.v()[y * picture.uvStride() + x] & 0xFF);
            }
        }
        return wide;
    }

    static void setAnchors(Av1Decoder decoder, List<Av1Streams.Unit> units, int count) {
        if (decoder.anchorFrameCount() != 0) {
            decoder.setAnchorFrames(List.of());
        }
        decoder.setAnchorFrames(anchors(decoder, units, count));
    }

    static VideoFrame copy(VideoFrame frame) {
        var picture = (YuvPicture) frame.picture();
        var copy = YuvPicture.allocate(picture.width(), picture.height(), picture.chromaFormat(), false,
                picture.color());
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), copy.y(), y * copy.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            System.arraycopy(picture.u(), y * picture.uvStride(), copy.u(), y * copy.uvStride(), picture.chromaWidth());
            System.arraycopy(picture.v(), y * picture.uvStride(), copy.v(), y * copy.uvStride(), picture.chromaWidth());
        }
        return new VideoFrame(copy, frame.pts(), frame.duration(), frame.keyframe());
    }

    static Packet packet(byte[] data, long pts) {
        return new Packet(1, data, pts, pts, 1, false);
    }

    static VideoTrack track(byte[] data) {
        return Av1Streams.track(data);
    }
}
