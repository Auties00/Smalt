/* Ports libvpx 1.15.1 (vp8/encoder/onyx_if.c: vp8_get_compressed_data, encode_frame_to_data_rate, Pass2Encode).
   Copyright (c) 2010 The WebM project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.video.vp8.Vp8EncoderOptions;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Runs the good-quality VP8 encoding loop: a lookahead of source pictures, golden frame groups
 * planned from first-pass statistics, hidden alternate references built by the temporal filter,
 * and a rate control that codes a frame again when its size misses its target.
 *
 * <p>Pictures are copied into the lookahead as they arrive. Once it holds more than the
 * configured depth, the oldest picture is coded, followed, when the golden frame group it starts
 * takes one, by the group's alternate reference: a hidden frame coded from the temporally
 * filtered picture that will start the next group, which the frames of the group predict from.
 * VP8 has no superframes, so a hidden frame is a packet of its own. In a two-pass encode the
 * statistics of the whole clip come from a first pass; in a one-pass encode the loop measures
 * each picture as it enters the lookahead.
 *
 * <p>The stream does not depend on the thread count. Instances are used by one thread at a time.
 *
 * @implNote This implementation follows libvpx's VP8 good-quality frame loop
 * ({@code vp8_get_compressed_data}, {@code encode_frame_to_data_rate}) without the key frame
 * recode against the ambient error of forced key frames.
 */
public final class GoodEncoder {
    /**
     * The most codings of one frame.
     */
    private static final int MAX_CODINGS = 8;

    /**
     * The golden frame boost at or below which the zero bins of inter macroblocks do not widen
     * with their modes, libvpx's two-pass {@code zbin_mode_boost_enabled} rule, which this
     * encoder applies to its one-pass planning too.
     */
    private static final int MIN_ZBIN_BOOST_GFU = 400;

    /**
     * Holds the rate control and coding settings of a {@link GoodEncoder}.
     *
     * @param rateControl {@code 0} for a variable bitrate, {@code 1} for constrained quality,
     * {@code 2} for constant quality
     * @param bitrate the target bitrate of the bitrate modes, in bits per second
     * @param frameRate the frame rate
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param cqQindex the quantizer index of the quality level
     * @param keyInterval the largest key frame interval, 0 for none
     * @param lookahead the lookahead depth in frames, from 0 to 25
     * @param altRef whether alternate references are coded
     * @param filterStrength the temporal filter strength, from 0 to 6
     * @param filterFrames the most pictures the temporal filter averages, 0 to code alternate
     * references from their unfiltered source
     * @param speed the good-quality speed, from 0 to 4
     * @param best whether the frames are coded at the best quality, whose thresholds and searches
     * ignore {@code speed}
     * @param firstPass the first-pass records of the whole clip, or {@code null} for a one-pass
     * encode
     * @param firstPassFrames the number of records of {@code firstPass}
     */
    public record Settings(int rateControl, long bitrate, double frameRate, int minQindex, int maxQindex,
                           int cqQindex, int keyInterval, int lookahead, boolean altRef, int filterStrength,
                           int filterFrames, int speed, boolean best, double[] firstPass, int firstPassFrames) {
    }

    /**
     * Holds one packet of the stream.
     *
     * @param data the coded frame
     * @param pts the timestamp: the frame's for a shown frame, the next shown frame's for a hidden
     * one
     * @param duration the duration of a shown frame, 0 for a hidden one
     * @param key whether the frame is a key frame
     * @param hidden whether the frame is an alternate reference the decoder does not show
     * @param qindex the frame's quantizer index
     */
    public record Packet(byte[] data, long pts, long duration, boolean key, boolean hidden, int qindex) {
    }

    /**
     * One picture of the lookahead.
     *
     * @param picture the picture, owned by the lookahead
     * @param pts the timestamp
     * @param duration the duration, in the time base
     * @param forceKey whether the caller asked for a key frame
     */
    private record Source(FrameBuffer picture, long pts, long duration, boolean forceKey) {
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
     * The number of macroblock columns.
     */
    private final int mbCols;

    /**
     * The number of macroblock rows.
     */
    private final int mbRows;

