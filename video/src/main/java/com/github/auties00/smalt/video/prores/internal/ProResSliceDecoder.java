package com.github.auties00.smalt.video.prores.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.video.intra.internal.BlockKernel;
import com.github.auties00.smalt.video.intra.internal.IntraBitReader;

import java.util.Arrays;

/**
 * Decodes the slices of ProRes pictures (SMPTE RDD 36 clauses 5.3 and 7) into the planes of a
 * {@link ProResTarget}: entropy decoding, inverse scanning and inverse quantization in one pass,
 * then the inverse transform and the sample conversion of {@link BlockKernel} in batches of eight
 * blocks, and the run-length decoding of the alpha channel.
 *
 * <p>Instances hold the scratch buffers of one decoding thread and are not thread-safe; the
 * frame decoder gives every parallel band its own.
 */
public final class ProResSliceDecoder {
    /**
     * The most blocks one component of a slice holds: four per macroblock of an eight-macroblock
     * slice.
     */
    private static final int MAX_BLOCKS = 32;

    /**
     * The bit reader.
     */
    private final IntraBitReader reader = new IntraBitReader();

    /**
     * The dequantized coefficients of one component, in batches of {@link BlockKernel#LANES}
     * blocks: block {@code b} is lane {@code b % 8} of batch {@code b / 8}.
     */
    private final float[][] batches = new float[MAX_BLOCKS / BlockKernel.LANES][BlockKernel.BATCH_LENGTH];

    /**
     * The integer samples of one batch.
     */
    private final int[] samples = new int[BlockKernel.BATCH_LENGTH];

    /**
     * The dequantization factor {@code W * qScale / 8} of every raster position for luma.
     */
    private final float[] lumaWeights = new float[64];

    /**
     * The dequantization factor of every raster position for chroma.
     */
    private final float[] chromaWeights = new float[64];

    /**
     * The decoded alpha values of a slice, in raster order.
     */
    private int[] alpha = new int[0];

    /**
     * Constructs a slice decoder.
     */
    public ProResSliceDecoder() {
    }

    /**
     * Decodes one slice.
     *
     * @param data the array holding the frame
     * @param start the index of the slice's first byte
     * @param size the coded size of the slice in bytes
     * @param header the frame header
     * @param scan the raster position of every scan index
     * @param target where the samples go
     * @param sliceRow the macroblock row of the slice in its picture
     * @param mbX the first macroblock column of the slice
     * @param mbCount the number of macroblocks of the slice: 1, 2, 4 or 8
     * @throws MediaException.Malformed if the slice is corrupt
     */
    public void decode(byte[] data, int start, int size, ProResFrameHeader header, int[] scan, ProResTarget target,
                       int sliceRow, int mbX, int mbCount) {
        if (size < 1) {
            throw new MediaException.Malformed("empty ProRes slice");
        }
        var headerSize = (data[start] & 0xFF) >>> 3;
        var hasAlpha = header.alphaType() != 0;
        var minimum = hasAlpha ? 8 : 6;
        if (headerSize < minimum || headerSize > size) {
            throw new MediaException.Malformed("invalid ProRes slice header size " + headerSize);
        }
        var quantizationIndex = data[start + 1] & 0xFF;
        if (quantizationIndex < 1 || quantizationIndex > ProResTables.MAX_QUANTIZATION_INDEX) {
            throw new MediaException.Malformed("reserved ProRes quantization index " + quantizationIndex);
        }
        var ySize = ByteArrays.u16be(data, start + 2);
        var cbSize = ByteArrays.u16be(data, start + 4);
        var available = size - headerSize;
        var crSize = hasAlpha ? ByteArrays.u16be(data, start + 6) : available - ySize - cbSize;
        if (ySize + cbSize > available || crSize < 0 || ySize + cbSize + crSize > available) {
            throw new MediaException.Malformed("ProRes slice component sizes overrun the slice");
        }
        var qScale = ProResTables.scale(quantizationIndex) * 0.125f;
        var luma = header.lumaMatrix();
        var chroma = header.chromaMatrix();
        for (var i = 0; i < 64; i++) {
            lumaWeights[i] = luma[i] * qScale;
            chromaWeights[i] = chroma[i] * qScale;
        }
        var log2Mbs = Integer.numberOfTrailingZeros(mbCount);
        var chroma444 = header.chromaFormat() == 3;
        var yStart = start + headerSize;
        var top = sliceRow * 16;
        component(data, yStart, yStart + ySize, 2 + log2Mbs, lumaWeights, scan);
        storeLuma(target, mbX * 16, top, 4 << log2Mbs);
        var cbStart = yStart + ySize;
        var chromaLog2 = (chroma444 ? 2 : 1) + log2Mbs;
        component(data, cbStart, cbStart + cbSize, chromaLog2, chromaWeights, scan);
        storeChroma(target, false, chroma444, mbX, top, 1 << chromaLog2);
        var crStart = cbStart + cbSize;
        component(data, crStart, crStart + crSize, chromaLog2, chromaWeights, scan);
        storeChroma(target, true, chroma444, mbX, top, 1 << chromaLog2);
        if (hasAlpha) {
            var rows = Math.min(16, target.pictureHeight() - top);
            var alphaStart = crStart + crSize;
            alpha(data, alphaStart, start + size, header.alphaType(), 16 * mbCount * rows);
            storeAlpha(target, header.alphaType(), mbX * 16, top, 16 * mbCount, rows);
        }
    }

