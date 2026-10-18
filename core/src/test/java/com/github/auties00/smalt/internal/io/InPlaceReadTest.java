package com.github.auties00.smalt.internal.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-place reading of array-backed sources and the sliding read-ahead window, checked by
 * replaying random operation sequences against an equivalent source that is not array backed
 * and comparing every result, position and failure message.
 */
@DisplayName("in-place and read-ahead reads")
class InPlaceReadTest {
    /** A plain source over an array that is not {@link ArrayBacked} and delivers short reads. */
    static final class PlainSource implements MediaSource {
        private final byte[] data;
        private final int chunk;
        int reads;
        long lastEnd = -1;
        int backward;

        PlainSource(byte[] data, int chunk) {
            this.data = data;
            this.chunk = chunk;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (position >= data.length) {
                return length == 0 ? 0 : -1;
            }
            var count = (int) Math.min(Math.min(length, chunk), data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, count);
            reads++;
            if (lastEnd >= 0 && position < lastEnd) {
                backward++;
            }
            lastEnd = position + count;
            return count;
        }

        @Override
        public void close() {
        }
    }

    static byte[] random(SplittableRandom random, int length) {
        var data = new byte[length];
        for (var i = 0; i < length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
        return data;
    }

    static List<String> replay(SourceReader reader, long seed, int operations, int size) {
        var random = new SplittableRandom(seed);
        var log = new ArrayList<String>();
        for (var step = 0; step < operations; step++) {
            try {
                switch (random.nextInt(9)) {
                    case 0 -> log.add("u8 " + reader.u8());
                    case 1 -> log.add("u16 " + reader.u16be() + " " + reader.u16le());
                    case 2 -> log.add("u24 " + reader.u24be() + " " + reader.u24le());
                    case 3 -> log.add("u32 " + reader.u32be() + " " + reader.u32le() + " " + reader.u64be());
                    case 4 -> {
                        var target = new byte[random.nextInt(9000)];
                        reader.readFully(target, 0, target.length);
                        log.add("fully " + Arrays.hashCode(target));
                    }
                    case 5 -> log.add("bytes " + Arrays.hashCode(reader.bytes(random.nextInt(5000), 1 << 20)));
                    case 6 -> {
                        reader.seek(random.nextInt(size + 20));
                        log.add("seek");
                    }
                    case 7 -> {
                        reader.skip(random.nextInt(3000));
                        log.add("skip");
                    }
                    default -> log.add("end " + reader.atEnd() + " " + reader.remaining());
                }
            } catch (MediaException exception) {
                log.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
            log.add("at " + reader.position());
        }
        return log;
    }

    @Nested
    @DisplayName("SourceReader")
    class Reader {
        @ParameterizedTest(name = "buffer {0}")
        @ValueSource(ints = {16, 100, 4096, 65536})
        @DisplayName("reads an array-backed source in place with the results of a copying reader")
        void sameAsCopying(int bufferSize) {
            for (var seed = 0; seed < 40; seed++) {
                var random = new SplittableRandom(seed);
                var data = random(random, 1 + random.nextInt(40_000));
                var offset = random.nextInt(50);
                var padded = new byte[offset + data.length + 7];
                System.arraycopy(data, 0, padded, offset, data.length);
                var direct = new SourceReader(MediaSource.of(padded, offset, data.length), bufferSize);
                var copying = new SourceReader(new PlainSource(data, Integer.MAX_VALUE), bufferSize);
                assertEquals(replay(copying, seed, 400, data.length), replay(direct, seed, 400, data.length), "seed " + seed);
            }
        }
    }

    @Nested
    @DisplayName("ReadAheadCache")
    class Cache {
        @ParameterizedTest(name = "chunk {0}")
        @ValueSource(ints = {1, 7, 1000, Integer.MAX_VALUE})
        @DisplayName("returns the source bytes for every read, in memory and through a window")
        void sameBytes(int chunk) {
            for (var seed = 0; seed < 30; seed++) {
                var random = new SplittableRandom(seed);
                var data = random(random, 1 + random.nextInt(200_000));
                var windowed = new ReadAheadCache(new PlainSource(data, chunk), 4096, 1024);
                var inPlace = new ReadAheadCache(MediaSource.of(data), 4096, 1024);
                var position = 0L;
                for (var step = 0; step < 3000; step++) {
                    position = random.nextInt(10) == 0 ? random.nextInt(data.length + 10)
                            : Math.max(0, position + random.nextInt(3000) - 900);
                    var length = random.nextInt(10) == 0 ? random.nextInt(9000) : random.nextInt(40);
                    for (var cache : new ReadAheadCache[]{windowed, inPlace}) {
                        var target = new byte[length + 3];
                        var read = cache.readUpTo(position, target, 3, length);
                        var expected = (int) Math.max(0, Math.min(length, data.length - position));
                        assertEquals(expected, read, "read at " + position);
                        assertArrayEquals(Arrays.copyOfRange(data, (int) Math.min(position, data.length),
                                (int) Math.min(position, data.length) + expected), Arrays.copyOfRange(target, 3, 3 + read));
                    }
                }
            }
        }

        @ParameterizedTest(name = "chunk {0}")
        @ValueSource(ints = {1, 1000, Integer.MAX_VALUE})
        @DisplayName("never fills its window past the limit and reads past the limit straight from the source")
        void limit(int chunk) {
            var data = random(new SplittableRandom(4), 300_000);
            var limit = 123_457;
            var reachedBeforeLimit = new long[1];
            var plain = new PlainSource(data, chunk);
            var source = new MediaSource() {
                @Override
                public long size() {
                    return plain.size();
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    var read = plain.read(position, buffer, offset, length);
                    if (position < limit && read > 0) {
                        reachedBeforeLimit[0] = Math.max(reachedBeforeLimit[0], position + read);
                    }
                    return read;
                }

                @Override
                public void close() {
                }
            };
            var cache = new ReadAheadCache(source, 64 * 1024, 0, limit);
            var random = new SplittableRandom(chunk);
            for (var position = 0L; position < limit; ) {
                var length = (int) Math.min(limit - position, 1 + random.nextInt(9000));
                var target = new byte[length];
                assertEquals(length, cache.readUpTo(position, target, 0, length));
                assertArrayEquals(Arrays.copyOfRange(data, (int) position, (int) position + length), target);
                position += length;
            }
            assertEquals(limit, reachedBeforeLimit[0], "the window stops at the limit");
            var after = new byte[500];
            assertEquals(500, cache.readUpTo(limit + 10, after, 0, 500));
            assertArrayEquals(Arrays.copyOfRange(data, limit + 10, limit + 510), after);
            var tail = new byte[100];
            assertEquals(70, cache.readUpTo(data.length - 70, tail, 0, 100));
        }

        @ParameterizedTest(name = "chunk {0}")
        @ValueSource(ints = {100, 65536})
        @DisplayName("reads the source strictly forward for probe-ahead, read-behind access")
        void sequential(int chunk) {
            var data = random(new SplittableRandom(1), 1 << 20);
            var source = new PlainSource(data, chunk);
            var cache = new ReadAheadCache(source, 64 * 1024, 8191);
            var header = new byte[7];
            var frame = new byte[8191];
            for (var position = 0L; position + 8191 * 2 < data.length; position += 700 + (position % 7000)) {
                cache.readUpTo(position, header, 0, 7);
                cache.readUpTo(position + 7000, header, 0, 7);
                cache.readUpTo(position + 7, frame, 0, 6000);
            }
            assertEquals(0, source.backward, "backward reads");
            assertTrue(source.reads <= data.length / Math.min(chunk, 64 * 1024) * 5 / 4 + 32, "reads " + source.reads);
        }
    }
}
