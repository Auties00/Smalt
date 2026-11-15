package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.track.PacketSideData;

import java.util.Map;
import java.util.TreeMap;

/**
 * Reads RFC 4867 payloads of every mode into frame-blocks of storage-format frames.
 *
 * <p>Payloads whose length does not match their table of contents, or that hold a frame type the payload format
 * forbids, are rejected as section 4.5.1 recommends. A frame whose CRC does not match gets its quality bit cleared.
 * Blocks that repeat a timestamp already emitted (redundant transmission) are dropped. Interleaved blocks wait until
 * a packet of a later interleaving group arrives, or their group is whole, and come out in timestamp order.
 */
final class AmrAssembler extends Assembler {
    private final AmrFormat format;
    private final TreeMap<Long, byte[]> blocks = new TreeMap<>();
    private long lastEmitted = Long.MIN_VALUE;

    AmrAssembler(Output out, long limit, AmrFormat format) {
        super(out, limit);
        this.format = format;
    }

    @Override
    void accept(RtpPacket packet, long timestamp, boolean gap) {
        var payload = packet.payload();
        var frames = format.octetAligned ? parseOctetAligned(payload) : parseBandwidthEfficient(payload);
        var parsed = frames.frames;
        if (parsed.length == 0 || parsed.length % format.channels != 0) {
            throw new MediaException.Malformed(parsed.length + " AMR frames are not a whole number of "
                    + format.channels + "-channel frame-blocks");
        }
        var duration = format.frameDuration();
        var stride = format.interleaving > 0 ? (long) (frames.ill + 1) * duration : duration;
        var count = parsed.length / format.channels;
        for (var block = 0; block < count; block++) {
            var size = 0;
            for (var channel = 0; channel < format.channels; channel++) {
                size += parsed[block * format.channels + channel].length;
            }
            var data = new byte[size];
            var position = 0;
            for (var channel = 0; channel < format.channels; channel++) {
                var frame = parsed[block * format.channels + channel];
                System.arraycopy(frame, 0, data, position, frame.length);
                position += frame.length;
            }
            var blockTimestamp = timestamp + block * stride;
            if (blockTimestamp > lastEmitted) {
                blocks.putIfAbsent(blockTimestamp, data);
            }
        }
        if (format.interleaving == 0) {
            release(Long.MAX_VALUE);
            return;
        }
        var groupStart = timestamp - (long) frames.ilp * duration;
        var groupEnd = groupStart + (long) count * (frames.ill + 1) * duration;
        release(groupStart);
        if (blocks.subMap(groupStart, groupEnd).size() == count * (frames.ill + 1)) {
            release(groupEnd);
        }
    }

    private void release(long before) {
        var duration = format.frameDuration();
        while (!blocks.isEmpty() && blocks.firstKey() < before) {
            Map.Entry<Long, byte[]> entry = blocks.pollFirstEntry();
            lastEmitted = entry.getKey();
            out.add(entry.getValue(), entry.getKey(), true, true, duration, PacketSideData.NONE);
        }
    }

    @Override
    void flush() {
        release(Long.MAX_VALUE);
        lastEmitted = Long.MIN_VALUE;
    }

    /** The frames of one payload, as storage-format frames, and its interleaving fields. */
    private record Parsed(byte[][] frames, int ill, int ilp) {
    }

