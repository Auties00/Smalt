package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.Arrays;

/**
 * Chooses the color type and bit depth the PNG encoder writes for a picture, and packs its pixels
 * into scanline bytes of that format.
 *
 * <p>The choice is lossless: an 8-bit picture becomes indexed color of 1, 2, 4 or 8 bits,
 * grayscale of 1, 2, 4 or 8 bits, grayscale with alpha, truecolor or truecolor with alpha; a 16-bit
 * picture becomes the 16-bit grayscale, grayscale with alpha, truecolor or truecolor with alpha
 * type. Grayscale needs equal red, green and blue samples, a depth below 8 needs every gray level
 * to be a multiple of {@code 255 / (2^depth - 1)}, and a type without alpha needs every pixel
 * opaque, or every alpha 0 or the maximum with one color shared by every transparent pixel and by
 * no opaque one, which a {@code tRNS} key then marks. Among the eligible formats the one whose
 * scanlines plus format chunks take the fewest bytes wins; ties go to grayscale, then truecolor,
 * then indexed color. Instances are not thread-safe; {@link #copy()} gives each packing thread its own.
 */
final class PngLayout {
    /**
     * The bit depths below 8 tried for grayscale and indexed images, smallest first.
     */
    private static final int[] LOW_DEPTHS = {1, 2, 4};

    /**
     * The bytes of a chunk's length, type and CRC.
     */
    private static final int CHUNK_OVERHEAD = 12;

    /**
     * For each 8-bit gray level, bit {@code i} set when the level is not representable at
     * {@code LOW_DEPTHS[i]} bits.
     */
    private static final byte[] LEVEL_FLAGS = new byte[256];

    static {
        for (var v = 0; v < 256; v++) {
            LEVEL_FLAGS[v] = (byte) ((v % 255 != 0 ? 1 : 0) | (v % 85 != 0 ? 2 : 0) | (v % 17 != 0 ? 4 : 0));
        }
    }

    /**
     * The 8-bit picture, or {@code null} for a 16-bit one.
     */
    private final RgbaPicture narrow;

    /**
     * The 16-bit picture, or {@code null} for an 8-bit one.
     */
    private final Rgba16Picture wide;

    /**
     * The {@code IHDR} color type.
     */
    private final int colorType;

    /**
     * The {@code IHDR} bit depth.
     */
    private final int bitDepth;

    /**
     * The ordered palette of an indexed image, or {@code null}.
     */
    private final PngPalette palette;

    /**
     * The {@code tRNS} key at the bit depth, or {@code null}.
     */
    private final int[] key;

    /**
     * The alpha bits forced into every 8-bit color, {@code 0xFF000000} when alpha is ignored.
     */
    private final int force;

    /**
     * The bits per pixel of a scanline.
     */
    private final int bitsPerPixel;

    /**
     * The color of the last palette lookup.
     */
    private int lastColor;

    /**
     * The index of the last palette lookup, or -1 before the first.
     */
    private int lastIndex = -1;

    /**
     * Constructs a layout.
     *
     * @param narrow the 8-bit picture, or {@code null}
     * @param wide the 16-bit picture, or {@code null}
     * @param colorType the color type
     * @param bitDepth the bit depth
     * @param palette the palette of an indexed image, or {@code null}
     * @param key the transparency key, or {@code null}
     * @param force the alpha forced into 8-bit colors
     */
    private PngLayout(RgbaPicture narrow, Rgba16Picture wide, int colorType, int bitDepth, PngPalette palette,
                      int[] key, int force) {
        this.narrow = narrow;
        this.wide = wide;
        this.colorType = colorType;
        this.bitDepth = bitDepth;
        this.palette = palette;
        this.key = key;
        this.force = force;
        this.bitsPerPixel = bitDepth * switch (colorType) {
            case PngHeader.RGB -> 3;
            case PngHeader.GRAY_ALPHA -> 2;
            case PngHeader.RGBA -> 4;
            default -> 1;
        };
    }

    /**
     * Returns an independent copy for packing on another thread.
     *
     * @return the copy, sharing the picture and palette
     */
    PngLayout copy() {
        return new PngLayout(narrow, wide, colorType, bitDepth, palette, key, force);
    }

