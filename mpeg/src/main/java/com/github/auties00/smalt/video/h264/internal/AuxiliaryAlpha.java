package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Arrays;

/**
 * Turns the samples of a decoded auxiliary coded picture into the straight alpha plane of the
 * frame of its access unit (ITU-T H.264 7.4.2.1.2).
 *
 * <p>Auxiliary samples map to alpha through {@link H264SpsExtension#alphaTable(int)}. For
 * pre-multiplied-black video ({@code aux_format_idc} 2) each color sample {@code s} is divided
 * back by its alpha, {@code min(max, round(s / w))} for the alpha weight {@code w} in 0 to 1 (and
 * left as it is for {@code w} 0), chroma samples using the mean alpha of the luma samples they
 * cover.
 */
public final class AuxiliaryAlpha {
    /**
     * Prevents instantiation of this utility class.
     */
    private AuxiliaryAlpha() {
        throw new AssertionError();
    }

    /**
     * Writes the alpha an auxiliary frame describes into the alpha plane of a picture, and divides
     * the color of a premultiplied stream back by it.
     *
     * @param extension the sequence parameter set extension describing the alpha
     * @param alpha the auxiliary frame, monochrome at the auxiliary bit depth
     * @param target the picture of the primary frame, with an alpha plane; a part the auxiliary
     * frame does not cover is made opaque
     */
    public static void apply(H264SpsExtension extension, H264DecoderEngine.OutputFrame alpha, Picture target) {
        var width = target.width();
        var height = target.height();
        var sps = alpha.sps();
        var alphaWidth = Math.min(width, sps.croppedWidth());
        var alphaHeight = Math.min(height, sps.croppedHeight());
        var origin = alpha.lumaOrigin() + sps.cropTop * alpha.lumaStride() + sps.cropLeft;
        var stride = alpha.lumaStride();
        var depth = target instanceof Yuv16Picture wide ? wide.bitDepth() : 8;
        var lut = extension.alphaTable(depth);
        var mask = (1 << alpha.bitDepthLuma()) - 1;
        var opaque = (1 << depth) - 1;
        var luma8 = alpha.luma();
        var luma16 = alpha.luma16();
        if (target instanceof YuvPicture yuv) {
            var plane = yuv.alpha();
            var planeStride = yuv.alphaStride();
            for (var y = 0; y < height; y++) {
                var row = y * planeStride;
                if (y >= alphaHeight) {
                    Arrays.fill(plane, row, row + width, (byte) opaque);
                    continue;
                }
                var from = origin + y * stride;
                for (var x = 0; x < alphaWidth; x++) {
                    var sample = luma16 != null ? luma16[from + x] & mask : luma8[from + x] & 0xFF;
                    plane[row + x] = (byte) lut[sample];
                }
                Arrays.fill(plane, row + alphaWidth, row + width, (byte) opaque);
            }
            if (extension.premultiplied()) {
                unpremultiply(yuv);
            }
        } else if (target instanceof Yuv16Picture wide) {
            var plane = wide.alpha();
            var planeStride = wide.alphaStride();
            for (var y = 0; y < height; y++) {
                var row = y * planeStride;
                if (y >= alphaHeight) {
                    Arrays.fill(plane, row, row + width, (short) opaque);
                    continue;
                }
                var from = origin + y * stride;
                for (var x = 0; x < alphaWidth; x++) {
                    var sample = luma16 != null ? luma16[from + x] & mask : luma8[from + x] & 0xFF;
                    plane[row + x] = (short) lut[sample];
                }
                Arrays.fill(plane, row + alphaWidth, row + width, (short) opaque);
            }
            if (extension.premultiplied()) {
                unpremultiply(wide);
            }
        }
    }

