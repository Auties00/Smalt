package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSink;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the options convention on {@link JpegEncoderOptions} and the encoder entry points that
 * take no options or write to a sink.
 */
@DisplayName("JpegEncoderOptions")
class JpegEncoderOptionsTest {
    private static RgbaPicture gradient() {
        var picture = RgbaPicture.allocate(37, 21, false);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i += 4) {
            pixels[i] = (byte) i;
            pixels[i + 1] = (byte) (i >> 3);
            pixels[i + 2] = (byte) (255 - (i >> 2));
            pixels[i + 3] = (byte) 255;
        }
        return picture;
    }

    @Test
    @DisplayName("defaults are quality 75, 4:2:0, optimized Huffman tables and no restart markers")
    void defaults() {
        var defaults = JpegEncoderOptions.defaults();
        assertEquals(75, defaults.quality());
        assertEquals(ChromaFormat.YUV420, defaults.chromaFormat());
        assertTrue(defaults.optimizeHuffman());
        assertEquals(0, defaults.restartInterval());
        assertEquals(defaults, JpegEncoderOptions.ofQuality(75));
        assertEquals(defaults, JpegEncoderOptions.builder().build());
    }

    @Test
    @DisplayName("withers change one field and leave the original untouched")
    void withers() {
        var base = JpegEncoderOptions.ofQuality(90);
        var changed = base.toBuilder().chromaFormat(ChromaFormat.YUV444).optimizeHuffman(false).restartInterval(4)
                .quality(60).build();
        assertEquals(90, base.quality());
        assertEquals(ChromaFormat.YUV420, base.chromaFormat());
        assertEquals(60, changed.quality());
        assertEquals(ChromaFormat.YUV444, changed.chromaFormat());
        assertFalse(changed.optimizeHuffman());
        assertEquals(4, changed.restartInterval());
        assertNotEquals(base, changed);
    }

    @Test
    @DisplayName("builders round-trip through toBuilder() with value equality")
    void builder() {
        var built = JpegEncoderOptions.builder().quality(85).chromaFormat(ChromaFormat.YUV422).optimizeHuffman(false)
                .restartInterval(2).build();
        assertEquals(built, built.toBuilder().build());
        assertEquals(built.hashCode(), built.toBuilder().build().hashCode());
        assertEquals(JpegEncoderOptions.ofQuality(85).toBuilder().chromaFormat(ChromaFormat.YUV422)
                .optimizeHuffman(false)
                .restartInterval(2).build(), built);
        assertTrue(built.toString().contains("quality=85"));
    }

    @ParameterizedTest(name = "quality {0}")
    @ValueSource(ints = {-1, 101})
    @DisplayName("rejects a quality outside 0..100")
    void rejectsQuality(int quality) {
        assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.ofQuality(quality));
        assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder().quality(quality).build());
    }

    @Test
    @DisplayName("rejects a restart interval outside 0..65535 and a null subsampling")
    void rejectsOthers() {
        assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder().restartInterval(-1).build());
        assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder().restartInterval(65536).build());
        assertThrows(NullPointerException.class, () -> JpegEncoderOptions.builder().chromaFormat(null).build());
    }

    @Test
    @DisplayName("quality 0 codes as quality 1, as libjpeg does")
    void qualityZero() {
        var picture = gradient();
        assertArrayEquals(JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(1)),
                JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(0)));
    }

    @Test
    @DisplayName("encode without options and encode to a sink write the bytes of the defaults")
    void entryPoints() {
        var picture = gradient();
        var expected = JpegEncoder.encode(picture, JpegEncoderOptions.defaults());
        assertArrayEquals(expected, JpegEncoder.encode(picture));
        var sink = MediaSink.memory();
        JpegEncoder.encode(picture, JpegEncoderOptions.defaults(), sink);
        assertArrayEquals(expected, sink.toByteArray());
    }

    @Nested
    @DisplayName("ofQuality and chromaFormat")
    class QualityPresetAndChromaFormat {
        @Test
        @DisplayName("ofQuality(q) sets the quality and keeps the other defaults")
        void ofQuality() {
            assertEquals(JpegEncoderOptions.builder().quality(90).build(), JpegEncoderOptions.ofQuality(90));
            assertEquals(90, JpegEncoderOptions.ofQuality(90).quality());
            assertEquals(JpegEncoderOptions.defaults().chromaFormat(), JpegEncoderOptions.ofQuality(90).chromaFormat());
            assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.ofQuality(101));
        }

        @Test
        @DisplayName("Builder.chromaFormat sets chromaFormat()")
        void chromaFormat() {
            var options = JpegEncoderOptions.builder().chromaFormat(ChromaFormat.YUV444).build();
            assertEquals(ChromaFormat.YUV444, options.chromaFormat());
            assertEquals(ChromaFormat.YUV420, JpegEncoderOptions.defaults().chromaFormat());
        }
    }
}
