package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.gif.GifEncoder;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the encoders that take any picture with channel pictures: each must code exactly what it codes for the
 * documented RGBA conversion of the picture, 16 bits for deep samples where the format holds them.
 */
@DisplayName("Encoders given channel pictures")
class ChannelEncodingTest {
    private static List<ChannelPicture> pictures() {
        var random = new SplittableRandom(11);
        var cmyk = new byte[19 * 13 * 4];
        random.nextBytes(cmyk);
        var deep = new short[17 * 9 * 5];
        for (var i = 0; i < deep.length; i++) {
            deep[i] = (short) random.nextInt(65536);
        }
        var floats = new float[15 * 11 * 3];
        for (var i = 0; i < floats.length; i++) {
            floats[i] = (float) (random.nextDouble() * 1.2 - 0.1);
        }
        return List.of(Channel8Picture.of(19, 13, 19 * 4, cmyk, PixelLayout.CMYK),
                Channel16Picture.of(17, 9, 17 * 5, deep, PixelLayout.CMYKA),
                ChannelFloatPicture.of(15, 11, 15 * 3, floats, PixelLayout.RGB));
    }

    @Test
    @DisplayName("PNG and JPEG 2000 code deep channel pictures at 16 bits and 8-bit ones at 8 bits")
    void deepFormats() {
        for (var picture : pictures()) {
            var deep = picture.bitDepth() > 8;
            Picture rgba = deep ? ImageOps.toRgba16(picture) : ImageOps.toRgba(picture);
            assertArrayEquals(PngEncoder.encode(rgba), PngEncoder.encode(picture), picture.layout().toString());
            if (picture.layout().colorModel() != ColorModel.CMYK) {
                // CMYK pictures keep their inks in JPEG 2000, as Jp2ChannelsTest covers
                assertArrayEquals(Jp2Encoder.encode(rgba, Jp2EncoderOptions.lossless()),
                        Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless()), picture.layout().toString());
            }
            if (deep) {
                var decoded = PngDecoder.decode(MediaSource.of(PngEncoder.encode(picture)),
                        ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
                var expected = (Rgba16Picture) rgba;
                assertArrayEquals(expected.pixels(), ((Rgba16Picture) decoded).pixels());
            }
        }
    }

    @Test
    @DisplayName("JPEG, WebP and GIF code the 8-bit RGBA conversion")
    void eightBitFormats() {
        for (var picture : pictures()) {
            var rgba = ImageOps.toRgba(picture);
            assertArrayEquals(JpegEncoder.encode(rgba), JpegEncoder.encode(picture));
            assertArrayEquals(WebpEncoder.encode(rgba, WebpEncoderOptions.lossless()),
                    WebpEncoder.encode(picture, WebpEncoderOptions.lossless()));
            assertArrayEquals(WebpEncoder.encode(rgba), WebpEncoder.encode(picture));
            assertArrayEquals(GifEncoder.encode(rgba), GifEncoder.encode(picture));
        }
    }

    @Test
    @DisplayName("ink separations, which have no RGB conversion without a profile, are rejected")
    void inks() {
        var inks = Channel8Picture.allocate(4, 4, PixelLayout.ofInks(List.of("Orange", "Green")));
        assertThrows(MediaException.Unsupported.class, () -> PngEncoder.encode(inks));
        assertThrows(MediaException.Unsupported.class, () -> JpegEncoder.encode(inks));
        assertThrows(MediaException.Unsupported.class, () -> WebpEncoder.encode(inks));
    }
}
