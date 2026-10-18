package com.github.auties00.smalt.internal.header;

/**
 * Decodes the four-byte header that starts every MPEG-1, MPEG-2 and MPEG-2.5 audio frame.
 *
 * <p>Headers are handled as a big-endian {@code int} whose most significant byte is the first
 * header byte, so parsing never allocates. The layout (ISO/IEC 11172-3 2.4.2.3, ISO/IEC 13818-3
 * 2.4.2.3) is: 11 sync bits, 2 version bits ({@code 11} MPEG-1, {@code 10} MPEG-2, {@code 00}
 * MPEG-2.5, {@code 01} reserved), 2 layer bits ({@code 11} Layer I, {@code 10} Layer II,
 * {@code 01} Layer III), the protection bit (zero when a CRC-16 follows the header), 4 bitrate
 * bits, 2 sample-rate bits, the padding bit, the private bit, 2 channel-mode bits, 2 mode
 * extension bits, the copyright and original bits and 2 emphasis bits.
 *
 * <p>MPEG-2.5 is accepted for Layer III only, the only layer it defines.
 *
 * @implNote This implementation follows the header predicates of minimp3 (CC0):
 * {@link #isValid(int)} is its {@code hdr_valid} and {@link #isCompatible(int, int)} its
 * {@code hdr_compare}.
 */
public final class MpegAudioHeader {
    /**
     * The size of a frame header in bytes.
     */
    public static final int SIZE = 4;

    /**
     * The channel mode value of single-channel frames.
     */
    public static final int MODE_MONO = 3;

    /**
     * The channel mode value of joint-stereo frames.
     */
    public static final int MODE_JOINT_STEREO = 1;

    /**
     * Holds half the bitrate in kbit/s, indexed by {@code [mpeg1][layer - 1][bitrate index]}
     * (ISO/IEC 11172-3 2.4.2.3, ISO/IEC 13818-3 2.4.2.3).
     */
    private static final int[][][] HALF_BITRATES = {
            {
                    {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80},
                    {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80},
                    {0, 16, 24, 28, 32, 40, 48, 56, 64, 72, 80, 88, 96, 112, 128}
            },
            {
                    {0, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160},
                    {0, 16, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192},
                    {0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224}
            }
    };

    /**
     * Holds the MPEG-1 sampling rates in hertz, indexed by the sample-rate bits.
     */
    private static final int[] MPEG1_RATES = {44100, 48000, 32000};

    /**
     * Prevents instantiation.
     */
    private MpegAudioHeader() {
        throw new AssertionError();
    }

