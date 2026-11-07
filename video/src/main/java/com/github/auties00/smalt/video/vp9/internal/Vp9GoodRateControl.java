/* Ports libvpx 1.15.1 (vp9/encoder/vp9_firstpass.c, vp9/encoder/vp9_ratectrl.c, vp9/encoder/vp9_encoder.c).
   Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.CODED_ERROR;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.COUNT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.DURATION;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.FIELDS;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.FRAME_NOISE_ENERGY;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.INACTIVE_ZONE_ROWS;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.INTRA_ERROR;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.INTRA_SKIP_PCT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.INTRA_SMOOTH_PCT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.MVC;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.MVC_ABS;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.MVR;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.MVR_ABS;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.MV_IN_OUT_COUNT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.PCNT_INTER;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.PCNT_INTRA_LOW;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.PCNT_MOTION;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.PCNT_NEUTRAL;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.PCNT_SECOND_REF;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.SR_CODED_ERROR;
import static com.github.auties00.smalt.video.vp9.internal.Vp9FirstPassAnalyzer.WEIGHT;

/**
 * Plans and regulates the bits of a good-quality VP9 stream from first-pass statistics: places
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
 * @implNote This implementation ports libvpx's second pass ({@code vp9_init_second_pass},
 * {@code vp9_rc_get_second_pass_params}, {@code find_next_key_frame}, {@code define_gf_group},
 * {@code allocate_gf_group_bits}, {@code get_twopass_worst_quality},
 * {@code vp9_twopass_postencode_update}) and the two-pass rate control
 * ({@code vp9_rc_pick_q_and_bounds_two_pass}, {@code rc_constant_q},
 * {@code vp9_rc_regulate_q}, {@code vp9_rc_update_rate_correction_factors},
 * {@code vp9_rc_postencode_update}, {@code vp9_set_target_rate}) with a single layer of
 * alternate references, as {@code vpxenc --auto-alt-ref=1} codes them. The one-pass mode is
 * Smalt's: it runs the same planning on the lookahead's statistics, searches the lookahead for
 * scene cuts at each new golden frame group, and replaces the clip budget by the average rate.
 */
final class Vp9GoodRateControl {
    /**
     * The update type of a key frame, which refreshes every reference ({@code KF_UPDATE}).
     */
    static final int KF_UPDATE = 0;

    /**
     * The update type of an ordinary frame, which refreshes the last reference ({@code LF_UPDATE}).
     */
    static final int LF_UPDATE = 1;

    /**
     * The update type of a golden frame without an alternate reference, which refreshes the last and golden
     * references ({@code GF_UPDATE}).
     */
    static final int GF_UPDATE = 2;

    /**
     * The update type of a hidden alternate reference ({@code ARF_UPDATE}).
     */
    static final int ARF_UPDATE = 3;

    /**
     * The update type of the frame that codes the source of an alternate reference again and refreshes the
     * golden reference ({@code OVERLAY_UPDATE}).
     */
    static final int OVERLAY_UPDATE = 4;

    /**
     * The rate correction level of ordinary inter frames ({@code INTER_NORMAL}).
     */
    private static final int INTER_NORMAL = 0;

    /**
     * The rate correction level of the alternate references below the first layer ({@code GF_ARF_LOW}).
     */
    private static final int GF_ARF_LOW = 2;

    /**
     * The rate correction level of golden frames and alternate references ({@code GF_ARF_STD}).
     */
    private static final int GF_ARF_STD = 3;

    /**
     * The rate correction level of key frames ({@code KF_STD}).
     */
    private static final int KF_STD = 4;

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
     * The share of the rate-distortion multiplier each update type keeps, in 1/128
     * ({@code rd_frame_type_factor}).
     */
    static final int[] RD_FRAME_TYPE_FACTOR = {128, 144, 128, 128, 144, 144};

    /**
     * The number of alternate reference layers the quantizer history keeps ({@code MAX_ARF_LAYERS}).
     */
    private static final int MAX_ARF_LAYERS = 6;

    /**
     * The boost of an ordinary frame, the unit of the boosts ({@code NORMAL_BOOST}).
     */
    private static final int NORMAL_BOOST = 100;

    /**
     * The smallest boost of an alternate reference ({@code MIN_ARF_GF_BOOST}).
     */
    private static final int MIN_ARF_GF_BOOST = 250;

    /**
     * The smallest accumulated prediction decay of a boost scan ({@code MIN_DECAY_FACTOR}).
     */
    private static final double MIN_DECAY_FACTOR = 0.01;

    /**
     * The decay each percent of intra blocks adds ({@code INTRA_PART}).
     */
    private static final double INTRA_PART = 0.005;

    /**
     * The smallest second reference decay rate ({@code DEFAULT_DECAY_LIMIT}).
     */
    private static final double DEFAULT_DECAY_LIMIT = 0.75;

    /**
     * The second reference error excess below which the prediction does not decay ({@code LOW_SR_DIFF_TRHESH}).
     */
    private static final double LOW_SR_DIFF_TRHESH = 0.1;

    /**
     * The coded error per macroblock above which neutral blocks count as intra in the decay ({@code
     * LOW_CODED_ERR_PER_MB}).
     */
    private static final double LOW_CODED_ERR_PER_MB = 10.0;

    /**
     * The intra to inter error ratio below which neutral blocks count as intra in the decay ({@code
     * NCOUNT_FRAME_II_THRESH}).
     */
    private static final double NCOUNT_FRAME_II_THRESH = 6.0;

    /**
     * The reference error per macroblock of an alternate reference's frame boost ({@code BASELINE_ERR_PER_MB}).
     */
    private static final double BASELINE_ERR_PER_MB = 12500.0;

    /**
     * The largest boost one frame adds to an alternate reference ({@code GF_MAX_FRAME_BOOST}).
     */
    private static final double GF_MAX_FRAME_BOOST = 96.0;

    /**
     * The smallest boost one frame adds to a key frame ({@code KF_MIN_FRAME_BOOST}).
     */
    private static final double KF_MIN_FRAME_BOOST = 40.0;

    /**
     * The largest boost one frame adds to a key frame ({@code KF_MAX_FRAME_BOOST}).
     */
    private static final double KF_MAX_FRAME_BOOST = 96.0;

    /**
     * The largest boost of a key frame ({@code MAX_KF_TOT_BOOST}).
     */
    private static final int MAX_KF_TOT_BOOST = 5400;

    /**
     * The weight of the zero-motion share in the prediction decay ({@code DEFAULT_ZM_FACTOR}).
     */
    private static final double DEFAULT_ZM_FACTOR = 0.5;

    /**
     * The most the best quantizer may be lowered for an undershoot of a variable bitrate ({@code
     * MINQ_ADJ_LIMIT}).
     */
    private static final int MINQ_ADJ_LIMIT = 48;

    /**
     * The most the best quantizer may be lowered for an undershoot of constrained quality ({@code
     * MINQ_ADJ_LIMIT_CQ}).
     */
    private static final int MINQ_ADJ_LIMIT_CQ = 20;

    /**
     * The share of a frame's target below which its undershoot is spent quickly ({@code
     * HIGH_UNDERSHOOT_RATIO}).
     */
    private static final int HIGH_UNDERSHOOT_RATIO = 2;

    /**
     * The smoothing divisor of the worst quantizer of successive groups ({@code AV_WQ_FACTOR}).
     */
    private static final double AV_WQ_FACTOR = 4.0;

    /**
     * The exponent of the active area in a frame's score ({@code ACT_AREA_CORRECTION}).
     */
    private static final double ACT_AREA_CORRECTION = 0.5;

    /**
     * The smallest active share of a frame ({@code MIN_ACTIVE_AREA}).
     */
    private static final double MIN_ACTIVE_AREA = 0.5;

    /**
     * The largest active share of a frame ({@code MAX_ACTIVE_AREA}).
     */
    private static final double MAX_ACTIVE_AREA = 1.0;

    /**
     * The accumulated zoom that ends a golden frame group ({@code ARF_ABS_ZOOM_THRESH}).
     */
    private static final double ARF_ABS_ZOOM_THRESH = 4.0;

    /**
     * The largest boost of a golden frame ({@code MAX_GF_BOOST}).
     */
    private static final int MAX_GF_BOOST = 5400;

    /**
     * The share of the alternate reference's quality boost kept at the end of a key frame group ({@code
     * LAST_ALR_ACTIVE_BEST_QUALITY_ADJUSTMENT_FACTOR}).
     */
    private static final double LAST_ALR_ADJUSTMENT_FACTOR = 0.2;

    /**
     * The intra to inter error ratio below which a spike of error can be a slide change ({@code VERY_LOW_II}).
     */
    private static final double VERY_LOW_II = 1.5;

    /**
     * The error ratio to both neighbors of a slide change ({@code ERROR_SPIKE}).
     */
    private static final double ERROR_SPIKE = 5.0;

    /**
     * The smallest intra share of a scene cut ({@code MIN_INTRA_LEVEL}).
     */
    private static final double MIN_INTRA_LEVEL = 0.25;

    /**
     * The second reference share above which a frame is not a scene cut ({@code SECOND_REF_USAGE_THRESH}).
     */
    private static final double SECOND_REF_USAGE_THRESH = 0.2;

    /**
     * The inter share below which a frame is a scene cut candidate ({@code VERY_LOW_INTER_THRESH}).
     */
    private static final double VERY_LOW_INTER_THRESH = 0.05;

    /**
     * The intra to inter error ratio below which an error spike is a scene cut candidate ({@code
     * KF_II_ERR_THRESHOLD}).
     */
    private static final double KF_II_ERR_THRESHOLD = 2.5;

    /**
     * The largest intra to inter error ratio a key frame candidate's scan counts ({@code KF_II_MAX}).
     */
    private static final double KF_II_MAX = 128.0;

    /**
     * The scale of the intra to inter error ratio of a key frame candidate's scan ({@code II_FACTOR}).
     */
    private static final double II_FACTOR = 12.5;

    /**
     * The intra error below which a key frame candidate's scan stops ({@code V_LOW_INTRA}).
     */
    private static final double V_LOW_INTRA = 0.5;

    /**
     * The number of recent decay rates the key frame search multiplies ({@code FRAMES_TO_CHECK_DECAY}).
     */
    private static final int FRAMES_TO_CHECK_DECAY = 8;

    /**
     * The smallest boost of a key frame ({@code MIN_KF_TOT_BOOST}).
     */
    private static final int MIN_KF_TOT_BOOST = 300;

    /**
     * The number of frames a key frame's boost scans by default ({@code DEFAULT_SCAN_FRAMES_FOR_KF_BOOST}).
     */
    private static final int DEFAULT_SCAN_FRAMES_FOR_KF_BOOST = 32;

    /**
     * The most frames a key frame's boost scans ({@code MAX_SCAN_FRAMES_FOR_KF_BOOST}).
     */
    private static final int MAX_SCAN_FRAMES_FOR_KF_BOOST = 48;

    /**
     * The fewest frames a key frame's boost scans ({@code MIN_SCAN_FRAMES_FOR_KF_BOOST}).
     */
    private static final int MIN_SCAN_FRAMES_FOR_KF_BOOST = 32;

    /**
     * The accumulated zoom that ends a key frame's boost scan ({@code KF_ABS_ZOOM_THRESH}).
     */
    private static final double KF_ABS_ZOOM_THRESH = 6.0;

    /**
     * The zero-motion percentage of a static key frame group ({@code STATIC_KF_GROUP_THRESH}).
     */
    private static final int STATIC_KF_GROUP_THRESH = 99;

    /**
     * The zero-motion percentage above which a forced key frame keeps the last key frame's quantizer ({@code
     * STATIC_MOTION_THRESH}).
     */
    private static final int STATIC_MOTION_THRESH = 95;

    /**
     * The smooth share above which constrained quality lowers its level ({@code SMOOTH_PCT_MIN}).
     */
    private static final double SMOOTH_PCT_MIN = 0.1;

    /**
     * The smooth share per step of that lowering ({@code SMOOTH_PCT_DIV}).
     */
    private static final double SMOOTH_PCT_DIV = 0.05;

    /**
     * The fewest bits a frame is predicted to take ({@code FRAME_OVERHEAD_BITS}).
     */
    private static final int FRAME_OVERHEAD_BITS = 200;

    /**
     * The fraction bits of the bits per macroblock ({@code BPER_MB_NORMBITS}).
     */
    private static final int BPER_MB_NORMBITS = 9;

    /**
     * The smallest rate correction factor ({@code MIN_BPB_FACTOR}).
     */
    private static final double MIN_BPB_FACTOR = 0.005;

    /**
     * The largest rate correction factor ({@code MAX_BPB_FACTOR}).
     */
    private static final double MAX_BPB_FACTOR = 50;

    /**
     * The bits per macroblock of the smallest frame size limit ({@code MAX_MB_RATE}).
     */
    private static final int MAX_MB_RATE = 250;

    /**
     * The smallest frame size limit in bits ({@code MAXRATE_1080P}).
     */
    private static final int MAXRATE_1080P = 4000000;

    /**
     * The shortest default golden frame interval ({@code MIN_GF_INTERVAL}).
     */
    private static final int MIN_GF_INTERVAL = 4;

    /**
     * The longest default golden frame interval ({@code MAX_GF_INTERVAL}).
     */
    private static final int MAX_GF_INTERVAL = 16;

    /**
     * The longest golden frame group of a static scene ({@code MAX_STATIC_GF_GROUP_LENGTH}).
     */
    private static final int MAX_STATIC_GF_GROUP_LENGTH = 250;

    /**
     * The largest share of a frame's target the bits off target change ({@code VBR_PCT_ADJUSTMENT_LIMIT}).
     */
    private static final int VBR_PCT_ADJUSTMENT_LIMIT = 50;

    /**
     * The largest quantizer step of a recode ({@code MAX_QSTEP_ADJ}).
     */
    private static final int MAX_QSTEP_ADJ = 4;

    /**
     * The boost below which golden frames take the high motion quantizers ({@code gf_low}).
     */
    private static final int GF_LOW = 400;

