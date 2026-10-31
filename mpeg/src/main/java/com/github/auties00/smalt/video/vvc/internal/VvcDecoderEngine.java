/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/DecLib.cpp and source/Lib/CommonLib/Slice.cpp: picture order counts,
 * reference picture lists and the decoded picture buffer). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft zur
 * Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Decodes the NAL units of a VVC stream into pictures in output order (ITU-T H.266 clause 8 and C.5.2): parameter
 * set handling, picture order counts, reference picture lists and marking, the generation of unavailable
 * references, the slice decoding, the in-loop filters and the output order decoded picture buffer.
 *
 * <p>Only the base layer ({@code nuh_layer_id} 0) is decoded. Instances are not thread-safe.
 */
public final class VvcDecoderEngine {
    // TODO: decode independent pictures in parallel (a slice of a single substream, as most encoders write without
    // tiles or wavefront rows, decodes on the calling thread) and vectorize the intra prediction, DMVR and
    // residual loops, which have no Vector API kernels yet
    /**
     * The sequence parameter sets received, by {@code sps_seq_parameter_set_id}.
     */
    private final VvcSps[] spsTable = new VvcSps[16];
    /**
     * The unescaped payload of every sequence parameter set in {@link #spsTable}, by which a repetition is told apart
     * from a new set.
     */
    private final byte[][] spsRaw = new byte[16][];
    /**
     * The picture parameter sets received, by {@code pps_pic_parameter_set_id}; {@code null} for one whose sequence
     * parameter set has not arrived.
     */
    private final VvcPps[] ppsTable = new VvcPps[64];
    /**
     * The unescaped payload of every picture parameter set, parsed again when its sequence parameter set changes.
     */
    private final byte[][] ppsRaw = new byte[64][];
    /**
     * The adaptive loop filter sets received, by {@code aps_adaptation_parameter_set_id}.
     */
    private final VvcAps[] alfAps = new VvcAps[8];
    /**
     * The luma mapping with chroma scaling sets received, by {@code aps_adaptation_parameter_set_id}.
     */
    private final VvcAps[] lmcsAps = new VvcAps[4];
    /**
     * The scaling list sets received, by {@code aps_adaptation_parameter_set_id}.
     */
    private final VvcAps[] scalingAps = new VvcAps[8];
    /**
     * The consumer of the pictures in output order.
     */
    private final Consumer<VvcPicture> output;
    /**
     * The resource bounds a picture must fit.
     */
    private final MediaLimits limits;
    /**
     * Whether a picture that does not match its decoded picture hash raises an error.
     */
    private final boolean checkHashes;
    /**
     * The reader of the NAL unit payloads.
     */
    private final H264BitReader reader = new H264BitReader();
    /**
     * The unescaped payload of the NAL unit being decoded, followed by the reader's padding.
     */
    private byte[] rbsp = new byte[1024];
    /**
     * The positions of the emulation prevention bytes removed from {@link #rbsp}, which the entry points are corrected
     * by.
     */
    private int[] removed = new int[512];

    /**
     * The decoded picture buffer.
     */
    private final List<VvcPicture> dpb = new ArrayList<>();
    /**
     * The pictures released from the buffer, recycled for later pictures.
     */
    private final List<VvcPicture> pool = new ArrayList<>();
    /**
     * The per picture maps of the picture size being decoded.
     */
    private VvcFrame frame;
    /**
     * The reconstruction of the picture being decoded.
     */
    private VvcRecon recon;
    /**
     * The motion derivation of the picture being decoded.
     */
    private VvcMotion motion;
    /**
     * The slice data decoder of the picture being decoded.
     */
    private VvcSliceDecoder sliceDecoder;

    /**
     * The most substreams of a slice decoded in one batch, the most stages of a {@link ParallelBands#pipeline}.
     */
    private static final int MAX_STAGES = 31;

    /**
     * The thread count the substreams of a slice and the in-loop filters may use: 0 for the shared pool, 1 for the
     * calling thread only, or a larger bound.
     */
    private final int threads;

    /**
     * Every slice decoder of the current frame size, {@link #sliceDecoder} first, each with its own reconstruction,
     * motion derivation and lane of coding units.
     */
    private final List<VvcSliceDecoder> lanes = new ArrayList<>();

    /**
     * The slice decoders not decoding a substream.
     */
    private final ConcurrentLinkedQueue<VvcSliceDecoder> idleLanes = new ConcurrentLinkedQueue<>();

    /**
     * Guards the creation of slice decoders by concurrent substreams.
     */
    private final Object laneLock = new Object();

    /**
     * The coordination of the substreams of a slice decoded concurrently.
     */
    private final VvcWavefront wavefront = new VvcWavefront();
    /**
     * The in-loop filters.
     */
    private final VvcLoopFilter loopFilter = new VvcLoopFilter();

