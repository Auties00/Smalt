/* Ports libFLAC 1.4 (src/libFLAC/stream_encoder.c, stream_encoder_framing.c). Copyright (C) 2000-2009 Josh Coalson,
   (C) 2011-2025 Xiph.Org Foundation; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.internal.header.Crc16;

import java.util.Arrays;

/**
 * Encodes blocks of integer samples into FLAC frames: chooses the stereo decorrelation, the
 * subframe type and predictor of every channel, and the Rice partitioning of every residual.
 *
 * <p>An instance holds the workspaces for its configuration's largest block and is reused for
 * every frame of a stream. Instances are not thread-safe.
 *
 * @implNote This implementation follows libFLAC's subframe and partition search
 * ({@code process_subframes_}, {@code process_subframe_}, {@code find_best_partition_order_},
 * {@code set_partitioned_rice_}) and its frame and subframe writers. It keeps the windowed signal
 * and the predictor coefficients in double rather than float, and the complement windows of a
 * subdivide-Tukey apodization subtract every autocorrelation lag, where libFLAC leaves the highest
 * lag of the section's own autocorrelation in place.
 */
final class FlacFrameEncoder {
    /**
     * The largest fixed predictor order.
     */
    static final int MAX_FIXED_ORDER = 4;

    /**
     * The largest linear predictor order.
     */
    static final int MAX_LPC_ORDER = 32;

    /**
     * The smallest quantized coefficient precision.
     */
    static final int MIN_QLP_PRECISION = 5;

    /**
     * The largest quantized coefficient precision.
     */
    static final int MAX_QLP_PRECISION = 15;

    /**
     * The largest Rice partition order.
     */
    static final int MAX_PARTITION_ORDER = 15;

    /**
     * The subframe type of a constant subframe.
     */
    private static final int CONSTANT = 0;

    /**
     * The subframe type of a verbatim subframe.
     */
    private static final int VERBATIM = 1;

    /**
     * The subframe type of a fixed-predictor subframe.
     */
    private static final int FIXED = 2;

    /**
     * The subframe type of a linear-predictor subframe.
     */
    private static final int LPC = 3;

    /**
     * The bits of a subframe header without its wasted-bits count: padding, type and flag.
     */
    private static final int SUBFRAME_HEADER_BITS = 8;

    /**
     * The bits coding the residual method and partition order.
     */
    private static final int RESIDUAL_HEADER_BITS = 6;

    /**
     * {@code ln(2)}.
     */
    private static final double LN2 = Math.log(2);

    /**
     * The magnitude a residual sample must stay below.
     */
    private static final long RESIDUAL_LIMIT = 1L << 31;

    /**
     * The kernel performing the data-parallel stages.
     */
    private final FlacEncoderKernel kernel;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The sample size of the stream.
     */
    private final int bitsPerSample;

    /**
     * The sampling rate of the stream.
     */
    private final int sampleRate;

    /**
     * Whether stereo frames try mid/side decorrelation.
     */
    private final boolean midSide;

    /**
     * Whether stereo frames reuse the previous channel assignment between periodic full searches.
     */
    private final boolean looseMidSide;

    /**
     * The number of frames between two full stereo searches in loose mode.
     */
    private final int looseFrames;

    /**
     * The largest linear predictor order, zero to disable linear prediction.
     */
    private final int maxLpcOrder;

    /**
     * The quantized coefficient precision.
     */
    private final int qlpPrecision;

    /**
     * Whether every coefficient precision is tried.
     */
    private final boolean precisionSearch;

    /**
     * Whether every predictor order is tried rather than the estimated best one.
     */
    private final boolean exhaustive;

    /**
     * Whether partitions may be coded verbatim with an escape code.
     */
    private final boolean escapeCoding;

    /**
     * The smallest Rice partition order tried.
     */
    private final int minPartitionOrder;

    /**
     * The largest Rice partition order tried.
     */
    private final int maxPartitionOrder;

    /**
     * The escape parameter that bounds the Rice parameters: 15 for streams up to 16 bits, 31 above.
     */
    private final int riceLimit;

    /**
     * The analysis windows.
     */
    private final FlacWindow[] windows;

    /**
     * The signal slots: one per channel, then mid and side.
     */
    private final Slot[] slots;

    /**
     * The 33-bit side channel of 32-bit stereo.
     */
    private long[] wideSide;

    /**
     * The windowed signal, zero padded for the autocorrelation kernel.
     */
    private double[] windowed;

    /**
     * The autocorrelation of the current analysis.
     */
    private final double[] autoc;

    /**
     * The autocorrelation of the whole block, for the complement windows of subdivide-Tukey.
     */
    private final double[] rootAutoc;

    /**
     * The predictor coefficients of every order of the current analysis.
     */
    private final double[][] lpCoefficients;

    /**
     * The working coefficients of the Levinson-Durbin recursion.
     */
    private final double[] levinsonScratch;

    /**
     * The prediction error of every order of the current analysis.
     */
    private final double[] lpcError;

    /**
     * The quantized coefficients of the candidate being evaluated.
     */
    private final int[] quantized;

    /**
     * The fixed-predictor error sums.
     */
    private final long[] fixedErrors;

    /**
     * The residual magnitude sums of every partition of every order tried.
     */
    private final long[] partitionSums;

    /**
     * The escape widths of every partition of every order tried.
     */
    private final int[] partitionRawBits;

    /**
     * The Rice parameters of the two partitionings compared.
     */
    private final int[][] parameterScratch;

    /**
     * The escape widths of the two partitionings compared.
     */
    private final int[][] rawBitsScratch;

    /**
     * The frame being written.
     */
    private final FlacBitWriter writer;

    /**
     * The channel assignment of the previous frame.
     */
    private int lastAssignment;

    /**
     * The frames since the last full stereo search in loose mode.
     */
    private int looseCount;

