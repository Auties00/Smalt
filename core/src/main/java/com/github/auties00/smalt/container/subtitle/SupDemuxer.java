package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Demuxes a Blu-ray presentation graphic stream file ({@code .sup}): one {@link Codec#PGS} track.
 *
 * <p>The file is a sequence of segments, each behind a 13-byte header: the magic {@code "PG"}, a presentation and a
 * decoding timestamp on the 90 kHz clock (32 bits each, big-endian), the segment type and the 16-bit segment
 * length. The demuxer reads the whole file and makes one packet of each display set, from its presentation
 * composition segment to its end segment, without the headers; the packet's presentation and decoding timestamps
 * are the presentation timestamp of its composition segment, in a 90 kHz time base. A display set that starts an
 * epoch or is an acquisition point is a key packet. Segments before the first composition segment, and a display set
 * the file cuts short, are kept as they are, in a packet of their own.
 */
public final class SupDemuxer extends BitmapFileDemuxer {
    /**
     * The time base of the timestamps, 90 kHz.
     */
    private static final Rational TIME_BASE = Rational.timeBaseOf(90_000);

    /**
     * Constructs a demuxer with the default options.
     *
     * @param source the input, read whole; not closed
     * @throws MediaException.Malformed if the input is not a presentation graphic stream file
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public SupDemuxer(MediaSource source) {
        this(source, DemuxerOptions.defaults());
    }

    /**
     * Constructs a demuxer.
     *
     * @param source the input, read whole; not closed
     * @param options the limits bounding the file size
     * @throws MediaException.Malformed if the input is not a presentation graphic stream file
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow
     * @throws MediaException.Io if reading the source fails
     */
    public SupDemuxer(MediaSource source, DemuxerOptions options) {
        this(parse(CueFileDemuxer.readBytes(source, options)));
    }

    private SupDemuxer(List<Packet> packets) {
        super(List.of(track(packets)), packets, Map.of());
    }

    private static DataTrack track(List<Packet> packets) {
        return DataTrack.builder().id(1).codec(Codec.PGS).timeBase(TIME_BASE)
                .duration(packets.isEmpty() ? 0 : packets.getLast().pts()).build();
    }

    /**
     * Returns whether bytes start like a presentation graphic stream file: the {@code "PG"} magic and a known
     * segment type.
     *
     * @param header the first bytes of the input
     * @return {@code true} when the input looks like a {@code .sup} file
     */
    public static boolean matches(byte[] header) {
        if (header.length < 13 || header[0] != 'P' || header[1] != 'G') {
            return false;
        }
        var type = header[10] & 0xFF;
        return type >= 0x14 && type <= 0x17 || type == 0x80;
    }

    @Override
    public ContainerFormat format() {
        return ContainerFormat.SUP;
    }

    /**
     * Groups the segments of a file into display sets.
     *
     * @param data the file
     * @return the packets in file order
     * @throws MediaException.Malformed if the file does not start with a segment
     */
    private static List<Packet> parse(byte[] data) {
        if (data.length > 0 && !matches(data)) {
            throw new MediaException.Malformed("not a presentation graphic stream file: no PG segment header");
        }
        var packets = new ArrayList<Packet>();
        var set = new ByteArrayOutputStream();
        var setPts = 0L;
        var key = false;
        var position = 0;
        while (position + 13 <= data.length && data[position] == 'P' && data[position + 1] == 'G') {
            var pts = readU32(data, position + 2);
            var type = data[position + 10] & 0xFF;
            var length = (data[position + 11] & 0xFF) << 8 | (data[position + 12] & 0xFF);
            var body = position + 13;
            if (body + length > data.length) {
                break;
            }
            if (type == 0x16) {
                flush(packets, set, setPts, key);
                setPts = pts;
                key = length >= 8 && (data[body + 7] & 0xC0) != 0;
            } else if (set.size() == 0) {
                setPts = pts;
            }
            set.write(type);
            set.write(length >> 8);
            set.write(length);
            set.write(data, body, length);
            position = body + length;
            if (type == 0x80) {
                flush(packets, set, setPts, key);
                key = false;
            }
        }
        flush(packets, set, setPts, key);
        return packets;
    }

    private static void flush(List<Packet> packets, ByteArrayOutputStream set, long pts, boolean key) {
        if (set.size() == 0) {
            return;
        }
        var flags = key ? EnumSet.of(PacketFlag.KEY) : EnumSet.noneOf(PacketFlag.class);
        packets.add(new Packet(1, set.toByteArray(), pts, pts, Packet.UNKNOWN_DURATION, flags, PacketSideData.NONE));
        set.reset();
    }

    private static long readU32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
    }
}
