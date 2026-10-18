package com.github.auties00.smalt.video.av1.internal;

/**
 * Builds the wedge and inter-intra blending masks of AV1 compound prediction (AV1 specification
 * sections 7.11.3.11 to 7.11.3.13).
 *
 * <p>Every mask lives in the shared array {@link #MASKS}; {@link #wedgeOffset} and
 * {@link #iiOffset} give the start of the mask of a plane subsampling, block size, sign and wedge
 * index or inter-intra mode. Masks hold weights from 0 to 64 for the first prediction, one byte per
 * sample, rows packed at the mask width.
 *
 * @implNote This implementation ports dav1d's {@code src/wedge.c}: the master templates, the three
 *           wedge codebooks and the per-size, per-subsampling mask derivation.
 */
public final class Wedge {
    /**
     * The horizontal wedge direction.
     */
    private static final int HORIZONTAL = 0;

    /**
     * The vertical wedge direction.
     */
    private static final int VERTICAL = 1;

    /**
     * The 27-degree wedge direction.
     */
    private static final int OBLIQUE27 = 2;

    /**
     * The 63-degree wedge direction.
     */
    private static final int OBLIQUE63 = 3;

    /**
     * The 117-degree wedge direction.
     */
    private static final int OBLIQUE117 = 4;

    /**
     * The 153-degree wedge direction.
     */
    private static final int OBLIQUE153 = 5;

    /**
     * Holds the wedge codebook for blocks taller than wide, as {@code (direction, x, y)} triples
     * ({@code Wedge_Codebook[1]}).
     */
    private static final int[] CODEBOOK_HGTW = {
            OBLIQUE27, 4, 4, OBLIQUE63, 4, 4, OBLIQUE117, 4, 4, OBLIQUE153, 4, 4,
            HORIZONTAL, 4, 2, HORIZONTAL, 4, 4, HORIZONTAL, 4, 6, VERTICAL, 4, 4,
            OBLIQUE27, 4, 2, OBLIQUE27, 4, 6, OBLIQUE153, 4, 2, OBLIQUE153, 4, 6,
            OBLIQUE63, 2, 4, OBLIQUE63, 6, 4, OBLIQUE117, 2, 4, OBLIQUE117, 6, 4,
    };

    /**
     * Holds the wedge codebook for blocks wider than tall ({@code Wedge_Codebook[2]}).
     */
    private static final int[] CODEBOOK_HLTW = {
            OBLIQUE27, 4, 4, OBLIQUE63, 4, 4, OBLIQUE117, 4, 4, OBLIQUE153, 4, 4,
            VERTICAL, 2, 4, VERTICAL, 4, 4, VERTICAL, 6, 4, HORIZONTAL, 4, 4,
            OBLIQUE27, 4, 2, OBLIQUE27, 4, 6, OBLIQUE153, 4, 2, OBLIQUE153, 4, 6,
            OBLIQUE63, 2, 4, OBLIQUE63, 6, 4, OBLIQUE117, 2, 4, OBLIQUE117, 6, 4,
    };

    /**
     * Holds the wedge codebook for square blocks ({@code Wedge_Codebook[0]}).
     */
    private static final int[] CODEBOOK_HEQW = {
            OBLIQUE27, 4, 4, OBLIQUE63, 4, 4, OBLIQUE117, 4, 4, OBLIQUE153, 4, 4,
            HORIZONTAL, 4, 2, HORIZONTAL, 4, 6, VERTICAL, 2, 4, VERTICAL, 6, 4,
            OBLIQUE27, 4, 2, OBLIQUE27, 4, 6, OBLIQUE153, 4, 2, OBLIQUE153, 4, 6,
            OBLIQUE63, 2, 4, OBLIQUE63, 6, 4, OBLIQUE117, 2, 4, OBLIQUE117, 6, 4,
    };

    /**
     * Holds the master template border rows (odd, even and vertical lines).
     */
    private static final int[][] MASTER_BORDER = {
            {1, 2, 6, 18, 37, 53, 60, 63},
            {1, 4, 11, 27, 46, 58, 62, 63},
            {0, 2, 7, 21, 43, 57, 62, 64},
    };

    /**
     * Holds the inter-intra smooth weights ({@code Ii_Weights_1d}).
     */
    private static final int[] II_WEIGHTS_1D = {
            60, 52, 45, 39, 34, 30, 26, 22, 19, 17, 15, 13, 11, 10, 8, 7,
            6, 6, 5, 4, 4, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1,
    };

