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
        this.planes = planes;
        this.strides = strides;
        this.coefficients = coefficients;
        this.nonzero = nonzero;
        this.zigzagQuant = new int[components][];
        this.scaleShift = scaleShift;
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
