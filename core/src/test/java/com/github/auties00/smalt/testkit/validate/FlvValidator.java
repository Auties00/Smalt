package com.github.auties00.smalt.testkit.validate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a Flash Video file (FLV specification 10.1 Annex E, with the Enhanced RTMP v2 headers) against its
 * specification, independently of Smalt's demuxer: the file header (signature, version 1, reserved type flags zero,
 * a data offset of 9 and a zero first back-pointer), every tag (reserved bits and filter zero, an audio, video or
 * script data type, a stream identifier of zero, a body within the file and a back-pointer equal to its size plus
 * 11), the type flags against the tags present, the legacy and enhanced audio and video headers (packet types,
 * {@code ModEx} and multitrack sizes that end within the tag, known FourCCs), a sequence start before the first
 * frame of every stream whose codec needs one (an {@code AVCDecoderConfigurationRecord} of version 1, an AAC
 * {@code AudioSpecificConfig} of at least two bytes, an {@code OpusHead}, a FLAC {@code fLaC} header), timestamps that
 * never decrease within a stream, and the first script data tag: an {@code onMetaData} at timestamp 0 whose AMF0
 * values parse, whose {@code filesize} is the file size and {@code duration} covers the last timestamp, and whose
 * {@code keyframes} index, when present, has as many times as positions, each position the start of a key frame
 * tag of the default video stream at that time.
 */
public final class FlvValidator {
    /** What validation learned of a file. */
    public record Summary(int tags, int audioStreams, int videoStreams, int keyframesIndexed,
                          Map<String, Object> metadata) {
    }

    private final byte[] data;
    private final Findings findings;
    private final Map<Integer, Stream> streams = new LinkedHashMap<>();
    private final Map<Long, Integer> videoKeyTags = new HashMap<>();
    private Map<String, Object> metadata;
    private long lastTimestamp = Long.MIN_VALUE;

    private FlvValidator(byte[] data, Findings findings) {
        this.data = data;
        this.findings = findings;
    }

    /** The state of one elementary stream. */
    private static final class Stream {
        final String name;
        boolean configured;
        boolean needsConfig;
        long lastTimestamp = Long.MIN_VALUE;

        Stream(String name) {
            this.name = name;
        }
    }

    /**
     * Validates a file.
     *
     * @param data the file
     * @param findings receives the problems found
     * @return what validation learned of the file
     */
    public static Summary validate(byte[] data, Findings findings) {
        return new FlvValidator(data, findings).run();
    }

