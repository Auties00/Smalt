package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;

import java.util.Arrays;

/**
 * Copies an input picture into the source planes of a {@link PictureData}: the stream's chroma format at its bit
 * depth, the area beyond the picture filled by repeating its last column and row up to the coded size.
 *
 * <p>Y'CbCr input is taken to use the stream's color description: pictures of the stream's chroma format keep
 * their samples, an 8-bit limited-range or same-depth picture shifted to the stream's depth, and every other
 * picture is converted by
 * {@link ImageOps#toYuv16(Picture, ChromaFormat, com.github.auties00.smalt.image.ColorInfo, int)}, RGB with the
 * stream's color description. 8-bit RGB with the full-range identity matrix of an 8-bit 4:4:4 stream is copied
 * exactly, G, B and R becoming Y, Cb and Cr. Instances are not thread-safe.
 */
final class InputConverter {
    /**
     * The settings.
     */
    private final EncoderSettings s;

    /**
     * The chroma format of the stream.
     */
    private final ChromaFormat format;

    /**
     * Constructs a converter.
     *
     * @param s the settings
     */
    InputConverter(EncoderSettings s) {
        this.s = s;
        this.format = s.options.chromaFormat();
    }

    /**
     * Copies a picture into a picture's source planes.
     *
     * @param picture the input, exactly the encoder's size
     * @param d the destination
     */
    void convert(Picture picture, PictureData d) {
        var depth = s.bitDepth;
        switch (picture) {
            case YuvPicture yuv when yuv.chromaFormat() == format
                    && (depth == 8 || yuv.color().range() == ColorRange.LIMITED) -> copy8(yuv, d, depth - 8);
            case Yuv16Picture yuv when yuv.chromaFormat() == format && (yuv.bitDepth() == depth
                    || (yuv.color().range() == ColorRange.LIMITED && yuv.bitDepth() <= depth)) ->
                    copy16(yuv, d, depth - yuv.bitDepth());
            case YuvPicture yuv -> copy16(ImageOps.toYuv16(yuv, format, yuv.color(), depth), d, 0);
            case Yuv16Picture yuv -> copy16(ImageOps.toYuv16(yuv, format, yuv.color(), depth), d, 0);
            case RgbaPicture rgba when depth == 8 && format == ChromaFormat.YUV444
                    && s.color.matrix() == MatrixCoefficients.IDENTITY && s.color.range() == ColorRange.FULL ->
                    identity(rgba, d);
            default -> copy16(ImageOps.toYuv16(picture, format, s.color, depth), d, 0);
        }
        extend(d.sourceY, s.codedWidth, s.codedHeight, s.inputWidth, s.inputHeight);
        var cw = format.chromaWidth(s.inputWidth);
        var ch = format.chromaHeight(s.inputHeight);
        extend(d.sourceCb, s.chromaWidth, s.chromaHeight, cw, ch);
        extend(d.sourceCr, s.chromaWidth, s.chromaHeight, cw, ch);
    }

    /**
     * Copies an 8-bit Y'CbCr picture of the stream's chroma format, shifting its samples left.
     *
     * @param yuv the picture
     * @param d the destination
     * @param shift the number of bits to shift left
     */
    private void copy8(YuvPicture yuv, PictureData d, int shift) {
        plane8(yuv.y(), yuv.yStride(), d.sourceY, s.codedWidth, yuv.width(), yuv.height(), shift);
        plane8(yuv.u(), yuv.uvStride(), d.sourceCb, s.chromaWidth, yuv.chromaWidth(), yuv.chromaHeight(), shift);
        plane8(yuv.v(), yuv.uvStride(), d.sourceCr, s.chromaWidth, yuv.chromaWidth(), yuv.chromaHeight(), shift);
    }

    /**
     * Copies a 16-bit Y'CbCr picture of the stream's chroma format, shifting its samples left.
     *
     * @param yuv the picture
     * @param d the destination
     * @param shift the number of bits to shift left
     */
    private void copy16(Yuv16Picture yuv, PictureData d, int shift) {
        plane16(yuv.y(), yuv.yStride(), d.sourceY, s.codedWidth, yuv.width(), yuv.height(), shift);
        plane16(yuv.u(), yuv.uvStride(), d.sourceCb, s.chromaWidth, yuv.chromaWidth(), yuv.chromaHeight(), shift);
        plane16(yuv.v(), yuv.uvStride(), d.sourceCr, s.chromaWidth, yuv.chromaWidth(), yuv.chromaHeight(), shift);
    }

    /**
     * Copies the G, B and R samples of an 8-bit RGB picture into the Y, Cb and Cr planes, ignoring alpha.
     *
     * @param rgba the picture
     * @param d the destination
     */
    private void identity(RgbaPicture rgba, PictureData d) {
        var pixels = rgba.pixels();
        var stride = rgba.stride();
        var w = s.codedWidth;
        for (var y = 0; y < rgba.height(); y++) {
            var from = y * stride;
            var to = y * w;
            for (var x = 0; x < rgba.width(); x++) {
                var at = from + 4 * x;
                d.sourceCr[to + x] = (short) (pixels[at] & 0xFF);
                d.sourceY[to + x] = (short) (pixels[at + 1] & 0xFF);
                d.sourceCb[to + x] = (short) (pixels[at + 2] & 0xFF);
            }
        }
    }

    /**
     * Copies one plane of 8-bit samples.
     *
     * @param source the samples
     * @param sourceStride the source row stride
     * @param plane the destination
     * @param stride the destination row stride
     * @param width the number of samples per row
     * @param height the number of rows
     * @param shift the number of bits to shift left
     */
    private static void plane8(byte[] source, int sourceStride, short[] plane, int stride, int width, int height,
                               int shift) {
        for (var y = 0; y < height; y++) {
            var from = y * sourceStride;
            var to = y * stride;
            for (var x = 0; x < width; x++) {
                plane[to + x] = (short) ((source[from + x] & 0xFF) << shift);
            }
        }
    }

    /**
     * Copies one plane of 16-bit samples.
     *
     * @param source the samples
     * @param sourceStride the source row stride
     * @param plane the destination
     * @param stride the destination row stride
     * @param width the number of samples per row
     * @param height the number of rows
     * @param shift the number of bits to shift left
     */
    private static void plane16(short[] source, int sourceStride, short[] plane, int stride, int width, int height,
                                int shift) {
        for (var y = 0; y < height; y++) {
            var from = y * sourceStride;
            var to = y * stride;
            if (shift == 0) {
                System.arraycopy(source, from, plane, to, width);
                continue;
            }
            for (var x = 0; x < width; x++) {
                plane[to + x] = (short) ((source[from + x] & 0xFFFF) << shift);
            }
        }
    }

    /**
     * Repeats a plane's last column and row over the rest of its area.
     *
     * @param plane the samples, extended in place
     * @param stride the plane width
     * @param rows the plane height
     * @param width the width of the valid samples
     * @param height the height of the valid samples
     */
    private static void extend(short[] plane, int stride, int rows, int width, int height) {
        if (width < stride) {
            for (var y = 0; y < height; y++) {
                var row = y * stride;
                Arrays.fill(plane, row + width, row + stride, plane[row + width - 1]);
            }
        }
        var last = (height - 1) * stride;
        for (var y = height; y < rows; y++) {
            System.arraycopy(plane, last, plane, y * stride, stride);
        }
    }
}
