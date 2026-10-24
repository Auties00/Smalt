package com.github.auties00.smalt.testkit.validate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks an MPEG-2 transport stream against ISO/IEC 13818-1, independently of Smalt's demuxer: the 188-byte packet
 * grid and its sync bytes, adaptation fields, continuity counters, the PAT and PMT sections (syntax and CRC), that
 * every PID carrying data is declared, PES packet headers (start code, stream id, length, PTS and DTS marker bits and
 * order), PCR presence on the PCR PID with intervals of at most 100 ms, and, for an HLS segment, that the PAT and the
 * PMT precede the first PES packet. The SCTE 35 streams of a program ({@code stream_type 0x86} outside Blu-ray
 * programs) carry {@code splice_info_section}s (table {@code 0xFC}, CRC checked) instead of PES packets, and the PES
 * packets of a stream registered as AV1 ({@code "AV01"}) are video, which may leave their length unbounded.
 */
public final class TsValidator {
    /** The packet size. */
    private static final int PACKET = 188;

    /** The longest PCR interval ISO/IEC 13818-1 section 2.7.2 allows, in 27 MHz ticks. */
    private static final long PCR_INTERVAL = 27_000_000L / 10;

    /** What validation learned of a stream. */
    public record Summary(int programs, Map<Integer, Integer> streamTypes, long packets, boolean tablesFirst) {
    }

    private final byte[] data;
    private final Findings findings;
    private final Map<Integer, Integer> continuity = new HashMap<>();
    private final Map<Integer, Boolean> duplicated = new HashMap<>();
    private final Set<Integer> pmtPids = new HashSet<>();
    private final Map<Integer, Integer> streams = new HashMap<>();
    private final Set<Integer> pcrPids = new HashSet<>();
    private final Map<Integer, Long> lastPcr = new HashMap<>();
    private final Map<Integer, Long> pesRemaining = new HashMap<>();
    private final Map<Integer, Integer> sectionLength = new HashMap<>();
    private final Map<Integer, java.io.ByteArrayOutputStream> sections = new HashMap<>();
    private final Set<Integer> splicePids = new HashSet<>();
    private final Set<Integer> privateVideoPids = new HashSet<>();
    private boolean patSeen;
    private boolean pmtSeen;
    private boolean pesBeforeTables;
    private int programs;

    private TsValidator(byte[] data, Findings findings) {
        this.data = data;
        this.findings = findings;
    }

    /**
     * Validates a stream.
     *
     * @param data the stream
     * @param findings receives the problems
     * @return what validation learned of the stream
     */
    public static Summary validate(byte[] data, Findings findings) {
        return new TsValidator(data, findings).run();
    }

