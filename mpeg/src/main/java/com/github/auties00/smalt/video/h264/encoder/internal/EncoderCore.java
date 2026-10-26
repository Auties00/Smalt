package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.video.h264.encoder.H264PacketFormat;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.internal.H264Nal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Runs the frame-level encoding loop: input conversion, analysis, frame type and quantizer
 * decisions, macroblock decisions and entropy coding, the deblocking filter, reference
 * management and packet assembly.
 *
 * <p>Pictures are coded as the slices the configuration fixes, divided further at a slice size
 * limit. P pictures predict from up to {@link EncoderSettings#refs()} previous reference pictures
 * in a sliding window, or with temporal layers from the latest picture of a lower layer, the
 * highest layer's pictures being no references; B pictures and look-ahead delay the packets as
 * {@link #encode} describes. Intra refresh codes a band of intra macroblock columns in every P
 * picture and keeps the vectors of the columns left of it inside the refreshed area.
 *
 * <p>With several workers, the macroblock rows of a picture are decided in a wavefront on helper
 * threads of the module's shared pool: each participant claims the next undecided row and stays
 * two macroblocks behind the row above, while the calling thread entropy codes the decided
 * macroblocks in raster order, reading them from a ring of row buffers. When the row the calling
 * thread codes next is still unclaimed, it claims and decides that row itself, coding each
 * macroblock as soon as it is decided, so a picture completes even when no helper is free; and
 * while fewer helpers run than there are workers, the calling thread also decides rows ahead
 * whenever it has caught up with the decisions. Since {@link MacroblockAnalyzer} decisions depend
 * neither on the entropy coder nor on the rows an analyzer decided before, the stream is the same
 * for every number of threads and every thread scheduling.
 *
 * <p>Key frame requests and bitrate changes may come from any thread while a frame is being
 * encoded: both are handed over through atomic variables that the encoding thread reads once per
 * frame, at the frame boundary. A request that arrives while an IDR picture is being encoded is
 * served by that picture, since it leaves the encoder after the request; any other request makes
 * the next frame an IDR picture, so no request is ever lost.
 *
 * <p>A reconstruction becomes a reference picture once it is deblocked, padded and interpolated,
 * one macroblock row at a time. With several workers that work is deferred to the next frame,
 * where it runs in one row wavefront together with the look-ahead analysis of the new source
 * picture (see {@link #prepare}): neither reads what the other writes, and the next picture's
 * macroblock decisions need both. With a single worker it runs right after the picture is coded.
 *
 * @implNote This implementation starts no threads of its own. Each wavefront starts, with
 * {@link ParallelBands#start(Runnable)}, at most as many participants as the configured workers
 * allow and {@link ParallelBands#available()} reports free, so concurrent encoders share the
 * pool's processors instead of each starting its own threads; the calling thread takes part, and
 * before returning it cancels the participants no helper has claimed and waits for the others.
 * Participants are started computations rather than {@link ParallelBands#run bands} because they
 * wait for rows other participants decide, and a waiting thread of another operation may run a
 * band but never another thread's started computation. Rows are claimed in order from a shared
 * counter, so every row a participant waits for belongs to a participant that is running it, and
 * every such wait is declared to the pool (see {@link Wavefront}), which lets another helper take
 * posted work while a helper is blocked. The encoder finishes the reconstruction immediately with
 * a single worker because interleaving its rows with the analysis of the next picture only pays
 * off when several workers would otherwise wait for each other at the ends of two separate
 * wavefronts; on one thread it is slightly slower than finishing the picture while its samples
 * are still cached.
 */
public final class EncoderCore implements EncoderEngine {
    /**
     * The ratio of inter to intra cost above which a picture starts a new scene.
     */
    private static final double SCENE_CUT_THRESHOLD = 0.72;

    /**
     * The factor by which a picture's inter cost must exceed the recent average for a scene cut.
     */
    private static final double SCENE_CUT_JUMP = 1.5;

    /**
     * The largest adaptive quantization offset, in quantizer steps.
     */
    private static final int AQ_MAX_OFFSET = 6;

    /**
     * The stream settings.
     */
    private final EncoderSettings settings;

    /**
     * The configuration.
     */
    private final EncoderConfig config;

    /**
     * The input converter.
     */
    private final InputConverter converter;

    /**
     * The source picture of the look-ahead frame being analyzed.
     */
    private Frame analysisSource;

    /**
     * The look-ahead frames that were analyzed and wait to be encoded, in display order.
     */
    private final List<LookaheadFrame> queue = new ArrayList<>();

    /**
     * The look-ahead frames not in {@link #queue}.
     */
    private final List<LookaheadFrame> spare = new ArrayList<>();

    /**
     * The frame being encoded: the first of {@link #queue}.
     */
    private LookaheadFrame head;

    /**
     * The number of pictures that are analyzed before the first one is encoded, {@code 0} to
     * encode each picture as it arrives.
     */
    private final int lookahead;

    /**
     * The macroblock tree estimator, or {@code null} without look-ahead.
     */
    private final MacroblockTree tree;

    /**
     * The macroblock tree quantizer offsets of the frame being encoded.
     */
    private final float[] treeOffsets;

    /**
     * Whether {@link #treeOffsets} holds the offsets of the frame being encoded.
     */
    private boolean treeActive;

    /**
     * Whether each look-ahead frame after the first starts a new group of pictures.
     */
    private final boolean[] treeCuts;

    /**
     * The macroblock tree strength in quantizer steps per doubling of the carried cost.
     */
    private final double treeStrength;

    /**
     * The default macroblock tree strength.
     */
    private static final double TREE_STRENGTH = 2.0;

    /**
     * The moving average of the mean macroblock tree offset of the pictures coded so far.
     */
    private double treeAverage;

    /**
     * Whether {@link #treeAverage} holds a value.
     */
    private boolean treeAverageKnown;

    /**
     * The weight of the newest picture in {@link #treeAverage}.
     */
    private static final double TREE_MEMORY = 0.05;

    /**
     * The number of consecutive B pictures between anchors, {@code 0} for none.
     */
    private final int bframes;

    /**
     * The analyzed pictures in coding order whose turn is next, with the anchor first.
     */
    private final List<LookaheadFrame> plan = new ArrayList<>();

    /**
     * The look-ahead frames of the macroblock tree: the frame being encoded and the queue.
     */
    private final List<LookaheadFrame> treeFrames = new ArrayList<>();

    /**
     * The number of pictures submitted.
     */
    private long submitted;

    /**
     * The number of pictures submitted since the last IDR picture, that one included.
     */
    private long submitFramesSinceIdr;

    /**
     * The display index of the last IDR picture coded.
     */
    private long gopBase;

    /**
     * The timestamps of the latest submitted pictures by display index modulo the length.
     */
    private final long[] ptsRing;

    /**
     * The type of the picture being coded.
     */
    private int pictureType;

    /**
     * The quantizer of the last anchor picture.
     */
    private int anchorQp = 26;

    /**
     * The quantizer steps by which a B picture is coarser than its anchor.
     */
    private static final int B_QUANTIZER_OFFSET = 3;

    /**
     * The quantizer steps by which a referenced B picture is coarser than its anchor.
     */
    private static final int B_REFERENCED_OFFSET = 2;

    /**
     * The quantizer steps by which an unreferenced B picture of a pyramid is coarser than its
     * anchor.
     */
    private static final int PYRAMID_B_OFFSET = 3;

    /**
     * The coding order and roles of the B pictures of each group length.
     */
    private final BStructure[] structures;

    /**
     * The motion seed of the picture being encoded.
     */
    private final int[] seedVectors;

    /**
     * The look-ahead frames of one motion trajectory.
     */
    private final List<LookaheadFrame> seedChain = new ArrayList<>();

    /**
     * The referenced B pictures of the open group, in coding order.
     */
    private final List<Frame> bRefs = new ArrayList<>();

    /**
     * The bits of the pictures of the open group of an anchor and its B pictures so far.
     */
    private long groupBits;

    /**
     * The quantizer of the open group's anchor.
     */
    private int groupQp;

    /**
     * The complexity of the open group.
     */
    private long groupComplexity;

    /**
     * The duration in seconds of the open group.
     */
    private double groupSeconds;

    /**
     * Whether the reconstruction of an unreferenced picture is deblocked so that
     * {@link #lastReconstructionI420()} can return it.
     */
    private boolean reconstructUnreferenced;

    /**
     * The display index of the last picture coded.
     */
    private long lastDisplayIndex = -1;

    /**
     * The number of key frame requests attached to a queued frame.
     */
    private long keyframeRequestsQueued;

    /**
     * The presentation timestamp of the last frame that entered the queue, or
     * {@link Packet#NO_TIMESTAMP}.
     */
    private long lastSubmittedPts = Packet.NO_TIMESTAMP;

    /**
     * The pictures not currently in use as references.
     */
    private final List<Frame> pool = new ArrayList<>();

    /**
     * The reference pictures, most recent first.
     */
    private final Frame[] references;

    /**
     * The number of valid entries of {@link #references}.
     */
    private int referenceCount;

    /**
     * The shared context of the picture being encoded.
     */
    private final PictureContext picture;

    /**
     * The number of workers: the most threads, the calling one included, that decide the rows of
     * a picture, deblock a reconstruction or analyze a source picture at once.
     */
    private final int workers;

    /**
     * Whether the wavefronts may run on helper threads: there are several workers and the
     * module runs tasks on several threads.
     */
    private final boolean parallel;

    /**
     * The operation of the encoder's stream of frames on the module's shared pool.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The macroblock analyzers: the calling thread's at index {@code 0}, then one per helper
     * participant of the slice wavefront.
     */
    private final MacroblockAnalyzer[] analyzers;

    /**
     * The entropy coder of the picture's slice.
     */
    private final SliceWriter writer;

    /**
     * The started participants of the running wavefront, in start order.
     */
    private final ParallelBands.Pending<?>[] participants;

    /**
     * The number of helper participants to start per wavefront, or {@code -1} to ask the pool.
     */
    private int forcedHelpers = -1;

    /**
     * The decided macroblocks of the rows in flight, indexed by row modulo the ring size of
     * twice the workers plus two rows (see {@link #fillIn}), or {@code null} when the encoder
     * runs on the calling thread only.
     */
    private final Macroblock[][] ring;

    /**
     * The next macroblock row of the slice wavefront to claim.
     */
    private final AtomicInteger nextRow = new AtomicInteger();

    /**
     * The CABAC context states of each row after its second macroblock, from which the next
     * row's rate estimates start.
     */
    private final byte[][] rowSync;

    /**
     * The number of decided macroblocks of each row.
     */
    private final Wavefront decided;

    /**
     * The number of rows the writer has finished, as the progress of a single row.
     */
    private final Wavefront written = new Wavefront(1);


    /**
     * The deblocking filters, one per participant of the preparation wavefront.
     */
    private final Deblocker[] deblockers;

    /**
     * The progress counters of the preparation wavefront (see {@link #prepare}): the filtered
     * macroblocks of each row of the reference being finished at rows {@code 0} to
     * {@code mbHeight - 1}, whether each row's band of that reference is padded at the next
     * {@code mbHeight} rows, and the look-ahead progress of each row after them.
     */
    private final Wavefront preparation;

    /**
     * The next row task of the preparation wavefront to hand out.
     */
    private final AtomicInteger tickets = new AtomicInteger();

    /**
     * The reconstruction of the last coded picture while it still waits to be deblocked, padded
     * and interpolated, or {@code null}.
     */
    private Frame pending;

    /**
     * The picture analyzer.
     */
    private final FrameAnalyzer analyzer;

    /**
     * The rate controller.
     */
    private final RateController rateControl;

    /**
     * The access unit assembler.
     */
    private final NalWriter nal;

    /**
     * The scratch space of half-sample interpolation, one per participant of the preparation
     * wavefront.
     */
    private final short[][] interpolationTemp;

    /**
     * The pixel kernels of the frame being encoded, selected by {@link #selectKernels()}.
     */
    private PixelKernels pixels = Kernels.SCALAR_PIXELS;

    /**
     * The transform kernels of the frame being encoded, selected by {@link #selectKernels()}.
     */
    private TransformKernels transform = Kernels.SCALAR_TRANSFORM;

    /**
     * The interpolation kernels of the frame being encoded, selected by {@link #selectKernels()}.
     */
    private InterpolationKernels interpolation = Kernels.SCALAR_INTERPOLATION;

    /**
     * The escaped sequence parameter set NAL unit.
     */
    private final byte[] sps;

    /**
     * The escaped picture parameter set NAL unit.
     */
    private final byte[] pps;

    /**
     * The decoder configuration record.
     */
    private final byte[] configurationRecord;

    /**
     * The quantizer offset per doubling of a macroblock's AC energy relative to the picture's
     * geometric mean, at the default strength.
     */
    private static final double AQ_SCALE = 0.75;

    /**
     * The reciprocal of the natural logarithm of 2.
     */
    private static final double INVERSE_LOG2 = 1 / StrictMath.log(2);

    /**
     * The scratch per-macroblock adaptive quantization offsets.
     */
    private final double[] aqOffsets;

    /**
     * The adaptive quantization strength, {@code 0} to disable.
     */
    private double aqStrength;

    /**
     * The number of key frame requests received, from any thread.
     */
    private final AtomicLong keyframeRequests = new AtomicLong();

    /**
     * The target bitrate to apply at the next frame boundary, or {@code 0} when unchanged.
     */
    private final AtomicInteger pendingBitrate = new AtomicInteger();

    /**
     * The number of IDR pictures encoded, readable from any thread.
     */
    private volatile long keyframeCount;

    /**
     * The number of bytes of every packet produced, readable from any thread.
     */
    private volatile long byteCount;

    /**
     * The target bitrate rate control aims at, readable from any thread.
     */
    private volatile int appliedBitrate;

    /**
     * The number of frames encoded, readable from any thread.
     */
    private volatile long frameCount;

    /**
     * The number of frames since the last IDR picture.
     */
    private long framesSinceIdr;

    /**
     * The moving average of the inter cost of recent pictures, or {@code -1} when no picture
     * since the last scene cut was analyzed for inter prediction.
     */
    private double recentInterSum = -1;

    /**
     * The {@code frame_num} of the next picture.
     */
    private int frameNum;

    /**
     * The {@code idr_pic_id} of the next IDR picture.
     */
    private int idrPicId;

    /**
     * The nominal frame duration in time-base units, at least 1.
     */
    private final long nominalDuration;

    /**
     * The quantizer of the last coded frame, readable from any thread.
     */
    private volatile int lastQp;

    /**
     * The complexity estimate of the last coded frame.
     */
    private long lastComplexity;

    /**
     * The reconstruction of the last coded frame, deblocked.
     */
    private Frame lastReconstruction;

    /**
     * The time spent in each stage, in nanoseconds: conversion, preparation (the look-ahead
     * analysis, with several workers together with the deblocking, padding and interpolation of
     * the previous reconstruction), macroblock coding, and the deblocking, padding and
     * interpolation of a reconstruction outside the preparation (with a single worker, or when
     * the reconstruction is requested before the next frame).
     */
    private final long[] stageNanos = new long[4];

    /**
     * Whether the encoder is closed.
     */
    private boolean closed;

    /**
     * The SEI payload of the mastering display and content light level messages of every IDR access
     * unit, or {@code null} when the color description carries neither.
     */
    private final byte[] staticSei;

    /**
     * The SEI payload of the recovery point message of the first picture of an intra refresh cycle,
     * or {@code null} without intra refresh.
     */
    private final byte[] recoverySei;

    /**
     * The number of macroblock columns each picture of an intra refresh cycle refreshes, or 0
     * without intra refresh.
     */
    private final int refreshBand;

    /**
     * The first column of the refresh band of the next P picture.
     */
    private int refreshColumn;

    /**
     * The number of temporal layers.
     */
    private final int temporalLayers;

    /**
     * The latest reference picture of each temporal layer since the last IDR picture, or
     * {@code null}.
     */
    private final Frame[] layerLatest = new Frame[4];

    /**
     * The temporal layer of the picture being coded.
     */
    private int temporalLayer;

    /**
     * The temporal layer of the last coded picture, readable from any thread.
     */
    private volatile int lastTemporalLayer;

    /**
     * Whether the P picture being coded is not a reference picture.
     */
    private boolean unreferencedP;

    /**
     * The number of pictures coded since the last IDR picture, that one excluded.
     */
    private long codedSinceIdr;

    /**
     * The address of the first macroblock of the slice being written.
     */
    private int sliceStart;

    /**
     * The payload of a prefix NAL unit (ITU-T H.264 7.3.2.12): the three bytes of
     * {@code nal_unit_header_svc_extension} and, for a reference picture, the
     * {@code prefix_nal_unit_svc} flags with the trailing bits.
     */
    private final byte[] prefixPayload = new byte[4];

    /**
     * The size of the access unit before its first slice NAL unit.
     */
    private int sliceBase;

    /**
     * The CABAC bins of the slices of the picture closed so far.
     */
    private long pictureBins;

    /**
     * The slice data bytes of the slices of the picture closed so far.
     */
    private long pictureBytes;

    /**
     * Constructs a core.
     *
     * @param config the configuration
     * @throws IllegalArgumentException if an explicit level is too small for the stream
     */
    public EncoderCore(EncoderConfig config) {
        this.config = config;
        this.settings = new EncoderSettings(config);
        this.converter = new InputConverter(config.color());
        var codedWidth = settings.codedWidth();
        var codedHeight = settings.codedHeight();
        this.lookahead = settings.lookahead();
        this.bframes = settings.bframes();
        this.ptsRing = new long[lookahead + 3];
        for (var i = 0; i <= lookahead; i++) {
            spare.add(new LookaheadFrame(codedWidth, codedHeight));
        }
        this.tree = lookahead > 0 ? new MacroblockTree(settings.mbWidth(), settings.mbHeight()) : null;
        this.treeOffsets = new float[settings.mbCount()];
        this.treeCuts = new boolean[lookahead + 1];
        this.treeStrength = TREE_STRENGTH;
        for (var i = 0; i <= settings.numRefFrames(); i++) {
            pool.add(new Frame(codedWidth, codedHeight));
        }
        this.seedVectors = new int[settings.mbCount()];
        this.structures = new BStructure[bframes + 1];
        for (var i = 0; i <= bframes; i++) {
            structures[i] = new BStructure(i, settings.pyramid());
        }
        this.references = new Frame[settings.refs()];
        this.picture = new PictureContext(settings);
        this.workers = settings.workers();
        this.parallel = workers > 1 && ParallelBands.enabled();
        var threads = parallel ? workers : 1;
        this.analyzers = new MacroblockAnalyzer[parallel ? workers + 1 : 1];
        for (var i = 0; i < analyzers.length; i++) {
            analyzers[i] = new MacroblockAnalyzer(settings, pixels, transform, interpolation);
        }
        this.writer = new SliceWriter(settings, pixels);
        this.participants = new ParallelBands.Pending<?>[threads];
        if (parallel) {
            this.ring = new Macroblock[2 * workers + 2][settings.mbWidth()];
            for (var row : ring) {
                for (var i = 0; i < row.length; i++) {
                    row[i] = new Macroblock();
                }
            }
        } else {
            this.ring = null;
        }
        this.rowSync = new byte[settings.mbHeight()][CabacTables.CONTEXTS];
        this.decided = new Wavefront(settings.mbHeight());
        this.deblockers = new Deblocker[threads];
        for (var i = 0; i < threads; i++) {
            deblockers[i] = new Deblocker(settings.chromaQpOffset());
        }
        this.preparation = new Wavefront(3 * settings.mbHeight());
        this.analyzer = new FrameAnalyzer(pixels, settings.mbWidth(), settings.mbHeight());
        this.rateControl = new RateController(config);
        this.appliedBitrate = config.rateControl() == H264RateControl.CONSTANT_QUALITY ? 0
                : config.bitrate();
        this.nal = new NalWriter(settings.mbCount() * 64, config.packetFormat() == H264PacketFormat.ANNEX_B);
        this.interpolationTemp = new short[threads][codedWidth + 2 * Frame.LUMA_PAD + 16];
        this.sps = NalWriter.unit(3, H264Nal.SPS, ParameterSets.sps(settings));
        this.pps = NalWriter.unit(3, H264Nal.PPS, ParameterSets.pps(settings));
        this.configurationRecord = ParameterSets.avcDecoderConfigurationRecord(settings, sps, pps);
        this.aqStrength = config.realtime() ? 0 : 1.0;
        this.staticSei = ParameterSets.staticSei(config.color());
        this.temporalLayers = settings.temporalLayers();
        if (settings.intraRefresh() > 0) {
            this.refreshBand = Math.ceilDiv(settings.mbWidth(), settings.intraRefresh());
            this.recoverySei = ParameterSets.recoveryPointSei(Math.ceilDiv(settings.mbWidth(), refreshBand) - 1);
        } else {
            this.refreshBand = 0;
            this.recoverySei = null;
        }
        this.aqOffsets = new double[settings.mbCount()];
        var rate = config.frameRate();
        var timeBase = config.timeBase();
        this.nominalDuration = Math.max(1, new com.github.auties00.smalt.track.Rational(rate.denominator(),
                rate.numerator()).rescale(1, timeBase));
    }

    /**
     * Returns the {@code AVCDecoderConfigurationRecord}.
     *
     * @return the record bytes, a copy
     */
    public byte[] configurationRecord() {
        return configurationRecord.clone();
    }

    /**
     * Returns the escaped sequence parameter set NAL unit; a test hook.
     *
     * @return the NAL unit bytes, a copy
     */
    public byte[] sequenceParameterSet() {
        return sps.clone();
    }

    /**
     * Returns the escaped picture parameter set NAL unit; a test hook.
     *
     * @return the NAL unit bytes, a copy
     */
    public byte[] pictureParameterSet() {
        return pps.clone();
    }

    /**
     * Makes the next frame an IDR picture, or the frame being encoded when it is one; callable
     * from any thread.
     */
    public void requestKeyframe() {
        keyframeRequests.incrementAndGet();
    }

    /**
     * Sets the target bitrate from the next frame on; callable from any thread.
     *
     * <p>When several calls arrive before the next frame, the last one wins. The bitrate is
     * capped at the maximum of the stream's level.
     *
     * @param bitsPerSecond the new target bitrate in bits per second, positive
     */
    public void setTargetBitrate(int bitsPerSecond) {
        pendingBitrate.set(bitsPerSecond);
    }

    /**
     * Returns the target bitrate rate control currently aims at; callable from any thread.
     *
     * @return the bitrate in bits per second, or {@code 0} for constant quality
     */
    public int targetBitrate() {
        return appliedBitrate;
    }

    /**
     * Returns the number of frames encoded.
     *
     * @return the frame count
     */
    public long frameCount() {
        return frameCount;
    }

    /**
     * Returns the temporal layer of the last coded picture; callable from any thread.
     *
     * @return the layer, 0 before the first picture
     */
    public int lastTemporalLayer() {
        return lastTemporalLayer;
    }

    /**
     * Returns the number of IDR pictures encoded.
     *
     * @return the IDR count
     */
    public long keyframeCount() {
        return keyframeCount;
    }

    /**
     * Returns the number of key frame requests received.
     *
     * @return the request count
     */
    public long keyframeRequestCount() {
        return keyframeRequests.get();
    }

    /**
     * Returns the total size of the packets produced.
     *
     * @return the byte count
     */
    public long byteCount() {
        return byteCount;
    }

    /**
     * Sets the adaptive quantization strength; a test hook.
     *
     * @param strength the strength, 0 to disable, 1 by default
     */
    public void setAdaptiveQuantization(double strength) {
        this.aqStrength = Math.clamp(strength, 0, 2);
    }

    /**
     * Lowers the size above which a coded macroblock is replaced by {@code I_PCM} on a second
     * pass over its picture; a test hook for that fallback.
     *
     * @param bits the limit in bits, at most the 3200 of Annex A
     */
    public void setMacroblockBitLimit(int bits) {
        writer.setBitLimit(bits);
    }

    /**
     * Returns the quantizer of the last coded frame; callable from any thread.
     *
     * @return the frame quantizer
     */
    public int lastQp() {
        return lastQp;
    }

    /**
     * Returns the quantizer assigned to each macroblock of the last coded frame; a test hook.
     *
     * @return the quantizers in raster order, a copy
     */
    public byte[] macroblockQuantizers() {
        return picture.mbQp.clone();
    }

    /**
     * Returns how many macroblocks of each type were coded so far; a test and profiling hook.
     *
     * @return the counts by {@link MbType} constant, with slot {@link MbType#TRANSFORM_8x8}
     * counting the macroblocks coded with the 8x8 transform, a copy
     */
    public long[] typeCounts() {
        return writer.typeCounts.clone();
    }

    /**
     * Returns the time spent so far in each encoding stage; a profiling hook.
     *
     * @return the nanoseconds spent in conversion, preparation (look-ahead analysis, with several
     * workers together with the deblocking, padding and interpolation of the previous
     * reconstruction), macroblock coding, and the deblocking, padding and interpolation of
     * reconstructions outside the preparation, a copy
     */
    public long[] stageNanos() {
        return stageNanos.clone();
    }

    /**
     * Returns the complexity estimate rate control used for the last coded frame; a profiling
     * hook.
     *
     * @return the half-resolution SATD estimate
     */
    public long lastComplexity() {
        return lastComplexity;
    }

    /**
     * Sets whether the reconstruction of a picture that no other picture references is deblocked
     * as well, so that {@link #lastReconstructionI420()} returns what a decoder outputs for it.
     *
     * <p>The deblocking costs time the encoder never needs, so it is off by default; a test hook.
     *
     * @param enabled whether to reconstruct unreferenced pictures
     */
    public void setReconstructUnreferenced(boolean enabled) {
        this.reconstructUnreferenced = enabled;
    }

    /**
     * Returns the display index of the picture coded last, counted from the first frame given to
     * the encoder; a test hook.
     *
     * @return the index, or -1 before the first picture
     */
    public long lastDisplayIndex() {
        return lastDisplayIndex;
    }

    /**
     * Returns the deblocked reconstruction of the last coded frame, cropped to the display size,
     * as planar 4:2:0 bytes: luma then Cb then Cr; a test hook.
     *
     * <p>When the reconstruction is still waiting to be filtered, it is filtered, padded and
     * interpolated here instead of during the next frame. The last coded frame is the last
     * anchor picture, or an unreferenced picture after {@link #setReconstructUnreferenced}.
     *
     * @return the reconstruction, or an empty array before the first frame
     * @throws IllegalStateException if a thread is interrupted while waiting for another
     * participant
     */
    public byte[] lastReconstructionI420() {
        if (lastReconstruction == null) {
            return new byte[0];
        }
        if (pending != null) {
            try (var _ = ParallelBands.operation(operation)) {
                var stageStart = System.nanoTime();
                var finishing = pending;
                pending = null;
                runPreparation(finishing, false);
                stageNanos[3] += System.nanoTime() - stageStart;
            }
        }
        var width = settings.displayWidth();
        var height = settings.displayHeight();
        var chromaWidth = width / 2;
        var chromaHeight = height / 2;
        var out = new byte[width * height + 2 * chromaWidth * chromaHeight];
        var frame = lastReconstruction;
        Planes.copy(frame.y, frame.lumaOrigin, frame.lumaStride, out, 0, width, width, height);
        var base = width * height;
        Planes.copy(frame.u, frame.chromaOrigin, frame.chromaStride, out, base, chromaWidth, chromaWidth, chromaHeight);
        Planes.copy(frame.v, frame.chromaOrigin, frame.chromaStride, out, base + chromaWidth * chromaHeight,
                chromaWidth, chromaWidth, chromaHeight);
        return out;
    }

    /**
     * Encodes one frame.
     *
     * <p>The frames of an encoder form one operation of the module's shared pool (see
     * {@link ParallelBands#operation(ParallelBands.Operation)}).
     *
     * @param picture the picture, exactly the configured size
     * @param pts the presentation timestamp in time-base units, or
     * {@link Packet#NO_TIMESTAMP}
     * @param duration the frame duration in time-base units, or {@code 0} when unknown
     * @return the packet of the oldest frame that is ready, or {@code null} while the look-ahead
     * queue fills
     * @throws IllegalArgumentException if the picture size differs from the configured size or
     * the timestamp does not increase
     * @throws IllegalStateException if the encoder is closed
     */
    public Packet encode(Picture picture, long pts, long duration) {
        try (var _ = ParallelBands.operation(operation)) {
            return encodeFrame(picture, pts, duration);
        }
    }

    /**
     * Encodes one frame inside the encoder's operation.
     *
     * @param picture the picture, exactly the configured size
     * @param pts the presentation timestamp in time-base units, or
     * {@link Packet#NO_TIMESTAMP}
     * @param duration the frame duration in time-base units, or {@code 0} when unknown
     * @return the packet of the frame
     * @throws IllegalArgumentException if the picture size differs from the configured size or,
     * outside real-time mode, the timestamp does not increase
     * @throws IllegalStateException if the encoder is closed
     */
    private Packet encodeFrame(Picture picture, long pts, long duration) {
        if (closed) {
            throw new IllegalStateException("encoder is closed");
        }
        if (picture.width() != config.width() || picture.height() != config.height()) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + config.width() + "x" + config.height());
        }
        var previous = lastSubmittedPts;
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
        lastSubmittedPts = pts;
        applyPendingBitrate();
        selectKernels();
        var entry = spare.removeLast();
        entry.pts = pts;
        entry.duration = duration;
        entry.nominal = nominal;
        entry.seconds = nominal && duration <= 0 ? 1 / config.frameRate().toDouble()
                : frameSeconds(pts, duration, previous);
        entry.displayIndex = submitted;
        ptsRing[(int) (submitted % ptsRing.length)] = pts;
        var requests = keyframeRequests.get();
        entry.keyframeRequested = requests != keyframeRequestsQueued;
        keyframeRequestsQueued = requests;
        var stageStart = System.nanoTime();
        converter.convert(picture, config.width(), config.height(), entry.source);
        stageNanos[0] += System.nanoTime() - stageStart;
        var forced = submitted == 0 || entry.keyframeRequested
                || (config.keyframeInterval() > 0 && refreshBand == 0
                && submitFramesSinceIdr >= config.keyframeInterval());
        stageStart = System.nanoTime();
        analysisSource = entry.source;
        prepare(lookahead > 0 || !forced);
        entry.store(analyzer);
        stageNanos[1] += System.nanoTime() - stageStart;
        var idr = forced;
        if (!idr && refreshBand == 0) {
            if (submitFramesSinceIdr >= minimumSceneDistance()
                    && entry.interSum > SCENE_CUT_THRESHOLD * entry.intraSum
                    && (recentInterSum < 0 || entry.interSum > SCENE_CUT_JUMP * recentInterSum)) {
                idr = true;
                recentInterSum = -1;
            } else {
                recentInterSum = recentInterSum < 0 ? entry.interSum
                        : 0.7 * recentInterSum + 0.3 * entry.interSum;
            }
        }
        entry.idr = idr;
        submitFramesSinceIdr = idr ? 1 : submitFramesSinceIdr + 1;
        submitted++;
        queue.add(entry);
        if (queue.size() + plan.size() <= lookahead) {
            return null;
        }
        return encodeNext();
    }

    @Override
    public long lastSubmittedPts() {
        return lastSubmittedPts;
    }

    /**
     * Encodes the pictures still queued, one per call.
     *
     * @return the packet of the next queued frame in coding order, or {@code null} when none is
     * left
     * @throws IllegalStateException if the encoder is closed
     */
    public Packet flush() {
        try (var _ = ParallelBands.operation(operation)) {
            if (closed) {
                throw new IllegalStateException("encoder is closed");
            }
            if (queue.isEmpty() && plan.isEmpty()) {
                return null;
            }
            selectKernels();
            if (pending != null) {
                var stageStart = System.nanoTime();
                var finishing = pending;
                pending = null;
                runPreparation(finishing, false);
                stageNanos[3] += System.nanoTime() - stageStart;
            }
            return encodeNext();
        }
    }

    /**
     * Chooses the pictures to code next from the head of the display-order queue: an anchor
     * picture of up to {@link #bframes} pictures ahead, followed by the B pictures it leaves
     * behind, and moves them to the coding-order plan.
     */
    private void buildPlan() {
        var first = queue.getFirst();
        var count = 0;
        if (bframes > 0 && !first.idr) {
            var limit = Math.min(bframes, queue.size() - 1);
            for (var k = 1; k <= limit; k++) {
                if (queue.get(k).idr) {
                    limit = k - 1;
                    break;
                }
            }
            count = limit;
        }
        var anchor = queue.get(count);
        anchor.type = anchor.idr ? LookaheadFrame.INTRA : LookaheadFrame.PREDICTED;
        anchor.bCount = count;
        anchor.groupInter = 0;
        anchor.groupSeconds = 0;
        for (var i = 0; i <= count; i++) {
            var frame = queue.get(i);
            anchor.groupInter += frame.interSum;
            anchor.groupSeconds += frame.seconds;
        }
        plan.add(anchor);
        var structure = structures[count];
        for (var k = 0; k < count; k++) {
            var frame = queue.get(structure.order[k] - 1);
            frame.type = structure.reference[k] ? LookaheadFrame.B_REFERENCED : LookaheadFrame.BIDIRECTIONAL;
            plan.add(frame);
        }
        for (var i = 0; i <= count; i++) {
            queue.removeFirst();
        }
    }

    /**
     * Encodes the next picture of the coding-order plan, building the plan first when it is
     * empty.
     *
     * @return the packet of the picture
     */
    private Packet encodeNext() {
        if (plan.isEmpty()) {
            buildPlan();
        }
        head = plan.removeFirst();
        var pts = head.pts;
        var duration = head.duration;
        var seconds = head.seconds;
        var idr = head.idr;
        var type = head.type;
        if (idr) {
            gopBase = head.displayIndex;
        }
        head.poc = 2 * (head.displayIndex - gopBase);
        pictureType = type;
        temporalLayer = 0;
        unreferencedP = false;
        int qp;
        long complexity;
        Frame reconstruction;
        long bits;
        var stageStart = System.nanoTime();
        if (type == LookaheadFrame.BIDIRECTIONAL || type == LookaheadFrame.B_REFERENCED) {
            complexity = head.interSum;
            var offset = type == LookaheadFrame.B_REFERENCED ? B_REFERENCED_OFFSET
                    : settings.pyramid() ? PYRAMID_B_OFFSET : B_QUANTIZER_OFFSET;
            qp = Math.clamp(anchorQp + offset, config.minQuantizer(), config.maxQuantizer());
            reconstruction = pool.removeLast();
            treeActive = false;
            bits = encodePicture(reconstruction, false, qp);
            for (var attempt = 0; attempt < 4 && rateControl.overflows(bits) && qp < config.maxQuantizer();
                 attempt++) {
                qp = rateControl.retryQp(qp, bits);
                bits = encodePicture(reconstruction, false, qp);
            }
            groupBits += bits;
            if (plan.isEmpty()) {
                rateControl.update(false, groupQp, groupBits, groupComplexity, groupSeconds);
            }
            if (type == LookaheadFrame.B_REFERENCED) {
                finishReference(reconstruction, false);
            } else {
                if (reconstructUnreferenced) {
                    runPreparation(reconstruction, false);
                    lastReconstruction = reconstruction;
                }
                pool.add(reconstruction);
            }
        } else {
            var grouped = !idr && head.bCount > 0;
            complexity = idr ? head.intraSum : head.groupInter;
            rateControl.setAnchorSpacing(head.bCount + 1);
            qp = rateControl.frameQp(idr, complexity, grouped ? head.groupSeconds : seconds);
            temporalLayer = idr ? 0 : layerOf(codedSinceIdr + 1);
            unreferencedP = temporalLayers > 1 && temporalLayer == temporalLayers - 1;
            if (temporalLayer > 0) {
                qp = Math.clamp(qp + 2 * temporalLayer, config.minQuantizer(), config.maxQuantizer());
            }
            reconstruction = pool.removeLast();
            computeTree();
            bits = encodePicture(reconstruction, idr, qp);
            if (!grouped) {
                var attempts = config.realtime() ? 1 : 3;
                for (var attempt = 0; attempt < attempts; attempt++) {
                    var corrected = rateControl.correctSize(idr, qp, bits, complexity);
                    if (corrected == qp) {
                        break;
                    }
                    qp = corrected;
                    bits = encodePicture(reconstruction, idr, qp);
                }
            }
            if (grouped && !config.realtime()) {
                for (var attempt = 0; attempt < 3; attempt++) {
                    var corrected = rateControl.correctSize(false, qp, bits, complexity);
                    if (corrected == qp) {
                        break;
                    }
                    qp = corrected;
                    bits = encodePicture(reconstruction, idr, qp);
                }
            }
            for (var attempt = 0; attempt < 4 && rateControl.overflows(bits) && qp < config.maxQuantizer();
                 attempt++) {
                qp = rateControl.retryQp(qp, bits);
                bits = encodePicture(reconstruction, idr, qp);
            }
            if (grouped) {
                groupBits = bits;
                groupQp = qp;
                groupComplexity = complexity;
                groupSeconds = head.groupSeconds;
            } else {
                rateControl.update(idr, treeActive ? meanQuantizer() : qp, bits, complexity, seconds);
            }
            anchorQp = qp;
        }
        lastQp = qp;
        lastComplexity = complexity;
        stageNanos[2] += System.nanoTime() - stageStart;
        if (type == LookaheadFrame.INTRA || type == LookaheadFrame.PREDICTED) {
            codedSinceIdr = idr ? 0 : codedSinceIdr + 1;
            lastTemporalLayer = temporalLayer;
            if (refreshBand > 0) {
                refreshColumn = idr || picture.refreshEnd >= settings.mbWidth() ? 0 : picture.refreshEnd;
            }
            if (unreferencedP) {
                if (reconstructUnreferenced) {
                    runPreparation(reconstruction, false);
                    lastReconstruction = reconstruction;
                }
                pool.add(reconstruction);
            } else {
                finishReference(reconstruction, idr);
            }
        }
        var data = nal.take();
        var packetDuration = duration > 0 ? duration : nominalDuration;
        var dts = pts;
        if (bframes > 0) {
            dts = Math.min(pts, ptsRing[(int) (frameCount % ptsRing.length)] - bframes * nominalDuration);
        }
        frameCount++;
        byteCount += data.length;
        if (idr) {
            keyframeCount++;
        }
        lastDisplayIndex = head.displayIndex;
        spare.add(head);
        head = null;
        var flags = idr ? EnumSet.of(PacketFlag.KEY)
                : unreferencedP ? EnumSet.of(PacketFlag.DISCARDABLE) : EnumSet.noneOf(PacketFlag.class);
        return new Packet(0, data, pts, dts, packetDuration, flags, PacketSideData.NONE);
    }

    /**
     * Returns the temporal layer of a picture from its position after the last IDR picture.
     *
     * @param index the number of pictures from the last IDR picture to this one, 0 for the IDR
     * picture itself
     * @return the layer, from 0 to {@link #temporalLayers} minus 1
     */
    private int layerOf(long index) {
        if (temporalLayers == 1) {
            return 0;
        }
        var position = (int) (index & ((1L << (temporalLayers - 1)) - 1));
        return position == 0 ? 0 : temporalLayers - 1 - Integer.numberOfTrailingZeros(position);
    }

    /**
     * Returns the mean macroblock quantizer of the frame just coded.
     *
     * @return the mean, rounded
     */
    private int meanQuantizer() {
        var sum = 0L;
        for (var value : picture.mbQp) {
            sum += value;
        }
        return (int) Math.round((double) sum / picture.mbQp.length);
    }

    /**
     * Computes the macroblock tree offsets of the frame about to be encoded from the pictures
     * queued after it.
     */
    private void computeTree() {
        treeActive = false;
        if (tree == null || treeStrength <= 0 || queue.isEmpty()) {
            return;
        }
        treeFrames.clear();
        treeFrames.add(head);
        treeFrames.addAll(queue);
        var count = treeFrames.size();
        for (var j = 1; j < count; j++) {
            treeCuts[j] = treeFrames.get(j).idr;
        }
        tree.compute(treeFrames, count, treeCuts, treeStrength, treeOffsets);
        var sum = 0.0;
        for (var offset : treeOffsets) {
            sum += offset;
        }
        var mean = sum / treeOffsets.length;
        treeAverage = treeAverageKnown ? treeAverage + TREE_MEMORY * (mean - treeAverage) : mean;
        treeAverageKnown = true;
        treeActive = true;
    }

    /**
     * Selects the kernels of the frame about to be encoded, and of the pending reconstruction it
     * finishes, and hands them to every component that computes with them.
     *
     * <p>Every implementation of a kernel family produces the same results, so the selection
     * never changes the bitstream (see {@link Kernels}).
     */
    private void selectKernels() {
        var pixels = Kernels.pixels();
        var transform = Kernels.transform();
        var interpolation = Kernels.interpolation();
        if (pixels == this.pixels && transform == this.transform && interpolation == this.interpolation) {
            return;
        }
        this.pixels = pixels;
        this.transform = transform;
        this.interpolation = interpolation;
        for (var macroblockAnalyzer : analyzers) {
            macroblockAnalyzer.useKernels(pixels, transform, interpolation);
        }
        writer.useKernels(pixels);
        analyzer.useKernels(pixels);
    }

    /**
     * Hands the last bitrate set since the previous frame to rate control, capped at the maximum
     * bitrate of the stream's level.
     */
    private void applyPendingBitrate() {
        var requested = pendingBitrate.getAndSet(0);
        if (requested <= 0) {
            return;
        }
        var factor = (long) Levels.bitrateFactor(config.profile());
        var bitrate = (int) Math.min(requested, settings.level().maxBr() * factor);
        rateControl.setBitrate(bitrate);
        if (config.rateControl() != H264RateControl.CONSTANT_QUALITY) {
            appliedBitrate = bitrate;
        }
    }

    /**
     * Analyzes the current source picture and finishes the pending reconstruction, if any, in
     * one row wavefront.
     *
     * <p>The two jobs are independent: the analysis reads the source picture and the previous
     * half-resolution picture, while finishing deblocks, pads and interpolates the last
     * reconstruction. Their macroblock rows are handed out alternately to the participants,
     * which wait only on rows handed out before theirs, so both wavefronts advance together and
     * the participants idle only at the end of the pictures. The result does not depend on the
     * number of participants or the scheduling.
     *
     * @param searchInter whether the analysis runs the inter search
     * @throws IllegalStateException if a thread is interrupted while waiting for another
     * participant
     */
    private void prepare(boolean searchInter) {
        var finishing = pending;
        pending = null;
        analyzer.start(searchInter, aqStrength > 0);
        runPreparation(finishing, true);
        analyzer.finish();
    }

    /**
     * Runs the preparation wavefront on the calling thread and the helper participants the pool
     * can spare: finishes a reconstruction, analyzes the current source picture, or both.
     *
     * @param finishing the reconstruction to deblock, pad and interpolate, or {@code null}
     * @param analyze whether to analyze the current source picture, after
     * {@link FrameAnalyzer#start}
     * @throws IllegalStateException if the calling thread is interrupted while waiting for a
     * participant
     */
    private void runPreparation(Frame finishing, boolean analyze) {
        if (finishing != null) {
            finishing.allocateHalfPlanes();
        }
        preparation.reset();
        tickets.set(0);
        var info = picture.info;
        parallel(helpers(workers - 1), worker -> prepareRows(worker, finishing, info, analyze), preparation);
    }

    /**
     * Takes row tasks of the preparation wavefront until none is left: with both jobs, even
     * tasks finish a row of the reconstruction and odd ones analyze a row of the source picture.
     *
     * <p>Tasks are handed out in order and each waits only for tasks handed out before it, which
     * their participants are running, so any number of participants completes the wavefront.
     *
     * @param worker the participant index
     * @param finishing the reconstruction to finish, or {@code null}
     * @param info the coded parameters of the reconstruction
     * @param analyze whether to analyze the current source picture
     */
    private void prepareRows(int worker, Frame finishing, PictureInfo info, boolean analyze) {
        var mbHeight = settings.mbHeight();
        var finish = finishing != null;
        var both = finish && analyze;
        var count = (finish ? mbHeight : 0) + (analyze ? mbHeight : 0);
        for (var ticket = tickets.getAndIncrement(); ticket < count; ticket = tickets.getAndIncrement()) {
            var row = both ? ticket >> 1 : ticket;
            if (finish && (!both || (ticket & 1) == 0)) {
                finishRow(worker, finishing, info, row);
            } else {
                analyzer.analyzeRow(analysisSource, row, preparation, 2 * mbHeight);
            }
        }
    }

    /**
     * Deblocks one macroblock row of a reconstruction, pads the rows that became final and
     * interpolates the band of rows that became computable.
     *
     * @param worker the participant index
     * @param frame the reconstruction
     * @param info the coded parameters of the reconstruction
     * @param mbY the macroblock row
     */
    private void finishRow(int worker, Frame frame, PictureInfo info, int mbY) {
        var mbHeight = settings.mbHeight();
        deblockers[worker].filterRow(frame, info, mbY, preparation, 0);
        frame.padBand(mbY);
        preparation.publish(mbHeight + mbY, 1);
        if (mbY > 0) {
            preparation.await(mbHeight + mbY - 1, 1);
        }
        frame.interpolateBand(mbY, interpolation, interpolationTemp[worker]);
    }

    /**
     * Returns the smallest number of frames between an IDR picture and a scene cut.
     *
     * @return the distance in frames
     */
    private long minimumSceneDistance() {
        var distance = Math.max(2, Math.round(config.frameRate().toDouble() / 4));
        return config.realtime() ? distance : Math.min(distance, 2);
    }

    /**
     * Returns a frame's duration in seconds for rate control.
     *
     * @param pts the frame's timestamp
     * @param duration the declared duration, or {@code 0}
     * @return the duration in seconds
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
     * Encodes the current source as one picture into the access unit assembler.
     *
     * <p>The access unit holds, in order: for an IDR picture of an Annex B stream the parameter
     * sets, for an IDR picture the SEI messages of the color description, for the first picture of
     * an intra refresh cycle the recovery point SEI message, and the slices, each preceded by a
     * prefix NAL unit with its temporal layer when the stream has several.
     *
     * @param reconstruction the frame receiving the reconstruction
     * @param idr whether the picture is an IDR picture
     * @param qp the frame quantizer
     * @return the access unit size in bits
     */
    private long encodePicture(Frame reconstruction, boolean idr, int qp) {
        nal.clear();
        if (idr && config.packetFormat() == H264PacketFormat.ANNEX_B) {
            nal.appendEscapedUnit(sps);
            nal.appendEscapedUnit(pps);
        }
        if (idr && staticSei != null) {
            nal.appendUnit(0, H264Nal.SEI, staticSei, staticSei.length);
        }
        var context = picture;
        context.refreshStart = 0;
        context.refreshEnd = 0;
        var bPicture = pictureType == LookaheadFrame.BIDIRECTIONAL || pictureType == LookaheadFrame.B_REFERENCED;
        if (refreshBand > 0 && !idr && !bPicture) {
            context.refreshStart = refreshColumn;
            context.refreshEnd = Math.min(settings.mbWidth(), refreshColumn + refreshBand);
            if (refreshColumn == 0) {
                nal.appendUnit(0, H264Nal.SEI, recoverySei, recoverySei.length);
            }
        }
        context.source = head.source;
        context.reconstruction = reconstruction;
        context.idr = idr;
        var unreferenced = pictureType == LookaheadFrame.BIDIRECTIONAL || (!bPicture && !idr && unreferencedP);
        context.info.bPicture = bPicture;
        context.pSlice = !idr && !bPicture;
        context.bSlice = bPicture;
        context.frameNum = idr ? 0 : frameNum;
        context.idrPicId = idrPicId;
        context.sliceQp = qp;
        context.pocLsb = (int) (head.poc & ((1 << settings.log2MaxPocLsb()) - 1));
        context.nalRefIdc = idr ? 3 : unreferenced ? 0 : 2;
        context.lowresIntra = head.intraCost;
        context.lowresInter = head.interCost;
        context.markCount = -1;
        context.reorderCount = 0;
        context.lightB = settings.analysis().lightB() && pictureType != LookaheadFrame.B_REFERENCED;
        if (bPicture) {
            context.refCount = 2;
            Frame past = null;
            Frame future = null;
            for (var i = -referenceCount; i < bRefs.size(); i++) {
                var candidate = i < 0 ? references[-i - 1] : bRefs.get(i);
                if (candidate.poc < head.poc) {
                    if (past == null || candidate.poc > past.poc) {
                        past = candidate;
                    }
                } else if (future == null || candidate.poc < future.poc) {
                    future = candidate;
                }
            }
            context.refs[0] = past;
            context.refs[1] = future;
            context.distPast = (int) (head.poc - past.poc);
            context.distFuture = (int) (future.poc - head.poc);
        } else {
            context.refCount = idr ? 0 : Math.min(referenceCount, settings.activeRefs());
            for (var i = 0; i < context.refCount; i++) {
                context.refs[i] = references[i];
            }
            if (!idr && settings.pyramid()) {
                planReferenceCommands(context);
            }
            if (!idr && temporalLayers > 1) {
                planLayerReference(context);
            }
            context.seed = null;
            if (!idr && context.refCount > 0 && settings.bframes() > 0) {
                var seed = seedVectors;
                Arrays.fill(seed, 0);
                trackSeed(seed);
                context.seed = seed;
            }
        }
        assignQuantizers(qp);
        Arrays.fill(context.forcePcm, false);
        sliceBase = nal.size();
        do {
            nal.truncate(sliceBase);
            codeSlice();
        } while (writer.overflowed());
        return nal.size() * 8L;
    }

    /**
     * Makes the reference of a P picture of a temporal layer the latest picture of a lower layer, or
     * for layer 0 the latest picture of layer 0, reordering the list when it is not the most recent
     * reference.
     *
     * @param context the context of the P picture
     */
    private void planLayerReference(PictureContext context) {
        Frame target = null;
        if (temporalLayer == 0) {
            target = layerLatest[0];
        } else {
            for (var layer = 0; layer < temporalLayer; layer++) {
                var candidate = layerLatest[layer];
                if (candidate != null && (target == null || isNewer(candidate, target))) {
                    target = candidate;
                }
            }
        }
        if (target == null || target == references[0]) {
            return;
        }
        context.refs[0] = target;
        context.refCount = 1;
        var current = context.frameNum;
        context.reorderIdc[0] = 0;
        context.reorderDifferences[0] = current - picNum(target, current) - 1;
        context.reorderCount = 1;
    }

    /**
     * Returns whether a reference picture was coded after another, by its position in
     * {@link #references}.
     *
     * @param a a reference picture
     * @param b another reference picture
     * @return whether {@code a} is more recent than {@code b}
     */
    private boolean isNewer(Frame a, Frame b) {
        for (var i = 0; i < referenceCount; i++) {
            if (references[i] == a) {
                return true;
            }
            if (references[i] == b) {
                return false;
            }
        }
        return false;
    }

    /**
     * Follows each macroblock of the frame being encoded back through the look-ahead frames
     * between it and its first reference, and stores the displacement as the motion seed.
     *
     * @param seed receives the packed displacement of each macroblock in quarter samples
     */
    private void trackSeed(int[] seed) {
        var mbWidth = settings.mbWidth();
        var mbHeight = settings.mbHeight();
        var chain = seedChain;
        chain.clear();
        chain.add(head);
        for (var index = head.displayIndex - 1; ; index--) {
            LookaheadFrame next = null;
            for (var candidate : plan) {
                if (candidate.displayIndex == index) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            chain.add(next);
        }
        for (var m = 0; m < seed.length; m++) {
            var x = (m % mbWidth) * 8 + 4;
            var y = (m / mbWidth) * 8 + 4;
            var startX = x;
            var startY = y;
            for (var frame : chain) {
                var mbX = Math.clamp(x >> 3, 0, mbWidth - 1);
                var mbY = Math.clamp(y >> 3, 0, mbHeight - 1);
                var v = frame.vectors[mbY * mbWidth + mbX];
                x += MotionPredictor.x(v);
                y += MotionPredictor.y(v);
            }
            seed[m] = MotionPredictor.pack(Math.clamp(8 * (x - startX), -32000, 32000),
                    Math.clamp(8 * (y - startY), -32000, 32000));
        }
    }

    /**
     * Returns the {@code PicNum} of a reference picture as seen from the current picture.
     *
     * @param frame the reference picture
     * @param frameNum the {@code frame_num} of the current picture
     * @return the picture number
     */
    private int picNum(Frame frame, int frameNum) {
        return frame.frameNum > frameNum ? frame.frameNum - (1 << settings.log2MaxFrameNum()) : frame.frameNum;
    }

    /**
     * Plans the reference list reordering and the reference marking of a P picture of a
     * pyramid: the list lists only the P chain although referenced B pictures sit in the decoded
     * picture buffer, and the marking drops those and the oldest P picture explicitly.
     *
     * @param context the context of the P picture
     */
    private void planReferenceCommands(PictureContext context) {
        var current = context.frameNum;
        var all = new ArrayList<Frame>(referenceCount + bRefs.size());
        for (var i = 0; i < referenceCount; i++) {
            all.add(references[i]);
        }
        all.addAll(bRefs);
        all.sort((a, b) -> picNum(b, current) - picNum(a, current));
        var same = true;
        for (var i = 0; i < context.refCount && same; i++) {
            same = all.get(i) == references[i];
        }
        if (!same) {
            var predicted = current;
            for (var i = 0; i < context.refCount; i++) {
                var number = picNum(references[i], current);
                var difference = predicted - number;
                context.reorderIdc[i] = difference > 0 ? 0 : 1;
                context.reorderDifferences[i] = Math.abs(difference) - 1;
                predicted = number;
            }
            context.reorderCount = context.refCount;
        }
        var marks = 0;
        for (var frame : bRefs) {
            context.markDifferences[marks++] = current - picNum(frame, current) - 1;
        }
        if (referenceCount == references.length) {
            context.markDifferences[marks++] = current - picNum(references[referenceCount - 1], current) - 1;
        }
        context.markCount = marks == 0 ? -1 : marks;
    }

    /**
     * Decides and entropy codes every macroblock of the current picture into the access unit, as
     * the slices the configuration fixes and, with a slice size limit, the further slices the
     * limit needs.
     *
     * <p>Without helper participants or with a slice size limit the calling thread decides and
     * codes each macroblock in turn; otherwise the picture is coded by
     * {@link #codeSliceParallel(int)}. With a size limit, a macroblock that makes the slice's NAL
     * unit too large is undone, the slice is closed before it, and the macroblock is decided again
     * as the first of a new slice.
     */
    private void codeSlice() {
        var context = picture;
        writer.startPicture();
        pictureBins = 0;
        pictureBytes = 0;
        sliceStart = 0;
        writer.start(context, 0);
        var limit = settings.maxSliceBytes();
        var helpers = limit > 0 ? 0 : helpers(workers);
        for (var i = 0; i <= helpers; i++) {
            analyzers[i].startPicture(context);
        }
        if (helpers > 0) {
            codeSliceParallel(helpers);
        } else {
            var analyzer = analyzers[0];
            var mbWidth = settings.mbWidth();
            var mbHeight = settings.mbHeight();
            var syncColumn = Math.min(1, mbWidth - 1);
            for (var mbY = 0; mbY < mbHeight; mbY++) {
                analyzer.startRow(rowStartsSlice(mbY) ? null : rowSync[mbY - 1]);
                for (var mbX = 0; mbX < mbWidth; mbX++) {
                    var address = mbY * mbWidth + mbX;
                    if (address != sliceStart && settings.sliceStart(address) == address) {
                        startSlice(address);
                    }
                    var mb = analyzer.analyze(mbX, mbY, context.forcePcm[address], sliceStart);
                    if (mbX == syncColumn) {
                        analyzer.saveRowStates(rowSync[mbY]);
                    }
                    if (address == sliceStart) {
                        writer.writeData(mb, address);
                        continue;
                    }
                    if (limit > 0) {
                        writer.save();
                    }
                    writer.endMacroblock(false);
                    writer.writeData(mb, address);
                    if (limit > 0 && writer.sizeBound() > limit) {
                        writer.restore();
                        startSlice(address);
                        analyzer.startSlice();
                        writer.writeData(analyzer.analyze(mbX, mbY, context.forcePcm[address], sliceStart), address);
                    }
                }
                ParallelBands.checkpoint();
            }
        }
        writer.endMacroblock(true);
        closeSlice(true);
    }

    /**
     * Returns whether a macroblock row starts one of the slices the configuration fixes.
     *
     * @param mbY the macroblock row
     * @return whether the row's first macroblock starts a slice
     */
    private boolean rowStartsSlice(int mbY) {
        var address = mbY * settings.mbWidth();
        return settings.sliceStart(address) == address;
    }

    /**
     * Closes the slice being written after its last macroblock and starts a new one.
     *
     * @param address the address of the new slice's first macroblock
     */
    private void startSlice(int address) {
        writer.endMacroblock(true);
        closeSlice(false);
        sliceStart = address;
        writer.start(picture, address);
    }

    /**
     * Writes one decided macroblock in raster order, closing the slice before it when it starts one
     * of the slices the configuration fixes.
     *
     * @param mb the decided macroblock
     * @param address its address
     */
    private void emit(Macroblock mb, int address) {
        if (address != sliceStart) {
            if (settings.sliceStart(address) == address) {
                startSlice(address);
            } else {
                writer.endMacroblock(false);
            }
        }
        writer.writeData(mb, address);
    }

    /**
     * Finishes the slice being written and appends it to the access unit, preceded by its prefix
     * NAL unit when the stream has temporal layers.
     *
     * <p>The last slice of a picture carries the {@code cabac_zero_word}s the picture needs.
     *
     * @param last whether the slice is the picture's last
     */
    private void closeSlice(boolean last) {
        var payload = writer.finish();
        var length = writer.payloadLength();
        pictureBins += writer.binCount();
        pictureBytes += length;
        if (last) {
            // TODO: with maxSliceBytes, the cabac_zero_words a picture needs can push its last slice past the
            //  limit; spread them over the picture's slices within the limit
            var zeroWords = cabacZeroWords(pictureBins, pictureBytes);
            if (zeroWords > 0) {
                payload = Arrays.copyOf(payload, length + 2 * zeroWords);
                length += 2 * zeroWords;
            }
        }
        var nalRefIdc = picture.nalRefIdc;
        if (temporalLayers > 1) {
            var prefix = prefixPayload;
            prefix[0] = (byte) (0x80 | (picture.idr ? 0x40 : 0));
            prefix[1] = (byte) 0x80;
            prefix[2] = (byte) ((temporalLayer << 5) | 0x07);
            prefix[3] = (byte) 0x20;
            nal.appendUnit(nalRefIdc, H264Nal.PREFIX, prefix, nalRefIdc != 0 ? 4 : 3);
        }
        nal.appendUnit(nalRefIdc, picture.idr ? H264Nal.IDR_SLICE : H264Nal.SLICE, payload, length);
    }

    /**
     * Decides the macroblock rows in a wavefront of helper participants while the calling thread
     * entropy codes them in raster order, deciding itself every row it reaches unclaimed.
     *
     * <p>A failure of any thread stops the others; the first failure is rethrown once every
     * participant has finished or been canceled.
     *
     * @param helpers the number of helper participants to start, at least 1
     * @throws IllegalStateException if a waiting thread is interrupted
     */
    private void codeSliceParallel(int helpers) {
        decided.reset();
        written.reset();
        nextRow.set(0);
        Throwable failure = null;
        try {
            startParticipants(helpers, this::decideRows, decided, written);
            writeRows(helpers);
        } catch (RuntimeException | Error exception) {
            decided.abort();
            written.abort();
            failure = exception;
        }
        rethrow(finishParticipants(helpers, failure));
    }

    /**
     * Decides rows for one helper participant: claims the next row until none is left, waiting
     * for the writer to free the row's ring buffer.
     *
     * @param index the participant index, from 1, which selects its analyzer
     */
    private void decideRows(int index) {
        var analyzer = analyzers[index];
        var mbHeight = settings.mbHeight();
        for (var mbY = nextRow.getAndIncrement(); mbY < mbHeight; mbY = nextRow.getAndIncrement()) {
            if (mbY >= ring.length) {
                written.await(0, mbY - ring.length + 1);
            }
            decideRow(analyzer, mbY, ring[mbY % ring.length]);
        }
    }

    /**
     * Decides one macroblock row, two macroblocks behind the row above, publishing each
     * macroblock's decision in {@link #decided}.
     *
     * <p>A helper participant copies each decision into the row's ring buffer for the writer;
     * the calling thread, which writes the rows it decides, codes each macroblock at once
     * instead.
     *
     * @param analyzer the analyzer of the deciding participant
     * @param mbY the macroblock row, claimed by the calling thread
     * @param records the ring buffer of the row, or {@code null} to code the macroblocks
     */
    private void decideRow(MacroblockAnalyzer analyzer, int mbY, Macroblock[] records) {
        var context = picture;
        var mbWidth = settings.mbWidth();
        var syncColumn = Math.min(1, mbWidth - 1);
        var above = 0;
        if (mbY > 0) {
            above = decided.awaitProgress(mbY - 1, syncColumn + 1);
        }
        analyzer.startRow(rowStartsSlice(mbY) ? null : rowSync[mbY - 1]);
        for (var mbX = 0; mbX < mbWidth; mbX++) {
            var needed = Math.min(mbX + 2, mbWidth);
            if (mbY > 0 && above < needed) {
                above = decided.awaitProgress(mbY - 1, needed);
            }
            var address = mbY * mbWidth + mbX;
            var mb = analyzer.analyze(mbX, mbY, context.forcePcm[address], settings.sliceStart(address));
            if (mbX == syncColumn) {
                analyzer.saveRowStates(rowSync[mbY]);
            }
            if (records == null) {
                decided.publish(mbY, mbX + 1);
                emit(mb, address);
            } else {
                records[mbX].copyDecisionFrom(mb);
                decided.publish(mbY, mbX + 1);
            }
        }
    }

    /**
     * Entropy codes the decided macroblocks in raster order as they become available, deciding
     * a row first whenever no participant has claimed it yet.
     *
     * <p>Rows are claimed in order, so a row the writer reaches unclaimed is the next one to
     * claim and every later row is unclaimed too: deciding it here never waits for a
     * participant that has not started, which lets the picture complete on the calling thread
     * alone. While fewer participants run than there are workers and the writer has caught up
     * with the decisions, it also decides the next unclaimed row into the ring before coding a
     * row (see {@link #fillIn}), so at most {@link #workers} threads decide rows at once.
     *
     * @param helpers the number of helper participants started
     */
    private void writeRows(int helpers) {
        var mbWidth = settings.mbWidth();
        var mbHeight = settings.mbHeight();
        var running = 0;
        for (var mbY = 0; mbY < mbHeight; mbY++) {
            if (nextRow.get() == mbY && nextRow.compareAndSet(mbY, mbY + 1)) {
                decideRow(analyzers[0], mbY, null);
            } else {
                if (running < helpers) {
                    running = runningParticipants(helpers);
                }
                if (running < workers && decided.progress(mbY) < mbWidth) {
                    fillIn(mbY);
                }
                var records = ring[mbY % ring.length];
                var ready = 0;
                for (var mbX = 0; mbX < mbWidth; mbX++) {
                    if (ready <= mbX) {
                        ready = decided.awaitProgress(mbY, mbX + 1);
                    }
                    emit(records[mbX], mbY * mbWidth + mbX);
                }
            }
            written.publish(0, mbY + 1);
            ParallelBands.checkpoint();
        }
    }

    /**
     * Decides the next unclaimed row into its ring buffer on the calling thread, when its ring
     * buffer is free, before the writer codes a row another participant claimed.
     *
     * <p>With fewer running participants than workers, a writer that has caught up with the
     * decisions would wait for the rows it codes; deciding a row meanwhile lets the calling
     * thread stand in for a missing participant. The row waits only for rows claimed before it,
     * whose participants are running, and the writer codes the rows it postponed in a burst
     * afterwards.
     *
     * @implNote This implementation leaves the decisions to the participants while the writer
     * lags behind them: with many participants the writer is the slowest stage of the
     * wavefront, and every row it decided would delay the whole picture. The ring holds twice
     * the workers plus two rows, so the rows the running participants claim while the writer
     * decides one still find a free ring buffer instead of waiting for the postponed rows to be
     * coded.
     * @param mbY the row the writer codes next, claimed by another participant
     */
    private void fillIn(int mbY) {
        var row = nextRow.get();
        if (row < settings.mbHeight() && row - ring.length < mbY && nextRow.compareAndSet(row, row + 1)) {
            decideRow(analyzers[0], row, ring[row % ring.length]);
        }
    }

    /**
     * Returns how many of the started helper participants a helper has claimed.
     *
     * @param helpers the number of helper participants started
     * @return the number of claimed participants
     */
    private int runningParticipants(int helpers) {
        var running = 0;
        for (var i = 0; i < helpers; i++) {
            if (participants[i].started()) {
                running++;
            }
        }
        return running;
    }

    /**
     * Returns the number of helper participants to start for a wavefront: none when the encoder
     * runs on the calling thread only, otherwise at most {@code limit} and at most the
     * {@link ParallelBands#available()} helpers, so the concurrent operations of the module keep
     * their processors.
     *
     * @param limit the most helper participants the wavefront can use
     * @return the number of helper participants, from zero to {@code limit}
     */
    private int helpers(int limit) {
        if (!parallel || limit <= 0) {
            return 0;
        }
        return Math.min(limit, forcedHelpers >= 0 ? forcedHelpers : ParallelBands.available());
    }

    /**
     * Fixes the number of helper participants every wavefront starts, whatever the load of the
     * module's pool; a test hook for wavefronts whose participants no helper runs.
     *
     * <p>The number is capped by the workers the configuration allows, and has no effect when the
     * encoder runs on the calling thread only.
     *
     * @param count the number of helper participants, or {@code -1} to ask the pool on every
     * wavefront
     */
    public void setForcedHelpers(int count) {
        forcedHelpers = count;
    }

    /**
     * Runs a wavefront task on the calling thread, as participant {@code 0}, and on helper
     * participants, and waits until every participant has finished.
     *
     * <p>A failing participant aborts the wavefront, so the others stop waiting; the first
     * failure is rethrown on the calling thread once every participant has finished or been
     * canceled, so no participant is still running when this method returns, normally or not.
     *
     * @param helpers the number of helper participants to start
     * @param task the task, receiving the participant index
     * @param wavefront the wavefront the participants synchronize on
     * @throws IllegalStateException if a waiting thread is interrupted
     */
    private void parallel(int helpers, IntConsumer task, Wavefront wavefront) {
        if (helpers == 0) {
            task.accept(0);
            return;
        }
        Throwable failure = null;
        try {
            startParticipants(helpers, task, wavefront, null);
            task.accept(0);
        } catch (RuntimeException | Error exception) {
            wavefront.abort();
            failure = exception;
        }
        rethrow(finishParticipants(helpers, failure));
    }

    /**
     * Starts helper participants, numbered from 1, as computations of the module's shared pool,
     * recording them in {@link #participants}.
     *
     * @param helpers the number of participants to start
     * @param task the task, receiving the participant index
     * @param first the wavefront a failing participant aborts
     * @param second another wavefront it aborts, or {@code null}
     */
    private void startParticipants(int helpers, IntConsumer task, Wavefront first, Wavefront second) {
        for (var i = 0; i < helpers; i++) {
            var index = i + 1;
            participants[i] = ParallelBands.start(() -> {
                try {
                    task.accept(index);
                } catch (RuntimeException | Error exception) {
                    first.abort();
                    if (second != null) {
                        second.abort();
                    }
                    throw exception;
                }
            });
        }
    }

    /**
     * Cancels the started participants no helper has claimed, then waits for the others, and
     * returns the failure to report.
     *
     * <p>A cancellation caused by another participant's failure is only returned when nothing
     * else failed.
     *
     * @param helpers the number of participants that may have been started
     * @param failure the failure of the calling thread's own part, or {@code null}
     * @return the failure to report, or {@code null}
     */
    private Throwable finishParticipants(int helpers, Throwable failure) {
        for (var i = 0; i < helpers; i++) {
            var participant = participants[i];
            if (participant != null && participant.tryCancel()) {
                participants[i] = null;
            }
        }
        for (var i = 0; i < helpers; i++) {
            var participant = participants[i];
            if (participant == null) {
                continue;
            }
            participants[i] = null;
            try {
                participant.join();
            } catch (RuntimeException | Error exception) {
                if (failure == null || failure instanceof CancellationException
                        && !(exception instanceof CancellationException)) {
                    failure = exception;
                }
            }
        }
        return failure;
    }

    /**
     * Rethrows a failure unchanged when unchecked, wrapped otherwise.
     *
     * @param failure the failure, or {@code null} for none
     */
    private static void rethrow(Throwable failure) {
        switch (failure) {
            case null -> {
            }
            case RuntimeException runtime -> throw runtime;
            case Error error -> throw error;
            default -> throw new IllegalStateException(failure);
        }
    }

    /**
     * Returns the number of {@code cabac_zero_word}s the picture needs to satisfy the bin count
     * constraint of clause 7.4.2.10.
     *
     * @param bins the number of CABAC bins of the picture
     * @param bytes the number of slice data bytes of the picture
     * @return the number of zero words to append
     */
    private int cabacZeroWords(long bins, long bytes) {
        if (!settings.cabac()) {
            return 0;
        }
        var rawBits = 3072L * settings.mbCount();
        var allowed = 32 * bytes / 3 + rawBits / 32;
        if (bins <= allowed) {
            return 0;
        }
        var neededBytes = ((bins - rawBits / 32) * 3 + 31) / 32 - bytes;
        return (int) Math.max(0, (neededBytes + 2) / 3);
    }

    /**
     * Fills the per-macroblock quantizers from the frame quantizer and the adaptive offsets.
     *
     * <p>A macroblock's offset is {@link #AQ_SCALE} times the strength times the binary logarithm
     * of its AC energy (from {@link FrameAnalyzer#activity}) relative to the geometric mean energy
     * of the picture, rounded and clamped to {@link #AQ_MAX_OFFSET} steps either way. Flat areas,
     * where coding noise is most visible, get finer quantizers and textured areas coarser ones,
     * while the average quantizer stays close to the frame quantizer that rate control chose.
     * The result never goes below 1 unless the frame quantizer is already at or below
     * {@link RateController#QP_MIN}, and always stays inside the configured quantizer window.
     *
     * @param qp the frame quantizer
     */
    private void assignQuantizers(int qp) {
        var count = settings.mbCount();
        var mbQp = picture.mbQp;
        var treeMean = treeActive ? treeAverage : 0.0;
        if (aqStrength <= 0 || count < 4) {
            if (!treeActive) {
                Arrays.fill(mbQp, (byte) Math.clamp(qp, config.minQuantizer(), config.maxQuantizer()));
                return;
            }
            for (var i = 0; i < count; i++) {
                var offset = (int) Math.round(treeOffsets[i] - treeMean);
                mbQp[i] = (byte) Math.clamp(qp + offset, config.minQuantizer(), config.maxQuantizer());
            }
            return;
        }
        var activity = head.activity;
        var offsets = aqOffsets;
        double logSum = 0;
        for (var i = 0; i < count; i++) {
            offsets[i] = StrictMath.log(activity[i]) * INVERSE_LOG2;
            logSum += offsets[i];
        }
        var mean = logSum / count;
        var scale = aqStrength * AQ_SCALE;
        var min = Math.max(qp <= RateController.QP_MIN ? 0 : 1, config.minQuantizer());
        var max = Math.min(RateController.QP_MAX, config.maxQuantizer());
        for (var i = 0; i < count; i++) {
            var aq = Math.clamp(scale * (offsets[i] - mean), -AQ_MAX_OFFSET, AQ_MAX_OFFSET);
            var offset = (int) Math.round(treeActive ? aq + treeOffsets[i] - treeMean : aq);
            mbQp[i] = (byte) Math.clamp(qp + offset, min, max);
        }
    }

    /**
     * Makes a reconstruction the most recent reference picture. When the encoder runs on the
     * calling thread only it is deblocked, padded and interpolated here; otherwise that work is
     * left pending until the next frame is prepared or the reconstruction is requested.
     *
     * @param reconstruction the reconstructed picture that becomes a reference
     * @param idr whether it is an IDR picture
     */
    private void finishReference(Frame reconstruction, boolean idr) {
        if (!parallel) {
            var stageStart = System.nanoTime();
            runPreparation(reconstruction, false);
            stageNanos[3] += System.nanoTime() - stageStart;
        } else {
            pending = reconstruction;
        }
        storeMotion(reconstruction);
        reconstruction.frameNum = picture.frameNum;
        reconstruction.poc = head.poc;
        if (pictureType == LookaheadFrame.B_REFERENCED) {
            bRefs.add(reconstruction);
            frameNum = (frameNum + 1) & ((1 << settings.log2MaxFrameNum()) - 1);
            lastReconstruction = reconstruction;
            return;
        }
        pool.addAll(bRefs);
        bRefs.clear();
        if (idr) {
            for (var i = 0; i < referenceCount; i++) {
                pool.add(references[i]);
                references[i] = null;
            }
            referenceCount = 0;
            Arrays.fill(layerLatest, null);
            framesSinceIdr = 0;
            idrPicId = (idrPicId + 1) & 0xFFFF;
            frameNum = 1;
        } else {
            frameNum = (frameNum + 1) & ((1 << settings.log2MaxFrameNum()) - 1);
        }
        framesSinceIdr++;
        if (referenceCount == references.length) {
            pool.add(references[referenceCount - 1]);
            referenceCount--;
        }
        System.arraycopy(references, 0, references, 1, referenceCount);
        references[0] = reconstruction;
        referenceCount++;
        lastReconstruction = reconstruction;
        if (temporalLayers > 1) {
            layerLatest[temporalLayer] = reconstruction;
        }
    }

    /**
     * Stores the average motion of each macroblock in a reconstruction, as candidates for the
     * next picture's motion search.
     *
     * @param reconstruction the reconstructed picture whose motion is kept for direct prediction
     */
    private void storeMotion(Frame reconstruction) {
        var info = picture.info;
        var motion = reconstruction.motion;
        reconstruction.motionDistance = picture.idr || picture.refCount == 0 ? 1
                : Math.max(1, (int) (head.poc - picture.refs[0].poc));
        for (var address = 0; address < motion.length; address++) {
            if (MbType.isIntra(info.type[address])) {
                motion[address] = 0;
                Arrays.fill(reconstruction.colRef, address * 4, address * 4 + 4, (byte) -1);
                continue;
            }
            for (var q = 0; q < 4; q++) {
                var corner = address * 16 + (q >> 1) * 12 + (q & 1) * 3;
                var list1 = info.bPicture && info.refs[address * 4 + q] < 0;
                reconstruction.colRef[address * 4 + q] = list1 ? info.refs1[address * 4 + q] : info.refs[address * 4 + q];
                reconstruction.colMvX[address * 4 + q] = list1 ? info.mvX1[corner] : info.mvX[corner];
                reconstruction.colMvY[address * 4 + q] = list1 ? info.mvY1[corner] : info.mvY[corner];
            }
            var base = address * 16;
            var sumX = 0;
            var sumY = 0;
            for (var i = 0; i < 16; i++) {
                var list1 = info.bPicture && info.refs[address * 4 + ((i >> 3) << 1) + ((i & 3) >> 1)] < 0;
                sumX += list1 ? info.mvX1[base + i] : info.mvX[base + i];
                sumY += list1 ? info.mvY1[base + i] : info.mvY[base + i];
            }
            motion[address] = MotionPredictor.pack((sumX + 8) >> 4, (sumY + 8) >> 4);
        }
    }

    /**
     * Closes the encoder, which rejects every later frame.
     *
     * <p>The encoder owns no threads and no participant of its wavefronts outlives the call that
     * started it, so there is nothing else to release.
     */
    public void close() {
        closed = true;
    }
}
