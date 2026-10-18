package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.intra.internal.IntraBands;
import com.github.auties00.smalt.video.intra.internal.IntraBitWriter;
import com.github.auties00.smalt.video.intra.internal.IntraInput;
import com.github.auties00.smalt.video.intra.internal.IntraPlanes;

import java.util.Arrays;

/**
 * Encodes pictures into VC-3 (DNxHD and DNxHR) frames, SMPTE ST 2019-1: prepares the planes,
 * transforms every macroblock, chooses the quantization scale factors so that each coding unit
 * fills its constant size, and assembles the header, the macroblock scan lines, the padding and
 * the end-of-frame signature of every coding unit.
 *
 * <p>4:4:4 Y'CbCr pictures are coded with the color format flag {@code CLF} clear, RGB pictures
 * with it set (ST 2019-1 clause 7.2.5), at the BT.709 levels of Table 2 for the in-band BT.709
 * color volume. The macroblocks of an RGB stream are either all coded in the alternate color
 * space, BT.709 Y'CbCr with the alternate color space flag {@code ACF} set (clauses 7.3.1.1 and
 * 8.1.1), or all coded as red, green and blue in that order (clause 6.3, Table 6) with the flag
 * clear. 4:2:2 IDs of the RI profile may code 4:2:0 instead ({@code SSC = 01b}).
 *
 * <p>With alpha, every coding unit sets {@code ALP} (header version 4 for the HD profile, as
 * Amendment 1:2023 requires), takes the size of its compression ID with alpha, and follows the
 * video macroblocks of each scan line with its alpha macroblocks: DCT-coded like luma with the
 * alpha weights of the compression ID, or, when lossless alpha is requested on IDs 1256 and 1270,
 * run-length coded with {@code LLA} set. A coding unit whose lossless alpha leaves no room for the
 * video at the coarsest scale factor falls back to DCT-coded alpha.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation chooses the finest scale factor that lets the whole coding unit
 * fit, the same for every macroblock, then spends what is left by giving the next finer
 * factor to as many macroblocks as fit, in raster order. Sizes are measured exactly from
 * the code lengths, scan lines in parallel, and every measurement of a frame is reused;
 * the search starts from the factor of the previous frame and jumps to the factor that
 * keeps the size per factor constant before bracketing and bisecting. It never mixes
 * RGB and Y'CbCr macroblocks in one stream, so the reading of the DC predictors of
 * clause 8.2.4 across the two color spaces does not arise in its output.
 */
public final class Vc3FrameEncoder {
    /**
     * The frame width.
     */
    private final int width;

    /**
     * The frame height.
     */
    private final int height;

    /**
     * The compression ID.
     */
    private final Vc3Compression compression;

    /**
     * The sample bit depth.
     */
    private final int depth;

    /**
     * Whether the stream codes RGB.
     */
    private final boolean rgb;

    /**
     * Whether the macroblocks of an RGB stream are coded as Y'CbCr with {@code ACF} set.
     */
    private final boolean alternate;

    /**
     * The color description of RGB input converted to Y'CbCr.
     */
    private final ColorInfo color;

    /**
     * The chroma layout of the coded Y'CbCr samples.
     */
    private final ChromaFormat chroma;

    /**
     * Whether frames carry an alpha channel.
     */
    private final boolean alpha;

    /**
     * Whether the alpha channel is coded losslessly when it fits.
     */
    private final boolean losslessAlpha;

    /**
     * The thread count.
     */
    private final int threads;

    /**
     * The allocation limits.
     */
    private final MediaLimits limits;

    /**
     * The number of coding units of a frame.
     */
    private final int units;

    /**
     * The width in macroblocks.
     */
    private final int widthInMb;

    /**
     * The height of a coding unit in macroblocks.
     */
    private final int heightInMb;

    /**
     * The coefficient store of every coding unit, created on first use.
     */
    private final Vc3Coefficients[] stores;

    /**
     * The alpha coefficient store of every coding unit, created on first use.
     */
    private final Vc3Coefficients[] alphaStores;

    /**
     * The scale factor every coding unit used in the previous frame.
     */
    private final int[] lastScales;

    /**
     * The planes of the previous frame, whose arrays the next frame reuses.
     */
    private IntraPlanes recycled;

    /**
     * The row encoder of every parallel band, created on first use.
     */
    private Vc3RowEncoder[] rowEncoders = new Vc3RowEncoder[1];

