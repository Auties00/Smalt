package com.github.auties00.smalt.container;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.CRC32;

/**
 * Runs ffprobe on a container and parses its packet and stream listing, and reads every packet of
 * one of our demuxers, so tests can compare the two packet by packet.
 */
public final class ContainerProbe {
    private ContainerProbe() {
        throw new AssertionError();
    }

    /** One packet as ffprobe reports it; timestamps are {@link Packet#NO_TIMESTAMP} when N/A. */
    public record ProbePacket(int stream, long pts, long dts, long duration, int size, boolean key, boolean discard,
                              String hash) {
    }

    /** One stream as ffprobe reports it, with every field kept as text. */
    public record ProbeStream(int index, Map<String, String> fields, byte[] extradata) {
        public String get(String key) {
            return fields.get(key);
        }

        public String codecName() {
            return fields.get("codec_name");
        }

        public long timeBaseDenominator() {
            var value = fields.get("time_base");
            return Long.parseLong(value.substring(value.indexOf('/') + 1));
        }

        public long timeBaseNumerator() {
            var value = fields.get("time_base");
            return Long.parseLong(value.substring(0, value.indexOf('/')));
        }

        public Integer rotation() {
            for (var entry : fields.entrySet()) {
                if (entry.getKey().endsWith("rotation")) {
                    return (int) Math.round(Double.parseDouble(entry.getValue()));
                }
            }
            return null;
        }
    }

    /** The whole probe. */
    public record Probe(List<ProbeStream> streams, List<ProbePacket> packets, Map<String, String> format) {
        public List<ProbePacket> packetsOf(int stream) {
            return packets.stream().filter(packet -> packet.stream() == stream).toList();
        }
    }

    /** Runs ffprobe on a file. */
    public static Probe probe(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-show_entries", "packet=stream_index,pts,dts,duration,size,flags,data_hash:packet_side_data=",
                "-show_entries", "stream:stream_tags:stream_side_data=rotation",
                "-show_entries", "format:format_tags",
                "-show_data_hash", "CRC32", "-show_data", "-of", "compact", file.toString()));
        var streams = new ArrayList<ProbeStream>();
        var packets = new ArrayList<ProbePacket>();
        var format = new LinkedHashMap<String, String>();
        for (var line : new String(output, StandardCharsets.UTF_8).split("\r?\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var fields = split(line);
            var section = fields.getFirst();
            var values = new LinkedHashMap<String, String>();
            for (var field : fields.subList(1, fields.size())) {
                var equals = field.indexOf('=');
                if (equals > 0) {
                    values.put(field.substring(0, equals), field.substring(equals + 1));
                }
            }
            switch (section) {
                case "packet" -> {
                    var flags = values.getOrDefault("flags", "");
                    packets.add(new ProbePacket(Integer.parseInt(values.get("stream_index")), timestamp(values.get("pts")),
                            timestamp(values.get("dts")), timestamp(values.get("duration")),
                            Integer.parseInt(values.get("size")), flags.indexOf('K') >= 0, flags.indexOf('D') >= 0,
                            values.get("data_hash")));
                }
                case "stream" -> {
                    var extradata = values.containsKey("extradata") ? hexdump(values.get("extradata")) : new byte[0];
                    streams.add(new ProbeStream(Integer.parseInt(values.get("index")), values, extradata));
                }
                case "format" -> format.putAll(values);
                default -> {
                }
            }
        }
        return new Probe(streams, packets, format);
    }

    /** Splits a compact line on unescaped '|' and resolves the escapes. */
    private static List<String> split(String line) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        for (var index = 0; index < line.length(); index++) {
            var c = line.charAt(index);
            if (c == '\\' && index + 1 < line.length()) {
                var next = line.charAt(++index);
                current.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
            } else if (c == '|') {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result;
    }

    private static long timestamp(String value) {
        return value == null || value.equals("N/A") ? Packet.NO_TIMESTAMP : Long.parseLong(value);
    }

    /** Parses ffprobe's hexdump: lines of "offset: hex groups  ascii". */
    private static byte[] hexdump(String dump) {
        var out = new ByteArrayOutputStream();
        for (var line : dump.split("\n")) {
            var colon = line.indexOf(": ");
            if (colon < 0) {
                continue;
            }
            var hex = line.substring(colon + 2, Math.min(line.length(), colon + 2 + 40)).replace(" ", "");
            out.writeBytes(HexFormat.of().parseHex(hex.length() % 2 == 0 ? hex : hex.substring(0, hex.length() - 1)));
        }
        return out.toByteArray();
    }

    /** Returns ffprobe's CRC32 notation of some bytes. */
    public static String crc32(byte[] data) {
        var crc = new CRC32();
        crc.update(data);
        return String.format("CRC32:%08x", crc.getValue());
    }

    /** Everything a demuxer produced. */
    public record Demuxed(List<Track> tracks, Map<Integer, List<Packet>> packets, List<Packet> order,
                          Map<String, String> metadata, long durationMicros) {
        public List<Packet> packetsOf(Track track) {
            return packets.getOrDefault(track.id(), List.of());
        }
    }

    /** Opens a file with a demuxer and reads every packet. */
    public static Demuxed demux(Path file, Function<MediaSource, Demuxer> factory) {
        try (var source = MediaSource.of(file); var demuxer = factory.apply(source)) {
            return demuxAll(demuxer);
        }
    }

    /** Reads every packet of an open demuxer. */
    public static Demuxed demuxAll(Demuxer demuxer) {
        var packets = new LinkedHashMap<Integer, List<Packet>>();
        var order = new ArrayList<Packet>();
        for (var track : demuxer.tracks()) {
            packets.put(track.id(), new ArrayList<>());
        }
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            order.add(packet);
            packets.computeIfAbsent(packet.trackId(), key -> new ArrayList<>()).add(packet);
        }
        return new Demuxed(demuxer.tracks(), packets, order, demuxer.metadata(), demuxer.durationMicros());
    }
}
