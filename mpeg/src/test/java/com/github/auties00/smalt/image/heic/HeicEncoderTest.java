package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifGeometry;
import com.github.auties00.smalt.internal.heif.HeifGrid;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Quality;
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

import static com.github.auties00.smalt.image.heic.HeicSamples.fast;
import static com.github.auties00.smalt.image.heic.HeicSamples.parse;
import static com.github.auties00.smalt.image.heic.HeicSamples.rgba;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the HEIC still image writer: files decode back through {@link HeicDecoder} close to the source, and their
 * structure is the one HEIF and MIAF require (brands, item and property layout, essential transformative
 * properties, hidden grid tiles, alpha and metadata references), checked with the strict HEIF parser.
 */
@DisplayName("HEIC encoder")
class HeicEncoderTest {
    private static RgbaPicture decodeRgba(byte[] file) {
        return ImageOps.toRgba(HeicDecoder.decode(MediaSource.of(file)).picture());
    }

    private static List<String> propertyTypes(HeifItem item) {
        var types = new ArrayList<String>();
        for (var property : item.properties()) {
            types.add(switch (property) {
                case HeifProperty.SpatialExtent _ -> "ispe";
                case HeifProperty.PixelInformation _ -> "pixi";
                case HeifProperty.HevcConfiguration _ -> "hvcC";
                case HeifProperty.ColorNclx _ -> "nclx";
                case HeifProperty.ColorIcc _ -> "prof";
                case HeifProperty.CleanAperture _ -> "clap";
                case HeifProperty.Rotation _ -> "irot";
                case HeifProperty.Mirror _ -> "imir";
                case HeifProperty.AuxiliaryType _ -> "auxC";
                case HeifProperty.ContentLightLevel _ -> "clli";
                case HeifProperty.MasteringDisplay _ -> "mdcv";
                default -> property.getClass().getSimpleName();
            });
        }
        return types;
    }

    private static int maxAlphaError(RgbaPicture expected, RgbaPicture actual) {
        var max = 0;
        for (var i = 3; i < expected.pixels().length; i += 4) {
            max = Math.max(max, Math.abs((expected.pixels()[i] & 0xFF) - (actual.pixels()[i] & 0xFF)));
        }
        return max;
    }

    @Nested
    @DisplayName("still images")
    class Still {
        @Test
        @DisplayName("an RGB picture decodes back close to the source, as a HEIC file of the heic brand")
        void roundTrip() {
            var source = rgba(96, 64, 0, false);
            var file = HeicEncoder.encode(source, fast().build());
            var decoded = HeicDecoder.decode(MediaSource.of(file));
            assertEquals(ImageFormat.HEIC, decoded.format());
            assertEquals(ImageFormat.HEIC, Images.detect(MediaSource.of(file)).orElseThrow());
            var yuv = assertInstanceOf(YuvPicture.class, decoded.picture());
            assertEquals(ChromaFormat.YUV420, yuv.chromaFormat());
            assertEquals(96, yuv.width());
            assertEquals(64, yuv.height());
            assertFalse(yuv.hasAlpha());
            var psnr = Quality.psnr(source, ImageOps.toRgba(yuv));
            assertTrue(psnr > 38, "PSNR " + psnr);
        }

