package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares {@link Media#probe} with ffprobe on a matrix of containers, codecs and images generated
 * by ffmpeg: format, duration, overall bit rate, every stream's codec, profile, level, size,
 * rotation, frame rate, sample aspect ratio, color description, pixel format, sampling rate,
 * channels and bits per sample, tags, chapters and cover art.
 */
@DisplayName("Probing")
class ProbeTest {
    private static final String VIDEO = "testsrc2=size=176x144:rate=25:duration=2";

    private static final String AUDIO = "sine=frequency=440:sample_rate=48000:duration=2";

    /** Runs ffprobe and returns its flat output as a map. */
    static Map<String, String> ffprobe(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error", "-show_format",
                "-show_streams", "-show_chapters", "-of", "flat", file.toString())), StandardCharsets.UTF_8);
        var result = new HashMap<String, String>();
        for (var line : output.split("\\R")) {
            var separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            var value = line.substring(separator + 1);
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(line.substring(0, separator), value);
        }
        return result;
    }

    static Path chaptersFile() {
        try {
            var file = Path.of("target", "media-fixtures", "probe-chapters.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, ";FFMETADATA1\ntitle=Probe Title\nartist=Probe Artist\nalbum=Probe Album\ngenre=Test\n"
                    + "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=800\ntitle=Intro\n"
                    + "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=800\nEND=2000\ntitle=Middle\n");
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Path cover() {
        return Fixtures.testImage("png", 64, 48, List.of());
    }

    static Path video(String extension, List<String> options) {
        var output = new ArrayList<>(options);
        output.add("-shortest");
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", VIDEO, "-f", "lavfi", "-i", AUDIO), output);
    }

    static Path audio(String extension, List<String> options) {
        var input = new ArrayList<>(List.of("-f", "lavfi", "-i", AUDIO, "-i", chaptersFile().toString()));
        var output = new ArrayList<>(List.of("-map", "0:a", "-map_metadata", "1", "-map_chapters", "1"));
        output.addAll(options);
        return Fixtures.ffmpeg(extension, input, output);
    }

    static Path withCover(String extension, List<String> options) {
        var input = new ArrayList<>(List.of("-f", "lavfi", "-i", AUDIO, "-i", cover().toString()));
        var output = new ArrayList<>(List.of("-map", "0:a", "-map", "1:v", "-c:v", "copy", "-disposition:v", "attached_pic",
                "-metadata", "title=Covered"));
        output.addAll(options);
        return Fixtures.ffmpeg(extension, input, output);
    }

    /** Remuxes a file while marking its video stream rotated, which lavfi inputs cannot carry. */
    static Path rotated(String extension, Path source, int degrees) {
        return Fixtures.ffmpeg(extension, List.of("-display_rotation", Integer.toString(degrees), "-i", source.toString()),
                List.of("-c", "copy"));
    }

    static Stream<Arguments> containers() {
        var h264 = video("mp4", List.of("-c:v", "libx264", "-profile:v", "high", "-level", "3.1", "-bf", "2", "-c:a", "aac"));
        return Stream.of(
                Arguments.of("mp4 h264 high + aac", h264),
                Arguments.of("mp4 rotated", rotated("mp4", h264, 90)),
                Arguments.of("mkv rotated", rotated("mkv", h264, -90)),
                Arguments.of("mp4 h264 baseline + anamorphic", video("mp4", List.of("-c:v", "libx264", "-profile:v", "baseline",
                        "-vf", "setsar=4/3", "-c:a", "aac"))),
                Arguments.of("mp4 hevc main10 hdr", video("mp4", List.of("-c:v", "libx265", "-pix_fmt", "yuv420p10le",
                        "-x265-params", "log-level=error", "-color_primaries", "bt2020", "-color_trc", "smpte2084",
                        "-colorspace", "bt2020nc", "-color_range", "tv", "-tag:v", "hvc1", "-an"))),
                Arguments.of("mov h264 + pcm", video("mov", List.of("-c:v", "libx264", "-pix_fmt", "yuv422p", "-c:a", "pcm_s16le"))),
                Arguments.of("mp4 av1 + opus", video("mp4", List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-g", "25",
                        "-c:a", "libopus"))),
                Arguments.of("mkv h264 + opus", video("mkv", List.of("-c:v", "libx264", "-c:a", "libopus",
                        "-metadata:s:a:0", "language=ita", "-metadata:s:a:0", "title=Commentary"))),
                Arguments.of("webm vp9 + vorbis", video("webm", List.of("-c:v", "libvpx-vp9", "-deadline", "realtime",
                        "-cpu-used", "8", "-color_range", "tv", "-colorspace", "bt709", "-c:a", "libvorbis"))),
                Arguments.of("webm vp8 + opus", video("webm", List.of("-c:v", "libvpx", "-c:a", "libopus"))),
                Arguments.of("ivf vp9", video("ivf", List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-an"))),
                Arguments.of("ivf av1", video("ivf", List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-an"))),
                Arguments.of("mp3 tags + chapters", audio("mp3", List.of("-c:a", "libmp3lame", "-id3v2_version", "4"))),
                Arguments.of("m4a tags + chapters", audio("m4a", List.of("-c:a", "aac"))),
                Arguments.of("ogg opus tags + chapters", audio("opus", List.of("-c:a", "libopus", "-f", "ogg"))),
                Arguments.of("ogg vorbis", audio("ogg", List.of("-c:a", "libvorbis"))),
                Arguments.of("flac 24-bit", audio("flac", List.of("-c:a", "flac", "-sample_fmt", "s32"))),
                Arguments.of("mka flac + chapters", audio("mka", List.of("-c:a", "flac"))),
                Arguments.of("wav s24 + info", audio("wav", List.of("-c:a", "pcm_s24le"))),
                Arguments.of("aiff", audio("aiff", List.of("-c:a", "pcm_s16be"))),
                Arguments.of("adts aac", audio("aac", List.of("-c:a", "aac", "-f", "adts"))),
                Arguments.of("amr", Fixtures.ffmpeg("amr", List.of("-f", "lavfi", "-i", "sine=sample_rate=8000:duration=2"),
                        List.of("-c:a", "libopencore_amrnb", "-ar", "8000", "-ac", "1"))),
                Arguments.of("mp3 cover", withCover("mp3", List.of("-c:a", "libmp3lame", "-id3v2_version", "3"))),
                Arguments.of("m4a cover", withCover("m4a", List.of("-c:a", "aac"))),
                Arguments.of("flac cover", withCover("flac", List.of("-c:a", "flac"))));
    }

    static ContainerFormat expectedFormat(String formatName) {
        return switch (formatName) {
            case "mov,mp4,m4a,3gp,3g2,mj2" -> ContainerFormat.MP4;
            case "matroska,webm" -> ContainerFormat.MATROSKA;
            case "ogg" -> ContainerFormat.OGG;
            case "mp3" -> ContainerFormat.MPEG_AUDIO;
            case "flac" -> ContainerFormat.FLAC;
            case "wav" -> ContainerFormat.WAV;
            case "aiff" -> ContainerFormat.AIFF;
            case "aac" -> ContainerFormat.ADTS;
            case "amr" -> ContainerFormat.AMR;
            case "ivf" -> ContainerFormat.IVF;
            default -> throw new AssertionError("unexpected format " + formatName);
        };
    }

    static String codecName(String smalt) {
        return smalt.replace('-', '_');
    }

    static long micros(String seconds) {
        return Math.round(Double.parseDouble(seconds) * 1_000_000);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("describes a container as ffprobe does")
    void container(String name, Path file) {
        var probe = ffprobe(file);
        var info = Media.probe(MediaSource.of(Fixtures.read(file)));
        assertEquals(expectedFormat(probe.get("format.format_name")), info.container().orElseThrow(), name);
        assertEquals(Files.isRegularFile(file) ? Fixtures.read(file).length : -1, info.size().orElse(-1), name + " size");
        var duration = probe.get("format.duration");
        if (duration != null && !duration.equals("N/A") && !name.startsWith("amr")) {
            // ffprobe reports an MP3 with a LAME tag untrimmed (whole frames) and estimates ADTS
            // from the bit rate; the probe reports the trimmed presentation and the exact frame sum
            var tolerance = name.startsWith("adts") ? 100_000 : name.startsWith("mp3") ? 50_000 : 30_000;
            assertEquals(micros(duration), info.durationMicros().orElseThrow(), tolerance, name + " duration");
            var bitRate = probe.get("format.bit_rate");
            if (bitRate != null && !bitRate.equals("N/A")) {
                assertEquals(Long.parseLong(bitRate), info.bitrate().orElseThrow(), Long.parseLong(bitRate) * 0.03,
                        name + " bit rate");
            }
        }
        var streams = new ArrayList<Integer>();
        var pictures = 0;
        for (var index = 0; probe.containsKey("streams.stream." + index + ".index"); index++) {
            var prefix = "streams.stream." + index + ".";
            if ("1".equals(probe.get(prefix + "disposition.attached_pic"))) {
                pictures++;
                continue;
            }
            if ("attachment".equals(probe.get(prefix + "codec_type"))) {
                continue;
            }
            streams.add(index);
        }
        assertEquals(streams.size(), info.streams().size(), name + " stream count: " + info.streams());
        for (var position = 0; position < streams.size(); position++) {
            var prefix = "streams.stream." + streams.get(position) + ".";
            var stream = info.streams().get(position);
            var label = name + " stream " + position + " ";
            switch (probe.get(prefix + "codec_type")) {
                case "video" -> assertVideo(label, probe, prefix, (VideoStreamInfo) stream);
                case "audio" -> assertAudio(label, probe, prefix, (AudioStreamInfo) stream);
                default -> assertTrue(stream instanceof DataStreamInfo, label + "is data");
            }
            var language = probe.get(prefix + "tags.language");
            if (language != null && !language.equals("und")) {
                assertEquals(language, stream.language().orElse(null), label + "language");
            }
            var title = probe.get(prefix + "tags.title");
            if (title != null && info.container().orElseThrow() == ContainerFormat.MATROSKA) {
                assertEquals(title, stream.tags().get("title"), label + "title");
            }
        }
        assertEquals(pictures, info.attachedPictures().size(), name + " attached pictures");
        for (var picture : info.attachedPictures()) {
            assertEquals(ImageFormat.PNG, picture.format().orElseThrow(), name + " picture format");
            // ffmpeg's FLAC muxer stores the picture type "other"
            assertTrue(picture.type() == AttachedPicture.Type.FRONT_COVER || picture.type() == AttachedPicture.Type.OTHER,
                    name + " picture type " + picture.type());
            assertTrue(Media.probe(MediaSource.of(picture.data())).image().orElseThrow().width() == 64, name + " picture size");
        }
        assertTags(name, probe, info);
        assertChapters(name, probe, info);
    }

    static void assertVideo(String label, Map<String, String> probe, String prefix, VideoStreamInfo stream) {
        assertEquals(probe.get(prefix + "codec_name"), codecName(stream.codec().displayName()), label + "codec");
        assertEquals(Integer.parseInt(probe.get(prefix + "width")), stream.width(), label + "width");
        assertEquals(Integer.parseInt(probe.get(prefix + "height")), stream.height(), label + "height");
        var profile = probe.get(prefix + "profile");
        if (profile != null && !profile.equals("unknown")) {
            assertEquals(profile, stream.profile().orElse(null), label + "profile");
        }
        var level = probe.get(prefix + "level");
        if (level != null && !level.equals("-99") && !stream.codec().name().startsWith("VP")) {
            assertEquals(Integer.parseInt(level), stream.level().orElse(-99), label + "level");
        }
        var pixelFormat = probe.get(prefix + "pix_fmt");
        if (pixelFormat != null) {
            var expectedDepth = pixelFormat.contains("10") ? 10 : pixelFormat.contains("12") ? 12 : 8;
            assertEquals(expectedDepth, stream.bitDepth().orElse(-1), label + "bit depth of " + pixelFormat);
            var expectedChroma = pixelFormat.startsWith("yuv420") || pixelFormat.startsWith("yuvj420") ? "YUV420"
                    : pixelFormat.startsWith("yuv422") || pixelFormat.startsWith("yuvj422") ? "YUV422"
                    : pixelFormat.startsWith("yuv444") ? "YUV444" : pixelFormat.startsWith("gray") ? "MONOCHROME" : null;
            if (expectedChroma != null) {
                assertEquals(expectedChroma, stream.chromaFormat().map(Enum::name).orElse(null), label + "chroma of " + pixelFormat);
            }
        }
        var rate = probe.get(prefix + "r_frame_rate");
        if (rate != null && stream.frameRate().isPresent()) {
            var parts = rate.split("/");
            var expected = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            assertEquals(expected, stream.frameRate().get().toDouble(), 0.01, label + "frame rate");
        }
        var sar = probe.get(prefix + "sample_aspect_ratio");
        if (sar != null && !sar.equals("1:1") && !sar.equals("0:1") && !sar.equals("N/A")) {
            var parts = sar.split(":");
            assertEquals(Long.parseLong(parts[0]) * 1.0 / Long.parseLong(parts[1]),
                    stream.sampleAspectRatio().orElseThrow().toDouble(), 1e-9, label + "sample aspect ratio");
            var dar = probe.get(prefix + "display_aspect_ratio").split(":");
            assertEquals(Double.parseDouble(dar[0]) / Double.parseDouble(dar[1]),
                    (double) stream.displayWidth() / stream.displayHeight(), 0.02, label + "display aspect ratio");
        }
        var rotation = probe.get(prefix + "side_data_list.side_data.0.rotation");
        var clockwise = rotation == null ? 0 : Math.floorMod(-Integer.parseInt(rotation), 360);
        assertEquals(Orientation.ofRotation(clockwise, false), stream.orientation(), label + "orientation");
        assertColor(label, probe, prefix, stream);
    }

    static void assertColor(String label, Map<String, String> probe, String prefix, VideoStreamInfo stream) {
        var range = known(probe.get(prefix + "color_range"));
        var space = known(probe.get(prefix + "color_space"));
        var transfer = known(probe.get(prefix + "color_transfer"));
        var primaries = known(probe.get(prefix + "color_primaries"));
        if (range == null && space == null && transfer == null && primaries == null) {
            return;
        }
        var color = stream.color().orElseThrow(() -> new AssertionError(label + "has no color description"));
        if (range != null && !range.equals("unknown")) {
            assertEquals(range.equals("pc") ? "FULL" : "LIMITED", color.range().name(), label + "range");
        }
        if (space != null && !space.equals("unknown")) {
            assertEquals(MATRICES.get(space), color.matrix().code(), label + "matrix " + space);
        }
        if (transfer != null && !transfer.equals("unknown")) {
            assertEquals(TRANSFERS.get(transfer), color.transfer().code(), label + "transfer " + transfer);
        }
        if (primaries != null && !primaries.equals("unknown")) {
            assertEquals(PRIMARIES.get(primaries), color.primaries().code(), label + "primaries " + primaries);
        }
    }

    static String known(String value) {
        return value == null || value.equals("unknown") || value.equals("unspecified") ? null : value;
    }

    private static final Map<String, Integer> MATRICES = Map.of("gbr", 0, "bt709", 1, "fcc", 4, "bt470bg", 5, "smpte170m", 6,
            "smpte240m", 7, "ycgco", 8, "bt2020nc", 9, "bt2020c", 10);

    private static final Map<String, Integer> TRANSFERS = Map.ofEntries(Map.entry("bt709", 1), Map.entry("gamma22", 4),
            Map.entry("gamma28", 5), Map.entry("smpte170m", 6), Map.entry("smpte240m", 7), Map.entry("linear", 8),
            Map.entry("iec61966-2-1", 13), Map.entry("bt2020-10", 14), Map.entry("bt2020-12", 15), Map.entry("smpte2084", 16),
            Map.entry("arib-std-b67", 18));

    private static final Map<String, Integer> PRIMARIES = Map.of("bt709", 1, "bt470m", 4, "bt470bg", 5, "smpte170m", 6,
            "smpte240m", 7, "film", 8, "bt2020", 9, "smpte431", 11, "smpte432", 12);

    static void assertAudio(String label, Map<String, String> probe, String prefix, AudioStreamInfo stream) {
        assertEquals(probe.get(prefix + "codec_name"), codecName(stream.codec().displayName()), label + "codec");
        assertEquals(Integer.parseInt(probe.get(prefix + "sample_rate")), stream.sampleRate(), label + "sample rate");
        assertEquals(Integer.parseInt(probe.get(prefix + "channels")), stream.channels(), label + "channels");
        var profile = probe.get(prefix + "profile");
        if (profile != null && stream.codec().name().equals("AAC")) {
            assertEquals(profile, stream.profile().orElse(null), label + "profile");
        }
        var bits = probe.get(prefix + "bits_per_raw_sample");
        if (bits != null && !bits.equals("N/A") && !bits.equals("0")) {
            assertEquals(Integer.parseInt(bits), stream.bitsPerSample().orElse(-1), label + "bits per sample");
        }
    }

    static void assertTags(String name, Map<String, String> probe, MediaInfo info) {
        var expected = new HashMap<String, String>();
        for (var entry : probe.entrySet()) {
            var key = entry.getKey();
            if (key.startsWith("format.tags.")) {
                expected.put(key.substring(12).toLowerCase(Locale.ROOT), entry.getValue());
            } else if (info.container().orElseThrow() == ContainerFormat.OGG && key.startsWith("streams.stream.0.tags.")) {
                expected.put(key.substring(22).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        for (var tag : List.of("title", "artist", "album", "genre")) {
            if (expected.containsKey(tag)) {
                assertEquals(expected.get(tag), info.tags().get(tag), name + " tag " + tag + " in " + info.tags());
            }
        }
    }

    static void assertChapters(String name, Map<String, String> probe, MediaInfo info) {
        var count = 0;
        while (probe.containsKey("chapters.chapter." + count + ".start_time")) {
            count++;
        }
        assertEquals(count, info.chapters().size(), name + " chapters " + info.chapters());
        for (var index = 0; index < count; index++) {
            var prefix = "chapters.chapter." + index + ".";
            var chapter = info.chapters().get(index);
            assertEquals(micros(probe.get(prefix + "start_time")), chapter.startMicros(), 1000, name + " chapter start");
            if (info.container().orElseThrow() != ContainerFormat.OGG) {
                assertEquals(micros(probe.get(prefix + "end_time")), chapter.endMicros(), 1000, name + " chapter end");
            }
            assertEquals(probe.get(prefix + "tags.title"), chapter.title().orElse(null), name + " chapter title");
        }
    }

    static Stream<Arguments> images() {
        return Stream.of(
                Arguments.of("png rgba", Fixtures.testImage("png", 120, 90, List.of("-pix_fmt", "rgba")), 8, true, 1),
                Arguments.of("png gray16", Fixtures.testImage("png", 120, 90, List.of("-pix_fmt", "gray16be")), 16, false, 1),
                Arguments.of("png palette", Fixtures.testImage("png", 120, 90, List.of("-pix_fmt", "pal8")), 8, false, 1),
                Arguments.of("jpeg", Fixtures.testImage("jpg", 120, 90, List.of()), 8, false, 1),
                Arguments.of("jpeg-ls", Fixtures.testImage("jls", 120, 90, List.of("-c:v", "jpegls", "-pix_fmt",
                        "rgb24")), 8, false, 1),
                Arguments.of("webp lossy", Fixtures.testImage("webp", 120, 90, List.of("-c:v", "libwebp")), 8, false, 1),
                Arguments.of("webp lossless alpha", Fixtures.testImage("webp", 120, 90, List.of("-vf",
                        "format=rgba,colorchannelmixer=aa=0.5", "-c:v", "libwebp", "-lossless", "1")), 8, true, 1),
                Arguments.of("webp lossy alpha", Fixtures.testImage("webp", 120, 90, List.of("-vf",
                        "format=rgba,colorchannelmixer=aa=0.5", "-c:v", "libwebp", "-pix_fmt", "yuva420p")), 8, true, 1),
                Arguments.of("bmp", Fixtures.testImage("bmp", 120, 90, List.of()), 8, false, 1),
                Arguments.of("tiff", Fixtures.testImage("tiff", 120, 90, List.of()), 8, false, 1),
                Arguments.of("ico", Fixtures.testImage("ico", 64, 48, List.of("-pix_fmt", "bgra")), 8, true, 1),
                Arguments.of("avif", Fixtures.testImage("avif", 120, 90, List.of("-c:v", "libaom-av1", "-cpu-used", "8",
                        "-pix_fmt", "yuv420p10le")), 10, false, 1),
                // ffmpeg's GIF encoder marks a transparent color to skip unchanged pixels
                Arguments.of("gif animation", Fixtures.ffmpeg("gif", List.of("-f", "lavfi", "-i",
                        "testsrc2=size=64x48:rate=10:duration=1"), List.of()), 8, true, 10),
                Arguments.of("apng animation", Fixtures.ffmpeg("apng", List.of("-f", "lavfi", "-i",
                        "testsrc2=size=64x48:rate=10:duration=1"), List.of("-plays", "0", "-f", "apng")), 8, false, 10),
                Arguments.of("webp animation", Fixtures.ffmpeg("webp", List.of("-f", "lavfi", "-i",
                        "testsrc2=size=64x48:rate=10:duration=1"), List.of("-c:v", "libwebp_anim", "-loop", "0")), 8, false, 10));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    @DisplayName("describes an image from its headers as ffprobe does")
    void image(String name, Path file, int bitDepth, boolean alpha, int frames) {
        var probe = ffprobe(file);
        var info = Media.probe(MediaSource.of(Fixtures.read(file)));
        assertTrue(info.isImage(), name);
        var image = info.image().orElseThrow();
        // ffprobe cannot decode animated WebP and reports a 0x0 picture
        var width = name.equals("webp animation") ? 64 : Integer.parseInt(probe.get("streams.stream.0.width"));
        var height = name.equals("webp animation") ? 48 : Integer.parseInt(probe.get("streams.stream.0.height"));
        assertEquals(width, image.width(), name + " width");
        assertEquals(height, image.height(), name + " height");
        assertEquals(bitDepth, image.bitDepth().orElse(-1), name + " bit depth");
        assertEquals(alpha, image.hasAlpha(), name + " alpha");
        assertEquals(frames, image.frameCount().orElse(-1), name + " frames");
        assertEquals(frames > 1, image.isAnimated(), name + " animated");
        if (frames > 1) {
            assertEquals(0, image.loopCount().orElse(-1), name + " loops forever");
            assertEquals(1_000_000, image.durationMicros().orElse(-1), 20_000, name + " duration");
        }
    }

    @Test
    @DisplayName("reports the depth of the AVIF sample transform an alternatives group prefers, and alpha for an overlay")
    void heifDerived() {
        var av1c = new byte[]{(byte) 0x81, 0x00, 0x0C, 0x00};
        var builder = new HeifBuilder();
        var base = builder.item("av01", new byte[]{1}, HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                HeifBuilder.essential(HeifBuilder.av1C(av1c)), HeifBuilder.plain(HeifBuilder.pixi(8, 8, 8)));
        var sato = builder.idatItem("sato", HeifBuilder.sato(2, 1), HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                HeifBuilder.plain(HeifBuilder.pixi(16, 16, 16)));
        builder.reference("dimg", sato, base).primary(base)
                .metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, sato, base)));
        var transformed = Media.probe(MediaSource.of(builder.build())).image().orElseThrow();
        assertEquals(64, transformed.width());
        assertEquals(16, transformed.bitDepth().orElse(-1));
        assertFalse(transformed.hasAlpha());
        // a transform the AVIF decoder skips (a reserved token, an unknown version) leaves the primary depth
        var skipped = HeifBuilder.sato(2, 1, 200);
        var version = HeifBuilder.sato(2, 1);
        version[0] |= 0x40;
        for (var payload : List.of(skipped, version)) {
            var unsupported = new HeifBuilder();
            var input = unsupported.item("av01", new byte[]{1}, HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                    HeifBuilder.essential(HeifBuilder.av1C(av1c)), HeifBuilder.plain(HeifBuilder.pixi(8, 8, 8)));
            var ignored = unsupported.idatItem("sato", payload, HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                    HeifBuilder.plain(HeifBuilder.pixi(16, 16, 16)));
            unsupported.reference("dimg", ignored, input).primary(input)
                    .metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, ignored, input)));
            assertEquals(8, Media.probe(MediaSource.of(unsupported.build())).image().orElseThrow().bitDepth()
                    .orElse(-1));
        }
        // sample transforms are AVIF items: the HEIC decoder shows the primary item of a HEIC file
        var heic = new HeifBuilder().brands("heic", "mif1", "heic");
        var hevc = heic.item("hvc1", new byte[]{1}, HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                HeifBuilder.plain(HeifBuilder.pixi(8, 8, 8)));
        var transform = heic.idatItem("sato", HeifBuilder.sato(2, 1), HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                HeifBuilder.plain(HeifBuilder.pixi(16, 16, 16)));
        heic.reference("dimg", transform, hevc).primary(hevc)
                .metaBox(HeifBuilder.grpl(HeifBuilder.entityGroup("altr", 1, transform, hevc)));
        assertEquals(8, Media.probe(MediaSource.of(heic.build())).image().orElseThrow().bitDepth().orElse(-1));
        var plain = new HeifBuilder();
        plain.primary(plain.item("av01", new byte[]{1}, HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                HeifBuilder.essential(HeifBuilder.av1C(av1c)), HeifBuilder.plain(HeifBuilder.pixi(8, 8, 8))));
        assertEquals(8, Media.probe(MediaSource.of(plain.build())).image().orElseThrow().bitDepth().orElse(-1));
        var overlaid = new HeifBuilder();
        var input = overlaid.item("av01", new byte[]{1}, HeifBuilder.plain(HeifBuilder.ispe(64, 48)),
                HeifBuilder.essential(HeifBuilder.av1C(av1c)), HeifBuilder.plain(HeifBuilder.pixi(8, 8, 8)));
        var overlay = overlaid.idatItem("iovl", HeifBuilder.overlay(false, new int[4], 80, 60, new int[]{0, 0}),
                HeifBuilder.plain(HeifBuilder.ispe(80, 60)));
        overlaid.reference("dimg", overlay, input).hidden(input).primary(overlay);
        var composed = Media.probe(MediaSource.of(overlaid.build())).image().orElseThrow();
        assertEquals(80, composed.width());
        assertTrue(composed.hasAlpha());
    }

    @Test
    @DisplayName("describes HEIF files of AVC, JPEG 2000 and uncompressed items")
    void heifItems() {
        var unci = new HeifBuilder().brands("mif1", "mif1", "miaf");
        unci.primary(unci.item("unci", new byte[16 * 8 * 4], HeifBuilder.plain(HeifBuilder.ispe(16, 8)),
                HeifBuilder.essential(HeifBuilder.fullBox("uncC", 1, 0, new byte[]{'r', 'g', 'b', 'a'}))));
        var rgba = Media.probe(MediaSource.of(unci.build())).image().orElseThrow();
        assertEquals(16, rgba.width());
        assertEquals(8, rgba.bitDepth().orElse(-1));
        assertTrue(rgba.hasAlpha());
        // an 8-bit alpha component before a 12-bit luma component: the depth is that of the luma
        var layout = new byte[]{0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 7, 0, 0, 0, 1, 11, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        var deep = new HeifBuilder().brands("mif1", "mif1", "miaf");
        deep.primary(deep.item("unci", new byte[16 * 8 * 3], HeifBuilder.plain(HeifBuilder.ispe(16, 8)),
                HeifBuilder.essential(HeifBuilder.fullBox("uncC", 0, 0, layout)),
                HeifBuilder.essential(HeifBuilder.box("cmpd", new byte[]{0, 0, 0, 2, 0, 7, 0, 1}))));
        var twelveBit = Media.probe(MediaSource.of(deep.build())).image().orElseThrow();
        assertEquals(12, twelveBit.bitDepth().orElse(-1));
        assertTrue(twelveBit.hasAlpha());
        var avc = new HeifBuilder().brands("avci", "avci", "mif1");
        avc.primary(avc.item("avc1", new byte[]{1}, HeifBuilder.plain(HeifBuilder.ispe(40, 30)),
                HeifBuilder.plain(HeifBuilder.pixi(10, 10, 10))));
        var avcData = avc.build();
        assertEquals(ImageFormat.HEIC, ImageFormat.detect(avcData, avcData.length).orElseThrow());
        var tenBit = Media.probe(MediaSource.of(avcData)).image().orElseThrow();
        assertEquals(40, tenBit.width());
        assertEquals(10, tenBit.bitDepth().orElse(-1));
        assertFalse(tenBit.hasAlpha());
        var cdef = HeifBuilder.box("cdef", new byte[]{0, 2, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 0});
        var j2k = new HeifBuilder().brands("j2ki", "j2ki", "mif1");
        j2k.primary(j2k.item("j2k1", new byte[]{1}, HeifBuilder.plain(HeifBuilder.ispe(20, 10)),
                HeifBuilder.essential(HeifBuilder.box("j2kH", cdef))));
        var j2kData = j2k.build();
        assertEquals(ImageFormat.HEIC, ImageFormat.detect(j2kData, j2kData.length).orElseThrow());
        assertTrue(Media.probe(MediaSource.of(j2kData)).image().orElseThrow().hasAlpha());
        var jpeg = new HeifBuilder().brands("jpeg", "jpeg", "mif1").build();
        assertEquals(ImageFormat.HEIC, ImageFormat.detect(jpeg, jpeg.length).orElseThrow());
    }

    @Test
    @DisplayName("describes a hierarchical JPEG image by its DHP segment, not by its smaller first frame")
    void hierarchicalJpeg() {
        var jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xDE, 0, 11, 12, 0, 29, 0, 41, 1, 1, 0x11, 0,
                (byte) 0xFF, (byte) 0xC1, 0, 11, 12, 0, 15, 0, 21, 1, 1, 0x11, 0,
                (byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 63, 0, (byte) 0xFF, (byte) 0xD9};
        var image = Media.probe(MediaSource.of(jpeg)).image().orElseThrow();
        assertEquals(41, image.width());
        assertEquals(29, image.height());
        assertEquals(12, image.bitDepth().orElse(-1));
    }

    @Nested
    @DisplayName("an image orientation")
    class Orientations {
        @Test
        @DisplayName("comes from the EXIF Orientation tag of a JPEG APP1 segment")
        void jpegExif() {
            var jpeg = Fixtures.read(Fixtures.testImage("jpg", 120, 90, List.of()));
            var exif = new byte[]{(byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1,
                    0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0};
            var tagged = new byte[jpeg.length + exif.length];
            System.arraycopy(jpeg, 0, tagged, 0, 2);
            System.arraycopy(exif, 0, tagged, 2, exif.length);
            System.arraycopy(jpeg, 2, tagged, 2 + exif.length, jpeg.length - 2);
            var image = Media.probe(MediaSource.of(tagged)).image().orElseThrow();
            assertEquals(Orientation.ROTATE_90, image.orientation());
            assertEquals(90, image.displayWidth());
            assertEquals(120, image.displayHeight());
        }
    }

    @Test
    @DisplayName("leaves out attached pictures when the options ask")
    void withoutPictures() {
        var file = withCover("mp3", List.of("-c:a", "libmp3lame"));
        assertFalse(Media.probe(MediaSource.of(Fixtures.read(file))).attachedPictures().isEmpty());
        assertTrue(Media.probe(MediaSource.of(Fixtures.read(file)), ProbeOptions.headersOnly()).attachedPictures().isEmpty());
    }
}
