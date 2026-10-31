package com.github.auties00.smalt.video.dv.internal;

import com.github.auties00.smalt.internal.header.DifProfile;

/**
 * Encodes the five macroblocks of a DV video segment into its five compressed macroblocks.
 *
 * <p>Every block is transformed, in the 2-4-8 mode (SD) or the field mode (DVCPRO HD 1080-line) when the
 * difference between its fields is large and adaptive DCT is on, and weighted. The class numbers follow the
 * example of SMPTE ST 314 table 22 (SD) or are the smallest that keep the levels within 255 (DVCPRO HD); the
 * quantization number is the finest at which the segment fits, then refined per macroblock while it still fits.
 * The code words are laid out in the three passes of SMPTE ST 314 clause 5.6 and SMPTE ST 370 clause 4.6.
 *
 * <p>Instances are not thread-safe; every encoding thread owns one.
 */
final class DvSegmentEncoder {
    /**
     * The rounding offset of the AC quantizer, below one half to favor zero.
     */
    private static final float ROUNDING = 0.375f;

    /**
     * The reserved word that fills the second and fourth areas of an SD 4:2:2 compressed macroblock: the DC word
     * {@code 100000000000} followed by the end of block.
     */
    private static final int RESERVED_WORD = 0x8006;

    private final DifProfile profile;
    private final boolean hd;
    private final boolean pseudo;
    private final boolean adaptive;
    private final int blocks;
    private final int[] areas;
    private final int capacity;
    private final float[] weighted;
    private final int[] dc;
    private final int[] mode;
    private final int[] classes;
    private final int[] baseClasses;
    private final int[] qno = new int[5];
    private final int[] bits = new int[5];
    private final DvBitBuffer[] streams;
    private final DvBitBuffer overflow = new DvBitBuffer(5 * 8 * 2048);
    private final float[] block = new float[64];
    private final float[] scratch = new float[64];
    private final int[] freeStart;
    private final int[] freeEnd;

    /**
     * Constructs the encoder of the segments of a profile.
     *
     * @param profile the DV format
     * @param adaptive whether to choose the 2-4-8 or field DCT mode for blocks whose fields differ
     */
    DvSegmentEncoder(DifProfile profile, boolean adaptive) {
        this.profile = profile;
        this.hd = profile.highDefinition();
        this.pseudo = !hd && profile.sampling() == DifProfile.Sampling.S422;
        this.adaptive = adaptive && !profile.progressive();
        this.areas = hd ? DvSegmentDecoder.HD_AREAS : DvSegmentDecoder.SD_AREAS;
        this.blocks = areas.length - 1;
        this.capacity = 76 * 8 - (pseudo ? 32 : 0);
        this.weighted = new float[5 * blocks * 64];
        this.dc = new int[5 * blocks];
        this.mode = new int[5 * blocks];
        this.classes = new int[5 * blocks];
        this.baseClasses = new int[5 * blocks];
        this.streams = new DvBitBuffer[5 * blocks];
        for (var i = 0; i < streams.length; i++) {
            streams[i] = new DvBitBuffer(2048);
        }
        this.freeStart = new int[5 * blocks];
        this.freeEnd = new int[5 * blocks];
    }

    /**
     * Returns whether a block of a macroblock is the reserved word of SD 4:2:2 rather than a DCT block.
     *
     * @param block the block index
     * @return {@code true} for the second and fourth areas of an SD 4:2:2 compressed macroblock
     */
    private boolean reserved(int block) {
        return pseudo && (block == 1 || block == 3);
    }

