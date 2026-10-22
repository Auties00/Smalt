/* Ports HM-16.21+SCM-8.8 (source/Lib/TLibDecoder/TDecSbac.cpp, parsePaletteModeSyntax and its run and index
   binarizations; source/Lib/TLibDecoder/TDecCu.cpp, xDecodePaletteTexture). Copyright (c) 2010-2017, ITU/ISO/IEC;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Parses and reconstructs the coding units coded in palette mode (ITU-T H.265 7.3.8.13 and
 * 8.4.4.2.7), and keeps the palette predictor of the slice segment being decoded.
 *
 * <p>The palette index map is kept in raster order of the coding block before the transpose of
 * {@code palette_transpose_flag}, which applies only when the samples are reconstructed. The escape
 * values are kept at the positions of the map that carry them.
 *
 * <p>Instances are reused across coding units and slice segments and are not thread-safe.
 */
final class PaletteCoding {
    /**
     * The largest {@code palette_max_size}.
     */
    static final int MAX_SIZE = 64;

    /**
     * The largest {@code PaletteMaxPredictorSize}.
     */
    static final int MAX_PREDICTOR = 128;

    /**
     * The largest palette coding block side, {@code MaxTbSizeY}.
     */
    private static final int MAX_BLOCK = 32;

    /**
     * {@code levelScale} of equation 8-77.
     */
    private static final int[] LEVEL_SCALE = {40, 45, 51, 57, 64, 72};

    /**
     * The context increments of {@code palette_run_prefix} bins 1 to 4 after a run of copied
     * indices (Table 9-51), indexed by the bin.
     */
    private static final int[] COPY_RUN_CONTEXT = {5, 6, 6, 7, 7};

    /**
     * The context increments of {@code palette_run_prefix} bins 1 to 4 after a run of one index
     * (Table 9-51), indexed by the bin; bin 0 depends on {@code palette_idx_idc}.
     */
    private static final int[] INDEX_RUN_CONTEXT = {0, 3, 3, 4, 4};

    /**
     * {@code PredictorPaletteEntries}, {@link #MAX_PREDICTOR} entries per component.
     */
    private final int[] predictor = new int[3 * MAX_PREDICTOR];

    /**
     * {@code PredictorPaletteSize}.
     */
    private int predictorSize;

    /**
     * The predictor of the next coding unit while {@link #updatePredictor} builds it.
     */
    private final int[] nextPredictor = new int[3 * MAX_PREDICTOR];

    /**
     * {@code PalettePredictorEntryReuseFlags}.
     */
    private final boolean[] reuse = new boolean[MAX_PREDICTOR];

    /**
     * {@code CurrentPaletteEntries}, {@link #MAX_SIZE} entries per component.
     */
    private final int[] palette = new int[3 * MAX_SIZE];

    /**
     * {@code CurrentPaletteSize}.
     */
    private int size;

    /**
     * {@code palette_escape_val_present_flag}.
     */
    private boolean escape;

    /**
     * {@code MaxPaletteIndex}.
     */
    private int maxIndex;

    /**
     * {@code palette_transpose_flag}.
     */
    private boolean transpose;

    /**
     * {@code copy_above_indices_for_final_run_flag}.
     */
    private boolean finalRunCopiesAbove;

    /**
     * {@code num_palette_indices_minus1 + 1}, or 0 when {@code MaxPaletteIndex} is 0.
     */
    private int numIndices;

    /**
     * {@code PaletteIndexIdc}.
     */
    private final int[] indexIdc = new int[MAX_BLOCK * MAX_BLOCK];

    /**
     * {@code PaletteIndexMap} of the coding block, raster order.
     */
    private final int[] map = new int[MAX_BLOCK * MAX_BLOCK];

    /**
     * {@code CopyAboveIndicesFlag} of the coding block, raster order.
     */
    private final boolean[] copied = new boolean[MAX_BLOCK * MAX_BLOCK];

    /**
     * {@code PaletteEscapeVal} of each component, at the raster positions of the escaped samples.
     */
    private final int[][] escapes = new int[3][MAX_BLOCK * MAX_BLOCK];

    /**
     * The coding block side, {@code nCbS}.
     */
    private int blockSize;

