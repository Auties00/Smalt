package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.container.mxf.MxfSet.Uid;
import com.github.auties00.smalt.container.mxf.MxfSet.Umid;
import com.github.auties00.smalt.track.Rational;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the header metadata of an MXF file into the essence tracks of its file packages, its tags and its
 * timecode (SMPTE ST 377-1 clauses 9 and 10, Annexes A and F).
 *
 * <p>The essence tracks are the tracks of the file (top-level source) packages with a nonzero track number and a
 * picture, sound or data definition, each described by the package's descriptor or, for a multiple descriptor, by the
 * sub-descriptor linked to the track. The material package gives the title, the user comments and the timecode; a
 * file or tape package gives the timecode when the material package has none.
 */
final class MxfStructure {
    /**
     * The largest number of strong references followed from one set.
     */
    private static final int MAX_REFERENCES = 4096;

    /**
     * The sets by instance UID.
     */
    private final Map<Uid, MxfSet> sets;

    /**
     * The essence tracks found, in package and track order.
     */
    final List<MxfStream> streams = new ArrayList<>();

    /**
     * The file-level tags.
     */
    final Map<String, String> metadata = new LinkedHashMap<>();

    /**
     * The tags of each essence track, by track number.
     */
    final Map<Integer, Map<String, String>> trackMetadata = new HashMap<>();

    /**
     * The timecode of the first frame, or {@code null}.
     */
    Timecode timecode;

    /**
     * The edit rate of the material package's picture track, or of its first track, or {@code null}.
     */
    Rational materialRate;

    /**
     * The duration of the material package in edit units of {@link #materialRate}, or {@code -1}.
     */
    long materialDuration = -1;

    /**
     * Whether the operational pattern is OP-Atom.
     */
    boolean atom;

    MxfStructure(Map<Uid, MxfSet> sets) {
        this.sets = sets;
    }

