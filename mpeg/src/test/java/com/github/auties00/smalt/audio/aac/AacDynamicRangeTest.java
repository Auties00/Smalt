package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks dynamic range control against the formulas of ISO/IEC 14496-3 subclause 4.5.2.7 and ETSI TS
 * 101 154 Annex C.5. The streams are AAC LC encodes of this module's encoder and HE-AAC conformance
 * streams of ISO/IEC 14496-26 into whose access units the tests insert fill elements with
 * {@code dynamic_range_info} (before the end marker, so every other element keeps its byte alignment)
 * and data stream elements with ETSI ancillary data. Gains scale the decoder's output linearly, so a
 * decode with gains is compared with the plain decode of the same stream: exactly for gains constant
 * over the stream, by tone amplitudes for band-limited gains, and by the position of the gain step for
 * the interpolation schemes of spectral band replication.
 */
class AacDynamicRangeTest {
    private static final int RATE = 48000;

    /** A bit string. */
    static final class Bits {
        private final MsbBitWriter writer = new MsbBitWriter();
        private int length;

        Bits put(int value, int bits) {
            writer.write(value, bits);
            length += bits;
            return this;
        }

        int length() {
            return length;
        }

        byte[] bytes() {
            return writer.toByteArray();
        }

        BigInteger value() {
            var bytes = writer.toByteArray();
            return new BigInteger(1, bytes).shiftRight(8 * bytes.length - length);
        }
    }

    /**
     * Returns a {@code dynamic_range_info} extension payload, extension type included.
     *
     * @param excluded the exclusion mask of the first seven channels (bit 6 the first), or -1 for none
     * @param tops the band tops, or {@code null} for one band over the frame
     * @param scheme the interpolation scheme, with bands
     * @param level the program reference level, or -1 for none
     * @param gains the gain of each band in quarter dB
     */
    static Bits drc(int excluded, int[] tops, int scheme, int level, int... gains) {
        var bits = new Bits().put(11, 4).put(0, 1);
        bits.put(excluded >= 0 ? 1 : 0, 1);
        if (excluded >= 0) {
            bits.put(excluded, 7).put(0, 1);
        }
        bits.put(tops != null ? 1 : 0, 1);
        if (tops != null) {
            bits.put(tops.length - 1, 4).put(scheme, 4);
            for (var top : tops) {
                bits.put(top, 8);
            }
        }
        bits.put(level >= 0 ? 1 : 0, 1);
        if (level >= 0) {
            bits.put(level, 7).put(0, 1);
        }
        for (var gain : gains) {
            bits.put(gain < 0 ? 1 : 0, 1).put(Math.abs(gain), 7);
        }
        return bits;
    }

    /**
     * Returns a one-band {@code dynamic_range_info} extension payload with a program config element
     * tag and an exclusion mask of several bytes.
     *
     * @param tag the {@code pce_instance_tag}, or -1 for none
     * @param masks the seven mask bits of each mask byte (bit 6 the first channel of the byte)
     * @param gain the gain in quarter dB
     */
    static Bits drcMasked(int tag, int[] masks, int gain) {
        var bits = new Bits().put(11, 4).put(tag >= 0 ? 1 : 0, 1);
        if (tag >= 0) {
            bits.put(tag, 4).put(0, 4);
        }
        bits.put(1, 1);
        for (var i = 0; i < masks.length; i++) {
            bits.put(masks[i], 7).put(i + 1 < masks.length ? 1 : 0, 1);
        }
        bits.put(0, 1).put(0, 1);
        return bits.put(gain < 0 ? 1 : 0, 1).put(Math.abs(gain), 7);
    }