    /**
     * The coding block side as a power of two.
     */
    private int log2BlockSize;

    /**
     * The number of color components coded, 1 or 3.
     */
    private int components;

    /**
     * Constructs a palette decoder with an empty predictor.
     */
    PaletteCoding() {
    }

    /**
     * Initializes the palette predictor for a slice segment, tile or wavefront row (9.3.2.3).
     *
     * @param sps the active sequence parameter set
     * @param pps the active picture parameter set
     */
    void initialize(HevcSps sps, HevcPps pps) {
        var count = sps.chromaArrayType == 0 ? 1 : 3;
        int[][] entries;
        if (pps.paletteInitializersPresent) {
            predictorSize = pps.numPaletteInitializers;
            entries = pps.paletteInitializers;
        } else if (sps.paletteInitializers != null) {
            predictorSize = sps.paletteInitializers[0].length;
            entries = sps.paletteInitializers;
        } else {
            predictorSize = 0;
            entries = null;
        }
        if (predictorSize == 0) {
            return;
        }
        for (var c = 0; c < count; c++) {
            System.arraycopy(entries[c], 0, predictor, c * MAX_PREDICTOR, predictorSize);
        }
    }

    /**
     * Stores the palette predictor (9.3.2.4).
     *
     * @param entries receives the entries, {@code maxPredictor} per component
     * @param offset the index in {@code entries} of the first entry
     * @param maxPredictor {@code PaletteMaxPredictorSize}
     * @return {@code PredictorPaletteSize}
     */
    int store(int[] entries, int offset, int maxPredictor) {
        for (var c = 0; c < 3; c++) {
            System.arraycopy(predictor, c * MAX_PREDICTOR, entries, offset + c * maxPredictor, predictorSize);
        }
        return predictorSize;
    }

    /**
     * Restores a palette predictor stored by {@link #store} (9.3.2.5).
     *
     * @param entries the stored entries
     * @param offset the index in {@code entries} of the first entry
     * @param maxPredictor {@code PaletteMaxPredictorSize}
     * @param count the stored {@code PredictorPaletteSize}
     */
    void load(int[] entries, int offset, int maxPredictor, int count) {
        predictorSize = count;
        for (var c = 0; c < 3; c++) {
            System.arraycopy(entries, offset + c * maxPredictor, predictor, c * MAX_PREDICTOR, count);
        }
    }

    /**
     * Parses the start of a {@code palette_coding()} structure (7.3.8.13), from the predictor reuse
     * flags to {@code palette_transpose_flag}.
     *
     * @param cabac the arithmetic decoder
     * @param sps the active sequence parameter set
     * @param log2Size the coding block size as a power of two, 3 to 5
     * @return {@code palette_escape_val_present_flag}, after which the caller parses
     * {@code delta_qp()} and {@code chroma_qp_offset()}
     * @throws MediaException.Malformed if a value is out of range
     */
    boolean parseHeader(CabacDecoder cabac, HevcSps sps, int log2Size) {
        log2BlockSize = log2Size;
        blockSize = 1 << log2Size;
        components = sps.chromaArrayType == 0 ? 1 : 3;
        var maxSize = sps.paletteMaxSize;
        java.util.Arrays.fill(reuse, 0, predictorSize, false);
        var predicted = 0;
        for (var i = 0; i < predictorSize && predicted < maxSize; i++) {
            var run = expGolomb(cabac, 0);
            if (run == 1) {
                break;
            }
            if (run > 1) {
                i += run - 1;
                if (i >= predictorSize) {
                    throw new MediaException.Malformed("palette_predictor_run past the predictor");
                }
            }
            reuse[i] = true;
            predicted++;
        }
        var signaled = predicted < maxSize ? expGolomb(cabac, 0) : 0;
        if (signaled > maxSize - predicted) {
            throw new MediaException.Malformed("palette larger than palette_max_size");
        }
        size = predicted + signaled;
        var k = 0;
        for (var i = 0; i < predictorSize; i++) {
            if (reuse[i]) {
                for (var c = 0; c < components; c++) {
                    palette[c * MAX_SIZE + k] = predictor[c * MAX_PREDICTOR + i];
                }
                k++;
            }
        }
        for (var c = 0; c < components; c++) {
            var bits = c == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
            for (var i = 0; i < signaled; i++) {
                palette[c * MAX_SIZE + predicted + i] = cabac.decodeBypassBins(bits);
            }
        }
        escape = size == 0 || cabac.decodeBypass() == 1;
        maxIndex = size - 1 + (escape ? 1 : 0);
        numIndices = 0;
        finalRunCopiesAbove = false;
        transpose = false;
        if (maxIndex > 0) {
            numIndices = numPaletteIndices(cabac) + 1;
            if (numIndices > blockSize * blockSize) {
                throw new MediaException.Malformed("num_palette_indices_minus1 out of range");
            }
            for (var i = 0; i < numIndices; i++) {
                var cMax = i == 0 ? maxIndex : maxIndex - 1;
                indexIdc[i] = cMax > 0 ? truncatedBinary(cabac, cMax) : 0;
            }
            finalRunCopiesAbove = cabac.decodeBin(CabacTables.PALETTE_COPY_ABOVE) == 1;
            transpose = cabac.decodeBin(CabacTables.PALETTE_TRANSPOSE) == 1;
        }
        return escape;
    }

