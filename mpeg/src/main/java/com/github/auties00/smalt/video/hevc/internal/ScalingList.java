package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds the scaling factors of one {@code scaling_list_data()} structure, or of the default or
 * flat lists (ITU-T H.265 7.3.4 and 7.4.5).
 *
 * <p>{@link #factors(int, int)} returns {@code ScalingFactor[sizeId][matrixId]} as a raster array
 * of {@code (4 << sizeId)^2} entries indexed {@code y * size + x}. For 32x32 chroma blocks (which
 * only 4:4:4 video has) the 16x16 list is upsampled as the specification requires.
 */
public final class ScalingList {
    /**
     * The default 8x8 intra list of Table 7-6 in up-right diagonal scan order.
     */
    private static final int[] DEFAULT_INTRA = {
            16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 17, 16, 17, 16, 17, 18,
            17, 18, 18, 17, 18, 21, 19, 20, 21, 20, 19, 21, 24, 22, 22, 24,
            24, 22, 22, 24, 25, 25, 27, 30, 27, 25, 25, 29, 31, 35, 35, 31,
            29, 36, 41, 44, 41, 36, 47, 54, 54, 47, 65, 70, 65, 88, 88, 115
    };

    /**
     * The default 8x8 inter list of Table 7-6 in up-right diagonal scan order.
     */
    private static final int[] DEFAULT_INTER = {
            16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 17, 17, 17, 17, 17, 18,
            18, 18, 18, 18, 18, 20, 20, 20, 20, 20, 20, 20, 24, 24, 24, 24,
            24, 24, 24, 24, 25, 25, 25, 25, 25, 25, 25, 28, 28, 28, 28, 28,
            28, 33, 33, 33, 33, 33, 41, 41, 41, 41, 54, 54, 54, 71, 71, 91
    };

    /**
     * The scaling lists in coded (diagonal scan) order, indexed {@code [sizeId][matrixId]}; 16
     * entries for size 0 and 64 otherwise.
     */
    private final int[][][] lists = new int[4][6][];

    /**
     * The DC values of the 16x16 and 32x32 lists, indexed {@code [sizeId - 2][matrixId]}.
     */
    private final int[][] dc = new int[2][6];

    /**
     * The derived scaling factors, indexed {@code [sizeId][matrixId]}.
     */
    private final int[][][] factors = new int[4][6][];

    /**
     * Constructs the default lists of Tables 7-5 and 7-6.
     */
    private ScalingList() {
        for (var sizeId = 0; sizeId < 4; sizeId++) {
            for (var matrixId = 0; matrixId < 6; matrixId++) {
                lists[sizeId][matrixId] = defaultList(sizeId, matrixId);
            }
        }
        for (var i = 0; i < 2; i++) {
            java.util.Arrays.fill(dc[i], 16);
        }
    }

    /**
     * Returns the default scaling lists of Tables 7-5 and 7-6, used when
     * {@code scaling_list_enabled_flag} is set without lists in the SPS or PPS.
     *
     * @return the default lists
     */
    public static ScalingList defaults() {
        var list = new ScalingList();
        list.derive();
        return list;
    }

    /**
     * Returns the default list of a size and matrix.
     *
     * @param sizeId   the size index, 0 to 3
     * @param matrixId the matrix index, 0 to 5
     * @return a fresh copy of the default list in diagonal scan order
     */
    private static int[] defaultList(int sizeId, int matrixId) {
        if (sizeId == 0) {
            var flat = new int[16];
            java.util.Arrays.fill(flat, 16);
            return flat;
        }
        return (matrixId < 3 ? DEFAULT_INTRA : DEFAULT_INTER).clone();
    }

    /**
     * Parses a {@code scaling_list_data()} structure (ITU-T H.265 7.3.4).
     *
     * @param reader the reader positioned at the structure
     * @return the parsed lists with their derived factors
     * @throws MediaException.Malformed if a prediction reference or a coefficient is invalid
     */
    public static ScalingList parse(H264BitReader reader) {
        var list = new ScalingList();
        for (var sizeId = 0; sizeId < 4; sizeId++) {
            var step = sizeId == 3 ? 3 : 1;
            var coefficients = Math.min(64, 1 << (4 + (sizeId << 1)));
            for (var matrixId = 0; matrixId < 6; matrixId += step) {
                if (!reader.readFlag()) {
                    var delta = reader.readUe(matrixId / step, "scaling_list_pred_matrix_id_delta");
                    if (delta == 0) {
                        list.lists[sizeId][matrixId] = defaultList(sizeId, matrixId);
                        if (sizeId > 1) {
                            list.dc[sizeId - 2][matrixId] = 16;
                        }
                    } else {
                        var reference = matrixId - delta * step;
                        list.lists[sizeId][matrixId] = list.lists[sizeId][reference].clone();
                        if (sizeId > 1) {
                            list.dc[sizeId - 2][matrixId] = list.dc[sizeId - 2][reference];
                        }
                    }
                } else {
                    var next = 8;
                    if (sizeId > 1) {
                        var dcValue = reader.readSe(-7, 247, "scaling_list_dc_coef_minus8") + 8;
                        list.dc[sizeId - 2][matrixId] = dcValue;
                        next = dcValue;
                    }
                    var values = new int[coefficients];
                    for (var i = 0; i < coefficients; i++) {
                        var delta = reader.readSe(-128, 127, "scaling_list_delta_coef");
                        next = (next + delta + 256) % 256;
                        if (next == 0) {
                            throw new MediaException.Malformed("zero scaling list coefficient");
                        }
                        values[i] = next;
                    }
                    list.lists[sizeId][matrixId] = values;
                }
            }
        }
        for (var matrixId = 1; matrixId < 6; matrixId++) {
            if (matrixId != 3) {
                list.lists[3][matrixId] = list.lists[2][matrixId].clone();
                list.dc[1][matrixId] = list.dc[0][matrixId];
            }
        }
        list.derive();
        return list;
    }

    /**
     * Derives {@code ScalingFactor} from the lists (equations 7-44 to 7-49 and the 4:4:4 chroma
     * 32x32 rule).
     */
    private void derive() {
        var scan4 = ScanTables.DIAGONAL[2];
        var scan8 = ScanTables.DIAGONAL[3];
        for (var matrixId = 0; matrixId < 6; matrixId++) {
            var f0 = new int[16];
            var l0 = lists[0][matrixId];
            for (var i = 0; i < 16; i++) {
                var pos = scan4[i];
                f0[(pos >> 8) * 4 + (pos & 0xFF)] = l0[i];
            }
            factors[0][matrixId] = f0;
            for (var sizeId = 1; sizeId < 4; sizeId++) {
                var size = 4 << sizeId;
                var ratio = size / 8;
                var factor = new int[size * size];
                var list = lists[sizeId][matrixId];
                for (var i = 0; i < 64; i++) {
                    var pos = scan8[i];
                    var x = pos & 0xFF;
                    var y = pos >> 8;
                    for (var j = 0; j < ratio; j++) {
                        for (var k = 0; k < ratio; k++) {
                            factor[(y * ratio + j) * size + x * ratio + k] = list[i];
                        }
                    }
                }
                if (sizeId > 1) {
                    factor[0] = dc[sizeId - 2][matrixId];
                }
                factors[sizeId][matrixId] = factor;
            }
        }
    }

    /**
     * Returns the scaling factors of a block size and matrix.
     *
     * @param sizeId   the size index, {@code log2(size) - 2}
     * @param matrixId the matrix index of Table 7-4: {@code cIdx} for intra and {@code 3 + cIdx}
     *                 for inter blocks
     * @return the raster factors, {@code (4 << sizeId)^2} entries indexed {@code y * size + x}
     */
    public int[] factors(int sizeId, int matrixId) {
        return factors[sizeId][matrixId];
    }
}
