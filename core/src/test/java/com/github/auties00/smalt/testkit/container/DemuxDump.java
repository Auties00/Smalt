package com.github.auties00.smalt.testkit.container;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ChapterEdition;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.testkit.Dumps;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the readable text form of a demux: the container header (format, duration, seekability,
 * metadata, chapters with their localized titles and nested chapters, the chapter editions when the container
 * stores more than one plain edition, attached pictures and other attachments, the timecode), one block per track, and one line per packet with its track,
 * timestamps, duration, flags, size, side data and the CRC-32 of its payload, in the spirit of FFmpeg's
 * {@code framecrc} and Media3's extractor dumps.
 *
 * <p>The text depends only on what the demuxer reports, never on identity hashes or the iteration order
 * of hash maps, so two demuxes of the same input compare equal as strings.
 */
public final class DemuxDump {
    private DemuxDump() {
        throw new AssertionError();
    }

    /**
     * Describes the container-level properties and the tracks of a demuxer, without its packets.
     *
     * @param demuxer the demuxer, positioned anywhere
     * @return the header lines
     */
    public static String header(Demuxer demuxer) {
        var text = new StringBuilder();
        text.append("format ").append(demuxer.format()).append('\n');
        text.append("duration ").append(demuxer.durationMicros()).append('\n');
        text.append("seekable ").append(demuxer.isSeekable()).append('\n');
        appendMap(text, "metadata", "", demuxer.metadata());
        for (var chapter : demuxer.chapters()) {
            appendChapter(text, "", chapter);
        }
        var chapters = demuxer.chapters();
        var editions = demuxer.chapterEditions();
        if (!editions.equals(chapters.isEmpty() ? List.of() : List.of(ChapterEdition.of(chapters)))) {
            for (var edition : editions) {
                text.append("edition default=").append(edition.defaultEdition()).append(" ordered=")
                        .append(edition.ordered());
                for (var name : edition.names()) {
                    text.append(' ').append(name.language()).append(':').append(quote(name.text()));
                }
                text.append('\n');
                for (var chapter : edition.chapters()) {
                    appendChapter(text, "  ", chapter);
                }
            }
        }
        for (var picture : demuxer.attachedPictures()) {
            text.append("picture ").append(picture.type()).append(' ').append(picture.mediaType()).append(' ')
                    .append(quote(picture.description())).append(" size=").append(picture.size()).append(" crc=")
                    .append(Dumps.crc32(picture.data())).append('\n');
        }
        for (var attachment : demuxer.attachments()) {
            text.append("attachment ").append(quote(attachment.fileName())).append(' ').append(attachment.mediaType())
                    .append(' ').append(quote(attachment.description())).append(" size=").append(attachment.size())
                    .append(" crc=").append(Dumps.crc32(attachment.data())).append('\n');
        }
        demuxer.timecode().ifPresent(timecode -> text.append("timecode ").append(timecode).append(" fps=")
                .append(timecode.framesPerSecond()).append('\n'));
        for (var track : demuxer.tracks()) {
            appendTrack(text, track);
            appendMap(text, "metadata", "  ", demuxer.trackMetadata(track.id()));
        }
        return text.toString();
    }

    /**
     * Describes one packet on one line.
     *
     * @param packet the packet
     * @return the line, without a line terminator
     */
    public static String packet(Packet packet) {
        var line = new StringBuilder();
        line.append("t").append(packet.trackId())
                .append(" pts=").append(timestamp(packet.pts()))
                .append(" dts=").append(timestamp(packet.dts()))
                .append(" dur=").append(packet.duration() == Packet.UNKNOWN_DURATION ? "?" : packet.duration())
                .append(" flags=").append(flags(packet))
                .append(" size=").append(packet.size())
                .append(" crc=").append(Dumps.crc32(packet.data()));
        var side = packet.sideData();
        if (!side.isEmpty()) {
            line.append(" side=").append(sideData(side));
        }
        return line.toString();
    }

    /**
     * Describes packets, one numbered line each.
     *
     * @param packets the packets, in demuxing order
     * @return the lines
     */
    public static String packets(List<Packet> packets) {
        var text = new StringBuilder();
        text.append("packets ").append(packets.size()).append('\n');
        for (var i = 0; i < packets.size(); i++) {
            text.append(i).append(' ').append(packet(packets.get(i))).append('\n');
        }
        return text.toString();
    }

    /**
     * Describes each packet on one line without numbering, for comparisons that report the first
     * differing packet.
     *
     * @param packets the packets
     * @return one line per packet
     */
    public static List<String> lines(List<Packet> packets) {
        var lines = new ArrayList<String>(packets.size());
        for (var packet : packets) {
            lines.add(packet(packet));
        }
        return lines;
    }

