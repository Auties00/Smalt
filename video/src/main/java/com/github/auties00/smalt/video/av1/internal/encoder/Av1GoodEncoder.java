package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.Obu;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static com.github.auties00.smalt.video.av1.internal.encoder.FrameUpdateType.ARF;
import static com.github.auties00.smalt.video.av1.internal.encoder.FrameUpdateType.INTERNAL_ARF;
import static com.github.auties00.smalt.video.av1.internal.encoder.FrameUpdateType.INTERNAL_OVERLAY;
import static com.github.auties00.smalt.video.av1.internal.encoder.FrameUpdateType.KEY;
import static com.github.auties00.smalt.video.av1.internal.encoder.FrameUpdateType.LEAF;
import static com.github.auties00.smalt.video.av1.internal.encoder.FrameUpdateType.OVERLAY;

/**
 * Runs the good-quality encoding loop: a lookahead of source pictures, golden frame groups coded
 * as a pyramid of hidden alternate references, and a constant-quality or variable-bitrate rate
 * control that sets each frame's quantizer from its place in the pyramid.
 *
 * <p>Pictures are copied into the lookahead as they arrive; once it holds a full group (or at
 * {@link #flush}), the group is coded. A group of {@code n} frames starting after the previous
 * group's last frame codes its last frame first as a hidden alternate reference, then the frames
 * before it recursively: the middle frame of each range as a hidden internal alternate reference,
 * the frames before it, the middle frame shown again through {@code show_existing_frame}, and the
 * frames after it; ranges of fewer than three frames are coded in display order. Every coded frame
 * predicts from up to seven of the eight reference slots, mapped by display order around it: the
 * nearest past frames as {@code LAST} to {@code LAST3}, the nearest future frames as
 * {@code BWDREF} and {@code ALTREF2}, the most recent lowest-level past frame as {@code GOLDEN}
 * and the farthest lowest-level future frame as {@code ALTREF}. Key frames are placed at the
 * first picture, on request, at the key frame interval and at scene cuts the first-pass statistics
 * detect.
 *
 * <p>Each call produces the temporal units of the shown frames it completes, in display order: a
 * temporal unit holds the hidden frames coded since the previous shown frame, then the shown frame
 * or the header that shows an earlier hidden one. The stream does not depend on the thread count.
 * An instance is used by one thread at a time.
 *
 * <p>Inter frames are coded with the real-time encoder's mode decision at the relaxed
 * {@link SpeedFeatures#good} levels, adding compound and skip modes, a rate-distortion partition
 * search, transform type and size searches, the coding of the few best single-reference modes,
 * local warps and the projection of the references' motion vectors.
 *
 * @implNote This implementation ports libaom's good-quality frame structure without frame-parallel
 * reordering ({@code gop_structure.c}, {@code encode_strategy.c}), its key frame test and boosts
 * ({@code pass2_strategy.c}) and its pyramid Lagrangian multipliers ({@code rd_layer_depth_factor}).
 */
public final class Av1GoodEncoder implements Av1Engine {
    /**
     * The longest golden frame group.
     */
    static final int MAX_GF_INTERVAL = 16;

    /**
     * The shortest group coded with an alternate reference.
     */
    private static final int MIN_ARF_INTERVAL = 4;

    /**
     * The deepest pyramid level of an internal alternate reference ({@code gf_max_pyr_height}).
     */
    private static final int MAX_PYRAMID_DEPTH = 5;

    /**
     * The pyramid depth of leaves and coded overlays ({@code MAX_ARF_LAYERS}).
     */
    private static final int LEAF_DEPTH = 6;

    /**
     * The number of frames after an alternate reference the lookahead keeps for the boost and the
     * temporal filter.
     */
    static final int FRAMES_AFTER_ARF = 7;

    /**
     * The temporal filter strength ({@code arnr_strength}).
     */
    private static final int FILTER_STRENGTH = 5;

    /**
     * The base number of pictures the temporal filter averages ({@code arnr_max_frames}).
     */
    private static final int FILTER_FRAMES = 7;

    /**
     * The Lagrangian multiplier scale of each pyramid depth, in 1/128 ({@code rd_layer_depth_factor}).
     */
    private static final int[] LAYER_RD_FACTOR = {160, 160, 160, 160, 192, 208, 224};

    /**
     * The extra Lagrangian multiplier share of each boost hundred, in 1/128 ({@code rd_boost_factor}).
     */
    private static final int[] BOOST_RD_FACTOR = {64, 32, 32, 32, 24, 16, 12, 12, 8, 8, 4, 4, 2, 2, 1, 0};

    /**
     * Holds the rate control and coding settings of a {@link Av1GoodEncoder}.
     *
     * @param speed the good-quality speed level, from 2 (slowest) to 6
     * @param cqQindex the leaf quantizer index of constant quality, or -1 for a variable bitrate
     * @param bitrate the target bitrate of a variable bitrate, in bits per second
     * @param frameRate the frame rate, in frames per second
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param keyInterval the key frame interval, 0 for key frames only on request and at scene cuts
     * @param lookahead the lookahead depth, in frames; below {@value #MIN_ARF_INTERVAL} no alternate
     * references are coded
     * @param tileColsLog2 the base-2 logarithm of the tile columns
     * @param threads the number of worker threads, 0 for the pool's size
     */
    public record Settings(int speed, int cqQindex, long bitrate, double frameRate, int minQindex, int maxQindex,
                           int keyInterval, int lookahead, int tileColsLog2, int threads) {
    }

    /**
     * Holds one coded temporal unit.
     *
     * @param data the temporal unit
     * @param pts the shown frame's timestamp
     * @param duration the shown frame's duration
     * @param key whether the shown frame is a key frame
     * @param qindex the quantizer index of the shown frame, or of the hidden frame it shows
     */
    public record Unit(byte[] data, long pts, long duration, boolean key, int qindex) {
    }

    /**
     * One source picture of the lookahead.
     */
    private static final class Source {
        /**
         * The picture, owned by the lookahead.
         */
        final YuvPicture picture;

        /**
         * The timestamp.
         */
        final long pts;

        /**
         * The duration.
         */
        final long duration;

        /**
         * Whether the caller requested a key frame.
         */
        final boolean forceKey;

        /**
         * The display index.
         */
        final int display;

