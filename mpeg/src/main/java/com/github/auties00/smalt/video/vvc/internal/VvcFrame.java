package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * The decoding state of the picture being decoded: its coding units, the per 4x4 block maps the neighbor
 * derivations and the loop filters read, and the per CTU loop filter parameters.
 *
 * <p>The state is sized for one picture size and reused across pictures of that size.
 */
final class VvcFrame {
    /**
     * The coded width in luma samples.
     */
    final int width;
    /**
     * The coded height in luma samples.
     */
    final int height;
    /**
     * {@code CtbLog2SizeY}.
     */
    final int log2Ctu;
    /**
     * {@code CtbSizeY}.
     */
    final int ctuSize;
    /**
     * {@code PicWidthInCtbsY}.
     */
    final int widthInCtus;
    /**
     * {@code PicHeightInCtbsY}.
     */
    final int heightInCtus;
    /**
     * The width in 4x4 blocks.
     */
    final int width4;
    /**
     * The height in 4x4 blocks.
     */
    final int height4;

    /**
     * The picture being decoded.
     */
    VvcPicture pic;
    /**
     * The sequence parameter set of the picture being decoded.
     */
    VvcSps sps;
    /**
     * The picture parameter set of the picture being decoded.
     */
    VvcPps pps;
    /**
     * The picture header of the picture being decoded.
     */
    VvcPictureHeader ph;

    /**
     * The number of bits of a coding unit reference that hold the index in its lane's pool.
     */
    private static final int LANE_SHIFT = 26;

    /**
     * The most lanes that decode coding units of one picture.
     */
    static final int MAX_LANES = 1 << (31 - LANE_SHIFT);

    /**
     * The coding units each lane allocated for the picture, recycled from picture to picture: a lane decodes the
     * substreams of the picture one at a time, and the lanes decode concurrently.
     */
    private final CuPool[] pools = new CuPool[MAX_LANES];

    /**
     * The coding units one lane allocated.
     */
    private static final class CuPool {
        /**
         * The coding units, the first {@link #size} of the current picture. A reader on another thread may see an
         * older, shorter array, which holds no unit it can be looking for.
         */
        volatile VvcCu[] units = new VvcCu[256];

        /**
         * The number of coding units of the picture.
         */
        int size;
    }

    /**
     * The coding unit index plus one of the luma tree at each 4x4 block, 0 when not yet decoded.
     */
    final int[] cuLuma;
    /**
     * The coding unit index plus one of the chroma tree at each 4x4 luma block, 0 when not yet decoded.
     */
    final int[] cuChroma;
    /**
     * Whether each 4x4 luma block has been reconstructed, for luma and for chroma.
     */
    final boolean[] decY;
    /**
     * Whether each 4x4 luma block has been reconstructed for chroma.
     */
    final boolean[] decC;
    /**
     * Whether wavefront parallel processing restricts the availability of the CTUs to the right.
     */
    boolean wpp;
    /**
     * The slice index at each CTU, -1 before the CTU is decoded.
     */
    final int[] ctuSlice;
    /**
     * The tile index of each CTU.
     */
    final int[] ctuTile;
    /**
     * The subpicture index of each CTU.
     */
    final int[] ctuSubpic;