    /**
     * Entropy-decodes and dequantizes the scanned coefficients of one component of a slice
     * (RDD 36 clauses 5.3.2, 7.1.1, 7.2 and 7.3) into {@link #batches}.
     *
     * @param data the frame
     * @param start the index of the component's first byte
     * @param end the index one past its last byte
     * @param log2Blocks the base-2 logarithm of the number of blocks of the component
     * @param weights the dequantization factor of every raster position
     * @param scan the raster position of every scan index
     * @throws MediaException.Malformed if the component's data is corrupt
     */
    private void component(byte[] data, int start, int end, int log2Blocks, float[] weights, int[] scan) {
        var blocks = 1 << log2Blocks;
        var batches = (blocks + BlockKernel.LANES - 1) / BlockKernel.LANES;
        var all = this.batches;
        for (var batch = 0; batch < batches; batch++) {
            Arrays.fill(all[batch], 0f);
        }
        var in = reader;
        in.reset(data, start, end);
        var first = ProResCodes.decode(in, ProResTables.FIRST_DC);
        if (first < 0) {
            throw malformed();
        }
        var dc = ProResCodes.fromSymbol(first);
        var dcWeight = weights[0];
        all[0][0] = dc * dcWeight;
        var previousDifference = ProResTables.INITIAL_DC_DIFFERENCE;
        for (var block = 1; block < blocks; block++) {
            var magnitude = Math.min(Math.abs(previousDifference), 3);
            var symbol = ProResCodes.decode(in, ProResTables.DC_DIFFERENCE[magnitude]);
            if (symbol < 0) {
                throw malformed();
            }
            var difference = ProResCodes.fromSymbol(symbol);
            if (previousDifference < 0) {
                difference = -difference;
            }
            dc += difference;
            previousDifference = difference;
            all[block >>> 3][block & 7] = dc * dcWeight;
        }
        var total = blocks << 6;
        var index = blocks;
        var previousRun = ProResTables.INITIAL_RUN;
        var previousLevel = ProResTables.INITIAL_LEVEL;
        var mask = blocks - 1;
        var bytes = in.array();
        var begin = in.position();
        var endBits = begin + in.remaining();
        var nextByte = (begin >>> 3) + 8;
        var cacheBits = 64 - (begin & 7);
        var cache = IntraBitReader.word(bytes, begin >>> 3) << (begin & 7);
        while (true) {
            if (cacheBits < 56) {
                cache |= IntraBitReader.word(bytes, nextByte) >>> cacheBits;
                var take = (63 - cacheBits) >>> 3;
                nextByte += take;
                cacheBits += take << 3;
            }
            var remaining = endBits - (nextByte * 8 - cacheBits);
            if (remaining <= 0 || (remaining < 32 && cache >>> (64 - remaining) == 0)) {
                break;
            }
            var runCode = ProResCodes.peek(cache, ProResTables.RUN[Math.min(previousRun, 15)]);
            if (runCode < 0) {
                throw malformed();
            }
            var runBits = (int) (runCode & 0xFF);
            var run = (int) (runCode >>> 8);
            previousRun = run;
            index += run;
            cache <<= runBits;
            cacheBits -= runBits;
            if (cacheBits < 48) {
                cache |= IntraBitReader.word(bytes, nextByte) >>> cacheBits;
                var take = (63 - cacheBits) >>> 3;
                nextByte += take;
                cacheBits += take << 3;
            }
            var levelCode = ProResCodes.peek(cache, ProResTables.LEVEL[Math.min(previousLevel, 8)]);
            var levelSymbol = (int) (levelCode >>> 8);
            if (levelCode < 0 || index >= total) {
                throw malformed();
            }
            var levelBits = (int) (levelCode & 0xFF);
            previousLevel = levelSymbol;
            cache <<= levelBits;
            var sign = (int) (cache >> 63);
            cache <<= 1;
            cacheBits -= levelBits + 1;
            var level = ((levelSymbol + 1) ^ sign) - sign;
            var block = index & mask;
            var position = scan[index >>> log2Blocks];
            all[block >>> 3][position * BlockKernel.LANES + (block & 7)] = level * weights[position];
            index++;
        }
        in.seek(nextByte * 8 - cacheBits);
        if (in.overrun()) {
            throw malformed();
        }
    }

