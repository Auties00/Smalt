package com.github.auties00.smalt.testkit.fuzz;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Converts media files to seeds of the packet decoder targets, for {@code tools/fuzz/fuzz.py}: every audio and
 * video track of every file Smalt demuxes becomes one {@link FuzzPackets} input, written to {@code
 * <output>/<codec>/<file>-<track>.bin}.
 *
 * <p>Usage: {@code FuzzSeedConverter <output directory> <max bytes> <max packets> <file>...}. A track keeps its
 * packets from the start until the input would exceed the byte cap or the packet cap; files Smalt cannot demux are
 * skipped. Short seeds keep each execution of a target fast, and a few packets already reach most of a decoder.
 */
public final class FuzzSeedConverter {
    private FuzzSeedConverter() {
        throw new AssertionError();
    }

    /**
     * Converts the files named on the command line.
     *
     * @param args the output directory, the byte cap and the packet cap of one seed, then the media files
     * @throws IOException if a seed cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: FuzzSeedConverter <output directory> <max bytes> <max packets> <file>...");
            System.exit(2);
        }
        var output = Path.of(args[0]);
        var maxBytes = Long.parseLong(args[1]);
        var maxPackets = Math.min(Integer.parseInt(args[2]), Fuzz.MAX_PACKETS);
        var written = 0;
        for (var index = 3; index < args.length; index++) {
            var file = Path.of(args[index]);
            try {
                written += convert(file, output, maxBytes, maxPackets);
            } catch (MediaException | IllegalArgumentException exception) {
                System.err.println("skipped " + file + ": " + exception.getMessage());
            }
        }
        System.out.println("wrote " + written + " seeds");
    }

    private static int convert(Path file, Path output, long maxBytes, int maxPackets) throws IOException {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            var tracks = new HashMap<Integer, Track>();
            var parts = new HashMap<Integer, List<FuzzPackets.Part>>();
            var sizes = new HashMap<Integer, Long>();
            for (var track : demuxer.tracks()) {
                if (track.codec().kind() != TrackKind.DATA) {
                    tracks.put(track.id(), track);
                    parts.put(track.id(), new ArrayList<>());
                    sizes.put(track.id(), (long) track.codecConfig().map(config -> config.length).orElse(0));
                }
            }
            var full = new HashSet<Integer>();
            for (var packet = demuxer.read(); packet != null && full.size() < tracks.size(); packet = demuxer.read()) {
                var list = parts.get(packet.trackId());
                if (list == null || full.contains(packet.trackId())) {
                    continue;
                }
                var size = sizes.get(packet.trackId()) + packet.size() + FuzzPackets.MARKER.length + 1;
                if (size > maxBytes || list.size() >= maxPackets) {
                    full.add(packet.trackId());
                    continue;
                }
                sizes.put(packet.trackId(), size);
                list.add(new FuzzPackets.Part(packet.data(), packet.keyframe()));
            }
            var written = 0;
            for (var track : tracks.values()) {
                var directory = output.resolve(track.codec().name().toLowerCase(Locale.ROOT));
                Files.createDirectories(directory);
                var name = file.getFileName() + "-" + track.id() + ".bin";
                Files.write(directory.resolve(name), FuzzSeeds.packets(track, 0, parts.get(track.id())));
                written++;
            }
            return written;
        }
    }
}
