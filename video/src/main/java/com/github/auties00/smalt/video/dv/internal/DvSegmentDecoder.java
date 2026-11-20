package com.github.auties00.smalt.video.dv.internal;

import java.util.Arrays;

/**
 * Decodes the coefficients of the five compressed macroblocks of a DV video segment, following the three passes
 * of SMPTE ST 314 clause 5.6 and SMPTE ST 370 clause 4.6: each block from its own compressed-data area, then the
 * blocks that overflowed their area from the unused space of the areas of their macroblock, then those that still
 * overflowed from the unused space left in the segment.
 *
 * <p>A block whose data ends before its end of block keeps the coefficients read so far; a code word that is not
 * in the table or that runs past the last coefficient ends the block. After {@link #decode} the levels of block
 * {@code b} of macroblock {@code q} are at {@code levels[(q * blocks + b) * 64 + position]}, raster positions,
 * with its DC value, DCT mode and class number, and the macroblock's QNO.
 *
 * <p>Instances are not thread-safe; every decoding thread owns one.
 */
final class DvSegmentDecoder {
    /**
     * The byte boundaries of the compressed-data areas of an SD compressed macroblock.
     */
    static final int[] SD_AREAS = {4, 18, 32, 46, 60, 70, 80};

    /**
     * The byte boundaries of the compressed-data areas of a DVCPRO HD compressed macroblock.
     */
    static final int[] HD_AREAS = {4, 14, 24, 34, 44, 54, 64, 72, 80};

    /**
     * The number of bits of a DC word: the DC value, the DCT mode and the class number.
     */
    private static final int DC_BITS = 12;

    /**
     * The number of bits of the reserved word that fills the second and fourth areas of an SD 4:2:2 compressed
     * macroblock, end of block included.
     */
    private static final int RESERVED_BITS = 16;

    final int blocks;
    final boolean highDefinition;
    final int[] areas;
    final boolean pseudo;
    final int[] levels;
    final int[] dc;
    final int[] mode;
    final int[] classes;
    final int[] qno = new int[5];
    private final int[] next;
    private final boolean[] done;
    private final int[] freeStart;
    private final int[] freeEnd;
    private final DvBitBuffer[] pending;
    private final DvBitBuffer pool = new DvBitBuffer(5 * 76 * 8);
    private final DvBitBuffer combined = new DvBitBuffer(2 * 5 * 76 * 8);
    private final DvBitBuffer segmentPool = new DvBitBuffer(5 * 76 * 8);
    private final boolean[] overflowed = new boolean[5];

    /**
     * Constructs a decoder for one kind of compressed macroblock.
     *
     * @param highDefinition whether the macroblocks are DVCPRO HD ones, of eight blocks
     * @param reservedAreas whether the second and fourth areas hold the reserved word of SD 4:2:2
     */
    DvSegmentDecoder(boolean highDefinition, boolean reservedAreas) {
        this.highDefinition = highDefinition;
        this.areas = highDefinition ? HD_AREAS : SD_AREAS;
        this.blocks = areas.length - 1;
        this.pseudo = reservedAreas;
        this.levels = new int[5 * blocks * 64];
        this.dc = new int[5 * blocks];
        this.mode = new int[5 * blocks];
        this.classes = new int[5 * blocks];
        this.next = new int[5 * blocks];
        this.done = new boolean[5 * blocks];
        this.freeStart = new int[5 * blocks];
        this.freeEnd = new int[5 * blocks];
        this.pending = new DvBitBuffer[5 * blocks];
        for (var i = 0; i < pending.length; i++) {
            pending[i] = new DvBitBuffer(2 * 5 * 76 * 8);
        }
    }

    /**
     * Returns whether a block of a macroblock is the reserved word of SD 4:2:2 rather than a DCT block.
     *
     * @param block the block index
     * @return {@code true} for the second and fourth areas of an SD 4:2:2 compressed macroblock
     */
    boolean reserved(int block) {
        return pseudo && (block == 1 || block == 3);
    }

