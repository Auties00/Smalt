package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.dash.DashDemuxer;
import com.github.auties00.smalt.container.hls.HlsDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inputs and checks shared by the packaging tests: synthetic encodes, subtitle files, packaging into a fresh
 * directory, reading a presentation back through the HLS and DASH demuxers, and comparing the packets read back
 * with the inputs' on one timeline.
 */
final class Presentations {
    /** The cues of the SubRip input: the second spans the boundary of the 2-second segments at 2 s and 4 s. */
    static final String SRT = """
            1
            00:00:00,500 --> 00:00:01,500
            Hello

            2
            00:00:01,800 --> 00:00:04,400
            This cue spans two segment boundaries

            3
            00:00:05,000 --> 00:00:05,900
            <i>Bye</i>
            """;

    /** The cues of the WebVTT input, with settings and an identifier. */
    static final String VTT = """
            WEBVTT

            00:00:00.400 --> 00:00:03.000 line:0 align:start
            Bonjour

            last
            00:00:04.100 --> 00:00:05.500
            Au revoir
            """;

    /** A TTML document of the IMSC 1 text profile. */
    static final String TTML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" \
            ttp:profile="http://www.w3.org/ns/ttml/profile/imsc1/text" xml:lang="de">
              <body><div>
                <p begin="00:00:01.000" end="00:00:02.500">Hallo</p>
                <p begin="00:00:03.000" end="00:00:04.000">Tschuess</p>
              </div></body>
            </tt>
            """;

    private Presentations() {
    }

    /** Returns an H.264 and AAC encode of 6 seconds with a key frame every {@code gop} frames at 30 frames per second. */
    static Path video(String size, String rate, int gop) {
        return video(size, rate, gop, 6);
    }

    /** Returns an H.264 and AAC encode with a key frame every {@code gop} frames at 30 frames per second. */
    static Path video(String size, String rate, int gop, int seconds) {
        return Fixtures.ffmpeg("mp4", List.of(
                "-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=30:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=" + seconds),
                List.of("-c:v", "libx264", "-b:v", rate, "-g", Integer.toString(gop), "-keyint_min",
                        Integer.toString(gop), "-sc_threshold", "0", "-bf", "2", "-c:a", "aac", "-ac", "2", "-b:a",
                        "96k"));
    }

    /** Returns an AAC tone of 6 seconds tagged with an ISO 639-2 language. */
    static Path audio(int frequency, String language) {
        return Fixtures.ffmpeg("m4a", List.of("-f", "lavfi", "-i", "sine=frequency=" + frequency
                        + ":sample_rate=48000:duration=6"),
                List.of("-c:a", "aac", "-ac", "2", "-b:a", "64k", "-metadata:s:a:0", "language=" + language));
    }

    /** Writes a text file into the scratch directory of the packaging tests. */
    static Path text(String name, String content) {
        return Scratch.share(Scratch.directory("packaging-inputs").resolve(name),
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns an empty directory of the scratch area. */
    static Path directory(String name) {
        var directory = Scratch.directory("packaging-output").resolve(name);
        deleteAll(directory);
        try {
            return Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Deletes the files of a directory. */
    static void deleteAll(Path directory) {
        try {
            if (Files.isDirectory(directory)) {
                try (var files = Files.list(directory)) {
                    for (var file : files.toList()) {
                        Files.delete(file);
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Opens every input once, builds the renditions with them, and packages them into a directory. */
    static PackagingResult pack(Path directory, PackagingOptions options, List<Input> inputs) {
        var sources = new LinkedHashMap<Path, MediaSource>();
        try {
            var renditions = new ArrayList<Rendition>();
            for (var input : inputs) {
                var source = sources.computeIfAbsent(input.file(), MediaSource::of);
                var builder = Rendition.builder(source);
                input.settings().accept(builder);
                renditions.add(builder.build());
            }
            return Packager.pack(renditions, PackageOutput.directory(directory), options);
        } finally {
            sources.values().forEach(MediaSource::close);
        }
    }

    /**
     * One input of {@link #pack}: a file and the settings of its rendition.
     *
     * @param file the file
     * @param settings sets the rendition's kind, name and attributes
     */
    record Input(Path file, Consumer<Rendition.Builder> settings) {
    }

    /** Opens the HLS demuxer of a playlist on disk. */
    static HlsDemuxer hls(Path playlist) {
        return HlsDemuxer.open(playlist);
    }

    /** Opens the DASH demuxer of a manifest on disk. */
    static DashDemuxer dash(Path manifest) {
        return DashDemuxer.open(manifest);
    }

    /** Returns the packets of each track of a demuxer, by track identifier. */
    static Map<Integer, List<Packet>> packets(Demuxer demuxer) {
        var packets = new LinkedHashMap<Integer, List<Packet>>();
        for (var track : demuxer.tracks()) {
            packets.put(track.id(), new ArrayList<>());
        }
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.computeIfAbsent(packet.trackId(), ignored -> new ArrayList<>()).add(packet);
        }
        return packets;
    }

    /** Returns the packets of the first track of a kind of a file. */
    static Stream source(Path file, TrackKind kind) {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            var track = demuxer.tracks().stream().filter(candidate -> candidate.kind() == kind
                    && (kind != TrackKind.DATA || candidate.codec().isSubtitle() || candidate.codec() == Codec.TTML))
                    .findFirst().orElseThrow();
            var all = packets(demuxer);
            return new Stream(track, all.get(track.id()));
        }
    }

    /**
     * The packets of one track.
     *
     * @param track the track
     * @param packets its packets, in decoding order
     */
    record Stream(Track track, List<Packet> packets) {
        /** {@return the presentation time of the first packet, in microseconds} */
        long startMicros() {
            var first = packets.stream().mapToLong(packet -> packet.pts() != Packet.NO_TIMESTAMP ? packet.pts()
                    : packet.dts()).min().orElse(0);
            return track.timeBase().toMicros(first);
        }

        /**
         * {@return the cues of a text subtitle track, in WebVTT markup, a cue the segments cut into consecutive
         * samples with the same text joined again}
         */
        List<SubtitleCue> cues() {
            var decoder = new SubtitleDecoder(track);
            var decoded = new ArrayList<SubtitleCue>();
            for (var packet : packets) {
                decoded.addAll(decoder.decodeAll(packet, SubtitleMarkup.WEBVTT));
            }
            decoded.addAll(decoder.flush(SubtitleMarkup.WEBVTT));
            var cues = new ArrayList<SubtitleCue>();
            for (var cue : decoded) {
                if (cue.text().isEmpty()) {
                    continue;
                }
                var last = cues.isEmpty() ? null : cues.getLast();
                if (last != null && last.text().equals(cue.text()) && Math.abs(last.endMicros() - cue.startMicros())
                        <= 1000) {
                    cues.set(cues.size() - 1, last.withTiming(last.startMicros(), cue.endMicros()));
                } else {
                    cues.add(cue);
                }
            }
            return cues;
        }
    }

    /** Returns the tracks of a demuxer with their packets, in track order. */
    static List<Stream> streams(Demuxer demuxer) {
        var packets = packets(demuxer);
        var streams = new ArrayList<Stream>();
        for (var track : demuxer.tracks()) {
            streams.add(new Stream(track, packets.get(track.id())));
        }
        return streams;
    }

    /**
     * Asserts that the packets read back are the input's, one for one, on a timeline shifted by a constant: the same
     * key frames, presentation times moved by {@code offsetMicros} within a tick of either time base, and, when asked,
     * the same bytes.
     */
    static void assertSamePackets(String what, Stream input, Stream read, long offsetMicros, boolean sameBytes) {
        assertEquals(input.packets().size(), read.packets().size(), what + ": packet count");
        var tolerance = Math.max(input.track().timeBase().toMicros(1), read.track().timeBase().toMicros(1)) + 1;
        for (var index = 0; index < input.packets().size(); index++) {
            var expected = input.packets().get(index);
            var actual = read.packets().get(index);
            assertEquals(expected.keyframe(), actual.keyframe(), what + ": key frame flag of packet " + index);
            var expectedTime = input.track().timeBase().toMicros(expected.pts() != Packet.NO_TIMESTAMP ? expected.pts()
                    : expected.dts()) + offsetMicros;
            var actualTime = read.track().timeBase().toMicros(actual.pts() != Packet.NO_TIMESTAMP ? actual.pts()
                    : actual.dts());
            assertTrue(Math.abs(expectedTime - actualTime) <= tolerance, what + ": packet " + index + " at "
                    + actualTime + " us, expected " + expectedTime + " us");
            if (sameBytes) {
                assertTrue(java.util.Arrays.equals(expected.data(), actual.data()), what + ": bytes of packet " + index);
            }
        }
    }

    /**
     * Asserts that the cues read back are the input's, on a timeline shifted by a constant, each once, within a
     * millisecond.
     */
    static void assertSameCues(String what, List<SubtitleCue> input, List<SubtitleCue> read, long offsetMicros) {
        assertEquals(input.size(), read.size(), what + ": cue count " + read);
        for (var index = 0; index < input.size(); index++) {
            var expected = input.get(index);
            var actual = read.get(index);
            assertEquals(expected.text(), actual.text(), what + ": text of cue " + index);
            assertTrue(Math.abs(expected.startMicros() + offsetMicros - actual.startMicros()) <= 1000
                            && Math.abs(expected.endMicros() + offsetMicros - actual.endMicros()) <= 1000,
                    what + ": cue " + index + " at " + actual.startMicros() + ".." + actual.endMicros()
                            + ", expected " + (expected.startMicros() + offsetMicros) + ".."
                            + (expected.endMicros() + offsetMicros));
        }
    }

    /** Returns the cues of a subtitle file, in WebVTT markup. */
    static List<SubtitleCue> cues(Path file) {
        try (var source = MediaSource.of(file)) {
            var markup = Subtitles.markupOf(source(file, TrackKind.DATA).track().codec());
            var cues = new ArrayList<SubtitleCue>();
            for (var cue : Subtitles.read(source)) {
                cues.add(Subtitles.convert(cue, markup, SubtitleMarkup.WEBVTT));
            }
            return cues;
        }
    }

    /** Reads a file as text. */
    static String text(Path file) {
        return new String(read(file), StandardCharsets.UTF_8);
    }

    /** Reads a file. */
    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns the input options under which ffmpeg reads a playlist or manifest: for HLS, every segment extension,
     * as ffmpeg refuses WebVTT segments ({@code .vtt}) by default.
     */
    private static List<String> input(Path manifest) {
        var options = new ArrayList<String>();
        if (manifest.getFileName().toString().endsWith(".m3u8")) {
            options.addAll(List.of("-allowed_extensions", "ALL"));
        }
        options.addAll(List.of("-i", manifest.toAbsolutePath().toString().replace('\\', '/')));
        return options;
    }

    /** Runs ffmpeg on a playlist or manifest, decoding every stream, and returns what it printed at warning level. */
    static String ffmpeg(Path manifest) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var arguments = new ArrayList<>(List.of("-hide_banner", "-nostdin", "-v", "warning", "-xerror"));
        arguments.addAll(input(manifest));
        arguments.addAll(List.of("-map", "0", "-f", "null", "-"));
        var output = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
        return new String(output, StandardCharsets.UTF_8);
    }

    /** Returns the codec names of the streams ffprobe finds in a playlist or manifest. */
    static List<String> probe(Path manifest) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var arguments = new ArrayList<>(List.of("-v", "error", "-show_entries", "stream=codec_name", "-of",
                "csv=p=0"));
        arguments.addAll(input(manifest));
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(5), arguments),
                StandardCharsets.UTF_8);
        var codecs = new ArrayList<String>();
        for (var line : output.split("\r?\n")) {
            if (!line.isBlank()) {
                codecs.add(line.strip());
            }
        }
        return codecs;
    }

    /** Returns the number of packets ffprobe reads from a file. */
    static int packets(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var arguments = new ArrayList<>(List.of("-v", "error", "-show_entries", "packet=pts", "-of", "csv=p=0"));
        arguments.addAll(input(file));
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(5), arguments),
                StandardCharsets.UTF_8);
        var count = 0;
        for (var line : output.split("\r?\n")) {
            if (!line.isBlank()) {
                count++;
            }
        }
        return count;
    }

    /**
     * An output that keeps every version of every file in memory and writes the latest to a directory, so a test can
     * check how an event or live presentation evolved.
     */
    static final class Recording implements PackageOutput {
        /** The directory written. */
        final Path directory;

        /** Every version of each file, in the order they were written. */
        final Map<String, List<byte[]>> versions = new LinkedHashMap<>();

        /** The files deleted, in order. */
        final List<String> deleted = new ArrayList<>();

        /** Runs on each completed version: the file name and its bytes. */
        private final java.util.function.BiConsumer<String, byte[]> observer;

        Recording(Path directory, java.util.function.BiConsumer<String, byte[]> observer) {
            this.directory = directory;
            this.observer = observer;
        }

        @Override
        public MediaSink open(String name) {
            var file = directory.resolve(name);
            var sink = MediaSink.of(file);
            var bytes = new ByteArrayOutputStream();
            return new MediaSink() {
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
                    data.duplicate().get(copy);
                    bytes.writeBytes(copy);
                    sink.write(data);
                }

                @Override
                public void close() {
                    sink.close();
                    versions.computeIfAbsent(name, ignored -> new ArrayList<>()).add(bytes.toByteArray());
                    observer.accept(name, bytes.toByteArray());
                }
            };
        }

        @Override
        public void delete(String name) {
            deleted.add(name);
            try {
                Files.deleteIfExists(directory.resolve(name));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        /** {@return the versions of a text file, decoded} */
        List<String> texts(String name) {
            return versions.getOrDefault(name, List.of()).stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList();
        }
    }
}
