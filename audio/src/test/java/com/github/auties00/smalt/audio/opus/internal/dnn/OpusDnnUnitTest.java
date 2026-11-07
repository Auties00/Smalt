package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks each neural building block of the Opus 1.5 extensions against libopus 1.5.2 on deterministic inputs:
 * the activation approximations, the LPCNet feature analysis, the Burg cepstrum, the FARGAN vocoder, the deep
 * PLC (with DRED features queued before one burst), the DRED RDOVAE decoder and the LACE and NoLACE enhancers.
 * The references are {@code dnn-units.bin}, written by {@code tools/testdata/opus-oracle/dnnunit.c} with the
 * generic C build of the networks; the inputs come from the same integer and float recipes on both sides.
 */
class OpusDnnUnitTest {
    private static final int FRAMES = 60;
    private static final int SAMPLES = FRAMES * 160;

    private static Map<String, Object> references;
    private static short[] signal;
    private static float[] features;
    private int lcg;

    @BeforeAll
    static void load() throws IOException {
        references = new HashMap<>();
        byte[] bytes;
        try (var in = OpusDnnUnitTest.class.getResourceAsStream("dnn-units.bin")) {
            assertNotNull(in, "dnn-units.bin");
            bytes = in.readAllBytes();
        }
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            var name = new byte[16];
            buffer.get(name);
            var end = 0;
            while (end < 16 && name[end] != 0) {
                end++;
            }
            var count = buffer.getInt();
            var type = buffer.getInt();
            if (type == 0) {
                var values = new float[count];
                buffer.asFloatBuffer().get(values);
                buffer.position(buffer.position() + 4 * count);
                references.put(new String(name, 0, end, StandardCharsets.US_ASCII), values);
            } else {
                var values = new short[count];
                buffer.asShortBuffer().get(values);
                buffer.position(buffer.position() + 2 * count);
                references.put(new String(name, 0, end, StandardCharsets.US_ASCII), values);
            }
        }
        signal = new OpusDnnUnitTest().signal16k();
        features = new float[FRAMES * LpcnetEncoder.NB_TOTAL_FEATURES];
        var encoder = new LpcnetEncoder(PitchDnnModel.get(), new Nnet(), new Freq());
        var x = new float[SAMPLES];
        for (var i = 0; i < SAMPLES; i++) {
            x[i] = signal[i];
        }
        for (var f = 0; f < FRAMES; f++) {
            encoder.computeSingleFrameFeatures(x, f * 160, features, f * LpcnetEncoder.NB_TOTAL_FEATURES);
        }
    }

    private float lcg() {
        lcg = lcg * 1664525 + 1013904223;
        return (float) ((lcg >>> 8) & 0xFFFFFF) * (1.f / 8388608.f) - 1.f;
    }

    private short[] signal16k() {
        var out = new short[SAMPLES];
        float y1 = 0;
        float y2 = 0;
        lcg = 1;
        for (var n = 0; n < SAMPLES; n++) {
            var period = 60 + ((n / 1600) % 5) * 15;
            var e = (n % period == 0 ? 6000.f : 0.f) + 300.f * lcg();
            var y = e + 1.6f * y1 - 0.8f * y2;
            y2 = y1;
            y1 = y;
            if (y > 32767.f) {
                y = 32767.f;
            }
            if (y < -32768.f) {
                y = -32768.f;
            }
            out[n] = (short) (int) y;
        }
        return out;
    }

    private static float[] floats(String name) {
        return (float[]) references.get(name);
    }

    private static short[] shorts(String name) {
        return (short[]) references.get(name);
    }

    private static void assertSame(String what, float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length, what + " length");
        for (var i = 0; i < expected.length; i++) {
            if (Float.floatToIntBits(expected[i]) != Float.floatToIntBits(actual[i])) {
                Tolerance.exact().check(what + " (first difference at " + i + ": " + expected[i] + " vs " + actual[i]
                        + ")", Tolerance.Difference.ofPcm(expected, actual));
            }
        }
    }

    private static void assertSame(String what, short[] expected, short[] actual) {
        assertEquals(expected.length, actual.length, what + " length");
        for (var i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                Tolerance.exact().check(what + " (first difference at " + i + ": " + expected[i] + " vs " + actual[i]
                        + ")", Tolerance.Difference.ofPcm(expected, actual));
            }
        }
    }

    @Test
    @DisplayName("generates the reference test signal")
    void signalMatches() {
        assertArrayEquals(shorts("signal"), signal);
    }

    @Test
    @DisplayName("approximates tanh and the sigmoid like vec.h")
    void activations() {
        var actual = new float[2 * 2001];
        for (var i = 0; i <= 2000; i++) {
            var v = (i - 1000) * 0.01f;
            actual[i] = Nnet.tanhApprox(v);
            actual[2001 + i] = Nnet.sigmoidApprox(v);
        }
        assertSame("activations", floats("activations"), actual);
    }

    @Test
    @DisplayName("computes the LPCNet features of every frame")
    void lpcnetFeatures() {
        assertSame("features", floats("features"), features);
    }

    @Test
    @DisplayName("computes the Burg cepstrum of every frame")
    void burgCepstrum() {
        var freq = new Freq();
        var actual = new float[FRAMES * 2 * Freq.NB_BANDS];
        var ceps = new float[2 * Freq.NB_BANDS];
        var frame = new float[160];
        for (var f = 0; f < FRAMES; f++) {
            for (var i = 0; i < 160; i++) {
                frame[i] = signal[f * 160 + i];
            }
            freq.burgCepstralAnalysis(ceps, frame);
            System.arraycopy(ceps, 0, actual, f * 2 * Freq.NB_BANDS, 2 * Freq.NB_BANDS);
        }
        assertSame("burg", floats("burg"), actual);
    }

    @Test
    @DisplayName("primes the FARGAN vocoder and synthesizes from features")
    void fargan() {
        var fargan = new Fargan(FarganModel.get(), new Nnet());
        var cont = new float[5 * LpcnetEncoder.NB_FEATURES];
        for (var i = 0; i < 5; i++) {
            System.arraycopy(features, i * LpcnetEncoder.NB_TOTAL_FEATURES, cont, i * LpcnetEncoder.NB_FEATURES,
                    LpcnetEncoder.NB_FEATURES);
        }
        var pcm0 = new float[Fargan.CONT_SAMPLES];
        for (var i = 0; i < Fargan.CONT_SAMPLES; i++) {
            pcm0[i] = signal[480 + i] * (1.f / 32768.f);
        }
        fargan.cont(pcm0, 0, cont);
        var actual = new short[(FRAMES - 5) * 160];
        var frame = new float[LpcnetEncoder.NB_TOTAL_FEATURES];
        for (var f = 5; f < FRAMES; f++) {
            System.arraycopy(features, f * LpcnetEncoder.NB_TOTAL_FEATURES, frame, 0, LpcnetEncoder.NB_TOTAL_FEATURES);
            fargan.synthesize(actual, (f - 5) * 160, frame);
        }
        assertSame("fargan", shorts("fargan"), actual);
    }

    @Test
    @DisplayName("conceals bursts of lost frames, taking queued DRED features first")
    void deepPlc() {
        var plc = new LpcnetPlc();
        var actual = new short[SAMPLES];
        for (var f = 0; f < FRAMES; f++) {
            var lost = (f >= 20 && f < 26) || (f >= 36 && f < 39) || f == 45 || (f >= 50 && f < 56);
            if (f == 50) {
                for (var k = 0; k < 4; k++) {
                    plc.fecAdd(features, (48 + k) * LpcnetEncoder.NB_TOTAL_FEATURES);
                }
                plc.fecAdd(null, 0);
            }
            if (lost) {
                plc.conceal(actual, f * 160);
            } else {
                System.arraycopy(signal, f * 160, actual, f * 160, 160);
                plc.update(actual, f * 160);
            }
        }
        assertSame("plc", shorts("plc"), actual);
    }

    @Test
    @DisplayName("decodes DRED latents into features with the RDOVAE decoder")
    void rdovae() {
        var state = new float[Dred.STATE_DIM];
        var latents = new float[26 * Dred.LATENT_DIM];
        lcg = 7;
        for (var i = 0; i < state.length; i++) {
            state[i] = 2.f * lcg();
        }
        for (var i = 0; i < latents.length; i++) {
            latents[i] = 3.f * lcg();
        }
        var actual = new float[2 * 52 * Dred.NUM_FEATURES];
        new Dred.RdovaeDecoder().decodeAll(actual, state, latents, 26);
        assertSame("rdovae", floats("rdovae"), actual);
    }

    @ParameterizedTest(name = "method {0}")
    @ValueSource(ints = {Osce.METHOD_LACE, Osce.METHOD_NOLACE})
    @DisplayName("enhances 20 ms SILK frames with LACE and NoLACE")
    void osce(int method) {
        var osce = new Osce();
        osce.reset(method);
        var predCoef = new short[2][16];
        var pitchL = new int[4];
        var gains = new int[4];
        var ltp = new short[20];
        var actual = new short[20 * 320];
        lcg = 11 + method;
        for (var fr = 0; fr < 20; fr++) {
            System.arraycopy(signal, fr * 320, actual, fr * 320, 320);
            var signalType = fr % 5 == 4 ? 1 : 2;
            for (var h = 0; h < 2; h++) {
                for (var i = 0; i < 16; i++) {
                    predCoef[h][i] = (short) (int) (4096.f * 0.9f * lcg() / (i + 1));
                }
            }
            for (var k = 0; k < 4; k++) {
                pitchL[k] = 40 + (int) (100.f * (lcg() + 1.f));
                gains[k] = 65536 * (50 + (int) (40.f * lcg()));
                for (var i = 0; i < 5; i++) {
                    ltp[k * 5 + i] = (short) (int) (4000.f * lcg());
                }
            }
            var numBits = 300 + (int) (200.f * lcg());
            osce.enhance(actual, fr * 320, 16, 4, 16, signalType, predCoef, pitchL, ltp, gains, numBits);
        }
        assertSame(method == Osce.METHOD_LACE ? "lace" : "nolace",
                shorts(method == Osce.METHOD_LACE ? "lace" : "nolace"), actual);
    }

    @Test
    @DisplayName("loads every model blob with the layer sizes of libopus 1.5.2")
    void models() {
        assertNotNull(PitchDnnModel.get());
        assertNotNull(FarganModel.get());
        assertNotNull(PlcModel.get());
        assertNotNull(RdovaeEncModel.get());
        assertNotNull(RdovaeDecModel.get());
        assertNotNull(LaceModel.get());
        assertNotNull(NolaceModel.get());
        assertThrows(IllegalStateException.class, () -> new WeightBlob(new byte[]{'D', 'N', 'N', 'w', 0}));
        var blob = new WeightBlob(new byte[0]);
        assertThrows(IllegalStateException.class, () -> LinearLayer.of(blob, "missing", null, null, null, null,
                null, null, 4, 8));
    }
}
