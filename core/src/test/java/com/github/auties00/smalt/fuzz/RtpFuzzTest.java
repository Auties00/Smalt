package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.rtp.RtpDepacketizer;
import com.github.auties00.smalt.rtp.RtpDepacketizerOptions;
import com.github.auties00.smalt.rtp.RtpFrame;
import com.github.auties00.smalt.rtp.RtpPacket;
import com.github.auties00.smalt.rtp.RtpPacketizer;
import com.github.auties00.smalt.rtp.RtpPacketizerOptions;
import com.github.auties00.smalt.rtp.RtpPayloadFormat;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.track.Packet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fuzz targets of the RTP payload formats: the RTP packet parser and serializer with the RFC 8285 header
 * extension elements ({@link RtpPacket#parse}), the depacketizers fed datagrams ({@link RtpDepacketizer}) and the
 * packetizers fed units whose packets are depacketized again ({@link RtpPacketizer}). The packet targets read the
 * {@link FuzzPackets} format with a one-byte header: the options byte picks the payload format (its {@code rtpmap})
 * and the codec configuration is the {@code fmtp} value, as text.
 */
class RtpFuzzTest {
    /** The payload formats, chosen by the options byte modulo their count. */
    static final String[] FORMATS = {"H264/90000", "VP8/90000", "VP9/90000", "AV1/90000", "opus/48000/2",
            "AMR/8000/1", "AMR-WB/16000/1", "mpeg4-generic/48000/2"};

    @FuzzTest
    void header(byte[] data) {
        var run = Fuzz.start("RTP header");
        run.value("parse", () -> RtpPacket.parse(data)).ifPresent(packet -> {
            var again = RtpPacket.parse(packet.toByteArray());
            if (!Arrays.equals(packet.payload(), again.payload()) || packet.sequenceNumber() != again.sequenceNumber()
                    || packet.timestamp() != again.timestamp() || !packet.csrcs().equals(again.csrcs())
                    || !java.util.Objects.equals(packet.extension(), again.extension())) {
                throw new AssertionError("packet does not survive a write and a parse: " + packet);
            }
            var extension = packet.extension();
            if (extension != null) {
                run.value("elements", () -> extension.hasElements() ? extension.elements() : null);
            }
            run.output();
        });
    }

    private static RtpPayloadFormat format(FuzzPackets input) {
        var rtpmap = FORMATS[input.header().options() % FORMATS.length];
        var fmtp = input.header().config().map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1)).orElse(null);
        return RtpPayloadFormat.fromSdp(rtpmap, fmtp);
    }

    @FuzzTest
    void depacketize(byte[] data) {
        var run = Fuzz.start("RTP depacketize");
        var input = FuzzPackets.parse(data, 1);
        var format = run.value("format", () -> format(input));
        if (format.isEmpty()) {
            return;
        }
        var options = RtpDepacketizerOptions.builder().limits(Fuzz.LIMITS)
                .reorderWindow(input.header().options() >>> 4).build();
        var depacketizer = run.value("create", () -> RtpDepacketizer.create(format.get(), options));
        if (depacketizer.isEmpty()) {
            return;
        }
        for (var index = 0; index < input.packets().size(); index++) {
            var part = input.packets().get(index);
            var packet = run.value("parse " + index, () -> RtpPacket.parse(part.data()));
            packet.ifPresent(value -> check(run, depacketizer.get().push(value)));
        }
        check(run, depacketizer.get().flush());
        depacketizer.get().codecConfig();
    }

    private static void check(com.github.auties00.smalt.testkit.fuzz.FuzzRun run, List<RtpFrame> frames) {
        for (var frame : frames) {
            if (frame.packet().data().length > Fuzz.LIMITS.maxBytes()) {
                throw new AssertionError("unit larger than the limit");
            }
            run.output();
        }
    }

    @FuzzTest
    void packetize(byte[] data) {
        var run = Fuzz.start("RTP packetize");
        var input = FuzzPackets.parse(data, 1);
        var format = run.value("format", () -> format(input));
        if (format.isEmpty()) {
            return;
        }
        var options = RtpPacketizerOptions.builder().ssrc(1).initialSequenceNumber(65_000).initialTimestamp(7)
                .maxPacketSize(RtpPacketizerOptions.MIN_PACKET_SIZE + (input.header().options() >>> 3) * 16)
                .build();
        var packetizer = run.value("create", () -> RtpPacketizer.create(format.get(), options));
        if (packetizer.isEmpty()) {
            return;
        }
        var packets = new ArrayList<RtpPacket>();
        var accepted = new ArrayList<byte[]>();
        var duration = format.get().clockRate() / 50;
        for (var index = 0; index < input.packets().size(); index++) {
            var part = input.packets().get(index);
            var unit = new Packet(1, part.data(), (long) index * duration, (long) index * duration, duration,
                    part.keyframe());
            var sent = run.value("packetize " + index, () -> packetizer.get().packetize(unit));
            sent.ifPresent(list -> {
                packets.addAll(list);
                accepted.add(part.data());
            });
        }
        run.value("flush", () -> packetizer.get().flush()).ifPresent(packets::addAll);
        for (var packet : packets) {
            if (packet.size() > options.maxPacketSize()) {
                throw new AssertionError("packet of " + packet.size() + " bytes exceeds " + options.maxPacketSize());
            }
        }
        var depacketizer = run.value("depacketizer", () -> RtpDepacketizer.create(format.get(),
                RtpDepacketizerOptions.builder().limits(Fuzz.LIMITS).build()));
        if (depacketizer.isEmpty()) {
            return;
        }
        var frames = new ArrayList<RtpFrame>();
        for (var packet : packets) {
            frames.addAll(depacketizer.get().push(RtpPacket.parse(packet.toByteArray())));
        }
        frames.addAll(depacketizer.get().flush());
        if (format.get().encodingName().equals("VP8") && accepted.size() == input.packets().size()) {
            if (frames.size() != accepted.size()) {
                throw new AssertionError(accepted.size() + " units sent, " + frames.size() + " received");
            }
            for (var index = 0; index < frames.size(); index++) {
                if (!Arrays.equals(accepted.get(index), frames.get(index).packet().data())) {
                    throw new AssertionError("unit " + index + " differs after a round trip");
                }
            }
        }
        frames.forEach(frame -> run.output());
    }
}
