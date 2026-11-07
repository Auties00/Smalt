package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.heic.HeicDecoder;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes HEIF JPEG 2000 items deeper than 31 bits, written by the Part 2 test codestream writer of the image
 * module (Smalt's encoder stops at 31 bits), against the wide samples the JPEG 2000 decoder returns for the bare
 * codestream.
 */
@DisplayName("HEIF JPEG 2000 items wider than 31 bits")
class HeicWideJpeg2000ItemTest {
    @ParameterizedTest(name = "signed: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("38-bit samples, signed ones offset by 2^37, keep their 31 most significant bits")
    void thirtyEight(boolean signed) {
        var width = 21;
        var height = 13;
        var random = new Random(500);
        var coefficients = new long[width * height];
        for (var i = 0; i < coefficients.length; i++) {
            coefficients[i] = random.nextLong(-60000, 60000);
        }
        coefficients[0] = -60000;
        coefficients[1] = 59999;
        var codestream = new Part2Writer(width, height, 1, 38, signed).write(new long[][]{coefficients});
        var channel = Jp2Decoder.decodeChannels(MediaSource.of(codestream)).channels().getFirst();
        assertTrue(channel.wide());
        assertEquals(38, channel.precision());
        var offset = signed ? 1L << 37 : 0;
        var builder = new HeifBuilder().brands("j2ki", "j2ki", "mif1", "miaf");
        builder.primary(builder.item("j2k1", codestream, HeifBuilder.plain(HeifBuilder.ispe(width, height))));
        var picture = assertInstanceOf(ChannelFloatPicture.class, HeicDecoder.decode(MediaSource.of(builder.build()),
                ImageDecoderOptions.builder().keepChannels(true).build()).picture());
        assertEquals(PixelLayout.GRAY, picture.layout());
        var max = (double) ((1L << 31) - 1);
        var want = new float[width * height];
        for (var i = 0; i < want.length; i++) {
            want[i] = (float) (((channel.wideSamples()[i] + offset) >>> 7) / max);
        }
        var got = new float[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), got, y * width, width);
        }
        assertArrayEquals(want, got);
        // the unsigned codestream holds the same values shifted by its DC level, so both decode alike
        for (var i = 0; i < want.length; i++) {
            assertEquals((float) (((1L << 37) + coefficients[i] >>> 7) / max), got[i], "sample " + i);
        }
    }
}
