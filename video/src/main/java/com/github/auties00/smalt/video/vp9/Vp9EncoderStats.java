package com.github.auties00.smalt.video.vp9;

/**
 * Reports the activity of a {@link Vp9Encoder} since it was created, as returned by
 * {@link Vp9Encoder#stats()}.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param framesEncoded the number of coded frames
 * @param keyframesEncoded the number of those frames that are key frames
 * @param framesDropped the number of frames the rate control dropped
 * @param bytesEncoded the total size of the coded frames in bytes
 * @param lastQuantizerIndex the quantizer index of the last coded frame, from 0 to 255, or 0 before
 * the first; the index is on the scale of the VP9 bitstream, not on the 0 to 63 scale of
 * {@link Vp9EncoderOptions#minQuantizer()} and {@link Vp9EncoderOptions#maxQuantizer()}
 * @param lastTemporalLayer the temporal layer of the last frame given to the encoder, coded or
 * dropped, from 0 to {@link Vp9EncoderOptions#temporalLayers()} minus 1, or 0 before the first
 * @param bufferLevelBits the rate control's buffer level in bits; negative when the stream spent
 * more than the channel delivered
 */
public record Vp9EncoderStats(long framesEncoded, long keyframesEncoded, long framesDropped, long bytesEncoded,
                              int lastQuantizerIndex, int lastTemporalLayer, long bufferLevelBits) {
}
