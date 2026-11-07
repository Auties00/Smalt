package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.internal.simd.KernelSetup;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Applies hue, saturation and value tables with the Vector API: the vector twin of
 * {@link ScalarHueSatKernel}.
 *
 * <p>Every lane runs the scalar kernel's arithmetic in the same order and single precision,
 * without fused multiply-add, so the results are bit-identical to it. A row is processed in chunks
 * of {@link #CHUNK} pixels and passes: the first converts the pixels to hue, saturation and value
 * and finds the table cells and interpolation weights, the next ones place each pixel on the value
 * axis and interpolate the three adjustments one channel at a time, and the last applies them and
 * converts back. Lanes whose pixel the scalar kernel would leave alone (the largest component not
 * positive) compute clamped cell indices and are restored from the input at the end; the pixels
 * after the last whole vector go through the scalar kernel.
 *
 * <p>With eight lanes the cells are read as a three-dimensional table lookup: the table's
 * {@linkplain HueSatTable#corners() corner table} holds, for every cell and channel, the eight
 * values of a trilinear interpolation (the four hue and saturation corners of the lower and the
 * upper value slice) next to each other, so one vector load fetches the corners of one pixel and
 * an eight by eight transpose turns the loads of eight pixels into one vector per corner. Tables
 * with one value division store the four corners of two channels per vector instead. With four
 * lanes the corners are read one by one.
 *
 * @implNote This implementation follows the rules of the module's JIT guard: loads and
 * conversions are cast through constant classes, operations use the per-shape
 * {@code lanewise}, {@code compare} and {@code blend} methods with vector operands, no
 * vector crosses a loop iteration or a branch, and the passes exchange their values
 * through per-thread scratch arrays rather than vector-typed helpers. The corner table
 * replaces the Vector API gathers, which dispatch through a profile every vector shape
 * shares and which the C2 compiler left on their allocating fallback with that profile
 * polluted: its loads are plain vector loads at a computed offset, and the transpose uses
 * single-vector rearrangements and blends. The cells of the four-lane path and the sRGB
 * encoding of the value axis are read with scalar loads into the scratch arrays.
 */
final class VectorHueSatKernel implements HueSatKernel {
    /**
     * The shape of the float vectors: 256 bits where the platform prefers at least that, else 128.
     */
    private static final VectorSpecies<Float> F = FloatVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? FloatVector.SPECIES_256 : FloatVector.SPECIES_128;

    /**
     * The shape of the int vectors, with as many lanes as {@link #F}.
     */
    private static final VectorSpecies<Integer> I = F.vectorBitSize() == 256 ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The number of lanes.
     */
    private static final int L = F.length();

    /**
     * The concrete class of the float vectors.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(F);

    /**
     * The concrete class of the int vectors.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * Whether the cells are read through the corner table: the transpose needs eight lanes.
     */
    private static final boolean LOOKUP = L == 8;

    /**
     * Moves lane {@code i - 1} to lane {@code i}, wrapping around: the first stage of the
     * transpose.
     */
    private static final VectorShuffle<Float> UP_1 = VectorShuffle.fromOp(F, lane -> (lane - 1) & (L - 1));

    /**
     * Moves lane {@code i + 1} to lane {@code i}, wrapping around.
     */
    private static final VectorShuffle<Float> DOWN_1 = VectorShuffle.fromOp(F, lane -> (lane + 1) & (L - 1));

    /**
     * Moves lane {@code i - 2} to lane {@code i}, wrapping around: the second stage of the
     * transpose.
     */
    private static final VectorShuffle<Float> UP_2 = VectorShuffle.fromOp(F, lane -> (lane - 2) & (L - 1));

    /**
     * Moves lane {@code i + 2} to lane {@code i}, wrapping around.
     */
    private static final VectorShuffle<Float> DOWN_2 = VectorShuffle.fromOp(F, lane -> (lane + 2) & (L - 1));

    /**
     * Swaps the lower and upper halves of the lanes: the third stage of the transpose.
     */
    private static final VectorShuffle<Float> SWAP_4 = VectorShuffle.fromOp(F, lane -> (lane + L / 2) & (L - 1));

    /**
     * Marks the lanes whose index has bit 0 set.
     */
    private static final VectorMask<Float> BIT_1 = lanes(1);

    /**
     * Marks the lanes whose index has bit 1 set.
     */
    private static final VectorMask<Float> BIT_2 = lanes(2);

    /**
     * Marks the lanes whose index has bit 2 set.
     */
    private static final VectorMask<Float> BIT_4 = lanes(4);

    /**
     * The pixels of a chunk, a multiple of every lane count.
     */
    static final int CHUNK = 1024;

    /**
     * The scratch arrays of each thread.
     */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * Constructs the kernel.
     */
    private VectorHueSatKernel() {
    }

    /**
     * Creates the kernel.
     *
     * @return the kernel
     */
    static HueSatKernel create() {
        return new VectorHueSatKernel();
    }

    /**
     * Returns the concrete vector class of a species.
     *
     * @param species the vector species whose class is returned
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds the mask of the lanes whose index has a bit set.
     *
     * @param bit the bit of the lane index, as a mask such as 1, 2 or 4
     * @return the mask
     */
    private static VectorMask<Float> lanes(int bit) {
        var bits = new boolean[L];
        for (var lane = 0; lane < L; lane++) {
            bits[lane] = (lane & bit) != 0;
        }
        return VectorMask.fromArray(F, bits, 0);
    }

    @Override
    @KernelSetup("builds the corner table of a table once, before its first row")
    public void prepare(HueSatTable table) {
        if (LOOKUP) {
            table.corners();
        }
    }

    @Override
    public void apply(HueSatTable table, float[] r, float[] g, float[] b, int count) {
        var scratch = SCRATCH.get();
        var bound = count - count % L;
        for (var start = 0; start < bound; start += CHUNK) {
            var length = Math.min(CHUNK, bound - start);
            locate(table, r, g, b, start, length, scratch);
            if (table.values > 1) {
                encode(table, length, scratch);
                slice(table, length, scratch);
            }
            if (LOOKUP) {
                var corners = table.corners();
                if (table.values > 1) {
                    lookUpVolume(corners, 0, scratch.shift, length, scratch);
                    lookUpVolume(corners, 1, scratch.satScale, length, scratch);
                    lookUpVolume(corners, 2, scratch.valScale, length, scratch);
                } else {
                    lookUpPlane(corners, 0, scratch.shift, scratch.satScale, length, scratch);
                    lookUpPlane(corners, 8, scratch.valScale, scratch.c00, length, scratch);
                }
                convert(r, g, b, start, length, scratch);
                continue;
            }
            for (var channel = 0; channel < 3; channel++) {
                gather(table, channel, length, scratch);
                var target = channel == 0 ? scratch.shift : channel == 1 ? scratch.satScale : scratch.valScale;
                if (table.values > 1) {
                    interpolateVolume(target, length, scratch);
                } else {
                    interpolatePlane(target, length, scratch);
                }
            }
            convert(r, g, b, start, length, scratch);
        }
        ScalarHueSatKernel.pixels(table, r, g, b, bound, count);
    }

    /**
     * Converts a chunk to hue, saturation and value and finds the cells and weights of the hue and
     * saturation interpolation.
     *
     * @param table the hue, saturation and value table being applied
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param start the first pixel of the chunk
     * @param length the pixels of the chunk, a multiple of the lane count
     * @param scratch the scratch arrays to fill
     */
    private static void locate(HueSatTable table, float[] r, float[] g, float[] b, int start, int length, Scratch scratch) {
        var zero = FLOAT_VECTOR.cast(FloatVector.zero(F));
        var one = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 1f));
        var two = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 2f));
        var four = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 4f));
        var six = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 6f));
        var hueScale = FLOAT_VECTOR.cast(FloatVector.broadcast(F, table.hueScale));
        var saturationSpan = FLOAT_VECTOR.cast(FloatVector.broadcast(F, (float) (table.saturations - 1)));
        var zeroInt = INT_VECTOR.cast(IntVector.zero(I));
        var oneInt = INT_VECTOR.cast(IntVector.broadcast(I, 1));
        var threeInt = INT_VECTOR.cast(IntVector.broadcast(I, 3));
        var hues = INT_VECTOR.cast(IntVector.broadcast(I, table.hues));
        var lastHue = INT_VECTOR.cast(IntVector.broadcast(I, table.hues - 1));
        var lastSaturation = INT_VECTOR.cast(IntVector.broadcast(I, table.saturations - 2));
        var hueStride = INT_VECTOR.cast(IntVector.broadcast(I, table.saturations * 3));
        var saturations = INT_VECTOR.cast(IntVector.broadcast(I, table.saturations));
        for (var index = 0; index < length; index += L) {
            var red = FLOAT_VECTOR.cast(FloatVector.fromArray(F, r, start + index));
            var green = FLOAT_VECTOR.cast(FloatVector.fromArray(F, g, start + index));
            var blue = FLOAT_VECTOR.cast(FloatVector.fromArray(F, b, start + index));
            var max = red.lanewise(VectorOperators.MAX, green).lanewise(VectorOperators.MAX, blue);
            var min = red.lanewise(VectorOperators.MIN, green).lanewise(VectorOperators.MIN, blue);
            var chroma = max.lanewise(VectorOperators.SUB, min);
            var saturation = chroma.lanewise(VectorOperators.DIV, max);
            var redHue = green.lanewise(VectorOperators.SUB, blue).lanewise(VectorOperators.DIV, chroma);
            redHue = redHue.blend(redHue.lanewise(VectorOperators.ADD, six), redHue.compare(VectorOperators.LT, zero));
            var greenHue = FLOAT_VECTOR.cast(two).lanewise(VectorOperators.ADD, blue.lanewise(VectorOperators.SUB, red)
                    .lanewise(VectorOperators.DIV, chroma));
            var blueHue = FLOAT_VECTOR.cast(four).lanewise(VectorOperators.ADD, red.lanewise(VectorOperators.SUB, green)
                    .lanewise(VectorOperators.DIV, chroma));
            var hue = blueHue.blend(greenHue, max.compare(VectorOperators.EQ, green))
                    .blend(redHue, max.compare(VectorOperators.EQ, red))
                    .blend(zero, chroma.compare(VectorOperators.LE, zero));
            var hueScaled = hue.lanewise(VectorOperators.MUL, hueScale);
            var h0 = INT_VECTOR.cast(hueScaled.convertShape(VectorOperators.F2I, I, 0));
            var hueFraction = hueScaled.lanewise(VectorOperators.SUB,
                    FLOAT_VECTOR.cast(h0.convertShape(VectorOperators.I2F, F, 0)));
            h0 = h0.blend(h0.lanewise(VectorOperators.SUB, hues), h0.compare(VectorOperators.GE, hues))
                    .lanewise(VectorOperators.MAX, zeroInt).lanewise(VectorOperators.MIN, lastHue);
            var h1 = h0.lanewise(VectorOperators.ADD, oneInt);
            h1 = h1.blend(zeroInt, h1.compare(VectorOperators.EQ, hues));
            var satScaled = saturation.lanewise(VectorOperators.MIN, one).lanewise(VectorOperators.MUL, saturationSpan);
            var s0 = INT_VECTOR.cast(satScaled.convertShape(VectorOperators.F2I, I, 0))
                    .lanewise(VectorOperators.MIN, lastSaturation).lanewise(VectorOperators.MAX, zeroInt);
            var satFraction = satScaled.lanewise(VectorOperators.SUB,
                    FLOAT_VECTOR.cast(s0.convertShape(VectorOperators.I2F, F, 0)));
            var saturationOffset = s0.lanewise(VectorOperators.MUL, threeInt);
            INT_VECTOR.cast(h0.lanewise(VectorOperators.MUL, saturations).lanewise(VectorOperators.ADD, s0))
                    .intoArray(scratch.cell, index);
            INT_VECTOR.cast(h0.lanewise(VectorOperators.MUL, hueStride).lanewise(VectorOperators.ADD, saturationOffset))
                    .intoArray(scratch.lower, index);
            INT_VECTOR.cast(h1.lanewise(VectorOperators.MUL, hueStride).lanewise(VectorOperators.ADD, saturationOffset))
                    .intoArray(scratch.upper, index);
            var hueLow = FLOAT_VECTOR.cast(one).lanewise(VectorOperators.SUB, hueFraction);
            var satLow = FLOAT_VECTOR.cast(one).lanewise(VectorOperators.SUB, satFraction);
            FLOAT_VECTOR.cast(hueLow.lanewise(VectorOperators.MUL, satLow)).intoArray(scratch.w00, index);
            FLOAT_VECTOR.cast(hueLow.lanewise(VectorOperators.MUL, satFraction)).intoArray(scratch.w01, index);
            FLOAT_VECTOR.cast(hueFraction.lanewise(VectorOperators.MUL, satLow)).intoArray(scratch.w10, index);
            FLOAT_VECTOR.cast(hueFraction.lanewise(VectorOperators.MUL, satFraction)).intoArray(scratch.w11, index);
            FLOAT_VECTOR.cast(hue).intoArray(scratch.hue, index);
            FLOAT_VECTOR.cast(saturation).intoArray(scratch.saturation, index);
            FLOAT_VECTOR.cast(max).intoArray(scratch.value, index);
        }
    }

    /**
     * Places the value of every pixel of a chunk on the table's value axis: encoded with the sRGB
     * transfer function, or clamped to 1 on a linear axis.
     *
     * @param table the hue, saturation and value table being applied
     * @param length the pixels of the chunk
     * @param scratch the scratch arrays
     */
    private static void encode(HueSatTable table, int length, Scratch scratch) {
        var value = scratch.value;
        var encoded = scratch.encoded;
        if (table.srgb) {
            for (var index = 0; index < length; index++) {
                encoded[index] = HueSatTable.encode(value[index]);
            }
        } else {
            for (var index = 0; index < length; index++) {
                encoded[index] = Math.min(value[index], 1f);
            }
        }
    }

    /**
     * Finds the value slice of a chunk, moving the cell indices to the lower slice and storing
     * the fraction towards the upper one.
     *
     * @param table the hue, saturation and value table being applied
     * @param length the pixels of the chunk
     * @param scratch the scratch arrays
     */
    private static void slice(HueSatTable table, int length, Scratch scratch) {
        var valueSpan = FLOAT_VECTOR.cast(FloatVector.broadcast(F, (float) (table.values - 1)));
        var zeroInt = INT_VECTOR.cast(IntVector.zero(I));
        var lastValue = INT_VECTOR.cast(IntVector.broadcast(I, table.values - 2));
        var valueStride = INT_VECTOR.cast(IntVector.broadcast(I, table.hues * table.saturations * 3));
        var valueCells = INT_VECTOR.cast(IntVector.broadcast(I, table.hues * table.saturations));
        for (var index = 0; index < length; index += L) {
            var valScaled = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.encoded, index))
                    .lanewise(VectorOperators.MUL, valueSpan);
            var v0 = INT_VECTOR.cast(valScaled.convertShape(VectorOperators.F2I, I, 0))
                    .lanewise(VectorOperators.MIN, lastValue).lanewise(VectorOperators.MAX, zeroInt);
            FLOAT_VECTOR.cast(valScaled.lanewise(VectorOperators.SUB, FLOAT_VECTOR.cast(v0.convertShape(VectorOperators.I2F, F, 0))))
                    .intoArray(scratch.fraction, index);
            var offset = v0.lanewise(VectorOperators.MUL, valueStride);
            INT_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(I, scratch.lower, index)).lanewise(VectorOperators.ADD, offset))
                    .intoArray(scratch.lower, index);
            INT_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(I, scratch.upper, index)).lanewise(VectorOperators.ADD, offset))
                    .intoArray(scratch.upper, index);
            INT_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(I, scratch.cell, index))
                            .lanewise(VectorOperators.ADD, v0.lanewise(VectorOperators.MUL, valueCells)))
                    .intoArray(scratch.cell, index);
        }
    }

    /**
     * Reads one channel of the cells around every pixel of a chunk: the four corners of the lower
     * value slice and, for a table with several value divisions, of the upper one.
     *
     * @param table the hue, saturation and value table being applied
     * @param channel the channel: 0 hue shift, 1 saturation scale, 2 value scale
     * @param length the pixels of the chunk
     * @param scratch the scratch arrays
     */
    private static void gather(HueSatTable table, int channel, int length, Scratch scratch) {
        var cells = table.cells;
        var lower = scratch.lower;
        var upper = scratch.upper;
        for (var index = 0; index < length; index++) {
            var low = lower[index] + channel;
            var high = upper[index] + channel;
            scratch.c00[index] = cells[low];
            scratch.c01[index] = cells[low + 3];
            scratch.c10[index] = cells[high];
            scratch.c11[index] = cells[high + 3];
        }
        if (table.values > 1) {
            var stride = table.hues * table.saturations * 3;
            for (var index = 0; index < length; index++) {
                var low = lower[index] + stride + channel;
                var high = upper[index] + stride + channel;
                scratch.d00[index] = cells[low];
                scratch.d01[index] = cells[low + 3];
                scratch.d10[index] = cells[high];
                scratch.d11[index] = cells[high + 3];
            }
        }
    }

    /**
     * Interpolates one adjustment of a chunk over hue and saturation, for a table with one value
     * division.
     *
     * @param target the array receiving the adjustment
     * @param length the pixels of the chunk
     * @param scratch the scratch arrays, with the channel's cells gathered
     */
    private static void interpolatePlane(float[] target, int length, Scratch scratch) {
        for (var index = 0; index < length; index += L) {
            var w00 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w00, index));
            var w01 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w01, index));
            var w10 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w10, index));
            var w11 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w11, index));
            var c00 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.c00, index));
            var c01 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.c01, index));
            var c10 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.c10, index));
            var c11 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.c11, index));
            FLOAT_VECTOR.cast(w00.lanewise(VectorOperators.MUL, c00)
                    .lanewise(VectorOperators.ADD, w01.lanewise(VectorOperators.MUL, c01))
                    .lanewise(VectorOperators.ADD, w10.lanewise(VectorOperators.MUL, c10))
                    .lanewise(VectorOperators.ADD, w11.lanewise(VectorOperators.MUL, c11)))
                    .intoArray(target, index);
        }
    }

    /**
     * Interpolates one adjustment of a chunk over hue, saturation and value, for a table with
     * several value divisions.
     *
     * @param target the array receiving the adjustment
     * @param length the pixels of the chunk
     * @param scratch the scratch arrays, with the channel's cells gathered
     */
    private static void interpolateVolume(float[] target, int length, Scratch scratch) {
        var one = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 1f));
        for (var index = 0; index < length; index += L) {
            var w00 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w00, index));
            var w01 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w01, index));
            var w10 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w10, index));
            var w11 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w11, index));
            var c00 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.c00, index));
            var c01 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.c01, index));
            var c10 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.c10, index));
            var c11 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.c11, index));
            var d00 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.d00, index));
            var d01 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.d01, index));
            var d10 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.d10, index));
            var d11 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.d11, index));
            var fraction = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.fraction, index));
            var near = w00.lanewise(VectorOperators.MUL, c00)
                    .lanewise(VectorOperators.ADD, w01.lanewise(VectorOperators.MUL, c01))
                    .lanewise(VectorOperators.ADD, w10.lanewise(VectorOperators.MUL, c10))
                    .lanewise(VectorOperators.ADD, w11.lanewise(VectorOperators.MUL, c11));
            var far = w00.lanewise(VectorOperators.MUL, d00)
                    .lanewise(VectorOperators.ADD, w01.lanewise(VectorOperators.MUL, d01))
                    .lanewise(VectorOperators.ADD, w10.lanewise(VectorOperators.MUL, d10))
                    .lanewise(VectorOperators.ADD, w11.lanewise(VectorOperators.MUL, d11));
            FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(one).lanewise(VectorOperators.SUB, fraction).lanewise(VectorOperators.MUL, near)
                    .lanewise(VectorOperators.ADD, fraction.lanewise(VectorOperators.MUL, far))).intoArray(target, index);
        }
    }

    /**
     * Interpolates one adjustment of a chunk over hue, saturation and value through the corner
     * table of a table with several value divisions.
     *
     * <p>Each group of eight pixels loads the eight corners of each pixel's cell, transposes them
     * into one vector per corner and interpolates them as
     * {@link #interpolateVolume(float[], int, Scratch)} does.
     *
     * @param corners the corner table, {@link HueSatTable#VOLUME_ENTRY} floats per cell
     * @param channel the channel: 0 hue shift, 1 saturation scale, 2 value scale
     * @param target the array receiving the adjustment
     * @param length the pixels of the chunk, a multiple of eight
     * @param scratch the scratch arrays
     */
    private static void lookUpVolume(float[] corners, int channel, float[] target, int length, Scratch scratch) {
        var cell = scratch.cell;
        var offset = 8 * channel;
        for (var index = 0; index < length; index += L) {
            var up1 = UP_1;
            var down1 = DOWN_1;
            var up2 = UP_2;
            var down2 = DOWN_2;
            var swap4 = SWAP_4;
            var bit1 = BIT_1;
            var bit2 = BIT_2;
            var bit4 = BIT_4;
            var r0 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.VOLUME_ENTRY * cell[index] + offset));
            var r1 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.VOLUME_ENTRY * cell[index + 1] + offset));
            var r2 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.VOLUME_ENTRY * cell[index + 2] + offset));
            var r3 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.VOLUME_ENTRY * cell[index + 3] + offset));
            var r4 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.VOLUME_ENTRY * cell[index + 4] + offset));
            var r5 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.VOLUME_ENTRY * cell[index + 5] + offset));
            var r6 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.VOLUME_ENTRY * cell[index + 6] + offset));
            var r7 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.VOLUME_ENTRY * cell[index + 7] + offset));
            var a0 = FLOAT_VECTOR.cast(r0.blend(FLOAT_VECTOR.cast(r1.rearrange(up1)), bit1));
            var a1 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(r0.rearrange(down1)).blend(r1, bit1));
            var a2 = FLOAT_VECTOR.cast(r2.blend(FLOAT_VECTOR.cast(r3.rearrange(up1)), bit1));
            var a3 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(r2.rearrange(down1)).blend(r3, bit1));
            var a4 = FLOAT_VECTOR.cast(r4.blend(FLOAT_VECTOR.cast(r5.rearrange(up1)), bit1));
            var a5 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(r4.rearrange(down1)).blend(r5, bit1));
            var a6 = FLOAT_VECTOR.cast(r6.blend(FLOAT_VECTOR.cast(r7.rearrange(up1)), bit1));
            var a7 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(r6.rearrange(down1)).blend(r7, bit1));
            var b0 = FLOAT_VECTOR.cast(a0.blend(FLOAT_VECTOR.cast(a2.rearrange(up2)), bit2));
            var b2 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(a0.rearrange(down2)).blend(a2, bit2));
            var b1 = FLOAT_VECTOR.cast(a1.blend(FLOAT_VECTOR.cast(a3.rearrange(up2)), bit2));
            var b3 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(a1.rearrange(down2)).blend(a3, bit2));
            var b4 = FLOAT_VECTOR.cast(a4.blend(FLOAT_VECTOR.cast(a6.rearrange(up2)), bit2));
            var b6 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(a4.rearrange(down2)).blend(a6, bit2));
            var b5 = FLOAT_VECTOR.cast(a5.blend(FLOAT_VECTOR.cast(a7.rearrange(up2)), bit2));
            var b7 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(a5.rearrange(down2)).blend(a7, bit2));
            var c00 = FLOAT_VECTOR.cast(b0.blend(FLOAT_VECTOR.cast(b4.rearrange(swap4)), bit4));
            var d00 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(b0.rearrange(swap4)).blend(b4, bit4));
            var c01 = FLOAT_VECTOR.cast(b1.blend(FLOAT_VECTOR.cast(b5.rearrange(swap4)), bit4));
            var d01 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(b1.rearrange(swap4)).blend(b5, bit4));
            var c10 = FLOAT_VECTOR.cast(b2.blend(FLOAT_VECTOR.cast(b6.rearrange(swap4)), bit4));
            var d10 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(b2.rearrange(swap4)).blend(b6, bit4));
            var c11 = FLOAT_VECTOR.cast(b3.blend(FLOAT_VECTOR.cast(b7.rearrange(swap4)), bit4));
            var d11 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(b3.rearrange(swap4)).blend(b7, bit4));
            var one = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 1f));
            var w00 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w00, index));
            var w01 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w01, index));
            var w10 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w10, index));
            var w11 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w11, index));
            var fraction = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.fraction, index));
            var near = w00.lanewise(VectorOperators.MUL, c00)
                    .lanewise(VectorOperators.ADD, w01.lanewise(VectorOperators.MUL, c01))
                    .lanewise(VectorOperators.ADD, w10.lanewise(VectorOperators.MUL, c10))
                    .lanewise(VectorOperators.ADD, w11.lanewise(VectorOperators.MUL, c11));
            var far = w00.lanewise(VectorOperators.MUL, d00)
                    .lanewise(VectorOperators.ADD, w01.lanewise(VectorOperators.MUL, d01))
                    .lanewise(VectorOperators.ADD, w10.lanewise(VectorOperators.MUL, d10))
                    .lanewise(VectorOperators.ADD, w11.lanewise(VectorOperators.MUL, d11));
            FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(one).lanewise(VectorOperators.SUB, fraction).lanewise(VectorOperators.MUL, near)
                    .lanewise(VectorOperators.ADD, fraction.lanewise(VectorOperators.MUL, far))).intoArray(target, index);
        }
    }

    /**
     * Interpolates two adjustments of a chunk over hue and saturation through the corner table of
     * a table with one value division.
     *
     * <p>Each group of eight pixels loads eight floats of each pixel's cell, the four corners of
     * two channels, transposes them into one vector per corner and channel and interpolates them
     * as {@link #interpolatePlane(float[], int, Scratch)} does.
     *
     * @param corners the corner table, {@link HueSatTable#PLANE_ENTRY} floats per cell
     * @param offset the offset of the channel pair in each cell's entry: 0 for the hue shift and
     * the saturation scale, 8 for the value scale and four unused floats
     * @param first the array receiving the adjustment of the first channel of the pair
     * @param second the array receiving the adjustment of the second channel of the pair
     * @param length the pixels of the chunk, a multiple of eight
     * @param scratch the scratch arrays
     */
    private static void lookUpPlane(float[] corners, int offset, float[] first, float[] second, int length,
                                    Scratch scratch) {
        var cell = scratch.cell;
        for (var index = 0; index < length; index += L) {
            var up1 = UP_1;
            var down1 = DOWN_1;
            var up2 = UP_2;
            var down2 = DOWN_2;
            var swap4 = SWAP_4;
            var bit1 = BIT_1;
            var bit2 = BIT_2;
            var bit4 = BIT_4;
            var r0 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.PLANE_ENTRY * cell[index] + offset));
            var r1 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.PLANE_ENTRY * cell[index + 1] + offset));
            var r2 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.PLANE_ENTRY * cell[index + 2] + offset));
            var r3 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.PLANE_ENTRY * cell[index + 3] + offset));
            var r4 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.PLANE_ENTRY * cell[index + 4] + offset));
            var r5 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.PLANE_ENTRY * cell[index + 5] + offset));
            var r6 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.PLANE_ENTRY * cell[index + 6] + offset));
            var r7 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, corners, HueSatTable.PLANE_ENTRY * cell[index + 7] + offset));
            var a0 = FLOAT_VECTOR.cast(r0.blend(FLOAT_VECTOR.cast(r1.rearrange(up1)), bit1));
            var a1 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(r0.rearrange(down1)).blend(r1, bit1));
            var a2 = FLOAT_VECTOR.cast(r2.blend(FLOAT_VECTOR.cast(r3.rearrange(up1)), bit1));
            var a3 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(r2.rearrange(down1)).blend(r3, bit1));
            var a4 = FLOAT_VECTOR.cast(r4.blend(FLOAT_VECTOR.cast(r5.rearrange(up1)), bit1));
            var a5 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(r4.rearrange(down1)).blend(r5, bit1));
            var a6 = FLOAT_VECTOR.cast(r6.blend(FLOAT_VECTOR.cast(r7.rearrange(up1)), bit1));
            var a7 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(r6.rearrange(down1)).blend(r7, bit1));
            var b0 = FLOAT_VECTOR.cast(a0.blend(FLOAT_VECTOR.cast(a2.rearrange(up2)), bit2));
            var b2 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(a0.rearrange(down2)).blend(a2, bit2));
            var b1 = FLOAT_VECTOR.cast(a1.blend(FLOAT_VECTOR.cast(a3.rearrange(up2)), bit2));
            var b3 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(a1.rearrange(down2)).blend(a3, bit2));
            var b4 = FLOAT_VECTOR.cast(a4.blend(FLOAT_VECTOR.cast(a6.rearrange(up2)), bit2));
            var b6 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(a4.rearrange(down2)).blend(a6, bit2));
            var b5 = FLOAT_VECTOR.cast(a5.blend(FLOAT_VECTOR.cast(a7.rearrange(up2)), bit2));
            var b7 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(a5.rearrange(down2)).blend(a7, bit2));
            var c00 = FLOAT_VECTOR.cast(b0.blend(FLOAT_VECTOR.cast(b4.rearrange(swap4)), bit4));
            var e00 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(b0.rearrange(swap4)).blend(b4, bit4));
            var c01 = FLOAT_VECTOR.cast(b1.blend(FLOAT_VECTOR.cast(b5.rearrange(swap4)), bit4));
            var e01 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(b1.rearrange(swap4)).blend(b5, bit4));
            var c10 = FLOAT_VECTOR.cast(b2.blend(FLOAT_VECTOR.cast(b6.rearrange(swap4)), bit4));
            var e10 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(b2.rearrange(swap4)).blend(b6, bit4));
            var c11 = FLOAT_VECTOR.cast(b3.blend(FLOAT_VECTOR.cast(b7.rearrange(swap4)), bit4));
            var e11 = FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(b3.rearrange(swap4)).blend(b7, bit4));
            var w00 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w00, index));
            var w01 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w01, index));
            var w10 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w10, index));
            var w11 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.w11, index));
            FLOAT_VECTOR.cast(w00.lanewise(VectorOperators.MUL, c00)
                    .lanewise(VectorOperators.ADD, w01.lanewise(VectorOperators.MUL, c01))
                    .lanewise(VectorOperators.ADD, w10.lanewise(VectorOperators.MUL, c10))
                    .lanewise(VectorOperators.ADD, w11.lanewise(VectorOperators.MUL, c11)))
                    .intoArray(first, index);
            FLOAT_VECTOR.cast(w00.lanewise(VectorOperators.MUL, e00)
                    .lanewise(VectorOperators.ADD, w01.lanewise(VectorOperators.MUL, e01))
                    .lanewise(VectorOperators.ADD, w10.lanewise(VectorOperators.MUL, e10))
                    .lanewise(VectorOperators.ADD, w11.lanewise(VectorOperators.MUL, e11)))
                    .intoArray(second, index);
        }
    }

    /**
     * Applies the interpolated adjustments to a chunk and converts it back to RGB, keeping the
     * pixels whose largest component is not positive.
     *
     * @param r the red values
     * @param g the green values
     * @param b the blue values
     * @param start the first pixel of the chunk
     * @param length the pixels of the chunk
     * @param scratch the scratch arrays
     */
    private static void convert(float[] r, float[] g, float[] b, int start, int length, Scratch scratch) {
        var zero = FLOAT_VECTOR.cast(FloatVector.zero(F));
        var one = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 1f));
        var two = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 2f));
        var three = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 3f));
        var four = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 4f));
        var six = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 6f));
        var degrees = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 360f));
        for (var index = 0; index < length; index += L) {
            var red = FLOAT_VECTOR.cast(FloatVector.fromArray(F, r, start + index));
            var green = FLOAT_VECTOR.cast(FloatVector.fromArray(F, g, start + index));
            var blue = FLOAT_VECTOR.cast(FloatVector.fromArray(F, b, start + index));
            var shift = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.shift, index));
            var hue = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.hue, index))
                    .lanewise(VectorOperators.ADD, shift.lanewise(VectorOperators.MUL, six).lanewise(VectorOperators.DIV, degrees));
            hue = hue.blend(hue.lanewise(VectorOperators.ADD, six), hue.compare(VectorOperators.LT, zero))
                    .blend(hue.lanewise(VectorOperators.SUB, six), hue.compare(VectorOperators.GE, six));
            var saturation = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.saturation, index))
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.satScale, index)))
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, one);
            var value = FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.value, index))
                    .lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch.valScale, index)));
            var p = value.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(one).lanewise(VectorOperators.SUB, saturation));
            var sector = FLOAT_VECTOR.cast(INT_VECTOR.cast(hue.convertShape(VectorOperators.F2I, I, 0))
                    .convertShape(VectorOperators.I2F, F, 0));
            var f = hue.lanewise(VectorOperators.SUB, sector);
            var q = value.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(one).lanewise(VectorOperators.SUB, saturation.lanewise(VectorOperators.MUL, f)));
            var t = value.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(one).lanewise(VectorOperators.SUB,
                    saturation.lanewise(VectorOperators.MUL, FLOAT_VECTOR.cast(one).lanewise(VectorOperators.SUB, f))));
            var s0 = sector.compare(VectorOperators.EQ, zero);
            var s1 = sector.compare(VectorOperators.EQ, one);
            var s2 = sector.compare(VectorOperators.EQ, two);
            var s3 = sector.compare(VectorOperators.EQ, three);
            var s4 = sector.compare(VectorOperators.EQ, four);
            var valid = red.lanewise(VectorOperators.MAX, green).lanewise(VectorOperators.MAX, blue)
                    .compare(VectorOperators.GT, zero);
            FLOAT_VECTOR.cast(red.blend(value.blend(q, s1).blend(p, s2.or(s3)).blend(t, s4), valid)).intoArray(r, start + index);
            FLOAT_VECTOR.cast(green.blend(p.blend(t, s0).blend(value, s1.or(s2)).blend(q, s3), valid)).intoArray(g, start + index);
            FLOAT_VECTOR.cast(blue.blend(q.blend(p, s0.or(s1)).blend(t, s2).blend(value, s3.or(s4)), valid)).intoArray(b, start + index);
        }
    }

    /**
     * Holds the per-thread arrays the passes exchange values through.
     */
    private static final class Scratch {
        /**
         * The index of each pixel's cell at the lower hue and saturation.
         */
        final int[] lower = new int[CHUNK];

        /**
         * The index of each pixel's cell at the higher hue and lower saturation.
         */
        final int[] upper = new int[CHUNK];

        /**
         * The number of each pixel's cell at the lower hue, saturation and value: its index
         * divided by three, the entry of {@link HueSatTable#corners()} it reads.
         */
        final int[] cell = new int[CHUNK];

        /**
         * The weight of the lower hue and saturation.
         */
        final float[] w00 = new float[CHUNK];

        /**
         * The weight of the lower hue and higher saturation.
         */
        final float[] w01 = new float[CHUNK];

        /**
         * The weight of the higher hue and lower saturation.
         */
        final float[] w10 = new float[CHUNK];

        /**
         * The weight of the higher hue and saturation.
         */
        final float[] w11 = new float[CHUNK];

        /**
         * The value on the table's value axis.
         */
        final float[] encoded = new float[CHUNK];

        /**
         * The fraction towards the upper value slice.
         */
        final float[] fraction = new float[CHUNK];

        /**
         * The current channel of the cell at the lower hue and saturation, lower value slice.
         */
        final float[] c00 = new float[CHUNK];

        /**
         * The current channel of the cell at the lower hue and higher saturation, lower value slice.
         */
        final float[] c01 = new float[CHUNK];

        /**
         * The current channel of the cell at the higher hue and lower saturation, lower value slice.
         */
        final float[] c10 = new float[CHUNK];

        /**
         * The current channel of the cell at the higher hue and saturation, lower value slice.
         */
        final float[] c11 = new float[CHUNK];

        /**
         * The current channel of the cell at the lower hue and saturation, upper value slice.
         */
        final float[] d00 = new float[CHUNK];

        /**
         * The current channel of the cell at the lower hue and higher saturation, upper value slice.
         */
        final float[] d01 = new float[CHUNK];

        /**
         * The current channel of the cell at the higher hue and lower saturation, upper value slice.
         */
        final float[] d10 = new float[CHUNK];

        /**
         * The current channel of the cell at the higher hue and saturation, upper value slice.
         */
        final float[] d11 = new float[CHUNK];

        /**
         * The hue, 0 to 6.
         */
        final float[] hue = new float[CHUNK];

        /**
         * The saturation.
         */
        final float[] saturation = new float[CHUNK];

        /**
         * The value, the largest component.
         */
        final float[] value = new float[CHUNK];

        /**
         * The interpolated hue shift.
         */
        final float[] shift = new float[CHUNK];

        /**
         * The interpolated saturation scale.
         */
        final float[] satScale = new float[CHUNK];

        /**
         * The interpolated value scale.
         */
        final float[] valScale = new float[CHUNK];
    }
}
