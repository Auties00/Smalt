package com.github.auties00.smalt.internal.header;

/**
 * Parses and validates a FLAC frame header, reusing one instance for every candidate.
 *
 * <p>A header (RFC 9639 section 9.1) is the 15-bit sync code {@code 0b111111111111100}, the
 * blocking strategy bit, 4-bit block size and sample rate codes, a 4-bit channel assignment, a
 * 3-bit sample size code, a reserved zero bit, the frame number (fixed blocking) or first sample
 * number (variable blocking) in the extended UTF-8 coding, the optional 8- or 16-bit uncommon
 * block size, the optional 8- or 16-bit uncommon sample rate, and a CRC-8 of all preceding header
 * bytes.
 *
 * <p>{@link #parse(byte[], int, int)} rejects every reserved or invalid code, a block size of
 * 65536 (which {@code STREAMINFO} cannot describe), a sample rate of zero, an over-long or
 * malformed number coding and a wrong CRC-8, so it doubles as the frame-sync validator of the
 * demuxer. Sample rate and sample size codes that defer to {@code STREAMINFO} are reported as
 * zero.
 */
public final class FlacFrameHeader {
    /**
     * The largest possible header size in bytes, CRC-8 included.
     */
    public static final int MAX_SIZE = 16;

    /**
     * The sample rates of the sample rate codes 1 to 11 (RFC 9639 section 9.1.2), indexed by
     * code; index zero defers to {@code STREAMINFO}.
     */
    private static final int[] SAMPLE_RATES = {0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100,
            48000, 96000};

    /**
     * The sample sizes of the sample size codes (RFC 9639 section 9.1.4), indexed by code; zero
     * defers to {@code STREAMINFO} and {@code -1} marks the reserved code.
     */
    private static final int[] SAMPLE_SIZES = {0, 8, 12, -1, 16, 20, 24, 32};

    /**
     * Whether the stream uses variable blocking, in which case {@link #number} is a sample
     * number.
     */
    private boolean variableBlocking;

    /**
     * The number of samples per channel in the frame.
     */
    private int blockSize;

    /**
     * The sampling rate in hertz, or zero when it is taken from {@code STREAMINFO}.
     */
    private int sampleRate;

    /**
     * The channel assignment code, from 0 to 10.
     */
    private int channelAssignment;

    /**
     * The sample size in bits, or zero when it is taken from {@code STREAMINFO}.
     */
    private int bitsPerSample;

    /**
     * The frame number or the first sample number.
     */
    private long number;

    /**
     * The header size in bytes, CRC-8 included.
     */
    private int size;

