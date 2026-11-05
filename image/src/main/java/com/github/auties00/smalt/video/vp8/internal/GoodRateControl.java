/* Ports libvpx 1.15.1 (vp8/encoder/firstpass.c: second pass; vp8/encoder/ratectrl.c; vp8/encoder/onyx_if.c:
   encode_frame_to_data_rate, update_golden_frame_stats, update_alt_ref_frame_stats). Copyright (c) 2010 The WebM
   project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.CODED_ERROR;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.COUNT;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.DURATION;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.FIELDS;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.INTRA_ERROR;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.MVC;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.MVC_ABS;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.MVR;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.MVR_ABS;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.MV_IN_OUT_COUNT;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.NEW_MV_COUNT;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.PCNT_INTER;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.PCNT_MOTION;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.PCNT_NEUTRAL;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.PCNT_SECOND_REF;
import static com.github.auties00.smalt.video.vp8.internal.FirstPassAnalyzer.SSIM_WEIGHTED_PRED_ERR;

/**
 * Plans and regulates the bits of a good-quality VP8 stream from first-pass statistics: places
 * key frames, sizes the golden frame groups and decides their alternate references, gives each
 * key frame group, golden frame group and frame its share of the bitrate, and picks each frame's
 * quantizer and the range a recode may move it in.
 *
 * <p>With the statistics of the whole clip (two-pass), the bits are spread over the clip as the
 * frames' complexity asks. With the statistics of the lookahead only (one-pass), each golden
 * frame group gets the bits of its frames at the average rate, weighted by their complexity, and
 * the bits spent above or below the target are corrected over the frames that follow. At a
 * constant quality the groups and boosts are planned the same way and the quantizers follow
 * from the quality level. Instances are used by one thread at a time.
 *
 * @implNote This implementation ports libvpx's VP8 second pass ({@code vp8_init_second_pass},
 * {@code vp8_second_pass}, {@code find_next_key_frame}, {@code define_gf_group},
 * {@code assign_std_frame_bits}, {@code estimate_max_q}, {@code estimate_cq}) and the frame rate
 * control of {@code encode_frame_to_data_rate} ({@code vp8_regulate_q},
 * {@code vp8_update_rate_correction_factors}, {@code vp8_compute_frame_size_bounds}, the recode
 * loop with its zero-bin over-quantization and its forced key frame test). libvpx codes
 * alternate references only in its second pass and has no constant quality: the one-pass mode,
 * which runs the same planning on the lookahead's statistics, and the constant quality mode,
 * which takes the boosted frames' quantizers from the quality level as the two-pass active best
 * quality does, are Smalt's.
 */
final class GoodRateControl {
    /**
     * The variable bitrate mode.
     */
    static final int MODE_VBR = 0;

    /**
     * The constrained quality mode: a variable bitrate whose quantizer never goes below the
     * quality level.
     */
    static final int MODE_CQ = 1;

    /**
     * The constant quality mode.
     */
    static final int MODE_Q = 2;

    /**
     * The largest quantizer index, libvpx's {@code MAXQ}.
     */
    private static final int MAXQ = 127;

    /**
     * The largest zero-bin over-quantization, in 1/128 of the AC step, libvpx's
     * {@code ZBIN_OQ_MAX}.
     */
    private static final int ZBIN_OQ_MAX = 192;

    /**
     * The largest zero-bin over-quantization of golden and alternate references.
     */
    private static final int GF_ZBIN_OQ_MAX = 16;

    /**
     * The growth of the size factor of each step of zero-bin over-quantization, libvpx's
     * {@code factor_adjustment}.
     */
    private static final double ZBIN_FACTOR_STEP = 0.01 / 256.0;

    /**
     * The scale of the bits per macroblock model, libvpx's {@code BPER_MB_NORMBITS}.
     */
    private static final int BPER_MB_NORMBITS = 9;

    /**
     * The smallest golden frame group, libvpx's {@code MIN_GF_INTERVAL}.
     */
    private static final int MIN_GF_INTERVAL = 4;

    /**
     * The golden frame interval before the first plan, libvpx's {@code DEFAULT_GF_INTERVAL}.
     */
    private static final int DEFAULT_GF_INTERVAL = 7;

    /**
     * The intra-to-inter ratio factor of the golden frame boost, libvpx's {@code IIFACTOR}.
     */
    private static final double IIFACTOR = 1.5;

    /**
     * The intra-to-inter ratio factor of the key frame candidate test, libvpx's
     * {@code IIKFACTOR1}.
     */
    private static final double IIKFACTOR1 = 1.40;

    /**
     * The intra-to-inter ratio factor of the key frame boost, libvpx's {@code IIKFACTOR2}.
     */
    private static final double IIKFACTOR2 = 1.5;

    /**
     * The largest per-frame key frame boost, libvpx's {@code RMAX}.
     */
    private static final double RMAX = 14.0;

    /**
     * The largest per-frame golden frame boost, libvpx's {@code GF_RMAX}.
     */
    private static final double GF_RMAX = 48.0;

    /**
     * The smallest intra error per macroblock the key frame boost assumes, libvpx's
     * {@code KF_MB_INTRA_MIN}.
     */
    private static final int KF_MB_INTRA_MIN = 300;

    /**
     * The smallest intra error per macroblock the golden frame boost assumes, libvpx's
     * {@code GF_MB_INTRA_MIN}.
     */
    private static final int GF_MB_INTRA_MIN = 200;

    /**
     * The smallest rate correction factor, libvpx's {@code MIN_BPB_FACTOR}.
     */
    private static final double MIN_BPB_FACTOR = 0.01;

    /**
     * The largest rate correction factor, libvpx's {@code MAX_BPB_FACTOR}.
     */
    private static final double MAX_BPB_FACTOR = 50;

    /**
     * The bias of the bits towards complex frames, in percent, libvpx's default
     * {@code rc_2pass_vbr_bias_pct}.
     */
    private static final int VBR_BIAS = 50;

    /**
     * The smallest share of the average frame bits a frame keeps, in percent, libvpx's default
     * {@code rc_2pass_vbr_minsection_pct}.
     */
    private static final int VBR_MIN_SECTION = 0;

    /**
     * The largest share of the average frame bits a frame takes, in percent, libvpx's default
     * {@code rc_2pass_vbr_maxsection_pct}.
     */
    private static final int VBR_MAX_SECTION = 400;

    /**
     * The most a target moves to make up the bits spent off target in the one-pass mode, in
     * percent.
     */
    private static final int VBR_PCT_ADJUSTMENT_LIMIT = 50;

    /**
     * The key frame interval that stands for no periodic key frames.
     */
    private static final int NO_KEY_INTERVAL = 1 << 20;

    /**
     * The number of macroblocks.
     */
    private final int mbs;

    /**
     * The picture area in samples, rounded to whole macroblocks.
     */
    private final int area;

    /**
     * The rate control mode.
     */
    private int mode;

    /**
     * The target bitrate in bits per second.
     */
    private long targetBandwidth;

    /**
     * The frame rate.
     */
    private double framerate = 30;

    /**
     * The smallest quantizer index.
     */
    private int bestQuality;

    /**
     * The largest quantizer index.
     */
    private int worstQuality = MAXQ;

    /**
     * The quantizer index of the quality level.
     */
    private int cqLevel;

    /**
     * The quality level the constrained quality mode keeps to, at least {@link #cqLevel}
     * ({@code cq_target_quality}).
     */
    private int cqTargetQuality;

    /**
     * The largest key frame interval.
     */
    private int keyFrameFrequency = NO_KEY_INTERVAL;

    /**
     * Whether alternate references are coded.
     */
    private boolean playAlternate;

    /**
     * The lookahead depth.
     */
    private int lagInFrames;

    /**
     * The factor of the bits a macroblock is expected to cost at the coding speed, against the
     * best quality's search: 1 at the best quality, 1.04 plus 0.04 per good-quality speed
     * (libvpx's {@code speed_correction}).
     */
    private double speedCorrection = 1.04;

    /**
     * The most pictures the temporal filter of an alternate reference averages
     * ({@code arnr_max_frames}).
     */
    private int arnrMaxFrames;

    /**
     * The intra-to-inter error ratio of the section, truncated ({@code section_intra_rating}).
     */
    private int sectionIntraRating;

    /**
     * The recode loop of the speed: 0 none, 1 every frame, 2 key frames, golden frames and
     * alternate references.
     */
    private int recodeLoop;

    /**
     * The average bits of a frame.
     */
    private int avPerFrameBandwidth;

    /**
     * The bits planned for the current frame.
     */
    private int perFrameBandwidth;

    /**
     * The smallest bits of a frame.
     */
    private int minFrameBandwidth;

    /**
     * The largest golden frame group.
     */
    private int maxGfInterval;

    /**
     * The largest golden frame group of a static scene.
     */
    private int staticSceneMaxGfInterval;

    /**
     * The first-pass records, {@link FirstPassAnalyzer#FIELDS} values per frame.
     */
    private double[] stats = new double[64 * FIELDS];

    /**
     * The number of records.
     */
    private int statsCount;

    /**
     * Whether no record follows the last one.
     */
    private boolean complete;

    /**
     * Whether the records are those of the lookahead only.
     */
    private boolean streaming = true;

    /**
     * The sum of every record.
     */
    private final double[] totalStats = new double[FIELDS];

    /**
     * The sum of the records of the frames not yet coded.
     */
    private final double[] totalLeftStats = new double[FIELDS];

    /**
     * A scratch sum of records.
     */
    private final double[] sectionStats = new double[FIELDS];

    /**
     * The bits left for the rest of the clip.
     */
    private long bitsLeft;

    /**
     * The sum of the modified errors of the clip.
     */
    private double modifiedErrorTotal;

    /**
     * The sum of the modified errors of the frames not yet planned.
     */
    private double modifiedErrorLeft;

    /**
     * The smallest intra error of the key frame boost.
     */
    private double kfIntraErrMin;

    /**
     * The smallest intra error of the golden frame boost.
     */
    private double gfIntraErrMin;

    /**
     * The number of frames to the next key frame.
     */
    private int framesToKey;

    /**
     * The bits of the current key frame group.
     */
    private long kfGroupBits;

    /**
     * The modified error of the frames of the key frame group not yet planned.
     */
    private double kfGroupErrorLeft;

    /**
     * The bits of the current golden frame group.
     */
    private long gfGroupBits;

