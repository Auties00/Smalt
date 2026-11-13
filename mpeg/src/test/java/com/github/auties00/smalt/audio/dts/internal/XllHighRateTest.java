package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.audio.dts.DtsDecoderOptions;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers XLL above 96 kHz: the two frequency bands of 128, 176.4 and 192 kHz streams. The filter that joins them is
 * pinned by impulse responses measured from ffmpeg 7.1 as a black box and by the integer decimator that inverts it;
 * crafted streams through every coding tool of the extension band are compared with ffmpeg's lossless output, sample
 * for sample, when it is available.
 */
final class XllHighRateTest {
    private static final int BAND_FRAME = 64;
    private static final int RATE_192 = 14;

    /**
     * The response of the filter to {@code 1 << 23} in band 0, then in band 1, then in each history sample, measured
     * from ffmpeg 7.1 (the gyan.dev essentials build for Windows) with lab streams of one frame.
     */
    private static final int[][] IMPULSES = {
            {-4872, 22892, -12045, -108143, 157586, 255193, -608539, -387140, 1533173, 493855, -3117153, -1255003,
                    5773206, 8374843, 4666344, 993019},
            {496510, -2333172, 4187423, -2886603, -627502, 1558576, 246926, -766586, -193569, 304269, 127597, -78792,
                    -54071, 6022, 11446, 2435},
            {8204883, 1746036},
            {-1442135, 6776803, 4625479, 984323},
            {595496, -2798322, 2951545, 6268428, 3682993, 783758},
            {-304524, 1431001, -1886845, -1431659, 3806912, 6007297, 3393623, 722179},
            {153944, -723404, 983025, 586607, -2148658, -996543, 4024455, 5934140, 3315624, 705580},
            {-66075, 310497, -402444, -343349, 1072028, 382814, -2194149, -900106, 4074513, 5922478, 3301053, 702479},
            {20534, -96494, 107075, 191259, -428583, -279065, 1082761, 351313, -2203250, -887957, 4081848, 5921878,
                    3299643, 702179}};

    private static int[] impulse(int band, int history) {
        var low = new int[16];
        var high = new int[16];
        var state = new int[XllBandSynthesis.HISTORY];
        if (band == 0) {
            low[0] = 1 << 23;
        } else if (band == 1) {
            high[0] = 1 << 23;
        } else {
            state[history] = 1 << 23;
        }
        var out = new int[32];
        XllBandSynthesis.synthesize(low, high, state, out, 16);
        return out;
    }

    @Nested
    @DisplayName("the band synthesis filter")
    final class Filter {
        @ParameterizedTest(name = "response {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("reproduces the impulse responses measured from ffmpeg")
        void impulses(int index) {
            var out = impulse(index < 2 ? index : 2, index - 2);
            var expected = Arrays.copyOf(IMPULSES[index], 32);
            assertArrayEquals(expected, out);
        }

        @Test
        @DisplayName("rounds an exact tie towards negative infinity")
        void ties() {
            var state = new int[XllBandSynthesis.HISTORY];
            state[0] = 1 << 22;
            var out = new int[2];
            XllBandSynthesis.synthesize(new int[1], new int[1], state, out, 1);
            // 8204883 * (1 << 22) / (1 << 23) is 4102441.5
            assertEquals(4102441, out[0]);
        }

        @Test
        @DisplayName("a frame depends only on its own history, which is the state the frame before it left")
        void frameIndependent() {
            var signal = randomSignal(new SplittableRandom(1), 142, 1 << 20);
            var bands = decimate(signal);
            var whole = new int[128];
            XllBandSynthesis.synthesize(bands.low, bands.high, bands.history(0), whole, 64);
            var head = new int[64];
            var history = bands.history(0);
            XllBandSynthesis.synthesize(bands.low, bands.high, history, head, 32);
            assertArrayEquals(bands.history(32), history);
            var tail = new int[64];
            XllBandSynthesis.synthesize(Arrays.copyOfRange(bands.low, 32, 64), Arrays.copyOfRange(bands.high, 32, 64),
                    bands.history(32), tail, 32);
            assertArrayEquals(Arrays.copyOf(whole, 64), head);
            assertArrayEquals(Arrays.copyOfRange(whole, 64, 128), tail);
        }

        @ParameterizedTest(name = "amplitude {0}")
        @ValueSource(ints = {1, 3, 100, 32767, 1 << 20, (1 << 23) - 1})
        @DisplayName("inverts the integer decimator, sample for sample")
        void lossless(int amplitude) {
            var signal = randomSignal(new SplittableRandom(amplitude), 4096, amplitude);
            var bands = decimate(signal);
            var out = new int[2 * bands.low.length];
            XllBandSynthesis.synthesize(bands.low, bands.high, bands.history(0), out, bands.low.length);
            assertArrayEquals(Arrays.copyOf(signal, out.length), out);
        }

        @Test
        @DisplayName("splits a low tone into band 0 and a high tone into band 1")
        void bandSplit() {
            var low = decimate(tone(4096, 0.05));
            var high = decimate(tone(4096, 0.45));
            assertTrue(energy(low.low) > 1000 * energy(low.high), "low tone");
            assertTrue(energy(high.high) > 1000 * energy(high.low), "high tone");
        }
    }

