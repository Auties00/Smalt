package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the parts of the low delay spectral band replication encoder of AAC-ELD against the decoder of this
 * package: the encoder's 64-band low delay analysis against the decoder's 32-band low delay banks, every low delay
 * time grid against the grid parser, the low delay tuning table against the frequency band derivation, and the
 * envelope and noise values a decoder reconstructs from {@code low_delay_sbr_data}, at dual rate and downsampled,
 * against those the encoder meant to send.
 */
class LowDelaySbrEncoderPartsTest {
    /** Returns a sum of tones at 48 kHz. */
    private static float[] tones(int length, double... frequencies) {
        var out = new float[length];
        for (var i = 0; i < length; i++) {
            var v = 0.0;
            for (var f = 0; f < frequencies.length; f++) {
                v += 0.2 * Math.sin(2 * Math.PI * frequencies[f] * i / 48000 + f);
            }
            out[i] = (float) v;
        }
        return out;
    }

    /** Analyzes a signal with the encoder's 64-band low delay bank, 64 values per column. */
    private static float[][] analyze(float[] input) {
        var columns = input.length / 64;
        var re = new float[columns * 64];
        var im = new float[columns * 64];
        var bank = new EncoderQmfAnalysis(true);
        for (var c = 0; c < columns; c++) {
            bank.analyze(input, 64 * c, re, im, 64 * c);
        }
        return new float[][]{re, im};
    }

    /** Synthesizes the lower 32 subbands of columns with the decoder's 32-band low delay bank. */
    private static float[] synthesize(float[][] bands) {
        var columns = bands[0].length / 64;
        var synthesis = new QmfSynthesis(32, SbrKernels.INSTANCE, true);
        var output = new float[columns * 32];
        var frameRe = new float[16 * 64];
        var frameIm = new float[16 * 64];
        for (var f = 0; f < columns / 16; f++) {
            System.arraycopy(bands[0], f * 1024, frameRe, 0, 1024);
            System.arraycopy(bands[1], f * 1024, frameIm, 0, 1024);
            synthesis.synthesize(frameRe, frameIm, 16, output, 512 * f);
        }
        return output;
    }

    /** Analyzes a signal with the decoder's 32-band low delay bank, 32 values per column. */
    private static float[][] decoderAnalyze(float[] input) {
        var columns = input.length / 32;
        var bank = new QmfAnalysis(SbrKernels.INSTANCE, true);
        var re = new float[columns * 32];
        var im = new float[columns * 32];
        var frame = new float[512];
        for (var f = 0; f < columns / 16; f++) {
            System.arraycopy(input, 512 * f, frame, 0, 512);
            bank.analyze(frame, 16, re, im, 512 * f);
        }
        return new float[][]{re, im};
    }

    @Nested
    @DisplayName("64-band low delay analysis")
    class Analysis {
        @Test
        @DisplayName("the decoder's 32-band low delay synthesis of the lower half outputs the input at half the rate")
        void downsampling() {
            double[] frequencies = {440, 2900, 7100, 9800};
            var input = tones(64 * 16 * 16, frequencies);
            var output = synthesize(analyze(input));
            // the output sample j is the input at 2 j - 63.5, half a sample between two input samples: the delay
            // that, with the decoder's banks, makes the delay of the dual-rate mode a whole number of samples
            var best = Double.NEGATIVE_INFINITY;
            var bestShift = 0.0;
            for (var shift = -1.5; shift <= 1.5; shift += 0.5) {
                double signal = 0;
                double noise = 0;
                for (var j = 2048; j < output.length - 512; j++) {
                    var expected = tone(2 * j - 63 + shift, frequencies);
                    signal += expected * expected;
                    noise += (output[j] - expected) * (output[j] - expected);
                }
                var snr = 10 * Math.log10(signal / noise);
                if (snr > best) {
                    best = snr;
                    bestShift = shift;
                }
            }
            assertTrue(best > 40, "downsampled SNR " + best);
            assertEquals(-0.5, bestShift, "the output's position in the input, best SNR " + best);
        }

        /** Returns the signal of {@link #tones} at a fractional sample position. */
        private static double tone(double position, double[] frequencies) {
            var v = 0.0;
            for (var f = 0; f < frequencies.length; f++) {
                v += 0.2 * Math.sin(2 * Math.PI * frequencies[f] * position / 48000 + f);
            }
            return v;
        }

