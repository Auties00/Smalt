package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Supplies the decoded samples of a frame whose samples are not DCT coded: a lossless JPEG frame, a JPEG-LS
 * frame or a hierarchical image whose last frames are lossless, for {@link LosslessOutput}.
 *
 * <p>Component {@code c} of factors {@code h} and {@code v} is {@link #componentWidth(int)} samples wide and
 * {@code ceil(Y * v / Vmax)} rows high, for a frame of {@code X} by {@code Y} samples and the largest factors
 * {@code Hmax} and {@code Vmax}.
 */
public interface LosslessSamples {
    /**
     * {@return the width of a component, {@code ceil(X * h / Hmax)}}
     *
     * @param component the component, in frame order
     */
    int componentWidth(int component);

    /**
     * {@return whether every component has the size of the frame, which {@link #decode} requires}
     */
    boolean fullResolution();

    /**
     * {@return whether {@link #decode} hands over the rows as it decodes them, without first decoding every
     * component whole}
     */
    boolean streamsRows();

    /**
     * Decodes every row of a frame whose components all have the frame's size, the components of each column
     * interleaved in frame order.
     *
     * @param sink receives the rows
     * @throws IllegalStateException if a component is subsampled ({@link #fullResolution()} is {@code false})
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the components decoded first exceed the
     * limits
     */
    void decode(LosslessJpeg.RowSink sink);

    /**
     * Decodes every component, handing over its rows.
     *
     * <p>The rows of a component arrive in order, each once, from one thread; those of different components may
     * interleave in any order and arrive from different threads at the same time, so the sink must not share
     * state between components.
     *
     * @param sink receives the rows
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if a buffer exceeds the limits
     */
    void decodeComponents(LosslessJpeg.ComponentSink sink);
}
