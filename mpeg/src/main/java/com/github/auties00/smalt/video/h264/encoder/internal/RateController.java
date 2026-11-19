package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.encoder.H264RateControl;

/**
 * Chooses the quantizer of every frame so the stream meets its rate control mode.
 *
 * <p>The size of a frame is predicted from its complexity (the half-resolution SATD estimate of
 * {@link FrameAnalyzer}) with a per-frame-type model
 * {@code log(bits) = a + log(complexity) - GAMMA * log(qscale)}, where
 * {@code qscale = 0.85 * 2^((qp - 12) / 6)} is the quantizer step and {@code a} is re-learned
 * after every coded frame from its actual size.
 *
 * <ul>
 *   <li>Constant quality codes P frames at the configured quantizer and IDR frames three steps
 *       finer.</li>
 *   <li>Average bitrate plans a two-second horizon: it finds the base quantizer at which the
 *       predicted size of the horizon's P and IDR frames (at their average complexities) equals
 *       the horizon's budget plus the accumulated surplus or deficit, scales its quantizer step
 *       by {@code 1 + (spent - wanted) / (bitrate * DRIFT_SECONDS)} (between one half and two)
 *       because the size model is biased on some content, then codes each P frame at
 *       the base quantizer raised or lowered with its complexity relative to the average (so a
 *       frame's size grows with roughly the 0.6th power of its complexity) and each IDR frame
 *       three steps finer, unless that would exceed eight frame budgets (or 12% of the horizon's
 *       budget, whichever is larger). Quantizers of consecutive P frames move by at most
 *       {@link #MAX_STEP}, or four times that when the complexity changes by more than a factor
 *       of two.</li>
 *   <li>Constant bitrate aims each frame at the per-frame budget corrected towards a bucket
 *       fullness of 80%, so the output rate follows the target frame by frame.</li>
 * </ul>
 *
 * <p>Before the first frame the model is a generic guess; when the first frame misses its size
 * target by more than {@link #FIRST_FRAME_TOLERANCE}, the model is recalibrated from the coded
 * size and the frame is coded again. Later IDR frames get the same treatment when they come out
 * more than {@link #OVERSHOOT_FACTOR} times larger than predicted and than the frame budget, which
 * happens on content whose size jumps with the quantizer, such as heavy noise that is either
 * coded or quantized away entirely (see {@link #correctSize(boolean, int, long, long)}).
 *
 * <p>The buffering verifier is a leaky bucket that starts full, loses each frame's bits when the
 * frame is decoded and refills at the maximum bitrate over the frame's duration, never above the
 * buffer size. A frame is never allowed to take more bits than the bucket holds: its quantizer is
 * raised before coding when the prediction says it would not fit, and the frame is coded again
 * with a higher quantizer when it still does not.
 *
 * <p>Every quantizer stays inside the configured window. In real-time mode the first IDR frame is
 * aimed at no more than {@link #FIRST_KEYFRAME_BUDGET} nominal frame budgets and the later ones at
 * no more than {@link #KEYFRAME_BUDGET}, and no frame is coded again to correct the size model
 * (only to avoid a buffer overflow). Because the
 * first IDR frame cannot be corrected, real-time mode predicts IDR sizes with an intra model
 * calibrated on natural video, {@code log(bits) = a + log(complexity) - }{@link
 * #REALTIME_INTRA_GAMMA}{@code  * log(qscale)} starting from {@code a = }{@link
 * #REALTIME_INTRA_OFFSET}, and the first P frames move at most {@link #MAX_STEP} away from the
 * IDR quantizer until the inter model has learned from a coded P frame. The target bitrate can
 * change between frames (see {@link #setBitrate(double)}).
 *
 * <p>In real-time mode the model of a P frame whose reference, the previous frame, is a P frame
 * also depends on the reference's quantizer: {@code log(bits) = a + log(complexity) - GAMMA *
 * log(qscale) + up * d+ + down * d-}, where {@code d = referenceQp - qp} moved {@link
 * #REFERENCE_DEAD_ZONE} steps towards zero is split into its positive part {@code d+} (the frame is
 * quantized finer than its reference and re-codes the detail the reference lost) and its negative
 * part {@code d-} (coarser, so the residual of a well predicted picture quantizes away).
 * The slopes start from priors measured on natural camera content ({@link #PRIOR_REFERENCE_UP},
 * {@link #PRIOR_REFERENCE_DOWN}) and are learned per stream from the P frames that follow a P frame,
 * {@code up} from those quantized finer than their reference and {@code down} from those quantized
 * coarser, each as a least-squares fit of {@code observed - a} against {@code d} weighted by
 * {@code d * d} with exponential forgetting ({@link #REFERENCE_SLOPE_MEMORY}), so a frame one step
 * away moves the estimate far less than one several steps away; {@code a} is then learned from the
 * observation with the reference term removed. On content predicted almost perfectly, such as
 * a scrolling screen, a frame one step finer than its reference can cost five times more than one
 * at the same step, and the term keeps the controller from lowering the quantizer by several steps
 * at once and then raising it again; without it a third of the quantizer changes on such content
 * were three steps or more.
 *
 * @implNote This implementation follows the complexity-based approach of MPEG-2 Test Model 5 (the
 * global complexity {@code X = S * Q}), generalized to an exponent and a planning horizon, with a
 * VBV constraint; it does not port any encoder's code. The real-time intra model plays the role
 * of the initial quantizer estimate of openh264 and libvpx, which derive the first key frame's
 * quantizer from the bits per pixel, but uses the frame's measured complexity instead: fitted on
 * the first frames of eight 240p to 720p conference and outdoor sequences coded intra at every
 * quantizer from 24 to 46, it predicts every size within a factor of 0.83 to 1.33, where the
 * shared exponent {@link #GAMMA} under-predicts IDR frames at real-time quantizers (30 to 44) by
 * two to three times.
 */
