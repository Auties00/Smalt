package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;

import java.util.Arrays;

/**
 * Runs the real-time encoding loop around a {@link FrameEncoder}: key frame placement, frame
 * dropping, the reference structure, the rate control and the loop filter and CDEF strengths.
 *
 * <p>Every call to {@link #encode} codes one picture into one temporal unit, or drops it, with no
 * lookahead and no frame reordering. With temporal layers the frames follow the {@code 0-1-0-1}
 * (two layers) or {@code 0-2-1-2} (three layers) pattern: each layer predicts only from lower or
 * equal layers, the top layer's frames are never referenced, and each layer has its own rate
 * control with a share of the bitrate. An instance is used by one thread at a time.
 *
 * <p>With a key frame limit, a key frame that overshoots its target by more than half is coded
 * again at a coarser quantizer, at most {@value #MAX_KEY_RECODES} times; a scene change is coded
 * near the worst quantizer; and with frame dropping on, a coded inter frame that would empty the
 * buffer is dropped after all. A new loop can take over the rate control of the loop it replaces
 * after a change of picture size ({@link #inheritRateControl}). {@code LAST} cycles through slots
 * 0 to 5, {@code GOLDEN} lives in slot 6 and {@code ALTREF} is the {@code LAST} of a few frames
 * earlier.
 *
 * @implNote This implementation ports libaom's one-layer real-time loop: the frame parameters, golden
 * refresh and reference structure of {@code av1/encoder/ratectrl.c}, the {@code LPF_PICK_FROM_Q}
 * level model ({@code picklpf.c}), {@code av1_pick_cdef_from_qp} ({@code pickcdef.c}) and the scene
 * change step and post-encode drop of {@code av1/encoder/encoder.c}.
 */
public final class Av1RtEncoder implements Av1Engine {
    /**
     * Holds the rate control and coding settings of a {@link Av1RtEncoder}.
     *
     * @param bitrate the target bitrate, in bits per second
     * @param frameRate the frame rate, in frames per second
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param keyInterval the key frame interval, 0 for key frames only on request
     * @param speed the speed level, 7 to 10
     * @param errorResilient whether every frame is coded error resilient
     * @param dropWaterMark the buffer level, in percent of the optimal level, below which frames are
     * dropped, or 0 to never drop
     * @param bufferMs the buffer size, in milliseconds
     * @param initialMs the initial buffer level, in milliseconds
     * @param optimalMs the optimal buffer level, in milliseconds
     * @param tileColsLog2 the base-2 logarithm of the tile columns
     * @param threads the number of worker threads, 0 for the pool's size
     * @param adaptiveQuantization whether the cyclic refresh adaptive quantization is on
     * @param maxIntraPct the largest key frame target, in percent of the average frame size, or 0
     * for no limit; with a limit, a later key frame's quantizer may reach the worst one and a scene
     * change at speed 10 follows its spatial activity
     */
    public record Settings(long bitrate, double frameRate, int minQindex, int maxQindex, int keyInterval, int speed,
                           boolean errorResilient, int dropWaterMark, int bufferMs, int initialMs, int optimalMs,
                           int tileColsLog2, int threads, boolean adaptiveQuantization, int maxIntraPct) {
    }

    /**
     * The number of rotating {@code LAST} slots.
     */
    private static final int LAST_SLOTS = 6;

    /**
     * The largest number of times a key frame is coded again after overshooting its target.
     */
    static final int MAX_KEY_RECODES = 2;

    /**
     * The golden slot.
     */
    private static final int GOLDEN_SLOT = 6;

    /**
     * The default distance of the {@code ALTREF} frame behind the current one.
     */
    private static final int ALT_LAG = 4;

    /**
     * The longest golden refresh interval ({@code MAX_GF_INTERVAL_RT}).
     */
    private static final int MAX_GF_INTERVAL = 160;

    /**
     * The golden refresh interval below which a refresh is not postponed
     * ({@code FIXED_GF_INTERVAL_RT}).
     */
    private static final int FIXED_GF_INTERVAL = 80;

    /**
     * The cumulative bitrate share of each of two temporal layers.
     */
    private static final double[] LAYER_SHARES_2 = {0.6, 1.0};