    /**
     * Creates a frame encoder for the format and compression settings of encoder options.
     *
     * @param options the format and compression settings; the frame encoder reads the channel
     * count, sample size and rate, block size, stereo mode, predictor, precision and partition
     * settings, and the apodizations
     * @param kernel the kernel performing the data-parallel stages
     */
    FlacFrameEncoder(FlacEncoderOptions options, FlacEncoderKernel kernel) {
        var channels = options.layout().channels();
        var bitsPerSample = options.bitsPerSample();
        var sampleRate = options.sampleRate();
        var blockSize = options.blockSize();
        var stereo = options.stereoMode();
        var maxPartitionOrder = options.maxPartitionOrder();
        this.kernel = kernel;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.sampleRate = sampleRate;
        this.midSide = stereo != FlacStereoMode.INDEPENDENT && channels == 2;
        this.looseMidSide = this.midSide && stereo == FlacStereoMode.LOOSE_MID_SIDE;
        this.looseFrames = Math.max(1, (int) (sampleRate * 0.4 / blockSize + 0.5));
        this.maxLpcOrder = options.maxLpcOrder();
        this.qlpPrecision = options.resolvedQlpPrecision();
        this.precisionSearch = options.precisionSearch();
        this.exhaustive = options.exhaustiveModelSearch();
        this.escapeCoding = options.escapeCoding();
        this.maxPartitionOrder = maxPartitionOrder;
        this.minPartitionOrder = Math.min(options.minPartitionOrder(), maxPartitionOrder);
        this.riceLimit = bitsPerSample > 16 ? 31 : 15;
        this.windows = FlacWindow.expand(options.apodizations()).toArray(FlacWindow[]::new);
        this.slots = new Slot[channels + 2];
        var partitions = 1 << this.maxPartitionOrder;
        for (var i = 0; i < slots.length; i++) {
            slots[i] = new Slot(blockSize, partitions);
        }
        this.wideSide = new long[0];
        this.windowed = new double[blockSize + FlacEncoderKernel.AUTOCORRELATION_PADDING];
        this.autoc = new double[MAX_LPC_ORDER + 1 + FlacEncoderKernel.AUTOCORRELATION_PADDING];
        this.rootAutoc = new double[autoc.length];
        this.lpCoefficients = new double[MAX_LPC_ORDER][MAX_LPC_ORDER];
        this.lpcError = new double[MAX_LPC_ORDER];
        this.levinsonScratch = new double[MAX_LPC_ORDER];
        this.quantized = new int[MAX_LPC_ORDER];
        this.fixedErrors = new long[MAX_FIXED_ORDER + 1];
        this.partitionSums = new long[2 * partitions];
        this.partitionRawBits = new int[2 * partitions];
        this.parameterScratch = new int[2][partitions];
        this.rawBitsScratch = new int[2][partitions];
        this.writer = new FlacBitWriter();
    }

    /**
     * Returns the array of a channel that the next frame reads its samples from.
     *
     * <p>The caller fills the first {@code blockSize} elements before {@link #encode(int, long)};
     * the encoder modifies them.
     *
     * @param channel the channel index
     * @return the channel's sample array
     */
    int[] input(int channel) {
        return slots[channel].signal;
    }

    /**
     * Encodes one frame from the samples in the {@link #input(int)} arrays.
     *
     * @param blockSize the number of samples per channel, at most the configured block size
     * @param frameNumber the frame number written in the header
     * @return the frame, header to CRC-16
     */
    byte[] encode(int blockSize, long frameNumber) {
        var maxOrder = Math.min(partitionOrderOfBlock(blockSize), maxPartitionOrder);
        var minOrder = Math.min(minPartitionOrder, maxOrder);
        boolean doIndependent;
        boolean doMidSide;
        if (midSide) {
            if (looseMidSide && looseCount != 0) {
                doIndependent = lastAssignment == 0;
                doMidSide = !doIndependent;
            } else {
                doIndependent = true;
                doMidSide = true;
            }
        } else {
            doIndependent = true;
            doMidSide = false;
        }
        var mid = slots[channels];
        var side = slots[channels + 1];
        if (doMidSide) {
            prepareMidSide(blockSize, mid, side);
        }
        if (doIndependent) {
            for (var c = 0; c < channels; c++) {
                var slot = slots[c];
                slot.wide = null;
                slot.wasted = wastedBits(slot.signal, blockSize);
                slot.bps = bitsPerSample - slot.wasted;
            }
        }
        if (doIndependent) {
            for (var c = 0; c < channels; c++) {
                processSubframe(slots[c], blockSize, minOrder, maxOrder);
            }
        }
        if (doMidSide) {
            processSubframe(mid, blockSize, minOrder, maxOrder);
            processSubframe(side, blockSize, minOrder, maxOrder);
        }
        int assignment;
        if (doMidSide) {
            if (looseMidSide && looseCount != 0) {
                assignment = lastAssignment == 0 ? 0 : 3;
            } else {
                var left = slots[0].bestBits;
                var right = slots[1].bestBits;
                long[] bits = {left + right, left + side.bestBits, right + side.bestBits, mid.bestBits + side.bestBits};
                assignment = 0;
                for (var ca = looseMidSide ? 3 : 1; ca <= 3; ca++) {
                    if (bits[ca] < bits[assignment]) {
                        assignment = ca;
                    }
                }
            }
        } else {
            assignment = 0;
        }
        writer.reset();
        writeHeader(blockSize, frameNumber, assignment);
        switch (assignment) {
            case 0 -> {
                for (var c = 0; c < channels; c++) {
                    writeSubframe(slots[c], blockSize);
                }
            }
            case 1 -> {
                writeSubframe(slots[0], blockSize);
                writeSubframe(side, blockSize);
            }
            case 2 -> {
                writeSubframe(side, blockSize);
                writeSubframe(slots[1], blockSize);
            }
            default -> {
                writeSubframe(mid, blockSize);
                writeSubframe(side, blockSize);
            }
        }
        writer.alignToByte();
        writer.flush();
        var crc = Crc16.crc16(0, writer.buffer(), 0, writer.byteCount());
        writer.write(crc, 16);
        if (looseMidSide) {
            looseCount++;
            if (looseCount >= looseFrames) {
                looseCount = 0;
            }
        }
        lastAssignment = assignment;
        return writer.toByteArray();
    }