        @Test
        @DisplayName("the file carries the heic, mif1 and miaf brands and one Main Still Picture item with its properties")
        void structure() {
            var file = parse(HeicEncoder.encode(rgba(96, 64, 0, false), fast().build()));
            assertEquals("heic", file.majorBrand());
            assertTrue(file.brands().containsAll(List.of("mif1", "heic", "miaf")), file.brands().toString());
            var primary = file.primaryItem();
            assertEquals("hvc1", primary.type());
            assertFalse(primary.isHidden());
            assertEquals(List.of("ispe", "pixi", "hvcC", "nclx"), propertyTypes(primary));
            var extent = primary.property(HeifProperty.SpatialExtent.class);
            assertEquals(96, extent.width());
            assertEquals(64, extent.height());
            assertArrayEquals(new int[]{8, 8, 8}, primary.property(HeifProperty.PixelInformation.class).depths());
            var hvcC = primary.property(HeifProperty.HevcConfiguration.class).record();
            assertEquals(3, HeicSamples.profileIdc(hvcC), "Main Still Picture");
            assertEquals(8, HeicSamples.bitDepth(hvcC));
            var nclx = primary.property(HeifProperty.ColorNclx.class);
            assertEquals(ColorPrimaries.BT709.code(), nclx.primaries());
            assertEquals(TransferCharacteristics.SRGB.code(), nclx.transfer());
            assertEquals(MatrixCoefficients.BT601.code(), nclx.matrix());
            assertTrue(nclx.fullRange());
            assertEquals(1, file.items().size(), "no item besides the image");
        }

        @Test
        @DisplayName("higher qualities give larger files that decode closer to the source")
        void qualityScale() {
            var source = rgba(96, 64, 3, false);
            var previousSize = 0;
            var previousPsnr = 0.0;
            for (var quality : new int[]{10, 50, 90}) {
                var file = HeicEncoder.encode(source, fast().quality(quality).build());
                var psnr = Quality.psnr(source, decodeRgba(file));
                assertTrue(file.length > previousSize, "quality " + quality + " size " + file.length);
                assertTrue(psnr > previousPsnr, "quality " + quality + " PSNR " + psnr);
                previousSize = file.length;
                previousPsnr = psnr;
            }
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"33, 17", "1, 1", "2, 3", "65, 64"})
        @DisplayName("an odd size is coded one sample larger and cropped back by an essential clean aperture")
        void oddSize(int width, int height) {
            var source = rgba(width, height, 1, false);
            var bytes = HeicEncoder.encode(source, fast().build());
            var primary = parse(bytes).primaryItem();
            var codedWidth = (width + 1) & ~1;
            var codedHeight = (height + 1) & ~1;
            var extent = primary.property(HeifProperty.SpatialExtent.class);
            assertEquals(codedWidth, extent.width());
            assertEquals(codedHeight, extent.height());
            var clap = primary.property(HeifProperty.CleanAperture.class);
            assertNotNull(clap);
            assertEquals(List.of("ispe", "pixi", "hvcC", "nclx", "clap"), propertyTypes(primary));
            assertNull(primary.unsupportedEssential(java.util.Set.of("hvcC", "clap")), "only known essentials");
            var geometry = HeifGeometry.resolve(primary.properties(), codedWidth, codedHeight);
            assertEquals(new HeifGeometry(0, 0, width, height, Orientation.NORMAL), geometry);
            var decoded = decodeRgba(bytes);
            assertEquals(width, decoded.width());
            assertEquals(height, decoded.height());
            // the 4:2:0 chroma of a picture a few samples wide averages across its steep gradients
            var minimum = width * height < 64 ? 20 : 33;
            assertTrue(Quality.psnr(source, decoded) > minimum, "PSNR " + Quality.psnr(source, decoded));
        }

        @Test
        @DisplayName("an explicit grid splits the picture into hidden tiles that decode back as one image")
        void grid() {
            var source = rgba(150, 100, 2, false);
            var bytes = HeicEncoder.encode(source, fast().gridTileSize(64).build());
            var file = parse(bytes);
            var grid = file.primaryItem();
            assertEquals("grid", grid.type());
            assertEquals(List.of("ispe", "pixi", "nclx"), propertyTypes(grid));
            var layout = HeifGrid.parse(file.readItem(MediaSource.of(bytes), grid, MediaLimits.DEFAULT));
            assertEquals(2, layout.rows());
            assertEquals(3, layout.columns());
            assertEquals(150, layout.outputWidth());
            assertEquals(100, layout.outputHeight());
            var tiles = grid.references("dimg");
            assertEquals(6, tiles.length);
            byte[] config = null;
            for (var id : tiles) {
                var tile = file.item(id);
                assertEquals("hvc1", tile.type());
                assertTrue(tile.isHidden(), "grid tiles are hidden");
                assertEquals(64, tile.property(HeifProperty.SpatialExtent.class).width());
                assertNull(tile.property(HeifProperty.CleanAperture.class));
                var record = tile.property(HeifProperty.HevcConfiguration.class).record();
                if (config != null) {
                    assertArrayEquals(config, record, "every tile has the same configuration");
                }
                config = record;
            }
            var decoded = decodeRgba(bytes);
            assertEquals(150, decoded.width());
            assertEquals(100, decoded.height());
            assertTrue(Quality.psnr(source, decoded) > 36, "PSNR " + Quality.psnr(source, decoded));
        }

