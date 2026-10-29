package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the channel pictures of the JPEG XL decoder on conformance files: their layouts against the extra
 * channels the file declares, their samples against the planes of the floating-point decode, and their
 * conversions against the default RGBA output.
 */
@DisplayName("JPEG XL channel pictures")
class JxlChannelsTest {
    private static final ImageDecoderOptions KEEP = ImageDecoderOptions.builder().keepChannels(true).build();

    @Test
    @DisplayName("keep the samples of a floating-point image unclamped, equal to the floating-point planes")
    void floats() {
        var file = JxlDecoderTest.file("lossless_pfm");
        var planes = JxlDecoder.decodeFloat(MediaSource.of(file));
        var image = JxlDecoder.decode(MediaSource.of(file), KEEP);
        var picture = assertInstanceOf(ChannelFloatPicture.class, image.picture());
        var layout = picture.layout();
        assertEquals(planes.colorChannels() == 1 ? ColorModel.GRAY : ColorModel.RGB, layout.colorModel());
        assertEquals(planes.colorChannels() + planes.extraChannelTypes().size(), layout.count());
        var count = layout.count();
        var length = picture.width() * picture.height();
        for (var c = 0; c < count; c++) {
            var plane = planes.plane(c);
            for (var i = 0; i < length; i++) {
                assertEquals(Float.floatToRawIntBits(plane[i]), Float.floatToRawIntBits(picture.pixels()[i * count + c]));
            }
        }
        assertArrayEquals(planes.iccProfile(), image.iccProfile().orElseThrow());
        var wide = (Rgba16Picture) JxlDecoder.decode(MediaSource.of(file),
                ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
        var converted = ImageOps.toRgba16(picture);
        for (var i = 0; i < wide.pixels().length; i++) {
            // the RGBA output rounds in single precision, the channel conversion in double precision
            assertEquals(wide.pixels()[i] & 0xFFFF, converted.pixels()[i] & 0xFFFF, 1, "sample " + i);
        }
    }

    @Test
    @DisplayName("keep spot and alpha extra channels of an integer image in file order")
    void spot() {
        var file = JxlDecoderTest.file("spot");
        var planes = JxlDecoder.decodeFloat(MediaSource.of(file));
        var picture = (ChannelPicture) JxlDecoder.decode(MediaSource.of(file), KEEP).picture();
        var layout = picture.layout();
        assertEquals(ColorModel.RGB, layout.colorModel());
        assertEquals(3 + planes.extraChannelTypes().size(), layout.count());
        assertTrue(layout.indexOf(ChannelType.SPOT) >= 3);
        assertTrue(layout.hasAlpha());
        assertInstanceOf(Channel8Picture.class, picture);
        var eight = (Channel8Picture) picture;
        var count = layout.count();
        var length = picture.width() * picture.height();
        for (var c = 0; c < count; c++) {
            var plane = planes.plane(c);
            var premultiplied = layout.channel(c).type() == ChannelType.PREMULTIPLIED_ALPHA;
            assertFalse(premultiplied && !planes.alphaPremultiplied());
            for (var i = 0; i < length; i++) {
                var expected = Math.clamp((long) (plane[i] * 255.0F + 0.5F), 0, 255);
                assertEquals(expected, eight.pixels()[i * count + c] & 0xFF, "channel " + c + " sample " + i);
            }
        }
        // the RGBA picture composites the spot channels the channel picture keeps apart
        var plain = (RgbaPicture) JxlDecoder.decode(MediaSource.of(file)).picture();
        var infos = com.github.auties00.smalt.image.jxl.internal.JxlCodestream.open(file,
                com.github.auties00.smalt.MediaLimits.DEFAULT).header().extraChannels();
        var composited = new byte[length * 4];
        for (var i = 0; i < length; i++) {
            var rgb = new float[]{planes.plane(0)[i], planes.plane(1)[i], planes.plane(2)[i]};
            for (var e = 0; e < infos.size(); e++) {
                var spot = infos.get(e).spotColor();
                if (spot != null) {
                    var mix = spot[3] * planes.plane(3 + e)[i];
                    for (var c = 0; c < 3; c++) {
                        rgb[c] = mix * spot[c] + (1 - mix) * rgb[c];
                    }
                }
            }
            if (planes.alphaPremultiplied()) {
                var alpha = planes.extraChannel(JxlChannelType.ALPHA).orElseThrow()[i];
                for (var c = 0; c < 3; c++) {
                    rgb[c] = alpha > 1e-7f ? rgb[c] * (1.0f / alpha) : 0.0f;
                }
            }
            for (var c = 0; c < 3; c++) {
                composited[i * 4 + c] = (byte) Math.clamp((long) (rgb[c] * 255.0F + 0.5F), 0, 255);
            }
            composited[i * 4 + 3] = plain.pixels()[i * 4 + 3];
        }
        assertArrayEquals(composited, plain.pixels());
        assertFalse(java.util.Arrays.equals(ImageOps.toRgba(picture).pixels(), plain.pixels()),
                "the spot colors show in the RGBA picture only");
    }

    @Test
    @DisplayName("turn a black extra channel into CMYK inks and convert them through the profile")
    void cmyk() {
        var file = JxlDecoderTest.file("cmyk_layers");
        var planes = JxlDecoder.decodeFloat(MediaSource.of(file));
        var image = JxlDecoder.decode(MediaSource.of(file), KEEP);
        var picture = (ChannelPicture) image.picture();
        var layout = picture.layout();
        assertEquals(ColorModel.CMYK, layout.colorModel());
        assertEquals(ProfileColorSpace.CMYK, image.colorProfile().orElseThrow().colorSpace());
        var black = planes.extraChannel(JxlChannelType.BLACK).orElseThrow();
        var count = layout.count();
        var length = picture.width() * picture.height();
        for (var i = 0; i < length; i += 97) {
            for (var c = 0; c < 4; c++) {
                var plane = c < 3 ? planes.plane(c) : black;
                var expected = Math.clamp((long) ((1.0F - plane[i]) * 255.0F + 0.5F), 0, 255);
                assertEquals(expected, sample(picture, i * count + c) >> (picture.bitDepth() > 8 ? 8 : 0), 1,
                        "channel " + c);
            }
        }
        var plain = (RgbaPicture) JxlDecoder.decode(MediaSource.of(file)).picture();
        var managed = ImageOps.toSrgb(image);
        var converted = ImageOps.toRgba(ImageOps.toEightBit(managed.picture()));
        var worst = 0;
        for (var i = 0; i < plain.pixels().length; i++) {
            worst = Math.max(worst, Math.abs((plain.pixels()[i] & 0xFF) - (converted.pixels()[i] & 0xFF)));
        }
        // the RGBA output converts 16-bit inks, the channel picture holds 8-bit inks
        assertTrue(worst <= 3, "worst difference " + worst);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"grayscale", "alpha_nonpremultiplied", "patches"})
    @DisplayName("leave images that RGBA can hold in their usual pictures")
    void unchanged(String name) {
        var file = JxlDecoderTest.file(name);
        var kept = JxlDecoder.decode(MediaSource.of(file), KEEP).picture();
        var plain = JxlDecoder.decode(MediaSource.of(file)).picture();
        assertSame(plain.getClass(), kept.getClass());
        assertArrayEquals(((RgbaPicture) plain).pixels(), ((RgbaPicture) kept).pixels());
    }

