/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aecm/aecm_core.cc, aecm_core_c.cc). Copyright (c)
   2011, The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aecm;

import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;

import java.util.Arrays;

/**
 * Cancels the echo of one 16 kHz channel in the frequency domain with the fixed-point algorithm
 * of WebRTC's mobile echo canceller.
 *
 * <p>The core consumes 10 ms frames of 80 samples of the lower band split into 64-sample blocks
 * that overlap by half. For every block it transforms the delay-compensated far-end signal and
 * the near-end signal with a {@value AecmFft#SIZE}-point FFT, estimates the delay between them
 * ({@link DelayEstimator}), adapts a magnitude-domain echo channel with a normalized LMS whose
 * step follows the far-end level, keeps a stored copy of the channel that wins on a running
 * error comparison, derives a Wiener-style suppression gain per bin from the estimated echo and
 * the smoothed near-end magnitude, optionally adds comfort noise, and resynthesizes the block
 * with a square-root Hanning window and overlap-add.
 *
 * @implNote This implementation ports {@code modules/audio_processing/aecm/aecm_core.cc} and
 * the generic C functions of {@code aecm_core_c.cc} of webrtc-audio-processing 2.1 (the
 * function-pointer kernels resolve to their C versions; the NEON and MIPS kernels and the
 * {@code AECM_WITH_ABS_APPROX} magnitude approximation are not built by the reference), with
 * the dynamic Q-domain ({@code AECM_DYNAMIC_Q}) enabled as in {@code aecm_defines.h}. It
 * supports only 16 kHz, where the reference processes two 80-sample frames per 10 ms; every
 * integer operation keeps the widths, signedness and shift behavior of the reference.
 */
final class AecmCore {
    /**
     * The number of samples of one frame ({@code FRAME_LEN}).
     */
    static final int FRAME_LEN = 80;

    /**
     * The number of samples of one block ({@code PART_LEN}).
     */
    static final int PART_LEN = 64;

    /**
     * The number of unique bins of the block spectrum ({@code PART_LEN1}).
     */
    static final int PART_LEN1 = PART_LEN + 1;

    /**
     * The number of samples of the transform input ({@code PART_LEN2}).
     */
    static final int PART_LEN2 = PART_LEN << 1;

    /**
     * The number of samples of the delay-compensation ring of the far-end signal
     * ({@code FAR_BUF_LEN}).
     */
    static final int FAR_BUF_LEN = PART_LEN << 2;

    /**
     * The number of far-end spectra the delay estimator searches ({@code MAX_DELAY}).
     */
    private static final int MAX_DELAY = 100;

    /**
     * The number of blocks of the first and second startup phases ({@code CONV_LEN}).
     */
    private static final int CONV_LEN = 512;

    /**
     * The number of entries of the energy histories ({@code MAX_BUF_LEN}).
     */
    private static final int MAX_BUF_LEN = 64;

    /**
     * The far-end log energy, in Q8, below which the far end is considered silent
     * ({@code FAR_ENERGY_MIN}).
     */
    private static final int FAR_ENERGY_MIN = 1025;

    /**
     * The spread between the far-end energy extremes, in Q8, that marks dynamic far-end speech
     * ({@code FAR_ENERGY_DIFF}).
     */
    private static final int FAR_ENERGY_DIFF = 929;

    /**
     * The estimation error tolerance in Q8 ({@code ENERGY_DEV_TOL}).
     */
    private static final int ENERGY_DEV_TOL = 400;

    /**
     * The base width, in Q8, of the far-end voice activity region ({@code FAR_ENERGY_VAD_REGION}).
     */
    private static final int FAR_ENERGY_VAD_REGION = 230;

    /**
     * The smallest NLMS step, as a right shift ({@code MU_MIN}).
     */
    private static final int MU_MIN = 10;

    /**
     * The largest NLMS step, as a right shift ({@code MU_MAX}).
     */
    private static final int MU_MAX = 1;

    /**
     * The range of NLMS steps ({@code MU_DIFF}).
     */
    private static final int MU_DIFF = 9;

    /**
     * The number of blocks the channel errors are averaged over ({@code MIN_MSE_COUNT}).
     */
    private static final int MIN_MSE_COUNT = 20;

    /**
     * The error ratio, 0.8 in Q5, that decides between the stored and the adapted channel
     * ({@code MIN_MSE_DIFF}).
     */
    private static final int MIN_MSE_DIFF = 29;

    /**
     * The resolution of the error comparison ({@code MSE_RESOLUTION}).
     */
    private static final int MSE_RESOLUTION = 5;

    /**
     * The Q-domain of the 16-bit channel ({@code RESOLUTION_CHANNEL16}).
     */
    private static final int RESOLUTION_CHANNEL16 = 12;

    /**
     * The Q-domain of the 32-bit channel ({@code RESOLUTION_CHANNEL32}).
     */
    private static final int RESOLUTION_CHANNEL32 = 28;

    /**
     * The least far-end bin magnitude that updates the channel ({@code CHANNEL_VAD}).
     */
    private static final int CHANNEL_VAD = 16;

    /**
     * The Q-domain of the suppression gain ({@code RESOLUTION_SUPGAIN}).
     */
    private static final int RESOLUTION_SUPGAIN = 8;

    /**
     * The default suppression gain, 1 in Q8 ({@code SUPGAIN_DEFAULT}).
     */
    static final int SUPGAIN_DEFAULT = 1 << RESOLUTION_SUPGAIN;

    /**
     * The largest suppression gain, 8 in Q8 ({@code SUPGAIN_ERROR_PARAM_A}).
     */
    static final int SUPGAIN_ERROR_PARAM_A = 3072;

    /**
     * The suppression gain before it falls towards the default ({@code SUPGAIN_ERROR_PARAM_B}).
     */
    static final int SUPGAIN_ERROR_PARAM_B = 1536;

    /**
     * The suppression gain at the error tolerance ({@code SUPGAIN_ERROR_PARAM_D}).
     */
    static final int SUPGAIN_ERROR_PARAM_D = SUPGAIN_DEFAULT;

    /**
     * The estimation error, in Q8, below which the suppression gain decreases from its maximum
     * ({@code SUPGAIN_EPC_DT}).
     */
    private static final int SUPGAIN_EPC_DT = 200;

    /**
     * One in Q14 ({@code ONE_Q14}).
     */
    private static final int ONE_Q14 = 1 << 14;

    /**
     * The suppression filter value, 0.2 in Q14, below which a bin is muted ({@code NLP_COMP_LOW}).
     */
    private static final int NLP_COMP_LOW = 3277;

    /**
     * The suppression filter value above which a bin passes unchanged ({@code NLP_COMP_HIGH}).
     */
    private static final int NLP_COMP_HIGH = ONE_Q14;

    /**
     * The Q-domain of the comfort noise estimate ({@code kNoiseEstQDomain}).
     */
    private static final int NOISE_EST_Q_DOMAIN = 15;

    /**
     * The number of blocks between the incremental steps of small noise estimates
     * ({@code kNoiseEstIncCount}).
     */
    private static final int NOISE_EST_INC_COUNT = 5;

    /**
     * The first bin of the band whose mean caps the upper bins at 16 kHz ({@code kMinPrefBand}).
     */
    private static final int MIN_PREF_BAND = 4;

    /**
     * The last bin of the band whose mean caps the upper bins at 16 kHz ({@code kMaxPrefBand}).
     */
    private static final int MAX_PREF_BAND = 24;

