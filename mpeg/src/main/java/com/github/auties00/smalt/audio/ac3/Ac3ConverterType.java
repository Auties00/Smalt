package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the types of analog-to-digital converter that captured a program, its
 * {@code adconvtyp} (ATSC A/52 table D2.9).
 */
public enum Ac3ConverterType {
    /**
     * A standard converter, also the type to declare when it is not known.
     */
    STANDARD,

    /**
     * A High Definition Compatible Digital (HDCD) converter.
     */
    HDCD
}
