package com.github.auties00.smalt.video.prores.internal;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.video.intra.internal.BlockKernel;
import com.github.auties00.smalt.video.intra.internal.IntraBitWriter;
import com.github.auties00.smalt.video.intra.internal.IntraPlanes;


/**
 * Encodes the slices of ProRes pictures (SMPTE RDD 36): gathers the blocks of a slice from the
 * prepared planes, transforms them with {@link BlockKernel}, chooses the slice's quantization
 * index under a size budget, and writes the slice header, the entropy-coded components and the
 * run-length coded alpha channel.
 *
 * <p>Instances hold the scratch buffers of one encoding thread and are not thread-safe.
 *
 * @implNote This implementation picks, for every slice, the finest quantization index whose coded
 * size fits the slice's budget, searching from the index the slice used in the previous
 * frame; the coded size of a candidate index is measured exactly from the code lengths,
 * without writing, by visiting only the non-zero levels (the quantizer reports a lane
 * mask per coefficient position). The search jumps to the index at which sizes inversely
 * proportional to the quantization scale would meet the budget, then interpolates between
 * the sizes of the bracketing indices. Sizes are not exactly monotonic in the index (the
 * codebooks adapt to the previous run and level, and every component rounds up to whole
 * bytes), so where several neighboring indices fit, the index found can differ by one from
 * the one a pure bisection finds. Levels are rounded with a dead zone ({@link #ROUNDING}),
 * which spends the bits on the coefficients that matter at equal size.
 */
public final class ProResSliceEncoder {
    /**
     * The rounding offset of the quantizer.
     */
    private static final float ROUNDING = 0.4f;

    /**
     * The largest magnitude a quantized level may take, which keeps every codeword within the
     * decoder's limits.
     */
    private static final int MAX_LEVEL = 1 << 20;

    /**
     * The most blocks one component of a slice holds.
     */
    private static final int MAX_BLOCKS = 32;

    /**
     * The number of symbols per codebook the length tables cover.
     */
    private static final int TABLE_SYMBOLS = 64;

    /**
     * The codeword length of the small symbols of every run codebook, by
     * {@code codebook * TABLE_SYMBOLS + symbol}.
     */
    private static final int[] RUN_BITS = lengths(ProResTables.RUN);

    /**
     * The codeword length of the small symbols of every level codebook, by
     * {@code codebook * TABLE_SYMBOLS + symbol}.
     */
    private static final int[] LEVEL_BITS = lengths(ProResTables.LEVEL);

    /**
     * The codewords of the small symbols of every run codebook, by
     * {@code codebook * TABLE_SYMBOLS + symbol}, in {@link #RUN_BITS} bits.
     */
    private static final int[] RUN_CODES = codes(ProResTables.RUN);

    /**
     * The codewords of the small symbols of every level codebook, by
     * {@code codebook * TABLE_SYMBOLS + symbol}, in {@link #LEVEL_BITS} bits.
     */
    private static final int[] LEVEL_CODES = codes(ProResTables.LEVEL);

    /**
     * The DCT coefficients of the three components, one array of batches per component.
     */
    private final float[][][] coefficients = new float[3][MAX_BLOCKS / BlockKernel.LANES][BlockKernel.BATCH_LENGTH];

    /**
     * The quantized levels of the three components, one array of batches per component.
     */
    private final int[][][] levels = new int[3][MAX_BLOCKS / BlockKernel.LANES][BlockKernel.BATCH_LENGTH];

    /**
     * The non-zero lane masks of the quantized levels, one array of 64 positions per batch, per
     * component.
     */
    private final int[][][] masks = new int[3][MAX_BLOCKS / BlockKernel.LANES][64];

    /**
     * The reciprocal quantizer steps of luma for the index being tried, interleaved like a batch.
     */
    private final float[] lumaSteps = new float[BlockKernel.BATCH_LENGTH];