    /**
     * The modified error of the frames of the golden frame group not yet planned.
     */
    private double gfGroupErrorLeft;

    /**
     * The bits of the golden frame or alternate reference of the group.
     */
    private int gfBits;

    /**
     * The extra bits of every other frame of a boosted group.
     */
    private int altExtraBits;

    /**
     * The quantizer correction of the section, from the intra-to-inter ratio.
     */
    private double sectionMaxQFactor = 1.0;

    /**
     * The learned correction of the worst quantizer estimate.
     */
    private double estMaxQCorrectionFactor = 1.0;

    /**
     * The largest worst quantizer the estimate may return.
     */
    private int maxqMaxLimit = MAXQ;

    /**
     * The smallest worst quantizer the estimate may return.
     */
    private int maxqMinLimit;

    /**
     * The intra-to-inter ratio of the next frame.
     */
    private int nextIIRatio;

    /**
     * The frames until the next golden frame.
     */
    private int framesTillGfUpdateDue;

    /**
     * The golden frame interval of the group.
     */
    private int baselineGfInterval = DEFAULT_GF_INTERVAL;

    /**
     * Whether the next coding step is an alternate reference.
     */
    private boolean sourceAltRefPending;

    /**
     * Whether the frames being coded predict from an alternate reference.
     */
    private boolean sourceAltRefActive;

    /**
     * The frames coded since the last golden frame.
     */
    private int framesSinceGolden;

    /**
     * The frames coded since the last key frame.
     */
    private int framesSinceKey;

    /**
     * The boost of the group's golden frame or alternate reference.
     */
    private int gfuBoost;

    /**
     * The number of pictures the temporal filter of the next alternate reference averages.
     */
    private int activeArnrFrames;

    /**
     * Whether the current key frame was forced by the interval.
     */
    private boolean thisKeyFrameForced;

    /**
     * Whether the next key frame is forced by the interval.
     */
    private boolean nextKeyFrameForced;

    /**
     * The worst quantizer index the current frame may use.
     */
    private int activeWorstQuality;

    /**
     * The best quantizer index the current frame may use.
     */
    private int activeBestQuality;

    /**
     * The average quantizer index of recent inter frames.
     */
    private int avgFrameQindex;

    /**
     * The number of ordinary inter frames coded.
     */
    private int niFrames;

    /**
     * The sum of the quantizer indices of the ordinary inter frames.
     */
    private long niTotQi;

    /**
     * The average quantizer index of the ordinary inter frames.
     */
    private int niAvQi;

    /**
     * The quantizer index of the last key frame and of the last inter frame.
     */
    private final int[] lastQ = new int[2];

    /**
     * The rate correction factor of inter frames.
     */
    private double rateCorrectionFactor = 1.0;

    /**
     * The rate correction factor of key frames.
     */
    private double keyFrameRateCorrectionFactor = 1.0;

    /**
     * The rate correction factor of golden frames and alternate references.
     */
    private double gfRateCorrectionFactor = 1.0;

    /**
     * The recent average of the frame targets.
     */
    private int rollingTargetBits;

    /**
     * The recent average of the frame sizes.
     */
    private int rollingActualBits;

    /**
     * The long-term average of the frame targets.
     */
    private int longRollingTargetBits;

    /**
     * The long-term average of the frame sizes.
     */
    private int longRollingActualBits;

    /**
     * The bits spent below the plan so far in the one-pass mode, negative after an overshoot: the
     * planned targets of the key frames and ordinary frames less their sizes, less the bits each
     * key frame was planned above its share of the bitrate. Golden and alternate reference frames
     * return their deviation to their group instead.
     */
    private long bitsOffTarget;

    /**
     * The target of the current step before the one-pass correction.
     */
    private int plannedTarget;

    /**
     * The target of the current frame.
     */
    private int thisFrameTarget;

    /**
     * The target of the last ordinary inter frame.
     */
    private int interFrameTarget;

    /**
     * The number of shown frames coded.
     */
    private int currentVideoFrame;

    /**
     * The mean modified error of the known frames in the one-pass mode.
     */
    private double meanModifiedError = 1.0;

    /**
     * Whether the current step is a key frame.
     */
    private boolean keyFrame;

    /**
     * Whether the current step refreshes the last reference.
     */
    private boolean refreshLast;

    /**
     * Whether the current step refreshes the golden reference.
     */
    private boolean refreshGolden;

    /**
     * Whether the current step refreshes the alternate reference.
     */
    private boolean refreshAlt;

    /**
     * Whether the current step codes the source of the active alternate reference
     * ({@code is_src_frame_alt_ref}).
     */
    private boolean srcFrameAltRef;

    /**
     * The display index of the alternate reference's source.
     */
    private int altRefSourceIdx = -1;

    /**
     * The smallest quantizer of the recode loop.
     */
    private int qLow;

    /**
     * The largest quantizer of the recode loop.
     */
    private int qHigh;

    /**
     * The smallest acceptable size of the current frame, in bits.
     */
    private int underShootLimit;

    /**
     * The largest acceptable size of the current frame, in bits.
     */
    private int overShootLimit;

    /**
     * The zero-bin over-quantization of the current coding, libvpx's {@code zbin_over_quant}.
     */
    private int zbinOverQuant;

    /**
     * The smallest zero-bin over-quantization of the recode loop.
     */
    private int zbinOqLow;

    /**
     * The largest zero-bin over-quantization of the recode loop.
     */
    private int zbinOqHigh;

    /**
     * The luma squared error of the reconstruction of the frame before a key frame the interval
     * forces, libvpx's {@code ambient_err}.
     */
    private long ambientError;

    /**
     * Whether a coding of the current frame overshot.
     */
    private boolean overshootSeen;

    /**
     * Whether a coding of the current frame undershot.
     */
    private boolean undershootSeen;

    /**
     * The size of the last coding of the current frame, in bits.
     */
    private int projectedFrameSize;

    /**
     * The quantizer of the last coding of the current frame.
     */
    private int lastCodedQ;

    /**
     * Creates the rate control of a picture size.
     *
     * @param mbs the number of macroblocks
     * @param area the picture area in samples, rounded to whole macroblocks
     */
    GoodRateControl(int mbs, int area) {
        this.mbs = mbs;
        this.area = area;
    }

    /**
     * Configures the rate control before the first frame.
     *
     * @param mode {@link #MODE_VBR}, {@link #MODE_CQ} or {@link #MODE_Q}
     * @param bitrate the target bitrate in bits per second
     * @param frameRate the frame rate
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param cqQindex the quantizer index of the quality level
     * @param keyInterval the largest key frame interval, 0 for none
     * @param lookahead the lookahead depth
     * @param altRef whether alternate references are coded
     * @param filterFrames the most pictures the temporal filter averages
     * @param speed the good-quality speed, from 0 to 4
     * @param best whether the frames are coded at the best quality, which recodes every frame and
     * expects the bits of its fuller search
     */
    void configure(int mode, long bitrate, double frameRate, int minQindex, int maxQindex, int cqQindex,
                   int keyInterval, int lookahead, boolean altRef, int filterFrames, int speed, boolean best) {
        this.mode = mode;
        this.targetBandwidth = bitrate;
        this.bestQuality = minQindex;
        this.worstQuality = maxQindex;
        this.cqLevel = Math.clamp(cqQindex, minQindex, maxQindex);
        this.cqTargetQuality = cqLevel;
        this.keyFrameFrequency = keyInterval > 0 ? keyInterval : NO_KEY_INTERVAL;
        this.lagInFrames = lookahead;
        this.playAlternate = altRef && lookahead > 0;
        this.arnrMaxFrames = filterFrames;
        this.recodeLoop = best ? 1 : speed > 3 ? 0 : speed > 2 ? 2 : 1;
        this.speedCorrection = best ? 1.0 : speed <= 5 ? 1.04 + speed * 0.04 : 1.25;
        this.activeWorstQuality = worstQuality;
        this.activeBestQuality = bestQuality;
        this.avgFrameQindex = worstQuality;
        this.niAvQi = worstQuality;
        this.maxqMaxLimit = worstQuality;
        this.maxqMinLimit = bestQuality;
        newFramerate(frameRate);
    }

    /**
     * Changes the bitrate, quantizer window and quality level of a running stream.
     *
     * @param bitrate the target bitrate in bits per second
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param cqQindex the quantizer index of the quality level
     */
    void retarget(long bitrate, int minQindex, int maxQindex, int cqQindex) {
        var bitsBefore = (double) targetBandwidth;
        targetBandwidth = bitrate;
        bestQuality = minQindex;
        worstQuality = maxQindex;
        cqLevel = Math.clamp(cqQindex, minQindex, maxQindex);
        cqTargetQuality = Math.max(cqTargetQuality, cqLevel);
        activeWorstQuality = Math.clamp(activeWorstQuality, bestQuality, worstQuality);
        activeBestQuality = Math.clamp(activeBestQuality, bestQuality, worstQuality);
        maxqMaxLimit = Math.clamp(maxqMaxLimit, bestQuality, worstQuality);
        maxqMinLimit = Math.clamp(maxqMinLimit, bestQuality, worstQuality);
        if (!streaming && bitsBefore > 0) {
            bitsLeft = (long) (bitsLeft * (bitrate / bitsBefore));
            kfGroupBits = (long) (kfGroupBits * (bitrate / bitsBefore));
            gfGroupBits = (long) (gfGroupBits * (bitrate / bitsBefore));
        }
        newFramerate(framerate);
    }

    /**
     * Sets the frame rate and the per-frame bandwidths that follow from it
     * ({@code vp8_new_framerate}).
     *
     * @param frameRate the frame rate
     */
    private void newFramerate(double frameRate) {
        framerate = frameRate < 0.1 ? 30 : frameRate;
        perFrameBandwidth = (int) Math.min(Integer.MAX_VALUE, Math.round(targetBandwidth / framerate));
        avPerFrameBandwidth = perFrameBandwidth;
        minFrameBandwidth = (int) Math.min((long) avPerFrameBandwidth * VBR_MIN_SECTION / 100, Integer.MAX_VALUE);
        maxGfInterval = Math.max(12, (int) (framerate / 2.0) + 2);
        staticSceneMaxGfInterval = keyFrameFrequency >> 1;
        if (playAlternate) {
            maxGfInterval = Math.min(maxGfInterval, lagInFrames - 1);
            staticSceneMaxGfInterval = Math.min(staticSceneMaxGfInterval, lagInFrames - 1);
        }
        maxGfInterval = Math.min(maxGfInterval, staticSceneMaxGfInterval);
    }

