/* Ports libjpeg-turbo (jccoefct.c, jcdctmgr.c). Copyright (C) 1991-1997, Thomas G. Lane; Copyright (C)
   2009-2024, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Transforms and quantizes every block of a DCT frame into coefficient planes, the input of
 * {@link CoefficientWriter}.
 *
 * <p>Bands of iMCU rows are transformed on several threads; the coefficients do not depend on the
 * number of threads. The dummy blocks that pad interleaved MCUs are left to the writer, which codes them as
 * libjpeg does.
 */
final class DctFrameEncoder {
    /**
     * The approximate number of operations to transform one block, which weighs a frame's work against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long BLOCK_WORK = 512;

    /**
     * The forward DCT of one 8-bit block.
     */
    private static final ScalarFdctKernel BLOCK_FDCT = new ScalarFdctKernel();

    /**
     * Prevents instantiation.
     */
    private DctFrameEncoder() {
        throw new AssertionError();
    }

    /**
     * Transforms and quantizes a frame.
     *
     * @param layout the frame, not lossless
     * @param rows the full-resolution rows, or {@code null} when {@code planes} is given
     * @param planes the planes in the frame's subsampling, or {@code null}
     * @param quantTables the natural-order quantization table of each slot, {@code null} when unused
     * @return the coefficient plane of each component
     */
    static CoefficientPlane[] transform(FrameLayout layout, SampleRows rows, ComponentBands.Planes planes,
                                        int[][] quantTables) {
        var count = layout.count;
        var planesOut = new CoefficientPlane[count];
        long blocks = 0;
        for (var c = 0; c < count; c++) {
            planesOut[c] = new CoefficientPlane(layout.unitsWide[c], layout.unitsHigh[c]);
            blocks += (long) layout.unitsWide[c] * layout.unitsHigh[c];
        }
        var narrow = layout.precision == 8;
        for (var c = 0; c < count && narrow; c++) {
            for (var value : quantTables[layout.quantSlots[c]]) {
                if (value > 255) {
                    narrow = false;
                    break;
                }
            }
        }
        var bytePath = narrow;
        var batched = bytePath && Simd.ENABLED;
        var minimumStride = new int[count];
        for (var c = 0; c < count; c++) {
            minimumStride[c] = batched
                    ? JpegFrame.ceilDiv(layout.unitsWide[c], BatchFdctKernel.LANES) * BatchFdctKernel.LANES * 8
                    : layout.bandWidth(c);
        }
        var divisors = new QuantDivisors[quantTables.length];
        if (bytePath) {
            for (var slot = 0; slot < quantTables.length; slot++) {
                if (quantTables[slot] != null) {
                    divisors[slot] = new QuantDivisors(quantTables[slot]);
                }
            }
        }
        var bandCount = ParallelBands.bands(layout.imcuRows, blocks * BLOCK_WORK);
        ParallelBands.run(bandCount, band -> {
            var worker = new Worker(layout, rows, planes, minimumStride, quantTables, divisors, bytePath, batched,
                    planesOut);
            var first = ParallelBands.start(band, bandCount, layout.imcuRows);
            var last = ParallelBands.start(band + 1, bandCount, layout.imcuRows);
            for (var imcu = first; imcu < last; imcu++) {
                worker.transform(imcu);
            }
        });
        return planesOut;
    }

    /**
     * Transforms the iMCU rows of one band: owns the band's samples and scratch arrays.
     */
    private static final class Worker {
        /**
         * The frame.
         */
        private final FrameLayout layout;

        /**
         * The samples of the current iMCU row.
         */
        private final ComponentBands bands;

        /**
         * The quantization tables by slot.
         */
        private final int[][] quantTables;

        /**
         * The quantization divisors by slot, for the byte kernels.
         */
        private final QuantDivisors[] divisors;

        /**
         * Whether the byte kernels transform the blocks.
         */
        private final boolean bytePath;

        /**
         * The batched kernel, or {@code null}.
         */
        private final BatchFdctKernel fdct;

        /**
         * The output planes.
         */
        private final CoefficientPlane[] planes;

        /**
         * The 8-bit copy of each band, or {@code null} outside the byte path.
         */
        private final byte[][] narrow;

