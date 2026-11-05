package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds transparent animated images (APNG, GIF, WebP) from {@link AlphaClips#frame} and checks that a transcode
 * into video keeps, or flattens, their transparency, for the transcode tests of the VP8 and VP9 alpha streams.
 */
public final class AlphaAnimations {
    /** The display time of every frame, in microseconds. */
    public static final long FRAME_MICROS = 100_000;

    private AlphaAnimations() {
        throw new AssertionError();
    }

    /** Writes an animation of {@code frames} synthetic transparent frames with Smalt's own writer of a format. */
    public static byte[] animation(ImageFormat format, int width, int height, int frames) {
        var sink = MediaSink.memory();
        try (var writer = Images.openAnimationWriter(format, width, height, sink)) {
            for (var index = 0; index < frames; index++) {
                writer.write(AlphaClips.frame(index, width, height), FRAME_MICROS);
            }
            writer.finish();
        }
        return sink.toByteArray();
    }

    /** Returns the alpha planes of the frames of an animation as Smalt decodes it. */
    public static List<byte[]> alphaPlanes(byte[] animation) {
        var planes = new ArrayList<byte[]>();
        try (var reader = Images.openAnimation(MediaSource.of(animation), ImageDecoderOptions.defaults())) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                planes.add(AlphaClips.alpha(ImageOps.toRgba(frame.picture())));
            }
        }
        return planes;
    }

    /** Transcodes an animation into a video file with options and returns the file. */
    public static byte[] transcode(byte[] animation, TranscodeOptions options) {
        var sink = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(animation), sink, options);
        return sink.toByteArray();
    }

    /**
     * Asserts that a transcoded video carries alpha and that each of its frames has the alpha of the animation
     * frame shown at its time, within a PSNR.
     */
    public static void assertKeepsAlpha(String label, byte[] animation, byte[] video, double minimumPsnr) {
        var expected = alphaPlanes(animation);
        try (var reader = VideoReader.open(MediaSource.of(video))) {
            assertTrue(reader.track().hasAlpha(), label + ": the track declares alpha");
            var index = 0;
            for (VideoFrame frame = reader.read(); frame != null; frame = reader.read(), index++) {
                assertTrue(frame.picture().hasAlpha(), label + " frame " + index);
                var shown = (int) Math.min(expected.size() - 1,
                        Math.max(0, Math.round(reader.track().timeBase().toMicros(frame.pts()) / (double) FRAME_MICROS)));
                var psnr = Quality.psnr(expected.get(shown), AlphaClips.alpha(frame.picture()));
                assertTrue(psnr >= minimumPsnr, label + " frame " + index + " alpha PSNR " + psnr);
            }
            assertEquals(expected.size(), index, label + ": frame count");
        }
    }

    /** Asserts that a transcoded video is opaque: its track declares no alpha and its frames have no alpha plane. */
    public static void assertOpaque(String label, byte[] video) {
        try (var reader = VideoReader.open(MediaSource.of(video))) {
            assertFalse(reader.track().hasAlpha(), label);
            for (VideoFrame frame = reader.read(); frame != null; frame = reader.read()) {
                assertFalse(frame.picture().hasAlpha(), label);
            }
        }
    }

    /** Returns the options of a transcode into a container with every stream but video dropped. */
    public static TranscodeOptions.Builder into(OutputContainer container) {
        return TranscodeOptions.of(container).toBuilder().noAudio();
    }
}