    /**
     * Transforms the luma blocks of a slice and writes them to the target.
     *
     * @param target the output planes
     * @param left the slice's first luma column
     * @param top the slice's first luma row in its picture
     * @param blocks the number of blocks
     */
    private void storeLuma(ProResTarget target, int left, int top, int blocks) {
        var kernel = BlockKernel.INSTANCE;
        for (var batch = 0; batch * BlockKernel.LANES < blocks; batch++) {
            var values = batches[batch];
            kernel.inverse(values);
            kernel.toSamples(values, target.scale(), target.offset(), target.limit(), samples);
            var lanes = Math.min(BlockKernel.LANES, blocks - batch * BlockKernel.LANES);
            for (var lane = 0; lane < lanes; lane++) {
                var block = batch * BlockKernel.LANES + lane;
                var sub = block & 3;
                var x = left + (block >>> 2) * 16 + (sub & 1) * 8;
                var y = top + (sub >>> 1) * 8;
                if (target.wide()) {
                    store16(lane, target.luma16(), target, target.lumaStride(), x, y);
                } else {
                    store8(lane, target.luma8(), target, target.lumaStride(), x, y);
                }
            }
        }
    }

    /**
     * Transforms the blocks of one chroma component of a slice and writes them to the target.
     *
     * @param target the output planes
     * @param red {@code true} for Cr, {@code false} for Cb
     * @param chroma444 whether the frame is 4:4:4
     * @param mbX the slice's first macroblock column
     * @param top the slice's first row in its picture
     * @param blocks the number of blocks
     */
    private void storeChroma(ProResTarget target, boolean red, boolean chroma444, int mbX, int top, int blocks) {
        var kernel = BlockKernel.INSTANCE;
        for (var batch = 0; batch * BlockKernel.LANES < blocks; batch++) {
            var values = batches[batch];
            kernel.inverse(values);
            kernel.toSamples(values, target.scale(), target.offset(), target.limit(), samples);
            var lanes = Math.min(BlockKernel.LANES, blocks - batch * BlockKernel.LANES);
            for (var lane = 0; lane < lanes; lane++) {
                var block = batch * BlockKernel.LANES + lane;
                int x;
                int y;
                if (chroma444) {
                    var sub = block & 3;
                    x = (mbX + (block >>> 2)) * 16 + (sub >>> 1) * 8;
                    y = top + (sub & 1) * 8;
                } else {
                    x = (mbX + (block >>> 1)) * 8;
                    y = top + (block & 1) * 8;
                }
                if (target.wide()) {
                    store16(lane, red ? target.cr16() : target.cb16(), target, target.chromaStride(), x, y);
                } else {
                    store8(lane, red ? target.cr8() : target.cb8(), target, target.chromaStride(), x, y);
                }
            }
        }
    }

    /**
     * Writes one block of {@link #samples} into a 16-bit plane.
     *
     * @param lane the block's lane
     * @param plane the 16-bit output plane
     * @param target the output description
     * @param stride the plane's row stride
     * @param x the block's first column
     * @param y the block's first row in its picture
     */
    private void store16(int lane, short[] plane, ProResTarget target, int stride, int x, int y) {
        var step = stride * target.rowStep();
        var at = (y * target.rowStep() + target.rowOffset()) * stride + x;
        var s = samples;
        for (var row = 0; row < 8; row++, at += step) {
            var from = row * 64 + lane;
            plane[at] = (short) s[from];
            plane[at + 1] = (short) s[from + 8];
            plane[at + 2] = (short) s[from + 16];
            plane[at + 3] = (short) s[from + 24];
            plane[at + 4] = (short) s[from + 32];
            plane[at + 5] = (short) s[from + 40];
            plane[at + 6] = (short) s[from + 48];
            plane[at + 7] = (short) s[from + 56];
        }
    }

