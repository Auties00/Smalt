package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.QuantizeOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the TIFF encoder's color models, compressions and planar output: CCITT fax data compared
 * byte for byte with libtiff's tiffcp, palette, bilevel, CMYK, Y'CbCr, floating-point (with the
 * floating-point predictor) and planar pages round-tripped through Smalt's decoder and read back
 * through libtiff (tiffcp to uncompressed contiguous samples, tiff2rgba to pixels), and JPEG pages
 * checked against Smalt's JPEG encoder and decoded by libtiff.
 */
class TiffEncoderModesTest {
    static RgbaPicture bilevelPicture(int width, int height, long seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            var black = random.nextBoolean();
            var x = 0;
            while (x < width) {
                // mixes short runs with runs longer than the 2560 make-up code
                var run = switch (random.nextInt(8)) {
                    case 0 -> random.nextInt(3000);
                    case 1 -> 64 + random.nextInt(200);
                    default -> random.nextInt(12);
                };
                for (var i = 0; i < run && x < width; i++, x++) {
                    var value = (byte) (black ? 0 : 255);
                    var at = (y * width + x) * 4;
                    pixels[at] = pixels[at + 1] = pixels[at + 2] = value;
                    pixels[at + 3] = (byte) 255;
                }
                black = !black;
            }
            if (y % 7 == 3) {
                for (var i = 0; i < width; i++) {
                    var at = (y * width + i) * 4;
                    pixels[at] = pixels[at + 1] = pixels[at + 2] = (byte) (y % 2 == 0 ? 0 : 255);
                }
            }
        }
        return picture;
    }

    static RgbaPicture colors(int width, int height, int count, boolean alpha, long seed) {
        var random = new Random(seed);
        var palette = new int[count];
        for (var i = 0; i < count; i++) {
            palette[i] = random.nextInt(1 << 24);
        }
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var i = 0; i < width * height; i++) {
            var color = palette[(i / 3 + random.nextInt(2)) % count];
            pixels[i * 4] = (byte) (color >>> 16);
            pixels[i * 4 + 1] = (byte) (color >>> 8);
            pixels[i * 4 + 2] = (byte) color;
            pixels[i * 4 + 3] = (byte) (alpha ? random.nextInt(256) : 255);
        }
        return picture;
    }

    static RgbaPicture photo(int width, int height, long seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = (y * width + x) * 4;
                pixels[at] = (byte) (128 + 100 * Math.sin(x / 9.0) + random.nextInt(5));
                pixels[at + 1] = (byte) (128 + 90 * Math.cos(y / 7.0));
                pixels[at + 2] = (byte) ((x + y) * 2);
                pixels[at + 3] = (byte) 255;
            }
        }
        return picture;
    }

    /** The strips or tiles of the first page of a classic TIFF, read independently of the decoder. */
    static List<byte[]> chunks(byte[] file) {
        var little = file[0] == 'I';
        var ifd = (int) u32(file, 4, little);
        var count = u16(file, ifd, little);
        long[] offsets = null;
        long[] counts = null;
        for (var i = 0; i < count; i++) {
            var at = ifd + 2 + i * 12;
            var tag = u16(file, at, little);
            if (tag != 273 && tag != 279 && tag != 324 && tag != 325) {
                continue;
            }
            var type = u16(file, at + 2, little);
            var n = (int) u32(file, at + 4, little);
            var size = type == 3 ? 2 : 4;
            var base = n * size <= 4 ? at + 8 : (int) u32(file, at + 8, little);
            var values = new long[n];
            for (var v = 0; v < n; v++) {
                values[v] = size == 2 ? u16(file, base + v * 2, little) : u32(file, base + v * 4, little);
            }
            if (tag == 273 || tag == 324) {
                offsets = values;
            } else if (tag == 279 || tag == 325) {
                counts = values;
            }
        }
        var result = new ArrayList<byte[]>();
        for (var i = 0; i < offsets.length; i++) {
            result.add(Arrays.copyOfRange(file, (int) offsets[i], (int) (offsets[i] + counts[i])));
        }
        return result;
    }

    /** The value of a tag of the first page of a classic TIFF, or null. */
    static long[] tag(byte[] file, int wanted) {
        var little = file[0] == 'I';
        var ifd = (int) u32(file, 4, little);
        var count = u16(file, ifd, little);
        for (var i = 0; i < count; i++) {
            var at = ifd + 2 + i * 12;
            if (u16(file, at, little) != wanted) {
                continue;
            }
            var type = u16(file, at + 2, little);
            var n = (int) u32(file, at + 4, little);
            var size = switch (type) {
                case 3 -> 2;
                case 4 -> 4;
                case 5 -> 8;
                default -> 1;
            };
            var base = n * size <= 4 ? at + 8 : (int) u32(file, at + 8, little);
            var values = new long[type == 5 ? 2 * n : n];
            for (var v = 0; v < values.length; v++) {
                values[v] = switch (size) {
                    case 2 -> u16(file, base + v * 2, little);
                    case 4, 8 -> u32(file, base + v * 4, little);
                    default -> file[base + v] & 0xFF;
                };
            }
            return values;
        }
        return null;
    }

    private static int u16(byte[] data, int at, boolean little) {
        return little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 : (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
    }

    private static long u32(byte[] data, int at, boolean little) {
        return little ? (u16(data, at, true) | (long) u16(data, at + 2, true) << 16)
                : ((long) u16(data, at, false) << 16 | u16(data, at + 2, false));
    }

    static Picture decode(byte[] file) {
        return TiffDecoder.decode(MediaSource.of(file)).picture();
    }

    static ChannelPicture decodeChannels(byte[] file) {
        var options = ImageDecoderOptions.builder().keepChannels(true).highBitDepth(true).build();
        return (ChannelPicture) TiffDecoder.decode(MediaSource.of(file), options).picture();
    }

    /** The RGBA libtiff's TIFFReadRGBAImage produces, or a failure when it cannot read the file. */
    static byte[] libtiffRgba(byte[] file) {
        var rgba = LibTiff.rgba(file);
        assertNotNull(rgba, "libtiff cannot read the file");
        return rgba;
    }

    /**
     * The raw samples libtiff decompresses (tiffcp to uncompressed data, keeping the planar
     * configuration and the tiles, in the host byte order), strip or tile after strip or tile.
     */
    static byte[] libtiffSamples(byte[] file) {
        var plain = LibTiff.tiffcp(file, List.of("-c", "none"));
        var out = new ByteArrayOutputStream();
        for (var chunk : chunks(plain)) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    /**
     * The samples of an uncompressed TIFF of 8 or more bits per sample, plane after plane, rows of
     * the image only: the strips concatenated, or the tiles cropped to the image.
     */
    static byte[] raster(byte[] file) {
        var out = new ByteArrayOutputStream();
        var chunks = chunks(file);
        var tileWidth = tag(file, 322);
        if (tileWidth == null) {
            for (var chunk : chunks) {
                out.writeBytes(chunk);
            }
            return out.toByteArray();
        }
        var width = (int) tag(file, 256)[0];
        var height = (int) tag(file, 257)[0];
        var tw = (int) tileWidth[0];
        var th = (int) tag(file, 323)[0];
        var samples = (int) tag(file, 277)[0];
        var bytes = (int) tag(file, 258)[0] / 8;
        var planar = tag(file, 284) != null && tag(file, 284)[0] == 2;
        var planes = planar ? samples : 1;
        var unit = (planar ? 1 : samples) * bytes;
        var across = (width + tw - 1) / tw;
        var down = (height + th - 1) / th;
        for (var plane = 0; plane < planes; plane++) {
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var tile = chunks.get(plane * across * down + (y / th) * across + x / tw);
                    out.write(tile, ((y % th) * tw + x % tw) * unit, unit);
                }
            }
        }
        return out.toByteArray();
    }

    static byte[] gray(RgbaPicture picture) {
        var pixels = RasterTesting.tight(picture);
        var out = new byte[pixels.length / 4];
        for (var i = 0; i < out.length; i++) {
            out[i] = pixels[i * 4];
        }
        return out;
    }

    @Nested
    @DisplayName("CCITT fax coding")
    class Fax {
        static Stream<Arguments> cases() {
            var result = new ArrayList<Arguments>();
            var modes = List.of(
                    new Object[]{TiffCompression.CCITT_T4, false, false, "g3"},
                    new Object[]{TiffCompression.CCITT_T4, true, false, "g3:2d"},
                    new Object[]{TiffCompression.CCITT_T4, false, true, "g3:fill"},
                    new Object[]{TiffCompression.CCITT_T4, true, true, "g3:2d:fill"},
                    new Object[]{TiffCompression.CCITT_T6, false, false, "g4"});
            for (var mode : modes) {
                for (var width : new int[]{1, 7, 8, 37, 200, 1731, 3001}) {
                    for (var rows : new int[]{0, 5}) {
                        for (var dpi : new int[]{72, 204}) {
                            result.add(Arguments.of(mode[0], mode[1], mode[2], mode[3], width, rows, dpi));
                        }
                    }
                }
            }
            return result.stream();
        }

        @ParameterizedTest(name = "{3} width {4} rows {5} at {6} dpi")
        @MethodSource("cases")
        @DisplayName("writes the bytes libtiff writes for the same pixels")
        void matchesLibtiff(TiffCompression compression, boolean twoDimensional, boolean fill, String scheme,
                            int width, int rows, int dpi) {
            LibTiff.assume("tiffcp");
            var picture = bilevelPicture(width, 23, width * 31L + rows);
            var base = TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.BILEVEL).resolution(dpi, dpi);
            if (rows > 0) {
                base.strips(rows);
            }
            var plain = TiffEncoder.encode(picture, base.compression(TiffCompression.NONE).build());
            var arguments = new ArrayList<>(List.of("-c", scheme, "-r", String.valueOf(rows > 0 ? rows : 23)));
            var reference = LibTiff.tiffcp(plain, arguments);
            var encoded = TiffEncoder.encode(picture, base.compression(compression).faxTwoDimensional(twoDimensional)
                    .faxFillBits(fill).build());
            var expected = chunks(reference);
            var actual = chunks(encoded);
            assertEquals(expected.size(), actual.size());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), actual.get(i), "strip " + i);
            }
            assertArrayEquals(gray(bilevelExpected(picture)), gray(ImageOps.toRgba(decode(encoded))));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TiffCompression.class, names = {"CCITT_RLE", "CCITT_RLEW", "CCITT_T4", "CCITT_T6"})
        @DisplayName("round-trips through Smalt's decoder and libtiff, in strips and tiles")
        void roundTrip(TiffCompression compression) {
            for (var options : List.of(TiffEncoderOptions.builder().compression(compression).build(),
                    TiffEncoderOptions.builder().compression(compression).strips(3).bigEndian(true).build(),
                    TiffEncoderOptions.builder().compression(compression).tiles(32, 16).build(),
                    TiffEncoderOptions.builder().compression(compression).bigTiff(true).faxTwoDimensional(true)
                            .faxFillBits(true).build())) {
                for (var width : new int[]{1, 9, 77, 2700}) {
                    var picture = bilevelPicture(width, 19, width);
                    var encoded = TiffEncoder.encode(picture, options);
                    var expected = gray(bilevelExpected(picture));
                    assertArrayEquals(expected, gray(ImageOps.toRgba(decode(encoded))), options.toString());
                    var page = TiffDecoder.openPages(MediaSource.of(encoded)).page(0);
                    assertEquals(compression, page.compression());
                    assertEquals(TiffPhotometric.WHITE_IS_ZERO, page.photometric());
                    assertEquals(1, page.bitsPerSample());
                    // libtiff 4.7.1 misreads word-aligned rows, its own output included (see tools/testdata/tiff-fax)
                    if (LibTiff.find("tiff2rgba").isPresent() && !options.isBigTiff()
                            && compression != TiffCompression.CCITT_RLEW) {
                        var rgba = libtiffRgba(encoded);
                        var libtiff = new byte[rgba.length / 4];
                        for (var i = 0; i < libtiff.length; i++) {
                            libtiff[i] = rgba[i * 4];
                        }
                        assertArrayEquals(expected, libtiff, "libtiff " + options);
                    }
                }
            }
        }

        static Stream<Arguments> vectors() {
            var result = new ArrayList<Arguments>();
            for (var width : new int[]{9, 77, 1731, 3001}) {
                for (var kind : List.of("rle", "rlew", "g3", "g4", "g3-2d", "g3-fill", "g3-2d-fill", "g3-2d-fine")) {
                    result.add(Arguments.of(width, kind));
                }
            }
            return result.stream();
        }

        @ParameterizedTest(name = "{1} width {0}")
        @MethodSource("vectors")
        @DisplayName("writes the strips of libtiff's vectors and decodes them to their pixels")
        void matchesVectors(int width, String kind) {
            var reference = vector("fax-" + width + "-" + kind + ".tif");
            var source = ImageOps.toRgba(decode(vector("fax-" + width + "-none.tif")));
            // the vectors are min-is-black, so their bitmaps code the inverted pixels
            var inverted = RgbaPicture.allocate(source.width(), source.height(), false);
            var pixels = RasterTesting.tight(source);
            for (var i = 0; i < pixels.length; i += 4) {
                inverted.pixels()[i] = inverted.pixels()[i + 1] = inverted.pixels()[i + 2] = (byte) ~pixels[i];
                inverted.pixels()[i + 3] = (byte) 255;
            }
            var compression = switch (kind) {
                case "rle" -> TiffCompression.CCITT_RLE;
                case "rlew" -> TiffCompression.CCITT_RLEW;
                case "g4" -> TiffCompression.CCITT_T6;
                default -> TiffCompression.CCITT_T4;
            };
            // the -fine vector records 196 rows per inch, which makes every fourth row one-dimensional
            var options = TiffEncoderOptions.builder().compression(compression).faxTwoDimensional(kind.contains("2d"))
                    .faxFillBits(kind.contains("fill"));
            if (kind.endsWith("fine")) {
                options.resolution(204, 196);
            }
            var encoded = chunks(TiffEncoder.encode(inverted, options.build())).getFirst();
            assertEquals(1, chunks(reference).size(), "the vector has one strip");
            assertArrayEquals(chunks(reference).getFirst(), encoded);
            assertArrayEquals(gray(source), gray(ImageOps.toRgba(decode(reference))));
        }

        static byte[] vector(String name) {
            var manifest = new String(TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/fax.sha256"),
                    java.nio.charset.StandardCharsets.US_ASCII);
            var data = TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/fax/" + name);
            try {
                var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(data));
                assertTrue(manifest.contains(digest + "  " + name), name + " does not match fax.sha256");
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
            return data;
        }

        @Test
        @DisplayName("starts Group 3 rows with end-of-line codes and ends Group 4 strips with EOFB")
        void terminators() {
            var picture = bilevelPicture(40, 6, 1);
            var g3 = chunks(TiffEncoder.encode(picture, TiffEncoderOptions.builder()
                    .compression(TiffCompression.CCITT_T4).strips(3).build()));
            for (var strip : g3) {
                assertEquals(0, strip[0]);
                assertEquals(0x10, strip[1] & 0xF0);
            }
            var g4 = chunks(TiffEncoder.encode(picture, TiffEncoderOptions.fax().toBuilder().strips(3).build()));
            for (var strip : g4) {
                var end = Arrays.copyOfRange(strip, strip.length - 3, strip.length);
                var bits = (end[0] & 0xFF) << 16 | (end[1] & 0xFF) << 8 | (end[2] & 0xFF);
                // EOFB (two end-of-line codes) is the last 24 bits before the zero padding
                var shifted = bits;
                while ((shifted & 1) == 0 && shifted != 0) {
                    shifted >>>= 1;
                }
                assertEquals(0x001001, shifted & 0xFFFFFF, "strip ends with EOFB");
            }
            var tags = TiffEncoder.encode(picture, TiffEncoderOptions.builder().compression(TiffCompression.CCITT_T4)
                    .faxTwoDimensional(true).faxFillBits(true).build());
            assertArrayEquals(new long[]{5}, tag(tags, 292));
        }

        @Test
        @DisplayName("choose the two-dimensional parameter from the vertical resolution the page records")
        void parameterFromRecordedResolution() {
            var picture = bilevelPicture(300, 12, 5);
            var base = TiffEncoderOptions.builder().compression(TiffCompression.CCITT_T4).faxTwoDimensional(true);
            var standard = chunks(TiffEncoder.encode(picture, base.build())).getFirst();
            var fine = chunks(TiffEncoder.encode(picture, base.resolution(204, 196).build())).getFirst();
            assertFalse(Arrays.equals(standard, fine));
            base.automaticResolution();
            var centimeters = com.github.auties00.smalt.image.exif.ExifData.EMPTY
                    .with(com.github.auties00.smalt.image.exif.ExifData.Directory.IFD0, 0x011A,
                            com.github.auties00.smalt.image.exif.ExifValue.ofRational(80, 1))
                    .with(com.github.auties00.smalt.image.exif.ExifData.Directory.IFD0, 0x011B,
                            com.github.auties00.smalt.image.exif.ExifValue.ofRational(77, 1))
                    .with(com.github.auties00.smalt.image.exif.ExifData.Directory.IFD0, 0x0128,
                            com.github.auties00.smalt.image.exif.ExifValue.ofShort(3));
            // 77 dots per centimeter are 195.6 per inch
            assertArrayEquals(fine, chunks(TiffEncoder.encode(picture, base.exif(centimeters).build())).getFirst());
            // without its horizontal entry the EXIF resolution is not written, so 72 dots per inch apply
            var verticalOnly = centimeters.without(com.github.auties00.smalt.image.exif.ExifData.Directory.IFD0, 0x011A);
            var file = TiffEncoder.encode(picture, base.exif(verticalOnly).build());
            assertArrayEquals(new long[]{72, 1}, tag(file, 283));
            assertArrayEquals(standard, chunks(file).getFirst());
        }
    }

    /** The pixels a bilevel page keeps from an RGB picture: black where the BT.601 luma is below 128. */
    static RgbaPicture bilevelExpected(RgbaPicture picture) {
        var out = RgbaPicture.allocate(picture.width(), picture.height(), false);
        var source = RasterTesting.tight(picture);
        var target = out.pixels();
        for (var i = 0; i < source.length; i += 4) {
            var luma = (77 * (source[i] & 0xFF) + 150 * (source[i + 1] & 0xFF) + 29 * (source[i + 2] & 0xFF) + 128) >> 8;
            target[i] = target[i + 1] = target[i + 2] = (byte) (luma < 128 ? 0 : 255);
            target[i + 3] = (byte) 255;
        }
        return out;
    }

    @Nested
    @DisplayName("bilevel pages")
    class Bilevel {
        @ParameterizedTest(name = "{0}")
        @EnumSource(TiffCompression.class)
        @DisplayName("threshold the luma under every compression that takes them")
        void threshold(TiffCompression compression) {
            var options = TiffEncoderOptions.builder().compression(compression).layout(TiffEncoderOptions.Layout.BILEVEL);
            TiffEncoderOptions built;
            try {
                built = options.build();
            } catch (IllegalArgumentException exception) {
                return;
            }
            var source = TiffEncoderTest.picture(45, 13, false, false, 9);
            var encoded = TiffEncoder.encode(source, built);
            assertArrayEquals(gray(bilevelExpected(source)), gray(ImageOps.toRgba(decode(encoded))), compression.name());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Dithering.class)
        @DisplayName("dither a gradient to its mean gray and keep pure black and white")
        void dithering(Dithering dithering) {
            var width = 128;
            var height = 64;
            var gradient = RgbaPicture.allocate(width, height, false);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = (y * width + x) * 4;
                    gradient.pixels()[at] = gradient.pixels()[at + 1] = gradient.pixels()[at + 2] = (byte) (x * 2);
                    gradient.pixels()[at + 3] = (byte) 255;
                }
            }
            var options = TiffEncoderOptions.fax().toBuilder().bilevelDithering(dithering).build();
            var decoded = gray(ImageOps.toRgba(decode(TiffEncoder.encode(gradient, options))));
            // the share of white pixels in each 16-column band follows the gradient when dithering
            for (var band = 0; band < width / 16; band++) {
                var white = 0;
                for (var y = 0; y < height; y++) {
                    for (var x = band * 16; x < band * 16 + 16; x++) {
                        white += decoded[y * width + x] != 0 ? 1 : 0;
                    }
                }
                var share = white / (16.0 * height);
                var mean = (band * 16 + 7.5) * 2 / 255.0;
                if (dithering == Dithering.NONE) {
                    assertEquals(mean >= 0.5 ? 1.0 : 0.0, share, 0.5, "band " + band);
                } else {
                    assertEquals(mean, share, 0.08, dithering + " band " + band);
                }
            }
            var bilevel = bilevelPicture(77, 9, 3);
            assertArrayEquals(gray(bilevel), gray(ImageOps.toRgba(decode(TiffEncoder.encode(bilevel, options)))));
        }
    }

    @Nested
    @DisplayName("palette pages")
    class Palette {
        @ParameterizedTest(name = "{0} colors")
        @ValueSource(ints = {1, 2, 3, 4, 5, 16, 17, 200, 256})
        @DisplayName("write the fewest bits and decode exactly, through Smalt and libtiff")
        void exact(int count) {
            var source = colors(61, 17, count, false, count);
            for (var options : List.of(TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.PALETTE).build(),
                    TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.PALETTE).compression(TiffCompression.PACKBITS)
                            .tiles(16, 16).bigEndian(true).build(),
                    TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.PALETTE).compression(TiffCompression.ZSTD)
                            .strips(4).build())) {
                var encoded = TiffEncoder.encode(source, options);
                var page = TiffDecoder.openPages(MediaSource.of(encoded)).page(0);
                assertEquals(TiffPhotometric.PALETTE, page.photometric());
                var bits = count <= 2 ? 1 : count <= 4 ? 2 : count <= 16 ? 4 : 8;
                assertEquals(bits, page.bitsPerSample());
                assertEquals(TiffPredictor.NONE, page.predictor());
                RasterTesting.assertSameRgba(RasterTesting.tight(source), RasterTesting.tight(ImageOps.toRgba(decode(encoded))),
                        61, options.toString());
                if (LibTiff.find("tiff2rgba").isPresent()) {
                    RasterTesting.assertSameRgba(RasterTesting.tight(source), libtiffRgba(encoded), 61, "libtiff tools");
                }
            }
        }

        @Test
        @DisplayName("quantize pictures with more colors, drop alpha and follow the quantize options")
        void quantized() {
            var source = TiffEncoderTest.picture(64, 40, true, false, 4);
            var encoded = TiffEncoder.encode(source, TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.PALETTE)
                    .quantizeOptions(QuantizeOptions.ofColors(16)).build());
            var page = TiffDecoder.openPages(MediaSource.of(encoded)).page(0);
            assertEquals(4, page.bitsPerSample());
            assertFalse(page.hasAlpha());
            var decoded = RasterTesting.tight(ImageOps.toRgba(decode(encoded)));
            var expected = RasterTesting.tight(source);
            var error = 0L;
            var distinct = new java.util.HashSet<Integer>();
            for (var i = 0; i < decoded.length; i += 4) {
                distinct.add((decoded[i] & 0xFF) << 16 | (decoded[i + 1] & 0xFF) << 8 | decoded[i + 2] & 0xFF);
                assertEquals((byte) 255, decoded[i + 3]);
                for (var c = 0; c < 3; c++) {
                    error += Math.abs((decoded[i + c] & 0xFF) - (expected[i + c] & 0xFF));
                }
            }
            assertTrue(distinct.size() <= 16, "palette size " + distinct.size());
            assertTrue(error / (64.0 * 40 * 3) < 40, "mean error " + error / (64.0 * 40 * 3));
            var colorMap = tag(encoded, 320);
            assertEquals(3 * 16, colorMap.length);
        }
    }

    @Nested
    @DisplayName("CMYK pages")
    class Cmyk {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.tiff.TiffEncoderModesTest#cmykCases")
        @DisplayName("separate RGB so that decoding restores it exactly")
        void exact(TiffEncoderOptions.Layout layout, TiffEncoderOptions.Depth depth) {
            var source = TiffEncoderTest.picture(37, 21, layout == TiffEncoderOptions.Layout.CMYK_ALPHA, false, 5);
            var options = TiffEncoderOptions.builder().layout(layout).depth(depth).build();
            var encoded = TiffEncoder.encode(source, options);
            var page = TiffDecoder.openPages(MediaSource.of(encoded)).page(0);
            assertEquals(TiffPhotometric.SEPARATED, page.photometric());
            assertEquals(layout == TiffEncoderOptions.Layout.CMYK ? 4 : 5, page.samplesPerPixel());
            var expected = RasterTesting.tight(source);
            if (layout == TiffEncoderOptions.Layout.CMYK) {
                for (var i = 3; i < expected.length; i += 4) {
                    expected[i] = (byte) 255;
                }
            }
            RasterTesting.assertSameRgba(expected, RasterTesting.tight(ImageOps.toRgba(decode(encoded))), 37,
                    options.toString());
            var channels = decodeChannels(encoded);
            assertEquals(layout == TiffEncoderOptions.Layout.CMYK ? PixelLayout.CMYK : PixelLayout.CMYKA, channels.layout());
        }

        @Test
        @DisplayName("write a channel picture as stored under the layout naming its own channels")
        void channelsAsStored() {
            var cmyk = Channel16Picture.allocate(13, 7, PixelLayout.CMYK);
            for (var i = 0; i < cmyk.pixels().length; i++) {
                cmyk.pixels()[i] = (short) (i * 7919);
            }
            for (var layout : List.of(TiffEncoderOptions.Layout.AUTO, TiffEncoderOptions.Layout.CMYK)) {
                var encoded = TiffEncoder.encode(cmyk, TiffEncoderOptions.builder().layout(layout).build());
                assertArrayEquals(cmyk.pixels(), ((Channel16Picture) decodeChannels(encoded)).pixels(), layout.name());
            }
            // values outside [0, 1] survive only when the samples are not converted
            var hdr = Floating.floats(PixelLayout.RGB, 11, 5, 3);
            hdr.pixels()[0] = 7.25f;
            var encoded = TiffEncoder.encode(hdr, TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.RGB)
                    .build());
            assertArrayEquals(hdr.pixels(), ((ChannelFloatPicture) decodeChannels(encoded)).pixels());
            var gray = TiffEncoder.encode(hdr, TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.GRAY)
                    .build());
            assertEquals(PixelLayout.GRAY, decodeChannels(gray).layout());
        }

        @Test
        @DisplayName("are read by libtiff with the same inks")
        void libtiff() {
            LibTiff.assume("tiffcp");
            var source = TiffEncoderTest.picture(37, 21, false, false, 6);
            var encoded = TiffEncoder.encode(source, TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.CMYK)
                    .build());
            var channels = (Channel8Picture) decodeChannels(encoded);
            assertArrayEquals(Arrays.copyOf(channels.pixels(), 37 * 21 * 4), libtiffSamples(encoded));
        }
    }

    static Stream<Arguments> cmykCases() {
        var result = new ArrayList<Arguments>();
        for (var layout : List.of(TiffEncoderOptions.Layout.CMYK, TiffEncoderOptions.Layout.CMYK_ALPHA)) {
            for (var depth : List.of(TiffEncoderOptions.Depth.AUTO, TiffEncoderOptions.Depth.SIXTEEN)) {
                result.add(Arguments.of(layout, depth));
            }
        }
        return result.stream();
    }

    @Nested
    @DisplayName("floating-point pages")
    class Floating {
        static ChannelFloatPicture floats(PixelLayout layout, int width, int height, long seed) {
            var random = new SplittableRandom(seed);
            var pixels = new float[width * height * layout.count()];
            for (var i = 0; i < pixels.length; i++) {
                pixels[i] = switch (random.nextInt(6)) {
                    case 0 -> -random.nextFloat() * 100;
                    case 1 -> Float.MIN_VALUE;
                    case 2 -> (float) Math.sin(i / 17.0);
                    default -> random.nextFloat();
                };
            }
            return ChannelFloatPicture.of(width, height, width * layout.count(), pixels, layout);
        }

        static Stream<Arguments> cases() {
            var result = new ArrayList<Arguments>();
            for (var compression : List.of(TiffCompression.LZW, TiffCompression.DEFLATE, TiffCompression.ZSTD)) {
                for (var layout : List.of(PixelLayout.GRAY, PixelLayout.RGB, PixelLayout.RGBA, PixelLayout.CMYK)) {
                    for (var bigEndian : new boolean[]{false, true}) {
                        for (var planar : new boolean[]{false, true}) {
                            result.add(Arguments.of(compression, layout, bigEndian, planar));
                        }
                    }
                }
            }
            return result.stream();
        }

        @ParameterizedTest(name = "{0} {1} big-endian {2} planar {3}")
        @MethodSource("cases")
        @DisplayName("apply the floating-point predictor and decode bit for bit, through Smalt and libtiff")
        void predictor(TiffCompression compression, PixelLayout layout, boolean bigEndian, boolean planar) {
            var source = floats(layout, 29, 11, layout.count());
            var options = TiffEncoderOptions.builder().compression(compression).predictor(TiffPredictor.FLOATING_POINT)
                    .bigEndian(bigEndian).planar(planar).strips(4).build();
            var encoded = TiffEncoder.encode(source, options);
            var page = TiffDecoder.openPages(MediaSource.of(encoded)).page(0);
            assertEquals(TiffPredictor.FLOATING_POINT, page.predictor());
            assertEquals(TiffSampleFormat.FLOATING_POINT, page.sampleFormat());
            assertEquals(planar && layout.count() > 1, page.isPlanar());
            var decoded = (ChannelFloatPicture) decodeChannels(encoded);
            assertArrayEquals(source.pixels(), Arrays.copyOf(decoded.pixels(), source.pixels().length), options.toString());
            if (LibTiff.find("tiffcp").isPresent()) {
                var plain = TiffEncoder.encode(source, TiffEncoderOptions.uncompressed().toBuilder().planar(planar)
                        .build());
                assertArrayEquals(raster(plain), libtiffSamples(encoded), "libtiff tools");
            }
        }

        @Test
        @DisplayName("converts integer pictures with the FLOAT depth and skips predictors that do not fit")
        void depth() {
            var source = TiffEncoderTest.picture(23, 9, true, false, 2);
            var encoded = TiffEncoder.encode(source, TiffEncoderOptions.builder().depth(TiffEncoderOptions.Depth.FLOAT)
                    .build());
            var page = TiffDecoder.openPages(MediaSource.of(encoded)).page(0);
            assertEquals(32, page.bitsPerSample());
            assertEquals(TiffPredictor.NONE, page.predictor());
            var decoded = (ChannelFloatPicture) decodeChannels(encoded);
            assertEquals(ImageOps.toChannelFloat(ImageOps.toChannel8(source)).pixels()[5], decoded.pixels()[5]);
            assertArrayEquals(RasterTesting.tight(source), RasterTesting.tight(ImageOps.toRgba(ImageOps.toChannel8(decoded))));
            var integers = TiffEncoder.encode(source, TiffEncoderOptions.builder()
                    .predictor(TiffPredictor.FLOATING_POINT).build());
            assertEquals(TiffPredictor.NONE, TiffDecoder.openPages(MediaSource.of(integers)).page(0).predictor());
        }
    }

    @Nested
    @DisplayName("planar pages")
    class Planar {
        static Stream<Arguments> cases() {
            var result = new ArrayList<Arguments>();
            for (var compression : List.of(TiffCompression.NONE, TiffCompression.PACKBITS, TiffCompression.LZW,
                    TiffCompression.DEFLATE, TiffCompression.ZSTD)) {
                for (var layout : List.of(TiffEncoderOptions.Layout.RGB, TiffEncoderOptions.Layout.RGBA,
                        TiffEncoderOptions.Layout.GRAY_ALPHA, TiffEncoderOptions.Layout.CMYK)) {
                    for (var depth : List.of(TiffEncoderOptions.Depth.EIGHT, TiffEncoderOptions.Depth.SIXTEEN)) {
                        result.add(Arguments.of(compression, layout, depth));
                    }
                }
            }
            return result.stream();
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @MethodSource("cases")
        @DisplayName("hold the samples of the interleaved page, in strips and tiles")
        void samePixels(TiffCompression compression, TiffEncoderOptions.Layout layout, TiffEncoderOptions.Depth depth) {
            var source = TiffEncoderTest.picture(45, 33, true, false, 8);
            var base = TiffEncoderOptions.builder().compression(compression).layout(layout).depth(depth);
            for (var options : List.of(base.strips(5).bigEndian(false).build(), base.tiles(16, 32).bigEndian(true).build())) {
                var interleaved = TiffEncoder.encode(source, options);
                var planar = TiffEncoder.encode(source, options.toBuilder().planar(true).build());
                var page = TiffDecoder.openPages(MediaSource.of(planar)).page(0);
                assertTrue(page.isPlanar());
                RasterTesting.assertSameRgba(RasterTesting.tight(ImageOps.toRgba(decode(interleaved))),
                        RasterTesting.tight(ImageOps.toRgba(decode(planar))), 45, options.toString());
                // TIFFReadRGBAImage reads separate planes of RGB and 8-bit CMYK only
                if (LibTiff.find("tiff2rgba").isPresent() && (layout == TiffEncoderOptions.Layout.RGB
                        || layout == TiffEncoderOptions.Layout.RGBA
                        || (layout == TiffEncoderOptions.Layout.CMYK && depth == TiffEncoderOptions.Depth.EIGHT))) {
                    assertArrayEquals(libtiffRgba(interleaved), libtiffRgba(planar), "libtiff pixels " + options);
                }
                // tiffcp 4.7.1 misplaces the samples when it copies separate 16-bit planes in tiles, which
                // tiff2rgba reads correctly
                if (LibTiff.find("tiffcp").isPresent() && (options.tileWidth().isEmpty()
                        || depth == TiffEncoderOptions.Depth.EIGHT)) {
                    var plain = TiffEncoder.encode(source, options.toBuilder().compression(TiffCompression.NONE)
                            .bigEndian(false).planar(true).build());
                    assertArrayEquals(raster(plain), raster(LibTiff.tiffcp(planar, List.of("-c", "none"))),
                            "libtiff samples " + options);
                }
            }
        }

        @Test
        @DisplayName("write one plane of strips per sample and an interleaved page for one sample")
        void structure() {
            var source = TiffEncoderTest.picture(40, 10, false, false, 1);
            var options = TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.RGB).planar(true).strips(4)
                    .compression(TiffCompression.NONE).build();
            var planar = TiffEncoder.encode(source, options);
            assertEquals(9, chunks(planar).size());
            assertArrayEquals(new long[]{2}, tag(planar, 284));
            var red = chunks(planar).get(0);
            var expected = RasterTesting.tight(source);
            for (var x = 0; x < 40; x++) {
                assertEquals(expected[x * 4], red[x]);
            }
            var gray = TiffEncoder.encode(source, options.toBuilder().layout(TiffEncoderOptions.Layout.GRAY).build());
            assertArrayEquals(new long[]{1}, tag(gray, 284));
        }
    }

    static YuvPicture yuv(int width, int height, ChromaFormat format, ColorInfo color, long seed) {
        var random = new Random(seed);
        var chromaWidth = format == ChromaFormat.YUV444 ? width : (width + 1) / 2;
        var chromaHeight = format == ChromaFormat.YUV420 ? (height + 1) / 2 : height;
        var luma = new byte[width * height];
        var cb = new byte[chromaWidth * chromaHeight];
        var cr = new byte[chromaWidth * chromaHeight];
        var limited = color.range() == com.github.auties00.smalt.image.ColorRange.LIMITED;
        for (var i = 0; i < luma.length; i++) {
            luma[i] = (byte) (limited ? 16 + random.nextInt(220) : random.nextInt(256));
        }
        for (var i = 0; i < cb.length; i++) {
            cb[i] = (byte) (limited ? 16 + random.nextInt(225) : random.nextInt(256));
            cr[i] = (byte) (limited ? 16 + random.nextInt(225) : random.nextInt(256));
        }
        return YuvPicture.builder().size(width, height).chromaFormat(format).color(color).luma(luma, width)
                .chroma(cb, cr, chromaWidth).build();
    }

    static int maxDifference(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        var max = 0;
        for (var i = 0; i < expected.length; i++) {
            max = Math.max(max, Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF)));
        }
        return max;
    }

    @Nested
    @DisplayName("Y'CbCr pages")
    class YCbCr {
        static Stream<Arguments> formats() {
            var result = new ArrayList<Arguments>();
            for (var format : List.of(ChromaFormat.YUV444, ChromaFormat.YUV422, ChromaFormat.YUV420)) {
                for (var size : new int[][]{{32, 16}, {37, 21}, {1, 1}}) {
                    result.add(Arguments.of(format, size[0], size[1]));
                }
            }
            return result.stream();
        }

        @ParameterizedTest(name = "{0} {1}x{2}")
        @MethodSource("formats")
        @DisplayName("write the planes of a Y'CbCr picture as data units, read back by libtiff and Smalt alike")
        void dataUnits(ChromaFormat format, int width, int height) {
            var source = yuv(width, height, format, ColorInfo.JPEG, width * 7L + height);
            var options = TiffEncoderOptions.builder().compression(TiffCompression.NONE)
                    .layout(TiffEncoderOptions.Layout.YCBCR).chromaFormat(format).build();
            var encoded = TiffEncoder.encode(source, options);
            var sx = format == ChromaFormat.YUV444 ? 1 : 2;
            var sy = format == ChromaFormat.YUV420 ? 2 : 1;
            assertArrayEquals(new long[]{sx, sy}, tag(encoded, 530));
            assertArrayEquals(new long[]{6}, tag(encoded, 262));
            assertArrayEquals(new long[]{0, 1, 255, 1, 128, 1, 255, 1, 128, 1, 255, 1}, tag(encoded, 532));
            var data = raster(encoded);
            var unit = sx * sy + 2;
            var chromaWidth = source.chromaWidth();
            var at = 0;
            for (var cy = 0; cy < source.chromaHeight(); cy++) {
                for (var cx = 0; cx < chromaWidth; cx++) {
                    for (var dy = 0; dy < sy; dy++) {
                        for (var dx = 0; dx < sx; dx++) {
                            var x = Math.min(cx * sx + dx, width - 1);
                            var y = Math.min(cy * sy + dy, height - 1);
                            assertEquals(source.y()[y * source.yStride() + x], data[at + dy * sx + dx]);
                        }
                    }
                    assertEquals(source.u()[cy * source.uvStride() + cx], data[at + sx * sy]);
                    assertEquals(source.v()[cy * source.uvStride() + cx], data[at + sx * sy + 1]);
                    at += unit;
                }
            }
            assertEquals(at, data.length);
            var smalt = RasterTesting.tight(ImageOps.toRgba(decode(encoded)));
            if (LibTiff.find("tiff2rgba").isPresent()) {
                assertTrue(maxDifference(libtiffRgba(encoded), smalt) <= 1, "libtiff and Smalt disagree");
            }
            for (var compression : List.of(TiffCompression.LZW, TiffCompression.DEFLATE, TiffCompression.PACKBITS)) {
                for (var other : List.of(options.toBuilder().compression(compression).build(),
                        options.toBuilder().compression(compression).tiles(16, 16).bigEndian(true).build(),
                        options.toBuilder().compression(compression).strips(sy * 3).planar(true).build())) {
                    var again = TiffEncoder.encode(source, other);
                    assertArrayEquals(smalt, RasterTesting.tight(ImageOps.toRgba(decode(again))), other.toString());
                    var predictor = TiffDecoder.openPages(MediaSource.of(again)).page(0).predictor();
                    assertEquals(compression != TiffCompression.PACKBITS && (sx * sy == 1 || other.isPlanar())
                            ? TiffPredictor.HORIZONTAL : TiffPredictor.NONE, predictor, other.toString());
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"YUV444", "YUV422", "YUV420"})
        @DisplayName("write separate planes at their subsampled size")
        void planar(ChromaFormat format) {
            var source = yuv(37, 21, format, ColorInfo.JPEG, 3);
            var options = TiffEncoderOptions.builder().compression(TiffCompression.NONE).planar(true)
                    .layout(TiffEncoderOptions.Layout.YCBCR).chromaFormat(format).build();
            var data = raster(TiffEncoder.encode(source, options));
            var lumaSize = 37 * 21;
            var chromaSize = source.chromaWidth() * source.chromaHeight();
            assertEquals(lumaSize + 2 * chromaSize, data.length);
            assertArrayEquals(Arrays.copyOf(source.y(), lumaSize), Arrays.copyOf(data, lumaSize));
            assertArrayEquals(Arrays.copyOf(source.u(), chromaSize), Arrays.copyOfRange(data, lumaSize, lumaSize + chromaSize));
            assertArrayEquals(Arrays.copyOf(source.v(), chromaSize), Arrays.copyOfRange(data, lumaSize + chromaSize,
                    data.length));
            var interleaved = TiffEncoder.encode(source, options.toBuilder().planar(false).build());
            assertArrayEquals(RasterTesting.tight(ImageOps.toRgba(decode(interleaved))),
                    RasterTesting.tight(ImageOps.toRgba(decode(TiffEncoder.encode(source, options)))));
            // tiffcp copies separate Y'CbCr planes only when they are not subsampled
            if (LibTiff.find("tiffcp").isPresent() && format == ChromaFormat.YUV444) {
                assertArrayEquals(data, raster(LibTiff.tiffcp(TiffEncoder.encode(source, options.toBuilder()
                        .compression(TiffCompression.LZW).build()), List.of("-c", "none"))));
            }
        }

        @Test
        @DisplayName("record the matrix and range of a BT.709 limited-range picture")
        void bt709() {
            var source = yuv(32, 16, ChromaFormat.YUV444, ColorInfo.BT709_LIMITED, 5);
            var encoded = TiffEncoder.encode(source, TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.YCBCR)
                    .chromaFormat(ChromaFormat.YUV444).build());
            assertArrayEquals(new long[]{16, 1, 235, 1, 128, 1, 240, 1, 128, 1, 240, 1}, tag(encoded, 532));
            var coefficients = tag(encoded, 529);
            assertEquals(0.2126, coefficients[0] / (double) coefficients[1], 1e-9);
            assertEquals(0.0722, coefficients[4] / (double) coefficients[5], 1e-9);
            // TIFF 6.0 scales chroma by 127 / (white - black) from ReferenceBlackWhite, which differs slightly from
            // the 127.5 / 112 of limited-range video
            var expected = RasterTesting.tight(ImageOps.toRgba(source));
            var smalt = RasterTesting.tight(ImageOps.toRgba(decode(encoded)));
            assertTrue(maxDifference(expected, smalt) <= 4, "Smalt " + maxDifference(expected, smalt));
            if (LibTiff.find("tiff2rgba").isPresent()) {
                assertTrue(maxDifference(smalt, libtiffRgba(encoded)) <= 1, "libtiff tools");
            }
        }

        @Test
        @DisplayName("convert RGB pictures to full-range BT.601")
        void fromRgb() {
            var source = photo(48, 30, 2);
            var full = TiffEncoder.encode(source, TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.YCBCR)
                    .chromaFormat(ChromaFormat.YUV444).build());
            assertTrue(maxDifference(RasterTesting.tight(source), RasterTesting.tight(ImageOps.toRgba(decode(full)))) <= 2);
            var subsampled = TiffEncoder.encode(source, TiffEncoderOptions.builder()
                    .layout(TiffEncoderOptions.Layout.YCBCR).build());
            assertArrayEquals(new long[]{2, 2}, tag(subsampled, 530));
            assertTrue(com.github.auties00.smalt.testkit.Quality.psnr(RasterTesting.tight(source),
                    RasterTesting.tight(ImageOps.toRgba(decode(subsampled)))) > 28);
        }
    }

    @Nested
    @DisplayName("JPEG pages")
    class Jpeg {
        /** The entropy-coded data of a JPEG stream: the bytes after the SOS segment, through EOI. */
        static byte[] scan(byte[] stream) {
            var at = 2;
            while (true) {
                var marker = stream[at + 1] & 0xFF;
                var length = (stream[at + 2] & 0xFF) << 8 | (stream[at + 3] & 0xFF);
                if (marker == 0xDA) {
                    return Arrays.copyOfRange(stream, at + 2 + length, stream.length);
                }
                at += 2 + length;
            }
        }

        static List<Integer> markers(byte[] stream) {
            var result = new ArrayList<Integer>();
            var at = 2;
            result.add(stream[1] & 0xFF);
            while (at < stream.length) {
                var marker = stream[at + 1] & 0xFF;
                result.add(marker);
                if (marker == 0xD9 || marker == 0xDA) {
                    break;
                }
                at += 2 + ((stream[at + 2] & 0xFF) << 8 | (stream[at + 3] & 0xFF));
            }
            return result;
        }

        static Stream<Arguments> settings() {
            var result = new ArrayList<Arguments>();
            for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444, ChromaFormat.MONOCHROME)) {
                for (var optimize : new boolean[]{false, true}) {
                    for (var quality : new int[]{40, 90}) {
                        result.add(Arguments.of(format, optimize, quality));
                    }
                }
            }
            return result.stream();
        }

        @ParameterizedTest(name = "{0} optimized {1} quality {2}")
        @MethodSource("settings")
        @DisplayName("code each strip as Smalt's JPEG encoder codes it, with the tables in JPEGTables")
        void sameScan(ChromaFormat format, boolean optimize, int quality) {
            var source = photo(64, 48, quality);
            var jpeg = JpegEncoderOptions.builder().chromaFormat(format).optimizeHuffman(optimize).quality(quality).build();
            var encoded = TiffEncoder.encode(source, TiffEncoderOptions.jpeg().toBuilder().jpegOptions(jpeg).strips(48)
                    .build());
            var strip = chunks(encoded).getFirst();
            var tables = tag(encoded, 347);
            var tableBytes = new byte[tables.length];
            for (var i = 0; i < tables.length; i++) {
                tableBytes[i] = (byte) tables[i];
            }
            var reference = JpegEncoder.encode(source, jpeg);
            assertArrayEquals(scan(reference), scan(strip));
            var stripMarkers = markers(strip);
            assertFalse(stripMarkers.contains(0xDB), "the strip carries no quantization table");
            assertFalse(stripMarkers.contains(0xE0), "the strip carries no JFIF segment");
            assertEquals(optimize, stripMarkers.contains(0xC4));
            var tableMarkers = markers(tableBytes);
            assertEquals(0xD8, tableMarkers.getFirst());
            assertEquals(0xD9, tableMarkers.getLast());
            assertTrue(tableMarkers.contains(0xDB));
            assertEquals(!optimize, tableMarkers.contains(0xC4));
            var gray = format == ChromaFormat.MONOCHROME;
            assertArrayEquals(new long[]{gray ? 1 : 6}, tag(encoded, 262));
            var expected = RasterTesting.tight(ImageOps.toRgba(JpegDecoder.decode(MediaSource.of(reference)).picture()));
            var smalt = RasterTesting.tight(ImageOps.toRgba(decode(encoded)));
            assertArrayEquals(expected, smalt);
            if (LibTiff.find("tiff2rgba").isPresent()) {
                assertTrue(maxDifference(smalt, libtiffRgba(encoded)) <= 1, "libtiff "
                        + maxDifference(smalt, libtiffRgba(encoded)));
            }
        }

        @Test
        @DisplayName("write tiles and several strips that libtiff and Smalt decode alike")
        void layouts() {
            var source = photo(77, 53, 7);
            for (var options : List.of(TiffEncoderOptions.jpeg(),
                    TiffEncoderOptions.jpeg().toBuilder().strips(16).bigEndian(true).build(),
                    TiffEncoderOptions.jpeg().toBuilder().tiles(32, 16).build(),
                    TiffEncoderOptions.jpeg().toBuilder().jpegOptions(JpegEncoderOptions.builder()
                            .chromaFormat(ChromaFormat.YUV444).restartInterval(2).build()).tiles(16, 16).build(),
                    TiffEncoderOptions.jpeg().toBuilder().bigTiff(true).build())) {
                var encoded = TiffEncoder.encode(source, options);
                var smalt = RasterTesting.tight(ImageOps.toRgba(decode(encoded)));
                assertTrue(com.github.auties00.smalt.testkit.Quality.psnr(RasterTesting.tight(source), smalt) > 30,
                        options.toString());
                if (LibTiff.find("tiff2rgba").isPresent() && !options.isBigTiff()) {
                    assertTrue(maxDifference(smalt, libtiffRgba(encoded)) <= 1, "libtiff " + options);
                }
            }
            var gray = TiffEncoderTest.picture(40, 20, false, true, 3);
            var encoded = TiffEncoder.encode(gray, TiffEncoderOptions.jpeg());
            assertArrayEquals(new long[]{1}, tag(encoded, 262));
            assertArrayEquals(new long[]{8}, tag(encoded, 258));
            assertEquals(1, TiffDecoder.openPages(MediaSource.of(encoded)).page(0).samplesPerPixel());
        }

        @Test
        @DisplayName("round automatic strips to whole MCU rows and reject what JPEG cannot hold")
        void constraints() {
            var tall = photo(300, 600, 1);
            var encoded = TiffEncoder.encode(tall, TiffEncoderOptions.jpeg());
            assertEquals(0, tag(encoded, 278)[0] % 16);
            assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.jpeg().toBuilder().strips(8).build());
            assertDoesNotThrow(() -> TiffEncoderOptions.jpeg().toBuilder().strips(8).jpegOptions(JpegEncoderOptions
                    .builder().chromaFormat(ChromaFormat.YUV422).build()).build());
            // gray pages have 8-row MCUs whatever the chroma subsampling of the JPEG options
            var gray = TiffEncoderOptions.jpeg().toBuilder().layout(TiffEncoderOptions.Layout.GRAY).strips(8).build();
            var strips = TiffEncoder.encode(photo(40, 20, 3), gray);
            assertEquals(3, chunks(strips).size());
            assertArrayEquals(new long[]{1}, tag(strips, 262));
            assertThrows(IllegalArgumentException.class, () -> gray.toBuilder().strips(12).build());
            for (var layout : List.of(TiffEncoderOptions.Layout.RGBA, TiffEncoderOptions.Layout.GRAY_ALPHA,
                    TiffEncoderOptions.Layout.CMYK, TiffEncoderOptions.Layout.PALETTE, TiffEncoderOptions.Layout.BILEVEL)) {
                assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.jpeg().toBuilder().layout(layout)
                        .build(), layout.name());
            }
            assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.jpeg().toBuilder()
                    .depth(TiffEncoderOptions.Depth.SIXTEEN).build());
            assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.jpeg().toBuilder().planar(true).build());
        }
    }

    @Nested
    @DisplayName("options")
    class Validation {
        @Test
        @DisplayName("reject the combinations the format or the compression cannot hold")
        void combinations() {
            var defaults = TiffEncoderOptions.defaults();
            for (var layout : List.of(TiffEncoderOptions.Layout.GRAY, TiffEncoderOptions.Layout.RGB,
                    TiffEncoderOptions.Layout.PALETTE, TiffEncoderOptions.Layout.YCBCR, TiffEncoderOptions.Layout.CMYK)) {
                assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.fax().toBuilder().layout(layout)
                        .build(), layout.name());
            }
            assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.fax().toBuilder()
                    .layout(TiffEncoderOptions.Layout.AUTO).depth(TiffEncoderOptions.Depth.SIXTEEN).build());
            assertDoesNotThrow(() -> TiffEncoderOptions.fax().toBuilder().layout(TiffEncoderOptions.Layout.AUTO).build());
            for (var depth : List.of(TiffEncoderOptions.Depth.SIXTEEN, TiffEncoderOptions.Depth.FLOAT,
                    TiffEncoderOptions.Depth.EIGHT)) {
                assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder()
                        .layout(TiffEncoderOptions.Layout.PALETTE).depth(depth).build(), depth.name());
                assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder()
                        .layout(TiffEncoderOptions.Layout.BILEVEL).depth(depth).build(), depth.name());
            }
            assertDoesNotThrow(() -> defaults.toBuilder().layout(TiffEncoderOptions.Layout.YCBCR)
                    .depth(TiffEncoderOptions.Depth.EIGHT).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().layout(TiffEncoderOptions.Layout.YCBCR)
                    .depth(TiffEncoderOptions.Depth.SIXTEEN).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().chromaFormat(ChromaFormat.MONOCHROME)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().layout(TiffEncoderOptions.Layout.YCBCR)
                    .strips(3).build());
            assertDoesNotThrow(() -> defaults.toBuilder().layout(TiffEncoderOptions.Layout.YCBCR).strips(4).build());
            assertDoesNotThrow(() -> defaults.toBuilder().layout(TiffEncoderOptions.Layout.YCBCR)
                    .chromaFormat(ChromaFormat.YUV422).strips(3).build());
            for (var compression : List.of(TiffCompression.WEBP, TiffCompression.JPEG, TiffCompression.JPEG_2000)) {
                assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().compression(compression)
                        .planar(true).build(), compression.name());
                assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().compression(compression)
                        .layout(TiffEncoderOptions.Layout.PALETTE).build(), compression.name());
            }
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().compression(TiffCompression.JPEG_2000)
                    .depth(TiffEncoderOptions.Depth.FLOAT).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().compression(TiffCompression.WEBP)
                    .layout(TiffEncoderOptions.Layout.CMYK).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().resolution(0, 72).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().resolution(72, Double.NaN).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder()
                    .resolution(Double.POSITIVE_INFINITY, 72).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().exif(null));
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().orientation(null));
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().jpegOptions(null));
            assertDoesNotThrow(() -> defaults.toBuilder().predictor(TiffPredictor.FLOATING_POINT).build());
        }

        @Test
        @DisplayName("keep every setting through toBuilder, equality and the presets")
        void copies() {
            var options = TiffEncoderOptions.builder().compression(TiffCompression.CCITT_T4).faxTwoDimensional(true)
                    .faxFillBits(true).resolution(204, 196).orientation(Orientation.ROTATE_180)
                    .bilevelDithering(Dithering.SIERRA).planar(true).chromaFormat(ChromaFormat.YUV422)
                    .quantizeOptions(QuantizeOptions.ofColors(8)).exif(TiffEncoderMetadataTest.EXIF)
                    .xmp(XmpPacket.empty()).iptc(IptcData.of(List.of()))
                    .jpegOptions(JpegEncoderOptions.builder().quality(30).build()).build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().faxFillBits(false).build());
            assertNotEquals(options, options.toBuilder().automaticResolution().build());
            assertNotEquals(options, options.toBuilder().automaticOrientation().build());
            assertNotEquals(options, options.toBuilder().noExif().build());
            assertTrue(options.toString().contains("faxTwoDimensional=true"), options.toString());
            assertEquals(TiffCompression.CCITT_T6, TiffEncoderOptions.fax().compression());
            assertEquals(TiffEncoderOptions.Layout.BILEVEL, TiffEncoderOptions.fax().layout());
            assertEquals(TiffCompression.JPEG, TiffEncoderOptions.jpeg().compression());
            assertTrue(TiffEncoderOptions.defaults().resolution().isEmpty());
            assertTrue(TiffEncoderOptions.defaults().orientation().isEmpty());
            assertFalse(TiffEncoderOptions.defaults().isPlanar());
            assertEquals(ChromaFormat.YUV420, TiffEncoderOptions.defaults().chromaFormat());
        }

        @Test
        @DisplayName("let each page of one file take its own settings")
        void pageOptions() {
            var sink = com.github.auties00.smalt.io.MediaSink.memory();
            var encoder = new TiffEncoder(TiffEncoderOptions.builder().bigEndian(true).build(), sink);
            var photo = photo(40, 24, 1);
            encoder.addPage(photo);
            encoder.addPage(bilevelPicture(40, 24, 2), TiffEncoderOptions.fax().toBuilder().bigEndian(false).build());
            encoder.addPage(photo, TiffEncoderOptions.jpeg().toBuilder().orientation(Orientation.ROTATE_90).build());
            encoder.finish();
            var file = sink.toByteArray();
            assertEquals('M', file[0]);
            var pages = TiffDecoder.openPages(MediaSource.of(file));
            assertEquals(3, pages.pageCount());
            assertEquals(TiffCompression.LZW, pages.page(0).compression());
            assertEquals(TiffCompression.CCITT_T6, pages.page(1).compression());
            assertEquals(TiffCompression.JPEG, pages.page(2).compression());
            assertEquals(Orientation.ROTATE_90, pages.page(2).orientation());
            assertArrayEquals(gray(bilevelExpected(bilevelPicture(40, 24, 2))),
                    gray(ImageOps.toRgba(pages.read(1).picture())));
            assertThrows(IllegalStateException.class, () -> encoder.addPage(photo, TiffEncoderOptions.defaults()));
        }
    }
}
