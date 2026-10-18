package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Decodes HEVC NAL units into pictures and outputs them in output order: parameter set management,
 * the picture order count (ITU-T H.265 8.3.1), the reference picture set (8.3.2), the reference
 * picture lists (8.3.4), the slice decoding of each picture, its in-loop filters (8.7) and the
 * output order decoded picture buffer with its bumping process (C.5.2).
 *
 * <p>A picture is finished when the next access unit starts (a first slice segment, an access
 * unit delimiter, a parameter set, a prefix SEI or an end of sequence NAL unit), when
 * {@link #endAccessUnit()} is called, or on {@link #flush()}. Random access starts at the first
 * IRAP picture; the RASL pictures of an IRAP picture that starts a coded video sequence are
 * skipped, as the specification allows.
 *
 * <p>Instances are not thread-safe.
 */
public final class HevcDecoderEngine {
    /**
     * The most pictures the decoded picture buffer may hold, including generated ones, before the
     * stream is treated as corrupt.
     */
    private static final int MAX_DPB = 24;

    /**
     * The sequence parameter sets by identifier.
     */
    private final HevcSps[] spsTable = new HevcSps[16];

    /**
     * The picture parameter sets by identifier.
     */
    private final HevcPps[] ppsTable = new HevcPps[64];

    /**
     * The pictures of the decoded picture buffer.
     */
    private final List<HevcFrame> dpb = new ArrayList<>();

    /**
     * Pictures no longer in the decoded picture buffer, kept for reuse.
     */
    private final List<HevcFrame> pool = new ArrayList<>();

    /**
     * Receives the output pictures, in output order.
     */
    private final Consumer<HevcFrame> output;

    /**
     * The resource limits.
     */
    private final MediaLimits limits;

    /**
     * Whether pictures are decoded on the shared pool, concurrently with the parsing of later
     * pictures.
     */
    private final boolean parallel;

    /**
     * The most pictures decoded at once: 1 when not {@link #parallel}.
     */
    private final int threads;

    /**
     * The idle picture decoders shared by the jobs of the engine.
     */
    private final Queue<PictureDecoder> idleDecoders = new ConcurrentLinkedQueue<>();

    /**
     * The started jobs that may still be running, in start order.
     */
    private final ArrayDeque<PictureJob> inFlight = new ArrayDeque<>();

    /**
     * The job of the picture receiving slices, or {@code null} between pictures.
     */
    private PictureJob currentJob;

    /**
     * The number of jobs created so far.
     */
    private long submittedJobs;

    /**
     * The sequence number of the last job of the leading run of finished jobs.
     */
    private long finishedJobs;

    /**
     * The pictures that left the decoded picture buffer while jobs that may read them were still
     * running; each returns to the pool once those jobs finished.
     */
    private final List<HevcFrame> pendingRelease = new ArrayList<>();

    /**
     * The coding information buffers of finished pictures, kept for reuse.
     */
    private final List<PictureState> statePool = new ArrayList<>();

    /**
     * The supplemental enhancement information collected so far.
     */
    private final HevcSei sei = new HevcSei();

    /**
     * The reusable bit reader for parameter sets and slice headers.
     */
    private final H264BitReader reader = new H264BitReader();

    /**
     * The buffer receiving the unescaped payload of the current NAL unit.
     */
    private byte[] rbsp = new byte[4096];

    /**
     * The escaped offsets of the emulation prevention bytes of the current NAL unit.
     */
    private int[] removed = new int[1366];

    /**
     * Whether decoded picture hash SEI messages are checked.
     */
    private final boolean checkHashes;

    /**
     * The picture being decoded, or {@code null} between pictures.
     */
    private HevcFrame current;

    /**
     * The header of the last independent slice segment of the current picture.
     */
    private HevcSliceHeader independent;

    /**
     * The sequence parameter set of the coded video sequence.
     */
    private HevcSps activeSps;

    /**
     * Whether the next picture is the first of the bitstream.
     */
    private boolean firstPicture = true;

    /**
     * Whether an end of sequence NAL unit preceded the next picture.
     */
    private boolean afterEndOfSequence;

    /**
     * Whether decoding has reached an IRAP picture yet.
     */
    private boolean started;

    /**
     * {@code NoRaslOutputFlag} of the last IRAP picture.
     */
    private boolean irapNoRaslOutput;

    /**
     * Whether the slices of the current picture are being skipped.
     */
    private boolean skipping;

    /**
     * The picture order count of {@code prevTid0Pic}.
     */
    private int prevTid0Poc;

    /**
     * {@code PicOutputFlag} of the current picture.
     */
    private boolean picOutput;

    /**
     * The decoded picture hash received for the current picture, as {@link HevcSei#hashType}, or
     * -1.
     */
    private int pendingHash = -1;

    /**
     * The counter that assigns {@link HevcFrame#id}.
     */
    private int nextId;

    /**
     * The timestamp of the packet being decoded.
     */
    private long packetPts;

    /**
     * The duration of the packet being decoded.
     */
    private long packetDuration;

    /**
     * The reference picture lists of the current slice, indexed {@code [list][refIdx]}.
     */
    private final HevcFrame[][] refList = new HevcFrame[2][16];

    /**
     * The RPS lists of the current picture: {@code RefPicSetStCurrBefore},
     * {@code RefPicSetStCurrAfter} and {@code RefPicSetLtCurr}.
     */
    private final HevcFrame[][] rps = new HevcFrame[3][16];

    /**
     * The sizes of {@link #rps}.
     */
    private final int[] rpsCount = new int[3];

    /**
     * Constructs an engine.
     *
     * @implNote This implementation starts no threads of its own: with several threads, each
     * picture that has received all its slices becomes a computation of
     * {@link ParallelBands}, which the shared helpers run, or the calling thread when it
     * has to wait for it and no helper has claimed it. {@code threads} bounds the
     * pictures in flight.
     * @param limits the resource limits
     * @param checkHashes whether to check decoded picture hash SEI messages
     * @param threads the most pictures decoded concurrently; 1 decodes on the calling thread
     * @param output receives the output pictures; a picture's samples may be reused once the
     * consumer returns
     */
    public HevcDecoderEngine(MediaLimits limits, boolean checkHashes, int threads, Consumer<HevcFrame> output) {
        this.limits = limits;
        this.checkHashes = checkHashes;
        this.output = output;
        this.parallel = threads > 1 && ParallelBands.enabled();
        this.threads = parallel ? threads : 1;
    }

    /**
     * Records the timing of the packet whose NAL units follow.
     *
     * @param pts the presentation timestamp
     * @param duration the duration of the pictures of the packet, in track time base units
     */
    public void beginPacket(long pts, long duration) {
        this.packetPts = pts;
        this.packetDuration = duration;
    }

    /**
     * Decodes one NAL unit.
     *
     * @param data the buffer holding the NAL unit, without start code or length prefix
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length in bytes
     * @throws MediaException.Malformed if the NAL unit is corrupt
     * @throws MediaException.Unsupported if the stream uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the pictures exceed the limits
     */
    public void decodeNal(byte[] data, int offset, int length) {
        if (length < 2) {
            return;
        }
        var header = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        if ((header & 0x8000) != 0) {
            throw new MediaException.Malformed("forbidden_zero_bit set");
        }
        var type = (header >> 9) & 0x3F;
        var layer = (header >> 3) & 0x3F;
        var temporalIdPlus1 = header & 7;
        if (temporalIdPlus1 == 0) {
            throw new MediaException.Malformed("nuh_temporal_id_plus1 is 0");
        }
        if (layer != 0) {
            return;
        }
        if (HevcNal.startsAccessUnit(type)) {
            finishPictureDeferred();
        }
        if (!HevcNal.isSlice(type) && type > HevcNal.SUFFIX_SEI || HevcNal.isReservedVcl(type) || type == HevcNal.FD) {
            throwDeferred();
            return;
        }
        decodeRbsp(type, temporalIdPlus1 - 1, data, offset, length);
        throwDeferred();
    }

    /**
     * Finishes the current picture, keeping a failure of its slices for {@link #throwDeferred()}
     * so that the NAL unit that ended the picture is still decoded.
     */
    private void finishPictureDeferred() {
        try {
            finishPicture();
        } catch (MediaException failure) {
            if (deferred == null) {
                deferred = failure;
            }
        }
    }

    /**
     * Throws the failure {@link #finishPictureDeferred()} kept, if any.
     *
     * @throws MediaException the kept failure
     */
    private void throwDeferred() {
        var failure = deferred;
        if (failure != null) {
            deferred = null;
            throw failure;
        }
    }

    /**
     * The failure of a picture finished by the NAL unit being decoded, thrown once that NAL unit is
     * processed.
     */
    private MediaException deferred;

    /**
     * Decodes the payload of a NAL unit whose header was checked.
     *
     * @param type the NAL unit type
     * @param temporalId the temporal identifier
     * @param data the buffer holding the NAL unit
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length in bytes
     */
    private void decodeRbsp(int type, int temporalId, byte[] data, int offset, int length) {
        var size = unescape(data, offset + 2, length - 2);
        reader.init(rbsp, 0, size);
        switch (type) {
            case HevcNal.VPS, HevcNal.AUD -> {
            }
            case HevcNal.SPS -> {
                var sps = HevcSps.parse(reader);
                spsTable[sps.id] = sps;
            }
            case HevcNal.PPS -> {
                var pps = HevcPps.parse(reader);
                ppsTable[pps.id] = pps;
            }
            case HevcNal.EOS -> afterEndOfSequence = true;
            case HevcNal.EOB -> {
                bumpAll();
                firstPicture = true;
                afterEndOfSequence = true;
            }
            case HevcNal.PREFIX_SEI, HevcNal.SUFFIX_SEI -> {
                var components = activeSps == null || activeSps.chromaFormatIdc == 0 ? 1 : 3;
                sei.parse(rbsp, 0, size, type == HevcNal.SUFFIX_SEI, components);
                if (sei.hashType >= 0) {
                    pendingHash = current != null ? sei.hashType : -1;
                    if (current == null) {
                        sei.hashType = -1;
                    }
                }
            }
            default -> slice(type, temporalId, size);
        }
    }

    /**
     * Copies a NAL unit payload into {@link #rbsp} without its emulation prevention bytes.
     *
     * @param data the buffer
     * @param offset the index of the first payload byte
     * @param length the payload length
     * @return the unescaped length
     */
    private int unescape(byte[] data, int offset, int length) {
        if (rbsp.length < length + H264BitReader.PADDING) {
            rbsp = new byte[length + H264BitReader.PADDING + (length >> 2)];
        }
        if (removed.length < length / 3 + 1) {
            removed = new int[length / 3 + 1];
        }
        var result = NalUnits.unescape(data, offset, length, rbsp, removed);
        var size = (int) result;
        Arrays.fill(rbsp, size, size + H264BitReader.PADDING, (byte) 0);
        removedCount = (int) (result >>> 32);
        return size;
    }

    /**
     * The number of emulation prevention bytes removed from the current NAL unit.
     */
    private int removedCount;

    /**
     * Decodes a slice segment NAL unit.
     *
     * @param type the NAL unit type
     * @param temporalId the temporal identifier
     * @param size the unescaped payload size in {@link #rbsp}
     */
    private void slice(int type, int temporalId, int size) {
        var header = HevcSliceHeader.parse(reader, type, temporalId, ppsTable, spsTable,
                independent != null && current != null ? independent : null);
        if (header.firstSliceSegmentInPic) {
            finishPictureDeferred();
            startPicture(header);
        } else if (current == null || skipping) {
            return;
        }
        if (skipping) {
            return;
        }
        if (header.sps != current.sps && !header.sps.sameLayout(current.sps)) {
            throw new MediaException.Malformed("slice of a picture refers to another sequence parameter set");
        }
        if (!header.dependentSliceSegment) {
            independent = header;
        }
        activate(header.pps, header.sps);
        var unsupported = header.pps.unsupportedFeature();
        if (unsupported != null) {
            throw new MediaException.Unsupported(unsupported, "the stream uses " + unsupported);
        }
        HevcFrame[][] lists = null;
        if (!header.isIntra()) {
            buildRefLists(header);
            lists = refList;
        }
        var sliceIndex = recordSlice(header);
        if (lists != null) {
            lists = new HevcFrame[][]{lists[0].clone(), lists[1].clone()};
        }
        currentJob.add(new PictureJob.Slice(header, lists, sliceIndex, Arrays.copyOf(rbsp, size + H264BitReader.PADDING),
                size, parallel ? substreams(header, size) : null));
    }

    /**
     * Returns the start of each substream of a slice segment in the unescaped payload, when its
     * substreams can be decoded concurrently: the rows of wavefront parallel processing without
     * tiles in a slice segment that starts at the beginning of a row, or the tiles of a slice
     * segment without wavefronts that starts at the beginning of a tile, with entry points that
     * stay inside the payload.
     *
     * @param header the slice segment header
     * @param size the unescaped payload size
     * @return the substream starts, the first being the start of the slice segment data, or
     * {@code null} to decode the slice segment on one thread
     */
    private int[] substreams(HevcSliceHeader header, int size) {
        var pps = header.pps;
        var widthCtbs = header.sps.widthInCtbs;
        if (header.numEntryPoints == 0 || pps.entropyCodingSync == pps.tilesEnabled) {
            return null;
        }
        if (pps.entropyCodingSync && header.segmentAddress % widthCtbs != 0) {
            return null;
        }
        if (pps.tilesEnabled) {
            var ts = pps.ctbAddrRsToTs[header.segmentAddress];
            var tile = pps.tileId[ts];
            if (pps.tileStartTs[tile] != ts || tile + header.numEntryPoints >= pps.tileStartTs.length - 1) {
                return null;
            }
        }
        var escaped = header.dataOffset;
        var skipped = 0;
        while (skipped < removedCount && removed[skipped] <= escaped) {
            escaped++;
            skipped++;
        }
        var starts = new int[header.numEntryPoints + 1];
        starts[0] = header.dataOffset;
        long position = escaped;
        for (var k = 1; k < starts.length; k++) {
            position += header.entryPointOffsets[k - 1];
            while (skipped < removedCount && removed[skipped] < position) {
                skipped++;
            }
            var start = position - skipped;
            if (start >= size || start <= starts[k - 1]) {
                return null;
            }
            starts[k] = (int) start;
        }
        return starts;
    }

    /**
     * Activates a picture parameter set for a sequence parameter set, first waiting for the
     * pictures in flight when it was active for another one, since activation replaces the tile
     * tables those pictures read.
     *
     * @param pps the picture parameter set
     * @param sps the sequence parameter set
     */
    private void activate(HevcPps pps, HevcSps sps) {
        if (parallel && !pps.isActiveFor(sps)) {
            awaitAllJobs();
        }
        pps.activate(sps);
    }

    /**
     * Records the reference lists of a slice in the current picture for the temporal motion vector
     * prediction of later pictures and for deblocking.
     *
     * @param header the slice segment header
     * @return the slice index
     */
    private int recordSlice(HevcSliceHeader header) {
        var poc = new int[32];
        var longTerm = new boolean[32];
        var ids = new int[32];
        Arrays.fill(ids, -1);
        if (!header.isIntra()) {
            for (var list = 0; list < 2; list++) {
                for (var i = 0; i < header.numRefIdx[list]; i++) {
                    var ref = refList[list][i];
                    poc[list * 16 + i] = ref.poc;
                    longTerm[list * 16 + i] = ref.longTerm;
                    ids[list * 16 + i] = ref.id;
                }
            }
        }
        return current.addSlice(poc, longTerm, ids);
    }

    /**
     * Starts decoding a picture: activates its parameter sets, derives its picture order count,
     * applies its reference picture set and outputs the pictures the decoded picture buffer no
     * longer holds back (C.5.2.2).
     *
     * @param header the header of the picture's first slice segment
     */
    private void startPicture(HevcSliceHeader header) {
        var type = header.nalType;
        var sps = header.sps;
        var irap = HevcNal.isIrap(type);
        var noRaslOutput = false;
        if (irap) {
            noRaslOutput = HevcNal.isIdr(type) || HevcNal.isBla(type) || firstPicture || afterEndOfSequence;
        }
        skipping = false;
        if (!started && !irap) {
            skipping = true;
            return;
        }
        if (HevcNal.isRasl(type) && irapNoRaslOutput) {
            skipping = true;
            return;
        }
        var unsupported = sps.unsupportedFeature();
        if (unsupported != null) {
            throw new MediaException.Unsupported(unsupported, "the stream uses " + unsupported);
        }
        LimitChecks.checkPicture(limits, sps.width, sps.height);
        activate(header.pps, sps);
        if (irap) {
            started = true;
            irapNoRaslOutput = noRaslOutput;
        }
        if (activeSps != sps) {
            if (activeSps != null && !(irap && noRaslOutput) && !sps.sameLayout(activeSps)) {
                throw new MediaException.Malformed("sequence parameter set changed inside a coded video sequence");
            }
            activeSps = sps;
        }
        var maxPocLsb = 1 << sps.log2MaxPocLsb;
        int poc;
        if (irap && noRaslOutput) {
            poc = header.pocLsb;
        } else {
            var prevLsb = prevTid0Poc & (maxPocLsb - 1);
            var prevMsb = prevTid0Poc - prevLsb;
            int msb;
            if (header.pocLsb < prevLsb && prevLsb - header.pocLsb >= maxPocLsb / 2) {
                msb = prevMsb + maxPocLsb;
            } else if (header.pocLsb > prevLsb && header.pocLsb - prevLsb > maxPocLsb / 2) {
                msb = prevMsb - maxPocLsb;
            } else {
                msb = prevMsb;
            }
            poc = msb + header.pocLsb;
        }
        if (header.temporalId == 0 && !HevcNal.isRasl(type) && !HevcNal.isRadl(type)
                && !HevcNal.isSubLayerNonReference(type)) {
            prevTid0Poc = poc;
        }
        applyReferencePictureSet(header, poc, irap && noRaslOutput);
        var highest = sps.maxSubLayers - 1;
        if (irap && noRaslOutput && !firstPicture) {
            var noOutputOfPriorPics = type == HevcNal.CRA_NUT || header.noOutputOfPriorPics;
            if (noOutputOfPriorPics) {
                for (var frame : dpb) {
                    release(frame);
                }
                dpb.clear();
            } else {
                bumpAll();
            }
        } else {
            removeUnused();
            while (needsBumping(sps, highest) || dpb.size() >= sps.maxDecPicBuffering[highest]) {
                if (!bump()) {
                    break;
                }
            }
        }
        firstPicture = false;
        afterEndOfSequence = false;
        picOutput = header.picOutput;
        var frame = obtainFrame(sps);
        frame.reset(sps.sizeInCtbs);
        frame.id = nextId++;
        frame.poc = poc;
        frame.irap = irap;
        frame.sps = sps;
        frame.pts = packetPts;
        frame.duration = packetDuration;
        frame.color = colorInfo(sps);
        current = frame;
        var state = obtainState(sps);
        state.reset();
        frame.beginDecoding();
        currentJob = new PictureJob(frame, state, ++submittedJobs, idleDecoders);
        independent = null;
        pendingHash = -1;
        sei.hashType = -1;
        if (dpb.size() >= MAX_DPB) {
            throw new MediaException.Malformed("decoded picture buffer overflow");
        }
    }

    /**
     * Applies the reference picture set of the current picture (8.3.2): marks the long-term and
     * unused pictures and fills {@link #rps}, generating stand-in pictures for missing references
     * the current picture uses.
     *
     * @param header the slice segment header
     * @param poc {@code PicOrderCntVal} of the current picture
     * @param reset whether the picture is an IRAP picture with {@code NoRaslOutputFlag} set
     */
    private void applyReferencePictureSet(HevcSliceHeader header, int poc, boolean reset) {
        if (reset) {
            for (var frame : dpb) {
                frame.shortTerm = false;
                frame.longTerm = false;
            }
        }
        Arrays.fill(rpsCount, 0);
        if (HevcNal.isIdr(header.nalType)) {
            return;
        }
        var sps = header.sps;
        var maxPocLsb = 1 << sps.log2MaxPocLsb;
        var keep = new ArrayList<HevcFrame>();
        var ltCurr = new int[32];
        var ltCurrMsb = new boolean[32];
        var ltFoll = new int[32];
        var ltFollMsb = new boolean[32];
        var nLtCurr = 0;
        var nLtFoll = 0;
        for (var i = 0; i < header.numLongTerm; i++) {
            var pocLt = header.pocLsbLt[i];
            if (header.deltaPocMsbPresent[i]) {
                pocLt += poc - header.deltaPocMsbCycleLt[i] * maxPocLsb - (poc & (maxPocLsb - 1));
            }
            if (header.usedByCurrPicLt[i]) {
                ltCurr[nLtCurr] = pocLt;
                ltCurrMsb[nLtCurr++] = header.deltaPocMsbPresent[i];
            } else {
                ltFoll[nLtFoll] = pocLt;
                ltFollMsb[nLtFoll++] = header.deltaPocMsbPresent[i];
            }
        }
        var ltCurrFrames = new HevcFrame[nLtCurr];
        for (var i = 0; i < nLtCurr; i++) {
            ltCurrFrames[i] = findLongTerm(ltCurr[i], ltCurrMsb[i], maxPocLsb);
        }
        var ltFollFrames = new HevcFrame[nLtFoll];
        for (var i = 0; i < nLtFoll; i++) {
            ltFollFrames[i] = findLongTerm(ltFoll[i], ltFollMsb[i], maxPocLsb);
        }
        for (var frame : ltCurrFrames) {
            if (frame != null) {
                frame.longTerm = true;
                frame.shortTerm = false;
                keep.add(frame);
            }
        }
        for (var frame : ltFollFrames) {
            if (frame != null) {
                frame.longTerm = true;
                frame.shortTerm = false;
                keep.add(frame);
            }
        }
        var st = header.shortTermRps;
        for (var i = 0; i < st.numNegative; i++) {
            var frame = findShortTerm(poc + st.deltaS0[i]);
            if (frame != null) {
                keep.add(frame);
            }
            if (st.usedS0[i]) {
                rps[0][rpsCount[0]++] = frame != null ? frame : generate(sps, poc + st.deltaS0[i], keep);
            }
        }
        for (var i = 0; i < st.numPositive; i++) {
            var frame = findShortTerm(poc + st.deltaS1[i]);
            if (frame != null) {
                keep.add(frame);
            }
            if (st.usedS1[i]) {
                rps[1][rpsCount[1]++] = frame != null ? frame : generate(sps, poc + st.deltaS1[i], keep);
            }
        }
        for (var i = 0; i < nLtCurr; i++) {
            var frame = ltCurrFrames[i];
            if (frame == null) {
                frame = generate(sps, ltCurr[i], keep);
                frame.longTerm = true;
                frame.shortTerm = false;
            }
            rps[2][rpsCount[2]++] = frame;
        }
        for (var frame : dpb) {
            if (frame.isReference() && !keep.contains(frame)) {
                frame.shortTerm = false;
                frame.longTerm = false;
            }
        }
    }

    /**
     * Finds a reference picture for a long-term entry.
     *
     * @param pocLt the entry's picture order count, or its least significant bits
     * @param msb whether the entry carries the most significant bits
     * @param maxPocLsb {@code MaxPicOrderCntLsb}
     * @return the picture, or {@code null}
     */
    private HevcFrame findLongTerm(int pocLt, boolean msb, int maxPocLsb) {
        for (var frame : dpb) {
            if (frame.isReference() && (msb ? frame.poc == pocLt : (frame.poc & (maxPocLsb - 1)) == pocLt)) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Finds a short-term reference picture.
     *
     * @param poc the picture order count
     * @return the picture, or {@code null}
     */
    private HevcFrame findShortTerm(int poc) {
        for (var frame : dpb) {
            if (frame.shortTerm && frame.poc == poc) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Generates a stand-in for a missing reference picture (8.3.3.2): mid-gray samples, intra
     * motion, not output.
     *
     * @param sps the active sequence parameter set
     * @param poc the picture order count of the missing picture
     * @param keep the pictures the reference picture set keeps, which receives the stand-in
     * @return the stand-in picture
     */
    private HevcFrame generate(HevcSps sps, int poc, List<HevcFrame> keep) {
        if (dpb.size() >= MAX_DPB) {
            throw new MediaException.Malformed("too many missing reference pictures");
        }
        var frame = obtainFrame(sps);
        frame.reset(sps.sizeInCtbs);
        Arrays.fill(frame.ctbSlice, -1);
        frame.id = nextId++;
        frame.poc = poc;
        frame.sps = sps;
        frame.generated = true;
        frame.damaged = true;
        frame.shortTerm = true;
        if (frame.eightBit()) {
            Arrays.fill(frame.y8, (byte) 128);
            if (frame.cb8 != null) {
                Arrays.fill(frame.cb8, (byte) 128);
                Arrays.fill(frame.cr8, (byte) 128);
            }
        } else {
            Arrays.fill(frame.y, (short) (1 << (sps.bitDepthLuma - 1)));
            if (frame.cb != null) {
                Arrays.fill(frame.cb, (short) (1 << (sps.bitDepthChroma - 1)));
                Arrays.fill(frame.cr, (short) (1 << (sps.bitDepthChroma - 1)));
            }
        }
        dpb.add(frame);
        keep.add(frame);
        return frame;
    }

    /**
     * Builds the reference picture lists of a P or B slice (8.3.4).
     *
     * @param header the slice segment header
     * @throws MediaException.Malformed if the picture has no reference pictures
     */
    private void buildRefLists(HevcSliceHeader header) {
        var total = rpsCount[0] + rpsCount[1] + rpsCount[2];
        if (total == 0) {
            throw new MediaException.Malformed("inter slice without reference pictures");
        }
        for (var list = 0; list < (header.sliceType == HevcSliceHeader.B ? 2 : 1); list++) {
            var count = Math.max(header.numRefIdx[list], total);
            var temp = new HevcFrame[count];
            var first = list == 0 ? 0 : 1;
            var second = list == 0 ? 1 : 0;
            var r = 0;
            while (r < count) {
                for (var i = 0; i < rpsCount[first] && r < count; i++) {
                    temp[r++] = rps[first][i];
                }
                for (var i = 0; i < rpsCount[second] && r < count; i++) {
                    temp[r++] = rps[second][i];
                }
                for (var i = 0; i < rpsCount[2] && r < count; i++) {
                    temp[r++] = rps[2][i];
                }
            }
            for (var i = 0; i < header.numRefIdx[list]; i++) {
                refList[list][i] = header.listModification[list] ? temp[header.listEntry[list][i]] : temp[i];
            }
        }
    }

    /**
     * Returns a picture buffer for a sequence parameter set, reusing a pooled one when it fits.
     *
     * @param sps the sequence parameter set
     * @return the picture
     */
    private HevcFrame obtainFrame(HevcSps sps) {
        for (var i = pool.size() - 1; i >= 0; i--) {
            var frame = pool.remove(i);
            if (frame.fits(sps)) {
                return frame;
            }
        }
        LimitChecks.checkAllocation(limits, (long) sps.width * sps.height * 2 * 2);
        return new HevcFrame(sps.width, sps.height, sps.chromaFormatIdc, HevcFrame.eightBit(sps));
    }

    /**
     * Returns a picture to the pool.
     *
     * @param frame the picture
     */
    private void release(HevcFrame frame) {
        if (parallel) {
            frame.releaseAfter = submittedJobs;
            pendingRelease.add(frame);
            return;
        }
        pool(frame);
    }

    /**
     * The pictures bumped out of the decoded picture buffer whose output waits for their decoding,
     * in output order.
     */
    private final ArrayDeque<HevcFrame> outputQueue = new ArrayDeque<>();

    /**
     * Keeps a picture no job reads any more for reuse, up to the most pictures the engine holds at
     * once: a full decoded picture buffer plus, with several threads, the pictures in flight and
     * those waiting for output.
     *
     * @param frame the picture
     */
    private void pool(HevcFrame frame) {
        if (pool.size() < MAX_DPB + 2 * threads) {
            pool.add(frame);
        }
    }

    /**
     * Returns a coding information buffer for a sequence parameter set, reusing a pooled one when
     * it fits.
     *
     * @param sps the sequence parameter set
     * @return the buffer
     */
    private PictureState obtainState(HevcSps sps) {
        for (var i = statePool.size() - 1; i >= 0; i--) {
            var state = statePool.remove(i);
            if (state.sps == sps) {
                return state;
            }
        }
        return new PictureState(sps);
    }

    /**
     * Waits for the oldest job, decoding its picture on the calling thread when no other thread
     * has claimed it, and retires the finished jobs.
     *
     * <p>Jobs are started in decoding order and waited for oldest first, so a job run here only
     * waits for older jobs that have finished or are running on other threads.
     */
    private void joinOldest() {
        inFlight.peekFirst().join();
        retireFinishedJobs();
    }

    /**
     * Waits until a picture is decoded, joining the jobs in flight oldest first until it is.
     *
     * @param frame the picture
     */
    private void awaitFrame(HevcFrame frame) {
        while (!frame.isDone() && !inFlight.isEmpty()) {
            joinOldest();
        }
        frame.awaitDone();
    }

    /**
     * Waits for every started job to finish, oldest first.
     */
    private void awaitAllJobs() {
        while (!inFlight.isEmpty()) {
            joinOldest();
        }
    }

    /**
     * Forgets the leading finished jobs, keeping the first failure among them for
     * {@link #throwDeferred()}, and returns the pictures no unfinished job reads any more to the
     * pool.
     */
    private void retireFinishedJobs() {
        while (!inFlight.isEmpty() && inFlight.peekFirst().frame.isDone()) {
            var job = inFlight.pollFirst();
            finishedJobs = job.sequence;
            statePool.add(job.pic);
            var failure = job.error();
            if (failure != null && deferred == null) {
                deferred = failure;
            }
        }
        for (var i = pendingRelease.size() - 1; i >= 0; i--) {
            var frame = pendingRelease.get(i);
            if (frame.releaseAfter <= finishedJobs && !frame.awaitingOutput) {
                pendingRelease.remove(i);
                pool(frame);
            }
        }
    }

    /**
     * Starts the job of the picture that has received all its slices, then decodes on the
     * calling thread the oldest started pictures that no helper is free to claim.
     *
     * @param job the decoding of the next picture
     * @implNote This implementation asks {@link ParallelBands#idle()}, not
     * {@link ParallelBands#available()}: a picture has to be decoded anyway, so only a
     * busy set of helpers, not the load of other operations, is a reason for the caller
     * to stop parsing ahead and decode it itself.
     */
    private void startJob(PictureJob job) {
        while (inFlight.size() >= threads) {
            joinOldest();
        }
        inFlight.addLast(job);
        job.start();
        while (!inFlight.isEmpty() && ParallelBands.idle() == 0 && !inFlight.peekFirst().claimed()) {
            joinOldest();
        }
        retireFinishedJobs();
    }

    /**
     * Waits for the pictures in flight, dropping the started pictures no thread has claimed when
     * no claimed picture can read them; afterwards no thread runs work of this engine.
     */
    public void close() {
        currentJob = null;
        current = null;
        while (!inFlight.isEmpty() && inFlight.peekLast().cancel()) {
            inFlight.pollLast();
        }
        while (!inFlight.isEmpty()) {
            inFlight.pollFirst().join();
        }
    }

    /**
     * Removes the pictures that are neither needed for output nor used for reference.
     */
    private void removeUnused() {
        for (var i = dpb.size() - 1; i >= 0; i--) {
            var frame = dpb.get(i);
            if (!frame.neededForOutput && !frame.isReference()) {
                dpb.remove(i);
                release(frame);
            }
        }
    }

    /**
     * Returns whether the reordering or latency limits require a bumping step.
     *
     * @param sps the active sequence parameter set
     * @param highest {@code HighestTid}
     * @return {@code true} when a picture must be output
     */
    private boolean needsBumping(HevcSps sps, int highest) {
        var waiting = 0;
        var latencyLimit = sps.maxLatencyIncreasePlus1[highest] != 0;
        var maxLatency = sps.maxNumReorder[highest] + sps.maxLatencyIncreasePlus1[highest] - 1;
        var late = false;
        for (var frame : dpb) {
            if (frame.neededForOutput) {
                waiting++;
                if (latencyLimit && frame.latencyCount >= maxLatency) {
                    late = true;
                }
            }
        }
        return waiting > sps.maxNumReorder[highest] || late;
    }

    /**
     * Outputs the picture with the smallest picture order count among those needed for output
     * (C.5.2.4).
     *
     * @return whether a picture was output
     */
    private boolean bump() {
        HevcFrame first = null;
        for (var frame : dpb) {
            if (frame.neededForOutput && (first == null || frame.poc < first.poc)) {
                first = frame;
            }
        }
        if (first == null) {
            return false;
        }
        first.neededForOutput = false;
        if (parallel) {
            first.awaitingOutput = true;
            outputQueue.addLast(first);
            if (!first.isReference()) {
                dpb.remove(first);
                release(first);
            }
            drainOutput(false);
            return true;
        }
        if (!first.isReference()) {
            dpb.remove(first);
            release(first);
        }
        output.accept(first);
        return true;
    }

    /**
     * Hands the pictures bumped out of the decoded picture buffer to the consumer, in output order,
     * as their decoding finishes.
     *
     * @param wait whether to wait for every queued picture; otherwise the call waits only while
     * more pictures are queued than threads decode, and returns at the first picture
     * still being decoded
     */
    private void drainOutput(boolean wait) {
        while (!outputQueue.isEmpty()) {
            var frame = outputQueue.peekFirst();
            if (!frame.isDone()) {
                if (!wait && outputQueue.size() <= threads) {
                    return;
                }
                awaitFrame(frame);
            }
            outputQueue.pollFirst();
            frame.awaitingOutput = false;
            output.accept(frame);
        }
        retireFinishedJobs();
    }

    /**
     * Outputs every picture still needed for output and empties the decoded picture buffer of the
     * pictures not used for reference.
     */
    private void bumpAll() {
        while (bump()) {
            // Outputs pictures in order until none is waiting.
        }
        removeUnused();
    }

    /**
     * Finishes the current picture: applies the in-loop filters, checks its decoded picture hash,
     * marks it as a short-term reference and outputs the pictures the reordering limits release
     * (C.5.2.3).
     *
     * @throws MediaException.Malformed if a slice of the picture was corrupt or its hash mismatched
     */
    public void finishPicture() {
        var frame = current;
        if (frame == null) {
            return;
        }
        current = null;
        independent = null;
        var sps = frame.sps;
        var job = currentJob;
        currentJob = null;
        if (checkHashes && pendingHash >= 0) {
            job.hashType = pendingHash;
            job.hash = new byte[][]{sei.hash[0].clone(), sei.hash[1].clone(), sei.hash[2].clone()};
        }
        MediaException failure = null;
        if (parallel) {
            startJob(job);
        } else {
            job.run();
            failure = job.error();
            statePool.add(job.pic);
        }
        pendingHash = -1;
        sei.hashType = -1;
        frame.shortTerm = true;
        frame.longTerm = false;
        var highest = sps.maxSubLayers - 1;
        if (picOutput) {
            for (var other : dpb) {
                if (other.neededForOutput && other.poc > frame.poc) {
                    other.latencyCount++;
                }
            }
            frame.neededForOutput = true;
            frame.latencyCount = 0;
        }
        dpb.add(frame);
        while (needsBumping(sps, highest)) {
            if (!bump()) {
                break;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Finishes the current picture at the end of an access unit, for packets that each hold one
     * whole access unit.
     */
    public void endAccessUnit() {
        finishPicture();
        throwDeferred();
    }

    /**
     * Finishes the current picture and outputs every picture still waiting.
     */
    public void flush() {
        try {
            finishPicture();
        } finally {
            awaitAllJobs();
            bumpAll();
            drainOutput(true);
        }
        throwDeferred();
    }

    /**
     * Returns the color description of the pictures of a sequence, with the high-dynamic-range
     * metadata of the last SEI messages that carried it.
     *
     * <p>Components the VUI leaves unspecified default to BT.709 for pictures wider than 1024 or
     * taller than 576 samples and to BT.601 otherwise; a preferred transfer characteristic of an
     * alternative transfer characteristics SEI message replaces the VUI's.
     *
     * @param sps the sequence parameter set
     * @return the color description
     */
    private ColorInfo colorInfo(HevcSps sps) {
        var width = sps.width - sps.cropLeft - sps.cropRight;
        var height = sps.height - sps.cropTop - sps.cropBottom;
        var fallback = width > 1024 || height > 576 ? ColorInfo.BT709_LIMITED : ColorInfo.BT601_LIMITED;
        var matrix = fallback.matrix();
        var primaries = fallback.primaries();
        var transfer = fallback.transfer();
        var signaledMatrix = MatrixCoefficients.of(sps.matrixCoefficients);
        if (signaledMatrix != MatrixCoefficients.UNSPECIFIED) {
            matrix = signaledMatrix;
        }
        var signaledPrimaries = ColorPrimaries.of(sps.colorPrimaries);
        if (signaledPrimaries != ColorPrimaries.UNSPECIFIED) {
            primaries = signaledPrimaries;
        }
        var signaledTransfer = TransferCharacteristics.of(sps.transferCharacteristics);
        if (signaledTransfer != TransferCharacteristics.UNSPECIFIED) {
            transfer = signaledTransfer;
        }
        if (sei.preferredTransfer >= 0) {
            var preferred = TransferCharacteristics.of(sei.preferredTransfer);
            if (preferred != TransferCharacteristics.UNSPECIFIED) {
                transfer = preferred;
            }
        }
        var info = new ColorInfo(matrix, sps.fullRange ? ColorRange.FULL : ColorRange.LIMITED, primaries, transfer);
        if (sps.chromaSampleLocType >= 0) {
            info = info.withChromaSiting(ChromaSiting.of(sps.chromaSampleLocType));
        }
        if (sei.masteringDisplay != null) {
            info = info.withMasteringDisplay(sei.masteringDisplay);
        }
        if (sei.contentLightLevel != null) {
            info = info.withContentLightLevel(sei.contentLightLevel);
        }
        return info;
    }

    /**
     * Returns the sequence parameter set that was active for the last picture, or {@code null}.
     *
     * @return the sequence parameter set
     */
    public HevcSps activeSps() {
        return activeSps;
    }
}