        @Test
        @DisplayName("a grid and a single image decode to nearly the same picture, and the file does not depend on threads")
        void gridThreads() {
            var source = rgba(130, 70, 4, true);
            var one = HeicEncoder.encode(source, fast().gridTileSize(64).threads(1).build());
            var many = HeicEncoder.encode(source, fast().gridTileSize(64).threads(4).build());
            var pool = HeicEncoder.encode(source, fast().gridTileSize(64).threads(0).build());
            assertArrayEquals(one, many);
            assertArrayEquals(one, pool);
            var single = HeicEncoder.encode(source, fast().threads(1).build());
            assertArrayEquals(single, HeicEncoder.encode(source, fast().threads(3).build()));
            assertTrue(Quality.psnr(decodeRgba(single), decodeRgba(one)) > 35);
        }

        @Test
        @DisplayName("transparency becomes an alpha auxiliary item that refers to the color item")
        void alpha() {
            var source = rgba(80, 48, 0, true);
            var bytes = HeicEncoder.encode(source, fast().build());
            var file = parse(bytes);
            var color = file.primaryItem();
            var auxiliaries = file.auxiliariesOf(color.id());
            assertEquals(1, auxiliaries.size());
            var alpha = auxiliaries.getFirst();
            assertEquals("hvc1", alpha.type());
            assertEquals(List.of("ispe", "pixi", "hvcC", "auxC"), propertyTypes(alpha));
            assertEquals(HeifProperty.AuxiliaryType.HEVC_ALPHA_URN, alpha.property(HeifProperty.AuxiliaryType.class).urn());
            assertTrue(alpha.references("auxl", color.id()));
            assertFalse(color.references("prem", alpha.id()));
            var decoded = decodeRgba(bytes);
            assertTrue(decoded.hasAlpha());
            assertTrue(maxAlphaError(source, decoded) <= 2, "alpha error " + maxAlphaError(source, decoded));
        }

        @Test
        @DisplayName("premultiplied alpha adds the prem reference and decodes back to straight color")
        void premultiplied() {
            var source = rgba(80, 48, 0, true);
            var bytes = HeicEncoder.encode(source, fast().premultipliedAlpha(true).build());
            var file = parse(bytes);
            var color = file.primaryItem();
            var alpha = file.auxiliariesOf(color.id()).getFirst();
            assertTrue(color.references("prem", alpha.id()));
            var decoded = decodeRgba(bytes);
            var straight = decodeRgba(HeicEncoder.encode(source, fast().build()));
            // the premultiplied coding loses precision where alpha is small, so compare the opaque half
            var right = ImageOps.crop(decoded, 40, 0, 40, 48);
            assertTrue(Quality.psnr(ImageOps.crop(straight, 40, 0, 40, 48), right) > 30);
        }

        @Test
        @DisplayName("a grid with transparency has an alpha grid of hidden tiles")
        void alphaGrid() {
            var source = rgba(100, 70, 1, true);
            var bytes = HeicEncoder.encode(source, fast().gridTileSize(64).build());
            var file = parse(bytes);
            var color = file.primaryItem();
            var alpha = file.auxiliariesOf(color.id()).getFirst();
            assertEquals("grid", alpha.type());
            assertEquals(List.of("ispe", "pixi", "auxC"), propertyTypes(alpha));
            for (var tile : alpha.references("dimg")) {
                assertTrue(file.item(tile).isHidden());
            }
            var decoded = decodeRgba(bytes);
            assertTrue(maxAlphaError(source, decoded) <= 2, "alpha error " + maxAlphaError(source, decoded));
        }

