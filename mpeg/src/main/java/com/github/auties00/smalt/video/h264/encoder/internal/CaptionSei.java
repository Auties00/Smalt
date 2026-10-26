package com.github.auties00.smalt.video.h264.encoder.internal;

import java.io.ByteArrayOutputStream;

/**
 * Writes closed captions into H.264 access units: an SEI NAL unit of {@code user_data_registered_itu_t_t35}
 * messages, each an ATSC A/72 {@code GA94} caption data structure of at most 31 {@code cc_data} constructs, placed
 * before the first slice of the access unit.
 */
public final class CaptionSei {
    /**
     * The most {@code cc_data} constructs one caption data structure holds ({@code cc_count} has five bits).
     */
    private static final int MAX_CONSTRUCTS = 31;

    private CaptionSei() {
        throw new AssertionError();
    }

    /**
     * Returns an access unit with captions added.
     *
     * @param accessUnit the access unit, four-byte length-prefixed or Annex B with four-byte start codes
     * @param annexB whether the access unit is in the Annex B format
     * @param ccData the {@code cc_data} constructs, three bytes each
     * @return a new access unit, or {@code accessUnit} itself when {@code ccData} holds no construct or the access
     * unit has no slice
     */
    public static byte[] insert(byte[] accessUnit, boolean annexB, byte[] ccData) {
        var count = ccData.length / 3;
        if (count == 0) {
            return accessUnit;
        }
        var position = 0;
        var at = -1;
        while (position + 4 < accessUnit.length) {
            var length = annexB ? nextStart(accessUnit, position + 4) - position - 4
                    : (accessUnit[position] & 0xFF) << 24 | (accessUnit[position + 1] & 0xFF) << 16
                    | (accessUnit[position + 2] & 0xFF) << 8 | (accessUnit[position + 3] & 0xFF);
            if (length <= 0 || position + 4 + length > accessUnit.length) {
                break;
            }
            var type = accessUnit[position + 4] & 0x1F;
            if (type >= 1 && type <= 5) {
                at = position;
                break;
            }
            position += 4 + length;
        }
        if (at < 0) {
            return accessUnit;
        }
        var unit = unit(ccData, count);
        var out = new ByteArrayOutputStream(accessUnit.length + unit.length + 4);
        out.write(accessUnit, 0, at);
        if (annexB) {
            out.writeBytes(new byte[]{0, 0, 0, 1});
        } else {
            out.writeBytes(new byte[]{(byte) (unit.length >>> 24), (byte) (unit.length >>> 16),
                    (byte) (unit.length >>> 8), (byte) unit.length});
        }
        out.write(unit, 0, unit.length);
        out.write(accessUnit, at, accessUnit.length - at);
        return out.toByteArray();
    }

    /**
     * Builds the SEI NAL unit, header included, with emulation prevention.
     */
    private static byte[] unit(byte[] ccData, int count) {
        var rbsp = new ByteArrayOutputStream();
        for (var first = 0; first < count; first += MAX_CONSTRUCTS) {
            var chunk = Math.min(MAX_CONSTRUCTS, count - first);
            var size = 10 + chunk * 3 + 1;
            rbsp.write(4);
            for (var value = size; ; value -= 255) {
                if (value < 255) {
                    rbsp.write(value);
                    break;
                }
                rbsp.write(255);
            }
            rbsp.writeBytes(new byte[]{(byte) 0xB5, 0x00, 0x31, 'G', 'A', '9', '4', 0x03, (byte) (0xC0 | chunk),
                    (byte) 0xFF});
            rbsp.write(ccData, first * 3, chunk * 3);
            rbsp.write(0xFF);
        }
        rbsp.write(0x80);
        var payload = rbsp.toByteArray();
        var out = new ByteArrayOutputStream(payload.length + 8);
        out.write(0x06);
        var zeros = 0;
        for (var value : payload) {
            if (zeros >= 2 && (value & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /**
     * Returns the index of the next four-byte start code at or after a position, or the length of the data.
     */
    private static int nextStart(byte[] data, int from) {
        for (var index = from; index + 3 < data.length; index++) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 0 && data[index + 3] == 1) {
                return index;
            }
        }
        return data.length;
    }
}
