package com.github.auties00.smalt.audio.aac.internal.usac;

/**
 * Holds the state one core channel of a USAC element carries from frame to frame.
 *
 * <p>Instances are not thread-safe.
 */
final class UsacChannel {
    /**
     * The frequency domain stream of the current frame.
     */
    final FdChannel fd;

    /**
     * The spectral noiseless decoder context, shared by FD and TCX frames.
     */
    final ArithmeticDecoder coder = new ArithmeticDecoder();

    /**
     * The noise filling random state, shared by FD and TCX frames.
     */
    final int[] seed = new int[1];

    /**
     * The overlap of the filterbank.
     */
    final float[] overlap;

    /**
     * The time output of the current frame.
     */
    final float[] output;

    /**
     * The window sequence of the previous frame.
     */
    int lastSequence = FdChannel.ONLY_LONG;

    /**
     * The window shape of the previous frame.
     */
    int previousShape;

    /**
     * Whether the current frame is coded in the linear prediction domain.
     */
    boolean lpd;

    /**
     * Whether the previous frame was coded in the linear prediction domain.
     */
    boolean lastLpd;

    /**
     * The linear prediction domain state.
     */
    final LpdState lpdState = new LpdState();

    /**
     * The final spectrum of the previous frame, kept for the MDST estimate of complex prediction.
     */
    final float[] savedSpectrum;

    /**
     * The time-warped filterbank state, or {@code null} before the first frame of an element that uses the
     * time-warped MDCT.
     */
    TimeWarpedSynthesis.State warp;

    /**
     * The error concealment state.
     */
    final UsacConcealment ec;

    /**
     * The value of {@link #lpd} before the current frame, restored when the frame fails to decode.
     */
    boolean savedLpd;

    /**
     * The value of {@link #lastLpd} before the current frame, restored when the frame fails to decode.
     */
    boolean savedLastLpd;

    /**
     * The noise state of {@link #seed} before the current frame, restored when the frame fails to decode.
     */
    int savedSeed;

    /**
     * Whether the current frame of the channel has been synthesized, decoded or concealed.
     */
    boolean complete;

    /**
     * Constructs the state of a channel.
     *
     * @param frameLength the core frame length, 768 or 1024
     * @param tableIndex the sampling table index of the band tables
     * @param seed the initial noise filling state
     */
    UsacChannel(int frameLength, int tableIndex, int seed) {
        this.fd = new FdChannel(frameLength, tableIndex);
        this.overlap = new float[2 * frameLength];
        this.output = new float[frameLength];
        this.savedSpectrum = new float[frameLength];
        this.ec = new UsacConcealment(frameLength);
        this.seed[0] = seed;
    }

    /**
     * Records the coding mode and the noise state before a frame is decoded.
     */
    void mark() {
        savedLpd = lpd;
        savedLastLpd = lastLpd;
        savedSeed = seed[0];
        complete = false;
    }

    /**
     * Restores the coding mode and the noise state recorded by {@link #mark()}, after a frame failed to decode.
     */
    void restore() {
        lpd = savedLpd;
        lastLpd = savedLastLpd;
        seed[0] = savedSeed;
    }
}
