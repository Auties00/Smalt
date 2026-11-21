package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.header.Crc32Mpeg;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes program streams byte by byte for the tests, independently of {@link PsMuxer}: pack headers of either
 * syntax, system headers, program stream maps, PES packets with any header fields, padding and garbage.
 */
final class PsWriter {
    /** One entry of a program stream map: its stream type, stream id and ISO 639 language, or {@code null}. */
    record MapEntry(int streamType, int streamId, String language) {
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final boolean mpeg1;

    PsWriter(boolean mpeg1) {
        this.mpeg1 = mpeg1;
    }

    PsWriter() {
        this(false);
    }

    /** Appends a pack header with a system clock reference in 90 kHz ticks and a 10.08 Mbit/s mux rate. */
    PsWriter pack(long scr) {
        var rate = 25_200;
        var base = scr & ((1L << 33) - 1);
        if (mpeg1) {
            out.writeBytes(new byte[]{0, 0, 1, (byte) 0xBA});
            out.writeBytes(TsWriter.timestamp(2, base));
            out.write(0x80 | (rate >> 15) & 0x7F);
            out.write((rate >> 7) & 0xFF);
            out.write((rate & 0x7F) << 1 | 1);
            return this;
        }
        out.writeBytes(new byte[]{0, 0, 1, (byte) 0xBA,
                (byte) (0x44 | ((base >> 30) & 7) << 3 | ((base >> 28) & 3)),
                (byte) (base >> 20),
                (byte) (((base >> 15) & 0x1F) << 3 | 0x04 | ((base >> 13) & 3)),
                (byte) (base >> 5),
                (byte) ((base & 0x1F) << 3 | 0x04),
                0x01,
                (byte) (rate >> 14), (byte) (rate >> 6), (byte) ((rate & 0x3F) << 2 | 3), (byte) 0xF8});
        return this;
    }

    /** Appends a system header listing stream ids with a buffer bound of 58 KiB each. */
    PsWriter systemHeader(int... ids) {
        var length = 6 + 3 * ids.length;
        out.writeBytes(new byte[]{0, 0, 1, (byte) 0xBB, (byte) (length >> 8), (byte) length, (byte) 0x80, (byte) 0xC4,
                (byte) 0xE1, 0x04, (byte) 0xE1, (byte) 0xFF});
        for (var id : ids) {
            out.writeBytes(new byte[]{(byte) id, (byte) 0xE0, 0x3A});
        }
        return this;
    }

    /** Appends a PES packet with a presentation and a decoding time ({@code -1} for none). */
    PsWriter pes(int streamId, long pts, long dts, byte[] payload) {
        if (mpeg1) {
            var header = new ByteArrayOutputStream();
            if (pts >= 0 && dts >= 0) {
                header.writeBytes(TsWriter.timestamp(3, pts));
                header.writeBytes(TsWriter.timestamp(1, dts));
            } else if (pts >= 0) {
                header.writeBytes(TsWriter.timestamp(2, pts));
            } else {
                header.write(0x0F);
            }
            return raw(streamId, header.toByteArray(), payload);
        }
        var fields = new ByteArrayOutputStream();
        if (pts >= 0) {
            fields.writeBytes(TsWriter.timestamp(dts >= 0 ? 3 : 2, pts));
        }
        if (dts >= 0) {
            fields.writeBytes(TsWriter.timestamp(1, dts));
        }
        var flags = pts < 0 ? 0 : dts >= 0 ? 0xC0 : 0x80;
        return mpeg2(streamId, 0x81, flags, fields.toByteArray(), payload);
    }

    /** Appends an MPEG-2 PES packet with its first two flag bytes and its optional fields given. */
    PsWriter mpeg2(int streamId, int first, int second, byte[] fields, byte[] payload) {
        var header = new byte[3 + fields.length];
        header[0] = (byte) first;
        header[1] = (byte) second;
        header[2] = (byte) fields.length;
        System.arraycopy(fields, 0, header, 3, fields.length);
        return raw(streamId, header, payload);
    }

    /** Appends a packet of a stream id with the bytes after its length field given as header and payload. */
    PsWriter raw(int streamId, byte[] header, byte[] payload) {
        var length = header.length + payload.length;
        out.writeBytes(new byte[]{0, 0, 1, (byte) streamId, (byte) (length >> 8), (byte) length});
        out.writeBytes(header);
        out.writeBytes(payload);
        return this;
    }

    /** Appends a padding packet of a total size. */
    PsWriter padding(int total) {
        var body = new byte[total - 6];
        java.util.Arrays.fill(body, (byte) 0xFF);
        return raw(0xBE, new byte[0], body);
    }

    /** Appends bytes as they are. */
    PsWriter bytes(byte[] data) {
        out.writeBytes(data);
        return this;
    }

    /** Appends a program stream map, its CRC computed over the whole map or from after its length field. */
    PsWriter map(List<MapEntry> entries, boolean crcFromLength, boolean corruptCrc) {
        var body = new ByteArrayOutputStream();
        for (var entry : entries) {
            body.write(entry.streamType());
            body.write(entry.streamId());
            if (entry.language() != null) {
                body.writeBytes(new byte[]{0, 6, 0x0A, 4});
                body.writeBytes(entry.language().getBytes(StandardCharsets.US_ASCII));
                body.write(0);
            } else {
                body.writeBytes(new byte[]{0, 0});
            }
        }
        var es = body.toByteArray();
        var map = new byte[16 + es.length];
        map[2] = 1;
        map[3] = (byte) 0xBC;
        map[4] = (byte) ((map.length - 6) >> 8);
        map[5] = (byte) (map.length - 6);
        map[6] = (byte) 0xA0;
        map[7] = (byte) 0xFF;
        map[10] = (byte) (es.length >> 8);
        map[11] = (byte) es.length;
        System.arraycopy(es, 0, map, 12, es.length);
        var from = crcFromLength ? 6 : 0;
        var crc = Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, map, from, map.length - 4 - from);
        if (corruptCrc) {
            crc ^= 1;
        }
        map[map.length - 4] = (byte) (crc >>> 24);
        map[map.length - 3] = (byte) (crc >>> 16);
        map[map.length - 2] = (byte) (crc >>> 8);
        map[map.length - 1] = (byte) crc;
        return bytes(map);
    }

    /** Appends the program end code. */
    PsWriter end() {
        return bytes(new byte[]{0, 0, 1, (byte) 0xB9});
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    /** Returns a payload with a prefix before it. */
    static byte[] prefixed(byte[] payload, int... prefix) {
        var result = new byte[prefix.length + payload.length];
        for (var index = 0; index < prefix.length; index++) {
            result[index] = (byte) prefix[index];
        }
        System.arraycopy(payload, 0, result, prefix.length, payload.length);
        return result;
    }
}
