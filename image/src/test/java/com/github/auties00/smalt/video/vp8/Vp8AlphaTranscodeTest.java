package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.image.AlphaAnimations;
import com.github.auties00.smalt.testkit.image.AlphaClips;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Transcodes transparent APNG, GIF and WebP animations into VP8 WebM and Matroska video, which keeps their alpha as a
 * second VP8 stream unless the options flatten it, and checks the result in Smalt and in ffmpeg's libvpx decoder.
 */
@DisplayName("transcoding transparent animations to VP8")
class Vp8AlphaTranscodeTest {
    private static final int WIDTH = 50;
    private static final int HEIGHT = 30;
    private static final int FRAMES = 12;

    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({"PNG, WEBM, 36", "WEBP, WEBM, 34", "GIF, WEBM, 28", "PNG, MATROSKA, 36"})
    @DisplayName("keeps the transparency of an animation as an alpha stream")
    void keepsAlpha(ImageFormat format, OutputContainer container, double psnr) throws IOException {
        var animation = AlphaAnimations.animation(format, WIDTH, HEIGHT, FRAMES);
        var video = AlphaAnimations.transcode(animation, AlphaAnimations.into(container)
                .video(VideoTranscodeOptions.encode(Codec.VP8)).build());
        AlphaAnimations.assertKeepsAlpha(format + " to " + container, animation, video, psnr);
        var file = Scratch.directory("vp8-alpha-transcode").resolve(format + "." + container.extension());
        Files.write(file, video);
        Oracle.assertDecodesCleanly(file);
        var ffmpeg = AlphaClips.ffmpegYuva(file, "libvpx");
        assertEquals(FRAMES * AlphaClips.yuvaFrameSize(WIDTH, HEIGHT), ffmpeg.length);
        var smalt = AlphaClips.read(file);
        for (var index = 0; index < FRAMES; index++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(smalt.get(index).alpha(),
                    AlphaClips.yuvaAlpha(ffmpeg, index, WIDTH, HEIGHT), "ffmpeg's alpha of frame " + index);
        }
    }

    @Test
    @DisplayName("flattens the transparency when the options ask for it or the file cannot store it")
    void flattens() {
        var animation = AlphaAnimations.animation(ImageFormat.PNG, WIDTH, HEIGHT, FRAMES);
        AlphaAnimations.assertOpaque("flatten", AlphaAnimations.transcode(animation, AlphaAnimations.into(OutputContainer.WEBM)
                .video(VideoTranscodeOptions.encode(Codec.VP8).toBuilder().flatten(true).build()).build()));
        AlphaAnimations.assertOpaque("IVF", AlphaAnimations.transcode(animation, AlphaAnimations.into(OutputContainer.IVF)
                .video(VideoTranscodeOptions.encode(Codec.VP8)).build()));
    }

    @Test
    @DisplayName("keeps the alpha of a transparent VP8 video it re-encodes")
    void reencodes() throws IOException {
        var animation = AlphaAnimations.animation(ImageFormat.PNG, WIDTH, HEIGHT, FRAMES);
        var first = AlphaAnimations.transcode(animation, AlphaAnimations.into(OutputContainer.WEBM)
                .video(VideoTranscodeOptions.encode(Codec.VP8)).build());
        var second = AlphaAnimations.transcode(first, AlphaAnimations.into(OutputContainer.MATROSKA)
                .video(VideoTranscodeOptions.encode(Codec.VP8)).build());
        AlphaAnimations.assertKeepsAlpha("re-encoded", animation, second, 30);
    }
}