    private Summary run() {
        if (data.length == 0 || data.length % PACKET != 0) {
            findings.add("/", data.length + " bytes are not a whole number of 188-byte packets");
        }
        var count = data.length / PACKET;
        var undeclared = new HashSet<Integer>();
        for (var index = 0; index < count; index++) {
            var at = index * PACKET;
            var where = "packet " + index;
            if ((data[at] & 0xFF) != 0x47) {
                findings.add(where, "sync byte 0x" + Integer.toHexString(data[at] & 0xFF));
                continue;
            }
            var error = (data[at + 1] & 0x80) != 0;
            var start = (data[at + 1] & 0x40) != 0;
            var pid = (data[at + 1] & 0x1F) << 8 | data[at + 2] & 0xFF;
            var scrambling = (data[at + 3] >> 6) & 3;
            var control = (data[at + 3] >> 4) & 3;
            var counter = data[at + 3] & 0xF;
            findings.require(!error, where, "the transport error indicator is set");
            findings.require(scrambling == 0, where, "scrambled");
            if (control == 0) {
                findings.add(where, "adaptation_field_control 0 is reserved");
                continue;
            }
            if (pid == 0x1FFF) {
                continue;
            }
            var payload = at + 4;
            var discontinuity = false;
            if ((control & 2) != 0) {
                var length = data[at + 4] & 0xFF;
                var limit = control == 2 ? 183 : 182;
                if (length > limit) {
                    findings.add(where, "adaptation field length " + length + " above " + limit);
                    continue;
                }
                if (control == 2 && length != 183) {
                    findings.add(where, "an adaptation-only packet with an adaptation field of " + length + " bytes");
                }
                if (length > 0) {
                    var flags = data[at + 5] & 0xFF;
                    discontinuity = (flags & 0x80) != 0;
                    if ((flags & 0x10) != 0) {
                        if (length < 7) {
                            findings.add(where, "a PCR flag in an adaptation field of " + length + " bytes");
                        } else {
                            pcr(pid, at + 6, where);
                        }
                    }
                }
                payload = at + 5 + length;
            }
            var hasPayload = (control & 1) != 0;
            if (hasPayload) {
                var previous = continuity.get(pid);
                if (previous != null && !discontinuity) {
                    if (counter == previous) {
                        findings.require(!duplicated.getOrDefault(pid, false), where, "PID " + pid
                                + " repeats continuity counter " + counter + " more than once");
                        duplicated.put(pid, true);
                    } else {
                        findings.require(counter == ((previous + 1) & 0xF), where, "PID " + pid
                                + " continuity counter " + counter + " after " + previous);
                        duplicated.put(pid, false);
                    }
                }
                continuity.put(pid, counter);
            } else if (continuity.containsKey(pid)) {
                findings.require(counter == continuity.get(pid), where, "PID " + pid
                        + " increments its continuity counter in a packet without payload");
            }
            var end = at + PACKET;
            if (!hasPayload || payload >= end) {
                continue;
            }
            if (pid == 0 || pmtPids.contains(pid) || splicePids.contains(pid)) {
                section(pid, start, payload, end, where);
            } else if (streams.containsKey(pid)) {
                pes(pid, start, payload, end, where);
            } else if (undeclared.add(pid)) {
                findings.add(where, "PID " + pid + " carries data but no PAT or PMT declares it");
            }
        }
        for (var entry : pesRemaining.entrySet()) {
            findings.require(entry.getValue() <= 0, "end", "the last PES packet of PID " + entry.getKey() + " misses "
                    + entry.getValue() + " bytes");
        }
        findings.require(patSeen, "/", "no PAT");
        findings.require(pmtSeen, "/", "no PMT");
        for (var pid : pcrPids) {
            findings.require(lastPcr.containsKey(pid), "/", "PCR PID " + pid + " carries no PCR");
        }
        return new Summary(programs, Map.copyOf(streams), count, !pesBeforeTables);
    }

    private void pcr(int pid, int at, String where) {
        var base = ((long) (data[at] & 0xFF) << 25) | (data[at + 1] & 0xFF) << 17 | (data[at + 2] & 0xFF) << 9
                | (data[at + 3] & 0xFF) << 1 | (data[at + 4] & 0xFF) >> 7;
        var extension = (data[at + 4] & 1) << 8 | data[at + 5] & 0xFF;
        findings.require(extension < 300, where, "PCR extension " + extension);
        var value = base * 300 + extension;
        var previous = lastPcr.get(pid);
        if (previous != null) {
            findings.require(value > previous, where, "PCR of PID " + pid + " does not increase");
            findings.require(value - previous <= PCR_INTERVAL, where, "PCRs of PID " + pid + " "
                    + (value - previous) / 27_000 + " ms apart, at most 100 ms allowed");
        }
        lastPcr.put(pid, value);
    }