    /**
     * The reciprocal quantizer steps of chroma for the index being tried, interleaved like a batch.
     */
    private final float[] chromaSteps = new float[BlockKernel.BATCH_LENGTH];

    /**
     * The quantization index the steps were computed for, or {@code 0}.
     */
    private int stepsIndex;

    /**
     * The luma matrix {@link #lumaSteps} were computed for.
     */
    private int[] lastLuma;

    /**
     * The chroma matrix {@link #chromaSteps} were computed for.
     */
    private int[] lastChroma;

    /**
     * The alpha values of a slice in raster order.
     */
    private int[] alpha = new int[0];

    /**
     * The sample plane of every block of the batch being gathered.
     */
    private final short[][] sources = new short[BlockKernel.LANES][];

    /**
     * The index of the first sample of every block of the batch being gathered.
     */
    private final int[] offsets = new int[BlockKernel.LANES];

    /**
     * The row stride of every block of the batch being gathered.
     */
    private final int[] strides = new int[BlockKernel.LANES];

    /**
     * The work array of the block loader.
     */
    private final float[] loadScratch = new float[64];

    /**
     * The coded size of each color component of the slice being written.
     */
    private final int[] componentSizes = new int[3];

    /**
     * The bit writer.
     */
    private final IntraBitWriter writer = new IntraBitWriter(1 << 15);

    /**
     * Constructs a slice encoder.
     */
    public ProResSliceEncoder() {
    }

    /**
     * Encodes one slice.
     *
     * @param planes the prepared planes of the frame
     * @param context the picture being encoded
     * @param sliceRow the macroblock row of the slice in its picture
     * @param mbX the first macroblock column of the slice
     * @param mbCount the number of macroblocks of the slice: 1, 2, 4 or 8
     * @param budget the most bytes the slice header and color components should take; the
     * alpha channel, coded losslessly, comes on top
     * @param startIndex the quantization index the search starts from
     * @param fixedIndex the quantization index to use without search, or {@code 0}
     * @return the coded slice, whose first byte holds the slice header
     */
    public EncodedSlice encode(IntraPlanes planes, ProResPictureContext context, int sliceRow, int mbX, int mbCount,
                               int budget, int startIndex, int fixedIndex) {
        var log2Mbs = Integer.numberOfTrailingZeros(mbCount);
        var chroma444 = context.chroma444();
        var lumaLog2 = 2 + log2Mbs;
        var chromaLog2 = (chroma444 ? 2 : 1) + log2Mbs;
        gather(planes, context, sliceRow, mbX, mbCount, chroma444);
        var kernel = BlockKernel.INSTANCE;
        transform(kernel, 0, 1 << lumaLog2);
        transform(kernel, 1, 1 << chromaLog2);
        transform(kernel, 2, 1 << chromaLog2);
        var hasAlpha = context.alphaType() != 0;
        var rows = Math.min(16, context.pictureHeight() - sliceRow * 16);
        if (hasAlpha) {
            gatherAlpha(planes, context, sliceRow, mbX, mbCount, rows);
        }
        var headerSize = hasAlpha ? 8 : 6;
        var index = fixedIndex;
        if (index == 0) {
            index = search(kernel, context, lumaLog2, chromaLog2, budget - headerSize, startIndex);
        }
        quantize(kernel, context, index, lumaLog2, chromaLog2);
        var w = writer;
        w.reset();
        var sizes = componentSizes;
        var end = 0;
        for (var c = 0; c < 3; c++) {
            writeComponent(w, levels[c], coefficients[c], masks[c], c == 0 ? lumaLog2 : chromaLog2, context.scan());
            w.alignToByte();
            sizes[c] = w.byteCount() - end;
            end += sizes[c];
        }
        if (hasAlpha) {
            writeAlpha(w, context.alphaType(), 16 * mbCount * rows);
            w.alignToByte();
        }
        var out = new byte[headerSize + w.byteCount()];
        out[0] = (byte) (headerSize << 3);
        out[1] = (byte) index;
        ByteArrays.put16be(out, 2, sizes[0]);
        ByteArrays.put16be(out, 4, sizes[1]);
        if (hasAlpha) {
            ByteArrays.put16be(out, 6, sizes[2]);
        }
        w.copyTo(out, headerSize);
        return new EncodedSlice(out, index);
    }

