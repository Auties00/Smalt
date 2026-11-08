package com.github.auties00.smalt.video.cfhd;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;

/**
 * What a {@link CfhdDecoder} makes of a CineForm RAW (Bayer coded) frame.
 *
 * @see CfhdDecoderOptions#bayerOutput()
 */
public enum CfhdBayerOutput {
    /**
     * An {@link Rgba16Picture} demosaiced as GoPro's decoder demosaics for its 16-bit RGB outputs (its default
     * "5x5 enhanced" interpolation with a light sharpening), in the domain of the curve the encoder applied to the
     * sensor data (CineForm's log curve unless the sample names another), without white balance or color matrix.
     */
    DEMOSAICED,

    /**
     * The color filter array mosaic as a {@link Yuv16Picture} of {@link ChromaFormat#MONOCHROME} format at 16 bits,
     * one sample per photosite in the arrangement {@link CfhdDecoder#bayerPhase()} reports, linearized by the inverse
     * of the encoder's curve as GoPro's decoder does for its 16-bit Bayer output; the samples of a source whose curve
     * was applied before encoding (a non-zero {@code PCRV} metadata value) keep that curve.
     */
    MOSAIC
}