final class RateController {
    /**
     * The smallest quantizer rate control chooses.
     */
    static final int QP_MIN = 10;

    /**
     * The largest quantizer.
     */
    static final int QP_MAX = 51;

    /**
     * The quantizer offset of IDR frames relative to P frames in real-time mode.
     */
    private static final int IDR_OFFSET = 3;

    /**
     * The quantizer offset of IDR frames relative to P frames outside real-time mode, where a
     * finer IDR picture pays for itself in the many pictures that predict from it.
     */
    private static final int FILE_IDR_OFFSET = 2;

    /**
     * The exponent of the quantizer step in the size model.
     */
    private static final double GAMMA = 1.6;

    /**
     * The exponent of the quantizer step in the IDR size model of real-time mode, fitted on
     * natural video coded intra at the quantizers real-time streams use.
     */
    private static final double REALTIME_INTRA_GAMMA = 0.85;

    /**
     * The initial IDR model offset {@code a} of real-time mode, natural logarithm, fitted together
     * with {@link #REALTIME_INTRA_GAMMA}.
     */
    private static final double REALTIME_INTRA_OFFSET = -0.30;

    /**
     * The size limit of the first IDR picture in real-time mode, in frame budgets: the bits one
     * frame of the nominal duration may use at the target bitrate.
     */
    static final int FIRST_KEYFRAME_BUDGET = 5;

    /**
     * The size limit of an IDR picture after the first in real-time mode, in frame budgets.
     */
    static final int KEYFRAME_BUDGET = 7;

    /**
     * The exponent of the relative complexity in a P frame's quantizer step, chosen so a frame's
     * size grows with the 0.6th power of its complexity.
     */
    private static final double COMPLEXITY_EXPONENT = 0.4 / GAMMA;

    /**
     * The largest quantizer change between consecutive P frames.
     */
    private static final double MAX_STEP = 3;

    /**
     * The planning horizon of average bitrate, in seconds.
     */
    private static final double HORIZON = 2.0;

    /**
     * The share of the frames of a group of pictures that an IDR frame takes in bits.
     */
    private static final double INTRA_SHARE = 0.25;

    /**
     * The largest size of a coded IDR frame, as a multiple of its target, before it is coded again.
     */
    private static final double INTRA_OVERSHOOT = 1.5;

    /**
     * The size of a coded P frame, as a multiple of the larger of its prediction and its budget,
     * above which it is coded again.
     */
    private static final double P_OVERSHOOT = 4;

    /**
     * The size, in budgets of the frame's group, that a P frame coded again is aimed at.
     */
    private static final double P_RECODE_TARGET = 2;

    /**
     * The size of the first IDR frame of a real-time stream, in frame budgets.
     */
    private static final double IFIRST = 6;

    /**
     * The share of the horizon's budget a real-time IDR frame may take.
     */
    private static final double ICAP = 0.12;
    /**
     * The accumulated surplus or deficit, in seconds at the target bitrate, that scales the base
     * quantizer step of average bitrate by a factor of two (clamped to between one half and two).
     */
    private static final double DRIFT_SECONDS = 2.0;

    /**
     * The factor by which the first frame may miss its size target before it is coded again.
     */
    private static final double FIRST_FRAME_TOLERANCE = 1.6;

    /**
     * The largest quantizer decrease applied when the first frame came out too small.
     */
    private static final int FIRST_FRAME_MAX_DECREASE = 6;

    /**
     * The weight of the previous IDR size model when it is updated from a coded IDR frame.
     */
    private static final double INTRA_MODEL_MEMORY = 0.6;

    /**
     * The weight of the previous P size model when it is updated from a coded P frame; high so
     * that the quantizer stays steady on content whose size jumps between quantizers.
     */
    private static final double INTER_MODEL_MEMORY = 0.8;

    /**
     * The factor by which a later IDR frame must exceed both its predicted size and the frame budget
     * to be coded again.
     */
    private static final double OVERSHOOT_FACTOR = 3;

    /**
     * The rate control mode.
     */
    private final H264RateControl mode;

    /**
     * The target bitrate in bits per second.
     */
    private double bitrate;

    /**
     * The buffer refill rate in bits per second.
     */
    private double maxBitrate;

    /**
     * The buffer size in bits, or {@code 0} without a buffer constraint.
     */
    private double bufferSize;

    /**
     * The smallest quantizer any frame is coded with.
     */
    private final int minQp;

    /**
     * The largest quantizer any frame is coded with.
     */
    private final int maxQp;