    private Summary run() {
        if (data.length < 13 || data[0] != 'F' || data[1] != 'L' || data[2] != 'V') {
            findings.add("header", "missing the FLV signature");
            return new Summary(0, 0, 0, 0, Map.of());
        }
        findings.require(data[3] == 1, "header", "version " + data[3] + " is not 1");
        var flags = data[4] & 0xFF;
        findings.require((flags & 0xFA) == 0, "header", "reserved type flags set: " + flags);
        findings.require(u32(5) == 9, "header", "data offset " + u32(5) + " is not 9");
        findings.require(u32(9) == 0, "header", "PreviousTagSize0 " + u32(9) + " is not 0");
        var position = 13L;
        var tags = 0;
        var sawAudio = false;
        var sawVideo = false;
        var firstScript = true;
        while (position < data.length) {
            var where = "tag at " + position;
            if (position + 11 > data.length) {
                findings.add(where, "truncated tag header");
                break;
            }
            var first = data[(int) position] & 0xFF;
            var type = first & 0x1F;
            var size = u24(position + 1);
            var timestamp = (long) (u24(position + 4) | (data[(int) position + 7] << 24));
            findings.require((first & 0xE0) == 0, where, "reserved or filter bits set: " + first);
            findings.require(type == 8 || type == 9 || type == 18, where, "tag type " + type);
            findings.require(u24(position + 8) == 0, where, "stream id " + u24(position + 8) + " is not 0");
            var body = position + 11;
            if (body + size + 4 > data.length) {
                findings.add(where, "body of " + size + " bytes and its back-pointer run past the end of the file");
                break;
            }
            findings.require(u32(body + size) == size + 11L, where, "back-pointer " + u32(body + size)
                    + " is not the tag size " + (size + 11));
            tags++;
            switch (type) {
                case 8 -> {
                    sawAudio = true;
                    audio(where, (int) body, (int) (body + size), timestamp);
                }
                case 9 -> {
                    sawVideo = true;
                    video(where, position, (int) body, (int) (body + size), timestamp);
                }
                case 18 -> {
                    if (firstScript) {
                        firstScript = false;
                        script(where, (int) body, (int) (body + size), timestamp);
                    }
                }
                default -> {
                }
            }
            if (type != 18) {
                lastTimestamp = Math.max(lastTimestamp, timestamp);
            }
            position = body + size + 4;
        }
        findings.require(((flags & 4) != 0) == sawAudio, "header", "audio flag " + ((flags & 4) != 0)
                + " but audio tags " + (sawAudio ? "present" : "absent"));
        findings.require(((flags & 1) != 0) == sawVideo, "header", "video flag " + ((flags & 1) != 0)
                + " but video tags " + (sawVideo ? "present" : "absent"));
        var indexed = 0;
        if (metadata != null) {
            if (metadata.get("filesize") instanceof Double fileSize && fileSize > 0) {
                findings.require(fileSize == data.length, "onMetaData", "filesize " + fileSize + " is not "
                        + data.length);
            }
            if (metadata.get("duration") instanceof Double duration && duration > 0 && lastTimestamp >= 0) {
                findings.require(duration * 1000 + 1 >= lastTimestamp, "onMetaData", "duration " + duration
                        + " s ends before the last timestamp " + lastTimestamp + " ms");
            }
            indexed = keyframes();
        }
        var audioStreams = 0;
        var videoStreams = 0;
        for (var entry : streams.entrySet()) {
            if ((entry.getKey() & 0x100) != 0) {
                videoStreams++;
            } else {
                audioStreams++;
            }
        }
        return new Summary(tags, audioStreams, videoStreams, indexed, metadata == null ? Map.of() : metadata);
    }

    private int keyframes() {
        if (!(metadata.get("keyframes") instanceof Map<?, ?> keyframes)) {
            return 0;
        }
        if (!(keyframes.get("times") instanceof List<?> times)
                || !(keyframes.get("filepositions") instanceof List<?> positions)) {
            findings.add("onMetaData keyframes", "missing the times or filepositions array");
            return 0;
        }
        findings.require(times.size() == positions.size(), "onMetaData keyframes", times.size() + " times but "
                + positions.size() + " positions");
        var count = Math.min(times.size(), positions.size());
        for (var index = 0; index < count; index++) {
            if (!(times.get(index) instanceof Double time) || !(positions.get(index) instanceof Double offset)) {
                findings.add("onMetaData keyframes " + index, "not a number");
                continue;
            }
            var at = (long) (double) offset;
            var tagTime = videoKeyTags.get(at);
            if (tagTime == null) {
                findings.add("onMetaData keyframes " + index, "position " + at + " is not a key frame tag of the"
                        + " default video stream");
            } else {
                findings.require(Math.abs(tagTime - time * 1000) <= 1, "onMetaData keyframes " + index, "time "
                        + time + " s but the tag is at " + tagTime + " ms");
            }
        }
        return count;
    }

    private void script(String where, int body, int end, long timestamp) {
        var reader = new Amf(body, end);
        try {
            var name = reader.value(0);
            if (!"onMetaData".equals(name)) {
                return;
            }
            findings.require(timestamp == 0, where, "onMetaData at timestamp " + timestamp);
            var value = reader.value(0);
            if (value instanceof Map<?, ?> map) {
                var object = new LinkedHashMap<String, Object>();
                map.forEach((key, entry) -> object.put((String) key, entry));
                metadata = object;
            } else {
                findings.add(where, "onMetaData argument is not an object or ECMA array");
            }
        } catch (IllegalStateException exception) {
            findings.add(where, "onMetaData: " + exception.getMessage());
        }
    }

