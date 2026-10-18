package com.github.auties00.smalt.audio.voice.internal.aecm;

import java.util.Objects;

/**
 * Cancels the acoustic echo of a 16 kHz mono capture signal with WebRTC's mobile echo canceller
 * (AECM), a low-complexity fixed-point canceller.
 *
 * <p>The far-end signal, the audio played on the local loudspeaker, is passed to
 * {@link #bufferFarend(short[], int)} in 10 ms frames of {@value #FRAME_SAMPLES} samples; the
 * near-end signal, the microphone audio, to
 * {@link #process(short[], int, short[], int, short[], int, int)}, together with the delay, in
 * milliseconds, the platform measured between playing a far-end sample and capturing its echo.
 * The far-end frames are queued (up to 500 ms) and consumed by the near-end frames at the pace
 * of the reported delay. While the queue and the reported delay settle (the first 60 to 500 ms)
 * the canceller passes the near-end signal through unchanged; afterwards it suppresses the echo
 * with a gain chosen by the echo mode, from {@code 0} (quiet earpiece or headset, the mildest)
 * to {@code 4} (loud speakerphone, the strongest), {@code 3} (speakerphone) being the default.
 * Comfort noise, when enabled, fills the suppressed bins with noise shaped like the estimated
 * background.
 *
 * <p>Methods return {@code 0} on success or one of the reference's error codes:
 * {@link #BAD_PARAMETER_ERROR} for an invalid configuration and {@link #BAD_PARAMETER_WARNING}
 * when the reported delay was clamped to {@code [0, 500]} ms (the frame is still processed). An
 * instance is not thread-safe and does not allocate after construction.
 *
 * @implNote This implementation ports {@code modules/audio_processing/aecm/echo_control_mobile.cc}
 * of webrtc-audio-processing 2.1 ({@code WebRtcAecm_Init}, {@code WebRtcAecm_set_config},
 * {@code WebRtcAecm_BufferFarend}, {@code WebRtcAecm_Process} with the far-end buffering,
 * startup and delay tracking), fixed to 16 kHz, over {@link AecmCore}.
 */
public final class MobileEchoCanceller {
    /**
     * The number of samples of one 10 ms frame at 16 kHz.
     */
    public static final int FRAME_SAMPLES = 160;

    /**
     * The error code of a configuration outside the accepted range
     * ({@code AECM_BAD_PARAMETER_ERROR}).
     */
    public static final int BAD_PARAMETER_ERROR = 12004;

    /**
     * The warning code of a reported delay clamped to {@code [0, 500]} ms
     * ({@code AECM_BAD_PARAMETER_WARNING}).
     */
    public static final int BAD_PARAMETER_WARNING = 12100;

    /**
     * The number of 80-sample frames the far-end queue holds ({@code BUF_SIZE_FRAMES}).
     */
    private static final int BUF_SIZE_FRAMES = 50;

    /**
     * The number of samples per millisecond at 8 kHz ({@code kSampMsNb}).
     */
    private static final int SAMP_MS_NB = 8;

    /**
     * The number of samples of one core frame.
     */
    private static final int FRAME_LEN = AecmCore.FRAME_LEN;

    /**
     * The echo canceller core.
     */
    private final AecmCore core = new AecmCore();

    /**
     * The queue of far-end samples not yet consumed.
     */
    private final AecmRingBuffer farendBuf = new AecmRingBuffer(BUF_SIZE_FRAMES * FRAME_LEN);

    /**
     * The last far-end frame read for each frame position, reused when the queue runs dry.
     */
    private final short[][] farendOld = new short[2][FRAME_LEN];

    /**
     * Scratch for the far-end frame read from the queue.
     */
    private final short[] farend = new short[FRAME_LEN];

    /**
     * The number of queued far-end frames that ends the startup.
     */
    private short bufSizeStart;

    /**
     * The smoothed far-end delay estimate of the queue, in samples.
     */
    private int knownDelay;

