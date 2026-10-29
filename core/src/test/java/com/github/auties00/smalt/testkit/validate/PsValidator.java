package com.github.auties00.smalt.testkit.validate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks an MPEG program stream (ISO/IEC 13818-1 clause 2.5) or MPEG-1 system stream (ISO/IEC 11172-1) against its
 * specification, independently of Smalt's demuxer: every pack header (start code, marker bits, one syntax throughout,
 * a constant {@code program_mux_rate}, non-decreasing system clock references), system headers (length, marker bits,
 * a rate bound of at least the mux rate, the {@code '11'} bits of every stream entry), the program stream map (length,
 * {@code CRC_32} over the whole map, entries within the map), and every PES packet (a stream id of a PES stream, a
 * length within the stream, the MPEG-2 or MPEG-1 header syntax, the marker bits and prefixes of the PTS and DTS, a DTS
 * only with a PTS and not after it, padding of {@code 0xFF} bytes only), and, for DVD-Video navigation packs, the
 * presentation control and data search information packets of private stream 2 with their sizes. Nothing but packs,
 * PES packets and the program end code may appear between packs, and nothing may follow the end code.
 *
 * <p>A strict validation also checks the pacing: each pack's system clock reference at least that of the previous
 * pack plus the time the previous pack takes at the mux rate, and the decoding time of each timestamped PES packet not
 * before the system clock reference of its pack.
 */
public final class PsValidator {
    /** What validation learned of a stream. */
    public record Summary(boolean mpeg1, long packs, Set<Integer> packSizes, long muxRate, Set<Integer> streamIds,
                          Map<Integer, Integer> mapTypes, int navigationPacks, boolean endCode, int systemHeaders) {
    }

    private final byte[] data;
    private final Findings findings;
    private final boolean strict;
    private final Set<Integer> packSizes = new LinkedHashSet<>();
    private final Set<Integer> streamIds = new LinkedHashSet<>();
    private final Map<Integer, Integer> mapTypes = new LinkedHashMap<>();
    private final Map<Integer, Long> lastDts = new HashMap<>();
    private Boolean mpeg1;
    private long muxRate = -1;
    private long previousScr = -1;
    private long packScr = -1;
    private int previousPackBytes;
    private long packs;
    private int navigation;
    private boolean endCode;
    private int systemHeaders;

    private PsValidator(byte[] data, Findings findings, boolean strict) {
        this.data = data;
        this.findings = findings;
        this.strict = strict;
    }

    /**
     * Validates a stream.
     *
     * @param data the stream
     * @param findings receives the problems
     * @param strict whether the pacing of the system clock references is checked too
     * @return what validation learned of the stream
     */
    public static Summary validate(byte[] data, Findings findings, boolean strict) {
        return new PsValidator(data, findings, strict).run();
    }

    private Summary run() {
        var position = 0;
        var packStart = -1;
        if (data.length < 12 || !startCode(0, 0xBA)) {
            findings.add("0", "the stream does not start with a pack header");
        }
        while (position < data.length) {
            var where = "offset " + position;
            if (endCode) {
                findings.add(where, "bytes after the program end code");
                break;
            }
            if (position + 4 > data.length || data[position] != 0 || data[position + 1] != 0
                    || data[position + 2] != 1) {
                findings.add(where, "no start code where a pack, a PES packet or the end code must start");
                break;
            }
            var code = data[position + 3] & 0xFF;
            if (code == 0xB9) {
                endCode = true;
                position += 4;
                continue;
            }
            if (code == 0xBA) {
                if (packStart >= 0) {
                    closePack(position - packStart);
                }
                packStart = position;
                var length = pack(position, where);
                if (length < 0) {
                    break;
                }
                position += length;
                continue;
            }
            if (code < 0xBB) {
                findings.add(where, "start code 0x" + Integer.toHexString(code) + " is not a system start code");
                break;
            }
            if (position + 6 > data.length) {
                findings.add(where, "truncated packet header");
                break;
            }
            var length = (data[position + 4] & 0xFF) << 8 | (data[position + 5] & 0xFF);
            var end = position + 6 + length;
            if (end > data.length) {
                findings.add(where, "packet of " + length + " bytes runs past the end of the stream");
                break;
            }
            if (packStart < 0) {
                findings.add(where, "packet before the first pack header");
            }
            switch (code) {
                case 0xBB -> systemHeader(position, end, where);
                case 0xBC -> map(position, end, where);
                case 0xBE -> {
                    for (var index = position + 6; index < end; index++) {
                        if ((data[index] & 0xFF) != 0xFF) {
                            findings.add(where, "padding byte 0x" + Integer.toHexString(data[index] & 0xFF));
                            break;
                        }
                    }
                }
                case 0xBF -> privateStream2(position, end, where);
                default -> pes(code, position, end, where);
            }
            position = end;
        }
        if (packStart >= 0) {
            closePack(position - packStart);
        }
        return new Summary(Boolean.TRUE.equals(mpeg1), packs, packSizes, muxRate, streamIds, mapTypes, navigation,
                endCode, systemHeaders);
    }