    private void audio(String where, int body, int end, long timestamp) {
        if (body == end) {
            return;
        }
        var first = data[body] & 0xFF;
        var format = first >>> 4;
        if (format != 9) {
            findings.require(format != 12 && format != 13, where, "reserved sound format " + format);
            var stream = stream(0, "audio track 0 (legacy format " + format + ")");
            if (format == 10) {
                if (end - body < 2) {
                    findings.add(where, "AAC tag without a packet type");
                    return;
                }
                var packetType = data[body + 1] & 0xFF;
                stream.needsConfig = true;
                if (packetType == 0) {
                    findings.require(end - body - 2 >= 2, where, "AAC sequence header shorter than an"
                            + " AudioSpecificConfig");
                    stream.configured = true;
                    return;
                }
                findings.require(packetType == 1, where, "AAC packet type " + packetType);
            }
            frame(where, stream, timestamp);
            return;
        }
        try {
            var at = new int[]{body + 1};
            var packetType = modEx(at, end, first & 15);
            var multitrack = packetType == 5;
            var multitrackType = 0;
            var fourCc = 0;
            if (multitrack) {
                var next = u8(at, end);
                multitrackType = next >>> 4;
                packetType = next & 15;
                findings.require(multitrackType <= 2, where, "multitrack type " + multitrackType);
                findings.require(packetType != 5, where, "nested multitrack packet type");
                if (multitrackType != 2) {
                    fourCc = fourCc(at, end);
                }
            } else {
                fourCc = fourCc(at, end);
            }
            while (true) {
                var trackId = 0;
                var trackEnd = end;
                if (multitrack) {
                    if (multitrackType == 2) {
                        fourCc = fourCc(at, end);
                    }
                    trackId = u8(at, end);
                    if (multitrackType != 0) {
                        trackEnd = at[0] + 3 + u24checked(at, end);
                        if (trackEnd > end) {
                            findings.add(where, "multitrack size runs past the tag");
                            return;
                        }
                    }
                }
                var name = new String(ByteBuffer.allocate(4).putInt(fourCc).array(), StandardCharsets.ISO_8859_1);
                findings.require(List.of("mp4a", "Opus", "fLaC", ".mp3", "ac-3", "ec-3").contains(name), where,
                        "unknown audio FourCC " + name);
                var stream = stream(trackId, "audio track " + trackId + " (" + name + ")");
                stream.needsConfig |= name.equals("mp4a") || name.equals("fLaC");
                switch (packetType) {
                    case 0 -> {
                        stream.configured = true;
                        if (name.equals("Opus") && trackEnd - at[0] > 0) {
                            findings.require(starts(at[0], trackEnd, "OpusHead"), where, "Opus sequence start is not"
                                    + " an OpusHead");
                        }
                        if (name.equals("fLaC")) {
                            findings.require(starts(at[0], trackEnd, "fLaC"), where, "FLAC sequence start lacks fLaC");
                        }
                        if (name.equals("mp4a")) {
                            findings.require(trackEnd - at[0] >= 2, where, "AAC sequence start is too short");
                        }
                    }
                    case 1 -> frame(where, stream, timestamp);
                    case 2, 4 -> {
                    }
                    default -> findings.add(where, "audio packet type " + packetType);
                }
                at[0] = trackEnd;
                if (!multitrack || multitrackType == 0 || at[0] >= end) {
                    return;
                }
            }
        } catch (IllegalStateException exception) {
            findings.add(where, exception.getMessage());
        }
    }

