package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.tiff.TiffCompression;
import com.github.auties00.smalt.image.tiff.TiffDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the TIFF files {@link SyntheticImages#tiffCompressions} codes for the training workloads decode to the
 * samples they were made from, which also checks Smalt's LZMA, LERC and NeXT decoders against independent encoders.
 */
@DisplayName("synthetic TIFF compressions of the training workloads")
class SyntheticCompressionsTest {
    @Test
    @DisplayName("every file decodes, the lossless ones to their source samples")
    void decode() {
        var source = RgbaPicture.allocate(37, 29, false);
        var pixels = source.pixels();
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (i & 3) == 3 ? (byte) 0xFF : (byte) (i * 7 + i / 148 * 13);
        }
        var files = SyntheticImages.tiffCompressions(source);
        var expected = new TiffCompression[]{TiffCompression.LZMA, TiffCompression.LERC, TiffCompression.LERC,
                TiffCompression.JBIG, TiffCompression.JBIG, TiffCompression.PIXARLOG, TiffCompression.PIXARLOG,
                TiffCompression.NEXT};
        assertEquals(expected.length, files.size());
        for (var i = 0; i < files.size(); i++) {
            try (var pages = TiffDecoder.openPages(MediaSource.of(files.get(i)))) {
                assertEquals(expected[i], pages.page(0).compression(), "file " + i);
            }
            TiffDecoder.decode(MediaSource.of(files.get(i)));
        }
        var rgb = (RgbaPicture) TiffDecoder.decode(MediaSource.of(files.get(0))).picture();
        var gray = (RgbaPicture) TiffDecoder.decode(MediaSource.of(files.get(1))).picture();
        var floats = (ChannelFloatPicture) TiffDecoder.decode(MediaSource.of(files.get(2)),
                ImageDecoderOptions.builder().keepChannels(true).build()).picture();
        var next = (RgbaPicture) TiffDecoder.decode(MediaSource.of(files.get(7))).picture();
        for (var y = 0; y < 29; y++) {
            for (var x = 0; x < 37; x++) {
                var at = y * source.stride() + x * 4;
                var green = pixels[at + 1] & 0xFF;
                for (var c = 0; c < 3; c++) {
                    assertEquals(pixels[at + c], rgb.pixels()[y * rgb.stride() + x * 4 + c], "LZMA " + x + "," + y);
                }
                assertEquals(green, gray.pixels()[y * gray.stride() + x * 4] & 0xFF, "LERC tiles " + x + "," + y);
                assertEquals(green / 255.0F, floats.pixels()[y * floats.stride() + x], "LERC sweep " + x + "," + y);
                assertEquals((green >> 6) * 85, next.pixels()[y * next.stride() + x * 4] & 0xFF, "NeXT " + x + "," + y);
            }
        }
    }
}
