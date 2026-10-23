package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Decodes the access units of a scalable bitstream (ITU-T H.264 Annex F) at a target layer: it
 * collects the slices of an access unit, decodes the layer representations the target depends on
 * (F.8.1), maintains the picture order counts, reference marking and reference picture lists of
 * every dependency representation (F.8.2) and outputs the decoded pictures of the target
 * dependency representation in output order (C.4.5).
 *
 * <p>Only frames of 4:2:0 8-bit samples are decoded: an access unit with a layer coded as fields
 * or macroblock-adaptive frames, in another chroma format or at another bit depth is rejected as
 * unsupported.
 */
final class SvcDecoder implements SvcLayerDecoder.Context {
    /**
     * Receives the decoded pictures in output order.
     */
    @FunctionalInterface
    interface Output {
        /**
         * Handles one decoded picture; its buffers may be reused after the call returns.
         *
         * @param frame the picture
         */
        void output(DecoderFrame frame);
    }

    /**
     * The state of one dependency representation across access units (ITU-T H.264 F.8.2).
     */
    private static final class Dependency {
        /**
         * The entries of the decoded picture buffer of the dependency.
         */
        final List<SvcPicture> dpb = new ArrayList<>();

        /**
         * The {@code prevPicOrderCntMsb}.
         */
        int prevPocMsb;

        /**
         * The {@code prevPicOrderCntLsb}.
         */
        int prevPocLsb;

        /**
         * The {@code prevFrameNumOffset}.
         */
        int prevFrameNumOffset;

        /**
         * The {@code frame_num} of the previous picture.
         */
        int prevFrameNum;

        /**
         * Whether the previous picture had a memory management control operation 5.
         */
        boolean prevMmco5;

        /**
         * The {@code PrevRefFrameNum}.
         */
        int prevRefFrameNum;

        /**
         * Whether a picture of the dependency was decoded since the stream started.
         */
        boolean started;

        /**
         * The {@code MaxLongTermFrameIdx}, -1 for "no long-term frame indices".
         */
        int maxLongTermFrameIdx = -1;

        /**
         * The picture order count of the current picture.
         */
        int poc;

        /**
         * The {@code TopFieldOrderCnt} of the current picture.
         */
        int topPoc;

        /**
         * The {@code BottomFieldOrderCnt} of the current picture.
         */
        int bottomPoc;

        /**
         * The {@code PicOrderCntMsb} of the current picture.
         */
        int pocMsb;

        /**
         * The {@code FrameNumOffset} of the current picture.
         */
        int frameNumOffset;

        /**
         * Constructs the state of a dependency without pictures.
         */
        Dependency() {
        }
    }

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * The receiver of the decoded pictures.
     */
    private final Output output;

    /**
     * The source of the color description of the decoded pictures.
     */
    private final Function<H264Sps, ColorInfo> colors;

    /**
     * The layer decoding processes.
     */
    private final SvcLayerDecoder decoder = new SvcLayerDecoder();

    /**
     * The slices of the access unit being collected.
     */
    private final List<SvcSlice> pending = new ArrayList<>();

    /**
     * The slice objects free for reuse.
     */
    private final ArrayDeque<SvcSlice> slicePool = new ArrayDeque<>();

    /**
     * The layer representations of the access unit, indexed by {@code DQId}.
     */
    private final SvcRepresentation[] reps = new SvcRepresentation[128];

    /**
     * Whether the access unit holds slices of each {@code DQId}.
     */
    private final boolean[] present = new boolean[128];

    /**
     * The {@code dqIdList} of the access unit (ITU-T H.264 F.8.1.1), highest first.
     */
    private final int[] dqIdList = new int[128];

    /**
     * The number of entries of {@link #dqIdList}.
     */
    private int dqIdCount;

    /**
     * The state of each dependency representation.
     */
    private final Dependency[] dependencies = new Dependency[8];

    /**
     * The layer arrays, kept for reuse.
     */
    private final List<SvcLayer> layers = new ArrayList<>();

    /**
     * The frames free for reuse.
     */
    private final ArrayDeque<DecoderFrame> framePool = new ArrayDeque<>();

    /**
     * The decoded picture buffer entries free for reuse.
     */
    private final ArrayDeque<SvcPicture> picturePool = new ArrayDeque<>();

    /**
     * The working list of the reference picture list construction.
     */
    private final List<SvcPicture> candidates = new ArrayList<>();

    /**
     * The working list of the second reference picture list.
     */
    private final List<SvcPicture> secondCandidates = new ArrayList<>();

    /**
     * The working list of the reference picture list modification.
     */
    private SvcPicture[] modification = new SvcPicture[33];

    /**
     * The decoding index of the last decoded access unit.
     */
    private long decodeIndex;

    /**
     * The {@code DQId} of the target layer, 127 for the highest layer of each access unit.
     */
    private int targetDqId = 127;

    /**
     * The highest {@code temporal_id} decoded.
     */
    private int maxTemporalId = 7;

    /**
     * The {@code DependencyIdMax} of the access unit.
     */
    private int dependencyIdMax;

    /**
     * The layer picture of dependency 0 of the access unit, holding its motion until it is
     * stored.
     */
    private SvcPicture baseLayerPicture;

    /**
     * The gray frame standing for missing references.
     */
    private DecoderFrame missing;

    /**
     * The presentation timestamp of the access unit being collected.
     */
    private long pts = com.github.auties00.smalt.track.Packet.NO_TIMESTAMP;

    /**
     * The presentation duration of the access unit being collected.
     */
    private long duration = com.github.auties00.smalt.track.Packet.UNKNOWN_DURATION;

    /**
     * Constructs a decoder.
     *
     * @param limits the resource limits
     * @param output the receiver of the decoded pictures
     * @param colors the source of the color description of the decoded pictures
     */
    SvcDecoder(MediaLimits limits, Output output, Function<H264Sps, ColorInfo> colors) {
        this.limits = limits;
        this.output = output;
        this.colors = colors;
        for (var i = 0; i < reps.length; i++) {
            reps[i] = new SvcRepresentation();
            reps[i].dqId = i;
        }
        for (var i = 0; i < dependencies.length; i++) {
            dependencies[i] = new Dependency();
        }
    }

    /**
     * Selects the layer to decode.
     *
     * @param dqId the {@code DQId} of the target layer, 127 for the highest layer of each access
     * unit
     * @param temporalId the highest {@code temporal_id} to decode
     */
    void setTarget(int dqId, int temporalId) {
        this.targetDqId = dqId;
        this.maxTemporalId = temporalId;
    }

    /**
     * Returns a slice object to fill, from the pool.
     *
     * @return an empty slice
     */
    SvcSlice obtainSlice() {
        var slice = slicePool.pollFirst();
        return slice != null ? slice : new SvcSlice();
    }

    /**
     * Adds a slice to the access unit being collected; the slice is dropped when it lies above
     * the target layer or is redundant.
     *
     * @param slice the slice, its header parsed and its payload taken
     */
    void addSlice(SvcSlice slice) {
        if (slice.dqId() > targetDqId || slice.temporalId > maxTemporalId || slice.header.redundantPicCnt > 0) {
            slicePool.addLast(slice);
            return;
        }
        pending.add(slice);
    }