        /**
         * The widened luma plane, kept while later frames' statistics need it.
         */
        short[] luma;

        /**
         * The first-pass statistics.
         */
        FirstPass.Stats stats;

        /**
         * The quantizer index the frame was coded with, for the overlay that shows it.
         */
        int qindex;

        /**
         * Creates a lookahead entry.
         *
         * @param picture the picture, owned by the lookahead
         * @param pts the timestamp
         * @param duration the duration, in the time base
         * @param forceKey whether a key frame is requested
         * @param display the display index
         */
        Source(YuvPicture picture, long pts, long duration, boolean forceKey, int display) {
            this.picture = picture;
            this.pts = pts;
            this.duration = duration;
            this.forceKey = forceKey;
            this.display = display;
        }
    }

    /**
     * One step of a group's coding plan.
     *
     * @param type the step type
     * @param display the display index of the frame coded or shown
     * @param depth the pyramid depth
     */
    private record Step(FrameUpdateType type, int display, int depth) {
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
     * The frame encoder.
     */
    private final FrameEncoder frame;

    /**
     * The allocation limits.
     */
    private final MediaLimits limits;

    /**
     * The rate control.
     */
    private final GoodRateControl rc = new GoodRateControl();

    /**
     * The parameters of the frame being coded.
     */
    private final FrameParams params = new FrameParams();

    /**
     * The lookahead, in display order, from the first frame not yet shown.
     */
    private final ArrayDeque<Source> queue = new ArrayDeque<>();

    /**
     * The last frames pushed, newest last, whose luma the next statistics need.
     */
    private final ArrayDeque<Source> recent = new ArrayDeque<>();

    /**
     * The display index of the frame in each reference slot, -1 when empty.
     */
    private final int[] slotDisplay = new int[FrameEncoder.SLOTS];

    /**
     * The pyramid level of the frame in each reference slot.
     */
    private final int[] slotLevel = new int[FrameEncoder.SLOTS];

    /**
     * The coding order of the frame in each reference slot.
     */
    private final long[] slotCoded = new long[FrameEncoder.SLOTS];

    /**
     * The picture each reference slot's frame was coded from.
     */
    private final YuvPicture[] slotPicture = new YuvPicture[FrameEncoder.SLOTS];

    /**
     * The quantizer index of the frame in each reference slot.
     */
    private final int[] slotQindex = new int[FrameEncoder.SLOTS];

    /**
     * The temporal unit being assembled.
     */
    private final ByteArrayOutputStream unit = new ByteArrayOutputStream(1 << 16);

    /**
     * The percentage of libaom's frame-layer Lagrangian multiplier the mode decision uses: the
     * decision's rate estimates run high against the distortion they are weighed with, and a
     * smaller multiplier compensates.
     */
    private static final int LAMBDA_PERCENT = 70;

    /**
     * The good-quality speed level, 2 to 6.
     */
    private int speed = 4;

    /**
     * The key frame interval, 0 for key frames only on request and at scene cuts.
     */
    private int keyInterval;

    /**
     * The lookahead depth in frames.
     */
    private int lookahead = 35;

    /**
     * The base-2 logarithm of the tile columns.
     */
    private int tileColsLog2;

    /**
     * The number of worker threads, 0 for the pool's size.
     */
    private int threads;

    /**
     * The display index of the next pushed picture.
     */
    private int nextDisplay;

    /**
     * The number of frames coded, the coding order of the next one.
     */
    private long coded;

    /**
     * The display index of the last key frame, or -1 before the first.
     */
    private int lastKeyDisplay = -1;

    /**
     * The boost of the group being coded.
     */
    private int gfuBoost = 100;

    /**
     * The boost of the last key frame.
     */
    private int kfBoost = 600;

    /**
     * The temporally filtered picture of the frame being coded, or {@code null} to code its
     * source.
     */
    private YuvPicture filtered;

    /**
     * Whether the current group's alternate reference is shown through
     * {@code show_existing_frame}, rather than by a coded overlay of its source.
     */
    private boolean showArf = true;

    /**
     * The lookahead position of the first frame of the group being coded after its key frame.
     */
    private int groupStart;

    /**
     * The lookahead position of the next key frame after the group's first frame, or the
     * lookahead size when none is known.
     */
    private int nextKey;

    /**
     * Whether the temporal dependency model sets the quantizers of key frames and alternate
     * references.
     */
    private boolean tpl = true;

    /**
     * The importance the dependency model gives the current group's key frame, or 0 when none.
     */
    private double keyImportance;

    /**
     * The importance the dependency model gives the current group's alternate reference, or 0
     * when none.
     */
    private double arfImportance;

    /**
     * The quantizer index of the last shown frame.
     */
    private int lastQindex;

    /**
     * Whether the last shown frame was a key frame.
     */
    private boolean lastKey;

    /**
     * Creates the loop of a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @param color the color description signaled in the sequence header
     * @param limits the allocation limits
     */
    public Av1GoodEncoder(int width, int height, ColorInfo color, MediaLimits limits) {
        this.width = width;
        this.height = height;
        var seq = FrameEncoder.sequenceHeader(width, height, color, ObuWriter.level(width, height), true, true);
        this.frame = new FrameEncoder(width, height, seq, limits);
        this.limits = limits;
        Arrays.fill(slotDisplay, -1);
    }

    /**
     * Configures the loop; the settings apply from the next group.
     *
     * @param settings the rate control and coding settings
     */
    public void configure(Settings settings) {
        this.speed = Math.clamp(settings.speed(), 2, 6);
        this.keyInterval = Math.max(0, settings.keyInterval());
        this.lookahead = Math.max(1, settings.lookahead());
        this.tileColsLog2 = settings.tileColsLog2();
        this.threads = settings.threads();
        rc.configure(settings.cqQindex(), settings.bitrate(), settings.frameRate(), Math.max(1, settings.minQindex()),
                settings.maxQindex());
    }

    @Override
    public byte[] codecConfigurationRecord() {
        return ObuWriter.av1Config(frame.sequenceHeader(), frame.sequenceHeaderObu());
    }

    /**
     * Returns the quantizer index of the last shown frame.
     *
     * @return the index
     */
    public int lastQindex() {
        return lastQindex;
    }

    /**
     * Returns whether the last shown frame was a key frame.
     *
     * @return {@code true} for a key frame
     */
    public boolean lastKeyFrame() {
        return lastKey;
    }

