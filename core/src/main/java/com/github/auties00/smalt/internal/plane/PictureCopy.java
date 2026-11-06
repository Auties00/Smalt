package com.github.auties00.smalt.internal.plane;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Copies pictures out of the buffers a decoder recycles.
 *
 * <p>A decoder hands out frames whose pictures are valid only until its consumer returns; a
 * reader or a pipeline stage that keeps a frame longer copies it first, into a picture of its own
 * that it recycles from one frame to the next. This class is thread-safe.
 */
public final class PictureCopy {
    /**
     * Prevents instantiation.
     */
    private PictureCopy() {
        throw new AssertionError();
    }

    /**
     * Copies a picture, into a recycled picture of the same shape when one is given.
     *
     * <p>{@code recycle} is overwritten when it has the same type, size, alpha and, for planar
     * pictures, the same chroma format, bit depth and color description as {@code source}, or for
     * channel pictures the same layout; otherwise a new picture is allocated. Every plane is copied,
     * alpha included.
     *
     * @param source the picture to copy
     * @param recycle a picture to overwrite, or {@code null}
     * @return the copy, {@code recycle} itself when it was reused; it shares no array with
     * {@code source}
     */
    public static Picture copy(Picture source, Picture recycle) {
        return switch (source) {
            case YuvPicture yuv -> copyYuv(yuv, recycle instanceof YuvPicture target && sameShape(yuv, target)
                    ? target : null);
            case RgbaPicture rgba -> copyRgba(rgba, recycle instanceof RgbaPicture target
                    && target.width() == rgba.width() && target.height() == rgba.height()
                    && target.hasAlpha() == rgba.hasAlpha() ? target : null);
            case Yuv16Picture yuv -> copyYuv16(yuv, recycle instanceof Yuv16Picture target
                    && sameShape(yuv, target) ? target : null);
            case Rgba16Picture rgba -> copyRgba16(rgba, recycle instanceof Rgba16Picture target
                    && target.width() == rgba.width() && target.height() == rgba.height()
                    && target.hasAlpha() == rgba.hasAlpha() ? target : null);
            case ChannelPicture channels -> copyChannels(channels, recycle instanceof ChannelPicture target
                    && target.getClass() == channels.getClass() && target.width() == channels.width()
                    && target.height() == channels.height() && target.layout().equals(channels.layout())
                    ? target : null);
        };
    }