    /**
     * Computes the mid and side signals of a stereo block and their wasted bits.
     *
     * @param blockSize the number of samples
     * @param mid the mid slot
     * @param side the side slot
     */
    private void prepareMidSide(int blockSize, Slot mid, Slot side) {
        var left = slots[0].signal;
        var right = slots[1].signal;
        if (bitsPerSample < 32) {
            kernel.midSide(left, right, mid.signal, side.signal, blockSize);
            side.wide = null;
            side.wasted = wastedBits(side.signal, blockSize);
        } else {
            if (wideSide.length < blockSize) {
                wideSide = new long[blockSize];
            }
            var wide = wideSide;
            var midSignal = mid.signal;
            var x = 0L;
            for (var i = 0; i < blockSize; i++) {
                long l = left[i];
                long r = right[i];
                wide[i] = l - r;
                midSignal[i] = (int) ((l + r) >> 1);
                x |= wide[i];
            }
            // libFLAC treats an all-zero 33-bit side channel as wasting one bit, so it fits 32 bits
            var shift = x == 0 ? 1 : Long.numberOfTrailingZeros(x);
            if (shift > 0) {
                var sideSignal = side.signal;
                for (var i = 0; i < blockSize; i++) {
                    sideSignal[i] = (int) (wide[i] >> shift);
                }
                side.wide = null;
            } else {
                side.wide = wide;
            }
            side.wasted = shift;
        }
        side.wasted = Math.min(side.wasted, bitsPerSample);
        side.bps = bitsPerSample - side.wasted + 1;
        mid.wide = null;
        mid.wasted = wastedBits(mid.signal, blockSize);
        mid.bps = bitsPerSample - mid.wasted;
    }

    /**
     * Finds the wasted bits of a signal, the trailing zero bits every sample shares, and shifts
     * them out.
     *
     * @param signal the samples, shifted in place
     * @param blockSize the number of samples
     * @return the number of wasted bits, zero when every sample is zero
     */
    private int wastedBits(int[] signal, int blockSize) {
        var x = 0;
        for (var i = 0; i < blockSize && (x & 1) == 0; i++) {
            x |= signal[i];
        }
        if (x == 0) {
            return 0;
        }
        var shift = Integer.numberOfTrailingZeros(x);
        if (shift > 0) {
            for (var i = 0; i < blockSize; i++) {
                signal[i] >>= shift;
            }
        }
        return Math.min(shift, bitsPerSample);
    }

    /**
     * Returns the largest Rice partition order a block size allows: the number of times it
     * divides by two, at most {@value #MAX_PARTITION_ORDER}.
     *
     * @param blockSize the block size
     * @return the partition order
     */
    private static int partitionOrderOfBlock(int blockSize) {
        return Math.min(MAX_PARTITION_ORDER, Integer.numberOfTrailingZeros(blockSize));
    }

    /**
     * Chooses the smallest subframe for one signal, leaving it in the slot's best candidate.
     *
     * @param slot the signal
     * @param blockSize the number of samples
     * @param minOrder the smallest Rice partition order
     * @param maxOrder the largest Rice partition order
     */
    private void processSubframe(Slot slot, int blockSize, int minOrder, int maxOrder) {
        var bps = slot.bps;
        slot.best = 0;
        var verbatim = slot.candidates[0];
        verbatim.type = VERBATIM;
        slot.bestBits = SUBFRAME_HEADER_BITS + slot.wasted + (long) blockSize * bps;
        if (blockSize <= MAX_FIXED_ORDER) {
            return;
        }
        if (slot.wide != null) {
            processWideSubframe(slot, blockSize, minOrder, maxOrder);
            return;
        }
        var signal = slot.signal;
        kernel.fixedErrors(signal, blockSize, fixedErrors, bps);
        var guess = guessFixedOrder(fixedErrors);
        if (fixedErrors[1] == 0 && isConstant(signal, blockSize)) {
            var bits = SUBFRAME_HEADER_BITS + slot.wasted + bps;
            if (bits < slot.bestBits) {
                var candidate = slot.candidates[1];
                candidate.type = CONSTANT;
                candidate.constant = signal[0];
                slot.best = 1;
                slot.bestBits = bits;
            }
            return;
        }
        var fixedSamples = blockSize - MAX_FIXED_ORDER;
        var minFixed = exhaustive ? 0 : guess;
        var maxFixed = exhaustive ? MAX_FIXED_ORDER : guess;
        for (var order = minFixed; order <= maxFixed; order++) {
            if (fixedResidualBits(fixedErrors[order], fixedSamples) >= bps) {
                continue;
            }
            var candidate = slot.candidates[1 - slot.best];
            if (bps + order <= 32) {
                kernel.fixedResidual(signal, blockSize, order, candidate.residual);
            } else if (!wideFixedResidual(signal, blockSize, order, candidate.residual)) {
                continue;
            }
            var bits = SUBFRAME_HEADER_BITS + slot.wasted + (long) order * bps
                    + partition(candidate, blockSize, order, minOrder, maxOrder);
            candidate.type = FIXED;
            candidate.order = order;
            if (bits < slot.bestBits) {
                slot.best = 1 - slot.best;
                slot.bestBits = bits;
            }
        }
        if (maxLpcOrder > 0) {
            var lpcMax = Math.min(maxLpcOrder, blockSize - 1);
            if (lpcMax > 0) {
                for (var window : windows) {
                    analyzeWindow(slot, window, blockSize, lpcMax, minOrder, maxOrder);
                }
            }
        }
    }

    /**
     * Runs the linear-prediction analyses of one window: the whole block, and for a
     * subdivide-Tukey window every section and section complement.
     *
     * @param slot the signal
     * @param window the analysis window and its sections
     * @param blockSize the number of samples
     * @param lpcMax the largest predictor order for this block
     * @param minOrder the smallest Rice partition order
     * @param maxOrder the largest Rice partition order
     */
    private void analyzeWindow(Slot slot, FlacWindow window, int blockSize, int lpcMax, int minOrder, int maxOrder) {
        var coefficients = window.coefficients(blockSize);
        var lags = lpcMax + 1;
        applyWindow(slot, coefficients, 0, 0, 0, blockSize);
        padWindowed(blockSize);
        kernel.autocorrelation(windowed, blockSize, lags, autoc);
        var parts = window.subdivisions();
        if (parts > 1) {
            System.arraycopy(autoc, 0, rootAutoc, 0, lags);
        }
        searchPredictors(slot, blockSize, lpcMax, minOrder, maxOrder);
        for (var depth = 2; depth <= parts; depth++) {
            var length = blockSize / depth;
            if (length <= MAX_LPC_ORDER) {
                break;
            }
            var half = length / 2;
            for (var section = 0; section < depth; section++) {
                // libFLAC's FLAC__lpc_window_data_partial: the section, squeezed between the two
                // halves of the whole-block window
                var shift = section * blockSize / depth;
                var head = Math.min(half, blockSize - half - shift);
                applyWindow(slot, coefficients, shift, 0, 0, half);
                applyWindow(slot, coefficients, shift + head, blockSize - half, head, half);
                if (head + half < length) {
                    windowed[head + half] = 0;
                }
                padWindowed(length);
                kernel.autocorrelation(windowed, length, lags, autoc);
                searchPredictors(slot, blockSize, lpcMax, minOrder, maxOrder);
                if (depth > 2) {
                    for (var lag = 0; lag < lags; lag++) {
                        autoc[lag] = rootAutoc[lag] - autoc[lag];
                    }
                    searchPredictors(slot, blockSize, lpcMax, minOrder, maxOrder);
                }
            }
        }
    }