    /**
     * Parses the palette index map of the coding block: the runs of indices and of indices
     * copied from the row above, in traverse scan order.
     *
     * @param cabac the arithmetic decoder
     * @throws MediaException.Malformed if the runs do not fit the block
     */
    void parseIndices(CabacDecoder cabac) {
        var n = blockSize;
        var total = n * n;
        var remaining = numIndices;
        var pos = 0;
        while (pos < total) {
            var raster = raster(pos);
            var previous = pos > 0 ? raster(pos - 1) : -1;
            var copyAbove = false;
            if (maxIndex > 0 && pos >= n && !copied[previous]) {
                if (remaining > 0 && pos < total - 1) {
                    copyAbove = cabac.decodeBin(CabacTables.PALETTE_COPY_ABOVE) == 1;
                } else {
                    copyAbove = !(pos == total - 1 && remaining > 0);
                }
            }
            var idc = 0;
            var index = 0;
            if (!copyAbove) {
                var parsed = numIndices - remaining;
                idc = parsed < numIndices ? indexIdc[parsed] : 0;
                index = idc;
                if (pos > 0) {
                    var reference = copied[previous] ? map[raster - n] : map[previous];
                    if (index >= reference) {
                        index++;
                    }
                }
            }
            var runMinus1 = total - pos - 1;
            if (maxIndex > 0) {
                if (!copyAbove) {
                    remaining--;
                    if (remaining < 0) {
                        throw new MediaException.Malformed("more palette index runs than num_palette_indices_minus1");
                    }
                }
                if (remaining > 0 || copyAbove != finalRunCopiesAbove) {
                    var maxRun = total - pos - 1 - remaining - (finalRunCopiesAbove ? 1 : 0);
                    if (maxRun < 0) {
                        throw new MediaException.Malformed("palette run past the coding block");
                    }
                    runMinus1 = maxRun > 0 ? run(cabac, copyAbove, idc, maxRun) : 0;
                }
            }
            for (var r = 0; r <= runMinus1; r++, pos++) {
                var at = raster(pos);
                copied[at] = copyAbove;
                map[at] = copyAbove ? map[at - n] : index;
            }
        }
    }

    /**
     * Parses the escape values of the escaped samples ({@code palette_escape_val}): one per sample
     * for luma, one per chroma sample of the subsampled chroma components.
     *
     * @param cabac the arithmetic decoder
     * @param sps the active sequence parameter set
     * @param bypass {@code cu_transquant_bypass_flag}, for which the values are fixed-length samples
     * @throws MediaException.Malformed if an Exp-Golomb code is too long
     */
    void parseEscapes(CabacDecoder cabac, HevcSps sps, boolean bypass) {
        if (!escape) {
            return;
        }
        var n = blockSize;
        var total = n * n;
        var format = sps.chromaArrayType;
        for (var c = 0; c < components; c++) {
            var bits = c == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
            var values = escapes[c];
            for (var pos = 0; pos < total; pos++) {
                var raster = raster(pos);
                if (map[raster] != maxIndex) {
                    continue;
                }
                var xC = raster & (n - 1);
                var yC = raster >> log2BlockSize;
                var coded = c == 0 || format == 3
                        || (format == 1 && (xC & 1) == 0 && (yC & 1) == 0)
                        || (format == 2 && ((transpose ? yC : xC) & 1) == 0);
                if (coded) {
                    values[raster] = bypass ? cabac.decodeBypassBins(bits) : expGolomb(cabac, 3);
                }
            }
        }
    }