    private void section(int pid, boolean start, int from, int end, String where) {
        var buffer = sections.get(pid);
        var position = from;
        if (start) {
            var pointer = data[position++] & 0xFF;
            if (buffer != null && buffer.size() > 0) {
                buffer.write(data, position, Math.min(pointer, end - position));
                finish(pid, buffer, where);
            }
            position += pointer;
            buffer = new java.io.ByteArrayOutputStream();
            sections.put(pid, buffer);
        }
        if (buffer == null || position >= end) {
            return;
        }
        while (position < end) {
            if (buffer.size() == 0 && (data[position] & 0xFF) == 0xFF) {
                return;
            }
            buffer.write(data[position++]);
            var bytes = buffer.size();
            if (bytes >= 3) {
                var raw = buffer.toByteArray();
                var length = ((raw[1] & 0x0F) << 8 | raw[2] & 0xFF) + 3;
                if (bytes == length) {
                    finish(pid, buffer, where);
                    buffer = new java.io.ByteArrayOutputStream();
                    sections.put(pid, buffer);
                }
            }
        }
    }

    private void finish(int pid, java.io.ByteArrayOutputStream buffer, String where) {
        var raw = buffer.toByteArray();
        buffer.reset();
        if (raw.length < 3) {
            return;
        }
        var length = ((raw[1] & 0x0F) << 8 | raw[2] & 0xFF) + 3;
        if (raw.length < length) {
            findings.add(where, "a section of PID " + pid + " ends after " + raw.length + " of " + length + " bytes");
            return;
        }
        if (length < 12) {
            findings.add(where, "a section of " + length + " bytes on PID " + pid);
            return;
        }
        var table = raw[0] & 0xFF;
        if (splicePids.contains(pid)) {
            findings.require(table == 0xFC, where, "table id " + table + " on the SCTE 35 PID " + pid);
            findings.require(length <= 4096, where, "a splice_info_section of " + length + " bytes");
            findings.require(crc32(raw, length) == 0, where, "CRC mismatch in a splice_info_section on PID " + pid);
            return;
        }
        findings.require((raw[1] & 0x80) != 0, where, "section_syntax_indicator 0 on PID " + pid);
        findings.require(length <= 1024, where, "a section of " + length + " bytes, at most 1024 allowed");
        if (crc32(raw, length) != 0) {
            findings.add(where, "CRC mismatch in table " + table + " on PID " + pid);
            return;
        }
        if (pid == 0) {
            findings.require(table == 0, where, "table id " + table + " on the PAT PID");
            patSeen = true;
            programs = 0;
            for (var offset = 8; offset + 4 <= length - 4; offset += 4) {
                var number = (raw[offset] & 0xFF) << 8 | raw[offset + 1] & 0xFF;
                var mapPid = (raw[offset + 2] & 0x1F) << 8 | raw[offset + 3] & 0xFF;
                if (number != 0) {
                    pmtPids.add(mapPid);
                    programs++;
                }
            }
            findings.require(programs > 0, where, "a PAT without programs");
        } else if (table == 2) {
            pmtSeen = true;
            var pcrPid = (raw[8] & 0x1F) << 8 | raw[9] & 0xFF;
            if (pcrPid != 0x1FFF) {
                pcrPids.add(pcrPid);
            }
            var infoLength = (raw[10] & 0x0F) << 8 | raw[11] & 0xFF;
            var hdmv = registered(raw, 12, Math.min(12 + infoLength, length - 4), "HDMV");
            var offset = 12 + infoLength;
            while (offset + 5 <= length - 4) {
                var type = raw[offset] & 0xFF;
                var elementary = (raw[offset + 1] & 0x1F) << 8 | raw[offset + 2] & 0xFF;
                var esInfo = (raw[offset + 3] & 0x0F) << 8 | raw[offset + 4] & 0xFF;
                if (type == 0x86 && !hdmv) {
                    splicePids.add(elementary);
                }
                if (type == 0x06 && registered(raw, offset + 5, Math.min(offset + 5 + esInfo, length - 4), "AV01")) {
                    privateVideoPids.add(elementary);
                }
                var previous = streams.put(elementary, type);
                findings.require(previous == null || previous == type, where, "PID " + elementary
                        + " changes its stream type");
                offset += 5 + esInfo;
            }
            findings.require(offset == length - 4, where, "PMT stream loop does not end at the CRC");
        }
    }

