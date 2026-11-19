/* Ports cineform-sdk (Codec/spatial.c InvertSpatialTopRow16s, InvertSpatialMiddleRow16s, InvertSpatialBottomRow16s,
 * InvertSpatialQuantDescale16s and InvertSpatialQuantOverflowProtected16s, Codec/InvertHorizontalStrip16s.c
 * InvertHorizontalStrip16s, InvertHorizontalStripDescale16s and InvertHorizontalStrip16sToRow16u, Codec/spatial.c
 * InvertHorizontalRow16s, Codec/temporal.c InvertTemporal16s and InvertInterlacedRow16sToRow16u: the inverse
 * CineForm wavelets). Copyright GoPro Inc; Apache License 2.0 or MIT. Modified for Smalt. */
package com.github.auties00.smalt.video.cfhd.internal;

/**
 * Inverts the wavelets of CineForm samples: the 2/6 spatial wavelet, the frame wavelet (a horizontal 2/6 wavelet and a
 * Haar wavelet between the two fields of a frame) and the temporal Haar wavelet between the two frames of a group.
 *
 * <p>The 2/6 synthesis reconstructs an even and an odd sample from a lowpass sample {@code l[i]} and a highpass sample
 * {@code h[i]}: {@code even = l[i] + ((l[i-1] - l[i+1] + 4) >> 3) + h[i]} and
 * {@code odd = l[i] + ((l[i+1] - l[i-1] + 4) >> 3) - h[i]}, with the boundary filters (11, -4, 1) and (5, 4, -1) in
 * eighths at the first sample and their mirror at the last; the vertical pass halves both, the horizontal pass halves,
 * keeps or doubles them as the encoder's prescaling of the level requires. Every intermediate result is saturated to
 * 16 bits. Planes are arrays of coefficients row after row. Arguments are not checked; values outside the documented
 * ranges give unspecified results. Instances hold their row buffers and are not thread-safe.
 */
public final class CfhdWavelets {
    /**
     * The horizontal lowpass rows of the level being inverted.
     */
    private short[] lowRows = new short[0];

    /**
     * The horizontal highpass rows of the level being inverted.
     */
    private short[] highRows = new short[0];

    /**
     * Constructs the buffers.
     */
    public CfhdWavelets() {
    }

