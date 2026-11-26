package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the meter against the minimum requirements of EBU Tech 3341 (loudness and true peak) and
 * EBU Tech 3342 (loudness range), with the test signals synthesized as the documents describe at
 * 48 kHz, and its streaming, weighting and bookkeeping behavior. Cases 7 and 8 of Tech 3341 and 5
 * and 6 of Tech 3342 are authentic programs, which cannot be synthesized; the ffmpeg comparison
 * on real music covers that ground.
 */
@DisplayName("LoudnessMeter")
class LoudnessMeterTest {
    private static final int RATE = 48_000;

    private static final ChannelLayout SURROUND_5_0 = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
            Speaker.FRONT_CENTER, Speaker.BACK_LEFT, Speaker.BACK_RIGHT));

    private static LoudnessMeter stereo(double... segments) {
        return Signals.measure(Signals.tones(RATE, 2, segments), RATE, ChannelLayout.STEREO);
    }

    @Nested
    @DisplayName("EBU Tech 3341 minimum requirements")
    class Tech3341 {
        @Test
        @DisplayName("case 1: -23 dBFS stereo sine reads -23 LUFS momentary, short-term and integrated")
        void case1() {
            var meter = stereo(20, -23);
            assertEquals(-23.0, meter.momentary(), 0.1);
            assertEquals(-23.0, meter.shortTerm(), 0.1);
            assertEquals(-23.0, meter.integrated(), 0.1);
        }

        @Test
        @DisplayName("case 2: -33 dBFS stereo sine reads -33 LUFS")
        void case2() {
            var meter = stereo(20, -33);
            assertEquals(-33.0, meter.momentary(), 0.1);
            assertEquals(-33.0, meter.shortTerm(), 0.1);
            assertEquals(-33.0, meter.integrated(), 0.1);
        }

        @Test
        @DisplayName("case 3: the relative gate removes the -36 dBFS edges")
        void case3() {
            assertEquals(-23.0, stereo(10, -36, 60, -23, 10, -36).integrated(), 0.1);
        }

        @Test
        @DisplayName("case 4: both gates remove the -72 and -36 dBFS edges")
        void case4() {
            assertEquals(-23.0, stereo(10, -72, 10, -36, 60, -23, 10, -36, 10, -72).integrated(), 0.1);
        }

        @Test
        @DisplayName("case 5: -26/-20/-26 dBFS integrates to -23 LUFS")
        void case5() {
            assertEquals(-23.0, stereo(20, -26, 20.1, -20, 20, -26).integrated(), 0.1);
        }

        @Test
        @DisplayName("case 6: 5.0 sine with surround weighting integrates to -23 LUFS")
        void case6() {
            var planes = Signals.channels(RATE, 20, -28, -28, -24, -30, -30);
            assertEquals(-23.0, Signals.measure(planes, RATE, SURROUND_5_0).integrated(), 0.1);
        }

        @Test
        @DisplayName("case 9: short-term loudness stays at -23 LUFS after 3 s")
        void case9() {
            var segments = new double[20];
            for (var index = 0; index < 5; index++) {
                segments[index * 4] = 1.34;
                segments[index * 4 + 1] = -20;
                segments[index * 4 + 2] = 1.66;
                segments[index * 4 + 3] = -30;
            }
            var planes = Signals.tones(RATE, 2, segments);
            var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE);
            var step = RATE / 100;
            for (var offset = 0; offset + step <= planes[0].length; offset += step) {
                meter.accept(planes, offset, step);
                if (offset + step >= 3 * RATE) {
                    assertEquals(-23.0, meter.shortTerm(), 0.1, "at frame " + (offset + step));
                }
            }
        }

        @Test
        @DisplayName("case 10: every segment has a maximum short-term loudness of -23 LUFS")
        void case10() {
            for (var i = 0; i < 20; i++) {
                var meter = stereo(i * 0.15, Double.NaN, 3, -23, 1, Double.NaN);
                assertEquals(-23.0, meter.stats().maxShortTerm(), 0.1, "segment " + i);
            }
        }

        @Test
        @DisplayName("case 11: the live maxima rise by 1 LU per segment")
        void case11() {
            var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE);
            for (var i = 0; i < 20; i++) {
                var planes = Signals.tones(RATE, 2, i * 0.15, Double.NaN, 3, -38.0 + i, 3 - i * 0.15, Double.NaN);
                var max = Double.NEGATIVE_INFINITY;
                var step = RATE / 100;
                for (var offset = 0; offset + step <= planes[0].length; offset += step) {
                    meter.accept(planes, offset, step);
                    max = Math.max(max, meter.shortTerm());
                }
                assertEquals(-38.0 + i, max, 0.1, "segment " + i);
            }
        }

        @Test
        @DisplayName("case 12: momentary loudness stays at -23 LUFS after 1 s")
        void case12() {
            var segments = new double[100];
            for (var index = 0; index < 25; index++) {
                segments[index * 4] = 0.18;
                segments[index * 4 + 1] = -20;
                segments[index * 4 + 2] = 0.22;
                segments[index * 4 + 3] = -30;
            }
            var planes = Signals.tones(RATE, 2, segments);
            var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE);
            var step = RATE / 100;
            for (var offset = 0; offset + step <= planes[0].length; offset += step) {
                meter.accept(planes, offset, step);
                if (offset + step >= RATE) {
                    assertEquals(-23.0, meter.momentary(), 0.1, "at frame " + (offset + step));
                }
            }
        }

        @Test
        @DisplayName("case 13: every segment has a maximum momentary loudness of -23 LUFS")
        void case13() {
            for (var i = 0; i < 20; i++) {
                var meter = stereo(i * 0.02, Double.NaN, 0.4, -23, 1, Double.NaN);
                assertEquals(-23.0, meter.stats().maxMomentary(), 0.1, "segment " + i);
            }
        }

        @Test
        @DisplayName("case 14: the live momentary maxima rise by 1 LU per segment")
        void case14() {
            var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE);
            for (var i = 0; i < 20; i++) {
                var planes = Signals.tones(RATE, 2, i * 0.02, Double.NaN, 0.4, -38.0 + i, 0.4 - i * 0.02, Double.NaN);
                var max = Double.NEGATIVE_INFINITY;
                var step = RATE / 100;
                for (var offset = 0; offset + step <= planes[0].length; offset += step) {
                    meter.accept(planes, offset, step);
                    max = Math.max(max, meter.momentary());
                }
                assertEquals(-38.0 + i, max, 0.1, "segment " + i);
            }
        }

        @ParameterizedTest(name = "case {0}: fs/{1} at {2} FFS, phase {3} reads {4} dBTP")
        @CsvSource({
                "15, 4, 0.50, 0.0, -6.0",
                "16, 4, 0.50, 45.0, -6.0",
                "17, 6, 0.50, 60.0, -6.0",
                "18, 8, 0.50, 67.5, -6.0",
                "19, 4, 1.41, 45.0, 3.0"
        })
        @DisplayName("true-peak sines")
        void truePeakSines(int testCase, int divisor, double amplitude, double phase, double expected) {
            var planes = Signals.truePeakSine(RATE, RATE / (double) divisor, amplitude, phase);
            var stats = Signals.measure(planes, RATE, ChannelLayout.STEREO).stats();
            var reading = stats.truePeakDb();
            assertTrue(reading >= expected - 0.4 && reading <= expected + 0.2, "case " + testCase + " read " + reading);
        }

        @ParameterizedTest(name = "case {0}: burst decimated at offset {1}")
        @CsvSource({"20, 0", "21, 1", "22, 2", "23, 3"})
        @DisplayName("true-peak bursts")
        void truePeakBursts(int testCase, int offset) {
            var planes = Signals.truePeakBurst(RATE, offset);
            var stats = Signals.measure(planes, RATE, ChannelLayout.STEREO).stats();
            var reading = stats.truePeakDb();
            assertTrue(reading >= -0.4 && reading <= 0.2, "case " + testCase + " read " + reading);
            assertTrue(stats.samplePeakDb() < reading || offset == 0, "the sample peak misses the burst");
        }
    }

    @Nested
    @DisplayName("EBU Tech 3342 minimum requirements")
    class Tech3342 {
        @ParameterizedTest(name = "case {0}")
        @CsvSource({
                "1, 10, -20, -30, , , ",
                "2, 5, -20, -15, , , ",
                "3, 20, -40, -20, , , ",
                "4, 15, -50, -35, -20, -35, -50"
        })
        @DisplayName("loudness range of tone steps")
        void range(int testCase, double expected, Double a, Double b, Double c, Double d, Double e) {
            var levels = new ArrayList<Double>();
            for (var level : new Double[]{a, b, c, d, e}) {
                if (level != null) {
                    levels.add(level);
                }
            }
            var segments = new double[levels.size() * 2];
            for (var index = 0; index < levels.size(); index++) {
                segments[index * 2] = 20;
                segments[index * 2 + 1] = levels.get(index);
            }
            assertEquals(expected, stereo(segments).loudnessRange(), 1.0, "case " + testCase);
        }

        @Test
        @DisplayName("repeating the signal leaves the range unchanged")
        void repeated() {
            var once = stereo(20, -20, 20, -30).loudnessRange();
            var twice = stereo(20, -20, 20, -30, 20, -20, 20, -30).loudnessRange();
            assertEquals(once, twice, 1.0);
        }
    }

    @Nested
    @DisplayName("streaming")
    class Streaming {
        @Test
        @DisplayName("gives the same result whatever the block sizes")
        void blockSizes() {
            var planes = Signals.tones(RATE, 2, 5, -30, 7.3, -18, 4, -40, 6, -25);
            var whole = Signals.measure(planes, RATE, ChannelLayout.STEREO).stats();
            var random = new Random(7);
            var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE);
            var offset = 0;
            while (offset < planes[0].length) {
                var count = Math.min(planes[0].length - offset, 1 + random.nextInt(9000));
                meter.accept(planes, offset, count);
                offset += count;
            }
            var chunked = meter.stats();
            assertEquals(whole.integrated(), chunked.integrated(), 1e-9);
            assertEquals(whole.loudnessRange(), chunked.loudnessRange(), 1e-9);
            assertEquals(whole.maxMomentary(), chunked.maxMomentary(), 1e-9);
            assertEquals(whole.truePeak(), chunked.truePeak(), 0.0);
            assertEquals(whole.samplePeak(), chunked.samplePeak(), 0.0);
            assertEquals(whole.durationMicros(), chunked.durationMicros());
        }

        @Test
        @DisplayName("accepts audio buffers and checks their rate and channel count")
        void buffers() {
            var planes = Signals.tones(RATE, 2, 2, -23);
            var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE);
            meter.accept(new AudioBuffer(ChannelLayout.STEREO, RATE, planes, planes[0].length));
            assertEquals(2 * RATE, meter.frames());
            assertThrows(IllegalArgumentException.class, () -> meter.accept(new AudioBuffer(ChannelLayout.STEREO, 44_100, 16)));
            assertThrows(IllegalArgumentException.class, () -> meter.accept(new AudioBuffer(ChannelLayout.MONO, RATE, 16)));
        }

        @Test
        @DisplayName("forgets everything on reset")
        void reset() {
            var meter = stereo(10, -18);
            meter.reset();
            assertEquals(Double.NEGATIVE_INFINITY, meter.integrated());
            assertEquals(0, meter.frames());
            meter.accept(Signals.tones(RATE, 2, 10, -30), 0, 10 * RATE);
            assertEquals(-30.0, meter.integrated(), 0.1);
        }

        @Test
        @DisplayName("reports silence as negative infinity with no range and no peak")
        void silence() {
            var stats = stereo(10, Double.NaN).stats();
            assertEquals(Double.NEGATIVE_INFINITY, stats.integrated());
            assertEquals(0, stats.loudnessRange());
            assertEquals(-70, stats.relativeThreshold());
            assertEquals(0, stats.samplePeak());
            assertEquals(0, stats.truePeak());
            assertEquals(Double.NEGATIVE_INFINITY, stats.maxMomentary());
        }

        @Test
        @DisplayName("reports no window maxima before the first full window")
        void short_() {
            var stats = stereo(0.3, -20).stats();
            assertEquals(Double.NEGATIVE_INFINITY, stats.maxMomentary());
            assertEquals(Double.NEGATIVE_INFINITY, stats.integrated());
            assertEquals(Double.NEGATIVE_INFINITY, stats.maxShortTerm());
        }
    }

    @Nested
    @DisplayName("rates and channels")
    class Channels {
        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 192000})
        @DisplayName("reads a -23 dBFS 1 kHz sine as -23 LUFS at every rate")
        void rates(int rate) {
            var meter = Signals.measure(Signals.tones(rate, 2, 10, -23), rate, ChannelLayout.STEREO);
            assertEquals(-23.0, meter.integrated(), 0.1);
            assertTrue(meter.truePeak() >= meter.samplePeak());
        }

        @Test
        @DisplayName("leaves the LFE channel out and weights the 5.1 surrounds")
        void lfe() {
            var withLfe = Signals.channels(RATE, 10, -28, -28, -24, 0, -30, -30);
            assertEquals(-23.0, Signals.measure(withLfe, RATE, ChannelLayout.SURROUND_5_1).integrated(), 0.1);
        }

        @Test
        @DisplayName("weights 7.1 back channels as front channels and side channels as surrounds")
        void sevenOne() {
            var layout = ChannelLayout.SURROUND_7_1;
            var meter = new LoudnessMeter(layout, RATE);
            var levels = new double[layout.channels()];
            java.util.Arrays.fill(levels, -40);
            meter.accept(Signals.channels(RATE, 5, levels), 0, 5 * RATE);
            var sides = 0;
            var others = 0;
            for (var speaker : layout.speakers()) {
                if (speaker == Speaker.SIDE_LEFT || speaker == Speaker.SIDE_RIGHT) {
                    sides++;
                } else if (speaker != Speaker.LOW_FREQUENCY) {
                    others++;
                }
            }
            var expected = -0.691 + 10 * Math.log10((others + 1.41 * sides) * Math.pow(10, -40 / 10.0) / 2) + 0.691;
            assertEquals(expected, meter.integrated(), 0.1);
        }

        @Test
        @DisplayName("leaves both LFE channels out and weights the surround direct pair as surrounds")
        void extended() {
            var layout = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.TOP_FRONT_LEFT, Speaker.WIDE_LEFT, Speaker.WIDE_RIGHT,
                    Speaker.SURROUND_DIRECT_LEFT, Speaker.SURROUND_DIRECT_RIGHT, Speaker.LOW_FREQUENCY_2,
                    Speaker.TOP_SIDE_LEFT, Speaker.BOTTOM_FRONT_CENTER));
            var meter = new LoudnessMeter(layout, RATE);
            var levels = new double[layout.channels()];
            java.util.Arrays.fill(levels, -40);
            meter.accept(Signals.channels(RATE, 5, levels), 0, 5 * RATE);
            // eight channels at weight 1, the surround direct pair at 1.41, the two LFE channels left out
            var expected = 10 * Math.log10((8 + 1.41 * 2) * Math.pow(10, -40 / 10.0) / 2);
            assertEquals(expected, meter.integrated(), 0.1);
        }

        @Test
        @DisplayName("measures dual mono 3 LU louder than mono")
        void dualMono() {
            var planes = Signals.tones(RATE, 1, 10, -23);
            var mono = new LoudnessMeter(ChannelLayout.MONO, RATE);
            mono.accept(planes, 0, planes[0].length);
            var dual = new LoudnessMeter(ChannelLayout.MONO, RATE, LoudnessMeterOptions.builder().dualMono(true).build());
            dual.accept(planes, 0, planes[0].length);
            assertEquals(10 * Math.log10(2), dual.integrated() - mono.integrated(), 0.01);
            assertEquals(-23.0, dual.integrated(), 0.1);
        }

        @Test
        @DisplayName("uses explicit channel weights")
        void explicitWeights() {
            var planes = Signals.channels(RATE, 10, -23, -23);
            var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE, LoudnessMeterOptions.builder().channelWeights(1, 0).build());
            meter.accept(planes, 0, planes[0].length);
            assertEquals(-26.0, meter.integrated(), 0.1);
            assertThrows(IllegalArgumentException.class, () -> new LoudnessMeter(ChannelLayout.MONO, RATE,
                    LoudnessMeterOptions.builder().channelWeights(1, 1).build()));
        }

        @Test
        @DisplayName("skips the true peak when asked")
        void noTruePeak() {
            var planes = Signals.tones(RATE, 2, 2, -10);
            var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE, LoudnessMeterOptions.loudnessOnly());
            meter.accept(planes, 0, planes[0].length);
            assertTrue(Double.isNaN(meter.truePeak()));
            assertTrue(Double.isNaN(meter.stats().truePeakDb()));
            assertEquals(-10.0, meter.stats().samplePeakDb(), 0.01);
        }

        @Test
        @DisplayName("rejects rates it cannot filter")
        void badRates() {
            assertThrows(IllegalArgumentException.class, () -> new LoudnessMeter(ChannelLayout.STEREO, 4000));
            assertThrows(IllegalArgumentException.class, () -> new LoudnessMeter(ChannelLayout.STEREO, 3_000_000));
        }
    }

    @Nested
    @DisplayName("reference signals")
    class References {
        /** A decoded signal: its layout, rate and planes. */
        record Signal(ChannelLayout layout, int rate, float[][] planes) {
        }

        private static Signal decode(byte[] wav) {
            try (var reader = com.github.auties00.smalt.audio.AudioReader.open(
                    com.github.auties00.smalt.io.MediaSource.of(wav))) {
                var blocks = new ArrayList<float[][]>();
                var frames = 0;
                AudioBuffer block;
                while ((block = reader.read()) != null) {
                    var copy = new float[block.channels()][];
                    for (var channel = 0; channel < copy.length; channel++) {
                        copy[channel] = java.util.Arrays.copyOf(block.plane(channel), block.frames());
                    }
                    blocks.add(copy);
                    frames += block.frames();
                }
                var planes = new float[reader.layout().channels()][frames];
                var at = 0;
                for (var copy : blocks) {
                    for (var channel = 0; channel < planes.length; channel++) {
                        System.arraycopy(copy[channel], 0, planes[channel], at, copy[channel].length);
                    }
                    at += copy[0].length;
                }
                return new Signal(reader.layout(), reader.sampleRate(), planes);
            }
        }

        private static byte[] member(String name) {
            try (var zip = new java.util.zip.ZipFile(com.github.auties00.smalt.testkit.Conformance.vector(
                    "ebu-loudness-test-setv05.zip").toFile())) {
                var entry = zip.getEntry(name);
                assertTrue(entry != null, name + " is not in the EBU loudness test set");
                try (var in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }

        /** Meters a signal in 10 ms steps, returning the meter and the largest momentary and short-term loudness. */
        private static double[] meter(Signal signal, LoudnessMeter meter) {
            var step = signal.rate() / 100;
            var maxMomentary = Double.NEGATIVE_INFINITY;
            var maxShortTerm = Double.NEGATIVE_INFINITY;
            var frames = signal.planes()[0].length;
            for (var offset = 0; offset < frames; offset += step) {
                meter.accept(signal.planes(), offset, Math.min(step, frames - offset));
                maxMomentary = Math.max(maxMomentary, meter.momentary());
                maxShortTerm = Math.max(maxShortTerm, meter.shortTerm());
            }
            return new double[]{maxMomentary, maxShortTerm};
        }

        private static LoudnessMeter meter(Signal signal) {
            return new LoudnessMeter(signal.layout(), signal.rate());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "seq-3341-1-16bit.wav, -23.0", "seq-3341-2-16bit.wav, -33.0", "seq-3341-3-16bit-v02.wav, -23.0",
                "seq-3341-4-16bit-v02.wav, -23.0", "seq-3341-5-16bit-v02.wav, -23.0",
                "seq-3341-6-5channels-16bit.wav, -23.0", "seq-3341-6-6channels-WAVEEX-16bit.wav, -23.0",
                "seq-3341-7_seq-3342-5-24bit.wav, -23.0", "seq-3341-2011-8_seq-3342-6-24bit-v02.wav, -23.0",
                "1kHz Sine -20 LUFS-16bit.wav, -20.0", "1kHz Sine -26 LUFS-16bit.wav, -26.0",
                "1kHz Sine -40 LUFS-16bit.wav, -40.0",
                "EBU-reference_listening_signal_pinknoise_500Hz_2kHz_R128.wav, -23.0"})
        @DisplayName("measures the integrated loudness of the EBU Tech 3341 test set within 0.1 LU")
        void integrated(String name, double expected) {
            var signal = decode(member(name));
            var meter = meter(signal);
            meter(signal, meter);
            assertEquals(expected, meter.integrated(), 0.1, name);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"seq-3342-1-16bit.wav, 10", "seq-3342-2-16bit.wav, 5", "seq-3342-3-16bit.wav, 20",
                "seq-3342-4-16bit.wav, 15", "seq-3341-7_seq-3342-5-24bit.wav, 5",
                "seq-3341-2011-8_seq-3342-6-24bit-v02.wav, 15"})
        @DisplayName("measures the loudness range of the EBU Tech 3342 test set within 1 LU")
        void loudnessRange(String name, double expected) {
            var signal = decode(member(name));
            var meter = meter(signal);
            meter(signal, meter);
            assertEquals(expected, meter.loudnessRange(), 1.0, name);
        }

        @Test
        @DisplayName("keeps the short-term loudness of EBU Tech 3341 case 9 at -23 LUFS once 3 s have passed")
        void constantShortTerm() {
            var signal = decode(member("seq-3341-9-24bit.wav"));
            var meter = meter(signal);
            var step = signal.rate() / 100;
            var frames = signal.planes()[0].length;
            for (var offset = 0; offset < frames; offset += step) {
                meter.accept(signal.planes(), offset, Math.min(step, frames - offset));
                if (offset >= 3 * signal.rate()) {
                    assertEquals(-23.0, meter.shortTerm(), 0.1, "at " + offset);
                }
            }
        }

        @Test
        @DisplayName("keeps the momentary loudness of EBU Tech 3341 case 12 at -23 LUFS once 0.4 s have passed")
        void constantMomentary() {
            var signal = decode(member("seq-3341-12-24bit.wav"));
            var meter = meter(signal);
            var step = signal.rate() / 100;
            var frames = signal.planes()[0].length;
            for (var offset = 0; offset < frames; offset += step) {
                meter.accept(signal.planes(), offset, Math.min(step, frames - offset));
                if (offset >= signal.rate() * 4 / 10) {
                    assertEquals(-23.0, meter.momentary(), 0.1, "at " + offset);
                }
            }
        }

        @ParameterizedTest(name = "case {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
        @DisplayName("reaches a maximum short-term loudness of -23 LUFS in EBU Tech 3341 case 10 and a maximum "
                + "momentary loudness of -23 LUFS in case 13")
        void maxima(int index) {
            var shortTerm = decode(member("seq-3341-10-" + index + "-24bit.wav"));
            assertEquals(-23.0, meter(shortTerm, meter(shortTerm))[1], 0.1, "10-" + index);
            var name = "seq-3341-13-" + index + "-24bit.wav" + (index <= 2 ? "" : ".wav");
            var momentary = decode(member(name));
            assertEquals(-23.0, meter(momentary, meter(momentary))[0], 0.1, name);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"seq-3341-15-24bit.wav.wav, -6.0", "seq-3341-16-24bit.wav.wav, -6.0",
                "seq-3341-17-24bit.wav.wav, -6.0", "seq-3341-18-24bit.wav.wav, -6.0",
                "seq-3341-19-24bit.wav.wav, 3.0", "seq-3341-20-24bit.wav.wav, 0.0", "seq-3341-21-24bit.wav.wav, 0.0",
                "seq-3341-22-24bit.wav.wav, 0.0", "seq-3341-23-24bit.wav.wav, 0.0"})
        @DisplayName("measures the true peak of the EBU Tech 3341 test set within +0.2 and -0.4 dB")
        void truePeak(String name, double expected) {
            var signal = decode(member(name));
            var meter = meter(signal);
            meter(signal, meter);
            var peak = 20 * Math.log10(meter.truePeak());
            assertTrue(peak >= expected - 0.4 && peak <= expected + 0.2, name + ": " + peak + " dBTP");
        }

        /** Reads a 16-bit little-endian PCM file of the ITU-T STL bs1770demo as planes of a layout. */
        private static Signal stl(String vector, ChannelLayout layout) {
            byte[] bytes;
            try {
                bytes = java.nio.file.Files.readAllBytes(com.github.auties00.smalt.testkit.Conformance.vector(vector));
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            var channels = layout.channels();
            var frames = bytes.length / 2 / channels;
            var planes = new float[channels][frames];
            var words = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            for (var frame = 0; frame < frames; frame++) {
                for (var channel = 0; channel < channels; channel++) {
                    planes[channel][frame] = words.get() / 32768f;
                }
            }
            return new Signal(layout, 48_000, planes);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"bs1770-stl-sine997_0dBFS.pcm, FIVE_ONE, -3.01",
                "bs1770-stl-sine997_0dBFS.26LKFS.pcm, FIVE_ONE, -26.0",
                "bs1770-stl-sine_noise_test.16LKFS.000L11.pcm, FIVE_ONE, -16.0",
                "bs1770-stl-sine_noise_test.16LKFS.11L000.pcm, SURROUNDS_FIRST, -16.0"})
        @DisplayName("measures the ITU-T STL bs1770demo signals at the loudness the reference tool normalized them to")
        void stl(String vector, String layout, double expected) {
            // bs1770demo's 48 kHz 16-bit files: six channels whose weights its -conf string gives ('0' a front
            // channel, '1' a surround one weighted 1.41, 'L' the LFE, excluded)
            var channels = layout.equals("FIVE_ONE") ? ChannelLayout.SURROUND_5_1
                    : new ChannelLayout(List.of(Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.LOW_FREQUENCY,
                    Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER));
            var signal = stl(vector, channels);
            var meter = meter(signal);
            meter(signal, meter);
            assertEquals(expected, meter.integrated(), 0.1, vector);
        }

        @Test
        @DisplayName("moves the loudness of the STL ramp and noise by the gain the reference tool normalized them with")
        void stlGains() {
            // normalization is a gain: the gated loudness moves by it, measured between the files; the ramp normalized
            // by RMS measures louder than the one normalized by gated loudness, whose gate leaves its quiet start out
            var original = stl("bs1770-stl-sine_ramp.pcm", ChannelLayout.MONO);
            var first = meter(original);
            meter(original, first);
            for (var name : List.of("bs1770-stl-sine_ramp.26LKFS.pcm", "bs1770-stl-sine_ramp.26LKFSrms.pcm")) {
                var normalized = stl(name, ChannelLayout.MONO);
                var second = meter(normalized);
                meter(normalized, second);
                var gain = 20 * Math.log10(peak(normalized) / peak(original));
                assertEquals(first.integrated() + gain, second.integrated(), 0.1, name);
            }
            var gated = meter(stl("bs1770-stl-sine_ramp.26LKFS.pcm", ChannelLayout.MONO));
            meter(stl("bs1770-stl-sine_ramp.26LKFS.pcm", ChannelLayout.MONO), gated);
            var rms = meter(stl("bs1770-stl-sine_ramp.26LKFSrms.pcm", ChannelLayout.MONO));
            meter(stl("bs1770-stl-sine_ramp.26LKFSrms.pcm", ChannelLayout.MONO), rms);
            assertTrue(rms.integrated() > gated.integrated(), rms.integrated() + " vs " + gated.integrated());
            var noise = stl("bs1770-stl-sine_noise_test.pcm", ChannelLayout.SURROUND_5_1);
            var noiseMeter = meter(noise);
            meter(noise, noiseMeter);
            var weighted = stl("bs1770-stl-sine_noise_test.16LKFS.000L11.pcm", ChannelLayout.SURROUND_5_1);
            assertEquals(-16.0, noiseMeter.integrated() + 20 * Math.log10(peak(weighted) / peak(noise)), 0.1);
        }

        private static double peak(Signal signal) {
            var peak = 0.0;
            for (var plane : signal.planes()) {
                for (var sample : plane) {
                    peak = Math.max(peak, Math.abs(sample));
                }
            }
            return peak;
        }
    }
}
