package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.PacketSideData;

/**
 * Reassembles AV1 temporal units from AOMedia RTP payloads: OBU elements and fragments are joined and written with
 * size fields, temporal delimiters and tile lists are dropped, and a temporal unit ends at the marker bit or at a
 * timestamp change.
 */
final class Av1Assembler extends Assembler {
    private final ByteSink unit;
    private final ByteSink obu;
    private boolean open;
    private boolean complete;
    private boolean key;
    private boolean sequenceHeader;
    private boolean keyFrameHeader;
    private boolean suspect;
    private long unitTimestamp;
    private boolean obuOpen;

    Av1Assembler(Output out, long limit) {
        super(out, limit);
        this.unit = new ByteSink(limit);
        this.obu = new ByteSink(limit);
    }

    @Override
    void accept(RtpPacket packet, long timestamp, boolean gap) {
        var payload = packet.payload();
        if (payload.length == 0) {
            throw new MediaException.Malformed("empty AV1 payload");
        }
        var aggregation = payload[0] & 0xFF;
        var continuation = (aggregation & 0x80) != 0;
        var willContinue = (aggregation & 0x40) != 0;
        var count = (aggregation >>> 4) & 0x03;
        var newSequence = (aggregation & 0x08) != 0;
        if (newSequence && continuation) {
            throw new MediaException.Malformed("AV1 aggregation header sets both N and Z");
        }
        checkElements(payload, count);
        if (gap) {
            if (open) {
                complete = false;
                suspect |= timestamp != unitTimestamp;
            } else {
                suspect = true;
            }
            obuOpen = false;
        }
        if (open && timestamp != unitTimestamp) {
            if (obuOpen) {
                obuOpen = false;
                complete = false;
            }
            finish();
        }
        if (!open) {
            open = true;
            complete = !suspect || newSequence;
            suspect = false;
            key = newSequence;
            sequenceHeader = false;
            keyFrameHeader = false;
            unitTimestamp = timestamp;
            unit.clear();
        }
        var position = 1;
        for (var index = 0; count != 0 ? index < count : position < payload.length; index++) {
            var last = count != 0 && index == count - 1;
            int length;
            if (last) {
                length = payload.length - position;
            } else {
                var leb = Av1Payloader.readLeb128(payload, position, payload.length);
                position += (int) leb;
                length = (int) (leb >>> 32);
                last = position + length == payload.length;
            }
            if (index == 0 && continuation) {
                if (obuOpen) {
                    obu.write(payload, position, length);
                } else {
                    complete = false;
                }
            } else {
                if (obuOpen) {
                    obuOpen = false;
                    complete = false;
                }
                obu.clear();
                obu.write(payload, position, length);
                obuOpen = true;
            }
            position += length;
            if (last && willContinue) {
                break;
            }
            if (obuOpen) {
                writeObu();
                obuOpen = false;
            }
        }
        if (packet.marker() && !obuOpen) {
            finish();
        }
    }

    /**
     * Checks that the OBU elements cover the payload: {@code count} elements with the last one unsized, or sized
     * elements up to the end when the count is 0.
     */
    private static void checkElements(byte[] payload, int count) {
        var position = 1;
        var index = 0;
        while (count != 0 ? index < count - 1 : position < payload.length) {
            var leb = Av1Payloader.readLeb128(payload, position, payload.length);
            position += (int) leb;
            var length = leb >>> 32;
            if (length > payload.length - position) {
                throw new MediaException.Malformed("AV1 OBU element of " + length + " bytes runs past the payload");
            }
            position += (int) length;
            index++;
        }
        if (count == 0 && index == 0) {
            throw new MediaException.Malformed("AV1 payload holds no OBU element");
        }
    }

    private void writeObu() {
        var data = obu.array();
        var size = obu.size();
        if (size == 0) {
            complete = false;
            return;
        }
        var header = data[0] & 0xFF;
        var type = (header >>> 3) & 0x0F;
        var extended = (header & 0x04) != 0;
        var headerSize = extended ? 2 : 1;
        if (size < headerSize || (header & 0x80) != 0) {
            complete = false;
            return;
        }
        var payloadStart = headerSize;
        var payloadSize = size - headerSize;
        if ((header & 0x02) != 0) {
            long leb;
            try {
                leb = Av1Payloader.readLeb128(data, headerSize, size);
            } catch (MediaException.Malformed exception) {
                complete = false;
                return;
            }
            payloadStart += (int) leb;
            var declared = leb >>> 32;
            if (declared > size - payloadStart) {
                complete = false;
                return;
            }
            payloadSize = (int) declared;
        }
        if (type == Av1Payloader.TEMPORAL_DELIMITER || type == Av1Payloader.TILE_LIST) {
            return;
        }
        if (type == Av1Payloader.SEQUENCE_HEADER) {
            sequenceHeader = true;
        } else if ((type == Av1Payloader.FRAME_HEADER || type == Av1Payloader.FRAME) && payloadSize > 0) {
            var first = data[payloadStart] & 0xFF;
            keyFrameHeader |= (first & 0x80) == 0 && (first & 0x60) == 0;
        }
        unit.write(header | 0x02);
        if (extended) {
            unit.write(data[1]);
        }
        unit.writeLeb128(payloadSize);
        unit.write(data, payloadStart, payloadSize);
    }

    private void finish() {
        open = false;
        if (unit.size() == 0) {
            return;
        }
        var isKey = key || (sequenceHeader && keyFrameHeader);
        out.add(unit.toByteArray(), unitTimestamp, isKey, complete, -1, PacketSideData.NONE);
    }

    @Override
    void flush() {
        if (obuOpen) {
            obuOpen = false;
            complete = false;
        }
        if (open) {
            finish();
        }
        suspect = false;
    }
}
