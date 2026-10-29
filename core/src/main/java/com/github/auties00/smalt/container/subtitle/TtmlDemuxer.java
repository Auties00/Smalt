package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.subtitle.Markup;
import com.github.auties00.smalt.internal.subtitle.TtmlDocument;
import com.github.auties00.smalt.internal.subtitle.TtmlWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Demuxes a TTML file (W3C TTML, DFXP, and the IMSC 1.0.1 and 1.1 profiles; {@code .ttml}, {@code .dfxp},
 * {@code .xml}) into one {@link Codec#TTML} track.
 *
 * <p>The file is read whole and parsed as a document at construction, so a malformed document fails here.
 * Its encoding is that of a byte order mark, else that of the XML declaration, else UTF-8. The track holds
 * one packet, the document itself, from zero to the end of its last cue in a millisecond time base, the
 * form an MP4 {@code stpp} sample stores, so that a remux into MP4 keeps the document unchanged; its
 * configuration is the {@code stpp} form of the namespaces the document declares. The cues of the document
 * come out of {@link com.github.auties00.smalt.subtitle.SubtitleDecoder#decodeAll(Packet)} and
 * {@link com.github.auties00.smalt.subtitle.SubtitleReader}. The document's {@code ttm:title} is the
 * {@code title} tag.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer.
 */
public final class TtmlDemuxer extends CueFileDemuxer {
    /**
     * The flags of the packet.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * Opens a TTML file with the default options.
     *
     * @param source the input
     * @throws MediaException.Malformed if the file is not a well-formed TTML document
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public TtmlDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Opens a TTML file.
     *
     * @param source the input, not closed by this demuxer
     * @param options the limits; the document's encoding comes from the document
     * @throws MediaException.Malformed if the file is not a well-formed TTML document
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public TtmlDemuxer(MediaSource source, DemuxerOptions options) {
        this(readBytes(source, options));
    }

    /**
     * Constructs a demuxer over a document.
     *
     * @param data the document bytes
     */
    private TtmlDemuxer(byte[] data) {
        this(data, TtmlDocument.parse(data));
    }

    /**
     * Constructs a demuxer over a parsed document.
     *
     * @param data the document bytes
     * @param document the parsed document
     */
    private TtmlDemuxer(byte[] data, TtmlDocument document) {
        super(track(document), List.of(packet(data, document)), cues(document), tags(document), document.endMicros());
    }

    /**
     * Returns the track of a document.
     *
     * @param document the parsed TTML document
     * @return the track
     */
    private static DataTrack track(TtmlDocument document) {
        return TtmlWriter.track(1).toBuilder().codecConfig(TtmlWriter.config(document.namespaces()))
                .duration((document.endMicros() + 999) / 1000).build();
    }

    /**
     * Returns the packet of a document.
     *
     * @param data the document bytes
     * @param document the parsed document
     * @return the packet spanning every cue
     */
    private static Packet packet(byte[] data, TtmlDocument document) {
        var duration = (document.endMicros() + 999) / 1000;
        return new Packet(1, data, 0, 0, duration, KEY, PacketSideData.NONE);
    }

    /**
     * Returns the cues of a document in SubRip markup.
     *
     * @param document the parsed TTML document
     * @return the cues in start order
     */
    private static List<SubtitleCue> cues(TtmlDocument document) {
        var result = new ArrayList<SubtitleCue>();
        for (var cue : document.cues()) {
            result.add(Markup.toCue(cue.text(), cue.startMicros(), cue.endMicros(), SubtitleMarkup.SUBRIP,
                    Markup.Context.NONE));
        }
        return result;
    }

    /**
     * Returns the container-level tags of a document.
     *
     * @param document the parsed TTML document
     * @return the {@code title} tag when the document has a title
     */
    private static Map<String, String> tags(TtmlDocument document) {
        return document.title().isBlank() ? Map.of() : Map.of("title", document.title());
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.TTML;
    }
}