    private void video(String where, long tag, int body, int end, long timestamp) {
        if (body == end) {
            findings.add(where, "empty video tag");
            return;
        }
        var first = data[body] & 0xFF;
        if ((first & 0x80) == 0) {
            var frameType = first >>> 4;
            var codecId = first & 15;
            findings.require(frameType >= 1 && frameType <= 5, where, "frame type " + frameType);
            findings.require(codecId >= 2 && codecId <= 7, where, "legacy video codec " + codecId);
            if (frameType == 5) {
                return;
            }
            var stream = stream(0x100, "video track 0 (legacy codec " + codecId + ")");
            if (codecId == 7) {
                stream.needsConfig = true;
                if (end - body < 5) {
                    findings.add(where, "AVC tag shorter than its header");
                    return;
                }
                var packetType = data[body + 1] & 0xFF;
                var composition = u24(body + 2);
                if (packetType == 0) {
                    findings.require(composition == 0, where, "AVC sequence header with a composition time");
                    findings.require(end - body > 5 && data[body + 5] == 1, where, "AVC sequence header is not an"
                            + " AVCDecoderConfigurationRecord of version 1");
                    stream.configured = true;
                    return;
                }
                if (packetType == 2) {
                    return;
                }
                findings.require(packetType == 1, where, "AVC packet type " + packetType);
            }
            if (frameType == 1) {
                videoKeyTags.put(tag, (int) timestamp);
            }
            frame(where, stream, timestamp);
            return;
        }
        try {
            var frameType = (first >>> 4) & 7;
            findings.require(frameType >= 1 && frameType <= 5, where, "frame type " + frameType);
            var at = new int[]{body + 1};
            var packetType = modEx(at, end, first & 15);
            if (packetType != 4 && frameType == 5) {
                return;
            }
            var multitrack = packetType == 6;
            var multitrackType = 0;
            var fourCc = 0;
            if (multitrack) {
                var next = u8(at, end);
                multitrackType = next >>> 4;
                packetType = next & 15;
                findings.require(multitrackType <= 2, where, "multitrack type " + multitrackType);
                findings.require(packetType != 6, where, "nested multitrack packet type");
                if (multitrackType != 2) {
                    fourCc = fourCc(at, end);
                }
            } else {
                fourCc = fourCc(at, end);
            }
            while (true) {
                var trackId = 0;
                var trackEnd = end;
                if (multitrack) {
                    if (multitrackType == 2) {
                        fourCc = fourCc(at, end);
                    }
                    trackId = u8(at, end);
                    if (multitrackType != 0) {
                        trackEnd = at[0] + 3 + u24checked(at, end);
                        if (trackEnd > end) {
                            findings.add(where, "multitrack size runs past the tag");
                            return;
                        }
                    }
                }
                var name = new String(ByteBuffer.allocate(4).putInt(fourCc).array(), StandardCharsets.ISO_8859_1);
                findings.require(List.of("avc1", "hvc1", "av01", "vp09", "vp08", "vvc1").contains(name), where,
                        "unknown video FourCC " + name);
                var stream = stream(0x100 | trackId, "video track " + trackId + " (" + name + ")");
                stream.needsConfig |= name.equals("avc1") || name.equals("hvc1") || name.equals("av01");
                switch (packetType) {
                    case 0 -> {
                        stream.configured = true;
                        if (name.equals("av01")) {
                            findings.require(trackEnd > at[0] && (data[at[0]] & 0xFF) == 0x81, where,
                                    "AV1 sequence start is not an AV1CodecConfigurationRecord");
                        }
                        if (name.equals("hvc1") || name.equals("avc1")) {
                            findings.require(trackEnd > at[0] && data[at[0]] == 1, where,
                                    "sequence start is not a decoder configuration record of version 1");
                        }
                    }
                    case 1, 3 -> {
                        if (trackId == 0 && frameType == 1) {
                            videoKeyTags.put(tag, (int) timestamp);
                        }
                        frame(where, stream, timestamp);
                    }
                    case 2, 4, 5 -> {
                    }
                    default -> findings.add(where, "video packet type " + packetType);
                }
                at[0] = trackEnd;
                if (!multitrack || multitrackType == 0 || at[0] >= end) {
                    return;
                }
            }
        } catch (IllegalStateException exception) {
            findings.add(where, exception.getMessage());
        }
    }

    private Stream stream(int key, String name) {
        return streams.computeIfAbsent(key, _ -> new Stream(name));
    }

