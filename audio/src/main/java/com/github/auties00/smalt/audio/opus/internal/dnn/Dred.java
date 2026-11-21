/* Ports libopus 1.5.2 (dnn/dred_decoder.c, dnn/dred_rdovae_dec.c, dnn/dred_coding.c, dnn/dred_config.h,
   celt/laplace.c (ec_laplace_decode_p0), src/opus_decoder.c (dred_find_payload, opus_dred_parse,
   opus_dred_process)). Copyright Amazon, CSIRO and Xiph.Org Foundation; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.dnn;

import com.github.auties00.smalt.audio.opus.internal.OpusExtensions;
import com.github.auties00.smalt.audio.opus.internal.OpusPacket;
import com.github.auties00.smalt.audio.opus.internal.celt.RangeDecoder;

import java.util.Arrays;

/**
 * Holds the Deep REDundancy (DRED) of one packet ({@code OpusDRED}): the entropy-decoded latents and initial
 * state of the redundancy, and once processed, the LPCNet features of up to 104 frames of 10 ms that precede the
 * packet, newest first.
 *
 * <p>DRED travels in the packet's padding as extension {@value #EXTENSION_ID}, whose payload starts with the
 * experimental marker {@code 'D', 10}. Instances are reusable and not thread-safe.
 */
public final class Dred {
    /**
     * The extension id of DRED ({@code DRED_EXTENSION_ID}).
     */
    public static final int EXTENSION_ID = 126;

    /**
     * The version byte that follows {@code 'D'} in the payload ({@code DRED_EXPERIMENTAL_VERSION}).
     */
    public static final int EXPERIMENTAL_VERSION = 10;

    /**
     * The number of marker bytes before the coded redundancy ({@code DRED_EXPERIMENTAL_BYTES}).
     */
    public static final int EXPERIMENTAL_BYTES = 2;

    /**
     * The number of features of one frame ({@code DRED_NUM_FEATURES}).
     */
    public static final int NUM_FEATURES = 20;

    /**
     * The size of a latent vector ({@code DRED_LATENT_DIM}).
     */
    static final int LATENT_DIM = 21;

    /**
     * The size of the initial state ({@code DRED_STATE_DIM}).
     */
    static final int STATE_DIM = 19;

    /**
     * The largest number of latents of a packet, each covering 40 ms ({@code DRED_MAX_LATENTS}).
     */
    public static final int MAX_LATENTS = 26;

    /**
     * The number of redundancy frame pairs ({@code DRED_NUM_REDUNDANCY_FRAMES}).
     */
    static final int NUM_REDUNDANCY_FRAMES = 2 * MAX_LATENTS;

    /**
     * The quantizer step, in sixteenths per latent, of each quantizer step index ({@code dQ_table}).
     */
    private static final int[] DQ_TABLE = {0, 2, 3, 4, 6, 8, 12, 16};

    /**
     * The decoded features, four 10 ms frames per latent, newest first.
     */
    final float[] fecFeatures = new float[2 * NUM_REDUNDANCY_FRAMES * NUM_FEATURES];

    final float[] state = new float[STATE_DIM];
    final float[] latents = new float[(NUM_REDUNDANCY_FRAMES / 2) * LATENT_DIM];

    /**
     * The number of decoded latents.
     */
    int nbLatents;

    /**
     * {@code -1} when empty, {@code 1} once entropy decoded, {@code 2} once the features are computed.
     */
    int processStage = -1;

    /**
     * The position of the newest redundancy relative to the start of the packet, in 2.5 ms units.
     */
    int dredOffset;

    private final RangeDecoder ec = new RangeDecoder();
    private final OpusPacket.Layout layout = new OpusPacket.Layout();
    private final int[] signIcdf = new int[3];
    private final int[] icdf = new int[8];
    private final int[] found = new int[3];

    /**
     * Constructs an empty holder.
     */
    public Dred() {
    }

    /**
     * {@return the number of decoded latents, each covering 40 ms of features}
     */
    public int latentCount() {
        return nbLatents;
    }

