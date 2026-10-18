/* Ports opencore-amr 0.1.6 (frame.h, frame_type_3gpp.h). Copyright PacketVideo; Apache License 2.0. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

/**
 * Lists the AMR-NB frame type enumerations as the integer values the reference's enums have: the
 * receive frame types ({@code RXFrameType}), the transmit frame types ({@code TXFrameType}), the
 * channel decoder types and qualities, and the 3GPP frame types of the storage and RTP formats
 * ({@code Frame_Type_3GPP}).
 */
final class FrameType {
    /**
     * A correctly received speech frame.
     */
    static final int RX_SPEECH_GOOD = 0;

    /**
     * A speech frame received with errors in its unprotected bits.
     */
    static final int RX_SPEECH_DEGRADED = 1;

    /**
     * The onset frame announcing the end of a comfort noise period.
     */
    static final int RX_ONSET = 2;

    /**
     * A speech frame received with errors in its protected bits, to be concealed.
     */
    static final int RX_SPEECH_BAD = 3;

    /**
     * The first silence descriptor of a comfort noise period.
     */
    static final int RX_SID_FIRST = 4;

    /**
     * A silence descriptor update.
     */
    static final int RX_SID_UPDATE = 5;

    /**
     * A corrupt silence descriptor.
     */
    static final int RX_SID_BAD = 6;

    /**
     * A frame with no data (lost, or not sent during comfort noise).
     */
    static final int RX_NO_DATA = 7;

    /**
     * An encoded speech frame.
     */
    static final int TX_SPEECH_GOOD = 0;

    /**
     * The first silence descriptor of a comfort noise period.
     */
    static final int TX_SID_FIRST = 1;

    /**
     * A silence descriptor update.
     */
    static final int TX_SID_UPDATE = 2;

    /**
     * A frame not transmitted during comfort noise.
     */
    static final int TX_NO_DATA = 3;

    /**
     * The 3GPP frame type of a 4.75 kbit/s speech frame.
     */
    static final int AMR_475 = 0;

    /**
     * The 3GPP frame type of a 12.2 kbit/s speech frame.
     */
    static final int AMR_122 = 7;

    /**
     * The 3GPP frame type of an AMR silence descriptor.
     */
    static final int AMR_SID = 8;

    /**
     * The 3GPP frame type of a frame with no data.
     */
    static final int AMR_NO_DATA = 15;

    /**
     * Prevents instantiation.
     */
    private FrameType() {
    }
}
