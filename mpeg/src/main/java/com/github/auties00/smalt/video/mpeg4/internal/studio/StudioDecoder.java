/* Ports the DPCM block decoding of the MoMuSys MPEG-4 Visual reference software of ISO/IEC 14496-5:2001/Amd.1:2002
 * (StudioProfile-MoMuSys, vm_dec/src/combined_decode.c: DecodeDPCMBlock_Studio) and its studio intra dequantisation
 * (vm_common/src/text_quant.c: BlockDequantMPEG_DCText_DeadZone_Studio, studio_quantizer.c). Copyright 1996-2002 the
 * ACTS-MoMuSys partners and Sony Corporation; ISO/IEC free license for products conforming to ISO/IEC 14496-2.
 * Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.studio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import com.github.auties00.smalt.video.mpeg4.internal.Vlc;

import java.util.Arrays;

/**
 * Decodes the VOPs of a rectangular Studio profile video object layer into planes of samples.
 *
 * <p>Intra VOPs (I-VOPs) are decoded, frame and field structured, with DCT and DPCM macroblocks in every chroma
 * format and sample precision. The slices of a VOP are decoded in parallel on the shared pool. A field VOP fills
 * the lines of its parity in the frame planes; {@link #fieldPending()} tells when the first field of a frame has
 * been decoded and the second has not.
 *
 * <p>An instance is not thread-safe.
 */
final class StudioDecoder {
    /**
     * The {@code vop_structure} of a top field VOP.
     */
    private static final int TOP_FIELD = 1;

    /**
     * The {@code vop_structure} of a bottom field VOP.
     */
    private static final int BOTTOM_FIELD = 2;

    /**
     * The {@code vop_structure} of a frame VOP.
     */
    private static final int FRAME = 3;

    /**
     * The alternate (vertical) scan: the raster index of each scan position.
     */
    private static final int[] ALTERNATE = {
            0, 8, 16, 24, 1, 9, 2, 10, 17, 25, 32, 40, 48, 56, 57, 49,
            41, 33, 26, 18, 3, 11, 4, 12, 19, 27, 34, 42, 50, 58, 35, 43,
            51, 59, 20, 28, 5, 13, 6, 14, 21, 29, 36, 44, 52, 60, 37, 45,
            53, 61, 22, 30, 7, 15, 23, 31, 38, 46, 54, 62, 39, 47, 55, 63};

    /**
     * The largest prefix of a DPCM residual: a prefix of this many zeros announces an escaped residual.
     */
    private static final int ESCAPE_PREFIX = 11;

    private final StudioLayer layer;
    private final int mbWidth;

    /**
     * The macroblock rows of a frame.
     */
    private final int mbHeight;

    /**
     * The coded width and height of the luminance plane (whole macroblocks).
     */
    private final int codedWidth;
    private final int codedHeight;

    /**
     * The coded size of a chrominance plane.
     */
    private final int chromaWidth;
    private final int chromaHeight;

    /**
     * The horizontal and vertical chroma subsampling shifts.
     */
    private final int shiftX;
    private final int shiftY;

    /**
     * The number of 8x8 blocks of a macroblock: 6, 8 or 12.
     */
    private final int blocks;

    /**
     * The decoded planes, in coded size; samples are unsigned values of {@link StudioLayer#bitsPerPixel} bits.
     */
    final short[] y;
    final short[] cb;
    final short[] cr;

    private final Slice[] workers;
    private final BitReader headerReader = new BitReader();

    /**
     * The fields of the VOP being decoded.
     */
    private int structure;
    private boolean alternateScan;
    private boolean framePredFrameDct;
    private int dctPrecision;
    private int intraDcPrecision;
    private boolean nonLinearScale;
    private boolean predictorsReset;

    /**
     * The parity of the first field of a frame whose second field has not been decoded, or -1.
     */
    private int pendingField = -1;

    /**
     * The quantiser matrices, carried from the layer and updated by VOP quant matrix extensions.
     */
    private final int[] intraMatrix = new int[64];
    private final int[] chromaIntraMatrix = new int[64];

