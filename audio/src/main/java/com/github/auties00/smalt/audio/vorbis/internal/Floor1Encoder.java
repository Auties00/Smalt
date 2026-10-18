/* Ports libvorbis 1.3.7 (lib/floor1.c). Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis.internal;

import com.github.auties00.smalt.internal.bits.LsbBitWriter;
import java.util.Arrays;
import java.util.List;

/**
 * Fits a floor type 1 curve to a masking curve and codes it ({@code floor1_fit},
 * {@code floor1_encode}).
 *
 * <p>The fit is a greedy piecewise-linear least-squares approximation: the line between the two
 * implicit end posts is split at each further post, in post list order, whenever the error of the
 * segment exceeds the configured bounds. The coded floor is rendered back into the integer
 * amplitudes the decoder will reconstruct, which the residue quantization divides by.
 *
 * <p>Instances are not thread-safe.
 */
public final class Floor1Encoder {
    /**
     * The floor configuration.
     */
    private final Floor1Info info;

    /**
     * The number of posts, the two end posts included.
     */
    private final int posts;

    /**
     * The X range of the posts.
     */
    private final int n;

    /**
     * The post index of each sorted position.
     */
    private final int[] forwardIndex = new int[65];

    /**
     * The sorted position of each post.
     */
    private final int[] reverseIndex = new int[65];

    /**
     * The X position of each sorted post.
     */
    private final int[] sortedIndex = new int[65];

    /**
     * The low neighbor of each post after the end posts.
     */
    private final int[] loNeighbor = new int[63];

    /**
     * The high neighbor of each post after the end posts.
     */
    private final int[] hiNeighbor = new int[63];

    /**
     * The amplitude range of the multiplier.
     */
    private final int quantQ;

    /**
     * The codebooks.
     */
    private final List<EncoderCodebook> books;

    /**
     * The fit accumulators of the segments between sorted posts.
     */
    private final Fit[] fits = new Fit[64];

    /**
     * The least-squares accumulator of one segment.
     */
    private static final class Fit {
        /**
         * The first X.
         */
        int x0;

        /**
         * The last X.
         */
        int x1;

        /**
         * Sum of X over audible points.
         */
        int xa;

        /**
         * Sum of Y over audible points.
         */
        int ya;

        /**
         * Sum of X squared over audible points.
         */
        int x2a;

        /**
         * Sum of Y squared over audible points.
         */
        int y2a;

        /**
         * Sum of X times Y over audible points.
         */
        int xya;

        /**
         * Number of audible points.
         */
        int an;

        /**
         * Sum of X over masked points.
         */
        int xb;

        /**
         * Sum of Y over masked points.
         */
        int yb;

        /**
         * Sum of X squared over masked points.
         */
        int x2b;

        /**
         * Sum of Y squared over masked points.
         */
        int y2b;

        /**
         * Sum of X times Y over masked points.
         */
        int xyb;

        /**
         * Number of masked points.
         */
        int bn;
    }

    /**
     * Prepares the lookups of a floor ({@code floor1_look}).
     *
     * @param info the floor configuration
     * @param books the stream's codebooks
     */
    public Floor1Encoder(Floor1Info info, List<EncoderCodebook> books) {
        this.info = info;
        this.books = books;
        this.n = info.postList[1];
        var count = 0;
        for (var i = 0; i < info.partitions; i++) {
            count += info.classDim[info.partitionClass[i]];
        }
        count += 2;
        this.posts = count;
        var order = new Integer[count];
        for (var i = 0; i < count; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(info.postList[a], info.postList[b]));
        for (var i = 0; i < count; i++) {
            forwardIndex[i] = order[i];
        }
        for (var i = 0; i < count; i++) {
            reverseIndex[forwardIndex[i]] = i;
        }
        for (var i = 0; i < count; i++) {
            sortedIndex[i] = info.postList[forwardIndex[i]];
        }
        quantQ = switch (info.mult) {
            case 1 -> 256;
            case 2 -> 128;
            case 3 -> 86;
            default -> 64;
        };
        for (var i = 0; i < count - 2; i++) {
            var lo = 0;
            var hi = 1;
            var lx = 0;
            var hx = n;
            var currentX = info.postList[i + 2];
            for (var j = 0; j < i + 2; j++) {
                var x = info.postList[j];
                if (x > lx && x < currentX) {
                    lo = j;
                    lx = x;
                }
                if (x < hx && x > currentX) {
                    hi = j;
                    hx = x;
                }
            }
            loNeighbor[i] = lo;
            hiNeighbor[i] = hi;
        }
        for (var i = 0; i < fits.length; i++) {
            fits[i] = new Fit();
        }
    }

