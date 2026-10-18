package com.github.auties00.smalt.image.png;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link PngFilterKernel} with the Vector API; the output equals
 * {@link ScalarPngFilterKernel} byte for byte.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd Simd}. Sub, Average and Paeth
 * reconstruction runs one pixel per step for filter distances of 3 and more, as libpng's SSE2
 * filter code does, and Sub with distances 1 and 2 as a strided prefix sum per vector. The
 * branch-free Paeth predictor follows stb_image and equals the specification's for all 2^24 inputs.
 */
final class VectorPngFilterKernel implements PngFilterKernel {
    /**
     * The 128-bit byte species used by the shuffle-based kernels.
     */
    private static final VectorSpecies<Byte> BYTES = ByteVector.SPECIES_128;

    /**
     * The 256-bit short species that holds {@link #BYTES} widened to 16 bits.
     */
    private static final VectorSpecies<Short> SHORTS = ShortVector.SPECIES_256;

    /**
     * The 256-bit integer species that holds pairs of {@link #SHORTS} lanes.
     */
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_256;

    /**
     * The preferred byte species used by the lane-wise kernels.
     */
    private static final VectorSpecies<Byte> WIDE = ByteVector.SPECIES_PREFERRED;

    /**
     * The number of lanes of {@link #BYTES}.
     */
    private static final int LANES = 16;

    /**
     * The 64-bit byte species holding one pixel (plus the start of the next) in the
     * pixel-at-a-time kernels.
     */
    private static final VectorSpecies<Byte> PIXEL = ByteVector.SPECIES_64;

    /**
     * The 128-bit short species that holds {@link #PIXEL} widened to 16 bits.
     */
    private static final VectorSpecies<Short> PIXEL_SHORTS = ShortVector.SPECIES_128;

    /**
     * The number of lanes of {@link #PIXEL}.
     */
    private static final int PIXEL_LANES = 8;

    /**
     * The number of 16-byte steps a 16-bit cost accumulator absorbs before it could overflow.
     */
    private static final int COST_FLUSH = 255;

    /**
     * The exact class of the vectors of {@link #BYTES}.
     */
    private static final Class<ByteVector> BYTE16 = vectorClass(BYTES);

    /**
     * The exact class of the vectors of {@link #SHORTS}.
     */
    private static final Class<ShortVector> SHORT16 = vectorClass(SHORTS);

    /**
     * The exact class of the vectors of {@link #INTS}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(INTS);

    /**
     * The exact class of the vectors of {@link #WIDE}.
     */
    private static final Class<ByteVector> BYTE_WIDE = vectorClass(WIDE);

    /**
     * The exact class of the vectors of {@link #PIXEL}.
     */
    private static final Class<ByteVector> BYTE8 = vectorClass(PIXEL);

    /**
     * The exact class of the vectors of {@link #PIXEL_SHORTS}.
     */
    private static final Class<ShortVector> SHORT8 = vectorClass(PIXEL_SHORTS);

    /**
     * Zero in every lane of {@link #BYTES}.
     */
    private static final ByteVector ZERO_BYTES = ByteVector.zero(BYTES);

    /**
     * Zero in every lane of {@link #SHORTS}.
     */
    private static final ShortVector ZERO_SHORTS = ShortVector.zero(SHORTS);

    /**
     * The low 16 bits of every lane of {@link #INTS}.
     */
    private static final IntVector LOW_HALF = IntVector.broadcast(INTS, 0xFFFF);

    /**
     * Three in every lane of {@link #SHORTS}.
     */
    private static final ShortVector THREE = ShortVector.broadcast(SHORTS, (short) 3);

    /**
     * Three in every lane of {@link #PIXEL_SHORTS}.
     */
    private static final ShortVector PIXEL_THREE = ShortVector.broadcast(PIXEL_SHORTS, (short) 3);

    /**
     * The low byte of every lane of {@link #PIXEL_SHORTS}.
     */
    private static final ShortVector PIXEL_LOW_BYTE = ShortVector.broadcast(PIXEL_SHORTS, (short) 0xFF);

    /**
     * The shift that halves a byte difference in the average without widening.
     */
    private static final int AVERAGE_SHIFT = 1;

    /**
     * Shifts the lanes up by 1, lane {@code j} taking lane {@code j - 1}.
     */
    private static final VectorShuffle<Byte> UP_1 = shift(1);