    /**
     * Deblocking edge flags per 4x4 block: bits 0 to 3 the vertical edge at the block's left side (bit 0 transform
     * edge, bit 1 coding or prediction edge, bit 2 subblock motion edge), bits 4 to 7 the horizontal edge at its
     * top.
     */
    final byte[] edges;
    /**
     * Coded block flags per 4x4 block of the transform block covering it: bit 0 luma, bit 1 Cb, bit 2 Cr; bit 3 set
     * when the block's coding unit is intra, bit 4 when it uses BDPCM for luma, bit 5 for chroma.
     */
    final byte[] blockInfo;
    /**
     * {@code QpY} per 4x4 block.
     */
    final byte[] qp4;
    /**
     * The width and height of the luma transform block covering each 4x4 block, in luma samples, and of the chroma
     * transform block, in chroma samples; the deblocking filter lengths depend on them.
     */
    final byte[] tbLumaW;
    /**
     * The height of the luma transform block covering each 4x4 block.
     */
    final byte[] tbLumaH;
    /**
     * The width of the chroma transform block covering each 4x4 block, in chroma samples.
     */
    final byte[] tbChromaW;
    /**
     * The height of the chroma transform block covering each 4x4 block, in chroma samples.
     */
    final byte[] tbChromaH;
    /**
     * The chroma QP of Cb, Cr and joint Cb-Cr per 4x4 luma block ({@code Qp'Cb - QpBdOffset} and so on), read by the
     * chroma deblocking filter.
     */
    final byte[] qpCb4;
    /**
     * The chroma QP of Cr per 4x4 luma block, read by the chroma deblocking filter.
     */
    final byte[] qpCr4;
    /**
     * The prediction (sub)block size for the deblocking filter length decision of inter coded blocks.
     */
    final byte[] predInfo;
    /**
     * The intra block copy vector of each 4x4 block of an IBC coding unit, in 1/16 luma samples.
     */
    final int[] bvx;
    /**
     * The vertical intra block copy vector of each 4x4 block of an IBC coding unit, in 1/16 luma samples.
     */
    final int[] bvy;
    /**
     * The spatial motion field of the picture per 4x4 block: the vectors of both lists before the decoder side
     * refinement and their reference indices (-1 when the list is unused). Merge and AMVP derivations and the
     * deblocking filter read it.
     */
    final int[] smvx0;
    /**
     * The vertical list 0 vector of the spatial motion field per 4x4 block.
     */
    final int[] smvy0;
    /**
     * The horizontal list 1 vector of the spatial motion field per 4x4 block.
     */
    final int[] smvx1;
    /**
     * The vertical list 1 vector of the spatial motion field per 4x4 block.
     */
    final int[] smvy1;
    /**
     * The list 0 reference index of the spatial motion field per 4x4 block, -1 when unused.
     */
    final byte[] sref0;
    /**
     * The list 1 reference index of the spatial motion field per 4x4 block, -1 when unused.
     */
    final byte[] sref1;

    /**
     * SAO parameters per CTU and component: type (0 off, 1 band, 2 edge), band position or edge class, four offsets.
     */
    final int[][] saoType;
    /**
     * The SAO band position or edge class per CTU and component.
     */
    final int[][] saoBand;
    /**
     * The four SAO offsets per CTU and component, scaled to the bit depth.
     */
    final int[][][] saoOffset;
    /**
     * ALF parameters per CTU: enable flags per component, luma filter set index, chroma alternative per chroma
     * component, CC-ALF filter index (0 off) per chroma component.
     */
    final boolean[][] alfEnabled;
    /**
     * The luma filter set index per CTU: 0 to 15 for the fixed sets, 16 and above for the adaptation parameter sets.
     */
    final int[] alfFilterSet;
    /**
     * The chroma filter alternative per CTU and chroma component.
     */
    final int[][] alfChromaAlt;
    /**
     * The cross-component filter index per CTU and chroma component, 0 when off.
     */
    final int[][] ccAlfIdx;
    /**
     * Whether LMCS, deblocking and the in-loop filters are enabled for each CTU, from its slice.
     */
    final VvcSliceHeader[] ctuHeader;

    /**
     * Allocates the maps of a picture size.
     *
     * @param width the coded width in luma samples
     * @param height the coded height in luma samples
     * @param log2Ctu {@code CtbLog2SizeY}
     */
    VvcFrame(int width, int height, int log2Ctu) {
        this.width = width;
        this.height = height;
        this.log2Ctu = log2Ctu;
        this.ctuSize = 1 << log2Ctu;
        this.widthInCtus = (width + ctuSize - 1) >> log2Ctu;
        this.heightInCtus = (height + ctuSize - 1) >> log2Ctu;
        this.width4 = (width + 3) >> 2;
        this.height4 = (height + 3) >> 2;
        var n4 = width4 * height4;
        cuLuma = new int[n4];
        cuChroma = new int[n4];
        decY = new boolean[n4];
        decC = new boolean[n4];
        edges = new byte[n4];
        blockInfo = new byte[n4];
        qp4 = new byte[n4];
        tbLumaW = new byte[n4];
        tbLumaH = new byte[n4];
        tbChromaW = new byte[n4];
        tbChromaH = new byte[n4];
        qpCb4 = new byte[n4];
        qpCr4 = new byte[n4];
        predInfo = new byte[n4];
        bvx = new int[n4];
        bvy = new int[n4];
        smvx0 = new int[n4];
        smvy0 = new int[n4];
        smvx1 = new int[n4];
        smvy1 = new int[n4];
        sref0 = new byte[n4];
        sref1 = new byte[n4];
        var ctus = widthInCtus * heightInCtus;
        ctuSlice = new int[ctus];
        ctuTile = new int[ctus];
        ctuSubpic = new int[ctus];
        saoType = new int[ctus][3];
        saoBand = new int[ctus][3];
        saoOffset = new int[ctus][3][4];
        alfEnabled = new boolean[ctus][3];
        alfFilterSet = new int[ctus];
        alfChromaAlt = new int[ctus][2];
        ccAlfIdx = new int[ctus][2];
        ctuHeader = new VvcSliceHeader[ctus];
    }

