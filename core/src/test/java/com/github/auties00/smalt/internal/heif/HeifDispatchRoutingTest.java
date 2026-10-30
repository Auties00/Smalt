package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PageGroup;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.HeifSequenceBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how HEIF files and items are routed to the decoder of their coding rather than of their brands, the
 * hidden flag of items and the page group model, on files built box by box with dummy payloads (no decoder runs
 * here: core has none).
 */
@DisplayName("HEIF dispatch by item coding")
class HeifDispatchRoutingTest {
    private static final byte[] AV1C = {(byte) 0x81, 0x00, 0x0C, 0x00};

    private static int coded(HeifBuilder builder, String type) {
        var configuration = switch (type) {
            case "av01" -> HeifBuilder.av1C(AV1C);
            case "avc1" -> HeifBuilder.box("avcC", new byte[]{1, 66, 0, 30, (byte) 0xFF, (byte) 0xE0, 0});
            default -> HeifBuilder.box("hvcC", new byte[23]);
        };
        return builder.item(type, new byte[8], plain(HeifBuilder.ispe(64, 48)), essential(configuration));
    }

    private static ImageFormat detect(byte[] data) {
        return Images.detect(MediaSource.of(data)).orElseThrow();
    }

    private static HeifFile parse(byte[] data) {
        return HeifFile.parse(MediaSource.of(data), MediaLimits.DEFAULT, false);
    }

    @Nested
    @DisplayName("files")
    class Files {
        @ParameterizedTest(name = "{0} brands, {1} primary item: {2}")
        @CsvSource({
                "mif1, av01, AVIF", "miaf, av01, AVIF", "heic, av01, AVIF", "avif, hvc1, HEIC", "avif, avc1, HEIC",
                "mif1, hvc1, HEIC", "avif, av01, AVIF", "heic, hvc1, HEIC", "mif1, xyz1, HEIC", "avif, xyz1, AVIF"
        })
        @DisplayName("are recognized by the coding of their primary item, the brands deciding only an unknown coding")
        void primary(String brand, String type, ImageFormat expected) {
            var builder = new HeifBuilder().brands(brand, brand, "mif1");
            builder.primary(coded(builder, type));
            var data = builder.build();
            assertEquals(brand.equals("avif") ? ImageFormat.AVIF : ImageFormat.HEIC,
                    ImageFormat.detect(data, data.length).orElseThrow(), "the brands alone");
            assertEquals(expected, detect(data));
        }

        @Test
        @DisplayName("are probed as AVIF when their image is AV1-coded under the generic brands only")
        void probe() {
            var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
            builder.primary(coded(builder, "av01"));
            var image = Media.probe(MediaSource.of(builder.build())).image().orElseThrow();
            assertEquals(ImageFormat.AVIF, image.format());
            assertEquals(64, image.width());
        }

        @Test
        @DisplayName("follow the first input of derived images down to a coded item")
        void derived() {
            var builder = new HeifBuilder().brands("mif1", "mif1");
            var tiles = new int[4];
            for (var i = 0; i < tiles.length; i++) {
                tiles[i] = coded(builder, "av01");
                builder.hidden(tiles[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 128, 96), plain(HeifBuilder.ispe(128, 96)));
            builder.reference("dimg", grid, tiles);
            var identity = builder.idatItem("iden", new byte[0], plain(HeifBuilder.ispe(128, 96)));
            builder.reference("dimg", identity, grid);
            builder.primary(identity);
            var data = builder.build();
            assertEquals(ImageFormat.AVIF, detect(data));
            var file = parse(data);
            assertEquals(ImageFormat.AVIF, HeifImageCodec.formatOf(file, file.item(identity)));
            assertEquals(ImageFormat.AVIF, HeifImageCodec.formatOf(file, file.item(grid)));
        }

        @Test
        @DisplayName("give a derivation cycle and an input-less derivation no coding, so the brands decide")
        void cycles() {
            var builder = new HeifBuilder().brands("mif1", "mif1");
            var first = builder.idatItem("iden", new byte[0], plain(HeifBuilder.ispe(8, 8)));
            var second = builder.idatItem("iden", new byte[0], plain(HeifBuilder.ispe(8, 8)));
            builder.reference("dimg", first, second).reference("dimg", second, first);
            var empty = builder.idatItem("grid", HeifBuilder.grid(1, 1, 8, 8), plain(HeifBuilder.ispe(8, 8)));
            builder.primary(first);
            var data = builder.build();
            var file = parse(data);
            assertNull(HeifImageCodec.formatOf(file, file.item(first)));
            assertNull(HeifImageCodec.formatOf(file, file.item(empty)));
            assertEquals(ImageFormat.HEIC, detect(data));
        }

        @Test
        @DisplayName("without a primary item take the coding of the color track of their sequence")
        void sequences() {
            var av1 = new HeifSequenceBuilder("msf1", 1000, "msf1", "iso8");
            av1.track("av01", 64, 48, 100, List.of(new byte[8]), HeifBuilder.av1C(AV1C));
            var data = av1.build();
            assertEquals(ImageFormat.HEIC, ImageFormat.detect(data, data.length).orElseThrow());
            assertEquals(ImageFormat.AVIF, detect(data));
            var hevc = new HeifSequenceBuilder("msf1", 1000, "msf1", "iso8");
            hevc.track("hvc1", 64, 48, 100, List.of(new byte[8]), HeifBuilder.box("hvcC", new byte[23]));
            assertEquals(ImageFormat.HEIC, detect(hevc.build()));
            var avis = new HeifSequenceBuilder("avis", 1000, "avis", "msf1");
            avis.track("hvc1", 64, 48, 100, List.of(new byte[8]), HeifBuilder.box("hvcC", new byte[23]));
            assertEquals(ImageFormat.AVIF, detect(avis.build()), "the avis major brand names the sequence AVIF");
        }

        @Test
        @DisplayName("whose structure cannot be read keep the format of their brands")
        void unreadable() {
            var builder = new HeifBuilder().brands("mif1", "mif1");
            builder.primary(coded(builder, "av01"));
            var data = builder.build();
            var truncated = Arrays.copyOf(data, 40);
            assertEquals(ImageFormat.HEIC, detect(truncated));
        }
    }

