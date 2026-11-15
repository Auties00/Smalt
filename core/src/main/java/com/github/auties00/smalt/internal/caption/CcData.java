package com.github.auties00.smalt.internal.caption;

import com.github.auties00.smalt.internal.nal.NalUnits;

import java.io.ByteArrayOutputStream;

/**
 * Finds the ATSC A/53 {@code cc_data} constructs that video bitstreams carry: in H.264 and HEVC
 * {@code user_data_registered_itu_t_t35} SEI messages, in MPEG-1 and MPEG-2 video user data, and in AV1 ITU-T T.35
 * metadata OBUs, each as an ATSC {@code GA94} caption data structure.
 */
public final class CcData {
    /**
     * The {@code itu_t_t35_country_code} of the United States.
     */
    private static final int COUNTRY_US = 0xB5;

    /**
     * The {@code itu_t_t35_provider_code} of ATSC.
     */
    private static final int PROVIDER_ATSC = 0x0031;

    /**
     * The ATSC {@code user_identifier}, {@code "GA94"}.
     */
    private static final int GA94 = 0x47413934;

    /**
     * The {@code user_data_type_code} of {@code cc_data}.
     */
    private static final int CC_DATA = 0x03;

    private CcData() {
        throw new AssertionError();
    }

    /**
     * Appends the {@code cc_data} constructs of an ATSC {@code GA94} structure.
     *
     * <p>The structure starts at its {@code user_identifier}; a structure of another type, or without
     * {@code process_cc_data_flag}, adds nothing.
     *
     * @param data the bytes
     * @param offset the index of the {@code user_identifier}
     * @param end the index after the last byte of the structure
     * @param out receives the three-byte constructs
     */
    public static void ga94(byte[] data, int offset, int end, ByteArrayOutputStream out) {
        if (offset + 7 > end || readInt(data, offset) != GA94 || (data[offset + 4] & 0xFF) != CC_DATA) {
            return;
        }
        var flags = data[offset + 5] & 0xFF;
        if ((flags & 0x40) == 0) {
            return;
        }
        var count = flags & 0x1F;
        var start = offset + 7;
        var available = Math.min(count, (end - start) / 3);
        out.write(data, start, available * 3);
    }

    /**
     * Appends the {@code cc_data} constructs of an ITU-T T.35 payload: an SEI {@code user_data_registered_itu_t_t35}
     * message or an AV1 T.35 metadata OBU, from its country code on.
     *
     * @param data the bytes, without emulation prevention
     * @param offset the index of the {@code itu_t_t35_country_code}
     * @param end the index after the payload
     * @param out receives the three-byte constructs
     */
    public static void t35(byte[] data, int offset, int end, ByteArrayOutputStream out) {
        if (offset + 3 > end || (data[offset] & 0xFF) != COUNTRY_US) {
            return;
        }
        var provider = (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF);
        if (provider == PROVIDER_ATSC) {
            ga94(data, offset + 3, end, out);
        }
    }

    /**
     * Appends the {@code cc_data} constructs of the SEI messages of one SEI NAL unit.
     *
     * @param unit the NAL unit, header included, with emulation prevention
     * @param offset the index of the NAL unit header
     * @param length the length of the NAL unit
     * @param hevc whether the unit has the two-byte HEVC header
     * @param out receives the three-byte constructs
     */
    public static void sei(byte[] unit, int offset, int length, boolean hevc, ByteArrayOutputStream out) {
        var header = hevc ? 2 : 1;
        if (length <= header) {
            return;
        }
        var rbsp = NalUnits.unescape(unit, offset + header, length - header);
        var position = 0;
        while (position < rbsp.length && !(position == rbsp.length - 1 && (rbsp[position] & 0xFF) == 0x80)) {
            var type = 0;
            while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                type += 255;
                position++;
            }
            if (position >= rbsp.length) {
                return;
            }
            type += rbsp[position++] & 0xFF;
            var size = 0;
            while (position < rbsp.length && (rbsp[position] & 0xFF) == 0xFF) {
                size += 255;
                position++;
            }
            if (position >= rbsp.length) {
                return;
            }
            size += rbsp[position++] & 0xFF;
            var end = Math.min(rbsp.length, position + size);
            if (type == 4) {
                t35(rbsp, position, end, out);
            }
            if ((long) position + size >= rbsp.length) {
                return;
            }
            position += size;
        }
    }

    /**
     * Appends the {@code cc_data} constructs of the user data of an MPEG-1 or MPEG-2 video picture.
     *
     * @param data the elementary stream bytes of the picture
     * @param out receives the three-byte constructs
     */
    public static void mpegVideo(byte[] data, ByteArrayOutputStream out) {
        var position = NalUnits.findStartCode(data, 0, data.length);
        while (position >= 0 && position + 3 < data.length) {
            var code = data[position + 3] & 0xFF;
            var next = NalUnits.findStartCode(data, position + 4, data.length);
            var end = next < 0 ? data.length : next;
            if (code == 0xB2) {
                ga94(data, position + 4, end, out);
            }
            position = next;
        }
    }

    /**
     * Appends the {@code cc_data} constructs of the ITU-T T.35 metadata OBUs of an AV1 temporal unit whose OBUs have
     * size fields.
     *
     * @param data the temporal unit
     * @param out receives the three-byte constructs
     */
    public static void av1(byte[] data, ByteArrayOutputStream out) {
        var position = 0;
        while (position < data.length) {
            var header = data[position] & 0xFF;
            var type = (header >> 3) & 0xF;
            var extension = (header & 0x04) != 0;
            var hasSize = (header & 0x02) != 0;
            var at = position + 1 + (extension ? 1 : 0);
            long size;
            if (hasSize) {
                size = 0;
                var shift = 0;
                while (true) {
                    if (at >= data.length || shift > 56) {
                        return;
                    }
                    var value = data[at++] & 0xFF;
                    size |= (long) (value & 0x7F) << shift;
                    shift += 7;
                    if ((value & 0x80) == 0) {
                        break;
                    }
                }
            } else {
                size = data.length - at;
            }
            if (size < 0 || size > data.length - at) {
                return;
            }
            var end = at + (int) size;
            if (type == 5 && at < end) {
                var metadataType = 0L;
                var shift = 0;
                var cursor = at;
                while (cursor < end && shift <= 56) {
                    var value = data[cursor++] & 0xFF;
                    metadataType |= (long) (value & 0x7F) << shift;
                    shift += 7;
                    if ((value & 0x80) == 0) {
                        break;
                    }
                }
                if (metadataType == 4) {
                    t35(data, cursor, end, out);
                }
            }
            position = end;
        }
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
    }
}
