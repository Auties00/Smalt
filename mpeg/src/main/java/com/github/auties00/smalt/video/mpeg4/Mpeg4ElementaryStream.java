package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Arrays;
import java.util.Objects;

/**
 * A reader that splits a raw MPEG-4 Part 2 ({@code .m4v}, {@code .cmp}) or H.263 ({@code .263})
 * elementary stream into one packet per picture, for {@link Mpeg4Decoder} and
 * {@link com.github.auties00.smalt.video.h263.H263Decoder}.
 *
 * <p>An MPEG-4 stream starts with a visual object sequence, visual object, video object or video
 * object layer start code ({@code 00 00 01 B0}, {@code B5}, {@code 00} to {@code 1F},
 * {@code 20} to {@code 2F}) or, with the short video header, with a picture start code; an
 * H.263 stream starts with a picture start code ({@code 00 00 80} to {@code 00 00 83}). Every
 * packet ends where the next picture's headers begin: the configuration headers and group of VOP
 * headers that precede a VOP belong to the packet of that VOP, so the first packet carries the
 * stream headers. The headers before the first VOP are also the track's
 * {@link VideoTrack#codecConfig() codec configuration}.
 *
 * <p>An elementary stream carries no timestamps beyond the pictures' own temporal references: the
 * track's time base is 1/90000 and only the first packet has a timestamp (zero), so the decoders
 * time the following pictures from their VOP times or temporal references. Packets of intra
 * pictures are flagged as key frames.
 *
 * <p>The source is read sequentially and not closed. Instances are not thread-safe.
 */
public final class Mpeg4ElementaryStream implements AutoCloseable {
    /**
     * The time base of the track.
     */
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    /**
     * The number of bytes read from the source at once.
     */
    private static final int CHUNK = 64 * 1024;

    /**
     * The size of the H.263 picture start code, in bits.
     */
    private static final int PSC_BITS = 22;

    /**
     * The size of the H.263 temporal reference, in bits.
     */
    private static final int TR_BITS = 8;

    /**
     * The size of PTYPE when it announces a PLUSPTYPE, in bits.
     */
    private static final int PTYPE_BITS = 8;

    /**
     * The index one past the source format field within PTYPE, in bits.
     */
    private static final int FORMAT_END = 8;

    /**
     * The PTYPE source format announcing a PLUSPTYPE.
     */
    private static final int EXTENDED_FORMAT = 7;

    /**
     * The size of the PLUSPTYPE update full extended PTYPE field (UFEP), in bits.
     */
    private static final int UFEP_BITS = 3;

    /**
     * The size of the optional part of PLUSPTYPE (OPPTYPE), present when UFEP is 1, in bits.
     */
    private static final int OPPTYPE_BITS = 18;

    /**
     * The size of the picture type code that starts the mandatory part of PLUSPTYPE, in bits.
     */
    private static final int TYPE_BITS = 3;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The limits.
     */
    private final MediaLimits limits;

    /**
     * Whether the stream is H.263 (or MPEG-4 with only short video headers).
     */
    private final boolean shortHeader;

    /**
     * The track.
     */
    private final VideoTrack track;

    /**
     * The bytes read and not yet returned, from {@link #start} to {@link #end}.
     */
    private byte[] buffer = new byte[CHUNK * 2];

    /**
     * The first byte not yet returned.
     */
    private int start;

    /**
     * The end of the bytes read.
     */
    private int end;

    /**
     * The position in the source of the next byte to read.
     */
    private long position;

    /**
     * Whether the source is exhausted.
     */
    private boolean exhausted;

    /**
     * Whether a packet was returned.
     */
    private boolean returned;

    /**
     * Opens an elementary stream with the {@linkplain DemuxerOptions#defaults() default} demuxer
     * settings.
     *
     * @param source the stream, read from offset zero
     * @return the stream
     * @throws MediaException.Unsupported if the content is not an MPEG-4 Part 2 or H.263
     * elementary stream
     * @throws MediaException.Io if reading fails
     */
    public static Mpeg4ElementaryStream open(MediaSource source) {
        return open(source, DemuxerOptions.defaults());
    }

    /**
     * Opens an elementary stream with demuxer settings, whose {@link DemuxerOptions#limits()}
     * bound the size of one picture.
     *
     * @param source the stream, read from offset zero
     * @param options the demuxer settings
     * @return the stream
     * @throws MediaException.Unsupported if the content is not an MPEG-4 Part 2 or H.263
     * elementary stream
     * @throws MediaException.Io if reading fails
     */
    public static Mpeg4ElementaryStream open(MediaSource source, DemuxerOptions options) {
        return new Mpeg4ElementaryStream(Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(options, "options").limits());
    }

    /**
     * Returns whether bytes start an MPEG-4 Part 2 or H.263 elementary stream.
     *
     * @param header the first bytes of the stream, at least four
     * @return {@code true} when they start with a configuration or picture start code
     */
    public static boolean matches(byte[] header) {
        if (header.length < 4 || header[0] != 0 || header[1] != 0) {
            return false;
        }
        if ((header[2] & 0xFC) == 0x80) {
            return true;
        }
        if (header[2] != 1) {
            return false;
        }
        var code = header[3] & 0xFF;
        return code == 0xB0 || code == 0xB5 || code <= 0x2F;
    }

