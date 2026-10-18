package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds one decoded picture of the decoded picture buffer: its sample planes, its motion field
 * (kept for temporal motion vector prediction and deblocking), and its reference and output state.
 *
 * <p>Pictures whose luma and chroma samples both have 8 bits keep them in 8-bit planes
 * ({@link #y8}, {@link #cb8}, {@link #cr8}); deeper pictures keep them in 16-bit planes
 * ({@link #y}, {@link #cb}, {@link #cr}). The planes of the other kind are {@code null}. The motion
 * field has one entry per 4x4
 * luma block: the two motion vectors packed as {@code (y << 16) | (x & 0xFFFF)} and the two
 * reference indices, -1 for an unused list (both -1 for intra blocks).
 *
 * <p>Pictures decoded concurrently wait for the published rows of their references: the rows
 * whose motion is final ({@link #awaitDecodedRows(int)}) and the rows whose samples are final, in
 * loop filter order ({@link #awaitRows(int)}). Each wait is declared to the module's pool with
 * {@link ParallelBands#beginBlocking()}, so a pool helper blocked here lets another helper decode
 * meanwhile.
 */
public final class HevcFrame {
    /**
     * The number of bytes the 8-bit planes hold past their last sample, so that vector kernels may
     * load whole vectors that start inside a plane.
     */
    public static final int PADDING = 16;

    /**
     * The 16-bit luma plane, {@link #width} samples per row, or {@code null} for 8-bit pictures.
     */
    public final short[] y;

    /**
     * The 16-bit Cb plane, {@link #chromaWidth} samples per row, or {@code null} for monochrome
     * video and 8-bit pictures.
     */
    public final short[] cb;

    /**
     * The 16-bit Cr plane, {@link #chromaWidth} samples per row, or {@code null} for monochrome
     * video and 8-bit pictures.
     */
    public final short[] cr;

    /**
     * The 8-bit luma plane, {@link #width} samples per row followed by {@link #PADDING} bytes, or
     * {@code null} for deeper pictures.
     */
    public final byte[] y8;

    /**
     * The 8-bit Cb plane, {@link #chromaWidth} samples per row followed by {@link #PADDING} bytes,
     * or {@code null} for monochrome video and deeper pictures.
     */
    public final byte[] cb8;

    /**
     * The 8-bit Cr plane, {@link #chromaWidth} samples per row followed by {@link #PADDING} bytes,
     * or {@code null} for monochrome video and deeper pictures.
     */
    public final byte[] cr8;

    /**
     * The coded width in luma samples.
     */
    public final int width;

    /**
     * The coded height in luma samples.
     */
    public final int height;

    /**
     * The width of the chroma planes.
     */
    public final int chromaWidth;

    /**
     * The height of the chroma planes.
     */
    public final int chromaHeight;

    /**
     * The chroma format, {@code chroma_format_idc}.
     */
    public final int chromaFormat;

    /**
     * The width of the motion field in 4x4 blocks.
     */
    public final int width4;

    /**
     * The height of the motion field in 4x4 blocks.
     */
    public final int height4;

    /**
     * The list 0 motion vectors.
     */
    public final int[] mv0;

    /**
     * The list 1 motion vectors.
     */
    public final int[] mv1;

    /**
     * The list 0 reference indices.
     */
    public final byte[] ref0;

    /**
     * The list 1 reference indices.
     */
    public final byte[] ref1;

    /**
     * The index into {@link #sliceRefPoc} of the slice of each coding tree block, by raster address.
     */
    public int[] ctbSlice;

    /**
     * The picture order counts of the reference pictures of each slice of the picture, indexed
     * {@code [slice][list * 16 + refIdx]}.
     */
    public int[][] sliceRefPoc = new int[4][];

    /**
     * Whether each reference picture of each slice was a long-term reference when the picture was
     * decoded, indexed like {@link #sliceRefPoc}.
     */
    public boolean[][] sliceRefLongTerm = new boolean[4][];

    /**
     * The identifiers of the reference pictures of each slice ({@link #id}), indexed like
     * {@link #sliceRefPoc}.
     */
    public int[][] sliceRefId = new int[4][];

    /**
     * The number of slices recorded in {@link #sliceRefPoc}.
     */
    public int sliceCount;

    /**
     * A number unique to the picture among the pictures of the decoder.
     */
    public int id;

    /**
     * {@code PicOrderCntVal}.
     */
    public int poc;

    /**
     * Whether the picture is marked "used for short-term reference".
     */
    public boolean shortTerm;

    /**
     * Whether the picture is marked "used for long-term reference".
     */
    public boolean longTerm;

    /**
     * Whether the picture is marked "needed for output".
     */
    public boolean neededForOutput;

    /**
     * {@code PicLatencyCount}.
     */
    public int latencyCount;

    /**
     * Whether the picture was generated for a missing reference rather than decoded.
     */
    public boolean generated;

    /**
     * Whether the picture is an IRAP picture.
     */
    public boolean irap;

    /**
     * Whether decoding the picture failed, so its samples are concealed.
     */
    public boolean damaged;

    /**
     * The presentation timestamp carried by the packet of the picture's first slice.
     */
    public long pts;

    /**
     * The duration carried by the packet of the picture's first slice.
     */
    public long duration;

    /**
     * The sequence parameter set the picture was decoded with.
     */
    public HevcSps sps;

    /**
     * The color description of the picture.
     */
    public com.github.auties00.smalt.image.ColorInfo color;

    /**
     * The number of leading luma sample rows whose samples are final: decoded, deblocked and
     * offset.
     */
    private volatile int readyRows;

    /**
     * The number of leading luma sample rows whose motion field is final.
     */
    private volatile int decodedRows;

    /**
     * Whether decoding of the picture finished, successfully or not.
     */
    private volatile boolean done = true;

    /**
     * Guards the waits on the progress counters.
     */
    private final ReentrantLock progressLock = new ReentrantLock();

    /**
     * Signals changes of the progress counters.
     */
    private final Condition progressChanged = progressLock.newCondition();

    /**
     * The number of picture jobs started when the picture left the decoded picture buffer; it
     * returns to the pool once every job up to that one finished, since only those may read it.
     */
    long releaseAfter;

    /**
     * Whether the picture was bumped out of the decoded picture buffer but not yet handed to the
     * consumer; it returns to the pool only afterwards.
     */
    boolean awaitingOutput;

    /**
     * Allocates a picture.
     *
     * @param width        the coded width in luma samples
     * @param height       the coded height in luma samples
     * @param chromaFormat the chroma format, {@code chroma_format_idc}
     * @param eightBit     whether the luma and chroma samples both have 8 bits, selecting 8-bit
     *                     planes
     */
    public HevcFrame(int width, int height, int chromaFormat, boolean eightBit) {
        this.width = width;
        this.height = height;
        this.chromaFormat = chromaFormat;
        this.y = eightBit ? null : new short[width * height];
        this.y8 = eightBit ? new byte[width * height + PADDING] : null;
        if (chromaFormat == 0) {
            this.chromaWidth = 0;
            this.chromaHeight = 0;
            this.cb = null;
            this.cr = null;
            this.cb8 = null;
            this.cr8 = null;
        } else {
            this.chromaWidth = chromaFormat == 3 ? width : (width + 1) >> 1;
            this.chromaHeight = chromaFormat == 1 ? (height + 1) >> 1 : height;
            var samples = chromaWidth * chromaHeight;
            this.cb = eightBit ? null : new short[samples];
            this.cr = eightBit ? null : new short[samples];
            this.cb8 = eightBit ? new byte[samples + PADDING] : null;
            this.cr8 = eightBit ? new byte[samples + PADDING] : null;
        }
        this.width4 = (width + 3) >> 2;
        this.height4 = (height + 3) >> 2;
        var blocks = width4 * height4;
        this.mv0 = new int[blocks];
        this.mv1 = new int[blocks];
        this.ref0 = new byte[blocks];
        this.ref1 = new byte[blocks];
    }

    /**
     * Returns whether this picture's planes and motion field fit a sequence parameter set.
     *
     * @param sps the sequence parameter set
     * @return {@code true} when the picture can be reused for pictures of {@code sps}
     */
    public boolean fits(HevcSps sps) {
        return width == sps.width && height == sps.height && chromaFormat == sps.chromaFormatIdc
                && (y8 != null) == eightBit(sps);
    }

    /**
     * Returns whether the pictures of a sequence keep their samples in 8-bit planes: those whose
     * luma and chroma samples both have 8 bits.
     *
     * @param sps the sequence parameter set
     * @return {@code true} for 8-bit planes
     */
    public static boolean eightBit(HevcSps sps) {
        return sps.bitDepthLuma == 8 && sps.bitDepthChroma == 8;
    }

    /**
     * Returns whether this picture keeps its samples in 8-bit planes.
     *
     * @return {@code true} when {@link #y8} is set
     */
    public boolean eightBit() {
        return y8 != null;
    }

    /**
     * Resets the reference and output state for a new picture and marks the whole motion field as
     * intra.
     *
     * @param sizeInCtbs the number of coding tree blocks of the picture
     */
    public void reset(int sizeInCtbs) {
        shortTerm = false;
        longTerm = false;
        neededForOutput = false;
        latencyCount = 0;
        generated = false;
        irap = false;
        damaged = false;
        sliceCount = 0;
        Arrays.fill(ref0, (byte) -1);
        Arrays.fill(ref1, (byte) -1);
        if (ctbSlice == null || ctbSlice.length != sizeInCtbs) {
            ctbSlice = new int[sizeInCtbs];
        }
    }

    /**
     * Records the reference lists of a slice of this picture and returns its index.
     *
     * @param poc      the picture order counts of the entries, {@code list * 16 + refIdx}
     * @param longTerm whether each entry is a long-term reference
     * @param ids      the identifiers of the entries
     * @return the slice index for {@link #ctbSlice}
     */
    public int addSlice(int[] poc, boolean[] longTerm, int[] ids) {
        if (sliceCount == sliceRefPoc.length) {
            sliceRefPoc = Arrays.copyOf(sliceRefPoc, sliceCount * 2);
            sliceRefLongTerm = Arrays.copyOf(sliceRefLongTerm, sliceCount * 2);
            sliceRefId = Arrays.copyOf(sliceRefId, sliceCount * 2);
        }
        sliceRefPoc[sliceCount] = poc;
        sliceRefLongTerm[sliceCount] = longTerm;
        sliceRefId[sliceCount] = ids;
        return sliceCount++;
    }

    /**
     * The unfiltered bottom sample line of every row of coding tree blocks but the last, one array
     * per color component and row stride per row, when the samples are 8-bit; {@code null}
     * before {@link #prepareEdges(int)}.
     */
    public byte[][] edge8;

    /**
     * Like {@link #edge8} for pictures with deeper samples.
     */
    public short[][] edge16;

    /**
     * Makes room for the bottom sample lines the intra prediction of the next row of coding tree
     * blocks reads, which the in-loop filters of a row may change before that row is decoded.
     *
     * @param ctbRows the number of rows of coding tree blocks of the picture
     */
    public void prepareEdges(int ctbRows) {
        var planes = chromaFormat == 0 ? 1 : 3;
        if (y8 != null) {
            if (edge8 == null || edge8.length != planes || edge8[0].length < ctbRows * width) {
                edge8 = new byte[planes][];
                edge8[0] = new byte[ctbRows * width];
                for (var c = 1; c < planes; c++) {
                    edge8[c] = new byte[ctbRows * chromaWidth];
                }
            }
        } else if (edge16 == null || edge16.length != planes || edge16[0].length < ctbRows * width) {
            edge16 = new short[planes][];
            edge16[0] = new short[ctbRows * width];
            for (var c = 1; c < planes; c++) {
                edge16[c] = new short[ctbRows * chromaWidth];
            }
        }
    }

    /**
     * Marks the picture as being decoded: no row is final yet.
     */
    public void beginDecoding() {
        readyRows = 0;
        decodedRows = 0;
        done = false;
    }

    /**
     * Publishes that the motion of the leading luma rows is final.
     *
     * @param rows the number of luma rows
     */
    public void publishDecodedRows(int rows) {
        if (rows <= decodedRows) {
            return;
        }
        progressLock.lock();
        try {
            decodedRows = rows;
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Publishes that the samples of the leading luma rows, and of the matching chroma rows, are
     * final.
     *
     * @param rows the number of luma rows
     */
    public void publishRows(int rows) {
        if (rows <= readyRows) {
            return;
        }
        progressLock.lock();
        try {
            readyRows = rows;
            decodedRows = Math.max(decodedRows, rows);
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Publishes that decoding of the picture finished: every row is final.
     */
    public void publishDone() {
        progressLock.lock();
        try {
            readyRows = height;
            decodedRows = height;
            done = true;
            progressChanged.signalAll();
        } finally {
            progressLock.unlock();
        }
    }

    /**
     * Returns whether decoding of the picture finished.
     *
     * @return {@code true} once {@link #publishDone()} was called
     */
    public boolean isDone() {
        return done;
    }

    /**
     * Waits until the samples of at least the given number of leading luma rows are final; returns
     * at once for a picture that is not being decoded.
     *
     * @param rows the number of luma rows needed, clamped to the picture height
     */
    public void awaitRows(int rows) {
        if (readyRows >= rows || done) {
            return;
        }
        var needed = Math.min(rows, height);
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (readyRows < needed && !done) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Waits until the motion of at least the given number of leading luma rows is final; returns at
     * once for a picture that is not being decoded.
     *
     * @param rows the number of luma rows needed, clamped to the picture height
     */
    public void awaitDecodedRows(int rows) {
        if (decodedRows >= rows || done) {
            return;
        }
        var needed = Math.min(rows, height);
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (decodedRows < needed && !done) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Waits until decoding of the picture finished.
     */
    public void awaitDone() {
        if (done) {
            return;
        }
        ParallelBands.beginBlocking();
        progressLock.lock();
        try {
            while (!done) {
                progressChanged.awaitUninterruptibly();
            }
        } finally {
            progressLock.unlock();
            ParallelBands.endBlocking();
        }
    }

    /**
     * Returns whether the picture is marked as used for reference.
     *
     * @return {@code true} when marked short-term or long-term
     */
    public boolean isReference() {
        return shortTerm || longTerm;
    }
}
