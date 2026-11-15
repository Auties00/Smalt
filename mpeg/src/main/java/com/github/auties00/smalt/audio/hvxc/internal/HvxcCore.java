/* Ports hvxcDec.c, hvxcQAmDec.c, hvxcQLspDec.c, hvxcVxcDec.c, hvxcItpDec.c, hvxcProtect.c and IPC_bpf of
 * hvxcFltDec.c of the ISO/IEC 14496-3 reference software (MPEG-4 Audio, subpart 2, HVXC), with pan_lspdec.c,
 * pan_stab.c and pan_sort.c. Copyright Sony Corporation, Matsushita Communication Industrial; ISO/IEC 14496-3
 * reference software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.hvxc.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.hvxc.internal.HvxcDsp.FRAME;
import static com.github.auties00.smalt.audio.hvxc.internal.HvxcDsp.ORDER;
import static com.github.auties00.smalt.audio.hvxc.internal.HvxcDsp.SAMPLE;

/**
 * Decodes HVXC frames into speech (ISO/IEC 14496-3 subclause 2.5): the parameter decoding of the LSPs, the
 * harmonic magnitudes and the stochastic excitation, the parameter interpolation that changes the speed, the voiced
 * and unvoiced synthesis, the variable rate tools and the error concealment of ER HVXC.
 *
 * <p>A frame arrives as the bits of the full rate frame in the order of the fixed rate syntax (or of the error
 * resilient syntax, which {@link #decode} first puts back into that order), zero-padded to ten bytes.
 *
 * <p>Instances are not thread-safe.
 */
public final class HvxcCore {
    /**
     * The 2 kbit/s rate mode ({@code DEC2K}).
     */
    public static final int RATE_2K = 0;

    /**
     * The 4 kbit/s rate mode ({@code DEC4K}).
     */
    public static final int RATE_4K = 1;

    /**
     * The 3.7 kbit/s rate mode ({@code DEC3K}).
     */
    public static final int RATE_3K = 2;

    /**
     * The test mode bit that skips the postfilter and the post-processing.
     */
    public static final int TEST_NO_POSTFILTER = 0x1;

    /**
     * The test mode bit that resets the harmonic phases to zero instead of random values.
     */
    public static final int TEST_ZERO_PHASE = 0x2;

    /**
     * The test mode bit that disables the noise of mixed voiced frames.
     */
    public static final int TEST_NO_NOISE = 0x4;

    /**
     * The test mode bit that silences the output of the time domain decoder and its random excitations.
     */
    public static final int TEST_NO_EXCITATION = 0x8;

    private static final int BGN_INTERVAL = 8;

    private static final int BGN_INTERVAL_RX = 12;

    private static final float PREDICTION = (float) 0.7;

    private static final float MIN_GAP = (float) (4. / 256.);

    private static final int HARMONICS = 44;

    private static final int[] V2K = {8, 9, 10, 11, 12, 19, 21, 30, 31, 32, 33, 34, 20, 35, 36, 37, 38, 2, 0, 1, 13,
            14, 15, 16, 17, 18, 39, 22, 23, 24, 25, 26, 27, 28, 29, 3, 4, 5, 6, 7};

    private static final int[] UV2K = {11, 12, 13, 14, 15, 16, 17, 18, 19, 22, 23, 24, 20, 21, 25, 26, 27, 2, 0, 1, 28,
            29, 30, 31, 32, 33, 3, 4, 5, 6, 34, 35, 36, 37, 38, 39, 7, 8, 9, 10};

    private static final int[] V4K = {8, 9, 10, 11, 12, 19, 20, 21, 22, 63, 64, 65, 28, 66, 67, 68, 69, 2, 0, 1, 13,
            14, 15, 16, 17, 18, 77, 55, 56, 57, 58, 59, 60, 61, 62, 3, 4, 5, 6, 7, 29, 70, 71, 72, 73, 74, 75, 76, 23,
            24, 25, 26, 27, 78, 79, 30, 33, 34, 35, 36, 37, 38, 39, 40, 41, 31, 42, 43, 44, 45, 46, 47, 48, 49, 32, 50,
            51, 52, 53, 54};

    private static final int[] UV4K = {11, 12, 13, 14, 15, 16, 17, 18, 19, 34, 35, 36, 20, 37, 38, 39, 40, 2, 0, 1,
            48, 49, 50, 51, 52, 53, 3, 4, 5, 6, 54, 55, 56, 57, 58, 59, 7, 8, 9, 10, 21, 41, 42, 43, 44, 45, 46, 47, 60,
            61, 62, 63, 64, 22, 23, 24, 65, 66, 67, 68, 69, 25, 26, 27, 70, 71, 72, 73, 74, 28, 29, 30, 75, 76, 77, 78,
            79, 31, 32, 33};

    private static final int[] V3K = {8, 9, 10, 11, 12, 19, 20, 21, 22, 57, 58, 59, 28, 60, 61, 62, 63, 2, 0, 1, 13,
            14, 15, 16, 17, 18, 71, 49, 50, 51, 52, 53, 54, 55, 56, 3, 4, 5, 6, 7, 29, 64, 65, 66, 67, 68, 69, 70, 23,
            24, 25, 26, 27, 72, 73, 30, 32, 33, 34, 35, 36, 37, 38, 39, 40, 31, 41, 42, 43, 44, 45, 46, 47, 48, 74, 75,
            76, 77, 78, 79};

    private static final int[] UV3K = {11, 12, 13, 14, 15, 16, 17, 18, 19, 31, 32, 33, 20, 34, 35, 36, 37, 2, 0, 1,
            45, 46, 47, 48, 49, 50, 3, 4, 5, 6, 51, 52, 53, 54, 55, 56, 7, 8, 9, 10, 21, 38, 39, 40, 41, 42, 43, 44, 57,
            58, 59, 60, 61, 22, 23, 24, 62, 63, 64, 65, 66, 25, 26, 27, 67, 68, 69, 70, 71, 28, 29, 30, 72, 73, 74, 75,
            76, 77, 78, 79};

    private static final int[] SC_V2K = {8, 9, 10, 11, 12, 19, 21, 30, 31, 32, 33, 34, 20, 35, 36, 37, 38, 2, 0, 1,
            13, 14, 15, 16, 17, 18, 39, 22, 23, 24, 25, 26, 27, 28, 29, 3, 4, 5, 6, 7, 45, 71, 72, 73, 74, 75, 76, 77,
            40, 41, 42, 43, 44, 78, 79, 46, 49, 50, 51, 52, 53, 54, 55, 56, 57, 47, 58, 59, 60, 61, 62, 63, 64, 65, 48,
            66, 67, 68, 69, 70};

