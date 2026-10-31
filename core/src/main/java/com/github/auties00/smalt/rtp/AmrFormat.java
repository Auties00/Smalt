package com.github.auties00.smalt.rtp;

/**
 * Holds the RFC 4867 parameters of an AMR or AMR-WB payload format and the frame tables of TS 26.101 and TS 26.201.
 */
final class AmrFormat {
    /** The speech bits of every AMR frame type (RFC 4867 table 1; SID is 39 bits). */
    private static final int[] NB_BITS = {95, 103, 118, 134, 148, 159, 204, 244, 39, 0, 0, 0, 0, 0, 0, 0};

    /** The speech bits of every AMR-WB frame type (TS 26.201 table 2; SID is 40 bits). */
    private static final int[] WB_BITS = {132, 177, 253, 285, 317, 365, 397, 461, 477, 40, 0, 0, 0, 0, 0, 0};

    /** The class A bits of every AMR frame type (RFC 4867 table 1). */
    private static final int[] NB_CLASS_A = {42, 49, 55, 58, 61, 75, 65, 81, 39, 0, 0, 0, 0, 0, 0, 0};

    /** The class A bits of every AMR-WB frame type (TS 26.201 table 2; RFC 4867 section 4.4.2.1 gives SID 40). */
    private static final int[] WB_CLASS_A = {54, 64, 72, 72, 72, 72, 72, 72, 72, 40, 0, 0, 0, 0, 0, 0};

    /** The frame type of a frame not transmitted. */
    static final int NO_DATA = 15;

    /** The frame type of a lost AMR-WB speech frame. */
    static final int SPEECH_LOST = 14;

    /** Whether the codec is AMR-WB. */
    final boolean wideband;
    /** The channel count. */
    final int channels;
    /** Whether the session uses the octet-aligned mode. */
    final boolean octetAligned;
    /** Whether the octet-aligned payloads carry frame CRCs. */
    final boolean crc;
    /** Whether the octet-aligned payloads use robust sorting. */
    final boolean robustSorting;
    /** The {@code interleaving} parameter, the largest interleaving group in frame-blocks, 0 when absent. */
    final int interleaving;

    private AmrFormat(boolean wideband, int channels, boolean octetAligned, boolean crc, boolean robustSorting,
                      int interleaving) {
        this.wideband = wideband;
        this.channels = channels;
        this.octetAligned = octetAligned;
        this.crc = crc;
        this.robustSorting = robustSorting;
        this.interleaving = interleaving;
    }

    /**
     * Reads the parameters of a format; {@code crc}, {@code robust-sorting} and {@code interleaving} imply the
     * octet-aligned mode.
     *
     * @throws com.github.auties00.smalt.MediaException.Malformed if a parameter is not valid
     */
    static AmrFormat of(RtpPayloadFormat format) {
        var wideband = format.encodingName().equals("AMR-WB");
        var channels = format.channels().orElse(1);
        var crc = format.flagParameter("crc");
        var robust = format.flagParameter("robust-sorting");
        var interleaving = format.parameter("interleaving").isPresent()
                ? format.intParameter("interleaving", 0, 1, 1 << 20) : 0;
        var octet = format.flagParameter("octet-align") || crc || robust || interleaving > 0;
        return new AmrFormat(wideband, channels, octet, crc, robust, interleaving);
    }

    /** Returns the samples of one frame-block at the clock rate. */
    int frameDuration() {
        return wideband ? 320 : 160;
    }

    /** Returns whether a frame type may appear in a payload. */
    boolean validType(int type) {
        if (type == NO_DATA) {
            return true;
        }
        return wideband ? type <= 9 || type == SPEECH_LOST : type <= 8;
    }

    /** Returns the speech bits of a frame type. */
    int bits(int type) {
        return (wideband ? WB_BITS : NB_BITS)[type & 0x0F];
    }

    /** Returns the class A bits of a frame type. */
    int classA(int type) {
        return (wideband ? WB_CLASS_A : NB_CLASS_A)[type & 0x0F];
    }

    /**
     * Computes the RFC 4867 section 4.4.2.1 CRC over the first {@code bits} bits of a frame, MSB first.
     */
    static int crc(byte[] data, int offset, int bits) {
        var register = 0;
        for (var bit = 0; bit < bits; bit++) {
            var value = (data[offset + (bit >>> 3)] >>> (7 - (bit & 7))) & 1;
            var feedback = (register & 1) ^ value;
            register >>>= 1;
            if (feedback != 0) {
                register ^= 0b10111000;
            }
        }
        return register;
    }
}
