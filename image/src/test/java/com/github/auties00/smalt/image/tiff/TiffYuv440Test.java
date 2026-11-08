package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers 4:4:0 pictures and settings in the TIFF encoder: TIFF never subsamples chroma more vertically than
 * horizontally, so the options reject 4:4:0 Y'CbCr pages, and a 4:4:0 picture codes as its conversion to the page's
 * subsampling.
 */
@DisplayName("TIFF and 4:4:0")
class TiffYuv440Test {
    @Test
    @DisplayName("4:4:0 Y'CbCr pages, plain or JPEG compressed, are rejected")
    void rejected() {
        assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.builder()
                .layout(TiffEncoderOptions.Layout.YCBCR).chromaFormat(ChromaFormat.YUV440).build());
        assertThrows(IllegalArgumentException.class, () -> TiffEncoderOptions.builder()
                .compression(TiffCompression.JPEG)
                .jpegOptions(JpegEncoderOptions.builder().chromaFormat(ChromaFormat.YUV440).build()).build());
    }

    @Test
    @DisplayName("a 4:4:0 picture codes as its 4:2:2 conversion")
    void converted() {
        var width = 23;
        var height = 17;
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV440, false, ColorInfo.JPEG);
        for (var i = 0; i < picture.y().length; i++) {
            picture.y()[i] = (byte) (i * 37);
        }
        for (var i = 0; i < picture.u().length; i++) {
            picture.u()[i] = (byte) (64 + (i * 11) % 128);
            picture.v()[i] = (byte) (200 - (i * 7) % 128);
        }
        var options = TiffEncoderOptions.builder().layout(TiffEncoderOptions.Layout.YCBCR)
                .chromaFormat(ChromaFormat.YUV422).build();
        var direct = TiffEncoder.encode(picture, options);
        var reference = TiffEncoder.encode(ImageOps.toYuv(picture, ChromaFormat.YUV422, ColorInfo.JPEG), options);
        assertArrayEquals(pixels(reference), pixels(direct));
    }

    private static byte[] pixels(byte[] tiff) {
        var decoded = ImageOps.toRgba(TiffDecoder.decode(MediaSource.of(tiff)).picture());
        return Arrays.copyOf(((RgbaPicture) decoded).pixels(), decoded.width() * decoded.height() * 4);
    }
}
