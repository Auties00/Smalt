package com.github.auties00.smalt.internal.field;

import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Describes one plane of samples of a picture, 8-bit or 16-bit.
 *
 * <p>Exactly one of the arrays is set. A packed RGBA plane counts every channel as a sample:
 * {@code width} is four times the pixel width and {@code step} is 4, the distance between the
 * samples of one channel in neighboring pixels; a planar plane has a step of 1.
 *
 * @param bytes the 8-bit samples, or {@code null}
 * @param shorts the 16-bit samples, or {@code null}
 * @param stride the distance between rows, in samples
 * @param width the samples of a row
 * @param height the rows
 * @param step the distance between horizontally neighboring samples of one channel
 */
public record Plane(byte[] bytes, short[] shorts, int stride, int width, int height, int step) {
    /**
     * Returns the planes of a picture: luma, the two chroma planes and alpha when present for a
     * planar picture, the one packed plane of an RGBA picture.
     *
     * @param picture the picture; not a channel picture
     * @return the planes
     * @throws IllegalArgumentException if the picture is a channel picture
     */
    public static Plane[] of(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> {
                var chroma = yuv.chromaFormat() != ChromaFormat.MONOCHROME;
                var count = (chroma ? 3 : 1) + (yuv.hasAlpha() ? 1 : 0);
                var planes = new Plane[count];
                var index = 0;
                planes[index++] = new Plane(yuv.y(), null, yuv.yStride(), yuv.width(), yuv.height(), 1);
                if (chroma) {
                    planes[index++] = new Plane(yuv.u(), null, yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), 1);
                    planes[index++] = new Plane(yuv.v(), null, yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), 1);
                }
                if (yuv.hasAlpha()) {
                    planes[index] = new Plane(yuv.alpha(), null, yuv.alphaStride(), yuv.width(), yuv.height(), 1);
                }
                yield planes;
            }
            case Yuv16Picture yuv -> {
                var chroma = yuv.chromaFormat() != ChromaFormat.MONOCHROME;
                var count = (chroma ? 3 : 1) + (yuv.hasAlpha() ? 1 : 0);
                var planes = new Plane[count];
                var index = 0;
                planes[index++] = new Plane(null, yuv.y(), yuv.yStride(), yuv.width(), yuv.height(), 1);
                if (chroma) {
                    planes[index++] = new Plane(null, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), 1);
                    planes[index++] = new Plane(null, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), 1);
                }
                if (yuv.hasAlpha()) {
                    planes[index] = new Plane(null, yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height(), 1);
                }
                yield planes;
            }
            case RgbaPicture rgba -> new Plane[]{new Plane(rgba.pixels(), null, rgba.stride(), 4 * rgba.width(),
                    rgba.height(), 4)};
            case Rgba16Picture rgba -> new Plane[]{new Plane(null, rgba.pixels(), rgba.stride(), 4 * rgba.width(),
                    rgba.height(), 4)};
            case ChannelPicture _ -> throw new IllegalArgumentException("channel pictures have no field planes");
        };
    }

    /**
     * {@return whether the samples are 16-bit}
     */
    public boolean wide() {
        return shorts != null;
    }
}