    /**
     * Adds a picture to the lookahead and codes every group the lookahead completes.
     *
     * @param picture the 4:2:0 picture of the configured size; it is copied
     * @param pts the timestamp
     * @param duration the duration, in the time base
     * @param forceKey whether the frame must be a key frame
     * @param output the receiver of the coded temporal units
     */
    public void encode(YuvPicture picture, long pts, long duration, boolean forceKey, Consumer<Unit> output) {
        var copy = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, picture.color());
        Planes.copy(picture.y(), 0, picture.yStride(), copy.y(), 0, width, width, height);
        var cw = (width + 1) >> 1;
        var ch = (height + 1) >> 1;
        Planes.copy(picture.u(), 0, picture.uvStride(), copy.u(), 0, cw, cw, ch);
        Planes.copy(picture.v(), 0, picture.uvStride(), copy.v(), 0, cw, cw, ch);
        var source = new Source(copy, pts, duration, forceKey, nextDisplay++);
        measure(source);
        queue.add(source);
        while (queue.size() >= lookahead) {
            codeGroup(false, output);
        }
    }

    /**
     * Codes every picture left in the lookahead.
     *
     * @param output the receiver of the coded temporal units
     */
    public void flush(Consumer<Unit> output) {
        while (!queue.isEmpty()) {
            codeGroup(true, output);
        }
    }

    /**
     * Computes a new lookahead picture's first-pass statistics against the two pictures before it.
     *
     * @param source the new picture
     */
    private void measure(Source source) {
        // TODO: a true two-pass mode that measures the whole stream before coding it, for files
        // whose length is known; the statistics now cover the lookahead only.
        source.luma = FirstPass.luma(source.picture);
        var last = recent.peekLast();
        Source second = null;
        if (recent.size() >= 2) {
            var it = recent.descendingIterator();
            it.next();
            second = it.next();
        }
        var bands = threads > 0 ? Math.min(threads, ParallelBands.THREADS) : ParallelBands.THREADS;
        source.stats = FirstPass.measure(source.luma, last == null || source.forceKey ? null : last.luma,
                second == null ? null : second.luma, width, height, bands);
        rc.observe(source.stats.codedError);
        recent.add(source);
        while (recent.size() > 2) {
            recent.removeFirst().luma = null;
        }
    }

    /**
     * Returns whether a lookahead frame starts a new key frame group: the first frame, a requested
     * key frame, the key frame interval or a scene cut ({@code test_candidate_kf}).
     *
     * @param index the frame's position in the lookahead
     * @param sinceKey the number of frames since the last key frame
     * @return {@code true} for a key frame
     */
    private boolean keyAt(int index, int sinceKey) {
        var s = get(index);
        if (lastKeyDisplay < 0 && index == 0 || s.forceKey) {
            return true;
        }
        if (keyInterval > 0 && sinceKey >= keyInterval) {
            return true;
        }
        if (index == 0 || index + 1 >= queue.size() || sinceKey < 3) {
            return false;
        }
        var last = get(index - 1).stats;
        var cur = s.stats;
        var next = get(index + 1).stats;
        var thresh = 0.085 + Math.min(sinceKey, 31) / 31.0 * 0.035;
        var pcntIntra = 1.0 - cur.pcntInter;
        var modifiedInter = cur.pcntInter - cur.pcntNeutral;
        var slide = cur.intraError < cur.codedError * 1.5 && cur.codedError > last.codedError * 5.0
                && cur.codedError > next.codedError * 5.0;
        if (!(cur.pcntSecondRef < thresh && next.pcntSecondRef < thresh
                && (cur.pcntInter < 0.05 || slide || (pcntIntra > 0.5 && pcntIntra > 1.9 * modifiedInter
                && cur.intraError / Math.max(cur.codedError, 1e-6) < 6.0
                && (Math.abs(last.codedError - cur.codedError) / Math.max(cur.codedError, 1e-6) > 0.4
                || Math.abs(last.intraError - cur.intraError) / Math.max(cur.intraError, 1e-6) > 0.4
                || next.intraError / Math.max(next.codedError, 1e-6) > 3.5))))) {
            return false;
        }
        var decay = 1.0;
        var boost = 0.0;
        var oldBoost = 0.0;
        var tested = 0;
        for (var i = 1; i <= 16 && index + i < queue.size(); i++) {
            var f = get(index + i).stats;
            var ii = Math.min(12.5 * f.intraError / Math.max(f.codedError, 1e-6), 128.0);
            decay *= f.pcntInter > 0.85 ? f.pcntInter : (0.85 + f.pcntInter) / 2.0;
            boost += decay * ii;
            tested = i;
            if (f.pcntInter < 0.05 || ii < 1.5 || (boost - oldBoost < 3.0) || f.intraError < 200) {
                break;
            }
            oldBoost = boost;
        }
        return boost > 30.0 && tested > 3;
    }

    /**
     * Returns a lookahead entry.
     *
     * @param index the position in the lookahead
     * @return the entry
     */
    private Source get(int index) {
        var i = 0;
        for (var s : queue) {
            if (i++ == index) {
                return s;
            }
        }
        throw new IndexOutOfBoundsException(index);
    }

    /**
     * Plans and codes the group at the head of the lookahead.
     *
     * @param flushing whether the lookahead is being drained
     * @param output the receiver of the coded temporal units
     */
    private void codeGroup(boolean flushing, Consumer<Unit> output) {
        var head = queue.peekFirst();
        var sinceKey = lastKeyDisplay < 0 ? 0 : head.display - lastKeyDisplay;
        var steps = new ArrayList<Step>();
        var start = 0;
        if (keyAt(0, sinceKey)) {
            steps.add(new Step(KEY, head.display, 0));
            start = 1;
            sinceKey = 0;
            kfBoost = keyBoost();
        }
        var available = queue.size() - start;
        var n = Math.min(MAX_GF_INTERVAL, available);
        if (!flushing) {
            n = Math.min(n, Math.max(1, available - FRAMES_AFTER_ARF));
        }
        nextKey = queue.size();
        for (var i = start + 1; i < queue.size(); i++) {
            if (keyAt(i, sinceKey + i)) {
                nextKey = i;
                break;
            }
        }
        n = Math.min(n, nextKey - start);
        rc.lastGroup(start + n == nextKey && (nextKey < queue.size() || flushing));
        var first = head.display + start;
        groupStart = start;
        if (n > 0) {
            var arf = n >= MIN_ARF_INTERVAL && lookahead >= MIN_ARF_INTERVAL;
            if (arf) {
                gfuBoost = arfBoost(start + n - 1, n - 1);
                steps.add(new Step(ARF, first + n - 1, 1));
                pyramid(steps, first, first + n - 1, 2);
                steps.add(new Step(OVERLAY, first + n - 1, LEAF_DEPTH));
            } else {
                gfuBoost = 100;
                for (var i = 0; i < n; i++) {
                    steps.add(new Step(LEAF, first + i, LEAF_DEPTH));
                }
            }
        }
        runTpl(steps, start + n);
        rc.importance(keyImportance, arfImportance);
        planRate(steps, start + n);
        for (var step : steps) {
            codeStep(step, output);
        }
        for (var i = 0; i < start + n; i++) {
            queue.removeFirst();
        }
    }

