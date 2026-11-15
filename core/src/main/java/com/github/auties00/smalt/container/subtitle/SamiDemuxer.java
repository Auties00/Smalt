package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.subtitle.SamiText;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * Demuxes a SAMI ({@code .smi}) file into one {@link Codec#SUBRIP} track per language class.
 *
 * <p>The file is read whole and decoded in the encoding of a byte order mark, else of
 * {@link DemuxerOptions#charset()}, else of a {@code charset} its head declares, else the one detected; it
 * must start with a {@code SAMI} tag. Each language class of the style block that holds text becomes a
 * track, in declaration order, its language the ISO 639-2 code of the class's {@code lang} and its
 * {@code Name} the track's {@code title} tag; paragraphs without a class form a track of their own. Each
 * packet is one cue, the SAMI markup turned into SubRip markup, in a millisecond time base. The head's
 * {@code TITLE} is the {@code title} tag.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer.
 */
public final class SamiDemuxer extends CueFileDemuxer {
    /**
     * Opens a SAMI file with the default options.
     *
     * @param source the input
     * @throws MediaException.Malformed if the file does not start with a {@code SAMI} tag
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public SamiDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Opens a SAMI file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the limits and the text encoding
     * @throws MediaException.Malformed if the file does not start with a {@code SAMI} tag
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public SamiDemuxer(MediaSource source, DemuxerOptions options) {
        this(parse(readBytes(source, options), options));
    }

    /**
     * Constructs a demuxer from a parsed file.
     *
     * @param parsed the file
     */
    private SamiDemuxer(SamiText.Parsed parsed) {
        super(streams(parsed), parsed.title().isBlank() ? Map.of() : Map.of("title", parsed.title()));
    }

    /**
     * Decodes and parses a file.
     *
     * @param data the file bytes
     * @param options the text encoding
     * @return the parsed file
     * @throws MediaException.Malformed if the file does not start with a {@code SAMI} tag
     */
    private static SamiText.Parsed parse(byte[] data, DemuxerOptions options) {
        var charset = options.charset().orElse(null);
        if (charset == null && TextFiles.byteOrderMark(data) == null) {
            charset = SamiText.declaredCharset(data);
        }
        var text = TextFiles.decode(data, charset);
        if (!SamiText.isSignature(text)) {
            throw new MediaException.Malformed("not a SAMI file: it does not start with a SAMI tag");
        }
        return SamiText.parse(text);
    }

    /**
     * Returns the tracks of a parsed file.
     *
     * @param parsed the file
     * @return the tracks and their cues
     */
    private static List<Stream> streams(SamiText.Parsed parsed) {
        var streams = new ArrayList<Stream>();
        var id = 1;
        for (var track : parsed.tracks()) {
            var builder = SubtitleEncoder.track(Codec.SUBRIP, id++).toBuilder();
            var language = language(track.language());
            if (language != null) {
                builder.language(language);
            }
            var tags = track.title().isBlank() ? Map.<String, String>of() : Map.of("title", track.title());
            streams.add(new Stream(builder.build(), track.cues(), tags));
        }
        return streams;
    }

    /**
     * Converts a {@code lang} value to an ISO 639-2 code.
     *
     * @param tag the language tag, such as {@code en-US} or {@code ko}
     * @return the three-letter code, or {@code null} when the tag names no known language
     */
    private static String language(String tag) {
        if (tag.isBlank()) {
            return null;
        }
        try {
            var code = Locale.forLanguageTag(tag.strip().replace('_', '-')).getISO3Language();
            return code.isEmpty() ? null : code;
        } catch (MissingResourceException exception) {
            return null;
        }
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.SAMI;
    }
}
