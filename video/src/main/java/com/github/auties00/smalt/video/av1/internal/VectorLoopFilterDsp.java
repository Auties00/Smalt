package com.github.auties00.smalt.video.av1.internal;

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the deblocking filter kernels with the Vector API, producing output identical to
 * {@link ScalarLoopFilterDsp}.
 *
 * <p>Row edges of 8- and 10-bit planes are filtered four 4-sample segments at a time, one 16-bit
 * lane per sample column: every lane carries its segment's limits and filter width, and the filter
 * decisions are lane masks. A decision kernel classifies every lane from the unfiltered samples
 * and stores the classes in the scratch buffer; then each filter variant (narrow, 6-, 8- and
 * 16-wide) runs as its own kernel over the whole group, writing only the lanes of its class, which
 * keeps each kernel small. Lanes of unselected segments are written back unchanged, never past the
 * end of the row. Edges whose segments are all at most 4 wide are decided and filtered by one
 * kernel. Column edges run through the same kernels: the samples across the edge of the 16 lines
 * of four segments are transposed into the scratch buffer, filtered there as a row edge, and the
 * filtered columns of the selected segments are copied back. 12-bit planes and groups that would
 * leave the plane use {@link ScalarLoopFilterDsp}.
 *
 * <p>Every Vector API operation goes through the per-shape methods, no method takes or returns a
 * vector, and every loaded vector, and every vector shifted by a scalar, is first cast through the
 * {@code static final} class of its species; see {@link VectorMcDsp}.
 *
 * <p>Generated from {@code tools/codegen/av1}; edit the template.
 *
 * @implNote This implementation follows dav1d's x86 loop filter ({@code src/x86/loopfilter16_avx2.asm}),
 *           which also filters 16 sample columns of a row edge per vector with per-lane limits and
 *           widths; the 16-wide filter updates a running sum, adding and removing two taps per
 *           output.
 */
public final class VectorLoopFilterDsp extends LoopFilterDsp {
    /**
     * The 16-lane short species of a group of four segments.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The exact class of {@link #S256} vectors.
     */
    private static final Class<ShortVector> SHORT256 = vectorClass(S256);

    /**
     * Selects the lanes of the segments after the first.
     */
    private static final VectorMask<Short> FROM_4 = VectorMask.fromLong(S256, 0xFFF0L);

    /**
     * Selects the lanes of the segments after the second.
     */
    private static final VectorMask<Short> FROM_8 = VectorMask.fromLong(S256, 0xFF00L);

    /**
     * Selects the lanes of the last segment.
     */
    private static final VectorMask<Short> FROM_12 = VectorMask.fromLong(S256, 0xF000L);

    /**
     * Sixteen shorts equal to {@code 0}.
     */
    private static final ShortVector ZERO = ShortVector.zero(S256);

    /**
     * Sixteen shorts equal to {@code 1}.
     */
    private static final ShortVector ONE = ShortVector.broadcast(S256, (short) 1);

    /**
     * Sixteen shorts equal to {@code 2}.
     */
    private static final ShortVector TWO = ShortVector.broadcast(S256, (short) 2);

    /**
     * Sixteen shorts equal to {@code 3}.
     */
    private static final ShortVector THREE = ShortVector.broadcast(S256, (short) 3);

    /**
     * Sixteen shorts equal to {@code 4}.
     */
    private static final ShortVector FOUR = ShortVector.broadcast(S256, (short) 4);

    /**
     * Sixteen shorts equal to {@code 6}.
     */
    private static final ShortVector SIX = ShortVector.broadcast(S256, (short) 6);

    /**
     * Sixteen shorts equal to {@code 7}.
     */
    private static final ShortVector SEVEN = ShortVector.broadcast(S256, (short) 7);

    /**
     * Sixteen shorts equal to {@code 8}.
     */
    private static final ShortVector EIGHT = ShortVector.broadcast(S256, (short) 8);

    /**
     * Sixteen shorts equal to {@code 16}.
     */
    private static final ShortVector SIXTEEN = ShortVector.broadcast(S256, (short) 16);

    /**
     * The offset in the scratch buffer of the transposed samples of a column edge group: 14 rows of
     * 16 lines, the row of each line's first sample right of the edge at index 7.
     */
    private static final int TRANSPOSED = 32;

    /**
     * The kernels used for the cases this class does not vectorize.
     */
    private final ScalarLoopFilterDsp scalar;

    /**
     * Constructs the kernels.
     */
    private VectorLoopFilterDsp() {
        this.scalar = new ScalarLoopFilterDsp();
    }

