package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.Narrow;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.auties00.smalt.testkit.HeifBuilder.alpha;
import static com.github.auties00.smalt.testkit.HeifBuilder.auxC;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.ispe;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the HEIF composition helpers shared by the AVIF and HEIC readers: the canvas, the still
 * image helpers and the sequence track selection and sample interleaving.
 */
class HeifSharedTest {
    /** A decoded tile over plain arrays, offset by one row and one column of padding. */
    private record Tile(byte[][] bytes, short[][] shorts, int stride, int depth) implements DecodedTile {
        @Override
        public boolean eightBit() {
            return bytes != null;
        }

        @Override
        public byte[] plane8(int plane) {
            return bytes[plane];
        }

        @Override
        public short[] plane16(int plane) {
            return shorts[plane];
        }

        @Override
        public int offset(int plane) {
            return stride + 1;
        }

        @Override
        public int stride(int plane) {
            return stride;
        }

        @Override
        public int depth(int plane) {
            return depth;
        }
    }

    @Nested
    @DisplayName("HeifCanvas")
    class Canvas {
        @Test
        @DisplayName("narrows deep tiles with the tile's kernel, keeps them in a wide canvas and widens 8-bit tiles")
        void color() {
            var stride = 6;
            var planes = new short[3][stride * 5];
            for (var plane = 0; plane < 3; plane++) {
                for (var i = 0; i < planes[plane].length; i++) {
                    planes[plane][i] = (short) ((i * 37 + plane * 101) & 1023);
                }
            }
            var deep = new Tile(null, planes, stride, 10);
            var narrow = new HeifCanvas("test", MediaLimits.DEFAULT, 4, 4, ChromaFormat.YUV444, 10, false, false);
            narrow.writeColor(deep, 0, 0, 4, 4, true);
            var picture = (YuvPicture) narrow.toPicture(ColorInfo.BT709_LIMITED);
            var expected = new byte[16];
            Narrow.select().narrow(planes[1], stride + 1, stride, expected, 0, 4, 4, 4, 2);
            assertArrayEquals(expected, picture.u());
            var wide = new HeifCanvas("test", MediaLimits.DEFAULT, 4, 4, ChromaFormat.YUV444, 10, true, false);
            wide.writeColor(deep, 0, 0, 4, 4, false);
            var widePicture = (Yuv16Picture) wide.toPicture(ColorInfo.BT709_LIMITED);
            assertEquals(planes[0][stride + 1], widePicture.y()[0]);
            assertEquals(planes[2][4 * stride + 4], widePicture.v()[15]);
            var bytes = new byte[3][stride * 5];
            bytes[0][stride + 1] = (byte) 200;
            var eight = new Tile(bytes, null, stride, 8);
            var widened = new HeifCanvas("test", MediaLimits.DEFAULT, 2, 2, ChromaFormat.YUV444, 8, true, false);
            widened.writeColor(eight, 0, 0, 2, 2, false);
            assertEquals(200, ((Yuv16Picture) widened.toPicture(ColorInfo.BT709_LIMITED)).y()[0]);
        }

        @Test
        @DisplayName("expands limited-range alpha and rescales deeper alpha exactly into an 8-bit canvas")
        void alphaPlane() {
            var stride = 3;
            var samples = new short[stride * 3];
            samples[stride + 1] = 64;
            samples[stride + 2] = 940;
            samples[2 * stride + 1] = 1023;
            samples[2 * stride + 2] = (short) 2000;
            var tile = new Tile(null, new short[][]{samples}, stride, 10);
            var canvas = new HeifCanvas("test", MediaLimits.DEFAULT, 2, 2, ChromaFormat.MONOCHROME, 10, false, true);
            canvas.writeAlpha(tile, 0, 0, 2, 2, true);
            var alpha = ((YuvPicture) canvas.toPicture(ColorInfo.BT709_LIMITED)).alpha();
            assertArrayEquals(new byte[]{0, (byte) 255, (byte) 255, (byte) 255}, alpha);
            var full = new HeifCanvas("test", MediaLimits.DEFAULT, 2, 2, ChromaFormat.MONOCHROME, 10, false, true);
            full.writeAlpha(tile, 0, 0, 2, 2, false);
            var fullAlpha = ((YuvPicture) full.toPicture(ColorInfo.BT709_LIMITED)).alpha();
            assertEquals(Math.min(255, (64 * 255 + 511) / 1023), fullAlpha[0] & 0xFF);
            assertEquals(255, fullAlpha[3] & 0xFF);
        }

