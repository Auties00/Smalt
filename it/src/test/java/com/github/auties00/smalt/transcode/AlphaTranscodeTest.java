package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.image.AlphaAnimations;
import com.github.auties00.smalt.testkit.image.AlphaClips;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transcodes transparent APNG, GIF and WebP animations into WebM and Matroska with the default video codec, VP9,
 * which keeps their transparency as an alpha stream, and into files that cannot store alpha, which flatten it;
 * ffmpeg's libvpx-vp9 decoder reads the same alpha planes from the output as Smalt.
 */
@DisplayName("transcoding transparent animations")
class AlphaTranscodeTest {
    private static final int WIDTH = 48;
    private static final int HEIGHT = 36;
    private static final int FRAMES = 10;

    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({"PNG, WEBM, 36", "WEBP, WEBM, 34", "GIF, MATROSKA, 28"})
    @DisplayName("keeps the transparency as a VP9 alpha stream, which ffmpeg's libvpx-vp9 decoder reads")
    void keepsAlpha(ImageFormat format, OutputContainer container, double psnr) throws IOException {
        var animation = AlphaAnimations.animation(format, WIDTH, HEIGHT, FRAMES);
        var video = AlphaAnimations.transcode(animation, AlphaAnimations.into(container).build());
        try (var reader = VideoReader.open(MediaSource.of(video))) {
            assertEquals(Codec.VP9, reader.track().codec());
        }
        AlphaAnimations.assertKeepsAlpha(format + " to " + container, animation, video, psnr);
        var file = Scratch.directory("alpha-transcode").resolve(format + "." + container.extension());
        Files.write(file, video);
        Oracle.assertDecodesCleanly(file);
        var ffmpeg = AlphaClips.ffmpegYuva(file, "libvpx-vp9");
        var smalt = AlphaClips.read(file);
        assertEquals(FRAMES, smalt.size());
        for (var index = 0; index < FRAMES; index++) {
            assertArrayEquals(smalt.get(index).alpha(), AlphaClips.yuvaAlpha(ffmpeg, index, WIDTH, HEIGHT),
                    "ffmpeg's alpha of frame " + index);
        }
    }

    static Stream<Arguments> processing() {
        var vp9 = VideoTranscodeOptions.encode(Codec.VP9).toBuilder();
        return Stream.of(
                Arguments.of("resized", vp9.build().toBuilder().maxSize(24, 18).build(), 24, 18),
                Arguments.of("cropped", vp9.build().toBuilder().crop(new Region(0, 0, 32, 24)).build(), 32, 24),
                Arguments.of("10-bit", vp9.build().toBuilder().bitDepth(10).build(), WIDTH, HEIGHT),
                Arguments.of("4:4:4", vp9.build().toBuilder().chromaFormat(ChromaFormat.YUV444).build(), WIDTH, HEIGHT),
                Arguments.of("blended frame rate", vp9.build().toBuilder().frameRate(new Rational(25, 1))
                        .frameRateConversion(FrameRateConversion.BLEND).build(), WIDTH, HEIGHT),
                Arguments.of("VP8 resized", VideoTranscodeOptions.encode(Codec.VP8).toBuilder().maxSize(24, 18).build(),
                        24, 18));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("processing")
    @DisplayName("keeps the transparency through resizing, cropping, pixel format and frame rate conversion")
    void processed(String label, VideoTranscodeOptions video, int width, int height) {
        var animation = AlphaAnimations.animation(ImageFormat.PNG, WIDTH, HEIGHT, FRAMES);
        var output = AlphaAnimations.transcode(animation, AlphaAnimations.into(OutputContainer.WEBM).video(video).build());
        try (var reader = VideoReader.open(MediaSource.of(output))) {
            assertTrue(reader.track().hasAlpha(), label + ": the track declares alpha");
            assertEquals(width, reader.track().width(), label);
            assertEquals(height, reader.track().height(), label);
            var frames = 0;
            for (VideoFrame frame = reader.read(); frame != null; frame = reader.read(), frames++) {
                assertTrue(frame.picture().hasAlpha(), label + " frame " + frames);
                var alpha = AlphaClips.alpha(frame.picture());
                // the top-left corner of every source frame is fully transparent and its disc fully opaque
                assertTrue((alpha[0] & 0xFF) < 24, label + " frame " + frames + " corner alpha " + (alpha[0] & 0xFF));
                var max = 0;
                for (var sample : alpha) {
                    max = Math.max(max, sample & 0xFF);
                }
                assertTrue(max >= 240, label + " frame " + frames + " largest alpha " + max);
            }
            assertTrue(frames >= FRAMES, label + ": " + frames + " frames");
        }
    }

    @Test
    @DisplayName("flattens the transparency into files that cannot store it, and on request")
    void flattens() {
        var animation = AlphaAnimations.animation(ImageFormat.PNG, WIDTH, HEIGHT, FRAMES);
        AlphaAnimations.assertOpaque("MP4", AlphaAnimations.transcode(animation,
                AlphaAnimations.into(OutputContainer.MP4).video(VideoTranscodeOptions.encode(Codec.VP9)).build()));
        AlphaAnimations.assertOpaque("AV1 WebM", AlphaAnimations.transcode(animation,
                AlphaAnimations.into(OutputContainer.WEBM).video(VideoTranscodeOptions.encode(Codec.AV1)).build()));
        AlphaAnimations.assertOpaque("flattened WebM", AlphaAnimations.transcode(animation,
                AlphaAnimations.into(OutputContainer.WEBM).video(VideoTranscodeOptions.defaults().toBuilder()
                        .flatten(true).build()).build()));
    }

    @Test
    @DisplayName("keeps the alpha of a transparent VP9 video it re-encodes to VP8")
    void reencodes() {
        var animation = AlphaAnimations.animation(ImageFormat.PNG, WIDTH, HEIGHT, FRAMES);
        var vp9 = AlphaAnimations.transcode(animation, AlphaAnimations.into(OutputContainer.WEBM).build());
        var vp8 = AlphaAnimations.transcode(vp9, AlphaAnimations.into(OutputContainer.WEBM)
                .video(VideoTranscodeOptions.encode(Codec.VP8)).build());
        AlphaAnimations.assertKeepsAlpha("VP9 to VP8", animation, vp8, 30);
    }
}
