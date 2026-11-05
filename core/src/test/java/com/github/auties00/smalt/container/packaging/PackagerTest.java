package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsDemuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages two aligned encodes into HLS and DASH in every layout and checks the result with
 * ffmpeg reading the master playlist or manifest (clean decode, every frame), with the test kit's
 * HLS and DASH validators (RFC 8216, the ISO/IEC 23009-1 schema and segment timelines, every
 * segment validated as a transport stream or ISOBMFF segment), with the playlist rules of
 * RFC 8216, and with the segments themselves (key frame starts, byte ranges on box boundaries).
 */
@DisplayName("HLS and DASH packager")
class PackagerTest {
    private static final Path OUTPUT = Path.of("target", "packaging-output");

    private static Path high() {
        return encode("320x240", "400k");
    }

    private static Path low() {
        return encode("160x120", "150k");
    }

    private static Path encode(String size, String rate) {
        return Fixtures.ffmpeg("mp4", List.of(
                "-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=30:duration=7",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=7"),
                List.of("-c:v", "libx264", "-b:v", rate, "-g", "30", "-keyint_min", "30", "-sc_threshold", "0", "-bf", "2",
                        "-c:a", "aac", "-ac", "2", "-b:a", "96k"));
    }

    private static PackagingOptions options(String mode) {
        var options = switch (mode) {
            case "hls" -> PackagingOptions.hls();
            case "hls-byte-ranges" -> PackagingOptions.hls().toBuilder().byteRanges(true).build();
            case "hls-cmaf" -> PackagingOptions.hlsCmaf();
            case "dash" -> PackagingOptions.dash();
            case "cmaf" -> PackagingOptions.cmaf();
            case "cmaf-byte-ranges" -> PackagingOptions.cmaf().toBuilder().byteRanges(true).build();
            case "dash-on-demand" -> PackagingOptions.dashOnDemand();
            case "cmaf-indexed" -> PackagingOptions.cmaf().toBuilder().byteRanges(true).segmentIndex(true).build();
            default -> throw new IllegalArgumentException(mode);
        };
        return options.toBuilder().segmentMicros(2_000_000).build();
    }

    private static PackagingResult pack(String mode, Path directory, Path... inputs) {
        deleteAll(directory);
        var sources = new ArrayList<MediaSource>();
        try {
            var renditions = new ArrayList<Rendition>();
            for (var input : inputs) {
                var source = MediaSource.of(input);
                sources.add(source);
                renditions.add(Rendition.of(source));
            }
            return Packager.pack(renditions, PackageOutput.directory(directory), options(mode));
        } finally {
            sources.forEach(MediaSource::close);
        }
    }

    private static void deleteAll(Path directory) {
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

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Runs ffmpeg on a playlist or manifest and returns what it printed at warning level, and the frame counts. */
    private static String ffmpeg(Path manifest) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var command = List.of(Oracle.find(Oracle.Tool.FFMPEG).orElseThrow().toString(), "-hide_banner", "-nostdin", "-v", "warning",
                "-xerror", "-i", manifest.toAbsolutePath().toString().replace('\\', '/'), "-map", "0", "-f", "null", "-");
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var output = new ByteArrayOutputStream();
            process.getInputStream().transferTo(output);
            assertTrue(process.waitFor(5, TimeUnit.MINUTES), "ffmpeg timed out");
            var text = output.toString(StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "ffmpeg failed:\n" + text);
            return text;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    /** Counts the frames of each stream ffprobe reads through a playlist or manifest. */
    private static Map<String, Integer> frames(Path manifest) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, java.time.Duration.ofMinutes(5), List.of("-v", "error",
                "-count_frames", "-show_entries", "stream=codec_name,nb_read_frames", "-of", "csv=p=0",
                manifest.toAbsolutePath().toString().replace('\\', '/'))), StandardCharsets.UTF_8);
        var counts = new LinkedHashMap<String, Integer>();
        for (var line : output.split("\r?\n")) {
            var parts = line.split(",");
            if (parts.length == 2 && !parts[1].isBlank()) {
                counts.merge(parts[0], Integer.parseInt(parts[1].trim()), Math::max);
            }
        }
        return counts;
    }

    /** Checks a media playlist against RFC 8216 and returns its segment durations. */
    private static List<Double> checkMediaPlaylist(String playlist) {
        assertTrue(playlist.startsWith("#EXTM3U\n"), "the playlist starts with #EXTM3U");
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD\n"));
        assertTrue(playlist.endsWith("#EXT-X-ENDLIST\n"));
        var target = Integer.parseInt(find(playlist, "#EXT-X-TARGETDURATION:(\\d+)"));
        var durations = new ArrayList<Double>();
        var matcher = Pattern.compile("#EXTINF:([0-9.]+),").matcher(playlist);
        while (matcher.find()) {
            var duration = Double.parseDouble(matcher.group(1));
            assertTrue(Math.round(duration) <= target, "EXTINF " + duration + " rounds above the target duration " + target);
            durations.add(duration);
        }
        return durations;
    }

    private static String find(String text, String regex) {
        var matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), "missing " + regex);
        return matcher.group(1);
    }

    @Nested
    @DisplayName("read back by ffmpeg")
    class ReadBack {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"hls", "hls-byte-ranges", "hls-cmaf", "cmaf", "cmaf-byte-ranges"})
        @DisplayName("writes HLS that ffmpeg plays through without a warning, every frame of every variant")
        void hls(String mode) {
            var directory = OUTPUT.resolve(mode);
            var result = pack(mode, directory, high(), low());
            var master = directory.resolve(result.masterPlaylist().orElseThrow());
            Validators.assertValidHls(master);
            var warnings = ffmpeg(master);
            assertTrue(warnings.isBlank(), warnings);
            var counts = frames(master);
            assertEquals(210, counts.get("h264"), "video frames");
            assertTrue(Math.abs(counts.get("aac") - 7 * 48_000 / 1024) <= 2, "audio frames " + counts.get("aac"));
            var text = text(master);
            assertTrue(text.contains("CODECS=\"avc1.6400"), text);
            assertTrue(text.contains("RESOLUTION=320x240") && text.contains("RESOLUTION=160x120"), text);
            assertTrue(text.contains("FRAME-RATE=30.000"), text);
            for (var stream : result.streams()) {
                var playlist = text(directory.resolve(stream.playlist().orElseThrow()));
                var durations = checkMediaPlaylist(playlist);
                assertEquals(stream.segments(), durations.size());
                var total = durations.stream().mapToDouble(Double::doubleValue).sum();
                assertTrue(Math.abs(total - 7.0) < 0.05, stream.name() + " lasts " + total);
                assertTrue(stream.peakBandwidth() >= stream.averageBandwidth() && stream.averageBandwidth() > 0);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"dash", "cmaf"})
        @DisplayName("writes DASH manifests that ffmpeg plays through without a warning")
        void dash(String mode) {
            var directory = OUTPUT.resolve(mode + "-mpd");
            var result = pack(mode, directory, high(), low());
            var manifest = directory.resolve(result.manifest().orElseThrow());
            Validators.assertValidDash(manifest);
            var warnings = ffmpeg(manifest);
            assertTrue(warnings.isBlank(), warnings);
            var mpd = text(manifest);
            assertTrue(mpd.contains("type=\"static\"") && mpd.contains("mediaPresentationDuration=\"PT7.0"), mpd);
            assertEquals(4, mpd.split("<Representation ", -1).length - 1);
            var timescales = Pattern.compile("timescale=\"(\\d+)\"").matcher(mpd);
            var segments = Pattern.compile("<S t=\"(\\d+)\" d=\"(\\d+)\"(?: r=\"(\\d+)\")?/>").matcher(mpd);
            var count = 0;
            while (segments.find()) {
                count += 1 + (segments.group(3) == null ? 0 : Integer.parseInt(segments.group(3)));
            }
            var files = result.files().stream().filter(name -> name.endsWith(".m4s")).count();
            assertEquals(files, count, "one timeline entry per media segment file");
            assertTrue(timescales.find());
        }

        @Test
        @DisplayName("packages an audio-only rendition")
        void audioOnly() {
            var directory = OUTPUT.resolve("audio-only");
            var input = Fixtures.tone("m4a", 440, 44_100, 2, 5, List.of("-c:a", "aac"));
            var result = pack("hls", directory, input);
            Validators.assertValidHls(directory.resolve("master.m3u8"));
            var master = text(directory.resolve("master.m3u8"));
            assertFalse(master.contains("RESOLUTION"));
            assertTrue(master.contains("CODECS=\"mp4a.40.2\""), master);
            assertTrue(ffmpeg(directory.resolve("master.m3u8")).isBlank());
            assertEquals(List.of(Codec.AAC), result.streams().getFirst().codecs());
        }

        @Test
        @DisplayName("names the ipcm sample entry of a PCM rendition in both manifests")
        void pcmCodecs() {
            var directory = OUTPUT.resolve("cmaf-pcm");
            var input = Fixtures.tone("wav", 440, 48_000, 2, 5, List.of("-c:a", "pcm_s16le"));
            var result = pack("cmaf", directory, input);
            Validators.assertValidHls(directory.resolve("master.m3u8"));
            Validators.assertValidDash(directory.resolve("manifest.mpd"));
            assertEquals(List.of(Codec.PCM_S16LE), result.streams().getFirst().codecs());
            assertEquals("ipcm", result.streams().getFirst().codecString());
            assertTrue(text(directory.resolve("master.m3u8")).contains("CODECS=\"ipcm\""));
            assertTrue(text(directory.resolve("manifest.mpd")).contains("codecs=\"ipcm\""));
        }
    }

    @Nested
    @DisplayName("segments")
    class Segments {
        @Test
        @DisplayName("start every transport stream segment with a key frame")
        void keyFrames() {
            var directory = OUTPUT.resolve("hls-keys");
            var result = pack("hls", directory, high());
            Validators.assertValidHls(directory.resolve(result.masterPlaylist().orElseThrow()));
            assertEquals(4, result.streams().getFirst().segments());
            for (var name : result.files()) {
                if (!name.endsWith(".ts")) {
                    continue;
                }
                try (var source = MediaSource.of(directory.resolve(name));
                     var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                    var video = demuxer.tracks().getFirst();
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        if (packet.trackId() == video.id()) {
                            assertTrue(packet.keyframe(), name + " starts with a key frame");
                            break;
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("addresses byte ranges on box boundaries in single-file CMAF")
        void ranges() {
            var directory = OUTPUT.resolve("cmaf-ranges");
            pack("cmaf-byte-ranges", directory, high());
            Validators.assertValidDash(directory.resolve("manifest.mpd"));
            Validators.assertValidHls(directory.resolve("master.m3u8"));
            var mpd = text(directory.resolve("manifest.mpd"));
            var file = bytes(directory.resolve("r0_video.mp4"));
            var init = Pattern.compile("<Initialization sourceURL=\"r0_video.mp4\" range=\"0-(\\d+)\"/>").matcher(mpd);
            assertTrue(init.find(), mpd);
            assertEquals("ftyp", new String(file, 4, 4, StandardCharsets.US_ASCII));
            var media = Pattern.compile("<SegmentURL media=\"r0_video.mp4\" mediaRange=\"(\\d+)-(\\d+)\"/>").matcher(mpd);
            var expectedStart = Long.parseLong(init.group(1)) + 1;
            var segments = 0;
            while (media.find()) {
                var start = Long.parseLong(media.group(1));
                assertEquals(expectedStart, start, "ranges are contiguous");
                assertEquals("styp", new String(file, (int) start + 4, 4, StandardCharsets.US_ASCII));
                expectedStart = Long.parseLong(media.group(2)) + 1;
                segments++;
            }
            assertEquals(file.length, expectedStart, "ranges cover the file");
            assertEquals(4, segments);
            var playlist = text(directory.resolve("r0_video.m3u8"));
            assertTrue(playlist.contains("#EXT-X-MAP:URI=\"r0_video.mp4\",BYTERANGE=\"" + (Long.parseLong(init.group(1)) + 1) + "@0\""),
                    playlist);
            assertTrue(playlist.contains("#EXT-X-BYTERANGE:"));
        }

        @Test
        @DisplayName("addresses an indexed file with a SegmentBase whose ranges hold the moov and the sidx")
        void onDemand() {
            var directory = OUTPUT.resolve("dash-on-demand");
            var result = pack("dash-on-demand", directory, high(), low());
            var manifest = directory.resolve(result.manifest().orElseThrow());
            Validators.assertValidDash(manifest);
            var mpd = text(manifest);
            assertTrue(mpd.contains("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\""), mpd);
            assertTrue(mpd.contains("subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\""), mpd);
            var bases = Pattern.compile("<BaseURL>([^<]+)</BaseURL>\\s*<SegmentBase timescale=\"\\d+\" indexRange=\"(\\d+)-(\\d+)\" "
                    + "indexRangeExact=\"true\">\\s*<Initialization range=\"0-(\\d+)\"/>").matcher(mpd);
            var count = 0;
            while (bases.find()) {
                var file = bytes(directory.resolve(bases.group(1)));
                var initEnd = Long.parseLong(bases.group(4));
                var indexStart = Long.parseLong(bases.group(2));
                var indexEnd = Long.parseLong(bases.group(3));
                assertEquals(initEnd + 1, indexStart, "the index follows the initialization segment");
                assertEquals("sidx", new String(file, (int) indexStart + 4, 4, StandardCharsets.US_ASCII));
                var size = ByteBuffer.wrap(file).getInt((int) indexStart);
                assertEquals(indexEnd - indexStart + 1, size, "the index range is the sidx box");
                assertEquals("moof", new String(file, (int) indexEnd + 1 + 4, 4, StandardCharsets.US_ASCII));
                count++;
            }
            assertEquals(4, count, mpd);
            assertTrue(result.files().stream().noneMatch(name -> name.endsWith(".m4s")));
            var warnings = ffmpeg(manifest);
            assertTrue(warnings.isBlank(), warnings);
            var frames = frames(manifest);
            assertEquals(210, frames.get("h264"), frames.toString());
        }

        @Test
        @DisplayName("keeps HLS byte ranges over the indexed files, past the sidx")
        void indexedHls() {
            var directory = OUTPUT.resolve("cmaf-indexed");
            pack("cmaf-indexed", directory, high());
            var file = bytes(directory.resolve("r0_video.mp4"));
            var playlist = text(directory.resolve("r0_video.m3u8"));
            var map = Pattern.compile("#EXT-X-MAP:URI=\"r0_video.mp4\",BYTERANGE=\"(\\d+)@0\"").matcher(playlist);
            assertTrue(map.find(), playlist);
            var sidx = Integer.parseInt(map.group(1));
            assertEquals("sidx", new String(file, sidx + 4, 4, StandardCharsets.US_ASCII));
            var ranges = Pattern.compile("#EXT-X-BYTERANGE:(\\d+)@(\\d+)").matcher(playlist);
            var expected = sidx + ByteBuffer.wrap(file).getInt(sidx);
            var segments = 0;
            while (ranges.find()) {
                assertEquals(expected, Long.parseLong(ranges.group(2)), "ranges are contiguous after the sidx");
                assertEquals("moof", new String(file, (int) expected + 4, 4, StandardCharsets.US_ASCII));
                expected += Long.parseLong(ranges.group(1));
                segments++;
            }
            assertEquals(file.length, expected);
            assertEquals(4, segments);
            assertTrue(ffmpeg(directory.resolve("master.m3u8")).isBlank());
        }

        @Test
        @DisplayName("writes through any output, not only a directory")
        void customOutput() {
            var files = new LinkedHashMap<String, byte[]>();
            PackageOutput output = name -> new MediaSink() {
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
                    files.put(name, bytes.toByteArray());
                }
            };
            try (var source = MediaSource.of(high())) {
                var result = Packager.pack(List.of(Rendition.of("main", source)), output, PackagingOptions.cmaf());
                assertEquals(new ArrayList<>(files.keySet()), result.files());
                assertTrue(files.containsKey("main_video_init.mp4") && files.containsKey("main_audio_00001.m4s"));
                assertTrue(files.containsKey("master.m3u8") && files.containsKey("manifest.mpd"));
            }
        }
    }

    @Nested
    @DisplayName("options and inputs")
    class OptionsAndInputs {
        @Test
        @DisplayName("validates options and rendition names")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.dash().toBuilder()
                    .segmentFormat(PackagingOptions.SegmentFormat.MPEG_TS).build());
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().hls(false).build());
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().segmentMicros(0)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder()
                    .masterPlaylistName("a/b.m3u8").build());
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.cmaf().toBuilder().segmentIndex(true)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.hls().toBuilder().byteRanges(true)
                    .segmentIndex(true).build());
            assertThrows(IllegalArgumentException.class, () -> PackagingOptions.dashOnDemand().toBuilder()
                    .byteRanges(false).build());
            assertTrue(PackagingOptions.dashOnDemand().segmentIndex() && !PackagingOptions.dashOnDemand().hlsEnabled());
            assertEquals(PackagingOptions.dashOnDemand(), PackagingOptions.dashOnDemand().toBuilder().build());
            assertEquals(PackagingOptions.cmaf(), PackagingOptions.cmaf().toBuilder().build());
            assertTrue(PackagingOptions.cmaf().hlsEnabled() && PackagingOptions.cmaf().dashEnabled());
            var source = MediaSource.of(new byte[0]);
            assertThrows(IllegalArgumentException.class, () -> Rendition.of("bad name", source));
            assertThrows(IllegalArgumentException.class, () -> Packager.pack(List.of(), name -> MediaSink.memory(), PackagingOptions.hls()));
            assertThrows(IllegalArgumentException.class, () -> Packager.pack(List.of(Rendition.of("a", source), Rendition.of("a", source)),
                    name -> MediaSink.memory(), PackagingOptions.hls()));
        }

        @Test
        @DisplayName("rejects a rendition without a track the segment format carries")
        void unsupported() {
            var wav = Fixtures.tone("wav", 440, 8_000, 1, 1, List.of("-c:a", "pcm_s16le"));
            try (var source = MediaSource.of(wav)) {
                assertThrows(MediaException.Unsupported.class,
                        () -> Packager.pack(List.of(Rendition.of(source)), name -> MediaSink.memory(), PackagingOptions.hls()));
            }
        }
    }

    @Nested
    @DisplayName("codec strings")
    class CodecStringsTest {
        @Test
        @DisplayName("follow RFC 6381 and the ISOBMFF bindings")
        void strings() {
            var avc = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(Rational.timeBaseOf(90_000))
                    .codecConfig(new byte[]{1, 0x64, 0x00, 0x1F, (byte) 0xFF, (byte) 0xE0, 0}).build();
            assertEquals("avc1.64001f", CodecStrings.of(avc, null));
            var hvcc = new byte[23];
            hvcc[0] = 1;
            hvcc[1] = 0x01;
            hvcc[2] = 0x60;
            hvcc[6] = (byte) 0x90;
            hvcc[12] = 93;
            var hevc = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(Rational.timeBaseOf(90_000))
                    .codecConfig(hvcc).build();
            assertEquals("hvc1.1.6.L93.90", CodecStrings.of(hevc, null));
            var av1 = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(Rational.timeBaseOf(90_000))
                    .codecConfig(new byte[]{(byte) 0x81, 0x08, 0x0C, 0}).build();
            assertEquals("av01.0.08M.08", CodecStrings.of(av1, null));
            var vp9 = VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(Rational.timeBaseOf(90_000))
                    .codecConfig(new byte[]{0, 31, (byte) 0x82, 1, 1, 1, 0, 0}).build();
            assertEquals("vp09.00.31.08", CodecStrings.of(vp9, null));
            var heaac = AudioTrack.builder().id(2).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                    .codecConfig(new byte[]{0x2B, (byte) 0x92, 0x08, 0x00}).build();
            assertEquals("mp4a.40.5", CodecStrings.of(heaac, null));
            assertEquals("ec-3", CodecStrings.of(AudioTrack.builder().id(3).codec(Codec.EAC3)
                    .timeBase(Rational.timeBaseOf(48_000)).build(), null));
        }

        @Test
        @DisplayName("name the Dolby Vision sample entry, profile and level of a stream without a compatible base layer")
        void dolbyVision() {
            var hvcc = new byte[23];
            hvcc[0] = 1;
            hvcc[1] = 0x02;
            hvcc[2] = 0x20;
            hvcc[12] = 120;
            var hevc = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(Rational.timeBaseOf(90_000))
                    .codecConfig(hvcc).build();
            var base = CodecStrings.of(hevc, null);
            assertEquals("dvh1.05.06", CodecStrings.of(hevc.toBuilder()
                    .dolbyVision(com.github.auties00.smalt.track.DolbyVision.of(5, 6, 0)).build(), null));
            assertEquals(base, CodecStrings.of(hevc.toBuilder()
                    .dolbyVision(com.github.auties00.smalt.track.DolbyVision.of(8, 6, 1)).build(), null));
            var av1 = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(Rational.timeBaseOf(90_000))
                    .codecConfig(new byte[]{(byte) 0x81, 0x08, 0x0C, 0})
                    .dolbyVision(com.github.auties00.smalt.track.DolbyVision.of(10, 9, 0)).build();
            assertEquals("dav1.10.09", CodecStrings.of(av1, null));
            var avc = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(Rational.timeBaseOf(90_000))
                    .codecConfig(new byte[]{1, 0x64, 0x00, 0x1F, (byte) 0xFF, (byte) 0xE0, 0})
                    .dolbyVision(com.github.auties00.smalt.track.DolbyVision.of(9, 5, 0)).build();
            assertEquals("dva1.09.05", CodecStrings.of(avc, null));
        }

        @Test
        @DisplayName("name the sample entries of the other codecs a CMAF segment carries")
        void cmafEntries() {
            // a VP8 key frame tag (version 0), its start code and a 320x240 picture
            var vp8Frame = new byte[]{0x50, 0x2A, 0x00, (byte) 0x9D, 0x01, 0x2A, 0x40, 0x01, (byte) 0xF0, 0x00};
            assertEquals("vp08.00.20.08", CodecStrings.of(video(Codec.VP8, null), vp8Frame));
            // visual object sequence header with profile_and_level_indication 0xF5 (Advanced Simple, level 5)
            var mpeg4 = new byte[]{0, 0, 1, (byte) 0xB0, (byte) 0xF5, 0, 0, 1, (byte) 0xB5, 0x09};
            assertEquals("mp4v.20.245", CodecStrings.of(video(Codec.MPEG4_PART2, mpeg4), null));
            var frame = new byte[24];
            System.arraycopy(mpeg4, 0, frame, 0, mpeg4.length);
            frame[10] = 0;
            frame[11] = 0;
            frame[12] = 1;
            frame[13] = 0x20;
            frame[16] = 0;
            frame[17] = 0;
            frame[18] = 1;
            frame[19] = (byte) 0xB6;
            assertEquals("mp4v.20.245", CodecStrings.of(video(Codec.MPEG4_PART2, null), frame));
            assertEquals("mp4v.20", CodecStrings.of(video(Codec.MPEG4_PART2, null), null));
            assertEquals("s263", CodecStrings.of(video(Codec.H263, null), null));
            // a vvcC record of the Main 10 profile at level 3.1 (35): the length size byte with ptl_present_flag,
            // ols_idx to bit_depth_minus8, one constraint byte, profile_idc 1 with the Main tier, level_idc 35
            var vvcC = new byte[]{(byte) 0xFF, 0x00, 0x55, 0x5F, 0x01, 0x02, 0x23, (byte) 0x80};
            assertEquals("vvc1.1.L35", CodecStrings.of(video(Codec.VVC, vvcC), null));
            var boxed = new byte[vvcC.length + 4];
            System.arraycopy(vvcC, 0, boxed, 4, vvcC.length);
            assertEquals("vvc1.1.L35", CodecStrings.of(video(Codec.VVC, boxed), null));
            assertEquals("vvc1", CodecStrings.of(video(Codec.VVC, null), null));
            assertEquals("mp4v.6C", CodecStrings.of(video(Codec.MJPEG, null), null));
            assertEquals("mjp2", CodecStrings.of(video(Codec.JPEG2000, null), null));
            assertEquals("apch", CodecStrings.of(video(Codec.PRORES, "apch".getBytes(StandardCharsets.US_ASCII)), null));
            var prores = new byte[32];
            System.arraycopy("icpf".getBytes(StandardCharsets.US_ASCII), 0, prores, 4, 4);
            prores[20] = (byte) 0xC0;
            assertEquals("ap4h", CodecStrings.of(video(Codec.PRORES, null), prores));
            assertEquals("ap4h", CodecStrings.of(video(Codec.PRORES, null), Arrays.copyOfRange(prores, 8, 32)));
            prores[20] = (byte) 0x80;
            assertEquals("apcn", CodecStrings.of(video(Codec.PRORES, null), prores));
            assertEquals("AVdh", CodecStrings.of(video(Codec.DNXHD, null), new byte[]{0, 0, 2, (byte) 0x80, 3, 0, 0, 0}));
            assertEquals("AVdn", CodecStrings.of(video(Codec.DNXHD, null), new byte[]{0, 0, 2, (byte) 0x80, 1, 0, 0, 0}));
            assertEquals("samr", CodecStrings.of(audio(Codec.AMR_NB), null));
            assertEquals("sawb", CodecStrings.of(audio(Codec.AMR_WB), null));
            for (var codec : List.of(Codec.PCM_S16LE, Codec.PCM_S16BE, Codec.PCM_S24LE, Codec.PCM_S24BE, Codec.PCM_S32LE,
                    Codec.PCM_S32BE)) {
                assertEquals("ipcm", CodecStrings.of(audio(codec), null), codec.name());
            }
            for (var codec : List.of(Codec.PCM_F32LE, Codec.PCM_F32BE, Codec.PCM_F64LE, Codec.PCM_F64BE)) {
                assertEquals("fpcm", CodecStrings.of(audio(codec), null), codec.name());
            }
            // every codec a CMAF segment carries gets a codec string
            for (var codec : Codec.values()) {
                if (FragmentedMp4Muxer.supports(codec) && codec.kind() != TrackKind.DATA) {
                    var track = codec.kind() == TrackKind.VIDEO ? video(codec, null) : audio(codec);
                    assertNotNull(CodecStrings.of(track, null), codec.name());
                }
            }
        }

        static VideoTrack video(Codec codec, byte[] config) {
            var builder = VideoTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(90_000)).width(320)
                    .height(240);
            if (config != null) {
                builder.codecConfig(config);
            }
            return builder.build();
        }

        static AudioTrack audio(Codec codec) {
            return AudioTrack.builder().id(2).codec(codec).timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000)
                    .layout(ChannelLayout.STEREO).build();
        }
    }
}