    /**
     * The number of consecutive stable delay reports during the startup.
     */
    private short counter;

    /**
     * The sum of the stable delay reports during the startup.
     */
    private short sum;

    /**
     * The first delay report of the current stable run.
     */
    private short firstVal;

    /**
     * The number of frames processed while the queue size was being determined.
     */
    private short checkBufSizeCtr;

    /**
     * The last reported delay plus 10 ms, in milliseconds.
     */
    private short msInSndCardBuf;

    /**
     * The filtered delay between the reported delay and the queued far-end samples, in samples.
     */
    private short filtDelay;

    /**
     * The number of consecutive frames the filtered delay has disagreed with
     * {@link #knownDelay}.
     */
    private int timeForDelayChange;

    /**
     * Whether the canceller is still starting up and passes the near end through.
     */
    private boolean ecStartup;

    /**
     * Whether the queue size is still being determined.
     */
    private boolean checkBuffSize;

    /**
     * The difference between the filtered and the known delay of the previous frame.
     */
    private short lastDelayDiff;

    /**
     * Creates and initializes a canceller ({@code WebRtcAecm_Create}, {@code WebRtcAecm_Init} at
     * 16 kHz and {@code WebRtcAecm_set_config}).
     *
     * @param echoMode     the echo mode, from {@code 0} (mildest) to {@code 4} (strongest)
     * @param comfortNoise whether comfort noise fills the suppressed bins
     * @throws IllegalArgumentException if {@code echoMode} is outside {@code [0, 4]}
     */
    public MobileEchoCanceller(int echoMode, boolean comfortNoise) {
        core.init();
        farendBuf.init();
        checkBuffSize = true;
        ecStartup = true;
        setConfig(3, true);
        if (setConfig(echoMode, comfortNoise) != 0) {
            throw new IllegalArgumentException("echoMode must be in [0, 4]: " + echoMode);
        }
    }

    /**
     * Changes the echo mode and the comfort noise ({@code WebRtcAecm_set_config}).
     *
     * <p>A new echo mode also resets the smoothed suppression gain to the mode's default.
     *
     * @param echoMode     the echo mode, from {@code 0} (mildest) to {@code 4} (strongest)
     * @param comfortNoise whether comfort noise fills the suppressed bins
     * @return {@code 0}, or {@link #BAD_PARAMETER_ERROR} if {@code echoMode} is outside
     *         {@code [0, 4]}, in which case the comfort noise setting is still applied
     */
    public int setConfig(int echoMode, boolean comfortNoise) {
        core.cngMode = comfortNoise;
        if (echoMode < 0 || echoMode > 4) {
            return BAD_PARAMETER_ERROR;
        }
        int supGain;
        int paramA;
        int paramB;
        int paramD;
        if (echoMode < 3) {
            var shift = 3 - echoMode;
            supGain = AecmCore.SUPGAIN_DEFAULT >> shift;
            paramA = AecmCore.SUPGAIN_ERROR_PARAM_A >> shift;
            paramB = AecmCore.SUPGAIN_ERROR_PARAM_B >> shift;
            paramD = AecmCore.SUPGAIN_ERROR_PARAM_D >> shift;
        } else {
            var shift = echoMode - 3;
            supGain = AecmCore.SUPGAIN_DEFAULT << shift;
            paramA = AecmCore.SUPGAIN_ERROR_PARAM_A << shift;
            paramB = AecmCore.SUPGAIN_ERROR_PARAM_B << shift;
            paramD = AecmCore.SUPGAIN_ERROR_PARAM_D << shift;
        }
        core.supGain = (short) supGain;
        core.supGainOld = (short) supGain;
        core.supGainErrParamA = (short) paramA;
        core.supGainErrParamD = (short) paramD;
        core.supGainErrParamDiffAB = (short) (paramA - paramB);
        core.supGainErrParamDiffBD = (short) (paramB - paramD);
        return 0;
    }

