package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.YuvConverter;
import com.github.auties00.smalt.internal.color.LumaWeights;
import com.github.auties00.smalt.internal.plane.Planes;

import java.util.Arrays;
import java.util.Optional;

/**
 * Copies an input picture into the coded area of a source {@link Frame} as 4:2:0 Y'CbCr, padding
 * the area beyond the picture by repeating its last column and row.
 *
 * <p>4:2:0 input is copied unchanged. Other Y'CbCr layouts keep their samples and only resample
 * chroma: 4:2:2 averages vertical pairs, 4:4:4 averages 2x2 blocks, and monochrome pictures get
 * neutral chroma. RGBA input is converted with the matrix and range of the stream's
 * {@link ColorInfo}, chroma taken from the average of each 2x2 block of RGB; alpha is ignored. A matrix
 * that weighted luma does not define converts as {@link ImageOps#toYuv(Picture, ChromaFormat, ColorInfo)}
 * does. Y'CbCr input is assumed to already use the stream's color description.
 */
final class InputConverter {
    /**
     * The stream color description.
     */
    private final ColorInfo color;

    /**
     * The converter of RGBA input in a matrix that weighted luma does not define, which converts as
     * {@link ImageOps#toYuv(Picture, ChromaFormat, ColorInfo)} into a picture it reuses, or {@code null} for a
     * weighted matrix.
     */
    private final YuvConverter exact;

    /**
     * The fixed-point (16 fractional bits) coefficients of R, G and B in luma, and the luma
     * scale and offset.
     */
    private final int yr;

    /**
     * The green coefficient of luma.
     */
    private final int yg;

    /**
     * The blue coefficient of luma.
     */
    private final int yb;

    /**
     * The red coefficient of Cb.
     */
    private final int ur;

    /**
     * The green coefficient of Cb.
     */
    private final int ug;

    /**
     * The blue coefficient of Cb.
     */
    private final int ub;

    /**
     * The red coefficient of Cr.
     */
    private final int vr;

    /**
     * The green coefficient of Cr.
     */
    private final int vg;

    /**
     * The blue coefficient of Cr.
     */
    private final int vb;

    /**
     * The luma offset, 16 for limited range and 0 for full range.
     */
    private final int yOffset;

    /**
     * Constructs a converter.
     *
     * @param color the stream color description; an unspecified matrix is converted as BT.709
     */
    InputConverter(ColorInfo color) {
        this.color = color;
        var direct = switch (color.matrix()) {
            case UNSPECIFIED -> Optional.of(LumaWeights.BT709);
            case CHROMA_DERIVED_NCL -> LumaWeights.of(color.matrix(), color.primaries());
            default -> LumaWeights.of(color.matrix());
        };
        this.exact = direct.isEmpty() ? YuvConverter.create(ChromaFormat.YUV420, color) : null;
        var weights = direct.orElse(LumaWeights.BT709);
        var kr = weights.kr();
        var kb = weights.kb();
        var kg = 1 - kr - kb;
        var full = color.range() == ColorRange.FULL;
        var yScale = full ? 255.0 / 255.0 : 219.0 / 255.0;
        var cScale = full ? 255.0 / 255.0 : 224.0 / 255.0;
        var one = 65536.0;
        yr = (int) Math.round(kr * yScale * one);
        yg = (int) Math.round(kg * yScale * one);
        yb = (int) Math.round(kb * yScale * one);
        var cbScale = cScale / (2 * (1 - kb));
        var crScale = cScale / (2 * (1 - kr));
        ur = (int) Math.round(-kr * cbScale * one);
        ug = (int) Math.round(-kg * cbScale * one);
        ub = (int) Math.round((1 - kb) * cbScale * one);
        vr = (int) Math.round((1 - kr) * crScale * one);
        vg = (int) Math.round(-kg * crScale * one);
        vb = (int) Math.round(-kb * crScale * one);
        yOffset = full ? 0 : 16;
    }