    /**
     * Encodes one segment into the frame.
     *
     * @param frame the array receiving the frame, whose video blocks carry their IDs and zeros
     * @param frameOffset the index of the frame's first byte
     * @param layout the layout of the profile
     * @param segment the index of the video segment in the layout
     * @param source the samples of the picture
     */
    void encode(byte[] frame, int frameOffset, DvLayout layout, int segment, DvSource source) {
        for (var q = 0; q < 5; q++) {
            transform(layout, segment * 5 + q, q, source);
        }
        chooseQuantization();
        for (var q = 0; q < 5; q++) {
            for (var b = 0; b < blocks; b++) {
                if (!reserved(b)) {
                    emit(q * blocks + b, qno[q]);
                }
            }
        }
        distribute(frame, frameOffset, layout, segment);
    }

    /**
     * Loads, transforms and weights the blocks of a macroblock.
     *
     * @param layout the places of the compressed macroblocks of the frame
     * @param entry the layout entry of the macroblock
     * @param q the index of the macroblock in the segment
     * @param source the samples of the picture
     */
    private void transform(DvLayout layout, int entry, int q, DvSource source) {
        var kind = layout.kind(entry);
        var x = layout.x(entry);
        var y = layout.y(entry);
        var fieldMode = false;
        if (hd && kind == DvLayout.KIND_HD && adaptive) {
            fieldMode = fieldsDiffer(source.luma(), source.lumaStride(), x, y, 16, 16);
        }
        for (var b = 0; b < blocks; b++) {
            if (reserved(b)) {
                continue;
            }
            var index = q * blocks + b;
            load(kind, b, x, y, fieldMode, source);
            int blockMode;
            if (hd) {
                blockMode = b == 0 ? (fieldMode ? 1 : 0) : 1;
            } else {
                blockMode = adaptive && fieldsDiffer(block) ? 1 : 0;
            }
            mode[index] = blockMode;
            var fields = !hd && blockMode == 1;
            DvTransform.forward(block, 0, fields, scratch);
            var order = fields ? DvTables.ORDER_248 : DvTables.ORDER_88;
            var base = index * 64;
            var limit = hd ? 2047f : 511f;
            var largest = 0f;
            for (var i = 1; i < 64; i++) {
                var position = order[i];
                float value;
                if (hd) {
                    value = block[position] * 32f / DvTables.hdMatrix(profile.progressive(), b >= 4)[position];
                } else {
                    var weight = fields ? DvTables.WEIGHT_248[position] : DvTables.WEIGHT_88[position];
                    value = (float) (block[position] * weight);
                }
                value = Math.max(-limit, Math.min(limit, value));
                weighted[base + i] = value;
                largest = Math.max(largest, Math.abs(value));
            }
            dc[index] = Math.max(-255, Math.min(255, Math.round(block[0] * 0.25f)));
            if (!hd) {
                baseClasses[index] = sdClass(largest, kindComponent(kind, b));
            }
        }
    }

    /**
     * Returns the component of a block: 0 for luma, 1 for Cr, 2 for Cb.
     *
     * @param kind the macroblock kind
     * @param b the block index
     * @return the component
     */
    private static int kindComponent(int kind, int b) {
        if (b < 4) {
            return 0;
        }
        return b == 4 ? 1 : 2;
    }

    /**
     * Classifies an SD block from its largest weighted AC magnitude, as SMPTE ST 314 table 22 does.
     *
     * @param largest the largest weighted AC magnitude
     * @param component 0 for luma, 1 for Cr, 2 for Cb
     * @return the class number
     */
    private static int sdClass(float largest, int component) {
        if (largest > 255) {
            return 3;
        }
        var base = largest < 12 ? 0 : largest < 24 ? 1 : largest < 36 ? 2 : 3;
        return Math.min(3, base + component);
    }