        @ParameterizedTest(name = "subband {0}")
        @ValueSource(ints = {2, 9, 17, 30})
        @DisplayName("a subband holds the energy the decoder's 32-band low delay analysis finds in the downsampled input")
        void energies(int band) {
            var frequency = (band + 0.5) * 48000 / 128.0;
            var input = tones(64 * 16 * 16, frequency);
            var bands = analyze(input);
            var half = new float[input.length / 2];
            for (var j = 0; j < half.length; j++) {
                half[j] = input[2 * j + 1];
            }
            var decoded = decoderAnalyze(half);
            double expected = 0;
            double actual = 0;
            for (var c = 64; c < half.length / 32; c++) {
                expected += decoded[0][32 * c + band] * decoded[0][32 * c + band]
                        + decoded[1][32 * c + band] * decoded[1][32 * c + band];
                actual += bands[0][64 * c + band] * bands[0][64 * c + band]
                        + bands[1][64 * c + band] * bands[1][64 * c + band];
            }
            var db = 10 * Math.log10(actual / expected);
            assertEquals(0, db, 0.3, "energy difference in dB");
        }

        @Test
        @DisplayName("a click reaches the decoder's analysis of the core input one column after the encoder's")
        void alignment() {
            var input = new float[64 * 16 * 8];
            input[64 * 60 + 13] = 0.9f;
            var bands = analyze(input);
            var core = synthesize(bands);
            var decoded = decoderAnalyze(core);
            for (var band : new int[]{3, 12, 25}) {
                assertEquals(peak(bands[0], bands[1], 64, band) + 1, peak(decoded[0], decoded[1], 32, band),
                        "subband " + band);
            }
        }

        private static int peak(float[] re, float[] im, int stride, int band) {
            var best = -1.0;
            var at = -1;
            for (var c = 0; c < re.length / stride; c++) {
                var e = re[stride * c + band] * re[stride * c + band] + im[stride * c + band] * im[stride * c + band];
                if (e > best) {
                    best = e;
                    at = c;
                }
            }
            return at;
        }
    }

