package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.heif.DecodedTile;
import com.github.auties00.smalt.internal.heif.HeifComponentPictures;
import com.github.auties00.smalt.internal.heif.HeifComponents;
import com.github.auties00.smalt.internal.heif.HeifProperty;

import java.util.List;

/**
 * Holds the components of an item that only a {@link ChannelPicture} represents (ink components, or
 * components deeper than 16 bits), decoded by a {@link com.github.auties00.smalt.internal.heif.HeifComponentCodec}.
 *
 * <p>The item's picture is assembled by {@link #picture(boolean)}. As a {@link CodedFrame}, which
 * alpha images are read through, the tile presents its first component alone, as a 16-bit monochrome picture
 * (deeper samples rescaled as {@code (v * 65535 + m / 2) / m} for a largest value {@code m}, a subsampled
 * component replicated).
 *
 * <p>Instances are not thread-safe.
 */
final class ChannelTile implements CodedFrame {
    /**
     * The decoded components.
     */
    private final HeifComponents components;

    /**
     * The color description of luma, chroma and monochrome components.
     */
    private final ColorInfo color;

    /**
     * The bounds of the pictures.
     */
    private final MediaLimits limits;

    /**
     * The monochrome picture of the first component, or {@code null} before its first use.
     */
    private PictureTile first;

    /**
     * Wraps decoded components.
     *
     * @param components the components, for which {@link HeifComponents#needsChannels()} holds
     * @param color the color description of luma, chroma and monochrome components
     * @param limits the bounds of the pictures
     */
    ChannelTile(HeifComponents components, ColorInfo color, MediaLimits limits) {
        this.components = components;
        this.color = color;
        this.limits = limits;
    }

    /**
     * Assembles the item's picture, as
     * {@link HeifComponentPictures#toChannels(String, HeifComponents, boolean, ColorInfo, MediaLimits)} does.
     *
     * @param wide whether ink components of more than 8 bits keep 16 bits
     * @return the picture
     */
    ChannelPicture picture(boolean wide) {
        return HeifComponentPictures.toChannels(FORMAT, components, wide, color, limits);
    }

    /**
     * {@return the monochrome picture of the first component, built on first use}
     */
    private PictureTile first() {
        if (first == null) {
            var plane = components.planes().getFirst();
            var width = components.width();
            var height = components.height();
            var max = (1L << plane.bitDepth()) - 1;
            var samples = new short[width * height];
            for (var y = 0; y < height; y++) {
                var row = Math.min(y / plane.subsamplingY(), plane.height() - 1) * plane.width();
                for (var x = 0; x < width; x++) {
                    long v = plane.sample(row + Math.min(x / plane.subsamplingX(), plane.width() - 1));
                    samples[y * width + x] = (short) (max == 65535 ? v : (v * 65535 + max / 2) / max);
                }
            }
            var mono = new HeifComponents(width, height, List.of(new HeifComponents.Plane(HeifComponents.MONOCHROME,
                    width, height, 1, 1, 16, samples)));
            first = PictureTile.of(HeifComponentPictures.toPicture(FORMAT, mono, color, limits), false);
        }
        return first;
    }

    @Override
    public int width() {
        return components.width();
    }

    @Override
    public int height() {
        return components.height();
    }

    @Override
    public ChromaFormat format() {
        return ChromaFormat.MONOCHROME;
    }

    @Override
    public int bitDepth() {
        return 16;
    }

    @Override
    public ColorInfo color() {
        return color;
    }

    @Override
    public boolean limitedAlpha(HeifProperty.ColorNclx nclx) {
        return first().limitedAlpha(nclx);
    }

    @Override
    public Picture lumaPicture() {
        return first().lumaPicture();
    }

    @Override
    public boolean eightBit() {
        return false;
    }

    @Override
    public byte[] plane8(int plane) {
        return first().plane8(plane);
    }

    @Override
    public short[] plane16(int plane) {
        return first().plane16(plane);
    }

    @Override
    public int offset(int plane) {
        return first().offset(plane);
    }

    @Override
    public int stride(int plane) {
        return first().stride(plane);
    }

    @Override
    public int depth(int plane) {
        return 16;
    }

    @Override
    public DecodedTile alpha() {
        return null;
    }
}