    /**
     * Installs the records of the whole clip, for a two-pass encode ({@code vp8_init_second_pass}).
     *
     * @param records the records, {@link FirstPassAnalyzer#FIELDS} values per frame
     * @param frames the number of frames
     */
    void setClipStats(double[] records, int frames) {
        stats = java.util.Arrays.copyOf(records, Math.max(records.length, FIELDS));
        statsCount = frames;
        complete = true;
        streaming = false;
        java.util.Arrays.fill(totalStats, 0);
        for (var i = 0; i < frames; i++) {
            accumulate(totalStats, i);
        }
        System.arraycopy(totalStats, 0, totalLeftStats, 0, FIELDS);
        if (totalStats[DURATION] > 0) {
            newFramerate(10_000_000.0 * totalStats[COUNT] / totalStats[DURATION]);
        }
        bitsLeft = (long) (totalStats[DURATION] * targetBandwidth / 10_000_000.0);
        kfIntraErrMin = (double) KF_MB_INTRA_MIN * mbs;
        gfIntraErrMin = (double) GF_MB_INTRA_MIN * mbs;
        modifiedErrorTotal = 0;
        for (var i = 0; i < frames; i++) {
            modifiedErrorTotal += modifiedErr(i);
        }
        modifiedErrorLeft = modifiedErrorTotal;
    }

    /**
     * Adds the record of the next lookahead frame, for a one-pass encode.
     *
     * @param record the array holding the record
     * @param off the index of its first value
     */
    void addStats(double[] record, int off) {
        if ((statsCount + 1) * FIELDS > stats.length) {
            stats = java.util.Arrays.copyOf(stats, stats.length * 2);
        }
        System.arraycopy(record, off, stats, statsCount * FIELDS, FIELDS);
        accumulate(totalStats, statsCount);
        accumulate(totalLeftStats, statsCount);
        statsCount++;
        kfIntraErrMin = (double) KF_MB_INTRA_MIN * mbs;
        gfIntraErrMin = (double) GF_MB_INTRA_MIN * mbs;
    }

    /**
     * Marks the records as complete: no frame follows the last one added.
     */
    void finishStats() {
        complete = true;
    }

    /**
     * Adds a frame's record to a sum ({@code accumulate_stats}).
     *
     * @param section the sum
     * @param frame the display index
     */
    private void accumulate(double[] section, int frame) {
        var base = frame * FIELDS;
        for (var i = 0; i < FIELDS; i++) {
            section[i] += stats[base + i];
        }
    }

    /**
     * Returns whether the records hold a frame.
     *
     * @param frame the display index
     * @return whether it is known
     */
    private boolean has(int frame) {
        return frame >= 0 && frame < statsCount;
    }

    /**
     * Returns one value of a frame's record.
     *
     * @param frame the display index
     * @param field the record field
     * @return the value
     */
    private double s(int frame, int field) {
        return stats[frame * FIELDS + field];
    }

    /**
     * Moves a value away from zero, so it can divide ({@code DOUBLE_DIVIDE_CHECK}).
     *
     * @param x the value
     * @return the value moved by a millionth away from zero
     */
    private static double divideCheck(double x) {
        return x < 0 ? x - 0.000001 : x + 0.000001;
    }

    /**
     * Returns a frame's error modified by the bias towards complex frames
     * ({@code calculate_modified_err}).
     *
     * @param frame the display index
     * @return the modified error
     */
    private double modifiedErr(int frame) {
        var avErr = totalStats[SSIM_WEIGHTED_PRED_ERR] / Math.max(totalStats[COUNT], 1e-6);
        var thisErr = s(frame, SSIM_WEIGHTED_PRED_ERR);
        return avErr * StrictMath.pow(thisErr / divideCheck(avErr), VBR_BIAS / 100.0);
    }

    /**
     * Recomputes the mean modified error of the known frames, for a one-pass encode.
     */
    private void refreshMeanModifiedError() {
        if (!streaming) {
            return;
        }
        var total = 0.0;
        for (var i = 0; i < statsCount; i++) {
            total += modifiedErr(i);
        }
        meanModifiedError = statsCount > 0 ? Math.max(total / statsCount, 1e-6) : 1.0;
    }

    /**
     * Returns the largest target of an ordinary frame ({@code frame_max_bits}).
     *
     * @return the bits
     */
    private int frameMaxBits() {
        long maxBits;
        if (streaming) {
            maxBits = (long) avPerFrameBandwidth * VBR_MAX_SECTION / 100;
        } else {
            var framesLeft = totalStats[COUNT] - currentVideoFrame;
            maxBits = (long) Math.min((bitsLeft / Math.max(framesLeft, 1e-6)) * (VBR_MAX_SECTION / 100.0),
                    Integer.MAX_VALUE);
        }
        return (int) Math.clamp(maxBits, 0, Integer.MAX_VALUE);
    }

    /**
     * Returns how much the prediction quality decays over a frame
     * ({@code get_prediction_decay_rate}).
     *
     * @param frame the display index
     * @return the decay rate
     */
    private double predictionDecayRate(int frame) {
        var motionPct = s(frame, PCNT_MOTION);
        var rate = s(frame, PCNT_INTER);
        var motionDecay = 1.0 - motionPct / 20.0;
        if (motionDecay < rate) {
            rate = motionDecay;
        }
        var rabs = Math.abs(s(frame, MVR_ABS) * motionPct);
        var cabs = Math.abs(s(frame, MVC_ABS) * motionPct);
        var distance = Math.sqrt(rabs * rabs + cabs * cabs) / 250.0;
        distance = distance > 1.0 ? 0.0 : 1.0 - distance;
        if (distance < rate) {
            rate = distance;
        }
        return rate;
    }

    /**
     * Returns whether a still section follows a transition ({@code detect_transition_to_still}).
     *
     * @param next the display index of the frame after the one examined
     * @param frameInterval the distance from the start of the group
     * @param stillInterval the number of frames that must be still
     * @param loopDecayRate the decay rate of the frame examined
     * @param decayAccumulator the accumulated decay of the group
     * @return whether they are
     */
    private boolean transitionToStill(int next, int frameInterval, int stillInterval, double loopDecayRate,
                                      double decayAccumulator) {
        if (frameInterval > MIN_GF_INTERVAL && loopDecayRate >= 0.999 && decayAccumulator < 0.9) {
            var j = 0;
            for (; j < stillInterval; j++) {
                if (!has(next + j)) {
                    break;
                }
                if (predictionDecayRate(next + j) < 0.999) {
                    break;
                }
            }
            return j == stillInterval;
        }
        return false;
    }

    /**
     * Returns whether a frame recovers from a flash: it predicts better from the second
     * reference than from the last ({@code detect_flash}).
     *
     * @param frame the display index, possibly unknown
     * @return whether it does
     */
    private boolean detectFlash(int frame) {
        return has(frame) && s(frame, PCNT_SECOND_REF) > s(frame, PCNT_INTER) && s(frame, PCNT_SECOND_REF) >= 0.5;
    }

    /**
     * The accumulated motion statistics of a boost scan: the current frame's in-out measure,
     * the signed and absolute in-out sums and the motion ratio sum.
     */
    private final double[] motion = new double[4];

    /**
     * Adds a frame's motion to the scan accumulators {@link #motion}
     * ({@code accumulate_frame_motion_stats}).
     *
     * @param frame the display index
     */
    private void accumulateMotion(int frame) {
        var motionPct = s(frame, PCNT_MOTION);
        motion[0] = s(frame, MV_IN_OUT_COUNT) * motionPct;
        motion[1] += s(frame, MV_IN_OUT_COUNT) * motionPct;
        motion[2] += Math.abs(s(frame, MV_IN_OUT_COUNT) * motionPct);
        if (motionPct > 0.05) {
            var rRatio = Math.abs(s(frame, MVR_ABS)) / divideCheck(Math.abs(s(frame, MVR)));
            var cRatio = Math.abs(s(frame, MVC_ABS)) / divideCheck(Math.abs(s(frame, MVC)));
            motion[3] += rRatio < s(frame, MVR_ABS) ? rRatio * motionPct : s(frame, MVR_ABS) * motionPct;
            motion[3] += cRatio < s(frame, MVC_ABS) ? cRatio * motionPct : s(frame, MVC_ABS) * motionPct;
        }
    }

    /**
     * Returns the boost one frame adds to a golden frame ({@code calc_frame_boost}).
     *
     * @param frame the display index
     * @param mvInOut the frame's in-out measure
     * @return the boost
     */
    private double frameBoost(int frame, double mvInOut) {
        var intra = Math.max(s(frame, INTRA_ERROR), gfIntraErrMin);
        var boost = IIFACTOR * intra / divideCheck(s(frame, CODED_ERROR));
        if (mvInOut > 0.0) {
            boost += boost * (mvInOut * 2.0);
        } else {
            boost += boost * (mvInOut / 2.0);
        }
        return Math.min(boost, GF_RMAX);
    }

    /**
     * Returns the boost of an alternate reference from the frames after and before its source
     * ({@code calc_arf_boost}).
     *
     * @param offset the display index of the frame after the source: the forward scan starts there
     * and the backward scan at the source
     * @param fFrames the frames scanned forward
     * @param bFrames the frames scanned backward
     * @param result receives the forward and backward boosts
     * @return their sum
     */
    private int arfBoost(int offset, int fFrames, int bFrames, int[] result) {
        var boostScore = 0.0;
        var decay = 1.0;
        java.util.Arrays.fill(motion, 0);
        for (var i = 0; i < fFrames; i++) {
            var frame = offset + i;
            if (!has(frame)) {
                break;
            }
            accumulateMotion(frame);
            var r = frameBoost(frame, motion[0]);
            var flash = detectFlash(frame) || detectFlash(frame + 1);
            if (!flash) {
                decay = Math.max(decay * predictionDecayRate(frame), 0.1);
            }
            boostScore += decay * r;
            if (!flash && (motion[3] > 100.0 || motion[2] > 3.0 || motion[1] < -2.0)) {
                break;
            }
        }
        result[0] = (int) (boostScore * 100.0) >> 4;
        boostScore = 0.0;
        decay = 1.0;
        java.util.Arrays.fill(motion, 0);
        for (var i = -1; i >= -bFrames; i--) {
            var frame = offset + i;
            if (!has(frame)) {
                break;
            }
            accumulateMotion(frame);
            var r = frameBoost(frame, motion[0]);
            var flash = detectFlash(frame) || detectFlash(frame + 1);
            if (!flash) {
                decay = Math.max(decay * predictionDecayRate(frame), 0.1);
            }
            boostScore += decay * r;
            if (!flash && (motion[3] > 100.0 || motion[2] > 3.0 || motion[1] < -2.0)) {
                break;
            }
        }
        result[1] = (int) (boostScore * 100.0) >> 4;
        return result[0] + result[1];
    }