    /**
     * {@return the position of the newest redundancy relative to the start of the packet, in 2.5 ms units;
     * negative when the redundancy ends before the packet starts}
     */
    public int offset() {
        return dredOffset;
    }

    /**
     * {@return whether the holder holds entropy-decoded or processed redundancy}
     */
    public boolean isParsed() {
        return processStage == 1 || processStage == 2;
    }

    /**
     * {@return whether the holder holds processed features}
     */
    public boolean isProcessed() {
        return processStage == 2;
    }

    /**
     * Copies the processed features of one 10 ms frame.
     *
     * @param index the frame index, 0 for the newest
     * @param out receives {@link #NUM_FEATURES} features
     */
    public void features(int index, float[] out) {
        System.arraycopy(fecFeatures, index * NUM_FEATURES, out, 0, NUM_FEATURES);
    }

    /**
     * Queues the features that cover a lost span in the deep PLC, as {@code opus_decode_native} does before
     * concealing with DRED.
     *
     * @param plc the deep PLC of the decoder
     * @param dredOffset the position of the start of the lost span before the packet that carried the
     * redundancy, in samples at {@code fs}
     * @param frameSize the length of the span in samples at {@code fs}
     * @param fs the output sampling rate of the decoder
     * @throws IllegalStateException if the redundancy is not processed
     */
    public void queueFeatures(LpcnetPlc plc, int dredOffset, int frameSize, int fs) {
        if (processStage != 2) {
            throw new IllegalStateException("DRED not processed");
        }
        var f10 = fs / 100;
        // Two extra frames prime the vocoder when the last PLC call received a frame.
        var initFrames = plc.blend ? 0 : 2;
        var featuresPerFrame = Math.max(1, frameSize / f10);
        var needed = initFrames + featuresPerFrame;
        plc.fecClear();
        for (var i = 0; i < needed; i++) {
            // floor instead of rounding: the 5 ms overlap compensates for the missing 0.5 offset.
            var featureOffset = initFrames - i - 2
                    + (int) StrictMath.floor(((float) dredOffset + this.dredOffset * f10 / 4) / f10);
            if (featureOffset <= 4 * nbLatents - 1 && featureOffset >= 0) {
                plc.fecAdd(fecFeatures, featureOffset * NUM_FEATURES);
            } else if (featureOffset >= 0) {
                plc.fecAdd(null, 0);
            }
        }
    }

    /**
     * Empties the holder.
     */
    public void clear() {
        processStage = -1;
        nbLatents = 0;
        dredOffset = 0;
    }

    /**
     * Copies another holder.
     *
     * @param other the source
     */
    public void copyFrom(Dred other) {
        System.arraycopy(other.fecFeatures, 0, fecFeatures, 0, fecFeatures.length);
        System.arraycopy(other.state, 0, state, 0, STATE_DIM);
        System.arraycopy(other.latents, 0, latents, 0, latents.length);
        nbLatents = other.nbLatents;
        processStage = other.processStage;
        dredOffset = other.dredOffset;
    }

    /**
     * Finds the DRED payload of the earliest frame that carries one ({@code dred_find_payload}).
     *
     * @param data the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param len the packet length
     * @param layout receives the frame layout of the packet
     * @param out receives the payload index at 0, its length at 1 and the frame offset in 2.5 ms units at 2
     * @return {@code 1} if a payload was found, {@code 0} if not, or a negative {@code OPUS_*} error code
     */
    public static int findPayload(byte[] data, int offset, int len, OpusPacket.Layout layout, int[] out) {
        var ret = OpusPacket.parseImpl(data, offset, len, false, layout);
        if (ret < 0) {
            return ret;
        }
        var frameSize = OpusPacket.getSamplesPerFrame(data[offset] & 0xFF, 48000);
        var pos = offset + layout.paddingOffset;
        var remaining = layout.paddingLen;
        var frame = 0;
        while (remaining > 0) {
            var id = (data[pos] & 0xFF) >> 1;
            var L = data[pos] & 1;
            var skipped = OpusExtensions.skip(data, pos, remaining);
            if (skipped < 0) {
                break;
            }
            var header = (int) skipped;
            var left = (int) (skipped >>> 32);
            var next = pos + remaining - left;
            if (id == 1) {
                frame += L == 0 ? 1 : data[pos + 1] & 0xFF;
            } else if (id == EXTENSION_ID) {
                var payload = pos + header;
                var payloadLen = next - pos - header;
                out[2] = frame * frameSize / 120;
                if (payloadLen > EXPERIMENTAL_BYTES && data[payload] == 'D'
                        && data[payload + 1] == EXPERIMENTAL_VERSION) {
                    out[0] = payload + EXPERIMENTAL_BYTES;
                    out[1] = payloadLen - EXPERIMENTAL_BYTES;
                    return 1;
                }
            }
            remaining = left;
            pos = next;
        }
        return 0;
    }

