package com.github.auties00.smalt.aot.internal;

import java.io.ByteArrayOutputStream;

/**
 * Builds synthetic CineForm samples for the training workloads.
 *
 * <p>A sample is laid out as CineForm's tag and value pairs: the sample header, then for each channel a lowpass band
 * of 16-bit coefficients and the nine highpass bands of its three levels, each band coded with codeset 17 as one
 * band end code.
 */
final class SyntheticCfhd {
    /**
     * The band end code of codeset 17, 26 bits, left aligned in a 32-bit word.
     */
    private static final int END_CODE = 0x03114BA3 << 6;

    private SyntheticCfhd() {
        throw new AssertionError();
    }

    /**
     * Returns an intra frame of 4:2:2 Y'CbCr at 10 bits.
     *
     * @param width the frame width, a multiple of 16
     * @param height the frame height, a multiple of 8
     * @return the sample
     */
    static byte[] intra422(int width, int height) {
        var out = new ByteArrayOutputStream();
        int[] header = {1, 9, 10, 0, 11, 1, 12, 3, 84, 1, 13, 3, 14, 10, 16, 3, 20, width, 21, height, 70, 10, 83,
                0x2000};
        for (var i = 0; i < header.length; i += 2) {
            tag(out, header[i], header[i + 1]);
        }
        for (var channel = 0; channel < 3; channel++) {
            var channelWidth = channel == 0 ? width : width / 2;
            if (channel > 0) {
                tag(out, 1, 3);
                tag(out, 62, channel);
            }
            var lowWidth = channelWidth / 8;
            var lowHeight = height / 8;
            tag(out, 26, 3);
            tag(out, 27, lowWidth);
            tag(out, 28, lowHeight);
            tag(out, 33, 0);
            tag(out, 34, 1);
            tag(out, 35, 16);
            tag(out, 4, 0x0F0F);
            var samples = lowWidth * lowHeight;
            for (var i = 0; i < samples; i++) {
                var value = 2048 + (i * 97 % 8192) + channel * 1024;
                out.write(value >>> 8);
                out.write(value);
            }
            if (samples % 2 != 0) {
                out.write(0);
                out.write(0);
            }
            for (var index = 2; index >= 0; index--) {
                var bandWidth = channelWidth >> (index + 1);
                var bandHeight = height >> (index + 1);
                tag(out, 37, index == 0 ? 5 : 3);
                tag(out, 38, index + 1);
                tag(out, 39, index + 1);
                for (var band = 1; band <= 3; band++) {
                    tag(out, 48, band);
                    tag(out, 72, 1);
                    tag(out, 49, bandWidth);
                    tag(out, 50, bandHeight);
                    tag(out, 51, (2 - index) * 3 + band);
                    tag(out, 52, 3);
                    tag(out, 53, 4);
                    tag(out, 55, 0);
                    out.write(END_CODE >>> 24);
                    out.write(END_CODE >>> 16);
                    out.write(END_CODE >>> 8);
                    out.write(END_CODE);
                    tag(out, 56, 0);
                }
            }
        }
        return out.toByteArray();
    }

    /**
     * Writes one tag and value pair.
     *
     * @param out the sample being built
     * @param tag the CineForm tag number, negative for an optional tag
     * @param value the value, from 0 to 65535
     */
    private static void tag(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag >>> 8);
        out.write(tag);
        out.write(value >>> 8);
        out.write(value);
    }
}
