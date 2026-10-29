package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

/**
 * Counts the decoder model times of a stream's frame headers: each frame's removal from the
 * decoder buffer, in decoding ticks since the last key frame, and each shown frame's presentation,
 * in display ticks since the last key frame's removal.
 *
 * <p>A decoding tick is one frame interval, so the frames since a key frame are removed one per
 * tick in decoding order. A shown frame is presented its timestamp's distance from the key frame's
 * timestamp after that key frame's removal, plus {@link StreamTiming#DISPLAY_DELAY_FRAMES} frame
 * intervals, which leaves time to decode the frames a pyramid codes ahead of their display.
 *
 * <p>An instance belongs to one encoder and is used by one thread at a time.
 */
final class FrameTiming {
    /**
     * The stream's timing.
     */
    private final StreamTiming stream;

    /**
     * The timestamp of the last key frame.
     */
    private long keyPts;

    /**
     * The frames decoded since the last key frame, the key frame included.
     */
    private long decoded;

    /**
     * Creates the counts of a stream.
     *
     * @param stream the stream's timing
     */
    FrameTiming(StreamTiming stream) {
        this.stream = stream;
    }

    /**
     * Sets the decoder model times of a frame header that is about to be coded; does nothing
     * unless the sequence signals the decoder model.
     *
     * @param h the frame header, with its type, shown flag and temporal layer set
     * @param seq the sequence header
     * @param pts the timestamp of the frame, in time base units
     */
    void fill(FrameHeader h, SequenceHeader seq, long pts) {
        // TODO: check the times against the decoder buffer model of the stream's level (Annex E) and
        // adapt the buffer delays to the coded sizes; they follow a fixed schedule
        h.bufferRemovalTimePresent = seq.decoderModelInfoPresent;
        if (!seq.decoderModelInfoPresent) {
            return;
        }
        var key = h.frameType == FrameHeader.KEY;
        var removal = key ? 0 : decoded;
        for (var i = 0; i < seq.numOperatingPoints; i++) {
            h.bufferRemovalTime[i] = removal & ((1L << StreamTiming.REMOVAL_TIME_BITS) - 1);
        }
        h.framePresentationTime = presentation(seq, key ? pts : keyPts, pts);
    }

    /**
     * Returns the presentation time of a frame a show-existing header shows.
     *
     * @param seq the sequence header
     * @param pts the timestamp of the frame, in time base units
     * @return the presentation time, or 0 unless the sequence signals the decoder model
     */
    long presentation(SequenceHeader seq, long pts) {
        return seq.decoderModelInfoPresent ? presentation(seq, keyPts, pts) : 0;
    }

    /**
     * Returns the presentation time of a frame after a key frame.
     *
     * @param seq the sequence header
     * @param keyPts the timestamp of the key frame
     * @param pts the timestamp of the frame
     * @return the presentation time in display ticks, wrapped to its field
     */
    private long presentation(SequenceHeader seq, long keyPts, long pts) {
        var ticks = pts - keyPts + StreamTiming.DISPLAY_DELAY_FRAMES * stream.frameTicks();
        return ticks & ((1L << seq.framePresentationDelayLength) - 1);
    }

    /**
     * Counts a coded frame.
     *
     * @param key whether the frame is a key frame
     * @param pts the timestamp of the frame
     */
    void advance(boolean key, long pts) {
        if (key) {
            keyPts = pts;
            decoded = 0;
        }
        decoded++;
    }
}
