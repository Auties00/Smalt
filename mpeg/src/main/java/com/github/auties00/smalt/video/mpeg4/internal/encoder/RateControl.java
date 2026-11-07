package com.github.auties00.smalt.video.mpeg4.internal.encoder;

/**
 * Chooses the quantizer of each picture to hold a target bitrate, or returns a fixed quantizer.
 *
 * <p>The model takes the bits of a picture as inversely proportional to its quantizer, with one complexity per
 * picture type (intra, predicted, bidirectional), and corrects the target of the next picture by the fullness of a
 * virtual buffer of one second, so the long-run rate follows the target. Instances are not thread-safe.
 */
public final class RateControl {
    /**
     * The intra picture type.
     */
    public static final int INTRA = 0;

    /**
     * The predicted picture type.
     */
    public static final int PREDICTED = 1;

    /**
     * The bidirectional picture type.
     */
    public static final int BIDIRECTIONAL = 2;

    /**
     * The share of a picture's bits each type takes relative to a predicted picture.
     */
    private static final double[] WEIGHTS = {4.0, 1.0, 0.6};

    /**
     * The quantizer offsets each type starts from relative to a predicted picture.
     */
    private static final double[] QUANT_SCALE = {0.8, 1.0, 1.25};

    /**
     * The target bitrate in bits per second, or 0 for a fixed quantizer.
     */
    private long bitrate;

    /**
     * The nominal frame rate in frames per second.
     */
    private double frameRate;

    /**
     * The fixed quantizer, or 0 under rate control.
     */
    private final int fixed;

    /**
     * The smallest and largest quantizer.
     */
    private final int minQuant;
    private final int maxQuant;

    /**
     * The product of bits and quantizer of the last picture of each type, or 0 before the first.
     */
    private final double[] complexity = new double[3];

    /**
     * The quantizer of the last picture of each type, or 0 before the first.
     */
    private final int[] lastQuant = new int[3];

    /**
     * The buffer fullness in bits: positive when more bits were spent than the target.
     */
    private double fullness;

    /**
     * The intra share of a key frame interval, used for the per-picture targets.
     */
    private final int interval;

    /**
     * The number of bidirectional pictures between references.
     */
    private final int bFrames;

    /**
     * Constructs a controller.
     *
     * @param bitrate the target bitrate in bits per second, positive, ignored with a fixed quantizer
     * @param frameRate the nominal frame rate in frames per second, positive
     * @param fixed the fixed quantizer, or 0 for rate control
     * @param minQuant the smallest quantizer, from 1 to 31
     * @param maxQuant the largest quantizer, from {@code minQuant} to 31
     * @param interval the key frame interval in frames, 0 for none
     * @param bFrames the bidirectional pictures between references
     */
    public RateControl(long bitrate, double frameRate, int fixed, int minQuant, int maxQuant, int interval,
                       int bFrames) {
        this.bitrate = bitrate;
        this.frameRate = frameRate;
        this.fixed = fixed;
        this.minQuant = minQuant;
        this.maxQuant = maxQuant;
        this.interval = interval;
        this.bFrames = bFrames;
    }

    /**
     * Changes the target bitrate from the next picture on; the buffer keeps its fullness relative to one second.
     *
     * @param bitrate the new target in bits per second, positive
     */
    public void retarget(long bitrate) {
        if (this.bitrate > 0) {
            fullness = fullness * bitrate / this.bitrate;
        }
        this.bitrate = bitrate;
    }

    /**
     * {@return whether the quantizer is fixed}
     */
    public boolean fixed() {
        return fixed > 0;
    }

    /**
     * Returns the quantizer of the next picture.
     *
     * @param type the picture type
     * @param area the number of macroblocks of the picture, for the first estimate
     * @return the quantizer, from the smallest to the largest
     */
    public int quantizer(int type, int area) {
        if (fixed > 0) {
            return fixed;
        }
        var target = target(type);
        if (complexity[type] == 0) {
            if (complexity[PREDICTED] != 0) {
                return clamp((int) Math.round(lastQuant[PREDICTED] * QUANT_SCALE[type]));
            }
            // A first estimate: about 0.15 bit per sample at quantizer 10, the order of magnitude of natural scenes.
            var estimate = area * 384.0 * 0.15 * 10 * WEIGHTS[type];
            return clamp((int) Math.round(estimate / Math.max(1, target)));
        }
        var q = (int) Math.round(complexity[type] / Math.max(1, target));
        var previous = lastQuant[type];
        // The quantizer moves by at most a quarter per picture, so a single scene change does not swing it.
        var low = Math.max(1, (int) Math.floor(previous * 0.75));
        var high = Math.max(low, (int) Math.ceil(previous * 1.25));
        return clamp(Math.max(low, Math.min(high, q)));
    }

    /**
     * Records the outcome of a picture.
     *
     * @param type the picture type
     * @param quant the quantizer it was coded with
     * @param bits the bits it took
     */
    public void update(int type, int quant, long bits) {
        complexity[type] = (double) bits * quant;
        lastQuant[type] = quant;
        if (fixed == 0) {
            fullness += bits - bitrate / frameRate;
            var limit = bitrate * 2.0;
            fullness = Math.max(-limit, Math.min(limit, fullness));
        }
    }

    /**
     * Returns the bit target of the next picture of a type.
     *
     * @param type the picture type
     * @return the target in bits
     */
    private double target(int type) {
        var perFrame = bitrate / frameRate;
        var group = interval > 0 ? interval : Math.max(1, (int) Math.round(frameRate * 10));
        var references = Math.max(1, group / (bFrames + 1));
        var bidirectional = group - references;
        var predicted = Math.max(0, references - 1);
        var units = WEIGHTS[INTRA] + predicted * WEIGHTS[PREDICTED] + bidirectional * WEIGHTS[BIDIRECTIONAL];
        var share = perFrame * group * WEIGHTS[type] / units;
        var correction = 1 - fullness / Math.max(1.0, bitrate);
        return share * Math.max(0.25, Math.min(1.75, correction));
    }

    /**
     * Clamps a quantizer to the allowed range.
     *
     * @param q the quantizer
     * @return the clamped quantizer
     */
    private int clamp(int q) {
        return Math.max(minQuant, Math.min(maxQuant, q));
    }
}