    private static final int[] SC_UV2K = {11, 12, 13, 14, 15, 16, 17, 18, 19, 22, 23, 24, 20, 21, 25, 26, 27, 2, 0, 1,
            28, 29, 30, 31, 32, 33, 3, 4, 5, 6, 34, 35, 36, 37, 38, 39, 7, 8, 9, 10, 40, 53, 54, 55, 56, 57, 58, 59, 60,
            61, 62, 63, 64, 41, 42, 43, 65, 66, 67, 68, 69, 44, 45, 46, 70, 71, 72, 73, 74, 47, 48, 49, 75, 76, 77, 78,
            79, 50, 51, 52};

    private static final int[] VAR_UV2K = {0, 1, 11, 12, 13, 14, 15, 16, 17, 18, 19, 22, 23, 24, 20, 21, 25, 26, 27, 2,
            3, 4, 5, 6, 7, 8, 9, 10};

    private static final int[] VAR_V2K = {0, 1, 8, 9, 10, 11, 12, 19, 21, 30, 31, 32, 33, 34, 20, 35, 36, 37, 38, 2,
            13, 14, 15, 16, 17, 18, 39, 22, 23, 24, 25, 26, 27, 28, 29, 3, 4, 5, 6, 7};

    private static final int[] VAR_BGN4K = {0, 1, 2, 8, 9, 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 17, 18, 22, 23, 24,
            3, 4, 5, 6, 7};

    private static final int[] VAR_UV4K = {0, 1, 11, 12, 13, 14, 15, 16, 17, 18, 19, 22, 23, 24, 20, 21, 25, 26, 27, 2,
            28, 29, 30, 31, 32, 33, 3, 4, 5, 6, 34, 35, 36, 37, 38, 39, 7, 8, 9, 10};

    private static final int[] VAR_V4K = {0, 1, 8, 9, 10, 11, 12, 19, 20, 21, 22, 63, 64, 65, 28, 66, 67, 68, 69, 2,
            13, 14, 15, 16, 17, 18, 77, 55, 56, 57, 58, 59, 60, 61, 62, 3, 4, 5, 6, 7, 29, 70, 71, 72, 73, 74, 75, 76,
            23, 24, 25, 26, 27, 78, 79, 30, 33, 34, 35, 36, 37, 38, 39, 40, 41, 31, 42, 43, 44, 45, 46, 47, 48, 49, 32,
            50, 51, 52, 53, 54};

    private static final int[] VAR_VSC2K = {0, 1, 8, 9, 10, 11, 12, 19, 21, 30, 31, 32, 33, 34, 20, 35, 36, 37, 38, 2,
            13, 14, 15, 16, 17, 18, 39, 22, 23, 24, 25, 26, 27, 28, 29, 3, 4, 5, 6, 7, 45, 71, 72, 73, 74, 75, 76, 77,
            40, 41, 42, 43, 44, 78, 79, 46, 49, 50, 51, 52, 53, 54, 55, 56, 57, 47, 58, 59, 60, 61, 62, 63, 64, 65, 48,
            66, 67, 68, 69, 70};

    /**
     * The interpolation filter of the harmonic magnitude resampling ({@code f_coef}): a Hann windowed sinc of 64
     * taps at an oversampling of 8.
     */
    private static final float[] RESAMPLING = resamplingFilter();

    private final boolean variable;

    private final int rateMode;

    private final boolean extension;

    private final boolean scalable;

    private final int testMode;

    private final boolean errorProtection;

    private final float speed;

    private final float pitchScale;

    private final HvxcRandom random = new HvxcRandom();

    private final VoicedSynthesizer voiced;

    private final UnvoicedSynthesizer unvoiced;

    private int decMode;

    private int nbs;

    private int fr0;

    private float targetP;

    private final int[] idVuv2 = new int[2];

    private final int[] bgnFlag2 = new int[2];

    private final float[] pch2 = new float[2];

    private final float[][] am2 = new float[2][SAMPLE / 2];

    private final float[][] qLsp2 = new float[2][ORDER];

    private final float[][] qRes2 = new float[2][FRAME];

    private final float[][] lspQueue = new float[4][ORDER];

    private final int[][] celpShape0 = new int[2][2];

    private final int[][] celpGain0 = new int[2][2];

    private final int[][] celpShape1 = new int[2][4];

    private final int[][] celpGain1 = new int[2][4];

    private int prevVuv;

    private final int[] encLsp = new int[6];

    private int encVuv;

    private int encBgn;

    /**
     * The excitation indices of the last unpacked frame, in one storage that the unvoiced fields ({@code idUV}: the
     * shapes and gains of the two stages) and the voiced fields ({@code idV}: pitch, spectral shapes and gain, the
     * 4 kbit/s shapes) share, as the reference decoder's union does: a frame that reuses fields of another kind
     * reads the values it overlaps.
     */
    private final int[] enc = new int[12];

    private static final int SHAPE0 = 0;

    private static final int GAIN0 = 2;

    private static final int SHAPE1 = 4;

    private static final int GAIN1 = 8;

    private static final int PITCH = 0;

    private static final int AM_S0 = 1;

    private static final int AM_S1 = 2;

    private static final int AM_G = 3;

    private static final int AM_4K = 4;

    private int decFrames;

    private final float[] prevQLsp = new float[ORDER];

    private final float[] prevQLsp2 = new float[ORDER];

    private int bgnCount;

    private int prevGainId;

    private int synFrames;

    private final int[] vuvs = new int[2];

    private final float[] pchs = new float[2];

    private final float[][] qLsps = new float[2][ORDER];

    private float feneqOld;

    private final int[] crcEp = new int[2];

    private int crcShape;

    private int crc4k;

    private final int[] epStatus = new int[2];

    private int crcErrors;

    private float mute = 1.0f;

    private final float[] hpfState = new float[4];

    private final float[] lpfState = new float[4];

    private final double[] hpf4State = new double[8];

    private final byte[] reordered = new byte[10];

    private final byte[] bits = new byte[80];

    private int position;

    private final int[] lspIndex = new int[6];

    private final float[] tmpQLsp = new float[ORDER];

    private final float[] tmpAm = new float[SAMPLE / 2];

    private final float[] qedvec = new float[HARMONICS + 1];

    private final float[] qam2 = new float[SAMPLE / 2];

    private final float[] target = new float[76];

    private final float[] resampled = new float[SAMPLE / 2 + 9];

    private final float[] enhancedLsp = new float[ORDER];

    private final float[] gaussianVector = new float[FRAME];

    private final float[] modLsp = new float[ORDER];

    private final float[] modAm = new float[SAMPLE / 2];

    private final float[] modRes = new float[FRAME];

    private final int[] synthesisVuv = new int[1];

    private final int[] harmonicVuv = new int[2];

    private final float[] voicedOut = new float[FRAME];

    private final float[] unvoicedOut = new float[FRAME];

    private final float[] mixed = new float[FRAME];

