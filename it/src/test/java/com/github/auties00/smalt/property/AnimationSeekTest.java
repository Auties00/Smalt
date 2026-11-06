package com.github.auties00.smalt.property;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.AnimationSeeks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeking an animation then reading equals reading it linearly: for every format Smalt writes animations in, an
 * animation of moving frames with uneven durations, read back through {@link Images#openAnimation}, returns after a
 * seek to frame starts, instants inside and at the end of frames, random times, times before the start and past the
 * end, the frame a linear read shows at that time, pixel for pixel, and then the frames that follow it
 * ({@link AnimationSeeks}).
 */
@DisplayName("Animation seek then read")
class AnimationSeekTest {
    private static final int FRAMES = 10;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ImageFormat.class, names = {"AVIF", "HEIC", "GIF", "PNG", "WEBP", "ANI"})
    @DisplayName("returns after a seek the frame and the frames a linear read returns from the target on")
    void seek(ImageFormat format) {
        assertTrue(Images.isAnimationEncodable(format), format + " animations are written");
        var sink = MediaSink.memory();
        try (var writer = Images.openAnimationWriter(format, 64, 48, sink)) {
            for (var frame = 0; frame < FRAMES; frame++) {
                writer.write(ImageOps.toRgba(Streams.picture(64, 48, frame, 7)), 50_000 + 10_000L * (frame % 3));
            }
            writer.finish();
        }
        var data = sink.toByteArray();
        AnimationSeeks.assertMatchesSequential(() -> Images.openAnimation(MediaSource.of(data)), format.ordinal());
    }
}
