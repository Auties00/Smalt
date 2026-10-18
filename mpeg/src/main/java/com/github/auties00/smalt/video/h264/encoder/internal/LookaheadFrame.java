package com.github.auties00.smalt.video.h264.encoder.internal;

/**
 * Holds a source picture that was analyzed but not yet encoded: the converted samples, the
 * half-resolution costs of {@link FrameAnalyzer} and the timestamps the caller gave.
 *
 * <p>Instances are recycled by {@link EncoderCore}, so every field is overwritten when a picture
 * enters the look-ahead queue.
 */
final class LookaheadFrame {
    /**
     * The converted source samples.
     */
    final Frame source;

    /**
     * The intra cost of each macroblock, see {@link FrameAnalyzer#intraCost}.
     */
    final int[] intraCost;

    /**
     * The inter cost of each macroblock against the previous source picture, see
     * {@link FrameAnalyzer#interCost}.
     */
    final int[] interCost;

    /**
     * The half-resolution vector of each macroblock, see {@link FrameAnalyzer#vectors}.
     */
    final int[] vectors;

    /**
     * The luma activity of each macroblock, see {@link FrameAnalyzer#activity}.
     */
    final int[] activity;

    /**
     * The sum of {@link #intraCost}.
     */
    long intraSum;

    /**
     * The sum of the smaller of the inter and intra cost of each macroblock.
     */
    long interSum;

    /**
     * The presentation timestamp in time-base units.
     */
    long pts;

    /**
     * The duration in time-base units, or {@code 0} when unknown.
     */
    long duration;

    /**
     * Whether the timestamp was moved forward to follow the previous picture's.
     */
    boolean nominal;

    /**
     * Whether a key frame was requested at or before this picture.
     */
    boolean keyframeRequested;

    /**
     * The picture type of an intra coded (IDR) picture.
     */
    static final int INTRA = 0;

    /**
     * The picture type of a P picture.
     */
    static final int PREDICTED = 1;

    /**
     * The picture type of a B picture that no other picture references.
     */
    static final int BIDIRECTIONAL = 2;

    /**
     * The picture type of a B picture that other B pictures reference.
     */
    static final int B_REFERENCED = 3;

    /**
     * The duration in seconds rate control assumes for the picture.
     */
    double seconds;

    /**
     * The index of the picture in display order, counted from the first picture of the stream.
     */
    long displayIndex;

    /**
     * Whether the picture is coded as an IDR picture.
     */
    boolean idr;

    /**
     * The picture type, one of {@link #INTRA}, {@link #PREDICTED}, {@link #BIDIRECTIONAL}
     * and {@link #B_REFERENCED}.
     */
    int type;

    /**
     * The picture order count of the picture, relative to the last IDR picture before it.
     */
    long poc;

    /**
     * For an anchor picture, the number of B pictures coded after it that precede it in display
     * order.
     */
    int bCount;

    /**
     * For an anchor picture, the sum of the inter costs of the pictures it covers, itself and its
     * B pictures.
     */
    long groupInter;

    /**
     * For an anchor picture, the sum of the durations in seconds of the pictures it covers.
     */
    double groupSeconds;

    /**
     * Constructs a look-ahead frame.
     *
     * @param width the coded luma width
     * @param height the coded luma height
     */
    LookaheadFrame(int width, int height) {
        this.source = new Frame(width, height);
        var count = (width >> 4) * (height >> 4);
        this.intraCost = new int[count];
        this.interCost = new int[count];
        this.vectors = new int[count];
        this.activity = new int[count];
    }

    /**
     * Copies the results of an analysis into this frame.
     *
     * @param analyzer the analyzer that has finished a picture
     */
    void store(FrameAnalyzer analyzer) {
        System.arraycopy(analyzer.intraCost, 0, intraCost, 0, intraCost.length);
        System.arraycopy(analyzer.interCost, 0, interCost, 0, interCost.length);
        System.arraycopy(analyzer.vectors, 0, vectors, 0, vectors.length);
        System.arraycopy(analyzer.activity, 0, activity, 0, activity.length);
        intraSum = analyzer.intraSum;
        interSum = analyzer.interSum;
    }
}