    /**
     * The number of threads.
     */
    private final int threads;

    /**
     * The options of the encoder.
     */
    private final Vp8EncoderOptions options;

    /**
     * The frame encoder.
     */
    private final RtEncoder enc;

    /**
     * The first pass of a one-pass encode.
     */
    private final FirstPassAnalyzer analyzer;

    /**
     * The rate control.
     */
    private final GoodRateControl rc;

    /**
     * The temporal filter of the alternate references.
     */
    private final TemporalFilter filter;

    /**
     * The lookahead, in display order from the next shown picture.
     */
    private final ArrayDeque<Source> queue = new ArrayDeque<>();

    /**
     * The pictures that left the lookahead, for reuse.
     */
    private final ArrayDeque<FrameBuffer> free = new ArrayDeque<>();

    /**
     * The record of the picture the first pass measured last.
     */
    private final double[] record = new double[FirstPassAnalyzer.FIELDS];

    /**
     * The pictures of the temporal filtering in progress.
     */
    private FrameBuffer[] filterPictures = new FrameBuffer[16];

    /**
     * The settings.
     */
    private Settings settings;

    /**
     * The display index of the next shown frame.
     */
    private int showIdx;

    /**
     * The number of pictures added to the lookahead.
     */
    private int pushed;

    /**
     * Whether the encode is the second pass of a two-pass encode.
     */
    private boolean twoPass;

    /**
     * The quantizer index of the frame coded last.
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
     * @param options the encoder options; their token partitions, threads and limits apply
     */
    public GoodEncoder(int width, int height, Vp8EncoderOptions options) {
        this.width = width;
        this.height = height;
        this.mbCols = (width + 15) >> 4;
        this.mbRows = (height + 15) >> 4;
        this.options = options;
        this.enc = new RtEncoder(width, height, options);
        enc.good = true;
        enc.modeLfDeltas = RtEncoder.GOOD_MODE_LF_DELTAS;
        enc.lfDeltaUpdate = true;
        enc.encodeBreakout = options.encodeBreakout();
        this.threads = enc.threads;
        this.analyzer = new FirstPassAnalyzer(width, height, options.limits());
        this.rc = new GoodRateControl(mbCols * mbRows, mbCols * 16 * mbRows * 16);
        this.filter = new TemporalFilter(mbCols, mbRows, options.limits());
    }

    /**
     * Configures the loop before the first picture.
     *
     * @param settings the rate control and coding settings
     */
    public void configure(Settings settings) {
        this.settings = settings;
        rc.configure(settings.rateControl(), settings.bitrate(), settings.frameRate(), settings.minQindex(),
                settings.maxQindex(), settings.cqQindex(), settings.keyInterval(), settings.lookahead(),
                settings.altRef(), settings.filterFrames(), settings.speed(), settings.best());
        if (settings.firstPass() != null) {
            twoPass = true;
            rc.setClipStats(settings.firstPass(), settings.firstPassFrames());
        }
    }

    /**
     * Returns the quantizer index of a quantizer on the 0 to 63 scale of the options
     * ({@code q_trans}).
     *
     * @param quantizer the quantizer, from 0 to 63
     * @return the quantizer index
     */
    public static int qindexOf(int quantizer) {
        return RtTables.Q_TRANS[quantizer];
    }

    /**
     * Changes the bitrate, quantizer window and quality level of a running stream.
     *
     * @param bitrate the target bitrate in bits per second
     * @param minQindex the smallest quantizer index
     * @param maxQindex the largest quantizer index
     * @param cqQindex the quantizer index of the quality level
     */
    public void retarget(long bitrate, int minQindex, int maxQindex, int cqQindex) {
        rc.retarget(bitrate, minQindex, maxQindex, cqQindex);
    }

    /**
     * Returns the quantizer index of the frame coded last.
     *
     * @return the index
     */
    public int lastQindex() {
        return lastQindex;
    }

    /**
     * Returns whether the last shown frame was a key frame.
     *
     * @return whether it was
     */
    public boolean lastKeyFrame() {
        return lastKey;
    }

