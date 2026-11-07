package com.github.auties00.smalt.internal.asf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parsed Header Object of an Advanced Systems Format (ASF) file: the file and stream properties, the header
 * extension, the descriptive objects (content description, extended content description, metadata, metadata library,
 * markers, script commands, codec list) and where every object lies, for the demuxer and the tag editor.
 *
 * <p>Objects of unknown type are skipped; an object whose size runs past its parent ends the parse of that parent.
 * Instances are not thread-safe.
 */
public final class AsfHeader {
    /**
     * The size of the fixed part of the Header Object: GUID, size, object count and two reserved bytes.
     */
    public static final int HEADER_PREFIX = 30;

    /**
     * The size of the fixed part of the Header Extension Object body: reserved GUID, reserved field and data size.
     */
    public static final int EXTENSION_PREFIX = 22;

    /**
     * The most streams an ASF file numbers (stream numbers 1 to 127).
     */
    private static final int MAX_STREAMS = 127;

    /**
     * The most entries read from one descriptive list.
     */
    private static final int MAX_ENTRIES = 1 << 16;

    /**
     * A NUL-terminated UTF-16LE string value, the first of the value types of the descriptors and metadata records.
     */
    public static final int UNICODE = 0;
    /**
     * A byte array value.
     */
    public static final int BYTES = 1;
    /**
     * A Boolean value: 4 bytes in the Extended Content Description Object, 2 bytes in the metadata objects.
     */
    public static final int BOOL = 2;
    /**
     * A 32-bit unsigned value.
     */
    public static final int DWORD = 3;
    /**
     * A 64-bit unsigned value.
     */
    public static final int QWORD = 4;
    /**
     * A 16-bit unsigned value.
     */
    public static final int WORD = 5;
    /**
     * A GUID value, in the Metadata Library Object only.
     */
    public static final int GUID = 6;

    /**
     * The fields of the File Properties Object.
     *
     * @param fileSize the size of the whole file, in bytes, or 0 when unknown
     * @param creationDate the creation date, in 100-nanosecond units since 1601-01-01 UTC
     * @param packetCount the number of data packets, or 0 when unknown
     * @param playDuration the play duration, in 100-nanosecond units, preroll included
     * @param sendDuration the send duration, in 100-nanosecond units
     * @param preroll the time to buffer before playing, in milliseconds, which every presentation time includes
     * @param flags the flags: bit 0 broadcast, bit 1 seekable
     * @param minPacketSize the smallest data packet size, in bytes
     * @param maxPacketSize the largest data packet size, in bytes
     * @param maxBitrate the largest instantaneous bit rate, in bits per second
     */
    public record FileProperties(long fileSize, long creationDate, long packetCount, long playDuration,
                                 long sendDuration, long preroll, int flags, long minPacketSize, long maxPacketSize,
                                 long maxBitrate) {
        /**
         * {@return whether the broadcast flag is set, so the sizes, counts and durations are not valid}
         */
        public boolean broadcast() {
            return (flags & 1) != 0;
        }
    }

    /**
     * A stream: its Stream Properties Object and, when present, its Extended Stream Properties Object.
     */
    public static final class Stream {
        /**
         * The stream number, from 1 to 127.
         */
        public int number;
        /**
         * The stream type GUID.
         */
        public byte[] type;
        /**
         * The type-specific data: a {@code WAVEFORMATEX} for audio, the video format for video.
         */
        public byte[] typeData;
        /**
         * The error correction type GUID.
         */
        public byte[] errorCorrection;
        /**
         * The error correction data.
         */
        public byte[] errorCorrectionData;
        /**
         * The presentation time offset of the stream, in 100-nanosecond units.
         */
        public long timeOffset;
        /**
         * Whether the stream's content is encrypted.
         */
        public boolean encrypted;
        /**
         * Whether an Extended Stream Properties Object describes the stream.
         */
        public boolean extended;
        /**
         * The average time per frame of the extended properties, in 100-nanosecond units, or 0.
         */
        public long averageTimePerFrame;
        /**
         * The index of the stream's language in the Language List Object, or {@code -1}.
         */
        public int languageIndex = -1;
        /**
         * The stream names of the extended properties.
         */
        public final List<String> names = new ArrayList<>();
        /**
         * The GUIDs of the payload extension systems, in the order of the replicated data.
         */
        public final List<byte[]> extensionSystems = new ArrayList<>();
        /**
         * The size of each payload extension system's data, {@code 0xFFFF} for a variable size.
         */
        public final List<Integer> extensionSizes = new ArrayList<>();