    /**
     * Constructs the decoder of one HVXC object.
     *
     * @param variable {@code HVXCvarMode}: whether the object uses the variable rate syntax
     * @param rateMode {@code HVXCrateMode}: {@link #RATE_2K}, {@link #RATE_4K} or {@link #RATE_3K}
     * @param extension {@code extensionFlag}: whether frames use the error resilient bit order
     * @param scalable whether the scalable bit order applies: {@code var_ScalableFlag}, or an error resilient object
     * decoded with its enhancement layer
     * @param errorProtection whether the error protection tool reports the class CRCs, which turns on the error
     * concealment
     * @param lowDelay whether the decoder runs in the low delay mode
     * @param testMode the {@code TEST_} bits of the conformance procedure, 0 for normal operation
     * @param speed the speed change factor, at least 0.2
     * @param pitch the pitch change factor
     */
    public HvxcCore(boolean variable, int rateMode, boolean extension, boolean scalable, boolean errorProtection,
                    boolean lowDelay, int testMode, float speed, float pitch) {
        this.variable = variable;
        this.rateMode = rateMode;
        this.extension = extension;
        this.scalable = scalable;
        this.errorProtection = errorProtection;
        this.testMode = testMode;
        this.speed = speed;
        this.pitchScale = (float) (1.0 / pitch);
        var postFilter = (testMode & TEST_NO_POSTFILTER) == 0;
        this.voiced = new VoicedSynthesizer(random, lowDelay, postFilter, (testMode & TEST_ZERO_PHASE) != 0,
                (testMode & TEST_NO_NOISE) == 0, HvxcDsp.window(lowDelay));
        this.unvoiced = new UnvoicedSynthesizer(lowDelay, postFilter);
        for (var i = 0; i < 4; i++) {
            for (var j = 0; j < ORDER; j++) {
                lspQueue[i][j] = 0.5f / 11.0f * (j + 1);
            }
        }
        for (var i = 0; i < ORDER; i++) {
            prevQLsp[i] = (float) (i + 1) / (float) (ORDER + 1);
            prevQLsp2[i] = prevQLsp[i];
        }
    }

    /**
     * {@return the largest number of samples one frame decodes to, at the speed change factor of the decoder}
     */
    public int maxSamples() {
        return FRAME * ((int) StrictMath.ceil(1.0 / speed) + 1);
    }

    /**
     * Decodes one frame.
     *
     * @param frame the frame, at least ten bytes, zero-padded; its contents are left unchanged
     * @param mode the rate of this frame: the object's rate mode, or {@link #RATE_4K} for a 2 kbit/s object decoded
     * with its enhancement layer
     * @param classErrors whether each error sensitivity class failed its CRC, in class order; read only with error
     * protection
     * @param samples receives the samples, in 16-bit units
     * @param offset the index of the first sample written
     * @return the number of samples written, a multiple of 160 up to {@link #maxSamples()}
     */
    public int decode(byte[] frame, int mode, boolean[] classErrors, float[] samples, int offset) {
        decMode = mode;
        var input = frame;
        if (extension) {
            reorder(frame, reordered);
            input = reordered;
            if (errorProtection) {
                interpretCrc(classErrors);
                badFrameStatus();
            }
        }
        if (variable) {
            parameters1stVariable(input);
        } else {
            parameters1st(input);
        }
        var count = 0;
        while (nbs == fr0) {
            parameters2nd(fr0 - 1);
            var ratio = targetP - (float) fr0 + 1;
            interpolate(ratio);
            synthesize(samples, offset + count * FRAME);
            targetP += speed;
            fr0 = (int) StrictMath.ceil(targetP);
            count++;
        }
        nbs++;
        return count * FRAME;
    }

    private void loadBits(byte[] frame, int from, int to) {
        for (var i = from; i < to; i++) {
            bits[i] = (byte) ((frame[i >>> 3] >>> (7 - (i & 7))) & 1);
        }
    }

    private int take(int count) {
        var value = 0;
        for (var i = 0; i < count; i++) {
            value = (value << 1) | bits[position++];
        }
        return value;
    }

    private boolean fourKbit() {
        return decMode == RATE_4K || decMode == RATE_3K;
    }

    /**
     * Unpacks the indices of a fixed rate frame ({@code Bit2Prm}).
     */
    private void unpack(byte[] frame) {
        position = 0;
        loadBits(frame, 0, 40);
        encLsp[0] = take(5);
        encLsp[1] = take(0);
        encLsp[2] = take(7);
        encLsp[3] = take(5);
        encLsp[4] = take(1);
        encVuv = take(2);
        if (encVuv == 0) {
            for (var i = 0; i < 2; i++) {
                enc[SHAPE0 + i] = take(6);
                enc[GAIN0 + i] = take(4);
            }
        } else {
            enc[PITCH] = take(7);
            enc[AM_S0] = take(4);
            enc[AM_S1] = take(4);
            enc[AM_G] = take(5);
        }
        if (fourKbit()) {
            loadBits(frame, 40, 80);
            encLsp[5] = take(8);
            if (encVuv == 0) {
                for (var i = 0; i < 4; i++) {
                    enc[SHAPE1 + i] = take(5);
                    enc[GAIN1 + i] = take(3);
                }
            } else {
                enc[AM_4K + 0] = take(7);
                enc[AM_4K + 1] = take(10);
                enc[AM_4K + 2] = take(9);
                enc[AM_4K + 3] = take(6);
            }
        }
    }

    /**
     * Unpacks the indices of a variable rate frame ({@code Bit2PrmVR}).
     */
    private void unpackVariable(byte[] frame) {
        position = 0;
        loadBits(frame, 0, 40);
        var tmpFlag = 0;
        var wide = rateMode == RATE_3K || rateMode == RATE_4K || scalable;
        encVuv = take(2);
        if (encVuv != 1) {
            encLsp[0] = take(5);
            encLsp[1] = take(0);
            encLsp[2] = take(7);
            encLsp[3] = take(5);
            encLsp[4] = take(1);
            if (encVuv == 0) {
                for (var i = 0; i < 2; i++) {
                    if (wide) {
                        enc[SHAPE0 + i] = take(6);
                    }
                    enc[GAIN0 + i] = take(4);
                }
            } else {
                enc[PITCH] = take(7);
                enc[AM_S0] = take(4);
                enc[AM_S1] = take(4);
                enc[AM_G] = take(5);
            }
            if (wide) {
                loadBits(frame, 40, 80);
                if (encVuv != 0) {
                    encLsp[5] = take(8);
                    enc[AM_4K + 0] = take(7);
                    enc[AM_4K + 1] = take(10);
                    enc[AM_4K + 2] = take(9);
                    enc[AM_4K + 3] = take(6);
                }
            }
        } else if (wide) {
            tmpFlag = take(1);
            if (tmpFlag != 0) {
                encLsp[0] = take(5);
                encLsp[1] = take(0);
                encLsp[2] = take(7);
                encLsp[3] = take(5);
                encLsp[4] = take(1);
                enc[GAIN0 + 0] = take(4);
                enc[GAIN0 + 1] = enc[GAIN0 + 0];
            }
            for (var i = 0; i < 2; i++) {
                enc[SHAPE0 + i] = random.next() % 64;
            }
        }
        if (encVuv == 1) {
            encVuv = prevVuv;
            encBgn = tmpFlag != 0 ? 2 : 1;
        } else {
            prevVuv = encVuv;
            encBgn = 0;
        }
    }

