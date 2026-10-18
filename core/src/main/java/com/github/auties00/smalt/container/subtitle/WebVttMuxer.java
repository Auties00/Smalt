package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;

/**
 * Writes a WebVTT ({@code .vtt}) file from one {@link Codec#WEBVTT} track.
 *
 * <p>The file starts with the track configuration, the WebVTT header with its {@code STYLE},
 * {@code REGION} and {@code NOTE} blocks, or with a bare {@code WEBVTT} line when the track has
 * none. Each cue is written with its identifier and settings, timestamps rounded to the
 * millisecond; blank payload lines are dropped and an arrow in a payload is escaped. Consecutive
 * packets with the same payload, identifier and settings that follow each other without a gap
 * become one cue. The file is UTF-8 with line feeds, written in order, so a streaming sink works.
 *
 * <p>Instances are not thread-safe.
 */
public final class WebVttMuxer extends CueFileMuxer {
    /**
     * Constructs a muxer writing to a sink from its current position.
     *
     * @param output the sink; not closed by the muxer
     */
    public WebVttMuxer(MediaSink output) {
        super(Codec.WEBVTT, output);
    }

    /**
     * Returns whether the muxer accepts tracks of a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#WEBVTT} only
     */
    public static boolean supports(Codec codec) {
        return codec == Codec.WEBVTT;
    }

    @Override
    String header(Track track) {
        var config = track == null ? null
                : track.codecConfig().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
        return WebVttText.header(config) + "\n\n";
    }

    @Override
    String format(SubtitleCue cue) {
        return WebVttText.format(cue);
    }
}