    /** {@return whether a descriptor loop holds a registration descriptor with a format identifier} */
    private static boolean registered(byte[] raw, int from, int to, String identifier) {
        var position = from;
        while (position + 2 <= to) {
            var tag = raw[position] & 0xFF;
            var size = raw[position + 1] & 0xFF;
            if (tag == 0x05 && size >= 4 && position + 6 <= to && new String(raw, position + 2, 4,
                    java.nio.charset.StandardCharsets.ISO_8859_1).equals(identifier)) {
                return true;
            }
            position += 2 + size;
        }
        return false;
    }

    private void pes(int pid, boolean start, int from, int end, String where) {
        if (!patSeen || !pmtSeen) {
            pesBeforeTables = true;
        }
        var remaining = pesRemaining.get(pid);
        if (start) {
            if (remaining != null && remaining > 0) {
                findings.add(where, "PID " + pid + " starts a PES packet " + remaining + " bytes before the previous"
                        + " one ends");
            }
            if (end - from < 9 || data[from] != 0 || data[from + 1] != 0 || data[from + 2] != 1) {
                findings.add(where, "PID " + pid + " starts a PES packet without the 00 00 01 prefix");
                pesRemaining.remove(pid);
                return;
            }
            var streamId = data[from + 3] & 0xFF;
            var length = (data[from + 4] & 0xFF) << 8 | data[from + 5] & 0xFF;
            var video = streamId >= 0xE0 && streamId <= 0xEF || privateVideoPids.contains(pid);
            findings.require(length != 0 || video, where, "an unbounded PES packet on non-video stream 0x"
                    + Integer.toHexString(streamId));
            if (streamId != 0xBC && streamId != 0xBE && streamId != 0xBF && streamId != 0xF0 && streamId != 0xF1
                    && streamId != 0xFF && streamId != 0xF2 && streamId != 0xF8) {
                findings.require((data[from + 6] & 0xC0) == 0x80, where, "PES header marker bits");
                var flags = (data[from + 7] & 0xFF) >> 6;
                var headerLength = data[from + 8] & 0xFF;
                findings.require(flags != 1, where, "PTS_DTS_flags 01 is forbidden");
                if (flags >= 2 && end - from >= 14) {
                    var pts = timestamp(from + 9, flags == 3 ? 3 : 2, where + " PTS");
                    if (flags == 3 && end - from >= 19) {
                        var dts = timestamp(from + 14, 1, where + " DTS");
                        findings.require(dts <= pts, where, "DTS after PTS");
                    }
                }
                findings.require(9 + headerLength <= end - from || length == 0, where, "a PES header longer than its"
                        + " first packet");
            }
            pesRemaining.put(pid, length == 0 ? Long.MIN_VALUE : (long) length + 6 - (end - from));
        } else if (remaining == null) {
            findings.add(where, "PID " + pid + " continues a PES packet that never started");
        } else if (remaining != Long.MIN_VALUE) {
            remaining -= end - from;
            pesRemaining.put(pid, remaining);
            findings.require(remaining >= 0, where, "PID " + pid + " overruns its PES packet length by " + -remaining
                    + " bytes");
        }
    }

    private long timestamp(int at, int prefix, String where) {
        var b0 = data[at] & 0xFF;
        findings.require((b0 >> 4) == prefix, where, "prefix " + (b0 >> 4) + ", expected " + prefix);
        findings.require((b0 & 1) == 1 && (data[at + 2] & 1) == 1 && (data[at + 4] & 1) == 1, where,
                "marker bits");
        return ((long) (b0 >> 1) & 7) << 30 | ((data[at + 1] & 0xFF) << 7 | (data[at + 2] & 0xFF) >> 1) << 15
                | (data[at + 3] & 0xFF) << 7 | (data[at + 4] & 0xFF) >> 1;
    }

    /** {@return the MPEG-2 CRC-32 over a section, 0 when its stored CRC matches} */
    private static int crc32(byte[] data, int length) {
        var crc = 0xFFFFFFFF;
        for (var i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF) << 24;
            for (var bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? crc << 1 ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }
}
