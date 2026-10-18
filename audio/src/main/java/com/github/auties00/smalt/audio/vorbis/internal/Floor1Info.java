package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Holds the configuration of a floor type 1: the partition classes, the post list and the
 * encoder-side fitting parameters.
 *
 * @implNote This implementation mirrors libvorbis's {@code vorbis_info_floor1} (BSD-3).
 */
public final class Floor1Info {
    /**
     * The number of partitions, 0 to 31.
     */
    public int partitions;

    /**
     * The class of each partition.
     */
    public final int[] partitionClass = new int[31];

    /**
     * The dimension of each class.
     */
    public final int[] classDim = new int[16];

    /**
     * The subclass bits of each class.
     */
    public final int[] classSubs = new int[16];

    /**
     * The master book of each class.
     */
    public final int[] classBook = new int[16];

    /**
     * The subclass books of each class, {@code -1} for none.
     */
    public final int[][] classSubbook = new int[16][8];

    /**
     * The amplitude multiplier, 1 to 4.
     */
    public int mult;

    /**
     * The X positions of the posts; the first two are 0 and the range.
     */
    public final int[] postList = new int[65];

    /**
     * The largest fit error above the curve.
     */
    public float maxOver;

    /**
     * The largest fit error below the curve.
     */
    public float maxUnder;

    /**
     * The largest mean squared fit error.
     */
    public float maxError;

    /**
     * The weight of the second fit.
     */
    public float twoFitWeight;

    /**
     * The attenuation threshold of the second fit, in decibels.
     */
    public float twoFitAttenuation;

    /**
     * The number of spectral lines the fit covers.
     */
    public int n;

    /**
     * Creates an empty configuration.
     */
    public Floor1Info() {
    }

    /**
     * Returns a copy of this configuration.
     *
     * @return the copy
     */
    public Floor1Info copy() {
        var copy = new Floor1Info();
        copy.partitions = partitions;
        System.arraycopy(partitionClass, 0, copy.partitionClass, 0, 31);
        System.arraycopy(classDim, 0, copy.classDim, 0, 16);
        System.arraycopy(classSubs, 0, copy.classSubs, 0, 16);
        System.arraycopy(classBook, 0, copy.classBook, 0, 16);
        for (var i = 0; i < 16; i++) {
            System.arraycopy(classSubbook[i], 0, copy.classSubbook[i], 0, 8);
        }
        copy.mult = mult;
        System.arraycopy(postList, 0, copy.postList, 0, 65);
        copy.maxOver = maxOver;
        copy.maxUnder = maxUnder;
        copy.maxError = maxError;
        copy.twoFitWeight = twoFitWeight;
        copy.twoFitAttenuation = twoFitAttenuation;
        copy.n = n;
        return copy;
    }
}
