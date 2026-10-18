package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the state of the frame being decoded: its headers, pictures, references, tiles, block grid
 * geometry, dequantizers, above contexts, motion state and in-loop filter state.
 *
 * <p>Buffers sized by the frame geometry are kept across frames and reallocated only when the
 * geometry changes.
 *
 * @implNote This implementation mirrors dav1d's {@code Dav1dFrameContext} ({@code src/internal.h}).
 */
public final class FrameContext {
    /**
     * The motion compensation kernels of the frame, selected by {@link FrameDecoder#init}.
     */
    public McDsp mc = McDsp.SCALAR;

    /**
     * The inverse transform kernels of the frame, selected by {@link FrameDecoder#init}.
     */
    public ItxDsp itx = ItxDsp.SCALAR;

    /**
     * The deblocking filter kernels of the frame, selected by {@link FrameDecoder#init}.
     */
    public LoopFilterDsp lf = LoopFilterDsp.SCALAR;

    /**
     * The CDEF kernels of the frame, selected by {@link FrameDecoder#init}.
     */
    public CdefDsp cdef = CdefDsp.SCALAR;

    /**
     * The loop restoration kernels of the frame, selected by {@link FrameDecoder#init}.
     */
    public LoopRestorationDsp lr = LoopRestorationDsp.SCALAR;

    /**
     * The sequence header.
     */
    public SequenceHeader seqHdr;

    /**
     * The frame header.
     */
    public FrameHeader frameHdr;

    /**
     * The picture of each of the seven references.
     */
    public final Av1Picture[] refp = new Av1Picture[7];

    /**
     * The picture being reconstructed, at the coded width.
     */
    public Av1Picture cur;

    /**
     * The output picture, at the upscaled width; the same as {@link #cur} without super-resolution.
     */
    public Av1Picture srCur;

    /**
     * The temporal motion records the frame saves, or {@code null}.
     */
    public RefMvs.Temporal mvs;

    /**
     * The temporal motion records of each reference, or {@code null}.
     */
    public final RefMvs.Temporal[] refMvs = new RefMvs.Temporal[7];

    /**
     * The segmentation map the frame writes or inherits, or {@code null}.
     */
    public byte[] curSegmap;

    /**
     * The segmentation map of the primary reference, or {@code null}.
     */
    public byte[] prevSegmap;

    /**
     * The order hint of each reference.
     */
    public final int[] refpoc = new int[7];

    /**
     * The order hints of the references of each reference.
     */
    public final int[][] refrefpoc = new int[7][7];

    /**
     * Whether global motion of each reference uses a warp.
     */
    public final boolean[] gmvWarpAllowed = new boolean[7];

    /**
     * The CDFs the tiles start from.
     */
    public char[] inCdf;

    /**
     * The CDFs the frame stores for later frames, or {@code null} without context refresh.
     */
    public char[] outCdf;

    /**
     * The buffers of the tile groups.
     */
    public byte[][] tileData = new byte[4][];

    /**
     * The offset of the tile data of each tile group.
     */
    public int[] tileOffset = new int[4];

    /**
     * The size of the tile data of each tile group.
     */
    public int[] tileSize = new int[4];

    /**
     * The first tile of each tile group.
     */
    public int[] tileStart = new int[4];

    /**
     * The last tile of each tile group.
     */
    public int[] tileEnd = new int[4];

    /**
     * The number of tile groups.
     */
    public int nTileData;

    /**
     * The horizontal ({@code [i * 2]}) and vertical ({@code [i * 2 + 1]}) scale of each reference in
     * 1/16384 units, 0 when unscaled.
     */
    public final int[] svcScale = new int[14];

    /**
     * The horizontal and vertical position step of each scaled reference in 1/1024 units.
     */
    public final int[] svcStep = new int[14];

    /**
     * The super-resolution step of luma and chroma.
     */
    public final int[] resizeStep = new int[2];

    /**
     * The super-resolution initial position of luma and chroma.
     */
    public final int[] resizeStart = new int[2];

    /**
     * The tile states.
     */
    public TileState[] ts = new TileState[0];

    /**
     * The bottom row of each superblock row before in-loop filtering, per plane, for intra prediction.
     */
    public final short[][] ipredEdge = new short[3][];

    /**
     * The stride of the 4x4 block grid.
     */
    public int b4Stride;

    /**
     * The frame width in 4-sample units.
     */
    public int w4;

    /**
     * The frame height in 4-sample units.
     */
    public int h4;

    /**
     * The block grid width in 4-sample units, rounded up to 8 samples.
     */
    public int bw;

    /**
     * The block grid height in 4-sample units, rounded up to 8 samples.
     */
    public int bh;

    /**
     * The width in 128-sample units.
     */
    public int sb128w;

    /**
     * The height in 128-sample units.
     */
    public int sb128h;

    /**
     * The number of superblock rows.
     */
    public int sbh;

    /**
     * The base-2 logarithm of the superblock size in 4-sample units.
     */
    public int sbShift;

    /**
     * The superblock size in 4-sample units.
     */
    public int sbStep;

    /**
     * The upscaled width in 128-sample units.
     */
    public int srSb128w;

    /**
     * The frame dequantizers, as {@code [segment 8][plane 3][dc/ac 2]}.
     */
    public final int[] dq = new int[8 * 3 * 2];

    /**
     * The quantizer matrix of each transform size and plane, as {@code [RectTxfmSize 19][plane 3]},
     * or {@code null} entries without matrices.
     */
    public final byte[][] qm = new byte[19 * 3][];

    /**
     * The above contexts, one per 128-sample column and tile row.
     */
    public BlockContext[] a = new BlockContext[0];