    /**
     * Returns the {@code er_raw_data_block} of ER AAC LC equivalent to an AAC LC raw data block that
     * holds one channel pair element and the end marker: the element without its element id,
     * followed by extension payloads.
     *
     * @param unit the AAC LC raw data block
     * @param extension the extension payloads, or {@code null} for none
     */
    static byte[] errorResilient(byte[] unit, Bits extension) {
        var whole = new BigInteger(1, unit);
        var total = 8 * unit.length;
        var endStart = total - 1 - whole.getLowestSetBit() - 2;
        assertEquals(1, whole.shiftRight(total - 3).intValue(), "the block starts with a channel pair element");
        var bits = new Bits();
        for (var i = 3; i < endStart; i++) {
            bits.put(whole.testBit(total - 1 - i) ? 1 : 0, 1);
        }
        if (extension != null) {
            append(bits, extension);
        }
        return bits.bytes();
    }

    /** Returns a fill element holding a payload. */
    static Bits fill(Bits payload) {
        var count = payload.length() / 8;
        var bits = new Bits().put(6, 3);
        if (count < 15) {
            bits.put(count, 4);
        } else {
            bits.put(15, 4).put(count - 14, 8);
        }
        return append(bits, payload);
    }

    /** Returns a data stream element holding ETSI TS 101 154 ancillary data with a compression value. */
    static Bits ancillary(boolean on, int value) {
        var bits = new Bits().put(4, 3).put(0, 4).put(0, 1).put(6, 8);
        // sync, bs_info (MPEG-4 audio), status (downmix levels and compression present)
        bits.put(0xBC, 8).put(0xC0, 8).put(0x14, 8).put(0x5A, 8);
        return bits.put(on ? 1 : 0, 8).put(on ? value : 0, 8);
    }

    static Bits append(Bits first, Bits second) {
        var value = second.value();
        for (var i = second.length() - 1; i >= 0; i--) {
            first.put(value.testBit(i) ? 1 : 0, 1);
        }
        return first;
    }

    /** Inserts elements before the end marker of an access unit's raw_data_block. */
    static byte[] insert(byte[] unit, Bits elements) {
        if (elements == null) {
            return unit;
        }
        var whole = new BigInteger(1, unit);
        var total = 8 * unit.length;
        var endStart = total - 1 - whole.getLowestSetBit() - 2;
        var tailLength = total - endStart;
        var head = whole.shiftRight(tailLength);
        var tail = whole.subtract(head.shiftLeft(tailLength));
        var length = endStart + elements.length() + tailLength;
        var padded = (length + 7) / 8 * 8;
        var result = head.shiftLeft(elements.length()).or(elements.value()).shiftLeft(tailLength).or(tail)
                .shiftLeft(padded - length);
        var bytes = result.toByteArray();
        var out = new byte[padded / 8];
        System.arraycopy(bytes, Math.max(0, bytes.length - out.length), out, Math.max(0, out.length - bytes.length),
                Math.min(bytes.length, out.length));
        return out;
    }

    static List<byte[]> inject(List<byte[]> frames, IntFunction<Bits> elements) {
        var out = new ArrayList<byte[]>();
        for (var i = 0; i < frames.size(); i++) {
            out.add(insert(frames.get(i), elements.apply(i)));
        }
        return out;
    }

