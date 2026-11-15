/* Ports libopus 1.5.2 (dnn/dred_encoder.c, dnn/dred_rdovae_enc.c, dnn/dred_coding.c, celt/laplace.c
   (ec_laplace_encode_p0)). Copyright Amazon, CSIRO and Xiph.Org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;
import com.github.auties00.smalt.audio.opus.internal.celt.RangeEncoder;

import java.util.Arrays;

/**
 * Computes the Deep REDundancy of an Opus encoder ({@code DREDEnc}): it resamples the input to 16 kHz, computes
 * LPCNet features every 10 ms, compresses each pair of frames into a latent vector and an initial state with the
 * RDOVAE encoder, and codes the newest latents, coarser with age, into the DRED extension payload.
 *
 * <p>The payload is bit-identical to that of libopus 1.5.2 built with DRED and the generic C code of its networks.
 * Instances are not thread-safe.
 */
public final class DredEncoder {
    /**
     * The largest DRED duration, in 10 ms frames ({@code DRED_MAX_FRAMES}).
     */
    public static final int MAX_FRAMES = 4 * Dred.MAX_LATENTS;

    /**
     * The largest payload ({@code DRED_MAX_DATA_SIZE}).
     */
    public static final int MAX_DATA_SIZE = 1000;

    /**
     * The smallest payload worth sending ({@code DRED_MIN_BYTES}).
     */
    public static final int MIN_BYTES = 8;

    private static final int FRAME_SIZE = 160;
    private static final int SILK_ENCODER_DELAY = 79 + 12 - 80;
    private static final int RESAMPLING_ORDER = 8;
    private static final int LATENT_DIM = Dred.LATENT_DIM;
    private static final int STATE_DIM = Dred.STATE_DIM;
    private static final int NUM_FEATURES = Dred.NUM_FEATURES;
    private static final int GRU = RdovaeEncModel.ENC_GRU1_STATE_SIZE;

    /**
     * The 48 and 24 kHz anti-aliasing filter, {@code ellip(7, .2, 70, 7750/24000)}.
     */
    private static final float[] B_48K = {0.005873358047f, 0.012980854831f, 0.014531340042f, 0.014531340042f,
        0.012980854831f, 0.005873358047f, 0.004523418224f, 0.f};
    private static final float[] A_48K = {-3.878718597768f, 7.748834257468f, -9.653651699533f, 8.007342726666f,
        -4.379450178552f, 1.463182111810f, -0.231720677804f, 0.f};
    private static final float B0_48K = 0.004523418224f;

    /**
     * The 12 kHz filter.
     */
    private static final float[] B_12K = {-0.001017101081f, 0.003673127243f, 0.001009165267f, 0.001009165267f,
        0.003673127243f, -0.001017101081f, 0.002033596776f, 0.f};
    private static final float[] A_12K = {-4.930414411612f, 11.291643096504f, -15.322037343815f, 13.216403930898f,
        -7.220409219553f, 2.310550142771f, -0.334338618782f, 0.f};
    private static final float B0_12K = 0.002033596776f;

    /**
     * The 8 kHz interpolation filter, {@code ellip(7, .2, 70, 3900/8000)}.
     */
    private static final float[] B_8K = {0.081670120929f, 0.180401598565f, 0.259391051971f, 0.259391051971f,
        0.180401598565f, 0.081670120929f, 0.020109185709f, 0.f};
    private static final float[] A_8K = {-1.393651933659f, 2.609789872676f, -2.403541968806f, 2.056814957331f,
        -1.148908574570f, 0.473001413788f, -0.110359852412f, 0.f};
    private static final float B0_8K = 0.020109185709f;

    private final RdovaeEncModel model;
    private final Nnet nnet = new Nnet();
    private final LpcnetEncoder lpcnet;
    private final int fs;
    private final int channels;

    private final float[] inputBuffer = new float[4 * FRAME_SIZE];
    private int inputBufferFill;
    private int dredOffset;
    private int latentOffset;
    private int lastExtraDredOffset;
    private final float[] latentsBuffer = new float[MAX_FRAMES * LATENT_DIM];

    /**
     * The number of latents computed, at most {@code 2 * MAX_LATENTS}.
     */
    private int latentsBufferFill;

    private final float[] stateBuffer = new float[MAX_FRAMES * STATE_DIM];
    private final float[] resampleMem = new float[RESAMPLING_ORDER + 1];