    /**
     * The forward and backward boosts of the last {@link #arfBoost}.
     */
    private final int[] arfBoosts = new int[2];

    /**
     * The prediction decay of the last eight frames of the key frame scan.
     */
    private final double[] recentDecay = new double[8];

    /**
     * Plans the golden frame group starting at a frame: its length, boost and alternate
     * reference, and its bits ({@code define_gf_group}).
     *
     * @param start the display index of the group's first frame
     */
    private void defineGfGroup(int start) {
        gfGroupBits = 0;
        var maxBits = frameMaxBits();
        var allowAltRef = playAlternate;
        var modFrameErr = modifiedErr(start);
        var gfFirstFrameErr = modFrameErr;
        var gfGroupErr = 0.0;
        if (keyFrame) {
            gfGroupErr -= gfFirstFrameErr;
        }
        java.util.Arrays.fill(motion, 0);
        var boostScore = 0.0;
        var oldBoostScore = 0.0;
        var decay = 1.0;
        var loopDecayRate = 1.0;
        var current = start;
        var next = start;
        var i = 0;
        var scanned = false;
        while ((i < staticSceneMaxGfInterval || framesToKey - i < MIN_GF_INTERVAL) && i < framesToKey) {
            i++;
            modFrameErr = modifiedErr(current);
            gfGroupErr += modFrameErr;
            next = current + 1;
            if (!has(next)) {
                break;
            }
            scanned = true;
            var flash = detectFlash(next + 1);
            accumulateMotion(next);
            var r = frameBoost(next, motion[0]);
            if (!flash) {
                loopDecayRate = predictionDecayRate(next);
                decay = Math.max(decay * loopDecayRate, 0.1);
            }
            boostScore += decay * r;
            if (transitionToStill(next + 1, i, 5, loopDecayRate, decay)) {
                allowAltRef = false;
                boostScore = oldBoostScore;
                break;
            }
            if ((i >= maxGfInterval && decay < 0.995)
                    || (i > MIN_GF_INTERVAL && framesToKey - i >= MIN_GF_INTERVAL
                    && (boostScore > 20.0 || s(next, PCNT_INTER) < 0.75) && !flash
                    && (motion[3] > 100.0 || motion[2] > 3.0 || motion[1] < -2.0 || boostScore - oldBoostScore < 2.0))) {
                boostScore = oldBoostScore;
                break;
            }
            current = next;
            oldBoostScore = boostScore;
        }
        if (framesToKey - i < MIN_GF_INTERVAL) {
            while (i < framesToKey) {
                i++;
                current++;
                if (!has(current)) {
                    break;
                }
                if (i < framesToKey) {
                    modFrameErr = modifiedErr(current);
                    gfGroupErr += modFrameErr;
                }
            }
        }
        gfuBoost = (int) (boostScore * 100.0) >> 4;
        var mvInOutAccumulator = motion[1];
        var altBoost = arfBoost(start + i + 1, i - 1, i - 1, arfBoosts);
        var fBoost = arfBoosts[0];
        var bBoost = arfBoosts[1];
        var nextInter = scanned && has(next) ? s(next, PCNT_INTER) : 1.0;
        var nextSecond = scanned && has(next) ? s(next, PCNT_SECOND_REF) : 0.0;
        if (allowAltRef && i >= MIN_GF_INTERVAL && i <= framesToKey - MIN_GF_INTERVAL
                && (nextInter > 0.75 || nextSecond > 0.5)
                && (mvInOutAccumulator / i > -0.2 || mvInOutAccumulator > -2.0) && bBoost > 100 && fBoost > 100) {
            gfuBoost = altBoost;
            var q = lastQ[1];
            int groupBits;
            if (streaming) {
                groupBits = (int) Math.min(avPerFrameBandwidth * gfGroupErr / meanModifiedError, Integer.MAX_VALUE);
            } else if (kfGroupBits > 0 && kfGroupErrorLeft > 0) {
                groupBits = (int) Math.min(kfGroupBits * (gfGroupErr / kfGroupErrorLeft), Integer.MAX_VALUE);
            } else {
                groupBits = 0;
            }
            var boost = altBoost * GoodTables.GF_BOOST_QADJUSTMENT[q] / 100;
            boost += i * 50;
            if (boost > (baselineGfInterval + 1) * 200) {
                boost = (baselineGfInterval + 1) * 200;
            } else if (boost < 125) {
                boost = 125;
            }
            var allocationChunks = i * 100 + boost;
            while (boost > 1000) {
                boost /= 2;
                allocationChunks /= 2;
            }
            var arfFrameBits = (int) (boost * (groupBits / (double) allocationChunks));
            var tmpQ = mode == MODE_Q ? bestQuality : estimateQ(modFrameErr, arfFrameBits);
            if (tmpQ < worstQuality) {
                sourceAltRefPending = true;
                baselineGfInterval = i;
                var halfGfInt = baselineGfInterval >> 1;
                var arf = start + i;
                var framesAfterArf = streaming && !complete ? Integer.MAX_VALUE
                        : (int) Math.max((streaming ? statsCount : totalStats[COUNT]) - arf - 1, 0);
                var forward = Math.max(arnrMaxFrames - 1, 0) >> 1;
                forward = Math.min(Math.min(forward, framesAfterArf), halfGfInt);
                var backward = forward;
                if (backward < halfGfInt) {
                    backward += (arnrMaxFrames + 1) & 1;
                }
                activeArnrFrames = backward + 1 + forward;
            } else {
                sourceAltRefPending = false;
                baselineGfInterval = i;
            }
        } else {
            sourceAltRefPending = false;
            baselineGfInterval = i;
        }
        if (streaming) {
            gfGroupBits = (long) (avPerFrameBandwidth * gfGroupErr / meanModifiedError);
        } else {
            if (framesToKey >= (int) (totalStats[COUNT] - currentVideoFrame)) {
                kfGroupBits = Math.max(bitsLeft, 0);
            }
            if (kfGroupBits > 0 && kfGroupErrorLeft > 0) {
                gfGroupBits = (long) (kfGroupBits * (gfGroupErr / kfGroupErrorLeft));
            } else {
                gfGroupBits = 0;
            }
            gfGroupBits = Math.clamp(gfGroupBits, 0, kfGroupBits);
        }
        gfGroupBits = Math.max(gfGroupBits, 0);
        if (gfGroupBits > (long) maxBits * baselineGfInterval) {
            gfGroupBits = (long) maxBits * baselineGfInterval;
        }
        var kfLeft = streaming ? Math.max(gfGroupErr, 1e-6) : kfGroupErrorLeft;
        var kfBits = streaming ? gfGroupBits : kfGroupBits;
        for (var k = 0; k <= (sourceAltRefPending && !keyFrame ? 1 : 0); k++) {
            var q = lastQ[1];
            int boost;
            int allocationChunks;
            if (sourceAltRefPending && k == 0) {
                boost = altBoost * GoodTables.GF_BOOST_QADJUSTMENT[q] / 100;
                boost += baselineGfInterval * 50;
                if (boost > (baselineGfInterval + 1) * 200) {
                    boost = (baselineGfInterval + 1) * 200;
                } else if (boost < 125) {
                    boost = 125;
                }
                allocationChunks = (baselineGfInterval + 1) * 100 + boost;
            } else {
                boost = gfuBoost * GoodTables.GF_BOOST_QADJUSTMENT[q] / 100;
                if (boost > baselineGfInterval * 150) {
                    boost = baselineGfInterval * 150;
                } else if (boost < 125) {
                    boost = 125;
                }
                allocationChunks = baselineGfInterval * 100 + (boost - 100);
            }
            while (boost > 1000) {
                boost /= 2;
                allocationChunks /= 2;
            }
            var bits = (int) Math.clamp(boost * (gfGroupBits / (double) Math.max(allocationChunks, 1)),
                    Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (modFrameErr < gfGroupErr / baselineGfInterval) {
                var altGroupBits = kfBits * (modFrameErr * baselineGfInterval) / divideCheck(kfLeft);
                var alt = (int) (boost * (altGroupBits / Math.max(allocationChunks, 1)));
                if (bits > alt) {
                    bits = alt;
                }
            } else {
                var alt = (int) Math.clamp(kfBits * modFrameErr / Math.max(kfLeft, 1), 0, Integer.MAX_VALUE);
                if (alt > bits) {
                    bits = alt;
                }
            }
            if (bits < 0) {
                bits = 0;
            }
            bits += minFrameBandwidth;
            if (k == 0) {
                gfBits = bits;
            }
            if (k == 1 || (!sourceAltRefPending && !keyFrame)) {
                perFrameBandwidth = bits;
            }
        }
        if (!streaming) {
            kfGroupErrorLeft -= gfGroupErr;
            kfGroupBits = Math.max(kfGroupBits - gfGroupBits, 0);
        }
        if (!sourceAltRefPending && !keyFrame) {
            gfGroupErrorLeft = gfGroupErr - gfFirstFrameErr;
        } else {
            gfGroupErrorLeft = gfGroupErr;
        }
        gfGroupBits = Math.max(gfGroupBits - (gfBits - minFrameBandwidth), 0);
        if (baselineGfInterval >= 3) {
            var boost = sourceAltRefPending ? bBoost : gfuBoost;
            if (boost >= 150) {
                var pctExtra = Math.min((boost - 100) / 50, 20);
                altExtraBits = (int) (gfGroupBits * pctExtra / 100);
                gfGroupBits -= altExtraBits;
                altExtraBits /= (baselineGfInterval - 1) >> 1;
            } else {
                altExtraBits = 0;
            }
        } else {
            altExtraBits = 0;
        }
        if (!keyFrame) {
            sectionMaxQFactor = sectionQFactor(start, baselineGfInterval);
        }
    }

    /**
     * Returns the worst quantizer correction of a section from its intra-to-inter error ratio.
     *
     * @param start the display index of the section's first frame
     * @param frames the number of frames
     * @return the correction, at least 0.8
     */
    private double sectionQFactor(int start, int frames) {
        java.util.Arrays.fill(sectionStats, 0);
        var count = 0;
        for (var i = 0; i < frames && has(start + i); i++) {
            accumulate(sectionStats, start + i);
            count++;
        }
        if (count == 0) {
            return sectionMaxQFactor;
        }
        var ratio = sectionStats[INTRA_ERROR] / divideCheck(sectionStats[CODED_ERROR]);
        sectionIntraRating = (int) ratio;
        return Math.max(1.0 - (ratio - 10.0) * 0.025, 0.80);
    }

    /**
     * Gives an ordinary frame its share of the golden frame group's bits
     * ({@code assign_std_frame_bits}).
     *
     * @param frame the display index
     */
    private void assignStdFrameBits(int frame) {
        var maxBits = frameMaxBits();
        var modifiedErr = modifiedErr(frame);
        var errFraction = gfGroupErrorLeft > 0 ? modifiedErr / gfGroupErrorLeft : 0.0;
        var target = (int) Math.clamp(gfGroupBits * errFraction, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (target < 0) {
            target = 0;
        } else {
            target = Math.min(target, maxBits);
            if (target > gfGroupBits) {
                target = (int) gfGroupBits;
            }
        }
        gfGroupErrorLeft -= modifiedErr;
        gfGroupBits = Math.max(gfGroupBits - target, 0);
        target += minFrameBandwidth;
        if ((framesSinceGolden & 1) != 0 && framesTillGfUpdateDue > 0) {
            target += altExtraBits;
        }
        perFrameBandwidth = target;
    }

    /**
     * Returns whether a frame is a scene cut that should start a key frame group
     * ({@code test_candidate_kf}).
     *
     * @param frame the display index, with known frames before and after it
     * @return whether it is
     */
    private boolean candidateKf(int frame) {
        if (!has(frame - 1) || !has(frame) || !has(frame + 1)) {
            return false;
        }
        var last = frame - 1;
        var next = frame + 1;
        if (s(frame, PCNT_SECOND_REF) < 0.10 && s(next, PCNT_SECOND_REF) < 0.10
                && (s(frame, PCNT_INTER) < 0.05
                || (s(frame, PCNT_INTER) - s(frame, PCNT_NEUTRAL) < .25
                && s(frame, INTRA_ERROR) / divideCheck(s(frame, CODED_ERROR)) < 2.5
                && (Math.abs(s(last, CODED_ERROR) - s(frame, CODED_ERROR)) / divideCheck(s(frame, CODED_ERROR)) > .40
                || Math.abs(s(last, INTRA_ERROR) - s(frame, INTRA_ERROR)) / divideCheck(s(frame, INTRA_ERROR)) > .40
                || s(next, INTRA_ERROR) / divideCheck(s(next, CODED_ERROR)) > 3.5)))) {
            var boostScore = 0.0;
            var oldBoostScore = 0.0;
            var decay = 1.0;
            var i = 0;
            var f = next;
            for (; i < 16; i++) {
                var nextIiratio = IIKFACTOR1 * s(f, INTRA_ERROR) / divideCheck(s(f, CODED_ERROR));
                if (nextIiratio > RMAX) {
                    nextIiratio = RMAX;
                }
                if (s(f, PCNT_INTER) > 0.85) {
                    decay *= s(f, PCNT_INTER);
                } else {
                    decay *= (0.85 + s(f, PCNT_INTER)) / 2.0;
                }
                boostScore += decay * nextIiratio;
                if (s(f, PCNT_INTER) < 0.05 || nextIiratio < 1.5
                        || (s(f, PCNT_INTER) - s(f, PCNT_NEUTRAL) < 0.20 && nextIiratio < 3.0)
                        || boostScore - oldBoostScore < 0.5 || s(f, INTRA_ERROR) < 200) {
                    break;
                }
                oldBoostScore = boostScore;
                if (!has(f + 1)) {
                    break;
                }
                f++;
            }
            return boostScore > 5.0 && i > 3;
        }
        return false;
    }

    /**
     * Places the next key frame and plans the key frame group starting at a frame
     * ({@code find_next_key_frame}).
     *
     * <p>A group with no natural key frame within the key frame interval ends at the interval, so
     * periodic key frames fall exactly every interval frames; libvpx instead halves such a group
     * to center the extra key frame.
     *
     * @param kf the display index of the key frame
     */
    private void findNextKeyFrame(int kf) {
        keyFrame = true;
        thisKeyFrameForced = nextKeyFrameForced;
        sourceAltRefActive = false;
        framesTillGfUpdateDue = 0;
        framesToKey = 1;
        kfGroupBits = 0;
        kfGroupErrorLeft = 0;
        refreshMeanModifiedError();
        var kfModErr = modifiedErr(kf);
        var kfGroupErr = 0.0;
        java.util.Arrays.fill(recentDecay, 1.0);
        var i = 0;
        var frame = kf;
        var found = false;
        while (has(frame + 1)) {
            kfGroupErr += modifiedErr(frame);
            var last = frame;
            frame++;
            if (has(frame + 1)) {
                if (i >= MIN_GF_INTERVAL && candidateKf(frame)) {
                    found = true;
                    break;
                }
                var loopDecayRate = predictionDecayRate(frame + 1);
                recentDecay[i % 8] = loopDecayRate;
                var decay = 1.0;
                for (var j = 0; j < 8; j++) {
                    decay *= recentDecay[j];
                }
                if (transitionToStill(frame + 1, i, keyFrameFrequency - i, loopDecayRate, decay)) {
                    found = true;
                    break;
                }
                framesToKey++;
                if (framesToKey >= 2 * keyFrameFrequency) {
                    found = true;
                    break;
                }
            } else {
                framesToKey++;
            }
            i++;
        }
        var atEnd = !found && !has(frame + 1);
        var open = streaming && atEnd && !complete;
        if (open) {
            framesToKey = keyFrameFrequency;
            nextKeyFrameForced = keyFrameFrequency < NO_KEY_INTERVAL;
        } else if (framesToKey > keyFrameFrequency) {
            framesToKey = keyFrameFrequency;
            kfGroupErr = 0;
            for (var k = 0; k < framesToKey && has(kf + k); k++) {
                kfGroupErr += modifiedErr(kf + k);
            }
            nextKeyFrameForced = true;
        } else {
            nextKeyFrameForced = false;
        }
        if (atEnd && !open && has(frame)) {
            kfGroupErr += modifiedErr(frame);
        }
        var known = 0;
        for (var k = 0; k < framesToKey && has(kf + k); k++) {
            known++;
        }
        if (streaming) {
            kfGroupBits = (long) (avPerFrameBandwidth * kfGroupErr / meanModifiedError);
            kfGroupBits = Math.min(kfGroupBits, (long) frameMaxBits() * Math.max(known, 1));
        } else if (bitsLeft > 0 && modifiedErrorLeft > 0.0) {
            var maxBits = frameMaxBits();
            kfGroupBits = (long) (bitsLeft * (kfGroupErr / modifiedErrorLeft));
            kfGroupBits = Math.min(kfGroupBits, (long) maxBits * framesToKey);
        } else {
            kfGroupBits = 0;
        }
        var decay = 1.0;
        var boostScore = 0.0;
        var oldBoostScore = 0.0;
        for (var k = 0; k < framesToKey; k++) {
            var f = kf + 1 + k;
            if (!has(f)) {
                break;
            }
            var intra = Math.max(s(f, INTRA_ERROR), kfIntraErrMin);
            var r = Math.min(IIKFACTOR2 * intra / divideCheck(s(f, CODED_ERROR)), RMAX);
            decay = Math.max(decay * predictionDecayRate(f), 0.1);
            boostScore += decay * r;
            if (k > MIN_GF_INTERVAL && boostScore - oldBoostScore < 1.0) {
                break;
            }
            oldBoostScore = boostScore;
        }
        sectionMaxQFactor = sectionQFactor(kf, framesToKey);
        var kfBoost = (int) boostScore;
        var counter = streaming ? Math.min(framesToKey, Math.max(known, 1)) : framesToKey;
        if (kfBoost < 48) {
            kfBoost = Math.min(kfBoost + ((counter + 1) >> 1), 48);
        }
        if (area > 320 * 240) {
            kfBoost += 2 * area / (320 * 240);
        } else if (area < 320 * 240) {
            kfBoost -= 4 * (320 * 240) / area;
        }
        kfBoost = (int) (kfBoost * 100.0) >> 4;
        if (kfBoost < 250) {
            kfBoost = 250;
        }
        int allocationChunks;
        if (decay >= 0.99) {
            allocationChunks = (counter - 1) * 10 + kfBoost;
        } else {
            allocationChunks = (counter - 1) * 100 + kfBoost;
        }
        while (kfBoost > 1000) {
            kfBoost /= 2;
            allocationChunks /= 2;
        }
        kfGroupBits = Math.max(kfGroupBits, 0);
        var kfBits = (int) Math.min(kfBoost * ((double) kfGroupBits / Math.max(allocationChunks, 1)), Integer.MAX_VALUE);
        var errLeft = streaming ? meanModifiedError * Math.max(known, 1) : modifiedErrorLeft;
        var bitsAvailable = streaming ? (double) avPerFrameBandwidth * Math.max(known, 1) : (double) bitsLeft;
        if (kfModErr < kfGroupErr / framesToKey) {
            var altGroupBits = bitsAvailable * (kfModErr * framesToKey) / divideCheck(errLeft);
            var alt = (int) Math.min(kfBoost * (altGroupBits / Math.max(allocationChunks, 1)), Integer.MAX_VALUE);
            if (kfBits > alt) {
                kfBits = alt;
            }
        } else {
            var alt = (int) Math.min(bitsAvailable * (kfModErr / divideCheck(errLeft)), Integer.MAX_VALUE);
            if (alt > kfBits) {
                kfBits = alt;
            }
        }
        kfGroupBits -= kfBits;
        if (streaming) {
            bitsOffTarget -= kfBits - (long) (avPerFrameBandwidth * kfModErr / meanModifiedError);
        }
        kfBits += minFrameBandwidth;
        perFrameBandwidth = kfBits;
        kfGroupErrorLeft = kfGroupErr - kfModErr;
        modifiedErrorLeft -= kfGroupErr;
    }

    /**
     * Returns the quantizer an alternate reference of a size would need ({@code estimate_q}).
     *
     * @param sectionErr the alternate reference's error
     * @param target the bits
     * @return the quantizer index
     */
    private int estimateQ(double sectionErr, int target) {
        var errPerMb = sectionErr / mbs;
        var targetNorm = target < (1 << 20) ? (512 * target) / mbs : 512 * (target / mbs);
        var q = 0;
        for (; q < MAXQ; q++) {
            var err = correctionFactor(errPerMb, 150.0, 0.40, 0.90, q);
            var bitsPerMb = (int) (.5 + err * speedCorrection * estMaxQCorrectionFactor
                    * RtTables.BITS_PER_MB[128 + q]);
            if (bitsPerMb <= targetNorm) {
                break;
            }
        }
        return q;
    }

    /**
     * Returns the correction of the bits per macroblock for an error ({@code calc_correction_factor}).
     *
     * @param errPerMb the error per macroblock
     * @param errDivisor the error divisor
     * @param ptLow the power at quantizer index 0
     * @param ptHigh the largest power
     * @param q the quantizer index
     * @return the factor, from 0.05 to 5
     */
    private static double correctionFactor(double errPerMb, double errDivisor, double ptLow, double ptHigh, int q) {
        var power = Math.min(ptLow + q * 0.01, ptHigh);
        var factor = StrictMath.pow(errPerMb / errDivisor, power);
        return Math.clamp(factor, 0.05, 5.0);
    }

    /**
     * Returns the cost of a bit of a probability, in bits ({@code bitcost}).
     *
     * @param prob the probability
     * @return the cost
     */
    private static double bitcost(double prob) {
        return prob > 0.000122 ? -StrictMath.log(prob) / StrictMath.log(2.0) : 13.0;
    }

    /**
     * Returns the mode and vector overhead of a section, in 1/512 bit ({@code estimate_modemvcost}).
     *
     * @param section the sum of the section's records
     * @return the overhead
     */
    private long modeMvCost(double[] section) {
        var count = Math.max(section[COUNT], 1e-6);
        var avPctInter = section[PCNT_INTER] / count;
        var avPctMotion = section[PCNT_MOTION] / count;
        var avIntra = 1.0 - avPctInter;
        var zzCost = bitcost(avPctInter - avPctMotion);
        var motionCost = bitcost(avPctMotion);
        var intraCost = bitcost(avIntra);
        var mvCost = ((int) (section[NEW_MV_COUNT] / count) * 8) << 9;
        var modeCost = (long) (((avPctInter - avPctMotion) * zzCost + avPctMotion * motionCost + avIntra * intraCost)
                * mbs) * 512;
        return mvCost + modeCost;
    }

    /**
     * Returns the worst quantizer that codes a section at a bitrate ({@code estimate_max_q}).
     *
     * @param section the sum of the section's records
     * @param target the bits per frame
     * @param overhead the mode and vector overhead
     * @return the quantizer index
     */
    private int estimateMaxQ(double[] section, int target, int overhead) {
        if (target <= 0) {
            return maxqMaxLimit;
        }
        var sectionErr = section[CODED_ERROR] / Math.max(section[COUNT], 1e-6);
        var errPerMb = sectionErr / mbs;
        var targetNorm = target < (1 << 20) ? (512 * target) / mbs : 512 * (target / mbs);
        if (rollingTargetBits > 0 && activeWorstQuality < worstQuality) {
            var ratio = (double) rollingActualBits / rollingTargetBits;
            if (ratio < 0.95) {
                estMaxQCorrectionFactor -= 0.005;
            } else if (ratio > 1.05) {
                estMaxQCorrectionFactor += 0.005;
            }
            estMaxQCorrectionFactor = Math.clamp(estMaxQCorrectionFactor, 0.1, 10.0);
        }
        var overheadPerMb = overhead / mbs;
        overheadPerMb = (int) (overheadPerMb * StrictMath.pow(0.98, maxqMinLimit));
        var q = maxqMinLimit;
        for (; q < maxqMaxLimit; q++) {
            var err = correctionFactor(errPerMb, 150.0, 0.40, 0.90, q);
            var bitsPerMb = RtTables.BITS_PER_MB[128 + q] + overheadPerMb;
            bitsPerMb = (int) (.5 + err * speedCorrection * estMaxQCorrectionFactor * sectionMaxQFactor * bitsPerMb);
            overheadPerMb = (int) (overheadPerMb * 0.98);
            if (bitsPerMb <= targetNorm) {
                break;
            }
        }
        if (mode == MODE_CQ && q < cqTargetQuality) {
            q = cqTargetQuality;
        }
        if (niFrames > ((int) totalStats[COUNT] >> 8) && niFrames > 150) {
            maxqMaxLimit = Math.min(niAvQi + 32, worstQuality);
            maxqMinLimit = Math.max(niAvQi - 32, bestQuality);
        }
        return q;
    }

    /**
     * Returns the quality level a constrained quality encode can afford ({@code estimate_cq}).
     *
     * @param section the sum of the section's records
     * @param target the bits per frame
     * @param overhead the mode and vector overhead
     * @return the quantizer index
     */
    private int estimateCq(double[] section, int target, int overhead) {
        var sectionErr = section[CODED_ERROR] / Math.max(section[COUNT], 1e-6);
        var errPerMb = sectionErr / mbs;
        var targetNorm = target < (1 << 20) ? (512 * target) / mbs : 512 * (target / mbs);
        var overheadPerMb = overhead / mbs;
        var clipIiratio = totalStats[INTRA_ERROR] / divideCheck(totalStats[CODED_ERROR]);
        var clipIifactor = Math.max(1.0 - (clipIiratio - 10.0) * 0.025, 0.80);
        var q = 0;
        for (; q < MAXQ; q++) {
            var err = correctionFactor(errPerMb, 100.0, 0.40, 0.90, q);
            var bitsPerMb = RtTables.BITS_PER_MB[128 + q] + overheadPerMb;
            bitsPerMb = (int) (.5 + err * speedCorrection * clipIifactor * bitsPerMb);
            overheadPerMb = (int) (overheadPerMb * 0.98);
            if (bitsPerMb <= targetNorm) {
                break;
            }
        }
        q = RtTables.INTER_MINQ[q];
        if (q >= worstQuality) {
            q = worstQuality - 1;
        }
        return Math.max(q, bestQuality);
    }

    /**
     * Returns whether the next coding step is a group's alternate reference.
     *
     * @return whether it is
     */
    boolean nextIsArf() {
        return sourceAltRefPending && playAlternate;
    }

    /**
     * Returns the distance from the next shown frame to the source of the alternate reference
     * the next coding step codes.
     *
     * @return the distance in frames
     */
    int arfSourceOffset() {
        return framesTillGfUpdateDue;
    }

    /**
     * Gives up the pending alternate reference, whose source is past the end of the clip.
     */
    void cancelAltRef() {
        sourceAltRefPending = false;
    }

    /**
     * Plans the next shown frame and sets its type, references and target
     * ({@code vp8_second_pass} and the target sizing of {@code encode_frame_to_data_rate}).
     *
     * @param showIdx the display index of the frame
     * @param forceKey whether the caller asked for a key frame
     */
    void beginFrame(int showIdx, boolean forceKey) {
        keyFrame = false;
        refreshAlt = false;
        refreshGolden = false;
        refreshLast = true;
        srcFrameAltRef = altRefSourceIdx == showIdx;
        if (srcFrameAltRef) {
            altRefSourceIdx = -1;
        }
        var periodic = keyFrameFrequency < NO_KEY_INTERVAL && framesSinceKey % keyFrameFrequency == 0;
        if (framesToKey <= 0 || forceKey || currentVideoFrame == 0 || periodic) {
            findNextKeyFrame(showIdx);
            srcFrameAltRef = false;
            altRefSourceIdx = -1;
        } else if (streaming && framesTillGfUpdateDue == 0) {
            for (var i = 1; i <= maxGfInterval + 1 && i < framesToKey; i++) {
                if (candidateKf(showIdx + i)) {
                    framesToKey = i;
                    break;
                }
            }
        }
        if (framesTillGfUpdateDue == 0) {
            refreshMeanModifiedError();
            defineGfGroup(showIdx);
            if (sourceAltRefPending && !keyFrame) {
                var bak = perFrameBandwidth;
                assignStdFrameBits(showIdx);
                perFrameBandwidth = bak;
            }
        } else {
            assignStdFrameBits(showIdx);
        }
        nextIIRatio = has(showIdx + 1)
                ? (int) (s(showIdx + 1, INTRA_ERROR) / divideCheck(s(showIdx + 1, CODED_ERROR))) : nextIIRatio;
        if (mode != MODE_Q) {
            worstQualityEstimate(showIdx);
        }
        framesToKey--;
        if (has(showIdx)) {
            for (var i = 0; i < FIELDS; i++) {
                totalLeftStats[i] -= s(showIdx, i);
            }
        }
        if (keyFrame) {
            sourceAltRefActive = false;
            framesTillGfUpdateDue = baselineGfInterval;
            refreshGolden = true;
            refreshAlt = true;
            thisFrameTarget = perFrameBandwidth;
        } else {
            pframeTarget();
        }
        plannedTarget = thisFrameTarget;
        if (streaming) {
            thisFrameTarget = correctTarget(thisFrameTarget);
        }
        setQualityBounds();
    }

    /**
     * Plans the next coding step as the pending alternate reference.
     *
     * @param sourceIdx the display index of its source
     */
    void beginAltRef(int sourceIdx) {
        keyFrame = false;
        refreshAlt = true;
        refreshGolden = false;
        refreshLast = false;
        srcFrameAltRef = false;
        altRefSourceIdx = sourceIdx;
        perFrameBandwidth = gfBits;
        thisFrameTarget = gfBits;
        plannedTarget = gfBits;
        if (streaming) {
            thisFrameTarget = correctTarget(thisFrameTarget);
        }
        setQualityBounds();
    }

    /**
     * Updates the worst quantizer estimate from the frames left ({@code vp8_second_pass}).
     *
     * @param showIdx the display index of the frame
     */
    private void worstQualityEstimate(int showIdx) {
        var section = totalLeftStats;
        var count = section[COUNT];
        if (count < 1) {
            return;
        }
        var overhead = (int) Math.min(modeMvCost(section), Integer.MAX_VALUE);
        int sectionTarget;
        if (streaming) {
            sectionTarget = (int) Math.clamp(avPerFrameBandwidth + bitsOffTarget / 16, 0, Integer.MAX_VALUE);
        } else {
            var framesLeft = Math.max(1, (int) (totalStats[COUNT] - currentVideoFrame));
            sectionTarget = (int) Math.min(bitsLeft / framesLeft, Integer.MAX_VALUE);
        }
        if (currentVideoFrame == 0) {
            estMaxQCorrectionFactor = 1.0;
            if (mode == MODE_CQ) {
                var est = estimateCq(section, sectionTarget, overhead);
                cqTargetQuality = Math.max(cqLevel, est);
            }
            maxqMaxLimit = worstQuality;
            maxqMinLimit = bestQuality;
            var tmpQ = estimateMaxQ(section, sectionTarget, overhead);
            maxqMaxLimit = Math.min(tmpQ + 32, worstQuality);
            maxqMinLimit = Math.max(tmpQ - 32, bestQuality);
            activeWorstQuality = tmpQ;
            niAvQi = tmpQ;
        } else if (streaming || (currentVideoFrame < (((int) totalStats[COUNT] * 255) >> 8)
                && currentVideoFrame + baselineGfInterval < (int) totalStats[COUNT])) {
            var tmpQ = estimateMaxQ(section, sectionTarget, overhead);
            if (tmpQ > activeWorstQuality) {
                activeWorstQuality++;
            } else if (tmpQ < activeWorstQuality) {
                activeWorstQuality--;
            }
            activeWorstQuality = (activeWorstQuality * 3 + tmpQ + 2) / 4;
        }
    }

    /**
     * Sets the target of an inter frame ({@code calc_pframe_target_size}).
     */
    private void pframeTarget() {
        var minFrameTarget = Math.max(minFrameBandwidth, avPerFrameBandwidth >> 5);
        thisFrameTarget = perFrameBandwidth;
        if (thisFrameTarget < minFrameTarget) {
            thisFrameTarget = minFrameTarget;
        }
        interFrameTarget = thisFrameTarget;
        if (framesTillGfUpdateDue == 0) {
            refreshGolden = true;
            framesTillGfUpdateDue = baselineGfInterval;
            if (sourceAltRefActive) {
                thisFrameTarget = 0;
            } else {
                thisFrameTarget = perFrameBandwidth;
            }
        }
    }

    /**
     * Corrects a target by the bits spent off target so far, for a one-pass encode.
     *
     * @param target the planned target
     * @return the corrected target
     */
    private int correctTarget(int target) {
        if (target <= 0) {
            return target;
        }
        var delta = bitsOffTarget / 16;
        var limit = (long) target * VBR_PCT_ADJUSTMENT_LIMIT / 100;
        delta = Math.clamp(delta, -limit, limit);
        return (int) Math.clamp(target + delta, 0, Integer.MAX_VALUE);
    }

    /**
     * Sets the active best and worst quantizers of the current step and the size range a coding
     * may land in (the quality setup of {@code encode_frame_to_data_rate} and
     * {@code vp8_compute_frame_size_bounds}).
     */
    private void setQualityBounds() {
        var q = mode == MODE_Q ? cqLevel : activeWorstQuality;
        if (keyFrame) {
            activeBestQuality = gfuBoost > 600 ? GoodTables.KF_LOW_MOTION_MINQ[q] : RtTables.KF_HIGH_MOTION_MINQ[q];
            if (thisKeyFrameForced) {
                if (activeBestQuality > avgFrameQindex * 7 / 8) {
                    activeBestQuality = avgFrameQindex * 7 / 8;
                } else if (activeBestQuality < (avgFrameQindex >> 2)) {
                    activeBestQuality = avgFrameQindex >> 2;
                }
            }
        } else if (refreshGolden || refreshAlt) {
            if (mode != MODE_Q && framesSinceKey > 1 && avgFrameQindex < activeWorstQuality) {
                q = avgFrameQindex;
            }
            if (mode == MODE_CQ && q < cqTargetQuality) {
                q = cqTargetQuality;
            }
            if (gfuBoost > 1000) {
                activeBestQuality = GoodTables.GF_LOW_MOTION_MINQ[q];
            } else if (gfuBoost < 400) {
                activeBestQuality = RtTables.GF_HIGH_MOTION_MINQ[q];
            } else {
                activeBestQuality = GoodTables.GF_MID_MOTION_MINQ[q];
            }
            if (mode == MODE_CQ) {
                activeBestQuality = activeBestQuality * 15 / 16;
            }
        } else {
            activeBestQuality = RtTables.INTER_MINQ[q];
            if (mode == MODE_CQ && activeBestQuality < cqTargetQuality) {
                activeBestQuality = rollingActualBits < minFrameBandwidth ? cqLevel : cqTargetQuality;
            }
        }
        if (mode == MODE_Q) {
            activeWorstQuality = cqLevel;
        }
        activeWorstQuality = Math.min(activeWorstQuality, worstQuality);
        activeBestQuality = Math.max(activeBestQuality, bestQuality);
        if (activeWorstQuality < activeBestQuality) {
            activeWorstQuality = activeBestQuality;
        }
        qLow = activeBestQuality;
        qHigh = activeWorstQuality;
        overshootSeen = false;
        undershootSeen = false;
        long over;
        long under;
        var target = (long) thisFrameTarget;
        if (keyFrame || refreshGolden || refreshAlt) {
            over = target * 9 / 8;
            under = target * 7 / 8;
        } else if (mode == MODE_CQ) {
            over = target * 11 / 8;
            under = target * 2 / 8;
        } else {
            over = target * 11 / 8;
            under = target * 5 / 8;
        }
        over += 200;
        under -= 200;
        overShootLimit = (int) Math.clamp(over, 1, Integer.MAX_VALUE);
        underShootLimit = (int) Math.clamp(under, 0, Integer.MAX_VALUE);
    }

    /**
     * Returns the quantizer of the first coding of the current step.
     *
     * @return the quantizer index
     */
    int pickQ() {
        zbinOverQuant = 0;
        zbinOqLow = 0;
        zbinOqHigh = zbinOqMax();
        if (mode == MODE_Q) {
            if (keyFrame || refreshAlt || (refreshGolden && !srcFrameAltRef)) {
                return activeBestQuality;
            }
            return cqLevel;
        }
        return regulateQ(thisFrameTarget);
    }

    /**
     * Returns the largest zero-bin over-quantization of the current step: none for key frames,
     * a little for golden and alternate references, {@link #ZBIN_OQ_MAX} otherwise.
     *
     * @return the over-quantization in 1/128 of the AC step
     */
    private int zbinOqMax() {
        if (keyFrame) {
            return 0;
        }
        return refreshAlt || (refreshGolden && !sourceAltRefActive) ? GF_ZBIN_OQ_MAX : ZBIN_OQ_MAX;
    }

    /**
     * Returns the zero-bin over-quantization the next coding of the current step uses.
     *
     * @return the over-quantization in 1/128 of the AC step
     */
    int zbinOverQuant() {
        return zbinOverQuant;
    }

    /**
     * Returns whether the current step is a key frame the interval forced, whose codings are
     * then measured against the error of the frame before it ({@link #recode}).
     *
     * @return whether it is
     */
    boolean forcedKeyFrame() {
        return keyFrame && thisKeyFrameForced;
    }

    /**
     * Returns whether the reconstruction of the current step is the last before a key frame the
     * interval forces, whose error is then given to {@link #setAmbientError}.
     *
     * @return whether it is
     */
    boolean measuresAmbientError() {
        return nextKeyFrameForced && framesToKey == 0;
    }

    /**
     * Sets the luma squared error of the reconstruction of the frame before a forced key frame.
     *
     * @param error the squared error, summed over the luma samples
     */
    void setAmbientError(long error) {
        ambientError = error;
    }

    /**
     * Returns the rate correction factor of the current step.
     *
     * @return the factor
     */
    private double rateCorrectionFactor() {
        if (keyFrame) {
            return keyFrameRateCorrectionFactor;
        }
        return refreshAlt || refreshGolden ? gfRateCorrectionFactor : rateCorrectionFactor;
    }

    /**
     * Returns the quantizer whose predicted size best meets a target ({@code vp8_regulate_q}).
     *
     * @param target the target in bits
     * @return the quantizer index
     */
    private int regulateQ(int target) {
        var q = activeWorstQuality;
        var correction = rateCorrectionFactor();
        int targetBitsPerMb;
        if (target > (Integer.MAX_VALUE >> BPER_MB_NORMBITS)) {
            var temp = target / mbs;
            targetBitsPerMb = temp > (Integer.MAX_VALUE >> BPER_MB_NORMBITS) ? Integer.MAX_VALUE
                    : temp << BPER_MB_NORMBITS;
        } else {
            targetBitsPerMb = (target << BPER_MB_NORMBITS) / mbs;
        }
        var kind = keyFrame ? 0 : 128;
        var lastError = Integer.MAX_VALUE;
        var i = activeBestQuality;
        do {
            var bitsPerMb = (int) (.5 + correction * RtTables.BITS_PER_MB[kind + i]);
            if (bitsPerMb <= targetBitsPerMb) {
                q = targetBitsPerMb - bitsPerMb <= lastError ? i : i - 1;
                break;
            }
            lastError = bitsPerMb - targetBitsPerMb;
        } while (++i <= activeWorstQuality);
        zbinOverQuant = 0;
        if (q >= MAXQ) {
            var oqMax = zbinOqMax();
            var factor = 0.99;
            var bitsPerMb = (int) (.5 + correction * RtTables.BITS_PER_MB[kind + Math.min(i, MAXQ)]);
            while (zbinOverQuant < oqMax) {
                zbinOverQuant++;
                bitsPerMb = (int) (factor * bitsPerMb);
                factor = Math.min(factor + ZBIN_FACTOR_STEP, 0.999);
                if (bitsPerMb <= targetBitsPerMb) {
                    break;
                }
            }
        }
        return q;
    }

    /**
     * Updates the rate correction factor of the current step from the size of its last coding
     * ({@code vp8_update_rate_correction_factors}).
     *
     * @param q the quantizer index of the coding
     * @param dampVar the damping: 0 for the first coding, 1 after oscillating, 2 for the final
     * coding
     */
    private void updateRateCorrectionFactors(int q, int dampVar) {
        var factor = rateCorrectionFactor();
        var kind = keyFrame ? 0 : 128;
        var projected = (int) (((.5 + factor * RtTables.BITS_PER_MB[kind + q]) * mbs) / (1 << BPER_MB_NORMBITS));
        var oqFactor = 0.99;
        for (var z = zbinOverQuant; z > 0; z--) {
            projected = (int) (oqFactor * projected);
            oqFactor = Math.min(oqFactor + ZBIN_FACTOR_STEP, 0.999);
        }
        var correction = 100;
        if (projected > 0) {
            correction = (int) ((100 * (long) projectedFrameSize) / projected);
        }
        var limit = dampVar == 0 ? 0.75 : dampVar == 1 ? 0.375 : 0.25;
        if (correction > 102) {
            correction = (int) (100.5 + (correction - 100) * limit);
            factor = Math.min(factor * correction / 100, MAX_BPB_FACTOR);
        } else if (correction < 99) {
            correction = (int) (100.5 - (100 - correction) * limit);
            factor = Math.max(factor * correction / 100, MIN_BPB_FACTOR);
        }
        if (keyFrame) {
            keyFrameRateCorrectionFactor = factor;
        } else if (refreshAlt || refreshGolden) {
            gfRateCorrectionFactor = factor;
        } else {
            rateCorrectionFactor = factor;
        }
    }

    /**
     * Returns the quantizer of the next coding of the current step after a coding of a size, or
     * {@code -1} when that coding is kept (the recode loop of {@code encode_frame_to_data_rate}).
     *
     * <p>A key frame the interval forced is coded again while its luma error is above 7/8 or
     * below half of the error of the frame before it; any other coding is coded again while its
     * size misses the range of the step, at the quantizer the corrected rate model predicts, the
     * zero-bin over-quantization widening when the largest quantizer index is reached.
     *
     * @param q the quantizer index of the coding
     * @param bytes the size of the coding in bytes
     * @param keyFrameError the luma squared error of the coding when {@link #forcedKeyFrame},
     *                      ignored otherwise
     * @return the next quantizer index, or {@code -1}
     */
    int recode(int q, int bytes, long keyFrameError) {
        projectedFrameSize = bytes << 3;
        lastCodedQ = q;
        if (mode == MODE_Q || srcFrameAltRef) {
            return -1;
        }
        int next;
        if (forcedKeyFrame()) {
            next = q;
            if (keyFrameError > ((ambientError * 7) >> 3)) {
                qHigh = q > qLow ? q - 1 : qLow;
                next = (qHigh + qLow) >> 1;
            } else if (keyFrameError < (ambientError >> 1)) {
                qLow = q < qHigh ? q + 1 : qHigh;
                next = (qHigh + qLow + 1) >> 1;
            }
            next = Math.clamp(next, qLow, qHigh);
            return next != q ? next : -1;
        }
        if (!recodeTest(q)) {
            return -1;
        }
        var retries = 0;
        if (projectedFrameSize > thisFrameTarget) {
            qLow = q < qHigh ? q + 1 : qHigh;
            if (zbinOverQuant > 0) {
                zbinOqLow = zbinOverQuant < zbinOqHigh ? zbinOverQuant + 1 : zbinOqHigh;
            }
            if (undershootSeen) {
                updateRateCorrectionFactors(q, 1);
                next = (qHigh + qLow + 1) / 2;
                if (next < MAXQ) {
                    zbinOverQuant = 0;
                } else {
                    zbinOqLow = zbinOverQuant < zbinOqHigh ? zbinOverQuant + 1 : zbinOqHigh;
                    zbinOverQuant = (zbinOqHigh + zbinOqLow) / 2;
                }
            } else {
                updateRateCorrectionFactors(q, 0);
                next = regulateQ(thisFrameTarget);
                while ((next < qLow || zbinOverQuant < zbinOqLow) && retries < 10) {
                    updateRateCorrectionFactors(q, 0);
                    next = regulateQ(thisFrameTarget);
                    retries++;
                }
            }
            overshootSeen = true;
        } else {
            if (zbinOverQuant == 0) {
                qHigh = q > qLow ? q - 1 : qLow;
            } else {
                zbinOqHigh = zbinOverQuant > zbinOqLow ? zbinOverQuant - 1 : zbinOqLow;
            }
            if (overshootSeen) {
                updateRateCorrectionFactors(q, 1);
                next = (qHigh + qLow) / 2;
                if (next < MAXQ) {
                    zbinOverQuant = 0;
                } else {
                    zbinOverQuant = (zbinOqHigh + zbinOqLow) / 2;
                }
            } else {
                updateRateCorrectionFactors(q, 0);
                next = regulateQ(thisFrameTarget);
                if (mode == MODE_CQ && next < qLow) {
                    qLow = next;
                }
                while ((next > qHigh || zbinOverQuant > zbinOqHigh) && retries < 10) {
                    updateRateCorrectionFactors(q, 0);
                    next = regulateQ(thisFrameTarget);
                    retries++;
                }
            }
            undershootSeen = true;
        }
        next = Math.clamp(next, qLow, qHigh);
        zbinOverQuant = Math.clamp(zbinOverQuant, zbinOqLow, zbinOqHigh);
        return next != q ? next : -1;
    }

    /**
     * Returns whether a coding misses the size range so the frame is coded again
     * ({@code recode_loop_test}).
     *
     * @param q the quantizer index of the coding
     * @return whether it does
     */
    private boolean recodeTest(int q) {
        if (recodeLoop == 1 || (recodeLoop == 2 && (keyFrame || refreshGolden || refreshAlt))) {
            if ((projectedFrameSize > overShootLimit && q < activeWorstQuality)
                    || (projectedFrameSize < underShootLimit && q > activeBestQuality)) {
                return true;
            }
            if (mode == MODE_CQ) {
                if (q > cqTargetQuality && projectedFrameSize < (thisFrameTarget * 7) >> 3) {
                    return true;
                }
                if (q > cqLevel && projectedFrameSize < minFrameBandwidth && activeBestQuality > cqLevel) {
                    activeBestQuality = cqLevel;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Records the final coding of the current step (the post-encode updates of
     * {@code encode_frame_to_data_rate}, {@code Pass2Encode}, {@code update_golden_frame_stats}
     * and {@code update_alt_ref_frame_stats}).
     *
     * @param q the quantizer index of the coding
     * @param bytes the size of the coding in bytes
     */
    void endFrame(int q, int bytes) {
        projectedFrameSize = bytes << 3;
        if (mode != MODE_Q) {
            updateRateCorrectionFactors(q, 2);
        }
        lastQ[keyFrame ? 0 : 1] = q;
        if (keyFrame) {
            framesSinceKey = 0;
        } else {
            avgFrameQindex = (2 + 3 * avgFrameQindex + q) >> 2;
        }
        if (!keyFrame && !refreshGolden && !refreshAlt) {
            niFrames++;
            niTotQi += q;
            niAvQi = (int) (niTotQi / niFrames);
        }
        rollingTargetBits = (int) ((rollingTargetBits * 3L + thisFrameTarget + 2) >> 2);
        rollingActualBits = (int) ((rollingActualBits * 3L + projectedFrameSize + 2) >> 2);
        longRollingTargetBits = (int) ((longRollingTargetBits * 31L + thisFrameTarget + 16) >> 5);
        longRollingActualBits = (int) ((longRollingActualBits * 31L + projectedFrameSize + 16) >> 5);
        if (keyFrame) {
            kfGroupBits = Math.max(kfGroupBits + thisFrameTarget - projectedFrameSize, 0);
        } else if (refreshGolden || refreshAlt) {
            gfGroupBits = Math.max(gfGroupBits + thisFrameTarget - projectedFrameSize, 0);
        }
        bitsLeft -= projectedFrameSize;
        if (streaming && (keyFrame || (!refreshGolden && !refreshAlt))) {
            bitsOffTarget += plannedTarget - projectedFrameSize;
        }
        if (refreshAlt && !keyFrame) {
            framesSinceGolden = 0;
            sourceAltRefPending = false;
            sourceAltRefActive = true;
        } else if (refreshGolden) {
            framesSinceGolden = 0;
            if (!sourceAltRefPending) {
                sourceAltRefActive = false;
            }
            if (framesTillGfUpdateDue > 0) {
                framesTillGfUpdateDue--;
            }
        } else {
            if (framesTillGfUpdateDue > 0) {
                framesTillGfUpdateDue--;
            }
            framesSinceGolden++;
        }
        if (!refreshAlt || keyFrame) {
            currentVideoFrame++;
            framesSinceKey++;
        }
    }

    /**
     * Returns whether the current step is a key frame.
     *
     * @return whether it is
     */
    boolean keyFrame() {
        return keyFrame;
    }

    /**
     * Returns whether the current step refreshes the last reference.
     *
     * @return whether it does
     */
    boolean refreshLast() {
        return refreshLast;
    }

    /**
     * Returns whether the current step refreshes the golden reference.
     *
     * @return whether it does
     */
    boolean refreshGolden() {
        return refreshGolden;
    }

    /**
     * Returns whether the current step refreshes the alternate reference.
     *
     * @return whether it does
     */
    boolean refreshAlt() {
        return refreshAlt;
    }

    /**
     * Returns whether the current step codes the source of the active alternate reference.
     *
     * @return whether it does
     */
    boolean srcFrameAltRef() {
        return srcFrameAltRef;
    }

    /**
     * Returns whether the frames being coded predict from an alternate reference of the future,
     * whose motion vectors point the other way ({@code ref_frame_sign_bias[ALTREF_FRAME]}).
     *
     * @return whether they do
     */
    boolean altRefActive() {
        return sourceAltRefActive;
    }

    /**
     * Returns the boost of the group's golden frame or alternate reference.
     *
     * @return the boost
     */
    int gfuBoost() {
        return gfuBoost;
    }

    /**
     * Returns the intra-to-inter error ratio of the next frame.
     *
     * @return the ratio, truncated
     */
    int nextIIRatio() {
        return nextIIRatio;
    }

    /**
     * Returns the number of frames of the golden frame group the next alternate reference
     * serves.
     *
     * @return the frames
     */
    int activeArnrFrames() {
        return activeArnrFrames;
    }

    /**
     * Returns the intra-to-inter error ratio of the current section.
     *
     * @return the ratio, truncated
     */
    int sectionIntraRating() {
        return sectionIntraRating;
    }

    /**
     * Returns the frames since the last golden frame.
     *
     * @return the count
     */
    int framesSinceGolden() {
        return framesSinceGolden;
    }
}
