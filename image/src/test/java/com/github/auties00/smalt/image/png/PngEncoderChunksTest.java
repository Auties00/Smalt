package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.RenderingIntent;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.chunks;
import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.gray;
import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.narrow;
import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.photo;
import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.types;
import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.wide;
import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.wideColor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ancillary chunks the PNG encoder writes (sBIT, gAMA, cHRM, sRGB, pHYs, iCCP, cICP,
 * mDCV, cLLI), their order and values as libpng and ImageIO report them, the decoder's reading of
 * the high-dynamic-range chunks, the options that select them, the PngSuite images re-encoded and
 * read back by libpng, and the unchanged bytes of the truecolor and 8-bit palette output.
 */
class PngEncoderChunksTest {
    private static final ImageDecoderOptions HIGH = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static RgbaPicture colorPhoto() {
        return narrow(40, 30, false, PngEncoderFormatsTest::photo);
    }

    private static RgbaPicture grayPhoto() {
        return narrow(40, 30, false, (x, y) -> gray((x * 7 + y * 5) & 0xFF, 255));
    }

    private static Chunk chunk(byte[] png, String type) {
        return chunks(png).stream().filter(c -> c.type().equals(type)).map(c -> new Chunk(c.type(), c.data()))
                .findFirst().orElse(null);
    }

    private record Chunk(String type, byte[] data) {
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    private static long u32(byte[] data, int offset) {
        return (long) u16(data, offset) << 16 | u16(data, offset + 2);
    }

