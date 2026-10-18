package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.RgbaPicture;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds a picture reduced to a palette: one palette index per pixel.
 *
 * <p>Palette entries are {@code 0xAARRGGBB} colors with straight alpha; every entry is opaque
 * except the transparent one, {@code 0x00000000}, which exists only when some pixel is
 * transparent. Indices are stored row by row, one unsigned byte per pixel, with no padding.
 *
 * <p>{@link #palette()} and {@link #indices()} return copies on every call; {@link #index(int, int)}
 * reads one pixel's index without copying. Instances are immutable and thread-safe.
 */
public final class QuantizedImage {
    /**
     * The width in pixels.
     */
    private final int width;

    /**
     * The height in pixels.
     */
    private final int height;

    /**
     * The palette entries.
     */
    private final int[] palette;

    /**
     * The palette index of every pixel.
     */
    private final byte[] indices;

    /**
     * The index of the transparent entry, or {@code -1}.
     */
    private final int transparentIndex;

    /**
     * Creates a quantized image, taking ownership of the arrays.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param palette the palette entries
     * @param indices the palette index of each pixel, row-major
     * @param transparentIndex the index of the transparent entry, or {@code -1}
     */
    QuantizedImage(int width, int height, int[] palette, byte[] indices, int transparentIndex) {
        this.width = width;
        this.height = height;
        this.palette = palette;
        this.indices = indices;
        this.transparentIndex = transparentIndex;
    }

    /**
     * Returns the width in pixels.
     *
     * @return the width, positive
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height in pixels.
     *
     * @return the height, positive
     */
    public int height() {
        return height;
    }

    /**
     * Returns the number of palette entries.
     *
     * @return the palette size, from 1 to 256
     */
    public int paletteSize() {
        return palette.length;
    }

    /**
     * Returns the palette.
     *
     * @return a copy of the entries, as {@code 0xAARRGGBB}
     */
    public int[] palette() {
        return palette.clone();
    }

    /**
     * Returns the palette indices of the pixels, row by row.
     *
     * @return a copy of the {@code width * height} indices, each an unsigned byte
     */
    public byte[] indices() {
        return indices.clone();
    }

    /**
     * Returns the palette index of one pixel.
     *
     * @param x the column
     * @param y the row
     * @return the index, from {@code 0} to {@code paletteSize() - 1}
     * @throws IndexOutOfBoundsException if the pixel is outside the image
     */
    public int index(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        return indices[y * width + x] & 0xFF;
    }

    /**
     * Returns the index of the transparent palette entry.
     *
     * @return the index, or an empty optional when every pixel is opaque
     */
    public OptionalInt transparentIndex() {
        return transparentIndex < 0 ? OptionalInt.empty() : OptionalInt.of(transparentIndex);
    }

    /**
     * Expands the image back to RGBA, each pixel taking its palette color.
     *
     * @return a new picture, reporting alpha when the palette has a transparent entry
     */
    public RgbaPicture toRgba() {
        var out = RgbaPicture.allocate(width, height, transparentIndex >= 0);
        var pixels = out.pixels();
        for (var i = 0; i < indices.length; i++) {
            var color = palette[indices[i] & 0xFF];
            pixels[4 * i] = (byte) (color >>> 16);
            pixels[4 * i + 1] = (byte) (color >>> 8);
            pixels[4 * i + 2] = (byte) color;
            pixels[4 * i + 3] = (byte) (color >>> 24);
        }
        return out;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QuantizedImage that
                && width == that.width
                && height == that.height
                && transparentIndex == that.transparentIndex
                && Arrays.equals(palette, that.palette)
                && Arrays.equals(indices, that.indices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, transparentIndex, Arrays.hashCode(palette), Arrays.hashCode(indices));
    }

    @Override
    public String toString() {
        return "QuantizedImage[" + width + "x" + height + ", " + palette.length + " colors"
                + (transparentIndex >= 0 ? ", transparent index " + transparentIndex : "") + "]";
    }
}
