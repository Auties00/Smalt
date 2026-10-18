package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@link Images} facade: content-based format recognition and dispatch to every
 * merged decoder, the single-frame animation view of still images, and the errors for inputs
 * that are not images or not decodable.
 */
class ImagesTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "jpg, JPEG, yuvj420p",
            "png, PNG, rgba",
            "gif, GIF, pal8",
            "bmp, BMP, bgr24"
    })
    @DisplayName("recognizes, probes and decodes each format by content")
    void dispatch(String extension, ImageFormat format, String pixelFormat) {
        var file = Fixtures.testImage(extension, 66, 44, List.of("-pix_fmt", pixelFormat));
        var source = MediaSource.of(Fixtures.read(file));
        assertEquals(Optional.of(format), Images.detect(source));
        var info = Images.probe(source);
        assertEquals(format, info.format());
        assertEquals(66, info.width());
        assertEquals(44, info.height());
        var decoded = Images.decode(source, ImageDecoderOptions.defaults());
        assertEquals(format, decoded.format());
        assertEquals(66, decoded.picture().width());
        assertEquals(44, decoded.picture().height());
    }

    @Test
    @DisplayName("presents a still image as a single frame played once")
    void stillAnimation() {
        var file = Fixtures.testImage("jpg", 16, 8, List.of("-pix_fmt", "yuvj444p"));
        try (var reader = Images.openAnimation(MediaSource.of(Fixtures.read(file)), ImageDecoderOptions.defaults())) {
            assertEquals(1, reader.frameCount());
            assertEquals(1, reader.loopCount());
            var frame = reader.read();
            assertEquals(16, frame.picture().width());
            assertEquals(0, frame.durationMicros());
            assertEquals(0, frame.timestampMicros());
            assertNull(reader.read());
        }
    }

    @Test
    @DisplayName("rejects non-images and empty input with Unsupported")
    void unsupported() {
        var text = MediaSource.of("not an image at all".getBytes(StandardCharsets.US_ASCII));
        assertEquals(Optional.empty(), Images.detect(text));
        var unknown = assertThrows(MediaException.Unsupported.class, () -> Images.decode(text, ImageDecoderOptions.defaults()));
        assertEquals("unknown image", unknown.format());
        assertThrows(MediaException.Unsupported.class, () -> Images.decode(MediaSource.of(new byte[0]), ImageDecoderOptions.defaults()));
    }
}
