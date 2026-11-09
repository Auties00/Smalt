package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.audio.aac.AacBinauralHrtf;
import com.github.auties00.smalt.audio.aac.AacBinauralQuality;

import java.util.Objects;

/**
 * Selects the binaural rendering of an MPEG Surround decoder.
 *
 * @param hrtf the data set of head related transfer functions
 * @param quality how they are applied
 */
public record MpsBinaural(AacBinauralHrtf hrtf, AacBinauralQuality quality) {
    /**
     * Validates the selection.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public MpsBinaural {
        Objects.requireNonNull(hrtf, "hrtf");
        Objects.requireNonNull(quality, "quality");
    }

    /**
     * {@return whether a tree renders binaurally: every tree with a mono or stereo downmix; the trees with a 5.1
     * downmix have no binaural path and keep their loudspeaker output}
     *
     * @param tree the {@code bsTreeConfig}
     */
    public static boolean appliesTo(int tree) {
        return tree != MpsConfig.TREE_7571 && tree != MpsConfig.TREE_7572;
    }
}
