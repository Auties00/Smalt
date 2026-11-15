package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.header.Crc32Mpeg;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits the track tags of MPEG transport streams: the name of each elementary stream, as an ATSC
 * A/65 component name descriptor, and the {@code audio_type} of the ISO 639 language descriptor of
 * each audio stream, in the program map table of the program {@link TsDemuxer} exposes.
 *
 * <p>The tracks are those of the demuxer, identified by their PID. A transport stream stores no
 * other tags, so a write that holds tags, pictures or chapters for the whole file is rejected,
 * and the other tags of a track are dropped. The program map table is repeated through the
 * stream; a write rebuilds every copy of it, in place: each copy keeps its transport packets
 * (headers, adaptation fields and continuity counters unchanged) and the new section, followed
 * by {@code 0xFF} stuffing, takes their payload. Nothing else of the file changes, so an edit is
 * rejected when a rebuilt section outgrows the packets of its copy, or when a copy shares its
 * packets with another section, as the layout then leaves no room to grow.
 */
final class TsTagFile implements TagFile {
    /**
     * The transport packet synchronization byte.
     */
    private static final int SYNC = 0x47;

    /**
     * The size of a transport packet without the M2TS header and the Reed-Solomon parity.
     */
    private static final int PACKET = 188;

    /**
     * The {@code table_id} of a program map section.
     */
    private static final int PMT_TABLE = 0x02;

    /**
     * The largest {@code section_length} of a PSI section (ISO/IEC 13818-1 clause 2.4.4).
     */
    private static final int MAX_SECTION_LENGTH = 1021;

    /**
     * The largest {@code ES_info_length} the 10 bits of a program map entry hold.
     */
    private static final int MAX_DESCRIPTORS = 1023;

    /**
     * The bytes before the first packet searched for it.
     */
    private static final int MAX_LEADING = 1 << 20;

    /**
     * The number of packets read at once while scanning.
     */
    private static final int BLOCK_PACKETS = 2048;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The size of a packet: 188, 192 (M2TS) or 204 bytes.
     */
    private final int packetSize;

    /**
     * The offset of the synchronization byte inside a packet.
     */
    private final int syncOffset;

    /**
     * The offset of the first packet.
     */
    private final long first;

    /**
     * The {@code program_number} of the program.
     */
    private final int program;

    /**
     * The PID of the program map table.
     */
    private final int pmtPid;

    /**
     * The tracks of the program, by PID.
     */
    private final Map<Integer, Track> tracks = new LinkedHashMap<>();

    /**
     * The decoded track tags.
     */
    private final MediaTags tags;