    /**
     * The motion vector prediction state.
     */
    public final RefMvs.Frame rf = new RefMvs.Frame();

    /**
     * The distance weights of each reference pair.
     */
    public final int[][] jntWeights = new int[7][7];

    /**
     * The largest sample value.
     */
    public int bitdepthMax;

    /**
     * The loop filter levels of each 4x4 block, four bytes per block.
     */
    public byte[] lfLevel = new byte[0];

    /**
     * The loop filter masks, one per 128x128 area.
     */
    public LfMask[] lfMask = new LfMask[0];

    /**
     * The restoration units, as {@code [128x128 area][plane 3][unit 4]}.
     */
    public RestorationUnit[] lrMask = new RestorationUnit[0];

    /**
     * The loop filter limit tables.
     */
    public final FilterLut limLut = new FilterLut();

    /**
     * The sharpness {@link #limLut} was computed for.
     */
    public int lastSharpness = -1;

    /**
     * The loop filter levels of the frame.
     */
    public final byte[] lvl = new byte[LoopFilterMasks.LF_VALUES_LENGTH];

    /**
     * The loop filter transform sizes at the right edge of each tile column, luma then chroma.
     */
    public final byte[][] txLpfRightEdge = new byte[2][];

    /**
     * The pre-CDEF lines saved above each superblock row: the luma buffer, then the chroma one twice.
     */
    public final short[][] cdefLine = new short[3][];

    /**
     * The offset of each saved line, as {@code [toggle 2][plane 3]}.
     */
    public final int[][] cdefLineOff = new int[2][3];

    /**
     * The deblocked lines of each plane saved for super-resolved CDEF in tile threading, or
     * {@code null}.
     */
    public final short[][] cdefLpfLine = new short[3][];

    /**
     * The deblocked lines of each plane saved above and below each loop restoration stripe.
     */
    public final short[][] lrLpfLine = new short[3][];

    /**
     * The tile row starting at each superblock row, or 0.
     */
    public byte[] startOfTileRow = new byte[0];

    /**
     * The planes that use loop restoration, as a bit mask.
     */
    public int restorePlanes;


    /**
     * Whether tile threading saves per-superblock-row line buffers.
     */
    public boolean haveTt;

    /**
     * Whether the frame decodes in two passes: entropy decoding into {@link #records}, then
     * reconstruction from them; set together with {@link #haveTt}.
     */
    public boolean twoPass;

    /**
     * The block records of each tile superblock row, as {@code [sby * tilingCols + tileCol]},
     * written by the parsing pass and read by the reconstruction pass of two-pass decoding.
     */
    int[][] records = new int[0][];

    /**
     * The lowest sample row of each reference that the inter blocks of each tile superblock row
     * read, as {@code [sby * tilingCols + tileCol][reference 7][luma/chroma 2]} in the rows of the
     * plane, {@link Integer#MIN_VALUE} for a reference the row does not read.
     */
    int[] lowestPx = new int[0];

    /**
     * The above contexts of the reconstruction pass, laid out as {@link #a}.
     */
    BlockContext[] reconA = new BlockContext[0];

    /**
     * The motion records of the reconstruction pass: one {@link RefMvs#BLOCK_INTS} record per 4x4
     * block of the whole frame, rows {@link #reconBlocksStride} blocks apart, which overlapped
     * prediction and sub-8x8 chroma prediction read for the neighbors of a block.
     */
    int[] reconBlocks = new int[0];

    /**
     * The row stride of {@link #reconBlocks}, in blocks.
     */
    int reconBlocksStride;

    /**
     * The number of block rows of {@link #reconBlocks}.
     */
    int reconBlocksRows;

    /**
     * Whether the frame is decoded while other frames are: its reconstruction waits for the rows
     * of its references ({@link Av1Picture#progress}) and it publishes the final rows of its own
     * picture.
     */
    boolean frameThreaded;

    /**
     * The progress of the superblock rows the reconstruction stage of two-pass decoding
     * reconstructs at once.
     */
    final ReconWavefront wavefront = new ReconWavefront();

    /**
     * The task contexts of the reconstruction stage not in use.
     */
    private final java.util.ArrayDeque<TaskContext> reconContexts = new java.util.ArrayDeque<>();

    /**
     * Guards {@link #reconContexts}.
     */
    private final Object reconContextsLock = new Object();


    /**
     * The scratch buffer of the deblocking filter kernels, used by the one thread deblocking the
     * frame at a time.
     */
    public final short[] lfScratch = new short[LoopFilterDsp.SCRATCH_SHORTS];

    /**
     * Creates an empty context.
     */
    public FrameContext() {
    }

    /**
     * Takes a task context for the reconstruction of one superblock row, creating one when none is
     * free; any thread may call it.
     *
     * @return the context, in {@link TaskContext#PASS_RECON}
     */
    TaskContext takeReconContext() {
        TaskContext t;
        synchronized (reconContextsLock) {
            t = reconContexts.poll();
        }
        if (t == null) {
            t = new TaskContext();
            t.pass = TaskContext.PASS_RECON;
        }
        return t;
    }

    /**
     * Returns a task context taken with {@link #takeReconContext()}.
     *
     * @param t the context
     */
    void returnReconContext(TaskContext t) {
        synchronized (reconContextsLock) {
            reconContexts.push(t);
        }
    }

    /**
     * Returns whether the frame uses super-resolution.
     *
     * @return {@code true} when the coded width differs from the upscaled width
     */
    public boolean resized() {
        return frameHdr.codedWidth != frameHdr.upscaledWidth;
    }
}
