package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegCoefficients;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.image.jpeg.internal.JpegTranscoder;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the jpegtran extensions of the lossless JPEG transforms at the coefficient level, independently of any
 * oracle: crop extension with its three fills and exact sizes, wipe with its three fills, drop with common
 * quantization tables or requantization, restart intervals in MCU rows, and arithmetic-coded output, at 8 and 12
 * bits; then the drop switch against {@code jpegtran} and the option rules.
 */
class JpegTransformEditsTest {
    private static JpegCoefficients coefficients(byte[] jpeg) {
        return new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodeCoefficients();
    }

    // one block of a component, in natural order, or zeros outside the component's real grid
    private static short[] block(JpegCoefficients coefficients, int component, int column, int row) {
        var info = coefficients.frame().components().get(component);
        var out = new short[64];
        if (column < 0 || row < 0 || column >= info.blocksWide() || row >= info.blocksHigh()) {
            return out;
        }
        var offset = (row * info.paddedBlocksWide() + column) * 64;
        System.arraycopy(coefficients.coefficients(component), offset, out, 0, 64);
        return out;
    }

    private static short[] mirrored(short[] block) {
        var out = block.clone();
        for (var k = 1; k < 64; k += 2) {
            out[k] = (short) -out[k];
        }
        return out;
    }

    private static short[] flat(int dc) {
        var out = new short[64];
        out[0] = (short) dc;
        return out;
    }

    private static int blocksWide(JpegCoefficients coefficients, int component) {
        return coefficients.frame().components().get(component).blocksWide();
    }

    private static int blocksHigh(JpegCoefficients coefficients, int component) {
        return coefficients.frame().components().get(component).blocksHigh();
    }

    private static byte[] transform(byte[] source, JpegTransformOptions options) {
        return JpegTransforms.transform(MediaSource.of(source), options);
    }

    private static JpegTransformOptions.Builder jpegtran() {
        return JpegTransformOptions.jpegtran().toBuilder();
    }

    @Nested
    @DisplayName("crop extension")
    class Extension {
        @Test
        @DisplayName("places the image at the MCU-aligned offset and leaves the added blocks and partial MCUs gray")
        void gray() {
            var source = JpegTransformsTest.encode(41, 29, ChromaFormat.YUV420, 0);
            var output = transform(source, jpegtran().crop(new Region(20, 17, 100, 60)).build());
            var info = JpegDecoder.probe(MediaSource.of(output));
            assertEquals(100, info.width());
            assertEquals(60, info.height());
            var in = coefficients(source);
            var out = coefficients(output);
            for (var c = 0; c < 3; c++) {
                var sampling = c == 0 ? 2 : 1;
                var left = sampling;
                var top = sampling;
                // only the whole MCUs of the 41x29 image survive: 2 MCU columns, 1 MCU row
                var columns = 2 * sampling;
                var rows = sampling;
                for (var row = 0; row < blocksHigh(out, c); row++) {
                    for (var column = 0; column < blocksWide(out, c); column++) {
                        var inside = column >= left && column < left + columns && row >= top && row < top + rows;
                        var expected = inside ? block(in, c, column - left, row - top) : new short[64];
                        assertArrayEquals(expected, block(out, c, column, row), c + " " + column + "," + row);
                    }
                }
            }
        }

        @Test
        @DisplayName("a flat fill repeats the DC coefficient of the nearest image block of the row")
        void flatFill() {
            var source = JpegTransformsTest.encode(41, 29, ChromaFormat.YUV444, 0);
            var output = transform(source, jpegtran().crop(new Region(16, 0, 75, 29)).fill(JpegFill.FLAT).build());
            var in = coefficients(source);
            var out = coefficients(output);
            for (var c = 0; c < 3; c++) {
                for (var row = 0; row < blocksHigh(out, c); row++) {
                    for (var column = 0; column < blocksWide(out, c); column++) {
                        short[] expected;
                        if (column < 2) {
                            expected = flat(block(in, c, 0, row)[0]);
                        } else if (column < 7) {
                            expected = block(in, c, column - 2, row);
                        } else {
                            expected = flat(block(in, c, 4, row)[0]);
                        }
                        assertArrayEquals(expected, block(out, c, column, row), c + " " + column + "," + row);
                    }
                }
            }
        }

        @Test
        @DisplayName("a flat fill of an image narrower than one MCU repeats its first block's DC on both sides")
        void flatNarrow() {
            var source = JpegTransformsTest.encode(7, 9, ChromaFormat.MONOCHROME, 0);
            var output = transform(source, jpegtran().crop(new Region(16, 0, 40, 9)).fill(JpegFill.FLAT).build());
            var in = coefficients(source);
            var out = coefficients(output);
            assertEquals(5, blocksWide(out, 0));
            for (var row = 0; row < blocksHigh(out, 0); row++) {
                for (var column = 0; column < blocksWide(out, 0); column++) {
                    assertArrayEquals(flat(block(in, 0, 0, row)[0]), block(out, 0, column, row), column + "," + row);
                }
            }
        }