    /**
     * Returns the quantizer of a latent ({@code compute_quantizer}).
     *
     * @param q0 the quantizer of the newest latent
     * @param dQ the quantizer step index, from 0 to 7
     * @param qmax the largest quantizer
     * @param i the latent index
     * @return the quantizer
     */
    public static int quantizer(int q0, int dQ, int qmax, int i) {
        var quant = q0 + (DQ_TABLE[dQ] * i + 8) / 16;
        return Math.min(quant, qmax);
    }

    /**
     * Decodes one Laplace-distributed value with an explicit probability of zero ({@code ec_laplace_decode_p0}).
     */
    private int laplaceDecodeP0(int p0, int decay) {
        signIcdf[0] = 32768 - p0;
        signIcdf[1] = signIcdf[0] / 2;
        signIcdf[2] = 0;
        var s = ec.decIcdf16(signIcdf, 15);
        if (s == 2) {
            s = -1;
        }
        if (s == 0) {
            return 0;
        }
        icdf[0] = Math.max(7, decay);
        for (var i = 1; i < 7; i++) {
            icdf[i] = Math.max(7 - i, (icdf[i - 1] * decay) >> 15);
        }
        icdf[7] = 0;
        var value = 1;
        int v;
        do {
            v = ec.decIcdf16(icdf, 15);
            value += v;
        } while (v == 7);
        return s * value;
    }

    /**
     * Decodes a latent vector ({@code dred_decode_latents}).
     */
    private void decodeLatents(float[] x, int xOffset, short[] scale, short[] r, short[] p0, int tableOffset,
                               int dim) {
        for (var i = 0; i < dim; i++) {
            var ri = r[tableOffset + i];
            var pi = p0[tableOffset + i];
            var q = ri == 0 || pi == 255 ? 0 : laplaceDecodeP0(pi << 7, ri << 7);
            var si = scale[tableOffset + i];
            x[xOffset + i] = q * 256.f / (si == 0 ? 1 : si);
        }
    }