    /**
     * Decodes the parameters of a fixed rate frame and queues them ({@code IPC_DecParams1st}).
     */
    private void parameters1st(byte[] frame) {
        var slot = decFrames % 2;
        if (crcEp[1] == 0) {
            unpack(frame);
        }
        System.arraycopy(encLsp, 0, lspIndex, 0, 5);
        var vuv = encVuv;
        float pitch;
        if (vuv == 0) {
            pitch = 148.0f;
            for (var i = 0; i < 2; i++) {
                if (crcEp[1] == 0) {
                    celpShape0[slot][i] = enc[SHAPE0 + i];
                    celpGain0[slot][i] = enc[GAIN0 + i];
                } else {
                    celpShape0[slot][i] = random.next() % 64;
                    celpGain0[slot][i] = enc[GAIN0 + 1];
                }
            }
        } else {
            pitch = enc[PITCH] + 20.0f;
        }
        if (fourKbit()) {
            lspIndex[5] = encLsp[5];
            if (vuv == 0) {
                for (var i = 0; i < 4; i++) {
                    if (crcEp[1] == 0) {
                        celpShape1[slot][i] = enc[SHAPE1 + i];
                        celpGain1[slot][i] = enc[GAIN1 + i];
                    } else {
                        celpShape1[slot][i] = random.next() % 32;
                        celpGain1[slot][i] = enc[GAIN1 + 3];
                    }
                }
            }
        }
        var sameVuv = (idVuv2[1] != 0 && vuv != 0) || (idVuv2[1] == 0 && vuv == 0);
        decodeLsp(prevQLsp, tmpQLsp, lspIndex, true, sameVuv);
        System.arraycopy(tmpQLsp, 0, prevQLsp, 0, ORDER);
        if (fourKbit() && crcEp[1] == 0) {
            enhanceLsp(lspIndex[5], tmpQLsp);
        }
        queue(tmpQLsp);
        idVuv2[0] = idVuv2[1];
        idVuv2[1] = vuv;
        pch2[0] = pch2[1];
        pch2[1] = harmonics(tmpAm, pitch, vuv);
        System.arraycopy(am2[1], 0, am2[0], 0, SAMPLE / 2);
        var down = !sameVuv && epStatus[1] == 7 ? (float) (0.6 * mute) : mute;
        for (var j = 0; j < SAMPLE / 2; j++) {
            am2[1][j] = tmpAm[j] * down;
        }
        decFrames++;
    }

    /**
     * Decodes the parameters of a variable rate frame and queues them ({@code IPC_DecParams1stVR}).
     */
    private void parameters1stVariable(byte[] frame) {
        var slot = decFrames % 2;
        unpackVariable(frame);
        var bgn = encBgn;
        var vuv = encVuv;
        System.arraycopy(encLsp, 0, lspIndex, 0, 5);
        float pitch;
        if (vuv == 0) {
            pitch = 148.0f;
            celpShape0[slot][0] = enc[SHAPE0 + 0];
            celpShape0[slot][1] = enc[SHAPE0 + 1];
            celpGain0[slot][0] = enc[GAIN0 + 0];
            celpGain0[slot][1] = enc[GAIN0 + 1];
        } else {
            pitch = enc[PITCH] + 20.0f;
        }
        if (fourKbit() || scalable) {
            lspIndex[5] = encLsp[5];
            if (vuv == 0) {
                System.arraycopy(enc, SHAPE1, celpShape1[slot], 0, 4);
                System.arraycopy(enc, GAIN1, celpGain1[slot], 0, 4);
            }
        }
        decodeLsp(prevQLsp, tmpQLsp, lspIndex, false, true);
        if (rateMode == RATE_3K || rateMode == RATE_4K || scalable) {
            if (bgn != 0) {
                if (bgn == 2) {
                    System.arraycopy(prevQLsp, 0, prevQLsp2, 0, ORDER);
                    System.arraycopy(tmpQLsp, 0, prevQLsp, 0, ORDER);
                    bgnCount = 0;
                }
                var rnd = bgnCount + (random.next() % 7) - 3;
                if (rnd < 0 || rnd >= BGN_INTERVAL_RX) {
                    rnd = bgnCount;
                }
                for (var i = 0; i < ORDER; i++) {
                    tmpQLsp[i] = (float) ((prevQLsp2[i] * (2.0 * BGN_INTERVAL_RX - 1.0 - 2.0 * rnd)
                            + prevQLsp[i] * (1.0 + 2.0 * rnd)) / (BGN_INTERVAL_RX * 2.0));
                }
                if (bgnCount < BGN_INTERVAL_RX) {
                    bgnCount++;
                }
            } else {
                System.arraycopy(prevQLsp, 0, prevQLsp2, 0, ORDER);
                System.arraycopy(tmpQLsp, 0, prevQLsp, 0, ORDER);
                if (fourKbit() && (vuv == 2 || vuv == 3)) {
                    enhanceLsp(lspIndex[5], tmpQLsp);
                }
                bgnCount = 0;
            }
        } else {
            if (bgn != 0) {
                for (var i = 0; i < ORDER; i++) {
                    tmpQLsp[i] = (float) ((prevQLsp2[i] * (2.0 * BGN_INTERVAL - 1.0 - 2.0 * bgnCount)
                            + prevQLsp[i] * (1.0 + 2.0 * bgnCount)) / (BGN_INTERVAL * 2.0));
                }
                bgnCount++;
            } else {
                System.arraycopy(prevQLsp, 0, prevQLsp2, 0, ORDER);
                System.arraycopy(tmpQLsp, 0, prevQLsp, 0, ORDER);
                if (bgnCount == BGN_INTERVAL && celpGain0[slot][0] <= prevGainId + 2) {
                    System.arraycopy(prevQLsp2, 0, tmpQLsp, 0, ORDER);
                }
                bgnCount = 0;
            }
            prevGainId = celpGain0[slot][1];
        }
        queue(tmpQLsp);
        idVuv2[0] = idVuv2[1];
        idVuv2[1] = vuv;
        bgnFlag2[0] = bgnFlag2[1];
        bgnFlag2[1] = bgn;
        pch2[0] = pch2[1];
        pch2[1] = harmonics(tmpAm, pitch, vuv);
        System.arraycopy(am2[1], 0, am2[0], 0, SAMPLE / 2);
        System.arraycopy(tmpAm, 0, am2[1], 0, SAMPLE / 2);
        decFrames++;
    }

    /**
     * Halves the LSPs of the frame and queues them two frames ahead.
     */
    private void queue(float[] lsp) {
        var row = lspQueue[(decFrames + 2) % 4];
        for (var j = 0; j < ORDER; j++) {
            lsp[j] = (float) (lsp[j] * .5);
            row[j] = lsp[j];
        }
    }

