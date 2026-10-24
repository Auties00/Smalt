package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.H264PacketFormat;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.internal.H264DecoderEngine;
import com.github.auties00.smalt.video.h264.internal.H264Nal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the frame-level loop of the extended engine, which codes samples of 8 to 14 bits in every
 * chroma format, lossless streams and interlaced frames: input conversion, frame type and
 * quantizer decisions, the slices of each picture, reference management and packet assembly.
 *
 * <p>Pictures are I and P pictures, coded in display order. Interlaced frames are coded as frames
 * of macroblock pairs or as two field pictures; both fields of a frame travel in one packet. A
 * coded picture becomes a reference once an internal {@link H264DecoderEngine} has decoded it, so
 * the encoder predicts from exactly the samples a decoder reconstructs, deblocking filter
 * included, for every format and field structure. Instances are not thread-safe, except
 * {@link #requestKeyframe()}, {@link #setTargetBitrate(int)} and the counters.
 *
 * @implNote This implementation codes every macroblock on the calling thread. Its tools are the
 * Intra_4x4, Intra_8x8 and Intra_16x16 modes and one motion vector per macroblock with
 * quarter-sample precision, chosen by Hadamard-transformed prediction error; B pictures, smaller
 * partitions, adaptive quantization and look-ahead are the domain of {@link EncoderCore}.
 */
public final class WideCore implements EncoderEngine {
    /**
     * The ratio of inter to intra cost above which a frame starts a new scene.
     */
    private static final double SCENE_CUT_THRESHOLD = 0.72;

    /**
     * The factor by which a frame's inter cost must exceed the recent average for a scene cut.
     */
    private static final double SCENE_CUT_JUMP = 1.5;

    // TODO: B pictures, partitions below 16x16, look-ahead with adaptive quantization,
    //  wavefront analysis on helper threads and separate color plane coding (separate_colour_plane_flag) in the
    //  extended engine
    private final EncoderConfig config;

    private final WideSettings settings;

    private final WideInput input;

    private final WideCoder coder;

    private final WideSliceWriter writer;

    private final NalWriter nal;

    private final RateController rateControl;

    /**
     * The decoder that reconstructs every coded picture as a decoder does.
     */
    private final H264DecoderEngine decoder;

    private final byte[] sps;

    private final byte[] pps;

    private final byte[] configurationRecord;

    /**
     * The SEI payload of the HDR metadata of every IDR access unit, or {@code null}.
     */
    private final byte[] staticSei;

    /**
     * The SEI payload of the recovery point of every intra refresh cycle, or {@code null}.
     */
    private final byte[] recoverySei;

    /**
     * The source frame of the frame being coded.
     */
    private final WideFrame source;

    /**
     * The reconstruction of the frame being coded, before the deblocking filter.
     */
    private final WideFrame recon;

    /**
     * The frames not in use.
     */
    private final List<WideFrame> pool = new ArrayList<>();

    /**
     * The reference frames, most recent first.
     */
    private final List<WideFrame> references = new ArrayList<>();

    /**
     * The frame being built from the decoder's output.
     */
    private WideFrame building;

    /**
     * The decoded samples of the last coded frame, for the reconstruction hook.
     */
    private final WideFrame lastDecoded;

    /**
     * Scratch space of the half-sample interpolation.
     */
    private final int[] interpolationScratch;

    /**
     * The macroblock columns each picture of an intra refresh cycle refreshes, or 0.
     */
    private final int refreshBand;

    /**
     * The first column of the refresh band of the next P picture.
     */
    private int refreshColumn;

    private final int temporalLayers;

    /**
     * The latest reference frame of each temporal layer since the last IDR frame.
     */
    private final WideFrame[] layerLatest = new WideFrame[4];

    /**
     * The temporal layer of the frame being coded.
     */
    private int temporalLayer;

    private volatile int lastTemporalLayer;

    private final AtomicLong keyframeRequests = new AtomicLong();

    private long keyframeRequestsSeen;

    private final AtomicInteger pendingBitrate = new AtomicInteger();

    private volatile int appliedBitrate;

    private volatile long frameCount;

    private volatile long keyframeCount;

    private volatile long byteCount;

    private volatile int lastQp;

    private long lastPts = Packet.NO_TIMESTAMP;

    private final long nominalDuration;

    /**
     * The number of frames coded since the last IDR frame, that one included.
     */
    private long framesSinceIdr;

    private long submitted;

    /**
     * The {@code frame_num} of the next reference frame.
     */
    private int frameNum;

    private int idrPicId;

    private boolean parametersFed;

    private boolean closed;

    /**
     * Whether the previous frame's low-resolution luma is in {@link #previousLowres}.
     */
    private boolean previousLowresValid;

    /**
     * The intra sum of the last measured frame, see {@link #measure()}.
     */
    private long intraSum;

    /**
     * The inter sum of the last measured frame, see {@link #measure()}.
     */
    private long interSum;

    /**
     * The sum of the per-block minima of the last measured frame, see {@link #measure()}.
     */
    private long bestSum;

    /**
     * The exponentially weighted average inter sum since the last IDR frame, or {@code -1} when
     * there is none.
     */
    private double recentInterSum = -1;

    private final short[] previousLowres;

    private final short[] lowres;

    /**
     * No references.
     */
    private static final WideReference[] NONE = new WideReference[0];

    /**
     * The reference of frame macroblocks, or of the first index of a field picture.
     */
    private final WideReference[] oneRef = new WideReference[1];

    /**
     * The two references of a field picture.
     */
    private final WideReference[] twoRefs = new WideReference[2];

    /**
     * The references of top field macroblocks of a frame of pairs.
     */
    private final WideReference[] topRefs = new WideReference[2];

    /**
     * The references of bottom field macroblocks of a frame of pairs.
     */
    private final WideReference[] bottomRefs = new WideReference[2];

    /**
     * The fields a P field picture predicts from, in index order.
     */
    private final List<WideReference> wanted = new ArrayList<>();

    /**
     * The fields of the initial list of a P field picture with the current field's parity.
     */
    private final List<WideReference> sameParity = new ArrayList<>();

    /**
     * The fields of the initial list of a P field picture with the other parity.
     */
    private final List<WideReference> otherParity = new ArrayList<>();

    /**
     * The initial list of a P field picture.
     */
    private final List<WideReference> initialList = new ArrayList<>();

    /**
     * The picture header of the picture being coded.
     */
    private final WideSliceWriter.Picture header = new WideSliceWriter.Picture();

    /**
     * The CABAC bins of the slice closed last.
     */
    private long sliceBins;

    /**
     * The slice data bytes of the slice closed last.
     */
    private long sliceBytes;

    /**
     * Constructs an engine.
     *
     * @param config the configuration
     * @throws IllegalArgumentException if an explicit level is too small for the stream
     */
    public WideCore(EncoderConfig config) {
        this.config = config;
        this.settings = new WideSettings(config);
        this.input = new WideInput(settings);
        this.coder = new WideCoder(settings);
        this.writer = new WideSliceWriter(settings, coder);
        this.nal = new NalWriter(settings.mbCount() * 128, config.packetFormat() == H264PacketFormat.ANNEX_B);
        this.rateControl = new RateController(config);
        this.decoder = new H264DecoderEngine(config.limits(), frame -> {
        }, 1);
        decoder.setLowDelay(true);
        this.sps = NalWriter.unit(3, H264Nal.SPS, ParameterSets.sps(settings));
        this.pps = NalWriter.unit(3, H264Nal.PPS, ParameterSets.pps(settings));
        this.configurationRecord = ParameterSets.avcDecoderConfigurationRecord(settings, sps, pps);
        this.staticSei = ParameterSets.staticSei(config.color());
        this.temporalLayers = config.temporalLayers();
        if (config.intraRefresh() > 0) {
            this.refreshBand = Math.ceilDiv(settings.mbWidth(), config.intraRefresh());
            this.recoverySei = ParameterSets.recoveryPointSei(Math.ceilDiv(settings.mbWidth(), refreshBand) - 1);
        } else {
            this.refreshBand = 0;
            this.recoverySei = null;
        }
        this.source = new WideFrame(settings);
        this.recon = new WideFrame(settings);
        this.lastDecoded = new WideFrame(settings);
        this.interpolationScratch = new int[source.planes[0].stride + 16];
        this.appliedBitrate = config.rateControl() == H264RateControl.CONSTANT_QUALITY ? 0 : config.bitrate();
        var rate = config.frameRate();
        this.nominalDuration = Math.max(1, new Rational(rate.denominator(), rate.numerator()).rescale(1,
                config.timeBase()));
        var lowresSize = (settings.codedWidth() / 2) * (settings.codedHeight() / 2);
        this.lowres = new short[lowresSize];
        this.previousLowres = new short[lowresSize];
    }

    @Override
    public byte[] configurationRecord() {
        return configurationRecord.clone();
    }

    /**
     * {@return the escaped sequence parameter set NAL unit, a copy; a test hook}
     */
    public byte[] sequenceParameterSet() {
        return sps.clone();
    }

    /**
     * {@return the escaped picture parameter set NAL unit, a copy; a test hook}
     */
    public byte[] pictureParameterSet() {
        return pps.clone();
    }

    /**
     * Sets whether the stream enables the deblocking filter; a test hook that makes the decoded
     * pictures equal the encoder's own reconstruction, which tests compare with decoders.
     *
     * @param enabled whether the filter runs, {@code true} by default
     */
    public void setDeblocking(boolean enabled) {
        writer.setDeblocking(enabled);
    }

    /**
     * Returns the decoded samples of the last coded frame, cropped to the display size; a test hook.
     *
     * @return the luma, Cb and Cr samples in raster order, the chroma arrays empty for monochrome
     * streams, or three empty arrays before the first frame
     */
    public short[][] lastReconstruction() {
        return frameCount == 0 ? new short[][]{new short[0], new short[0], new short[0]} : crop(lastDecoded);
    }

    /**
     * Returns the encoder's own reconstruction of the last coded frame before the deblocking
     * filter, cropped to the display size; a test hook.
     *
     * @return the luma, Cb and Cr samples in raster order, laid out as {@link #lastReconstruction()}
     */
    public short[][] lastEncoderReconstruction() {
        return crop(recon);
    }

    /**
     * Returns the display area of a frame's planes.
     *
     * @param frame the frame to crop
     * @return the luma, Cb and Cr samples in raster order
     */
    private short[][] crop(WideFrame frame) {
        var result = new short[3][];
        for (var c = 0; c < 3; c++) {
            var plane = frame.planes[c];
            if (plane == null) {
                result[c] = new short[0];
                continue;
            }
            var w = c == 0 ? settings.displayWidth() : settings.displayWidth() / settings.subWidth();
            var h = c == 0 ? settings.displayHeight() : settings.displayHeight() / settings.subHeight();
            var out = new short[w * h];
            for (var y = 0; y < h; y++) {
                System.arraycopy(plane.data, plane.origin + y * plane.stride, out, y * w, w);
            }
            result[c] = out;
        }
        return result;
    }

    @Override
    public void requestKeyframe() {
        keyframeRequests.incrementAndGet();
    }

    @Override
    public void setTargetBitrate(int bitsPerSecond) {
        pendingBitrate.set(bitsPerSecond);
    }

    @Override
    public int targetBitrate() {
        return appliedBitrate;
    }

    @Override
    public long frameCount() {
        return frameCount;
    }

    @Override
    public long keyframeCount() {
        return keyframeCount;
    }

    @Override
    public long keyframeRequestCount() {
        return keyframeRequests.get();
    }

    @Override
    public long byteCount() {
        return byteCount;
    }

    @Override
    public int lastQp() {
        return lastQp;
    }

    @Override
    public int lastTemporalLayer() {
        return lastTemporalLayer;
    }

    @Override
    public Packet flush() {
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        return null;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            decoder.close();
        }
    }

    @Override
    public Packet encode(Picture picture, long pts, long duration) {
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        if (picture.width() != config.width() || picture.height() != config.height()) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + config.width() + "x" + config.height());
        }
        var previous = lastPts;
        var nominal = false;
        if (pts == Packet.NO_TIMESTAMP) {
            pts = previous == Packet.NO_TIMESTAMP ? 0 : previous + nominalDuration;
        } else if (previous != Packet.NO_TIMESTAMP && pts <= previous) {
            if (!config.realtime()) {
                throw new IllegalArgumentException("timestamp " + pts + " does not follow " + previous);
            }
            pts = previous + 1;
            nominal = true;
        }
        lastPts = pts;
        applyPendingBitrate();
        var seconds = nominal && duration <= 0 ? 1 / config.frameRate().toDouble()
                : frameSeconds(pts, duration, previous);
        input.convert(picture, source);
        var requests = keyframeRequests.get();
        var requested = requests != keyframeRequestsSeen;
        keyframeRequestsSeen = requests;
        var idr = submitted == 0 || requested
                || (config.keyframeInterval() > 0 && refreshBand == 0 && framesSinceIdr >= config.keyframeInterval());
        var inter = measure();
        if (!idr && refreshBand == 0 && inter) {
            if (framesSinceIdr >= minimumSceneDistance() && interSum > SCENE_CUT_THRESHOLD * intraSum
                    && (recentInterSum < 0 || interSum > SCENE_CUT_JUMP * recentInterSum)) {
                idr = true;
                recentInterSum = -1;
            } else {
                recentInterSum = recentInterSum < 0 ? interSum : 0.7 * recentInterSum + 0.3 * interSum;
            }
        }
        submitted++;
        var complexity = Math.max(1, 2 * (idr || !inter ? intraSum : bestSum));
        temporalLayer = idr ? 0 : layerOf(framesSinceIdr);
        var unreferenced = temporalLayers > 1 && temporalLayer == temporalLayers - 1;
        int qp;
        if (settings.lossless()) {
            qp = -settings.qpBdOffset();
        } else {
            qp = rateControl.frameQp(idr, complexity, seconds);
            if (temporalLayer > 0) {
                qp = Math.clamp(qp + 2 * temporalLayer, config.minQuantizer(), config.maxQuantizer());
            }
        }
        var refreshStart = 0;
        var refreshEnd = 0;
        if (refreshBand > 0 && !idr) {
            refreshStart = refreshColumn;
            refreshEnd = Math.min(settings.mbWidth(), refreshColumn + refreshBand);
        }
        long bits;
        if (settings.fieldPictures()) {
            bits = codeFieldPair(idr, unreferenced, qp, refreshStart, refreshEnd, pts, duration);
        } else {
            bits = codeFrame(idr, unreferenced, qp, refreshStart, refreshEnd);
            if (!settings.lossless()) {
                var attempts = config.realtime() ? 1 : 2;
                for (var attempt = 0; attempt < attempts; attempt++) {
                    var corrected = rateControl.correctSize(idr, qp, bits, complexity);
                    if (corrected == qp) {
                        break;
                    }
                    qp = corrected;
                    bits = codeFrame(idr, unreferenced, qp, refreshStart, refreshEnd);
                }
                for (var attempt = 0; attempt < 4 && rateControl.overflows(bits) && qp < config.maxQuantizer();
                     attempt++) {
                    qp = rateControl.retryQp(qp, bits);
                    bits = codeFrame(idr, unreferenced, qp, refreshStart, refreshEnd);
                }
            }
            decodePicture(0, pts, duration, 0, true, true, unreferenced, idr);
        }
        if (!settings.lossless()) {
            rateControl.update(idr, qp, bits, complexity, seconds);
        }
        lastQp = Math.max(qp, 0);
        if (refreshBand > 0) {
            refreshColumn = idr || refreshEnd >= settings.mbWidth() ? 0 : refreshEnd;
        }
        framesSinceIdr = idr ? 1 : framesSinceIdr + 1;
        lastTemporalLayer = temporalLayer;
        var data = nal.take();
        frameCount++;
        byteCount += data.length;
        if (idr) {
            keyframeCount++;
        }
        var flags = idr ? EnumSet.of(PacketFlag.KEY)
                : unreferenced ? EnumSet.of(PacketFlag.DISCARDABLE) : EnumSet.noneOf(PacketFlag.class);
        return new Packet(0, data, pts, pts, duration > 0 ? duration : nominalDuration, flags, PacketSideData.NONE);
    }

    /**
     * Returns the temporal layer of a frame from its position after the last IDR frame.
     *
     * @param index the number of frames from the last IDR frame to this one
     * @return the layer
     */
    private int layerOf(long index) {
        if (temporalLayers == 1) {
            return 0;
        }
        var position = (int) (index & ((1L << (temporalLayers - 1)) - 1));
        return position == 0 ? 0 : temporalLayers - 1 - Integer.numberOfTrailingZeros(position);
    }

    /**
     * Hands the last bitrate set since the previous frame to rate control, capped at the level's
     * maximum.
     */
    private void applyPendingBitrate() {
        var requested = pendingBitrate.getAndSet(0);
        if (requested <= 0) {
            return;
        }
        var bitrate = (int) Math.min(requested,
                (long) settings.level().maxBr() * Levels.bitrateFactor(config.profile()));
        rateControl.setBitrate(bitrate);
        if (config.rateControl() != H264RateControl.CONSTANT_QUALITY) {
            appliedBitrate = bitrate;
        }
    }

    /**
     * Returns a frame's duration in seconds for rate control.
     *
     * @param pts the frame's timestamp
     * @param duration the declared duration, or {@code 0}
     * @param previous the previous timestamp, or {@link Packet#NO_TIMESTAMP}
     * @return the duration
     */
    private double frameSeconds(long pts, long duration, long previous) {
        var timeBase = config.timeBase().toDouble();
        var nominal = 1 / config.frameRate().toDouble();
        double seconds;
        if (duration > 0) {
            seconds = duration * timeBase;
        } else if (previous != Packet.NO_TIMESTAMP) {
            seconds = (pts - previous) * timeBase;
        } else {
            seconds = nominal;
        }
        return Math.clamp(seconds, nominal / 8, nominal * 8);
    }

    /**
     * Measures the source frame's luma at half resolution in 8-bit units over 8x8 blocks, for rate
     * control and scene cut detection.
     *
     * <p>It sets {@link #intraSum} to the sum of the absolute deviations from each block's mean,
     * {@link #interSum} to the sum of the absolute differences from the previous frame's co-located
     * block, and {@link #bestSum} to the sum over blocks of the smaller of the two.
     *
     * @return whether a previous frame was measured, so that the inter sums are meaningful
     */
    private boolean measure() {
        var plane = source.planes[0];
        var w = settings.codedWidth() / 2;
        var h = settings.codedHeight() / 2;
        var shift = settings.bitDepth() - 8 + 2;
        for (var y = 0; y < h; y++) {
            var row0 = plane.origin + 2 * y * plane.stride;
            var row1 = row0 + plane.stride;
            for (var x = 0; x < w; x++) {
                var sum = plane.data[row0 + 2 * x] + plane.data[row0 + 2 * x + 1] + plane.data[row1 + 2 * x]
                        + plane.data[row1 + 2 * x + 1];
                lowres[y * w + x] = (short) (sum >> shift);
            }
        }
        var intraTotal = 0L;
        var interTotal = 0L;
        var bestTotal = 0L;
        for (var by = 0; by + 8 <= h; by += 8) {
            for (var bx = 0; bx + 8 <= w; bx += 8) {
                var mean = 0;
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 8; x++) {
                        mean += lowres[(by + y) * w + bx + x];
                    }
                }
                mean = (mean + 32) >> 6;
                var intraCost = 0L;
                var interCost = 0L;
                for (var y = 0; y < 8; y++) {
                    for (var x = 0; x < 8; x++) {
                        var i = (by + y) * w + bx + x;
                        intraCost += Math.abs(lowres[i] - mean);
                        interCost += Math.abs(lowres[i] - previousLowres[i]);
                    }
                }
                intraTotal += intraCost;
                interTotal += interCost;
                bestTotal += Math.min(intraCost, interCost);
            }
        }
        var measured = previousLowresValid;
        intraSum = intraTotal;
        interSum = interTotal;
        bestSum = bestTotal;
        System.arraycopy(lowres, 0, previousLowres, 0, lowres.length);
        previousLowresValid = true;
        return measured;
    }

    /**
     * Returns the smallest distance in frames from the previous IDR frame at which a scene cut
     * starts a new one: a quarter second, at least two frames, and two frames outside real time.
     *
     * @return the distance in frames
     */
    private long minimumSceneDistance() {
        var distance = Math.max(2, Math.round(config.frameRate().toDouble() / 4));
        return config.realtime() ? distance : Math.min(distance, 2);
    }

    /**
     * Codes a progressive frame or a frame of macroblock pairs into the access unit assembler,
     * replacing what it held.
     *
     * @param idr whether the frame is an IDR frame
     * @param unreferenced whether the frame is no reference picture
     * @param qp the quantizer
     * @param refreshStart the first column of the intra refresh band
     * @param refreshEnd the column after the band
     * @return the access unit size in bits
     */
    private long codeFrame(boolean idr, boolean unreferenced, int qp, int refreshStart, int refreshEnd) {
        nal.clear();
        appendHeaderUnits(idr, refreshStart, refreshEnd);
        var mbaff = settings.mbaff();
        var n = idr ? 0 : (int) framesSinceIdr;
        var topPoc = settings.interlaced() && settings.bottomFirst() ? 2 * n + 1 : 2 * n;
        var bottomPoc = !settings.interlaced() ? topPoc : settings.bottomFirst() ? 2 * n : 2 * n + 1;
        var predicted = !idr && !references.isEmpty();
        var currentFrameNum = idr ? 0 : frameNum;
        var h = header;
        h.reorderCount = 0;
        if (predicted) {
            var target = layerTarget();
            oneRef[0] = target.frameRef;
            if (mbaff) {
                topRefs[0] = target.topRef;
                topRefs[1] = target.bottomRef;
                bottomRefs[0] = target.bottomRef;
                bottomRefs[1] = target.topRef;
            }
            if (target != references.getFirst()) {
                h.reorder[0] = 0;
                h.reorder[1] = currentFrameNum - picNum(target.frameNum, currentFrameNum) - 1;
                h.reorderCount = 2;
            }
        }
        coder.startPicture(source, recon, 0, mbaff, predicted, predicted ? oneRef : NONE, mbaff ? topRefs : null,
                mbaff ? bottomRefs : null, refreshStart, refreshEnd);
        h.idr = idr;
        h.predicted = predicted;
        h.frameNum = currentFrameNum;
        h.idrPicId = idrPicId;
        h.structure = 0;
        h.pocLsb = topPoc & ((1 << settings.log2MaxPocLsb()) - 1);
        h.deltaPocBottom = bottomPoc - topPoc;
        h.nalRefIdc = idr ? 3 : unreferenced ? 0 : 2;
        h.refCount = 1;
        h.sliceQp = qp;
        codeSlices(h, mbaff);
        return nal.size() * 8L;
    }

    /**
     * Returns the {@code PicNum} of a frame as seen from a picture.
     *
     * @param refFrameNum the frame's {@code frame_num}
     * @param current the current {@code frame_num}
     * @return the picture number
     */
    private int picNum(int refFrameNum, int current) {
        return refFrameNum > current ? refFrameNum - (1 << settings.log2MaxFrameNum()) : refFrameNum;
    }

    /**
     * Returns the reference frame a P picture of the current temporal layer predicts from: the
     * latest reference without temporal layers, otherwise the latest frame of a lower layer, or of
     * layer 0 for layer 0.
     *
     * @return the reference
     */
    private WideFrame layerTarget() {
        if (temporalLayers == 1) {
            return references.getFirst();
        }
        WideFrame target = null;
        if (temporalLayer == 0) {
            target = layerLatest[0];
        } else {
            for (var layer = 0; layer < temporalLayer; layer++) {
                var candidate = layerLatest[layer];
                if (candidate != null && references.contains(candidate)
                        && (target == null || references.indexOf(candidate) < references.indexOf(target))) {
                    target = candidate;
                }
            }
        }
        return target != null && references.contains(target) ? target : references.getFirst();
    }

    /**
     * Appends the parameter sets of an Annex B IDR access unit and the SEI messages of the access
     * unit.
     *
     * @param idr whether the access unit holds an IDR picture
     * @param refreshStart the first column of the intra refresh band
     * @param refreshEnd the column after the band
     */
    private void appendHeaderUnits(boolean idr, int refreshStart, int refreshEnd) {
        if (idr && config.packetFormat() == H264PacketFormat.ANNEX_B) {
            nal.appendEscapedUnit(sps);
            nal.appendEscapedUnit(pps);
        }
        if (idr && staticSei != null) {
            nal.appendUnit(0, H264Nal.SEI, staticSei, staticSei.length);
        }
        if (!idr && refreshBand > 0 && refreshStart == 0 && refreshEnd > 0) {
            nal.appendUnit(0, H264Nal.SEI, recoverySei, recoverySei.length);
        }
    }

    /**
     * Codes the macroblocks of the current picture as the configured slices, dividing
     * them further at the slice size limit: a macroblock (or pair) that makes the slice's NAL unit
     * too large is undone and coded again as the first of a new slice.
     *
     * @param header the picture header
     * @param pairs whether the picture is a frame of macroblock pairs
     */
    private void codeSlices(WideSliceWriter.Picture header, boolean pairs) {
        var width = settings.mbWidth();
        var rows = header.structure == 0 && !pairs ? settings.mbHeight() : settings.mbHeight() / 2;
        var unit = pairs ? 2 : 1;
        var units = width * rows;
        var slices = settings.sliceRows();
        var limit = config.maxSliceBytes();
        var bins = 0L;
        var bytes = 0L;
        var sliceStart = 0;
        var nextFixed = 1;
        writer.start(header, 0);
        for (var u = 0; u < units; u++) {
            var address = u * unit;
            if (u > 0 && u % width == 0 && nextFixed < slices
                    && u / width == (int) ((long) nextFixed * rows / slices)) {
                nextFixed++;
                writer.endMacroblock(true, address - 1);
                closeSlice(header, false, 0, 0);
                bins += sliceBins;
                bytes += sliceBytes;
                sliceStart = address;
                writer.start(header, address);
            }
            if (address != sliceStart) {
                if (limit > 0) {
                    writer.save();
                }
                writer.endMacroblock(false, address - 1);
            }
            codeUnit(address, pairs, header.sliceQp, sliceStart);
            if (limit > 0 && address != sliceStart && writer.sizeBound() > limit) {
                writer.restore();
                writer.endMacroblock(true, address - 1);
                closeSlice(header, false, 0, 0);
                bins += sliceBins;
                bytes += sliceBytes;
                sliceStart = address;
                writer.start(header, address);
                codeUnit(address, pairs, header.sliceQp, sliceStart);
            }
        }
        writer.endMacroblock(true, units * unit - 1);
        closeSlice(header, true, bins, bytes);
    }

    /**
     * Codes one macroblock, or both macroblocks of a pair, and writes them.
     *
     * @param address the address of the macroblock, the top one of a pair
     * @param pairs whether the picture is a frame of macroblock pairs
     * @param qp the quantizer
     * @param sliceStart the address of the slice's first macroblock
     */
    private void codeUnit(int address, boolean pairs, int qp, int sliceStart) {
        if (!pairs) {
            var mb = coder.code(address, false, qp, sliceStart, true);
            writer.writeData(mb, false, false);
            return;
        }
        var field = coder.decideField(address >> 1);
        var inferred = coder.inferredField(address, sliceStart);
        var top = coder.code(address, field, qp, sliceStart, field == inferred);
        writer.writeData(top, field, inferred);
        writer.endMacroblock(false, address);
        var bottom = coder.code(address + 1, field, qp, sliceStart, true);
        writer.writeData(bottom, field, inferred);
    }

    /**
     * Finishes the slice being written and appends it, with its prefix NAL unit for temporal layers.
     *
     * @param header the picture header
     * @param last whether the slice is the picture's last, which carries the picture's
     * {@code cabac_zero_word}s
     * @param bins the CABAC bins of the picture's earlier slices
     * @param bytes the slice data bytes of the picture's earlier slices
     */
    private void closeSlice(WideSliceWriter.Picture header, boolean last, long bins, long bytes) {
        var payload = writer.finish();
        var length = writer.payloadLength();
        sliceBins = writer.binCount();
        sliceBytes = length;
        if (last && settings.cabac()) {
            // TODO: with maxSliceBytes, the cabac_zero_words a picture needs can push its last slice past the
            //  limit; spread them over the picture's slices within the limit
            var raw = (long) (256 * settings.bitDepth() + 2 * settings.mbWidthC() * settings.mbHeightC()
                    * settings.bitDepth()) * settings.mbCount() / (header.structure == 0 ? 1 : 2);
            var totalBins = bins + sliceBins;
            var totalBytes = bytes + length;
            if (totalBins > 32 * totalBytes / 3 + raw / 32) {
                var needed = ((totalBins - raw / 32) * 3 + 31) / 32 - totalBytes;
                var words = (int) Math.max(0, (needed + 2) / 3);
                payload = Arrays.copyOf(payload, length + 2 * words);
                length += 2 * words;
            }
        }
        var nalRefIdc = header.nalRefIdc;
        if (temporalLayers > 1) {
            prefix[0] = (byte) (0x80 | (header.idr ? 0x40 : 0));
            prefix[1] = (byte) 0x80;
            prefix[2] = (byte) ((temporalLayer << 5) | 0x07);
            prefix[3] = (byte) 0x20;
            nal.appendUnit(nalRefIdc, H264Nal.PREFIX, prefix, nalRefIdc != 0 ? 4 : 3);
        }
        nal.appendUnit(nalRefIdc, header.idr ? H264Nal.IDR_SLICE : H264Nal.SLICE, payload, length);
    }

    /**
     * The payload of a prefix NAL unit (ITU-T H.264 7.3.2.12).
     */
    private final byte[] prefix = new byte[4];

    /**
     * Codes the two fields of an interlaced frame as field pictures, the first field in the field
     * order first, and makes the frame a reference.
     *
     * @param idr whether the frame starts with an IDR field
     * @param unreferenced whether the frame is no reference picture
     * @param qp the quantizer
     * @param refreshStart the first column of the intra refresh band
     * @param refreshEnd the column after the band
     * @param pts the presentation timestamp
     * @param duration the frame duration in time-base units
     * @return the access unit size in bits
     */
    private long codeFieldPair(boolean idr, boolean unreferenced, int qp, int refreshStart, int refreshEnd, long pts,
                               long duration) {
        nal.clear();
        appendHeaderUnits(idr, refreshStart, refreshEnd);
        var n = idr ? 0 : (int) framesSinceIdr;
        var bottomFirst = settings.bottomFirst();
        var currentFrameNum = idr ? 0 : frameNum;
        var nalRefIdc = idr ? 3 : unreferenced ? 0 : 2;
        var target = idr || references.isEmpty() ? null : layerTarget();
        for (var k = 0; k < 2; k++) {
            var parity = bottomFirst == (k == 0) ? 1 : 0;
            var structure = parity + 1;
            var list = wanted;
            list.clear();
            var second = k == 1 && !unreferenced;
            if (second && target != null) {
                list.add(parity == 0 ? target.topRef : target.bottomRef);
            }
            if (second) {
                list.add(parity == 0 ? building.bottomRef : building.topRef);
            } else if (target != null) {
                list.add(parity == 0 ? target.topRef : target.bottomRef);
                list.add(parity == 0 ? target.bottomRef : target.topRef);
            }
            var h = header;
            h.reorderCount = list.isEmpty() ? 0 : fieldReorder(list, currentFrameNum, parity, second, h.reorder);
            var refs = list.size() == 2 ? twoRefs : list.size() == 1 ? oneRef : NONE;
            for (var i = 0; i < list.size(); i++) {
                refs[i] = list.get(i);
            }
            var fieldIdr = idr && k == 0;
            coder.startPicture(source, recon, structure, false, !list.isEmpty(), refs, null, null, refreshStart,
                    refreshEnd);
            h.idr = fieldIdr;
            h.predicted = !list.isEmpty();
            h.frameNum = currentFrameNum;
            h.idrPicId = idrPicId;
            h.structure = structure;
            h.pocLsb = (2 * n + k) & ((1 << settings.log2MaxPocLsb()) - 1);
            h.deltaPocBottom = 0;
            h.nalRefIdc = fieldIdr ? 3 : nalRefIdc;
            h.refCount = refs.length;
            h.sliceQp = qp;
            var first = nal.unitCount();
            codeSlices(h, false);
            decodePicture(first, pts, duration, structure, k == 0, k == 1, unreferenced, idr);
        }
        return nal.size() * 8L;
    }

    /**
     * Returns the reference list modification that puts the wanted fields first, or none when the
     * initial list of ITU-T H.264 8.2.4.2.5 already starts with them.
     *
     * @param list the wanted fields in order
     * @param currentFrameNum the {@code frame_num} of the current frame
     * @param parity the parity of the current field
     * @param second whether the current field is the second of a reference frame
     * @param commands receives the modification as {@code modification_of_pic_nums_idc} and
     * {@code abs_diff_pic_num_minus1} pairs
     * @return the number of values written to {@code commands}, 0 for no modification
     */
    private int fieldReorder(List<WideReference> list, int currentFrameNum, int parity, boolean second,
                             int[] commands) {
        var defaults = defaultFieldList(parity, second);
        var same = defaults.size() >= list.size();
        for (var i = 0; same && i < list.size(); i++) {
            same = defaults.get(i) == list.get(i);
        }
        if (same) {
            return 0;
        }
        var predictor = 2 * currentFrameNum + 1;
        for (var i = 0; i < list.size(); i++) {
            var field = list.get(i);
            var refFrameNum = field.frame == building ? currentFrameNum : field.frame.frameNum;
            var number = 2 * picNum(refFrameNum, currentFrameNum) + (field.parity == parity ? 1 : 0);
            var difference = number - predictor;
            if (difference < 0) {
                commands[2 * i] = 0;
                commands[2 * i + 1] = -difference - 1;
            } else {
                commands[2 * i] = 1;
                commands[2 * i + 1] = difference - 1;
            }
            predictor = number;
        }
        return 2 * list.size();
    }

    /**
     * Returns the initial reference list of a P field (ITU-T H.264 8.2.4.2.2 and 8.2.4.2.5): the
     * fields of the reference frames by descending {@code FrameNumWrap}, the current frame's first
     * field included for the second field, alternating parities from the current one.
     *
     * @param parity the parity of the current field
     * @param second whether the current field is the second of a reference frame
     * @return the fields
     */
    private List<WideReference> defaultFieldList(int parity, boolean second) {
        var same = sameParity;
        var opposite = otherParity;
        same.clear();
        opposite.clear();
        if (second) {
            (parity == (settings.bottomFirst() ? 1 : 0) ? same : opposite)
                    .add(settings.bottomFirst() ? building.bottomRef : building.topRef);
        }
        for (var frame : references) {
            same.add(parity == 0 ? frame.topRef : frame.bottomRef);
            opposite.add(parity == 0 ? frame.bottomRef : frame.topRef);
        }
        var result = initialList;
        result.clear();
        var i = 0;
        var j = 0;
        while (i < same.size() || j < opposite.size()) {
            if (i < same.size()) {
                result.add(same.get(i++));
            }
            if (j < opposite.size()) {
                result.add(opposite.get(j++));
            }
        }
        return result;
    }

    /**
     * Decodes the slices of a coded picture with the internal decoder, copies its samples into the
     * frame being built and, once the frame is complete, makes it a reference.
     *
     * @param firstUnit the index of the picture's first unit in the access unit assembler
     * @param pts the presentation timestamp
     * @param duration the frame duration in time-base units
     * @param structure 0 for a frame, 1 for a top field, 2 for a bottom field
     * @param first whether the picture is the first of its frame
     * @param last whether the picture completes its frame
     * @param unreferenced whether the frame is no reference picture
     * @param idr whether the frame is an IDR frame
     */
    private void decodePicture(int firstUnit, long pts, long duration, int structure, boolean first, boolean last,
                               boolean unreferenced, boolean idr) {
        if (!parametersFed) {
            decoder.decodeNal(sps, 0, sps.length);
            decoder.decodeNal(pps, 0, pps.length);
            parametersFed = true;
        }
        decoder.beginPacket(pts, duration);
        var buffer = nal.buffer();
        for (var i = firstUnit; i < nal.unitCount(); i++) {
            var start = nal.unitStart(i);
            var type = buffer[start] & 0x1F;
            if (type != H264Nal.SPS && type != H264Nal.PPS) {
                decoder.decodeNal(buffer, start, nal.unitLength(i));
            }
        }
        decoder.endPacket();
        var planes = decoder.lastFinishedPicture();
        if (first) {
            building = takeFrame();
        }
        copyDecoded(planes, building, structure);
        copyDecoded(planes, lastDecoded, structure);
        if (!last) {
            buildField(building, structure == 2);
            return;
        }
        if (unreferenced) {
            pool.add(building);
            building = null;
            return;
        }
        var frame = building;
        frame.finishReference(settings.interlaced());
        var components = settings.chroma444() ? 3 : 1;
        if (!settings.fieldPictures()) {
            if (frame.frameRef == null) {
                frame.frameRef = new WideReference(frame, frame.planes, -1);
            }
            frame.frameRef.interpolate(components, settings.maxSample(), interpolationScratch);
        }
        if (settings.interlaced()) {
            fieldReference(frame, false).interpolate(components, settings.maxSample(), interpolationScratch);
            fieldReference(frame, true).interpolate(components, settings.maxSample(), interpolationScratch);
        }
        frame.frameNum = idr ? 0 : frameNum;
        addReference(building, idr);
        building = null;
    }

    /**
     * Builds the padded planes and the motion compensation view of the first field of the frame
     * being built, so the second field can predict from it.
     *
     * @param frame the frame being built
     * @param bottom whether the first field is the bottom one
     */
    private void buildField(WideFrame frame, boolean bottom) {
        frame.finishReference(true);
        var components = settings.chroma444() ? 3 : 1;
        fieldReference(frame, bottom).interpolate(components, settings.maxSample(), interpolationScratch);
    }

    /**
     * Returns the motion compensation view of a field of a frame whose field planes exist, creating it
     * on first use.
     *
     * @param frame the frame whose field planes exist
     * @param bottom whether to return the bottom field's view
     * @return the view
     */
    private static WideReference fieldReference(WideFrame frame, boolean bottom) {
        if (bottom) {
            if (frame.bottomRef == null) {
                frame.bottomRef = new WideReference(frame, frame.bottomField, 1);
            }
            return frame.bottomRef;
        }
        if (frame.topRef == null) {
            frame.topRef = new WideReference(frame, frame.topField, 0);
        }
        return frame.topRef;
    }

    /**
     * Copies the decoder's samples of a picture into a frame: every row for a frame, the field's
     * rows for a field.
     *
     * @param planes the decoder's planes
     * @param frame the destination
     * @param structure 0 for a frame, 1 for a top field, 2 for a bottom field
     */
    private static void copyDecoded(H264DecoderEngine.DecodedPlanes planes, WideFrame frame, int structure) {
        for (var c = 0; c < 3; c++) {
            var plane = frame.planes[c];
            if (plane == null) {
                continue;
            }
            var stride = c == 0 ? planes.lumaStride() : planes.chromaStride();
            var origin = c == 0 ? planes.lumaOrigin() : planes.chromaOrigin();
            var bytes = c == 0 ? planes.luma() : c == 1 ? planes.cb() : planes.cr();
            var shorts = c == 0 ? planes.luma16() : c == 1 ? planes.cb16() : planes.cr16();
            var firstRow = structure == 2 ? 1 : 0;
            var step = structure == 0 ? 1 : 2;
            for (var y = firstRow; y < plane.height; y += step) {
                var s = origin + y * stride;
                var d = plane.origin + y * plane.stride;
                if (shorts != null) {
                    System.arraycopy(shorts, s, plane.data, d, plane.width);
                } else {
                    for (var x = 0; x < plane.width; x++) {
                        plane.data[d + x] = (short) (bytes[s + x] & 0xFF);
                    }
                }
            }
        }
    }

    /**
     * Takes a frame from the pool.
     *
     * @return the frame
     */
    private WideFrame takeFrame() {
        return pool.isEmpty() ? new WideFrame(settings) : pool.removeLast();
    }

    /**
     * Makes a decoded frame the most recent reference, with the sliding window of ITU-T H.264
     * 8.2.5.3.
     *
     * @param frame the decoded frame
     * @param idr whether it is an IDR frame, which empties the window first
     */
    private void addReference(WideFrame frame, boolean idr) {
        if (idr) {
            pool.addAll(references);
            references.clear();
            Arrays.fill(layerLatest, null);
            idrPicId = (idrPicId + 1) & 0xFFFF;
            frameNum = 1;
        } else {
            frameNum = (frameNum + 1) & ((1 << settings.log2MaxFrameNum()) - 1);
        }
        var capacity = settings.layerRefs();
        while (references.size() >= capacity) {
            pool.add(references.removeLast());
        }
        references.addFirst(frame);
        if (temporalLayers > 1) {
            layerLatest[temporalLayer] = frame;
        }
    }
}
