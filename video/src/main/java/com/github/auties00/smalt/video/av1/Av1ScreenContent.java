package com.github.auties00.smalt.video.av1;

/**
 * Whether an {@link Av1Encoder} codes with the AV1 screen content tools: palette prediction, which
 * codes a block as a few colors and a map of them, and, in the good-quality usage, whole-sample
 * motion for frames that hold sharp, synthetic content.
 *
 * <p>A stream that may use the tools spends one bit per frame header on whether a frame uses
 * them; {@link #NEVER} spares it.
 */
public enum Av1ScreenContent {
    /**
     * Decides at each key frame of the intra-only and good-quality usages from the picture: the
     * tools apply until the next key frame when more than a tenth of the picture's 16x16 luma
     * blocks hold two to four distinct values, as text, user interfaces and slides do. The
     * intra-only speeds {@link Av1EncoderSpeed#FASTER} and {@link Av1EncoderSpeed#FASTEST}, which
     * search no palette, and the real-time usage, whose fast mode decision tests no screen
     * content, code no frame with the tools.
     */
    DETECT,

    /**
     * Codes every frame with the tools, for screen sharing and remote desktops.
     */
    ALWAYS,

    /**
     * Codes no frame with the tools, for camera content.
     */
    NEVER
}
