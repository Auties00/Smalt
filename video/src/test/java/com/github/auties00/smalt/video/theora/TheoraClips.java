package com.github.auties00.smalt.video.theora;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Synthesizes deterministic clips for the inter frame tests: a textured background panning by half samples above a
 * static textured band (the bottom quarter), a rigid textured square moving across both, a small patch of fresh
 * noise in every frame and, from frame {@code cut} on, a different scene.
 *
 * <p>The background texture is a lattice of hashed values sampled bilinearly, so that a pan by a fraction of a
 * sample is a smooth sub-sample shift. The square covers the background where it passes, which the golden frame
 * then holds, and moves at another speed than the background, so that the macro blocks along its edges hold two
 * motions.
 */
final class TheoraClips {
    private TheoraClips() {
    }

    private static int hash(int x, int y, int salt) {
        var h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ salt * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0xFF;
    }

    private static double lattice(double u, double v, int salt, int scale) {
        var x = u / scale;
        var y = v / scale;
        var x0 = (int) Math.floor(x);
        var y0 = (int) Math.floor(y);
        var fx = x - x0;
        var fy = y - y0;
        var a = hash(x0, y0, salt);
        var b = hash(x0 + 1, y0, salt);
        var c = hash(x0, y0 + 1, salt);
        var d = hash(x0 + 1, y0 + 1, salt);
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    private static double texture(double u, double v, int scene) {
        return 0.55 * lattice(u, v, 11 + scene, 6) + 0.3 * lattice(u, v, 23 + scene, 2) + 0.15 * 128
                + 20 * Math.sin((u + 3 * scene) / 13.0);
    }

    /**
     * Returns frame {@code t} of a pan of the whole picture over the background texture, moving by
     * {@code (velocityX, velocityY)} samples a frame, so that content enters from the edges at any speed.
     *
     * @param width the picture width
     * @param height the picture height
     * @param format the chroma format
     * @param t the frame index
     * @param velocityX the horizontal motion, in samples a frame
     * @param velocityY the vertical motion, in samples a frame
     * @return the picture
     */
    static YuvPicture pan(int width, int height, ChromaFormat format, int t, double velocityX, double velocityY) {
        var picture = YuvPicture.allocate(width, height, format, false, ColorInfo.BT601_LIMITED);
        var panX = velocityX * t;
        var panY = velocityY * t;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) Math.clamp(Math.round(texture(x + panX, y + panY, 0)),
                        16, 235);
            }
        }
        var cw = picture.chromaWidth();
        var ch = picture.chromaHeight();
        var sx = (double) width / cw;
        var sy = (double) height / ch;
        for (var y = 0; y < ch; y++) {
            for (var x = 0; x < cw; x++) {
                var u = x * sx + panX;
                var v = y * sy + panY;
                picture.u()[y * picture.uvStride() + x] = (byte) Math.clamp(Math.round(
                        128 + 0.4 * (lattice(u, v, 31, 8) - 128)), 16, 240);
                picture.v()[y * picture.uvStride() + x] = (byte) Math.clamp(Math.round(
                        128 - 0.3 * (lattice(u, v, 47, 8) - 128)), 16, 240);
            }
        }
        return picture;
    }

    /**
     * Returns frame {@code t} of a clip.
     *
     * @param width the picture width
     * @param height the picture height
     * @param format the chroma format
     * @param t the frame index
     * @param cut the first frame of the second scene
     * @return the picture
     */
    static YuvPicture frame(int width, int height, ChromaFormat format, int t, int cut) {
        var picture = YuvPicture.allocate(width, height, format, false, ColorInfo.BT601_LIMITED);
        var scene = t < cut ? 0 : 1;
        var time = t < cut ? t : t - cut;
        var panX = scene == 0 ? 1.5 * time : -0.5 * time;
        var panY = scene == 0 ? 0.5 * time : time;
        var squareX = 4 + 3 * time;
        var squareY = 6 + 2 * time;
        var size = Math.max(8, Math.min(24, Math.min(width, height) / 3));
        var noiseX = width * 2 / 3;
        var noiseY = height / 2;
        var band = height * 3 / 4;
        var cw = picture.chromaWidth();
        var ch = picture.chromaHeight();
        var sx = (double) width / cw;
        var sy = (double) height / ch;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                double value;
                var inSquare = x >= squareX && x < squareX + size && y >= squareY && y < squareY + size;
                if (inSquare) {
                    var u = x - squareX;
                    var v = y - squareY;
                    value = ((u >> 2) + (v >> 2) & 1) == 0 ? 200 - 2 * u : 50 + 3 * v;
                } else if (x >= noiseX && x < noiseX + 12 && y >= noiseY && y < noiseY + 12) {
                    value = 40 + 0.7 * hash(x, y, 1000 + t);
                } else if (y >= band) {
                    value = texture(x, y, scene + 5);
                } else {
                    value = texture(x + panX, y + panY, scene);
                }
                picture.y()[y * picture.yStride() + x] = (byte) Math.clamp(Math.round(value), 16, 235);
            }
        }
        for (var y = 0; y < ch; y++) {
            for (var x = 0; x < cw; x++) {
                var u = x * sx;
                var v = y * sy;
                var inSquare = u >= squareX && u < squareX + size && v >= squareY && v < squareY + size;
                double cb;
                double cr;
                if (inSquare) {
                    cb = 90;
                    cr = 170;
                } else {
                    var moving = v < band;
                    cb = 128 + 0.4 * (lattice(u + (moving ? panX : 0), v + (moving ? panY : 0), 31 + scene, 8) - 128);
                    cr = 128 - 0.3 * (lattice(u + (moving ? panX : 0), v + (moving ? panY : 0), 47 + scene, 8) - 128);
                }
                picture.u()[y * picture.uvStride() + x] = (byte) Math.clamp(Math.round(cb), 16, 240);
                picture.v()[y * picture.uvStride() + x] = (byte) Math.clamp(Math.round(cr), 16, 240);
            }
        }
        return picture;
    }
}
