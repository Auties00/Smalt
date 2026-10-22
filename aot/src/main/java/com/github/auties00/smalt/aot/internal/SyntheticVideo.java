package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Synthesizes the 4:2:0 video the training workloads code, deterministically.
 *
 * <p>The training run's profiles cover only the branches its inputs take, so the content spans the
 * properties that steer the codecs' decisions: smooth natural texture with sub-sample motion
 * ({@link #natural}), and the content classes of {@link #content}: high-frequency texture, fast
 * global motion reaching the frame borders, and scene cuts with fades.
 */
final class SyntheticVideo {
    /**
     * The number of content classes {@link #content} synthesizes.
     */
    static final int CONTENT_CLASSES = 4;

    /**
     * The side of the square lattice of random values behind the value noise.
     */
    private static final int LATTICE = 64;

    /**
     * Prevents instantiation.
     */
    private SyntheticVideo() {
        throw new AssertionError();
    }

    /**
     * Synthesizes natural-looking video: smooth value noise and sinusoids panning at sub-sample
     * speeds, and a moving bright spot.
     *
     * <p>Every frame also has a flat band over its left quarter, and every sixth frame is black:
     * flat blocks have zero residuals and are skipped in inter frames, and black intra blocks
     * quantize to zero, the branches real content takes on letterboxing, fades, slides and a
     * covered camera.
     *
     * @param width the width in samples
     * @param height the height in samples
     * @param frames the number of frames
     * @return the pictures, in limited-range BT.709
     */
    static List<YuvPicture> natural(int width, int height, int frames) {
        var lattice = lattice();
        var pictures = new ArrayList<YuvPicture>(frames);
        for (var t = 0; t < frames; t++) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            var y = picture.y();
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    var noise = valueNoise(lattice, (column + t * 1.37) / 9.0, (row - t * 0.61) / 9.0);
                    var wave = 40 * StrictMath.sin((column - t * 2.3) / 23.0) * StrictMath.cos((row + t * 1.1) / 31.0);
                    var dx = column - width / 2.0 - 60 * StrictMath.sin(t / 9.0);
                    var dy = row - height / 2.0;
                    var spot = dx * dx + dy * dy < 1600 ? 50 : 0;
                    y[row * picture.yStride() + column] =
                            (byte) Math.clamp((int) (60 + noise * 0.5 + wave + spot), 0, 255);
                }
            }
            for (var row = 0; row < picture.chromaHeight(); row++) {
                for (var column = 0; column < picture.chromaWidth(); column++) {
                    var noise = valueNoise(lattice, (column + t * 0.7) / 5.0 + 17, (row + t * 0.3) / 5.0 + 5);
                    picture.u()[row * picture.uvStride() + column] = (byte) Math.clamp((int) (96 + noise / 4), 0, 255);
                    picture.v()[row * picture.uvStride() + column] = (byte) Math.clamp((int) (160 - noise / 4), 0, 255);
                }
            }
            var flatColumns = t % 6 == 5 ? width : width / 4;
            var flatLuma = t % 6 == 5 ? 16 : 128;
            for (var row = 0; row < height; row++) {
                Arrays.fill(y, row * picture.yStride(), row * picture.yStride() + flatColumns, (byte) flatLuma);
            }
            for (var row = 0; row < picture.chromaHeight(); row++) {
                var offset = row * picture.uvStride();
                Arrays.fill(picture.u(), offset, offset + flatColumns / 2, (byte) 128);
                Arrays.fill(picture.v(), offset, offset + flatColumns / 2, (byte) 128);
            }
            pictures.add(picture);
        }
        return pictures;
    }

    /**
     * Synthesizes a 10-bit 4:2:0 picture of natural content in limited-range BT.2020 with the PQ
     * transfer of HDR10 video.
     *
     * @param width the width in samples
     * @param height the height in samples
     * @return the picture
     */
    static Yuv16Picture hdr(int width, int height) {
        var source = natural(width, height, 1).getFirst();
        var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ);
        var picture = Yuv16Picture.allocate(width, height, ChromaFormat.YUV420, 10, false, color);
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                picture.y()[row * picture.yStride() + column] = (short) ((source.y()[row * source.yStride() + column] & 0xFF) << 2);
            }
        }
        for (var row = 0; row < picture.chromaHeight(); row++) {
            for (var column = 0; column < picture.chromaWidth(); column++) {
                picture.u()[row * picture.uvStride() + column] = (short) ((source.u()[row * source.uvStride() + column] & 0xFF) << 2);
                picture.v()[row * picture.uvStride() + column] = (short) ((source.v()[row * source.uvStride() + column] & 0xFF) << 2);
            }
        }
        return picture;
    }

    /**
     * Synthesizes a clip of one content class.
     *
     * <p>The classes are:
     * <ul>
     * <li>{@code 0}: natural content, as {@link #natural};</li>
     * <li>{@code 1}: texture, stripes, a checkerboard and a fine grid with moving phase, which have
     *     many non-zero coefficients;</li>
     * <li>{@code 2}: motion, the first natural picture moving by up to 40 samples per frame and
     *     wrapping around, so motion vectors reach the search range and blocks reference samples
     *     outside the frame;</li>
     * <li>{@code 3}: cuts, two scenes alternating every five frames with a fade to black and back
     *     in between.</li>
     * </ul>
     *
     * @param kind the content class, from {@code 0} to {@link #CONTENT_CLASSES} minus one
     * @param width the width in samples
     * @param height the height in samples
     * @param frames the number of frames
     * @return the pictures, in limited-range BT.709
     * @throws IllegalArgumentException if {@code kind} is not a content class
     */
    static List<YuvPicture> content(int kind, int width, int height, int frames) {
        if (kind < 0 || kind >= CONTENT_CLASSES) {
            throw new IllegalArgumentException("not a content class: " + kind);
        }
        if (kind == 0) {
            return natural(width, height, frames);
        }
        var base = natural(width, height, 1).getFirst();
        var pictures = new ArrayList<YuvPicture>(frames);
        for (var t = 0; t < frames; t++) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            for (var row = 0; row < height; row++) {
                for (var column = 0; column < width; column++) {
                    picture.y()[row * picture.yStride() + column] = (byte) Math.clamp(switch (kind) {
                        case 1 -> texture(column, row, t);
                        case 2 -> moved(base, column, row, t, width, height);
                        default -> cut(base, column, row, t);
                    }, 0, 255);
                }
            }
            for (var row = 0; row < picture.chromaHeight(); row++) {
                for (var column = 0; column < picture.chromaWidth(); column++) {
                    var shift = kind == 2 ? t * 9 : kind == 3 && t / 5 % 2 == 1 ? 40 : 0;
                    picture.u()[row * picture.uvStride() + column] =
                            (byte) Math.clamp(96 + (column * 3 + shift + row) % 64, 0, 255);
                    picture.v()[row * picture.uvStride() + column] =
                            (byte) Math.clamp(160 - (row * 5 + shift) % 48, 0, 255);
                }
            }
            pictures.add(picture);
        }
        return pictures;
    }

    /**
     * Returns a luma sample of the texture class.
     *
     * @param column the luma column
     * @param row the luma row
     * @param t the frame index
     * @return the sample, unclamped
     */
    private static int texture(int column, int row, int t) {
        var stripes = ((column + 3 * t) / 2 % 2) * 60;
        var checker = (((column >> 3) + (row >> 3) + t) & 1) * 50;
        var grid = (column * 31 + row * 17 + t * 7) % 13;
        return 40 + stripes + checker + grid * 6;
    }

    /**
     * Returns a luma sample of the motion class.
     *
     * @param base the picture that moves
     * @param column the luma column
     * @param row the luma row
     * @param t the frame index
     * @param width the width in samples
     * @param height the height in samples
     * @return the sample
     */
    private static int moved(YuvPicture base, int column, int row, int t, int width, int height) {
        var dx = (int) (40 * StrictMath.sin(t / 3.0));
        var dy = (int) (24 * StrictMath.cos(t / 4.0));
        var x = Math.floorMod(column + dx * t / 2, width);
        var y = Math.floorMod(row + dy * t / 3, height);
        return base.y()[y * base.yStride() + x] & 0xFF;
    }

    /**
     * Returns a luma sample of the cuts class.
     *
     * @param base the picture of the first scene
     * @param column the luma column
     * @param row the luma row
     * @param t the frame index
     * @return the sample, unclamped
     */
    private static int cut(YuvPicture base, int column, int row, int t) {
        var value = t / 5 % 2 == 0 ? base.y()[row * base.yStride() + column] & 0xFF
                : 30 + ((column / 40 + row / 30) % 2) * 160;
        var phase = t % 5;
        var fade = phase == 4 ? 0.2 : phase == 0 ? 0.6 : 1.0;
        return (int) (16 + (value - 16) * fade);
    }

    /**
     * Returns the fixed lattice of pseudo-random byte values the value noise interpolates.
     *
     * @return {@link #LATTICE} squared values from 0 to 255
     */
    private static int[] lattice() {
        var lattice = new int[LATTICE * LATTICE];
        var seed = 0x9E3779B9L;
        for (var i = 0; i < lattice.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            lattice[i] = (int) (seed >>> 56);
        }
        return lattice;
    }

    /**
     * Returns smoothly interpolated value noise at a point of the plane, the lattice wrapping
     * around every {@link #LATTICE} units.
     *
     * @param lattice the lattice values
     * @param u the horizontal coordinate
     * @param v the vertical coordinate
     * @return the noise, from 0 to 255
     */
    private static double valueNoise(int[] lattice, double u, double v) {
        var x0 = (int) Math.floor(u);
        var y0 = (int) Math.floor(v);
        var fx = u - x0;
        var fy = v - y0;
        var mask = LATTICE - 1;
        var a = lattice[(y0 & mask) * LATTICE + (x0 & mask)];
        var b = lattice[(y0 & mask) * LATTICE + ((x0 + 1) & mask)];
        var c = lattice[((y0 + 1) & mask) * LATTICE + (x0 & mask)];
        var d = lattice[((y0 + 1) & mask) * LATTICE + ((x0 + 1) & mask)];
        var sx = fx * fx * (3 - 2 * fx);
        var sy = fy * fy * (3 - 2 * fy);
        return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy;
    }
}
