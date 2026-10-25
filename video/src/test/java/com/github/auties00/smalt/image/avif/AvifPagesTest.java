package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageGroup;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.avif.AvifReference.Planes;
import com.github.auties00.smalt.image.avif.AvifStructureTest.Coded;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HeifSequenceBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static com.github.auties00.smalt.image.avif.AvifStructureTest.alphaPlane;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.encode;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.still;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.tile;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AVIF images in files without the AVIF brands (a file is AVIF by the coding of its image, not by its
 * brands) and the page reader of AVIF collections, on files built box by box around libaom-coded payloads: each
 * page checked against ffmpeg's decode of its payload and against the item decoded on its own.
 */
@DisplayName("AVIF collections and generic brands")
class AvifPagesTest {
    /** An essential property no decoder implements, which makes an item undecodable. */
    private static final HeifBuilder.Property UNKNOWN = essential(HeifBuilder.box("zzzz", new byte[4]));

    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static Coded content(int index) {
        return tile((index % 2) * 64, (index / 2) * 64, "yuv420p");
    }

    private static Planes reference(Coded coded) {
        return AvifReference.decode(coded.payload(), coded.width(), coded.height(), ChromaFormat.YUV420, 8);
    }

    private static Planes withoutAlpha(Planes planes) {
        return new Planes(planes.width(), planes.height(), planes.format(), planes.depth(),
                new int[][]{planes.planes()[0], planes.planes()[1], planes.planes()[2], null});
    }

    private static void assertSamePixels(Picture expected, Picture actual) {
        if (expected instanceof RgbaPicture rgba) {
            var other = assertInstanceOf(RgbaPicture.class, actual);
            assertEquals(rgba.width(), other.width());
            assertEquals(rgba.height(), other.height());
            assertArrayEquals(rgba.pixels(), other.pixels());
        } else {
            assertPlanes(Planes.of(expected), Planes.of(actual));
        }
    }

    private static void assertSame(DecodedImage expected, DecodedImage actual) {
        assertSamePixels(expected.picture(), actual.picture());
        assertEquals(expected.format(), actual.format());
        assertEquals(expected.orientation(), actual.orientation());
        assertEquals(expected.sourceWidth(), actual.sourceWidth());
        assertEquals(expected.sourceHeight(), actual.sourceHeight());
        assertEquals(expected.colorProfile().isPresent(), actual.colorProfile().isPresent());
        assertArrayEquals(expected.iccProfile().orElse(null), actual.iccProfile().orElse(null));
        assertArrayEquals(expected.exif().orElse(null), actual.exif().orElse(null));
        assertArrayEquals(expected.xmp().orElse(null), actual.xmp().orElse(null));
    }

    private static void assertPlanes(Planes expected, Planes actual) {
        assertEquals(expected.width(), actual.width(), "width");
        assertEquals(expected.height(), actual.height(), "height");
        assertEquals(expected.format(), actual.format(), "format");
        assertEquals(expected.depth(), actual.depth(), "depth");
        for (var plane = 0; plane < 4; plane++) {
            assertArrayEquals(expected.planes()[plane], actual.planes()[plane], "plane " + plane);
        }
    }

    private static int alphaItem(HeifBuilder builder, Coded coded, int master) {
        var id = builder.item("av01", coded.payload(), plain(HeifBuilder.ispe(coded.width(), coded.height())),
                essential(HeifBuilder.av1C(coded.config())), essential(HeifBuilder.alpha()));
        builder.reference("auxl", id, master).hidden(id);
        return id;
    }