    /**
     * Returns whether the access unit being collected holds slices.
     *
     * @return {@code true} when {@link #endAccessUnit} has an access unit to decode
     */
    boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * Sets the presentation metadata of the access unit being collected.
     *
     * @param pts the presentation timestamp
     * @param duration the presentation duration
     */
    void setTiming(long pts, long duration) {
        this.pts = pts;
        this.duration = duration;
    }

    /**
     * Decodes the collected access unit and outputs the pictures it makes due.
     *
     * @throws MediaException.Malformed if the access unit cannot be decoded
     * @throws MediaException.Unsupported if a layer uses a coding the decoder does not support
     */
    void endAccessUnit() {
        if (pending.isEmpty()) {
            return;
        }
        try {
            decodeAccessUnit();
        } finally {
            for (var i = 0; i < 128; i++) {
                if (present[i]) {
                    reps[i].slices.clear();
                    present[i] = false;
                }
            }
            slicePool.addAll(pending);
            pending.clear();
        }
    }

    /**
     * Outputs every picture still waiting for output, in output order.
     */
    void flush() {
        var dependency = dependencies[dependencyIdMax];
        while (bump(dependency)) {
            continue;
        }
        removeUnused(dependency);
    }

    /**
     * Forgets every picture and the decoding state, for a decoder that restarts.
     */
    void reset() {
        for (var dependency : dependencies) {
            for (var picture : dependency.dpb) {
                recycle(picture);
            }
            dependency.dpb.clear();
            dependency.started = false;
            dependency.maxLongTermFrameIdx = -1;
            dependency.prevMmco5 = false;
        }
        slicePool.addAll(pending);
        pending.clear();
    }