    /**
     * The initial echo channel at 16 kHz ({@code kChannelStored16kHz}).
     */
    private static final short[] CHANNEL_STORED_16KHZ = {
            2040, 1590, 1405, 1385, 1451, 1562, 1726, 1882, 1953, 2010, 2040,
            2027, 2014, 1980, 1869, 1732, 1635, 1572, 1517, 1444, 1367, 1294,
            1245, 1233, 1260, 1303, 1373, 1441, 1499, 1549, 1582, 1621, 1676,
            1741, 1802, 1861, 1921, 1983, 2040, 2102, 2170, 2265, 2375, 2515,
            2651, 2781, 2922, 3075, 3253, 3471, 3738, 3976, 4151, 4258, 4308,
            4288, 4270, 4253, 4237, 4179, 4086, 3947, 3757, 3484, 3153};

    /**
     * The square root of the Hanning window in Q14 ({@code WebRtcAecm_kSqrtHanning}).
     */
    private static final short[] SQRT_HANNING = {
            0, 399, 798, 1196, 1594, 1990, 2386, 2780, 3172, 3562, 3951,
            4337, 4720, 5101, 5478, 5853, 6224, 6591, 6954, 7313, 7668, 8019,
            8364, 8705, 9040, 9370, 9695, 10013, 10326, 10633, 10933, 11227, 11514,
            11795, 12068, 12335, 12594, 12845, 13089, 13325, 13553, 13773, 13985, 14189,
            14384, 14571, 14749, 14918, 15079, 15231, 15373, 15506, 15631, 15746, 15851,
            15947, 16034, 16111, 16179, 16237, 16286, 16325, 16354, 16373, 16384};

    /**
     * The number of 80-sample frames per 10 ms ({@code mult}): 2 at 16 kHz.
     */
    final int mult = 2;

    /**
     * The write position in {@link #farBuf}.
     */
    private int farBufWritePos;

    /**
     * The read position in {@link #farBuf}.
     */
    private int farBufReadPos;

    /**
     * The delay, in samples, the far-end signal is compensated by.
     */
    int knownDelay;

    /**
     * The compensation delay of the previous frame.
     */
    private int lastKnownDelay;

    /**
     * Whether the first far-end activity has not yet validated the initial channel.
     */
    private boolean firstVad;

    /**
     * The delay-compensated far-end samples waiting to form blocks.
     */
    private final AecmRingBuffer farFrameBuf = new AecmRingBuffer(FRAME_LEN + PART_LEN);

    /**
     * The noisy near-end samples waiting to form blocks.
     */
    private final AecmRingBuffer nearNoisyFrameBuf = new AecmRingBuffer(FRAME_LEN + PART_LEN);

    /**
     * The clean near-end samples waiting to form blocks.
     */
    private final AecmRingBuffer nearCleanFrameBuf = new AecmRingBuffer(FRAME_LEN + PART_LEN);

    /**
     * The processed samples waiting to form output frames.
     */
    private final AecmRingBuffer outFrameBuf = new AecmRingBuffer(FRAME_LEN + PART_LEN);

    /**
     * The delay-compensation ring of the far-end signal.
     */
    private final short[] farBuf = new short[FAR_BUF_LEN];

    /**
     * The state of the comfort noise generator, an unsigned 31-bit value.
     */
    private int seed;

    /**
     * The delay estimator between the far-end and the near-end spectra.
     */
    private final DelayEstimator delayEstimator = new DelayEstimator(PART_LEN1, MAX_DELAY);

    /**
     * The far-end magnitude spectra of the last {@value #MAX_DELAY} blocks.
     */
    private final char[] farHistory = new char[PART_LEN1 * MAX_DELAY];

    /**
     * The index of the newest spectrum in {@link #farHistory}.
     */
    private int farHistoryPos;

    /**
     * The Q-domain of each spectrum in {@link #farHistory}.
     */
    private final int[] farQDomains = new int[MAX_DELAY];

    /**
     * Whether the non-linear post-processing is applied.
     */
    private boolean nlpFlag;

    /**
     * The fixed delay in blocks overriding the estimate, or {@code -1} to use the estimate.
     */
    private int fixedDelay;

    /**
     * The number of blocks processed, an unsigned counter.
     */
    private int totCount;

    /**
     * The Q-domain of the clean near-end spectrum.
     */
    private short dfaCleanQDomain;

    /**
     * The Q-domain of the clean near-end spectrum of the previous block.
     */
    private short dfaCleanQDomainOld;

    /**
     * The Q-domain of the noisy near-end spectrum.
     */
    private short dfaNoisyQDomain;

    /**
     * The Q-domain of the noisy near-end spectrum of the previous block.
     */
    private short dfaNoisyQDomainOld;

    /**
     * The log energies of the near-end blocks in Q8, newest first.
     */
    private final short[] nearLogEnergy = new short[MAX_BUF_LEN];

    /**
     * The log energy of the aligned far-end block in Q8.
     */
    private short farLogEnergy;

    /**
     * The log energies of the echo estimated through the adapted channel in Q8, newest first.
     */
    private final short[] echoAdaptLogEnergy = new short[MAX_BUF_LEN];

    /**
     * The log energies of the echo estimated through the stored channel in Q8, newest first.
     */
    private final short[] echoStoredLogEnergy = new short[MAX_BUF_LEN];

    /**
     * The stored echo channel in Q12.
     */
    final short[] channelStored = new short[PART_LEN1];

    /**
     * The adapted echo channel in Q12.
     */
    private final short[] channelAdapt16 = new short[PART_LEN1];

    /**
     * The adapted echo channel in Q28.
     */
    private final int[] channelAdapt32 = new int[PART_LEN1];

    /**
     * The far-end samples of the previous and the current block.
     */
    private final short[] xBuf = new short[PART_LEN2];

    /**
     * The clean near-end samples of the previous and the current block.
     */
    private final short[] dBufClean = new short[PART_LEN2];

    /**
     * The noisy near-end samples of the previous and the current block.
     */
    private final short[] dBufNoisy = new short[PART_LEN2];

    /**
     * The second half of the previous synthesized block, awaiting overlap-add.
     */
    private final short[] outBuf = new short[PART_LEN];

    /**
     * The smoothed echo estimate per bin.
     */
    private final int[] echoFilt = new int[PART_LEN1];

    /**
     * The smoothed clean near-end magnitude per bin.
     */
    private final short[] nearFilt = new short[PART_LEN1];

    /**
     * The comfort noise level estimate per bin in Q15.
     */
    private final int[] noiseEst = new int[PART_LEN1];

    /**
     * The number of consecutive blocks each noise estimate stayed below the input.
     */
    private final int[] noiseEstTooLowCtr = new int[PART_LEN1];

    /**
     * The number of consecutive blocks each noise estimate stayed above the input.
     */
    private final int[] noiseEstTooHighCtr = new int[PART_LEN1];

    /**
     * The number of blocks the noise estimate has tracked quickly, up to 100.
     */
    private short noiseEstCtr;

    /**
     * Whether comfort noise is added.
     */
    boolean cngMode;

    /**
     * The averaged error of the adapted channel at the previous comparison.
     */
    private int mseAdaptOld;

    /**
     * The averaged error of the stored channel at the previous comparison.
     */
    private int mseStoredOld;

    /**
     * The averaged error below which the adapted channel may be stored.
     */
    private int mseThreshold;

    /**
     * The tracked minimum of the far-end log energy in Q8.
     */
    private short farEnergyMin;

    /**
     * The tracked maximum of the far-end log energy in Q8.
     */
    private short farEnergyMax;

    /**
     * The difference between {@link #farEnergyMax} and {@link #farEnergyMin}.
     */
    private short farEnergyMaxMin;

    /**
     * The far-end log energy above which the far end is active, in Q8.
     */
    private short farEnergyVad;

