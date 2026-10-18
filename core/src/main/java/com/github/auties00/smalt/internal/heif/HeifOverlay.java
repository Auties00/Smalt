package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.FourCc;

/**
 * Describes the image overlay of an {@code iovl} derived image item (ISO/IEC 23008-12 clause
 * 6.6.2.3): a canvas of {@code outputWidth} by {@code outputHeight} filled with one color, on
 * which the input images, the {@code dimg} references of the item, are placed in reference order,
 * each one over the ones before it, with its top-left corner at its offset.
 *
 * <p>The fill color is given as four 16-bit values, red, green, blue and alpha, where
 * {@code 0xFFFF} is the full intensity and {@code 0xFFFF} alpha is opaque.
 *
 * @param fill the red, green, blue and alpha fill values, from 0 to {@code 0xFFFF}
 * @param outputWidth the canvas width, positive
 * @param outputHeight the canvas height, positive
 * @param offsetsX the canvas column of the left edge of each input, possibly negative
 * @param offsetsY the canvas row of the top edge of each input, possibly negative
 */
public record HeifOverlay(int[] fill, long outputWidth, long outputHeight, long[] offsetsX, long[] offsetsY) {
    /**
     * Parses the payload of an {@code iovl} item ({@code ImageOverlay}).
     *
     * @param payload the item data
     * @param inputs the number of {@code dimg} references of the item
     * @return the overlay
     * @throws MediaException.Unsupported if the version is not {@code 0}
     * @throws MediaException.Malformed if the payload is truncated, has trailing bytes or declares
     * an empty canvas
     */
    public static HeifOverlay parse(byte[] payload, int inputs) {
        var box = BoxCursor.strict(payload, FourCc.of("iovl"));
        var version = box.u8();
        if (version != 0) {
            throw new MediaException.Unsupported("heif", "image overlay version " + version);
        }
        var wide = (box.u8() & 1) != 0;
        var fill = new int[4];
        for (var i = 0; i < 4; i++) {
            fill[i] = box.u16();
        }
        var width = wide ? box.u32() : box.u16();
        var height = wide ? box.u32() : box.u16();
        if (box.remaining() != (long) inputs * (wide ? 8 : 4)) {
            throw new MediaException.Malformed("heif: image overlay of " + inputs + " inputs holds "
                    + box.remaining() + " bytes of offsets");
        }
        var offsetsX = new long[inputs];
        var offsetsY = new long[inputs];
        for (var i = 0; i < inputs; i++) {
            offsetsX[i] = wide ? box.s32() : (short) box.u16();
            offsetsY[i] = wide ? box.s32() : (short) box.u16();
        }
        if (width == 0 || height == 0) {
            throw new MediaException.Malformed("heif: image overlay canvas is " + width + "x" + height);
        }
        return new HeifOverlay(fill, width, height, offsetsX, offsetsY);
    }
}