    /**
     * Queues one 10 ms frame of the far-end signal ({@code WebRtcAecm_BufferFarend}).
     *
     * <p>Once the startup is over, a queue that holds far fewer samples than the reported delay
     * asks for is first padded by stepping its read position back over already consumed samples.
     * Samples beyond the queue's capacity are dropped.
     *
     * @param farend the far-end samples; not modified
     * @param offset the index of the first of the {@value #FRAME_SAMPLES} samples
     * @return {@code 0}
     * @throws IndexOutOfBoundsException if {@code farend} holds fewer than {@value #FRAME_SAMPLES}
     *                                   samples from {@code offset}
     */
    public int bufferFarend(short[] farend, int offset) {
        Objects.checkFromIndexSize(offset, FRAME_SAMPLES, farend.length);
        if (!ecStartup) {
            delayComp();
        }
        farendBuf.write(farend, offset, FRAME_SAMPLES);
        return 0;
    }

    /**
     * Cancels the echo of one 10 ms frame of the near-end signal ({@code WebRtcAecm_Process}).
     *
     * <p>The noisy near-end signal drives the delay and channel estimation; the clean one, when
     * given (for example after a noise suppressor), is the signal the suppression is applied to,
     * otherwise the noisy one is. The output may alias either input.
     *
     * @param noisy          the noisy near-end samples
     * @param noisyOffset    the index of the first noisy sample
     * @param clean          the clean near-end samples, or {@code null}
     * @param cleanOffset    the index of the first clean sample; ignored when {@code clean} is
     *                       {@code null}
     * @param out            receives the {@value #FRAME_SAMPLES} output samples
     * @param outOffset      the index of the first output sample
     * @param msInSndCardBuf the render to capture delay in milliseconds, read as a 16-bit value
     *                       and clamped to {@code [0, 500]}
     * @return {@code 0}, {@link #BAD_PARAMETER_WARNING} when the delay was clamped, or {@code -1}
     *         on an internal error
     * @throws IndexOutOfBoundsException if an array holds fewer than {@value #FRAME_SAMPLES}
     *                                   samples from its offset
     */
    public int process(short[] noisy, int noisyOffset, short[] clean, int cleanOffset, short[] out, int outOffset,
                       int msInSndCardBuf) {
        Objects.checkFromIndexSize(noisyOffset, FRAME_SAMPLES, noisy.length);
        Objects.checkFromIndexSize(outOffset, FRAME_SAMPLES, out.length);
        if (clean != null) {
            Objects.checkFromIndexSize(cleanOffset, FRAME_SAMPLES, clean.length);
        }
        var retVal = 0;
        var delay = (short) msInSndCardBuf;
        if (delay < 0) {
            delay = 0;
            retVal = BAD_PARAMETER_WARNING;
        } else if (delay > 500) {
            delay = 500;
            retVal = BAD_PARAMETER_WARNING;
        }
        delay += 10;
        this.msInSndCardBuf = delay;

        var nFrames = FRAME_SAMPLES / FRAME_LEN;
        var nBlocks10ms = nFrames / core.mult;

        if (ecStartup) {
            if (clean == null) {
                System.arraycopy(noisy, noisyOffset, out, outOffset, FRAME_SAMPLES);
            } else {
                System.arraycopy(clean, cleanOffset, out, outOffset, FRAME_SAMPLES);
            }
            var filledBuffers = (short) farendBuf.availableRead() / FRAME_LEN;
            if (checkBuffSize) {
                checkBufSizeCtr++;
                if (counter == 0) {
                    firstVal = this.msInSndCardBuf;
                    sum = 0;
                }
                if (Math.abs(firstVal - this.msInSndCardBuf) < Math.max(0.2 * this.msInSndCardBuf, SAMP_MS_NB)) {
                    sum += this.msInSndCardBuf;
                    counter++;
                } else {
                    counter = 0;
                }
                if (counter * nBlocks10ms >= 6) {
                    bufSizeStart = (short) Math.min((3 * sum * core.mult) / (counter * 40), BUF_SIZE_FRAMES);
                    checkBuffSize = false;
                }
                if (checkBufSizeCtr * nBlocks10ms > 50) {
                    bufSizeStart = (short) Math.min((3 * this.msInSndCardBuf * core.mult) / 40, BUF_SIZE_FRAMES);
                    checkBuffSize = false;
                }
            }
            if (!checkBuffSize) {
                if (filledBuffers == bufSizeStart) {
                    ecStartup = false;
                } else if (filledBuffers > bufSizeStart) {
                    farendBuf.moveReadPointer(farendBuf.availableRead() - bufSizeStart * FRAME_LEN);
                    ecStartup = false;
                }
            }
        } else {
            for (var i = 0; i < nFrames; i++) {
                var filledBuffers = (short) farendBuf.availableRead() / FRAME_LEN;
                short[] farendFrame;
                if (filledBuffers > 0) {
                    farendBuf.read(farend, 0, FRAME_LEN);
                    System.arraycopy(farend, 0, farendOld[i], 0, FRAME_LEN);
                    farendFrame = farend;
                } else {
                    farendFrame = farendOld[i];
                }
                if (i == 1) {
                    estBufDelay(this.msInSndCardBuf);
                }
                if (core.processFrame(farendFrame, 0, noisy, noisyOffset + FRAME_LEN * i, clean,
                        cleanOffset + FRAME_LEN * i, out, outOffset + FRAME_LEN * i) == -1) {
                    return -1;
                }
            }
        }
        return retVal;
    }

