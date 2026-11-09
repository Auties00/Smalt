package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.video.hevc.encoder.HevcRateControl;

/**
 * Chooses the slice quantizer of every picture so the stream meets its rate control mode.
 *
 * <p>The size of a picture is predicted from its complexity, a sum of Hadamard differences of its source
 * blocks, with a model {@code bits = a * complexity / qstep} per picture type, where
 * {@code qstep = 2^((qp - 4) / 6)} is the quantizer step and {@code a} is re-learned from every coded picture.
 *
 * <ul>
 *   <li>Constant quality codes P pictures at the configured quantizer, IDR pictures two steps finer (the only
 *       picture of a still picture stream at the quantizer itself) and B pictures coarser by their pyramid
 *       depth.</li>
 *   <li>Average bitrate aims each picture at its share of the bitrate, weighted by type, corrected by the
 *       bits spent above or below the target so far, spread over two seconds.</li>
 *   <li>Constant bitrate aims each picture at the per-picture budget corrected towards a buffer fullness of
 *       one half, so the output rate follows the target picture by picture.</li>
 * </ul>
 * The P and B budgets are scaled so that a group of one P picture and its B pictures takes the share of as
 * many pictures. In the bitrate modes a leaky bucket of the buffer size, filled at the maximum bitrate, bounds
 * every picture: its quantizer is raised when the prediction says it would not fit, and a picture that still
 * does not fit is coded again at the quantizer {@link #recodeQp(int, long)} returns. Quantizers stay inside the
 * configured window, and the planned quantizer of consecutive P pictures moves by at most three steps.
 * Instances are not thread-safe except {@link #setBitrate(double)} and {@link #bitrate()}.
 */
final class RateController {
    /**
     * The picture types the model distinguishes.
     */
    static final int INTRA = 0;

    /**
     * A P picture.
     */
    static final int PREDICTED = 1;

    /**
     * A B picture.
     */
    static final int BIPREDICTED = 2;

    /**
     * The relative budgets of I, P and B pictures.
     */
    private static final double[] WEIGHT = {4.0, 1.0, 0.6};

    /**
     * The settings.
     */
    private final EncoderSettings s;

    /**
     * The model coefficients {@code a} of each picture type.
     */
    private final double[] model = {0.25, 0.4, 0.4};

    /**
     * Whether the model of each type has learned from a coded picture.
     */
    private final boolean[] learned = new boolean[3];

    /**
     * The target bitrate in bits per second.
     */
    private volatile double bitrate;

    /**
     * The rate at which the bucket fills, as a multiple of the bitrate.
     */
    private final double peakRatio;

    /**
     * The factor of the P and B weights that gives a group of B pictures and its P picture the budget of as
     * many average pictures.
     */
    private final double interScale;

    /**
     * The bucket size, as playback time.
     */
    private final double bufferSeconds;

    /**
     * The bits in the bucket.
     */
    private double fullness;

    /**
     * The bits spent so far.
     */
    private double spent;

    /**
     * The bits the target allowed so far.
     */
    private double wanted;

    /**
     * The quantizer of the last P picture, or -1 before the first.
     */
    private int lastP = -1;

    /**
     * Constructs a controller.
     *
     * @param s the settings
     */
    RateController(EncoderSettings s) {
        this.s = s;
        this.bitrate = s.bitrate;
        this.peakRatio = s.bitrate == 0 ? 1 : (double) s.maxBitrate / s.bitrate;
        this.bufferSeconds = s.maxBitrate == 0 ? 0 : (double) s.bufferBits / s.maxBitrate;
        this.fullness = s.bufferBits;
        this.interScale = (s.bFrames + 1) / (WEIGHT[PREDICTED] + WEIGHT[BIPREDICTED] * s.bFrames);
    }

    /**
     * {@return the current target bitrate in bits per second, 0 for constant quality}
     */
    double bitrate() {
        return s.rateControl == HevcRateControl.CONSTANT_QUALITY ? 0 : bitrate;
    }

    /**
     * Changes the target bitrate of the pictures coded from now on.
     *
     * @param value the target in bits per second
     */
    void setBitrate(double value) {
        this.bitrate = value;
    }

    /**
     * Returns the quantizer step of a quantizer.
     *
     * @param qp the quantizer
     * @return the step
     */
    private static double qstep(double qp) {
        return StrictMath.pow(2.0, (qp - 4) / 6.0);
    }

