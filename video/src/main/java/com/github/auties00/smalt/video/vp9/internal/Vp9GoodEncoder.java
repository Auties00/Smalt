/* Ports libvpx 1.15.1 (vp9/encoder/vp9_encoder.c: vp9_get_compressed_data, encode_with_recode_loop;
   vp9/vp9_cx_iface.c: write_superframe_index). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Runs the good-quality VP9 encoding loop: a lookahead of source pictures, golden frame groups
 * planned from first-pass statistics, hidden alternate references built by the temporal filter,
 * and a rate control that codes a frame again when its size misses its target.
 *
 * <p>Pictures are copied into the lookahead as they arrive. Once it holds more than the
 * configured depth, the oldest picture is coded, preceded, at the start of a golden frame group
 * that takes one, by the group's alternate reference: a hidden frame coded from the temporally
 * filtered picture that will start the next group. The next group then starts with an overlay
 * that codes that picture again against the alternate reference. A hidden frame travels in the
 * packet of the shown frame after it, joined by a superframe index, so each packet shows exactly
 * one frame. In a two-pass encode the statistics of the whole clip come from a first pass; in a
 * one-pass encode the loop measures each picture as it enters the lookahead.
 *
 * <p>The stream does not depend on the thread count. Instances are used by one thread at a time.
 *
 * @implNote This implementation follows libvpx's good-quality frame loop
 * ({@code vp9_get_compressed_data}, {@code encode_with_recode_loop}) with a single layer of
 * alternate references.
 */
public final class Vp9GoodEncoder {

    /**
     * The most codings of one frame.
     */
    private static final int MAX_CODINGS = 6;

    /**
     * Holds the rate control and coding settings of a {@link Vp9GoodEncoder}.
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
     * @param best whether the frames are coded at the best quality, whose search ignores
     * {@code speed}
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
     * @param data the packet: the shown frame, preceded by the hidden frames coded before it
     * @param pts the shown frame's timestamp
     * @param duration the shown frame's duration
     * @param key whether the shown frame is a key frame
     * @param qindex the shown frame's quantizer index
     */
    public record Packet(byte[] data, long pts, long duration, boolean key, int qindex) {
    }

    /**
     * One picture of the lookahead.
     *
     * @param picture the picture, owned by the lookahead
     * @param pts the timestamp
     * @param duration the duration, in the time base
     * @param forceKey whether the caller asked for a key frame
     */
    private record Source(Vp9EncPicture picture, long pts, long duration, boolean forceKey) {
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
     * The horizontal chroma subsampling shift.
     */
    private final int ssX;

    /**
     * The vertical chroma subsampling shift.
     */
    private final int ssY;

    /**
     * The bit depth.
     */
    private final int bitDepth;

    /**
     * The number of threads.
     */
    private final int threads;

    /**
     * The frame encoder.
     */
    private final Vp9RtEncoder enc;

    /**
     * The first pass of a one-pass encode.
     */
    private final Vp9FirstPassAnalyzer analyzer;

    /**
     * The rate control.
     */
    private final Vp9GoodRateControl rc;

    /**
     * The temporal filter of the alternate references.
     */
    private final Vp9TemporalFilter filter;

    /**
     * The lookahead, in display order from the next shown picture.
     */
    private final ArrayDeque<Source> queue = new ArrayDeque<>();

    /**
     * The pictures that left the lookahead, for reuse.
     */
    private final ArrayDeque<Vp9EncPicture> free = new ArrayDeque<>();

    /**
     * The record of the picture the first pass measured last.
     */
    private final double[] record = new double[Vp9FirstPassAnalyzer.FIELDS];

    /**
     * The hidden frames coded since the last shown frame.
     */
    private final byte[][] hidden = new byte[8][];

    /**
     * The size of each hidden frame.
     */
    private final int[] hiddenSizes = new int[8];

    /**
     * The pictures of the temporal filtering in progress.
     */
    private Vp9EncPicture[] filterPictures = new Vp9EncPicture[16];

    /**
     * The settings.
     */
    private Settings settings;

    /**
     * The number of hidden frames waiting for the next shown frame.
     */
    private int hiddenCount;

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
     * The quantizer index of the last shown frame.
     */
    private int lastQindex;

    /**
     * Whether the last shown frame was a key frame.
     */
    private boolean lastKey;