    /**
     * Chooses the format of an 8-bit picture.
     *
     * @param picture the picture; its alpha is ignored when {@link RgbaPicture#hasAlpha()} is
     * {@code false}
     * @param profile the ICC profile to embed, which restricts the color types, or {@code null}
     * @return the layout
     * @throws IllegalArgumentException if the profile is a gray profile and the picture is not gray
     */
    static PngLayout of(RgbaPicture picture, ColorProfile profile) {
        var width = picture.width();
        var height = picture.height();
        var pixels = picture.pixels();
        var stride = picture.stride();
        var force = picture.hasAlpha() ? 0 : 0xFF00_0000;
        var gray = true;
        var levels = 0;
        var binary = true;
        var transparent = false;
        var conflict = false;
        var keyColor = 0;
        for (var y = 0; y < height; y++) {
            var row = y * stride;
            for (var x = 0; x < width; x++) {
                var color = ByteArrays.i32le(pixels, row + x * 4) | force;
                var r = color & 0xFF;
                var rgb = color & 0xFF_FFFF;
                gray &= rgb == r * 0x01_0101;
                levels |= LEVEL_FLAGS[r];
                var alpha = color >>> 24;
                if (alpha == 0) {
                    if (!transparent) {
                        transparent = true;
                        keyColor = rgb;
                    } else {
                        conflict |= rgb != keyColor;
                    }
                } else if (alpha != 0xFF) {
                    binary = false;
                }
            }
        }
        var opaque = binary && !transparent;
        var keyed = binary && transparent && !conflict && !opaqueHas(picture, keyColor);
        var palette = PngPalette.of(picture, !picture.hasAlpha());
        var choice = new Choice(width, height, profile);
        if (choice.allowsGray(gray)) {
            var depth = 8;
            for (var i = LOW_DEPTHS.length - 1; i >= 0; i--) {
                if ((levels & (1 << i)) == 0) {
                    depth = LOW_DEPTHS[i];
                }
            }
            if (opaque || keyed) {
                choice.offer(PngHeader.GRAY, depth, keyed ? 2 : 0);
            } else {
                choice.offer(PngHeader.GRAY_ALPHA, 8, 0);
            }
        }
        if (choice.allowsColor()) {
            if (opaque || keyed) {
                choice.offer(PngHeader.RGB, 8, keyed ? 6 : 0);
            } else {
                choice.offer(PngHeader.RGBA, 8, 0);
            }
            if (palette != null) {
                var depth = 8;
                for (var i = LOW_DEPTHS.length - 1; i >= 0; i--) {
                    if (palette.size() <= 1 << LOW_DEPTHS[i]) {
                        depth = LOW_DEPTHS[i];
                    }
                }
                var extra = palette.size() * 3 + (palette.translucentCount() > 0
                        ? CHUNK_OVERHEAD + palette.translucentCount() : 0);
                choice.offerIndexed(depth, extra);
            }
        }
        choice.check();
        int[] key = null;
        if (choice.trns > 0) {
            if (choice.type == PngHeader.GRAY) {
                key = new int[]{(keyColor & 0xFF) * ((1 << choice.depth) - 1) / 255};
            } else {
                key = new int[]{keyColor & 0xFF, (keyColor >>> 8) & 0xFF, (keyColor >>> 16) & 0xFF};
            }
        }
        return new PngLayout(picture, null, choice.type, choice.depth,
                choice.type == PngHeader.PALETTE ? palette : null, key, force);
    }

