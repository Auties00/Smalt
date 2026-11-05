package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reassembles the units of one elementary stream from the RTP packets of a payload format.
 *
 * <p>Packets are pushed in arrival order. The depacketizer puts them back in sequence order, waiting for a missing
 * packet until {@link RtpDepacketizerOptions#reorderWindow()} later packets have arrived, drops duplicates and
 * packets that arrive after their place has passed, and hands the result to the payload format's reassembly. At the
 * start of a stream it holds the first packets until more than the window have arrived, so that the lowest sequence
 * number starts the stream even when it does not arrive first. The
 * units come out in decoding order as {@link RtpFrame}s holding a {@link Packet} in the codec's canonical form:
 * <ul>
 *   <li>H.264: an access unit of NAL units with 4-byte length prefixes, put in decoding order in the interleaved
 *       mode, the in-band parameter sets kept; a unit whose parameter sets differ from those of
 *       {@link #codecConfig()} so far carries the new {@code avcC} record as
 *       {@link PacketSideData#newCodecConfig()}. Its decoding timestamp is unknown.</li>
 *   <li>VP8: a frame. VP9: a picture, its frames joined into a superframe with an index when it has several, a
 *       hidden frame joined with the shown frame of the same timestamp. AV1: a temporal unit of OBUs with size
 *       fields, without temporal delimiter.</li>
 *   <li>Opus: one Opus packet. AMR and AMR-WB: one frame-block of storage-format frames, one frame per channel,
 *       with the quality bit cleared when a frame CRC fails. {@code mpeg4-generic}: one access unit, put in decoding
 *       order when the sender interleaves.</li>
 * </ul>
 * A packet whose payload cannot be parsed is counted in {@link #discardedPackets()} and treated as lost. A change of
 * synchronization source ends the current stream: what is pending is flushed, reassembly restarts and the output
 * timestamps of the new stream count from its own first packet.
 *
 * <p>Output timestamps count from the RTP timestamp of the first packet, which maps to zero, in the time base of
 * the options.
 *
 * <p>Instances are not thread-safe.
 */
public final class RtpDepacketizer {
    /**
     * The sequence number distance beyond which a packet starts a new numbering (RFC 3550 appendix A.1 takes 3000).
     */
    private static final int JUMP = 3000;

    private final RtpPayloadFormat format;
    private final RtpDepacketizerOptions options;
    private final Assembler assembler;
    private final Rational clockTimeBase;
    private final Rational outputTimeBase;
    private final TreeMap<Long, RtpPacket> buffer = new TreeMap<>();
    private List<RtpFrame> pending = new ArrayList<>();
    private boolean started;
    private long ssrc;
    private long nextSequence;
    private long highestSequence;
    private boolean timestampsStarted;
    private long lastTimestamp;
    private long firstTimestamp;
    private boolean gap;
    private RtpPacket probation;
    private boolean primed;
    private long lost;
    private long duplicates;
    private long late;
    private long discarded;

    private RtpDepacketizer(RtpPayloadFormat format, RtpDepacketizerOptions options) {
        this.format = format;
        this.options = options;
        this.clockTimeBase = Rational.timeBaseOf(format.clockRate());
        this.outputTimeBase = options.timeBase().orElse(clockTimeBase);
        Assembler.Output output = this::emit;
        var limit = options.limits().maxBytes();
        this.assembler = switch (format.encodingName()) {
            case "H264" -> new H264Assembler(output, limit, format);
            case "VP8" -> new Vp8Assembler(output, limit);
            case "VP9" -> new Vp9Assembler(output, limit);
            case "AV1" -> new Av1Assembler(output, limit);
            case "opus" -> new OpusAssembler(output, limit);
            case "AMR", "AMR-WB" -> new AmrAssembler(output, limit, AmrFormat.of(format));
            case "mpeg4-generic" -> new Mpeg4GenericAssembler(output, limit, Mpeg4GenericFormat.of(format));
            default -> throw new MediaException.Unsupported(format.encodingName(), "no RTP depacketizer for "
                    + format.encodingName());
        };
    }

    /**
     * Creates a depacketizer of a payload format.
     *
     * @param format the payload format
     * @param options the reordering window, output time base, track identifier and limits
     * @return the depacketizer
     * @throws MediaException.Unsupported if the format's encoding name is not one this package implements
     * @throws MediaException.Malformed if a format parameter the depacketizer needs is invalid
     */
    public static RtpDepacketizer create(RtpPayloadFormat format, RtpDepacketizerOptions options) {
        return new RtpDepacketizer(format, options);
    }

    /**
     * {@return the payload format}
     */
    public RtpPayloadFormat format() {
        return format;
    }

    /**
     * Takes one received packet.
     *
     * <p>The packet's payload type is not checked: a caller that receives several payload types on one stream routes
     * each to its own depacketizer.
     *
     * @param packet the received packet, in arrival order
     * @return the units completed by this packet, in decoding order, possibly none
     */
    public List<RtpFrame> push(RtpPacket packet) {
        if (!started || packet.ssrc() != ssrc) {
            if (started) {
                drain();
                assembler.flush();
            }
            started = true;
            ssrc = packet.ssrc();
            nextSequence = packet.sequenceNumber();
            highestSequence = nextSequence;
            buffer.clear();
            probation = null;
            gap = false;
            primed = false;
            timestampsStarted = false;
        }
        var delta = (short) (packet.sequenceNumber() - (int) (highestSequence & 0xFFFF));
        var extended = highestSequence + delta;
        if (extended > highestSequence + JUMP || extended < nextSequence - JUMP) {
            if (probation != null && packet.sequenceNumber() == ((probation.sequenceNumber() + 1) & 0xFFFF)) {
                drain();
                gap = true;
                restart(probation, packet);
                probation = null;
                release(false);
            } else {
                probation = packet;
                late++;
            }
            return take();
        }
        probation = null;
        if (!primed && extended < nextSequence && nextSequence - extended <= options.reorderWindow()) {
            nextSequence = extended;
        }
        if (extended < nextSequence) {
            late++;
        } else if (buffer.containsKey(extended)) {
            duplicates++;
        } else {
            buffer.put(extended, packet);
            highestSequence = Math.max(highestSequence, extended);
        }
        if (!primed && buffer.size() > options.reorderWindow()) {
            primed = true;
        }
        if (primed) {
            release(false);
        }
        return take();
    }

    /**
     * Releases everything held: packets waiting for a missing one are reassembled as if it were lost, and units
     * still incomplete are emitted.
     *
     * <p>The depacketizer stays usable; the next packet continues the stream.
     *
     * @return the units, in decoding order, possibly none
     */
    public List<RtpFrame> flush() {
        drain();
        assembler.flush();
        return take();
    }

    /**
     * {@return the out-of-band codec configuration known so far: the {@code avcC} record of the H.264 parameter
     * sets of the format and of the stream, the {@code AudioSpecificConfig} of {@code mpeg4-generic}; empty for the
     * other formats or before any parameter set is known}
     */
    public Optional<byte[]> codecConfig() {
        return assembler.codecConfig();
    }

    /**
     * {@return the number of packets declared lost: missing after the reordering window passed them or when the
     * depacketizer was flushed; the numbers a sequence number jump skips are not counted}
     */
    public long lostPackets() {
        return lost;
    }

    /**
     * {@return the number of packets dropped because a packet with the same sequence number was already held}
     */
    public long duplicatePackets() {
        return duplicates;
    }

    /**
     * {@return the number of packets dropped because they arrived after their place in sequence had passed}
     */
    public long latePackets() {
        return late;
    }

    /**
     * {@return the number of packets dropped because their payload could not be parsed or made a unit exceed the
     * size limit}
     */
    public long discardedPackets() {
        return discarded;
    }

    private void drain() {
        release(true);
        primed = true;
    }

    private void restart(RtpPacket first, RtpPacket second) {
        var base = (highestSequence + 0x20000) & ~0xFFFFL;
        nextSequence = base + first.sequenceNumber();
        highestSequence = nextSequence + 1;
        buffer.put(nextSequence, first);
        buffer.put(highestSequence, second);
        late--;
        primed = true;
    }

    private void release(boolean all) {
        while (!buffer.isEmpty()) {
            var first = buffer.firstKey();
            if (first != nextSequence) {
                if (!all && buffer.size() <= options.reorderWindow()) {
                    return;
                }
                lost += first - nextSequence;
                gap = true;
                nextSequence = first;
            }
            var packet = buffer.pollFirstEntry().getValue();
            nextSequence++;
            deliver(packet);
        }
    }

    private void deliver(RtpPacket packet) {
        if (!timestampsStarted) {
            timestampsStarted = true;
            lastTimestamp = packet.timestamp();
            firstTimestamp = lastTimestamp;
        }
        var delta = (int) (packet.timestamp() - (lastTimestamp & 0xFFFF_FFFFL));
        var timestamp = lastTimestamp + delta;
        lastTimestamp = timestamp;
        var hadGap = gap;
        gap = false;
        try {
            assembler.accept(packet, timestamp, hadGap);
        } catch (MediaException.Malformed exception) {
            discarded++;
            gap = true;
        }
    }

    private void emit(byte[] data, long timestamp, boolean key, boolean complete, long duration,
                      PacketSideData sideData) {
        var pts = clockTimeBase.rescale(timestamp - firstTimestamp, outputTimeBase);
        var dts = assembler.decodeOrderIsPresentationOrder() ? pts : Packet.NO_TIMESTAMP;
        var length = duration < 0 ? Packet.UNKNOWN_DURATION : clockTimeBase.rescale(duration, outputTimeBase);
        Set<PacketFlag> flags = key ? EnumSet.of(PacketFlag.KEY) : Set.of();
        var packet = new Packet(options.trackId(), data, pts, dts, length, flags, sideData);
        pending.add(new RtpFrame(packet, timestamp, complete));
    }

    private List<RtpFrame> take() {
        if (pending.isEmpty()) {
            return List.of();
        }
        var result = pending;
        pending = new ArrayList<>();
        return result;
    }

    @Override
    public String toString() {
        return "RtpDepacketizer[format=" + format + ", lost=" + lost + ", duplicates=" + duplicates + ", late=" + late
                + ", discarded=" + discarded + "]";
    }
}