    /**
     * Holds every mask.
     */
    public static final byte[] MASKS = new byte[150_000];

    /**
     * Holds the offset of each wedge mask, as {@code [444/422/420 3][BlockSize - BS_32x32 11][sign 2][index 16]}.
     */
    private static final int[] WEDGE_OFFSETS = new int[3 * 11 * 2 * 16];

    /**
     * Holds the offset of each inter-intra mask, as {@code [444/422/420 3][BlockSize - BS_32x32 11][mode 4]}.
     */
    private static final int[] II_OFFSETS = new int[3 * 11 * 4];

    /**
     * The next free offset of {@link #MASKS} while the masks are built.
     */
    private static int next;

    static {
        build();
    }

    /**
     * Prevents instantiation.
     */
    private Wedge() {
        throw new AssertionError();
    }

    /**
     * Returns the offset of a wedge mask in {@link #MASKS}.
     *
     * @param c    the chroma subsampling: 0 for 4:4:4 (and luma), 1 for 4:2:2, 2 for 4:2:0
     * @param bs   the block size ({@code BlockSize}), from {@code BS_32x32} (7) to {@code BS_8x8} (17)
     * @param sign the mask sign
     * @param idx  the wedge index
     * @return the offset
     */
    public static int wedgeOffset(int c, int bs, int sign, int idx) {
        return WEDGE_OFFSETS[((c * 11 + bs - 7) * 2 + sign) * 16 + idx];
    }

    /**
     * Returns the offset of an inter-intra blending mask in {@link #MASKS}.
     *
     * @param c    the chroma subsampling: 0 for 4:4:4 (and luma), 1 for 4:2:2, 2 for 4:2:0
     * @param bs   the block size ({@code BlockSize}), from {@code BS_32x32} (7) to {@code BS_8x8} (17)
     * @param mode the inter-intra mode
     * @return the offset
     */
    public static int iiOffset(int c, int bs, int mode) {
        return II_OFFSETS[(c * 11 + bs - 7) * 4 + mode];
    }

    /**
     * Reserves a region of {@link #MASKS}.
     *
     * @param size the region size
     * @return its offset
     */
    private static int alloc(int size) {
        var off = next;
        next += size;
        return off;
    }

    /**
     * Writes one row of a master template: zeros, the border ramp centered on {@code ctr}, then 64s.
     *
     * @param dst    the template
     * @param dstOff the row offset
     * @param border the border ramp
     * @param ctr    the ramp center
     */
    private static void insertBorder(byte[] dst, int dstOff, int[] border, int ctr) {
        if (ctr > 4) {
            for (var i = 0; i < ctr - 4; i++) {
                dst[dstOff + i] = 0;
            }
        }
        var start = Math.max(ctr, 4) - 4;
        var srcStart = Math.max(4 - ctr, 0);
        var n = Math.min(64 - ctr, 8);
        for (var i = 0; i < n; i++) {
            dst[dstOff + start + i] = (byte) border[srcStart + i];
        }
        if (ctr < 64 - 4) {
            for (var i = ctr + 4; i < 64; i++) {
                dst[dstOff + i] = 64;
            }
        }
    }

