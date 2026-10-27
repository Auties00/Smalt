package com.github.auties00.smalt.video.h264.encoder.internal;

import java.io.ByteArrayOutputStream;

/**
 * Builds the SEI NAL unit that carries a frame's closed captions: {@code user_data_registered_itu_t_t35}
 * messages, each an ATSC A/72 {@code GA94} caption data structure of at most 31 {@code cc_data} constructs,
 * which the engines place before the first slice of the frame's access unit.
 */
final class CaptionSei {
    /**
     * The most {@code cc_data} constructs one caption data structure holds ({@code cc_count} has five bits).
     */
    private static final int MAX_CONSTRUCTS = 31;

    private CaptionSei() {
        throw new AssertionError();
    }

    /**
     * Builds the SEI NAL unit of a frame's captions, header included, with emulation prevention.
     *
     * @param ccData the {@code cc_data} constructs, three bytes each, or {@code null} for none
     * @return the escaped NAL unit, or {@code null} when {@code ccData} holds no construct
     */
    static byte[] unit(byte[] ccData) {
        var count = ccData == null ? 0 : ccData.length / 3;
        if (count == 0) {
            return null;
        }
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
}
