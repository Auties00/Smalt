/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_dec.c SpatialDecOpen and
   SpatialDecApplyFrame; sac_bitdec.c GetUsacMps212SpecificConfig, SpatialDecDecodeHeader, SpatialDecParseFrame,
   SpatialDecDecodeFrame, mapIndexData, createMapping, mapFrequency, decodeAndMapFrameOtt and decodeAndMapFrameSmg;
   sac_calcM1andM2.c SpatialDecCalculateM1andM2_212, param2UMX_PS, param2UMX_PS_IPD_OPD, calculateOpd,
   param2UMX_Prediction, SpatialDecApplyM1, SpatialDecApplyM2, interp_UMX and interp_phase; sac_smoothing.c;
   sac_process.c; sac_TPprocess.c; sac_reshapeBBEnv.c), restricted to the 2-1-2 tree of USAC. Copyright Agere
   Systems, Coding Technologies, Fraunhofer IIS, Philips, Sony Corporation and ISO/IEC 2008; ISO/IEC reference
   software licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsDecorrelator;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsHybrid;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsLossless;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsTables;

import java.util.Arrays;

/**
 * Decodes MPEG Surround 2-1-2 ({@code Mps212Data()}, ISO/IEC 23003-3 subclause 7.11): it upmixes the QMF slots of
 * the downmix, and of the residual when the element codes one, into two output channels.
 *
 * <p>Samples keep the scale of the core decoder. Instances keep the state of one channel pair element; they are not
 * thread-safe.
 */
final class Mps212Decoder {
    private static final float ABS_THR = 1e-9f * 32768 * 32768;
    private static final float OPD_THR = 1.0e-9f;
    private static final double P_PI = 3.1415926535897932;
    private static final float PI = 3.1415926535897932f;
    private static final int HYBRID = MpsHybrid.BANDS;
    private static final int MAX_BANDS = MpsLossless.MAX_BANDS;
    private static final int MAX_SETS = MpsLossless.MAX_SETS;
    private static final int TSD_START_BAND = 7;
    private static final int TP_BORDER = 12;
    private static final int BP_SIZE = 25;
    private static final int HP_LIMIT = 16;

    private static final int[] FREQUENCY_RESOLUTIONS = {0, 28, 20, 14, 10, 7, 5, 4};
    private static final int[] KERNEL_BANDS = {4, 5, 7, 10, 14, 20, 28};
    private static final float[] CLIP_GAINS = {
            1.000000f, 1.189207f, 1.414213f, 1.681792f, 2.000000f, 2.378414f, 2.828427f, 4.000000f
    };
    private static final int[] STRIDES = {1, 2, 5, 28};
    private static final int[] SMOOTHING_TIMES = {64, 128, 256, 512};
    private static final float[] DEQUANT_CLD = {
            -150.0f, -45.0f, -40.0f, -35.0f, -30.0f, -25.0f, -22.0f, -19.0f, -16.0f, -13.0f, -10.0f, -8.0f, -6.0f,
            -4.0f, -2.0f, 0.0f, 2.0f, 4.0f, 6.0f, 8.0f, 10.0f, 13.0f, 16.0f, 19.0f, 22.0f, 25.0f, 30.0f, 35.0f, 40.0f,
            45.0f, 150.0f
    };
    private static final float[] DEQUANT_ICC = {1.0000f, 0.9370f, 0.84118f, 0.60092f, 0.36764f, 0.0f, -0.5890f,
            -0.9900f};
    private static final float[] DEQUANT_IPD = {
            0.f, 0.392699082f, 0.785398163f, 1.178097245f, 1.570796327f, 1.963495408f, 2.35619449f, 2.748893572f,
            3.141592654f, 3.534291735f, 3.926990817f, 4.319689899f, 4.71238898f, 5.105088062f, 5.497787144f,
            5.890486225f
    };
    private static final float TSD_SQRT_HALF = 0.707106781186548f;
    private static final float[] TSD_REAL = {1.0f, TSD_SQRT_HALF, 0.0f, -TSD_SQRT_HALF, -1.0f, -TSD_SQRT_HALF, 0.0f,
            TSD_SQRT_HALF};
    private static final float[] TSD_IMAG = {0.0f, TSD_SQRT_HALF, 1.0f, TSD_SQRT_HALF, 0.0f, -TSD_SQRT_HALF, -1.0f,
            -TSD_SQRT_HALF};
    private static final float[] STP_BP = {
            0.0000f, 0.0005f, 0.0092f, 0.0587f, 0.2580f, 0.7392f, 0.9791f, 0.9993f, 1.0000f, 1.0000f,
            1.0000f, 1.0000f, 0.9999f, 0.9984f, 0.9908f, 0.9639f, 0.8952f, 0.7711f, 0.6127f, 0.4609f,
            0.3391f, 0.2493f, 0.1848f, 0.1387f, 0.1053f
    };
    private static final float[] STP_GF = {
            0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 1e-008f, 8.1e-007f, 3.61e-006f, 8.41e-006f, 1.6e-005f, 2.704e-005f,
            3.969e-005f, 5.625e-005f, 7.396e-005f, 9.801e-005f, 0.00012321f, 0.00015625f,
            0.00019881f, 0.00024964f, 0.00032041f, 0.00041209f, 0.00053824f, 0.00070756f, 0.00094249f
    };
    private static final float[] STP_BP2 = new float[BP_SIZE];
    private static final int[] HYBRID_TO_QMF = {0, 0, 0, 0, 0, 0, 1, 1, 2, 2};
    private static final double STP_LPF_COEFF1 = 0.950;
    private static final double STP_LPF_COEFF2 = 0.450;
    private static final int STP_UPDATE_ENERGY_RATE = 32;
    private static final double STP_SCALE_LIMIT = 2.82;
    private static final float GES_ALPHA = (float) StrictMath.exp(-64 / (0.4f * 44100));
    private static final float GES_BETA = (float) StrictMath.exp(-64 / (0.04f * 44100));

    /**
     * The binomial coefficients up to 64 over 64, which fit in a {@code long}.
     */
    private static final long[][] BINOMIALS = new long[65][65];

    static {
        for (var n = 0; n < BP_SIZE; n++) {
            STP_BP2[n] = STP_BP[n] * STP_BP[n];
        }
        for (var n = 0; n <= 64; n++) {
            BINOMIALS[n][0] = 1;
            for (var k = 1; k <= n; k++) {
                BINOMIALS[n][k] = BINOMIALS[n - 1][k - 1] + BINOMIALS[n - 1][k];
            }
        }
    }

    private final MpsLossless lossless = new MpsLossless();
    private final int slots;
    private final int bands;
    private final int[] kernelBand = new int[HYBRID];
    private final int[] kernelSign = new int[HYBRID];
    private final boolean residual;
    private final int residualBands;
    private final int phaseCoding;
    private final int ipdBands;
    private final boolean highRate;
    private final int temporalShaping;
    private final int envelopeQuantization;
    private final float clipGain;

    private int sets = 1;
    private int setsPrevious;
    private final int[] parameterSlot = new int[MAX_SETS + 1];
    private boolean extendFrame;
    private boolean independent;

    private final MpsLossless.Modes cldModes = new MpsLossless.Modes();
    private final MpsLossless.Modes iccModes = new MpsLossless.Modes();
    private final MpsLossless.Modes ipdModes = new MpsLossless.Modes();
    private final int[][] cldCoded = new int[MAX_SETS + 1][MAX_BANDS];
    private final int[][] iccCoded = new int[MAX_SETS + 1][MAX_BANDS];
    private final int[][] ipdCoded = new int[MAX_SETS + 1][MAX_BANDS];
    private final int[] cldCodedPrevious = new int[MAX_BANDS];
    private final int[] iccCodedPrevious = new int[MAX_BANDS];
    private final int[] ipdCodedPrevious = new int[MAX_BANDS];
    private final int[][] cldIndex = new int[MAX_SETS + 1][MAX_BANDS];
    private final int[][] iccIndex = new int[MAX_SETS + 1][MAX_BANDS];
    private final int[][] ipdIndex = new int[MAX_SETS + 1][MAX_BANDS];
    private final int[] cldIndexPrevious = new int[MAX_BANDS];
    private final int[] iccIndexPrevious = new int[MAX_BANDS];
    private final int[] ipdIndexPrevious = new int[MAX_BANDS];
    private final float[][] cld = new float[MAX_SETS + 1][MAX_BANDS];
    private final float[][] icc = new float[MAX_SETS + 1][MAX_BANDS];
    private final float[][] ipd = new float[MAX_SETS + 1][MAX_BANDS];
    private boolean phaseMode;
    private boolean opdSmoothing;