    /**
     * Creates a decoder for a layer.
     *
     * @param layer the layer whose VOPs are decoded
     * @param limits the limits bounding the allocations
     * @throws MediaException.LimitExceeded if the planes exceed the allocation limit
     */
    StudioDecoder(StudioLayer layer, MediaLimits limits) {
        this.layer = layer;
        this.mbWidth = layer.mbWidth();
        this.mbHeight = layer.progressive ? (layer.height + 15) >> 4 : ((layer.height + 31) >> 5) << 1;
        this.codedWidth = mbWidth << 4;
        this.codedHeight = mbHeight << 4;
        this.shiftX = layer.chroma == ChromaFormat.YUV444 ? 0 : 1;
        this.shiftY = layer.chroma == ChromaFormat.YUV420 ? 1 : 0;
        this.chromaWidth = codedWidth >> shiftX;
        this.chromaHeight = codedHeight >> shiftY;
        this.blocks = 4 + 2 * ((16 >> shiftX) * (16 >> shiftY) / 64);
        LimitChecks.checkAllocation(limits, 2L * ((long) codedWidth * codedHeight + 2L * chromaWidth * chromaHeight));
        this.y = new short[codedWidth * codedHeight];
        this.cb = new short[chromaWidth * chromaHeight];
        this.cr = new short[chromaWidth * chromaHeight];
        this.workers = new Slice[Math.max(1, ParallelBands.THREADS)];
        System.arraycopy(layer.intraMatrix, 0, intraMatrix, 0, 64);
        System.arraycopy(layer.chromaIntraMatrix, 0, chromaIntraMatrix, 0, 64);
    }

    /**
     * Reloads the quantiser matrices from the layer, after extension data that follows the layer header.
     */
    void reloadMatrices() {
        System.arraycopy(layer.intraMatrix, 0, intraMatrix, 0, 64);
        System.arraycopy(layer.chromaIntraMatrix, 0, chromaIntraMatrix, 0, 64);
    }

    /**
     * Forgets the first field of a frame whose second field was not decoded, as after a seek.
     */
    void reset() {
        pendingField = -1;
    }

    /**
     * {@return the width of the luminance planes in samples}
     */
    int codedWidth() {
        return codedWidth;
    }

    /**
     * {@return the width of the chrominance planes in samples}
     */
    int chromaWidth() {
        return chromaWidth;
    }

    /**
     * {@return whether the first field of a frame was decoded and its second field was not}
     */
    boolean fieldPending() {
        return pendingField >= 0;
    }

