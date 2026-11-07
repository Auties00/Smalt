package com.github.auties00.smalt.aot.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes the ASF files the training workload of the ASF demuxer reads, following the Advanced Systems Format
 * Specification (revision 01.20.05): a Header Object with the File Properties, a Header Extension holding a Language
 * List and an Extended Stream Properties Object per stream, the Stream Properties and a Content Description; a Data
 * Object of fixed-size data packets with error correction data, multiple payloads per packet, media objects split
 * over several payloads, runs of small objects as compressed payloads and audio spread interleaving; and a Simple
 * Index Object of the key frames of the video stream.
 */
final class SyntheticAsf {
    /**
     * The size of every data packet.
     */
    static final int PACKET_SIZE = 4096;

    /**
     * The preroll, in milliseconds, which every presentation time of the file includes.
     */
    static final int PREROLL = 500;

    /**
     * The interval of the Simple Index entries, in milliseconds.
     */
    private static final int INDEX_INTERVAL = 500;

    /**
     * The bytes of a packet before its payloads: the error correction data, the two flag bytes, the padding length,
     * the send time, the duration and the payload flags.
     */
    private static final int PACKET_HEADER = 3 + 2 + 2 + 4 + 2 + 1;

    /**
     * The bytes of a payload header before its replicated data: stream number, media object number, offset and
     * replicated data length.
     */
    private static final int PAYLOAD_HEADER = 1 + 1 + 4 + 1;

    private static final byte[] HEADER = guid("75B22630-668E-11CF-A6D9-00AA0062CE6C");
    private static final byte[] DATA = guid("75B22636-668E-11CF-A6D9-00AA0062CE6C");
    private static final byte[] SIMPLE_INDEX = guid("33000890-E5B1-11CF-89F4-00A0C90349CB");
    private static final byte[] FILE_PROPERTIES = guid("8CABDCA1-A947-11CF-8EE4-00C00C205365");
    private static final byte[] STREAM_PROPERTIES = guid("B7DC0791-A9B7-11CF-8EE6-00C00C205365");
    private static final byte[] HEADER_EXTENSION = guid("5FBF03B5-A92E-11CF-8EE3-00C00C205365");
    private static final byte[] CONTENT_DESCRIPTION = guid("75B22633-668E-11CF-A6D9-00AA0062CE6C");
    private static final byte[] EXTENDED_STREAM_PROPERTIES = guid("14E6A5CB-C672-4332-8399-A96952065B5A");
    private static final byte[] LANGUAGE_LIST = guid("7C4346A9-EFE0-4BFC-B229-393EDE415C85");
    private static final byte[] HEADER_EXTENSION_RESERVED = guid("ABD3D211-A9BA-11CF-8EE6-00C00C205365");
    private static final byte[] AUDIO_MEDIA = guid("F8699E40-5B4D-11CF-A8FD-00805F5C442B");
    private static final byte[] VIDEO_MEDIA = guid("BC19EFC0-5B4D-11CF-A8FD-00805F5C442B");
    private static final byte[] NO_ERROR_CORRECTION = guid("20FB5700-5B55-11CF-A8FD-00805F5C442B");
    private static final byte[] AUDIO_SPREAD = guid("BFC3CD50-618F-11CF-8BB2-00AA00B4E220");
    private static final byte[] FILE_ID = guid("6D616C53-2D74-6F61-7472-61696E696E67");

    /**
     * Prevents instantiation.
     */
    private SyntheticAsf() {
        throw new AssertionError();
    }

    /**
     * A stream of the file.
     *
     * @param number the stream number, 1 to 127
     * @param video whether the stream is a video stream, otherwise an audio stream
     * @param typeData the type-specific data of its Stream Properties Object
     * @param averageTimePerFrame the average time per frame, in 100-nanosecond units, or 0
     * @param span the number of virtual packets of audio spread interleaving, or 0 for none
     * @param spanPacket the size of a virtual packet of the interleaving
     * @param spanChunk the size of a chunk of the interleaving
     */
    record Stream(int number, boolean video, byte[] typeData, long averageTimePerFrame, int span, int spanPacket,
                  int spanChunk) {
        /**
         * Returns whether a media object of the stream is stored interleaved: whole objects of {@code span} virtual
         * packets are, others are not.
         *
         * @param length the size of the object
         * @return whether the object is interleaved
         */
        boolean interleaves(int length) {
            return span > 1 && length == span * spanPacket;
        }
    }

