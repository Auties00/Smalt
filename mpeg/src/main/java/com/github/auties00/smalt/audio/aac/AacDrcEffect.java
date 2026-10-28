package com.github.auties00.smalt.audio.aac;

/**
 * Enumerates the dynamic range control effects an {@link AacDecoder} requests from the MPEG-D DRC metadata of a
 * USAC stream (the {@code drcSetEffect} types of ISO/IEC 23003-4 Table A.48 that a playback system may request).
 *
 * <p>The stream defines which DRC sets it offers. The decoder selects a set with the requested effect; when the
 * stream has none, a request for one of the first six effects falls back to the other five in the order of
 * ISO/IEC 23003-4 Annex E.2.2, and a stream without any of them plays without compression.
 */
public enum AacDrcEffect {
    /**
     * Requests the DRC sets without a compression effect, so the program keeps its dynamic range; DRC sets the
     * stream requires, such as clipping prevention or ducking, still apply.
     */
    NONE,

    /**
     * Requests late night listening, with a reduced dynamic range for low playback levels.
     */
    NIGHT,

    /**
     * Requests listening in a noisy environment.
     */
    NOISY,

    /**
     * Requests playback on a device with a limited dynamic range.
     */
    LIMITED,

    /**
     * Requests playback at a low level.
     */
    LOW_LEVEL,

    /**
     * Requests enhanced dialog intelligibility.
     */
    DIALOG,

    /**
     * Requests general compression.
     */
    GENERAL_COMPRESSION,

    /**
     * Requests an expanded dynamic range.
     */
    EXPANDED,

    /**
     * Requests the artistic effect the content creator intended.
     */
    ARTISTIC
}
