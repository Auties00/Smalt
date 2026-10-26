package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.Av1PicturePool;
import com.github.auties00.smalt.video.av1.internal.BitReader;
import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.CdefDsp;
import com.github.auties00.smalt.video.av1.internal.DequantTables;
import com.github.auties00.smalt.video.av1.internal.EncoderBridge;
import com.github.auties00.smalt.video.av1.internal.FrameContext;
import com.github.auties00.smalt.video.av1.internal.FilmGrainData;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.LoopFilterDsp;
import com.github.auties00.smalt.video.av1.internal.LoopRestorationDsp;
import com.github.auties00.smalt.video.av1.internal.Obu;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import com.github.auties00.smalt.video.av1.internal.TaskContext;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Encodes still pictures as AV1 key frames for quality rather than speed: every frame is an
 * intra-only frame coded at a constant quantizer with a rate-distortion search of its partitions,
 * modes and transforms, followed by the search of its loop filter levels and CDEF strengths.
 *
 * <p>The encoder handles every AV1 picture format: 8, 10 and 12 bits per sample, 4:2:0, 4:2:2,
 * 4:4:4 and monochrome, in the profile each requires, and lossless coding at quantizer index 0.
 * Each call to {@link #encode} codes one picture into a sequence header OBU and a frame OBU
 * without a temporal delimiter, the layout of an AV1 image item and of the first sample of an AV1
 * track.
 *
 * <p>The tiles of a frame are encoded in parallel on the module's pool when the settings allow
 * more than one thread; the output does not depend on the thread count. An instance is used by
 * one thread at a time.
 *
 * <p>The block reconstruction is the decoder's own, driven through
 * {@link com.github.auties00.smalt.video.av1.internal.CoefficientSource CoefficientSource}, so the encoder's
 * picture is exactly what any decoder reconstructs.
 *
 * @implNote This implementation follows the structure of libaom's all-intra encoder
 * ({@code av1/encoder/encoder.c}, {@code av1/encoder/encodeframe.c}): the frame is searched and
 * reconstructed superblock by superblock, the deblocking levels picked ({@code av1/encoder/picklpf.c})
 * and the CDEF strengths searched ({@code av1/encoder/pickcdef.c}) on the reconstruction, and only then
 * is the bitstream packed, as {@code av1_pack_bitstream} packs its token buffers.
 */
public final class StillFrameEncoder implements Av1Engine, FilterTarget {
    /**
     * The picture width.
     */
    final int width;

    /**
     * The picture height.
     */
    final int height;

    /**
     * The pixel layout, one of the {@link SequenceHeader} layouts.
     */
    final int layout;

    /**
     * The bit depth: 8, 10 or 12.
     */
    final int bitDepth;

    /**
     * Whether chroma is subsampled horizontally, as 0 or 1.
     */
    final int ssHor;

    /**
     * Whether chroma is subsampled vertically, as 0 or 1.
     */
    final int ssVer;

    /**
     * The largest sample value.
     */
    final int bdMax;

    /**
     * The shift that brings squared errors at the bit depth to the 8-bit scale.
     */
    final int distShift;

    /**
     * The search settings.
     */
    final StillSettings settings;

    /**
     * The smallest block size the partition search reaches, 4 or 8 samples.
     */
    final int minBlock;

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
     * The decoder-side state of the frame being encoded.
     */
    final FrameContext f = new FrameContext();

    /**
     * The pool of reconstruction pictures.
     */
    private final Av1PicturePool pool;

    /**
     * The source picture with edges replicated to whole superblocks.
     */
    final Av1Picture source;

    /**
     * The header of the frame being encoded.
     */
    FrameHeader hdr;

    /**
     * The quantizers of the frame being encoded.
     */
    StillQuant quant;

    /**
     * The Lagrangian multiplier of the frame being encoded.
     */
    int rdmult;

    /**
     * The number of 64x64 superblocks per row.
     */
    final int sbCols;

    /**
     * The number of 64x64 superblock rows.
     */
    final int sbRows;

    /**
     * The recorded symbols of each superblock, in raster order.
     */
    private final SymbolLog[] logs;

    /**
     * The CDEF index of each superblock, in raster order.
     */
    private final int[] sbCdef;

    /**
     * The tile encoders, one per tile coded at once.
     */
    private StillTileEncoder[] tileEncoders = new StillTileEncoder[0];

    /**
     * The reconstruction of the last coded frame, or {@code null}.
     */
    private Av1Picture recon;

    /**
     * The header writer.
     */
    private final HeaderWriter headerWriter = new HeaderWriter();

    /**
     * The order hint of the key frame being coded by {@link #encodeKeyFrame}.
     */
    private int orderHint;

    /**
     * Creates an encoder for a picture size and format.
     *
     * @param width the picture width
     * @param height the picture height
     * @param layout the pixel layout, one of the {@link SequenceHeader} layouts
     * @param bitDepth the bit depth: 8, 10 or 12
     * @param color the color description signaled in the sequence header
     * @param settings the quantizer and search settings
     * @param limits the allocation limits
     * @throws IllegalArgumentException if a dimension is out of range or the bit depth is not 8,
     * 10 or 12
     */
    public StillFrameEncoder(int width, int height, int layout, int bitDepth, ColorInfo color, StillSettings settings,
                             MediaLimits limits) {
        this(width, height, layout, bitDepth, sequenceHeader(width, height, layout, bitDepth, color, settings),
                settings, limits);
    }

    /**
     * Creates an encoder of the key frames of a video stream whose sequence header another encoder
     * built: pictures of the header's chroma layout and bit depth whose frame headers carry an order
     * hint when the sequence enables them, coded with {@link #encodeKeyFrame}.
     *
     * @param width the picture width
     * @param height the picture height
     * @param seq the stream's sequence header
     * @param settings the quantizer and search settings
     * @param limits the allocation limits
     * @throws IllegalArgumentException if a dimension is out of range
     */
    public StillFrameEncoder(int width, int height, SequenceHeader seq, StillSettings settings, MediaLimits limits) {
        this(width, height, seq.layout, 8 + 2 * seq.hbd, seq, settings, limits);
    }

    /**
     * Creates an encoder with a sequence header.
     *
     * @param width the picture width
     * @param height the picture height
     * @param layout the pixel layout
     * @param bitDepth the bit depth
     * @param seq the sequence header
     * @param settings the quantizer and search settings
     * @param limits the allocation limits
     * @throws IllegalArgumentException if a dimension is out of range or the bit depth is not 8,
     * 10 or 12
     */
    private StillFrameEncoder(int width, int height, int layout, int bitDepth, SequenceHeader seq,
                              StillSettings settings, MediaLimits limits) {
        if (width < 1 || height < 1 || width > 65536 || height > 65536) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("unsupported bit depth " + bitDepth);
        }
        this.width = width;
        this.height = height;
        this.layout = layout;
        this.bitDepth = bitDepth;
        this.ssHor = layout == SequenceHeader.LAYOUT_I420 || layout == SequenceHeader.LAYOUT_I422 ? 1 : 0;
        this.ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        this.bdMax = (1 << bitDepth) - 1;
        this.distShift = 2 * (bitDepth - 8);
        this.settings = settings;
        this.minBlock = settings.minBlock(width, height);
        this.limits = limits;
        this.pool = new Av1PicturePool(limits);
        this.seq = seq;
        this.seqObu = StillHeaderWriter.sequenceHeader(seq);
        this.source = pool.obtain(width, height, layout, bitDepth);
        this.sbCols = (width + 63) >> 6;
        this.sbRows = (height + 63) >> 6;
        this.logs = new SymbolLog[sbCols * sbRows];
        this.sbCdef = new int[sbCols * sbRows];
    }

    /**
     * Builds the sequence header of a still picture.
     *
     * @param width the picture width
     * @param height the picture height
     * @param layout the pixel layout
     * @param bitDepth the bit depth
     * @param color the color description
     * @param settings the quantizer and search settings
     * @return the sequence header
     */
    private static SequenceHeader sequenceHeader(int width, int height, int layout, int bitDepth, ColorInfo color,
                                                 StillSettings settings) {
        var seq = new SequenceHeader();
        var mono = layout == SequenceHeader.LAYOUT_I400;
        seq.profile = bitDepth == 12 || layout == SequenceHeader.LAYOUT_I422 ? 2
                : layout == SequenceHeader.LAYOUT_I444 ? 1 : 0;
        seq.stillPicture = settings.reducedHeader;
        seq.reducedStillPictureHeader = settings.reducedHeader;
        seq.numOperatingPoints = 1;
        var level = ObuWriter.level(width, height);
        seq.majorLevel[0] = 2 + (level >> 2);
        seq.minorLevel[0] = level & 3;
        seq.tier[0] = 0;
        seq.widthNBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(width - 1));
        seq.heightNBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(height - 1));
        seq.maxWidth = width;
        seq.maxHeight = height;
        seq.sb128 = false;
        seq.filterIntra = true;
        seq.intraEdgeFilter = true;
        seq.screenContentTools = SequenceHeader.ADAPTIVE;
        seq.forceIntegerMv = SequenceHeader.ADAPTIVE;
        seq.superRes = false;
        seq.cdef = true;
        seq.restoration = settings.restoration && settings.qindex != 0;
        seq.hbd = bitDepth == 8 ? 0 : bitDepth == 10 ? 1 : 2;
        seq.monochrome = mono;
        seq.layout = layout;
        seq.colorDescriptionPresent = true;
        seq.pri = color.primaries().code();
        seq.trc = color.transfer().code();
        seq.mtrx = color.matrix().code();
        if (seq.mtrx == 0 && layout != SequenceHeader.LAYOUT_I444) {
            // the identity matrix needs 4:4:4; the stream signals an unspecified matrix instead
            seq.mtrx = 2;
        }
        // color_config codes the sRGB description without a range, which it implies is full
        var srgb = seq.pri == 1 && seq.trc == 13 && seq.mtrx == 0;
        seq.colorRange = color.range() == ColorRange.FULL || srgb;
        seq.ssHor = mono ? 1 : layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
        seq.ssVer = mono ? 1 : layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
        seq.chr = layout == SequenceHeader.LAYOUT_I420 ? chromaPosition(color.chromaSiting()) : 0;
        seq.separateUvDeltaQ = false;
        seq.filmGrainPresent = settings.filmGrain && !settings.reducedHeader;
        return seq;
    }

    /**
     * Returns the AV1 chroma sample position of a chroma siting.
     *
     * @param siting the chroma siting
     * @return the {@code chroma_sample_position}: 0 unknown, 1 vertical, 2 colocated
     */
    private static int chromaPosition(ChromaSiting siting) {
        return switch (siting) {
            case LEFT -> 1;
            case TOP_LEFT -> 2;
            default -> 0;
        };
    }

    /**
     * Returns the sequence header model.
     *
     * @return the sequence header
     */
    public SequenceHeader sequenceHeader() {
        return seq;
    }

    /**
     * Returns the sequence header OBU, with its OBU header and size field.
     *
     * @return the OBU
     */
    public byte[] sequenceHeaderObu() {
        var out = new ByteArrayOutputStream(seqObu.length + 4);
        ObuWriter.writeObu(out, Obu.OBU_SEQ_HDR, seqObu, 0, seqObu.length);
        return out.toByteArray();
    }

    @Override
    public byte[] codecConfigurationRecord() {
        return ObuWriter.av1Config(seq, seqObu);
    }

    /**
     * Returns the reconstruction of the last coded frame, with its in-loop filters applied.
     *
     * @return the picture, valid until the next {@link #encode}; {@code null} before the first
     */
    public Av1Picture reconstruction() {
        return recon;
    }

    /**
     * Returns the symbol log of a superblock, created on first use.
     *
     * @param sbIndex the superblock index in raster order
     * @return the cleared log
     */
    SymbolLog log(int sbIndex) {
        var log = logs[sbIndex];
        if (log == null) {
            log = new SymbolLog();
            logs[sbIndex] = log;
        } else {
            log.clear();
        }
        return log;
    }

    /**
     * Encodes one picture given as sample planes at the encoder's bit depth and returns its
     * sequence header OBU and frame OBU.
     *
     * @param planes the luma, U and V planes; the chroma planes are ignored for monochrome
     * @param strides the row stride of each plane
     * @return the OBUs
     */
    public byte[] encode(short[][] planes, int[] strides) {
        loadSource(planes, strides);
        return encodeLoaded(true);
    }

    /**
     * Encodes one 8-bit picture given as byte planes and returns its sequence header OBU and frame
     * OBU.
     *
     * @param planes the luma, U and V planes; the chroma planes are ignored for monochrome
     * @param strides the row stride of each plane
     * @return the OBUs
     */
    public byte[] encode(byte[][] planes, int[] strides) {
        loadSource(planes, strides);
        return encodeLoaded(true);
    }

    /**
     * Encodes one 8-bit picture given as byte planes as a key frame of a video stream and returns
     * its frame OBU alone; the stream's sequence header comes from the encoder that built it.
     *
     * @param planes the luma, U and V planes; the chroma planes are ignored for monochrome
     * @param strides the row stride of each plane
     * @param orderHint the frame's order hint, reduced modulo the order hint range
     * @return the frame OBU
     */
    public byte[] encodeKeyFrame(byte[][] planes, int[] strides, int orderHint) {
        loadSource(planes, strides);
        this.orderHint = orderHint;
        try {
            return encodeLoaded(false);
        } finally {
            this.orderHint = 0;
        }
    }

    /**
     * Encodes one picture given as sample planes at the encoder's bit depth as a key frame of a
     * video stream and returns its frame OBU alone; the stream's sequence header comes from the
     * encoder that built it.
     *
     * @param planes the luma, U and V planes; the chroma planes are ignored for monochrome
     * @param strides the row stride of each plane
     * @param orderHint the frame's order hint, reduced modulo the order hint range
     * @return the frame OBU
     */
    public byte[] encodeKeyFrame(short[][] planes, int[] strides, int orderHint) {
        loadSource(planes, strides);
        this.orderHint = orderHint;
        try {
            return encodeLoaded(false);
        } finally {
            this.orderHint = 0;
        }
    }

    /**
     * Copies sample planes into the source picture and replicates the right and bottom edges to
     * whole superblocks.
     *
     * @param planes the luma, U and V planes
     * @param strides the row stride of each plane
     */
    private void loadSource(short[][] planes, int[] strides) {
        var count = layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        for (var pl = 0; pl < count; pl++) {
            var w = pl == 0 ? width : (width + ssHor) >> ssHor;
            var h = pl == 0 ? height : (height + ssVer) >> ssVer;
            var dst = source.data[pl];
            var stride = source.stride[pl != 0 ? 1 : 0];
            Planes.copy(planes[pl], 0, strides[pl], dst, 0, stride, w, h);
            extend(pl, w, h);
        }
    }

    /**
     * Copies 8-bit sample planes into the source picture and replicates the right and bottom edges
     * to whole superblocks.
     *
     * @param planes the luma, U and V planes
     * @param strides the row stride of each plane
     */
    private void loadSource(byte[][] planes, int[] strides) {
        var count = layout == SequenceHeader.LAYOUT_I400 ? 1 : 3;
        for (var pl = 0; pl < count; pl++) {
            var w = pl == 0 ? width : (width + ssHor) >> ssHor;
            var h = pl == 0 ? height : (height + ssVer) >> ssVer;
            var dst = source.data[pl];
            var stride = source.stride[pl != 0 ? 1 : 0];
            var src = planes[pl];
            var srcStride = strides[pl];
            for (var y = 0; y < h; y++) {
                var s = y * srcStride;
                var d = y * stride;
                for (var x = 0; x < w; x++) {
                    dst[d + x] = (short) (src[s + x] & 0xFF);
                }
            }
            extend(pl, w, h);
        }
    }

    /**
     * Replicates the last column and row of a source plane to whole superblocks.
     *
     * @param pl the plane
     * @param w the plane width
     * @param h the plane height
     */
    private void extend(int pl, int w, int h) {
        var ss = pl == 0 ? 0 : ssHor;
        var sv = pl == 0 ? 0 : ssVer;
        var pw = ((width + 63) & ~63) >> ss;
        var ph = ((height + 63) & ~63) >> sv;
        var dst = source.data[pl];
        var stride = source.stride[pl != 0 ? 1 : 0];
        Planes.extendSides(dst, 0, stride, w, h, 0, pw - w);
        Planes.extendBottom(dst, (h - 1) * stride, stride, pw, ph - h);
    }

    /**
     * Encodes the loaded source.
     *
     * @param withSequenceHeader whether the output starts with the sequence header OBU
     * @return the sequence header OBU, when requested, and the frame OBU
     */
    private byte[] encodeLoaded(boolean withSequenceHeader) {
        try (var _ = ParallelBands.operation()) {
            hdr = buildHeader();
            setupFrame();
            encodeTiles();
            pickFilters();
            return assemble(withSequenceHeader);
        } finally {
            if (f.cur != null && recon != f.cur) {
                f.cur.release();
            }
        }
    }

    /**
     * Returns the number of threads the frame may use.
     *
     * @return the thread count
     */
    private int threads() {
        return settings.threads > 0 ? Math.min(settings.threads, ParallelBands.THREADS) : ParallelBands.THREADS;
    }

    /**
     * Builds, writes and parses back the frame header with provisional filter parameters, so every
     * derived field matches what a decoder derives.
     *
     * @return the parsed frame header
     */
    private FrameHeader buildHeader() {
        var h = new FrameHeader();
        h.frameType = FrameHeader.KEY;
        h.showFrame = true;
        h.errorResilientMode = true;
        // A key frame of a stream whose sequence header fixes the screen content tools (the
        // good-quality encoder's) must follow it: the frame header then carries no flag of its own.
        h.allowScreenContentTools = seq.screenContentTools == SequenceHeader.ADAPTIVE
                ? settings.screenContent || (settings.palette && settings.detectScreenContent && screenContent())
                : seq.screenContentTools != 0;
        h.refreshFrameFlags = 0xFF;
        if (seq.orderHint) {
            h.frameOffset = orderHint & ((1 << seq.orderHintNBits) - 1);
        }
        ObuWriter.uniformTiling(h, sbCols, sbRows, settings.tileColsLog2, settings.tileRowsLog2);
        h.quantYac = settings.qindex;
        h.quantUdcDelta = settings.chromaDelta;
        h.quantUacDelta = settings.chromaDelta;
        h.quantVdcDelta = settings.chromaDelta;
        h.quantVacDelta = settings.chromaDelta;
        var lf = lfModel();
        h.loopfilterLevelY[0] = lf;
        h.loopfilterLevelY[1] = lf;
        h.loopfilterLevelU = lf;
        h.loopfilterLevelV = lf;
        h.cdefDamping = 3 + (settings.qindex >> 6);
        h.cdefNBits = 0;
        h.txfmMode = FrameHeader.TX_SWITCHABLE;
        if (seq.filmGrainPresent && grain != null) {
            h.filmGrainPresent = true;
            h.filmGrain.copyFrom(grain);
        }
        if (timing != null) {
            timing.fill(h, seq, timingPts);
        }
        return parseBack(h);
    }

    /**
     * The film grain the next key frame signals, with its seed, or {@code null} for none.
     */
    private FilmGrainData grain;

    /**
     * Sets the film grain the next key frames signal when the sequence signals film grain.
     *
     * @param grain the grain in the decoder's model, with the frame's seed, or {@code null} for none
     */
    public void filmGrain(FilmGrainData grain) {
        this.grain = grain;
    }

    /**
     * The decoder model times the next key frames' headers take, or {@code null} for none.
     */
    private FrameTiming timing;

    /**
     * The timestamp of the next key frame, for its decoder model times.
     */
    private long timingPts;

    /**
     * Makes the next key frames' headers carry decoder model times when the sequence signals the
     * decoder model; the caller advances the times once the frame is kept.
     *
     * @param timing the times of the stream
     * @param pts the timestamp of the next key frame, in time base units
     */
    void timing(FrameTiming timing, long pts) {
        this.timing = timing;
        this.timingPts = pts;
    }

    /**
     * Writes a frame header and parses it back.
     *
     * @param h the header to write
     * @return the parsed header
     */
    private FrameHeader parseBack(FrameHeader h) {
        StillHeaderWriter.frameHeader(headerWriter, seq, h);
        var bytes = Arrays.copyOf(headerWriter.buffer(), headerWriter.size());
        var gb = new BitReader();
        gb.init(bytes, 0, bytes.length);
        var parsed = new FrameHeader();
        if (!parsed.parse(seq, gb, new FrameHeader[8], true)) {
            throw new IllegalStateException("the encoder wrote a frame header it cannot parse");
        }
        return parsed;
    }

    /**
     * Returns whether the loaded source looks like screen content: more than a tenth of its
     * 16x16 luma blocks hold two to four distinct values.
     *
     * @return {@code true} to enable the screen content tools
     * @implNote This implementation ports libaom's {@code av1_set_screen_content_options}
     * ({@code av1/encoder/encoder.c}).
     */
    private boolean screenContent() {
        var src = source.data[0];
        var stride = source.stride[0];
        var counts = 0L;
        var values = new int[5];
        for (var by = 0; by + 16 <= height; by += 16) {
            for (var bx = 0; bx + 16 <= width; bx += 16) {
                var n = 0;
                for (var y = 0; y < 16 && n <= 4; y++) {
                    var row = (by + y) * stride + bx;
                    for (var x = 0; x < 16 && n <= 4; x++) {
                        var v = src[row + x];
                        var found = false;
                        for (var i = 0; i < n; i++) {
                            if (values[i] == v) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            values[n++] = v;
                        }
                    }
                }
                if (n > 1 && n <= 4) {
                    counts++;
                }
            }
        }
        return counts * 16 * 16 * 10 > (long) width * height;
    }

    /**
     * Returns the deblocking level the quantizer implies ({@code LPF_PICK_FROM_Q}).
     *
     * @return the level
     */
    private int lfModel() {
        if (settings.qindex == 0) {
            return 0;
        }
        var q = DequantTables.DQ[seq.hbd * 512 + settings.qindex * 2 + 1];
        int guess = switch (bitDepth) {
            case 8 -> (q * 17563 - 421574 + (1 << 17)) >> 18;
            case 10 -> ((q * 20723 + 4060632 + (1 << 19)) >> 20) - 4;
            default -> ((int) (((long) q * 20723 + 16242526 + (1 << 21)) >> 22)) - 4;
        };
        return Math.clamp(guess, 0, 63);
    }

    /**
     * Prepares the frame context: geometry, reconstruction picture, CDFs, quantizers and the
     * decoder's frame setup.
     */
    private void setupFrame() {
        f.seqHdr = seq;
        f.frameHdr = hdr;
        for (var i = 0; i < 7; i++) {
            f.refp[i] = null;
            f.refMvs[i] = null;
            f.svcScale[i * 2] = 0;
            f.svcScale[i * 2 + 1] = 0;
            f.gmvWarpAllowed[i] = false;
            f.refpoc[i] = 0;
        }
        if (recon != null) {
            recon.release();
            recon = null;
        }
        var cur = pool.obtain(width, height, layout, bitDepth);
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
        f.bitdepthMax = bdMax;
        f.haveTt = true;
        f.curSegmap = null;
        f.prevSegmap = null;
        var cdf = new char[Cdf.SIZE];
        Cdf.loadDefaults(cdf, Cdf.qcat(hdr.quantYac));
        f.inCdf = cdf;
        f.outCdf = null;
        EncoderBridge.initFrame(f, limits);
        for (var n = 0; n < f.sb128w * hdr.tilingRows; n++) {
            f.a[n].reset(true);
        }
        quant = new StillQuant(Arrays.copyOf(f.dq, 6), hdr.quantYac, bitDepth, hdr.allLossless);
        rdmult = rdmult(hdr.quantYac);
    }

    /**
     * Returns the Lagrangian multiplier of a key frame at a quantizer index
     * ({@code av1_compute_rd_mult_based_on_qindex}).
     *
     * @param qindex the quantizer index
     * @return the multiplier, at the 8-bit scale
     */
    private int rdmult(int qindex) {
        long q = DequantTables.DQ[seq.hbd * 512 + qindex * 2];
        var rd = (long) (q * q * (3.3 + 0.0015 * q));
        var shift = 2 * (bitDepth - 8);
        if (shift > 0) {
            rd = (rd + (1L << (shift - 1))) >> shift;
        }
        return (int) Math.clamp(rd, 1, Integer.MAX_VALUE);
    }

    /**
     * Encodes every tile, in parallel bands of tiles when several threads are allowed.
     */
    private void encodeTiles() {
        // TODO: run the superblock rows of an all-intra image in a wavefront too, which needs a cost
        // model that does not adapt along the tile.
        var cols = hdr.tilingCols;
        var nTiles = cols * hdr.tilingRows;
        if (settings.rowWavefront) {
            encodeRowWavefront(cols);
            return;
        }
        var bands = Math.min(nTiles, threads());
        if (tileEncoders.length < bands) {
            var grown = Arrays.copyOf(tileEncoders, bands);
            for (var i = tileEncoders.length; i < bands; i++) {
                grown[i] = new StillTileEncoder(this);
            }
            tileEncoders = grown;
        }
        if (bands <= 1) {
            for (var i = 0; i < nTiles; i++) {
                tileEncoders[0].encode(i / cols, i % cols);
            }
        } else {
            ParallelBands.run(bands, band -> {
                for (var i = band; i < nTiles; i += bands) {
                    tileEncoders[band].encode(i / cols, i % cols);
                }
            });
        }
    }

    /**
     * The wavefront the superblock rows of a frame synchronize on.
     */
    private final SbRowWavefront wavefront = new SbRowWavefront();

    /**
     * Encodes the superblock rows of every tile in a wavefront, as many at once as the threads
     * allow, each costed from the initial CDFs of the frame so the stream is the same for any
     * number of threads.
     *
     * @param cols the number of tile columns
     */
    private void encodeRowWavefront(int cols) {
        var units = 0;
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            units += (Math.min(hdr.rowStartSb[tileRow + 1], sbRows) - hdr.rowStartSb[tileRow]) * cols;
        }
        var unitTile = new int[units];
        var unitRow = new int[units];
        var unitAbove = new int[units];
        var unit = 0;
        for (var tileRow = 0; tileRow < hdr.tilingRows; tileRow++) {
            var first = hdr.rowStartSb[tileRow];
            var end = Math.min(hdr.rowStartSb[tileRow + 1], sbRows);
            for (var sby = first; sby < end; sby++) {
                for (var col = 0; col < cols; col++, unit++) {
                    unitTile[unit] = tileRow * cols + col;
                    unitRow[unit] = sby;
                    unitAbove[unit] = sby == first ? -1 : unit - cols;
                }
            }
        }
        var participants = Math.min(threads(), units);
        if (tileEncoders.length < participants) {
            var grown = Arrays.copyOf(tileEncoders, participants);
            for (var i = tileEncoders.length; i < participants; i++) {
                grown[i] = new StillTileEncoder(this);
            }
            tileEncoders = grown;
        }
        wavefront.run(units, 0, participants, (participant, u) -> {
            var tile = unitTile[u];
            tileEncoders[participant].encodeWavefrontRow(tile / cols, tile % cols, unitRow[u], wavefront, u,
                    unitAbove[u]);
        }, () -> {
        }).join();
    }

    /**
     * Picks the deblocking levels and CDEF strengths, applies the in-loop filters to the
     * reconstruction and records each superblock's CDEF index.
     */
    private void pickFilters() {
        Arrays.fill(sbCdef, 0);
        if (hdr.allLossless) {
            return;
        }
        f.lf = LoopFilterDsp.select();
        f.cdef = CdefDsp.select();
        LoopFilterSearch.pick(this, settings.loopFilterSearch);
        for (var sby = 0; sby < f.sbh; sby++) {
            EncoderBridge.deblockSbrow(f, sby);
        }
        CdefSearch.pick(this, settings.cdefSearch);
        var task = new TaskContext();
        task.f = f;
        for (var sby = 0; sby < f.sbh; sby++) {
            EncoderBridge.cdefSbrow(task, f, sby);
        }
        if (seq.restoration && settings.restoration) {
            f.lr = LoopRestorationDsp.select();
            new RestorationSearch(this).pick();
        }
    }

    @Override
    public void setCdefIndex(int sbIndex, int idx) {
        sbCdef[sbIndex] = idx;
    }

    @Override
    public FrameContext context() {
        return f;
    }

    @Override
    public FrameHeader header() {
        return hdr;
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
        return layout;
    }

    @Override
    public int ssHor() {
        return ssHor;
    }

    @Override
    public int ssVer() {
        return ssVer;
    }

    @Override
    public int bitDepth() {
        return bitDepth;
    }

    @Override
    public int bdMax() {
        return bdMax;
    }

    @Override
    public int hbd() {
        return seq.hbd;
    }

    @Override
    public int distShift() {
        return distShift;
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
        return rdmult;
    }

    @Override
    public boolean parallel() {
        return settings.threads != 1;
    }

    /**
     * Codes the recorded symbols of every tile and assembles the sequence header OBU and the frame
     * OBU.
     *
     * @param withSequenceHeader whether the output starts with the sequence header OBU
     * @return the OBUs
     */
    private byte[] assemble(boolean withSequenceHeader) {
        var cols = hdr.tilingCols;
        var nTiles = cols * hdr.tilingRows;
        var sizes = new int[nTiles];
        var data = new byte[nTiles][];
        var cdf = new char[Cdf.SIZE];
        var ec = new RangeEncoder();
        for (var i = 0; i < nTiles; i++) {
            var tileRow = i / cols;
            var tileCol = i % cols;
            ec.reset(false);
            System.arraycopy(f.inCdf, 0, cdf, 0, Cdf.SIZE);
            var rowEnd = Math.min(hdr.rowStartSb[tileRow + 1], sbRows);
            var colEnd = Math.min(hdr.colStartSb[tileCol + 1], sbCols);
            var lrRefs = f.restorePlanes != 0 ? RestorationSearch.tileReferences() : null;
            for (var sby = hdr.rowStartSb[tileRow]; sby < rowEnd; sby++) {
                for (var sbx = hdr.colStartSb[tileCol]; sbx < colEnd; sbx++) {
                    var index = sby * sbCols + sbx;
                    if (lrRefs != null) {
                        RestorationSearch.write(ec, cdf, f, lrRefs, sbx * 16, sby * 16);
                    }
                    logs[index].replay(ec, cdf, sbCdef[index], hdr.cdefNBits);
                }
            }
            sizes[i] = ec.finish();
            data[i] = Arrays.copyOf(ec.buffer(), sizes[i]);
        }
        var maxSize = 0;
        for (var s : sizes) {
            maxSize = Math.max(maxSize, s);
        }
        hdr.tilingUpdate = 0;
        hdr.tilingNBytes = ObuWriter.tileSizeBytes(maxSize);
        parseBack(hdr);
        var w = headerWriter;
        StillHeaderWriter.frameHeader(w, seq, hdr);
        var payload = w.size() + 1 + nTiles * hdr.tilingNBytes;
        for (var s : sizes) {
            payload += s;
        }
        var out = new ByteArrayOutputStream(payload + seqObu.length + 16);
        if (withSequenceHeader) {
            ObuWriter.writeObu(out, Obu.OBU_SEQ_HDR, seqObu, 0, seqObu.length);
        }
        ObuWriter.writeFrameObu(out, w, false, 0, nTiles, sizes, data, hdr.tilingNBytes);
        recon = f.cur;
        return out.toByteArray();
    }

    @Override
    public void close() {
        if (recon != null) {
            recon.release();
            recon = null;
        }
        source.release();
    }
}
