package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.header.Crc32Mpeg;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes small transport streams packet by packet, independently of {@link TsMuxer}, so that the demuxer is
 * tested against streams laid out exactly as a test needs them: program tables with any descriptors and
 * version, PES packets with any timestamps and adaptation fields, and sections on any PID.
 */
final class TsWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<Integer, Integer> counters = new HashMap<>();

    /** Describes one elementary stream of a program map table. */
    record Stream(int type, int pid, byte[] descriptors) {
        Stream(int type, int pid) {
            this(type, pid, new byte[0]);
        }
    }

    /** Writes a program association table naming one program. */
    TsWriter pat(int program, int pmtPid, int version) {
        var body = new byte[]{(byte) (program >> 8), (byte) program, (byte) (0xE0 | pmtPid >> 8), (byte) pmtPid};
        return section(0, longSection(0x00, 1, version, body));
    }

    /** Writes a program map table. */
    TsWriter pmt(int pmtPid, int program, int version, int pcrPid, byte[] programInfo, List<Stream> streams) {
        var body = new ByteArrayOutputStream();
        body.write(0xE0 | pcrPid >> 8);
        body.write(pcrPid);
        body.write(0xF0 | programInfo.length >> 8);
        body.write(programInfo.length);
        body.writeBytes(programInfo);
        for (var stream : streams) {
            body.write(stream.type());
            body.write(0xE0 | stream.pid() >> 8);
            body.write(stream.pid());
            body.write(0xF0 | stream.descriptors().length >> 8);
            body.write(stream.descriptors().length);
            body.writeBytes(stream.descriptors());
        }
        return section(pmtPid, longSection(0x02, program, version, body.toByteArray()));
    }

    /** Returns a long-form section with its CRC. */
    static byte[] longSection(int table, int extension, int version, byte[] body) {
        var section = new byte[8 + body.length + 4];
        var length = section.length - 3;
        section[0] = (byte) table;
        section[1] = (byte) (0xB0 | length >> 8);
        section[2] = (byte) length;
        section[3] = (byte) (extension >> 8);
        section[4] = (byte) extension;
        section[5] = (byte) (0xC1 | (version & 0x1F) << 1);
        System.arraycopy(body, 0, section, 8, body.length);
        return withCrc(section);
    }

    /** Fills the last four bytes of a section with its CRC_32. */
    static byte[] withCrc(byte[] section) {
        var crc = Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, section, 0, section.length - 4);
        section[section.length - 4] = (byte) (crc >>> 24);
        section[section.length - 3] = (byte) (crc >>> 16);
        section[section.length - 2] = (byte) (crc >>> 8);
        section[section.length - 1] = (byte) crc;
        return section;
    }

    /** Writes a section, starting at a packet with a zero pointer field. */
    TsWriter section(int pid, byte[] section) {
        var position = 0;
        var first = true;
        while (position < section.length) {
            var packet = header(pid, first, false);
            var cursor = 4;
            if (first) {
                packet[cursor++] = 0;
            }
            var take = Math.min(188 - cursor, section.length - position);
            System.arraycopy(section, position, packet, cursor, take);
            position += take;
            first = false;
            out.writeBytes(packet);
        }
        return this;
    }

    /**
     * Writes a PES packet with a presentation time and, unless negative, a decoding time; a negative presentation
     * time writes neither.
     */
    TsWriter pes(int pid, int streamId, long pts, long dts, byte[] payload, boolean randomAccess) {
        var withPts = pts >= 0;
        var withDts = withPts && dts >= 0;
        var header = new ByteArrayOutputStream();
        header.writeBytes(new byte[]{0, 0, 1, (byte) streamId});
        var headerLength = withDts ? 10 : withPts ? 5 : 0;
        var length = 3 + headerLength + payload.length;
        if (length > 0xFFFF || (streamId & 0xF0) == 0xE0) {
            length = 0;
        }
        header.write(length >> 8);
        header.write(length);
        header.write(0x84);
        header.write(withDts ? 0xC0 : withPts ? 0x80 : 0x00);
        header.write(headerLength);
        if (withPts) {
            header.writeBytes(timestamp(withDts ? 3 : 2, pts));
        }
        if (withDts) {
            header.writeBytes(timestamp(1, dts));
        }
        header.writeBytes(payload);
        var bytes = header.toByteArray();
        var position = 0;
        var first = true;
        while (position < bytes.length) {
            var remaining = bytes.length - position;
            var flags = first && randomAccess;
            var adaptation = flags ? 2 : 0;
            if (remaining < 184 - adaptation) {
                adaptation = 184 - remaining;
            }
            var packet = header(pid, first, adaptation > 0);
            var cursor = 4;
            if (adaptation > 0) {
                packet[4] = (byte) (adaptation - 1);
                if (adaptation > 1) {
                    packet[5] = (byte) (flags ? 0x40 : 0);
                    Arrays.fill(packet, 6, 4 + adaptation, (byte) 0xFF);
                }
                cursor += adaptation;
            }
            var take = 188 - cursor;
            System.arraycopy(bytes, position, packet, cursor, take);
            position += take;
            first = false;
            out.writeBytes(packet);
        }
        return this;
    }

    /** Returns the five bytes of a PES timestamp. */
    static byte[] timestamp(int prefix, long value) {
        var time = value & ((1L << 33) - 1);
        return new byte[]{
                (byte) ((prefix << 4) | (int) ((time >>> 29) & 0x0E) | 1),
                (byte) (time >>> 22),
                (byte) (((time >>> 14) & 0xFE) | 1),
                (byte) (time >>> 7),
                (byte) (((time << 1) & 0xFE) | 1)};
    }

    /** Returns a transport packet header with the PID's next continuity counter. */
    private byte[] header(int pid, boolean unitStart, boolean adaptation) {
        var packet = new byte[188];
        Arrays.fill(packet, (byte) 0xFF);
        var counter = counters.merge(pid, 1, Integer::sum) - 1;
        packet[0] = 0x47;
        packet[1] = (byte) ((unitStart ? 0x40 : 0) | pid >> 8);
        packet[2] = (byte) pid;
        packet[3] = (byte) ((adaptation ? 0x30 : 0x10) | (counter & 0x0F));
        return packet;
    }

    /** Returns the stream written so far. */
    byte[] toByteArray() {
        return out.toByteArray();
    }

    /** Returns a registration descriptor. */
    static byte[] registration(String identifier) {
        var bytes = identifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return new byte[]{0x05, 4, bytes[0], bytes[1], bytes[2], bytes[3]};
    }

    /** Concatenates byte arrays. */
    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Splits a stream of 188-byte packets into the PES payloads of a PID, in order. */
    static List<byte[]> pesPayloads(byte[] stream, int pid) {
        var result = new ArrayList<byte[]>();
        ByteArrayOutputStream current = null;
        for (var offset = 0; offset + 188 <= stream.length; offset += 188) {
            if (((stream[offset + 1] & 0x1F) << 8 | (stream[offset + 2] & 0xFF)) != pid) {
                continue;
            }
            var control = (stream[offset + 3] >> 4) & 3;
            var payload = offset + 4;
            if ((control & 2) != 0) {
                payload += 1 + (stream[offset + 4] & 0xFF);
            }
            if ((control & 1) == 0) {
                continue;
            }
            if ((stream[offset + 1] & 0x40) != 0) {
                if (current != null) {
                    result.add(stripHeader(current.toByteArray()));
                }
                current = new ByteArrayOutputStream();
            }
            if (current != null) {
                current.write(stream, payload, offset + 188 - payload);
            }
        }
        if (current != null) {
            result.add(stripHeader(current.toByteArray()));
        }
        return result;
    }

    private static byte[] stripHeader(byte[] pes) {
        var start = 9 + (pes[8] & 0xFF);
        var length = (pes[4] & 0xFF) << 8 | (pes[5] & 0xFF);
        var end = length == 0 ? pes.length : 6 + length;
        return Arrays.copyOfRange(pes, start, end);
    }
}