    /**
     * The far-end log energy above which the channel errors are compared, in Q8.
     */
    private short farEnergyMse;

    /**
     * Whether the far end is active in the current block.
     */
    private boolean currentVadValue;

    /**
     * The number of blocks since the far-end activity threshold last moved.
     */
    private short vadUpdateCount;

    /**
     * The startup phase: 0 for the first {@value #CONV_LEN} blocks, 1 for the next
     * {@value #CONV_LEN}, 2 afterwards.
     */
    private short startupState;

    /**
     * The number of consecutive blocks with enough far-end energy for the channel comparison.
     */
    private short mseChannelCount;

    /**
     * The smoothed suppression gain in Q8.
     */
    short supGain;

    /**
     * The suppression gain target of the previous block in Q8.
     */
    short supGainOld;

    /**
     * The largest suppression gain of the configured echo mode in Q8.
     */
    short supGainErrParamA;

    /**
     * The suppression gain at the error tolerance of the configured echo mode in Q8.
     */
    short supGainErrParamD;

    /**
     * The difference between the largest and the intermediate suppression gain.
     */
    short supGainErrParamDiffAB;

    /**
     * The difference between the intermediate and the tolerance suppression gain.
     */
    short supGainErrParamDiffBD;

    /**
     * The fixed-point FFT of the blocks.
     */
    private final AecmFft fft = new AecmFft();

    /**
     * Scratch for the delay-compensated far-end frame.
     */
    private final short[] farFrame = new short[FRAME_LEN];

    /**
     * Scratch for the far-end block.
     */
    private final short[] farBlock = new short[PART_LEN];

    /**
     * Scratch for the noisy near-end block.
     */
    private final short[] nearNoisyBlock = new short[PART_LEN];

    /**
     * Scratch for the clean near-end block.
     */
    private final short[] nearCleanBlock = new short[PART_LEN];

    /**
     * Scratch for the synthesized block.
     */
    private final short[] outBlock = new short[PART_LEN];

    /**
     * Scratch for the far-end magnitude spectrum.
     */
    private final char[] xfa = new char[PART_LEN1];

    /**
     * Scratch for the noisy near-end magnitude spectrum.
     */
    private final char[] dfaNoisy = new char[PART_LEN1];

    /**
     * Scratch for the clean near-end magnitude spectrum.
     */
    private final char[] dfaClean = new char[PART_LEN1];

    /**
     * Scratch for the windowed time signal and the inverse transform input.
     */
    private final short[] fftBuffer = new short[PART_LEN2 + 2];

    /**
     * Scratch for the echo estimate per bin.
     */
    private final int[] echoEst32 = new int[PART_LEN1];

    /**
     * Scratch for the near-end spectrum, interleaved real and imaginary parts.
     */
    private final short[] dfw = new short[PART_LEN2 + 2];

    /**
     * Scratch for the filtered spectrum, interleaved real and imaginary parts, and then the
     * inverse transform output.
     */
    private final short[] efw = new short[PART_LEN2 + 2];

    /**
     * Scratch for the suppression filter per bin in Q14.
     */
    private final short[] hnl = new short[PART_LEN1];

    /**
     * Scratch for the uniform random numbers of the comfort noise.
     */
    private final short[] randW16 = new short[PART_LEN];

    /**
     * Scratch for the real part of the comfort noise.
     */
    private final short[] uReal = new short[PART_LEN1];

    /**
     * Scratch for the imaginary part of the comfort noise.
     */
    private final short[] uImag = new short[PART_LEN1];

    /**
     * Scratch for the comfort noise level per bin.
     */
    private final short[] noiseRShift16 = new short[PART_LEN1];

    /**
     * Scratch for the sum of the magnitudes of a transform.
     */
    private int magnitudeSum;

    /**
     * Creates an uninitialized core ({@code WebRtcAecm_CreateCore}); {@link #init()} must run
     * before the first frame.
     */
    AecmCore() {
    }

    /**
     * Initializes the core for 16 kHz ({@code WebRtcAecm_InitCore}).
     */
    void init() {
        farBufWritePos = 0;
        farBufReadPos = 0;
        knownDelay = 0;
        lastKnownDelay = 0;
        farFrameBuf.init();
        nearNoisyFrameBuf.init();
        nearCleanFrameBuf.init();
        outFrameBuf.init();
        Arrays.fill(xBuf, (short) 0);
        Arrays.fill(dBufClean, (short) 0);
        Arrays.fill(dBufNoisy, (short) 0);
        Arrays.fill(outBuf, (short) 0);
        seed = 666;
        totCount = 0;
        delayEstimator.init();
        Arrays.fill(farHistory, (char) 0);
        Arrays.fill(farQDomains, 0);
        farHistoryPos = MAX_DELAY;
        nlpFlag = true;
        fixedDelay = -1;
        dfaCleanQDomain = 0;
        dfaCleanQDomainOld = 0;
        dfaNoisyQDomain = 0;
        dfaNoisyQDomainOld = 0;
        Arrays.fill(nearLogEnergy, (short) 0);
        farLogEnergy = 0;
        Arrays.fill(echoAdaptLogEnergy, (short) 0);
        Arrays.fill(echoStoredLogEnergy, (short) 0);
        initEchoPath(CHANNEL_STORED_16KHZ);
        Arrays.fill(echoFilt, 0);
        Arrays.fill(nearFilt, (short) 0);
        noiseEstCtr = 0;
        cngMode = true;
        Arrays.fill(noiseEstTooLowCtr, 0);
        Arrays.fill(noiseEstTooHighCtr, 0);
        var tmp32 = PART_LEN1 * PART_LEN1;
        var tmp16 = (short) PART_LEN1;
        var i = 0;
        for (; i < (PART_LEN1 >> 1) - 1; i++) {
            noiseEst[i] = tmp32 << 8;
            tmp16--;
            tmp32 -= (tmp16 << 1) + 1;
        }
        for (; i < PART_LEN1; i++) {
            noiseEst[i] = tmp32 << 8;
        }
        farEnergyMin = Short.MAX_VALUE;
        farEnergyMax = Short.MIN_VALUE;
        farEnergyMaxMin = 0;
        farEnergyVad = FAR_ENERGY_MIN;
        farEnergyMse = 0;
        currentVadValue = false;
        vadUpdateCount = 0;
        firstVad = true;
        startupState = 0;
        supGain = SUPGAIN_DEFAULT;
        supGainOld = SUPGAIN_DEFAULT;
        supGainErrParamA = SUPGAIN_ERROR_PARAM_A;
        supGainErrParamD = SUPGAIN_ERROR_PARAM_D;
        supGainErrParamDiffAB = SUPGAIN_ERROR_PARAM_A - SUPGAIN_ERROR_PARAM_B;
        supGainErrParamDiffBD = SUPGAIN_ERROR_PARAM_B - SUPGAIN_ERROR_PARAM_D;
    }

    /**
     * Resets the stored and adapted channels to an echo path ({@code WebRtcAecm_InitEchoPathCore}).
     *
     * @param echoPath the echo channel in Q12, {@value #PART_LEN1} bins
     */
    private void initEchoPath(short[] echoPath) {
        System.arraycopy(echoPath, 0, channelStored, 0, PART_LEN1);
        System.arraycopy(echoPath, 0, channelAdapt16, 0, PART_LEN1);
        for (var i = 0; i < PART_LEN1; i++) {
            channelAdapt32[i] = channelAdapt16[i] << 16;
        }
        mseAdaptOld = 1000;
        mseStoredOld = 1000;
        mseThreshold = Integer.MAX_VALUE;
        mseChannelCount = 0;
    }