    /**
     * Decodes a segment.
     *
     * @param data the array holding the frame
     * @param offsets the byte offsets of the five DIF blocks of the segment in {@code data}
     */
    void decode(byte[] data, int[] offsets) {
        Arrays.fill(levels, 0);
        for (var q = 0; q < 5; q++) {
            var base = offsets[q];
            qno[q] = data[base + 3] & 0x0F;
            for (var b = 0; b < blocks; b++) {
                var index = q * blocks + b;
                var start = (base + areas[b]) << 3;
                var end = (base + areas[b + 1]) << 3;
                if (reserved(b)) {
                    done[index] = true;
                    freeStart[index] = start + RESERVED_BITS;
                    freeEnd[index] = end;
                    continue;
                }
                var word = DvBitBuffer.peek16(data, start) >>> 4;
                var value = word >>> 3;
                dc[index] = value >= 256 ? value - 512 : value;
                mode[index] = (word >>> 2) & 1;
                classes[index] = word & 3;
                next[index] = 1;
                done[index] = false;
                var stop = read(index, data, start + DC_BITS, end);
                if (done[index]) {
                    freeStart[index] = stop;
                    freeEnd[index] = end;
                } else {
                    freeStart[index] = end;
                    freeEnd[index] = end;
                    pending[index].clear();
                    pending[index].append(data, stop, end);
                }
            }
        }
        segmentPool.clear();
        for (var q = 0; q < 5; q++) {
            pool.clear();
            for (var b = 0; b < blocks; b++) {
                var index = q * blocks + b;
                pool.append(data, freeStart[index], freeEnd[index]);
            }
            var position = 0;
            overflowed[q] = false;
            for (var b = 0; b < blocks && !overflowed[q]; b++) {
                var index = q * blocks + b;
                if (done[index]) {
                    continue;
                }
                position = resume(index, pool, position);
                if (!done[index]) {
                    overflowed[q] = true;
                }
            }
            if (!overflowed[q]) {
                segmentPool.append(pool.data, position, pool.length);
            }
        }
        var position = 0;
        for (var q = 0; q < 5; q++) {
            if (!overflowed[q]) {
                continue;
            }
            for (var b = 0; b < blocks; b++) {
                var index = q * blocks + b;
                if (done[index]) {
                    continue;
                }
                position = resume(index, segmentPool, position);
                if (!done[index]) {
                    return;
                }
            }
        }
    }

    /**
     * Continues an overflowing block with its pending bits followed by the bits of a pool from a position.
     *
     * @param index the block
     * @param source the pool
     * @param position the first unused bit of the pool
     * @return the first unused bit of the pool after the block
     */
    private int resume(int index, DvBitBuffer source, int position) {
        var tail = pending[index];
        combined.clear();
        combined.append(tail.data, 0, tail.length);
        var own = combined.length;
        combined.append(source.data, position, source.length);
        var stop = read(index, combined.data, 0, combined.length);
        if (done[index]) {
            return position + Math.max(0, stop - own);
        }
        tail.clear();
        tail.append(combined.data, stop, combined.length);
        return source.length;
    }

    /**
     * Reads the code words of a block from a run of bits until its end of block or until the next code word does
     * not fit in the run.
     *
     * @param index the block
     * @param source the array holding the bits
     * @param position the position of the first bit
     * @param end the position one past the last bit
     * @return the position after the end of block when the block is complete, otherwise the position of the first
     * code word that did not fit
     */
    private int read(int index, byte[] source, int position, int end) {
        var order = highDefinition || mode[index] == 0 ? DvTables.ORDER_88 : DvTables.ORDER_248;
        var base = index * 64;
        var coefficient = next[index];
        while (true) {
            var remaining = end - position;
            if (remaining <= 0) {
                next[index] = coefficient;
                return position;
            }
            var entry = DvTables.DECODE[DvBitBuffer.peek16(source, position)];
            var length = entry & 0x1F;
            if (length == 0 || length > remaining) {
                if (length == 0 && remaining >= 16) {
                    done[index] = true;
                    next[index] = coefficient;
                    return end;
                }
                next[index] = coefficient;
                return position;
            }
            position += length;
            if ((entry & DvTables.ENTRY_EOB) != 0) {
                done[index] = true;
                next[index] = coefficient;
                return position;
            }
            coefficient += (entry >>> 5) & 0x3F;
            var amplitude = (entry >>> 11) & 0x1FF;
            if (amplitude == 0) {
                coefficient++;
                if (coefficient > 64) {
                    done[index] = true;
                    next[index] = 64;
                    return position;
                }
                continue;
            }
            if (coefficient >= 64) {
                done[index] = true;
                next[index] = 64;
                return position;
            }
            levels[base + order[coefficient]] = (entry & DvTables.ENTRY_NEGATIVE) != 0 ? -amplitude : amplitude;
            coefficient++;
        }
    }
}