    /**
     * Gathers the blocks of a slice from the planes into {@link #coefficients}, as samples centerd
     * on zero at the 9-bit scale of the transform domain.
     *
     * @param planes the prepared planes
     * @param context the picture
     * @param sliceRow the macroblock row
     * @param mbX the first macroblock column
     * @param mbCount the number of macroblocks
     * @param chroma444 whether chroma has full width
     */
    private void gather(IntraPlanes planes, ProResPictureContext context, int sliceRow, int mbX, int mbCount,
                        boolean chroma444) {
        var subtract = 1 << (planes.depth() - 1);
        var scale = 512f / (1 << planes.depth());
        var top = sliceRow * 16;
        // One call site of the kernel: C2 inlines the kernel once per call site, and three copies push this method
        // past the compiler's inlining node budget, leaving the last copy on the Vector API's fallback.
        for (var component = 0; component < 3; component++) {
            var luma = component == 0;
            var blocks = (luma || chroma444 ? 4 : 2) * mbCount;
            var stride = luma ? planes.lumaStride() : planes.chromaStride();
            var plane = switch (component) {
                case 0 -> planes.first();
                case 1 -> planes.second();
                default -> planes.third();
            };
            for (var first = 0; first < blocks; first += BlockKernel.LANES) {
                var lanes = Math.min(BlockKernel.LANES, blocks - first);
                for (var lane = 0; lane < lanes; lane++) {
                    var block = first + lane;
                    int x;
                    int y;
                    if (luma) {
                        var sub = block & 3;
                        x = mbX * 16 + (block >>> 2) * 16 + (sub & 1) * 8;
                        y = top + (sub >>> 1) * 8;
                    } else if (chroma444) {
                        var sub = block & 3;
                        x = (mbX + (block >>> 2)) * 16 + (sub >>> 1) * 8;
                        y = top + (sub & 1) * 8;
                    } else {
                        x = (mbX + (block >>> 1)) * 8;
                        y = top + (block & 1) * 8;
                    }
                    sources[lane] = plane;
                    offsets[lane] = (y * context.rowStep() + context.rowOffset()) * stride + x;
                    strides[lane] = stride * context.rowStep();
                }
                BlockKernel.INSTANCE.loadBlocks(sources, offsets, strides, lanes, subtract, scale,
                        coefficients[component][first >>> 3], loadScratch);
            }
        }
    }

    /**
     * Applies the forward transform to the batches of one component.
     *
     * @param kernel the block kernel
     * @param component the color component: 0 for luma, 1 and 2 for chroma
     * @param blocks the number of blocks
     */
    private void transform(BlockKernel kernel, int component, int blocks) {
        var batches = coefficients[component];
        for (var batch = 0; batch * BlockKernel.LANES < blocks; batch++) {
            if (blocks - batch * BlockKernel.LANES < BlockKernel.LANES) {
                var values = batches[batch];
                for (var i = 0; i < BlockKernel.BATCH_LENGTH; i++) {
                    if ((i & 7) >= blocks - batch * BlockKernel.LANES) {
                        values[i] = 0f;
                    }
                }
            }
            kernel.forward(batches[batch]);
        }
    }

