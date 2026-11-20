/* Ports libaom 3.13.1 (aom_dsp/noise_model.c: aom_denoise_and_model_run). Copyright (c) 2017, Alliance for
   Open Media; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.video.av1.Av1FilmGrain;

import java.util.Arrays;

/**
 * Denoises the pictures of a video and estimates the film grain the denoising removed, frame
 * after frame: the flat blocks of each picture are found, the picture is denoised with a Wiener
 * filter, and the noise model is updated with the difference.
 *
 * <p>An instance is used by one thread at a time; its estimates depend on the pictures given
 * before.
 */
public final class FilmGrainEstimator {
    /**
     * The block size of the flat block search, the denoising and the model ({@code noise_block_size}).
     */
    private static final int BLOCK_SIZE = 32;

    /**
     * The lag of the autoregressive model.
     */
    private static final int LAG = 3;

    /**
     * The seed given to the first frame's grain ({@code random_seed} of 0 replaced by 7391).
     */
    private static final int FIRST_SEED = 7391;

    /**
     * The denoised picture and the grain estimated for it.
     *
     * @param picture the picture to code: the denoised picture when the noise could be estimated,
     * the picture itself otherwise
     * @param grain the grain to signal, or {@code null} when none could be estimated
     */
    public record Result(Picture picture, Av1FilmGrain grain) {
    }

    /**
     * The chroma format of the pictures.
     */
    private final ChromaFormat format;

    /**
     * The noise model.
     */
    private final NoiseModel model;

    /**
     * The denoiser.
     */
    private final WienerDenoiser denoiser;

    /**
     * The flat block finder of luma.
     */
    private final NoiseModel.FlatBlockFinder finder;

    /**
     * The widened samples of each plane of the last picture, of stride the plane width.
     */
    private final short[][] data = new short[3][];

    /**
     * The denoised samples of each plane of the last picture.
     */
    private final short[][] denoised = new short[3][];

    /**
     * The width of each plane of the last picture.
     */
    private final int[] strides = new int[3];

    /**
     * The flat block map of the last picture.
     */
    private byte[] flat = new byte[0];

    /**
     * The denoised picture of the last run, overwritten by the next, or {@code null}.
     */
    private Picture out;

    /**
     * Creates an estimator for pictures of a format.
     *
     * @param format the chroma format, any but {@link ChromaFormat#YUV440}
     * @param bitDepth the bit depth, 8, 10 or 12
     * @param noiseLevel the denoising strength, positive
     */
    public FilmGrainEstimator(ChromaFormat format, int bitDepth, float noiseLevel) {
        this.format = format;
        this.model = new NoiseModel(LAG, bitDepth);
        this.denoiser = new WienerDenoiser(BLOCK_SIZE, bitDepth, noiseLevel);
        this.finder = new NoiseModel.FlatBlockFinder(BLOCK_SIZE, BLOCK_SIZE, bitDepth);
    }

    /**
     * Denoises a picture and estimates its grain ({@code aom_denoise_and_model_run}).
     *
     * @param picture the picture, a {@code YuvPicture} at 8 bits or a {@code Yuv16Picture} of the
     * estimator's depth, in its chroma format
     * @param threads the most threads to use
     * @return the picture to code and its grain; a denoised picture is overwritten by the next run
     */
    public Result run(Picture picture, int threads) {
        var planes = format.hasChroma() ? 3 : 1;
        var subX = format.shiftX();
        var subY = format.shiftY();
        var w = picture.width();
        var h = picture.height();
        for (var pl = 0; pl < planes; pl++) {
            var pw = SourcePlanes.width(picture, pl);
            var ph = SourcePlanes.height(picture, pl);
            if (data[pl] == null || data[pl].length != pw * ph) {
                data[pl] = new short[pw * ph];
                denoised[pl] = new short[pw * ph];
            }
            strides[pl] = pw;
            SourcePlanes.widen(picture, pl, 0, ph, data[pl], 0, pw);
        }
        var blocksW = (w + BLOCK_SIZE - 1) / BLOCK_SIZE;
        var blocksH = (h + BLOCK_SIZE - 1) / BLOCK_SIZE;
        if (flat.length != blocksW * blocksH) {
            flat = new byte[blocksW * blocksH];
        } else {
            Arrays.fill(flat, (byte) 0);
        }
        finder.run(data[0], w, h, strides[0], flat);
        for (var pl = 0; pl < planes; pl++) {
            var sx = pl == 0 ? 0 : subX;
            var sy = pl == 0 ? 0 : subY;
            denoiser.denoise(data[pl], SourcePlanes.width(picture, pl), SourcePlanes.height(picture, pl), strides[pl],
                    sx, sy, w, h, denoised[pl], threads);
        }
        var status = model.update(data, denoised, w, h, strides, subX, subY, flat, BLOCK_SIZE);
        var estimate = switch (status) {
            case OK -> true;
            case DIFFERENT_NOISE_TYPE -> {
                model.saveLatest();
                yield true;
            }
            default -> model.hasEstimate();
        };
        if (!estimate) {
            return new Result(picture, null);
        }
        var grain = model.grainParameters(FIRST_SEED, planes > 1);
        if (out == null || out.width() != w || out.height() != h || out.getClass() != picture.getClass()) {
            out = SourcePlanes.allocateLike(picture);
        }
        for (var pl = 0; pl < planes; pl++) {
            SourcePlanes.narrow(denoised[pl], strides[pl], out, pl);
        }
        return new Result(out, grain.addsGrain() ? grain : null);
    }
}
