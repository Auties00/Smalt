package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.Languages;
import com.github.auties00.smalt.internal.subtitle.bitmap.DvdSubtitleStream;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Demuxes VobSub subtitles: the MPEG program stream of a {@code .sub} file, with or without its {@code .idx} index.
 *
 * <p>Every subpicture stream of the program stream (the private stream 1 substreams {@code 0x20} to {@code 0x3F})
 * becomes a {@link Codec#DVD_SUBTITLE} track whose identifier is its substream number plus one, in a 90 kHz time
 * base; each subpicture unit, reassembled from the PES packets that carry it, is one key packet stamped with the
 * presentation timestamp of the PES packet that started it and lasting until its first stop command when it has one.
 * Other streams, and a unit the file cuts short, are skipped.
 *
 * <p>With the index, the tracks get its header (the lines before its first stream, among them the frame size and
 * the palette) as their configuration and the language of their {@code id} line, and the {@code time offset} of the
 * header shifts every timestamp. Without it a track has no configuration, so it decodes with a gray palette. The
 * files are read whole.
 */
public final class VobSubDemuxer extends BitmapFileDemuxer {
    private static final Rational TIME_BASE = Rational.timeBaseOf(90_000);

    /**
     * Constructs a demuxer over a {@code .sub} file alone.
     *
     * @param sub the program stream, read whole; not closed
     * @throws MediaException.Malformed if the input is not an MPEG program stream
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public VobSubDemuxer(MediaSource sub) {
        this(null, sub, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer over a {@code .sub} file alone.
     *
     * @param sub the program stream, read whole; not closed
     * @param options the limits bounding the file size
     * @throws MediaException.Malformed if the input is not an MPEG program stream
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public VobSubDemuxer(MediaSource sub, DemuxerOptions options) {
        this(null, sub, options);
    }

    /**
     * Constructs a demuxer over a {@code .idx} and {@code .sub} file pair.
     *
     * @param idx the index, read whole, or {@code null} to read the program stream alone; not closed
     * @param sub the program stream, read whole; not closed
     * @param options the limits bounding the file sizes and the encoding of the index
     * @throws MediaException.Malformed if the index is not a VobSub index or the program stream is not one
     * @throws MediaException.LimitExceeded if a file is larger than the limits allow
     * @throws MediaException.Io if reading a source fails
     */
    public VobSubDemuxer(MediaSource idx, MediaSource sub, DemuxerOptions options) {
        this(parse(idx == null ? null : CueFileDemuxer.readBytes(idx, options), CueFileDemuxer.readBytes(sub, options)));
    }

    private VobSubDemuxer(Parsed parsed) {
        super(parsed.tracks, parsed.packets, Map.of());
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.VOBSUB;
    }

    /**
     * Returns whether bytes start like the program stream of a VobSub {@code .sub} file: a pack header followed by a
     * private stream 1 PES packet whose substream is a subpicture stream.
     *
     * @param header the first bytes of the input
     * @return {@code true} when the input looks like a {@code .sub} file
     */
    public static boolean matches(byte[] header) {
        if (header.length < 14 || header[0] != 0 || header[1] != 0 || header[2] != 1 || (header[3] & 0xFF) != 0xBA) {
            return false;
        }
        var position = packEnd(header, 0);
        if (position < 0 || position + 9 > header.length || header[position] != 0 || header[position + 1] != 0
                || header[position + 2] != 1 || (header[position + 3] & 0xFF) != 0xBD) {
            return false;
        }
        var payload = payloadStart(header, position);
        return payload >= 0 && payload < header.length && (header[payload] & 0xE0) == 0x20;
    }

    /**
     * Returns whether text starts like a VobSub index file.
     *
     * @param header the first bytes of the input
     * @return {@code true} when the input starts with the VobSub index signature
     */
    public static boolean matchesIndex(byte[] header) {
        var start = header.length >= 3 && (header[0] & 0xFF) == 0xEF && (header[1] & 0xFF) == 0xBB
                && (header[2] & 0xFF) == 0xBF ? 3 : 0;
        var text = new String(header, start, Math.min(header.length - start, 64), StandardCharsets.ISO_8859_1);
        return text.startsWith("# VobSub index file");
    }

    private record Parsed(List<Track> tracks, List<Packet> packets) {
    }

    private record Index(byte[] header, Map<Integer, String> languages, long offsetTicks) {
    }

    private static Parsed parse(byte[] idx, byte[] sub) {
        var index = idx == null ? null : index(idx);
        var units = new TreeMap<Integer, Assembly>();
        var packets = new ArrayList<Packet>();
        var position = 0;
        while (position + 4 <= sub.length) {
            if (sub[position] != 0 || sub[position + 1] != 0 || sub[position + 2] != 1) {
                position++;
                continue;
            }
            var code = sub[position + 3] & 0xFF;
            if (code == 0xBA) {
                var end = packEnd(sub, position);
                position = end < 0 ? sub.length : end;
                continue;
            }
            if (code < 0xBB || position + 6 > sub.length) {
                position += 4;
                continue;
            }
            var length = (sub[position + 4] & 0xFF) << 8 | (sub[position + 5] & 0xFF);
            var end = Math.min(sub.length, position + 6 + length);
            if (code == 0xBD) {
                var payload = payloadStart(sub, position);
                if (payload >= 0 && payload < end && (sub[payload] & 0xE0) == 0x20) {
                    var stream = sub[payload] & 0x1F;
                    var pts = pts(sub, position);
                    var assembly = units.computeIfAbsent(stream, _ -> new Assembly());
                    var unit = assembly.add(sub, payload + 1, end, pts);
                    if (unit != null) {
                        var time = unit.pts + (index == null ? 0 : index.offsetTicks);
                        var stop = DvdSubtitleStream.stopTicks(unit.data);
                        packets.add(new Packet(stream + 1, unit.data, time, time, stop >= 0 ? stop : Packet.UNKNOWN_DURATION,
                                EnumSet.of(PacketFlag.KEY), PacketSideData.NONE));
                    }
                }
            }
            position = end;
        }
        if (packets.isEmpty() && (sub.length < 4 || (sub[3] & 0xFF) != 0xBA)) {
            throw new MediaException.Malformed("not an MPEG program stream: no pack header");
        }
        var tracks = new ArrayList<Track>();
        for (var stream : units.keySet()) {
            var duration = 0L;
            for (var packet : packets) {
                if (packet.trackId() == stream + 1) {
                    duration = Math.max(duration, packet.pts() + Math.max(packet.duration(), 0));
                }
            }
            var language = index == null ? null : index.languages.get(stream);
            tracks.add(DataTrack.builder().id(stream + 1).codec(Codec.DVD_SUBTITLE).timeBase(TIME_BASE)
                    .duration(duration).codecConfig(index == null ? null : index.header)
                    .language(language).build());
        }
        if (tracks.isEmpty() && index != null) {
            for (var stream : index.languages.keySet()) {
                tracks.add(DataTrack.builder().id(stream + 1).codec(Codec.DVD_SUBTITLE).timeBase(TIME_BASE)
                        .duration(0).codecConfig(index.header).language(index.languages.get(stream)).build());
            }
        }
        return new Parsed(tracks, packets);
    }

    /**
     * Reads the parts of an index file the tracks need.
     */
    private static Index index(byte[] data) {
        if (!matchesIndex(data)) {
            throw new MediaException.Malformed("not a VobSub index: no signature line");
        }
        var text = new String(data, StandardCharsets.UTF_8);
        var header = new StringBuilder();
        var languages = new TreeMap<Integer, String>();
        var offset = 0L;
        var inHeader = true;
        for (var line : text.split("\r\n|\r|\n")) {
            var trimmed = line.trim();
            var lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("id:")) {
                inHeader = false;
                var comma = trimmed.indexOf(',');
                var code = (comma < 0 ? trimmed.substring(3) : trimmed.substring(3, comma)).trim();
                var at = lower.indexOf("index:");
                if (at >= 0) {
                    try {
                        var number = Integer.parseInt(trimmed.substring(at + 6).trim());
                        if (number >= 0 && number < 32) {
                            var bibliographic = code.isEmpty() || code.equals("--") ? "und"
                                    : Languages.bibliographicCode(code);
                            languages.put(number, bibliographic.equals("und") ? null : bibliographic);
                        }
                    } catch (NumberFormatException ignored) {
                        // an id line without a usable index names no stream
                    }
                }
                continue;
            }
            if (lower.startsWith("timestamp:") || lower.startsWith("delay:")) {
                inHeader = false;
                continue;
            }
            if (inHeader) {
                header.append(line).append('\n');
                if (lower.startsWith("time offset:")) {
                    offset = timeOffset(trimmed.substring(12).trim());
                }
            }
        }
        return new Index(header.toString().getBytes(StandardCharsets.UTF_8), languages, offset);
    }

    /**
     * Parses a {@code time offset} value: milliseconds, or {@code hh:mm:ss:ms}, optionally negative.
     *
     * @return the offset in 90 kHz ticks
     */
    private static long timeOffset(String value) {
        var negative = value.startsWith("-");
        var digits = negative ? value.substring(1) : value;
        long millis;
        try {
            var parts = digits.split(":");
            if (parts.length == 4) {
                millis = ((Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 60
                        + Long.parseLong(parts[2])) * 1000 + Long.parseLong(parts[3]);
            } else {
                millis = Long.parseLong(digits);
            }
        } catch (NumberFormatException exception) {
            return 0;
        }
        millis = Math.min(millis, 1L << 40);
        return (negative ? -millis : millis) * 90;
    }

    /**
     * Returns the index after a pack header, or {@code -1} when it is truncated.
     */
    private static int packEnd(byte[] data, int position) {
        if (position + 5 > data.length) {
            return -1;
        }
        if ((data[position + 4] & 0xC0) == 0x40) {
            if (position + 14 > data.length) {
                return -1;
            }
            return position + 14 + (data[position + 13] & 7);
        }
        return position + 12;
    }

    /**
     * Returns the index of the payload of a PES packet, MPEG-1 or MPEG-2 syntax, or {@code -1} when truncated.
     */
    private static int payloadStart(byte[] data, int position) {
        var at = position + 6;
        if (at >= data.length) {
            return -1;
        }
        if ((data[at] & 0xC0) == 0x80) {
            return at + 2 < data.length ? at + 3 + (data[at + 2] & 0xFF) : -1;
        }
        while (at < data.length && (data[at] & 0xFF) == 0xFF) {
            at++;
        }
        if (at < data.length && (data[at] & 0xC0) == 0x40) {
            at += 2;
        }
        if (at >= data.length) {
            return -1;
        }
        var flags = data[at] & 0xF0;
        return flags == 0x20 ? at + 5 : flags == 0x30 ? at + 10 : at + 1;
    }

    /**
     * Returns the presentation timestamp of a PES packet, or {@link Packet#NO_TIMESTAMP}.
     */
    private static long pts(byte[] data, int position) {
        var at = position + 6;
        if (at + 2 >= data.length) {
            return Packet.NO_TIMESTAMP;
        }
        int field;
        if ((data[at] & 0xC0) == 0x80) {
            if ((data[at + 1] & 0x80) == 0) {
                return Packet.NO_TIMESTAMP;
            }
            field = at + 3;
        } else {
            while (at < data.length && (data[at] & 0xFF) == 0xFF) {
                at++;
            }
            if (at < data.length && (data[at] & 0xC0) == 0x40) {
                at += 2;
            }
            if (at >= data.length || (data[at] & 0xE0) != 0x20) {
                return Packet.NO_TIMESTAMP;
            }
            field = at;
        }
        if (field + 5 > data.length) {
            return Packet.NO_TIMESTAMP;
        }
        return (long) (data[field] & 0x0E) << 29 | (data[field + 1] & 0xFF) << 22 | (data[field + 2] & 0xFE) << 14
                | (data[field + 3] & 0xFF) << 7 | (data[field + 4] & 0xFE) >> 1;
    }

    /**
     * Reassembles the subpicture units of one stream from the PES packets that carry them.
     */
    private static final class Assembly {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private long pts = Packet.NO_TIMESTAMP;
        private long previous;

        /**
         * Adds the payload of one PES packet.
         *
         * @return the unit the payload completes, or {@code null}
         */
        Unit add(byte[] data, int start, int end, long time) {
            if (time != Packet.NO_TIMESTAMP) {
                if (buffer.size() > 0) {
                    buffer.reset();
                }
                pts = time;
            }
            if (buffer.size() == 0 && pts == Packet.NO_TIMESTAMP) {
                pts = previous;
            }
            buffer.write(data, start, end - start);
            if (buffer.size() < 2) {
                return null;
            }
            var bytes = buffer.toByteArray();
            var size = (bytes[0] & 0xFF) << 8 | (bytes[1] & 0xFF);
            if (size < 4 || bytes.length < size) {
                if (size < 4) {
                    buffer.reset();
                    pts = Packet.NO_TIMESTAMP;
                }
                return null;
            }
            var unit = new Unit(Arrays.copyOf(bytes, size), pts);
            previous = pts;
            buffer.reset();
            pts = Packet.NO_TIMESTAMP;
            return unit;
        }
    }

    private record Unit(byte[] data, long pts) {
    }
}