    /**
     * Finds the finest quantization index whose coded components fit a budget.
     *
     * <p>The search measures the starting index first, then jumps to the index whose quantization
     * scale would make the measured size equal the budget if sizes were inversely proportional to
     * the scale, widening the jump by doubling steps when that estimate does not bracket the
     * answer, and finally narrows the bracket by interpolating between its measured ends.
     *
     * @param kernel the block kernel
     * @param context the picture
     * @param lumaLog2 the base-2 logarithm of the number of luma blocks
     * @param chromaLog2 the base-2 logarithm of the number of blocks of each chroma component
     * @param budget the most bytes the three components may take
     * @param start the index the search starts from
     * @return the index, from 1 to 224
     */
    private int search(BlockKernel kernel, ProResPictureContext context, int lumaLog2, int chromaLog2, int budget,
                       int start) {
        var max = ProResTables.MAX_QUANTIZATION_INDEX;
        var divisor = Math.max(1, budget);
        var index = Math.clamp(start, 1, max);
        var size = size(kernel, context, index, lumaLog2, chromaLog2);
        var fitting = 0;
        var fittingSize = 0;
        var failing = 0;
        var failingSize = 0;
        if (size <= budget) {
            fitting = index;
            fittingSize = size;
        } else {
            failing = index;
            failingSize = size;
        }
        var step = 1;
        while (fitting == 0 || failing == 0 || fitting - failing > 1) {
            int candidate;
            if (fitting != 0 && failing != 0) {
                var width = fitting - failing;
                if (width <= 3 || failingSize == fittingSize) {
                    candidate = (failing + fitting) >>> 1;
                } else {
                    candidate = failing + (int) ((long) (failingSize - budget) * width / (failingSize - fittingSize));
                    candidate = Math.clamp(candidate, failing + 1, fitting - 1);
                }
            } else if (fitting != 0) {
                if (fitting == 1) {
                    return 1;
                }
                candidate = Math.min(indexOfScale((long) ProResTables.scale(fitting) * fittingSize / budget), fitting - step);
                candidate = Math.max(candidate, 1);
                step *= 2;
            } else {
                if (failing == max) {
                    return max;
                }
                candidate = Math.max(indexOfScale(((long) ProResTables.scale(failing) * failingSize + budget - 1) / budget),
                        failing + step);
                candidate = Math.min(candidate, max);
                step *= 2;
            }
            size = size(kernel, context, candidate, lumaLog2, chromaLog2);
            if (size <= budget) {
                fitting = candidate;
                fittingSize = size;
            } else {
                failing = candidate;
                failingSize = size;
            }
        }
        return fitting;
    }

    /**
     * Returns the largest quantization index whose quantization scale does not exceed a value.
     *
     * @param scale the quantization scale
     * @return the index, at least 1 and at most the largest index
     */
    private static int indexOfScale(long scale) {
        if (scale <= 128) {
            return (int) Math.max(1, scale);
        }
        return (int) Math.min(ProResTables.MAX_QUANTIZATION_INDEX, 128 + (scale - 128) / 4);
    }

    /**
     * Returns the coded size of the three components at a quantization index.
     *
     * @param kernel the block kernel
     * @param context the picture
     * @param index the quantization index
     * @param lumaLog2 the base-2 logarithm of the number of luma blocks
     * @param chromaLog2 the base-2 logarithm of the number of blocks of each chroma component
     * @return the size in bytes
     */
    private int size(BlockKernel kernel, ProResPictureContext context, int index, int lumaLog2, int chromaLog2) {
        quantize(kernel, context, index, lumaLog2, chromaLog2);
        var bytes = 0;
        for (var c = 0; c < 3; c++) {
            bytes += (componentBits(levels[c], masks[c], c == 0 ? lumaLog2 : chromaLog2, context.scan()) + 7) >>> 3;
        }
        return bytes;
    }

