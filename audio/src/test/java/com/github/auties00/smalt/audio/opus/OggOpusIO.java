package com.github.auties00.smalt.audio.opus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Ogg Opus reader and writer (RFC 3533, RFC 7845) for self-contained tests: the real
 * demuxer and muxer live in another workstream.
 */
final class OggOpusIO {
    private static final int[] CRC_TABLE = new int[256];

    static {
        for (var i = 0; i < 256; i++) {
            var r = i << 24;
            for (var j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            CRC_TABLE[i] = r;
        }
    }

    /** The content of an Ogg Opus stream. */
    record Stream(byte[] head, byte[] tags, List<byte[]> packets, long finalGranule) {
    }

    private OggOpusIO() {
    }

    /** Reads the first logical stream of an Ogg file. */
    static Stream read(byte[] file) {
        var packets = new ArrayList<byte[]>();
        var current = new ByteArrayOutputStream();
        var offset = 0;
        var serial = -1;
        long granule = -1;
        while (offset + 27 <= file.length) {
            if (file[offset] != 'O' || file[offset + 1] != 'g' || file[offset + 2] != 'g' || file[offset + 3] != 'S') {
                throw new IllegalStateException("lost Ogg sync at " + offset);
            }
            var pageSerial = readLe32(file, offset + 14);
            var segments = file[offset + 26] & 0xFF;
            var body = offset + 27 + segments;
            if (serial == -1) {
                serial = pageSerial;
            }
            var pageGranule = readLe64(file, offset + 6);
            var dataOffset = body;
            for (var s = 0; s < segments; s++) {
                var lace = file[offset + 27 + s] & 0xFF;
                if (pageSerial == serial) {
                    current.write(file, dataOffset, lace);
                    if (lace < 255) {
                        packets.add(current.toByteArray());
                        current.reset();
                    }
                }
                dataOffset += lace;
            }
            if (pageSerial == serial && pageGranule != -1) {
                granule = pageGranule;
            }
            offset = dataOffset;
        }
        if (packets.size() < 2) {
            throw new IllegalStateException("missing Opus headers");
        }
        return new Stream(packets.get(0), packets.get(1), List.copyOf(packets.subList(2, packets.size())), granule);
    }

    /**
     * Writes an Ogg Opus stream.
     *
     * @param head           the OpusHead packet
     * @param packets        the audio packets
     * @param samples48k     the duration of each packet at 48 kHz
     * @param preSkip        the pre-skip at 48 kHz
     * @param totalSamples48 the number of real input samples at 48 kHz, for end trimming
     */
    static byte[] write(byte[] head, List<byte[]> packets, int[] samples48k, int preSkip, long totalSamples48) {
        var out = new ByteArrayOutputStream();
        var serial = 0x0C0BA17;
        var sequence = new int[]{0};
        writePage(out, serial, sequence, 0x02, 0, List.of(head));
        writePage(out, serial, sequence, 0, 0, List.of(tags()));
        long granule = 0;
        var pagePackets = new ArrayList<byte[]>();
        var pageBytes = 0;
        for (var i = 0; i < packets.size(); i++) {
            pagePackets.add(packets.get(i));
            pageBytes += packets.get(i).length;
            granule += samples48k[i];
            var last = i == packets.size() - 1;
            if (last || pagePackets.size() >= 40 || pageBytes > 4000) {
                var pageGranule = last ? Math.min(granule, preSkip + totalSamples48) : granule;
                writePage(out, serial, sequence, last ? 0x04 : 0, pageGranule, pagePackets);
                pagePackets.clear();
                pageBytes = 0;
            }
        }
        return out.toByteArray();
    }

    private static byte[] tags() {
        var vendor = "Smalt".getBytes(StandardCharsets.US_ASCII);
        var tags = new byte[8 + 4 + vendor.length + 4];
        System.arraycopy("OpusTags".getBytes(StandardCharsets.US_ASCII), 0, tags, 0, 8);
        writeLe32(tags, 8, vendor.length);
        System.arraycopy(vendor, 0, tags, 12, vendor.length);
        writeLe32(tags, 12 + vendor.length, 0);
        return tags;
    }

    private static void writePage(ByteArrayOutputStream out, int serial, int[] sequence, int flags, long granule,
                                  List<byte[]> packets) {
        var lacing = new ByteArrayOutputStream();
        var body = new ByteArrayOutputStream();
        for (var packet : packets) {
            var remaining = packet.length;
            while (remaining >= 255) {
                lacing.write(255);
                remaining -= 255;
            }
            lacing.write(remaining);
            body.writeBytes(packet);
        }
        var laces = lacing.toByteArray();
        if (laces.length > 255) {
            throw new IllegalStateException("page too large");
        }
        var page = new byte[27 + laces.length + body.size()];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[5] = (byte) flags;
        for (var i = 0; i < 8; i++) {
            page[6 + i] = (byte) (granule >>> (8 * i));
        }
        writeLe32(page, 14, serial);
        writeLe32(page, 18, sequence[0]++);
        page[26] = (byte) laces.length;
        System.arraycopy(laces, 0, page, 27, laces.length);
        System.arraycopy(body.toByteArray(), 0, page, 27 + laces.length, body.size());
        var crc = 0;
        for (var b : page) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
        }
        writeLe32(page, 22, crc);
        out.writeBytes(page);
    }

    private static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
    }

    private static long readLe64(byte[] data, int offset) {
        return (readLe32(data, offset) & 0xFFFFFFFFL) | (long) readLe32(data, offset + 4) << 32;
    }

    private static void writeLe32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }
}
