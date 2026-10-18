package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.Av1PicturePool;
import com.github.auties00.smalt.video.av1.internal.BitReader;
import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.CdefDsp;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.FrameContext;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.LoopFilterDsp;
import com.github.auties00.smalt.video.av1.internal.Obu;
import com.github.auties00.smalt.video.av1.internal.RefMvs;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.TaskContext;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Encodes AV1 frames from per-frame parameters: writes the headers, sets up the decoder-side
 * frame state, encodes the tiles, runs the in-loop filters on the reconstruction and keeps the
 * eight reference slots a decoder keeps.
 *
 * <p>The frame encoder makes no rate or structure decisions: the caller chooses the frame type,
 * quantizer, references and filter strengths through {@link FrameParams}.
 *
 * <p>Frames may be hidden ({@link FrameParams#showFrame} clear) and shown later through
 * {@link #showExisting}, and several frames may share one temporal unit. With
 * {@link FrameParams#goodSpeed} set, inter frames record their symbols, the deblocking levels and
 * CDEF strengths are searched on the reconstruction ({@link LoopFilterSearch}, {@link CdefSearch}),
 * and the symbols are coded afterwards with each superblock's CDEF index.
 *
 * <p>An instance is used by one thread at a time; it runs its tiles on the shared pool.
 */
final class FrameEncoder implements FilterTarget {
    /**
     * The number of reference slots.
     */
    static final int SLOTS = 8;

    /**
     * The number of bits of the order hint.
     */
    static final int ORDER_HINT_BITS = 7;

    /**
     * The picture width.
     */
    final int width;

    /**
     * The picture height.
     */
    final int height;

    /**
     * The allocation limits.
     */
    private final MediaLimits limits;

    /**
     * The sequence header model.
     */
    final SequenceHeader seq;

    /**
     * The sequence header OBU payload.
     */
    private final byte[] seqObu;

    /**
     * The header writer.
     */
    private final ObuWriter obuWriter = new ObuWriter();

    /**
     * The pool of reconstruction pictures.
     */
    private final Av1PicturePool pool;

    /**
     * The picture of each reference slot, or {@code null}.
     */
    final Av1Picture[] refPic = new Av1Picture[SLOTS];

    /**
     * The frame header of each reference slot, or {@code null}.
     */
    private final FrameHeader[] refHdr = new FrameHeader[SLOTS];

    /**
     * The saved CDFs of each reference slot, or {@code null}.
     */
    private final char[][] refCdf = new char[SLOTS][];

    /**
     * The order hint of each reference slot.
     */
    final int[] refOrderHint = new int[SLOTS];

    /**
     * The temporal motion records saved with the frame of each reference slot, or {@code null}.
     */
    private final RefMvs.Temporal[] slotMvs = new RefMvs.Temporal[SLOTS];

    /**
     * The order hints of the references of the frame of each reference slot.
     */
    private final int[][] slotRefPoc = new int[SLOTS][7];

    /**
     * The default CDFs of each quantizer category, created on first use.
     */
    private final char[][] defaultCdf = new char[4][];

    /**
     * The decoder-side state of the frame being encoded.
     */
    final FrameContext f = new FrameContext();

    /**
     * The source picture of the frame being encoded, as 16-bit samples with replicated edges.
     */
    Av1Picture source;

    /**
     * The source picture of the previous frame, or {@code null} before the first frame.
     */
    Av1Picture lastSource;

    /**
     * The speed features of the frame being encoded.
     */
    SpeedFeatures speed;

    /**
     * The rate tables of the frame being encoded.
     */
    final RdCost rd = new RdCost();

    /**
     * The base quantizer index of the frame being encoded.
     */
    int baseQindex;

    /**
     * The SAD of each 64x64 source block against the previous source, in raster order.
     */
    private long[] sbSad;

    /**
     * The number of 64x64 source blocks per row.
     */
    private final int sbCols;

    /**
     * The mean SAD of the 64x64 source blocks against the previous source.
     */
    long frameSourceSad;

    /**
     * The running average of {@link #frameSourceSad}.
     */
    long avgSourceSad;

    /**
     * Whether the last analysis detected a scene change.
     */
    boolean highSourceSad;

    /**
     * The running percentage of the picture coded from {@code LAST} with near-zero motion.
     */
    int avgFrameLowMotion;

    /**
     * The number of frames since the golden reference was refreshed.
     */
    int framesSinceGolden;

    /**
     * The cyclic refresh of the frame being coded, or {@code null} when it is off.
     */
    CyclicRefresh cyclicRefresh;

    /**
     * The CDFs each slot owns, refreshed by copy so the arrays are reused.
     */
    private final char[][] refCdfStore = new char[SLOTS][];

    /**
     * The adapted CDFs of the frame being coded, reused across frames.
     */
    private char[] outCdf;

    /**
     * The temporal unit being assembled, reused across frames.
     */
    private final ByteArrayOutputStream output = new ByteArrayOutputStream(1 << 16);

    /**
     * The edge-extended reference of each slot.
     */
    private final RefFrame[] slotRef = new RefFrame[SLOTS];

    /**
     * The edge-extended references available for reuse.
     */
    private final ArrayDeque<RefFrame> freeRefs = new ArrayDeque<>();


    /**
     * The quantizers of each segment and plane, as {@code [segment * 3 + plane]}.
     */
    private final Quantizer[] quantizers = new Quantizer[8 * 3];

    /**
     * The tile encoders.
     */
    private TileEncoder[] tiles = new TileEncoder[0];

    /**
     * The coded size of each tile of the frame being encoded.
     */
    private int[] tileSizes = new int[0];

    /**
     * The coded bytes of each tile of the frame being encoded.
     */
    private byte[][] tileData = new byte[0][];

    /**
     * The temporal motion records no reference slot holds, ready for the next inter frame.
     */
    private final ArrayDeque<RefMvs.Temporal> freeMvs = new ArrayDeque<>();

    /**
     * The segment map storage, kept while segmentation is off.
     */
    private byte[] segmapStore;

    /**
     * The header {@link #buildHeader} writes before parsing it back, reused by every frame.
     */
    private final FrameHeader draft = new FrameHeader();

    /**
     * The reader {@link #buildHeader} parses the written header with.
     */
    private final BitReader headerReader = new BitReader();

    /**
     * The parsed headers this encoder created; {@link #buildHeader} parses into one that no slot,
     * reference picture, current frame or pending frame shares.
     */
    private final ArrayList<FrameHeader> headers = new ArrayList<>();

    /**
     * The motion vector reference state of each tile column.
     */
    private RefMvs.Frame[] tileMvs = new RefMvs.Frame[0];

    /**
     * The task context of the in-loop filters.
     */
    private final TaskContext filterTask = new TaskContext();

    /**
     * The task contexts of the parallel CDEF bands.
     */
    private TaskContext[] filterTasks = new TaskContext[0];

    /**
     * The parameters of the frame being encoded.
     */
    private FrameParams params;

    /**
     * The header of the frame coded by {@link #code} and not yet committed or discarded.
     */
    private FrameHeader pending;

    /**
     * The encoders of the participants of the superblock row wavefront, the calling thread's
     * first.
     */
    private TileEncoder[] workers = new TileEncoder[0];

    /**
     * The symbols of each superblock row of the wavefront, in wavefront order.
     */
    private SymbolLog[] logs = new SymbolLog[0];

    /**
     * The superblock row of each wavefront unit.
     */
    private int[] unitRow = new int[0];

    /**
     * The tile of each wavefront unit.
     */
    private int[] unitTile = new int[0];

    /**
     * The wavefront unit of the row above in the same tile of each unit, or {@code -1}.
     */
    private int[] unitAbove = new int[0];

    /**
     * The number of tiles that have coded each superblock row of the wavefront's frame.
     */
    private AtomicIntegerArray rowTilesDone = new AtomicIntegerArray(0);

    /**
     * Whether a participant has taken the deblocking of each superblock row of the wavefront's
     * frame.
     */
    private AtomicIntegerArray deblockTaken = new AtomicIntegerArray(0);

    /**
     * The threads the frame coded last could use, which also copy the next source picture and the
     * reference pictures; one before the first frame.
     */
    private int lastThreads = 1;

    /**
     * Whether the wavefront of the frame coded last also deblocked its rows.
     */
    private boolean deblockedInLoop;

    /**
     * The superblock row wavefront.
     */
    private final SbRowWavefront wavefront = new SbRowWavefront();

    /**
     * Whether the frame coded last ran in the wavefront, so its counts are the workers'.
     */
    private boolean rowParallel;

    /**
     * Whether the tiles defer each superblock's CDEF index to the replay of their symbols.
     */
    boolean deferCdef;

    /**
     * The CDEF index of each 64x64 superblock of a frame whose indices are deferred, in raster
     * order.
     */
    private final int[] sbCdef;

    /**
     * The number of 64x64 superblock rows.
     */
    private final int sbRows;

    /**
     * Creates a frame encoder for a picture size and color description.
     *
     * @param width the picture width
     * @param height the picture height
     * @param color the color description signaled in the sequence header
     * @param level the sequence level as {@code major * 4 + minor - 8}, the {@code seq_level_idx}
     * @param limits the allocation limits
     */
    FrameEncoder(int width, int height, ColorInfo color, int level, MediaLimits limits) {
        this(width, height, color, level, 1, limits);
    }

    /**
     * Creates a frame encoder whose stream carries temporal layers, each an operating point.
     *
     * @param width the picture width
     * @param height the picture height
     * @param color the color description signaled in the sequence header
     * @param level the sequence level as {@code major * 4 + minor - 8}
     * @param temporalLayers the number of temporal layers, 1 to 3
     * @param limits the allocation limits
     */
    FrameEncoder(int width, int height, ColorInfo color, int level, int temporalLayers, MediaLimits limits) {
        this(width, height, sequenceHeader(width, height, color, level, temporalLayers), limits);
    }

    /**
     * Creates a frame encoder whose stream carries a given sequence header, describing 8-bit 4:2:0
     * pictures of the given size with order hints.
     *
     * @param width the picture width
     * @param height the picture height
     * @param seq the sequence header
     * @param limits the allocation limits
     */
    FrameEncoder(int width, int height, SequenceHeader seq, MediaLimits limits) {
        this.width = width;
        this.height = height;
        this.limits = limits;
        this.pool = new Av1PicturePool(limits);
        this.seq = seq;
        this.seqObu = obuWriter.sequenceHeader(seq);
        this.source = pool.obtain(width, height, SequenceHeader.LAYOUT_I420, 8);
        this.sbCols = (width + 63) >> 6;
        this.sbRows = (height + 63) >> 6;
        this.sbCdef = new int[sbCols * sbRows];
        this.speed = SpeedFeatures.realtime(9, width, height);
        for (var i = 0; i < quantizers.length; i++) {
            quantizers[i] = new Quantizer();
        }
    }

    /**
     * Builds the sequence header of a single-layer stream that may also enable loop restoration
     * and filter intra, the tools of the still picture search, so key frames coded by
     * {@link StillFrameEncoder} and inter frames coded by this class share it.
     *
     * @param width the picture width
     * @param height the picture height
     * @param color the color description
     * @param level the {@code seq_level_idx}
     * @param restoration whether loop restoration is enabled
     * @param filterIntra whether filter intra prediction is enabled
     * @return the sequence header
     */
    static SequenceHeader sequenceHeader(int width, int height, ColorInfo color, int level,
                                                boolean restoration, boolean filterIntra) {
        var seq = sequenceHeader(width, height, color, level, 1);
        seq.restoration = restoration;
        seq.filterIntra = filterIntra;
        seq.warpedMotion = true;
        seq.refFrameMvs = true;
        return seq;
    }

    /**
     * Builds the sequence header of a stream.
     *
     * @param width the picture width
     * @param height the picture height
     * @param color the color description
     * @param level the {@code seq_level_idx}
     * @param temporalLayers the number of temporal layers
     * @return the sequence header
     */
    private static SequenceHeader sequenceHeader(int width, int height, ColorInfo color, int level,
                                                 int temporalLayers) {
        var seq = new SequenceHeader();
        seq.profile = 0;
        seq.numOperatingPoints = temporalLayers;
        for (var i = 0; i < temporalLayers; i++) {
            // operating point i decodes the temporal layers 0 to temporalLayers - 1 - i of spatial layer 0
            seq.operatingPointIdc[i] = temporalLayers == 1 ? 0 : 1 << 8 | ((1 << (temporalLayers - i)) - 1);
            seq.majorLevel[i] = 2 + (level >> 2);
            seq.minorLevel[i] = level & 3;
        }
        seq.widthNBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(width - 1));
        seq.heightNBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(height - 1));
        seq.maxWidth = width;
        seq.maxHeight = height;
        seq.intraEdgeFilter = true;
        seq.orderHint = true;
        seq.orderHintNBits = ORDER_HINT_BITS;
        seq.screenContentTools = 0;
        seq.forceIntegerMv = 2;
        seq.cdef = true;
        seq.layout = SequenceHeader.LAYOUT_I420;
        seq.ssHor = 1;
        seq.ssVer = 1;
        seq.colorDescriptionPresent = true;
        seq.pri = color.primaries().code();
        seq.trc = color.transfer().code();
        seq.mtrx = color.matrix().code();
        seq.colorRange = color.range() == ColorRange.FULL;
        return seq;
    }

    /**
     * Returns the sequence header OBU payload.
     *
     * @return the payload
     */
    byte[] sequenceHeaderObu() {
        return seqObu.clone();
    }

    /**
     * Returns the sequence header model.
     *
     * @return the sequence header
     */
    SequenceHeader sequenceHeader() {
        return seq;
    }

    /**
     * Returns the quantizer of a segment and plane of the frame being encoded.
     *
     * @param segment the segment, 0 to 7
     * @param plane the plane index: 0 luma, 1 U, 2 V
     * @return the quantizer
     */
    Quantizer quantizer(int segment, int plane) {
        return quantizers[segment * 3 + plane];
    }

    /**
     * Returns the edge-extended picture a decoder reference index of the frame points to.
     *
     * @param ref the decoder reference index, 0 {@code LAST} to 6 {@code ALTREF}
     * @return the reference
     */
    RefFrame ref(int ref) {
        return slotRef[f.frameHdr.refidx[ref]];
    }

    /**
     * Returns whether the mode decision may predict from a reference.
     *
     * @param ref the decoder reference index
     * @return {@code true} when searchable
     */
    boolean refEnabled(int ref) {
        return (params.refSearch & (1 << ref)) != 0 && slotRef[f.frameHdr.refidx[ref]] != null;
    }

    /**
     * Returns the quantizer index of a segment.
     *
     * @param segment the segment, 0 to 7
     * @return the quantizer index
     */
    int segmentQindex(int segment) {
        return f.frameHdr.segQidx[segment];
    }

    /**
     * Returns whether a segment is boosted by the cyclic refresh.
     *
     * @param segment the segment, 0 to 7
     * @return {@code true} for the refresh segments
     */
    boolean segmentBoosted(int segment) {
        return params.segmentation && segment != 0;
    }

    /**
     * Returns the SAD of the 64x64 source block containing a position against the previous source.
     *
     * @param px the column
     * @param py the row
     * @return the SAD, or 0 when not analyzed
     */
    long blockSourceSad(int px, int py) {
        return sbSad == null ? 0 : sbSad[(py >> 6) * sbCols + (px >> 6)];
    }

    /**
     * Returns the picture width rounded up to 8.
     *
     * @return the aligned width
     */
    int alignedWidth() {
        return (width + 7) & ~7;
    }

    /**
     * Returns the picture height rounded up to 8.
     *
     * @return the aligned height
     */
    int alignedHeight() {
        return (height + 7) & ~7;
    }

    /**
     * Returns the number of 4x4 columns of the picture.
     *
     * @return the columns
     */
    int miCols() {
        return alignedWidth() >> 2;
    }

    /**
     * Returns the number of 4x4 rows of the picture.
     *
     * @return the rows
     */
    int miRows() {
        return alignedHeight() >> 2;
    }

    /**
     * Compares the loaded source with the previous one block by block, in bands of superblock rows
     * on as many threads as the frame coded last could use, and updates the scene statistics
     * ({@code rc_scene_detection_onepass_rt}).
     *
     * @param framesSinceKey the number of frames since the last key frame
     * @return whether a scene change was detected
     */
    boolean analyze(int framesSinceKey) {
        highSourceSad = false;
        if (lastSource == null) {
            sbSad = null;
            return false;
        }
        var sbRows = (height + 63) >> 6;
        if (sbSad == null || sbSad.length != sbRows * sbCols) {
            sbSad = new long[sbRows * sbCols];
        }
        var stride = source.stride[0];
        var sads = sbSad;
        var count = Math.min(lastThreads, sbRows);
        ParallelBands.run(count, band -> {
            var dsp = EncDsp.select();
            var last = ParallelBands.start(band + 1, count, sbRows);
            for (var r = ParallelBands.start(band, count, sbRows); r < last; r++) {
                for (var c = 0; c < sbCols; c++) {
                    var off = (r * stride + c) * 64;
                    sads[r * sbCols + c] = dsp.sad(source.data[0], off, stride, lastSource.data[0], off, stride, 64,
                            64);
                }
            }
        });
        long sum = 0;
        var zero = 0;
        for (var i = 0; i < sbRows * sbCols; i++) {
            var sad = sads[i];
            sum += sad;
            if (sad == 0) {
                zero++;
            }
        }
        var n = sbRows * sbCols;
        var avg = sum / n;
        highSourceSad = avg > Math.max(10000L, avgSourceSad * 6) && framesSinceKey > 2 && zero < 3 * (n >> 2);
        avgSourceSad = (3 * avgSourceSad + avg) >> 2;
        frameSourceSad = avg;
        return highSourceSad;
    }

    /**
     * Measures the spatial activity of the loaded source: the mean variance per sample of its 64x64
     * luma blocks ({@code rc_spatial_act_onepass_rt}).
     *
     * <p>Blocks crossing the right or bottom edge read the source's edge replication.
     *
     * @return the mean variance
     */
    int spatialActivity() {
        var sbRows = (height + 63) >> 6;
        var stride = source.stride[0];
        var y = source.data[0];
        long total = 0;
        for (var r = 0; r < sbRows; r++) {
            for (var c = 0; c < sbCols; c++) {
                var base = (r * stride + c) * 64;
                long sum = 0;
                long sse = 0;
                for (var row = 0; row < 64; row++) {
                    var off = base + row * stride;
                    for (var x = 0; x < 64; x++) {
                        var d = y[off + x] - 128;
                        sum += d;
                        sse += d * d;
                    }
                }
                total += sse - ((sum * sum) >> 12);
            }
        }
        return (int) ((total / (sbRows * sbCols)) >> 12);
    }

    /**
     * Returns the CDEF strength index coded in the first non-skip block of each superblock.
     *
     * @return the index
     */
    int cdefIndex() {
        return 0;
    }

    /**
     * Returns the parameters of the frame being encoded.
     *
     * @return the parameters
     */
    FrameParams params() {
        return params;
    }

    /**
     * Returns the motion vector reference state of a tile column.
     *
     * @param tileCol the tile column
     * @return the state
     */
    RefMvs.Frame refMvs(int tileCol) {
        return tileMvs[tileCol];
    }

    /**
     * Returns the reconstruction of a reference slot.
     *
     * @param slot the reference slot, 0 to 7
     * @return the picture, or {@code null} when empty
     */
    Av1Picture reference(int slot) {
        return refPic[slot];
    }

    /**
     * Returns the default CDFs of a base quantizer index.
     *
     * @param qidx the base quantizer index
     * @return the CDFs, shared and read-only
     */
    private char[] defaults(int qidx) {
        var qcat = Cdf.qcat(qidx);
        var cdf = defaultCdf[qcat];
        if (cdf == null) {
            cdf = new char[Cdf.SIZE];
            Cdf.loadDefaults(cdf, qcat);
            defaultCdf[qcat] = cdf;
        }
        return cdf;
    }

    /**
     * Copies a picture into the source picture, converting to 16-bit samples and replicating the
     * right and bottom edges to whole superblocks, in horizontal bands on as many threads as the
     * frame coded last could use.
     *
     * @param in the picture
     */
    private void loadSource(YuvPicture in) {
        var count = Math.max(1, Math.min(lastThreads, (height + 1) >> 1));
        if (count == 1) {
            for (var pl = 0; pl < 3; pl++) {
                loadRows(in, pl, 0, pl == 0 ? height : (height + 1) >> 1);
            }
            return;
        }
        ParallelBands.run(count, band -> {
            for (var pl = 0; pl < 3; pl++) {
                var h = pl == 0 ? height : (height + 1) >> 1;
                loadRows(in, pl, ParallelBands.start(band, count, h), ParallelBands.start(band + 1, count, h));
            }
        });
    }

    /**
     * Widens a range of one plane's source rows to 16 bits and repeats each row's last sample to
     * the 64-aligned width; the range holding the last visible row also repeats that row down to
     * the 64-aligned height.
     *
     * @param in the picture
     * @param pl the plane
     * @param first the first row
     * @param end the row after the last
     */
    private void loadRows(YuvPicture in, int pl, int first, int end) {
        var w = pl == 0 ? width : (width + 1) >> 1;
        var h = pl == 0 ? height : (height + 1) >> 1;
        var pw = pl == 0 ? (width + 63) & ~63 : ((width + 63) & ~63) >> 1;
        var ph = pl == 0 ? (height + 63) & ~63 : ((height + 63) & ~63) >> 1;
        var src = pl == 0 ? in.y() : pl == 1 ? in.u() : in.v();
        var srcStride = pl == 0 ? in.yStride() : in.uvStride();
        var dst = source.data[pl];
        var stride = source.stride[pl != 0 ? 1 : 0];
        for (var y = first; y < end; y++) {
            var s = y * srcStride;
            var d = y * stride;
            for (var x = 0; x < w; x++) {
                dst[d + x] = (short) (src[s + x] & 0xFF);
            }
            Arrays.fill(dst, d + w, d + pw, dst[d + w - 1]);
        }
        if (end == h) {
            for (var y = h; y < ph; y++) {
                System.arraycopy(dst, (h - 1) * stride, dst, y * stride, pw);
            }
        }
    }

    /**
     * Encodes one frame and returns its temporal unit.
     *
     * @param in the picture, 4:2:0 of the encoder's size
     * @param p the frame parameters
     * @return the temporal unit: a temporal delimiter, the sequence header on key frames and the
     * frame OBU
     */
    byte[] encode(YuvPicture in, FrameParams p) {
        load(in);
        analyze(Integer.MAX_VALUE);
        return encode(p);
    }

    /**
     * Loads the next source picture, keeping the previous one for the scene analysis.
     *
     * @param in the picture, 4:2:0 of the encoder's size
     */
    void load(YuvPicture in) {
        loadSource(in);
    }

    /**
     * Loads the next source picture and the picture its scene analysis and partitioning compare it
     * with, normally the source of the frame its {@code LAST} reference reconstructs.
     *
     * @param in the picture, 4:2:0 of the encoder's size
     * @param previous the picture to compare with, or {@code null} for none
     */
    void load(YuvPicture in, YuvPicture previous) {
        if (previous == null) {
            if (lastSource != null) {
                lastSource.release();
                lastSource = null;
            }
        } else {
            if (lastSource == null) {
                lastSource = pool.obtain(width, height, SequenceHeader.LAYOUT_I420, 8);
            }
            var current = source;
            source = lastSource;
            loadSource(previous);
            lastSource = source;
            source = current;
        }
        loadSource(in);
    }

    /**
     * Makes a key frame another encoder coded with this stream's sequence header the content of
     * every reference slot, as a decoder stores it: the reconstruction, the header, the default
     * CDFs of its quantizer (the key frame does not update its CDFs) and its order hint.
     *
     * @param recon the key frame's reconstruction, retained by each slot
     * @param hdr the key frame's parsed header
     */
    void installKeyFrame(Av1Picture recon, FrameHeader hdr) {
        var padded = freeRefs.isEmpty() ? new RefFrame() : freeRefs.pop();
        padded.build(recon, width, height, lastThreads);
        padded.orderHint = hdr.frameOffset;
        var cdf = defaults(hdr.quantYac);
        for (var i = 0; i < SLOTS; i++) {
            var old = slotRef[i];
            slotRef[i] = padded;
            if (old != null && old != padded && !held(old)) {
                freeRefs.push(old);
            }
            if (refPic[i] != null) {
                refPic[i].release();
            }
            refPic[i] = recon.retain();
            refHdr[i] = hdr;
            if (refCdfStore[i] == null) {
                refCdfStore[i] = new char[Cdf.SIZE];
            }
            System.arraycopy(cdf, 0, refCdfStore[i], 0, Cdf.SIZE);
            refCdf[i] = refCdfStore[i];
            refOrderHint[i] = hdr.frameOffset;
            var oldMvs = slotMvs[i];
            slotMvs[i] = null;
            releaseMvs(oldMvs);
        }
    }

    /**
     * Returns cleared temporal motion records of a size, reusing ones no slot holds.
     *
     * @param size the number of 8x8 blocks
     * @return the records, all zero
     */
    private RefMvs.Temporal takeMvs(int size) {
        while (!freeMvs.isEmpty()) {
            var mvs = freeMvs.pop();
            if (mvs.size() == size) {
                Arrays.fill(mvs.mv, 0);
                Arrays.fill(mvs.ref, (byte) 0);
                return mvs;
            }
        }
        return new RefMvs.Temporal(size);
    }

    /**
     * Makes temporal motion records reusable once no reference slot holds them.
     *
     * @param mvs the records a slot stopped holding, or {@code null}
     */
    private void releaseMvs(RefMvs.Temporal mvs) {
        if (mvs == null) {
            return;
        }
        for (var held : slotMvs) {
            if (held == mvs) {
                return;
            }
        }
        freeMvs.push(mvs);
    }

    /**
     * Returns the temporal unit, or the part of one, that shows the frame of a reference slot
     * through a {@code show_existing_frame} header.
     *
     * @param slot the reference slot holding a showable frame
     * @param temporalDelimiter whether the bytes start a temporal unit
     * @return the OBUs
     */
    byte[] showExisting(int slot, boolean temporalDelimiter) {
        var out = new ByteArrayOutputStream(16);
        if (temporalDelimiter) {
            ObuWriter.writeTemporalDelimiter(out);
        }
        var w = obuWriter.showExistingHeader(slot);
        ObuWriter.writeObu(out, Obu.OBU_FRAME_HDR, w.buffer(), 0, w.size());
        return out.toByteArray();
    }

    /**
     * Ends the frame without coding it: the loaded source becomes the previous source.
     */
    void skip() {
        swapSources();
    }

    /**
     * Makes the loaded source the previous one.
     */
    private void swapSources() {
        var old = lastSource;
        lastSource = source;
        source = old != null ? old : pool.obtain(width, height, SequenceHeader.LAYOUT_I420, 8);
    }

    /**
     * The dominant full-sample translation against each reference, packed as
     * {@code row << 16 | (column & 0xFFFF)}, or {@link RefMvs#INVALID_MV} when not estimated.
     */
    private final int[] globalMv = new int[7];

    /**
     * Estimates the dominant translation of the loaded source against each searched reference,
     * the distinct reference pictures in parallel bands on as many threads as the frame coded last
     * could use.
     */
    private void estimateGlobal() {
        Arrays.fill(globalMv, RefMvs.INVALID_MV);
        var cur = new GlobalTranslation.Pyramid(source.data[0], 0, source.stride[0], width, height);
        var owner = new int[7];
        var tasks = new int[7];
        var count = 0;
        for (var r = 0; r < 7; r++) {
            owner[r] = -1;
            if (!refEnabled(r)) {
                continue;
            }
            var rf = ref(r);
            for (var k = 0; k < r; k++) {
                if (owner[k] >= 0 && ref(k) == rf) {
                    owner[r] = owner[k];
                    break;
                }
            }
            if (owner[r] < 0) {
                owner[r] = r;
                tasks[count++] = r;
                rf.pyramid(width, height);
            }
        }
        var total = count;
        var bands = Math.max(1, Math.min(lastThreads, total));
        ParallelBands.run(bands, band -> {
            for (var i = band; i < total; i += bands) {
                var r = tasks[i];
                var rf = ref(r);
                globalMv[r] = GlobalTranslation.estimate(cur, rf.pyramid(width, height), source.data[0], 0,
                        source.stride[0], rf.padded[0], rf.origin[0], rf.stride[0], width, height, 120);
            }
        });
        for (var r = 0; r < 7; r++) {
            if (owner[r] >= 0 && owner[r] != r) {
                globalMv[r] = globalMv[owner[r]];
            }
        }
    }

    /**
     * Returns the dominant translation of the source against a reference.
     *
     * @param ref the decoder reference index
     * @return the full-sample vector packed as {@code row << 16 | (column & 0xFFFF)}, or
     * {@link RefMvs#INVALID_MV}
     */
    int globalStart(int ref) {
        return globalMv[ref];
    }

    /**
     * Encodes the loaded source picture, makes it the next reference as its parameters say and
     * returns its temporal unit.
     *
     * @param p the frame parameters
     * @return the temporal unit: a temporal delimiter, the sequence header on key frames and the
     * frame OBU
     */
    byte[] encode(FrameParams p) {
        var data = code(p);
        commit();
        return data;
    }

    /**
     * Codes the loaded source picture without updating the references, so the frame can be coded
     * again with other parameters or dropped; {@link #commit} or {@link #discard} must follow.
     *
     * @param p the frame parameters
     * @return the temporal unit: a temporal delimiter, the sequence header on key frames and the
     * frame OBU
     */
    byte[] code(FrameParams p) {
        if (pending != null) {
            discard();
        }
        this.params = p;
        if (p.goodSpeed > 0) {
            if (speed.goodLevel != Math.clamp(p.goodSpeed, 2, 6)) {
                speed = SpeedFeatures.good(p.goodSpeed);
            }
        } else if (speed.goodLevel != 0 || speed.speed != Math.clamp(p.speed, 7, 10)) {
            speed = SpeedFeatures.realtime(p.speed, width, height);
        }
        var hdr = buildHeader(p);
        var threads = p.threads > 0 ? Math.min(p.threads, ParallelBands.THREADS) : ParallelBands.THREADS;
        lastThreads = threads;
        var good = p.goodSpeed > 0 && hdr.isInterOrSwitch();
        setupFrame(hdr, threads > 1 || good);
        if (good) {
            estimateGlobal();
        }
        var nTiles = hdr.tilingCols * hdr.tilingRows;
        if (tiles.length != nTiles) {
            tiles = new TileEncoder[nTiles];
            for (var i = 0; i < nTiles; i++) {
                tiles[i] = new TileEncoder(this);
            }
        }
        if (tileSizes.length != nTiles) {
            tileSizes = new int[nTiles];
            tileData = new byte[nTiles][];
        }
        var sizes = tileSizes;
        var data = tileData;
        var cols = hdr.tilingCols;
        var rows = hdr.tilingRows;
        var bands = Math.min(cols, threads);
        rowParallel = hdr.isInterOrSwitch() && (good || (threads > 1 && f.sbh > 1));
        ParallelBands.Pending<Void> replay = null;
        deblockedInLoop = false;
        deferCdef = good;
        if (good) {
            encodeGood(hdr, threads, sizes, data);
        } else if (rowParallel) {
            replay = encodeRows(hdr, threads, sizes, data);
        } else if (bands <= 1) {
            for (var i = 0; i < nTiles; i++) {
                sizes[i] = tiles[i].encode(i / cols, i % cols);
                data[i] = tiles[i].ec.buffer();
            }
        } else {
            ParallelBands.run(bands, band -> {
                for (var col = band; col < cols; col += bands) {
                    for (var row = 0; row < rows; row++) {
                        var i = row * cols + col;
                        sizes[i] = tiles[i].encode(row, col);
                        data[i] = tiles[i].ec.buffer();
                    }
                }
            });
        }
        filterTask.f = f;
        if (good) {
            deferCdef = false;
        } else if (f.haveTt) {
            filterRows(threads);
        } else {
            for (var sby = 0; sby < f.sbh; sby++) {
                EncoderBridge.filterSbrow(filterTask, f, sby);
            }
        }
        if (replay != null) {
            replay.join();
        }
        var largest = 0;
        var maxSize = 0;
        for (var i = 0; i < nTiles; i++) {
            if (sizes[i] > maxSize) {
                maxSize = sizes[i];
                largest = i;
            }
        }
        hdr.tilingUpdate = largest;
        hdr.tilingNBytes = ObuWriter.tileSizeBytes(maxSize);
        if (hdr.refreshContext) {
            System.arraycopy(f.inCdf, 0, f.outCdf, 0, Cdf.SIZE);
            Cdf.update(f.outCdf, tiles[largest].ts.cdf, hdr.isKeyOrIntra());
        }
        var out = output;
        out.reset();
        if (p.temporalDelimiter) {
            ObuWriter.writeTemporalDelimiter(out);
        }
        if (hdr.frameType == FrameHeader.KEY) {
            ObuWriter.writeObu(out, Obu.OBU_SEQ_HDR, seqObu, 0, seqObu.length);
        }
        var w = obuWriter.frameHeader(seq, hdr, refOrderHint, refHdr);
        ObuWriter.writeFrameObu(out, w, p.temporalLayers > 1, p.temporalId, nTiles, sizes, data, hdr.tilingNBytes);
        pending = hdr;
        return out.toByteArray();
    }

    /**
     * Encodes the tiles of an inter frame with superblock rows in a wavefront: rows are decided,
     * reconstructed and their symbols recorded on several threads, while a follower codes each
     * tile's rows' symbols in raster order as the rows complete. The stream is the one
     * {@link TileEncoder#encode} gives, because
     * every decision of an inter frame depends only on the reconstruction, contexts and motion
     * records of the blocks above and to the left, never on the adapted CDFs.
     *
     * <p>This method returns once every row is encoded; the follower, {@link #replayRows}, may still
     * be coding symbols, and the caller joins it before the tiles' bytes are read.
     *
     * <p>With the threaded filter layout the same threads also deblock the frame while later rows
     * are still being coded: the participant that completes the last tile of a superblock row
     * deblocks the rows this makes ready, a row being ready once every tile has coded it and the
     * row below it (the intra prediction of a row reads the unfiltered bottom of the row above, and
     * the deblocking of a row changes it). Each deblocking first waits for the row above to be
     * deblocked, so the rows are deblocked in the order {@link #filterRows} deblocks them.
     *
     * @implNote This implementation follows libaom's row-based multithreading
     * ({@code av1/encoder/ethread.c}: {@code enc_row_mt_worker_hook}), whose decisions
     * read only what the superblock above and to the right has finished, with the
     * symbols recorded per row and coded afterwards as libaom's token buffers are packed.
     * Key frames stay in raster order: their 8x8 intra search costs coefficients with
     * the tile's adapted CDFs.
     *
     * @param hdr the frame header
     * @param threads the most threads to use
     * @param sizes receives the coded size of each tile
     * @param data receives the coded bytes of each tile
     * @return the handle of the follower
     */
    private ParallelBands.Pending<Void> encodeRows(FrameHeader hdr, int threads, int[] sizes, byte[][] data) {
        var cols = hdr.tilingCols;
        var units = 0;
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            units += (Math.min(hdr.rowStartSb[tileRow + 1], f.sbh) - hdr.rowStartSb[tileRow]) * cols;
        }
        if (unitRow.length < units) {
            unitRow = new int[units];
            unitTile = new int[units];
            unitAbove = new int[units];
        }
        if (logs.length < units) {
            var grown = Arrays.copyOf(logs, units);
            for (var i = logs.length; i < units; i++) {
                grown[i] = new SymbolLog();
            }
            logs = grown;
        }
        var unit = 0;
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            var first = hdr.rowStartSb[tileRow];
            var end = Math.min(hdr.rowStartSb[tileRow + 1], f.sbh);
            for (var sby = first; sby < end; sby++) {
                for (var col = 0; col < cols; col++, unit++) {
                    unitRow[unit] = sby;
                    unitTile[unit] = tileRow * cols + col;
                    unitAbove[unit] = sby == first ? -1 : unit - cols;
                }
            }
        }
        var participants = Math.min(threads, units);
        if (workers.length < participants) {
            var grown = Arrays.copyOf(workers, participants);
            for (var i = workers.length; i < participants; i++) {
                grown[i] = new TileEncoder(this);
            }
            workers = grown;
        }
        for (var worker : workers) {
            worker.resetCounts();
        }
        var count = units;
        var deblock = f.haveTt;
        var sbh = f.sbh;
        if (rowTilesDone.length() < sbh) {
            rowTilesDone = new AtomicIntegerArray(sbh);
            deblockTaken = new AtomicIntegerArray(sbh);
        }
        for (var sby = 0; sby < sbh; sby++) {
            rowTilesDone.set(sby, 0);
            deblockTaken.set(sby, 0);
        }
        deblockedInLoop = deblock;
        return wavefront.run(units, deblock ? sbh : 0, participants, (participant, u) -> {
            var worker = workers[participant];
            var tile = unitTile[u];
            worker.bind(tile / cols, tile % cols, false);
            var log = logs[u];
            log.clear();
            worker.ec.record(log);
            try {
                worker.encodeRow(unitRow[u], wavefront, u, unitAbove[u]);
            } finally {
                worker.ec.record(null);
            }
            var sby = unitRow[u];
            if (deblock && rowTilesDone.incrementAndGet(sby) == cols) {
                deblockReady(sby - 1, count);
                deblockReady(sby, count);
            }
        }, () -> replayRows(cols, count, sizes, data));
    }

    /**
     * Encodes the tiles of a good-quality inter frame: the superblock rows run in the wavefront of
     * {@link #encodeRows} without in-loop deblocking, recording every symbol with each superblock's
     * CDEF index deferred; then the deblocking levels and CDEF strengths are picked and applied to
     * the reconstruction, and each tile's rows are coded in raster order with the chosen indices.
     *
     * @param hdr the frame header
     * @param threads the most threads to use
     * @param sizes receives the coded size of each tile
     * @param data receives the coded bytes of each tile
     */
    private void encodeGood(FrameHeader hdr, int threads, int[] sizes, byte[][] data) {
        var cols = hdr.tilingCols;
        var units = layoutUnits(hdr);
        var participants = Math.min(threads, units);
        prepareWorkers(participants);
        wavefront.run(units, 0, participants, (participant, u) -> {
            var worker = workers[participant];
            var tile = unitTile[u];
            worker.bind(tile / cols, tile % cols, false);
            var log = logs[u];
            log.clear();
            worker.ec.record(log);
            try {
                worker.encodeRow(unitRow[u], wavefront, u, unitAbove[u]);
            } finally {
                worker.ec.record(null);
            }
        }, () -> {
        }).join();
        f.lf = LoopFilterDsp.select();
        f.cdef = CdefDsp.select();
        Arrays.fill(sbCdef, 0);
        LoopFilterSearch.pick(this, params.loopFilterSearch);
        for (var sby = 0; sby < f.sbh; sby++) {
            EncoderBridge.deblockSbrow(f, sby);
        }
        deblockedInLoop = true;
        if (params.cdefSearch > 0) {
            CdefSearch.pick(this, params.cdefSearch);
        }
        filterRows(threads);
        for (var i = 0; i < sizes.length; i++) {
            var tile = tiles[i];
            tile.bind(i / cols, i % cols, true);
            tile.resetCounts();
            for (var u = 0; u < units; u++) {
                if (unitTile[u] == i) {
                    logs[u].replay(tile.ec, tile.ts.cdf, sbCdef, hdr.cdefNBits);
                }
            }
            sizes[i] = tile.ec.finish();
            data[i] = tile.ec.buffer();
        }
    }

    /**
     * Lays out the wavefront units of a frame, superblock rows of tiles in wavefront order, and
     * grows the symbol logs to hold them.
     *
     * @param hdr the frame header
     * @return the number of units
     */
    private int layoutUnits(FrameHeader hdr) {
        var cols = hdr.tilingCols;
        var units = 0;
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            units += (Math.min(hdr.rowStartSb[tileRow + 1], f.sbh) - hdr.rowStartSb[tileRow]) * cols;
        }
        if (unitRow.length < units) {
            unitRow = new int[units];
            unitTile = new int[units];
            unitAbove = new int[units];
        }
        if (logs.length < units) {
            var grown = Arrays.copyOf(logs, units);
            for (var i = logs.length; i < units; i++) {
                grown[i] = new SymbolLog();
            }
            logs = grown;
        }
        var unit = 0;
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            var first = hdr.rowStartSb[tileRow];
            var end = Math.min(hdr.rowStartSb[tileRow + 1], f.sbh);
            for (var sby = first; sby < end; sby++) {
                for (var col = 0; col < cols; col++, unit++) {
                    unitRow[unit] = sby;
                    unitTile[unit] = tileRow * cols + col;
                    unitAbove[unit] = sby == first ? -1 : unit - cols;
                }
            }
        }
        return units;
    }

    /**
     * Grows the wavefront participants' encoders to a count and zeroes their statistics.
     *
     * @param participants the number of participants
     */
    private void prepareWorkers(int participants) {
        if (workers.length < participants) {
            var grown = Arrays.copyOf(workers, participants);
            for (var i = workers.length; i < participants; i++) {
                grown[i] = new TileEncoder(this);
            }
            workers = grown;
        }
        for (var worker : workers) {
            worker.resetCounts();
        }
    }

    /**
     * Deblocks a superblock row if every tile has coded it and the row below it and no other
     * participant has taken it, once the row above is deblocked.
     *
     * @param sby the superblock row, or -1 for none
     * @param units the number of coding units of the wavefront, the index of the progress slot of
     * the first row's deblocking
     * @throws java.util.concurrent.CancellationException if another participant failed
     */
    private void deblockReady(int sby, int units) {
        var cols = f.frameHdr.tilingCols;
        if (sby < 0 || rowTilesDone.get(sby) != cols) {
            return;
        }
        if (sby + 1 < f.sbh && rowTilesDone.get(sby + 1) != cols) {
            return;
        }
        if (!deblockTaken.compareAndSet(sby, 0, 1)) {
            return;
        }
        if (sby > 0) {
            wavefront.await(units + sby - 1, SbRowWavefront.DONE);
        }
        EncoderBridge.deblockSbrow(f, sby);
        wavefront.complete(units + sby);
    }

    /**
     * Codes the symbols {@link #encodeRows} records, each tile's rows in raster order with the
     * tile's own coder and CDFs, waiting for each row to complete.
     *
     * @param cols the number of tile columns
     * @param units the number of recorded rows
     * @param sizes receives the coded size of each tile
     * @param data receives the coded bytes of each tile
     */
    private void replayRows(int cols, int units, int[] sizes, byte[][] data) {
        for (var i = 0; i < sizes.length; i++) {
            var tile = tiles[i];
            tile.bind(i / cols, i % cols, true);
            tile.resetCounts();
            for (var u = 0; u < units; u++) {
                if (unitTile[u] == i) {
                    wavefront.await(u, SbRowWavefront.DONE);
                    logs[u].replay(tile.ec, tile.ts.cdf);
                }
            }
            sizes[i] = tile.ec.finish();
            data[i] = tile.ec.buffer();
        }
    }

    /**
     * Runs the in-loop filters with the per-row line backups of the threaded layout: deblocks every
     * superblock row in order, then runs CDEF on the rows in parallel bands. The picture is the one
     * the row-by-row filter gives.
     *
     * @param threads the most threads to use
     */
    private void filterRows(int threads) {
        for (var sby = 0; !deblockedInLoop && sby < f.sbh; sby++) {
            EncoderBridge.deblockSbrow(f, sby);
        }
        if (!f.seqHdr.cdef) {
            return;
        }
        var rows = f.sbh;
        var bands = Math.min(threads, rows);
        if (filterTasks.length < bands) {
            var grown = Arrays.copyOf(filterTasks, bands);
            for (var i = filterTasks.length; i < bands; i++) {
                grown[i] = new TaskContext();
            }
            filterTasks = grown;
        }
        ParallelBands.run(bands, band -> {
            var t = filterTasks[band];
            t.f = f;
            var last = ParallelBands.start(band + 1, bands, rows);
            for (var sby = ParallelBands.start(band, bands, rows); sby < last; sby++) {
                EncoderBridge.cdefSbrow(t, f, sby);
            }
        });
    }

    /**
     * Keeps the frame {@link #code} produced last: updates the motion and refresh statistics,
     * stores the reconstruction in the slots the frame refreshes and makes the source the previous
     * one.
     */
    void commit() {
        var hdr = pending;
        pending = null;
        if (cyclicRefresh != null) {
            long seg1 = 0;
            long seg2 = 0;
            for (var tile : tiles) {
                seg1 += tile.segmentCounts[0];
                seg2 += tile.segmentCounts[1];
            }
            for (var i = 0; rowParallel && i < workers.length; i++) {
                seg1 += workers[i].segmentCounts[0];
                seg2 += workers[i].segmentCounts[1];
            }
            cyclicRefresh.actualNumSeg1Blocks = seg1;
            cyclicRefresh.actualNumSeg2Blocks = seg2;
        }
        if (hdr.isInterOrSwitch()) {
            long zero = 0;
            for (var tile : tiles) {
                zero += tile.zeroMvCount;
            }
            for (var i = 0; rowParallel && i < workers.length; i++) {
                zero += workers[i].zeroMvCount;
            }
            var pct = (int) (100 * zero / ((long) f.bw * f.bh));
            avgFrameLowMotion = avgFrameLowMotion == 0 ? pct : (3 * avgFrameLowMotion + pct) / 4;
        }
        framesSinceGolden = params.goldenUpdate || params.key ? 0 : framesSinceGolden + 1;
        refresh(hdr);
        swapSources();
    }

    /**
     * Throws away the frame {@link #code} produced last: the references and statistics stay as
     * they were and the source stays loaded.
     */
    void discard() {
        pending = null;
        f.cur.release();
        for (var i = 0; i < 7; i++) {
            f.refp[i] = null;
        }
        f.cur = null;
        f.srCur = null;
    }

    /**
     * Builds, writes and parses back the frame header, so every derived field matches what a
     * decoder derives.
     *
     * @param p the frame parameters
     * @return the parsed frame header
     */
    private FrameHeader buildHeader(FrameParams p) {
        var h = draft;
        h.frameType = p.key ? FrameHeader.KEY : FrameHeader.INTER;
        h.showFrame = p.showFrame;
        h.showableFrame = !p.key;
        h.errorResilientMode = p.key || p.errorResilient;
        h.disableCdfUpdate = false;
        h.frameOffset = p.orderHint & ((1 << ORDER_HINT_BITS) - 1);
        h.primaryRefFrame = p.key || p.errorResilient ? FrameHeader.PRIMARY_REF_NONE : p.primaryRefFrame;
        h.refreshFrameFlags = p.key ? 0xFF : p.refreshFlags;
        System.arraycopy(p.refIdx, 0, h.refidx, 0, 7);
        h.hp = p.allowHighPrecisionMv;
        h.subpelFilterMode = p.interpFilter;
        h.switchableMotionMode = p.warpedMotion && seq.warpedMotion;
        h.warpMotion = h.switchableMotionMode;
        h.useRefFrameMvs = seq.refFrameMvs && seq.orderHint && !h.errorResilientMode && p.refFrameMvs;
        h.refreshContext = true;
        ObuWriter.uniformTiling(h, sbCols, sbRows, p.tileColsLog2, 0);
        h.quantYac = p.qindex;
        h.quantYdcDelta = 0;
        h.quantUdcDelta = 0;
        h.quantUacDelta = 0;
        h.segmentationEnabled = p.segmentation;
        if (p.segmentation) {
            h.segmentationUpdateMap = true;
            h.segmentationTemporal = false;
            h.segmentationUpdateData = true;
            System.arraycopy(p.segDeltaQ, 0, h.segDeltaQ, 0, 8);
        }
        for (var i = 0; i < 8; i++) {
            h.segRef[i] = -1;
        }
        h.loopfilterLevelY[0] = p.lfLevelY[0];
        h.loopfilterLevelY[1] = p.lfLevelY[1];
        h.loopfilterLevelU = p.lfLevelU;
        h.loopfilterLevelV = p.lfLevelV;
        h.modeRefDeltaEnabled = false;
        h.cdefDamping = p.cdefDamping;
        // TODO: skip CDEF on static superblocks (skip_cdef_sb) and code loop restoration
        h.cdefNBits = 0;
        h.cdefYStrength[0] = p.cdefYStrength;
        h.cdefUvStrength[0] = p.cdefUvStrength;
        h.txfmMode = p.txSwitchable ? FrameHeader.TX_SWITCHABLE : FrameHeader.TX_LARGEST;
        h.switchableCompRefs = !p.key && p.referenceSelect;
        h.skipModeEnabled = h.switchableCompRefs && p.skipMode;
        var w = obuWriter.frameHeader(seq, h, refOrderHint, refHdr);
        var gb = headerReader;
        gb.init(w.buffer(), 0, w.size());
        var parsed = unsharedHeader();
        parsed.temporalId = p.temporalId;
        if (!parsed.parse(seq, gb, refHdr, true)) {
            throw new IllegalStateException("the encoder wrote a frame header it cannot parse");
        }
        return parsed;
    }

    /**
     * Returns a reset header from {@link #headers} that nothing shares, adding a new one when
     * every header is shared.
     *
     * @return the header to parse into
     */
    private FrameHeader unsharedHeader() {
        for (var i = 0; i < headers.size(); i++) {
            var header = headers.get(i);
            if (!shared(header)) {
                header.reset();
                return header;
            }
        }
        var header = new FrameHeader();
        headers.add(header);
        return header;
    }

    /**
     * Returns whether a reference slot, a reference picture, the current frame or the pending
     * frame still uses a header.
     *
     * @param header a header from {@link #headers}
     * @return {@code true} when the header is in use
     */
    private boolean shared(FrameHeader header) {
        if (header == pending || header == f.frameHdr) {
            return true;
        }
        for (var i = 0; i < SLOTS; i++) {
            if (refHdr[i] == header || refPic[i] != null && refPic[i].frameHdr == header) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prepares the frame context for the frame: references, geometry, motion state, CDFs,
     * quantizers and the decoder's frame setup.
     *
     * @param hdr the frame header
     * @param rowFilters whether the in-loop filter buffers take the per-row layout that lets
     * {@link #filterRows} run CDEF on several rows at once
     */
    private void setupFrame(FrameHeader hdr, boolean rowFilters) {
        f.seqHdr = seq;
        f.frameHdr = hdr;
        var inter = hdr.isInterOrSwitch();
        for (var i = 0; i < 7; i++) {
            f.refp[i] = inter ? refPic[hdr.refidx[i]] : null;
            f.refMvs[i] = inter && hdr.useRefFrameMvs ? slotMvs[hdr.refidx[i]] : null;
            if (f.refMvs[i] != null) {
                System.arraycopy(slotRefPoc[hdr.refidx[i]], 0, f.refrefpoc[i], 0, 7);
            }
            f.svcScale[i * 2] = 0;
            f.svcScale[i * 2 + 1] = 0;
            f.gmvWarpAllowed[i] = false;
            f.refpoc[i] = inter ? refOrderHint[hdr.refidx[i]] : 0;
        }
        var cur = pool.obtain(width, height, SequenceHeader.LAYOUT_I420, 8);
        cur.frameHdr = hdr;
        cur.seqHdr = seq;
        f.cur = cur;
        f.srCur = cur;
        f.w4 = (hdr.codedWidth + 3) >> 2;
        f.h4 = (hdr.height + 3) >> 2;
        f.bw = ((hdr.codedWidth + 7) >> 3) << 1;
        f.bh = ((hdr.height + 7) >> 3) << 1;
        f.sb128w = (f.bw + 31) >> 5;
        f.sb128h = (f.bh + 31) >> 5;
        f.sbShift = 4;
        f.sbStep = 16;
        f.sbh = (f.bh + f.sbStep - 1) >> f.sbShift;
        f.b4Stride = (f.bw + 31) & ~31;
        f.bitdepthMax = 255;
        f.haveTt = rowFilters;
        if (inter) {
            var size = f.sb128h * 16 * (f.b4Stride >> 1);
            if (f.mvs == null || f.mvs.size() != size) {
                f.mvs = takeMvs(size);
            }
        }
        if (hdr.segmentationEnabled) {
            var size = f.b4Stride * 32 * f.sb128h;
            if (segmapStore == null || segmapStore.length != size) {
                segmapStore = new byte[size];
            }
            f.curSegmap = segmapStore;
        } else {
            f.curSegmap = null;
        }
        f.prevSegmap = null;
        if (hdr.primaryRefFrame == FrameHeader.PRIMARY_REF_NONE) {
            f.inCdf = defaults(hdr.quantYac);
        } else {
            var cdf = refCdf[hdr.refidx[hdr.primaryRefFrame]];
            f.inCdf = cdf != null ? cdf : defaults(hdr.quantYac);
        }
        if (hdr.refreshContext && outCdf == null) {
            outCdf = new char[Cdf.SIZE];
        }
        f.outCdf = hdr.refreshContext ? outCdf : null;
        EncoderBridge.initFrame(f, limits);
        var keyOrIntra = hdr.isKeyOrIntra();
        for (var n = 0; n < f.sb128w * hdr.tilingRows; n++) {
            f.a[n].reset(keyOrIntra);
        }
        if (inter) {
            if (tileMvs.length != hdr.tilingCols) {
                tileMvs = new RefMvs.Frame[hdr.tilingCols];
                for (var i = 0; i < tileMvs.length; i++) {
                    tileMvs[i] = new RefMvs.Frame();
                }
            }
            tileMvs[0] = f.rf;
            for (var i = 1; i < tileMvs.length; i++) {
                if (tileMvs[i] == f.rf) {
                    tileMvs[i] = new RefMvs.Frame();
                }
                tileMvs[i].init(seq, hdr, f.refpoc, f.mvs, f.refrefpoc, f.refMvs, 1);
            }
        }
        baseQindex = hdr.quantYac;
        rd.setup(f.inCdf, hdr.quantYac, hdr.isKeyOrIntra(), params.goldenUpdate, hdr.hp);
        if (params.rdmultScale != 128) {
            rd.scale(params.rdmultScale);
        }
        if (hdr.segmentationEnabled && cyclicRefresh != null) {
            // libaom codes both boosted segments with the multiplier of the first one's quantizer.
            var boosted = RdCost.rdmult(hdr.segQidx[CyclicRefresh.BOOST1], false, params.goldenUpdate);
            rd.segmentRdmult[CyclicRefresh.BOOST1] = boosted;
            rd.segmentRdmult[CyclicRefresh.BOOST2] = boosted;
        }
        for (var s = 0; s < 8; s++) {
            var qidx = hdr.segQidx[s];
            quantizers[s * 3].set(qidx, hdr.quantYdcDelta, 0);
            quantizers[s * 3 + 1].set(qidx, hdr.quantUdcDelta, hdr.quantUacDelta);
            quantizers[s * 3 + 2].set(qidx, hdr.quantVdcDelta, hdr.quantVacDelta);
        }
    }

    /**
     * Stores the reconstruction, header and CDFs in the slots the frame refreshes.
     *
     * @param hdr the frame header
     */
    private void refresh(FrameHeader hdr) {
        var cur = f.cur;
        var cdf = hdr.refreshContext ? f.outCdf : f.inCdf;
        RefFrame padded = null;
        if (hdr.refreshFrameFlags != 0) {
            padded = freeRefs.isEmpty() ? new RefFrame() : freeRefs.pop();
            padded.build(cur, width, height, lastThreads);
            padded.orderHint = hdr.frameOffset;
        }
        for (var i = 0; i < SLOTS; i++) {
            if ((hdr.refreshFrameFlags & (1 << i)) != 0) {
                var old = slotRef[i];
                slotRef[i] = padded;
                if (old != null && old != padded && !held(old)) {
                    freeRefs.push(old);
                }
                if (refPic[i] != null) {
                    refPic[i].release();
                }
                refPic[i] = cur.retain();
                refHdr[i] = hdr;
                if (refCdfStore[i] == null) {
                    refCdfStore[i] = new char[Cdf.SIZE];
                }
                if (refCdfStore[i] != cdf) {
                    System.arraycopy(cdf, 0, refCdfStore[i], 0, Cdf.SIZE);
                }
                refCdf[i] = refCdfStore[i];
                refOrderHint[i] = hdr.frameOffset;
            }
        }
        if (hdr.refreshFrameFlags != 0 && f.mvs != null) {
            for (var i = 0; i < SLOTS; i++) {
                if ((hdr.refreshFrameFlags & (1 << i)) != 0) {
                    var old = slotMvs[i];
                    slotMvs[i] = f.mvs;
                    releaseMvs(old);
                    System.arraycopy(f.refpoc, 0, slotRefPoc[i], 0, 7);
                }
            }
            f.mvs = null;
        }
        cur.release();
        for (var i = 0; i < 7; i++) {
            f.refp[i] = null;
        }
        f.cur = null;
        f.srCur = null;
    }

    /**
     * Returns whether a slot still holds an edge-extended reference.
     *
     * @param ref the reference
     * @return {@code true} when a slot points to it
     */
    private boolean held(RefFrame ref) {
        for (var r : slotRef) {
            if (r == ref) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FrameContext context() {
        return f;
    }

    @Override
    public FrameHeader header() {
        return f.frameHdr;
    }

    @Override
    public Av1Picture sourcePicture() {
        return source;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int layout() {
        return SequenceHeader.LAYOUT_I420;
    }

    @Override
    public int ssHor() {
        return 1;
    }

    @Override
    public int ssVer() {
        return 1;
    }

    @Override
    public int bitDepth() {
        return 8;
    }

    @Override
    public int bdMax() {
        return 255;
    }

    @Override
    public int hbd() {
        return 0;
    }

    @Override
    public int distShift() {
        return 0;
    }

    @Override
    public int sbCols() {
        return sbCols;
    }

    @Override
    public int sbRows() {
        return sbRows;
    }

    @Override
    public int rdmult() {
        return rd.rdmult;
    }

    @Override
    public boolean parallel() {
        return lastThreads > 1;
    }

    @Override
    public void setCdefIndex(int sbIndex, int idx) {
        sbCdef[sbIndex] = idx;
    }

    /**
     * Releases the reference pictures.
     */
    void close() {
        for (var i = 0; i < SLOTS; i++) {
            if (refPic[i] != null) {
                refPic[i].release();
                refPic[i] = null;
            }
            refHdr[i] = null;
            refCdf[i] = null;
        }
    }
}