    /**
     * Decodes one VOP.
     *
     * @param data the bytes
     * @param offset the index after the VOP start code
     * @param end the index after the last byte of the VOP: its header, extension data and slices
     * @return {@code true} when the VOP completed a frame, {@code false} for a VOP that is not coded or the first
     * field of a frame
     * @throws MediaException.Malformed if the VOP is corrupt
     * @throws MediaException.Unsupported for a P-, B- or S-VOP
     */
    boolean decode(byte[] data, int offset, int end) {
        var headerEnd = nextStartCode(data, offset, end);
        var reader = headerReader;
        reader.load(data, offset, headerEnd - offset);
        reader.skip(72);
        reader.skip(10);
        structure = reader.read(2);
        var type = reader.read(2);
        if (structure == 0) {
            throw new MediaException.Malformed("reserved vop_structure in a Studio VOP");
        }
        if (!reader.readFlag()) {
            if (structure != FRAME && pendingField >= 0) {
                pendingField = -1;
            }
            return false;
        }
        if (type != 0) {
            // TODO: decode the P-VOPs of the Core Studio profile. Blocked: their macroblock header follows the FDAM 1
            //  syntax of the ISO/IEC 14496-5 reference decoder (not_coded, compression_mode, the ISO/IEC 13818-2
            //  macroblock_type and coded_block_pattern codes), but the final inter coefficient VLCs differ from both
            //  the FDAM 1 tables and the intra tables, and no available decoder reconstructs Core Studio streams to
            //  derive them from (FFmpeg decodes Simple Studio only; the reference decoder implements FDAM 1).
            //  Known so far: the inter tables are canonical codes like the intra ones (lengths close to FDAM 1, no
            //  code under the 000000 prefix), the first-coefficient table has no EOB, and the escape is 14 bits for
            //  10-bit video; the DC-only codes and the EOBs after a level are pinned by vcon-stp4/7/8, which then
            //  parse exactly, but the AC codes of the other contexts are still unknown.
            throw new MediaException.Unsupported("mpeg-4 core studio inter vops",
                    "vop_prediction_type " + type + " of a Studio VOP");
        }
        // top_field_first, repeat_first_field and progressive_frame
        reader.skip(3);
        predictorsReset = reader.readFlag();
        alternateScan = reader.readFlag();
        framePredFrameDct = reader.readFlag();
        dctPrecision = reader.read(2);
        intraDcPrecision = reader.read(2);
        nonLinearScale = reader.readFlag();
        if (reader.readFlag()) {
            reader.skip(1 + 3 + 1 + 7 + 8);
        }
        while (reader.readFlag()) {
            reader.skip(8);
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated Studio VOP header");
        }
        var position = headerEnd;
        var count = 0;
        var slices = sliceBuffer(data, position, end);
        while (position + 3 < end) {
            var code = data[position + 3] & 0xFF;
            var next = nextStartCode(data, position + 4, end);
            if (code == StudioLayer.EXTENSION_START) {
                if (count == 0) {
                    readVopExtension(data, position + 4, next - position - 4);
                }
            } else if (code == SLICE_START) {
                slices[count * 2] = position + 4;
                slices[count * 2 + 1] = next;
                count++;
            }
            position = next;
        }
        if (structure == FRAME) {
            pendingField = -1;
        }
        var parity = structure == BOTTOM_FIELD ? 1 : 0;
        decodeSlices(data, slices, count, parity);
        if (structure == FRAME) {
            return true;
        }
        if (pendingField >= 0 && pendingField != parity) {
            pendingField = -1;
            return true;
        }
        pendingField = parity;
        return false;
    }

    /**
     * The slice start code value after the prefix.
     */
    private static final int SLICE_START = 0xB7;

    /**
     * Slice boundaries of the VOP being decoded: pairs of start and end indices.
     */
    private int[] sliceBounds = new int[0];