    /**
     * Divides the color samples of an 8-bit picture back by its alpha.
     *
     * @param picture the picture with its straight alpha written
     */
    private static void unpremultiply(YuvPicture picture) {
        var alpha = picture.alpha();
        var alphaStride = picture.alphaStride();
        divide(picture.y(), picture.yStride(), picture.width(), picture.height(), 0, 0, alpha, alphaStride,
                picture.width(), picture.height(), 255);
        if (picture.chromaFormat() == com.github.auties00.smalt.image.ChromaFormat.MONOCHROME) {
            return;
        }
        var shiftX = picture.chromaFormat().shiftX();
        var shiftY = picture.chromaFormat().shiftY();
        divide(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), shiftX, shiftY, alpha,
                alphaStride, picture.width(), picture.height(), 255);
        divide(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), shiftX, shiftY, alpha,
                alphaStride, picture.width(), picture.height(), 255);
    }

    /**
     * Divides the color samples of a 16-bit picture back by its alpha.
     *
     * @param picture the picture with its straight alpha written
     */
    private static void unpremultiply(Yuv16Picture picture) {
        var alpha = picture.alpha();
        var alphaStride = picture.alphaStride();
        var max = (1 << picture.bitDepth()) - 1;
        divide(picture.y(), picture.yStride(), picture.width(), picture.height(), 0, 0, alpha, alphaStride,
                picture.width(), picture.height(), max);
        if (picture.chromaFormat() == com.github.auties00.smalt.image.ChromaFormat.MONOCHROME) {
            return;
        }
        var shiftX = picture.chromaFormat().shiftX();
        var shiftY = picture.chromaFormat().shiftY();
        divide(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), shiftX, shiftY, alpha,
                alphaStride, picture.width(), picture.height(), max);
        divide(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(), shiftX, shiftY, alpha,
                alphaStride, picture.width(), picture.height(), max);
    }

    /**
     * Divides the samples of an 8-bit plane by the mean alpha of the luma samples each covers.
     *
     * @param plane the color plane
     * @param stride its row stride
     * @param width its width
     * @param height its height
     * @param shiftX the horizontal subsampling shift of the plane
     * @param shiftY the vertical subsampling shift of the plane
     * @param alpha the alpha plane
     * @param alphaStride the alpha row stride
     * @param lumaWidth the luma width
     * @param lumaHeight the luma height
     * @param max the largest sample and alpha value
     */
    private static void divide(byte[] plane, int stride, int width, int height, int shiftX, int shiftY, byte[] alpha,
                               int alphaStride, int lumaWidth, int lumaHeight, int max) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var weight = meanAlpha(alpha, alphaStride, x, y, shiftX, shiftY, lumaWidth, lumaHeight);
                if (weight > 0) {
                    var index = y * stride + x;
                    var sample = plane[index] & 0xFF;
                    plane[index] = (byte) Math.min(max, (sample * max + weight / 2) / weight);
                }
            }
        }
    }

    /**
     * Divides the samples of a 16-bit plane by the mean alpha of the luma samples each covers; see
     * {@link #divide(byte[], int, int, int, int, int, byte[], int, int, int, int)}.
     *
     * @param plane the color plane
     * @param stride its row stride
     * @param width its width
     * @param height its height
     * @param shiftX the horizontal subsampling shift of the plane
     * @param shiftY the vertical subsampling shift of the plane
     * @param alpha the alpha plane
     * @param alphaStride the alpha row stride
     * @param lumaWidth the luma width
     * @param lumaHeight the luma height
     * @param max the largest sample and alpha value
     */
    private static void divide(short[] plane, int stride, int width, int height, int shiftX, int shiftY,
                               short[] alpha, int alphaStride, int lumaWidth, int lumaHeight, int max) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var weight = meanAlpha(alpha, alphaStride, x, y, shiftX, shiftY, lumaWidth, lumaHeight);
                if (weight > 0) {
                    var index = y * stride + x;
                    var sample = plane[index] & 0xFFFF;
                    plane[index] = (short) Math.min(max, ((long) sample * max + weight / 2) / weight);
                }
            }
        }
    }

    /**
     * Returns the mean, rounded to nearest, of the 8-bit alpha samples a color sample covers.
     *
     * @param alpha the alpha plane
     * @param stride the alpha row stride
     * @param x the column of the color sample
     * @param y the row of the color sample
     * @param shiftX the horizontal subsampling shift
     * @param shiftY the vertical subsampling shift
     * @param width the luma width
     * @param height the luma height
     * @return the mean alpha
     */
    private static int meanAlpha(byte[] alpha, int stride, int x, int y, int shiftX, int shiftY, int width,
                                 int height) {
        var sum = 0;
        var count = 0;
        for (var dy = 0; dy < 1 << shiftY; dy++) {
            var row = (y << shiftY) + dy;
            if (row >= height) {
                break;
            }
            for (var dx = 0; dx < 1 << shiftX; dx++) {
                var column = (x << shiftX) + dx;
                if (column < width) {
                    sum += alpha[row * stride + column] & 0xFF;
                    count++;
                }
            }
        }
        return count == 0 ? 0 : (sum + count / 2) / count;
    }

    /**
     * Returns the mean, rounded to nearest, of the 16-bit alpha samples a color sample covers; see
     * {@link #meanAlpha(byte[], int, int, int, int, int, int, int)}.
     *
     * @param alpha the alpha plane
     * @param stride the alpha row stride
     * @param x the column of the color sample
     * @param y the row of the color sample
     * @param shiftX the horizontal subsampling shift
     * @param shiftY the vertical subsampling shift
     * @param width the luma width
     * @param height the luma height
     * @return the mean alpha
     */
    private static int meanAlpha(short[] alpha, int stride, int x, int y, int shiftX, int shiftY, int width,
                                 int height) {
        var sum = 0;
        var count = 0;
        for (var dy = 0; dy < 1 << shiftY; dy++) {
            var row = (y << shiftY) + dy;
            if (row >= height) {
                break;
            }
            for (var dx = 0; dx < 1 << shiftX; dx++) {
                var column = (x << shiftX) + dx;
                if (column < width) {
                    sum += alpha[row * stride + column] & 0xFFFF;
                    count++;
                }
            }
        }
        return count == 0 ? 0 : (sum + count / 2) / count;
    }
}
