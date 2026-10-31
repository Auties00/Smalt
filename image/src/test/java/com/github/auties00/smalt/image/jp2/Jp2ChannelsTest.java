package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the CMYK channel pictures of the JPEG 2000 codec: lossless round trips of every sample type and alpha
 * kind through the encoder and the decoder, the samples of independently built images of other precisions and
 * subsampling, the naive and color-managed conversions against the default decode, and hostile input.
 */
@DisplayName("JPEG 2000 channel pictures")
class Jp2ChannelsTest {
    private static final ImageDecoderOptions KEEP = ImageDecoderOptions.builder().keepChannels(true).build();

    private static final ImageDecoderOptions KEEP_WIDE = KEEP.toBuilder().highBitDepth(true).build();

    private static final PixelLayout PREMULTIPLIED = PixelLayout.CMYK
            .withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA));

    private static final PixelLayout DEPTH = PixelLayout.CMYKA.withChannel(Channel.of(ChannelType.DEPTH));

    private static ChannelPicture random(SplittableRandom random, int bits, int width, int height,
                                         PixelLayout layout) {
        var count = layout.count();
        var stride = width * count + random.nextInt(3);
        return switch (bits) {
            case 8 -> {
                var pixels = new byte[stride * height];
                random.nextBytes(pixels);
                yield Channel8Picture.of(width, height, stride, pixels, layout);
            }
            case 16 -> {
                var pixels = new short[stride * height];
                for (var i = 0; i < pixels.length; i++) {
                    pixels[i] = (short) random.nextInt(65536);
                }
                yield Channel16Picture.of(width, height, stride, pixels, layout);
            }
            default -> {
                var pixels = new float[stride * height];
                for (var i = 0; i < pixels.length; i++) {
                    pixels[i] = (float) (random.nextDouble() * 1.4 - 0.2);
                }
                yield ChannelFloatPicture.of(width, height, stride, pixels, layout);
            }
        };
    }

    private static int sample(ChannelPicture picture, int x, int y, int c) {
        var at = y * picture.stride() + x * picture.channelCount() + c;
        return switch (picture) {
            case Channel8Picture eight -> eight.pixels()[at] & 0xFF;
            case Channel16Picture sixteen -> sixteen.pixels()[at] & 0xFFFF;
            case ChannelFloatPicture _ -> throw new AssertionError();
        };
    }

    private static byte[] swopProfile() {
        try {
            return Files.readAllBytes(Conformance.fetch("skia-icc-misc-US_Web_Coated_SWOP_CMYK.icc",
                    "https://raw.githubusercontent.com/google/skia/0c98530bf7d68e59147c2f1b06d8825c4302af8c/resources/"
                            + "icc_profiles/misc/US_Web_Coated_SWOP_CMYK.icc",
                    "4855b8fabb96bdc6495d45d089bb8c8efb1ae18389e0dc9e75a5f701a9c0b662"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The RGB of one CMYK pixel as OpenJPEG's color_cmyk_to_rgb computes it, the default decode output. */
    private static int openJpegRgb(int ink, int black, int max) {
        var scale = 1.0f / (float) max;
        return Math.clamp((int) (255.0f * (1.0f - (float) ink * scale) * (1.0f - (float) black * scale)), 0, 255);
    }

    @Test
    @DisplayName("lossless files keep every CMYK sample type and alpha kind exactly, dropping other extras")
    void roundTrips() {
        var random = new SplittableRandom(3);
        var pictures = List.of(random(random, 8, 23, 17, PixelLayout.CMYK), random(random, 8, 13, 9, PixelLayout.CMYKA),
                random(random, 16, 19, 11, PixelLayout.CMYKA), random(random, 16, 7, 21, PREMULTIPLIED),
                random(random, 8, 9, 9, DEPTH));
        for (var picture : pictures) {
            var file = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless());
            var layout = picture.layout();
            var expectedLayout = layout.hasAlpha() ? PixelLayout.CMYK.withChannel(layout.channel(layout.alphaIndex()))
                    : PixelLayout.CMYK;
            var stored = Jp2Decoder.decodeChannels(MediaSource.of(file));
            assertEquals(Jp2ColorSpace.CMYK, stored.colorSpace());
            assertEquals(expectedLayout.count(), stored.channels().size());
            if (layout.hasAlpha()) {
                assertEquals(layout.isAlphaPremultiplied() ? Jp2ChannelType.PREMULTIPLIED_OPACITY
                        : Jp2ChannelType.OPACITY, stored.channels().get(4).type());
            }
            var wide = picture.bitDepth() > 8;
            var kept = assertInstanceOf(ChannelPicture.class,
                    Jp2Decoder.decode(MediaSource.of(file), wide ? KEEP_WIDE : KEEP).picture());
            assertEquals(picture.bitDepth(), kept.bitDepth());
            assertEquals(expectedLayout, kept.layout());
            var sources = new int[]{0, 1, 2, 3, layout.alphaIndex()};
            for (var y = 0; y < picture.height(); y++) {
                for (var x = 0; x < picture.width(); x++) {
                    for (var c = 0; c < expectedLayout.count(); c++) {
                        assertEquals(sample(picture, x, y, sources[c]), sample(kept, x, y, c),
                                layout + " pixel " + x + "," + y + " channel " + c);
                    }
                }
            }
            // the default output converts as OpenJPEG does, truncating where the channel conversion rounds
            var plain = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(file)).picture();
            var narrow = ImageOps.toChannel8(kept);
            var converted = ImageOps.toRgba(kept);
            for (var y = 0; y < picture.height(); y++) {
                for (var x = 0; x < picture.width(); x++) {
                    var at = (y * picture.width() + x) * 4;
                    for (var c = 0; c < 3; c++) {
                        var expected = layout.isAlphaPremultiplied() ? -1
                                : openJpegRgb(sample(kept, x, y, c), sample(kept, x, y, 3), wide ? 65535 : 255);
                        if (expected >= 0 && !wide) {
                            assertEquals(expected, plain.pixels()[at + c] & 0xFF);
                        }
                        assertEquals(plain.pixels()[at + c] & 0xFF, converted.pixels()[at + c] & 0xFF,
                                layout.isAlphaPremultiplied() || wide ? 2 : 1, "pixel " + x + "," + y);
                    }
                    if (layout.hasAlpha()) {
                        assertEquals(sample(narrow, x, y, 4), plain.pixels()[at + 3] & 0xFF);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("floating-point CMYK pictures are coded at 16 bits, as the 16-bit conversion holds them")
    void floats() {
        var picture = random(new SplittableRandom(5), 32, 11, 7, PixelLayout.CMYKA);
        var file = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless());
        var kept = (Channel16Picture) Jp2Decoder.decode(MediaSource.of(file), KEEP_WIDE).picture();
        var expected = ImageOps.toChannel16(picture);
        assertEquals(PixelLayout.CMYKA, kept.layout());
        for (var y = 0; y < 7; y++) {
            for (var x = 0; x < 11; x++) {
                for (var c = 0; c < 5; c++) {
                    assertEquals(sample(expected, x, y, c), sample(kept, x, y, c));
                }
            }
        }
        assertInstanceOf(Channel8Picture.class, Jp2Decoder.decode(MediaSource.of(file), KEEP).picture());
    }

    @Test
    @DisplayName("independently built images of other precisions and subsampling scale and replicate their samples")
    void precisions() {
        var random = new SplittableRandom(7);
        var width = 13;
        var height = 9;
        var channels = new ArrayList<Jp2Channel>();
        var planes = new int[5][];
        for (var c = 0; c < 5; c++) {
            var dx = c == 1 ? 2 : 1;
            var dy = c == 2 ? 2 : 1;
            var w = Math.ceilDiv(width, dx);
            var h = Math.ceilDiv(height, dy);
            planes[c] = new int[w * h];
            for (var i = 0; i < planes[c].length; i++) {
                planes[c][i] = random.nextInt(4096);
            }
            channels.add(new Jp2Channel(0, 0, w, h, dx, dy, 12, false, c == 4 ? Jp2ChannelType.OPACITY
                    : Jp2ChannelType.COLOR, c == 4 ? 0 : c + 1, planes[c]));
        }
        var image = new Jp2Image(0, 0, width, height, width, height, 0, channels, Jp2ColorSpace.CMYK, Optional.empty(),
                Optional.empty(), Optional.empty());
        var file = Jp2Encoder.encode(image, Jp2EncoderOptions.lossless());
        var narrow = (Channel8Picture) Jp2Decoder.decode(MediaSource.of(file), KEEP).picture();
        var wide = (Channel16Picture) Jp2Decoder.decode(MediaSource.of(file), KEEP_WIDE).picture();
        assertEquals(PixelLayout.CMYKA, narrow.layout());
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < 5; c++) {
                    var sx = c == 1 ? x / 2 : x;
                    var sy = c == 2 ? y / 2 : y;
                    var stored = planes[c][sy * Math.ceilDiv(width, c == 1 ? 2 : 1) + sx];
                    assertEquals(Math.round(stored * 255.0 / 4095), sample(narrow, x, y, c), "channel " + c);
                    assertEquals(Math.round(stored * 65535.0 / 4095), sample(wide, x, y, c), "channel " + c);
                }
            }
        }
    }

    @Test
    @DisplayName("channel definitions declare premultiplied opacity as premultiplied, for channels too")
    void premultipliedDefinition() {
        var random = new SplittableRandom(8);
        var channels = new ArrayList<Jp2Channel>();
        for (var c = 0; c < 2; c++) {
            var samples = new int[6 * 5];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = random.nextInt(256);
            }
            channels.add(new Jp2Channel(0, 0, 6, 5, 1, 1, 8, false, c == 0 ? Jp2ChannelType.COLOR
                    : Jp2ChannelType.PREMULTIPLIED_OPACITY, c == 0 ? 1 : 0, samples));
        }
        var image = new Jp2Image(0, 0, 6, 5, 6, 5, 0, channels, Jp2ColorSpace.GRAYSCALE, Optional.empty(),
                Optional.empty(), Optional.empty());
        var decoded = Jp2Decoder.decodeChannels(MediaSource.of(Jp2Encoder.encode(image, Jp2EncoderOptions.lossless())));
        assertEquals(Jp2ChannelType.PREMULTIPLIED_OPACITY, decoded.channels().get(1).type());
        assertArrayEquals(channels.get(1).samples(), decoded.channels().get(1).samples());
    }

    @Test
    @DisplayName("an embedded CMYK profile is reported and converts the inks to sRGB")
    void profile() {
        var icc = swopProfile();
        var profile = ColorProfile.parse(icc);
        var picture = random(new SplittableRandom(9), 8, 17, 12, PixelLayout.CMYK);
        var file = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless().toBuilder().colorProfile(profile).build());
        var image = Jp2Decoder.decode(MediaSource.of(file), KEEP);
        // without channel definitions, the CMYK profile makes all four channels colors
        var stored = Jp2Decoder.decodeChannels(MediaSource.of(file));
        assertEquals(Jp2ColorSpace.ICC, stored.colorSpace());
        for (var channel : stored.channels()) {
            assertEquals(Jp2ChannelType.COLOR, channel.type());
        }
        assertFalse(Jp2Decoder.probe(MediaSource.of(file)).hasAlpha());
        var kept = assertInstanceOf(Channel8Picture.class, image.picture());
        var plain = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(file)).picture();
        var naive = ImageOps.toRgba(kept);
        for (var i = 0; i < plain.pixels().length; i++) {
            assertEquals(plain.pixels()[i] & 0xFF, naive.pixels()[i] & 0xFF, 1, "sample " + i);
        }
        assertEquals(ProfileColorSpace.CMYK, image.colorProfile().orElseThrow().colorSpace());
        assertEquals(ProfileColorSpace.CMYK, ColorProfile.parse(image.iccProfile().orElseThrow()).colorSpace());
        var srgb = ImageOps.toSrgb(image);
        assertEquals(ColorProfile.srgb(), srgb.colorProfile().orElseThrow());
        var expected = ImageOps.toRgba(ImageOps.convertColor(image.picture(), profile, ColorProfile.srgb()));
        assertArrayEquals(expected.pixels(), ((RgbaPicture) srgb.picture()).pixels());
    }

    @Test
    @DisplayName("other images, and every image when sRGB is asked for, decode as without the option")
    void unchanged() {
        var random = new SplittableRandom(11);
        var rgba = RgbaPicture.allocate(15, 10, true);
        random.nextBytes(rgba.pixels());
        var rgbFile = Jp2Encoder.encode(rgba, Jp2EncoderOptions.lossless());
        assertArrayEquals(((RgbaPicture) Jp2Decoder.decode(MediaSource.of(rgbFile)).picture()).pixels(),
                ((RgbaPicture) Jp2Decoder.decode(MediaSource.of(rgbFile), KEEP).picture()).pixels());
        var cmykFile = Jp2Encoder.encode(random(random, 16, 9, 8, PixelLayout.CMYK), Jp2EncoderOptions.lossless());
        var both = Jp2Decoder.decode(MediaSource.of(cmykFile), KEEP_WIDE.toBuilder().convertToSrgb(true).build());
        // the naive CMYK conversion of the default output is 8-bit whatever the inks' depth
        assertArrayEquals(((RgbaPicture) Jp2Decoder.decode(MediaSource.of(cmykFile),
                        ImageDecoderOptions.builder().highBitDepth(true).build()).picture()).pixels(),
                ((RgbaPicture) both.picture()).pixels());
        var codestream = Jp2Encoder.encode(random(random, 8, 9, 8, PixelLayout.CMYK), Jp2EncoderOptions.lossless()
                .toBuilder().format(Jp2Format.CODESTREAM).build());
        // a raw codestream declares no color space, so its four channels are not taken as inks
        assertFalse(Jp2Decoder.decode(MediaSource.of(codestream), KEEP).picture() instanceof ChannelPicture);
    }

    @Test
    @DisplayName("mutated and truncated CMYK files decode to valid channel pictures or raise MediaException")
    void robustness() {
        var random = new SplittableRandom(13);
        var file = Jp2Encoder.encode(random(random, 16, 21, 14, PREMULTIPLIED), Jp2EncoderOptions.lossless());
        var options = KEEP_WIDE.toBuilder().limits(MediaLimits.builder().maxBytes(64 << 20).maxPixels(4 << 20)
                .build()).build();
        var mutations = new java.util.Random(17);
        assertTimeoutPreemptively(Duration.ofSeconds(180), () -> {
            for (var round = 0; round < 300; round++) {
                var data = file.clone();
                var flips = 1 + mutations.nextInt(8);
                for (var i = 0; i < flips; i++) {
                    data[mutations.nextInt(data.length)] = (byte) mutations.nextInt(256);
                }
                if (mutations.nextInt(5) == 0) {
                    data = Arrays.copyOf(data, mutations.nextInt(data.length));
                }
                try {
                    if (Jp2Decoder.decode(MediaSource.of(data), options).picture() instanceof ChannelPicture channels) {
                        assertEquals(PixelLayout.CMYK, channels.layout().colorOnly());
                        assertTrue(channels.stride() >= channels.width() * channels.channelCount());
                    }
                } catch (MediaException expected) {
                    // hostile input is reported as a media exception
                }
            }
        });
        for (var length = 0; length < file.length; length += 7) {
            try {
                Jp2Decoder.decode(MediaSource.of(Arrays.copyOf(file, length)), options);
            } catch (MediaException expected) {
                // rejected
            }
        }
    }
}
