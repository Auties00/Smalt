package com.github.auties00.smalt.container;

import com.github.auties00.smalt.io.MediaSink;

/**
 * Receives the segments of a segmented output, one sink per segment.
 *
 * <p>Muxers that cut their output into segments, for adaptive streaming, call {@link #open(Segment)} once
 * per segment, in order, from the thread that writes to the muxer.
 */
@FunctionalInterface
public interface SegmentSink {
    /**
     * Returns the sink a segment is written to.
     *
     * <p>The muxer writes the whole segment, {@link Segment#size()} bytes, to the returned sink and then
     * closes it.
     *
     * @param segment the description of the segment about to be written
     * @return the sink
     */
    MediaSink open(Segment segment);
}