    private static short saturate(int value) {
        return (short) Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Synthesizes the even and odd samples of position {@code i} of a line.
     *
     * @param low the lowpass line
     * @param lowAt the index of the line's first sample
     * @param lowStep the distance between consecutive samples
     * @param high the highpass line
     * @param highAt the index of the line's first sample
     * @param highStep the distance between consecutive samples
     * @param n the number of samples of the lines, at least 1
     * @param i the position
     * @param scale -1 to halve both results, 0 to keep them, 1 to double them
     * @param lag the number of positions by which the last position's boundary filter reads its three lowpass samples
     * early, 0 or 1
     * @return the even result in the low 16 bits and the odd one in the high 16 bits
     */
    private static int synthesize(short[] low, int lowAt, int lowStep, short[] high, int highAt, int highStep, int n,
            int i, int scale, int lag) {
        int even;
        int odd;
        var l = low[lowAt + (i == n - 1 && n > 3 ? i - lag : i) * lowStep];
        if (n >= 3 && i == 0) {
            var l1 = low[lowAt + lowStep];
            var l2 = low[lowAt + 2 * lowStep];
            even = (11 * l - 4 * l1 + l2 + 4) >> 3;
            odd = (5 * l + 4 * l1 - l2 + 4) >> 3;
        } else if (n >= 3 && i == n - 1) {
            var at = n > 3 ? i - lag : i;
            var l1 = low[lowAt + (at - 1) * lowStep];
            var l2 = low[lowAt + (at - 2) * lowStep];
            even = (5 * l + 4 * l1 - l2 + 4) >> 3;
            odd = (11 * l - 4 * l1 + l2 + 4) >> 3;
        } else {
            var before = low[lowAt + Math.max(i - 1, 0) * lowStep];
            var after = low[lowAt + Math.min(i + 1, n - 1) * lowStep];
            even = ((before - after + 4) >> 3) + l;
            odd = ((after - before + 4) >> 3) + l;
        }
        var h = high[highAt + i * highStep];
        even += h;
        odd -= h;
        if (scale < 0) {
            even >>= 1;
            odd >>= 1;
        } else if (scale > 0) {
            even = saturate(even) << 1;
            odd = saturate(odd) << 1;
        }
        return (saturate(even) & 0xFFFF) | saturate(odd) << 16;
    }

    /**
     * Makes the row buffers hold a level.
     *
     * @param samples the samples of each buffer
     */
    private void ensure(int samples) {
        if (lowRows.length < samples) {
            lowRows = new short[samples];
            highRows = new short[samples];
        }
    }

    /**
     * Inverts the vertical pass of a spatial level into {@link #lowRows} and {@link #highRows}, rows of {@code width}
     * samples, twice as many as the bands have.
     *
     * @param bands the four bands: lowlow, lowhigh, highlow and highhigh
     * @param width the band width
     * @param height the band height
     * @param lag 1 to read the lowlow rows of the last row's boundary filter one row early, as GoPro's decoder does
     * for the levels it inverts with 32-bit intermediates, or 0
     */
    private void vertical(short[][] bands, int width, int height, int lag) {
        ensure(2 * width * height);
        for (var x = 0; x < width; x++) {
            for (var i = 0; i < height; i++) {
                var low = synthesize(bands[0], x, width, bands[2], x, width, height, i, -1, lag);
                lowRows[2 * i * width + x] = (short) low;
                lowRows[(2 * i + 1) * width + x] = (short) (low >> 16);
                var high = synthesize(bands[1], x, width, bands[3], x, width, height, i, -1, 0);
                highRows[2 * i * width + x] = (short) high;
                highRows[(2 * i + 1) * width + x] = (short) (high >> 16);
            }
        }
    }

    /**
     * Inverts one spatial level into the lowpass band of the next finer level.
     *
     * @param bands the four bands of the level
     * @param width the band width
     * @param height the band height
     * @param scale the scaling of the horizontal pass: -1 halves its results (a level the encoder did not prescale),
     * 0 keeps them (a level prescaled once) and 1 doubles them (a level prescaled twice and coded above 8 bits)
     * @param lag 1 for a level GoPro's decoder inverts with 32-bit intermediates (an unprescaled level of the fourth
     * level or above, and the unprescaled level above the temporal highpass band), whose last row's boundary filter
     * reads the lowlow rows one row early, or 0
     * @param out receives the {@code 2 * width} by {@code 2 * height} plane
     */
    public void spatial(short[][] bands, int width, int height, int scale, int lag, short[] out) {
        vertical(bands, width, height, lag);
        for (var row = 0; row < 2 * height; row++) {
            var at = row * width;
            for (var i = 0; i < width; i++) {
                var pair = synthesize(lowRows, at, 1, highRows, at, 1, width, i, scale, 0);
                out[row * 2 * width + 2 * i] = (short) pair;
                out[row * 2 * width + 2 * i + 1] = (short) (pair >> 16);
            }
        }
    }

    /**
     * Inverts the finest spatial level into a channel of samples clamped to their precision.
     *
     * @param bands the four bands of the level
     * @param width the band width
     * @param height the band height
     * @param max the largest sample value
     * @param out receives the {@code 2 * width} by {@code 2 * height} samples
     */
    public void spatialToSamples(short[][] bands, int width, int height, int max, int[] out) {
        vertical(bands, width, height, 0);
        for (var row = 0; row < 2 * height; row++) {
            var at = row * width;
            for (var i = 0; i < width; i++) {
                var pair = synthesize(lowRows, at, 1, highRows, at, 1, width, i, -1, 0);
                out[row * 2 * width + 2 * i] = Math.clamp((short) pair, 0, max);
                out[row * 2 * width + 2 * i + 1] = Math.clamp((short) (pair >> 16), 0, max);
            }
        }
    }

    /**
     * Inverts the finest spatial level into a channel of 16-bit samples as GoPro's decoder writes its 16-bit rows of
     * four-channel frames: the coded precision in the top bits.
     *
     * <p>The horizontal pass follows that decoder's vector loop, which covers the columns up to
     * {@code max(8, width - width % 8 - 16) + 6} with 16-bit saturating arithmetic and clamps each result to the coded
     * precision, and its scalar code past them, which saturates the shifted result to 16 bits instead, so a sample
     * above the coded range becomes 65535 there; the first two and the last two samples of a row take the boundary
     * filters. A band narrower than 16 coefficients, where that decoder's vector code reads a coefficient it has not
     * loaded, takes the vector arithmetic for every interior column.
     *
     * @param bands the four bands of the level
     * @param width the band width
     * @param height the band height
     * @param precision the coded precision, from 8 to 12
     * @param out receives the {@code 2 * width} by {@code 2 * height} samples
     */
    public void spatialToRow16u(short[][] bands, int width, int height, int precision, int[] out) {
        if (width < 3) {
            spatialToSamples(bands, width, height, (1 << precision) - 1, out);
            for (var i = 0; i < 4 * width * height; i++) {
                out[i] <<= 16 - precision;
            }
            return;
        }
        vertical(bands, width, height, 0);
        var shift = 16 - precision;
        var protection = 0x7FFF - (2 << precision) + 1;
        var vector = width >= 16 ? Math.max(8, width - width % 8 - 16) + 7 : width - 1;
        var max = 0xFFFF >> shift;
        for (var row = 0; row < 2 * height; row++) {
            var at = row * width;
            var to = row * 2 * width;
            var l = lowRows;
            var h = highRows;
            var even = ((11 * l[at] - 4 * l[at + 1] + l[at + 2] + 4) >> 3) + h[at] >> 1;
            var odd = ((5 * l[at] + 4 * l[at + 1] - l[at + 2] + 4) >> 3) - h[at] >> 1;
            out[to] = Math.clamp(even, 0, max) << shift;
            out[to + 1] = Math.clamp(odd, 0, max) << shift;
            for (var c = 1; c < vector; c++) {
                int left = l[at + c - 1];
                int right = l[at + c + 1];
                int low = l[at + c];
                int high = h[at + c];
                var e = saturate(saturate(saturate(saturate(left - right) + 4) >> 3) + low);
                var o = saturate(saturate(saturate(saturate(right - left) + 4) >> 3) + low);
                out[to + 2 * c] = protect(saturate(e + high), protection, shift);
                out[to + 2 * c + 1] = protect(saturate(o - high), protection, shift);
            }
            for (var c = vector; c < width - 1; c++) {
                int left = l[at + c - 1];
                int right = l[at + c + 1];
                int low = l[at + c];
                int high = h[at + c];
                out[to + 2 * c] = Math.clamp(((left - right + 4) >> 3) + low + high >> 1 << shift, 0, 0xFFFF);
                out[to + 2 * c + 1] = Math.clamp(((right - left + 4) >> 3) + low - high >> 1 << shift, 0, 0xFFFF);
            }
            var c = at + width - 1;
            even = ((5 * l[c] + 4 * l[c - 1] - l[c - 2] + 4) >> 3) + h[c] >> 1;
            odd = ((11 * l[c] - 4 * l[c - 1] + l[c - 2] + 4) >> 3) - h[c] >> 1;
            out[to + 2 * width - 2] = Math.clamp(even << shift, 0, 0xFFFF);
            out[to + 2 * width - 1] = Math.clamp(odd << shift, 0, 0xFFFF);
        }
    }

    /**
     * Applies the vector code's clamp of a sum to twice the coded range, its halving and its shift to 16 bits.
     *
     * @param sum the saturated 16-bit sum
     * @param protection {@code 0x7FFF - (2 << precision) + 1}
     * @param shift {@code 16 - precision}
     * @return the 16-bit sample
     */
    private static int protect(int sum, int protection, int shift) {
        var biased = saturate(sum + protection) & 0xFFFF;
        var clamped = Math.max(biased - protection, 0);
        return (((short) clamped >> 1) << shift) & 0xFFFF;
    }

    /**
     * Inverts a frame level into a channel of samples clamped to their precision: the horizontal wavelet of the field
     * lowpass and highpass bands, then the Haar wavelet between the fields, {@code even = (low - high) >> 1} and
     * {@code odd = (low + high) >> 1}.
     *
     * @param bands the four bands: lowlow, lowhigh (horizontal highpass of the field lowpass), highlow and highhigh
     * @param width the band width
     * @param height the band height
     * @param max the largest sample value
     * @param out receives the {@code 2 * width} by {@code 2 * height} samples
     */
    public void frameToSamples(short[][] bands, int width, int height, int max, int[] out) {
        var outWidth = 2 * width;
        for (var row = 0; row < height; row++) {
            var at = row * width;
            for (var i = 0; i < width; i++) {
                var low = synthesize(bands[0], at, 1, bands[1], at, 1, width, i, -1, 0);
                var high = synthesize(bands[2], at, 1, bands[3], at, 1, width, i, -1, 0);
                for (var half = 0; half < 2; half++) {
                    int l = (short) (half == 0 ? low : low >> 16);
                    int h = (short) (half == 0 ? high : high >> 16);
                    var x = 2 * i + half;
                    out[2 * row * outWidth + x] = Math.clamp((l - h) >> 1, 0, max);
                    out[(2 * row + 1) * outWidth + x] = Math.clamp((l + h) >> 1, 0, max);
                }
            }
        }
    }

    /**
     * Inverts the temporal wavelet of a group into the lowpass bands of its two frame levels:
     * {@code first = (low - high) >> 1} and {@code second = (low + high + t) >> 1}.
     *
     * <p>The halftone {@code t} is 0 above 8 bits. At 8 bits it alternates between 0 and 1 in a checkerboard, as
     * GoPro's decoder adds it: 1 where the sum of the column and the row is even in the columns of a row that its
     * vector loop handles (the largest multiple of 40 below the width) and where it is odd in the others. The sums of
     * the vector loop saturate to 16 bits before the shift, the others do not.
     *
     * @param low the temporal lowpass plane
     * @param high the temporal highpass plane
     * @param width the plane width
     * @param height the plane height
     * @param precision the coded precision
     * @param first receives the first frame's lowpass band
     * @param second receives the second frame's lowpass band
     */
    public static void temporal(short[] low, short[] high, int width, int height, int precision, short[] first,
            short[] second) {
        var vector = width - width % 40;
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var at = row * width + column;
                int l = low[at];
                int h = high[at];
                if (column < vector) {
                    var halftone = precision == 8 && (column + row & 1) == 0 ? 1 : 0;
                    first[at] = (short) (saturate(l - h) >> 1);
                    second[at] = (short) (saturate(saturate(l + h) + halftone) >> 1);
                } else {
                    var halftone = precision == 8 ? column + row & 1 : 0;
                    first[at] = (short) ((l - h) >> 1);
                    second[at] = (short) ((l + h + halftone) >> 1);
                }
            }
        }
    }
}