    /**
     * Copies a picture into a frame's coded area.
     *
     * @param picture the input, exactly the configured size
     * @param width the picture width
     * @param height the picture height
     * @param frame the destination
     */
    void convert(Picture picture, int width, int height, Frame frame) {
        switch (picture) {
            case YuvPicture yuv -> convertYuv(yuv, width, height, frame);
            case RgbaPicture rgba when exact != null -> convertYuv(exact.convert(rgba), width, height, frame);
            case Rgba16Picture high when exact != null -> convertYuv(exact.convert(high), width, height, frame);
            case RgbaPicture rgba -> convertRgba(rgba, width, height, frame);
            case Yuv16Picture high -> convertYuv(ImageOps.toEightBit(high), width, height, frame);
            case Rgba16Picture high -> convertRgba(ImageOps.toEightBit(high), width, height, frame);
            case ChannelPicture channels -> convertRgba(ImageOps.toRgba(channels), width, height, frame);
        }
        extend(frame.y, frame.lumaOrigin, frame.lumaStride, width, height, frame.width, frame.height);
        var chromaWidth = (width + 1) >> 1;
        var chromaHeight = (height + 1) >> 1;
        extend(frame.u, frame.chromaOrigin, frame.chromaStride, chromaWidth, chromaHeight, frame.width / 2,
                frame.height / 2);
        extend(frame.v, frame.chromaOrigin, frame.chromaStride, chromaWidth, chromaHeight, frame.width / 2,
                frame.height / 2);
    }

    /**
     * Copies a Y'CbCr picture, resampling its chroma to 4:2:0.
     *
     * @param yuv the input
     * @param width the picture width
     * @param height the picture height
     * @param frame the destination
     */
    private static void convertYuv(YuvPicture yuv, int width, int height, Frame frame) {
        Planes.copy(yuv.y(), 0, yuv.yStride(), frame.y, frame.lumaOrigin, frame.lumaStride, width, height);
        var chromaWidth = (width + 1) >> 1;
        var chromaHeight = (height + 1) >> 1;
        switch (yuv.chromaFormat()) {
            case YUV420 -> {
                Planes.copy(yuv.u(), 0, yuv.uvStride(), frame.u, frame.chromaOrigin, frame.chromaStride, chromaWidth,
                        chromaHeight);
                Planes.copy(yuv.v(), 0, yuv.uvStride(), frame.v, frame.chromaOrigin, frame.chromaStride, chromaWidth,
                        chromaHeight);
            }
            case YUV422 -> {
                var sourceRows = yuv.chromaHeight();
                for (var y = 0; y < chromaHeight; y++) {
                    var row0 = Math.min(2 * y, sourceRows - 1) * yuv.uvStride();
                    var row1 = Math.min(2 * y + 1, sourceRows - 1) * yuv.uvStride();
                    var d = frame.chromaOrigin + y * frame.chromaStride;
                    for (var x = 0; x < chromaWidth; x++) {
                        frame.u[d + x] = (byte) (((yuv.u()[row0 + x] & 0xFF) + (yuv.u()[row1 + x] & 0xFF) + 1) >> 1);
                        frame.v[d + x] = (byte) (((yuv.v()[row0 + x] & 0xFF) + (yuv.v()[row1 + x] & 0xFF) + 1) >> 1);
                    }
                }
            }
            case YUV440 -> {
                for (var y = 0; y < chromaHeight; y++) {
                    var row = y * yuv.uvStride();
                    var d = frame.chromaOrigin + y * frame.chromaStride;
                    for (var x = 0; x < chromaWidth; x++) {
                        var x0 = row + 2 * x;
                        var x1 = row + Math.min(2 * x + 1, width - 1);
                        frame.u[d + x] = (byte) (((yuv.u()[x0] & 0xFF) + (yuv.u()[x1] & 0xFF) + 1) >> 1);
                        frame.v[d + x] = (byte) (((yuv.v()[x0] & 0xFF) + (yuv.v()[x1] & 0xFF) + 1) >> 1);
                    }
                }
            }
            case YUV444 -> {
                for (var y = 0; y < chromaHeight; y++) {
                    var row0 = Math.min(2 * y, height - 1) * yuv.uvStride();
                    var row1 = Math.min(2 * y + 1, height - 1) * yuv.uvStride();
                    var d = frame.chromaOrigin + y * frame.chromaStride;
                    for (var x = 0; x < chromaWidth; x++) {
                        var x0 = 2 * x;
                        var x1 = Math.min(2 * x + 1, width - 1);
                        frame.u[d + x] = average4(yuv.u(), row0 + x0, row0 + x1, row1 + x0, row1 + x1);
                        frame.v[d + x] = average4(yuv.v(), row0 + x0, row0 + x1, row1 + x0, row1 + x1);
                    }
                }
            }
            case MONOCHROME -> {
                for (var y = 0; y < chromaHeight; y++) {
                    var d = frame.chromaOrigin + y * frame.chromaStride;
                    Arrays.fill(frame.u, d, d + chromaWidth, (byte) 128);
                    Arrays.fill(frame.v, d, d + chromaWidth, (byte) 128);
                }
            }
        }
    }

