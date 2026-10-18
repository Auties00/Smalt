package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the options convention on {@link PngEncoderOptions} and the encoder entry points that
 * take no options, take any picture, or write to a sink.
 */
@DisplayName("PngEncoderOptions")
class PngEncoderOptionsTest {
    private static RgbaPicture gradient() {
        var picture = RgbaPicture.allocate(29, 17, true);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i += 4) {
            pixels[i] = (byte) i;
            pixels[i + 1] = (byte) (i >> 2);
            pixels[i + 2] = (byte) (i * 3);
            pixels[i + 3] = (byte) (200 + (i & 31));
        }
        return picture;
    }

    @Test
    @DisplayName("defaults are level 6 with adaptive filtering")
    void defaults() {
        var defaults = PngEncoderOptions.defaults();
        assertEquals(6, defaults.compressionLevel());
        assertEquals(PngEncoderOptions.Filter.ADAPTIVE, defaults.filter());
        assertEquals(defaults, PngEncoderOptions.builder().build());
    }

    @Test
    @DisplayName("withers, builders and value equality")
    void convention() {
        var base = PngEncoderOptions.defaults();
        var changed = base.toBuilder().compressionLevel(9).filter(PngEncoderOptions.Filter.PAETH).build();
        assertEquals(6, base.compressionLevel());
        assertEquals(9, changed.compressionLevel());
        assertEquals(PngEncoderOptions.Filter.PAETH, changed.filter());
        assertNotEquals(base, changed);
        var built = PngEncoderOptions.builder().compressionLevel(9).filter(PngEncoderOptions.Filter.PAETH).build();
        assertEquals(changed, built);
        assertEquals(changed.hashCode(), built.hashCode());
        assertEquals(built, built.toBuilder().build());
        assertEquals("PngEncoderOptions[compressionLevel=9, filter=PAETH]", built.toString());
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {-1, 10})
    @DisplayName("rejects a compression level outside 0..9")
    void rejectsLevel(int level) {
        assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().compressionLevel(level).build());
        assertThrows(IllegalArgumentException.class, () -> PngEncoderOptions.builder().compressionLevel(level).build());
    }

    @Test
    @DisplayName("rejects a null filter")
    void rejectsFilter() {
        assertThrows(NullPointerException.class, () -> PngEncoderOptions.builder().filter(null).build());
    }

    @Test
    @DisplayName("encode without options and encode to a sink write the bytes of the defaults")
    void entryPoints() {
        var picture = gradient();
        var expected = PngEncoder.encode(picture, PngEncoderOptions.defaults());
        assertArrayEquals(expected, PngEncoder.encode(picture));
        var sink = MediaSink.memory();
        PngEncoder.encode(picture, PngEncoderOptions.defaults(), sink);
        assertArrayEquals(expected, sink.toByteArray());
    }

    @Test
    @DisplayName("a planar picture encodes as its RGBA conversion")
    void planar() {
        var yuv = ImageOps.toYuv(gradient(), ChromaFormat.YUV420, ColorInfo.JPEG);
        assertArrayEquals(PngEncoder.encode(ImageOps.toRgba(yuv)), PngEncoder.encode(yuv));
    }
}