    /**
     * Prepares the state for a new picture.
     *
     * @param pic the picture to decode
     * @param sps its sequence parameter set
     * @param pps its picture parameter set
     * @param ph its picture header
     */
    void begin(VvcPicture pic, VvcSps sps, VvcPps pps, VvcPictureHeader ph) {
        this.pic = pic;
        this.sps = sps;
        this.pps = pps;
        this.ph = ph;
        for (var pool : pools) {
            if (pool != null) {
                pool.size = 0;
            }
        }
        Arrays.fill(cuLuma, 0);
        Arrays.fill(cuChroma, 0);
        Arrays.fill(decY, false);
        Arrays.fill(decC, false);
        wpp = sps.entropyCodingSync;
        Arrays.fill(ctuSlice, -1);
        Arrays.fill(edges, (byte) 0);
        Arrays.fill(blockInfo, (byte) 0);
        Arrays.fill(ctuHeader, null);
        for (var y = 0; y < heightInCtus; y++) {
            for (var x = 0; x < widthInCtus; x++) {
                ctuTile[y * widthInCtus + x] = pps.ctuToTileRow[y] * pps.numTileColumns + pps.ctuToTileCol[x];
            }
        }
        Arrays.fill(ctuSubpic, 0);
        for (var s = 1; s < sps.numSubpics; s++) {
            var y1 = Math.min(heightInCtus, sps.subpicCtuY[s] + sps.subpicHeight[s]);
            var x1 = Math.min(widthInCtus, sps.subpicCtuX[s] + sps.subpicWidth[s]);
            for (var y = sps.subpicCtuY[s]; y < y1; y++) {
                Arrays.fill(ctuSubpic, y * widthInCtus + sps.subpicCtuX[s], y * widthInCtus + x1, s);
            }
        }
    }

    /**
     * Allocates a coding unit from the pool of a lane and registers it in the maps of its tree.
     *
     * @param lane the lane, from 0 to {@link #MAX_LANES} minus one
     * @param x the luma column
     * @param y the luma row
     * @param w the luma width
     * @param h the luma height
     * @param treeType the tree type: single, dual luma or dual chroma
     * @param slice the slice index
     * @return the coding unit
     */
    VvcCu newCu(int lane, int x, int y, int w, int h, int treeType, int slice) {
        var pool = pools[lane];
        if (pool == null) {
            pool = new CuPool();
            pools[lane] = pool;
        }
        var units = pool.units;
        if (pool.size == units.length) {
            units = Arrays.copyOf(units, units.length * 2);
            pool.units = units;
        }
        var cu = units[pool.size];
        if (cu == null) {
            cu = new VvcCu();
            units[pool.size] = cu;
        }
        pool.size++;
        if (pool.size >= 1 << LANE_SHIFT) {
            throw new MediaException.Malformed("vvc picture holds too many coding units");
        }
        cu.reset(x, y, w, h, treeType);
        cu.slice = slice;
        var index = (lane << LANE_SHIFT) | pool.size;
        var x0 = x >> 2;
        var y0 = y >> 2;
        var x1 = Math.min(width4, (x + w) >> 2);
        var y1 = Math.min(height4, (y + h) >> 2);
        for (var yy = y0; yy < y1; yy++) {
            var row = yy * width4;
            if (treeType != VvcCu.TREE_CHROMA) {
                Arrays.fill(cuLuma, row + x0, row + x1, index);
            }
            if (treeType != VvcCu.TREE_LUMA) {
                Arrays.fill(cuChroma, row + x0, row + x1, index);
            }
        }
        return cu;
    }