    /**
     * Decodes the excitation of the two frames around the synthesis point ({@code IPC_DecParams2nd},
     * {@code IPC_DecParams2ndVR}).
     */
    private void parameters2nd(int frame) {
        frame += 2;
        System.arraycopy(lspQueue[frame % 4], 0, qLsp2[0], 0, ORDER);
        System.arraycopy(lspQueue[(frame + 1) % 4], 0, qLsp2[1], 0, ORDER);
        for (var k = 0; k < 2; k++) {
            var slot = (frame + k) % 2;
            if (variable) {
                if ((testMode & TEST_NO_EXCITATION) != 0) {
                    Arrays.fill(qRes2[k], 0.0f);
                } else if (idVuv2[k] == 0) {
                    excitationVariable(slot, qRes2[k]);
                } else {
                    Arrays.fill(qRes2[k], 0.0f);
                }
            } else if (idVuv2[k] == 0) {
                excitation(slot, qRes2[k]);
            } else {
                Arrays.fill(qRes2[k], 0.0f);
            }
        }
        if (!variable) {
            for (var k = 0; k < 2; k++) {
                for (var j = 0; j < FRAME; j++) {
                    qRes2[k][j] *= mute;
                }
            }
        }
    }

    /**
     * Decodes the stochastic excitation of an unvoiced frame ({@code DecRes}).
     */
    private void excitation(int slot, float[] res) {
        for (var i = 0; i < 2; i++) {
            var gain = HvxcTables.CELP_GAIN[celpGain0[slot][i]];
            var shape = celpShape0[slot][i] * (FRAME / 2);
            for (var j = 0; j < FRAME / 2; j++) {
                res[j + FRAME / 2 * i] = gain * HvxcTables.CELP_SHAPE[shape + j];
            }
        }
        if (fourKbit()) {
            var k = decMode == RATE_4K ? 4 : 3;
            for (var i = 0; i < k; i++) {
                var gain = HvxcTables.CELP_4K_GAIN[celpGain1[slot][i]];
                var shape = celpShape1[slot][i] * (FRAME / 4);
                for (var j = 0; j < FRAME / 4; j++) {
                    res[j + FRAME / 4 * i] += gain * HvxcTables.CELP_4K_SHAPE[shape + j];
                }
            }
        }
    }

    /**
     * Decodes the excitation of an unvoiced frame of the variable rate syntax ({@code DecResVR}): code book shapes,
     * or at 2 kbit/s without scalability clipped and normalized Gaussian noise.
     */
    private void excitationVariable(int slot, float[] res) {
        if (rateMode == RATE_3K || rateMode == RATE_4K || scalable) {
            for (var i = 0; i < 2; i++) {
                var gain = HvxcTables.CELP_GAIN[celpGain0[slot][i]];
                var shape = celpShape0[slot][i] * (FRAME / 2);
                for (var j = 0; j < FRAME / 2; j++) {
                    res[j + FRAME / 2 * i] = gain * HvxcTables.CELP_SHAPE[shape + j];
                }
            }
            return;
        }
        var vec = gaussianVector;
        for (var i = 0; i < 2; i++) {
            var size = FRAME / 2;
            var n = 0;
            while (n < size) {
                var fra1 = (float) random.next() / (float) HvxcRandom.MAX;
                var fra2 = (float) random.next() / (float) HvxcRandom.MAX;
                var uni1 = (float) (fra1 * 2.0 - 1.0);
                var uni2 = (float) (fra2 * 2.0 - 1.0);
                var s = uni1 * uni1 + uni2 * uni2;
                if (s <= 1.0 && s > 0.0f) {
                    var z = (float) StrictMath.sqrt((-2.0 * StrictMath.log(s)) / s);
                    vec[n] = uni1 * z;
                    n++;
                }
            }
            for (var j = 0; j < size; j++) {
                if (Math.abs(vec[j]) - 0.3f < 0.0) {
                    vec[j] = 0.0f;
                } else if (vec[j] > 0.0) {
                    vec[j] -= 0.3f;
                } else {
                    vec[j] += 0.3f;
                }
            }
            var norm = 0.0f;
            for (var j = 0; j < size; j++) {
                norm += vec[j] * vec[j];
            }
            norm = (float) StrictMath.sqrt(norm);
            for (var j = 0; j < size; j++) {
                vec[j] = norm == 0.0f ? 0.0f : vec[j] / norm;
            }
            var gain = HvxcTables.CELP_GAIN[celpGain0[slot][i]];
            for (var j = 0; j < size; j++) {
                res[j + size * i] = gain * vec[j];
            }
        }
    }

    /**
     * Decodes the LSPs of a frame ({@code pan_lspdec}, {@code pan_lspdecEP}).
     *
     * @param previous the LSPs of the previous frame
     * @param out receives the LSPs
     * @param idx the five indices of the two stage quantizer
     * @param concealment whether to apply the error concealment of ER HVXC
     * @param sameVuv whether the frame has the voicing of the previous frame
     */
    private void decodeLsp(float[] previous, float[] out, int[] idx, boolean concealment, boolean sameVuv) {
        var lsp = HvxcTables.LSP;
        var d = HvxcTables.LSP_DIFFERENCE;
        var pd = HvxcTables.LSP_PREDICTED;
        for (var i = 0; i < 10; i++) {
            out[i] = lsp[10 * idx[0] + i];
        }
        if (idx[4] == 0) {
            if (idx[2] < 64) {
                for (var i = 0; i < 5; i++) {
                    out[i] = out[i] + d[idx[2] * 5 + i];
                }
            } else {
                for (var i = 0; i < 5; i++) {
                    out[i] = out[i] - d[(idx[2] - 64) * 5 + i];
                }
            }
            if (idx[3] < 16) {
                for (var i = 0; i < 5; i++) {
                    out[5 + i] = out[5 + i] + d[320 + idx[3] * 5 + i];
                }
            } else {
                for (var i = 0; i < 5; i++) {
                    out[5 + i] = out[5 + i] - d[320 + (idx[3] - 16) * 5 + i];
                }
            }
        } else if (!concealment || (crcEp[1] == 0 && crcEp[0] == 0)) {
            var p = PREDICTION;
            for (var i = 0; i < 5; i++) {
                var predicted = (double) (p * previous[i]) + (1. - p) * out[i];
                out[i] = idx[2] < 64 ? (float) (predicted + pd[idx[2] * 5 + i])
                        : (float) (predicted - pd[(idx[2] - 64) * 5 + i]);
            }
            for (var i = 0; i < 5; i++) {
                var predicted = (double) (p * previous[5 + i]) + (1. - p) * out[5 + i];
                out[5 + i] = idx[3] < 16 ? (float) (predicted + pd[320 + idx[3] * 5 + i])
                        : (float) (predicted - pd[320 + (idx[3] - 16) * 5 + i]);
            }
        } else if (crcEp[1] != 0) {
            System.arraycopy(previous, 0, out, 0, 10);
        } else if (sameVuv) {
            var p = (float) (PREDICTION - 0.1 * (float) crcErrors);
            if (p < 0.0) {
                p = 0.0f;
            }
            for (var i = 0; i < 10; i++) {
                out[i] = (float) ((double) (p * previous[i]) + (1. - p) * out[i]);
            }
        } else {
            var minDif = 1.0f;
            for (var i = 0; i < ORDER - 1; i++) {
                var dif = Math.abs(out[i + 1] - out[i]);
                if (dif < minDif) {
                    minDif = dif;
                }
            }
            if (minDif < 0.025) {
                var x = (float) ((1. / (float) (ORDER + 1) - 0.025) / (1. / (float) (ORDER + 1) - minDif));
                for (var i = 0; i < ORDER; i++) {
                    out[i] = (float) ((double) (x * out[i]) + (1. - x) * (float) (i + 1) / (float) (ORDER + 1));
                }
            }
        }
        stabilize(out);
    }

