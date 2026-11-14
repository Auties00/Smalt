package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HEIF writer shared by the AVIF and HEIC encoders: the files it writes parse back with the strict HEIF
 * parser into the items, properties, references and groups that were added, its transformative properties resolve
 * to the geometry they describe, and the tracks of {@link HeifTrackWriter} demux to the samples, times, sync flags
 * and loop count they were given.
 */
@DisplayName("HEIF writer")
class HeifWriterTest {
    private static HeifFile parse(byte[] file) {
        return HeifFile.parse(MediaSource.of(file), MediaLimits.DEFAULT, true);
    }

    private static byte[] bytes(int length, int seed) {
        var out = new byte[length];
        for (var i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }

    @Nested
    @DisplayName("items")
    class Items {
        @Test
        @DisplayName("items, their data and their shared properties parse back as written")
        void items() {
            var writer = new HeifWriter("test", "heic", List.of("mif1", "heic"));
            var first = bytes(100, 1);
            var second = bytes(50, 2);
            var a = writer.addItem("hvc1", first, false, HeifWriter.ispe(64, 48), HeifWriter.pixi(8, 3),
                    HeifWriter.configuration("hvcC", bytes(23, 3)));
            var b = writer.addItem("hvc1", second, true, HeifWriter.ispe(64, 48), HeifWriter.pixi(8, 3),
                    HeifWriter.configuration("hvcC", bytes(23, 3)));
            var grid = writer.addIdatItem("grid", HeifWriter.grid(1, 2, 128, 48), HeifWriter.ispe(128, 48));
            writer.reference("dimg", grid, a, b);
            writer.primary(grid);
            var bytes = writer.write();
            var file = parse(bytes);
            assertEquals("heic", file.majorBrand());
            assertEquals(List.of("heic", "mif1", "heic"), file.brands(), "the major brand, then the compatible ones");
            assertEquals(grid, file.primaryItem().id());
            assertArrayEquals(new int[]{a, b}, file.item(grid).references("dimg"));
            var source = MediaSource.of(bytes);
            assertArrayEquals(first, file.readItem(source, file.item(a), MediaLimits.DEFAULT));
            assertArrayEquals(second, file.readItem(source, file.item(b), MediaLimits.DEFAULT));
            var layout = HeifGrid.parse(file.readItem(source, file.item(grid), MediaLimits.DEFAULT));
            assertEquals(new HeifGrid(1, 2, 128, 48), layout);
            assertFalse(file.item(a).isHidden());
            assertTrue(file.item(b).isHidden());
            assertEquals(3, file.item(a).properties().size());
            assertArrayEquals(bytes(23, 3), file.item(b).property(HeifProperty.HevcConfiguration.class).record());
        }

        @Test
        @DisplayName("an identical property is stored once and shared, which keeps the narrow association form")
        void shared() {
            var writer = new HeifWriter("test", "heic", List.of("mif1"));
            var ids = new ArrayList<Integer>();
            for (var i = 0; i < 200; i++) {
                ids.add(writer.addItem("hvc1", bytes(4, i), true, HeifWriter.ispe(16, 16), HeifWriter.pixi(8, 3)));
            }
            writer.primary(ids.getFirst());
            var bytes = writer.write();
            var ipco = indexOf(bytes, "ipco");
            var ipma = indexOf(bytes, "ipma");
            assertTrue(ipma - ipco < 80, "two distinct properties only");
            assertEquals(0, bytes[ipma + 11], "narrow ipma flags");
            assertEquals(200, parse(bytes).items().size());
        }

        @Test
        @DisplayName("more than 127 distinct properties use the wide association form")
        void wide() {
            var writer = new HeifWriter("test", "heic", List.of("mif1"));
            for (var i = 0; i < 130; i++) {
                writer.addItem("hvc1", bytes(4, i), true, HeifWriter.ispe(16 + 2 * i, 16));
            }
            var last = writer.addItem("hvc1", bytes(4, 0), false, HeifWriter.ispe(16 + 2 * 129, 16));
            writer.primary(last);
            var bytes = writer.write();
            assertEquals(1, bytes[indexOf(bytes, "ipma") + 11], "wide ipma flags");
            var file = parse(bytes);
            assertEquals(16 + 2 * 129, file.primaryItem().property(HeifProperty.SpatialExtent.class).width());
        }

        @Test
        @DisplayName("the 65535th item is the last one the 16-bit identifiers number, and a reference names at most 65535 items")
        void itemLimit() {
            var writer = new HeifWriter("test", "heic", List.of("mif1"));
            var data = new byte[1];
            var extent = HeifWriter.ispe(16, 16);
            for (var i = 1; i < HeifWriter.MAX_ITEMS; i++) {
                writer.addItem("hvc1", data, true, extent);
            }
            var last = writer.addItem("hvc1", data, false, extent);
            assertEquals(0xFFFF, last);
            assertThrows(MediaException.LimitExceeded.class, () -> writer.addItem("hvc1", data, true));
            assertThrows(MediaException.LimitExceeded.class, () -> writer.addIdatItem("grid", data));
            assertThrows(MediaException.LimitExceeded.class, () -> writer.addMimeItem("text/plain", data));
            assertThrows(MediaException.LimitExceeded.class, () -> writer.addSampleItem("hvc1", 0, 1));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> writer.reference("dimg", 1, new int[HeifWriter.MAX_ITEMS + 1]));
            writer.primary(last);
            var file = parse(writer.write());
            assertEquals(HeifWriter.MAX_ITEMS, file.items().size());
            assertEquals(0xFFFF, file.primaryItem().id());
        }

        @Test
        @DisplayName("Exif and XMP become cdsc items of the described item, Exif with the offset of its TIFF header")
        void metadata() {
            var writer = new HeifWriter("test", "heic", List.of("mif1"));
            var image = writer.addItem("hvc1", bytes(8, 1), false, HeifWriter.ispe(8, 8));
            writer.primary(image);
            var exif = new byte[]{'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0};
            var xmp = "<x:xmpmeta/>".getBytes(StandardCharsets.UTF_8);
            writer.addMetadata(image, exif, xmp);
            var bytes = writer.write();
            var file = parse(bytes);
            var descriptions = file.descriptionsOf(image);
            assertEquals(2, descriptions.size());
            var exifItem = descriptions.stream().filter(item -> item.type().equals("Exif")).findFirst().orElseThrow();
            var payload = file.readItem(MediaSource.of(bytes), exifItem, MediaLimits.DEFAULT);
            assertArrayEquals(new byte[]{0, 0, 0, 6}, Arrays.copyOf(payload, 4), "the TIFF header is at offset 6");
            assertArrayEquals(exif, Arrays.copyOfRange(payload, 4, payload.length));
            var xmpItem = descriptions.stream().filter(item -> item.type().equals("mime")).findFirst().orElseThrow();
            assertEquals(HeifFile.XMP_TYPE, xmpItem.contentType());
            assertArrayEquals(xmp, file.readItem(MediaSource.of(bytes), xmpItem, MediaLimits.DEFAULT));
            assertArrayEquals(xmp, file.xmpOf(MediaSource.of(bytes), file.item(image), MediaLimits.DEFAULT));
            assertArrayEquals(new byte[]{0, 0, 0, 0, 1, 2}, HeifWriter.exifPayload(new byte[]{1, 2}),
                    "no TIFF header: offset 0");
        }

        @Test
        @DisplayName("an alternatives group is written with an identifier above every item's")
        void alternatives() {
            var writer = new HeifWriter("test", "heic", List.of("mif1"));
            var a = writer.addItem("hvc1", bytes(8, 1), false, HeifWriter.ispe(8, 8));
            var b = writer.addItem("hvc1", bytes(8, 2), false, HeifWriter.ispe(8, 8));
            writer.alternatives(b, a);
            writer.primary(a);
            var groups = parse(writer.write()).entityGroups();
            assertEquals(1, groups.size());
            assertEquals("altr", groups.getFirst().type());
            assertEquals(3, groups.getFirst().id());
            assertArrayEquals(new int[]{b, a}, groups.getFirst().entities());
        }
    }

    @Nested
    @DisplayName("properties")
    class Properties {
        @ParameterizedTest(name = "{0}x{1} in {2}x{3}")
        @CsvSource({"33, 17, 34, 18", "1, 1, 2, 2", "64, 47, 64, 48", "100, 100, 108, 104", "7, 64, 8, 64"})
        @DisplayName("a clean aperture keeps the top left of the image and is essential")
        void clap(int width, int height, int imageWidth, int imageHeight) {
            var writer = new HeifWriter("test", "heic", List.of("mif1"));
            var image = writer.addItem("hvc1", bytes(8, 1), false, HeifWriter.ispe(imageWidth, imageHeight),
                    HeifWriter.clap(width, height, imageWidth, imageHeight));
            writer.primary(image);
            var item = parse(writer.write()).primaryItem();
            assertNotNull(item.property(HeifProperty.CleanAperture.class));
            assertEquals("clap", item.unsupportedEssential(java.util.Set.of()));
            assertEquals(new HeifGeometry(0, 0, width, height, Orientation.NORMAL),
                    HeifGeometry.resolve(item.properties(), imageWidth, imageHeight));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Orientation.class)
        @DisplayName("the rotation and mirroring of an orientation resolve back to that orientation")
        void orientation(Orientation orientation) {
            var writer = new HeifWriter("test", "heic", List.of("mif1"));
            var properties = new ArrayList<HeifWriter.Property>();
            properties.add(HeifWriter.ispe(16, 8));
            properties.addAll(HeifWriter.orientation(orientation));
            var image = writer.addItem("hvc1", bytes(8, 1), false, properties.toArray(HeifWriter.Property[]::new));
            writer.primary(image);
            var item = parse(writer.write()).primaryItem();
            assertEquals(orientation, HeifGeometry.resolve(item.properties(), 16, 8).orientation());
        }

        @Test
        @DisplayName("the color properties carry the CICP description, the ICC profile and the HDR metadata")
        void color() {
            var mastering = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.170, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.3290), 1000, 0.005);
            var color = ColorInfo.BT709_LIMITED.withMasteringDisplay(mastering)
                    .withContentLightLevel(new ContentLightLevel(1000, 400));
            var profile = ColorProfile.displayP3();
            var properties = HeifWriter.colorProperties(color, profile);
            assertEquals(4, properties.size());
            var writer = new HeifWriter("test", "heic", List.of("mif1"));
            var image = writer.addItem("hvc1", bytes(8, 1), false, properties.toArray(HeifWriter.Property[]::new));
            writer.primary(image);
            var item = parse(writer.write()).primaryItem();
            var nclx = item.property(HeifProperty.ColorNclx.class);
            assertEquals(new HeifProperty.ColorNclx(1, 1, 1, false), nclx);
            assertArrayEquals(profile.toByteArray(), item.property(HeifProperty.ColorIcc.class).profile());
            var clli = item.property(HeifProperty.ContentLightLevel.class);
            assertEquals(1000, clli.maxContentLightLevel());
            assertEquals(400, clli.maxPictureAverageLightLevel());
            var mdcv = item.property(HeifProperty.MasteringDisplay.class);
            assertEquals(10_000_000L, mdcv.maxLuminance());
            assertEquals(50, mdcv.minLuminance());
            assertEquals(15635, mdcv.whitePointX());
            assertEquals(1, HeifWriter.colorProperties(ColorInfo.JPEG, null).size());
        }
    }

    @Nested
    @DisplayName("image sequence tracks")
    class Tracks {
        @ParameterizedTest(name = "{0} plays")
        @CsvSource({"0", "1", "3"})
        @DisplayName("reordered samples demux in decoding order with their presentation times, sync flags and loops")
        void reordered(int loops) {
            // decoding order I0 P3 B1 B2 P6 B4 B5 with 1000-unit frames; decoding times run one frame behind
            long[] pts = {0, 3000, 1000, 2000, 6000, 4000, 5000};
            long[] dts = {-1000, 0, 1000, 2000, 3000, 4000, 5000};
            var sizes = new int[pts.length];
            var deltas = new long[pts.length];
            var offsets = new int[pts.length];
            var sync = new boolean[pts.length];
            var samples = new java.io.ByteArrayOutputStream();
            for (var i = 0; i < pts.length; i++) {
                var data = bytes(10 + i, i);
                samples.writeBytes(data);
                sizes[i] = data.length;
                offsets[i] = (int) (pts[i] - dts[i]);
                deltas[i] = i + 1 < pts.length ? dts[i + 1] - dts[i] : 1000 + 7000 - (dts[i] - dts[0]);
                sync[i] = i == 0;
            }
            var track = new HeifTrackWriter.Track(1, 0, 0, "pict", 0, sizes, deltas, offsets, sync, 1000,
                    out -> HeifTrackWriter.visualSampleEntry(out, "hvc1", 32, 16, entry -> {
                        HeifWriter.box(entry, "hvcC", bytes(23, 7));
                        HeifTrackWriter.ccst(entry, false, true, 15);
                    }));
            var writer = new HeifWriter("test", "msf1", List.of("msf1", "mif1"));
            var item = writer.addSampleItem("hvc1", 0, sizes[0], HeifWriter.ispe(32, 16));
            writer.primary(item);
            var data = samples.toByteArray();
            writer.movie(data, base -> HeifTrackWriter.moov(base, 32, 16, 1_000_000, 7000, loops, List.of(track)));
            var bytes = writer.write();
            var file = parse(bytes);
            assertArrayEquals(bytes(10, 0), file.readItem(MediaSource.of(bytes), file.primaryItem(),
                    MediaLimits.DEFAULT), "the primary item is the first sample");
            var movie = HeifMovie.parse(MediaSource.of(bytes), file, MediaLimits.DEFAULT);
            var parsed = movie.tracks().getFirst();
            assertEquals("pict", parsed.handler());
            assertEquals("hvc1", parsed.sampleEntry());
            assertEquals(7, parsed.sampleCount());
            assertEquals(loops, parsed.loopCount());
            assertInstanceOf(HeifProperty.HevcConfiguration.class, parsed.sampleProperties().getFirst());
            try (var demuxer = new Mp4Demuxer(MediaSource.of(bytes))) {
                var times = new ArrayList<Long>();
                var index = 0;
                for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    assertArrayEquals(bytes(10 + index, index), packet.data(), "sample " + index);
                    assertEquals(index == 0, packet.keyframe(), "sync flag of sample " + index);
                    assertTrue(packet.dts() <= packet.pts(), "sample " + index + " decodes before it is shown");
                    times.add(packet.pts());
                    index++;
                }
                assertEquals(7, index);
                var first = times.stream().mapToLong(Long::longValue).min().orElseThrow();
                for (var i = 0; i < pts.length; i++) {
                    assertEquals(pts[i], times.get(i) - first, "presentation time of sample " + i);
                }
            }
        }

        @Test
        @DisplayName("an auxiliary track refers to the color track with its auxi type; premultiplied color refers back")
        void auxiliary() {
            int[] sizes = {4, 4};
            long[] deltas = {500, 500};
            var color = new HeifTrackWriter.Track(1, 0, 2, "pict", 0, sizes, deltas, null, null, 0,
                    out -> HeifTrackWriter.visualSampleEntry(out, "hvc1", 8, 8,
                            entry -> HeifWriter.box(entry, "hvcC", bytes(23, 1))));
            var alpha = new HeifTrackWriter.Track(2, 1, 0, "auxv", 8, sizes, deltas, null, null, 0,
                    out -> HeifTrackWriter.visualSampleEntry(out, "hvc1", 8, 8, entry -> {
                        HeifWriter.box(entry, "hvcC", bytes(23, 2));
                        HeifTrackWriter.ccst(entry, true, true, 0);
                        HeifTrackWriter.auxi(entry, HeifProperty.AuxiliaryType.HEVC_ALPHA_URN);
                    }));
            var writer = new HeifWriter("test", "msf1", List.of("msf1"));
            writer.primary(writer.addSampleItem("hvc1", 0, 4, HeifWriter.ispe(8, 8)));
            writer.movie(bytes(16, 3), base -> HeifTrackWriter.moov(base, 8, 8, 1000, 1000, 0, List.of(color, alpha)));
            var bytes = writer.write();
            var tracks = HeifMovie.parse(MediaSource.of(bytes), parse(bytes), MediaLimits.DEFAULT).tracks();
            assertEquals(2, tracks.size());
            var parsed = tracks.get(1);
            assertEquals("auxv", parsed.handler());
            assertTrue(parsed.references("auxl", 1));
            assertTrue(tracks.get(0).references("prem", 2), "the color track is premultiplied by the alpha track");
            assertFalse(parsed.references("prem", 1));
            assertEquals(HeifProperty.AuxiliaryType.HEVC_ALPHA_URN, parsed.property(HeifProperty.AuxiliaryType.class)
                    .urn());
        }
    }

    private static int indexOf(byte[] data, String type) {
        var pattern = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (var i = 4; i + 4 <= data.length; i++) {
            for (var j = 0; j < 4; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i - 4;
        }
        throw new AssertionError("no " + type + " box");
    }
}