    /**
     * Creates the loop of a picture format.
     *
     * @param width the picture width
     * @param height the picture height
     * @param ssX the horizontal chroma subsampling shift, 0 or 1
     * @param ssY the vertical chroma subsampling shift, 0 or 1
     * @param bitDepth the bit depth, 8, 10 or 12
     * @param tileColumnsLog2 the requested log2 of the tile column count
     * @param threads the number of threads
     * @param limits the allocation limits
     */
    public Vp9GoodEncoder(int width, int height, int ssX, int ssY, int bitDepth, int tileColumnsLog2, int threads,
                          MediaLimits limits) {
        this.width = width;
        this.height = height;
        this.ssX = ssX;
        this.ssY = ssY;
        this.bitDepth = bitDepth;
        this.threads = Math.max(1, threads);
        this.enc = new Vp9RtEncoder(width, height, ssX, ssY, bitDepth, 1, tileColumnsLog2, threads, limits);
        enc.good = true;
        this.analyzer = new Vp9FirstPassAnalyzer(width, height);
        this.rc = new Vp9GoodRateControl(width, height);
        this.filter = new Vp9TemporalFilter(width, height, ssX, ssY, bitDepth);
    }

    /**
     * Sets the color description of the key frame headers.
     *
     * @param colorSpace the {@code color_space} code
     * @param fullRange whether the samples use the full range
     */
    public void setColor(int colorSpace, boolean fullRange) {
        enc.setColor(colorSpace, fullRange);
    }

    /**
     * Configures the loop before the first picture.
     *
     * @param settings the rate control and coding settings
     * @throws IllegalArgumentException if the first-pass records do not match the picture size
     */
    public void configure(Settings settings) {
        this.settings = settings;
        rc.configure(settings.rateControl(), settings.bitrate(), settings.frameRate(), settings.minQindex(),
                settings.maxQindex(), settings.cqQindex(), settings.keyInterval(), settings.lookahead(),
                settings.altRef(), settings.speed());
        if (settings.firstPass() != null) {
            twoPass = true;
            rc.setClipStats(settings.firstPass(), settings.firstPassFrames());
        }
        enc.optimizeCoefficients = twoPass;
        enc.unfilteredAltRef = settings.filterFrames() == 0;
    }

