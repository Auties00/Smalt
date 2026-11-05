package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Packetizes access units per RFC 3640 with the AU header section the format configures: consecutive AUs are
 * aggregated, an AU too large for one packet is fragmented where the mode allows it, every AU index and delta is 0
 * (AUs in decoding order, constant duration), the random access flag follows the input key flag, and an empty
 * auxiliary section is written when one is configured.
 */
final class Mpeg4GenericPayloader extends Payloader {
    private final Mpeg4GenericFormat format;
    private final int framesPerPacket;
    private final List<byte[]> pending = new ArrayList<>();
    private final List<Boolean> pendingKeys = new ArrayList<>();
    private long pendingTimestamp;
    private int pendingBytes;

    Mpeg4GenericPayloader(int maxPayload, Mpeg4GenericFormat format, int framesPerPacket) {
        super(maxPayload);
        this.format = format;
        var aggregate = format.sizeLength > 0 || format.constantSize > 0;
        this.framesPerPacket = aggregate ? framesPerPacket : 1;
    }

    private int headerBits(boolean first) {
        var bits = format.sizeLength + (first ? format.indexLength : format.indexDeltaLength);
        bits += format.ctsDeltaLength > 0 ? 1 : 0;
        bits += format.dtsDeltaLength > 0 ? 1 : 0;
        bits += format.randomAccessIndication ? 1 : 0;
        return bits + format.streamStateIndication;
    }

    private int overhead(int units) {
        var bits = 0;
        for (var index = 0; index < units; index++) {
            bits += headerBits(index == 0);
        }
        var size = format.hasHeaders() ? 2 + (bits + 7) / 8 : 0;
        if (format.auxiliaryDataSizeLength > 0) {
            size += (format.auxiliaryDataSizeLength + 7) / 8;
        }
        return size;
    }

    @Override
    void packetize(byte[] data, long timestamp, boolean key, Output out) {
        if (data.length == 0) {
            throw new MediaException.Malformed("empty access unit");
        }
        if (format.sizeLength > 0 && data.length >= 1L << format.sizeLength) {
            throw new MediaException.LimitExceeded("access unit of " + data.length + " bytes does not fit sizeLength="
                    + format.sizeLength);
        }
        if (format.constantSize > 0 && data.length != format.constantSize) {
            throw new MediaException.Malformed("access unit of " + data.length + " bytes is not constantSize="
                    + format.constantSize);
        }
        if (!pending.isEmpty()) {
            var expected = pendingTimestamp + (long) pending.size() * format.duration;
            var fits = overhead(pending.size() + 1) + pendingBytes + data.length <= maxPayload;
            if (timestamp != expected || !fits || pending.size() >= framesPerPacket) {
                sendPending(out);
            }
        }
        if (overhead(1) + data.length > maxPayload) {
            if (!format.fragmentable()) {
                throw new MediaException.LimitExceeded("access unit of " + data.length
                        + " bytes does not fit a packet and mode " + format.mode + " forbids fragmentation");
            }
            fragment(data, timestamp, key, out);
            return;
        }
        if (pending.isEmpty()) {
            pendingTimestamp = timestamp;
        }
        pending.add(data);
        pendingKeys.add(key);
        pendingBytes += data.length;
        if (pending.size() >= framesPerPacket) {
            sendPending(out);
        }
    }

    private void writeHeaders(MsbBitWriter writer, int[] sizes, boolean[] keys) {
        var bits = 0;
        for (var index = 0; index < sizes.length; index++) {
            bits += headerBits(index == 0);
        }
        if (format.hasHeaders()) {
            writer.write(bits, 16);
            for (var index = 0; index < sizes.length; index++) {
                writer.writeLong(sizes[index], format.sizeLength);
                writer.writeLong(0, index == 0 ? format.indexLength : format.indexDeltaLength);
                if (format.ctsDeltaLength > 0) {
                    writer.write(0, 1);
                }
                if (format.dtsDeltaLength > 0) {
                    writer.write(0, 1);
                }
                if (format.randomAccessIndication) {
                    writer.write(keys[index] ? 1 : 0, 1);
                }
                writer.writeLong(0, format.streamStateIndication);
            }
            writer.alignWithZeros();
        }
        if (format.auxiliaryDataSizeLength > 0) {
            writer.writeLong(0, format.auxiliaryDataSizeLength);
            writer.alignWithZeros();
        }
    }

    private void sendPending(Output out) {
        if (pending.isEmpty()) {
            return;
        }
        var sizes = new int[pending.size()];
        var keys = new boolean[pending.size()];
        for (var index = 0; index < sizes.length; index++) {
            sizes[index] = pending.get(index).length;
            keys[index] = pendingKeys.get(index);
        }
        var writer = new MsbBitWriter(64 + pendingBytes);
        writeHeaders(writer, sizes, keys);
        for (var unit : pending) {
            writer.writeBytes(unit, 0, unit.length);
        }
        out.add(writer.toByteArray(), true, pendingTimestamp);
        pending.clear();
        pendingKeys.clear();
        pendingBytes = 0;
    }

    private void fragment(byte[] data, long timestamp, boolean key, Output out) {
        var room = maxPayload - overhead(1);
        var sizes = balancedSizes(data.length, room);
        var position = 0;
        for (var index = 0; index < sizes.length; index++) {
            var writer = new MsbBitWriter(64 + sizes[index]);
            writeHeaders(writer, new int[]{data.length}, new boolean[]{key && index == 0});
            writer.writeBytes(data, position, sizes[index]);
            position += sizes[index];
            out.add(writer.toByteArray(), index == sizes.length - 1, timestamp);
        }
    }

    @Override
    void flush(Output out) {
        sendPending(out);
    }
}