    /**
     * Returns the Y of a point on the line between two posts ({@code render_point}).
     *
     * @param x0 the first X
     * @param x1 the second X
     * @param y0 the first Y, flag bit ignored
     * @param y1 the second Y, flag bit ignored
     * @param x the X
     * @return the Y
     */
    private static int renderPoint(int x0, int x1, int y0, int y1, int x) {
        y0 &= 0x7FFF;
        y1 &= 0x7FFF;
        var dy = y1 - y0;
        var adx = x1 - x0;
        var ady = Math.abs(dy);
        var err = ady * (x - x0);
        var off = err / adx;
        return dy < 0 ? y0 - off : y0 + off;
    }

    /**
     * Quantizes a decibel value to the 0 to 1023 fit scale ({@code vorbis_dBquant}).
     *
     * @param x the decibels
     * @return the quantized value
     */
    private static int dbQuant(float x) {
        var i = (int) (x * 7.3142857f + 1023.5f);
        if (i > 1023) {
            return 1023;
        }
        return Math.max(i, 0);
    }

    /**
     * Renders a line into integer amplitudes ({@code render_line0}).
     *
     * @param n the number of values
     * @param x0 the first X
     * @param x1 the second X
     * @param y0 the first Y
     * @param y1 the second Y
     * @param d receives the amplitudes
     */
    private static void renderLine(int n, int x0, int x1, int y0, int y1, int[] d) {
        var dy = y1 - y0;
        var adx = x1 - x0;
        var ady = Math.abs(dy);
        var base = dy / adx;
        var sy = dy < 0 ? base - 1 : base + 1;
        var x = x0;
        var y = y0;
        var err = 0;
        ady -= Math.abs(base * adx);
        if (n > x1) {
            n = x1;
        }
        if (x < n) {
            d[x] = y;
        }
        while (++x < n) {
            err = err + ady;
            if (err >= adx) {
                err -= adx;
                y += sy;
            } else {
                y += base;
            }
            d[x] = y;
        }
    }

    /**
     * Accumulates the least-squares sums of a segment ({@code accumulate_fit}).
     *
     * @param flr the mask in decibels
     * @param mdct the MDCT spectrum in decibels
     * @param x0 the first X
     * @param x1 the last X
     * @param a receives the sums
     * @param n the number of lines
     * @return the number of audible points
     */
    private int accumulateFit(float[] flr, float[] mdct, int x0, int x1, Fit a, int n) {
        int xa = 0;
        int ya = 0;
        int x2a = 0;
        int y2a = 0;
        int xya = 0;
        int na = 0;
        int xb = 0;
        int yb = 0;
        int x2b = 0;
        int y2b = 0;
        int xyb = 0;
        int nb = 0;
        a.x0 = x0;
        a.x1 = x1;
        if (x1 >= n) {
            x1 = n - 1;
        }
        for (var i = x0; i <= x1; i++) {
            var quantized = dbQuant(flr[i]);
            if (quantized != 0) {
                if (mdct[i] + info.twoFitAttenuation >= flr[i]) {
                    xa += i;
                    ya += quantized;
                    x2a += i * i;
                    y2a += quantized * quantized;
                    xya += i * quantized;
                    na++;
                } else {
                    xb += i;
                    yb += quantized;
                    x2b += i * i;
                    y2b += quantized * quantized;
                    xyb += i * quantized;
                    nb++;
                }
            }
        }
        a.xa = xa;
        a.ya = ya;
        a.x2a = x2a;
        a.y2a = y2a;
        a.xya = xya;
        a.an = na;
        a.xb = xb;
        a.yb = yb;
        a.x2b = x2b;
        a.y2b = y2b;
        a.xyb = xyb;
        a.bn = nb;
        return na;
    }

