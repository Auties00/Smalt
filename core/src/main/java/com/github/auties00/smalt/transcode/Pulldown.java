package com.github.auties00.smalt.transcode;

/**
 * Enumerates the field pulldown a transcode applies or removes: the 2:3 cadence that shows film
 * frames at the field rate of interlaced video.
 *
 * <p>With 2:3 pulldown, four film frames {@code A B C D} take ten fields, two, three, two and three
 * of them, which pair into the five interlaced frames {@code AA BB BC CD DD} (top field first): 24
 * frames per second become 30, and 24000/1001 become 30000/1001.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Pulldown {
    /**
     * Applies no pulldown.
     */
    NONE,

    /**
     * Applies 2:3 pulldown to the output: the progressive frames, at the output frame rate (the
     * source rate unless {@link VideoTranscodeOptions#frameRate()} sets one), become interlaced
     * frames at five fourths of it, top field first, the cadence starting with the first frame. The
     * output track declares an interlaced, top field first scan.
     */
    TELECINE,

    /**
     * Removes 2:3 pulldown from the source (inverse telecine): each frame's first field is paired
     * with the second field of the same, the previous or the next frame, whichever pairs with the
     * least combing, and of every five frames so rebuilt the one most like the frame before it is
     * dropped. The output has four fifths of the source rate, 24000/1001 frames per second from
     * 30000/1001, and a progressive scan; with a {@link VideoTranscodeOptions#deinterlacer()}, the
     * rebuilt frames still combed after matching are deinterlaced.
     */
    INVERSE_TELECINE
}
