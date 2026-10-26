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
import com.github.auties00.smalt.internal.sei.DisplaySei;
import com.github.auties00.smalt.internal.sei.FilmGrainParameters;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
 * IRAP picture or at the first picture carrying a recovery point SEI message (D.3.8) whose
 * {@code recovery_poc_cnt} is in range, whichever comes first; the RASL pictures of an IRAP
 * picture that starts a coded video sequence are skipped, as the specification allows. After a
 * start at a recovery point, the missing references are generated (8.3.3.2) and the pictures that
 * precede the recovery point in output order are decoded but not output, until the next IRAP
 * picture that starts a coded video sequence.
 *
 * <p>Streams whose video parameter set describes several layers (Annexes F, G and H) are decoded
 * as a single layer stream, ignoring the NAL units of the other layers, unless the target output
 * layer set holds more than the base layer or an alpha auxiliary layer is to be attached to an
 * output layer. The layers decoded are then the necessary layers of the target output layer set
 * (F.8.1.2) and the alpha auxiliary layers of its primary output layers with their reference
 * layers; each follows the multi-layer picture order count (F.8.3.1), reference picture set
 * (F.8.3.2) and reference picture list (F.8.3.4) processes, with the inter-layer reference
 * pictures of H.8.1.3 and H.8.1.4, and the pictures of an access unit are output together in
 * increasing layer order (F.13.5.2), an alpha picture attached to its primary picture
 * ({@link HevcFrame#alpha}) instead of being output by itself.
 *
 * <p>Instances are not thread-safe.
 */
public final class HevcDecoderEngine {
    /**
     * The most pictures the decoded picture buffer may hold for one layer, including generated
     * ones, before the stream is treated as corrupt.
     */
    private static final int MAX_DPB = 24;

    /**
     * The video parameter sets by identifier.
     */
    private final HevcVps[] vpsTable = new HevcVps[16];

    /**
     * The sequence parameter sets by identifier.
     */
    private final HevcSps[] spsTable = new HevcSps[16];

    /**
     * The picture parameter sets by identifier.
     */
    private final HevcPps[] ppsTable = new HevcPps[64];

    /**
     * The pictures of the decoded picture buffer, of every layer.
     */
    private final List<HevcFrame> dpb = new ArrayList<>();

    /**
     * Pictures no longer in the decoded picture buffer, kept for reuse.
     */
    private final List<HevcFrame> pool = new ArrayList<>();

    /**
     * Inter-layer reference pictures no longer read, kept for reuse.
     */
    private final List<HevcFrame> interLayerPool = new ArrayList<>();

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
     * The idle working memories of the inter-layer reference picture derivations.
     */
    private final Queue<InterLayerPicture.Scratch> idleScratches = new ConcurrentLinkedQueue<>();

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
     * The target output layer set requested: its index, or -1 for the last one the video parameter
     * set defines.
     */
    private final int requestedOls;

    /**
     * Whether the alpha auxiliary layers of the primary output layers are decoded and attached to
     * their pictures.
     */
    private final boolean attachAlpha;

    /**
     * The state of each layer, by layer identifier, created with the first NAL unit of the layer.
     */
    private final LayerState[] layers = new LayerState[64];

    /**
     * The active sequence parameter set of each layer, by layer identifier.
     */
    private final HevcSps[] activeSpsByLayer = new HevcSps[64];

    /**
     * The active picture parameter set of each layer, by layer identifier.
     */
    private final HevcPps[] activePpsByLayer = new HevcPps[64];

    /**
     * The video parameter set the layers to decode were chosen for, or {@code null} for a single
     * layer stream.
     */
    private HevcVps planVps;

    /**
     * Whether more than the base layer is decoded, which selects the multi-layer processes.
     */
    private boolean multiLayer;

    /**
     * The target output layer set index, {@code TargetOlsIdx}.
     */
    private int targetOls;

    /**
     * Whether each layer is decoded, by layer identifier.
     */
    private final boolean[] decodedLayers = new boolean[64];

    /**
     * Whether each layer is output, by layer identifier; an attached alpha layer counts as output.
     */
    private final boolean[] outputLayers = new boolean[64];

    /**
     * Whether each layer is an alpha layer attached to the pictures of a primary layer.
     */
    private final boolean[] attachedAlpha = new boolean[64];

    /**
     * The alpha layer attached to each primary layer, by layer identifier, or -1.
     */
    private final int[] alphaLayerOf = new int[64];

    /**
     * Whether the output pictures name their layer: several layers are output, or a layer other
     * than the base layer.
     */
    private boolean layeredOutput;

    /**
     * The source of the base layer pictures of a stream whose base layer is coded outside it, or
     * {@code null}.
     */
    private ExternalBaseLayer externalBase;

    /**
     * Whether decoding may start at any picture, as if each picture without a recovery point carried one with a
     * {@code recovery_poc_cnt} of 0.
     */
    private boolean startAnywhere;

    /**
     * The picture being decoded, or {@code null} between pictures.
     */
    private HevcFrame current;

    /**
     * The layer of {@link #current}.
     */
    private LayerState currentLayer;

    /**
     * The header of the last independent slice segment of the current picture.
     */
    private HevcSliceHeader independent;

    /**
     * Whether the decoded picture hash of the current picture may be checked: not for a picture
     * whose content the recovery point does not guarantee.
     */
    private boolean hashVerifiable;

    /**
     * Whether the slices of the current picture are being skipped.
     */
    private boolean skipping;

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
     * {@code RefPicSetInterLayer0} and {@code RefPicSetInterLayer1} of the current picture.
     */
    private final HevcFrame[][] interLayer = new HevcFrame[2][8];

    /**
     * {@code NumActiveRefLayerPics0} and {@code NumActiveRefLayerPics1}.
     */
    private final int[] interLayerCount = new int[2];

    /**
     * The pictures of the access unit in progress, in decoding order.
     */
    private final List<HevcFrame> accessUnit = new ArrayList<>();

    /**
     * The layer of the last picture started in the access unit in progress, or -1 when it holds
     * none.
     */
    private int accessUnitLayer = -1;

    /**
     * The picture order count of the access unit in progress.
     */
    private int accessUnitPoc;

    /**
     * The {@code poc_reset_period_id} of the POC resetting period in progress, or -1.
     */
    private int resetPeriod = -1;

    /**
     * Whether a video parameter set was activated since the last IRAP picture of the smallest
     * layer.
     */
    private boolean newVps;

    /**
     * The state of one layer that lasts from picture to picture.
     */
    private static final class LayerState {
        /**
         * The {@code nuh_layer_id}.
         */
        final int id;

        /**
         * The supplemental enhancement information collected for the layer.
         */
        final HevcSei sei = new HevcSei();

        /**
         * The sequence parameter set of the coded video sequence of the layer.
         */
        HevcSps activeSps;

        /**
         * Whether the next picture of the layer is its first of the bitstream.
         */
        boolean firstPicture = true;

        /**
         * Whether an end of sequence NAL unit of the layer preceded its next picture.
         */
        boolean afterEndOfSequence;

        /**
         * Whether decoding of the layer has started: an IRAP picture or a recovery point started
         * the base layer, and {@code LayerInitializedFlag} for the other layers.
         */
        boolean started;

        /**
         * {@code NoRaslOutputFlag} of the last IRAP picture of the layer.
         */
        boolean irapNoRaslOutput;

        /**
         * Whether decoding started at a recovery point and no IRAP picture with
         * {@code NoRaslOutputFlag} has started a coded video sequence since.
         */
        boolean recovering;

        /**
         * The picture order count of the recovery point while {@link #recovering}.
         */
        int recoveryPoc;

        /**
         * {@code exact_match_flag} of the recovery point decoding started at.
         */
        boolean recoveryExact;

        /**
         * The picture order count of {@code prevTid0Pic}, {@code PrevPicOrderCnt} of the layer.
         */
        int prevTid0Poc;

        /**
         * {@code FirstPicInLayerDecodedFlag}.
         */
        boolean firstPicDecoded;

        /**
         * The {@code poc_reset_period_id} of the last picture of the layer, or -1.
         */
        int pocResetPeriod = -1;

        /**
         * {@code PocDecrementedInDPBFlag}.
         */
        boolean pocDecremented;

        LayerState(int id) {
            this.id = id;
        }
    }

    /**
     * Supplies the decoded pictures of a base layer coded outside the stream (F.8.1.8), one per
     * access unit that holds a picture of another layer.
     */
    public interface ExternalBaseLayer {
        /**
         * Returns the base layer picture of the next access unit, in decoding order.
         *
         * <p>The picture takes the picture order count of the access unit (F.8.1.9), which the
         * access unit's first picture derives once the base layer picture is known; its
         * {@code slice_pic_order_cnt_lsb} identifies the access unit to a source that keeps its
         * pictures in output order.
         *
         * @param format the representation format the video parameter set gives the base layer
         * @param pocLsb the {@code slice_pic_order_cnt_lsb} of the access unit's first picture
         * @return the picture, holding its samples in the format of {@code format}, or
         * {@code null} when the access unit has none
         */
        Picture next(HevcVps.RepFormat format, int pocLsb);

        /**
         * A decoded base layer picture.
         *
         * @param frame the samples, the size and format of the base layer's representation format
         * @param irap whether the picture is an IRAP picture ({@code BlIrapPicFlag})
         * @param nalType the {@code nal_unit_type} of an IRAP picture: {@link HevcNal#IDR_W_RADL},
         * {@link HevcNal#CRA_NUT} or {@link HevcNal#BLA_W_LP}
         */
        record Picture(HevcFrame frame, boolean irap, int nalType) {
        }
    }

    /**
     * Constructs an engine that decodes the base layer of a stream.
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
        this(limits, checkHashes, threads, 0, false, output);
    }

    /**
     * Constructs an engine that decodes the layers of an output layer set of a stream.
     *
     * @param limits the resource limits
     * @param checkHashes whether to check decoded picture hash SEI messages
     * @param threads the most pictures decoded concurrently; 1 decodes on the calling thread
     * @param outputLayerSet the target output layer set index, or -1 for the last one the stream
     * defines; an index past the last one selects the last one
     * @param attachAlpha whether the alpha auxiliary layers of the primary output layers are
     * decoded and attached to their pictures
     * @param output receives the output pictures; a picture's samples may be reused once the
     * consumer returns
     */
    public HevcDecoderEngine(MediaLimits limits, boolean checkHashes, int threads, int outputLayerSet,
                             boolean attachAlpha, Consumer<HevcFrame> output) {
        this.limits = limits;
        this.checkHashes = checkHashes;
        this.output = output;
        this.parallel = threads > 1 && ParallelBands.enabled();
        this.threads = parallel ? threads : 1;
        this.requestedOls = outputLayerSet;
        this.attachAlpha = attachAlpha;
        Arrays.fill(alphaLayerOf, -1);
        decodedLayers[0] = true;
        outputLayers[0] = true;
    }

    /**
     * Sets the source of the base layer pictures of a stream whose video parameter set says its
     * base layer is coded outside it ({@code vps_base_layer_internal_flag} clear).
     *
     * @param source the source, or {@code null} for none, which leaves such streams undecodable
     */
    public void externalBaseLayer(ExternalBaseLayer source) {
        this.externalBase = source;
    }

    /**
     * Lets decoding start at a picture that is neither an IRAP picture nor carries a recovery point, such as a
     * HEIF image item coded as an intra trailing picture, as if it carried a recovery point SEI message with a
     * {@code recovery_poc_cnt} of 0: the picture is output and the references it names are generated (8.3.3.2).
     *
     * @param enabled whether decoding may start anywhere; {@code false} by default
     */
    public void startAnywhere(boolean enabled) {
        this.startAnywhere = enabled;
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
     * Returns the state of a layer, creating it with the first NAL unit of the layer.
     *
     * @param id the {@code nuh_layer_id}
     * @return the state
     */
    private LayerState layer(int id) {
        var state = layers[id];
        if (state == null) {
            state = new LayerState(id);
            layers[id] = state;
        }
        return state;
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
        var layerId = (header >> 3) & 0x3F;
        var temporalIdPlus1 = header & 7;
        if (temporalIdPlus1 == 0) {
            throw new MediaException.Malformed("nuh_temporal_id_plus1 is 0");
        }
        if (layerId != 0 && !accepts(layerId, type)) {
            return;
        }
        if (HevcNal.startsAccessUnit(type)) {
            finishPictureDeferred();
        }
        if (!HevcNal.isSlice(type) && type > HevcNal.SUFFIX_SEI || HevcNal.isReservedVcl(type) || type == HevcNal.FD) {
            throwDeferred();
            return;
        }
        decodeRbsp(type, layerId, temporalIdPlus1 - 1, data, offset, length);
        throwDeferred();
    }

    /**
     * Returns whether a NAL unit of a layer above the base layer is decoded: parameter sets when
     * any layer above the base layer is, the other NAL units when their layer is.
     *
     * @param layerId the {@code nuh_layer_id}, from 1 to 63
     * @param type the NAL unit type
     * @return {@code true} to decode the NAL unit
     */
    private boolean accepts(int layerId, int type) {
        if (!multiLayer || layerId == 63) {
            return false;
        }
        return type == HevcNal.SPS || type == HevcNal.PPS || decodedLayers[layerId];
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
     * @param layerId the {@code nuh_layer_id}
     * @param temporalId the temporal identifier
     * @param data the buffer holding the NAL unit
     * @param offset the index of the NAL unit header
     * @param length the NAL unit length in bytes
     */
    private void decodeRbsp(int type, int layerId, int temporalId, byte[] data, int offset, int length) {
        var size = unescape(data, offset + 2, length - 2);
        reader.init(rbsp, 0, size);
        switch (type) {
            case HevcNal.AUD -> {
            }
            case HevcNal.VPS -> parseVps(size);
            case HevcNal.SPS -> {
                var sps = HevcSps.parse(reader, layerId, vpsTable);
                spsTable[sps.id] = sps;
            }
            case HevcNal.PPS -> {
                var pps = HevcPps.parse(reader);
                ppsTable[pps.id] = pps;
            }
            case HevcNal.EOS -> {
                layer(layerId).afterEndOfSequence = true;
                if (layerId == 0) {
                    for (var state : layers) {
                        if (state != null) {
                            state.afterEndOfSequence = true;
                        }
                    }
                }
            }
            case HevcNal.EOB -> {
                endAccessUnitPictures();
                bumpAll();
                for (var state : layers) {
                    if (state != null) {
                        state.firstPicture = true;
                        state.afterEndOfSequence = true;
                    }
                }
            }
            case HevcNal.PREFIX_SEI, HevcNal.SUFFIX_SEI -> {
                var state = layer(layerId);
                var sps = state.activeSps;
                var components = sps == null || sps.chromaFormatIdc == 0 ? 1 : 3;
                var sei = state.sei;
                sei.parse(rbsp, 0, size, type == HevcNal.SUFFIX_SEI, components);
                if (sei.hashType >= 0) {
                    var forCurrent = current != null && currentLayer == state;
                    pendingHash = forCurrent ? sei.hashType : -1;
                    if (!forCurrent) {
                        sei.hashType = -1;
                    }
                }
            }
            default -> slice(type, layerId, temporalId, size);
        }
    }

    /**
     * Parses a video parameter set and, when the layers it describes change the layers to decode,
     * chooses them. A corrupt set is ignored when only the base layer is wanted.
     *
     * @param size the unescaped payload size
     */
    private void parseVps(int size) {
        HevcVps vps;
        try {
            vps = HevcVps.parse(reader);
        } catch (MediaException.Malformed failure) {
            if (requestedOls != 0) {
                throw failure;
            }
            return;
        }
        var previous = vpsTable[vps.id];
        if (previous != null && Arrays.equals(previous.raw, vps.raw)) {
            // a repeated set keeps the object the parsed parameter sets were resolved with
            return;
        }
        // a set of new content starts a coded video sequence, so the access unit in progress ends under the
        // layers chosen before
        endAccessUnitPictures();
        vpsTable[vps.id] = vps;
        if (planVps != null) {
            newVps = true;
        }
        plan(vps);
    }

    /**
     * Chooses the layers to decode and output for a video parameter set (F.8.1.2): the necessary
     * layers of the target output layer set, and, when alpha layers are attached, the alpha
     * auxiliary layers associated with its primary output layers with their reference layers.
     *
     * @param vps the video parameter set
     */
    private void plan(HevcVps vps) {
        Arrays.fill(decodedLayers, false);
        Arrays.fill(outputLayers, false);
        Arrays.fill(attachedAlpha, false);
        Arrays.fill(alphaLayerOf, -1);
        planVps = vps;
        var count = vps.numOutputLayerSets();
        var ols = requestedOls < 0 || requestedOls >= count ? count - 1 : requestedOls;
        if (!vps.baseLayerInternal && ols == 0 && count > 1) {
            // the output layer set of the base layer alone cannot be decoded from the stream
            ols = 1;
        }
        targetOls = ols;
        var list = vps.layerSetLayerIdList[vps.olsIdxToLsIdx[ols]];
        for (var j = 0; j < list.length; j++) {
            if (vps.necessaryLayerFlag[ols][j]) {
                decodedLayers[list[j]] = true;
            }
            if (vps.outputLayerFlag[ols][j]) {
                outputLayers[list[j]] = true;
            }
        }
        if (attachAlpha && vps.extension) {
            for (var i = 0; i < vps.maxLayers; i++) {
                var alpha = vps.layerIdInNuh[i];
                if (vps.auxId[alpha] != HevcVps.AUX_ALPHA) {
                    continue;
                }
                // the primary layer whose scalability identifiers the alpha layer shares, else the one of its view
                var primary = associatedPrimary(vps, i, true);
                if (primary < 0) {
                    primary = associatedPrimary(vps, i, false);
                }
                if (primary >= 0) {
                    alphaLayerOf[primary] = alpha;
                    attachedAlpha[alpha] = true;
                    outputLayers[alpha] = true;
                    decodedLayers[alpha] = true;
                    for (var reference : vps.idRefLayer[alpha]) {
                        decodedLayers[reference] = true;
                    }
                }
            }
        }
        if (vps.baseLayerInternal) {
            decodedLayers[0] = true;
        }
        var decoded = 0;
        var output = 0;
        var nonBase = false;
        for (var id = 0; id < 64; id++) {
            if (decodedLayers[id]) {
                decoded++;
            }
            if (outputLayers[id] && !attachedAlpha[id]) {
                output++;
                nonBase |= id != 0;
            }
        }
        multiLayer = decoded > 1 || !vps.baseLayerInternal;
        if (!multiLayer) {
            Arrays.fill(decodedLayers, false);
            Arrays.fill(outputLayers, false);
            decodedLayers[0] = true;
            outputLayers[0] = true;
            planVps = null;
        }
        layeredOutput = multiLayer && (output > 1 || nonBase);
        for (var frame : dpb) {
            // the pictures of a layer no longer decoded are still output but never referenced again
            if (!decodedLayers[frame.layerId]) {
                frame.shortTerm = false;
                frame.longTerm = false;
            }
        }
    }

    /**
     * Returns the primary output layer an alpha layer belongs to that has no alpha layer yet.
     *
     * @param vps the video parameter set
     * @param auxIdx the layer index of the alpha layer
     * @param strict whether the layers' scalability identifiers must agree in every dimension but
     * the auxiliary one, which defines the association (F.14.3.8), or only outside the dependency
     * dimension too, which pairs an alpha layer coded as a quality layer of its view
     * @return the identifier of the primary layer, the lowest when several qualify, or -1
     */
    private int associatedPrimary(HevcVps vps, int auxIdx, boolean strict) {
        for (var j = 0; j < vps.maxLayers; j++) {
            var primary = vps.layerIdInNuh[j];
            if (!outputLayers[primary] || vps.auxId[primary] != 0 || alphaLayerOf[primary] >= 0) {
                continue;
            }
            var same = true;
            for (var dimension = 0; dimension < 16 && same; dimension++) {
                if (dimension != 3 && (strict || dimension != 2)) {
                    same = vps.scalabilityId[auxIdx][dimension] == vps.scalabilityId[j][dimension];
                }
            }
            if (same) {
                return primary;
            }
        }
        return -1;
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
     * Returns the layer of a slice segment as its header parses it.
     *
     * @param layerId the {@code nuh_layer_id}
     * @return the layer
     */
    private HevcSliceHeader.Layer sliceLayer(int layerId) {
        if (!multiLayer) {
            return HevcSliceHeader.Layer.BASE;
        }
        return new HevcSliceHeader.Layer(layerId, planVps, targetOls, activeSpsByLayer, activePpsByLayer);
    }

    /**
     * Decodes a slice segment NAL unit.
     *
     * @param type the NAL unit type
     * @param layerId the {@code nuh_layer_id}
     * @param temporalId the temporal identifier
     * @param size the unescaped payload size in {@link #rbsp}
     */
    private void slice(int type, int layerId, int temporalId, int size) {
        var state = layer(layerId);
        var sameLayer = current != null && currentLayer == state;
        var header = HevcSliceHeader.parse(reader, type, temporalId, ppsTable, spsTable,
                independent != null && sameLayer ? independent : null, sliceLayer(layerId));
        if (header.firstSliceSegmentInPic && !continuesPlanes(header, state)) {
            finishPictureDeferred();
            startPicture(state, header);
        } else if (current == null || skipping || currentLayer != state) {
            return;
        }
        if (skipping) {
            return;
        }
        if (header.sps.separateColorPlane) {
            if (current.planes == null) {
                throw new MediaException.Malformed("separate color plane slice in a picture coded without them");
            }
            planesSeen |= 1 << header.colorPlaneId;
        } else if (current.planes != null) {
            throw new MediaException.Malformed("slice without color planes in a picture coded with them");
        }
        if (header.sps != current.sps && !header.sps.sameLayout(current.sps)) {
            throw new MediaException.Malformed("slice of a picture refers to another sequence parameter set");
        }
        if (!header.dependentSliceSegment) {
            independent = header;
        }
        if (!header.isIntra()) {
            current.intraOnly = false;
        }
        activate(header.pps, header.sps);
        var unsupported = header.pps.unsupportedFeature();
        if (unsupported != null) {
            throw new MediaException.Unsupported(unsupported, "the stream uses " + unsupported);
        }
        if (header.numActiveRefLayerPics != activeRefLayerPics) {
            throw new MediaException.Malformed("slices of a picture differ in their inter-layer reference pictures");
        }
        HevcFrame[][] lists = null;
        if (!header.isIntra()) {
            buildRefLists(header);
            lists = refList;
        }
        var sliceIndex = recordSlice(header);
        if (lists != null) {
            var plane = header.colorPlaneId;
            lists = new HevcFrame[2][16];
            for (var list = 0; list < 2; list++) {
                for (var i = 0; i < header.numRefIdx[list]; i++) {
                    lists[list][i] = refList[list][i].plane(plane);
                }
            }
        }
        currentJob.add(new PictureJob.Slice(header, lists, sliceIndex, Arrays.copyOf(rbsp, size + H264BitReader.PADDING),
                size, parallel ? substreams(header, size) : null));
    }

    /**
     * {@code NumActiveRefLayerPics} of the current picture.
     */
    private int activeRefLayerPics;

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
                    // the current picture and the inter-layer references are marked as long-term references
                    // while the picture is decoded (8.1.3, H.8.1.3)
                    longTerm[list * 16 + i] = ref == current || ref.longTerm || isInterLayer(ref);
                    ids[list * 16 + i] = ref.id;
                }
            }
        }
        return current.plane(header.colorPlaneId).addSlice(poc, longTerm, ids);
    }

    /**
     * Returns whether a picture is an inter-layer reference picture of the current picture.
     *
     * @param frame the picture
     * @return {@code true} when one of the inter-layer reference picture sets holds it
     */
    private boolean isInterLayer(HevcFrame frame) {
        for (var list = 0; list < 2; list++) {
            for (var i = 0; i < interLayerCount[list]; i++) {
                if (interLayer[list][i] == frame) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether a slice segment flagged as the first of its picture continues the current
     * picture instead: a picture coded with separate color planes whose first slice segment of a
     * plane not seen yet carries the picture order count and NAL unit type of the current picture.
     *
     * @param header the slice segment header
     * @param state the layer of the slice segment
     * @return {@code true} when the slice segment belongs to the current picture
     */
    private boolean continuesPlanes(HevcSliceHeader header, LayerState state) {
        return current != null && currentLayer == state && !skipping && current.planes != null
                && header.sps.separateColorPlane
                && (planesSeen & (1 << header.colorPlaneId)) == 0 && header.nalType == currentNalType
                && header.pocLsb == currentPocLsb
                && (header.sps == current.sps || header.sps.sameLayout(current.sps));
    }

    /**
     * The color planes of the current picture that received a slice segment, bit
     * {@code colour_plane_id}.
     */
    private int planesSeen;

    /**
     * The NAL unit type of the first slice segment of the current picture.
     */
    private int currentNalType;

    /**
     * {@code slice_pic_order_cnt_lsb} of the current picture.
     */
    private int currentPocLsb;

    /**
     * Starts decoding a picture: activates its parameter sets, derives its picture order count,
     * applies its reference picture set and outputs the pictures the decoded picture buffer no
     * longer holds back (C.5.2.2).
     *
     * @param state the layer of the picture
     * @param header the header of the picture's first slice segment
     */
    private void startPicture(LayerState state, HevcSliceHeader header) {
        if (multiLayer) {
            startLayerPicture(state, header);
            return;
        }
        var type = header.nalType;
        var sps = header.sps;
        var sei = state.sei;
        var irap = HevcNal.isIrap(type);
        var noRaslOutput = false;
        if (irap) {
            noRaslOutput = HevcNal.isIdr(type) || HevcNal.isBla(type) || state.firstPicture || state.afterEndOfSequence;
        }
        var halfPocLsb = 1 << (sps.log2MaxPocLsb - 1);
        var recoveryPoint = sei.recoveryPoint && sei.recoveryPocCount >= -halfPocLsb
                && sei.recoveryPocCount < halfPocLsb;
        var recoveryCount = recoveryPoint ? sei.recoveryPocCount : 0;
        var exactMatch = recoveryPoint && sei.recoveryExactMatch;
        var messages = takeMessages(sei);
        skipping = false;
        var recoveryStart = false;
        if (!state.started && !irap) {
            if (!recoveryPoint && !startAnywhere || HevcNal.isRasl(type)) {
                skipping = true;
                return;
            }
            recoveryStart = true;
        }
        if (HevcNal.isRasl(type) && state.irapNoRaslOutput) {
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
            state.started = true;
            state.irapNoRaslOutput = noRaslOutput;
        } else if (recoveryStart) {
            state.started = true;
            // the leading pictures of an IRAP picture that was never decoded cannot be decoded
            state.irapNoRaslOutput = true;
        }
        if (state.activeSps != sps) {
            if (state.activeSps != null && !(irap && noRaslOutput) && !sps.sameLayout(state.activeSps)) {
                throw new MediaException.Malformed("sequence parameter set changed inside a coded video sequence");
            }
            state.activeSps = sps;
            activeSpsByLayer[0] = sps;
        }
        var maxPocLsb = 1 << sps.log2MaxPocLsb;
        int poc;
        if (irap && noRaslOutput || recoveryStart) {
            poc = header.pocLsb;
        } else {
            poc = currMsb(header.pocLsb, state.prevTid0Poc, maxPocLsb) + header.pocLsb;
        }
        // the picture decoding starts at anchors the picture order counts that follow it
        if (recoveryStart || header.temporalId == 0 && !HevcNal.isRasl(type) && !HevcNal.isRadl(type)
                && !HevcNal.isSubLayerNonReference(type)) {
            state.prevTid0Poc = poc;
        }
        Arrays.fill(interLayerCount, 0);
        activeRefLayerPics = 0;
        applyReferencePictureSet(state, header, poc, irap && noRaslOutput);
        var highest = sps.maxSubLayers - 1;
        if (irap && noRaslOutput && !state.firstPicture) {
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
            removeUnused(-1);
            while (needsBumping(sps, highest) || dpb.size() >= sps.maxDecPicBuffering[highest]) {
                if (!bump()) {
                    break;
                }
            }
        }
        state.firstPicture = false;
        state.afterEndOfSequence = false;
        if (irap && noRaslOutput) {
            state.recovering = false;
        } else if (recoveryStart) {
            state.recovering = true;
            state.recoveryPoc = poc + recoveryCount;
            state.recoveryExact = exactMatch;
        }
        var beforeRecovery = state.recovering && poc < state.recoveryPoc;
        picOutput = header.picOutput && !beforeRecovery;
        hashVerifiable = !state.recovering || state.recoveryExact && !beforeRecovery;
        var newSequence = irap && noRaslOutput || recoveryStart;
        if (newSequence && !messages.transferPending()) {
            // an alternative transfer characteristics message lasts until the end of its sequence
            sei.preferredTransfer = -1;
        }
        var frame = newPicture(state, header, sps, poc, irap, recoveryPoint, newSequence, messages);
        if (dpb.size() >= MAX_DPB) {
            throw new MediaException.Malformed("decoded picture buffer overflow");
        }
        current = frame;
    }

    /**
     * The messages of the SEI collector that apply to the next picture, taken when the picture
     * starts.
     *
     * @param timing the first byte of the picture timing message, or -1
     * @param filmGrain the film grain characteristics message, or {@code null}
     * @param framePacking the frame packing arrangement message, or {@code null}
     * @param orientation the display orientation message, or {@code null}
     * @param transferPending whether an alternative transfer characteristics message arrived
     * @param alphaPending whether an alpha channel information message arrived
     */
    private record Messages(int timing, FilmGrainParameters filmGrain, DisplaySei.FramePackingMessage framePacking,
                            DisplaySei.OrientationMessage orientation, byte[] captions,
                            boolean transferPending, boolean alphaPending) {
    }

    /**
     * Takes the messages that apply to the next picture of a layer and clears them from its
     * collector.
     *
     * @param sei the collector of the layer
     * @return the messages
     */
    private static Messages takeMessages(HevcSei sei) {
        var messages = new Messages(sei.pictureTiming, sei.filmGrain, sei.framePacking, sei.orientation,
                sei.captions.size() == 0 ? null : sei.captions.toByteArray(), sei.transferPending,
                sei.alphaChannelPending);
        sei.clearPictureMessages();
        return messages;
    }

    /**
     * Creates the picture a first slice segment starts, with its job.
     *
     * @param state the layer of the picture
     * @param header the header of the picture's first slice segment
     * @param sps the sequence parameter set of the picture
     * @param poc {@code PicOrderCntVal}
     * @param irap whether the picture is an IRAP picture
     * @param recoveryPoint whether its access unit carried a usable recovery point SEI message
     * @param newSequence whether the picture starts a coded video sequence
     * @param messages the SEI messages that apply to the picture
     * @return the picture
     */
    private HevcFrame newPicture(LayerState state, HevcSliceHeader header, HevcSps sps, int poc, boolean irap,
                                 boolean recoveryPoint, boolean newSequence, Messages messages) {
        var frame = obtainFrame(sps);
        frame.reset(sps.sizeInCtbs);
        frame.id = nextId++;
        frame.poc = poc;
        frame.layerId = state.id;
        frame.viewId = planVps != null ? planVps.viewId[state.id] : 0;
        frame.auxId = planVps != null ? planVps.auxId[state.id] : 0;
        frame.layered = layeredOutput;
        frame.irap = irap;
        frame.recoveryPoint = recoveryPoint;
        frame.picStruct = sps.frameFieldInfoPresent && messages.timing() >= 0 ? messages.timing() >> 4 : -1;
        frame.newSequence = newSequence;
        frame.filmGrain = messages.filmGrain();
        frame.framePacking = messages.framePacking();
        frame.orientation = messages.orientation();
        frame.closedCaptions = messages.captions();
        if (newSequence && !messages.alphaPending()) {
            state.sei.alphaChannel = null;
        }
        frame.alphaChannel = state.sei.alphaChannel;
        var base = layers[0];
        if (frame.alphaChannel == null && frame.auxId == HevcVps.AUX_ALPHA && base != null) {
            // an alpha channel information message carried in a NAL unit of the base layer
            frame.alphaChannel = base.sei.alphaChannel;
        }
        frame.discardable = header.discardable;
        frame.sps = sps;
        frame.pts = packetPts;
        frame.duration = packetDuration;
        frame.color = colorInfo(state, sps);
        frame.syncPlanes();
        currentLayer = state;
        currentNalType = header.nalType;
        currentPocLsb = header.pocLsb;
        planesSeen = 0;
        var states = new PictureState[sps.separateColorPlane ? 3 : 1];
        for (var k = 0; k < states.length; k++) {
            states[k] = obtainState(sps);
            states[k].reset();
        }
        frame.beginDecoding();
        currentJob = new PictureJob(frame, states, ++submittedJobs, idleDecoders, parallel);
        currentJob.scratches = idleScratches;
        independent = null;
        pendingHash = -1;
        state.sei.hashType = -1;
        return frame;
    }

    /**
     * Returns {@code PicOrderCntMsb} from the least significant bits of a picture order count and
     * the previous picture order count (equations 8-27 and F-60, {@code GetCurrMsb}).
     *
     * @param lsb {@code slice_pic_order_cnt_lsb}
     * @param previous {@code PrevPicOrderCnt}
     * @param maxPocLsb {@code MaxPicOrderCntLsb}
     * @return the most significant bits
     */
    private static int currMsb(int lsb, int previous, int maxPocLsb) {
        var prevLsb = previous & (maxPocLsb - 1);
        var prevMsb = previous - prevLsb;
        return getCurrMsb(lsb, prevLsb, prevMsb, maxPocLsb);
    }

    /**
     * {@code GetCurrMsb(cl, pl, pm, ml)} (equation F-60).
     *
     * @param cl the current least significant bits
     * @param pl the previous least significant bits
     * @param pm the previous most significant bits
     * @param ml {@code MaxPicOrderCntLsb}
     * @return the current most significant bits
     */
    private static int getCurrMsb(int cl, int pl, int pm, int ml) {
        if (pl - cl >= ml / 2) {
            return pm + ml;
        }
        if (cl - pl > ml / 2) {
            return pm - ml;
        }
        return pm;
    }

    /**
     * Starts decoding a picture of a multi-layer stream (F.8.1.3 to F.8.1.5): ends the access unit
     * in progress when the picture starts a new one, derives {@code NoRaslOutputFlag} and the
     * layer initialization, the picture order count of F.8.3.1, the reference picture set of the
     * layer, the inter-layer reference picture set, and outputs the pictures the decoded picture
     * buffer no longer holds back (F.13.5.2.2).
     *
     * @param state the layer of the picture
     * @param header the header of the picture's first slice segment
     */
    private void startLayerPicture(LayerState state, HevcSliceHeader header) {
        var vps = planVps;
        var layerId = state.id;
        var type = header.nalType;
        var sps = header.sps;
        var sei = state.sei;
        skipping = false;
        if (accessUnitLayer >= 0 && layerId <= accessUnitLayer) {
            endAccessUnitPictures();
        }
        var firstInAccessUnit = accessUnitLayer < 0;
        if (firstInAccessUnit && layerId > 0 && !vps.baseLayerInternal) {
            provideExternalBase(vps, header);
        }
        var irap = HevcNal.isIrap(type);
        var smallest = layerId == smallestLayerId(vps);
        var directRefs = vps.idDirectRefLayer[layerId];
        if (irap && smallest) {
            var noClrasOutput = state.firstPicture || state.afterEndOfSequence || HevcNal.isBla(type)
                    || HevcNal.isIdr(type) && header.crossLayerBla;
            if (noClrasOutput) {
                for (var other : layers) {
                    if (other != null) {
                        other.started = false;
                        other.firstPicDecoded = false;
                    }
                }
                if (externalBase != null && !vps.baseLayerInternal) {
                    layer(0).started = true;
                    layer(0).firstPicDecoded = true;
                }
            }
            noClrasOutputFlag = noClrasOutput;
        }
        var layerReset = false;
        if (irap && !smallest && (state.afterEndOfSequence || HevcNal.isBla(type)
                || HevcNal.isIdr(type) && header.crossLayerBla)) {
            layerReset = true;
            resetPredictedLayers(vps, layerId);
        }
        var noRaslOutput = false;
        if (irap) {
            if (HevcNal.isIdr(type) || HevcNal.isBla(type) || state.firstPicture || state.afterEndOfSequence) {
                noRaslOutput = true;
            } else {
                noRaslOutput = !state.started && referencesStarted(directRefs);
            }
        }
        var halfPocLsb = 1 << (sps.log2MaxPocLsb - 1);
        var recoveryPoint = sei.recoveryPoint && sei.recoveryPocCount >= -halfPocLsb
                && sei.recoveryPocCount < halfPocLsb;
        var recoveryCount = recoveryPoint ? sei.recoveryPocCount : 0;
        var exactMatch = recoveryPoint && sei.recoveryExactMatch;
        var messages = takeMessages(sei);
        var recoveryStart = false;
        if (irap && noRaslOutput && (layerId == 0 || !state.started && (directRefs.length == 0
                || referencesStarted(directRefs)))) {
            state.started = true;
        } else if (!state.started && !irap && layerId == 0 && (recoveryPoint || startAnywhere)
                && !HevcNal.isRasl(type)) {
            recoveryStart = true;
            state.started = true;
        }
        if (!state.started || HevcNal.isRasl(type) && state.irapNoRaslOutput && !irap) {
            // pictures of a layer not initialized yet (CL-RAS pictures) and skipped RASL pictures are not
            // output and do not affect the pictures that are
            skipping = true;
            state.firstPicture = false;
            state.afterEndOfSequence = false;
            return;
        }
        if (irap) {
            state.irapNoRaslOutput = noRaslOutput;
        } else if (recoveryStart) {
            state.irapNoRaslOutput = true;
        }
        var unsupported = sps.unsupportedFeature();
        if (unsupported != null) {
            throw new MediaException.Unsupported(unsupported, "the stream uses " + unsupported);
        }
        LimitChecks.checkPicture(limits, sps.width, sps.height);
        activate(header.pps, sps);
        if (state.activeSps != sps) {
            if (state.activeSps != null && !(irap && noRaslOutput) && !sps.sameLayout(state.activeSps)) {
                throw new MediaException.Malformed("sequence parameter set changed inside a coded video sequence");
            }
            state.activeSps = sps;
            activeSpsByLayer[layerId] = sps;
        }
        var resetting = isPocResetting(state, header);
        if (resetting) {
            // the pictures of earlier access units are output before the reset changes their order
            outputBefore();
        }
        var poc = layerPoc(state, header, recoveryStart, resetting);
        if (!firstInAccessUnit && poc != accessUnitPoc && header.pocResetIdc == 0) {
            // a layer whose picture is missing from the access unit: the picture starts the next one
            endAccessUnitPictures();
            firstInAccessUnit = true;
        }
        if (externalPending != null) {
            externalPending.poc = poc;
            externalPending = null;
        }
        Arrays.fill(interLayerCount, 0);
        activeRefLayerPics = header.numActiveRefLayerPics;
        applyReferencePictureSet(state, header, poc, irap && noRaslOutput);
        if (irap && smallest && (noClrasOutputFlag || newVps)) {
            for (var frame : dpb) {
                if (frame.layerId != layerId) {
                    frame.shortTerm = false;
                    frame.longTerm = false;
                }
            }
        }
        bumpBeforeDecoding(state, header, irap && noRaslOutput, smallest, layerReset);
        state.firstPicture = false;
        state.afterEndOfSequence = false;
        if (irap && smallest) {
            newVps = false;
        }
        if (irap && noRaslOutput) {
            state.recovering = false;
        } else if (recoveryStart) {
            state.recovering = true;
            state.recoveryPoc = poc + recoveryCount;
            state.recoveryExact = exactMatch;
        }
        var beforeRecovery = state.recovering && poc < state.recoveryPoc;
        picOutput = header.picOutput && !beforeRecovery;
        hashVerifiable = !state.recovering || state.recoveryExact && !beforeRecovery;
        var newSequence = irap && noRaslOutput || recoveryStart;
        if (newSequence && !messages.transferPending()) {
            sei.preferredTransfer = -1;
        }
        var frame = newPicture(state, header, sps, poc, irap, recoveryPoint, newSequence, messages);
        frame.picOutputFlag = picOutput;
        activePpsByLayer[layerId] = header.pps;
        current = frame;
        accessUnitLayer = layerId;
        accessUnitPoc = poc;
        if (layerSize(layerId) >= MAX_DPB) {
            throw new MediaException.Malformed("decoded picture buffer overflow");
        }
        if (header.numActiveRefLayerPics > 0) {
            buildInterLayerSet(state, header, frame);
        }
    }

    /**
     * Whether the IRAP picture of the smallest layer being started has {@code NoClrasOutputFlag}
     * set.
     */
    private boolean noClrasOutputFlag;

    /**
     * Returns {@code SmallestLayerId} (F.8.1.2): 0 for an output layer set of a layer set the
     * base part of the video parameter set defines, whether the base layer is coded in the stream
     * or outside it, and the smallest layer of an additional layer set otherwise.
     *
     * @param vps the active video parameter set
     * @return the identifier of the smallest layer
     */
    private int smallestLayerId(HevcVps vps) {
        var lsIdx = vps.olsIdxToLsIdx[targetOls];
        if (lsIdx < vps.numBaseLayerSets) {
            return 0;
        }
        var list = vps.layerSetLayerIdList[lsIdx];
        for (var j = 0; j < list.length; j++) {
            if (vps.necessaryLayerFlag[targetOls][j]) {
                return list[j];
            }
        }
        return 0;
    }

    /**
     * Returns whether every direct reference layer of a layer has started decoding
     * ({@code LayerInitializedFlag} of each is set).
     *
     * @param directRefs the identifiers of the direct reference layers
     * @return {@code true} when they all have, or there are none
     */
    private boolean referencesStarted(int[] directRefs) {
        for (var reference : directRefs) {
            var other = layers[reference];
            if (other == null || !other.started) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resets a layer and the layers it predicts (equation F-58, {@code LayerResetFlag}): their
     * decoding restarts at their next IRAP pictures and their pictures are marked unused for
     * reference.
     *
     * @param vps the active video parameter set
     * @param layerId the identifier of the layer, {@code dolLayerId}
     */
    private void resetPredictedLayers(HevcVps vps, int layerId) {
        for (var predicted : vps.idPredictedLayer[layerId]) {
            var other = layers[predicted];
            if (other != null) {
                other.started = false;
                other.firstPicDecoded = false;
            }
        }
        for (var frame : dpb) {
            if (frame.layerId == layerId || contains(vps.idPredictedLayer[layerId], frame.layerId)) {
                frame.shortTerm = false;
                frame.longTerm = false;
            }
        }
    }

    /**
     * Returns whether an array holds a value.
     *
     * @param values the layer identifiers to search
     * @param value the layer identifier to find
     * @return {@code true} when one entry equals {@code value}
     */
    private static boolean contains(int[] values, int value) {
        for (var candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether a picture is a POC resetting picture (F.3.44): the first picture of its layer
     * in a POC resetting period, which starts with an access unit whose {@code poc_reset_idc} is 1
     * or 2.
     *
     * @param state the layer of the picture
     * @param header the header of the picture's first slice segment
     * @return {@code true} for a POC resetting picture
     */
    private static boolean isPocResetting(LayerState state, HevcSliceHeader header) {
        return header.pocResetIdc != 0 && header.pocResetPeriodId != state.pocResetPeriod;
    }

    /**
     * Derives the picture order count of a picture of a multi-layer stream (F.8.3.1), applying a
     * POC reset to the pictures of the affected layers in the decoded picture buffer, and updates
     * {@code PrevPicOrderCnt}.
     *
     * @param state the layer of the picture
     * @param header the header of the picture's first slice segment
     * @param recoveryStart whether decoding starts at this picture through a recovery point
     * @param resetting whether the picture is a POC resetting picture
     * @return {@code PicOrderCntVal}
     */
    private int layerPoc(LayerState state, HevcSliceHeader header, boolean recoveryStart, boolean resetting) {
        var vps = planVps;
        var sps = header.sps;
        var type = header.nalType;
        var maxPocLsb = 1 << sps.log2MaxPocLsb;
        var layerId = state.id;
        if (resetting && header.pocResetPeriodId != resetPeriod) {
            // the first picture of a new POC resetting period among all layers
            resetPeriod = header.pocResetPeriodId;
            for (var other : layers) {
                if (other != null) {
                    other.pocDecremented = false;
                }
            }
        }
        var resettingFlag = resetting && (!vps.pocLsbAligned || !state.pocDecremented);
        var affected = vps.pocLsbAligned ? withPredicted(vps, layerId) : new int[]{layerId};
        int poc;
        if (resettingFlag) {
            if (state.firstPicDecoded) {
                var pocLsbVal = header.pocResetIdc == 3 ? header.pocLsbVal : header.pocLsb;
                long msbDelta;
                if (header.pocMsbCycleValPresent) {
                    msbDelta = header.pocMsbCycleVal * maxPocLsb;
                } else {
                    msbDelta = currMsb(pocLsbVal, state.prevTid0Poc, maxPocLsb);
                }
                var lsbDelta = header.pocResetIdc == 2 || header.pocResetIdc == 3 && header.fullPocReset ? pocLsbVal : 0;
                var delta = (int) (msbDelta + lsbDelta);
                if (delta != 0) {
                    if (parallel) {
                        awaitAllJobs();
                    }
                    for (var frame : dpb) {
                        var other = layers[frame.layerId];
                        if (contains(affected, frame.layerId) && (other == null || !other.pocDecremented)) {
                            frame.decrementPoc(delta);
                        }
                    }
                    for (var frame : accessUnit) {
                        if (!dpb.contains(frame) && contains(affected, frame.layerId)) {
                            var other = layers[frame.layerId];
                            if (other == null || !other.pocDecremented) {
                                frame.decrementPoc(delta);
                            }
                        }
                    }
                }
                for (var id : affected) {
                    layer(id).pocDecremented = true;
                }
            }
            if (header.pocResetIdc == 1) {
                poc = header.pocLsb;
            } else if (header.pocResetIdc == 2) {
                poc = 0;
            } else {
                poc = getCurrMsb(header.pocLsb, header.fullPocReset ? 0 : header.pocLsbVal, 0, maxPocLsb) + header.pocLsb;
            }
        } else {
            int msb;
            if (header.pocMsbCycleValPresent) {
                msb = (int) (header.pocMsbCycleVal * maxPocLsb);
            } else if (!state.firstPicDecoded || HevcNal.isIdr(type) || recoveryStart) {
                msb = 0;
            } else {
                msb = currMsb(header.pocLsb, state.prevTid0Poc, maxPocLsb);
            }
            poc = msb + header.pocLsb;
        }
        var anchor = header.temporalId == 0 && !HevcNal.isRasl(type) && !HevcNal.isRadl(type)
                && !HevcNal.isSubLayerNonReference(type) && !header.discardable;
        for (var id : affected) {
            var target = layer(id);
            if (anchor || recoveryStart && id == layerId) {
                target.prevTid0Poc = poc;
            } else if (header.pocResetIdc == 3 && (!state.firstPicDecoded || resetting)) {
                target.prevTid0Poc = header.fullPocReset ? 0 : header.pocLsbVal;
            }
        }
        if (header.pocResetIdc != 0 || header.pocResetPeriodId >= 0) {
            state.pocResetPeriod = header.pocResetPeriodId;
        }
        return poc;
    }

    /**
     * Returns a layer with the layers it predicts.
     *
     * @param vps the active video parameter set
     * @param layerId the identifier of the layer
     * @return the identifiers
     */
    private static int[] withPredicted(HevcVps vps, int layerId) {
        var predicted = vps.idPredictedLayer[layerId];
        var result = Arrays.copyOf(predicted, predicted.length + 1);
        result[predicted.length] = layerId;
        return result;
    }

    /**
     * Outputs the pictures of earlier access units still needed for output before a POC
     * resetting picture changes the picture order counts (F.13.5.2.2).
     */
    private void outputBefore() {
        while (true) {
            HevcFrame first = null;
            for (var frame : dpb) {
                if (frame.neededForOutput && !accessUnit.contains(frame) && (first == null || frame.poc < first.poc)) {
                    first = frame;
                }
            }
            if (first == null || !bump()) {
                return;
            }
        }
    }

    /**
     * Supplies the base layer picture of the access unit a picture of a layer above the base
     * layer starts, for a stream whose base layer is coded outside it (F.8.1.8 and F.8.1.9).
     *
     * @param vps the active video parameter set
     * @param header the header of the first slice segment of the access unit's first picture
     */
    private void provideExternalBase(HevcVps vps, HevcSliceHeader header) {
        if (externalBase == null) {
            throw new MediaException.Unsupported("hevc external base layer",
                    "the stream codes its base layer outside it, with another codec");
        }
        var format = vps.repFormatOf(0);
        if (format == null) {
            throw new MediaException.Malformed("external base layer without a representation format");
        }
        if (parallel) {
            // the previous external picture may still be read by the pictures in flight
            awaitAllJobs();
        }
        var picture = externalBase.next(format, header.pocLsb);
        if (picture == null) {
            return;
        }
        var base = layer(0);
        if (picture.irap()) {
            var first = base.firstPicture;
            var noClras = first || picture.nalType() == HevcNal.BLA_W_LP;
            if (noClras) {
                for (var other : layers) {
                    if (other != null && other.id > 0) {
                        other.started = false;
                        other.firstPicDecoded = false;
                    }
                }
            }
        }
        base.started = true;
        base.firstPicDecoded = true;
        base.firstPicture = false;
        // the sub-DPB of the base layer holds the picture of the current access unit only
        for (var i = dpb.size() - 1; i >= 0; i--) {
            var frame = dpb.get(i);
            if (frame.layerId == 0) {
                dpb.remove(i);
                release(frame);
            }
        }
        var frame = picture.frame();
        var sps = baseLayerSps(vps, frame);
        frame.reset(sps.sizeInCtbs);
        Arrays.fill(frame.ctbSlice, -1);
        frame.sps = sps;
        frame.layerId = 0;
        frame.irap = picture.irap();
        frame.id = nextId++;
        frame.longTerm = true;
        frame.shortTerm = false;
        frame.neededForOutput = false;
        frame.picOutputFlag = false;
        frame.externalPoc = true;
        dpb.add(frame);
        accessUnit.add(frame);
        externalPending = frame;
    }

    /**
     * The external base layer picture of the access unit in progress whose picture order count is
     * set by the first picture of the access unit (F.8.1.9), or {@code null}.
     */
    private HevcFrame externalPending;

    /**
     * Derives the inter-layer reference picture set of the current picture (G.8.1.3 and H.8.1.3):
     * the picture of each active reference layer in the access unit, or the inter-layer reference
     * picture derived from it (H.8.1.4).
     *
     * @param state the layer of the picture
     * @param header the header of the picture's first slice segment
     * @param frame the current picture
     * @throws MediaException.Malformed if an active reference layer has no picture in the access
     * unit
     */
    private void buildInterLayerSet(LayerState state, HevcSliceHeader header, HevcFrame frame) {
        var vps = planVps;
        var layerId = state.id;
        var currentView = vps.viewId[layerId];
        var baseView = vps.viewId[0];
        var layerIdx = vps.layerIndex(layerId);
        for (var i = 0; i < header.numActiveRefLayerPics; i++) {
            var refLayerId = header.refPicLayerId[i];
            var refView = vps.viewId[refLayerId];
            var list = currentView <= baseView && currentView <= refView || currentView >= baseView && currentView >= refView
                    ? 0 : 1;
            HevcFrame picture = null;
            for (var candidate : accessUnit) {
                if (candidate.layerId == refLayerId) {
                    picture = candidate;
                }
            }
            if (picture == null) {
                throw new MediaException.Malformed("an active reference layer has no picture in the access unit");
            }
            var refLayerIdx = vps.layerIndex(refLayerId);
            var samples = refLayerIdx >= 0 && vps.samplePrediction[layerIdx][refLayerIdx];
            var motion = refLayerIdx >= 0 && vps.motionPrediction[layerIdx][refLayerIdx];
            var location = header.pps.refLocations[refLayerId];
            var geometry = InterLayerPicture.geometry(header.sps, picture.sps,
                    location != null ? location : HevcPps.RefLocation.NONE);
            if (geometry == null) {
                throw new MediaException.Malformed("reference layer offsets leave no reference region");
            }
            var mapping = header.pps.colourMapping != null && header.pps.colourMapping.appliesTo(refLayerId)
                    ? header.pps.colourMapping : null;
            HevcFrame reference;
            if (InterLayerPicture.identity(geometry, header.sps, picture.sps, mapping != null)) {
                reference = picture;
            } else {
                reference = obtainInterLayerFrame(header.sps);
                reference.reset(header.sps.sizeInCtbs);
                reference.id = nextId++;
                reference.poc = picture.poc;
                reference.layerId = refLayerId;
                reference.sps = header.sps;
                reference.syncPlanes();
                currentJob.interLayers.add(new PictureJob.InterLayer(picture, reference, geometry, mapping, samples,
                        motion));
            }
            interLayer[list][interLayerCount[list]++] = reference;
        }
    }

    /**
     * Returns the sequence parameter set describing an external base layer picture: the
     * representation format of the base layer, with the coding block sizes of the current picture.
     *
     * @param vps the active video parameter set
     * @param frame the external picture
     * @return the set
     */
    private HevcSps baseLayerSps(HevcVps vps, HevcFrame frame) {
        var cached = externalSps;
        if (cached != null && cached.width == frame.width && cached.height == frame.height
                && cached.chromaFormatIdc == frame.chromaFormat) {
            return cached;
        }
        externalSps = HevcSps.external(vps.repFormatOf(0));
        if (externalSps.width != frame.width || externalSps.height != frame.height
                || externalSps.chromaFormatIdc != frame.chromaFormat) {
            throw new MediaException.Malformed("external base layer picture does not match its representation format");
        }
        return externalSps;
    }

    /**
     * The sequence parameter set describing the external base layer pictures, or {@code null}.
     */
    private HevcSps externalSps;

    /**
     * Returns an inter-layer reference picture buffer for a sequence parameter set, reusing a
     * pooled one when it fits.
     *
     * @param sps the sequence parameter set of the current picture
     * @return the picture
     */
    private HevcFrame obtainInterLayerFrame(HevcSps sps) {
        for (var i = interLayerPool.size() - 1; i >= 0; i--) {
            var frame = interLayerPool.remove(i);
            if (frame.fits(sps)) {
                return frame;
            }
        }
        LimitChecks.checkAllocation(limits, (long) sps.width * sps.height * 2 * 2);
        return new HevcFrame(sps.width, sps.height, sps.chromaFormatIdc, HevcFrame.eightBit(sps),
                sps.separateColorPlane);
    }

    /**
     * Outputs and removes pictures before the decoding of a picture of a multi-layer stream
     * (F.13.5.2.2).
     *
     * @param state the layer of the picture
     * @param header the header of the picture's first slice segment
     * @param resetIrap whether the picture is an IRAP picture with {@code NoRaslOutputFlag}
     * @param smallest whether the picture belongs to the smallest layer
     * @param layerReset {@code LayerResetFlag}
     */
    private void bumpBeforeDecoding(LayerState state, HevcSliceHeader header, boolean resetIrap, boolean smallest,
                                    boolean layerReset) {
        var vps = planVps;
        var layerId = state.id;
        var independentLayer = vps.idDirectRefLayer[layerId].length == 0;
        if (resetIrap && (smallest || independentLayer) && !state.firstPicture) {
            int[] toEmpty;
            if (smallest && (noClrasOutputFlag || newVps)) {
                toEmpty = null;
            } else if (!smallest && independentLayer && layerReset) {
                toEmpty = withPredicted(vps, layerId);
            } else {
                toEmpty = new int[]{layerId};
            }
            var noOutputOfPriorPics = header.nalType == HevcNal.CRA_NUT || header.noOutputOfPriorPics;
            if (!noOutputOfPriorPics) {
                while (true) {
                    var waiting = false;
                    for (var frame : dpb) {
                        if (frame.neededForOutput && (toEmpty == null || contains(toEmpty, frame.layerId))) {
                            waiting = true;
                            break;
                        }
                    }
                    if (!waiting || !bump()) {
                        break;
                    }
                }
            }
            for (var i = dpb.size() - 1; i >= 0; i--) {
                var frame = dpb.get(i);
                var emptied = toEmpty == null || contains(toEmpty, frame.layerId);
                if (accessUnit.contains(frame) && frame.layerId != layerId) {
                    continue;
                }
                if (emptied || !frame.neededForOutput && !frame.isReference()) {
                    frame.neededForOutput = false;
                    dpb.remove(i);
                    release(frame);
                }
            }
            return;
        }
        removeUnused(layerId);
        var highest = maxSubLayers() - 1;
        var maxBuffering = maxDecPicBuffering(layerId, highest);
        while (accessUnitsNeedingOutput() > maxNumReorder(highest) || latencyExceeded(highest)
                || layerSize(layerId) >= maxBuffering) {
            if (!bump()) {
                break;
            }
        }
    }

    /**
     * {@return {@code HighestTid + 1}: the most sub-layers of the decoded layers}
     */
    private int maxSubLayers() {
        var vps = planVps;
        return vps != null ? vps.maxSubLayers : 7;
    }

    /**
     * Returns {@code MaxDecPicBufferingMinus1 + 1} of a layer: the size of its sub-DPB in the
     * target output layer set, or the one its active SPS gives.
     *
     * @param layerId the layer identifier
     * @param highest {@code HighestTid}
     * @return the size
     */
    private int maxDecPicBuffering(int layerId, int highest) {
        var vps = planVps;
        if (targetOls > 0 && vps.maxDecPicBuffering[targetOls] != null) {
            var list = vps.layerSetLayerIdList[vps.olsIdxToLsIdx[targetOls]];
            for (var k = 0; k < list.length; k++) {
                if (list[k] == layerId) {
                    var sizes = vps.maxDecPicBuffering[targetOls][k];
                    return sizes[Math.min(highest, sizes.length - 1)];
                }
            }
        }
        var sps = activeSpsByLayer[layerId];
        return sps != null ? sps.maxDecPicBuffering[Math.min(highest, sps.maxSubLayers - 1)] : MAX_DPB;
    }

    /**
     * Returns {@code MaxNumReorderPics}: the reordering limit of the target output layer set, or
     * the largest one the active SPSs of the decoded layers give.
     *
     * @param highest {@code HighestTid}
     * @return the most access units waiting for output
     */
    private int maxNumReorder(int highest) {
        var vps = planVps;
        if (targetOls > 0 && vps.maxNumReorder[targetOls] != null) {
            var values = vps.maxNumReorder[targetOls];
            return values[Math.min(highest, values.length - 1)];
        }
        var result = 0;
        for (var id = 0; id < 64; id++) {
            var sps = activeSpsByLayer[id];
            if (decodedLayers[id] && sps != null) {
                result = Math.max(result, sps.maxNumReorder[Math.min(highest, sps.maxSubLayers - 1)]);
            }
        }
        return result;
    }

    /**
     * Returns whether an access unit waiting for output reached the latency limit of the target
     * output layer set ({@code MaxLatencyValue}).
     *
     * @param highest {@code HighestTid}
     * @return {@code true} when a picture must be output
     */
    private boolean latencyExceeded(int highest) {
        var vps = planVps;
        int increase;
        int reorder;
        if (targetOls > 0 && vps.maxLatencyIncreasePlus1[targetOls] != null) {
            var values = vps.maxLatencyIncreasePlus1[targetOls];
            increase = values[Math.min(highest, values.length - 1)];
            reorder = maxNumReorder(highest);
        } else {
            var sps = activeSpsByLayer[0];
            if (sps == null) {
                return false;
            }
            var tid = Math.min(highest, sps.maxSubLayers - 1);
            increase = sps.maxLatencyIncreasePlus1[tid];
            reorder = sps.maxNumReorder[tid];
        }
        if (increase == 0) {
            return false;
        }
        var maxLatency = reorder + increase - 1;
        for (var frame : dpb) {
            if (frame.neededForOutput && frame.latencyCount >= maxLatency) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return the number of access units with a picture waiting for output}
     */
    private int accessUnitsNeedingOutput() {
        var count = 0;
        for (var i = 0; i < dpb.size(); i++) {
            var frame = dpb.get(i);
            if (!frame.neededForOutput) {
                continue;
            }
            var seen = false;
            for (var j = 0; j < i; j++) {
                var other = dpb.get(j);
                if (other.neededForOutput && other.poc == frame.poc) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                count++;
            }
        }
        return count;
    }

    /**
     * {@return the number of pictures of a layer in the decoded picture buffer}
     *
     * @param layerId the layer identifier
     */
    private int layerSize(int layerId) {
        var count = 0;
        for (var frame : dpb) {
            if (frame.layerId == layerId) {
                count++;
            }
        }
        return count;
    }

    /**
     * Ends the access unit in progress of a multi-layer stream (F.8.1.3 and F.13.5.2.3): finishes
     * its last picture, settles the {@code PicOutputFlag} of its pictures, marks those to output as
     * needed for output and outputs the pictures the reordering and latency limits release.
     */
    private void endAccessUnitPictures() {
        if (!multiLayer) {
            return;
        }
        finishPicture();
        if (accessUnit.isEmpty()) {
            accessUnitLayer = -1;
            return;
        }
        var vps = planVps;
        var outputLayerPresent = false;
        for (var frame : accessUnit) {
            if (outputLayers[frame.layerId] && !attachedAlpha[frame.layerId] && frame.picOutputFlag) {
                outputLayerPresent = true;
            }
        }
        if (vps.altOutputLayer[targetOls] && !outputLayerPresent) {
            // an alternative output layer: the highest reference layer picture of the output layer is output
            HevcFrame alternative = null;
            for (var frame : accessUnit) {
                if (frame.picOutputFlag && !attachedAlpha[frame.layerId]) {
                    alternative = frame;
                }
            }
            for (var frame : accessUnit) {
                frame.picOutputFlag = frame == alternative || attachedAlpha[frame.layerId] && frame.picOutputFlag;
            }
        } else {
            for (var frame : accessUnit) {
                if (!outputLayers[frame.layerId]) {
                    frame.picOutputFlag = false;
                }
            }
        }
        var anyOutput = false;
        for (var frame : accessUnit) {
            anyOutput |= frame.picOutputFlag;
        }
        if (anyOutput) {
            for (var other : dpb) {
                if (other.neededForOutput && other.poc > accessUnitPoc) {
                    other.latencyCount++;
                }
            }
        }
        for (var frame : accessUnit) {
            if (frame.picOutputFlag && dpb.contains(frame)) {
                frame.neededForOutput = true;
                frame.latencyCount = 0;
            }
        }
        accessUnit.clear();
        accessUnitLayer = -1;
        var highest = maxSubLayers() - 1;
        while (accessUnitsNeedingOutput() > maxNumReorder(highest) || latencyExceeded(highest)) {
            if (!bump()) {
                break;
            }
        }
    }

    /**
     * Applies the reference picture set of the current picture (8.3.2, F.8.3.2): marks the
     * long-term and unused pictures of its layer and fills {@link #rps}, generating stand-in
     * pictures for missing references the current picture uses.
     *
     * @param state the layer of the picture
     * @param header the slice segment header
     * @param poc {@code PicOrderCntVal} of the current picture
     * @param reset whether the picture is an IRAP picture with {@code NoRaslOutputFlag} set
     */
    private void applyReferencePictureSet(LayerState state, HevcSliceHeader header, int poc, boolean reset) {
        var layerId = state.id;
        if (reset) {
            for (var frame : dpb) {
                if (frame.layerId == layerId) {
                    frame.shortTerm = false;
                    frame.longTerm = false;
                }
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
            ltCurrFrames[i] = findLongTerm(layerId, ltCurr[i], ltCurrMsb[i], maxPocLsb);
        }
        var ltFollFrames = new HevcFrame[nLtFoll];
        for (var i = 0; i < nLtFoll; i++) {
            ltFollFrames[i] = findLongTerm(layerId, ltFoll[i], ltFollMsb[i], maxPocLsb);
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
            var frame = findShortTerm(layerId, poc + st.deltaS0[i]);
            if (frame != null) {
                keep.add(frame);
            }
            if (st.usedS0[i]) {
                rps[0][rpsCount[0]++] = frame != null ? frame : generate(layerId, sps, poc + st.deltaS0[i], keep);
            }
        }
        for (var i = 0; i < st.numPositive; i++) {
            var frame = findShortTerm(layerId, poc + st.deltaS1[i]);
            if (frame != null) {
                keep.add(frame);
            }
            if (st.usedS1[i]) {
                rps[1][rpsCount[1]++] = frame != null ? frame : generate(layerId, sps, poc + st.deltaS1[i], keep);
            }
        }
        for (var i = 0; i < nLtCurr; i++) {
            var frame = ltCurrFrames[i];
            if (frame == null) {
                frame = generate(layerId, sps, ltCurr[i], keep);
                frame.longTerm = true;
                frame.shortTerm = false;
            }
            rps[2][rpsCount[2]++] = frame;
        }
        for (var frame : dpb) {
            if (frame.layerId == layerId && frame.isReference() && !keep.contains(frame)) {
                frame.shortTerm = false;
                frame.longTerm = false;
            }
        }
    }

    /**
     * Finds a reference picture of a layer for a long-term entry.
     *
     * @param layerId the layer identifier
     * @param pocLt the entry's picture order count, or its least significant bits
     * @param msb whether the entry carries the most significant bits
     * @param maxPocLsb {@code MaxPicOrderCntLsb}
     * @return the picture, or {@code null}
     */
    private HevcFrame findLongTerm(int layerId, int pocLt, boolean msb, int maxPocLsb) {
        for (var frame : dpb) {
            if (frame.layerId == layerId && frame.isReference()
                    && (msb ? frame.poc == pocLt : (frame.poc & (maxPocLsb - 1)) == pocLt)) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Finds a short-term reference picture of a layer.
     *
     * @param layerId the layer identifier
     * @param poc the picture order count
     * @return the picture, or {@code null}
     */
    private HevcFrame findShortTerm(int layerId, int poc) {
        for (var frame : dpb) {
            if (frame.layerId == layerId && frame.shortTerm && frame.poc == poc) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Generates a stand-in for a missing reference picture (8.3.3.2): mid-gray samples, intra
     * motion, not output.
     *
     * @param layerId the layer of the missing picture
     * @param sps the active sequence parameter set
     * @param poc the picture order count of the missing picture
     * @param keep the pictures the reference picture set keeps, which receives the stand-in
     * @return the stand-in picture
     */
    private HevcFrame generate(int layerId, HevcSps sps, int poc, List<HevcFrame> keep) {
        if (layerSize(layerId) >= MAX_DPB) {
            throw new MediaException.Malformed("too many missing reference pictures");
        }
        var frame = obtainFrame(sps);
        frame.reset(sps.sizeInCtbs);
        Arrays.fill(frame.ctbSlice, -1);
        frame.id = nextId++;
        frame.poc = poc;
        frame.layerId = layerId;
        frame.sps = sps;
        frame.generated = true;
        frame.damaged = true;
        frame.shortTerm = true;
        frame.syncPlanes();
        if (frame.planes != null) {
            for (var plane : frame.planes) {
                Arrays.fill(plane.ctbSlice, -1);
            }
        }
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
     * Builds the reference picture lists of a P or B slice (8.3.4, F.8.3.4).
     *
     * @param header the slice segment header
     * @throws MediaException.Malformed if the picture has no reference pictures
     */
    private void buildRefLists(HevcSliceHeader header) {
        var self = header.pps.currPicRefEnabled;
        var total = rpsCount[0] + rpsCount[1] + rpsCount[2] + interLayerCount[0] + interLayerCount[1] + (self ? 1 : 0);
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
                for (var i = 0; i < interLayerCount[first] && r < count; i++) {
                    temp[r++] = interLayer[first][i];
                }
                for (var i = 0; i < rpsCount[second] && r < count; i++) {
                    temp[r++] = rps[second][i];
                }
                for (var i = 0; i < rpsCount[2] && r < count; i++) {
                    temp[r++] = rps[2][i];
                }
                for (var i = 0; i < interLayerCount[second] && r < count; i++) {
                    temp[r++] = interLayer[second][i];
                }
                if (self && r < count) {
                    temp[r++] = current;
                }
            }
            for (var i = 0; i < header.numRefIdx[list]; i++) {
                refList[list][i] = header.listModification[list] ? temp[header.listEntry[list][i]] : temp[i];
            }
            if (self && list == 0 && !header.listModification[0] && count > header.numRefIdx[0]) {
                refList[0][header.numRefIdx[0] - 1] = current;
            }
            for (var i = 0; i < header.numRefIdx[list]; i++) {
                if ((refList[list][i] == current) != header.currentPictureEntry[list][i]) {
                    throw new MediaException.Malformed("reference picture lists do not match the slice header");
                }
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
        return new HevcFrame(sps.width, sps.height, sps.chromaFormatIdc, HevcFrame.eightBit(sps),
                sps.separateColorPlane);
    }

    /**
     * Returns a picture to the pool.
     *
     * @param frame the picture
     */
    private void release(HevcFrame frame) {
        if (frame.externalPoc) {
            // an external base layer picture belongs to its provider
            return;
        }
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
     * Returns the inter-layer reference pictures of a finished job to their pool.
     *
     * @param job the finished picture job whose inter-layer pictures are no longer read
     */
    private void recycleInterLayers(PictureJob job) {
        for (var task : job.interLayers) {
            if (interLayerPool.size() < 2 * threads + 8) {
                interLayerPool.add(task.target());
            }
        }
        job.interLayers.clear();
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
            statePool.addAll(Arrays.asList(job.states));
            recycleInterLayers(job);
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
     *
     * @param layerId the layer whose pictures are removed, or -1 for every layer
     */
    private void removeUnused(int layerId) {
        for (var i = dpb.size() - 1; i >= 0; i--) {
            var frame = dpb.get(i);
            if ((layerId < 0 || frame.layerId == layerId) && !frame.neededForOutput && !frame.isReference()
                    && !accessUnit.contains(frame)) {
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
     * (C.5.2.4), or, for a multi-layer stream, every picture with that picture order count in
     * increasing layer order (F.13.5.2.4), an attached alpha picture with its primary picture.
     *
     * @return whether a picture was output
     */
    private boolean bump() {
        HevcFrame first = null;
        for (var frame : dpb) {
            if (frame.neededForOutput && (first == null || frame.poc < first.poc
                    || frame.poc == first.poc && frame.layerId < first.layerId)) {
                first = frame;
            }
        }
        if (first == null) {
            return false;
        }
        if (!multiLayer) {
            outputPicture(first);
            return true;
        }
        var group = bumpGroup;
        group.clear();
        for (var frame : dpb) {
            if (frame.neededForOutput && frame.poc == first.poc) {
                group.add(frame);
            }
        }
        group.sort(BY_LAYER);
        for (var frame : group) {
            if (attachedAlpha[frame.layerId]) {
                continue;
            }
            var alphaLayer = alphaLayerOf[frame.layerId];
            frame.alpha = null;
            if (alphaLayer >= 0) {
                for (var candidate : group) {
                    if (candidate.layerId == alphaLayer) {
                        frame.alpha = candidate;
                    }
                }
            }
        }
        for (var frame : group) {
            if (attachedAlpha[frame.layerId]) {
                frame.neededForOutput = false;
                if (parallel && isAttached(group, frame)) {
                    // the output queue reads it with its primary picture
                    frame.awaitingOutput = true;
                }
                if (!frame.isReference()) {
                    dpb.remove(frame);
                    release(frame);
                }
            }
        }
        for (var frame : group) {
            if (!attachedAlpha[frame.layerId]) {
                outputPicture(frame);
            }
        }
        group.clear();
        return true;
    }

    /**
     * The order of the pictures {@link #bump()} outputs together: by increasing layer identifier.
     */
    private static final Comparator<HevcFrame> BY_LAYER = Comparator.comparingInt(frame -> frame.layerId);

    /**
     * The pictures of one picture order count that {@link #bump()} outputs together, reused from
     * call to call.
     */
    private final List<HevcFrame> bumpGroup = new ArrayList<>();

    /**
     * Returns whether an alpha picture is attached to a primary picture output with it.
     *
     * @param group the pictures output together
     * @param alpha the alpha picture
     * @return {@code true} when a picture of {@code group} carries {@code alpha}
     */
    private static boolean isAttached(List<HevcFrame> group, HevcFrame alpha) {
        for (var frame : group) {
            if (frame.alpha == alpha) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hands one picture bumped out of the decoded picture buffer to the consumer, or, with several
     * threads, queues it until its decoding finishes.
     *
     * @param first the picture
     */
    private void outputPicture(HevcFrame first) {
        first.neededForOutput = false;
        first.outputPoc = first.poc;
        if (parallel) {
            first.awaitingOutput = true;
            outputQueue.addLast(first);
            if (!first.isReference()) {
                dpb.remove(first);
                release(first);
            }
            drainOutput(false);
            return;
        }
        if (!first.isReference()) {
            dpb.remove(first);
            release(first);
        }
        output.accept(first);
        first.alpha = null;
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
            var alpha = frame.alpha;
            if (!frame.isDone() || alpha != null && !alpha.isDone()) {
                if (!wait && outputQueue.size() <= threads) {
                    return;
                }
                awaitFrame(frame);
                if (alpha != null) {
                    awaitFrame(alpha);
                }
            }
            outputQueue.pollFirst();
            frame.awaitingOutput = false;
            output.accept(frame);
            frame.alpha = null;
            if (alpha != null) {
                alpha.awaitingOutput = false;
            }
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
        removeUnused(-1);
    }

    /**
     * Finishes the current picture: applies the in-loop filters, checks its decoded picture hash,
     * marks it as a short-term reference and outputs the pictures the reordering limits release
     * (C.5.2.3); for a multi-layer stream, the marking for output waits for the end of the access
     * unit.
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
        var state = currentLayer;
        var sps = frame.sps;
        var job = currentJob;
        currentJob = null;
        if (checkHashes && pendingHash >= 0 && hashVerifiable) {
            job.hashType = pendingHash;
            job.hash = new byte[][]{state.sei.hash[0].clone(), state.sei.hash[1].clone(), state.sei.hash[2].clone()};
        }
        MediaException failure = null;
        if (parallel) {
            startJob(job);
        } else {
            job.run();
            failure = job.error();
            statePool.addAll(Arrays.asList(job.states));
            recycleInterLayers(job);
        }
        pendingHash = -1;
        state.sei.hashType = -1;
        frame.shortTerm = !frame.discardable;
        frame.longTerm = false;
        if (multiLayer) {
            // the inter-layer references return to their own marking (F.8.1.6)
            Arrays.fill(interLayerCount, 0);
            state.firstPicDecoded = true;
            dpb.add(frame);
            accessUnit.add(frame);
            if (failure != null) {
                throw failure;
            }
            return;
        }
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
        try {
            finishPicture();
            endAccessUnitPictures();
        } catch (MediaException failure) {
            if (deferred == null) {
                deferred = failure;
            }
        }
        throwDeferred();
    }

    /**
     * Finishes the current picture and outputs every picture still waiting. The SEI messages of an
     * access unit whose picture never started are forgotten.
     */
    public void flush() {
        try {
            finishPicture();
            endAccessUnitPictures();
        } finally {
            for (var state : layers) {
                if (state != null) {
                    state.sei.clearPictureMessages();
                }
            }
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
     * @param state the layer of the pictures
     * @param sps the sequence parameter set
     * @return the color description
     */
    private static ColorInfo colorInfo(LayerState state, HevcSps sps) {
        var sei = state.sei;
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
     * Returns the sequence parameter set that was active for the last picture of the base layer,
     * or {@code null}.
     *
     * @return the sequence parameter set
     */
    public HevcSps activeSps() {
        var base = layers[0];
        return base != null ? base.activeSps : null;
    }

    /**
     * Returns the video parameter set the layers to decode were chosen for, or {@code null} when
     * only the base layer is decoded.
     *
     * @return the video parameter set
     */
    public HevcVps layeredVps() {
        return planVps;
    }
}
