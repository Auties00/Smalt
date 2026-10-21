package com.github.auties00.smalt.audio.opus;

/**
 * A channel mapping family of Ogg Opus (RFC 7845 section 5.1.1), which splits the channels of an
 * {@link OpusEncoder} into elementary streams and tells a decoder where each channel plays.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum OpusChannelMapping {
    /**
     * Families 0 and 1, the Vorbis channel order of mono, stereo, 3.0, quad, 5.0, 5.1, 6.1 and 7.1.
     *
     * <p>One or two channels code a single elementary stream (family 0). Three to eight channels
     * (family 1) code each left and right pair as a coupled stereo stream, the other channels as
     * mono streams, and the LFE channel of 5.1, 6.1 and 7.1 as a narrowband stream; the encoder
     * analyzes how much the channels mask each other and moves bits to the bands that stay
     * audible. The input layout must hold the speakers of {@link
     * com.github.auties00.smalt.audio.ChannelLayout#defaultFor(int) ChannelLayout.defaultFor} for its
     * channel count, in any order; for quad, 5.0 and 5.1 the side speakers may stand in for the back
     * ones. A decoder outputs that default layout.
     */
    VORBIS(1),

    /**
     * Family 2, ambisonics: {@code (order + 1)^2} channels in ACN order with SN3D normalization,
     * optionally followed by a non-diegetic stereo pair, up to 227 channels.
     *
     * <p>Each ambisonic channel is coded as a mono stream and the stereo pair as a coupled stream,
     * all in CELT. The channel order is the input order; the speakers of the input layout are not
     * used.
     */
    AMBISONIC(2),

    /**
     * Family 3, projection-coded ambisonics (RFC 8486 section 3.2): {@code (order + 1)^2} channels in
     * ACN order with SN3D normalization for an order from 1 to 5 (4, 9, 16, 25 or 36 channels),
     * optionally followed by a non-diegetic stereo pair.
     *
     * <p>The encoder mixes the channels with a fixed matrix of the order into coupled streams (and
     * one mono stream for an odd count), which may use every coding mode; the identification header
     * carries the matrix that undoes the mix and, as its output gain, the gain that matrix needs. A
     * decoder outputs the ambisonic channels, of unknown position, in input order.
     */
    AMBISONIC_PROJECTION(3),

    /**
     * Family 255, discrete channels: every channel is coded as its own mono stream, in input order,
     * for 1 to 255 channels.
     *
     * <p>The stream carries no speaker positions, so a decoder outputs channels of unknown position.
     */
    DISCRETE(255);

    /**
     * The mapping family the {@code OpusHead} identification header declares.
     */
    private final int family;

    OpusChannelMapping(int family) {
        this.family = family;
    }

    /**
     * Returns the mapping family the identification header declares for a channel count.
     *
     * @param channels the number of channels
     * @return 0 for {@link #VORBIS} with one or two channels, otherwise 1, 2 or 255
     */
    int family(int channels) {
        return this == VORBIS && channels <= 2 ? 0 : family;
    }
}