        @Test
        @DisplayName("expands 16-bit limited-range alpha without overflowing")
        void deepLimitedAlpha() {
            var stride = 3;
            var samples = new short[stride * 3];
            samples[stride + 1] = (short) 4096;
            samples[stride + 2] = (short) 32768;
            samples[2 * stride + 1] = (short) 60160;
            samples[2 * stride + 2] = (short) 65535;
            var tile = new Tile(null, new short[][]{samples}, stride, 16);
            var wide = new HeifCanvas("test", MediaLimits.DEFAULT, 2, 2, ChromaFormat.MONOCHROME, 16, true, true);
            wide.writeAlpha(tile, 0, 0, 2, 2, true);
            var alpha = ((Yuv16Picture) wide.toPicture(ColorInfo.BT709_LIMITED)).alpha();
            var middle = ((32768L - 4096) * 65535 + 56064 / 2) / 56064;
            assertArrayEquals(new short[]{0, (short) middle, (short) 65535, (short) 65535}, alpha);
            var narrow = new HeifCanvas("test", MediaLimits.DEFAULT, 2, 2, ChromaFormat.MONOCHROME, 16, false, true);
            narrow.writeAlpha(tile, 0, 0, 2, 2, true);
            var narrowAlpha = ((YuvPicture) narrow.toPicture(ColorInfo.BT709_LIMITED)).alpha();
            assertEquals((middle * 255 + 32767) / 65535, narrowAlpha[1] & 0xFF);
            assertEquals(255, narrowAlpha[2] & 0xFF);
        }

        @Test
        @DisplayName("rejects a picture of another layout or depth, and checks the limits before allocating")
        void checks() {
            var canvas = new HeifCanvas("heic", MediaLimits.DEFAULT, 2, 2, ChromaFormat.YUV420, 8, false, false);
            var other = YuvPicture.allocate(2, 2, ChromaFormat.YUV444, false, ColorInfo.BT709_LIMITED);
            var failure = assertThrows(MediaException.Malformed.class, () -> canvas.writeColor(other, 0, 0, 2, 2));
            assertEquals("heic: the inputs of a grid differ in layout or depth", failure.getMessage());
            assertThrows(MediaException.LimitExceeded.class, () -> new HeifCanvas("heic",
                    MediaLimits.builder().maxPixels(3).build(), 2, 2, ChromaFormat.YUV420, 8, false, false));
            var same = YuvPicture.allocate(2, 2, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            same.y()[3] = 77;
            canvas.writeColor(same, 0, 0, 2, 2);
            assertEquals(77, ((YuvPicture) canvas.toPicture(ColorInfo.BT709_LIMITED)).y()[3]);
        }
    }

    @Nested
    @DisplayName("HeifStills")
    class Stills {
        @Test
        @DisplayName("converts declared sizes with the format's messages")
        void sizes() {
            assertEquals(640, HeifStills.checkedSize("avif", 640));
            assertEquals("avif: invalid size 0", assertThrows(MediaException.Malformed.class,
                    () -> HeifStills.checkedSize("avif", 0)).getMessage());
            assertEquals("heic: size 4294967295 exceeds the largest picture",
                    assertThrows(MediaException.LimitExceeded.class,
                            () -> HeifStills.checkedSize("heic", 0xFFFF_FFFFL)).getMessage());
        }