    /**
     * The boost above which golden frames take the low motion quantizers ({@code gf_high}).
     */
    private static final int GF_HIGH = 2000;

    /**
     * The boost below which key frames take the high motion quantizers ({@code kf_low}).
     */
    private static final int KF_LOW = 300;

    /**
     * The boost above which key frames take the low motion quantizers ({@code kf_high}).
     */
    private static final int KF_HIGH = 4800;

    /**
     * The noise energy of an ordinary section ({@code SECTION_NOISE_DEF}).
     */
    private static final double SECTION_NOISE_DEF = Vp9FirstPassAnalyzer.SECTION_NOISE_DEF;

    /**
     * The smallest noise factor of the worst quantizer estimate ({@code NOISE_FACTOR_MIN}).
     */
    private static final double NOISE_FACTOR_MIN = 0.9;

    /**
     * The largest noise factor of the worst quantizer estimate ({@code NOISE_FACTOR_MAX}).
     */
    private static final double NOISE_FACTOR_MAX = 1.1;

    /**
     * The exponent of the error ratio of the worst quantizer estimate at every 32nd quantizer index ({@code
     * q_pow_term}).
     */
    private static final double[] Q_POW_TERM = {0.65, 0.70, 0.75, 0.85, 0.90, 0.90, 0.90, 1.00, 1.25};

    /**
     * The bits each rate correction level may spend over an ordinary frame at the worst quantizer ({@code
     * rate_factor_deltas}).
     */
    private static final double[] RATE_FACTOR_DELTAS = {1.00, 1.00, 1.50, 1.75, 2.00};

    /**
     * The best key frame quantizer of each worst quantizer for low motion ({@code kf_low_motion_minq}).
     */
    private static final int[] KF_LOW_MOTION_MINQ = new int[256];

    /**
     * The best key frame quantizer of each worst quantizer for high motion ({@code kf_high_motion_minq}).
     */
    private static final int[] KF_HIGH_MOTION_MINQ = new int[256];

    /**
     * The best golden frame quantizer of each worst quantizer for low motion ({@code arfgf_low_motion_minq}).
     */
    private static final int[] ARFGF_LOW_MOTION_MINQ = new int[256];

    /**
     * The best golden frame quantizer of each worst quantizer for high motion ({@code arfgf_high_motion_minq}).
     */
    private static final int[] ARFGF_HIGH_MOTION_MINQ = new int[256];

    /**
     * The best quantizer of an ordinary frame for each worst quantizer ({@code inter_minq}).
     */
    private static final int[] INTER_MINQ = new int[256];

    static {
        for (var i = 0; i < 256; i++) {
            var maxq = Vp9EncTables.qindexToQ(i);
            KF_LOW_MOTION_MINQ[i] = minqIndex(maxq, 0.000001, -0.0004, 0.150);
            KF_HIGH_MOTION_MINQ[i] = minqIndex(maxq, 0.0000021, -0.00125, 0.45);
            ARFGF_LOW_MOTION_MINQ[i] = minqIndex(maxq, 0.0000015, -0.0009, 0.30);
            ARFGF_HIGH_MOTION_MINQ[i] = minqIndex(maxq, 0.0000021, -0.00125, 0.55);
            INTER_MINQ[i] = minqIndex(maxq, 0.00000271, -0.00113, 0.70);
        }
    }

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The number of 16x16 macroblocks of a picture.
     */
    private final int mbs;

    /**
     * The number of macroblock rows of a picture.
     */
    private final int mbRows;

    /**
     * The rate control mode: {@link #MODE_VBR}, {@link #MODE_CQ} or {@link #MODE_Q}.
     */
    private int rcMode = MODE_VBR;

    /**
     * The target bitrate in bits per second.
     */
    private long targetBandwidth = 1_000_000;

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
    private int worstQuality = 255;

    /**
     * The quantizer index of the quality level.
     */
    private int cqLevel = 40;

    /**
     * The largest key frame interval.
     */
    private int keyFreq = Integer.MAX_VALUE / 4;

    /**
     * The lookahead depth in frames.
     */
    private int lagInFrames = 25;

    /**
     * The good-quality speed, which scales the worst quantizer estimate.
     */
    private int speed = 1;

    /**
     * Whether alternate references are coded.
     */
    private boolean enableAltRef = true;

    /**
     * The undershoot percentage that extends the quantizer range ({@code under_shoot_pct}).
     */
    private int underShootPct = 25;

    /**
     * The overshoot percentage that extends the quantizer range ({@code over_shoot_pct}).
     */
    private int overShootPct = 25;

    /**
     * The exponent percentage of a frame's error in its score ({@code two_pass_vbrbias}).
     */
    private int vbrBias = 50;

    /**
     * The smallest frame target as a percentage of the average ({@code two_pass_vbrmin_section}).
     */
    private int vbrMinSection;

    /**
     * The largest frame target as a percentage of the average ({@code two_pass_vbrmax_section}).
     */
    private int vbrMaxSection = 2000;

    /**
     * The first-pass records, {@link Vp9FirstPassAnalyzer#FIELDS} values per frame in display
     * order.
     */
    private double[] stats = new double[64 * FIELDS];

    /**
     * The number of frames whose records {@link #stats} holds.
     */
    private int statsCount;

    /**
     * Whether {@link #stats} holds every frame of the clip, as in a two-pass encode or once the
     * stream is being flushed.
     */
    private boolean complete;

    /**
     * Whether the planning runs on the lookahead's records rather than on the whole clip's.
     */
    private boolean streaming = true;

    /**
     * The sum of the records of every known frame ({@code total_stats}).
     */
    private final double[] totalStats = new double[FIELDS];

    /**
     * The sum of the records of the frames not yet shown ({@code total_left_stats}).
     */
    private final double[] totalLeftStats = new double[FIELDS];

    /**
     * The mean raw score of the frames, the midpoint of the normalized scores ({@code mean_mod_score}).
     */
    private double meanModScore = 1.0;

    /**
     * The sum of the normalized scores of the frames not yet planned ({@code normalized_score_left}).
     */
    private double normalizedScoreLeft;

    /**
     * The bits left for the frames of a two-pass encode not yet coded ({@code bits_left}).
     */
    private long bitsLeft;

    /**
     * The bits left for the current key frame group ({@code kf_group_bits}).
     */
    private long kfGroupBits;

    /**
     * The score left of the current key frame group's frames ({@code kf_group_error_left}).
     */
    private double kfGroupErrorLeft;

    /**
     * The correction of the bits per macroblock of the worst quantizer estimate from recent groups ({@code
     * bpm_factor}).
     */
    private double bpmFactor = 1.0;

    /**
     * The bits the frames of the current group were given ({@code rolling_arf_group_target_bits}).
     */
    private long rollingArfGroupTargetBits = 1;

    /**
     * The bits the frames of the current group took ({@code rolling_arf_group_actual_bits}).
     */
    private long rollingArfGroupActualBits = 1;

    /**
     * The zero-motion percentage of the current key frame group ({@code kf_zeromotion_pct}).
     */
    private int kfZeromotionPct = 100;

    /**
     * The zero-motion percentage of the previous key frame group ({@code last_kfgroup_zeromotion_pct}).
     */
    private int lastKfgroupZeromotionPct = 100;

    /**
     * The lowering of the best quantizer that corrects an undershoot ({@code extend_minq}).
     */
    private int extendMinq;

    /**
     * The raising of the worst quantizer that corrects an overshoot ({@code extend_maxq}).
     */
    private int extendMaxq;

    /**
     * The fast lowering of the best quantizer that spends a large undershoot ({@code extend_minq_fast}).
     */
    private int extendMinqFast;

    /**
     * The worst quantizer of the current group ({@code active_worst_quality}).
     */
    private int activeWorstQuality;

    /**
     * The adjustment of the temporal filter strength of the current group ({@code arnr_strength_adjustment}).
     */
    private int arnrStrengthAdjustment;

    /**
     * The smooth share of the current frame ({@code mb_smooth_pct}).
     */
    private double mbSmoothPct;

    /**
     * The quantizer index each alternate reference layer was last coded at ({@code last_qindex_of_arf_layer}).
     */
    private final int[] lastQindexOfArfLayer = new int[MAX_ARF_LAYERS];

    /**
     * The reference error per macroblock of a key frame's boost, by picture size ({@code kf_err_per_mb}).
     */
    private final double kfErrPerMb;

    /**
     * The update type of each step of the current group.
     */
    private final int[] updateType = new int[MAX_STATIC_GF_GROUP_LENGTH + 2];

    /**
     * The rate correction level of each step of the current group.
     */
    private final int[] rfLevel = new int[MAX_STATIC_GF_GROUP_LENGTH + 2];

    /**
     * The bits of each step of the current group.
     */
    private final int[] bitAllocation = new int[MAX_STATIC_GF_GROUP_LENGTH + 2];

    /**
     * The distance from each step's shown frame to its source, nonzero for an alternate reference.
     */
    private final int[] arfSrcOffset = new int[MAX_STATIC_GF_GROUP_LENGTH + 2];

    /**
     * The layer of each step of the current group.
     */
    private final int[] layerDepth = new int[MAX_STATIC_GF_GROUP_LENGTH + 2];

    /**
     * The number of steps of the current group.
     */
    private int gfGroupSize;

    /**
     * The current step of the current group.
     */
    private int gfIndex;

    /**
     * The deepest layer of the current group.
     */
    private int maxLayerDepth;

    /**
     * The average quantizer index of recent key frames and ordinary frames ({@code avg_frame_qindex}).
     */
    private final int[] avgFrameQindex = new int[2];

    /**
     * The quantizer index of the last key frame and ordinary frame ({@code last_q}).
     */
    private final int[] lastQ = new int[2];

    /**
     * The quantizer index of the last boosted frame ({@code last_boosted_qindex}).
     */
    private int lastBoostedQindex;

    /**
     * The quantizer index of the last key frame ({@code last_kf_qindex}).
     */
    private int lastKfQindex;

    /**
     * The learned correction of the bits per macroblock of each rate correction level ({@code
     * rate_correction_factors}).
     */
    private final double[] rateCorrectionFactors = {1.0, 1.0, 1.0, 1.0, 1.0};

    /**
     * Whether each level's correction was adjusted once, after which adjustments are damped ({@code
     * damped_adjustment}).
     */
    private final boolean[] dampedAdjustment = new boolean[5];

    /**
     * The direction of the last size miss ({@code rc_1_frame}).
     */
    private int rc1Frame;

    /**
     * The direction of the size miss before it ({@code rc_2_frame}).
     */
    private int rc2Frame;

    /**
     * The average bits per frame ({@code avg_frame_bandwidth}).
     */
    private int avgFrameBandwidth;

    /**
     * The smallest frame target ({@code min_frame_bandwidth}).
     */
    private int minFrameBandwidth;

    /**
     * The largest frame target ({@code max_frame_bandwidth}).
     */
    private int maxFrameBandwidth;

    /**
     * The bit target of the current step ({@code this_frame_target}).
     */
    private int thisFrameTarget;

    /**
     * The planned bits of the current step before the correction of the bits off target ({@code
     * base_frame_target}).
     */
    private int baseFrameTarget;

    /**
     * The size of the current step's last coding in bits ({@code projected_frame_size}).
     */
    private int projectedFrameSize;

    /**
     * The number of frames until the next key frame ({@code frames_to_key}).
     */
    private int framesToKey;

    /**
     * The number of frames since the last key frame ({@code frames_since_key}).
     */
    private int framesSinceKey = 8;

    /**
     * The number of shown frames until the next golden frame group ({@code frames_till_gf_update_due}).
     */
    private int framesTillGfUpdateDue;

    /**
     * The number of frames the current group shows ({@code baseline_gf_interval}).
     */
    private int baselineGfInterval;

    /**
     * The shortest golden frame interval ({@code min_gf_interval}).
     */
    private int minGfInterval;

    /**
     * The longest golden frame interval ({@code max_gf_interval}).
     */
    private int maxGfInterval;

    /**
     * The longest golden frame interval of a static scene ({@code static_scene_max_gf_interval}).
     */
    private int staticSceneMaxGfInterval = MAX_STATIC_GF_GROUP_LENGTH;

    /**
     * Whether the current group's alternate reference is still to be coded ({@code source_alt_ref_pending}).
     */
    private boolean sourceAltRefPending;

    /**
     * Whether the alternate reference holds a frame ahead in display order ({@code source_alt_ref_active}).
     */
    private boolean sourceAltRefActive;

    /**
     * Whether the current step codes the source of the alternate reference again ({@code
     * is_src_frame_alt_ref}).
     */
    private boolean srcFrameAltRef;

    /**
     * The boost of the current key frame ({@code kf_boost}).
     */
    private int kfBoost;

    /**
     * The boost of the current golden frame group ({@code gfu_boost}).
     */
    private int gfuBoost;

    /**
     * Whether the current key frame was placed by the key frame interval ({@code this_key_frame_forced}).
     */
    private boolean thisKeyFrameForced;

    /**
     * Whether the next key frame is placed by the key frame interval ({@code next_key_frame_forced}).
     */
    private boolean nextKeyFrameForced;

    /**
     * Whether the current group was cut short by the next key frame ({@code constrained_gf_group}).
     */
    private boolean constrainedGfGroup;

    /**
     * The share of the alternate reference's quality boost kept ({@code
     * arf_active_best_quality_adjustment_factor}).
     */
    private double arfActiveBestQualityAdjustmentFactor = 1.0;

    /**
     * Whether the alternate reference's quantizer moves to the low motion table (-1), the high motion table (1)
     * or neither (0) ({@code arf_increase_active_best_quality}).
     */
    private int arfIncreaseActiveBestQuality;

    /**
     * The bits spent below the targets so far, negative for an overspend ({@code vbr_bits_off_target}).
     */
    private long vbrBitsOffTarget;

    /**
     * The bits of large undershoots still to be spent quickly ({@code vbr_bits_off_target_fast}).
     */
    private long vbrBitsOffTargetFast;

    /**
     * The bits off target as a percentage of the bits spent ({@code rate_error_estimate}).
     */
    private int rateErrorEstimate;

