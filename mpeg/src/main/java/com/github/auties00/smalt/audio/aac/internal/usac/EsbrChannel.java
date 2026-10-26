/* Ports the ISO/IEC 23003-3:2019 reference software (ct_sbrdec.c: sbr_dec, sbr_dec_from_mps, resetSbrDec,
   copyHarmonicSpectrum).
   Copyright Coding Technologies, Dolby Laboratories, Sony Corporation and ISO/IEC 2002-2008; ISO/IEC reference
   software licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import java.util.Arrays;

/**
 * Runs enhanced spectral band replication on one channel ({@code sbr_dec}): the core band analysis, the high
 * band generation by copy-up patching or harmonic transposition, the envelope adjustment and the synthesis at
 * the output rate.
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class EsbrChannel {
    private static final int CODEC_ROWS = 80 + 2 * EsbrContext.TRANSPOSER_DELAY;
    private static final int ROWS = 80;

    final EsbrContext context;
    final EsbrFrame frame;
    final EsbrFrequencies bands = new EsbrFrequencies();
    final EsbrHfGenerator generator = new EsbrHfGenerator();
    final EsbrAdjuster adjuster;
    final EsbrTransposer transposer;
    final boolean harmonic;

    /**
     * Whether a header initialized the channel; until then it only resamples.
     */
    boolean active;

    /**
     * Whether the band tables were rebuilt for the current frame.
     */
    boolean reset;

    int previousLowSubband = 64;

    final float[][] codecReal = new float[CODEC_ROWS][64];
    final float[][] codecImag = new float[CODEC_ROWS][64];
    final float[][] highReal = new float[ROWS][64];
    final float[][] highImag = new float[ROWS][64];
    final float[][] transposedReal;
    final float[][] transposedImag;

    /**
     * The QMF slots of the last frame, as the synthesis consumed them.
     */
    final float[][] outputReal;
    final float[][] outputImag;

    private float[][] mpsCodecReal;
    private float[][] mpsCodecImag;
    private float[][] mpsHighReal;
    private float[][] mpsHighImag;
    private float[][] mpsTransposed;

    private final float[] lowBand = new float[2624];
    private final float[] analysisState;
    private final double[] synthesisState = new double[1280];
    private final double[] scratch = new double[64];
    private final float[] pvcSlots = new float[16 * 32];
    private final float[] pvcOut = new float[1024];

    /**
     * Constructs a channel.
     *
     * @param context the shared state
     * @param harmonic whether the element uses the harmonic transposer
     * @param pvc whether the element may use predictive vector coding
     */
    EsbrChannel(EsbrContext context, boolean harmonic, boolean pvc) {
        this.context = context;
        this.harmonic = harmonic;
        this.frame = new EsbrFrame(context.rate);
        this.adjuster = new EsbrAdjuster(pvc);
        this.analysisState = new float[10 * context.qmf.bands];
        if (harmonic) {
            this.transposer = new EsbrTransposer(context.coreLength, context.sbr41());
            this.transposedReal = new float[ROWS][64];
            this.transposedImag = new float[ROWS][64];
        } else {
            this.transposer = null;
            this.transposedReal = null;
            this.transposedImag = null;
        }
        this.outputReal = new float[context.columns][64];
        this.outputImag = new float[context.columns][64];
        context.channels.add(this);
    }

    /**
     * Rebuilds the band tables after a header change ({@code resetSbrDec}).
     */
    void resetBands() {
        reset = true;
        bands.reset(frame.header, context.outputRate, context.rate);
        frame.scaleFactorBands[EsbrFrequencies.LOW] = bands.bands[EsbrFrequencies.LOW];
        frame.scaleFactorBands[EsbrFrequencies.HIGH] = bands.bands[EsbrFrequencies.HIGH];
        frame.noiseBands = bands.noiseBands;
        frame.offset = 2 * frame.scaleFactorBands[EsbrFrequencies.LOW] - frame.scaleFactorBands[EsbrFrequencies.HIGH];
        var startBand = previousLowSubband;
        var stopBand = bands.lowSubband;
        var writeOffset = context.writeOffset;
        for (var other : context.channels) {
            if (other.harmonic) {
                continue;
            }
            for (var l = 0; l < Math.max(EsbrContext.READ_OFFSET, writeOffset); l++) {
                for (var k = startBand; k < stopBand; k++) {
                    other.codecReal[l][k] = 0.0f;
                    other.codecImag[l][k] = 0.0f;
                }
            }
        }
        if (harmonic) {
            transposer.reset(bands);
            var columns = context.columns;
            for (var k = 0; k < 2; k++) {
                if (context.sbr41() && k == 0) {
                    continue;
                }
                var delay = columns * k;
                if (context.sbr41()) {
                    delay = columns * k - 32;
                }
                for (var i = 0; i < writeOffset; i++) {
                    System.arraycopy(transposedReal[columns + i], 0, transposedReal[i], 0, 64);
                    System.arraycopy(transposedImag[columns + i], 0, transposedImag[i], 0, 64);
                }
                transposer.apply(codecReal, codecImag, writeOffset + delay, columns, transposedReal, transposedImag,
                        writeOffset, frame.pitchInBins);
                if (context.sbr41()) {
                    copyHarmonicSpectrum(writeOffset);
                }
            }
        }
    }

    /**
     * Processes one frame.
     *
     * @param input the core output, {@code coreLength} samples
     * @param output receives the output, {@code 64 * columns} samples
     * @param apply whether to replicate the high band; otherwise the channel only resamples
     * @param stereoConfigIndex the element's {@code stereoConfigIndex}
     * @param element the element decoder, for the predictive vector coding state
     * @param pvcFrame whether the frame uses predictive vector coding
     * @param synthesize whether to synthesize the output; otherwise only {@link #outputReal} and
     *                   {@link #outputImag} receive the frame
     */
    void process(float[] input, float[] output, boolean apply, int stereoConfigIndex, EsbrElement element,
                 boolean pvcFrame, boolean synthesize) {
        var qmf = context.qmf;
        var analysisBands = qmf.bands;
        var columns = context.columns;
        var fromMps = stereoConfigIndex == 3;
        var codecDelay = harmonic ? EsbrContext.TRANSPOSER_DELAY : 0;
        if (context.sbr41()) {
            codecDelay *= 2;
        }
        var writeOffset = fromMps ? context.hybridOffset : context.sbrOffset;
        context.writeOffset = writeOffset;
        var history = context.history;
        var coreLength = context.coreLength;
        System.arraycopy(lowBand, coreLength, lowBand, 0, history);
        System.arraycopy(input, 0, lowBand, history, coreLength);
        for (var i = 0; i < writeOffset + codecDelay; i++) {
            System.arraycopy(codecReal[columns + i], 0, codecReal[i], 0, 64);
            System.arraycopy(codecImag[columns + i], 0, codecImag[i], 0, 64);
        }
        for (var i = 0; i < writeOffset; i++) {
            System.arraycopy(highReal[columns + i], 0, highReal[i], 0, 64);
            System.arraycopy(highImag[columns + i], 0, highImag[i], 0, 64);
        }
        if (harmonic) {
            for (var i = 0; i < writeOffset; i++) {
                System.arraycopy(transposedReal[columns + i], 0, transposedReal[i], 0, 64);
                System.arraycopy(transposedImag[columns + i], 0, transposedImag[i], 0, 64);
            }
        }
        for (var i = 0; i < columns; i++) {
            qmf.analyze(analysisState, lowBand, i * analysisBands, codecReal[writeOffset + i + codecDelay],
                    codecImag[writeOffset + i + codecDelay], scratch);
        }
        if (apply && harmonic) {
            var delay = codecDelay;
            if (context.sbr41()) {
                delay = codecDelay - 32;
            }
            transposer.apply(codecReal, codecImag, writeOffset + delay, columns, transposedReal, transposedImag,
                    writeOffset, frame.pitchInBins);
            if (context.sbr41()) {
                copyHarmonicSpectrum(writeOffset);
            }
        }
        var read = EsbrContext.READ_OFFSET;
        var pvc = element.pvc;
        if (pvc != null && apply && !fromMps) {
            if (context.sbr41()) {
                for (var i = 0; i < 16; i++) {
                    for (var j = 0; j < 16; j++) {
                        var sum = 0.0f;
                        for (var s = 0; s < 4; s++) {
                            var re = codecReal[read + 4 * i + s][j];
                            var im = codecImag[read + 4 * i + s][j];
                            sum += re * re + im * im;
                        }
                        pvcSlots[16 * i + j] = sum / 4.0f;
                    }
                }
            } else {
                for (var i = 0; i < 16; i++) {
                    for (var j = 0; j < 32; j++) {
                        var re0 = codecReal[read + 2 * i][j];
                        var im0 = codecImag[read + 2 * i][j];
                        var re1 = codecReal[read + 2 * i + 1][j];
                        var im1 = codecImag[read + 2 * i + 1][j];
                        pvcSlots[32 * i + j] = (re0 * re0 + im0 * im0 + (re1 * re1 + im1 * im1)) / 2.0f;
                    }
                }
            }
        }
        if (!fromMps && apply) {
            generator.generate(codecReal, codecImag, read, transposedReal, transposedImag, read, harmonic, frame,
                    bands, context.outputRate, highReal, highImag, read, context.sbr41());
            if (pvc != null) {
                pvc.decode(pvcFrame, context.rate, bands.lowSubband, frame.pvcFrameInfo[1], pvcSlots, pvcOut);
            }
            adjuster.adjust(frame, bands, highReal, highImag, read, codecReal, codecImag, read, reset,
                    harmonic ? transposer.crossover : null, generator, context.sbr41(), pvcFrame, pvcOut,
                    element.previousPvcFrame);
        } else {
            for (var i = 0; i < 64; i++) {
                Arrays.fill(highReal[i], 0.0f);
                Arrays.fill(highImag[i], 0.0f);
            }
        }
        var stopBorder = apply ? context.rate * frame.frameInfo[1] : 0;
        for (var i = 0; i < columns; i++) {
            int crossover;
            if (apply) {
                crossover = i < stopBorder ? previousLowSubband : bands.lowSubband;
            } else {
                crossover = analysisBands;
            }
            var maxBand = fromMps ? analysisBands : crossover;
            var outRe = outputReal[i];
            var outIm = outputImag[i];
            var codecRow = read + i;
            for (var k = 0; k < 64; k++) {
                var re = 0.0f;
                var im = 0.0f;
                if (k < maxBand) {
                    var row = k < 3 && stereoConfigIndex > 0 ? codecRow + EsbrContext.HYBRID_DELAY : codecRow;
                    re += codecReal[row][k];
                    im += codecImag[row][k];
                }
                if (!fromMps && k >= crossover) {
                    re += highReal[codecRow][k];
                    im += highImag[codecRow][k];
                }
                outRe[k] = re;
                outIm[k] = im;
            }
        }
        if (synthesize) {
            for (var i = 0; i < columns; i++) {
                qmf.synthesize(synthesisState, outputReal[i], outputImag[i], output, i * 64);
            }
        }
        if (!fromMps) {
            reset = false;
        }
        if (apply && !fromMps) {
            previousLowSubband = bands.lowSubband;
        }
    }

    /**
     * Replicates the high band of one MPEG Surround output channel ({@code sbr_dec_from_mps}), for the elements
     * whose {@code stereoConfigIndex} is 3.
     *
     * <p>The core band of the MPEG Surround output comes out {@code sbrOffset - READ_OFFSET} slots late, with the
     * replicated high band above it.
     *
     * @param real the real parts of the output channel's QMF slots, {@code [columns][64]}, replaced in place
     * @param imag the imaginary parts of the output channel's QMF slots, replaced in place
     */
    void processFromMps(float[][] real, float[][] imag) {
        if (mpsCodecReal == null) {
            mpsCodecReal = new float[ROWS][64];
            mpsCodecImag = new float[ROWS][64];
            mpsHighReal = new float[ROWS][64];
            mpsHighImag = new float[ROWS][64];
            if (harmonic) {
                mpsTransposed = new float[ROWS][64];
            }
        }
        var columns = context.columns;
        var offset = context.sbrOffset;
        var read = EsbrContext.READ_OFFSET;
        var low = bands.lowSubband;
        for (var i = 0; i < columns; i++) {
            System.arraycopy(real[i], 0, mpsCodecReal[offset + i], 0, low);
            System.arraycopy(imag[i], 0, mpsCodecImag[offset + i], 0, low);
        }
        if (reset) {
            var startBand = previousLowSubband;
            var stopBand = context.qmf.bands;
            var startSlot = read + frame.rate * frame.frameInfo[1];
            for (var l = startSlot; l < offset; l++) {
                for (var k = startBand; k < stopBand; k++) {
                    mpsCodecReal[l][k] = 0.0f;
                    mpsCodecImag[l][k] = 0.0f;
                }
            }
            for (var l = 0; l < read; l++) {
                for (var k = startBand; k < stopBand; k++) {
                    mpsCodecReal[l][k] = 0.0f;
                    mpsCodecImag[l][k] = 0.0f;
                }
            }
        }
        previousLowSubband = low;
        generator.generate(mpsCodecReal, mpsCodecImag, read, mpsTransposed, mpsTransposed, read, harmonic, frame,
                bands, context.outputRate, mpsHighReal, mpsHighImag, read, context.sbr41());
        adjuster.adjust(frame, bands, mpsHighReal, mpsHighImag, read, mpsCodecReal, mpsCodecImag, read, reset,
                harmonic ? transposer.crossover : null, generator, context.sbr41(), false, pvcOut, false);
        for (var i = 0; i < columns; i++) {
            System.arraycopy(mpsCodecReal[read + i], 0, real[i], 0, low);
            System.arraycopy(mpsCodecImag[read + i], 0, imag[i], 0, low);
            System.arraycopy(mpsHighReal[read + i], low, real[i], low, 64 - low);
            System.arraycopy(mpsHighImag[read + i], low, imag[i], low, 64 - low);
        }
        for (var i = 0; i < offset; i++) {
            System.arraycopy(mpsCodecReal[columns + i], 0, mpsCodecReal[i], 0, 64);
            System.arraycopy(mpsCodecImag[columns + i], 0, mpsCodecImag[i], 0, 64);
            System.arraycopy(mpsHighReal[columns + i], 0, mpsHighReal[i], 0, 64);
            System.arraycopy(mpsHighImag[columns + i], 0, mpsHighImag[i], 0, 64);
        }
        reset = false;
    }

    private void copyHarmonicSpectrum(int base) {
        var xover = transposer.crossover;
        var maxStretch = transposer.maxStretch;
        var columns = context.columns;
        var patches = 0;
        for (var i = 1; i < EsbrHfGenerator.MAX_PATCHES; i++) {
            if (xover[i] != 0) {
                patches++;
            }
        }
        if (maxStretch < 2) {
            return;
        }
        for (var patch = maxStretch - 1; patch < patches; patch++) {
            var patchBands = xover[patch + 1] - xover[patch];
            var target = xover[patch];
            var sourceBands = xover[maxStretch - 1] - xover[maxStretch - 2];
            var guard = 0;
            while (patchBands > 0) {
                if (++guard > 64) {
                    break;
                }
                var count = sourceBands;
                var startBand = xover[maxStretch - 1] - 1;
                if (target + count >= xover[patch + 1]) {
                    count = xover[patch + 1] - target;
                }
                if ((((target + count - 1) % 2) + ((xover[maxStretch - 1] - 1) % 2)) % 2 != 0) {
                    if (count == sourceBands) {
                        count--;
                    } else {
                        startBand--;
                    }
                }
                for (var col = 0; col < columns; col++) {
                    var re = transposedReal[base + col];
                    var im = transposedImag[base + col];
                    var i = 0;
                    for (var band = count; band > 0; band--) {
                        var index = target + band - 1;
                        if (index < 64 && index < xover[patch + 1]) {
                            re[index] = re[startBand - i];
                            im[index] = im[startBand - i];
                            i++;
                        }
                    }
                }
                target += count;
                patchBands -= count;
            }
        }
    }
}
