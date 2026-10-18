package com.github.auties00.smalt.audio.mp3;

/**
 * Describes the coding bands of one block kind (long or short) at one sampling rate.
 *
 * <p>A coding band is a scalefactor band of a long block, or a scalefactor band of one window of
 * a short block. Long blocks have 22 coding bands (bands 0 to 20 with a scalefactor, and band 21
 * without); short blocks have 39, ordered band by band and window by window within a band (bands
 * 0 to 11 with scalefactors, band 12 without). Band bounds index the transform output: frequency
 * order for long blocks, window-major ({@code 192 w + f}) for short ones.
 */
final class Mp3BandLayout {
    /**
     * Whether this is the short-block layout.
     */
    final boolean shortBlocks;

    /**
     * The number of coding bands.
     */
    final int count;

    /**
     * The first transform line of each coding band.
     */
    final int[] start;

    /**
     * The end transform line of each coding band.
     */
    final int[] end;

    /**
     * The window of each coding band, {@code 0} for long blocks.
     */
    final int[] window;

    /**
     * The scalefactor index of each coding band in {@link Mp3CodedGranule#scalefactors}, or
     * {@code -1} for the bands without a scalefactor.
     */
    final int[] scalefactor;

    /**
     * The scalefactor band of each coding band, from 0 to 21 for long blocks and 0 to 12 for
     * short ones.
     */
    final int[] band;

    /**
     * The center frequency of each coding band in hertz.
     */
    final float[] center;

    /**
     * The transform line of each position of the coding order.
     */
    final int[] order;

    /**
     * The band widths as the decoder walks the spectrum, zero-terminated: the long widths, or each
     * short width once per window.
     */
    final int[] widths;

    /**
     * Creates the layout of a block kind at a sampling rate.
     *
     * @param rateIndex   the index in {@link Mp3EncoderTables#SAMPLE_RATES}
     * @param shortBlocks whether to describe short blocks
     */
    Mp3BandLayout(int rateIndex, boolean shortBlocks) {
        this.shortBlocks = shortBlocks;
        var rate = Mp3EncoderTables.SAMPLE_RATES[rateIndex];
        this.order = new int[576];
        if (!shortBlocks) {
            var bounds = Mp3EncoderTables.longBands(rateIndex);
            this.count = 22;
            this.start = new int[count];
            this.end = new int[count];
            this.window = new int[count];
            this.scalefactor = new int[count];
            this.band = new int[count];
            this.center = new float[count];
            this.widths = new int[23];
            for (var b = 0; b < 22; b++) {
                start[b] = bounds[b];
                end[b] = bounds[b + 1];
                scalefactor[b] = b < 21 ? b : -1;
                band[b] = b;
                center[b] = (start[b] + end[b]) * 0.5f * rate / 1152;
                widths[b] = end[b] - start[b];
            }
            for (var i = 0; i < 576; i++) {
                order[i] = i;
            }
        } else {
            var bounds = Mp3EncoderTables.shortBands(rateIndex);
            this.count = 39;
            this.start = new int[count];
            this.end = new int[count];
            this.window = new int[count];
            this.scalefactor = new int[count];
            this.band = new int[count];
            this.center = new float[count];
            this.widths = new int[40];
            var position = 0;
            for (var b = 0; b < 13; b++) {
                for (var w = 0; w < 3; w++) {
                    var index = 3 * b + w;
                    start[index] = 192 * w + bounds[b];
                    end[index] = 192 * w + bounds[b + 1];
                    window[index] = w;
                    scalefactor[index] = b < 12 ? index : -1;
                    band[index] = b;
                    center[index] = (bounds[b] + bounds[b + 1]) * 0.5f * rate / 384;
                    widths[index] = bounds[b + 1] - bounds[b];
                    for (var f = bounds[b]; f < bounds[b + 1]; f++) {
                        order[position++] = 192 * w + f;
                    }
                }
            }
        }
    }
}
