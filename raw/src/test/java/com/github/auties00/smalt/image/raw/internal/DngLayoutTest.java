package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaLayout;
import com.github.auties00.smalt.image.raw.CfaPattern;
import com.github.auties00.smalt.image.raw.DngBuilder;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDemosaic;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the stored layouts of DNG sensor data on synthetic files: integer samples of 17 to 32 bits,
 * separately stored planes, sub-tile blocks and interleaved rows and columns decode to exactly the samples
 * written, and the staggered CFA layouts demosaic photosites at their physical places.
 */
class DngLayoutTest {
    static DngBuilder base(int width, int height, int samples, int bits) {
        var builder = new DngBuilder();
        var dir = builder.first;
        dir.longs(254, 0).longs(256, width).longs(257, height);
        var depths = new int[samples];
        java.util.Arrays.fill(depths, bits);
        dir.shorts(258, depths).shorts(259, 1).shorts(277, samples);
        dir.shorts(262, samples == 1 ? 32803 : 34892);
        if (samples == 1) {
            dir.shorts(33421, 2, 2).bytes(33422, 0, 1, 1, 2);
        }
        dir.bytes(50706, 1, 7, 1, 0).signedRationals(50721, 1, 0, 0, 0, 1, 0, 0, 0, 1).rationals(50728, 1, 1, 1)
                .shorts(50778, 21).ascii(271, "Smalt").ascii(272, "Layout");
        return builder;
    }

    static long[] random(int count, int bits, long seed) {
        var random = new Random(seed);
        var values = new long[count];
        var mask = bits == 32 ? 0xFFFFFFFFL : (1L << bits) - 1;
        for (var index = 0; index < count; index++) {
            values[index] = random.nextLong() & mask;
        }
        return values;
    }

    static SensorPlane read(byte[] dng) {
        var file = RawParser.parse(MediaSource.of(dng), MediaLimits.DEFAULT);
        assertTrue(file.sensor != null, file.unsupported);
        var plane = SensorPlane.of(file.sensor, MediaLimits.DEFAULT);
        file.sensor.reader.read(plane, MediaLimits.DEFAULT);
        return plane;
    }

    static long[] values(SensorPlane plane) {
        var result = new long[plane.length()];
        for (var index = 0; index < result.length; index++) {
            result[index] = plane.integer(index);
        }
        return result;
    }

    // the stored bytes of rows of samples: words in the file's order for 16 and 32 bits, packed bits otherwise
    static byte[] encode(long[] samples, int perRow, int bits, ByteOrder order) {
        return bits == 16 || bits == 32 ? DngBuilder.words(samples, bits, order) : DngBuilder.packMsb(samples, perRow, bits);
    }

    // horizontal differences modulo the sample range, the inverse of TIFF predictor 2
    static long[] differences(long[] samples, int perRow, int distance, int bits) {
        var mask = bits == 32 ? 0xFFFFFFFFL : (1L << bits) - 1;
        var result = samples.clone();
        for (var start = 0; start < samples.length; start += perRow) {
            for (var index = perRow - 1; index >= distance; index--) {
                result[start + index] = (samples[start + index] - samples[start + index - distance]) & mask;
            }
        }
        return result;
    }

