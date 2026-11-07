package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.audio.dts.internal.CoreStreamWriter;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the four packings of a DTS core stream (16-bit or 14-bit words, big or little endian, as DTS audio
 * CDs store them) decode to the samples of the plain stream.
 */
final class DtsPackingTest {
    private static List<byte[]> frames() {
        var frames = new ArrayList<byte[]>();
        for (var f = 0; f < 4; f++) {
            var channels = new CoreStreamWriter.Channels(2);
            var frame = new CoreStreamWriter.Frame(channels);
            frame.amode = 2;
            frame.sfreq = 8;
            var subframe = new CoreStreamWriter.Subframe(2);
            for (var ch = 0; ch < 2; ch++) {
                for (var n = 0; n < 32; n++) {
                    subframe.allocation[ch][n] = 11;
                    subframe.scaleIndex[ch][n][0] = 30 + n;
                    for (var m = 0; m < 16; m++) {
                        subframe.codes[ch][n][m] = ((m * 37 + n * 11 + ch * 5 + f) % 41) - 20;
                    }
                }
            }
            frame.subframes.add(subframe);
            frames.add(CoreStreamWriter.write(frame));
        }
        return frames;
    }

    /**
     * Packs a plain frame into 14 bits per 16-bit word, the two top bits copying bit 13.
     */
    private static byte[] pack14(byte[] plain, boolean little) {
        var words = (plain.length * 8 + 13) / 14;
        var out = new byte[words * 2];
        long accumulator = 0;
        var bits = 0;
        var input = 0;
        for (var w = 0; w < words; w++) {
            while (bits < 14) {
                accumulator = accumulator << 8 | (input < plain.length ? plain[input] & 0xFF : 0);
                input++;
                bits += 8;
            }
            bits -= 14;
            var value = (int) (accumulator >>> bits) & 0x3FFF;
            if ((value & 0x2000) != 0) {
                value |= 0xC000;
            }
            out[2 * w + (little ? 1 : 0)] = (byte) (value >>> 8);
            out[2 * w + (little ? 0 : 1)] = (byte) value;
        }
        return out;
    }

    private static byte[] swap(byte[] plain) {
        var out = plain.clone();
        for (var i = 0; i + 1 < out.length; i += 2) {
            out[i] = plain[i + 1];
            out[i + 1] = plain[i];
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"16-bit little-endian", "14-bit big-endian", "14-bit little-endian"})
    @DisplayName("every packing decodes to the samples of the plain stream")
    void packing(String packing) {
        var plain = frames();
        var packed = new ArrayList<byte[]>();
        for (var frame : plain) {
            packed.add(switch (packing) {
                case "16-bit little-endian" -> swap(frame);
                case "14-bit big-endian" -> pack14(frame, false);
                default -> pack14(frame, true);
            });
        }
        var expected = DtsTestSupport.decodeUnits(plain, DtsDecoderOptions.defaults());
        var actual = DtsTestSupport.decodeUnits(packed, DtsDecoderOptions.defaults());
        assertEquals(expected.channels(), actual.channels());
        assertEquals(44100, actual.sampleRate());
        Tolerance.exact().assertPcm(packing, expected.samples(), actual.samples());
    }
}