    /**
     * Cancels the echo of one 80-sample frame ({@code WebRtcAecm_ProcessFrame}).
     *
     * <p>The far-end frame is delay-compensated by {@link #knownDelay}, the frames are cut into
     * blocks, every complete block is processed, and one 80-sample frame of output is produced,
     * padded with earlier ring contents on the very first frame.
     *
     * @param farend the far-end frame
     * @param farendOffset the index of the first far-end sample
     * @param noisy the noisy near-end signal
     * @param noisyOffset the index of the first noisy near-end sample
     * @param clean the clean near-end signal, or {@code null}
     * @param cleanOffset the index of the first clean near-end sample
     * @param out receives the output frame; may alias {@code noisy} or {@code clean}
     * @param outOffset the index of the first output sample
     * @return {@code 0}, or {@code -1} on an internal error
     */
    int processFrame(short[] farend, int farendOffset, short[] noisy, int noisyOffset, short[] clean,
                     int cleanOffset, short[] out, int outOffset) {
        bufferFarFrame(farend, farendOffset, FRAME_LEN);
        fetchFarFrame(farFrame, FRAME_LEN, knownDelay);
        farFrameBuf.write(farFrame, 0, FRAME_LEN);
        nearNoisyFrameBuf.write(noisy, noisyOffset, FRAME_LEN);
        if (clean != null) {
            nearCleanFrameBuf.write(clean, cleanOffset, FRAME_LEN);
        }
        while (farFrameBuf.availableRead() >= PART_LEN) {
            farFrameBuf.read(farBlock, 0, PART_LEN);
            nearNoisyFrameBuf.read(nearNoisyBlock, 0, PART_LEN);
            if (clean != null) {
                nearCleanFrameBuf.read(nearCleanBlock, 0, PART_LEN);
                if (processBlock(farBlock, nearNoisyBlock, nearCleanBlock, outBlock) == -1) {
                    return -1;
                }
            } else if (processBlock(farBlock, nearNoisyBlock, null, outBlock) == -1) {
                return -1;
            }
            outFrameBuf.write(outBlock, 0, PART_LEN);
        }
        var size = outFrameBuf.availableRead();
        if (size < FRAME_LEN) {
            outFrameBuf.moveReadPointer(size - FRAME_LEN);
        }
        outFrameBuf.read(out, outOffset, FRAME_LEN);
        return 0;
    }

    /**
     * Writes a far-end frame into the delay-compensation ring ({@code WebRtcAecm_BufferFarFrame}).
     *
     * @param farend the far-end samples
     * @param offset the index of the first sample
     * @param farLen the number of samples
     */
    private void bufferFarFrame(short[] farend, int offset, int farLen) {
        var writeLen = farLen;
        var writePos = 0;
        while (farBufWritePos + writeLen > FAR_BUF_LEN) {
            writeLen = FAR_BUF_LEN - farBufWritePos;
            System.arraycopy(farend, offset + writePos, farBuf, farBufWritePos, writeLen);
            farBufWritePos = 0;
            writePos = writeLen;
            writeLen = farLen - writeLen;
        }
        System.arraycopy(farend, offset + writePos, farBuf, farBufWritePos, writeLen);
        farBufWritePos += writeLen;
    }

    /**
     * Reads a far-end frame from the delay-compensation ring, moving the read position by the
     * change of the compensation delay ({@code WebRtcAecm_FetchFarFrame}).
     *
     * @param farend receives the samples
     * @param farLen the number of samples
     * @param knownDelay the compensation delay in samples
     */
    private void fetchFarFrame(short[] farend, int farLen, int knownDelay) {
        var readLen = farLen;
        var readPos = 0;
        var delayChange = knownDelay - lastKnownDelay;
        farBufReadPos -= delayChange;
        while (farBufReadPos < 0) {
            farBufReadPos += FAR_BUF_LEN;
        }
        while (farBufReadPos > FAR_BUF_LEN - 1) {
            farBufReadPos -= FAR_BUF_LEN;
        }
        lastKnownDelay = knownDelay;
        while (farBufReadPos + readLen > FAR_BUF_LEN) {
            readLen = FAR_BUF_LEN - farBufReadPos;
            System.arraycopy(farBuf, farBufReadPos, farend, readPos, readLen);
            farBufReadPos = 0;
            readPos = readLen;
            readLen = farLen - readLen;
        }
        System.arraycopy(farBuf, farBufReadPos, farend, readPos, readLen);
        farBufReadPos += readLen;
    }