    /**
     * Quantizes the three components at a quantization index into {@link #levels}.
     *
     * @param kernel the block kernel
     * @param context the picture
     * @param index the quantization index
     * @param lumaLog2 the base-2 logarithm of the number of luma blocks
     * @param chromaLog2 the base-2 logarithm of the number of blocks of each chroma component
     */
    private void quantize(BlockKernel kernel, ProResPictureContext context, int index, int lumaLog2, int chromaLog2) {
        if (stepsIndex != index || context.lumaMatrix() != lastLuma || context.chromaMatrix() != lastChroma) {
            var scale = ProResTables.scale(index);
            for (var position = 0; position < 64; position++) {
                var luma = 8f / (context.lumaMatrix()[position] * scale);
                var chroma = 8f / (context.chromaMatrix()[position] * scale);
                for (var lane = 0; lane < BlockKernel.LANES; lane++) {
                    lumaSteps[position * BlockKernel.LANES + lane] = luma;
                    chromaSteps[position * BlockKernel.LANES + lane] = chroma;
                }
            }
            stepsIndex = index;
            lastLuma = context.lumaMatrix();
            lastChroma = context.chromaMatrix();
        }
        for (var c = 0; c < 3; c++) {
            var blocks = 1 << (c == 0 ? lumaLog2 : chromaLog2);
            var steps = c == 0 ? lumaSteps : chromaSteps;
            for (var batch = 0; batch * BlockKernel.LANES < blocks; batch++) {
                var values = coefficients[c][batch];
                var out = levels[c][batch];
                kernel.quantizeMasked(values, steps, ROUNDING, MAX_LEVEL, out, masks[c][batch]);
                for (var lane = 0; lane < BlockKernel.LANES; lane++) {
                    out[lane] = Math.clamp((int) Math.floor(values[lane] * steps[lane] + 0.5f), -MAX_LEVEL, MAX_LEVEL);
                }
            }
        }
    }

    /**
     * Merges the non-zero lane masks of the batches of a component at one raster position into
     * one mask over its blocks, bit {@code b} set when block {@code b} has a non-zero level there.
     *
     * @param masks the component's lane masks, one array of 64 positions per batch
     * @param count the number of batches of the component, from 1 to 4
     * @param raster the raster position
     * @return the mask
     */
    private static int blockMask(int[][] masks, int count, int raster) {
        var mask = masks[0][raster];
        if (count > 1) {
            mask |= masks[1][raster] << 8;
            if (count > 2) {
                mask |= masks[2][raster] << 16 | masks[3][raster] << 24;
            }
        }
        return mask;
    }

    /**
     * Tabulates the codewords of the small symbols of a family of codebooks, as the value that
     * {@link ProResCodes#encode} writes in {@link ProResCodes#length} bits.
     *
     * @param books the codebooks
     * @return the codewords, by {@code book * TABLE_SYMBOLS + symbol}
     */
    private static int[] codes(int[] books) {
        var table = new int[books.length * TABLE_SYMBOLS];
        for (var book = 0; book < books.length; book++) {
            var riceLimit = books[book] >>> 8;
            var kRice = (books[book] >>> 4) & 15;
            var kExp = books[book] & 15;
            var riceRange = riceLimit << kRice;
            for (var symbol = 0; symbol < TABLE_SYMBOLS; symbol++) {
                table[book * TABLE_SYMBOLS + symbol] = symbol < riceRange
                        ? 1 << kRice | (symbol & ((1 << kRice) - 1))
                        : symbol - riceRange + (1 << kExp);
            }
        }
        return table;
    }

    /**
     * Tabulates the codeword lengths of the small symbols of a family of codebooks.
     *
     * @param books the codebooks
     * @return the lengths, by {@code book * TABLE_SYMBOLS + symbol}
     */
    private static int[] lengths(int[] books) {
        var table = new int[books.length * TABLE_SYMBOLS];
        for (var book = 0; book < books.length; book++) {
            for (var symbol = 0; symbol < TABLE_SYMBOLS; symbol++) {
                table[book * TABLE_SYMBOLS + symbol] = ProResCodes.length(books[book], symbol);
            }
        }
        return table;
    }