        /**
         * {@return whether the stream is an audio stream}
         */
        public boolean isAudio() {
            return AsfGuid.is(type, 0, AsfGuid.AUDIO_MEDIA);
        }

        /**
         * {@return whether the stream is a video stream}
         */
        public boolean isVideo() {
            return AsfGuid.is(type, 0, AsfGuid.VIDEO_MEDIA);
        }
    }

    /**
     * A descriptor or metadata record.
     *
     * @param name the attribute name, such as {@code WM/AlbumTitle}
     * @param type the value type, one of {@link #UNICODE} to {@link #GUID}
     * @param value the value bytes as stored
     * @param stream the stream it describes, 0 for the whole file
     * @param language the index of its language in the Language List Object, 0 for the default
     */
    public record Attribute(String name, int type, byte[] value, int stream, int language) {
    }

    /**
     * A marker of the Marker Object.
     *
     * @param offset the byte offset of the marker's packet, from the first data packet
     * @param time the presentation time, in 100-nanosecond units, preroll included
     * @param name the description
     */
    public record Marker(long offset, long time, String name) {
    }

    /**
     * A command of the Script Command Object.
     *
     * @param millis the presentation time, in milliseconds, preroll included
     * @param type the command type, such as {@code URL} or {@code TEXT}
     * @param command the command parameter
     */
    public record ScriptCommand(long millis, String type, String command) {
    }

    /**
     * A child object: its type and its extent within the bytes the parse was given.
     *
     * @param guid the object GUID
     * @param start the index of the object's first byte
     * @param end the index after its last byte
     */
    public record Child(byte[] guid, int start, int end) {
        /**
         * Returns whether the object is of a type.
         *
         * @param type the GUID of the type
         * @return {@code true} for an object of that type
         */
        public boolean is(byte[] type) {
            return AsfGuid.is(guid, 0, type);
        }
    }

    /**
     * The File Properties Object, or {@code null}.
     */
    private FileProperties properties;

    /**
     * The streams by number.
     */
    private final Map<Integer, Stream> streams = new TreeMap<>();

    /**
     * The Content Description fields, as {@link #UNICODE} attributes named {@code Title}, {@code Author},
     * {@code Copyright}, {@code Description} and {@code Rating}.
     */
    private final List<Attribute> contentDescription = new ArrayList<>();

    /**
     * The Extended Content Description descriptors.
     */
    private final List<Attribute> extendedContent = new ArrayList<>();

    /**
     * The Metadata Object records.
     */
    private final List<Attribute> metadata = new ArrayList<>();

    /**
     * The Metadata Library Object records.
     */
    private final List<Attribute> metadataLibrary = new ArrayList<>();

    /**
     * The language tags of the Language List Object.
     */
    private final List<String> languages = new ArrayList<>();

    /**
     * The markers.
     */
    private final List<Marker> markers = new ArrayList<>();

    /**
     * The script commands.
     */
    private final List<ScriptCommand> scriptCommands = new ArrayList<>();

    /**
     * The codec names of the Codec List Object, in order.
     */
    private final List<String> codecNames = new ArrayList<>();

    /**
     * The top-level children of the Header Object.
     */
    private final List<Child> children = new ArrayList<>();

    /**
     * The children of the Header Extension Object.
     */
    private final List<Child> extensionChildren = new ArrayList<>();

    /**
     * The Header Extension Object, or {@code null}.
     */
    private Child extension;

    /**
     * Whether a content encryption object protects the file.
     */
    private boolean protectedContent;

    private AsfHeader() {
    }

    /**
     * Parses a Header Object.
     *
     * @param data the whole Header Object, GUID and size included
     * @return the header
     * @throws MediaException.Malformed if the data is not a Header Object or lacks the File Properties Object
     */
    public static AsfHeader parse(byte[] data) {
        if (data.length < HEADER_PREFIX || !AsfGuid.is(data, 0, AsfGuid.HEADER)) {
            throw new MediaException.Malformed("not an ASF header object");
        }
        var header = new AsfHeader();
        try {
            header.parseChildren(data, HEADER_PREFIX, data.length, header.children, false);
        } catch (IndexOutOfBoundsException exception) {
            throw new MediaException.Malformed("truncated ASF header object", exception);
        }
        if (header.properties == null) {
            throw new MediaException.Malformed("ASF header without a file properties object");
        }
        return header;
    }

