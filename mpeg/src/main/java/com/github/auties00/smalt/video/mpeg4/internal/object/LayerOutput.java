package com.github.auties00.smalt.video.mpeg4.internal.object;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Arrays;

/**
 * Turns the VOPs of one layer into pictures as the reference decoder of ISO/IEC 14496-5 writes them
 * ({@code dumpFrame} and {@code dumpFrameNonCoded}): the display window of the planes, with the samples outside an
 * arbitrary shape set to 0 (luminance) and mid-gray (chrominance), and the shape or the first grayscale alpha plane
 * as the alpha plane.
 *
 * <p>The pictures are reused: each one is valid until the next call.
 */
final class LayerOutput {
    private final ObjectDecoder decoder;
    private final ColorInfo color;
    private final boolean highBitDepth;
    private final int width;
    private final int height;
    private boolean emitNotCoded;
    private Picture lastShown;
    private YuvPicture picture8;
    private Yuv16Picture picture16;
    private int[] chromaMask = new int[0];

    /**
     * Builds the output of a layer.
     *
     * @param decoder the decoder of the layer
     * @param width the width of the pictures
     * @param height the height of the pictures
     * @param color the color description of the pictures
     * @param highBitDepth whether samples of more than 8 bits keep their depth
     */
    LayerOutput(ObjectDecoder decoder, int width, int height, ColorInfo color, boolean highBitDepth) {
        this.decoder = decoder;
        this.width = width;
        this.height = height;
        this.color = color;
        this.highBitDepth = highBitDepth;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    boolean hasAlpha() {
        return decoder.volMode().alphaUsage != VopPlanes.RECTANGLE;
    }

    void emitNotCoded(boolean enabled) {
        this.emitNotCoded = enabled;
    }

    /**
     * Hands out the picture of a VOP that was not coded, when such VOPs produce one: the previous picture of a
     * rectangular layer and an empty one of a shaped layer.
     */
    void notCoded(long time, ObjectStream.Sink sink) {
        if (!emitNotCoded) {
            return;
        }
        if (!hasAlpha() && lastShown != null) {
            sink.picture(lastShown, time, false);
            return;
        }
        var picture = target();
        clear(picture);
        sink.picture(picture, time, false);
    }

    /**
     * Hands out the picture of a decoded VOP.
     */
    void show(VopPlanes vop, long time, boolean key, ObjectStream.Sink sink) {
        var picture = target();
        render(vop, picture);
        lastShown = picture;
        sink.picture(picture, time, key);
    }

    void reset() {
        lastShown = null;
    }

    private static void clear(Picture picture) {
        if (picture instanceof YuvPicture p) {
            Arrays.fill(p.y(), (byte) 0);
            Arrays.fill(p.u(), (byte) 0);
            Arrays.fill(p.v(), (byte) 0);
            if (p.alpha() != null) {
                Arrays.fill(p.alpha(), (byte) 0);
            }
        } else if (picture instanceof Yuv16Picture p) {
            Arrays.fill(p.y(), (short) 0);
            Arrays.fill(p.u(), (short) 0);
            Arrays.fill(p.v(), (short) 0);
            if (p.alpha() != null) {
                Arrays.fill(p.alpha(), (short) 0);
            }
        }
    }

    private Picture target() {
        var nBits = decoder.volMode().nBits;
        var alpha = hasAlpha();
        if (nBits > 8 && highBitDepth) {
            if (picture16 == null) {
                picture16 = Yuv16Picture.allocate(width, height, ChromaFormat.YUV420, nBits, alpha, color);
            }
            return picture16;
        }
        if (picture8 == null) {
            picture8 = YuvPicture.allocate(width, height, ChromaFormat.YUV420, alpha, color);
        }
        return picture8;
    }

    /**
     * Copies the displayed part of a VOP into a picture, masking the samples outside its shape as the reference
     * decoder's {@code dumpWithMask} does; samples of fewer than 8 bits are scaled up and, unless the picture keeps
     * them, samples of more than 8 bits are truncated to 8 bits.
     */
    private void render(VopPlanes vop, Picture picture) {
        var volmd = decoder.volMode();
        var nBits = volmd.nBits;
        var shaped = volmd.alphaUsage != VopPlanes.RECTANGLE && vop.by != null;
        var by = vop.by;
        var chromaWidth = (width + 1) / 2;
        var chromaHeight = (height + 1) / 2;
        if (shaped) {
            decimate(by, chromaWidth, chromaHeight, decoder.vopMode().interlace);
        }
        var zeroUV = 1 << (nBits - 1);
        var alphaScale = decoder.vopMode().vopConstantAlphaValue + 1;
        var eight = volmd.alphaUsage == VopPlanes.EIGHT_BIT && vop.a.length > 0;
        if (picture instanceof YuvPicture p) {
            var up = Math.max(0, 8 - nBits);
            var down = Math.max(0, nBits - 8);
            var y = p.y();
            for (var row = 0; row < height; row++) {
                var src = vop.y.offset(0, row);
                var mask = shaped ? by.offset(0, row) : 0;
                var dst = row * p.yStride();
                for (var x = 0; x < width; x++) {
                    var value = shaped && by.pixels[mask + x] == 0 ? 0 : vop.y.pixels[src + x];
                    y[dst + x] = (byte) (value << up >> down);
                }
            }
            for (var row = 0; row < chromaHeight; row++) {
                var src = vop.u.offset(0, row);
                var dst = row * p.uvStride();
                for (var x = 0; x < chromaWidth; x++) {
                    var masked = shaped && chromaMask[row * chromaWidth + x] == 0;
                    p.u()[dst + x] = (byte) ((masked ? zeroUV : vop.u.pixels[src + x]) << up >> down);
                    p.v()[dst + x] = (byte) ((masked ? zeroUV : vop.v.pixels[src + x]) << up >> down);
                }
            }
            if (p.alpha() != null) {
                var a = p.alpha();
                var plane = eight ? vop.a[0] : null;
                for (var row = 0; row < height; row++) {
                    var mask = by.offset(0, row);
                    var src = eight ? plane.offset(0, row) : 0;
                    var dst = row * p.alphaStride();
                    for (var x = 0; x < width; x++) {
                        int value;
                        if (eight) {
                            value = by.pixels[mask + x] == 0 ? 0 : plane.pixels[src + x];
                        } else {
                            value = by.pixels[mask + x];
                        }
                        if (alphaScale != 256) {
                            value = (value * alphaScale) >> 8;
                        }
                        a[dst + x] = (byte) value;
                    }
                }
            }
        } else if (picture instanceof Yuv16Picture p) {
            var y = p.y();
            for (var row = 0; row < height; row++) {
                var src = vop.y.offset(0, row);
                var mask = shaped ? by.offset(0, row) : 0;
                var dst = row * p.yStride();
                for (var x = 0; x < width; x++) {
                    var value = shaped && by.pixels[mask + x] == 0 ? 0 : vop.y.pixels[src + x];
                    y[dst + x] = (short) value;
                }
            }
            for (var row = 0; row < chromaHeight; row++) {
                var src = vop.u.offset(0, row);
                var dst = row * p.uvStride();
                for (var x = 0; x < chromaWidth; x++) {
                    var masked = shaped && chromaMask[row * chromaWidth + x] == 0;
                    p.u()[dst + x] = (short) (masked ? zeroUV : vop.u.pixels[src + x]);
                    p.v()[dst + x] = (short) (masked ? zeroUV : vop.v.pixels[src + x]);
                }
            }
            if (p.alpha() != null) {
                var a = p.alpha();
                var max = (1 << nBits) - 1;
                var plane = eight ? vop.a[0] : null;
                for (var row = 0; row < height; row++) {
                    var mask = by.offset(0, row);
                    var src = eight ? plane.offset(0, row) : 0;
                    var dst = row * p.alphaStride();
                    for (var x = 0; x < width; x++) {
                        var value = eight ? (by.pixels[mask + x] == 0 ? 0 : plane.pixels[src + x])
                                : by.pixels[mask + x];
                        if (alphaScale != 256) {
                            value = (value * alphaScale) >> 8;
                        }
                        a[dst + x] = (short) ((value * max + 127) / 255);
                    }
                }
            }
        }
    }

    /**
     * Computes the chrominance mask of a shape: a chrominance sample is inside when any of its luminance samples is
     * ({@code decimateBinaryShapeFrom}).
     */
    private void decimate(Plane by, int chromaWidth, int chromaHeight, boolean interlace) {
        if (chromaMask.length < chromaWidth * chromaHeight) {
            chromaMask = new int[chromaWidth * chromaHeight];
        }
        for (var y = 0; y < chromaHeight; y++) {
            for (var x = 0; x < chromaWidth; x++) {
                int top;
                int next;
                if (!interlace) {
                    top = 2 * y;
                    next = top + 1;
                } else {
                    top = 4 * (y >> 1) + (y & 1);
                    next = top + 2;
                }
                var a = by.pixel(2 * x, top) | by.pixel(2 * x + 1, top) | by.pixel(2 * x, next)
                        | by.pixel(2 * x + 1, next);
                chromaMask[y * chromaWidth + x] = a != 0 ? 255 : 0;
            }
        }
    }
}
