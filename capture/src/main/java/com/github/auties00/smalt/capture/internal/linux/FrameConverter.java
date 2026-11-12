package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.internal.convert.ConversionKernel;
import com.github.auties00.smalt.capture.internal.convert.MjpegReducer;
import com.github.auties00.smalt.capture.internal.convert.RgbLayout;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Arrays;
import java.util.Objects;

/**
 * Converts the assembled frames of one negotiated Video4Linux2 format to planar 4:2:0 pictures of
 * even size.
 *
 * <p>A frame holds the image planes where its {@link V4l2FrameLayout} puts them, or one JPEG
 * image. Rows are read in display order through the layout's {@link V4l2FrameLayout.RowOrder}, so
 * sequential and alternating fields are woven and a single field is line-doubled. An odd width or
 * height is cropped by one pixel. Chroma is reduced to 4:2:0 by averaging: vertically adjacent
 * samples of 4:2:2, the 2x2 block of 4:4:4 (rounding half up, {@code (a + b + c + d + 2) >> 2});
 * semi-planar chroma is de-interleaved and planar chroma copied. R'G'B' pixels are converted with
 * the BT.709 matrix to limited range ({@link YuvCoefficients}), limited-range R'G'B' expanded to
 * full range first and the 5-bit and 6-bit channels of RGB565 scaled to 8 bits by rounding
 * {@code value * 255 / max} to nearest. Luma-only frames keep their most significant 8 bits and get neutral chroma.
 * Motion-JPEG frames are decoded and reduced by a {@link MjpegReducer}.
 *
 * <p>A picture is valid until the next conversion. Instances are not thread-safe.
 */
final class FrameConverter {
    /**
     * The R'G'B' layout of the scratch rows R'G'B' pixels are expanded into.
     */
    private static final RgbLayout EXPANDED = RgbLayout.BGRX;

    /**
     * The 8-bit value of every 5-bit sample, rounded to nearest.
     */
    private static final byte[] SCALE5 = scale(31);

    /**
     * The 8-bit value of every 6-bit sample, rounded to nearest.
     */
    private static final byte[] SCALE6 = scale(63);

    /**
     * The captured pixel format.
     */
    private final V4l2PixelFormat format;

    /**
     * The width of the pictures, the frame width rounded down to even.
     */
    private final int outputWidth;

    /**
     * The height of the pictures, the frame height rounded down to even.
     */
    private final int outputHeight;

    /**
     * The smallest number of bytes a raw frame must hold, or {@code 1} for JPEG.
     */
    private final int requiredBytes;

    /**
     * The offset of every displayed row of every image plane in the frame.
     */
    private final int[][] rowOffsets;

    /**
     * The picture the raw formats are converted into.
     */
    private final YuvPicture target;

    private final ConversionKernel kernel;

    /**
     * The R'G'B' to Y'CbCr conversion of the R'G'B' formats, else {@code null}.
     */
    private final YuvCoefficients coefficients;

    /**
     * The layout of 4-byte R'G'B' pixels converted in place, or {@code null} when the pixels are
     * expanded into {@link #scratch} first.
     */
    private final RgbLayout directLayout;

    /**
     * The map from limited-range to full-range R'G'B' samples, or {@code null} for full-range
     * pixels.
     */
    private final byte[] expandRange;

    /**
     * The scratch rows of the expansions and the chroma reductions.
     */
    private final byte[] scratch;

    /**
     * The reducer of Motion-JPEG frames, or {@code null} for the raw formats.
     */
    private final MjpegReducer mjpeg;

    /**
     * Creates a converter for single-planar progressive frames.
     *
     * @param format the pixel format, which must be contiguous
     * @param width the frame width in pixels
     * @param height the frame height in pixels
     * @param bytesPerLine the driver's row stride of the first plane, or 0 for a tight stride
     * @param color the color of the raw formats' samples
     * @param kernel the row kernel
     * @throws MediaException.Unsupported if the size is smaller than 2x2 or the stride cannot hold a
     * row
     * @throws MediaException.LimitExceeded if the size exceeds the default limits
     */
    FrameConverter(V4l2PixelFormat format, int width, int height, int bytesPerLine, ColorInfo color, ConversionKernel kernel) {
        this(new V4l2FrameLayout(format, width, height, V4l2.FIELD_NONE, bytesPerLine), color, kernel);
    }

