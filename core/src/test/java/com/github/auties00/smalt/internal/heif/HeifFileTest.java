package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.github.auties00.smalt.testkit.HeifBuilder.box;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.fullBox;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HEIF item parser on files written box by box with {@link HeifBuilder}: every
 * {@code meta} child, the three {@code iloc} construction methods, the container rules libavif
 * enforces, the grid and clean-aperture arithmetic, the movie summary of sequences, and
 * truncated or mutated input.
 */
@DisplayName("HEIF parser")
class HeifFileTest {
    private static final byte[] AV1C = {(byte) 0x81, 0x00, 0x0C, 0x00};

    private static HeifFile parse(byte[] data) {
        return HeifFile.parse(MediaSource.of(data), MediaLimits.DEFAULT);
    }

    private static byte[] simple() {
        var builder = new HeifBuilder();
        var image = builder.item("av01", new byte[]{1, 2, 3, 4, 5},
                plain(HeifBuilder.ispe(64, 48)), essential(HeifBuilder.av1C(AV1C)),
                plain(HeifBuilder.nclx(1, 13, 6, true)), plain(HeifBuilder.pixi(8, 8, 8)));
        var exif = builder.item("Exif", new byte[]{0, 0, 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8});
        var xmp = builder.mimeItem("application/rdf+xml", "<x/>".getBytes(StandardCharsets.UTF_8));
        builder.reference("cdsc", exif, image).reference("cdsc", xmp, image).primary(image);
        return builder.build();
    }