    /**
     * Sorts the LSPs and enforces their minimum distance ({@code pan_stab}).
     */
    private static void stabilize(float[] lsp) {
        var n = ORDER;
        for (var i = n; i > 1; i--) {
            var swapped = false;
            for (var j = 0; j < i - 1; j++) {
                if (lsp[j] > lsp[j + 1]) {
                    var x0 = lsp[j];
                    lsp[j] = lsp[j + 1];
                    lsp[j + 1] = x0;
                    swapped = true;
                }
            }
            if (!swapped) {
                break;
            }
        }
        for (var i = 0; i < n; i++) {
            if (lsp[i] < MIN_GAP) {
                lsp[i] = MIN_GAP;
            }
        }
        for (var i = 0; i < n - 1; i++) {
            if (lsp[i + 1] - lsp[i] < MIN_GAP) {
                lsp[i + 1] = lsp[i] + MIN_GAP;
            }
        }
        for (var i = 0; i < n; i++) {
            if (lsp[i] > 1 - MIN_GAP) {
                lsp[i] = 1 - MIN_GAP;
            }
        }
        for (var i = n - 1; i > 0; i--) {
            if (lsp[i] - lsp[i - 1] < MIN_GAP) {
                lsp[i - 1] = lsp[i] - MIN_GAP;
            }
        }
    }

    /**
     * Adds the 4 kbit/s LSP enhancement and stabilizes the result ({@code IPC_DecLspEnh}).
     */
    private void enhanceLsp(int index, float[] qLsp) {
        var lsp = enhancedLsp;
        for (var i = 0; i < ORDER; i++) {
            lsp[i] = HvxcTables.LSP_ENHANCEMENT[index * ORDER + i] + qLsp[i];
        }
        for (var i = 0; i < 2; i++) {
            if (lsp[i + 1] - lsp[i] < 0) {
                var tmp = lsp[i + 1];
                lsp[i + 1] = lsp[i];
                lsp[i] = tmp;
            }
            if (lsp[i + 1] - lsp[i] < 0.020) {
                lsp[i + 1] = (float) (lsp[i] + 0.020);
            }
        }
        for (var i = 2; i < 6; i++) {
            if (lsp[i + 1] - lsp[i] < 0.020) {
                var tmp = (float) ((lsp[i + 1] + lsp[i]) / 2.0);
                lsp[i + 1] = (float) (tmp + 0.020 / 2.0);
                lsp[i] = (float) (tmp - 0.020 / 2.0);
            }
        }
        for (var i = 6; i < ORDER - 1; i++) {
            if (lsp[i + 1] - lsp[i] < 0) {
                var tmp = lsp[i + 1];
                lsp[i + 1] = lsp[i];
                lsp[i] = tmp;
            }
            if (lsp[i + 1] - lsp[i] < 0.020) {
                lsp[i] = (float) (lsp[i + 1] - 0.020);
            }
        }
        System.arraycopy(lsp, 0, qLsp, 0, ORDER);
    }

    private static float[] resamplingFilter() {
        var numc = 64;
        var f = new float[numc + 1];
        for (var i = 1; i <= numc / 2; i++) {
            var value = (float) (StrictMath.sin((float) i * Math.PI / 8.0) / ((float) i * Math.PI / 8.0));
            f[i + numc / 2] = value;
            f[numc / 2 - i] = value;
        }
        f[numc / 2] = 1.0f;
        for (var i = 0; i <= numc; i++) {
            f[i] = (float) (f[i] * 0.5 * (1.0 - StrictMath.cos(2.0 * Math.PI * i / numc)));
        }
        return f;
    }

    /**
     * Decodes the harmonic magnitudes of a frame ({@code harm_sew_dec}).
     *
     * @param am receives the 128 magnitudes
     * @param pitch the transmitted pitch lag
     * @param vuv the voicing of the frame
     * @return the pitch lag after the pitch change and its limits
     */
    private float harmonics(float[] am, float pitch, int vuv) {
        var sendMax = HARMONICS;
        var w0max = (float) (SAMPLE * 8 / 2 * 0.95) / (float) sendMax;
        qedvec[0] = 0.0f;
        if (vuv != 0) {
            // A background noise frame after a voiced one reads unvoiced fields as these indices (see enc); the
            // shape indices then exceed the 16 vectors, where the reference decoder reads outside its tables.
            var g = HvxcTables.AM_GAIN[enc[AM_G] & 31];
            var s0 = (enc[AM_S0] & 15) * HARMONICS;
            var s1 = (enc[AM_S1] & 15) * HARMONICS;
            for (var l = 0; l < sendMax; l++) {
                qedvec[l + 1] = g * (HvxcTables.AM_SHAPE1[s0 + l] + HvxcTables.AM_SHAPE2[s1 + l]);
            }
        } else {
            Arrays.fill(qedvec, 1, sendMax + 1, 0.0f);
        }
        if (crcShape != 0) {
            for (var i = 1; i < 6; i++) {
                qedvec[i] = (float) (qedvec[i] * (0.1 + 0.15 * (float) i));
            }
        }
        qedvec[0] = 0.0f;
        for (var i = 1; i <= sendMax; i++) {
            if (qedvec[i] <= 0) {
                qedvec[i] = 0.0f;
            }
        }
        var feneq = 0.0f;
        for (var i = 1; i <= sendMax; i++) {
            feneq += qedvec[i] * qedvec[i];
        }
        feneq = (float) StrictMath.sqrt(feneq / (float) sendMax);
        var muted = false;
        if (feneq < 1.0) {
            Arrays.fill(qedvec, 0.0f);
            muted = true;
        } else if (0.5 * (feneqOld + feneq) < 1.4) {
            Arrays.fill(qedvec, 0.0f);
            muted = true;
        }
        feneqOld = feneq;
        var pch = pitch * pitchScale;
        if (pch > 147) {
            pch = 147.0f;
        }
        if (pch < 8) {
            pch = 8.0f;
        }
        var w02 = (float) (SAMPLE * 8) / pch;
        var send2 = (int) (float) (0.95 * pch / 2.0);
        Arrays.fill(qam2, 0.0f);
        resample(qedvec, sendMax, w0max, qam2, w02, send2);
        if (!muted && fourKbit() && crc4k == 0) {
            target[0] = 0.0f;
            var k = 1;
            for (var l = 0; l < 2; l++) {
                target[k++] = HvxcTables.AM_4K_SHAPE0[enc[AM_4K + 0] * 2 + l];
            }
            for (var l = 0; l < 4; l++) {
                target[k++] = HvxcTables.AM_4K_SHAPE1[enc[AM_4K + 1] * 4 + l];
            }
            for (var l = 0; l < 4; l++) {
                target[k++] = HvxcTables.AM_4K_SHAPE2[enc[AM_4K + 2] * 4 + l];
            }
            for (var l = 0; l < 4; l++) {
                target[k++] = HvxcTables.AM_4K_SHAPE3[enc[AM_4K + 3] * 4 + l];
            }
            var last = decMode == RATE_3K ? 10 : 14;
            for (var i = last + 1; i <= send2; i++) {
                target[i] = 0.0f;
            }
            for (var i = 0; i <= send2; i++) {
                qam2[i] += target[i];
            }
        }
        for (var i = 0; i <= send2; i++) {
            am[i] = qam2[i];
        }
        for (var i = send2 + 1; i < SAMPLE / 2; i++) {
            am[i] = 0.0f;
        }
        am[0] = 0.0f;
        return pch;
    }

