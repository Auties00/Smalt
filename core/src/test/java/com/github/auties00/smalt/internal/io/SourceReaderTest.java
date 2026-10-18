package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link SourceReader} and the {@link MediaSource} factories: endianness, buffering across
 * refills, seeking, bounded reads, and truncation.
 */
class SourceReaderTest {
    @Test
    @DisplayName("reads big- and little-endian integers of every width")
    void integers() {
        var data = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, (byte) 0xFF, (byte) 0xFE,
                (byte) 0xFD, (byte) 0xFC, (byte) 0xFB, (byte) 0xFA, (byte) 0xF9, (byte) 0xF8};
        var reader = new SourceReader(MediaSource.of(data));
        assertEquals(0x0102, reader.u16be());
        assertEquals(0x0403, reader.u16le());
        assertEquals(0x050607, reader.u24be());
        assertEquals(0xFEFF08, reader.u24le());
        assertEquals(0xFDFCFBFAL, reader.u32be());
        assertEquals(0xF8F9L, reader.u16le());
        assertTrue(reader.atEnd());
        reader.seek(8);
        assertEquals(0xFFFEFDFCFBFAF9F8L, reader.u64be());
        reader.seek(0);
        assertEquals(0x0807060504030201L, reader.u64le());
    }

    @Test
    @DisplayName("stays correct across buffer refills and seeks outside the buffer")
    void refillsAndSeeks() {
        var data = new byte[100_000];
        new Random(7).nextBytes(data);
        var reader = new SourceReader(MediaSource.of(data), 16);
        for (var i = 0; i < 1000; i++) {
            assertEquals(data[i] & 0xFF, reader.u8());
        }
        reader.seek(90_000);
        assertEquals(((data[90_000] & 0xFF) << 8) | (data[90_001] & 0xFF), reader.u16be());
        reader.seek(10);
        var chunk = new byte[5000];
        reader.readFully(chunk, 0, chunk.length);
        for (var i = 0; i < chunk.length; i++) {
            assertEquals(data[10 + i], chunk[i]);
        }
        assertEquals(5010, reader.position());
    }

    @Test
    @DisplayName("fails with Malformed when a value runs past the end")
    void truncation() {
        var reader = new SourceReader(MediaSource.of(new byte[]{1, 2, 3}));
        reader.u16be();
        assertThrows(MediaException.Malformed.class, reader::u16be);
    }

    @Test
    @DisplayName("bounds byte-array reads by the caller's limit and the remaining input")
    void boundedBytes() {
        var reader = new SourceReader(MediaSource.of(new byte[10]));
        assertThrows(MediaException.LimitExceeded.class, () -> reader.bytes(9, 8));
        assertThrows(MediaException.Malformed.class, () -> reader.bytes(11, 100));
        assertThrows(MediaException.Malformed.class, () -> reader.bytes(-1, 100));
        assertEquals(10, reader.bytes(10, 10).length);
    }

    @Test
    @DisplayName("reads files through positional reads and closes owned channels")
    void fileSource(@TempDir Path directory) throws IOException {
        var file = directory.resolve("data.bin");
        var data = new byte[70_000];
        new Random(3).nextBytes(data);
        Files.write(file, data);
        try (var source = MediaSource.of(file)) {
            assertEquals(data.length, source.size());
            var slice = new byte[100];
            source.readFully(65_000, slice, 0, 100);
            for (var i = 0; i < 100; i++) {
                assertEquals(data[65_000 + i], slice[i]);
            }
            assertEquals(-1, source.read(data.length, slice, 0, 1));
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var source = MediaSource.of(channel);
            source.close();
            assertTrue(channel.isOpen(), "a borrowed channel must stay open");
        }
    }

    @Test
    @DisplayName("exposes an array range with offset zero at the range start")
    void arrayRange() {
        var data = new byte[]{9, 9, 1, 2, 3, 9};
        var source = MediaSource.of(data, 2, 3);
        assertEquals(3, source.size());
        var target = new byte[3];
        source.readFully(0, target, 0, 3);
        assertArrayEquals(new byte[]{1, 2, 3}, target);
        assertThrows(MediaException.Malformed.class, () -> source.readFully(1, new byte[3], 0, 3));
    }
}