    /**
     * Returns the number of bits of the entropy-coded scanned coefficients of a component.
     *
     * @param batches the component's levels
     * @param masks the non-zero lane masks of the levels
     * @param log2Blocks the base-2 logarithm of the number of blocks
     * @param scan the raster position of every scan index
     * @return the bit count, before byte alignment
     */
    private static int componentBits(int[][] batches, int[][] masks, int log2Blocks, int[] scan) {
        var blocks = 1 << log2Blocks;
        var previousDc = batches[0][0];
        var bits = ProResCodes.length(ProResTables.FIRST_DC, ProResCodes.toSymbol(previousDc));
        var previousDifference = ProResTables.INITIAL_DC_DIFFERENCE;
        for (var block = 1; block < blocks; block++) {
            var dc = batches[block >>> 3][block & 7];
            var difference = dc - previousDc;
            var coded = previousDifference < 0 ? -difference : difference;
            bits += ProResCodes.length(ProResTables.DC_DIFFERENCE[Math.min(Math.abs(previousDifference), 3)],
                    ProResCodes.toSymbol(coded));
            previousDifference = difference;
            previousDc = dc;
        }
        var count = (blocks + BlockKernel.LANES - 1) / BlockKernel.LANES;
        var run = 0;
        var previousRun = ProResTables.INITIAL_RUN;
        var previousLevel = ProResTables.INITIAL_LEVEL;
        for (var position = 1; position < 64; position++) {
            var raster = scan[position];
            var mask = blockMask(masks, count, raster);
            var next = 0;
            while (mask != 0) {
                var block = Integer.numberOfTrailingZeros(mask);
                run += block - next;
                next = block + 1;
                var magnitude = batches[block >>> 3][raster * BlockKernel.LANES + (block & 7)] - 1;
                var runBook = Math.min(previousRun, 15);
                var levelBook = Math.min(previousLevel, 8);
                bits += (run < TABLE_SYMBOLS ? RUN_BITS[runBook * TABLE_SYMBOLS + run]
                        : ProResCodes.length(ProResTables.RUN[runBook], run))
                        + (magnitude < TABLE_SYMBOLS ? LEVEL_BITS[levelBook * TABLE_SYMBOLS + magnitude]
                        : ProResCodes.length(ProResTables.LEVEL[levelBook], magnitude)) + 1;
                previousRun = run;
                previousLevel = magnitude;
                run = 0;
                mask &= mask - 1;
            }
            run += blocks - next;
        }
        return bits;
    }

    /**
     * Writes the entropy-coded scanned coefficients of a component (RDD 36 clause 5.3.2).
     *
     * @param w the writer
     * @param batches the component's levels
     * @param coefficients the component's coefficients, whose signs are the signs of the levels
     * @param masks the non-zero lane masks of the levels
     * @param log2Blocks the base-2 logarithm of the number of blocks
     * @param scan the raster position of every scan index
     */
    private static void writeComponent(IntraBitWriter w, int[][] batches, float[][] coefficients, int[][] masks,
                                       int log2Blocks, int[] scan) {
        var blocks = 1 << log2Blocks;
        var previousDc = batches[0][0];
        ProResCodes.encode(w, ProResTables.FIRST_DC, ProResCodes.toSymbol(previousDc));
        var previousDifference = ProResTables.INITIAL_DC_DIFFERENCE;
        for (var block = 1; block < blocks; block++) {
            var dc = batches[block >>> 3][block & 7];
            var difference = dc - previousDc;
            var coded = previousDifference < 0 ? -difference : difference;
            ProResCodes.encode(w, ProResTables.DC_DIFFERENCE[Math.min(Math.abs(previousDifference), 3)],
                    ProResCodes.toSymbol(coded));
            previousDifference = difference;
            previousDc = dc;
        }
        var count = (blocks + BlockKernel.LANES - 1) / BlockKernel.LANES;
        var run = 0;
        var previousRun = ProResTables.INITIAL_RUN;
        var previousLevel = ProResTables.INITIAL_LEVEL;
        for (var position = 1; position < 64; position++) {
            var raster = scan[position];
            var mask = blockMask(masks, count, raster);
            var next = 0;
            while (mask != 0) {
                var block = Integer.numberOfTrailingZeros(mask);
                run += block - next;
                next = block + 1;
                var at = raster * BlockKernel.LANES + (block & 7);
                var magnitude = batches[block >>> 3][at] - 1;
                var runBook = Math.min(previousRun, 15);
                var levelBook = Math.min(previousLevel, 8);
                var negative = Float.floatToRawIntBits(coefficients[block >>> 3][at]) >>> 31;
                if (run < TABLE_SYMBOLS && magnitude < TABLE_SYMBOLS) {
                    var runSlot = runBook * TABLE_SYMBOLS + run;
                    var levelSlot = levelBook * TABLE_SYMBOLS + magnitude;
                    var levelBits = LEVEL_BITS[levelSlot];
                    w.write(RUN_CODES[runSlot] << (levelBits + 1) | LEVEL_CODES[levelSlot] << 1 | negative,
                            RUN_BITS[runSlot] + levelBits + 1);
                } else {
                    ProResCodes.encode(w, ProResTables.RUN[runBook], run);
                    ProResCodes.encode(w, ProResTables.LEVEL[levelBook], magnitude);
                    w.write(negative, 1);
                }
                previousRun = run;
                previousLevel = magnitude;
                run = 0;
                mask &= mask - 1;
            }
            run += blocks - next;
        }
    }