    /**
     * Writes windowed samples into {@link #windowed}.
     *
     * @param slot the signal
     * @param window the window coefficients
     * @param signalOffset the index of the first sample
     * @param windowOffset the index of the first coefficient
     * @param outOffset the index of the first windowed sample
     * @param count the number of samples
     */
    private void applyWindow(Slot slot, double[] window, int signalOffset, int windowOffset, int outOffset, int count) {
        if (slot.wide == null) {
            kernel.window(slot.signal, signalOffset, window, windowOffset, windowed, outOffset, count);
            return;
        }
        var wide = slot.wide;
        var out = windowed;
        for (var i = 0; i < count; i++) {
            out[outOffset + i] = wide[signalOffset + i] * window[windowOffset + i];
        }
    }

    /**
     * Zeroes the elements of {@link #windowed} after the analyzed samples, as the autocorrelation
     * kernel requires.
     *
     * @param length the number of analyzed samples
     */
    private void padWindowed(int length) {
        Arrays.fill(windowed, length, length + FlacEncoderKernel.AUTOCORRELATION_PADDING, 0.0);
    }

    /**
     * Computes the predictors of the current autocorrelation and evaluates the orders and
     * precisions the configuration tries.
     *
     * @param slot the signal
     * @param blockSize the number of samples
     * @param lpcMax the largest predictor order
     * @param minOrder the smallest Rice partition order
     * @param maxOrder the largest Rice partition order
     */
    private void searchPredictors(Slot slot, int blockSize, int lpcMax, int minOrder, int maxOrder) {
        if (autoc[0] == 0.0) {
            return;
        }
        var bps = slot.bps;
        var maxOrderHere = levinson(lpcMax);
        var guess = bestOrder(maxOrderHere, blockSize, bps + (precisionSearch ? MIN_QLP_PRECISION : qlpPrecision));
        var first = exhaustive ? 1 : guess;
        var last = exhaustive ? maxOrderHere : guess;
        for (var order = first; order <= last; order++) {
            if (expectedResidualBits(lpcError[order - 1], blockSize - order) >= bps) {
                continue;
            }
            int minPrecision;
            int maxPrecision;
            if (precisionSearch) {
                minPrecision = MIN_QLP_PRECISION;
                maxPrecision = bps <= 17
                        ? Math.max(Math.min(32 - bps - log2(order), MAX_QLP_PRECISION), MIN_QLP_PRECISION)
                        : MAX_QLP_PRECISION;
            } else {
                minPrecision = qlpPrecision;
                maxPrecision = qlpPrecision;
            }
            for (var precision = minPrecision; precision <= maxPrecision; precision++) {
                var candidate = slot.candidates[1 - slot.best];
                var bits = evaluateLpc(slot, candidate, blockSize, order, precision, minOrder, maxOrder);
                if (bits > 0 && bits < slot.bestBits) {
                    slot.best = 1 - slot.best;
                    slot.bestBits = bits;
                }
            }
        }
    }

    /**
     * Computes the predictor coefficients of every order up to {@code maxOrder} from
     * {@link #autoc} with the Levinson-Durbin recursion.
     *
     * @param maxOrder the largest order
     * @return the largest order computed, smaller than {@code maxOrder} when the error reaches
     * zero first
     */
    private int levinson(int maxOrder) {
        var lpc = levinsonScratch;
        var err = autoc[0];
        for (var i = 0; i < maxOrder; i++) {
            var r = -autoc[i + 1];
            for (var j = 0; j < i; j++) {
                r -= lpc[j] * autoc[i - j];
            }
            r /= err;
            lpc[i] = r;
            var j = 0;
            for (; j < (i >> 1); j++) {
                var tmp = lpc[j];
                lpc[j] += r * lpc[i - 1 - j];
                lpc[i - 1 - j] += r * tmp;
            }
            if ((i & 1) != 0) {
                lpc[j] += lpc[j] * r;
            }
            err *= 1.0 - r * r;
            var coefficients = lpCoefficients[i];
            for (j = 0; j <= i; j++) {
                coefficients[j] = -lpc[j];
            }
            lpcError[i] = err;
            if (err == 0.0) {
                return i + 1;
            }
        }
        return maxOrder;
    }

    /**
     * Estimates the predictor order that codes smallest from the prediction errors.
     *
     * @param maxOrder the largest order computed
     * @param blockSize the number of samples
     * @param overheadBits the bits each order adds per coefficient
     * @return the order
     */
    private int bestOrder(int maxOrder, int blockSize, int overheadBits) {
        var scale = 0.5 / blockSize;
        var best = 0;
        var bestBits = Double.MAX_VALUE;
        for (var index = 0; index < maxOrder; index++) {
            var order = index + 1;
            var bits = expectedResidualBitsScaled(lpcError[index], scale) * (blockSize - order) + (double) order * overheadBits;
            if (bits < bestBits) {
                best = index;
                bestBits = bits;
            }
        }
        return best + 1;
    }

    /**
     * Returns the expected bits per residual sample of a prediction error.
     *
     * @param error the prediction error
     * @param samples the number of residual samples
     * @return the estimate
     */
    private static double expectedResidualBits(double error, int samples) {
        return expectedResidualBitsScaled(error, 0.5 / samples);
    }

    /**
     * Returns the expected bits per residual sample of a scaled prediction error.
     *
     * @param error the prediction error
     * @param scale half the reciprocal of the sample count
     * @return the estimate
     */
    private static double expectedResidualBitsScaled(double error, double scale) {
        if (error > 0.0) {
            var bps = 0.5 * Math.log(scale * error) / LN2;
            return bps >= 0.0 ? bps : 0.0;
        }
        return error < 0.0 ? 1e32 : 0.0;
    }

