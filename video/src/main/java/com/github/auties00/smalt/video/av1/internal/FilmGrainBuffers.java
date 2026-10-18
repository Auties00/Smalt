package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Holds the grain and scaling tables of film grain synthesis and applies grain to shown pictures,
 * the 32-row stripes of a large picture in parallel bands.
 *
 * @implNote This implementation drives {@link FilmGrainApplier}, the port of dav1d's
 * {@code dav1d_apply_grain}, splitting the stripes as dav1d's film grain tasks do: once
 * the tables are built, each stripe reads only its own rows.
 */
final class FilmGrainBuffers {
    /**
     * The scaling table of each plane.
     */
    private final byte[][] scaling = {new byte[4096], new byte[4096], new byte[4096]};

    /**
     * The grain table of each plane.
     */
    private final short[][] grainLut = {
            new short[FilmGrainDsp.GRAIN_LUT_SIZE], new short[FilmGrainDsp.GRAIN_LUT_SIZE],
            new short[FilmGrainDsp.GRAIN_LUT_SIZE]
    };

    /**
     * The plane offsets of the pictures, all zero.
     */
    private final int[] offsets = new int[3];

    /**
     * Creates the buffers.
     */
    FilmGrainBuffers() {
    }

    /**
     * Writes a picture with film grain applied.
     *
     * @param in the decoded picture, whose frame header carries the grain parameters
     * @param out a picture of the same geometry receiving the result
     */
    void apply(Av1Picture in, Av1Picture out) {
        var bitdepthMax = (1 << in.bpc) - 1;
        var dsp = FilmGrainDsp.INSTANCE;
        var data = in.frameHdr.filmGrain;
        var identity = in.seqHdr.mtrx == 0;
        FilmGrainApplier.prepGrain(dsp, out.data, offsets, in.data, offsets, in.stride[0], in.stride[1], in.w, in.h,
                in.layout, bitdepthMax, data, scaling, grainLut);
        var rows = FilmGrainApplier.rows(in.h);
        // Grain synthesis costs several times a picture copy per sample.
        var bands = ParallelBands.bands(rows, 4L * in.w * in.h);
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, rows);
            for (var row = ParallelBands.start(band, bands, rows); row < last; row++) {
                FilmGrainApplier.applyGrainRow(dsp, out.data, offsets, in.data, offsets, in.stride[0], in.stride[1],
                        in.w, in.h, in.layout, identity, bitdepthMax, data, scaling, grainLut, row);
            }
        });
    }
}