    /**
     * Parses the child objects of a region.
     *
     * @param data the header bytes
     * @param start the index of the first child
     * @param end the end of the region
     * @param into the list that receives the children
     * @param inExtension whether the region is the body of the Header Extension Object
     */
    private void parseChildren(byte[] data, int start, int end, List<Child> into, boolean inExtension) {
        var position = start;
        var count = 0;
        while (end - position >= 24) {
            var size = ByteArrays.i64le(data, position + 16);
            if (size < 24 || size > end - position) {
                break;
            }
            if (++count > MAX_ENTRIES) {
                throw new MediaException.Malformed("too many ASF header objects");
            }
            var child = new Child(Arrays.copyOfRange(data, position, position + 16), position,
                    position + (int) size);
            into.add(child);
            parseObject(data, child, inExtension);
            position = child.end();
        }
    }

    /**
     * Parses one object.
     *
     * @param data the header bytes
     * @param child the object
     * @param inExtension whether it is a Header Extension child
     */
    private void parseObject(byte[] data, Child child, boolean inExtension) {
        var body = child.start() + 24;
        var end = child.end();
        if (child.is(AsfGuid.FILE_PROPERTIES) && end - body >= 80) {
            properties = new FileProperties(ByteArrays.i64le(data, body + 16), ByteArrays.i64le(data, body + 24),
                    ByteArrays.i64le(data, body + 32), ByteArrays.i64le(data, body + 40),
                    ByteArrays.i64le(data, body + 48), ByteArrays.i64le(data, body + 56),
                    ByteArrays.i32le(data, body + 64), ByteArrays.u32le(data, body + 68),
                    ByteArrays.u32le(data, body + 72), ByteArrays.u32le(data, body + 76));
        } else if (child.is(AsfGuid.STREAM_PROPERTIES)) {
            parseStreamProperties(data, body, end);
        } else if (child.is(AsfGuid.HEADER_EXTENSION) && !inExtension && end - body >= EXTENSION_PREFIX) {
            extension = child;
            var size = ByteArrays.u32le(data, body + 18);
            var childrenEnd = (int) Math.min(end, body + EXTENSION_PREFIX + size);
            parseChildren(data, body + EXTENSION_PREFIX, childrenEnd, extensionChildren, true);
        } else if (child.is(AsfGuid.EXTENDED_STREAM_PROPERTIES)) {
            parseExtendedStreamProperties(data, body, end);
        } else if (child.is(AsfGuid.CONTENT_DESCRIPTION)) {
            parseContentDescription(data, body, end);
        } else if (child.is(AsfGuid.EXTENDED_CONTENT_DESCRIPTION)) {
            parseExtendedContent(data, body, end);
        } else if (child.is(AsfGuid.METADATA)) {
            parseMetadata(data, body, end, metadata, false);
        } else if (child.is(AsfGuid.METADATA_LIBRARY)) {
            parseMetadata(data, body, end, metadataLibrary, true);
        } else if (child.is(AsfGuid.LANGUAGE_LIST)) {
            parseLanguages(data, body, end);
        } else if (child.is(AsfGuid.MARKER)) {
            parseMarkers(data, body, end);
        } else if (child.is(AsfGuid.SCRIPT_COMMAND)) {
            parseScriptCommands(data, body, end);
        } else if (child.is(AsfGuid.CODEC_LIST)) {
            parseCodecList(data, body, end);
        } else if (child.is(AsfGuid.CONTENT_ENCRYPTION) || child.is(AsfGuid.EXTENDED_CONTENT_ENCRYPTION)
                || child.is(AsfGuid.ADVANCED_CONTENT_ENCRYPTION)) {
            protectedContent = true;
        }
    }

    /**
     * Parses a Stream Properties Object body.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     * @return the stream, or {@code null} when the object is too short
     */
    private Stream parseStreamProperties(byte[] data, int body, int end) {
        if (end - body < 54) {
            return null;
        }
        var typeLength = ByteArrays.u32le(data, body + 40);
        var correctionLength = ByteArrays.u32le(data, body + 44);
        var flags = ByteArrays.u16le(data, body + 48);
        var number = flags & 0x7F;
        if (number == 0 || typeLength + correctionLength > end - body - 54) {
            throw new MediaException.Malformed("corrupt ASF stream properties object");
        }
        var stream = streams.get(number);
        if (stream == null) {
            if (streams.size() >= MAX_STREAMS) {
                throw new MediaException.Malformed("more than " + MAX_STREAMS + " ASF streams");
            }
            stream = new Stream();
            stream.number = number;
            streams.put(number, stream);
        } else if (stream.type != null) {
            return stream;
        }
        stream.type = Arrays.copyOfRange(data, body, body + 16);
        stream.errorCorrection = Arrays.copyOfRange(data, body + 16, body + 32);
        stream.timeOffset = ByteArrays.i64le(data, body + 32);
        stream.encrypted = (flags & 0x8000) != 0;
        var typeStart = body + 54;
        stream.typeData = Arrays.copyOfRange(data, typeStart, typeStart + (int) typeLength);
        stream.errorCorrectionData = Arrays.copyOfRange(data, typeStart + (int) typeLength,
                typeStart + (int) (typeLength + correctionLength));
        return stream;
    }