    /**
     * The lanes below 1, which take zero after {@link #UP_1}.
     */
    private static final VectorMask<Byte> BELOW_1 = below(1);

    /**
     * Shifts the lanes up by 2, lane {@code j} taking lane {@code j - 2}.
     */
    private static final VectorShuffle<Byte> UP_2 = shift(2);

    /**
     * The lanes below 2, which take zero after {@link #UP_2}.
     */
    private static final VectorMask<Byte> BELOW_2 = below(2);

    /**
     * Shifts the lanes up by 4, lane {@code j} taking lane {@code j - 4}.
     */
    private static final VectorShuffle<Byte> UP_4 = shift(4);

    /**
     * The lanes below 4, which take zero after {@link #UP_4}.
     */
    private static final VectorMask<Byte> BELOW_4 = below(4);

    /**
     * Shifts the lanes up by 8, lane {@code j} taking lane {@code j - 8}.
     */
    private static final VectorShuffle<Byte> UP_8 = shift(8);

    /**
     * The lanes below 8, which take zero after {@link #UP_8}.
     */
    private static final VectorMask<Byte> BELOW_8 = below(8);

    /**
     * The shuffle broadcasting the last lane of a reconstructed vector, the carry of distance 1.
     */
    private static final VectorShuffle<Byte> CARRY_1 = carry(1, LANES);

    /**
     * The shuffle repeating the last two lanes of a reconstructed vector, the carry of distance 2.
     */
    private static final VectorShuffle<Byte> CARRY_2 = carry(2, LANES);

    /**
     * The shuffle broadcasting the first lane, the seed carry of distance 1.
     */
    private static final VectorShuffle<Byte> SEED_1 = carry(1, 0);

    /**
     * The shuffle repeating the first two lanes, the seed carry of distance 2.
     */
    private static final VectorShuffle<Byte> SEED_2 = carry(2, 0);

    /**
     * The shuffle moving four packed RGB pixels (the first 12 lanes) to the RGB lanes of four RGBA
     * pixels.
     */
    private static final VectorShuffle<Byte> RGB_TO_RGBA =
            VectorShuffle.fromValues(BYTES, 0, 1, 2, 0, 3, 4, 5, 0, 6, 7, 8, 0, 9, 10, 11, 0);

    /**
     * The alpha lanes of four RGBA pixels, set to 255.
     */
    private static final ByteVector OPAQUE = ByteVector.fromArray(BYTES,
            new byte[]{0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0, -1}, 0);

    /**
     * Constructs the kernel.
     */
    VectorPngFilterKernel() {
    }

