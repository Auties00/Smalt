package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;
import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;
import static com.github.auties00.smalt.audio.opus.internal.silk.SilkErrors.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Component checks of the SILK encoder against the independently ported decoder: every encoder
 * stage that has a decoder counterpart must round-trip through it, and the analysis stages must
 * behave sanely on synthetic input. Bit-exactness against libopus is covered by
 * {@link SilkEncoderBitExactTest}.
 */
class SilkEncoderComponentsTest {
    private static short[] stableNlsf(Random random, NlsfCodebook cb) {
        var order = cb.order;
        var nlsf = new short[order];
        var row = random.nextInt(cb.nVectors) * order;
        for (var i = 0; i < order; i++) {
            nlsf[i] = (short) Math.max(1, Math.min(32766, (cb.CB1_NLSF_Q8[row + i] << 7) + random.nextInt(601) - 300));
        }
        Arrays.sort(nlsf);
        NlsfStabilize.nlsfStabilize(nlsf, cb.deltaMin_Q15, order);
        return nlsf;
    }

    @Nested
    @DisplayName("NLSF quantizer")
    class Nlsf {
        @ParameterizedTest(name = "order {0}")
        @ValueSource(ints = {10, 16})
        @DisplayName("produces indices that NlsfDecode maps back to the quantized vector")
        void encodeDecode(int order) {
            var cb = order == 16 ? Tables.silk_NLSF_CB_WB : Tables.silk_NLSF_CB_NB_MB;
            var random = new Random(order);
            var quantizer = new NlsfEncode();
            var weights = new short[order];
            var indices = new byte[MAX_LPC_ORDER + 1];
            var decoded = new short[order];
            for (var trial = 0; trial < 200; trial++) {
                var original = stableNlsf(random, cb);
                NlsfEncode.nlsfVqWeightsLaroia(weights, original, order);
                var quantized = original.clone();
                var signalType = trial % 3;
                quantizer.nlsfEncode(indices, quantized, cb, weights, 3146, 16, signalType);
                assertTrue(indices[0] >= 0 && indices[0] < cb.nVectors, "first-stage index");
                for (var i = 1; i <= order; i++) {
                    assertTrue(Math.abs(indices[i]) <= NLSF_QUANT_MAX_AMPLITUDE_EXT, "residual index " + i);
                }
                NlsfDecode.nlsfDecode(decoded, indices, cb);
                assertArrayEquals(quantized, decoded);
                for (var i = 1; i < order; i++) {
                    assertTrue(decoded[i] > decoded[i - 1], "quantized vector must stay ordered");
                }
            }
        }

        @Test
        @DisplayName("never gets worse when more first-stage survivors are searched")
        void survivorsMonotone() {
            var cb = Tables.silk_NLSF_CB_WB;
            var random = new Random(7);
            var quantizer = new NlsfEncode();
            var weights = new short[16];
            var indices = new byte[MAX_LPC_ORDER + 1];
            for (var trial = 0; trial < 100; trial++) {
                var original = stableNlsf(random, cb);
                NlsfEncode.nlsfVqWeightsLaroia(weights, original, 16);
                var few = quantizer.nlsfEncode(indices, original.clone(), cb, weights, 3146, 2, 1);
                var many = quantizer.nlsfEncode(indices, original.clone(), cb, weights, 3146, 16, 1);
                assertTrue(many <= few, "RD with 16 survivors " + many + " exceeds RD with 2 survivors " + few);
            }
        }

        @ParameterizedTest(name = "order {0}")
        @ValueSource(ints = {10, 16})
        @DisplayName("A2NLSF inverts NLSF2A up to fixed-point precision")
        void a2NlsfInvertsNlsf2A(int order) {
            var cb = order == 16 ? Tables.silk_NLSF_CB_WB : Tables.silk_NLSF_CB_NB_MB;
            var random = new Random(31L * order);
            var a_Q12 = new short[order];
            var a_Q16 = new int[order];
            var recovered = new short[order];
            var worst = 0;
            for (var trial = 0; trial < 500; trial++) {
                var nlsf = stableNlsf(random, cb);
                Nlsf2A.nlsf2a(a_Q12, nlsf, order);
                for (var i = 0; i < order; i++) {
                    a_Q16[i] = a_Q12[i] << 4;
                }
                A2Nlsf.a2nlsf(recovered, a_Q16, order);
                for (var i = 0; i < order; i++) {
                    worst = Math.max(worst, Math.abs(recovered[i] - nlsf[i]));
                }
            }
            assertTrue(worst <= NLSF_TOLERANCE, "worst NLSF deviation " + worst);
        }
    }