    /**
     * Parses an Extended Stream Properties Object body, and the Stream Properties Object it may embed.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     */
    private void parseExtendedStreamProperties(byte[] data, int body, int end) {
        if (end - body < 64) {
            return;
        }
        var number = ByteArrays.u16le(data, body + 48);
        if (number == 0 || number > MAX_STREAMS) {
            return;
        }
        var stream = streams.computeIfAbsent(number, key -> {
            var created = new Stream();
            created.number = key;
            return created;
        });
        stream.extended = true;
        stream.languageIndex = ByteArrays.u16le(data, body + 50);
        stream.averageTimePerFrame = ByteArrays.i64le(data, body + 52);
        var nameCount = ByteArrays.u16le(data, body + 60);
        var systemCount = ByteArrays.u16le(data, body + 62);
        var position = body + 64;
        for (var i = 0; i < nameCount && end - position >= 4; i++) {
            var length = ByteArrays.u16le(data, position + 2);
            if (length > end - position - 4) {
                return;
            }
            var name = utf16(data, position + 4, length);
            if (!name.isEmpty()) {
                stream.names.add(name);
            }
            position += 4 + length;
        }
        for (var i = 0; i < systemCount && end - position >= 22; i++) {
            var infoLength = ByteArrays.u32le(data, position + 18);
            if (infoLength > end - position - 22) {
                return;
            }
            stream.extensionSystems.add(Arrays.copyOfRange(data, position, position + 16));
            stream.extensionSizes.add(ByteArrays.u16le(data, position + 16));
            position += 22 + (int) infoLength;
        }
        if (end - position >= 24 && AsfGuid.is(data, position, AsfGuid.STREAM_PROPERTIES)) {
            var size = ByteArrays.i64le(data, position + 16);
            if (size >= 24 && size <= end - position) {
                parseStreamProperties(data, position + 24, position + (int) size);
            }
        }
    }

    /**
     * Parses a Content Description Object body.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     */
    private void parseContentDescription(byte[] data, int body, int end) {
        if (end - body < 10 || !contentDescription.isEmpty()) {
            return;
        }
        var names = new String[]{"Title", "Author", "Copyright", "Description", "Rating"};
        var position = body + 10;
        for (var i = 0; i < 5; i++) {
            var length = ByteArrays.u16le(data, body + 2 * i);
            if (length > end - position) {
                return;
            }
            var value = Arrays.copyOfRange(data, position, position + length);
            contentDescription.add(new Attribute(names[i], UNICODE, value, 0, 0));
            position += length;
        }
    }

    /**
     * Parses an Extended Content Description Object body.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     */
    private void parseExtendedContent(byte[] data, int body, int end) {
        if (end - body < 2) {
            return;
        }
        var count = ByteArrays.u16le(data, body);
        var position = body + 2;
        for (var i = 0; i < count && end - position >= 2; i++) {
            var nameLength = ByteArrays.u16le(data, position);
            if (nameLength > end - position - 6) {
                return;
            }
            var name = utf16(data, position + 2, nameLength);
            position += 2 + nameLength;
            var type = ByteArrays.u16le(data, position);
            var length = ByteArrays.u16le(data, position + 2);
            if (length > end - position - 4) {
                return;
            }
            extendedContent.add(new Attribute(name, type,
                    Arrays.copyOfRange(data, position + 4, position + 4 + length), 0, 0));
            position += 4 + length;
        }
    }

