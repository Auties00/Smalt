package com.github.auties00.smalt.video.hevc.encoder;

/**
 * Holds the counters of an {@link HevcEncoder}, as returned by {@link HevcEncoder#stats()}.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param framesEncoded the number of frames coded so far, one packet each; frames the encoder still holds to
 * code them out of order are not counted
 * @param keyframesEncoded the number of IDR access units produced
 * @param keyframeRequests the number of {@link HevcEncoder#requestKeyframe()} calls received
 * @param bytesEncoded the total size of the packets produced, in bytes
 * @param lastQuantizer the slice quantizer of the last frame, from 0 to 51, or 0 before the first frame
 * @param targetBitrate the bitrate rate control currently aims at, in bits per second, after the last
 * {@link HevcEncoder#setTargetBitrate(long)} that reached a frame boundary, or 0 for constant quality
 */
public record HevcEncoderStats(long framesEncoded, long keyframesEncoded, long keyframeRequests, long bytesEncoded,
                               int lastQuantizer, long targetBitrate) {
}