    /**
     * Returns the coding unit covering a luma position, or {@code null} when none has been decoded there yet.
     *
     * @param x the luma column, inside the picture
     * @param y the luma row, inside the picture
     * @param chroma whether to look in the chroma tree
     * @return the coding unit, or {@code null}
     */
    VvcCu cuAt(int x, int y, boolean chroma) {
        var index = (chroma ? cuChroma : cuLuma)[(y >> 2) * width4 + (x >> 2)];
        if (index == 0) {
            return null;
        }
        var pool = pools[index >>> LANE_SHIFT];
        var local = (index & ((1 << LANE_SHIFT) - 1)) - 1;
        var units = pool == null ? null : pool.units;
        return units == null || local >= units.length ? null : units[local];
    }

    /**
     * Returns the coding unit at a neighbor position when it is available for the current block (6.4.4): inside the
     * picture, already decoded and in the same slice and tile.
     *
     * @param x the neighbor luma column
     * @param y the neighbor luma row
     * @param curX the current block's luma column
     * @param curY the current block's luma row
     * @param chroma whether to look in the chroma tree
     * @return the coding unit, or {@code null} when it is not available
     */
    VvcCu available(int x, int y, int curX, int curY, boolean chroma) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return null;
        }
        var cu = cuAt(x, y, chroma);
        if (cu == null) {
            return null;
        }
        var ctu = (y >> log2Ctu) * widthInCtus + (x >> log2Ctu);
        var cur = (curY >> log2Ctu) * widthInCtus + (curX >> log2Ctu);
        if (ctuSlice[ctu] != ctuSlice[cur] || ctuTile[ctu] != ctuTile[cur]) {
            return null;
        }
        return cu;
    }

    /**
     * {@return whether the CTU containing a neighbor position is available for the CTU containing the current one}
     *
     * @param ctu the CTU of the neighbor
     * @param cur the current CTU
     */
    boolean ctuAvailable(int ctu, int cur) {
        return ctu >= 0 && ctuSlice[ctu] >= 0 && ctuSlice[ctu] == ctuSlice[cur] && ctuTile[ctu] == ctuTile[cur];
    }

    /**
     * Derives the availability of a neighboring sample for the current block (6.4.4, {@code checkPredModeY} false).
     *
     * @param xNb the neighbor luma column
     * @param yNb the neighbor luma row
     * @param xCur the current block's luma column
     * @param yCur the current block's luma row
     * @param chroma whether to check the chroma reconstruction
     * @return whether the neighboring sample is available
     */
    boolean sampleAvailable(int xNb, int yNb, int xCur, int yCur, boolean chroma) {
        if (xNb < 0 || yNb < 0 || xNb >= width || yNb >= height) {
            return false;
        }
        var nx = xNb >> log2Ctu;
        var ny = yNb >> log2Ctu;
        var cx = xCur >> log2Ctu;
        var cy = yCur >> log2Ctu;
        if ((nx > cx && ny >= cy) || ny >= cy + 1 || (wpp && nx >= cx + 1)) {
            return false;
        }
        var unit = (yNb >> 2) * width4 + (xNb >> 2);
        if (!(chroma ? decC : decY)[unit]) {
            return false;
        }
        var nCtu = ny * widthInCtus + nx;
        var cCtu = cy * widthInCtus + cx;
        return ctuSlice[nCtu] == ctuSlice[cCtu] && ctuTile[nCtu] == ctuTile[cCtu];
    }

    /**
     * Marks a luma-coordinate rectangle as reconstructed for luma or chroma.
     *
     * @param x the luma column
     * @param y the luma row
     * @param w the luma width
     * @param h the luma height
     * @param chroma whether the chroma tree is marked rather than the luma tree
     */
    void markDecoded(int x, int y, int w, int h, boolean chroma) {
        var map = chroma ? decC : decY;
        var x0 = x >> 2;
        var y0 = y >> 2;
        var x1 = Math.min(width4, (x + w + 3) >> 2);
        var y1 = Math.min(height4, (y + h + 3) >> 2);
        for (var yy = y0; yy < y1; yy++) {
            Arrays.fill(map, yy * width4 + x0, yy * width4 + x1, true);
        }
    }
}