    /**
     * Constructs a frame encoder.
     *
     * @param width the frame width
     * @param height the frame height
     * @param compression the compression ID
     * @param depth the sample bit depth
     * @param rgb whether to code RGB (4:4:4 IDs only)
     * @param alternate whether to code the macroblocks of an RGB stream as BT.709 Y'CbCr with the
     * alternate color space flag set; ignored without {@code rgb}
     * @param color the color description RGB input is converted with
     * @param chroma the chroma layout: {@link ChromaFormat#YUV444} for the 4:4:4 IDs,
     * {@link ChromaFormat#YUV422} or, for the RI IDs that permit it,
     * {@link ChromaFormat#YUV420} for the others
     * @param alpha whether frames carry an alpha channel; the ID must permit it
     * @param losslessAlpha whether to code the alpha channel losslessly (IDs 1256 and 1270)
     * @param threads the thread count: {@code 1} for the calling thread, otherwise the shared
     * pool with at most this many threads ({@code 0} for no cap)
     * @param limits the allocation limits
     */
    public Vc3FrameEncoder(int width, int height, Vc3Compression compression, int depth, boolean rgb, boolean alternate,
                           ColorInfo color, ChromaFormat chroma, boolean alpha, boolean losslessAlpha, int threads,
                           MediaLimits limits) {
        this.width = width;
        this.height = height;
        this.compression = compression;
        this.depth = depth;
        this.rgb = rgb;
        this.alternate = rgb && alternate;
        this.color = color;
        this.chroma = chroma;
        this.alpha = alpha;
        this.losslessAlpha = losslessAlpha;
        this.threads = threads;
        this.limits = limits;
        this.units = compression.codingUnits();
        this.widthInMb = (width + 15) >>> 4;
        this.heightInMb = (height / units + 15) >>> 4;
        this.stores = new Vc3Coefficients[units];
        this.alphaStores = new Vc3Coefficients[units];
        this.lastScales = new int[units];
        Arrays.fill(lastScales, 8);
    }

    /**
     * Encodes one picture into a frame.
     *
     * @param picture the picture, of the encoder's size
     * @return the frame: one coding unit, or two for field-encoded interlaced IDs
     */
    public byte[] encode(Picture picture) {
        var paddedWidth = widthInMb * 16;
        var paddedHeight = heightInMb * 16 * units;
        var planeSamples = chroma == ChromaFormat.YUV444 ? 3 : chroma == ChromaFormat.YUV420 ? 1.5 : 2;
        var blockBatches = chroma == ChromaFormat.YUV444 ? 2 : 1;
        LimitChecks.checkAllocation(limits, (long) ((paddedWidth * (long) paddedHeight) * (planeSamples + (alpha ? 1 : 0)) * 2)
                + (long) widthInMb * heightInMb * units * (blockBatches + (alpha ? 1 : 0)) * 2048);
        var alphaDepth = alpha ? depth : 0;
        IntraPlanes planes;
        IntraInput.Deferred deferred = null;
        if (rgb) {
            var source = IntraInput.rgb(picture, 16, alphaDepth, paddedWidth, paddedHeight);
            if (alternate) {
                Vc3Rgb.toYcbcr(source.first(), source.second(), source.third(), depth);
            } else {
                Vc3Rgb.compress(source.first(), depth);
                Vc3Rgb.compress(source.second(), depth);
                Vc3Rgb.compress(source.third(), depth);
            }
            planes = new IntraPlanes(source.first(), source.second(), source.third(), source.alpha(), paddedWidth,
                    paddedWidth, paddedHeight, depth, alphaDepth, source.color().withRange(ColorRange.LIMITED));
        } else {
            deferred = units == 1 && !alpha ? IntraInput.yuvDeferred(picture, chroma, depth, paddedWidth, paddedHeight,
                    recycled) : null;
            if (deferred != null) {
                planes = deferred.planes();
            } else {
                planes = IntraInput.yuv(picture, chroma, depth, color, alphaDepth, paddedWidth, paddedHeight, threads,
                        recycled);
            }
            recycled = planes;
        }
        var layout = chroma == ChromaFormat.YUV444 ? Vc3Layout.YUV444
                : chroma == ChromaFormat.YUV420 ? Vc3Layout.YUV420 : Vc3Layout.YUV422;
        var unitSize = compression.codingUnitSize(widthInMb, heightInMb, alpha);
        var out = new byte[unitSize * units];
        for (var unit = 0; unit < units; unit++) {
            if (stores[unit] == null) {
                stores[unit] = new Vc3Coefficients(compression, layout, depth, widthInMb, heightInMb, false);
            }
            var store = stores[unit];
            store.field(unit, units);
            encodeUnit(planes, deferred, store, unit, out, unit * unitSize, unitSize);
        }
        return out;
    }

