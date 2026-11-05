package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the matrix encodings of the surround channels a 2/2 or 3/2 program may declare, its
 * {@code dsurexmod} (ATSC A/52 table D2.7).
 */
public enum Ac3SurroundExMode {
    /**
     * The stream does not say.
     */
    NOT_INDICATED,

    /**
     * The program is encoded neither in Dolby Surround EX, nor in Dolby Pro Logic IIx or IIz.
     */
    NOT_ENCODED,

    /**
     * The program is encoded in Dolby Surround EX or Dolby Pro Logic IIx.
     */
    SURROUND_EX,

    /**
     * The program is encoded in Dolby Pro Logic IIz.
     */
    PRO_LOGIC_IIZ
}
