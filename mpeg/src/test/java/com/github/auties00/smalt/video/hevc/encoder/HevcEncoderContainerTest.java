package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoReader;
import com.github.auties00.smalt.video.VideoWriter;
import com.github.auties00.smalt.video.VideoWriterOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the HEVC encoder behind the codec-independent interfaces: the provider {@link VideoEncoders} finds, the
 * {@link VideoWriter} into MP4, Matroska, MPEG transport stream and raw HEVC files, and the transcoder target, each
 * file read back by Smalt and decoded cleanly by ffmpeg.
 */
@DisplayName("HEVC encoder in containers")
class HevcEncoderContainerTest {
    @Test
    @DisplayName("VideoEncoders creates the HEVC encoder, at 10 bits when asked")
    void provider() {
        assertTrue(VideoEncoders.isSupported(Codec.HEVC));
        var options = VideoEncoderOptions.of(64, 48, new Rational(25, 1), 200_000);
        try (var encoder = VideoEncoders.create(Codec.HEVC, options)) {
            assertEquals(Codec.HEVC, encoder.codec());
            assertTrue(encoder instanceof HevcEncoder);
            assertEquals(new Rational(1, 25), encoder.timeBase());
            assertTrue(encoder.codecConfig().isPresent());
        }
        try (var encoder = VideoEncoders.create(Codec.HEVC, options.toBuilder().bitDepth(10).realtime(true)
                .build())) {
            var hevc = (HevcEncoder) encoder;
            assertEquals(HevcProfile.MAIN_10, hevc.options().profile());
            assertEquals(10, hevc.options().bitDepth());
            assertTrue(hevc.options().realtime());
            assertTrue(encoder.codecConfig().isEmpty(), "real-time packets are Annex B");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = OutputContainer.class, names = {"MP4", "FRAGMENTED_MP4", "MOV", "MATROSKA", "MPEG_TS", "HEVC"})
    @DisplayName("a video writer writes HEVC that Smalt reads back and ffmpeg decodes")
    void writer(OutputContainer container) {
        var pictures = HevcEncoderHarness.sequence(96, 64, 12, 81);
        var sink = MediaSink.memory();
        try (var writer = VideoWriter.open(sink, container, VideoWriterOptions.builder().codec(Codec.HEVC)
                .size(96, 64).frameRate(new Rational(30, 1)).bitrate(300_000).build())) {
            for (var picture : pictures) {
                writer.write(picture);
            }
            writer.finish();
        }
        var bytes = sink.toByteArray();
        var frames = 0;
        var worst = Double.MAX_VALUE;
        try (var reader = VideoReader.open(MediaSource.of(bytes))) {
            assertEquals(Codec.HEVC, reader.track().codec());
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                worst = Math.min(worst, HevcEncoderHarness.psnr(frame.picture(), pictures.get(frames)));
                frames++;
            }
        }
        assertEquals(12, frames);
        assertTrue(worst > 25, "PSNR " + worst);
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = HevcEncoderHarness.write("writer." + container.extension(), bytes);
        if (container == OutputContainer.HEVC) {
            Oracle.assertFfmpegDecodes(file);
        } else {
            Oracle.assertDecodesCleanly(file);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = OutputContainer.class, names = {"MP4", "MOV", "MATROSKA", "MPEG_TS"})
    @DisplayName("the transcoder encodes HEVC into MP4, QuickTime, Matroska and transport streams")
    void transcode(OutputContainer container) {
        var pictures = HevcEncoderHarness.sequence(80, 48, 10, 83);
        var source = MediaSink.memory();
        try (var writer = VideoWriter.open(source, OutputContainer.MATROSKA, VideoWriterOptions.builder()
                .codec(Codec.H264).size(80, 48).frameRate(new Rational(30, 1)).build())) {
            for (var picture : pictures) {
                writer.write(picture);
            }
            writer.finish();
        }
        var output = MediaSink.memory();
        var result = Transcoder.transcode(MediaSource.of(source.toByteArray()), output, TranscodeOptions.builder()
                .container(container).video(VideoTranscodeOptions.encode(Codec.HEVC)).build());
        assertTrue(result.streams().stream().anyMatch(stream -> !stream.copied()
                && stream.track().codec() == Codec.HEVC), result.toString());
        var bytes = output.toByteArray();
        var frames = 0;
        try (var reader = VideoReader.open(MediaSource.of(bytes))) {
            assertEquals(Codec.HEVC, reader.track().codec());
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                frames++;
            }
        }
        assertEquals(10, frames);
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assertDecodesCleanly(HevcEncoderHarness.write("transcoded." + container.extension(), bytes));
    }

    @Test
    @DisplayName("the containers that carry HEVC list it among the codecs a transcode can pick")
    void listed() {
        for (var container : List.of(OutputContainer.MP4, OutputContainer.FRAGMENTED_MP4, OutputContainer.MOV,
                OutputContainer.MATROSKA, OutputContainer.MPEG_TS, OutputContainer.HEVC)) {
            assertTrue(container.supports(Codec.HEVC), container.toString());
            assertTrue(container.videoCodecs().contains(Codec.HEVC), container.toString());
        }
    }
}
