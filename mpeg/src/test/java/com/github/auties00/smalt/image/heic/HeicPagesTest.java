package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageGroup;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.heic.HeicReference.Planes;
import com.github.auties00.smalt.image.heic.HeicStructureTest.Coded;
import com.github.auties00.smalt.image.heic.internal.StillDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.image.heic.HeicStructureTest.assertPlanes;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.encode;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.grid;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.heic;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.still;
import static com.github.auties00.smalt.image.heic.HeicStructureTest.tile;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the page reader of HEIF collections: which items are master images, how alternatives collapse into one
 * page, the order of the pages and the reported entity groups, on files built box by box around x265 payloads
 * (each page checked against ffmpeg's decode of its payload and against the item decoded on its own) and on the
 * Nokia conformance files whose structure the conformance file descriptions give.
 */
@DisplayName("HEIC pages")
class HeicPagesTest {
    /** An essential property no decoder implements, which makes an item undecodable. */
    private static final HeifBuilder.Property UNKNOWN = essential(HeifBuilder.box("zzzz", new byte[4]));

    private static Planes direct(byte[] data, int item) {
        var source = MediaSource.of(data);
        var file = HeifFile.parse(source, MediaLimits.DEFAULT, false);
        return Planes.of(StillDecoder.decode(source, file, file.item(item), MediaLimits.DEFAULT, false).picture());
    }

    private static PageReader pages(byte[] data) {
        return HeicDecoder.openPages(MediaSource.of(data));
    }

    private static Coded content(int index) {
        return tile((index % 3) * 64, (index / 3) * 64, "yuv420p");
    }

    /**
     * The collection the fixtures share: three master images (the first primary), a hidden item, a thumbnail of the
     * first, an alpha plane of the second, an alternatives group whose first entity is undecodable, a grid of
     * hidden tiles and an EXIF item; a stereo pair of the second and third, a burst of the three and a
     * time-synchronized group naming the third and a track.
     */
    private record Collection(byte[] data, int[] masters, Coded[] coded, int alternative, int gridItem,
                              List<Coded> tileContents) {
        static Collection build() {
            var builder = heic();
            var coded = new Coded[]{content(0), content(1), content(2), content(3), content(4)};
            var a = still(builder, coded[0]);
            var b = still(builder, coded[1]);
            var c = still(builder, coded[2]);
            var hidden = still(builder, coded[3]);
            builder.hidden(hidden);
            var thumbnail = still(builder, encode("scale=32:32", 32, 32, "yuv420p", ""));
            builder.reference("thmb", thumbnail, a);
            HeicStructureTest.alphaItem(builder, HeicStructureTest.alphaPlane(64, 64, ""), b);
            var undecodable = still(builder, coded[3], UNKNOWN);
            var alternative = still(builder, coded[4]);
            var tiles = HeicStructureTest.tiles("yuv420p");
            var grid = grid(builder, tiles, 2, 128, 128);
            var exif = builder.item("Exif", new byte[]{0, 0, 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0});
            builder.reference("cdsc", exif, a);
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 100, undecodable, alternative),
                    HeifBuilder.entityGroup("ster", 101, b, c), HeifBuilder.entityGroup("brst", 102, a, b, c),
                    HeifBuilder.entityGroup("tsyn", 103, c, 900)));
            builder.primary(a);
            return new Collection(builder.build(), new int[]{a, b, c}, coded, alternative, grid, tiles);
        }
    }

    @Nested
    @DisplayName("collections built box by box")
    class Built {
        @Test
        @DisplayName("lists the primary image, the other master images, the decodable alternative and the grid")
        void masters() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var collection = Collection.build();
            try (var reader = pages(collection.data())) {
                assertEquals(5, reader.pageCount());
                var expected = new ArrayList<Planes>();
                for (var i = 0; i < 3; i++) {
                    expected.add(collection.coded()[i].reference());
                }
                expected.add(collection.coded()[4].reference());
                expected.add(HeicStructureTest.stitched(collection.tileContents().stream().map(Coded::reference).toList(),
                        2, 64, 128, 128));
                var items = new int[]{collection.masters()[0], collection.masters()[1], collection.masters()[2],
                        collection.alternative(), collection.gridItem()};
                for (var page = 0; page < 5; page++) {
                    var image = reader.read(page);
                    var planes = Planes.of(image.picture());
                    // the alpha plane of the second image comes with it, the payload reference has none
                    assertPlanes(expected.get(page), page == 1 ? planes.withAlpha(null) : planes);
                    assertPlanes(direct(collection.data(), items[page]), planes);
                    assertEquals(ImageFormat.HEIC, image.format());
                }
                assertTrue(reader.read(1).picture().hasAlpha());
                assertTrue(reader.read(0).exif().isPresent(), "the EXIF item of the primary image");
                assertFalse(reader.read(2).exif().isPresent());
            }
        }

        @Test
        @DisplayName("page 0 is what decode returns, and pages read in any order and repeatedly are identical")
        void order() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var collection = Collection.build();
            var decoded = HeicDecoder.decode(MediaSource.of(collection.data()));
            try (var reader = pages(collection.data())) {
                var third = Planes.of(reader.read(3).picture());
                var first = Planes.of(reader.read(1).picture());
                assertPlanes(Planes.of(decoded.picture()), Planes.of(reader.read(0).picture()));
                assertPlanes(third, Planes.of(reader.read(3).picture()));
                assertPlanes(first, Planes.of(reader.read(1).picture()));
            }
        }

        @Test
        @DisplayName("probes every page from its headers, with the page count as frame count")
        void probe() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var collection = Collection.build();
            try (var reader = pages(collection.data())) {
                var sizes = new int[]{64, 64, 64, 64, 128};
                for (var page = 0; page < reader.pageCount(); page++) {
                    var info = reader.probe(page);
                    assertEquals(sizes[page], info.width(), "page " + page);
                    assertEquals(sizes[page], info.height(), "page " + page);
                    assertEquals(5, info.frameCount());
                    assertFalse(info.animated());
                    assertEquals(page == 1, info.hasAlpha(), "page " + page);
                }
                assertThrows(IndexOutOfBoundsException.class, () -> reader.probe(5));
                assertThrows(IndexOutOfBoundsException.class, () -> reader.read(-1));
            }
        }

        @Test
        @DisplayName("reports the stereo pair left then right, the burst and the group naming a track")
        void groups() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var collection = Collection.build();
            try (var reader = pages(collection.data())) {
                assertEquals(List.of(new PageGroup("ster", List.of(1, 2)), new PageGroup("brst", List.of(0, 1, 2)),
                        new PageGroup("tsyn", List.of(2))), reader.groups());
            }
            try (var reader = Images.openPages(MediaSource.of(collection.data()))) {
                assertEquals(5, reader.pageCount());
                assertEquals(3, reader.groups().size());
            }
        }

        @Test
        @DisplayName("keeps the view order of a stereo pair listed right to left in item order")
        void stereoOrder() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            var a = still(builder, content(0));
            var b = still(builder, content(1));
            var c = still(builder, content(2));
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("ster", 1, c, a)));
            builder.primary(a);
            var data = builder.build();
            try (var reader = pages(data)) {
                assertEquals(3, reader.pageCount());
                assertEquals(List.of(new PageGroup("ster", List.of(2, 0))), reader.groups());
                assertPlanes(direct(data, b), Planes.of(reader.read(1).picture()));
                for (var view = 0; view < 2; view++) {
                    var page = reader.groups().getFirst().pages().get(view);
                    assertPlanes(Planes.of(HeicDecoder.decodeStereoView(MediaSource.of(data), view).orElseThrow()
                            .picture()), Planes.of(reader.read(page).picture()));
                }
                assertPlanes(direct(data, c), Planes.of(reader.read(2).picture()));
            }
        }

        @Test
        @DisplayName("does not report a stereo pair one of whose views is not a page")
        void hiddenView() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            var a = still(builder, content(0));
            var b = still(builder, content(1));
            builder.hidden(b);
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("ster", 1, a, b)));
            builder.primary(a);
            try (var reader = pages(builder.build())) {
                assertEquals(1, reader.pageCount());
                assertEquals(List.of(), reader.groups());
            }
        }

        @Test
        @DisplayName("shows the alternative preferred to the primary item as page 0 and gives its group no other page")
        void primaryAlternatives() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            var a = still(builder, content(0));
            var preferred = still(builder, content(1));
            var other = still(builder, content(2));
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, preferred, a)));
            builder.primary(a);
            var data = builder.build();
            try (var reader = pages(data)) {
                assertEquals(2, reader.pageCount());
                assertPlanes(direct(data, preferred), Planes.of(reader.read(0).picture()));
                assertPlanes(direct(data, other), Planes.of(reader.read(1).picture()));
            }
        }

        @Test
        @DisplayName("keeps an alternatives group none of whose items decodes as one page that raises Unsupported")
        void undecodableAlternatives() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            var a = still(builder, content(0));
            var first = still(builder, content(1), UNKNOWN);
            var second = still(builder, content(2), UNKNOWN);
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, first, second)));
            builder.primary(a);
            try (var reader = pages(builder.build())) {
                assertEquals(2, reader.pageCount());
                assertThrows(MediaException.Unsupported.class, () -> reader.read(1));
                assertThrows(MediaException.Unsupported.class, () -> reader.probe(1));
            }
        }

        @Test
        @DisplayName("shows an item two alternatives groups list on one page only")
        void overlappingAlternatives() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            var a = still(builder, content(0));
            var b = still(builder, content(1));
            var c = still(builder, content(2));
            var d = still(builder, content(3));
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, b, c),
                    HeifBuilder.entityGroup("altr", 2, c, d), HeifBuilder.entityGroup("brst", 3, a, b, c, d)));
            builder.primary(a);
            var data = builder.build();
            try (var reader = pages(data)) {
                assertEquals(3, reader.pageCount());
                assertPlanes(direct(data, b), Planes.of(reader.read(1).picture()));
                assertPlanes(direct(data, d), Planes.of(reader.read(2).picture()));
                assertPlanes(content(3).reference(), Planes.of(reader.read(2).picture()));
                assertEquals(List.of(new PageGroup("brst", List.of(0, 1, 2))), reader.groups());
            }
        }

        @Test
        @DisplayName("gives an item two alternatives groups list to the first group even when the second comes first")
        void overlappingAlternativesOrder() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            var a = still(builder, content(0));
            var x = still(builder, content(1));
            var y = still(builder, content(2));
            var z = still(builder, content(3));
            builder.metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 101, z, y),
                    HeifBuilder.entityGroup("altr", 102, y, x)));
            builder.primary(a);
            var data = builder.build();
            try (var reader = pages(data)) {
                assertEquals(3, reader.pageCount());
                assertPlanes(direct(data, x), Planes.of(reader.read(1).picture()));
                assertPlanes(direct(data, z), Planes.of(reader.read(2).picture()));
                assertPlanes(content(1).reference(), Planes.of(reader.read(1).picture()));
                assertPlanes(content(3).reference(), Planes.of(reader.read(2).picture()));
            }
        }

        @Test
        @DisplayName("lists an AV1-coded image as a page that names smalt-video when the artifact is absent")
        void foreignCoding() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            var a = still(builder, content(0));
            builder.item("av01", new byte[16], plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.av1C(new byte[]{(byte) 0x81, 0, 0, 0})));
            builder.primary(a);
            try (var reader = pages(builder.build())) {
                assertEquals(2, reader.pageCount());
                var failure = assertThrows(MediaException.Unsupported.class, () -> reader.read(1));
                assertTrue(failure.getMessage().contains("smalt-video"), failure.getMessage());
                assertThrows(MediaException.Unsupported.class, () -> reader.probe(1));
                assertPlanes(content(0).reference(), Planes.of(reader.read(0).picture()));
            }
        }

        @Test
        @DisplayName("decodes an image opaque whose only alpha image is AV1-coded when smalt-video is absent")
        void foreignAlpha() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            var a = still(builder, content(0));
            var alpha = builder.item("av01", new byte[16], plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.av1C(new byte[]{(byte) 0x81, 0, 0, 0})),
                    essential(HeifBuilder.auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha")));
            builder.reference("auxl", alpha, a).hidden(alpha);
            builder.primary(a);
            var data = builder.build();
            var image = HeicDecoder.decode(MediaSource.of(data));
            assertFalse(image.picture().hasAlpha());
            assertPlanes(content(0).reference(), Planes.of(image.picture()));
            assertFalse(HeicDecoder.probe(MediaSource.of(data)).hasAlpha());
        }

        @Test
        @DisplayName("applies the reduced-size hint to every page, which picks the thumbnail of its own image")
        void thumbnails() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var collection = Collection.build();
            try (var reader = HeicDecoder.openPages(MediaSource.of(collection.data()),
                    ImageDecoderOptions.ofMinimumSize(16, 16))) {
                var first = reader.read(0);
                assertEquals(32, first.picture().width());
                assertEquals(64, first.sourceWidth());
                assertEquals(64, reader.read(2).picture().width(), "an image without a thumbnail decodes in full");
            }
        }

        @Test
        @DisplayName("counts the pages against the frame limit when opening")
        void frameLimit() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var collection = Collection.build();
            var limits = MediaLimits.builder().maxFrames(4).build();
            assertThrows(MediaException.LimitExceeded.class, () -> HeicDecoder.openPages(
                    MediaSource.of(collection.data()), ImageDecoderOptions.builder().limits(limits).build()));
        }

        @Test
        @DisplayName("raises only media exceptions on a collection truncated anywhere, when opening or reading a page")
        void truncated() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var data = Collection.build().data();
            var step = Math.max(1, data.length / 97);
            for (var cut = 0; cut < data.length; cut += step) {
                var part = Arrays.copyOf(data, cut);
                try (var reader = pages(part)) {
                    for (var page = 0; page < reader.pageCount(); page++) {
                        try {
                            reader.probe(page);
                            reader.read(page);
                        } catch (MediaException expected) {
                            // a page whose data is cut off raises a media exception, the others still read
                        }
                    }
                    reader.groups();
                } catch (MediaException expected) {
                    // a file cut inside its meta box does not open
                }
            }
        }

        @Test
        @DisplayName("reads a file with one image as one page with no groups")
        void single() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var builder = heic();
            builder.primary(still(builder, content(0)));
            try (var reader = pages(builder.build())) {
                assertEquals(1, reader.pageCount());
                assertEquals(List.of(), reader.groups());
                assertEquals(Orientation.NORMAL, reader.probe(0).orientation());
            }
        }
    }

    @Nested
    @DisplayName("generic-brand files of AV1-coded images without smalt-video")
    class Dispatch {
        private static byte[] av1File() {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            builder.primary(builder.item("av01", new byte[16], plain(HeifBuilder.ispe(64, 64)),
                    essential(HeifBuilder.av1C(new byte[]{(byte) 0x81, 0, 0, 0}))));
            return builder.build();
        }

        @Test
        @DisplayName("are recognized as AVIF and raise Unsupported naming smalt-video in every HEIC entry point")
        void unsupported() {
            var data = av1File();
            assertEquals(ImageFormat.HEIC, ImageFormat.detect(data, data.length).orElseThrow());
            assertEquals(ImageFormat.AVIF, Images.detect(MediaSource.of(data)).orElseThrow());
            for (var operation : List.<Runnable>of(() -> HeicDecoder.decode(MediaSource.of(data)),
                    () -> HeicDecoder.probe(MediaSource.of(data)), () -> HeicDecoder.openAnimation(MediaSource.of(data)),
                    () -> HeicDecoder.openPages(MediaSource.of(data)), () -> Images.decode(MediaSource.of(data)))) {
                var failure = assertThrows(MediaException.Unsupported.class, operation::run);
                assertTrue(failure.getMessage().contains("smalt-video"), failure.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Nokia conformance files")
    class Nokia {
        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = ';', value = {
                "C001.heic; 1018; ",
                "C003.heic; 1002 1005; ",
                "C004.heic; 1002 1004 1006 1008 1010 1012 1014 1016 1018 1020; ",
                "C009.heic; 1002; ",
                "C010.heic; 1002; ",
                "C011.heic; 1002; ",
                "C012.heic; 1002 1004 1006 1008 1010 1012 1014 1016 1018 1020 1044; ",
                "C013.heic; 1002 1005; ",
                "C014.heic; 1007 1003; ",
                "C040.heic; 1014 1002 1005 1008 1011; ",
                "C043.heic; 1002 1004 1006; ",
                "C045.heic; 1002 1004 1006 1008; brst 0 1 2 3",
                "C047.heic; 1002 1004 1007 1009; tsyn 0 2, tsyn 1 3",
                "C048.heic; 1004; ",
                "C049.heic; 1820; iaug 0",
                "C050.heic; 1002 1004 1006 1008 1010 1012; albc 0 1 2 5, albc 3 4 5 0",
                "C051.heic; 1002 1004; ",
                "C053.heic; 1002 1004; ster 0 1",
                "MIAF005.heic; 1004; ",
                "MIAF006.heic; 1004; ",
                "multilayer003.heic; 1002 1004; ster 0 1",
                "multilayer005.heic; 20003 20004; ster 0 1"
        })
        @DisplayName("list the master images the descriptions give, each equal to its item decoded on its own")
        void pages(String name, String items, String groups) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var data = HeicVectors.read(HeicVectors.nokia(name));
            var ids = Arrays.stream(items.split(" ")).mapToInt(Integer::parseInt).toArray();
            var source = MediaSource.of(data);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT, false);
            try (var reader = HeicDecoder.openPages(source)) {
                assertEquals(ids.length, reader.pageCount(), name);
                assertEquals(groups == null ? List.of() : parseGroups(groups), reader.groups(), name);
                assertPlanes(Planes.of(HeicDecoder.decode(MediaSource.of(data)).picture()),
                        Planes.of(reader.read(0).picture()));
                for (var page = 1; page < ids.length; page++) {
                    var image = reader.read(page);
                    var item = file.item(ids[page]);
                    assertPlanes(direct(data, ids[page]), Planes.of(image.picture()));
                    if (item.type().equals("hvc1") && item.property(HeifProperty.CleanAperture.class) == null) {
                        var planes = Planes.of(image.picture());
                        assertPlanes(HeicReference.item(source, file, item, planes.width(), planes.height(),
                                planes.format(), planes.depth()), planes);
                    }
                    assertEquals(reader.pageCount(), reader.probe(page).frameCount());
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"C031.heic", "C032.heic"})
        @DisplayName("an image sequence without a primary image is one page, the first frame decode returns")
        void sequenceOnly(String name) {
            var data = HeicVectors.read(HeicVectors.nokia(name));
            try (var reader = HeicDecoder.openPages(MediaSource.of(data))) {
                assertEquals(1, reader.pageCount());
                assertEquals(List.of(), reader.groups());
                var expected = HeicDecoder.decode(MediaSource.of(data)).picture();
                var actual = reader.read(0).picture();
                assertEquals(expected.getClass(), actual.getClass());
                assertEquals(expected.width(), actual.width());
                assertEquals(expected.height(), actual.height());
                assertArrayEquals(ImageOps.toRgba(expected).pixels(), ImageOps.toRgba(actual).pixels());
                var info = reader.probe(0);
                assertEquals(1, info.frameCount());
                assertFalse(info.animated());
                assertEquals(expected.width(), info.width());
            }
        }

        @Test
        @DisplayName("C014: the second identity derivation keeps its own half-turn orientation")
        void orientation() {
            var data = HeicVectors.read(HeicVectors.nokia("C014.heic"));
            try (var reader = HeicDecoder.openPages(MediaSource.of(data))) {
                assertEquals(Orientation.ROTATE_180, reader.read(1).orientation());
                assertEquals(Orientation.ROTATE_180, reader.probe(1).orientation());
            }
        }

        @Test
        @DisplayName("multilayer005: the right view page is the multiview layer decodeStereoView shows")
        void multiview() {
            var data = HeicVectors.read(HeicVectors.nokia("multilayer005.heic"));
            try (var reader = HeicDecoder.openPages(MediaSource.of(data))) {
                for (var view = 0; view < 2; view++) {
                    assertPlanes(Planes.of(HeicDecoder.decodeStereoView(MediaSource.of(data), view).orElseThrow()
                            .picture()), Planes.of(reader.read(view).picture()));
                }
            }
        }

        private static List<PageGroup> parseGroups(String text) {
            var result = new ArrayList<PageGroup>();
            for (var group : text.split(",")) {
                var parts = group.trim().split(" ");
                var pages = new ArrayList<Integer>();
                for (var i = 1; i < parts.length; i++) {
                    pages.add(Integer.parseInt(parts[i]));
                }
                result.add(new PageGroup(parts[0], pages));
            }
            return result;
        }
    }
}