    /**
     * Returns the rounded average of four samples.
     *
     * @param plane the samples
     * @param a the index of the first sample
     * @param b the index of the second sample
     * @param c the index of the third sample
     * @param d the index of the fourth sample
     * @return the average
     */
    private static byte average4(byte[] plane, int a, int b, int c, int d) {
        return (byte) (((plane[a] & 0xFF) + (plane[b] & 0xFF) + (plane[c] & 0xFF) + (plane[d] & 0xFF) + 2) >> 2);
    }

    /**
     * Converts an RGBA picture.
     *
     * @param rgba the input
     * @param width the picture width
     * @param height the picture height
     * @param frame the destination
     */
    private void convertRgba(RgbaPicture rgba, int width, int height, Frame frame) {
        var pixels = rgba.pixels();
        var stride = rgba.stride();
        var round = (yOffset << 16) + 32768;
        for (var y = 0; y < height; y++) {
            var s = y * stride;
            var d = frame.lumaOrigin + y * frame.lumaStride;
            for (var x = 0; x < width; x++) {
                var r = pixels[s] & 0xFF;
                var g = pixels[s + 1] & 0xFF;
                var b = pixels[s + 2] & 0xFF;
                frame.y[d + x] = clip((yr * r + yg * g + yb * b + round) >> 16);
                s += 4;
            }
        }
        var chromaWidth = (width + 1) >> 1;
        var chromaHeight = (height + 1) >> 1;
        var chromaRound = (128 << 18) + (1 << 17);
        for (var y = 0; y < chromaHeight; y++) {
            var row0 = 2 * y * stride;
            var row1 = Math.min(2 * y + 1, height - 1) * stride;
            var d = frame.chromaOrigin + y * frame.chromaStride;
            for (var x = 0; x < chromaWidth; x++) {
                var x0 = 8 * x;
                var x1 = Math.min(2 * x + 1, width - 1) * 4;
                var r = (pixels[row0 + x0] & 0xFF) + (pixels[row0 + x1] & 0xFF)
                        + (pixels[row1 + x0] & 0xFF) + (pixels[row1 + x1] & 0xFF);
                var g = (pixels[row0 + x0 + 1] & 0xFF) + (pixels[row0 + x1 + 1] & 0xFF)
                        + (pixels[row1 + x0 + 1] & 0xFF) + (pixels[row1 + x1 + 1] & 0xFF);
                var b = (pixels[row0 + x0 + 2] & 0xFF) + (pixels[row0 + x1 + 2] & 0xFF)
                        + (pixels[row1 + x0 + 2] & 0xFF) + (pixels[row1 + x1 + 2] & 0xFF);
                frame.u[d + x] = clip((ur * r + ug * g + ub * b + chromaRound) >> 18);
                frame.v[d + x] = clip((vr * r + vg * g + vb * b + chromaRound) >> 18);
            }
        }
    }

    /**
     * Clips a value to the 8-bit sample range.
     *
     * @param value the converted sample, possibly outside 0 to 255
     * @return the clipped sample
     */
    private static byte clip(int value) {
        return (byte) (value < 0 ? 0 : Math.min(value, 255));
    }

    /**
     * Repeats a plane's last column and row over the rest of the coded area.
     *
     * @param plane the samples
     * @param origin the index of sample {@code (0, 0)}
     * @param stride the row stride
     * @param width the width of the valid samples
     * @param height the height of the valid samples
     * @param codedWidth the coded width
     * @param codedHeight the coded height
     */
    private static void extend(byte[] plane, int origin, int stride, int width, int height, int codedWidth,
                               int codedHeight) {
        if (width < codedWidth) {
            Planes.extendSides(plane, origin, stride, width, height, 0, codedWidth - width);
        }
        Planes.extendBottom(plane, origin + (height - 1) * stride, stride, codedWidth, codedHeight - height);
    }

    /**
     * Returns the stream color description.
     *
     * @return the color
     */
    ColorInfo color() {
        return color;
    }
}