    /**
     * Copies a channel picture.
     *
     * @param source the picture to copy
     * @param target a picture of the same type, size and layout to overwrite, or {@code null}
     * @return the copy
     */
    private static ChannelPicture copyChannels(ChannelPicture source, ChannelPicture target) {
        var width = source.width();
        var height = source.height();
        var layout = source.layout();
        var out = target != null ? target : switch (source) {
            case Channel8Picture _ -> Channel8Picture.allocate(width, height, layout);
            case Channel16Picture _ -> Channel16Picture.allocate(width, height, layout);
            case ChannelFloatPicture _ -> ChannelFloatPicture.allocate(width, height, layout);
        };
        Object src = switch (source) {
            case Channel8Picture eight -> eight.pixels();
            case Channel16Picture sixteen -> sixteen.pixels();
            case ChannelFloatPicture floating -> floating.pixels();
        };
        Object dst = switch (out) {
            case Channel8Picture eight -> eight.pixels();
            case Channel16Picture sixteen -> sixteen.pixels();
            case ChannelFloatPicture floating -> floating.pixels();
        };
        var row = width * source.channelCount();
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, y * source.stride(), dst, y * out.stride(), row);
        }
        return out;
    }

    /**
     * Returns whether a recycled planar picture can hold a copy of another.
     *
     * @param source the picture to copy
     * @param target the recycled picture
     * @return {@code true} when size, layout, alpha and color match
     */
    private static boolean sameShape(YuvPicture source, YuvPicture target) {
        return target.width() == source.width()
                && target.height() == source.height()
                && target.chromaFormat() == source.chromaFormat()
                && target.hasAlpha() == source.hasAlpha()
                && target.color().equals(source.color());
    }

    /**
     * Returns whether a recycled high-bit-depth planar picture can hold a copy of another.
     *
     * @param source the picture to copy
     * @param target the recycled picture
     * @return {@code true} when size, layout, depth, alpha and color match
     */
    private static boolean sameShape(Yuv16Picture source, Yuv16Picture target) {
        return target.width() == source.width()
                && target.height() == source.height()
                && target.chromaFormat() == source.chromaFormat()
                && target.bitDepth() == source.bitDepth()
                && target.hasAlpha() == source.hasAlpha()
                && target.color().equals(source.color());
    }

    /**
     * Copies an 8-bit planar picture.
     *
     * @param source the picture to copy
     * @param target a picture of the same shape to overwrite, or {@code null} to allocate one
     * @return the copy
     */
    private static YuvPicture copyYuv(YuvPicture source, YuvPicture target) {
        if (target == null) {
            target = YuvPicture.allocate(source.width(), source.height(), source.chromaFormat(), source.hasAlpha(),
                    source.color());
        }
        Planes.copy(source.y(), 0, source.yStride(), target.y(), 0, target.yStride(), source.width(),
                source.height());
        if (source.chromaFormat().hasChroma()) {
            var chromaWidth = source.chromaWidth();
            var chromaHeight = source.chromaHeight();
            Planes.copy(source.u(), 0, source.uvStride(), target.u(), 0, target.uvStride(), chromaWidth,
                    chromaHeight);
            Planes.copy(source.v(), 0, source.uvStride(), target.v(), 0, target.uvStride(), chromaWidth,
                    chromaHeight);
        }
        if (source.hasAlpha()) {
            Planes.copy(source.alpha(), 0, source.alphaStride(), target.alpha(), 0, target.alphaStride(),
                    source.width(), source.height());
        }
        return target;
    }

    /**
     * Copies a high-bit-depth planar picture.
     *
     * @param source the picture to copy
     * @param target a picture of the same shape to overwrite, or {@code null} to allocate one
     * @return the copy
     */
    private static Yuv16Picture copyYuv16(Yuv16Picture source, Yuv16Picture target) {
        if (target == null) {
            target = Yuv16Picture.allocate(source.width(), source.height(), source.chromaFormat(), source.bitDepth(),
                    source.hasAlpha(), source.color());
        }
        Planes.copy(source.y(), 0, source.yStride(), target.y(), 0, target.yStride(), source.width(),
                source.height());
        if (source.chromaFormat().hasChroma()) {
            var chromaWidth = source.chromaWidth();
            var chromaHeight = source.chromaHeight();
            Planes.copy(source.u(), 0, source.uvStride(), target.u(), 0, target.uvStride(), chromaWidth,
                    chromaHeight);
            Planes.copy(source.v(), 0, source.uvStride(), target.v(), 0, target.uvStride(), chromaWidth,
                    chromaHeight);
        }
        if (source.hasAlpha()) {
            Planes.copy(source.alpha(), 0, source.alphaStride(), target.alpha(), 0, target.alphaStride(),
                    source.width(), source.height());
        }
        return target;
    }

    /**
     * Copies an 8-bit packed picture.
     *
     * @param source the picture to copy
     * @param target a picture of the same size to overwrite, or {@code null} to allocate one
     * @return the copy
     */
    private static RgbaPicture copyRgba(RgbaPicture source, RgbaPicture target) {
        if (target == null) {
            target = RgbaPicture.allocate(source.width(), source.height(), source.hasAlpha());
        }
        Planes.copy(source.pixels(), 0, source.stride(), target.pixels(), 0, target.stride(), source.width() * 4,
                source.height());
        return target;
    }

    /**
     * Copies a 16-bit packed picture.
     *
     * @param source the picture to copy
     * @param target a picture of the same size to overwrite, or {@code null} to allocate one
     * @return the copy
     */
    private static Rgba16Picture copyRgba16(Rgba16Picture source, Rgba16Picture target) {
        if (target == null) {
            target = Rgba16Picture.allocate(source.width(), source.height(), source.hasAlpha());
        }
        Planes.copy(source.pixels(), 0, source.stride(), target.pixels(), 0, target.stride(), source.width() * 4,
                source.height());
        return target;
    }
}
