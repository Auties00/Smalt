package com.github.auties00.smalt.transcode;

/**
 * Enumerates how a transcode handles a video stream whose samples are not square (anamorphic
 * video, whose {@link com.github.auties00.smalt.track.VideoTrack#sampleAspectRatio() sample aspect
 * ratio} stretches the pictures for display).
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
 * {@code default} branch.
 */
public enum SampleAspectMode {
    /**
     * Keeps the samples as stored when the output container records a sample aspect ratio
     * ({@link OutputContainer#storesSampleAspectRatio()}), and makes them square otherwise; a
     * copied stream is always kept as stored.
     */
    AUTO,

    /**
     * Keeps the samples as stored: the box and the edge limits apply to the stored size, and the
     * output declares the sample aspect ratio that keeps the displayed shape, scaled by any uneven
     * scaling; square samples stay square, so a resize that changes the shape of square-sampled
     * video changes its displayed shape too. For a container that cannot record one, the ratio is
     * lost and the video plays stretched or squeezed.
     */
    PRESERVE,

    /**
     * Resamples the pictures to square samples: a picture {@code width} samples wide becomes
     * {@code round(width * sampleAspectRatio)} pixels wide before the box and the edge limits
     * apply, and the output declares no sample aspect ratio.
     */
    APPLY
}