    /**
     * Returns the alpha coefficient store of a coding unit, transformed.
     *
     * @param planes the prepared planes
     * @param unit the coding unit index
     * @param bands the band count
     * @return the store
     */
    private Vc3Coefficients alphaStore(IntraPlanes planes, int unit, int bands) {
        if (alphaStores[unit] == null) {
            alphaStores[unit] = new Vc3Coefficients(compression, Vc3Layout.ALPHA, depth, widthInMb, heightInMb, true);
        }
        var store = alphaStores[unit];
        store.field(unit, units);
        run(bands, (encoder, row) -> encoder.transform(planes, store, row));
        return store;
    }

    /**
     * Encodes one coding unit.
     *
     * @param planes the prepared planes
     * @param deferred the conversion that fills the planes row by row, or {@code null} when the
     * planes are filled already
     * @param store the coding unit's coefficient store
     * @param unit the coding unit index
     * @param out the frame
     * @param offset the index of the coding unit's first byte
     * @param size the coding unit size
     */
    private void encodeUnit(IntraPlanes planes, IntraInput.Deferred deferred, Vc3Coefficients store, int unit,
                            byte[] out, int offset, int size) {
        var headerSize = Vc3Header.headerSize(heightInMb);
        var budget = (long) (size - headerSize - 4) * 8;
        var bands = IntraBands.count(threads, heightInMb, (long) width * height / units);
        if (rowEncoders.length < bands) {
            rowEncoders = Arrays.copyOf(rowEncoders, bands);
        }
        var bandCount = bands;
        var maxScale = compression.cid() == 1260 ? 1023 : 2047;
        var startScale = Math.clamp(lastScales[unit], 1, maxScale);
        int[][] startBits = null;
        if (alpha) {
            run(bandCount, (encoder, row) -> encoder.transform(planes, store, row));
        } else {
            var measured = new int[heightInMb][widthInMb];
            startBits = measured;
            run(bandCount, (encoder, row) -> {
                if (deferred != null) {
                    deferred.fill(row);
                }
                encoder.transform(planes, store, row);
                encoder.measure(store, null, null, row, startScale, measured[row]);
            });
        }
        Vc3Coefficients alphaStore = null;
        int[][] lossless = null;
        if (alpha && losslessAlpha) {
            var sizes = new int[heightInMb][widthInMb];
            run(bandCount, (encoder, row) -> encoder.measureLossless(planes, store, row, sizes[row]));
            lossless = sizes;
            if (measure(store, null, lossless, maxScale, new int[heightInMb][widthInMb], bandCount) > budget) {
                lossless = null;
            }
        }
        if (alpha && lossless == null) {
            alphaStore = alphaStore(planes, unit, bandCount);
        }
        var alphaPart = alphaStore;
        var losslessPart = lossless;
        var sizes = new Sizes(store, alphaPart, losslessPart, bandCount);
        if (startBits != null) {
            sizes.accept(startScale, startBits);
        }
        var scale = search(sizes, startScale, maxScale, budget);
        lastScales[unit] = scale;
        var scales = new int[heightInMb][widthInMb];
        for (var row = 0; row < heightInMb; row++) {
            Arrays.fill(scales[row], scale);
        }
        if (scale > 1) {
            refine(sizes.bits(scale), sizes.bits(scale - 1), scales, budget);
        }
        var rows = new byte[heightInMb][];
        var losslessPlanes = losslessPart != null ? planes : null;
        run(bandCount, (encoder, row) -> {
            var writer = encoder.writer();
            writer.reset();
            encoder.write(store, alphaPart, losslessPlanes, row, scales[row], alternate, writer);
            rows[row] = writer.toByteArray();
        });
        var offsets = new int[heightInMb];
        var at = 0;
        for (var row = 0; row < heightInMb; row++) {
            offsets[row] = at;
            at += rows[row].length;
        }
        if (at > size - headerSize - 4) {
            throw new IllegalStateException("VC-3 coding unit overflow: " + at + " bytes");
        }
        var interlaced = compression.interlaced();
        var fieldCount = units == 1 ? 1 : 2 + unit;
        var version = alpha && !compression.resolutionIndependent() ? 4 : compression.headerVersion();
        var subsampling = chroma == ChromaFormat.YUV444 ? 2 : chroma == ChromaFormat.YUV420 ? 1 : 0;
        var header = new Vc3Header(headerSize, version, fieldCount, false, compression.cid() == 1260, alpha,
                losslessPart != null, false, height / units, width, depth, interlaced, compression, subsampling, 0,
                compression.chroma444() && rgb ? 1 : 0, 0, 0, heightInMb, offsets);
        header.write(out, offset);
        var payload = offset + headerSize;
        for (var row = 0; row < heightInMb; row++) {
            System.arraycopy(rows[row], 0, out, payload + offsets[row], rows[row].length);
        }
        ByteArrays.put32be(out, offset + size - 4, Vc3Header.SIGNATURE);
    }

