package com.github.auties00.smalt.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    }
}
