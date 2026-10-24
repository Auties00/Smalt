package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.track.TrackDisposition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;

/**
 * Edits the tags, chapters and picture attachments of a Matroska or WebM file.
 *
 * <p>The level-1 elements of the segment are listed up to the end of the file (or up to the first
 * cluster of unknown size). An edit first tries to fit every changed element ({@code Tags},
 * {@code Chapters}, {@code Attachments}) into the space of its old version plus the {@code Void}
 * elements that follow it, or, for an element the file lacks, into a {@code Void} element before
 * the first cluster; the remainder becomes a {@code Void} element and nothing moves. A removed
 * element becomes a {@code Void} element and its {@code SeekHead} entry a {@code Void} child.
 *
 * <p>The track tags are read from the {@code TrackEntry} of each track (its {@code Name} as the
 * title and its flag elements as the dispositions, the track identifier being its
 * {@code TrackNumber}) and from the {@code Tag} elements targeting its {@code TrackUID} alone. A
 * write that changes the tags of a track rebuilds its {@code TrackEntry} with the new name and
 * flags, in the {@code Tracks} element, and replaces the {@code Tag} elements targeting it alone
 * with one holding its other entries.
 *
 * <p>When an element does not fit, the segment is rewritten: a new {@code SeekHead}, the header
 * elements, the new metadata elements and a {@code Void} element of the configured padding, then
 * the clusters copied unchanged and the {@code Cues} re-encoded with the cluster positions moved
 * by the shift.
 */
final class MatroskaTagFile implements TagFile {
    /**
     * The largest number of level-1 elements listed.
     */
    private static final int MAX_ELEMENTS = 1 << 20;

    /**
     * The flag elements of a {@code TrackEntry} and the dispositions they store.
     */
    private static final Map<Integer, TrackDisposition> FLAGS = flags();

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The offset of the segment data.
     */
    private final long segmentData;

    /**
     * The offset of the segment header.
     */
    private final long segmentOffset;

    /**
     * Whether the segment size is unknown.
     */
    private final boolean unknownSize;

    /**
     * The level-1 elements as offset, total size and identifier, in file order.
     */
    private final List<long[]> elements;

    /**
     * The offset where listing stopped: the end of the segment, of the file, or the start of a
     * cluster of unknown size.
     */
    private final long listedEnd;

    /**
     * The bytes of the first {@code Tags}, {@code Chapters} and {@code Attachments} elements,
     * or {@code null} for each the file lacks.
     */
    private final byte[] tagsElement;

    /**
     * The bytes of the first {@code Chapters} element, or {@code null}.
     */
    private final byte[] chaptersElement;

    /**
     * The bytes of the first {@code Attachments} element, or {@code null}.
     */
    private final byte[] attachmentsElement;

    /**
     * The bytes of the first {@code Info} element, or {@code null}.
     */
    private final byte[] infoElement;

    /**
     * The segment title of {@code Info}, or {@code null}.
     */
    private final String infoTitle;

    /**
     * The entries of the global tags of the {@code Tags} element.
     */
    private final List<MediaTags.Entry> tagEntries;

    /**
     * The decoded tags.
     */
    private final MediaTags tags;

    /**
     * The opaque {@code Tag} elements.
     */
    private final List<byte[]> opaqueTags;

    /**
     * The bytes of the first {@code Tracks} element, or {@code null}.
     */
    private final byte[] tracksElement;

    /**
     * The tags of the tracks, by track number.
     */
    private final Map<Long, TrackTags> trackTags;

    /**
     * The {@code TrackUID} of the tracks, by track number.
     */
    private final Map<Long, Long> trackUids;

    /**
     * The opaque {@code AttachedFile} elements.
     */
    private final List<byte[]> opaqueFiles;