    /**
     * {@return a new kernel} {@link PngFilterKernel#INSTANCE} calls it only when the Vector API is
     * enabled, so this class is not linked otherwise.
     */
    static PngFilterKernel create() {
        return new VectorPngFilterKernel();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vector class is wanted
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds the shuffle shifting the lanes of a 16-lane vector up by {@code shift}; the lanes
     * below the shift take lane 0.
     *
     * @param shift the shift, below 16
     * @return the shuffle
     */
    private static VectorShuffle<Byte> shift(int shift) {
        var indexes = new int[LANES];
        for (var lane = 0; lane < LANES; lane++) {
            indexes[lane] = lane >= shift ? lane - shift : 0;
        }
        return VectorShuffle.fromArray(BYTES, indexes, 0);
    }

    /**
     * Builds the mask of the lanes below a shift.
     *
     * @param shift the shift, below 16
     * @return the mask
     */
    private static VectorMask<Byte> below(int shift) {
        var bits = new boolean[LANES];
        for (var lane = 0; lane < LANES; lane++) {
            bits[lane] = lane < shift;
        }
        return VectorMask.fromArray(BYTES, bits, 0);
    }

    /**
     * Builds a repeating shuffle: lane {@code j} takes lane
     * {@code base - (base == 0 ? 0 : distance) + j % distance}.
     *
     * @param distance the filter distance, 1 or 2
     * @param base {@code 16} for a carry, {@code 0} for a seed
     * @return the shuffle
     */
    private static VectorShuffle<Byte> carry(int distance, int base) {
        var indexes = new int[LANES];
        for (var lane = 0; lane < LANES; lane++) {
            indexes[lane] = base - (base == 0 ? 0 : distance) + lane % distance;
        }
        return VectorShuffle.fromArray(BYTES, indexes, 0);
    }

    @Override
    public void expandRgb(byte[] source, int sourceOffset, byte[] target, int targetOffset, int count) {
        var i = 0;
        for (; i + 6 <= count; i += 4) {
            BYTE16.cast(BYTE16.cast(ByteVector.fromArray(BYTES, source, sourceOffset + i * 3)).rearrange(RGB_TO_RGBA)
                    .lanewise(VectorOperators.OR, OPAQUE)).intoArray(target, targetOffset + i * 4);
        }
        for (; i < count; i++) {
            var s = sourceOffset + i * 3;
            var t = targetOffset + i * 4;
            target[t] = source[s];
            target[t + 1] = source[s + 1];
            target[t + 2] = source[s + 2];
            target[t + 3] = (byte) 0xFF;
        }
    }

    @Override
    public void unfilterSub(byte[] raw, int rawOffset, byte[] out, int outOffset, int length, int distance) {
        if (distance >= 3) {
            subPixels(raw, rawOffset, out, outOffset, length, distance);
        } else if (distance == 2) {
            subPrefix2(raw, rawOffset, out, outOffset, length);
        } else {
            subPrefix1(raw, rawOffset, out, outOffset, length);
        }
    }

    /**
     * Reverses the Sub filter one pixel per step, for distances of 3 and more.
     *
     * @param raw the filtered scanline
     * @param rawOffset the index of its first byte
     * @param out the destination
     * @param outOffset the index of the first reconstructed byte
     * @param length the scanline length
     * @param distance the filter distance, from 3 to 8
     */
    private static void subPixels(byte[] raw, int rawOffset, byte[] out, int outOffset, int length, int distance) {
        var head = Math.min(distance, length);
        System.arraycopy(raw, rawOffset, out, outOffset, head);
        var i = head;
        if (length >= distance + PIXEL_LANES) {
            var left = BYTE8.cast(ByteVector.fromArray(PIXEL, out, outOffset));
            for (; i + PIXEL_LANES <= length; i += distance) {
                left = BYTE8.cast(ByteVector.fromArray(PIXEL, raw, rawOffset + i)).lanewise(VectorOperators.ADD, left);
                BYTE8.cast(left).intoArray(out, outOffset + i);
            }
        }
        for (; i < length; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + out[outOffset + i - distance]);
        }
    }