    /**
     * Cancels the echo of one 64-sample block ({@code WebRtcAecm_ProcessBlock}).
     *
     * @param farend the far-end block
     * @param nearendNoisy the noisy near-end block
     * @param nearendClean the clean near-end block, or {@code null}
     * @param output receives the output block
     * @return {@code 0}, or {@code -1} on an internal error
     */
    private int processBlock(short[] farend, short[] nearendNoisy, short[] nearendClean, short[] output) {
        if (startupState < 2) {
            startupState = (short) ((Integer.compareUnsigned(totCount, CONV_LEN) >= 0 ? 1 : 0)
                    + (Integer.compareUnsigned(totCount, CONV_LEN << 1) >= 0 ? 1 : 0));
        }
        System.arraycopy(farend, 0, xBuf, PART_LEN, PART_LEN);
        System.arraycopy(nearendNoisy, 0, dBufNoisy, PART_LEN, PART_LEN);
        if (nearendClean != null) {
            System.arraycopy(nearendClean, 0, dBufClean, PART_LEN, PART_LEN);
        }

        var farQ = timeToFrequencyDomain(xBuf, dfw, xfa);
        var zerosDBufNoisy = timeToFrequencyDomain(dBufNoisy, dfw, dfaNoisy);
        var dfaNoisySum = magnitudeSum;
        dfaNoisyQDomainOld = dfaNoisyQDomain;
        dfaNoisyQDomain = (short) zerosDBufNoisy;
        char[] ptrDfaClean;
        if (nearendClean == null) {
            ptrDfaClean = dfaNoisy;
            dfaCleanQDomainOld = dfaNoisyQDomainOld;
            dfaCleanQDomain = dfaNoisyQDomain;
        } else {
            ptrDfaClean = dfaClean;
            var zerosDBufClean = timeToFrequencyDomain(dBufClean, dfw, dfaClean);
            dfaCleanQDomainOld = dfaCleanQDomain;
            dfaCleanQDomain = (short) zerosDBufClean;
        }

        updateFarHistory(xfa, farQ);
        if (delayEstimator.addFarSpectrum(xfa, farQ) == -1) {
            return -1;
        }
        var delay = delayEstimator.process(dfaNoisy, zerosDBufNoisy);
        if (delay == -1) {
            return -1;
        } else if (delay == -2) {
            delay = 0;
        }
        if (fixedDelay >= 0) {
            delay = fixedDelay;
        }

        var bufferPosition = farHistoryPos - delay;
        if (bufferPosition < 0) {
            bufferPosition += MAX_DELAY;
        }
        var alignedQ = farQDomains[bufferPosition];
        var farSpectrumOffset = bufferPosition * PART_LEN1;
        var zerosXBuf = (short) alignedQ;

        calcEnergies(farSpectrumOffset, zerosXBuf, dfaNoisySum, echoEst32);
        var mu = calcStepSize();
        totCount++;
        updateChannel(farSpectrumOffset, zerosXBuf, dfaNoisy, mu, echoEst32);
        var supGain = calcSuppressionGain();

        short numPosCoef = 0;
        for (var i = 0; i < PART_LEN1; i++) {
            var tmp32no1 = echoEst32[i] - echoFilt[i];
            echoFilt[i] += (int) (((long) tmp32no1 * 50) >> 8);

            var zeros32 = (short) (FixedPointMath.normW32(echoFilt[i]) + 1);
            var zeros16 = (short) (FixedPointMath.normW16(supGain) + 1);
            int echoEst32Gained;
            short resolutionDiff;
            if (zeros32 + zeros16 > 16) {
                echoEst32Gained = echoFilt[i] * (supGain & 0xFFFF);
                resolutionDiff = (short) (14 - RESOLUTION_CHANNEL16 - RESOLUTION_SUPGAIN);
                resolutionDiff += (short) (dfaCleanQDomain - zerosXBuf);
            } else {
                var tmp16no1 = (short) (17 - zeros32 - zeros16);
                resolutionDiff = (short) (14 + tmp16no1 - RESOLUTION_CHANNEL16 - RESOLUTION_SUPGAIN);
                resolutionDiff += (short) (dfaCleanQDomain - zerosXBuf);
                if (zeros32 > tmp16no1) {
                    echoEst32Gained = echoFilt[i] * ((supGain >> tmp16no1) & 0xFFFF);
                } else {
                    echoEst32Gained = (echoFilt[i] >> tmp16no1) * supGain;
                }
            }

            zeros16 = (short) FixedPointMath.normW16(nearFilt[i]);
            var dfaCleanQDomainDiff = (short) (dfaCleanQDomain - dfaCleanQDomainOld);
            short tmp16no1;
            short tmp16no2;
            short qDomainDiff;
            if (zeros16 < dfaCleanQDomainDiff && nearFilt[i] != 0) {
                tmp16no1 = (short) (nearFilt[i] * (1 << zeros16));
                qDomainDiff = (short) (zeros16 - dfaCleanQDomainDiff);
                tmp16no2 = (short) (ptrDfaClean[i] >> -qDomainDiff);
            } else {
                tmp16no1 = dfaCleanQDomainDiff < 0
                        ? (short) (nearFilt[i] >> -dfaCleanQDomainDiff)
                        : (short) (nearFilt[i] * (1 << dfaCleanQDomainDiff));
                qDomainDiff = 0;
                tmp16no2 = (short) ptrDfaClean[i];
            }
            tmp32no1 = tmp16no2 - tmp16no1;
            tmp16no2 = (short) (tmp32no1 >> 4);
            tmp16no2 += tmp16no1;
            zeros16 = (short) FixedPointMath.normW16(tmp16no2);
            if ((tmp16no2 & (-qDomainDiff > zeros16 ? 1 : 0)) != 0) {
                nearFilt[i] = Short.MAX_VALUE;
            } else {
                nearFilt[i] = qDomainDiff < 0
                        ? (short) (tmp16no2 * (1 << -qDomainDiff))
                        : (short) (tmp16no2 >> qDomainDiff);
            }

            if (echoEst32Gained == 0) {
                hnl[i] = ONE_Q14;
            } else if (nearFilt[i] == 0) {
                hnl[i] = 0;
            } else {
                echoEst32Gained += nearFilt[i] >> 1;
                var tmpU32 = FixedPointMath.divU32U16(echoEst32Gained, nearFilt[i]);
                tmp32no1 = resolutionDiff >= 0 ? tmpU32 * (1 << resolutionDiff) : tmpU32 >>> -resolutionDiff;
                if (tmp32no1 > ONE_Q14) {
                    hnl[i] = 0;
                } else if (tmp32no1 < 0) {
                    hnl[i] = ONE_Q14;
                } else {
                    hnl[i] = (short) (ONE_Q14 - (short) tmp32no1);
                    if (hnl[i] < 0) {
                        hnl[i] = 0;
                    }
                }
            }
            if (hnl[i] != 0) {
                numPosCoef++;
            }
        }

        if (mult == 2) {
            for (var i = 0; i < PART_LEN1; i++) {
                hnl[i] = (short) ((hnl[i] * hnl[i]) >> 14);
            }
            var avgHnl32 = 0;
            for (var i = MIN_PREF_BAND; i <= MAX_PREF_BAND; i++) {
                avgHnl32 += hnl[i];
            }
            avgHnl32 /= MAX_PREF_BAND - MIN_PREF_BAND + 1;
            for (var i = MAX_PREF_BAND; i < PART_LEN1; i++) {
                if (hnl[i] > (short) avgHnl32) {
                    hnl[i] = (short) avgHnl32;
                }
            }
        }

        if (nlpFlag) {
            for (var i = 0; i < PART_LEN1; i++) {
                if (hnl[i] > NLP_COMP_HIGH) {
                    hnl[i] = ONE_Q14;
                } else if (hnl[i] < NLP_COMP_LOW) {
                    hnl[i] = 0;
                }
                var nlpGain = numPosCoef < 3 ? 0 : ONE_Q14;
                if (hnl[i] == ONE_Q14 && nlpGain == ONE_Q14) {
                    hnl[i] = ONE_Q14;
                } else {
                    hnl[i] = (short) ((hnl[i] * nlpGain) >> 14);
                }
                efw[2 * i] = (short) mulRound14(dfw[2 * i], hnl[i]);
                efw[2 * i + 1] = (short) mulRound14(dfw[2 * i + 1], hnl[i]);
            }
        } else {
            for (var i = 0; i < PART_LEN1; i++) {
                efw[2 * i] = (short) mulRound14(dfw[2 * i], hnl[i]);
                efw[2 * i + 1] = (short) mulRound14(dfw[2 * i + 1], hnl[i]);
            }
        }

        if (cngMode) {
            comfortNoise(ptrDfaClean, efw, hnl);
        }

        inverseFftAndWindow(fftBuffer, efw, output, nearendClean != null);
        return 0;
    }

    /**
     * Returns {@code (a * b + 2^13) >> 14} on 16-bit operands
     * ({@code WEBRTC_SPL_MUL_16_16_RSFT_WITH_ROUND} with a shift of 14).
     *
     * @param a the first operand, read as 16-bit
     * @param b the second operand, read as 16-bit
     * @return the rounded product
     */
    private static int mulRound14(int a, int b) {
        return ((short) a * (short) b + (1 << 13)) >> 14;
    }

    /**
     * Stores a far-end spectrum and its Q-domain as the newest entry of the history
     * ({@code WebRtcAecm_UpdateFarHistory}).
     *
     * @param farSpectrum the far-end magnitude spectrum
     * @param farQ its Q-domain
     */
    private void updateFarHistory(char[] farSpectrum, int farQ) {
        farHistoryPos++;
        if (farHistoryPos >= MAX_DELAY) {
            farHistoryPos = 0;
        }
        farQDomains[farHistoryPos] = farQ;
        System.arraycopy(farSpectrum, 0, farHistory, farHistoryPos * PART_LEN1, PART_LEN1);
    }