    /**
     * Reads a header from four bytes.
     *
     * @param data   the bytes
     * @param offset the index of the first header byte; {@code offset + 4} must not exceed the
     *               array length
     * @return the header as a big-endian integer
     */
    public static int read(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Returns the second header byte.
     *
     * @param header the header
     * @return the byte holding the version, layer and protection bits
     */
    private static int byte1(int header) {
        return (header >>> 16) & 0xFF;
    }

    /**
     * Returns the third header byte.
     *
     * @param header the header
     * @return the byte holding the bitrate, sample rate, padding and private bits
     */
    private static int byte2(int header) {
        return (header >>> 8) & 0xFF;
    }

    /**
     * Returns whether a header is syntactically valid.
     *
     * <p>A valid header has the sync word, a defined version (MPEG-2.5 only with Layer III), a
     * defined layer, a bitrate index other than 15 and a sample-rate index other than 3.
     *
     * @param header the header
     * @return {@code true} when the header can start a frame
     */
    public static boolean isValid(int header) {
        var b1 = byte1(header);
        return (header >>> 24) == 0xFF
                && ((b1 & 0xF0) == 0xF0 || (b1 & 0xFE) == 0xE2)
                && layerBits(header) != 0
                && bitrateIndex(header) != 15
                && sampleRateIndex(header) != 3;
    }

    /**
     * Returns whether {@code candidate} is valid and belongs to the same stream as
     * {@code reference}: same version, layer and sample rate, and both free-format or both not.
     *
     * @param reference the header of a known frame
     * @param candidate the header to test
     * @return {@code true} when the candidate can follow the reference
     */
    public static boolean isCompatible(int reference, int candidate) {
        return isValid(candidate)
                && ((byte1(reference) ^ byte1(candidate)) & 0xFE) == 0
                && ((byte2(reference) ^ byte2(candidate)) & 0x0C) == 0
                && isFreeFormat(reference) == isFreeFormat(candidate);
    }

    /**
     * Returns the raw layer bits.
     *
     * @param header the header
     * @return {@code 3} for Layer I, {@code 2} for Layer II, {@code 1} for Layer III
     */
    private static int layerBits(int header) {
        return (byte1(header) >> 1) & 3;
    }

    /**
     * Returns the layer.
     *
     * @param header a valid header
     * @return 1, 2 or 3
     */
    public static int layer(int header) {
        return 4 - layerBits(header);
    }

    /**
     * Returns whether the header belongs to MPEG-1.
     *
     * @param header the header
     * @return {@code true} for MPEG-1, {@code false} for MPEG-2 and MPEG-2.5
     */
    public static boolean isMpeg1(int header) {
        return (byte1(header) & 0x08) != 0;
    }

    /**
     * Returns whether the header belongs to MPEG-2.5.
     *
     * @param header the header
     * @return {@code true} for MPEG-2.5
     */
    public static boolean isMpeg25(int header) {
        return (byte1(header) & 0x10) == 0;
    }

    /**
     * Returns whether a CRC-16 follows the header.
     *
     * @param header the header
     * @return {@code true} when the protection bit is zero
     */
    public static boolean hasCrc(int header) {
        return (byte1(header) & 1) == 0;
    }

    /**
     * Returns the bitrate index.
     *
     * @param header the header
     * @return the index, from 0 (free format) to 15 (invalid)
     */
    public static int bitrateIndex(int header) {
        return byte2(header) >> 4;
    }

    /**
     * Returns whether the frame uses free-format bitrate.
     *
     * @param header the header
     * @return {@code true} when the bitrate index is zero
     */
    public static boolean isFreeFormat(int header) {
        return bitrateIndex(header) == 0;
    }

    /**
     * Returns the sample-rate index.
     *
     * @param header the header
     * @return the index, from 0 to 3 (3 is invalid)
     */
    public static int sampleRateIndex(int header) {
        return (byte2(header) >> 2) & 3;
    }

    /**
     * Returns the sample-rate class used to select the scalefactor band tables.
     *
     * @param header a valid header
     * @return 0 to 2 for MPEG-2.5, 3 to 5 for MPEG-2, 6 to 8 for MPEG-1
     */
    public static int sampleRateClass(int header) {
        var b1 = byte1(header);
        return sampleRateIndex(header) + (((b1 >> 3) & 1) + ((b1 >> 4) & 1)) * 3;
    }

    /**
     * Returns whether the padding bit is set.
     *
     * @param header the header
     * @return {@code true} when the frame carries one padding slot
     */
    public static boolean isPadded(int header) {
        return (byte2(header) & 0x02) != 0;
    }

    /**
     * Returns the channel mode.
     *
     * @param header the header
     * @return 0 stereo, 1 joint stereo, 2 dual channel, 3 mono
     */
    public static int mode(int header) {
        return (header >> 6) & 3;
    }

    /**
     * Returns the mode extension bits.
     *
     * @param header the header
     * @return the two mode extension bits
     */
    public static int modeExtension(int header) {
        return (header >> 4) & 3;
    }

    /**
     * Returns whether the frame is single-channel.
     *
     * @param header the header
     * @return {@code true} for mono frames
     */
    public static boolean isMono(int header) {
        return (header & 0xC0) == 0xC0;
    }

    /**
     * Returns the number of channels.
     *
     * @param header the header
     * @return 1 or 2
     */
    public static int channels(int header) {
        return isMono(header) ? 1 : 2;
    }

    /**
     * Returns whether a Layer III frame uses mid/side stereo.
     *
     * @param header the header
     * @return {@code true} for joint stereo with the mid/side bit set
     */
    public static boolean isMsStereo(int header) {
        return (header & 0xE0) == 0x60;
    }

    /**
     * Returns whether a Layer III frame uses intensity stereo.
     *
     * @param header the header
     * @return {@code true} when the intensity bit of the mode extension is set
     */
    public static boolean isIntensityStereo(int header) {
        return (header & 0x10) != 0;
    }

    /**
     * Returns the bitrate.
     *
     * @param header a valid header
     * @return the bitrate in kbit/s, or 0 for free format
     */
    public static int bitrateKbps(int header) {
        return 2 * HALF_BITRATES[isMpeg1(header) ? 1 : 0][layerBits(header) - 1][bitrateIndex(header)];
    }

    /**
     * Returns the sampling rate.
     *
     * @param header a valid header
     * @return the rate in hertz, from 8000 to 48000
     */
    public static int sampleRate(int header) {
        var rate = MPEG1_RATES[sampleRateIndex(header)];
        if (!isMpeg1(header)) {
            rate >>= 1;
        }
        if (isMpeg25(header)) {
            rate >>= 1;
        }
        return rate;
    }

    /**
     * Returns the number of samples per channel a frame decodes to.
     *
     * @param header a valid header
     * @return 384 for Layer I, 576 for MPEG-2 and MPEG-2.5 Layer III, 1152 otherwise
     */
    public static int samplesPerFrame(int header) {
        var b1 = byte1(header);
        if ((b1 & 6) == 6) {
            return 384;
        }
        return (b1 & 14) == 2 ? 576 : 1152;
    }

    /**
     * Returns the size of the padding slot the padding bit adds.
     *
     * @param header a valid header
     * @return 4 for padded Layer I frames, 1 for other padded frames, 0 otherwise
     */
    public static int padding(int header) {
        if (!isPadded(header)) {
            return 0;
        }
        return layer(header) == 1 ? 4 : 1;
    }

    /**
     * Returns the frame size without the padding slot.
     *
     * @param header          a valid header
     * @param freeFormatBytes the size of free-format frames of this stream, or 0 when unknown
     * @return the size in bytes including the header, or {@code freeFormatBytes} for a
     *         free-format frame
     */
    public static int frameBytes(int header, int freeFormatBytes) {
        var bytes = samplesPerFrame(header) * bitrateKbps(header) * 125 / sampleRate(header);
        if (layer(header) == 1) {
            bytes &= ~3;
        }
        return bytes != 0 ? bytes : freeFormatBytes;
    }

    /**
     * Returns the size of the Layer III side information that follows the header and CRC.
     *
     * @param header a valid Layer III header
     * @return 32 or 17 bytes for MPEG-1 stereo or mono, 17 or 9 bytes otherwise
     */
    public static int sideInfoBytes(int header) {
        if (isMpeg1(header)) {
            return isMono(header) ? 17 : 32;
        }
        return isMono(header) ? 9 : 17;
    }
}