        /**
         * The interleaved coefficients of one batch.
         */
        private final int[] batch;

        /**
         * The nonzero masks of one batch.
         */
        private final long[] masks;

        /**
         * The scratch array of the batched kernel.
         */
        private final byte[] scratch;

        /**
         * The coefficients of one block.
         */
        private final int[] block;

        /**
         * The scratch array of {@link WideFdct}.
         */
        private final long[] wide;

        /**
         * Allocates the buffers of one band.
         *
         * @param layout the frame
         * @param rows the full-resolution rows, or {@code null}
         * @param planes the planes, or {@code null}
         * @param minimumStride the smallest stride of each band
         * @param quantTables the quantization tables by slot
         * @param divisors the divisors by slot
         * @param bytePath whether the byte kernels transform the blocks
         * @param batched whether the batched kernel does
         * @param out the output planes
         */
        Worker(FrameLayout layout, SampleRows rows, ComponentBands.Planes planes, int[] minimumStride,
               int[][] quantTables, QuantDivisors[] divisors, boolean bytePath, boolean batched,
               CoefficientPlane[] out) {
            this.layout = layout;
            this.bands = new ComponentBands(layout, rows, planes, minimumStride);
            this.quantTables = quantTables;
            this.divisors = divisors;
            this.bytePath = bytePath;
            this.fdct = batched ? VectorKernels.batchFdct() : null;
            this.planes = out;
            if (bytePath) {
                narrow = new byte[layout.count][];
                for (var c = 0; c < layout.count; c++) {
                    narrow[c] = new byte[bands.bands[c].length];
                }
            } else {
                narrow = null;
            }
            this.batch = batched ? new int[BatchFdctKernel.BATCH_LENGTH] : null;
            this.masks = batched ? new long[BatchFdctKernel.LANES] : null;
            this.scratch = batched ? new byte[BatchFdctKernel.SCRATCH_LENGTH] : null;
            this.block = new int[64];
            this.wide = new long[64];
        }

        /**
         * Transforms one iMCU row.
         *
         * @param imcu the iMCU row
         */
        void transform(int imcu) {
            bands.fill(imcu);
            for (var c = 0; c < layout.count; c++) {
                var plane = planes[c];
                var stride = bands.stride[c];
                var samples = bands.bands[c];
                var slot = layout.quantSlots[c];
                var wideBlocks = layout.unitsWide[c];
                byte[] bytes = null;
                if (bytePath) {
                    bytes = narrow[c];
                    for (var i = 0; i < samples.length; i++) {
                        bytes[i] = (byte) samples[i];
                    }
                }
                for (var by = 0; by < layout.v[c]; by++) {
                    var row = imcu * layout.v[c] + by;
                    if (row >= layout.unitsHigh[c]) {
                        break;
                    }
                    var rowOffset = by * 8 * stride;
                    if (fdct != null) {
                        for (var bx = 0; bx < wideBlocks; bx += BatchFdctKernel.LANES) {
                            fdct.transform(bytes, rowOffset + bx * 8, stride, divisors[slot], batch, masks, scratch);
                            var lanes = Math.min(BatchFdctKernel.LANES, wideBlocks - bx);
                            for (var lane = 0; lane < lanes; lane++) {
                                var base = plane.offset(bx + lane, row);
                                for (var k = 0; k < 64; k++) {
                                    plane.data[base + k] = (short) batch[k * BatchFdctKernel.LANES + lane];
                                }
                            }
                        }
                    } else if (bytePath) {
                        for (var bx = 0; bx < wideBlocks; bx++) {
                            BLOCK_FDCT.transform(bytes, rowOffset + bx * 8, stride, divisors[slot], block);
                            var base = plane.offset(bx, row);
                            for (var k = 0; k < 64; k++) {
                                plane.data[base + k] = (short) block[k];
                            }
                        }
                    } else {
                        for (var bx = 0; bx < wideBlocks; bx++) {
                            WideFdct.transform(samples, rowOffset + bx * 8, stride, layout.precision,
                                    quantTables[slot], plane.data, plane.offset(bx, row), wide);
                        }
                    }
                }
            }
        }
    }
}
