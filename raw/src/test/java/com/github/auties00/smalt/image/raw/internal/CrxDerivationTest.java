package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-derives, from Adobe DNG Converter's output alone, the transform and quantization model of the C-RAW decoder,
 * independently of its entropy coder: the color planes of a version 1 file, put side by side across its tiles,
 * are the integer 5/3 synthesis of subbands quantized with one step per subband, so the 5/3 analysis of the
 * converter's photosites (vertical split first, symmetric extension, the whole plane in one piece at the first
 * two levels) must give, in every subband of those levels, only multiples of the step its header's quantization
 * parameter selects, the HEVC-like scale {@code levelScale[qp % 6] << qp / 6 >> 6}; one coefficient off the model
 * fails the test. Two kinds of coefficient are left out, since the model does not cover them: those whose support
 * reaches a photosite the decoder clipped to the range of the bit depth, and those within three coefficients of a
 * tile edge, where each tile's own transform (which the photosite test checks) departs from the whole-plane one;
 * and a level is checked only when the tiles' edges fall on even columns of its input, since each tile transforms
 * its own columns from its left edge with the phase that edge gives (a 3564-photosite tile of a 90D plane, odd at
 * the second level, is checked at the first only). A
 * step of one would pass any data, so the test also requires the steps to exceed one, and most coefficients to be
 * checked. The oracle files are the ones {@code tools/testdata/raw-oracle.py} writes; the test is skipped without
 * them.
 */
class CrxDerivationTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    private static final int[] LEVEL_SCALE = {40, 45, 51, 57, 64, 72};

    // the color planes' photosites in a 2x2 cell of the RGGB layout the main images use
    private static final int[] CELLS = {0, 1, 2, 3};

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CR3_EOS_250D_C_RAW", "CR3_EOS_90D", "CR3_EOS_R", "CR3_EOS_RP",
            "CR3_EOS_M50_CRAW", "CR3_POWERSHOT_SX70_HS_C_RAW", "CR3_POWERSHOT_G5_X_MARK_II_C_RAW"})
    @DisplayName("the first two wavelet levels of the converter's photosites are multiples of the subbands' steps")
    void quantization(RawSamples sample) throws IOException {
        var name = sample.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        Skip.unless(Files.isRegularFile(dng), "data:raw-oracle", "no oracle file " + dng + "; run tools/testdata/raw-oracle.py");
        var file = Files.readAllBytes(sample.fetch());
        var header = mainImage(file);
        assertEquals(0x100, header.version, "a version 1 file");
        var oracle = NikonTableDerivation.oracle(dng);
        assertEquals(header.width, oracle.width);
        var planeWidth = header.width / 2;
        var planeHeight = header.height / 2;
        var tilePlaneWidth = header.tileWidth / 2;
        var maximum = (1 << header.bits) - 1;
        var checked = 0L;
        var total = 0L;
        for (var plane = 0; plane < 4; plane++) {
            var cell = CELLS[plane];
            var values = new long[planeWidth * planeHeight];
            // the count of clipped photosites above and left of each position, for window sums
            var clipped = new int[(planeWidth + 1) * (planeHeight + 1)];
            for (var y = 0; y < planeHeight; y++) {
                for (var x = 0; x < planeWidth; x++) {
                    var value = oracle.integers[(2 * y + (cell >> 1)) * oracle.width + 2 * x + (cell & 1)] & 0xFFFF;
                    values[y * planeWidth + x] = value;
                    clipped[(y + 1) * (planeWidth + 1) + x + 1] = (value == 0 || value == maximum ? 1 : 0)
                            + clipped[y * (planeWidth + 1) + x + 1] + clipped[(y + 1) * (planeWidth + 1) + x]
                            - clipped[y * (planeWidth + 1) + x];
                }
            }
            var width = planeWidth;
            var height = planeHeight;
            for (var level = 1; level <= 2; level++) {
                var lowRows = (height + 1) / 2;
                var lowColumns = (width + 1) / 2;
                var split = new long[width * height];
                var column = new long[height];
                for (var x = 0; x < width; x++) {
                    for (var y = 0; y < height; y++) {
                        column[y] = values[y * width + x];
                    }
                    var out = analysis(column);
                    for (var y = 0; y < height; y++) {
                        split[y * width + x] = out[y];
                    }
                }
                var row = new long[width];
                for (var y = 0; y < height; y++) {
                    System.arraycopy(split, y * width, row, 0, width);
                    System.arraycopy(analysis(row), 0, split, y * width, width);
                }
                // the subbands of the level in coding order: HL (high columns of the low rows), LH, HH; a level
                // whose tiles start at an odd input column has another phase in each tile than in the whole plane
                var first = 1 + 3 * (3 - level);
                var phased = tilePlaneWidth == planeWidth || tilePlaneWidth % (1 << level) == 0;
                for (var band = 0; band < 3 && phased; band++) {
                    var step = step(header.quantizers[plane][first + band]);
                    assertTrue(step > 1, "the subband is quantized");
                    var rows0 = band == 0 ? 0 : lowRows;
                    var rows1 = band == 0 ? lowRows : height;
                    var columns0 = band == 1 ? 0 : lowColumns;
                    var columns1 = band == 1 ? lowColumns : width;
                    for (var y = rows0; y < rows1; y++) {
                        for (var x = columns0; x < columns1; x++) {
                            total++;
                            var bx = x - columns0;
                            var by = y - rows0;
                            if (nearTileEdge(bx, level, tilePlaneWidth, planeWidth)
                                    || clipped(clipped, planeWidth, planeHeight, bx << level, by << level, 3 << level)) {
                                continue;
                            }
                            checked++;
                            var coefficient = split[y * width + x];
                            if (coefficient % step != 0) {
                                throw new AssertionError(sample + " plane " + plane + " level " + level + " subband " + band
                                        + ": coefficient " + coefficient + " at " + (x - columns0) + "," + (y - rows0)
                                        + " is not a multiple of the step " + step);
                            }
                        }
                    }
                }
                var low = new long[lowColumns * lowRows];
                for (var y = 0; y < lowRows; y++) {
                    System.arraycopy(split, y * width, low, y * lowColumns, lowColumns);
                }
                values = low;
                width = lowColumns;
                height = lowRows;
            }
        }
        assertTrue(checked > total * 9 / 10, "only " + checked + " of " + total + " coefficients checked");
    }

    // whether a coefficient of a level lies within three coefficients of an edge between two tiles
    private static boolean nearTileEdge(int x, int level, int tileWidth, int planeWidth) {
        for (var edge = tileWidth; edge < planeWidth; edge += tileWidth) {
            if (Math.abs(x - (edge >> level)) <= 3) {
                return true;
            }
        }
        return false;
    }

    // whether a window around a position holds a clipped photosite
    private static boolean clipped(int[] counts, int width, int height, int x, int y, int radius) {
        var x0 = Math.clamp(x - radius, 0, width);
        var x1 = Math.clamp(x + radius + 1, 0, width);
        var y0 = Math.clamp(y - radius, 0, height);
        var y1 = Math.clamp(y + radius + 1, 0, height);
        var stride = width + 1;
        return counts[y1 * stride + x1] - counts[y0 * stride + x1] - counts[y1 * stride + x0] + counts[y0 * stride + x0] > 0;
    }

    private static long step(int quantizer) {
        return ((long) LEVEL_SCALE[quantizer % 6] << (quantizer / 6)) >> 6;
    }

    // the reversible 5/3 analysis with symmetric extension, lows then highs
    private static long[] analysis(long[] x) {
        var n = x.length;
        var lows = (n + 1) / 2;
        var highs = n / 2;
        var d = new long[highs];
        for (var i = 0; i < highs; i++) {
            var right = 2 * i + 2 < n ? x[2 * i + 2] : x[2 * i];
            d[i] = x[2 * i + 1] - Math.floorDiv(x[2 * i] + right, 2);
        }
        var out = new long[n];
        for (var i = 0; i < lows; i++) {
            var left = i > 0 ? d[i - 1] : d[0];
            var right = i < highs ? d[i] : d[highs - 1];
            out[i] = x[2 * i] + Math.floorDiv(left + right + 2, 4);
        }
        System.arraycopy(d, 0, out, lows, highs);
        return out;
    }

    private record Header(int version, int width, int height, int tileWidth, int bits, int[][] quantizers) {
    }

    // the largest CRX image: its CMP1 description and the quantization parameters of the first tile's subbands
    private static Header mainImage(byte[] file) {
        var best = new long[3];
        walk(file, 0, file.length, best, new long[3]);
        var c = (int) best[0];
        var buffer = ByteBuffer.wrap(file);
        var version = buffer.getShort(c + 4) & 0xFFFF;
        var width = buffer.getInt(c + 8);
        var height = buffer.getInt(c + 12);
        var tileWidth = buffer.getInt(c + 16);
        var bits = file[c + 24] & 0xFF;
        var at = (int) best[2];
        var quantizers = new int[4][10];
        var plane = -1;
        var band = 0;
        while (plane < 4) {
            var marker = buffer.getShort(at) & 0xFFFF;
            var size = buffer.getShort(at + 2) & 0xFFFF;
            if (marker == 0xFF02) {
                if (++plane == 4) {
                    break;
                }
                band = 0;
            } else if (marker == 0xFF03) {
                quantizers[plane][band++] = (buffer.getInt(at + 8) >>> 19) & 0xFF;
            } else if (marker != 0xFF01 || plane >= 0) {
                break;
            }
            at += 4 + size;
        }
        return new Header(version, width, height, tileWidth, bits, quantizers);
    }

    private static void walk(byte[] file, long start, long end, long[] best, long[] track) {
        var buffer = ByteBuffer.wrap(file);
        var position = start;
        while (position + 8 <= end) {
            long size = buffer.getInt((int) position) & 0xFFFFFFFFL;
            var type = new String(file, (int) position + 4, 4, StandardCharsets.ISO_8859_1);
            var body = position + 8;
            if (size == 1) {
                size = buffer.getLong((int) position + 8);
                body += 8;
            }
            if (size < 8) {
                return;
            }
            switch (type) {
                case "moov", "mdia", "minf", "stbl" -> walk(file, body, position + size, best, track);
                case "trak" -> {
                    var found = new long[3];
                    walk(file, body, position + size, best, found);
                    if (found[0] > 0 && (best[0] == 0 || buffer.getInt((int) found[0] + 8) > buffer.getInt((int) best[0] + 8))) {
                        System.arraycopy(found, 0, best, 0, 3);
                    }
                }
                case "stsd" -> {
                    var entry = body + 8;
                    if (new String(file, (int) entry + 4, 4, StandardCharsets.ISO_8859_1).equals("CRAW")) {
                        var entryEnd = entry + (buffer.getInt((int) entry) & 0xFFFFFFFFL);
                        for (var p = entry + 0x5A; p + 8 <= entryEnd; ) {
                            var childSize = buffer.getInt((int) p) & 0xFFFFFFFFL;
                            if (new String(file, (int) p + 4, 4, StandardCharsets.ISO_8859_1).equals("CMP1")) {
                                track[0] = p + 8;
                            }
                            if (childSize < 8) {
                                break;
                            }
                            p += childSize;
                        }
                    }
                }
                case "co64" -> track[2] = buffer.getLong((int) body + 8);
                default -> {
                }
            }
            position += size;
        }
    }
}