    /**
     * Adds a picture to the lookahead and codes the frames it lets out.
     *
     * @param picture the picture, of the loop's size in limited-range BT.601 4:2:0; it is copied
     * @param pts the timestamp
     * @param duration the duration, in the time base
     * @param durationTicks the duration in {@link RtEncoder#TICKS_PER_SECOND} ticks
     * @param forceKey whether the picture must be a key frame
     * @param output the receiver of the packets
     * @throws IllegalStateException if a two-pass encode receives more pictures than its first
     * pass analyzed
     */
    public void encode(YuvPicture picture, long pts, long duration, long durationTicks, boolean forceKey,
                       Consumer<Packet> output) {
        if (twoPass && pushed >= settings.firstPassFrames()) {
            throw new IllegalStateException("the first pass analyzed " + settings.firstPassFrames()
                    + " pictures; the second pass received more");
        }
        if (!twoPass) {
            analyzer.analyze(picture, durationTicks, record, 0, threads);
            rc.addStats(record, 0);
        }
        var copy = free.pollFirst();
        if (copy == null) {
            copy = new FrameBuffer(mbCols, mbRows, options.limits());
        }
        load(picture, copy);
        pushed++;
        queue.add(new Source(copy, pts, duration, forceKey));
        while (queue.size() > settings.lookahead()) {
            codeNext(output);
        }
    }

    /**
     * Copies a picture into a lookahead buffer, pads it to whole macroblocks and extends its
     * borders.
     *
     * @param picture the picture to copy
     * @param frame the buffer
     */
    private void load(YuvPicture picture, FrameBuffer frame) {
        var w16 = mbCols * 16;
        var h16 = mbRows * 16;
        copyPadded(picture.y(), picture.yStride(), width, height, frame.y, frame.yOrigin, frame.yStride, w16, h16);
        var cw = (width + 1) >> 1;
        var ch = (height + 1) >> 1;
        copyPadded(picture.u(), picture.uvStride(), cw, ch, frame.u, frame.uvOrigin, frame.uvStride, w16 / 2, h16 / 2);
        copyPadded(picture.v(), picture.uvStride(), cw, ch, frame.v, frame.uvOrigin, frame.uvStride, w16 / 2, h16 / 2);
        for (var r = 0; r < mbRows; r++) {
            frame.extendRowSides(r);
        }
        frame.extendTopBottom();
    }

    /**
     * Copies a plane into a larger one, replicating its last column and row into the padding.
     *
     * @param src the source plane
     * @param srcStride the source row stride
     * @param w the source width
     * @param h the source height
     * @param dst the destination plane
     * @param dstOff the index of the destination's first sample
     * @param dstStride the destination row stride
     * @param dw the padded width
     * @param dh the padded height
     */
    private static void copyPadded(byte[] src, int srcStride, int w, int h, byte[] dst, int dstOff, int dstStride,
                                   int dw, int dh) {
        for (var r = 0; r < h; r++) {
            var d = dstOff + r * dstStride;
            System.arraycopy(src, r * srcStride, dst, d, w);
            if (dw > w) {
                Arrays.fill(dst, d + w, d + dw, dst[d + w - 1]);
            }
        }
        for (var r = h; r < dh; r++) {
            System.arraycopy(dst, dstOff + (h - 1) * dstStride, dst, dstOff + r * dstStride, dw);
        }
    }

    /**
     * Codes every picture left in the lookahead.
     *
     * @param output the receiver of the packets
     */
    public void flush(Consumer<Packet> output) {
        rc.finishStats();
        while (!queue.isEmpty()) {
            codeNext(output);
        }
    }

    /**
     * Codes the next step: a group's alternate reference, or the oldest picture of the lookahead.
     *
     * @param output the receiver of the packets
     */
    private void codeNext(Consumer<Packet> output) {
        if (rc.nextIsArf()) {
            var offset = rc.arfSourceOffset();
            if (offset < queue.size()) {
                codeAltRef(offset, output);
                return;
            }
            rc.cancelAltRef();
        }
        var source = queue.removeFirst();
        rc.beginFrame(showIdx, source.forceKey());
        var key = rc.keyFrame();
        var data = codeFrame(source.picture(), true);
        showIdx++;
        free.add(source.picture());
        lastKey = key;
        output.accept(new Packet(data, source.pts(), source.duration(), key, false, lastQindex));
    }