        @Test
        @Timeout(30)
        @DisplayName("a reflection of an image narrower than one MCU ends and leaves the added blocks gray")
        void reflectNarrow() {
            // jpegtran loops forever here: it has no whole MCU column to reflect
            var source = JpegTransformsTest.encode(13, 20, ChromaFormat.YUV420, 0);
            var output = transform(source, jpegtran().crop(new Region(16, 0, 48, 20)).fill(JpegFill.REFLECT).build());
            var in = coefficients(source);
            var out = coefficients(output);
            assertEquals(48, out.frame().width());
            for (var c = 0; c < 3; c++) {
                for (var row = 0; row < blocksHigh(out, c); row++) {
                    for (var column = 0; column < blocksWide(out, c); column++) {
                        assertArrayEquals(new short[64], block(out, c, column, row), c + " " + column + "," + row);
                    }
                }
            }
            assertFalse(Arrays.equals(new short[64], block(in, 0, 0, 0)), "the image itself is not gray");
        }

        @Test
        @DisplayName("a reflection mirrors the image into the added columns on both sides")
        void reflection() {
            var source = JpegTransformsTest.encode(41, 29, ChromaFormat.YUV444, 0);
            var output = transform(source, jpegtran().crop(new Region(40, 0, 160, 29)).fill(JpegFill.REFLECT)
                    .build());
            var in = coefficients(source);
            var out = coefficients(output);
            for (var row = 0; row < blocksHigh(out, 0); row++) {
                for (var k = 0; k < 5; k++) {
                    assertArrayEquals(block(in, 0, k, row), block(out, 0, 5 + k, row));
                    // the first period on each side mirrors the image about its edge
                    assertArrayEquals(mirrored(block(in, 0, k, row)), block(out, 0, 4 - k, row));
                    assertArrayEquals(mirrored(block(in, 0, 4 - k, row)), block(out, 0, 10 + k, row));
                    // the next period mirrors the mirror, which gives the image back
                    assertArrayEquals(block(in, 0, k, row), block(out, 0, 10 + 5 + k, row));
                }
            }
        }