    private Parsed parseBandwidthEfficient(byte[] payload) {
        var reader = new MsbBitReader(payload);
        reader.read(4);
        var types = new int[16];
        var qualities = new int[16];
        var count = 0;
        var more = true;
        while (more) {
            if (reader.bitsLeft() < 6) {
                throw new MediaException.Malformed("AMR table of contents is truncated");
            }
            more = reader.read(1) == 1;
            var type = reader.read(4);
            var quality = reader.read(1);
            if (!format.validType(type)) {
                throw new MediaException.Malformed("AMR frame type " + type + " is not allowed");
            }
            if (count == types.length) {
                types = java.util.Arrays.copyOf(types, count * 2);
                qualities = java.util.Arrays.copyOf(qualities, count * 2);
            }
            types[count] = type;
            qualities[count++] = quality;
        }
        var frames = new byte[count][];
        for (var index = 0; index < count; index++) {
            var bits = format.bits(types[index]);
            if (reader.bitsLeft() < bits) {
                throw new MediaException.Malformed("AMR speech frame is truncated");
            }
            var frame = new byte[1 + (bits + 7) / 8];
            frame[0] = (byte) ((types[index] << 3) | (qualities[index] << 2));
            for (var bit = 0; bit < bits; bit++) {
                frame[1 + (bit >>> 3)] |= (byte) (reader.read(1) << (7 - (bit & 7)));
            }
            frames[index] = frame;
        }
        if (reader.bitsLeft() >= 8) {
            throw new MediaException.Malformed("AMR payload is longer than its table of contents");
        }
        return new Parsed(frames, 0, 0);
    }

    private Parsed parseOctetAligned(byte[] payload) {
        var position = 1;
        var ill = 0;
        var ilp = 0;
        if (format.interleaving > 0) {
            if (payload.length < 2) {
                throw new MediaException.Malformed("AMR interleaving header is truncated");
            }
            ill = (payload[1] >>> 4) & 0x0F;
            ilp = payload[1] & 0x0F;
            if (ilp > ill) {
                throw new MediaException.Malformed("AMR interleaving index " + ilp + " exceeds the length " + ill);
            }
            position = 2;
        }
        var tocStart = position;
        var more = true;
        while (more) {
            if (position >= payload.length) {
                throw new MediaException.Malformed("AMR table of contents is truncated");
            }
            var entry = payload[position++] & 0xFF;
            more = (entry & 0x80) != 0;
            var type = (entry >>> 3) & 0x0F;
            if (!format.validType(type)) {
                throw new MediaException.Malformed("AMR frame type " + type + " is not allowed");
            }
        }
        var count = position - tocStart;
        var sizes = new int[count];
        var withData = 0;
        var total = 0;
        for (var index = 0; index < count; index++) {
            var type = (payload[tocStart + index] >>> 3) & 0x0F;
            sizes[index] = (format.bits(type) + 7) / 8;
            total += sizes[index];
            if (sizes[index] > 0) {
                withData++;
            }
        }
        var crcStart = position;
        if (format.crc) {
            position += withData;
        }
        if (position + total != payload.length) {
            throw new MediaException.Malformed("AMR payload of " + payload.length + " bytes does not match its "
                    + (position + total) + "-byte table of contents");
        }
        var frames = new byte[count][];
        for (var index = 0; index < count; index++) {
            frames[index] = new byte[1 + sizes[index]];
            frames[index][0] = (byte) (payload[tocStart + index] & 0x7C);
        }
        if (!format.robustSorting) {
            for (var index = 0; index < count; index++) {
                System.arraycopy(payload, position, frames[index], 1, sizes[index]);
                position += sizes[index];
            }
        } else {
            var longest = 0;
            for (var size : sizes) {
                longest = Math.max(longest, size);
            }
            for (var octet = 0; octet < longest; octet++) {
                for (var index = 0; index < count; index++) {
                    if (octet < sizes[index]) {
                        frames[index][1 + octet] = payload[position++];
                    }
                }
            }
        }
        if (format.crc) {
            var crc = crcStart;
            for (var index = 0; index < count; index++) {
                if (sizes[index] == 0) {
                    continue;
                }
                var type = (frames[index][0] >>> 3) & 0x0F;
                if (AmrFormat.crc(frames[index], 1, format.classA(type)) != (payload[crc++] & 0xFF)) {
                    frames[index][0] &= (byte) ~0x04;
                }
            }
        }
        return new Parsed(frames, ill, ilp);
    }
}