    @Test
    @DisplayName("convert to sRGB when asked even with channels kept")
    void srgbWins() {
        var file = JxlDecoderTest.file("cmyk_layers");
        var both = JxlDecoder.decode(MediaSource.of(file), KEEP.toBuilder().convertToSrgb(true).build());
        assertInstanceOf(RgbaPicture.class, both.picture());
        assertEquals(PixelLayout.CMYK, ((ChannelPicture) JxlDecoder.decode(MediaSource.of(file), KEEP).picture())
                .layout().colorOnly());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lossless_pfm", "spot", "cmyk_layers"})
    @DisplayName("carry the XMP packet of the container, also through the sRGB conversion")
    void xmp(String name) {
        var file = JxlXmpTest.withXmp(JxlDecoderTest.file(name));
        var image = JxlDecoder.decode(MediaSource.of(file), KEEP);
        assertInstanceOf(ChannelPicture.class, image.picture());
        assertArrayEquals(JxlXmpTest.PACKET, image.xmp().orElseThrow());
        assertArrayEquals(JxlXmpTest.PACKET, ImageOps.toSrgb(image).xmp().orElseThrow());
    }

    private static int sample(ChannelPicture picture, int index) {
        return switch (picture) {
            case Channel8Picture eight -> eight.pixels()[index] & 0xFF;
            case Channel16Picture sixteen -> sixteen.pixels()[index] & 0xFFFF;
            case ChannelFloatPicture floating -> (int) floating.pixels()[index];
        };
    }
}