    private static void appendChapter(StringBuilder text, String indent, Chapter chapter) {
        text.append(indent).append("chapter ").append(chapter.startMicros()).append(' ').append(chapter.endMicros())
                .append(chapter.title().map(title -> " " + quote(title)).orElse(""));
        for (var title : chapter.titles()) {
            text.append(' ').append(title.language()).append(':').append(quote(title.text()));
        }
        text.append('\n');
        for (var child : chapter.children()) {
            appendChapter(text, indent + "  ", child);
        }
    }

    private static void appendTrack(StringBuilder text, Track track) {
        text.append("track ").append(track.id()).append(' ').append(track.kind()).append(' ').append(track.codec())
                .append(" timebase=").append(rational(track.timeBase()))
                .append(" duration=").append(track.duration() == Track.UNKNOWN_DURATION ? "?" : track.duration());
        track.language().ifPresent(language -> text.append(" language=").append(language));
        var config = track.codecConfig();
        text.append(" config=").append(config.map(bytes -> bytes.length + ":" + Dumps.crc32(bytes)).orElse("none"))
                .append('\n');
        switch (track) {
            case VideoTrack video -> {
                text.append("  video ").append(video.width()).append('x').append(video.height())
                        .append(" orientation=").append(video.orientation());
                video.frameRate().ifPresent(rate -> text.append(" rate=").append(rational(rate)));
                video.sampleAspectRatio().ifPresent(sar -> text.append(" sar=").append(rational(sar)));
                video.interlacing().ifPresent(interlacing -> text.append(" interlacing=").append(interlacing));
                video.stereoMode().ifPresent(mode -> text.append(" stereo=").append(mode));
                if (video.hasAlpha()) {
                    text.append(" alpha");
                }
                text.append('\n');
                video.spherical().ifPresent(spherical -> text.append("  spherical ").append(spherical.projection())
                        .append(" yaw=").append(spherical.yaw()).append(" pitch=").append(spherical.pitch())
                        .append(" roll=").append(spherical.roll()).append(" source=")
                        .append(quote(spherical.metadataSource())).append('\n'));
                video.color().ifPresent(color -> text.append("  color ").append(color).append('\n'));
                video.dolbyVision().ifPresent(configuration -> text.append("  dolbyVision ").append(configuration)
                        .append('\n'));
            }
            case AudioTrack audio -> {
                text.append("  audio rate=").append(audio.sampleRate()).append(" bits=").append(audio.bitsPerSample())
                        .append(" skip=").append(audio.skipSamples());
                audio.layout().ifPresent(layout -> {
                    text.append(" layout=").append(layout.speakers());
                    layout.ambisonics().ifPresent(ambisonics -> text.append(" ambisonics=")
                            .append(ambisonics.normalization()).append(ambisonics.components()));
                });
                text.append('\n');
            }
            case DataTrack ignored -> {
            }
        }
    }

    private static void appendMap(StringBuilder text, String label, String indent, Map<String, String> map) {
        for (var entry : new TreeMap<>(map).entrySet()) {
            text.append(indent).append(label).append(' ').append(entry.getKey()).append('=')
                    .append(quote(entry.getValue())).append('\n');
        }
    }

    private static String flags(Packet packet) {
        if (packet.flags().isEmpty()) {
            return "-";
        }
        var names = new StringBuilder();
        for (var flag : PacketFlag.values()) {
            if (packet.flags().contains(flag)) {
                if (!names.isEmpty()) {
                    names.append(',');
                }
                names.append(flag);
            }
        }
        return names.toString();
    }

    private static String sideData(PacketSideData side) {
        var text = new StringBuilder();
        text.append('[');
        if (side.discardStart() != 0 || side.discardEnd() != 0) {
            text.append("discard=").append(side.discardStart()).append('/').append(side.discardEnd());
        }
        side.newCodecConfig().ifPresent(config -> separate(text).append("config=").append(config.length).append(':')
                .append(Dumps.crc32(config)));
        side.hdr10Plus().ifPresent(hdr -> separate(text).append("hdr10plus=").append(hdr.length).append(':')
                .append(Dumps.crc32(hdr)));
        side.alphaFrame().ifPresent(alpha -> separate(text).append("alpha=").append(alpha.length).append(':')
                .append(Dumps.crc32(alpha)));
        for (var entry : new TreeMap<>(side.extra()).entrySet()) {
            separate(text).append(entry.getKey()).append('=').append(entry.getValue().length).append(':')
                    .append(Dumps.crc32(entry.getValue()));
        }
        return text.append(']').toString();
    }

    private static StringBuilder separate(StringBuilder text) {
        if (text.length() > 1) {
            text.append(' ');
        }
        return text;
    }

    private static String timestamp(long value) {
        return value == Packet.NO_TIMESTAMP ? "?" : Long.toString(value);
    }

    private static String rational(Rational rational) {
        return rational.numerator() + "/" + rational.denominator();
    }

    private static String quote(String value) {
        var text = new StringBuilder("\"");
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c == '"' || c == '\\') {
                text.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                text.append("\\u%04x".formatted((int) c));
            } else {
                text.append(c);
            }
        }
        return text.append('"').toString();
    }
}