    /**
     * The quantizer offset of IDR frames relative to P frames.
     */
    private final int idrOffset;

    /**
     * Whether real-time tuning is on: capped IDR frames and no size-model corrections.
     */
    private final boolean realtime;

    /**
     * The configured quality for constant quality.
     */
    private final int quality;

    /**
     * The nominal frame rate.
     */
    private final double frameRate;

    /**
     * The number of frames between periodic IDR frames, or {@code 0} without periodic IDR frames.
     */
    private final int keyframeInterval;

    /**
     * The model offsets {@code a} by frame type (0 intra, 1 inter), natural logarithm.
     */
    private final double[] offset = {StrictMath.log(2.0), StrictMath.log(1.0)};

    /**
     * Whether each model offset learned from a coded frame.
     */
    private final boolean[] learned = new boolean[2];

    /**
     * The number of coded frames each model offset learned from; the first updates are plain
     * averages, so an atypical first frame of a type does not dominate for long.
     */
    private final long[] updates = new long[2];

    /**
     * The decaying average complexity by frame type, {@code 0} before the first frame of the type.
     */
    private final double[] averageComplexity = new double[2];

    /**
     * The bits in the buffer before the next frame is removed.
     */
    private double fill;

    /**
     * The bits the stream should have produced so far.
     */
    private double wanted;

    /**
     * The bits the stream has produced so far.
     */
    private double spent;

    /**
     * The quantizer of the last P frame, or {@code -1} before the first.
     */
    private double lastPQp = -1;

    /**
     * The decaying average of recent P frame quantizers, or {@code -1} before the first.
     */
    private double averagePQp = -1;

    /**
     * The quantizer of the last IDR frame, or {@code -1} before the first.
     */
    private double lastIQp = -1;

    /**
     * The largest learned per-step log-size slope of a P frame quantized finer than its reference.
     */
    private static final double MAX_REFERENCE_UP = 2.0;

    /**
     * The largest learned per-step log-size slope of a P frame quantized coarser than its
     * reference.
     */
    private static final double MAX_REFERENCE_DOWN = 1.0;

    /**
     * The number of quantizer steps around the reference quantizer the reference term ignores.
     *
     * <p>Natural content gains from the small quantizer moves the plain model makes (a frame
     * refined by a step is a better reference for the next); a dead zone of one step cost up to
     * 2% BD-rate on the real-time clips, two steps is within the measurement noise.
     */
    private static final double REFERENCE_DEAD_ZONE = 2.0;

    /**
     * The per-step log-size slope a P frame quantized finer than its reference is assumed to have
     * before the stream has shown its own.
     */
    private static final double PRIOR_REFERENCE_UP = 0.4;

    /**
     * The per-step log-size slope a P frame quantized coarser than its reference is assumed to
     * have before the stream has shown its own.
     */
    private static final double PRIOR_REFERENCE_DOWN = 0.1;

    /**
     * The weight of the prior slopes, in squared quantizer steps.
     */
    private static final double PRIOR_REFERENCE_WEIGHT = 2;

    /**
     * The factor each earlier observation of a reference slope is weighted down by when a new one
     * arrives.
     */
    private static final double REFERENCE_SLOPE_MEMORY = 0.9;

    /**
     * The quantizer of the last coded frame, the reference of the next P frame, or {@code -1}
     * before the first frame.
     */
    private double referenceQp = -1;

    /**
     * Whether the last coded frame was a P frame.
     */
    private boolean referenceInter;

    /**
     * The learned per-step log-size slope of a real-time P frame quantized finer than its
     * reference.
     */
    private double referenceUp = PRIOR_REFERENCE_UP;

    /**
     * The weighted sum of {@code (observed - a) * d} over the frames quantized finer than their
     * reference.
     */
    private double referenceUpSum = PRIOR_REFERENCE_UP * PRIOR_REFERENCE_WEIGHT;

    /**
     * The weighted sum of {@code d * d} over the frames quantized finer than their reference.
     */
    private double referenceUpWeight = PRIOR_REFERENCE_WEIGHT;

    /**
     * The learned per-step log-size slope of a real-time P frame quantized coarser than its
     * reference.
     */
    private double referenceDown = PRIOR_REFERENCE_DOWN;

    /**
     * The weighted sum of {@code (observed - a) * d} over the frames quantized coarser than their
     * reference.
     */
    private double referenceDownSum = PRIOR_REFERENCE_DOWN * PRIOR_REFERENCE_WEIGHT;

    /**
     * The weighted sum of {@code d * d} over the frames quantized coarser than their reference.
     */
    private double referenceDownWeight = PRIOR_REFERENCE_WEIGHT;

    /**
     * The size in bits the first frame was aimed at, or {@code -1} once the first frame is coded.
     */
    private double firstTarget = -1;

    /**
     * The picture distance between the P pictures rate control sees.
     */
    private int anchorSpacing = 1;

    /**
     * The predicted size in bits of the frame about to be coded, at the quantizer chosen for it.
     */
    private double plannedBits;

    /**
     * The bits one frame of the nominal duration may use at the target bitrate.
     */
    private double frameBudget;