    /**
     * Writes one block of {@link #samples} into an 8-bit plane.
     *
     * @param lane the block's lane
     * @param plane the 8-bit output plane
     * @param target the output description
     * @param stride the plane's row stride
     * @param x the block's first column
     * @param y the block's first row in its picture
     */
    private void store8(int lane, byte[] plane, ProResTarget target, int stride, int x, int y) {
        var step = stride * target.rowStep();
        var at = (y * target.rowStep() + target.rowOffset()) * stride + x;
        var s = samples;
        for (var row = 0; row < 8; row++, at += step) {
            var from = row * 64 + lane;
            plane[at] = (byte) s[from];
            plane[at + 1] = (byte) s[from + 8];
            plane[at + 2] = (byte) s[from + 16];
            plane[at + 3] = (byte) s[from + 24];
            plane[at + 4] = (byte) s[from + 32];
            plane[at + 5] = (byte) s[from + 40];
            plane[at + 6] = (byte) s[from + 48];
            plane[at + 7] = (byte) s[from + 56];
        }
    }

    /**
     * Decodes the run-length coded alpha values of a slice (RDD 36 clauses 5.3.3 and 7.1.2) into
     * {@link #alpha}.
     *
     * @param data the frame
     * @param start the index of the alpha data's first byte
     * @param end the index one past the slice
     * @param alphaType {@code 1} for 8-bit alpha, {@code 2} for 16-bit alpha
     * @param count the number of alpha values of the slice
     * @throws MediaException.Malformed if the alpha data is corrupt
     */
    private void alpha(byte[] data, int start, int end, int alphaType, int count) {
        if (alpha.length < count) {
            alpha = new int[count];
        }
        var values = alpha;
        var in = reader;
        in.reset(data, start, end);
        var wide = alphaType == 2;
        var mask = wide ? 0xFFFF : 0xFF;
        var magnitudeBits = wide ? 6 : 3;
        var escapeBits = wide ? 16 : 8;
        var previous = -1;
        var n = 0;
        while (n < count) {
            int value;
            if (in.readBit() == 0) {
                var code = in.read(magnitudeBits + 1);
                var magnitude = (code >>> 1) + 1;
                value = previous + ((code & 1) != 0 ? -magnitude : magnitude);
            } else {
                value = previous + in.read(escapeBits);
            }
            value &= mask;
            previous = value;
            int run;
            if (in.readBit() != 0) {
                run = 1;
            } else {
                var code = in.read(4);
                run = code != 0 ? code + 1 : in.read(11) + 1;
            }
            if (in.overrun()) {
                throw malformed();
            }
            // prores_ks codes the alpha of the bottom slices of a picture whose height is not a
            // multiple of 16 as if they were 16 rows tall; the excess is dropped
            run = Math.min(run, count - n);
            Arrays.fill(values, n, n + run, value);
            n += run;
        }
    }

    /**
     * Converts the alpha values of a slice to the output depth and writes them to the target.
     *
     * @param target the output planes
     * @param alphaType {@code 1} for 8-bit alpha, {@code 2} for 16-bit alpha
     * @param left the slice's first column
     * @param top the slice's first row in its picture
     * @param width the number of alpha values of a slice row
     * @param rows the number of rows
     */
    private void storeAlpha(ProResTarget target, int alphaType, int left, int top, int width, int rows) {
        var stride = target.lumaStride();
        var step = stride * target.rowStep();
        var at = (top * target.rowStep() + target.rowOffset()) * stride + left;
        var maxIn = alphaType == 2 ? 65535 : 255;
        var maxOut = (1 << target.depth()) - 1;
        var identity = maxIn == maxOut;
        var half = maxIn / 2;
        var values = alpha;
        for (var row = 0; row < rows; row++, at += step) {
            var from = row * width;
            for (var x = 0; x < width; x++) {
                var value = values[from + x];
                var sample = identity ? value : (int) (((long) value * maxOut + half) / maxIn);
                if (target.wide()) {
                    target.alpha16()[at + x] = (short) sample;
                } else {
                    target.alpha8()[at + x] = (byte) sample;
                }
            }
        }
    }

    /**
     * Creates the exception of a corrupt slice.
     *
     * @return the exception
     */
    private static MediaException.Malformed malformed() {
        return new MediaException.Malformed("corrupt ProRes slice data");
    }
}
