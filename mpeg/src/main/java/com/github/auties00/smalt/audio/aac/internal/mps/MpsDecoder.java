/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_dec.c SpatialDecOpen and
   SpatialDecApplyFrame; sac_process.c SpatialDecMDCT2QMF, SpatialDecHybridQMFAnalysis, SpatialDecDecorrelate,
   SpatialDecMergeResDecor, SpatialDecCreateX and SpatialDecUpdateBuffers; sac_calcM1andM2.c SpatialDecApplyM1,
   SpatialDecApplyM2, interp_UMX and applyAbsKernels; sac_TPprocess.c subbandTP and tpProcess; sac_reshapeBBEnv.c
   extractBBEnv and spatialDecReshapeBBEnv), for the trees of ISO/IEC 23003-1 other than the 2-1-2 tree. Copyright
   Agere Systems, Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software licence
   (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.Arrays;

/**
 * Decodes MPEG Surround (ISO/IEC 23003-1): it upmixes a mono, stereo or 5.1 downmix into 5.1 or 7.1 channels with the
 * spatial parameters of one {@code SpatialFrame()} per spatial frame.
 *
 * <p>The decoder takes the downmix either as time samples, which it splits with its own QMF bank, or as QMF slots
 * (the output of an SBR decoder), and returns time samples in the scale of the downmix. The output channels are in the
 * order left, right, center, LFE, left surround, right surround and, for the 7.1 trees, the two added channels (left
 * and right center for trees 3 and 5, left and right rear surround for trees 4 and 6). The arbitrary tree extension is
 * skipped and the base layout decoded; binaural, 3D stereo and blind upmixing are not implemented.
 *
 * <p>Instances keep the state of one stream; they are not thread-safe.
 */
public final class MpsDecoder {
    private static final float ABS_THR = 1e-9f * 32768 * 32768;
    private static final int[] KERNEL_BANDS = {4, 5, 7, 10, 14, 20, 28};
    private static final float[] SURROUND_GAINS = {1.000000f, 1.189207f, 1.414213f, 1.681792f, 2.000000f};
    private static final float[] LFE_GAINS = {1.000000f, 3.162277f, 10.000000f, 31.622776f, 100.000000f};
    private static final float[] CLIP_GAINS = {1.000000f, 1.189207f, 1.414213f, 1.681792f, 2.000000f, 2.378414f,
            2.828427f, 4.000000f};
    private static final int[][] ROW_TO_CHANNEL_STP = {
            {0, 1, 2, -1, 3, 4, -1, -1},
            {0, 3, 1, 4, 2, -1, -1, -1},
            {0, 2, 1, 3, -1, -1, -1, -1},
            {0, 4, 2, 1, 5, 3, -1, -1},
            {0, 4, 2, 1, 5, 3, -1, -1},
            {0, 2, -1, 1, 3, -1, -1, -1},
            {-1, 2, 0, -1, 3, 1, -1, -1}
    };
    private static final int[][] ROW_TO_CHANNEL_GES = {
            {0, 1, 2, -1, 3, 4, -1, -1},
            {0, 3, 1, 4, 2, -1, -1, -1},
            {0, 3, 1, 4, 2, -1, -1, -1},
            {0, 5, 3, 1, 6, 4, 2, -1},
            {0, 5, 3, 1, 6, 4, 2, -1},
            {0, 2, -1, 1, 3, -1, -1, -1},
            {-1, 2, 0, -1, 3, 1, -1, -1}
    };
    private static final int[][] ROW_TO_RESIDUAL = {
            {-1, 0, 1, 3, 2, 4},
            {-1, 0, 1, 3, 4, 2},
            {-1, -1, -1, 1, 2, 0},
            {-1, -1, -1, 1, 2, 0, 3, 4},
            {-1, -1, -1, 1, 2, 0, 3, 4},
            {-1, -1, -1, -1, -1, -1, 0, 1},
            {-1, -1, -1, -1, -1, -1, 0, 1}
    };
    private static final int TP_BORDER = 12;
    private static final int BP_SIZE = 25;
    private static final int HP_LIMIT = 9 - 3 + 10;
    private static final float[] STP_BP = {0.0000f, 0.0005f, 0.0092f, 0.0587f, 0.2580f, 0.7392f, 0.9791f, 0.9993f,
            1.0000f, 1.0000f, 1.0000f, 1.0000f, 0.9999f, 0.9984f, 0.9908f, 0.9639f, 0.8952f, 0.7711f, 0.6127f,
            0.4609f, 0.3391f, 0.2493f, 0.1848f, 0.1387f, 0.1053f};
    private static final float[] STP_GF = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 1e-008f, 8.1e-007f, 3.61e-006f, 8.41e-006f,
            1.6e-005f, 2.704e-005f, 3.969e-005f, 5.625e-005f, 7.396e-005f, 9.801e-005f, 0.00012321f, 0.00015625f,
            0.00019881f, 0.00024964f, 0.00032041f, 0.00041209f, 0.00053824f, 0.00070756f, 0.00094249f};
    private static final float[] STP_BP2 = new float[BP_SIZE];
    private static final int[] HYBRID_TO_QMF = {0, 0, 0, 0, 0, 0, 1, 1, 2, 2};
    private static final double STP_LPF_COEFF1 = 0.950;
    private static final double STP_LPF_COEFF2 = 0.450;
    private static final int STP_UPDATE_ENERGY_RATE = 32;
    private static final double STP_SCALE_LIMIT = 2.82;
    private static final float GES_ALPHA = (float) StrictMath.exp(-64 / (0.4f * 44100));
    private static final float GES_BETA = (float) StrictMath.exp(-64 / (0.04f * 44100));

    static {
        for (var n = 0; n < BP_SIZE; n++) {
            STP_BP2[n] = STP_BP[n] * STP_BP[n];
        }
    }

    private final MpsConfig config;
    private final MpsParameters parameters;
    private final MpsMatrices matrices;
    private final int tree;
    private final int slots;
    private final int qmfBands;
    private final int hybridBands;
    private final int bands;
    private final int inputs;
    private final int outputs;
    private final int ottCount;
    private final int tttCount;
    private final int direct;
    private final int decorrelated;
    private final int vChannels;
    private final int wChannels;
    private final int xChannels;
    private final int[] kernelBand;
    private final int[] kernelSign;
    private final float clipGain;
    private final float surroundGain;
    private final float lfeGain;
    private final boolean residualCoding;
    private final int arbitraryDownmix;

    private final MpsPolyphase polyphase;
    private final double[][] analysisStates;
    private final double[][] synthesisStates;
    private final MpsHybrid[] inputHybrid;
    private final MpsHybrid[] residualHybrid;
    private final MpsDecorrelator[] decorrelators;
    private final MpsMdctToQmf converter;

    private final float[][][] qmfInReal;
    private final float[][][] qmfInImag;
    private final float[][][] hybridInReal;
    private final float[][][] hybridInImag;
    private final float[][][] qmfResidualReal;
    private final float[][][] qmfResidualImag;
    private final float[][][] hybridResidualReal;
    private final float[][][] hybridResidualImag;
    private final float[][][] xReal;
    private final float[][][] xImag;
    private final float[][][] vReal;
    private final float[][][] vImag;
    private final float[][][] wDryReal;
    private final float[][][] wDryImag;
    private final float[][][] wWetReal;
    private final float[][][] wWetImag;
    private final float[][][] dryReal;
    private final float[][][] dryImag;
    private final float[][][] wetReal;
    private final float[][][] wetImag;
    private final float[][] qmfOutReal;
    private final float[][] qmfOutImag;
    private final double[][] timeOut;

    private final float[][] r1Real;
    private final float[][] r1Imag;
    private final float[][] r2Decor;
    private final float[][] r2Resid;

    private final float[] runDryEnergy = new float[6];
    private final float[] runWetEnergy = new float[8];
    private final float[] oldDryEnergy = new float[6];
    private final float[] oldWetEnergy = new float[8];
    private final float[] previousStpScale = new float[8];
    private int stpUpdateCounter;
    private final float[][] stpDryReal = new float[8][BP_SIZE];
    private final float[][] stpDryImag = new float[8][BP_SIZE];
    private final float[][] stpWetReal = new float[8][BP_SIZE];
    private final float[][] stpWetImag = new float[8][BP_SIZE];
    private final float[][] stpDownmixReal = new float[6][BP_SIZE];
    private final float[][] stpDownmixImag = new float[6][BP_SIZE];
    private final float[] dryEnergy = new float[6];
    private final float[] wetEnergy = new float[8];
    private final float[] stpScale = new float[8];

    private final float[][] gesPartPrevious = new float[2 * 8 + 6][MpsParameters.MAX_BANDS];
    private final float[] gesNormPrevious = new float[2 * 8 + 6];
    private final float[] gesFramePrevious = new float[2 * 8 + 6];
    private final float[][] gesSlotEnergy;
    private final float[] gesPart = new float[MpsParameters.MAX_BANDS];
    private final float[] gesEnvelopeDry;
    private final float[][] gesEnvelopeDownmix;

    /**
     * Constructs a decoder.
     *
     * @param config the {@code SpatialSpecificConfig}
     * @param sampleRate the sampling frequency of the downmix and the output, which selects the QMF bank: 32 subbands
     *                   below 27713 Hz, 128 from 55426 Hz and 64 between
     * @throws MediaException.Malformed if the configuration is inconsistent
     * @throws MediaException.Unsupported if residual frames need a longer MDCT than the reference decoder supports
     */
    public MpsDecoder(MpsConfig config, int sampleRate) {
        this.config = config;
        this.qmfBands = sampleRate < 27713 ? 32 : sampleRate >= 55426 ? 128 : 64;
        this.hybridBands = MpsHybrid.bands(qmfBands);
        this.parameters = new MpsParameters(config, qmfBands);
        this.tree = parameters.tree;
        this.slots = parameters.slots;
        this.bands = parameters.bands;
        this.inputs = parameters.inputs;
        this.outputs = parameters.outputs;
        this.ottCount = parameters.ottCount;
        this.tttCount = parameters.tttCount;
        this.residualCoding = config.residualCoding;
        this.arbitraryDownmix = config.arbitraryDownmix;
        var kernel = MpsTables.KERNELS[Arrays.binarySearch(KERNEL_BANDS, bands)];
        this.kernelBand = new int[hybridBands];
        this.kernelSign = new int[hybridBands];
        for (var i = 0; i < hybridBands; i++) {
            var index = Math.min(i, kernel.length / 2 - 1);
            kernelBand[i] = kernel[2 * index];
            kernelSign[i] = kernel[2 * index + 1];
        }
        this.clipGain = CLIP_GAINS[config.fixedGainDownmix];
        this.surroundGain = SURROUND_GAINS[config.fixedGainSurround];
        this.lfeGain = LFE_GAINS[config.fixedGainLfe];
        switch (tree) {
            case MpsConfig.TREE_5151, MpsConfig.TREE_5152 -> {
                direct = 1;
                decorrelated = 4;
                xChannels = arbitraryDownmix == 2 ? 2 : 1;
            }
            case MpsConfig.TREE_525 -> {
                direct = 3;
                decorrelated = parameters.tttDecorrelated[1] ? 3 : 2;
                xChannels = arbitraryDownmix == 2 ? 5 : residualCoding ? 3 : 2;
            }
            case MpsConfig.TREE_7271, MpsConfig.TREE_7272 -> {
                direct = 3;
                decorrelated = 5;
                xChannels = arbitraryDownmix == 2 ? 5 : residualCoding ? 3 : 2;
            }
            default -> {
                direct = 6;
                decorrelated = 2;
                xChannels = 6;
            }
        }
        this.vChannels = direct + decorrelated;
        this.wChannels = vChannels;
        this.matrices = new MpsMatrices(parameters, xChannels);
        this.polyphase = new MpsPolyphase(qmfBands);
        this.analysisStates = new double[inputs][];
        for (var i = 0; i < inputs; i++) {
            analysisStates[i] = polyphase.analysisState();
        }
        this.synthesisStates = new double[outputs][];
        for (var i = 0; i < outputs; i++) {
            synthesisStates[i] = polyphase.synthesisState();
        }
        this.inputHybrid = new MpsHybrid[inputs];
        for (var i = 0; i < inputs; i++) {
            inputHybrid[i] = new MpsHybrid(slots, qmfBands);
        }
        this.residualHybrid = new MpsHybrid[10];
        for (var i = 0; i < 10; i++) {
            residualHybrid[i] = new MpsHybrid(slots, qmfBands);
        }
        this.decorrelators = new MpsDecorrelator[decorrelated];
        for (var i = 0; i < decorrelated; i++) {
            decorrelators[i] = new MpsDecorrelator(slots, config.decorrelator, hybridBands);
        }
        var anyResidual = residualCoding || arbitraryDownmix == 2;
        this.converter = anyResidual ? new MpsMdctToQmf(qmfBands) : null;
        this.qmfInReal = new float[inputs][slots][qmfBands];
        this.qmfInImag = new float[inputs][slots][qmfBands];
        this.hybridInReal = new float[inputs][slots][hybridBands];
        this.hybridInImag = new float[inputs][slots][hybridBands];
        var residualChannels = anyResidual ? 10 : 0;
        this.qmfResidualReal = new float[residualChannels][2 * slots][qmfBands];
        this.qmfResidualImag = new float[residualChannels][2 * slots][qmfBands];
        this.hybridResidualReal = new float[residualChannels][slots][hybridBands];
        this.hybridResidualImag = new float[residualChannels][slots][hybridBands];
        this.xReal = new float[xChannels][slots][hybridBands];
        this.xImag = new float[xChannels][slots][hybridBands];
        this.vReal = new float[vChannels][slots][hybridBands];
        this.vImag = new float[vChannels][slots][hybridBands];
        var wRows = tttCount > 0 ? Math.max(wChannels, 6) : wChannels;
        this.wDryReal = new float[wChannels][slots][hybridBands];
        this.wDryImag = new float[wChannels][slots][hybridBands];
        this.wWetReal = new float[wRows][slots][hybridBands];
        this.wWetImag = new float[wRows][slots][hybridBands];
        this.dryReal = new float[outputs][slots][hybridBands];
        this.dryImag = new float[outputs][slots][hybridBands];
        this.wetReal = new float[outputs][slots][hybridBands];
        this.wetImag = new float[outputs][slots][hybridBands];
        this.qmfOutReal = new float[slots][qmfBands];
        this.qmfOutImag = new float[slots][qmfBands];
        this.timeOut = new double[outputs][slots * qmfBands];
        this.r1Real = new float[bands][MpsMatrices.STRIDE * MpsMatrices.STRIDE];
        this.r1Imag = new float[bands][MpsMatrices.STRIDE * MpsMatrices.STRIDE];
        this.r2Decor = new float[bands][MpsMatrices.STRIDE * MpsMatrices.STRIDE];
        this.r2Resid = new float[bands][MpsMatrices.STRIDE * MpsMatrices.STRIDE];
        Arrays.fill(oldDryEnergy, 32768 * 32768);
        Arrays.fill(oldWetEnergy, 32768 * 32768);
        Arrays.fill(previousStpScale, 1.0f);
        Arrays.fill(gesNormPrevious, 32768.f * 32768.f);
        this.gesSlotEnergy = new float[slots][MpsParameters.MAX_BANDS];
        this.gesEnvelopeDry = new float[slots];
        this.gesEnvelopeDownmix = new float[2][slots];
    }

    /**
     * {@return the number of QMF subbands}
     */
    public int qmfBands() {
        return qmfBands;
    }

    /**
     * {@return the number of QMF slots of a spatial frame}
     */
    public int slots() {
        return slots;
    }

    /**
     * {@return the number of samples per channel of a spatial frame}
     */
    public int frameLength() {
        return slots * qmfBands;
    }

    /**
     * {@return the number of downmix channels}
     */
    public int inputChannels() {
        return inputs;
    }

    /**
     * {@return the number of output channels}
     */
    public int outputChannels() {
        return outputs;
    }

    /**
     * {@return the configuration}
     */
    public MpsConfig config() {
        return config;
    }

    /**
     * Reads the {@code SpatialFrame()} of the next spatial frame.
     *
     * @param reader the bit source, positioned at the frame
     * @throws MediaException.Malformed if the frame is invalid
     */
    public void readFrame(AacBitReader reader) {
        parameters.read(reader);
    }

    /**
     * Returns whether a {@code SpatialFrame()} has its {@code bsIndependencyFlag} set, so that it decodes without the
     * frames before it.
     *
     * @param reader the bit source, positioned at the frame; it is left after the flag
     * @return {@code true} for an independent frame
     */
    public boolean independentFrame(AacBitReader reader) {
        return MpsParameters.independent(reader, slots);
    }

    /**
     * Keeps the parameters of the last frame read for the next spatial frame, as for a frame whose side
     * information is missing.
     */
    public void repeatFrame() {
        parameters.repeat();
    }

    /**
     * Upmixes one spatial frame of time samples, with the parameters of the last frame read.
     *
     * @param input the downmix channels, {@link #frameLength()} samples each from {@code inputOffset}
     * @param inputOffset the index of the first input sample
     * @param output receives the output channels, {@link #frameLength()} samples each from {@code outputOffset}
     * @param outputOffset the index of the first output sample
     */
    public void decode(float[][] input, int inputOffset, float[][] output, int outputOffset) {
        for (var ch = 0; ch < inputs; ch++) {
            for (var ts = 0; ts < slots; ts++) {
                polyphase.analyze(analysisStates[ch], input[ch], inputOffset + ts * qmfBands, qmfInReal[ch][ts],
                        qmfInImag[ch][ts]);
                scale(qmfInReal[ch][ts]);
                scale(qmfInImag[ch][ts]);
            }
        }
        process(output, outputOffset);
    }

    /**
     * Upmixes one spatial frame of QMF slots, with the parameters of the last frame read.
     *
     * @param inputReal the real parts of the downmix, {@code [channel][slot][band]}
     * @param inputImag the imaginary parts of the downmix
     * @param output receives the output channels, {@link #frameLength()} samples each from {@code outputOffset}
     * @param outputOffset the index of the first output sample
     */
    public void decodeQmf(float[][][] inputReal, float[][][] inputImag, float[][] output, int outputOffset) {
        for (var ch = 0; ch < inputs; ch++) {
            for (var ts = 0; ts < slots; ts++) {
                for (var qs = 0; qs < qmfBands; qs++) {
                    qmfInReal[ch][ts][qs] = clipGain * inputReal[ch][ts][qs];
                    qmfInImag[ch][ts][qs] = clipGain * inputImag[ch][ts][qs];
                }
            }
        }
        process(output, outputOffset);
    }

    private void scale(float[] values) {
        for (var i = 0; i < qmfBands; i++) {
            values[i] = clipGain * values[i];
        }
    }

    private void process(float[][] output, int outputOffset) {
        residualToQmf();
        hybridAnalysis();
        createX();
        matrices.calculate();
        applyM1();
        decorrelate();
        mergeResidual();
        applyM2();
        if (config.temporalShaping == 2) {
            reshapeEnvelope();
        }
        temporalProcess();
        for (var n = 0; n < slots * qmfBands; n++) {
            timeOut[3][n] *= lfeGain;
            timeOut[4][n] *= surroundGain;
            timeOut[5][n] *= surroundGain;
            if (tree == MpsConfig.TREE_7272 || tree == MpsConfig.TREE_7572) {
                timeOut[6][n] *= surroundGain;
                timeOut[7][n] *= surroundGain;
            }
        }
        for (var ch = 0; ch < outputs; ch++) {
            var source = timeOut[ch];
            var target = output[ch];
            for (var n = 0; n < slots * qmfBands; n++) {
                target[outputOffset + n] = (float) source[n];
            }
        }
        updateBuffers();
    }

    private void residualToQmf() {
        if (!parameters.residualFresh) {
            return;
        }
        if (residualCoding) {
            for (var box = 0; box < ottCount + tttCount; box++) {
                if (parameters.residualBands[box] <= 0) {
                    continue;
                }
                var offset = 0;
                for (var frame = 0; frame < parameters.residualFrames; frame++) {
                    converter.process(parameters.residualUpdate, parameters.residualSpectra[box][frame],
                            qmfResidualReal[box], qmfResidualImag[box], parameters.residualWindows[box][frame],
                            offset);
                    offset += parameters.residualUpdate;
                }
            }
        }
        if (arbitraryDownmix == 2) {
            var base = ottCount + tttCount;
            for (var ch = 0; ch < inputs; ch++) {
                var offset = 0;
                for (var frame = 0; frame < parameters.downmixResidualFrames; frame++) {
                    converter.process(parameters.downmixResidualUpdate, parameters.residualSpectra[base + ch][frame],
                            qmfResidualReal[base + ch], qmfResidualImag[base + ch],
                            parameters.residualWindows[base + ch][frame], offset);
                    offset += parameters.downmixResidualUpdate;
                }
            }
        }
    }

    private void hybridAnalysis() {
        for (var ch = 0; ch < inputs; ch++) {
            inputHybrid[ch].analyze(qmfInReal[ch], qmfInImag[ch], hybridInReal[ch], hybridInImag[ch]);
        }
        if (residualCoding) {
            for (var box = 0; box < ottCount + tttCount; box++) {
                if (parameters.residualBands[box] > 0) {
                    residualHybrid[box].analyze(qmfResidualReal[box], qmfResidualImag[box], hybridResidualReal[box],
                            hybridResidualImag[box]);
                }
            }
        }
        if (arbitraryDownmix == 2) {
            var base = ottCount + tttCount;
            for (var ch = 0; ch < inputs; ch++) {
                residualHybrid[base + ch].analyze(qmfResidualReal[base + ch], qmfResidualImag[base + ch],
                        hybridResidualReal[base + ch], hybridResidualImag[base + ch]);
            }
        }
    }

    private void createX() {
        for (var ts = 0; ts < slots; ts++) {
            for (var qs = 0; qs < hybridBands; qs++) {
                var index = kernelBand[qs];
                for (var row = 0; row < inputs && row < xChannels; row++) {
                    xReal[row][ts][qs] = hybridInReal[row][ts][qs];
                    xImag[row][ts][qs] = hybridInImag[row][ts][qs];
                }
                var res = ottCount;
                for (var row = inputs; row < inputs + tttCount; row++, res++) {
                    if (row >= xChannels) {
                        continue;
                    }
                    if (residualCoding && index < parameters.residualBands[res]) {
                        xReal[row][ts][qs] = hybridResidualReal[res][ts][qs];
                        xImag[row][ts][qs] = hybridResidualImag[res][ts][qs];
                    } else {
                        xReal[row][ts][qs] = 0.0f;
                        xImag[row][ts][qs] = 0.0f;
                    }
                }
                if (arbitraryDownmix == 2) {
                    for (var row = inputs + tttCount; row < inputs + tttCount + inputs; row++, res++) {
                        if (index < parameters.downmixResidualBands) {
                            xReal[row][ts][qs] = hybridResidualReal[res][ts][qs];
                            xImag[row][ts][qs] = hybridResidualImag[res][ts][qs];
                        } else {
                            xReal[row][ts][qs] = 0.0f;
                            xImag[row][ts][qs] = 0.0f;
                        }
                    }
                }
            }
        }
    }

    /**
     * Interpolates the matrices of the parameter sets for one slot ({@code interp_UMX}).
     */
    private void interpolate(float[][][] matrix, float[][] previous, int ts, float[][] out, int rows, int columns) {
        var ps = 0;
        while (parameters.parameterSlot[ps] < ts) {
            ps++;
        }
        var previousSlot = ps == 0 ? -1.0f : (float) parameters.parameterSlot[ps - 1];
        var currentSlot = (float) parameters.parameterSlot[ps];
        var alpha = ts / (currentSlot - previousSlot) - previousSlot / (currentSlot - previousSlot);
        var from = ps == 0 ? null : matrix[ps - 1];
        var to = matrix[ps];
        for (var pb = 0; pb < bands; pb++) {
            var a = from == null ? previous[pb] : from[pb];
            var b = to[pb];
            var r = out[pb];
            for (var row = 0; row < rows; row++) {
                for (var col = 0; col < columns; col++) {
                    var i = row * MpsMatrices.STRIDE + col;
                    r[i] = a[i] * (1 - alpha) + alpha * b[i];
                }
            }
        }
    }

    private void applyM1() {
        for (var ts = 0; ts < slots; ts++) {
            interpolate(matrices.m1Real, matrices.m1RealPrevious, ts, r1Real, vChannels, xChannels);
            interpolate(matrices.m1Imag, matrices.m1ImagPrevious, ts, r1Imag, vChannels, xChannels);
            for (var qs = 0; qs < hybridBands; qs++) {
                var rr = r1Real[kernelBand[qs]];
                var ri = r1Imag[kernelBand[qs]];
                var sign = kernelSign[qs];
                for (var row = 0; row < vChannels; row++) {
                    var re = 0.0f;
                    var im = 0.0f;
                    for (var col = 0; col < xChannels; col++) {
                        var cr = rr[row * MpsMatrices.STRIDE + col];
                        var ci = ri[row * MpsMatrices.STRIDE + col];
                        var xr = xReal[col][ts][qs];
                        var xi = xImag[col][ts][qs];
                        var real = xr * cr - xi * ci * sign;
                        var imag = xr * ci * sign + xi * cr;
                        re += real;
                        im += imag;
                    }
                    vReal[row][ts][qs] = re;
                    vImag[row][ts][qs] = im;
                }
            }
        }
    }

    private void decorrelate() {
        for (var k = direct; k < direct + decorrelated; k++) {
            decorrelators[k - direct].apply(vReal[k], vImag[k], wWetReal[k], wWetImag[k]);
        }
    }

    private void mergeResidual() {
        var residualRows = ROW_TO_RESIDUAL[tree];
        for (var ts = 0; ts < slots; ts++) {
            for (var qs = 0; qs < hybridBands; qs++) {
                var index = kernelBand[qs];
                for (var row = 0; row < direct; row++) {
                    wDryReal[row][ts][qs] = vReal[row][ts][qs];
                    wDryImag[row][ts][qs] = vImag[row][ts][qs];
                }
                for (var row = direct; row < wChannels; row++) {
                    var res = residualRows[row];
                    if (index < parameters.residualBands[res]) {
                        wDryReal[row][ts][qs] = hybridResidualReal[res][ts][qs];
                        wDryImag[row][ts][qs] = hybridResidualImag[res][ts][qs];
                    } else {
                        wDryReal[row][ts][qs] = 0.0f;
                        wDryImag[row][ts][qs] = 0.0f;
                    }
                }
                if (tttCount > 0 && ((index < parameters.tttStop[0] && parameters.tttDecorrelated[0])
                        || (index >= parameters.tttStart[1] && parameters.tttDecorrelated[1]))) {
                    wWetReal[5][ts][qs] = wWetReal[3][ts][qs] + wWetReal[4][ts][qs] + 0.707107f * wWetReal[5][ts][qs];
                    wWetImag[5][ts][qs] = wWetImag[3][ts][qs] + wWetImag[4][ts][qs] + 0.707107f * wWetImag[5][ts][qs];
                }
                for (var row = 0; row < direct; row++) {
                    wWetReal[row][ts][qs] = 0.0f;
                    wWetImag[row][ts][qs] = 0.0f;
                }
                for (var row = direct; row < wChannels; row++) {
                    if (index < parameters.residualBands[residualRows[row]]) {
                        wWetReal[row][ts][qs] = 0.0f;
                        wWetImag[row][ts][qs] = 0.0f;
                    }
                }
            }
        }
    }

    private void applyM2() {
        for (var ts = 0; ts < slots; ts++) {
            interpolate(matrices.m2Decor, matrices.m2DecorPrevious, ts, r2Decor, outputs, wChannels);
            interpolate(matrices.m2Resid, matrices.m2ResidPrevious, ts, r2Resid, outputs, wChannels);
            for (var qs = 0; qs < hybridBands; qs++) {
                var decor = r2Decor[kernelBand[qs]];
                var resid = r2Resid[kernelBand[qs]];
                for (var row = 0; row < outputs; row++) {
                    var dr = 0.0f;
                    var di = 0.0f;
                    var wr = 0.0f;
                    var wi = 0.0f;
                    for (var col = 0; col < wChannels; col++) {
                        var cr = resid[row * MpsMatrices.STRIDE + col];
                        var cw = decor[row * MpsMatrices.STRIDE + col];
                        dr += wDryReal[col][ts][qs] * cr;
                        di += wDryImag[col][ts][qs] * cr;
                        wr += wWetReal[col][ts][qs] * cw;
                        wi += wWetImag[col][ts][qs] * cw;
                    }
                    dryReal[row][ts][qs] = dr;
                    dryImag[row][ts][qs] = di;
                    wetReal[row][ts][qs] = wr;
                    wetImag[row][ts][qs] = wi;
                }
            }
        }
    }

    private void temporalProcess() {
        for (var ch = 0; ch < outputs; ch++) {
            for (var ts = 0; ts < slots; ts++) {
                var dr = dryReal[ch][ts];
                var di = dryImag[ch][ts];
                var wr = wetReal[ch][ts];
                var wi = wetImag[ch][ts];
                for (var hyb = 0; hyb < TP_BORDER; hyb++) {
                    dr[hyb] += wr[hyb];
                    di[hyb] += wi[hyb];
                    wr[hyb] = 0;
                    wi[hyb] = 0;
                }
            }
        }
        for (var ts = 0; ts < slots; ts++) {
            subbandTemporalProcess(ts);
        }
        for (var ch = 0; ch < outputs; ch++) {
            MpsHybrid.synthesize(dryReal[ch], dryImag[ch], slots, qmfOutReal, qmfOutImag, qmfBands);
            for (var ts = 0; ts < slots; ts++) {
                polyphase.synthesize(synthesisStates[ch], qmfOutReal[ts], qmfOutImag[ts], timeOut[ch],
                        qmfBands * ts);
            }
        }
        switch (tree) {
            case MpsConfig.TREE_5152, MpsConfig.TREE_525 -> {
                for (var n = 0; n < slots * qmfBands; n++) {
                    var ls = timeOut[1][n];
                    var rf = timeOut[2][n];
                    var rs = timeOut[3][n];
                    var c = timeOut[4][n];
                    var lfe = timeOut[5][n];
                    timeOut[1][n] = rf;
                    timeOut[2][n] = c;
                    timeOut[3][n] = lfe;
                    timeOut[4][n] = ls;
                    timeOut[5][n] = rs;
                }
            }
            case MpsConfig.TREE_7271, MpsConfig.TREE_7272, MpsConfig.TREE_7571, MpsConfig.TREE_7572 -> {
                for (var n = 0; n < slots * qmfBands; n++) {
                    var al = timeOut[1][n];
                    var ls = timeOut[2][n];
                    var rf = timeOut[3][n];
                    var ar = timeOut[4][n];
                    var c = timeOut[6][n];
                    var lfe = timeOut[7][n];
                    timeOut[1][n] = rf;
                    timeOut[2][n] = c;
                    timeOut[3][n] = lfe;
                    timeOut[4][n] = ls;
                    timeOut[6][n] = al;
                    timeOut[7][n] = ar;
                }
            }
            default -> {
            }
        }
    }

    /**
     * Shapes the decorrelated signals of one slot with the downmix energy ({@code subbandTP}).
     */
    private void subbandTemporalProcess(int ts) {
        if (stpUpdateCounter == STP_UPDATE_ENERGY_RATE) {
            stpUpdateCounter = 1;
            System.arraycopy(runDryEnergy, 0, oldDryEnergy, 0, inputs);
            System.arraycopy(runWetEnergy, 0, oldWetEnergy, 0, outputs);
        } else {
            stpUpdateCounter++;
        }
        for (var ch = 0; ch < outputs; ch++) {
            var dr = dryReal[ch][ts];
            var di = dryImag[ch][ts];
            var wr = wetReal[ch][ts];
            var wi = wetImag[ch][ts];
            var r0 = dr[0];
            var i0 = di[0];
            var r1 = wr[0];
            var i1 = wi[0];
            for (var i = 1; i < 6; i++) {
                r0 += dr[i];
                i0 += di[i];
                r1 += wr[i];
                i1 += wi[i];
            }
            stpDryReal[ch][0] = r0;
            stpDryImag[ch][0] = i0;
            stpWetReal[ch][0] = r1;
            stpWetImag[ch][0] = i1;
            stpDryReal[ch][1] = dr[6] + dr[7];
            stpDryImag[ch][1] = di[6] + di[7];
            stpWetReal[ch][1] = wr[6] + wr[7];
            stpWetImag[ch][1] = wi[6] + wi[7];
            stpDryReal[ch][2] = dr[8] + dr[9];
            stpDryImag[ch][2] = di[8] + di[9];
            stpWetReal[ch][2] = wr[8] + wr[9];
            stpWetImag[ch][2] = wi[8] + wi[9];
            for (var i = 3; i < BP_SIZE; i++) {
                stpDryReal[ch][i] = dr[i - 3 + 10];
                stpDryImag[ch][i] = di[i - 3 + 10];
                stpWetReal[ch][i] = wr[i - 3 + 10];
                stpWetImag[ch][i] = wi[i - 3 + 10];
            }
        }
        int lf;
        int rf;
        int c;
        int lfe;
        int ls;
        int rs;
        var al = 0;
        var ar = 0;
        switch (tree) {
            case MpsConfig.TREE_5151 -> {
                lf = 0;
                rf = 1;
                c = 2;
                lfe = 3;
                ls = 4;
                rs = 5;
            }
            case MpsConfig.TREE_5152, MpsConfig.TREE_525 -> {
                lf = 0;
                rf = 2;
                c = 4;
                lfe = 5;
                ls = 1;
                rs = 3;
            }
            default -> {
                lf = 0;
                rf = 3;
                c = 6;
                lfe = 7;
                ls = 2;
                rs = 5;
                al = 1;
                ar = 4;
            }
        }
        for (var n = 1; n < BP_SIZE; n++) {
            var gain = STP_BP[n] * STP_GF[n];
            switch (tree) {
                case MpsConfig.TREE_5151, MpsConfig.TREE_5152 -> {
                    stpDownmixReal[0][n] = sum(stpDryReal, n, lf, rf, c, ls, rs) * gain;
                    stpDownmixImag[0][n] = sum(stpDryImag, n, lf, rf, c, ls, rs) * gain;
                }
                case MpsConfig.TREE_525 -> {
                    stpDownmixReal[0][n] = (stpDryReal[lf][n] + stpDryReal[ls][n]) * gain;
                    stpDownmixReal[1][n] = (stpDryReal[rf][n] + stpDryReal[rs][n]) * gain;
                    stpDownmixImag[0][n] = (stpDryImag[lf][n] + stpDryImag[ls][n]) * gain;
                    stpDownmixImag[1][n] = (stpDryImag[rf][n] + stpDryImag[rs][n]) * gain;
                }
                case MpsConfig.TREE_7271, MpsConfig.TREE_7272 -> {
                    stpDownmixReal[0][n] = (stpDryReal[lf][n] + stpDryReal[ls][n] + stpDryReal[al][n]) * gain;
                    stpDownmixReal[1][n] = (stpDryReal[rf][n] + stpDryReal[rs][n] + stpDryReal[ar][n]) * gain;
                    stpDownmixImag[0][n] = (stpDryImag[lf][n] + stpDryImag[ls][n] + stpDryImag[al][n]) * gain;
                    stpDownmixImag[1][n] = (stpDryImag[rf][n] + stpDryImag[rs][n] + stpDryImag[ar][n]) * gain;
                }
                case MpsConfig.TREE_7571 -> {
                    stpDownmixReal[0][n] = (stpDryReal[lf][n] + stpDryReal[al][n]) * gain;
                    stpDownmixReal[1][n] = (stpDryReal[rf][n] + stpDryReal[ar][n]) * gain;
                    stpDownmixImag[0][n] = (stpDryImag[lf][n] + stpDryImag[al][n]) * gain;
                    stpDownmixImag[1][n] = (stpDryImag[rf][n] + stpDryImag[ar][n]) * gain;
                }
                default -> {
                    stpDownmixReal[0][n] = (stpDryReal[ls][n] + stpDryReal[al][n]) * gain;
                    stpDownmixReal[1][n] = (stpDryReal[rs][n] + stpDryReal[ar][n]) * gain;
                    stpDownmixImag[0][n] = (stpDryImag[ls][n] + stpDryImag[al][n]) * gain;
                    stpDownmixImag[1][n] = (stpDryImag[rs][n] + stpDryImag[ar][n]) * gain;
                }
            }
        }
        for (var ch = 0; ch < inputs; ch++) {
            var energy = 0.0f;
            if (ch < 2) {
                for (var n = 1; n < BP_SIZE; n++) {
                    energy += stpDownmixReal[ch][n] * stpDownmixReal[ch][n];
                    energy += stpDownmixImag[ch][n] * stpDownmixImag[ch][n];
                }
            }
            runDryEnergy[ch] = (float) (STP_LPF_COEFF1 * runDryEnergy[ch] + (1.0 - STP_LPF_COEFF1) * energy);
            dryEnergy[ch] = energy / (oldDryEnergy[ch] + ABS_THR);
        }
        for (var ch = 0; ch < outputs; ch++) {
            if (ch == lfe || (tree == MpsConfig.TREE_525 && ch == c)
                    || (tree == MpsConfig.TREE_7571 && (ch == ls || ch == rs))
                    || (tree == MpsConfig.TREE_7572 && (ch == lf || ch == rf))) {
                continue;
            }
            var energy = 0.0f;
            for (var n = 1; n < BP_SIZE; n++) {
                var temp = stpWetReal[ch][n] * stpWetReal[ch][n];
                temp += stpWetImag[ch][n] * stpWetImag[ch][n];
                temp *= STP_BP2[n] * STP_GF[n] * STP_GF[n];
                energy += temp;
            }
            runWetEnergy[ch] = (float) (STP_LPF_COEFF1 * runWetEnergy[ch] + (1.0 - STP_LPF_COEFF1) * energy);
            wetEnergy[ch] = energy / (oldWetEnergy[ch] + ABS_THR);
        }
        Arrays.fill(stpScale, 1.0f);
        switch (tree) {
            case MpsConfig.TREE_5151, MpsConfig.TREE_5152 -> {
                stpScale[lf] = scale(0, lf);
                stpScale[rf] = scale(0, rf);
                stpScale[c] = scale(0, c);
                stpScale[ls] = scale(0, ls);
                stpScale[rs] = scale(0, rs);
            }
            case MpsConfig.TREE_525 -> {
                stpScale[lf] = scale(0, lf);
                stpScale[rf] = scale(1, rf);
                stpScale[ls] = scale(0, ls);
                stpScale[rs] = scale(1, rs);
            }
            case MpsConfig.TREE_7271, MpsConfig.TREE_7272 -> {
                stpScale[lf] = scale(0, lf);
                stpScale[rf] = scale(1, rf);
                stpScale[ls] = scale(0, ls);
                stpScale[rs] = scale(1, rs);
                stpScale[al] = scale(0, al);
                stpScale[ar] = scale(1, ar);
            }
            case MpsConfig.TREE_7571 -> {
                stpScale[lf] = scale(0, lf);
                stpScale[rf] = scale(1, rf);
                stpScale[al] = scale(0, al);
                stpScale[ar] = scale(1, ar);
            }
            default -> {
                stpScale[ls] = scale(0, ls);
                stpScale[rs] = scale(1, rs);
                stpScale[al] = scale(0, al);
                stpScale[ar] = scale(1, ar);
            }
        }
        var damp = 0.1f;
        for (var ch = 0; ch < outputs; ch++) {
            stpScale[ch] = damp + (1 - damp) * stpScale[ch];
        }
        for (var ch = 0; ch < outputs; ch++) {
            if (stpScale[ch] > STP_SCALE_LIMIT) {
                stpScale[ch] = (float) STP_SCALE_LIMIT;
            }
            if (stpScale[ch] < 1.0 / STP_SCALE_LIMIT) {
                stpScale[ch] = (float) (1.0 / STP_SCALE_LIMIT);
            }
        }
        for (var ch = 0; ch < outputs; ch++) {
            stpScale[ch] = (float) (STP_LPF_COEFF2 * stpScale[ch] + (1.0 - STP_LPF_COEFF2) * previousStpScale[ch]);
            previousStpScale[ch] = stpScale[ch];
        }
        var channels = config.temporalShaping == 1 ? ROW_TO_CHANNEL_STP[tree]
                : config.temporalShaping == 2 ? ROW_TO_CHANNEL_GES[tree] : null;
        for (var ch = 0; ch < outputs; ch++) {
            var shaping = false;
            if (channels != null && channels[ch] != -1) {
                shaping = parameters.stpEnabled[channels[ch]];
            }
            var dr = dryReal[ch][ts];
            var di = dryImag[ch][ts];
            var wr = wetReal[ch][ts];
            var wi = wetImag[ch][ts];
            for (var n = 0; n < HP_LIMIT; n++) {
                float temp;
                if (n < 10) {
                    temp = shaping ? stpScale[ch] * STP_BP[HYBRID_TO_QMF[n]] : 1.f;
                } else {
                    temp = shaping ? stpScale[ch] * STP_BP[n + 3 - 10] : 1.f;
                }
                dr[n] += wr[n] * temp;
                di[n] += wi[n] * temp;
            }
            var temp = shaping ? stpScale[ch] : 1.f;
            for (var n = HP_LIMIT; n < hybridBands; n++) {
                dr[n] += wr[n] * temp;
                di[n] += wi[n] * temp;
            }
        }
    }

    private static float sum(float[][] values, int n, int a, int b, int c, int d, int e) {
        var total = values[a][n];
        total += values[b][n];
        total += values[c][n];
        total += values[d][n];
        total += values[e][n];
        return total;
    }

    private float scale(int input, int output) {
        return (float) Math.sqrt(dryEnergy[input] / (wetEnergy[output] + 1e-9));
    }

    /**
     * Applies guided envelope shaping ({@code spatialDecReshapeBBEnv}).
     */
    private void reshapeEnvelope() {
        if (tree == MpsConfig.TREE_7572) {
            for (var ch = 0; ch < 2; ch++) {
                extractEnvelope(ch + 4, outputs + ch + 4, true, gesEnvelopeDownmix[ch]);
            }
        } else {
            for (var ch = 0; ch < Math.min(inputs, 2); ch++) {
                extractEnvelope(ch, outputs + ch, true, gesEnvelopeDownmix[ch]);
            }
        }
        var channels = ROW_TO_CHANNEL_GES[tree];
        for (var ch = 0; ch < outputs; ch++) {
            var ch2 = channels[ch];
            if (ch2 == -1) {
                continue;
            }
            extractEnvelope(ch, ch, false, gesEnvelopeDry);
            if (!parameters.gesEnabled[ch2]) {
                continue;
            }
            for (var ts = 0; ts < slots; ts++) {
                var shape = parameters.envelopeShape[ch2][ts];
                var target = switch (tree) {
                    case MpsConfig.TREE_5151, MpsConfig.TREE_5152 -> shape * gesEnvelopeDownmix[0][ts];
                    case MpsConfig.TREE_525, MpsConfig.TREE_7271, MpsConfig.TREE_7272 -> switch (ch2) {
                        case 0, 3, 5 -> shape * gesEnvelopeDownmix[0][ts];
                        case 1, 4, 6 -> shape * gesEnvelopeDownmix[1][ts];
                        default -> shape * (gesEnvelopeDownmix[0][ts] + gesEnvelopeDownmix[1][ts]) * 0.5f;
                    };
                    default -> ch2 == 0 || ch2 == 2 ? shape * gesEnvelopeDownmix[0][ts]
                            : shape * gesEnvelopeDownmix[1][ts];
                };
                var dryFactor = target / (gesEnvelopeDry[ts] + 1e-9f);
                var dryAmplitude = 0.0f;
                var wetAmplitude = 0.0f;
                var dr = dryReal[ch][ts];
                var di = dryImag[ch][ts];
                var wr = wetReal[ch][ts];
                var wi = wetImag[ch][ts];
                for (var qs = 6; qs < hybridBands; qs++) {
                    dryAmplitude += dr[qs] * dr[qs] + di[qs] * di[qs];
                    wetAmplitude += wr[qs] * wr[qs] + wi[qs] * wi[qs];
                }
                var ratio = (float) Math.sqrt(wetAmplitude / (dryAmplitude + ABS_THR));
                dryFactor = Math.min(4.f, Math.max(0.25f, Math.max(0.f, dryFactor + ratio * (dryFactor - 1.f))));
                for (var qs = 6; qs < hybridBands; qs++) {
                    dr[qs] *= dryFactor;
                    di[qs] *= dryFactor;
                }
            }
        }
    }

    /**
     * Extracts the broadband envelope of a downmix channel or of an output channel ({@code extractBBEnv}).
     */
    private void extractEnvelope(int ch, int state, boolean downmix, float[] envelope) {
        for (var ts = 0; ts < slots; ts++) {
            var energy = gesSlotEnergy[ts];
            Arrays.fill(energy, 0.0f);
            if (downmix) {
                var re = hybridInReal[ch][ts];
                var im = hybridInImag[ch][ts];
                for (var qs = 0; qs < hybridBands; qs++) {
                    energy[MpsTables.envelopeBand(qs)] += re[qs] * re[qs] + im[qs] * im[qs];
                }
            } else {
                var dr = dryReal[ch][ts];
                var di = dryImag[ch][ts];
                var wr = wetReal[ch][ts];
                var wi = wetImag[ch][ts];
                for (var qs = 0; qs < hybridBands; qs++) {
                    var re = dr[qs] + wr[qs];
                    var im = di[qs] + wi[qs];
                    energy[MpsTables.envelopeBand(qs)] += re * re + im * im;
                }
            }
        }
        var start = 10;
        var end = 18;
        System.arraycopy(gesPartPrevious[state], 0, gesPart, 0, MpsParameters.MAX_BANDS);
        var norm = gesNormPrevious[state];
        for (var ts = 0; ts < slots; ts++) {
            var energy = gesSlotEnergy[ts];
            var frame = 0.0f;
            for (var pb = start; pb <= end; pb++) {
                gesPart[pb] = (1 - GES_ALPHA) * energy[pb] + GES_ALPHA * gesPart[pb];
                frame += energy[pb];
            }
            frame /= end - start + 1;
            frame = (1 - GES_ALPHA) * frame + GES_ALPHA * gesFramePrevious[state];
            gesFramePrevious[state] = frame;
            var env = 0.0f;
            for (var pb = start; pb <= end; pb++) {
                env += energy[pb] * (frame / (gesPart[pb] + ABS_THR));
            }
            norm = (1 - GES_BETA) * env + GES_BETA * norm;
            envelope[ts] = (float) Math.sqrt(env / (norm + ABS_THR));
        }
        System.arraycopy(gesPart, 0, gesPartPrevious[state], 0, MpsParameters.MAX_BANDS);
        gesNormPrevious[state] = norm;
    }

    private void updateBuffers() {
        if (residualCoding) {
            for (var box = 0; box < ottCount + tttCount; box++) {
                if (parameters.residualBands[box] > 0) {
                    shift(box);
                }
            }
        }
        if (arbitraryDownmix == 2) {
            for (var ch = 0; ch < inputs; ch++) {
                shift(ottCount + tttCount + ch);
            }
        }
    }

    private void shift(int channel) {
        for (var ts = 0; ts < slots; ts++) {
            System.arraycopy(qmfResidualReal[channel][ts + slots], 0, qmfResidualReal[channel][ts], 0, qmfBands);
            System.arraycopy(qmfResidualImag[channel][ts + slots], 0, qmfResidualImag[channel][ts], 0, qmfBands);
            Arrays.fill(qmfResidualReal[channel][ts + slots], 0.0f);
            Arrays.fill(qmfResidualImag[channel][ts + slots], 0.0f);
        }
    }
}
