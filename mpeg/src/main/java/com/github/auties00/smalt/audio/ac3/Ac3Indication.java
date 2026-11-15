package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the answers a stream gives to whether its program was matrix-encoded by a process,
 * as its {@code dsurmod} and {@code dheadphonmod} fields give them (ATSC A/52 tables 5.11 and D2.8).
 */
public enum Ac3Indication {
    /**
     * The stream does not say.
     */
    NOT_INDICATED,

    /**
     * The program was not encoded by the process.
     */
    NOT_ENCODED,

    /**
     * The program was encoded by the process.
     */
    ENCODED
}
