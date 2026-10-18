package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.video.intra.internal.IntraBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the bit layout of the lossless alpha codewords of SMPTE ST 2019-1 clause 7.3.1.2 on a
 * macroblock whose first codeword is a run of six samples.
 */
@DisplayName("VC-3 lossless alpha")
class Vc3LosslessAlphaTest {
    @Test
    @DisplayName("writes the Elias gamma remainder most significant bit first, as Figures 36 and 28 lay it out")
    void gammaRemainderOrder() {
        // the first six samples of the alpha zig-zag (row 0, left to right) ramp 516, 521, ..., 541 and the
        // rest are 700: the first codeword adds 5 to the previous sample, starting from 511, over a run of
        // 6 = 2^2 + 2
        var plane = new short[16 * 16];
        Arrays.fill(plane, (short) 700);
        for (var i = 0; i < 6; i++) {
            plane[i] = (short) (516 + 5 * i);
        }
        var writer = new IntraBitWriter(1024);
        new Vc3LosslessAlpha().code(plane, 16, 0, 0, 1, 0, 10, writer);
        writer.alignToByte();
        var bytes = writer.toByteArray();
        // header 0001 (RL set), P = 0, gamma 00 1 10: the byte 0001 0001, then the remainder bits 1, 0
        assertEquals(0x11, bytes[0] & 0xFF);
        assertEquals(0b10, (bytes[1] & 0xFF) >>> 6);
    }
}
