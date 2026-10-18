package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("PngChunkWriter")
class PngChunkWriterTest {
    @Test
    @DisplayName("frames chunks with their length, type, sequence number and CRC after the signature")
    void framing() {
        var writer = new PngChunkWriter(16);
        writer.chunk(PngChunks.IEND, new byte[0], 0, 0);
        writer.chunk(PngChunks.FDAT, 7, new byte[]{1, 2, 3}, 1, 2);
        var png = writer.toByteArray();
        assertArrayEquals(PngChunks.SIGNATURE, Arrays.copyOf(png, 8));
        assertEquals(0, ByteArrays.i32be(png, 8));
        assertEquals(PngChunks.IEND, ByteArrays.i32be(png, 12));
        assertEquals(0xAE426082, ByteArrays.i32be(png, 16));
        assertEquals(6, ByteArrays.i32be(png, 20));
        assertEquals(PngChunks.FDAT, ByteArrays.i32be(png, 24));
        assertEquals(7, ByteArrays.i32be(png, 28));
        assertArrayEquals(new byte[]{2, 3}, Arrays.copyOfRange(png, 32, 34));
        var crc = new CRC32();
        crc.update(png, 24, 10);
        assertEquals((int) crc.getValue(), ByteArrays.i32be(png, 34));
        assertEquals(38, png.length);
    }

    @Test
    @DisplayName("splits image data into chunks of a maximum size, numbering fdAT chunks")
    void data() {
        var writer = new PngChunkWriter(16);
        assertEquals(-1, writer.data(PngChunks.IDAT, new byte[5], -1, 2));
        assertEquals(4, writer.data(PngChunks.FDAT, new byte[3], 1, 1));
        var png = writer.toByteArray();
        assertEquals(8 + 3 * 12 + 5 + 3 * 16 + 3, png.length);
    }

    @Test
    @DisplayName("refuses a chunk that would make the datastream too large, writing nothing")
    void limit() {
        var writer = new PngChunkWriter(16, 40);
        writer.chunk(PngChunks.IHDR, new byte[13], 0, 13);
        assertThrows(MediaException.LimitExceeded.class, () -> writer.chunk(PngChunks.IEND, new byte[0], 0, 0));
        assertEquals(33, writer.toByteArray().length);
    }
}
