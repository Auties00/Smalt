package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.internal.subtitle.SubRipText;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;

/**
 * Writes a SubRip ({@code .srt}) file from one {@link Codec#SUBRIP} track.
 *
 * <p>Cues are numbered from 1 in the order they are written, with timestamps rounded to the
 * millisecond; blank lines in a cue text, which would end the cue early, are dropped. Consecutive
 * packets with the same text that follow each other without a gap become one cue. The file is
 * UTF-8 with line feeds, written in order, so a streaming sink works.
 *
 * <p>Instances are not thread-safe.
 */
public final class SubRipMuxer extends CueFileMuxer {
    /**
     * The number of the next cue.
     */
    private long counter = 1;

    /**
     * Constructs a muxer writing to a sink from its current position.
     *
     * @param output the sink; not closed by the muxer
     */
    public SubRipMuxer(MediaSink output) {
        super(Codec.SUBRIP, output);
    }

    /**
     * Returns whether the muxer accepts tracks of a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#SUBRIP} only
     */
    public static boolean supports(Codec codec) {
        return codec == Codec.SUBRIP;
    }

    @Override
    String header(Track track) {
        return "";
    }

    @Override
    String format(SubtitleCue cue) {
        return SubRipText.format(counter++, cue);
    }
}