    /**
     * The plan of the second pass of a two-pass encode, or {@code null} for a single pass. The frames the
     * first pass measured get their quantizers from it, the later ones from the single-pass model, which
     * learns from every frame.
     */
    private final TwoPassPlan plan;

    /**
     * Whether the quantizer of the frame being coded came from {@link #plan}.
     */
    private boolean planned;

    /**
     * Constructs a rate controller.
     *
     * @param config the encoder configuration
     */
    RateController(EncoderConfig config) {
        this.mode = config.rateControl();
        this.bitrate = config.bitrate();
        this.maxBitrate = config.effectiveMaxBitrate();
        this.bufferSize = config.effectiveBufferSize();
        this.quality = config.quality();
        this.frameRate = config.frameRate().toDouble();
        this.keyframeInterval = config.keyframeInterval();
        this.minQp = config.minQuantizer();
        this.maxQp = config.maxQuantizer();
        this.realtime = config.realtime();
        this.idrOffset = realtime ? IDR_OFFSET : FILE_IDR_OFFSET;
        this.fill = bufferSize;
        if (realtime) {
            offset[0] = REALTIME_INTRA_OFFSET;
        }
        this.plan = config.passRecords() == null || mode != H264RateControl.AVERAGE_BITRATE ? null
                : new TwoPassPlan(config.passRecords(), bitrate, idrOffset);
    }

    /**
     * Returns the exponent of the quantizer step in the size model of a frame type:
     * {@link #REALTIME_INTRA_GAMMA} for IDR frames in real-time mode, {@link #GAMMA} otherwise.
     *
     * @param intra whether the frame is an IDR frame
     * @return the exponent
     */
    private double gamma(boolean intra) {
        return intra && realtime ? REALTIME_INTRA_GAMMA : GAMMA;
    }

    /**
     * Returns the size an IDR frame of a file may take, in frame budgets: a fraction of the
     * frames up to the next periodic IDR frame, as IDR frames of natural video take about that
     * share of the bits when the quantizer follows the frame type.
     *
     * @return the number of budgets
     */
    private double introBudgets() {
        return keyframeInterval > 0 ? Math.clamp(INTRA_SHARE * keyframeInterval, 6, 24) : 8;
    }

    /**
     * Returns the target bitrate.
     *
     * @return the target bitrate in bits per second
     */
    double bitrate() {
        return bitrate;
    }

    /**
     * Retargets the stream at a new bitrate from the next frame on.
     *
     * <p>The buffer refill rate and the buffer size scale with the bitrate, so the buffer keeps
     * its duration and its relative fullness; the long-term accounting of average bitrate restarts
     * at the new rate; and the quantizers that bound the next frames' quantizers move by the
     * change the size model predicts for the new per-frame budget, so the rate follows the new
     * target within a few frames instead of approaching it a few quantizer steps per frame.
     * Constant quality ignores the call.
     *
     * @param newBitrate the new target bitrate in bits per second, positive
     */
    void setBitrate(double newBitrate) {
        if (newBitrate == bitrate || mode == H264RateControl.CONSTANT_QUALITY) {
            return;
        }
        var relative = bufferSize > 0 ? fill / bufferSize : 1;
        var ratio = newBitrate / bitrate;
        bitrate = newBitrate;
        if (plan != null) {
            plan.retarget(newBitrate);
        }
        maxBitrate *= ratio;
        bufferSize *= ratio;
        fill = relative * bufferSize;
        wanted = spent;
        var shift = -6 * StrictMath.log(ratio) / StrictMath.log(2) / GAMMA;
        if (lastPQp >= 0) {
            lastPQp = Math.clamp(lastPQp + shift, minQp, maxQp);
        }
        if (averagePQp >= 0) {
            averagePQp = Math.clamp(averagePQp + shift, minQp, maxQp);
        }
        if (lastIQp >= 0) {
            lastIQp = Math.clamp(lastIQp + shift, minQp, maxQp);
        }
    }

    /**
     * Returns the smallest quantizer rate control chooses: {@link #QP_MIN} or the window's lower
     * end, whichever is larger.
     *
     * @return the lower bound
     */
    private int floorQp() {
        return Math.max(QP_MIN, minQp);
    }

    /**
     * Returns the quantizer step of a quantizer.
     *
     * @param qp the quantizer
     * @return the step
     */
    static double qscale(double qp) {
        return 0.85 * StrictMath.pow(2, (qp - 12) / 6.0);
    }

    /**
     * Returns the quantizer of a quantizer step.
     *
     * @param qscale the step
     * @return the quantizer, unclamped
     */
    static double qp(double qscale) {
        return 12 + 6 * StrictMath.log(qscale / 0.85) / StrictMath.log(2);
    }

    /**
     * Returns whether rate control enforces a buffer.
     *
     * @return whether a bucket is simulated
     */
    boolean hasBuffer() {
        return mode != H264RateControl.CONSTANT_QUALITY && bufferSize > 0 && maxBitrate > 0;
    }

    /**
     * Returns the predicted size of a frame.
     *
     * @param intra whether the frame is an IDR frame
     * @param complexity the frame's complexity estimate
     * @param qp the quantizer
     * @return the predicted size in bits
     */
    double predict(boolean intra, double complexity, double qp) {
        return StrictMath.exp(offset[intra ? 0 : 1] + StrictMath.log(Math.max(1, complexity)) - gamma(intra) * StrictMath.log(qscale(qp))
                + (intra ? 0 : referenceTerm(qp))) + 64;
    }