    private final int[] smoothMode = new int[MAX_SETS];
    private final int[] smoothTime = new int[MAX_SETS];
    private final int[] smoothStride = new int[MAX_SETS];
    private final int[][] smoothFlags = new int[MAX_SETS][MAX_BANDS];
    private final int[] smoothingTime = new int[MAX_SETS + 1];
    private final int[][] smoothingData = new int[MAX_SETS + 1][MAX_BANDS];
    private int previousSmoothingTime;
    private final int[] previousSmoothingData = new int[MAX_BANDS];

    private final boolean[] stpEnabled = new boolean[2];
    private final boolean[] gesEnabled = new boolean[2];
    private final float[][] envelopeShape;
    private final int[] envelopeCodes;
    private boolean tsdEnabled;
    private final boolean[] tsdSlots;
    private final int[] tsdPhases;

    private final float[][] m1 = new float[MAX_SETS + 1][MAX_BANDS * 2];
    private final float[][] m2DecorReal = new float[MAX_SETS + 1][MAX_BANDS * 4];
    private final float[][] m2DecorImag = new float[MAX_SETS + 1][MAX_BANDS * 4];
    private final float[][] m2ResidReal = new float[MAX_SETS + 1][MAX_BANDS * 4];
    private final float[][] m2ResidImag = new float[MAX_SETS + 1][MAX_BANDS * 4];
    private final float[] m1Previous = new float[MAX_BANDS * 2];
    private final float[] m2DecorRealPrevious = new float[MAX_BANDS * 4];
    private final float[] m2DecorImagPrevious = new float[MAX_BANDS * 4];
    private final float[] m2ResidRealPrevious = new float[MAX_BANDS * 4];
    private final float[] m2ResidImagPrevious = new float[MAX_BANDS * 4];
    private final float[][] phaseLeft = new float[MAX_SETS + 1][MAX_BANDS];
    private final float[][] phaseRight = new float[MAX_SETS + 1][MAX_BANDS];
    private final float[] phaseLeftPrevious = new float[MAX_BANDS];
    private final float[] phaseRightPrevious = new float[MAX_BANDS];
    private final float[] phaseLeftSmooth = new float[MAX_BANDS];
    private final float[] phaseRightSmooth = new float[MAX_BANDS];

    private final float[] h11Real = new float[MAX_BANDS];
    private final float[] h11Imag = new float[MAX_BANDS];
    private final float[] h12Real = new float[MAX_BANDS];
    private final float[] h12Imag = new float[MAX_BANDS];
    private final float[] h21Real = new float[MAX_BANDS];
    private final float[] h21Imag = new float[MAX_BANDS];
    private final float[] h22Real = new float[MAX_BANDS];
    private final float[] h22Imag = new float[MAX_BANDS];
    private final float[] h12Residual = new float[MAX_BANDS];
    private final float[] h22Residual = new float[MAX_BANDS];
    private final float[] channelLeft = new float[MAX_BANDS];
    private final float[] channelRight = new float[MAX_BANDS];
    private final float[] iidLinear = new float[MAX_BANDS];
    private final float[] iccScratch = new float[MAX_BANDS];
    private final float[] opd = new float[MAX_BANDS];

    private final float[][] rM1;
    private final float[][] rDryReal;
    private final float[][] rDryImag;
    private final float[][] rWetReal;
    private final float[][] rWetImag;
    private final float[][] rPhaseReal;
    private final float[][] rPhaseImag;

    private final MpsHybrid[] analysis;
    private final MpsDecorrelator decorrelator;
    private final float[][][] qmfInReal;
    private final float[][][] qmfInImag;
    private final float[][][] hybridInputReal;
    private final float[][][] hybridInputImag;
    private final float[][][] vReal;
    private final float[][][] vImag;
    private final float[][][] wDryReal;
    private final float[][][] wDryImag;
    private final float[][] wetReal;
    private final float[][] wetImag;
    private final float[][] tsdReal;
    private final float[][] tsdImag;
    private final float[][][] dryOutReal;
    private final float[][][] dryOutImag;
    private final float[][][] wetOutReal;
    private final float[][][] wetOutImag;
    private final float[][][] qmfOutReal;
    private final float[][][] qmfOutImag;
    private final double[][] synthesisStates = new double[2][1280];

    private final float[] runDryEnergy = new float[1];
    private final float[] runWetEnergy = new float[2];
    private final float[] oldDryEnergy = {32768 * 32768};
    private final float[] oldWetEnergy = {32768 * 32768, 32768 * 32768};
    private final float[] previousStpScale = {1.0f, 1.0f};
    private int stpUpdateCounter;
    private final float[] stpQmfDryReal = new float[2 * BP_SIZE];
    private final float[] stpQmfDryImag = new float[2 * BP_SIZE];
    private final float[] stpQmfWetReal = new float[2 * BP_SIZE];
    private final float[] stpQmfWetImag = new float[2 * BP_SIZE];

    private final float[][] gesPartPrevious = new float[3][MAX_BANDS];
    private final float[] gesNormPrevious = {32768.f * 32768.f, 32768.f * 32768.f, 32768.f * 32768.f};
    private final float[] gesFramePrevious = new float[3];
    private final float[][] gesSlotEnergy;
    private final float[] gesEnvelopeDry;
    private final float[] gesEnvelopeDownmix;

    private final int[] mapping = new int[MAX_BANDS + 1];
    private final int[] mappingWidths = new int[MAX_BANDS + 1];
    private final int[] interpolate = new int[MAX_SETS + 1];
    private final int[] uncompressedCoarse = new int[MAX_SETS + 1];

    /**
     * Constructs the decoder of a channel pair element ({@code GetUsacMps212SpecificConfig},
     * {@code SpatialDecDecodeHeader}).
     *
     * @param config the element's configuration
     * @param stereoConfigIndex the element's {@code stereoConfigIndex}, 1 to 3
     * @param slots the number of QMF slots per frame, 32 or 64
     * @throws MediaException.Malformed if the configuration is reserved
     */
    Mps212Decoder(UsacConfig.Mps212Config config, int stereoConfigIndex, int slots) {
        this.slots = slots;
        var freqRes = config.frequencyResolution();
        if (freqRes == 0) {
            throw new MediaException.Malformed("MPEG Surround frequency resolution 0 is reserved");
        }
        this.bands = FREQUENCY_RESOLUTIONS[freqRes];
        var kernel = MpsTables.KERNELS[Arrays.binarySearch(KERNEL_BANDS, bands)];
        for (var i = 0; i < HYBRID; i++) {
            kernelBand[i] = kernel[2 * i];
            kernelSign[i] = kernel[2 * i + 1];
        }
        this.residual = stereoConfigIndex > 1;
        this.residualBands = residual ? Math.min(config.residualBands(), bands) : 0;
        var coding = config.phaseCoding() ? 1 : 0;
        if (residual && coding != 0) {
            coding = 2;
        }
        this.phaseCoding = coding;
        if (config.phaseBandsPresent()) {
            this.ipdBands = Math.min(config.phaseBands(), MAX_BANDS);
        } else {
            var defaultBands = switch (bands) {
                case 4, 5 -> 2;
                case 7 -> 3;
                case 10 -> 5;
                case 14 -> 7;
                default -> 10;
            };
            if (residual && defaultBands < config.residualBands()) {
                defaultBands = config.residualBands();
            }
            this.ipdBands = Math.min(defaultBands, MAX_BANDS);
        }
        this.highRate = config.highRateMode();
        this.temporalShaping = config.temporalShaping();
        this.envelopeQuantization = config.envelopeQuantization();
        this.clipGain = CLIP_GAINS[config.fixedGainDownmix()];
        this.envelopeShape = new float[2][slots];
        this.envelopeCodes = new int[slots];
        this.tsdSlots = new boolean[slots];
        this.tsdPhases = new int[slots];
        this.rM1 = new float[slots][MAX_BANDS * 2];
        this.rDryReal = new float[slots][MAX_BANDS * 4];
        this.rDryImag = new float[slots][MAX_BANDS * 4];
        this.rWetReal = new float[slots][MAX_BANDS * 4];
        this.rWetImag = new float[slots][MAX_BANDS * 4];
        this.rPhaseReal = new float[slots][MAX_BANDS * 2];
        this.rPhaseImag = new float[slots][MAX_BANDS * 2];
        var inputs = residual ? 2 : 1;
        this.analysis = new MpsHybrid[inputs];
        for (var i = 0; i < inputs; i++) {
            analysis[i] = new MpsHybrid(slots);
        }
        this.decorrelator = new MpsDecorrelator(slots, config.decorrelator());
        this.qmfInReal = new float[inputs][slots][64];
        this.qmfInImag = new float[inputs][slots][64];
        this.hybridInputReal = new float[inputs][slots][HYBRID];
        this.hybridInputImag = new float[inputs][slots][HYBRID];
        this.vReal = new float[2][slots][HYBRID];
        this.vImag = new float[2][slots][HYBRID];
        this.wDryReal = new float[2][slots][HYBRID];
        this.wDryImag = new float[2][slots][HYBRID];
        this.wetReal = new float[slots][HYBRID];
        this.wetImag = new float[slots][HYBRID];
        this.tsdReal = new float[slots][HYBRID];
        this.tsdImag = new float[slots][HYBRID];
        this.dryOutReal = new float[2][slots][HYBRID];
        this.dryOutImag = new float[2][slots][HYBRID];
        this.wetOutReal = new float[2][slots][HYBRID];
        this.wetOutImag = new float[2][slots][HYBRID];
        this.qmfOutReal = new float[2][slots][64];
        this.qmfOutImag = new float[2][slots][64];
        this.gesSlotEnergy = new float[slots][MAX_BANDS];
        this.gesEnvelopeDry = new float[slots];
        this.gesEnvelopeDownmix = new float[slots];
    }