    /**
     * The rolling average of the frame targets ({@code rolling_target_bits}).
     */
    private int rollingTargetBits;

    /**
     * The rolling average of the frame sizes ({@code rolling_actual_bits}).
     */
    private int rollingActualBits;

    /**
     * The bits of every coded frame ({@code total_actual_bits}).
     */
    private long totalActualBits;

    /**
     * The average bits of every shown frame ({@code total_target_bits}).
     */
    private long totalTargetBits;

    /**
     * The number of shown frames since the golden reference was refreshed ({@code frames_since_golden}).
     */
    private int framesSinceGolden;

    /**
     * The number of steps coded.
     */
    private int currentFrame;

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
     * The best quantizer the current step may take ({@code bottom_index}).
     */
    private int bottomIndex;

    /**
     * The worst quantizer the current step may take ({@code top_index}).
     */
    private int topIndex;

    /**
     * The quantizer index of the current step's last coding.
     */
    private int frameQindex;

    /**
     * The quantizer index the current step was first coded at.
     */
    private int frameQ;

    /**
     * The lower bound of the recode's quantizer search ({@code q_low}).
     */
    private int qLow;

    /**
     * The upper bound of the recode's quantizer search ({@code q_high}).
     */
    private int qHigh;

    /**
     * The size below which the current step is coded again ({@code frame_under_shoot_limit}).
     */
    private int frameUnderShootLimit;

    /**
     * The size above which the current step is coded again ({@code frame_over_shoot_limit}).
     */
    private int frameOverShootLimit;

    /**
     * Whether a coding of the current step overshot.
     */
    private boolean overshootSeen;

    /**
     * Whether a coding of the current step undershot.
     */
    private boolean undershootSeen;

    /**
     * The number of recodes of the current step ({@code loop_at_this_size}).
     */
    private int loopAtThisSize;

    /**
     * The frames the speed codes again: {@link Vp9GoodSpeed#RECODE_NONE} to {@link Vp9GoodSpeed#RECODE_FIRST}.
     */
    private int recodeLoop = Vp9GoodSpeed.RECODE_FIRST;

    /**
     * The undershoot tolerated before a recode, as a percentage of the target.
     */
    private int recodeToleranceLow = 12;

    /**
     * The overshoot tolerated before a recode, as a percentage of the target.
     */
    private int recodeToleranceHigh = 25;

    /**
     * Creates the rate control of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     */
    Vp9GoodRateControl(int width, int height) {
        this.width = width;
        this.height = height;
        var miRows = (height + 7) >> 3;
        var miCols = (width + 7) >> 3;
        this.mbRows = (miRows + 1) >> 1;
        this.mbs = mbRows * ((miCols + 1) >> 1);
        var area = width * height;
        this.kfErrPerMb = area < 1280 * 720 ? 2000.0 : area < 1920 * 1080 ? 500.0 : 250.0;
    }

    /**
     * Returns the quantizer index whose step meets a cubic of a maximum step ({@code get_minq_index}).
     *
     * @param maxq the maximum step
     * @param x3 the cubic coefficient
     * @param x2 the quadratic coefficient
     * @param x1 the linear coefficient
     * @return the index
     */
    private static int minqIndex(double maxq, double x3, double x2, double x1) {
        var target = Math.min(((x3 * maxq + x2) * maxq + x1) * maxq, maxq);
        if (target <= 2.0) {
            return 0;
        }
        for (var i = 0; i < 256; i++) {
            if (target <= Vp9EncTables.qindexToQ(i)) {
                return i;
            }
        }
        return 255;
    }

    /**
     * Configures the rate control before the first frame.
     *
     * @param mode {@link #MODE_VBR}, {@link #MODE_CQ} or {@link #MODE_Q}
     * @param bitrate the target bitrate of the variable bitrate modes, in bits per second
     * @param frameRate the frame rate
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param cqQindex the quantizer index of the quality level of {@link #MODE_CQ} and
     * {@link #MODE_Q}
     * @param keyInterval the largest key frame interval, 0 for none
     * @param lookahead the lookahead depth in frames, which bounds the alternate references
     * @param altRef whether alternate references are coded
     * @param speedLevel the good-quality speed, from 0 to 4
     */
    void configure(int mode, long bitrate, double frameRate, int minQindex, int maxQindex, int cqQindex,
                   int keyInterval, int lookahead, boolean altRef, int speedLevel) {
        this.rcMode = mode;
        this.targetBandwidth = bitrate;
        this.framerate = frameRate;
        this.bestQuality = minQindex;
        this.worstQuality = maxQindex;
        this.cqLevel = Math.clamp(cqQindex, minQindex, maxQindex);
        this.keyFreq = keyInterval > 0 ? keyInterval : Integer.MAX_VALUE / 4;
        this.lagInFrames = lookahead;
        this.enableAltRef = altRef && lookahead > 0;
        this.speed = speedLevel;
        avgFrameQindex[0] = (worstQuality + bestQuality) / 2;
        avgFrameQindex[1] = (worstQuality + bestQuality) / 2;
        lastQ[0] = bestQuality;
        lastQ[1] = worstQuality;
        activeWorstQuality = mode == MODE_Q ? cqLevel : worstQuality;
        updateFramerate(frameRate);
        rollingTargetBits = avgFrameBandwidth;
        rollingActualBits = avgFrameBandwidth;
        baselineGfInterval = (minGfInterval + maxGfInterval) / 2;
    }

    /**
     * Changes the bitrate, quantizer window and quality level of a running stream; the next
     * frame's target follows them.
     *
     * @param bitrate the target bitrate in bits per second
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param cqQindex the quantizer index of the quality level
     */
    void retarget(long bitrate, int minQindex, int maxQindex, int cqQindex) {
        targetBandwidth = bitrate;
        bestQuality = minQindex;
        worstQuality = maxQindex;
        cqLevel = Math.clamp(cqQindex, minQindex, maxQindex);
        activeWorstQuality = Math.clamp(activeWorstQuality, bestQuality, worstQuality);
        updateFramerate(framerate);
    }

    /**
     * Sets the frame rate the per-frame bandwidth follows ({@code vp9_rc_update_framerate}).
     *
     * @param frameRate the frame rate
     */
    private void updateFramerate(double frameRate) {
        framerate = frameRate < 0.1 ? 30 : frameRate;
        avgFrameBandwidth = (int) Math.min(Integer.MAX_VALUE, Math.round(targetBandwidth / framerate));
        minFrameBandwidth = (int) Math.max(Math.min((long) avgFrameBandwidth * vbrMinSection / 100, Integer.MAX_VALUE),
                FRAME_OVERHEAD_BITS);
        var vbrMaxBits = (int) Math.min((long) avgFrameBandwidth * vbrMaxSection / 100, Integer.MAX_VALUE);
        maxFrameBandwidth = Math.max(Math.max(mbs * MAX_MB_RATE, MAXRATE_1080P), vbrMaxBits);
        var safe = 3840 * 2160 * 20.0;
        var factor = (double) width * height * framerate;
        var minInterval = Math.clamp((int) Math.round(framerate * 0.125), MIN_GF_INTERVAL, MAX_GF_INTERVAL);
        if (factor > safe) {
            minInterval = Math.max(minInterval, (int) Math.round(MIN_GF_INTERVAL * factor / safe));
        }
        var maxInterval = Math.min(MAX_GF_INTERVAL, (int) Math.round(framerate * 0.75));
        maxInterval += maxInterval & 1;
        maxInterval = Math.max(maxInterval, minInterval);
        maxGfInterval = Math.min(maxInterval, staticSceneMaxGfInterval);
        minGfInterval = Math.min(minInterval, maxGfInterval);
    }

    /**
     * Sets the speed features the recode loop follows; a one-pass encode codes every frame once.
     *
     * @param sf the speed features of the frame being coded
     */
    void setRecode(Vp9GoodSpeed sf) {
        recodeLoop = streaming ? Vp9GoodSpeed.RECODE_NONE : sf.recodeLoop;
        recodeToleranceLow = sf.recodeToleranceLow;
        recodeToleranceHigh = sf.recodeToleranceHigh;
    }

    /**
     * Installs the records of the whole clip, for a two-pass encode ({@code vp9_init_second_pass}).
     *
     * @param records the records, {@link Vp9FirstPassAnalyzer#FIELDS} values per frame
     * @param frames the number of frames
     */
    void setClipStats(double[] records, int frames) {
        stats = records.clone();
        statsCount = frames;
        complete = true;
        streaming = false;
        java.util.Arrays.fill(totalStats, 0);
        for (var i = 0; i < frames; i++) {
            accumulate(totalStats, i);
        }
        System.arraycopy(totalStats, 0, totalLeftStats, 0, FIELDS);
        var avErr = distributionAvErr();
        var modifiedScoreTotal = 0.0;
        for (var i = 0; i < frames; i++) {
            modifiedScoreTotal += modFrameScore(i, avErr);
        }
        meanModScore = modifiedScoreTotal / divideCheck(totalStats[COUNT]);
        var normalized = 0.0;
        for (var i = 0; i < frames; i++) {
            normalized += normFrameScore(i, avErr);
        }
        normalizedScoreLeft = normalized;
        if (totalStats[DURATION] > 0) {
            updateFramerate(10_000_000.0 * totalStats[COUNT] / totalStats[DURATION]);
        }
        bitsLeft = (long) (totalStats[DURATION] * targetBandwidth / 10_000_000.0);
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
    }

    /**
     * Marks the records as complete: no frame follows the last one added.
     */
    void finishStats() {
        complete = true;
    }