    /**
     * Returns the quantizer of a picture.
     *
     * @param type {@link #INTRA}, {@link #PREDICTED} or {@link #BIPREDICTED}
     * @param depth the pyramid depth of a B picture, from 1
     * @param complexity the picture's complexity
     * @param seconds the picture's duration
     * @return the slice quantizer
     */
    int pictureQp(int type, int depth, long complexity, double seconds) {
        var min = s.options.minQuantizer();
        var max = s.options.maxQuantizer();
        if (s.rateControl == HevcRateControl.CONSTANT_QUALITY) {
            var q = s.options.quality();
            var qp = switch (type) {
                case INTRA -> s.stillPicture ? q : q - 2;
                case PREDICTED -> q;
                default -> q + depth;
            };
            return Math.clamp(qp, min, max);
        }
        var rate = bitrate;
        var frameBits = rate * seconds;
        double budget;
        if (s.rateControl == HevcRateControl.CONSTANT_BITRATE) {
            var target = frameBits * weight(type);
            var buffer = s.bufferBits * rate / Math.max(1, s.bitrate);
            var correction = (fullness - buffer * 0.5) * 0.25;
            budget = target + correction;
            if (type == INTRA && s.options.realtime() && learned[INTRA]) {
                budget = Math.min(budget, 7 * frameBits);
            }
        } else {
            var drift = (wanted - spent) / 2.0;
            budget = frameBits * weight(type) + drift * seconds;
        }
        budget = Math.max(budget, frameBits * 0.1);
        if (s.bufferBits > 0) {
            budget = Math.min(budget, Math.max(fullness * 0.9, frameBits * 0.1));
        }
        var step = model[type] * Math.max(1, complexity) / budget;
        var qp = (int) Math.round(6 * StrictMath.log(Math.max(step, 1e-9)) / StrictMath.log(2) + 4);
        if (type == PREDICTED && lastP >= 0 && learned[PREDICTED]) {
            qp = Math.clamp(qp, lastP - 3, lastP + 3);
        }
        if (type == BIPREDICTED && lastP >= 0) {
            qp = Math.max(qp, lastP);
        }
        return Math.clamp(qp, min, max);
    }

    /**
     * Returns the budget of a picture type relative to the average picture.
     *
     * @param type the picture type
     * @return the weight
     */
    private double weight(int type) {
        return type == INTRA ? WEIGHT[INTRA] : WEIGHT[type] * interScale;
    }

    /**
     * Returns whether a coded picture would underflow the buffering verifier, so it must be coded again.
     *
     * @param bits the coded size in bits
     * @return {@code true} when the bucket does not hold the picture
     */
    boolean overflows(long bits) {
        return s.bufferBits > 0 && bits > fullness;
    }

    /**
     * Returns the quantizer to code a picture again with after it overflowed the buffering verifier, aimed at
     * nine tenths of the bucket's bits.
     *
     * @param qp the quantizer the picture overflowed at
     * @param bits the coded size in bits
     * @return a larger quantizer, at most the largest of the window
     */
    int recodeQp(int qp, long bits) {
        var target = Math.max(1.0, 0.9 * fullness);
        var delta = (int) Math.ceil(6 * StrictMath.log(bits / target) / StrictMath.log(2));
        return Math.min(s.options.maxQuantizer(), qp + Math.max(1, delta));
    }

    /**
     * Learns from a coded picture.
     *
     * @param type the picture type
     * @param qp the slice quantizer it was coded with
     * @param bits the coded size in bits
     * @param complexity the picture's complexity
     * @param seconds the picture's duration
     */
    void update(int type, int qp, long bits, long complexity, double seconds) {
        var observed = bits * qstep(qp) / Math.max(1, complexity);
        model[type] = learned[type] ? 0.6 * model[type] + 0.4 * observed : observed;
        learned[type] = true;
        if (type == INTRA && !learned[PREDICTED]) {
            model[PREDICTED] = observed;
        }
        if (type == PREDICTED) {
            lastP = qp;
            if (!learned[BIPREDICTED]) {
                model[BIPREDICTED] = observed;
            }
        } else if (type == INTRA && lastP < 0) {
            lastP = qp + 2;
        }
        if (s.rateControl == HevcRateControl.CONSTANT_QUALITY) {
            return;
        }
        var rate = bitrate;
        spent += bits;
        wanted += rate * seconds;
        if (s.bufferBits > 0) {
            var size = bufferSeconds * rate * peakRatio;
            fullness = Math.min(size, fullness - bits + rate * peakRatio * seconds);
            fullness = Math.max(fullness, 0);
        }
    }
}