    @ParameterizedTest(name = "{0} slots")
    @ValueSource(ints = {16, 15})
    @DisplayName("every low delay grid the encoder builds parses back to the same borders, resolutions and noise borders")
    void grids(int timeSlots) {
        var grid = new SbrGrid();
        var parsed = new SbrFrameData();
        for (var attack = -1; attack < timeSlots; attack++) {
            for (var split = 0; split < 2; split++) {
                for (var low = 0; low < 2; low++) {
                    for (var amp = 0; amp < 2; amp++) {
                        grid.buildLowDelay(timeSlots, attack, split == 1, low == 1);
                        var what = "transient " + attack + ", split " + split + ", low " + low + ", amp " + amp;
                        var writer = new MsbBitWriter();
                        grid.writeLowDelay(writer, amp);
                        writer.write(0x5A, 8);
                        var reader = new AacBitReader(writer.toByteArray());
                        parsed.readLowDelayGrid(reader, timeSlots);
                        assertEquals(0x5A, reader.read(8), what + ": the grid parses to its end");
                        assertEquals(grid.envelopes, parsed.envelopes, what);
                        for (var l = 0; l <= grid.envelopes; l++) {
                            assertEquals(grid.borders[l], parsed.borders[l], what);
                        }
                        for (var l = 0; l < grid.envelopes; l++) {
                            assertEquals(grid.resolution[l], parsed.resolution[l], what);
                        }
                        assertEquals(grid.transientEnvelope, parsed.transientEnvelope, what);
                        assertEquals(grid.noiseEnvelopes, parsed.noiseEnvelopes, what);
                        for (var e = 0; e <= grid.noiseEnvelopes; e++) {
                            assertEquals(grid.noiseBorders[e], parsed.noiseBorders[e], what);
                        }
                        assertEquals(grid.envelopes == 1 ? amp : -1, parsed.gridAmpResolution, what);
                        assertEquals(timeSlots, grid.end(), what);
                        if (attack >= 0) {
                            var envelope = grid.transientEnvelope;
                            assertTrue(grid.borders[envelope] <= attack && grid.borders[envelope + 1] > attack,
                                    "the transient envelope holds the transient: " + what);
                        } else {
                            assertEquals(split == 1 ? 2 : 1, grid.envelopes, what);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("every low delay tuning entry derives valid tables, within half the core rate when downsampled")
    void tuning() {
        int[] rates = {12000, 16000, 22050, 24000, 32000, 44100, 48000};
        var checked = 0;
        for (var rate : rates) {
            for (var channels = 1; channels <= 2; channels++) {
                var min = SbrTuning.minimumLowDelayBitrate(rate, channels);
                if (min == 0) {
                    continue;
                }
                var max = SbrTuning.maximumLowDelayBitrate(rate, channels);
                for (var downsampled : new boolean[]{false, true}) {
                    for (var bitrate = min; bitrate <= max; bitrate += 500) {
                        var tuning = SbrTuning.lowDelay(rate, channels, bitrate, downsampled).orElseThrow(
                                () -> new AssertionError("gap in the low delay tuning table"));
                        var header = new SbrHeader();
                        header.startFrequency = tuning.startFrequency();
                        header.stopFrequency = tuning.stopFrequency();
                        header.frequencyScale = tuning.frequencyScale();
                        header.noiseBands = tuning.noiseBands();
                        var tables = new SbrFrequencies();
                        var what = rate + " Hz, " + channels + " channels, " + bitrate + " bps, downsampled "
                                + downsampled + ": " + tuning;
                        assertTrue(tables.update(header, 2 * rate), what);
                        assertTrue(tables.patches <= 5, what + " needs " + tables.patches + " patches");
                        assertTrue(tables.kx < (downsampled ? 32 : 64) && tables.kx + tables.m <= (downsampled ? 32
                                : 64), what);
                        checked++;
                    }
                    assertTrue(SbrTuning.lowDelay(rate, channels, min - 1, downsampled).isEmpty());
                    assertTrue(SbrTuning.lowDelay(rate, channels, max + 1, downsampled).isEmpty());
                }
            }
        }
        assertTrue(checked > 2000, "checked " + checked);
        assertEquals(0, SbrTuning.minimumLowDelayBitrate(8000, 1));
        assertEquals(0, SbrTuning.minimumLowDelayBitrate(12000, 2));
    }

    @Nested
    @DisplayName("frame data")
    class FrameData {
        @ParameterizedTest(name = "{0}, {1} channels, downsampled {2}, {3} samples")
        @CsvSource({"tones, 1, false, 512", "noise, 1, false, 480", "attacks, 1, false, 512",
                "attacks, 1, true, 480", "silence, 1, false, 512", "tones, 2, false, 480", "attacks, 2, false, 512",
                "panned, 2, false, 512", "different, 2, true, 512", "noise, 2, true, 480", "tones, 1, true, 512",
                "late, 1, false, 512", "late, 1, true, 480", "late, 2, false, 480"})
        @DisplayName("a decoder parses every low delay payload completely and reconstructs the values the encoder coded")
        void reconstructs(String kind, int channels, boolean downsampled, int frameLength) {
            var coreRate = downsampled ? 48000 : 24000;
            var slots = frameLength / 32;
            var frames = 60;
            var inputFrame = downsampled ? frameLength : 2 * frameLength;
            var signals = signals(kind, channels, inputFrame * (frames + 4), 48000, inputFrame / slots, slots);
            var columns = new SbrColumns[channels];
            var wide = new EncoderQmfAnalysis[channels];
            var narrow = new QmfAnalysis[channels];
            for (var c = 0; c < channels; c++) {
                columns[c] = new SbrColumns();
                wide[c] = new EncoderQmfAnalysis(true);
                narrow[c] = new QmfAnalysis(SbrKernels.INSTANCE, true);
            }
            var bitrate = channels == 1 ? (downsampled ? 48000 : 32000) : (downsampled ? 96000 : 48000);
            var tuning = SbrTuning.lowDelay(coreRate, channels, bitrate, downsampled).orElseThrow();
            var encoder = new SbrEncoderElement(tuning, 2 * coreRate, slots, columns, null, bitrate,
                    bitrate - 3000 * channels, true);
            var decoder = new SbrElement(channels, coreRate, frameLength, downsampled, false, true);
            var header = new MsbBitWriter();
            encoder.writeHeader(header);
            decoder.configure(header.toByteArray(), 0);
            var core = new float[channels][frameLength];
            var out = new float[channels][decoder.outputLength()];
            var narrowRe = new float[32 * slots];
            var narrowIm = new float[32 * slots];
            var analyzed = 0L;
            var positions = new ArrayList<Integer>();
            var coupled = 0;
            for (var frame = 0; frame < frames; frame++) {
                while (analyzed < (long) slots * frame + slots + 8) {
                    for (var c = 0; c < channels; c++) {
                        if (downsampled) {
                            var chunk = new float[frameLength];
                            System.arraycopy(signals[c], (int) (32 * analyzed), chunk, 0, frameLength);
                            narrow[c].analyze(chunk, slots, narrowRe, narrowIm, 0);
                            for (var col = 0; col < slots; col++) {
                                System.arraycopy(narrowRe, 32 * col, columns[c].re(),
                                        SbrColumns.offset(analyzed + col), 32);
                                System.arraycopy(narrowIm, 32 * col, columns[c].im(),
                                        SbrColumns.offset(analyzed + col), 32);
                            }
                        } else {
                            for (var col = 0; col < slots; col++) {
                                wide[c].analyze(signals[c], (int) (64 * (analyzed + col)), columns[c].re(),
                                        columns[c].im(), SbrColumns.offset(analyzed + col));
                            }
                        }
                    }
                    analyzed += slots;
                }
                var payload = encoder.encode((long) slots * frame);
                var bits = payload.bitCount();
                var reader = new AacBitReader(payload.toByteArray());
                decoder.parseLowDelay(reader, bits, false);
                assertTrue(decoder.frameDataValid(), "frame " + frame + " parses");
                assertEquals(bits, decoder.consumedBits(), "frame " + frame + " parses to its end");
                decoder.decode(core, out, false, null);
                for (var c = 0; c < channels; c++) {
                    var data = decoder.frameData(c);
                    var grid = encoder.grid(c);
                    assertEquals(grid.envelopes, data.envelopes);
                    for (var l = 0; l < data.envelopes; l++) {
                        var bands = encoder.tables().bands(data.resolution[l]);
                        for (var b = 0; b < bands; b++) {
                            assertEquals(encoder.codedEnvelope(c, l, b), data.envelope[l][b],
                                    "frame " + frame + " channel " + c + " envelope " + l + " band " + b);
                        }
                    }
                    for (var e = 0; e < data.noiseEnvelopes; e++) {
                        for (var b = 0; b < encoder.tables().noiseBands; b++) {
                            assertEquals(encoder.codedNoise(c, e, b), data.noise[e][b],
                                    "frame " + frame + " channel " + c + " noise " + e + " band " + b);
                        }
                    }
                }
                positions.add(encoder.grid(0).transientPosition);
                coupled += encoder.coupled() ? 1 : 0;
            }
            if (kind.equals("attacks")) {
                assertTrue(positions.stream().filter(p -> p >= 0).count() >= 2,
                        "attacks get LD_TRAN grids: " + positions);
            }
            var continued = 0;
            for (var frame = 0; frame + 1 < frames; frame++) {
                if (positions.get(frame) >= slots - 3) {
                    // the transient envelope of an LD_TRAN frame ends with the frame, so a transient in its last
                    // three slots continues as an LD_TRAN frame from slot 0
                    assertTrue(positions.get(frame + 1) >= 0, "frame " + frame + " continues: " + positions);
                    continued += positions.get(frame + 1) == 0 ? 1 : 0;
                }
            }
            if (kind.equals("late")) {
                assertTrue(continued >= 1, "a late transient continues into the next frame: " + positions);
            }
            if (kind.equals("tones") || kind.equals("silence")) {
                assertTrue(positions.subList(3, frames).stream().allMatch(p -> p < 0),
                        "stationary grids: " + positions);
            }
            if (kind.equals("noise")) {
                // the slot energies of noise fluctuate, so a few slots pass the detector's ratio
                assertTrue(positions.subList(3, frames).stream().filter(p -> p >= 0).count() <= frames / 15,
                        "mostly stationary grids: " + positions);
            }
            if (kind.equals("panned")) {
                assertTrue(coupled > frames / 2, "a correlated pair is coupled: " + coupled + " of " + frames);
            }
            if (downsampled) {
                assertTrue(encoder.tables().kx + encoder.tables().m <= 32, "the high band ends at half the core rate");
            }
        }

        /**
         * Returns the test signal of a kind; {@code late} places noise bursts {@code 5 * slots + 3} slots apart, so
         * that their slots within the frame step through every residue and reach the last three slots.
         */
        private static float[][] signals(String kind, int channels, int length, int rate, int slotSamples,
                                         int slots) {
            var period = (5 * slots + 3) * slotSamples;
            var random = new Random(kind.hashCode());
            var out = new float[channels][length];
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < length; i++) {
                    double v = switch (kind) {
                        case "tones", "panned" -> 0.1 * Math.sin(2 * Math.PI * 440 * i / rate)
                                + 0.05 * Math.sin(2 * Math.PI * 9000 * i / rate)
                                + 0.03 * Math.sin(2 * Math.PI * 14000 * i / rate)
                                + 0.005 * random.nextGaussian();
                        case "noise" -> 0.1 * random.nextGaussian();
                        case "attacks" -> (i % 12000 < 400 ? 0.5 * Math.exp(-(i % 12000) / 120.0) : 0.002)
                                * random.nextGaussian();
                        case "late" -> (i % period < 400 ? 0.5 * Math.exp(-(i % period) / 120.0) : 0.002)
                                * random.nextGaussian();
                        case "different" -> c == 0 ? 0.1 * random.nextGaussian()
                                : 0.1 * Math.sin(2 * Math.PI * 12000 * i / rate);
                        default -> 0;
                    };
                    out[c][i] = (float) v;
                }
                if (kind.equals("panned") && c == 1) {
                    System.arraycopy(out[0], 0, out[1], 0, length);
                    for (var i = 0; i < length; i++) {
                        out[1][i] *= 0.5f;
                    }
                }
            }
            return out;
        }
    }
}