    /**
     * Adds a frame's record to a sum.
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
     * Returns the number of frames the planning may look at.
     *
     * @return the count
     */
    private int numFrames() {
        return statsCount;
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
     * Returns the share of a frame not taken by formatting bars or still areas
     * ({@code calculate_active_area}).
     *
     * @param frame the display index
     * @return the share, from 0.5 to 1
     */
    private double activeArea(int frame) {
        var pct = 1.0 - (s(frame, INTRA_SKIP_PCT) / 2 + (s(frame, INACTIVE_ZONE_ROWS) * 2) / mbRows);
        return Math.clamp(pct, MIN_ACTIVE_AREA, MAX_ACTIVE_AREA);
    }

    /**
     * Returns the weighted average error per frame of the known frames ({@code get_distribution_av_err}).
     *
     * @return the error
     */
    private double distributionAvErr() {
        var count = Math.max(totalStats[COUNT], 1e-6);
        var avWeight = totalStats[WEIGHT] / count;
        return totalStats[CODED_ERROR] * avWeight / count;
    }

    /**
     * Returns a frame's raw score for spreading the bits ({@code calculate_mod_frame_score}).
     *
     * @param frame the display index
     * @param avErr the average error
     * @return the score
     */
    private double modFrameScore(int frame, double avErr) {
        var score = avErr * StrictMath.pow(s(frame, CODED_ERROR) * s(frame, WEIGHT) / divideCheck(avErr), vbrBias / 100.0);
        return score * StrictMath.pow(activeArea(frame), ACT_AREA_CORRECTION);
    }

    /**
     * Returns a frame's score normalized to the clip's mean and clamped to the section limits
     * ({@code calc_norm_frame_score}).
     *
     * @param frame the display index
     * @param avErr the average error
     * @return the score
     */
    private double normFrameScore(int frame, double avErr) {
        var score = modFrameScore(frame, avErr) / divideCheck(meanModScore);
        return Math.clamp(score, vbrMinSection / 100.0, vbrMaxSection / 100.0);
    }

    /**
     * Recomputes the mean score from every frame known, for a one-pass encode.
     */
    private void refreshMeanScore() {
        if (!streaming) {
            return;
        }
        var avErr = distributionAvErr();
        var total = 0.0;
        for (var i = 0; i < statsCount; i++) {
            total += modFrameScore(i, avErr);
        }
        meanModScore = statsCount > 0 ? total / statsCount : 1.0;
    }

    /**
     * Returns the largest target of a frame ({@code frame_max_bits}).
     *
     * @return the bits
     */
    private int frameMaxBits() {
        var bits = (long) avgFrameBandwidth * vbrMaxSection / 100;
        return (int) Math.clamp(bits, 0, maxFrameBandwidth);
    }

    /**
     * Returns how much the prediction from the second reference decays over a frame ({@code
     * get_sr_decay_rate}).
     *
     * @param frame the display index
     * @return the decay rate, at least {@link #DEFAULT_DECAY_LIMIT}
     */
    private double srDecayRate(int frame) {
        var srDiff = s(frame, SR_CODED_ERROR) - s(frame, CODED_ERROR);
        var decay = 1.0;
        if (srDiff > LOW_SR_DIFF_TRHESH) {
            var srDiffPart = (srDiff * 0.25) / s(frame, INTRA_ERROR);
            var modifiedPctInter = s(frame, PCNT_INTER);
            if (s(frame, CODED_ERROR) > LOW_CODED_ERR_PER_MB
                    && s(frame, INTRA_ERROR) / divideCheck(s(frame, CODED_ERROR)) < NCOUNT_FRAME_II_THRESH) {
                modifiedPctInter = s(frame, PCNT_INTER) + s(frame, PCNT_INTRA_LOW) - s(frame, PCNT_NEUTRAL);
            }
            var modifiedPcntIntra = 100 * (1.0 - modifiedPctInter);
            decay = 1.0 - srDiffPart - INTRA_PART * modifiedPcntIntra;
        }
        return Math.max(decay, DEFAULT_DECAY_LIMIT);
    }

    /**
     * Returns the share of a frame that stays still, bounded by its second reference decay ({@code
     * get_zero_motion_factor}).
     *
     * @param frame the display index
     * @return the factor
     */
    private double zeroMotionFactor(int frame) {
        var zeroMotionPct = s(frame, PCNT_INTER) - s(frame, PCNT_MOTION);
        return Math.min(srDecayRate(frame), zeroMotionPct);
    }

    /**
     * Returns how much the prediction quality decays over a frame ({@code get_prediction_decay_rate}).
     *
     * @param frame the display index
     * @return the decay rate, from 0 to 1
     */
    private double predictionDecayRate(int frame) {
        var srDecay = srDecayRate(frame);
        var zm = Math.clamp(DEFAULT_ZM_FACTOR * (s(frame, PCNT_INTER) - s(frame, PCNT_MOTION)), 0.0, 1.0);
        return Math.max(zm, srDecay + (1.0 - srDecay) * zm);
    }

    /**
     * Returns whether a run of frames starting at a frame is still, as after a fade to a static scene ({@code
     * check_transition_to_still}).
     *
     * @param showIdx the first frame
     * @param stillInterval the number of frames that must be still
     * @return whether they all are
     */
    private boolean checkTransitionToStill(int showIdx, int stillInterval) {
        if (showIdx + stillInterval > numFrames()) {
            return false;
        }
        var j = 0;
        for (; j < stillInterval; j++) {
            if (s(showIdx + j, PCNT_INTER) - s(showIdx + j, PCNT_MOTION) < 0.999) {
                break;
            }
        }
        return j == stillInterval;
    }

    /**
     * Returns whether a frame predicts better from the second reference than from the last, as after a flash
     * ({@code detect_flash_from_frame_stats}).
     *
     * @param frame the display index, possibly unknown
     * @return whether a flash is detected
     */
    private boolean detectFlash(int frame) {
        if (!has(frame)) {
            return false;
        }
        return s(frame, SR_CODED_ERROR) < s(frame, CODED_ERROR)
                || (s(frame, PCNT_SECOND_REF) > s(frame, PCNT_INTER) && s(frame, PCNT_SECOND_REF) >= 0.5);
    }

    /**
     * The motion accumulators of a boost or group length scan: the last frame's in-out balance,
     * its sum, the sum of its magnitude and the motion uniformity.
     */
    private final double[] motion = new double[4];

    /**
     * Adds a frame's motion to the scan accumulators {@link #motion} ({@code accumulate_frame_motion_stats}).
     *
     * @param frame the display index
     */
    private void accumulateMotion(int frame) {
        var pct = s(frame, PCNT_MOTION);
        motion[0] = s(frame, MV_IN_OUT_COUNT) * pct;
        motion[1] += motion[0];
        motion[2] += Math.abs(motion[0]);
        if (pct > 0.05) {
            var mvrRatio = Math.abs(s(frame, MVR_ABS)) / divideCheck(Math.abs(s(frame, MVR)));
            var mvcRatio = Math.abs(s(frame, MVC_ABS)) / divideCheck(Math.abs(s(frame, MVC)));
            motion[3] += pct * Math.min(mvrRatio, s(frame, MVR_ABS));
            motion[3] += pct * Math.min(mvcRatio, s(frame, MVC_ABS));
        }
    }

    /**
     * Returns the boost one frame adds to a golden frame or alternate reference ({@code calc_frame_boost}).
     *
     * @param frame the display index
     * @param avgQindex the average quantizer index of recent frames
     * @param mvInOut the frame's zoom measure
     * @return the boost
     */
    private double frameBoost(int frame, int avgQindex, double mvInOut) {
        var lq = Vp9EncTables.qindexToQ(avgQindex);
        var correction = Math.min(0.5 + lq * 0.015, 1.5);
        var boost = (BASELINE_ERR_PER_MB * activeArea(frame)) / divideCheck(s(frame, CODED_ERROR));
        if (mvInOut > 0.0) {
            boost += boost * (mvInOut * 2.0);
        }
        boost *= correction;
        return Math.min(boost, GF_MAX_FRAME_BOOST * correction);
    }

    /**
     * Returns the boost one frame adds to a key frame and accumulates its second reference error ({@code
     * calc_kf_frame_boost}).
     *
     * @param frame the display index
     * @param srAccumulator the accumulated second reference error excess, updated
     * @param zmFactor the zero-motion factor, from 0.75 to 1.25
     * @return the boost
     */
    private double kfFrameBoost(int frame, double[] srAccumulator, double zmFactor) {
        var lq = Vp9EncTables.qindexToQ(avgFrameQindex[1]);
        var correction = Math.min(0.50 + lq * 0.015, 2.00);
        var boost = (kfErrPerMb * activeArea(frame)) / divideCheck(s(frame, CODED_ERROR) + srAccumulator[0]);
        srAccumulator[0] += s(frame, SR_CODED_ERROR) - s(frame, CODED_ERROR);
        srAccumulator[0] = Math.max(0.0, srAccumulator[0]);
        boost = (boost + KF_MIN_FRAME_BOOST) * correction;
        var maxBoost = KF_MAX_FRAME_BOOST * zmFactor * correction;
        return Math.min(boost, maxBoost);
    }

    /**
     * Returns the boost of an alternate reference from the frames after and before it ({@code
     * compute_arf_boost}).
     *
     * @param arfShowIdx the display index of the alternate reference
     * @param fFrames the number of frames after it
     * @param bFrames the number of frames before it
     * @param avgQindex the average quantizer index of recent frames
     * @return the boost
     */
    private int computeArfBoost(int arfShowIdx, int fFrames, int bFrames, int avgQindex) {
        var boostScore = 0.0;
        var decay = 1.0;
        java.util.Arrays.fill(motion, 0);
        for (var i = 0; i < fFrames; i++) {
            var frame = arfShowIdx + i;
            if (!has(frame)) {
                break;
            }
            accumulateMotion(frame);
            var flash = detectFlash(frame) || detectFlash(frame + 1);
            if (!flash) {
                decay = Math.max(decay * predictionDecayRate(frame), MIN_DECAY_FACTOR);
            }
            boostScore += decay * frameBoost(frame, avgQindex, motion[0]);
        }
        var arfBoost = (int) boostScore;
        boostScore = 0.0;
        decay = 1.0;
        java.util.Arrays.fill(motion, 0);
        for (var i = -1; i >= -bFrames; i--) {
            var frame = arfShowIdx + i;
            if (!has(frame)) {
                break;
            }
            accumulateMotion(frame);
            var flash = detectFlash(frame) || detectFlash(frame + 1);
            if (!flash) {
                decay = Math.max(decay * predictionDecayRate(frame), MIN_DECAY_FACTOR);
            }
            boostScore += decay * frameBoost(frame, avgQindex, motion[0]);
        }
        arfBoost += (int) boostScore;
        arfBoost = Math.max(arfBoost, (bFrames + fFrames) * 40);
        return Math.max(arfBoost, MIN_ARF_GF_BOOST);
    }

    /**
     * Returns the bits of a golden frame group ({@code calculate_total_gf_group_bits}).
     *
     * @param gfGroupErr the sum of the scores of the group's frames
     * @param keyOrArf whether the group starts with a key frame or an overlay, whose bits are counted elsewhere
     * @return the bits
     */
    private long totalGfGroupBits(double gfGroupErr, boolean keyOrArf) {
        var maxBits = frameMaxBits();
        var gopFrames = baselineGfInterval + (sourceAltRefPending ? 1 : 0) - (keyOrArf ? 1 : 0);
        long total;
        if (streaming) {
            total = (long) (avgFrameBandwidth * gfGroupErr);
        } else if (kfGroupBits > 0 && kfGroupErrorLeft > 0.0) {
            var keyFrameInterval = framesSinceKey + framesToKey;
            var distance = framesToKey - (baselineGfInterval + (sourceAltRefPending ? 1 : 0));
            var normalize = baselineGfInterval / 16.0;
            total = (long) (kfGroupBits * (gfGroupErr / kfGroupErrorLeft));
            total += (long) ((double) distance / keyFrameInterval * avgFrameBandwidth * normalize);
            total = Math.clamp(total, 0, kfGroupBits);
        } else {
            total = 0;
        }
        return Math.min(Math.max(total, 0), (long) maxBits * Math.max(gopFrames, 1));
    }

    /**
     * Returns the extra bits of a boosted frame among ordinary frames ({@code calculate_boost_bits}).
     *
     * @param frameCount the number of ordinary frames
     * @param boost the boosted frame's boost
     * @param totalGroupBits the bits of the frames together
     * @return the extra bits
     */
    private static int boostBits(int frameCount, int boost, long totalGroupBits) {
        if (boost == 0 || totalGroupBits <= 0 || frameCount < 0) {
            return 0;
        }
        var chunks = frameCount * NORMAL_BOOST + boost;
        if (boost > 1023) {
            var divisor = boost >> 10;
            boost /= divisor;
            chunks /= divisor;
        }
        return (int) Math.max(Math.min((long) boost * totalGroupBits / chunks, Integer.MAX_VALUE), 0);
    }

    /**
     * Returns the worst quantizer that codes a section within its bits ({@code get_twopass_worst_quality}).
     *
     * @param sectionErr the mean coded error of the section's frames
     * @param inactiveZone the inactive share of the frames
     * @param sectionNoise the mean noise energy of the frames
     * @param sectionTarget the bits per frame
     * @return the quantizer index
     */
    private int twopassWorstQuality(double sectionErr, double inactiveZone, double sectionNoise, int sectionTarget) {
        var targetRate = clampPframeTarget(sectionTarget);
        var noiseFactor = Math.clamp(StrictMath.pow(sectionNoise / SECTION_NOISE_DEF, 0.5), NOISE_FACTOR_MIN, NOISE_FACTOR_MAX);
        inactiveZone = Math.clamp(inactiveZone, 0.0, 1.0);
        if (targetRate <= 0) {
            return worstQuality;
        }
        var activePct = Math.max(0.01, 1.0 - inactiveZone);
        var activeMbs = (int) Math.max(1, mbs * activePct);
        var avErrPerMb = sectionErr / activePct;
        var speedTerm = 1.0 + 0.04 * speed;
        var targetNorm = ((long) targetRate << BPER_MB_NORMBITS) / activeMbs;
        var lastGroupRateErr = (double) rollingArfGroupActualBits / divideCheck(rollingArfGroupTargetBits);
        lastGroupRateErr = Math.clamp(lastGroupRateErr, 0.25, 4.0);
        bpmFactor *= (3.0 + lastGroupRateErr) / 4.0;
        bpmFactor = Math.clamp(bpmFactor, 0.25, 4.0);
        var q = bestQuality;
        for (; q < worstQuality; q++) {
            var factor = correctionFactor(avErrPerMb, wqErrDivisor(), q);
            var bitsPerMb = bitsPerMb(false, q, Math.clamp(factor * speedTerm * bpmFactor * noiseFactor,
                    MIN_BPB_FACTOR, MAX_BPB_FACTOR));
            if (bitsPerMb <= targetNorm) {
                break;
            }
        }
        if (rcMode == MODE_CQ) {
            q = Math.max(q, cqLevel);
        }
        return q;
    }

    /**
     * Returns the correction of the bits per macroblock for a section's error at a quantizer ({@code
     * calc_correction_factor}).
     *
     * @param errPerMb the error per macroblock
     * @param errDivisor the error divisor of the picture size
     * @param q the quantizer index
     * @return the factor, from 0.05 to 5
     */
    private static double correctionFactor(double errPerMb, double errDivisor, int q) {
        var errorTerm = errPerMb / divideCheck(errDivisor);
        var index = q >> 5;
        var power = Q_POW_TERM[index] + (Q_POW_TERM[index + 1] - Q_POW_TERM[index]) * (q % 32) / 32.0;
        return Math.clamp(StrictMath.pow(Math.max(errorTerm, 0), power), 0.05, 5.0);
    }

    /**
     * Returns the error divisor of the worst quantizer estimate for the picture size ({@code wq_err_divisor}).
     *
     * @return the divisor
     */
    private double wqErrDivisor() {
        var area = width * height;
        if (area <= 640 * 360) {
            return 115.0;
        } else if (area < 1280 * 720) {
            return 125.0;
        } else if (area <= 1920 * 1080) {
            return 130.0;
        } else if (area < 3840 * 2160) {
            return 150.0;
        }
        return 200.0;
    }

    /**
     * Returns the number of frames of the golden frame group starting at a frame, and whether it
     * takes an alternate reference ({@code get_gop_coding_frame_num}).
     *
     * @param start the group's first frame
     * @param minInterval the smallest group length the break-out tests allow
     * @param maxInterval the largest group length outside static scenes
     * @param useAltRef receives whether to use an alternate reference
     * @return the number of frames
     */
    private int gopCodingFrameNum(int start, int minInterval, int maxInterval, boolean[] useAltRef) {
        var loopDecay = 1.00;
        java.util.Arrays.fill(motion, 0);
        var srAccumulator = 0.0;
        var mvRatioThresh = (height + width) / 4.0;
        var zeroMotion = 1.0;
        useAltRef[0] = true;
        var frames = 0;
        while (frames < staticSceneMaxGfInterval && frames < framesToKey) {
            frames++;
            var next = start + frames;
            if (!has(next)) {
                break;
            }
            var flash = detectFlash(next + 1);
            accumulateMotion(next);
            if (framesSinceKey + frames - 1 > 1) {
                zeroMotion = Math.min(zeroMotion, zeroMotionFactor(next));
            }
            if (!flash) {
                var lastDecay = loopDecay;
                loopDecay = predictionDecayRate(next);
                if (frames > minGfInterval && loopDecay >= 0.999 && lastDecay < 0.9
                        && checkTransitionToStill(start + frames, 5)) {
                    useAltRef[0] = false;
                    break;
                }
                if (frames == 1) {
                    srAccumulator += s(next, CODED_ERROR);
                } else {
                    srAccumulator += s(next, SR_CODED_ERROR) - s(next, CODED_ERROR);
                }
            }
            if (frames >= maxInterval && (zeroMotion < 0.995 || sourceAltRefActive)) {
                break;
            }
            if (frames >= minInterval && framesToKey - frames >= minGfInterval && (frames & 1) != 0 && !flash
                    && (motion[3] > mvRatioThresh || motion[2] > ARF_ABS_ZOOM_THRESH
                    || srAccumulator > s(next, INTRA_ERROR))) {
                break;
            }
        }
        useAltRef[0] &= zeroMotion < 0.995;
        useAltRef[0] &= frames < lagInFrames;
        useAltRef[0] &= frames >= minGfInterval;
        return frames;
    }

    /**
     * Plans the golden frame group starting at a frame and gives its frames their bits
     * ({@code define_gf_group}).
     *
     * @param start the group's first frame
     */
    private void defineGfGroup(int start) {
        var keyOrArf = keyFrame || sourceAltRefActive;
        if (!keyFrame) {
            java.util.Arrays.fill(updateType, 0);
            java.util.Arrays.fill(bitAllocation, 0);
        }
        var intMaxQ = (int) Vp9EncTables.qindexToQ(activeWorstQuality);
        var qTerm = start == 0 ? intMaxQ / 32 : (int) (Vp9EncTables.qindexToQ(lastBoostedQindex) / 6);
        var minInterval = Math.min(minGfInterval + (keyOrArf ? 1 : 0) + Math.min(2, intMaxQ / 200),
                maxGfInterval + (keyOrArf ? 1 : 0));
        var maxInterval = (11 + (keyOrArf ? 1 : 0) + Math.min(5, qTerm)) | 1;
        if (maxInterval < minInterval) {
            maxInterval = minInterval;
        } else {
            maxInterval = Math.min(maxInterval, maxGfInterval + (keyOrArf ? 1 : 0));
        }
        if (maxInterval <= framesToKey && maxInterval >= framesToKey - minGfInterval) {
            maxInterval = framesToKey / 2;
        }
        maxInterval = Math.max(maxInterval, minInterval);
        var useAltRef = new boolean[1];
        var gopFramesCoded = gopCodingFrameNum(start, minInterval, maxInterval, useAltRef);
        var altRef = useAltRef[0] && enableAltRef;
        constrainedGfGroup = gopFramesCoded >= framesToKey;
        if (altRef) {
            var fFrames = framesToKey - gopFramesCoded >= gopFramesCoded - 1 ? gopFramesCoded - 1
                    : Math.max(0, framesToKey - gopFramesCoded);
            var bFrames = gopFramesCoded - 1;
            var arfShowIdx = Math.min(start + gopFramesCoded + 1, numFrames());
            gfuBoost = computeArfBoost(arfShowIdx, fFrames, bFrames, avgFrameQindex[1]);
            sourceAltRefPending = true;
        } else {
            var arfBoost = computeArfBoost(Math.min(start + 1, numFrames()), gopFramesCoded - 1, 0, avgFrameQindex[1]);
            gfuBoost = Math.min(MAX_GF_BOOST, arfBoost);
            sourceAltRefPending = false;
        }
        arfActiveBestQualityAdjustmentFactor = 1.0;
        arfIncreaseActiveBestQuality = 0;
        if (worstQuality > 0) {
            if (framesSinceKey >= framesToKey) {
                arfActiveBestQualityAdjustmentFactor = LAST_ALR_ADJUSTMENT_FACTOR + (1.0 - LAST_ALR_ADJUSTMENT_FACTOR)
                        * (framesToKey - gopFramesCoded)
                        / Math.max(1, (framesToKey + framesSinceKey) / 2 - gopFramesCoded);
                arfIncreaseActiveBestQuality = 1;
            } else if (framesToKey - gopFramesCoded > 0) {
                arfActiveBestQualityAdjustmentFactor = LAST_ALR_ADJUSTMENT_FACTOR + (1.0 - LAST_ALR_ADJUSTMENT_FACTOR)
                        * (framesSinceKey + gopFramesCoded)
                        / Math.max(1, (framesToKey + framesSinceKey) / 2 + gopFramesCoded);
                arfIncreaseActiveBestQuality = -1;
            }
        }
        gfuBoost = Math.min(gfuBoost, gopFramesCoded * 200);
        baselineGfInterval = gopFramesCoded - (sourceAltRefPending ? 1 : 0);
        refreshMeanScore();
        var avErr = distributionAvErr();
        var gfGroupErr = 0.0;
        var rawError = 0.0;
        var noise = 0.0;
        var skipPct = 0.0;
        var inactiveRows = 0.0;
        var inter = 0.0;
        var motionPct = 0.0;
        for (var j = keyOrArf ? 1 : 0; j < gopFramesCoded; j++) {
            var frame = start + j;
            if (!has(frame)) {
                break;
            }
            gfGroupErr += normFrameScore(frame, avErr);
            rawError += s(frame, CODED_ERROR);
            noise += s(frame, FRAME_NOISE_ENERGY);
            skipPct += s(frame, INTRA_SKIP_PCT);
            inactiveRows += s(frame, INACTIVE_ZONE_ROWS);
            inter += s(frame, PCNT_INTER);
            motionPct += s(frame, PCNT_MOTION);
        }
        var gfGroupBits = totalGfGroupBits(gfGroupErr, keyOrArf);
        var gopFrames = baselineGfInterval + (sourceAltRefPending ? 1 : 0) - (keyOrArf ? 1 : 0);
        if (rcMode != MODE_Q && baselineGfInterval > 1 && gopFrames > 0) {
            var perFrame = (int) (gfGroupBits / gopFrames);
            var tmpQ = twopassWorstQuality(rawError / gopFrames,
                    skipPct / gopFrames + (inactiveRows * 2) / (gopFrames * (double) mbRows), noise / gopFrames,
                    perFrame);
            activeWorstQuality = (int) ((tmpQ + activeWorstQuality * (AV_WQ_FACTOR - 1)) / AV_WQ_FACTOR);
        }
        if (baselineGfInterval > 1 && gopFrames > 0) {
            var sectionZeromv = (inter - motionPct) / gopFrames;
            var sectionNoise = noise / gopFrames;
            arnrStrengthAdjustment = 0;
            if (sectionNoise < 150) {
                arnrStrengthAdjustment--;
                if (sectionNoise < 75) {
                    arnrStrengthAdjustment--;
                }
            } else if (sectionNoise > 250) {
                arnrStrengthAdjustment++;
            }
            if (sectionZeromv > 0.50) {
                arnrStrengthAdjustment++;
            }
        } else {
            arnrStrengthAdjustment = 0;
        }
        var gfArfBits = boostBits(baselineGfInterval - 1, gfuBoost, gfGroupBits);
        kfGroupErrorLeft -= gfGroupErr;
        defineGfGroupStructure();
        allocateGfGroupBits(gfGroupBits, gfArfBits);
        rollingArfGroupTargetBits = 0;
        rollingArfGroupActualBits = 0;
    }

    /**
     * Lays out the update types of the group ({@code define_gf_group_structure}).
     */
    private void defineGfGroupStructure() {
        var index = 0;
        if (!keyFrame) {
            if (sourceAltRefActive) {
                updateType[index] = OVERLAY_UPDATE;
                rfLevel[index] = INTER_NORMAL;
                layerDepth[index] = MAX_ARF_LAYERS - 1;
            } else {
                updateType[index] = GF_UPDATE;
                rfLevel[index] = GF_ARF_STD;
                layerDepth[index] = 0;
            }
        } else {
            updateType[index] = KF_UPDATE;
            rfLevel[index] = KF_STD;
            layerDepth[index] = 0;
        }
        arfSrcOffset[index] = 0;
        index++;
        maxLayerDepth = 0;
        if (sourceAltRefPending) {
            updateType[index] = ARF_UPDATE;
            rfLevel[index] = GF_ARF_STD;
            layerDepth[index] = 1;
            arfSrcOffset[index] = baselineGfInterval - 1;
            maxLayerDepth = 1;
            index++;
        }
        var gopFrames = baselineGfInterval - (keyFrame || sourceAltRefPending ? 1 : 0);
        var depth = sourceAltRefPending ? 2 : 1;
        for (var i = 1; i <= gopFrames; i++) {
            updateType[index] = LF_UPDATE;
            arfSrcOffset[index] = 0;
            rfLevel[index] = INTER_NORMAL;
            layerDepth[index] = depth;
            index++;
        }
        maxLayerDepth = Math.max(maxLayerDepth, depth);
        if (sourceAltRefPending) {
            updateType[index] = OVERLAY_UPDATE;
            rfLevel[index] = INTER_NORMAL;
            layerDepth[index] = MAX_ARF_LAYERS - 1;
        } else {
            updateType[index] = GF_UPDATE;
            rfLevel[index] = GF_ARF_STD;
            layerDepth[index] = 0;
        }
        arfSrcOffset[index] = 0;
        gfGroupSize = index;
        gfIndex = 0;
    }

    /**
     * Gives each frame of the group its bits ({@code allocate_gf_group_bits}).
     *
     * @param gfGroupBits the group's bits
     * @param gfArfBits the extra bits of the boosted frame
     */
    private void allocateGfGroupBits(long gfGroupBits, int gfArfBits) {
        var maxBits = frameMaxBits();
        var total = gfGroupBits;
        var index = 0;
        if (!keyFrame) {
            bitAllocation[index] = sourceAltRefActive ? 0 : gfArfBits;
        }
        if (sourceAltRefPending || !keyFrame) {
            total -= gfArfBits;
        }
        index++;
        if (sourceAltRefPending) {
            bitAllocation[index] = gfArfBits;
            index++;
        }
        var midFrameIdx = index + (baselineGfInterval >> 1) - 1;
        var normalFrames = baselineGfInterval - 1;
        var normalFrameBits = (int) (normalFrames > 1 ? total / normalFrames : total);
        var lastFrameReduction = 0;
        for (var i = 0; i < normalFrames; i++) {
            var target = normalFrameBits;
            if (i == normalFrames - 1 && i >= 1) {
                lastFrameReduction = normalFrameBits / 16;
                target -= lastFrameReduction;
            }
            target = (int) Math.clamp(target, 0, Math.min(maxBits, Math.max(total, 0)));
            bitAllocation[index] = target;
            index++;
        }
        if (midFrameIdx >= 0 && midFrameIdx < bitAllocation.length) {
            bitAllocation[midFrameIdx] += lastFrameReduction;
        }
    }

    /**
     * Returns whether a frame is a sharp change between two still scenes ({@code slide_transition}).
     *
     * @param frame the display index, with a known frame before and after it
     * @return whether it is
     */
    private boolean slideTransition(int frame) {
        return s(frame, INTRA_ERROR) < s(frame, CODED_ERROR) * VERY_LOW_II
                && s(frame, CODED_ERROR) > s(frame - 1, CODED_ERROR) * ERROR_SPIKE
                && s(frame, CODED_ERROR) > s(frame + 1, CODED_ERROR) * ERROR_SPIKE;
    }

    /**
     * Returns whether a frame's intra signal changes markedly from its neighbors' ({@code
     * intra_step_transition}).
     *
     * @param frame the display index, with a known frame before and after it
     * @return whether it does
     */
    private boolean intraStepTransition(int frame) {
        var lastPcntIntra = 1.0 - s(frame - 1, PCNT_INTER);
        var thisPcntIntra = 1.0 - s(frame, PCNT_INTER);
        var nextPcntIntra = 1.0 - s(frame + 1, PCNT_INTER);
        var modThisIntra = thisPcntIntra + s(frame, PCNT_NEUTRAL);
        var lastIi = s(frame - 1, INTRA_ERROR) / divideCheck(s(frame - 1, CODED_ERROR));
        var thisIi = s(frame, INTRA_ERROR) / divideCheck(s(frame, CODED_ERROR));
        var nextIi = s(frame + 1, INTRA_ERROR) / divideCheck(s(frame + 1, CODED_ERROR));
        if (thisIi < 2.0 && lastIi > 2.25 && nextIi > 2.25 && thisPcntIntra > 3 * lastPcntIntra
                && thisPcntIntra > 3 * nextPcntIntra && (thisPcntIntra > 0.075 || modThisIntra > 0.85)) {
            return true;
        }
        return thisIi < 1.25 && modThisIntra > 0.85 && thisIi < lastIi * 0.9 && thisIi < nextIi * 0.9;
    }

    /**
     * Returns whether a frame should start a key frame group: a scene cut that predicts the
     * frames after it well ({@code test_candidate_kf}).
     *
     * @param frame the frame, with a known frame before and after it
     * @return whether it is a key frame
     */
    private boolean candidateKf(int frame) {
        if (!has(frame - 1) || !has(frame + 1)) {
            return false;
        }
        var pcntIntra = 1.0 - s(frame, PCNT_INTER);
        if (!(!detectFlash(frame) && !detectFlash(frame + 1) && s(frame, PCNT_SECOND_REF) < SECOND_REF_USAGE_THRESH
                && (s(frame, PCNT_INTER) < VERY_LOW_INTER_THRESH || slideTransition(frame) || intraStepTransition(frame)
                || (s(frame, CODED_ERROR) > s(frame + 1, CODED_ERROR) * 1.2
                && s(frame, CODED_ERROR) > s(frame - 1, CODED_ERROR) * 1.2 && pcntIntra > MIN_INTRA_LEVEL
                && pcntIntra + s(frame, PCNT_NEUTRAL) > 0.5
                && s(frame, INTRA_ERROR) / divideCheck(s(frame, CODED_ERROR)) < KF_II_ERR_THRESHOLD)))) {
            return false;
        }
        var boostScore = 0.0;
        var oldBoost = 0.0;
        var decay = 1.0;
        var i = 0;
        for (; i < 16; i++) {
            var f = frame + 1 + i;
            if (!has(f)) {
                break;
            }
            var nextIi = Math.min(II_FACTOR * s(f, INTRA_ERROR) / divideCheck(s(f, CODED_ERROR)), KF_II_MAX);
            var inter = s(f, PCNT_INTER);
            decay *= inter > 0.85 ? inter : (0.85 + inter) / 2.0;
            boostScore += decay * nextIi;
            if (inter < 0.05 || nextIi < 1.5 || (inter - s(f, PCNT_NEUTRAL) < 0.20 && nextIi < 3.0)
                    || boostScore - oldBoost < 3.0 || s(f, INTRA_ERROR) < V_LOW_INTRA) {
                break;
            }
            oldBoost = boostScore;
            if (f == numFrames() - 1) {
                break;
            }
        }
        return boostScore > 30.0 && i > 3;
    }

    /**
     * Returns the distance from a key frame to the next one ({@code vp9_get_frames_to_next_key}).
     *
     * @param kf the key frame
     * @return the distance
     */
    private int framesToNextKey(int kf) {
        var maxFrames = keyFreq;
        if (complete) {
            maxFrames = Math.min(maxFrames, numFrames() - kf);
        }
        var recentDecay = new double[FRAMES_TO_CHECK_DECAY];
        java.util.Arrays.fill(recentDecay, 1.0);
        var frames = 1;
        while (frames < maxFrames) {
            if (kf + frames + 1 < numFrames()) {
                if (candidateKf(kf + frames)) {
                    break;
                }
                var loopDecay = predictionDecayRate(kf + frames + 1);
                recentDecay[(frames - 1) % FRAMES_TO_CHECK_DECAY] = loopDecay;
                var decay = 1.0;
                for (var d : recentDecay) {
                    decay *= d;
                }
                if (frames - 1 > minGfInterval && loopDecay >= 0.999 && decay < 0.9
                        && checkTransitionToStill(kf + frames, keyFreq - (frames - 1))) {
                    break;
                }
            } else if (!complete) {
                return maxFrames;
            }
            frames++;
        }
        return frames;
    }

    /**
     * Starts a key frame group at a frame and gives the key frame its bits
     * ({@code find_next_key_frame}).
     *
     * @param kf the key frame
     */
    private void findNextKeyFrame(int kf) {
        keyFrame = true;
        framesSinceKey = 0;
        java.util.Arrays.fill(updateType, 0);
        java.util.Arrays.fill(bitAllocation, 0);
        thisKeyFrameForced = nextKeyFrameForced;
        sourceAltRefActive = false;
        framesTillGfUpdateDue = 0;
        kfGroupBits = 0;
        kfGroupErrorLeft = 0.0;
        refreshMeanScore();
        var avErr = distributionAvErr();
        var kfRawErr = s(kf, INTRA_ERROR);
        var kfModErr = normFrameScore(kf, avErr);
        framesToKey = framesToNextKey(kf);
        nextKeyFrameForced = framesToKey >= keyFreq;
        var known = 0;
        var kfGroupErr = 0.0;
        for (var i = 0; i < framesToKey && has(kf + i); i++) {
            kfGroupErr += normFrameScore(kf + i, avErr);
            known++;
        }
        if (streaming && known > 0 && known < framesToKey) {
            kfGroupErr *= (double) framesToKey / known;
        }
        var maxBits = frameMaxBits();
        if (streaming) {
            var window = Math.min(framesToKey, Math.max(known, 1));
            kfGroupBits = (long) (avgFrameBandwidth * kfGroupErr * window / Math.max(framesToKey, 1));
            kfGroupBits = Math.min(kfGroupBits, (long) maxBits * window);
        } else if (bitsLeft > 0 && normalizedScoreLeft > 0.0) {
            kfGroupBits = (long) (bitsLeft * (kfGroupErr / normalizedScoreLeft));
            kfGroupBits = Math.min(kfGroupBits, (long) maxBits * framesToKey);
        }
        kfGroupBits = Math.max(0, kfGroupBits);
        var zeroMotionSum = 0.0;
        var compensableSum = 0.0;
        var scanned = 0;
        for (var i = 0; i < Math.min(MAX_SCAN_FRAMES_FOR_KF_BOOST, framesToKey - 1); i++) {
            var f = kf + 1 + i;
            if (!has(f)) {
                break;
            }
            zeroMotionSum += s(f, PCNT_INTER) - s(f, PCNT_MOTION);
            compensableSum += 1 - s(f, CODED_ERROR) / s(f, INTRA_ERROR);
            scanned++;
        }
        var scanFrames = DEFAULT_SCAN_FRAMES_FOR_KF_BOOST;
        if (scanned >= MIN_SCAN_FRAMES_FOR_KF_BOOST) {
            var zeroAvg = zeroMotionSum / scanned;
            var compAvg = compensableSum / scanned;
            scanFrames = (int) Math.max(64 * zeroAvg - 16, 160 * compAvg - 112);
            scanFrames = Math.clamp(scanFrames, MIN_SCAN_FRAMES_FOR_KF_BOOST, MAX_SCAN_FRAMES_FOR_KF_BOOST);
        }
        var boostScore = 0.0;
        var zeroMotion = 1.0;
        var sr = new double[1];
        var absMvInOut = 0.0;
        for (var i = 0; i < framesToKey - 1; i++) {
            var f = kf + 1 + i;
            if (!has(f)) {
                break;
            }
            if (i <= scanFrames || zeroMotion >= 0.99) {
                if (i > 0) {
                    zeroMotion = Math.min(zeroMotion, zeroMotionFactor(f));
                } else {
                    zeroMotion = s(f, PCNT_INTER) - s(f, PCNT_MOTION);
                }
                var zmFactor = 0.75 + zeroMotion / 2.0;
                if (i < 2) {
                    sr[0] = 0.0;
                }
                var boost = kfFrameBoost(f, sr, zmFactor);
                boostScore += boost;
                absMvInOut += Math.abs(s(f, MV_IN_OUT_COUNT) * s(f, PCNT_MOTION));
                if (boost < 25.00 || absMvInOut > KF_ABS_ZOOM_THRESH || sr[0] > kfRawErr * 1.50) {
                    break;
                }
            } else {
                break;
            }
        }
        kfZeromotionPct = (int) (zeroMotion * 100.0);
        if (zeroMotion > 0.99 && framesToKey > 8) {
            kfBoost = MAX_KF_TOT_BOOST;
        } else {
            kfBoost = Math.max((int) boostScore, framesToKey * 3);
            kfBoost = Math.max(kfBoost, MIN_KF_TOT_BOOST);
            kfBoost = Math.min(kfBoost, MAX_KF_TOT_BOOST);
        }
        var boostFrames = streaming ? Math.min(framesToKey, Math.max(known, 1)) - 1 : framesToKey - 1;
        var kfBits = boostBits(boostFrames, kfBoost, kfGroupBits);
        kfBits += (int) ((kfGroupBits - kfBits) * (kfModErr / divideCheck(kfGroupErr)));
        var maxKfBits = Math.clamp(kfGroupBits - (long) (framesToKey - 1) * FRAME_OVERHEAD_BITS, 0,
                Integer.MAX_VALUE);
        if (!streaming) {
            kfBits = (int) Math.min(kfBits, maxKfBits);
        }
        kfGroupBits -= kfBits;
        if (streaming) {
            vbrBitsOffTarget -= kfBits - (long) (avgFrameBandwidth * kfModErr);
        }
        bitAllocation[0] = kfBits;
        updateType[0] = KF_UPDATE;
        rfLevel[0] = KF_STD;
        layerDepth[0] = 0;
        kfGroupErrorLeft = kfGroupErr - kfModErr;
        normalizedScoreLeft -= kfGroupErr;
    }

    /**
     * Returns whether the next coding step is a group's alternate reference.
     *
     * @return whether it is
     */
    boolean nextIsArf() {
        return gfGroupSize > 0 && gfIndex < gfGroupSize && updateType[gfIndex] == ARF_UPDATE;
    }

    /**
     * Returns the distance from the next shown frame to the source of the alternate reference
     * the next coding step codes.
     *
     * @return the distance in frames
     */
    int arfSourceOffset() {
        return arfSrcOffset[gfIndex];
    }

    /**
     * Plans the next coding step and sets its type, references and target
     * ({@code vp9_rc_get_second_pass_params} and {@code vp9_set_target_rate}).
     *
     * @param showIdx the display index of the next shown frame
     * @param forceKey whether the caller asked for a key frame at the next shown frame
     */
    void beginFrame(int showIdx, boolean forceKey) {
        srcFrameAltRef = false;
        if (nextIsArf()) {
            keyFrame = false;
            configureBufferUpdates();
            baseFrameTarget = clampPframeTarget(bitAllocation[gfIndex]);
            setTargetRate();
            return;
        }
        if (rcMode == MODE_Q) {
            activeWorstQuality = cqLevel;
        } else if (currentFrame == 0) {
            var framesLeft = Math.max(1.0, streaming ? numFrames() : totalStats[COUNT]);
            var sectionTarget = streaming ? avgFrameBandwidth : (int) Math.min(bitsLeft / (long) framesLeft,
                    Integer.MAX_VALUE);
            var length = Math.max(totalLeftStats[COUNT], 1.0);
            var tmpQ = twopassWorstQuality(totalLeftStats[CODED_ERROR] / length,
                    totalLeftStats[INTRA_SKIP_PCT] / length
                            + (totalLeftStats[INACTIVE_ZONE_ROWS] * 2) / ((double) mbRows * length),
                    totalLeftStats[FRAME_NOISE_ENERGY] / length, sectionTarget);
            activeWorstQuality = tmpQ;
            lastQ[1] = tmpQ;
            avgFrameQindex[1] = tmpQ;
            lastQ[0] = (tmpQ + bestQuality) / 2;
            avgFrameQindex[0] = lastQ[0];
        }
        keyFrame = false;
        if (framesToKey <= 0 || forceKey || currentFrame == 0) {
            findNextKeyFrame(showIdx);
        } else if (streaming && framesTillGfUpdateDue == 0) {
            for (var i = 1; i <= maxGfInterval + 1 && i < framesToKey; i++) {
                if (candidateKf(showIdx + i)) {
                    framesToKey = i;
                    break;
                }
            }
        }
        if (framesTillGfUpdateDue == 0) {
            defineGfGroup(showIdx);
            framesTillGfUpdateDue = baselineGfInterval;
        }
        configureBufferUpdates();
        baseFrameTarget = bitAllocation[gfIndex];
        mbSmoothPct = has(showIdx) ? s(showIdx, INTRA_SMOOTH_PCT) : 0;
        if (has(showIdx)) {
            for (var i = 0; i < FIELDS; i++) {
                totalLeftStats[i] -= s(showIdx, i);
            }
        }
        setTargetRate();
    }

    /**
     * Sets the references the current step refreshes from its update type
     * ({@code vp9_configure_buffer_updates}).
     */
    private void configureBufferUpdates() {
        srcFrameAltRef = false;
        switch (updateType[gfIndex]) {
            case KF_UPDATE -> {
                refreshLast = true;
                refreshGolden = true;
                refreshAlt = true;
            }
            case LF_UPDATE -> {
                refreshLast = true;
                refreshGolden = false;
                refreshAlt = false;
            }
            case GF_UPDATE -> {
                refreshLast = true;
                refreshGolden = true;
                refreshAlt = false;
            }
            case OVERLAY_UPDATE -> {
                refreshLast = false;
                refreshGolden = true;
                refreshAlt = false;
                srcFrameAltRef = true;
            }
            default -> {
                refreshLast = false;
                refreshGolden = false;
                refreshAlt = true;
            }
        }
    }

    /**
     * Sets the target of the current step from its planned bits ({@code vp9_set_target_rate}).
     */
    private void setTargetRate() {
        var target = keyFrame ? clampIframeTarget(baseFrameTarget) : clampPframeTarget(baseFrameTarget);
        if (rcMode == MODE_VBR || rcMode == MODE_CQ) {
            target = vbrRateCorrection(target);
        }
        thisFrameTarget = target;
    }

    /**
     * Corrects a target by the bits spent off target so far ({@code vbr_rate_correction}).
     *
     * @param target the planned target
     * @return the corrected target
     */
    private int vbrRateCorrection(int target) {
        var frameTarget = (long) target;
        var window = streaming ? 16 : (int) Math.min(16, totalStats[COUNT] - currentFrame);
        if (window > 0) {
            var maxDelta = vbrBitsOffTarget > 0 ? vbrBitsOffTarget / window : -vbrBitsOffTarget / window;
            maxDelta = Math.min(maxDelta, frameTarget * VBR_PCT_ADJUSTMENT_LIMIT / 100);
            if (vbrBitsOffTarget > 0) {
                frameTarget += Math.min(vbrBitsOffTarget, maxDelta);
            } else {
                frameTarget -= Math.min(-vbrBitsOffTarget, maxDelta);
            }
        }
        if (!(keyFrame || refreshGolden || refreshAlt) && !srcFrameAltRef && vbrBitsOffTargetFast > 0) {
            var oneFrameBits = Math.max(avgFrameBandwidth, frameTarget);
            var fastExtra = Math.min(vbrBitsOffTargetFast, oneFrameBits);
            fastExtra = Math.min(fastExtra, Math.max(oneFrameBits / 8, vbrBitsOffTargetFast / 8));
            frameTarget += fastExtra;
            vbrBitsOffTargetFast -= fastExtra;
        }
        return (int) Math.clamp(frameTarget, 0, Integer.MAX_VALUE);
    }

    /**
     * Clamps the target of an inter frame ({@code vp9_rc_clamp_pframe_target_size}).
     *
     * @param target the frame target in bits
     * @return the clamped target
     */
    private int clampPframeTarget(int target) {
        var minTarget = Math.max(minFrameBandwidth, avgFrameBandwidth >> 5);
        if (target < minTarget) {
            target = minTarget;
        }
        if (refreshGolden && srcFrameAltRef) {
            target = minTarget;
        }
        return Math.min(target, maxFrameBandwidth);
    }

    /**
     * Clamps the target of a key frame ({@code vp9_rc_clamp_iframe_target_size}).
     *
     * @param target the frame target in bits
     * @return the clamped target
     */
    private int clampIframeTarget(int target) {
        return Math.min(target, maxFrameBandwidth);
    }

    /**
     * Returns the bits per macroblock the rate model predicts at a quantizer ({@code vp9_rc_bits_per_mb}).
     *
     * @param key whether the frame is a key frame
     * @param qindex the quantizer index
     * @param correctionFactor the learned correction
     * @return the bits per macroblock, in 1/512
     */
    private static int bitsPerMb(boolean key, int qindex, double correctionFactor) {
        var q = Vp9EncTables.qindexToQ(qindex);
        var enumerator = key ? 2700000 : 1800000;
        enumerator += (int) (enumerator * q) >> 12;
        return (int) (enumerator * correctionFactor / q);
    }

    /**
     * Returns the bits a frame is predicted to take at a quantizer ({@code vp9_estimate_bits_at_q}).
     *
     * @param key whether the frame is a key frame
     * @param q the quantizer index
     * @param correctionFactor the learned correction
     * @return the bits
     */
    private int estimateBitsAtQ(boolean key, int q, double correctionFactor) {
        var bpm = bitsPerMb(key, q, correctionFactor);
        return Math.max(FRAME_OVERHEAD_BITS, (int) (((long) bpm * mbs) >> BPER_MB_NORMBITS));
    }

    /**
     * Returns the rate correction level of the current step.
     *
     * @return the level
     */
    private int rateLevel() {
        return keyFrame ? KF_STD : rfLevel[gfIndex];
    }

    /**
     * Returns the learned correction of the current step's level ({@code get_rate_correction_factor}).
     *
     * @return the factor
     */
    private double rateCorrectionFactor() {
        return Math.clamp(rateCorrectionFactors[rateLevel()], MIN_BPB_FACTOR, MAX_BPB_FACTOR);
    }

    /**
     * Learns from the size of a coding at the frame's quantizer how far the rate model is off
     * ({@code vp9_rc_update_rate_correction_factors}).
     */
    private void updateRateCorrectionFactors() {
        if (srcFrameAltRef) {
            return;
        }
        var level = rateLevel();
        var factor = rateCorrectionFactor();
        var projected = estimateBitsAtQ(keyFrame, frameQindex, factor);
        var correction = 100;
        if (projected > FRAME_OVERHEAD_BITS) {
            correction = (int) (100L * projectedFrameSize / projected);
        }
        double limit;
        if (!dampedAdjustment[level]) {
            limit = 1.0;
            dampedAdjustment[level] = true;
        } else {
            limit = 0.25 + 0.5 * Math.min(1, Math.abs(StrictMath.log10(0.01 * correction)));
        }
        rc2Frame = rc1Frame;
        rc1Frame = correction > 110 ? -1 : correction < 90 ? 1 : 0;
        if (rc1Frame == -1 && rc2Frame == 1 && correction > 1000) {
            rc2Frame = 0;
        }
        if (correction > 102) {
            correction = (int) (100 + (correction - 100) * limit);
            factor = Math.min(factor * correction / 100, MAX_BPB_FACTOR);
        } else if (correction < 99) {
            correction = (int) (100 - (100 - correction) * limit);
            factor = Math.max(factor * correction / 100, MIN_BPB_FACTOR);
        }
        rateCorrectionFactors[level] = Math.clamp(factor, MIN_BPB_FACTOR, MAX_BPB_FACTOR);
    }

    /**
     * Returns the quantizer whose predicted size best meets a target ({@code vp9_rc_regulate_q}).
     *
     * @param target the target in bits
     * @param activeBest the best quantizer allowed
     * @param activeWorst the worst quantizer allowed
     * @return the quantizer index
     */
    private int regulateQ(int target, int activeBest, int activeWorst) {
        var q = activeWorst;
        var lastError = Integer.MAX_VALUE;
        var factor = rateCorrectionFactor();
        var targetPerMb = (int) (((long) target << BPER_MB_NORMBITS) / mbs);
        var i = activeBest;
        do {
            var bits = bitsPerMb(keyFrame, i, factor);
            if (bits <= targetPerMb) {
                q = targetPerMb - bits <= lastError ? i : i - 1;
                break;
            }
            lastError = bits - targetPerMb;
        } while (++i <= activeWorst);
        return q;
    }

    /**
     * Interpolates the best quantizer of a boosted frame between a low and a high motion table ({@code
     * get_active_quality}).
     *
     * @param q the worst quantizer index
     * @param boost the frame's boost
     * @param low the boost below which the high motion table applies
     * @param high the boost above which the low motion table applies
     * @param lowMotion the low motion table
     * @param highMotion the high motion table
     * @return the quantizer index
     */
    private static int activeQuality(int q, int boost, int low, int high, int[] lowMotion, int[] highMotion) {
        if (boost > high) {
            return lowMotion[q];
        } else if (boost < low) {
            return highMotion[q];
        }
        var gap = high - low;
        var offset = high - boost;
        var diff = highMotion[q] - lowMotion[q];
        return lowMotion[q] + (offset * diff + (gap >> 1)) / gap;
    }

    /**
     * Returns the quantizer index change between two quantizer steps ({@code vp9_compute_qdelta}).
     *
     * @param qstart the starting step
     * @param qtarget the target step
     * @return the index change
     */
    private int computeQdelta(double qstart, double qtarget) {
        var start = worstQuality;
        var target = worstQuality;
        for (var i = bestQuality; i < worstQuality; i++) {
            start = i;
            if (Vp9EncTables.qindexToQ(i) >= qstart) {
                break;
            }
        }
        for (var i = bestQuality; i < worstQuality; i++) {
            target = i;
            if (Vp9EncTables.qindexToQ(i) >= qtarget) {
                break;
            }
        }
        return target - start;
    }

    /**
     * Returns the quantizer index change that scales the predicted bits by a ratio ({@code
     * vp9_compute_qdelta_by_rate}).
     *
     * @param key whether the frame is a key frame
     * @param qindex the starting quantizer index
     * @param ratio the ratio of the bits
     * @return the index change
     */
    private int computeQdeltaByRate(boolean key, int qindex, double ratio) {
        var target = worstQuality;
        var base = bitsPerMb(key, qindex, 1.0);
        var targetBits = (int) (ratio * base);
        for (var i = bestQuality; i < worstQuality; i++) {
            if (bitsPerMb(key, i, 1.0) <= targetBits) {
                target = i;
                break;
            }
        }
        return target - qindex;
    }

    /**
     * Returns the quality level of the current step, lowered for smooth content and for a large undershoot of
     * constrained quality ({@code get_active_cq_level_two_pass}).
     *
     * @return the quantizer index
     */
    private int activeCqLevel() {
        var level = cqLevel;
        if (rcMode == MODE_CQ) {
            if (mbSmoothPct > SMOOTH_PCT_MIN) {
                level -= (int) ((mbSmoothPct - SMOOTH_PCT_MIN) / SMOOTH_PCT_DIV);
                level = Math.max(level, 0);
            }
            if (totalTargetBits > 0) {
                var x = (double) totalActualBits / totalTargetBits;
                if (x < 0.1) {
                    level = (int) (level * x / 0.1);
                }
            }
        }
        return level;
    }

    /**
     * Sets the bounds of a key frame's quantizer ({@code pick_kf_q_bound_two_pass}).
     */
    private void pickKfQBound() {
        int best;
        var worst = activeWorstQuality;
        if (thisKeyFrameForced) {
            if (lastKfgroupZeromotionPct >= STATIC_MOTION_THRESH) {
                var qindex = Math.min(lastKfQindex, lastBoostedQindex);
                best = qindex;
                var lastQv = Vp9EncTables.qindexToQ(qindex);
                worst = Math.min(qindex + computeQdelta(lastQv, lastQv * 1.25), worst);
            } else {
                var qindex = lastBoostedQindex;
                var lastQv = Vp9EncTables.qindexToQ(qindex);
                best = Math.max(qindex + computeQdelta(lastQv, lastQv * 0.75), bestQuality);
            }
        } else {
            best = activeQuality(worst, kfBoost, KF_LOW, KF_HIGH, KF_LOW_MOTION_MINQ, KF_HIGH_MOTION_MINQ);
            if (kfZeromotionPct >= STATIC_KF_GROUP_THRESH) {
                best /= 4;
            }
            best = Math.min(worst, Math.max(1, best));
            var adjust = 1.0;
            if (width * height <= 352 * 288) {
                adjust -= 0.25;
            }
            adjust += 0.05 - 0.001 * kfZeromotionPct;
            var qv = Vp9EncTables.qindexToQ(best);
            best += computeQdelta(qv, qv * adjust);
        }
        topIndex = worst;
        bottomIndex = best;
    }

    /**
     * Picks the quantizer of the current step and the range a recode may move it in
     * ({@code vp9_rc_pick_q_and_bounds_two_pass} and {@code rc_constant_q}).
     *
     * @return the quantizer index
     */
    int pickQ() {
        var q = rcMode == MODE_Q ? constantQ() : twoPassQ();
        frameQ = q;
        qLow = bottomIndex;
        qHigh = topIndex;
        overshootSeen = false;
        undershootSeen = false;
        loopAtThisSize = 0;
        if (rcMode == MODE_Q) {
            frameUnderShootLimit = 0;
            frameOverShootLimit = Integer.MAX_VALUE;
        } else {
            var low = (int) ((long) recodeToleranceLow * thisFrameTarget / 100);
            var high = (int) ((long) recodeToleranceHigh * thisFrameTarget / 100);
            frameUnderShootLimit = Math.max(thisFrameTarget - low - 100, 0);
            frameOverShootLimit = Math.min(thisFrameTarget + high + 100, maxFrameBandwidth);
            if (frameOverShootLimit == 0) {
                frameOverShootLimit = 1;
            }
        }
        return q;
    }

    /**
     * Picks the quantizer of the current step at a constant quality ({@code rc_constant_q}).
     *
     * @return the quantizer index
     */
    private int constantQ() {
        var level = activeCqLevel();
        var best = level;
        var worst = level;
        if (keyFrame && framesToKey > 1) {
            var saved = activeWorstQuality;
            activeWorstQuality = level;
            pickKfQBound();
            activeWorstQuality = saved;
            best = bottomIndex;
            worst = topIndex;
        }
        if (!keyFrame && !srcFrameAltRef && refreshAlt) {
            best = activeQuality(level, gfuBoost, GF_LOW, GF_HIGH, ARFGF_LOW_MOTION_MINQ, ARFGF_HIGH_MOTION_MINQ);
        }
        topIndex = worst;
        bottomIndex = best;
        return best;
    }

    /**
     * Picks the quantizer of the current step at a variable bitrate or constrained quality ({@code
     * vp9_rc_pick_q_and_bounds_two_pass}).
     *
     * @return the quantizer index
     */
    private int twoPassQ() {
        var cq = activeCqLevel();
        var worst = activeWorstQuality;
        int best;
        var boostFrame = !srcFrameAltRef && (refreshGolden || refreshAlt);
        if (keyFrame) {
            pickKfQBound();
            best = bottomIndex;
            worst = topIndex;
        } else if (boostFrame) {
            var q = framesSinceKey > 1 && avgFrameQindex[1] < worst ? avgFrameQindex[1] : worst;
            if (rcMode == MODE_CQ && q < cq) {
                q = cq;
            }
            best = activeQuality(q, gfuBoost, GF_LOW, GF_HIGH, ARFGF_LOW_MOTION_MINQ, ARFGF_HIGH_MOTION_MINQ);
            var hl = best;
            if (arfIncreaseActiveBestQuality == 1) {
                hl = ARFGF_HIGH_MOTION_MINQ[q];
            } else if (arfIncreaseActiveBestQuality == -1) {
                hl = ARFGF_LOW_MOTION_MINQ[q];
            }
            best = (int) (best * arfActiveBestQualityAdjustmentFactor + hl * (1.0 - arfActiveBestQualityAdjustmentFactor));
        } else {
            best = INTER_MINQ[worst];
            if (rcMode == MODE_CQ && best < cq) {
                best = cq;
            }
        }
        if (keyFrame || boostFrame) {
            best -= extendMinq + extendMinqFast;
            worst += extendMaxq / 2;
        } else {
            best -= (extendMinq + extendMinqFast) / 2;
            worst += extendMaxq;
            best = Math.max(best, lastQindexOfArfLayer[Math.max(maxLayerDepth - 1, 0)]);
        }
        if (!keyFrame || !thisKeyFrameForced || lastKfgroupZeromotionPct < STATIC_MOTION_THRESH) {
            var qdelta = computeQdeltaByRate(keyFrame, worst, RATE_FACTOR_DELTAS[rateLevel()]);
            worst = Math.max(worst + qdelta, best);
        }
        best = Math.clamp(best, bestQuality, worstQuality);
        worst = Math.clamp(worst, best, worstQuality);
        int q;
        if (keyFrame && thisKeyFrameForced) {
            q = lastKfgroupZeromotionPct >= STATIC_MOTION_THRESH ? Math.min(lastKfQindex, lastBoostedQindex)
                    : lastBoostedQindex;
        } else if (keyFrame) {
            q = best;
        } else {
            q = regulateQ(thisFrameTarget, best, worst);
            if (q > worst) {
                if (thisFrameTarget >= maxFrameBandwidth) {
                    worst = q;
                } else {
                    q = worst;
                }
            }
        }
        topIndex = worst;
        bottomIndex = best;
        return Math.clamp(q, bestQuality, worstQuality);
    }

    /**
     * Returns whether the current step is a key frame, a golden frame or an alternate reference ({@code
     * frame_is_kf_gf_arf}).
     *
     * @return whether it is
     */
    private boolean kfGfArf() {
        return keyFrame || ((refreshGolden || refreshAlt) && !srcFrameAltRef);
    }

    /**
     * Returns whether the current step is the first ordinary frame of its golden frame group in a
     * two-pass encode ({@code two_pass_first_group_inter}).
     *
     * @return whether it is
     */
    private boolean firstGroupInter() {
        if (streaming || gfIndex >= gfGroupSize) {
            return false;
        }
        if (gfIndex == 0) {
            return updateType[0] == LF_UPDATE;
        }
        return updateType[gfIndex - 1] != LF_UPDATE && updateType[gfIndex] == LF_UPDATE;
    }

    /**
     * Returns the size above which the current step misses its target badly ({@code
     * big_rate_miss_high_threshold}).
     *
     * @return the size in bits
     */
    private int bigRateMissHigh() {
        return kfGfArf() ? thisFrameTarget * 3 / 2 : thisFrameTarget * 2;
    }

    /**
     * Returns whether the last coding of the current step missed its target badly ({@code big_rate_miss}).
     *
     * @return whether it did
     */
    private boolean bigRateMiss() {
        if (srcFrameAltRef) {
            return false;
        }
        return projectedFrameSize > bigRateMissHigh() || projectedFrameSize < thisFrameTarget / 2;
    }

    /**
     * Returns the quantizer the current step is coded again at, after a coding of a size, or -1
     * to keep the coding ({@code encode_with_recode_loop} and {@code recode_loop_test}).
     *
     * @param q the quantizer index of the coding
     * @param bytes the coding's size in bytes
     * @param keyFrameError the luma squared error of the coding when {@link #forcedKeyFrame},
     *                      ignored otherwise
     * @return the quantizer index of the next coding, or -1
     */
    int recode(int q, int bytes, long keyFrameError) {
        frameQindex = q;
        projectedFrameSize = bytes << 3;
        if (rcMode == MODE_Q || recodeLoop == Vp9GoodSpeed.RECODE_NONE || recodeLoop == Vp9GoodSpeed.RECODE_KF_MAX_BW
                && projectedFrameSize < maxFrameBandwidth) {
            return -1;
        }
        if (forcedKeyFrame() && projectedFrameSize < maxFrameBandwidth) {
            return recodeForcedKey(q, keyFrameError);
        }
        var loop = false;
        var maxq = Math.max(qHigh, topIndex);
        var minq = bottomIndex;
        var forced = projectedFrameSize >= maxFrameBandwidth || bigRateMiss()
                || (firstGroupInter() && recodeLoop == Vp9GoodSpeed.RECODE_FIRST)
                || (kfGfArf() && recodeLoop >= Vp9GoodSpeed.RECODE_KF_ARF_GF);
        if (forced) {
            if (projectedFrameSize >= maxFrameBandwidth || (recodeLoop >= Vp9GoodSpeed.RECODE_KF_ARF_GF
                    && projectedFrameSize >= bigRateMissHigh())) {
                loop = true;
            } else if ((projectedFrameSize > frameOverShootLimit && q < maxq)
                    || (projectedFrameSize < frameUnderShootLimit && q > minq)) {
                loop = true;
            } else if (rcMode == MODE_CQ && q > cqLevel && projectedFrameSize < (thisFrameTarget * 7) >> 3) {
                loop = true;
            }
        }
        var next = q;
        if (loop) {
            var retries = 0;
            if (projectedFrameSize > thisFrameTarget) {
                if (q == qHigh && (projectedFrameSize >= maxFrameBandwidth
                        || (!srcFrameAltRef && projectedFrameSize >= bigRateMissHigh()))) {
                    var maxRate = Math.max(1, Math.min(maxFrameBandwidth, bigRateMissHigh()));
                    var qv = Vp9EncTables.qindexToQ(qHigh) * ((double) projectedFrameSize / maxRate);
                    qHigh = Math.clamp(qToQindex(qv), bestQuality, worstQuality);
                }
                var qstep = qstepAdj(projectedFrameSize, thisFrameTarget);
                qLow = Math.min(q + qstep, qHigh);
                updateRateCorrectionFactors();
                if (undershootSeen || loopAtThisSize > 1) {
                    next = (qHigh + qLow + 1) / 2;
                } else {
                    next = regulateQ(thisFrameTarget, bottomIndex, Math.max(qHigh, topIndex));
                    while (next < qLow && retries < 10) {
                        updateRateCorrectionFactors();
                        next = regulateQ(thisFrameTarget, bottomIndex, Math.max(qHigh, topIndex));
                        retries++;
                    }
                }
                overshootSeen = true;
            } else {
                var qstep = qstepAdj(thisFrameTarget, projectedFrameSize);
                qHigh = Math.max(q - qstep, qLow);
                updateRateCorrectionFactors();
                if (overshootSeen || loopAtThisSize > 1) {
                    next = (qHigh + qLow) / 2;
                } else {
                    next = regulateQ(thisFrameTarget, Math.min(qLow, bottomIndex), topIndex);
                    if (rcMode == MODE_CQ && next < qLow) {
                        qLow = next;
                    }
                    while (next > qHigh && retries < 10) {
                        updateRateCorrectionFactors();
                        next = regulateQ(thisFrameTarget, Math.min(qLow, bottomIndex), topIndex);
                        retries++;
                    }
                }
                undershootSeen = true;
            }
            next = Math.clamp(next, qLow, qHigh);
            loop = next != q;
        }
        if (srcFrameAltRef && projectedFrameSize < maxFrameBandwidth) {
            loop = false;
        }
        if (!loop) {
            return -1;
        }
        loopAtThisSize++;
        return next;
    }

    /**
     * Returns the quantizer of the next coding of a key frame the interval forced, or {@code -1}
     * when the coding is kept: a key frame whose luma error is above the error of the frame before
     * it, or above half of it within the size range, is coded again at a lower quantizer; one whose
     * error is below half, at a higher quantizer (the forced key frame branch of
     * {@code encode_with_recode_loop}).
     *
     * @param q the quantizer index of the coding
     * @param keyFrameError the luma squared error of the coding
     * @return the next quantizer index, or {@code -1}
     */
    private int recodeForcedKey(int q, long keyFrameError) {
        var high = ambientError;
        var low = ambientError >> 1;
        var err = Math.max(keyFrameError, 1);
        var next = q;
        if ((err > high && projectedFrameSize <= frameOverShootLimit)
                || (err > low && projectedFrameSize <= frameUnderShootLimit)) {
            qHigh = q > qLow ? q - 1 : qLow;
            next = (int) (q * high / err);
            next = Math.min(next, (qHigh + qLow) >> 1);
        } else if (err < low && projectedFrameSize >= frameUnderShootLimit) {
            qLow = q < qHigh ? q + 1 : qHigh;
            next = (int) (q * low / err);
            next = Math.min(next, (qHigh + qLow + 1) >> 1);
        }
        next = Math.clamp(next, qLow, qHigh);
        if (next == q) {
            return -1;
        }
        loopAtThisSize++;
        return next;
    }

    /**
     * Returns whether the current step is a key frame the interval forced, whose codings are then
     * measured against the error of the frame before it.
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
        return nextKeyFrameForced && framesToKey == 1;
    }

    /**
     * Sets the luma squared error of the reconstruction of the frame before a forced key frame,
     * libvpx's {@code ambient_err}.
     *
     * @param error the squared error, summed over the luma samples
     */
    void setAmbientError(long error) {
        ambientError = error;
    }

    /**
     * The luma squared error of the reconstruction of the frame before a forced key frame.
     */
    private long ambientError;

    /**
     * Returns the quantizer step of a recode from the ratio of two sizes ({@code get_qstep_adj}).
     *
     * @param excess the larger size
     * @param limit the smaller size
     * @return the step, at most {@link #MAX_QSTEP_ADJ}
     */
    private static int qstepAdj(int excess, int limit) {
        var qstep = limit != 0 ? (excess + limit / 2) / limit : Integer.MAX_VALUE;
        return Math.min(qstep, MAX_QSTEP_ADJ);
    }

    /**
     * Returns the smallest quantizer index whose step reaches a step ({@code vp9_convert_q_to_qindex}).
     *
     * @param q the step
     * @return the quantizer index
     */
    private static int qToQindex(double q) {
        for (var i = 0; i < 256; i++) {
            if (Vp9EncTables.qindexToQ(i) >= q) {
                return i;
            }
        }
        return 255;
    }

    /**
     * Learns from the kept coding of the current step and moves to the next
     * ({@code vp9_rc_postencode_update} and {@code vp9_twopass_postencode_update}).
     *
     * @param q the quantizer index the step was coded with
     * @param bytes the coding's size in bytes
     * @param shown whether the step's frame is shown
     */
    void endFrame(int q, int bytes, boolean shown) {
        frameQindex = q;
        projectedFrameSize = bytes << 3;
        updateRateCorrectionFactors();
        if (keyFrame) {
            lastQ[0] = q;
            avgFrameQindex[0] = (3 * avgFrameQindex[0] + q + 2) >> 2;
        } else if (!srcFrameAltRef && !(refreshGolden || refreshAlt)) {
            lastQ[1] = q;
            avgFrameQindex[1] = (3 * avgFrameQindex[1] + q + 2) >> 2;
        }
        if (q < lastBoostedQindex || keyFrame || (!constrainedGfGroup && (refreshAlt
                || (refreshGolden && !srcFrameAltRef)))) {
            lastBoostedQindex = q;
        }
        var depth = layerDepth[gfIndex];
        if (q < lastQindexOfArfLayer[depth] || keyFrame || (!constrainedGfGroup && (refreshAlt
                || (refreshGolden && !srcFrameAltRef)))) {
            lastQindexOfArfLayer[depth] = q;
        }
        if (keyFrame) {
            lastKfQindex = q;
        }
        if (!keyFrame) {
            rollingTargetBits = (int) ((rollingTargetBits * 3L + thisFrameTarget + 2) >> 2);
            rollingActualBits = (int) ((rollingActualBits * 3L + projectedFrameSize + 2) >> 2);
        }
        totalActualBits += projectedFrameSize;
        totalTargetBits += shown ? avgFrameBandwidth : 0;
        if (refreshAlt && !keyFrame) {
            framesSinceGolden = 0;
            sourceAltRefPending = false;
            sourceAltRefActive = true;
        } else if (refreshGolden) {
            framesSinceGolden = 0;
            if (!sourceAltRefPending && gfIndex == 0) {
                sourceAltRefActive = false;
            }
            if (framesTillGfUpdateDue > 0) {
                framesTillGfUpdateDue--;
            }
        } else if (!refreshAlt) {
            if (framesTillGfUpdateDue > 0) {
                framesTillGfUpdateDue--;
            }
            framesSinceGolden++;
        }
        if (keyFrame) {
            framesSinceKey = 0;
            sourceAltRefActive = false;
            if (sourceAltRefPending) {
                sourceAltRefActive = false;
            }
        }
        if (shown) {
            framesSinceKey++;
            framesToKey--;
        }
        var bitsUsed = baseFrameTarget;
        vbrBitsOffTarget += baseFrameTarget - projectedFrameSize;
        bitsLeft = Math.max(bitsLeft - bitsUsed, 0);
        rollingArfGroupTargetBits += thisFrameTarget;
        rollingArfGroupActualBits += projectedFrameSize;
        if (totalActualBits > 0) {
            rateErrorEstimate = (int) Math.clamp(vbrBitsOffTarget * 100 / totalActualBits, -100, 100);
        } else {
            rateErrorEstimate = 0;
        }
        if (!keyFrame) {
            kfGroupBits -= bitsUsed;
            lastKfgroupZeromotionPct = kfZeromotionPct;
        }
        kfGroupBits = Math.max(kfGroupBits, 0);
        if (rcMode != MODE_Q && !srcFrameAltRef) {
            var maxqAdjLimit = worstQuality - activeWorstQuality;
            var minqAdjLimit = rcMode == MODE_CQ ? MINQ_ADJ_LIMIT_CQ : MINQ_ADJ_LIMIT;
            if (rateErrorEstimate > underShootPct) {
                extendMaxq--;
                if (rollingTargetBits >= rollingActualBits) {
                    extendMinq++;
                }
            } else if (rateErrorEstimate < -overShootPct) {
                extendMinq--;
                if (rollingTargetBits < rollingActualBits) {
                    extendMaxq++;
                }
            } else {
                if (projectedFrameSize > 2 * baseFrameTarget && projectedFrameSize > 2 * avgFrameBandwidth) {
                    extendMaxq++;
                }
                if (rollingTargetBits < rollingActualBits) {
                    extendMinq--;
                } else if (rollingTargetBits > rollingActualBits) {
                    extendMaxq--;
                }
            }
            extendMinq = Math.clamp(extendMinq, 0, Math.max(minqAdjLimit, 0));
            extendMaxq = Math.clamp(extendMaxq, 0, Math.max(maxqAdjLimit, 0));
            if (!kfGfArf()) {
                var fastExtraThresh = baseFrameTarget / HIGH_UNDERSHOOT_RATIO;
                if (projectedFrameSize < fastExtraThresh) {
                    vbrBitsOffTargetFast += fastExtraThresh - projectedFrameSize;
                    vbrBitsOffTargetFast = Math.min(vbrBitsOffTargetFast, 4L * avgFrameBandwidth);
                    if (avgFrameBandwidth > 0) {
                        extendMinqFast = (int) (vbrBitsOffTargetFast * 8 / avgFrameBandwidth);
                    }
                    extendMinqFast = Math.min(extendMinqFast, minqAdjLimit - extendMinq);
                } else if (vbrBitsOffTargetFast > 0) {
                    extendMinqFast = Math.min(extendMinqFast, minqAdjLimit - extendMinq);
                } else {
                    extendMinqFast = 0;
                }
            }
        }
        gfIndex++;
        currentFrame++;
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
     * Returns whether the current step codes the source of the alternate reference again.
     *
     * @return whether it does
     */
    boolean srcFrameAltRef() {
        return srcFrameAltRef;
    }

    /**
     * Returns whether the alternate reference holds a frame that lies ahead in display order
     * ({@code source_alt_ref_active}).
     *
     * @return whether it does
     */
    boolean altRefActive() {
        return sourceAltRefActive;
    }

    /**
     * Returns the update type of the current step.
     *
     * @return the type
     */
    int updateType() {
        return updateType[gfIndex];
    }

    /**
     * Returns the boost of the current golden frame group.
     *
     * @return the boost
     */
    int gfuBoost() {
        return gfuBoost;
    }

    /**
     * Returns the temporal filter strength adjustment of the current group.
     *
     * @return the adjustment, from -2 to 2
     */
    int arnrStrengthAdjustment() {
        return streaming ? 0 : arnrStrengthAdjustment;
    }

    /**
     * Returns the average quantizer index of recent frames of a type, which the temporal filter
     * strength follows.
     *
     * @param key whether to return the key frames' average
     * @return the index
     */
    int avgFrameQindex(boolean key) {
        return avgFrameQindex[key ? 0 : 1];
    }

    /**
     * Returns the number of steps coded.
     *
     * @return the count
     */
    int currentFrame() {
        return currentFrame;
    }

    /**
     * Returns the bit target of the current step.
     *
     * @return the target in bits
     */
    int frameTarget() {
        return thisFrameTarget;
    }

    /**
     * Returns the number of frames until the next key frame, counting the current one.
     *
     * @return the count
     */
    int framesToKey() {
        return framesToKey;
    }
}
