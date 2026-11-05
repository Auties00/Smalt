package com.github.auties00.smalt.testkit.container;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.packaging.PackageOutput;
import com.github.auties00.smalt.container.packaging.Packager;
import com.github.auties00.smalt.container.packaging.PackagingEncryption;
import com.github.auties00.smalt.container.packaging.PackagingOptions;
import com.github.auties00.smalt.container.packaging.Rendition;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Packages the committed I/O behavior samples into small HLS and DASH presentations, held in memory, for the I/O
 * behavior harness and the fuzz seeds of the presentation demuxers: an H.264 and AAC clip
 * ({@code samples/mp4/faststart.mp4}) with subtitles ({@code samples/subtitle/cues.*}), in every segment addressing
 * the packager writes. Packaging is deterministic (fixed initialization vectors and program date), so the bytes depend
 * only on the samples and the packager.
 */
public final class PresentationSamples {
    /** The samples directory of core, relative to the module directory. */
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    /**
     * A packaged presentation.
     *
     * @param manifest the name of its master playlist, media playlist or manifest
     * @param files every file written, by name
     */
    public record Sample(String manifest, Map<String, byte[]> files) {
        /** {@return the bytes of the manifest} */
        public byte[] manifestBytes() {
            return files.get(manifest);
        }

        /** {@return the files other than the manifest} */
        public Map<String, byte[]> others() {
            var others = new LinkedHashMap<>(files);
            others.remove(manifest);
            return others;
        }
    }

    private PresentationSamples() {
        throw new AssertionError();
    }

    /**
     * {@return the names of the presentations, each a format and a layout}
     */
    public static List<String> names() {
        return List.of("hls-ts", "hls-ts-ranges", "hls-cmaf", "hls-aes", "hls-iframes", "hls-cmaf-iframes",
                "dash-template", "dash-list", "dash-base", "dash-live");
    }

