package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds tagged audio and video fixtures with ffmpeg and reads tags back with ffprobe, the
 * black-box oracle of the tag tests.
 */
final class TagFixtures {
    static final String AUDIO = "sine=frequency=440:sample_rate=44100:duration=2";

    static final String VIDEO = "testsrc2=size=96x64:rate=10:duration=2";

    private TagFixtures() {
    }

    /** Writes an ffmetadata file with common tags and two chapters. */
    static Path metadataFile() {
        return Scratch.share(Path.of("target", "media-fixtures", "tags-metadata.txt"),
                (";FFMETADATA1\ntitle=Fixture Title\nartist=Fixture Artist\nalbum=Fixture Album\n"
                        + "genre=Rock\ndate=2021\ntrack=3/12\ncomment=fixture comment\n"
                        + "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=800\ntitle=Intro\n"
                        + "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=800\nEND=2000\ntitle=Middle\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    static Path cover() {
        return Fixtures.testImage("png", 64, 48, List.of());
    }

    /** An audio file with the metadata file's tags and chapters. */
    static Path audio(String extension, List<String> options) {
        var input = new ArrayList<>(List.of("-f", "lavfi", "-i", AUDIO, "-i", metadataFile().toString()));
        var output = new ArrayList<>(List.of("-map", "0:a", "-map_metadata", "1", "-map_chapters", "1"));
        output.addAll(options);
        return Fixtures.ffmpeg(extension, input, output);
    }

    /** An audio file with a cover picture. */
    static Path audioWithCover(String extension, List<String> options) {
        var input = new ArrayList<>(List.of("-f", "lavfi", "-i", AUDIO, "-i", cover().toString()));
        var output = new ArrayList<>(List.of("-map", "0:a", "-map", "1:v", "-c:v", "copy", "-disposition:v", "attached_pic",
                "-metadata", "title=Covered", "-metadata", "artist=Cover Artist"));
        output.addAll(options);
        return Fixtures.ffmpeg(extension, input, output);
    }

    /** A video file with audio and the metadata file's tags. */
    static Path video(String extension, List<String> options) {
        var input = new ArrayList<>(List.of("-f", "lavfi", "-i", VIDEO, "-f", "lavfi", "-i", AUDIO, "-i",
                metadataFile().toString()));
        var output = new ArrayList<>(List.of("-map", "0:v", "-map", "1:a", "-map_metadata", "2", "-map_chapters", "2",
                "-shortest"));
        output.addAll(options);
        return Fixtures.ffmpeg(extension, input, output);
    }

    /** Copies a fixture into a temporary file a test may modify. */
    static Path copy(Path fixture, Path directory) {
        try {
            var target = directory.resolve("edit-" + fixture.getFileName());
            Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

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

    /** Returns the format-level tags ffprobe reports, keys in lower case, completed by the first stream's (Ogg). */
    static Map<String, String> ffprobeTags(Path file) {
        var result = new HashMap<String, String>();
        var probe = ffprobe(file);
        for (var entry : probe.entrySet()) {
            if (entry.getKey().startsWith("format.tags.")) {
                result.put(entry.getKey().substring(12).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        for (var entry : probe.entrySet()) {
            if (entry.getKey().startsWith("streams.stream.0.tags.")) {
                result.putIfAbsent(entry.getKey().substring(22).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return result;
    }

    /** Returns the tags ffprobe reports for one stream, keys in lower case. */
    static Map<String, String> ffprobeStreamTags(Path file, int stream) {
        var result = new HashMap<String, String>();
        var prefix = "streams.stream." + stream + ".tags.";
        for (var entry : ffprobe(file).entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return result;
    }

    /** Returns the chapters ffprobe reports. */
    static List<Chapter> ffprobeChapters(Path file) {
        var probe = ffprobe(file);
        var result = new ArrayList<Chapter>();
        for (var index = 0; probe.containsKey("chapters.chapter." + index + ".start_time"); index++) {
            var prefix = "chapters.chapter." + index + ".";
            result.add(Chapter.of(micros(probe.get(prefix + "start_time")), micros(probe.get(prefix + "end_time")),
                    probe.get(prefix + "tags.title")));
        }
        return result;
    }

    /** Returns the number of attached-picture streams ffprobe reports. */
    static int ffprobeAttachedPictures(Path file) {
        var probe = ffprobe(file);
        var count = 0;
        for (var index = 0; probe.containsKey("streams.stream." + index + ".index"); index++) {
            if ("1".equals(probe.get("streams.stream." + index + ".disposition.attached_pic"))) {
                count++;
            }
        }
        return count;
    }

    static long micros(String seconds) {
        return Math.round(Double.parseDouble(seconds) * 1_000_000);
    }

    /** Returns the hashes ffmpeg computes over the packets of every audio and video stream, copied unchanged. */
    static String packetHash(Path file) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        return new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:a?", "-map", "0:V?", "-c", "copy", "-f", "streamhash", "-hash", "md5", "-")),
                StandardCharsets.US_ASCII).strip();
    }

    static AttachedPicture picture(AttachedPicture.Type type, String description) {
        return AttachedPicture.of(type, "image/png", description, Fixtures.read(cover()));
    }

    /** Tags that exercise every standard field, multiple values, non-Latin text, a custom key, a picture and chapters. */
    static MediaTags sample() {
        return MediaTags.builder()
                .set(StandardTag.TITLE, "Edited Title \u00E8\u4E2D")
                .set(StandardTag.ARTIST, "First Artist", "Second Artist")
                .set(StandardTag.ALBUM, "Edited Album")
                .set(StandardTag.ALBUM_ARTIST, "Album Artist")
                .set(StandardTag.TRACK, "5/9")
                .set(StandardTag.DISC, "1/2")
                .set(StandardTag.DATE, "2024-05-01")
                .set(StandardTag.GENRE, "Jazz")
                .set(StandardTag.COMMENT, "a comment")
                .set(StandardTag.COMPOSER, "Composer")
                .set(StandardTag.LYRICS, "la la la")
                .set("REPLAYGAIN_TRACK_GAIN", "-6.50 dB")
                .addPicture(picture(AttachedPicture.Type.FRONT_COVER, "front"))
                .chapters(List.of(Chapter.of(0, 700_000, "One"), Chapter.of(700_000, 1_500_000, "Two"),
                        Chapter.of(1_500_000, 2_000_000, "Three")))
                .build();
    }
}
