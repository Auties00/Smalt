package com.github.auties00.smalt.audio.g722;

import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Runs the digital test sequences of ITU-T G.722 Appendix II through the sub-band coders, which the sequences
 * test with the quadrature mirror filters bypassed: the encoder sequences must code to the reference codes, and the
 * reference codes must decode in the lower sub-band, in each of the three modes, and in the higher sub-band to the
 * reference reconstructed signals. A word with its lowest bit set resets both coders, as the sequences signal it.
 * The speech test file of the ITU-T Software Tool Library's G.722 checks the whole codec, quadrature mirror filters
 * included: the encoder must code it to the library's codes, and the codes must decode in each mode to the library's
 * output.
 */
class G722ConformanceTest {
    /** The test sequences check the codes and reconstructed signals bit for bit. */
    private static final Tolerance TOLERANCE = Tolerance.exact();

    private static int[] sequence(String name) {
        return G722Sequences.words(name);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"T1C1.XMT, T2R1.COD", "T1C2.XMT, T2R2.COD"})
    @DisplayName("codes the encoder sequences to the reference codes")
    void encoder(String input, String reference) {
        var x = sequence(input);
        var expected = sequence(reference);
        var low = new G722Band(true);
        var high = new G722Band(false);
        var codes = new int[x.length];
        for (var i = 0; i < x.length; i++) {
            if ((x[i] & 1) != 0) {
                low.reset();
                high.reset();
                codes[i] = 1;
                continue;
            }
            var xl = (short) x[i] >> 1;
            var il = low.encodeLow(xl);
            var ih = high.encodeHigh(xl);
            codes[i] = ((ih << 6) + il) << 8;
        }
        TOLERANCE.assertWithin("G.722 " + input + " codes", expected, codes);
    }

    @ParameterizedTest(name = "{0} mode {3}")
    @CsvSource({"T2R1.COD, T3L1.RC, T3H1.RC0, 1", "T2R1.COD, T3L1.RC, T3H1.RC0, 2", "T2R1.COD, T3L1.RC, T3H1.RC0, 3",
            "T2R2.COD, T3L2.RC, T3H2.RC0, 1", "T2R2.COD, T3L2.RC, T3H2.RC0, 2", "T2R2.COD, T3L2.RC, T3H2.RC0, 3",
            "T1D3.COD, T3L3.RC, T3H3.RC0, 1", "T1D3.COD, T3L3.RC, T3H3.RC0, 2", "T1D3.COD, T3L3.RC, T3H3.RC0, 3"})
    @DisplayName("decodes the reference codes to the reference sub-band signals")
    void decoder(String input, String lowPrefix, String highReference, int mode) {
        var codes = sequence(input);
        var expectedLow = sequence(lowPrefix + mode);
        var low = new G722Band(true);
        var lowOut = new int[codes.length];
        for (var i = 0; i < codes.length; i++) {
            if ((codes[i] & 1) != 0) {
                low.reset();
                lowOut[i] = 1;
            } else {
                lowOut[i] = (low.decodeLow((codes[i] >> 8) & 63, mode) << 1) & 0xFFFF;
            }
        }
        TOLERANCE.assertWithin("G.722 " + input + " lower sub-band, mode " + mode, expectedLow, lowOut);
        if (mode == 1) {
            var expectedHigh = sequence(highReference);
            var high = new G722Band(false);
            var highOut = new int[codes.length];
            for (var i = 0; i < codes.length; i++) {
                if ((codes[i] & 1) != 0) {
                    high.reset();
                    highOut[i] = 1;
                } else {
                    highOut[i] = (high.decodeHigh((codes[i] >> 14) & 3) << 1) & 0xFFFF;
                }
            }
            TOLERANCE.assertWithin("G.722 " + input + " higher sub-band", expectedHigh, highOut);
        }
    }

    /** Reads a little-endian 16-bit file of the ITU-T Software Tool Library's G.722 test data. */
    private static short[] stlWords(String name) {
        try {
            var bytes = java.nio.file.Files.readAllBytes(com.github.auties00.smalt.testkit.Conformance.vector(name));
            var out = new short[bytes.length / 2];
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
            return out;
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** The codes of {@code codspw.cod}, one per 16-bit word, which {@code g722demo -dec} reads. */
    private static byte[] stlCodes() {
        var words = stlWords("g722-stl-codspw.cod");
        var codes = new byte[words.length];
        for (var i = 0; i < words.length; i++) {
            org.junit.jupiter.api.Assertions.assertEquals(0, words[i] & 0xFF00, "code word " + i);
            codes[i] = (byte) words[i];
        }
        return codes;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("codes the Software Tool Library's speech file, quadrature mirror filter included, to its reference codes")
    void stlEncoder() throws java.io.IOException {
        // g722demo -enc inpsp.bin: the full 64 kbit/s encoder of the ITU-T STL (cbacae9), whose codes codspw.cod
        // holds one per word and codsp.cod packed two per big-endian word
        var input = stlWords("g722-stl-inpsp.bin");
        var planes = new int[1][input.length];
        for (var i = 0; i < input.length; i++) {
            planes[0][i] = input[i];
        }
        var packets = com.github.auties00.smalt.audio.telephony.TelephonyStreams.encode(new G722Encoder(), planes);
        var coded = new java.io.ByteArrayOutputStream();
        for (var packet : packets) {
            coded.write(packet.data());
        }
        var expected = stlCodes();
        var packed = java.nio.file.Files.readAllBytes(com.github.auties00.smalt.testkit.Conformance.vector(
                "g722-stl-codsp.cod"));
        org.junit.jupiter.api.Assertions.assertEquals(expected.length, packed.length);
        for (var i = 0; i < packed.length; i++) {
            org.junit.jupiter.api.Assertions.assertEquals(expected[i], packed[i ^ 1], "packed code " + i);
        }
        TOLERANCE.assertWithin("G.722 STL inpsp.bin codes", toInts(expected), toInts(coded.toByteArray()));
    }

    @ParameterizedTest(name = "mode {0}")
    @CsvSource({"1, 8", "2, 7", "3, 6"})
    @DisplayName("decodes the Software Tool Library's reference codes in each mode to its output, but for the last bit")
    void stlDecoder(int mode, int bitsPerSample) {
        // g722demo -dec -mode N codspw.cod: the receive filter of the STL keeps a 16th bit below the 15 bits of the
        // Recommendation's output, which this decoder outputs with its lowest bit clear
        var codes = stlCodes();
        var track = com.github.auties00.smalt.track.AudioTrack.builder().id(1)
                .codec(com.github.auties00.smalt.track.Codec.ADPCM_G722)
                .timeBase(com.github.auties00.smalt.track.Rational.timeBaseOf(16000)).sampleRate(16000)
                .layout(com.github.auties00.smalt.audio.ChannelLayout.MONO).bitsPerSample(bitsPerSample).build();
        var decoded = com.github.auties00.smalt.audio.telephony.TelephonyStreams.decodeShorts(new G722Decoder(track),
                java.util.List.of(new com.github.auties00.smalt.track.Packet(1, codes, 0, 0, 2 * codes.length, true)));
        var reference = stlWords("g722-stl-outsp" + mode + ".bin");
        var expected = new int[reference.length];
        for (var i = 0; i < reference.length; i++) {
            expected[i] = reference[i] & ~1;
        }
        var actual = new int[decoded.length];
        for (var i = 0; i < decoded.length; i++) {
            actual[i] = decoded[i];
        }
        TOLERANCE.assertWithin("G.722 STL outsp" + mode + ".bin", expected, actual);
    }

    private static int[] toInts(byte[] codes) {
        var out = new int[codes.length];
        for (var i = 0; i < codes.length; i++) {
            out[i] = codes[i] & 0xFF;
        }
        return out;
    }
}
