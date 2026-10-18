package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Holds the stream-wide psychoacoustic parameters: block switching thresholds, the amplitude
 * tracker and the point stereo settings of each packet blob.
 *
 * @implNote This implementation mirrors libvorbis's {@code vorbis_info_psy_global} (BSD-3).
 */
public final class PsyGlobal {
    /**
     * The number of envelope bands.
     */
    public static final int ENVELOPE_BANDS = 7;

    /**
     * The number of packet blobs of bitrate management.
     */
    public static final int PACKET_BLOBS = 15;

    /**
     * The eighth-octave resolution of the tone masking curves.
     */
    public int eighthOctaveLines;

    /**
     * The pre-echo trigger threshold of each envelope band.
     */
    public final float[] preEchoThreshold = new float[ENVELOPE_BANDS];

    /**
     * The post-echo trigger threshold of each envelope band.
     */
    public final float[] postEchoThreshold = new float[ENVELOPE_BANDS];

    /**
     * The penalty applied while the envelope stretches.
     */
    public float stretchPenalty;

    /**
     * The smallest energy that can trigger pre-echo detection.
     */
    public float preEchoMinEnergy;

    /**
     * The decay of the global amplitude maximum, in decibels per second.
     */
    public float ampmaxAttenuationPerSecond;

    /**
     * The point stereo frequency of each blob, in kilohertz.
     */
    public final int[] couplingKhz = new int[PACKET_BLOBS];

    /**
     * The point stereo limit of each blob, in lines, for short (0) and long (1) blocks.
     */
    public final int[][] couplingPointLimit = new int[2][PACKET_BLOBS];

    /**
     * The pre-point lossless coupling amplitude index of each blob.
     */
    public final int[] couplingPrePointAmp = new int[PACKET_BLOBS];

    /**
     * The post-point lossless coupling amplitude index of each blob.
     */
    public final int[] couplingPostPointAmp = new int[PACKET_BLOBS];

    /**
     * The lowpass of each blob, in lines, for short (0) and long (1) blocks.
     */
    public final int[][] slidingLowpass = new int[2][PACKET_BLOBS];

    /**
     * Creates parameters with every field zero.
     */
    public PsyGlobal() {
    }

    /**
     * Returns a copy of these parameters.
     *
     * @return the copy
     */
    public PsyGlobal copy() {
        var copy = new PsyGlobal();
        copy.eighthOctaveLines = eighthOctaveLines;
        System.arraycopy(preEchoThreshold, 0, copy.preEchoThreshold, 0, ENVELOPE_BANDS);
        System.arraycopy(postEchoThreshold, 0, copy.postEchoThreshold, 0, ENVELOPE_BANDS);
        copy.stretchPenalty = stretchPenalty;
        copy.preEchoMinEnergy = preEchoMinEnergy;
        copy.ampmaxAttenuationPerSecond = ampmaxAttenuationPerSecond;
        System.arraycopy(couplingKhz, 0, copy.couplingKhz, 0, PACKET_BLOBS);
        for (var i = 0; i < 2; i++) {
            System.arraycopy(couplingPointLimit[i], 0, copy.couplingPointLimit[i], 0, PACKET_BLOBS);
            System.arraycopy(slidingLowpass[i], 0, copy.slidingLowpass[i], 0, PACKET_BLOBS);
        }
        System.arraycopy(couplingPrePointAmp, 0, copy.couplingPrePointAmp, 0, PACKET_BLOBS);
        System.arraycopy(couplingPostPointAmp, 0, copy.couplingPostPointAmp, 0, PACKET_BLOBS);
        return copy;
    }
}
