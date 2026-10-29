package com.github.auties00.smalt.internal.caption;

import java.io.ByteArrayOutputStream;

/**
 * Converts the samples of QuickTime closed caption tracks to and from {@code cc_data} constructs.
 *
 * <p>A {@code c608} sample holds a {@code cdat} atom with the byte pairs of field 1 and, when field 2 has any, a
 * {@code cdt2} atom with those of field 2. A {@code c708} sample holds a {@code ccdp} atom with a SMPTE ST 334-2
 * caption distribution packet, whose {@code ccdata_section} lists {@code cc_data} constructs.
 */
public final class QuickTimeCaptions {
    private static final int CDAT = 0x63646174;
    private static final int CDT2 = 0x63647432;
    private static final int CCDP = 0x63636470;

    /**
     * The {@code cdp_identifier} of a caption distribution packet.
     */
    private static final int CDP_IDENTIFIER = 0x9669;

    private QuickTimeCaptions() {
        throw new AssertionError();
    }

    /**
     * Converts a {@code c608} or {@code c708} sample to {@code cc_data} constructs.
     *
     * <p>Atoms of other types and truncated atoms are skipped.
     *
     * @param sample the sample data, a sequence of atoms
     * @return the constructs, three bytes each
     */
    public static byte[] toCcData(byte[] sample) {
        var out = new ByteArrayOutputStream();
        var position = 0;
        while (position + 8 <= sample.length) {
            var size = readInt(sample, position);
            var type = readInt(sample, position + 4);
            if (size < 8 || size > sample.length - position) {
                break;
            }
            var body = position + 8;
            var end = position + size;
            switch (type) {
                case CDAT, CDT2 -> {
                    var header = type == CDAT ? 0xFC : 0xFD;
                    for (var at = body; at + 2 <= end; at += 2) {
                        out.write(header);
                        out.write(sample[at]);
                        out.write(sample[at + 1]);
                    }
                }
                case CCDP -> cdp(sample, body, end, out);
                default -> {
                }
            }
            position = end;
        }
        return out.toByteArray();
    }

    /**
     * Reads the {@code cc_data} constructs of a caption distribution packet.
     */
    private static void cdp(byte[] data, int start, int end, ByteArrayOutputStream out) {
        if (start + 7 > end || ((data[start] & 0xFF) << 8 | (data[start + 1] & 0xFF)) != CDP_IDENTIFIER) {
            return;
        }
        var flags = data[start + 4] & 0xFF;
        var position = start + 7;
        if ((flags & 0x80) != 0) {
            position += 5;
        }
        if ((flags & 0x40) == 0 || position + 2 > end || (data[position] & 0xFF) != 0x72) {
            return;
        }
        var count = data[position + 1] & 0x1F;
        position += 2;
        var available = Math.min(count, (end - position) / 3);
        out.write(data, position, available * 3);
    }

    /**
     * Converts {@code cc_data} constructs to a {@code c608} sample: the valid field 1 pairs in a {@code cdat} atom and
     * the valid field 2 pairs in a {@code cdt2} atom.
     *
     * @param ccData the constructs, three bytes each
     * @return the sample, empty when no pair of either field is valid
     */
    public static byte[] toC608(byte[] ccData) {
        var field1 = new ByteArrayOutputStream();
        var field2 = new ByteArrayOutputStream();
        for (var index = 0; index + 3 <= ccData.length; index += 3) {
            var header = ccData[index] & 0xFF;
            if ((header & 0x04) == 0) {
                continue;
            }
            switch (header & 3) {
                case 0 -> field1.write(ccData, index + 1, 2);
                case 1 -> field2.write(ccData, index + 1, 2);
                default -> {
                }
            }
        }
        var out = new ByteArrayOutputStream();
        atom(out, CDAT, field1.toByteArray());
        atom(out, CDT2, field2.toByteArray());
        return out.toByteArray();
    }

    /**
     * Converts {@code cc_data} constructs to a {@code c708} sample: a {@code ccdp} atom holding a caption
     * distribution packet without time code or service information.
     *
     * @param ccData the constructs, three bytes each, at most 31
     * @param frameRateCode the {@code cdp_frame_rate} of SMPTE ST 334-2, 1 to 8
     * @param sequence the {@code cdp_hdr_sequence_cntr}
     * @return the sample
     */
    public static byte[] toC708(byte[] ccData, int frameRateCode, int sequence) {
        var count = Math.min(ccData.length / 3, 31);
        var length = 7 + 2 + count * 3 + 4;
        var cdp = new byte[length];
        cdp[0] = (byte) (CDP_IDENTIFIER >> 8);
        cdp[1] = (byte) CDP_IDENTIFIER;
        cdp[2] = (byte) length;
        cdp[3] = (byte) (frameRateCode << 4 | 0x0F);
        cdp[4] = 0x43;
        cdp[5] = (byte) (sequence >> 8);
        cdp[6] = (byte) sequence;
        cdp[7] = 0x72;
        cdp[8] = (byte) (0xE0 | count);
        System.arraycopy(ccData, 0, cdp, 9, count * 3);
        var footer = 9 + count * 3;
        cdp[footer] = 0x74;
        cdp[footer + 1] = (byte) (sequence >> 8);
        cdp[footer + 2] = (byte) sequence;
        var sum = 0;
        for (var index = 0; index < length - 1; index++) {
            sum += cdp[index] & 0xFF;
        }
        cdp[length - 1] = (byte) (-sum);
        var out = new ByteArrayOutputStream();
        atom(out, CCDP, cdp);
        return out.toByteArray();
    }

    /**
     * Returns the {@code cdp_frame_rate} code of a frame rate.
     *
     * @param numerator the frame rate numerator
     * @param denominator the frame rate denominator
     * @return the code, 4 (30000/1001) for a rate the table does not list
     */
    public static int frameRateCode(long numerator, long denominator) {
        if (numerator <= 0 || denominator <= 0) {
            return 4;
        }
        var millis = numerator * 1000 / denominator;
        if (millis >= 23970 && millis <= 23980) {
            return 1;
        }
        return switch ((int) Math.min(millis, Integer.MAX_VALUE)) {
            case 24000 -> 2;
            case 25000 -> 3;
            case 29970 -> 4;
            case 30000 -> 5;
            case 50000 -> 6;
            case 59940 -> 7;
            case 60000 -> 8;
            default -> 4;
        };
    }

    private static void atom(ByteArrayOutputStream out, int type, byte[] body) {
        if (body.length == 0) {
            return;
        }
        var size = body.length + 8;
        out.write(size >>> 24);
        out.write(size >>> 16);
        out.write(size >>> 8);
        out.write(size);
        out.write(type >>> 24);
        out.write(type >>> 16);
        out.write(type >>> 8);
        out.write(type);
        out.write(body, 0, body.length);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
    }
}
