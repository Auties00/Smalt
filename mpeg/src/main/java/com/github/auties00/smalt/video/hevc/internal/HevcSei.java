package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;

/**
 * Collects the supplemental enhancement information messages the decoder uses (ITU-T H.265 D.2):
 * the decoded picture hash (D.3.19), the mastering display color volume (D.3.28), the content light
 * level information (D.3.35), the alternative transfer characteristics (D.3.38) and the recovery
 * point (D.3.8).
 *
 * <p>A message too short for its syntax is ignored; every other message is skipped.
 */
public final class HevcSei {
    /**
     * The decoded picture hash type of an MD5 hash.
     */
    public static final int HASH_MD5 = 0;

    /**
     * The decoded picture hash type of a CRC.
     */
    public static final int HASH_CRC = 1;

    /**
     * The decoded picture hash type of a checksum.
     */
    public static final int HASH_CHECKSUM = 2;

    /**
     * The mastering display of the last message that carried one, or {@code null}.
     */
    public MasteringDisplay masteringDisplay;

    /**
     * The content light level of the last message that carried one, or {@code null}.
     */
    public ContentLightLevel contentLightLevel;

    /**
     * The {@code preferred_transfer_characteristics} of the last alternative transfer
     * characteristics message, or -1.
     */
    public int preferredTransfer = -1;

    /**
     * The hash type of the last decoded picture hash message, or -1 when none is pending.
     */
    public int hashType = -1;

    /**
     * The hash of each color component of the last decoded picture hash message: 16 bytes for
     * MD5, the value in the first 2 (CRC) or 4 (checksum) bytes big-endian otherwise.
     */
    public final byte[][] hash = new byte[3][16];

    /**
     * Constructs an empty collector.
     */
    public HevcSei() {
    }

    /**
     * Parses the messages of an SEI RBSP.
     *
     * @param rbsp the unescaped payload buffer
     * @param offset the index of the first byte after the NAL unit header
     * @param end the index one past the last payload byte
     * @param suffix whether the NAL unit is a suffix SEI NAL unit
     * @param components the number of color components of the active sequence, 1 or 3, which sizes
     * the decoded picture hash
     */
    public void parse(byte[] rbsp, int offset, int end, boolean suffix, int components) {
        var position = offset;
        while (position < end - 1) {
            var payloadType = 0;
            while (position < end && (rbsp[position] & 0xFF) == 0xFF) {
                payloadType += 255;
                position++;
            }
            if (position >= end) {
                return;
            }
            payloadType += rbsp[position++] & 0xFF;
            var payloadSize = 0;
            while (position < end && (rbsp[position] & 0xFF) == 0xFF) {
                payloadSize += 255;
                position++;
            }
            if (position >= end) {
                return;
            }
            payloadSize += rbsp[position++] & 0xFF;
            if (payloadSize > end - position) {
                return;
            }
            if (suffix) {
                if (payloadType == 132) {
                    parseHash(rbsp, position, payloadSize, components);
                }
            } else {
                switch (payloadType) {
                    // TODO: keep recovery point messages (payload type 6) for random access from
                    // non-IRAP pictures
                    case 132 -> parseHash(rbsp, position, payloadSize, components);
                    case 137 -> {
                        if (payloadSize >= 24) {
                            masteringDisplay = parseMasteringDisplay(rbsp, position);
                        }
                    }
                    case 144 -> {
                        if (payloadSize >= 4) {
                            contentLightLevel = new ContentLightLevel(u16(rbsp, position), u16(rbsp, position + 2));
                        }
                    }
                    case 147 -> {
                        if (payloadSize >= 1) {
                            preferredTransfer = rbsp[position] & 0xFF;
                        }
                    }
                    default -> {
                    }
                }
            }
            position += payloadSize;
        }
    }

    /**
     * Parses a decoded picture hash message.
     *
     * @param rbsp the payload buffer
     * @param position the index of the message payload
     * @param size the message payload size
     * @param components the number of color components
     */
    private void parseHash(byte[] rbsp, int position, int size, int components) {
        var type = rbsp[position] & 0xFF;
        var length = switch (type) {
            case HASH_MD5 -> 16;
            case HASH_CRC -> 2;
            case HASH_CHECKSUM -> 4;
            default -> -1;
        };
        if (length < 0 || size < 1 + length * components) {
            return;
        }
        for (var c = 0; c < components; c++) {
            System.arraycopy(rbsp, position + 1 + c * length, hash[c], 0, length);
        }
        hashType = type;
    }

    /**
     * Parses the payload of a mastering display color volume message, whose primaries are stored
     * in the order green, blue, red, in units of 0.00002, and whose luminances are in units of
     * 0.0001 candelas per square meter.
     *
     * @param rbsp the payload buffer
     * @param position the index of the message payload
     * @return the mastering display
     */
    private static MasteringDisplay parseMasteringDisplay(byte[] rbsp, int position) {
        var green = new Chromaticity(u16(rbsp, position) * 0.00002, u16(rbsp, position + 2) * 0.00002);
        var blue = new Chromaticity(u16(rbsp, position + 4) * 0.00002, u16(rbsp, position + 6) * 0.00002);
        var red = new Chromaticity(u16(rbsp, position + 8) * 0.00002, u16(rbsp, position + 10) * 0.00002);
        var white = new Chromaticity(u16(rbsp, position + 12) * 0.00002, u16(rbsp, position + 14) * 0.00002);
        var max = u32(rbsp, position + 16) * 0.0001;
        var min = u32(rbsp, position + 20) * 0.0001;
        return new MasteringDisplay(red, green, blue, white, max, min);
    }

    /**
     * Reads a big-endian unsigned 16-bit value.
     *
     * @param data the buffer
     * @param position the index of the first byte
     * @return the value
     */
    private static int u16(byte[] data, int position) {
        return ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
    }

    /**
     * Reads a big-endian unsigned 32-bit value.
     *
     * @param data the buffer
     * @param position the index of the first byte
     * @return the value
     */
    private static long u32(byte[] data, int position) {
        return ((long) u16(data, position) << 16) | u16(data, position + 2);
    }
}
