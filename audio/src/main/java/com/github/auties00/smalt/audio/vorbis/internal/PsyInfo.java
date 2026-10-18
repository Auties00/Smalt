/* Ports libvorbis 1.3.7 (lib/psy.h). Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Holds the psychoacoustic parameters of one block type: the threshold in quiet, tone masking,
 * noise masking and noise normalization settings ({@code vorbis_info_psy}).
 */
public final class PsyInfo {
    /**
     * The number of noise curves (low, nominal and high bitrate).
     */
    public static final int NOISE_CURVES = 3;

    /**
     * The number of half-octave bands of the masking tables.
     */
    public static final int BANDS = 17;

    /**
     * The number of noise compander levels.
     */
    public static final int COMPAND_LEVELS = 40;

    /**
     * Whether the parameters are for long blocks (1) or short ones (0).
     */
    public int blockFlag;

    /**
     * The attenuation of the floating threshold in quiet below the local maximum, in decibels.
     */
    public float athAdjustAttenuation;

    /**
     * The largest attenuation of the threshold in quiet, in decibels.
     */
    public float athMaxAttenuation;

    /**
     * The master attenuation of tone masking for each noise curve, in decibels.
     */
    public final float[] toneMasterAttenuation = new float[NOISE_CURVES];

    /**
     * The boost at the center of the tone masking curves.
     */
    public float toneCenterBoost;

    /**
     * The decay of the tone masking curves away from their center.
     */
    public float toneDecay;

    /**
     * The absolute limit of tone masking, in decibels.
     */
    public float toneAbsoluteLimit;

    /**
     * The tone masking attenuation of each band, in decibels.
     */
    public final float[] toneAttenuation = new float[BANDS];

    /**
     * The largest noise suppression, in decibels.
     */
    public float noiseMaxSuppression;

    /**
     * The width of the noise median window below a line, in Bark.
     */
    public float noiseWindowLow;

    /**
     * The width of the noise median window above a line, in Bark.
     */
    public float noiseWindowHigh;

    /**
     * The smallest width of the noise window below a line, in lines.
     */
    public int noiseWindowLowMin;

    /**
     * The smallest width of the noise window above a line, in lines.
     */
    public int noiseWindowHighMin;

    /**
     * The width of the fixed noise window, in lines.
     */
    public int noiseWindowFixed;

    /**
     * The noise offset of each noise curve and band, in decibels.
     */
    public final float[][] noiseOffset = new float[NOISE_CURVES][BANDS];

    /**
     * The noise compander by level.
     */
    public final float[] noiseCompand = new float[COMPAND_LEVELS];

    /**
     * The largest tone curve level, in decibels.
     */
    public float maxCurveDb;

    /**
     * Whether noise normalization is on.
     */
    public int normalP;

    /**
     * The first line of noise normalization.
     */
    public int normalStart;

    /**
     * The partition size of noise normalization.
     */
    public int normalPartition;

    /**
     * The energy threshold of noise normalization.
     */
    public double normalThreshold;

    /**
     * Creates parameters with every field zero.
     */
    public PsyInfo() {
    }

    /**
     * Returns a copy of these parameters.
     *
     * @return the copy
     */
    public PsyInfo copy() {
        var copy = new PsyInfo();
        copy.blockFlag = blockFlag;
        copy.athAdjustAttenuation = athAdjustAttenuation;
        copy.athMaxAttenuation = athMaxAttenuation;
        System.arraycopy(toneMasterAttenuation, 0, copy.toneMasterAttenuation, 0, NOISE_CURVES);
        copy.toneCenterBoost = toneCenterBoost;
        copy.toneDecay = toneDecay;
        copy.toneAbsoluteLimit = toneAbsoluteLimit;
        System.arraycopy(toneAttenuation, 0, copy.toneAttenuation, 0, BANDS);
        copy.noiseMaxSuppression = noiseMaxSuppression;
        copy.noiseWindowLow = noiseWindowLow;
        copy.noiseWindowHigh = noiseWindowHigh;
        copy.noiseWindowLowMin = noiseWindowLowMin;
        copy.noiseWindowHighMin = noiseWindowHighMin;
        copy.noiseWindowFixed = noiseWindowFixed;
        for (var i = 0; i < NOISE_CURVES; i++) {
            System.arraycopy(noiseOffset[i], 0, copy.noiseOffset[i], 0, BANDS);
        }
        System.arraycopy(noiseCompand, 0, copy.noiseCompand, 0, COMPAND_LEVELS);
        copy.maxCurveDb = maxCurveDb;
        copy.normalP = normalP;
        copy.normalStart = normalStart;
        copy.normalPartition = normalPartition;
        copy.normalThreshold = normalThreshold;
        return copy;
    }
}
