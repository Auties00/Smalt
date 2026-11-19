package com.github.auties00.smalt.container.asf;

import com.github.auties00.smalt.internal.asf.AsfGuid;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes ASF files from media objects, independently of the demuxer, following the ASF Specification: every
 * payload layout of the data packets (single payloads, multiple payloads, compressed payloads), every length type of
 * the payload parsing information, error correction data, padding, audio spread interleaving, extended stream
 * properties with payload extension systems and hidden streams, the descriptive objects and the Simple Index.
 * The tests use it for structures no other writer at hand produces.
 */
final class AsfWriter {
    /** How payloads are laid out in data packets. */
    enum Layout {
        /** One payload per packet. */
        SINGLE,
        /** Several payloads per packet. */
        MULTIPLE,
        /** Several payloads per packet; runs of small objects of one stream become compressed payloads. */
        COMPRESSED
    }

    /** A payload extension system of a stream: its GUID and fixed data size, 0xFFFF for a variable size. */
    record Extension(byte[] guid, int size) {
    }

    /** One media object: the stream, the presentation time in milliseconds (preroll included), and the bytes. */
    record MediaObject(int stream, long millis, boolean key, byte[] data, byte[] extensionData) {
        MediaObject(int stream, long millis, boolean key, byte[] data) {
            this(stream, millis, key, data, new byte[0]);
        }
    }

    /** A stream: its Stream Properties Object fields and optional Extended Stream Properties Object fields. */
    static final class Stream {
        final int number;
        final byte[] type;
        final byte[] typeData;
        byte[] errorCorrection = AsfGuid.of("20FB5700-5B55-11CF-A8FD-00805F5C442B");
        byte[] errorCorrectionData = new byte[0];
        boolean encrypted;
        boolean extended;
        boolean hidden;
        long averageTimePerFrame;
        int languageIndex;
        final List<String> names = new ArrayList<>();
        final List<Extension> extensions = new ArrayList<>();
        int span;
        int spanPacket;
        int spanChunk;

        Stream(int number, byte[] type, byte[] typeData) {
            this.number = number;
            this.type = type;
            this.typeData = typeData;
        }

        /** Declares audio spread interleaving and makes the demuxer's input scrambled accordingly. */
        Stream spread(int span, int packet, int chunk) {
            this.span = span;
            this.spanPacket = packet;
            this.spanChunk = chunk;
            this.errorCorrection = AsfGuid.AUDIO_SPREAD;
            var data = le(9);
            data.put((byte) span).putShort((short) packet).putShort((short) chunk).putShort((short) 1).put((byte) 0);
            this.errorCorrectionData = data.array();
            return this;
        }
    }

    /** A descriptor or metadata record. */
    record Attribute(String name, int type, byte[] value, int stream, int language) {
    }

    /** A marker: its time in 100-nanosecond units (preroll included) and its name. */
    record Marker(long time, String name) {
    }

    /** A script command: its time in milliseconds (preroll included), its type and its parameter. */
    record Command(long millis, String type, String command) {
    }

    final List<Stream> streams = new ArrayList<>();
    final List<MediaObject> objects = new ArrayList<>();
    final List<Attribute> descriptors = new ArrayList<>();
    final List<Attribute> metadata = new ArrayList<>();
    final List<Attribute> library = new ArrayList<>();
    final List<String> languages = new ArrayList<>();
    final List<Marker> markers = new ArrayList<>();
    final List<Command> commands = new ArrayList<>();
    final List<byte[]> extraObjects = new ArrayList<>();
    String[] contentDescription;
    Layout layout = Layout.MULTIPLE;
    int packetSize = 512;
    long preroll = 1000;
    long creationDate;
    boolean errorCorrectionData = true;
    boolean explicitPacketLength;
    int numberWidth = 1;
    int offsetWidth = 4;
    int replicatedWidth = 1;
    int paddingWidth = 2;
    int payloadLengthWidth = 2;
    boolean simpleIndex = true;
    boolean broadcast;
    int headerPadding;