        @Test
        @DisplayName("reads grids, refusing oversized payloads, and lists the usable alpha auxiliaries in order")
        void gridAndAlpha() {
            var builder = new HeifBuilder();
            var tile = builder.item("av01", new byte[4], plain(ispe(8, 8)));
            var grid = builder.item("grid", HeifBuilder.grid(1, 1, 8, 8), plain(ispe(8, 8)));
            var oversized = builder.item("grid", new byte[16], plain(ispe(8, 8)));
            var alpha = builder.item("av01", new byte[4], plain(ispe(8, 8)), plain(alpha()));
            var depth = builder.item("av01", new byte[4], plain(ispe(8, 8)),
                    plain(auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:depth")));
            var unsupported = builder.item("av01", new byte[4], plain(ispe(8, 8)), plain(alpha()),
                    essential(HeifBuilder.box("zzzz", new byte[0])));
            var second = builder.item("av01", new byte[4], plain(ispe(8, 8)), plain(alpha()));
            builder.reference("dimg", grid, tile);
            builder.reference("auxl", alpha, grid);
            builder.reference("auxl", depth, grid);
            builder.reference("auxl", unsupported, grid);
            builder.reference("auxl", second, grid);
            builder.primary(grid);
            var data = builder.build();
            var source = MediaSource.of(data);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var parsed = HeifStills.readGrid("avif", source, file, file.item(grid), MediaLimits.DEFAULT);
            assertEquals(new HeifGrid(1, 1, 8, 8), parsed);
            var failure = assertThrows(MediaException.Malformed.class,
                    () -> HeifStills.readGrid("avif", source, file, file.item(oversized), MediaLimits.DEFAULT));
            assertEquals("avif: grid item " + oversized + " holds 16 bytes", failure.getMessage());
            var supported = Set.of("ispe", "auxC", "av1C");
            var candidates = HeifStills.alphaCandidates(file, file.item(grid), supported);
            assertEquals(List.of(alpha, second), candidates.stream().map(HeifItem::id).toList());
        }
    }

    @Nested
    @DisplayName("HeifSequence")
    class Sequence {
        private static HeifMovie.Track track(int id, String entry, long samples, Map<String, int[]> references,
                                             List<HeifProperty> properties) {
            return new HeifMovie.Track(id, "pict", 8, 8, references, entry, properties, samples, 0);
        }

        @Test
        @DisplayName("selects the color track, with or without excluding thumbnails, and its alpha track")
        void selection() {
            var thumbnail = track(1, "hvc1", 1, Map.of("thmb", new int[]{2}), List.of());
            var color = track(2, "hvc1", 10, Map.of(), List.of());
            var empty = track(3, "hvc1", 0, Map.of("auxl", new int[]{2}), List.of());
            var depth = track(4, "hvc1", 10, Map.of("auxl", new int[]{2}),
                    List.of(new HeifProperty.AuxiliaryType("auxi", "urn:mpeg:mpegB:cicp:systems:auxiliary:depth", new byte[0])));
            var alphaTrack = track(5, "hev1", 10, Map.of("auxl", new int[]{2}), List.of());
            var movie = new HeifMovie(List.of(thumbnail, color, empty, depth, alphaTrack));
            var hevc = (java.util.function.Predicate<String>) entry -> entry.equals("hvc1") || entry.equals("hev1");
            assertSame(thumbnail, HeifSequence.colorTrack(movie, hevc, false));
            assertSame(color, HeifSequence.colorTrack(movie, hevc, true));
            assertSame(alphaTrack, HeifSequence.alphaTrack(movie, 2, hevc));
            assertNull(HeifSequence.colorTrack(movie, "av01"::equals, true));
            assertNull(HeifSequence.alphaTrack(movie, 1, hevc));
        }

        @Test
        @DisplayName("interleaves the color and alpha samples and bounds the samples held back")
        void interleave() {
            var packets = new ArrayDeque<Packet>();
            for (var i = 0; i < 3; i++) {
                packets.add(new Packet(1, new byte[]{(byte) i}, i, i, 1, true));
            }
            for (var i = 0; i < 3; i++) {
                packets.add(new Packet(2, new byte[]{(byte) (10 + i)}, i, i, 1, true));
            }
            packets.add(new Packet(9, new byte[1], 0, 0, 1, true));
            var sequence = new HeifSequence(new QueueDemuxer(packets), 1, 2, MediaLimits.DEFAULT, "avif");
            assertEquals(0, sequence.nextColor().data()[0]);
            assertEquals(10, sequence.nextAlpha().data()[0]);
            assertEquals(1, sequence.nextColor().data()[0]);
            assertEquals(11, sequence.nextAlpha().data()[0]);
            assertEquals(2, sequence.nextColor().data()[0]);
            assertEquals(12, sequence.nextAlpha().data()[0]);
            assertNull(sequence.nextColor());
            assertNull(sequence.nextAlpha());
            var noAlpha = new HeifSequence(new QueueDemuxer(new ArrayDeque<>()), 1, -1, MediaLimits.DEFAULT, "avif");
            assertNull(noAlpha.nextAlpha());
            var far = new ArrayDeque<Packet>();
            for (var i = 0; i < 5; i++) {
                far.add(new Packet(1, new byte[1], i, i, 1, true));
            }
            var bounded = new HeifSequence(new QueueDemuxer(far), 1, 2, MediaLimits.builder().maxFrames(3).build(), "heic");
            var failure = assertThrows(MediaException.LimitExceeded.class, bounded::nextAlpha);
            assertEquals("heic: the color and alpha samples are too far apart", failure.getMessage());
        }
    }

    /** A demuxer that returns queued packets. */
    private record QueueDemuxer(ArrayDeque<Packet> packets) implements Demuxer {
        @Override
        public ContainerFormat format() {
            return ContainerFormat.MP4;
        }

        @Override
        public List<Track> tracks() {
            return List.of();
        }

        @Override
        public Map<String, String> metadata() {
            return Map.of();
        }

        @Override
        public long durationMicros() {
            return -1;
        }

        @Override
        public Packet read() {
            return packets.poll();
        }
    }

    @Test
    @DisplayName("holds a reconstructed still with its geometry")
    void still() {
        var picture = YuvPicture.allocate(2, 2, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        var geometry = new HeifGeometry(0, 0, 2, 2, com.github.auties00.smalt.image.Orientation.NORMAL);
        var still = new HeifStill(picture, 4, 4, geometry);
        assertInstanceOf(YuvPicture.class, still.picture());
        assertEquals(4, still.width());
    }
}
