package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns the packets of one elementary stream into RTP packets of a payload format.
 *
 * <p>Each input {@link Packet} holds one unit in the codec's canonical form (see {@link Track}): a length-prefixed
 * or Annex B H.264 access unit, a VP8 or VP9 frame (a VP9 superframe is split into its frames), an AV1 temporal
 * unit of OBUs, one Opus packet, AMR or AMR-WB storage-format frames (a multiple of the channel count), or one raw
 * AAC access unit. The packets come out in transmission order with consecutive sequence numbers, the payload type
 * and synchronization source of the {@link RtpPacketizerOptions}, and the marker bit and timestamps the payload
 * format prescribes:
 * <ul>
 *   <li>H.264: single NAL unit packets, STAP-A aggregation of small NAL units and FU-A fragmentation in mode 1;
 *       one NAL unit per packet in mode 0; STAP-B, FU-B and FU-A with decoding order numbers in mode 2. The marker
 *       is set on the last packet of each access unit.</li>
 *   <li>VP8 and VP9: the payload descriptor with the picture identifier of {@link
 *       RtpPacketizerOptions#pictureIdBits()}, fragments of nearly equal size, the marker on the last packet of
 *       each frame; VP9 key frames carry the scalability structure with the frame size.</li>
 *   <li>AV1: the aggregation header, OBUs without size fields, the temporal delimiter and tile list OBUs dropped,
 *       and the {@code N} bit on the first packet of a key temporal unit that holds a sequence header; with
 *       {@link RtpPacketizerOptions#dependencyDescriptorId()} set, every packet carries an
 *       {@link Av1DependencyDescriptor} of {@link Av1TemplateStructure#singleLayer(int)}, attached to the first
 *       packet and to the first packet of every key temporal unit, one frame per temporal unit.</li>
 *   <li>Opus: one Opus packet per RTP packet.</li>
 *   <li>AMR and AMR-WB: the bandwidth-efficient or octet-aligned mode with the frame CRCs, robust sorting and
 *       interleaving the format signals, {@link RtpPacketizerOptions#framesPerPacket()} frame-blocks per packet;
 *       the marker is set on the first packet.</li>
 *   <li>{@code mpeg4-generic}: the AU header section the format configures, up to
 *       {@link RtpPacketizerOptions#framesPerPacket()} access units per packet or the fragments of one access unit;
 *       the marker is set on every packet that ends an access unit.</li>
 * </ul>
 *
 * <p>Packetization is deterministic: given the same options, with the synchronization source, the initial sequence
 * number and the initial timestamp fixed, the same input gives the same packets.
 *
 * <p>Instances are not thread-safe.
 */
public final class RtpPacketizer {
    private final RtpPayloadFormat format;
    private final RtpPacketizerOptions options;
    private final Payloader payloader;
    private final long ssrc;
    private final long initialTimestamp;
    private final Rational inputTimeBase;
    private final Rational clockTimeBase;
    private int sequenceNumber;
    private long lastTimestamp;
    private List<RtpPacket> pending;

    private RtpPacketizer(RtpPayloadFormat format, RtpPacketizerOptions options, Rational timeBase,
                          int nalLengthSize) {
        this.format = format;
        this.options = options;
        var random = ThreadLocalRandom.current();
        this.ssrc = options.ssrc().orElseGet(() -> random.nextLong(1L << 32));
        this.sequenceNumber = options.initialSequenceNumber().orElseGet(() -> random.nextInt(1 << 16));
        this.initialTimestamp = options.initialTimestamp().orElseGet(() -> random.nextLong(1L << 32));
        this.clockTimeBase = Rational.timeBaseOf(format.clockRate());
        this.inputTimeBase = timeBase != null ? timeBase : clockTimeBase;
        var maxPayload = options.maxPacketSize() - RtpPacket.HEADER_SIZE;
        this.payloader = switch (format.encodingName()) {
            case "H264" -> new H264Payloader(maxPayload, format, options, nalLengthSize);
            case "VP8" -> new Vp8Payloader(maxPayload, options.pictureIdBits(), sequenceNumber);
            case "VP9" -> new Vp9Payloader(maxPayload, options.pictureIdBits(), sequenceNumber);
            case "AV1" -> new Av1Payloader(maxPayload, options.dependencyDescriptorId().orElse(0), sequenceNumber);
            case "opus" -> new OpusPayloader(maxPayload);
            case "AMR", "AMR-WB" -> new AmrPayloader(maxPayload, AmrFormat.of(format), options.framesPerPacket(),
                    options.interleavingLength());
            case "mpeg4-generic" -> new Mpeg4GenericPayloader(maxPayload, Mpeg4GenericFormat.of(format),
                    options.framesPerPacket());
            default -> throw new MediaException.Unsupported(format.encodingName(), "no RTP packetizer for "
                    + format.encodingName());
        };
    }

    /**
     * Creates a packetizer of a payload format.
     *
     * <p>H.264 input is read as NAL units with 4-byte length prefixes when its lengths cover it exactly, and
     * otherwise as Annex B when it starts with a start code.
     *
     * @param format the payload format
     * @param options the header fields, packet size, input time base and per-format settings
     * @return the packetizer
     * @throws IllegalArgumentException if the options do not suit the format: an AMR interleaving group larger than
     * the format's {@code interleaving}
     * @throws MediaException.Unsupported if the format's encoding name is not one this package implements
     * @throws MediaException.Malformed if a format parameter the packetizer needs is invalid
     */
    public static RtpPacketizer create(RtpPayloadFormat format, RtpPacketizerOptions options) {
        return new RtpPacketizer(format, options, options.timeBase().orElse(null), 4);
    }

    /**
     * Creates a packetizer for a track, with the payload format {@link RtpPayloadFormat#forTrack(Track)} gives.
     *
     * <p>The input timestamps are in the track's time base unless the options set one, and H.264 input uses the
     * NAL unit length size of the track's {@code avcC} record.
     *
     * @param track the track whose packets will be packetized
     * @param options the header fields, packet size, input time base and per-format settings
     * @return the packetizer
     * @throws IllegalArgumentException if the options do not suit the format: an AMR interleaving group larger than
     * the format's {@code interleaving}
     * @throws MediaException.Unsupported if no payload format of this package carries the track's codec
     * @throws MediaException.Malformed if the track's codec configuration cannot be parsed
     */
    public static RtpPacketizer create(Track track, RtpPacketizerOptions options) {
        var format = RtpPayloadFormat.forTrack(track);
        var lengthSize = 4;
        if (track.codec() == Codec.H264) {
            var config = track.codecConfig().filter(bytes -> bytes.length > 0);
            if (config.isPresent()) {
                lengthSize = NalRecords.parseAvcStrict(config.get()).lengthSize();
            }
        }
        return new RtpPacketizer(format, options, options.timeBase().orElse(track.timeBase()), lengthSize);
    }

    /**
     * {@return the payload format}
     */
    public RtpPayloadFormat format() {
        return format;
    }

    /**
     * {@return the synchronization source identifier of every packet}
     */
    public long ssrc() {
        return ssrc;
    }

    /**
     * {@return the sequence number the next packet will carry}
     */
    public int nextSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Packetizes one unit.
     *
     * <p>The RTP timestamp comes from the presentation timestamp, or the decoding timestamp when there is none.
     * Formats that aggregate (AMR, AMR-WB and {@code mpeg4-generic} with several frames per packet) may hold the
     * unit back and return its packets from a later call or from {@link #flush()}.
     *
     * @param packet the unit
     * @return the packets now ready, in transmission order, possibly none
     * @throws IllegalArgumentException if the packet has no timestamp
     * @throws MediaException.Malformed if the unit is not in the codec's canonical form
     * @throws MediaException.LimitExceeded if the unit, or a part of it the format cannot fragment, does not fit
     * the largest packet size; the packets of the unit made before the failure are discarded and their sequence
     * numbers reused
     */
    public List<RtpPacket> packetize(Packet packet) {
        var pts = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        if (pts == Packet.NO_TIMESTAMP) {
            throw new IllegalArgumentException("packet has no timestamp");
        }
        var timestamp = initialTimestamp + inputTimeBase.rescale(pts, clockTimeBase);
        var first = sequenceNumber;
        var result = new ArrayList<RtpPacket>();
        pending = result;
        try {
            payloader.packetize(packet.data(), timestamp, packet.keyframe(), this::emit);
        } catch (RuntimeException exception) {
            sequenceNumber = first;
            throw exception;
        } finally {
            pending = null;
        }
        return result;
    }

    /**
     * Sends every unit held back for aggregation.
     *
     * @return the packets, in transmission order, possibly none
     */
    public List<RtpPacket> flush() {
        pending = new ArrayList<>();
        payloader.flush(this::emit);
        var result = pending;
        pending = null;
        return result;
    }

    private void emit(byte[] payload, boolean marker, long timestamp, RtpHeaderExtension extension) {
        lastTimestamp = timestamp;
        pending.add(new RtpPacket(marker, options.payloadType(), sequenceNumber, timestamp & 0xFFFF_FFFFL, ssrc,
                List.of(), extension, payload));
        sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
    }

    @Override
    public String toString() {
        return "RtpPacketizer[format=" + format + ", ssrc=" + ssrc + ", nextSequenceNumber=" + sequenceNumber
                + ", lastTimestamp=" + (lastTimestamp & 0xFFFF_FFFFL) + "]";
    }
}