    /**
     * Reverses the Sub filter 16 bytes per step as a prefix sum, for distance 1.
     *
     * @param raw the filtered scanline
     * @param rawOffset the index of its first byte
     * @param out the destination
     * @param outOffset the index of the first reconstructed byte
     * @param length the scanline length
     */
    private static void subPrefix1(byte[] raw, int rawOffset, byte[] out, int outOffset, int length) {
        var head = Math.min(1, length);
        System.arraycopy(raw, rawOffset, out, outOffset, head);
        var i = head;
        if (length >= 1 + LANES) {
            var carry = BYTE16.cast(ByteVector.fromArray(BYTES, out, outOffset + i - 1)).rearrange(SEED_1);
            for (; i + LANES <= length; i += LANES) {
                var vector = BYTE16.cast(ByteVector.fromArray(BYTES, raw, rawOffset + i));
                vector = vector.lanewise(VectorOperators.ADD, vector.rearrange(UP_1).blend(ZERO_BYTES, BELOW_1));
                vector = vector.lanewise(VectorOperators.ADD, vector.rearrange(UP_2).blend(ZERO_BYTES, BELOW_2));
                vector = vector.lanewise(VectorOperators.ADD, vector.rearrange(UP_4).blend(ZERO_BYTES, BELOW_4));
                vector = vector.lanewise(VectorOperators.ADD, vector.rearrange(UP_8).blend(ZERO_BYTES, BELOW_8))
                        .lanewise(VectorOperators.ADD, carry);
                BYTE16.cast(vector).intoArray(out, outOffset + i);
                carry = vector.rearrange(CARRY_1);
            }
        }
        for (; i < length; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + out[outOffset + i - 1]);
        }
    }

    /**
     * Reverses the Sub filter 16 bytes per step as a strided prefix sum, for distance 2.
     *
     * @param raw the filtered scanline
     * @param rawOffset the index of its first byte
     * @param out the destination
     * @param outOffset the index of the first reconstructed byte
     * @param length the scanline length
     */
    private static void subPrefix2(byte[] raw, int rawOffset, byte[] out, int outOffset, int length) {
        var head = Math.min(2, length);
        System.arraycopy(raw, rawOffset, out, outOffset, head);
        var i = head;
        if (length >= 2 + LANES) {
            var carry = BYTE16.cast(ByteVector.fromArray(BYTES, out, outOffset + i - 2)).rearrange(SEED_2);
            for (; i + LANES <= length; i += LANES) {
                var vector = BYTE16.cast(ByteVector.fromArray(BYTES, raw, rawOffset + i));
                vector = vector.lanewise(VectorOperators.ADD, vector.rearrange(UP_2).blend(ZERO_BYTES, BELOW_2));
                vector = vector.lanewise(VectorOperators.ADD, vector.rearrange(UP_4).blend(ZERO_BYTES, BELOW_4));
                vector = vector.lanewise(VectorOperators.ADD, vector.rearrange(UP_8).blend(ZERO_BYTES, BELOW_8))
                        .lanewise(VectorOperators.ADD, carry);
                BYTE16.cast(vector).intoArray(out, outOffset + i);
                carry = vector.rearrange(CARRY_2);
            }
        }
        for (; i < length; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + out[outOffset + i - 2]);
        }
    }

    @Override
    public void unfilterUp(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset,
                           int length) {
        var i = 0;
        var bound = WIDE.loopBound(length);
        for (; i < bound; i += WIDE.length()) {
            BYTE_WIDE.cast(BYTE_WIDE.cast(ByteVector.fromArray(WIDE, raw, rawOffset + i))
                    .lanewise(VectorOperators.ADD, ByteVector.fromArray(WIDE, prior, priorOffset + i)))
                    .intoArray(out, outOffset + i);
        }
        for (; i < length; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + prior[priorOffset + i]);
        }
    }

    @Override
    public void unfilterAverage(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset,
                                int length, int distance) {
        var head = Math.min(distance, length);
        for (var i = 0; i < head; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + ((prior[priorOffset + i] & 0xFF) >>> 1));
        }
        var i = head;
        if (distance >= 3 && length >= distance + PIXEL_LANES) {
            var left = BYTE8.cast(ByteVector.fromArray(PIXEL, out, outOffset));
            for (; i + PIXEL_LANES <= length; i += distance) {
                var above = BYTE8.cast(ByteVector.fromArray(PIXEL, prior, priorOffset + i));
                // floor((a + b) / 2) without widening: (a & b) + ((a ^ b) >>> 1)
                var average = above.lanewise(VectorOperators.AND, left)
                        .lanewise(VectorOperators.ADD, BYTE8.cast(above.lanewise(VectorOperators.XOR, left))
                                .lanewise(VectorOperators.LSHR, AVERAGE_SHIFT));
                left = BYTE8.cast(ByteVector.fromArray(PIXEL, raw, rawOffset + i))
                        .lanewise(VectorOperators.ADD, average);
                BYTE8.cast(left).intoArray(out, outOffset + i);
            }
        }
        for (; i < length; i++) {
            var a = out[outOffset + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            out[outOffset + i] = (byte) (raw[rawOffset + i] + ((a + b) >>> 1));
        }
    }

    /**
     * Returns the Paeth predictor with the branch-free formulation described on the class.
     *
     * @param a the byte to the left, from 0 to 255
     * @param b the byte above, from 0 to 255
     * @param c the byte above and to the left, from 0 to 255
     * @return the predictor
     */
    private static int paeth(int a, int b, int c) {
        var threshold = c * 3 - (a + b);
        var low = Math.min(a, b);
        var high = Math.max(a, b);
        var middle = high <= threshold ? low : c;
        return threshold <= low ? high : middle;
    }

    @Override
    public void unfilterPaeth(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset,
                              int length, int distance) {
        var head = Math.min(distance, length);
        for (var i = 0; i < head; i++) {
            out[outOffset + i] = (byte) (raw[rawOffset + i] + prior[priorOffset + i]);
        }
        var i = head;
        if (distance >= 3 && length >= distance + PIXEL_LANES) {
            var left = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, out, outOffset))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0));
            var corner = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, prior, priorOffset))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0));
            for (; i + PIXEL_LANES <= length; i += distance) {
                var above = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, prior, priorOffset + i))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0));
                var threshold = PIXEL_THREE.lanewise(VectorOperators.MUL, corner).lanewise(VectorOperators.SUB, above)
                        .lanewise(VectorOperators.SUB, left);
                var low = above.lanewise(VectorOperators.MIN, left);
                var high = above.lanewise(VectorOperators.MAX, left);
                var middle = low.blend(corner, high.compare(VectorOperators.GT, threshold));
                var predictor = high.blend(middle, threshold.compare(VectorOperators.GT, low));
                left = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, raw, rawOffset + i))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0))
                        .lanewise(VectorOperators.ADD, predictor)
                        .lanewise(VectorOperators.AND, PIXEL_LOW_BYTE);
                BYTE8.cast(SHORT8.cast(left).convertShape(VectorOperators.S2B, PIXEL, 0)).intoArray(out, outOffset + i);
                corner = above;
            }
        }
        for (; i < length; i++) {
            var a = out[outOffset + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            var c = prior[priorOffset + i - distance] & 0xFF;
            out[outOffset + i] = (byte) (raw[rawOffset + i] + paeth(a, b, c));
        }
    }

    @Override
    public void unfilterAveragePair(byte[] raw, int firstOffset, int secondOffset, byte[] first, int firstOut,
                                    byte[] second, int secondOut, byte[] prior, int priorOffset, int length,
                                    int distance) {
        if (distance < 3 || length < distance + PIXEL_LANES) {
            unfilterAverage(raw, firstOffset, first, firstOut, prior, priorOffset, length, distance);
            unfilterAverage(raw, secondOffset, second, secondOut, first, firstOut, length, distance);
            return;
        }
        for (var i = 0; i < distance; i++) {
            first[firstOut + i] = (byte) (raw[firstOffset + i] + ((prior[priorOffset + i] & 0xFF) >>> 1));
            second[secondOut + i] = (byte) (raw[secondOffset + i] + ((first[firstOut + i] & 0xFF) >>> 1));
        }
        var upper = BYTE8.cast(ByteVector.fromArray(PIXEL, first, firstOut));
        var lower = BYTE8.cast(ByteVector.fromArray(PIXEL, second, secondOut));
        var i = distance;
        for (; i + PIXEL_LANES <= length; i += distance) {
            var above = BYTE8.cast(ByteVector.fromArray(PIXEL, prior, priorOffset + i));
            var aboveAverage = above.lanewise(VectorOperators.AND, upper)
                    .lanewise(VectorOperators.ADD, BYTE8.cast(above.lanewise(VectorOperators.XOR, upper))
                            .lanewise(VectorOperators.LSHR, AVERAGE_SHIFT));
            var reconstructed = BYTE8.cast(ByteVector.fromArray(PIXEL, raw, firstOffset + i))
                    .lanewise(VectorOperators.ADD, aboveAverage);
            BYTE8.cast(reconstructed).intoArray(first, firstOut + i);
            var lowerAverage = reconstructed.lanewise(VectorOperators.AND, lower)
                    .lanewise(VectorOperators.ADD, BYTE8.cast(reconstructed.lanewise(VectorOperators.XOR, lower))
                            .lanewise(VectorOperators.LSHR, AVERAGE_SHIFT));
            lower = BYTE8.cast(ByteVector.fromArray(PIXEL, raw, secondOffset + i))
                    .lanewise(VectorOperators.ADD, lowerAverage);
            BYTE8.cast(lower).intoArray(second, secondOut + i);
            upper = reconstructed;
        }
        var tail = i;
        for (i = tail; i < length; i++) {
            var a = first[firstOut + i - distance] & 0xFF;
            first[firstOut + i] = (byte) (raw[firstOffset + i] + ((a + (prior[priorOffset + i] & 0xFF)) >>> 1));
        }
        for (i = tail; i < length; i++) {
            var a = second[secondOut + i - distance] & 0xFF;
            second[secondOut + i] = (byte) (raw[secondOffset + i] + ((a + (first[firstOut + i] & 0xFF)) >>> 1));
        }
    }

    @Override
    public void unfilterPaethPair(byte[] raw, int firstOffset, int secondOffset, byte[] first, int firstOut,
                                  byte[] second, int secondOut, byte[] prior, int priorOffset, int length,
                                  int distance) {
        if (distance < 3 || length < distance + PIXEL_LANES) {
            unfilterPaeth(raw, firstOffset, first, firstOut, prior, priorOffset, length, distance);
            unfilterPaeth(raw, secondOffset, second, secondOut, first, firstOut, length, distance);
            return;
        }
        for (var i = 0; i < distance; i++) {
            first[firstOut + i] = (byte) (raw[firstOffset + i] + prior[priorOffset + i]);
            second[secondOut + i] = (byte) (raw[secondOffset + i] + first[firstOut + i]);
        }
        var upper = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, first, firstOut))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0));
        var corner = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, prior, priorOffset))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0));
        var lower = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, second, secondOut))
                .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0));
        var i = distance;
        for (; i + PIXEL_LANES <= length; i += distance) {
            var above = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, prior, priorOffset + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0));
            var threshold = PIXEL_THREE.lanewise(VectorOperators.MUL, corner).lanewise(VectorOperators.SUB, above)
                    .lanewise(VectorOperators.SUB, upper);
            var low = above.lanewise(VectorOperators.MIN, upper);
            var high = above.lanewise(VectorOperators.MAX, upper);
            var middle = low.blend(corner, high.compare(VectorOperators.GT, threshold));
            var reconstructed = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, raw, firstOffset + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0))
                    .lanewise(VectorOperators.ADD, high.blend(middle, threshold.compare(VectorOperators.GT, low)))
                    .lanewise(VectorOperators.AND, PIXEL_LOW_BYTE);
            BYTE8.cast(SHORT8.cast(reconstructed).convertShape(VectorOperators.S2B, PIXEL, 0))
                    .intoArray(first, firstOut + i);
            var lowerThreshold = PIXEL_THREE.lanewise(VectorOperators.MUL, upper)
                    .lanewise(VectorOperators.SUB, reconstructed)
                    .lanewise(VectorOperators.SUB, lower);
            var lowerLow = reconstructed.lanewise(VectorOperators.MIN, lower);
            var lowerHigh = reconstructed.lanewise(VectorOperators.MAX, lower);
            var lowerMiddle = lowerLow.blend(upper, lowerHigh.compare(VectorOperators.GT, lowerThreshold));
            lower = SHORT8.cast(BYTE8.cast(ByteVector.fromArray(PIXEL, raw, secondOffset + i))
                    .convertShape(VectorOperators.ZERO_EXTEND_B2S, PIXEL_SHORTS, 0))
                    .lanewise(VectorOperators.ADD, lowerHigh.blend(lowerMiddle, lowerThreshold
                    .compare(VectorOperators.GT, lowerLow)))
                    .lanewise(VectorOperators.AND, PIXEL_LOW_BYTE);
            BYTE8.cast(SHORT8.cast(lower).convertShape(VectorOperators.S2B, PIXEL, 0)).intoArray(second, secondOut + i);
            upper = reconstructed;
            corner = above;
        }
        var tail = i;
        for (i = tail; i < length; i++) {
            var a = first[firstOut + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            var c = prior[priorOffset + i - distance] & 0xFF;
            first[firstOut + i] = (byte) (raw[firstOffset + i] + paeth(a, b, c));
        }
        for (i = tail; i < length; i++) {
            var a = second[secondOut + i - distance] & 0xFF;
            var b = first[firstOut + i] & 0xFF;
            var c = first[firstOut + i - distance] & 0xFF;
            second[secondOut + i] = (byte) (raw[secondOffset + i] + paeth(a, b, c));
        }
    }

    @Override
    public int filterNone(byte[] row, int offset, int length, byte[] out, int outOffset) {
        var sum = 0;
        var head = 0;
        var i = head;
        while (i + LANES <= length) {
            var accumulator = SHORT16.cast(ShortVector.zero(SHORTS));
            for (var steps = 0; steps < COST_FLUSH && i + LANES <= length; steps++, i += LANES) {
                var vector = BYTE16.cast(ByteVector.fromArray(BYTES, row, offset + i));
                BYTE16.cast(vector).intoArray(out, outOffset + i);
                accumulator = SHORT16.cast(BYTE16.cast(vector.lanewise(VectorOperators.ABS))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2S, SHORTS, 0))
                        .lanewise(VectorOperators.ADD, accumulator);
            }
            var pairs = INT_VECTOR.cast(SHORT16.cast(ZERO_SHORTS.lanewise(VectorOperators.ADD, accumulator))
                    .reinterpretShape(INTS, 0));
            sum += pairs.lanewise(VectorOperators.AND, LOW_HALF)
                    .lanewise(VectorOperators.ADD, pairs.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        for (; i < length; i++) {
            var value = row[offset + i];
            out[outOffset + i] = value;
            sum += Math.abs((int) value);
        }
        return sum;
    }

    @Override
    public int filterSub(byte[] row, int offset, int length, int distance, byte[] out, int outOffset) {
        var sum = 0;
        var head = Math.min(distance, length);
        for (var k = 0; k < head; k++) {
            var value = row[offset + k];
            out[outOffset + k] = value;
            sum += Math.abs((int) value);
        }
        var i = head;
        while (i + LANES <= length) {
            var accumulator = SHORT16.cast(ShortVector.zero(SHORTS));
            for (var steps = 0; steps < COST_FLUSH && i + LANES <= length; steps++, i += LANES) {
                var vector = BYTE16.cast(ByteVector.fromArray(BYTES, row, offset + i))
                        .lanewise(VectorOperators.SUB, ByteVector.fromArray(BYTES, row, offset + i - distance));
                BYTE16.cast(vector).intoArray(out, outOffset + i);
                accumulator = SHORT16.cast(BYTE16.cast(vector.lanewise(VectorOperators.ABS))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2S, SHORTS, 0))
                        .lanewise(VectorOperators.ADD, accumulator);
            }
            var pairs = INT_VECTOR.cast(SHORT16.cast(ZERO_SHORTS.lanewise(VectorOperators.ADD, accumulator))
                    .reinterpretShape(INTS, 0));
            sum += pairs.lanewise(VectorOperators.AND, LOW_HALF)
                    .lanewise(VectorOperators.ADD, pairs.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        for (; i < length; i++) {
            var value = (byte) (row[offset + i] - row[offset + i - distance]);
            out[outOffset + i] = value;
            sum += Math.abs((int) value);
        }
        return sum;
    }

    @Override
    public int filterUp(byte[] row, int offset, byte[] prior, int priorOffset, int length, byte[] out, int outOffset) {
        var sum = 0;
        var head = 0;
        var i = head;
        while (i + LANES <= length) {
            var accumulator = SHORT16.cast(ShortVector.zero(SHORTS));
            for (var steps = 0; steps < COST_FLUSH && i + LANES <= length; steps++, i += LANES) {
                var vector = BYTE16.cast(ByteVector.fromArray(BYTES, row, offset + i))
                        .lanewise(VectorOperators.SUB, ByteVector.fromArray(BYTES, prior, priorOffset + i));
                BYTE16.cast(vector).intoArray(out, outOffset + i);
                accumulator = SHORT16.cast(BYTE16.cast(vector.lanewise(VectorOperators.ABS))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2S, SHORTS, 0))
                        .lanewise(VectorOperators.ADD, accumulator);
            }
            var pairs = INT_VECTOR.cast(SHORT16.cast(ZERO_SHORTS.lanewise(VectorOperators.ADD, accumulator))
                    .reinterpretShape(INTS, 0));
            sum += pairs.lanewise(VectorOperators.AND, LOW_HALF)
                    .lanewise(VectorOperators.ADD, pairs.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        for (; i < length; i++) {
            var value = (byte) (row[offset + i] - prior[priorOffset + i]);
            out[outOffset + i] = value;
            sum += Math.abs((int) value);
        }
        return sum;
    }

    @Override
    public int filterAverage(byte[] row, int offset, byte[] prior, int priorOffset, int length, int distance,
                             byte[] out, int outOffset) {
        var sum = 0;
        var head = Math.min(distance, length);
        for (var k = 0; k < head; k++) {
            var value = (byte) (row[offset + k] - ((prior[priorOffset + k] & 0xFF) >>> 1));
            out[outOffset + k] = value;
            sum += Math.abs((int) value);
        }
        var i = head;
        while (i + LANES <= length) {
            var accumulator = SHORT16.cast(ShortVector.zero(SHORTS));
            for (var steps = 0; steps < COST_FLUSH && i + LANES <= length; steps++, i += LANES) {
                var a = BYTE16.cast(ByteVector.fromArray(BYTES, row, offset + i - distance));
                var b = BYTE16.cast(ByteVector.fromArray(BYTES, prior, priorOffset + i));
                // floor((a + b) / 2) without widening: (a & b) + ((a ^ b) >>> 1)
                var average = a.lanewise(VectorOperators.AND, b)
                        .lanewise(VectorOperators.ADD, BYTE16.cast(a.lanewise(VectorOperators.XOR, b))
                        .lanewise(VectorOperators.LSHR, AVERAGE_SHIFT));
                var vector = BYTE16.cast(ByteVector.fromArray(BYTES, row, offset + i))
                        .lanewise(VectorOperators.SUB, average);
                BYTE16.cast(vector).intoArray(out, outOffset + i);
                accumulator = SHORT16.cast(BYTE16.cast(vector.lanewise(VectorOperators.ABS))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2S, SHORTS, 0))
                        .lanewise(VectorOperators.ADD, accumulator);
            }
            var pairs = INT_VECTOR.cast(SHORT16.cast(ZERO_SHORTS.lanewise(VectorOperators.ADD, accumulator))
                    .reinterpretShape(INTS, 0));
            sum += pairs.lanewise(VectorOperators.AND, LOW_HALF)
                    .lanewise(VectorOperators.ADD, pairs.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        for (; i < length; i++) {
            var a = row[offset + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            var value = (byte) (row[offset + i] - ((a + b) >>> 1));
            out[outOffset + i] = value;
            sum += Math.abs((int) value);
        }
        return sum;
    }

    @Override
    public int filterPaeth(byte[] row, int offset, byte[] prior, int priorOffset, int length, int distance,
                           byte[] out, int outOffset) {
        var sum = 0;
        var head = Math.min(distance, length);
        for (var k = 0; k < head; k++) {
            var value = (byte) (row[offset + k] - prior[priorOffset + k]);
            out[outOffset + k] = value;
            sum += Math.abs((int) value);
        }
        var i = head;
        while (i + LANES <= length) {
            var accumulator = SHORT16.cast(ShortVector.zero(SHORTS));
            for (var steps = 0; steps < COST_FLUSH && i + LANES <= length; steps++, i += LANES) {
                var a = SHORT16.cast(BYTE16.cast(ByteVector.fromArray(BYTES, row, offset + i - distance))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, SHORTS, 0));
                var b = SHORT16.cast(BYTE16.cast(ByteVector.fromArray(BYTES, prior, priorOffset + i))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, SHORTS, 0));
                var c = SHORT16.cast(BYTE16.cast(ByteVector.fromArray(BYTES, prior, priorOffset + i - distance))
                        .convertShape(VectorOperators.ZERO_EXTEND_B2S, SHORTS, 0));
                var threshold = THREE.lanewise(VectorOperators.MUL, c).lanewise(VectorOperators.SUB, a
                        .lanewise(VectorOperators.ADD, b));
                var low = a.lanewise(VectorOperators.MIN, b);
                var high = a.lanewise(VectorOperators.MAX, b);
                var middle = c.blend(low, high.compare(VectorOperators.LE, threshold));
                var predictor = middle.blend(high, threshold.compare(VectorOperators.LE, low));
                var narrowed = BYTE16.cast(SHORT16.cast(predictor).convertShape(VectorOperators.S2B, BYTES, 0));
                var vector = BYTE16.cast(ByteVector.fromArray(BYTES, row, offset + i))
                        .lanewise(VectorOperators.SUB, narrowed);
                BYTE16.cast(vector).intoArray(out, outOffset + i);
                accumulator = SHORT16.cast(BYTE16.cast(vector.lanewise(VectorOperators.ABS))
                                .convertShape(VectorOperators.ZERO_EXTEND_B2S, SHORTS, 0))
                        .lanewise(VectorOperators.ADD, accumulator);
            }
            var pairs = INT_VECTOR.cast(SHORT16.cast(ZERO_SHORTS.lanewise(VectorOperators.ADD, accumulator))
                    .reinterpretShape(INTS, 0));
            sum += pairs.lanewise(VectorOperators.AND, LOW_HALF)
                    .lanewise(VectorOperators.ADD, pairs.lanewise(VectorOperators.LSHR, 16))
                    .reduceLanes(VectorOperators.ADD);
        }
        for (; i < length; i++) {
            var a = row[offset + i - distance] & 0xFF;
            var b = prior[priorOffset + i] & 0xFF;
            var c = prior[priorOffset + i - distance] & 0xFF;
            var value = (byte) (row[offset + i] - paeth(a, b, c));
            out[outOffset + i] = value;
            sum += Math.abs((int) value);
        }
        return sum;
    }
}