    /**
     * Windows a two-block time signal, transforms it and computes the magnitude of each bin
     * ({@code TimeToFrequencyDomain} and {@code WindowAndFFT}); the sum of the magnitudes is left
     * in {@link #magnitudeSum}.
     *
     * @param timeSignal the {@value #PART_LEN2} samples
     * @param freqSignal receives the spectrum, interleaved real and imaginary parts of
     * {@value #PART_LEN1} bins
     * @param magnitude receives the magnitude of each bin
     * @return the left shift applied to the time signal, which is the Q-domain of the spectrum
     */
    private int timeToFrequencyDomain(short[] timeSignal, short[] freqSignal, char[] magnitude) {
        var peak = FixedPointMath.maxAbsValueW16(timeSignal, 0, PART_LEN2);
        var scaling = FixedPointMath.normW16(peak);
        var buffer = fftBuffer;
        for (var i = 0; i < PART_LEN; i++) {
            var scaled = (short) (timeSignal[i] * (1 << scaling));
            buffer[i] = (short) ((scaled * SQRT_HANNING[i]) >> 14);
            scaled = (short) (timeSignal[i + PART_LEN] * (1 << scaling));
            buffer[PART_LEN + i] = (short) ((scaled * SQRT_HANNING[PART_LEN - i]) >> 14);
        }
        fft.forward(buffer, 0, freqSignal);
        for (var i = 0; i < PART_LEN; i++) {
            freqSignal[2 * i + 1] = (short) -freqSignal[2 * i + 1];
        }

        freqSignal[1] = 0;
        freqSignal[2 * PART_LEN + 1] = 0;
        magnitude[0] = (char) abs16(freqSignal[0]);
        magnitude[PART_LEN] = (char) abs16(freqSignal[2 * PART_LEN]);
        var sum = magnitude[0] + magnitude[PART_LEN];
        for (var i = 1; i < PART_LEN; i++) {
            var real = freqSignal[2 * i];
            var imag = freqSignal[2 * i + 1];
            if (real == 0) {
                magnitude[i] = (char) abs16(imag);
            } else if (imag == 0) {
                magnitude[i] = (char) abs16(real);
            } else {
                var absReal = (short) abs16(real);
                var absImag = (short) abs16(imag);
                var sumOfSquares = FixedPointMath.addSatW32(absReal * absReal, absImag * absImag);
                magnitude[i] = (char) AecmMath.sqrtFloor(sumOfSquares);
            }
            sum += magnitude[i];
        }
        magnitudeSum = sum;
        return scaling;
    }

    /**
     * Returns the absolute value of a 16-bit value without narrowing ({@code WEBRTC_SPL_ABS_W16}),
     * so {@code -32768} gives {@code 32768}.
     *
     * @param a the value, read as 16-bit
     * @return its absolute value
     */
    private static int abs16(int a) {
        var value = (short) a;
        return value >= 0 ? value : -value;
    }

    /**
     * Updates the log energies of the near end, the far end and both echo estimates, and the
     * far-end activity levels ({@code WebRtcAecm_CalcEnergies}).
     *
     * @param farOffset the index in {@link #farHistory} of the aligned far-end spectrum
     * @param farQ the Q-domain of the aligned far-end spectrum
     * @param nearEner the sum of the noisy near-end magnitudes, unsigned
     * @param echoEst receives the echo estimated through the stored channel per bin
     */
    private void calcEnergies(int farOffset, short farQ, int nearEner, int[] echoEst) {
        int increaseMaxShifts = 4;
        int decreaseMaxShifts = 11;
        int increaseMinShifts = 11;
        int decreaseMinShifts = 3;

        System.arraycopy(nearLogEnergy, 0, nearLogEnergy, 1, MAX_BUF_LEN - 1);
        nearLogEnergy[0] = logOfEnergyInQ8(nearEner, dfaNoisyQDomain);

        var tmpFar = 0;
        var tmpAdapt = 0;
        var tmpStored = 0;
        for (var i = 0; i < PART_LEN1; i++) {
            int far = farHistory[farOffset + i];
            echoEst[i] = channelStored[i] * far;
            tmpFar += far;
            tmpAdapt += channelAdapt16[i] * far;
            tmpStored += echoEst[i];
        }

        System.arraycopy(echoAdaptLogEnergy, 0, echoAdaptLogEnergy, 1, MAX_BUF_LEN - 1);
        System.arraycopy(echoStoredLogEnergy, 0, echoStoredLogEnergy, 1, MAX_BUF_LEN - 1);
        farLogEnergy = logOfEnergyInQ8(tmpFar, farQ);
        echoAdaptLogEnergy[0] = logOfEnergyInQ8(tmpAdapt, RESOLUTION_CHANNEL16 + farQ);
        echoStoredLogEnergy[0] = logOfEnergyInQ8(tmpStored, RESOLUTION_CHANNEL16 + farQ);

        if (farLogEnergy > FAR_ENERGY_MIN) {
            if (startupState == 0) {
                increaseMaxShifts = 2;
                decreaseMinShifts = 2;
                increaseMinShifts = 8;
            }
            farEnergyMin = asymFilt(farEnergyMin, farLogEnergy, increaseMinShifts, decreaseMinShifts);
            farEnergyMax = asymFilt(farEnergyMax, farLogEnergy, increaseMaxShifts, decreaseMaxShifts);
            farEnergyMaxMin = (short) (farEnergyMax - farEnergyMin);

            var tmp16 = (short) (2560 - farEnergyMin);
            if (tmp16 > 0) {
                tmp16 = (short) ((tmp16 * FAR_ENERGY_VAD_REGION) >> 9);
            } else {
                tmp16 = 0;
            }
            tmp16 += FAR_ENERGY_VAD_REGION;

            if (startupState == 0 || vadUpdateCount > 1024) {
                farEnergyVad = (short) (farEnergyMin + tmp16);
            } else {
                if (farEnergyVad > farLogEnergy) {
                    farEnergyVad += (short) ((farLogEnergy + tmp16 - farEnergyVad) >> 6);
                    vadUpdateCount = 0;
                } else {
                    vadUpdateCount++;
                }
            }
            farEnergyMse = (short) (farEnergyVad + (1 << 8));
        }

        if (farLogEnergy > farEnergyVad) {
            if (startupState == 0 || farEnergyMaxMin > FAR_ENERGY_DIFF) {
                currentVadValue = true;
            }
        } else {
            currentVadValue = false;
        }
        if (currentVadValue && firstVad) {
            firstVad = false;
            if (echoAdaptLogEnergy[0] > nearLogEnergy[0]) {
                for (var i = 0; i < PART_LEN1; i++) {
                    channelAdapt16[i] >>= 3;
                }
                echoAdaptLogEnergy[0] -= 3 << 8;
                firstVad = true;
            }
        }
    }

    /**
     * Returns the log of an energy in Q8 ({@code LogOfEnergyInQ8}).
     *
     * @param energy the energy, unsigned
     * @param qDomain the Q-domain of the energy
     * @return {@code log2(energy) - qDomain + 7} in Q8, or {@code 7} in Q8 for zero energy
     */
    private static short logOfEnergyInQ8(int energy, int qDomain) {
        var logEnergyQ8 = (short) (7 << 7);
        if (energy != 0) {
            var zeros = FixedPointMath.normU32(energy);
            var frac = (short) (((energy << zeros) & 0x7FFFFFFF) >>> 23);
            logEnergyQ8 += (short) (((31 - zeros) << 8) + frac - (qDomain << 8));
        }
        return logEnergyQ8;
    }

    /**
     * Moves a filtered value towards a new value with different shifts upwards and downwards
     * ({@code WebRtcAecm_AsymFilt}).
     *
     * @param filtOld the previous filtered value
     * @param inVal the new value
     * @param stepSizePos the right shift applied when rising
     * @param stepSizeNeg the right shift applied when falling
     * @return the filtered value; {@code inVal} when {@code filtOld} is at a 16-bit extreme
     */
    private static short asymFilt(short filtOld, short inVal, int stepSizePos, int stepSizeNeg) {
        if (filtOld == Short.MAX_VALUE || filtOld == Short.MIN_VALUE) {
            return inVal;
        }
        var retVal = filtOld;
        if (filtOld > inVal) {
            retVal -= (short) ((filtOld - inVal) >> stepSizeNeg);
        } else {
            retVal += (short) ((inVal - filtOld) >> stepSizePos);
        }
        return retVal;
    }