    private void frame(String where, Stream stream, long timestamp) {
        findings.require(!stream.needsConfig || stream.configured, where, stream.name + ": frame before the"
                + " sequence start");
        findings.require(stream.lastTimestamp == Long.MIN_VALUE || timestamp >= stream.lastTimestamp, where,
                stream.name + ": timestamp " + timestamp + " before " + stream.lastTimestamp);
        stream.lastTimestamp = Math.max(stream.lastTimestamp, timestamp);
    }

    private int modEx(int[] at, int end, int packetType) {
        while (packetType == 7) {
            var size = u8(at, end) + 1;
            if (size == 256) {
                size = (u8(at, end) << 8 | u8(at, end)) + 1;
            }
            if (at[0] + size > end) {
                throw new IllegalStateException("ModEx data runs past the tag");
            }
            at[0] += size;
            packetType = u8(at, end) & 15;
        }
        return packetType;
    }

    private boolean starts(int at, int end, String text) {
        if (end - at < text.length()) {
            return false;
        }
        for (var index = 0; index < text.length(); index++) {
            if (data[at + index] != text.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private int u8(int[] at, int end) {
        if (at[0] >= end) {
            throw new IllegalStateException("enhanced header runs past the tag");
        }
        return data[at[0]++] & 0xFF;
    }

    private int fourCc(int[] at, int end) {
        return u8(at, end) << 24 | u8(at, end) << 16 | u8(at, end) << 8 | u8(at, end);
    }

    private int u24checked(int[] at, int end) {
        return u8(at, end) << 16 | u8(at, end) << 8 | u8(at, end);
    }

    private int u24(long at) {
        return (data[(int) at] & 0xFF) << 16 | (data[(int) at + 1] & 0xFF) << 8 | data[(int) at + 2] & 0xFF;
    }

    private long u32(long at) {
        return ByteBuffer.wrap(data, (int) at, 4).getInt() & 0xFFFFFFFFL;
    }

    /** An AMF0 reader written from the FLV specification E.4.4. */
    private final class Amf {
        private int position;
        private final int end;

        Amf(int position, int end) {
            this.position = position;
            this.end = end;
        }

        Object value(int depth) {
            if (depth > 64) {
                throw new IllegalStateException("AMF0 nested too deeply");
            }
            var type = u8();
            return switch (type) {
                case 0 -> Double.longBitsToDouble(u64());
                case 1 -> u8() != 0;
                case 2 -> string(u16());
                case 3 -> properties(depth);
                case 5, 6 -> null;
                case 7 -> {
                    u16();
                    yield null;
                }
                case 8 -> {
                    u32();
                    yield properties(depth);
                }
                case 10 -> {
                    var count = u32();
                    var values = new ArrayList<>();
                    for (var index = 0; index < count; index++) {
                        values.add(value(depth + 1));
                    }
                    yield values;
                }
                case 11 -> {
                    var millis = Double.longBitsToDouble(u64());
                    u16();
                    yield millis;
                }
                case 12 -> string(u32());
                default -> throw new IllegalStateException("AMF0 type " + type);
            };
        }

        private Map<String, Object> properties(int depth) {
            var object = new LinkedHashMap<String, Object>();
            while (true) {
                var name = string(u16());
                if (name.isEmpty() && position < end && data[position] == 9) {
                    position++;
                    return object;
                }
                object.put(name, value(depth + 1));
            }
        }

        private String string(long length) {
            if (position + length > end) {
                throw new IllegalStateException("AMF0 string runs past the tag");
            }
            var text = new String(data, position, (int) length, StandardCharsets.UTF_8);
            position += (int) length;
            return text;
        }

        private int u8() {
            if (position >= end) {
                throw new IllegalStateException("AMF0 value runs past the tag");
            }
            return data[position++] & 0xFF;
        }

        private int u16() {
            return u8() << 8 | u8();
        }

        private long u32() {
            return (long) u16() << 16 | u16();
        }

        private long u64() {
            return u32() << 32 | u32();
        }
    }
}
