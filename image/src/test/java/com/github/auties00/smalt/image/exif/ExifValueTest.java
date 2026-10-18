package com.github.auties00.smalt.image.exif;

import com.github.auties00.smalt.image.Orientation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the typed factories and the code lookup of {@link ExifValue}, the parse and serialize
 * pair of {@link ExifData}, and the in-place orientation patch of {@link ExifWriter}.
 */
@DisplayName("EXIF values and blocks")
class ExifValueTest {
    @Nested
    @DisplayName("ExifValue factories")
    class Factories {
        @Test
        @DisplayName("ofShort, ofLong, ofRational and ofAscii build values of their TIFF types")
        void types() {
            assertEquals(new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{0, 65_535}), ExifValue.ofShort(0, 65_535));
            assertEquals(new ExifValue.Integers(ExifValue.Type.LONG, new long[]{0xFFFF_FFFFL}),
                    ExifValue.ofLong(0xFFFF_FFFFL));
            assertEquals(new ExifValue.Rationals(ExifValue.Type.RATIONAL, new long[]{72}, new long[]{1}),
                    ExifValue.ofRational(72, 1));
            assertEquals(new ExifValue.Ascii("Smalt"), ExifValue.ofAscii("Smalt"));
        }

        @Test
        @DisplayName("ofShort and ofLong copy their array")
        void copies() {
            var values = new long[]{7};
            var value = ExifValue.ofLong(values);
            values[0] = 8;
            assertEquals(7, value.get(0));
        }

        @Test
        @DisplayName("each factory rejects a value its type cannot store")
        void ranges() {
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofShort(65_536));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofShort(-1));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofLong(1L << 32));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofLong(-1));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofRational(1L << 32, 1));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofRational(1, -1));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofAscii("a\0b"));
            assertThrows(IllegalArgumentException.class, () -> ExifValue.ofAscii("€"));
        }
    }

    @Nested
    @DisplayName("ExifValue.Type.of")
    class TypeLookup {
        @ParameterizedTest
        @EnumSource(ExifValue.Type.class)
        @DisplayName("of(code()) gives the constant back")
        void roundTrip(ExifValue.Type type) {
            assertEquals(type, ExifValue.Type.of(type.code()));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 13, -1})
        @DisplayName("of rejects a code that is not a TIFF 6.0 type")
        void unknown(int code) {
            assertThrows(IllegalArgumentException.class, () -> ExifValue.Type.of(code));
        }
    }

    @Nested
    @DisplayName("ExifData.parse and toBytes, ExifWriter.withOrientation")
    class Blocks {
        static ExifData sample() {
            return new ExifData(Map.of(ExifData.ORIENTATION, ExifValue.ofShort(6), 0x010F, ExifValue.ofAscii("Maker")),
                    Map.of(), Map.of());
        }

        @Test
        @DisplayName("toBytes() writes the block ExifWriter.write writes, and parse reads it back")
        void roundTrip() {
            var data = sample();
            assertArrayEquals(ExifWriter.write(data), data.toBytes());
            assertArrayEquals(ExifWriter.write(data, ByteOrder.LITTLE_ENDIAN), data.toBytes(ByteOrder.LITTLE_ENDIAN));
            assertEquals(ExifReader.parse(data.toBytes()), ExifData.parse(data.toBytes()));
            assertEquals(Orientation.ROTATE_90, ExifData.parse(data.toBytes(ByteOrder.LITTLE_ENDIAN)).orientation());
        }

        @Test
        @DisplayName("ExifWriter.withOrientation patches the block in place")
        void withOrientation() {
            var block = sample().toBytes();
            var patched = ExifWriter.withOrientation(block, Orientation.FLIP_HORIZONTAL);
            assertEquals(block.length, patched.length);
            assertEquals(Orientation.FLIP_HORIZONTAL, ExifReader.orientation(patched));
        }
    }
}
