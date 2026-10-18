package com.github.auties00.smalt.video.vp9.internal;

/**
 * Estimates the noise level of the source of the real-time VP9 encoder from the temporal
 * variance of static background blocks, so that noisy video gets coarser partitions and a more
 * conservative cyclic refresh.
 *
 * <p>Every eighth frame, the 16x16 blocks that kept a zero motion vector for several frames,
 * skin excluded, contribute the variance of their difference with the previous source to a
 * histogram; the histogram's mode, smoothed over time, is the noise value, which thresholds that
 * grow with the resolution map to four levels. The level is re-evaluated every 15, then 30,
 * estimates, sooner when the value jumps.
 *
 * @implNote This implementation ports libvpx's {@code vp9/encoder/vp9_noise_estimate.c},
 *           BSD-3, without the denoiser and spatial layers.
 */
final class Vp9RtNoiseEstimate {
    /**
     * The lowest noise level ({@code kLowLow}).
     */
    static final int LOW_LOW = 0;

    /**
     * A low noise level ({@code kLow}).
     */
    static final int LOW = 1;

    /**
     * A medium noise level ({@code kMedium}).
     */
    static final int MEDIUM = 2;

    /**
     * A high noise level ({@code kHigh}).
     */
    static final int HIGH = 3;

    /**
     * The number of variance histogram bins ({@code MAX_VAR_HIST_BINS}).
     */
    private static final int BINS = 20;

    /**
     * Whether the estimate is in use for the frame.
     */
    boolean enabled;

    /**
     * The noise level from the last periodic evaluation.
     */
    int level;

    /**
     * The smoothed noise value.
     */
    private int value;

    /**
     * The estimates since the last evaluation.
     */
    private int count;

    /**
     * The value separating the medium level from the low one.
     */
    private int thresh;

    /**
     * The value above which a low level is re-evaluated at once.
     */
    private int adaptThresh;

    /**
     * The estimates between two evaluations.
     */
    private int numFramesEstimate;

    /**
     * The width of the frame of the last estimate.
     */
    private int lastWidth;

    /**
     * The height of the frame of the last estimate.
     */
    private int lastHeight;

    /**
     * The variance histogram.
     */
    private final int[] hist = new int[BINS];

    /**
     * Creates an estimator for a frame size ({@code vp9_noise_estimate_init}).
     *
     * @param width  the frame width
     * @param height the frame height
     */
    Vp9RtNoiseEstimate(int width, int height) {
        var area = (long) width * height;
        level = area < 1280 * 720 ? LOW_LOW : LOW;
        thresh = 90;
        if (area >= 1920 * 1080) {
            thresh = 200;
        } else if (area >= 1280 * 720) {
            thresh = 140;
        } else if (area >= 640 * 360) {
            thresh = 115;
        }
        numFramesEstimate = 15;
        adaptThresh = (3 * thresh) >> 1;
    }

    /**
     * Returns the noise level of the current value ({@code vp9_noise_estimate_extract_level}).
     *
     * @return one of {@link #LOW_LOW}, {@link #LOW}, {@link #MEDIUM} and {@link #HIGH}
     */
    int extractLevel() {
        if (value > (thresh << 1)) {
            return HIGH;
        }
        if (value > thresh) {
            return MEDIUM;
        }
        return value > (thresh >> 1) ? LOW : LOW_LOW;
    }

