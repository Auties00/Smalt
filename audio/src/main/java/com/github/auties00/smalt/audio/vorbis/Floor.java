package com.github.auties00.smalt.audio.vorbis;

/**
 * Describes one Vorbis floor configuration: the spectral envelope a channel's residue is
 * multiplied by.
 *
 * <p>Decoding is split in two phases, as the specification allows: {@link #decode} reads the
 * floor's packet data for one channel into a {@link FloorData} before the residue is decoded, and
 * {@link #apply} later multiplies the channel's residue by the synthesized curve, fusing curve
 * generation with the floor/residue dot product.
 */
sealed abstract class Floor permits Floor0, Floor1 {
    /**
     * Constructs a floor configuration.
     */
    Floor() {
    }

    /**
     * Reads the floor data of one channel from an audio packet.
     *
     * <p>End-of-packet during the read is a nominal occurrence that makes the channel unused.
     *
     * @param reader the packet reader, positioned at the floor data
     * @param data   receives the decoded values
     * @return {@code true} when the channel is used, {@code false} when it is unused this frame
     */
    abstract boolean decode(BitReader reader, FloorData data);

    /**
     * Multiplies a channel's residue by the floor curve decoded into {@code data}.
     *
     * @param data     the floor data returned by a successful {@link #decode}
     * @param spectrum the residue, replaced by the audio spectrum
     * @param n        the half block size, the number of spectral lines
     * @param longBlock whether the block is a long block
     */
    abstract void apply(FloorData data, float[] spectrum, int n, boolean longBlock);

    /**
     * Returns the number of integer values one decoded channel needs.
     *
     * @return the capacity {@link FloorData#values} must have
     */
    abstract int intCapacity();

    /**
     * Returns the number of float values one decoded channel needs.
     *
     * @return the capacity {@link FloorData#coefficients} must have
     */
    abstract int floatCapacity();
}
