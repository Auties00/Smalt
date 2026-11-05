package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
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

    /**
     * Starts the composition of this overlay on a canvas filled with its fill color.
     *
     * <p>The canvas starts at 8 bits, holding the fill reduced as {@code (v * 255 + 32767) / 65535}.
     * When {@code deep} is set, the first input of more than 8 bits turns it into a 16-bit canvas:
     * the pixels no input has changed take the exact 16-bit fill and the others are widened as
     * {@code v * 257}, and every later input is drawn at 16 bits. A composition whose inputs all
     * have 8 bits is therefore the same whether {@code deep} is set or not. The caller has checked
     * the canvas size against its limits: 4 bytes per pixel, or 12 when {@code deep} is set, since
     * the 8-bit canvas and the 16-bit one coexist while the canvas is promoted.
     *
     * @param width the canvas width, {@code outputWidth} checked to fit an int
     * @param height the canvas height, {@code outputHeight} checked to fit an int
     * @param deep whether inputs of more than 8 bits keep their depth
     * @return the composition
     */
    public Composition compose(int width, int height, boolean deep) {
        return new Composition(fill, width, height, deep);
    }

    /**
     * Draws the inputs of an overlay over its canvas, in packed straight-alpha RGBA. Instances are
     * not thread-safe.
     *
     * <p>An input that reports no alpha replaces the canvas pixels it covers with opaque ones; an
     * input with alpha is blended over them with the source-over operator in straight alpha, every
     * result rounded once at the canvas depth. Only the part of an input that overlaps the canvas
     * is drawn.
     */
    public static final class Composition {
        private final int[] fill;
        private final long[] touched;
        private RgbaPicture canvas8;
        private Rgba16Picture canvas16;

        /**
         * Constructs a composition.
         *
         * @param fill the 16-bit fill
         * @param width the canvas width
         * @param height the canvas height
         * @param deep whether inputs of more than 8 bits keep their depth
         */
        private Composition(int[] fill, int width, int height, boolean deep) {
            this.fill = fill;
            this.canvas8 = RgbaPicture.allocate(width, height, true);
            this.touched = deep ? new long[(int) ((long) width * height + 63 >>> 6)] : null;
            var pixels = canvas8.pixels();
            for (var c = 0; c < 4; c++) {
                pixels[c] = (byte) ((fill[c] * 255 + 32767) / 65535);
            }
            for (var filled = 4; filled < pixels.length; ) {
                var length = Math.min(filled, pixels.length - filled);
                System.arraycopy(pixels, 0, pixels, filled, length);
                filled += length;
            }
        }

        /**
         * Draws one input over the canvas.
         *
         * @param input the input picture, of any representation; a 16-bit input on a canvas that does not keep
         * the depth is reduced with {@link ImageOps#toRgba(Picture)}
         * @param left the canvas column of the input's left edge
         * @param top the canvas row of the input's top edge
         */
        public void draw(Picture input, long left, long top) {
            var deepInput = input instanceof Yuv16Picture || input instanceof Rgba16Picture;
            if (canvas16 == null && touched != null && deepInput) {
                promote();
            }
            if (canvas16 != null) {
                var packed = deepInput ? ImageOps.toRgba16(input) : ImageOps.toSixteenBit(ImageOps.toRgba(input));
                draw(canvas16, packed, left, top);
            } else {
                draw(canvas8, ImageOps.toRgba(input), left, top, touched);
            }
        }

        /**
         * Returns the composed canvas.
         *
         * @return an {@link Rgba16Picture} when an input of more than 8 bits kept its depth, otherwise an
         * {@link RgbaPicture}
         */
        public Picture result() {
            return canvas16 != null ? canvas16 : canvas8;
        }

        /**
         * Turns the 8-bit canvas into a 16-bit one, which replaces it: the exact fill where no
         * input has changed a pixel, the widened 8-bit pixel elsewhere.
         */
        private void promote() {
            var width = canvas8.width();
            var height = canvas8.height();
            canvas16 = Rgba16Picture.allocate(width, height, true);
            var src = canvas8.pixels();
            var dst = canvas16.pixels();
            for (var i = 0; i < width * height; i++) {
                var changed = (touched[i >>> 6] & (1L << i)) != 0;
                for (var c = 0; c < 4; c++) {
                    dst[4 * i + c] = (short) (changed ? (src[4 * i + c] & 0xFF) * 257 : fill[c]);
                }
            }
            canvas8 = null;
        }

        /**
         * Draws an 8-bit picture over an 8-bit canvas.
         *
         * @param canvas the canvas, drawn on
         * @param picture the input, packed at the canvas depth
         * @param left the canvas column of the picture's left edge
         * @param top the canvas row of the picture's top edge
         * @param touched the set of changed canvas pixels, one bit per pixel in raster order, or
         * {@code null} when not tracked
         */
        private static void draw(RgbaPicture canvas, RgbaPicture picture, long left, long top, long[] touched) {
            var x0 = (int) Math.clamp(left, 0, canvas.width());
            var y0 = (int) Math.clamp(top, 0, canvas.height());
            var x1 = (int) Math.clamp(left + picture.width(), 0, canvas.width());
            var y1 = (int) Math.clamp(top + picture.height(), 0, canvas.height());
            var dst = canvas.pixels();
            var src = picture.pixels();
            for (var y = y0; y < y1; y++) {
                var in = (int) (y - top) * picture.stride() + (int) (x0 - left) * 4;
                var out = y * canvas.stride() + x0 * 4;
                for (var x = x0; x < x1; x++, in += 4, out += 4) {
                    var alpha = picture.hasAlpha() ? src[in + 3] & 0xFF : 255;
                    if (alpha == 0) {
                        continue;
                    }
                    if (touched != null) {
                        var index = y * canvas.width() + x;
                        touched[index >>> 6] |= 1L << index;
                    }
                    if (alpha == 255) {
                        System.arraycopy(src, in, dst, out, 3);
                        dst[out + 3] = (byte) 255;
                        continue;
                    }
                    var below = dst[out + 3] & 0xFF;
                    var coverage = alpha * 255 + below * (255 - alpha);
                    for (var c = 0; c < 3; c++) {
                        var value = ((src[in + c] & 0xFF) * alpha * 255 + (dst[out + c] & 0xFF) * below * (255 - alpha)
                                + coverage / 2) / coverage;
                        dst[out + c] = (byte) value;
                    }
                    dst[out + 3] = (byte) ((coverage + 127) / 255);
                }
            }
        }

        /**
         * Draws a 16-bit picture over a 16-bit canvas.
         *
         * @param canvas the canvas, drawn on
         * @param picture the input, packed at the canvas depth
         * @param left the canvas column of the picture's left edge
         * @param top the canvas row of the picture's top edge
         */
        private static void draw(Rgba16Picture canvas, Rgba16Picture picture, long left, long top) {
            var x0 = (int) Math.clamp(left, 0, canvas.width());
            var y0 = (int) Math.clamp(top, 0, canvas.height());
            var x1 = (int) Math.clamp(left + picture.width(), 0, canvas.width());
            var y1 = (int) Math.clamp(top + picture.height(), 0, canvas.height());
            var dst = canvas.pixels();
            var src = picture.pixels();
            for (var y = y0; y < y1; y++) {
                var in = (int) (y - top) * picture.stride() + (int) (x0 - left) * 4;
                var out = y * canvas.stride() + x0 * 4;
                for (var x = x0; x < x1; x++, in += 4, out += 4) {
                    long alpha = picture.hasAlpha() ? src[in + 3] & 0xFFFF : 0xFFFF;
                    if (alpha == 0) {
                        continue;
                    }
                    if (alpha == 0xFFFF) {
                        System.arraycopy(src, in, dst, out, 3);
                        dst[out + 3] = (short) 0xFFFF;
                        continue;
                    }
                    long below = dst[out + 3] & 0xFFFF;
                    var coverage = alpha * 0xFFFF + below * (0xFFFF - alpha);
                    for (var c = 0; c < 3; c++) {
                        var value = ((src[in + c] & 0xFFFF) * alpha * 0xFFFF
                                + (dst[out + c] & 0xFFFF) * below * (0xFFFF - alpha) + coverage / 2) / coverage;
                        dst[out + c] = (short) value;
                    }
                    dst[out + 3] = (short) ((coverage + 0x7FFF) / 0xFFFF);
                }
            }
        }
    }
}
