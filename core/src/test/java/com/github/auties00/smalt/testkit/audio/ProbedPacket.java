package com.github.auties00.smalt.testkit.audio;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * One packet as ffprobe reports it: timestamps in the stream time base, size and the CRC-32 of the
 * payload (ffprobe's {@code CRC32} hash is the zlib CRC-32, the same as {@link CRC32}).
 */
public record ProbedPacket(long pts, long duration, int size, long crc32) {
    /** The marker ffprobe prints for an absent timestamp. */
    public static final long NONE = Long.MIN_VALUE;

    /**
     * Runs ffprobe on the first audio stream of a file.
     */
    public static List<ProbedPacket> probe(Path file) {
        return probe(file, "a:0");
    }

    /**
     * Runs ffprobe on the selected stream of a file.
     */
    public static List<ProbedPacket> probe(Path file, String stream) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-select_streams", stream, "-show_entries", "packet=pts,duration,size,data_hash",
                "-show_data_hash", "CRC32", "-of", "compact=p=0:nk=0", file.toString())), StandardCharsets.UTF_8);
        var packets = new ArrayList<ProbedPacket>();
        for (var line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            long pts = NONE;
            long duration = 0;
            var size = -1;
            var crc = -1L;
            for (var field : line.split("\\|")) {
                var separator = field.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                var key = field.substring(0, separator);
                var value = field.substring(separator + 1).trim();
                switch (key) {
                    case "pts" -> pts = value.equals("N/A") ? NONE : Long.parseLong(value);
                    case "duration" -> duration = value.equals("N/A") ? 0 : Long.parseLong(value);
                    case "size" -> size = Integer.parseInt(value);
                    case "data_hash" -> crc = Long.parseLong(value.substring(value.indexOf(':') + 1), 16);
                    default -> {
                    }
                }
            }
            packets.add(new ProbedPacket(pts, duration, size, crc));
        }
        return packets;
    }

    /**
     * Returns the zlib CRC-32 of a packet payload.
     */
    public static long crc32(byte[] data) {
        var crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Returns the probed form of one of our packets, for direct comparison.
     */
    public static ProbedPacket of(Packet packet) {
        return new ProbedPacket(packet.pts() == Packet.NO_TIMESTAMP ? NONE : packet.pts(), packet.duration(),
                packet.size(), crc32(packet.data()));
    }
}
