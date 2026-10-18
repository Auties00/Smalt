package com.github.auties00.smalt.video.vp8;

/**
 * Holds the counters of a {@link Vp8Encoder} since it was created, as returned by
 * {@link Vp8Encoder#stats()}.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param framesEncoded the number of frames coded into packets
 * @param keyframesEncoded the number of those frames that are key frames
 * @param framesDropped the number of frames the rate control dropped
 * @param bytesEncoded the total size of the packets in bytes
 * @param lastQuantizerIndex the quantizer index of the last coded frame, from 0 to 127, or 0
 * before the first
 * @param lastTemporalLayer the temporal layer of the last frame given to the encoder, coded or
 * dropped, from 0 to {@link Vp8EncoderOptions#temporalLayers()} minus 1, or 0 before the first
 * @param bufferLevelBits the rate control's buffer level in bits; negative when the stream spent
 * more than the channel delivered
 */
public record Vp8EncoderStats(long framesEncoded, long keyframesEncoded, long framesDropped, long bytesEncoded,
                              int lastQuantizerIndex, int lastTemporalLayer, long bufferLevelBits) {
}
