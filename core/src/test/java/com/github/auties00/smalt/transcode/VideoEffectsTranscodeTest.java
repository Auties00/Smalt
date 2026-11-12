package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.auties00.smalt.transcode.TranscodeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transcodes uncompressed YUV4MPEG2 video through the timing and fade steps and compares each output picture with
 * its definition: fades blend every sample toward the fade color's code values by the gain at the frame's time, and
 * a speed change divides the timestamps and multiplies the frame rate.
 */
@DisplayName("Video effects in a transcode")
class VideoEffectsTranscodeTest {
    static final Rational FPS = new Rational(10, 1);

    static byte[] clip(int frames) {
        return y4m(raw(0, 32, 16, FPS, frames, index -> gradient(32, 16, index)));
    }

    static TranscodeOptions.Builder y4mOptions(VideoTranscodeOptions.Builder video) {
        return TranscodeOptions.of(OutputContainer.Y4M).toBuilder().video(video.codec(Codec.RAW_VIDEO)
                .mode(StreamMode.ENCODE).build());
    }

    @Test
    @DisplayName("fades in from a color and out to it, sample for sample")
    void fades() {
        var fadeList = List.of(Fade.in(500_000), Fade.out(1_500_000, 500_000));
        var options = y4mOptions(VideoTranscodeOptions.builder().fadeColor(0xFF0000).fades(fadeList)).build();
        var frames = video(transcode(clip(20), options));
        assertEquals(20, frames.size());
        var color = video(clip(1)).getFirst().picture().color();
        var red = FadeStep.yuvColor(color, 255, 0, 0, 8);
        for (var index = 0; index < 20; index++) {
            var gain = Fade.gain(fadeList, index * 100_000.0, 2_000_000);
            var weight = (int) Math.round(gain * 65_536);
            var target = red;
            if (gain >= 1) {
                assertArrayEquals(gradient(32, 16, index).y(), frames.get(index).picture().y(), "frame " + index);
                continue;
            }
            var source = gradient(32, 16, index);
            var actual = frames.get(index).picture();
            for (var position = 0; position < source.y().length; position++) {
                assertEquals(FadeStep.mix(source.y()[position] & 0xFF, target[0], weight), actual.y()[position] & 0xFF,
                        "luma of frame " + index);
            }
            for (var position = 0; position < source.u().length; position++) {
                assertEquals(FadeStep.mix(source.u()[position] & 0xFF, target[1], weight), actual.u()[position] & 0xFF);
                assertEquals(FadeStep.mix(source.v()[position] & 0xFF, target[2], weight), actual.v()[position] & 0xFF);
            }
        }
        assertEquals(red[0], meanLuma(frames.getFirst().picture()), 1e-9, "the first frame is the color");
    }

    @Test
    @DisplayName("converts the fade color to the code values of the picture's matrix, range and depth")
    void colors() {
        assertArrayEquals(new int[]{16, 128, 128}, FadeStep.yuvColor(ColorInfo.BT709_LIMITED, 0, 0, 0, 8));
        assertArrayEquals(new int[]{235, 128, 128}, FadeStep.yuvColor(ColorInfo.BT709_LIMITED, 255, 255, 255, 8));
        assertArrayEquals(new int[]{63, 102, 240}, FadeStep.yuvColor(ColorInfo.BT709_LIMITED, 255, 0, 0, 8));
        assertArrayEquals(new int[]{0, 128, 128}, FadeStep.yuvColor(ColorInfo.JPEG, 0, 0, 0, 8));
        assertArrayEquals(new int[]{64, 512, 512}, FadeStep.yuvColor(ColorInfo.BT709_LIMITED, 0, 0, 0, 10));
        var identity = ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.IDENTITY).withRange(ColorRange.FULL);
        assertArrayEquals(new int[]{255, 0, 128}, FadeStep.yuvColor(identity, 128, 255, 0, 8));
        assertEquals(100, FadeStep.mix(100, 16, 65_536));
        assertEquals(16, FadeStep.mix(100, 16, 0));
        assertEquals(58, FadeStep.mix(100, 16, 32_768));
    }

    @Test
    @DisplayName("a speed of 2 halves every timestamp and doubles the frame rate")
    void speed() {
        var output = transcode(clip(20), y4mOptions(VideoTranscodeOptions.builder()).speed(2).build());
        var frames = video(output);
        assertEquals(20, frames.size());
        for (var index = 0; index < 20; index++) {
            assertEquals(50_000L * index, frames.get(index).micros(), "frame " + index);
            assertArrayEquals(gradient(32, 16, index).y(), frames.get(index).picture().y());
        }
        try (var reader = VideoReader.open(MediaSource.of(output))) {
            assertEquals(new Rational(20, 1), reader.track().frameRate().orElseThrow());
        }
        var result = Transcoder.transcode(MediaSource.of(clip(20)), MediaSink.memory(),
                y4mOptions(VideoTranscodeOptions.builder()).speed(2).build());
        assertEquals(1_000_000, result.durationMicros());
    }

    @Test
    @DisplayName("a slower speed with a constant output rate repeats frames on the output timeline")
    void slowerConstantRate() {
        var output = transcode(clip(10), y4mOptions(VideoTranscodeOptions.builder().frameRate(FPS)).speed(0.5).build());
        var frames = video(output);
        assertEquals(20, frames.size());
        for (var index = 0; index < 20; index++) {
            assertArrayEquals(gradient(32, 16, index / 2).y(), frames.get(index).picture().y(), "frame " + index);
        }
    }

    @Test
    @DisplayName("a speed change cannot copy the stream")
    void noCopy() {
        var options = TranscodeOptions.remux(OutputContainer.Y4M).toBuilder().speed(1.5).build();
        assertThrows(MediaException.Unsupported.class, () -> transcode(clip(5), options));
        var fade = TranscodeOptions.remux(OutputContainer.Y4M).toBuilder()
                .video(VideoTranscodeOptions.copy().toBuilder().fades(List.of(Fade.in(100_000))).build()).build();
        assertThrows(MediaException.Unsupported.class, () -> transcode(clip(5), fade));
    }
}
