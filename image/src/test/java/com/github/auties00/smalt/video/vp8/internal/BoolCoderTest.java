package com.github.auties00.smalt.video.vp8.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips random symbol sequences through the boolean encoder and decoder, and checks the
 * decoder's specialized readers against their definitions in terms of {@link BoolDecoder#readBool}.
 */
@DisplayName("VP8 boolean entropy coder")
class BoolCoderTest {
    static byte[] finish(BoolEncoder encoder) {
        encoder.finish();
        return Arrays.copyOf(encoder.buffer(), encoder.size());
    }

    @ParameterizedTest(name = "{0} symbols")
    @ValueSource(ints = {0, 1, 7, 64, 1000, 100_000})
    @DisplayName("decodes every boolean the encoder wrote, for any probability")
    void roundTrip(int count) {
        var random = new Random(count);
        var bits = new int[count];
        var probs = new int[count];
        var encoder = new BoolEncoder(16);
        for (var i = 0; i < count; i++) {
            probs[i] = switch (random.nextInt(4)) {
                case 0 -> 1;
                case 1 -> 255;
                default -> 1 + random.nextInt(255);
            };
            bits[i] = random.nextInt(256) >= probs[i] ? 1 : 0;
            if (random.nextInt(20) == 0) {
                bits[i] ^= 1;
            }
            encoder.putBit(bits[i], probs[i]);
        }
        var data = finish(encoder);
        var decoder = new BoolDecoder();
        decoder.init(data, 0, data.length);
        for (var i = 0; i < count; i++) {
            assertEquals(bits[i], decoder.readBool(probs[i]), "symbol " + i);
        }
        assertFalse(decoder.isEof());
    }

    @Test
    @DisplayName("literals, uniform bits and signed values round-trip")
    void literals() {
        var random = new Random(2);
        var values = new int[5000];
        var widths = new int[values.length];
        var encoder = new BoolEncoder(16);
        for (var i = 0; i < values.length; i++) {
            widths[i] = 1 + random.nextInt(24);
            values[i] = random.nextInt(1 << widths[i]);
            encoder.putBits(values[i], widths[i]);
        }
        var data = finish(encoder);
        var decoder = new BoolDecoder();
        decoder.init(data, 0, data.length);
        for (var i = 0; i < values.length; i++) {
            assertEquals(values[i], decoder.readLiteral(widths[i]), "literal " + i);
        }
    }

    @Test
    @DisplayName("tree decoding follows the coded path to a leaf")
    void tree() {
        int[] tree = {-0, 2, -1, 4, -2, -3};
        int[] probs = {200, 30, 128};
        var random = new Random(4);
        var symbols = new int[3000];
        var encoder = new BoolEncoder(16);
        for (var i = 0; i < symbols.length; i++) {
            symbols[i] = random.nextInt(4);
            switch (symbols[i]) {
                case 0 -> encoder.putBit(0, probs[0]);
                case 1 -> {
                    encoder.putBit(1, probs[0]);
                    encoder.putBit(0, probs[1]);
                }
                default -> {
                    encoder.putBit(1, probs[0]);
                    encoder.putBit(1, probs[1]);
                    encoder.putBit(symbols[i] - 2, probs[2]);
                }
            }
        }
        var data = finish(encoder);
        var decoder = new BoolDecoder();
        decoder.init(data, 0, data.length);
        for (var i = 0; i < symbols.length; i++) {
            assertEquals(symbols[i], decoder.readTree(tree, probs, 0), "symbol " + i);
        }
    }

    @Test
    @DisplayName("reading past the end yields zeros and raises the end-of-data flag")
    void endOfData() {
        var decoder = new BoolDecoder();
        decoder.init(new byte[]{(byte) 0xA5}, 0, 1);
        for (var i = 0; i < 64; i++) {
            decoder.readBit();
        }
        assertTrue(decoder.isEof());
        decoder.init(new byte[0], 0, 0);
        for (var i = 0; i < 64; i++) {
            assertEquals(0, decoder.readBool(1 + i % 255));
        }
        assertTrue(decoder.isEof());
    }
}