    /**
     * Entropy decodes a DRED payload ({@code dred_ec_decode}).
     *
     * @param bytes the buffer holding the payload
     * @param offset the index of its first byte
     * @param numBytes its length
     * @param minFeatureFrames the number of 10 ms feature frames wanted
     * @param dredFrameOffset the position of the carrying frame in the packet, in 2.5 ms units
     * @return the number of decoded latents
     */
    int ecDecode(byte[] bytes, int offset, int numBytes, int minFeatureFrames, int dredFrameOffset) {
        ec.init(bytes, offset, numBytes);
        var q0 = ec.decUint(16);
        var dQ = ec.decUint(8);
        var extraOffset = ec.decUint(2) != 0 ? 32 * ec.decUint(256) : 0;
        dredOffset = 16 - ec.decUint(32) - extraOffset + dredFrameOffset;
        var qmax = 15;
        if (q0 < 14 && dQ > 0) {
            var nvals = 15 - (q0 + 1);
            var ft = 2 * nvals;
            var s = ec.decode(ft);
            if (s >= nvals) {
                qmax = q0 + (s - nvals) + 1;
                ec.decUpdate(s, s + 1, ft);
            } else {
                ec.decUpdate(0, nvals, ft);
            }
        }
        decodeLatents(state, 0, DredStats.DRED_STATE_QUANT_SCALES_Q8, DredStats.DRED_STATE_R_Q8,
                DredStats.DRED_STATE_P0_Q8, q0 * STATE_DIM, STATE_DIM);
        var i = 0;
        for (; i < Math.min(NUM_REDUNDANCY_FRAMES, (minFeatureFrames + 1) / 2); i += 2) {
            // libopus misses a last latent that would take fewer than 8 bits (its FIXME).
            if (8 * numBytes - ec.tell() <= 7) {
                break;
            }
            var qLevel = quantizer(q0, dQ, qmax, i / 2);
            decodeLatents(latents, (i / 2) * LATENT_DIM, DredStats.DRED_LATENT_QUANT_SCALES_Q8,
                    DredStats.DRED_LATENT_R_Q8, DredStats.DRED_LATENT_P0_Q8, qLevel * LATENT_DIM, LATENT_DIM);
        }
        processStage = 1;
        nbLatents = i / 2;
        return i / 2;
    }

    /**
     * Parses the DRED of a packet ({@code opus_dred_parse}) and, unless deferred, computes its features.
     *
     * @param decoder the network that computes the features
     * @param data the buffer holding the packet
     * @param offset the index of the table-of-contents byte
     * @param len the packet length
     * @param maxDredSamples the largest amount of redundancy wanted, in samples at {@code samplingRate}
     * @param samplingRate the sampling rate the sample counts refer to
     * @param end receives at index 0 the number of samples of silence that end the redundancy, or {@code null}
     * @param deferProcessing whether to leave the features for {@link #process}
     * @return the number of samples of redundancy available before the packet, or a negative {@code OPUS_*} error
     * code if the packet is invalid
     */
    public int parse(RdovaeDecoder decoder, byte[] data, int offset, int len, int maxDredSamples, int samplingRate,
                     int[] end, boolean deferProcessing) {
        processStage = -1;
        var ret = findPayload(data, offset, len, layout, found);
        if (ret < 0) {
            return ret;
        }
        if (ret > 0) {
            // In long: a large maximum must not wrap to a negative frame count.
            var frames = 100L * maxDredSamples / samplingRate;
            var minFeatureFrames = (int) Math.min(2 + frames, 2 * NUM_REDUNDANCY_FRAMES);
            ecDecode(data, found[0], found[1], minFeatureFrames, found[2]);
            if (!deferProcessing) {
                process(decoder);
            }
            if (end != null) {
                end[0] = Math.max(0, -dredOffset * samplingRate / 400);
            }
            return Math.max(0, nbLatents * samplingRate / 25 - dredOffset * samplingRate / 400);
        }
        if (end != null) {
            end[0] = 0;
        }
        return 0;
    }

    /**
     * Computes the features of entropy-decoded redundancy ({@code opus_dred_process}); processed redundancy is
     * left as it is.
     *
     * @param decoder the network that computes the features
     * @throws IllegalStateException if the holder holds no redundancy
     */
    public void process(RdovaeDecoder decoder) {
        if (processStage != 1 && processStage != 2) {
            throw new IllegalStateException("no DRED to process");
        }
        if (processStage == 2) {
            return;
        }
        decoder.decodeAll(fecFeatures, state, latents, nbLatents);
        processStage = 2;
    }