    /**
     * Decodes the access unit held in {@link #pending}.
     */
    private void decodeAccessUnit() {
        var maxDq = -1;
        for (var slice : pending) {
            var dq = slice.dqId();
            present[dq] = true;
            reps[dq].slices.add(slice);
            maxDq = Math.max(maxDq, dq);
        }
        for (var dq = 0; dq < 128; dq++) {
            if (present[dq] && (dq & 15) > 0) {
                if (!present[dq & ~15]) {
                    throw new MediaException.Malformed("quality layer without its dependency representation");
                }
                for (var slice : reps[dq].slices) {
                    slice.inherit(covering(reps[dq & ~15], slice.header.firstMbInSlice));
                }
            }
        }
        dqIdCount = 0;
        var current = maxDq;
        while (current >= 0) {
            if (!present[current]) {
                throw new MediaException.Malformed("missing reference layer representation " + current);
            }
            dqIdList[dqIdCount++] = current;
            var next = -1;
            for (var slice : reps[current].slices) {
                next = Math.max(next, slice.refLayerDqId);
            }
            if (next >= current) {
                throw new MediaException.Malformed("ref_layer_dq_id out of range");
            }
            current = next;
        }
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var rep = reps[dqIdList[i]];
            var refDq = -1;
            for (var slice : rep.slices) {
                refDq = Math.max(refDq, slice.refLayerDqId);
            }
            rep.derive(refDq >= 0 ? reps[refDq] : null);
            checkSupported(rep);
        }
        var dqMax = dqIdList[0];
        var previousTarget = dependencyIdMax;
        dependencyIdMax = dqMax >> 4;
        if (previousTarget != dependencyIdMax) {
            flushDependency(dependencies[previousTarget]);
        }
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var dq = dqIdList[i];
            if ((dq & 15) == 0) {
                startDependency(dq >> 4, reps[dq]);
            }
        }
        var targetBase = reps[dependencyIdMax << 4];
        for (var slice : targetBase.slices) {
            if (slice.header.sliceType % 5 != H264SliceHeader.I) {
                buildLists(dependencyIdMax, slice);
            }
        }
        if (dependencyIdMax > 0 && present[0]) {
            for (var slice : reps[0].slices) {
                if (slice.header.sliceType % 5 == H264SliceHeader.B) {
                    buildLists(0, slice);
                }
            }
        }
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var rep = reps[dqIdList[i]];
            if (rep.croppingChange && rep.dependencyId() != dependencyIdMax) {
                for (var slice : rep.slices) {
                    if (slice.header.sliceType % 5 != H264SliceHeader.I) {
                        buildLists(rep.dependencyId(), slice);
                    }
                }
            }
        }
        decoder.start(this);
        baseLayerPicture = null;
        var targetSps = targetBase.sps;
        var storeBase = targetBase.first().storeRefBasePic && targetBase.first().nalRefIdc > 0;
        DecoderFrame baseFrame = null;
        SvcLayer cur = null;
        SvcLayer ref = null;
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var dq = dqIdList[i];
            var rep = reps[dq];
            if (!rep.spatialResolutionChange) {
                if (rep.minNoInterLayerPred || cur == null) {
                    cur = layer(rep.widthMbs, rep.heightMbs, cur, ref);
                    cur.reset();
                    ref = null;
                } else if (rep.scaledLeft != 0 || rep.scaledTop != 0 || rep.scaledRight != 0
                        || rep.scaledBottom != 0) {
                    var moved = layer(rep.widthMbs, rep.heightMbs, cur, ref);
                    restructure(rep, cur, moved);
                    cur = moved;
                }
                decoder.decodeWithoutResolutionChange(rep, cur);
            } else {
                decoder.prepareResampling(rep, cur);
                ref = cur;
                cur = layer(rep.widthMbs, rep.heightMbs, ref);
                cur.reset();
                decoder.decodeWithResolutionChange(rep, ref, cur);
            }
            if (dq == 0) {
                baseLayerPicture = picture();
                baseLayerPicture.keepMotion(cur);
            }
            if (dq == dependencyIdMax << 4 && storeBase && dq != dqMax) {
                var temp = layer(cur.widthMbs, cur.heightMbs, cur, ref);
                temp.copyFrom(cur);
                decoder.decodeTarget(rep, temp, rep.spatialResolutionChange ? ref : null);
                baseFrame = frameOf(temp, targetSps);
            }
        }
        var targetRep = reps[dqMax];
        decoder.decodeTarget(targetRep, cur, targetRep.spatialResolutionChange ? ref : null);
        var frame = frameOf(cur, targetSps);
        frame.scalableLayer = dqMax;
        if (storeBase && baseFrame == null) {
            baseFrame = frameOf(cur, targetSps);
        }
        frame.damaged = decoder.damaged();
        finishAccessUnit(frame, baseFrame);
    }

    /**
     * Returns the slice of a quality 0 representation whose macroblocks cover a macroblock
     * address.
     *
     * @param base the quality 0 representation
     * @param mbAddr the macroblock address
     * @return the slice with the largest {@code first_mb_in_slice} not above {@code mbAddr}
     */
    private static SvcSlice covering(SvcRepresentation base, int mbAddr) {
        SvcSlice best = null;
        for (var slice : base.slices) {
            var first = slice.header.firstMbInSlice;
            if (first <= mbAddr && (best == null || first > best.header.firstMbInSlice)) {
                best = slice;
            }
        }
        return best != null ? best : base.first();
    }

    /**
     * Rejects a layer representation coded in a way the decoder does not support.
     *
     * @param rep the layer representation
     * @throws MediaException.Unsupported if the layer is not a progressive 4:2:0 8-bit layer
     */
    private static void checkSupported(SvcRepresentation rep) {
        var sps = rep.sps;
        if (!sps.frameMbsOnly) {
            throw new MediaException.Unsupported("h264 svc interlaced",
                    "scalable layers coded as fields or macroblock-adaptive frames");
        }
        if (sps.chromaFormatIdc != 1 || sps.bitDepthLuma != 8 || sps.bitDepthChroma != 8) {
            throw new MediaException.Unsupported("h264 svc format", "scalable layers other than 4:2:0 with 8-bit samples");
        }
    }

    /**
     * Returns layer arrays of a size, other than the given ones.
     *
     * @param widthMbs the width in macroblocks
     * @param heightMbs the height in macroblocks
     * @param busy the arrays in use that must not be returned, possibly {@code null}
     * @return the arrays
     */
    private SvcLayer layer(int widthMbs, int heightMbs, SvcLayer... busy) {
        for (var candidate : layers) {
            if (!candidate.fits(widthMbs, heightMbs)) {
                continue;
            }
            var free = true;
            for (var used : busy) {
                if (used == candidate) {
                    free = false;
                    break;
                }
            }
            if (free) {
                return candidate;
            }
        }
        LimitChecks.checkAllocation(limits, (long) widthMbs * heightMbs * (SvcLayer.COEFFS * 8L + 1700));
        var created = new SvcLayer(widthMbs, heightMbs);
        layers.add(created);
        return created;
    }

    /**
     * Moves the arrays of the reference layer by the scaled reference layer offsets (ITU-T H.264
     * F.8.1.2.2).
     *
     * @param rep the layer representation
     * @param source the arrays of the reference layer
     * @param target receives the restructured arrays
     */
    private static void restructure(SvcRepresentation rep, SvcLayer source, SvcLayer target) {
        target.reset();
        var xOffset = rep.scaledLeft;
        var yOffset = rep.scaledTop;
        for (var mb = 0; mb < target.mbCount; mb++) {
            var refX = mb % target.widthMbs - xOffset / 16;
            var refY = mb / target.widthMbs - yOffset / 16;
            if (refX < 0 || refX >= source.widthMbs || refY < 0 || refY >= source.heightMbs) {
                continue;
            }
            var refMb = refY * source.widthMbs + refX;
            target.sliceIdc[mb] = source.sliceIdc[refMb];
            target.cTrafo[mb] = source.cTrafo[refMb];
            target.baseMode[mb] = source.baseMode[refMb];
            target.mbType[mb] = source.mbType[refMb];
            target.qpY[mb] = source.qpY[refMb];
            target.qpCb[mb] = source.qpCb[refMb];
            target.qpCr[mb] = source.qpCr[refMb];
            target.ipred16x16[mb] = source.ipred16x16[refMb];
            target.ipredChroma[mb] = source.ipredChroma[refMb];
            target.syntax[mb] = source.syntax[refMb];
            target.cbp[mb] = source.cbp[refMb];
            target.codedLuma[mb] = source.codedLuma[refMb];
            System.arraycopy(source.subMbType, refMb * 4, target.subMbType, mb * 4, 4);
            System.arraycopy(source.ipred4x4, refMb * 16, target.ipred4x4, mb * 16, 16);
            System.arraycopy(source.ipred8x8, refMb * 4, target.ipred8x8, mb * 4, 4);
            System.arraycopy(source.refIdx0, refMb * 4, target.refIdx0, mb * 4, 4);
            System.arraycopy(source.refIdx1, refMb * 4, target.refIdx1, mb * 4, 4);
            System.arraycopy(source.mv0, refMb * 32, target.mv0, mb * 32, 32);
            System.arraycopy(source.mv1, refMb * 32, target.mv1, mb * 32, 32);
            System.arraycopy(source.tCoeff, refMb * SvcLayer.COEFFS, target.tCoeff, mb * SvcLayer.COEFFS,
                    SvcLayer.COEFFS);
            System.arraycopy(source.sTCoeff, refMb * SvcLayer.COEFFS, target.sTCoeff, mb * SvcLayer.COEFFS,
                    SvcLayer.COEFFS);
        }
        for (var y = Math.max(0, yOffset); y < Math.min(target.height, source.height + yOffset); y++) {
            for (var x = Math.max(0, xOffset); x < Math.min(target.width, source.width + xOffset); x++) {
                target.rY[y * target.width + x] = source.rY[(y - yOffset) * source.width + x - xOffset];
                target.cY[y * target.stride + SvcLayer.PAD + x] =
                        source.cY[(y - yOffset) * source.stride + SvcLayer.PAD + x - xOffset];
            }
        }
        var cx = (xOffset >> 4) * 8;
        var cy = (yOffset >> 4) * 8;
        for (var y = Math.max(0, cy); y < Math.min(target.chromaHeight, source.chromaHeight + cy); y++) {
            for (var x = Math.max(0, cx); x < Math.min(target.chromaWidth, source.chromaWidth + cx); x++) {
                var t = y * target.chromaWidth + x;
                var s = (y - cy) * source.chromaWidth + x - cx;
                target.rCb[t] = source.rCb[s];
                target.rCr[t] = source.rCr[s];
                var ts = y * target.chromaStride + SvcLayer.PAD + x;
                var ss = (y - cy) * source.chromaStride + SvcLayer.PAD + x - cx;
                target.cCb[ts] = source.cCb[ss];
                target.cCr[ts] = source.cCr[ss];
            }
        }
    }

    /**
     * Copies the constructed samples of the target into a frame for output and reference.
     *
     * @param vars the arrays of the target
     * @param sps the sequence parameter set of the target dependency representation
     * @return the frame
     */
    private DecoderFrame frameOf(SvcLayer vars, H264Sps sps) {
        DecoderFrame frame = null;
        while (!framePool.isEmpty() && frame == null) {
            var candidate = framePool.pollFirst();
            if (candidate.mbWidth == vars.widthMbs && candidate.mbHeight == vars.heightMbs) {
                frame = candidate;
            }
        }
        if (frame == null) {
            LimitChecks.checkAllocation(limits, DecoderFrame.allocationSize(vars.widthMbs, vars.heightMbs, 1, false,
                    false));
            frame = new DecoderFrame(vars.widthMbs, vars.heightMbs, 1);
        }
        for (var y = 0; y < vars.height; y++) {
            System.arraycopy(vars.cY, y * vars.stride + SvcLayer.PAD, frame.luma,
                    frame.lumaOrigin + y * frame.lumaStride, vars.width);
        }
        for (var y = 0; y < vars.chromaHeight; y++) {
            System.arraycopy(vars.cCb, y * vars.chromaStride + SvcLayer.PAD, frame.cb,
                    frame.chromaOrigin + y * frame.chromaStride, vars.chromaWidth);
            System.arraycopy(vars.cCr, y * vars.chromaStride + SvcLayer.PAD, frame.cr,
                    frame.chromaOrigin + y * frame.chromaStride, vars.chromaWidth);
        }
        frame.fields = DecoderFrame.FRAME;
        frame.codingStruct = DecoderFrame.CODED_FRAME;
        frame.beginDecoding(DecoderFrame.FRAME, true);
        frame.extendBorders(0, vars.height, 0, vars.chromaHeight);
        frame.publishDone();
        frame.sps = sps;
        frame.color = colors.apply(sps);
        frame.picStruct = -1;
        frame.pairKey = -1;
        frame.auxiliary = null;
        frame.voIdx = 0;
        frame.viewId = 0;
        frame.scalableLayer = -1;
        frame.discard = false;
        frame.nonExisting = false;
        frame.damaged = false;
        frame.filmGrain = null;
        frame.framePacking = null;
        frame.orientation = null;
        frame.job = null;
        return frame;
    }

    /**
     * Returns a decoded picture buffer entry, from the pool.
     *
     * @return a cleared entry
     */
    private SvcPicture picture() {
        var picture = picturePool.pollFirst();
        if (picture == null) {
            picture = new SvcPicture();
        }
        picture.refBase = false;
        picture.marking = SvcPicture.UNUSED;
        picture.nonExisting = false;
        picture.outputNeeded = false;
        picture.frame = null;
        picture.longTermFrameIdx = 0;
        return picture;
    }

    /**
     * Returns an entry and its frame to their pools.
     *
     * @param picture the entry, no longer in a decoded picture buffer
     */
    private void recycle(SvcPicture picture) {
        if (picture.frame != null) {
            framePool.addLast(picture.frame);
            picture.frame = null;
        }
        picturePool.addLast(picture);
    }

    /**
     * Derives the picture order count of a dependency representation and handles the gaps in
     * its {@code frame_num} (ITU-T H.264 F.8.2.1 and F.8.2.5).
     *
     * @param dependencyId the {@code dependency_id}
     * @param rep the representation with {@code quality_id} 0
     */
    private void startDependency(int dependencyId, SvcRepresentation rep) {
        var dependency = dependencies[dependencyId];
        var slice = rep.first();
        var header = slice.header;
        var sps = header.sps;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        if (slice.idr) {
            dependency.started = true;
        } else if (!dependency.started) {
            dependency.prevRefFrameNum = header.frameNum;
            dependency.prevFrameNum = header.frameNum;
            dependency.started = true;
        } else if (header.frameNum != dependency.prevRefFrameNum
                && header.frameNum != (dependency.prevRefFrameNum + 1) % maxFrameNum) {
            fillGap(dependencyId, dependency, header.frameNum, sps);
        }
        computePoc(dependency, slice, sps);
    }

    /**
     * Inserts the frames lost to a gap in {@code frame_num} as non-existing reference frames
     * (ITU-T H.264 8.2.5.2 with F.8.2.5).
     *
     * @param dependencyId the {@code dependency_id}
     * @param dependency the dependency state
     * @param frameNum the {@code frame_num} of the current picture
     * @param sps the sequence parameter set
     */
    private void fillGap(int dependencyId, Dependency dependency, int frameNum, H264Sps sps) {
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        var unused = (dependency.prevRefFrameNum + 1) % maxFrameNum;
        var guard = 0;
        while (unused != frameNum && guard++ < maxFrameNum) {
            numberPictures(dependency, unused, maxFrameNum);
            slidingWindow(dependency, sps, false, unused);
            var picture = picture();
            picture.nonExisting = true;
            picture.frameNum = unused;
            picture.marking = SvcPicture.SHORT_TERM;
            picture.poc = 0;
            dependency.dpb.add(picture);
            dependency.prevRefFrameNum = unused;
            dependency.prevFrameNum = unused;
            unused = (unused + 1) % maxFrameNum;
        }
        removeUnused(dependency);
    }

    /**
     * Computes the picture order counts of the current picture of a dependency representation
     * (ITU-T H.264 8.2.1 for frames).
     *
     * @param dependency the dependency state
     * @param slice the first slice of the representation with {@code quality_id} 0
     * @param sps the sequence parameter set
     */
    private static void computePoc(Dependency dependency, SvcSlice slice, H264Sps sps) {
        var header = slice.header;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        int top;
        int bottom;
        switch (sps.pocType) {
            case 0 -> {
                var prevMsb = dependency.prevPocMsb;
                var prevLsb = dependency.prevPocLsb;
                if (slice.idr) {
                    prevMsb = 0;
                    prevLsb = 0;
                }
                var maxLsb = 1 << sps.log2MaxPocLsb;
                var lsb = header.pocLsb;
                int msb;
                if (lsb < prevLsb && prevLsb - lsb >= maxLsb / 2) {
                    msb = prevMsb + maxLsb;
                } else if (lsb > prevLsb && lsb - prevLsb > maxLsb / 2) {
                    msb = prevMsb - maxLsb;
                } else {
                    msb = prevMsb;
                }
                dependency.pocMsb = msb;
                top = msb + lsb;
                bottom = top + header.deltaPocBottom;
            }
            case 1 -> {
                var offset = frameNumOffset(dependency, slice, maxFrameNum);
                dependency.frameNumOffset = offset;
                var cycle = sps.offsetForRefFrame.length;
                var absFrameNum = cycle != 0 ? offset + header.frameNum : 0;
                if (slice.nalRefIdc == 0 && absFrameNum > 0) {
                    absFrameNum--;
                }
                var expected = 0;
                if (absFrameNum > 0) {
                    var cycleCount = (absFrameNum - 1) / cycle;
                    var inCycle = (absFrameNum - 1) % cycle;
                    var delta = 0;
                    for (var value : sps.offsetForRefFrame) {
                        delta += value;
                    }
                    expected = cycleCount * delta;
                    for (var i = 0; i <= inCycle; i++) {
                        expected += sps.offsetForRefFrame[i];
                    }
                }
                if (slice.nalRefIdc == 0) {
                    expected += sps.offsetForNonRefPic;
                }
                top = expected + header.deltaPoc0;
                bottom = top + sps.offsetForTopToBottomField + header.deltaPoc1;
            }
            default -> {
                var offset = frameNumOffset(dependency, slice, maxFrameNum);
                dependency.frameNumOffset = offset;
                int temp;
                if (slice.idr) {
                    temp = 0;
                } else if (slice.nalRefIdc == 0) {
                    temp = 2 * (offset + header.frameNum) - 1;
                } else {
                    temp = 2 * (offset + header.frameNum);
                }
                top = temp;
                bottom = temp;
            }
        }
        dependency.topPoc = top;
        dependency.bottomPoc = bottom;
        dependency.poc = Math.min(top, bottom);
    }

    /**
     * Derives the {@code FrameNumOffset} of the current picture (ITU-T H.264 equations 8-6 and
     * 8-11).
     *
     * @param dependency the dependency state
     * @param slice the first slice of the picture
     * @param maxFrameNum the {@code MaxFrameNum}
     * @return the offset
     */
    private static int frameNumOffset(Dependency dependency, SvcSlice slice, int maxFrameNum) {
        if (slice.idr) {
            return 0;
        }
        var previous = dependency.prevMmco5 ? 0 : dependency.prevFrameNumOffset;
        if (dependency.prevFrameNum > slice.header.frameNum) {
            return previous + maxFrameNum;
        }
        return previous;
    }

    /**
     * Assigns {@code FrameNumWrap} to the short-term references of a dependency (ITU-T H.264
     * 8.2.4.1 for frames).
     *
     * @param dependency the dependency state
     * @param frameNum the {@code frame_num} of the current picture
     * @param maxFrameNum the {@code MaxFrameNum}
     */
    private static void numberPictures(Dependency dependency, int frameNum, int maxFrameNum) {
        for (var picture : dependency.dpb) {
            if (picture.marking == SvcPicture.SHORT_TERM) {
                picture.frameNumWrap = picture.frameNum > frameNum ? picture.frameNum - maxFrameNum : picture.frameNum;
            }
        }
    }

    /**
     * Builds the reference picture lists of a slice (ITU-T H.264 F.8.2.3 for frames).
     *
     * @param dependencyId the {@code dependency_id} of the slice
     * @param slice the slice, with {@code quality_id} 0
     */
    private void buildLists(int dependencyId, SvcSlice slice) {
        var dependency = dependencies[dependencyId];
        var header = slice.header;
        var sps = header.sps;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        numberPictures(dependency, header.frameNum, maxFrameNum);
        var b = header.sliceType % 5 == H264SliceHeader.B;
        var count0 = header.numRefIdxActive[0];
        var count1 = b ? header.numRefIdxActive[1] : 0;
        if (slice.refLists == null || slice.refLists[0].length != count0 || slice.refLists[1].length != count1) {
            slice.refLists = new SvcPicture[][]{new SvcPicture[count0], new SvcPicture[count1]};
        }
        var poc = dependency.poc;
        candidates.clear();
        secondCandidates.clear();
        if (!b) {
            addShortTerms(dependency, slice.useRefBasePic, candidates, Integer.MIN_VALUE, true, true);
            addLongTerms(dependency, slice.useRefBasePic, candidates);
            fill(slice.refLists[0], candidates);
        } else {
            addShortTerms(dependency, slice.useRefBasePic, candidates, poc, true, false);
            addShortTerms(dependency, slice.useRefBasePic, candidates, poc, false, false);
            addLongTerms(dependency, slice.useRefBasePic, candidates);
            addShortTerms(dependency, slice.useRefBasePic, secondCandidates, poc, false, false);
            addShortTerms(dependency, slice.useRefBasePic, secondCandidates, poc, true, false);
            addLongTerms(dependency, slice.useRefBasePic, secondCandidates);
            if (secondCandidates.size() > 1 && secondCandidates.equals(candidates)) {
                var first = secondCandidates.get(0);
                secondCandidates.set(0, secondCandidates.get(1));
                secondCandidates.set(1, first);
            }
            fill(slice.refLists[0], candidates);
            fill(slice.refLists[1], secondCandidates);
        }
        for (var list = 0; list < (b ? 2 : 1); list++) {
            if (header.refPicListModification[list]) {
                modify(dependency, slice, list, maxFrameNum);
            }
        }
    }

    /**
     * Returns whether an entry of the decoded picture buffer takes part in the reference picture
     * list construction (ITU-T H.264 F.8.2.2).
     *
     * @param dependency the dependency state
     * @param picture the entry
     * @param useRefBasePic the {@code use_ref_base_pic_flag} of the slice
     * @return {@code true} when the entry is present for the construction
     */
    private static boolean listed(Dependency dependency, SvcPicture picture, boolean useRefBasePic) {
        if (!picture.isReference()) {
            return false;
        }
        if (!useRefBasePic) {
            return !picture.refBase;
        }
        if (picture.refBase) {
            return true;
        }
        for (var other : dependency.dpb) {
            if (other == picture || !other.refBase || other.marking != picture.marking) {
                continue;
            }
            if (picture.marking == SvcPicture.SHORT_TERM ? other.frameNum == picture.frameNum
                    : other.longTermFrameIdx == picture.longTermFrameIdx) {
                return false;
            }
        }
        return true;
    }

    /**
     * Appends the short-term references of a dependency to a list in an initial list order.
     *
     * @param dependency the dependency state
     * @param useRefBasePic the {@code use_ref_base_pic_flag} of the slice
     * @param target the list
     * @param poc the picture order count of the current picture
     * @param before for B slices, whether the references preceding the current picture are
     * wanted (in descending order) rather than the following ones (in ascending order); for P
     * slices ignored
     * @param byPicNum whether the order is by descending {@code PicNum} (P slices)
     */
    private void addShortTerms(Dependency dependency, boolean useRefBasePic, List<SvcPicture> target, int poc,
                               boolean before, boolean byPicNum) {
        var start = target.size();
        for (var picture : dependency.dpb) {
            if (picture.marking != SvcPicture.SHORT_TERM || !listed(dependency, picture, useRefBasePic)) {
                continue;
            }
            if (!byPicNum && (before ? picture.poc >= poc : picture.poc <= poc)) {
                continue;
            }
            target.add(picture);
        }
        var section = target.subList(start, target.size());
        if (byPicNum) {
            section.sort((a, b) -> Integer.compare(b.frameNumWrap, a.frameNumWrap));
        } else if (before) {
            section.sort((a, b) -> Integer.compare(b.poc, a.poc));
        } else {
            section.sort((a, b) -> Integer.compare(a.poc, b.poc));
        }
    }

    /**
     * Appends the long-term references of a dependency to a list in ascending
     * {@code LongTermPicNum}.
     *
     * @param dependency the dependency state
     * @param useRefBasePic the {@code use_ref_base_pic_flag} of the slice
     * @param target the list
     */
    private static void addLongTerms(Dependency dependency, boolean useRefBasePic, List<SvcPicture> target) {
        var start = target.size();
        for (var picture : dependency.dpb) {
            if (picture.marking == SvcPicture.LONG_TERM && listed(dependency, picture, useRefBasePic)) {
                target.add(picture);
            }
        }
        target.subList(start, target.size()).sort((a, b) -> Integer.compare(a.longTermFrameIdx, b.longTermFrameIdx));
    }

    /**
     * Copies an initial list into a reference picture list, truncated or padded with no
     * reference.
     *
     * @param list the reference picture list
     * @param initial the initial list
     */
    private static void fill(SvcPicture[] list, List<SvcPicture> initial) {
        for (var i = 0; i < list.length; i++) {
            list[i] = i < initial.size() ? initial.get(i) : null;
        }
    }

    /**
     * Applies the reference picture list modification of a slice to one of its lists (ITU-T
     * H.264 8.2.4.3 for frames).
     *
     * @param dependency the dependency state
     * @param slice the slice whose list is modified
     * @param list the list index
     * @param maxFrameNum the {@code MaxFrameNum}
     */
    private void modify(Dependency dependency, SvcSlice slice, int list, int maxFrameNum) {
        var header = slice.header;
        var entries = slice.refLists[list];
        var count = entries.length;
        if (modification.length < count + 1) {
            modification = new SvcPicture[count + 1];
        }
        var work = modification;
        System.arraycopy(entries, 0, work, 0, count);
        work[count] = null;
        var currPicNum = header.frameNum;
        var predicted = currPicNum;
        var refIdx = 0;
        for (var i = 0; i < header.modificationCount[list] && refIdx < count; i++) {
            var idc = header.modificationIdc[list][i];
            var value = header.modificationValue[list][i];
            SvcPicture chosen = null;
            if (idc == 0 || idc == 1) {
                int noWrap;
                if (idc == 0) {
                    noWrap = predicted - (value + 1);
                    if (noWrap < 0) {
                        noWrap += maxFrameNum;
                    }
                } else {
                    noWrap = predicted + (value + 1);
                    if (noWrap >= maxFrameNum) {
                        noWrap -= maxFrameNum;
                    }
                }
                predicted = noWrap;
                var picNum = noWrap > currPicNum ? noWrap - maxFrameNum : noWrap;
                for (var picture : dependency.dpb) {
                    if (picture.marking == SvcPicture.SHORT_TERM && picture.frameNumWrap == picNum
                            && listed(dependency, picture, slice.useRefBasePic)) {
                        chosen = picture;
                        break;
                    }
                }
            } else if (idc == 2) {
                for (var picture : dependency.dpb) {
                    if (picture.marking == SvcPicture.LONG_TERM && picture.longTermFrameIdx == value
                            && listed(dependency, picture, slice.useRefBasePic)) {
                        chosen = picture;
                        break;
                    }
                }
            } else {
                continue;
            }
            for (var c = count; c > refIdx; c--) {
                work[c] = work[c - 1];
            }
            work[refIdx++] = chosen;
            var n = refIdx;
            for (var c = refIdx; c <= count; c++) {
                if (work[c] != chosen) {
                    work[n++] = work[c];
                }
            }
        }
        System.arraycopy(work, 0, entries, 0, count);
    }

    /**
     * Marks the reference pictures after an access unit and stores its pictures, then outputs
     * the pictures due (ITU-T H.264 F.8.2.4 and C.4.5).
     *
     * @param frame the decoded picture of the target dependency representation
     * @param baseFrame the reference base picture of the target dependency representation, or
     * {@code null}
     */
    private void finishAccessUnit(DecoderFrame frame, DecoderFrame baseFrame) {
        var target = dependencies[dependencyIdMax];
        var targetSlice = reps[dependencyIdMax << 4].first();
        var targetHeader = targetSlice.header;
        var mmco5 = hasMmco5(targetHeader);
        if (targetSlice.idr || mmco5) {
            if (targetSlice.idr && targetHeader.noOutputOfPriorPics) {
                for (var picture : target.dpb) {
                    picture.outputNeeded = false;
                }
            } else {
                while (bump(target)) {
                    continue;
                }
            }
        }
        DecoderFrame stored = null;
        SvcPicture current = null;
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var dq = dqIdList[i];
            if ((dq & 15) != 0) {
                continue;
            }
            var dependencyId = dq >> 4;
            var dependency = dependencies[dependencyId];
            var rep = reps[dq];
            var slice = rep.first();
            var isTarget = dependencyId == dependencyIdMax;
            SvcPicture picture = null;
            SvcPicture base = null;
            if (slice.nalRefIdc > 0 || isTarget) {
                picture = dependencyId == 0 && baseLayerPicture != null ? baseLayerPicture : picture();
                if (picture == baseLayerPicture) {
                    baseLayerPicture = null;
                }
                describe(picture, dependency, rep, slice);
                if (isTarget) {
                    picture.frame = frame;
                    stored = frame;
                    picture.outputNeeded = slice.output;
                    current = picture;
                    frame.pts = pts;
                    frame.duration = duration;
                    frame.keyframe = slice.idr;
                    frame.idr = slice.idr;
                    frame.decodeIndex = ++decodeIndex;
                }
            }
            if (slice.nalRefIdc > 0) {
                if (slice.storeRefBasePic) {
                    base = picture();
                    describe(base, dependency, rep, slice);
                    base.refBase = true;
                    if (isTarget) {
                        base.frame = baseFrame;
                        baseFrame = null;
                    }
                }
                mark(dependency, rep, slice, picture, base);
            }
            updateOrder(dependency, slice, hasMmco5(slice.header), picture, base);
            if (isTarget) {
                frame.poc = picture.poc;
            }
            if (!isTarget) {
                if (picture != null && picture.isReference()) {
                    dependency.dpb.add(picture);
                } else if (picture != null) {
                    recycle(picture);
                }
                if (base != null) {
                    dependency.dpb.add(base);
                }
                removeUnused(dependency);
            } else {
                if (base != null) {
                    dependency.dpb.add(base);
                }
                removeUnused(dependency);
                store(dependency, picture, rep.sps);
            }
        }
        if (baseFrame != null) {
            framePool.addLast(baseFrame);
        }
        if (current == null && stored != null) {
            framePool.addLast(stored);
        }
        if (baseLayerPicture != null) {
            recycle(baseLayerPicture);
            baseLayerPicture = null;
        }
    }

    /**
     * Fills the identification of a new entry from its layer representation.
     *
     * @param picture the entry
     * @param dependency the dependency state
     * @param rep the layer representation with {@code quality_id} 0
     * @param slice its first slice
     */
    private static void describe(SvcPicture picture, Dependency dependency, SvcRepresentation rep, SvcSlice slice) {
        picture.frameNum = slice.header.frameNum;
        picture.poc = dependency.poc;
        picture.temporalId = slice.temporalId;
        picture.scaledLeft = rep.scaledLeft;
        picture.scaledTop = rep.scaledTop;
        picture.scaledRight = rep.scaledRight;
        picture.scaledBottom = rep.scaledBottom;
    }

    /**
     * Returns whether a slice header holds a memory management control operation 5.
     *
     * @param header the slice header
     * @return {@code true} when one of its operations is 5
     */
    private static boolean hasMmco5(H264SliceHeader header) {
        if (!header.adaptiveRefPicMarking) {
            return false;
        }
        for (var i = 0; i < header.mmcoCount; i++) {
            if (header.mmcoOp[i] == 5) {
                return true;
            }
        }
        return false;
    }

    /**
     * Updates the state the picture order count and {@code frame_num} derivations of the next
     * picture of a dependency read (ITU-T H.264 8.2.1).
     *
     * @param dependency the dependency state
     * @param slice the first slice of the current picture
     * @param mmco5 whether the picture had a memory management control operation 5
     * @param picture the decoded picture entry, or {@code null}
     * @param base the reference base picture entry, or {@code null}
     */
    private static void updateOrder(Dependency dependency, SvcSlice slice, boolean mmco5, SvcPicture picture,
                                    SvcPicture base) {
        var header = slice.header;
        if (mmco5) {
            var temp = Math.min(dependency.topPoc, dependency.bottomPoc);
            if (picture != null) {
                picture.poc = dependency.poc - temp;
            }
            if (base != null) {
                base.poc = dependency.poc - temp;
            }
            dependency.topPoc -= temp;
            dependency.bottomPoc -= temp;
        }
        if (slice.nalRefIdc > 0) {
            if (mmco5) {
                dependency.prevPocMsb = 0;
                dependency.prevPocLsb = dependency.topPoc;
            } else {
                dependency.prevPocMsb = dependency.pocMsb;
                dependency.prevPocLsb = header.pocLsb;
            }
            dependency.prevRefFrameNum = mmco5 ? 0 : header.frameNum;
        }
        dependency.prevMmco5 = mmco5;
        dependency.prevFrameNumOffset = mmco5 ? 0 : dependency.frameNumOffset;
        dependency.prevFrameNum = mmco5 ? 0 : header.frameNum;
    }

    /**
     * Marks the reference pictures of a dependency representation after decoding it (ITU-T H.264
     * F.8.2.4.1 for frames).
     *
     * @param dependency the dependency state
     * @param rep the layer representation with {@code quality_id} 0
     * @param slice its first slice, which holds the marking syntax
     * @param picture the entry of the decoded picture
     * @param base the entry of the reference base picture, or {@code null}
     */
    private void mark(Dependency dependency, SvcRepresentation rep, SvcSlice slice, SvcPicture picture,
                      SvcPicture base) {
        var header = slice.header;
        var sps = rep.sps;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        if (slice.idr) {
            for (var entry : dependency.dpb) {
                entry.marking = SvcPicture.UNUSED;
            }
            if (header.longTermReference) {
                picture.marking = SvcPicture.LONG_TERM;
                picture.longTermFrameIdx = 0;
                dependency.maxLongTermFrameIdx = 0;
                if (base != null) {
                    base.marking = SvcPicture.LONG_TERM;
                    base.longTermFrameIdx = 0;
                }
            } else {
                picture.marking = SvcPicture.SHORT_TERM;
                dependency.maxLongTermFrameIdx = -1;
                if (base != null) {
                    base.marking = SvcPicture.SHORT_TERM;
                }
            }
            return;
        }
        numberPictures(dependency, header.frameNum, maxFrameNum);
        var currPicNum = header.frameNum;
        if (slice.adaptiveRefBasePicMarking) {
            for (var i = 0; i < slice.baseMmcoCount; i++) {
                var arg = slice.baseMmcoArg[i];
                if (slice.baseMmcoOp[i] == 1) {
                    var picNum = currPicNum - (arg + 1);
                    for (var entry : dependency.dpb) {
                        if (entry.refBase && entry.marking == SvcPicture.SHORT_TERM && entry.frameNumWrap == picNum) {
                            entry.marking = SvcPicture.UNUSED;
                        }
                    }
                } else {
                    for (var entry : dependency.dpb) {
                        if (entry.refBase && entry.marking == SvcPicture.LONG_TERM && entry.longTermFrameIdx == arg) {
                            entry.marking = SvcPicture.UNUSED;
                        }
                    }
                }
            }
        }
        var currentLong = false;
        var baseLong = false;
        if (header.adaptiveRefPicMarking) {
            for (var i = 0; i < header.mmcoCount; i++) {
                var op = header.mmcoOp[i];
                var arg1 = header.mmcoArg1[i];
                var arg2 = header.mmcoArg2[i];
                switch (op) {
                    case 1 -> {
                        var picNum = currPicNum - (arg1 + 1);
                        for (var entry : dependency.dpb) {
                            if (!entry.refBase && entry.marking == SvcPicture.SHORT_TERM
                                    && entry.frameNumWrap == picNum) {
                                entry.marking = SvcPicture.UNUSED;
                            }
                        }
                    }
                    case 2 -> {
                        for (var entry : dependency.dpb) {
                            if (!entry.refBase && entry.marking == SvcPicture.LONG_TERM
                                    && entry.longTermFrameIdx == arg1) {
                                entry.marking = SvcPicture.UNUSED;
                            }
                        }
                    }
                    case 3 -> {
                        var picNum = currPicNum - (arg1 + 1);
                        assignLongTerm(dependency, picNum, arg2, false);
                        var baseFound = false;
                        for (var entry : dependency.dpb) {
                            if (entry.refBase && entry.marking == SvcPicture.SHORT_TERM && entry.frameNumWrap == picNum) {
                                baseFound = true;
                                break;
                            }
                        }
                        if (baseFound) {
                            assignLongTerm(dependency, picNum, arg2, true);
                        } else {
                            for (var entry : dependency.dpb) {
                                if (entry.refBase && entry.marking == SvcPicture.LONG_TERM
                                        && entry.longTermFrameIdx == arg2) {
                                    entry.marking = SvcPicture.UNUSED;
                                }
                            }
                        }
                    }
                    case 4 -> {
                        dependency.maxLongTermFrameIdx = arg1 - 1;
                        for (var entry : dependency.dpb) {
                            if (entry.marking == SvcPicture.LONG_TERM
                                    && entry.longTermFrameIdx > dependency.maxLongTermFrameIdx) {
                                entry.marking = SvcPicture.UNUSED;
                            }
                        }
                    }
                    case 5 -> {
                        for (var entry : dependency.dpb) {
                            entry.marking = SvcPicture.UNUSED;
                        }
                        dependency.maxLongTermFrameIdx = -1;
                    }
                    case 6 -> {
                        for (var entry : dependency.dpb) {
                            if (!entry.refBase && entry.marking == SvcPicture.LONG_TERM
                                    && entry.longTermFrameIdx == arg2) {
                                entry.marking = SvcPicture.UNUSED;
                            }
                        }
                        picture.marking = SvcPicture.LONG_TERM;
                        picture.longTermFrameIdx = arg2;
                        currentLong = true;
                        for (var entry : dependency.dpb) {
                            if (entry.refBase && entry.marking == SvcPicture.LONG_TERM
                                    && entry.longTermFrameIdx == arg2) {
                                entry.marking = SvcPicture.UNUSED;
                            }
                        }
                        if (base != null) {
                            base.marking = SvcPicture.LONG_TERM;
                            base.longTermFrameIdx = arg2;
                            baseLong = true;
                        }
                    }
                    default -> {
                    }
                }
            }
        } else {
            slidingWindow(dependency, sps, false, header.frameNum);
        }
        if (!currentLong) {
            picture.marking = SvcPicture.SHORT_TERM;
        }
        if (base != null && !baseLong) {
            if (!slice.adaptiveRefBasePicMarking) {
                dependency.dpb.add(picture);
                numberPictures(dependency, header.frameNum, maxFrameNum);
                slidingWindow(dependency, sps, true, header.frameNum);
                dependency.dpb.remove(picture);
            }
            base.marking = SvcPicture.SHORT_TERM;
        }
    }

    /**
     * Assigns a long-term frame index to the short-term entry with a picture number (ITU-T H.264
     * 8.2.5.4.3), considering only entries of one kind.
     *
     * @param dependency the dependency state
     * @param picNum the picture number
     * @param index the long-term frame index
     * @param refBase whether reference base pictures are considered rather than decoded pictures
     */
    private static void assignLongTerm(Dependency dependency, int picNum, int index, boolean refBase) {
        for (var entry : dependency.dpb) {
            if (entry.refBase == refBase && entry.marking == SvcPicture.LONG_TERM && entry.longTermFrameIdx == index) {
                entry.marking = SvcPicture.UNUSED;
            }
        }
        for (var entry : dependency.dpb) {
            if (entry.refBase == refBase && entry.marking == SvcPicture.SHORT_TERM && entry.frameNumWrap == picNum) {
                entry.marking = SvcPicture.LONG_TERM;
                entry.longTermFrameIdx = index;
            }
        }
    }

    /**
     * Applies the sliding window marking of a dependency (ITU-T H.264 F.8.2.4.2 for frames).
     *
     * @param dependency the dependency state
     * @param sps the sequence parameter set
     * @param refBasePicFlag whether the window runs for a reference base picture
     * @param frameNum the {@code frame_num} of the current picture
     */
    private static void slidingWindow(Dependency dependency, H264Sps sps, boolean refBasePicFlag, int frameNum) {
        var shortCount = 0;
        var longCount = 0;
        for (var entry : dependency.dpb) {
            if (entry.marking == SvcPicture.SHORT_TERM) {
                shortCount++;
            } else if (entry.marking == SvcPicture.LONG_TERM) {
                longCount++;
            }
        }
        if (shortCount + longCount < Math.max(sps.maxNumRefFrames, 1) || shortCount == 0) {
            return;
        }
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        SvcPicture decoded = null;
        SvcPicture base = null;
        for (var entry : dependency.dpb) {
            if (entry.marking != SvcPicture.SHORT_TERM) {
                continue;
            }
            if (entry.refBase) {
                if (base == null || entry.frameNumWrap < base.frameNumWrap) {
                    base = entry;
                }
            } else if (decoded == null || entry.frameNumWrap < decoded.frameNumWrap) {
                decoded = entry;
            }
        }
        var decodedWrap = decoded != null ? decoded.frameNumWrap : maxFrameNum;
        var baseWrap = base != null ? base.frameNumWrap : maxFrameNum;
        var chosen = decodedWrap < baseWrap ? decoded : base;
        if (chosen != null) {
            chosen.marking = SvcPicture.UNUSED;
        }
    }

    /**
     * Stores the current picture of the target dependency in its decoded picture buffer,
     * outputting pictures as the buffer requires (ITU-T H.264 C.4.5.1 and C.4.5.2).
     *
     * @param dependency the target dependency state
     * @param picture the entry of the current picture
     * @param sps the sequence parameter set of the target
     */
    private void store(Dependency dependency, SvcPicture picture, H264Sps sps) {
        var size = Math.max(1, sps.dpbFrames());
        if (picture.isReference()) {
            while (occupancy(dependency) >= size && bump(dependency)) {
                continue;
            }
            dependency.dpb.add(picture);
            return;
        }
        if (!picture.outputNeeded) {
            recycle(picture);
            return;
        }
        while (occupancy(dependency) >= size) {
            var lowest = lowestWaiting(dependency);
            if (lowest == null || picture.poc < lowest.poc) {
                output.output(picture.frame);
                recycle(picture);
                return;
            }
            bump(dependency);
        }
        dependency.dpb.add(picture);
    }

    /**
     * Returns the number of frame buffers the decoded pictures of the target dependency occupy.
     *
     * @param dependency the target dependency state
     * @return the number of decoded picture entries that are references or wait for output
     */
    private static int occupancy(Dependency dependency) {
        var count = 0;
        for (var entry : dependency.dpb) {
            if (!entry.refBase && !entry.nonExisting && (entry.isReference() || entry.outputNeeded)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the waiting picture with the lowest picture order count.
     *
     * @param dependency the target dependency state
     * @return the picture, or {@code null} when none waits
     */
    private static SvcPicture lowestWaiting(Dependency dependency) {
        SvcPicture lowest = null;
        for (var entry : dependency.dpb) {
            if (entry.outputNeeded && (lowest == null || entry.poc < lowest.poc)) {
                lowest = entry;
            }
        }
        return lowest;
    }

    /**
     * Outputs the waiting picture with the lowest picture order count and removes it when it is
     * no longer a reference (ITU-T H.264 C.4.5.3).
     *
     * @param dependency the target dependency state
     * @return {@code false} when no picture waits for output
     */
    private boolean bump(Dependency dependency) {
        var lowest = lowestWaiting(dependency);
        if (lowest == null) {
            return false;
        }
        lowest.outputNeeded = false;
        output.output(lowest.frame);
        if (!lowest.isReference()) {
            dependency.dpb.remove(lowest);
            recycle(lowest);
        }
        return true;
    }

    /**
     * Outputs every waiting picture of a dependency that stopped being the target.
     *
     * @param dependency the dependency state
     */
    private void flushDependency(Dependency dependency) {
        while (bump(dependency)) {
            continue;
        }
        removeUnused(dependency);
    }

    /**
     * Removes the entries that are neither references nor waiting for output.
     *
     * @param dependency the dependency state
     */
    private void removeUnused(Dependency dependency) {
        var dpb = dependency.dpb;
        for (var i = dpb.size() - 1; i >= 0; i--) {
            var entry = dpb.get(i);
            if (!entry.isReference() && !entry.outputNeeded) {
                dpb.remove(i);
                recycle(entry);
            }
        }
    }

    @Override
    public SvcRepresentation representation(int dqId) {
        return dqId >= 0 && dqId < 128 && present[dqId] ? reps[dqId] : null;
    }

    @Override
    public SvcSlice slice(int sliceIdc) {
        var rep = reps[sliceIdc & 127];
        var first = sliceIdc >> 7;
        var slice = first < rep.mbSlice.length ? rep.mbSlice[first] : null;
        return slice != null ? slice : rep.first();
    }

    @Override
    public SvcSlice listSlice(int sliceIdc) {
        var rep = reps[(sliceIdc & 127) & ~15];
        var first = sliceIdc >> 7;
        var slice = first < rep.mbSlice.length ? rep.mbSlice[first] : null;
        return slice != null ? slice : rep.first();
    }

    @Override
    public SvcSlice weightSlice(int dqId) {
        for (var i = 0; i < dqIdCount; i++) {
            var dq = dqIdList[i];
            if (dq < dqId && !reps[dq].first().basePredWeightTable) {
                return reps[dq].first();
            }
        }
        return null;
    }

    @Override
    public int currentPoc() {
        return dependencies[dependencyIdMax].poc;
    }

    @Override
    public DecoderFrame missingFrame() {
        var rep = reps[dependencyIdMax << 4];
        if (missing == null || missing.mbWidth != rep.widthMbs || missing.mbHeight != rep.heightMbs) {
            missing = new DecoderFrame(rep.widthMbs, rep.heightMbs, 1);
            missing.fillGray();
            missing.extendBorders(0, rep.heightMbs * 16, 0, rep.heightMbs * 8);
        }
        return missing;
    }
}
