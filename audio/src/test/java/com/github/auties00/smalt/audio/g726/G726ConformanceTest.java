package com.github.auties00.smalt.audio.g726;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.stream.Stream;

/**
 * Runs the digital test sequences of ITU-T G.726 Appendix II (as the ITU-T Software Tools Library distributes them,
 * one 16-bit little-endian word per sample) through the coder: the A-law and mu-law encoder inputs of the normal
 * and overload sequences must code to the reference ADPCM codes at every rate, and the reference codes, and the
 * decoder-only sequences {@code i16} to {@code i40}, must decode to the reference A-law and mu-law output,
 * including the cross-law outputs. The test supplies the A-law and mu-law expansion, compression and synchronous
 * coding adjustment of section 4.2.8, which the codec itself leaves out for its uniform PCM interface.
 */
class G726ConformanceTest {
    /** The test sequences check the codes and output characters bit for bit. */
    private static final Tolerance TOLERANCE = Tolerance.exact();

    static Stream<Arguments> encoderSequences() {
        var arguments = new ArrayList<Arguments>();
        for (var rate : new int[]{16, 24, 32, 40}) {
            for (var law : new char[]{'a', 'm'}) {
                for (var kind : new char[]{'n', 'v'}) {
                    arguments.add(Arguments.of(rate, law, kind));
                }
            }
        }
        return arguments.stream();
    }

    static Stream<Arguments> decoderSequences() {
        var arguments = new ArrayList<Arguments>();
        for (var rate : new int[]{16, 24, 32, 40}) {
            for (var law : new char[]{'a', 'm'}) {
                arguments.add(Arguments.of(rate, law));
            }
        }
        return arguments.stream();
    }