    /**
     * Gathers the alpha values of a slice into {@link #alpha}, rescaled to the coded alpha depth.
     *
     * @param planes the prepared planes, whose alpha plane has the coded depth
     * @param context the picture
     * @param sliceRow the macroblock row
     * @param mbX the first macroblock column
     * @param mbCount the number of macroblocks
     * @param rows the number of rows of the slice inside the picture
     */
    private void gatherAlpha(IntraPlanes planes, ProResPictureContext context, int sliceRow, int mbX, int mbCount,
                             int rows) {
        var width = 16 * mbCount;
        var count = width * rows;
        if (alpha.length < count) {
            alpha = new int[count];
        }
        var stride = planes.lumaStride();
        var step = stride * context.rowStep();
        var at = (sliceRow * 16 * context.rowStep() + context.rowOffset()) * stride + mbX * 16;
        var plane = planes.alpha();
        for (var row = 0; row < rows; row++, at += step) {
            for (var x = 0; x < width; x++) {
                alpha[row * width + x] = plane[at + x] & 0xFFFF;
            }
        }
    }

    /**
     * Writes the run-length coded alpha values of a slice (RDD 36 clauses 5.3.3 and 7.1.2).
     *
     * @param w the writer
     * @param alphaType {@code 1} for 8-bit alpha, {@code 2} for 16-bit alpha
     * @param count the number of alpha values
     */
    private void writeAlpha(IntraBitWriter w, int alphaType, int count) {
        var previous = -1;
        var n = 0;
        var magnitudeBits = alphaType == 2 ? 6 : 3;
        var escapeBits = alphaType == 2 ? 16 : 8;
        var shortRange = alphaType == 2 ? 64 : 8;
        var mask = alphaType == 2 ? 0xFFFF : 0xFF;
        while (n < count) {
            var value = alpha[n];
            var run = 1;
            while (n + run < count && run < ProResTables.MAX_ALPHA_RUN && alpha[n + run] == value) {
                run++;
            }
            var difference = value - previous;
            if (difference != 0 && Math.abs(difference) <= shortRange) {
                w.write(0, 1);
                w.write(Math.abs(difference) - 1, magnitudeBits);
                w.write(difference < 0 ? 1 : 0, 1);
            } else {
                w.write(1, 1);
                w.write(difference & mask, escapeBits);
            }
            if (run == 1) {
                w.write(1, 1);
            } else if (run <= 16) {
                w.write(run - 1, 5);
            } else {
                w.write(0, 5);
                w.write(run - 1, 11);
            }
            previous = value;
            n += run;
        }
    }

    /**
     * Holds a coded slice and the quantization index it used.
     *
     * @param data the slice bytes, starting with the slice header
     * @param index the quantization index
     */
    public record EncodedSlice(byte[] data, int index) {
    }
}