    /**
     * Returns the log-size change of a P frame from the quantizer of its reference.
     *
     * @param qp the frame's quantizer
     * @return {@code up * d+ + down * d-} for {@code d = referenceQp - qp}, zero outside real-time
     * mode or before the first frame
     */
    private double referenceTerm(double qp) {
        if (!realtime || referenceQp < 0 || !referenceInter) {
            return 0;
        }
        var difference = excess(referenceQp - qp);
        return difference > 0 ? referenceUp * difference : referenceDown * difference;
    }

    /**
     * Returns the part of a quantizer difference beyond the dead zone.
     *
     * @param difference the reference quantizer minus the frame's
     * @return the difference moved {@link #REFERENCE_DEAD_ZONE} towards zero, or zero inside it
     */
    private static double excess(double difference) {
        return Math.signum(difference) * Math.max(0, Math.abs(difference) - REFERENCE_DEAD_ZONE);
    }

    /**
     * Returns the quantizer whose predicted size meets a target.
     *
     * @param intra whether the frame is an IDR frame
     * @param complexity the frame's complexity estimate
     * @param target the target size in bits
     * @return the quantizer, unclamped
     */
    private double solve(boolean intra, double complexity, double target) {
        var bits = Math.max(target - 64, 16);
        var logQscale = (offset[intra ? 0 : 1] + StrictMath.log(Math.max(1, complexity)) - StrictMath.log(bits)) / gamma(intra);
        var qp = qp(StrictMath.exp(logQscale));
        if (intra || !realtime || referenceQp < 0 || !referenceInter || (referenceUp == 0 && referenceDown == 0)) {
            return qp;
        }
        // log(bits) falls by GAMMA * ln(2) / 6 per quantizer step, plus the reference slope on the
        // side of the reference quantizer the solution lies on
        var slope = gamma(false) * StrictMath.log(2) / 6;
        var finerEdge = referenceQp - REFERENCE_DEAD_ZONE;
        if (qp < finerEdge) {
            return qp + referenceUp * (finerEdge - qp) / (slope + referenceUp);
        }
        var coarserEdge = referenceQp + REFERENCE_DEAD_ZONE;
        if (qp > coarserEdge) {
            return qp + referenceDown * (coarserEdge - qp) / (slope + referenceDown);
        }
        return qp;
    }

    /**
     * Chooses the quantizer of the next frame.
     *
     * <p>In the second pass of a two-pass encode, a frame the first pass measured gets the quantizer of the
     * plan, raised while both the plan and the single-pass model predict a size above 90% of the buffer's
     * fullness.
     *
     * @param intra whether the frame is an IDR frame
     * @param complexity the frame's complexity estimate
     * @param duration the frame's duration in seconds
     * @param frame the frame's display index
     * @return the quantizer
     */
    int frameQp(boolean intra, long complexity, double duration, long frame) {
        planned = false;
        if (mode == H264RateControl.CONSTANT_QUALITY) {
            return Math.clamp(intra ? quality - idrOffset : quality, minQp, maxQp);
        }
        var budget = bitrate * duration;
        if (plan != null && plan.covers(frame)) {
            planned = true;
            var qp = Math.clamp(plan.quantizer(frame, intra), floorQp(), maxQp);
            if (hasBuffer()) {
                var limit = 0.9 * fill;
                while (qp < maxQp && Math.min(plan.predict(frame, qp), predict(intra, complexity, qp)) > limit) {
                    qp += 1;
                }
            }
            var chosen = (int) Math.round(Math.clamp(qp, floorQp(), maxQp));
            plannedBits = plan.predict(frame, chosen);
            frameBudget = budget;
            firstTarget = -1;
            return chosen;
        }
        double qp;
        if (mode == H264RateControl.CONSTANT_BITRATE) {
            var target = Math.max(budget + 0.25 * (fill - 0.8 * bufferSize), budget * 0.25);
            if (intra) {
                if (averagePQp >= 0) {
                    qp = averagePQp - idrOffset;
                } else {
                    var intraTarget = Math.min(6 * target, 0.5 * bufferSize);
                    if (!learned[0]) {
                        firstTarget = intraTarget;
                    }
                    qp = solve(true, complexity, intraTarget);
                }
                if (realtime) {
                    qp = Math.max(qp, solve(true, complexity, keyframeCap()));
                }
            } else {
                qp = solve(false, complexity, target);
                var base = lastPQp >= 0 ? lastPQp : lastIQp >= 0 ? lastIQp + idrOffset : qp;
                var relative = complexity / Math.max(1, averageComplexity[1]);
                var step = maxStep(relative);
                qp = Math.clamp(qp, base - step, base + step);
            }
        } else if (!learned[0] && !learned[1]) {
            var intraTarget = (realtime ? IFIRST : introBudgets()) * budget;
            if (hasBuffer()) {
                intraTarget = Math.min(intraTarget, 0.5 * bufferSize);
            }
            if (realtime && intra) {
                intraTarget = Math.min(intraTarget, keyframeCap());
            }
            firstTarget = intra ? intraTarget : budget;
            qp = solve(intra, complexity, firstTarget);
        } else {
            var baseQp = baseQp(intra, complexity);
            if (intra) {
                var cap = Math.max((realtime ? 8 : introBudgets()) * budget, ICAP * bitrate * HORIZON);
                if (hasBuffer()) {
                    cap = Math.min(cap, 0.5 * bufferSize);
                }
                if (realtime) {
                    cap = Math.min(cap, keyframeCap());
                }
                qp = Math.max(baseQp - idrOffset, solve(true, complexity, cap));
            } else {
                var unlearned = !realtime && averageComplexity[1] == 0;
                var relative = unlearned ? 1 : complexity / Math.max(1, averageComplexity[1]);
                qp = qp(qscale(baseQp) * StrictMath.pow(Math.clamp(relative, 0.1, 10), COMPLEXITY_EXPONENT));
                var base = lastPQp >= 0 ? lastPQp : lastIQp >= 0 ? lastIQp + idrOffset : qp;
                var step = unlearned ? MAX_STEP : maxStep(relative);
                qp = Math.clamp(qp, base - step, base + step);
            }
        }
        qp = Math.clamp(qp, floorQp(), maxQp);
        if (hasBuffer()) {
            var limit = 0.9 * fill;
            if (mode == H264RateControl.CONSTANT_BITRATE && !intra) {
                limit = Math.min(limit, Math.max(budget * 3, 0.5 * fill));
            }
            while (qp < maxQp && predict(intra, complexity, qp) > limit) {
                qp += 1;
            }
        }
        var chosen = (int) Math.round(Math.clamp(qp, floorQp(), maxQp));
        plannedBits = predict(intra, complexity, chosen);
        frameBudget = budget;
        return chosen;
    }

