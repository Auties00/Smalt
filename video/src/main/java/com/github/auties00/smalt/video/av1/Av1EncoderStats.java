package com.github.auties00.smalt.video.av1;

/**
 * Holds the running totals of an {@link Av1Encoder} and the description of its last coded frame,
 * as returned by {@link Av1Encoder#stats()}.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param framesEncoded the number of coded frames
 * @param keyframesEncoded the number of those frames that are key frames
 * @param framesDropped the number of frames the rate control dropped
 * @param bytesEncoded the total size of the coded frames in bytes
 * @param lastFrameBytes the size of the last coded frame in bytes, 0 before the first frame
 * @param lastQuantizerIndex the base quantizer index of the last coded frame, from 0 to 255; the
 * index is on the scale of the AV1 bitstream, not on the 0 to 63 scale of
 * {@link Av1EncoderOptions#minQuantizer()} and {@link Av1EncoderOptions#maxQuantizer()}
 * @param lastKeyframe whether the last coded frame was a key frame
 * @param lastTemporalLayer the temporal layer of the last coded frame, 0 for the base layer
 * @param bufferLevelBits the fullness of the rate control's buffer model in bits; negative when the
 * encoder overshot the buffer
 */
public record Av1EncoderStats(long framesEncoded, long keyframesEncoded, long framesDropped, long bytesEncoded,
                              int lastFrameBytes, int lastQuantizerIndex, boolean lastKeyframe,
                              int lastTemporalLayer, long bufferLevelBits) {
}
