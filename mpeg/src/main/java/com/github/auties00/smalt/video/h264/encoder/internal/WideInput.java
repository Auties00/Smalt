package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;

import java.util.Arrays;

/**
 * Copies an input picture into the coded area of a source {@link WideFrame} in the stream's chroma
 * format and bit depth, padding the area beyond the picture by repeating its last column and row.
 *
 * <p>Y'CbCr input is taken to use the stream's color description: a picture of the stream's chroma
 * format keeps its samples, scaled to the stream's depth as
 * {@link ImageOps#toYuv16(Picture, ChromaFormat, ColorInfo, int)} scales them, and any other layout is
 * resampled by that method first. RGB and channel input is converted with the stream's color
 * description. Instances are not thread-safe.
 */
final class WideInput {
    /**
     * The stream settings.
     */
    private final WideSettings settings;

    /**
     * Constructs a converter.
     *
     * @param settings the stream settings
     */
    WideInput(WideSettings settings) {
        this.settings = settings;
    }

    /**
     * Copies a picture into a frame's coded area.
     *
     * @param picture the input, exactly the configured size
     * @param frame the destination
     */
    void convert(Picture picture, WideFrame frame) {
        var format = settings.chromaFormat();
        var depth = settings.bitDepth();
        var width = settings.width();
        var height = settings.height();
        switch (picture) {
            case YuvPicture yuv when fits(yuv.chromaFormat(), format) && (depth == 8
                    || yuv.color().range() == ColorRange.LIMITED) -> copy8(yuv, frame, depth - 8);
            case Yuv16Picture yuv when fits(yuv.chromaFormat(), format) && (yuv.bitDepth() == depth
                    || (yuv.color().range() == ColorRange.LIMITED && yuv.bitDepth() <= depth)) ->
                    copy16(yuv, frame, depth - yuv.bitDepth());
            case YuvPicture yuv -> copy16(ImageOps.toYuv16(yuv, format, yuv.color(), depth), frame, 0);
            case Yuv16Picture yuv -> copy16(ImageOps.toYuv16(yuv, format, yuv.color(), depth), frame, 0);
            default -> copy16(ImageOps.toYuv16(picture, format, settings.color(), depth), frame, 0);
        }
        for (var c = 0; c < 3; c++) {
            var plane = frame.planes[c];
            if (plane == null) {
                continue;
            }
            var w = c == 0 ? width : Math.ceilDiv(width, settings.subWidth());
            var h = c == 0 ? height : Math.ceilDiv(height, settings.subHeight());
            extend(plane, w, h);
        }
    }

    /**
     * Returns whether a picture of one chroma format provides the planes of another unchanged.
     *
     * @param input the picture's chroma format
     * @param stream the stream's chroma format
     * @return whether the formats match, or the stream is monochrome and takes the luma alone
     */
    private static boolean fits(ChromaFormat input, ChromaFormat stream) {
        return input == stream || stream == ChromaFormat.MONOCHROME;
    }

    /**
     * Copies an 8-bit picture of the stream's layout, shifting its samples left.
     *
     * @param yuv the picture
     * @param frame the destination
     * @param shift the number of bits to shift left
     */
    private void copy8(YuvPicture yuv, WideFrame frame, int shift) {
        copyPlane8(yuv.y(), yuv.yStride(), frame.planes[0], yuv.width(), yuv.height(), shift);
        if (frame.planes[1] != null) {
            copyPlane8(yuv.u(), yuv.uvStride(), frame.planes[1], yuv.chromaWidth(), yuv.chromaHeight(), shift);
            copyPlane8(yuv.v(), yuv.uvStride(), frame.planes[2], yuv.chromaWidth(), yuv.chromaHeight(), shift);
        }
    }

    /**
     * Copies a 16-bit picture of the stream's layout, shifting its samples left.
     *
     * @param yuv the picture
     * @param frame the destination
     * @param shift the number of bits to shift left
     */
    private void copy16(Yuv16Picture yuv, WideFrame frame, int shift) {
        copyPlane16(yuv.y(), yuv.yStride(), frame.planes[0], yuv.width(), yuv.height(), shift);
        if (frame.planes[1] != null) {
            copyPlane16(yuv.u(), yuv.uvStride(), frame.planes[1], yuv.chromaWidth(), yuv.chromaHeight(), shift);
            copyPlane16(yuv.v(), yuv.uvStride(), frame.planes[2], yuv.chromaWidth(), yuv.chromaHeight(), shift);
        }
    }

    /**
     * Copies one plane of 8-bit samples.
     *
     * @param source the samples
     * @param stride the source row stride
     * @param plane the destination plane
     * @param width the number of samples per row
     * @param height the number of rows
     * @param shift the number of bits to shift left
     */
    private static void copyPlane8(byte[] source, int stride, WidePlane plane, int width, int height, int shift) {
        for (var y = 0; y < height; y++) {
            var s = y * stride;
            var d = plane.origin + y * plane.stride;
            for (var x = 0; x < width; x++) {
                plane.data[d + x] = (short) ((source[s + x] & 0xFF) << shift);
            }
        }
    }

    /**
     * Copies one plane of 16-bit samples.
     *
     * @param source the samples
     * @param stride the source row stride
     * @param plane the destination plane
     * @param width the number of samples per row
     * @param height the number of rows
     * @param shift the number of bits to shift left
     */
    private static void copyPlane16(short[] source, int stride, WidePlane plane, int width, int height, int shift) {
        for (var y = 0; y < height; y++) {
            var s = y * stride;
            var d = plane.origin + y * plane.stride;
            if (shift == 0) {
                System.arraycopy(source, s, plane.data, d, width);
                continue;
            }
            for (var x = 0; x < width; x++) {
                plane.data[d + x] = (short) ((source[s + x] & 0xFFFF) << shift);
            }
        }
    }

    /**
     * Repeats a plane's last column and row over the rest of the coded area.
     *
     * @param plane the plane to extend
     * @param width the width of the valid samples
     * @param height the height of the valid samples
     */
    private static void extend(WidePlane plane, int width, int height) {
        var data = plane.data;
        if (width < plane.width) {
            for (var y = 0; y < height; y++) {
                var row = plane.origin + y * plane.stride;
                Arrays.fill(data, row + width, row + plane.width, data[row + width - 1]);
            }
        }
        var last = plane.origin + (height - 1) * plane.stride;
        for (var y = height; y < plane.height; y++) {
            System.arraycopy(data, last, data, plane.origin + y * plane.stride, plane.width);
        }
    }
}
