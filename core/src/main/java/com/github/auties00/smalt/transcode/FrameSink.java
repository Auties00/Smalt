package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.video.FieldOrder;

/**
 * Receives the frames of one step of the video processing of a transcode, in presentation order.
 *
 * <p>Times are in the time base of the input track, counted from the start of the kept range. A
 * picture is valid only during the call that receives it; a step that keeps it copies it.
 */
interface FrameSink {
    /**
     * Receives one frame.
     *
     * @param picture the pixels, valid during the call
     * @param time the presentation time, zero or more, increasing from frame to frame
     * @param duration the display duration, positive
     * @param scan how the rows of the picture are scanned in time
     */
    void accept(Picture picture, long time, long duration, FieldOrder scan);

    /**
     * Receives the end of the stream, after which no frame arrives.
     *
     * @param end the end of the output timeline, at or after the end of the last frame received
     */
    void finish(long end);
}
