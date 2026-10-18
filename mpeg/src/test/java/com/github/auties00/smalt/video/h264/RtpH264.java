package com.github.auties00.smalt.video.h264;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RFC 6184 packetization of Annex B access units as a real-time call path does it (single NAL unit,
 * STAP-A and FU-A packets, a 1200-byte MTU less the RTP header and the SRTP room), and a receiver
 * that reassembles what survives a lossy channel the way lib's depacketizer does: fragments are
 * concatenated without a sequence check, so a lost middle fragment truncates the NAL unit, and a
 * NAL unit whose first or last fragment is lost is dropped. The receiver also reports, per access
 * unit, whether any of its packets was lost, which is what a depacketizer that tracks RTP sequence
 * numbers can tell the decoder.
 */
public final class RtpH264 {
    /** The payload budget of one RTP packet: MTU 1200 minus the RTP header and SRTP room. */
    static final int PAYLOAD_BUDGET = 1200 - 12 - 64;

    private RtpH264() {
        throw new AssertionError();
    }

    /** One RTP packet of an access unit. */
    public record RtpPacket(int accessUnit, byte[] payload, boolean marker) {
    }

    /** What the receiver hands the decoder for one access unit. */
    public record Received(int accessUnit, byte[] data, boolean incomplete) {
    }

    /** Splits an Annex B access unit into RTP payloads. */
    public static List<byte[]> packetize(byte[] accessUnit) {
        var payloads = new ArrayList<byte[]>();
        var pending = new ArrayList<byte[]>();
        var pendingBytes = 1;
        for (var nal : H264TestStreams.nalUnits(accessUnit)) {
            if (nal.length > PAYLOAD_BUDGET) {
                flush(pending, payloads);
                pendingBytes = 1;
                fragment(nal, payloads);
                continue;
            }
            if (pendingBytes + 2 + nal.length > PAYLOAD_BUDGET) {
                flush(pending, payloads);
                pendingBytes = 1;
            }
            pending.add(nal);
            pendingBytes += 2 + nal.length;
        }
        flush(pending, payloads);
        return payloads;
    }

    /** Packetizes a sequence of access units, numbering their packets in order. */
    public static List<RtpPacket> packetize(List<byte[]> accessUnits) {
        var packets = new ArrayList<RtpPacket>();
        for (var i = 0; i < accessUnits.size(); i++) {
            var payloads = packetize(accessUnits.get(i));
            for (var k = 0; k < payloads.size(); k++) {
                packets.add(new RtpPacket(i, payloads.get(k), k == payloads.size() - 1));
            }
        }
        return packets;
    }

    private static void flush(List<byte[]> pending, List<byte[]> out) {
        if (pending.size() == 1) {
            out.add(pending.getFirst());
        } else if (pending.size() > 1) {
            var stap = new ByteArrayOutputStream();
            var nri = 0;
            for (var nal : pending) {
                nri = Math.max(nri, nal[0] & 0x60);
            }
            stap.write(nri | 24);
            for (var nal : pending) {
                stap.write(nal.length >>> 8);
                stap.write(nal.length);
                stap.writeBytes(nal);
            }
            out.add(stap.toByteArray());
        }
        pending.clear();
    }

    private static void fragment(byte[] nal, List<byte[]> out) {
        var indicator = (nal[0] & 0x60) | 28;
        var type = nal[0] & 0x1F;
        var budget = PAYLOAD_BUDGET - 2;
        var offset = 1;
        while (offset < nal.length) {
            var chunk = Math.min(budget, nal.length - offset);
            var header = type | (offset == 1 ? 0x80 : 0) | (offset + chunk == nal.length ? 0x40 : 0);
            var payload = new byte[2 + chunk];
            payload[0] = (byte) indicator;
            payload[1] = (byte) header;
            System.arraycopy(nal, offset, payload, 2, chunk);
            out.add(payload);
            offset += chunk;
        }
    }

    /**
     * Delivers the surviving packets of a stream, one access unit at a time.
     *
     * @param packets         every packet sent, in order
     * @param lost            which packets the channel lost
     * @param requireMarker   whether an access unit whose last (marker) packet was lost is dropped,
     *                        as lib's depacketizer does today, rather than delivered as it is
     * @return the access units the receiver hands to the decoder, in order
     */
    public static List<Received> receive(List<RtpPacket> packets, boolean[] lost, boolean requireMarker) {
        var result = new ArrayList<Received>();
        var index = 0;
        while (index < packets.size()) {
            var unit = packets.get(index).accessUnit();
            var end = index;
            while (end < packets.size() && packets.get(end).accessUnit() == unit) {
                end++;
            }
            var nals = new ArrayList<byte[]>();
            var fragments = new ArrayList<byte[]>();
            var fragmentHeader = -1;
            var incomplete = false;
            var markerSeen = false;
            for (var i = index; i < end; i++) {
                if (lost[i]) {
                    incomplete = true;
                    continue;
                }
                var packet = packets.get(i);
                markerSeen |= packet.marker();
                var payload = packet.payload();
                var type = payload[0] & 0x1F;
                if (type == 28) {
                    var fuHeader = payload[1] & 0xFF;
                    if ((fuHeader & 0x80) != 0) {
                        fragments.clear();
                        fragmentHeader = (payload[0] & 0x60) | (fuHeader & 0x1F);
                    } else if (fragments.isEmpty()) {
                        continue;
                    }
                    fragments.add(Arrays.copyOfRange(payload, 2, payload.length));
                    if ((fuHeader & 0x40) != 0) {
                        var nal = new ByteArrayOutputStream();
                        nal.write(fragmentHeader);
                        for (var fragment : fragments) {
                            nal.writeBytes(fragment);
                        }
                        fragments.clear();
                        nals.add(nal.toByteArray());
                    }
                } else if (type == 24) {
                    var offset = 1;
                    while (offset + 2 <= payload.length) {
                        var length = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
                        offset += 2;
                        if (length == 0 || offset + length > payload.length) {
                            break;
                        }
                        nals.add(Arrays.copyOfRange(payload, offset, offset + length));
                        offset += length;
                    }
                } else {
                    nals.add(payload);
                }
            }
            if (!nals.isEmpty() && (markerSeen || !requireMarker)) {
                result.add(new Received(unit, H264TestStreams.annexB(nals), incomplete));
            }
            index = end;
        }
        return result;
    }
}
