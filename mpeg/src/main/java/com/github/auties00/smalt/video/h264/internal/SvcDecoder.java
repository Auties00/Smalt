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
 * every dependency representation (F.8.2, in {@link SvcDpb}) and outputs the decoded pictures of
 * the target dependency representation in output order (C.4.5).
 *
 * <p>Layers are coded as frames, as macroblock-adaptive frames or as fields; the two fields of a
 * frame come in two access units and are output together. Only 4:2:0 samples of 8 bits are
 * decoded: an access unit with a layer in another chroma format or at another bit depth is
 * rejected as unsupported.
 */
final class SvcDecoder implements SvcLayerDecoder.Context, SvcDpb.Entries {
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
     * The decoded picture buffer of each dependency representation.
     */
    private final SvcDpb[] dpbs = new SvcDpb[8];

    /**
     * The layer arrays, kept for reuse.
     */
    private final List<SvcLayer> layers = new ArrayList<>();

    /**
     * The most layer arrays kept in {@link #layers} before those of other sizes are forgotten: four
     * for each of the eight dependency layers.
     */
    private static final int MAX_LAYER_ARRAYS = 32;

    /**
     * The frames free for reuse.
     */
    private final ArrayDeque<DecoderFrame> framePool = new ArrayDeque<>();

    /**
     * The decoded picture buffer entries free for reuse.
     */
    private final ArrayDeque<SvcPicture> picturePool = new ArrayDeque<>();

    /**
     * The pictures each reference index of the base layer referred to, indexed
     * {@code [list][mbAddr * 4 + quadrant]}, for {@link SvcMotionStore#keep}.
     */
    private final int[][] refPics = {new int[0], new int[0]};

    /**
     * The decoding index of the last decoded access unit.
     */
    private long decodeIndex;

    /**
     * The number of losses of stream data reported so far.
     */
    private int lossEvents;

    /**
     * The value of {@link #lossEvents} when the first slice of the access unit being collected
     * arrived.
     */
    private int lossesAtStart;

    /**
     * The value of {@link #lossesAtStart} for the access unit decoded before.
     */
    private int lossesAtPreviousStart;

    /**
     * The {@code DQId} of the target layer representation of the access unit decoded before, -1
     * before the first.
     */
    private int previousDqMax = -1;

    /**
     * Whether the access unit being collected was received incomplete.
     */
    private boolean incomplete;

