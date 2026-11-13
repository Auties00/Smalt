package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

import java.util.List;

/**
 * Blends each frame toward a color by the fades' gain at the time it is shown.
 *
 * <p>A sample {@code s} of a plane whose fade-color value is {@code c} becomes {@code c + (s - c) g}, rounded to the
 * nearest integer, with {@code g} the product of the fades' gains at the frame's time; alpha planes are kept. A
 * frame at gain 1 passes through unchanged. The color is converted to the picture's color description: the matrix
 * and range of a YUV picture, with BT.709 for a matrix that defines no coefficients, and code values scaled to its
 * bit depth. An instance is not thread-safe.
 */
final class FadeStep implements FrameSink {
    /**
     * The fixed-point scale of the blend weight.
     */
    private static final int ONE = 1 << 16;

    private final FrameSink next;
    private final List<Fade> fades;
    private final Rational timeBase;

    /**
     * The output duration in microseconds, or {@code -1} when unknown.
     */
    private final long outputMicros;

    /**
     * The picture faded frames are written to, reused.
     */
    private Picture scratch;

    /**
     * The color description {@link #yuv} was converted for, or {@code null} before the first YUV picture.
     */
    private ColorInfo yuvColor;

    /**
     * The bit depth {@link #yuv} was converted for.
     */
    private int yuvDepth;

    /**
     * The fade color as the luma, Cb and Cr code values of the last YUV picture's description.
     */
    private int[] yuv;

    /**
     * The fade color at 8 bits per component.
     */
    private final int[] rgb8;

    /**
     * The fade color at 16 bits per component.
     */
    private final int[] rgb16;

    /**
     * Constructs a step.
     *
     * @param next receives the faded frames
     * @param fades the fades to apply
     * @param rgb the fade color, {@code 0xRRGGBB}
     * @param timeBase the time base of the frame times
     * @param outputMicros the output duration in microseconds, or {@code -1} when unknown
     * @throws IllegalArgumentException if a fade-out at the end has no known output duration
     */
    FadeStep(FrameSink next, List<Fade> fades, int rgb, Rational timeBase, long outputMicros) {
        this.next = next;
        this.fades = List.copyOf(fades);
        var red = (rgb >> 16) & 0xFF;
        var green = (rgb >> 8) & 0xFF;
        var blue = rgb & 0xFF;
        this.rgb8 = new int[]{red, green, blue};
        this.rgb16 = new int[]{red * 257, green * 257, blue * 257};
        this.timeBase = timeBase;
        this.outputMicros = outputMicros;
        if (outputMicros < 0 && this.fades.stream().anyMatch(Fade::endsWithOutput)) {
            throw new IllegalArgumentException("a fade-out at the end of the output needs a known duration: the input "
                    + "declares none, so give the fade a start or the transcode a range");
        }
    }

    @Override
    public void accept(Picture picture, long time, long duration, FieldOrder scan) {
        var gain = Fade.gain(fades, timeBase.toMicros(time), outputMicros);
        if (gain >= 1) {
            next.accept(picture, time, duration, scan);
            return;
        }
        scratch = PictureCopy.copy(picture, scratch);
        blend(scratch, (int) Math.round(gain * ONE));
        next.accept(scratch, time, duration, scan);
    }

    @Override
    public void finish(long end) {
        next.finish(end);
    }