    /**
     * Finds the finest scale factor whose coding unit fits the budget, or the coarsest factor
     * when none fits.
     *
     * <p>The search measures the factor of the previous frame first, then the factor that keeps
     * the measured size per scale factor constant (coded sizes fall roughly in inverse proportion
     * to the factor), and brackets the answer from there, widening by doubling steps when the
     * estimate is off, before bisecting the bracket.
     *
     * @param sizes the measured sizes
     * @param start the factor to measure first
     * @param maxScale the coarsest factor
     * @param budget the payload size in bits
     * @return the finest fitting factor
     */
    private static int search(Sizes sizes, int start, int maxScale, long budget) {
        var startTotal = sizes.total(start);
        int failing;
        int fitting;
        if (startTotal <= budget) {
            fitting = start;
            failing = 0;
            if (start > 1) {
                var guess = (int) Math.clamp(start * startTotal / budget, 1L, start - 1L);
                if (sizes.total(guess) <= budget) {
                    fitting = guess;
                    var step = 1;
                    while (fitting > 1) {
                        var candidate = Math.max(1, fitting - step);
                        if (sizes.total(candidate) > budget) {
                            failing = candidate;
                            break;
                        }
                        fitting = candidate;
                        step *= 2;
                    }
                } else {
                    failing = guess;
                }
            }
        } else {
            failing = start;
            fitting = -1;
            if (start < maxScale) {
                var guess = (int) Math.clamp((start * startTotal + budget - 1) / budget, start + 1L, maxScale);
                if (sizes.total(guess) <= budget) {
                    fitting = guess;
                } else {
                    failing = guess;
                    var step = 1;
                    while (failing < maxScale) {
                        var candidate = Math.min(maxScale, failing + step);
                        if (sizes.total(candidate) <= budget) {
                            fitting = candidate;
                            break;
                        }
                        failing = candidate;
                        step *= 2;
                    }
                }
            }
            if (fitting < 0) {
                return maxScale;
            }
        }
        while (fitting - failing > 1) {
            var middle = (failing + fitting) >>> 1;
            if (sizes.total(middle) <= budget) {
                fitting = middle;
            } else {
                failing = middle;
            }
        }
        return fitting;
    }

    /**
     * Measures the macroblocks of one coding unit at scale factors on demand, remembering every
     * measurement of the frame.
     */
    private final class Sizes {
        /**
         * The video coefficient store.
         */
        private final Vc3Coefficients store;

        /**
         * The alpha coefficient store, or {@code null}.
         */
        private final Vc3Coefficients alpha;

        /**
         * The size of every lossless alpha macroblock, or {@code null}.
         */
        private final int[][] lossless;

        /**
         * The band count.
         */
        private final int bands;

        /**
         * The size of every macroblock at each measured scale factor.
         */
        private final java.util.Map<Integer, int[][]> bits = new java.util.HashMap<>();

        /**
         * The payload size at each measured scale factor.
         */
        private final java.util.Map<Integer, Long> totals = new java.util.HashMap<>();

        /**
         * Constructs the measurements of a coding unit.
         *
         * @param store the video coefficient store
         * @param alpha the alpha coefficient store, or {@code null}
         * @param lossless the size of every lossless alpha macroblock, or {@code null}
         * @param bands the band count
         */
        Sizes(Vc3Coefficients store, Vc3Coefficients alpha, int[][] lossless, int bands) {
            this.store = store;
            this.alpha = alpha;
            this.lossless = lossless;
            this.bands = bands;
        }