    /**
     * Opens the transport stream and reads the track tags of its program.
     *
     * @param source the transport stream; not closed
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the stream has no program or packet layout
     */
    TsTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        var builder = MediaTags.builder();
        try (var demuxer = new TsDemuxer(source, DemuxerOptions.builder().limits(limits).build())) {
            this.packetSize = demuxer.packetSize();
            this.program = demuxer.program().number();
            this.pmtPid = demuxer.program().pmtPid();
            for (var track : demuxer.tracks()) {
                tracks.put(track.id(), track);
                var name = demuxer.trackMetadata(track.id()).get(StandardTag.TITLE.key());
                var trackTags = name == null ? MediaTags.empty()
                        : MediaTags.builder().set(StandardTag.TITLE, name).build();
                builder.track(new TrackTags(track.id(), trackTags, demuxer.trackDisposition(track.id())));
            }
        }
        this.syncOffset = packetSize == 192 ? 4 : 0;
        this.first = firstPacket();
        this.tags = builder.build();
    }

    /**
     * Finds the first packet: the first offset where the synchronization byte opens a packet that
     * the next one follows.
     *
     * @return the offset
     * @throws MediaException.Malformed if no packet is found
     */
    private long firstPacket() {
        var size = source.size();
        var window = TagIo.readAvailable(source, 0, (int) Math.min(size, MAX_LEADING + 2L * packetSize));
        for (var start = 0; start + syncOffset < window.length && start < MAX_LEADING; start++) {
            if ((window[start + syncOffset] & 0xFF) != SYNC) {
                continue;
            }
            var next = start + packetSize + syncOffset;
            if (next >= window.length || (window[next] & 0xFF) == SYNC) {
                return start;
            }
        }
        throw new MediaException.Malformed("no MPEG transport stream packets found");
    }

    @Override
    public MediaTags read() {
        return tags;
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        if (!MuxerTags.isGlobalEmpty(newTags)) {
            throw new MediaException.Unsupported("mpeg-ts", "transport streams store only track tags: the names"
                    + " and audio types of their tracks");
        }
        var changed = new HashMap<Integer, TrackTags>();
        for (var track : newTags.tracks()) {
            var old = tags.track(track.trackId());
            if (old.isPresent() && tracks.containsKey(track.trackId()) && !old.get().equals(track)) {
                changed.put(track.trackId(), track);
            }
        }
        if (changed.isEmpty()) {
            return EditPlan.builder().copy(0, source.size()).build();
        }
        var occurrences = scan();
        if (occurrences.isEmpty()) {
            throw new MediaException.Unsupported("mpeg-ts", "the program map table of program " + program
                    + " is not found in the stream");
        }
        var originals = new ArrayList<byte[]>();
        var rebuilt = new ArrayList<byte[]>();
        var slices = new HashMap<String, byte[]>();
        var plan = EditPlan.builder();
        var position = 0L;
        for (var occurrence : occurrences) {
            var section = occurrence.section();
            if (!isEdited(section)) {
                continue;
            }
            var index = indexOf(originals, section);
            if (index < 0) {
                originals.add(section);
                rebuilt.add(rebuild(section, changed));
                index = originals.size() - 1;
            }
            var payload = rebuilt.get(index);
            var capacity = 0;
            for (var range : occurrence.payloads()) {
                capacity += (int) (range[1] - range[0]);
            }
            if (1 + payload.length > capacity) {
                throw new MediaException.Unsupported("mpeg-ts", "the edited program map section of " + payload.length
                        + " bytes outgrows the " + (capacity - 1) + " bytes its transport packets carry");
            }
            var written = 0;
            for (var range : occurrence.payloads()) {
                var length = (int) (range[1] - range[0]);
                var key = index + ":" + written + ":" + length;
                var slice = slices.get(key);
                if (slice == null) {
                    slice = new byte[length];
                    Arrays.fill(slice, (byte) 0xFF);
                    for (var at = 0; at < length; at++) {
                        var offset = written + at;
                        if (offset == 0) {
                            slice[at] = 0;
                        } else if (offset - 1 < payload.length) {
                            slice[at] = payload[offset - 1];
                        }
                    }
                    slices.put(key, slice);
                }
                plan.copy(position, range[0] - position).bytes(slice);
                position = range[1];
                written += length;
            }
        }
        plan.copy(position, source.size() - position);
        return plan.build();
    }

    /**
     * Returns whether a section is a program map section of the program, which an edit rebuilds.
     *
     * @param section the whole section
     * @return {@code true} for a valid program map section of the program
     */
    private boolean isEdited(byte[] section) {
        if (section.length < 16 || (section[0] & 0xFF) != PMT_TABLE || (section[1] & 0x80) == 0) {
            return false;
        }
        var number = (section[3] & 0xFF) << 8 | (section[4] & 0xFF);
        return number == program && Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, section, 0, section.length) == 0;
    }

    /**
     * Returns the position of a section among those already rebuilt.
     *
     * @param sections the sections already rebuilt
     * @param section the section looked for
     * @return its index, or {@code -1}
     */
    private static int indexOf(List<byte[]> sections, byte[] section) {
        for (var index = 0; index < sections.size(); index++) {
            if (Arrays.equals(sections.get(index), section)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Rebuilds a program map section with the new names and audio types of the changed tracks.
     *
     * @param section the whole section, its CRC valid
     * @param changed the new tags of the changed tracks, by PID
     * @return the rebuilt section, its CRC included
     * @throws MediaException.LimitExceeded if the descriptors of a stream or the section outgrow their length fields
     */
    private byte[] rebuild(byte[] section, Map<Integer, TrackTags> changed) {
        var end = section.length - 4;
        var infoLength = (section[10] & 0x0F) << 8 | (section[11] & 0xFF);
        var position = Math.min(end, 12 + infoLength);
        var out = new ByteWriter(section.length + 256);
        out.bytes(section, 0, position);
        while (position + 5 <= end) {
            var pid = (section[position + 1] & 0x1F) << 8 | (section[position + 2] & 0xFF);
            var length = (section[position + 3] & 0x0F) << 8 | (section[position + 4] & 0xFF);
            if (position + 5 + length > end) {
                break;
            }
            var descriptors = Arrays.copyOfRange(section, position + 5, position + 5 + length);
            var track = changed.get(pid);
            if (track != null) {
                descriptors = descriptors(descriptors, track, tracks.get(pid));
                if (descriptors.length > MAX_DESCRIPTORS) {
                    throw new MediaException.LimitExceeded("the descriptors of stream " + pid + " exceed the "
                            + MAX_DESCRIPTORS + " bytes a program map entry holds");
                }
            }
            out.bytes(section, position, 3);
            out.u8((section[position + 3] & 0xF0) | descriptors.length >> 8);
            out.u8(descriptors.length);
            out.bytes(descriptors);
            position += 5 + length;
        }
        out.bytes(section, position, end - position);
        var body = out.toByteArray();
        var sectionLength = body.length + 4 - 3;
        if (sectionLength > MAX_SECTION_LENGTH) {
            throw new MediaException.LimitExceeded("the edited program map section of " + sectionLength
                    + " bytes exceeds the " + MAX_SECTION_LENGTH + " a section length allows");
        }
        body[1] = (byte) ((body[1] & 0xF0) | sectionLength >> 8);
        body[2] = (byte) sectionLength;
        var crc = Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, body, 0, body.length);
        var result = Arrays.copyOf(body, body.length + 4);
        result[body.length] = (byte) (crc >>> 24);
        result[body.length + 1] = (byte) (crc >>> 16);
        result[body.length + 2] = (byte) (crc >>> 8);
        result[body.length + 3] = (byte) crc;
        return result;
    }

    /**
     * Rebuilds the descriptor loop of one stream: its component name descriptors replaced by one of
     * the new name, and, for an audio stream, the {@code audio_type} of the first language of its
     * language descriptor (one added when the stream has none and the audio type is defined).
     *
     * @param descriptors the old descriptor loop
     * @param described the new tags of the track
     * @param track the track of the stream, which gives its kind and language
     * @return the new descriptor loop
     */
    private static byte[] descriptors(byte[] descriptors, TrackTags described, Track track) {
        var audioType = track.kind() == TrackKind.AUDIO ? TsDescriptors.audioType(described.disposition()) : -1;
        var language = track.language().filter(TsDescriptors::isLanguageCode).orElse(TsDescriptors.UNDETERMINED);
        var out = new ByteWriter(descriptors.length + 64);
        var languageSeen = false;
        var position = 0;
        while (position + 2 <= descriptors.length) {
            var tag = descriptors[position] & 0xFF;
            var size = descriptors[position + 1] & 0xFF;
            if (position + 2 + size > descriptors.length) {
                break;
            }
            if (tag == TsDescriptors.LANGUAGE && size >= 4 && !languageSeen) {
                languageSeen = true;
                var code = new String(descriptors, position + 2, 3, StandardCharsets.ISO_8859_1);
                if (TsDescriptors.isLanguageCode(code)) {
                    language = code;
                }
                var copy = Arrays.copyOfRange(descriptors, position, position + 2 + size);
                if (audioType >= 0) {
                    copy[5] = (byte) audioType;
                }
                out.bytes(copy);
            } else if (tag != TsDescriptors.COMPONENT_NAME) {
                out.bytes(descriptors, position, 2 + size);
            }
            position += 2 + size;
        }
        out.bytes(descriptors, position, descriptors.length - position);
        if (!languageSeen && audioType > 0) {
            out.u8(TsDescriptors.LANGUAGE);
            out.u8(4);
            out.bytes(language.getBytes(StandardCharsets.US_ASCII));
            out.u8(audioType);
        }
        var name = described.name().orElse("");
        if (!name.isEmpty()) {
            out.bytes(TsDescriptors.componentName(language, name));
        }
        return out.toByteArray();
    }

    /**
     * Collects every section carried on the PID of the program map table, with the payload ranges
     * of the packets that carry it.
     *
     * @return the sections, in stream order
     * @throws MediaException.Unsupported if a section shares its packets with another one, does not start at the
     * start of a packet payload, or is cut by the end of the stream or a damaged packet
     */
    private List<Occurrence> scan() {
        var size = source.size();
        var result = new ArrayList<Occurrence>();
        var buffer = new byte[packetSize * BLOCK_PACKETS];
        var collected = new byte[3 + 0xFFF];
        var length = 0;
        var target = -1;
        List<long[]> payloads = null;
        for (var block = first; block + packetSize <= size; block += buffer.length) {
            var count = (int) Math.min(BLOCK_PACKETS, (size - block) / packetSize);
            source.readFully(block, buffer, 0, count * packetSize);
            for (var packet = 0; packet < count; packet++) {
                var base = packet * packetSize + syncOffset;
                if ((buffer[base] & 0xFF) != SYNC) {
                    continue;
                }
                var pid = (buffer[base + 1] & 0x1F) << 8 | (buffer[base + 2] & 0xFF);
                if (pid != pmtPid) {
                    continue;
                }
                var damaged = (buffer[base + 1] & 0x80) != 0;
                var unitStart = (buffer[base + 1] & 0x40) != 0;
                var control = buffer[base + 3] >> 4 & 3;
                var start = base + 4;
                if ((control & 2) != 0) {
                    start += 1 + (buffer[base + 4] & 0xFF);
                }
                var end = base + PACKET;
                if (damaged || (control & 1) == 0 || start >= end) {
                    if (payloads != null) {
                        throw cut();
                    }
                    continue;
                }
                if (unitStart) {
                    if (payloads != null) {
                        throw cut();
                    }
                    if (buffer[start] != 0) {
                        throw shared();
                    }
                    if (start + 1 >= end || (buffer[start + 1] & 0xFF) == 0xFF) {
                        continue;
                    }
                    payloads = new ArrayList<>();
                    length = 0;
                    target = -1;
                } else if (payloads == null) {
                    continue;
                }
                var from = unitStart ? start + 1 : start;
                payloads.add(new long[]{block + start, block + end});
                while (from < end && (target < 0 || length < target)) {
                    collected[length++] = buffer[from++];
                    if (target < 0 && length == 3) {
                        target = 3 + ((collected[1] & 0x0F) << 8 | (collected[2] & 0xFF));
                    }
                }
                if (target >= 0 && length == target) {
                    for (var at = from; at < end; at++) {
                        if ((buffer[at] & 0xFF) != 0xFF) {
                            throw shared();
                        }
                    }
                    checkParity(buffer, base - syncOffset);
                    result.add(new Occurrence(Arrays.copyOf(collected, length), List.copyOf(payloads)));
                    payloads = null;
                } else {
                    checkParity(buffer, base - syncOffset);
                }
            }
        }
        if (payloads != null) {
            throw cut();
        }
        return result;
    }

    /**
     * Rejects a 204-byte packet whose Reed-Solomon parity bytes are not constant filler, as an edit of its payload
     * would leave them wrong.
     *
     * @param buffer the packets
     * @param packet the index of the packet
     * @throws MediaException.Unsupported if the parity bytes hold a code
     */
    private void checkParity(byte[] buffer, int packet) {
        if (packetSize != 204) {
            return;
        }
        for (var at = packet + PACKET + 1; at < packet + packetSize; at++) {
            if (buffer[at] != buffer[packet + PACKET]) {
                throw new MediaException.Unsupported("mpeg-ts", "the program map packets carry Reed-Solomon parity,"
                        + " which an edit would invalidate");
            }
        }
    }

    /**
     * {@return the error of a program map section cut by a damaged packet or the end of the stream}
     */
    private static MediaException cut() {
        return new MediaException.Unsupported("mpeg-ts", "a program map section is cut by a damaged packet or the"
                + " end of the stream, so its copies cannot all be rewritten");
    }

    /**
     * {@return the error of a program map section that shares its packets with another section}
     */
    private static MediaException shared() {
        return new MediaException.Unsupported("mpeg-ts", "a program map section shares its transport packets with"
                + " another section, so it cannot be rewritten in place");
    }

    /**
     * One copy of a section in the stream.
     *
     * @param section the whole section
     * @param payloads the file offsets of the start and end of the payload of each packet carrying it, the first
     * one starting at its {@code pointer_field}
     */
    private record Occurrence(byte[] section, List<long[]> payloads) {
    }
}