    /**
     * Quantizes the predictor of an order, computes its residual and partitions it.
     *
     * @param slot the signal
     * @param candidate the candidate to fill
     * @param blockSize the number of samples
     * @param order the predictor order
     * @param precision the coefficient precision
     * @param minOrder the smallest Rice partition order
     * @param maxOrder the largest Rice partition order
     * @return the coded size in bits, or zero when the predictor cannot be used
     */
    private long evaluateLpc(Slot slot, Subframe candidate, int blockSize, int order, int precision, int minOrder, int maxOrder) {
        var bps = slot.bps;
        if (bps <= 17) {
            precision = Math.min(precision, 32 - bps - log2(order));
        }
        var shift = quantize(lpCoefficients[order - 1], order, precision, quantized);
        if (shift == Integer.MIN_VALUE) {
            return 0;
        }
        var residual = candidate.residual;
        var absSum = 0L;
        for (var j = 0; j < order; j++) {
            absSum += Math.abs(quantized[j]);
        }
        var beforeShift = bps + signedLog2(absSum == 0 ? 1 : absSum);
        var residualBps = Math.max(bps, beforeShift - shift) + 1;
        if (slot.wide != null) {
            if (!wideLpcResidual(slot.wide, blockSize, quantized, order, shift, residual)) {
                return 0;
            }
        } else if (residualBps > 32 || beforeShift > 32) {
            if (!kernel.lpcResidualWide(slot.signal, blockSize, quantized, order, shift, residual)) {
                return 0;
            }
        } else {
            kernel.lpcResidual(slot.signal, blockSize, quantized, order, shift, residual);
        }
        System.arraycopy(quantized, 0, candidate.coefficients, 0, order);
        candidate.type = LPC;
        candidate.order = order;
        candidate.precision = precision;
        candidate.shift = shift;
        return SUBFRAME_HEADER_BITS + slot.wasted + 4 + 5 + (long) order * (precision + bps)
                + partition(candidate, blockSize, order, minOrder, maxOrder);
    }

    /**
     * Quantizes predictor coefficients, as libFLAC's {@code FLAC__lpc_quantize_coefficients}
     * does: the shift puts the largest coefficient at the top of the precision, and each
     * coefficient is rounded with the rounding error of the previous ones carried forward.
     *
     * @param coefficients the predictor coefficients
     * @param order the predictor order
     * @param precision the precision in bits, sign included
     * @param out receives the quantized coefficients
     * @return the shift, or {@link Integer#MIN_VALUE} when the coefficients cannot be quantized
     */
    private static int quantize(double[] coefficients, int order, int precision, int[] out) {
        var bits = precision - 1;
        var qmax = (1 << bits) - 1;
        var qmin = -(1 << bits);
        var cmax = 0.0;
        for (var i = 0; i < order; i++) {
            cmax = Math.max(cmax, Math.abs(coefficients[i]));
        }
        if (!(cmax > 0.0) || Double.isInfinite(cmax)) {
            return Integer.MIN_VALUE;
        }
        var log2cmax = Math.getExponent(cmax);
        var shift = bits - log2cmax - 1;
        if (shift > 15) {
            shift = 15;
        } else if (shift < -16) {
            return Integer.MIN_VALUE;
        }
        var error = 0.0;
        if (shift >= 0) {
            var scale = (double) (1 << shift);
            for (var i = 0; i < order; i++) {
                error += coefficients[i] * scale;
                var q = (int) Math.max(qmin, Math.min(qmax, roundHalfAway(error)));
                error -= q;
                out[i] = q;
            }
            return shift;
        }
        var scale = (double) (1 << -shift);
        for (var i = 0; i < order; i++) {
            error += coefficients[i] / scale;
            var q = (int) Math.max(qmin, Math.min(qmax, roundHalfAway(error)));
            error -= q;
            out[i] = q;
        }
        return 0;
    }

    /**
     * Rounds to the nearest integer, halves away from zero, as C's {@code lround}.
     *
     * @param value the value to round
     * @return the rounded value
     */
    private static long roundHalfAway(double value) {
        return value >= 0 ? (long) Math.floor(value + 0.5) : -(long) Math.floor(-value + 0.5);
    }

    /**
     * Returns the floor of the base-2 logarithm of a positive value.
     *
     * @param value the value, positive
     * @return the logarithm
     */
    private static int log2(long value) {
        return 63 - Long.numberOfLeadingZeros(value);
    }

    /**
     * Returns the bits a signed value needs, as libFLAC's {@code FLAC__bitmath_silog2}.
     *
     * @param value the signed value
     * @return the width in bits, sign included
     */
    private static int signedLog2(long value) {
        if (value == 0) {
            return 0;
        }
        if (value == -1) {
            return 2;
        }
        var magnitude = value < 0 ? -(value + 1) : value;
        return log2(magnitude) + 2;
    }

    /**
     * Returns the fixed order with the smallest error sum, preferring lower orders on ties.
     *
     * @param errors the five error sums
     * @return the order
     */
    private static int guessFixedOrder(long[] errors) {
        var e0 = errors[0];
        var e1 = errors[1];
        var e2 = errors[2];
        var e3 = errors[3];
        var e4 = errors[4];
        if (e0 <= Math.min(Math.min(Math.min(e1, e2), e3), e4)) {
            return 0;
        }
        if (e1 <= Math.min(Math.min(e2, e3), e4)) {
            return 1;
        }
        if (e2 <= Math.min(e3, e4)) {
            return 2;
        }
        return e3 <= e4 ? 3 : 4;
    }

    /**
     * Estimates the bits per residual sample of a fixed predictor from its error sum.
     *
     * @param error the error sum, {@link Long#MAX_VALUE} for an unusable order
     * @param samples the number of samples summed
     * @return the estimate
     */
    private static double fixedResidualBits(long error, int samples) {
        if (error == Long.MAX_VALUE) {
            return 34.0;
        }
        return error > 0 ? Math.log(LN2 * error / samples) / LN2 : 0.0;
    }