        /**
         * Returns the payload size at a scale factor, measuring it on first use.
         *
         * @param scale the scale factor
         * @return the payload size in bits, each scan line padded to 32 bits
         */
        long total(int scale) {
            var known = totals.get(scale);
            if (known != null) {
                return known;
            }
            var sizes = new int[heightInMb][widthInMb];
            var total = measure(store, alpha, lossless, scale, sizes, bands);
            bits.put(scale, sizes);
            totals.put(scale, total);
            return total;
        }

        /**
         * Records the sizes of every macroblock at a scale factor that were measured elsewhere.
         *
         * @param scale the scale factor
         * @param sizes the size of every macroblock in bits, with its alpha macroblock
         */
        void accept(int scale, int[][] sizes) {
            var total = 0L;
            for (var row : sizes) {
                total += padded(Arrays.stream(row).asLongStream().sum());
            }
            bits.put(scale, sizes);
            totals.put(scale, total);
        }

        /**
         * Returns the size of every macroblock at a scale factor, measuring it on first use.
         *
         * @param scale the scale factor
         * @return the sizes in bits, shared
         */
        int[][] bits(int scale) {
            total(scale);
            return bits.get(scale);
        }
    }

    /**
     * Measures every macroblock at one scale factor and returns the payload size.
     *
     * @param store the coefficient store
     * @param alpha the alpha coefficient store, or {@code null}
     * @param lossless the size of every lossless alpha macroblock, or {@code null}
     * @param scale the scale factor
     * @param bits receives the size of every macroblock in bits, with its alpha macroblock
     * @param bands the band count
     * @return the payload size in bits, each scan line padded to 32 bits
     */
    private long measure(Vc3Coefficients store, Vc3Coefficients alpha, int[][] lossless, int scale, int[][] bits,
                         int bands) {
        run(bands, (encoder, row) -> encoder.measure(store, alpha, lossless == null ? null : lossless[row], row, scale,
                bits[row]));
        var total = 0L;
        for (var row : bits) {
            total += padded(Arrays.stream(row).asLongStream().sum());
        }
        return total;
    }

    /**
     * Gives the next finer scale factor to as many macroblocks as the budget allows, in raster
     * order.
     *
     * @param bits the size of every macroblock at the coarse factor
     * @param finer the size of every macroblock at the finer factor
     * @param scales the factor of every macroblock, updated
     * @param budget the payload size in bits
     */
    private void refine(int[][] bits, int[][] finer, int[][] scales, long budget) {
        var rowBits = new long[heightInMb];
        var total = 0L;
        for (var row = 0; row < heightInMb; row++) {
            for (var mb = 0; mb < widthInMb; mb++) {
                rowBits[row] += bits[row][mb];
            }
            total += padded(rowBits[row]);
        }
        for (var row = 0; row < heightInMb; row++) {
            for (var mb = 0; mb < widthInMb; mb++) {
                var grown = rowBits[row] + finer[row][mb] - bits[row][mb];
                var next = total - padded(rowBits[row]) + padded(grown);
                if (next <= budget) {
                    rowBits[row] = grown;
                    total = next;
                    scales[row][mb]--;
                }
            }
        }
    }

    /**
     * Returns a scan line size padded to a multiple of 32 bits.
     *
     * @param bits the size in bits
     * @return the padded size
     */
    private static long padded(long bits) {
        return (bits + 31) & ~31L;
    }

    /**
     * Runs a computation on every scan line of a coding unit, in parallel bands.
     *
     * @param bands the band count
     * @param task the computation
     */
    private void run(int bands, RowTask task) {
        var encoders = rowEncoders;
        var rows = heightInMb;
        ParallelBands.run(bands, band -> {
            var encoder = encoders[band];
            if (encoder == null) {
                encoder = new Vc3RowEncoder();
                encoders[band] = encoder;
            }
            for (var row = ParallelBands.start(band, bands, rows); row < ParallelBands.start(band + 1, bands, rows); row++) {
                task.run(encoder, row);
            }
        });
    }

    /**
     * Computes something for one scan line with the calling band's row encoder.
     */
    @FunctionalInterface
    private interface RowTask {
        /**
         * Runs the computation.
         *
         * @param encoder the band's row encoder
         * @param row the scan line
         */
        void run(Vc3RowEncoder encoder, int row);
    }
}