    private final float[][] gruState = new float[5][GRU];
    private final float[] conv1State = new float[RdovaeEncModel.ENC_CONV1_STATE_SIZE];
    private final float[][] convState = {
        new float[2 * RdovaeEncModel.ENC_CONV2_STATE_SIZE], new float[2 * RdovaeEncModel.ENC_CONV3_STATE_SIZE],
        new float[2 * RdovaeEncModel.ENC_CONV4_STATE_SIZE], new float[2 * RdovaeEncModel.ENC_CONV5_STATE_SIZE]
    };
    private final LinearLayer[] gruIn;
    private final LinearLayer[] gruRec;
    private final LinearLayer[] conv;

    private final float[] featureBuffer = new float[2 * LpcnetEncoder.NB_TOTAL_FEATURES];
    private final float[] dframe = new float[2 * NUM_FEATURES];
    private final float[] buffer = new float[RdovaeEncModel.ENC_DENSE1_OUT_SIZE + 5 * GRU
            + 5 * RdovaeEncModel.ENC_CONV1_OUT_SIZE];
    private final float[] paddedLatents = new float[RdovaeEncModel.ENC_ZDENSE_OUT_SIZE];
    private final float[] paddedState = new float[RdovaeEncModel.GDENSE2_OUT_SIZE];
    private final float[] stateHidden = new float[RdovaeEncModel.GDENSE1_OUT_SIZE];
    private final float[] downmix = new float[960 * 2];
    private final RangeEncoder ec = new RangeEncoder();
    private final RangeEncoder ecBak = new RangeEncoder();
    private final int[] q = new int[LATENT_DIM];
    private final float[] xq = new float[LATENT_DIM];
    private final float[] delta = new float[LATENT_DIM];
    private final float[] deadzone = new float[LATENT_DIM];
    private final int[] signIcdf = new int[3];
    private final int[] icdf = new int[8];

    /**
     * Constructs an encoder for an Opus encoder's input ({@code dred_encoder_init}).
     *
     * @param fs the input sampling rate: 8000, 12000, 16000, 24000 or 48000 Hz
     * @param channels the number of interleaved input channels, 1 or 2
     * @throws IllegalStateException if a model cannot be loaded
     */
    public DredEncoder(int fs, int channels) {
        var m = RdovaeEncModel.get();
        this.model = m;
        this.fs = fs;
        this.channels = channels;
        this.lpcnet = new LpcnetEncoder(PitchDnnModel.get(), nnet, new Freq());
        this.gruIn = new LinearLayer[]{m.enc_gru1_input, m.enc_gru2_input, m.enc_gru3_input, m.enc_gru4_input,
            m.enc_gru5_input};
        this.gruRec = new LinearLayer[]{m.enc_gru1_recurrent, m.enc_gru2_recurrent, m.enc_gru3_recurrent,
            m.enc_gru4_recurrent, m.enc_gru5_recurrent};
        this.conv = new LinearLayer[]{m.enc_conv2, m.enc_conv3, m.enc_conv4, m.enc_conv5};
        reset();
    }

    /**
     * Clears the state ({@code dred_encoder_reset}).
     */
    public void reset() {
        Arrays.fill(inputBuffer, 0);
        inputBufferFill = SILK_ENCODER_DELAY;
        dredOffset = 0;
        latentOffset = 0;
        lastExtraDredOffset = 0;
        Arrays.fill(latentsBuffer, 0);
        latentsBufferFill = 0;
        Arrays.fill(stateBuffer, 0);
        Arrays.fill(resampleMem, 0);
        lpcnet.reset();
        for (var row : gruState) {
            Arrays.fill(row, 0);
        }
        Arrays.fill(conv1State, 0);
        for (var row : convState) {
            Arrays.fill(row, 0);
        }
    }

    /**
     * Forgets the computed latents, as an encoder does while DRED is off ({@code latents_buffer_fill = 0}).
     */
    public void clearLatents() {
        latentsBufferFill = 0;
    }

