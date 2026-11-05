package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.subtitle.AssFile;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Demuxes a SubStation Alpha ({@code .ssa}, v4.00) or Advanced SubStation Alpha ({@code .ass}, v4.00+)
 * script into one {@link Codec#ASS} track.
 *
 * <p>The file is read whole and decoded like a SubRip file (a byte order mark, then the encoding of
 * {@link DemuxerOptions#charset()} or the one detected), and must start with a {@code [Script Info]}
 * section. The track configuration is the script header: every line but the {@code Dialogue} lines, so
 * the info fields, the styles, the event format, {@code Comment} events and any other section are kept
 * for a muxer to write back. Each {@code Dialogue} line becomes one packet in the Matroska block form,
 * {@code ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text}, its read order the
 * position of the event in the file and its layer 0 for an SSA event; the packets are returned in start
 * order, in a millisecond time base, as Matroska stores them. The script's {@code Title} is the
 * {@code title} tag.
 *
 * <p>Instances are not thread-safe. The source is not closed by the demuxer.
 */
public final class AssDemuxer extends CueFileDemuxer {
    /**
     * The flags of every packet.
     */
    private static final Set<PacketFlag> KEY = Set.of(PacketFlag.KEY);

    /**
     * Opens a script with the default options.
     *
     * @param source the input
     * @throws MediaException.Malformed if the file does not start with a {@code [Script Info]} section
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public AssDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Opens a script.
     *
     * @param source the input, not closed by this demuxer
     * @param options the limits and the text encoding
     * @throws MediaException.Malformed if the file does not start with a {@code [Script Info]} section
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public AssDemuxer(MediaSource source, DemuxerOptions options) {
        this(parse(readText(source, options)));
    }

    /**
     * Constructs a demuxer from a parsed script.
     *
     * @param parsed the script
     */
    private AssDemuxer(AssFile.Parsed parsed) {
        super(track(parsed), packets(parsed), cues(parsed), tags(parsed), end(parsed));
    }

    /**
     * Parses a script, checking its signature.
     *
     * @param text the file text
     * @return the parsed script
     * @throws MediaException.Malformed if the text does not start with a {@code [Script Info]} section
     */
    private static AssFile.Parsed parse(String text) {
        if (!AssFile.isSignature(text)) {
            throw new MediaException.Malformed("not an SSA or ASS script: it does not start with [Script Info]");
        }
        return AssFile.parse(text);
    }

    /**
     * Returns the track of a script.
     *
     * @param parsed the script
     * @return the track, its configuration the header
     */
    private static DataTrack track(AssFile.Parsed parsed) {
        var end = end(parsed);
        return SubtitleEncoder.track(Codec.ASS, 1).toBuilder()
                .codecConfig(parsed.header().getBytes(StandardCharsets.UTF_8))
                .duration(parsed.events().isEmpty() ? 0 : (end + 500) / 1000).build();
    }

    /**
     * Returns the events of a script in start order.
     *
     * @param parsed the script
     * @return the events, a stable sort keeping the file order of events that start together
     */
    private static List<AssFile.Timed> sorted(AssFile.Parsed parsed) {
        var events = new ArrayList<>(parsed.events());
        events.sort(Comparator.comparingLong(AssFile.Timed::startMicros));
        return events;
    }

    /**
     * Returns the packets of a script.
     *
     * @param parsed the script
     * @return the packets in start order
     */
    private static List<Packet> packets(AssFile.Parsed parsed) {
        var timeBase = Rational.MILLISECONDS;
        var result = new ArrayList<Packet>();
        for (var timed : sorted(parsed)) {
            var pts = Rational.MICROSECONDS.rescale(timed.startMicros(), timeBase);
            var duration = Math.max(0, Rational.MICROSECONDS.rescale(timed.endMicros(), timeBase) - pts);
            result.add(new Packet(1, timed.event().block().getBytes(StandardCharsets.UTF_8), pts, pts, duration, KEY,
                    PacketSideData.NONE));
        }
        return result;
    }

    /**
     * Returns the cues of a script.
     *
     * @param parsed the script
     * @return the cues in start order, their settings the event fields
     */
    private static List<SubtitleCue> cues(AssFile.Parsed parsed) {
        var result = new ArrayList<SubtitleCue>();
        for (var timed : sorted(parsed)) {
            result.add(new SubtitleCue(timed.startMicros(), timed.endMicros(), timed.event().text(), "",
                    timed.event().fields()));
        }
        return result;
    }

    /**
     * Returns the container-level tags of a script.
     *
     * @param parsed the script
     * @return the {@code title} tag when the script has a title
     */
    private static Map<String, String> tags(AssFile.Parsed parsed) {
        var title = parsed.script().info("Title");
        return title == null || title.isBlank() ? Map.of() : Map.of("title", title);
    }

    /**
     * Returns the end of the last event.
     *
     * @param parsed the script
     * @return the end in microseconds
     */
    private static long end(AssFile.Parsed parsed) {
        var end = 0L;
        for (var timed : parsed.events()) {
            end = Math.max(end, timed.endMicros());
        }
        return end;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.ASS;
    }
}
