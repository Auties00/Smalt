package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Splits an MPEG-1 or MPEG-2 video stream ({@code stream_type 0x01} or {@code 0x02}, ISO/IEC 11172-2 and ISO/IEC
 * 13818-2) into one packet per PES packet, the picture or pictures the writer put in it.
 *
 * <p>The track's configuration is the sequence header with its extensions that the first key frame carries in band
 * (as the {@code DecoderSpecificInfo} of an {@code esds} box holds it), and its size, frame rate, sample aspect ratio
 * and color come from that header, as does its codec: a stream type of MPEG-2 video may carry MPEG-1 video, whose
 * sequence header has no sequence extension. A packet is a key frame when its first picture is an I-picture (or an
 * MPEG-1 D-picture); one whose sequence header differs from the configuration in force carries it as its
 * {@link PacketSideData#newCodecConfig()}. Packets before the first key frame with a sequence header are dropped.
 * Timestamps are the PES ones on the 90 kHz timeline, and a packet lasts until the next one's decoding time; a PES
 * packet without timestamps (a picture of a packet whose writer stamped only some) takes the previous packet's
 * decoding time plus its duration. Instances are not thread-safe.
 */
final class MpegVideoParser extends ElementaryParser {
    /**
     * The codec the program map declares, which the first sequence header overrides.
     */
    private final Codec codec;

    /**
     * The configuration last reported downstream, or {@code null}.
     */
    private byte[] established;

    /**
     * The packet waiting for its successor's decoding time, or {@code null}.
     */
    private Packet held;

    /**
     * The file offset of {@link #held}.
     */
    private long heldOffset;

    /**
     * The duration of the last emitted packet, in 90 kHz ticks.
     */
    private long lastDuration;

    /**
     * Whether a key frame was met, after which every packet is emitted.
     */
    private boolean seenKey;

    /**
     * Whether packets before the first key frame are emitted too.
     */
    private boolean keepLeading;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     * @param codec {@link Codec#MPEG1_VIDEO} or {@link Codec#MPEG2_VIDEO}, as the stream type declares
     */
    MpegVideoParser(Psi.StreamEntry entry, MediaLimits limits, Codec codec) {
        super(entry, limits);
        this.codec = codec;
    }

    @Override
    ElementaryParser fresh() {
        var parser = new MpegVideoParser(entry, limits, codec);
        parser.track = track;
        parser.established = track == null ? null : track.codecConfig().orElse(null);
        return parser;
    }

    @Override
    void keepLeadingUnits() {
        keepLeading = true;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        var dts = pes.dts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.dts(), true);
        var pts = pes.pts() == TsClock.NONE ? TsClock.NONE : clock.ticks(trackId(), pes.pts(), dts == TsClock.NONE);
        if (dts == TsClock.NONE) {
            dts = pts;
        }
        if (pes.length() == 0) {
            return;
        }
        var payload = Arrays.copyOfRange(pes.data(), pes.offset(), pes.offset() + pes.length());
        var type = MpegVideoHeaders.firstPictureType(payload);
        var key = type == 1 || type == 4;
        var headers = key ? MpegVideoHeaders.config(payload) : null;
        byte[] newConfig = null;
        if (headers != null) {
            if (track == null) {
                configure(headers);
            } else if (!Arrays.equals(headers, established)) {
                newConfig = headers;
                established = headers;
            }
        }
        if (track == null || !seenKey && !(key && established != null) && !keepLeading) {
            return;
        }
        if (dts == TsClock.NONE) {
            if (held == null) {
                return;
            }
            dts = held.dts() + Math.max(1, lastDuration);
            pts = Packet.NO_TIMESTAMP;
        }
        seenKey = true;
        var flags = EnumSet.noneOf(PacketFlag.class);
        if (key) {
            flags.add(PacketFlag.KEY);
        }
        if (pes.corrupt()) {
            flags.add(PacketFlag.CORRUPT);
        }
        var packet = new Packet(trackId(), payload, pts, dts, Packet.UNKNOWN_DURATION, flags,
                newConfig == null ? PacketSideData.NONE : PacketSideData.builder().newCodecConfig(newConfig).build());
        release(dts, sink);
        held = packet;
        heldOffset = pes.fileOffset();
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        if (held != null) {
            sink.accept(held.withTiming(held.pts(), held.dts(), lastDuration > 0 ? lastDuration
                    : Packet.UNKNOWN_DURATION), heldOffset);
            held = null;
        }
    }

    /**
     * Passes the held packet on, lasting until a decoding time.
     *
     * @param dts the decoding time of the packet that follows it
     * @param sink receives the packet
     */
    private void release(long dts, UnitSink sink) {
        if (held == null) {
            return;
        }
        var duration = dts - held.dts();
        if (duration <= 0) {
            duration = lastDuration;
        }
        lastDuration = Math.max(0, duration);
        sink.accept(held.withTiming(held.pts(), held.dts(), lastDuration > 0 ? lastDuration
                : Packet.UNKNOWN_DURATION), heldOffset);
        held = null;
    }

    /**
     * Builds the track from the sequence header of the first key frame.
     *
     * @param headers the sequence header with its extensions
     */
    private void configure(byte[] headers) {
        var sequence = MpegVideoHeaders.find(headers);
        var builder = VideoTrack.builder()
                .id(trackId())
                .codec(sequence == null ? codec : sequence.mpeg2() ? Codec.MPEG2_VIDEO : Codec.MPEG1_VIDEO)
                .timeBase(NalParser.TIME_BASE)
                .codecConfig(headers)
                .language(entry.language());
        if (sequence != null) {
            LimitChecks.checkPicture(limits, sequence.width(), sequence.height());
            builder.width(sequence.width()).height(sequence.height());
            var rate = sequence.frameRate();
            if (rate != null) {
                builder.frameRate(rate);
            }
            var aspect = sequence.sampleAspect();
            if (aspect != null) {
                builder.sampleAspectRatio(aspect);
            }
            var color = sequence.signaledColor();
            if (color != null) {
                builder.color(color);
            }
        }
        track = builder.build();
        established = headers;
    }
}