    private static int[] words(String name) {
        try {
            var data = Files.readAllBytes(Conformance.fetchListed("g726-stl-" + name));
            var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            var words = new int[data.length / 2];
            for (var i = 0; i < words.length; i++) {
                words[i] = buffer.getShort() & 0xFFFF;
            }
            return words;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static short[] shorts(int[] values) {
        var out = new short[values.length];
        for (var i = 0; i < values.length; i++) {
            out[i] = (short) values[i];
        }
        return out;
    }

    @ParameterizedTest(name = "{0} kbit/s, {1}-law, {2}")
    @MethodSource("encoderSequences")
    @DisplayName("codes the normal and overload sequences to the reference codes")
    void encoder(int rate, char law, char kind) {
        var input = words((kind == 'n' ? "nrm." : "ovr.") + law);
        var reference = words("r" + kind + rate + "f" + law + ".i");
        var codec = new G726Codec(rate / 8);
        var codes = new int[input.length];
        for (var i = 0; i < input.length; i++) {
            codec.estimate();
            var code = codec.quantize(Laws.expand(input[i] & 0xFF, law));
            codec.update(code);
            codes[i] = code;
        }
        TOLERANCE.assertWithin("G.726 " + rate + " " + law + "-law " + kind + " codes", reference, codes);
    }

    @ParameterizedTest(name = "{0} kbit/s, {1}-law")
    @MethodSource("decoderSequences")
    @DisplayName("decodes the reference codes to the reference output in both laws")
    void decoder(int rate, char law) {
        for (var kind : new char[]{'n', 'v'}) {
            var codes = words("r" + kind + rate + "f" + law + ".i");
            var cross = law == 'a' ? 'm' : 'a';
            assertDecodes(rate, codes, law, words("r" + kind + rate + "f" + law + ".o"), kind + " same law");
            assertDecodes(rate, codes, cross, words("r" + kind + rate + "f" + (law == 'a' ? 'x' : 'c') + ".o"),
                    kind + " cross law");
        }
        assertDecodes(rate, words("i" + rate), law, words("ri" + rate + "f" + law + ".o"), "decoder-only sequence");
    }

    private static void assertDecodes(int rate, int[] codes, char law, int[] reference, String what) {
        var bits = rate / 8;
        var codec = new G726Codec(bits);
        var output = new int[codes.length];
        for (var i = 0; i < codes.length; i++) {
            var code = codes[i] & ((1 << bits) - 1);
            codec.estimate();
            var se = codec.se;
            var y = codec.y;
            var sr = codec.update(code);
            output[i] = Laws.synchronize(codec, bits, code, Laws.compress(sr, law), law, se, y);
            reference[i] &= 0xFF;
        }
        TOLERANCE.assertPcm("G.726 " + rate + " " + law + "-law " + what, shorts(reference), shorts(output));
    }

    /**
     * The A-law and mu-law expansion, compression and synchronous coding adjustment of G.726 section 4.2.8, with
     * the PCM characters as integers from 0 to 255.
     */
    static final class Laws {
        private Laws() {
        }

        /** Returns the 14-bit uniform value of a PCM character. */
        static int expand(int character, char law) {
            if (law == 'm') {
                var c = ~character & 0xFF;
                var t = (((c & 0x0F) << 3) + 0x84) << ((c & 0x70) >> 4);
                var value = (c & 0x80) != 0 ? 0x84 - t : t - 0x84;
                return value >> 2;
            }
            var c = character ^ 0x55;
            var t = (c & 0x0F) << 4;
            var segment = (c & 0x70) >> 4;
            if (segment == 0) {
                t += 8;
            } else if (segment == 1) {
                t += 0x108;
            } else {
                t = (t + 0x108) << (segment - 1);
            }
            return ((c & 0x80) != 0 ? t : -t) >> 2;
        }

        /** Compresses a reconstructed signal (16-bit two's complement) to a PCM character. */
        static int compress(int sr, char law) {
            var negative = sr >> 15 != 0;
            var magnitude = negative ? (65536 - sr) & 32767 : sr;
            if (law == 'm') {
                var v = Math.min(magnitude, 8158) + 33;
                var segment = 0;
                while (segment < 7 && v >= (64 << segment)) {
                    segment++;
                }
                var code = (segment << 4) | ((v >> (segment + 1)) & 15);
                return (~code & 0x7F) | (negative ? 0 : 0x80);
            }
            var v = Math.min(negative ? Math.max(0, magnitude - 1) >> 1 : magnitude >> 1, 4095);
            int code;
            if (v < 32) {
                code = v >> 1;
            } else {
                var segment = 32 - Integer.numberOfLeadingZeros(v) - 5;
                code = (segment << 4) | ((v >> segment) & 15);
            }
            return (code | (negative ? 0 : 0x80)) ^ 0x55;
        }

        /** Returns the ordinal of a PCM character's output level, mu-law's negative zero merged with zero. */
        private static int level(int character, char law) {
            if (law == 'a') {
                var u = character ^ 0x55;
                var magnitude = u & 0x7F;
                return (u & 0x80) != 0 ? magnitude : -magnitude - 1;
            }
            var magnitude = ~character & 0x7F;
            return (character & 0x80) != 0 ? magnitude : -magnitude - 1;
        }

        /** Returns the PCM character of an output level ordinal. */
        private static int character(int level, char law) {
            if (law == 'a') {
                return (level >= 0 ? 0x80 | level : -level - 1) ^ 0x55;
            }
            return level >= 0 ? (~level & 0x7F) | 0x80 : ~(-level - 1) & 0x7F;
        }

        /**
         * Applies the synchronous coding adjustment (SYNC) to a decoded PCM character.
         *
         * @param codec the coder, its state already updated by the code
         * @param bits the code size
         * @param code the code
         * @param sp the compressed output character
         * @param law the law
         * @param se the signal estimate the code was decoded with
         * @param y the scale factor the code was decoded with
         * @return the adjusted character
         */
        static int synchronize(G726Codec codec, int bits, int code, int sp, char law, int se, int y) {
            var half = 1 << (bits - 1);
            var slx = expand(sp, law);
            var savedSe = codec.se;
            var savedY = codec.y;
            codec.se = se;
            codec.y = y;
            var sei = se >> 14 == 0 ? se : 32768 + se;
            var dx = ((slx & 0xFFFF) + 65536 - sei) & 0xFFFF;
            var k = codec.level(dx);
            codec.se = savedSe;
            codec.y = savedY;
            int id;
            if (dx >> 15 != 0) {
                id = half - 1 - k;
            } else {
                id = k >= 1 || bits == 2 ? half + k : half - 1;
            }
            var im = code >> (bits - 1) == 0 ? code + half : code & (half - 1);
            if (law == 'm') {
                if (id < im) {
                    if ((sp & 0x80) != 0) {
                        return sp > 0x80 ? sp - 1 : sp;
                    }
                    return sp < 0x7F ? sp + 1 : 0xFF;
                }
                if (id > im) {
                    if ((sp & 0x80) != 0) {
                        return sp < 0xFF ? sp + 1 : 0x7E;
                    }
                    return sp > 0 ? sp - 1 : sp;
                }
                return sp;
            }
            var level = level(sp, law);
            if (id < im) {
                level = Math.min(127, level + 1);
            } else if (id > im) {
                level = Math.max(-128, level - 1);
            }
            return character(level, law);
        }
    }
}