    /**
     * A media object.
     *
     * @param stream the stream number
     * @param millis the presentation time in milliseconds, without the preroll
     * @param key whether the object is a key frame
     * @param data the object
     */
    record MediaObject(int stream, long millis, boolean key, byte[] data) {
    }

    /**
     * Returns a video stream of H.264 as an Annex B byte stream, whose {@code BITMAPINFOHEADER} carries the parameter
     * sets as Annex B extra data.
     *
     * @param number the stream number
     * @param width the width of the pictures
     * @param height the height of the pictures
     * @param parameterSets the sequence and picture parameter sets, with start codes
     * @param averageTimePerFrame the average time per frame, in 100-nanosecond units
     * @return the stream
     */
    static Stream h264(int number, int width, int height, byte[] parameterSets, long averageTimePerFrame) {
        var bitmap = le(40 + parameterSets.length);
        bitmap.putInt(40 + parameterSets.length).putInt(width).putInt(height).putShort((short) 1)
                .putShort((short) 24).put("H264".getBytes(StandardCharsets.ISO_8859_1)).putInt(width * height * 3)
                .putInt(0).putInt(0).putInt(0).putInt(0).put(parameterSets);
        var data = le(11 + bitmap.capacity());
        data.putInt(width).putInt(height).put((byte) 2).putShort((short) bitmap.capacity()).put(bitmap.array());
        return new Stream(number, true, data.array(), averageTimePerFrame, 0, 0, 0);
    }

