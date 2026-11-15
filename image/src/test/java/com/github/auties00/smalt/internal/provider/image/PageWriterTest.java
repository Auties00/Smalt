package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the page writers of the TIFF and icon providers through {@link Images#openPageWriter}: N
 * pages of different sizes and depths written and read back in order by {@link Images#openPages},
 * the metadata a decoded TIFF page carries, the scaling of large icon entries, and the writer state
 * rules.
 */
class PageWriterTest {
    static RgbaPicture picture(int width, int height, boolean alpha, long seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i % 4 == 3 && !alpha ? 255 : random.nextInt(256));
        }
        return picture;
    }

    static List<Picture> pages(int count) {
        var result = new ArrayList<Picture>();
        for (var i = 0; i < count; i++) {
            result.add(switch (i % 3) {
                case 0 -> picture(5 + i, 3 + 2 * i, i % 2 == 0, i);
                case 1 -> {
                    var wide = Rgba16Picture.allocate(7, 4 + i, false);
                    for (var p = 0; p < wide.pixels().length; p++) {
                        wide.pixels()[p] = (short) (p % 4 == 3 ? 0xFFFF : p * 977);
                    }
                    yield wide;
                }
                default -> {
                    var cmyk = Channel16Picture.allocate(6, 6, PixelLayout.CMYK);
                    for (var p = 0; p < cmyk.pixels().length; p++) {
                        cmyk.pixels()[p] = (short) (p * 1231);
                    }
                    yield cmyk;
                }
            });
        }
        return result;
    }

    @Nested
    @DisplayName("TIFF")
    class Tiff {
        @ParameterizedTest(name = "{0} pages")
        @ValueSource(ints = {1, 2, 7})
        @DisplayName("writes one directory per page, read back in order with the same samples")
        void roundTrip(int count) {
            assertTrue(Images.isPageEncodable(ImageFormat.TIFF));
            var sink = MediaSink.memory();
            var pages = pages(count);
            try (var writer = Images.openPageWriter(ImageFormat.TIFF, sink)) {
                for (var page : pages) {
                    writer.write(page);
                }
                assertEquals(count, writer.pageCount());
                writer.finish();
                assertThrows(IllegalStateException.class, () -> writer.write(pages.getFirst()));
                assertThrows(IllegalStateException.class, writer::finish);
            }
            var options = ImageDecoderOptions.builder().keepChannels(true).highBitDepth(true).build();
            try (var reader = Images.openPages(MediaSource.of(sink.toByteArray()), options)) {
                assertEquals(count, reader.pageCount());
                for (var i = 0; i < count; i++) {
                    var expected = pages.get(i);
                    var actual = reader.read(i).picture();
                    assertEquals(expected.width(), actual.width());
                    assertEquals(expected.height(), actual.height());
                    switch (expected) {
                        case RgbaPicture rgba -> RasterTesting.assertSameRgba(RasterTesting.tight(rgba),
                                RasterTesting.tight(ImageOps.toRgba(actual)), rgba.width(), "page " + i);
                        case Rgba16Picture wide -> assertArrayEquals(wide.pixels(), ((Rgba16Picture) actual).pixels(),
                                "page " + i);
                        case ChannelPicture channels -> assertArrayEquals(((Channel16Picture) channels).pixels(),
                                ((Channel16Picture) actual).pixels(), "page " + i);
                        default -> fail();
                    }
                }
            }
        }

        @Test
        @DisplayName("keeps the orientation, color profile, EXIF metadata and XMP packet of decoded pages")
        void metadata() {
            var exif = ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x010F, ExifValue.ofAscii("Maker"))
                    .with(ExifData.Directory.EXIF, 0x8827, ExifValue.ofShort(100));
            var xmp = XmpPacket.empty().with(XmpPacket.DC, "title", "Second");
            var profile = ColorProfile.displayP3();
            var source = picture(9, 4, false, 3);
            var decoded = new DecodedImage(source, ImageFormat.PNG, Orientation.ROTATE_90, 9, 4,
                    Optional.of(profile.toByteArray()), Optional.of(exif.toBytes()), Optional.of(xmp.toBytes()),
                    Optional.of(profile));
            var sink = MediaSink.memory();
            try (var writer = Images.openPageWriter(ImageFormat.TIFF, sink)) {
                writer.write(source);
                writer.write(decoded);
                writer.write(new DecodedImage(source, ImageFormat.PNG, Orientation.NORMAL, 9, 4, Optional.empty(),
                        Optional.of(new byte[]{1, 2, 3}), Optional.of("<not xml".getBytes()), Optional.empty()));
                // an HEIF page whose transform overrides the orientation its EXIF entry records
                writer.write(new DecodedImage(source, ImageFormat.HEIC, Orientation.NORMAL, 9, 4, Optional.empty(),
                        Optional.of(exif.withOrientation(Orientation.ROTATE_90).toBytes()), Optional.empty(),
                        Optional.empty()));
                writer.finish();
            }
            try (var reader = Images.openPages(MediaSource.of(sink.toByteArray()))) {
                var first = reader.read(0);
                assertEquals(Orientation.NORMAL, first.orientation());
                assertTrue(first.exif().isEmpty());
                var second = reader.read(1);
                assertEquals(Orientation.ROTATE_90, second.orientation());
                assertArrayEquals(profile.toByteArray(), second.iccProfile().orElseThrow());
                assertEquals(exif.exif(), ExifReader.parse(second.exif().orElseThrow()).exif());
                assertEquals(xmp, XmpPacket.parse(second.xmp().orElseThrow()));
                var third = reader.read(2);
                assertTrue(third.exif().isEmpty());
                assertTrue(third.xmp().isEmpty());
                var fourth = reader.read(3);
                assertEquals(Orientation.NORMAL, fourth.orientation());
                assertEquals(exif.exif(), ExifReader.parse(fourth.exif().orElseThrow()).exif());
            }
        }
    }

    @Nested
    @DisplayName("ICO")
    class Ico {
        @Test
        @DisplayName("writes one entry per page and scales pages larger than 256 pixels into 256-pixel squares")
        void entries() {
            assertTrue(Images.isPageEncodable(ImageFormat.ICO));
            var pages = List.<Picture>of(picture(16, 16, true, 1), picture(32, 24, false, 2), picture(300, 150, true, 3),
                    picture(256, 256, true, 4));
            var sink = MediaSink.memory();
            try (var writer = Images.openPageWriter(ImageFormat.ICO, sink)) {
                for (var page : pages) {
                    writer.write(page);
                }
                assertEquals(4, writer.pageCount());
                writer.finish();
                assertThrows(IllegalStateException.class, () -> writer.write(pages.getFirst()));
            }
            try (var reader = Images.openPages(MediaSource.of(sink.toByteArray()))) {
                assertEquals(4, reader.pageCount());
                RasterTesting.assertSameRgba(RasterTesting.tight((RgbaPicture) pages.get(0)),
                        RasterTesting.tight(ImageOps.toRgba(reader.read(0).picture())), 16, "entry 0");
                var opaque = RasterTesting.tight((RgbaPicture) pages.get(1));
                RasterTesting.assertSameRgba(opaque, RasterTesting.tight(ImageOps.toRgba(reader.read(1).picture())), 32,
                        "entry 1");
                var scaled = reader.read(2).picture();
                assertEquals(256, scaled.width());
                assertEquals(256, scaled.height());
                var pixels = RasterTesting.tight(ImageOps.toRgba(scaled));
                assertEquals(0, pixels[3], "the square is padded with transparent pixels");
                assertEquals(256, reader.read(3).picture().width());
            }
        }

        @Test
        @DisplayName("needs a page before finishing and checks lossless depth")
        void state() {
            try (var writer = Images.openPageWriter(ImageFormat.ICO, MediaSink.memory())) {
                assertThrows(IllegalStateException.class, writer::finish);
            }
            var deep = Rgba16Picture.allocate(2, 2, false);
            deep.pixels()[0] = 1;
            try (var writer = Images.openPageWriter(ImageFormat.ICO, ImageEncoderOptions.lossless(), MediaSink.memory())) {
                assertThrows(MediaException.Unsupported.class, () -> writer.write(deep));
            }
            try (var writer = Images.openPageWriter(ImageFormat.TIFF, MediaSink.memory())) {
                assertThrows(IllegalStateException.class, writer::finish);
            }
            assertFalse(Images.isPageEncodable(ImageFormat.PNG));
            assertThrows(MediaException.Unsupported.class, () -> Images.openPageWriter(ImageFormat.PNG, MediaSink.memory()));
        }
    }
}