    /**
     * Parses the header starting at {@code data[offset]}.
     *
     * <p>On failure the fields hold unspecified values.
     *
     * @param data   the bytes
     * @param offset the index of the first header byte
     * @param limit  the index one past the last byte that may be read
     * @return {@code true} when the bytes form a valid header whose CRC-8 matches
     */
    public boolean parse(byte[] data, int offset, int limit) {
        if (limit - offset < 6 || data[offset] != (byte) 0xFF || (data[offset + 1] & 0xFE) != 0xF8) {
            return false;
        }
        variableBlocking = (data[offset + 1] & 1) != 0;
        var codes = data[offset + 2] & 0xFF;
        var blockCode = codes >>> 4;
        var rateCode = codes & 0x0F;
        var format = data[offset + 3] & 0xFF;
        channelAssignment = format >>> 4;
        var sizeCode = (format >>> 1) & 0x07;
        if (blockCode == 0 || rateCode == 15 || channelAssignment > 10 || sizeCode == 3 || (format & 1) != 0) {
            return false;
        }
        bitsPerSample = SAMPLE_SIZES[sizeCode];
        var position = offset + 4;
        var first = data[position++] & 0xFF;
        int extra;
        long value;
        if (first < 0x80) {
            extra = 0;
            value = first;
        } else if (first >= 0xC0 && first < 0xE0) {
            extra = 1;
            value = first & 0x1F;
        } else if (first >= 0xE0 && first < 0xF0) {
            extra = 2;
            value = first & 0x0F;
        } else if (first >= 0xF0 && first < 0xF8) {
            extra = 3;
            value = first & 0x07;
        } else if (first >= 0xF8 && first < 0xFC) {
            extra = 4;
            value = first & 0x03;
        } else if (first >= 0xFC && first < 0xFE) {
            extra = 5;
            value = first & 0x01;
        } else if (first == 0xFE) {
            extra = 6;
            value = 0;
        } else {
            return false;
        }
        if (!variableBlocking && extra > 5) {
            return false;
        }
        if (limit - position < extra + 1) {
            return false;
        }
        for (var i = 0; i < extra; i++) {
            var next = data[position++] & 0xFF;
            if ((next & 0xC0) != 0x80) {
                return false;
            }
            value = value << 6 | (next & 0x3F);
        }
        number = value;
        if (blockCode == 1) {
            blockSize = 192;
        } else if (blockCode <= 5) {
            blockSize = 576 << (blockCode - 2);
        } else if (blockCode == 6) {
            if (limit - position < 2) {
                return false;
            }
            blockSize = (data[position++] & 0xFF) + 1;
        } else if (blockCode == 7) {
            if (limit - position < 3) {
                return false;
            }
            blockSize = ((data[position] & 0xFF) << 8 | data[position + 1] & 0xFF) + 1;
            position += 2;
            if (blockSize > 65535) {
                return false;
            }
        } else {
            blockSize = 256 << (blockCode - 8);
        }
        if (rateCode <= 11) {
            sampleRate = SAMPLE_RATES[rateCode];
        } else {
            var length = rateCode == 12 ? 1 : 2;
            if (limit - position < length + 1) {
                return false;
            }
            var raw = length == 1 ? data[position] & 0xFF : (data[position] & 0xFF) << 8 | data[position + 1] & 0xFF;
            position += length;
            sampleRate = switch (rateCode) {
                case 12 -> raw * 1000;
                case 13 -> raw;
                default -> raw * 10;
            };
            if (sampleRate == 0) {
                return false;
            }
        }
        if (position >= limit) {
            return false;
        }
        if (Crc16.crc8(data, offset, position - offset) != (data[position] & 0xFF)) {
            return false;
        }
        size = position + 1 - offset;
        return true;
    }

    /**
     * Copies every field of another header into this one.
     *
     * @param other the header to copy
     */
    public void copyFrom(FlacFrameHeader other) {
        variableBlocking = other.variableBlocking;
        blockSize = other.blockSize;
        sampleRate = other.sampleRate;
        channelAssignment = other.channelAssignment;
        bitsPerSample = other.bitsPerSample;
        number = other.number;
        size = other.size;
    }

    /**
     * Returns whether the stream uses variable blocking.
     *
     * @return {@code true} when {@link #number()} is a sample number, {@code false} when it is a
     *         frame number
     */
    public boolean variableBlocking() {
        return variableBlocking;
    }

    /**
     * Returns the number of samples per channel in the frame.
     *
     * @return the block size, from 1 to 65535
     */
    public int blockSize() {
        return blockSize;
    }

    /**
     * Returns the sampling rate coded in the header.
     *
     * @return the rate in hertz, or {@code 0} when it is taken from {@code STREAMINFO}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the channel assignment code.
     *
     * @return 0 to 7 for 1 to 8 independent channels, 8 for left/side, 9 for side/right and 10
     *         for mid/side stereo
     */
    public int channelAssignment() {
        return channelAssignment;
    }

    /**
     * Returns the number of channels the channel assignment implies.
     *
     * @return the channel count, from 1 to 8
     */
    public int channels() {
        return channelAssignment < 8 ? channelAssignment + 1 : 2;
    }

    /**
     * Returns the sample size coded in the header.
     *
     * @return the size in bits, or {@code 0} when it is taken from {@code STREAMINFO}
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * Returns the coded frame or sample number.
     *
     * @return the frame number for fixed blocking, the number of the first sample for variable
     *         blocking
     */
    public long number() {
        return number;
    }

    /**
     * Returns the header size.
     *
     * @return the number of header bytes, CRC-8 included
     */
    public int size() {
        return size;
    }
}
