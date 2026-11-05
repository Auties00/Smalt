package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.subtitle.LrcText;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.track.Codec;

import java.util.List;
import java.util.Map;

/**
 * Demuxes an LRC ({@code .lrc}) lyrics file into one {@link Codec#WEBVTT} track.
 *
 * <p>The file is read whole and decoded like a SubRip file (a byte order mark, then the encoding of
 * {@link DemuxerOptions#charset()} or the one detected), and must start with a time tag or an ID tag. Each
 * packet is one cue: the lines shown from one time until the next, the word times of the enhanced format
 * as WebVTT timestamp tags, in a millisecond time base. The ID tags are the container tags, {@code ti},
 * {@code ar} and {@code al} under {@code title}, {@code artist} and {@code album} and the others under
 * their own names.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer.
 */
public final class LrcDemuxer extends CueFileDemuxer {
    /**
     * Opens an LRC file with the default options.
     *
     * @param source the input
     * @throws MediaException.Malformed if the file does not start with an LRC tag
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public LrcDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Opens an LRC file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the limits and the text encoding
     * @throws MediaException.Malformed if the file does not start with an LRC tag
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public LrcDemuxer(MediaSource source, DemuxerOptions options) {
        this(parse(readText(source, options)));
    }

    /**
     * Constructs a demuxer from a parsed file.
     *
     * @param parsed the file
     */
    private LrcDemuxer(LrcText.Parsed parsed) {
        super(List.of(new Stream(SubtitleEncoder.track(Codec.WEBVTT, 1), parsed.cues(), Map.of())), parsed.tags());
    }

    /**
     * Parses a file, checking its signature.
     *
     * @param text the file text
     * @return the parsed file
     * @throws MediaException.Malformed if the text does not start with an LRC tag
     */
    private static LrcText.Parsed parse(String text) {
        if (!LrcText.isSignature(text)) {
            throw new MediaException.Malformed("not an LRC file: it does not start with a time or ID tag");
        }
        return LrcText.parse(text);
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.LRC;
    }
}
