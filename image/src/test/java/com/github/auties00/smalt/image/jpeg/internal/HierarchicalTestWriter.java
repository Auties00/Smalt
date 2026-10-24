package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Writes hierarchical JPEG streams for the tests, as the encoder of ITU-T T.81 annex J (figure J.1): the source is
 * downsampled into a pyramid by 2x2 averaging, the smallest level is coded in a non-differential frame and every
 * larger one in a differential frame of the differences between the level and the upsampled reconstruction of the
 * level below (J.1.1.2, written here independently of the decoder), each differential frame preceded by an EXP
 * segment. DCT frames are coded with a floating-point forward DCT, the differential ones without the level shift
 * and with their DC coefficients unpredicted (J.1.3.1); lossless differential frames code the differences modulo
 * {@code 2^16} without prediction (J.1.3.2). The writer returns the reconstruction a decoder must produce, which it
 * tracks as the encoder does: the decode of the first frame as a plain JPEG stream, then each differential frame's
 * reconstruction added to the upsampled reference modulo {@code 2^16} (J.2.1), unclamped, and the completed image
 * clamped to the range of the precision.
 */
final class HierarchicalTestWriter {
    /**
     * A written stream with the samples it decodes to.
     *
     * @param stream the stream
     * @param expected the decoded samples of each component at its size in the completed image
     * @param outOfRange the number of samples of the references of differential frames outside the range of the
     * precision, which a decoder must not clamp
     */
    record Written(byte[] stream, int[][] expected, int outOfRange) {
    }

    /**
     * The settings of a stream.
     *
     * @param precision the sample precision: 8 or 12 with DCT frames, 2 to 16 with lossless ones
     * @param dct whether the non-differential frame and the differential frames before the last are DCT coded
     * @param finalLossless whether the last frame is a lossless differential frame (with DCT frames before it)
     * @param progressive whether the DCT frames are progressive
     * @param arithmetic whether the lossless frames are arithmetic coded
     * @param expansions the expansion of each differential frame, horizontal in bit 1 and vertical in bit 0, from
     * the smallest level up; 0 codes a differential frame of the size of its reference
     * @param quality the quality of the quantization tables of the DCT frames
     */
    record Settings(int precision, boolean dct, boolean finalLossless, boolean progressive, boolean arithmetic,
                    int[] expansions, int quality) {
    }

    // A coded DCT frame: the stream and, for each component, its quantized coefficients in the decoder's padded
    // block layout, the padded blocks per row and the natural-order quantization table.
    private record DctFrame(byte[] stream, short[][] coefficients, int[] paddedWide, int[][] quantization) {
    }

    private HierarchicalTestWriter() {
    }

