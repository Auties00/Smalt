package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.container.dv.DvDemuxer;
import com.github.auties00.smalt.container.dv.DvFormat;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transcodes files of the common codecs, made by ffmpeg, into raw DV through every artifact: the decoders of the
 * inputs, the DV encoder and the audio resampler. The outputs pass the test kit's DV validator and ffmpeg's full
 * decode, and keep the duration of their source within one frame.
 */
@DisplayName("Transcode into raw DV from common inputs")
class DvTargetTest {
    static Stream<Arguments> inputs() {
        Supplier<Path> h264 = () -> Fixtures.testVideo("mp4", 320, 240, 1.0, List.of("-c:v", "libx264", "-pix_fmt",
                "yuv420p", "-bf", "2", "-c:a", "aac", "-shortest"));
        Supplier<Path> vp9 = () -> Fixtures.testVideo("webm", 640, 360, 1.0, List.of("-c:v", "libvpx-vp9", "-b:v",
                "300k", "-deadline", "realtime", "-cpu-used", "8", "-c:a", "libopus", "-shortest"));
        return Stream.of(
                Arguments.of("MP4 H.264+AAC", h264, DvFormat.DV_525, 4),
                Arguments.of("MP4 H.264+AAC to DVCPRO50", h264, DvFormat.DVCPRO50_625, 2),
                Arguments.of("WebM VP9+Opus", vp9, DvFormat.DV_525, 2),
                Arguments.of("WebM VP9+Opus to DVCPRO HD", vp9, DvFormat.DVCPRO_HD_1080I50, 2));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    @DisplayName("writes valid DV of the source's duration with its audio at 48 kHz")
    void transcodes(String name, Supplier<Path> input, DvFormat format, int channels, @TempDir Path directory) {
        var output = directory.resolve("out.dv");
        var options = TranscodeOptions.builder().container(OutputContainer.DV)
                .video(VideoTranscodeOptions.builder().dvFormat(format).build())
                .audio(AudioTranscodeOptions.builder().maxChannels(channels).build()).build();
        TranscodeResult result;
        try (var in = MediaSource.of(input.get()); var out = MediaSink.of(output)) {
            result = Transcoder.transcode(in, out, options);
        }
        Oracle.assertDecodesCleanly(output);
        var data = Fixtures.read(output);
        var summary = Validators.assertValidDv(name, data);
        assertEquals(format.frameSize(), summary.frameSize());
        var frameSeconds = format.frameRate().toDouble();
        assertEquals(1.0, summary.frames() / frameSeconds, 1 / frameSeconds + 1e-9, "duration");
        assertEquals(1.0, result.durationMicros() / 1e6, 1 / frameSeconds + 0.03, "reported duration");
        try (var demuxer = new DvDemuxer(MediaSource.of(data))) {
            var video = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(format.width(), video.width());
            assertEquals(format.frameRate(), video.frameRate().orElseThrow());
            var audio = demuxer.tracks().stream().filter(track -> track instanceof AudioTrack).toList();
            assertTrue(!audio.isEmpty(), "audio pairs");
            // the sources are mono, which every format carries at 48 kHz whatever channel limit the options set
            audio.forEach(track -> assertEquals(48000, ((AudioTrack) track).sampleRate()));
        }
    }

    @Test
    @DisplayName("codes the frames of an animated GIF at the DV frame rate")
    void animation(@TempDir Path directory) {
        var gif = Fixtures.ffmpeg("gif", List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=10:duration=1"),
                List.of());
        var output = directory.resolve("animation.dv");
        try (var in = MediaSource.of(gif); var out = MediaSink.of(output)) {
            Transcoder.transcode(in, out, TranscodeOptions.of(OutputContainer.DV));
        }
        Oracle.assertDecodesCleanly(output);
        var summary = Validators.assertValidDv("animation", Fixtures.read(output));
        // 160 by 120 at 10 frames per second: 525/60 DV, one second of 30000/1001 frames
        assertEquals(DvFormat.DV_525.frameSize(), summary.frameSize());
        assertEquals(30, summary.frames(), 1);
    }
}