    /**
     * Resamples the fixed-dimension magnitudes to the harmonics of the pitch ({@code IpAmFIR_Deci_r}).
     */
    private void resample(float[] am1, int send1, float w01, float[] out, float w0, int send) {
        var re = resampled;
        var half = 4;
        var tmp = am1[send1];
        for (var i = 0; i < half; i++) {
            re[i] = 0.0f;
        }
        for (var i = 0; i <= send1; i++) {
            re[i + half] = am1[i];
        }
        for (var i = 0; i < half; i++) {
            re[i + send1 + 1 + half] = tmp;
        }
        var w0f = w01 / (float) 8;
        var ii = 0;
        for (var i = 0; i <= send1 && ii <= send; i++) {
            for (var p = 0; p < 8 && ii <= send; p++) {
                var ratio = (i * 8 + p + 1) * w0f - w0 * ii;
                if (ratio > 0) {
                    ratio /= w0f;
                    var rel0 = 0.0f;
                    var rel1 = 0.0f;
                    for (var j = 1; j < 9; j++) {
                        rel0 += RESAMPLING[j * 8 - p] * re[i + j];
                        rel1 += RESAMPLING[j * 8 - (p + 1)] * re[i + j];
                    }
                    out[ii] = rel0 * ratio + rel1 * (1.0f - ratio);
                    ii++;
                }
            }
        }
    }

    /**
     * Interpolates the parameters of the two frames around the synthesis point ({@code IPC_InterpolateParams}).
     *
     * @param rate the position between the frames, from 0 (the earlier) to 1
     */
    private void interpolate(float rate) {
        var modVuv = 0;
        float modPch;
        if (idVuv2[0] != 0 && idVuv2[1] != 0) {
            var tmp = (float) (idVuv2[0] * (1.0 - rate) + idVuv2[1] * rate);
            if (tmp > 2) {
                modVuv = 3;
            } else if (tmp > 1) {
                modVuv = 2;
            } else if (tmp > 0) {
                modVuv = 1;
            }
            if (pch2[1] == 0.0f) {
                modPch = pch2[0];
            } else if (0.57 < pch2[0] / pch2[1] && pch2[0] / pch2[1] < 1.75) {
                modPch = (float) (pch2[0] * (1.0 - rate) + pch2[1] * rate);
            } else {
                modPch = rate < 0.5 ? pch2[0] : pch2[1];
            }
            for (var i = 0; i < SAMPLE / 2; i++) {
                modAm[i] = (float) (am2[0][i] * (1.0 - rate) + am2[1][i] * rate);
            }
            for (var i = 0; i < ORDER; i++) {
                modLsp[i] = (float) (qLsp2[0][i] * (1.0 - rate) + qLsp2[1][i] * rate);
            }
            Arrays.fill(modRes, 0.0f);
        } else if (idVuv2[0] == 0 && idVuv2[1] == 0) {
            modPch = 148.0f;
            Arrays.fill(modAm, 0.0f);
            for (var i = 0; i < ORDER; i++) {
                modLsp[i] = (float) (qLsp2[0][i] * (1.0 - rate) + qLsp2[1][i] * rate);
            }
            var shift = (int) (FRAME * rate);
            for (var i = 0; i < FRAME - shift; i++) {
                modRes[i] = qRes2[0][i + shift];
            }
            for (var i = FRAME - shift; i < FRAME; i++) {
                modRes[i] = qRes2[1][i - FRAME + shift];
            }
            randomizeResidual(rate);
        } else if (idVuv2[0] != 0) {
            if (rate < 0.5) {
                modVuv = idVuv2[0];
                modPch = pch2[0];
                System.arraycopy(am2[0], 0, modAm, 0, SAMPLE / 2);
                System.arraycopy(qLsp2[0], 0, modLsp, 0, ORDER);
                Arrays.fill(modRes, 0.0f);
            } else {
                modPch = 148.0f;
                Arrays.fill(modAm, 0.0f);
                System.arraycopy(qLsp2[1], 0, modLsp, 0, ORDER);
                System.arraycopy(qRes2[1], 0, modRes, 0, FRAME);
                randomizeResidual(rate);
            }
        } else {
            if (rate < 0.5) {
                modPch = 148.0f;
                Arrays.fill(modAm, 0.0f);
                System.arraycopy(qLsp2[0], 0, modLsp, 0, ORDER);
                System.arraycopy(qRes2[0], 0, modRes, 0, FRAME);
                randomizeResidual(rate);
            } else {
                modVuv = idVuv2[1];
                modPch = pch2[1];
                System.arraycopy(am2[1], 0, modAm, 0, SAMPLE / 2);
                System.arraycopy(qLsp2[1], 0, modLsp, 0, ORDER);
                Arrays.fill(modRes, 0.0f);
            }
        }
        synthesisVuv[0] = modVuv;
        interpolatedPitch = modPch;
    }

    /**
     * The pitch lag of the interpolated parameters.
     */
    private float interpolatedPitch;

