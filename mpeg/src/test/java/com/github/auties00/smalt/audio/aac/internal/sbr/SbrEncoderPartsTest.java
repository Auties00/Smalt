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
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the parts of the spectral band replication and parametric stereo encoder against the decoder of this
 * package: the encoder's QMF analysis against the decoder's banks, every codeword against the decoding trees,
 * every time grid against the grid parser, the tuning table against the frequency band derivation, and the
 * envelope and noise values a decoder reconstructs against those the encoder meant to send.
 */
class SbrEncoderPartsTest {
    /**
     * Returns a sum of tones and a little noise at 48 kHz.
     */
    private static float[] tones(int length, double... frequencies) {
        var random = new Random(3);
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

    /**
     * Analyzes a signal with the encoder's 64-band bank.
     */
    private static float[][] analyze(float[] input) {
        var columns = input.length / 64;
        var re = new float[columns * 64];
        var im = new float[columns * 64];
        var bank = new EncoderQmfAnalysis();
        for (var c = 0; c < columns; c++) {
            bank.analyze(input, 64 * c, re, im, 64 * c);
        }
        return new float[][]{re, im};
    }

    @Nested
    @DisplayName("64-band analysis")
    class Analysis {
        @Test
        @DisplayName("the decoder's 64-band synthesis reconstructs the input 577 samples later")
        void reconstruction() {
            var input = tones(64 * 32 * 8, 440, 2900, 7100, 13300, 19900);
            var bands = analyze(input);
            var synthesis = new QmfSynthesis(64, SbrKernels.INSTANCE);
            var output = new float[input.length];
            var frameRe = new float[32 * 64];
            var frameIm = new float[32 * 64];
            for (var f = 0; f < input.length / 2048; f++) {
                System.arraycopy(bands[0], f * 2048, frameRe, 0, 2048);
                System.arraycopy(bands[1], f * 2048, frameIm, 0, 2048);
                synthesis.synthesize(frameRe, frameIm, 32, output, 2048 * f);
            }
            var snr = snr(input, 0, output, 577, 4096, input.length - 4096);
            assertTrue(snr > 50, "reconstruction SNR " + snr);
        }

        @Test
        @DisplayName("the decoder's 32-band synthesis of the lower half outputs the input at half the rate")
        void downsampling() {
            var input = tones(64 * 32 * 8, 440, 2900, 7100, 9800);
            var bands = analyze(input);
            var synthesis = new QmfSynthesis(32, SbrKernels.INSTANCE);
            var output = new float[input.length / 2];
            var frameRe = new float[32 * 64];
            var frameIm = new float[32 * 64];
            for (var f = 0; f < input.length / 2048; f++) {
                System.arraycopy(bands[0], f * 2048, frameRe, 0, 2048);
                System.arraycopy(bands[1], f * 2048, frameIm, 0, 2048);
                synthesis.synthesize(frameRe, frameIm, 32, output, 1024 * f);
            }
            double signal = 0;
            double noise = 0;
            for (var j = 2048; j < output.length - 2048; j++) {
                var expected = input[2 * j - 577];
                signal += expected * expected;
                noise += (output[j] - expected) * (output[j] - expected);
            }
            var snr = 10 * Math.log10(signal / noise);
            assertTrue(snr > 40, "downsampled SNR " + snr);
        }

        @ParameterizedTest(name = "subband {0}")
        @ValueSource(ints = {2, 9, 17, 30})
        @DisplayName("a subband holds the energy the decoder's 32-band analysis finds in the downsampled input")
        void energies(int band) {
            var frequency = (band + 0.5) * 48000 / 128.0;
            var input = tones(64 * 32 * 8, frequency);
            var bands = analyze(input);
            var half = new float[input.length / 2];
            for (var j = 0; j < half.length; j++) {
                half[j] = input[2 * j];
            }
            var decoder = new QmfAnalysis(SbrKernels.INSTANCE);
            var re = new float[32 * 32];
            var im = new float[32 * 32];
            var frame = new float[1024];
            double expected = 0;
            for (var f = 0; f < half.length / 1024; f++) {
                System.arraycopy(half, 1024 * f, frame, 0, 1024);
                decoder.analyze(frame, 32, re, im, 0);
                if (f >= 2) {
                    for (var slot = 0; slot < 32; slot++) {
                        expected += re[32 * slot + band] * re[32 * slot + band] + im[32 * slot + band] * im[32 * slot + band];
                    }
                }
            }
            double actual = 0;
            for (var c = 64; c < input.length / 64; c++) {
                actual += bands[0][64 * c + band] * bands[0][64 * c + band] + bands[1][64 * c + band] * bands[1][64 * c + band];
            }
            var db = 10 * Math.log10(actual / expected);
            assertEquals(0, db, 0.3, "energy difference in dB");
        }

        @Test
        @DisplayName("a click reaches the decoder's analysis of the core nine columns after the encoder's")
        void alignment() {
            var input = new float[64 * 32 * 6];
            input[64 * 80 + 13] = 0.9f;
            var bands = analyze(input);
            var synthesis = new QmfSynthesis(32, SbrKernels.INSTANCE);
            var core = new float[input.length / 2];
            var frameRe = new float[32 * 64];
            var frameIm = new float[32 * 64];
            for (var f = 0; f < input.length / 2048; f++) {
                System.arraycopy(bands[0], f * 2048, frameRe, 0, 2048);
                System.arraycopy(bands[1], f * 2048, frameIm, 0, 2048);
                synthesis.synthesize(frameRe, frameIm, 32, core, 1024 * f);
            }
            var decoder = new QmfAnalysis(SbrKernels.INSTANCE);
            var re = new float[32 * 32 * 6];
            var im = new float[32 * 32 * 6];
            var frame = new float[1024];
            for (var f = 0; f < core.length / 1024; f++) {
                System.arraycopy(core, 1024 * f, frame, 0, 1024);
                decoder.analyze(frame, 32, re, im, 32 * 32 * f);
            }
            for (var band : new int[]{3, 12, 25}) {
                assertEquals(peak(bands[0], bands[1], 64, band) + 9, peak(re, im, 32, band), "subband " + band);
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

        private static double snr(float[] expected, int from, float[] actual, int lag, int start, int end) {
            double signal = 0;
            double noise = 0;
            for (var i = start; i < end; i++) {
                var e = expected[from + i];
                var d = e - actual[i + lag];
                signal += e * e;
                noise += d * d;
            }
            return 10 * Math.log10(signal / noise);
        }
    }

    @Test
    @DisplayName("every codeword of every codebook decodes back to its value")
    void codewords() throws ReflectiveOperationException {
        for (var field : SbrHuffmanCodes.class.getDeclaredFields()) {
            if (field.getType() != SbrHuffmanCodes.class) {
                continue;
            }
            var codes = (SbrHuffmanCodes) field.get(null);
            var tree = (byte[]) SbrHuffman.class.getDeclaredField(field.getName()).get(null);
            var writer = new MsbBitWriter();
            for (var value = codes.min(); value <= codes.max(); value++) {
                codes.write(writer, value);
            }
            var bits = writer.bitCount();
            var reader = new AacBitReader(writer.toByteArray());
            var total = 0L;
            for (var value = codes.min(); value <= codes.max(); value++) {
                assertEquals(value, SbrHuffman.decode(tree, reader), field.getName());
                total += codes.length(value);
            }
            assertEquals(bits, total, field.getName());
            assertEquals(bits, reader.bitPosition(), field.getName());
        }
    }

    @Nested
    @DisplayName("time grid")
    class Grid {
        @ParameterizedTest(name = "{0} slots")
        @ValueSource(ints = {16, 15})
        @DisplayName("every grid the encoder builds parses back to the same borders, resolutions and noise borders")
        void parsesBack(int timeSlots) {
            var grid = new SbrGrid();
            var parsed = new SbrFrameData();
            for (var start = 0; start <= 3; start++) {
                for (var attack = -1; attack < timeSlots; attack++) {
                    if (attack >= 0 && attack < start) {
                        continue;
                    }
                    for (var split = 0; split < 2; split++) {
                        for (var low = 0; low < 2; low++) {
                            grid.build(timeSlots, start, attack, split == 1, low == 1);
                            var what = "start " + start + ", transient " + attack + ", split " + split;
                            var writer = new MsbBitWriter();
                            grid.write(writer, timeSlots);
                            assertEquals(grid.bits(), writer.bitCount(), what);
                            parsed.readGrid(new AacBitReader(writer.toByteArray()), timeSlots);
                            assertEquals(grid.frameClass, parsed.frameClass, what);
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
                            assertEquals(start, grid.borders[0], what);
                            assertTrue(grid.end() >= timeSlots && grid.end() <= timeSlots + 3, what);
                            if (attack >= 0) {
                                var found = false;
                                for (var l = 0; l < grid.envelopes; l++) {
                                    found |= grid.borders[l] == attack && grid.borders[l + 1] == attack + 2;
                                }
                                assertTrue(found, "a two-slot envelope starts at the transient: " + what);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("every tuning entry derives valid frequency band tables with at most five patches")
    void tuning() {
        int[] rates = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};
        var checked = 0;
        for (var rate : rates) {
            for (var channels = 1; channels <= 2; channels++) {
                var min = SbrTuning.minimumBitrate(rate, channels);
                if (min == 0) {
                    continue;
                }
                var max = SbrTuning.maximumBitrate(rate, channels);
                for (var bitrate = min; bitrate <= max; bitrate += 500) {
                    var tuning = SbrTuning.of(rate, channels, bitrate).orElseThrow(
                            () -> new AssertionError("gap in the tuning table"));
                    var header = new SbrHeader();
                    header.startFrequency = tuning.startFrequency();
                    header.stopFrequency = tuning.stopFrequency();
                    header.frequencyScale = tuning.frequencyScale();
                    header.noiseBands = tuning.noiseBands();
                    var tables = new SbrFrequencies();
                    var what = rate + " Hz, " + channels + " channels, " + bitrate + " bps: " + tuning;
                    assertTrue(tables.update(header, 2 * rate), what);
                    assertTrue(tables.patches <= 5, what + " needs " + tables.patches + " patches");
                    assertTrue(tables.kx <= 32, what);
                    checked++;
                }
                assertTrue(SbrTuning.of(rate, channels, min - 1).isEmpty());
                assertTrue(SbrTuning.of(rate, channels, max + 1).isEmpty());
            }
        }
        assertTrue(checked > 1000);
    }

    @Nested
    @DisplayName("frame data")
    class FrameData {
        @ParameterizedTest(name = "{0}, {1} channels")
        @CsvSource({"tones, 1", "noise, 1", "attacks, 1", "silence, 1", "tones, 2", "attacks, 2", "panned, 2",
                "different, 2", "chaos, 1", "chaos, 2", "tones-flat, 1", "panned-flat, 2",
                "chaos-flat, 2"})
        @DisplayName("a decoder parses every payload completely and reconstructs exactly the values the encoder coded")
        void reconstructs(String kind, int channels) {
            var outputRate = 44100;
            var frames = 40;
            var length = 2048 * (frames + 2);
            // a "-flat" kind lowers the payload limit to nothing, so that every frame takes the flat fallback
            var flat = kind.endsWith("-flat");
            kind = kind.replace("-flat", "");
            var signals = signals(kind, channels, length);
            var columns = new SbrColumns[channels];
            var banks = new EncoderQmfAnalysis[channels];
            for (var c = 0; c < channels; c++) {
                columns[c] = new SbrColumns();
                banks[c] = new EncoderQmfAnalysis();
            }
            var bitrate = channels == 1 ? 32000 : 48000;
            var tuning = SbrTuning.of(outputRate / 2, channels, bitrate).orElseThrow();
            var encoder = new SbrEncoderElement(tuning, outputRate, 16, columns, null, bitrate, bitrate - 4000);
            if (flat) {
                encoder.limitPayload(0);
            }
            var decoder = new SbrElement(channels, outputRate / 2, 1024, false, false);
            var core = new float[channels][1024];
            var out = new float[channels][decoder.outputLength()];
            var analyzed = 0L;
            var classes = new ArrayList<Integer>();
            var coupled = 0;
            var maxBits = 0L;
            for (var frame = 0; frame < frames; frame++) {
                while (analyzed < 32L * frame + 80) {
                    for (var c = 0; c < channels; c++) {
                        banks[c].analyze(signals[c], (int) (64 * analyzed), columns[c].re(), columns[c].im(),
                                SbrColumns.offset(analyzed));
                    }
                    analyzed++;
                }
                var payload = encoder.encode(32L * frame);
                var bits = payload.bitCount();
                // a fill element carries at most 269 bytes, extension_type included
                assertTrue(bits <= 8 * 269, "frame " + frame + " payload of " + bits + " bits fits a fill element");
                maxBits = Math.max(maxBits, bits);
                var reader = new AacBitReader(payload.toByteArray());
                assertEquals(SbrEncoderElement.EXT_SBR_DATA, reader.read(4));
                decoder.parse(reader, bits - 4, false);
                assertTrue(decoder.frameDataValid(), "frame " + frame + " parses");
                assertEquals(bits - 4, decoder.consumedBits(), "frame " + frame + " parses to its end");
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
                    if (flat) {
                        assertTrue(!encoder.coupled(), "the fallback codes the channels separately");
                        for (var l = 0; l < data.envelopes; l++) {
                            for (var b = 1; b < encoder.tables().bands(data.resolution[l]); b++) {
                                assertEquals(data.envelope[l][0], data.envelope[l][b], "flat envelope " + l);
                            }
                        }
                        for (var e = 0; e < data.noiseEnvelopes; e++) {
                            for (var b = 1; b < encoder.tables().noiseBands; b++) {
                                assertEquals(data.noise[e][0], data.noise[e][b], "flat noise floor " + e);
                            }
                        }
                    }
                }
                classes.add(encoder.grid(0).frameClass);
                coupled += encoder.coupled() ? 1 : 0;
            }
            System.out.println("SBR payload " + kind + " " + channels + ": at most " + maxBits + " bits");
            if (kind.equals("attacks")) {
                assertTrue(classes.contains(SbrFrameData.FIXVAR) || classes.contains(SbrFrameData.VARVAR),
                        "attacks get transient grids: " + classes);
            }
            if (kind.equals("tones") || kind.equals("noise") || kind.equals("silence")) {
                // the onset of the signal in the first frame is a transient
                assertTrue(classes.subList(2, frames).stream().allMatch(c -> c == SbrFrameData.FIXFIX),
                        "stationary grids: " + classes);
            }
            if (kind.equals("panned") && !flat) {
                assertTrue(coupled > frames / 2, "a correlated pair is coupled: " + coupled + " of " + frames);
            }
        }

        private static float[][] signals(String kind, int channels, int length) {
            var random = new Random(kind.hashCode());
            var out = new float[channels][length];
            if (kind.equals("chaos")) {
                // the worst case for the payload size: one tone in the middle of every QMF subband from 4 kHz
                // up, each jumping to an independent level within 80 dB every 1024 samples, in each channel
                var gains = new double[64];
                for (var c = 0; c < channels; c++) {
                    for (var i = 0; i < length; i++) {
                        if (i % 1024 == 0) {
                            for (var k = 12; k < 64; k++) {
                                gains[k] = 0.04 * Math.pow(10, -4 * random.nextDouble());
                            }
                        }
                        var v = 0.0;
                        for (var k = 12; k < 64; k++) {
                            v += gains[k] * Math.sin(Math.PI * (k + 0.5) * i / 64 + k + c);
                        }
                        out[c][i] = (float) v;
                    }
                }
                return out;
            }
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < length; i++) {
                    double v = switch (kind) {
                        case "tones", "panned" -> 0.1 * Math.sin(2 * Math.PI * 440 * i / 44100.0)
                                + 0.05 * Math.sin(2 * Math.PI * 9000 * i / 44100.0)
                                + 0.03 * Math.sin(2 * Math.PI * 14000 * i / 44100.0)
                                + 0.005 * random.nextGaussian();
                        case "noise" -> 0.1 * random.nextGaussian();
                        case "attacks" -> (i % 11025 < 400 ? 0.5 * Math.exp(-(i % 11025) / 120.0) : 0.002)
                                * random.nextGaussian();
                        case "different" -> c == 0 ? 0.1 * random.nextGaussian()
                                : 0.1 * Math.sin(2 * Math.PI * 12000 * i / 44100.0);
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

    @Test
    @DisplayName("a single channel element with parametric stereo parses completely, extension included")
    void parametricStereo() {
        var outputRate = 48000;
        var length = 2048 * 30;
        var random = new Random(11);
        var left = new float[length];
        var right = new float[length];
        for (var i = 0; i < length; i++) {
            var common = random.nextGaussian() * 0.05;
            left[i] = (float) (common + 0.1 * Math.sin(2 * Math.PI * 1000 * i / 48000.0));
            right[i] = (float) (common * 0.3 + 0.02 * random.nextGaussian());
        }
        var columns = new SbrColumns[]{new SbrColumns(), new SbrColumns()};
        var mono = new SbrColumns();
        var banks = new EncoderQmfAnalysis[]{new EncoderQmfAnalysis(), new EncoderQmfAnalysis()};
        for (var configuration = 0; configuration < 6; configuration++) {
            var tenBands = configuration % 2 == 1;
            var envelopes = 1 + configuration / 2 % 2;
            // the last two configurations take the flat fallback in every frame, the extension kept
            var flat = configuration >= 4;
            var ps = new PsEncoder(columns[0], columns[1], mono, 16, tenBands, envelopes);
            var tuning = SbrTuning.of(outputRate / 2, 1, 32000).orElseThrow();
            var encoder = new SbrEncoderElement(tuning, outputRate, 16, new SbrColumns[]{mono}, ps, 32000, 28000);
            if (flat) {
                encoder.limitPayload(0);
            }
            var decoder = new SbrElement(1, outputRate / 2, 1024, false, true);
            var analyzed = 0L;
            List<float[]> inputs = List.of(left, right);
            for (var frame = 0; frame < 25; frame++) {
                while (analyzed < 32L * frame + 80) {
                    for (var c = 0; c < 2; c++) {
                        banks[c].analyze(inputs.get(c), (int) (64 * (analyzed % 900)), columns[c].re(),
                                columns[c].im(), SbrColumns.offset(analyzed));
                    }
                    ps.downmix(analyzed);
                    analyzed++;
                }
                ps.analyze(32L * frame);
                var payload = encoder.encode(32L * frame);
                var bits = payload.bitCount();
                var reader = new AacBitReader(payload.toByteArray());
                assertEquals(SbrEncoderElement.EXT_SBR_DATA, reader.read(4));
                decoder.parse(reader, bits - 4, false);
                assertTrue(decoder.frameDataValid(), "frame " + frame);
                assertEquals(bits - 4, decoder.consumedBits(), "frame " + frame);
            }
        }
    }

    @Test
    @DisplayName("the downmix keeps the mean power of the channels and the phase of their sum")
    void downmix() {
        var left = new SbrColumns();
        var right = new SbrColumns();
        var mono = new SbrColumns();
        var random = new Random(5);
        for (var k = 0; k < 64; k++) {
            left.re()[k] = (float) random.nextGaussian();
            left.im()[k] = (float) random.nextGaussian();
            right.re()[k] = (float) random.nextGaussian();
            right.im()[k] = (float) random.nextGaussian();
        }
        right.re()[7] = -left.re()[7];
        right.im()[7] = -left.im()[7];
        new PsEncoder(left, right, mono, 16, false, 1).downmix(0);
        for (var k = 0; k < 64; k++) {
            var sr = left.re()[k] + right.re()[k];
            var si = left.im()[k] + right.im()[k];
            var power = (left.energy(0, k) + right.energy(0, k)) / 2;
            if (k == 7) {
                assertEquals(0, mono.energy(0, k), 1e-9);
                continue;
            }
            var gain = Math.sqrt(power / (sr * sr + si * si));
            var expected = Math.min(gain, 2);
            assertEquals(expected * sr, mono.re()[k], 1e-4 * Math.max(1, Math.abs(sr)));
            assertEquals(expected * si, mono.im()[k], 1e-4 * Math.max(1, Math.abs(si)));
        }
    }
}