    /**
     * Parses the segment structure and the metadata elements.
     *
     * @param source the Matroska file; not closed
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the EbmlIds.EBML header or the segment is missing
     */
    MatroskaTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var size = source.size();
        var header = headerAt(0);
        if (header == null || header.id() != EbmlIds.EBML || header.size() == Ebml.UNKNOWN) {
            throw new MediaException.Malformed("Matroska file without an EbmlIds.EBML header");
        }
        var position = header.length() + header.size();
        var segment = headerAt(position);
        if (segment == null || segment.id() != EbmlIds.SEGMENT) {
            throw new MediaException.Malformed("Matroska file without a segment after its EbmlIds.EBML header");
        }
        this.segmentOffset = position;
        this.segmentData = position + segment.length();
        this.unknownSize = segment.size() == Ebml.UNKNOWN;
        var end = unknownSize ? size : Math.min(size, segmentData + segment.size());
        var list = new ArrayList<long[]>();
        position = segmentData;
        while (position < end) {
            if (list.size() >= MAX_ELEMENTS) {
                throw new MediaException.Malformed("more than " + MAX_ELEMENTS + " Matroska level-1 elements");
            }
            var element = headerAt(position);
            if (element == null || element.size() == Ebml.UNKNOWN) {
                break;
            }
            var total = element.length() + element.size();
            if (total > end - position) {
                break;
            }
            list.add(new long[]{position, total, element.id()});
            position += total;
        }
        this.elements = List.copyOf(list);
        this.listedEnd = position;
        this.tagsElement = readFirst(EbmlIds.TAGS);
        this.chaptersElement = readFirst(EbmlIds.CHAPTERS);
        this.attachmentsElement = readFirst(EbmlIds.ATTACHMENTS);
        this.infoElement = readFirst(EbmlIds.INFO);
        this.infoTitle = infoTitle();
        var entries = MediaTags.builder();
        var opaque = new ArrayList<byte[]>();
        if (tagsElement != null) {
            MatroskaTags.decodeTags(tagsElement, root(tagsElement), entries, opaque);
        }
        this.opaqueTags = List.copyOf(opaque);
        this.tagEntries = entries.build().entries();
        var builder = MediaTags.builder();
        if (infoTitle != null && !infoTitle.isEmpty() && !entries.build().contains(StandardTag.TITLE.key())) {
            builder.add(StandardTag.TITLE.key(), infoTitle);
        }
        for (var entry : tagEntries) {
            builder.add(entry.key(), entry.value());
        }
        if (chaptersElement != null) {
            builder.chapters(MatroskaTags.decodeChapters(chaptersElement, root(chaptersElement), durationMicros()));
        }
        var pictures = new ArrayList<AttachedPicture>();
        var files = new ArrayList<byte[]>();
        if (attachmentsElement != null) {
            MatroskaTags.decodeAttachments(attachmentsElement, root(attachmentsElement), pictures, files);
        }
        this.opaqueFiles = List.copyOf(files);
        this.tracksElement = readFirst(EbmlIds.TRACKS);
        this.trackTags = new LinkedHashMap<>();
        this.trackUids = new HashMap<>();
        if (tracksElement != null) {
            readTracks();
        }
        for (var track : trackTags.values()) {
            builder.track(track);
        }
        this.tags = builder.pictures(pictures).build();
    }

    /**
     * Reads the tags of every track: the name and flags of its {@code TrackEntry} and the entries of the
     * {@code Tag} elements targeting it alone.
     */
    private void readTracks() {
        for (var entry : Ebml.children(tracksElement, root(tracksElement).data(), tracksElement.length)) {
            if (entry.id() != EbmlIds.TRACK_ENTRY) {
                continue;
            }
            var fields = Ebml.children(tracksElement, entry.data(), entry.end());
            var number = Ebml.find(fields, EbmlIds.TRACK_NUMBER);
            if (number == null || Ebml.unsigned(tracksElement, number) <= 0
                    || Ebml.unsigned(tracksElement, number) > Integer.MAX_VALUE) {
                continue;
            }
            var trackNumber = Ebml.unsigned(tracksElement, number);
            var uidElement = Ebml.find(fields, EbmlIds.TRACK_UID);
            var uid = uidElement != null ? Ebml.unsigned(tracksElement, uidElement) : 0;
            var disposition = EnumSet.of(TrackDisposition.DEFAULT);
            String name = null;
            for (var field : fields) {
                var value = FLAGS.get(field.id());
                if (value != null) {
                    if (Ebml.unsigned(tracksElement, field) != 0) {
                        disposition.add(value);
                    } else {
                        disposition.remove(value);
                    }
                } else if (field.id() == EbmlIds.NAME) {
                    name = Ebml.string(tracksElement, field);
                }
            }
            var trackTagsBuilder = MediaTags.builder();
            if (name != null && !name.isEmpty()) {
                trackTagsBuilder.add(StandardTag.TITLE.key(), name);
            }
            for (var tag : targeting(uid)) {
                for (var item : MatroskaTags.decodeSimpleTags(tag, root(tag))) {
                    if (name == null || name.isEmpty() || !item.key().equals(StandardTag.TITLE.key())) {
                        trackTagsBuilder.add(item.key(), item.value());
                    }
                }
            }
            if (!trackTags.containsKey(trackNumber)) {
                trackTags.put(trackNumber, new TrackTags((int) trackNumber, trackTagsBuilder.build(), disposition));
                trackUids.put(trackNumber, uid);
            }
        }
    }

    /**
     * Returns the opaque {@code Tag} elements that target one track alone.
     *
     * @param uid the {@code TrackUID} of the track, 0 for none
     * @return the complete elements
     */
    private List<byte[]> targeting(long uid) {
        var result = new ArrayList<byte[]>();
        if (uid == 0) {
            return result;
        }
        for (var tag : opaqueTags) {
            var children = Ebml.children(tag, root(tag).data(), tag.length);
            var targets = MatroskaTags.targets(tag, Ebml.find(children, EbmlIds.TARGETS));
            if (targets.singleTrack() && targets.trackUid() == uid) {
                result.add(tag);
            }
        }
        return result;
    }

    /**
     * Rebuilds the {@code Tracks} element with the names and flags of the changed tracks, dropping
     * its {@code CRC-32} child.
     *
     * @param changed the new tags of the changed tracks, by track number
     * @return the element
     */
    private byte[] tracksWith(Map<Long, TrackTags> changed) {
        var out = new ByteWriter(tracksElement.length + 256);
        for (var entry : Ebml.children(tracksElement, root(tracksElement).data(), tracksElement.length)) {
            if (entry.id() == EbmlIds.CRC32) {
                continue;
            }
            var fields = entry.id() == EbmlIds.TRACK_ENTRY ? Ebml.children(tracksElement, entry.data(), entry.end())
                    : List.<Ebml.Element>of();
            var number = Ebml.find(fields, EbmlIds.TRACK_NUMBER);
            var track = number != null ? changed.get(Ebml.unsigned(tracksElement, number)) : null;
            if (track == null) {
                out.bytes(tracksElement, entry.offset(), entry.end() - entry.offset());
                continue;
            }
            var body = new ByteWriter(entry.size() + 64);
            for (var field : fields) {
                if (field.id() != EbmlIds.NAME && field.id() != EbmlIds.CRC32 && !FLAGS.containsKey(field.id())) {
                    body.bytes(tracksElement, field.offset(), field.end() - field.offset());
                }
            }
            for (var flag : FLAGS.entrySet()) {
                var set = track.has(flag.getValue());
                if (set || flag.getValue() == TrackDisposition.DEFAULT) {
                    Ebml.unsigned(body, flag.getKey(), set ? 1 : 0);
                }
            }
            var name = track.name().orElse(null);
            if (name != null && !name.isEmpty()) {
                Ebml.string(body, EbmlIds.NAME, name);
            }
            Ebml.element(out, EbmlIds.TRACK_ENTRY, body.toByteArray());
        }
        return Ebml.element(EbmlIds.TRACKS, out.toByteArray());
    }

    /**
     * Builds the table of flag elements.
     *
     * @return the dispositions by element identifier, in writing order
     */
    private static Map<Integer, TrackDisposition> flags() {
        var flags = new LinkedHashMap<Integer, TrackDisposition>();
        flags.put(EbmlIds.FLAG_DEFAULT, TrackDisposition.DEFAULT);
        flags.put(EbmlIds.FLAG_FORCED, TrackDisposition.FORCED);
        flags.put(EbmlIds.FLAG_HEARING_IMPAIRED, TrackDisposition.HEARING_IMPAIRED);
        flags.put(EbmlIds.FLAG_VISUAL_IMPAIRED, TrackDisposition.VISUAL_IMPAIRED);
        flags.put(EbmlIds.FLAG_TEXT_DESCRIPTIONS, TrackDisposition.DESCRIPTIONS);
        flags.put(EbmlIds.FLAG_ORIGINAL, TrackDisposition.ORIGINAL);
        flags.put(EbmlIds.FLAG_COMMENTARY, TrackDisposition.COMMENTARY);
        return Collections.unmodifiableMap(flags);
    }

    /**
     * Decodes the element header at an offset of the file.
     *
     * @param position the offset
     * @return the header, or {@code null} when malformed
     */
    private Ebml.Header headerAt(long position) {
        var bytes = TagIo.readAvailable(source, position, 12);
        return Ebml.header(bytes, 0, bytes.length);
    }

    /**
     * Returns the element that spans a whole array.
     *
     * @param bytes the element bytes
     * @return the element
     */
    private static Ebml.Element root(byte[] bytes) {
        var header = Ebml.header(bytes, 0, bytes.length);
        return new Ebml.Element(header.id(), 0, header.length(), bytes.length - header.length());
    }

    /**
     * Reads the first level-1 element with an identifier.
     *
     * @param id the identifier
     * @return the element bytes, or {@code null} when absent
     */
    private byte[] readFirst(int id) {
        for (var element : elements) {
            if ((int) element[2] == id) {
                return TagIo.read(source, element[0], element[1], limits);
            }
        }
        return null;
    }

    /**
     * {@return the segment duration from {@code Info}, in microseconds, or {@code -1} when unknown}
     */
    private long durationMicros() {
        var info = readFirst(EbmlIds.INFO);
        if (info == null) {
            return -1;
        }
        var children = Ebml.children(info, root(info).data(), info.length);
        var scale = Ebml.find(children, EbmlIds.TIMESTAMP_SCALE);
        var duration = Ebml.find(children, EbmlIds.DURATION);
        if (duration == null) {
            return -1;
        }
        var nanos = Ebml.floating(info, duration) * (scale != null ? Ebml.unsigned(info, scale) : 1_000_000);
        return Double.isFinite(nanos) && nanos >= 0 ? Math.round(nanos / 1000) : -1;
    }

    /**
     * {@return the segment title recorded in {@code Info}, or {@code null} when absent}
     */
    private String infoTitle() {
        if (infoElement == null) {
            return null;
        }
        var title = Ebml.find(Ebml.children(infoElement, root(infoElement).data(), infoElement.length), EbmlIds.TITLE);
        return title != null ? Ebml.string(infoElement, title) : null;
    }

    /**
     * Rebuilds {@code Info} with another title, dropping its {@code CRC-32} child.
     *
     * @param title the title, or {@code null} to remove it
     * @return the element
     */
    private byte[] infoWithTitle(String title) {
        var out = new ByteWriter(infoElement.length + 64);
        for (var child : Ebml.children(infoElement, root(infoElement).data(), infoElement.length)) {
            if (child.id() != EbmlIds.TITLE && child.id() != EbmlIds.CRC32 && child.id() != EbmlIds.VOID) {
                out.bytes(infoElement, child.offset(), child.end() - child.offset());
            }
        }
        if (title != null) {
            Ebml.string(out, EbmlIds.TITLE, title);
        }
        return Ebml.element(EbmlIds.INFO, out.toByteArray());
    }

    @Override
    public MediaTags read() {
        return tags;
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        var keepOpaque = options.preserveUnknown();
        var replacements = new ArrayList<Replacement>();
        var title = newTags.get(StandardTag.TITLE).orElse(null);
        var tagsPart = newTags;
        if (infoElement != null) {
            tagsPart = newTags.toBuilder().remove(StandardTag.TITLE).build();
            if (!java.util.Objects.equals(title, infoTitle)) {
                replacements.add(new Replacement(EbmlIds.INFO, infoWithTitle(title)));
            }
        }
        var changed = new LinkedHashMap<Long, TrackTags>();
        for (var track : newTags.tracks()) {
            var number = (long) track.trackId();
            if (trackTags.containsKey(number) && !trackTags.get(number).equals(track)) {
                changed.put(number, track);
            }
        }
        if (!changed.isEmpty()) {
            replacements.add(new Replacement(EbmlIds.TRACKS, tracksWith(changed)));
        }
        if (!tagsPart.entries().equals(tagEntries) || (!keepOpaque && !opaqueTags.isEmpty()) || !changed.isEmpty()) {
            var kept = new ArrayList<byte[]>();
            if (keepOpaque) {
                kept.addAll(opaqueTags);
            }
            for (var track : changed.entrySet()) {
                var uid = trackUids.get(track.getKey());
                kept.removeAll(targeting(uid));
                var entries = track.getValue().tags().toBuilder().remove(StandardTag.TITLE).build().entries();
                if (uid != 0 && !entries.isEmpty()) {
                    kept.add(MatroskaTags.encodeTrackTag(uid, entries));
                }
            }
            replacements.add(new Replacement(EbmlIds.TAGS, MatroskaTags.encodeTags(tagsPart, kept)));
        }
        if (!newTags.chapters().equals(tags.chapters())) {
            replacements.add(new Replacement(EbmlIds.CHAPTERS, MatroskaTags.encodeChapters(newTags.chapters())));
        }
        if (!newTags.pictures().equals(tags.pictures()) || (!keepOpaque && !opaqueFiles.isEmpty())) {
            replacements.add(new Replacement(EbmlIds.ATTACHMENTS,
                    MatroskaTags.encodeAttachments(newTags.pictures(), keepOpaque ? opaqueFiles : List.of())));
        }
        if (replacements.isEmpty()) {
            return EditPlan.builder().copy(0, source.size()).build();
        }
        // TODO: when an element does not fit, void it and append the new one at the segment end with an updated
        // SeekHead entry, as mkvpropedit does, instead of rewriting the whole segment.
        var inPlace = planInPlace(replacements);
        return inPlace != null ? inPlace : planRewrite(replacements, options);
    }

    /**
     * Plans an edit that moves nothing, writing each replacement into the space of its old
     * element and the following {@code Void} elements, or into a {@code Void} element before the
     * first cluster.
     *
     * @param replacements the elements to replace
     * @return the plan, or {@code null} when an element does not fit
     */
    private EditPlan planInPlace(List<Replacement> replacements) {
        var writes = new ArrayList<long[]>();
        var contents = new ArrayList<byte[]>();
        var used = new boolean[elements.size()];
        for (var replacement : replacements) {
            var index = indexOf(replacement.id());
            if (index < 0) {
                if (replacement.bytes().length == 0) {
                    continue;
                }
                index = voidBeforeClusters(replacement.bytes().length, used);
                if (index < 0) {
                    return null;
                }
                used[index] = true;
                writes.add(new long[]{elements.get(index)[0], elements.get(index)[1]});
                contents.add(fill(replacement.bytes(), elements.get(index)[1]));
                continue;
            }
            var start = elements.get(index)[0];
            var slot = elements.get(index)[1];
            used[index] = true;
            var next = index + 1;
            while (slot < replacement.bytes().length + 2L && slot != replacement.bytes().length
                    && next < elements.size() && (int) elements.get(next)[2] == EbmlIds.VOID && !used[next]) {
                slot += elements.get(next)[1];
                used[next] = true;
                next++;
            }
            if (replacement.bytes().length != slot && replacement.bytes().length + 2L > slot) {
                return null;
            }
            writes.add(new long[]{start, slot});
            contents.add(fill(replacement.bytes(), slot));
            if (replacement.bytes().length == 0) {
                var seek = seekEntry(replacement.id());
                if (seek != null) {
                    writes.add(new long[]{seek[0], seek[1]});
                    contents.add(Ebml.voidElement(seek[1]));
                }
            }
        }
        var order = new ArrayList<Integer>();
        for (var i = 0; i < writes.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Long.compare(writes.get(a)[0], writes.get(b)[0]));
        var plan = EditPlan.builder();
        var position = 0L;
        for (var index : order) {
            var write = writes.get(index);
            if (write[0] < position) {
                return null;
            }
            plan.copy(position, write[0] - position);
            plan.bytes(contents.get(index));
            position = write[0] + write[1];
        }
        plan.copy(position, source.size() - position);
        return plan.build();
    }

    /**
     * Pads an element with a {@code Void} element to fill a slot.
     *
     * @param element the element bytes, possibly empty
     * @param slot the size of the slot, equal to the element or at least two bytes larger
     * @return the filled slot
     */
    private static byte[] fill(byte[] element, long slot) {
        if (element.length == slot) {
            return element;
        }
        var result = Arrays.copyOf(element, (int) slot);
        var filler = Ebml.voidElement(slot - element.length);
        System.arraycopy(filler, 0, result, element.length, filler.length);
        return result;
    }

    /**
     * Returns the index of the first level-1 element with an identifier.
     *
     * @param id the identifier
     * @return the index, or {@code -1}
     */
    private int indexOf(int id) {
        for (var i = 0; i < elements.size(); i++) {
            if ((int) elements.get(i)[2] == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds an unused {@code Void} element before the first cluster that can hold an element.
     *
     * @param length the size of the element
     * @param used the elements already claimed
     * @return the index of the {@code Void} element, or {@code -1}
     */
    private int voidBeforeClusters(int length, boolean[] used) {
        for (var i = 0; i < elements.size(); i++) {
            var id = (int) elements.get(i)[2];
            if (id == EbmlIds.CLUSTER) {
                return -1;
            }
            var size = elements.get(i)[1];
            if (id == EbmlIds.VOID && !used[i] && (size == length || size >= length + 2L)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Locates the {@code Seek} entry of the first {@code SeekHead} that points at an element.
     *
     * @param id the identifier of the element
     * @return the file offset and size of the {@code Seek} element, or {@code null}
     */
    private long[] seekEntry(int id) {
        var index = indexOf(EbmlIds.SEEK_HEAD);
        if (index < 0) {
            return null;
        }
        var head = TagIo.read(source, elements.get(index)[0], elements.get(index)[1], limits);
        for (var seek : Ebml.children(head, root(head).data(), head.length)) {
            if (seek.id() != EbmlIds.SEEK) {
                continue;
            }
            var target = Ebml.find(Ebml.children(head, seek.data(), seek.end()), EbmlIds.SEEK_ID);
            if (target != null && Ebml.unsigned(head, target) == (id & 0xFFFFFFFFL)) {
                return new long[]{elements.get(index)[0] + seek.offset(), seek.end() - seek.offset()};
            }
        }
        return null;
    }

    /**
     * Plans a rewrite of the segment with the new metadata elements before the clusters.
     *
     * @param replacements the elements to replace
     * @param options the write options
     * @return the plan
     * @throws MediaException.LimitExceeded if the cues exceed the limits
     */
    private EditPlan planRewrite(List<Replacement> replacements, TagWriteOptions options) {
        var firstCluster = indexOf(EbmlIds.CLUSTER);
        var headerEnd = firstCluster >= 0 ? firstCluster : elements.size();
        var replaced = new HashMap<Integer, byte[]>();
        for (var replacement : replacements) {
            replaced.put(replacement.id(), replacement.bytes());
        }
        var head = new ArrayList<long[]>();
        byte[] oldCues = null;
        var cuesBeforeClusters = false;
        for (var i = 0; i < headerEnd; i++) {
            var element = elements.get(i);
            var id = (int) element[2];
            if (id == EbmlIds.CUES && oldCues == null) {
                oldCues = TagIo.read(source, element[0], element[1], limits);
                cuesBeforeClusters = true;
            }
            if (id == EbmlIds.SEEK_HEAD || id == EbmlIds.VOID || id == EbmlIds.CUES || isMetadata(id)) {
                continue;
            }
            head.add(element);
        }
        var metadata = new ArrayList<byte[]>();
        for (var id : new int[]{EbmlIds.CHAPTERS, EbmlIds.ATTACHMENTS, EbmlIds.TAGS}) {
            var bytes = replaced.containsKey(id) ? replaced.get(id) : readFirst(id);
            if (bytes != null && bytes.length > 0) {
                metadata.add(bytes);
            }
        }
        var tail = new ArrayList<long[]>();
        for (var i = headerEnd; i < elements.size(); i++) {
            var element = elements.get(i);
            var id = (int) element[2];
            if (id == EbmlIds.SEEK_HEAD || isMetadata(id) || (id == EbmlIds.CUES && oldCues != null)) {
                continue;
            }
            if (id == EbmlIds.CUES) {
                oldCues = TagIo.read(source, element[0], element[1], limits);
            }
            tail.add(element);
        }
        var cuesSize = oldCues != null ? reencodeCues(oldCues, position -> position).length : 0;
        var seekCount = metadata.size() + (oldCues != null ? 1 : 0);
        for (var element : head) {
            if ((int) element[2] == EbmlIds.INFO || (int) element[2] == EbmlIds.TRACKS) {
                seekCount++;
            }
        }
        var seekHeadTotal = seekCount > 0 ? 12 + 21 * seekCount : 0;
        var padding = options.padding() >= 2 ? options.padding() : 0;
        var segmentEnd = unknownSize ? source.size() : Math.min(source.size(), segmentEnd());
        var trailing = Math.max(0, segmentEnd - listedEnd);
        var layout = new ArrayList<long[]>();
        var positions = new HashMap<Integer, Long>();
        var cursor = (long) seekHeadTotal;
        for (var element : head) {
            positions.putIfAbsent((int) element[2], cursor);
            var bytes = headReplacement(replaced, (int) element[2]);
            if (bytes != null) {
                cursor += bytes.length;
                continue;
            }
            layout.add(new long[]{element[0], cursor, element[1]});
            cursor += element[1];
        }
        for (var bytes : metadata) {
            positions.put(Ebml.header(bytes, 0, bytes.length).id(), cursor);
            cursor += bytes.length;
        }
        if (cuesBeforeClusters) {
            positions.put(EbmlIds.CUES, cursor);
            cursor += cuesSize;
        }
        cursor += padding;
        for (var element : tail) {
            if ((int) element[2] == EbmlIds.CUES) {
                positions.put(EbmlIds.CUES, cursor);
                cursor += cuesSize;
                continue;
            }
            layout.add(new long[]{element[0], cursor, element[1]});
            cursor += element[1];
        }
        if (trailing > 0) {
            layout.add(new long[]{listedEnd, cursor, trailing});
            cursor += trailing;
        }
        var segmentSize = cursor;
        var relocations = layout.toArray(long[][]::new);
        LongUnaryOperator relocate = old -> {
            var absolute = segmentData + old;
            for (var entry : relocations) {
                if (absolute >= entry[0] && absolute < entry[0] + entry[2]) {
                    return entry[1] + (absolute - entry[0]);
                }
            }
            return old;
        };
        var plan = EditPlan.builder();
        plan.copy(0, segmentOffset);
        var segmentHeader = new ByteWriter(12);
        Ebml.id(segmentHeader, EbmlIds.SEGMENT);
        Ebml.size(segmentHeader, unknownSize ? Ebml.UNKNOWN : segmentSize, 8);
        plan.bytes(segmentHeader.toByteArray());
        if (seekHeadTotal > 0) {
            plan.bytes(seekHead(positions, seekHeadTotal));
        }
        for (var element : head) {
            var bytes = headReplacement(replaced, (int) element[2]);
            if (bytes != null) {
                plan.bytes(bytes);
            } else {
                plan.copy(element[0], element[1]);
            }
        }
        for (var bytes : metadata) {
            plan.bytes(bytes);
        }
        if (cuesBeforeClusters) {
            plan.bytes(reencodeCues(oldCues, relocate));
        }
        if (padding > 0) {
            plan.bytes(Ebml.voidElement(padding));
        }
        for (var element : tail) {
            if ((int) element[2] == EbmlIds.CUES) {
                plan.bytes(reencodeCues(oldCues, relocate));
            } else {
                plan.copy(element[0], element[1]);
            }
        }
        if (trailing > 0) {
            plan.copy(listedEnd, trailing);
        }
        if (segmentEnd < source.size()) {
            plan.copy(segmentEnd, source.size() - segmentEnd);
        }
        return plan.build();
    }

    /**
     * Returns the new encoding of a header element a rewrite replaces.
     *
     * @param replaced the new elements by identifier
     * @param id the identifier of the header element
     * @return the new {@code Info} or {@code Tracks} element, or {@code null} to copy the old one
     */
    private static byte[] headReplacement(Map<Integer, byte[]> replaced, int id) {
        return id == EbmlIds.INFO || id == EbmlIds.TRACKS ? replaced.get(id) : null;
    }

    /**
     * Returns whether a level-1 identifier is one of the metadata elements this editor writes.
     *
     * @param id the identifier
     * @return {@code true} for {@code Tags}, {@code Chapters} and {@code Attachments}
     */
    private static boolean isMetadata(int id) {
        return id == EbmlIds.TAGS || id == EbmlIds.CHAPTERS || id == EbmlIds.ATTACHMENTS;
    }

    /**
     * {@return the offset of the end of a segment of known size}
     */
    private long segmentEnd() {
        var bytes = TagIo.readAvailable(source, segmentOffset, 12);
        return segmentData + Ebml.header(bytes, 0, bytes.length).size();
    }

    /**
     * Builds a {@code SeekHead} indexing the header, metadata and cues elements, with eight-byte
     * positions, padded with a {@code Void} element to a fixed size.
     *
     * @param positions the segment-relative position of each indexed element
     * @param total the size to fill
     * @return the bytes
     */
    private static byte[] seekHead(Map<Integer, Long> positions, int total) {
        var seeks = new ByteWriter(total);
        for (var id : new int[]{EbmlIds.INFO, EbmlIds.TRACKS, EbmlIds.CHAPTERS, EbmlIds.ATTACHMENTS, EbmlIds.TAGS, EbmlIds.CUES}) {
            var at = positions.get(id);
            if (at == null) {
                continue;
            }
            var seek = new ByteWriter(21);
            Ebml.unsigned(seek, EbmlIds.SEEK_ID, id & 0xFFFFFFFFL, 4);
            Ebml.unsigned(seek, EbmlIds.SEEK_POSITION, at, 8);
            Ebml.element(seeks, EbmlIds.SEEK, seek.toByteArray());
        }
        var head = new ByteWriter(total);
        Ebml.id(head, EbmlIds.SEEK_HEAD);
        Ebml.size(head, seeks.size(), 8);
        head.bytes(seeks.toByteArray());
        if (head.size() < total) {
            head.bytes(Ebml.voidElement(total - head.size()));
        }
        return head.toByteArray();
    }

    /**
     * Re-encodes a {@code Cues} element with relocated cluster positions written on eight bytes,
     * so its size does not depend on the positions.
     *
     * @param cues the old element
     * @param relocate maps an old segment-relative position to the new one
     * @return the new element
     */
    private static byte[] reencodeCues(byte[] cues, LongUnaryOperator relocate) {
        var out = new ByteWriter(cues.length + 1024);
        for (var point : Ebml.children(cues, root(cues).data(), cues.length)) {
            if (point.id() != EbmlIds.CUE_POINT) {
                if (point.id() != EbmlIds.CRC32 && point.id() != EbmlIds.VOID) {
                    out.bytes(cues, point.offset(), point.end() - point.offset());
                }
                continue;
            }
            var pointOut = new ByteWriter(point.size() + 16);
            for (var child : Ebml.children(cues, point.data(), point.end())) {
                if (child.id() != EbmlIds.CUE_TRACK_POSITIONS) {
                    pointOut.bytes(cues, child.offset(), child.end() - child.offset());
                    continue;
                }
                var positions = new ByteWriter(child.size() + 16);
                for (var field : Ebml.children(cues, child.data(), child.end())) {
                    if (field.id() == EbmlIds.CUE_CLUSTER_POSITION) {
                        Ebml.unsigned(positions, EbmlIds.CUE_CLUSTER_POSITION, relocate.applyAsLong(Ebml.unsigned(cues, field)), 8);
                    } else {
                        positions.bytes(cues, field.offset(), field.end() - field.offset());
                    }
                }
                Ebml.element(pointOut, EbmlIds.CUE_TRACK_POSITIONS, positions.toByteArray());
            }
            Ebml.element(out, EbmlIds.CUE_POINT, pointOut.toByteArray());
        }
        var body = out.toByteArray();
        var result = new ByteWriter(body.length + 12);
        Ebml.id(result, EbmlIds.CUES);
        Ebml.size(result, body.length, 8);
        result.bytes(body);
        return result.toByteArray();
    }

    /**
     * Pairs a level-1 identifier with its new encoding.
     *
     * @param id the identifier
     * @param bytes the new element, empty to remove it
     */
    private record Replacement(int id, byte[] bytes) {
    }
}