    static Written write(int[][] source, int width, int height, int[][] factors, Settings settings) {
        var count = source.length;
        var levels = settings.expansions().length + 1;
        var widths = new int[levels];
        var heights = new int[levels];
        widths[levels - 1] = width;
        heights[levels - 1] = height;
        for (var level = levels - 2; level >= 0; level--) {
            var expansion = settings.expansions()[level];
            widths[level] = (expansion & 2) != 0 ? (widths[level + 1] + 1) / 2 : widths[level + 1];
            heights[level] = (expansion & 1) != 0 ? (heights[level + 1] + 1) / 2 : heights[level + 1];
        }
        var pyramid = new int[levels][][];
        pyramid[levels - 1] = source;
        for (var level = levels - 2; level >= 0; level--) {
            var expansion = settings.expansions()[level];
            pyramid[level] = new int[count][];
            for (var c = 0; c < count; c++) {
                var above = pyramid[level + 1][c];
                var aw = componentSize(widths[level + 1], factors, c, 0);
                var ah = componentSize(heights[level + 1], factors, c, 1);
                pyramid[level][c] = downsample(above, aw, ah, (expansion & 2) != 0, (expansion & 1) != 0,
                        componentSize(widths[level], factors, c, 0), componentSize(heights[level], factors, c, 1));
            }
        }
        var out = new ByteArrayOutputStream();
        marker(out, 0xD8);
        marker(out, 0xDE);
        u16(out, 8 + 3 * count);
        out.write(settings.precision());
        u16(out, height);
        u16(out, width);
        out.write(count);
        for (var c = 0; c < count; c++) {
            out.write(c + 1);
            out.write((factors[c][0] << 4) | factors[c][1]);
            out.write(0);
        }
        var max = (1 << settings.precision()) - 1;
        var outOfRange = 0;
        int[][] reconstruction;
        if (settings.dct()) {
            var frame = dctFrame(pyramid[0], widths[0], heights[0], factors, settings, false);
            out.writeBytes(body(frame.stream()));
            reconstruction = plainDecode(frame.stream(), factors, widths[0], heights[0], settings.precision());
        } else {
            var scans = List.of(Lj92Encoder.ScanSpec.of(all(count), 4, 0, 0));
            var frame = Lj92Encoder.encodeFrame(pyramid[0], widths[0], heights[0], factors, settings.precision(),
                    settings.arithmetic(), scans, Lj92Encoder.Tables.defaults(count), false);
            out.writeBytes(body(frame));
            reconstruction = copy(pyramid[0]);
        }
        for (var level = 1; level < levels; level++) {
            var expansion = settings.expansions()[level - 1];
            marker(out, 0xDF);
            u16(out, 3);
            out.write(((expansion >> 1) << 4) | (expansion & 1));
            var reference = new int[count][];
            for (var c = 0; c < count; c++) {
                var rw = componentSize(widths[level - 1], factors, c, 0);
                var rh = componentSize(heights[level - 1], factors, c, 1);
                var tw = componentSize(widths[level], factors, c, 0);
                var th = componentSize(heights[level], factors, c, 1);
                reference[c] = upsample(reconstruction[c], rw, rh, (expansion & 2) != 0, (expansion & 1) != 0, tw, th);
                for (var value : reference[c]) {
                    outOfRange += value < 0 || value > max ? 1 : 0;
                }
            }
            var lossless = !settings.dct() || (settings.finalLossless() && level == levels - 1);
            var differences = new int[count][];
            for (var c = 0; c < count; c++) {
                differences[c] = new int[reference[c].length];
                for (var i = 0; i < reference[c].length; i++) {
                    var difference = pyramid[level][c][i] - reference[c][i];
                    differences[c][i] = lossless ? difference & 0xFFFF : difference;
                }
            }
            if (lossless) {
                var scans = List.of(Lj92Encoder.ScanSpec.of(all(count), 0, 0, 0));
                var frame = Lj92Encoder.encodeFrame(differences, widths[level], heights[level], factors,
                        settings.precision(), settings.arithmetic(), scans, Lj92Encoder.Tables.defaults(count), true);
                out.writeBytes(body(frame));
                reconstruction = copy(pyramid[level]);
            } else {
                var frame = dctFrame(differences, widths[level], heights[level], factors, settings, true);
                out.writeBytes(body(frame.stream()));
                var decoded = differentialDecode(frame, factors, widths[level], heights[level], settings.precision());
                reconstruction = new int[count][];
                for (var c = 0; c < count; c++) {
                    reconstruction[c] = new int[reference[c].length];
                    for (var i = 0; i < reference[c].length; i++) {
                        reconstruction[c][i] = (short) (reference[c][i] + decoded[c][i]);
                    }
                }
            }
        }
        marker(out, 0xD9);
        for (var plane : reconstruction) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = Math.clamp(plane[i], 0, max);
            }
        }
        return new Written(out.toByteArray(), reconstruction, outOfRange);
    }

    static int componentSize(int size, int[][] factors, int c, int axis) {
        var largest = 1;
        for (var factor : factors) {
            largest = Math.max(largest, factor[axis]);
        }
        return (size * factors[c][axis] + largest - 1) / largest;
    }

    private static int[] all(int count) {
        var components = new int[count];
        for (var c = 0; c < count; c++) {
            components[c] = c;
        }
        return components;
    }

    private static int[][] copy(int[][] planes) {
        var result = new int[planes.length][];
        for (var c = 0; c < planes.length; c++) {
            result[c] = planes[c].clone();
        }
        return result;
    }

    // Averages pairs of samples, rounding up; the last sample of an odd line or column stands alone.
    static int[] downsample(int[] plane, int width, int height, boolean horizontal, boolean vertical, int targetWidth,
                            int targetHeight) {
        var result = new int[targetWidth * targetHeight];
        for (var y = 0; y < targetHeight; y++) {
            for (var x = 0; x < targetWidth; x++) {
                var sum = 0;
                var samples = 0;
                for (var dy = 0; dy < (vertical ? 2 : 1); dy++) {
                    for (var dx = 0; dx < (horizontal ? 2 : 1); dx++) {
                        var sy = Math.min((vertical ? 2 * y : y) + dy, height - 1);
                        var sx = Math.min((horizontal ? 2 * x : x) + dx, width - 1);
                        sum += plane[sy * width + sx];
                        samples++;
                    }
                }
                result[y * targetWidth + x] = (sum + samples / 2) / samples;
            }
        }
        return result;
    }

    // T.81 J.1.1.2: bilinear doubling, truncating, horizontal first; then the target size by cropping or repeating.
    // A negative sample, which only an unclamped DCT reconstruction holds, averages toward negative infinity.
    static int[] upsample(int[] plane, int width, int height, boolean horizontal, boolean vertical, int targetWidth,
                          int targetHeight) {
        var w = horizontal ? 2 * width : width;
        var h = vertical ? 2 * height : height;
        var wide = new int[w * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < w; x++) {
                if (!horizontal) {
                    wide[y * w + x] = plane[y * width + x];
                } else if ((x & 1) == 0) {
                    wide[y * w + x] = plane[y * width + x / 2];
                } else {
                    var a = plane[y * width + x / 2];
                    var b = plane[y * width + Math.min(x / 2 + 1, width - 1)];
                    wide[y * w + x] = Math.floorDiv(a + b, 2);
                }
            }
        }
        var full = new int[w * h];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                if (!vertical) {
                    full[y * w + x] = wide[y * w + x];
                } else if ((y & 1) == 0) {
                    full[y * w + x] = wide[(y / 2) * w + x];
                } else {
                    var a = wide[(y / 2) * w + x];
                    var b = wide[Math.min(y / 2 + 1, height - 1) * w + x];
                    full[y * w + x] = Math.floorDiv(a + b, 2);
                }
            }
        }
        var result = new int[targetWidth * targetHeight];
        for (var y = 0; y < targetHeight; y++) {
            for (var x = 0; x < targetWidth; x++) {
                result[y * targetWidth + x] = full[Math.min(y, h - 1) * w + Math.min(x, w - 1)];
            }
        }
        return result;
    }

    // A DCT frame of quantized coefficients from the floating-point forward DCT, written as a plain JPEG stream
    // (SOF1 or SOF2, or SOF5 or SOF6 when differential).
    private static DctFrame dctFrame(int[][] planes, int width, int height, int[][] factors, Settings settings,
                                     boolean differential) {
        var count = planes.length;
        var hMax = Arrays.stream(factors).mapToInt(f -> f[0]).max().orElseThrow();
        var vMax = Arrays.stream(factors).mapToInt(f -> f[1]).max().orElseThrow();
        var mcusWide = JpegFrame.ceilDiv(width, hMax * 8);
        var mcusHigh = JpegFrame.ceilDiv(height, vMax * 8);
        var luma = JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, settings.quality());
        var chroma = JpegWriter.scaledTable(JpegTables.STD_CHROMINANCE_QUANT, settings.quality());
        var shift = differential ? 0 : 1 << (settings.precision() - 1);
        var components = new TestJpegWriter.Component[count];
        var stored = new short[count][];
        var padded = new int[count];
        var quantization = new int[count][];
        for (var c = 0; c < count; c++) {
            var cw = componentSize(width, factors, c, 0);
            var ch = componentSize(height, factors, c, 1);
            var blocksWide = JpegFrame.ceilDiv(cw, 8);
            var blocksHigh = JpegFrame.ceilDiv(ch, 8);
            var paddedWide = count == 1 ? blocksWide : mcusWide * factors[c][0];
            var paddedHigh = count == 1 ? blocksHigh : mcusHigh * factors[c][1];
            var table = c == 0 ? luma : chroma;
            var coefficients = new short[paddedWide * paddedHigh * 64];
            var block = new double[64];
            for (var by = 0; by < paddedHigh; by++) {
                for (var bx = 0; bx < paddedWide; bx++) {
                    for (var y = 0; y < 8; y++) {
                        for (var x = 0; x < 8; x++) {
                            var sy = Math.min(by * 8 + y, ch - 1);
                            var sx = Math.min(bx * 8 + x, cw - 1);
                            block[y * 8 + x] = planes[c][sy * cw + sx] - shift;
                        }
                    }
                    for (var v = 0; v < 8; v++) {
                        for (var u = 0; u < 8; u++) {
                            var sum = 0.0;
                            for (var y = 0; y < 8; y++) {
                                for (var x = 0; x < 8; x++) {
                                    sum += block[y * 8 + x] * StrictMath.cos((2 * x + 1) * u * StrictMath.PI / 16)
                                            * StrictMath.cos((2 * y + 1) * v * StrictMath.PI / 16);
                                }
                            }
                            var cu = u == 0 ? StrictMath.sqrt(0.5) : 1;
                            var cv = v == 0 ? StrictMath.sqrt(0.5) : 1;
                            var coefficient = 0.25 * cu * cv * sum;
                            var natural = v * 8 + u;
                            coefficients[(by * paddedWide + bx) * 64 + natural] =
                                    (short) StrictMath.round(coefficient / table[natural]);
                        }
                    }
                }
            }
            components[c] = new TestJpegWriter.Component(c + 1, factors[c][0], factors[c][1], c == 0 ? 0 : 1,
                    coefficients, paddedWide, paddedHigh, blocksWide, blocksHigh);
            stored[c] = coefficients;
            padded[c] = paddedWide;
            quantization[c] = table;
        }
        var writer = new TestJpegWriter(width, height, components,
                count == 1 ? new int[][]{luma} : new int[][]{luma, chroma});
        writer.jfif = false;
        writer.precision = settings.precision();
        writer.differentialDc = differential;
        writer.sofMarker = (differential ? 0xC5 : 0xC1) + (settings.progressive() ? 1 : 0);
        var stream = settings.progressive() ? writer.progressive(TestJpegWriter.simpleProgression(count, false))
                : writer.sequential(all(count));
        return new DctFrame(stream, stored, padded, quantization);
    }

    // The decode of the non-differential frame as a plain JPEG stream: the frame marker is read as the
    // non-hierarchical process of the same coding.
    private static int[][] plainDecode(byte[] frame, int[][] factors, int width, int height, int precision) {
        var reader = new JpegReader(frame, 0, frame.length, MediaLimits.DEFAULT);
        reader.readHeaders();
        var planes = precision == 12 ? reader.decodeWidePlanes(0) : reader.decodePlanes(0);
        var result = new int[factors.length][];
        for (var c = 0; c < factors.length; c++) {
            var cw = componentSize(width, factors, c, 0);
            var ch = componentSize(height, factors, c, 1);
            result[c] = new int[cw * ch];
            for (var y = 0; y < ch; y++) {
                for (var x = 0; x < cw; x++) {
                    result[c][y * cw + x] = precision == 12 ? planes.widePlane(c)[y * planes.stride(c) + x]
                            : planes.plane(c)[y * planes.stride(c) + x] & 0xFF;
                }
            }
        }
        return result;
    }

    // The differences a decoder reconstructs from a differential DCT frame: the dequantized coefficients through
    // the accurate integer inverse DCT, without the level shift.
    private static int[][] differentialDecode(DctFrame frame, int[][] factors, int width, int height, int precision) {
        var result = new int[factors.length][];
        var block = new int[64];
        for (var c = 0; c < factors.length; c++) {
            var cw = componentSize(width, factors, c, 0);
            var ch = componentSize(height, factors, c, 1);
            var plane = new int[cw * ch];
            var quant = frame.quantization()[c];
            var stored = frame.coefficients()[c];
            for (var by = 0; by < JpegFrame.ceilDiv(ch, 8); by++) {
                for (var bx = 0; bx < JpegFrame.ceilDiv(cw, 8); bx++) {
                    var base = (by * frame.paddedWide()[c] + bx) * 64;
                    for (var k = 0; k < 64; k++) {
                        block[k] = stored[base + k] * quant[k];
                    }
                    HierarchicalIdct.transform(block, precision, true, plane, by * 8 * cw + bx * 8, cw,
                            Math.min(8, cw - bx * 8), Math.min(8, ch - by * 8));
                }
            }
            result[c] = plane;
        }
        return result;
    }

    // The segments of a plain stream between SOI and EOI.
    private static byte[] body(byte[] stream) {
        return Arrays.copyOfRange(stream, 2, stream.length - 2);
    }

    private static void marker(ByteArrayOutputStream out, int code) {
        out.write(0xFF);
        out.write(code);
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value);
    }
}