    /**
     * Updates the estimate with the frame about to be coded ({@code vp9_update_noise_estimate}).
     *
     * @param enc the encoder, whose source, previous source and zero motion counts are read
     */
    void update(Vp9RtEncoder enc) {
        var width = enc.width;
        var height = enc.height;
        var lowRes = width <= 352 && height <= 288;
        var thresholdZeromv = 6;
        enabled = enc.cyclicRefreshEnabled && enc.temporalLayers == 1 && (long) width * height >= 640 * 360;
        var last = enc.lastSource;
        if (!enabled || enc.currentFrame % 8 != 0 || last == null || lastWidth != width || lastHeight != height) {
            if (last != null) {
                lastWidth = width;
                lastHeight = height;
            }
            return;
        }
        if (enc.currentFrame > 60 && enc.codedFrames() > 1 && enc.rc.framesSinceKey > 1
                && enc.rc.avgFrameLowMotion < (lowRes ? 60 : 40)) {
            level = LOW_LOW;
            count = 0;
            numFramesEstimate = 10;
            return;
        }
        var miRows = enc.miRows;
        var miCols = enc.miCols;
        var czm = enc.consecZeroMv;
        var numLowMotion = 0;
        for (var i = 0; i < miRows * miCols; i++) {
            if ((czm[i] & 0xFF) > thresholdZeromv) {
                numLowMotion++;
            }
        }
        var frameLowMotion = numLowMotion >= ((3 * miRows * miCols) >> 3);
        java.util.Arrays.fill(hist, 0);
        var src = enc.source;
        var edsp = Vp9EncDsp.INSTANCE;
        var offsets = new int[3];
        for (var row = 0; row < miRows; row += 4) {
            for (var col = 0; col < miCols; col += 4) {
                if (row >= miRows - 1 || col >= miCols - 1) {
                    continue;
                }
                var index = row * miCols + col;
                var consec = Math.min(czm[index] & 0xFF, Math.min(czm[index + 1] & 0xFF,
                        Math.min(czm[index + miCols] & 0xFF, czm[index + miCols + 1] & 0xFF)));
                if (frameLowMotion && consec > thresholdZeromv && !enc.rc.highSourceSad) {
                    offsets[0] = src.offset(0, col << 3, row << 3);
                    offsets[1] = src.offset(1, col << 2, row << 2);
                    offsets[2] = src.offset(2, col << 2, row << 2);
                    var skin = enc.useSkinDetection && enc.content.skinBlockWithMotion(offsets, consec);
                    if (!skin) {
                        var packed = edsp.variance(src.planes[0], offsets[0], src.strides[0], last.planes[0],
                                last.offset(0, col << 3, row << 3), last.strides[0], 16, 16);
                        var variance = Vp9EncDsp.variance(packed, 8);
                        var bin = variance / 100;
                        if (bin < BINS) {
                            hist[(int) bin]++;
                        } else if (bin < 3 * (BINS >> 1)) {
                            hist[BINS - 1]++;
                        }
                    }
                }
            }
        }
        lastWidth = width;
        lastHeight = height;
        if (hist[0] > 10 && hist[BINS - 1] > hist[0] >> 2) {
            hist[0] = 0;
            hist[1] >>= 2;
            hist[2] >>= 2;
            hist[3] >>= 2;
            hist[4] >>= 1;
            hist[5] >>= 1;
            hist[6] = 3 * hist[6] >> 1;
            hist[BINS - 1] >>= 1;
        }
        var maxBin = 0;
        var maxBinCount = 0;
        for (var bin = 0; bin < BINS; bin++) {
            int avg;
            if (bin == 0) {
                avg = (hist[0] + hist[1] + hist[2]) / 3;
            } else if (bin == BINS - 1) {
                avg = hist[BINS - 1] >> 2;
            } else if (bin == BINS - 2) {
                avg = (hist[bin - 1] + 2 * hist[bin] + (hist[bin + 1] >> 1) + 2) >> 2;
            } else {
                avg = (hist[bin - 1] + 2 * hist[bin] + hist[bin + 1] + 2) >> 2;
            }
            if (avg > maxBinCount) {
                maxBinCount = avg;
                maxBin = bin;
            }
        }
        value = (3 * value + maxBin * 40) >> 2;
        if (level < MEDIUM && value > adaptThresh) {
            count = numFramesEstimate;
        } else {
            count++;
        }
        if (count == numFramesEstimate) {
            numFramesEstimate = 30;
            count = 0;
            level = extractLevel();
        }
    }
}