    /** The largest NLSF deviation, in Q15, tolerated after an NLSF2A/A2NLSF round trip. */
    private static final int NLSF_TOLERANCE = 64;

    @Nested
    @DisplayName("entropy coding")
    class EntropyCoding {
        @Test
        @DisplayName("side information decodes to the encoded indices")
        void indices() {
            var random = new Random(11);
            var enc = new EncoderState();
            enc.fs_kHz = 16;
            enc.nb_subfr = MAX_NB_SUBFR;
            enc.psNLSF_CB = Tables.silk_NLSF_CB_WB;
            enc.pitch_lag_low_bits_iCDF = Tables.silk_uniform8_iCDF;
            enc.pitch_contour_iCDF = Tables.silk_pitch_contour_iCDF;
            var dec = new DecoderState();
            dec.fs_kHz = 16;
            dec.nb_subfr = MAX_NB_SUBFR;
            dec.psNLSF_CB = Tables.silk_NLSF_CB_WB;
            dec.pitch_lag_low_bits_iCDF = Tables.silk_uniform8_iCDF;
            dec.pitch_contour_iCDF = Tables.silk_pitch_contour_iCDF;
            var buffer = new byte[1275];
            var coder = new EncodeIndices();
            for (var packet = 0; packet < 300; packet++) {
                var rc = new RangeEncoder(buffer, 0, buffer.length);
                var frames = 3;
                var expected = new SideInfoIndices[frames];
                var prevSignalType = new int[frames];
                var prevLag = new short[frames];
                var vad = new int[frames];
                for (var f = 0; f < frames; f++) {
                    var condCoding = f == 0 ? CODE_INDEPENDENTLY : CODE_CONDITIONALLY;
                    var ix = enc.indices;
                    randomIndices(random, ix, condCoding, enc.ec_prevLagIndex);
                    expected[f] = copy(ix);
                    prevSignalType[f] = enc.ec_prevSignalType;
                    prevLag[f] = enc.ec_prevLagIndex;
                    vad[f] = ix.signalType != TYPE_NO_VOICE_ACTIVITY ? 1 : 0;
                    coder.encodeIndices(enc, rc, f, 0, condCoding);
                    enc.ec_prevSignalType = ix.signalType;
                }
                rc.encDone();
                var rd = new RangeDecoder(buffer, 0, buffer.length);
                for (var f = 0; f < frames; f++) {
                    var condCoding = f == 0 ? CODE_INDEPENDENTLY : CODE_CONDITIONALLY;
                    dec.VAD_flags[f] = vad[f];
                    dec.ec_prevSignalType = prevSignalType[f];
                    dec.ec_prevLagIndex = prevLag[f];
                    DecodeIndices.decodeIndices(dec, rd, f, 0, condCoding);
                    assertIndicesEqual(expected[f], dec.indices, "packet " + packet + " frame " + f);
                }
            }
        }

        @ParameterizedTest(name = "{0} samples")
        @ValueSource(ints = {80, 120, 160, 240, 320})
        @DisplayName("excitation pulses decode to the encoded pulses")
        void pulses(int frameLength) {
            var random = new Random(frameLength);
            var encoder = new EncodePulses();
            var padded = (frameLength + SHELL_CODEC_FRAME_LENGTH - 1) / SHELL_CODEC_FRAME_LENGTH * SHELL_CODEC_FRAME_LENGTH;
            var pulses = new byte[padded + SHELL_CODEC_FRAME_LENGTH];
            var decoded = new short[padded];
            var buffer = new byte[1275];
            for (var trial = 0; trial < 300; trial++) {
                var density = trial % 4;
                for (var i = 0; i < frameLength; i++) {
                    var p = switch (density) {
                        case 0 -> random.nextInt(9) == 0 ? random.nextInt(3) - 1 : 0;
                        case 1 -> random.nextInt(7) - 3;
                        case 2 -> random.nextInt(31) - 15;
                        default -> random.nextInt(50) == 0 ? random.nextInt(255) - 127 : random.nextInt(5) - 2;
                    };
                    pulses[i] = (byte) p;
                }
                var signalType = trial % 3;
                var quantOffsetType = (trial / 3) % 2;
                var expected = Arrays.copyOf(pulses, frameLength);
                var rc = new RangeEncoder(buffer, 0, buffer.length);
                encoder.encodePulses(rc, signalType, quantOffsetType, pulses, frameLength);
                rc.encDone();
                var rd = new RangeDecoder(buffer, 0, buffer.length);
                DecodePulses.decodePulses(rd, decoded, signalType, quantOffsetType, frameLength);
                for (var i = 0; i < frameLength; i++) {
                    assertEquals(expected[i], decoded[i], "trial " + trial + " sample " + i);
                }
            }
        }