    static AacTestSupport.Decoded decode(AacTestSupport.Stream stream, List<byte[]> frames, AacDecoderOptions options) {
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(RATE))
                .codecConfig(stream.config()).sampleRate(0).build();
        return AacTestSupport.decodeAll(new AacDecoder(track, options), frames);
    }

    static AacDecoderOptions.Builder light() {
        return AacDecoderOptions.builder().compression(AacCompression.LIGHT);
    }

    /** Asserts that a channel equals the plain decode scaled by a gain, from a sample on. */
    static void assertScaled(float[] plain, float[] actual, double gain, int from, String label) {
        double peak = 0;
        double error = 0;
        for (var i = from; i < plain.length; i++) {
            peak = Math.max(peak, Math.abs(plain[i]));
            error = Math.max(error, Math.abs(actual[i] - gain * plain[i]));
        }
        assertTrue(peak > 0.01, label + ": silent");
        assertTrue(error <= 2e-6 + 1e-5 * peak * Math.max(1, gain), label + ": error " + error + " at gain " + gain);
    }

    /** The two-tone AAC LC stereo stream (750 Hz and 12 kHz, the right channel at half level). */
    static AacTestSupport.Stream tones() {
        var seconds = 2.0;
        var samples = (int) (RATE * seconds);
        var planes = new float[2][samples];
        for (var i = 0; i < samples; i++) {
            var value = 0.25 * Math.sin(2 * Math.PI * 750 * i / RATE) + 0.25 * Math.sin(2 * Math.PI * 12000 * i / RATE);
            planes[0][i] = (float) value;
            planes[1][i] = (float) (0.5 * value);
        }
        var encoded = AacEncoderTest.encode(AacEncoderOptions.of(RATE, ChannelLayout.STEREO, 192000), planes);
        return new AacTestSupport.Stream(encoded.config(), encoded.frames(), RATE, 2);
    }

    /** A transient-rich AAC LC mono stream, which the encoder codes with short windows. */
    static AacTestSupport.Stream transients() {
        var planes = TestSignals.generate(TestSignals.Kind.TRANSIENTS, RATE, 1, 2);
        var encoded = AacEncoderTest.encode(AacEncoderOptions.of(RATE, ChannelLayout.MONO, 96000), planes);
        return new AacTestSupport.Stream(encoded.config(), encoded.frames(), RATE, 1);
    }

    static AacTestSupport.Stream conformance(String name) {
        return AacTestSupport.parseMp4(Fixtures.read(HeAacConformanceTest.fetch(name)));
    }

    /** Returns the amplitude of a tone over a sample range (Goertzel). */
    static double amplitude(float[] signal, double frequency, int from, int to) {
        double re = 0;
        double im = 0;
        for (var i = from; i < to; i++) {
            var angle = 2 * Math.PI * frequency * i / RATE;
            re += signal[i] * Math.cos(angle);
            im += signal[i] * Math.sin(angle);
        }
        return 2 * Math.hypot(re, im) / (to - from);
    }

    @Nested
    @DisplayName("Low Complexity")
    class LowComplexity {
        @Test
        @DisplayName("ignores the gains without compression or normalization")
        void ignored() {
            var stream = tones();
            var injected = inject(stream.frames(), i -> fill(drc(-1, null, 0, 80, -60)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var none = decode(stream, injected, AacDecoderOptions.defaults());
            for (var c = 0; c < 2; c++) {
                assertArrayEquals(plain.planes()[c], none.planes()[c]);
            }
        }

        @ParameterizedTest(name = "gain {0}, cut {1}, boost {2}")
        @CsvSource({"-40, 1, 1", "-40, 0.5, 1", "-40, 0, 1", "30, 1, 1", "30, 1, 0.25", "30, 1, 0", "-127, 1, 1",
                "127, 1, 1"})
        @DisplayName("scale the output by 2^(scale * gain / 24) from the first frame")
        void constant(int gain, float cut, float boost) {
            var stream = tones();
            var injected = inject(stream.frames(), i -> fill(drc(-1, null, 0, -1, gain)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().cutScale(cut).boostScale(boost).build());
            var expected = Math.pow(2, (gain < 0 ? cut : boost) * gain / 24.0);
            for (var c = 0; c < 2; c++) {
                assertScaled(plain.planes()[c], scaled.planes()[c], expected, 0, "channel " + c);
            }
        }

        @Test
        @DisplayName("keep a channel's gains until the stream overwrites them")
        void persistence() {
            var stream = tones();
            var injected = inject(stream.frames(), i -> i == 0 ? fill(drc(-1, null, 0, -1, -24))
                    : i == 40 ? fill(drc(-1, null, 0, -1, 12)) : null);
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().build());
            var cutPart = new float[39 * 1024];
            var actualCut = new float[39 * 1024];
            System.arraycopy(plain.planes()[0], 0, cutPart, 0, cutPart.length);
            System.arraycopy(scaled.planes()[0], 0, actualCut, 0, cutPart.length);
            assertScaled(cutPart, actualCut, 0.5, 0, "before the change");
            assertScaled(plain.planes()[0], scaled.planes()[0], Math.pow(2, 0.5), 41 * 1024, "after the change");
        }

        @Test
        @DisplayName("apply each band's gain to its spectral lines")
        void bands() {
            var stream = tones();
            // lines 0 to 255 (up to 6 kHz) cut by 6 dB, the rest boosted by 3 dB
            var injected = inject(stream.frames(), i -> fill(drc(-1, new int[]{63, 255}, 0, -1, -24, 12)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().build());
            for (var c = 0; c < 2; c++) {
                var low = amplitude(scaled.planes()[c], 750, 8192, 81920) / amplitude(plain.planes()[c], 750, 8192, 81920);
                var high = amplitude(scaled.planes()[c], 12000, 8192, 81920)
                        / amplitude(plain.planes()[c], 12000, 8192, 81920);
                assertEquals(0.5, low, 0.005, "channel " + c + " below the border");
                assertEquals(Math.sqrt(2), high, 0.014, "channel " + c + " above the border");
            }
        }

        @Test
        @DisplayName("leave the spectral lines above the last band unchanged")
        void aboveLastBand() {
            var stream = tones();
            var injected = inject(stream.frames(), i -> fill(drc(-1, new int[]{63}, 0, -1, -48)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().build());
            var low = amplitude(scaled.planes()[0], 750, 8192, 81920) / amplitude(plain.planes()[0], 750, 8192, 81920);
            var high = amplitude(scaled.planes()[0], 12000, 8192, 81920) / amplitude(plain.planes()[0], 12000, 8192, 81920);
            assertEquals(0.25, low, 0.003);
            assertEquals(1, high, 0.01);
        }

        @Test
        @DisplayName("skip the channels the exclusion mask names")
        void excluded() {
            var stream = tones();
            var injected = inject(stream.frames(), i -> fill(drc(0b0100000, null, 0, -1, -24)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().build());
            assertScaled(plain.planes()[0], scaled.planes()[0], 0.5, 0, "included channel");
            assertArrayEquals(plain.planes()[1], scaled.planes()[1]);
        }

        @Test
        @DisplayName("apply every payload of a fill element, a program tag and a multi-byte exclusion mask included")
        void payloads() {
            var stream = tones();
            // the first payload cuts channel 0 (channel 1 excluded); the second, whose mask spans two bytes,
            // boosts channel 1 (channel 0 excluded), so each channel keeps the gains of the payload that names it
            var injected = inject(stream.frames(), i -> fill(append(drcMasked(0, new int[]{0b0100000}, -24),
                    drcMasked(-1, new int[]{0b1000000, 0}, 12))));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().build());
            assertScaled(plain.planes()[0], scaled.planes()[0], 0.5, 0, "channel 0");
            assertScaled(plain.planes()[1], scaled.planes()[1], Math.sqrt(2), 0, "channel 1");
        }

        @Test
        @DisplayName("scale eight short windows like a long frame")
        void shortWindows() {
            var stream = transients();
            var injected = inject(stream.frames(), i -> fill(drc(-1, null, 0, -1, -36)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().build());
            assertScaled(plain.planes()[0], scaled.planes()[0], Math.pow(2, -1.5), 0, "mono");
        }

        @ParameterizedTest(name = "program level {0}, target {1}")
        @CsvSource({"100, 124", "124, 100", "92, 92", "0, 127"})
        @DisplayName("normalize the program reference level to the target level")
        void normalization(int level, int target) {
            var stream = tones();
            var injected = inject(stream.frames(), i -> fill(drc(-1, null, 0, level, 0)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var normalized = decode(stream, injected, AacDecoderOptions.builder().targetLevel(target).build());
            assertScaled(plain.planes()[0], normalized.planes()[0], Math.pow(2, (level - target) / 24.0), 0, "level");
            var withGain = decode(stream, inject(stream.frames(), i -> fill(drc(-1, null, 0, level, -24))),
                    light().targetLevel(target).build());
            assertScaled(plain.planes()[1], withGain.planes()[1], 0.5 * Math.pow(2, (level - target) / 24.0), 0,
                    "level and gain");
        }

        @Test
        @DisplayName("keep the stream's level until it transmits a program reference level")
        void noReferenceLevel() {
            var stream = tones();
            var injected = inject(stream.frames(), i -> fill(drc(-1, null, 0, -1, 0)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var normalized = decode(stream, injected, AacDecoderOptions.builder().targetLevel(64).build());
            assertArrayEquals(plain.planes()[0], normalized.planes()[0]);
        }

        @ParameterizedTest(name = "compression value {0}")
        @ValueSource(ints = {0x80, 0x93, 0x6F, 0xC0, 0x00})
        @DisplayName("apply the heavy compression value instead of the light gains")
        void heavy(int value) {
            var stream = tones();
            var injected = inject(stream.frames(), i -> append(fill(drc(-1, null, 0, -1, -24)), ancillary(true, value)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var heavy = decode(stream, injected, AacDecoderOptions.builder().compression(AacCompression.HEAVY).build());
            var expected = Math.pow(10, (48.164 - 6.0206 * (value >> 4) - 0.4014 * (value & 15)) / 20);
            assertScaled(plain.planes()[0], heavy.planes()[0], expected, 0, "heavy");
            var light = decode(stream, injected, light().build());
            assertScaled(plain.planes()[0], light.planes()[0], 0.5, 0, "light ignores the ancillary data");
        }

        @Test
        @DisplayName("fall back to the light gains while the heavy compression is switched off")
        void heavyOff() {
            var stream = tones();
            var injected = inject(stream.frames(), i -> append(fill(drc(-1, null, 0, -1, -24)), ancillary(false, 0)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var heavy = decode(stream, injected, AacDecoderOptions.builder().compression(AacCompression.HEAVY).build());
            assertScaled(plain.planes()[0], heavy.planes()[0], 0.5, 0, "heavy off");
        }

        @Test
        @DisplayName("survive truncated and arbitrary dynamic range payloads")
        void robustness() {
            var stream = tones();
            var random = new Random(11);
            var injected = inject(stream.frames(), i -> {
                var payload = new Bits().put(11, 4);
                var length = 4 + random.nextInt(12) * 8;
                for (var bit = 0; bit < length; bit++) {
                    payload.put(random.nextInt(2), 1);
                }
                return fill(payload);
            });
            var decoded = decode(stream, injected, light().targetLevel(100).build());
            for (var plane : decoded.planes()) {
                for (var sample : plane) {
                    assertTrue(Float.isFinite(sample));
                }
            }
        }
    }

    @Nested
    @DisplayName("spectral band replication")
    class Replication {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"al_sbr_e_48_2", "al_sbr_ps_01", "al960_sbr_e_48_1"})
        @DisplayName("scale the QMF subbands, look-ahead included, by a constant gain")
        void constant(String name) {
            var stream = conformance(name);
            var injected = inject(stream.frames(), i -> fill(drc(-1, null, 0, -1, -24)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().build());
            var frame = plain.planes()[0].length / stream.frames().size();
            for (var c = 0; c < plain.planes().length; c++) {
                assertScaled(plain.planes()[c], scaled.planes()[c], 0.5, 2 * frame, name + " channel " + c);
            }
        }

        @Test
        @DisplayName("ignore the gains without compression or normalization")
        void ignored() {
            var stream = conformance("al_sbr_e_48_2");
            var injected = inject(stream.frames(), i -> fill(drc(-1, new int[]{20, 255}, 3, 100, -60, 30)));
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var none = decode(stream, injected, AacDecoderOptions.defaults());
            assertArrayEquals(plain.planes()[0], none.planes()[0]);
        }

        /** Returns the first sample after {@code from} where the gain of a decode crosses a level. */
        int crossing(float[] plain, float[] scaled, int from, double level) {
            for (var block = from; block + 64 <= plain.length; block += 16) {
                double reference = 0;
                double actual = 0;
                for (var i = block; i < block + 64; i++) {
                    reference += plain[i] * plain[i];
                    actual += scaled[i] * scaled[i];
                }
                if (reference > 1e-6 && Math.sqrt(actual / reference) < level) {
                    return block + 32;
                }
            }
            throw new AssertionError("no crossing");
        }

        @Test
        @DisplayName("step to new gains at the slot each interpolation scheme selects")
        void schemes() {
            var stream = conformance("al_sbr_e_48_2");
            var step = 60;
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var frame = plain.planes()[0].length / stream.frames().size();
            var crossings = new int[9];
            for (var scheme = 0; scheme <= 8; scheme++) {
                var chosen = scheme;
                var injected = inject(stream.frames(), i -> fill(i < step ? drc(-1, new int[]{255}, 0, -1, 0)
                        : drc(-1, new int[]{255}, chosen, -1, -48)));
                var scaled = decode(stream, injected, light().build());
                crossings[scheme] = crossing(plain.planes()[0], scaled.planes()[0], (step - 2) * frame, 0.625);
            }
            var slot = frame / 32;
            for (var scheme = 2; scheme <= 8; scheme++) {
                assertEquals(4 * (scheme - 1) * slot, crossings[scheme] - crossings[1], slot,
                        "scheme " + scheme + " against scheme 1");
            }
            // the linear interpolation over a frame reaches the midpoint where the step at slot 16 steps
            assertEquals(crossings[5], crossings[0], slot, "linear interpolation");
        }

        @Test
        @DisplayName("align the gain step with the core's frame")
        void alignment() {
            var stream = conformance("al_sbr_e_48_2");
            var step = 60;
            var injected = inject(stream.frames(), i -> fill(i < step ? drc(-1, new int[]{255}, 0, -1, 0)
                    : drc(-1, new int[]{255}, 5, -1, -48)));
            var core = AacDecoderOptions.defaults().toBuilder().spectralBandReplication(false);
            var corePlain = decode(stream, stream.frames(), core.build());
            var coreScaled = decode(stream, injected, core.compression(AacCompression.LIGHT).build());
            var plain = decode(stream, stream.frames(), AacDecoderOptions.defaults());
            var scaled = decode(stream, injected, light().build());
            var coreFrame = corePlain.planes()[0].length / stream.frames().size();
            var frame = plain.planes()[0].length / stream.frames().size();
            var coreCrossing = crossing(corePlain.planes()[0], coreScaled.planes()[0], (step - 2) * coreFrame, 0.625);
            var crossing = crossing(plain.planes()[0], scaled.planes()[0], (step - 2) * frame, 0.625);
            var delay = HeAacCompare.bestSnr(HeAacCompare.lowPass(corePlain.planes()[0]),
                    HeAacCompare.lowBand(plain.planes()[0], 2))[1];
            // the replicated output runs at twice the core rate; one QMF slot is 64 output samples
            assertEquals(2 * coreCrossing, crossing - 2 * delay, 96, "core crossing " + coreCrossing + ", delay " + delay);
        }
    }

    @Nested
    @DisplayName("error resilient streams")
    class ErrorResilient {
        @Test
        @DisplayName("apply the gains of the extension payloads that end an er_raw_data_block")
        void gains() {
            var planes = new float[2][2 * RATE];
            for (var i = 0; i < planes[0].length; i++) {
                planes[0][i] = (float) (0.25 * Math.sin(2 * Math.PI * 440 * i / RATE));
                planes[1][i] = (float) (0.25 * Math.sin(2 * Math.PI * 880 * i / RATE));
            }
            // the variable bitrate encoder writes no fill elements, so each block is one CPE and the end marker;
            // below 1275 Hz the encoder codes no TNS data, whose position differs in the error resilient syntax
            var encoded = AacEncoderTest.encode(AacEncoderOptions.of(RATE, ChannelLayout.STEREO, 160000).toBuilder()
                    .bitrateMode(AacBitrateMode.VBR).bandwidth(1200).build(), planes);
            // ER AAC LC (object type 17), 48 kHz, configuration 2, every resilience flag off, epConfig 0
            var config = new byte[]{(byte) 0x89, (byte) 0x91, 0x00};
            var plainFrames = new ArrayList<byte[]>();
            var gainFrames = new ArrayList<byte[]>();
            var twoPayloads = new ArrayList<byte[]>();
            for (var frame : encoded.frames()) {
                plainFrames.add(errorResilient(frame, null));
                gainFrames.add(errorResilient(frame, drc(-1, null, 0, -1, -24)));
                twoPayloads.add(errorResilient(frame, append(drc(0b0100000, null, 0, -1, -48),
                        drc(0b1000000, null, 0, -1, 24))));
            }
            var lc = new AacTestSupport.Stream(encoded.config(), encoded.frames(), RATE, 2);
            var er = new AacTestSupport.Stream(config, plainFrames, RATE, 2);
            var reference = decode(lc, encoded.frames(), AacDecoderOptions.defaults());
            var plain = decode(er, plainFrames, light().build());
            for (var c = 0; c < 2; c++) {
                assertArrayEquals(reference.planes()[c], plain.planes()[c], "channel " + c + " of the ER conversion");
            }
            var scaled = decode(er, gainFrames, light().build());
            var unscaled = decode(er, gainFrames, AacDecoderOptions.defaults());
            var both = decode(er, twoPayloads, light().build());
            for (var c = 0; c < 2; c++) {
                assertScaled(plain.planes()[c], scaled.planes()[c], 0.5, 0, "channel " + c);
                assertArrayEquals(plain.planes()[c], unscaled.planes()[c], "channel " + c + " without compression");
            }
            assertScaled(plain.planes()[0], both.planes()[0], 0.25, 0, "first payload");
            assertScaled(plain.planes()[1], both.planes()[1], 2, 0, "second payload");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al10_48_ep0.mp4", "er_al21_48_ep0.mp4"})
        @DisplayName("decode unchanged when their extension payloads carry no gains")
        void unchanged(String name) {
            var stream = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(name)));
            var plain = AacTestSupport.decodeAll(stream);
            var light = AacTestSupport.decodeAll(new AacDecoder(stream.track(), light().targetLevel(100).build()),
                    stream.frames());
            for (var c = 0; c < plain.planes().length; c++) {
                assertArrayEquals(plain.planes()[c], light.planes()[c]);
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("default to the full range at the stream's level and round-trip through toBuilder")
        void defaults() {
            var defaults = AacDecoderOptions.defaults();
            assertEquals(AacCompression.NONE, defaults.compression());
            assertEquals(1, defaults.cutScale());
            assertEquals(1, defaults.boostScale());
            assertTrue(defaults.targetLevel().isEmpty());
            var options = AacDecoderOptions.builder().compression(AacCompression.HEAVY).cutScale(0.25f).boostScale(0.5f)
                    .targetLevel(92).build();
            assertEquals(92, options.targetLevel().orElseThrow());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().cutScale(0.5f).build());
            assertEquals(defaults, options.toBuilder().compression(AacCompression.NONE).cutScale(1).boostScale(1)
                    .noTargetLevel().build());
            assertEquals(0, AacDecoderOptions.builder().targetLevel(0).build().targetLevel().orElseThrow());
        }

        @Test
        @DisplayName("reject scales outside [0, 1] and target levels outside [0, 127] when built")
        void ranges() {
            for (var scale : new float[]{-0.01f, 1.01f, Float.NaN}) {
                assertThrows(IllegalArgumentException.class, () -> AacDecoderOptions.builder().cutScale(scale).build());
                assertThrows(IllegalArgumentException.class, () -> AacDecoderOptions.builder().boostScale(scale).build());
            }
            for (var level : new int[]{-1, 128}) {
                assertThrows(IllegalArgumentException.class, () -> AacDecoderOptions.builder().targetLevel(level).build());
            }
            assertThrows(NullPointerException.class, () -> AacDecoderOptions.builder().compression(null));
        }
    }
}