    /**
     * Parses a Metadata or Metadata Library Object body.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     * @param into the list that receives the records
     * @param library whether the object is the Metadata Library Object, whose records carry a language
     */
    private static void parseMetadata(byte[] data, int body, int end, List<Attribute> into, boolean library) {
        if (end - body < 2) {
            return;
        }
        var count = ByteArrays.u16le(data, body);
        var position = body + 2;
        for (var i = 0; i < count && end - position >= 12; i++) {
            var language = library ? ByteArrays.u16le(data, position) : 0;
            var stream = ByteArrays.u16le(data, position + 2);
            var nameLength = ByteArrays.u16le(data, position + 4);
            var type = ByteArrays.u16le(data, position + 6);
            var length = ByteArrays.u32le(data, position + 8);
            if (nameLength + length > end - position - 12) {
                return;
            }
            var name = utf16(data, position + 12, nameLength);
            var valueStart = position + 12 + nameLength;
            into.add(new Attribute(name, type, Arrays.copyOfRange(data, valueStart,
                    valueStart + (int) length), stream, language));
            position = valueStart + (int) length;
        }
    }

    /**
     * Parses a Language List Object body.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     */
    private void parseLanguages(byte[] data, int body, int end) {
        if (end - body < 2 || !languages.isEmpty()) {
            return;
        }
        var count = ByteArrays.u16le(data, body);
        var position = body + 2;
        for (var i = 0; i < count && end - position >= 1; i++) {
            var length = data[position] & 0xFF;
            if (length > end - position - 1) {
                return;
            }
            languages.add(utf16(data, position + 1, length));
            position += 1 + length;
        }
    }

    /**
     * Parses a Marker Object body.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     */
    private void parseMarkers(byte[] data, int body, int end) {
        if (end - body < 24) {
            return;
        }
        var count = ByteArrays.u32le(data, body + 16);
        var nameLength = ByteArrays.u16le(data, body + 22);
        var position = body + 24 + nameLength;
        for (long i = 0; i < count && end - position >= 30 && markers.size() < MAX_ENTRIES; i++) {
            var offset = ByteArrays.i64le(data, position);
            var time = ByteArrays.i64le(data, position + 8);
            var entryLength = ByteArrays.u16le(data, position + 16);
            var descriptionChars = ByteArrays.u32le(data, position + 26);
            var descriptionBytes = descriptionChars * 2;
            if (descriptionBytes > end - position - 30) {
                return;
            }
            markers.add(new Marker(offset, time, utf16(data, position + 30, (int) descriptionBytes)));
            position += 18 + Math.max(entryLength, 12 + (int) descriptionBytes);
        }
    }

    /**
     * Parses a Script Command Object body.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     */
    private void parseScriptCommands(byte[] data, int body, int end) {
        if (end - body < 20) {
            return;
        }
        var commandCount = ByteArrays.u16le(data, body + 16);
        var typeCount = ByteArrays.u16le(data, body + 18);
        var position = body + 20;
        var types = new ArrayList<String>();
        for (var i = 0; i < typeCount && end - position >= 2; i++) {
            var length = 2 * ByteArrays.u16le(data, position);
            if (length > end - position - 2) {
                return;
            }
            types.add(utf16(data, position + 2, length));
            position += 2 + length;
        }
        for (var i = 0; i < commandCount && end - position >= 8; i++) {
            var millis = ByteArrays.u32le(data, position);
            var typeIndex = ByteArrays.u16le(data, position + 4);
            var length = 2 * ByteArrays.u16le(data, position + 6);
            if (length > end - position - 8) {
                return;
            }
            var type = typeIndex < types.size() ? types.get(typeIndex) : "";
            scriptCommands.add(new ScriptCommand(millis, type, utf16(data, position + 8, length)));
            position += 8 + length;
        }
    }

    /**
     * Parses a Codec List Object body.
     *
     * @param data the header bytes
     * @param body the index of the body
     * @param end the end of the object
     */
    private void parseCodecList(byte[] data, int body, int end) {
        if (end - body < 20) {
            return;
        }
        var count = ByteArrays.u32le(data, body + 16);
        var position = body + 20;
        for (long i = 0; i < count && end - position >= 4 && codecNames.size() < MAX_ENTRIES; i++) {
            var nameLength = 2 * ByteArrays.u16le(data, position + 2);
            if (nameLength > end - position - 6) {
                return;
            }
            codecNames.add(utf16(data, position + 4, nameLength));
            position += 4 + nameLength;
            var descriptionLength = 2 * ByteArrays.u16le(data, position);
            if (descriptionLength > end - position - 4) {
                return;
            }
            position += 2 + descriptionLength;
            var infoLength = ByteArrays.u16le(data, position);
            if (infoLength > end - position - 2) {
                return;
            }
            position += 2 + infoLength;
        }
    }

