package com.github.auties00.smalt.container.mp4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the conversion of the {@code mjqt} and {@code mjht} boxes to a track's configuration; the layout itself is
 * checked against QuickTime by {@code MjpegTablesBoxTest} in {@code smalt-image}.
 */
@DisplayName("MotionJpegTables")
class MotionJpegTablesTest {
    private static final byte[] TWO = {0, 4, 7, 8};
    private static final byte[] THREE = {0, 5, 1, 2, 3};

    @Test
    @DisplayName("frames each box with its marker code, quantization first")
    void both() {
        assertArrayEquals(new byte[]{-1, (byte) 0xDB, 0, 4, 7, 8, -1, (byte) 0xC4, 0, 5, 1, 2, 3},
                MotionJpegTables.toConfig(TWO, THREE));
        assertArrayEquals(new byte[]{-1, (byte) 0xC4, 0, 5, 1, 2, 3}, MotionJpegTables.toConfig(null, THREE));
        assertArrayEquals(new byte[]{-1, (byte) 0xDB, 0, 4, 7, 8}, MotionJpegTables.toConfig(TWO, null));
    }

    @Test
    @DisplayName("leaves out the bytes after a segment")
    void padding() {
        assertArrayEquals(new byte[]{-1, (byte) 0xDB, 0, 4, 7, 8},
                MotionJpegTables.toConfig(new byte[]{0, 4, 7, 8, 9, 9}, null));
    }

    @Test
    @DisplayName("holds nothing for a box without a valid length")
    void invalid() {
        assertNull(MotionJpegTables.toConfig(null, null));
        assertNull(MotionJpegTables.toConfig(new byte[0], new byte[1]));
        assertNull(MotionJpegTables.toConfig(new byte[]{0, 0, 1}, new byte[]{0, 1, 1}));
        assertNull(MotionJpegTables.toConfig(new byte[]{0, 9, 1}, null));
    }

    private static byte[] written(byte[] config) {
        var out = new ByteArrayOutputStream();
        var writer = new BoxWriter(64);
        MotionJpegTables.write(writer, config);
        out.writeBytes(writer.toByteArray());
        return out.toByteArray();
    }

    @Test
    @DisplayName("writes the boxes back")
    void write() {
        assertArrayEquals(new byte[]{0, 0, 0, 12, 'm', 'j', 'q', 't', 0, 4, 7, 8, 0, 0, 0, 13, 'm', 'j', 'h', 't', 0, 5, 1, 2, 3},
                written(MotionJpegTables.toConfig(TWO, THREE)));
    }

    @Test
    @DisplayName("writes nothing for a configuration of any other form")
    void foreign() {
        assertArrayEquals(new byte[0], written(null));
        assertArrayEquals(new byte[0], written(new byte[0]));
        assertArrayEquals(new byte[0], written(new byte[]{1, 2, 3, 4}));
        assertArrayEquals(new byte[0], written(new byte[]{-1, (byte) 0xDB, 0, 9, 1}));
        assertArrayEquals(new byte[0], written(new byte[]{-1, (byte) 0xDB, 0, 2, 7}));
    }
}