    /**
     * The cumulative bitrate share of each of three temporal layers.
     */
    private static final double[] LAYER_SHARES_3 = {0.4, 0.6, 1.0};

    /**
     * The source SAD thresholds of the adaptive {@code ALTREF} distance, by level
     * ({@code th_frame_sad}).
     */
    private static final long[][] ALT_LAG_SAD = {{18000, 18000, 18000}, {25000, 25000, 25000},
            {40000, 30000, 20000}, {30000, 25000, 20000}};

    /**
     * The picture width.
     */
    private final int width;

    /**
     * The picture height.
     */
    private final int height;

    /**
     * The frame encoder.
     */
    private final FrameEncoder frame;

    /**
     * The number of temporal layers, 1 to 3.
     */
    private final int temporalLayers;

    /**
     * The rate control of each temporal layer.
     */
    private final RateControl[] layerRc;

    /**
     * The rate control of the current frame's layer.
     */
    private RateControl rc;

    /**
     * The position in the temporal layer pattern of the next frame, restarted by key frames.
     */
    private long frameInPattern;

    /**
     * The temporal layer of the last coded frame.
     */
    private int lastTemporalId;

    /**
     * The parameters of the frame being coded.
     */
    private final FrameParams params = new FrameParams();

    /**
     * The speed level, 7 to 10.
     */
    private int speed = 9;

    /**
     * The key frame interval, 0 for key frames only on request.
     */
    private int keyInterval;

    /**
     * Whether every frame is coded error resilient.
     */
    private boolean errorResilient;

    /**
     * The base-2 logarithm of the tile columns.
     */
    private int tileColsLog2;

    /**
     * The number of worker threads, 0 for the pool's size.
     */
    private int threads;

    /**
     * The number of frames until the next periodic key frame.
     */
    private int framesToKey;

    /**
     * The number of coded frames since the last key frame, the position in the reference
     * structure.
     */
    private int frameInKey;

    /**
     * The number of frames until the golden reference is refreshed.
     */
    private int framesTillGfUpdate;

    /**
     * The current golden refresh interval ({@code baseline_gf_interval}).
     */
    private int baselineGfInterval;

    /**
     * The number of coded frames since the golden reference was refreshed.
     */
    private int framesSinceGolden;

    /**
     * The cyclic refresh, or {@code null} when adaptive quantization is off.
     */
    private CyclicRefresh refresh;

    /**
     * The smallest quantizer index.
     */
    private int minQindex = 1;

    /**
     * The speed features of the configured speed, created on first use.
     */
    private SpeedFeatures features;

    /**
     * The order hint of the next frame.
     */
    private int orderHint;

    /**
     * Whether a frame was coded yet.
     */
    private boolean started;

    /**
     * Whether the rate control carries state from coded frames, its own or those of the loop it
     * took over.
     */
    private boolean rateControlLive;

    /**
     * The number of luma samples of the loop whose rate control this one took over, 0 when none
     * is pending.
     */
    private long inheritedPixels;

    /**
     * Whether the last coded frame was a key frame.
     */
    private boolean lastKey;

    /**
     * The quantizer index of the last coded frame.
     */
    private int lastQindex;

    /**
     * Creates the loop of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @param color the color description signaled in the sequence header
     * @param limits the allocation limits
     */
    public Av1RtEncoder(int width, int height, ColorInfo color, MediaLimits limits) {
        this(width, height, color, 1, limits);
    }

    /**
     * Creates the loop of a picture size with temporal layers.
     *
     * @param width the picture width
     * @param height the picture height
     * @param color the color description signaled in the sequence header
     * @param temporalLayers the number of temporal layers, 1 to 3
     * @param limits the allocation limits
     */
    public Av1RtEncoder(int width, int height, ColorInfo color, int temporalLayers, MediaLimits limits) {
        this.width = width;
        this.height = height;
        this.temporalLayers = Math.clamp(temporalLayers, 1, 3);
        this.layerRc = new RateControl[this.temporalLayers];
        for (var i = 0; i < layerRc.length; i++) {
            layerRc[i] = new RateControl();
        }
        this.rc = layerRc[0];
        this.frame = new FrameEncoder(width, height, color, ObuWriter.level(width, height), this.temporalLayers,
                limits);
    }

