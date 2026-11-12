package com.github.auties00.smalt.audio.ac3;

/**
 * Enumerates the types of service an AC-3 or E-AC-3 stream declares, its bit stream mode
 * {@code bsmod} (ATSC A/52 table 5.7).
 */
public enum Ac3ServiceType {
    /**
     * A main audio service carrying a complete program, {@code bsmod} 0.
     */
    COMPLETE_MAIN(0),

    /**
     * A main audio service carrying the music and effects of a program without its dialogue,
     * {@code bsmod} 1.
     */
    MUSIC_AND_EFFECTS(1),

    /**
     * An associated service for the visually impaired, {@code bsmod} 2.
     */
    VISUALLY_IMPAIRED(2),

    /**
     * An associated service for the hearing impaired, {@code bsmod} 3.
     */
    HEARING_IMPAIRED(3),

    /**
     * An associated service carrying dialogue, {@code bsmod} 4.
     */
    DIALOGUE(4),

    /**
     * An associated service carrying commentary, {@code bsmod} 5.
     */
    COMMENTARY(5),

    /**
     * An associated service carrying an emergency announcement, {@code bsmod} 6.
     */
    EMERGENCY(6),

    /**
     * An associated service carrying a voice over, {@code bsmod} 7 in a mono stream.
     */
    VOICE_OVER(7),

    /**
     * A main audio service carrying karaoke, {@code bsmod} 7 in a stream of two or more
     * full-bandwidth channels.
     */
    KARAOKE(7);

    /**
     * The code of the service.
     */
    private final int code;

    Ac3ServiceType(int code) {
        this.code = code;
    }

    /**
     * {@return the bit stream mode {@code bsmod} of the service, from 0 to 7}
     */
    public int code() {
        return code;
    }
}
