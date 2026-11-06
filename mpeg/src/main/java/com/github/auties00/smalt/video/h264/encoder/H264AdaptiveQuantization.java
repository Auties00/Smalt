package com.github.auties00.smalt.video.h264.encoder;

/**
 * Enumerates the ways an {@link H264Encoder} varies the quantizer between the macroblocks of a picture, so
 * that the bits go where coding noise is most visible.
 *
 * <p>Each mode derives a quantizer offset for every macroblock from the picture alone; the offsets of a
 * picture average to zero before they are rounded and limited, so the picture's quantizer, which rate control
 * chooses, stays close to their average. The
 * {@linkplain H264EncoderOptions#macroblockTreeStrength() macroblock tree} adds its own offsets on top.
 */
public enum H264AdaptiveQuantization {
    /**
     * No adaptive quantization: every macroblock takes the picture's quantizer.
     */
    NONE,

    /**
     * Offsets by texture energy: each macroblock's offset is the binary logarithm of its texture energy
     * relative to the picture's geometric mean, times three quarters of the
     * {@linkplain H264EncoderOptions#adaptiveQuantizationStrength() strength}, so flat areas, where coding
     * noise shows, get finer quantizers and busy areas, which hide it, coarser ones.
     */
    VARIANCE,

    /**
     * Offsets by texture energy scaled per picture: as {@link #VARIANCE}, with each picture's offsets scaled so
     * that their spread is the same in every picture, so a picture whose areas differ little in texture still
     * gets a wide variation (its offsets are amplified at most four times), and one that mixes very flat and
     * very busy areas does not get extreme offsets.
     */
    AUTO_VARIANCE,

    /**
     * Offsets by texture energy scaled per picture, with a bias to dark areas: as {@link #AUTO_VARIANCE}, with
     * dark macroblocks quantized finer still, up to three steps times the strength for black areas, as banding
     * and blocking show most in dark scenes.
     */
    AUTO_VARIANCE_DARK
}