    /**
     * Configures the rate control and the coding tools; a running stream keeps its state.
     *
     * @param settings the rate control and coding settings
     */
    public void configure(Settings settings) {
        var bitrate = settings.bitrate();
        var framerate = settings.frameRate();
        var minQindex = settings.minQindex();
        var keyInterval = settings.keyInterval();
        var fractions = temporalLayers == 3 ? LAYER_SHARES_3 : LAYER_SHARES_2;
        var prevTarget = 0L;
        var prevRate = 0.0;
        for (var tl = 0; tl < temporalLayers; tl++) {
            var share = temporalLayers == 1 ? 1.0 : fractions[tl];
            var layerBitrate = Math.max(1, Math.round(bitrate * share));
            var layerRate = framerate / (1 << (temporalLayers - 1 - tl));
            var lrc = layerRc[tl];
            lrc.temporalLayers = temporalLayers;
            lrc.configure(layerBitrate, layerRate, Math.max(1, minQindex), Math.max(1, settings.maxQindex()),
                    settings.bufferMs(), settings.initialMs(), settings.optimalMs(), settings.dropWaterMark(), 0,
                    settings.maxIntraPct(), width, height, !rateControlLive);
            lrc.layerFrameSize = temporalLayers == 1 ? 0
                    : (int) Math.round((layerBitrate - prevTarget) / (layerRate - prevRate));
            prevTarget = layerBitrate;
            prevRate = layerRate;
        }
        if (this.keyInterval != keyInterval) {
            framesToKey = keyInterval;
        }
        this.keyInterval = keyInterval;
        this.speed = Math.clamp(settings.speed(), 7, 10);
        this.errorResilient = settings.errorResilient();
        this.tileColsLog2 = settings.tileColsLog2();
        this.threads = settings.threads();
        this.minQindex = Math.max(1, minQindex);
        var aq = settings.adaptiveQuantization();
        if (aq && refresh == null) {
            refresh = new CyclicRefresh(((width + 7) & ~7) >> 2, ((height + 7) & ~7) >> 2);
        } else if (!aq) {
            refresh = null;
        }
        frame.cyclicRefresh = refresh;
        for (var lrc : layerRc) {
            lrc.cyclicRefresh = refresh;
        }
        if (inheritedPixels > 0) {
            for (var lrc : layerRc) {
                lrc.resize(inheritedPixels);
            }
            inheritedPixels = 0;
        }
    }

    /**
     * Takes over the rate control of the loop this one replaces after a change of picture size:
     * the buffer, the rate correction factors and the quantizer history carry over, rescaled for
     * the new size by the next {@link #configure}, instead of starting over. Call it before the
     * first {@link #configure} of this loop; the next frame is still a key frame.
     *
     * @param previous the loop being replaced, with the same number of temporal layers
     * @throws IllegalArgumentException if the loops have different numbers of temporal layers
     */
    public void inheritRateControl(Av1RtEncoder previous) {
        if (previous.temporalLayers != temporalLayers) {
            throw new IllegalArgumentException("temporal layers differ");
        }
        if (!previous.rateControlLive) {
            return;
        }
        System.arraycopy(previous.layerRc, 0, layerRc, 0, temporalLayers);
        rc = layerRc[0];
        rateControlLive = true;
        inheritedPixels = (long) previous.width * previous.height;
    }

    /**
     * Returns the sequence header OBU payload of the stream.
     *
     * @return the payload
     */
    public byte[] sequenceHeaderObu() {
        return frame.sequenceHeaderObu();
    }

    @Override
    public byte[] codecConfigurationRecord() {
        return ObuWriter.av1Config(frame.sequenceHeader(), frame.sequenceHeaderObu());
    }

    /**
     * Returns whether the last coded frame was a key frame.
     *
     * @return {@code true} for a key frame
     */
    public boolean lastKeyFrame() {
        return lastKey;
    }

    /**
     * Returns the quantizer index of the last coded frame.
     *
     * @return the index
     */
    public int lastQindex() {
        return lastQindex;
    }

    /**
     * Returns the buffer level of the rate control.
     *
     * @return the level in bits
     */
    public long bufferLevel() {
        return layerRc[temporalLayers - 1].bufferLevel();
    }