    /**
     * Fits a line over consecutive segments, optionally through fixed end points
     * ({@code fit_line}).
     *
     * @param first the first segment
     * @param count the number of segments
     * @param y the end point Ys, {@code -200} when free; receives the fitted Ys
     * @return 0 on success, 1 when the segments hold no data
     */
    private int fitLine(int first, int count, int[] y) {
        double xb = 0;
        double yb = 0;
        double x2b = 0;
        double y2b = 0;
        double xyb = 0;
        double bn = 0;
        var x0 = fits[first].x0;
        var x1 = fits[first + count - 1].x1;
        for (var i = 0; i < count; i++) {
            var a = fits[first + i];
            var weight = (a.bn + a.an) * info.twoFitWeight / (a.an + 1) + 1.;
            xb += a.xb + a.xa * weight;
            yb += a.yb + a.ya * weight;
            x2b += a.x2b + a.x2a * weight;
            y2b += a.y2b + a.y2a * weight;
            xyb += a.xyb + a.xya * weight;
            bn += a.bn + a.an * weight;
        }
        if (y[0] >= 0) {
            xb += x0;
            yb += y[0];
            x2b += x0 * x0;
            y2b += y[0] * y[0];
            xyb += y[0] * x0;
            bn++;
        }
        if (y[1] >= 0) {
            xb += x1;
            yb += y[1];
            x2b += x1 * x1;
            y2b += y[1] * y[1];
            xyb += y[1] * x1;
            bn++;
        }
        var denominator = bn * x2b - xb * xb;
        if (denominator > 0.) {
            var a = (yb * x2b - xyb * xb) / denominator;
            var b = (bn * xyb - xb * yb) / denominator;
            y[0] = (int) Math.rint(a + b * x0);
            y[1] = (int) Math.rint(a + b * x1);
            y[0] = Math.clamp(y[0], 0, 1023);
            y[1] = Math.clamp(y[1], 0, 1023);
            return 0;
        }
        y[0] = 0;
        y[1] = 0;
        return 1;
    }

    /**
     * Returns whether a segment's line strays too far from the mask ({@code inspect_error}).
     *
     * @param x0 the first X
     * @param x1 the second X
     * @param y0 the first Y
     * @param y1 the second Y
     * @param mask the mask in decibels
     * @param mdct the MDCT spectrum in decibels
     * @return {@code true} to split the segment
     */
    private boolean inspectError(int x0, int x1, int y0, int y1, float[] mask, float[] mdct) {
        var dy = y1 - y0;
        var adx = x1 - x0;
        var ady = Math.abs(dy);
        var base = dy / adx;
        var sy = dy < 0 ? base - 1 : base + 1;
        var x = x0;
        var y = y0;
        var err = 0;
        var val = dbQuant(mask[x]);
        int mse;
        var count = 0;
        ady -= Math.abs(base * adx);
        mse = y - val;
        mse *= mse;
        count++;
        if (mdct[x] + info.twoFitAttenuation >= mask[x]) {
            if (y + info.maxOver < val) {
                return true;
            }
            if (y - info.maxUnder > val) {
                return true;
            }
        }
        while (++x < x1) {
            err = err + ady;
            if (err >= adx) {
                err -= adx;
                y += sy;
            } else {
                y += base;
            }
            val = dbQuant(mask[x]);
            mse += (y - val) * (y - val);
            count++;
            if (mdct[x] + info.twoFitAttenuation >= mask[x]) {
                if (val != 0) {
                    if (y + info.maxOver < val) {
                        return true;
                    }
                    if (y - info.maxUnder > val) {
                        return true;
                    }
                }
            }
        }
        if (info.maxOver * info.maxOver / count > info.maxError) {
            return false;
        }
        if (info.maxUnder * info.maxUnder / count > info.maxError) {
            return false;
        }
        return mse / count > info.maxError;
    }

    /**
     * Returns the Y of a post from the two fits ({@code post_Y}).
     *
     * @param a the first fit
     * @param b the second fit
     * @param pos the post
     * @return the Y
     */
    private static int postY(int[] a, int[] b, int pos) {
        if (a[pos] < 0) {
            return b[pos];
        }
        if (b[pos] < 0) {
            return a[pos];
        }
        return (a[pos] + b[pos]) >> 1;
    }