        private static void randomIndices(Random random, SideInfoIndices ix, int condCoding, short prevLag) {
            ix.signalType = (byte) random.nextInt(3);
            ix.quantOffsetType = (byte) random.nextInt(2);
            ix.GainsIndices[0] = (byte) (condCoding == CODE_INDEPENDENTLY ? random.nextInt(N_LEVELS_QGAIN)
                    : random.nextInt(MAX_DELTA_GAIN_QUANT - MIN_DELTA_GAIN_QUANT + 1));
            for (var k = 1; k < MAX_NB_SUBFR; k++) {
                ix.GainsIndices[k] = (byte) random.nextInt(MAX_DELTA_GAIN_QUANT - MIN_DELTA_GAIN_QUANT + 1);
            }
            ix.NLSFIndices[0] = (byte) random.nextInt(32);
            for (var i = 1; i <= 16; i++) {
                ix.NLSFIndices[i] = (byte) (random.nextInt(8) == 0
                        ? random.nextInt(2 * NLSF_QUANT_MAX_AMPLITUDE_EXT + 1) - NLSF_QUANT_MAX_AMPLITUDE_EXT
                        : random.nextInt(5) - 2);
            }
            ix.NLSFInterpCoef_Q2 = (byte) random.nextInt(5);
            if (ix.signalType == TYPE_VOICED) {
                ix.lagIndex = (short) (random.nextBoolean() ? random.nextInt(256)
                        : Math.max(0, Math.min(255, prevLag + random.nextInt(20) - 8)));
                ix.contourIndex = (byte) random.nextInt(34);
                ix.PERIndex = (byte) random.nextInt(3);
                for (var k = 0; k < MAX_NB_SUBFR; k++) {
                    ix.LTPIndex[k] = (byte) random.nextInt(Tables.silk_LTP_vq_sizes[ix.PERIndex]);
                }
                ix.LTP_scaleIndex = (byte) (condCoding == CODE_INDEPENDENTLY ? random.nextInt(3) : 0);
            }
            ix.Seed = (byte) random.nextInt(4);
        }

        private static SideInfoIndices copy(SideInfoIndices ix) {
            var c = new SideInfoIndices();
            System.arraycopy(ix.GainsIndices, 0, c.GainsIndices, 0, MAX_NB_SUBFR);
            System.arraycopy(ix.LTPIndex, 0, c.LTPIndex, 0, MAX_NB_SUBFR);
            System.arraycopy(ix.NLSFIndices, 0, c.NLSFIndices, 0, MAX_LPC_ORDER + 1);
            c.lagIndex = ix.lagIndex;
            c.contourIndex = ix.contourIndex;
            c.signalType = ix.signalType;
            c.quantOffsetType = ix.quantOffsetType;
            c.NLSFInterpCoef_Q2 = ix.NLSFInterpCoef_Q2;
            c.PERIndex = ix.PERIndex;
            c.LTP_scaleIndex = ix.LTP_scaleIndex;
            c.Seed = ix.Seed;
            return c;
        }

