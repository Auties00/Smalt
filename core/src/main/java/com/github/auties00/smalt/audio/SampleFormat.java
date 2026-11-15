package com.github.auties00.smalt.audio;

/**
 * The representation of the samples an {@link AudioStreamDecoder} or {@link AudioReader} outputs.
 */
public enum SampleFormat {
    /**
     * 32-bit float samples nominally in {@code [-1, 1]}, as {@link AudioBuffer} holds them; every codec decodes to
     * them, and the output can be resampled and remixed.
     */
    FLOAT,

    /**
     * The source's own signed integer samples, as {@link IntegerAudioBuffer} holds them, bit for bit; only codecs
     * that code integers decode to them ({@link AudioDecoder#supportsIntegerOutput()}), at their own rate and layout.
     */
    INTEGER,

    /**
     * 64-bit float samples nominally in {@code [-1, 1]}, as {@link DoubleAudioBuffer} holds them, at the codec's own
     * rate and layout: exact for 64-bit float sources and for integer sources of up to 53 bits where the codec
     * offers them ({@link AudioDecoder#supportsDoubleOutput()}), and the 32-bit float output widened otherwise.
     */
    DOUBLE
}
