package com.github.auties00.smalt.internal.codec;

/**
 * Reads the frame structure of AMR-NB and AMR-WB in the RFC 4867 storage format and builds the
 * {@code AMRSpecificBox} ({@code damr}) of 3GPP TS 26.244 that the MP4 {@code samr} and
 * {@code sawb} sample entries carry.
 */
public final class AmrConfigs {
    /**
     * The payload size in bytes, without the header byte, of every AMR-NB frame type (TS 26.101
     * table 1 and RFC 4867 section 3.6).
     */
    private static final int[] NB_SIZES = {12, 13, 15, 17, 19, 20, 26, 31, 5, 6, 5, 5, 0, 0, 0, 0};

    /**
     * The payload size in bytes, without the header byte, of every AMR-WB frame type (TS 26.201
     * table 2 and RFC 4867 section 3.6).
     */
    private static final int[] WB_SIZES = {17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0};

    /**
     * The vendor code written in the {@code damr} box.
     */
    private static final byte[] VENDOR = {'s', 'm', 'l', 't'};

    /**
     * Prevents instantiation.
     */
    private AmrConfigs() {
        throw new AssertionError();
    }

    /**
     * Returns the payload size of a frame type.
     *
     * @param wideband whether the stream is AMR-WB
     * @param type     the 4-bit frame type
     * @return the bytes following the header byte
     */
    public static int payloadSize(boolean wideband, int type) {
        return (wideband ? WB_SIZES : NB_SIZES)[type & 0x0F];
    }

    /**
     * Returns the speech modes the frames of a sample use, one bit per frame type.
     *
     * <p>The sample holds storage-format frames back to back; comfort noise and no-data frames
     * contribute nothing, and parsing stops at a frame cut short.
     *
     * @param wideband whether the stream is AMR-WB
     * @param data     the array holding the sample
     * @param offset   the first byte of the sample
     * @param size     the sample size
     * @return the mode bits: bit {@code k} set when a frame of type {@code k} is present
     */
    public static int modes(boolean wideband, byte[] data, int offset, int size) {
        var speechTypes = wideband ? 9 : 8;
        var modes = 0;
        var position = offset;
        var end = offset + size;
        while (position < end) {
            var type = (data[position] >> 3) & 0x0F;
            if (type < speechTypes) {
                modes |= 1 << type;
            }
            position += 1 + payloadSize(wideband, type);
        }
        return modes;
    }

    /**
     * Builds the body of an {@code AMRSpecificBox}: vendor, decoder version 0, the mode set, no
     * mode change period restriction and one frame per sample.
     *
     * @param wideband whether the stream is AMR-WB
     * @param modes    the speech modes present, one bit per frame type, or {@code 0} when unknown,
     *                 which declares every mode of the codec
     * @return the 9-byte body
     */
    public static byte[] damr(boolean wideband, int modes) {
        var set = modes != 0 ? modes : wideband ? 0x01FF : 0x00FF;
        return new byte[]{VENDOR[0], VENDOR[1], VENDOR[2], VENDOR[3], 0, (byte) (set >> 8), (byte) set, 0, 1};
    }
}