        private static void assertIndicesEqual(SideInfoIndices e, SideInfoIndices a, String where) {
            assertEquals(e.signalType, a.signalType, where + " signalType");
            assertEquals(e.quantOffsetType, a.quantOffsetType, where + " quantOffsetType");
            assertArrayEquals(Arrays.copyOf(e.GainsIndices, MAX_NB_SUBFR), Arrays.copyOf(a.GainsIndices, MAX_NB_SUBFR),
                    where + " gains");
            assertArrayEquals(Arrays.copyOf(e.NLSFIndices, 17), Arrays.copyOf(a.NLSFIndices, 17), where + " NLSF");
            assertEquals(e.NLSFInterpCoef_Q2, a.NLSFInterpCoef_Q2, where + " interpolation");
            if (e.signalType == TYPE_VOICED) {
                assertEquals(e.lagIndex, a.lagIndex, where + " lag");
                assertEquals(e.contourIndex, a.contourIndex, where + " contour");
                assertEquals(e.PERIndex, a.PERIndex, where + " PER");
                assertArrayEquals(Arrays.copyOf(e.LTPIndex, MAX_NB_SUBFR), Arrays.copyOf(a.LTPIndex, MAX_NB_SUBFR),
                        where + " LTP");
                assertEquals(e.LTP_scaleIndex, a.LTP_scaleIndex, where + " LTP scale");
            }
            assertEquals(e.Seed, a.Seed, where + " seed");
        }
    }

    @Nested
    @DisplayName("voice activity detector")
    class VoiceActivity {
        private static int[] activity(short[] pcm, int fsKHz) {
            var state = new EncoderState();
            Vad.init(state.sVAD);
            state.fs_kHz = fsKHz;
            state.frame_length = 20 * fsKHz;
            var vad = new Vad();
            var frames = pcm.length / state.frame_length;
            var out = new int[frames];
            for (var f = 0; f < frames; f++) {
                assertEquals(0, vad.getSaQ8(state, pcm, f * state.frame_length));
                out[f] = state.speech_activity_Q8;
                assertTrue(state.input_tilt_Q15 >= -32768 && state.input_tilt_Q15 <= 32767);
                for (var band : state.input_quality_bands_Q15) {
                    assertTrue(band >= 0 && band <= 32767, "band quality " + band);
                }
            }
            return out;
        }

        @ParameterizedTest(name = "{0} kHz")
        @ValueSource(ints = {8, 12, 16})
        @DisplayName("reports near-zero activity for silence and high activity for a loud voiced tone")
        void silenceVersusTone(int fsKHz) {
            var fs = fsKHz * 1000;
            var pcm = new short[fs * 3];
            var random = new Random(fsKHz);
            for (var t = 0; t < pcm.length; t++) {
                var noise = random.nextInt(5) - 2;
                double tone = 0;
                if (t >= fs * 2) {
                    for (var k = 1; k <= 8; k++) {
                        tone += StrictMath.sin(2 * Math.PI * 140 * k * t / fs) / k;
                    }
                }
                pcm[t] = (short) (noise + Math.round(tone * 6000));
            }
            var activity = activity(pcm, fsKHz);
            var perSecond = activity.length / 3;
            var silenceMax = 0;
            for (var f = perSecond; f < 2 * perSecond; f++) {
                silenceMax = Math.max(silenceMax, activity[f]);
            }
            var toneMin = 255;
            for (var f = 2 * perSecond + 5; f < activity.length; f++) {
                toneMin = Math.min(toneMin, activity[f]);
            }
            assertTrue(silenceMax < 64, "silence activity " + silenceMax);
            assertTrue(toneMin > 200, "tone activity " + toneMin);
        }
    }

    @Nested
    @DisplayName("encoder API")
    class Api {
        private static SilkEncControl control() {
            var ctl = new SilkEncControl();
            ctl.nChannelsAPI = 1;
            ctl.nChannelsInternal = 1;
            ctl.API_sampleRate = 16000;
            ctl.maxInternalSampleRate = 16000;
            ctl.minInternalSampleRate = 8000;
            ctl.desiredInternalSampleRate = 16000;
            ctl.payloadSize_ms = 20;
            ctl.bitRate = 16000;
            ctl.complexity = 10;
            ctl.maxBits = 1274 * 8;
            return ctl;
        }