    @Nested
    @DisplayName("integer samples of more than 16 bits")
    class WideSamples {
        @ParameterizedTest(name = "{0} bits, compression {1}, predictor {2}, little endian {3}")
        @CsvSource({"17, 1, 1, true", "20, 1, 1, true", "24, 1, 1, false", "31, 1, 1, true", "32, 1, 1, true",
                "32, 1, 1, false", "32, 8, 1, true", "32, 8, 2, false", "24, 8, 2, true", "18, 8, 34892, true",
                "32, 8, 34893, true"})
        @DisplayName("decode to exactly the stored values")
        void exact(int bits, int compression, int predictor, boolean little) {
            var width = 24;
            var height = 10;
            var samples = random(width * height, bits, bits * 31L + compression);
            var order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            var distance = predictor == 34892 ? 2 : predictor == 34893 ? 4 : 1;
            var stored = predictor == 1 ? samples : differences(samples, width, distance, bits);
            var data = encode(stored, width, bits, order);
            if (compression == 8) {
                data = DngBuilder.deflate(data);
            }
            var builder = new DngBuilder(little, 42);
            var dir = builder.first;
            dir.longs(254, 0).longs(256, width).longs(257, height).shorts(258, bits).shorts(259, compression)
                    .shorts(262, 32803).shorts(277, 1).shorts(33421, 2, 2).bytes(33422, 0, 1, 1, 2)
                    .bytes(50706, 1, 7, 1, 0).signedRationals(50721, 1, 0, 0, 0, 1, 0, 0, 0, 1).shorts(50778, 21)
                    .longs(50717, bits == 32 ? 0xFFFFFFFFL : (1L << bits) - 1);
            if (predictor != 1) {
                dir.shorts(317, predictor);
            }
            dir.strips(height, data);
            var plane = read(builder.write());
            assertTrue(plane.wide != null);
            assertArrayEquals(samples, values(plane));
        }

        @Test
        @DisplayName("normalize to 16 bits by the white level, through the linearization table when there is one")
        void linear() {
            var width = 8;
            var height = 4;
            var samples = new long[width * height];
            for (var index = 0; index < samples.length; index++) {
                samples[index] = (long) index * 500009L;
            }
            var builder = base(width, height, 1, 24);
            builder.first.longs(50717, 31 * 500009L).strips(height, DngBuilder.packMsb(samples, width, 24));
            var raw = RawDecoder.decodeRaw(MediaSource.of(builder.write()));
            for (var index = 0; index < samples.length; index++) {
                var expected = Math.round(Math.min(1.0, samples[index] / (31.0 * 500009)) * 65535);
                assertEquals(expected, raw.samples()[index] & 0xFFFF, 1, "sample " + index);
            }
            // a table of 16 entries maps every stored value above 15 to its last entry, sample 5 among them
            var table = new int[16];
            for (var index = 0; index < 16; index++) {
                table[index] = index * 4000;
            }
            builder.first.shorts(50712, table).longs(50717, 60000);
            var mapped = RawDecoder.decodeRaw(MediaSource.of(builder.write()));
            assertEquals(0, mapped.samples()[0] & 0xFFFF);
            assertEquals(Math.round(60000.0 / 60000 * 65535), mapped.samples()[5] & 0xFFFF);
        }

        @Test
        @DisplayName("only a reader of more than 16-bit samples gets a plane of 32-bit integers")
        void storage() {
            var sensor = new Sensor();
            sensor.width = 4;
            sensor.height = 2;
            sensor.bitsPerSample = 24;
            // the vendor parsers that write their own samples never set the flag, whatever bit depth a file declares
            var plane = SensorPlane.of(sensor, MediaLimits.DEFAULT);
            assertTrue(plane.integers != null && plane.wide == null);
            sensor.wide = true;
            plane = SensorPlane.of(sensor, MediaLimits.DEFAULT);
            assertTrue(plane.integers == null && plane.wide != null);
            sensor.bitsPerSample = 16;
            assertTrue(SensorPlane.of(sensor, MediaLimits.DEFAULT).integers != null);
        }

        @Test
        @DisplayName("lossless JPEG of more than 16 bits is unsupported")
        void losslessJpeg() {
            var builder = base(8, 8, 1, 20);
            builder.first.shorts(259, 7).strips(8, new byte[64]);
            var info = RawDecoder.info(MediaSource.of(builder.write()));
            assertFalse(info.isDecodable());
        }
    }

