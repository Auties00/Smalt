package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transcodes a matrix of input containers and codecs into every output container and checks each
 * result with ffmpeg: it decodes without error, carries the expected streams and codecs, keeps
 * the frame count and the audio length, and keeps audio and video in sync.
 */
class TranscodeMatrixTest {
    /**
     * The seconds of every audio/video fixture.
     */
    private static final double SECONDS = 2.0;

    /**
     * Describes an input fixture.
     *
     * @param name    the display name
     * @param file    generates the fixture
     * @param video   the video codec, or {@code null}
     * @param audio   the audio codec, or {@code null}
     * @param frames  the number of video frames
     * @param seconds the presentation duration
     * @param animated whether the fixture is an animated image, whose frames are coded again in every output
     */
    record Input(String name, Supplier<Path> file, Codec video, Codec audio, int frames, double seconds,
            boolean animated) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Describes an output.
     *
     * @param name      the display name
     * @param container the container
     * @param video     the requested video codec, or {@code null} for the container's choice
     */
    record Target(String name, OutputContainer container, Codec video) {
        @Override
        public String toString() {
            return name;
        }

        TranscodeOptions options() {
            var options = TranscodeOptions.of(container);
            return video == null ? options : options.toBuilder().video(VideoTranscodeOptions.builder().codec(video)
                    .build()).build();
        }
    }

    static List<Input> inputs() {
        return List.of(
                av("MP4 H.264+AAC", "mp4", Codec.H264, Codec.AAC, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "2", "-c:a", "aac"),
                av("MKV H.264+AAC", "mkv", Codec.H264, Codec.AAC, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "2", "-c:a", "aac"),
                av("WebM VP9+Opus", "webm", Codec.VP9, Codec.OPUS, "-c:v", "libvpx-vp9", "-b:v", "200k", "-deadline", "realtime",
                        "-cpu-used", "8", "-c:a", "libopus"),
                av("WebM VP8+Vorbis", "webm", Codec.VP8, Codec.VORBIS, "-c:v", "libvpx", "-b:v", "200k", "-c:a", "libvorbis"),
                av("WebM AV1+Opus", "webm", Codec.AV1, Codec.OPUS, "-c:v", "libaom-av1", "-cpu-used", "8", "-b:v", "100k",
                        "-c:a", "libopus"),
                audio("Ogg Opus", "ogg", Codec.OPUS, "-c:a", "libopus"),
                audio("Ogg Vorbis", "ogg", Codec.VORBIS, "-c:a", "libvorbis"),
                audio("WAV", "wav", Codec.PCM_S16LE, "-c:a", "pcm_s16le"),
                audio("M4A AAC", "m4a", Codec.AAC, "-c:a", "aac"),
                audio("MP3", "mp3", Codec.MP3, "-c:a", "libmp3lame"),
                audio("FLAC", "flac", Codec.FLAC, "-c:a", "flac"),
                animation("GIF", "gif", List.of()),
                animation("APNG", "png", List.of("-plays", "0", "-f", "apng")),
                animation("animated WebP", "webp", List.of("-c:v", "libwebp_anim", "-loop", "0", "-lossless", "1")));
    }

    static List<Target> targets() {
        return List.of(
                new Target("MP4", OutputContainer.MP4, null),
                new Target("WebM VP9", OutputContainer.WEBM, Codec.VP9),
                new Target("WebM VP8", OutputContainer.WEBM, Codec.VP8),
                new Target("WebM AV1", OutputContainer.WEBM, Codec.AV1),
                new Target("Matroska", OutputContainer.MATROSKA, null),
                new Target("fragmented MP4", OutputContainer.FRAGMENTED_MP4, null),
                new Target("Ogg", OutputContainer.OGG, null),
                new Target("WAV", OutputContainer.WAV, null),
                new Target("M4A", OutputContainer.M4A, null),
                new Target("ADTS", OutputContainer.ADTS, null),
                new Target("AVI", OutputContainer.AVI, null));
    }