    private boolean startCode(int at, int code) {
        return at + 4 <= data.length && data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 1
                && (data[at + 3] & 0xFF) == code;
    }

    private void closePack(int bytes) {
        packSizes.add(bytes);
        previousPackBytes = bytes;
    }

    private int pack(int at, String where) {
        packs++;
        if (at + 12 > data.length) {
            findings.add(where, "truncated pack header");
            return -1;
        }
        var b4 = data[at + 4] & 0xFF;
        var isMpeg1 = (b4 & 0xC0) != 0x40;
        if (mpeg1 == null) {
            mpeg1 = isMpeg1;
        } else if (mpeg1 != isMpeg1) {
            findings.add(where, "pack header syntax changes");
        }
        long scr;
        long rate;
        int length;
        if (isMpeg1) {
            findings.require((b4 & 0xF1) == 0x21 && (data[at + 6] & 1) == 1 && (data[at + 8] & 1) == 1
                    && (data[at + 9] & 0x80) != 0 && (data[at + 11] & 1) == 1, where, "MPEG-1 pack header marker bits");
            scr = timestamp(at + 4) * 300;
            rate = ((data[at + 9] & 0x7FL) << 15 | (data[at + 10] & 0xFFL) << 7 | (data[at + 11] & 0xFFL) >> 1) * 400;
            length = 12;
        } else {
            if (at + 14 > data.length) {
                findings.add(where, "truncated pack header");
                return -1;
            }
            findings.require((b4 & 0x04) != 0 && (data[at + 6] & 0x04) != 0 && (data[at + 8] & 0x04) != 0
                    && (data[at + 9] & 0x01) != 0 && (data[at + 12] & 0x03) == 0x03, where, "pack header marker bits");
            var base = ((long) (b4 & 0x38) << 27) | ((long) (b4 & 0x03) << 28) | ((data[at + 5] & 0xFFL) << 20)
                    | ((long) (data[at + 6] & 0xF8) << 12) | ((long) (data[at + 6] & 0x03) << 13)
                    | ((data[at + 7] & 0xFFL) << 5) | ((data[at + 8] & 0xFFL) >> 3);
            var extension = (data[at + 8] & 0x03) << 7 | (data[at + 9] & 0xFF) >> 1;
            findings.require(extension < 300, where, "system_clock_reference_extension " + extension + " above 299");
            scr = base * 300 + extension;
            rate = ((data[at + 10] & 0xFFL) << 14 | (data[at + 11] & 0xFFL) << 6 | (data[at + 12] & 0xFFL) >> 2) * 400;
            length = 14 + (data[at + 13] & 0x07);
            for (var index = at + 14; index < at + length && index < data.length; index++) {
                findings.require((data[index] & 0xFF) == 0xFF, where, "pack stuffing byte is not 0xFF");
            }
        }
        findings.require(rate > 0, where, "program_mux_rate is zero");
        if (muxRate < 0) {
            muxRate = rate;
        } else {
            findings.require(rate == muxRate, where, "program_mux_rate changes from " + muxRate + " to " + rate);
        }
        if (previousScr >= 0) {
            findings.require(scr >= previousScr, where, "system clock reference goes back");
            if (strict && rate > 0) {
                var arrival = previousScr + previousPackBytes * 8L * 27_000_000L / rate;
                findings.require(scr >= arrival, where, "system clock reference " + scr + " before the previous pack"
                        + " arrives at the mux rate (" + arrival + ")");
            }
        }
        previousScr = scr;
        packScr = scr;
        return length;
    }