        private static int encode(SilkEncControl ctl, int nSamples, int prefill) {
            var encoder = new SilkEncoder();
            assertEquals(SILK_NO_ERROR, encoder.initEncoder(new SilkEncControl()));
            var buffer = new byte[1275];
            var rc = new RangeEncoder(buffer, 0, 1274);
            var pcm = new short[Math.max(nSamples, 0) * 2 + 16];
            return encoder.encode(ctl, pcm, 0, nSamples, prefill != 0 ? null : rc, new int[]{1274}, prefill, -1);
        }

        @Test
        @DisplayName("accepts a valid voice-note configuration")
        void valid() {
            assertEquals(SILK_NO_ERROR, encode(control(), 320, 0));
            assertEquals(SILK_NO_ERROR, encode(control(), 160, 1));
        }

        @Test
        @DisplayName("rejects invalid controls with the libopus error codes")
        void invalidControls() {
            var ctl = control();
            ctl.API_sampleRate = 11025;
            assertEquals(SILK_ENC_FS_NOT_SUPPORTED, encode(ctl, 220, 0));
            ctl = control();
            ctl.desiredInternalSampleRate = 24000;
            assertEquals(SILK_ENC_FS_NOT_SUPPORTED, encode(ctl, 320, 0));
            ctl = control();
            ctl.payloadSize_ms = 30;
            assertEquals(SILK_ENC_PACKET_SIZE_NOT_SUPPORTED, encode(ctl, 320, 0));
            ctl = control();
            ctl.packetLossPercentage = 101;
            assertEquals(SILK_ENC_INVALID_LOSS_RATE, encode(ctl, 320, 0));
            ctl = control();
            ctl.useDTX = 2;
            assertEquals(SILK_ENC_INVALID_DTX_SETTING, encode(ctl, 320, 0));
            ctl = control();
            ctl.useCBR = -1;
            assertEquals(SILK_ENC_INVALID_CBR_SETTING, encode(ctl, 320, 0));
            ctl = control();
            ctl.useInBandFEC = 3;
            assertEquals(SILK_ENC_INVALID_INBAND_FEC_SETTING, encode(ctl, 320, 0));
            ctl = control();
            ctl.nChannelsInternal = 2;
            assertEquals(SILK_ENC_INVALID_NUMBER_OF_CHANNELS_ERROR, encode(ctl, 320, 0));
            ctl = control();
            ctl.nChannelsAPI = 3;
            assertEquals(SILK_ENC_INVALID_NUMBER_OF_CHANNELS_ERROR, encode(ctl, 320, 0));
            ctl = control();
            ctl.complexity = 11;
            assertEquals(SILK_ENC_INVALID_COMPLEXITY_SETTING, encode(ctl, 320, 0));
        }

        @Test
        @DisplayName("rejects input lengths that are not whole 10 ms blocks of one packet")
        void invalidLengths() {
            assertEquals(SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES, encode(control(), 300, 0));
            assertEquals(SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES, encode(control(), 480, 0));
            assertEquals(SILK_ENC_INPUT_INVALID_NO_OF_SAMPLES, encode(control(), 320, 1));
        }
    }

    @Nested
    @DisplayName("encoder and decoder")
    class RoundTrip {
        private static double[] roundTrip(int apiFs, int channels, int payloadMs, int bitrate, short[] pcm) {
            return roundTrip(apiFs, channels, payloadMs, bitrate, pcm, false);
        }

