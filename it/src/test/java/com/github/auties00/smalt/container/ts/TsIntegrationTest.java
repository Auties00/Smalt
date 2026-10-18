package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.container.packaging.PackageOutput;
import com.github.auties00.smalt.container.packaging.Packager;
import com.github.auties00.smalt.container.packaging.PackagingOptions;
import com.github.auties00.smalt.container.packaging.Rendition;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises transport streams and packaging with the codec artifacts present: transcodes that
 * encode into a transport stream, a ladder of transcodes packaged into HLS and DASH, and the
 * decoding readers over transport stream input.
 */
@DisplayName("Transport streams and packaging with codecs")
class TsIntegrationTest {
    private static final Path OUTPUT = Path.of("target", "ts-integration");

    private static Path video(String extension, List<String> options) {
        return Fixtures.ffmpeg(extension, List.of(
                "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=25:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"), options);
    }

    private static Path save(String name, byte[] data) {
        try {
            Files.createDirectories(OUTPUT);
            var file = OUTPUT.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String path(Path file) {
        return file.toAbsolutePath().toString().replace('\\', '/');
    }

    private static List<String> streams(Path file) {
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error", "-count_frames",
                "-show_entries", "stream=codec_name,nb_read_frames", "-of", "csv=p=0", path(file))), StandardCharsets.UTF_8);
        // a transport stream lists its streams once per program and once on their own
        return output.lines().filter(line -> !line.isBlank()).distinct().toList();
    }

    @Test
    @DisplayName("transcodes VP9 and Opus into a transport stream of H.264 and copied Opus")
    void encodeIntoTs() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = video("webm", List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-c:a", "libopus"));
        var sink = MediaSink.memory();
        try (var source = MediaSource.of(input)) {
            Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MPEG_TS));
        }
        var output = save("vp9-opus.ts", sink.toByteArray());
        Oracle.assertDecodesCleanly(output);
        var streams = streams(output);
        assertEquals("h264,50", streams.get(0));
        // the Opus encoder adds one frame for its pre-skip
        assertTrue(streams.get(1).equals("opus,100") || streams.get(1).equals("opus,101"), streams.toString());
    }

    @Test
    @DisplayName("transcodes H.264 and AAC into a smaller H.264 transport stream")
    void scaleIntoTs() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = video("mp4", List.of("-c:v", "libx264", "-c:a", "aac"));
        var sink = MediaSink.memory();
        try (var source = MediaSource.of(input)) {
            Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MPEG_TS)
                    .toBuilder().video(VideoTranscodeOptions.builder().maxSize(96, 72).build()).build());
        }
        var output = save("scaled.ts", sink.toByteArray());
        Oracle.assertDecodesCleanly(output);
        var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v", "-show_entries", "stream=width,height", "-of", "csv=p=0", path(output))), StandardCharsets.UTF_8);
        assertEquals("96,72", probe.lines().findFirst().orElseThrow().strip());
    }

    @Test
    @DisplayName("packages a ladder of two transcodes into HLS and DASH that ffmpeg reads")
    void ladder() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = video("mp4", List.of("-c:v", "libx264", "-c:a", "aac"));
        var rungs = new ArrayList<byte[]>();
        for (var height : new int[]{120, 72}) {
            var sink = MediaSink.memory();
            try (var source = MediaSource.of(input)) {
                Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MP4)
                        .toBuilder().video(VideoTranscodeOptions.encode(Codec.H264).toBuilder().maxSize(0, height)
                                .build()).build());
            }
            rungs.add(sink.toByteArray());
        }
        var directory = OUTPUT.resolve("ladder");
        var renditions = List.of(Rendition.of("high", MediaSource.of(rungs.get(0))), Rendition.of("low", MediaSource.of(rungs.get(1))));
        var result = Packager.pack(renditions, PackageOutput.directory(directory), PackagingOptions.cmaf().toBuilder()
                .segmentMicros(1_000_000).build());
        assertEquals(4, result.streams().size());
        for (var manifest : List.of(result.masterPlaylist().orElseThrow(), result.manifest().orElseThrow())) {
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-xerror", "-i",
                    path(directory.resolve(manifest)), "-map", "0", "-f", "null", "-"));
        }
    }

    @Test
    @DisplayName("decodes a transport stream through the audio and video readers")
    void readers() {
        var input = video("ts", List.of("-c:v", "libx264", "-c:a", "aac"));
        try (var source = MediaSource.of(input); var reader = VideoReader.open(source)) {
            var frames = 0;
            while (reader.read() != null) {
                frames++;
            }
            assertEquals(50, frames);
        }
        try (var source = MediaSource.of(input); var reader = AudioReader.open(source)) {
            var samples = 0L;
            for (var buffer = reader.read(); buffer != null; buffer = reader.read()) {
                samples += buffer.frames();
            }
            // a transport stream does not signal the AAC encoder's priming and padding: up to two
            // frames more than the two seconds of the tone
            assertTrue(samples >= 96_000 && samples <= 96_000 + 2 * 1024, "decoded samples " + samples);
        }
    }
}
