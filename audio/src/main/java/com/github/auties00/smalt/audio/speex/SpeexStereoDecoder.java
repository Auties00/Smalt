/* Ports libspeex 1.2.1 (libspeex/stereo.c). Copyright 2002-2008 Xiph.org Foundation, Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

import static com.github.auties00.smalt.audio.speex.SpeexFixed.mac16_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_16_p14;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.mult16_32_q15;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.pshr32;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.q16;
import static com.github.auties00.smalt.audio.speex.SpeexFixed.shl16;

/**
 * Expands a decoded mono frame to the intensity stereo the stream's in-band stereo requests describe.
 *
 * <p>Instances are not thread-safe.
 */
final class SpeexStereoDecoder {
    private static final short[] E_RATIO_QUANT = {8192, 10332, 13009, 16384};

    private static final short Q_0_98 = q16(0.98, 15);

    private static final short Q_0_02 = q16(0.02, 15);

    private int balance = 65536;

    private int energyRatio = 16384;

    private int smoothLeft = 16384;

    private int smoothRight = 16384;

    /**
     * Reads an in-band stereo request ({@code speex_std_stereo_request_handler}).
     */
    void request(SpeexBits bits) {
        var sign = bits.unsigned(1) != 0 ? -1 : 1;
        var dexp = bits.unsigned(5);
        balance = SpeexFixed.exp(mult16_16(sign, shl16(dexp, 9)));
        energyRatio = E_RATIO_QUANT[bits.unsigned(2)];
    }

    /**
     * Expands a mono frame in place to interleaved stereo ({@code speex_decode_stereo_int}).
     *
     * @param data holds the mono frame at {@code offset}, and receives {@code 2 * frameSize} interleaved samples
     */
    void expand(short[] data, int offset, int frameSize) {
        var right = (short) ((1 << 22) / SpeexFixed.sqrt(mult16_32_q15(energyRatio, (1 << 16) + balance)));
        var left = (short) (mult16_16(SpeexFixed.sqrt(balance), right) >> 8);
        for (var i = frameSize - 1; i >= 0; i--) {
            var tmp = data[offset + i];
            smoothLeft = (short) pshr32(mac16_16(mult16_16(smoothLeft, Q_0_98), left, Q_0_02), 15);
            smoothRight = (short) pshr32(mac16_16(mult16_16(smoothRight, Q_0_98), right, Q_0_02), 15);
            data[offset + 2 * i] = (short) mult16_16_p14(smoothLeft, tmp);
            data[offset + 2 * i + 1] = (short) mult16_16_p14(smoothRight, tmp);
        }
    }
}