    private void systemHeader(int at, int end, String where) {
        systemHeaders++;
        if (end - at < 12) {
            findings.add(where, "system header shorter than its fixed fields");
            return;
        }
        findings.require((data[at + 6] & 0x80) != 0 && (data[at + 8] & 0x01) != 0 && (data[at + 10] & 0x20) != 0,
                where, "system header marker bits");
        var bound = ((data[at + 6] & 0x7FL) << 15 | (data[at + 7] & 0xFFL) << 7 | (data[at + 8] & 0xFFL) >> 1) * 400;
        if (muxRate > 0) {
            findings.require(bound >= muxRate, where, "rate_bound " + bound + " below the mux rate " + muxRate);
        }
        findings.require((end - at - 12) % 3 == 0, where, "system header stream entries are not 3 bytes each");
        for (var entry = at + 12; entry + 3 <= end; entry += 3) {
            var id = data[entry] & 0xFF;
            findings.require(id >= 0xB8, where, "system header names stream id 0x" + Integer.toHexString(id));
            findings.require((data[entry + 1] & 0xC0) == 0xC0, where, "system header entry lacks its '11' bits");
        }
    }

    private void map(int at, int end, String where) {
        if (end - at < 16) {
            findings.add(where, "program stream map shorter than its fixed fields");
            return;
        }
        var crc = new CRC32Mpeg();
        crc.update(data, at, end - at);
        findings.require(crc.value() == 0, where, "program stream map CRC_32 does not check");
        var infoLength = (data[at + 8] & 0xFF) << 8 | (data[at + 9] & 0xFF);
        var position = at + 10 + infoLength;
        if (position + 2 > end - 4) {
            findings.add(where, "program_stream_info_length runs past the map");
            return;
        }
        var mapLength = (data[position] & 0xFF) << 8 | (data[position + 1] & 0xFF);
        position += 2;
        findings.require(position + mapLength == end - 4, where, "elementary_stream_map_length does not end at the CRC");
        while (position + 4 <= end - 4) {
            var type = data[position] & 0xFF;
            var id = data[position + 1] & 0xFF;
            var length = (data[position + 2] & 0xFF) << 8 | (data[position + 3] & 0xFF);
            mapTypes.put(id, type);
            position += 4 + length;
        }
        findings.require(position == end - 4, where, "elementary stream entries overrun the map");
    }

    private void privateStream2(int at, int end, String where) {
        var length = end - at - 6;
        if (length == 980 && data[at + 6] == 0x00) {
            navigation++;
            findings.require(startCode(end, 0xBF) && end + 6 + 1018 <= data.length
                    && ((data[end + 4] & 0xFF) << 8 | (data[end + 5] & 0xFF)) == 1018 && data[end + 6] == 0x01,
                    where, "presentation control information not followed by data search information");
        }
    }