    /**
     * {@return the number of QMF slots per frame}
     */
    int slots() {
        return slots;
    }

    /**
     * Reads {@code Mps212Data()} ({@code SpatialDecParseFrame}) and maps its parameters
     * ({@code SpatialDecDecodeFrame}).
     *
     * @param reader the bit source
     * @param usacIndependent the frame's {@code usacIndependencyFlag}
     * @throws MediaException.Malformed if the data is invalid
     */
    void read(AacBitReader reader, boolean usacIndependent) {
        setsPrevious = sets;
        var framing = false;
        if (highRate) {
            framing = reader.readFlag();
            sets = reader.read(3) + 1;
        } else {
            sets = 1;
        }
        var slotBits = 0;
        while ((1 << slotBits) < slots) {
            slotBits++;
        }
        for (var i = 0; i < sets; i++) {
            if (framing) {
                parameterSlot[i] = reader.read(slotBits);
            } else {
                parameterSlot[i] = (int) StrictMath.ceil((float) slots * (float) (i + 1) / (float) sets) - 1;
            }
        }
        for (var i = 1; i < sets; i++) {
            if (parameterSlot[i] <= parameterSlot[i - 1] || parameterSlot[i] >= slots) {
                throw new MediaException.Malformed("MPEG Surround parameter slots are not increasing");
            }
        }
        if (parameterSlot[0] >= slots) {
            throw new MediaException.Malformed("MPEG Surround parameter slot is past the frame");
        }
        independent = usacIndependent || reader.readFlag();
        lossless.read(reader, cldModes, cldCoded, cldCodedPrevious, MpsLossless.CLD, 0, bands, 0, sets, residual,
                independent);
        lossless.read(reader, iccModes, iccCoded, iccCodedPrevious, MpsLossless.ICC, 0, bands, 0, sets, residual,
                independent);
        phaseMode = false;
        if (phaseCoding != 0) {
            phaseMode = reader.readFlag();
        }
        if (phaseMode) {
            opdSmoothing = reader.readFlag();
            lossless.read(reader, ipdModes, ipdCoded, ipdCodedPrevious, MpsLossless.IPD, 0, ipdBands, 0, sets,
                    residual, independent);
        } else {
            for (var pb = 0; pb < ipdBands; pb++) {
                ipdCodedPrevious[pb] = 0;
                for (var i = 0; i < sets; i++) {
                    ipdCoded[i][pb] = 0;
                    ipdIndex[i][pb] = 0;
                }
                ipdIndexPrevious[pb] = 0;
            }
            opdSmoothing = false;
        }
        if (highRate) {
            for (var ps = 0; ps < sets; ps++) {
                smoothMode[ps] = reader.read(2);
                if (smoothMode[ps] >= 2) {
                    smoothTime[ps] = reader.read(2);
                }
                if (smoothMode[ps] == 3) {
                    smoothStride[ps] = reader.read(2);
                    var groups = (bands - 1) / STRIDES[smoothStride[ps]] + 1;
                    for (var pg = 0; pg < groups; pg++) {
                        smoothFlags[ps][pg] = reader.read(1);
                    }
                }
            }
        } else {
            Arrays.fill(smoothMode, 0, sets, 0);
        }
        stpEnabled[0] = stpEnabled[1] = false;
        gesEnabled[0] = gesEnabled[1] = false;
        tsdEnabled = false;
        if (temporalShaping == 3) {
            tsdEnabled = reader.readFlag();
        } else if (temporalShaping != 0) {
            if (reader.readFlag()) {
                if (temporalShaping == 1) {
                    for (var i = 0; i < 2; i++) {
                        stpEnabled[i] = reader.readFlag();
                    }
                } else {
                    for (var i = 0; i < 2; i++) {
                        gesEnabled[i] = reader.readFlag();
                    }
                    for (var i = 0; i < 2; i++) {
                        if (gesEnabled[i]) {
                            lossless.readReshape(reader, envelopeCodes, slots);
                            for (var ts = 0; ts < slots; ts++) {
                                envelopeShape[i][ts] = (float) StrictMath.pow(2,
                                        (float) envelopeCodes[ts] / (envelopeQuantization + 2) - 1);
                            }
                        }
                    }
                }
            }
        }
        if (tsdEnabled) {
            readTsd(reader);
        }
        decodeFrame();
    }

    private void readTsd(AacBitReader reader) {
        int slotBits;
        if (slots < 7) {
            slotBits = 1;
        } else if (slots < 13) {
            slotBits = 2;
        } else if (slots < 25) {
            slotBits = 3;
        } else if (slots < 49) {
            slotBits = 4;
        } else {
            slotBits = 5;
        }
        var transients = reader.read(slotBits) + 1;
        if (transients > slots) {
            throw new MediaException.Malformed("MPEG Surround signals more transient slots than the frame has");
        }
        var combinations = BINOMIALS[slots][transients];
        var codewordLength = 64 - Long.numberOfLeadingZeros(combinations - 1);
        var codeword = 0L;
        var remaining = codewordLength;
        while (remaining > 0) {
            var chunk = Math.min(16, remaining - 16 * ((remaining - 1) / 16));
            codeword = (codeword << chunk) | reader.read(chunk);
            remaining -= chunk;
        }
        Arrays.fill(tsdSlots, false);
        var p = transients;
        for (var k = slots - 1; k >= 0; k--) {
            if (p > k) {
                for (; k >= 0; k--) {
                    tsdSlots[k] = true;
                }
                break;
            }
            var c = BINOMIALS[k][p];
            if (Long.compareUnsigned(codeword, c) >= 0) {
                codeword -= c;
                tsdSlots[k] = true;
                p--;
                if (p == 0) {
                    break;
                }
            }
        }
        for (var i = 0; i < slots; i++) {
            if (tsdSlots[i]) {
                tsdPhases[i] = reader.read(3);
            }
        }
    }