    /**
     * Loads the samples of a block, less the level shift of 128.
     *
     * @param kind the macroblock kind
     * @param b the block index
     * @param x the left edge of the macroblock
     * @param y the top edge of the macroblock
     * @param fieldMode whether a DVCPRO HD macroblock is in the field mode
     * @param source the samples of the picture
     */
    private void load(int kind, int b, int x, int y, boolean fieldMode, DvSource source) {
        var lumaStride = source.lumaStride();
        var chromaStride = source.chromaStride();
        switch (kind) {
            case DvLayout.KIND_411 -> {
                if (b < 4) {
                    copy(source.luma(), lumaStride, x + 8 * b, y, 1, 0, 8);
                } else {
                    copy(b == 4 ? source.cr() : source.cb(), chromaStride, x / 4, y, 1, 0, 8);
                }
            }
            case DvLayout.KIND_411_RIGHT -> {
                if (b < 4) {
                    copy(source.luma(), lumaStride, x + 8 * (b & 1), y + 8 * (b >> 1), 1, 0, 8);
                } else {
                    var plane = b == 4 ? source.cr() : source.cb();
                    copy(plane, chromaStride, x / 4, y, 1, 0, 4);
                    copy(plane, chromaStride, x / 4, y + 8, 1, 4, 4);
                }
            }
            case DvLayout.KIND_420 -> {
                if (b < 4) {
                    copy(source.luma(), lumaStride, x + 8 * (b & 1), y + 8 * (b >> 1), 1, 0, 8);
                } else {
                    copy(b == 4 ? source.cr() : source.cb(), chromaStride, x / 2, y / 2, 1, 0, 8);
                }
            }
            case DvLayout.KIND_422 -> {
                switch (b) {
                    case 0 -> copy(source.luma(), lumaStride, x, y, 1, 0, 8);
                    case 2 -> copy(source.luma(), lumaStride, x + 8, y, 1, 0, 8);
                    case 4 -> copy(source.cr(), chromaStride, x / 2, y, 1, 0, 8);
                    default -> copy(source.cb(), chromaStride, x / 2, y, 1, 0, 8);
                }
            }
            case DvLayout.KIND_HD -> {
                byte[] plane;
                int stride;
                int left;
                boolean bottom;
                if (b < 4) {
                    plane = source.luma();
                    stride = lumaStride;
                    left = x + 8 * (b & 1);
                    bottom = b >= 2;
                } else {
                    plane = b < 6 ? source.cr() : source.cb();
                    stride = chromaStride;
                    left = x / 2;
                    bottom = (b & 1) == 1;
                }
                if (fieldMode) {
                    copy(plane, stride, left, y + (bottom ? 1 : 0), 2, 0, 8);
                } else {
                    copy(plane, stride, left, y + (bottom ? 8 : 0), 1, 0, 8);
                }
            }
            default -> {
                if (b < 4) {
                    copy(source.luma(), lumaStride, x + 8 * b, y, 1, 0, 8);
                } else {
                    var plane = b < 6 ? source.cr() : source.cb();
                    copy(plane, chromaStride, x / 2 + 8 * (b & 1), y, 1, 0, 8);
                }
            }
        }
    }

    /**
     * Copies columns of a plane into the block.
     *
     * @param plane the samples of one component
     * @param stride its row stride
     * @param x the first column
     * @param y the line of the block's first row
     * @param lineStep the distance between the lines of two rows, 1 or 2
     * @param firstColumn the first column of the block to fill
     * @param columns the number of columns to fill
     */
    private void copy(byte[] plane, int stride, int x, int y, int lineStep, int firstColumn, int columns) {
        for (var row = 0; row < 8; row++) {
            var line = (y + row * lineStep) * stride + x;
            for (var column = 0; column < columns; column++) {
                block[row * 8 + firstColumn + column] = (plane[line + column] & 0xFF) - 128;
            }
        }
    }

    /**
     * Returns whether the two fields of the loaded block differ enough for the 2-4-8 mode to code it better.
     *
     * @param samples the block, 64 samples in raster order
     * @return {@code true} when the activity between adjacent lines clearly exceeds the activity within the fields
     */
    private static boolean fieldsDiffer(float[] samples) {
        var frame = 0f;
        var field = 0f;
        for (var row = 0; row < 7; row++) {
            for (var column = 0; column < 8; column++) {
                frame += Math.abs(samples[row * 8 + column] - samples[(row + 1) * 8 + column]);
                if (row < 6) {
                    field += Math.abs(samples[row * 8 + column] - samples[(row + 2) * 8 + column]);
                }
            }
        }
        // 56 adjacent-line differences against 48 same-field differences
        return frame * 48 > field * 56 * 1.5f + 512;
    }

