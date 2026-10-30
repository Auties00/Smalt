package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.MemoryChannel;
import com.github.auties00.smalt.io.MediaSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The media data spool: copied and held segments, in memory and spilled past the memory limit,
 * come out in append order.
 */
@DisplayName("MP4 media data spool")
class MdatSpoolTest {
    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, 1, 70_000, 3 << 20, MdatSpool.MEMORY_LIMIT, MdatSpool.MEMORY_LIMIT + 1, (MdatSpool.MEMORY_LIMIT * 3) / 2})
    @DisplayName("transfers exactly the appended bytes, or any range of them, whether written, held or spilled")
    void roundTrip(int total) {
        var random = new SplittableRandom(total);
        var expected = new ByteArrayOutputStream();
        try (var spool = new MdatSpool(null)) {
            var appended = 0;
            while (appended < total) {
                var length = Math.min(total - appended, random.nextInt(4) == 0 ? random.nextInt(2 << 20) : random.nextInt(9000));
                var data = new byte[length + 8];
                random.nextBytes(data);
                if (random.nextBoolean()) {
                    var held = Arrays.copyOf(data, length);
                    spool.hold(held);
                    expected.writeBytes(held);
                } else {
                    spool.write(data, 5, length);
                    expected.write(data, 5, length);
                    // a writer may reuse its array after write returns
                    Arrays.fill(data, (byte) 0x5A);
                }
                appended += length;
            }
            assertEquals(total, spool.size());
            var output = new MemoryChannel();
            spool.transferTo(MediaSink.of(output));
            assertArrayEquals(expected.toByteArray(), output.toByteArray());
            var bytes = expected.toByteArray();
            for (var round = 0; round < 12 && total > 0; round++) {
                var from = random.nextInt(total);
                var length = random.nextInt(total - from + 1);
                var range = new MemoryChannel();
                spool.transferTo(MediaSink.of(range), from, length);
                assertArrayEquals(Arrays.copyOfRange(bytes, from, from + length), range.toByteArray(),
                        "range " + from + " + " + length);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> spool.transferTo(MediaSink.of(new MemoryChannel()), total, 1));
        }
    }
}
