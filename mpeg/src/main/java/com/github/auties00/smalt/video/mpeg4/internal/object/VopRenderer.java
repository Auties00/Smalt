package com.github.auties00.smalt.video.mpeg4.internal.object;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.Arrays;

/**
 * Copies the displayed part of decoded VOPs into the pictures a layer hands out, masking the samples outside the
 * shape as the reference decoder's {@code dumpWithMask} does: luminance 0 and chrominance mid-gray outside the shape
 * (everywhere for a shape only layer), the shape or the first grayscale alpha plane, scaled by the VOP's constant
 * alpha, as the alpha plane.
 *
 * <p>The renderer owns one picture per sample depth and reuses it; a picture is valid until the next call. Samples of
 * more than 8 bits come in a {@link Yuv16Picture} when high bit depth output is asked for and are reduced to 8 bits
 * as {@code ImageOps.toEightBit} reduces them otherwise; samples of fewer bits are shifted up to 8 bits. Instances are
 * not thread-safe.
 */
final class VopRenderer {
    private final ColorInfo color;
    private final boolean highBitDepth;
    private YuvPicture picture8;
    private Yuv16Picture picture16;
    private Picture lastShown;
    private int[] chromaMask = new int[0];

    VopRenderer(ColorInfo color, boolean highBitDepth) {
        this.color = color;
        this.highBitDepth = highBitDepth;
    }

    /**
     * {@return the last picture rendered, or {@code null}}
     */
    Picture last() {
        return lastShown;
    }

    /**
     * Renders a VOP of a layer.
     *
     * @param decoder the decoder of the layer, for its modes
     * @param vop the planes of the VOP
     * @param width the display width
     * @param height the display height
     * @return the picture, valid until the next call
     */
    Picture render(ObjectDecoder decoder, VopPlanes vop, int width, int height) {
        var picture = target(decoder, width, height);
        render(decoder, vop, picture, width, height);
        lastShown = picture;
        return picture;
    }

    /**
     * Renders the empty picture of a VOP that was not coded.
     *
     * @return the picture, valid until the next call
     */
    Picture empty(ObjectDecoder decoder, int width, int height) {
        var picture = target(decoder, width, height);
        switch (picture) {
            case YuvPicture p -> {
                Arrays.fill(p.y(), (byte) 0);
                Arrays.fill(p.u(), (byte) 0);
                Arrays.fill(p.v(), (byte) 0);
                if (p.alpha() != null) {
                    Arrays.fill(p.alpha(), (byte) 0);
                }
            }
            case Yuv16Picture p -> {
                Arrays.fill(p.y(), (short) 0);
                Arrays.fill(p.u(), (short) 0);
                Arrays.fill(p.v(), (short) 0);
                if (p.alpha() != null) {
                    Arrays.fill(p.alpha(), (short) 0);
                }
            }
            default -> throw new IllegalStateException();
        }
        return picture;
    }

    private Picture target(ObjectDecoder decoder, int width, int height) {
        var nBits = decoder.volMode().nBits;
        var alpha = decoder.volMode().alphaUsage != VopPlanes.RECTANGLE;
        if (nBits > 8 && highBitDepth) {
            if (picture16 == null || picture16.width() != width || picture16.height() != height) {
                picture16 = Yuv16Picture.allocate(width, height, ChromaFormat.YUV420, nBits, alpha, color);
            }
            return picture16;
        }
        if (picture8 == null || picture8.width() != width || picture8.height() != height) {
            picture8 = YuvPicture.allocate(width, height, ChromaFormat.YUV420, alpha, color);
        }
        return picture8;
    }

    /**
     * Reduces a sample of a depth to 8 bits: shifted up from fewer bits, rounded down from more bits as
     * {@code ImageOps.toEightBit} rounds.
     */
    private static int eightBit(int value, int nBits) {
        if (nBits <= 8) {
            return value << (8 - nBits);
        }
        return Math.min(255, (value + (1 << (nBits - 9))) >> (nBits - 8));
    }

    private void render(ObjectDecoder decoder, VopPlanes vop, Picture picture, int width, int height) {
        var volmd = decoder.volMode();
        var nBits = volmd.nBits;
        var shaped = volmd.alphaUsage != VopPlanes.RECTANGLE && vop.by != null;
        var by = vop.by;
        var chromaWidth = (width + 1) / 2;
        var chromaHeight = (height + 1) / 2;
        if (shaped) {
            decimate(by, chromaWidth, chromaHeight, decoder.vopMode().interlace, volmd.shapeOnly);
        }
        var noTexture = volmd.shapeOnly;
        var zeroUV = 1 << (nBits - 1);
        var alphaScale = decoder.vopMode().vopConstantAlphaValue + 1;
        var eight = volmd.alphaUsage == VopPlanes.EIGHT_BIT && vop.a.length > 0;
        if (picture instanceof YuvPicture p) {
            var y = p.y();
            for (var row = 0; row < height; row++) {
                var src = vop.y.offset(0, row);
                var mask = shaped ? by.offset(0, row) : 0;
                var dst = row * p.yStride();
                for (var x = 0; x < width; x++) {
                    var value = shaped && (noTexture || by.pixels[mask + x] == 0) ? 0 : vop.y.pixels[src + x];
                    y[dst + x] = (byte) eightBit(value, nBits);
                }
            }
            for (var row = 0; row < chromaHeight; row++) {
                var src = vop.u.offset(0, row);
                var dst = row * p.uvStride();
                for (var x = 0; x < chromaWidth; x++) {
                    var masked = shaped && chromaMask[row * chromaWidth + x] == 0;
                    p.u()[dst + x] = (byte) eightBit(masked ? zeroUV : vop.u.pixels[src + x], nBits);
                    p.v()[dst + x] = (byte) eightBit(masked ? zeroUV : vop.v.pixels[src + x], nBits);
                }
            }
            if (p.alpha() != null) {
                var a = p.alpha();
                for (var row = 0; row < height; row++) {
                    var mask = by.offset(0, row);
                    var plane = eight ? vop.a[0] : null;
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
                    var value = shaped && (noTexture || by.pixels[mask + x] == 0) ? 0 : vop.y.pixels[src + x];
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
                for (var row = 0; row < height; row++) {
                    var mask = by.offset(0, row);
                    var plane = eight ? vop.a[0] : null;
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
     * ({@code decimateBinaryShapeFrom}); a shape only layer masks every sample.
     */
    private void decimate(Plane by, int chromaWidth, int chromaHeight, boolean interlace, boolean shapeOnly) {
        if (chromaMask.length < chromaWidth * chromaHeight) {
            chromaMask = new int[chromaWidth * chromaHeight];
        }
        if (shapeOnly) {
            Arrays.fill(chromaMask, 0, chromaWidth * chromaHeight, 0);
            return;
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
