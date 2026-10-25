package com.github.auties00.smalt.internal.field;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Computes the field operations of video processing on {@link Plane}s: rebuilding a frame from one
 * of its fields, weaving two fields, blending two frames, and the measures that field matching and
 * decimation compare.
 *
 * <p>The top field of a plane holds its rows 0, 2, 4 and so on, the bottom field its rows 1, 3, 5
 * and so on. Planes passed together have the same size, sample width and step. The results do not
 * depend on the thread count. Arguments are not checked; values outside the documented ranges give
 * unspecified results. This class is thread-safe.
 */
public final class FieldOps {
    /**
     * Rebuilds the missing rows by repeating the kept row above them (the row below for the first row).
     */
    public static final int BOB = 0;

    /**
     * Rebuilds the missing rows as the rounded mean of the kept rows above and below.
     */
    public static final int LINEAR = 1;

    /**
     * Rebuilds each missing sample as the rounded mean of the pair of kept samples, along the
     * vertical or one of the two diagonals through it, that differ least (edge-based line average).
     */
    public static final int ELA = 2;

    /**
     * Prevents instantiation.
     */
    private FieldOps() {
        throw new AssertionError();
    }

    /**
     * Rebuilds a frame from one of its fields: the rows of the kept field are copied and the others
     * interpolated from them with a method.
     *
     * <p>A missing row with no kept row above it uses the row below, and one with none below the row
     * above. In {@link #ELA}, a diagonal is considered only where both of its samples lie in the row,
     * and a tie keeps the vertical, then the diagonal that falls to the right.
     *
     * @param source the woven frame
     * @param target the plane written; may not be {@code source}
     * @param bottom whether the bottom field is kept rather than the top one
     * @param method {@link #BOB}, {@link #LINEAR} or {@link #ELA}
     */
    public static void deinterlace(Plane source, Plane target, boolean bottom, int method) {
        var height = source.height();
        var width = source.width();
        var bands = ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            for (var y = first; y < last; y++) {
                if (((y & 1) == 1) == bottom) {
                    copyRow(source, y, target, y);
                    continue;
                }
                var above = y - 1;
                var below = y + 1 < height ? y + 1 : -1;
                if (above < 0 && below < 0) {
                    copyRow(source, y, target, y);
                } else if (above < 0) {
                    copyRow(source, below, target, y);
                } else if (below < 0 || method == BOB) {
                    copyRow(source, above, target, y);
                } else if (method == LINEAR) {
                    averageRows(source, above, below, target, y);
                } else {
                    edgeAverage(source, above, below, target, y);
                }
            }
        });
    }

    /**
     * Weaves two fields into a frame: the even rows come from one plane, the odd rows from the other.
     *
     * @param top the plane whose even rows are taken
     * @param bottom the plane whose odd rows are taken
     * @param target the plane written; may be {@code top} or {@code bottom}
     */
    public static void weave(Plane top, Plane bottom, Plane target) {
        var height = target.height();
        var bands = ParallelBands.bands(height, (long) target.width() * height);
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                var from = (y & 1) == 0 ? top : bottom;
                if (from != target) {
                    copyRow(from, y, target, y);
                }
            }
        });
    }

    /**
     * Blends two frames: each sample becomes {@code (a * (256 - weight) + b * weight + 128) >> 8}.
     *
     * @param a the first frame
     * @param b the second frame
     * @param weight the weight of {@code b}, from 0 to 256
     * @param target the plane written; may be {@code a} or {@code b}
     */
    public static void blend(Plane a, Plane b, int weight, Plane target) {
        var height = target.height();
        var width = target.width();
        var bands = ParallelBands.bands(height, (long) width * height);
        var keep = 256 - weight;
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, height);
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                var ia = y * a.stride();
                var ib = y * b.stride();
                var it = y * target.stride();
                if (target.wide()) {
                    var sa = a.shorts();
                    var sb = b.shorts();
                    var st = target.shorts();
                    for (var x = 0; x < width; x++) {
                        st[it + x] = (short) (((sa[ia + x] & 0xFFFF) * keep + (sb[ib + x] & 0xFFFF) * weight + 128) >>> 8);
                    }
                } else {
                    var sa = a.bytes();
                    var sb = b.bytes();
                    var st = target.bytes();
                    for (var x = 0; x < width; x++) {
                        st[it + x] = (byte) (((sa[ia + x] & 0xFF) * keep + (sb[ib + x] & 0xFF) * weight + 128) >>> 8);
                    }
                }
            }
        });
    }

    /**
     * Measures how combed a frame woven from two fields is: the sum, over the rows that have a row
     * above and below, of {@code |2 * sample - above - below|}.
     *
     * <p>The rows of one field are compared with the rows of the other, so the measure is low for a
     * frame whose fields show the same instant and high where they show moving content apart.
     *
     * @param top the plane whose even rows form the frame
     * @param bottom the plane whose odd rows form the frame
     * @return the measure, zero or more
     */
    public static long combing(Plane top, Plane bottom) {
        var height = top.height();
        var width = top.width();
        if (height < 3) {
            return 0;
        }
        var rows = height - 2;
        var bands = ParallelBands.bands(rows, (long) width * rows);
        var sums = new long[bands];
        ParallelBands.run(bands, band -> {
            var sum = 0L;
            var last = 1 + ParallelBands.start(band + 1, bands, rows);
            for (var y = 1 + ParallelBands.start(band, bands, rows); y < last; y++) {
                var middle = (y & 1) == 0 ? top : bottom;
                var outer = (y & 1) == 0 ? bottom : top;
                var im = y * middle.stride();
                var iu = (y - 1) * outer.stride();
                var id = (y + 1) * outer.stride();
                if (middle.wide()) {
                    var sm = middle.shorts();
                    var so = outer.shorts();
                    for (var x = 0; x < width; x++) {
                        sum += Math.abs(2 * (sm[im + x] & 0xFFFF) - (so[iu + x] & 0xFFFF) - (so[id + x] & 0xFFFF));
                    }
                } else {
                    var sm = middle.bytes();
                    var so = outer.bytes();
                    for (var x = 0; x < width; x++) {
                        sum += Math.abs(2 * (sm[im + x] & 0xFF) - (so[iu + x] & 0xFF) - (so[id + x] & 0xFF));
                    }
                }
            }
            sums[band] = sum;
        });
        var total = 0L;
        for (var sum : sums) {
            total += sum;
        }
        return total;
    }

    /**
     * Counts the combed samples of a frame woven from two fields: those whose row lies between a row
     * above and below that both differ from it by more than a threshold in the same direction.
     *
     * @param top the plane whose even rows form the frame
     * @param bottom the plane whose odd rows form the frame
     * @param threshold the smallest difference that counts, zero or more
     * @return the count
     */
    public static long combedSamples(Plane top, Plane bottom, int threshold) {
        var height = top.height();
        var width = top.width();
        if (height < 3) {
            return 0;
        }
        var rows = height - 2;
        var bands = ParallelBands.bands(rows, (long) width * rows);
        var counts = new long[bands];
        ParallelBands.run(bands, band -> {
            var count = 0L;
            var last = 1 + ParallelBands.start(band + 1, bands, rows);
            for (var y = 1 + ParallelBands.start(band, bands, rows); y < last; y++) {
                var middle = (y & 1) == 0 ? top : bottom;
                var outer = (y & 1) == 0 ? bottom : top;
                var im = y * middle.stride();
                var iu = (y - 1) * outer.stride();
                var id = (y + 1) * outer.stride();
                for (var x = 0; x < width; x++) {
                    int m;
                    int u;
                    int d;
                    if (middle.wide()) {
                        m = middle.shorts()[im + x] & 0xFFFF;
                        u = outer.shorts()[iu + x] & 0xFFFF;
                        d = outer.shorts()[id + x] & 0xFFFF;
                    } else {
                        m = middle.bytes()[im + x] & 0xFF;
                        u = outer.bytes()[iu + x] & 0xFF;
                        d = outer.bytes()[id + x] & 0xFF;
                    }
                    var up = m - u;
                    var down = m - d;
                    if ((up > threshold && down > threshold) || (up < -threshold && down < -threshold)) {
                        count++;
                    }
                }
            }
            counts[band] = count;
        });
        var total = 0L;
        for (var count : counts) {
            total += count;
        }
        return total;
    }

    /**
     * Counts the samples of a frame that show combing rather than detail: those that differ by more
     * than a threshold, in the same direction, from the rows above and below (the other field), and by
     * at most the threshold from one of the rows two above and two below (their own field).
     *
     * <p>A thin horizontal line of a progressive frame differs from its own field's rows too, so it does
     * not count; the fields of moving content woven together do.
     *
     * @param plane the woven frame
     * @param threshold the smallest difference that counts, zero or more
     * @return the count
     */
    public static long combedFrameSamples(Plane plane, int threshold) {
        var height = plane.height();
        var width = plane.width();
        if (height < 5) {
            return 0;
        }
        var rows = height - 4;
        var bands = ParallelBands.bands(rows, (long) width * rows);
        var counts = new long[bands];
        ParallelBands.run(bands, band -> {
            var count = 0L;
            var last = 2 + ParallelBands.start(band + 1, bands, rows);
            for (var y = 2 + ParallelBands.start(band, bands, rows); y < last; y++) {
                var stride = plane.stride();
                for (var x = 0; x < width; x++) {
                    var index = y * stride + x;
                    int m;
                    int u;
                    int d;
                    int uu;
                    int dd;
                    if (plane.wide()) {
                        var s = plane.shorts();
                        m = s[index] & 0xFFFF;
                        u = s[index - stride] & 0xFFFF;
                        d = s[index + stride] & 0xFFFF;
                        uu = s[index - 2 * stride] & 0xFFFF;
                        dd = s[index + 2 * stride] & 0xFFFF;
                    } else {
                        var s = plane.bytes();
                        m = s[index] & 0xFF;
                        u = s[index - stride] & 0xFF;
                        d = s[index + stride] & 0xFF;
                        uu = s[index - 2 * stride] & 0xFF;
                        dd = s[index + 2 * stride] & 0xFF;
                    }
                    var up = m - u;
                    var down = m - d;
                    var across = (up > threshold && down > threshold) || (up < -threshold && down < -threshold);
                    if (across && (Math.abs(m - uu) <= threshold || Math.abs(m - dd) <= threshold)) {
                        count++;
                    }
                }
            }
            counts[band] = count;
        });
        var total = 0L;
        for (var count : counts) {
            total += count;
        }
        return total;
    }

    /**
     * Returns the most combed samples ({@link #combedFrameSamples}) found in one 16 by 16 window of a
     * frame, the windows placed every 8 samples in both directions.
     *
     * <p>Motion combs whole areas, while detail that passes for combing is scattered, so the densest
     * window tells a combed frame from a detailed progressive one.
     *
     * @param plane the woven frame
     * @param threshold the smallest difference that counts, zero or more
     * @param cells scratch of at least {@link #combingCells(int, int)} entries for the plane's size,
     * overwritten
     * @return the count, from 0 to 256
     */
    public static int densestCombing(Plane plane, int threshold, int[] cells) {
        var height = plane.height();
        var width = plane.width();
        var cellsX = (width + 7) >> 3;
        var cellsY = (height + 7) >> 3;
        var stride = plane.stride();
        var bytes = plane.bytes();
        var shorts = plane.shorts();
        var wide = plane.wide();
        var bands = ParallelBands.bands(cellsY, (long) width * height);
        ParallelBands.run(bands, band -> {
            var firstCell = ParallelBands.start(band, bands, cellsY);
            var lastCell = ParallelBands.start(band + 1, bands, cellsY);
            Arrays.fill(cells, firstCell * cellsX, lastCell * cellsX, 0);
            var last = Math.min(height - 2, lastCell << 3);
            for (var y = Math.max(2, firstCell << 3); y < last; y++) {
                var row = (y >> 3) * cellsX;
                for (var x = 0; x < width; x++) {
                    var index = y * stride + x;
                    var m = sample(bytes, shorts, wide, index);
                    var up = m - sample(bytes, shorts, wide, index - stride);
                    var down = m - sample(bytes, shorts, wide, index + stride);
                    if (!(up > threshold && down > threshold || up < -threshold && down < -threshold)) {
                        continue;
                    }
                    var uu = sample(bytes, shorts, wide, index - 2 * stride);
                    var dd = sample(bytes, shorts, wide, index + 2 * stride);
                    if (Math.abs(m - uu) <= threshold || Math.abs(m - dd) <= threshold) {
                        cells[row + (x >> 3)]++;
                    }
                }
            }
        });
        var densest = 0;
        for (var cy = 0; cy < cellsY; cy++) {
            for (var cx = 0; cx < cellsX; cx++) {
                var sum = cells[cy * cellsX + cx];
                if (cx + 1 < cellsX) {
                    sum += cells[cy * cellsX + cx + 1];
                }
                if (cy + 1 < cellsY) {
                    sum += cells[(cy + 1) * cellsX + cx];
                    if (cx + 1 < cellsX) {
                        sum += cells[(cy + 1) * cellsX + cx + 1];
                    }
                }
                densest = Math.max(densest, sum);
            }
        }
        return densest;
    }

    /**
     * {@return the scratch entries {@link #densestCombing} needs for a plane of a size}
     *
     * @param width the samples of a row
     * @param height the rows
     */
    public static int combingCells(int width, int height) {
        return ((width + 7) >> 3) * ((height + 7) >> 3);
    }

    /**
     * Returns the sum of the absolute differences between two planes.
     *
     * @param a the first plane
     * @param b the second plane
     * @return the sum
     */
    public static long difference(Plane a, Plane b) {
        var height = a.height();
        var width = a.width();
        var bands = ParallelBands.bands(height, (long) width * height);
        var sums = new long[bands];
        ParallelBands.run(bands, band -> {
            var sum = 0L;
            var last = ParallelBands.start(band + 1, bands, height);
            for (var y = ParallelBands.start(band, bands, height); y < last; y++) {
                var ia = y * a.stride();
                var ib = y * b.stride();
                if (a.wide()) {
                    var sa = a.shorts();
                    var sb = b.shorts();
                    for (var x = 0; x < width; x++) {
                        sum += Math.abs((sa[ia + x] & 0xFFFF) - (sb[ib + x] & 0xFFFF));
                    }
                } else {
                    var sa = a.bytes();
                    var sb = b.bytes();
                    for (var x = 0; x < width; x++) {
                        sum += Math.abs((sa[ia + x] & 0xFF) - (sb[ib + x] & 0xFF));
                    }
                }
            }
            sums[band] = sum;
        });
        var total = 0L;
        for (var sum : sums) {
            total += sum;
        }
        return total;
    }

    /**
     * Copies one row.
     *
     * @param source the plane read
     * @param sourceRow the row read
     * @param target the plane written
     * @param targetRow the row written
     */
    private static void copyRow(Plane source, int sourceRow, Plane target, int targetRow) {
        if (source.wide()) {
            System.arraycopy(source.shorts(), sourceRow * source.stride(), target.shorts(), targetRow * target.stride(),
                    source.width());
        } else {
            System.arraycopy(source.bytes(), sourceRow * source.stride(), target.bytes(), targetRow * target.stride(),
                    source.width());
        }
    }

    /**
     * Writes the rounded mean of two rows.
     *
     * @param source the plane read
     * @param above the first row read
     * @param below the second row read
     * @param target the plane written
     * @param row the row written
     */
    private static void averageRows(Plane source, int above, int below, Plane target, int row) {
        var width = source.width();
        var ia = above * source.stride();
        var ib = below * source.stride();
        var it = row * target.stride();
        if (source.wide()) {
            var s = source.shorts();
            var t = target.shorts();
            for (var x = 0; x < width; x++) {
                t[it + x] = (short) (((s[ia + x] & 0xFFFF) + (s[ib + x] & 0xFFFF) + 1) >>> 1);
            }
        } else {
            var s = source.bytes();
            var t = target.bytes();
            for (var x = 0; x < width; x++) {
                t[it + x] = (byte) (((s[ia + x] & 0xFF) + (s[ib + x] & 0xFF) + 1) >>> 1);
            }
        }
    }

    /**
     * Writes the edge-based line average of two rows.
     *
     * @param source the plane read
     * @param above the row above
     * @param below the row below
     * @param target the plane written
     * @param row the row written
     */
    private static void edgeAverage(Plane source, int above, int below, Plane target, int row) {
        var width = source.width();
        var step = source.step();
        var ia = above * source.stride();
        var ib = below * source.stride();
        var it = row * target.stride();
        var wide = source.wide();
        var bytes = source.bytes();
        var shorts = source.shorts();
        for (var x = 0; x < width; x++) {
            var a0 = sample(bytes, shorts, wide, ia + x);
            var b0 = sample(bytes, shorts, wide, ib + x);
            var best = Math.abs(a0 - b0);
            var value = a0 + b0;
            if (x >= step && x + step < width) {
                // the diagonal falling to the right joins the sample up-left with the one down-right
                var aLeft = sample(bytes, shorts, wide, ia + x - step);
                var bRight = sample(bytes, shorts, wide, ib + x + step);
                var aRight = sample(bytes, shorts, wide, ia + x + step);
                var bLeft = sample(bytes, shorts, wide, ib + x - step);
                var falling = Math.abs(aLeft - bRight);
                var rising = Math.abs(aRight - bLeft);
                if (falling < best) {
                    best = falling;
                    value = aLeft + bRight;
                }
                if (rising < best) {
                    value = aRight + bLeft;
                }
            }
            if (wide) {
                target.shorts()[it + x] = (short) ((value + 1) >>> 1);
            } else {
                target.bytes()[it + x] = (byte) ((value + 1) >>> 1);
            }
        }
    }

    /**
     * {@return one sample as an unsigned value}
     *
     * @param bytes the 8-bit samples, used when {@code wide} is {@code false}
     * @param shorts the 16-bit samples, used when {@code wide} is {@code true}
     * @param wide whether the samples are 16-bit
     * @param index the sample index
     */
    private static int sample(byte[] bytes, short[] shorts, boolean wide, int index) {
        return wide ? shorts[index] & 0xFFFF : bytes[index] & 0xFF;
    }
}
