package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

/**
 * Turns the packets of one input track into the packets of one output track.
 *
 * <p>A stage receives its track's packets in decoding order through {@link #accept(Packet)} and
 * the end of the input through {@link #finish()}; it writes to the shared {@link OutputMuxer}.
 * Times are on the output timeline, which starts at zero at the start of the kept range.
 *
 * <p>An instance is not thread-safe.
 */
sealed interface Stage extends TrackReceiver, AutoCloseable
        permits VideoEncodeStage, AudioEncodeStage, CopyStage, SubtitleStage, CaptionStage {
    /**
     * Drains the stage once the input ended or every stage is done.
     */
    void finish();

    /**
     * Returns how much of the output timeline the stage wrote.
     *
     * @return the output time in microseconds
     */
    long positionMicros();

    /**
     * Describes the output stream.
     *
     * @return the stream, once the output track is declared
     */
    TranscodedStream result();

    /**
     * Releases the stage's decoders and encoders.
     */
    @Override
    void close();
}
