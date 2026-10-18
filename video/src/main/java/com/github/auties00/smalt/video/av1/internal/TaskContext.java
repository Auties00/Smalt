package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the per-thread state of block decoding and post-filtering: the block position, the left
 * context, and every scratch buffer the decoding of one block or filter unit needs.
 *
 * <p>One instance serves one thread at a time; parallel tiles and filter rows each use their own.
 *
 * @implNote This implementation mirrors dav1d's {@code Dav1dTaskContext} ({@code src/internal.h}),
 * with its scratch unions split into separate arrays.
 */
public final class TaskContext {
    /**
     * The pass that decodes and reconstructs every block at once.
     */
    public static final int PASS_SINGLE = 0;

    /**
     * The first pass of two-pass decoding: entropy decoding of the mode information and
     * coefficients into {@link FrameContext#records}, without reconstruction.
     */
    public static final int PASS_PARSE = 1;

    /**
     * The second pass of two-pass decoding: reconstruction of the blocks the first pass recorded.
     */
    public static final int PASS_RECON = 2;

    /**
     * The pass this context runs, one of {@link #PASS_SINGLE}, {@link #PASS_PARSE} and
     * {@link #PASS_RECON}.
     */
    public int pass;

    /**
     * The block records of the tile superblock row being parsed or reconstructed; see
     * {@link BlockRecords}.
     */
    int[] records;

    /**
     * The position of the next record entry in {@link #records}.
     */
    int recordPos;

    /**
     * Whether the next block record of the parsing pass carries the loop filter deltas the
     * superblock it starts signaled.
     */
    boolean recordDeltaLf;

    /**
     * The loop filter levels the reconstruction pass applies to the blocks of the current
     * superblock: the frame's or {@link #reconLflvlMem}.
     */
    byte[] reconLflvl;

    /**
     * The loop filter levels derived from the deltas of the current superblock in the
     * reconstruction pass.
     */
    final byte[] reconLflvlMem = new byte[LoopFilterMasks.LF_VALUES_LENGTH];

    /**
     * The loop filter deltas {@link #reconLflvl} was derived from.
     */
    final int[] reconDeltaLf = new int[4];

    /**
     * The frame being decoded.
     */
    public FrameContext f;

    /**
     * The tile being decoded.
     */
    public TileState ts;

    /**
     * The block column, in 4-sample units.
     */
    public int bx;

    /**
     * The block row, in 4-sample units.
     */
    public int by;

    /**
     * The left context of the superblock row.
     */
    public final BlockContext l = new BlockContext();

    /**
     * The above context of the current 128-sample column.
     */
    public BlockContext a;

    /**
     * The index of {@link #a} in the frame's above contexts.
     */
    public int aIdx;

    /**
     * The motion vector prediction state of the tile.
     */
    public final RefMvs.Tile rt = new RefMvs.Tile();

    /**
     * The coefficients of the transform block being decoded, in dav1d's transposed layout.
     */
    public final int[] cf = new int[32 * 32];

    /**
     * The above ({@code [0]}) and left ({@code [1]}) palettes, as {@code [2][32][plane 3][8]}.
     */
    public final short[] alPal = new short[2 * 32 * 3 * 8];

    /**
     * The above ({@code [0]}) and left ({@code [1]}) chroma palette sizes, as {@code [2][32]}.
     */
    public final byte[] palSzUv = new byte[2 * 32];

    /**
     * The overlapped block motion compensation prediction.
     */
    public final short[] lap = new short[128 * 32];

    /**
     * The intermediate predictions of the two references of a compound block.
     */
    public final short[][] compinter = {new short[128 * 128], new short[128 * 128]};

    /**
     * The mask of difference-weighted compound prediction.
     */
    public final byte[] segMask = new byte[128 * 128];

    /**
     * The edge-extended reference area of a block whose reference reaches outside the frame.
     */
    public final short[] emuEdge = new short[320 * (256 + 7)];

    /**
     * The coefficient levels of the transform block being decoded.
     */
    public final byte[] levels = new byte[32 * 34];

    /**
     * The color orders of one palette index diagonal.
     */
    public final byte[] palOrder = new byte[64 * 8];

    /**
     * The color contexts of one palette index diagonal.
     */
    public final byte[] palCtx = new byte[64];

    /**
     * The chroma-from-luma averaged luma.
     */
    public final short[] ac = new short[IpredDsp.AC_SHORTS];

    /**
     * The transform type of each 4x4 luma unit of an inter block, as {@code [32][32]}.
     */
    public final byte[] txtpMap = new byte[32 * 32];

    /**
     * The packed luma palette indices.
     */
    public final byte[] palIdxY = new byte[32 * 64];