    private static ColorInfo pqColor() {
        return new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ)
                .withMasteringDisplay(new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                        new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), 1000, 0.0001))
                .withContentLightLevel(new ContentLightLevel(1200, 400));
    }

    @Nested
    @DisplayName("significant bits")
    class SignificantBits {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "gray 1-bit, 5, 1, 1",
                "gray 8-bit, 5, 1, 5",
                "gray with alpha, 6, 2, 6",
                "indexed 4-bit, 7, 3, 7",
                "indexed 2-bit from three gray levels, 12, 3, 8",
                "truecolor with a tRNS key, 7, 3, 7",
                "truecolor with alpha for two transparent colors, 7, 4, 7",
                "16-bit gray, 10, 1, 10",
                "16-bit truecolor with alpha, 12, 4, 12",
                "16-bit truecolor with a tRNS key, 16, 3, 16"
        })
        @DisplayName("writes one value per channel, capped at the sample depth, before PLTE")
        void values(String name, int bits, int channels, int stored) {
            var c = PngEncoderFormatsTest.cases(61, 37).stream().filter(it -> it.name().equals(name)).findFirst()
                    .orElseThrow();
            var png = PngEncoder.encode(c.picture().get(), PngEncoderOptions.builder().significantBits(bits).build());
            var sbit = chunk(png, "sBIT");
            assertNotNull(sbit);
            var expected = new byte[channels];
            Arrays.fill(expected, (byte) stored);
            assertArrayEquals(expected, sbit.data(), name);
            var order = types(png);
            if (order.contains("PLTE")) {
                assertTrue(order.indexOf("sBIT") < order.indexOf("PLTE"), order.toString());
            }
            if (PngOracles.hasLibpng()) {
                var info = PngOracles.libpngInfo(png);
                assertTrue(info.stream().noneMatch(line -> line.startsWith("warning")), info.toString());
                var line = PngOracles.libpngChunk(info, "sBIT").split(" ");
                // libpng reports red, green, blue, gray, alpha
                if (c.colorType() == PngHeader.GRAY || c.colorType() == PngHeader.GRAY_ALPHA) {
                    assertEquals(stored, Integer.parseInt(line[4]));
                } else {
                    assertEquals(stored, Integer.parseInt(line[1]));
                    assertEquals(stored, Integer.parseInt(line[3]));
                }
                if (channels == 2 || channels == 4) {
                    assertEquals(stored, Integer.parseInt(line[5]));
                }
            }
        }

        @Test
        @DisplayName("leaves the samples unchanged and is absent by default")
        void samplesUnchanged() {
            var picture = wide(20, 10, false, (x, y) -> wideColor(x << 6 << 6, y << 6 << 4, 1023 << 6, 0xFFFF));
            var plain = PngEncoder.encode(picture);
            var marked = PngEncoder.encode(picture, PngEncoderOptions.builder().significantBits(10).build());
            assertNull(chunk(plain, "sBIT"));
            assertArrayEquals(PngEncoderFormatsTest.imageData(plain), PngEncoderFormatsTest.imageData(marked));
            var decoded = (Rgba16Picture) PngDecoder.decode(MediaSource.of(marked), HIGH).picture();
            assertArrayEquals(picture.pixels(), decoded.pixels());
        }
    }

    @Nested
    @DisplayName("color space chunks")
    class ColorSpace {
        @Test
        @DisplayName("writes gAMA and cHRM with the values libpng and this decoder read back")
        void gammaAndChromaticities() {
            var chroma = new PngChromaticities(new Chromaticity(0.3457, 0.3585), new Chromaticity(0.7347, 0.2653),
                    new Chromaticity(0.1596, 0.8404), new Chromaticity(0.0366, 0.0001));
            var png = PngEncoder.encode(colorPhoto(), PngEncoderOptions.builder().gamma(1 / 1.8).chromaticities(chroma)
                    .build());
            assertEquals(List.of("IHDR", "gAMA", "cHRM", "IDAT", "IEND"), types(png));
            assertEquals(55556, u32(chunk(png, "gAMA").data(), 0));
            var chrm = chunk(png, "cHRM").data();
            long[] expected = {34570, 35850, 73470, 26530, 15960, 84040, 3660, 10};
            for (var i = 0; i < 8; i++) {
                assertEquals(expected[i], u32(chrm, i * 4), "cHRM value " + i);
            }
            if (PngOracles.hasLibpng()) {
                var info = PngOracles.libpngInfo(png);
                assertEquals("gAMA 55556", PngOracles.libpngChunk(info, "gAMA"));
                assertEquals("cHRM 34570 35850 73470 26530 15960 84040 3660 10", PngOracles.libpngChunk(info, "cHRM"));
            }
            var profile = PngDecoder.decode(MediaSource.of(png)).colorProfile().orElseThrow();
            assertEquals(ColorProfile.rgb(new Chromaticity(0.7347, 0.2653), new Chromaticity(0.1596, 0.8404),
                    new Chromaticity(0.0366, 0.0001), new Chromaticity(0.3457, 0.3585), 100000.0 / 55556), profile);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.png.PngEncoderChunksTest#intents")
        @DisplayName("writes sRGB with the prescribed gAMA and cHRM, which decodes as sRGB")
        void srgb(RenderingIntent intent) {
            var png = PngEncoder.encode(colorPhoto(), PngEncoderOptions.builder().srgbIntent(intent).build());
            assertEquals(List.of("IHDR", "sRGB", "gAMA", "cHRM", "IDAT", "IEND"), types(png));
            assertEquals(intent.ordinal(), chunk(png, "sRGB").data()[0]);
            assertEquals(45455, u32(chunk(png, "gAMA").data(), 0));
            var chrm = chunk(png, "cHRM").data();
            long[] expected = {31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000};
            for (var i = 0; i < 8; i++) {
                assertEquals(expected[i], u32(chrm, i * 4));
            }
            if (PngOracles.hasLibpng()) {
                var info = PngOracles.libpngInfo(png);
                assertEquals("sRGB " + intent.ordinal(), PngOracles.libpngChunk(info, "sRGB"));
                assertTrue(info.stream().noneMatch(line -> line.startsWith("warning")), info.toString());
            }
            assertTrue(PngDecoder.decode(MediaSource.of(png)).colorProfile().isEmpty());
        }

        @Test
        @DisplayName("embeds an RGB ICC profile and keeps a gray picture in a color type")
        void rgbProfile() {
            var png = PngEncoder.encode(grayPhoto(), PngEncoderOptions.builder().colorProfile(ColorProfile.displayP3())
                    .build());
            assertEquals(PngHeader.PALETTE, png[25], "a grayscale type would contradict the RGB profile");
            assertEquals(List.of("IHDR", "iCCP", "PLTE", "IDAT", "IEND"), types(png));
            var decoded = PngDecoder.decode(MediaSource.of(png));
            assertEquals(ColorProfile.displayP3(), decoded.colorProfile().orElseThrow());
            assertArrayEquals(RasterTesting.tight(grayPhoto()), RasterTesting.tight((RgbaPicture) decoded.picture()));
            if (PngOracles.hasLibpng()) {
                var info = PngOracles.libpngInfo(png);
                assertEquals("iCCP ICC profile " + ColorProfile.displayP3().toByteArray().length,
                        PngOracles.libpngChunk(info, "iCCP"));
                assertTrue(info.stream().noneMatch(line -> line.startsWith("warning")), info.toString());
            }
        }

        @Test
        @DisplayName("embeds a gray ICC profile in a grayscale type and rejects a picture with color")
        void grayProfile() {
            var options = PngEncoderOptions.builder().colorProfile(ColorProfile.grayGamma22()).build();
            var png = PngEncoder.encode(grayPhoto(), options);
            assertEquals(PngHeader.GRAY, png[25]);
            assertEquals(ColorProfile.grayGamma22(), PngDecoder.decode(MediaSource.of(png)).colorProfile().orElseThrow());
            if (PngOracles.hasLibpng()) {
                var info = PngOracles.libpngInfo(png);
                assertNotNull(PngOracles.libpngChunk(info, "iCCP"));
                assertTrue(info.stream().noneMatch(line -> line.startsWith("warning")), info.toString());
            }
            var keyed = narrow(8, 8, true, (x, y) -> x == 0 ? 0 : gray(255, 255));
            assertEquals(PngHeader.GRAY, PngEncoder.encode(keyed, options)[25]);
            assertThrows(IllegalArgumentException.class, () -> PngEncoder.encode(colorPhoto(), options));
            var translucentColor = narrow(4, 4, true, (x, y) -> 0x80_10_20_30);
            assertThrows(IllegalArgumentException.class, () -> PngEncoder.encode(translucentColor, options));
        }

        @Test
        @DisplayName("rejects an ICC profile of a color space PNG cannot hold")
        void cmykProfile() {
            var bytes = ColorProfile.srgb().toByteArray();
            bytes[16] = 'C';
            bytes[17] = 'M';
            bytes[18] = 'Y';
            bytes[19] = 'K';
            var cmyk = ColorProfile.parse(bytes);
            assertThrows(MediaException.Unsupported.class, () -> PngEncoderOptions.builder().colorProfile(cmyk).build());
            assertThrows(MediaException.Unsupported.class, () -> ApngEncoderOptions.builder().colorProfile(cmyk).build());
            assertThrows(MediaException.Unsupported.class,
                    () -> ApngEncoderOptions.builder().colorProfile(ColorProfile.grayGamma22()).build());
        }

        @Test
        @DisplayName("writes pHYs in meters or as an aspect ratio")
        void physicalDimensions() {
            var dpi = PngEncoder.encode(colorPhoto(), PngEncoderOptions.builder()
                    .physicalDimensions(PngPhysicalDimensions.ofDotsPerInch(300)).build());
            var phys = chunk(dpi, "pHYs").data();
            assertEquals(11811, u32(phys, 0));
            assertEquals(11811, u32(phys, 4));
            assertEquals(1, phys[8]);
            var aspect = PngEncoder.encode(colorPhoto(), PngEncoderOptions.builder()
                    .physicalDimensions(PngPhysicalDimensions.ofAspectRatio(2, 1)).build());
            phys = chunk(aspect, "pHYs").data();
            assertEquals(1, u32(phys, 0));
            assertEquals(2, u32(phys, 4));
            assertEquals(0, phys[8]);
            if (PngOracles.hasLibpng()) {
                assertEquals("pHYs 11811 11811 1", PngOracles.libpngChunk(PngOracles.libpngInfo(dpi), "pHYs"));
                assertEquals("pHYs 1 2 0", PngOracles.libpngChunk(PngOracles.libpngInfo(aspect), "pHYs"));
            }
            assertEquals(new PngPhysicalDimensions(2835, 2835, PngPhysicalDimensions.Unit.METER),
                    PngPhysicalDimensions.ofDotsPerInch(72));
            assertThrows(IllegalArgumentException.class, () -> PngPhysicalDimensions.ofDotsPerInch(0));
            assertThrows(IllegalArgumentException.class, () -> PngPhysicalDimensions.ofDotsPerInch(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> PngPhysicalDimensions.ofAspectRatio(0, 1));
        }

        @Test
        @DisplayName("orders every chunk as PNG 3rd edition Table 7 requires")
        void order() {
            var picture = narrow(40, 30, true, (x, y) -> 0xFF00_0000 | ((x % 5) * 0x30_2010 + 1));
            var png = PngEncoder.encode(picture, PngEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb())
                    .gamma(0.5).chromaticities(PngChromaticities.ofSrgb()).significantBits(6)
                    .physicalDimensions(PngPhysicalDimensions.ofDotsPerInch(96)).build());
            assertEquals(PngHeader.PALETTE, png[25]);
            assertEquals(List.of("IHDR", "iCCP", "gAMA", "cHRM", "sBIT", "pHYs", "PLTE", "IDAT", "IEND"), types(png));
            if (PngOracles.hasLibpng()) {
                var info = PngOracles.libpngInfo(png);
                assertTrue(info.stream().noneMatch(line -> line.startsWith("warning")), info.toString());
            }
            if (PngOracles.hasImageIo()) {
                var metadata = PngOracles.imageIo(List.of(png)).getFirst().metadata();
                for (var node : List.of("iCCP", "gAMA value=50000", "cHRM", "sBIT_Palette red=6", "pHYs", "PLTE")) {
                    assertTrue(metadata.contains(node), node + " in " + metadata);
                }
            }
        }
    }

    static Stream<RenderingIntent> intents() {
        return Arrays.stream(RenderingIntent.values());
    }

    @Nested
    @DisplayName("high-dynamic-range profiles")
    class HighDynamicRange {
        @Test
        @DisplayName("writes a PQ profile as cICP, mDCV, cLLI and iCCP that libpng and this decoder read back exactly")
        void pq() {
            var profile = ColorProfile.of(pqColor());
            var picture = wide(30, 20, false, (x, y) -> wideColor(x * 2000, y * 3000, 40000, 0xFFFF));
            var png = PngEncoder.encode(picture, PngEncoderOptions.builder().colorProfile(profile).build());
            assertEquals(List.of("IHDR", "cICP", "mDCV", "cLLI", "iCCP", "IDAT", "IEND"), types(png));
            assertArrayEquals(new byte[]{9, 16, 0, 1}, chunk(png, "cICP").data());
            assertArrayEquals(profile.toByteArray(), PngDecoder.decode(MediaSource.of(png)).iccProfile().orElseThrow());
            var mdcv = chunk(png, "mDCV").data();
            int[] chroma = {35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450};
            for (var i = 0; i < 8; i++) {
                assertEquals(chroma[i], u16(mdcv, i * 2), "mDCV value " + i);
            }
            assertEquals(10_000_000, u32(mdcv, 16));
            assertEquals(1, u32(mdcv, 20));
            var clli = chunk(png, "cLLI").data();
            assertEquals(12_000_000, u32(clli, 0));
            assertEquals(4_000_000, u32(clli, 4));
            if (PngOracles.hasLibpng()) {
                var info = PngOracles.libpngInfo(png);
                assertEquals("cICP 9 16 0 1", PngOracles.libpngChunk(info, "cICP"));
                // libpng reports the white point first, at its usual 1/100000 scale
                assertEquals("mDCV 31270 32900 70800 29200 17000 79700 13100 4600 10000000 1",
                        PngOracles.libpngChunk(info, "mDCV"));
                assertEquals("cLLI 12000000 4000000", PngOracles.libpngChunk(info, "cLLI"));
                assertTrue(info.stream().noneMatch(line -> line.startsWith("warning")), info.toString());
            }
            var decoded = PngDecoder.decode(MediaSource.of(png), HIGH);
            var read = decoded.colorProfile().orElseThrow();
            assertTrue(read.isHighDynamicRange());
            assertEquals(profile, read);
            var color = read.colorInfo().orElseThrow();
            assertEquals(pqColor().masteringDisplay(), color.masteringDisplay());
            assertEquals(pqColor().contentLightLevel(), color.contentLightLevel());
            assertArrayEquals(picture.pixels(), ((Rgba16Picture) decoded.picture()).pixels());
        }

        @Test
        @DisplayName("writes an HLG profile without metadata as cICP and its ICC form")
        void hlg() {
            var profile = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                    ColorPrimaries.BT2020, TransferCharacteristics.HLG));
            var png = PngEncoder.encode(colorPhoto(), PngEncoderOptions.builder().colorProfile(profile).build());
            assertEquals(List.of("IHDR", "cICP", "iCCP", "IDAT", "IEND"), types(png));
            assertArrayEquals(new byte[]{9, 18, 0, 1}, chunk(png, "cICP").data());
            assertEquals(profile, PngDecoder.decode(MediaSource.of(png)).colorProfile().orElseThrow());
            if (PngOracles.hasLibpng()) {
                assertEquals("cICP 9 18 0 1", PngOracles.libpngChunk(PngOracles.libpngInfo(png), "cICP"));
            }
        }

        @Test
        @DisplayName("writes the BT.709 code point for a profile whose primaries are unspecified")
        void unspecifiedPrimaries() {
            var profile = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                    ColorPrimaries.UNSPECIFIED, TransferCharacteristics.HLG));
            var png = PngEncoder.encode(colorPhoto(), PngEncoderOptions.builder().colorProfile(profile).build());
            assertArrayEquals(new byte[]{1, 18, 0, 1}, chunk(png, "cICP").data());
            assertEquals(profile, PngDecoder.decode(MediaSource.of(png)).colorProfile().orElseThrow());
        }

        @Test
        @DisplayName("keeps a gray picture grayscale under a high-dynamic-range profile, without the RGB iCCP")
        void gray() {
            var png = PngEncoder.encode(grayPhoto(), PngEncoderOptions.builder().colorProfile(ColorProfile.of(pqColor())).build());
            assertEquals(PngHeader.GRAY, png[25]);
            assertEquals(List.of("IHDR", "cICP", "mDCV", "cLLI", "IDAT", "IEND"), types(png));
            assertTrue(PngDecoder.decode(MediaSource.of(png)).colorProfile().orElseThrow().isHighDynamicRange());
        }

        private static byte[] hdrFile(String mdcvType, String clliType, boolean afterPalette, boolean badCrc) {
            var mdcv = new byte[24];
            int[] values = {35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450};
            for (var i = 0; i < 8; i++) {
                mdcv[i * 2] = (byte) (values[i] >>> 8);
                mdcv[i * 2 + 1] = (byte) values[i];
            }
            PngFiles.put32(mdcv, 16, 4_000_000);
            PngFiles.put32(mdcv, 20, 50);
            var clli = new byte[8];
            PngFiles.put32(clli, 0, 6_004_999);
            PngFiles.put32(clli, 4, 1_995_000);
            var files = PngFiles.create().ihdr(1, 1, 8, afterPalette ? 3 : 2, 0).chunk("cICP", new byte[]{9, 16, 0, 1});
            if (afterPalette) {
                files.chunk("PLTE", new byte[]{1, 2, 3});
            }
            files.chunk(mdcvType, mdcv, badCrc).chunk(clliType, clli, badCrc);
            return files.idat(PngFiles.zlib(new byte[]{0, 0, 0, 0})).iend().bytes();
        }

        @ParameterizedTest(name = "{0} and {1}")
        @CsvSource({"mDCV, cLLI", "mDCv, cLLi"})
        @DisplayName("reads mDCV and cLLI, in either spelling, into the cICP description")
        void reads(String mdcvType, String clliType) {
            var color = PngDecoder.decode(MediaSource.of(hdrFile(mdcvType, clliType, false, false))).colorProfile()
                    .orElseThrow().colorInfo().orElseThrow();
            assertEquals(ColorPrimaries.BT2020, color.primaries());
            assertEquals(TransferCharacteristics.PQ, color.transfer());
            var display = color.masteringDisplay().orElseThrow();
            assertEquals(new Chromaticity(0.708, 0.292), display.red());
            assertEquals(new Chromaticity(0.17, 0.797), display.green());
            assertEquals(new Chromaticity(0.131, 0.046), display.blue());
            assertEquals(new Chromaticity(0.3127, 0.329), display.whitePoint());
            assertEquals(400, display.maxLuminance());
            assertEquals(0.005, display.minLuminance());
            assertEquals(new ContentLightLevel(600, 200), color.contentLightLevel().orElseThrow());
        }

        @Test
        @DisplayName("ignores mDCV and cLLI after PLTE or with a bad CRC")
        void ignoresMisplaced() {
            for (var file : List.of(hdrFile("mDCV", "cLLI", false, true))) {
                var color = PngDecoder.decode(MediaSource.of(file)).colorProfile().orElseThrow().colorInfo().orElseThrow();
                assertTrue(color.masteringDisplay().isEmpty());
                assertTrue(color.contentLightLevel().isEmpty());
            }
            var palette = PngFiles.create().ihdr(1, 1, 8, 2, 0).chunk("cICP", new byte[]{9, 16, 0, 1})
                    .chunk("PLTE", new byte[]{1, 2, 3}).chunk("cLLI", new byte[8]).idat(PngFiles.zlib(new byte[4])).iend()
                    .bytes();
            var color = PngDecoder.decode(MediaSource.of(palette)).colorProfile().orElseThrow().colorInfo().orElseThrow();
            assertTrue(color.contentLightLevel().isEmpty());
            var wrongLength = PngFiles.create().ihdr(1, 1, 8, 2, 0).chunk("cICP", new byte[]{9, 16, 0, 1})
                    .chunk("cLLI", new byte[7]).idat(PngFiles.zlib(new byte[4])).iend().bytes();
            assertTrue(PngDecoder.decode(MediaSource.of(wrongLength)).colorProfile().orElseThrow().colorInfo()
                    .orElseThrow().contentLightLevel().isEmpty());
            var afterPalette = PngDecoder.decode(MediaSource.of(hdrFile("mDCV", "cLLI", true, false))).colorProfile()
                    .orElseThrow().colorInfo().orElseThrow();
            assertTrue(afterPalette.masteringDisplay().isEmpty());
            assertTrue(afterPalette.contentLightLevel().isEmpty());
        }

        @Test
        @DisplayName("keeps the first valid mDCV and cLLI, skipping a corrupt one before it and ignoring later ones")
        void firstValid() {
            var first = new byte[24];
            PngFiles.put32(first, 16, 4_000_000);
            var second = new byte[24];
            PngFiles.put32(second, 16, 6_000_000);
            var light = new byte[8];
            PngFiles.put32(light, 0, 3_000_000);
            var other = new byte[8];
            PngFiles.put32(other, 0, 5_000_000);
            var file = PngFiles.create().ihdr(1, 1, 8, 2, 0).chunk("mDCV", second, true).chunk("cLLI", other, true)
                    .chunk("mDCV", first).chunk("cICP", new byte[]{9, 16, 0, 1}).chunk("cLLi", light)
                    .chunk("mDCv", second).chunk("cLLI", other).idat(PngFiles.zlib(new byte[4])).iend().bytes();
            var color = PngDecoder.decode(MediaSource.of(file)).colorProfile().orElseThrow().colorInfo().orElseThrow();
            assertEquals(400, color.masteringDisplay().orElseThrow().maxLuminance());
            assertEquals(300, color.contentLightLevel().orElseThrow().maxContentLightLevel());
        }

        @Test
        @DisplayName("ignores mDCV and cLLI without a high-dynamic-range cICP")
        void withoutCicp() {
            var mdcv = new byte[24];
            PngFiles.put32(mdcv, 16, 4_000_000);
            var file = PngFiles.create().ihdr(1, 1, 8, 2, 0).chunk("mDCV", mdcv).chunk("cLLI", new byte[8])
                    .idat(PngFiles.zlib(new byte[4])).iend().bytes();
            assertTrue(PngDecoder.decode(MediaSource.of(file)).colorProfile().isEmpty());
            var bt709 = PngFiles.create().ihdr(1, 1, 8, 2, 0).chunk("cICP", new byte[]{1, 1, 0, 1}).chunk("mDCV", mdcv)
                    .idat(PngFiles.zlib(new byte[4])).iend().bytes();
            PngDecoder.decode(MediaSource.of(bt709)).colorProfile()
                    .ifPresent(profile -> assertTrue(profile.colorInfo().isEmpty()));
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("validates the new settings in build()")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().significantBits(0).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().significantBits(17).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().gamma(0).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().gamma(-1).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().gamma(Double.NaN).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().gamma(1e-7).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().gamma(1e5).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder()
                    .srgbIntent(RenderingIntent.PERCEPTUAL).colorProfile(ColorProfile.srgb()).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder()
                    .srgbIntent(RenderingIntent.PERCEPTUAL).gamma(0.45).build());
            assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder()
                    .srgbIntent(RenderingIntent.PERCEPTUAL).chromaticities(PngChromaticities.ofSrgb()).build());
            assertThrows(NullPointerException.class, () -> PngEncoderOptions.builder().srgbIntent(null));
            assertThrows(IllegalArgumentException.class, () -> new PngChromaticities(new Chromaticity(30000, 0),
                    new Chromaticity(0, 0), new Chromaticity(0, 0), new Chromaticity(0, 0)));
            assertThrows(IllegalArgumentException.class, () -> new PngPhysicalDimensions(0, 1,
                    PngPhysicalDimensions.Unit.METER));
        }

        @Test
        @DisplayName("accepts a high-dynamic-range profile and resets every setting by name")
        void resets() {
            var full = PngEncoderOptions.builder().colorProfile(ColorProfile.of(pqColor())).interlaced(true).significantBits(10)
                    .gamma(0.5).chromaticities(PngChromaticities.ofSrgb())
                    .physicalDimensions(PngPhysicalDimensions.ofDotsPerInch(72)).build();
            assertEquals(full, full.toBuilder().build());
            assertEquals(full.hashCode(), full.toBuilder().build().hashCode());
            assertTrue(full.toString().contains("interlaced"));
            var cleared = full.toBuilder().noColorProfile().interlaced(false).noSignificantBits().noGamma()
                    .noChromaticities().noPhysicalDimensions().build();
            assertEquals(PngEncoderOptions.defaults(), cleared);
            var srgb = PngEncoderOptions.builder().srgbIntent(RenderingIntent.SATURATION).build();
            assertEquals(PngEncoderOptions.defaults(), srgb.toBuilder().noSrgbIntent().build());
            assertEquals(java.util.OptionalInt.of(10), full.significantBits());
            assertEquals(java.util.OptionalDouble.of(0.5), full.gamma());
        }
    }

    static Stream<String> suiteImages() {
        return PngSuiteTest.valid();
    }

    private static Map<String, byte[]> suite;

    private static synchronized Map<String, byte[]> suite() {
        if (suite == null) {
            suite = RasterTesting.untarGz(Conformance.fetch("PngSuite-2017jul19.tgz",
                    "http://www.schaik.com/pngsuite/PngSuite-2017jul19.tgz",
                    "0294b244c95a8342c01b00010cf34abdcabc7c6a34fd0fe1bd963917537bfdc8"));
        }
        return suite;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("suiteImages")
    @DisplayName("re-encodes every valid PngSuite image so that libpng reads the source samples back")
    void pngSuite(String name) {
        Assumptions.assumeTrue(PngOracles.hasLibpng(), "smalt-pngdump is not on PATH");
        var source = suite().get(name + ".png");
        var picture = PngDecoder.decode(MediaSource.of(source), HIGH).picture();
        for (var interlaced : new boolean[]{false, true}) {
            var png = PngEncoder.encode(picture, PngEncoderOptions.builder().interlaced(interlaced).build());
            assertArrayEquals(PngOracles.libpngPixels(source), PngOracles.libpngPixels(png), name);
            var sourceDepth = source[24];
            var depth = png[24];
            assertTrue(sourceDepth == 16 ? depth == 16 : depth <= 8, name + " depth " + depth);
            var sourceType = source[25];
            if (sourceType == PngHeader.GRAY && sourceDepth < 16 && types(source).indexOf("tRNS") < 0) {
                // a grayscale source never needs more bits than it has
                assertTrue(depth <= sourceDepth, name + " depth " + depth);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "basn0g01, 0, 1", "basn0g02, 0, 2", "basn0g04, 0, 4", "basn0g08, 0, 8", "basn0g16, 0, 16",
            "basn2c16, 2, 16", "basn3p01, 3, 1", "basn3p02, 3, 2", "basn3p04, 3, 4", "basn4a08, 4, 8",
            "basn4a16, 4, 16", "basn6a16, 6, 16", "tbbn0g04, 0, 4", "tbrn2c08, 2, 8", "tbwn0g16, 0, 16"
    })
    @DisplayName("writes the basic PngSuite images in their own color type and bit depth")
    void pngSuiteFormats(String name, int colorType, int bitDepth) {
        var source = suite().get(name + ".png");
        var png = PngEncoder.encode(PngDecoder.decode(MediaSource.of(source), HIGH).picture());
        assertEquals(colorType, png[25], name);
        assertEquals(bitDepth, png[24], name);
        assertEquals(types(source).contains("tRNS"), types(png).contains("tRNS"), name);
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static RgbaPicture legacyPhoto(int width, int height, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        var random = new Random(width * 7L + height);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                pixels[i] = (byte) (x * 3 + random.nextInt(8));
                pixels[i + 1] = (byte) (y * 5 + random.nextInt(8));
                pixels[i + 2] = (byte) ((x ^ y) + random.nextInt(4));
                pixels[i + 3] = (byte) (alpha ? (x + y * 2) : 255);
            }
        }
        return picture;
    }

    private static RgbaPicture legacyIndexed(int width, int height, int colors) {
        var picture = RgbaPicture.allocate(width, height, true);
        var pixels = picture.pixels();
        var random = new Random(colors);
        var table = new int[colors];
        for (var i = 0; i < colors; i++) {
            table[i] = random.nextInt() | 0x80000000;
        }
        for (var p = 0; p < width * height; p++) {
            var color = table[(p / 3 + p % 7) % colors];
            pixels[p * 4] = (byte) color;
            pixels[p * 4 + 1] = (byte) (color >>> 8);
            pixels[p * 4 + 2] = (byte) (color >>> 16);
            pixels[p * 4 + 3] = (byte) (color >>> 24);
        }
        return picture;
    }

    @ParameterizedTest(name = "{0}x{1} alpha {2} {3} level {4}")
    @CsvSource({
            "67, 45, false, NONE, 9, 0cc23a4e8e280e65c8e8f49dbac90f20a976220b47962e3e3c37ee894a441981",
            "67, 45, false, ADAPTIVE, 6, 7f4c064beb387efa03c4a7248dd9b0c1917111e84a760f938ea49d28214d4aa1",
            "45, 67, true, PAETH, 1, 9cef78734bc0ad58283c56f1621daceb24ee6162957ad7849d6d5045e0315d1d",
            "45, 67, true, ADAPTIVE, 6, 587be50e70b5153e8fe0777010f486572147778972faec5ceadf6451fbd79456",
            "1500, 900, false, ADAPTIVE, 6, 16a05355e0db1f369e584b5bc2657c8c334dbaab07a987f4f64f6a84168eeb32",
            "1500, 900, false, PAETH, 1, 801755401194a4ddd81ea3034cd23a5fa7c007b8e9dbc8cc60598d79fdc696ec",
            "700, 600, true, NONE, 9, 836496e390d4c3de1818a165ec0ed58963e61f14b3d0db1f84f1f5ae03a6f1a6",
            "700, 600, true, ADAPTIVE, 6, 438e3716d0e9d54199769c26591235cffaabee69e8122464850092ac2f667621",
            "71, 33, true, ADAPTIVE, 6, fd50dda6ba2903b05e2a721fd4b0007a2dcc54afaf91b7195b1dfcf3687a13b3",
            "71, 33, true, NONE, 9, c72db7c2d5153a91e1fa5ab2ca96924c8f87d6667e50b7beb7716d0ef9fbae43"
    })
    @DisplayName("keeps the bytes of truecolor and 8-bit palette output")
    void unchangedBytes(int width, int height, boolean alpha, PngEncoderOptions.Filter filter, int level, String sha)
            throws Exception {
        Picture picture = width == 71 ? legacyIndexed(width, height, 200) : legacyPhoto(width, height, alpha);
        var png = PngEncoder.encode(picture, PngEncoderOptions.builder().filter(filter).compressionLevel(level).build());
        assertEquals(sha, sha256(png));
    }
}