    /**
     * Encodes a double feature frame into a latent vector and an initial state ({@code dred_rdovae_encode_dframe}).
     */
    private void encodeDframe() {
        var outputIndex = 0;
        nnet.dense(model.enc_dense1, buffer, outputIndex, dframe, 0, Nnet.ACTIVATION_TANH);
        outputIndex += RdovaeEncModel.ENC_DENSE1_OUT_SIZE;
        nnet.gru(gruIn[0], gruRec[0], gruState[0], 0, buffer, 0);
        System.arraycopy(gruState[0], 0, buffer, outputIndex, GRU);
        outputIndex += GRU;
        nnet.conv1d(model.enc_conv1, buffer, outputIndex, conv1State, 0, buffer, 0, outputIndex, Nnet.ACTIVATION_TANH);
        outputIndex += RdovaeEncModel.ENC_CONV1_OUT_SIZE;
        for (var k = 1; k < 5; k++) {
            nnet.gru(gruIn[k], gruRec[k], gruState[k], 0, buffer, 0);
            System.arraycopy(gruState[k], 0, buffer, outputIndex, GRU);
            outputIndex += GRU;
            nnet.conv1dDilation(conv[k - 1], buffer, outputIndex, convState[k - 1], 0, buffer, 0, outputIndex, 2,
                    Nnet.ACTIVATION_TANH);
            outputIndex += RdovaeEncModel.ENC_CONV1_OUT_SIZE;
        }
        nnet.dense(model.enc_zdense, paddedLatents, 0, buffer, 0, Nnet.ACTIVATION_LINEAR);
        System.arraycopy(paddedLatents, 0, latentsBuffer, 0, LATENT_DIM);
        nnet.dense(model.gdense1, stateHidden, 0, buffer, 0, Nnet.ACTIVATION_TANH);
        nnet.dense(model.gdense2, paddedState, 0, stateHidden, 0, Nnet.ACTIVATION_LINEAR);
        System.arraycopy(paddedState, 0, stateBuffer, 0, STATE_DIM);
    }

    /**
     * Computes the latent of the 20 ms in the input buffer ({@code dred_process_frame}).
     */
    private void processFrame() {
        System.arraycopy(latentsBuffer, 0, latentsBuffer, LATENT_DIM, (MAX_FRAMES - 1) * LATENT_DIM);
        System.arraycopy(stateBuffer, 0, stateBuffer, STATE_DIM, (MAX_FRAMES - 1) * STATE_DIM);
        lpcnet.computeSingleFrameFeatures(inputBuffer, 0, featureBuffer, 0);
        lpcnet.computeSingleFrameFeatures(inputBuffer, FRAME_SIZE, featureBuffer, LpcnetEncoder.NB_TOTAL_FEATURES);
        System.arraycopy(featureBuffer, 0, dframe, 0, NUM_FEATURES);
        System.arraycopy(featureBuffer, LpcnetEncoder.NB_TOTAL_FEATURES, dframe, NUM_FEATURES, NUM_FEATURES);
        encodeDframe();
        latentsBufferFill = Math.min(latentsBufferFill + 1, Dred.NUM_REDUNDANCY_FRAMES);
    }

    /**
     * Filters with a direct form II transposed IIR filter ({@code filter_df2t}).
     */
    private static void filterDf2t(float[] in, float[] out, int outOffset, int len, float b0, float[] b, float[] a,
                                   int order, float[] mem) {
        for (var i = 0; i < len; i++) {
            var xi = in[i];
            var yi = xi * b0 + mem[0];
            var nyi = -yi;
            for (var j = 0; j < order; j++) {
                mem[j] = mem[j + 1] + b[j] * xi + a[j] * nyi;
            }
            out[outOffset + i] = yi;
        }
    }

    /**
     * Downmixes and resamples input to 16 kHz in 16-bit range ({@code dred_convert_to_16k}).
     */
    private void convertTo16k(float[] in, int inOffset, int inLen, float[] out, int outOffset, int outLen) {
        var up = switch (fs) {
            case 8000, 24000 -> 2;
            case 12000 -> 4;
            default -> 1;
        };
        Arrays.fill(downmix, 0, up * inLen, 0);
        if (channels == 1) {
            for (var i = 0; i < inLen; i++) {
                downmix[up * i] = MathOps.float2int16(up * in[inOffset + i]);
            }
        } else {
            for (var i = 0; i < inLen; i++) {
                var mixed = (float) (.5 * up * (in[inOffset + 2 * i] + in[inOffset + 2 * i + 1]));
                downmix[up * i] = MathOps.float2int16(mixed);
            }
        }
        switch (fs) {
            case 16000 -> System.arraycopy(downmix, 0, out, outOffset, outLen);
            case 48000, 24000 -> {
                filterDf2t(downmix, downmix, 0, up * inLen, B0_48K, B_48K, A_48K, RESAMPLING_ORDER, resampleMem);
                for (var i = 0; i < outLen; i++) {
                    out[outOffset + i] = downmix[3 * i];
                }
            }
            case 12000 -> {
                filterDf2t(downmix, downmix, 0, up * inLen, B0_12K, B_12K, A_12K, RESAMPLING_ORDER, resampleMem);
                for (var i = 0; i < outLen; i++) {
                    out[outOffset + i] = downmix[3 * i];
                }
            }
            default -> filterDf2t(downmix, out, outOffset, outLen, B0_8K, B_8K, A_8K, RESAMPLING_ORDER, resampleMem);
        }
    }

