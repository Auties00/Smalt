package com.github.auties00.smalt.internal.header;

/**
 * Maps the Vorbis channel order (Vorbis I section 4.3.9, which Opus mapping families 0 and 1 also
 * use, RFC 7845 section 5.1.1.2) to the WAVE order of
 * {@link com.github.auties00.smalt.audio.ChannelLayout#defaultFor(int) ChannelLayout.defaultFor}.
 *
 * <p>The orders are defined for one to eight channels. This class is thread-safe.
 */
public final class VorbisChannelOrder {
    /**
     * The WAVE position of each Vorbis channel, indexed by channel count and Vorbis index.
     */
    private static final int[][] TO_WAVE = {
            {},
            {0},
            {0, 1},
            {0, 2, 1},
            {0, 1, 2, 3},
            {0, 2, 1, 3, 4},
            {0, 2, 1, 4, 5, 3},
            {0, 2, 1, 5, 6, 4, 3},
            {0, 2, 1, 6, 7, 4, 5, 3}
    };

    /**
     * The largest channel count with a defined order.
     */
    public static final int MAX_CHANNELS = 8;

    /**
     * Prevents instantiation.
     */
    private VorbisChannelOrder() {
        throw new AssertionError();
    }

    /**
     * Returns the WAVE position of every Vorbis channel.
     *
     * @param channels the channel count
     * @return a new array whose entry {@code i} is the WAVE position of Vorbis channel {@code i},
     * or {@code null} when {@code channels} is outside 1 to 8
     */
    public static int[] toWave(int channels) {
        return channels < 1 || channels > MAX_CHANNELS ? null : TO_WAVE[channels].clone();
    }

    /**
     * Returns the WAVE position of one Vorbis channel.
     *
     * @param channels the channel count, from 1 to 8
     * @param index the channel index in Vorbis order, from 0 to {@code channels - 1}
     * @return the channel index in WAVE order
     * @throws IndexOutOfBoundsException if an argument is out of range
     */
    public static int waveIndex(int channels, int index) {
        if (channels < 1 || channels > MAX_CHANNELS) {
            throw new IndexOutOfBoundsException("no Vorbis channel order for " + channels + " channels");
        }
        return TO_WAVE[channels][index];
    }
}