    /**
     * Returns the NLMS step of the channel adaptation as a right shift
     * ({@code WebRtcAecm_CalcStepSize}).
     *
     * @return the step, {@code 0} when the far end is inactive
     */
    private short calcStepSize() {
        short mu = MU_MAX;
        if (!currentVadValue) {
            mu = 0;
        } else if (startupState > 0) {
            if (farEnergyMin >= farEnergyMax) {
                mu = MU_MIN;
            } else {
                var tmp16 = (short) (farLogEnergy - farEnergyMin);
                var tmp32 = tmp16 * MU_DIFF;
                tmp32 = FixedPointMath.divW32W16(tmp32, farEnergyMaxMin);
                mu = (short) (MU_MIN - 1 - (short) tmp32);
            }
            if (mu < MU_MAX) {
                mu = MU_MAX;
            }
        }
        return mu;
    }

    /**
     * Adapts the echo channel with the NLMS step and decides whether to store or restore it
     * ({@code WebRtcAecm_UpdateChannel}).
     *
     * @param farOffset the index in {@link #farHistory} of the aligned far-end spectrum
     * @param farQ the Q-domain of the aligned far-end spectrum
     * @param dfa the noisy near-end magnitude spectrum
     * @param mu the NLMS step as a right shift, {@code 0} to skip the adaptation
     * @param echoEst the echo estimated through the stored channel, recomputed when the channel
     * is stored
     */
    private void updateChannel(int farOffset, short farQ, char[] dfa, short mu, int[] echoEst) {
        if (mu != 0) {
            for (var i = 0; i < PART_LEN1; i++) {
                int far = farHistory[farOffset + i];
                var zerosCh = (short) FixedPointMath.normU32(channelAdapt32[i]);
                var zerosFar = (short) FixedPointMath.normU32(far);
                int tmpU32no1;
                short shiftChFar;
                if (zerosCh + zerosFar > 31) {
                    tmpU32no1 = channelAdapt32[i] * far;
                    shiftChFar = 0;
                } else {
                    shiftChFar = (short) (32 - zerosCh - zerosFar);
                    tmpU32no1 = (shiftChFar >= 32 ? 0 : channelAdapt32[i] >> shiftChFar) * far;
                }
                var zerosNum = (short) FixedPointMath.normU32(tmpU32no1);
                short zerosDfa;
                if (dfa[i] != 0) {
                    zerosDfa = (short) FixedPointMath.normU32(dfa[i]);
                } else {
                    zerosDfa = 32;
                }
                var tmp16no1 = (short) (zerosDfa - 2 + dfaNoisyQDomain - RESOLUTION_CHANNEL32 - farQ + shiftChFar);
                short xfaQ;
                short dfaQ;
                if (zerosNum > tmp16no1 + 1) {
                    xfaQ = tmp16no1;
                    dfaQ = (short) (zerosDfa - 2);
                } else {
                    xfaQ = (short) (zerosNum - 2);
                    dfaQ = (short) (RESOLUTION_CHANNEL32 + farQ - dfaNoisyQDomain - shiftChFar + xfaQ);
                }
                tmpU32no1 = xfaQ >= 0 ? tmpU32no1 * (1 << xfaQ) : tmpU32no1 >>> -xfaQ;
                int tmpU32no2 = dfaQ >= 0 ? dfa[i] * (1 << dfaQ) : dfa[i] >>> -dfaQ;
                var tmp32no1 = tmpU32no2 - tmpU32no1;
                zerosNum = (short) FixedPointMath.normW32(tmp32no1);
                if (tmp32no1 != 0 && far > (CHANNEL_VAD << farQ)) {
                    int tmp32no2;
                    short shiftNum;
                    if (zerosNum + zerosFar > 31) {
                        if (tmp32no1 > 0) {
                            tmp32no2 = tmp32no1 * far;
                        } else {
                            tmp32no2 = -(-tmp32no1 * far);
                        }
                        shiftNum = 0;
                    } else {
                        shiftNum = (short) (32 - (zerosNum + zerosFar));
                        if (tmp32no1 > 0) {
                            tmp32no2 = (tmp32no1 >> shiftNum) * far;
                        } else {
                            tmp32no2 = -((-tmp32no1 >> shiftNum) * far);
                        }
                    }
                    tmp32no2 = FixedPointMath.divW32W16(tmp32no2, i + 1);
                    var shift2ResChan = (short) (shiftNum + shiftChFar - xfaQ - mu - ((30 - zerosFar) << 1));
                    if (FixedPointMath.normW32(tmp32no2) < shift2ResChan) {
                        tmp32no2 = Integer.MAX_VALUE;
                    } else {
                        tmp32no2 = shift2ResChan >= 0 ? tmp32no2 * (1 << shift2ResChan) : tmp32no2 >> -shift2ResChan;
                    }
                    channelAdapt32[i] = FixedPointMath.addSatW32(channelAdapt32[i], tmp32no2);
                    if (channelAdapt32[i] < 0) {
                        channelAdapt32[i] = 0;
                    }
                    channelAdapt16[i] = (short) (channelAdapt32[i] >> 16);
                }
            }
        }

        if (startupState == 0 && currentVadValue) {
            storeAdaptiveChannel(farOffset, echoEst);
        } else {
            if (farLogEnergy < farEnergyMse) {
                mseChannelCount = 0;
            } else {
                mseChannelCount++;
            }
            if (mseChannelCount >= MIN_MSE_COUNT + 10) {
                var mseStored = 0;
                var mseAdapt = 0;
                for (var i = 0; i < MIN_MSE_COUNT; i++) {
                    mseStored += Math.abs(echoStoredLogEnergy[i] - nearLogEnergy[i]);
                    mseAdapt += Math.abs(echoAdaptLogEnergy[i] - nearLogEnergy[i]);
                }
                if ((mseStored << MSE_RESOLUTION) < MIN_MSE_DIFF * mseAdapt
                        && (mseStoredOld << MSE_RESOLUTION) < MIN_MSE_DIFF * mseAdaptOld) {
                    resetAdaptiveChannel();
                } else if (MIN_MSE_DIFF * mseStored > (mseAdapt << MSE_RESOLUTION)
                        && mseAdapt < mseThreshold
                        && mseAdaptOld < mseThreshold) {
                    storeAdaptiveChannel(farOffset, echoEst);
                    if (mseThreshold == Integer.MAX_VALUE) {
                        mseThreshold = mseAdapt + mseAdaptOld;
                    } else {
                        var scaledThreshold = mseThreshold * 5 / 8;
                        mseThreshold += ((mseAdapt - scaledThreshold) * 205) >> 8;
                    }
                }
                mseChannelCount = 0;
                mseStoredOld = mseStored;
                mseAdaptOld = mseAdapt;
            }
        }
    }

    /**
     * Copies the adapted channel into the stored channel and recomputes the echo estimate
     * ({@code StoreAdaptiveChannelC}).
     *
     * @param farOffset the index in {@link #farHistory} of the aligned far-end spectrum
     * @param echoEst receives the echo estimated through the stored channel
     */
    private void storeAdaptiveChannel(int farOffset, int[] echoEst) {
        System.arraycopy(channelAdapt16, 0, channelStored, 0, PART_LEN1);
        for (var i = 0; i < PART_LEN1; i++) {
            echoEst[i] = channelStored[i] * farHistory[farOffset + i];
        }
    }

    /**
     * Restores the adapted channel from the stored channel ({@code ResetAdaptiveChannelC}).
     */
    private void resetAdaptiveChannel() {
        System.arraycopy(channelStored, 0, channelAdapt16, 0, PART_LEN1);
        for (var i = 0; i < PART_LEN1; i++) {
            channelAdapt32[i] = channelStored[i] << 16;
        }
    }