        @Test
        @DisplayName("an opaque picture that reports alpha has no alpha item")
        void opaqueAlpha() {
            var opaque = rgba(32, 32, 0, false);
            var reporting = RgbaPicture.of(32, 32, 128, opaque.pixels(), true);
            var file = parse(HeicEncoder.encode(reporting, fast().build()));
            assertEquals(List.of(), file.auxiliariesOf(file.primaryItem().id()));
            assertArrayEquals(HeicEncoder.encode(opaque, fast().build()), HeicEncoder.encode(reporting, fast().build()));
        }

        @Test
        @DisplayName("a deep picture is coded at 10 bits in Main 10 Still Picture under the heix brand")
        void tenBit() {
            var source = HeicSamples.rgba16(64, 48, true);
            var bytes = HeicEncoder.encode(source, fast().quality(90).build());
            var file = parse(bytes);
            assertEquals("heix", file.majorBrand());
            assertTrue(file.brands().containsAll(List.of("mif1", "heix", "miaf")));
            var hvcC = file.primaryItem().property(HeifProperty.HevcConfiguration.class).record();
            assertEquals(2, HeicSamples.profileIdc(hvcC));
            assertEquals(10, HeicSamples.bitDepth(hvcC));
            assertArrayEquals(new int[]{10, 10, 10}, file.primaryItem().property(HeifProperty.PixelInformation.class)
                    .depths());
            var decoded = HeicDecoder.decode(MediaSource.of(bytes), ImageDecoderOptions.builder().highBitDepth(true)
                    .build());
            var deep = assertInstanceOf(Yuv16Picture.class, decoded.picture());
            assertEquals(10, deep.bitDepth());
            assertTrue(deep.hasAlpha());
            var psnr = Quality.psnr(source, ImageOps.toRgba16(deep));
            assertTrue(psnr > 40, "PSNR " + psnr);
            var eight = HeicEncoder.encode(source, fast().quality(90).bitDepth(8).build());
            assertEquals("heic", parse(eight).majorBrand());
        }

