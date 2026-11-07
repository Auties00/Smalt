package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the types of the room a program was mixed in, its {@code roomtyp} (ATSC A/52 table
 * 5.12).
 */
public enum Ac3RoomType {
    /**
     * The stream does not say.
     */
    NOT_INDICATED,

    /**
     * A large room with an X-curve monitor.
     */
    LARGE,

    /**
     * A small room with a flat monitor.
     */
    SMALL
}