    /**
     * Packages one presentation.
     *
     * @param name one of {@link #names()}
     * @param maxMicros the media time at which the input is cut, or {@link Long#MAX_VALUE} for all of it
     * @return the presentation
     */
    public static Sample of(String name, long maxMicros) {
        var segment = PackagingOptions.hls().toBuilder().segmentMicros(500_000);
        var options = switch (name) {
            case "hls-ts", "hls-iframes" -> segment.trickPlay(true).build();
            case "hls-ts-ranges" -> segment.byteRanges(true).build();
            case "hls-cmaf", "hls-cmaf-iframes" -> segment.segmentFormat(PackagingOptions.SegmentFormat.CMAF)
                    .trickPlay(true).build();
            case "hls-aes" -> segment.encryption(PackagingEncryption.builder()
                    .method(PackagingEncryption.Method.AES_128).key(bytes(16, 1)).keyUri("clip.key")
                    .initializationVector(bytes(16, 2)).keyFileName("clip.key").build()).build();
            case "dash-template" -> segment.hls(false).dash(true).segmentFormat(PackagingOptions.SegmentFormat.CMAF)
                    .trickPlay(true).build();
            case "dash-list" -> segment.hls(false).dash(true).segmentFormat(PackagingOptions.SegmentFormat.CMAF)
                    .byteRanges(true).build();
            case "dash-base" -> segment.hls(false).dash(true).segmentFormat(PackagingOptions.SegmentFormat.CMAF)
                    .byteRanges(true).segmentIndex(true).build();
            case "dash-live" -> segment.hls(false).dash(true).segmentFormat(PackagingOptions.SegmentFormat.CMAF)
                    .presentation(PackagingOptions.Presentation.LIVE).windowSegments(2)
                    .programDateTime(Instant.parse("2026-01-01T00:00:00Z")).build();
            default -> throw new IllegalArgumentException(name);
        };
        var subtitles = name.startsWith("dash") ? "cues.ttml" : name.equals("hls-cmaf") ? "cues.srt" : "cues.vtt";
        var files = new LinkedHashMap<String, byte[]>();
        PackageOutput output = file -> new MediaSink() {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            @Override
            public long position() {
                return bytes.size();
            }

            @Override
            public void position(long position) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isSeekable() {
                return false;
            }

            @Override
            public void write(ByteBuffer data) {
                var copy = new byte[data.remaining()];
                data.get(copy);
                bytes.writeBytes(copy);
            }

            @Override
            public void close() {
                files.put(file, bytes.toByteArray());
            }
        };
        var clip = read(SAMPLES.resolve("mp4").resolve("faststart.mp4"));
        var cues = read(SAMPLES.resolve("subtitle").resolve(subtitles));
        var renditions = new ArrayList<Rendition>();
        var packets = new ArrayList<List<Packet>>();
        try (var clipSource = MediaSource.of(clip); var clipDemuxer = Containers.openDemuxer(clipSource);
             var cueSource = MediaSource.of(cues); var cueDemuxer = Containers.openDemuxer(cueSource)) {
            var clipTracks = clipDemuxer.tracks();
            var cueTracks = cueDemuxer.tracks();
            renditions.add(Rendition.builder(clipTracks).name("clip").build());
            if (name.equals("hls-cmaf")) {
                var audio = clipTracks.stream().filter(track -> track.codec() == Codec.AAC).findFirst().orElseThrow();
                renditions.add(Rendition.builder(clipTracks).kind(Rendition.Kind.AUDIO).name("speech")
                        .trackId(audio.id()).language("en").defaultRendition(true).build());
            }
            renditions.add(Rendition.builder(cueTracks).kind(Rendition.Kind.SUBTITLES).name("cues").language("en")
                    .build());
            var clipPackets = cut(clipDemuxer, clipTracks, maxMicros);
            packets.add(clipPackets);
            if (name.equals("hls-cmaf")) {
                packets.add(clipPackets);
            }
            packets.add(cut(cueDemuxer, cueTracks, maxMicros));
        }
        try (var writer = Packager.open(renditions, output, options)) {
            var cursors = new int[packets.size()];
            while (true) {
                var next = -1;
                var best = Long.MAX_VALUE;
                for (var index = 0; index < packets.size(); index++) {
                    if (cursors[index] < packets.get(index).size()) {
                        var packet = packets.get(index).get(cursors[index]);
                        var time = time(renditions.get(index).tracks(), packet);
                        if (time < best) {
                            best = time;
                            next = index;
                        }
                    }
                }
                if (next < 0) {
                    break;
                }
                writer.writePacket(renditions.get(next).name().orElseThrow(), packets.get(next).get(cursors[next]++));
            }
            writer.finish();
        }
        var manifest = name.startsWith("dash") ? "manifest.mpd" : name.equals("hls-iframes") ? "clip_iframes.m3u8"
                : name.equals("hls-cmaf-iframes") ? "clip_video_iframes.m3u8" : "master.m3u8";
        return new Sample(manifest, files);
    }

    /** Reads the packets of a demuxer whose decoding times are below a bound. */
    private static List<Packet> cut(com.github.auties00.smalt.container.Demuxer demuxer, List<Track> tracks,
                                    long maxMicros) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            if (time(tracks, packet) < maxMicros) {
                packets.add(packet);
            }
        }
        return packets;
    }

    /** Returns the decoding time of a packet, in microseconds. */
    private static long time(List<Track> tracks, Packet packet) {
        for (var track : tracks) {
            if (track.id() == packet.trackId()) {
                var value = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
                return track.timeBase().toMicros(value);
            }
        }
        return Long.MAX_VALUE;
    }

    /** Writes a presentation into a directory. */
    public static Path write(Sample sample, Path directory) {
        try {
            Files.createDirectories(directory);
            for (var entry : sample.files().entrySet()) {
                Files.write(directory.resolve(entry.getKey()), entry.getValue());
            }
            return directory.resolve(sample.manifest());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] bytes(int length, int seed) {
        var bytes = new byte[length];
        for (var index = 0; index < length; index++) {
            bytes[index] = (byte) (seed * 31 + index * 7);
        }
        return bytes;
    }
}