    /**
     * Returns the quantizer index of a quantizer on the 0 to 63 scale of the options
     * ({@code vp9_quantizer_to_qindex}).
     *
     * @param quantizer the quantizer, from 0 to 63
     * @return the quantizer index
     */
    public static int qindexOf(int quantizer) {
        return Vp9EncTables.quantizerToQindex(quantizer);
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
     * @return whether it was
     */
    public boolean lastKeyFrame() {
        return lastKey;
    }

    /**
     * Adds an 8-bit picture to the lookahead and codes the frames it lets out.
     *
     * @param picture the picture, of the loop's size and chroma format, or 4:4:4 for 4:4:0; it
     * is copied
     * @param pts the timestamp
     * @param duration the duration, in the time base
     * @param durationTicks the duration in {@link Vp9RtEncoder#TICKS_PER_SECOND} ticks
     * @param forceKey whether the picture must be a key frame
     * @param output the receiver of the packets
     * @throws IllegalStateException if a two-pass encode receives more pictures than its first
     * pass analyzed
     */
    public void encode(YuvPicture picture, long pts, long duration, long durationTicks, boolean forceKey,
                       Consumer<Packet> output) {
        var copy = take();
        copy.copyFrom(picture, threads);
        push(copy, pts, duration, durationTicks, forceKey, output);
    }

    /**
     * Adds a high bit depth picture to the lookahead and codes the frames it lets out.
     *
     * @param picture the picture, of the loop's size, bit depth and chroma format, or 4:4:4 for
     * 4:4:0; it is copied
     * @param pts the timestamp
     * @param duration the duration, in the time base
     * @param durationTicks the duration in ticks
     * @param forceKey whether the picture must be a key frame
     * @param output the receiver of the packets
     * @throws IllegalStateException if a two-pass encode receives more pictures than its first
     * pass analyzed
     */
    public void encode(Yuv16Picture picture, long pts, long duration, long durationTicks, boolean forceKey,
                       Consumer<Packet> output) {
        var copy = take();
        copy.copyFrom(picture, threads);
        push(copy, pts, duration, durationTicks, forceKey, output);
    }

    /**
     * Returns a free lookahead picture.
     *
     * @return the picture
     */
    private Vp9EncPicture take() {
        var picture = free.pollFirst();
        return picture != null ? picture : new Vp9EncPicture(width, height, ssX, ssY, bitDepth);
    }

    /**
     * Adds a copied picture to the lookahead.
     */
    private void push(Vp9EncPicture picture, long pts, long duration, long durationTicks, boolean forceKey,
                      Consumer<Packet> output) {
        if (twoPass && pushed >= settings.firstPassFrames()) {
            free.add(picture);
            throw new IllegalStateException("the first pass analyzed " + settings.firstPassFrames()
                    + " pictures; the second pass received more");
        }
        if (!twoPass) {
            analyzer.analyze(picture, durationTicks, forceKey, record, 0, threads);
            rc.addStats(record, 0);
        }
        pushed++;
        queue.add(new Source(picture, pts, duration, forceKey));
        while (queue.size() > settings.lookahead()) {
            codeNext(output);
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
        if (rc.nextIsArf() && rc.arfSourceOffset() < queue.size()) {
            codeAltRef(rc.arfSourceOffset());
            return;
        }
        var source = queue.removeFirst();
        rc.beginFrame(showIdx, source.forceKey());
        var key = rc.keyFrame();
        var data = codeFrame(source.picture(), key, true);
        showIdx++;
        free.add(source.picture());
        byte[] packet;
        if (hiddenCount > 0) {
            hidden[hiddenCount] = data;
            hiddenSizes[hiddenCount] = data.length;
            packet = Vp9Superframe.pack(hidden, hiddenSizes, hiddenCount + 1);
            Arrays.fill(hidden, null);
            hiddenCount = 0;
        } else {
            packet = data;
        }
        lastQindex = codedQindex;
        lastKey = key;
        output.accept(new Packet(packet, source.pts(), source.duration(), key, codedQindex));
    }

    /**
     * The quantizer index of the frame coded last.
     */
    private int codedQindex;

    /**
     * Codes a group's alternate reference from the filtered lookahead picture at a distance from
     * the next shown frame.
     *
     * @param offset the distance
     */
    private void codeAltRef(int offset) {
        rc.beginFrame(showIdx, false);
        var center = queued(offset);
        var picture = center;
        if (settings.filterFrames() > 0) {
            var avgQ = Vp9EncTables.qindexToQ(rc.currentFrame() > 1 ? rc.avgFrameQindex(false) : rc.avgFrameQindex(true));
            var plan = Vp9TemporalFilter.plan(offset, queue.size(), rc.gfuBoost(), settings.filterFrames(),
                    settings.filterStrength() + rc.arnrStrengthAdjustment(), avgQ);
            var backward = plan & 0xFF;
            var forward = (plan >> 8) & 0xFF;
            var strength = (plan >> 16) & 0xFF;
            var count = backward + 1 + forward;
            if (count > 1) {
                if (filterPictures.length < count) {
                    filterPictures = new Vp9EncPicture[count];
                }
                for (var i = 0; i < count; i++) {
                    filterPictures[i] = queued(offset - backward + i);
                }
                picture = filter.filter(filterPictures, count, backward, strength, settings.best(), threads);
                Arrays.fill(filterPictures, null);
            }
        }
        var data = codeFrame(picture, false, false);
        hidden[hiddenCount] = data;
        hiddenSizes[hiddenCount] = data.length;
        hiddenCount++;
    }

    /**
     * Returns the picture of a queued source.
     *
     * @param index the source's position in the queue
     * @return the picture
     */
    private Vp9EncPicture queued(int index) {
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
     * @param key whether the frame is a key frame
     * @param show whether the frame is shown
     * @return the coded frame
     */
    private byte[] codeFrame(Vp9EncPicture picture, boolean key, boolean show) {
        var updateType = rc.updateType();
        enc.beginGoodFrame(picture, key, show, rc.refreshLast(), rc.refreshGolden(), rc.refreshAlt(),
                rc.srcFrameAltRef(), settings.speed(), settings.best(), Vp9GoodRateControl.RD_FRAME_TYPE_FACTOR[updateType],
                rc.gfuBoost(), rc.altRefActive() && !rc.refreshAlt());
        rc.setRecode(enc.goodSpeed);
        enc.measureLumaError = rc.forcedKeyFrame() || rc.measuresAmbientError();
        var q = rc.pickQ();
        var size = enc.codeGoodFrame(q);
        codedFrames++;
        codings++;
        for (var coding = 1; coding < MAX_CODINGS; coding++) {
            var next = rc.recode(q, size, enc.unfilteredLumaError);
            if (next < 0) {
                break;
            }
            q = next;
            size = enc.codeGoodFrame(q);
            codings++;
        }
        if (rc.measuresAmbientError()) {
            rc.setAmbientError(enc.unfilteredLumaError);
        }
        if (rc.forcedKeyFrame()) {
            forcedKeyFrames++;
        }
        var data = Arrays.copyOf(enc.buffer(), size);
        enc.commitGoodFrame();
        rc.endFrame(q, size, show);
        codedQindex = q;
        return data;
    }

    /**
     * The number of key frames the interval forced, for the module's tests.
     */
    int forcedKeyFrames;

    /**
     * The number of frames coded, hidden ones included, for the module's tests.
     */
    int codedFrames;

    /**
     * The number of codings of those frames, recodings included, for the module's tests.
     */
    int codings;

    /**
     * Returns the frame encoder, for the module's tests.
     *
     * @return the engine
     */
    Vp9RtEncoder engine() {
        return enc;
    }
}