    @Nested
    @DisplayName("streams without core")
    final class Alone {
        @Test
        @DisplayName("a decimated 192 kHz signal decodes back to itself at twice the band rate")
        void roundTrip() {
            var random = new SplittableRandom(192);
            var signal = new int[2][4 * 2 * BAND_FRAME + 2 * XllBandSynthesis.HISTORY];
            var bands = new Bands[2];
            for (var ch = 0; ch < 2; ch++) {
                signal[ch] = randomSignal(random, signal[ch].length, 1 << 22);
                bands[ch] = decimate(signal[ch]);
            }
            var units = new ArrayList<byte[]>();
            for (var f = 0; f < 4; f++) {
                var set = new XllStreamWriter.ChannelSet(2, 6);
                set.residualType = 3;
                set.rateCode = RATE_192;
                set.residuals = new int[2][];
                set.highResiduals = new int[2][];
                set.history = new int[2][];
                for (var ch = 0; ch < 2; ch++) {
                    set.history[ch] = bands[ch].history(f * BAND_FRAME);
                    set.residuals[ch] = Arrays.copyOfRange(bands[ch].low, f * BAND_FRAME, (f + 1) * BAND_FRAME);
                    set.highResiduals[ch] = Arrays.copyOfRange(bands[ch].high, f * BAND_FRAME, (f + 1) * BAND_FRAME);
                }
                units.add(alone(List.of(set), 2, 1, RATE_192));
            }
            var decoded = decode(units);
            assertEquals(192000, decoded.rate);
            var expected = new int[4 * 2 * BAND_FRAME * 2];
            for (var n = 0; n < expected.length / 2; n++) {
                expected[2 * n] = signal[0][n] << 8;
                expected[2 * n + 1] = signal[1][n] << 8;
            }
            assertArrayEquals(expected, decoded.samples);
        }

