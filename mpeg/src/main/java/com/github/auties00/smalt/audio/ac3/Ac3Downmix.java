package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the output channel configurations an {@link Ac3Decoder} can produce.
 *
 * <p>Every downmix follows ATSC A/52 section 7.8 with the mix levels the stream carries, and is
 * scaled so that it cannot exceed full scale. A downmixed E-AC-3 program is mixed from its 5.1
 * independent substream only, as Annex E section 3.8.2 requires; channel extensions are decoded
 * only by {@link #NONE}.
 */
public enum Ac3Downmix {
    /**
     * Outputs every coded channel, in WAVE order: the native layout of the stream, including the
     * channels of E-AC-3 dependent substreams (7.1 and beyond).
     */
    NONE,

    /**
     * Outputs one channel: the sum of the Lo/Ro downmix.
     */
    MONO,

    /**
     * Outputs a conventional stereo (Lo/Ro) downmix.
     */
    STEREO,

    /**
     * Outputs a matrix surround compatible stereo (Lt/Rt) downmix, which a matrix decoder can
     * steer back to center and surround.
     */
    MATRIX_STEREO,

    /**
     * Outputs the stereo downmix the stream prefers ({@code dmixmod}): Lt/Rt when it asks for it,
     * Lo/Ro otherwise.
     */
    PREFERRED_STEREO
}