    /**
     * The packed chroma palette indices, also the unpacked scratch of both planes.
     */
    public final byte[] palIdxUv = new byte[64 * 64];

    /**
     * The intra prediction of inter-intra blocks.
     */
    public final short[] interintra = new short[64 * 64];

    /**
     * The intra edge buffer; the top-left sample is at {@link IpredDsp#EDGE_TOPLEFT}.
     */
    public final short[] edge = new short[IpredDsp.EDGE_BUFFER_SIZE];

    /**
     * The palettes of the block, as {@code [plane 3][8]}.
     */
    public final short[] pal = new short[3 * 8];

    /**
     * The neighbor color cache of palette decoding.
     */
    public final int[] palCache = new int[16];

    /**
     * The cached colors a palette reuses.
     */
    public final int[] palUsedCache = new int[8];

    /**
     * The transform type of the transform block being decoded; for chroma of inter blocks, the luma
     * type it derives from.
     */
    public int txtp;

    /**
     * The coefficient context byte the transform block being decoded leaves for its neighbors.
     */
    public int cfCtx;

    /**
     * The source of the coefficients of every transform block in place of the bitstream, or
     * {@code null} to decode them; the encoder sets it to drive the decoder's reconstruction.
     */
    public CoefficientSource coefSource;

    /**
     * The motion compensation scratch.
     */
    public final short[] mcScratch = new short[McDsp.SCRATCH_SHORTS];

    /**
     * The index scratch of the warp kernels.
     */
    public final int[] warpIndices = new int[McDsp.WARP_SCRATCH_INTS];

    /**
     * The inverse transform scratch.
     */
    public final int[] itxScratch = new int[ItxDsp.SCRATCH_INTS];

    /**
     * The intra prediction scratch.
     */
    public final short[] ipredScratch = new short[IpredDsp.SCRATCH_SHORTS];

    /**
     * The deblocking kernel scratch of the vertical edges a reconstruction task filters.
     */
    public final short[] lfScratch = new short[LoopFilterDsp.SCRATCH_SHORTS];

    /**
     * The loop filter mask scratch.
     */
    public final byte[] lfTxa = new byte[LoopFilterMasks.TX_SCRATCH_LENGTH];

    /**
     * The intra angle in/out holder of {@link IpredDsp#prepareIntraEdges}.
     */
    public final int[] angle = new int[1];

    /**
     * The local warp model of the block.
     */
    public final WarpedMotionParams warpmv = new WarpedMotionParams();

    /**
     * The loop filter masks of the current 128x128 area.
     */
    public LfMask lfMask;

    /**
     * The index of {@link #lfMask} in the frame's masks.
     */
    public int lfMaskIdx;

    /**
     * The index of the CDEF index entry of the current superblock in {@link #lfMask}.
     */
    public int curSbCdefIdx;

    /**
     * The CDEF line buffer toggle.
     */
    public int topPreCdefToggle;

    /**
     * The 2-D filter of the top-left 4x4 block of a split 8x8 square, for sub-8x8 chroma.
     */
    public int tl4x4Filter;

    /**
     * The mode information of the block being decoded.
     */
    public final Av1Block b = new Av1Block();

    /**
     * The motion vector candidate list.
     */
    public final RefMvs.Candidates cand = new RefMvs.Candidates();

    /**
     * The sample pairs of the local warp fit.
     */
    public final int[] warpPts = new int[32];

    /**
     * The motion vector differences of the local warp fit.
     */
    public final int[] warpMvd = new int[8];

    /**
     * The CDEF filter scratch.
     */
    public final short[] cdefScratch = new short[CdefDsp.SCRATCH_SHORTS];

    /**
     * The CDEF direction variance and scratch.
     */
    public final int[] cdefVar = new int[CdefDsp.DIR_SCRATCH_INTS];

    /**
     * The pre-filter left columns of CDEF blocks, as {@code [2][plane 3][8][2]}.
     */
    public final short[] cdefLrBak = new short[2 * 3 * 8 * 2];

    /**
     * The loop restoration scratch.
     */
    public final int[] lrScratch = new int[LoopRestorationDsp.SCRATCH_INTS];

    /**
     * The pre-filter left columns of restoration units, as {@code [2][136][4]}.
     */
    public final short[] preLrBorder = new short[2 * 136 * 4];

    /**
     * The loop restoration parameters.
     */
    public final LrParams lrParams = new LrParams();

    /**
     * The above and left neighbor masks of the local warp search.
     */
    public final long[] warpMasks = new long[2];

    /**
     * Creates a context.
     */
    public TaskContext() {
    }
}
