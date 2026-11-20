package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.metadata.XmpPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the metadata decisions the image codec providers share: the orientation recorded in EXIF and XMP, the
 * still pictures turned upright, and the conversion to sRGB of pictures whose profile a format cannot record.
 */
class EncodeMetadataTest {
    /**
     * {@return a big-endian EXIF block with only an orientation entry}
     */
    static byte[] exif(int orientation) {
        var block = new byte[26];
        block[0] = 'M';
        block[1] = 'M';
        block[3] = 42;
        block[7] = 8;
        block[9] = 1;
        block[10] = 0x01;
        block[11] = 0x12;
        block[13] = 3;
        block[17] = 1;
        block[19] = (byte) orientation;
        return block;
    }

    static RgbaPicture gradient(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / Math.max(1, width - 1));
                pixels[at + 1] = (byte) (y * 255 / Math.max(1, height - 1));
                pixels[at + 2] = (byte) ((x + y) * 7);
                pixels[at + 3] = (byte) 255;
            }
        }
        return picture;
    }

    @Nested
    @DisplayName("orientation")
    class Orientations {
        @Test
        @DisplayName("takes the explicit orientation over the EXIF one, and normal without either")
        void display() {
            assertEquals(Orientation.NORMAL, EncodeMetadata.displayOrientation(ImageEncoderOptions.defaults()));
            var fromExif = ImageEncoderOptions.builder().exif(exif(6)).build();
            assertEquals(Orientation.ROTATE_90, EncodeMetadata.displayOrientation(fromExif));
            var explicit = fromExif.toBuilder().orientation(Orientation.ROTATE_180).build();
            assertEquals(Orientation.ROTATE_180, EncodeMetadata.displayOrientation(explicit));
        }

        @Test
        @DisplayName("patches the EXIF block, creates one for a turned picture and adds none for a normal one")
        void exifBlocks() {
            assertTrue(EncodeMetadata.exif(ImageEncoderOptions.defaults()).isEmpty());
            var normal = ImageEncoderOptions.builder().orientation(Orientation.NORMAL).build();
            assertTrue(EncodeMetadata.exif(normal).isEmpty());
            var turned = ImageEncoderOptions.builder().orientation(Orientation.ROTATE_270).build();
            assertEquals(Orientation.ROTATE_270, TiffExif.orientation(EncodeMetadata.exif(turned).orElseThrow(), 0));
            var kept = ImageEncoderOptions.builder().exif(exif(6)).build();
            assertArrayEquals(exif(6), EncodeMetadata.exif(kept).orElseThrow());
            var reset = kept.toBuilder().orientation(Orientation.NORMAL).build();
            assertArrayEquals(exif(1), EncodeMetadata.exif(reset).orElseThrow());
            assertArrayEquals(exif(1), EncodeMetadata.exif(kept, Orientation.NORMAL).orElseThrow());
        }

        @Test
        @DisplayName("sets a tiff:Orientation the XMP packet holds and leaves other packets byte for byte")
        void xmp() {
            var oriented = XmpPacket.empty().with(XmpPacket.TIFF, "Orientation", "6")
                    .with(XmpPacket.DC, "format", "image/jpeg").toBytes();
            var options = ImageEncoderOptions.builder().xmp(oriented).orientation(Orientation.NORMAL).build();
            var written = XmpPacket.parse(EncodeMetadata.xmp(options).orElseThrow());
            assertEquals("1", written.text(XmpPacket.TIFF, "Orientation").orElseThrow());
            assertEquals("image/jpeg", written.text(XmpPacket.DC, "format").orElseThrow());
            var plain = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                    + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:format=\"x\"/>"
                    + "</rdf:RDF></x:xmpmeta>";
            var bytes = plain.getBytes(StandardCharsets.UTF_8);
            var untouched = ImageEncoderOptions.builder().xmp(bytes).orientation(Orientation.ROTATE_90).build();
            assertArrayEquals(bytes, EncodeMetadata.xmp(untouched).orElseThrow());
            var garbage = "not xml".getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(garbage, EncodeMetadata.withOrientation(garbage, Orientation.ROTATE_90));
            assertArrayEquals(oriented, EncodeMetadata.xmp(ImageEncoderOptions.builder().xmp(oriented).build())
                    .orElseThrow(), "no explicit orientation leaves the packet alone");
        }

        @Test
        @DisplayName("turns a still picture upright and rejects a turned animation")
        void upright() {
            var picture = gradient(5, 3);
            var options = ImageEncoderOptions.builder().exif(exif(6)).build();
            var upright = EncodeMetadata.upright(picture, options);
            assertEquals(3, upright.width());
            assertEquals(5, upright.height());
            assertArrayEquals(((RgbaPicture) ImageOps.orient(picture, Orientation.ROTATE_90)).pixels(),
                    ((RgbaPicture) upright).pixels());
            assertSame(picture, EncodeMetadata.upright(picture, ImageEncoderOptions.defaults()));
            assertThrows(MediaException.Unsupported.class, () -> EncodeMetadata.requireUpright(options, "gif"));
            assertDoesNotThrow(() -> EncodeMetadata.requireUpright(options.toBuilder()
                    .orientation(Orientation.NORMAL).build(), "gif"));
        }
    }

    @Nested
    @DisplayName("color")
    class Color {
        @Test
        @DisplayName("records a profile the format accepts and converts the picture otherwise")
        void decision() {
            var picture = gradient(16, 8);
            var p3 = ImageEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).build();
            var recorded = EncodeMetadata.color(picture, p3, EncodeMetadata::rgbOnly);
            assertSame(picture, recorded.picture());
            assertEquals(ColorProfile.displayP3(), recorded.profile());
            var converted = EncodeMetadata.color(picture, p3, (profile, coded) -> false);
            assertNull(converted.profile());
            var expected = (RgbaPicture) ImageOps.convertColor(picture, ColorProfile.displayP3(), ColorProfile.srgb());
            assertArrayEquals(expected.pixels(), ((RgbaPicture) converted.picture()).pixels());
            var none = EncodeMetadata.color(picture, ImageEncoderOptions.defaults(), (profile, coded) -> false);
            assertSame(picture, none.picture());
            assertNull(none.profile());
        }

        @Test
        @DisplayName("rejects a profile that does not describe the picture")
        void mismatch() {
            var gray = ColorProfile.grayGamma22();
            var cmyk = Channel8Picture.allocate(4, 4, PixelLayout.CMYK);
            assertThrows(IllegalArgumentException.class, () -> EncodeMetadata.checkProfile(cmyk, gray));
            assertThrows(IllegalArgumentException.class, () -> EncodeMetadata.checkProfile(cmyk, ColorProfile.srgb()));
            assertDoesNotThrow(() -> EncodeMetadata.checkProfile(gradient(2, 2), gray));
            assertDoesNotThrow(() -> EncodeMetadata.checkProfile(gradient(2, 2), ColorProfile.adobeRgb()));
            assertTrue(EncodeMetadata.isCmyk(cmyk));
            assertEquals(ColorModel.CMYK, EncodeMetadata.codedModel(cmyk));
            assertEquals(ColorModel.RGB, EncodeMetadata.codedModel(gradient(1, 1)));
            assertTrue(EncodeMetadata.describes(ColorProfile.adobeRgb(), ColorModel.RGB));
            assertFalse(EncodeMetadata.describes(gray, ColorModel.RGB));
            assertTrue(EncodeMetadata.describes(gray, ColorModel.GRAY));
        }

        @Test
        @DisplayName("records an RGB profile for an animation or converts every frame through it")
        void animation() {
            var options = ImageEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb()).build();
            var recorded = EncodeMetadata.animationColor(options, true);
            assertEquals(ColorProfile.adobeRgb(), recorded.profile());
            assertNull(recorded.transform());
            var converted = EncodeMetadata.animationColor(options, false);
            assertNull(converted.profile());
            var frames = new ArrayList<RgbaPicture>();
            var sink = new AnimationWriter() {
                @Override
                public int width() {
                    return 6;
                }

                @Override
                public int height() {
                    return 4;
                }

                @Override
                public void write(RgbaPicture picture, long durationMicros) {
                    frames.add(picture);
                }

                @Override
                public void finish() {
                }

                @Override
                public void close() {
                }
            };
            var writer = converted.wrap(sink);
            assertNotSame(sink, writer);
            var frame = gradient(6, 4);
            writer.write(frame, 1000);
            var expected = (RgbaPicture) ImageOps.convertColor(frame,
                    ColorTransform.create(ColorProfile.adobeRgb(), ColorProfile.srgb()));
            assertArrayEquals(expected.pixels(), frames.getFirst().pixels());
            assertSame(sink, recorded.wrap(sink));
            assertSame(sink, EncodeMetadata.animationColor(ImageEncoderOptions.defaults(), false).wrap(sink));
        }

        @Test
        @DisplayName("converts every frame as convertColor does, into one reused picture, leaving the input as is")
        void frameConverter() {
            for (var profile : new ColorProfile[]{ColorProfile.displayP3(), ColorProfile.grayGamma22()}) {
                var transform = EncodeMetadata.frameTransform(profile);
                var converter = new EncodeMetadata.FrameConverter(transform);
                assertTrue(converter.converts());
                RgbaPicture previous = null;
                for (var frame = 0; frame < 3; frame++) {
                    // a padded stride and alpha: the converter reads rows by stride and keeps alpha
                    var source = gradient(23, 1000 + frame);
                    var padded = RgbaPicture.of(23, source.height(), 23 * 4 + 12, new byte[(23 * 4 + 12) * source.height()],
                            true);
                    for (var y = 0; y < source.height(); y++) {
                        System.arraycopy(source.pixels(), y * source.stride(), padded.pixels(), y * padded.stride(), 23 * 4);
                        padded.pixels()[y * padded.stride() + 3] = (byte) (y * 3 + frame);
                    }
                    var before = padded.pixels().clone();
                    var converted = converter.convert(padded);
                    var expected = (RgbaPicture) ImageOps.convertColor(padded, transform);
                    assertEquals(padded.hasAlpha(), converted.hasAlpha());
                    for (var y = 0; y < converted.height(); y++) {
                        assertArrayEquals(Arrays.copyOfRange(expected.pixels(), y * expected.stride(),
                                        y * expected.stride() + 23 * 4),
                                Arrays.copyOfRange(converted.pixels(), y * converted.stride(),
                                        y * converted.stride() + 23 * 4), profile + " row " + y);
                    }
                    assertArrayEquals(before, padded.pixels(), "the input frame is not modified");
                    var row = 500 * padded.stride();
                    assertFalse(Arrays.equals(Arrays.copyOfRange(before, row, row + 23 * 4),
                            Arrays.copyOfRange(converted.pixels(), 500 * converted.stride(), 500 * converted.stride() + 23 * 4)),
                            "the profile changes the colors");
                    if (previous != null && previous.height() == converted.height()) {
                        assertSame(previous, converted);
                    }
                    previous = converted;
                }
                var same = gradient(23, 1002);
                var reused = converter.convert(RgbaPicture.of(23, 1002, 23 * 4, same.pixels(), true));
                assertSame(previous, reused, "a frame of the same size reuses the picture");
            }
            var identity = new EncodeMetadata.FrameConverter(null);
            var frame = gradient(4, 4);
            assertFalse(identity.converts());
            assertSame(frame, identity.convert(frame));
        }
    }
}