    /**
     * Updates and returns the suppression gain from the echo estimation error
     * ({@code WebRtcAecm_CalcSuppressionGain}).
     *
     * @return the smoothed suppression gain in Q8
     */
    private short calcSuppressionGain() {
        short supGain = SUPGAIN_DEFAULT;
        short tmp16no1;
        if (!currentVadValue) {
            supGain = 0;
        } else {
            tmp16no1 = (short) (nearLogEnergy[0] - echoStoredLogEnergy[0]);
            var dE = (short) abs16(tmp16no1);
            if (dE < ENERGY_DEV_TOL) {
                if (dE < SUPGAIN_EPC_DT) {
                    var tmp32no1 = supGainErrParamDiffAB * dE;
                    tmp32no1 += SUPGAIN_EPC_DT >> 1;
                    tmp16no1 = (short) FixedPointMath.divW32W16(tmp32no1, SUPGAIN_EPC_DT);
                    supGain = (short) (supGainErrParamA - tmp16no1);
                } else {
                    var tmp32no1 = supGainErrParamDiffBD * (ENERGY_DEV_TOL - dE);
                    tmp32no1 += (ENERGY_DEV_TOL - SUPGAIN_EPC_DT) >> 1;
                    tmp16no1 = (short) FixedPointMath.divW32W16(tmp32no1, ENERGY_DEV_TOL - SUPGAIN_EPC_DT);
                    supGain = (short) (supGainErrParamD + tmp16no1);
                }
            } else {
                supGain = supGainErrParamD;
            }
        }
        tmp16no1 = supGain > supGainOld ? supGain : supGainOld;
        supGainOld = supGain;
        this.supGain += (short) ((tmp16no1 - this.supGain) >> 4);
        return this.supGain;
    }

    /**
     * Adds comfort noise shaped by the estimated noise floor to the filtered spectrum
     * ({@code ComfortNoise}).
     *
     * @param dfa the clean near-end magnitude spectrum
     * @param out the filtered spectrum, interleaved real and imaginary parts, updated in place
     * @param lambda the suppression filter per bin in Q14
     */
    private void comfortNoise(char[] dfa, short[] out, short[] lambda) {
        var shiftFromNearToNoise = (short) (NOISE_EST_Q_DOMAIN - dfaCleanQDomain);
        int minTrackShift;
        if (noiseEstCtr < 100) {
            noiseEstCtr++;
            minTrackShift = 6;
        } else {
            minTrackShift = 9;
        }
        for (var i = 0; i < PART_LEN1; i++) {
            int tmp32 = dfa[i];
            var outLShift32 = tmp32 << shiftFromNearToNoise;
            if (outLShift32 < noiseEst[i]) {
                noiseEstTooLowCtr[i] = 0;
                if (noiseEst[i] < (1 << minTrackShift)) {
                    noiseEstTooHighCtr[i]++;
                    if (noiseEstTooHighCtr[i] >= NOISE_EST_INC_COUNT) {
                        noiseEst[i]--;
                        noiseEstTooHighCtr[i] = 0;
                    }
                } else {
                    noiseEst[i] -= (noiseEst[i] - outLShift32) >> minTrackShift;
                }
            } else {
                noiseEstTooHighCtr[i] = 0;
                if ((noiseEst[i] >> 19) > 0) {
                    noiseEst[i] >>= 11;
                    noiseEst[i] *= 2049;
                } else if ((noiseEst[i] >> 11) > 0) {
                    noiseEst[i] *= 2049;
                    noiseEst[i] >>= 11;
                } else {
                    noiseEstTooLowCtr[i]++;
                    if (noiseEstTooLowCtr[i] >= NOISE_EST_INC_COUNT) {
                        noiseEst[i] += (noiseEst[i] >> 9) + 1;
                        noiseEstTooLowCtr[i] = 0;
                    }
                }
            }
        }
        for (var i = 0; i < PART_LEN1; i++) {
            var tmp32 = noiseEst[i] >> shiftFromNearToNoise;
            if (tmp32 > 32767) {
                tmp32 = 32767;
                noiseEst[i] = tmp32 << shiftFromNearToNoise;
            }
            noiseRShift16[i] = (short) tmp32;
            var tmp16 = (short) (ONE_Q14 - lambda[i]);
            noiseRShift16[i] = (short) ((tmp16 * noiseRShift16[i]) >> 14);
        }
        seed = AecmMath.randUArray(randW16, PART_LEN, seed);
        uReal[0] = 0;
        uImag[0] = 0;
        for (var i = 1; i < PART_LEN1; i++) {
            var tmp16 = (short) ((359 * randW16[i - 1]) >> 15);
            uReal[i] = (short) ((noiseRShift16[i] * AecmMath.COS_TABLE[tmp16]) >> 13);
            uImag[i] = (short) ((-noiseRShift16[i] * AecmMath.SIN_TABLE[tmp16]) >> 13);
        }
        uImag[PART_LEN] = 0;
        for (var i = 0; i < PART_LEN1; i++) {
            out[2 * i] = FixedPointMath.addSatW16(out[2 * i], uReal[i]);
            out[2 * i + 1] = FixedPointMath.addSatW16(out[2 * i + 1], uImag[i]);
        }
    }

    /**
     * Resynthesizes a block from the filtered spectrum with overlap-add and shifts the block
     * histories ({@code InverseFFTAndWindow}).
     *
     * @param fftInput scratch for the inverse transform input
     * @param efw the filtered spectrum, interleaved real and imaginary parts; overwritten
     * with the inverse transform output
     * @param output receives the {@value #PART_LEN} output samples
     * @param clean whether a clean near-end signal is in use
     */
    private void inverseFftAndWindow(short[] fftInput, short[] efw, short[] output, boolean clean) {
        for (int i = 1, j = 2; i < PART_LEN; i += 1, j += 2) {
            fftInput[j] = efw[2 * i];
            fftInput[j + 1] = (short) -efw[2 * i + 1];
        }
        fftInput[0] = efw[0];
        fftInput[1] = (short) -efw[1];
        fftInput[PART_LEN2] = efw[2 * PART_LEN];
        fftInput[PART_LEN2 + 1] = (short) -efw[2 * PART_LEN + 1];

        var ifftOut = efw;
        var outCfft = fft.inverse(fftInput, ifftOut);
        var shift = outCfft - dfaCleanQDomain;
        for (var i = 0; i < PART_LEN; i++) {
            ifftOut[i] = (short) mulRound14(ifftOut[i], SQRT_HANNING[i]);
            int sample = ifftOut[i];
            var tmp32no1 = shift >= 0 ? sample * (1 << shift) : sample >> -shift;
            output[i] = (short) saturate16(tmp32no1 + outBuf[i]);
            tmp32no1 = (ifftOut[PART_LEN + i] * SQRT_HANNING[PART_LEN - i]) >> 14;
            tmp32no1 = shift >= 0 ? tmp32no1 * (1 << shift) : tmp32no1 >> -shift;
            outBuf[i] = (short) saturate16(tmp32no1);
        }
        System.arraycopy(xBuf, PART_LEN, xBuf, 0, PART_LEN);
        System.arraycopy(dBufNoisy, PART_LEN, dBufNoisy, 0, PART_LEN);
        if (clean) {
            System.arraycopy(dBufClean, PART_LEN, dBufClean, 0, PART_LEN);
        }
    }

    /**
     * Clamps a value to the 16-bit range ({@code WEBRTC_SPL_SAT}).
     *
     * @param value the 32-bit value
     * @return {@code value} clamped to {@code [-32768, 32767]}
     */
    private static int saturate16(int value) {
        return value > Short.MAX_VALUE ? Short.MAX_VALUE : value < Short.MIN_VALUE ? Short.MIN_VALUE : value;
    }
}