    /**
     * Feeds one frame of encoder input ({@code dred_compute_latents}).
     *
     * @param pcm the interleaved input in {@code [-1, 1]}
     * @param pcmOffset the index of the first sample
     * @param frameSize the number of samples per channel
     * @param extraDelay the encoder delay compensation in samples at the input rate
     */
    public void computeLatents(float[] pcm, int pcmOffset, int frameSize, int extraDelay) {
        var frameSize16k = frameSize * 16000 / fs;
        var currOffset16k = 40 + extraDelay * 16000 / fs - inputBufferFill;
        dredOffset = (int) StrictMath.floor((currOffset16k + 20.f) / 40.f);
        latentOffset = 0;
        while (frameSize16k > 0) {
            var processSize16k = Math.min(2 * FRAME_SIZE, frameSize16k);
            var processSize = processSize16k * fs / 16000;
            convertTo16k(pcm, pcmOffset, processSize, inputBuffer, inputBufferFill, processSize16k);
            inputBufferFill += processSize16k;
            if (inputBufferFill >= 2 * FRAME_SIZE) {
                currOffset16k += 320;
                processFrame();
                inputBufferFill -= 2 * FRAME_SIZE;
                System.arraycopy(inputBuffer, 2 * FRAME_SIZE, inputBuffer, 0, inputBufferFill);
                // 15 ms (6 * 2.5 ms) is the ideal offset because it is the vocoder look-ahead.
                if (dredOffset < 6) {
                    dredOffset += 8;
                } else {
                    latentOffset++;
                }
            }
            // libopus advances by the samples of one channel, also for stereo input.
            pcmOffset += processSize;
            frameSize16k -= processSize16k;
        }
    }

    /**
     * Encodes one value with a Laplace distribution and an explicit probability of zero
     * ({@code ec_laplace_encode_p0}).
     */
    private void laplaceEncodeP0(RangeEncoder enc, int value, int p0, int decay) {
        signIcdf[0] = 32768 - p0;
        signIcdf[1] = signIcdf[0] / 2;
        signIcdf[2] = 0;
        var s = value == 0 ? 0 : value > 0 ? 1 : 2;
        enc.encIcdf16(s, signIcdf, 15);
        value = Math.abs(value);
        if (value != 0) {
            icdf[0] = Math.max(7, decay);
            for (var i = 1; i < 7; i++) {
                icdf[i] = Math.max(7 - i, (icdf[i - 1] * decay) >> 15);
            }
            icdf[7] = 0;
            value--;
            do {
                enc.encIcdf16(Math.min(value, 7), icdf, 15);
                value -= 7;
            } while (value >= 0);
        }
    }

    /**
     * Quantizes with a dead zone and codes a latent or state vector ({@code dred_encode_latents}).
     */
    private void encodeLatents(RangeEncoder enc, float[] x, int xOffset, short[] scale, short[] dzone, short[] r,
                               short[] p0, int tableOffset, int dim) {
        final var eps = .1f;
        for (var i = 0; i < dim; i++) {
            delta[i] = dzone[tableOffset + i] * (1.f / 256.f);
            xq[i] = x[xOffset + i] * scale[tableOffset + i] * (1.f / 256.f);
            deadzone[i] = xq[i] / (delta[i] + eps);
        }
        nnet.activation(deadzone, 0, deadzone, 0, dim, Nnet.ACTIVATION_TANH);
        for (var i = 0; i < dim; i++) {
            xq[i] = xq[i] - delta[i] * deadzone[i];
            q[i] = (int) StrictMath.floor(.5f + xq[i]);
        }
        for (var i = 0; i < dim; i++) {
            var ri = r[tableOffset + i];
            var pi = p0[tableOffset + i];
            if (ri != 0 && pi != 255) {
                laplaceEncodeP0(enc, q[i], pi << 7, ri << 7);
            }
        }
    }