    private void decodeFrame() {
        extendFrame = parameterSlot[sets - 1] != slots - 1;
        mapIndex(cldModes, cld, cldIndex, cldCoded, cldIndexPrevious, MpsLossless.CLD, bands, 0);
        mapIndex(iccModes, icc, iccIndex, iccCoded, iccIndexPrevious, MpsLossless.ICC, bands, 0);
        if (phaseCoding != 0) {
            mapIndex(ipdModes, ipd, ipdIndex, ipdCoded, ipdIndexPrevious, MpsLossless.IPD, ipdBands, 0);
        }
        decodeSmoothing();
        if (extendFrame) {
            sets++;
            parameterSlot[sets - 1] = slots - 1;
        }
    }

    private void mapIndex(MpsLossless.Modes modes, float[][] output, int[][] outputIndex, int[][] coded,
                          int[] previous, int type, int stopBand, int defaultValue) {
        var setIndex = 0;
        for (var i = 0; i < sets; i++) {
            var mode = modes.dataMode[i];
            if (mode == 0) {
                uncompressedCoarse[i] = 0;
                for (var band = 0; band < stopBand; band++) {
                    outputIndex[i][band] = defaultValue;
                    previous[band] = defaultValue;
                }
                modes.quantCoarsePrevious = 0;
            }
            if (mode == 1 || mode == 2) {
                System.arraycopy(previous, 0, outputIndex[i], 0, stopBand);
                uncompressedCoarse[i] = modes.quantCoarsePrevious;
            }
            interpolate[i] = mode == 2 ? 1 : 0;
            if (mode == 3) {
                var stride = STRIDES[modes.freqResStride[setIndex]];
                var dataBands = (stopBand - 1) / stride + 1;
                createMapping(mapping, 0, stopBand, stride);
                for (var j = 0; j < dataBands; j++) {
                    var value = coded[setIndex][j];
                    for (var band = mapping[j]; band < mapping[j + 1]; band++) {
                        outputIndex[i][band] = value;
                    }
                }
                System.arraycopy(outputIndex[i], 0, previous, 0, stopBand);
                modes.quantCoarsePrevious = modes.quantCoarse[setIndex];
                uncompressedCoarse[i] = modes.quantCoarse[setIndex];
                setIndex++;
            }
        }
        for (var i = 0; i < sets; i++) {
            if (uncompressedCoarse[i] == 1) {
                MpsLossless.coarseToFine(outputIndex[i], type, 0, stopBand);
                uncompressedCoarse[i] = 0;
            }
        }
        var i1 = -1;
        for (var i = 0; i < sets; i++) {
            if (interpolate[i] != 1) {
                i1 = i;
            }
            var i2 = i;
            while (i2 < sets - 1 && interpolate[i2] == 1) {
                i2++;
            }
            int x1;
            if (i1 == -1) {
                x1 = 0;
                i1 = 0;
            } else {
                x1 = parameterSlot[i1];
            }
            var first = i1;
            var xi = parameterSlot[i];
            var x2 = parameterSlot[i2];
            if (interpolate[i] == 1 && x2 != x1) {
                for (var band = 0; band < stopBand; band++) {
                    var y1 = outputIndex[first][band];
                    var y2 = outputIndex[i2][band];
                    int yi;
                    if (type == MpsLossless.IPD) {
                        if (y2 - y1 > 8) {
                            y1 += 16;
                        }
                        if (y1 - y2 > 8) {
                            y2 += 16;
                        }
                        yi = (y1 + (xi - x1) * (y2 - y1) / (x2 - x1)) % 16;
                    } else {
                        yi = y1 + (xi - x1) * (y2 - y1) / (x2 - x1);
                    }
                    outputIndex[i][band] = yi;
                }
            }
        }
        for (var ps = 0; ps < sets; ps++) {
            for (var band = 0; band < stopBand; band++) {
                output[ps][band] = dequantize(outputIndex[ps][band], type);
            }
        }
        if (extendFrame) {
            if (type == MpsLossless.IPD) {
                modes.quantCoarse[sets] = modes.quantCoarse[sets - 1];
            }
            System.arraycopy(output[sets - 1], 0, output[sets], 0, stopBand);
        }
    }

    private static float dequantize(int value, int type) {
        return switch (type) {
            case MpsLossless.CLD -> DEQUANT_CLD[Math.clamp(value + 15, 0, 30)];
            case MpsLossless.ICC -> DEQUANT_ICC[Math.clamp(value, 0, 7)];
            default -> DEQUANT_IPD[value & 15];
        };
    }

    /**
     * Builds the band borders of a coded parameter set ({@code createMapping}).
     */
    private void createMapping(int[] map, int startBand, int stopBand, int stride) {
        var inBands = stopBand - startBand;
        var outBands = Math.max(1, (inBands - 1) / stride + 1);
        var diff = inBands - outBands * stride;
        var widths = mappingWidths;
        Arrays.fill(widths, 0, outBands, stride);
        int increment;
        int k;
        if (diff > 0) {
            increment = -1;
            k = outBands - 1;
        } else {
            increment = 1;
            k = 0;
        }
        while (diff != 0) {
            widths[k] -= increment;
            k += increment;
            diff += increment;
            if (k >= outBands) {
                if (diff > 0) {
                    k = outBands - 1;
                } else if (diff < 0) {
                    k = 0;
                }
            }
        }
        map[0] = startBand;
        for (var i = 0; i < outBands; i++) {
            map[i + 1] = map[i] + widths[i];
        }
    }

    private void decodeSmoothing() {
        for (var ps = 0; ps < sets; ps++) {
            switch (smoothMode[ps]) {
                case 0 -> {
                    smoothingTime[ps] = 256;
                    Arrays.fill(smoothingData[ps], 0, bands, 0);
                }
                case 1 -> {
                    smoothingTime[ps] = ps > 0 ? smoothingTime[ps - 1] : previousSmoothingTime;
                    System.arraycopy(ps > 0 ? smoothingData[ps - 1] : previousSmoothingData, 0, smoothingData[ps], 0,
                            bands);
                }
                case 2 -> {
                    smoothingTime[ps] = SMOOTHING_TIMES[smoothTime[ps]];
                    Arrays.fill(smoothingData[ps], 0, bands, 1);
                }
                default -> {
                    smoothingTime[ps] = SMOOTHING_TIMES[smoothTime[ps]];
                    var stride = STRIDES[smoothStride[ps]];
                    var groups = (bands - 1) / stride + 1;
                    createMapping(mapping, 0, bands, stride);
                    for (var pg = 0; pg < groups; pg++) {
                        for (var pb = mapping[pg]; pb < mapping[pg + 1]; pb++) {
                            smoothingData[ps][pb] = smoothFlags[ps][pg];
                        }
                    }
                }
            }
        }
        previousSmoothingTime = smoothingTime[sets - 1];
        System.arraycopy(smoothingData[sets - 1], 0, previousSmoothingData, 0, bands);
        if (extendFrame) {
            smoothingTime[sets] = smoothingTime[sets - 1];
            System.arraycopy(smoothingData[sets - 1], 0, smoothingData[sets], 0, bands);
        }
    }