    /**
     * Returns the temporal layer of the last coded frame.
     *
     * @return the layer, 0 for the base layer
     */
    public int lastTemporalId() {
        return lastTemporalId;
    }

    /**
     * Encodes one picture.
     *
     * @param picture the 4:2:0 picture of the configured size
     * @param forceKey whether the frame must be a key frame
     * @return the temporal unit, or {@code null} when the rate control dropped the frame
     */
    public byte[] encode(YuvPicture picture, boolean forceKey) {
        var key = !started || forceKey || (keyInterval > 0 && framesToKey <= 0);
        if (key) {
            frameInPattern = 0;
        }
        var tl = temporalId(frameInPattern);
        rc = layerRc[tl];
        frame.load(picture);
        var prevAvgSourceSad = frame.avgSourceSad;
        var sceneCut = frame.analyze(rc.framesSinceKey) && !key;
        var sceneVariance = sceneCut && speed >= 10 && rc.limitsKeyFrames();
        if (sceneVariance) {
            rc.spatialActivity(frame.spatialActivity());
        }
        var gfUpdate = false;
        if (tl == 0 && (key || sceneCut || framesTillGfUpdate <= 0)) {
            setGfInterval();
            gfUpdate = !key;
        }
        rc.frameTarget(key);
        if (rc.dropFrame(key)) {
            frame.skip();
            rc.postDrop();
            for (var l = tl + 1; l < temporalLayers; l++) {
                layerRc[l].updateBuffer(0);
            }
            frameInPattern++;
            return null;
        }
        var pixels = (long) width * height;
        var cr = refresh;
        if (cr != null) {
            cr.updateParameters(key, sceneCut, rc.framesSinceKey, rc.avgQindexInter(), minQindex,
                    frame.avgFrameLowMotion, speed, pixels, rc.avgFrameBandwidth(), tl, temporalLayers);
        }
        var q = rc.pickQindex(key, frame.frameSourceSad, frame.avgSourceSad, prevAvgSourceSad);
        if (sceneCut) {
            if (cr != null) {
                cr.restartSceneChangeCount();
            }
            q = rc.sceneChangeQindex(q, sceneVariance, frame.frameSourceSad);
            for (var lrc : layerRc) {
                if (lrc != rc) {
                    lrc.followSceneChange(rc, q);
                }
            }
        }
        if (cr != null) {
            cr.setup(rc, key, sceneCut, q, rc.thisFrameTarget(), pixels, speed, frame, rc.framesSinceKey);
            if (cr.qindexDelta[CyclicRefresh.BOOST1] == 0 && cr.qindexDelta[CyclicRefresh.BOOST2] == 0) {
                cr.apply = false;
            }
        }
        if (!key && !sceneCut && temporalLayers == 1) {
            var avgQp = rc.avgQindexInter();
            var lowMotion = frame.avgFrameLowMotion;
            if (framesSinceGolden < FIXED_GF_INTERVAL && framesTillGfUpdate == 1 && q > avgQp) {
                gfUpdate = false;
                setGfInterval();
            } else if (framesTillGfUpdate <= baselineGfInterval - 10
                    && (q < 87 * avgQp / 100 || (lowMotion > 0 && lowMotion < 20))) {
                gfUpdate = true;
                setGfInterval();
            }
        }
        setup(key, gfUpdate, q, tl);
        var data = frame.code(params);
        for (var recode = 0; key && recode < MAX_KEY_RECODES; recode++) {
            var next = rc.recodeKeyQindex(q, data.length);
            if (next == q) {
                break;
            }
            q = next;
            setup(key, gfUpdate, q, tl);
            data = frame.code(params);
        }
        if (!key && rc.dropCoded(q, data.length, sceneCut)) {
            frame.discard();
            frame.skip();
            for (var lrc : layerRc) {
                if (lrc != rc) {
                    lrc.followCodedDrop();
                }
            }
            frameInPattern++;
            return null;
        }
        frame.commit();
        rateControlLive = true;
        rc.postEncode(key, q, data.length);
        for (var l = tl + 1; l < temporalLayers; l++) {
            layerRc[l].updateBuffer(data.length * 8);
        }
        lastTemporalId = tl;
        frameInPattern++;
        started = true;
        lastKey = key;
        lastQindex = q;
        frameInKey = key ? 1 : frameInKey + 1;
        orderHint++;
        if (tl == 0) {
            framesTillGfUpdate--;
        }
        framesSinceGolden = key || gfUpdate ? 0 : framesSinceGolden + 1;
        framesToKey = key ? keyInterval - 1 : framesToKey - 1;
        return data;
    }

