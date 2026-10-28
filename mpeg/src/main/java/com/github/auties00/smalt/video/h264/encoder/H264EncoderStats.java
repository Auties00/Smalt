package com.github.auties00.smalt.video.h264.encoder;

/**
 * Holds the counters of an {@link H264Encoder}, as returned by {@link H264Encoder#stats()}.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param framesEncoded the number of frames coded so far, one packet each; frames the encoder still
 * holds for its {@linkplain H264EncoderOptions#lookahead() look-ahead} are not counted
 * @param keyframesEncoded the number of IDR access units produced
 * @param keyframeRequests the number of {@link H264Encoder#requestKeyframe()} calls received
 * @param bytesEncoded the total size of the packets produced, in bytes
 * @param lastQuantizer the frame quantizer of the last frame, from 0 to 51, or 0 before the first
 * frame
 * @param targetBitrate the bitrate rate control currently aims at, in bits per second, after the
 * last {@link H264Encoder#setTargetBitrate(long)} that reached a frame boundary, or 0 for constant
 * quality
 * @param lastTemporalLayer the temporal layer of the last packet, from 0 to
 * {@link H264EncoderOptions#temporalLayers()} minus 1, or 0 before the first packet
 */
public record H264EncoderStats(long framesEncoded, long keyframesEncoded, long keyframeRequests, long bytesEncoded,
                               int lastQuantizer, long targetBitrate, int lastTemporalLayer) {
}
