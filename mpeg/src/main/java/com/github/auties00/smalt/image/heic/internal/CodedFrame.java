package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.heif.DecodedTile;
import com.github.auties00.smalt.internal.heif.HeifCanvas;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.track.Packet;

/**
 * Presents the decoded picture of a coded image item or sequence sample, whatever its codec, to a
 * {@link HeifCanvas}: its visible samples, layout, depth and color, and the alpha plane it carries
 * itself, if any.
 *
 * <p>A frame is a view of the decoder's picture, valid until the next call on that decoder.
 */
interface CodedFrame extends DecodedTile {
    /**
     * The format name of the canvases, for messages.
     */
    String FORMAT = "heic";

    /**
     * {@return the visible width}
     */
    int width();

    /**
     * {@return the visible height}
     */
    int height();

    /**
     * {@return the chroma layout}
     */
    ChromaFormat format();

    /**
     * {@return the depth of the picture: the larger of the luma and chroma depths, from 8 to 16}
     */
    int bitDepth();

    /**
     * {@return the color description the payload gives, with the MIAF defaults for what it leaves
     * unspecified}
     */
    ColorInfo color();

    /**
     * {@return whether the samples are RGB coded with the identity matrix, which the color
     * properties of the item cannot change}
     */
    default boolean rgb() {
        return false;
    }

    /**
     * Returns whether the samples of the picture are limited range when it is an alpha plane.
     *
     * @param nclx the {@code nclx} color box of the alpha item or track, or {@code null}
     * @return {@code true} when the samples span the limited range and must be expanded
     */
    boolean limitedAlpha(HeifProperty.ColorNclx nclx);

    /**
     * {@return a copy of the visible luma plane, at its depth, as a monochrome picture}
     */
    Picture lumaPicture();

    /**
     * {@return the presentation time of the sample that carried the picture, or
     * {@link Packet#NO_TIMESTAMP}}
     */
    default long pts() {
        return Packet.NO_TIMESTAMP;
    }

    /**
     * {@return the duration of the sample that carried the picture}
     */
    default long duration() {
        return 0;
    }

    /**
     * {@return the alpha plane the picture carries itself, as plane 0 of a tile, or {@code null}}
     */
    default DecodedTile alpha() {
        return null;
    }

    /**
     * {@return whether the color is premultiplied by the alpha plane the picture carries itself}
     */
    default boolean premultiplied() {
        return false;
    }

    /**
     * Allocates a canvas in the layout and depth of this picture.
     *
     * <p>A canvas that keeps the depth of a picture whose luma and chroma depths differ holds the
     * shallower planes shifted left to the larger depth.
     *
     * @param limits the bounds of the canvas
     * @param width the canvas width
     * @param height the canvas height
     * @param wide whether a source of more than 8 bits keeps its depth
     * @param hasAlpha whether to allocate an alpha plane
     * @return the canvas
     */
    default HeifCanvas canvas(MediaLimits limits, int width, int height, boolean wide, boolean hasAlpha) {
        var depth = bitDepth();
        return new HeifCanvas(FORMAT, limits, width, height, format(), depth, wide && depth > 8, hasAlpha);
    }

    /**
     * {@return whether this picture has the chroma layout and the bit depth of a canvas}
     *
     * @param canvas the canvas to write into
     */
    default boolean fits(HeifCanvas canvas) {
        return format() == canvas.format() && bitDepth() == canvas.bitDepth();
    }
}
