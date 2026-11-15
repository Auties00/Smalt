package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Holds the decoded sample planes of every component of a frame, before any upsampling or color
 * conversion.
 *
 * <p>Plane {@code i} belongs to frame component {@code i}. It is MCU-padded: its stride and row
 * count cover whole blocks, and only the top-left {@link #componentWidth(int)} by
 * {@link #componentHeight(int)} samples are meaningful. The planes hold either 8-bit samples
 * ({@link #plane(int)}) or, for the planes of a 12-bit frame kept at their precision, 12-bit
 * samples ({@link #widePlane(int)}); {@link #wide()} tells which.
 */
public final class JpegPlanes {
    /**
     * The decoded frame.
     */
    private final JpegFrame frame;

    /**
     * The 8-bit sample plane of each component, or {@code null} when the planes are wide.
     */
    private final byte[][] planes;

    /**
     * The 12-bit sample plane of each component, or {@code null} when the planes are 8-bit.
     */
    private final short[][] widePlanes;

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
        this.widePlanes = null;
        this.strides = strides;
        this.scaleShift = scaleShift;
    }

    /**
     * Constructs the result of a 12-bit frame kept at its precision.
     *
     * @param frame the decoded frame
     * @param widePlanes the 12-bit sample plane of each component
     * @param strides the row stride of each plane
     * @param scaleShift the binary logarithm of the size reduction
     */
    JpegPlanes(JpegFrame frame, short[][] widePlanes, int[] strides, int scaleShift) {
        this.frame = frame;
        this.planes = null;
        this.widePlanes = widePlanes;
        this.strides = strides;
        this.scaleShift = scaleShift;
    }

    /**
     * {@return whether the planes hold 12-bit samples, read through {@link #widePlane(int)}}
     */
    public boolean wide() {
        return widePlanes != null;
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
     * Returns the 8-bit plane of one component, not a copy.
     *
     * @param component the frame component index
     * @return the samples
     * @throws IllegalStateException if the planes are {@linkplain #wide() wide}
     */
    public byte[] plane(int component) {
        if (planes == null) {
            throw new IllegalStateException("the planes hold 12-bit samples");
        }
        return planes[component];
    }

    /**
     * Returns the 12-bit plane of one component, not a copy.
     *
     * @param component the frame component index
     * @return the samples, from 0 to 4095
     * @throws IllegalStateException if the planes are not {@linkplain #wide() wide}
     */
    public short[] widePlane(int component) {
        if (widePlanes == null) {
            throw new IllegalStateException("the planes hold 8-bit samples");
        }
        return widePlanes[component];
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