    /**
     * Returns a buffer large enough for the slices of a VOP.
     *
     * @param data the bytes
     * @param from the first index of the VOP's slices
     * @param end the index after the VOP
     * @return the buffer
     */
    private int[] sliceBuffer(byte[] data, int from, int end) {
        var count = 0;
        for (var i = from; i + 3 < end; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                count++;
            }
        }
        if (sliceBounds.length < count * 2) {
            sliceBounds = new int[count * 2];
        }
        return sliceBounds;
    }

    /**
     * The holder the quant matrix extensions of VOPs are read into.
     */
    private final StudioLayer extensionScratch = new StudioLayer();

    /**
     * Reads the extension data after a VOP header: a quant matrix extension updates the matrices.
     *
     * @param data the bytes
     * @param offset the index after the extension start code
     * @param length the bytes up to the next start code
     */
    private void readVopExtension(byte[] data, int offset, int length) {
        var copy = extensionScratch;
        System.arraycopy(intraMatrix, 0, copy.intraMatrix, 0, 64);
        System.arraycopy(chromaIntraMatrix, 0, copy.chromaIntraMatrix, 0, 64);
        copy.readExtension(data, offset, length, headerReader, false);
        System.arraycopy(copy.intraMatrix, 0, intraMatrix, 0, 64);
        System.arraycopy(copy.chromaIntraMatrix, 0, chromaIntraMatrix, 0, 64);
    }

    /**
     * Decodes the slices of a VOP, in parallel bands.
     *
     * @param data the bytes
     * @param bounds the slice boundaries
     * @param count the number of slices
     * @param parity the field parity of a field VOP
     */
    private void decodeSlices(byte[] data, int[] bounds, int count, int parity) {
        if (count == 0) {
            return;
        }
        var bands = Math.min(ParallelBands.bands(count, (long) (bounds[count * 2 - 1] - bounds[0]) * 64),
                workers.length);
        sliceData = data;
        sliceCount = count;
        sliceParity = parity;
        sliceBands = bands;
        failure = null;
        try {
            ParallelBands.run(bands, sliceBand);
        } finally {
            sliceData = null;
        }
        if (failure != null) {
            var first = failure;
            failure = null;
            throw first;
        }
    }

    /**
     * The bytes, number of slices, field parity and number of bands of the VOP whose slices are being decoded.
     */
    private byte[] sliceData;
    private int sliceCount;
    private int sliceParity;
    private int sliceBands;

    /**
     * The first failure of a slice of the VOP being decoded, or {@code null}; guarded by {@link #failureLock}.
     */
    private MediaException failure;
    private final Object failureLock = new Object();

    /**
     * Decodes the slices of one band of the VOP being decoded.
     */
    private final ParallelBands.Band sliceBand = this::decodeBand;

    /**
     * Decodes the slices of one band.
     *
     * @param band the band index
     */
    private void decodeBand(int band) {
        var worker = workers[band];
        if (worker == null) {
            worker = new Slice();
            workers[band] = worker;
        }
        var from = ParallelBands.start(band, sliceBands, sliceCount);
        var to = ParallelBands.start(band + 1, sliceBands, sliceCount);
        for (var i = from; i < to; i++) {
            try {
                worker.decode(sliceData, sliceBounds[i * 2], sliceBounds[i * 2 + 1], sliceParity);
            } catch (MediaException exception) {
                synchronized (failureLock) {
                    if (failure == null) {
                        failure = exception;
                    }
                }
            }
        }
    }

    /**
     * Returns the index of the next start code prefix, or the end.
     *
     * @param data the bytes
     * @param from the index to search from
     * @param end the index after the last byte
     * @return the index
     */
    static int nextStartCode(byte[] data, int from, int end) {
        for (var i = from; i + 2 < end; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
        }
        return end;
    }

    /**
     * The state of one slice decoder: a reader and the scratch blocks, owned by one band.
     */
    private final class Slice {
        private final BitReader reader = new BitReader();
        private final StudioIdct idct = new StudioIdct();
        private final int[] levels = new int[64];
        private final int[] coefficients = new int[64];
        private final int[] samples = new int[64];
        private final int[][] mbBlocks = new int[12][64];
        private final int[] residuals = new int[256];
        private final int[] dpcm = new int[256];
        private final int[] predictors = new int[3];

        /**
         * Decodes one slice.
         *
         * @param data the bytes
         * @param from the index after the slice start code
         * @param to the index of the next start code
         * @param parity the field parity of a field VOP
         */
        void decode(byte[] data, int from, int to, int parity) {
            var reader = this.reader;
            reader.load(data, from, to - from);
            var frame = structure == FRAME;
            var rows = frame ? mbHeight : mbHeight >> 1;
            var total = mbWidth * rows;
            var addressBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(total));
            var address = reader.read(addressBits);
            var quantiserCode = reader.read(5);
            if (reader.readFlag()) {
                reader.skip(8);
                while (reader.readFlag()) {
                    reader.skip(8);
                }
            }
            if (address >= total) {
                throw new MediaException.Malformed("Studio slice address " + address + " outside the VOP");
            }
            var bits = layer.bitsPerPixel;
            var reset = 1 << (bits + dctPrecision + intraDcPrecision - 1);
            predictors[0] = reset;
            predictors[1] = reset;
            predictors[2] = reset;
            var mb = address;
            while (true) {
                if (mb >= total) {
                    throw new MediaException.Malformed("Studio slice runs past the end of the VOP");
                }
                if (predictorsReset) {
                    predictors[0] = reset;
                    predictors[1] = reset;
                    predictors[2] = reset;
                }
                var dct = reader.readFlag();
                if (!reader.readFlag()) {
                    if (!reader.readFlag()) {
                        throw new MediaException.Malformed("invalid Studio intra macroblock_type");
                    }
                    quantiserCode = -1;
                }
                var fieldDct = false;
                if (dct && frame && !layer.progressive && !framePredFrameDct) {
                    fieldDct = reader.readFlag();
                }
                if (quantiserCode < 0) {
                    quantiserCode = reader.read(5);
                }
                if (quantiserCode == 0) {
                    throw new MediaException.Malformed("zero Studio quantiser_scale_code");
                }
                var mbX = mb % mbWidth;
                var mbY = mb / mbWidth;
                if (dct) {
                    decodeDctMacroblock(mbX, mbY, quantiserCode, fieldDct, frame, parity);
                } else {
                    decodeDpcmMacroblock(mbX, mbY, frame, parity);
                }
                if (reader.overrun()) {
                    throw new MediaException.Malformed("truncated Studio slice");
                }
                mb++;
                if (reader.remaining() < 8 || reader.peek(23) == 0) {
                    break;
                }
            }
        }

        /**
         * Decodes a DCT coded intra macroblock.
         */
        private void decodeDctMacroblock(int mbX, int mbY, int quantiserCode, boolean fieldDct, boolean frame,
                                         int parity) {
            var scale = quantiserScale(quantiserCode);
            var bits = layer.bitsPerPixel;
            var escapeBits = bits + dctPrecision + 4;
            var scan = alternateScan ? ALTERNATE : StudioLayer.ZIGZAG;
            var maximum = (1 << bits) - 1;
            var mpeg2 = layer.mpeg2Stream;
            var range = mpeg2 ? 1 << (bits + 3) : 1 << (bits + 6);
            var dcScale = 8 >> intraDcPrecision;
            for (var k = 0; k < blocks; k++) {
                var chroma = k >= 4;
                var component = chroma ? 1 + (k & 1) : 0;
                var diff = dcDifferential(chroma);
                readCoefficients(escapeBits);
                predictors[component] += diff;
                var dc = predictors[component];
                var matrix = chroma ? chromaIntraMatrix : intraMatrix;
                long dcValue = mpeg2 ? (long) dc * dcScale : ((long) dc * dcScale * 8) >> dctPrecision;
                coefficients[0] = (int) dcValue;
                var sum = coefficients[0];
                var any = coefficients[0] != 0;
                for (var i = 1; i < 64; i++) {
                    var level = levels[i];
                    var position = scan[i];
                    if (level == 0) {
                        coefficients[position] = 0;
                        continue;
                    }
                    long value = 2L * level * matrix[position] * scale;
                    if (!mpeg2) {
                        value *= 8;
                    }
                    var divisor = mpeg2 ? 32L : 32L << dctPrecision;
                    value = value >= 0 ? value / divisor : -(-value / divisor);
                    var clipped = (int) Math.max(-range, Math.min(range - 1, value));
                    coefficients[position] = clipped;
                    sum += clipped;
                    any |= clipped != 0;
                }
                if (any && (sum & 1) == 0) {
                    coefficients[63] += (coefficients[63] & 1) != 0 ? -1 : 1;
                }
                idct.apply(coefficients, mpeg2 ? 1.0 : 8.0, samples);
                var out = mbBlocks[k];
                for (var i = 0; i < 64; i++) {
                    var v = samples[i];
                    out[i] = v < 0 ? 0 : Math.min(v, maximum);
                }
            }
            storeDctBlocks(mbX, mbY, fieldDct, frame, parity);
        }

        /**
         * Reads the AC coefficient levels of one intra block into {@link #levels} in scan order.
         *
         * @param escapeBits the length of an escaped level
         */
        private void readCoefficients(int escapeBits) {
            Arrays.fill(levels, 0);
            var reader = this.reader;
            var index = 1;
            var context = 0;
            while (true) {
                var group = StudioTables.AC_VLC[context].decode(reader);
                if (group == Vlc.INVALID) {
                    throw new MediaException.Malformed("invalid Studio AC coefficient group code");
                }
                if (group == 0) {
                    return;
                }
                if (index >= 64) {
                    throw new MediaException.Malformed("Studio block has more than 64 coefficients");
                }
                if (group <= 6) {
                    var add = reader.read(group);
                    index += (1 << (group - 1)) | (add >>> 1);
                    if (index >= 64) {
                        throw new MediaException.Malformed("Studio coefficient run past the end of the block");
                    }
                    levels[index] = (add & 1) != 0 ? 1 : -1;
                    context = 16;
                } else if (group <= 13) {
                    var size = group - 8;
                    var add = size == 0 ? 0 : reader.read(size);
                    index += ((1 << size) | add) - 1;
                    if (index >= 64) {
                        throw new MediaException.Malformed("Studio zero run past the end of the block");
                    }
                    context = 8;
                } else if (group <= 24) {
                    var size = group - 16;
                    var add = reader.read(size);
                    levels[index] = (add >>> (size - 1)) != 0 ? add : -(add ^ ((1 << size) - 1));
                    context = group;
                } else {
                    var add = reader.read(escapeBits);
                    var mask = (1 << escapeBits) - 1;
                    levels[index] = (add >>> (escapeBits - 1)) != 0 ? -((add ^ mask) + 1) : add;
                    context = 25;
                }
                index++;
            }
        }

        /**
         * Reads a DC differential.
         *
         * @param chroma whether the block is a chrominance block
         * @return the differential
         */
        private int dcDifferential(boolean chroma) {
            var size = (chroma ? StudioTables.DC_CHROMA_VLC : StudioTables.DC_LUMA_VLC).decode(reader);
            if (size == Vlc.INVALID) {
                throw new MediaException.Malformed("invalid Studio DC size code");
            }
            if (size == 0) {
                return 0;
            }
            var code = reader.read(size);
            var value = (code >>> (size - 1)) != 0 ? code : -(code ^ ((1 << size) - 1));
            if (size > 8) {
                reader.skip(1);
            }
            return value;
        }

        /**
         * Copies the blocks of a DCT macroblock into the planes.
         */
        private void storeDctBlocks(int mbX, int mbY, boolean fieldDct, boolean frame, int parity) {
            for (var k = 0; k < 4; k++) {
                var block = mbBlocks[k];
                var column = (mbX << 4) + ((k & 1) << 3);
                for (var r = 0; r < 8; r++) {
                    var row = fieldDct ? (mbY << 4) + 2 * r + (k >> 1) : (mbY << 4) + ((k >> 1) << 3) + r;
                    store(y, codedWidth, column, row, frame, parity, block, r);
                }
            }
            var mbChromaWidth = 16 >> shiftX;
            var mbChromaHeight = 16 >> shiftY;
            var columns = mbChromaWidth >> 3;
            var chromaField = fieldDct && shiftY == 0;
            for (var k = 4; k < blocks; k++) {
                var block = mbBlocks[k];
                var plane = (k & 1) == 0 ? cb : cr;
                var n = (k - 4) >> 1;
                var blockRow = mbChromaHeight == 8 ? 0 : n & 1;
                var blockColumn = mbChromaHeight == 8 ? n : n >> 1;
                if (columns == 1) {
                    blockColumn = 0;
                }
                var column = mbX * mbChromaWidth + (blockColumn << 3);
                for (var r = 0; r < 8; r++) {
                    var row = chromaField ? mbY * mbChromaHeight + 2 * r + blockRow
                            : mbY * mbChromaHeight + (blockRow << 3) + r;
                    store(plane, chromaWidth, column, row, frame, parity, block, r);
                }
            }
        }

        /**
         * Stores one row of eight samples, mapping a field VOP row to its frame line.
         */
        private void store(short[] plane, int stride, int column, int row, boolean frame, int parity, int[] block,
                           int r) {
            var line = frame ? row : 2 * row + parity;
            var base = line * stride + column;
            for (var c = 0; c < 8; c++) {
                plane[base + c] = (short) block[r * 8 + c];
            }
        }

        /**
         * Decodes a DPCM coded macroblock.
         */
        private void decodeDpcmMacroblock(int mbX, int mbY, boolean frame, int parity) {
            var reverse = reader.readFlag();
            var shift = dctPrecision + intraDcPrecision;
            predictors[0] = decodeDpcmBlock(16, 16, reverse) << shift;
            storeDpcm(y, codedWidth, mbX << 4, mbY << 4, 16, 16, frame, parity);
            var width = 16 >> shiftX;
            var height = 16 >> shiftY;
            predictors[1] = decodeDpcmBlock(width, height, reverse) << shift;
            storeDpcm(cb, chromaWidth, mbX * width, mbY * height, width, height, frame, parity);
            predictors[2] = decodeDpcmBlock(width, height, reverse) << shift;
            storeDpcm(cr, chromaWidth, mbX * width, mbY * height, width, height, frame, parity);
        }

        /**
         * Copies a DPCM block into a plane.
         */
        private void storeDpcm(short[] plane, int stride, int x, int y, int width, int height, boolean frame,
                               int parity) {
            for (var r = 0; r < height; r++) {
                var line = frame ? y + r : 2 * (y + r) + parity;
                var base = line * stride + x;
                for (var c = 0; c < width; c++) {
                    plane[base + c] = (short) dpcm[r * width + c];
                }
            }
        }

        /**
         * Decodes one DPCM block into {@link #dpcm}.
         *
         * @param width the block width
         * @param height the block height
         * @param reverse whether the lines are coded bottom up ({@code dpcm_scan_order})
         * @return the {@code block_mean}
         */
        private int decodeDpcmBlock(int width, int height, boolean reverse) {
            var reader = this.reader;
            var bits = layer.bitsPerPixel;
            var mean = reader.read(bits);
            var rice = reader.read(4);
            if (rice == 15) {
                rice = 0;
            }
            var count = width * height;
            for (var i = 0; i < count; i++) {
                var prefix = 0;
                while (!reader.readFlag()) {
                    prefix++;
                    if (prefix > ESCAPE_PREFIX) {
                        throw new MediaException.Malformed("invalid Studio DPCM residual prefix");
                    }
                }
                residuals[i] = prefix == ESCAPE_PREFIX ? reader.read(bits)
                        : (prefix << rice) + (rice == 0 ? 0 : reader.read(rice));
            }
            var mask = (1 << bits) - 1;
            var start = 1 << (bits - 1);
            for (var i = 0; i < height; i++) {
                var current = start;
                var top = start;
                var line = reverse ? height - 1 - i : i;
                for (var j = 0; j < width; j++) {
                    var left = current;
                    var topLeft = top;
                    if (i != 0) {
                        top = dpcm[(reverse ? line + 1 : line - 1) * width + j];
                    }
                    var prediction = top + left - topLeft;
                    var min = Math.min(top, left);
                    var max = top + left - min;
                    if (prediction < min) {
                        prediction = min;
                    } else if (prediction > max) {
                        prediction = max;
                    }
                    var outerMin = Math.min(topLeft, min);
                    var outerMax = Math.max(topLeft, max);
                    var second = (outerMin + outerMax) >> 1;
                    if (prediction == second) {
                        second = mean;
                    }
                    var residual = residuals[i * width + j];
                    if ((residual & 1) != 0) {
                        residual = -residual;
                    }
                    residual >>= 1;
                    if (second > prediction) {
                        residual = -residual;
                    }
                    current = (prediction + residual) & mask;
                    dpcm[line * width + j] = current;
                }
            }
            return mean;
        }
    }

    /**
     * Returns the quantiser scale of a quantiser scale code.
     *
     * @param code the code, 1 to 31
     * @return the scale
     */
    private int quantiserScale(int code) {
        if (!nonLinearScale) {
            return 2 * code;
        }
        if (code < 9) {
            return code;
        }
        if (code < 17) {
            return 2 * (code - 9) + 10;
        }
        if (code < 25) {
            return 4 * (code - 17) + 28;
        }
        return 8 * (code - 25) + 64;
    }
}
