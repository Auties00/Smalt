package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.MemoryChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MediaSink")
class MediaSinkTest {
    private static final byte[] HELLO = {'h', 'e', 'l', 'l', 'o'};

    @Nested
    @DisplayName("in memory")
    class Memory {
        @Test
        @DisplayName("collects writes, rewrites earlier bytes and zero-fills gaps")
        void writes() {
            var sink = MediaSink.memory();
            assertTrue(sink.isSeekable());
            sink.write(HELLO);
            sink.position(1);
            sink.write(new byte[]{'E'});
            assertEquals(2, sink.position());
            sink.position(7);
            sink.write(ByteBuffer.wrap(new byte[]{'!'}));
            assertEquals(8, sink.size());
            assertArrayEquals(new byte[]{'h', 'E', 'l', 'l', 'o', 0, 0, '!'}, sink.toByteArray());
            sink.close();
            assertEquals(8, sink.toByteArray().length);
        }

        @Test
        @DisplayName("grows past its initial capacity and consumes the buffers it writes")
        void grows() {
            var sink = MediaSink.memory();
            var data = new byte[100_000];
            for (var i = 0; i < data.length; i++) {
                data[i] = (byte) i;
            }
            var buffer = ByteBuffer.allocateDirect(data.length).put(data).flip();
            sink.write(buffer);
            assertFalse(buffer.hasRemaining());
            assertArrayEquals(data, sink.toByteArray());
        }

        @Test
        @DisplayName("rejects negative positions")
        void rejectsNegative() {
            assertThrows(IllegalArgumentException.class, () -> MediaSink.memory().position(-1));
        }
    }

    @Nested
    @DisplayName("over a file")
    class File {
        @Test
        @DisplayName("creates or truncates the file, seeks, and closes its handle")
        void writes(@TempDir Path directory) throws IOException {
            var path = directory.resolve("out.bin");
            Files.write(path, new byte[64]);
            try (var sink = MediaSink.of(path)) {
                assertTrue(sink.isSeekable());
                assertEquals(0, sink.position());
                sink.write(HELLO);
                sink.position(0);
                sink.write(new byte[]{'H'});
            }
            assertArrayEquals(new byte[]{'H', 'e', 'l', 'l', 'o'}, Files.readAllBytes(path));
            Files.delete(path);
        }

        @Test
        @DisplayName("reports a directory it cannot open as an I/O failure")
        void failure(@TempDir Path directory) {
            assertThrows(MediaException.Io.class, () -> MediaSink.of(directory));
        }
    }

    @Nested
    @DisplayName("over a channel")
    class Channel {
        @Test
        @DisplayName("writes from the channel's position and leaves the channel open")
        void writes() throws IOException {
            var channel = new MemoryChannel();
            channel.write(ByteBuffer.wrap(new byte[]{1, 2}));
            var sink = MediaSink.of(channel);
            assertEquals(2, sink.position());
            sink.write(HELLO);
            sink.close();
            assertTrue(channel.isOpen());
            assertArrayEquals(new byte[]{1, 2, 'h', 'e', 'l', 'l', 'o'}, channel.toByteArray());
        }
    }

    @Nested
    @DisplayName("over a stream")
    class Stream {
        @Test
        @DisplayName("appends, counts its position and accepts only its current position")
        void appends() {
            var bytes = new ByteArrayOutputStream();
            var sink = MediaSink.of(bytes);
            assertFalse(sink.isSeekable());
            sink.write(HELLO);
            sink.write(ByteBuffer.allocateDirect(3).put(new byte[]{'a', 'b', 'c'}).flip());
            assertEquals(8, sink.position());
            sink.position(8);
            var failure = assertThrows(MediaException.Unsupported.class, () -> sink.position(0));
            assertEquals("non-seekable output", failure.format());
            assertArrayEquals(new byte[]{'h', 'e', 'l', 'l', 'o', 'a', 'b', 'c'}, bytes.toByteArray());
        }

        @Test
        @DisplayName("flushes but does not close the stream, and wraps its failures")
        void closes() {
            var flushed = new boolean[1];
            var closed = new boolean[1];
            var stream = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("broken");
                }

                @Override
                public void flush() {
                    flushed[0] = true;
                }

                @Override
                public void close() {
                    closed[0] = true;
                }
            };
            var sink = MediaSink.of(stream);
            assertThrows(MediaException.Io.class, () -> sink.write(HELLO));
            sink.close();
            sink.close();
            assertTrue(flushed[0]);
            assertFalse(closed[0]);
        }
    }

    @Test
    @DisplayName("hands out the in-memory sink type from memory()")
    void memoryType() {
        var sink = MediaSink.memory();
        assertSame(ByteArrayMediaSink.class, sink.getClass());
    }
}