    /**
     * Tracks the delay between the reported delay and the queued far-end samples
     * ({@code WebRtcAecm_EstBufDelay}).
     *
     * @param msInSndCardBuf the reported delay plus 10 ms, in milliseconds
     */
    private void estBufDelay(short msInSndCardBuf) {
        var nSampFar = (short) farendBuf.availableRead();
        var nSampSndCard = (short) (msInSndCardBuf * SAMP_MS_NB * core.mult);
        var delayNew = (short) (nSampSndCard - nSampFar);
        if (delayNew < FRAME_LEN) {
            farendBuf.moveReadPointer(FRAME_LEN);
            delayNew += FRAME_LEN;
        }
        filtDelay = (short) Math.max(0, (8 * filtDelay + 2 * delayNew) / 10);
        var diff = (short) (filtDelay - knownDelay);
        if (diff > 224) {
            if (lastDelayDiff < 96) {
                timeForDelayChange = 0;
            } else {
                timeForDelayChange++;
            }
        } else if (diff < 96 && knownDelay > 0) {
            if (lastDelayDiff > 224) {
                timeForDelayChange = 0;
            } else {
                timeForDelayChange++;
            }
        } else {
            timeForDelayChange = 0;
        }
        lastDelayDiff = diff;
        if (timeForDelayChange > 25) {
            knownDelay = Math.max(filtDelay - 160, 0);
        }
    }

    /**
     * Pads the far-end queue when it holds far fewer samples than the reported delay asks for
     * ({@code WebRtcAecm_DelayComp}).
     */
    private void delayComp() {
        var nSampFar = farendBuf.availableRead();
        var maxStuffSamp = 10 * FRAME_LEN;
        var nSampSndCard = msInSndCardBuf * SAMP_MS_NB * core.mult;
        var delayNew = nSampSndCard - nSampFar;
        if (delayNew > AecmCore.FAR_BUF_LEN - FRAME_LEN * core.mult) {
            var nSampAdd = Math.max((nSampSndCard >> 1) - nSampFar, FRAME_LEN);
            nSampAdd = Math.min(nSampAdd, maxStuffSamp);
            farendBuf.moveReadPointer(-nSampAdd);
        }
    }
}