    static Stream pcmAudio(int number, int channels, int rate) {
        var format = le(18);
        format.putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16).putShort((short) 0);
        return new Stream(number, AsfGuid.AUDIO_MEDIA, format.array());
    }

    static Stream audio(int number, int tag, int channels, int rate, int blockAlign, byte[] extra) {
        var format = le(18 + extra.length);
        format.putShort((short) tag).putShort((short) channels).putInt(rate).putInt(rate * 2)
                .putShort((short) blockAlign).putShort((short) 0).putShort((short) extra.length).put(extra);
        return new Stream(number, AsfGuid.AUDIO_MEDIA, format.array());
    }

    static Stream video(int number, String fourCc, int width, int height, byte[] extra) {
        var bitmap = le(40 + extra.length);
        bitmap.putInt(40 + extra.length).putInt(width).putInt(height).putShort((short) 1).putShort((short) 24)
                .put(fourCc.getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
                .put(extra);
        var data = le(11 + bitmap.capacity());
        data.putInt(width).putInt(height).put((byte) 2).putShort((short) bitmap.capacity()).put(bitmap.array());
        return new Stream(number, AsfGuid.VIDEO_MEDIA, data.array());
    }

    static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] utf16z(String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_16LE);
        return Arrays.copyOf(bytes, bytes.length + 2);
    }

    static byte[] object(byte[] guid, byte[] body) {
        var out = le(24 + body.length);
        out.put(guid).putLong(24 + body.length).put(body);
        return out.array();
    }

    static byte[] concat(List<byte[]> parts) {
        var out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** Writes the file. */
    byte[] build() {
        var packets = packets();
        var dataBody = le(26 + packets.size() * packetSize);
        dataBody.put(new byte[16]).putLong(packets.size()).put((byte) 1).put((byte) 1);
        packets.forEach(dataBody::put);
        var data = object(AsfGuid.DATA, dataBody.array());
        var header = header(packets.size(), data.length);
        var parts = new ArrayList<byte[]>(List.of(header, data));
        if (simpleIndex) {
            parts.add(simpleIndex(packets));
        }
        var file = concat(parts);
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putLong(fileSizeOffset(header), file.length);
        return file;
    }

    private int fileSizeOffset(byte[] header) {
        var position = 30;
        while (position < header.length) {
            var size = (int) ByteBuffer.wrap(header, position + 16, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (Arrays.equals(header, position, position + 16, AsfGuid.FILE_PROPERTIES, 0, 16)) {
                return position + 24 + 16;
            }
            position += size;
        }
        throw new IllegalStateException();
    }

    private long lastTime() {
        return objects.stream().mapToLong(MediaObject::millis).max().orElse(0);
    }

    private byte[] header(long packetCount, long dataSize) {
        var children = new ArrayList<byte[]>();
        var properties = le(80);
        var play = (lastTime() + 40) * 10_000;
        properties.put(new byte[16]).putLong(0).putLong(creationDate).putLong(packetCount).putLong(play)
                .putLong(play - preroll * 10_000).putLong(preroll).putInt(broadcast ? 1 : 2).putInt(packetSize)
                .putInt(packetSize).putInt(1_000_000);
        children.add(object(AsfGuid.FILE_PROPERTIES, properties.array()));
        children.add(headerExtension());
        for (var stream : streams) {
            if (!stream.hidden) {
                children.add(streamProperties(stream));
            }
        }
        if (contentDescription != null) {
            var encoded = new byte[5][];
            var body = new ByteArrayOutputStream();
            for (var i = 0; i < 5; i++) {
                encoded[i] = contentDescription[i] == null ? new byte[0] : utf16z(contentDescription[i]);
                body.write(encoded[i].length & 0xFF);
                body.write(encoded[i].length >> 8);
            }
            for (var value : encoded) {
                body.writeBytes(value);
            }
            children.add(object(AsfGuid.CONTENT_DESCRIPTION, body.toByteArray()));
        }
        if (!descriptors.isEmpty()) {
            var body = new ByteArrayOutputStream();
            body.writeBytes(le(2).putShort((short) descriptors.size()).array());
            for (var descriptor : descriptors) {
                var name = utf16z(descriptor.name());
                body.writeBytes(le(2).putShort((short) name.length).array());
                body.writeBytes(name);
                body.writeBytes(le(4).putShort((short) descriptor.type()).putShort((short) descriptor.value().length)
                        .array());
                body.writeBytes(descriptor.value());
            }
            children.add(object(AsfGuid.EXTENDED_CONTENT_DESCRIPTION, body.toByteArray()));
        }
        if (!markers.isEmpty()) {
            var body = new ByteArrayOutputStream();
            body.writeBytes(AsfGuid.RESERVED_4);
            body.writeBytes(le(8).putInt(markers.size()).putShort((short) 0).putShort((short) 0).array());
            for (var marker : markers) {
                var name = utf16z(marker.name());
                body.writeBytes(le(30).putLong(0).putLong(marker.time()).putShort((short) (12 + name.length))
                        .putInt((int) (marker.time() / 10_000)).putInt(0).putInt(name.length / 2).array());
                body.writeBytes(name);
            }
            children.add(object(AsfGuid.MARKER, body.toByteArray()));
        }
        if (!commands.isEmpty()) {
            var types = commands.stream().map(Command::type).distinct().toList();
            var body = new ByteArrayOutputStream();
            body.writeBytes(AsfGuid.RESERVED_3);
            body.writeBytes(le(4).putShort((short) commands.size()).putShort((short) types.size()).array());
            for (var type : types) {
                var name = utf16z(type);
                body.writeBytes(le(2).putShort((short) (name.length / 2)).array());
                body.writeBytes(name);
            }
            for (var command : commands) {
                var name = utf16z(command.command());
                body.writeBytes(le(8).putInt((int) command.millis()).putShort((short) types.indexOf(command.type()))
                        .putShort((short) (name.length / 2)).array());
                body.writeBytes(name);
            }
            children.add(object(AsfGuid.SCRIPT_COMMAND, body.toByteArray()));
        }
        children.addAll(extraObjects);
        if (headerPadding > 0) {
            children.add(object(AsfGuid.PADDING, new byte[headerPadding]));
        }
        var body = concat(children);
        var out = le(30 + body.length);
        out.put(AsfGuid.HEADER).putLong(30 + body.length).putInt(children.size()).put((byte) 1).put((byte) 2)
                .put(body);
        return out.array();
    }

    private byte[] headerExtension() {
        var children = new ArrayList<byte[]>();
        if (!languages.isEmpty()) {
            var body = new ByteArrayOutputStream();
            body.writeBytes(le(2).putShort((short) languages.size()).array());
            for (var language : languages) {
                var name = utf16z(language);
                body.write(name.length);
                body.writeBytes(name);
            }
            children.add(object(AsfGuid.LANGUAGE_LIST, body.toByteArray()));
        }
        for (var stream : streams) {
            if (stream.extended || stream.hidden) {
                children.add(extendedStreamProperties(stream));
            }
        }
        if (!metadata.isEmpty()) {
            children.add(object(AsfGuid.METADATA, records(metadata)));
        }
        if (!library.isEmpty()) {
            children.add(object(AsfGuid.METADATA_LIBRARY, records(library)));
        }
        var body = concat(children);
        var out = le(22 + body.length);
        out.put(AsfGuid.RESERVED_1).putShort((short) 6).putInt(body.length).put(body);
        return object(AsfGuid.HEADER_EXTENSION, out.array());
    }

    private static byte[] records(List<Attribute> records) {
        var body = new ByteArrayOutputStream();
        body.writeBytes(le(2).putShort((short) records.size()).array());
        for (var record : records) {
            var name = utf16z(record.name());
            body.writeBytes(le(12).putShort((short) record.language()).putShort((short) record.stream())
                    .putShort((short) name.length).putShort((short) record.type()).putInt(record.value().length)
                    .array());
            body.writeBytes(name);
            body.writeBytes(record.value());
        }
        return body.toByteArray();
    }

    private byte[] streamProperties(Stream stream) {
        var body = le(54 + stream.typeData.length + stream.errorCorrectionData.length);
        body.put(stream.type).put(stream.errorCorrection).putLong(0).putInt(stream.typeData.length)
                .putInt(stream.errorCorrectionData.length)
                .putShort((short) (stream.number | (stream.encrypted ? 0x8000 : 0))).putInt(0)
                .put(stream.typeData).put(stream.errorCorrectionData);
        return object(AsfGuid.STREAM_PROPERTIES, body.array());
    }

    private byte[] extendedStreamProperties(Stream stream) {
        var body = new ByteArrayOutputStream();
        body.writeBytes(le(64).putLong(0).putLong(0).putInt(100_000).putInt(1000).putInt(1000).putInt(0).putInt(0)
                .putInt(0).putInt(0).putInt(0).putShort((short) stream.number).putShort((short) stream.languageIndex)
                .putLong(stream.averageTimePerFrame).putShort((short) stream.names.size())
                .putShort((short) stream.extensions.size()).array());
        for (var name : stream.names) {
            var bytes = utf16z(name);
            body.writeBytes(le(4).putShort((short) 0).putShort((short) bytes.length).array());
            body.writeBytes(bytes);
        }
        for (var extension : stream.extensions) {
            body.writeBytes(extension.guid());
            body.writeBytes(le(6).putShort((short) extension.size()).putInt(0).array());
        }
        if (stream.hidden) {
            body.writeBytes(streamProperties(stream));
        }
        return object(AsfGuid.EXTENDED_STREAM_PROPERTIES, body.toByteArray());
    }

    private Stream streamOf(int number) {
        return streams.stream().filter(stream -> stream.number == number).findFirst().orElseThrow();
    }

    /** Scrambles an object of an interleaved audio stream: original chunk i is stored at (i % span) * chunks + i / span. */
    static byte[] scramble(byte[] object, int span, int chunks, int chunkSize) {
        var result = new byte[object.length];
        for (var index = 0; index < span * chunks; index++) {
            var stored = (index % span) * chunks + index / span;
            System.arraycopy(object, index * chunkSize, result, stored * chunkSize, chunkSize);
        }
        return result;
    }

    /** A payload waiting for a packet. */
    private record Payload(int stream, boolean key, int objectNumber, int offset, byte[] replicated, byte[] data) {
    }

    private List<byte[]> packets() {
        var numbers = new int[128];
        var payloads = new ArrayList<Payload>();
        var times = new ArrayList<Long>();
        for (var index = 0; index < objects.size(); index++) {
            var object = objects.get(index);
            var stream = streamOf(object.stream());
            var data = object.data();
            if (stream.span > 1 && data.length == stream.span * stream.spanPacket) {
                data = scramble(data, stream.span, stream.spanPacket / stream.spanChunk, stream.spanChunk);
            }
            var number = numbers[object.stream()]++ & mask(numberWidth);
            if (layout == Layout.COMPRESSED && data.length <= 255) {
                var run = new ArrayList<MediaObject>(List.of(object));
                var runBytes = 1 + data.length;
                while (index + run.size() < objects.size() && run.size() < 8) {
                    var next = objects.get(index + run.size());
                    if (runBytes + 1 + next.data().length > packetSize - 64) {
                        break;
                    }
                    var delta = next.millis() - run.getLast().millis();
                    var firstDelta = run.size() == 1 ? delta : run.get(1).millis() - run.get(0).millis();
                    if (next.stream() != object.stream() || next.data().length > 255 || delta != firstDelta
                            || delta < 0 || delta > 255 || next.key() != object.key()) {
                        break;
                    }
                    run.add(next);
                    runBytes += 1 + next.data().length;
                }
                if (run.size() > 1) {
                    var sub = new ByteArrayOutputStream();
                    for (var member : run) {
                        sub.write(member.data().length);
                        sub.writeBytes(member.data());
                    }
                    var delta = (int) (run.get(1).millis() - run.get(0).millis());
                    numbers[object.stream()] += run.size() - 1;
                    payloads.add(new Payload(object.stream(), object.key(), number, (int) object.millis(),
                            new byte[]{(byte) delta}, sub.toByteArray()));
                    times.add(object.millis());
                    index += run.size() - 1;
                    continue;
                }
            }
            var replicated = le(8 + object.extensionData().length).putInt(data.length).putInt((int) object.millis())
                    .put(object.extensionData()).array();
            var budget = Math.max(16, packetSize - 64 - replicated.length);
            for (var offset = 0; offset < data.length; offset += budget) {
                var piece = Arrays.copyOfRange(data, offset, Math.min(data.length, offset + budget));
                payloads.add(new Payload(object.stream(), object.key(), number, offset, replicated, piece));
                times.add(object.millis());
            }
        }
        var packets = new ArrayList<byte[]>();
        var send = 0L;
        var cursor = 0;
        while (cursor < payloads.size()) {
            var time = Math.max(send, times.get(cursor) - preroll);
            send = time;
            var packet = new ByteArrayOutputStream();
            var multiple = layout != Layout.SINGLE;
            var lengthFlags = (multiple ? 1 : 0) | code(paddingWidth) << 3
                    | (explicitPacketLength ? code(2) << 5 : 0);
            var propertyFlags = code(replicatedWidth) | code(offsetWidth) << 2 | code(numberWidth) << 4 | 1 << 6;
            if (errorCorrectionData) {
                packet.write(0x82);
                packet.write(0);
                packet.write(0);
            }
            packet.write(lengthFlags);
            packet.write(propertyFlags);
            var lengthAt = packet.size();
            if (explicitPacketLength) {
                packet.writeBytes(new byte[2]);
            }
            var paddingAt = packet.size();
            packet.writeBytes(new byte[paddingWidth]);
            packet.writeBytes(le(6).putInt((int) time).putShort((short) 40).array());
            var body = new ByteArrayOutputStream();
            var count = 0;
            var used = packet.size() + (multiple ? 1 : 0);
            while (cursor < payloads.size() && count < 63) {
                var payload = payloads.get(cursor);
                var headerSize = 1 + numberWidth + offsetWidth + replicatedWidth + payload.replicated().length
                        + (multiple ? payloadLengthWidth : 0);
                var size = headerSize + payload.data().length;
                if (used + size > packetSize) {
                    if (count == 0) {
                        throw new IllegalStateException("payload of " + payload.data().length + " bytes ("
                                + headerSize + " of header, " + used + " of packet header) larger than a packet of "
                                + packetSize + " bytes");
                    }
                    break;
                }
                body.write(payload.stream() | (payload.key() ? 0x80 : 0));
                field(body, payload.objectNumber(), numberWidth);
                field(body, payload.offset(), offsetWidth);
                field(body, payload.replicated().length, replicatedWidth);
                body.writeBytes(payload.replicated());
                if (multiple) {
                    field(body, payload.data().length, payloadLengthWidth);
                }
                body.writeBytes(payload.data());
                used += size;
                count++;
                cursor++;
                if (!multiple) {
                    break;
                }
            }
            if (multiple) {
                packet.write(count | code(payloadLengthWidth) << 6);
            }
            packet.writeBytes(body.toByteArray());
            var bytes = Arrays.copyOf(packet.toByteArray(), packetSize);
            var padding = packetSize - packet.size();
            var view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (explicitPacketLength) {
                // without a padding field the stated length ends the payloads; with one it is the whole packet
                view.putShort(lengthAt, (short) (paddingWidth == 0 ? packet.size() : packetSize));
            }
            switch (paddingWidth) {
                case 1 -> bytes[paddingAt] = (byte) padding;
                case 2 -> view.putShort(paddingAt, (short) padding);
                case 4 -> view.putInt(paddingAt, padding);
                default -> {
                    if (padding != 0 && !explicitPacketLength) {
                        throw new IllegalStateException("no padding field for " + padding + " bytes");
                    }
                }
            }
            packets.add(bytes);
        }
        return packets;
    }

    private static int mask(int width) {
        return width == 0 ? 0 : width == 4 ? -1 : (1 << (8 * width)) - 1;
    }

    private static int code(int width) {
        return switch (width) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 4 -> 3;
            default -> throw new IllegalArgumentException("width " + width);
        };
    }

    private static void field(ByteArrayOutputStream out, int value, int width) {
        for (var i = 0; i < width; i++) {
            out.write(value >>> (8 * i));
        }
    }

    private byte[] simpleIndex(List<byte[]> packets) {
        var interval = 1000L;
        var entries = (int) (lastTime() / interval) + 1;
        var body = le(32 + 6 * entries);
        body.put(new byte[16]).putLong(interval * 10_000).putInt(1).putInt(entries);
        for (var entry = 0; entry < entries; entry++) {
            body.putInt(0).putShort((short) 1);
        }
        return object(AsfGuid.SIMPLE_INDEX, body.array());
    }
}