    /**
     * Returns whether every sample of a block equals the first.
     *
     * @param signal the samples
     * @param blockSize the number of samples
     * @return whether the block is constant
     */
    private static boolean isConstant(int[] signal, int blockSize) {
        var first = signal[0];
        for (var i = 1; i < blockSize; i++) {
            if (signal[i] != first) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes a fixed residual in 64-bit arithmetic, checking that it fits a residual sample.
     *
     * @param signal the samples
     * @param blockSize the number of samples
     * @param order the predictor order
     * @param residual receives the residual
     * @return {@code false} when a residual sample does not fit
     */
    private static boolean wideFixedResidual(int[] signal, int blockSize, int order, int[] residual) {
        for (var i = order; i < blockSize; i++) {
            long value = switch (order) {
                case 0 -> signal[i];
                case 1 -> (long) signal[i] - signal[i - 1];
                case 2 -> (long) signal[i] - 2L * signal[i - 1] + signal[i - 2];
                case 3 -> (long) signal[i] - 3L * signal[i - 1] + 3L * signal[i - 2] - signal[i - 3];
                default -> (long) signal[i] - 4L * signal[i - 1] + 6L * signal[i - 2] - 4L * signal[i - 3] + signal[i - 4];
            };
            if (value <= -RESIDUAL_LIMIT || value >= RESIDUAL_LIMIT) {
                return false;
            }
            residual[i] = (int) value;
        }
        return true;
    }

    /**
     * Chooses the subframe of a 33-bit side channel: verbatim, fixed or linear-predictive with
     * residuals checked against the 32-bit residual range.
     *
     * @param slot the side channel slot
     * @param blockSize the number of samples
     * @param minOrder the smallest Rice partition order
     * @param maxOrder the largest Rice partition order
     */
    private void processWideSubframe(Slot slot, int blockSize, int minOrder, int maxOrder) {
        var wide = slot.wide;
        var bps = slot.bps;
        long[] errors = new long[MAX_FIXED_ORDER + 1];
        boolean[] valid = {true, true, true, true, true};
        for (var i = MAX_FIXED_ORDER; i < blockSize; i++) {
            for (var order = 0; order <= MAX_FIXED_ORDER; order++) {
                var value = Math.abs(wideFixed(wide, i, order));
                valid[order] &= value < RESIDUAL_LIMIT;
                errors[order] += valid[order] ? value : 0;
            }
        }
        var guess = -1;
        for (var order = 0; order <= MAX_FIXED_ORDER; order++) {
            if (!valid[order]) {
                errors[order] = Long.MAX_VALUE;
            } else if (guess < 0 || errors[order] < errors[guess]) {
                guess = order;
            }
        }
        if (guess >= 0) {
            var minFixed = exhaustive ? 0 : guess;
            var maxFixed = exhaustive ? MAX_FIXED_ORDER : guess;
            for (var order = minFixed; order <= maxFixed; order++) {
                if (fixedResidualBits(errors[order], blockSize - MAX_FIXED_ORDER) >= bps) {
                    continue;
                }
                var candidate = slot.candidates[1 - slot.best];
                var ok = true;
                for (var i = order; i < blockSize && ok; i++) {
                    var value = wideFixed(wide, i, order);
                    ok = value > -RESIDUAL_LIMIT && value < RESIDUAL_LIMIT;
                    candidate.residual[i] = (int) value;
                }
                if (!ok) {
                    continue;
                }
                var bits = SUBFRAME_HEADER_BITS + slot.wasted + (long) order * bps
                        + partition(candidate, blockSize, order, minOrder, maxOrder);
                candidate.type = FIXED;
                candidate.order = order;
                if (bits < slot.bestBits) {
                    slot.best = 1 - slot.best;
                    slot.bestBits = bits;
                }
            }
        }
        if (maxLpcOrder > 0) {
            var lpcMax = Math.min(maxLpcOrder, blockSize - 1);
            if (lpcMax > 0) {
                for (var window : windows) {
                    analyzeWindow(slot, window, blockSize, lpcMax, minOrder, maxOrder);
                }
            }
        }
    }

    /**
     * Returns the fixed-predictor residual of one 64-bit sample.
     *
     * @param signal the samples
     * @param i the sample index, at least {@code order}
     * @param order the predictor order
     * @return the residual
     */
    private static long wideFixed(long[] signal, int i, int order) {
        return switch (order) {
            case 0 -> signal[i];
            case 1 -> signal[i] - signal[i - 1];
            case 2 -> signal[i] - 2 * signal[i - 1] + signal[i - 2];
            case 3 -> signal[i] - 3 * signal[i - 1] + 3 * signal[i - 2] - signal[i - 3];
            default -> signal[i] - 4 * signal[i - 1] + 6 * signal[i - 2] - 4 * signal[i - 3] + signal[i - 4];
        };
    }

    /**
     * Computes the linear-prediction residual of a 64-bit signal, checking its range.
     *
     * @param signal the samples
     * @param blockSize the number of samples
     * @param coefficients the quantized coefficients
     * @param order the predictor order
     * @param shift the quantization shift
     * @param residual receives the residual
     * @return {@code false} when a residual sample does not fit
     */
    private static boolean wideLpcResidual(long[] signal, int blockSize, int[] coefficients, int order, int shift, int[] residual) {
        for (var i = order; i < blockSize; i++) {
            var sum = 0L;
            for (var j = 0; j < order; j++) {
                sum += coefficients[j] * signal[i - 1 - j];
            }
            var value = signal[i] - (sum >> shift);
            if (value <= -RESIDUAL_LIMIT || value >= RESIDUAL_LIMIT) {
                return false;
            }
            residual[i] = (int) value;
        }
        return true;
    }

    /**
     * Chooses the Rice partition order and parameters of a residual and stores them in the
     * candidate.
     *
     * @param candidate the candidate whose residual, at the samples' indices from {@code order},
     * is partitioned
     * @param blockSize the number of samples
     * @param order the predictor order
     * @param minOrder the smallest partition order
     * @param maxOrder the largest partition order
     * @return the estimated residual size in bits, coding method and partition order included
     */
    private long partition(Subframe candidate, int blockSize, int order, int minOrder, int maxOrder) {
        var max = maxOrder;
        while (max > 0 && (blockSize >> max) <= order) {
            max--;
        }
        var min = Math.min(minOrder, max);
        var residual = candidate.residual;
        var partitions = 1 << max;
        var partitionSize = blockSize >> max;
        var sums = partitionSums;
        for (var p = 0; p < partitions; p++) {
            sums[p] = kernel.absSum(residual, p == 0 ? order : p * partitionSize, (p + 1) * partitionSize);
        }
        var from = 0;
        var to = partitions;
        for (var level = max - 1; level >= min; level--) {
            var count = 1 << level;
            for (var i = 0; i < count; i++) {
                sums[to++] = sums[from] + sums[from + 1];
                from += 2;
            }
        }
        if (escapeCoding) {
            precomputeRawBits(residual, blockSize, order, min, max);
        }
        var bestBits = Long.MAX_VALUE;
        var bestIndex = 0;
        var bestOrder = 0;
        var offset = 0;
        for (var level = max; level >= min; level--) {
            var scratch = bestBits == Long.MAX_VALUE ? 0 : 1 - bestIndex;
            var bits = riceParameters(offset, blockSize, order, level, parameterScratch[scratch], rawBitsScratch[scratch]);
            if (bits < 0) {
                break;
            }
            offset += 1 << level;
            if (bits < bestBits) {
                bestBits = bits;
                bestIndex = scratch;
                bestOrder = level;
            }
        }
        var count = 1 << bestOrder;
        System.arraycopy(parameterScratch[bestIndex], 0, candidate.parameters, 0, count);
        System.arraycopy(rawBitsScratch[bestIndex], 0, candidate.rawBits, 0, count);
        candidate.partitionOrder = bestOrder;
        var extended = false;
        for (var p = 0; p < count; p++) {
            extended |= candidate.parameters[p] >= 15;
        }
        candidate.extended = extended;
        return bestBits;
    }

    /**
     * Computes the escape width of every partition, the bits that hold its largest residual in
     * two's complement, for every order from {@code max} down to {@code min}.
     *
     * @param residual the residual samples, from index {@code order}
     * @param blockSize the number of samples
     * @param order the predictor order
     * @param min the smallest partition order
     * @param max the largest partition order
     */
    private void precomputeRawBits(int[] residual, int blockSize, int order, int min, int max) {
        var raw = partitionRawBits;
        var partitions = 1 << max;
        var partitionSize = blockSize >> max;
        for (var p = 0; p < partitions; p++) {
            var rmax = 0;
            var end = (p + 1) * partitionSize;
            for (var i = p == 0 ? order : p * partitionSize; i < end; i++) {
                var r = residual[i];
                rmax |= r < 0 ? ~r : r;
            }
            raw[p] = rmax != 0 ? 31 - Integer.numberOfLeadingZeros(rmax) + 2 : 1;
        }
        var from = 0;
        var to = partitions;
        for (var level = max - 1; level >= min; level--) {
            var count = 1 << level;
            for (var i = 0; i < count; i++) {
                raw[to++] = Math.max(raw[from], raw[from + 1]);
                from += 2;
            }
        }
    }

    /**
     * Chooses the Rice parameter of every partition of one partition order from the partition
     * magnitude sums.
     *
     * @param offset the index of the order's first sum in {@link #partitionSums}
     * @param blockSize the number of samples
     * @param order the predictor order
     * @param level the partition order
     * @param parameters receives the parameters
     * @param rawBits receives the escape widths, zero for Rice-coded partitions
     * @return the estimated size in bits, or {@code -1} when the first partition holds no residual
     */
    private long riceParameters(int offset, int blockSize, int order, int level, int[] parameters, int[] rawBits) {
        var bits = (long) RESIDUAL_HEADER_BITS;
        var partitions = 1 << level;
        var baseSize = blockSize >> level;
        var baseDivisor = 0x40000L / baseSize;
        for (var p = 0; p < partitions; p++) {
            var size = baseSize;
            var divisor = baseDivisor;
            if (p == 0) {
                if (size <= order) {
                    return -1;
                }
                size -= order;
                divisor = 0x40000L / size;
            }
            var mean = partitionSums[offset + p];
            var scaled = mean < 2 ? 0 : ((mean - 1) * divisor) >>> 18;
            var parameter = scaled == 0 ? 0 : log2(scaled) + 1;
            if (parameter >= riceLimit) {
                parameter = riceLimit - 1;
            }
            var partitionBits = 4 + (1L + parameter) * size
                    + (parameter > 0 ? mean >>> (parameter - 1) : mean << 1) - (size >> 1);
            var raw = 0;
            if (escapeCoding) {
                var width = partitionRawBits[offset + p];
                var escaped = 5 + 5 + (long) width * size;
                if (escaped <= partitionBits && width < 32) {
                    raw = width;
                    parameter = 0;
                    partitionBits = escaped;
                }
            }
            parameters[p] = parameter;
            rawBits[p] = raw;
            bits += partitionBits;
        }
        return bits;
    }

    /**
     * Writes the frame header (RFC 9639 section 9.1).
     *
     * @param blockSize the number of samples per channel
     * @param frameNumber the frame number
     * @param assignment the channel assignment: 0 independent, 1 left/side, 2 side/right,
     * 3 mid/side
     */
    private void writeHeader(int blockSize, long frameNumber, int assignment) {
        var w = writer;
        w.write(0xFFF8, 16);
        int blockCode;
        var blockHint = 0;
        switch (blockSize) {
            case 192 -> blockCode = 1;
            case 576 -> blockCode = 2;
            case 1152 -> blockCode = 3;
            case 2304 -> blockCode = 4;
            case 4608 -> blockCode = 5;
            case 256 -> blockCode = 8;
            case 512 -> blockCode = 9;
            case 1024 -> blockCode = 10;
            case 2048 -> blockCode = 11;
            case 4096 -> blockCode = 12;
            case 8192 -> blockCode = 13;
            case 16384 -> blockCode = 14;
            case 32768 -> blockCode = 15;
            default -> {
                blockCode = blockSize <= 0x100 ? 6 : 7;
                blockHint = blockCode;
            }
        }
        w.write(blockCode, 4);
        int rateCode;
        var rateHint = 0;
        switch (sampleRate) {
            case 88200 -> rateCode = 1;
            case 176400 -> rateCode = 2;
            case 192000 -> rateCode = 3;
            case 8000 -> rateCode = 4;
            case 16000 -> rateCode = 5;
            case 22050 -> rateCode = 6;
            case 24000 -> rateCode = 7;
            case 32000 -> rateCode = 8;
            case 44100 -> rateCode = 9;
            case 48000 -> rateCode = 10;
            case 96000 -> rateCode = 11;
            default -> {
                if (sampleRate <= 255000 && sampleRate % 1000 == 0) {
                    rateCode = 12;
                } else if (sampleRate <= 655350 && sampleRate % 10 == 0) {
                    rateCode = 14;
                } else if (sampleRate <= 0xFFFF) {
                    rateCode = 13;
                } else {
                    rateCode = 0;
                }
                rateHint = rateCode;
            }
        }
        w.write(rateCode, 4);
        w.write(assignment == 0 ? channels - 1 : 7 + assignment, 4);
        var sizeCode = switch (bitsPerSample) {
            case 8 -> 1;
            case 12 -> 2;
            case 16 -> 4;
            case 20 -> 5;
            case 24 -> 6;
            case 32 -> 7;
            default -> 0;
        };
        w.write(sizeCode, 3);
        w.write(0, 1);
        w.writeUtf8(frameNumber);
        if (blockHint != 0) {
            w.write(blockSize - 1, blockHint == 6 ? 8 : 16);
        }
        switch (rateHint) {
            case 12 -> w.write(sampleRate / 1000, 8);
            case 13 -> w.write(sampleRate, 16);
            case 14 -> w.write(sampleRate / 10, 16);
            default -> {
            }
        }
        w.flush();
        w.write(Crc16.crc8(w.buffer(), 0, w.byteCount()), 8);
    }

    /**
     * Writes the best subframe of a slot (RFC 9639 section 9.2).
     *
     * @param slot the signal
     * @param blockSize the number of samples
     */
    private void writeSubframe(Slot slot, int blockSize) {
        var w = writer;
        var subframe = slot.candidates[slot.best];
        var bps = slot.bps;
        var wasted = slot.wasted;
        var flag = wasted > 0 ? 1 : 0;
        switch (subframe.type) {
            case CONSTANT -> {
                w.write(flag, 8);
                writeWasted(wasted);
                w.writeLong(subframe.constant, bps);
            }
            case VERBATIM -> {
                w.write(2 | flag, 8);
                writeWasted(wasted);
                if (slot.wide != null) {
                    w.writeSignedBlock(slot.wide, 0, blockSize, bps);
                } else {
                    w.writeSignedBlock(slot.signal, 0, blockSize, bps);
                }
            }
            case FIXED -> {
                w.write(0x10 | (subframe.order << 1) | flag, 8);
                writeWasted(wasted);
                writeWarmUp(slot, subframe.order, bps);
                writeResidual(subframe, blockSize);
            }
            default -> {
                w.write(0x40 | ((subframe.order - 1) << 1) | flag, 8);
                writeWasted(wasted);
                writeWarmUp(slot, subframe.order, bps);
                w.write(subframe.precision - 1, 4);
                w.write(subframe.shift, 5);
                w.writeSignedBlock(subframe.coefficients, 0, subframe.order, subframe.precision);
                writeResidual(subframe, blockSize);
            }
        }
    }

    /**
     * Writes the unary wasted-bits count of a subframe header.
     *
     * @param wasted the number of wasted bits
     */
    private void writeWasted(int wasted) {
        if (wasted > 0) {
            writer.writeUnary(wasted - 1);
        }
    }

    /**
     * Writes the warm-up samples of a predicted subframe.
     *
     * @param slot the signal
     * @param order the predictor order
     * @param bps the subframe's sample size
     */
    private void writeWarmUp(Slot slot, int order, int bps) {
        if (slot.wide != null) {
            writer.writeSignedBlock(slot.wide, 0, order, bps);
        } else {
            writer.writeSignedBlock(slot.signal, 0, order, bps);
        }
    }

    /**
     * Writes the partitioned Rice residual of a predicted subframe.
     *
     * @param subframe the chosen subframe to write
     * @param blockSize the number of samples
     */
    private void writeResidual(Subframe subframe, int blockSize) {
        var w = writer;
        var extended = subframe.extended;
        var parameterBits = extended ? 5 : 4;
        var escape = extended ? 31 : 15;
        var level = subframe.partitionOrder;
        w.write(extended ? 1 : 0, 2);
        w.write(level, 4);
        var partitions = 1 << level;
        var size = blockSize >> level;
        var residual = subframe.residual;
        for (var p = 0; p < partitions; p++) {
            var from = p == 0 ? subframe.order : p * size;
            var to = (p + 1) * size;
            var raw = subframe.rawBits[p];
            if (raw == 0) {
                w.write(subframe.parameters[p], parameterBits);
                w.writeRiceBlock(residual, from, to, subframe.parameters[p]);
            } else {
                w.write(escape, parameterBits);
                w.write(raw, 5);
                w.writeSignedBlock(residual, from, to, raw);
            }
        }
    }

    /**
     * Holds one signal of a frame (a channel, or the mid or side signal) and its two subframe
     * candidates: the best so far and the one being evaluated.
     */
    private static final class Slot {
        /**
         * The samples, with wasted bits shifted out.
         */
        final int[] signal;

        /**
         * The 33-bit samples of a 32-bit stereo side channel that wastes no bit, otherwise
         * {@code null}.
         */
        long[] wide;

        /**
         * The number of wasted bits.
         */
        int wasted;

        /**
         * The subframe's sample size after removing the wasted bits.
         */
        int bps;

        /**
         * The two candidates.
         */
        final Subframe[] candidates;

        /**
         * The index of the best candidate.
         */
        int best;

        /**
         * The coded size of the best candidate in bits.
         */
        long bestBits;

        /**
         * Creates a slot.
         *
         * @param blockSize the largest block size
         * @param partitions the largest number of Rice partitions
         */
        Slot(int blockSize, int partitions) {
            this.signal = new int[blockSize];
            this.candidates = new Subframe[]{new Subframe(blockSize, partitions), new Subframe(blockSize, partitions)};
        }
    }

    /**
     * Describes one coded subframe.
     */
    private static final class Subframe {
        /**
         * The subframe type.
         */
        int type;

        /**
         * The predictor order.
         */
        int order;

        /**
         * The value of a constant subframe.
         */
        long constant;

        /**
         * The quantized coefficients of a linear predictor.
         */
        final int[] coefficients = new int[MAX_LPC_ORDER];

        /**
         * The coefficient precision of a linear predictor.
         */
        int precision;

        /**
         * The quantization shift of a linear predictor.
         */
        int shift;

        /**
         * The residual at the samples' indices.
         */
        final int[] residual;

        /**
         * The Rice partition order.
         */
        int partitionOrder;

        /**
         * The Rice parameter of every partition.
         */
        final int[] parameters;

        /**
         * The escape width of every partition, zero for Rice-coded ones.
         */
        final int[] rawBits;

        /**
         * Whether the residual uses five-bit Rice parameters.
         */
        boolean extended;

        /**
         * Creates a subframe workspace.
         *
         * @param blockSize the largest block size
         * @param partitions the largest number of Rice partitions
         */
        Subframe(int blockSize, int partitions) {
            this.residual = new int[blockSize];
            this.parameters = new int[partitions];
            this.rawBits = new int[partitions];
        }
    }
}
