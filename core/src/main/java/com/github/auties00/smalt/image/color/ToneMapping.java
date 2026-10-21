package com.github.auties00.smalt.image.color;

/**
 * Selects how a {@link ColorTransform} compresses luminance that its target cannot hold: that of a
 * high-dynamic-range source (PQ or HLG) converted to a standard-dynamic-range target, and that of
 * any source whose peak exceeds the limit of a high-dynamic-range target.
 *
 * <p>For a standard-dynamic-range target both operators measure luminance against
 * {@link ColorTransformOptions#referenceWhite()}: a source luminance equal to it reaches the target
 * white unless the operator compresses it. For a high-dynamic-range target they compress to the
 * target limit that {@link ColorTransform} defines.
 */
public enum ToneMapping {
    /**
     * Rolls highlights off smoothly with the ITU-R BT.2390 EETF, applied to the largest of the
     * red, green and blue components so that hues are kept, from the source peak (the mastering
     * display peak, else the maximum content light level, else 1000 candelas per square meter)
     * down to the reference white, or to the limit of a high-dynamic-range target.
     *
     * <p>The curve leaves luminance below its knee unchanged and compresses everything above it,
     * the reference white included: for a 1000 candelas per square meter source the knee is near
     * 95 candelas per square meter and the reference white lands at about 0.8 of the target white
     * in linear light (8-bit sRGB 229), which keeps highlight detail. A source whose peak does not
     * exceed the reference white, or the limit of a high-dynamic-range target, is not compressed.
     */
    BT2390,

    /**
     * Clips every component above the reference white, or above the limit of a high-dynamic-range
     * target.
     */
    CLIP
}