    /**
     * Chooses the format of a 16-bit picture, which keeps 16 bits per sample.
     *
     * @param picture the picture; its alpha is ignored when {@link Rgba16Picture#hasAlpha()} is
     * {@code false}
     * @param profile the ICC profile to embed, which restricts the color types, or {@code null}
     * @return the layout
     * @throws IllegalArgumentException if the profile is a gray profile and the picture is not gray
     */
    static PngLayout of(Rgba16Picture picture, ColorProfile profile) {
        var width = picture.width();
        var height = picture.height();
        var pixels = picture.pixels();
        var stride = picture.stride();
        var ignoreAlpha = !picture.hasAlpha();
        var gray = true;
        var binary = true;
        var transparent = false;
        var conflict = false;
        var keyColor = 0L;
        for (var y = 0; y < height; y++) {
            var row = y * stride;
            for (var x = 0; x < width; x++) {
                var i = row + x * 4;
                var r = pixels[i] & 0xFFFF;
                var g = pixels[i + 1] & 0xFFFF;
                var b = pixels[i + 2] & 0xFFFF;
                var alpha = ignoreAlpha ? 0xFFFF : pixels[i + 3] & 0xFFFF;
                gray &= r == g && g == b;
                if (alpha == 0) {
                    var rgb = (long) r << 32 | (long) g << 16 | b;
                    if (!transparent) {
                        transparent = true;
                        keyColor = rgb;
                    } else {
                        conflict |= rgb != keyColor;
                    }
                } else if (alpha != 0xFFFF) {
                    binary = false;
                }
            }
        }
        var opaque = binary && !transparent;
        var keyed = binary && transparent && !conflict && !opaqueHas(picture, keyColor);
        var choice = new Choice(width, height, profile);
        if (choice.allowsGray(gray)) {
            choice.offer(opaque || keyed ? PngHeader.GRAY : PngHeader.GRAY_ALPHA, 16, keyed ? 2 : 0);
        }
        if (choice.allowsColor()) {
            choice.offer(opaque || keyed ? PngHeader.RGB : PngHeader.RGBA, 16, keyed ? 6 : 0);
        }
        choice.check();
        int[] key = null;
        if (choice.trns > 0) {
            var r = (int) (keyColor >>> 32);
            key = choice.type == PngHeader.GRAY ? new int[]{r}
                    : new int[]{r, (int) (keyColor >>> 16) & 0xFFFF, (int) keyColor & 0xFFFF};
        }
        return new PngLayout(null, picture, choice.type, 16, null, key, 0);
    }