    /**
     * Fits the floor to a mask ({@code floor1_fit}).
     *
     * @param logMdct the MDCT spectrum in decibels
     * @param logMask the mask in decibels
     * @return the post values (bit 15 marks an interpolated post), or {@code null} for an unused
     * floor
     */
    public int[] fit(float[] logMdct, float[] logMask) {
        var lines = n;
        var nonzero = 0;
        var fitA = new int[65];
        var fitB = new int[65];
        var lo = new int[65];
        var hi = new int[65];
        var memo = new int[65];
        Arrays.fill(fitA, 0, posts, -200);
        Arrays.fill(fitB, 0, posts, -200);
        Arrays.fill(lo, 0, posts, 0);
        Arrays.fill(hi, 0, posts, 1);
        Arrays.fill(memo, 0, posts, -1);
        if (posts == 0) {
            nonzero += accumulateFit(logMask, logMdct, 0, lines, fits[0], lines);
        } else {
            for (var i = 0; i < posts - 1; i++) {
                nonzero += accumulateFit(logMask, logMdct, sortedIndex[i], sortedIndex[i + 1], fits[i], lines);
            }
        }
        if (nonzero == 0) {
            return null;
        }
        var ends = new int[]{-200, -200};
        fitLine(0, posts - 1, ends);
        fitA[0] = ends[0];
        fitB[0] = ends[0];
        fitB[1] = ends[1];
        fitA[1] = ends[1];
        var low = new int[2];
        var high = new int[2];
        for (var i = 2; i < posts; i++) {
            var sortPos = reverseIndex[i];
            var ln = lo[sortPos];
            var hn = hi[sortPos];
            if (memo[ln] != hn) {
                var lSortPos = reverseIndex[ln];
                var hSortPos = reverseIndex[hn];
                memo[ln] = hn;
                var lx = info.postList[ln];
                var hx = info.postList[hn];
                var ly = postY(fitA, fitB, ln);
                var hy = postY(fitA, fitB, hn);
                if (ly == -1 || hy == -1) {
                    throw new IllegalStateException("floor fit lost a post");
                }
                if (inspectError(lx, hx, ly, hy, logMask, logMdct)) {
                    low[0] = -200;
                    low[1] = -200;
                    high[0] = -200;
                    high[1] = -200;
                    var ret0 = fitLine(lSortPos, sortPos - lSortPos, low);
                    var ret1 = fitLine(sortPos, hSortPos - sortPos, high);
                    if (ret0 != 0) {
                        low[0] = ly;
                        low[1] = high[0];
                    }
                    if (ret1 != 0) {
                        high[0] = low[1];
                        high[1] = hy;
                    }
                    if (ret0 != 0 && ret1 != 0) {
                        fitA[i] = -200;
                        fitB[i] = -200;
                    } else {
                        fitB[ln] = low[0];
                        if (ln == 0) {
                            fitA[ln] = low[0];
                        }
                        fitA[i] = low[1];
                        fitB[i] = high[0];
                        fitA[hn] = high[1];
                        if (hn == 1) {
                            fitB[hn] = high[1];
                        }
                        if (low[1] >= 0 || high[0] >= 0) {
                            for (var j = sortPos - 1; j >= 0; j--) {
                                if (hi[j] == hn) {
                                    hi[j] = i;
                                } else {
                                    break;
                                }
                            }
                            for (var j = sortPos + 1; j < posts; j++) {
                                if (lo[j] == ln) {
                                    lo[j] = i;
                                } else {
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    fitA[i] = -200;
                    fitB[i] = -200;
                }
            }
        }
        var output = new int[posts];
        output[0] = postY(fitA, fitB, 0);
        output[1] = postY(fitA, fitB, 1);
        for (var i = 2; i < posts; i++) {
            var ln = loNeighbor[i - 2];
            var hn = hiNeighbor[i - 2];
            var x0 = info.postList[ln];
            var x1 = info.postList[hn];
            var y0 = output[ln];
            var y1 = output[hn];
            var predicted = renderPoint(x0, x1, y0, y1, info.postList[i]);
            var vx = postY(fitA, fitB, i);
            if (vx >= 0 && predicted != vx) {
                output[i] = vx;
            } else {
                output[i] = predicted | 0x8000;
            }
        }
        return output;
    }

    /**
     * Codes a fitted floor and renders the integer amplitudes the decoder will reconstruct
     * ({@code floor1_encode}).
     *
     * @param out the packet
     * @param post the fitted posts, or {@code null} for an unused floor; modified
     * @param ilogmask receives the amplitude index of each line
     * @param halfSize half the block size
     * @return whether the floor is used
     */
    public boolean encode(LsbBitWriter out, int[] post, int[] ilogmask, int halfSize) {
        if (post == null) {
            out.write(0, 1);
            Arrays.fill(ilogmask, 0, halfSize, 0);
            return false;
        }
        var coded = new int[65];
        for (var i = 0; i < posts; i++) {
            var val = post[i] & 0x7FFF;
            switch (info.mult) {
                case 1 -> val >>= 2;
                case 2 -> val >>= 3;
                case 3 -> val /= 12;
                default -> val >>= 4;
            }
            post[i] = val | (post[i] & 0x8000);
        }
        coded[0] = post[0];
        coded[1] = post[1];
        for (var i = 2; i < posts; i++) {
            var ln = loNeighbor[i - 2];
            var hn = hiNeighbor[i - 2];
            var x0 = info.postList[ln];
            var x1 = info.postList[hn];
            var y0 = post[ln];
            var y1 = post[hn];
            var predicted = renderPoint(x0, x1, y0, y1, info.postList[i]);
            if ((post[i] & 0x8000) != 0 || predicted == post[i]) {
                post[i] = predicted | 0x8000;
                coded[i] = 0;
            } else {
                var headroom = Math.min(quantQ - predicted, predicted);
                var val = post[i] - predicted;
                if (val < 0) {
                    if (val < -headroom) {
                        val = headroom - val - 1;
                    } else {
                        val = -1 - (val << 1);
                    }
                } else if (val >= headroom) {
                    val = val + headroom;
                } else {
                    val <<= 1;
                }
                coded[i] = val;
                post[ln] &= 0x7FFF;
                post[hn] &= 0x7FFF;
            }
        }
        out.write(1, 1);
        var bits = Ilog.ilog(quantQ - 1);
        out.write(coded[0], bits);
        out.write(coded[1], bits);
        for (int i = 0, j = 2; i < info.partitions; i++) {
            var cls = info.partitionClass[i];
            var cdim = info.classDim[cls];
            var csubbits = info.classSubs[cls];
            var csub = 1 << csubbits;
            var bookas = new int[8];
            var cval = 0;
            var cshift = 0;
            if (csubbits != 0) {
                var maxval = new int[8];
                for (var k = 0; k < csub; k++) {
                    var bookNumber = info.classSubbook[cls][k];
                    maxval[k] = bookNumber < 0 ? 1 : books.get(bookNumber).entries();
                }
                for (var k = 0; k < cdim; k++) {
                    for (var l = 0; l < csub; l++) {
                        if (coded[j + k] < maxval[l]) {
                            bookas[k] = l;
                            break;
                        }
                    }
                    cval |= bookas[k] << cshift;
                    cshift += csubbits;
                }
                books.get(info.classBook[cls]).encode(cval, out);
            }
            for (var k = 0; k < cdim; k++) {
                var book = info.classSubbook[cls][bookas[k]];
                if (book >= 0 && coded[j + k] < books.get(book).entries()) {
                    books.get(book).encode(coded[j + k], out);
                }
            }
            j += cdim;
        }
        var hx = 0;
        var lx = 0;
        var ly = post[0] * info.mult;
        for (var j = 1; j < posts; j++) {
            var current = forwardIndex[j];
            var hy = post[current] & 0x7FFF;
            if (hy == post[current]) {
                hy *= info.mult;
                hx = info.postList[current];
                renderLine(halfSize, lx, hx, ly, hy, ilogmask);
                lx = hx;
                ly = hy;
            }
        }
        for (var j = hx; j < halfSize; j++) {
            ilogmask[j] = ly;
        }
        return true;
    }
}