    /**
     * Returns the temporal layer of a position in the layer pattern.
     *
     * @param position the position since the last key frame
     * @return the layer
     */
    private int temporalId(long position) {
        return switch (temporalLayers) {
            case 2 -> (int) (position & 1);
            case 3 -> {
                var phase = (int) (position & 3);
                yield phase == 0 ? 0 : phase == 2 ? 1 : 2;
            }
            default -> 0;
        };
    }

    /**
     * Returns the speed features of the configured speed and picture size.
     *
     * @return the features
     */
    private SpeedFeatures features() {
        if (features == null || features.speed != speed) {
            features = SpeedFeatures.realtime(speed, width, height);
        }
        return features;
    }

    /**
     * Restarts the golden refresh countdown ({@code set_golden_update} and
     * {@code set_baseline_gf_interval}).
     */
    private void setGfInterval() {
        var mult = speed >= 9 && Math.min(width, height) >= 360 ? 4 : 8;
        var divisor = refresh != null ? refresh.percentRefresh : 10;
        var interval = divisor > 0 ? Math.min(mult * (100 / divisor), MAX_GF_INTERVAL) : FIXED_GF_INTERVAL;
        var lowMotion = frame.avgFrameLowMotion;
        if (lowMotion > 0 && lowMotion < 40) {
            interval = 16;
        }
        if (keyInterval > 0) {
            interval = Math.min(interval, Math.max(1, framesToKey));
        }
        baselineGfInterval = interval;
        framesTillGfUpdate = interval;
    }

    /**
     * Returns the distance of the {@code ALTREF} frame behind the current one, longer for static
     * content ({@code sad_based_adp_altref_lag}).
     *
     * @return the distance in frames
     */
    private int altLag() {
        var minDim = Math.min(width, height);
        var level = 0;
        if (minDim >= 360) {
            if (speed >= 10) {
                level = minDim >= 720 ? 3 : 4;
            } else if (speed >= 9) {
                level = minDim >= 720 ? 1 : 2;
            }
        }
        if (level == 0) {
            return ALT_LAG;
        }
        var th = ALT_LAG_SAD[level - 1];
        var sad = frame.avgSourceSad;
        return sad > th[0] ? 3 : sad > th[1] ? 4 : sad > th[2] ? 5 : 6;
    }

