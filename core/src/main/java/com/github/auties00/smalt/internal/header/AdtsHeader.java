package com.github.auties00.smalt.internal.header;

/**
 * Holds the fields of an ADTS frame header (ISO/IEC 14496-3 clause 1.A.2.2 and ISO/IEC 13818-7
 * clause 6.2), the seven bytes before each AAC frame of an ADTS stream.
 *
 * {@snippet :
 * syncword 12 | ID 1 | layer 2 | protection_absent 1 | profile 2 | sampling_frequency_index 4
 * private 1 | channel_configuration 3 | original 1 | home 1 | copyright 2 | frame_length 13
 * buffer_fullness 11 | number_of_raw_data_blocks_in_frame 2
 * }
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param profile the profile, the AAC audio object type minus one, from 0 to 3
 * @param frequencyIndex the sampling frequency index, from 0 to 12
 * @param channelConfiguration the channel configuration, from 0 to 7, where 0 means a program
 * config element in the stream defines the channels
 * @param protectionAbsent whether the frame carries no CRC
 * @param frameLength the frame size in bytes, header included, or 0 for a header built from a
 * configuration
 * @param blocks the number of raw data blocks in the frame, from 1 to 4
 */
public record AdtsHeader(int profile, int frequencyIndex, int channelConfiguration, boolean protectionAbsent,
                         int frameLength, int blocks) {
    /**
     * The size of a header without its CRC words.
     */
    public static final int SIZE = 7;

    /**
     * The largest frame size the 13-bit length field can declare.
     */
    public static final int MAX_FRAME_LENGTH = 0x1FFF;

    /**
     * Parses the header at an offset.
     *
     * <p>A header is valid when it starts with the 12-bit syncword, has layer 0, a sampling
     * frequency index below 13 and a frame length larger than its own size, CRC words included.
     *
     * @param data the bytes
     * @param offset the index of the syncword
     * @return the header, or {@code null} when fewer than seven bytes follow {@code offset} or
     * they are not a valid header
     */
    public static AdtsHeader parse(byte[] data, int offset) {
        if (offset < 0 || offset + SIZE > data.length) {
            return null;
        }
        if ((data[offset] & 0xFF) != 0xFF || (data[offset + 1] & 0xF6) != 0xF0) {
            return null;
        }
        var frequency = (data[offset + 2] >> 2) & 0x0F;
        if (frequency > 12) {
            return null;
        }
        var profile = (data[offset + 2] & 0xFF) >> 6;
        var channels = ((data[offset + 2] & 1) << 2) | ((data[offset + 3] & 0xFF) >> 6);
        var protectionAbsent = (data[offset + 1] & 1) != 0;
        var length = ((data[offset + 3] & 3) << 11) | ((data[offset + 4] & 0xFF) << 3) | ((data[offset + 5] & 0xFF) >> 5);
        var blocks = (data[offset + 6] & 3) + 1;
        var header = new AdtsHeader(profile, frequency, channels, protectionAbsent, length, blocks);
        return length > header.headerSize() ? header : null;
    }

    /**
     * Returns the header fields an {@code AudioSpecificConfig} implies, for writing the frames of
     * a stream with {@link #write}.
     *
     * <p>An object type of 5 or 29 (explicit SBR or PS signaling) is replaced by the core object
     * type that follows it. The result describes a CRC-less frame of one raw data block whose
     * length is set at {@code write}.
     *
     * @param config the configuration
     * @return the header fields, with a frame length of 0
     * @throws IllegalArgumentException if {@code config} is {@code null} or truncated, uses an
     * explicit sampling rate or a reserved frequency index, codes an object type other than 1 to 4,
     * or has a channel configuration outside 1 to 7
     */
    public static AdtsHeader fromAudioSpecificConfig(byte[] config) {
        if (config == null) {
            throw new IllegalArgumentException("an AAC track needs its AudioSpecificConfig");
        }
        var bits = new FieldReader(config);
        var objectType = bits.objectType();
        var index = bits.read(4);
        if (index == 15) {
            throw new IllegalArgumentException("an explicit sampling rate cannot be expressed in ADTS");
        }
        var channels = bits.read(4);
        if (objectType == 5 || objectType == 29) {
            if (bits.read(4) == 15) {
                bits.read(24);
            }
            objectType = bits.objectType();
        }
        if (objectType < 1 || objectType > 4) {
            throw new IllegalArgumentException("AAC object type " + objectType + " cannot be expressed in ADTS");
        }
        if (index > 12) {
            throw new IllegalArgumentException("invalid sampling frequency index " + index);
        }
        if (channels == 0 || channels > 7) {
            throw new IllegalArgumentException("channel configuration " + channels + " cannot be expressed in ADTS");
        }
        return new AdtsHeader(objectType - 1, index, channels, true, 0, 1);
    }

    /**
     * {@return the header size in bytes: 7, plus the CRC words when the frame is protected}
     */
    public int headerSize() {
        if (protectionAbsent) {
            return SIZE;
        }
        return blocks == 1 ? SIZE + 2 : SIZE + 2 * blocks;
    }

    /**
     * {@return the sampling rate in hertz that the frequency index names}
     */
    public int sampleRate() {
        return AacRates.rate(frequencyIndex);
    }

    /**
     * Writes the header of a CRC-less frame of one raw data block, with these profile, frequency
     * and channel fields, an MPEG-4 ID and a buffer fullness of {@code 0x7FF} (variable rate).
     *
     * @param out the destination
     * @param offset the index of the first header byte; seven bytes are written
     * @param frameLength the frame size in bytes, header included
     * @throws IllegalArgumentException if {@code frameLength} is outside 7 to 8191
     */
    public void write(byte[] out, int offset, int frameLength) {
        if (frameLength < SIZE || frameLength > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("ADTS frame length " + frameLength + " is not in [7, 8191]");
        }
        out[offset] = (byte) 0xFF;
        out[offset + 1] = (byte) 0xF1;
        out[offset + 2] = (byte) ((profile << 6) | (frequencyIndex << 2) | (channelConfiguration >> 2));
        out[offset + 3] = (byte) (((channelConfiguration & 3) << 6) | (frameLength >> 11));
        out[offset + 4] = (byte) (frameLength >> 3);
        out[offset + 5] = (byte) (((frameLength & 7) << 5) | 0x1F);
        out[offset + 6] = (byte) 0xFC;
    }

    /**
     * Returns the two-byte {@code AudioSpecificConfig} these fields describe: the object type,
     * the frequency index and the channel configuration, with every GA flag clear.
     *
     * @return the configuration
     */
    public byte[] audioSpecificConfig() {
        var bits = ((profile + 1) << 11) | (frequencyIndex << 7) | (channelConfiguration << 3);
        return new byte[]{(byte) (bits >> 8), (byte) bits};
    }

    /**
     * Reads the fields of an {@code AudioSpecificConfig} most significant bit first.
     */
    private static final class FieldReader {
        /**
         * The configuration bytes.
         */
        private final byte[] data;

        /**
         * The index of the next bit.
         */
        private int position;

        /**
         * Constructs a reader at the first bit.
         *
         * @param data the configuration bytes
         */
        private FieldReader(byte[] data) {
            this.data = data;
        }

        /**
         * Reads an unsigned field.
         *
         * @param count the field width in bits, at most 24
         * @return the value
         * @throws IllegalArgumentException if the configuration ends inside the field
         */
        private int read(int count) {
            if (position + count > data.length * 8L) {
                throw new IllegalArgumentException("truncated AudioSpecificConfig");
            }
            var value = 0;
            for (var i = 0; i < count; i++, position++) {
                value = (value << 1) | ((data[position >> 3] >> (7 - (position & 7))) & 1);
            }
            return value;
        }

        /**
         * Reads an audio object type with its escape.
         *
         * @return the object type
         */
        private int objectType() {
            var type = read(5);
            return type == 31 ? 32 + read(6) : type;
        }
    }
}
