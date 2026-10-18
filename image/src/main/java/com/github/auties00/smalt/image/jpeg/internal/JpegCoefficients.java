package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Holds the quantized DCT coefficients of every component of a frame, with the quantization
 * tables they were coded with.
 *
 * <p>Buffer {@code i} belongs to frame component {@code i}; it holds 64 coefficients per block in
 * natural (row-major) order, blocks in raster order of the component's MCU-padded block grid,
 * whose row length is {@link JpegComponent#paddedBlocksWide()}.
 */
public final class JpegCoefficients {
    /**
     * The decoded frame.
     */
    private final JpegFrame frame;

    /**
     * The coefficient buffer of each component.
     */
    private final short[][] coefficients;

    /**
     * The quantization table of each component in natural order, all zero for a component no
     * scan referenced.
     */
    private final int[][] quantization;

    /**
     * Constructs the result.
     *
     * @param frame        the decoded frame
     * @param coefficients the coefficient buffer of each component
     * @param quantization the natural-order quantization table of each component
     */
    JpegCoefficients(JpegFrame frame, short[][] coefficients, int[][] quantization) {
        this.frame = frame;
        this.coefficients = coefficients;
        this.quantization = quantization;
    }

    /**
     * Returns the decoded frame.
     *
     * @return the frame
     */
    public JpegFrame frame() {
        return frame;
    }

    /**
     * Returns the coefficient buffer of one component, not a copy.
     *
     * @param component the frame component index
     * @return the coefficients
     */
    public short[] coefficients(int component) {
        return coefficients[component];
    }

    /**
     * Returns the natural-order quantization table of one component, not a copy.
     *
     * @param component the frame component index
     * @return the 64 quantizer values
     */
    public int[] quantization(int component) {
        return quantization[component];
    }
}