    /**
     * Upmixes one frame ({@code SpatialDecApplyFrame}).
     *
     * @param inputReal the real parts of the input QMF slots, {@code [input][slot][64]}: the downmix and, with
     *                  residual coding, the residual
     * @param inputImag the imaginary parts of the input QMF slots
     * @param qmf the QMF synthesis bank
     * @param outputs receives the two output channels, {@code 64 * slots} samples each
     * @param highBand the high band decoders of the two output channels, or {@code null} when the high band is
     *                 already in the input
     */
    void apply(float[][][] inputReal, float[][][] inputImag, EsbrQmf qmf, float[][] outputs, EsbrChannel[] highBand) {
        for (var ch = 0; ch < analysis.length; ch++) {
            for (var ts = 0; ts < slots; ts++) {
                var r = inputReal[ch][ts];
                var i = inputImag[ch][ts];
                var sr = qmfInReal[ch][ts];
                var si = qmfInImag[ch][ts];
                for (var qs = 0; qs < 64; qs++) {
                    sr[qs] = clipGain * r[qs];
                    si[qs] = clipGain * i[qs];
                }
            }
        }
        analysis[0].analyze(qmfInReal[0], qmfInImag[0], hybridInputReal[0], hybridInputImag[0]);
        if (residual && residualBands > 0) {
            analysis[1].analyze(qmfInReal[1], qmfInImag[1], hybridInputReal[1], hybridInputImag[1]);
        }
        calculateMatrices();
        smoothMatrices();
        applyM1();
        createW();
        applyM2();
        if (temporalShaping == 2) {
            reshapeEnvelope();
        }
        for (var ch = 0; ch < 2; ch++) {
            for (var ts = 0; ts < slots; ts++) {
                var dr = dryOutReal[ch][ts];
                var di = dryOutImag[ch][ts];
                var wr = wetOutReal[ch][ts];
                var wi = wetOutImag[ch][ts];
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
        for (var ch = 0; ch < 2; ch++) {
            MpsHybrid.synthesize(dryOutReal[ch], dryOutImag[ch], slots, qmfOutReal[ch], qmfOutImag[ch]);
            if (highBand != null) {
                highBand[ch].processFromMps(qmfOutReal[ch], qmfOutImag[ch]);
            }
            for (var ts = 0; ts < slots; ts++) {
                qmf.synthesize(synthesisStates[ch], qmfOutReal[ch][ts], qmfOutImag[ch][ts], outputs[ch], 64 * ts);
            }
        }
    }

    private void calculateMatrices() {
        var previous = setsPrevious - 1;
        System.arraycopy(m1[previous], 0, m1Previous, 0, MAX_BANDS * 2);
        System.arraycopy(m2DecorReal[previous], 0, m2DecorRealPrevious, 0, MAX_BANDS * 4);
        System.arraycopy(m2DecorImag[previous], 0, m2DecorImagPrevious, 0, MAX_BANDS * 4);
        System.arraycopy(m2ResidReal[previous], 0, m2ResidRealPrevious, 0, MAX_BANDS * 4);
        System.arraycopy(m2ResidImag[previous], 0, m2ResidImagPrevious, 0, MAX_BANDS * 4);
        System.arraycopy(phaseLeft[previous], 0, phaseLeftPrevious, 0, MAX_BANDS);
        System.arraycopy(phaseRight[previous], 0, phaseRightPrevious, 0, MAX_BANDS);
        for (var ps = 0; ps < sets; ps++) {
            Arrays.fill(h11Imag, 0.0f);
            Arrays.fill(h12Imag, 0.0f);
            Arrays.fill(h21Imag, 0.0f);
            Arrays.fill(h22Imag, 0.0f);
            switch (phaseCoding) {
                case 0 -> {
                    if (residual) {
                        prediction(ps);
                    } else {
                        stereoCore(cld[ps], icc[ps], residualBands);
                    }
                }
                case 1 -> phaseUpmix(ps);
                default -> prediction(ps);
            }
            var m1Set = m1[ps];
            var decorReal = m2DecorReal[ps];
            var decorImag = m2DecorImag[ps];
            var residReal = m2ResidReal[ps];
            var residImag = m2ResidImag[ps];
            for (var pb = 0; pb < bands; pb++) {
                m1Set[2 * pb] = 1;
                m1Set[2 * pb + 1] = 1;
                var o = 4 * pb;
                decorReal[o] = 0;
                decorImag[o] = 0;
                decorReal[o + 1] = h12Real[pb];
                decorImag[o + 1] = h12Imag[pb];
                decorReal[o + 2] = 0;
                decorImag[o + 2] = 0;
                decorReal[o + 3] = h22Real[pb];
                decorImag[o + 3] = h22Imag[pb];
                residReal[o] = h11Real[pb];
                residImag[o] = h11Imag[pb];
                residReal[o + 1] = h12Residual[pb];
                residImag[o + 1] = 0;
                residReal[o + 2] = h21Real[pb];
                residImag[o + 2] = h21Imag[pb];
                residReal[o + 3] = h22Residual[pb];
                residImag[o + 3] = 0;
            }
        }
        smoothPhases();
    }

    /**
     * Computes the upmix of the parametric stereo model ({@code param2UMX_PS_Core}).
     */
    private void stereoCore(float[] cldSet, float[] iccSet, int resBands) {
        var maxInverseDeterminant = 1.2f;
        for (var band = 0; band < bands; band++) {
            var iid = (float) StrictMath.pow(10, cldSet[band] / 20.0f);
            iidLinear[band] = iid;
            channelLeft[band] = (float) Math.sqrt((iid * iid) / (1 + (iid * iid)));
            channelRight[band] = (float) Math.sqrt(1 / (1 + (iid * iid)));
        }
        for (var band = 0; band < bands; band++) {
            var iid = iidLinear[band];
            var cl = channelLeft[band];
            var cr = channelRight[band];
            if (band < resBands) {
                var limit = Math.max(iccSet[band], (1 / (maxInverseDeterminant * maxInverseDeterminant) - 1.0f)
                        * (iid + 1.0f / iid) / 2.0f);
                var alpha = (float) (0.5f * StrictMath.acos(limit));
                var beta = (float) StrictMath.atan(StrictMath.tan(alpha) * (cr - cl) / (cr + cl));
                h11Real[band] = (float) (cl * StrictMath.cos(alpha + beta));
                h21Real[band] = (float) (cr * StrictMath.cos(-alpha + beta));
                h12Real[band] = 0;
                h22Real[band] = 0;
                h12Residual[band] = 1;
                h22Residual[band] = -1;
            } else {
                var alpha = (float) (0.5 * StrictMath.acos(iccSet[band]));
                var beta = (float) StrictMath.atan(StrictMath.tan(alpha) * (cr - cl) / (cr + cl));
                h11Real[band] = (float) (cl * StrictMath.cos(alpha + beta));
                h21Real[band] = (float) (cr * StrictMath.cos(-alpha + beta));
                h12Real[band] = (float) (cl * StrictMath.sin(alpha + beta));
                h22Real[band] = (float) (cr * StrictMath.sin(-alpha + beta));
                h12Residual[band] = 0;
                h22Residual[band] = 0;
            }
        }
    }

    /**
     * Computes the upmix with interchannel phase differences ({@code param2UMX_PS_IPD_OPD}).
     */
    private void phaseUpmix(int ps) {
        System.arraycopy(icc[ps], 0, iccScratch, 0, bands);
        for (var band = 0; band < residualBands; band++) {
            iccScratch[band] *= (float) StrictMath.cos(ipd[ps][band]);
        }
        stereoCore(cld[ps], iccScratch, residualBands);
        var phaseBands = ipdBands;
        if (phaseMode) {
            calculateOpd(ps);
            for (var band = 0; band < ipdBands; band++) {
                phaseLeft[ps][band] = wrapPhase(opd[band]);
                phaseRight[ps][band] = wrapPhase(opd[band] - ipd[ps][band]);
            }
        } else {
            phaseBands = 0;
        }
        for (var band = phaseBands; band < bands; band++) {
            phaseLeft[ps][band] = 0.0f;
            phaseRight[ps][band] = 0.0f;
        }
    }

    private static float wrapPhase(float phase) {
        var twoPi = 2.0f * (float) P_PI;
        while (phase < 0.0f) {
            phase += twoPi;
        }
        while (phase >= twoPi) {
            phase -= twoPi;
        }
        return phase;
    }

    private void calculateOpd(int ps) {
        for (var band = 0; band < ipdBands; band++) {
            var c = cld[ps][band];
            var p = ipd[ps][band];
            var i = icc[ps][band];
            var index = ipdIndex[ps][band];
            if (c == 0.0f && (index & 0xf) == 8) {
                opd[band] = 0.0f;
            } else {
                var iid = (float) StrictMath.pow(10.0f, c / 20.0f);
                var iid2 = iid * iid;
                var iid21 = iid2 + 1.0f;
                var sin = (float) StrictMath.sin(p);
                var cos = (float) StrictMath.cos(p);
                var temp1 = 2.0f * i * iid;
                var temp1c = temp1 * cos;
                var ratio = (iid21 + temp1c) / (iid21 + temp1) + OPD_THR;
                var w2 = (float) StrictMath.pow(ratio, 0.25f);
                var w1 = 2.0f - w2;
                opd[band] = (float) StrictMath.atan2(w2 * sin, w1 * iid + w2 * cos);
            }
        }
    }

    /**
     * Computes the prediction upmix of residual coding ({@code param2UMX_Prediction}).
     */
    private void prediction(int ps) {
        var maxWeight = 1.2f;
        for (var band = 0; band < bands; band++) {
            var c = cld[ps][band];
            var i = icc[ps][band];
            var p = ipd[ps][band];
            if (band < ipdBands && c == 0.0f && i == 1.0f && Math.abs(p - P_PI) < 0.01f) {
                var gain = 0.5f / maxWeight;
                h11Real[band] = gain;
                h11Imag[band] = 0.0f;
                h12Real[band] = 0.0f;
                h12Imag[band] = 0.0f;
                h22Real[band] = 0.0f;
                h22Imag[band] = 0.0f;
                h21Imag[band] = 0.0f;
                if (band < residualBands) {
                    h21Real[band] = gain;
                    h12Residual[band] = gain;
                    h22Residual[band] = -gain;
                } else {
                    h21Real[band] = -gain;
                    h12Residual[band] = 0.0f;
                    h22Residual[band] = 0.0f;
                }
            } else {
                var iid = (float) StrictMath.pow(10.0f, c / 20.0f);
                var iid2 = iid * iid;
                var iid21 = iid2 + 1.0f;
                var iidIcc2 = iid * i * 2.0f;
                float cos;
                float sin;
                if (band < ipdBands) {
                    cos = (float) StrictMath.cos(p);
                    sin = (float) StrictMath.sin(p);
                } else {
                    cos = 1.0f;
                    sin = 0.0f;
                }
                var temp = iid21 + iidIcc2 * cos;
                var weight = (float) Math.sqrt(iid21 / temp);
                weight = 0.5f / Math.min(maxWeight, weight);
                var alphaReal = (1.0f - iid2) / temp;
                var alphaImag = -iidIcc2 * sin / temp;
                h11Real[band] = weight - alphaReal * weight;
                h11Imag[band] = -alphaImag * weight;
                h21Real[band] = weight + alphaReal * weight;
                h21Imag[band] = alphaImag * weight;
                if (band < residualBands) {
                    h12Real[band] = 0.0f;
                    h12Imag[band] = 0.0f;
                    h22Real[band] = 0.0f;
                    h22Imag[band] = 0.0f;
                    h12Residual[band] = weight;
                    h22Residual[band] = -weight;
                } else {
                    var beta = 2.0f * iid * (float) Math.sqrt(1.0f - i * i) * weight / temp;
                    h12Real[band] = beta;
                    h12Imag[band] = 0.0f;
                    h22Real[band] = -beta;
                    h22Imag[band] = 0.0f;
                    h12Residual[band] = 0.0f;
                    h22Residual[band] = 0.0f;
                }
            }
        }
    }

    /**
     * Smooths the phases of the phase coding ({@code SpatialDecSmoothOPD}).
     */
    private void smoothPhases() {
        if (!opdSmoothing) {
            System.arraycopy(phaseLeft[sets - 1], 0, phaseLeftSmooth, 0, bands);
            System.arraycopy(phaseRight[sets - 1], 0, phaseRightSmooth, 0, bands);
            return;
        }
        for (var ps = 0; ps < sets; ps++) {
            var coarse = ipdModes.quantCoarse[ps] != 0;
            var dSlots = ps == 0 ? parameterSlot[ps] + 1 : parameterSlot[ps] - parameterSlot[ps - 1];
            var delta = (float) dSlots / 128.f;
            for (var pb = 0; pb < bands; pb++) {
                var left = phaseLeft[ps][pb];
                var right = phaseRight[ps][pb];
                while (left > phaseLeftSmooth[pb] + PI) {
                    left -= 2 * PI;
                }
                while (left < phaseLeftSmooth[pb] - PI) {
                    left += 2 * PI;
                }
                while (right > phaseRightSmooth[pb] + PI) {
                    right -= 2 * PI;
                }
                while (right < phaseRightSmooth[pb] - PI) {
                    right += 2 * PI;
                }
                phaseLeftSmooth[pb] = delta * left + (1 - delta) * phaseLeftSmooth[pb];
                phaseRightSmooth[pb] = delta * right + (1 - delta) * phaseRightSmooth[pb];
                var difference = (left - right) - (phaseLeftSmooth[pb] - phaseRightSmooth[pb]);
                while (difference > PI) {
                    difference -= 2 * PI;
                }
                while (difference < -PI) {
                    difference += 2 * PI;
                }
                if (Math.abs(difference) > (coarse ? 50 : 25) * PI / 180) {
                    phaseLeftSmooth[pb] = left;
                    phaseRightSmooth[pb] = right;
                }
                while (phaseLeftSmooth[pb] > 2 * PI) {
                    phaseLeftSmooth[pb] -= 2 * PI;
                }
                while (phaseLeftSmooth[pb] < 0) {
                    phaseLeftSmooth[pb] += 2 * PI;
                }
                while (phaseRightSmooth[pb] > 2 * PI) {
                    phaseRightSmooth[pb] -= 2 * PI;
                }
                while (phaseRightSmooth[pb] < 0) {
                    phaseRightSmooth[pb] += 2 * PI;
                }
                phaseLeft[ps][pb] = phaseLeftSmooth[pb];
                phaseRight[ps][pb] = phaseRightSmooth[pb];
            }
        }
    }

    /**
     * Smooths the upmix matrices over time ({@code SpatialDecSmoothM1andM2}).
     */
    private void smoothMatrices() {
        for (var ps = 0; ps < sets; ps++) {
            var dSlots = ps == 0 ? parameterSlot[ps] + 1 : parameterSlot[ps] - parameterSlot[ps - 1];
            var delta = (float) dSlots / smoothingTime[ps];
            for (var pb = 0; pb < bands; pb++) {
                if (smoothingData[ps][pb] == 0 || pb < residualBands) {
                    continue;
                }
                smooth(m1, m1Previous, ps, 2 * pb, 2, delta);
                smooth(m2DecorReal, m2DecorRealPrevious, ps, 4 * pb, 4, delta);
                smooth(m2DecorImag, m2DecorImagPrevious, ps, 4 * pb, 4, delta);
                smooth(m2ResidReal, m2ResidRealPrevious, ps, 4 * pb, 4, delta);
                smooth(m2ResidImag, m2ResidImagPrevious, ps, 4 * pb, 4, delta);
            }
        }
    }

    private static void smooth(float[][] matrix, float[] previous, int ps, int offset, int count, float delta) {
        var current = matrix[ps];
        var before = ps > 0 ? matrix[ps - 1] : previous;
        for (var i = offset; i < offset + count; i++) {
            current[i] = delta * current[i] + (1 - delta) * before[i];
        }
    }

    /**
     * Interpolates one matrix over the slots of the frame ({@code interp_UMX}).
     */
    private void interpolate(float[][] matrix, float[] previous, float[][] out, int width) {
        var count = bands * width;
        var prevSlot = -1.0f;
        var currSlot = (float) parameterSlot[0];
        for (var ts = 0; ts <= (int) currSlot; ts++) {
            var alpha = ts / (currSlot - prevSlot) - prevSlot / (currSlot - prevSlot);
            var row = out[ts];
            var m = matrix[0];
            for (var i = 0; i < count; i++) {
                row[i] = previous[i] * (1 - alpha) + alpha * m[i];
            }
        }
        for (var ps = 1; ps < sets; ps++) {
            prevSlot = parameterSlot[ps - 1];
            currSlot = parameterSlot[ps];
            var a = matrix[ps - 1];
            var b = matrix[ps];
            for (var ts = (int) prevSlot + 1; ts <= (int) currSlot; ts++) {
                var alpha = ts / (currSlot - prevSlot) - prevSlot / (currSlot - prevSlot);
                var row = out[ts];
                for (var i = 0; i < count; i++) {
                    row[i] = a[i] * (1 - alpha) + alpha * b[i];
                }
            }
        }
    }

    private void applyM1() {
        interpolate(m1, m1Previous, rM1, 2);
        var x = hybridInputReal[0];
        var xi = hybridInputImag[0];
        for (var ts = 0; ts < slots; ts++) {
            var r = rM1[ts];
            for (var qs = 0; qs < HYBRID; qs++) {
                var pb = kernelBand[qs];
                for (var row = 0; row < 2; row++) {
                    var g = r[2 * pb + row];
                    vReal[row][ts][qs] = x[ts][qs] * g;
                    vImag[row][ts][qs] = xi[ts][qs] * g;
                }
            }
        }
    }

    /**
     * Builds the direct and the decorrelated inputs of the second matrix ({@code SpatialDecCreateW}).
     */
    private void createW() {
        var v1Real = vReal[1];
        var v1Imag = vImag[1];
        if (tsdEnabled) {
            for (var ts = 0; ts < slots; ts++) {
                if (tsdSlots[ts]) {
                    for (var qs = TSD_START_BAND; qs < HYBRID; qs++) {
                        tsdReal[ts][qs] = v1Real[ts][qs];
                        tsdImag[ts][qs] = v1Imag[ts][qs];
                        v1Real[ts][qs] = 0.0f;
                        v1Imag[ts][qs] = 0.0f;
                    }
                }
            }
        }
        decorrelator.apply(v1Real, v1Imag, wetReal, wetImag);
        if (tsdEnabled) {
            for (var ts = 0; ts < slots; ts++) {
                if (!tsdSlots[ts]) {
                    continue;
                }
                var mr = TSD_REAL[tsdPhases[ts]];
                var mi = TSD_IMAG[tsdPhases[ts]];
                for (var qs = TSD_START_BAND; qs < HYBRID; qs++) {
                    var re = mr * tsdReal[ts][qs] - mi * tsdImag[ts][qs];
                    var im = mi * tsdReal[ts][qs] + mr * tsdImag[ts][qs];
                    wetReal[ts][qs] += re;
                    wetImag[ts][qs] += im;
                }
            }
        }
        for (var ts = 0; ts < slots; ts++) {
            System.arraycopy(vReal[0][ts], 0, wDryReal[0][ts], 0, HYBRID);
            System.arraycopy(vImag[0][ts], 0, wDryImag[0][ts], 0, HYBRID);
            for (var qs = 0; qs < HYBRID; qs++) {
                if (kernelBand[qs] < residualBands) {
                    wDryReal[1][ts][qs] = hybridInputReal[1][ts][qs];
                    wDryImag[1][ts][qs] = hybridInputImag[1][ts][qs];
                    wetReal[ts][qs] = 0.0f;
                    wetImag[ts][qs] = 0.0f;
                } else {
                    wDryReal[1][ts][qs] = 0.0f;
                    wDryImag[1][ts][qs] = 0.0f;
                }
            }
        }
    }

    private void applyM2() {
        var complex = phaseCoding != 0;
        var interpolatePhase = phaseCoding == 1;
        interpolate(m2DecorReal, m2DecorRealPrevious, rWetReal, 4);
        interpolate(m2ResidReal, m2ResidRealPrevious, rDryReal, 4);
        if (complex && !interpolatePhase) {
            interpolate(m2DecorImag, m2DecorImagPrevious, rWetImag, 4);
            interpolate(m2ResidImag, m2ResidImagPrevious, rDryImag, 4);
        }
        if (interpolatePhase) {
            interpolatePhases();
            for (var ts = 0; ts < slots; ts++) {
                var pr = rPhaseReal[ts];
                var pi = rPhaseImag[ts];
                var dr = rDryReal[ts];
                var di = rDryImag[ts];
                var wr = rWetReal[ts];
                var wi = rWetImag[ts];
                for (var pb = 0; pb < bands; pb++) {
                    for (var row = 0; row < 2; row++) {
                        var cos = pr[2 * pb + row];
                        var sin = pi[2 * pb + row];
                        for (var col = 0; col < 2; col++) {
                            var o = 4 * pb + 2 * row + col;
                            di[o] = dr[o] * sin;
                            dr[o] = dr[o] * cos;
                            wi[o] = wr[o] * sin;
                            wr[o] = wr[o] * cos;
                        }
                    }
                }
            }
        }
        for (var ts = 0; ts < slots; ts++) {
            var dr = rDryReal[ts];
            var di = rDryImag[ts];
            var wr = rWetReal[ts];
            var wi = rWetImag[ts];
            for (var qs = 0; qs < HYBRID; qs++) {
                var pb = kernelBand[qs];
                var sign = kernelSign[qs];
                var d0r = wDryReal[0][ts][qs];
                var d0i = wDryImag[0][ts][qs];
                var d1r = wDryReal[1][ts][qs];
                var d1i = wDryImag[1][ts][qs];
                var w1r = wetReal[ts][qs];
                var w1i = wetImag[ts][qs];
                for (var row = 0; row < 2; row++) {
                    var o = 4 * pb + 2 * row;
                    var dryRe = d0r * dr[o];
                    var dryIm = d0i * dr[o];
                    var wetRe = 0.0f * wr[o];
                    var wetIm = 0.0f * wr[o];
                    dryRe += d1r * dr[o + 1];
                    dryIm += d1i * dr[o + 1];
                    wetRe += w1r * wr[o + 1];
                    wetIm += w1i * wr[o + 1];
                    if (complex) {
                        dryRe -= d0i * sign * di[o];
                        dryIm += d0r * sign * di[o];
                        wetRe -= 0.0f * sign * wi[o];
                        wetIm += 0.0f * sign * wi[o];
                        dryRe -= d1i * sign * di[o + 1];
                        dryIm += d1r * sign * di[o + 1];
                        wetRe -= w1i * sign * wi[o + 1];
                        wetIm += w1r * sign * wi[o + 1];
                    }
                    dryOutReal[row][ts][qs] = dryRe;
                    dryOutImag[row][ts][qs] = dryIm;
                    wetOutReal[row][ts][qs] = wetRe;
                    wetOutImag[row][ts][qs] = wetIm;
                }
            }
        }
    }

    /**
     * Interpolates the output phases over the slots of the frame ({@code interp_phase}).
     */
    private void interpolatePhases() {
        for (var pb = 0; pb < bands; pb++) {
            var prevSlot = -1.0f;
            var currSlot = (float) parameterSlot[0];
            for (var ts = 0; ts <= (int) currSlot; ts++) {
                var alpha = ts / (currSlot - prevSlot) - prevSlot / (currSlot - prevSlot);
                setPhase(ts, pb, interpolateAngle(phaseLeftPrevious[pb], phaseLeft[0][pb], alpha),
                        interpolateAngle(phaseRightPrevious[pb], phaseRight[0][pb], alpha));
            }
            for (var ps = 1; ps < sets; ps++) {
                prevSlot = parameterSlot[ps - 1];
                currSlot = parameterSlot[ps];
                for (var ts = (int) prevSlot + 1; ts <= (int) currSlot; ts++) {
                    var alpha = ts / (currSlot - prevSlot) - prevSlot / (currSlot - prevSlot);
                    setPhase(ts, pb, interpolateAngle(phaseLeft[ps - 1][pb], phaseLeft[ps][pb], alpha),
                            interpolateAngle(phaseRight[ps - 1][pb], phaseRight[ps][pb], alpha));
                }
            }
        }
    }

    private void setPhase(int ts, int pb, float left, float right) {
        rPhaseReal[ts][2 * pb] = (float) StrictMath.cos(left);
        rPhaseImag[ts][2 * pb] = (float) StrictMath.sin(left);
        rPhaseReal[ts][2 * pb + 1] = (float) StrictMath.cos(right);
        rPhaseImag[ts][2 * pb + 1] = (float) StrictMath.sin(right);
    }

    private static float interpolateAngle(float angle1, float angle2, float alpha) {
        var pi = (float) P_PI;
        while (angle2 - angle1 > pi) {
            angle1 = angle1 + 2.0f * pi;
        }
        while (angle1 - angle2 > pi) {
            angle2 = angle2 + 2.0f * pi;
        }
        return (1 - alpha) * angle1 + alpha * angle2;
    }

    /**
     * Shapes the temporal envelope of the decorrelated signal in the QMF domain ({@code subbandTP}).
     */
    private void subbandTemporalProcess(int ts) {
        if (stpUpdateCounter == STP_UPDATE_ENERGY_RATE) {
            stpUpdateCounter = 1;
            oldDryEnergy[0] = runDryEnergy[0];
            oldWetEnergy[0] = runWetEnergy[0];
            oldWetEnergy[1] = runWetEnergy[1];
        } else {
            stpUpdateCounter++;
        }
        for (var ch = 0; ch < 2; ch++) {
            var dr = dryOutReal[ch][ts];
            var di = dryOutImag[ch][ts];
            var wr = wetOutReal[ch][ts];
            var wi = wetOutImag[ch][ts];
            var o = ch * BP_SIZE;
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
            stpQmfDryReal[o] = r0;
            stpQmfDryImag[o] = i0;
            stpQmfWetReal[o] = r1;
            stpQmfWetImag[o] = i1;
            stpQmfDryReal[o + 1] = dr[6] + dr[7];
            stpQmfDryImag[o + 1] = di[6] + di[7];
            stpQmfWetReal[o + 1] = wr[6] + wr[7];
            stpQmfWetImag[o + 1] = wi[6] + wi[7];
            stpQmfDryReal[o + 2] = dr[8] + dr[9];
            stpQmfDryImag[o + 2] = di[8] + di[9];
            stpQmfWetReal[o + 2] = wr[8] + wr[9];
            stpQmfWetImag[o + 2] = wi[8] + wi[9];
            for (var i = 3; i < BP_SIZE; i++) {
                stpQmfDryReal[o + i] = dr[i - 3 + 10];
                stpQmfDryImag[o + i] = di[i - 3 + 10];
                stpQmfWetReal[o + i] = wr[i - 3 + 10];
                stpQmfWetImag[o + i] = wi[i - 3 + 10];
            }
        }
        var dryEnergy = 0.0f;
        for (var n = 1; n < BP_SIZE; n++) {
            var gain = STP_BP[n] * STP_GF[n];
            var re = stpQmfDryReal[n];
            re += stpQmfDryReal[BP_SIZE + n];
            re *= gain;
            var im = stpQmfDryImag[n];
            im += stpQmfDryImag[BP_SIZE + n];
            im *= gain;
            dryEnergy += re * re;
            dryEnergy += im * im;
        }
        runDryEnergy[0] = (float) (STP_LPF_COEFF1 * runDryEnergy[0] + (1.0 - STP_LPF_COEFF1) * dryEnergy);
        dryEnergy /= oldDryEnergy[0] + ABS_THR;
        for (var ch = 0; ch < 2; ch++) {
            var o = ch * BP_SIZE;
            var wetEnergy = 0.0f;
            for (var n = 1; n < BP_SIZE; n++) {
                var temp = stpQmfWetReal[o + n] * stpQmfWetReal[o + n];
                temp += stpQmfWetImag[o + n] * stpQmfWetImag[o + n];
                temp *= STP_BP2[n] * STP_GF[n] * STP_GF[n];
                wetEnergy += temp;
            }
            runWetEnergy[ch] = (float) (STP_LPF_COEFF1 * runWetEnergy[ch] + (1.0 - STP_LPF_COEFF1) * wetEnergy);
            wetEnergy /= oldWetEnergy[ch] + ABS_THR;
            var scale = (float) Math.sqrt(dryEnergy / (wetEnergy + 1e-9));
            var damp = 0.1f;
            scale = damp + (1 - damp) * scale;
            if (scale > STP_SCALE_LIMIT) {
                scale = (float) STP_SCALE_LIMIT;
            }
            if (scale < 1.0 / STP_SCALE_LIMIT) {
                scale = (float) (1.0 / STP_SCALE_LIMIT);
            }
            scale = (float) (STP_LPF_COEFF2 * scale + (1.0 - STP_LPF_COEFF2) * previousStpScale[ch]);
            previousStpScale[ch] = scale;
            var shaping = temporalShaping == 1 && stpEnabled[ch];
            var dr = dryOutReal[ch][ts];
            var di = dryOutImag[ch][ts];
            var wr = wetOutReal[ch][ts];
            var wi = wetOutImag[ch][ts];
            for (var n = 0; n < HP_LIMIT; n++) {
                float temp;
                if (n < 10) {
                    temp = shaping ? scale * STP_BP[HYBRID_TO_QMF[n]] : 1.f;
                } else {
                    temp = shaping ? scale * STP_BP[n + 3 - 10] : 1.f;
                }
                dr[n] += wr[n] * temp;
                di[n] += wi[n] * temp;
            }
            var temp = shaping ? scale : 1.f;
            for (var n = HP_LIMIT; n < HYBRID; n++) {
                dr[n] += wr[n] * temp;
                di[n] += wi[n] * temp;
            }
        }
    }

    /**
     * Applies guided envelope shaping ({@code spatialDecReshapeBBEnv}).
     */
    private void reshapeEnvelope() {
        extractEnvelope(-1, 2, gesEnvelopeDownmix);
        for (var ch = 0; ch < 2; ch++) {
            extractEnvelope(ch, ch, gesEnvelopeDry);
            if (!gesEnabled[ch]) {
                continue;
            }
            for (var ts = 0; ts < slots; ts++) {
                var target = envelopeShape[ch][ts] * gesEnvelopeDownmix[ts];
                var dryFactor = target / (gesEnvelopeDry[ts] + 1e-9f);
                var dryAmplitude = 0.0f;
                var wetAmplitude = 0.0f;
                var dr = dryOutReal[ch][ts];
                var di = dryOutImag[ch][ts];
                var wr = wetOutReal[ch][ts];
                var wi = wetOutImag[ch][ts];
                for (var qs = 6; qs < HYBRID; qs++) {
                    dryAmplitude += dr[qs] * dr[qs] + di[qs] * di[qs];
                    wetAmplitude += wr[qs] * wr[qs] + wi[qs] * wi[qs];
                }
                var ratio = (float) Math.sqrt(wetAmplitude / (dryAmplitude + ABS_THR));
                dryFactor = Math.min(4.f, Math.max(0.25f, Math.max(0.f, dryFactor + ratio * (dryFactor - 1.f))));
                for (var qs = 6; qs < HYBRID; qs++) {
                    dr[qs] *= dryFactor;
                    di[qs] *= dryFactor;
                }
            }
        }
    }

    /**
     * Extracts the broadband envelope of the downmix (channel -1) or of an output channel ({@code extractBBEnv}).
     */
    private void extractEnvelope(int ch, int state, float[] envelope) {
        var kernels = MpsTables.ENVELOPE_KERNELS;
        for (var ts = 0; ts < slots; ts++) {
            Arrays.fill(gesSlotEnergy[ts], 0.0f);
        }
        for (var ts = 0; ts < slots; ts++) {
            var energy = gesSlotEnergy[ts];
            if (ch < 0) {
                var re = hybridInputReal[0][ts];
                var im = hybridInputImag[0][ts];
                for (var qs = 0; qs < HYBRID; qs++) {
                    energy[kernels[qs]] += re[qs] * re[qs] + im[qs] * im[qs];
                }
            } else {
                var dr = dryOutReal[ch][ts];
                var di = dryOutImag[ch][ts];
                var wr = wetOutReal[ch][ts];
                var wi = wetOutImag[ch][ts];
                for (var qs = 0; qs < HYBRID; qs++) {
                    var re = dr[qs] + wr[qs];
                    var im = di[qs] + wi[qs];
                    energy[kernels[qs]] += re * re + im * im;
                }
            }
        }
        var start = 10;
        var end = 18;
        var part = gesPartPrevious[state];
        var norm = gesNormPrevious[state];
        for (var ts = 0; ts < slots; ts++) {
            var energy = gesSlotEnergy[ts];
            var frame = 0.0f;
            for (var pb = start; pb <= end; pb++) {
                part[pb] = (1 - GES_ALPHA) * energy[pb] + GES_ALPHA * part[pb];
                frame += energy[pb];
            }
            frame /= end - start + 1;
            frame = (1 - GES_ALPHA) * frame + GES_ALPHA * gesFramePrevious[state];
            gesFramePrevious[state] = frame;
            var env = 0.0f;
            for (var pb = start; pb <= end; pb++) {
                env += energy[pb] * (frame / (part[pb] + ABS_THR));
            }
            norm = (1 - GES_BETA) * env + GES_BETA * norm;
            envelope[ts] = (float) Math.sqrt(env / (norm + ABS_THR));
        }
        gesNormPrevious[state] = norm;
    }
}
