package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;

import java.nio.charset.StandardCharsets;

/**
 * Demuxes a WebVTT ({@code .vtt}) file into one {@link Codec#WEBVTT} track.
 *
 * <p>The file is read whole and decoded like a SubRip file (a byte order mark, then UTF-8, then
 * Windows-1252), and must start with the {@code WEBVTT} signature. The track configuration is the
 * file header: the {@code WEBVTT} block and the {@code STYLE}, {@code REGION} and {@code NOTE}
 * blocks before the first cue. Each packet is one cue payload, with the cue identifier and
 * settings as side data, in a millisecond time base; cues are returned in start order.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer.
 */
public final class WebVttDemuxer extends CueFileDemuxer {
    /**
     * Opens a WebVTT file under the default limits.
     *
     * @param source the input
     * @throws MediaException.Malformed if the file does not start with the WebVTT signature
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public WebVttDemuxer(MediaSource source) {
        this(source, MediaLimits.DEFAULT);
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.Malformed if the file does not start with the WebVTT signature
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public WebVttDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private WebVttDemuxer(MediaSource source, MediaLimits limits) {
        this(WebVttText.parse(readText(source, limits)));
    }

    /**
     * Constructs a demuxer from a parsed document.
     *
     * @param document the parsed file
     */
    private WebVttDemuxer(WebVttText.Document document) {
        super(Codec.WEBVTT, document.header().getBytes(StandardCharsets.UTF_8), document.cues());
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.WEBVTT;
    }
}