    /**
     * Decodes DRED latents into features with the RDOVAE decoder ({@code RDOVAEDecState}).
     *
     * <p>Instances own their scratch space and are not thread-safe.
     */
    public static final class RdovaeDecoder {
        private static final int GRU = RdovaeDecModel.DEC_GRU1_STATE_SIZE;
        private final RdovaeDecModel model;
        private final Nnet nnet = new Nnet();
        private final float[][] gruState = new float[5][GRU];
        private final float[][] convState = {
            new float[RdovaeDecModel.DEC_CONV1_STATE_SIZE], new float[RdovaeDecModel.DEC_CONV2_STATE_SIZE],
            new float[RdovaeDecModel.DEC_CONV3_STATE_SIZE], new float[RdovaeDecModel.DEC_CONV4_STATE_SIZE],
            new float[RdovaeDecModel.DEC_CONV5_STATE_SIZE]
        };
        private final float[] hidden = new float[RdovaeDecModel.DEC_HIDDEN_INIT_OUT_SIZE];
        private final float[] stateInit = new float[RdovaeDecModel.DEC_GRU_INIT_OUT_SIZE];
        private final float[] buffer = new float[RdovaeDecModel.DEC_DENSE1_OUT_SIZE + 5 * GRU
                + 5 * RdovaeDecModel.DEC_CONV1_OUT_SIZE];
        private final LinearLayer[] gruIn;
        private final LinearLayer[] gruRec;
        private final LinearLayer[] glu;
        private final LinearLayer[] conv;

        /**
         * Constructs a decoder, loading its network on first use.
         *
         * @throws IllegalStateException if the model cannot be loaded
         */
        public RdovaeDecoder() {
            var m = RdovaeDecModel.get();
            this.model = m;
            this.gruIn = new LinearLayer[]{m.dec_gru1_input, m.dec_gru2_input, m.dec_gru3_input, m.dec_gru4_input,
                m.dec_gru5_input};
            this.gruRec = new LinearLayer[]{m.dec_gru1_recurrent, m.dec_gru2_recurrent, m.dec_gru3_recurrent,
                m.dec_gru4_recurrent, m.dec_gru5_recurrent};
            this.glu = new LinearLayer[]{m.dec_glu1, m.dec_glu2, m.dec_glu3, m.dec_glu4, m.dec_glu5};
            this.conv = new LinearLayer[]{m.dec_conv1, m.dec_conv2, m.dec_conv3, m.dec_conv4, m.dec_conv5};
        }

        /**
         * Decodes every latent of a packet ({@code DRED_rdovae_decode_all}).
         */
        void decodeAll(float[] features, float[] state, float[] latents, int nbLatents) {
            for (var row : gruState) {
                Arrays.fill(row, 0);
            }
            for (var row : convState) {
                Arrays.fill(row, 0);
            }
            nnet.dense(model.dec_hidden_init, hidden, 0, state, 0, Nnet.ACTIVATION_TANH);
            nnet.dense(model.dec_gru_init, stateInit, 0, hidden, 0, Nnet.ACTIVATION_TANH);
            for (var k = 0; k < 5; k++) {
                System.arraycopy(stateInit, k * GRU, gruState[k], 0, GRU);
            }
            for (var i = 0; i < 2 * nbLatents; i += 2) {
                decodeQframe(features, 2 * i * NUM_FEATURES, latents, (i / 2) * LATENT_DIM);
            }
        }

        /**
         * Decodes one latent into four frames of features, newest first ({@code dred_rdovae_decode_qframe}).
         */
        private void decodeQframe(float[] qframe, int qframeOffset, float[] input, int inputOffset) {
            var outputIndex = 0;
            nnet.dense(model.dec_dense1, buffer, outputIndex, input, inputOffset, Nnet.ACTIVATION_TANH);
            outputIndex += RdovaeDecModel.DEC_DENSE1_OUT_SIZE;
            for (var k = 0; k < 5; k++) {
                nnet.gru(gruIn[k], gruRec[k], gruState[k], 0, buffer, 0);
                nnet.glu(glu[k], buffer, outputIndex, gruState[k], 0);
                outputIndex += GRU;
                nnet.conv1d(conv[k], buffer, outputIndex, convState[k], 0, buffer, 0, outputIndex,
                        Nnet.ACTIVATION_TANH);
                outputIndex += RdovaeDecModel.DEC_CONV1_OUT_SIZE;
            }
            nnet.dense(model.dec_output, qframe, qframeOffset, buffer, 0, Nnet.ACTIVATION_LINEAR);
        }
    }
}