    /**
     * {@return whether any 2.5 ms unit of a 40 ms latent was active}
     */
    private static boolean voiceActive(byte[] activityMem, int offset) {
        for (var i = 0; i < 16; i++) {
            if (activityMem[8 * offset + i] == 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Codes the newest latents into a DRED payload ({@code dred_encode_silk_frame}).
     *
     * @param buf receives the payload
     * @param bufOffset the index of its first byte
     * @param maxChunks the largest number of latent pairs to code
     * @param maxBytes the space available
     * @param q0 the quantizer of the newest latent
     * @param dQ the quantizer step index
     * @param qmax the largest quantizer
     * @param activityMem the activity of the last 2.5 ms units, newest first, followed by the 8 bytes of encoder state
     * that libopus reads past its activity buffer when no latent is active
     * @return the payload length, or 0 when nothing worth sending fits
     */
    public int encode(byte[] buf, int bufOffset, int maxChunks, int maxBytes, int q0, int dQ, int qmax,
                      byte[] activityMem) {
        var offset = latentOffset;
        var extraDredOffset = 0;
        var delayedDred = false;
        if (activityMem[0] != 0 && lastExtraDredOffset > 0) {
            offset = lastExtraDredOffset;
            delayedDred = true;
            lastExtraDredOffset = 0;
        }
        while (offset < latentsBufferFill && !voiceActive(activityMem, offset)) {
            offset++;
            extraDredOffset++;
        }
        if (!delayedDred) {
            lastExtraDredOffset = extraDredOffset;
        }
        ec.init(buf, bufOffset, maxBytes);
        ec.encUint(q0, 16);
        ec.encUint(dQ, 8);
        var totalOffset = 16 - (dredOffset - extraDredOffset * 8);
        if (totalOffset > 31) {
            ec.encUint(1, 2);
            ec.encUint(totalOffset >> 5, 256);
            ec.encUint(totalOffset & 31, 32);
        } else {
            ec.encUint(0, 2);
            ec.encUint(totalOffset, 32);
        }
        if (q0 < 14 && dQ > 0) {
            var nvals = 15 - (q0 + 1);
            ec.encode(qmax >= 15 ? 0 : nvals + qmax - (q0 + 1), qmax >= 15 ? nvals : nvals + qmax - q0, 2 * nvals);
        }
        encodeLatents(ec, stateBuffer, offset * STATE_DIM, DredStats.DRED_STATE_QUANT_SCALES_Q8,
                DredStats.DRED_STATE_DEAD_ZONE_Q8, DredStats.DRED_STATE_R_Q8, DredStats.DRED_STATE_P0_Q8,
                q0 * STATE_DIM, STATE_DIM);
        if (ec.tell() > 8 * maxBytes) {
            return 0;
        }
        ecBak.copyFrom(ec);
        var prevActive = false;
        var dredEncoded = 0;
        for (var i = 0; i < Math.min(2 * maxChunks, latentsBufferFill - offset - 1); i += 2) {
            var qLevel = Dred.quantizer(q0, dQ, qmax, i / 2);
            encodeLatents(ec, latentsBuffer, (i + offset) * LATENT_DIM, DredStats.DRED_LATENT_QUANT_SCALES_Q8,
                    DredStats.DRED_LATENT_DEAD_ZONE_Q8, DredStats.DRED_LATENT_R_Q8, DredStats.DRED_LATENT_P0_Q8,
                    qLevel * LATENT_DIM, LATENT_DIM);
            if (ec.tell() > 8 * maxBytes) {
                if (i == 0) {
                    return 0;
                }
                break;
            }
            var active = voiceActive(activityMem, i + offset);
            if (active || prevActive) {
                ecBak.copyFrom(ec);
                dredEncoded = i + 2;
            }
            prevActive = active;
        }
        if (dredEncoded == 0 || (dredEncoded <= 2 && extraDredOffset != 0)) {
            return 0;
        }
        ec.copyFrom(ecBak);
        var fill = (ec.tell() + 7) / 8;
        ec.encShrink(fill);
        ec.encDone();
        return fill;
    }
}