    /**
     * Reconstructs the samples of the coding block into the picture (8.4.4.2.7), the transpose of
     * {@code palette_transpose_flag} included.
     *
     * @param frame the picture being decoded
     * @param sps the active sequence parameter set
     * @param x0 the luma x of the coding block
     * @param y0 the luma y of the coding block
     * @param bypass {@code cu_transquant_bypass_flag}, for which the escape values are the samples
     * @param qp the quantization parameter {@code qP} of each component, bit depth offset
     * included, for the escape values
     */
    void reconstruct(HevcFrame frame, HevcSps sps, int x0, int y0, boolean bypass, int[] qp) {
        var n = blockSize;
        for (var c = 0; c < components; c++) {
            var sx = c == 0 ? 0 : sps.chromaShiftX;
            var sy = c == 0 ? 0 : sps.chromaShiftY;
            var width = n >> sx;
            var height = n >> sy;
            var stride = c == 0 ? frame.width : frame.chromaWidth;
            var origin = (y0 >> sy) * stride + (x0 >> sx);
            var plane = c == 0 ? frame.y : c == 1 ? frame.cb : frame.cr;
            var plane8 = c == 0 ? frame.y8 : c == 1 ? frame.cb8 : frame.cr8;
            var maxValue = (1 << (c == 0 ? sps.bitDepthLuma : sps.bitDepthChroma)) - 1;
            var scale = LEVEL_SCALE[qp[c] % 6];
            var shift = qp[c] / 6;
            var values = escapes[c];
            for (var y = 0; y < height; y++) {
                var row = origin + y * stride;
                for (var x = 0; x < width; x++) {
                    // the map position of the sample, transposed with palette_transpose_flag (8-69, 8-70)
                    var at = transpose ? ((x << sx) << log2BlockSize) + (y << sy)
                            : ((y << sy) << log2BlockSize) + (x << sx);
                    var index = map[at];
                    int sample;
                    if (escape && index == maxIndex) {
                        sample = bypass ? values[at]
                                : Math.clamp((((long) values[at] * scale << shift) + 32) >> 6, 0, maxValue);
                    } else {
                        sample = palette[c * MAX_SIZE + index];
                    }
                    if (plane8 != null) {
                        plane8[row + x] = (byte) sample;
                    } else {
                        plane[row + x] = (short) sample;
                    }
                }
            }
        }
    }

    /**
     * Updates the palette predictor after the coding unit (equation 8-79): the entries of the
     * current palette followed by the entries of the predictor it did not reuse.
     *
     * @param maxPredictor {@code PaletteMaxPredictorSize}
     */
    void updatePredictor(int maxPredictor) {
        for (var c = 0; c < components; c++) {
            System.arraycopy(palette, c * MAX_SIZE, nextPredictor, c * MAX_PREDICTOR, size);
        }
        var count = size;
        for (var i = 0; i < predictorSize && count < maxPredictor; i++) {
            if (!reuse[i]) {
                for (var c = 0; c < components; c++) {
                    nextPredictor[c * MAX_PREDICTOR + count] = predictor[c * MAX_PREDICTOR + i];
                }
                count++;
            }
        }
        for (var c = 0; c < components; c++) {
            System.arraycopy(nextPredictor, c * MAX_PREDICTOR, predictor, c * MAX_PREDICTOR, count);
        }
        predictorSize = count;
    }

