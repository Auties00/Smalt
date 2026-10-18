package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.internal.subtitle.SubRipText;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;

/**
 * Demuxes a SubRip ({@code .srt}) file into one {@link Codec#SUBRIP} track.
 *
 * <p>The file is read whole and decoded as UTF-8 or UTF-16 when it starts with a byte order mark,
 * as UTF-8 when it is valid UTF-8, and as Windows-1252 otherwise; line breaks may be {@code CRLF},
 * {@code LF} or {@code CR}. Cues are found by their timing lines, so wrong or missing counters,
 * missing blank lines between cues and blank lines inside a cue are tolerated, and they are
 * returned in start order whatever their order in the file. Each packet is one cue, its text with
 * the SubRip markup kept, in a millisecond time base.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer.
 */
public final class SubRipDemuxer extends CueFileDemuxer {
    /**
     * Opens a SubRip file under the default limits.
     *
     * @param source the input
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public SubRipDemuxer(MediaSource source) {
        this(source, MediaLimits.DEFAULT);
    }

    /**
     * Constructs a demuxer over {@code source} that enforces the limits of {@code options}.
     *
     * @param source the input, not closed by this demuxer
     * @param options the demuxing options
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public SubRipDemuxer(MediaSource source, DemuxerOptions options) {
        this(source, options.limits());
    }

    private SubRipDemuxer(MediaSource source, MediaLimits limits) {
        super(Codec.SUBRIP, null, SubRipText.parse(readText(source, limits)));
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.SUBRIP;
    }
}
