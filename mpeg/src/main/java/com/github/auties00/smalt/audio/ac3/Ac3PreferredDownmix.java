package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the stereo downmixes a stream may declare its mastering engineer preferred, its
 * {@code dmixmod} (ATSC A/52 table D2.2).
 */
public enum Ac3PreferredDownmix {
    /**
     * No preference is declared.
     */
    NOT_INDICATED,

    /**
     * The matrix-surround compatible Lt/Rt downmix is preferred.
     */
    LT_RT,

    /**
     * The conventional Lo/Ro downmix is preferred.
     */
    LO_RO
}
