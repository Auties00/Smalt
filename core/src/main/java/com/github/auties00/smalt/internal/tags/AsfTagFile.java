package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.asf.AsfGuid;
import com.github.auties00.smalt.internal.asf.AsfHeader;
import com.github.auties00.smalt.internal.asf.AsfTags;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagWriteOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Edits the tags of ASF files ({@code .asf}, {@code .wma}, {@code .wmv}): the Content Description, Extended Content
 * Description, Metadata and Metadata Library Objects and the Marker Object of the Header Object.
 *
 * <p>The tags, pictures and chapters are read as {@link AsfTags} reads them. A write rebuilds the Header Object: the
 * title, first artist, copyright, first comment and rating go to the Content Description Object; every other entry
 * goes to the Extended Content Description Object under the {@linkplain StandardTag#asfName() ASF name} of its key (a
 * track number, a tempo or a compilation flag stored as a number or Boolean when it is one), except a value too long
 * for a descriptor, which goes to the Metadata Library Object, as does a picture of more than 64 KiB; the other
 * pictures become {@code WM/Picture} descriptors, and the chapters a Marker Object whose markers point at the data
 * packets their times fall in. The records of the metadata objects that describe a single stream are kept, and the
 * byte array and GUID attributes that are not tags are kept unless {@link TagWriteOptions#preserveUnknown()} is off.
 * No other object changes. When the rebuilt header is no longer than the old one, it is padded to the old size with a
 * Padding Object and the edit is in place; otherwise the data and index objects move after the larger header, which
 * gains the configured padding, and the file size of the File Properties Object is updated: ASF locates packets
 * relative to the Data Object, so nothing else refers to their file offsets. Nested chapters cannot be stored.
 */
final class AsfTagFile implements TagFile {
    /**
     * The largest Header Object read.
     */
    private static final long MAX_HEADER = 256L << 20;

    /**
     * The largest value an Extended Content Description descriptor holds.
     */
    private static final int MAX_DESCRIPTOR_VALUE = 0xFFFF;

    /**
     * The size of an object header: GUID and 64-bit size.
     */
    private static final int OBJECT_HEADER = 24;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The original Header Object.
     */
    private final byte[] headerBytes;

    /**
     * The parsed header.
     */
    private final AsfHeader header;

    /**
     * The decoded tags.
     */
    private final MediaTags tags;

    /**
     * Parses the Header Object.
     *
     * @param source the ASF file; not closed
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the Header Object is corrupt
     */
    AsfTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        var prefix = TagIo.read(source, 0, AsfHeader.HEADER_PREFIX, limits);
        if (!AsfGuid.is(prefix, 0, AsfGuid.HEADER)) {
            throw new MediaException.Malformed("not an ASF file");
        }
        var size = ByteArrays.i64le(prefix, 16);
        if (size < AsfHeader.HEADER_PREFIX || size > MAX_HEADER || size > source.size()) {
            throw new MediaException.Malformed("invalid ASF header size " + size);
        }
        this.headerBytes = TagIo.read(source, 0, size, limits);
        this.header = AsfHeader.parse(headerBytes);
        var builder = MediaTags.builder();
        AsfTags.read(header, builder, limits);
        var properties = header.properties();
        var duration = properties.broadcast() ? -1 : properties.playDuration() / 10 - properties.preroll() * 1000;
        builder.chapters(AsfTags.chapters(header, Math.max(-1, duration)));
        this.tags = builder.build();
    }

    @Override
    public MediaTags read() {
        return tags;
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        for (var chapter : newTags.chapters()) {
            if (!chapter.children().isEmpty()) {
                throw new MediaException.Unsupported("asf", "ASF markers cannot nest chapters");
            }
        }
        var children = rebuild(newTags, options);
        var oldSize = headerBytes.length;
        var size = AsfHeader.HEADER_PREFIX;
        for (var child : children) {
            size += child.length;
        }
        var gap = (long) oldSize - size;
        if (gap > 0 && gap < OBJECT_HEADER || gap < 0) {
            // too little room for a Padding Object: the data moves, after the configured padding
            gap = options.padding() >= OBJECT_HEADER ? options.padding() : 0;
        }
        if (gap > 0) {
            children.add(padding((int) gap));
            size += (int) gap;
        }
        var out = new ByteWriter(size);
        out.bytes(AsfGuid.HEADER);
        out.u64le(size);
        out.u32le(children.size());
        out.u8(headerBytes[28]);
        out.u8(headerBytes[29]);
        for (var child : children) {
            out.bytes(child);
        }
        var rebuilt = out.toByteArray();
        var delta = (long) size - oldSize;
        if (delta != 0) {
            patchFileSize(rebuilt, delta);
        }
        return EditPlan.builder().bytes(rebuilt).copy(oldSize, source.size() - oldSize).build();
    }

    /**
     * Builds the top-level children of the new Header Object, without its padding.
     *
     * @param newTags the tags to write
     * @param options how to write them
     * @return the complete child objects, in order
     */
    private List<byte[]> rebuild(MediaTags newTags, TagWriteOptions options) {
        var description = new String[5];
        var descriptors = new ArrayList<byte[]>();
        var descriptorCount = 0;
        var library = new ArrayList<byte[]>();
        for (var entry : newTags.entries()) {
            var key = entry.key();
            var field = contentField(key);
            if (field >= 0 && description[field] == null && entry.value().length() < 0x7FFF) {
                description[field] = entry.value();
                continue;
            }
            var name = StandardTag.fromName(key).map(StandardTag::asfName).orElse(key);
            var value = typedValue(key, entry.value());
            var nameBytes = AsfTags.nulTerminated(name);
            if (value.bytes().length > MAX_DESCRIPTOR_VALUE || nameBytes.length > MAX_DESCRIPTOR_VALUE) {
                library.add(record(0, 0, name, value.type(), value.bytes()));
            } else {
                descriptors.add(descriptor(nameBytes, value.type(), value.bytes()));
                descriptorCount++;
            }
        }
        for (var picture : newTags.pictures()) {
            var value = AsfTags.picture(picture);
            if (value.length > MAX_DESCRIPTOR_VALUE) {
                library.add(record(0, 0, AsfTags.PICTURE, AsfHeader.BYTES, value));
            } else {
                descriptors.add(descriptor(AsfTags.nulTerminated(AsfTags.PICTURE), AsfHeader.BYTES, value));
                descriptorCount++;
            }
        }
        var keptDescriptors = new ArrayList<byte[]>();
        if (options.preserveUnknown()) {
            for (var attribute : header.extendedContent()) {
                if (!AsfTags.isTag(attribute)) {
                    keptDescriptors.add(descriptor(AsfTags.nulTerminated(attribute.name()), attribute.type(),
                            attribute.value()));
                }
            }
        }
        var keptMetadata = new ArrayList<byte[]>();
        for (var attribute : header.metadata()) {
            if (keep(attribute, options)) {
                keptMetadata.add(record(-1, attribute.stream(), attribute.name(), attribute.type(),
                        attribute.value()));
            }
        }
        var keptLibrary = new ArrayList<byte[]>();
        for (var attribute : header.metadataLibrary()) {
            if (keep(attribute, options)) {
                keptLibrary.add(record(attribute.language(), attribute.stream(), attribute.name(), attribute.type(),
                        attribute.value()));
            }
        }
        keptLibrary.addAll(library);
        var children = new ArrayList<byte[]>();
        var extensionWritten = false;
        for (var child : header.children()) {
            if (child.is(AsfGuid.CONTENT_DESCRIPTION) || child.is(AsfGuid.EXTENDED_CONTENT_DESCRIPTION)
                    || child.is(AsfGuid.MARKER) || child.is(AsfGuid.PADDING)) {
                continue;
            }
            if (child.is(AsfGuid.HEADER_EXTENSION) && !extensionWritten) {
                children.add(extension(child, keptMetadata, keptLibrary));
                extensionWritten = true;
                continue;
            }
            children.add(Arrays.copyOfRange(headerBytes, child.start(), child.end()));
        }
        if (!extensionWritten && (!keptMetadata.isEmpty() || !keptLibrary.isEmpty())) {
            children.add(extension(null, keptMetadata, keptLibrary));
        }
        if (anyPresent(description)) {
            children.add(contentDescription(description));
        }
        if (descriptorCount + keptDescriptors.size() > 0) {
            if (descriptorCount + keptDescriptors.size() > 0xFFFF) {
                throw new MediaException.LimitExceeded("more than 65535 ASF descriptors");
            }
            var body = new ByteWriter(4096);
            body.u16le(descriptorCount + keptDescriptors.size());
            descriptors.forEach(body::bytes);
            keptDescriptors.forEach(body::bytes);
            children.add(object(AsfGuid.EXTENDED_CONTENT_DESCRIPTION, body.toByteArray()));
        }
        if (!newTags.chapters().isEmpty()) {
            children.add(markers(newTags.chapters()));
        }
        return children;
    }

    /**
     * Returns whether a metadata record is kept by a write.
     *
     * @param attribute the record
     * @param options how the tags are written
     * @return {@code true} for a record of a stream, and for a whole-file record that is not a tag when unknown
     * items are preserved
     */
    private static boolean keep(AsfHeader.Attribute attribute, TagWriteOptions options) {
        if (attribute.stream() != 0) {
            return true;
        }
        return !AsfTags.isTag(attribute) && options.preserveUnknown();
    }

    /**
     * Returns the Content Description field a key is stored in.
     *
     * @param key the normalized key
     * @return the field index (title, author, copyright, description, rating), or {@code -1}
     */
    private static int contentField(String key) {
        var lower = key.toLowerCase(Locale.ROOT);
        if (lower.equals(StandardTag.TITLE.key())) {
            return 0;
        }
        if (lower.equals(StandardTag.ARTIST.key())) {
            return 1;
        }
        if (lower.equals(StandardTag.COPYRIGHT.key())) {
            return 2;
        }
        if (lower.equals(StandardTag.COMMENT.key())) {
            return 3;
        }
        if (lower.equals(StandardTag.RATING.key())) {
            return 4;
        }
        return -1;
    }

    /**
     * An attribute value with its type.
     *
     * @param type the value type
     * @param bytes the value bytes
     */
    private record Value(int type, byte[] bytes) {
    }

    /**
     * Encodes a tag value: a number for a whole track number or tempo, a Boolean for a compilation flag, text
     * otherwise.
     *
     * @param key the normalized key
     * @param value the text
     * @return the typed value
     */
    private static Value typedValue(String key, String value) {
        var lower = key.toLowerCase(Locale.ROOT);
        if ((lower.equals(StandardTag.TRACK.key()) || lower.equals(StandardTag.BPM.key()))
                && value.matches("[0-9]{1,9}")) {
            var bytes = new byte[4];
            ByteArrays.put32le(bytes, 0, Long.parseLong(value));
            return new Value(AsfHeader.DWORD, bytes);
        }
        if (lower.equals(StandardTag.COMPILATION.key()) && (value.equals("0") || value.equals("1"))) {
            var bytes = new byte[4];
            bytes[0] = (byte) (value.equals("1") ? 1 : 0);
            return new Value(AsfHeader.BOOL, bytes);
        }
        return new Value(AsfHeader.UNICODE, AsfTags.nulTerminated(value));
    }

    /**
     * Encodes an Extended Content Description descriptor.
     *
     * @param name the NUL-terminated UTF-16LE name
     * @param type the value type
     * @param value the value bytes
     * @return the descriptor
     */
    private static byte[] descriptor(byte[] name, int type, byte[] value) {
        var out = new ByteWriter(name.length + value.length + 6);
        out.u16le(name.length);
        out.bytes(name);
        out.u16le(type);
        out.u16le(value.length);
        out.bytes(value);
        return out.toByteArray();
    }

    /**
     * Encodes a Metadata or Metadata Library record.
     *
     * @param language the language index, or {@code -1} for a Metadata Object record
     * @param stream the stream number, 0 for the whole file
     * @param name the record name
     * @param type the value type
     * @param value the value bytes; a Boolean of a Metadata Library record is stored on 2 bytes
     * @return the record
     */
    private static byte[] record(int language, int stream, String name, int type, byte[] value) {
        var nameBytes = AsfTags.nulTerminated(name);
        var stored = type == AsfHeader.BOOL && value.length == 4 ? Arrays.copyOf(value, 2) : value;
        var out = new ByteWriter(nameBytes.length + stored.length + 12);
        out.u16le(Math.max(0, language));
        out.u16le(stream);
        out.u16le(nameBytes.length);
        out.u16le(type);
        out.u32le(stored.length);
        out.bytes(nameBytes);
        out.bytes(stored);
        return out.toByteArray();
    }

    /**
     * Rebuilds the Header Extension Object with new Metadata and Metadata Library Objects.
     *
     * @param original the original object, or {@code null} to create one
     * @param metadata the Metadata Object records
     * @param library the Metadata Library Object records
     * @return the object
     */
    private byte[] extension(AsfHeader.Child original, List<byte[]> metadata, List<byte[]> library) {
        var body = new ByteWriter(4096);
        for (var child : header.extensionChildren()) {
            if (child.is(AsfGuid.METADATA) || child.is(AsfGuid.METADATA_LIBRARY) || child.is(AsfGuid.PADDING)) {
                continue;
            }
            body.bytes(headerBytes, child.start(), child.end() - child.start());
        }
        if (!metadata.isEmpty()) {
            body.bytes(object(AsfGuid.METADATA, records(metadata)));
        }
        if (!library.isEmpty()) {
            body.bytes(object(AsfGuid.METADATA_LIBRARY, records(library)));
        }
        var children = body.toByteArray();
        var out = new ByteWriter(children.length + AsfHeader.EXTENSION_PREFIX);
        if (original != null) {
            out.bytes(headerBytes, original.start() + OBJECT_HEADER, 18);
        } else {
            out.bytes(AsfGuid.RESERVED_1);
            out.u16le(6);
        }
        out.u32le(children.length);
        out.bytes(children);
        return object(AsfGuid.HEADER_EXTENSION, out.toByteArray());
    }

    /**
     * Encodes the body of a Metadata or Metadata Library Object.
     *
     * @param records the encoded records
     * @return the body
     * @throws MediaException.LimitExceeded if there are more than 65535 records
     */
    private static byte[] records(List<byte[]> records) {
        if (records.size() > 0xFFFF) {
            throw new MediaException.LimitExceeded("more than 65535 ASF metadata records");
        }
        var out = new ByteWriter(4096);
        out.u16le(records.size());
        records.forEach(out::bytes);
        return out.toByteArray();
    }

    /**
     * Encodes a Content Description Object.
     *
     * @param fields the title, author, copyright, description and rating, {@code null} for an absent one
     * @return the object
     */
    private static byte[] contentDescription(String[] fields) {
        var encoded = new byte[5][];
        var body = new ByteWriter(256);
        for (var i = 0; i < 5; i++) {
            encoded[i] = fields[i] == null ? new byte[0] : AsfTags.nulTerminated(fields[i]);
            body.u16le(encoded[i].length);
        }
        for (var value : encoded) {
            body.bytes(value);
        }
        return object(AsfGuid.CONTENT_DESCRIPTION, body.toByteArray());
    }

    /**
     * Returns whether any element of an array is present.
     *
     * @param values the field values
     * @return {@code true} when one is not {@code null}
     */
    private static boolean anyPresent(String[] values) {
        for (var value : values) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Encodes the Marker Object of chapters.
     *
     * @param chapters the chapters, without children
     * @return the object
     */
    private byte[] markers(List<Chapter> chapters) {
        var properties = header.properties();
        var preroll = properties.preroll();
        var body = new ByteWriter(4096);
        body.bytes(AsfGuid.RESERVED_4);
        body.u32le(chapters.size());
        body.u16le(0);
        body.u16le(0);
        var locator = new PacketLocator();
        for (var chapter : chapters) {
            var millis = chapter.startMicros() / 1000 + preroll;
            var packet = locator.lastSentBy(millis);
            var description = AsfTags.nulTerminated(chapter.title().orElse(""));
            body.u64le(packet[0]);
            body.u64le(chapter.startMicros() * 10 + preroll * 10_000);
            body.u16le(12 + description.length);
            body.u32le(packet[1]);
            body.u32le(0);
            body.u32le(description.length / 2);
            body.bytes(description);
        }
        return object(AsfGuid.MARKER, body.toByteArray());
    }

    /**
     * Finds the data packets the markers point at.
     */
    private final class PacketLocator {
        /**
         * The offset of the first data packet, or {@code -1} when the file has no readable data object.
         */
        private final long first;

        /**
         * The packet size.
         */
        private final long size;

        /**
         * The number of whole packets.
         */
        private final long count;

        PacketLocator() {
            var properties = header.properties();
            var dataStart = (long) headerBytes.length;
            var valid = properties.minPacketSize() == properties.maxPacketSize() && properties.minPacketSize() >= 16
                    && source.size() >= dataStart + 50;
            if (valid) {
                var dataHeader = TagIo.readAvailable(source, dataStart, 16);
                valid = AsfGuid.is(dataHeader, 0, AsfGuid.DATA);
            }
            this.first = valid ? dataStart + 50 : -1;
            this.size = properties.minPacketSize();
            this.count = valid ? (source.size() - first) / size : 0;
        }

        /**
         * Returns the last packet sent at or before a time.
         *
         * @param millis the time, in milliseconds, preroll included
         * @return the packet's offset from the first packet and its send time; zeros when there is no packet
         */
        long[] lastSentBy(long millis) {
            if (count == 0) {
                return new long[]{0, 0};
            }
            long low = 0;
            var high = count - 1;
            while (low < high) {
                var middle = (low + high + 1) >>> 1;
                if (sendTime(middle) <= millis) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return new long[]{low * size, Math.max(0, sendTime(low))};
        }

        /**
         * Reads the send time of a data packet.
         *
         * @param index the packet index
         * @return the send time in milliseconds, or {@link Long#MAX_VALUE} when the packet header is unreadable
         */
        private long sendTime(long index) {
            var bytes = TagIo.readAvailable(source, first + index * size, 32);
            if (bytes.length < 2) {
                return Long.MAX_VALUE;
            }
            var position = 0;
            var flags = bytes[position++] & 0xFF;
            if ((flags & 0x80) != 0) {
                position += flags & 0x0F;
                flags = position < bytes.length ? bytes[position++] & 0xFF : 0;
            }
            position++;
            position += width(flags >> 5) + width(flags >> 1) + width(flags >> 3);
            return position + 4 <= bytes.length ? ByteArrays.u32le(bytes, position) : Long.MAX_VALUE;
        }
    }

    /**
     * Returns the size of a field of a length type.
     *
     * @param type the two-bit length type in the low bits
     * @return the size in bytes
     */
    private static int width(int type) {
        return switch (type & 3) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
    }

    /**
     * Encodes a Padding Object.
     *
     * @param size the object size, at least 24
     * @return the object
     */
    private static byte[] padding(int size) {
        return object(AsfGuid.PADDING, new byte[size - OBJECT_HEADER]);
    }

    /**
     * Encodes an object.
     *
     * @param guid the object GUID
     * @param body the object body
     * @return the object
     */
    private static byte[] object(byte[] guid, byte[] body) {
        var out = new ByteWriter(body.length + OBJECT_HEADER);
        out.bytes(guid);
        out.u64le(body.length + OBJECT_HEADER);
        out.bytes(body);
        return out.toByteArray();
    }

    /**
     * Adds the growth of the header to the file size of the File Properties Object.
     *
     * @param rebuilt the rebuilt Header Object
     * @param delta the growth
     */
    private static void patchFileSize(byte[] rebuilt, long delta) {
        var position = AsfHeader.HEADER_PREFIX;
        while (rebuilt.length - position >= OBJECT_HEADER) {
            var size = ByteArrays.i64le(rebuilt, position + 16);
            if (AsfGuid.is(rebuilt, position, AsfGuid.FILE_PROPERTIES) && size >= OBJECT_HEADER + 24) {
                var fileSize = ByteArrays.i64le(rebuilt, position + OBJECT_HEADER + 16);
                if (fileSize > 0) {
                    ByteArrays.put64le(rebuilt, position + OBJECT_HEADER + 16, fileSize + delta);
                }
                return;
            }
            if (size < OBJECT_HEADER) {
                return;
            }
            position += (int) size;
        }
    }
}