    /**
     * The picture header of the picture being decoded, or of the next one.
     */
    private VvcPictureHeader ph;
    /**
     * The picture being decoded, or {@code null} between pictures.
     */
    private VvcPicture current;
    /**
     * The sequence parameter set of the picture being decoded.
     */
    private VvcSps currentSps;
    /**
     * The picture parameter set of the picture being decoded.
     */
    private VvcPps currentPps;
    /**
     * The number of slices of the picture being decoded.
     */
    private int sliceCount;
    /**
     * Whether the slices of the current picture are skipped: a RASL picture of a CRA picture that starts the
     * stream, or a picture before the first random access point.
     */
    private boolean skipping;
    /**
     * The decoded picture hash of the picture being decoded, from its suffix SEI message, or {@code null}.
     */
    private VvcSei.Hash pendingHash;

    /**
     * The display SEI messages received for the next picture, and the persistent ones in effect.
     */
    private final VvcSei sei = new VvcSei();
    /**
     * Whether no picture was decoded yet, so that decoding starts at the next random access point.
     */
    private boolean firstPicture = true;
    /**
     * Whether an end of sequence ended the last coded video sequence.
     */
    private boolean afterEos = true;
    /**
     * {@code ph_pic_order_cnt_lsb} of the last picture with {@code TemporalId} 0 that is not a RASL, RADL or
     * non-reference picture.
     */
    private int prevTid0PocLsb;
    /**
     * The {@code PicOrderCntMsb} of that picture.
     */
    private int prevTid0PocMsb;
    /**
     * Whether the last IRAP picture had {@code NoOutputBeforeRecoveryFlag} set.
     */
    private boolean lastIrapNoOutput;
    /**
     * Whether the last IRAP picture was a CRA picture.
     */
    private boolean lastIrapIsCra;
    /**
     * Whether the pictures of a GDR picture that started a sequence are decoded before its recovery point.
     */
    private boolean gdrRecovering;
    /**
     * The picture order count of the recovery point of that GDR picture.
     */
    private int gdrRecoveryPoc;
    /**
     * The number of pictures started so far, which orders them in decoding order.
     */
    private long decodeOrder;
    /**
     * The timestamp of the packet being decoded, or {@link Long#MIN_VALUE}.
     */
    private long packetPts = Long.MIN_VALUE;
    /**
     * The duration of the packet being decoded, or 0.
     */
    private long packetDuration;
    /**
     * The timestamp of the packet that started the next picture, or {@link Long#MIN_VALUE}.
     */
    private long pendingPts = Long.MIN_VALUE;
    /**
     * The duration of the packet that started the next picture, or 0.
     */
    private long pendingDuration;
    /**
     * The number of decoded pictures whose hash did not match.
     */
    private int hashMismatches;
    /**
     * The number of decoded pictures checked against a hash.
     */
    private int hashChecked;
    /**
     * The first error of a slice of the picture being decoded, thrown once the picture is finished.
     */
    private MediaException.Malformed pendingError;

    /**
     * The error of the last picture finished during the current call, thrown when the call ends.
     */
    private MediaException.Malformed finishedError;

    /**
     * Constructs an engine with the default limits and without hash verification.
     *
     * @param output receives the decoded pictures in output order, valid until the consumer returns
     */
    public VvcDecoderEngine(Consumer<VvcPicture> output) {
        this(MediaLimits.DEFAULT, false, output);
    }

    /**
     * Constructs an engine.
     *
     * @param limits the resource bounds a picture must fit
     * @param checkHashes whether a picture that does not match its decoded picture hash SEI message raises
     * {@link MediaException.Malformed}
     * @param output receives the decoded pictures in output order, valid until the consumer returns
     */
    public VvcDecoderEngine(MediaLimits limits, boolean checkHashes, Consumer<VvcPicture> output) {
        this(limits, checkHashes, 1, output);
    }