    /**
     * The identifier of the next decoded picture buffer entry.
     */
    private int nextPictureId;

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
     * A non-reference first field of the target dependency that is output as soon as its frame
     * completes, ahead of the pictures waiting in the decoded picture buffer (C.4.5.2), or
     * {@code null}.
     */
    private SvcPicture directPending;

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
        for (var i = 0; i < dpbs.length; i++) {
            dpbs[i] = new SvcDpb();
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
     * Notes a loss of stream data: a gap in {@code frame_num} after it may stand for lost
     * pictures even in a sequence that allows gaps.
     */
    void noteLoss() {
        lossEvents++;
    }

    /**
     * Notes that a slice of the access unit being collected arrived in a packet declared
     * incomplete, so that its pictures are damaged.
     */
    void noteIncomplete() {
        incomplete = true;
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
        this.lossesAtStart = lossEvents;
    }

    /**
     * Decodes the collected access unit and outputs the pictures it makes due. An access unit
     * that fails to decode counts as a loss of stream data for the pictures after it.
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
        } catch (RuntimeException failure) {
            lossEvents++;
            throw failure;
        } finally {
            for (var i = 0; i < 128; i++) {
                if (present[i]) {
                    reps[i].slices.clear();
                    present[i] = false;
                }
            }
            slicePool.addAll(pending);
            pending.clear();
            incomplete = false;
            lossesAtPreviousStart = lossesAtStart;
        }
    }

    /**
     * Outputs every picture still waiting for output, in output order.
     */
    void flush() {
        releaseDirect();
        var dpb = dpbs[dependencyIdMax];
        dpb.lastField = null;
        dpb.lastBaseField = null;
        while (bump(dpb)) {
            continue;
        }
        dpb.removeUnused(this);
    }

    /**
     * Forgets every picture and the decoding state, for a decoder that restarts.
     */
    void reset() {
        if (directPending != null) {
            recycle(directPending);
            directPending = null;
        }
        for (var dpb : dpbs) {
            dpb.reset(this);
        }
        slicePool.addAll(pending);
        pending.clear();
    }

    /**
     * Decodes the access unit held in {@link #pending}.
     */
    private void decodeAccessUnit() {
        // TODO: decode on the worker threads of the decoder, as the single-layer path does; the layers of an
        // access unit and its macroblocks are decoded one after the other on the calling thread
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
            var ref = refDq >= 0 ? reps[refDq] : null;
            rep.derive(ref);
            if (ref != null && !rep.spatialResolutionChange && !rep.minNoInterLayerPred && rep.scaledLeft == 0
                    && rep.scaledTop == 0 && rep.scaledRight == 0 && rep.scaledBottom == 0
                    && (rep.widthMbs != ref.widthMbs || rep.heightMbs != ref.heightMbs)) {
                throw new MediaException.Malformed("layer representation " + dqIdList[i]
                        + " predicts without resampling from a reference layer of another size");
            }
            checkSupported(rep);
        }
        var dqMax = dqIdList[0];
        var previousTarget = dependencyIdMax;
        dependencyIdMax = dqMax >> 4;
        if (previousTarget != dependencyIdMax) {
            flushDependency(dpbs[previousTarget]);
        }
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var dq = dqIdList[i];
            if ((dq & 15) == 0) {
                var dpb = dpbs[dq >> 4];
                dpb.begin(reps[dq].first(), this, lossesAtStart != lossesAtPreviousStart);
                prepareCurrent(dpb, reps[dq]);
            }
        }
        if (lossesAtStart != lossesAtPreviousStart) {
            for (var dpb : dpbs) {
                dpb.markLongTermStale();
            }
        }
        var targetBase = reps[dependencyIdMax << 4];
        for (var slice : targetBase.slices) {
            if (slice.header.sliceType % 5 != H264SliceHeader.I) {
                dpbs[dependencyIdMax].buildLists(slice);
            }
        }
        if (dependencyIdMax > 0 && present[0]) {
            for (var slice : reps[0].slices) {
                if (slice.header.sliceType % 5 != H264SliceHeader.I) {
                    dpbs[0].buildLists(slice);
                }
            }
        }
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var rep = reps[dqIdList[i]];
            if (rep.croppingChange && rep.dependencyId() != dependencyIdMax && rep.dependencyId() != 0) {
                for (var slice : rep.slices) {
                    if (slice.header.sliceType % 5 != H264SliceHeader.I) {
                        dpbs[rep.dependencyId()].buildLists(slice);
                    }
                }
            }
        }
        decoder.start(this);
        var targetSps = targetBase.sps;
        var storeBase = targetBase.first().storeRefBasePic && targetBase.first().nalRefIdc > 0;
        DecoderFrame baseFrame = null;
        SvcLayer baseVars = null;
        SvcLayer cur = null;
        SvcLayer ref = null;
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var dq = dqIdList[i];
            var rep = reps[dq];
            if (!rep.spatialResolutionChange) {
                if (rep.minNoInterLayerPred || cur == null) {
                    cur = layer(rep.widthMbs, rep.heightMbs, cur, ref, baseVars);
                    cur.reset();
                    ref = null;
                } else if (rep.scaledLeft != 0 || rep.scaledTop != 0 || rep.scaledRight != 0
                        || rep.scaledBottom != 0) {
                    var moved = layer(rep.widthMbs, rep.heightMbs, cur, ref, baseVars);
                    restructure(rep, cur, moved);
                    cur = moved;
                }
                cur.setGeometry(rep);
                decoder.decodeWithoutResolutionChange(rep, cur);
            } else {
                decoder.prepareResampling(rep, cur);
                ref = cur;
                cur = layer(rep.widthMbs, rep.heightMbs, ref, null, baseVars);
                cur.reset();
                cur.setGeometry(rep);
                decoder.decodeWithResolutionChange(rep, ref, cur);
            }
            if (dq == 0) {
                keepMotion(cur, rep);
            }
            if (dq == dependencyIdMax << 4 && storeBase && dq != dqMax) {
                baseVars = layer(cur.widthMbs, cur.heightMbs, cur, ref, null);
                baseVars.copyFrom(cur);
                decoder.decodeTarget(rep, baseVars, rep.spatialResolutionChange ? ref : null);
            }
        }
        var targetRep = reps[dqMax];
        decoder.decodeTarget(targetRep, cur, targetRep.spatialResolutionChange ? ref : null);
        var targetDpb = dpbs[dependencyIdMax];
        var picture = targetDpb.current;
        var structure = targetDpb.structure;
        var frame = samplesOf(picture, cur, targetSps, structure);
        frame.scalableLayer = dqMax;
        var damaged = accessUnitDamaged(targetBase)
                || (lossEvents != lossesAtStart && dqMax < previousDqMax);
        previousDqMax = dqMax;
        if (damaged) {
            frame.damaged = true;
        }
        if (storeBase) {
            var base = targetDpb.pairBase != null ? targetDpb.pairBase : newEntry(targetDpb, targetBase, true);
            targetDpb.currentBase = base;
            baseFrame = samplesOf(base, baseVars != null ? baseVars : cur, targetSps, structure);
            if (damaged) {
                baseFrame.damaged = true;
                base.damaged = true;
            }
        }
        if (damaged) {
            for (var i = 0; i < dqIdCount; i++) {
                if ((dqIdList[i] & 15) == 0) {
                    dpbs[dqIdList[i] >> 4].current.damaged = true;
                }
            }
        }
        finishAccessUnit(frame);
    }

    /**
     * Returns whether the pictures of the access unit may differ from the encoder's: a slice
     * arrived in an incomplete packet, the decoding predicted from a missing, lost or damaged
     * picture or read the motion of a lost or damaged co-located picture, or the initial lists of
     * a slice that predicts left out a stand-in for a lost picture.
     *
     * @param targetBase the representation of the target dependency with {@code quality_id} 0
     * @return {@code true} when the access unit is damaged
     */
    private boolean accessUnitDamaged(SvcRepresentation targetBase) {
        if (incomplete || decoder.damaged()) {
            return true;
        }
        for (var slice : targetBase.slices) {
            if (slice.header.sliceType % 5 != H264SliceHeader.I && slice.lostEntryLeftOut) {
                return true;
            }
        }
        if (dependencyIdMax > 0 && present[0]) {
            for (var slice : reps[0].slices) {
                if (slice.header.sliceType % 5 != H264SliceHeader.I && slice.lostEntryLeftOut) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sets the entry of the current picture of a dependency representation: the entry of the
     * first field a second field completes, or a new entry.
     *
     * @param dpb the decoded picture buffer of the dependency
     * @param rep the layer representation with {@code quality_id} 0
     */
    private void prepareCurrent(SvcDpb dpb, SvcRepresentation rep) {
        dpb.current = dpb.pairDecoded != null ? dpb.pairDecoded : newEntry(dpb, rep, false);
        dpb.currentBase = null;
        if (dpb.pairDecoded != null) {
            dpb.current.temporalId = Math.max(dpb.current.temporalId, rep.first().temporalId);
        }
    }

    /**
     * Returns a new entry for the current picture of a dependency representation.
     *
     * @param dpb the decoded picture buffer of the dependency
     * @param rep the layer representation with {@code quality_id} 0
     * @param refBase whether the entry is a reference base picture
     * @return the entry, not yet in the buffer
     */
    private SvcPicture newEntry(SvcDpb dpb, SvcRepresentation rep, boolean refBase) {
        var slice = rep.first();
        var picture = obtain();
        picture.refBase = refBase;
        picture.frameNum = slice.header.frameNum;
        picture.codedFrame = dpb.structure == SvcPicture.FRAME;
        picture.mbaff = rep.mbaff;
        picture.firstFieldReference = slice.nalRefIdc > 0;
        picture.temporalId = slice.temporalId;
        picture.scaledLeft = rep.scaledLeft;
        picture.scaledTop = rep.scaledTop;
        picture.scaledRight = rep.scaledRight;
        picture.scaledBottom = rep.scaledBottom;
        return picture;
    }

    /**
     * Keeps the motion of the layer representation with DQId 0 in the entry of its picture, with
     * the pictures its reference indices referred to.
     *
     * @param cur the arrays of the representation
     * @param rep the representation
     */
    private void keepMotion(SvcLayer cur, SvcRepresentation rep) {
        var dpb = dpbs[0];
        var count = cur.mbCount;
        if (refPics[0].length < count * 4) {
            refPics[0] = new int[count * 4];
            refPics[1] = new int[count * 4];
        }
        for (var mb = 0; mb < count; mb++) {
            var slice = mb < rep.mbSlice.length ? rep.mbSlice[mb] : null;
            for (var list = 0; list < 2; list++) {
                var refs = list == 0 ? cur.refIdx0 : cur.refIdx1;
                for (var q = 0; q < 4; q++) {
                    var refIdx = refs[mb * 4 + q];
                    refPics[list][mb * 4 + q] = slice == null || refIdx < 0 ? -1
                            : referenceId(slice, list, refIdx, cur.mbaff && cur.fieldMb[mb], mb);
                }
            }
        }
        dpb.current.motion(dpb.structure).keep(cur, refPics);
    }

    /**
     * Returns the identification of the picture a reference index of a macroblock referred to,
     * as {@link SvcMotionStore#refPic0} holds it.
     *
     * @param slice the slice of the macroblock
     * @param list the list index
     * @param refIdx the reference index
     * @param fieldMb whether the macroblock is a field macroblock of a frame
     * @param mbAddr the macroblock address
     * @return the identification, or -1 when the index names no picture
     */
    private static int referenceId(SvcSlice slice, int list, int refIdx, boolean fieldMb, int mbAddr) {
        if (slice.refLists == null) {
            return -1;
        }
        var index = fieldMb ? refIdx >> 1 : refIdx;
        if (index >= slice.refLists[list].length) {
            return -1;
        }
        var picture = slice.refLists[list][index];
        if (picture == null) {
            return -1;
        }
        var structure = slice.refStructures[list][index];
        if (fieldMb) {
            var parity = (mbAddr & 1) == 0 ? SvcPicture.TOP : SvcPicture.BOTTOM;
            structure = (refIdx & 1) == 0 ? parity : SvcPicture.FRAME - parity;
        }
        return picture.id * 4 + structure;
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
     * @throws MediaException.Unsupported if the layer is not a 4:2:0 8-bit layer
     */
    private static void checkSupported(SvcRepresentation rep) {
        var sps = rep.sps;
        if (sps.chromaFormatIdc != 1 || sps.bitDepthLuma != 8 || sps.bitDepthChroma != 8) {
            throw new MediaException.Unsupported("h264 svc format", "scalable layers other than 4:2:0 with 8-bit samples");
        }
    }

    /**
     * Returns layer arrays of a size, other than the ones in use. Arrays of other sizes beyond the
     * few a stream uses are forgotten, so a stream that keeps changing its picture size does not
     * accumulate them.
     *
     * @param widthMbs the width in macroblocks
     * @param heightMbs the height in macroblocks
     * @param busy0 arrays in use that must not be returned, or {@code null}
     * @param busy1 arrays in use that must not be returned, or {@code null}
     * @param busy2 arrays in use that must not be returned, or {@code null}
     * @return the arrays
     */
    private SvcLayer layer(int widthMbs, int heightMbs, SvcLayer busy0, SvcLayer busy1, SvcLayer busy2) {
        for (var candidate : layers) {
            if (candidate.fits(widthMbs, heightMbs) && candidate != busy0 && candidate != busy1
                    && candidate != busy2) {
                return candidate;
            }
        }
        if (layers.size() >= MAX_LAYER_ARRAYS) {
            layers.removeIf(candidate -> !candidate.fits(widthMbs, heightMbs) && candidate != busy0
                    && candidate != busy1 && candidate != busy2);
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
        target.setGeometry(rep);
        var xOffset = rep.scaledLeft;
        var yOffset = rep.scaledTop / (rep.fieldPic ? 2 : 1);
        var eS = rep.mbaff ? 2 : 1;
        var bS = source.mbaff ? 2 : 1;
        for (var mb = 0; mb < target.mbCount; mb++) {
            var refX = (mb / eS) % target.widthMbs - xOffset / 16;
            var refY = ((mb / eS) / target.widthMbs) * eS + mb % eS - yOffset / 16;
            if (refX < 0 || refX >= source.widthMbs || refY < 0 || refY >= source.heightMbs) {
                continue;
            }
            var refMb = (refY / bS) * bS * source.widthMbs + refY % bS + refX * bS;
            target.sliceIdc[mb] = source.sliceIdc[refMb];
            target.fieldMb[mb] = source.fieldMb[refMb];
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
     * Copies the constructed samples of the target into the frame of an entry, which receives a
     * frame when it has none: all rows for a frame, the rows of the field for a field.
     *
     * @param picture the entry
     * @param vars the arrays of the target
     * @param sps the sequence parameter set of the target dependency representation
     * @param structure the structure of the current picture
     * @return the frame
     */
    private DecoderFrame samplesOf(SvcPicture picture, SvcLayer vars, H264Sps sps, int structure) {
        var frameHeightMbs = sps.frameHeightInMbs;
        var frame = picture.frame;
        if (frame == null) {
            frame = obtainFrame(vars.widthMbs, frameHeightMbs);
            picture.frame = frame;
            frame.fields = 0;
            frame.codingStruct = structure == SvcPicture.FRAME ? (picture.mbaff ? DecoderFrame.CODED_MBAFF
                    : DecoderFrame.CODED_FRAME) : DecoderFrame.CODED_FIELDS;
            frame.beginDecoding(structure, true);
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
            frame.missingFieldFilled = false;
            frame.filmGrain = null;
            frame.framePacking = null;
            frame.orientation = null;
            frame.job = null;
        } else {
            frame.beginDecoding(structure, false);
        }
        var step = structure == SvcPicture.FRAME ? 1 : 2;
        var first = structure == SvcPicture.BOTTOM ? 1 : 0;
        for (var y = 0; y < vars.height; y++) {
            System.arraycopy(vars.cY, y * vars.stride + SvcLayer.PAD, frame.luma,
                    frame.lumaOrigin + (first + y * step) * frame.lumaStride, vars.width);
        }
        for (var y = 0; y < vars.chromaHeight; y++) {
            var row = frame.chromaOrigin + (first + y * step) * frame.chromaStride;
            System.arraycopy(vars.cCb, y * vars.chromaStride + SvcLayer.PAD, frame.cb, row, vars.chromaWidth);
            System.arraycopy(vars.cCr, y * vars.chromaStride + SvcLayer.PAD, frame.cr, row, vars.chromaWidth);
        }
        frame.fields |= structure;
        if (structure == SvcPicture.FRAME) {
            frame.extendBorders(0, vars.height, 0, vars.chromaHeight);
        } else {
            frame.extendFieldBorders(structure == SvcPicture.TOP ? 0 : 1);
        }
        frame.publishDone(structure);
        return frame;
    }

    /**
     * Returns a frame of a size from the pool, or a new one.
     *
     * @param widthMbs the width in macroblocks
     * @param heightMbs the frame height in macroblocks
     * @return the frame
     */
    private DecoderFrame obtainFrame(int widthMbs, int heightMbs) {
        DecoderFrame frame = null;
        while (!framePool.isEmpty() && frame == null) {
            var candidate = framePool.pollFirst();
            if (candidate.mbWidth == widthMbs && candidate.mbHeight == heightMbs) {
                frame = candidate;
            }
        }
        if (frame == null) {
            LimitChecks.checkAllocation(limits, DecoderFrame.allocationSize(widthMbs, heightMbs, 1, false, false));
            frame = new DecoderFrame(widthMbs, heightMbs, 1);
        }
        return frame;
    }

    @Override
    public SvcPicture obtain() {
        var picture = picturePool.pollFirst();
        if (picture == null) {
            picture = new SvcPicture();
        }
        picture.id = nextPictureId++;
        picture.refBase = false;
        picture.topMarking = SvcPicture.UNUSED;
        picture.bottomMarking = SvcPicture.UNUSED;
        picture.fields = 0;
        picture.codedFrame = true;
        picture.mbaff = false;
        picture.firstFieldReference = false;
        picture.nonExisting = false;
        picture.lostStandIn = false;
        picture.damaged = false;
        picture.outputNeeded = false;
        picture.frame = null;
        picture.longTermFrameIdx = 0;
        picture.frameNumWrap = 0;
        picture.topPoc = 0;
        picture.bottomPoc = 0;
        picture.temporalId = 0;
        picture.topMotion.present = false;
        picture.bottomMotion.present = false;
        return picture;
    }

    @Override
    public void recycle(SvcPicture picture) {
        if (picture.frame != null) {
            framePool.addLast(picture.frame);
            picture.frame = null;
        }
        picturePool.addLast(picture);
    }

    /**
     * Marks the reference pictures after an access unit and stores its pictures, then outputs
     * the pictures due (ITU-T H.264 F.8.2.4 and C.4.5).
     *
     * @param frame the frame of the target dependency representation holding the decoded picture
     */
    private void finishAccessUnit(DecoderFrame frame) {
        var target = dpbs[dependencyIdMax];
        var targetSlice = reps[dependencyIdMax << 4].first();
        var targetHeader = targetSlice.header;
        if (directPending != null && directPending != target.current) {
            releaseDirect();
        }
        var mmco5 = SvcDpb.hasMmco5(targetHeader);
        if ((targetSlice.idr || mmco5) && target.pairDecoded == null) {
            if (targetSlice.idr && targetHeader.noOutputOfPriorPics) {
                for (var picture : target.entries) {
                    picture.outputNeeded = false;
                }
            } else {
                target.lastField = null;
                target.lastBaseField = null;
                while (bump(target)) {
                    continue;
                }
            }
        }
        for (var i = dqIdCount - 1; i >= 0; i--) {
            var dq = dqIdList[i];
            if ((dq & 15) != 0) {
                continue;
            }
            var dependencyId = dq >> 4;
            var dpb = dpbs[dependencyId];
            var rep = reps[dq];
            var slice = rep.first();
            var isTarget = dependencyId == dependencyIdMax;
            var picture = dpb.current;
            var reference = slice.nalRefIdc > 0;
            var newPicture = dpb.pairDecoded == null && picture != directPending;
            SvcPicture base = null;
            if (reference && slice.storeRefBasePic) {
                base = isTarget ? dpb.currentBase : dpb.pairBase != null ? dpb.pairBase : newEntry(dpb, rep, true);
            }
            var newBase = base != null && dpb.pairBase == null;
            if (isTarget) {
                if (newPicture) {
                    frame.pts = pts;
                    frame.duration = duration;
                    frame.keyframe = slice.idr;
                    frame.idr = slice.idr;
                    frame.decodeIndex = ++decodeIndex;
                    picture.outputNeeded = slice.output;
                } else {
                    picture.outputNeeded |= slice.output;
                }
            }
            if (reference) {
                if (newPicture) {
                    dpb.entries.add(picture);
                }
                if (newBase) {
                    dpb.entries.add(base);
                }
                dpb.mark(slice, rep.sps, picture, base);
            }
            dpb.finishPicture(slice, picture, base);
            if (isTarget) {
                frame.poc = picture.poc();
            }
            if (!isTarget) {
                if (!reference && newPicture) {
                    recycle(picture);
                }
                dpb.removeUnused(this);
            } else {
                if (newBase) {
                    dpb.entries.remove(base);
                    dpb.entries.add(base);
                }
                store(dpb, picture, newPicture, rep.sps);
                dpb.removeUnused(this);
            }
        }
    }

    /**
     * Stores the current picture of the target dependency in its decoded picture buffer,
     * outputting pictures as the buffer requires (ITU-T H.264 C.4.5.1 and C.4.5.2).
     *
     * @param dpb the decoded picture buffer of the target dependency
     * @param picture the entry of the current picture
     * @param newPicture whether the entry is new, rather than the entry of a first field the
     * current field completes
     * @param sps the sequence parameter set of the target
     */
    private void store(SvcDpb dpb, SvcPicture picture, boolean newPicture, H264Sps sps) {
        if (!newPicture) {
            if (picture == directPending && picture.fields == SvcPicture.FRAME) {
                directPending = null;
                dpb.lastField = null;
                emit(picture);
                if (picture.isReference()) {
                    dpb.entries.add(picture);
                } else {
                    recycle(picture);
                }
            }
            return;
        }
        dpb.entries.remove(picture);
        var size = Math.max(1, sps.dpbFrames());
        var firstField = picture.fields != SvcPicture.FRAME;
        if (picture.isReference()) {
            while (occupancy(dpb) >= size && bump(dpb)) {
                continue;
            }
            dpb.entries.add(picture);
            if (firstField) {
                dpb.lastField = picture;
            }
            return;
        }
        if (!picture.outputNeeded) {
            if (firstField) {
                dpb.entries.add(picture);
                dpb.lastField = picture;
            } else {
                recycle(picture);
            }
            return;
        }
        while (occupancy(dpb) >= size) {
            var lowest = lowestWaiting(dpb);
            if (lowest == null || picture.poc() < lowest.poc()) {
                if (firstField) {
                    directPending = picture;
                    dpb.lastField = picture;
                } else {
                    emit(picture);
                    recycle(picture);
                }
                return;
            }
            bump(dpb);
        }
        dpb.entries.add(picture);
        if (firstField) {
            dpb.lastField = picture;
        }
    }

    /**
     * Outputs the non-reference first field held for output, as a frame whose missing field is
     * mid-gray.
     */
    private void releaseDirect() {
        if (directPending == null) {
            return;
        }
        var picture = directPending;
        directPending = null;
        var dpb = dpbs[dependencyIdMax];
        if (dpb.lastField == picture) {
            dpb.lastField = null;
        }
        emit(picture);
        recycle(picture);
    }

    /**
     * Returns the number of frame buffers the decoded pictures of the target dependency occupy.
     *
     * @param dpb the decoded picture buffer of the target dependency
     * @return the number of decoded picture entries that are references or wait for output
     */
    private static int occupancy(SvcDpb dpb) {
        var count = 0;
        for (var entry : dpb.entries) {
            if (!entry.refBase && !entry.nonExisting && (entry.isReference() || entry.outputNeeded)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the waiting picture with the lowest picture order count.
     *
     * @param dpb the decoded picture buffer of the target dependency
     * @return the picture, or {@code null} when none waits
     */
    private static SvcPicture lowestWaiting(SvcDpb dpb) {
        SvcPicture lowest = null;
        for (var entry : dpb.entries) {
            if (entry.outputNeeded && (lowest == null || entry.poc() < lowest.poc())) {
                lowest = entry;
            }
        }
        return lowest;
    }

    /**
     * Outputs the waiting picture with the lowest picture order count and removes it when it is
     * no longer a reference (ITU-T H.264 C.4.5.3).
     *
     * @param dpb the decoded picture buffer of the target dependency
     * @return {@code false} when no picture waits for output
     */
    private boolean bump(SvcDpb dpb) {
        var lowest = lowestWaiting(dpb);
        if (lowest == null) {
            return false;
        }
        lowest.outputNeeded = false;
        emit(lowest);
        if (!lowest.isReference() && lowest != dpb.lastField) {
            dpb.entries.remove(lowest);
            recycle(lowest);
        }
        return true;
    }

    /**
     * Hands the frame of an entry to the output, a frame holding a single field with its missing
     * field mid-gray.
     *
     * @param picture the entry
     */
    private void emit(SvcPicture picture) {
        var frame = picture.frame;
        if (frame == null) {
            return;
        }
        if (frame.fields != DecoderFrame.FRAME) {
            frame.completeMissingField();
            if (!picture.bottomMotion.present && picture.fields == SvcPicture.TOP) {
                picture.bottomMotion.intra(frame.mbWidth, frame.mbHeight / 2);
            }
        }
        output.output(frame);
    }

    /**
     * Outputs every waiting picture of a dependency that stopped being the target.
     *
     * @param dpb the decoded picture buffer of the dependency
     */
    private void flushDependency(SvcDpb dpb) {
        releaseDirect();
        dpb.lastField = null;
        dpb.lastBaseField = null;
        while (bump(dpb)) {
            continue;
        }
        dpb.removeUnused(this);
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
    public int currentPoc(int structure) {
        return dependencyPoc(dependencyIdMax, structure);
    }

    @Override
    public int dependencyPoc(int dependencyId, int structure) {
        var dpb = dpbs[dependencyId];
        return switch (structure) {
            case SvcPicture.TOP -> dpb.structure == SvcPicture.BOTTOM ? dpb.current.topPoc : dpb.topPoc;
            case SvcPicture.BOTTOM -> dpb.structure == SvcPicture.TOP ? dpb.current.bottomPoc : dpb.bottomPoc;
            default -> dpb.poc;
        };
    }

    @Override
    public int currentStructure(int dependencyId) {
        return dpbs[dependencyId].structure;
    }

    @Override
    public DecoderFrame missingFrame() {
        var rep = reps[dependencyIdMax << 4];
        var heightMbs = rep.sps.frameHeightInMbs;
        if (missing == null || missing.mbWidth != rep.widthMbs || missing.mbHeight != heightMbs) {
            LimitChecks.checkAllocation(limits, DecoderFrame.allocationSize(rep.widthMbs, heightMbs, 1, false, false));
            missing = new DecoderFrame(rep.widthMbs, heightMbs, 1);
            missing.fillGray();
            missing.extendBorders(0, heightMbs * 16, 0, heightMbs * 8);
        }
        return missing;
    }
}