    /**
     * Codes a group's alternate reference from the filtered lookahead picture at a distance from
     * the next shown frame.
     *
     * @param offset the distance
     * @param output the receiver of the packets
     */
    private void codeAltRef(int offset, Consumer<Packet> output) {
        rc.beginAltRef(showIdx + offset);
        var picture = queued(offset);
        if (settings.filterFrames() > 0) {
            var plan = TemporalFilter.plan(offset, queue.size(), rc.activeArnrFrames());
            var backward = plan & 0xFF;
            var forward = (plan >> 8) & 0xFF;
            var count = backward + 1 + forward;
            if (count > 1) {
                if (filterPictures.length < count) {
                    filterPictures = new FrameBuffer[count];
                }
                for (var i = 0; i < count; i++) {
                    filterPictures[i] = queued(offset - backward + i);
                }
                picture = filter.filter(filterPictures, count, backward, settings.filterStrength(), threads);
                Arrays.fill(filterPictures, null);
            }
        }
        var data = codeFrame(picture, false);
        output.accept(new Packet(data, queue.getFirst().pts(), 0, false, true, lastQindex));
    }

    /**
     * Returns the picture of a queued source.
     *
     * @param index the source's position in the queue
     * @return the picture
     */
    private FrameBuffer queued(int index) {
        var iterator = queue.iterator();
        for (var i = 0; i < index; i++) {
            iterator.next();
        }
        return iterator.next().picture();
    }

    /**
     * Codes the current step's frame, again at another quantizer while the rate control asks,
     * and keeps the last coding.
     *
     * @param picture the picture to code
     * @param show whether the frame is shown
     * @return the coded frame
     */
    private byte[] codeFrame(FrameBuffer picture, boolean show) {
        enc.showFrame = show;
        enc.signBiasAlt = rc.altRefActive();
        enc.srcFrameAltRef = rc.srcFrameAltRef();
        enc.forceZeroAltRef = settings.filterFrames() == 0;
        enc.altRefActive = rc.altRefActive();
        enc.goodFramesSinceGolden = rc.framesSinceGolden();
        enc.nextIIRatio = rc.nextIIRatio();
        enc.sectionIntraRating = rc.sectionIntraRating();
        enc.beginGoodFrame(picture, rc.keyFrame(), rc.refreshLast(), rc.refreshGolden(), rc.refreshAlt(),
                settings.speed(), settings.best());
        enc.zbinModeBoostEnabled = rc.gfuBoost() > MIN_ZBIN_BOOST_GFU;
        enc.measureLumaError = rc.forcedKeyFrame() || rc.measuresAmbientError();
        var q = rc.pickQ();
        var size = enc.codeGoodFrame(q, rc.zbinOverQuant());
        for (var coding = 1; coding < MAX_CODINGS; coding++) {
            var next = rc.recode(q, size, enc.unfilteredLumaError);
            if (next < 0) {
                break;
            }
            q = next;
            size = enc.codeGoodFrame(q, rc.zbinOverQuant());
        }
        if (rc.measuresAmbientError()) {
            rc.setAmbientError(enc.unfilteredLumaError);
        }
        if (rc.forcedKeyFrame()) {
            forcedKeyFrames++;
        }
        maxZbinOverQuant = Math.max(maxZbinOverQuant, rc.zbinOverQuant());
        var data = enc.commitGoodFrame();
        rc.endFrame(q, data.length);
        lastQindex = q;
        return data;
    }

    /**
     * The number of key frames the interval forced, for the module's tests.
     */
    int forcedKeyFrames;

    /**
     * The largest zero-bin over-quantization of a kept coding, for the module's tests.
     */
    int maxZbinOverQuant;

    /**
     * Returns the frame encoder, for the module's tests.
     *
     * @return the engine
     */
    RtEncoder engine() {
        return enc;
    }
}
