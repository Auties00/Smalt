package com.github.auties00.smalt.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Image value contracts")
class ImageValueContractsTest {
    @Nested
    @DisplayName("YuvPicture.allocate")
    class Allocate {
        @Test
        @DisplayName("rejects a null color description")
        void nullColor() {
            assertThrows(NullPointerException.class,
                    () -> YuvPicture.allocate(2, 2, ChromaFormat.YUV420, false, null));
        }
    }

    @Nested
    @DisplayName("DecodedImage")
    class Decoded {
        private final Picture picture = RgbaPicture.allocate(2, 2, false);

        private DecodedImage image(byte[] icc, byte[] exif) {
            return new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, Optional.ofNullable(icc),
                    Optional.ofNullable(exif));
        }

        @Test
        @DisplayName("compares the embedded byte arrays by content")
        void arrayContent() {
            var first = image(new byte[]{1, 2, 3}, new byte[]{'M', 'M', 0, 42});
            var second = image(new byte[]{1, 2, 3}, new byte[]{'M', 'M', 0, 42});
            assertEquals(first, second);
            assertEquals(first.hashCode(), second.hashCode());
            assertNotEquals(first, image(new byte[]{1, 2, 4}, new byte[]{'M', 'M', 0, 42}));
            assertNotEquals(first, image(new byte[]{1, 2, 3}, null));
        }

        @Test
        @DisplayName("compares the picture by identity")
        void pictureIdentity() {
            var other = new DecodedImage(RgbaPicture.allocate(2, 2, false), ImageFormat.PNG, Orientation.NORMAL, 2,
                    2, Optional.empty(), Optional.empty());
            assertNotEquals(image(null, null), other);
            assertEquals(image(null, null), image(null, null));
        }

        @Test
        @DisplayName("keeps the XMP packet, compares it by content, and leaves it empty in the older constructors")
        void xmp() {
            var packet = "<x:xmpmeta xmlns:x='adobe:ns:meta/'/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var withXmp = new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, Optional.empty(),
                    Optional.empty(), Optional.of(packet), Optional.empty());
            assertArrayEquals(packet, withXmp.xmp().orElseThrow());
            assertTrue(image(null, null).xmp().isEmpty());
            assertTrue(new DecodedImage(picture, ImageFormat.PNG, Orientation.NORMAL, 2, 2, Optional.empty(),
                    Optional.empty(), Optional.empty()).xmp().isEmpty());
            var copy = image(null, null).withXmp(Optional.of(packet.clone()));
            assertEquals(withXmp, copy);
            assertEquals(withXmp.hashCode(), copy.hashCode());
            assertSame(picture, copy.picture());
            assertNotEquals(withXmp, image(null, null));
            assertNotEquals(withXmp, image(null, null).withXmp(Optional.of(new byte[]{1})));
            assertEquals(image(null, null), withXmp.withXmp(Optional.empty()));
            assertThrows(NullPointerException.class, () -> image(null, null).withXmp(null));
        }
    }

    @Nested
    @DisplayName("AnimationFrame")
    class Frames {
        @Test
        @DisplayName("holds 8-bit or 16-bit packed RGBA and rejects planar pictures")
        void pictureTypes() {
            var deep = Rgba16Picture.allocate(3, 2, true);
            assertSame(deep, new AnimationFrame(deep, 0, 10).picture());
            assertThrows(IllegalArgumentException.class,
                    () -> new AnimationFrame(YuvPicture.allocate(2, 2, ChromaFormat.YUV420, false, ColorInfo.JPEG), 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new AnimationFrame(deep, -1, 0));
        }

        @Test
        @DisplayName("are written by the default AnimationWriter.write reduced to 8 bits")
        void defaultWrite() {
            var deep = Rgba16Picture.allocate(3, 2, true);
            for (var i = 0; i < deep.pixels().length; i++) {
                deep.pixels()[i] = (short) (i * 4099 + 17);
            }
            var written = new java.util.ArrayList<RgbaPicture>();
            var durations = new java.util.ArrayList<Long>();
            var writer = new AnimationWriter() {
                @Override
                public int width() {
                    return 3;
                }

                @Override
                public int height() {
                    return 2;
                }

                @Override
                public void write(RgbaPicture picture, long durationMicros) {
                    written.add(picture);
                    durations.add(durationMicros);
                }

                @Override
                public void finish() {
                }

                @Override
                public void close() {
                }
            };
            var narrow = RgbaPicture.allocate(3, 2, true);
            writer.write(new AnimationFrame(narrow, 0, 5));
            writer.write(new AnimationFrame(deep, 5, 7));
            assertSame(narrow, written.get(0));
            assertArrayEquals(com.github.auties00.smalt.image.ops.ImageOps.toEightBit(deep).pixels(),
                    written.get(1).pixels());
            assertEquals(java.util.List.of(5L, 7L), durations);
        }
    }
}