    /**
     * Fills the frame parameters of the next frame.
     *
     * @param key whether the frame is a key frame
     * @param gfUpdate whether the frame refreshes the golden reference
     * @param q the quantizer index
     * @param tl the frame's temporal layer
     */
    private void setup(boolean key, boolean gfUpdate, int q, int tl) {
        var p = params;
        p.key = key;
        p.errorResilient = errorResilient;
        p.orderHint = orderHint;
        p.primaryRefFrame = 0;
        p.qindex = q;
        p.speed = speed;
        p.threads = threads;
        p.tileColsLog2 = tileColsLog2;
        p.txSwitchable = !key;
        // TODO: search and code 1/8-sample motion vectors at low quantizers (allow_high_precision_mv)
        p.allowHighPrecisionMv = false;
        p.interpFilter = !key && features().useNonrdFilterSearch ? FrameHeader.FILTER_SWITCHABLE : 0;
        p.goldenUpdate = gfUpdate;
        p.temporalId = tl;
        p.temporalLayers = temporalLayers;
        var cr = refresh;
        p.segmentation = !key && cr != null && cr.apply;
        Arrays.fill(p.segDeltaQ, 0);
        if (p.segmentation) {
            p.segDeltaQ[CyclicRefresh.BOOST1] = cr.qindexDelta[CyclicRefresh.BOOST1];
            p.segDeltaQ[CyclicRefresh.BOOST2] = cr.qindexDelta[CyclicRefresh.BOOST2];
        }
        if (key) {
            p.refreshFlags = 0xFF;
            p.refSearch = 0;
            for (var i = 0; i < 7; i++) {
                p.refIdx[i] = 0;
            }
        } else if (temporalLayers > 1) {
            for (var i = 0; i < 7; i++) {
                p.refIdx[i] = 7;
            }
            var phase = (int) (frameInPattern & (temporalLayers == 3 ? 3 : 1));
            // the 0-2-1-2 pattern's second top-layer frame predicts from the middle layer's slot 1
            var last = temporalLayers == 3 && phase == 3 ? 1 : 0;
            p.refIdx[0] = last;
            p.refIdx[3] = GOLDEN_SLOT;
            p.refIdx[6] = last;
            p.refreshFlags = tl == 0 ? 1 | (gfUpdate ? 1 << GOLDEN_SLOT : 0) : tl == 1 && temporalLayers == 3 ? 1 << 1 : 0;
            p.refSearch = 1 | 1 << 3;
        } else {
            var n = frameInKey;
            var last = n > 1 ? (n - 1) % LAST_SLOTS : 0;
            var lastRefresh = n % LAST_SLOTS;
            var lag = altLag();
            var alt = n > lag ? (n - lag) % LAST_SLOTS : 0;
            for (var i = 0; i < 7; i++) {
                p.refIdx[i] = 7;
            }
            p.refIdx[0] = last;
            p.refIdx[1] = lastRefresh;
            p.refIdx[3] = GOLDEN_SLOT;
            p.refIdx[6] = alt;
            p.refreshFlags = 1 << lastRefresh | (gfUpdate ? 1 << GOLDEN_SLOT : 0);
            p.refSearch = 1 | 1 << 3 | 1 << 6;
        }
        filterModel(p, key, q);
    }

    /**
     * Sets a frame's deblocking levels and CDEF strengths from its quantizer, with the models of
     * libaom's real-time mode ({@code LPF_PICK_FROM_Q}, {@code av1_pick_cdef_from_qp}).
     *
     * @param p the frame parameters
     * @param key whether the frame is a key frame
     * @param q the quantizer index
     */
    static void filterModel(FrameParams p, boolean key, int q) {
        var acq = Quantizer.acQuant(q, 0);
        int lf;
        if (key) {
            lf = (acq * 17563 - 421574 + (1 << 17)) >> 18;
        } else {
            lf = (acq * 12034 + 650707 + (1 << 17)) >> 18;
        }
        lf = Math.clamp(lf, 0, 63);
        p.lfLevelY[0] = lf;
        p.lfLevelY[1] = lf;
        p.lfLevelU = lf;
        p.lfLevelV = lf;
        p.cdefDamping = 3 + (q >> 6);
        float fq = acq;
        int yf1;
        int yf2;
        int uvf1;
        int uvf2;
        if (!key) {
            yf1 = Math.clamp(Math.round(fq * fq * -0.0000023593946f + fq * 0.0068615186f + 0.02709886f), 0, 15);
            yf2 = Math.clamp(Math.round(fq * fq * -0.00000057629734f + fq * 0.0013993345f + 0.03831067f), 0, 3);
            uvf1 = Math.clamp(Math.round(fq * fq * -0.0000007095069f + fq * 0.0034628846f + 0.00887099f), 0, 15);
            uvf2 = Math.clamp(Math.round(fq * fq * 0.00000023874085f + fq * 0.00028223585f + 0.05576307f), 0, 3);
        } else {
            yf1 = Math.clamp(Math.round(fq * fq * 0.0000033731974f + fq * 0.008070594f + 0.0187634f), 0, 15);
            yf2 = Math.clamp(Math.round(fq * fq * 0.0000029167343f + fq * 0.0027798624f + 0.0079405f), 0, 3);
            uvf1 = Math.clamp(Math.round(fq * fq * -0.0000130790995f + fq * 0.012892405f - 0.00748388f), 0, 15);
            uvf2 = Math.clamp(Math.round(fq * fq * 0.0000032651783f + fq * 0.00035520183f + 0.00228092f), 0, 3);
        }
        p.cdefYStrength = yf1 * 4 + yf2;
        p.cdefUvStrength = uvf1 * 4 + uvf2;
    }

    @Override
    public void close() {
        frame.close();
    }
}
