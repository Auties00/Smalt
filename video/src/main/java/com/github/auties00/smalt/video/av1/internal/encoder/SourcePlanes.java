package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.Planes;

/**
 * Reads and writes the planes of the pictures the inter encoders code: an 8-bit
 * {@link YuvPicture} or a 10-bit or 12-bit {@link Yuv16Picture}, of any chroma format.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class SourcePlanes {
    /**
     * Prevents instantiation.
     */
    private SourcePlanes() {
        throw new AssertionError();
    }

    /**
     * Returns the chroma format of a picture.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @return the chroma format
     */
    static ChromaFormat format(Picture p) {
        return p instanceof YuvPicture yuv ? yuv.chromaFormat() : ((Yuv16Picture) p).chromaFormat();
    }

    /**
     * Returns the bit depth of a picture's samples.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @return 8 for a {@link YuvPicture}, the picture's depth otherwise
     */
    static int bitDepth(Picture p) {
        return p instanceof Yuv16Picture yuv ? yuv.bitDepth() : 8;
    }

    /**
     * Returns the color description of a picture.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @return the color description
     */
    static ColorInfo color(Picture p) {
        return p instanceof YuvPicture yuv ? yuv.color() : ((Yuv16Picture) p).color();
    }

    /**
     * Returns the width of a plane of a picture.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param plane the plane: 0 luma, 1 U, 2 V
     * @return the width in samples
     */
    static int width(Picture p, int plane) {
        return plane == 0 ? p.width() : format(p).chromaWidth(p.width());
    }

    /**
     * Returns the height of a plane of a picture.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param plane the plane: 0 luma, 1 U, 2 V
     * @return the height in samples
     */
    static int height(Picture p, int plane) {
        return plane == 0 ? p.height() : format(p).chromaHeight(p.height());
    }

    /**
     * Copies rows of one plane of a picture into a 16-bit plane.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param plane the plane: 0 luma, 1 U, 2 V
     * @param first the first row
     * @param end the row after the last
     * @param dst the destination plane
     * @param dstOff the index of the first copied sample, that of row {@code first}
     * @param dstStride the destination stride
     */
    static void widen(Picture p, int plane, int first, int end, short[] dst, int dstOff, int dstStride) {
        var w = width(p, plane);
        if (p instanceof YuvPicture yuv) {
            var src = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
            var srcStride = plane == 0 ? yuv.yStride() : yuv.uvStride();
            for (var y = first; y < end; y++) {
                var s = y * srcStride;
                var d = dstOff + (y - first) * dstStride;
                for (var x = 0; x < w; x++) {
                    dst[d + x] = (short) (src[s + x] & 0xFF);
                }
            }
            return;
        }
        var yuv = (Yuv16Picture) p;
        var src = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
        var srcStride = plane == 0 ? yuv.yStride() : yuv.uvStride();
        Planes.copy(src, first * srcStride, srcStride, dst, dstOff, dstStride, w, end - first);
    }

    /**
     * Copies a 16-bit plane into one plane of a picture, the reverse of {@link #widen}.
     *
     * @param src the samples, within the picture's range
     * @param srcStride the source stride
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param plane the plane: 0 luma, 1 U, 2 V
     */
    static void narrow(short[] src, int srcStride, Picture p, int plane) {
        var w = width(p, plane);
        var h = height(p, plane);
        if (p instanceof YuvPicture yuv) {
            var dst = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
            var dstStride = plane == 0 ? yuv.yStride() : yuv.uvStride();
            for (var y = 0; y < h; y++) {
                var s = y * srcStride;
                var d = y * dstStride;
                for (var x = 0; x < w; x++) {
                    dst[d + x] = (byte) src[s + x];
                }
            }
            return;
        }
        var yuv = (Yuv16Picture) p;
        var dst = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
        Planes.copy(src, 0, srcStride, dst, 0, plane == 0 ? yuv.yStride() : yuv.uvStride(), w, h);
    }

    /**
     * Returns a copy of a picture with its own planes.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @return the copy, of the same class, size, format, depth and color
     */
    static Picture copy(Picture p) {
        var w = p.width();
        var h = p.height();
        var format = format(p);
        var cw = format.chromaWidth(w);
        var ch = format.chromaHeight(h);
        if (p instanceof YuvPicture yuv) {
            var out = YuvPicture.allocate(w, h, format, false, yuv.color());
            Planes.copy(yuv.y(), 0, yuv.yStride(), out.y(), 0, out.yStride(), w, h);
            if (format.hasChroma()) {
                Planes.copy(yuv.u(), 0, yuv.uvStride(), out.u(), 0, out.uvStride(), cw, ch);
                Planes.copy(yuv.v(), 0, yuv.uvStride(), out.v(), 0, out.uvStride(), cw, ch);
            }
            return out;
        }
        var yuv = (Yuv16Picture) p;
        var out = Yuv16Picture.allocate(w, h, format, yuv.bitDepth(), false, yuv.color());
        Planes.copy(yuv.y(), 0, yuv.yStride(), out.y(), 0, out.yStride(), w, h);
        if (format.hasChroma()) {
            Planes.copy(yuv.u(), 0, yuv.uvStride(), out.u(), 0, out.uvStride(), cw, ch);
            Planes.copy(yuv.v(), 0, yuv.uvStride(), out.v(), 0, out.uvStride(), cw, ch);
        }
        return out;
    }

    /**
     * Allocates a picture of the class, size, format, depth and color of another.
     *
     * @param p the model picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @return the new picture
     */
    static Picture allocateLike(Picture p) {
        if (p instanceof YuvPicture yuv) {
            return YuvPicture.allocate(p.width(), p.height(), yuv.chromaFormat(), false, yuv.color());
        }
        var yuv = (Yuv16Picture) p;
        return Yuv16Picture.allocate(p.width(), p.height(), yuv.chromaFormat(), yuv.bitDepth(), false, yuv.color());
    }

    /**
     * Stores one sample of a plane of a picture.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param plane the plane: 0 luma, 1 U, 2 V
     * @param x the column
     * @param y the row
     * @param value the sample, within the picture's range
     */
    static void store(Picture p, int plane, int x, int y, int value) {
        if (p instanceof YuvPicture yuv) {
            var dst = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
            dst[y * (plane == 0 ? yuv.yStride() : yuv.uvStride()) + x] = (byte) value;
            return;
        }
        var yuv = (Yuv16Picture) p;
        var dst = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
        dst[y * (plane == 0 ? yuv.yStride() : yuv.uvStride()) + x] = (short) value;
    }

    /**
     * Returns a picture's luma plane at the 8-bit scale, of stride its width: the samples
     * themselves at 8 bits, rounded to 8 bits above.
     *
     * @param p the picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @return the luma plane
     */
    static short[] luma8(Picture p) {
        var w = p.width();
        var h = p.height();
        var out = new short[w * h];
        widen(p, 0, 0, h, out, 0, w);
        var shift = bitDepth(p) - 8;
        if (shift > 0) {
            var round = 1 << (shift - 1);
            for (var i = 0; i < out.length; i++) {
                out[i] = (short) Math.min(255, (out[i] + round) >> shift);
            }
        }
        return out;
    }
}