    /**
     * Constructs an engine whose in-loop filters may run on several threads.
     *
     * @param limits the resource bounds a picture must fit
     * @param checkHashes whether a picture that does not match its decoded picture hash SEI message raises
     * {@link MediaException.Malformed}
     * @param threads the thread count of the substreams of a slice (its tiles and, with wavefront parallel processing,
     * the CTU rows of its tiles) and of the in-loop filters: 0 for the shared pool, 1 for the calling thread only, or
     * a larger bound
     * @param output receives the decoded pictures in output order, valid until the consumer returns
     */
    public VvcDecoderEngine(MediaLimits limits, boolean checkHashes, int threads, Consumer<VvcPicture> output) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.checkHashes = checkHashes;
        this.output = Objects.requireNonNull(output, "output");
        this.threads = threads;
        loopFilter.setThreads(threads);
    }

    /**
     * {@return the number of decoded pictures whose picture hash SEI did not match}
     */
    public int hashMismatches() {
        return hashMismatches;
    }

    /**
     * {@return the number of decoded pictures checked against a picture hash SEI}
     */
    public int hashChecked() {
        return hashChecked;
    }

    /**
     * Sets the timestamp and duration of the packet whose NAL units follow; a picture takes those of the packet in
     * which its picture header arrived.
     *
     * @param pts the presentation timestamp, or {@link Long#MIN_VALUE} when unknown
     * @param duration the duration, or 0 when unknown
     */
    public void beginPacket(long pts, long duration) {
        packetPts = pts;
        packetDuration = duration;
    }

    /**
     * Decodes a NAL unit of the current packet.
     *
     * @param data the buffer holding the unit, header included
     * @param offset the first byte of the unit
     * @param length the unit's length in bytes
     * @throws MediaException.Malformed if the unit or a finished picture is corrupt
     * @throws MediaException.Unsupported if the stream uses a feature outside the Main 10 profiles
     * @throws MediaException.LimitExceeded if a picture exceeds the limits
     */
    public void decodeNal(byte[] data, int offset, int length) {
        try {
            decodeUnit(data, offset, length);
        } catch (RuntimeException exception) {
            reportFinished(exception);
            throw exception;
        }
        reportFinished(null);
    }

    /**
     * Decodes a NAL unit, recording the error of a picture it finishes for {@link #reportFinished} rather than
     * throwing it, so that the unit that starts the next picture is decoded too.
     *
     * @param data the buffer holding the unit, header included
     * @param offset the first byte of the unit
     * @param length the unit's length in bytes
     */
    private void decodeUnit(byte[] data, int offset, int length) {
        if (length < 2) {
            return;
        }
        var b0 = data[offset] & 0xFF;
        var b1 = data[offset + 1] & 0xFF;
        if ((b0 & 0x80) != 0) {
            throw new MediaException.Malformed("vvc forbidden_zero_bit set");
        }
        var type = VvcNal.type(b1);
        var layer = VvcNal.layer(b0);
        var tid = VvcNal.temporalId(b1);
        if (layer != 0) {
            return;
        }
        if (tid < 0) {
            throw new MediaException.Malformed("vvc nuh_temporal_id_plus1 equal to 0");
        }
        var payloadLength = length - 2;
        if (rbsp.length < payloadLength + H264BitReader.PADDING) {
            rbsp = new byte[payloadLength + H264BitReader.PADDING + 1024];
            removed = new int[rbsp.length / 3 + 2];
        }
        var result = NalUnits.unescape(data, offset + 2, payloadLength, rbsp, removed);
        var size = (int) result;
        var removedCount = (int) (result >>> 32);
        Arrays.fill(rbsp, size, size + H264BitReader.PADDING, (byte) 0);
        switch (type) {
            case VvcNal.SPS -> {
                var id = (rbsp[0] & 0xFF) >> 4;
                var stored = spsRaw[id];
                if (stored != null && spsTable[id] != null && stored.length == size
                        && Arrays.equals(stored, 0, size, rbsp, 0, size)) {
                    // a repetition keeps the set, and with it the picture parameter sets parsed against it
                    return;
                }
                reader.init(rbsp, 0, size);
                var sps = VvcSps.parse(reader);
                spsTable[sps.id] = sps;
                spsRaw[sps.id] = Arrays.copyOf(rbsp, size);
            }
            case VvcNal.PPS -> {
                var id = (rbsp[0] & 0xFF) >> 2;
                var stored = ppsRaw[id];
                if (stored != null && ppsTable[id] != null && stored.length == size + H264BitReader.PADDING
                        && Arrays.equals(stored, 0, size, rbsp, 0, size)) {
                    // a repetition, which may come between the slices of a picture (H.266 7.4.2.4.4) and keeps
                    // the set the slices refer to
                    return;
                }
                ppsRaw[id] = Arrays.copyOf(rbsp, size + H264BitReader.PADDING);
                ppsTable[id] = null;
                reader.init(rbsp, 0, size);
                try {
                    ppsTable[id] = VvcPps.parse(reader, spsTable);
                } catch (MediaException.Malformed missingSps) {
                    ppsTable[id] = null;
                }
            }
            case VvcNal.PREFIX_APS, VvcNal.SUFFIX_APS -> {
                reader.init(rbsp, 0, size);
                var aps = VvcAps.parse(reader);
                if (aps != null) {
                    switch (aps.type) {
                        case VvcAps.ALF -> alfAps[aps.id & 7] = aps;
                        case VvcAps.LMCS -> lmcsAps[aps.id & 3] = aps;
                        default -> scalingAps[aps.id & 7] = aps;
                    }
                }
            }
            case VvcNal.PH -> {
                finishPicture();
                pendingPts = packetPts;
                pendingDuration = packetDuration;
                refreshPps();
                reader.init(rbsp, 0, size);
                ph = VvcPictureHeader.parse(reader, ppsTable);
            }
            case VvcNal.AUD -> finishPicture();
            case VvcNal.EOS, VvcNal.EOB -> {
                finishPicture();
                afterEos = true;
            }
            case VvcNal.PREFIX_SEI, VvcNal.SUFFIX_SEI -> {
                var hash = VvcSei.findHash(rbsp, 0, size);
                if (hash != null && type == VvcNal.SUFFIX_SEI) {
                    pendingHash = hash;
                }
                if (type == VvcNal.PREFIX_SEI) {
                    sei.parsePrefix(rbsp, 0, size);
                }
            }
            default -> {
                if (VvcNal.isVcl(type) && !VvcNal.isReservedVcl(type)) {
                    decodeSlice(type, tid, size, removedCount);
                }
            }
        }
    }

    /**
     * Parses again the picture parameter sets whose sequence parameter set was replaced or which could not be
     * parsed before their sequence parameter set arrived.
     */
    private void refreshPps() {
        for (var id = 0; id < 64; id++) {
            var raw = ppsRaw[id];
            if (raw == null) {
                continue;
            }
            var pps = ppsTable[id];
            var spsId = (raw[0] & 0x03) << 2 | (raw[1] & 0xFF) >> 6;
            if (pps == null || pps.sps != spsTable[spsId]) {
                reader.init(raw, 0, raw.length - H264BitReader.PADDING);
                try {
                    ppsTable[id] = VvcPps.parse(reader, spsTable);
                } catch (MediaException.Malformed missingSps) {
                    ppsTable[id] = null;
                }
            }
        }
    }

    /**
     * Decodes a coded slice NAL unit, starting a picture when it is the first slice of one: parses the slice header,
     * builds the reference picture lists, marks the references and bumps the buffer for the first slice, then decodes
     * the slice data.
     *
     * @param type the NAL unit type
     * @param tid the {@code TemporalId}
     * @param size the payload size in {@link #rbsp}
     * @param removedCount the number of emulation prevention bytes removed from the payload
     */
    private void decodeSlice(int type, int tid, int size, int removedCount) {
        var pictureHeaderInSlice = (rbsp[0] & 0x80) != 0;
        if (pictureHeaderInSlice) {
            finishPicture();
            pendingPts = packetPts;
            pendingDuration = packetDuration;
            refreshPps();
        }
        reader.init(rbsp, 0, size);
        var sh = VvcSliceHeader.parse(reader, type, pictureHeaderInSlice ? null : ph, ppsTable, removed,
                removedCount);
        sh.temporalId = tid;
        if (pictureHeaderInSlice) {
            ph = sh.ph;
        }
        if (current == null && !skipping) {
            startPicture(sh, type, tid);
        }
        if (skipping) {
            return;
        }
        if (sh.pps != currentPps) {
            throw new MediaException.Malformed("vvc slices of a picture refer to different picture parameter sets");
        }
        sh.poc = current.poc;
        buildRefLists(sh);
        if (sliceCount == 0) {
            markReferences(sh);
            // C.5.2.2: remove the pictures that are neither needed for output nor references, then bump while the
            // buffer is full
            removeUnused();
            var maxBuffering = sh.sps.maxDecPicBuffering[sh.sps.maxSubLayers - 1];
            while (dpb.size() >= Math.max(1, maxBuffering) && bump()) {
                removeUnused();
            }
        }
        var slice = sliceCount++;
        recordSlicePocs(sh, slice);
        try {
            decodeSliceData(sh, size, slice);
        } catch (MediaException.Malformed e) {
            if (pendingError == null) {
                pendingError = e;
            }
        }
    }

    /**
     * Starts decoding a picture from its first slice: the random access handling, the picture order count (8.3.1),
     * the output flag and the decoding state.
     *
     * @param sh the header of the first slice
     * @param type the NAL unit type of the slice
     * @param tid the {@code TemporalId} of the slice
     */
    private void startPicture(VvcSliceHeader sh, int type, int tid) {
        var sps = sh.sps;
        var pps = sh.pps;
        if (sps.chromaFormatIdc > 1) {
            throw new MediaException.Unsupported(MediaException.Unsupported.Kind.FEATURE, "vvc 4:2:2 and 4:4:4",
                    "VVC 4:2:2 and 4:4:4 streams (the Main 10 4:4:4 profiles) are not supported");
        }
        if (sps.bitDepth > 10) {
            throw new MediaException.Unsupported(MediaException.Unsupported.Kind.FEATURE, "vvc bit depth",
                    "VVC streams deeper than 10 bits are not supported");
        }
        if (sps.extendedPrecision || sps.rrcRiceExtension || sps.persistentRiceAdaptation
                || sps.reverseLastSigCoeff) {
            throw new MediaException.Unsupported(MediaException.Unsupported.Kind.FEATURE, "vvc range extension",
                    "VVC range extension coding tools are not supported");
        }
        var ph = sh.ph;
        // a picture with mixed NAL unit types is not an IRAP picture, but its IRAP slices still start the
        // association of the following RASL pictures
        var irapNal = VvcNal.isIrap(type);
        var irap = irapNal && !pps.mixedNaluTypes;
        var gdr = type == VvcNal.GDR && !pps.mixedNaluTypes;
        var noOutputBeforeRecovery = false;
        if (irap || gdr) {
            noOutputBeforeRecovery = firstPicture || afterEos || VvcNal.isIdr(type);
        }
        if (type == VvcNal.RASL && lastIrapIsCra && lastIrapNoOutput) {
            skipping = true;
            sei.clearPictureMessages();
            return;
        }
        if (!irapNal && !gdr && firstPicture) {
            // decoding starts at the first random access point
            skipping = true;
            sei.clearPictureMessages();
            return;
        }
        var clvss = (irap || gdr) && noOutputBeforeRecovery;
        var maxLsb = 1 << sps.log2MaxPocLsb;
        int msb;
        if (ph.pocMsbPresent) {
            msb = ph.pocMsbVal * maxLsb;
        } else if (clvss) {
            msb = 0;
        } else if (ph.pocLsb < prevTid0PocLsb && prevTid0PocLsb - ph.pocLsb >= maxLsb / 2) {
            msb = prevTid0PocMsb + maxLsb;
        } else if (ph.pocLsb > prevTid0PocLsb && ph.pocLsb - prevTid0PocLsb > maxLsb / 2) {
            msb = prevTid0PocMsb - maxLsb;
        } else {
            msb = prevTid0PocMsb;
        }
        var poc = msb + ph.pocLsb;
        if (tid == 0 && !ph.nonRef && type != VvcNal.RASL && type != VvcNal.RADL) {
            prevTid0PocLsb = ph.pocLsb;
            prevTid0PocMsb = msb;
        }
        if (irapNal) {
            lastIrapNoOutput = noOutputBeforeRecovery;
            lastIrapIsCra = type == VvcNal.CRA;
        }
        if (gdr && noOutputBeforeRecovery) {
            gdrRecovering = ph.recoveryPocCnt > 0;
            gdrRecoveryPoc = poc + ph.recoveryPocCnt;
        } else if (irap) {
            gdrRecovering = false;
        }
        if (clvss) {
            for (var p : dpb) {
                p.reference = false;
            }
            if (!firstPicture) {
                if (sh.noOutputOfPriorPics) {
                    for (var p : dpb) {
                        p.neededForOutput = false;
                    }
                } else {
                    flushOutput();
                }
            }
        }
        firstPicture = false;
        afterEos = false;
        LimitChecks.checkPicture(limits, pps.width, pps.height);
        var pic = allocate(pps.width, pps.height, sps.chromaFormatIdc, sps.bitDepth);
        pic.poc = poc;
        pic.temporalId = tid;
        pic.layer = 0;
        pic.longTerm = false;
        pic.reference = false;
        pic.generated = false;
        pic.decoded = false;
        pic.decodeOrder = decodeOrder++;
        pic.pts = pendingPts;
        pic.duration = pendingDuration;
        pic.keyframe = irap || (gdr && ph.recoveryPocCnt == 0);
        pic.ctuSize = sps.ctuSize;
        pic.pps = pps;
        pic.sps = sps;
        pic.fieldParity = sps.fieldSeq ? sei.fieldParity : -1;
        if (clvss && !sei.transferPending) {
            // an alternative transfer characteristics message lasts until the end of its coded layer video sequence
            sei.preferredTransfer = -1;
        }
        pic.newSequence = clvss;
        pic.filmGrain = sei.filmGrain;
        pic.framePacking = sei.framePacking;
        pic.orientation = sei.orientation;
        pic.captions = sei.captions.size() == 0 ? null : sei.captions.toByteArray();
        pic.fieldOrder = sps.fieldSeq ? null : sei.fieldOrder;
        pic.repeatedFields = sps.fieldSeq ? 0 : sei.repeatedFields;
        pic.masteringDisplay = sei.masteringDisplay;
        pic.contentLightLevel = sei.contentLightLevel;
        pic.preferredTransfer = sei.preferredTransfer;
        if (clvss && !sei.ambientPending) {
            sei.ambientViewing = null;
        }
        pic.ambientViewing = sei.ambientViewing;
        pic.contentColorVolume = sei.contentColorVolume;
        pic.sampleAspectRatio = sei.sampleAspectRatio;
        pic.equirectangular = sei.equirectangular;
        pic.cubemap = sei.cubemap;
        pic.sphereRotation = sei.sphereRotation;
        pic.regionWisePacking = sei.regionWisePacking;
        pic.viewports = sei.viewports;
        sei.clearPictureMessages();
        pic.confLeft = pps.confLeft;
        pic.confRight = pps.confRight;
        pic.confTop = pps.confTop;
        pic.confBottom = pps.confBottom;
        pic.scalingWindow[0] = pps.scalingLeft;
        pic.scalingWindow[1] = pps.scalingRight;
        pic.scalingWindow[2] = pps.scalingTop;
        pic.scalingWindow[3] = pps.scalingBottom;
        pic.subpicTreatedAsPic = sps.subpicTreatedAsPic;
        var outputFlag = ph.picOutput;
        if (type == VvcNal.RASL && lastIrapNoOutput) {
            outputFlag = false;
        }
        pic.beforeRecovery = gdrRecovering && poc < gdrRecoveryPoc;
        if (pic.beforeRecovery) {
            outputFlag = false;
        }
        if (gdrRecovering && poc >= gdrRecoveryPoc) {
            gdrRecovering = false;
        }
        pic.outputFlag = outputFlag;
        pic.neededForOutput = false;
        pic.resetMotion();
        pic.sliceRecords = 0;
        current = pic;
        currentSps = sps;
        currentPps = pps;
        sliceCount = 0;
        pendingHash = null;
        pendingError = null;
        if (frame == null || frame.width != pps.width || frame.height != pps.height
                || frame.log2Ctu != sps.log2CtuSize) {
            frame = new VvcFrame(pps.width, pps.height, sps.log2CtuSize);
            recon = new VvcRecon(frame);
            motion = new VvcMotion(frame);
            recon.setMotion(motion);
            sliceDecoder = new VvcSliceDecoder(frame, recon, motion, 0);
            lanes.clear();
            idleLanes.clear();
            lanes.add(sliceDecoder);
            idleLanes.add(sliceDecoder);
        }
        frame.begin(pic, sps, pps, ph);
        for (var lane : lanes) {
            lane.recon().setApsTables(alfAps, lmcsAps, scalingAps);
            lane.recon().beginPicture(sps, ph);
        }
    }

    /**
     * Decodes the slice data of a slice: on the calling thread when it has a single substream or the decoder keeps
     * to one thread, otherwise its substreams concurrently, in batches of {@link #MAX_STAGES}.
     *
     * @param sh the slice header
     * @param size the payload size in {@link #rbsp}
     * @param slice the index of the slice in the picture
     * @throws MediaException.Malformed if the slice data is corrupt
     */
    private void decodeSliceData(VvcSliceHeader sh, int size, int slice) {
        var starts = VvcSliceDecoder.substreams(sh);
        var count = starts.length - 1;
        if (count < 2 || threads == 1 || !ParallelBands.enabled()) {
            sliceDecoder.decode(sh, rbsp, sh.dataOffset, size, slice);
            return;
        }
        sliceDecoder.prepare(sh, slice, true);
        wavefront.beginSlice(sh, starts);
        var batchSize = threads > 1 ? Math.min(MAX_STAGES, threads) : MAX_STAGES;
        for (var batch = 0; batch < count; batch += batchSize) {
            var first = batch;
            var stages = Math.min(batchSize, count - batch);
            wavefront.beginBatch(first, stages);
            ParallelBands.pipeline(stages, stage -> {
                var lane = acquireLane();
                try {
                    lane.prepare(sh, slice, false);
                    lane.decodeSubstream(rbsp, sh.dataOffset, size, first + stage, starts, wavefront);
                } catch (RuntimeException exception) {
                    wavefront.fail();
                    throw exception;
                } finally {
                    idleLanes.add(lane);
                }
            });
        }
    }

    /**
     * Returns an idle slice decoder, creating one for the current picture when every decoder is busy.
     *
     * @return the decoder, which the caller returns to {@link #idleLanes}
     */
    private VvcSliceDecoder acquireLane() {
        var lane = idleLanes.poll();
        if (lane != null) {
            return lane;
        }
        synchronized (laneLock) {
            if (lanes.size() >= VvcFrame.MAX_LANES) {
                throw new IllegalStateException("more concurrent vvc substreams than lanes");
            }
            var laneRecon = new VvcRecon(frame);
            var laneMotion = new VvcMotion(frame);
            laneRecon.setMotion(laneMotion);
            laneRecon.setApsTables(alfAps, lmcsAps, scalingAps);
            laneRecon.beginPicture(currentSps, ph);
            lane = new VvcSliceDecoder(frame, laneRecon, laneMotion, lanes.size());
            lanes.add(lane);
            return lane;
        }
    }

    /**
     * {@return the number of pictures held in {@link #pool} for reuse}
     */
    int pooledPictures() {
        return pool.size();
    }

    /**
     * Returns a picture of a size and format, recycled from {@link #pool} when one matches; otherwise the pool is
     * emptied and a new picture allocated.
     *
     * @param width the coded width
     * @param height the coded height
     * @param chromaFormat {@code sps_chroma_format_idc}
     * @param bitDepth the bit depth
     * @return the picture
     * @throws MediaException.LimitExceeded if a plane of the picture exceeds the allocation limit
     */
    private VvcPicture allocate(int width, int height, int chromaFormat, int bitDepth) {
        for (var i = 0; i < pool.size(); i++) {
            var p = pool.get(i);
            if (p.width == width && p.height == height && p.chromaFormat == chromaFormat && p.bitDepth == bitDepth) {
                pool.remove(i);
                return p;
            }
        }
        // no pooled picture fits: the pooled ones belong to earlier sizes, so they are released rather than kept for
        // the rest of the stream
        pool.clear();
        LimitChecks.checkAllocation(limits, (long) width * height * Short.BYTES);
        return new VvcPicture(width, height, chromaFormat, bitDepth);
    }

    /**
     * Constructs the reference picture lists of a slice (8.3.2), generating the missing active references (8.3.4),
     * and derives the reference picture resampling ratios (8.3.2).
     *
     * @param sh the slice header
     */
    private void buildRefLists(VvcSliceHeader sh) {
        var sps = sh.sps;
        var pps = sh.pps;
        var maxLsb = 1 << sps.log2MaxPocLsb;
        var poc = current.poc;
        var curW = pps.width - ((pps.scalingLeft + pps.scalingRight) << sps.chromaShiftX());
        var curH = pps.height - ((pps.scalingTop + pps.scalingBottom) << sps.chromaShiftY());
        for (var l = 0; l < 2; l++) {
            var list = sh.rpl[l];
            Arrays.fill(sh.refPics[l], null);
            for (var j = 0; j < list.numEntries; j++) {
                VvcPicture ref = null;
                int refPoc;
                var lt = list.longTerm[j];
                if (list.interLayer[j]) {
                    throw new MediaException.Unsupported(MediaException.Unsupported.Kind.FEATURE,
                            "vvc inter-layer prediction", "VVC inter-layer prediction is not supported");
                }
                if (!lt) {
                    refPoc = poc + list.identifier[j];
                    ref = referenceWithPoc(refPoc, -1);
                } else if (!list.msbPresent[j]) {
                    refPoc = list.identifier[j];
                    ref = referenceWithPoc(refPoc, maxLsb - 1);
                } else {
                    refPoc = poc - list.msbCycle[j] * maxLsb - (poc & (maxLsb - 1)) + list.identifier[j];
                    ref = referenceWithPoc(refPoc, -1);
                }
                if (ref == null) {
                    if (j >= sh.numRefIdxActive[l]) {
                        sh.refPoc[l][j] = refPoc;
                        sh.refLongTerm[l][j] = lt;
                        continue;
                    }
                    ref = generateMissing(sh, refPoc, lt);
                }
                sh.refPics[l][j] = ref;
                sh.refPoc[l][j] = ref.poc;
                sh.refLongTerm[l][j] = lt;
                var refW = ref.width - ((ref.scalingWindow[0] + ref.scalingWindow[1]) << sps.chromaShiftX());
                var refH = ref.height - ((ref.scalingWindow[2] + ref.scalingWindow[3]) << sps.chromaShiftY());
                sh.refScaleX[l][j] = ((refW << 14) + (curW >> 1)) / curW;
                sh.refScaleY[l][j] = ((refH << 14) + (curH >> 1)) / curH;
                sh.rprActive[l][j] = ref.width != pps.width || ref.height != pps.height
                        || ref.scalingWindow[0] != pps.scalingLeft || ref.scalingWindow[1] != pps.scalingRight
                        || ref.scalingWindow[2] != pps.scalingTop || ref.scalingWindow[3] != pps.scalingBottom
                        || (ref.pps != null && ref.pps.sps.numSubpics != sps.numSubpics);
            }
        }
    }

    /**
     * {@return the reference picture of the buffer whose order count, masked by {@code mask} unless it is -1, equals
     * a value, or {@code null}}
     *
     * @param poc the order count
     * @param mask the mask of the order count bits compared, or -1 to compare the whole count
     */
    private VvcPicture referenceWithPoc(int poc, int mask) {
        for (var p : dpb) {
            if (p.reference && (mask == -1 ? p.poc : p.poc & mask) == poc) {
                return p;
            }
        }
        return null;
    }

    /**
     * Generates a mid-gray stand-in for a missing reference picture (8.3.4) and stores it in the buffer.
     *
     * @param sh the slice header naming the reference
     * @param poc the order count of the missing picture
     * @param longTerm whether the reference is a long-term reference
     * @return the generated picture
     */
    private VvcPicture generateMissing(VvcSliceHeader sh, int poc, boolean longTerm) {
        var sps = sh.sps;
        var pps = sh.pps;
        var pic = allocate(pps.width, pps.height, sps.chromaFormatIdc, sps.bitDepth);
        pic.fillGray();
        pic.poc = poc;
        pic.reference = true;
        pic.longTerm = longTerm;
        pic.generated = true;
        pic.decoded = true;
        pic.outputFlag = false;
        pic.neededForOutput = false;
        pic.pps = pps;
        pic.sps = sps;
        pic.ctuSize = sps.ctuSize;
        pic.scalingWindow[0] = pps.scalingLeft;
        pic.scalingWindow[1] = pps.scalingRight;
        pic.scalingWindow[2] = pps.scalingTop;
        pic.scalingWindow[3] = pps.scalingBottom;
        pic.subpicTreatedAsPic = sps.subpicTreatedAsPic;
        pic.sliceRecords = 0;
        pic.compressMotion();
        dpb.add(pic);
        return pic;
    }

    /**
     * Applies the reference picture marking of the current picture (8.3.3).
     *
     * @param sh the header of the first slice of the current picture
     */
    private void markReferences(VvcSliceHeader sh) {
        for (var p : dpb) {
            if (!p.reference) {
                continue;
            }
            var used = false;
            var asLong = false;
            for (var l = 0; l < 2; l++) {
                for (var j = 0; j < sh.rpl[l].numEntries; j++) {
                    if (sh.refPics[l][j] == p) {
                        used = true;
                        asLong |= sh.refLongTerm[l][j];
                    }
                }
            }
            if (!used) {
                p.reference = false;
            } else if (asLong) {
                p.longTerm = true;
            }
        }
    }

    /**
     * Stores the order counts and long-term markings of a slice's active references, which the temporal motion
     * field of the picture refers to.
     *
     * @param sh the slice header
     * @param slice the slice index
     */
    private void recordSlicePocs(VvcSliceHeader sh, int slice) {
        var pic = current;
        if (pic.slicePocs.length <= slice) {
            var capacity = Math.max(slice + 1, 2 * pic.slicePocs.length);
            var pocs = Arrays.copyOf(pic.slicePocs, capacity);
            var lt = Arrays.copyOf(pic.sliceLongTerm, capacity);
            for (var i = pic.slicePocs.length; i < capacity; i++) {
                pocs[i] = new int[2][VvcRefPicList.MAX_ENTRIES];
                lt[i] = new boolean[2][VvcRefPicList.MAX_ENTRIES];
            }
            pic.slicePocs = pocs;
            pic.sliceLongTerm = lt;
        }
        for (var i = pic.sliceRecords; i <= slice; i++) {
            for (var l = 0; l < 2; l++) {
                Arrays.fill(pic.slicePocs[i][l], 0);
                Arrays.fill(pic.sliceLongTerm[i][l], false);
            }
        }
        for (var l = 0; l < 2; l++) {
            var pocs = pic.slicePocs[slice][l];
            var lt = pic.sliceLongTerm[slice][l];
            Arrays.fill(pocs, 0);
            Arrays.fill(lt, false);
            for (var j = 0; j < sh.numRefIdxActive[l]; j++) {
                pocs[j] = sh.refPoc[l][j];
                lt[j] = sh.refLongTerm[l][j];
            }
        }
        pic.sliceRecords = slice + 1;
    }

    /**
     * Finishes the picture being decoded: applies the in-loop filters, checks its hash, stores it in the decoded
     * picture buffer and outputs the pictures the buffer bumps (C.5.2.3).
     */
    private void finishPicture() {
        skipping = false;
        if (current == null) {
            return;
        }
        var pic = current;
        current = null;
        var error = pendingError;
        pendingError = null;
        loopFilter.apply(frame, currentSps, currentPps, recon);
        pic.compressMotion();
        pic.decoded = true;
        if (pendingHash != null) {
            hashChecked++;
            if (!VvcSei.matches(pic, pendingHash)) {
                hashMismatches++;
                if (checkHashes && error == null && !pic.beforeRecovery) {
                    error = new MediaException.Malformed("vvc picture " + pic.poc + " does not match its hash");
                }
            }
            pendingHash = null;
        }
        pic.reference = true;
        pic.longTerm = false;
        var sps = currentSps;
        var top = sps.maxSubLayers - 1;
        if (pic.outputFlag) {
            for (var p : dpb) {
                if (p.neededForOutput && p.poc > pic.poc) {
                    p.latency++;
                }
            }
            pic.neededForOutput = true;
            pic.latency = 0;
        }
        dpb.add(pic);
        var maxLatency = sps.maxNumReorder[top] + sps.maxLatencyIncreasePlus1[top] - 1;
        while (true) {
            var needed = 0;
            var latencyHit = false;
            for (var p : dpb) {
                if (p.neededForOutput) {
                    needed++;
                    if (sps.maxLatencyIncreasePlus1[top] != 0 && p.latency >= maxLatency) {
                        latencyHit = true;
                    }
                }
            }
            if (!(needed > sps.maxNumReorder[top] || latencyHit) || !bump()) {
                break;
            }
        }
        if (error != null && finishedError == null) {
            finishedError = error;
        }
    }

    /**
     * Throws the error of the last finished picture, if it was corrupt, and forgets it.
     *
     * @param primary the exception the current call is about to throw, or {@code null}, which then carries the
     * picture's error as a suppressed exception
     * @throws MediaException.Malformed if the last finished picture was corrupt and {@code primary} is {@code null}
     */
    private void reportFinished(RuntimeException primary) {
        var error = finishedError;
        finishedError = null;
        if (error == null) {
            return;
        }
        if (primary != null) {
            primary.addSuppressed(error);
            return;
        }
        throw error;
    }

    /**
     * Releases to {@link #pool} the pictures of the buffer that are neither references nor waiting for output.
     */
    private void removeUnused() {
        for (var it = dpb.iterator(); it.hasNext(); ) {
            var p = it.next();
            if (!p.reference && !p.neededForOutput && p != current) {
                it.remove();
                pool.add(p);
            }
        }
    }

    /**
     * Outputs the picture of the buffer with the smallest order count among those needed for output (C.5.2.4).
     *
     * @return {@code false} when no picture is waiting for output
     */
    private boolean bump() {
        VvcPicture first = null;
        for (var p : dpb) {
            if (p.neededForOutput && (first == null || p.poc < first.poc)) {
                first = p;
            }
        }
        if (first == null) {
            return false;
        }
        first.neededForOutput = false;
        output.accept(first);
        if (!first.reference) {
            dpb.remove(first);
            pool.add(first);
        }
        return true;
    }

    /**
     * Outputs every picture waiting for output and empties the buffer of the pictures no longer needed.
     */
    private void flushOutput() {
        while (bump()) {
            removeUnused();
        }
        removeUnused();
    }

    /**
     * Finishes the picture being decoded and outputs every picture still waiting for output.
     *
     * @throws MediaException.Malformed if the last picture was corrupt
     */
    public void flush() {
        try {
            finishPicture();
            flushOutput();
        } catch (RuntimeException exception) {
            reportFinished(exception);
            throw exception;
        } finally {
            afterEos = true;
        }
        reportFinished(null);
    }
}