    private void pes(int code, int at, int end, String where) {
        if (code == 0xBD || code >= 0xC0 && code <= 0xEF || code == 0xFD) {
            streamIds.add(code);
        } else if (code < 0xF0) {
            findings.add(where, "stream id 0x" + Integer.toHexString(code) + " is not a PES stream");
            return;
        }
        if (end - at < 7) {
            findings.add(where, "PES packet without a header");
            return;
        }
        long pts = -1;
        long dts = -1;
        if ((data[at + 6] & 0xC0) == 0x80) {
            findings.require(!Boolean.TRUE.equals(mpeg1), where, "MPEG-2 PES header in an MPEG-1 system stream");
            if (end - at < 9) {
                findings.add(where, "truncated PES header");
                return;
            }
            var flags = (data[at + 7] & 0xFF) >> 6;
            var headerLength = data[at + 8] & 0xFF;
            findings.require(at + 9 + headerLength <= end, where, "PES_header_data_length runs past the packet");
            findings.require(flags != 1, where, "PTS_DTS_flags is the forbidden value 01");
            if (flags >= 2) {
                pts = checkedTimestamp(at + 9, flags == 3 ? 0x30 : 0x20, where, "PTS");
            }
            if (flags == 3) {
                dts = checkedTimestamp(at + 14, 0x10, where, "DTS");
            }
        } else {
            var position = at + 6;
            var stuffing = 0;
            while (position < end && (data[position] & 0xFF) == 0xFF) {
                position++;
                stuffing++;
            }
            findings.require(stuffing <= 16, where, stuffing + " MPEG-1 stuffing bytes, more than 16");
            if (position + 2 <= end && (data[position] & 0xC0) == 0x40) {
                position += 2;
            }
            if (position >= end) {
                findings.add(where, "truncated MPEG-1 packet header");
                return;
            }
            var marker = data[position] & 0xF0;
            if (marker == 0x20) {
                pts = checkedTimestamp(position, 0x20, where, "PTS");
            } else if (marker == 0x30) {
                pts = checkedTimestamp(position, 0x30, where, "PTS");
                dts = checkedTimestamp(position + 5, 0x10, where, "DTS");
            } else {
                findings.require((data[position] & 0xFF) == 0x0F, where, "MPEG-1 packet header without its 0x0F byte");
            }
        }
        if (pts < 0) {
            return;
        }
        var decode = dts >= 0 ? dts : pts;
        if (dts >= 0) {
            var ahead = Math.floorMod(pts - dts, 1L << 33);
            findings.require(ahead < 1L << 32, where, "DTS after PTS");
        }
        var payload = (data[at + 6] & 0xC0) == 0x80 ? at + 9 + (data[at + 8] & 0xFF) : -1;
        var key = code == 0xBD && payload >= 0 && payload < end ? 0x100 | (data[payload] & 0xFF) : code;
        var last = lastDts.get(key);
        if (last != null) {
            findings.require(Math.floorMod(decode - last, 1L << 33) < 1L << 32, where,
                    "decoding time goes back on stream 0x" + Integer.toHexString(key));
        }
        lastDts.put(key, decode);
        if (strict && packScr >= 0) {
            findings.require(Math.floorMod(decode * 300 - packScr, 300L << 33) < 300L << 32, where,
                    "decoding time " + decode + " before the system clock reference " + packScr / 300 + " of its pack");
        }
    }

    private long checkedTimestamp(int at, int prefix, String where, String name) {
        if (at + 5 > data.length) {
            findings.add(where, "truncated " + name);
            return -1;
        }
        findings.require((data[at] & 0xF0) == prefix, where, name + " prefix");
        findings.require((data[at] & 1) == 1 && (data[at + 2] & 1) == 1 && (data[at + 4] & 1) == 1, where,
                name + " marker bits");
        return timestamp(at);
    }

    private long timestamp(int at) {
        return ((long) (data[at] >> 1) & 0x07) << 30 | (data[at + 1] & 0xFFL) << 22 | ((data[at + 2] & 0xFFL) >> 1) << 15
                | (data[at + 3] & 0xFFL) << 7 | (data[at + 4] & 0xFFL) >> 1;
    }

    /** The CRC-32 of ISO/IEC 13818-1 Annex A: polynomial 0x04C11DB7, initial value all ones, no reflection. */
    private static final class CRC32Mpeg {
        private int value = 0xFFFFFFFF;

        void update(byte[] bytes, int offset, int length) {
            for (var index = offset; index < offset + length; index++) {
                value ^= (bytes[index] & 0xFF) << 24;
                for (var bit = 0; bit < 8; bit++) {
                    value = value < 0 ? value << 1 ^ 0x04C11DB7 : value << 1;
                }
            }
        }

        int value() {
            return value;
        }
    }
}