    /**
     * Creates the kernels.
     *
     * @return the vector kernels
     */
    static LoopFilterDsp create() {
        return new VectorLoopFilterDsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vectors are asked for
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void loopFilterSb(int plane, int dir, short[] dst, int dstOff, int stride, int mask0, int mask1,
                             int mask2, byte[] lvl, int lvlOff, int b4Stride, FilterLut lut, int length,
                             short[] scratch, int bitdepthMax) {
        if (bitdepthMax > 1023) {
            scalar.loopFilterSb(plane, dir, dst, dstOff, stride, mask0, mask1, mask2, lvl, lvlOff, b4Stride, lut,
                    length, scratch, bitdepthMax);
            return;
        }
        if (dir == DIR_COLUMN_EDGES) {
            columns(plane, dst, dstOff, stride, mask0, mask1, mask2, lvl, lvlOff, b4Stride, lut, length, scratch,
                    bitdepthMax);
            return;
        }
        var luma = plane == PLANE_LUMA;
        var vm = luma ? mask0 | mask1 | mask2 : mask0 | mask1;
        var bitdepthMin8 = 24 - Integer.numberOfLeadingZeros(bitdepthMax);
        var above = 4 * b4Stride;
        var column = dstOff % stride;
        var before = luma ? 7 : 3;
        var after = luma ? 6 : 2;
        for (var g = 0; g < 32 && (vm >>> g) != 0; g += 4) {
            if (((vm >>> g) & 15) == 0) {
                continue;
            }
            var segment = lvlOff + 4 * g;
            var s0 = segment(luma, vm, mask1, mask2, 1 << g, lvl, segment, above, lut);
            var s1 = segment(luma, vm, mask1, mask2, 2 << g, lvl, segment + 4, above, lut);
            var s2 = segment(luma, vm, mask1, mask2, 4 << g, lvl, segment + 8, above, lut);
            var s3 = segment(luma, vm, mask1, mask2, 8 << g, lvl, segment + 12, above, lut);
            if ((s0 | s1 | s2 | s3) == 0) {
                continue;
            }
            var off = dstOff + 4 * g;
            if (column + 4 * g + 16 > stride || off - before * stride < 0 || off + after * stride + 16 > dst.length) {
                var selected = 15 << g;
                scalar.loopFilterSb(plane, dir, dst, dstOff, stride, mask0 & selected, mask1 & selected,
                        mask2 & selected, lvl, lvlOff, b4Stride, lut, length, scratch, bitdepthMax);
                continue;
            }
            filterGroup(dst, off, stride, s0, s1, s2, s3, bitdepthMin8, bitdepthMax, scratch);
        }
    }

    /**
     * Filters a group of four segments of a row edge, or of a transposed column edge.
     *
     * @param dst          the plane, or the transposed samples
     * @param off          the index of the first sample below the edge in the first segment
     * @param stride       the row stride
     * @param s0           the packed parameters of segment 0, see {@link #segment}
     * @param s1           the packed parameters of segment 1
     * @param s2           the packed parameters of segment 2
     * @param s3           the packed parameters of segment 3
     * @param bitdepthMin8 the bit depth minus 8
     * @param bitdepthMax  the largest sample value
     * @param scratch      the scratch buffer
     */
    private static void filterGroup(short[] dst, int off, int stride, int s0, int s1, int s2, int s3, int bitdepthMin8,
                                    int bitdepthMax, short[] scratch) {
        var flat = 1 << bitdepthMin8;
        var diffMin = -128 * flat;
        var diffMax = 128 * flat - 1;
        var maxWd = Math.max(Math.max(s0 >>> 24, s1 >>> 24), Math.max(s2 >>> 24, s3 >>> 24));
        var e0 = (s0 & 0xFF) << bitdepthMin8;
        var e1 = (s1 & 0xFF) << bitdepthMin8;
        var e2 = (s2 & 0xFF) << bitdepthMin8;
        var e3 = (s3 & 0xFF) << bitdepthMin8;
        var i0 = ((s0 >> 8) & 0xFF) << bitdepthMin8;
        var i1 = ((s1 >> 8) & 0xFF) << bitdepthMin8;
        var i2 = ((s2 >> 8) & 0xFF) << bitdepthMin8;
        var i3 = ((s3 >> 8) & 0xFF) << bitdepthMin8;
        var h0 = ((s0 >> 16) & 0xFF) << bitdepthMin8;
        var h1 = ((s1 >> 16) & 0xFF) << bitdepthMin8;
        var h2 = ((s2 >> 16) & 0xFF) << bitdepthMin8;
        var h3 = ((s3 >> 16) & 0xFF) << bitdepthMin8;
        var w0 = s0 >>> 24;
        var w1 = s1 >>> 24;
        var w2 = s2 >>> 24;
        var w3 = s3 >>> 24;
        if (maxWd == 4) {
            narrow4(dst, off, stride, e0, e1, e2, e3, i0, i1, i2, i3, h0, h1, h2, h3, w0, w1, w2, w3, diffMin,
                    diffMax, bitdepthMax);
            return;
        }
        var found = switch (maxWd) {
            case 6 -> decide6(dst, off, stride, e0, e1, e2, e3, i0, i1, i2, i3, h0, h1, h2, h3, w0, w1, w2, w3,
                    flat, scratch);
            case 8 -> decide8(dst, off, stride, e0, e1, e2, e3, i0, i1, i2, i3, h0, h1, h2, h3, w0, w1, w2, w3,
                    flat, scratch);
            default -> decide16(dst, off, stride, e0, e1, e2, e3, i0, i1, i2, i3, h0, h1, h2, h3, w0, w1, w2, w3,
                    flat, scratch);
        };
        if ((found & 1) != 0) {
            narrowApply(dst, off, stride, diffMin, diffMax, bitdepthMax, scratch);
        }
        if ((found & 2) != 0) {
            if (maxWd == 6) {
                flat6Apply(dst, off, stride, scratch);
            } else {
                flat8Apply(dst, off, stride, scratch);
            }
        }
        if ((found & 4) != 0) {
            flat16Apply(dst, off, stride, scratch);
        }
    }

    /**
     * Filters the column edges of a run, 16 lines (four segments) at a time through the transposed
     * copy in the scratch buffer.
     *
     * @param plane       {@link #PLANE_LUMA} or {@link #PLANE_CHROMA}
     * @param dst         the plane
     * @param dstOff      the index of the first sample right of the edge in the first block
     * @param stride      the plane's row stride
     * @param mask0       the blocks filtered at the narrowest width
     * @param mask1       the blocks filtered at the middle width
     * @param mask2       the blocks filtered at the widest width
     * @param lvl         the level cache
     * @param lvlOff      the index of the level byte of the first block
     * @param b4Stride    the level cache row stride, in blocks
     * @param lut         the filter limits of the frame
     * @param length      the number of blocks of the run within the picture
     * @param scratch     the scratch buffer
     * @param bitdepthMax the largest sample value
     */
    private void columns(int plane, short[] dst, int dstOff, int stride, int mask0, int mask1, int mask2, byte[] lvl,
                         int lvlOff, int b4Stride, FilterLut lut, int length, short[] scratch, int bitdepthMax) {
        var luma = plane == PLANE_LUMA;
        var vm = luma ? mask0 | mask1 | mask2 : mask0 | mask1;
        var bitdepthMin8 = 24 - Integer.numberOfLeadingZeros(bitdepthMax);
        var lstep = 4 * b4Stride;
        for (var g = 0; g < 32 && (vm >>> g) != 0; g += 4) {
            if (((vm >>> g) & 15) == 0) {
                continue;
            }
            var segment = lvlOff + g * lstep;
            var s0 = segment(luma, vm, mask1, mask2, 1 << g, lvl, segment, 4, lut);
            var s1 = segment(luma, vm, mask1, mask2, 2 << g, lvl, segment + lstep, 4, lut);
            var s2 = segment(luma, vm, mask1, mask2, 4 << g, lvl, segment + 2 * lstep, 4, lut);
            var s3 = segment(luma, vm, mask1, mask2, 8 << g, lvl, segment + 3 * lstep, 4, lut);
            if ((s0 | s1 | s2 | s3) == 0) {
                continue;
            }
            var maxWd = Math.max(Math.max(s0 >>> 24, s1 >>> 24), Math.max(s2 >>> 24, s3 >>> 24));
            var before = maxWd == 16 ? 7 : maxWd == 8 ? 4 : maxWd == 6 ? 3 : 2;
            var after = maxWd == 16 ? 7 : maxWd == 8 ? 4 : maxWd == 6 ? 3 : 2;
            var off = dstOff + 4 * g * stride;
            if (off - before < 0 || off + 15 * stride + after > dst.length) {
                var selected = 15 << g;
                scalar.loopFilterSb(plane, DIR_COLUMN_EDGES, dst, dstOff, stride, mask0 & selected, mask1 & selected,
                        mask2 & selected, lvl, lvlOff, b4Stride, lut, length, scratch, bitdepthMax);
                continue;
            }
            transposeIn(dst, off, stride, scratch, s0, 0, before);
            transposeIn(dst, off, stride, scratch, s1, 4, before);
            transposeIn(dst, off, stride, scratch, s2, 8, before);
            transposeIn(dst, off, stride, scratch, s3, 12, before);
            filterGroup(scratch, TRANSPOSED + 7 * 16, 16, s0, s1, s2, s3, bitdepthMin8, bitdepthMax, scratch);
            var changed = maxWd == 16 ? 6 : maxWd == 8 ? 3 : 2;
            var end = maxWd == 16 ? 6 : maxWd == 8 ? 3 : 2;
            copyBack(dst, off, stride, scratch, s0, 0, changed, end);
            copyBack(dst, off, stride, scratch, s1, 4, changed, end);
            copyBack(dst, off, stride, scratch, s2, 8, changed, end);
            copyBack(dst, off, stride, scratch, s3, 12, changed, end);
        }
    }

    /**
     * Copies the samples across a column edge of the four lines of one segment into the transposed
     * scratch rows, one scratch row per distance from the edge; an unfiltered segment is skipped,
     * since the filters write its lanes back unchanged and {@link #copyBack} ignores them.
     *
     * @param dst     the plane
     * @param off     the index of the first sample right of the edge in the group's first line
     * @param stride  the plane's row stride
     * @param scratch the scratch buffer receiving the transposed samples
     * @param params  the packed parameters of the segment, 0 when it is not filtered
     * @param first   the first line of the segment in the group
     * @param half    the number of samples on each side of the edge the filters read: 2, 3, 4 or 7
     */
    private static void transposeIn(short[] dst, int off, int stride, short[] scratch, int params, int first,
                                    int half) {
        if (params == 0) {
            return;
        }
        var row = off + first * stride;
        var s = TRANSPOSED + 7 * 16 + first;
        switch (half) {
            case 2 -> {
                for (var line = 0; line < 4; line++, row += stride, s++) {
                    scratch[s - 32] = dst[row - 2];
                    scratch[s - 16] = dst[row - 1];
                    scratch[s] = dst[row];
                    scratch[s + 16] = dst[row + 1];
                }
            }
            case 3 -> {
                for (var line = 0; line < 4; line++, row += stride, s++) {
                    scratch[s - 48] = dst[row - 3];
                    scratch[s - 32] = dst[row - 2];
                    scratch[s - 16] = dst[row - 1];
                    scratch[s] = dst[row];
                    scratch[s + 16] = dst[row + 1];
                    scratch[s + 32] = dst[row + 2];
                }
            }
            case 4 -> {
                for (var line = 0; line < 4; line++, row += stride, s++) {
                    scratch[s - 64] = dst[row - 4];
                    scratch[s - 48] = dst[row - 3];
                    scratch[s - 32] = dst[row - 2];
                    scratch[s - 16] = dst[row - 1];
                    scratch[s] = dst[row];
                    scratch[s + 16] = dst[row + 1];
                    scratch[s + 32] = dst[row + 2];
                    scratch[s + 48] = dst[row + 3];
                }
            }
            default -> {
                for (var line = 0; line < 4; line++, row += stride, s++) {
                    for (var t = -half; t < half; t++) {
                        scratch[s + t * 16] = dst[row + t];
                    }
                }
            }
        }
    }

    /**
     * Copies the filtered columns of one segment of a transposed column edge back to the plane.
     *
     * @param dst     the plane
     * @param off     the index of the first sample right of the edge in the group's first line
     * @param stride  the plane's row stride
     * @param scratch the scratch buffer holding the transposed samples
     * @param params  the packed parameters of the segment, 0 when it is not filtered
     * @param first   the first line of the segment in the group
     * @param before  the number of samples left of the edge the filters may change
     * @param after   the number of samples right of the edge the filters may change
     */
    private static void copyBack(short[] dst, int off, int stride, short[] scratch, int params, int first, int before,
                                 int after) {
        if (params == 0) {
            return;
        }
        var row = off + first * stride;
        var s = TRANSPOSED + 7 * 16 + first;
        if (before == 2 && after == 2) {
            for (var line = 0; line < 4; line++, row += stride, s++) {
                dst[row - 2] = scratch[s - 32];
                dst[row - 1] = scratch[s - 16];
                dst[row] = scratch[s];
                dst[row + 1] = scratch[s + 16];
            }
        } else if (before == 3 && after == 3) {
            for (var line = 0; line < 4; line++, row += stride, s++) {
                dst[row - 3] = scratch[s - 48];
                dst[row - 2] = scratch[s - 32];
                dst[row - 1] = scratch[s - 16];
                dst[row] = scratch[s];
                dst[row + 1] = scratch[s + 16];
                dst[row + 2] = scratch[s + 32];
            }
        } else {
            for (var line = 0; line < 4; line++, row += stride, s++) {
                for (var t = -before; t < after; t++) {
                    dst[row + t] = scratch[s + t * 16];
                }
            }
        }
    }

    /**
     * Returns the parameters of one segment of an edge, packed: the edge limit in bits 0-7, the
     * interior limit in bits 8-15, the high edge variance threshold in bits 16-23 (each before
     * scaling to the bit depth) and the filter width in bits 24-31, or 0 when the segment is not
     * filtered.
     *
     * @param luma   whether the plane is luma
     * @param vm     the union of the masks
     * @param mask1  the blocks filtered at the middle width
     * @param mask2  the blocks filtered at the widest width
     * @param bit    the segment's mask bit
     * @param lvl    the level cache
     * @param lvlOff the index of the segment's level byte
     * @param above  the distance in the level cache to the block before the edge
     * @param lut    the filter limits of the frame
     * @return the packed parameters
     */
    private static int segment(boolean luma, int vm, int mask1, int mask2, int bit, byte[] lvl, int lvlOff, int above,
                               FilterLut lut) {
        if ((vm & bit) == 0) {
            return 0;
        }
        var level = lvl[lvlOff] & 0xFF;
        if (level == 0) {
            level = lvl[lvlOff - above] & 0xFF;
            if (level == 0) {
                return 0;
            }
        }
        var wd = luma ? (mask2 & bit) != 0 ? 16 : (mask1 & bit) != 0 ? 8 : 4 : (mask1 & bit) != 0 ? 6 : 4;
        return lut.e[level] | lut.i[level] << 8 | (level >> 4) << 16 | wd << 24;
    }

    /**
     * Decides and applies the narrow filter for four 4-sample segments of a row edge none of which is wider
     * than 4.
     *
     * @param dst the plane
     * @param off the index of the first sample below the edge in the first segment
     * @param stride the plane's row stride
     * @param e0 the edge limit of segment 0
     * @param e1 the edge limit of segment 1
     * @param e2 the edge limit of segment 2
     * @param e3 the edge limit of segment 3
     * @param i0 the interior limit of segment 0
     * @param i1 the interior limit of segment 1
     * @param i2 the interior limit of segment 2
     * @param i3 the interior limit of segment 3
     * @param h0 the high edge variance threshold of segment 0
     * @param h1 the high edge variance threshold of segment 1
     * @param h2 the high edge variance threshold of segment 2
     * @param h3 the high edge variance threshold of segment 3
     * @param w0 the filter width of segment 0, or 0 when it is not filtered
     * @param w1 the filter width of segment 1, or 0 when it is not filtered
     * @param w2 the filter width of segment 2, or 0 when it is not filtered
     * @param w3 the filter width of segment 3, or 0 when it is not filtered
     * @param diffMin the smallest filter difference
     * @param diffMax the largest filter difference
     * @param max the largest sample value
     */
    private static void narrow4(short[] dst, int off, int stride, int e0, int e1, int e2, int e3, int i0, int i1,
            int i2, int i3, int h0, int h1, int h2, int h3, int w0, int w1, int w2, int w3, int diffMin, int diffMax,
            int max) {
        var e = SHORT256.cast(ShortVector.broadcast(S256, (short) e0))
                .blend(ShortVector.broadcast(S256, (short) e1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) e2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) e3), FROM_12);
        var i = SHORT256.cast(ShortVector.broadcast(S256, (short) i0))
                .blend(ShortVector.broadcast(S256, (short) i1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) i2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) i3), FROM_12);
        var h = SHORT256.cast(ShortVector.broadcast(S256, (short) h0))
                .blend(ShortVector.broadcast(S256, (short) h1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) h2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) h3), FROM_12);
        var w = SHORT256.cast(ShortVector.broadcast(S256, (short) w0))
                .blend(ShortVector.broadcast(S256, (short) w1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) w2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) w3), FROM_12);
        var p1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 2 * stride));
        var p0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - stride));
        var q0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off));
        var q1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + stride));
        var fm = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .and(p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)
                        .lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.SUB, q0)
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, SHORT256
                                .cast(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS))
                                .lanewise(VectorOperators.ASHR, 1))
                        .compare(VectorOperators.LE, e))
                .and(w.compare(VectorOperators.GT, ZERO));
        var hev = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.GT, h)
                .or(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.GT, h));
        var low = ShortVector.broadcast(S256, (short) diffMin);
        var high = ShortVector.broadcast(S256, (short) diffMax);
        var top = ShortVector.broadcast(S256, (short) max);
        var lanes = fm;
        var strong = hev;
        var outer = ZERO.blend(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high), strong);
        var base = q0.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.MUL, THREE)
                .lanewise(VectorOperators.ADD, outer).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var f1 = SHORT256.cast(base.lanewise(VectorOperators.ADD, FOUR).lanewise(VectorOperators.MIN, high))
                .lanewise(VectorOperators.ASHR, 3);
        var f2 = SHORT256.cast(base.lanewise(VectorOperators.ADD, THREE).lanewise(VectorOperators.MIN, high))
                .lanewise(VectorOperators.ASHR, 3);
        var half = SHORT256.cast(f1.lanewise(VectorOperators.ADD, ONE)).lanewise(VectorOperators.ASHR, 1);
        var outerLanes = lanes.andNot(strong);
        p0.blend(p0.lanewise(VectorOperators.ADD, f2).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, top), lanes).intoArray(dst, off - stride);
        q0.blend(q0.lanewise(VectorOperators.SUB, f1).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, top), lanes).intoArray(dst, off);
        p1.blend(p1.lanewise(VectorOperators.ADD, half).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, top), outerLanes).intoArray(dst, off - 2 * stride);
        q1.blend(q1.lanewise(VectorOperators.SUB, half).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, top), outerLanes).intoArray(dst, off + stride);
    }

    /**
     * Takes the filter decisions of four 4-sample segments of a chroma row edge at most 6 wide from the
     * unfiltered samples and stores them in the scratch buffer.
     *
     * @param dst the plane
     * @param off the index of the first sample below the edge in the first segment
     * @param stride the plane's row stride
     * @param e0 the edge limit of segment 0
     * @param e1 the edge limit of segment 1
     * @param e2 the edge limit of segment 2
     * @param e3 the edge limit of segment 3
     * @param i0 the interior limit of segment 0
     * @param i1 the interior limit of segment 1
     * @param i2 the interior limit of segment 2
     * @param i3 the interior limit of segment 3
     * @param h0 the high edge variance threshold of segment 0
     * @param h1 the high edge variance threshold of segment 1
     * @param h2 the high edge variance threshold of segment 2
     * @param h3 the high edge variance threshold of segment 3
     * @param w0 the filter width of segment 0, or 0 when it is not filtered
     * @param w1 the filter width of segment 1, or 0 when it is not filtered
     * @param w2 the filter width of segment 2, or 0 when it is not filtered
     * @param w3 the filter width of segment 3, or 0 when it is not filtered
     * @param flat the flatness threshold, {@code 1 << (bitDepth - 8)}
     * @param scratch the decisions of the group, written by the decision kernel: the category of each lane
     *                (0 unfiltered, 1 narrow, 2 flat, 3 flat over 16 samples) at indices 0 to 15 and its
     *                high edge variance flag at indices 16 to 31
     * @return the categories present: bit 0 for narrow lanes, bit 1 for flat lanes, bit 2 for lanes flat
     *         over 16 samples
     */
    private static int decide6(short[] dst, int off, int stride, int e0, int e1, int e2, int e3, int i0, int i1, int i2,
            int i3, int h0, int h1, int h2, int h3, int w0, int w1, int w2, int w3, int flat, short[] scratch) {
        var e = SHORT256.cast(ShortVector.broadcast(S256, (short) e0))
                .blend(ShortVector.broadcast(S256, (short) e1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) e2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) e3), FROM_12);
        var i = SHORT256.cast(ShortVector.broadcast(S256, (short) i0))
                .blend(ShortVector.broadcast(S256, (short) i1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) i2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) i3), FROM_12);
        var h = SHORT256.cast(ShortVector.broadcast(S256, (short) h0))
                .blend(ShortVector.broadcast(S256, (short) h1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) h2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) h3), FROM_12);
        var w = SHORT256.cast(ShortVector.broadcast(S256, (short) w0))
                .blend(ShortVector.broadcast(S256, (short) w1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) w2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) w3), FROM_12);
        var f = ShortVector.broadcast(S256, (short) flat);
        var p2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 3 * stride));
        var p1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 2 * stride));
        var p0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - stride));
        var q0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off));
        var q1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + stride));
        var q2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 2 * stride));
        var fm = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .and(p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)
                        .lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.SUB, q0)
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, SHORT256
                                .cast(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS))
                                .lanewise(VectorOperators.ASHR, 1))
                        .compare(VectorOperators.LE, e))
                .and(w.compare(VectorOperators.GT, ZERO));
        fm = fm.and(p2.lanewise(VectorOperators.SUB, p1).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q2.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .or(w.compare(VectorOperators.LE, FOUR)));
        var hev = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.GT, h)
                .or(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.GT, h));
        var flatIn = p2.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f)
                .and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q2.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(w.compare(VectorOperators.GE, SIX)).and(fm);
        var narrow = fm.andNot(flatIn);
        ZERO.blend(ONE, narrow).blend(TWO, flatIn).intoArray(scratch, 0);
        ZERO.blend(ONE, hev).intoArray(scratch, 16);
        return (narrow.anyTrue() ? 1 : 0) | (flatIn.anyTrue() ? 2 : 0);
    }

    /**
     * Takes the filter decisions of four 4-sample segments of a luma row edge at most 8 wide from the
     * unfiltered samples and stores them in the scratch buffer.
     *
     * @param dst the plane
     * @param off the index of the first sample below the edge in the first segment
     * @param stride the plane's row stride
     * @param e0 the edge limit of segment 0
     * @param e1 the edge limit of segment 1
     * @param e2 the edge limit of segment 2
     * @param e3 the edge limit of segment 3
     * @param i0 the interior limit of segment 0
     * @param i1 the interior limit of segment 1
     * @param i2 the interior limit of segment 2
     * @param i3 the interior limit of segment 3
     * @param h0 the high edge variance threshold of segment 0
     * @param h1 the high edge variance threshold of segment 1
     * @param h2 the high edge variance threshold of segment 2
     * @param h3 the high edge variance threshold of segment 3
     * @param w0 the filter width of segment 0, or 0 when it is not filtered
     * @param w1 the filter width of segment 1, or 0 when it is not filtered
     * @param w2 the filter width of segment 2, or 0 when it is not filtered
     * @param w3 the filter width of segment 3, or 0 when it is not filtered
     * @param flat the flatness threshold, {@code 1 << (bitDepth - 8)}
     * @param scratch the decisions of the group, written by the decision kernel: the category of each lane
     *                (0 unfiltered, 1 narrow, 2 flat, 3 flat over 16 samples) at indices 0 to 15 and its
     *                high edge variance flag at indices 16 to 31
     * @return the categories present: bit 0 for narrow lanes, bit 1 for flat lanes, bit 2 for lanes flat
     *         over 16 samples
     */
    private static int decide8(short[] dst, int off, int stride, int e0, int e1, int e2, int e3, int i0, int i1, int i2,
            int i3, int h0, int h1, int h2, int h3, int w0, int w1, int w2, int w3, int flat, short[] scratch) {
        var e = SHORT256.cast(ShortVector.broadcast(S256, (short) e0))
                .blend(ShortVector.broadcast(S256, (short) e1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) e2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) e3), FROM_12);
        var i = SHORT256.cast(ShortVector.broadcast(S256, (short) i0))
                .blend(ShortVector.broadcast(S256, (short) i1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) i2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) i3), FROM_12);
        var h = SHORT256.cast(ShortVector.broadcast(S256, (short) h0))
                .blend(ShortVector.broadcast(S256, (short) h1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) h2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) h3), FROM_12);
        var w = SHORT256.cast(ShortVector.broadcast(S256, (short) w0))
                .blend(ShortVector.broadcast(S256, (short) w1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) w2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) w3), FROM_12);
        var f = ShortVector.broadcast(S256, (short) flat);
        var p3 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 4 * stride));
        var p2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 3 * stride));
        var p1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 2 * stride));
        var p0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - stride));
        var q0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off));
        var q1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + stride));
        var q2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 2 * stride));
        var q3 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 3 * stride));
        var fm = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .and(p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)
                        .lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.SUB, q0)
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, SHORT256
                                .cast(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS))
                                .lanewise(VectorOperators.ASHR, 1))
                        .compare(VectorOperators.LE, e))
                .and(w.compare(VectorOperators.GT, ZERO));
        fm = fm.and(p2.lanewise(VectorOperators.SUB, p1).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q2.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .or(w.compare(VectorOperators.LE, FOUR)));
        fm = fm.and(p3.lanewise(VectorOperators.SUB, p2).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q3.lanewise(VectorOperators.SUB, q2).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .or(w.compare(VectorOperators.LE, SIX)));
        var hev = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.GT, h)
                .or(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.GT, h));
        var flatIn = p2.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f)
                .and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q2.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(w.compare(VectorOperators.GE, SIX)).and(fm);
        flatIn = flatIn.and(p3.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)
                .compare(VectorOperators.LE, f)
                .and(q3.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .or(w.compare(VectorOperators.LE, SIX)));
        var narrow = fm.andNot(flatIn);
        ZERO.blend(ONE, narrow).blend(TWO, flatIn).intoArray(scratch, 0);
        ZERO.blend(ONE, hev).intoArray(scratch, 16);
        return (narrow.anyTrue() ? 1 : 0) | (flatIn.anyTrue() ? 2 : 0);
    }

    /**
     * Takes the filter decisions of four 4-sample segments of a luma row edge with a 16-wide segment from
     * the unfiltered samples and stores them in the scratch buffer.
     *
     * @param dst the plane
     * @param off the index of the first sample below the edge in the first segment
     * @param stride the plane's row stride
     * @param e0 the edge limit of segment 0
     * @param e1 the edge limit of segment 1
     * @param e2 the edge limit of segment 2
     * @param e3 the edge limit of segment 3
     * @param i0 the interior limit of segment 0
     * @param i1 the interior limit of segment 1
     * @param i2 the interior limit of segment 2
     * @param i3 the interior limit of segment 3
     * @param h0 the high edge variance threshold of segment 0
     * @param h1 the high edge variance threshold of segment 1
     * @param h2 the high edge variance threshold of segment 2
     * @param h3 the high edge variance threshold of segment 3
     * @param w0 the filter width of segment 0, or 0 when it is not filtered
     * @param w1 the filter width of segment 1, or 0 when it is not filtered
     * @param w2 the filter width of segment 2, or 0 when it is not filtered
     * @param w3 the filter width of segment 3, or 0 when it is not filtered
     * @param flat the flatness threshold, {@code 1 << (bitDepth - 8)}
     * @param scratch the decisions of the group, written by the decision kernel: the category of each lane
     *                (0 unfiltered, 1 narrow, 2 flat, 3 flat over 16 samples) at indices 0 to 15 and its
     *                high edge variance flag at indices 16 to 31
     * @return the categories present: bit 0 for narrow lanes, bit 1 for flat lanes, bit 2 for lanes flat
     *         over 16 samples
     */
    private static int decide16(short[] dst, int off, int stride, int e0, int e1, int e2, int e3, int i0, int i1,
            int i2, int i3, int h0, int h1, int h2, int h3, int w0, int w1, int w2, int w3, int flat, short[] scratch) {
        var e = SHORT256.cast(ShortVector.broadcast(S256, (short) e0))
                .blend(ShortVector.broadcast(S256, (short) e1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) e2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) e3), FROM_12);
        var i = SHORT256.cast(ShortVector.broadcast(S256, (short) i0))
                .blend(ShortVector.broadcast(S256, (short) i1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) i2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) i3), FROM_12);
        var h = SHORT256.cast(ShortVector.broadcast(S256, (short) h0))
                .blend(ShortVector.broadcast(S256, (short) h1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) h2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) h3), FROM_12);
        var w = SHORT256.cast(ShortVector.broadcast(S256, (short) w0))
                .blend(ShortVector.broadcast(S256, (short) w1), FROM_4)
                .blend(ShortVector.broadcast(S256, (short) w2), FROM_8)
                        .blend(ShortVector.broadcast(S256, (short) w3), FROM_12);
        var f = ShortVector.broadcast(S256, (short) flat);
        var p6 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 7 * stride));
        var p5 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 6 * stride));
        var p4 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 5 * stride));
        var p3 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 4 * stride));
        var p2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 3 * stride));
        var p1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 2 * stride));
        var p0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - stride));
        var q0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off));
        var q1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + stride));
        var q2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 2 * stride));
        var q3 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 3 * stride));
        var q4 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 4 * stride));
        var q5 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 5 * stride));
        var q6 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 6 * stride));
        var fm = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .and(p0.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS)
                        .lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.SUB, q0)
                        .lanewise(VectorOperators.ABS))
                        .lanewise(VectorOperators.ADD, SHORT256
                                .cast(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS))
                                .lanewise(VectorOperators.ASHR, 1))
                        .compare(VectorOperators.LE, e))
                .and(w.compare(VectorOperators.GT, ZERO));
        fm = fm.and(p2.lanewise(VectorOperators.SUB, p1).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q2.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .or(w.compare(VectorOperators.LE, FOUR)));
        fm = fm.and(p3.lanewise(VectorOperators.SUB, p2).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i)
                .and(q3.lanewise(VectorOperators.SUB, q2).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, i))
                .or(w.compare(VectorOperators.LE, SIX)));
        var hev = p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.GT, h)
                .or(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.GT, h));
        var flatIn = p2.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f)
                .and(p1.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q1.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q2.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(w.compare(VectorOperators.GE, SIX)).and(fm);
        flatIn = flatIn.and(p3.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS)
                .compare(VectorOperators.LE, f)
                .and(q3.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .or(w.compare(VectorOperators.LE, SIX)));
        var flat16 = p6.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f)
                .and(p5.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(p4.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q4.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q5.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(q6.lanewise(VectorOperators.SUB, q0).lanewise(VectorOperators.ABS).compare(VectorOperators.LE, f))
                .and(w.compare(VectorOperators.EQ, SIXTEEN)).and(flatIn);
        var narrow = fm.andNot(flatIn);
        ZERO.blend(ONE, narrow).blend(TWO, flatIn).blend(THREE, flat16).intoArray(scratch, 0);
        ZERO.blend(ONE, hev).intoArray(scratch, 16);
        return (narrow.anyTrue() ? 1 : 0) | (flatIn.andNot(flat16).anyTrue() ? 2 : 0) | (flat16.anyTrue() ? 4 : 0);
    }

    /**
     * Applies the narrow filter to the lanes the decision kernel classified as narrow.
     *
     * @param dst the plane
     * @param off the index of the first sample below the edge in the first segment
     * @param stride the plane's row stride
     * @param diffMin the smallest filter difference
     * @param diffMax the largest filter difference
     * @param max the largest sample value
     * @param scratch the decisions of the group, written by the decision kernel: the category of each lane
     *                (0 unfiltered, 1 narrow, 2 flat, 3 flat over 16 samples) at indices 0 to 15 and its
     *                high edge variance flag at indices 16 to 31
     */
    private static void narrowApply(short[] dst, int off, int stride, int diffMin, int diffMax, int max,
            short[] scratch) {
        var p1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 2 * stride));
        var p0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - stride));
        var q0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off));
        var q1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + stride));
        var category = SHORT256.cast(ShortVector.fromArray(S256, scratch, 0));
        var variance = SHORT256.cast(ShortVector.fromArray(S256, scratch, 16));
        var low = ShortVector.broadcast(S256, (short) diffMin);
        var high = ShortVector.broadcast(S256, (short) diffMax);
        var top = ShortVector.broadcast(S256, (short) max);
        var lanes = category.compare(VectorOperators.EQ, ONE);
        var strong = variance.compare(VectorOperators.EQ, ONE);
        var outer = ZERO.blend(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high), strong);
        var base = q0.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.MUL, THREE)
                .lanewise(VectorOperators.ADD, outer).lanewise(VectorOperators.MAX, low)
                .lanewise(VectorOperators.MIN, high);
        var f1 = SHORT256.cast(base.lanewise(VectorOperators.ADD, FOUR).lanewise(VectorOperators.MIN, high))
                .lanewise(VectorOperators.ASHR, 3);
        var f2 = SHORT256.cast(base.lanewise(VectorOperators.ADD, THREE).lanewise(VectorOperators.MIN, high))
                .lanewise(VectorOperators.ASHR, 3);
        var half = SHORT256.cast(f1.lanewise(VectorOperators.ADD, ONE)).lanewise(VectorOperators.ASHR, 1);
        var outerLanes = lanes.andNot(strong);
        p0.blend(p0.lanewise(VectorOperators.ADD, f2).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, top), lanes).intoArray(dst, off - stride);
        q0.blend(q0.lanewise(VectorOperators.SUB, f1).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, top), lanes).intoArray(dst, off);
        p1.blend(p1.lanewise(VectorOperators.ADD, half).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, top), outerLanes).intoArray(dst, off - 2 * stride);
        q1.blend(q1.lanewise(VectorOperators.SUB, half).lanewise(VectorOperators.MAX, ZERO)
                .lanewise(VectorOperators.MIN, top), outerLanes).intoArray(dst, off + stride);
    }

    /**
     * Applies the 6-wide filter to the lanes the decision kernel classified as flat.
     *
     * @param dst the plane
     * @param off the index of the first sample below the edge in the first segment
     * @param stride the plane's row stride
     * @param scratch the decisions of the group, written by the decision kernel: the category of each lane
     *                (0 unfiltered, 1 narrow, 2 flat, 3 flat over 16 samples) at indices 0 to 15 and its
     *                high edge variance flag at indices 16 to 31
     */
    private static void flat6Apply(short[] dst, int off, int stride, short[] scratch) {
        var p2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 3 * stride));
        var p1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 2 * stride));
        var p0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - stride));
        var q0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off));
        var q1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + stride));
        var q2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 2 * stride));
        var lanes = SHORT256.cast(ShortVector.fromArray(S256, scratch, 0)).compare(VectorOperators.EQ, TWO);
        var np1 = SHORT256.cast(p2.lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q0)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        var np0 = SHORT256.cast(p2.lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q1)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        var nq0 = SHORT256.cast(p1.lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q2)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        var nq1 = SHORT256.cast(p0.lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, q2.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q2)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        p1.blend(np1, lanes).intoArray(dst, off - 2 * stride);
        p0.blend(np0, lanes).intoArray(dst, off - stride);
        q0.blend(nq0, lanes).intoArray(dst, off);
        q1.blend(nq1, lanes).intoArray(dst, off + stride);
    }

    /**
     * Applies the 8-wide filter to the lanes the decision kernel classified as flat.
     *
     * @param dst the plane
     * @param off the index of the first sample below the edge in the first segment
     * @param stride the plane's row stride
     * @param scratch the decisions of the group, written by the decision kernel: the category of each lane
     *                (0 unfiltered, 1 narrow, 2 flat, 3 flat over 16 samples) at indices 0 to 15 and its
     *                high edge variance flag at indices 16 to 31
     */
    private static void flat8Apply(short[] dst, int off, int stride, short[] scratch) {
        var p3 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 4 * stride));
        var p2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 3 * stride));
        var p1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 2 * stride));
        var p0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - stride));
        var q0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off));
        var q1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + stride));
        var q2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 2 * stride));
        var q3 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 3 * stride));
        var lanes = SHORT256.cast(ShortVector.fromArray(S256, scratch, 0)).compare(VectorOperators.EQ, TWO);
        var np2 = SHORT256.cast(p3.lanewise(VectorOperators.ADD, p3).lanewise(VectorOperators.ADD, p3)
                .lanewise(VectorOperators.ADD, p2.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, p1)
                .lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q0)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        var np1 = SHORT256.cast(p3.lanewise(VectorOperators.ADD, p3).lanewise(VectorOperators.ADD, p2)
                .lanewise(VectorOperators.ADD, p1.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, p0)
                .lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, q1)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        var np0 = SHORT256.cast(p3.lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, p1)
                .lanewise(VectorOperators.ADD, p0.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q0)
                .lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, q2)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        var nq0 = SHORT256.cast(p2.lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, p0)
                .lanewise(VectorOperators.ADD, q0.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q1)
                .lanewise(VectorOperators.ADD, q2).lanewise(VectorOperators.ADD, q3)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        var nq1 = SHORT256.cast(p1.lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q0)
                .lanewise(VectorOperators.ADD, q1.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q2)
                .lanewise(VectorOperators.ADD, q3).lanewise(VectorOperators.ADD, q3)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        var nq2 = SHORT256.cast(p0.lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, q1)
                .lanewise(VectorOperators.ADD, q2.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, q3)
                .lanewise(VectorOperators.ADD, q3).lanewise(VectorOperators.ADD, q3)
                .lanewise(VectorOperators.ADD, FOUR)).lanewise(VectorOperators.ASHR, 3);
        p2.blend(np2, lanes).intoArray(dst, off - 3 * stride);
        p1.blend(np1, lanes).intoArray(dst, off - 2 * stride);
        p0.blend(np0, lanes).intoArray(dst, off - stride);
        q0.blend(nq0, lanes).intoArray(dst, off);
        q1.blend(nq1, lanes).intoArray(dst, off + stride);
        q2.blend(nq2, lanes).intoArray(dst, off + 2 * stride);
    }

    /**
     * Applies the 16-wide filter to the lanes the decision kernel classified as flat over 16 samples,
     * updating a running sum by two taps in and two out per output.
     *
     * @param dst the plane
     * @param off the index of the first sample below the edge in the first segment
     * @param stride the plane's row stride
     * @param scratch the decisions of the group, written by the decision kernel: the category of each lane
     *                (0 unfiltered, 1 narrow, 2 flat, 3 flat over 16 samples) at indices 0 to 15 and its
     *                high edge variance flag at indices 16 to 31
     */
    private static void flat16Apply(short[] dst, int off, int stride, short[] scratch) {
        var p6 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 7 * stride));
        var p5 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 6 * stride));
        var p4 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 5 * stride));
        var p3 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 4 * stride));
        var p2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 3 * stride));
        var p1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - 2 * stride));
        var p0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off - stride));
        var q0 = SHORT256.cast(ShortVector.fromArray(S256, dst, off));
        var q1 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + stride));
        var q2 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 2 * stride));
        var q3 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 3 * stride));
        var q4 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 4 * stride));
        var q5 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 5 * stride));
        var q6 = SHORT256.cast(ShortVector.fromArray(S256, dst, off + 6 * stride));
        var lanes = SHORT256.cast(ShortVector.fromArray(S256, scratch, 0)).compare(VectorOperators.EQ, THREE);
        var s0 = p6.lanewise(VectorOperators.MUL, SEVEN)
                .lanewise(VectorOperators.ADD, p5.lanewise(VectorOperators.LSHL, 1))
                .lanewise(VectorOperators.ADD, p4.lanewise(VectorOperators.LSHL, 1)).lanewise(VectorOperators.ADD, p3)
                .lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, p1)
                .lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q0)
                .lanewise(VectorOperators.ADD, EIGHT);
        p5.blend(SHORT256.cast(s0).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off - 6 * stride);
        var s1 = s0.lanewise(VectorOperators.SUB, p6).lanewise(VectorOperators.SUB, p6)
                .lanewise(VectorOperators.ADD, p3).lanewise(VectorOperators.ADD, q1);
        p4.blend(SHORT256.cast(s1).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off - 5 * stride);
        var s2 = s1.lanewise(VectorOperators.SUB, p6).lanewise(VectorOperators.SUB, p5)
                .lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, q2);
        p3.blend(SHORT256.cast(s2).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off - 4 * stride);
        var s3 = s2.lanewise(VectorOperators.SUB, p6).lanewise(VectorOperators.SUB, p4)
                .lanewise(VectorOperators.ADD, p1).lanewise(VectorOperators.ADD, q3);
        p2.blend(SHORT256.cast(s3).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off - 3 * stride);
        var s4 = s3.lanewise(VectorOperators.SUB, p6).lanewise(VectorOperators.SUB, p3)
                .lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q4);
        p1.blend(SHORT256.cast(s4).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off - 2 * stride);
        var s5 = s4.lanewise(VectorOperators.SUB, p6).lanewise(VectorOperators.SUB, p2)
                .lanewise(VectorOperators.ADD, q0).lanewise(VectorOperators.ADD, q5);
        p0.blend(SHORT256.cast(s5).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off - stride);
        var s6 = s5.lanewise(VectorOperators.SUB, p6).lanewise(VectorOperators.SUB, p1)
                .lanewise(VectorOperators.ADD, q1).lanewise(VectorOperators.ADD, q6);
        q0.blend(SHORT256.cast(s6).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off);
        var s7 = s6.lanewise(VectorOperators.SUB, p5).lanewise(VectorOperators.SUB, p0)
                .lanewise(VectorOperators.ADD, q2).lanewise(VectorOperators.ADD, q6);
        q1.blend(SHORT256.cast(s7).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off + stride);
        var s8 = s7.lanewise(VectorOperators.SUB, p4).lanewise(VectorOperators.SUB, q0)
                .lanewise(VectorOperators.ADD, q3).lanewise(VectorOperators.ADD, q6);
        q2.blend(SHORT256.cast(s8).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off + 2 * stride);
        var s9 = s8.lanewise(VectorOperators.SUB, p3).lanewise(VectorOperators.SUB, q1)
                .lanewise(VectorOperators.ADD, q4).lanewise(VectorOperators.ADD, q6);
        q3.blend(SHORT256.cast(s9).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off + 3 * stride);
        var s10 = s9.lanewise(VectorOperators.SUB, p2).lanewise(VectorOperators.SUB, q2)
                .lanewise(VectorOperators.ADD, q5).lanewise(VectorOperators.ADD, q6);
        q4.blend(SHORT256.cast(s10).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off + 4 * stride);
        var s11 = s10.lanewise(VectorOperators.SUB, p1).lanewise(VectorOperators.SUB, q3)
                .lanewise(VectorOperators.ADD, q6).lanewise(VectorOperators.ADD, q6);
        q5.blend(SHORT256.cast(s11).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, off + 5 * stride);
    }
}