    /**
     * Returns whether the two fields of an area of a plane differ enough for the field DCT mode.
     *
     * @param plane the samples of one component
     * @param stride its row stride
     * @param x the left edge
     * @param y the top edge
     * @param width the width of the area
     * @param height the height of the area, even
     * @return {@code true} when the activity between adjacent lines clearly exceeds the activity within the fields
     */
    private static boolean fieldsDiffer(byte[] plane, int stride, int x, int y, int width, int height) {
        var frame = 0L;
        var field = 0L;
        for (var row = 0; row < height - 1; row++) {
            for (var column = 0; column < width; column++) {
                var at = (y + row) * stride + x + column;
                frame += Math.abs((plane[at] & 0xFF) - (plane[at + stride] & 0xFF));
                if (row < height - 2) {
                    field += Math.abs((plane[at] & 0xFF) - (plane[at + 2 * stride] & 0xFF));
                }
            }
        }
        return frame * (height - 2) * 2 > field * (height - 1) * 3 + 2048;
    }

    /**
     * Chooses the class numbers and the quantization numbers of the segment.
     */
    private void chooseQuantization() {
        var budget = 5 * capacity;
        int chosen;
        if (hd) {
            var low = 1;
            var high = 15;
            while (low < high) {
                var middle = (low + high) >>> 1;
                if (total(middle) <= budget) {
                    high = middle;
                } else {
                    low = middle + 1;
                }
            }
            chosen = low;
        } else {
            var low = 0;
            var high = 15;
            while (low < high) {
                var middle = (low + high + 1) >>> 1;
                if (total(middle) <= budget) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            chosen = low;
        }
        var sum = 0;
        for (var q = 0; q < 5; q++) {
            qno[q] = chosen;
            bits[q] = macroblockBits(q, chosen);
            sum += bits[q];
        }
        var step = hd ? -1 : 1;
        for (var q = 0; q < 5; q++) {
            while (true) {
                var finer = qno[q] + step;
                if (finer < (hd ? 1 : 0) || finer > 15) {
                    break;
                }
                var cost = macroblockBits(q, finer);
                if (sum - bits[q] + cost > budget) {
                    break;
                }
                sum += cost - bits[q];
                bits[q] = cost;
                qno[q] = finer;
            }
        }
        for (var q = 0; q < 5; q++) {
            for (var b = 0; b < blocks; b++) {
                if (!reserved(b)) {
                    classes[q * blocks + b] = classOf(q * blocks + b, qno[q]);
                }
            }
        }
    }

    /**
     * Returns the bits of the segment with every macroblock at one quantization number.
     *
     * @param candidate the quantization number
     * @return the bit count
     */
    private int total(int candidate) {
        var sum = 0;
        for (var q = 0; q < 5; q++) {
            sum += macroblockBits(q, candidate);
        }
        return sum;
    }

    /**
     * Returns the bits of a macroblock at a quantization number.
     *
     * @param q the macroblock
     * @param candidate the quantization number
     * @return the bit count of its blocks, DC words and ends of block included
     */
    private int macroblockBits(int q, int candidate) {
        var sum = 0;
        for (var b = 0; b < blocks; b++) {
            if (!reserved(b)) {
                var index = q * blocks + b;
                sum += blockBits(index, candidate, classOf(index, candidate));
            }
        }
        return sum;
    }

    /**
     * Returns the class number of a block at a quantization number.
     *
     * @param index the block
     * @param candidate the quantization number
     * @return the class number: the one SMPTE ST 314 table 22 gives for SD, the smallest of SMPTE ST 370 table 26
     * that keeps the levels within 255 for DVCPRO HD
     */
    private int classOf(int index, int candidate) {
        if (!hd) {
            return baseClasses[index];
        }
        var largest = 0f;
        var base = index * 64;
        for (var i = 1; i < 64; i++) {
            largest = Math.max(largest, Math.abs(weighted[base + i]));
        }
        var last = 0;
        for (var c = 0; c < 4; c++) {
            if (!DvTables.hdDefined(candidate, c)) {
                break;
            }
            last = c;
            if (largest / DvTables.hdStep(candidate, c) + ROUNDING < 256) {
                return c;
            }
        }
        return last;
    }

    /**
     * Returns the step of a coefficient.
     *
     * @param index the block
     * @param i the transmission index of the coefficient
     * @param candidate the quantization number
     * @param classNumber the class number
     * @return the step
     */
    private float step(int index, int i, int candidate, int classNumber) {
        if (hd) {
            return DvTables.hdStep(candidate, classNumber);
        }
        var fields = mode[index] == 1;
        var position = fields ? DvTables.ORDER_248[i] : DvTables.ORDER_88[i];
        var area = fields ? DvTables.AREA_248[position] : DvTables.AREA_88[position];
        return DvTables.sdStep(candidate, classNumber, area) * (classNumber == 3 ? 2 : 1);
    }

    /**
     * Returns the level of a coefficient.
     *
     * @param index the block
     * @param i the transmission index
     * @param candidate the quantization number
     * @param classNumber the class number
     * @return the signed level, its magnitude at most 255
     */
    private int level(int index, int i, int candidate, int classNumber) {
        var value = weighted[index * 64 + i];
        var magnitude = Math.min(255, (int) (Math.abs(value) / step(index, i, candidate, classNumber) + ROUNDING));
        return value < 0 ? -magnitude : magnitude;
    }

    /**
     * Returns the bits of a block.
     *
     * @param index the block
     * @param candidate the quantization number
     * @param classNumber the class number
     * @return the bit count, DC word and end of block included
     */
    private int blockBits(int index, int candidate, int classNumber) {
        var sum = 12 + DvTables.EOB_LENGTH;
        var run = 0;
        for (var i = 1; i < 64; i++) {
            var level = level(index, i, candidate, classNumber);
            if (level == 0) {
                run++;
                continue;
            }
            sum += codeLength(run, Math.abs(level));
            run = 0;
        }
        return sum;
    }

    /**
     * Returns the length of the code of a run and a nonzero amplitude, sign bit included.
     *
     * @param run the run of zeros before the coefficient
     * @param amplitude the amplitude, from 1 to 255
     * @return the length
     */
    private static int codeLength(int run, int amplitude) {
        var direct = DvTables.CODE_LENGTHS[run * 256 + amplitude];
        if (direct != 0) {
            return direct + 1;
        }
        return DvTables.CODE_LENGTHS[(run - 1) * 256] + DvTables.CODE_LENGTHS[amplitude] + 1;
    }

    /**
     * Writes the bit sequence of a block: its DC word, the code words of its AC coefficients and the end of block.
     *
     * @param index the block
     * @param candidate the quantization number of its macroblock
     */
    private void emit(int index, int candidate) {
        var stream = streams[index];
        stream.clear();
        var classNumber = classes[index];
        stream.appendBits((dc[index] & 0x1FF) << 3 | mode[index] << 2 | classNumber, 12);
        var run = 0;
        for (var i = 1; i < 64; i++) {
            var level = level(index, i, candidate, classNumber);
            if (level == 0) {
                run++;
                continue;
            }
            var amplitude = Math.abs(level);
            var sign = level < 0 ? 1 : 0;
            var key = run * 256 + amplitude;
            if (DvTables.CODE_LENGTHS[key] == 0) {
                stream.appendBits(DvTables.CODES[(run - 1) * 256], DvTables.CODE_LENGTHS[(run - 1) * 256]);
                key = amplitude;
            }
            stream.appendBits(DvTables.CODES[key] << 1 | sign, DvTables.CODE_LENGTHS[key] + 1);
            run = 0;
        }
        stream.appendBits(DvTables.EOB, DvTables.EOB_LENGTH);
    }

    /**
     * Lays the bit sequences of the segment out over its compressed macroblocks in three passes.
     *
     * @param frame the array receiving the frame
     * @param frameOffset the index of the frame's first byte
     * @param layout the places of the compressed macroblocks of the frame
     * @param segment the index of the video segment in the layout
     */
    private void distribute(byte[] frame, int frameOffset, DvLayout layout, int segment) {
        overflow.clear();
        for (var q = 0; q < 5; q++) {
            var base = frameOffset + layout.offset(segment * 5 + q);
            frame[base + 3] = (byte) qno[q];
            for (var b = 0; b < blocks; b++) {
                var index = q * blocks + b;
                var start = (base + areas[b]) << 3;
                var end = (base + areas[b + 1]) << 3;
                if (reserved(b)) {
                    write(frame, start, RESERVED_WORD, 16);
                    freeStart[index] = start + 16;
                    freeEnd[index] = end;
                    continue;
                }
                var stream = streams[index];
                var length = Math.min(stream.length, end - start);
                copy(frame, start, stream.data, 0, length);
                freeStart[index] = start + length;
                freeEnd[index] = stream.length <= end - start ? end : start + length;
            }
        }
        for (var q = 0; q < 5; q++) {
            var area = q * blocks;
            for (var b = 0; b < blocks; b++) {
                var index = q * blocks + b;
                if (reserved(b)) {
                    continue;
                }
                var stream = streams[index];
                var areaBits = (areas[b + 1] - areas[b]) << 3;
                var position = areaBits;
                while (position < stream.length) {
                    while (area < (q + 1) * blocks && freeStart[area] >= freeEnd[area]) {
                        area++;
                    }
                    if (area >= (q + 1) * blocks) {
                        overflow.append(stream.data, position, stream.length);
                        break;
                    }
                    var count = Math.min(stream.length - position, freeEnd[area] - freeStart[area]);
                    copy(frame, freeStart[area], stream.data, position, count);
                    freeStart[area] += count;
                    position += count;
                }
            }
        }
        var position = 0;
        for (var area = 0; area < 5 * blocks && position < overflow.length; area++) {
            var count = Math.min(overflow.length - position, freeEnd[area] - freeStart[area]);
            if (count <= 0) {
                continue;
            }
            copy(frame, freeStart[area], overflow.data, position, count);
            freeStart[area] += count;
            position += count;
        }
    }

    /**
     * Writes the low bits of a value into the frame.
     *
     * @param frame the array
     * @param position the bit position of the first bit
     * @param value the bits, in its low bits
     * @param count the number of bits
     */
    private static void write(byte[] frame, int position, int value, int count) {
        for (var i = 0; i < count; i++) {
            var bit = (value >>> (count - 1 - i)) & 1;
            var at = position + i;
            frame[at >>> 3] |= (byte) (bit << (7 - (at & 7)));
        }
    }

    /**
     * Copies a run of bits into the frame, whose bits there are zero.
     *
     * @param frame the array
     * @param position the bit position of the first bit written
     * @param source the array holding the bits
     * @param from the bit position of the first bit read
     * @param count the number of bits
     */
    private static void copy(byte[] frame, int position, byte[] source, int from, int count) {
        for (var i = 0; i < count; i++) {
            var read = from + i;
            var bit = (source[read >>> 3] >>> (7 - (read & 7))) & 1;
            var at = position + i;
            frame[at >>> 3] |= (byte) (bit << (7 - (at & 7)));
        }
    }
}