    /**
     * Runs the temporal dependency model over a group's coded frames, the reference frames they
     * predict from and up to a group of the frames that follow it, and keeps the importance of the
     * group's key frame and alternate reference for their quantizers.
     *
     * @param steps the group's steps
     * @param frames the number of lookahead frames the group shows
     */
    private void runTpl(List<Step> steps, int frames) {
        keyImportance = 0;
        arfImportance = 0;
        if (!tpl) {
            return;
        }
        var display = Arrays.copyOf(slotDisplay, FrameEncoder.SLOTS);
        var level = Arrays.copyOf(slotLevel, FrameEncoder.SLOTS);
        var slotFrame = new int[FrameEncoder.SLOTS];
        var list = new ArrayList<TplModel.Frame>();
        for (var s = 0; s < FrameEncoder.SLOTS; s++) {
            slotFrame[s] = -1;
            if (display[s] < 0) {
                continue;
            }
            for (var t = 0; t < s; t++) {
                if (display[t] == display[s]) {
                    slotFrame[s] = slotFrame[t];
                }
            }
            if (slotFrame[s] < 0) {
                slotFrame[s] = list.size();
                list.add(new TplModel.Frame(slotPicture[s], new int[0], false));
            }
        }
        var refIdx = new int[7];
        var keyIndex = -1;
        var arfIndex = -1;
        var arfDisplay = -1;
        var byDisplay = new HashMap<Integer, Integer>();
        for (var step : steps) {
            if (step.type == OVERLAY || step.type == INTERNAL_OVERLAY) {
                continue;
            }
            var picture = sourceOf(step.display).picture;
            int[] refs;
            if (step.type == KEY) {
                refs = new int[0];
            } else {
                mapReferences(display, level, step.display, refIdx);
                refs = distinctFrames(refIdx, slotFrame);
            }
            var index = list.size();
            list.add(new TplModel.Frame(picture, refs, true));
            byDisplay.put(step.display, index);
            var frameLevel = step.type == KEY ? 0 : step.type == ARF ? 1 : step.type == INTERNAL_ARF ? step.depth
                    : LEAF_DEPTH;
            if (step.type == KEY) {
                keyIndex = index;
                Arrays.fill(display, step.display);
                Arrays.fill(level, 0);
                Arrays.fill(slotFrame, index);
            } else {
                var slot = refreshSlot(display, level, step.type == ARF, step.display);
                display[slot] = step.display;
                level[slot] = frameLevel;
                slotFrame[slot] = index;
                if (step.type == ARF) {
                    arfIndex = index;
                    arfDisplay = step.display;
                }
            }
        }
        if (arfIndex >= 0) {
            var previous = arfIndex;
            for (var i = frames; i < queue.size() && i < frames + MAX_GF_INTERVAL; i++) {
                var s = get(i);
                if (s.forceKey) {
                    break;
                }
                var refs = previous == arfIndex ? new int[]{arfIndex} : new int[]{previous, arfIndex};
                list.add(new TplModel.Frame(s.picture, refs, true));
                previous = list.size() - 1;
            }
        }
        if (keyIndex < 0 && arfIndex < 0) {
            return;
        }
        var model = list.toArray(TplModel.Frame[]::new);
        var bands = threads > 0 ? Math.min(threads, ParallelBands.THREADS) : ParallelBands.THREADS;
        TplModel.run(model, width, height, bands);
        if (keyIndex >= 0) {
            keyImportance = model[keyIndex].importance;
        }
        if (arfIndex >= 0) {
            arfImportance = model[arfIndex].importance;
        }
    }

