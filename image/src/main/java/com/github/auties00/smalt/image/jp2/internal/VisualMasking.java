package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Holds the visual masking a {@code VMS} segment declares (ISO/IEC 15444-2 A.3.2) and undoes it: the point-wise
 * extended non-linearity of annex E, which the decoder inverts between dequantization and the inverse wavelet
 * transformation (E.3).
 *
 * <p>In every sub-band of a resolution level of at least {@link #minimumLevel()}, in raster order, a dequantized
 * coefficient {@code z} becomes {@code x = sign(z) [|z / g| (1 + a S / |phi|)]^(1 / alpha) g} (equation E-4), where
 * {@code g} is the sub-band gain, {@code a = (1000 / 2^(R_I - 1))^beta} for a component of {@code R_I} bits, and
 * {@code S} sums {@code |xhat / g|^beta} over the {@code |phi|} coefficients of the causal neighbourhood: the
 * {@code window} rows above, {@code window} columns either side, and the {@code window} coefficients to the left,
 * kept inside the sub-band and, when {@link #respectBlocks()} holds, inside the code-block. {@code xhat} is the
 * reconstruction of equation E-6 from the quantization index {@code floor(|z| / Delta_b)} with all but its
 * {@code bits_retained} most significant of {@code M_b} bits cleared (equation E-5), which the encoder can reproduce
 * from any truncation of the codestream that keeps those bits.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation applies the masking to the components of irreversible wavelets only: annex E defines
 * it for the quantized coefficients of irreversible filters (E.6), and a reversible component, whose integer
 * coefficients the reversible transformation reconstructs exactly, decodes as if no {@code VMS} segment applied. A
 * coefficient whose neighbourhood is empty, the first of its sub-band or code-block, takes {@code S / |phi| = 0}.
 * Each quantization index uses the step size and bit-plane count of its own precinct, which precinct-dependent
 * quantization may set apart from the sub-band's.
 *
 * @param component the component the segment applies to, or {@link #ALL_COMPONENTS}
 * @param minimumLevel the lowest resolution level masked, {@code minlevel}, from 0 to 32
 * @param respectBlocks whether the neighbourhoods stop at code-block boundaries ({@code respect_block_boundaries})
 * @param window the half width of the neighbourhood, {@code win_width}
 * @param retainedBits the number of most significant bits of a quantization index the neighbourhood sees,
 * {@code bits_retained}
 * @param alpha the exponent {@code alpha} of the transducer function, {@code Avms / 128}
 * @param beta the exponent {@code beta} of the neighbourhood masking, {@code Bvms / 128}
 */
record VisualMasking(int component, int minimumLevel, boolean respectBlocks, int window, int retainedBits,
                     double alpha, double beta) {
    /**
     * The {@code Cvms} value of a segment applying to every component.
     */
    static final int ALL_COMPONENTS = 65535;

    /**
     * The largest component index a {@code VMS} segment may name (Table A.23).
     */
    private static final int MAX_COMPONENT = 16383;

    /**
     * The largest {@code minlevel} (Table A.24).
     */
    private static final int MAX_LEVEL = 32;

    /**
     * The approximate work of one coefficient, weighed against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long SAMPLE_WORK = 96;

    /**
     * Parses the fields of a {@code VMS} segment that follow its length.
     *
     * @param data the codestream
     * @param position the index of the {@code Cvms} field
     * @param end the end of the segment
     * @return the visual masking
     * @throws MediaException.Malformed if the segment is not 7 bytes long, names a reserved component, has a
     * {@code minlevel} above 32 or an {@code alpha} of 0
     */
    static VisualMasking parse(byte[] data, int position, int end) {
        if (end - position != 7) {
            throw new MediaException.Malformed("invalid JPEG 2000 VMS segment length " + (end - position + 2));
        }
        var component = ByteArrays.u16be(data, position);
        if (component > MAX_COMPONENT && component != ALL_COMPONENTS) {
            throw new MediaException.Malformed("invalid JPEG 2000 VMS component " + component);
        }
        var style = data[position + 2] & 0xFF;
        var level = style & 0x7F;
        if (level > MAX_LEVEL) {
            throw new MediaException.Malformed("invalid JPEG 2000 VMS minimum resolution level " + level);
        }
        var numerator = data[position + 5] & 0xFF;
        if (numerator == 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 VMS exponent alpha 0");
        }
        return new VisualMasking(component, level, (style & 0x80) != 0, data[position + 3] & 0xFF,
                data[position + 4] & 0xFF, numerator / 128.0, (data[position + 6] & 0xFF) / 128.0);
    }

    /**
     * Undoes the masking of the decoded sub-bands of a tile-component in place, the sub-bands in parallel.
     *
     * @param data the dequantized coefficients, row-major, in the layout of the sub-bands of B.5
     * @param stride the row stride of {@code data}
     * @param tileComponent the tile-component
     * @param precision the bit depth {@code R_I} of the component's samples
     */
    void apply(float[] data, int stride, TileComponent tileComponent, int precision) {
        var bands = new ArrayList<Band>();
        var work = 0L;
        for (var r = minimumLevel; r < tileComponent.decodedResolutions; r++) {
            for (var band : tileComponent.resolutions[r].bands()) {
                if (!band.empty()) {
                    bands.add(band);
                    work += (band.x1() - band.x0()) * (band.y1() - band.y0()) * SAMPLE_WORK;
                }
            }
        }
        if (bands.isEmpty()) {
            return;
        }
        var normalization = StrictMath.pow(1000 / Math.scalb(1.0, precision - 1), beta);
        var count = bands.size();
        var parallel = ParallelBands.bands(count, work);
        ParallelBands.run(parallel, index -> {
            var end = ParallelBands.start(index + 1, parallel, count);
            for (var b = ParallelBands.start(index, parallel, count); b < end; b++) {
                unmask(data, stride, bands.get(b), normalization);
            }
        });
    }

    /**
     * Undoes the masking of one sub-band, in raster order.
     *
     * <p>The neighbourhood sums come from a running sum of each column over the rows of the window above and a prefix
     * sum across the row, so each coefficient costs the same whatever the window.
     *
     * @param data the coefficients
     * @param stride the row stride
     * @param band the sub-band
     * @param normalization the constant {@code a}
     */
    private void unmask(float[] data, int stride, Band band, double normalization) {
        var width = (int) (band.x1() - band.x0());
        var height = (int) (band.y1() - band.y0());
        var gain = Math.scalb(1.0, band.gain());
        Precinct first = null;
        var uniform = true;
        for (var precinct : band.precincts()) {
            if (precinct == null || precinct.codeBlocks.length == 0) {
                continue;
            }
            if (first == null) {
                first = precinct;
            } else if (precinct.stepSize != first.stepSize || precinct.bitPlanes != first.bitPlanes) {
                uniform = false;
            }
        }
        var uniformStep = first == null ? band.stepSize() : first.stepSize;
        var uniformPlanes = first == null ? band.bitPlanes() : first.bitPlanes;
        float[] steps = uniform ? null : new float[width * height];
        int[] planes = uniform ? null : new int[width * height];
        int[] left = respectBlocks ? new int[width] : null;
        int[] right = respectBlocks ? new int[width] : null;
        int[] top = respectBlocks ? new int[height] : null;
        if (!uniform || respectBlocks) {
            for (var precinct : band.precincts()) {
                if (precinct == null) {
                    continue;
                }
                for (var block : precinct.codeBlocks) {
                    var bx0 = (int) (block.x0 - band.x0());
                    var by0 = (int) (block.y0 - band.y0());
                    var bx1 = (int) (block.x1 - band.x0());
                    var by1 = (int) (block.y1 - band.y0());
                    if (respectBlocks) {
                        for (var x = bx0; x < bx1; x++) {
                            left[x] = bx0;
                            right[x] = bx1;
                        }
                        for (var y = by0; y < by1; y++) {
                            top[y] = by0;
                        }
                    }
                    if (!uniform) {
                        for (var y = by0; y < by1; y++) {
                            for (var x = bx0; x < bx1; x++) {
                                steps[y * width + x] = precinct.stepSize;
                                planes[y * width + x] = precinct.bitPlanes;
                            }
                        }
                    }
                }
            }
        }
        var masked = new float[width * height];
        var columns = new double[width];
        var above = new double[width + 1];
        var row = new double[width + 1];
        var transducer = 1 / alpha;
        var neighbour = beta / alpha;
        var base = band.offsetY() * stride + band.offsetX();
        var blockTop = -1;
        for (var y = 0; y < height; y++) {
            var rowTop = top == null ? 0 : top[y];
            if (rowTop != blockTop) {
                Arrays.fill(columns, 0);
                blockTop = rowTop;
            } else {
                var added = (y - 1) * width;
                var removed = y - 1 - window;
                for (var x = 0; x < width; x++) {
                    columns[x] += masked[added + x];
                }
                if (removed >= rowTop) {
                    for (var x = 0; x < width; x++) {
                        columns[x] -= masked[removed * width + x];
                    }
                }
            }
            var rowsAbove = Math.min(window, y - rowTop);
            for (var x = 0; x < width; x++) {
                above[x + 1] = above[x] + columns[x];
            }
            var offset = base + y * stride;
            for (var x = 0; x < width; x++) {
                var low = Math.max(left == null ? 0 : left[x], x - window);
                var high = Math.min((right == null ? width : right[x]) - 1, x + window);
                var size = (long) rowsAbove * (high - low + 1) + (x - low);
                var sum = above[high + 1] - above[low] + row[x] - row[low];
                var factor = size == 0 ? 1 : 1 + normalization * sum / size;
                var value = data[offset + x];
                var magnitude = Math.abs((double) value);
                var step = steps == null ? uniformStep : steps[y * width + x];
                var bits = planes == null ? uniformPlanes : planes[y * width + x];
                var index = (long) Math.floor(magnitude / step);
                var dropped = bits - retainedBits;
                if (dropped > 0) {
                    index = dropped >= 63 ? 0 : index >> dropped << dropped;
                }
                var contribution = (float) StrictMath.pow(index * step / gain * factor, neighbour);
                masked[y * width + x] = contribution;
                row[x + 1] = row[x] + contribution;
                if (value != 0) {
                    var restored = StrictMath.pow(magnitude / gain * factor, transducer) * gain;
                    data[offset + x] = (float) (value < 0 ? -restored : restored);
                }
            }
        }
    }
}