        @Test
        @DisplayName("exact sizes keep the requested size from the MCU boundary left of and above the corner")
        void exact() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0);
            var grown = JpegDecoder.probe(MediaSource.of(transform(source, jpegtran().crop(new Region(9, 5, 20, 18))
                    .build())));
            assertEquals(29, grown.width());
            assertEquals(23, grown.height());
            var exact = transform(source, jpegtran().crop(new Region(9, 5, 20, 18)).exactCropWidth(true)
                    .exactCropHeight(true).build());
            var info = JpegDecoder.probe(MediaSource.of(exact));
            assertEquals(20, info.width());
            assertEquals(18, info.height());
            var in = coefficients(source);
            var out = coefficients(exact);
            for (var row = 0; row < blocksHigh(out, 0); row++) {
                for (var column = 0; column < blocksWide(out, 0); column++) {
                    assertArrayEquals(block(in, 0, column, row), block(out, 0, column, row));
                }
            }
        }

        @Test
        @DisplayName("an extension under a transform, or that pushes the image past the edge, is rejected")
        void rejects() {
            var source = JpegTransformsTest.encode(41, 29, ChromaFormat.YUV420, 0);
            assertThrows(IllegalArgumentException.class, () -> transform(source, jpegtran()
                    .crop(new Region(0, 0, 100, 60)).transform(Orientation.ROTATE_90).build()));
            assertThrows(IllegalArgumentException.class, () -> transform(source, jpegtran()
                    .crop(new Region(60, 0, 100, 29)).build()));
            assertThrows(IllegalArgumentException.class, () -> transform(source, jpegtran()
                    .crop(new Region(0, 2, 100, 30)).build()), "a 30-line crop can place the 29-line image at y 0 or 1");
            transform(source, jpegtran().crop(new Region(0, 1, 100, 30)).build());
            // a frame header records at most 65535 pixels per side
            var widest = transform(source, jpegtran().crop(new Region(0, 0, 65535, 29)).build());
            assertEquals(65535, JpegDecoder.probe(MediaSource.of(widest)).width());
            assertThrows(IllegalArgumentException.class, () -> transform(source, jpegtran()
                    .crop(new Region(0, 0, 65536, 29)).build()));
            assertThrows(IllegalArgumentException.class, () -> transform(source, jpegtran()
                    .crop(new Region(0, 0, 41, 70000)).build()));
        }

        @Test
        @DisplayName("a 12-bit image extends with its precision and coefficients kept")
        void twelveBit() {
            var source = JpegTransformsTest.twelveBit("64x48-yuv444-baseline-q100.jpg");
            var output = transform(source, jpegtran().crop(new Region(8, 8, 80, 64)).fill(JpegFill.REFLECT).build());
            var in = coefficients(source);
            var out = coefficients(output);
            assertEquals(12, out.frame().precision());
            for (var row = 0; row < 6; row++) {
                for (var column = 0; column < 8; column++) {
                    assertArrayEquals(block(in, 1, column, row), block(out, 1, column + 1, row + 1));
                }
                assertArrayEquals(mirrored(block(in, 1, 0, row)), block(out, 1, 0, row + 1));
            }
        }
    }

    @Nested
    @DisplayName("wipe")
    class Wipe {
        @Test
        @DisplayName("clears the MCUs covering the region to gray and keeps the rest")
        void gray() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0);
            var output = JpegTransforms.wipe(MediaSource.of(source), new Region(9, 5, 20, 18));
            var in = coefficients(source);
            var out = coefficients(output);
            assertEquals(64, out.frame().width());
            for (var c = 0; c < 3; c++) {
                var limit = c == 0 ? 4 : 2;
                for (var row = 0; row < blocksHigh(out, c); row++) {
                    for (var column = 0; column < blocksWide(out, c); column++) {
                        var wiped = column < limit && row < limit;
                        assertArrayEquals(wiped ? new short[64] : block(in, c, column, row), block(out, c, column, row));
                    }
                }
            }
        }

        @Test
        @DisplayName("a flat wipe takes the average DC of the blocks left and right of the region")
        void flatFill() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV444, 0);
            var output = transform(source, jpegtran().wipe(new Region(16, 16, 16, 16)).fill(JpegFill.FLAT).build());
            var in = coefficients(source);
            var out = coefficients(output);
            for (var c = 0; c < 3; c++) {
                for (var row = 0; row < 6; row++) {
                    for (var column = 0; column < 8; column++) {
                        var wiped = column >= 2 && column < 4 && row >= 2 && row < 4;
                        var expected = wiped
                                ? flat((block(in, c, 1, row)[0] + block(in, c, 4, row)[0]) >> 1)
                                : block(in, c, column, row);
                        assertArrayEquals(expected, block(out, c, column, row));
                    }
                }
            }
        }

        @Test
        @DisplayName("a reflecting wipe at the left edge mirrors the image right of it")
        void reflection() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV444, 0);
            var output = transform(source, jpegtran().wipe(new Region(0, 0, 16, 48)).fill(JpegFill.REFLECT).build());
            var in = coefficients(source);
            var out = coefficients(output);
            for (var row = 0; row < 6; row++) {
                assertArrayEquals(mirrored(block(in, 0, 2, row)), block(out, 0, 1, row));
                assertArrayEquals(mirrored(block(in, 0, 3, row)), block(out, 0, 0, row));
                for (var column = 2; column < 8; column++) {
                    assertArrayEquals(block(in, 0, column, row), block(out, 0, column, row));
                }
            }
            // not spanning the full height, the same region is wiped to gray
            var partial = coefficients(transform(source, jpegtran().wipe(new Region(0, 8, 16, 40))
                    .fill(JpegFill.REFLECT).build()));
            assertArrayEquals(new short[64], block(partial, 0, 0, 1));
            assertArrayEquals(block(in, 0, 0, 0), block(partial, 0, 0, 0));
        }

        @Test
        @DisplayName("a 12-bit image is wiped with its precision kept")
        void twelveBit() {
            var source = JpegTransformsTest.twelveBit("227x149-yuv420-arithmetic-rst3.jpg");
            var output = transform(source, jpegtran().wipe(new Region(100, 50, 30, 30)).arithmetic(true).build());
            var out = coefficients(output);
            assertEquals(12, out.frame().precision());
            assertTrue(out.frame().arithmetic());
            assertArrayEquals(new short[64], block(out, 0, 12, 6));
            assertArrayEquals(block(coefficients(source), 0, 0, 0), block(out, 0, 0, 0));
        }

        @Test
        @DisplayName("a wipe region outside the image is rejected")
        void rejects() {
            var source = JpegTransformsTest.encode(32, 32, ChromaFormat.YUV420, 0);
            assertThrows(IllegalArgumentException.class, () -> JpegTransforms.wipe(MediaSource.of(source),
                    new Region(30, 0, 8, 8)));
            assertThrows(IllegalArgumentException.class, () -> JpegTransforms.wipe(MediaSource.of(source),
                    new Region(0, 0, 40, 8)));
        }
    }

    @Nested
    @DisplayName("drop")
    class Drop {
        @Test
        @DisplayName("common quantization tables keep the exact values of both images")
        void commonTables() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0, 88);
            var image = JpegTransformsTest.encode(40, 32, ChromaFormat.YUV420, 0, 50);
            var output = JpegTransforms.drop(MediaSource.of(source), MediaSource.of(image), 16, 16);
            var in = coefficients(source);
            var dropped = coefficients(image);
            var out = coefficients(output);
            assertFalse(Arrays.equals(in.quantization(0), dropped.quantization(0)));
            for (var c = 0; c < 3; c++) {
                var sampling = c == 0 ? 2 : 1;
                var table = out.quantization(c);
                for (var k = 0; k < 64; k++) {
                    assertEquals(0, in.quantization(c)[k] % table[k]);
                    assertEquals(0, dropped.quantization(c)[k] % table[k]);
                }
                for (var row = 0; row < blocksHigh(out, c); row++) {
                    for (var column = 0; column < blocksWide(out, c); column++) {
                        // the 40x32 image keeps 2 whole MCU columns and, reaching the bottom edge, both MCU rows
                        var inside = column >= sampling && column < 3 * sampling && row >= sampling
                                && row < 3 * sampling;
                        var from = inside ? dropped : in;
                        var expected = block(from, c, inside ? column - sampling : column, inside ? row - sampling : row);
                        var actual = block(out, c, column, row);
                        for (var k = 0; k < 64; k++) {
                            assertEquals(expected[k] * from.quantization(c)[k], actual[k] * table[k],
                                    c + " " + column + "," + row + " " + k);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("requantization keeps the source's tables and rounds the dropped coefficients")
        void requantize() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV444, 0, 88);
            var image = JpegTransformsTest.encode(24, 24, ChromaFormat.YUV444, 0, 40);
            var output = transform(source, jpegtran().drop(image, 8, 16).requantizeDrop(true).build());
            var in = coefficients(source);
            var dropped = coefficients(image);
            var out = coefficients(output);
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(in.quantization(c), out.quantization(c));
                for (var row = 0; row < 3; row++) {
                    for (var column = 0; column < 3; column++) {
                        var from = block(dropped, c, column, row);
                        var actual = block(out, c, column + 1, row + 2);
                        for (var k = 0; k < 64; k++) {
                            var value = from[k] * dropped.quantization(c)[k];
                            var q = in.quantization(c)[k];
                            var rounded = value < 0 ? -((-value + q / 2) / q) : (value + q / 2) / q;
                            assertEquals(rounded, actual[k]);
                        }
                    }
                }
                assertArrayEquals(block(in, c, 0, 0), block(out, c, 0, 0));
            }
        }

        @Test
        @DisplayName("the offset moves to the next MCU and only whole MCUs are copied, except at the edges")
        void alignment() {
            var source = JpegTransformsTest.encode(61, 47, ChromaFormat.YUV420, 0);
            var image = JpegTransformsTest.encode(29, 15, ChromaFormat.YUV420, 0);
            var dropped = coefficients(image);
            var out = coefficients(transform(source, jpegtran().drop(image, 32, 32).build()));
            // 32 + 29 = 61 and 32 + 15 = 47 reach the edges, so the partial MCUs come along
            for (var row = 0; row < 2; row++) {
                for (var column = 0; column < 4; column++) {
                    assertArrayEquals(block(dropped, 0, column, row), block(out, 0, column + 4, row + 4));
                }
            }
            var in = coefficients(source);
            var shifted = coefficients(transform(source, jpegtran().drop(image, 1, 1).build()));
            // from 1,1 the drop starts at MCU 1,1, where 14 of its 29 columns are left: no whole MCU, nothing copied
            for (var row = 0; row < blocksHigh(shifted, 0); row++) {
                for (var column = 0; column < blocksWide(shifted, 0); column++) {
                    assertArrayEquals(block(in, 0, column, row), block(shifted, 0, column, row));
                }
            }
        }

        @Test
        @DisplayName("a gray image drops into a color one, its missing chroma filled with gray")
        void grayIntoColor() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0);
            var image = JpegTransformsTest.encode(32, 32, ChromaFormat.MONOCHROME, 0);
            var in = coefficients(source);
            var dropped = coefficients(image);
            var out = coefficients(transform(source, jpegtran().drop(image, 16, 16).build()));
            for (var row = 0; row < 4; row++) {
                for (var column = 0; column < 4; column++) {
                    assertArrayEquals(block(dropped, 0, column, row), block(out, 0, column + 2, row + 2));
                }
            }
            for (var c = 1; c < 3; c++) {
                for (var row = 0; row < 3; row++) {
                    for (var column = 0; column < 4; column++) {
                        var inside = column >= 1 && column < 3 && row >= 1 && row < 3;
                        assertArrayEquals(inside ? new short[64] : block(in, c, column, row), block(out, c, column, row));
                    }
                }
            }
        }

        @Test
        @DisplayName("a color image drops into a gray one through its luma, and a 12-bit image into a 12-bit one")
        void colorIntoGrayAndTwelveBit() {
            var gray = JpegTransformsTest.encode(53, 37, ChromaFormat.MONOCHROME, 0);
            var color = JpegTransformsTest.encode(16, 16, ChromaFormat.YUV444, 0);
            var out = coefficients(transform(gray, jpegtran().drop(color, 8, 8).build()));
            assertEquals(1, out.frame().components().size());
            assertArrayEquals(block(coefficients(color), 0, 1, 1), block(out, 0, 2, 2));
            var source = JpegTransformsTest.twelveBit("227x149-yuv420-arithmetic-rst3.jpg");
            var image = transform(JpegTransformsTest.twelveBit("64x48-yuv420-progressive-q100.jpg"),
                    jpegtran().crop(new Region(0, 0, 32, 32)).build());
            var twelve = coefficients(transform(source, jpegtran().drop(image, 32, 16).build()));
            assertEquals(12, twelve.frame().precision());
            var dropped = coefficients(image);
            for (var k = 0; k < 64; k++) {
                assertEquals(block(dropped, 0, 0, 0)[k] * dropped.quantization(0)[k],
                        block(twelve, 0, 4, 2)[k] * twelve.quantization(0)[k]);
            }
        }

        @Test
        @DisplayName("a zero quantizer of the drop image keeps the source's quantizer and zeroes those coefficients")
        void zeroQuantizer() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0, 88);
            var image = JpegTransformsTest.encode(32, 32, ChromaFormat.YUV420, 0, 50);
            var dqt = 2;
            while (!((image[dqt] & 0xFF) == 0xFF && (image[dqt + 1] & 0xFF) == 0xDB)) {
                dqt++;
            }
            // the sixth zigzag entry of the first table, a luma AC quantizer
            image[dqt + 5 + 5] = 0;
            var in = coefficients(source);
            var dropped = coefficients(image);
            var k = 0;
            while (dropped.quantization(0)[k] != 0) {
                k++;
            }
            var out = coefficients(JpegTransforms.drop(MediaSource.of(source), MediaSource.of(image), 16, 16));
            assertEquals(in.quantization(0)[k], out.quantization(0)[k]);
            for (var row = 2; row < 6; row++) {
                for (var column = 2; column < 6; column++) {
                    assertEquals(0, block(out, 0, column, row)[k], column + "," + row);
                }
            }
            // the source keeps its values, on the common tables
            for (var i = 0; i < 64; i++) {
                assertEquals(block(in, 0, 0, 0)[i] * in.quantization(0)[i],
                        block(out, 0, 0, 0)[i] * out.quantization(0)[i], "coefficient " + i);
            }
        }

        @Test
        @DisplayName("the limits bound the size of the drop image")
        void limits() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0);
            var image = JpegTransformsTest.encode(32, 32, ChromaFormat.YUV420, 0, 60);
            // bytes after EOI are ignored by the decoder but still count as input
            var padded = Arrays.copyOf(image, 200_000);
            var limits = MediaLimits.builder().maxBytes(100_000).build();
            var options = jpegtran().limits(limits);
            transform(source, options.drop(image, 16, 16).build());
            assertThrows(MediaException.LimitExceeded.class, () -> transform(source, options.drop(padded, 16, 16)
                    .build()));
        }

        @Test
        @DisplayName("a drop that does not fit, is sampled differently or has another precision is rejected")
        void rejects() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0);
            var small = JpegTransformsTest.encode(24, 24, ChromaFormat.YUV420, 0);
            assertThrows(IllegalArgumentException.class, () -> transform(source, jpegtran().drop(small, 48, 0).build()));
            assertThrows(IllegalArgumentException.class, () -> transform(small, jpegtran().drop(source, 0, 0).build()));
            var subsampled = JpegTransformsTest.encode(24, 24, ChromaFormat.YUV422, 0);
            assertThrows(MediaException.Unsupported.class, () -> transform(source, jpegtran().drop(subsampled, 0, 0)
                    .build()));
            var twelve = transform(JpegTransformsTest.twelveBit("64x48-yuv420-progressive-q100.jpg"),
                    jpegtran().crop(new Region(0, 0, 32, 32)).build());
            assertThrows(MediaException.Unsupported.class, () -> transform(source, jpegtran().drop(twelve, 0, 0)
                    .build()));
            assertThrows(MediaException.class, () -> transform(source, jpegtran().drop(new byte[]{1, 2, 3}, 0, 0)
                    .build()));
        }
    }

    // the restart interval in effect for each scan (the last DRI before its SOS), with the scan's component count
    private static List<int[]> scanIntervals(byte[] jpeg) {
        var result = new ArrayList<int[]>();
        var interval = 0;
        var position = 2;
        while (position + 4 <= jpeg.length) {
            if ((jpeg[position] & 0xFF) != 0xFF) {
                position++;
                continue;
            }
            var marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xD9) {
                break;
            }
            if (marker == 0 || marker == 0xFF || marker >= 0xD0 && marker <= 0xD7) {
                position += marker == 0xFF ? 1 : 2;
                continue;
            }
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            if (marker == 0xDD) {
                interval = ((jpeg[position + 4] & 0xFF) << 8) | (jpeg[position + 5] & 0xFF);
            }
            if (marker == 0xDA) {
                result.add(new int[]{interval, jpeg[position + 4] & 0xFF});
            }
            position += 2 + length;
        }
        return result;
    }

    private static int restartMarkers(byte[] jpeg) {
        var count = 0;
        for (var i = 0; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) >= 0xD0 && (jpeg[i + 1] & 0xFF) <= 0xD7) {
                count++;
            }
        }
        return count;
    }

    private static int sofMarker(byte[] jpeg) {
        for (var i = 2; i + 1 < jpeg.length; i++) {
            var marker = jpeg[i + 1] & 0xFF;
            if ((jpeg[i] & 0xFF) == 0xFF && marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
                    && marker != 0xCC) {
                return marker;
            }
        }
        throw new AssertionError("no SOF");
    }

    @Nested
    @DisplayName("restart rows")
    class RestartRows {
        @Test
        @DisplayName("set each scan's interval to that many rows of its own MCUs")
        void perScan() {
            var source = JpegTransformsTest.encode(61, 47, ChromaFormat.YUV420, 0);
            var sequential = transform(source, jpegtran().restartRows(1).build());
            var intervals = scanIntervals(sequential);
            assertEquals(1, intervals.size());
            assertEquals(4, intervals.getFirst()[0], "61 pixels are 4 MCUs of 16");
            assertEquals(2, restartMarkers(sequential), "3 MCU rows give 2 restart markers");
            var progressive = transform(source, jpegtran().restartRows(2).progressive(true).build());
            for (var scan : scanIntervals(progressive)) {
                // interleaved scans count 4 MCUs per row; single-component scans count blocks: 8 luma, 4 chroma
                assertTrue(scan[1] == 3 ? scan[0] == 8 : scan[0] == 8 || scan[0] == 16, Arrays.toString(scan));
            }
            var widths = scanIntervals(progressive).stream().map(scan -> scan[0]).distinct().sorted().toList();
            assertEquals(List.of(8, 16), widths, "luma AC scans count 8 blocks per row, chroma and DC 4 per row");
            var capped = transform(source, jpegtran().restartRows(65535).build());
            assertEquals(65535, scanIntervals(capped).getFirst()[0]);
        }

        @Test
        @DisplayName("restart rows and restart intervals reset each other, and both decode to the same coefficients")
        void reset() {
            var options = JpegTransformOptions.builder().restartRows(3).restartInterval(7).build();
            assertEquals(7, options.restartInterval());
            assertEquals(0, options.restartRows());
            options = options.toBuilder().restartRows(2).build();
            assertEquals(0, options.restartInterval());
            assertEquals(2, options.restartRows());
            // jpegtran -restart 7B -restart 0 keeps the interval of 7 MCUs; -restart 2 -restart 0B clears both
            options = options.toBuilder().restartInterval(7).restartRows(0).build();
            assertEquals(7, options.restartInterval());
            assertEquals(0, options.restartRows());
            options = options.toBuilder().restartRows(2).restartInterval(0).build();
            assertEquals(0, options.restartInterval());
            assertEquals(0, options.restartRows());
            assertThrows(IllegalArgumentException.class, () -> JpegTransformOptions.builder().restartRows(65536).build());
            assertThrows(IllegalArgumentException.class, () -> JpegTransformOptions.builder().restartRows(-1).build());
            var source = JpegTransformsTest.encode(61, 47, ChromaFormat.YUV422, 0);
            var rows = coefficients(transform(source, jpegtran().restartRows(1).arithmetic(true).build()));
            var plain = coefficients(source);
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(plain.coefficients(c), rows.coefficients(c));
            }
        }
    }

    @Nested
    @DisplayName("arithmetic output")
    class Arithmetic {
        static Stream<Arguments> sources() {
            return Stream.of(
                    Arguments.of("420", JpegTransformsTest.encode(61, 47, ChromaFormat.YUV420, 0)),
                    Arguments.of("gray", JpegTransformsTest.encode(53, 37, ChromaFormat.MONOCHROME, 3)),
                    Arguments.of("12-bit 422 progressive",
                            JpegTransformsTest.twelveBit("33x65-yuv422-progressive-arithmetic.jpg")),
                    Arguments.of("12-bit rgb", JpegTransformsTest.twelveBit("227x149-rgb-progressive.jpg")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("sources")
        @DisplayName("codes the same coefficients with SOF9 or SOF10")
        void lossless(String name, byte[] source) {
            var in = coefficients(source);
            for (var progressive : List.of(false, true)) {
                var output = transform(source, jpegtran().arithmetic(true).progressive(progressive).build());
                assertEquals(progressive ? 0xCA : 0xC9, sofMarker(output));
                var out = coefficients(output);
                assertEquals(in.frame().precision(), out.frame().precision());
                for (var c = 0; c < in.frame().components().size(); c++) {
                    assertArrayEquals(in.coefficients(c), out.coefficients(c));
                }
                var back = transform(output, jpegtran().build());
                assertArrayEquals(transform(source, jpegtran().build()), back, "and back to Huffman coding");
            }
        }

        @Test
        @DisplayName("decodes to the pixels of the source")
        void pixels() {
            var source = JpegTransformsTest.encode(61, 47, ChromaFormat.YUV420, 0);
            var output = transform(source, JpegTransformOptions.builder().arithmetic(true).build());
            var a = (YuvPicture) JpegDecoder.decode(MediaSource.of(source), ImageDecoderOptions.defaults()).picture();
            var b = (YuvPicture) JpegDecoder.decode(MediaSource.of(output), ImageDecoderOptions.defaults()).picture();
            assertArrayEquals(JpegTestSupport.planar(a), JpegTestSupport.planar(b));
            assertTrue(output.length < transform(source, JpegTransformOptions.defaults()).length,
                    "arithmetic coding beats optimized Huffman tables");
        }
    }

    static Stream<Arguments> drops() {
        var cases = new ArrayList<Arguments>();
        var q88 = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0, 88);
        var source422 = JpegTransformsTest.encode(75, 33, ChromaFormat.YUV422, 5, 88);
        var source444 = JpegTransformsTest.encode(41, 29, ChromaFormat.YUV444, 0, 75);
        var sourceGray = JpegTransformsTest.encode(53, 37, ChromaFormat.MONOCHROME, 0, 90);
        var odd = JpegTransformsTest.encode(61, 47, ChromaFormat.YUV420, 0, 88);
        var twelve = JpegTransformsTest.twelveBit("227x149-yuv420-arithmetic-rst3.jpg");
        var drop420 = JpegtranSwitches.drop("420 q50", JpegTransformsTest.encode(40, 33, ChromaFormat.YUV420, 0, 50));
        var drop420same = JpegtranSwitches.drop("420 q88", JpegTransformsTest.encode(29, 15, ChromaFormat.YUV420, 0, 88));
        var drop422 = JpegtranSwitches.drop("422 q30", JpegTransformsTest.encode(37, 19, ChromaFormat.YUV422, 0, 30));
        var drop444 = JpegtranSwitches.drop("444 q95", JpegTransformsTest.encode(20, 17, ChromaFormat.YUV444, 0, 95));
        var dropGray = JpegtranSwitches.drop("gray q60", JpegTransformsTest.encode(30, 21, ChromaFormat.MONOCHROME, 0, 60));
        var drop12 = JpegtranSwitches.drop("12-bit 420 q100", JpegTransformsTest.twelveBit("64x48-yuv420-progressive-q100.jpg"));
        var sources = List.of(
                new Object[]{"420 64x48", q88, List.of(drop420, drop420same, drop422, dropGray)},
                new Object[]{"420 61x47", odd, List.of(drop420, drop420same)},
                new Object[]{"422 75x33", source422, List.of(drop422, dropGray, drop420)},
                new Object[]{"444 41x29", source444, List.of(drop444, dropGray)},
                new Object[]{"gray 53x37", sourceGray, List.of(dropGray, drop420, drop444)},
                new Object[]{"12-bit 227x149", twelve, List.of(drop12)});
        var extra = List.of(List.<String>of(), List.of("-trim"), List.of("-progressive", "-arithmetic"),
                List.of("-grayscale", "-optimize"), List.of("-restart", "1", "-trim"));
        for (var source : sources) {
            var bytes = (byte[]) source[1];
            var info = JpegDecoder.probe(MediaSource.of(bytes));
            @SuppressWarnings("unchecked")
            var images = (List<String>) source[2];
            for (var image : images) {
                var size = JpegDecoder.probe(MediaSource.of(JpegtranSwitches.dropImage(image)));
                var offsets = new ArrayList<String>(List.of("+0+0", "+5+3", "+16+16", "+17+1"));
                offsets.add("+" + (info.width() - size.width()) + "+" + (info.height() - size.height()));
                offsets.add("+" + (info.width() - size.width() + 1) + "+0");
                for (var offset : offsets) {
                    for (var more : extra) {
                        var switches = new ArrayList<>(List.of("-drop", offset, image));
                        switches.addAll(more);
                        cases.add(Arguments.of(source[0] + " " + String.join(" ", switches), bytes, switches));
                    }
                }
            }
        }
        return cases.stream();
    }

    // jpegtran cannot read a gray drop image in the MCU rows of a vertically subsampled image, which Smalt can
    // (Drop.grayIntoColor checks the result): for such a drop, runs Smalt and returns true
    static boolean jpegtranCannotDrop(byte[] source, List<String> switches) {
        var at = switches.indexOf("-drop");
        if (at < 0) {
            return false;
        }
        var frame = coefficients(source).frame();
        var drop = coefficients(JpegtranSwitches.dropImage(switches.get(at + 2))).frame();
        if (frame.vMax() == 1 || drop.components().size() != 1 || switches.contains("-grayscale")) {
            return false;
        }
        try {
            var output = JpegTransforms.transform(MediaSource.of(source), JpegtranSwitches.options(switches));
            assertEquals(frame.components().size(), coefficients(output).frame().components().size());
        } catch (IllegalArgumentException outside) {
            // the drop image does not fit at this offset
        }
        return true;
    }

    @Nested
    @DisplayName("drop against jpegtran")
    class DropJpegtran {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegTransformEditsTest#drops")
        @DisplayName("writes the same bytes as jpegtran")
        void identical(String name, byte[] source, List<String> switches) {
            Oracle.assume(Oracle.Tool.JPEGTRAN);
            byte[] expected;
            try {
                expected = JpegTransformsTest.run("jpegtran", switches, source);
            } catch (JpegTransformsTest.ToolFailure failure) {
                if (jpegtranCannotDrop(source, switches)) {
                    return;
                }
                var thrown = assertThrows(RuntimeException.class,
                        () -> JpegTransforms.transform(MediaSource.of(source), JpegtranSwitches.options(switches)));
                assertTrue(thrown instanceof MediaException || thrown instanceof IllegalArgumentException, thrown.toString());
                return;
            }
            var actual = JpegTransforms.transform(MediaSource.of(source), JpegtranSwitches.options(switches));
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError(name + ": " + actual.length + " bytes instead of " + expected.length
                        + ", first difference at " + Arrays.mismatch(expected, actual));
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("allow one of a crop, a wipe and a drop, and no wipe or drop under a transform")
        void exclusive() {
            var region = new Region(0, 0, 8, 8);
            var image = new byte[]{1};
            assertThrows(IllegalArgumentException.class, () -> JpegTransformOptions.builder().crop(region).wipe(region)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> JpegTransformOptions.builder().crop(region)
                    .drop(image, 0, 0).build());
            assertThrows(IllegalArgumentException.class, () -> JpegTransformOptions.builder().wipe(region)
                    .drop(image, 0, 0).build());
            assertThrows(IllegalArgumentException.class, () -> JpegTransformOptions.builder().wipe(region)
                    .transform(Orientation.ROTATE_90).build());
            assertThrows(IllegalArgumentException.class, () -> JpegTransformOptions.builder().drop(image, 0, 0)
                    .transform(Orientation.FLIP_VERTICAL).build());
            assertThrows(IllegalArgumentException.class, () -> JpegTransformOptions.builder().drop(image, -1, 0)
                    .build());
            JpegTransformOptions.builder().crop(region).transform(Orientation.ROTATE_90).build();
            var cleared = JpegTransformOptions.builder().wipe(region).noWipe().drop(image, 3, 4).noDrop().crop(region)
                    .build();
            assertTrue(cleared.wipe().isEmpty());
            assertTrue(cleared.dropImage().isEmpty());
            assertEquals(0, cleared.dropX());
        }

        @Test
        @DisplayName("auto-orientation wipes, drops and extends an upright image and rejects them for a turned one")
        void autoOrient() {
            var image = JpegTransformsTest.encode(32, 16, ChromaFormat.YUV420, 0, 60);
            var wipe = JpegTransformOptions.builder().wipe(new Region(0, 0, 16, 16)).build();
            var drop = JpegTransformOptions.builder().drop(image, 16, 16).build();
            var extend = JpegTransformOptions.builder().crop(new Region(16, 0, 96, 34)).build();
            var upright = JpegTransformsTest.withExif(JpegTransformsTest.encode(50, 34, ChromaFormat.YUV420, 0), 1,
                    false);
            for (var options : List.of(wipe, drop, extend)) {
                var oriented = JpegTransforms.autoOrient(MediaSource.of(upright), options);
                assertArrayEquals(transform(upright, options.toBuilder().resetOrientation(true).build()), oriented);
            }
            var turned = JpegTransformsTest.withExif(JpegTransformsTest.encode(50, 34, ChromaFormat.YUV420, 0), 6,
                    false);
            for (var options : List.of(wipe, drop, extend)) {
                assertThrows(IllegalArgumentException.class, () -> JpegTransforms.autoOrient(MediaSource.of(turned),
                        options), options.toString());
            }
        }

        @Test
        @DisplayName("copy the drop image and compare it by content")
        void dropCopies() {
            var image = new byte[]{1, 2, 3};
            var options = JpegTransformOptions.builder().drop(image, 8, 16).build();
            image[0] = 9;
            assertArrayEquals(new byte[]{1, 2, 3}, options.dropImage().orElseThrow());
            options.dropImage().orElseThrow()[1] = 9;
            assertArrayEquals(new byte[]{1, 2, 3}, options.dropImage().orElseThrow());
            var same = JpegTransformOptions.builder().drop(new byte[]{1, 2, 3}, 8, 16).build();
            assertEquals(options, same);
            assertEquals(options.hashCode(), same.hashCode());
            assertNotEquals(options, same.toBuilder().drop(new byte[]{1, 2, 4}, 8, 16).build());
            assertNotEquals(options, same.toBuilder().requantizeDrop(true).build());
            assertTrue(options.toString().contains("3 bytes at 8,16"), options.toString());
            assertEquals(options, options.toBuilder().build());
        }

        @Test
        @DisplayName("default to gray fills, no exact crop, Huffman coding and no restart rows")
        void defaults() {
            var options = JpegTransformOptions.defaults();
            assertEquals(JpegFill.GRAY, options.fill());
            assertFalse(options.exactCropWidth());
            assertFalse(options.exactCropHeight());
            assertFalse(options.arithmetic());
            assertFalse(options.requantizeDrop());
            assertEquals(0, options.restartRows());
            assertTrue(options.wipe().isEmpty());
            for (var fill : JpegFill.values()) {
                var changed = options.toBuilder().fill(fill).exactCropHeight(true).build();
                assertEquals(fill, changed.fill());
                assertEquals(changed, changed.toBuilder().build());
            }
            assertNotEquals(options, options.toBuilder().arithmetic(true).build());
            assertNotEquals(options, options.toBuilder().exactCropWidth(true).build());
            assertNotEquals(options, options.toBuilder().restartRows(1).build());
        }

        @Test
        @DisplayName("the conveniences transform under the defaults")
        void conveniences() {
            var source = JpegTransformsTest.encode(64, 48, ChromaFormat.YUV420, 0);
            var image = JpegTransformsTest.encode(32, 32, ChromaFormat.YUV420, 0, 60);
            var region = new Region(3, 4, 30, 20);
            assertArrayEquals(transform(source, JpegTransformOptions.builder().wipe(region).build()),
                    JpegTransforms.wipe(MediaSource.of(source), region));
            assertArrayEquals(transform(source, JpegTransformOptions.builder().drop(image, 16, 16).build()),
                    JpegTransforms.drop(MediaSource.of(source), MediaSource.of(image), 16, 16));
            assertThrows(IllegalArgumentException.class, () -> JpegTransforms.drop(MediaSource.of(source),
                    MediaSource.of(image), -1, 0));
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        @DisplayName("mutated sources and drop images fail only with media or argument exceptions")
        void mutations() {
            var base = JpegTransformsTest.encode(48, 40, ChromaFormat.YUV420, 2);
            var image = JpegTransformsTest.encode(24, 24, ChromaFormat.YUV420, 0, 50);
            var random = new Random(41);
            var options = List.of(
                    JpegTransformOptions.builder().wipe(new Region(5, 5, 20, 20)).fill(JpegFill.FLAT).build(),
                    JpegTransformOptions.builder().wipe(new Region(0, 0, 16, 40)).fill(JpegFill.REFLECT)
                            .arithmetic(true).build(),
                    JpegTransformOptions.builder().crop(new Region(16, 8, 90, 70)).fill(JpegFill.REFLECT)
                            .restartRows(1).build(),
                    JpegTransformOptions.builder().drop(image, 8, 8).progressive(true).build(),
                    JpegTransformOptions.builder().drop(image, 8, 8).requantizeDrop(true).arithmetic(true).build());
            for (var round = 0; round < 300; round++) {
                var data = base.clone();
                var flips = 1 + random.nextInt(8);
                for (var i = 0; i < flips; i++) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                }
                var option = options.get(round % options.size());
                if (round % 3 == 0 && option.dropImage().isPresent()) {
                    option = option.toBuilder().drop(data, 8, 8).build();
                    data = base;
                }
                var drop = option.dropImage().orElse(base);
                try {
                    coefficients(data);
                    coefficients(drop);
                } catch (RuntimeException unreadable) {
                    // the reader's own robustness is tested with the decoder; this checks the transform code
                    continue;
                }
                try {
                    // the transcoder directly, so that a failure of the transform code is not hidden behind the
                    // public API's translation of unexpected exceptions into MediaException.Malformed
                    var output = JpegTranscoder.transcode(data, option);
                    JpegDecoder.decode(MediaSource.of(output), ImageDecoderOptions.defaults());
                } catch (MediaException | IllegalArgumentException expected) {
                    // hostile input is reported as a media exception, or as a drop that no longer fits
                }
            }
        }
    }
}