        private static double[] roundTrip(int apiFs, int channels, int payloadMs, int bitrate, short[] pcm,
                                          boolean checkReconstruction) {
            var encoder = new SilkEncoder();
            var ctl = new SilkEncControl();
            assertEquals(SILK_NO_ERROR, encoder.initEncoder(ctl));
            var decoder = new SilkDecoder();
            var dctl = new SilkDecControl();
            var n = payloadMs * apiFs / 1000;
            var frame = new short[n * channels];
            var buffer = new byte[1275];
            var rc = new RangeEncoder();
            var rd = new RangeDecoder();
            var decoded = new short[pcm.length];
            var out = new short[n * channels];
            var nOut = new int[1];
            var nBytes = new int[1];
            var written = 0;
            for (var pos = 0; pos + frame.length <= pcm.length; pos += frame.length) {
                System.arraycopy(pcm, pos, frame, 0, frame.length);
                ctl.nChannelsAPI = channels;
                ctl.nChannelsInternal = channels;
                ctl.API_sampleRate = apiFs;
                ctl.maxInternalSampleRate = 16000;
                ctl.minInternalSampleRate = 8000;
                ctl.desiredInternalSampleRate = 16000;
                ctl.payloadSize_ms = payloadMs;
                ctl.bitRate = bitrate;
                ctl.complexity = 10;
                ctl.maxBits = 1274 * 8;
                rc.init(buffer, 0, 1274);
                nBytes[0] = 1274;
                assertEquals(SILK_NO_ERROR, encoder.encode(ctl, frame, 0, n, rc, nBytes, 0, -1));
                rc.encDone();
                dctl.nChannelsAPI = channels;
                dctl.nChannelsInternal = ctl.nChannelsInternal;
                dctl.API_sampleRate = apiFs;
                dctl.internalSampleRate = ctl.internalSampleRate;
                dctl.payloadSize_ms = payloadMs;
                rd.init(buffer, 0, nBytes[0]);
                var calls = payloadMs == 10 ? 1 : payloadMs / 20;
                for (var c = 0; c < calls; c++) {
                    assertEquals(SILK_NO_ERROR, decoder.decode(dctl, FLAG_DECODE_NORMAL, c == 0 ? 1 : 0, rd, out, 0, nOut));
                    var samples = nOut[0] * channels;
                    System.arraycopy(out, 0, decoded, written, samples);
                    written += samples;
                }
                if (checkReconstruction) {
                    var encoded = encoder.state_Fxx[0].sCmn;
                    var core = decoder.channel_state[0];
                    assertEquals(encoded.frame_length, core.frame_length);
                    var from = encoded.ltp_mem_length - encoded.frame_length;
                    assertArrayEquals(Arrays.copyOfRange(encoded.sNSQ.xq, from, from + encoded.frame_length),
                            Arrays.copyOfRange(core.outBuf, core.ltp_mem_length - core.frame_length, core.ltp_mem_length),
                            "packet at sample " + pos);
                }
            }
            return snrAtBestDelay(pcm, decoded, written, channels);
        }

        private static double[] snrAtBestDelay(short[] ref, short[] dec, int length, int channels) {
            var bestCorr = -1.0;
            var bestSnr = Double.NEGATIVE_INFINITY;
            var bestDelay = -1;
            for (var delay = 0; delay < 400; delay++) {
                double xy = 0, xx = 0, yy = 0, err = 0;
                for (var i = delay * channels; i < length; i++) {
                    double x = ref[i - delay * channels];
                    double y = dec[i];
                    xy += x * y;
                    xx += x * x;
                    yy += y * y;
                    err += (x - y) * (x - y);
                }
                var corr = xy / Math.sqrt(xx * yy + 1e-9);
                if (corr > bestCorr) {
                    bestCorr = corr;
                    bestSnr = 10 * Math.log10(xx / (err + 1e-9));
                    bestDelay = delay;
                }
            }
            return new double[]{bestCorr, bestSnr, bestDelay};
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"16000/20/12000/0", "16000/10/24000/0", "48000/20/32000/0", "8000/20/8000/0",
                "12000/10/10000/1", "24000/20/16000/4"})
        @DisplayName("decodes to exactly the reconstruction the encoder's quantizer predicted")
        void reconstruction(String spec) {
            var p = Arrays.stream(spec.split("/")).mapToInt(Integer::parseInt).toArray();
            var pcm = SilkEncoderHarness.signal(p[3], p[0], 1, 3.0, 0xC0FFEEL);
            roundTrip(p[0], 1, p[1], p[2], pcm, true);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"16000/1/20/20000/1", "16000/1/60/24000/1", "48000/1/20/24000/1", "48000/2/40/32000/1",
                "8000/1/10/10000/1", "16000/2/20/24000/1"})
        @DisplayName("reproduces the waveform after the codec delay")
        void waveform(String spec) {
            var p = Arrays.stream(spec.split("/")).mapToInt(Integer::parseInt).toArray();
            var pcm = SilkEncoderHarness.signal(p[4], p[0], p[1], 3.0, 0xC0FFEEL);
            var result = roundTrip(p[0], p[1], p[2], p[3], pcm);
            assertTrue(result[0] > 0.9, "correlation " + result[0]);
            assertTrue(result[1] > 6, "SNR " + result[1]);
        }
    }
}