    @Nested
    @DisplayName("items")
    class Items {
        @Test
        @DisplayName("read the hidden flag of their infe entry")
        void hidden() {
            var builder = new HeifBuilder().brands("mif1", "mif1");
            var shown = coded(builder, "hvc1");
            var hidden = coded(builder, "hvc1");
            builder.hidden(hidden).primary(shown);
            var file = parse(builder.build());
            assertFalse(file.item(shown).isHidden());
            assertTrue(file.item(hidden).isHidden());
        }

        @ParameterizedTest(name = "{0}: {1}")
        @CsvSource({"av01, AVIF", "sato, AVIF", "hvc1, HEIC", "lhv1, HEIC", "avc1, HEIC", "jpeg, HEIC", "j2k1, HEIC",
                "unci, HEIC"})
        @DisplayName("map their coded types to the family that decodes them")
        void families(String type, ImageFormat format) {
            assertEquals(format, HeifImageCodec.formatOf(type));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"grid", "iden", "iovl", "tmap", "Exif", "mime", "vvc1"})
        @DisplayName("of derived, metadata or unknown types have no family of their own")
        void noFamily(String type) {
            assertNull(HeifImageCodec.formatOf(type));
        }
    }

    @Nested
    @DisplayName("pages without a decoder")
    class Pages {
        @Test
        @DisplayName("refuse to open when the family of the shown image has no codec, naming its artifact")
        void missingCodec() {
            for (var type : List.of("av01", "hvc1")) {
                var builder = new HeifBuilder().brands("mif1", "mif1");
                builder.primary(coded(builder, type));
                var data = builder.build();
                var failure = assertThrows(MediaException.Unsupported.class,
                        () -> HeifPages.open(MediaSource.of(data), ImageDecoderOptions.defaults(), ImageFormat.HEIC));
                assertTrue(failure.getMessage().contains(type.equals("av01") ? "smalt-video" : "smalt-mpeg"),
                        failure.getMessage());
                assertThrows(MediaException.Unsupported.class, () -> Images.openPages(MediaSource.of(data)));
            }
        }
    }

    @Nested
    @DisplayName("page groups")
    class Groups {
        @Test
        @DisplayName("copy their pages and reject empty groups, negative and repeated pages")
        void validation() {
            var pages = new ArrayList<>(List.of(2, 0));
            var group = new PageGroup("ster", pages);
            pages.add(5);
            assertEquals(List.of(2, 0), group.pages());
            assertThrows(UnsupportedOperationException.class, () -> group.pages().add(1));
            assertThrows(IllegalArgumentException.class, () -> new PageGroup("brst", List.of()));
            assertThrows(IllegalArgumentException.class, () -> new PageGroup("brst", List.of(1, -1)));
            assertThrows(IllegalArgumentException.class, () -> new PageGroup("brst", List.of(1, 1)));
            assertThrows(NullPointerException.class, () -> new PageGroup(null, List.of(1)));
        }
    }
}