    /**
     * Returns the largest quantizer change from the previous P frame: {@link #MAX_STEP}, or four
     * times that when the complexity changed by more than a factor of two. In real-time mode the
     * first P frames, before the inter model has an average complexity to compare with, keep
     * {@link #MAX_STEP}, because their predicted size rests on an unlearned model.
     *
     * @param relative the frame's complexity relative to the average P frame complexity
     * @return the largest change
     */
    private double maxStep(double relative) {
        if (realtime && averageComplexity[1] == 0) {
            return MAX_STEP;
        }
        return (relative > 2 || relative < 0.5 ? 4 * MAX_STEP : MAX_STEP) * anchorSpacing;
    }

    /**
     * Returns the size limit of an IDR frame in real-time mode:
     * {@link #FIRST_KEYFRAME_BUDGET} frame budgets of the nominal frame duration at the target
     * bitrate for the first IDR frame, and {@link #KEYFRAME_BUDGET} for the later ones.
     *
     * @return the limit in bits
     */
    private double keyframeCap() {
        var budgets = learned[0] ? KEYFRAME_BUDGET : FIRST_KEYFRAME_BUDGET;
        return budgets * bitrate / frameRate;
    }

    /**
     * Returns the P frame quantizer at which the predicted size of the planning horizon meets its
     * budget, including the accumulated surplus or deficit, corrected by the long-term drift
     * between the bits spent and the bits wanted (see {@link #DRIFT_SECONDS}).
     *
     * @param intra whether the frame about to be coded is an IDR frame
     * @param complexity the complexity of the frame about to be coded
     * @return the base quantizer, unclamped
     */
    private double baseQp(boolean intra, double complexity) {
        var frames = Math.max(1, HORIZON * frameRate);
        var intraFrames = keyframeInterval > 0 ? frames / keyframeInterval : 0;
        if (intra) {
            intraFrames = Math.max(1, intraFrames);
        }
        var interFrames = Math.max(0, frames - intraFrames) / anchorSpacing;
        var interComplexity = averageComplexity[1] > 0 ? averageComplexity[1]
                : anchorSpacing == 1 ? complexity / 3 : intra ? complexity / 3 * anchorSpacing : complexity;
        var intraComplexity = averageComplexity[0] > 0 ? averageComplexity[0] : complexity * 3;
        if (intra) {
            intraComplexity = 0.5 * intraComplexity + 0.5 * complexity;
        }
        var budget = bitrate * HORIZON + (wanted - spent);
        budget = Math.clamp(budget, 0.3 * bitrate * HORIZON, 3 * bitrate * HORIZON);
        var available = Math.max(budget - 64 * frames, 1);
        var overflow = Math.clamp(1 + (spent - wanted) / (bitrate * DRIFT_SECONDS), 0.5, 2);
        if (realtime) {
            var interWeight = interFrames * StrictMath.exp(offset[1]) * interComplexity;
            var intraWeight = intraFrames * StrictMath.exp(offset[0]) * intraComplexity;
            return qp(realtimeBaseQscale(interWeight, intraWeight, available) * overflow);
        }
        var interOffset = learned[1] ? offset[1] : offset[0] - StrictMath.log(1.5);
        var intraOffset = learned[0] ? offset[0] : offset[1] + StrictMath.log(1.5);
        var weight = interFrames * StrictMath.exp(interOffset) * interComplexity
                + intraFrames * StrictMath.exp(intraOffset) * intraComplexity * StrictMath.pow(qscale(12 + idrOffset) / 0.85, GAMMA);
        return qp(StrictMath.pow(weight / available, 1 / GAMMA) * overflow);
    }