    /**
     * Opens the stream and reads its headers.
     *
     * @param source the stream, read from offset zero
     * @param limits the limits bounding the size of one picture
     */
    private Mpeg4ElementaryStream(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        fill();
        var head = Arrays.copyOfRange(buffer, start, Math.min(end, start + 4));
        if (!matches(head)) {
            throw new MediaException.Unsupported("mpeg4-elementary", "not an MPEG-4 Part 2 or H.263 elementary stream");
        }
        shortHeader = (head[2] & 0xFC) == 0x80;
        byte[] config = null;
        if (!shortHeader) {
            var vop = findPicture(start);
            while (vop < 0 && !exhausted && end - start < limits.maxBytes()) {
                fill();
                vop = findPicture(start);
            }
            if (vop > start) {
                config = Arrays.copyOfRange(buffer, start, vop);
            }
        }
        this.track = VideoTrack.builder()
                .id(1)
                .codec(shortHeader ? Codec.H263 : Codec.MPEG4_PART2)
                .timeBase(TIME_BASE)
                .codecConfig(config)
                .build();
    }

    /**
     * {@return the track the stream describes: MPEG-4 Part 2 with the headers before the first VOP
     * as configuration, or H.263; its size is left for the decoder to read}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * Reads the packet of the next picture, flagged as a key frame when the picture is intra
     * coded.
     *
     * @return the packet, or {@code null} at the end of the stream
     * @throws MediaException.LimitExceeded if a picture exceeds the allocation limit
     * @throws MediaException.Io if reading fails
     */
    public Packet read() {
        while (true) {
            var picture = findPicture(start);
            var next = picture < 0 ? -1 : findBoundary(picture + 3);
            if (next >= 0 || exhausted) {
                if (start >= end) {
                    return null;
                }
                var from = start;
                var stop = next >= 0 ? next : end;
                var data = Arrays.copyOfRange(buffer, from, stop);
                start = stop;
                var key = picture >= 0 && isIntra(data, picture - from);
                var pts = returned ? Packet.NO_TIMESTAMP : 0;
                returned = true;
                return new Packet(track.id(), data, pts, pts, Packet.UNKNOWN_DURATION, key);
            }
            LimitChecks.checkAllocation(limits, end - start + (long) CHUNK);
            fill();
        }
    }

    /**
     * Finds the start code of the next picture.
     *
     * @param from the index to search from
     * @return the index of the start code, or {@code -1}
     */
    private int findPicture(int from) {
        for (var i = from; i + 3 < end; i++) {
            if (buffer[i] == 0 && buffer[i + 1] == 0) {
                if (shortHeader ? (buffer[i + 2] & 0xFC) == 0x80 : buffer[i + 2] == 1 && (buffer[i + 3] & 0xFF) == 0xB6) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Finds where the packet of the picture before a position ends: the next start code of any
     * kind for MPEG-4, the next picture start code for H.263.
     *
     * @param from the index to search from
     * @return the index, or {@code -1} when it is not in the buffer yet
     */
    private int findBoundary(int from) {
        for (var i = from; i + 3 < end; i++) {
            if (buffer[i] == 0 && buffer[i + 1] == 0) {
                if (shortHeader ? (buffer[i + 2] & 0xFC) == 0x80 : buffer[i + 2] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Returns whether a picture is intra coded.
     *
     * @param data the packet
     * @param offset the index of the picture's start code
     * @return {@code true} for an intra picture
     */
    private boolean isIntra(byte[] data, int offset) {
        if (offset < 0 || offset + 8 > data.length) {
            return false;
        }
        if (!shortHeader && data[offset + 2] == 1) {
            return (data[offset + 4] & 0xC0) == 0;
        }
        var bits = 0L;
        for (var i = 0; i < 8; i++) {
            bits = bits << 8 | (data[offset + i] & 0xFF);
        }
        var ptype = Long.SIZE - PSC_BITS - TR_BITS;
        var format = (int) (bits >>> (ptype - FORMAT_END)) & 7;
        if (format != EXTENDED_FORMAT) {
            return ((bits >>> (ptype - FORMAT_END - 1)) & 1) == 0;
        }
        var plusptype = ptype - PTYPE_BITS;
        var ufep = (int) (bits >>> (plusptype - UFEP_BITS)) & 7;
        var typeShift = plusptype - UFEP_BITS - (ufep == 1 ? OPPTYPE_BITS : 0) - TYPE_BITS;
        return ((bits >>> typeShift) & 7) == 0;
    }

    /**
     * Reads the next chunk of the source, compacting the buffer.
     */
    private void fill() {
        if (exhausted) {
            return;
        }
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start);
            end -= start;
            start = 0;
        }
        if (buffer.length - end < CHUNK) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, end + CHUNK));
        }
        var read = source.read(position, buffer, end, CHUNK);
        if (read <= 0) {
            exhausted = true;
            return;
        }
        end += read;
        position += read;
    }

    /**
     * Does nothing: the source belongs to the caller.
     */
    @Override
    public void close() {
    }
}