    @Nested
    @DisplayName("separately stored planes")
    class Planar {
        @ParameterizedTest(name = "{0} bits, compression {1}, predictor {2}")
        @CsvSource({"16, 1, 1", "12, 1, 1", "24, 1, 1", "16, 8, 2", "32, 8, 2", "8, 8, 1"})
        @DisplayName("of a three-sample linear raw image decode to the interleaved samples")
        void linearRaw(int bits, int compression, int predictor) {
            var width = 12;
            var height = 6;
            var samples = random(width * height * 3, bits, bits + 7L * compression);
            var builder = base(width, height, 3, bits);
            builder.first.shorts(284, 2).shorts(259, compression);
            if (predictor != 1) {
                builder.first.shorts(317, predictor);
            }
            // two strips of three rows for each plane
            var strips = new byte[6][];
            for (var plane = 0; plane < 3; plane++) {
                for (var strip = 0; strip < 2; strip++) {
                    var part = new long[width * 3];
                    for (var row = 0; row < 3; row++) {
                        for (var x = 0; x < width; x++) {
                            part[row * width + x] = samples[((strip * 3 + row) * width + x) * 3 + plane];
                        }
                    }
                    var stored = predictor == 1 ? part : differences(part, width, 1, bits);
                    var data = encode(stored, width, bits, ByteOrder.LITTLE_ENDIAN);
                    strips[plane * 2 + strip] = compression == 8 ? DngBuilder.deflate(data) : data;
                }
            }
            builder.first.strips(3, strips);
            assertArrayEquals(samples, values(read(builder.write())));
        }

        @Test
        @DisplayName("fewer blocks than the planes need are malformed")
        void missingPlane() {
            var builder = base(4, 4, 3, 16);
            builder.first.shorts(284, 2).strips(4, new byte[32], new byte[32]);
            assertThrows(MediaException.Malformed.class, () -> RawDecoder.info(MediaSource.of(builder.write())));
        }
    }

    @Nested
    @DisplayName("sub-tile blocks and interleaved fields")
    class Reordered {
        @ParameterizedTest(name = "blocks of {0}x{1}")
        @CsvSource({"2, 4", "4, 2", "1, 8", "8, 8", "2, 2"})
        @DisplayName("SubTileBlockSize groups the pixels of each tile in blocks")
        void subTiles(int blockRows, int blockColumns) {
            var width = 24;
            var height = 20;
            var tile = 8;
            var samples = random(width * height, 16, blockRows * 10L + blockColumns);
            var across = (width + tile - 1) / tile;
            var down = (height + tile - 1) / tile;
            var tiles = new byte[across * down][];
            for (var ty = 0; ty < down; ty++) {
                for (var tx = 0; tx < across; tx++) {
                    var stored = new long[tile * tile];
                    var at = 0;
                    for (var by = 0; by < tile / blockRows; by++) {
                        for (var bx = 0; bx < tile / blockColumns; bx++) {
                            for (var y = 0; y < blockRows; y++) {
                                for (var x = 0; x < blockColumns; x++) {
                                    var row = ty * tile + by * blockRows + y;
                                    var column = tx * tile + bx * blockColumns + x;
                                    stored[at++] = row < height && column < width ? samples[row * width + column] : 0;
                                }
                            }
                        }
                    }
                    tiles[ty * across + tx] = DngBuilder.words(stored, 16, ByteOrder.LITTLE_ENDIAN);
                }
            }
            var builder = base(width, height, 1, 16);
            builder.first.shorts(50974, blockRows, blockColumns).tiles(tile, tile, tiles);
            assertArrayEquals(samples, values(read(builder.write())));
        }

        @Test
        @DisplayName("sub-tile blocks that do not divide the tile are malformed")
        void uneven() {
            var builder = base(8, 8, 1, 16);
            builder.first.shorts(50974, 3, 2).tiles(8, 8, new byte[128]);
            assertThrows(MediaException.Malformed.class, () -> RawDecoder.info(MediaSource.of(builder.write())));
        }

