package com.github.auties00.smalt.transcode;

/**
 * Enumerates what a transcode does with a selected stream.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum StreamMode {
    /**
     * Copies the stream when nothing requires re-encoding it, and encodes it otherwise.
     *
     * <p>A stream is copied when the output container can carry its codec (with the configuration
     * that codec needs there), when it matches the codec the options name, if any, and when no
     * requested operation changes its content: for video no size change, crop, rotation of the
     * pixels, frame-rate conversion or pixel-format conversion, for audio no change of sampling
     * rate, channel layout or gain. Trimming never forces encoding (see {@link #COPY}). A
     * {@link OutputContainer#WAV} output copies coded audio only when the options name its codec, and
     * decodes it to PCM otherwise.
     */
    AUTO,

    /**
     * Copies the stream's packets unchanged (remuxing), failing when the output container cannot
     * carry the codec or an operation that needs decoding is requested.
     *
     * <p>A copied stream can only start at a packet a decoder can start from: video at the key
     * frame at or before the requested start, audio at the packet that holds it (plus one packet
     * of decoder pre-roll). Containers with edit lists still present the output from exactly the
     * requested start; other containers present the extra leading media. PCM, which needs no
     * decoder, is cut to exactly the requested samples in every container.
     */
    COPY,

    /**
     * Decodes and re-encodes the stream, applying every requested operation exactly.
     */
    ENCODE,

    /**
     * Leaves the stream out of the output.
     */
    DROP
}