    static Stream<Arguments> matrix() {
        var cases = new ArrayList<Arguments>();
        for (var input : inputs()) {
            for (var target : targets()) {
                var hasVideo = input.video() != null && !target.container().videoCodecs().isEmpty();
                var hasAudio = input.audio() != null && !target.container().audioCodecs().isEmpty();
                if (hasVideo || hasAudio) {
                    cases.add(Arguments.of(input, target));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("matrix")
    @DisplayName("transcodes every input into every output that can carry one of its streams")
    void transcodes(Input input, Target target, @TempDir Path directory) {
        var source = input.file().get();
        var output = directory.resolve("out." + target.container().extension());
        TranscodeResult result;
        try (var in = MediaSource.of(source); var out = MediaSink.of(output)) {
            result = Transcoder.transcode(in, out, target.options());
        }
        Oracle.assertDecodesCleanly(output);
        var probe = MediaProbe.of(output);
        var container = target.container();
        var expectVideo = input.video() != null && !container.videoCodecs().isEmpty();
        var expectAudio = input.audio() != null && !container.audioCodecs().isEmpty();
        assertEquals(expectVideo ? 1 : 0, probe.of("video").size(), "video streams");
        assertEquals(expectAudio ? 1 : 0, probe.of("audio").size(), "audio streams");
        if (expectVideo) {
            var video = probe.video();
            // an animated image is decoded and coded again; a video track is copied when the container carries it
            var codec = target.video() != null ? target.video()
                    : !input.animated() && container.supports(input.video()) ? input.video()
                    : container.videoCodecs().getFirst();
            assertEquals(ffmpegName(codec), video.codec(), "video codec");
            assertEquals(input.frames(), video.decoded(), 1, "decoded video frames");
        }
        if (expectAudio) {
            var audio = probe.audio();
            // a WAV output keeps PCM and decodes coded audio unless the options name its codec
            var copied = container.supports(input.audio()) && (container != OutputContainer.WAV || input.audio().isPcm());
            var codec = copied ? input.audio() : container.audioCodecs().getFirst();
            assertEquals(ffmpegName(codec), audio.codec(), "audio codec");
            // ffmpeg's raw decoding keeps the padding of a final AAC frame trimmed only by an edit list
            var tolerance = codec == Codec.AAC || input.audio() == Codec.AAC || input.audio() == Codec.MP3 ? 0.05 : 0.025;
            assertEquals(input.seconds(), audio.audioSeconds(), tolerance, "decoded audio seconds");
        }
        if (expectVideo && expectAudio) {
            assertEquals(probe.video().start(), probe.audio().start(), 0.05, "audio and video start together");
        }
        assertEquals(input.seconds(), result.durationMicros() / 1e6, 0.1, "reported duration");
        assertEquals((expectVideo ? 1 : 0) + (expectAudio ? 1 : 0), result.streams().size(), "reported streams");
    }

    private static Input av(String name, String extension, Codec video, Codec audio, String... options) {
        var arguments = new ArrayList<>(List.of(options));
        arguments.add("-shortest");
        return new Input(name, () -> Fixtures.testVideo(extension, 320, 240, SECONDS, arguments), video, audio,
                (int) (SECONDS * 30), SECONDS, false);
    }

    private static Input audio(String name, String extension, Codec codec, String... options) {
        return new Input(name, () -> Fixtures.tone(extension, 440, 48_000, 2, SECONDS, List.of(options)), null, codec, 0,
                SECONDS, false);
    }

    private static Input animation(String name, String extension, List<String> options) {
        return new Input(name, () -> Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i",
                "testsrc2=size=160x120:rate=10:duration=1"), options), Codec.GIF, null, 10, 1.0, true);
    }

    private static String ffmpegName(Codec codec) {
        return switch (codec) {
            case PCM_S16LE -> "pcm_s16le";
            case PCM_S16BE -> "pcm_s16be";
            default -> codec.name().toLowerCase(Locale.ROOT);
        };
    }
}