    /**
     * Returns the raster position in the coding block of a traverse scan position (6.5.6): rows
     * from top to bottom, the even ones left to right and the odd ones right to left.
     *
     * @param pos the scan position
     * @return the raster position, {@code y * nCbS + x}
     */
    private int raster(int pos) {
        var y = pos >> log2BlockSize;
        var x = pos & (blockSize - 1);
        return (y << log2BlockSize) + ((y & 1) == 0 ? x : blockSize - 1 - x);
    }

    /**
     * Parses {@code num_palette_indices_minus1} (9.3.3.14): a truncated Rice prefix with
     * {@code cRiceParam = 3 + ((MaxPaletteIndex + 1) >> 3)} and {@code cMax = 4 << cRiceParam},
     * followed when the prefix is all ones by an Exp-Golomb suffix of order {@code cRiceParam + 1}.
     *
     * @param cabac the arithmetic decoder
     * @return the value
     * @throws MediaException.Malformed if the code is too long
     */
    private int numPaletteIndices(CabacDecoder cabac) {
        var rice = 3 + ((maxIndex + 1) >> 3);
        var prefix = cabac.decodeBypassOnes(32);
        if (prefix <= 3) {
            return (prefix << rice) + cabac.decodeBypassBins(rice);
        }
        var bits = prefix - 3 + rice;
        if (bits > 24) {
            throw new MediaException.Malformed("num_palette_indices_minus1 too long");
        }
        return (((1 << (prefix - 3)) + 2) << rice) + cabac.decodeBypassBins(bits);
    }

    /**
     * Parses a {@code palette_run_prefix} and its {@code palette_run_suffix}, returning
     * {@code PaletteRunMinus1} (equations 7-85 and 7-86).
     *
     * @param cabac the arithmetic decoder
     * @param copyAbove {@code copy_above_palette_indices_flag} of the run
     * @param idc {@code palette_idx_idc} of a run of one index
     * @param maxRun {@code PaletteMaxRunMinus1}, at least 1
     * @return the run length minus 1, at most {@code maxRun}
     */
    private int run(CabacDecoder cabac, boolean copyAbove, int idc, int maxRun) {
        var cMax = 32 - Integer.numberOfLeadingZeros(maxRun);
        var prefix = 0;
        while (prefix < cMax) {
            int bin;
            if (prefix > 4) {
                bin = cabac.decodeBypass();
            } else {
                var ctx = copyAbove ? COPY_RUN_CONTEXT[prefix]
                        : prefix > 0 ? INDEX_RUN_CONTEXT[prefix] : idc < 1 ? 0 : idc < 3 ? 1 : 2;
                bin = cabac.decodeBin(CabacTables.PALETTE_RUN + ctx);
            }
            if (bin == 0) {
                break;
            }
            prefix++;
        }
        if (prefix < 2) {
            return prefix;
        }
        var offset = 1 << (prefix - 1);
        if (maxRun == offset) {
            return offset;
        }
        var suffixMax = (offset << 1) > maxRun ? maxRun - offset : offset - 1;
        return offset + truncatedBinary(cabac, suffixMax);
    }

    /**
     * Decodes a truncated binary bypass code (9.3.3.6) of {@code cMax + 1} symbols.
     *
     * @param cabac the arithmetic decoder
     * @param cMax the largest value, at least 1
     * @return the value
     */
    private static int truncatedBinary(CabacDecoder cabac, int cMax) {
        var symbols = cMax + 1;
        var k = 31 - Integer.numberOfLeadingZeros(symbols);
        var u = (1 << (k + 1)) - symbols;
        var value = cabac.decodeBypassBins(k);
        if (value >= u) {
            value = ((value << 1) | cabac.decodeBypass()) - u;
        }
        return value;
    }

    /**
     * Decodes a k-th order Exp-Golomb bypass code (9.3.3.3).
     *
     * @param cabac the arithmetic decoder
     * @param k the order
     * @return the value
     * @throws MediaException.Malformed if the prefix exceeds the longest valid one
     */
    private static int expGolomb(CabacDecoder cabac, int k) {
        var ones = cabac.decodeBypassOnes(30 - k);
        if (ones > 30 - k) {
            throw new MediaException.Malformed("Exp-Golomb bypass code too long");
        }
        return (((1 << ones) - 1) << k) + cabac.decodeBypassBins(k + ones);
    }
}
