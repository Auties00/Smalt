package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;

/**
 * Describes the frame an encoding writes: its size, precision, color space and components, with the
 * component dimensions of T.81 A.1.1 in data units (8x8 blocks, or single samples in a lossless frame).
 *
 * <p>Component {@code c} of factors {@code h} and {@code v} covers {@code ceil(X * h / Hmax)} by
 * {@code ceil(Y * v / Vmax)} samples and {@link #unitsWide}{@code [c]} by {@link #unitsHigh}{@code [c]} data
 * units; a fully interleaved MCU row ("iMCU row") holds {@code v} data-unit rows of every component.
 */
final class FrameLayout {
    /**
     * The frame width in samples.
     */
    final int width;

    /**
     * The frame height in samples.
     */
    final int height;

    /**
     * The sample precision in bits.
     */
    final int precision;

    /**
     * Whether the frame is lossless, so that a data unit is one sample rather than an 8x8 block.
     */
    final boolean lossless;

    /**
     * The color space of the components.
     */
    final JpegEncoderOptions.ColorSpace colorSpace;

    /**
     * The number of components.
     */
    final int count;

    /**
     * The component identifiers.
     */
    final int[] ids;

    /**
     * The horizontal sampling factors.
     */
    final int[] h;

    /**
     * The vertical sampling factors.
     */
    final int[] v;

    /**
     * The quantization table slot of each component.
     */
    final int[] quantSlots;

    /**
     * The entropy table slot of each component, used for both its DC and AC tables.
     */
    final int[] tableSlots;

    /**
     * The largest horizontal factor.
     */
    final int maxH;

    /**
     * The largest vertical factor.
     */
    final int maxV;

    /**
     * The width of each component in samples.
     */
    final int[] sampleWidth;

    /**
     * The height of each component in samples.
     */
    final int[] sampleHeight;

    /**
     * The width of each component in data units.
     */
    final int[] unitsWide;

    /**
     * The height of each component in data units.
     */
    final int[] unitsHigh;

    /**
     * The number of fully interleaved MCU rows.
     */
    final int imcuRows;

    /**
     * The number of samples per side of a data unit: 8, or 1 in a lossless frame.
     */
    final int unit;

    /**
     * Constructs a layout.
     *
     * @param width the frame width
     * @param height the frame height
     * @param precision the sample precision
     * @param lossless whether the frame is lossless
     * @param colorSpace the color space
     * @param ids the component identifiers
     * @param h the horizontal sampling factors, each dividing the largest
     * @param v the vertical sampling factors, each dividing the largest
     * @param quantSlots the quantization table slots
     * @param tableSlots the entropy table slots
     */
    FrameLayout(int width, int height, int precision, boolean lossless, JpegEncoderOptions.ColorSpace colorSpace,
                int[] ids, int[] h, int[] v, int[] quantSlots, int[] tableSlots) {
        this.width = width;
        this.height = height;
        this.precision = precision;
        this.lossless = lossless;
        this.colorSpace = colorSpace;
        this.count = ids.length;
        this.ids = ids;
        this.h = h;
        this.v = v;
        this.quantSlots = quantSlots;
        this.tableSlots = tableSlots;
        this.unit = lossless ? 1 : 8;
        var hMax = 1;
        var vMax = 1;
        for (var c = 0; c < count; c++) {
            hMax = Math.max(hMax, h[c]);
            vMax = Math.max(vMax, v[c]);
        }
        this.maxH = hMax;
        this.maxV = vMax;
        this.sampleWidth = new int[count];
        this.sampleHeight = new int[count];
        this.unitsWide = new int[count];
        this.unitsHigh = new int[count];
        for (var c = 0; c < count; c++) {
            sampleWidth[c] = JpegFrame.ceilDiv((long) width * h[c], hMax);
            sampleHeight[c] = JpegFrame.ceilDiv((long) height * v[c], vMax);
            unitsWide[c] = JpegFrame.ceilDiv((long) width * h[c], hMax * unit);
            unitsHigh[c] = JpegFrame.ceilDiv((long) height * v[c], vMax * unit);
        }
        this.imcuRows = JpegFrame.ceilDiv(height, vMax * unit);
    }

    /**
     * {@return the number of MCUs per row of an interleaved scan}
     */
    int mcusWide() {
        return JpegFrame.ceilDiv(width, maxH * unit);
    }

    /**
     * Returns the number of data units in an interleaved MCU of some components.
     *
     * @param components the frame indexes of the components
     * @return the sum of {@code h * v} over them
     */
    int unitsPerMcu(int[] components) {
        var units = 0;
        for (var c : components) {
            units += h[c] * v[c];
        }
        return units;
    }

    /**
     * Returns the number of data units of a component's band, its share of one iMCU row: {@code v} rows of
     * data units, each as wide as the component padded to whole MCUs.
     *
     * @param component the component index
     * @return the band width in samples
     */
    int bandWidth(int component) {
        return unitsWide[component] * unit;
    }
}