    @Nested
    @DisplayName("items")
    class Items {
        @Test
        @DisplayName("reads brands, items, properties, references and metadata")
        void structure() {
            var data = simple();
            var file = parse(data);
            assertEquals("avif", file.majorBrand());
            assertTrue(file.hasBrand("mif1"));
            assertFalse(file.hasMovie());
            var primary = file.primaryItem();
            assertEquals("av01", primary.type());
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, file.readItem(MediaSource.of(data), primary, MediaLimits.DEFAULT));
            var extent = primary.property(HeifProperty.SpatialExtent.class);
            assertEquals(64, extent.width());
            assertEquals(48, extent.height());
            var config = primary.property(HeifProperty.Av1Configuration.class);
            assertEquals(8, config.bitDepth());
            assertTrue(config.subsamplingX() && config.subsamplingY());
            // av1C is the only essential association: ispe, colr and pixi are not reported
            assertEquals("av1C", primary.unsupportedEssential(Set.of()));
            assertNull(primary.unsupportedEssential(Set.of("av1C")));
            var nclx = primary.property(HeifProperty.ColorNclx.class);
            assertEquals(new HeifProperty.ColorNclx(1, 13, 6, true), nclx);
            assertArrayEquals(new int[]{8, 8, 8}, primary.property(HeifProperty.PixelInformation.class).depths());
            var descriptions = file.descriptionsOf(primary.id());
            assertEquals(List.of("Exif", "mime"), descriptions.stream().map(HeifItem::type).toList());
            assertEquals("application/rdf+xml", descriptions.get(1).contentType());
            var tiff = HeifFile.exifTiff(file.readItem(MediaSource.of(data), descriptions.getFirst(), MediaLimits.DEFAULT));
            assertArrayEquals(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8}, tiff);
        }

        @Test
        @DisplayName("reads idat items, multi-extent items and items built from other items")
        void constructionMethods() {
            var builder = new HeifBuilder();
            var source = builder.idatItem("av01", "0123456789".getBytes(StandardCharsets.US_ASCII));
            var split = builder.item("av01", "abcdef".getBytes(StandardCharsets.US_ASCII));
            builder.extents(split, 1, new HeifBuilder.Extent(0, 3, 1), new HeifBuilder.Extent(7, 2, 1));
            var derived = builder.item("av01", new byte[0]);
            builder.extents(derived, 2, new HeifBuilder.Extent(2, 3, 1), new HeifBuilder.Extent(8, 2, 1));
            builder.reference("iloc", derived, source).primary(derived);
            var data = builder.build();
            var file = parse(data);
            var in = MediaSource.of(data);
            assertEquals("0123456789", new String(file.readItem(in, file.item(source), MediaLimits.DEFAULT), StandardCharsets.US_ASCII));
            assertEquals("01278", new String(file.readItem(in, file.item(split), MediaLimits.DEFAULT), StandardCharsets.US_ASCII));
            assertEquals("23489", new String(file.readItem(in, file.item(derived), MediaLimits.DEFAULT), StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("reads 32-bit identifiers and 15-bit property indexes")
        void wideFields() {
            var builder = new HeifBuilder().ilocVersion(2).wideIndex();
            var image = builder.item("av01", new byte[]{9}, essential(HeifBuilder.av1C(AV1C)), plain(HeifBuilder.ispe(1, 1)));
            builder.primary(image);
            var file = parse(builder.build());
            assertNotNull(file.primaryItem().property(HeifProperty.Av1Configuration.class));
            assertEquals("av1C", file.primaryItem().unsupportedEssential(Set.of()));
        }

        @Test
        @DisplayName("stops following item references at the depth limit, so cycles fail")
        void cyclicConstruction() {
            var builder = new HeifBuilder();
            var a = builder.item("av01", new byte[0]);
            var b = builder.item("av01", new byte[0]);
            builder.extents(a, 2, new HeifBuilder.Extent(0, 1, 1));
            builder.extents(b, 2, new HeifBuilder.Extent(0, 1, 1));
            builder.reference("iloc", a, b).reference("iloc", b, a).primary(a);
            var data = builder.build();
            var file = parse(data);
            var failure = assertThrows(MediaException.Malformed.class,
                    () -> file.readItem(MediaSource.of(data), file.item(a), MediaLimits.DEFAULT));
            assertTrue(failure.getMessage().contains("nested"), failure.getMessage());
        }

        @Test
        @DisplayName("rejects extents outside the file or the idat box, and items above the byte limit")
        void extentsOutside() {
            var builder = new HeifBuilder();
            var outside = builder.item("av01", new byte[4]);
            builder.extents(outside, 0, new HeifBuilder.Extent(1 << 20, 4, 1));
            var idat = builder.idatItem("av01", new byte[4]);
            builder.extents(idat, 1, new HeifBuilder.Extent(2, 4, 1));
            var big = builder.item("av01", new byte[64]);
            var data = builder.build();
            var file = parse(data);
            var in = MediaSource.of(data);
            assertThrows(MediaException.Malformed.class, () -> file.readItem(in, file.item(outside), MediaLimits.DEFAULT));
            assertThrows(MediaException.Malformed.class, () -> file.readItem(in, file.item(idat), MediaLimits.DEFAULT));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> file.readItem(in, file.item(big), MediaLimits.builder().maxBytes(32).build()));
        }

        @Test
        @DisplayName("reports the first unsupported essential property and ignores non-essential ones")
        void unsupportedEssential() {
            var builder = new HeifBuilder();
            var image = builder.item("av01", new byte[1], essential(HeifBuilder.av1C(AV1C)),
                    plain(box("fred", new byte[3])), essential(box("wxyz", new byte[2])));
            builder.primary(image);
            var item = parse(builder.build()).primaryItem();
            assertEquals("wxyz", item.unsupportedEssential(Set.of("av1C")));
            // A property the parser does not interpret is never supported, whatever the reader lists.
            assertEquals("wxyz", item.unsupportedEssential(Set.of("av1C", "wxyz")));
        }
    }

    @Nested
    @DisplayName("container rules")
    class Rules {
        @Test
        @DisplayName("requires ftyp first and a pict handler")
        void framing() {
            var data = simple();
            var noFtyp = Arrays.copyOfRange(data, data[3], data.length);
            assertThrows(MediaException.Malformed.class, () -> parse(noFtyp));
            var builder = new HeifBuilder();
            builder.primary(builder.item("av01", new byte[1]));
            var wrongHandler = new String(builder.build(), StandardCharsets.ISO_8859_1).replace("pict", "vide")
                    .getBytes(StandardCharsets.ISO_8859_1);
            assertThrows(MediaException.Malformed.class, () -> parse(wrongHandler));
        }

        @Test
        @DisplayName("rejects transformative properties not marked essential and an essential a1lx")
        void essentialRules() {
            for (var property : List.of(plain(HeifBuilder.irot(1)), plain(HeifBuilder.imir(0)),
                    plain(HeifBuilder.clap(1, 1, 1, 1, 0, 1, 0, 1)), plain(box("lsel", new byte[]{0, 0})),
                    plain(box("a1op", new byte[]{0})), essential(box("a1lx", new byte[]{0, 0, 0, 0, 0, 0, 0})))) {
                var builder = new HeifBuilder();
                builder.primary(builder.item("av01", new byte[1], property));
                var data = builder.build();
                assertThrows(MediaException.Malformed.class, () -> parse(data));
            }
        }

        @Test
        @DisplayName("rejects two dimg boxes from one item and a repeated input")
        void derivedReferences() {
            var builder = new HeifBuilder();
            var tile = builder.item("av01", new byte[1]);
            var grid = builder.item("grid", HeifBuilder.grid(1, 2, 128, 64));
            builder.reference("dimg", grid, tile, tile).primary(grid);
            var repeated = builder.build();
            assertThrows(MediaException.Malformed.class, () -> parse(repeated));
            var twice = new HeifBuilder();
            var t = twice.item("av01", new byte[1]);
            var g = twice.item("grid", HeifBuilder.grid(1, 1, 64, 64));
            twice.reference("dimg", g, t).reference("dimg", g, t).primary(g);
            var data = twice.build();
            assertThrows(MediaException.Malformed.class, () -> parse(data));
        }

        @Test
        @DisplayName("rejects meta boxes above the byte limit")
        void metaLimit() {
            var data = simple();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> HeifFile.parse(MediaSource.of(data), MediaLimits.builder().maxBytes(64).build()));
        }

        @Test
        @DisplayName("raises only MediaException on every truncation and on random corruption")
        void hostile() {
            var data = simple();
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                for (var length = 0; length < data.length; length++) {
                    var truncated = Arrays.copyOf(data, length);
                    try {
                        var file = parse(truncated);
                        for (var item : file.items()) {
                            item.properties();
                        }
                    } catch (MediaException expected) {
                        // truncation may fail, but only with a MediaException
                    }
                }
                var random = new Random(7);
                for (var round = 0; round < 3000; round++) {
                    var mutated = data.clone();
                    for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                        mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                    }
                    try {
                        var file = parse(mutated);
                        for (var item : file.items()) {
                            if (item.isLocated()) {
                                file.readItem(MediaSource.of(mutated), item, MediaLimits.DEFAULT);
                            }
                        }
                    } catch (MediaException expected) {
                        // corruption may fail, but only with a MediaException
                    }
                }
            });
        }
    }

    @Nested
    @DisplayName("image grids")
    class Grids {
        @Test
        @DisplayName("parses 16- and 32-bit grids and rejects other versions, trailing bytes and empty outputs")
        void parse() {
            assertEquals(new HeifGrid(2, 3, 300, 100), HeifGrid.parse(HeifBuilder.grid(2, 3, 300, 100)));
            assertEquals(new HeifGrid(256, 256, 70000, 5), HeifGrid.parse(HeifBuilder.grid(256, 256, 70000, 5)));
            assertThrows(MediaException.Unsupported.class, () -> HeifGrid.parse(new byte[]{1, 0, 0, 0, 0, 1, 0, 1}));
            assertThrows(MediaException.Malformed.class, () -> HeifGrid.parse(new byte[]{0, 0, 0, 0, 0, 1, 0, 1, 9}));
            assertThrows(MediaException.Malformed.class, () -> HeifGrid.parse(new byte[]{0, 0, 0, 0, 0, 0, 0, 1}));
            assertThrows(MediaException.Malformed.class, () -> HeifGrid.parse(new byte[]{0, 0, 0}));
        }

        @Test
        @DisplayName("validates coverage, overlap, the 64-sample minimum and chroma alignment")
        void validate() {
            var grid = new HeifGrid(2, 2, 200, 120);
            grid.validate(100, 64, true, true);
            assertThrows(MediaException.Malformed.class, () -> grid.validate(99, 64, false, false));
            assertThrows(MediaException.Malformed.class, () -> grid.validate(200, 64, false, false));
            assertThrows(MediaException.Malformed.class, () -> new HeifGrid(1, 1, 40, 40).validate(40, 40, false, false));
            assertThrows(MediaException.Malformed.class, () -> new HeifGrid(1, 2, 201, 64).validate(101, 64, true, false));
        }
    }

    @Nested
    @DisplayName("transformative properties")
    class Geometry {
        @ParameterizedTest(name = "{0} then {1}")
        @DisplayName("compose like matrices")
        @CsvSource({
                "ROTATE_90, NORMAL, ROTATE_90",
                "ROTATE_90, ROTATE_90, ROTATE_180",
                "ROTATE_270, ROTATE_90, NORMAL",
                "FLIP_HORIZONTAL, FLIP_VERTICAL, ROTATE_180",
                "ROTATE_270, FLIP_VERTICAL, TRANSPOSE",
                "FLIP_VERTICAL, ROTATE_270, TRANSVERSE",
                "FLIP_HORIZONTAL, ROTATE_90, TRANSVERSE",
                "ROTATE_90, FLIP_HORIZONTAL, TRANSPOSE",
                "FLIP_HORIZONTAL, ROTATE_270, TRANSPOSE",
        })
        void compose(Orientation first, Orientation second, Orientation expected) {
            assertEquals(expected, HeifGeometry.compose(second, first));
        }

        @Test
        @DisplayName("maps irot anticlockwise and imir axis 0 to a top-bottom swap")
        void properties() {
            assertEquals(Orientation.ROTATE_270, HeifGeometry.resolve(List.of(new HeifProperty.Rotation(1)), 4, 2).orientation());
            assertEquals(Orientation.ROTATE_90, HeifGeometry.resolve(List.of(new HeifProperty.Rotation(3)), 4, 2).orientation());
            assertEquals(Orientation.FLIP_VERTICAL, HeifGeometry.resolve(List.of(new HeifProperty.Mirror(0)), 4, 2).orientation());
            assertEquals(Orientation.FLIP_HORIZONTAL, HeifGeometry.resolve(List.of(new HeifProperty.Mirror(1)), 4, 2).orientation());
        }

        @Test
        @DisplayName("computes the clean aperture of the Link-U crop files exactly")
        void cleanAperture() {
            var crop = new HeifProperty.CleanAperture(385, 1, 330, 1, 207, 2, -616, 2);
            var geometry = HeifGeometry.resolve(List.of(crop), 722, 1024);
            assertEquals(new HeifGeometry(272, 39, 385, 330, Orientation.NORMAL), geometry);
            var rotated = new HeifProperty.CleanAperture(330, 1, 385, 1, -616, 2, 207, 2);
            var then = HeifGeometry.resolve(List.of(rotated, new HeifProperty.Rotation(1), new HeifProperty.Mirror(0)), 1024, 722);
            assertEquals(new HeifGeometry(39, 272, 330, 385, Orientation.TRANSPOSE), then);
        }

        @Test
        @DisplayName("maps a crop that follows a rotation back onto the stored image")
        void cropAfterRotation() {
            // A 4x2 image rotated a quarter turn anticlockwise shows 2x4; keeping its top half keeps
            // the right half of the stored image.
            var geometry = HeifGeometry.resolve(List.of(new HeifProperty.Rotation(1),
                    new HeifProperty.CleanAperture(2, 1, 2, 1, 0, 1, -1, 1)), 4, 2);
            assertEquals(new HeifGeometry(2, 0, 2, 2, Orientation.ROTATE_270), geometry);
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("ignores clean apertures that are not whole samples or leave the image")
        @CsvSource({
                "fractional width, 3, 2, 2, 1, 0, 1, 0, 1",
                "fractional edge, 3, 1, 2, 1, 0, 1, 0, 1",
                "outside, 4, 1, 2, 1, 1, 1, 0, 1",
                "zero denominator, 2, 0, 2, 1, 0, 1, 0, 1",
                "negative width, -2, 1, 2, 1, 0, 1, 0, 1",
                "empty, 0, 1, 2, 1, 0, 1, 0, 1",
        })
        void invalidAperture(String name, int wn, int wd, int hn, int hd, int xn, int xd, int yn, int yd) {
            var geometry = HeifGeometry.resolve(List.of(new HeifProperty.CleanAperture(wn, wd, hn, hd, xn, xd, yn, yd)), 4, 2);
            assertFalse(geometry.crops(4, 2), name);
        }
    }

    @Test
    @DisplayName("finds the TIFF header of an Exif item through its offset or by searching")
    void exif() {
        assertArrayEquals(new byte[]{'I', 'I', 42, 0, 1}, HeifFile.exifTiff(new byte[]{0, 0, 0, 2, 'E', 'x', 'I', 'I', 42, 0, 1}));
        assertArrayEquals(new byte[]{'M', 'M', 0, 42, 7}, HeifFile.exifTiff(new byte[]{0, 0, 0, 9, 'x', 'M', 'M', 0, 42, 7}));
        assertNull(HeifFile.exifTiff(new byte[]{0, 0, 0, 0, 1, 2, 3, 4}));
        assertNull(HeifFile.exifTiff(new byte[]{0, 0}));
    }

    @Nested
    @DisplayName("image sequences")
    class Movies {
        private static byte[] trak(int id, String handler, long duration, byte[] edits, int... auxl) {
            var tkhd = new ByteArrayOutputStream();
            tkhd.writeBytes(new byte[8]);
            HeifBuilder.u32(tkhd, id);
            HeifBuilder.u32(tkhd, 0);
            HeifBuilder.u32(tkhd, duration);
            tkhd.writeBytes(new byte[52]);
            HeifBuilder.u32(tkhd, 64L << 16);
            HeifBuilder.u32(tkhd, 48L << 16);
            var stsd = new ByteArrayOutputStream();
            HeifBuilder.u32(stsd, 1);
            stsd.writeBytes(box("av01", concat(new byte[78], HeifBuilder.av1C(AV1C))));
            var stsz = new ByteArrayOutputStream();
            HeifBuilder.u32(stsz, 0);
            HeifBuilder.u32(stsz, 5);
            var stbl = box("stbl", concat(fullBox("stsd", 0, 0, stsd.toByteArray()), fullBox("stsz", 0, 0, stsz.toByteArray())));
            var mdia = box("mdia", concat(HeifBuilder.hdlr(handler), box("minf", stbl)));
            var children = concat(fullBox("tkhd", 0, 0, tkhd.toByteArray()), mdia);
            if (edits != null) {
                children = concat(children, box("edts", edits));
            }
            if (auxl.length > 0) {
                var refs = new ByteArrayOutputStream();
                for (var target : auxl) {
                    HeifBuilder.u32(refs, target);
                }
                children = concat(children, box("tref", box("auxl", refs.toByteArray())));
            }
            return box("trak", children);
        }

        private static byte[] elst(int flags, long segment) {
            var out = new ByteArrayOutputStream();
            HeifBuilder.u32(out, 1);
            HeifBuilder.u32(out, segment);
            HeifBuilder.u32(out, 0);
            HeifBuilder.u32(out, 1 << 16);
            return fullBox("elst", 0, flags, out.toByteArray());
        }

        private static byte[] concat(byte[]... parts) {
            var out = new ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }

        private static HeifMovie movie(byte[]... tracks) {
            var data = new HeifBuilder().brands("avis", "avis", "msf1")
                    .topLevel(box("moov", concat(tracks))).build();
            var source = MediaSource.of(data);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            return HeifMovie.parse(source, file, MediaLimits.DEFAULT);
        }

        @Test
        @DisplayName("reads track identifiers, handlers, references and sample counts")
        void tracks() {
            var movie = movie(trak(1, "pict", 100, null), trak(2, "auxv", 100, null, 1));
            assertEquals(2, movie.tracks().size());
            var color = movie.tracks().getFirst();
            assertEquals(1, color.id());
            assertEquals("pict", color.handler());
            assertEquals("av01", color.sampleEntry());
            assertEquals(5, color.sampleCount());
            assertEquals(64, color.width());
            assertNotNull(color.property(HeifProperty.Av1Configuration.class));
            assertTrue(movie.tracks().get(1).references("auxl", 1));
        }

        @ParameterizedTest(name = "{0}")
        @DisplayName("derives the loop count from the edit list")
        @CsvSource({
                "no edit list, -1, 0, 100, 0",
                "single play, 0, 100, 100, 1",
                "repeated three times, 1, 100, 300, 3",
                "partial repetition, 1, 100, 250, 3",
                "indefinite, 1, 100, 4294967295, 0",
        })
        void loops(String name, int flags, long segment, long duration, int plays) {
            var edits = flags < 0 ? null : elst(flags, segment);
            assertEquals(plays, movie(trak(1, "pict", duration, edits)).tracks().getFirst().loopCount(), name);
        }

        @Test
        @DisplayName("rejects sample counts above the frame limit")
        void frameLimit() {
            var data = new HeifBuilder().brands("avis", "avis").topLevel(box("moov", trak(1, "pict", 1, null))).build();
            var source = MediaSource.of(data);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            assertInstanceOf(MediaException.LimitExceeded.class, assertThrows(MediaException.class,
                    () -> HeifMovie.parse(source, file, MediaLimits.builder().maxFrames(4).build())));
        }
    }

    @Nested
    @DisplayName("entity groups")
    class EntityGroups {
        private static byte[] grouped(byte[]... groups) {
            var builder = new HeifBuilder();
            var primary = builder.item("av01", new byte[]{1}, plain(HeifBuilder.ispe(8, 8)),
                    essential(HeifBuilder.av1C(AV1C)));
            builder.item("sato", HeifBuilder.sato(2, 1), plain(HeifBuilder.ispe(8, 8)));
            builder.item("av01", new byte[]{2}, plain(HeifBuilder.ispe(8, 8)), essential(HeifBuilder.av1C(AV1C)));
            builder.reference("dimg", 2, 1).primary(primary);
            if (groups.length > 0) {
                builder.metaBox(HeifBuilder.grpl(groups));
            }
            return builder.build();
        }

        @Test
        @DisplayName("lists the groups and the alternatives preferred to an item")
        void alternatives() {
            var file = parse(grouped(HeifBuilder.entityGroup("ster", 7, 1, 3),
                    HeifBuilder.entityGroup("altr", 8, 2, 3, 1)));
            assertEquals(2, file.entityGroups().size());
            assertEquals("ster", file.entityGroups().getFirst().type());
            assertEquals(8, file.entityGroups().get(1).id());
            assertArrayEquals(new int[]{2, 3, 1}, file.entityGroups().get(1).entities());
            assertEquals(List.of(2, 3), file.preferredAlternatives(1).stream().map(HeifItem::id).toList());
            assertEquals(List.of(), file.preferredAlternatives(2));
            assertEquals(List.of(), parse(grouped()).preferredAlternatives(1));
            assertEquals(List.of(), parse(grouped(HeifBuilder.entityGroup("ster", 7, 2, 1))).preferredAlternatives(1));
        }

        @Test
        @DisplayName("skips the group-specific fields after the entity identifiers")
        void groupFields() {
            var group = HeifBuilder.entityGroup("altr", 1, 2, 1);
            var extended = Arrays.copyOf(group, group.length + 4);
            extended[3] += 4;
            assertEquals(List.of(2), parse(grouped(extended)).preferredAlternatives(1).stream().map(HeifItem::id)
                    .toList());
        }

        @Test
        @DisplayName("rejects truncated groups, repeated group identifiers, repeated entities and two grpl boxes")
        void malformed() {
            var group = HeifBuilder.entityGroup("altr", 1, 2, 1);
            var truncated = Arrays.copyOf(group, group.length - 4);
            truncated[3] -= 4;
            assertThrows(MediaException.Malformed.class, () -> parse(grouped(truncated)));
            assertThrows(MediaException.Malformed.class, () -> parse(grouped(HeifBuilder.entityGroup("altr", 1, 2, 1),
                    HeifBuilder.entityGroup("ster", 1, 3))));
            assertThrows(MediaException.Malformed.class, () -> parse(grouped(HeifBuilder.entityGroup("altr", 1, 2, 2))));
            var builder = new HeifBuilder();
            builder.primary(builder.item("av01", new byte[]{1}, plain(HeifBuilder.ispe(8, 8)),
                    essential(HeifBuilder.av1C(AV1C))));
            builder.metaBox(HeifBuilder.grpl()).metaBox(HeifBuilder.grpl());
            assertThrows(MediaException.Malformed.class, () -> parse(builder.build()));
        }

        private static HeifFile lenient(byte[] data) {
            return HeifFile.parse(MediaSource.of(data), MediaLimits.DEFAULT, false);
        }

        private static List<Integer> preferred(HeifFile file) {
            return file.preferredAlternatives(1).stream().map(HeifItem::id).toList();
        }

        @Test
        @DisplayName("a lenient parse keeps the groups before a truncated one and accepts repeated identifiers")
        void lenient() {
            var altr = HeifBuilder.entityGroup("altr", 1, 2, 1);
            var truncated = HeifBuilder.entityGroup("ster", 9, 3, 1);
            truncated = Arrays.copyOf(truncated, truncated.length - 4);
            truncated[3] -= 4;
            var kept = lenient(grouped(altr, truncated));
            assertEquals(1, kept.entityGroups().size());
            assertEquals(List.of(2), preferred(kept));
            assertEquals(List.of(), lenient(grouped(truncated, altr)).entityGroups());
            var repeated = lenient(grouped(HeifBuilder.entityGroup("ster", 1, 3, 3),
                    HeifBuilder.entityGroup("altr", 1, 2, 2, 1)));
            assertEquals(2, repeated.entityGroups().size());
            assertArrayEquals(new int[]{2, 2, 1}, repeated.entityGroups().get(1).entities());
            assertEquals(List.of(2, 2), preferred(repeated));
            var builder = new HeifBuilder();
            var primary = builder.item("av01", new byte[]{1}, plain(HeifBuilder.ispe(8, 8)),
                    essential(HeifBuilder.av1C(AV1C)));
            builder.item("av01", new byte[]{2}, plain(HeifBuilder.ispe(8, 8)), essential(HeifBuilder.av1C(AV1C)));
            builder.primary(primary).metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 5, 2, 1)))
                    .metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 6, 1, 2)));
            assertEquals(List.of(2), preferred(lenient(builder.build())));
        }
    }
}