    private static int grid(HeifBuilder builder, List<Coded> tiles) {
        var ids = new int[tiles.size()];
        for (var i = 0; i < ids.length; i++) {
            ids[i] = still(builder, tiles.get(i));
            builder.hidden(ids[i]);
        }
        var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 128, 128), plain(HeifBuilder.ispe(128, 128)));
        builder.reference("dimg", grid, ids);
        return grid;
    }

    /**
     * Builds the same file under two sets of brands.
     */
    private static byte[][] branded(Function<HeifBuilder, Integer> content) {
        var avif = new HeifBuilder().brands("avif", "avif", "mif1", "miaf");
        avif.primary(content.apply(avif));
        var generic = new HeifBuilder().brands("mif1", "mif1", "miaf");
        generic.primary(content.apply(generic));
        return new byte[][]{avif.build(), generic.build()};
    }

    @Nested
    @DisplayName("files with only the generic HEIF brands")
    class GenericBrands {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"still", "grid", "alpha", "exif"})
        @DisplayName("decode exactly as the same file under the avif brand")
        void same(String kind) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var coded = content(0);
            var tiles = List.of(content(0), content(1), content(2), content(3));
            var alpha = alphaPlane(64, 64);
            var files = branded(builder -> switch (kind) {
                case "still" -> still(builder, coded);
                case "grid" -> grid(builder, tiles);
                case "alpha" -> {
                    var id = still(builder, coded);
                    alphaItem(builder, alpha, id);
                    yield id;
                }
                default -> {
                    var id = still(builder, coded, essential(HeifBuilder.irot(1)));
                    var exif = builder.item("Exif", new byte[]{0, 0, 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0});
                    builder.reference("cdsc", exif, id);
                    yield id;
                }
            });
            var avif = files[0];
            var generic = files[1];
            assertEquals(ImageFormat.HEIC, ImageFormat.detect(generic, generic.length).orElseThrow(),
                    "the brands alone say HEIC");
            assertEquals(ImageFormat.AVIF, Images.detect(MediaSource.of(generic)).orElseThrow());
            for (var options : List.of(ImageDecoderOptions.defaults(), WIDE,
                    ImageDecoderOptions.builder().convertToSrgb(true).build())) {
                var expected = AvifDecoder.decode(MediaSource.of(avif), options);
                assertSame(expected, AvifDecoder.decode(MediaSource.of(generic), options));
                assertSame(expected, Images.decode(MediaSource.of(generic), options));
            }
            assertEquals(AvifDecoder.probe(MediaSource.of(avif)), AvifDecoder.probe(MediaSource.of(generic)));
            assertEquals(AvifDecoder.probe(MediaSource.of(avif)), Images.probe(MediaSource.of(generic)));
            try (var expected = AvifDecoder.openAnimation(MediaSource.of(avif));
                 var actual = Images.openAnimation(MediaSource.of(generic))) {
                assertSamePixels(expected.read().picture(), actual.read().picture());
            }
            if (kind.equals("still")) {
                assertPlanes(reference(coded), Planes.of(AvifDecoder.decode(MediaSource.of(generic)).picture()));
            }
            if (kind.equals("alpha")) {
                assertEquals(AvifDecoder.auxiliaryImages(MediaSource.of(avif)),
                        AvifDecoder.auxiliaryImages(MediaSource.of(generic)));
                assertPlanes(Planes.of(AvifDecoder.decodeAuxiliaryImage(MediaSource.of(avif), 0).picture()),
                        Planes.of(AvifDecoder.decodeAuxiliaryImage(MediaSource.of(generic), 0).picture()));
            }
        }

        @Test
        @DisplayName("an msf1 sequence of AV1 frames without a primary image plays as under the avis brand")
        void sequence() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var frames = new ArrayList<Coded>();
            for (var i = 0; i < 3; i++) {
                frames.add(encode("crop=64:64:" + (i * 16) + ":" + (i * 8), 64, 64, "yuv420p"));
            }
            var datas = new ArrayList<byte[]>();
            for (var brands : List.of(new String[]{"avis", "avis", "msf1", "iso8", "mif1", "miaf"},
                    new String[]{"msf1", "msf1", "iso8", "miaf"})) {
                var builder = new HeifSequenceBuilder(brands[0], 1000, Arrays.copyOfRange(brands, 1, brands.length));
                builder.track("av01", 64, 64, 100, frames.stream().map(Coded::payload).toList(),
                        HeifBuilder.av1C(frames.getFirst().config()));
                datas.add(builder.build());
            }
            var generic = datas.get(1);
            assertEquals(ImageFormat.AVIF, Images.detect(MediaSource.of(generic)).orElseThrow());
            assertSame(AvifDecoder.decode(MediaSource.of(datas.get(0))), Images.decode(MediaSource.of(generic)));
            try (var expected = AvifDecoder.openAnimation(MediaSource.of(datas.get(0)));
                 var actual = Images.openAnimation(MediaSource.of(generic))) {
                assertEquals(expected.frameCount(), actual.frameCount());
                for (var frame = expected.read(); frame != null; frame = expected.read()) {
                    var other = actual.read();
                    assertEquals(frame.timestampMicros(), other.timestampMicros());
                    assertSamePixels(frame.picture(), other.picture());
                }
            }
        }
    }

    @Nested
    @DisplayName("pages")
    class Pages {
        /**
         * Three master images (the first primary), an alpha plane of the second, an alternatives group whose first
         * entity is undecodable, a grid of hidden tiles, a thumbnail of the first, a stereo pair and a burst.
         */
        private static byte[] collection(List<Coded> coded, List<Coded> tiles, Coded alpha) {
            var builder = new HeifBuilder();
            var a = still(builder, coded.get(0));
            var b = still(builder, coded.get(1));
            var c = still(builder, coded.get(2));
            alphaItem(builder, alpha, b);
            var thumbnail = still(builder, encode("scale=32:32", 32, 32, "yuv420p"));
            builder.reference("thmb", thumbnail, a);
            var undecodable = still(builder, coded.get(3), UNKNOWN);
            var alternative = still(builder, coded.get(3));
            grid(builder, tiles);
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 50, undecodable, alternative),
                    HeifBuilder.entityGroup("ster", 51, c, b), HeifBuilder.entityGroup("brst", 52, a, b, c)));
            return builder.primary(a).build();
        }

        @Test
        @DisplayName("read every master image, each equal to its payload decoded by ffmpeg and to the item on its own")
        void masters() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var coded = List.of(content(0), content(1), content(2), content(3));
            var tiles = List.of(content(0), content(1), content(2), content(3));
            var alpha = alphaPlane(64, 64);
            var data = collection(coded, tiles, alpha);
            try (var reader = AvifDecoder.openPages(MediaSource.of(data))) {
                assertEquals(5, reader.pageCount());
                assertEquals(List.of(new PageGroup("ster", List.of(2, 1)), new PageGroup("brst", List.of(0, 1, 2))),
                        reader.groups());
                assertSame(AvifDecoder.decode(MediaSource.of(data)), reader.read(0));
                for (var page = 0; page < 4; page++) {
                    var planes = Planes.of(reader.read(page).picture());
                    assertPlanes(reference(coded.get(page)), page == 1 ? withoutAlpha(planes) : planes);
                    assertPlanes(Planes.of(AvifStructureTest.decode(coded.get(page))),
                            page == 1 ? withoutAlpha(planes) : planes);
                    assertEquals(5, reader.probe(page).frameCount());
                    assertEquals(page == 1, reader.probe(page).hasAlpha());
                }
                var single = new HeifBuilder();
                single.primary(grid(single, tiles));
                assertSame(AvifDecoder.decode(MediaSource.of(single.build())), reader.read(4));
                assertEquals(128, reader.probe(4).width());
            }
            try (var reader = Images.openPages(MediaSource.of(data))) {
                assertEquals(5, reader.pageCount());
            }
        }

        @Test
        @DisplayName("apply the options to every page: the reduced-size hint and the bit depth choice")
        void options() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var data = collection(List.of(content(0), content(1), content(2), content(3)),
                    List.of(content(0), content(1), content(2), content(3)), alphaPlane(64, 64));
            try (var reader = AvifDecoder.openPages(MediaSource.of(data), ImageDecoderOptions.ofMinimumSize(16, 16))) {
                assertEquals(32, reader.read(0).picture().width());
                assertEquals(64, reader.read(0).sourceWidth());
                assertEquals(64, reader.read(2).picture().width());
            }
        }

        @Test
        @DisplayName("list an HEVC-coded image as a page that names smalt-mpeg when the artifact is absent")
        void foreignCoding() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = new HeifBuilder();
            var a = still(builder, content(0));
            builder.item("hvc1", new byte[16], plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.box("hvcC", new byte[23])));
            builder.primary(a);
            try (var reader = AvifDecoder.openPages(MediaSource.of(builder.build()))) {
                assertEquals(2, reader.pageCount());
                var failure = assertThrows(MediaException.Unsupported.class, () -> reader.read(1));
                assertTrue(failure.getMessage().contains("smalt-mpeg"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("a file whose primary image is HEVC-coded is HEIC whatever its brands, and names smalt-mpeg here")
        void foreignFile() {
            var builder = new HeifBuilder().brands("avif", "avif", "mif1", "miaf");
            builder.primary(builder.item("hvc1", new byte[16], plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.box("hvcC", new byte[23]))));
            var data = builder.build();
            assertEquals(ImageFormat.AVIF, ImageFormat.detect(data, data.length).orElseThrow());
            assertEquals(ImageFormat.HEIC, Images.detect(MediaSource.of(data)).orElseThrow());
            for (var operation : List.<Runnable>of(() -> AvifDecoder.decode(MediaSource.of(data)),
                    () -> AvifDecoder.probe(MediaSource.of(data)), () -> AvifDecoder.openPages(MediaSource.of(data)),
                    () -> AvifDecoder.openAnimation(MediaSource.of(data)))) {
                var failure = assertThrows(MediaException.Unsupported.class, operation::run);
                assertTrue(failure.getMessage().contains("smalt-mpeg"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("count the pages against the frame limit when opening")
        void frameLimit() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var data = collection(List.of(content(0), content(1), content(2), content(3)),
                    List.of(content(0), content(1), content(2), content(3)), alphaPlane(64, 64));
            var options = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxFrames(4).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> AvifDecoder.openPages(MediaSource.of(data),
                    options));
        }
    }
}