    /**
     * Returns the model positions of the distinct frames a frame's references point to.
     *
     * @param refIdx the slot of each reference
     * @param slotFrame the model position of each slot's frame
     * @return the positions, duplicates removed
     */
    private static int[] distinctFrames(int[] refIdx, int[] slotFrame) {
        var out = new int[7];
        var n = 0;
        for (var slot : refIdx) {
            var frame = slotFrame[slot];
            if (frame < 0) {
                continue;
            }
            var dup = false;
            for (var i = 0; i < n; i++) {
                if (out[i] == frame) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out[n++] = frame;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Appends the coding steps of the frames of a display range below an alternate reference
     * ({@code set_multi_layer_params}).
     *
     * @param steps the group's coding steps, appended to
     * @param start the first display index
     * @param end the display index after the last
     * @param depth the pyramid depth of an internal alternate reference of the range
     */
    private static void pyramid(List<Step> steps, int start, int end, int depth) {
        if (depth > MAX_PYRAMID_DEPTH || end - start < 3) {
            for (var d = start; d < end; d++) {
                steps.add(new Step(LEAF, d, LEAF_DEPTH));
            }
            return;
        }
        var m = (start + end - 1) / 2;
        steps.add(new Step(INTERNAL_ARF, m, depth));
        pyramid(steps, start, m, depth + 1);
        steps.add(new Step(INTERNAL_OVERLAY, m, depth));
        pyramid(steps, m + 1, end, depth + 1);
    }

    /**
     * Chooses the group's leaf quantizer.
     *
     * @param steps the group's steps
     * @param frames the number of lookahead frames the group shows
     */
    private void planRate(List<Step> steps, int frames) {
        var count = 0;
        for (var step : steps) {
            if (step.type != INTERNAL_OVERLAY && step.type != OVERLAY) {
                count++;
            }
        }
        var types = new FrameUpdateType[count];
        var depths = new int[count];
        var i = 0;
        for (var step : steps) {
            if (step.type != INTERNAL_OVERLAY && step.type != OVERLAY) {
                types[i] = step.type;
                depths[i++] = step.depth;
            }
        }
        var err = 0.0;
        for (var k = 0; k < frames; k++) {
            err += get(k).stats.codedError;
        }
        rc.planGroup(types, depths, frames, err / Math.max(1, frames), gfuBoost, kfBoost, blocks(),
                (long) width * height, lastStreamFrame());
    }

    /**
     * Returns whether the lookahead's head is the only frame left of a drained stream.
     *
     * @return {@code true} when one frame is left
     */
    private boolean lastStreamFrame() {
        return queue.size() == 1;
    }

    /**
     * Returns the number of 16x16 blocks of a picture.
     *
     * @return the count
     */
    private long blocks() {
        return (long) ((width + 15) >> 4) * ((height + 15) >> 4);
    }

    /**
     * Returns the boost of an alternate reference: how much the frames around it predict from it
     * ({@code av1_calc_arf_boost}).
     *
     * @param offset the alternate reference's position in the lookahead
     * @param backward the number of frames before it in its group
     * @return the boost
     */
    private int arfBoost(int offset, int backward) {
        var leafQ = RateControl.qindexToQ(rc.leafQindex());
        var correction = Math.min(0.5 + leafQ * 0.015, 1.5);
        var boost = 100.0;
        var decay = 1.0;
        for (var i = 0; i < MAX_GF_INTERVAL && offset + i < queue.size(); i++) {
            var s = get(offset + i).stats;
            decay = Math.max(decay * decayRate(s), 0.01);
            boost += decay * frameBoost(s, correction);
        }
        decay = 1.0;
        for (var i = 1; i <= backward && offset - i >= 0; i++) {
            var s = get(offset - i).stats;
            decay = Math.max(decay * decayRate(s), 0.01);
            boost += decay * frameBoost(s, correction);
        }
        return (int) boost;
    }

    /**
     * Returns the boost one frame contributes to an alternate reference ({@code calc_frame_boost}).
     *
     * @param s the frame's statistics
     * @param correction the quantizer correction
     * @return the boost
     */
    private double frameBoost(FirstPass.Stats s, double correction) {
        var baseline = (long) width * height <= 640L * 360 ? 500.0 : 1000.0;
        var boost = Math.max(baseline, s.intraError) / Math.max(s.codedError, 1e-6) * 12.5 * correction;
        if (s.mvInOutCount * s.pcntMotion > 0) {
            boost += boost * (s.mvInOutCount * s.pcntMotion * 2.0);
        } else {
            boost += boost * (s.mvInOutCount * s.pcntMotion / 2.0);
        }
        return Math.min(boost, 90.0 * correction);
    }

    /**
     * Returns how much the prediction quality decays over one frame ({@code get_prediction_decay_rate}).
     *
     * @param s the frame's statistics
     * @return the decay, 0 to 1
     */
    private static double decayRate(FirstPass.Stats s) {
        var srDecay = srDecayRate(s);
        var zeroMotion = Math.clamp(0.5 * (s.pcntInter - s.pcntMotion), 0.0, 1.0);
        return Math.max(zeroMotion, srDecay + (1.0 - srDecay) * zeroMotion);
    }

    /**
     * Returns the decay of the second reference's prediction ({@code get_sr_decay_rate}).
     *
     * @param s the frame's statistics
     * @return the decay
     */
    private static double srDecayRate(FirstPass.Stats s) {
        var srDiff = s.srCodedError - s.codedError;
        var modifiedInter = s.pcntInter;
        if (s.codedError > 0.01 && s.intraError / Math.max(s.codedError, 1e-6) < 5.0) {
            modifiedInter = s.pcntInter - s.pcntNeutral;
        }
        var modifiedIntra = 100 * (1.0 - modifiedInter);
        var decay = 1.0;
        if (srDiff > 0.01) {
            decay = 1.0 - srDiff * 0.25 / s.intraError - 0.005 * modifiedIntra;
        }
        return Math.max(decay, 0.75);
    }

    /**
     * Returns the boost of the key frame at the head of the lookahead ({@code get_kf_boost_score}).
     *
     * @return the boost
     */
    private int keyBoost() {
        var leafQ = RateControl.qindexToQ(rc.leafQindex());
        var correction = Math.min(0.5 + leafQ * 0.015, 2.0);
        var baseline = (long) width * height <= 640L * 360 ? 500.0 : 1000.0;
        var rawErr = get(0).stats.intraError;
        var sr = 0.0;
        var zeroMotion = 1.0;
        var boost = 0.0;
        var frames = 0;
        for (var i = 1; i < queue.size(); i++) {
            var s = get(i).stats;
            if (s.pcntInter < 0.05 && i > 1) {
                break;
            }
            zeroMotion = i > 1 ? Math.min(zeroMotion, Math.min(srDecayRate(s), s.pcntInter - s.pcntMotion))
                    : s.pcntInter - s.pcntMotion;
            if (sr < rawErr * 1.5 && i <= MAX_GF_INTERVAL * 2) {
                if (i < 2) {
                    sr = 0;
                }
                var frameBoost = Math.max(baseline, s.intraError) / Math.max(s.codedError + sr, 1e-6);
                sr = Math.max(0.0, sr + s.srCodedError - s.codedError);
                frameBoost = (frameBoost + 40.0) * correction;
                boost += Math.min(frameBoost, 128.0 * correction) * (0.75 + zeroMotion / 2.0);
            }
            frames++;
        }
        var projected = frames > 0 ? boost * Math.sqrt(Math.max(1.0, 150.0 / frames)) : 600;
        return (int) Math.max(600, projected);
    }

    /**
     * Codes one step: a hidden or shown frame, or the header that shows a hidden one, and outputs
     * the temporal unit a shown frame completes.
     *
     * @param step the coding step
     * @param output the receiver of the coded temporal units
     */
    private void codeStep(Step step, Consumer<Unit> output) {
        var source = sourceOf(step.display);
        var startUnit = unit.size() == 0;
        var codedOverlay = step.type == OVERLAY && !showArf;
        if (step.type == INTERNAL_OVERLAY || (step.type == OVERLAY && !codedOverlay)) {
            var slot = slotOf(step.display);
            unit.writeBytes(frame.showExisting(slot, startUnit));
            emit(source, false, slotQindex[slot], output);
            return;
        }
        var key = step.type == KEY;
        var p = params;
        // TODO: search every inter mode and interpolation filter by the coded residual's real cost
        // (libaom's rdopt.c; only the few best single-reference modes and the motion mode are measured
        // now), add AB and four-way partitions, wedge and difference compound masks, inter-intra,
        // OBMC and global motion parameters, and refine the vector of a local warp.
        // TODO: per-superblock quantizer offsets from the dependency model (deltaq-mode) and loop
        // restoration searches on inter frames.
        var q = rc.qindex(step.type, step.depth, rc.leafQindex(), gfuBoost, kfBoost, (long) width * height,
                lastStreamFrame());
        p.key = key;
        p.errorResilient = false;
        p.orderHint = step.display;
        p.qindex = q;
        p.speed = 7;
        p.goodSpeed = speed;
        p.threads = threads;
        p.tileColsLog2 = tileColsLog2;
        p.txSwitchable = true;
        p.allowHighPrecisionMv = !key && q < 128;
        p.interpFilter = key ? 0 : FrameHeader.FILTER_SWITCHABLE;
        p.goldenUpdate = step.type == ARF;
        p.temporalId = 0;
        p.temporalLayers = 1;
        p.segmentation = false;
        Arrays.fill(p.segDeltaQ, 0);
        p.showFrame = step.type == KEY || step.type == LEAF || codedOverlay;
        p.temporalDelimiter = startUnit;
        p.referenceSelect = false;
        p.warpedMotion = !key;
        p.refFrameMvs = !key;
        p.loopFilterSearch = !key;
        p.cdefSearch = key ? 0 : speed <= 3 ? 4 : speed <= 5 ? 3 : 2;
        var boostIndex = Math.min(15, gfuBoost / 100);
        var rdScale = key ? 128 : LAYER_RD_FACTOR[Math.min(step.depth, LEAF_DEPTH)];
        if (!key) {
            rdScale += (rdScale * BOOST_RD_FACTOR[boostIndex]) >> 7;
        }
        p.rdmultScale = rdScale * LAMBDA_PERCENT / 100;
        Av1RtEncoder.filterModel(p, key, q);
        if (p.loopFilterSearch && p.lfLevelY[0] == 0) {
            p.lfLevelY[0] = 1;
            p.lfLevelY[1] = 1;
            p.lfLevelU = 1;
            p.lfLevelV = 1;
        }
        var level = switch (step.type) {
            case KEY -> 0;
            case ARF -> 1;
            case INTERNAL_ARF -> step.depth;
            case LEAF, OVERLAY, INTERNAL_OVERLAY -> LEAF_DEPTH;
        };
        YuvPicture previous = null;
        if (key) {
            p.refreshFlags = 0xFF;
            p.refSearch = 0;
            p.primaryRefFrame = 0;
            Arrays.fill(p.refIdx, 0);
        } else {
            mapReferences(slotDisplay, slotLevel, step.display, p.refIdx);
            p.refSearch = searchMask(p.refIdx);
            p.primaryRefFrame = primaryRef(p.refIdx, level);
            p.refreshFlags = codedOverlay ? 0 : 1 << refreshSlot(slotDisplay, slotLevel, step.type == ARF, step.display);
            previous = slotPicture[p.refIdx[0]];
            p.referenceSelect = bidirectional(p.refIdx, step.display);
            p.skipMode = p.referenceSelect;
        }
        if (key || step.type == ARF || (step.type == INTERNAL_ARF && step.depth == 2)) {
            filter(step, q);
        }
        var picture = filtered != null ? filtered : source.picture;
        filtered = null;
        byte[] data;
        if (key) {
            data = codeKey(picture, step.display, q, startUnit);
        } else {
            frame.load(picture, previous);
            data = frame.encode(p);
        }
        unit.writeBytes(data);
        rc.update(step.type, q, (long) data.length * 8, blocks());
        for (var s = 0; s < FrameEncoder.SLOTS; s++) {
            if ((p.refreshFlags & (1 << s)) != 0) {
                slotDisplay[s] = step.display;
                slotLevel[s] = level;
                slotCoded[s] = coded;
                slotPicture[s] = picture;
                slotQindex[s] = q;
            }
        }
        coded++;
        source.qindex = q;
        if (key) {
            lastKeyDisplay = step.display;
        }
        if (p.showFrame) {
            emit(source, key, q, output);
        }
    }

    /**
     * Codes a key frame with the still picture search and makes it the content of every reference
     * slot.
     *
     * @param picture the picture to code
     * @param display the display index
     * @param q the quantizer index
     * @param startUnit whether the frame starts a temporal unit
     * @return the temporal delimiter when starting a unit, the sequence header OBU and the frame
     * OBU
     */
    private byte[] codeKey(YuvPicture picture, int display, int q, boolean startUnit) {
        var settings = StillSettings.of(q, keyStillSpeed()).tiles(tileColsLog2, 0).threads(threads).forGoodKey();
        try (var still = new StillFrameEncoder(width, height, frame.sequenceHeader(), settings, limits)) {
            var obu = still.encodeKeyFrame(new byte[][]{picture.y(), picture.u(), picture.v()},
                    new int[]{picture.yStride(), picture.uvStride(), picture.uvStride()}, display);
            frame.installKeyFrame(still.reconstruction(), still.header());
            var seqObu = frame.sequenceHeaderObu();
            var out = new ByteArrayOutputStream(obu.length + seqObu.length + 8);
            if (startUnit) {
                ObuWriter.writeTemporalDelimiter(out);
            }
            ObuWriter.writeObu(out, Obu.OBU_SEQ_HDR, seqObu, 0, seqObu.length);
            out.writeBytes(obu);
            return out.toByteArray();
        }
    }

    /**
     * Returns the still picture speed key frames are searched at: one level slower than the
     * good-quality level from level 4 down, the default still speed 6 above it.
     *
     * @return the still speed
     */
    private int keyStillSpeed() {
        return speed <= 4 ? speed + 1 : 6;
    }

    /**
     * Filters the source of a key frame or alternate reference with its neighbors in the
     * lookahead, leaving the result in {@link #filtered}, and decides whether the group's
     * alternate reference is shown as filtered ({@code tf_setup_filtering_buffer},
     * {@code av1_check_show_filtered_frame}).
     *
     * <p>A key frame averages up to the following pictures before the next key frame; an
     * alternate reference up to as many on each side, within its group and before the next key
     * frame, their number limited by its boost; a key frame without measurable noise is not
     * filtered. The base number of pictures grows by six for a key frame and, for an alternate
     * reference, by an amount that falls with the frame's noise and, from level 5, is four, two
     * or none. The first internal alternate reference of a group, the one in the middle, averages
     * at most three pictures and is coded from the filtered picture only when that stays close to
     * its source; the other internal alternate references are coded from their sources.
     *
     * @param step the step of the frame
     * @param q the frame's quantizer index
     */
    private void filter(Step step, int q) {
        if (step.type != INTERNAL_ARF) {
            showArf = true;
        }
        var index = 0;
        for (var s : queue) {
            if (s.display == step.display) {
                break;
            }
            index++;
        }
        var key = step.type == KEY;
        var center = sourceOf(step.display).picture;
        var noise = TemporalFilter.noiseLevel(center, 0);
        if (key && noise <= 0) {
            return;
        }
        var adjust = 6;
        if (!key) {
            adjust = speed >= 5 ? (noise < 0.5 ? 4 : noise < 1.0 ? 2 : 0) : (noise < 0.5 ? 6 : noise < 1.0 ? 4 : 2);
        }
        var frames = Math.min(FILTER_FRAMES + adjust, queue.size());
        var maxAfter = Math.max(0, nextKey - index - 1);
        var maxBefore = key ? 0 : index - groupStart;
        int before;
        int after;
        if (key) {
            before = 0;
            after = Math.min(frames - 1, maxAfter);
        } else {
            frames = Math.min(frames, gfuBoost / 150);
            frames += (frames & 1) == 0 ? 1 : 0;
            if (step.type == INTERNAL_ARF) {
                frames = Math.min(frames, 3);
            }
            if (Math.min(maxAfter, maxBefore) >= frames / 2) {
                before = frames / 2;
                after = frames / 2;
            } else if (maxAfter < frames / 2) {
                after = maxAfter;
                before = Math.min(frames - 1 - after, maxBefore);
            } else {
                before = maxBefore;
                after = Math.min(frames - 1 - before, maxAfter);
            }
        }
        if (before + after == 0) {
            return;
        }
        var pictures = new ArrayList<YuvPicture>(before + after + 1);
        for (var i = index - before; i <= index + after; i++) {
            pictures.add(get(i).picture);
        }
        var bands = threads > 0 ? Math.min(threads, ParallelBands.THREADS) : ParallelBands.THREADS;
        var result = TemporalFilter.filter(pictures, before, rc.leafQindex(), FILTER_STRENGTH, key, bands);
        filtered = result.picture();
        if (step.type == INTERNAL_ARF) {
            if (!TemporalFilter.showFiltered(result, q)) {
                filtered = null;
            }
        } else if (!key) {
            showArf = TemporalFilter.showFiltered(result, q);
        }
    }

    /**
     * Outputs the assembled temporal unit of a shown frame.
     *
     * @param source the shown frame
     * @param key whether it is a key frame
     * @param qindex its quantizer index
     * @param output the receiver
     */
    private void emit(Source source, boolean key, int qindex, Consumer<Unit> output) {
        var data = unit.toByteArray();
        unit.reset();
        lastQindex = qindex;
        lastKey = key;
        output.accept(new Unit(data, source.pts, source.duration, key, qindex));
    }

    /**
     * Returns the lookahead entry of a display index.
     *
     * @param display the display index
     * @return the entry
     */
    private Source sourceOf(int display) {
        for (var s : queue) {
            if (s.display == display) {
                return s;
            }
        }
        throw new IllegalStateException("frame " + display + " left the lookahead");
    }

    /**
     * Returns the reference slot holding a display index.
     *
     * @param display the display index
     * @return the slot
     */
    private int slotOf(int display) {
        for (var s = 0; s < FrameEncoder.SLOTS; s++) {
            if (slotDisplay[s] == display) {
                return s;
            }
        }
        throw new IllegalStateException("frame " + display + " is not held by a reference slot");
    }

    /**
     * Maps the seven references of a frame to reference slots by display order
     * ({@code av1_get_ref_frames}).
     *
     * @param slotDisplay the display index of each slot's frame, -1 when empty
     * @param slotLevel the pyramid level of each slot's frame
     * @param cur the frame's display index
     * @param refIdx receives the slot of each reference, {@code LAST} first
     */
    private static void mapReferences(int[] slotDisplay, int[] slotLevel, int cur, int[] refIdx) {
        Arrays.fill(refIdx, -1);
        var slots = new int[FrameEncoder.SLOTS];
        var disp = new int[FrameEncoder.SLOTS];
        var lvl = new int[FrameEncoder.SLOTS];
        var used = new boolean[FrameEncoder.SLOTS];
        var n = 0;
        var minLevel = Integer.MAX_VALUE;
        for (var s = 0; s < FrameEncoder.SLOTS; s++) {
            var d = slotDisplay[s];
            if (d < 0) {
                continue;
            }
            var dup = false;
            for (var i = 0; i < n; i++) {
                if (disp[i] == d) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            slots[n] = s;
            disp[n] = d;
            lvl[n] = slotLevel[s];
            minLevel = Math.min(minLevel, slotLevel[s]);
            n++;
        }
        for (var i = 1; i < n; i++) {
            for (var j = i; j > 0 && disp[j - 1] > disp[j]; j--) {
                swap(slots, j);
                swap(disp, j);
                swap(lvl, j);
            }
        }
        var minCount = 0;
        var closestPast = -1;
        var golden = -1;
        var altref = -1;
        for (var i = n - 1; i >= 0; i--) {
            if (lvl[i] == minLevel) {
                minCount++;
                if (disp[i] < cur && golden == -1) {
                    golden = i;
                } else if (disp[i] > cur && altref == -1) {
                    altref = i;
                }
            } else if (disp[i] == cur) {
                refIdx[4] = slots[i];
                used[i] = true;
            }
            if (disp[i] < cur && closestPast < 0) {
                closestPast = i;
            }
        }
        if (golden > -1) {
            refIdx[3] = slots[golden];
            used[golden] = true;
        }
        if (altref > -1) {
            refIdx[6] = slots[altref];
            used[altref] = true;
        }
        if (n > 7) {
            var maxDist = 0;
            var unmapped = -1;
            for (var i = 0; i < n; i++) {
                if (used[i]) {
                    continue;
                }
                if (lvl[i] != minLevel || minCount >= 5) {
                    var dist = Math.abs(cur - disp[i]);
                    if (dist > maxDist) {
                        maxDist = dist;
                        unmapped = i;
                    }
                }
            }
            if (unmapped >= 0) {
                used[unmapped] = true;
            }
        }
        for (var ref = 0; ref < 3; ref++) {
            if (refIdx[ref] != -1) {
                continue;
            }
            var best = -1;
            for (var i = n - 1; i >= 0; i--) {
                if (!used[i] && disp[i] < cur && (best < 0 || disp[i] > disp[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            refIdx[ref] = slots[best];
            used[best] = true;
        }
        for (var ref = 4; ref < 7; ref++) {
            if (refIdx[ref] != -1) {
                continue;
            }
            var best = -1;
            for (var i = n - 1; i >= 0; i--) {
                if (!used[i] && disp[i] > cur && (best < 0 || disp[i] < disp[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            refIdx[ref] = slots[best];
            used[best] = true;
        }
        var idx = closestPast;
        for (var ref = 0; ref < 7; ref++) {
            if (refIdx[ref] != -1) {
                continue;
            }
            while (idx >= 0 && used[idx]) {
                idx--;
            }
            if (idx < 0) {
                break;
            }
            refIdx[ref] = slots[idx];
            used[idx] = true;
        }
        idx = n - 1;
        for (var ref = 6; ref >= 0; ref--) {
            if (refIdx[ref] != -1) {
                continue;
            }
            while (idx > closestPast && used[idx]) {
                idx--;
            }
            if (idx <= closestPast || idx < 0) {
                break;
            }
            refIdx[ref] = slots[idx];
            used[idx] = true;
        }
        var fallback = refIdx[0] != -1 ? refIdx[0] : slots[0];
        for (var ref = 0; ref < 7; ref++) {
            if (refIdx[ref] == -1) {
                refIdx[ref] = fallback;
            }
        }
    }

    /**
     * Returns whether a frame has both a past and a future reference, so its blocks may average
     * the two.
     *
     * @param refIdx the slot of each reference
     * @param cur the frame's display index
     * @return {@code true} when bidirectional compound prediction is possible
     */
    private boolean bidirectional(int[] refIdx, int cur) {
        var past = false;
        var future = false;
        for (var slot : refIdx) {
            past |= slotDisplay[slot] < cur;
            future |= slotDisplay[slot] > cur;
        }
        return past && future;
    }

    /**
     * Swaps two adjacent entries of an array.
     *
     * @param a the array
     * @param j the index of the second entry
     */
    private static void swap(int[] a, int j) {
        var t = a[j];
        a[j] = a[j - 1];
        a[j - 1] = t;
    }

    /**
     * Returns the references the mode decision may predict from: one per distinct slot, the first
     * that maps it.
     *
     * @param refIdx the slot of each reference
     * @return the mask, one bit per reference
     */
    private static int searchMask(int[] refIdx) {
        var mask = 0;
        for (var ref = 0; ref < 7; ref++) {
            var dup = false;
            for (var r = 0; r < ref; r++) {
                if (refIdx[r] == refIdx[ref]) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                mask |= 1 << ref;
            }
        }
        return mask;
    }

    /**
     * Returns the reference whose adapted CDFs a frame starts from: the most recently coded
     * reference frame of the same pyramid level, else {@code LAST} ({@code choose_primary_ref_frame}).
     *
     * @param refIdx the slot of each reference
     * @param level the frame's pyramid level
     * @return the reference index
     */
    private int primaryRef(int[] refIdx, int level) {
        var best = 0;
        var bestCoded = -1L;
        for (var ref = 0; ref < 7; ref++) {
            var slot = refIdx[ref];
            if (slotLevel[slot] == level && slotCoded[slot] > bestCoded) {
                bestCoded = slotCoded[slot];
                best = ref;
            }
        }
        return best;
    }

    /**
     * Returns the reference slot a coded frame replaces: an empty slot or one repeating the frame
     * of a lower slot (as after a key frame), else the oldest past frame beyond the three nearest
     * that is not a first-level alternate reference, unless more than two of those are held and
     * the frame is one ({@code init_ref_map_pair}, {@code get_refresh_idx}).
     *
     * @param slotDisplay the display index of each slot's frame, -1 when empty
     * @param slotLevel the pyramid level of each slot's frame
     * @param arf the frame is a group's alternate reference
     * @param cur the frame's display index
     * @return the slot
     */
    private static int refreshSlot(int[] slotDisplay, int[] slotLevel, boolean arf, int cur) {
        for (var s = 0; s < FrameEncoder.SLOTS; s++) {
            if (slotDisplay[s] < 0) {
                return s;
            }
            for (var t = 0; t < s; t++) {
                if (slotDisplay[t] == slotDisplay[s]) {
                    return s;
                }
            }
        }
        var arfCount = 0;
        var oldestArfOrder = Integer.MAX_VALUE;
        var oldestArf = -1;
        var oldestOrder = Integer.MAX_VALUE;
        var oldest = -1;
        for (var s = 0; s < FrameEncoder.SLOTS; s++) {
            var order = slotDisplay[s];
            if (order > cur - 3) {
                continue;
            }
            if (slotLevel[s] == 1) {
                if (order < oldestArfOrder) {
                    oldestArfOrder = order;
                    oldestArf = s;
                }
                arfCount++;
                continue;
            }
            if (order < oldestOrder) {
                oldestOrder = order;
                oldest = s;
            }
        }
        if (arf && arfCount > 2) {
            return oldestArf;
        }
        if (oldest >= 0) {
            return oldest;
        }
        if (oldestArf >= 0) {
            return oldestArf;
        }
        throw new IllegalStateException("no reference slot to refresh at frame " + cur);
    }

    @Override
    public void close() {
        frame.close();
    }
}