    /**
     * Creates a converter for the frames a layout assembles.
     *
     * @param layout the layout of the frames
     * @param color the color of the Y'CbCr formats' samples; for R'G'B', the primaries and transfer
     * of the samples and whether they are limited range
     * @param kernel the row kernel
     */
    FrameConverter(V4l2FrameLayout layout, ColorInfo color, ConversionKernel kernel) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(color, "color");
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.format = layout.format();
        this.outputWidth = layout.width() & ~1;
        this.outputHeight = layout.height() & ~1;
        var required = 1L;
        this.rowOffsets = new int[layout.planes()][];
        for (var plane = 0; plane < layout.planes(); plane++) {
            var stored = layout.frameRows(plane);
            var shown = layout.order() == V4l2FrameLayout.RowOrder.DOUBLED ? 2 * stored : stored;
            var offsets = new int[shown];
            for (var row = 0; row < shown; row++) {
                offsets[row] = layout.frameOffset(plane) + storedRow(layout.order(), row, stored) * layout.stride(plane);
            }
            rowOffsets[plane] = offsets;
            if (format.kind() != V4l2PixelFormat.Kind.JPEG) {
                required = Math.max(required, layout.frameOffset(plane) + (long) layout.stride(plane) * (stored - 1)
                        + layout.rowBytes(plane));
            }
        }
        this.requiredBytes = (int) required;
        if (format.kind() == V4l2PixelFormat.Kind.RGB) {
            var output = new ColorInfo(MatrixCoefficients.BT709, ColorRange.LIMITED, color.primaries(), color.transfer());
            this.coefficients = YuvCoefficients.of(output);
            this.expandRange = color.range() == ColorRange.LIMITED ? limitedToFull() : null;
            this.directLayout = format.bytesPerPixel() == 4 && expandRange == null
                    ? new RgbLayout(format.lumaIndex(), format.cbIndex(), format.crIndex()) : null;
            this.target = YuvPicture.allocate(outputWidth, outputHeight, ChromaFormat.YUV420, false, output);
        } else {
            this.coefficients = null;
            this.expandRange = null;
            this.directLayout = null;
            this.target = YuvPicture.allocate(outputWidth, outputHeight, ChromaFormat.YUV420, false, color);
        }
        this.scratch = switch (format.kind()) {
            case RGB -> directLayout == null ? new byte[8 * outputWidth] : new byte[0];
            case SEMI_PLANAR_422 -> new byte[outputWidth];
            default -> new byte[0];
        };
        if (format.kind() == V4l2PixelFormat.Kind.LUMA) {
            Arrays.fill(target.u(), (byte) 128);
            Arrays.fill(target.v(), (byte) 128);
        }
        this.mjpeg = format.kind() == V4l2PixelFormat.Kind.JPEG ? new MjpegReducer(kernel) : null;
    }

    /**
     * Maps a displayed row of a plane to the stored row that holds it.
     *
     * @param order the row order
     * @param row the displayed row
     * @param stored the number of stored rows
     * @return the stored row
     */
    static int storedRow(V4l2FrameLayout.RowOrder order, int row, int stored) {
        return switch (order) {
            case PROGRESSIVE -> row;
            case TOP_FIRST -> (row & 1) == 0 ? row / 2 : (stored + 1) / 2 + row / 2;
            case BOTTOM_FIRST -> (row & 1) == 1 ? row / 2 : stored / 2 + row / 2;
            case DOUBLED -> row / 2;
        };
    }

    /**
     * Builds the map from the samples of a narrow channel to 8 bits.
     *
     * @param max the largest sample
     * @return the map, {@code value * 255 / max} rounded to nearest
     */
    private static byte[] scale(int max) {
        var map = new byte[max + 1];
        for (var value = 0; value <= max; value++) {
            map[value] = (byte) ((value * 255 + max / 2) / max);
        }
        return map;
    }

    /**
     * Builds the map from limited-range R'G'B' samples (16 to 235) to full range.
     *
     * @return the map, indexed by the unsigned sample
     */
    private static byte[] limitedToFull() {
        var map = new byte[256];
        for (var sample = 0; sample < 256; sample++) {
            map[sample] = (byte) Math.clamp(((sample - 16) * 255 + 109) / 219, 0, 255);
        }
        return map;
    }

    /**
     * Returns the width of the pictures.
     *
     * @return the width, even
     */
    int outputWidth() {
        return outputWidth;
    }

    /**
     * Returns the height of the pictures.
     *
     * @return the height, even
     */
    int outputHeight() {
        return outputHeight;
    }

    /**
     * Returns the smallest number of bytes a frame must hold to be converted.
     *
     * @return the size of a raw frame, or {@code 1} for JPEG
     */
    int requiredBytes() {
        return requiredBytes;
    }

    /**
     * Converts a frame.
     *
     * @param frame the array holding the frame from index {@code 0}
     * @param length the number of bytes of the frame
     * @return the picture, valid until the next conversion, or {@code null} when the frame is
     * unusable: shorter than {@link #requiredBytes()}, or a JPEG image that does not
     * decode or has another size
     */
    YuvPicture convert(byte[] frame, int length) {
        if (length < requiredBytes || length > frame.length) {
            return null;
        }
        return switch (format.kind()) {
            case PLANAR -> planar(frame, false);
            case PLANAR_422 -> planar(frame, true);
            case SEMI_PLANAR -> semiPlanar(frame);
            case SEMI_PLANAR_422 -> semiPlanar422(frame);
            case SEMI_PLANAR_444 -> semiPlanar444(frame);
            case PACKED -> packed(frame);
            case RGB -> rgb(frame);
            case LUMA -> luma(frame);
            case JPEG -> jpeg(frame, length);
        };
    }

    /**
     * Converts a planar 4:2:0 or 4:2:2 frame.
     *
     * @param averaged whether the chroma is 4:2:2 and its rows are averaged in pairs
     * @return the target picture
     */
    private YuvPicture planar(byte[] frame, boolean averaged) {
        copyLuma(frame);
        var u = rowOffsets[format.cbIndex() == 0 ? 1 : 2];
        var v = rowOffsets[format.cbIndex() == 0 ? 2 : 1];
        var outputChroma = outputWidth / 2;
        for (var row = 0; row < outputHeight / 2; row++) {
            var at = row * outputChroma;
            if (averaged) {
                kernel.averageRows(frame, u[2 * row], frame, u[2 * row + 1], outputChroma, target.u(), at);
                kernel.averageRows(frame, v[2 * row], frame, v[2 * row + 1], outputChroma, target.v(), at);
            } else {
                System.arraycopy(frame, u[row], target.u(), at, outputChroma);
                System.arraycopy(frame, v[row], target.v(), at, outputChroma);
            }
        }
        return target;
    }

    /**
     * Converts a semi-planar 4:2:0 frame.
     *
     * @return the target picture
     */
    private YuvPicture semiPlanar(byte[] frame) {
        copyLuma(frame);
        var chroma = rowOffsets[1];
        var first = format.cbIndex() == 0 ? target.u() : target.v();
        var second = format.cbIndex() == 0 ? target.v() : target.u();
        var outputChroma = outputWidth / 2;
        for (var row = 0; row < outputHeight / 2; row++) {
            kernel.deinterleave(frame, chroma[row], outputChroma, first, second, row * outputChroma);
        }
        return target;
    }

    /**
     * Converts a semi-planar 4:2:2 frame, averaging each pair of chroma rows before splitting it.
     *
     * @return the target picture
     */
    private YuvPicture semiPlanar422(byte[] frame) {
        copyLuma(frame);
        var chroma = rowOffsets[1];
        var first = format.cbIndex() == 0 ? target.u() : target.v();
        var second = format.cbIndex() == 0 ? target.v() : target.u();
        var outputChroma = outputWidth / 2;
        for (var row = 0; row < outputHeight / 2; row++) {
            kernel.averageRows(frame, chroma[2 * row], frame, chroma[2 * row + 1], outputWidth, scratch, 0);
            kernel.deinterleave(scratch, 0, outputChroma, first, second, row * outputChroma);
        }
        return target;
    }

    /**
     * Converts a semi-planar 4:4:4 frame, averaging the 2x2 block of every chroma sample.
     *
     * @return the target picture
     */
    private YuvPicture semiPlanar444(byte[] frame) {
        copyLuma(frame);
        var chroma = rowOffsets[1];
        var first = format.cbIndex() == 0 ? target.u() : target.v();
        var second = format.cbIndex() == 0 ? target.v() : target.u();
        var outputChroma = outputWidth / 2;
        for (var row = 0; row < outputHeight / 2; row++) {
            var upper = chroma[2 * row];
            var lower = chroma[2 * row + 1];
            var at = row * outputChroma;
            for (var k = 0; k < outputChroma; k++) {
                var a = upper + 4 * k;
                var b = lower + 4 * k;
                first[at + k] = (byte) (((frame[a] & 0xFF) + (frame[a + 2] & 0xFF) + (frame[b] & 0xFF)
                        + (frame[b + 2] & 0xFF) + 2) >> 2);
                second[at + k] = (byte) (((frame[a + 1] & 0xFF) + (frame[a + 3] & 0xFF) + (frame[b + 1] & 0xFF)
                        + (frame[b + 3] & 0xFF) + 2) >> 2);
            }
        }
        return target;
    }

    /**
     * Converts a packed 4:2:2 frame.
     *
     * @return the target picture
     */
    private YuvPicture packed(byte[] frame) {
        var y = target.y();
        var rows = rowOffsets[0];
        var outputChroma = outputWidth / 2;
        for (var row = 0; row < outputHeight; row += 2) {
            var upper = rows[row];
            var lower = rows[row + 1];
            kernel.packedLuma(frame, upper, outputWidth, format.lumaIndex(), y, row * outputWidth);
            kernel.packedLuma(frame, lower, outputWidth, format.lumaIndex(), y, (row + 1) * outputWidth);
            kernel.packedChroma(frame, upper, lower, outputChroma, format.cbIndex(), format.crIndex(),
                    target.u(), target.v(), (row / 2) * outputChroma);
        }
        return target;
    }

    /**
     * Converts an R'G'B' frame, expanding pixels that are not full-range 4-byte pixels into
     * {@link #scratch} first.
     *
     * @return the target picture
     */
    private YuvPicture rgb(byte[] frame) {
        var y = target.y();
        var rows = rowOffsets[0];
        var chromaWidth = outputWidth / 2;
        for (var row = 0; row < outputHeight; row += 2) {
            byte[] source;
            int upper;
            int lower;
            RgbLayout layout;
            if (directLayout != null) {
                source = frame;
                upper = rows[row];
                lower = rows[row + 1];
                layout = directLayout;
            } else {
                expand(frame, rows[row], 0);
                expand(frame, rows[row + 1], 4 * outputWidth);
                source = scratch;
                upper = 0;
                lower = 4 * outputWidth;
                layout = EXPANDED;
            }
            kernel.rgbToLuma(source, upper, outputWidth, layout, coefficients, y, row * outputWidth);
            kernel.rgbToLuma(source, lower, outputWidth, layout, coefficients, y, (row + 1) * outputWidth);
            kernel.rgbToChroma(source, upper, lower, chromaWidth, layout, coefficients, target.u(), target.v(),
                    (row / 2) * chromaWidth);
        }
        return target;
    }

    /**
     * Expands one row of R'G'B' pixels into {@code B G R X} pixels of {@link #scratch}, at full
     * range.
     *
     * @param frame the assembled frame
     * @param offset the row's first byte
     * @param destination the first byte of the expanded row in {@link #scratch}
     */
    private void expand(byte[] frame, int offset, int destination) {
        var bytes = format.bytesPerPixel();
        var map = expandRange;
        if (bytes == 2) {
            for (var x = 0; x < outputWidth; x++) {
                var at = offset + 2 * x;
                var value = (frame[at] & 0xFF) | (frame[at + 1] & 0xFF) << 8;
                var d = destination + 4 * x;
                scratch[d] = range(map, SCALE5[value & 0x1F] & 0xFF);
                scratch[d + 1] = range(map, SCALE6[(value >>> 5) & 0x3F] & 0xFF);
                scratch[d + 2] = range(map, SCALE5[value >>> 11] & 0xFF);
            }
            return;
        }
        var ri = format.lumaIndex();
        var gi = format.cbIndex();
        var bi = format.crIndex();
        for (var x = 0; x < outputWidth; x++) {
            var at = offset + bytes * x;
            var d = destination + 4 * x;
            scratch[d] = range(map, frame[at + bi] & 0xFF);
            scratch[d + 1] = range(map, frame[at + gi] & 0xFF);
            scratch[d + 2] = range(map, frame[at + ri] & 0xFF);
        }
    }

    /**
     * Maps a sample through the range map, if any.
     *
     * @param map the map, or {@code null}
     * @param sample the unsigned sample
     * @return the mapped sample
     */
    private static byte range(byte[] map, int sample) {
        return map == null ? (byte) sample : map[sample];
    }

    /**
     * Converts a luma-only frame; the neutral chroma was written once.
     *
     * @return the target picture
     */
    private YuvPicture luma(byte[] frame) {
        var y = target.y();
        var rows = rowOffsets[0];
        if (format.bytesPerPixel() == 1) {
            copyLuma(frame);
            return target;
        }
        var shift = format.significantBits() - 8;
        var high = format.bigEndian() ? 0 : 1;
        for (var row = 0; row < outputHeight; row++) {
            var at = rows[row];
            var out = row * outputWidth;
            for (var x = 0; x < outputWidth; x++) {
                var value = (frame[at + 2 * x + high] & 0xFF) << 8 | (frame[at + 2 * x + 1 - high] & 0xFF);
                y[out + x] = (byte) Math.min(255, value >>> shift);
            }
        }
        return target;
    }

    /**
     * Copies the luma plane of a planar, semi-planar or 8-bit luma frame.
     */
    private void copyLuma(byte[] frame) {
        var y = target.y();
        var rows = rowOffsets[0];
        for (var row = 0; row < outputHeight; row++) {
            System.arraycopy(frame, rows[row], y, row * outputWidth, outputWidth);
        }
    }

    /**
     * Decodes a JPEG frame and reduces it to 4:2:0.
     *
     * @param length the number of bytes of the frame
     * @return the picture, or {@code null} when the frame does not decode or has another size
     */
    private YuvPicture jpeg(byte[] frame, int length) {
        YuvPicture reduced;
        try {
            reduced = mjpeg.decode(frame, 0, length);
        } catch (MediaException exception) {
            return null;
        }
        return reduced.width() == outputWidth && reduced.height() == outputHeight ? reduced : null;
    }
}