    /**
     * Returns the P frame quantizer step at which the horizon's predicted size meets its budget
     * in real-time mode, where the IDR and P size models have different exponents.
     *
     * <p>The horizon's size {@code interWeight * s^-GAMMA + intraWeight * (s / 2^(IDR_OFFSET / 6))
     * ^-REALTIME_INTRA_GAMMA} decreases with the step {@code s}, so the step is found by bisection
     * on its logarithm between the steps of quantizers {@code 0} and {@code 51}.
     *
     * @param interWeight the P frames' size at a step of one
     * @param intraWeight the IDR frames' size at a step of one
     * @param available the bits the horizon may use
     * @return the base quantizer step
     */
    private static double realtimeBaseQscale(double interWeight, double intraWeight, double available) {
        var intraScale = StrictMath.pow(2, -IDR_OFFSET / 6.0);
        var low = StrictMath.log(qscale(0));
        var high = StrictMath.log(qscale(QP_MAX));
        for (var iteration = 0; iteration < 40; iteration++) {
            var middle = 0.5 * (low + high);
            var step = StrictMath.exp(middle);
            var bits = interWeight * StrictMath.pow(step, -GAMMA)
                    + intraWeight * StrictMath.pow(step * intraScale, -REALTIME_INTRA_GAMMA);
            if (bits > available) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return StrictMath.exp(0.5 * (low + high));
    }

    /**
     * Returns the quantizer to code a frame again with when its size is far from what rate control
     * planned, or the given quantizer when the size is acceptable.
     *
     * <p>The first frame is sized with a generic model, which can be off by several times on
     * unusual content such as heavy noise: when it misses its target by more than a factor of
     * {@link #FIRST_FRAME_TOLERANCE} either way, the model is recalibrated from the coded size and
     * a corrected quantizer is returned. The downward correction is bounded by
     * {@link #FIRST_FRAME_MAX_DECREASE} steps so that content which codes to almost nothing at any
     * quantizer does not collapse to the finest one. A later IDR frame is only corrected upwards,
     * when it exceeds both its predicted size and the frame budget by {@link #OVERSHOOT_FACTOR};
     * it is then aimed at its predicted size, or three frame budgets when that is larger. Later P
     * frames are left to the buffer verifier, and constant
     * quality, real-time mode and frames planned by a two-pass encode never correct.
     *
     * @param intra whether the frame is an IDR picture
     * @param qp the quantizer the frame was coded with
     * @param bits the coded size in bits
     * @param complexity the frame's complexity estimate
     * @return the quantizer to code the frame with
     */
    int correctSize(boolean intra, int qp, long bits, long complexity) {
        if (mode == H264RateControl.CONSTANT_QUALITY || planned) {
            return qp;
        }
        if (realtime) {
            firstTarget = -1;
            return qp;
        }
        if (intra && (learned[0] || learned[1])) {
            var target = introBudgets() * frameBudget;
            if (bits <= INTRA_OVERSHOOT * target || qp >= maxQp) {
                return qp;
            }
            var step = (int) Math.ceil(6 * StrictMath.log(bits / target) / StrictMath.log(2) / GAMMA);
            return Math.min(maxQp, qp + Math.max(1, step));
        }
        if (!intra && (learned[0] || learned[1])) {
            var limit = P_OVERSHOOT * Math.max(plannedBits, frameBudget);
            if (bits <= limit || qp >= maxQp) {
                return qp;
            }
            var target = Math.max(plannedBits, P_RECODE_TARGET * frameBudget);
            var step = (int) Math.ceil(6 * StrictMath.log(bits / target) / StrictMath.log(2) / GAMMA);
            return Math.min(maxQp, qp + Math.max(1, step));
        }
        if (firstTarget < 0 || learned[0] || learned[1]) {
            var limit = OVERSHOOT_FACTOR * Math.max(plannedBits, frameBudget);
            if (!intra || bits <= limit || qp >= maxQp) {
                return qp;
            }
            var observed = StrictMath.log(Math.max(16, bits - 64)) - StrictMath.log(Math.max(1, complexity))
                    + gamma(intra) * StrictMath.log(qscale(qp));
            var target = Math.max(plannedBits, OVERSHOOT_FACTOR * frameBudget);
            var type = intra ? 0 : 1;
            var saved = offset[type];
            offset[type] = observed;
            var corrected = solve(intra, complexity, target);
            offset[type] = saved;
            return (int) Math.round(Math.clamp(corrected, qp + 1, maxQp));
        }
        var target = firstTarget;
        firstTarget = -1;
        var ratio = bits / target;
        if (ratio <= FIRST_FRAME_TOLERANCE && ratio >= 1 / FIRST_FRAME_TOLERANCE) {
            return qp;
        }
        var type = intra ? 0 : 1;
        offset[type] = StrictMath.log(Math.max(16, bits - 64)) - StrictMath.log(Math.max(1, complexity))
                + gamma(intra) * StrictMath.log(qscale(qp));
        var corrected = Math.max(solve(intra, complexity, target), qp - FIRST_FRAME_MAX_DECREASE);
        return (int) Math.round(Math.clamp(corrected, floorQp(), maxQp));
    }

    /**
     * Sets the bits the buffer holds for the next frame, those of the hypothetical reference decoder the
     * stream signals, which supersede the leaky bucket's estimate.
     *
     * @param bits the most bits the next frame may take
     */
    void setFill(double bits) {
        fill = bits;
    }

    /**
     * Returns whether a coded frame overflows the buffer, meaning it must be coded again.
     *
     * @param bits the frame size in bits
     * @return whether the frame does not fit in the buffer
     */
    boolean overflows(long bits) {
        return hasBuffer() && bits > fill;
    }

    /**
     * Returns the quantizer for coding a frame again after it overflowed the buffer.
     *
     * @param qp the quantizer of the failed attempt
     * @param bits the size of the failed attempt
     * @return a larger quantizer, at most the window's upper end
     */
    int retryQp(int qp, long bits) {
        var ratio = bits / Math.max(1, 0.8 * fill);
        var step = (int) Math.ceil(6 * StrictMath.log(ratio) / StrictMath.log(2) / 1.2);
        return Math.min(maxQp, qp + Math.max(2, step));
    }

    /**
     * Records a coded frame.
     *
     * @param intra whether the frame is an IDR frame
     * @param qp the frame's quantizer
     * @param bits the frame size in bits
     * @param complexity the frame's complexity estimate
     * @param duration the frame's duration in seconds
     */
    void update(boolean intra, int qp, long bits, long complexity, double duration) {
        var type = intra ? 0 : 1;
        var observed = StrictMath.log(Math.max(16, bits - 64)) - StrictMath.log(Math.max(1, complexity))
                + gamma(intra) * StrictMath.log(qscale(qp));
        if (!intra && realtime && referenceInter && learned[1]) {
            var difference = excess(referenceQp - qp);
            var residual = observed - offset[1];
            if (difference > 0) {
                referenceUpSum = REFERENCE_SLOPE_MEMORY * referenceUpSum + residual * difference;
                referenceUpWeight = REFERENCE_SLOPE_MEMORY * referenceUpWeight + difference * difference;
                referenceUp = Math.clamp(referenceUpSum / referenceUpWeight, 0, MAX_REFERENCE_UP);
            } else if (difference < 0) {
                referenceDownSum = REFERENCE_SLOPE_MEMORY * referenceDownSum + residual * difference;
                referenceDownWeight = REFERENCE_SLOPE_MEMORY * referenceDownWeight + difference * difference;
                referenceDown = Math.clamp(referenceDownSum / referenceDownWeight, 0, MAX_REFERENCE_DOWN);
            }
            observed -= referenceTerm(qp);
        }
        referenceQp = qp;
        referenceInter = !intra;
        if (!learned[type]) {
            offset[type] = observed;
            learned[type] = true;
        } else {
            var memory = intra ? INTRA_MODEL_MEMORY : StrictMath.pow(INTER_MODEL_MEMORY, anchorSpacing);
            var keep = Math.min(memory, updates[type] / (updates[type] + 1.0));
            offset[type] = keep * offset[type] + (1 - keep) * observed;
        }
        updates[type]++;
        averageComplexity[type] = averageComplexity[type] == 0 ? complexity
                : (intra ? 0.5 : 0.85) * averageComplexity[type] + (intra ? 0.5 : 0.15) * complexity;
        if (intra) {
            lastIQp = qp;
        } else {
            lastPQp = qp;
            averagePQp = averagePQp < 0 ? qp : 0.8 * averagePQp + 0.2 * qp;
        }
        wanted += bitrate * duration;
        spent += bits;
        if (hasBuffer()) {
            fill = Math.max(0, fill - bits);
            fill = Math.min(bufferSize, fill + maxBitrate * duration);
        }
    }

    /**
     * Returns the quantizer of a B picture, which the encoder derives from its anchor's: in the second pass of
     * a two-pass encode the plan's quantizer for a frame the first pass measured, inside the quantizer window,
     * and the derived quantizer otherwise.
     *
     * @param frame the frame's display index
     * @param derived the quantizer derived from the anchor's
     * @return the quantizer
     */
    int bQp(long frame, int derived) {
        if (plan == null || !plan.covers(frame)) {
            return derived;
        }
        return (int) Math.round(Math.clamp(plan.quantizer(frame, false), floorQp(), maxQp));
    }

    /**
     * Records the final size of a coded frame for the plan of a two-pass encode; without a plan the call does
     * nothing.
     *
     * @param frame the frame's display index
     * @param qp the frame's quantizer, without the offset of its temporal layer
     * @param bits the frame size in bits
     */
    void coded(long frame, int qp, long bits) {
        if (plan != null) {
            plan.coded(frame, qp, bits);
        }
    }

    /**
     * Sets the number of pictures between the P pictures rate control sees, which scales how far
     * the quantizer may move from one P picture to the next.
     *
     * @param spacing the picture distance between anchors, at least 1
     */
    void setAnchorSpacing(int spacing) {
        this.anchorSpacing = spacing;
    }
}
