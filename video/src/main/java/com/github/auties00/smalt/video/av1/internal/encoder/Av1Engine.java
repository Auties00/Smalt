package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * A coding engine of the AV1 encoder: the real-time loop, the good-quality loop or the intra-only
 * frame encoder.
 */
public sealed interface Av1Engine extends AutoCloseable permits Av1RtEncoder, Av1GoodEncoder, StillFrameEncoder {
    /**
     * {@return the {@code av1C} codec configuration record of the stream: the four bytes describing
     * the sequence header, followed by the sequence header OBU}
     */
    byte[] codecConfigurationRecord();

    /**
     * Releases the engine's pictures; closing twice has no effect.
     */
    @Override
    void close();
}
