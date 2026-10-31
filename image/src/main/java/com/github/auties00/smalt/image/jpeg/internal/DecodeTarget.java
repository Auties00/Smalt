package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Holds where the entropy decoders put what they decode: sample planes, coefficient buffers, or
 * both, indexed by the component's position in the frame header.
 *
 * <p>In pixel mode, sequential scans transform every block as soon as it is decoded and write the
 * samples into {@link #planes}, at {@code 8 >> scaleShift} samples per block side. In coefficient
 * mode, every scan stores quantized coefficients into {@link #coefficients}; progressive frames
 * always use coefficient mode and are transformed after the last scan.
 */
final class DecodeTarget {
    /**
     * The sample plane of each component, or {@code null} in coefficient mode.
     */
    final byte[][] planes;

    /**
     * The row stride of each sample plane.
     */
    final int[] strides;

    /**
     * The quantized coefficients of each component, 64 per block in natural order, blocks in
     * raster order of the MCU-padded grid, or {@code null} in pixel mode.
     */
    final short[][] coefficients;

    /**
     * The zigzag-order mask of nonzero AC coefficients of every block of each component, kept by
     * progressive Huffman scans so refinement scans visit only nonzero positions, or
     * {@code null} when not needed.
     */
    final long[][] nonzero;

    /**
     * The dequantization table latched for each component, in zigzag order padded to 80 entries,
     * or {@code null} before the component's first scan.
     */
    final int[][] zigzagQuant;

    /**
     * The binary logarithm of the size reduction, from 0 (full size) to 3 (1/8).
     */
    final int scaleShift;

    /**
     * Whether the frame is a differential frame of hierarchical coding, whose DC coefficients are coded
     * directly, without prediction (T.81 J.2.3.1).
     */
    final boolean differential;

    /**
     * The MCUs of the frame the sample planes hold in pixel mode, as the left, top, right and bottom MCU column and
     * row (the last two exclusive), or {@code null} when they hold every MCU.
     *
     * <p>Plane {@code i} then starts at block column {@code window[0] * h} and block row {@code window[1] * v} of
     * component {@code i}; sequential scans decode the MCUs outside the window without storing them, and stop
     * after its last MCU row.
     */
    final int[] window;

    /**
     * Constructs a target.
     *
     * @param components   the number of frame components
     * @param planes       the sample planes, or {@code null} in coefficient mode
     * @param strides      the plane strides, or {@code null} in coefficient mode
     * @param coefficients the coefficient buffers, or {@code null} in pixel mode
     * @param nonzero      the nonzero masks of every block, or {@code null}
     * @param scaleShift   the binary logarithm of the size reduction
     */
    DecodeTarget(int components, byte[][] planes, int[] strides, short[][] coefficients, long[][] nonzero,
                 int scaleShift) {
        this(components, planes, strides, coefficients, nonzero, scaleShift, false);
    }

    /**
     * Constructs a target, possibly for a differential frame.
     *
     * @param components the number of frame components
     * @param planes the sample planes, or {@code null} in coefficient mode
     * @param strides the plane strides, or {@code null} in coefficient mode
     * @param coefficients the coefficient buffers, or {@code null} in pixel mode
     * @param nonzero the nonzero masks of every block, or {@code null}
     * @param scaleShift the binary logarithm of the size reduction
     * @param differential whether the DC coefficients are coded without prediction
     */
    DecodeTarget(int components, byte[][] planes, int[] strides, short[][] coefficients, long[][] nonzero,
                 int scaleShift, boolean differential) {
        this(components, planes, strides, coefficients, nonzero, scaleShift, differential, null);
    }

    /**
     * Constructs a target whose sample planes hold a window of MCUs.
     *
     * @param components the number of frame components
     * @param planes the sample planes, or {@code null} in coefficient mode
     * @param strides the plane strides, or {@code null} in coefficient mode
     * @param coefficients the coefficient buffers, or {@code null} in pixel mode
     * @param nonzero the nonzero masks of every block, or {@code null}
     * @param scaleShift the binary logarithm of the size reduction
     * @param differential whether the DC coefficients are coded without prediction
     * @param window the MCUs the planes hold, as {@link #window} describes, or {@code null} for every MCU
     */
    DecodeTarget(int components, byte[][] planes, int[] strides, short[][] coefficients, long[][] nonzero,
                 int scaleShift, boolean differential, int[] window) {
        this.planes = planes;
        this.strides = strides;
        this.coefficients = coefficients;
        this.nonzero = nonzero;
        this.zigzagQuant = new int[components][];
        this.scaleShift = scaleShift;
        this.differential = differential;
        this.window = window;
    }

    /**
     * Returns the MCUs of a scan the sample planes hold.
     *
     * @param scan the scan being decoded
     * @return the left, top, right and bottom MCU column and row of the scan (the last two exclusive), or
     * {@code null} when the planes hold every MCU
     */
    int[] scanWindow(JpegScan scan) {
        if (window == null || !pixels()) {
            return null;
        }
        if (scan.interleaved()) {
            return new int[]{window[0], window[1], Math.min(window[2], scan.mcusWide),
                    Math.min(window[3], scan.mcusHigh)};
        }
        var component = scan.components[0];
        return new int[]{window[0] * component.h, window[1] * component.v,
                Math.min(window[2] * component.h, scan.mcusWide), Math.min(window[3] * component.v, scan.mcusHigh)};
    }

    /**
     * Returns whether sequential scans write samples directly.
     *
     * @return {@code true} in pixel mode
     */
    boolean pixels() {
        return coefficients == null;
    }
}