        @Test
        @DisplayName("planar pictures keep their color description; other layouts are resampled to 4:2:0")
        void planar() {
            var rgb = rgba(64, 48, 0, false);
            for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444,
                    ChromaFormat.MONOCHROME)) {
                var yuv = ImageOps.toYuv(rgb, format, ColorInfo.BT709_LIMITED);
                var bytes = HeicEncoder.encode(yuv, fast().quality(90).build());
                var nclx = parse(bytes).primaryItem().property(HeifProperty.ColorNclx.class);
                assertEquals(MatrixCoefficients.BT709.code(), nclx.matrix(), format.toString());
                assertFalse(nclx.fullRange(), format.toString());
                var decoded = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(bytes)).picture());
                assertEquals(ChromaFormat.YUV420, decoded.chromaFormat());
                assertEquals(ColorRange.LIMITED, decoded.color().range());
                var expected = ImageOps.toYuv(yuv, ChromaFormat.YUV420, yuv.color());
                var psnr = Quality.psnr(expected, decoded);
                assertTrue(psnr > 40, format + " PSNR " + psnr);
            }
        }

        @Test
        @DisplayName("the ICC profile, Exif and XMP metadata round trip and describe the primary item")
        void metadata() {
            var exif = new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0};
            var xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);
            var profile = ColorProfile.displayP3();
            var bytes = HeicEncoder.encode(rgba(48, 32, 0, false), fast().exif(exif).xmp(xmp).colorProfile(profile)
                    .build());
            var file = parse(bytes);
            var primary = file.primaryItem();
            assertEquals(List.of("ispe", "pixi", "hvcC", "nclx", "prof"), propertyTypes(primary));
            var descriptions = file.descriptionsOf(primary.id());
            assertEquals(2, descriptions.size());
            var decoded = HeicDecoder.decode(MediaSource.of(bytes));
            assertArrayEquals(exif, decoded.exif().orElseThrow());
            assertArrayEquals(xmp, decoded.xmp().orElseThrow());
            assertArrayEquals(profile.toByteArray(), decoded.iccProfile().orElseThrow());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Orientation.class)
        @DisplayName("every orientation is signaled with irot and imir after the descriptive properties")
        void orientation(Orientation orientation) {
            var bytes = HeicEncoder.encode(rgba(33, 20, 0, false), fast().orientation(orientation).build());
            var primary = parse(bytes).primaryItem();
            var types = propertyTypes(primary);
            assertEquals(List.of("ispe", "pixi", "hvcC", "nclx", "clap"), types.subList(0, 5));
            var transformative = types.subList(5, types.size());
            var expected = switch (orientation) {
                case NORMAL -> List.of();
                case FLIP_HORIZONTAL, FLIP_VERTICAL -> List.of("imir");
                case ROTATE_90, ROTATE_180, ROTATE_270 -> List.of("irot");
                case TRANSPOSE, TRANSVERSE -> List.of("irot", "imir");
            };
            assertEquals(expected, transformative);
            assertEquals(orientation, HeicDecoder.decode(MediaSource.of(bytes)).orientation());
        }

        @Test
        @DisplayName("the mastering display and content light level of HDR content become mdcv and clli")
        void hdr() {
            var mastering = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.170, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.3290), 1000, 0.005);
            var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ).withMasteringDisplay(mastering)
                    .withContentLightLevel(new ContentLightLevel(800, 400));
            var yuv = ImageOps.toYuv16(rgba(48, 32, 0, false), ChromaFormat.YUV420, color, 10);
            var bytes = HeicEncoder.encode(yuv, fast().build());
            var primary = parse(bytes).primaryItem();
            assertEquals(List.of("ispe", "pixi", "hvcC", "nclx", "clli", "mdcv"), propertyTypes(primary));
            var clli = primary.property(HeifProperty.ContentLightLevel.class);
            assertEquals(800, clli.maxContentLightLevel());
            assertEquals(400, clli.maxPictureAverageLightLevel());
            assertEquals(10_000_000L, primary.property(HeifProperty.MasteringDisplay.class).maxLuminance());
            var decoded = HeicDecoder.decode(MediaSource.of(bytes), ImageDecoderOptions.builder().highBitDepth(true)
                    .build());
            var signaled = ((Yuv16Picture) decoded.picture()).color();
            assertEquals(TransferCharacteristics.PQ, signaled.transfer());
            assertEquals(800, signaled.contentLightLevel().orElseThrow().maxContentLightLevel());
        }

        @Test
        @DisplayName("the sink overload writes the bytes the array overload returns")
        void sink() {
            var source = rgba(40, 30, 0, true);
            var options = fast().build();
            var sink = MediaSink.memory();
            HeicEncoder.encode(source, options, sink);
            assertArrayEquals(HeicEncoder.encode(source, options), sink.toByteArray());
        }
    }

    @Nested
    @DisplayName("through the image registry")
    class Registry {
        @Test
        @DisplayName("Images encodes HEIC at the requested quality with the generic metadata")
        void encode() {
            assertTrue(Images.isEncodable(ImageFormat.HEIC));
            assertTrue(Images.isAnimationEncodable(ImageFormat.HEIC));
            var source = rgba(64, 40, 0, true);
            var low = Images.encode(source, ImageFormat.HEIC, ImageEncoderOptions.ofQuality(20));
            var high = Images.encode(source, ImageFormat.HEIC, ImageEncoderOptions.ofQuality(95));
            assertTrue(high.length > low.length);
            assertArrayEquals(HeicEncoder.encode(source, HeicEncoderOptions.ofQuality(95)), high);
            var decoded = Images.decode(MediaSource.of(high));
            assertEquals(ImageFormat.HEIC, decoded.format());
            assertTrue(Quality.psnr(source, ImageOps.toRgba(decoded.picture())) > 38);
            var xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);
            var tagged = Images.encode(source, ImageFormat.HEIC, ImageEncoderOptions.builder().xmp(xmp)
                    .orientation(Orientation.ROTATE_90).build());
            var read = Images.decode(MediaSource.of(tagged));
            assertEquals(Orientation.ROTATE_90, read.orientation());
            assertTrue(new String(read.xmp().orElseThrow(), StandardCharsets.UTF_8).contains("xmpmeta"));
        }

        @Test
        @DisplayName("a lossless request is rejected, as the HEVC encoder codes lossy images only")
        void lossless() {
            var error = assertThrows(MediaException.Unsupported.class,
                    () -> Images.encode(rgba(16, 16, 0, false), ImageFormat.HEIC, ImageEncoderOptions.lossless()));
            assertTrue(error.getMessage().contains("lossy"), error.getMessage());
        }
    }

    @Nested
    @DisplayName("invalid settings and inputs")
    class Invalid {
        @Test
        @DisplayName("the builder rejects values out of range and colors that need 4:4:4")
        void builder() {
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().quality(101).build());
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().alphaQuality(-1).build());
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().bitDepth(12).build());
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().gridTileSize(63).build());
            // 5970 is the largest even side whose square HEVC level 6.2 codes
            assertEquals(5970, HeicEncoderOptions.builder().gridTileSize(5970).build().gridTileSize().orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().gridTileSize(5972)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().gridTileSize(65).build());
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().threads(-1).build());
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().loopCount(-1).build());
            assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder()
                    .color(ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY)).build());
            for (var reversible : List.of(MatrixCoefficients.YCGCO_RE, MatrixCoefficients.YCGCO_RO)) {
                assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder()
                        .color(ColorInfo.JPEG.withMatrix(reversible)).build(), reversible.toString());
            }
            // YCgCo (matrix 8) is not reversible and codes in 4:2:0
            assertEquals(MatrixCoefficients.YCGCO, HeicEncoderOptions.builder()
                    .color(ColorInfo.JPEG.withMatrix(MatrixCoefficients.YCGCO)).build().color().matrix());
            var options = HeicEncoderOptions.forArchive();
            assertEquals(options, options.toBuilder().build());
            assertEquals(10, options.bitDepth().orElseThrow());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
        }

        @Test
        @DisplayName("a planar picture whose matrix needs 4:4:4 is rejected")
        void identity() {
            var gbr = ImageOps.toYuv(rgba(16, 16, 0, false), ChromaFormat.YUV444,
                    ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY));
            assertThrows(MediaException.Unsupported.class, () -> HeicEncoder.encode(gbr, fast().build()));
        }

        @Test
        @DisplayName("a picture over the limits is rejected before coding")
        void limits() {
            var limits = MediaLimits.builder().maxDimension(32).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> HeicEncoder.encode(rgba(48, 16, 0, false), fast().limits(limits).build()));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> HeicEncoder.openAnimationWriter(48, 16, fast().limits(limits).build(), MediaSink.memory()));
            assertThrows(IllegalArgumentException.class,
                    () -> HeicEncoder.openAnimationWriter(0, 16, fast().build(), MediaSink.memory()));
        }

        @Test
        @DisplayName("the options are value objects")
        void equality() {
            var a = fast().exif(new byte[]{1, 2}).build();
            var b = fast().exif(new byte[]{1, 2}).build();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertFalse(a.equals(fast().build()));
            assertTrue(a.toString().contains("exif=2 bytes"), a.toString());
            var exif = a.exif().orElseThrow();
            exif[0] = 9;
            assertTrue(Arrays.equals(new byte[]{1, 2}, a.exif().orElseThrow()), "accessors return copies");
            assertTrue(fast().noMetadata().build().exif().isEmpty());
        }
    }
}