        @ParameterizedTest(name = "rows {0}, columns {1}, tiled {2}")
        @CsvSource({"2, 1, false", "1, 2, false", "2, 2, false", "3, 2, true", "2, 4, true", "5, 3, false", "2, 2, true"})
        @DisplayName("RowInterleaveFactor and ColumnInterleaveFactor store the image field by field")
        void interleaved(int rowFactor, int columnFactor, boolean tiled) {
            var width = 21;
            var height = 17;
            var samples = random(width * height, 16, rowFactor * 100L + columnFactor);
            var rowOf = TiffSamples.fieldOrder(height, rowFactor);
            var columnOf = TiffSamples.fieldOrder(width, columnFactor);
            var stored = new long[width * height];
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    stored[row * width + column] = samples[rowOf[row] * width + columnOf[column]];
                }
            }
            var builder = base(width, height, 1, 16);
            builder.first.shorts(50975, rowFactor).shorts(52547, columnFactor);
            if (tiled) {
                var tile = 16;
                var across = 2;
                var down = 2;
                var tiles = new byte[across * down][];
                for (var ty = 0; ty < down; ty++) {
                    for (var tx = 0; tx < across; tx++) {
                        var part = new long[tile * tile];
                        for (var y = 0; y < tile; y++) {
                            for (var x = 0; x < tile; x++) {
                                var row = ty * tile + y;
                                var column = tx * tile + x;
                                part[y * tile + x] = row < height && column < width ? stored[row * width + column] : 0;
                            }
                        }
                        tiles[ty * across + tx] = DngBuilder.words(part, 16, ByteOrder.LITTLE_ENDIAN);
                    }
                }
                builder.first.tiles(tile, tile, tiles);
            } else {
                builder.first.strips(height, DngBuilder.words(stored, 16, ByteOrder.LITTLE_ENDIAN));
            }
            assertArrayEquals(samples, values(read(builder.write())));
        }

        @Test
        @DisplayName("each field holds every n-th line, the first field first")
        void fieldOrder() {
            assertArrayEquals(new int[]{0, 2, 4, 1, 3}, TiffSamples.fieldOrder(5, 2));
            assertArrayEquals(new int[]{0, 3, 6, 1, 4, 2, 5}, TiffSamples.fieldOrder(7, 3));
            assertArrayEquals(new int[]{0, 1, 2}, TiffSamples.fieldOrder(3, 1));
        }

        @ParameterizedTest(name = "factor {0}")
        @CsvSource({"0", "40"})
        @DisplayName("an interleave factor of 0 or beyond the image is malformed")
        void invalidFactor(int factor) {
            var builder = base(8, 8, 1, 16);
            builder.first.shorts(50975, factor).strips(8, new byte[128]);
            assertThrows(MediaException.Malformed.class, () -> RawDecoder.info(MediaSource.of(builder.write())));
        }
    }

    @Nested
    @DisplayName("staggered layouts")
    class StaggeredLayouts {
        // a 2x4 SuperCCD-like pattern of red, green and blue
        static final CfaPattern PATTERN = CfaPattern.of(4, 2, java.util.List.of(
                com.github.auties00.smalt.image.raw.CfaColor.RED, com.github.auties00.smalt.image.raw.CfaColor.GREEN,
                com.github.auties00.smalt.image.raw.CfaColor.BLUE, com.github.auties00.smalt.image.raw.CfaColor.GREEN,
                com.github.auties00.smalt.image.raw.CfaColor.BLUE, com.github.auties00.smalt.image.raw.CfaColor.GREEN,
                com.github.auties00.smalt.image.raw.CfaColor.RED, com.github.auties00.smalt.image.raw.CfaColor.GREEN));

        static double field(double x, double y) {
            return 0.3 + 0.01 * x + 0.015 * y + 0.002 * x * y / 10;
        }

        // the image of the field sampled at the photosites' physical places for a layout
        static LinearImage sampled(CfaLayout layout, int width, int height) {
            var data = new float[width * height];
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    data[row * width + column] = (float) field(column + layout.columnOffset(row, column),
                            row + layout.rowOffset(row, column));
                }
            }
            var planes = new int[8];
            for (var row = 0; row < 2; row++) {
                for (var column = 0; column < 4; column++) {
                    planes[row * 4 + column] = PATTERN.color(row, column).code();
                }
            }
            return new LinearImage(width, height, 1, data, PATTERN, planes);
        }

        static double error(LinearImage demosaiced) {
            var worst = 0.0;
            for (var row = 4; row < demosaiced.height - 4; row++) {
                for (var column = 4; column < demosaiced.width - 4; column++) {
                    for (var plane = 0; plane < 3; plane++) {
                        var value = demosaiced.data[(row * demosaiced.width + column) * 3 + plane];
                        worst = Math.max(worst, Math.abs(value - field(column, row)));
                    }
                }
            }
            return worst;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = CfaLayout.class, names = "RECTANGULAR", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("demosaicing at the physical places reproduces a smooth field, the rectangular assumption does not")
        void physicalPlaces(CfaLayout layout) {
            var image = sampled(layout, 40, 32);
            for (var algorithm : RawDemosaic.values()) {
                var right = error(Staggered.demosaic(image, layout, 1, 1, algorithm, null, false, MediaLimits.DEFAULT));
                var wrong = error(Staggered.demosaic(image, CfaLayout.RECTANGULAR, 1, 1, algorithm, null, false,
                        MediaLimits.DEFAULT));
                assertTrue(right < 0.003, layout + " " + algorithm + " error " + right);
                assertTrue(right < wrong / 2, layout + " " + algorithm + " error " + right + " against " + wrong);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = CfaLayout.class, names = "RECTANGULAR", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("the border pixels, whose stencils lose neighbors, stay within the range of the field")
        void borders(CfaLayout layout) {
            var width = 24;
            var height = 18;
            var image = sampled(layout, width, height);
            var lowest = Double.MAX_VALUE;
            var highest = -Double.MAX_VALUE;
            for (var value : image.data) {
                lowest = Math.min(lowest, value);
                highest = Math.max(highest, value);
            }
            var margin = (highest - lowest) * 0.1;
            for (var algorithm : RawDemosaic.values()) {
                var demosaiced = Staggered.demosaic(image, layout, 1, 1, algorithm, null, false, MediaLimits.DEFAULT);
                for (var row = 0; row < height; row++) {
                    for (var column = 0; column < width; column++) {
                        if (row >= 4 && row < height - 4 && column >= 4 && column < width - 4) {
                            continue;
                        }
                        for (var plane = 0; plane < 3; plane++) {
                            var value = demosaiced.data[(row * width + column) * 3 + plane];
                            assertTrue(value >= lowest - margin && value <= highest + margin, layout + " " + algorithm
                                    + " at " + column + ", " + row + ": " + value);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("a photosite on the grid keeps its own value when the layout leaves it in place")
        void ownValue() {
            var image = sampled(CfaLayout.STAGGERED_A, 16, 16);
            var demosaiced = Staggered.demosaic(image, CfaLayout.STAGGERED_A, 1, 1, RawDemosaic.BILINEAR, null, false,
                    MediaLimits.DEFAULT);
            // columns 0 and 2 (odd when counted from one) stay in place; column 0 of row 0 is red
            assertEquals(image.data[4 * 16 + 4], demosaiced.data[(4 * 16 + 4) * 3], 1e-6);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(CfaLayout.class)
        @DisplayName("shifting by an odd row or column exchanges the roles of the even and odd lines")
        void shifted(CfaLayout layout) {
            assertEquals(layout, layout.shifted(2, 4));
            for (var top = 0; top < 2; top++) {
                for (var left = 0; left < 2; left++) {
                    var shifted = layout.shifted(top, left);
                    // the offsets of the shifted layout equal those of the original, up to one translation of the grid
                    var dy = layout.rowOffset(top, left) - shifted.rowOffset(0, 0);
                    var dx = layout.columnOffset(top, left) - shifted.columnOffset(0, 0);
                    for (var row = 0; row < 4; row++) {
                        for (var column = 0; column < 4; column++) {
                            assertEquals(layout.rowOffset(row + top, column + left), shifted.rowOffset(row, column) + dy,
                                    1e-12, layout + " shifted by " + top + ", " + left);
                            assertEquals(layout.columnOffset(row + top, column + left),
                                    shifted.columnOffset(row, column) + dx, 1e-12);
                        }
                    }
                }
            }
            assertEquals(layout, CfaLayout.of(layout.code()));
        }

        @Test
        @DisplayName("an undefined CFALayout value makes the file undecodable")
        void undefined() {
            var builder = base(8, 8, 1, 16);
            builder.first.shorts(50711, 10).strips(8, new byte[128]);
            var info = RawDecoder.info(MediaSource.of(builder.write()));
            assertFalse(info.isDecodable());
            assertThrows(IllegalArgumentException.class, () -> CfaLayout.of(0));
        }
    }
}