    /**
     * Resolves the sets.
     *
     * @throws MediaException.Malformed if no preface or no package is found
     */
    void resolve() {
        MxfSet preface = null;
        for (var set : sets.values()) {
            if (set.kind() == MxfLabels.PREFACE) {
                preface = set;
                break;
            }
        }
        List<MxfSet> packages = new ArrayList<>();
        var essenceData = new ArrayList<MxfSet>();
        var storage = preface == null ? null : set(preface.uid(0x3B03));
        if (storage != null) {
            for (var reference : limit(storage.references(0x1901))) {
                var found = set(reference);
                if (found != null) {
                    packages.add(found);
                }
            }
            for (var reference : limit(storage.references(0x1902))) {
                var found = set(reference);
                if (found != null) {
                    essenceData.add(found);
                }
            }
        }
        if (packages.isEmpty()) {
            for (var set : sets.values()) {
                if (set.kind() == MxfLabels.MATERIAL_PACKAGE || set.kind() == MxfLabels.SOURCE_PACKAGE) {
                    packages.add(set);
                }
            }
        }
        if (essenceData.isEmpty()) {
            for (var set : sets.values()) {
                if (set.kind() == MxfLabels.ESSENCE_CONTAINER_DATA) {
                    essenceData.add(set);
                }
            }
        }
        if (packages.isEmpty()) {
            throw new MediaException.Malformed("MXF header metadata without a package");
        }
        if (preface != null) {
            var pattern = preface.label(0x3B09);
            atom = pattern != null && pattern[12] == 0x10;
            metadata.put("operational_pattern", patternName(pattern));
            identification(preface);
        }
        var bodies = new HashMap<Umid, Long>();
        for (var data : essenceData) {
            var umid = data.umid(0x2701);
            if (umid != null) {
                bodies.put(umid, data.u32(0x3F07, 0));
            }
        }
        MxfSet material = null;
        var sources = new ArrayList<MxfSet>();
        for (var pack : packages) {
            if (pack.kind() == MxfLabels.MATERIAL_PACKAGE) {
                if (material == null) {
                    material = pack;
                }
            } else if (pack.kind() == MxfLabels.SOURCE_PACKAGE) {
                sources.add(pack);
            }
        }
        var filePackages = new ArrayList<MxfSet>();
        for (var source : sources) {
            var umid = source.umid(0x4401);
            var descriptor = set(source.uid(0x4701));
            if (umid != null && bodies.containsKey(umid) || bodies.isEmpty() && isFileDescriptor(descriptor)) {
                filePackages.add(source);
            }
        }
        for (var pack : filePackages) {
            var umid = pack.umid(0x4401);
            var body = umid == null ? null : bodies.get(umid);
            essenceTracks(pack, body == null ? 0 : body);
        }
        if (material != null) {
            material(material);
        }
        if (timecode == null) {
            for (var pack : filePackages) {
                timecode = timecode(pack);
                if (timecode != null) {
                    break;
                }
            }
        }
        if (timecode == null) {
            for (var source : sources) {
                if (!filePackages.contains(source)) {
                    timecode = timecode(source);
                    if (timecode != null) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * {@return a set, or {@code null}}
     *
     * @param uid the instance UID, or {@code null}
     */
    private MxfSet set(Uid uid) {
        return uid == null ? null : sets.get(uid);
    }

    private static <T> List<T> limit(List<T> list) {
        return list.size() > MAX_REFERENCES ? list.subList(0, MAX_REFERENCES) : list;
    }

    /**
     * {@return whether a descriptor describes file essence: a file descriptor with an essence container label}
     *
     * @param descriptor the descriptor, or {@code null}
     */
    private static boolean isFileDescriptor(MxfSet descriptor) {
        return descriptor != null && (descriptor.has(0x3004) || descriptor.kind() == MxfLabels.MULTIPLE_DESCRIPTOR);
    }

    /**
     * Reads the product of the last identification set.
     *
     * @param preface the preface set, which lists the identifications
     */
    private void identification(MxfSet preface) {
        var list = preface.references(0x3B06);
        MxfSet last = null;
        for (var reference : limit(list)) {
            var found = set(reference);
            if (found != null) {
                last = found;
            }
        }
        if (last == null) {
            return;
        }
        put("company_name", last.string(0x3C01));
        put("product_name", last.string(0x3C02));
        put("product_version", last.string(0x3C04));
        put("application_platform", last.string(0x3C08));
        var modified = last.get(0x3C06);
        if (modified != null && modified.length >= 8 && (modified[0] != 0 || modified[1] != 0)) {
            put("modification_date", timestamp(modified));
        }
    }

    private void put(String key, String value) {
        if (value != null && !value.isEmpty()) {
            metadata.put(key, value);
        }
    }

    /**
     * Formats an MXF timestamp as ISO 8601.
     *
     * @param value year (16 bits), month, day, hour, minute, second and quarter milliseconds
     * @return the date and time, in UTC
     */
    static String timestamp(byte[] value) {
        var year = (value[0] & 0xFF) << 8 | value[1] & 0xFF;
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".formatted(year, value[2] & 0xFF, value[3] & 0xFF,
                value[4] & 0xFF, value[5] & 0xFF, value[6] & 0xFF, Math.min(999, 4 * (value[7] & 0xFF)));
    }

    /**
     * {@return the name of an operational pattern label}
     *
     * @param label the label, or {@code null}
     */
    static String patternName(byte[] label) {
        if (label == null || !MxfLabels.startsWith(label, MxfLabels.bytes("060e2b34040101010d010201"))) {
            return "unknown";
        }
        if (label[12] == 0x10) {
            return "OPAtom";
        }
        if (label[12] >= 1 && label[12] <= 3 && label[13] >= 1 && label[13] <= 3) {
            return "OP" + label[12] + (char) ('a' + label[13] - 1);
        }
        return "unknown";
    }

    /**
     * Collects the essence tracks of a file package.
     *
     * @param pack the file package
     * @param bodySid the body stream id of its essence container, or 0
     */
    private void essenceTracks(MxfSet pack, long bodySid) {
        var descriptor = set(pack.uid(0x4701));
        var subDescriptors = new ArrayList<MxfSet>();
        if (descriptor != null && descriptor.kind() == MxfLabels.MULTIPLE_DESCRIPTOR) {
            for (var reference : limit(descriptor.references(0x3F01))) {
                var found = set(reference);
                if (found != null) {
                    subDescriptors.add(found);
                }
            }
        }
        var essenceIndex = 0;
        for (var reference : limit(pack.references(0x4403))) {
            var track = set(reference);
            if (track == null || track.kind() != MxfLabels.TIMELINE_TRACK && track.kind() != MxfLabels.STATIC_TRACK
                    && track.kind() != MxfLabels.EVENT_TRACK) {
                continue;
            }
            var number = (int) track.u32(0x4804, 0);
            var segment = set(track.uid(0x4803));
            var kind = segment == null ? 0 : MxfLabels.dataKind(segment.label(0x0201));
            if (number == 0 || kind != 'p' && kind != 's' && kind != 'd') {
                continue;
            }
            var trackId = (int) track.u32(0x4801, 0);
            MxfSet described = descriptor;
            if (!subDescriptors.isEmpty()) {
                described = null;
                for (var sub : subDescriptors) {
                    if (sub.has(0x3006) && sub.u32(0x3006, -1) == trackId) {
                        described = sub;
                        break;
                    }
                }
                if (described == null && essenceIndex < subDescriptors.size()) {
                    described = subDescriptors.get(essenceIndex);
                }
            }
            essenceIndex++;
            var stream = new MxfStream();
            stream.trackNumber = number;
            stream.fileTrackId = trackId;
            stream.kind = kind;
            stream.bodySid = bodySid;
            stream.editRate = rational(track.rational(0x4B01));
            stream.duration = segment.s64(0x0202, -1);
            stream.name = track.string(0x4802);
            if (described != null) {
                describe(stream, described);
            }
            if (stream.editRate == null) {
                throw new MediaException.Malformed("MXF essence track " + trackId + " without an edit rate");
            }
            streams.add(stream);
        }
    }

    /**
     * {@return a positive rational, or {@code null}}
     *
     * @param value numerator and denominator, or {@code null}
     */
    static Rational rational(long[] value) {
        return value == null || value[0] <= 0 || value[1] <= 0 ? null : new Rational(value[0], value[1]);
    }

    /**
     * Copies the properties of a file descriptor onto a stream.
     *
     * @param stream the essence track read
     * @param descriptor the file descriptor set
     */
    private void describe(MxfStream stream, MxfSet descriptor) {
        stream.container = descriptor.label(0x3004);
        if (stream.kind == 'p') {
            stream.coding = descriptor.label(0x3201);
            for (var reference : limit(descriptor.references(MxfLabels.SUB_DESCRIPTORS_TAG))) {
                var sub = set(reference);
                if (sub != null && sub.kind() == MxfLabels.AVC_SUB_DESCRIPTOR) {
                    stream.decodingDelay = sub.u8(MxfLabels.AVC_DECODING_DELAY_TAG, 0xFF);
                }
            }
            var layout = descriptor.u8(0x320C, 0);
            stream.frameLayout = layout;
            var stored = new int[]{(int) descriptor.u32(0x3203, 0), (int) descriptor.u32(0x3202, 0)};
            var width = (int) descriptor.u32(0x3209, descriptor.u32(0x3205, stored[0]));
            var height = (int) descriptor.u32(0x3208, descriptor.u32(0x3204, stored[1]));
            stream.width = Math.max(0, width);
            stream.height = Math.max(0, layout == 1 ? 2 * height : height);
            pictureDetails.put(stream, descriptor);
        } else if (stream.kind == 's') {
            stream.coding = descriptor.label(0x3D06);
            var rate = rational(descriptor.rational(0x3D03));
            stream.sampleRate = rate == null ? 0 : (int) Math.min(Integer.MAX_VALUE,
                    (rate.numerator() + rate.denominator() / 2) / rate.denominator());
            stream.channels = (int) Math.min(Integer.MAX_VALUE, descriptor.u32(0x3D07, 0));
            stream.bits = (int) Math.min(Integer.MAX_VALUE, descriptor.u32(0x3D01, 0));
            stream.blockAlign = descriptor.u16(0x3D0A, 0);
            soundKind.put(stream, descriptor.kind());
        }
        if (stream.container != null) {
            var wrapping = stream.container[14];
            var family = stream.container[13];
            stream.clipWrapped = family == 0x06 ? wrapping == 0x02 || wrapping == 0x04
                    : family == 0x10 || family == 0x0F || family == 0x04 ? stream.container[15] == 0x02
                    : wrapping == 0x02;
        }
    }

    /**
     * The picture descriptor of each picture stream, for the details the demuxer reads into its track.
     */
    final Map<MxfStream, MxfSet> pictureDetails = new HashMap<>();

    /**
     * The set kind of the descriptor of each sound stream.
     */
    final Map<MxfStream, Integer> soundKind = new HashMap<>();

    /**
     * Reads the title, user comments, timecode and duration of the material package.
     *
     * @param material the material package
     */
    private void material(MxfSet material) {
        put("title", material.string(0x4402));
        var umid = material.get(0x4401);
        if (umid != null && umid.length == 32) {
            metadata.put("material_package_umid", HexFormat.of().formatHex(umid));
        }
        for (var reference : limit(material.references(0x4406))) {
            var tagged = set(reference);
            if (tagged == null || tagged.kind() != MxfLabels.TAGGED_VALUE) {
                continue;
            }
            var name = tagged.string(0x5001);
            var value = indirectString(tagged.get(0x5003));
            if (name != null && !name.isEmpty() && value != null) {
                metadata.putIfAbsent(name, value);
            }
        }
        var timecodeFound = timecode(material);
        if (timecodeFound != null) {
            timecode = timecodeFound;
        }
        for (var reference : limit(material.references(0x4403))) {
            var track = set(reference);
            if (track == null) {
                continue;
            }
            var segment = set(track.uid(0x4803));
            if (segment == null) {
                continue;
            }
            var kind = MxfLabels.dataKind(segment.label(0x0201));
            if (kind != 'p' && kind != 's') {
                continue;
            }
            var rate = rational(track.rational(0x4B01));
            if (rate != null && (materialRate == null || kind == 'p')) {
                materialRate = rate;
                materialDuration = segment.s64(0x0202, -1);
            }
            var name = track.string(0x4802);
            if (name == null || name.isEmpty()) {
                continue;
            }
            var clip = firstComponent(segment, MxfLabels.SOURCE_CLIP);
            if (clip == null) {
                continue;
            }
            var sourceTrack = clip.u32(0x1102, -1);
            for (var stream : streams) {
                if (stream.fileTrackId == sourceTrack && stream.name == null) {
                    stream.name = name;
                }
            }
        }
        for (var stream : streams) {
            if (stream.name != null && !stream.name.isEmpty()) {
                trackMetadata.computeIfAbsent(stream.trackNumber, ignored -> new LinkedHashMap<>())
                        .put("title", stream.name);
            }
        }
    }

    /**
     * Decodes an indirect value holding a string (SMPTE ST 377-1 Annex B: byte order, type label, value).
     *
     * @param value the item, or {@code null}
     * @return the string, or {@code null} when the value is not a UTF-16 or UTF-8 string
     */
    static String indirectString(byte[] value) {
        if (value == null || value.length < 17 || value[0] != 0x42 && value[0] != 0x4C) {
            return null;
        }
        var type = MxfSet.slice(value, 1, 16);
        var bigEndian = value[0] == 0x42;
        if (MxfLabels.matches(type, MxfLabels.bytes("060e2b34010401010110020000000000"))) {
            var end = value.length - ((value.length - 17) & 1);
            var text = new String(value, 17, end - 17, bigEndian ? StandardCharsets.UTF_16BE
                    : StandardCharsets.UTF_16LE);
            var nul = text.indexOf('\0');
            return nul >= 0 ? text.substring(0, nul) : text;
        }
        if (MxfLabels.matches(type, MxfLabels.bytes("060e2b34010401010110060000000000"))) {
            var text = new String(value, 17, value.length - 17, StandardCharsets.UTF_8);
            var nul = text.indexOf('\0');
            return nul >= 0 ? text.substring(0, nul) : text;
        }
        return null;
    }

    /**
     * Returns the first component of a kind in a track's segment.
     *
     * @param segment the sequence, or the component itself
     * @param kind the set byte of the component
     * @return the component, or {@code null}
     */
    private MxfSet firstComponent(MxfSet segment, int kind) {
        if (segment.kind() == kind) {
            return segment;
        }
        if (segment.kind() != MxfLabels.SEQUENCE) {
            return null;
        }
        for (var reference : limit(segment.references(0x1001))) {
            var component = set(reference);
            if (component != null && component.kind() == kind) {
                return component;
            }
        }
        return null;
    }

    /**
     * Returns the start of the first timecode track of a package.
     *
     * @param pack the package
     * @return the timecode, or {@code null}
     */
    private Timecode timecode(MxfSet pack) {
        for (var reference : limit(pack.references(0x4403))) {
            var track = set(reference);
            if (track == null) {
                continue;
            }
            var segment = set(track.uid(0x4803));
            if (segment == null) {
                continue;
            }
            var component = firstComponent(segment, MxfLabels.TIMECODE_COMPONENT);
            if (component == null) {
                continue;
            }
            var base = component.u16(0x1502, 0);
            var start = component.s64(0x1501, -1);
            if (base <= 0 || start < 0) {
                continue;
            }
            var drop = component.u8(0x1503, 0) != 0 && (base == 30 || base == 60);
            try {
                return Timecode.ofFrameNumber(start, base, drop);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        return null;
    }
}