    /**
     * Transposes a 64x64 template.
     *
     * @param dst the destination
     * @param src the source
     */
    private static void transpose(byte[] dst, byte[] src) {
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 64; x++) {
                dst[x * 64 + y] = src[y * 64 + x];
            }
        }
    }

    /**
     * Mirrors a 64x64 template horizontally.
     *
     * @param dst the destination
     * @param src the source
     */
    private static void hflip(byte[] dst, byte[] src) {
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 64; x++) {
                dst[y * 64 + 63 - x] = src[y * 64 + x];
            }
        }
    }

    /**
     * Copies a window of a template, optionally inverted.
     *
     * @param dstOff the destination offset in {@link #MASKS}
     * @param src    the template
     * @param sign   whether to invert the weights
     * @param w      the window width
     * @param h      the window height
     * @param xOff   the window column
     * @param yOff   the window row
     */
    private static void copy2d(int dstOff, byte[] src, int sign, int w, int h, int xOff, int yOff) {
        var s = yOff * 64 + xOff;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                MASKS[dstOff + x] = sign != 0 ? (byte) (64 - src[s + x]) : src[s + x];
            }
            s += 64;
            dstOff += w;
        }
    }

    /**
     * Derives a subsampled chroma mask from a luma mask.
     *
     * @param chroma the chroma mask offset
     * @param luma   the luma mask offset
     * @param sign   the rounding bias to subtract
     * @param w      the luma width
     * @param h      the luma height
     * @param ssVer  whether the chroma is vertically subsampled
     * @return the chroma mask offset
     */
    private static int initChroma(int chroma, int luma, int sign, int w, int h, int ssVer) {
        var offset = chroma;
        for (var y = 0; y < h; y += 1 + ssVer) {
            for (var x = 0; x < w; x += 2) {
                var sum = MASKS[luma + x] + MASKS[luma + x + 1] + 1;
                if (ssVer != 0) {
                    sum += MASKS[luma + w + x] + MASKS[luma + w + x + 1] + 1;
                }
                MASKS[chroma + (x >> 1)] = (byte) ((sum - sign) >> (1 + ssVer));
            }
            luma += w << ssVer;
            chroma += w >> 1;
        }
        return offset;
    }

    /**
     * Builds the 16 wedge masks of a block size for every subsampling.
     *
     * @param w      the block width
     * @param h      the block height
     * @param bs     the block size index minus {@code BS_32x32}
     * @param master the master templates
     * @param cb     the codebook
     * @param signs  the sign bit of each wedge
     */
    private static void fill(int w, int h, int bs, byte[][] master, int[] cb, int signs) {
        var nStride444 = w * h;
        var nStride422 = nStride444 >> 1;
        var nStride420 = nStride444 >> 2;
        var signStride422 = 16 * nStride422;
        var signStride420 = 16 * nStride420;
        var masks444 = alloc(16 * nStride444);
        var masks422 = alloc(2 * 16 * nStride422);
        var masks420 = alloc(2 * 16 * nStride420);
        for (var n = 0; n < 16; n++) {
            var sign = signs & 1;
            copy2d(masks444, master[cb[n * 3]], sign, w, h,
                    32 - (w * cb[n * 3 + 1] >> 3), 32 - (h * cb[n * 3 + 2] >> 3));
            WEDGE_OFFSETS[(bs * 2) * 16 + n] = masks444;
            WEDGE_OFFSETS[(bs * 2 + 1) * 16 + n] = masks444;
            WEDGE_OFFSETS[((11 + bs) * 2) * 16 + n] =
                    initChroma(masks422 + sign * signStride422, masks444, 0, w, h, 0);
            WEDGE_OFFSETS[((11 + bs) * 2 + 1) * 16 + n] =
                    initChroma(masks422 + (sign ^ 1) * signStride422, masks444, 1, w, h, 0);
            WEDGE_OFFSETS[((22 + bs) * 2) * 16 + n] =
                    initChroma(masks420 + sign * signStride420, masks444, 0, w, h, 1);
            WEDGE_OFFSETS[((22 + bs) * 2 + 1) * 16 + n] =
                    initChroma(masks420 + (sign ^ 1) * signStride420, masks444, 1, w, h, 1);
            signs >>>= 1;
            masks444 += nStride444;
            masks422 += nStride422;
            masks420 += nStride420;
        }
    }

    /**
     * Builds the vertical, horizontal and smooth inter-intra masks of one size.
     *
     * @param w    the mask width
     * @param h    the mask height
     * @param step the weight index step
     * @return the offset of the vertical mask, followed by the horizontal and smooth ones
     */
    private static int buildNondcIiMasks(int w, int h, int step) {
        var maskV = alloc(3 * w * h);
        var maskH = maskV + w * h;
        var maskSm = maskH + w * h;
        for (int y = 0, off = 0; y < h; y++, off += w) {
            for (var x = 0; x < w; x++) {
                MASKS[maskV + off + x] = (byte) II_WEIGHTS_1D[y * step];
                MASKS[maskSm + off + x] = (byte) II_WEIGHTS_1D[Math.min(x, y) * step];
                MASKS[maskH + off + x] = (byte) II_WEIGHTS_1D[x * step];
            }
        }
        return maskV;
    }

    /**
     * Assigns the inter-intra mask offsets of one block size.
     *
     * @param bs      the block size ({@code BlockSize})
     * @param o444    the offset of the 4:4:4 masks
     * @param size444 the size of one 4:4:4 mask
     * @param o422    the offset of the 4:2:2 masks
     * @param size422 the size of one 4:2:2 mask
     * @param o420    the offset of the 4:2:0 masks
     * @param size420 the size of one 4:2:0 mask
     */
    private static void assignIi(int bs, int o444, int size444, int o422, int size422, int o420, int size420) {
        var b = bs - 7;
        for (var p = 0; p < 3; p++) {
            II_OFFSETS[(b) * 4 + p + 1] = o444 + p * size444;
            II_OFFSETS[(11 + b) * 4 + p + 1] = o422 + p * size422;
            II_OFFSETS[(22 + b) * 4 + p + 1] = o420 + p * size420;
        }
    }

    /**
     * Builds every mask.
     */
    private static void build() {
        var master = new byte[6][64 * 64];
        for (var y = 0; y < 64; y++) {
            insertBorder(master[VERTICAL], y * 64, MASTER_BORDER[2], 32);
        }
        for (int y = 0, off = 0, ctr = 48; y < 64; y += 2, off += 128, ctr--) {
            insertBorder(master[OBLIQUE63], off, MASTER_BORDER[1], ctr);
            insertBorder(master[OBLIQUE63], off + 64, MASTER_BORDER[0], ctr - 1);
        }
        transpose(master[OBLIQUE27], master[OBLIQUE63]);
        transpose(master[HORIZONTAL], master[VERTICAL]);
        hflip(master[OBLIQUE117], master[OBLIQUE63]);
        hflip(master[OBLIQUE153], master[OBLIQUE27]);
        fill(32, 32, 0, master, CODEBOOK_HEQW, 0x7bfb);
        fill(32, 16, 1, master, CODEBOOK_HLTW, 0x7beb);
        fill(32, 8, 2, master, CODEBOOK_HLTW, 0x6beb);
        fill(16, 32, 4, master, CODEBOOK_HGTW, 0x7beb);
        fill(16, 16, 5, master, CODEBOOK_HEQW, 0x7bfb);
        fill(16, 8, 6, master, CODEBOOK_HLTW, 0x7beb);
        fill(8, 32, 8, master, CODEBOOK_HGTW, 0x7aeb);
        fill(8, 16, 9, master, CODEBOOK_HGTW, 0x7beb);
        fill(8, 8, 10, master, CODEBOOK_HEQW, 0x7bfb);
        var iiDc = alloc(32 * 32);
        for (var i = 0; i < 32 * 32; i++) {
            MASKS[iiDc + i] = 32;
        }
        int[] dcSizes = {7, 8, 11, 12, 13, 16, 17};
        for (var c = 0; c < 3; c++) {
            for (var bs : dcSizes) {
                II_OFFSETS[(c * 11 + bs - 7) * 4] = iiDc;
            }
        }
        var ii32x32 = buildNondcIiMasks(32, 32, 1);
        var ii16x32 = buildNondcIiMasks(16, 32, 1);
        var ii16x16 = buildNondcIiMasks(16, 16, 2);
        var ii8x32 = buildNondcIiMasks(8, 32, 1);
        var ii8x16 = buildNondcIiMasks(8, 16, 2);
        var ii8x8 = buildNondcIiMasks(8, 8, 4);
        var ii4x16 = buildNondcIiMasks(4, 16, 2);
        var ii4x8 = buildNondcIiMasks(4, 8, 4);
        var ii4x4 = buildNondcIiMasks(4, 4, 8);
        assignIi(7, ii32x32, 32 * 32, ii16x32, 16 * 32, ii16x16, 16 * 16);
        assignIi(8, ii32x32, 32 * 32, ii16x16, 16 * 16, ii16x16, 16 * 16);
        assignIi(11, ii16x32, 16 * 32, ii8x32, 8 * 32, ii8x16, 8 * 16);
        assignIi(12, ii16x16, 16 * 16, ii8x16, 8 * 16, ii8x8, 8 * 8);
        assignIi(13, ii16x16, 16 * 16, ii8x8, 8 * 8, ii8x8, 8 * 8);
        assignIi(16, ii8x16, 8 * 16, ii4x16, 4 * 16, ii4x8, 4 * 8);
        assignIi(17, ii8x8, 8 * 8, ii4x8, 4 * 8, ii4x4, 4 * 4);
    }
}