    /**
     * Returns an audio stream of integer PCM in a {@code WAVEFORMATEX}.
     *
     * @param number the stream number
     * @param channels the number of channels
     * @param rate the sampling rate
     * @param bits the bits per sample, 8 (unsigned) or 16 (signed little endian)
     * @param span the number of virtual packets of audio spread interleaving, or 0 for none
     * @param spanPacket the size of a virtual packet of the interleaving, a multiple of {@code spanChunk}
     * @param spanChunk the size of a chunk of the interleaving
     * @return the stream
     */
    static Stream pcm(int number, int channels, int rate, int bits, int span, int spanPacket, int spanChunk) {
        var blockAlign = channels * bits / 8;
        var format = le(18);
        format.putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * blockAlign)
                .putShort((short) blockAlign).putShort((short) bits).putShort((short) 0);
        return new Stream(number, false, format.array(), 0, span, spanPacket, spanChunk);
    }

    /**
     * Converts an H.264 access unit of length-prefixed NAL units to an Annex B byte stream.
     *
     * @param unit the access unit
     * @param lengthSize the size of each length prefix, 1 to 4 bytes
     * @return the byte stream, each NAL unit after a four-byte start code
     */
    static byte[] annexB(byte[] unit, int lengthSize) {
        var out = new ByteArrayOutputStream(unit.length + 16);
        var position = 0;
        while (position + lengthSize <= unit.length) {
            var length = 0;
            for (var index = 0; index < lengthSize; index++) {
                length = length << 8 | unit[position + index] & 0xFF;
            }
            position += lengthSize;
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.write(unit, position, Math.min(length, unit.length - position));
            position += length;
        }
        return out.toByteArray();
    }

    /**
     * Returns the parameter sets of an {@code AVCDecoderConfigurationRecord} as an Annex B byte stream.
     *
     * @param config the configuration record
     * @return the sequence and then the picture parameter sets, each after a four-byte start code
     */
    static byte[] annexBParameterSets(byte[] config) {
        var out = new ByteArrayOutputStream();
        var position = 5;
        for (var group = 0; group < 2; group++) {
            var count = group == 0 ? config[position++] & 0x1F : config[position++] & 0xFF;
            for (var index = 0; index < count; index++) {
                var length = (config[position] & 0xFF) << 8 | config[position + 1] & 0xFF;
                position += 2;
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.write(config, position, length);
                position += length;
            }
        }
        return out.toByteArray();
    }

    /**
     * Returns the size of the length prefixes of the NAL units an {@code AVCDecoderConfigurationRecord} declares.
     *
     * @param config the configuration record
     * @return the size, 1 to 4 bytes
     */
    static int lengthSize(byte[] config) {
        return (config[4] & 3) + 1;
    }

    /**
     * Writes a file.
     *
     * @param title the title of the Content Description Object
     * @param streams the streams, each declared by a Stream Properties Object
     * @param objects the media objects, in the order they are sent; each stream's in decoding order
     * @return the file
     */
    static byte[] write(String title, List<Stream> streams, List<MediaObject> objects) {
        var indexed = streams.stream().filter(Stream::video).mapToInt(Stream::number).findFirst().orElse(-1);
        var keys = new ArrayList<long[]>();
        var packets = packets(streams, objects, indexed, keys);
        var lastMillis = objects.stream().mapToLong(MediaObject::millis).max().orElse(0);
        var dataBody = le(26 + packets.size() * PACKET_SIZE);
        dataBody.put(FILE_ID).putLong(packets.size()).put((byte) 1).put((byte) 1);
        packets.forEach(dataBody::put);
        var data = object(DATA, dataBody.array());
        var index = simpleIndex(keys, lastMillis, packets.size());
        var header = header(title, streams, packets.size(), lastMillis);
        var file = new byte[header.length + data.length + index.length];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(data, 0, file, header.length, data.length);
        System.arraycopy(index, 0, file, header.length + data.length, index.length);
        // the file size field of the File Properties Object, its first child after the 30 bytes of the header
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putLong(30 + 24 + 16, file.length);
        return file;
    }

    /**
     * Returns the Header Object.
     *
     * @param title the title of the Content Description Object
     * @param streams the streams to declare
     * @param packetCount the number of data packets
     * @param lastMillis the presentation time of the last media object, without the preroll
     * @return the object
     */
    private static byte[] header(String title, List<Stream> streams, long packetCount, long lastMillis) {
        var children = new ArrayList<byte[]>();
        var play = (lastMillis + 40 + PREROLL) * 10_000;
        var properties = le(80);
        properties.put(FILE_ID).putLong(0).putLong(0).putLong(packetCount).putLong(play)
                .putLong(play - PREROLL * 10_000L).putLong(PREROLL).putInt(2).putInt(PACKET_SIZE).putInt(PACKET_SIZE)
                .putInt(4_000_000);
        children.add(object(FILE_PROPERTIES, properties.array()));
        children.add(headerExtension(streams));
        for (var stream : streams) {
            children.add(streamProperties(stream));
        }
        var encodedTitle = utf16z(title);
        var description = le(10 + encodedTitle.length);
        description.putShort((short) encodedTitle.length).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).put(encodedTitle);
        children.add(object(CONTENT_DESCRIPTION, description.array()));
        var body = concat(children);
        var out = le(30 + body.length);
        out.put(HEADER).putLong(30 + body.length).putInt(children.size()).put((byte) 1).put((byte) 2).put(body);
        return out.array();
    }

    /**
     * Returns the Header Extension Object: a Language List of one language and an Extended Stream Properties Object
     * per stream, every stream in that language.
     *
     * @param streams the streams to describe
     * @return the object
     */
    private static byte[] headerExtension(List<Stream> streams) {
        var children = new ArrayList<byte[]>();
        var language = utf16z("en-us");
        var languages = le(3 + language.length);
        languages.putShort((short) 1).put((byte) language.length).put(language);
        children.add(object(LANGUAGE_LIST, languages.array()));
        for (var stream : streams) {
            var body = le(64);
            body.putLong(0).putLong(0).putInt(1_000_000).putInt(1000).putInt(1000).putInt(0).putInt(0).putInt(0)
                    .putInt(0).putInt(2).putShort((short) stream.number()).putShort((short) 0)
                    .putLong(stream.averageTimePerFrame()).putShort((short) 0).putShort((short) 0);
            children.add(object(EXTENDED_STREAM_PROPERTIES, body.array()));
        }
        var body = concat(children);
        var out = le(22 + body.length);
        out.put(HEADER_EXTENSION_RESERVED).putShort((short) 6).putInt(body.length).put(body);
        return object(HEADER_EXTENSION, out.array());
    }

    /**
     * Returns the Stream Properties Object of a stream.
     *
     * @param stream the stream to declare
     * @return the object
     */
    private static byte[] streamProperties(Stream stream) {
        byte[] correction;
        if (stream.span() > 1) {
            var spread = le(8);
            spread.put((byte) stream.span()).putShort((short) stream.spanPacket()).putShort((short) stream.spanChunk())
                    .putShort((short) 1).put((byte) 0);
            correction = spread.array();
        } else {
            correction = new byte[0];
        }
        var body = le(54 + stream.typeData().length + correction.length);
        body.put(stream.video() ? VIDEO_MEDIA : AUDIO_MEDIA).put(stream.span() > 1 ? AUDIO_SPREAD : NO_ERROR_CORRECTION)
                .putLong(0).putInt(stream.typeData().length).putInt(correction.length)
                .putShort((short) stream.number()).putInt(0).put(stream.typeData()).put(correction);
        return object(STREAM_PROPERTIES, body.array());
    }

    /**
     * A payload waiting for a packet.
     *
     * @param stream the stream number
     * @param key whether its object is a key frame
     * @param objectNumber the media object number
     * @param offset the offset of the payload in its object, or the presentation time of a compressed payload
     * @param replicated the replicated data
     * @param data the payload data
     */
    private record Payload(int stream, boolean key, int objectNumber, int offset, byte[] replicated, byte[] data) {
        /**
         * Returns the size of the payload in a packet.
         *
         * @return the size, header included
         */
        int size() {
            return PAYLOAD_HEADER + replicated.length + 2 + data.length;
        }
    }

    /**
     * Lays the media objects out as payloads and the payloads out as data packets.
     *
     * @param streams the streams of the objects
     * @param objects the media objects
     * @param indexed the number of the stream the Simple Index covers, or -1
     * @param keys receives, for every key frame of the indexed stream, its time in milliseconds, its first packet
     * and the number of packets it spans
     * @return the packets
     */
    private static List<byte[]> packets(List<Stream> streams, List<MediaObject> objects, int indexed,
                                        List<long[]> keys) {
        var payloads = layout(streams, objects, indexed, keys);
        var packetOf = new int[payloads.size()];
        var packets = new ArrayList<byte[]>();
        var sendTime = 0L;
        var cursor = 0;
        while (cursor < payloads.size()) {
            sendTime = Math.max(sendTime, sendTime(payloads.get(cursor)));
            var body = new ByteArrayOutputStream(PACKET_SIZE);
            var used = PACKET_HEADER;
            var count = 0;
            while (cursor < payloads.size() && count < 63 && used + payloads.get(cursor).size() <= PACKET_SIZE) {
                var payload = payloads.get(cursor);
                body.write(payload.stream() | (payload.key() ? 0x80 : 0));
                body.write(payload.objectNumber());
                body.writeBytes(le(4).putInt(payload.offset()).array());
                body.write(payload.replicated().length);
                body.writeBytes(payload.replicated());
                body.writeBytes(le(2).putShort((short) payload.data().length).array());
                body.writeBytes(payload.data());
                packetOf[cursor] = packets.size();
                used += payload.size();
                count++;
                cursor++;
            }
            if (count == 0) {
                throw new IllegalStateException("payload larger than a packet");
            }
            var packet = le(PACKET_SIZE);
            // error correction present with two bytes of data; multiple payloads; a word of padding length; byte
            // replicated data lengths, dword offsets, byte object numbers and byte stream numbers
            packet.put((byte) 0x82).put((byte) 0).put((byte) 0).put((byte) 0x11).put((byte) 0x5D)
                    .putShort((short) (PACKET_SIZE - used)).putInt((int) sendTime).putShort((short) 40)
                    .put((byte) (count | 2 << 6)).put(body.toByteArray());
            packets.add(packet.array());
        }
        for (var key : keys) {
            var first = packetOf[(int) key[1]];
            key[2] = packetOf[(int) key[2]] - first + 1;
            key[1] = first;
        }
        return packets;
    }

    /**
     * Returns the earliest time a payload may be sent: the presentation time of its object, without the preroll.
     *
     * @param payload a payload of a media object or a compressed payload
     * @return the time in milliseconds
     */
    private static long sendTime(Payload payload) {
        if (payload.replicated().length == 1) {
            return payload.offset() - PREROLL;
        }
        return ByteBuffer.wrap(payload.replicated()).order(ByteOrder.LITTLE_ENDIAN).getInt(4) - PREROLL;
    }

    /**
     * Splits the media objects into payloads: runs of up to eight objects of at most 255 bytes of one stream at a
     * constant distance become compressed payloads; other objects are interleaved when their stream spreads them and
     * split into payloads that fit a packet.
     *
     * @param streams the streams of the objects
     * @param objects the media objects
     * @param indexed the number of the stream the Simple Index covers, or -1
     * @param keys receives, for every key frame of the indexed stream, its time in milliseconds and the indices of
     * its first and last payloads
     * @return the payloads, in order
     */
    private static List<Payload> layout(List<Stream> streams, List<MediaObject> objects, int indexed,
                                        List<long[]> keys) {
        var numbers = new int[128];
        var payloads = new ArrayList<Payload>();
        for (var index = 0; index < objects.size(); index++) {
            var object = objects.get(index);
            var stream = streams.stream().filter(candidate -> candidate.number() == object.stream()).findFirst()
                    .orElseThrow();
            var number = numbers[object.stream()]++ & 0xFF;
            var run = run(objects, index);
            if (run > 1) {
                var sub = new ByteArrayOutputStream();
                for (var member = index; member < index + run; member++) {
                    sub.write(objects.get(member).data().length);
                    sub.writeBytes(objects.get(member).data());
                }
                var delta = (int) (objects.get(index + 1).millis() - object.millis());
                payloads.add(new Payload(object.stream(), object.key(), number, (int) (object.millis() + PREROLL),
                        new byte[]{(byte) delta}, sub.toByteArray()));
                numbers[object.stream()] += run - 1;
                index += run - 1;
                continue;
            }
            var data = object.data();
            if (stream.interleaves(data.length)) {
                data = scramble(data, stream.span(), stream.spanPacket() / stream.spanChunk(), stream.spanChunk());
            }
            var firstPayload = payloads.size();
            var replicated = le(8).putInt(data.length).putInt((int) (object.millis() + PREROLL)).array();
            var budget = PACKET_SIZE - PACKET_HEADER - PAYLOAD_HEADER - replicated.length - 2;
            for (var offset = 0; offset < data.length; offset += budget) {
                payloads.add(new Payload(object.stream(), object.key(), number, offset, replicated,
                        Arrays.copyOfRange(data, offset, Math.min(data.length, offset + budget))));
            }
            if (object.stream() == indexed && object.key()) {
                keys.add(new long[]{object.millis(), firstPayload, payloads.size() - 1});
            }
        }
        return payloads;
    }

    /**
     * Returns the number of media objects from an index that form a compressed payload.
     *
     * @param objects the media objects
     * @param start the index of the first
     * @return the length of the run, 1 when the object is not compressed
     */
    private static int run(List<MediaObject> objects, int start) {
        var first = objects.get(start);
        if (first.data().length > 255 || start + 1 >= objects.size()) {
            return 1;
        }
        var delta = objects.get(start + 1).millis() - first.millis();
        var bytes = 1 + first.data().length;
        var run = 1;
        while (start + run < objects.size() && run < 8) {
            var next = objects.get(start + run);
            var previous = objects.get(start + run - 1);
            if (next.stream() != first.stream() || next.key() != first.key() || next.data().length > 255
                    || next.millis() - previous.millis() != delta || delta <= 0 || delta > 255
                    || PACKET_HEADER + PAYLOAD_HEADER + 1 + 2 + bytes + 1 + next.data().length > PACKET_SIZE) {
                break;
            }
            bytes += 1 + next.data().length;
            run++;
        }
        return run;
    }

    /**
     * Stores an object interleaved by audio spread: the chunk at index {@code i} of the original order is stored at
     * index {@code (i % span) * chunks + i / span}.
     *
     * @param object the object, {@code span * chunks * chunkSize} bytes
     * @param span the number of virtual packets
     * @param chunks the chunks per virtual packet
     * @param chunkSize the size of a chunk
     * @return the stored object
     */
    static byte[] scramble(byte[] object, int span, int chunks, int chunkSize) {
        var result = new byte[object.length];
        for (var index = 0; index < span * chunks; index++) {
            var stored = (index % span) * chunks + index / span;
            System.arraycopy(object, index * chunkSize, result, stored * chunkSize, chunkSize);
        }
        return result;
    }

    /**
     * Returns the Simple Index Object: for every interval, the first packet and packet count of the last key frame
     * of the indexed stream presented at or before it. The entries are on the time line of the presentation times,
     * which include the preroll, so entry {@code i} names the last key frame at or before
     * {@code i * INDEX_INTERVAL - PREROLL} milliseconds of the presentation.
     *
     * @param keys the key frames of the indexed stream: time without the preroll, first packet, packet count
     * @param lastMillis the presentation time of the last media object, without the preroll
     * @param packetCount the number of packets
     * @return the object
     */
    private static byte[] simpleIndex(List<long[]> keys, long lastMillis, long packetCount) {
        var entries = (int) ((lastMillis + PREROLL) / INDEX_INTERVAL) + 1;
        var maxCount = keys.stream().mapToLong(key -> key[2]).max().orElse(1);
        var body = le(32 + 6 * entries);
        body.put(FILE_ID).putLong(INDEX_INTERVAL * 10_000L).putInt((int) maxCount).putInt(entries);
        for (var entry = 0; entry < entries; entry++) {
            var time = (long) entry * INDEX_INTERVAL - PREROLL;
            long[] chosen = null;
            for (var key : keys) {
                if (key[0] <= time) {
                    chosen = key;
                }
            }
            body.putInt(chosen == null ? 0 : (int) chosen[1])
                    .putShort((short) (chosen == null ? Math.min(1, packetCount) : chosen[2]));
        }
        return object(SIMPLE_INDEX, body.array());
    }

    /**
     * Returns an object: its GUID, its size and its body.
     *
     * @param guid the GUID
     * @param body the bytes after the size field
     * @return the object
     */
    private static byte[] object(byte[] guid, byte[] body) {
        return le(24 + body.length).put(guid).putLong(24 + body.length).put(body).array();
    }

    /**
     * Joins byte arrays.
     *
     * @param parts the arrays
     * @return the joined array
     */
    private static byte[] concat(List<byte[]> parts) {
        var out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /**
     * Returns a string as null-terminated UTF-16LE.
     *
     * @param text the string
     * @return the bytes
     */
    private static byte[] utf16z(String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_16LE);
        return Arrays.copyOf(bytes, bytes.length + 2);
    }

    /**
     * Returns a little-endian buffer.
     *
     * @param size the capacity in bytes
     * @return the buffer
     */
    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Returns the bytes of a GUID as ASF stores it: the first three fields little endian, the rest as written.
     *
     * @param text the GUID, as {@code XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}
     * @return the 16 bytes
     */
    private static byte[] guid(String text) {
        var hex = text.replace("-", "");
        var bytes = new byte[16];
        for (var index = 0; index < 16; index++) {
            bytes[index] = (byte) Integer.parseInt(hex, index * 2, index * 2 + 2, 16);
        }
        var out = le(16);
        out.putInt(ByteBuffer.wrap(bytes, 0, 4).getInt()).putShort(ByteBuffer.wrap(bytes, 4, 2).getShort())
                .putShort(ByteBuffer.wrap(bytes, 6, 2).getShort()).put(bytes, 8, 8);
        return out.array();
    }
}
