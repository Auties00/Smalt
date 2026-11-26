package com.github.auties00.smalt.container.hls;

import com.github.auties00.smalt.container.DemuxAssertions;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads Apple's advanced HLS example streams (bipbop: MPEG-TS with AAC, AC-3 and E-AC-3 renditions, fragmented MP4
 * with byte-range segments, HEVC in fMP4, I-frame playlists, WebVTT subtitles and CEA-608 closed captions). The hosted
 * data keeps every playlist and the first segments of some renditions; the tree is rebuilt under the scratch
 * directory with each media playlist cut to the segments it holds, then every rendition is compared with ffprobe's
 * reading of the same playlist: its streams, codecs and packet counts. The master playlists must name only playlists
 * the snapshot holds.
 */
@DisplayName("Apple bipbop HLS streams")
class HlsBipbopTest {
    private static final String PREFIX = "apple-hls-bipbop-";

    /** The directory of each rendition, as the vector names flatten it. */
    private static final Pattern DIRECTORY = Pattern.compile("^(s\\d+-[a-z]+|[a-z]+\\d+)-(.*)$");

    private static Path tree;

    /** Rebuilds the stream tree once: every vector at its path, the media playlists cut to their hosted segments. */
    private static synchronized Path tree() {
        if (tree != null) {
            return tree;
        }
        var root = Scratch.directory("hls-bipbop");
        try {
            for (var name : Conformance.vectors(PREFIX)) {
                var rest = name.substring(PREFIX.length());
                var dash = rest.indexOf('-', rest.indexOf('-') + 1);
                var stream = rest.substring(0, dash);
                var file = rest.substring(dash + 1);
                var matcher = DIRECTORY.matcher(file);
                var relative = matcher.matches() ? matcher.group(1).replace('-', '/') + "/" + matcher.group(2) : file;
                var target = root.resolve(stream).resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(Conformance.vector(name), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (var files = Files.walk(root)) {
                for (var playlist : files.filter(path -> path.toString().endsWith(".m3u8")).toList()) {
                    cut(playlist);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        tree = root;
        return root;
    }

    /** Cuts a media playlist after its last segment whose bytes the tree holds, and ends it. */
    private static void cut(Path playlist) throws IOException {
        var lines = Files.readAllLines(playlist, StandardCharsets.UTF_8);
        if (lines.stream().noneMatch(line -> line.startsWith("#EXTINF"))) {
            return;
        }
        var out = new ArrayList<String>();
        var pending = new ArrayList<String>();
        var offsets = new HashMap<String, Long>();
        var range = -1L;
        var rangeOffset = -1L;
        for (var line : lines) {
            if (line.startsWith("#EXT-X-ENDLIST")) {
                continue;
            }
            if (line.startsWith("#EXT-X-BYTERANGE:")) {
                var value = line.substring("#EXT-X-BYTERANGE:".length()).split("@");
                range = Long.parseLong(value[0]);
                rangeOffset = value.length > 1 ? Long.parseLong(value[1]) : -1;
            }
            if (line.startsWith("#EXTINF") || line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-DISCONTINUITY")) {
                pending.add(line);
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                if (pending.isEmpty()) {
                    out.add(line);
                } else {
                    pending.add(line);
                }
                continue;
            }
            var segment = playlist.resolveSibling(line);
            var available = Files.isRegularFile(segment);
            if (available && range >= 0) {
                var start = rangeOffset >= 0 ? rangeOffset : offsets.getOrDefault(line, 0L);
                available = start + range <= Files.size(segment);
                offsets.put(line, start + range);
            }
            if (!available) {
                break;
            }
            out.addAll(pending);
            out.add(line);
            pending.clear();
            range = -1;
        }
        out.add("#EXT-X-ENDLIST");
        Files.write(playlist, out, StandardCharsets.UTF_8);
    }

    /** The media playlists of the renditions whose first segments the snapshot holds. */
    static List<String> renditions() {
        var result = new ArrayList<String>();
        try (var files = Files.walk(tree())) {
            for (var playlist : files.filter(path -> path.getFileName().toString().equals("prog_index.m3u8")
                    || path.getFileName().toString().equals("iframe_index.m3u8")).sorted().toList()) {
                if (Files.readAllLines(playlist).stream().anyMatch(line -> line.startsWith("#EXTINF"))) {
                    result.add(tree().relativize(playlist).toString().replace('\\', '/'));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return result;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("renditions")
    @DisplayName("reads each rendition as ffprobe does: its streams, codecs and packets; an I-frame playlist's frames")
    void rendition(String relative) throws IOException {
        var playlist = tree().resolve(relative);
        if (relative.endsWith("iframe_index.m3u8")) {
            // ffmpeg 7.1 reads an I-frame playlist as a media playlist of whole segments; RFC 8216 4.3.3.6 makes each
            // of its media segments one I-frame
            var frames = Files.readAllLines(playlist).stream().filter(line -> line.startsWith("#EXTINF")).count();
            var keys = 0;
            try (var demuxer = HlsDemuxer.open(playlist)) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    assertTrue(packet.keyframe(), relative + ": an I-frame playlist holds key frames only");
                    keys++;
                }
            }
            assertEquals(frames, keys, relative + " I-frames");
            return;
        }
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(5), List.of("-v", "error",
                "-allowed_extensions", "ALL", "-count_packets", "-show_entries",
                "stream=codec_name,codec_type,nb_read_packets", "-of", "csv=p=0", playlist.toString())),
                StandardCharsets.UTF_8);
        var expected = new ArrayList<String[]>();
        // ffprobe lists each stream under its program too
        for (var line : new java.util.LinkedHashSet<>(List.of(output.split("\r?\n")))) {
            var fields = line.split(",");
            if (fields.length == 3 && !fields[2].equals("0") && !fields[1].equals("data")) {
                expected.add(fields);
            }
        }
        var counts = new HashMap<Integer, Integer>();
        Map<Integer, com.github.auties00.smalt.track.Track> tracks = new HashMap<>();
        try (var demuxer = HlsDemuxer.open(playlist)) {
            for (var track : demuxer.tracks()) {
                tracks.put(track.id(), track);
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                counts.merge(packet.trackId(), 1, Integer::sum);
            }
        }
        var read = tracks.values().stream().filter(track -> counts.getOrDefault(track.id(), 0) > 0)
                .filter(track -> track.codec() != com.github.auties00.smalt.track.Codec.DATA).toList();
        // ffmpeg lists the CEA-608 captions of the video's SEI as a stream; Smalt reads those from the video
        // (CaptionExtractor), and only a caption track of the container (a c608 sample entry) as a track
        if (read.stream().noneMatch(track -> track.codec().isCaption())) {
            expected.removeIf(fields -> fields[0].equals("eia_608"));
        }
        assertEquals(expected.size(), read.size(), relative + ": " + tracks.values());
        for (var index = 0; index < read.size(); index++) {
            var track = read.get(index);
            var stream = expected.stream().filter(fields -> fields[0].equals(DemuxAssertions.ffprobeName(track.codec()))
                    || fields[1].equals(track.kind().name().toLowerCase(java.util.Locale.ROOT))).findFirst()
                    .orElseThrow(() -> new AssertionError(relative + ": no ffprobe stream for " + track));
            var theirs = Integer.parseInt(stream[2]);
            var ours = counts.get(track.id());
            // a transport stream's partial last PES: ffmpeg may return it
            assertTrue(Math.abs(theirs - ours) <= (relative.contains("adv-ts") ? 2 : 0), relative + " " + track.codec()
                    + ": " + ours + " packets, ffprobe " + theirs);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"adv-ts", "adv-fmp4", "adv-hevc"})
    @DisplayName("names in each master playlist only playlists the snapshot holds")
    void master(String stream) throws IOException {
        var master = tree().resolve(stream).resolve("master.m3u8");
        var uris = new ArrayList<String>();
        for (var line : Files.readAllLines(master, StandardCharsets.UTF_8)) {
            var uri = Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
            if (uri.find()) {
                uris.add(uri.group(1));
            } else if (!line.isBlank() && !line.startsWith("#")) {
                uris.add(line.strip());
            }
        }
        assertTrue(uris.size() >= 10, stream + ": " + uris);
        for (var uri : uris) {
            assertTrue(Files.isRegularFile(master.resolveSibling(uri)), stream + ": " + uri);
        }
    }
}
