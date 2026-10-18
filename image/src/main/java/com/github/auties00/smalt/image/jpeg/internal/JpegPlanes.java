package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Holds the decoded sample planes of every component of a frame, before any upsampling or color
 * conversion.
 *
 * <p>Plane {@code i} belongs to frame component {@code i}. It is MCU-padded: its stride and row
 * count cover whole blocks, and only the top-left {@link #componentWidth(int)} by
 * {@link #componentHeight(int)} samples are meaningful.
 */
public final class JpegPlanes {
    /**
     * The decoded frame.
     */
    private final JpegFrame frame;

    /**
     * The sample plane of each component.
     */
    private final byte[][] planes;

    /**
     * The row stride of each plane.
     */
    private final int[] strides;

    /**
     * The binary logarithm of the size reduction.
     */
    private final int scaleShift;

    /**
     * Constructs the result.
     *
     * @param frame the decoded frame
     * @param planes the sample plane of each component
     * @param strides the row stride of each plane
     * @param scaleShift the binary logarithm of the size reduction
     */
    JpegPlanes(JpegFrame frame, byte[][] planes, int[] strides, int scaleShift) {
        this.frame = frame;
        this.planes = planes;
        this.strides = strides;
        this.scaleShift = scaleShift;
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
     * Returns the plane of one component, not a copy.
     *
     * @param component the frame component index
     * @return the samples
     */
    public byte[] plane(int component) {
        return planes[component];
    }

    /**
     * Returns the row stride of one component's plane.
     *
     * @param component the frame component index
     * @return the stride
     */
    public int stride(int component) {
        return strides[component];
    }

    /**
     * Returns the binary logarithm of the size reduction.
     *
     * @return 0 for full size up to 3 for 1/8
     */
    public int scaleShift() {
        return scaleShift;
    }

    /**
     * Returns the decoded picture width.
     *
     * @return {@code ceil(frameWidth / 2^scaleShift)}
     */
    public int width() {
        return JpegFrame.ceilDiv(frame.width, 1 << scaleShift);
    }

    /**
     * Returns the decoded picture height.
     *
     * @return {@code ceil(frameHeight / 2^scaleShift)}
     */
    public int height() {
        return JpegFrame.ceilDiv(frame.height, 1 << scaleShift);
    }

    /**
     * Returns the meaningful width of one component's plane.
     *
     * @param component the frame component index
     * @return {@code ceil(componentWidth / 2^scaleShift)}
     */
    public int componentWidth(int component) {
        return JpegFrame.ceilDiv(frame.components[component].width, 1 << scaleShift);
    }

    /**
     * Returns the meaningful height of one component's plane.
     *
     * @param component the frame component index
     * @return {@code ceil(componentHeight / 2^scaleShift)}
     */
    public int componentHeight(int component) {
        return JpegFrame.ceilDiv(frame.components[component].height, 1 << scaleShift);
    }
}
