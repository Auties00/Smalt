package com.github.auties00.smalt.testkit.validate;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks an MXF file (SMPTE ST 377-1) against its specification, independently of Smalt's demuxer: the KLV coding of
 * every packet from the header partition pack to the end of the file (keys of the SMPTE registry prefix, BER lengths
 * within the file), every partition pack (version, the offsets of itself, of the previous partition and of the footer,
 * header and index byte counts that end where the header metadata and the index table segments end, body stream ids
 * only for partitions that hold essence, and its position on the KLV alignment grid), the primer pack (an entry for
 * every local tag a set uses, none twice), the header metadata of each partition (unique instance UIDs, every strong
 * reference of the structural metadata resolving to a set of the right kind, one preface, source clips of the
 * material package naming a track of a package of the file, an essence element of the file for every track number of
 * a file package track), the index table segments (an entry for every edit unit of the essence, stream offsets that
 * land on the key of an essence element or, for clip wrapping, within the clip value, temporal offsets that permute
 * the edit units, the random access flag on the first entry) and the random index pack (one entry per partition,
 * with its offset and body stream id, and the pack's length in its last four bytes). OP-Atom files must have a
 * closed and complete header partition and header metadata in it only.
 */
public final class MxfValidator {
    /** What validation learned of a file. */
    public record Summary(int partitions, int sets, boolean closedHeader, boolean randomIndex, long editUnits,
                          boolean clipWrapped, Map<Integer, Integer> elements, String pattern) {
    }

    private static final byte[] PARTITION = hex("060e2b34020501010d010201");
    private static final byte[] PRIMER = hex("060e2b34020501010d01020101050100");
    private static final byte[] RIP = hex("060e2b34020501010d01020101110100");
    private static final byte[] INDEX = hex("060e2b34025301010d01020101100100");
    private static final byte[] FILL = hex("060e2b34010101010301021001000000");
    private static final byte[] ELEMENT = hex("060e2b34010201010d010301");

    /** The local tags of strong references, with the set bytes the targets may have (empty for any). */
    private static final Map<Integer, Set<Integer>> STRONG = Map.ofEntries(
            Map.entry(0x3B03, Set.of(0x18)), Map.entry(0x3B06, Set.of(0x30)), Map.entry(0x1901, Set.of(0x36, 0x37)),
            Map.entry(0x1902, Set.of(0x23)), Map.entry(0x4403, Set.of(0x3A, 0x3B, 0x39)),
            Map.entry(0x4803, Set.of()), Map.entry(0x1001, Set.of()), Map.entry(0x4701, Set.of()),
            Map.entry(0x3F01, Set.of()), Map.entry(0x4406, Set.of(0x3F)));

    private final byte[] data;
    private final Findings findings;
    private final List<long[]> partitions = new ArrayList<>();
    private final Map<Integer, Integer> elements = new LinkedHashMap<>();
    private final List<Long> elementOffsets = new ArrayList<>();
    private final List<long[]> clips = new ArrayList<>();
    private final Map<Long, List<Long>> essenceStarts = new HashMap<>();
    private int sets;
    private boolean closedHeader;
    private boolean randomIndex;
    private long editUnits = -1;
    private String pattern = "unknown";
    private int kag = 1;

    private MxfValidator(byte[] data, Findings findings) {
        this.data = data;
        this.findings = findings;
    }

    /**
     * Validates a file.
     *
     * @param data the file
     * @param findings receives the problems
     * @return what validation learned of the file
     */
    public static Summary validate(byte[] data, Findings findings) {
        var validator = new MxfValidator(data, findings);
        validator.run();
        return new Summary(validator.partitions.size(), validator.sets, validator.closedHeader, validator.randomIndex,
                validator.editUnits, !validator.clips.isEmpty(), validator.elements, validator.pattern);
    }

    private static byte[] hex(String text) {
        var out = new byte[text.length() / 2];
        for (var index = 0; index < out.length; index++) {
            out[index] = (byte) Integer.parseInt(text, 2 * index, 2 * index + 2, 16);
        }
        return out;
    }

    private boolean starts(long at, byte[] prefix) {
        if (at + prefix.length > data.length) {
            return false;
        }
        for (var index = 0; index < prefix.length; index++) {
            if (index != 7 && data[(int) at + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private long u32(long at) {
        return ByteBuffer.wrap(data, (int) at, 4).getInt() & 0xFFFFFFFFL;
    }

    private long u64(long at) {
        return ByteBuffer.wrap(data, (int) at, 8).getLong();
    }

    private int u16(long at) {
        return (data[(int) at] & 0xFF) << 8 | data[(int) at + 1] & 0xFF;
    }

    /** One KLV packet: key offset, value offset and value length. */
    private record Klv(long at, long value, long length) {
        long end() {
            return value + length;
        }
    }

    private Klv klv(long at) {
        if (at + 17 > data.length) {
            return null;
        }
        var first = data[(int) at + 16] & 0xFF;
        long length;
        long value;
        if (first < 0x80) {
            length = first;
            value = at + 17;
        } else {
            var count = first & 0x7F;
            if (count == 0 || count > 8 || at + 17 + count > data.length) {
                return null;
            }
            length = 0;
            for (var index = 0; index < count; index++) {
                length = length << 8 | data[(int) at + 17 + index] & 0xFF;
            }
            value = at + 17 + count;
        }
        return length < 0 || value + length > data.length ? null : new Klv(at, value, length);
    }

    private void run() {
        if (!starts(0, PARTITION) || data.length < 17 || data[13] != 0x02) {
            findings.add("offset 0", "the file does not start with a header partition pack");
            return;
        }
        var packets = new ArrayList<Klv>();
        for (long at = 0; at < data.length; ) {
            var packet = klv(at);
            if (packet == null) {
                findings.add("offset " + at, "a KLV packet runs past the end of the file or has an invalid length");
                return;
            }
            if ((data[(int) at] & 0xFF) != 0x06 || data[(int) at + 1] != 0x0E || data[(int) at + 2] != 0x2B
                    || data[(int) at + 3] != 0x34) {
                findings.add("offset " + at, "a key without the SMPTE universal label prefix");
                return;
            }
            packets.add(packet);
            at = packet.end();
        }
        var metadataSets = new ArrayList<List<Klv>>();
        var indexSegments = new ArrayList<Klv>();
        long previousPartition = -1;
        long footer = -1;
        long declaredFooter = 0;
        long header = -1;
        for (var index = 0; index < packets.size(); index++) {
            var packet = packets.get(index);
            if (starts(packet.at, PARTITION) && data[(int) packet.at + 12] == 0x01
                    && data[(int) packet.at + 13] >= 2 && data[(int) packet.at + 13] <= 4) {
                var kind = data[(int) packet.at + 13];
                var status = data[(int) packet.at + 14];
                var where = "partition at " + packet.at;
                if (packet.length < 88) {
                    findings.add(where, "a partition pack of " + packet.length + " bytes");
                    continue;
                }
                var v = packet.value;
                findings.require(u16(v) == 1 && (u16(v + 2) == 2 || u16(v + 2) == 3), where,
                        "version " + u16(v) + "." + u16(v + 2));
                if (partitions.isEmpty()) {
                    kag = (int) Math.max(1, u32(v + 4));
                }
                findings.require(packet.at % kag == 0, where, "not on the KLV alignment grid of " + kag);
                findings.require(u64(v + 8) == packet.at, where, "ThisPartition is " + u64(v + 8));
                findings.require(previousPartition < 0 ? u64(v + 16) == 0 : u64(v + 16) == previousPartition, where,
                        "PreviousPartition is " + u64(v + 16) + ", the previous partition is at " + previousPartition);
                if (u64(v + 24) != 0) {
                    declaredFooter = u64(v + 24);
                }
                if (kind == 0x02) {
                    header = packet.at;
                    closedHeader = status == 0x04;
                    var op = Arrays.copyOfRange(data, (int) v + 64, (int) v + 80);
                    pattern = op[12] == 0x10 ? "OPAtom" : op[12] >= 1 && op[12] <= 3 && op[13] >= 1 && op[13] <= 3
                            ? "OP" + op[12] + (char) ('a' + op[13] - 1) : "unknown";
                } else if (kind == 0x04) {
                    footer = packet.at;
                }
                var headerBytes = u64(v + 32);
                var indexBytes = u64(v + 40);
                var bodySid = u32(v + 60);
                var batchCount = u32(v + 80);
                findings.require(u32(v + 84) == 16 && 88 + 16 * batchCount == packet.length, where,
                        "an essence container batch that does not fill the pack");
                var at = index + 1;
                while (at < packets.size() && starts(packets.get(at).at, FILL)) {
                    at++;
                }
                var metadata = new ArrayList<Klv>();
                if (at < packets.size() && starts(packets.get(at).at, PRIMER)) {
                    var start = packets.get(at).at;
                    findings.require(start % kag == 0, where, "header metadata not on the KLV alignment grid");
                    while (at < packets.size() && (starts(packets.get(at).at, PRIMER) || isSet(packets.get(at).at)
                            && !starts(packets.get(at).at, INDEX) || starts(packets.get(at).at, FILL))) {
                        metadata.add(packets.get(at));
                        at++;
                    }
                    var end = metadata.getLast().end();
                    findings.require(headerBytes == end - start, where, "HeaderByteCount " + headerBytes
                            + " but the header metadata spans " + (end - start) + " bytes");
                    metadataSets.add(metadata);
                } else {
                    findings.require(headerBytes == 0, where, "HeaderByteCount " + headerBytes
                            + " without header metadata");
                }
                if (at < packets.size() && starts(packets.get(at).at, INDEX)) {
                    var start = packets.get(at).at;
                    findings.require(start % kag == 0, where, "index table segments not on the KLV alignment grid");
                    long end = start;
                    while (at < packets.size() && (starts(packets.get(at).at, INDEX)
                            || starts(packets.get(at).at, FILL))) {
                        if (starts(packets.get(at).at, INDEX)) {
                            indexSegments.add(packets.get(at));
                        }
                        end = packets.get(at).end();
                        at++;
                    }
                    findings.require(indexBytes == end - start, where, "IndexByteCount " + indexBytes
                            + " but the index table segments span " + (end - start) + " bytes");
                    findings.require(u32(v + 48) != 0, where, "index table segments without an IndexSID");
                } else {
                    findings.require(indexBytes == 0, where, "IndexByteCount " + indexBytes + " without segments");
                }
                var hasEssence = at < packets.size() && isEssence(packets.get(at).at);
                findings.require(hasEssence == (bodySid != 0), where, hasEssence ? "essence with BodySID 0"
                        : "BodySID " + bodySid + " without essence");
                if (hasEssence) {
                    essenceStarts.computeIfAbsent(bodySid, ignored -> new ArrayList<>()).add(packets.get(at).at);
                    essenceStarts.computeIfAbsent(-bodySid, ignored -> new ArrayList<>()).add(u64(v + 52));
                }
                partitions.add(new long[]{packet.at, bodySid});
                previousPartition = packet.at;
                continue;
            }
            if (starts(packet.at, RIP)) {
                randomIndex = true;
                findings.require(index == packets.size() - 1, "random index pack at " + packet.at,
                        "the random index pack is not the last packet");
                var entries = (packet.length - 4) / 12;
                findings.require((packet.length - 4) % 12 == 0 && entries == partitions.size(),
                        "random index pack at " + packet.at, "lists " + entries + " partitions of "
                                + partitions.size());
                for (var entry = 0; entry < Math.min(entries, partitions.size()); entry++) {
                    var at = packet.value + 12 * entry;
                    findings.require(u32(at) == partitions.get(entry)[1] && u64(at + 4) == partitions.get(entry)[0],
                            "random index pack entry " + entry, "names body SID " + u32(at) + " at " + u64(at + 4));
                }
                findings.require(u32(packet.end() - 4) == packet.end() - packet.at, "random index pack",
                        "the overall length " + u32(packet.end() - 4) + " is not the pack's");
                continue;
            }
            if (isEssence(packet.at)) {
                elementOffsets.add(packet.at);
            }
            if (starts(packet.at, ELEMENT)) {
                var number = (int) u32(packet.at + 12);
                elements.merge(number, 1, Integer::sum);
                if (kag > 1 && (data[(int) packet.at + 12] == 0x15 || data[(int) packet.at + 12] == 0x16)) {
                    findings.require(packet.at % kag == 0, "element at " + packet.at,
                            "not on the KLV alignment grid of " + kag);
                }
                var type = data[(int) packet.at + 14];
                var item = data[(int) packet.at + 12];
                if (item == 0x16 && (type == 0x02 || type == 0x04) || item == 0x15 && (type == 0x0D || type == 0x06)) {
                    clips.add(new long[]{packet.at, packet.value, packet.length});
                }
            }
        }
        findings.require(footer < 0 || declaredFooter == 0 || declaredFooter == footer, "partitions",
                "FooterPartition " + declaredFooter + " but the footer is at " + footer);
        if (pattern.equals("OPAtom")) {
            findings.require(closedHeader, "header partition", "an OP-Atom header partition must be closed and complete");
            findings.require(metadataSets.size() == 1, "header metadata", "OP-Atom holds header metadata in "
                    + metadataSets.size() + " partitions");
            findings.require(footer >= 0 && !indexSegments.isEmpty(), "footer", "OP-Atom needs an index table");
        }
        for (var metadata : metadataSets) {
            headerMetadata(metadata);
        }
        index(indexSegments);
    }

    /**
     * Returns whether a packet belongs to an essence container: a generic container element or system item, whose
     * keys share bytes 8 to 11.
     */
    private boolean isEssence(long at) {
        return at + 16 <= data.length && data[(int) at + 8] == 0x0D && data[(int) at + 9] == 0x01
                && data[(int) at + 10] == 0x03 && data[(int) at + 11] == 0x01;
    }

    private boolean isSet(long at) {
        return at + 6 <= data.length && data[(int) at + 4] == 0x02 && data[(int) at + 5] == 0x53;
    }

    private void headerMetadata(List<Klv> metadata) {
        var primer = metadata.getFirst();
        var where = "primer at " + primer.at;
        var count = u32(primer.value);
        findings.require(u32(primer.value + 4) == 18 && 8 + 18 * count == primer.length, where, "a malformed batch");
        var tags = new HashSet<Integer>();
        for (var entry = 0; entry < count && 8 + 18 * (entry + 1) <= primer.length; entry++) {
            var tag = u16(primer.value + 8 + 18L * entry);
            findings.require(tags.add(tag), where, "local tag " + Integer.toHexString(tag) + " twice");
        }
        var byUid = new HashMap<String, Integer>();
        var references = new ArrayList<Object[]>();
        var packages = new HashMap<String, Set<Long>>();
        var trackNumbers = new ArrayList<Long>();
        var clipsOf = new ArrayList<Object[]>();
        var prefaces = 0;
        for (var packet : metadata) {
            if (!isSet(packet.at)) {
                continue;
            }
            sets++;
            var kind = data[(int) packet.at + 14] & 0xFF;
            var setWhere = "set " + Integer.toHexString(kind) + " at " + packet.at;
            prefaces += kind == 0x2F ? 1 : 0;
            var items = new HashMap<Integer, byte[]>();
            for (long at = packet.value; at < packet.end(); ) {
                if (at + 4 > packet.end()) {
                    findings.add(setWhere, "a truncated local item");
                    break;
                }
                var tag = u16(at);
                var length = u16(at + 2);
                if (at + 4 + length > packet.end()) {
                    findings.add(setWhere, "local item " + Integer.toHexString(tag) + " overruns its set");
                    break;
                }
                findings.require(tags.contains(tag), setWhere, "local tag " + Integer.toHexString(tag)
                        + " is not in the primer");
                items.put(tag, Arrays.copyOfRange(data, (int) at + 4, (int) at + 4 + length));
                at += 4 + length;
            }
            var uid = items.get(0x3C0A);
            if (uid == null || uid.length != 16) {
                findings.add(setWhere, "no InstanceUID");
                continue;
            }
            findings.require(byUid.put(Arrays.toString(uid), kind) == null, setWhere, "a duplicate InstanceUID");
            for (var entry : items.entrySet()) {
                var targets = STRONG.get(entry.getKey());
                if (targets == null) {
                    continue;
                }
                var value = entry.getValue();
                if (value.length == 16) {
                    references.add(new Object[]{setWhere, Arrays.toString(value), targets});
                } else if (value.length >= 8) {
                    var number = ByteBuffer.wrap(value).getInt();
                    var size = ByteBuffer.wrap(value, 4, 4).getInt();
                    findings.require(size == 16 && 8 + 16L * number == value.length, setWhere,
                            "a malformed reference batch " + Integer.toHexString(entry.getKey()));
                    for (var element = 0; element < number && 8 + 16 * (element + 1) <= value.length; element++) {
                        references.add(new Object[]{setWhere,
                                Arrays.toString(Arrays.copyOfRange(value, 8 + 16 * element, 24 + 16 * element)),
                                targets});
                    }
                }
            }
            if ((kind == 0x36 || kind == 0x37) && items.containsKey(0x4401)) {
                packages.put(Arrays.toString(items.get(0x4401)), new HashSet<>());
            }
            if (kind == 0x3B && items.containsKey(0x4804)) {
                trackNumbers.add(ByteBuffer.wrap(items.get(0x4804)).getInt() & 0xFFFFFFFFL);
            }
            if (kind == 0x11 && items.containsKey(0x1101)) {
                clipsOf.add(new Object[]{setWhere, Arrays.toString(items.get(0x1101)),
                        items.containsKey(0x1102) ? (long) ByteBuffer.wrap(items.get(0x1102)).getInt() : -1L});
            }
        }
        findings.require(prefaces == 1, "header metadata", prefaces + " prefaces");
        for (var reference : references) {
            var kind = byUid.get((String) reference[1]);
            @SuppressWarnings("unchecked")
            var targets = (Set<Integer>) reference[2];
            findings.require(kind != null, (String) reference[0], "a strong reference to no set");
            findings.require(kind == null || targets.isEmpty() || targets.contains(kind), (String) reference[0],
                    "a strong reference to a set of kind " + (kind == null ? "none" : Integer.toHexString(kind)));
        }
        var zero = Arrays.toString(new byte[32]);
        for (var clip : clipsOf) {
            var umid = (String) clip[1];
            findings.require(umid.equals(zero) || packages.containsKey(umid) || !pattern.startsWith("OP1"),
                    (String) clip[0], "a source clip naming a package that is not in the file");
        }
        for (var number : trackNumbers) {
            if (number != 0) {
                findings.require(elements.containsKey(number.intValue()), "track number " + Long.toHexString(number),
                        "no essence element carries the track number");
            }
        }
    }

    private void index(List<Klv> segments) {
        if (segments.isEmpty()) {
            return;
        }
        var offsets = new HashMap<Long, Long>();
        var temporal = new HashMap<Long, Integer>();
        var flags = new HashMap<Long, Integer>();
        var constant = false;
        long end = 0;
        long body = 0;
        for (var segment : segments) {
            var where = "index table segment at " + segment.at;
            var items = new HashMap<Integer, byte[]>();
            for (long at = segment.value; at + 4 <= segment.end(); ) {
                var length = u16(at + 2);
                items.put(u16(at), Arrays.copyOfRange(data, (int) at + 4, (int) Math.min(segment.end(), at + 4 + length)));
                at += 4 + length;
            }
            var start = items.containsKey(0x3F0C) ? ByteBuffer.wrap(items.get(0x3F0C)).getLong() : -1;
            var duration = items.containsKey(0x3F0D) ? ByteBuffer.wrap(items.get(0x3F0D)).getLong() : -1;
            var byteCount = items.containsKey(0x3F05) ? ByteBuffer.wrap(items.get(0x3F05)).getInt() : 0;
            body = items.containsKey(0x3F07) ? ByteBuffer.wrap(items.get(0x3F07)).getInt() & 0xFFFFFFFFL : 0;
            findings.require(items.containsKey(0x3F0B) && items.containsKey(0x3F0C) && items.containsKey(0x3F0D)
                    && items.containsKey(0x3F07), where, "a required item is missing");
            var array = items.get(0x3F0A);
            if (byteCount != 0 && array == null) {
                constant = true;
                end = Math.max(end, start + duration);
                continue;
            }
            if (array == null || array.length < 8) {
                findings.add(where, "no index entry array");
                continue;
            }
            var number = ByteBuffer.wrap(array).getInt();
            var size = ByteBuffer.wrap(array, 4, 4).getInt();
            findings.require(size >= 11 && 8L + (long) number * size == array.length, where, "a malformed entry array");
            findings.require(number == duration, where, number + " entries for a duration of " + duration);
            for (var entry = 0; entry < number && 8 + (entry + 1) * size <= array.length; entry++) {
                var at = 8 + entry * size;
                offsets.put(start + entry, ByteBuffer.wrap(array, at + 3, 8).getLong());
                temporal.put(start + entry, (int) array[at]);
                flags.put(start + entry, array[at + 2] & 0xFF);
            }
            end = Math.max(end, start + number);
        }
        editUnits = end;
        if (constant) {
            return;
        }
        for (long unit = 0; unit < end; unit++) {
            findings.require(offsets.containsKey(unit), "index", "no entry for edit unit " + unit);
        }
        findings.require(end == 0 || (flags.getOrDefault(0L, 0) & 0x80) != 0, "index",
                "the first edit unit is not a random access point");
        var seen = new HashSet<Long>();
        for (long unit = 0; unit < end; unit++) {
            var stored = unit + temporal.getOrDefault(unit, 0);
            findings.require(stored >= 0 && stored < end && seen.add(stored), "index entry " + unit,
                    "the temporal offset " + temporal.get(unit) + " does not permute the edit units");
        }
        var starts = essenceStarts.getOrDefault(body, List.of());
        var bodyOffsets = essenceStarts.getOrDefault(-body, List.of());
        if (starts.isEmpty()) {
            findings.add("index", "an index of body SID " + body + " whose essence is not in the file");
            return;
        }
        var keys = new HashSet<>(elementOffsets);
        for (long unit = 0; unit < end; unit++) {
            var offset = offsets.get(unit);
            if (offset == null) {
                continue;
            }
            var partition = 0;
            for (var candidate = 0; candidate < bodyOffsets.size(); candidate++) {
                if (bodyOffsets.get(candidate) <= offset) {
                    partition = candidate;
                }
            }
            if (!clips.isEmpty()) {
                var clip = clips.getFirst();
                findings.require(offset >= 0 && offset < clip[2], "index entry " + unit,
                        "a stream offset " + offset + " past the clip of " + clip[2] + " bytes");
                continue;
            }
            var file = starts.get(partition) + offset - bodyOffsets.get(partition);
            findings.require(keys.contains(file), "index entry " + unit, "the stream offset " + offset
                    + " (file offset " + file + ") is not the key of an essence element");
        }
    }
}