    /**
     * Blends a picture toward the color in place.
     *
     * @param picture the picture to change, owned by this step
     * @param weight the weight of the picture, from 0 to {@link #ONE}
     */
    private void blend(Picture picture, int weight) {
        switch (picture) {
            case YuvPicture yuv -> {
                var color = yuvCode(yuv.color(), 8);
                blend8(yuv.y(), yuv.yStride(), yuv.width(), yuv.height(), color[0], weight);
                if (yuv.chromaFormat().hasChroma()) {
                    blend8(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), color[1], weight);
                    blend8(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), color[2], weight);
                }
            }
            case Yuv16Picture yuv -> {
                var color = yuvCode(yuv.color(), yuv.bitDepth());
                blend16(yuv.y(), yuv.yStride(), yuv.width(), yuv.height(), color[0], weight);
                if (yuv.chromaFormat().hasChroma()) {
                    blend16(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), color[1], weight);
                    blend16(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), color[2], weight);
                }
            }
            case RgbaPicture rgba -> blendPacked8(rgba.pixels(), rgba.stride(), rgba.width(), rgba.height(), rgb8,
                    weight);
            case Rgba16Picture rgba -> blendPacked16(rgba.pixels(), rgba.stride(), rgba.width(), rgba.height(), rgb16,
                    weight);
            default -> throw new MediaException.Unsupported("fade", "fading " + picture.getClass().getSimpleName()
                    + " pictures is not supported");
        }
    }

    /**
     * Returns the fade color as code values of a YUV picture, converting it once per color description and depth.
     *
     * @param color the picture's color description
     * @param depth the bit depth
     * @return the luma, Cb and Cr code values
     */
    private int[] yuvCode(ColorInfo color, int depth) {
        if (yuv == null || depth != yuvDepth || !color.equals(yuvColor)) {
            yuv = yuvColor(color, rgb8[0], rgb8[1], rgb8[2], depth);
            yuvColor = color;
            yuvDepth = depth;
        }
        return yuv;
    }

    /**
     * Converts the fade color to the code values of a YUV picture.
     *
     * @param color the picture's color description
     * @param red the red component, 0 to 255
     * @param green the green component, 0 to 255
     * @param blue the blue component, 0 to 255
     * @param depth the bit depth
     * @return the luma, Cb and Cr code values
     */
    static int[] yuvColor(ColorInfo color, int red, int green, int blue, int depth) {
        var max = (1 << depth) - 1;
        var r = red / 255.0;
        var g = green / 255.0;
        var b = blue / 255.0;
        var matrix = color.matrix();
        var full = color.range() == ColorRange.FULL;
        double y;
        double cb;
        double cr;
        if (matrix == MatrixCoefficients.IDENTITY) {
            y = g;
            cb = b - 0.5;
            cr = r - 0.5;
        } else {
            var kr = matrix.kr().orElse(0.2126);
            var kb = matrix.kb().orElse(0.0722);
            y = kr * r + (1 - kr - kb) * g + kb * b;
            cb = (b - y) / (2 * (1 - kb));
            cr = (r - y) / (2 * (1 - kr));
        }
        var scale = 1 << (depth - 8);
        int luma;
        int blueDifference;
        int redDifference;
        if (full) {
            luma = (int) Math.round(y * max);
            blueDifference = (int) Math.round((cb + 0.5) * max);
            redDifference = (int) Math.round((cr + 0.5) * max);
        } else {
            luma = (int) Math.round((16 + 219 * y) * scale);
            blueDifference = (int) Math.round((128 + 224 * cb) * scale);
            redDifference = (int) Math.round((128 + 224 * cr) * scale);
        }
        return new int[]{Math.clamp(luma, 0, max), Math.clamp(blueDifference, 0, max),
                Math.clamp(redDifference, 0, max)};
    }

    /**
     * {@return a sample blended toward a value}
     *
     * @param sample the sample value
     * @param target the value faded to
     * @param weight the weight of the sample, from 0 to {@link #ONE}
     */
    static int mix(int sample, int target, int weight) {
        return target + (int) Math.floorDiv((long) (sample - target) * weight + ONE / 2, ONE);
    }

    private static void blend8(byte[] plane, int stride, int width, int height, int target, int weight) {
        for (var row = 0; row < height; row++) {
            var base = row * stride;
            for (var x = 0; x < width; x++) {
                plane[base + x] = (byte) mix(plane[base + x] & 0xFF, target, weight);
            }
        }
    }

    private static void blend16(short[] plane, int stride, int width, int height, int target, int weight) {
        for (var row = 0; row < height; row++) {
            var base = row * stride;
            for (var x = 0; x < width; x++) {
                plane[base + x] = (short) mix(plane[base + x] & 0xFFFF, target, weight);
            }
        }
    }

    private static void blendPacked8(byte[] pixels, int stride, int width, int height, int[] target, int weight) {
        for (var row = 0; row < height; row++) {
            var base = row * stride;
            for (var x = 0; x < width; x++) {
                var at = base + 4 * x;
                for (var component = 0; component < 3; component++) {
                    pixels[at + component] = (byte) mix(pixels[at + component] & 0xFF, target[component], weight);
                }
            }
        }
    }

    private static void blendPacked16(short[] pixels, int stride, int width, int height, int[] target, int weight) {
        for (var row = 0; row < height; row++) {
            var base = row * stride;
            for (var x = 0; x < width; x++) {
                var at = base + 4 * x;
                for (var component = 0; component < 3; component++) {
                    pixels[at + component] = (short) mix(pixels[at + component] & 0xFFFF, target[component], weight);
                }
            }
        }
    }
}