    /**
     * Decodes a UTF-16LE string up to its first NUL character.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes; an odd last byte is ignored
     * @return the text
     */
    public static String utf16(byte[] data, int offset, int length) {
        var chars = length / 2;
        var used = 0;
        while (used < chars && (data[offset + 2 * used] != 0 || data[offset + 2 * used + 1] != 0)) {
            used++;
        }
        return new String(data, offset, 2 * used, StandardCharsets.UTF_16LE);
    }

    /**
     * Returns the text form of an attribute value.
     *
     * @param attribute the descriptor or record
     * @return the text: the string, the number in decimal, {@code 1} or {@code 0} for a Boolean, the GUID in registry
     * form; {@code null} for a byte array or a value too short for its type
     */
    public static String text(Attribute attribute) {
        var value = attribute.value();
        return switch (attribute.type()) {
            case UNICODE -> utf16(value, 0, value.length);
            case BOOL -> value.length >= 2 ? (value[0] != 0 || value[1] != 0 ? "1" : "0") : null;
            case DWORD -> value.length >= 4 ? Long.toString(ByteArrays.u32le(value, 0)) : null;
            case QWORD -> value.length >= 8 ? Long.toUnsignedString(ByteArrays.i64le(value, 0)) : null;
            case WORD -> value.length >= 2 ? Integer.toString(ByteArrays.u16le(value, 0)) : null;
            case GUID -> value.length >= 16 ? guidText(value, 0) : null;
            default -> null;
        };
    }

    /**
     * Returns the registry form of a stored GUID.
     *
     * @param data the bytes
     * @param offset the index of the GUID
     * @return the GUID, such as {@code 75B22630-668E-11CF-A6D9-00AA0062CE6C}
     */
    public static String guidText(byte[] data, int offset) {
        var out = new StringBuilder(36);
        var order = new int[]{3, 2, 1, 0, -1, 5, 4, -1, 7, 6, -1, 8, 9, -1, 10, 11, 12, 13, 14, 15};
        for (var index : order) {
            if (index < 0) {
                out.append('-');
            } else {
                out.append(Character.toUpperCase(Character.forDigit((data[offset + index] >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(data[offset + index] & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /**
     * {@return the File Properties Object}
     */
    public FileProperties properties() {
        return properties;
    }

    /**
     * {@return the streams in increasing stream number, those without a Stream Properties Object included}
     */
    public List<Stream> streams() {
        return List.copyOf(streams.values());
    }

    /**
     * Returns a stream by number.
     *
     * @param number the stream number
     * @return the stream, or {@code null}
     */
    public Stream stream(int number) {
        return streams.get(number);
    }

    /**
     * {@return the Content Description fields that are present, as Unicode attributes}
     */
    public List<Attribute> contentDescription() {
        return Collections.unmodifiableList(contentDescription);
    }

    /**
     * {@return the Extended Content Description descriptors}
     */
    public List<Attribute> extendedContent() {
        return Collections.unmodifiableList(extendedContent);
    }

    /**
     * {@return the Metadata Object records}
     */
    public List<Attribute> metadata() {
        return Collections.unmodifiableList(metadata);
    }

    /**
     * {@return the Metadata Library Object records}
     */
    public List<Attribute> metadataLibrary() {
        return Collections.unmodifiableList(metadataLibrary);
    }

    /**
     * {@return the language tags of the Language List Object}
     */
    public List<String> languages() {
        return Collections.unmodifiableList(languages);
    }

    /**
     * {@return the markers}
     */
    public List<Marker> markers() {
        return Collections.unmodifiableList(markers);
    }

    /**
     * {@return the script commands}
     */
    public List<ScriptCommand> scriptCommands() {
        return Collections.unmodifiableList(scriptCommands);
    }

    /**
     * {@return the codec names of the Codec List Object}
     */
    public List<String> codecNames() {
        return Collections.unmodifiableList(codecNames);
    }

    /**
     * {@return the top-level children of the Header Object}
     */
    public List<Child> children() {
        return Collections.unmodifiableList(children);
    }

    /**
     * {@return the children of the Header Extension Object}
     */
    public List<Child> extensionChildren() {
        return Collections.unmodifiableList(extensionChildren);
    }

    /**
     * {@return the Header Extension Object, or {@code null}}
     */
    public Child extension() {
        return extension;
    }

    /**
     * {@return whether a content encryption object protects the file}
     */
    public boolean isProtected() {
        return protectedContent;
    }
}