    /**
     * Replaces the excitation of an interpolated unvoiced frame between two frames by Gaussian noise of its energy.
     */
    private void randomizeResidual(float rate) {
        if (rate == 0.0f || rate == 1.0f) {
            return;
        }
        if ((testMode & TEST_NO_EXCITATION) != 0) {
            Arrays.fill(modRes, 0.0f);
            return;
        }
        var nrmOrg = 0.0f;
        for (var i = 0; i < FRAME; i++) {
            nrmOrg += modRes[i] * modRes[i];
        }
        nrmOrg = (float) StrictMath.sqrt(nrmOrg);
        var nrmRnd = 0.0f;
        for (var i = 0; i < FRAME; i++) {
            float x;
            float y;
            do {
                x = (float) (random.next() % HvxcRandom.MAX) / (float) HvxcRandom.MAX;
                y = (float) (random.next() % HvxcRandom.MAX) / (float) HvxcRandom.MAX;
                x = (float) (2.0 * x - 1.0);
                y = (float) (2.0 * y - 1.0);
            } while (x * x + y * y > 1.0 || x * x + y * y == 0.0f);
            var r = x * x + y * y;
            modRes[i] = (float) (0.25 * x * StrictMath.sqrt(-2.0 * StrictMath.log(r) / r));
            nrmRnd += modRes[i] * modRes[i];
        }
        nrmRnd = (float) StrictMath.sqrt(nrmRnd);
        for (var i = 0; i < FRAME; i++) {
            modRes[i] /= nrmRnd / nrmOrg;
        }
    }

    /**
     * Synthesizes one frame from the interpolated parameters ({@code IPC_SynthSC}).
     */
    private void synthesize(float[] samples, int offset) {
        var idVuv = synthesisVuv[0];
        if (synFrames == 0) {
            System.arraycopy(modLsp, 0, qLsps[0], 0, ORDER);
            System.arraycopy(modLsp, 0, qLsps[1], 0, ORDER);
            vuvs[0] = idVuv;
            vuvs[1] = idVuv;
            pchs[0] = interpolatedPitch;
            pchs[1] = interpolatedPitch;
            Arrays.fill(mixed, 0.0f);
        } else {
            System.arraycopy(qLsps[1], 0, qLsps[0], 0, ORDER);
            System.arraycopy(modLsp, 0, qLsps[1], 0, ORDER);
            vuvs[0] = vuvs[1];
            vuvs[1] = idVuv;
            pchs[0] = pchs[1];
            pchs[1] = interpolatedPitch;
            harmonicVuv[0] = variable && vuvs[0] == 1 ? 0 : vuvs[0];
            harmonicVuv[1] = variable && vuvs[1] == 1 ? 0 : vuvs[1];
            voiced.synthesize(modAm, pchs, harmonicVuv, qLsps, decMode == RATE_2K, voicedOut);
            unvoiced.synthesize(modRes, vuvs, qLsps, unvoicedOut);
            for (var i = 0; i < FRAME; i++) {
                mixed[i] = unvoicedOut[i] + voicedOut[i];
            }
            if ((testMode & TEST_NO_POSTFILTER) == 0) {
                HvxcFilters.iir2(mixed, hpfState, HvxcFilters.HIGH_PASS);
                HvxcFilters.iir4(mixed, hpf4State, HvxcFilters.HIGH_PASS_4);
                HvxcFilters.iir2(mixed, lpfState, HvxcFilters.LOW_PASS);
            }
        }
        for (var i = 0; i < FRAME; i++) {
            var value = mixed[i];
            if (value > 32767.0f) {
                value = 32767.0f;
            }
            if (value < -32767.0f) {
                value = -32767.0f;
            }
            samples[offset + i] = (float) (int) value;
        }
        synFrames++;
    }

    /**
     * Records the class CRCs of a frame ({@code IPC_InterpreteCrcResult}).
     */
    private void interpretCrc(boolean[] classErrors) {
        var esc = 0;
        crcEp[0] = crcEp[1];
        crcEp[1] = error(classErrors, esc++);
        if (crcEp[0] != 0) {
            if (crcErrors < 10) {
                crcErrors++;
            }
        } else {
            crcErrors = 0;
        }
        if (rateMode == RATE_4K) {
            crc4k = error(classErrors, esc++);
        }
        crcShape = error(classErrors, esc) & error(classErrors, esc + 1);
    }

    private static int error(boolean[] classErrors, int index) {
        return classErrors != null && index < classErrors.length && classErrors[index] ? 1 : 0;
    }

    /**
     * Steps the bad frame state machine and sets the concealment attenuation ({@code IPC_BadFrameStatus}).
     */
    private void badFrameStatus() {
        epStatus[0] = epStatus[1];
        if (crcEp[1] != 0) {
            if (epStatus[1] <= 5) {
                epStatus[1]++;
            } else if (epStatus[1] == 7) {
                epStatus[1] = 1;
            }
        } else {
            epStatus[1] = epStatus[1] == 0 || epStatus[1] == 7 ? 0 : 7;
        }
        mute = switch (epStatus[1]) {
            case 0 -> 1.0f;
            case 1 -> 0.8f;
            case 2 -> 0.7f;
            case 3 -> 0.5f;
            case 4 -> 0.25f;
            case 5 -> 0.125f;
            case 6 -> 0.0f;
            default -> Math.min((mute + 1.0f) / 2.0f, 0.8f);
        };
    }

    /**
     * Puts the bits of an error resilient frame back into the order of the fixed rate syntax
     * ({@code IPC_BitReordering}).
     */
    private void reorder(byte[] in, byte[] out) {
        int num;
        int[] order;
        if (!variable) {
            num = scalable || decMode != RATE_2K ? 80 : 40;
            var voicedFrame = ((in[0] & 0xFF) >>> 6) != 0;
            if (scalable) {
                order = voicedFrame ? SC_V2K : SC_UV2K;
            } else if (decMode == RATE_2K) {
                order = voicedFrame ? V2K : UV2K;
            } else if (decMode == RATE_4K) {
                order = voicedFrame ? V4K : UV4K;
            } else {
                order = voicedFrame ? V3K : UV3K;
            }
        } else {
            var vuv = (in[0] & 0xC0) >>> 6;
            if (decMode == RATE_2K && !scalable) {
                if (vuv == 0) {
                    order = VAR_UV2K;
                    num = 28;
                } else if (vuv == 1) {
                    order = VAR_UV2K;
                    num = 2;
                } else {
                    order = VAR_V2K;
                    num = 40;
                }
            } else if (vuv == 0) {
                order = VAR_UV4K;
                num = 40;
            } else if (vuv == 1) {
                order = VAR_BGN4K;
                num = 25;
            } else {
                order = scalable ? VAR_VSC2K : VAR_V4K;
                num = 80;
            }
        }
        System.arraycopy(in, 0, out, 0, 10);
        for (var i = 0; i < num; i++) {
            if ((i & 7) == 0) {
                out[i >>> 3] = 0;
            }
            var k = order[i];
            var bit = (in[k >>> 3] >>> (7 - (k & 7))) & 1;
            out[i >>> 3] |= (byte) (bit << (7 - (i & 7)));
        }
    }
}