        @ParameterizedTest(name = "rate code {0}")
        @CsvSource({"14, 192000", "8, 176400", "4, 128000"})
        @DisplayName("every rate with two bands decodes as ffmpeg decodes it")
        void rates(int rateCode, int rate) throws IOException {
            var units = stream(rateCode, new SplittableRandom(rateCode), set -> {
            });
            assertEquals(rate, decode(units).rate);
            assertLikeFfmpeg("rate " + rate, units, 0);
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("pairwise decorrelation with a different order in each band")
        void pairwise(int seed) throws IOException {
            var random = new SplittableRandom(seed);
            assertLikeFfmpeg("pairwise", stream(RATE_192, random, set -> {
                pairs(set.low, random, set.channels);
                pairs(set.high, random, set.channels);
            }), 0);
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("fixed and adaptive prediction in the extension band")
        void prediction(int seed) throws IOException {
            var random = new SplittableRandom(seed);
            assertLikeFfmpeg("prediction", stream(RATE_192, random, set -> {
                for (var band : List.of(set.low, set.high)) {
                    band.adaptiveOrders = new int[set.channels];
                    band.fixedOrders = new int[set.channels];
                    band.reflection = new int[set.channels][16];
                    for (var ch = 0; ch < set.channels; ch++) {
                        band.adaptiveOrders[ch] = random.nextInt(6);
                        band.fixedOrders[ch] = band.adaptiveOrders[ch] == 0 ? random.nextInt(4) : 0;
                        for (var n = 0; n < 16; n++) {
                            band.reflection[ch][n] = random.nextInt(-60, 61);
                        }
                    }
                }
                fill(set.residuals, random, 30);
                fill(set.highResiduals, random, 30);
            }), 0);
        }

        @Test
        @DisplayName("Rice codes in both bands")
        void rice() throws IOException {
            var random = new SplittableRandom(5);
            assertLikeFfmpeg("rice", stream(RATE_192, random, set -> {
                set.riceBits = 3;
                fill(set.residuals, random, 60);
                fill(set.highResiduals, random, 60);
            }), 0);
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {1, 4, 16, 24, 31, 32})
        @DisplayName("decimator history is a signed value of the coded width")
        void historyBits(int bits) throws IOException {
            var random = new SplittableRandom(bits);
            assertLikeFfmpeg("history " + bits, stream(RATE_192, random, set -> {
                set.historyBits = bits;
                for (var row : set.history) {
                    for (var n = 0; n < row.length; n++) {
                        row[n] = bits == 32 ? random.nextInt(-1 << 22, 1 << 22) : random.nextInt(-1 << bits - 1,
                                bits == 31 ? Integer.MAX_VALUE >> 1 : 1 << bits - 1);
                    }
                }
            }), 0);
        }

        @Test
        @DisplayName("16-bit samples, whose bit allocation fields grow by one bit with a second band")
        void sixteenBits() throws IOException {
            var random = new SplittableRandom(16);
            assertLikeFfmpeg("16 bits", stream(RATE_192, random, set -> {
                set.resolution = 16;
                set.width = 16;
                fill(set.residuals, random, 3000);
                fill(set.highResiduals, random, 3000);
            }), 0);
        }

        @Test
        @DisplayName("samples of a lower resolution than their storage are widened after the bands are joined")
        void widen() throws IOException {
            var random = new SplittableRandom(24);
            assertLikeFfmpeg("widen", stream(RATE_192, random, set -> {
                set.resolution = 16;
                fill(set.residuals, random, 3000);
                fill(set.highResiduals, random, 3000);
            }), 0);
        }

        @ParameterizedTest(name = "crc {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("least significant bits in both bands, with and without band CRCs")
        void lsbs(int crc) throws IOException {
            var random = new SplittableRandom(crc);
            var units = new ArrayList<byte[]>();
            for (var f = 0; f < 4; f++) {
                var set = randomSet(RATE_192, random);
                set.low.lsbBits = 4;
                set.low.lsbs = lsbValues(random, set.channels, 4);
                set.high.lsbBits = 3;
                set.high.lsbs = lsbValues(random, set.channels, 3);
                units.add(XllStreamWriter.substream(XllStreamWriter.component(List.of(set), BAND_FRAME, crc),
                        set.channels, 1, RATE_192));
            }
            assertLikeFfmpeg("LSB crc " + crc, units, 0);
        }

        @Test
        @DisplayName("least significant bits and a width adjustment in the extension band alone")
        void extensionBandLsbs() throws IOException {
            var random = new SplittableRandom(9);
            assertLikeFfmpeg("extension band LSBs", stream(RATE_192, random, set -> {
                set.high.lsbBits = 4;
                set.high.lsbs = lsbValues(random, set.channels, 4);
                set.high.adjust = new int[]{2, 1};
            }), 0);
        }

        @Test
        @DisplayName("a width adjustment in the extension band without its LSB part shifts over zeros, not the LSBs "
                + "of an earlier frame")
        void extensionBandAdjustWithoutLsbs() throws IOException {
            var random = new SplittableRandom(31);
            var units = new ArrayList<byte[]>();
            for (var f = 0; f < 4; f++) {
                var set = randomSet(RATE_192, random);
                if (f % 2 == 0) {
                    set.high.lsbBits = 4;
                    set.high.lsbs = lsbValues(random, set.channels, 4);
                }
                set.high.adjust = new int[]{2, 3};
                units.add(alone(List.of(set), set.channels, 1, RATE_192));
            }
            assertLikeFfmpeg("extension band adjustment without LSBs", units, 0);
        }

        @ParameterizedTest(name = "LSB part in band 1: {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("a fixed LSB width in both bands")
        void fixedLsbWidth(boolean highLsbs) throws IOException {
            var random = new SplittableRandom(highLsbs ? 41 : 42);
            var units = new ArrayList<byte[]>();
            for (var f = 0; f < 4; f++) {
                var set = randomSet(RATE_192, random);
                set.low.lsbBits = 3;
                set.low.lsbs = lsbValues(random, set.channels, 3);
                if (highLsbs || f % 2 == 0) {
                    set.high.lsbBits = 3;
                    set.high.lsbs = lsbValues(random, set.channels, 3);
                }
                units.add(XllStreamWriter.substream(XllStreamWriter.component(List.of(set), BAND_FRAME, 0, 3),
                        set.channels, 1, RATE_192));
            }
            assertLikeFfmpeg("fixed LSB width", units, 0);
        }

        @ParameterizedTest(name = "in band 1: {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("an embedded downmix is undone in band 0 and, with its history, in band 1 when flagged")
        void downmix(boolean inBand1) throws IOException {
            var random = new SplittableRandom(inBand1 ? 1 : 2);
            var units = new ArrayList<byte[]>();
            var codes = new int[]{0x65, 0x100, 0xD9, 0x100, 0x100, 0x65, 0x100, 0x100, 0xD9, 0x100};
            for (var f = 0; f < 4; f++) {
                var first = randomSet(RATE_192, random, 2, 6);
                var second = randomSet(RATE_192, random, 4, 0x39);
                second.primary = false;
                second.downmix = codes;
                second.downmixInHigh = inBand1;
                units.add(alone(List.of(first, second), 6, 0x2F, RATE_192));
            }
            assertLikeFfmpeg("downmix", units, 0);
        }

        @Test
        @DisplayName("two channel sets, without downmix")
        void twoSets() throws IOException {
            var random = new SplittableRandom(22);
            var units = new ArrayList<byte[]>();
            for (var f = 0; f < 4; f++) {
                var first = randomSet(RATE_192, random);
                var second = randomSet(RATE_192, random, 2, 12);
                second.primary = false;
                units.add(alone(List.of(first, second), 4, 0xF, RATE_192));
            }
            assertLikeFfmpeg("two sets", units, 0);
        }
    }

    @Nested
    @DisplayName("streams over a core")
    final class OverCore {
        @ParameterizedTest(name = "amplitude {0}")
        @ValueSource(ints = {0, 3, 3000})
        @DisplayName("band 0 at twice the rate of the core adds the 64-band fixed-point reconstruction")
        void residuals(int amplitude) throws IOException {
            var random = new SplittableRandom(amplitude);
            var units = new ArrayList<byte[]>();
            for (var f = 0; f < 4; f++) {
                var set = DtsFixedCoreDifferentialTest.set(6, 0x3F, 0, null, random, Math.max(amplitude, 1), 2);
                if (amplitude == 0) {
                    fill(set.residuals, random, 0);
                }
                set.rateCode = RATE_192;
                set.highResiduals = new int[6][1024];
                fill(set.highResiduals, random, amplitude);
                set.history = history(random, 6, amplitude);
                var out = new ByteArrayOutputStream();
                out.writeBytes(CoreStreamWriter.write(DtsFixedCoreDifferentialTest.plainFrame(random, 80, false, 6)));
                out.writeBytes(XllStreamWriter.substream(XllStreamWriter.component(List.of(set), 1024), 6, 0xF,
                        RATE_192));
                units.add(out.toByteArray());
            }
            assertLikeFfmpeg("over core " + amplitude, units, 0);
        }

        @Test
        @DisplayName("176.4 kHz channels over a 44.1 kHz core decode as 192 kHz channels over a 48 kHz core")
        void core44100() {
            // ffmpeg refuses these rates; the fixed-point reconstruction and the filter do not depend on the rate
            assertArrayEquals(decode(rateUnits(13, RATE_192)).samples, decode(rateUnits(8, 8)).samples);
        }

        private static List<byte[]> rateUnits(int coreRate, int rateCode) {
            var random = new SplittableRandom(1764);
            var units = new ArrayList<byte[]>();
            for (var f = 0; f < 3; f++) {
                var frame = DtsFixedCoreDifferentialTest.plainFrame(random, 80, false, 6);
                frame.sfreq = coreRate;
                var set = DtsFixedCoreDifferentialTest.set(6, 0x3F, 0, null, random, 300, 2);
                set.rateCode = rateCode;
                set.highResiduals = new int[6][1024];
                fill(set.highResiduals, random, 300);
                set.history = history(random, 6, 300);
                var out = new ByteArrayOutputStream();
                out.writeBytes(CoreStreamWriter.write(frame));
                out.writeBytes(XllStreamWriter.substream(XllStreamWriter.component(List.of(set), 1024), 6, 0xF,
                        rateCode));
                units.add(out.toByteArray());
            }
            return units;
        }

        @ParameterizedTest(name = "rate code {0}, four bands {1}")
        @CsvSource({"14, true", "15, false", "9, false"})
        @DisplayName("what is not decoded falls back to the core")
        void fallback(int rateCode, boolean extraBands) {
            var random = new SplittableRandom(rateCode);
            var units = new ArrayList<byte[]>();
            var plain = new ArrayList<byte[]>();
            for (var f = 0; f < 3; f++) {
                var frame = DtsFixedCoreDifferentialTest.plainFrame(random, 80, false, 6);
                var set = DtsFixedCoreDifferentialTest.set(6, 0x3F, 0x3F, null, random, 30, 2);
                set.rateCode = rateCode;
                set.extraBands = extraBands;
                set.highResiduals = new int[6][1024];
                set.history = history(random, 6, 30);
                var core = CoreStreamWriter.write(frame);
                plain.add(core);
                var out = new ByteArrayOutputStream();
                out.writeBytes(core);
                out.writeBytes(XllStreamWriter.substream(XllStreamWriter.component(List.of(set), 1024), 6, 0xF, 14));
                units.add(out.toByteArray());
            }
            var decoded = decode(units);
            assertEquals(48000, decoded.rate);
            assertArrayEquals(decode(plain).samples, decoded.samples);
        }
    }

    @Nested
    @DisplayName("what is not decoded")
    final class Unsupported {
        @ParameterizedTest(name = "rate code {0}, four bands {1}")
        @CsvSource({"14, true", "15, false", "9, false", "15, true"})
        @DisplayName("four bands and rates above 192 kHz raise Unsupported")
        void rates(int rateCode, boolean extraBands) {
            var set = randomSet(rateCode, new SplittableRandom(1));
            set.extraBands = extraBands;
            var unit = alone(List.of(set), 2, 1, rateCode);
            assertThrows(MediaException.Unsupported.class, () -> decode(List.of(unit)));
        }

        @Test
        @DisplayName("channel sets with different numbers of bands raise Unsupported")
        void mixedSets() {
            var random = new SplittableRandom(2);
            var first = randomSet(13, random);
            first.highResiduals = null;
            first.history = null;
            var second = randomSet(RATE_192, random, 2, 12);
            second.primary = false;
            var unit = alone(List.of(first, second), 4, 0xF, RATE_192);
            assertThrows(MediaException.Unsupported.class, () -> decode(List.of(unit)));
        }
    }

    @Nested
    @DisplayName("damaged streams")
    final class Damaged {
        @Test
        @DisplayName("a history that overruns its band data is rejected")
        void shortHistory() {
            var set = randomSet(RATE_192, new SplittableRandom(3));
            set.historyBits = 1;
            set.codedHistoryBits = 32;
            fill(set.history, new SplittableRandom(3), 0);
            var unit = alone(List.of(set), 2, 1, RATE_192);
            assertThrows(MediaException.Malformed.class, () -> decode(List.of(unit)));
        }

        @Test
        @DisplayName("random damage raises only media exceptions")
        void randomDamage() {
            var random = new SplittableRandom(4);
            var unit = alone(List.of(randomSet(RATE_192, random)), 2, 1, RATE_192);
            for (var i = 0; i < 400; i++) {
                var damaged = unit.clone();
                for (var flips = random.nextInt(1, 6); flips > 0; flips--) {
                    damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
                }
                try {
                    decode(List.of(damaged));
                } catch (MediaException expected) {
                    // the only failure a damaged stream may raise
                }
            }
        }

        @ParameterizedTest(name = "cut {0}")
        @ValueSource(ints = {1, 10, 40, 100, 200})
        @DisplayName("a truncated frame raises only media exceptions")
        void truncated(int cut) {
            var unit = alone(List.of(randomSet(RATE_192, new SplittableRandom(5))), 2, 1, RATE_192);
            try {
                decode(List.of(Arrays.copyOf(unit, unit.length - Math.min(cut, unit.length - 1))));
            } catch (MediaException expected) {
                // the only failure a truncated stream may raise
            }
        }

    }

    private static List<byte[]> stream(int rateCode, SplittableRandom random, Consumer<XllStreamWriter.ChannelSet> tools) {
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 4; f++) {
            var set = randomSet(rateCode, random);
            tools.accept(set);
            units.add(alone(List.of(set), set.channels, 1, rateCode));
        }
        return units;
    }

    private static XllStreamWriter.ChannelSet randomSet(int rateCode, SplittableRandom random) {
        return randomSet(rateCode, random, 2, 3);
    }

    private static XllStreamWriter.ChannelSet randomSet(int rateCode, SplittableRandom random, int channels, int mask) {
        var set = new XllStreamWriter.ChannelSet(channels, mask);
        set.residualType = (1 << channels) - 1;
        set.rateCode = rateCode;
        set.residuals = new int[channels][BAND_FRAME];
        set.highResiduals = new int[channels][BAND_FRAME];
        fill(set.residuals, random, 1 << 16);
        fill(set.highResiduals, random, 1 << 16);
        set.history = history(random, channels, 1 << 16);
        return set;
    }

    private static int[][] history(SplittableRandom random, int channels, int amplitude) {
        var history = new int[channels][XllBandSynthesis.HISTORY];
        fill(history, random, amplitude);
        return history;
    }

    private static void fill(int[][] rows, SplittableRandom random, int amplitude) {
        for (var row : rows) {
            for (var n = 0; n < row.length; n++) {
                row[n] = amplitude == 0 ? 0 : random.nextInt(-amplitude, amplitude);
            }
        }
    }

    private static void pairs(XllStreamWriter.Band band, SplittableRandom random, int channels) {
        band.order = new int[channels];
        for (var ch = 0; ch < channels; ch++) {
            band.order[ch] = random.nextBoolean() ? ch : channels - 1 - ch;
        }
        if (band.order[0] == band.order[1]) {
            band.order[1] = 1 - band.order[0];
        }
        band.pairCoefficients = new int[channels >> 1];
        for (var pair = 0; pair < band.pairCoefficients.length; pair++) {
            band.pairCoefficients[pair] = random.nextInt(-40, 41);
        }
    }

    private static int[][] lsbValues(SplittableRandom random, int channels, int bits) {
        var rows = new int[channels][BAND_FRAME];
        for (var row : rows) {
            for (var n = 0; n < row.length; n++) {
                row[n] = random.nextInt(1 << bits);
            }
        }
        return rows;
    }

    private static byte[] alone(List<XllStreamWriter.ChannelSet> sets, int total, int speakers, int rateCode) {
        var frameSamples = sets.getFirst().residuals[0].length;
        return XllStreamWriter.substream(XllStreamWriter.component(sets, frameSamples), total, speakers, rateCode);
    }

    /**
     * The result of the integer decimator.
     *
     * @param low the samples of band 0
     * @param high the samples of band 1
     * @param delays the delay of each stage of the synthesis filter before each block, by stage and block
     */
    private record Bands(int[] low, int[] high, int[][] delays) {
        int[] history(int block) {
            var history = new int[XllBandSynthesis.HISTORY];
            for (var stage = 0; stage < history.length; stage++) {
                history[stage] = delays[stage][block];
            }
            return history;
        }
    }

    /**
     * The integer decimator: the steps of the synthesis filter undone in reverse order. Each stage reads the output of
     * the next one a block later, so the bands are shorter than the signal by the seven blocks of the delays.
     */
    private static Bands decimate(int[] signal) {
        var sine = new int[]{8204883, -6928550, 5074941, -3259333, 1788164, -785564, 245210, -41153};
        var tangent = new int[]{-6791313, 4430736, -2825313, 1696305, -904476, 393647, -122631, 20577};
        var blocks = signal.length / 2;
        var even = new int[blocks];
        var odd = new int[blocks];
        for (var n = 0; n < blocks; n++) {
            even[n] = signal[2 * n];
            odd[n] = signal[2 * n + 1];
        }
        var delays = new int[XllBandSynthesis.HISTORY][];
        for (var stage = 0; stage < sine.length; stage++) {
            for (var n = 0; n < blocks - stage; n++) {
                odd[n] -= product(tangent[stage], even[n]);
                even[n] -= product(sine[stage], odd[n]);
                odd[n] -= product(tangent[stage], even[n]);
            }
            if (stage < delays.length) {
                delays[stage] = Arrays.copyOf(odd, blocks - stage);
                System.arraycopy(odd, 1, odd, 0, blocks - stage - 1);
            }
        }
        var samples = blocks - XllBandSynthesis.HISTORY;
        var low = new int[samples];
        var high = new int[samples];
        for (var n = 0; n < samples; n++) {
            var joined = even[n] + odd[n];
            var first = odd[n] - product(2456966, joined);
            high[n] = joined - product(11863284, first);
            low[n] = first - product(-1737338, high[n]);
        }
        return new Bands(low, high, delays);
    }

    private static int product(int coefficient, int sample) {
        return (int) ((long) coefficient * sample + (1 << 22) - 1 >> 23);
    }

    private static int[] randomSignal(SplittableRandom random, int samples, int amplitude) {
        var out = new int[samples];
        for (var n = 0; n < samples; n++) {
            out[n] = random.nextInt(-amplitude, amplitude);
        }
        return out;
    }

    private static int[] tone(int samples, double frequency) {
        var out = new int[samples];
        for (var n = 0; n < samples; n++) {
            out[n] = (int) (2000000 * Math.sin(2 * Math.PI * frequency * n));
        }
        return out;
    }

    private static double energy(int[] samples) {
        var sum = 0.0;
        for (var n = 16; n < samples.length; n++) {
            sum += (double) samples[n] * samples[n];
        }
        return sum;
    }

    private record Decoded(int[] samples, int channels, int rate) {
    }

    private static Decoded decode(List<byte[]> units) {
        var track = AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .build();
        var decoder = new DtsDecoder(track, DtsDecoderOptions.defaults());
        var buffers = new ArrayList<AudioBuffer>();
        for (var unit : units) {
            decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffer -> buffers.add(buffer.copy()));
        }
        if (buffers.isEmpty()) {
            return new Decoded(new int[0], 0, 0);
        }
        var channels = buffers.getFirst().channels();
        var out = new int[buffers.stream().mapToInt(AudioBuffer::frames).sum() * channels];
        var position = 0;
        for (var buffer : buffers) {
            for (var i = 0; i < buffer.frames(); i++) {
                for (var ch = 0; ch < channels; ch++) {
                    out[position++] = Math.round(buffer.plane(ch)[i] * 8388608.0f) << 8;
                }
            }
        }
        return new Decoded(out, channels, buffers.getFirst().sampleRate());
    }

    /**
     * Asserts that the output equals ffmpeg's lossless output, from a sample per channel on.
     */
    private static void assertLikeFfmpeg(String name, List<byte[]> units, int from) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Scratch.directory("dts-high-rate").resolve("stream-" + Thread.currentThread().threadId() + ".dts");
        var stream = new ByteArrayOutputStream();
        units.forEach(stream::writeBytes);
        Files.write(file, stream.toByteArray());
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-cpuflags", "0", "-v", "error", "-f",
                "dts", "-i", file.toString(), "-f", "s32le", "-"));
        var reference = new int[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(reference);
        var decoded = decode(units);
        assertEquals(reference.length, decoded.samples.length, name + " sample count");
        Tolerance.exact().assertWithin(name, Arrays.copyOfRange(reference, from * decoded.channels, reference.length),
                Arrays.copyOfRange(decoded.samples, from * decoded.channels, decoded.samples.length));
    }
}
