package com.github.auties00.smalt.image.jpeg.internal;

import java.util.List;

/**
 * Describes a JPEG frame: its coding process, size, and components.
 *
 * <p>The height may be zero in the SOF segment, in which case a DNL segment after the first scan
 * defines it and {@link #setHeight(int)} is called before any block is decoded.
 */
public final class JpegFrame {
    /**
     * The SOF marker code, {@code 0xC0} to {@code 0xCF}.
     */
    final int marker;

    /**
     * The sample precision in bits.
     */
    final int precision;

    /**
     * The width in pixels.
     */
    final int width;

    /**
     * The height in pixels, zero until defined by a DNL segment when the SOF left it open.
     */
    int height;

    /**
     * The components, in frame header order.
     */
    final JpegComponent[] components;

    /**
     * The largest horizontal sampling factor.
     */
    final int hMax;

    /**
     * The largest vertical sampling factor.
     */
    final int vMax;

    /**
     * The number of MCU columns of an interleaved scan.
     */
    int mcusWide;

    /**
     * The number of MCU rows of an interleaved scan.
     */
    int mcusHigh;

    /**
     * Constructs a frame.
     *
     * @param marker the SOF marker code
     * @param precision the sample precision in bits
     * @param width the width in pixels
     * @param height the height in pixels, or zero when a DNL segment defines it
     * @param components the components, in frame header order
     */
    JpegFrame(int marker, int precision, int width, int height, JpegComponent[] components) {
        this.marker = marker;
        this.precision = precision;
        this.width = width;
        this.height = height;
        this.components = components;
        var hMax = 1;
        var vMax = 1;
        for (var component : components) {
            hMax = Math.max(hMax, component.h);
            vMax = Math.max(vMax, component.v);
        }
        this.hMax = hMax;
        this.vMax = vMax;
    }

    /**
     * Returns whether the frame uses the progressive coding process.
     *
     * @return {@code true} for SOF2, SOF6, SOF10 and SOF14
     */
    public boolean progressive() {
        return (marker & 3) == 2;
    }

    /**
     * Returns whether the frame uses arithmetic entropy coding.
     *
     * @return {@code true} for SOF9 to SOF15
     */
    public boolean arithmetic() {
        return marker >= 0xC9;
    }

    /**
     * Defines the frame height from a DNL segment.
     *
     * @param height the number of lines, positive
     */
    void setHeight(int height) {
        this.height = height;
    }

    /**
     * Derives the MCU grid and the block layout of every component; requires a positive height.
     */
    void computeLayout() {
        mcusWide = ceilDiv(width, hMax * 8);
        mcusHigh = ceilDiv(height, vMax * 8);
        for (var component : components) {
            component.width = ceilDiv(width * component.h, hMax);
            component.height = ceilDiv(height * component.v, vMax);
            component.blocksWide = ceilDiv(component.width, 8);
            component.blocksHigh = ceilDiv(component.height, 8);
            if (components.length == 1) {
                component.paddedBlocksWide = component.blocksWide;
                component.paddedBlocksHigh = component.blocksHigh;
            } else {
                component.paddedBlocksWide = mcusWide * component.h;
                component.paddedBlocksHigh = mcusHigh * component.v;
            }
        }
    }

    /**
     * Divides rounding up.
     *
     * @param value the dividend, non-negative
     * @param divisor the divisor, positive
     * @return {@code ceil(value / divisor)}
     */
    static int ceilDiv(long value, int divisor) {
        return (int) ((value + divisor - 1) / divisor);
    }

    /**
     * {@return the sample precision in bits}
     */
    public int precision() {
        return precision;
    }

    /**
     * Returns the width.
     *
     * @return the width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height.
     *
     * @return the height in pixels, or zero while a DNL segment has not defined it
     */
    public int height() {
        return height;
    }

    /**
     * Returns the components.
     *
     * @return the components in frame header order
     */
    public List<JpegComponent> components() {
        return List.of(components);
    }

    /**
     * Returns the largest horizontal sampling factor.
     *
     * @return the factor
     */
    public int hMax() {
        return hMax;
    }

    /**
     * Returns the largest vertical sampling factor.
     *
     * @return the factor
     */
    public int vMax() {
        return vMax;
    }
}