    /**
     * Returns whether an opaque pixel of an 8-bit picture has a color.
     *
     * @param picture the picture, whose alpha is binary
     * @param rgb the packed red, green and blue samples
     * @return {@code true} if some pixel of alpha 255 has that color
     */
    private static boolean opaqueHas(RgbaPicture picture, int rgb) {
        var pixels = picture.pixels();
        for (var y = 0; y < picture.height(); y++) {
            var row = y * picture.stride();
            for (var x = 0; x < picture.width(); x++) {
                var color = ByteArrays.i32le(pixels, row + x * 4);
                if (color == (0xFF00_0000 | rgb)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether an opaque pixel of a 16-bit picture has a color.
     *
     * @param picture the picture, whose alpha is binary
     * @param rgb the red, green and blue samples packed in 48 bits
     * @return {@code true} if some pixel of alpha 65535 has that color
     */
    private static boolean opaqueHas(Rgba16Picture picture, long rgb) {
        var pixels = picture.pixels();
        for (var y = 0; y < picture.height(); y++) {
            var row = y * picture.stride();
            for (var x = 0; x < picture.width(); x++) {
                var i = row + x * 4;
                if ((pixels[i + 3] & 0xFFFF) == 0xFFFF && (pixels[i] & 0xFFFF) == rgb >>> 32
                        && (pixels[i + 1] & 0xFFFF) == ((rgb >>> 16) & 0xFFFF) && (pixels[i + 2] & 0xFFFF) == (rgb & 0xFFFF)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return the {@code IHDR} color type}
     */
    int colorType() {
        return colorType;
    }

    /**
     * {@return the {@code IHDR} bit depth}
     */
    int bitDepth() {
        return bitDepth;
    }

    /**
     * {@return the palette of an indexed image, or {@code null}}
     */
    PngPalette palette() {
        return palette;
    }

    /**
     * {@return the {@code tRNS} key at the bit depth, or {@code null}}
     */
    int[] key() {
        return key;
    }

    /**
     * {@return the filter distance in bytes, from 1 to 8}
     */
    int distance() {
        return Math.max(1, bitsPerPixel >>> 3);
    }

    /**
     * {@return whether the adaptive selection filters the scanlines: not for indexed color or a
     * depth below 8}
     */
    boolean filtersAdaptively() {
        return colorType != PngHeader.PALETTE && bitDepth >= 8;
    }

    /**
     * {@return whether the scanlines of a non-interlaced image are the 8-bit picture's own rows}
     */
    boolean direct() {
        return narrow != null && colorType == PngHeader.RGBA;
    }

    /**
     * {@return the 8-bit picture, or {@code null} for a 16-bit one}
     */
    RgbaPicture directPicture() {
        return narrow;
    }

    /**
     * Returns the bytes of a scanline of pixels.
     *
     * @param pixels the number of pixels
     * @return the scanline length without its filter type byte
     */
    long rowBytes(long pixels) {
        return (pixels * bitsPerPixel + 7) >>> 3;
    }

    /**
     * Returns the {@code sBIT} values of the format.
     *
     * @param bits the significant bits of every channel
     * @return one value per channel the chunk holds for the color type, each capped at the sample
     * depth
     */
    byte[] significantBits(int bits) {
        var channels = switch (colorType) {
            case PngHeader.GRAY -> 1;
            case PngHeader.GRAY_ALPHA -> 2;
            case PngHeader.RGBA -> 4;
            default -> 3;
        };
        var depth = colorType == PngHeader.PALETTE ? 8 : bitDepth;
        var values = new byte[channels];
        Arrays.fill(values, (byte) Math.min(bits, depth));
        return values;
    }

    /**
     * Packs pixels of a picture row into scanline bytes.
     *
     * @param y the picture row
     * @param x0 the column of the first pixel
     * @param dx the column step between pixels
     * @param count the number of pixels, positive
     * @param target the destination, {@link #rowBytes(long)} bytes from index 0
     */
    void pack(int y, int x0, int dx, int count, byte[] target) {
        if (narrow != null) {
            packNarrow(y, x0, dx, count, target);
        } else {
            packWide(y, x0, dx, count, target);
        }
    }

    /**
     * Packs pixels of an 8-bit picture row.
     *
     * @param y the picture row
     * @param x0 the column of the first pixel
     * @param dx the column step between pixels
     * @param count the number of pixels
     * @param target the destination
     */
    private void packNarrow(int y, int x0, int dx, int count, byte[] target) {
        var pixels = narrow.pixels();
        var base = y * narrow.stride() + x0 * 4;
        var step = dx * 4;
        switch (colorType) {
            case PngHeader.RGBA -> {
                if (dx == 1) {
                    System.arraycopy(pixels, base, target, 0, count * 4);
                } else {
                    for (var i = 0; i < count; i++) {
                        System.arraycopy(pixels, base + i * step, target, i * 4, 4);
                    }
                }
            }
            case PngHeader.RGB -> {
                for (var i = 0; i < count; i++) {
                    var s = base + i * step;
                    target[i * 3] = pixels[s];
                    target[i * 3 + 1] = pixels[s + 1];
                    target[i * 3 + 2] = pixels[s + 2];
                }
            }
            case PngHeader.GRAY_ALPHA -> {
                for (var i = 0; i < count; i++) {
                    var s = base + i * step;
                    target[i * 2] = pixels[s];
                    target[i * 2 + 1] = pixels[s + 3];
                }
            }
            case PngHeader.GRAY -> {
                if (bitDepth == 8) {
                    for (var i = 0; i < count; i++) {
                        target[i] = pixels[base + i * step];
                    }
                } else {
                    var divisor = 255 / ((1 << bitDepth) - 1);
                    var accumulator = 0;
                    var bits = 0;
                    var t = 0;
                    for (var i = 0; i < count; i++) {
                        accumulator = accumulator << bitDepth | (pixels[base + i * step] & 0xFF) / divisor;
                        bits += bitDepth;
                        if (bits == 8) {
                            target[t++] = (byte) accumulator;
                            accumulator = 0;
                            bits = 0;
                        }
                    }
                    if (bits > 0) {
                        target[t] = (byte) (accumulator << (8 - bits));
                    }
                }
            }
            default -> {
                var accumulator = 0;
                var bits = 0;
                var t = 0;
                for (var i = 0; i < count; i++) {
                    var color = ByteArrays.i32le(pixels, base + i * step) | force;
                    if (color != lastColor || lastIndex < 0) {
                        lastColor = color;
                        lastIndex = palette.indexOf(color);
                    }
                    accumulator = accumulator << bitDepth | lastIndex;
                    bits += bitDepth;
                    if (bits == 8) {
                        target[t++] = (byte) accumulator;
                        accumulator = 0;
                        bits = 0;
                    }
                }
                if (bits > 0) {
                    target[t] = (byte) (accumulator << (8 - bits));
                }
            }
        }
    }

    /**
     * Packs pixels of a 16-bit picture row, big-endian.
     *
     * @param y the picture row
     * @param x0 the column of the first pixel
     * @param dx the column step between pixels
     * @param count the number of pixels
     * @param target the destination
     */
    private void packWide(int y, int x0, int dx, int count, byte[] target) {
        var pixels = wide.pixels();
        var base = y * wide.stride() + x0 * 4;
        var step = dx * 4;
        switch (colorType) {
            case PngHeader.RGBA -> {
                for (var i = 0; i < count; i++) {
                    var s = base + i * step;
                    var t = i * 8;
                    ByteArrays.put16be(target, t, pixels[s]);
                    ByteArrays.put16be(target, t + 2, pixels[s + 1]);
                    ByteArrays.put16be(target, t + 4, pixels[s + 2]);
                    ByteArrays.put16be(target, t + 6, pixels[s + 3]);
                }
            }
            case PngHeader.RGB -> {
                for (var i = 0; i < count; i++) {
                    var s = base + i * step;
                    var t = i * 6;
                    ByteArrays.put16be(target, t, pixels[s]);
                    ByteArrays.put16be(target, t + 2, pixels[s + 1]);
                    ByteArrays.put16be(target, t + 4, pixels[s + 2]);
                }
            }
            case PngHeader.GRAY_ALPHA -> {
                for (var i = 0; i < count; i++) {
                    var s = base + i * step;
                    ByteArrays.put16be(target, i * 4, pixels[s]);
                    ByteArrays.put16be(target, i * 4 + 2, pixels[s + 3]);
                }
            }
            default -> {
                for (var i = 0; i < count; i++) {
                    ByteArrays.put16be(target, i * 2, pixels[base + i * step]);
                }
            }
        }
    }

    /**
     * Collects the eligible formats and keeps the smallest.
     */
    private static final class Choice {
        /**
         * The picture width.
         */
        private final long width;

        /**
         * The picture height.
         */
        private final long height;

        /**
         * Whether the profile is an RGB ICC profile, which excludes grayscale.
         */
        private final boolean rgbProfile;

        /**
         * Whether the profile is a gray ICC profile, which requires grayscale.
         */
        private final boolean grayProfile;

        /**
         * The chosen color type, or -1 before the first offer.
         */
        private int type = -1;

        /**
         * The chosen bit depth.
         */
        private int depth;

        /**
         * The {@code tRNS} key length of the chosen format, 0 for none.
         */
        private int trns;

        /**
         * The size of the chosen format.
         */
        private long size = Long.MAX_VALUE;

        /**
         * Constructs an empty choice.
         *
         * @param width the picture width
         * @param height the picture height
         * @param profile the ICC profile to embed, or {@code null}
         */
        private Choice(int width, int height, ColorProfile profile) {
            this.width = width;
            this.height = height;
            var icc = profile != null && !profile.isHighDynamicRange();
            this.rgbProfile = icc && profile.colorSpace() == ProfileColorSpace.RGB;
            this.grayProfile = icc && profile.colorSpace() == ProfileColorSpace.GRAY;
        }

        /**
         * {@return whether a grayscale type may be offered}
         *
         * @param gray whether every pixel is gray
         */
        private boolean allowsGray(boolean gray) {
            return gray && !rgbProfile;
        }

        /**
         * {@return whether a color type with color samples may be offered}
         */
        private boolean allowsColor() {
            return !grayProfile;
        }

        /**
         * Offers a grayscale or truecolor format.
         *
         * @param type the color type
         * @param depth the bit depth
         * @param trns the length of the samples of a {@code tRNS} key, 0 for none
         */
        private void offer(int type, int depth, int trns) {
            var channels = switch (type) {
                case PngHeader.RGB -> 3;
                case PngHeader.GRAY_ALPHA -> 2;
                case PngHeader.RGBA -> 4;
                default -> 1;
            };
            var candidate = height * (1 + ((width * channels * depth + 7) >>> 3))
                    + (trns > 0 ? CHUNK_OVERHEAD + trns : 0);
            if (candidate < size) {
                this.type = type;
                this.depth = depth;
                this.trns = trns;
                this.size = candidate;
            }
        }

        /**
         * Offers indexed color.
         *
         * @param depth the bit depth
         * @param extra the bytes of the palette entries and of the {@code tRNS} chunk
         */
        private void offerIndexed(int depth, long extra) {
            var candidate = height * (1 + ((width * depth + 7) >>> 3)) + CHUNK_OVERHEAD + extra;
            if (candidate < size) {
                this.type = PngHeader.PALETTE;
                this.depth = depth;
                this.trns = 0;
                this.size = candidate;
            }
        }

        /**
         * Checks that some format was offered.
         *
         * @throws IllegalArgumentException if a gray profile meets a picture that is not gray
         */
        private void check() {
            if (type < 0) {
                throw new IllegalArgumentException("a gray color profile needs a picture whose red, green and blue "
                        + "samples are equal");
            }
        }
    }
}
